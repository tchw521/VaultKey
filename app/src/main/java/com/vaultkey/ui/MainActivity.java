package com.vaultkey.ui;

import android.content.Context;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import android.net.Uri;
import com.vaultkey.data.BackupDir;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.sync.Sync;
import com.vaultkey.util.Csv;
import com.vaultkey.util.BwJson;
import com.vaultkey.util.Ico;
import com.vaultkey.util.IndexBar;
import com.vaultkey.util.Ui;
import java.util.List;

/**
 * 主界面。
 * 密码箱页 = 常驻左侧分类导航 + 右侧账号列表（A-Z 索引）。
 * 左侧首项「全部」即密码列表（不过滤分类）。
 * 底部两个 Tab：密码箱 / 设置中心。
 */
public final class MainActivity extends BaseActivity implements ToolsView.Host {
    private static final int N = 3;
    private static final String[] TABS = {"密码箱", "链接库", "设置中心"};
    private static final String[] TAB_ICONS = {"box", "link", "settings"};

    private FrameLayout content;
    private final View[] pages = new View[N];
    private final ImageView[] tabIcons = new ImageView[N];
    private final TextView[] tabTexts = new TextView[N];
    private final View[] tabDots = new View[N];
    private int cur = 0;

    private ImageView menuBtn;
    private View fab;

    private SettingsView settings;
    private TextView topTitle;
    /** 顶部那一行（☰ + 标题）。设置中心不需要，整行隐藏 */
    private LinearLayout topBar;
    private LinearLayout batchBar;
    private TextView batchCount;
    /**
     * 批量条按钮的显隐分组。
     * hideInTrash：回收站里点了没意义的（分类 / 标签 / 收藏）
     * showInTrash：只有回收站才需要的（恢复）
     */
    private final java.util.List<View> hideInTrash = new java.util.ArrayList<>();
    private final java.util.List<View> showInTrash = new java.util.ArrayList<>();

    /* 密码箱(kind=0) 与 链接库(kind=1) 共用同一个 VaultPage 实现：
       界面与交互完全一致，差异只在内容类型与是否含卡包。 */
    private VaultPage box;
    private VaultPage link;

