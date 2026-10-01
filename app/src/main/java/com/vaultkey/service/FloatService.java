package com.vaultkey.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.ui.UnlockActivity;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Match;
import com.vaultkey.util.Skin;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮窗：在其他应用的登录界面上方浮一个小窗，直接显示匹配到的账号。
 *
 * 使用场景：自动填充偶尔识别不出自定义输入框（游戏、WebView、Flutter 页面），
 * 这时填充不弹出，用户只能切回密盒查、再切回去手打。悬浮窗让这一步变成
 * 「点开小窗 → 复制 → 粘贴」，全程不离开当前应用。
 *
 * 实现要点：
 *  - 用 TYPE_APPLICATION_OVERLAY（Android 8+ 必须），需要 SYSTEM_ALERT_WINDOW 权限
 *  - 前台服务 + 常驻通知，否则会被系统回收
 *  - 悬浮球可拖动；点击展开面板，再点收起
 *  - 面板内容按「当前前台包名 / 域名」匹配，数据来源与自动填充共用 Match
 */
public final class FloatService extends Service {

    private static final String CH = "float";
    private static final int NOTI = 71;

    public static final String ACTION_SHOW = "com.vaultkey.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.vaultkey.FLOAT_HIDE";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_DOMAIN = "domain";
    /** 指定单条：传 uuid 时不再做匹配，直接浮这一条 */
    public static final String EXTRA_UUID = "uuid";

    private WindowManager wm;
    private com.vaultkey.util.DragLayout ball;          // 悬浮球
    private com.vaultkey.util.DragLayout panel;         // 展开的面板
    private WindowManager.LayoutParams lpBall, lpPanel;
    private boolean panelShown;
    private String curPkg = "", curDomain = "", curUuid = "";
    private boolean needUnlock;
    private Db.Entry pinned;
    private final Handler h = new Handler(Looper.getMainLooper());

    /* ---------------- 生命周期 ---------------- */

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startFg();
    }

    /**
     * 启动前台服务。
     *
     * Android 14(targetSdk 34) 硬性要求：
     *   1. manifest 必须声明 foregroundServiceType
     *   2. 必须声明该类型对应的 FOREGROUND_SERVICE_* 权限（否则 SecurityException）
     *   3. startForeground() 运行时传入的类型必须是 manifest 声明的子集
     *
     * 这里传 shortService：本服务是「浮出来取一次账号就走」的短任务，
     * 官方定义它约 3 分钟上限且**不需要任何类型专用权限**，
     * 因此即使某台设备的 specialUse 权限校验异常也不会崩。
     *
     * 外层再包一层 try-catch：万一某个 ROM 仍然拒绝，
     * 也不能让整个服务创建失败导致应用闪退 —— 降级为不带前台通知继续跑浮窗。
     */
    private void startFg() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI, noti(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI, noti(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
            } else {
                startForeground(NOTI, noti());
            }
        } catch (Throwable t) {
            try { startForeground(NOTI, noti()); } catch (Throwable t2) { /* 降级失败也不崩 */ }
        }
    }

    /**
     * shortService 超时回调（仅 Android 14+ 会触发）。
     * 官方要求：收到后必须立即 stopSelf() 或 stopForeground()，
     * 否则应用会被缓存并最终 ANR。
     */
    @Override public void onTimeout(int startId) {
        super.onTimeout(startId);
        hideAll();
        stopSelf();
    }


    @Override public int onStartCommand(Intent i, int flags, int start) {
        if (i != null) {
            String a = i.getAction();
            if (ACTION_HIDE.equals(a)) {
                hideAll();
                stopSelf();
                return START_NOT_STICKY;
            }
            curPkg = i.getStringExtra(EXTRA_PKG) == null ? "" : i.getStringExtra(EXTRA_PKG);
            curDomain = i.getStringExtra(EXTRA_DOMAIN) == null ? "" : i.getStringExtra(EXTRA_DOMAIN);
            curUuid = i.getStringExtra(EXTRA_UUID) == null ? "" : i.getStringExtra(EXTRA_UUID);
        }
        /* 指定单条：先查出来，查不到就退化成按包名/域名匹配 */
        pinned = null;
        if (!curUuid.isEmpty()) {
            try {
                java.util.List<Db.Entry> all = Db.get(this).list(null, false, false, null, 0);
                for (Db.Entry x : all) {
                    if (curUuid.equals(x.uuid)) { pinned = x; break; }
                }
            } catch (Exception ignored) { }
        }
        showBall();
        bumpAutoHide();
        /* 指定了条目、或有匹配结果，就自动展开，省一次点击 */
        if (pinned != null
                || ((!curPkg.isEmpty() || !curDomain.isEmpty())
                    && !Match.find(this, curDomain, curPkg).isEmpty())) {
            showPanel();
        }
        /* shortService 不支持粘性前台服务（官方文档明确），
           被杀后不应自动重启 —— 悬浮窗本来就是"用完即走"，
           自动复活反而会在用户不知情时重新挂出一个窗口和通知。 */
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        cancelAutoHide();
        h.removeCallbacks(clearClip);
        hideAll();
        super.onDestroy();
    }

    /* ---------------- 悬浮球 ---------------- */

    private void showBall() {
        if (ball != null) return;
        try {
            int size = Ui.dp(this, 42);
            ball = new com.vaultkey.util.DragLayout(this);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(Ico.get(this, "shield", 0xFFFFFFFF, 21));
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            g.setCornerRadius(size / 2f);
            int ac = Skin.accent(this);
            g.setColor(0xE6000000 | (ac & 0x00FFFFFF));
            g.setStroke(Math.max(1, Ui.dp(this, 1)), 0x66FFFFFF);
            wrap.setBackground(g);
            wrap.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            wrap.addView(iv);
            ((LinearLayout) ball).addView(wrap);

            lpBall = params(size, size, Gravity.TOP | Gravity.START);
            lpBall.x = Prefs.getI("float_x", Ui.dp(this, 8));
            lpBall.y = Prefs.getI("float_y", Ui.dp(this, 160));
            wm.addView(ball, lpBall);
            attachDrag(ball, lpBall, true);
        } catch (Exception e) {
            toast("悬浮窗显示失败，请检查权限");
        }
    }

    /**
     * 拖动 + 「拖到边缘松手关闭」。
     *
     * 关闭方式改成拖到屏幕边缘，是为了省掉底部那排按钮（占约 34dp）。
     * 悬浮球本来就是可拖的，把它拖到边缘「丢掉」比点一个小按钮更符合直觉。
     *
     * 判定：球心进入左右 56dp 或底部 80dp 的区域就算「贴边」，
     * 此时球体变红、缩小，给出明确反馈；在这个状态下松手即关闭。
     * 只贴边不松手不会关闭 —— 避免手滑拖过去就误关。
     */
    /**
     * 拖动 + 长按 + 「拖到边缘松手关闭」。
     *
     * 这一版重做了手势处理，修两个 bug：
     *
     * **bug 1：长按不触发。**
     * 根因：View 的长按检测（CheckForLongPress）实现在 View.onTouchEvent() 里。
     * 而一旦设置了 OnTouchListener，且它在 ACTION_DOWN 返回 true，
     * View 自己的 onTouchEvent() 就**根本不会被调用**，长按回调永远等不到。
     * 所以问题不是「没写长按」，是被触摸监听吃掉了。
     * 修法：改用 GestureDetector —— 它内部就是官方那套延时检测，
     * 且移动超过阈值会自动取消，比手写 Handler 更可靠。
     *
     * **bug 2：无法自由移动。**
     * 根因：拖动过程中 applyEdgeState() 会启动 ViewPropertyAnimator 动画
     * （scaleX/scaleY/alpha）。动画运行期间会持续修改 View 的变换并重绘，
     * 与 updateViewLayout() 的窗口位置更新互相打架，表现为拖不动或一跳一跳。
     * 修法：贴边反馈改用 **setAlpha + ColorFilter**（一次赋值，无动画、
     * 不产生持续重绘），只在「进入 / 离开」贴边区的那一刻执行一次。
     */
    /**
     * 绑定拖动手势。
     *
     * 用的是 DragLayout 的回调，而不是直接给 View 挂 OnTouchListener ——
     * 后者会在 ACTION_DOWN 就吃掉事件，导致面板内部的「复制」按钮点不了。
     * DragLayout 靠 onInterceptTouchEvent 区分「点击」和「拖动」，两者可以共存。
     */
    private void attachDrag(final com.vaultkey.util.DragLayout v,
                            final WindowManager.LayoutParams lp, final boolean isBall) {
        v.setCallback(new com.vaultkey.util.DragLayout.Callback() {
            float px, py;
            boolean nearEdge;

            @Override public void onDown(float rawX, float rawY) {
                bumpAutoHide();
                px = lp.x; py = lp.y;
                nearEdge = false;
            }

            @Override public void onMove(float dx, float dy, float rawX, float rawY) {
                int oldX = lp.x, oldY = lp.y;
                lp.x = (int) (px + dx);
                lp.y = (int) (py + dy);

                /* 面板跟着球一起走：面板自己虽然也能拖，
                   但拖球时保持两者相对位置，视觉才连贯 */
                if (isBall && panelShown && panel != null && lpPanel != null) {
                    lpPanel.x += (lp.x - oldX);
                    lpPanel.y += (lp.y - oldY);
                    try { wm.updateViewLayout(panel, lpPanel); } catch (Exception ignored) { }
                }
                /* 拖面板时，球也跟着（两者本来就是一组） */
                if (!isBall && ball != null && lpBall != null) {
                    lpBall.x += (lp.x - oldX);
                    lpBall.y += (lp.y - oldY);
                    try { wm.updateViewLayout(ball, lpBall); } catch (Exception ignored) { }
                }

                /* 只在进入 / 离开贴边区的那一刻改一次外观。
                   注意不能用动画：动画会持续重绘，与 updateViewLayout 打架，
                   表现为拖不动或一跳一跳。 */
                boolean edge = isNearEdge(rawX, rawY);
                if (edge != nearEdge) {
                    nearEdge = edge;
                    applyEdgeState(v, edge);
                }
                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
            }

            @Override public void onUp(float dx, float dy, float rawX, float rawY,
                                       boolean moved, boolean longPress, boolean cancelled) {
                if (!cancelled && nearEdge && moved) {
                    /* 在贴边状态下松手 → 关闭整个悬浮窗 */
                    applyEdgeState(v, false);
                    toast("已关闭悬浮窗");
                    hideAll();
                    stopSelf();
                    return;
                }
                if (isBall && moved) {
                    Prefs.putI("float_x", lp.x);
                    Prefs.putI("float_y", lp.y);
                }
                /* 无论如何都复位，否则下次拖动会带着旧状态 */
                applyEdgeState(v, false);
                nearEdge = false;
            }

            @Override public void onLongPress() { showBallMenu(); }

            @Override public void onTap() { onBallClick(); }
        });
    }

    /** 点击悬浮球：展开 / 收起面板，未解锁则先去解锁 */
    private void onBallClick() {
        bumpAutoHide();
        if (needUnlock) {
            /* 用户主动点了悬浮球 —— 这是明确交互，此时跳转才合规 */
            needUnlock = false;
            startActivity(new Intent(FloatService.this, UnlockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return;
        }
        if (panelShown) hidePanel(); else showPanel();
    }

    /** 长按菜单：把从底部栏省掉的「搜索全部 / 收起 / 关闭」放回这里 */
    private void showBallMenu() {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (panelShown) items.add("收起面板");
        items.add("在密盒中搜索");
        items.add("关闭悬浮窗");
        final String[] arr = items.toArray(new String[0]);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setItems(arr, (d, w) -> {
                    String it = arr[w];
                    if (it.equals("收起面板")) { hidePanel(); return; }
                    if (it.equals("在密盒中搜索")) {
                        hideAll();
                        startActivity(new Intent(this, UnlockActivity.class)
                                .putExtra("search", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        return;
                    }
                    toast("已关闭悬浮窗");
                    hideAll();
                    stopSelf();
                });

        try {
            android.app.Dialog dg = b.create();
            /* 从 Service 弹窗必须是系统级窗口，否则会 BadTokenException */
            if (Build.VERSION.SDK_INT >= 26) {
                dg.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dg.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
            }
            dg.show();
        } catch (Exception e) {
            /* 弹不出来就只提示，不要把悬浮窗关掉 —— 那样会让人以为手滑了 */
            toast("菜单显示失败，可拖到屏幕边缘关闭");
        }
        bumpAutoHide();
    }

    /** 球心是否贴近屏幕边缘（左右 56dp / 底部 80dp） */
    private boolean isNearEdge(float rawX, float rawY) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int edgeX = Ui.dp(this, 56);
        int edgeY = Ui.dp(this, 80);
        return rawX < edgeX
                || rawX > dm.widthPixels - edgeX
                || rawY > dm.heightPixels - edgeY;
    }

    /**
     * 贴边反馈：变红 + 半透明。
     *
     * 关键：**不能用动画**。拖动时每帧都在 updateViewLayout 改位置，
     * 若同时有 ViewPropertyAnimator 在跑，两者会互相覆盖导致拖不动。
     * 所以这里只用 setAlpha + ColorFilter —— 一次赋值，不产生持续重绘。
     */
    private void applyEdgeState(View v, boolean on) {
        try {
            v.setAlpha(on ? 0.55f : 1f);
            /* 悬浮球结构是 LinearLayout → LinearLayout → ImageView，
               递归一层才能拿到真正显示图标的那个 View */
            View iv = findImageView(v);
            if (iv != null) {
                ((ImageView) iv).setColorFilter(on ? 0xFFFF5555 : 0,
                        android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
        } catch (Exception ignored) { }
    }

    private static View findImageView(View v) {
        if (v instanceof ImageView) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findImageView(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /* ---------------- 面板 ---------------- */

    private void showPanel() {
        if (panelShown) { refreshPanel(); return; }
        if (wm == null) return;
        /*
         * 未解锁：不直接 startActivity。
         *
         * Android 14 收紧了后台启动 Activity —— 从服务里自发跳转没有豁免，
         * FLAG_ACTIVITY_NEW_TASK 也不授予这个资格（它只解决任务栈问题）。
         * 而这次跳转发生在 onStartCommand，并非用户交互，属于高风险路径。
         *
         * 改成：把悬浮球标记为"待解锁"，用户点它时才跳转 —— 那就是明确的用户交互了。
         * 这样既合规，也避免了「正在用别的 App，密盒突然自己弹出来」的打扰。
         */
        if (Session.key() == null || Session.expired(Prefs.getI("lock_ms", 180000))) {
            needUnlock = true;
            toast("密盒已锁定，点一下悬浮球解锁");
            return;
        }
        try {
            panel = buildPanel();
            /* 最小化：宽度压到 236dp（屏宽 62%），只够放下一行账号 + 两个小按钮 */
            int w = Math.min(Ui.dp(this, 208),
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.55f));
            lpPanel = params(w, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            lpPanel.x = Prefs.getI("float_x", Ui.dp(this, 8));
            lpPanel.y = Prefs.getI("float_y", Ui.dp(this, 160)) + Ui.dp(this, 48);
            /* 别超屏幕底部 */
            int maxY = getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 200);
            if (lpPanel.y > maxY) lpPanel.y = Math.max(0, maxY);
            /* 面板自己也能拖。
               之前只有悬浮球挂了手势，面板没挂，所以"悬浮后想拖面板"是拖不动的。 */
            panel.setInnerScrollable(
                    com.vaultkey.util.DragLayout.hasScrollable(panel));
            attachDrag(panel, lpPanel, false);
            wm.addView(panel, lpPanel);
            panelShown = true;
            Ui.popIn(panel, 0);
        } catch (Exception e) {
            toast("面板显示失败");
        }
    }

    private void hidePanel() {
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
        }
        panel = null;
        panelShown = false;
    }

    private void refreshPanel() {
        if (!panelShown || panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;
        panelShown = false;
        showPanel();
    }

    private com.vaultkey.util.DragLayout buildPanel() {
        com.vaultkey.util.DragLayout root = new com.vaultkey.util.DragLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xF20B0F12);
        g.setCornerRadius(Ui.dp(this, 14));
        g.setStroke(Math.max(1, Ui.dp(this, 1)), 0x33FFFFFF);
        root.setBackground(g);


        List<Db.Entry> show = new ArrayList<>();
        if (pinned != null) {
            show.add(pinned);
        } else {
            for (Match.Hit h : Match.find(this, curDomain, curPkg)) show.add(h.e);
        }

        /* 多候选时给一行极简提示，告诉用户可以滚。单条时不显示。 */
        if (show.size() > 1) {
            TextView head = new TextView(this);
            head.setText(show.size() + " 个匹配");
            head.setTextSize(10f);
            head.setTextColor(0xFF8A9391);
            head.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
            root.addView(head);
        }
        if (show.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("无匹配");
            empty.setTextSize(11.5f);
            empty.setTextColor(0xFF9AA3A1);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            root.addView(empty);
        } else {
            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (final Db.Entry it : show) {
                list.addView(row(it));
            }
            sv.addView(list);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sv.setLayoutParams(sl);
            /* 最多显示 4 条的高度，超出滚动 */
            sv.setPadding(0, 0, 0, 0);
            /*
             * 高度策略：
             * - 只有 1 条（绝大多数场景）→ 完全自适应内容，不留一点空白
             * - 多条 → 限制约 2 条的高度，超出滚动，避免浮窗占半屏
             */
            int hMax = show.size() <= 1
                    ? LinearLayout.LayoutParams.WRAP_CONTENT
                    : Ui.dp(this, 116);
            root.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, hMax));
        }

        /* 底部栏已移除：原来有「搜索全部 / 收起」两个按钮，占约 34dp。
           收起改为把悬浮球拖到屏幕边缘（更符合"浮窗"的直觉），
           搜索全部在单条模式下也不是必需。 */
        return root;
    }

    /**
     * 条目布局：三行，每行内容 + 各自的「复制」按钮。
     *
     *   标题        [复制]
     *   账号        [复制]
     *   ••••••••   [复制]
     *
     * 之前把账号和密码合并成一行是为了省高度，但那样密码只能靠点整行切换、
     * 也没有独立复制。按你的截图改回三行独立 —— 高度从别处省：
     *   1. 去掉顶部「密盒 · xxx」头部（标题已在第一行，重复显示）
     *   2. 去掉底部「搜索全部 / 收起」栏（收起改为拖到边缘关闭）
     *   3. 去掉行间分隔线
     *   4. 行高与内边距压到最小
     */
    private View row(final Db.Entry e) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));

        final String title = e.title.isEmpty()
                ? (e.user.isEmpty() ? "未命名" : e.user) : e.title;
        r.addView(copyRow(title, 12.5f, true, 0xFFFFFFFF, title));

        if (!e.user.isEmpty()) {
            r.addView(copyRow(e.user, 11.5f, false, 0xFFB9C2C0, e.user));
        }

        if (!e.pass.isEmpty()) {
            /* 密码默认打码：悬浮窗浮在别的应用之上，可能被旁人看到或被截屏。
               点一下才显示明文，再点收回 —— 需要看清时用，平时不暴露。 */
            final String masked = dots(e.pass);
            final TextView tv = new TextView(this);
            tv.setText(masked);
            tv.setTextSize(11.5f);
            tv.setTextColor(0xFFB9C2C0);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(0, 0, 0, 0);
            tv.setOnClickListener(v -> {
                boolean isMasked = masked.equals(tv.getText().toString());
                tv.setText(isMasked ? e.pass : masked);
                tv.setTextColor(isMasked ? 0xFFFFFFFF : 0xFFB9C2C0);
                bumpAutoHide();
            });
            LinearLayout line = wrapCopy(tv, e.pass);
            r.addView(line);
        }
        return r;
    }

    /** 一行：左侧内容 + 右侧「复制」按钮 */
    private LinearLayout copyRow(String text, float size, boolean bold, int color, final String payload) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return wrapCopy(t, payload);
    }

    private LinearLayout wrapCopy(TextView t, final String payload) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(miniBtn("复制", v -> copy(payload)));
        return line;
    }

    private static String dots(String s) {
        int n = Math.min(s.length(), 12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('•');
        return sb.toString();
    }

    private TextView miniBtn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Skin.accent(this));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(999f);
        g.setColor(0x2200B8A0);
        t.setBackground(g);
        t.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (t.getParent() instanceof LinearLayout) { /* 由 addView 顺序决定间距 */ }
        lp.setMarginStart(Ui.dp(this, 4));
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private void copy(String s) {
        if (s == null || s.isEmpty()) { toast("没有内容"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("vaultkey", s));
            toast("已复制");
            /* 与列表页一致：到时自动清空剪贴板 */
            h.removeCallbacks(clearClip);
            h.postDelayed(clearClip, Prefs.getI("clip_sec", 45) * 1000L);
            /* 复制完就是"用完了"，重置自动收起倒计时 */
            bumpAutoHide();
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private final Runnable clearClip = new Runnable() {
        @Override public void run() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) { }
        }
    };

    /* ---------------- 工具 ---------------- */

    private WindowManager.LayoutParams params(int w, int h, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        /* 关键：让窗口外的触摸穿透给下面的应用。
                           没有它，悬浮窗会拦掉整屏触摸，用户没法操作背后的登录框。 */
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        /*
         * 这里**绝对不能**加 FLAG_WATCH_OUTSIDE_TOUCH。
         *
         * 它就是"拖不动 + 长按没反应"的真正根因：
         * 官方对该 flag 的说明是「你不会收到完整的 down/move/up 手势，
         * 只会收到首次按下的位置，以 ACTION_OUTSIDE 的形式」。
         *
         * 也就是说，手指按在悬浮窗上时，MotionEvent 的 action 是
         * ACTION_OUTSIDE(4)，而不是 ACTION_DOWN(0) ——
         * onTouch 的 switch 只有 DOWN/MOVE/UP/CANCEL 四个分支，
         * 收到的 4 全部落到 default，一次都没处理。
         * 于是起点记不下来、MOVE 收不到，自然纹丝不动。
         *
         * 这个 flag 的本意是监听"窗口外面"的点击（比如点外面自动收起），
         * 和拖动完全无关，加在这里纯属误用。
         */
        return lp;
    }

    private void hideAll() {
        hidePanel();
        if (ball != null && wm != null) {
            try { wm.removeView(ball); } catch (Exception ignored) { }
        }
        ball = null;
    }

    private Notification noti() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "悬浮窗", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
        Intent i = new Intent(this, UnlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 77, i, f);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CH)
                : new android.app.Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("密盒悬浮窗已开启")
                .setContentText("点此回到密盒，或点通知关闭悬浮窗")
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { }
    }

    /* ---------------- 外部调用 ---------------- */

    public static boolean canDraw(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try { return android.provider.Settings.canDrawOverlays(c); } catch (Exception e) { return false; }
    }

    /** 请求悬浮窗权限（跳系统设置页） */
    public static void request(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
        } catch (Exception ignored) { }
    }

    /**
     * 「用完即走」：任何交互都会重置这个倒计时，超时自动收起并停掉服务。
     *
     * 目的：悬浮窗浮在别的应用之上，长期挂着既遮挡内容、
     * 又让通知栏常驻一条通知。既然典型用法是「浮出来 → 复制 → 走」，
     * 那就让它在最后一次操作后自动消失，不留残留。
     *
     * 时长取 90 秒：够切到目标 App、在登录框里粘两次，
     * 又短到不会长期占位。shortService 的 ~3 分钟上限是最后兜底。
     */
    private final Runnable autoHide = new Runnable() {
        @Override public void run() {
            hideAll();
            stopSelf();
        }
    };

    private void bumpAutoHide() {
        h.removeCallbacks(autoHide);
        int sec = Prefs.getI("float_auto_sec", 90);
        if (sec > 0) h.postDelayed(autoHide, sec * 1000L);
    }

    private void cancelAutoHide() { h.removeCallbacks(autoHide); }

    public static void show(Context c, String pkg, String domain) {
        try {
            Intent i = new Intent(c, FloatService.class);
            i.setAction(ACTION_SHOW);
            i.putExtra(EXTRA_PKG, pkg == null ? "" : pkg);
            i.putExtra(EXTRA_DOMAIN, domain == null ? "" : domain);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    /**
     * 浮出指定条目（列表右侧按钮用）。
     * 与 show() 的区别：不做匹配，直接显示这条。
     */
    public static void showEntry(Context c, String uuid) {
        try {
            Intent i = new Intent(c, FloatService.class);
            i.setAction(ACTION_SHOW);
            i.putExtra(EXTRA_UUID, uuid == null ? "" : uuid);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        try { c.startService(new Intent(c, FloatService.class).setAction(ACTION_HIDE)); }
        catch (Exception ignored) { }
    }

    /** 是否开启（开关状态） */
    public static boolean enabled() { return Prefs.getB("float_on", false); }

    public static void setEnabled(boolean on) { Prefs.putB("float_on", on); }

    /** 自动弹出：检测到登录框时自动展面板 */
    public static boolean autoPopup() { return Prefs.getB("float_auto", true); }

    public static void setAutoPopup(boolean on) { Prefs.putB("float_auto", on); }
}