    /** 当前操作的页（设置页时回落到密码箱，供批量条等使用） */
    private VaultPage page() { return cur == 1 ? link : box; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Session.key() == null) {
            startActivity(new Intent(this, UnlockActivity.class));
            finish();
            return;
        }
        /* 修一次存量数据：早期新建的链接被挂到了密码箱的分类下，
           在链接库里按分类找不到。改挂回本库默认分类。 */
        Db.get(this).fixCrossKindCats();
        build();
    }

    private void build() {
        /* ---------- 顶栏 ---------- */
        topBar = new LinearLayout(this);
        LinearLayout bar = topBar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 10);
        bar.setLayoutParams(bl);

        menuBtn = new ImageView(this);
        menuBtn.setImageDrawable(Ico.get(this, "menu", Ui.attr(this, R.attr.textColor), 24));
        menuBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        Ui.press(menuBtn);
        menuBtn.setOnClickListener(v -> {
            page().toggleNav();
        });
        bar.addView(menuBtn);

        topTitle = title("全部", 20);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 14));
        topTitle.setLayoutParams(tl);
        bar.addView(topTitle);

        /* 右上角原本有「选择」和「新增账号」两个按钮，既挤又不好看：
           - 新增账号：右下角悬浮加号已能替代，去掉
           - 选择（批量）：改成长按条目 → 「选择多项」，或长按底部「密码箱」标签 */
        body.addView(bar);

        /* ---------- 内容区 ---------- */
        content = new SwipeHost(this);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cl.topMargin = Ui.dp(this, 8);
        content.setLayoutParams(cl);
        body.addView(content);

        /* 密码箱与链接库是同一个 VaultPage 的两个实例，只差 kind。
           这样两边的界面、交互、样式永远同步，不会各写一套跑偏。 */
        VaultPage.Host ph = new VaultPage.Host() {
            @Override public void onState() { onPageState(); }
            /*
             * 不再切到设置中心，直接在当前页弹分类对话框。
             *
             * 之前是 switchTo(2) 先跳设置页再弹窗 —— 弹窗一关，
             * 人就留在设置中心了，而不是回到刚才的密码箱/链接库。
             * 弹窗只是个 AlertDialog，根本不需要先切页。
             */
            @Override public void goCats(int k) {
                settings.showCats(k, new Runnable() {
                    @Override public void run() {
                        /* 分类改完，两套导航都要重画 */
                        box.refresh();
                        link.refresh();
                    }
                });
            }
            @Override public void goRecover() {
                startActivity(new Intent(MainActivity.this, RecoverActivity.class));
            }
            @Override public void addCard() { addCardDialog(); }
            @Override public void onSelChanged() { refreshBatchBar(); }
        };
        box = new VaultPage(this, 0, ph);
        link = new VaultPage(this, 1, ph);
        pages[0] = box.view();
        pages[1] = link.view();
        settings = new SettingsView(this, this);
        pages[2] = settings.view();
        for (int i = 0; i < N; i++) {
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            content.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        buildBatchBar();
        buildBottomBar();
        buildFab();
        switchTo(0);
        Ui.popIn(bar, 0);
    }


    /**
     * 右下角悬浮加号。
     *
     * 这次重构把它从 buildBoxPage() 里搬出来了 —— 之前它建在密码箱页内部，
     * 而链接库页没有，导致删除 buildBoxPage 后 fab 为 null，
     * switchTo(0) 一调用就 NullPointerException（启动即崩溃）。
     * 现在它是 Activity 层级的悬浮按钮，两页共用，由 switchTo 控制显隐。
     */
    private void buildFab() {
        fab = new ImageView(this);
        int fs = Ui.dp(this, 54);
        fab.setBackground(Ui.gradient(new int[]{accent(), accent2()}, fs / 2f, 45));
        fab.setElevation(Ui.dp(this, 8));
        ((ImageView) fab).setImageDrawable(Ico.get(this, "add", Color.WHITE, 25));
        int pd = Ui.dp(this, 15);
        fab.setPadding(pd, pd, pd, pd);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(
                fs, fs, Gravity.BOTTOM | Gravity.END);
        fl.setMargins(0, 0, Ui.dp(this, 10), Ui.dp(this, 86));
        fab.setLayoutParams(fl);
        Ui.press(fab);
        fab.setOnClickListener(v -> page().add());
        root.addView(fab, fl);
    }

    /** VaultPage 状态变化（切分类、开关导航、列表增删）时同步顶栏与按钮 */
    private void onPageState() {
        if (cur != 0 && cur != 1) return;
        /* 这些控件可能还没建好（VaultPage 构建时会回调），逐个判空 */
        if (topTitle != null) topTitle.setText(page().title());
        if (menuBtn != null) {
            menuBtn.setImageDrawable(VaultPage.menuIcon(this, page().isNavShown()));
        }
        if (fab != null) {
            fab.setVisibility(page().list().isSelectMode() ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 左右滑动手势容器。
     *
     * 规则（按起始点分区，避免两种手势打架）：
     *   - 起始点在屏幕左缘（64dp 内）：右滑开导航、左滑关导航
     *   - 其余区域：左滑去「设置中心」、右滑回「密码箱」
     *
     * 关键取舍：必须重写 onInterceptTouchEvent 而不是给外层挂 OnTouchListener。
     * 后者在列表竖向滚动时收不到 ACTION_UP（事件被子 ListView 消费掉），
     * 而且无法区分「用户想竖向滚列表」还是「想横向切页」。
     * 这里只在「横向位移明显大于纵向」时才拦截，竖向滚动照常交给列表。
     */
    private final class SwipeHost extends FrameLayout {
        private float sx, sy;
        private boolean drag;
        private final int slop;

        SwipeHost(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getX();
                    sy = e.getY();
                    drag = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!drag) {
                        float dx = Math.abs(e.getX() - sx);
                        float dy = Math.abs(e.getY() - sy);
                        /* 横向要明显强于纵向才算切页手势 */
                        if (dx > slop * 2 && dx > dy * 1.4f) {
                            drag = true;
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = false;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (drag && a == MotionEvent.ACTION_UP) {
                    float dx = e.getX() - sx;
                    float min = Ui.dp(MainActivity.this, 56);
                    if (Math.abs(dx) > min) {
                        /*
                         * 只用屏幕中间的手势，按「当前状态」判定意图，不做边缘分区。
                         * 优先级：能开/关导航就先管导航，管不了才切页。
                         *
                         *   密码箱页 · 右滑 → 开导航（导航已开则去链接库）
                         *   密码箱页 · 左滑 → 关导航（导航已关则去链接库）
                         *   链接库   · 右滑 → 回密码箱，左滑 → 去设置中心
                         *   设置中心 · 右滑 → 回链接库
                         *
                         * 三页按 密码箱(0) → 链接库(1) → 设置中心(2) 顺序排列，
                         * 右滑往回、左滑往前，符合直觉。
                         */
                        /*
                         * 密码箱与链接库行为一致：先管自己的导航，管不了才切页。
                         *   右滑 → 开导航（已开则往左切一页）
                         *   左滑 → 关导航（已关则往右切一页）
                         * 设置页：右滑回链接库。
                         */
                        if (cur == 0 || cur == 1) {
                            if (dx > 0) {
                                if (!page().isNavShown()) page().setNav(true);
                                else switchTo(cur == 0 ? 1 : 0, 1);
                            } else {
                                if (page().isNavShown()) page().setNav(false);
                                else switchTo(cur == 0 ? 1 : 2, -1);
                            }
                        } else {
                            switchTo(1, 1);
                        }
                    }
                }
                drag = false;
                return true;
            }
            return true;
        }
    }



    /* ---------------- 密码箱页 ---------------- */






    /** 导航开合：内容区宽度变化 + 淡入，避免生硬地跳一下 */

    /* ---------------- 左侧导航内容 ---------------- */









    /**
     * @param cardsToo 回收站专用：true 表示展示「已删除的卡片」而非「已删除的账号」。
     *                 之前回收站只显示账号，被删掉的卡片既看不到也恢复不了，等于永久丢失。
     */

    /** 回收站顶部：账号 / 卡片 二选一 */


    /* ---------------- 底部 Tab ---------------- */

    private void buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.attr(this, R.attr.cardColor2));
        g.setCornerRadius(Ui.dp(this, 22));
        g.setStroke(Math.max(1, Ui.dp(this, 1)), Ui.attr(this, R.attr.strokeColor));
        bar.setBackground(g);

        for (int i = 0; i < N; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 54), 1f));

            View dot = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 3));
            dl.bottomMargin = Ui.dp(this, 4);
            dot.setLayoutParams(dl);
            dot.setBackground(Ui.roundRect(Color.TRANSPARENT, 2f));
            tab.addView(dot);
            tabDots[i] = dot;

            ImageView ic = new ImageView(this);
            ic.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            tab.addView(ic);
            tabIcons[i] = ic;

            TextView tx = new TextView(this);
            tx.setText(TABS[i]);
            tx.setTextSize(11);
            tx.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            xl.topMargin = Ui.dp(this, 2);
            tx.setLayoutParams(xl);
            tab.addView(tx);
            tabTexts[i] = tx;

            Ui.press(tab);
            tab.setOnClickListener(v -> switchTo(idx));
            /*
             * 长按底部标签 → 切换批量多选。
             * 密码箱和链接库都支持，设置中心不参与（那里是设置项，没有条目可选）。
             *
             * 这是「选择」入口从右上角挪走后的去处之一：
             * 单手拇指本来就落在底栏，比够右上角顺手。
             */
            tab.setOnLongClickListener(v -> {
                if (idx == 0 || idx == 1) {
                    switchTo(idx);
                    toggleSelectMode();
                    return true;
                }
                return false;
            });
            bar.addView(tab);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 6);
        bl.bottomMargin = Ui.dp(this, 8);
        body.addView(bar, bl);
    }


    private void buildBatchBar() {
        batchBar = new LinearLayout(this);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        batchBar.setGravity(Gravity.CENTER_VERTICAL);
        batchBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.attr(this, R.attr.cardColor2));
        g.setCornerRadius(Ui.dp(this, 20));
        g.setStroke(Math.max(1, Ui.dp(this, 1)), accent());
        batchBar.setBackground(g);
        batchBar.setVisibility(View.GONE);

        batchCount = new TextView(this);
        batchCount.setTextSize(12.5f);
        batchCount.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        batchCount.setTextColor(accent());
        batchCount.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);
        batchBar.addView(batchCount);

        batchBar.addView(batchAct("全选", v -> page().list().selectAll(true)));
        batchBar.addView(batchAct("反选", v -> {
            java.util.Set<Long> cur = new java.util.LinkedHashSet<>(page().list().selected());
            page().list().selectAll(true);
            for (Long id : cur) page().list().selected().remove(id);
            page().list().setSelectMode(true);
            refreshBatchBar();
        }));
        /* 分类 / 标签 / 收藏 只在正常列表里有意义，回收站里隐藏，
           否则一排按钮大多点了没反应 */
        TextView bMove = batchAct("移动", v -> batchMove());
        TextView bTag = batchAct("标签", v -> batchTag());
        TextView bFav = batchAct("收藏", v -> {
            int n = Db.get(this).setFav(page().list().selected(), true);
            afterBatch("已收藏 " + n + " 个");
        });
        batchBar.addView(bMove);
        batchBar.addView(bTag);
        batchBar.addView(bFav);
        hideInTrash.add(bMove);
        hideInTrash.add(bTag);
        hideInTrash.add(bFav);
        final TextView bDel = batchAct("删除", v -> {
            final java.util.Set<Long> ids = new java.util.LinkedHashSet<>(page().list().selected());
            /* 回收站里的删除是"彻底删除"，不可恢复 —— 文案必须说清楚，
               否则用户以为是普通删除，点完就找不回来了 */
            boolean inTrash = page().list().isTrashMode();
            String thing = page().kind() == 1 ? "链接" : "账号";
            new android.app.AlertDialog.Builder(this)
                    .setTitle((inTrash ? "彻底删除 " : "删除 ") + ids.size() + " 个" + thing + "？")
                    .setMessage(inTrash ? "彻底删除后无法恢复。" : "会移入回收站，可在回收站恢复。")
                    .setPositiveButton("删除", (d, w) -> {
                        int n = inTrash ? Db.get(this).hardDelete(ids) : Db.get(this).delete(ids);
                        afterBatch("已删除 " + n + " 个");
                    })
                    .setNegativeButton("取消", null).show();
        });
        batchBar.addView(bDel);
        final TextView bRestore = batchAct("恢复", v -> {
            final java.util.Set<Long> ids = new java.util.LinkedHashSet<>(page().list().selected());
            int n = Db.get(this).restore(ids);
            afterBatch("已恢复 " + n + " 个");
        });
        batchBar.addView(bRestore);
        /* 回收站：只留「恢复」和「彻底删除」；正常列表：反之 */
        showInTrash.add(bRestore);
        bDel.setText("删除");
        /* bDel 在回收站里也要显示（只是文案变"彻底删除"），
           所以不加入 trashOnly；改由 refreshBatchBar 动态改文案 */

        TextView exit = new TextView(this);
        exit.setText("退出");
        exit.setTextSize(12.5f);
        exit.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        exit.setTextColor(getResources().getColor(R.color.bad));
        exit.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        Ui.press(exit);
        exit.setOnClickListener(v -> toggleSelectMode());
        batchBar.addView(exit);

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 6);
        body.addView(batchBar, bl);
    }

    private TextView batchAct(String name, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.attr(this, R.attr.textColor));
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        Ui.press(t);
        t.setOnClickListener(l);
        return t;
    }

    private void toggleSelectMode() {
        boolean on = !page().list().isSelectMode();
        page().list().setSelectMode(on);
        refreshBatchBar();
    }

    private void refreshBatchBar() {
        /* VaultPage 构建过程中就可能回调到这里（列表首次渲染），
           此时批量条还没建 —— 必须判空，否则启动即崩。 */
        if (batchBar == null || batchCount == null) return;
        boolean on = page().list().isSelectMode();
        batchBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        batchCount.setText("已选 " + page().list().selected().size());
        /* 回收站里只留「恢复 / 彻底删除」，正常列表里只留「分类 / 标签 / 收藏 / 删除」 */
        boolean inTrash = page().list().isTrashMode();
        for (View v : hideInTrash) v.setVisibility(inTrash ? View.GONE : View.VISIBLE);
        for (View v : showInTrash) v.setVisibility(inTrash ? View.VISIBLE : View.GONE);
        /* 「删除」两边都有，但回收站里是"彻底删除" */
        for (int i = 0; i < batchBar.getChildCount(); i++) {
            View v = batchBar.getChildAt(i);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                if (t.getText().equals("删除") || t.getText().equals("彻底删除")) {
                    t.setText(inTrash ? "彻底删除" : "删除");
                }
            }
        }
    }

    private void afterBatch(String msg) {
        toast(msg);
        page().list().setSelectMode(false);
        refreshBatchBar();
        page().refresh();
    }

    /**
     * 批量移动：把选中的条目移到另一个分类。
     * 取的是**当前所在库**的分类（账号用密码箱的，链接用链接库的），
     * 否则会出现"把链接移到密码箱的分类里"，移到之后在链接库里又找不着。
     */
    private void batchMove() {
        final java.util.Set<Long> ids = new java.util.LinkedHashSet<>(page().list().selected());
        String thing = page().kind() == 1 ? "链接" : "账号";
        if (ids.isEmpty()) { toast("还没选" + thing); return; }
        List<Db.Cat> cats = Db.get(this).cats(page().kind());
        if (cats.isEmpty()) { toast("还没有分类，先去设置里建一个"); return; }
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) names[i] = cats.get(i).name;
        new android.app.AlertDialog.Builder(this)
                .setTitle("移动 " + ids.size() + " 个" + thing + "到")
                .setItems(names, (d, w) -> {
                    int n = Db.get(this).moveTo(ids, cats.get(w).uuid);
                    afterBatch("已移动 " + n + " 个到「" + names[w] + "」");
                }).show();
    }

    private void batchTag() {
        final java.util.Set<Long> ids = new java.util.LinkedHashSet<>(page().list().selected());
        if (ids.isEmpty()) { toast("还没选" + (page().kind() == 1 ? "链接" : "账号")); return; }
        String[] modes = {"追加标签（保留原有）", "替换标签（覆盖原有）"};
        new android.app.AlertDialog.Builder(this).setTitle("批量设置标签（" + ids.size() + " 个）")
                .setItems(modes, (d, w) -> {
                    final boolean append = w == 0;
                    TagPicker.show(this, null, picked -> {
                        int n = append ? Db.get(this).addTags(ids, picked) : Db.get(this).setTags(ids, picked);
                        afterBatch("已为 " + n + " 个账号设置标签");
                    });
                }).show();
    }

    private void switchTo(int i) { switchTo(i, 0); }

    /**
     * @param dir 滑动方向：-1 = 左滑（新页从右侧进），1 = 右滑（新页从左侧进），0 = 无动画
     */
    private void switchTo(int i, int dir) {
        if (page().list().isSelectMode() && i != cur) {
            page().list().setSelectMode(false);
            refreshBatchBar();
        }
        cur = i;
        for (int k = 0; k < N; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            tabIcons[k].setImageDrawable(Ico.get(this, TAB_ICONS[k],
                    on ? accent() : Ui.withAlpha(Ui.attr(this, R.attr.textColor2), 150), 22));
            tabTexts[k].setTextColor(on ? accent() : Ui.attr(this, R.attr.textColor2));
            tabTexts[k].setTypeface(android.graphics.Typeface.DEFAULT_BOLD,
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabDots[k].setBackground(Ui.roundRect(on ? accent() : Color.TRANSPARENT, 2f));
        }
        /* ☰ 在密码箱与链接库都显示，各管各的左侧导航；设置页不需要 */
        menuBtn.setVisibility(i == 0 || i == 1 ? View.VISIBLE : View.GONE);
        if (i == 2) {
            /* 设置中心不再显示顶部标题 —— 底部 Tab 已经标明当前位置，
               顶上再来一行大标题是重复。密码箱/链接库保留（那里显示的是当前分类名）。 */
            if (topBar != null) topBar.setVisibility(View.GONE);
            settings.refresh();
            fab.setVisibility(View.GONE);
        } else {
            if (topBar != null) topBar.setVisibility(View.VISIBLE);
            VaultPage p = (i == 1 ? link : box);
            p.refresh();
            topTitle.setText(p.title());
            menuBtn.setImageDrawable(VaultPage.menuIcon(this, p.isNavShown()));
            /* 密码箱与链接库都能新增（各加各的类型），设置页不需要 */
            fab.setVisibility(p.list().isSelectMode() ? View.GONE : View.VISIBLE);
        }
        /* 滑动切页时的跟手过渡：新页从滑动来的那一侧淡入 */
        if (dir != 0 && pages[i] != null) {
            float from = -dir * Ui.dp(this, 30);
            pages[i].setAlpha(0f);
            pages[i].setTranslationX(from);
            pages[i].animate().alpha(1f).translationX(0)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    @Override public void showTrash() { switchTo(0); box.selectMode(2, "回收站"); }

    @Override public void showBatch() {
        switchTo(0);
        box.selectCat("", "全部");
        if (!page().list().isSelectMode()) toggleSelectMode();
    }

    void addCardDialog() {
        String[] o = Db.CARD_KINDS;
        new android.app.AlertDialog.Builder(this).setTitle("添加哪种卡？")
                .setItems(o, (d, w) -> CardEditActivity.openNew(this, o[w]))
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Session.key() == null) { startActivity(new Intent(this, UnlockActivity.class)); finish(); return; }
        if (box != null) {
            box.refresh();
            link.refresh();
            Sync.maybeAuto(this);
        com.vaultkey.data.AutoBackup.maybeRun(this);
        }
        /* 小部件 / 自动填充 / 长按快捷方式：解锁回来后直达目标 */
        /* 搜索框在 VaultPage 内部，这里只负责把页面切过去并聚焦 */
        if (Prefs.getB("pending_search", false)) {
            Prefs.putB("pending_search", false);
            if (cur != 0) switchTo(0);
            box.focusSearch();
        } else if (Prefs.getB("pending_new", false)) {
            Prefs.putB("pending_new", false);
            if (cur != 0) switchTo(0);
            box.add();
        }
    }

    @Override public void onBackPressed() {
        if (settings != null && settings.onBack()) return;
        if (cur == 0 || cur == 1) {
            if (page().list().isSelectMode()) { toggleSelectMode(); return; }
            if (page().onBack()) return;
        }
        if (cur != 0) { switchTo(0); return; }
        moveTaskToBack(true);
    }

    /* ---------------- 导入 / 导出 ---------------- */

    private static final int REQ_EXPORT = 42, REQ_IMPORT = 43;
    private static final int REQ_EXPORT_BW = 44, REQ_IMPORT_BW = 45;
    private String pendingExport;

    /* ---------------- Bitwarden 格式导出 ---------------- */

    @Override public void exportBitwarden() {
        new android.app.AlertDialog.Builder(this).setTitle("导出 Bitwarden 格式？")
                .setMessage("生成标准 .json 文件，可导入 Bitwarden / KeePassXC / 其他密码管理器。"
                        + "\n\n文件未加密，请妥善保管并及时删除。")
                .setPositiveButton("导出", (d, w) -> {
                    List<BwJson.Item> items = new java.util.ArrayList<>();
                    for (Db.Entry e : Db.get(this).list(null, false, false, null, 0)) {
                        BwJson.Item it = new BwJson.Item(
                                e.title.isEmpty() ? e.user : e.title);
                        it.user = e.user;
                        it.pass = e.pass;
                        it.url = e.url;
                        it.notes = e.notes;
                        it.totp = e.totp;
                        it.fav = e.fav == 1;
                        it.folder = catNameOf(e.catUuid);
                        for (Db.F f : Db.customs(e.custom)) {
                            if (f.k != null && !f.k.isEmpty()) it.fields.add(new String[]{f.k, f.v});
                        }
                        items.add(it);
                    }
                    pendingExport = BwJson.write(items);
                    saveExport("vaultkey-bitwarden.json", "application/json", REQ_EXPORT_BW);
                })
                .setNegativeButton("取消", null).show();
    }

    /* ---------------- Bitwarden 格式导入 ---------------- */

    @Override public void importBitwarden() {
        new android.app.AlertDialog.Builder(this).setTitle("导入 Bitwarden 文件")
                .setMessage("选择从 Bitwarden 导出的 .json 文件（可以是加密外的任一格式）。"
                        + "\n\n导入的条目会追加到现有数据，不会覆盖。")
                .setPositiveButton("选择文件", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(i, REQ_IMPORT_BW); }
                    catch (Exception ex) { toast("无法打开文件选择器"); }
                })
                .setNegativeButton("取消", null).show();
    }

    private void doImportBitwarden(android.net.Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) { toast("读不到文件"); return; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String text = new String(bo.toByteArray(), "UTF-8");

            java.util.Map<String, String> fmap = new java.util.HashMap<>();
            List<Db.Entry> inList = BwJson.read(text, fmap);
            if (inList.isEmpty()) {
                toast("没解析到可导入的条目。请确认是 Bitwarden 导出的 .json 文件");
                return;
            }
            int added = 0;
            for (Db.Entry e : inList) {
                Db.Entry ne = Db.get(this).newEntry();
                ne.title = e.title;
                ne.user = e.user == null ? "" : e.user;
                ne.pass = e.pass == null ? "" : e.pass;
                ne.url = e.url == null ? "" : e.url;
                ne.notes = e.notes == null ? "" : e.notes;
                ne.totp = e.totp == null ? "" : e.totp;
                ne.custom = e.custom == null ? "" : e.custom;
                ne.fav = e.fav;
                if (e.catName != null && !e.catName.isEmpty()) {
                    String u = Db.get(this).catUuidByName(e.catName);
                    if (u == null) u = Db.get(this).addCat(e.catName, "custom", Ui.colorOf(e.catName));
                    ne.catUuid = u;
                }
                Db.get(this).save(ne);
                added++;
            }
            toast("已导入 " + added + " 条");
            page().refresh();
        } catch (Exception ex) {
            toast("导入失败：" + ex.getMessage());
        }
    }

    @Override public void exportCsv() {
        new android.app.AlertDialog.Builder(this).setTitle("导出明文 CSV？")
                .setMessage("导出的文件未加密，请妥善保管并及时删除")
                .setPositiveButton("导出", (d, w) -> {
                    StringBuilder sb = new StringBuilder("name,login_username,login_password,login_uri,notes,folder,login_totp\n");
                    for (Db.Entry e : Db.get(this).list(null, false, false, null, 0)) {
                        sb.append(Csv.escape(e.title)).append(',')
                                .append(Csv.escape(e.user)).append(',')
                                .append(Csv.escape(e.pass)).append(',')
                                .append(Csv.escape(e.url)).append(',')
                                .append(Csv.escape(e.notes)).append(',')
                                .append(Csv.escape(catNameOf(e.catUuid))).append(',')
                                .append(Csv.escape(e.totp)).append('\n');
                    }
                    pendingExport = sb.toString();
                    saveExport("vaultkey-export.csv", "text/csv", REQ_EXPORT);
                })
                .setNegativeButton("取消", null).show();
    }

    /**
     * 导出落盘：已授权备份文件夹就直接写进去，否则弹文件选择器。
     *
     * 直接写之前先检查目录是否还活着 —— 文件夹被移动或权限被回收时，
     * 如果还闷头写，用户会以为一直在备份，其实一次都没成功。
     * 检测失败就退回文件选择器，并说明原因。
     */
    private void saveExport(String fileName, String mime, int reqCode) {
        if (BackupDir.has(this)) {
            if (BackupDir.alive(this)) {
                byte[] data;
                try { data = pendingExport.getBytes("UTF-8"); }
                catch (Exception e) { data = pendingExport.getBytes(); }
                Uri f = BackupDir.write(this, fileName, mime, data);
                if (f != null) {
                    toast("已导出到「" + BackupDir.name(this) + "」");
                    return;
                }
                toast("写入备份文件夹失败，请选择其他位置");
            } else {
                toast("备份文件夹已不可访问，请重新选择");
            }
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        try { startActivityForResult(i, reqCode); }
        catch (Exception ex) { toast("无法导出"); }
    }

    @Override public void importCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_IMPORT); } catch (Exception ex) { toast("无法打开文件选择器"); }
    }

    /** 导入时把分类名映射成 uuid：在两套分类里都找一遍，找不到就新建到密码箱 */
    private String catNameOf(String uuid) {
        for (Db.Cat c : Db.get(this).cats(0)) if (c.uuid.equals(uuid)) return c.name;
        for (Db.Cat c : Db.get(this).cats(1)) if (c.uuid.equals(uuid)) return c.name;
        return "";
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        /* 启动页选图：交给 SettingsView 处理 */
        if (res == RESULT_OK && settings != null && settings.onPickResult(req, data)) return;
        /* 授权本地备份文件夹：坚果云同步页在用（导入导出入口都在那儿） */
        if (settings != null && settings.syncPage().onPickDirResult(req, data)) return;
        /* 设置中心「数据备份」页授权的备份文件夹 */
        if (res == RESULT_OK && req == 9001 && settings != null) {
            settings.onBackupDirResult(data);
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_EXPORT) {
            try {
                java.io.OutputStream o = getContentResolver().openOutputStream(data.getData());
                if (o != null) {
                    o.write(pendingExport.getBytes("UTF-8"));
                    o.close();
                    toast("已导出");
                }
            } catch (Exception e) { toast("导出失败"); }
        } else if (req == REQ_EXPORT_BW) {
            try {
                java.io.OutputStream o = getContentResolver().openOutputStream(data.getData());
                if (o != null) {
                    o.write(pendingExport.getBytes("UTF-8"));
                    o.close();
                    toast("已导出 Bitwarden 格式");
                }
            } catch (Exception e) { toast("导出失败"); }
        } else if (req == REQ_IMPORT_BW) {
            doImportBitwarden(data.getData());
        } else if (req == REQ_IMPORT) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                List<Csv.Row> rows = Csv.read(in);
                if (rows.isEmpty()) { toast("文件里没有可导入的数据"); return; }
                int n = 0;
                for (Csv.Row r : rows) {
                    Db.Entry e = Db.get(this).newEntry();
                    e.title = r.title.isEmpty() ? r.user : r.title;
                    e.user = r.user;
                    e.pass = r.pass;
                    e.url = r.url;
                    e.notes = r.notes;
                    e.totp = com.vaultkey.util.Totp.normalize(r.totp);
                    if (!r.cat.isEmpty()) {
                        String u = Db.get(this).catUuidByName(r.cat);
                        if (u == null) u = Db.get(this).addCat(r.cat, "custom", Ui.colorOf(r.cat));
                        e.catUuid = u;
                    }
                    Db.get(this).save(e);
                    n++;
                }
                toast("已导入 " + n + " 条");
                page().list().refresh();
                page().renderNav();
            } catch (Exception e) { toast("导入失败"); }
        }
    }
}
