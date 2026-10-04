package com.vaultkey.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.vaultkey.R;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Cutout;
import com.vaultkey.util.Skin;
import com.vaultkey.util.Ui;

public abstract class BaseActivity extends Activity {
    protected FrameLayout root;
    protected LinearLayout body;

    @Override protected void attachBaseContext(Context newBase) {
        Prefs.init(newBase);
        super.attachBaseContext(SettingsView.wrap(newBase));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 刘海屏：必须在 setContentView 之前设置，否则不生效 */
        Cutout.apply(this);
        applySecure();
        if (Build.VERSION.SDK_INT >= 28 && Prefs.getB("nav_transparent", true)) {
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        applyBarColors();
        mySkinVer = Skin.ver();
        root = new FrameLayout(this);
        root.setBackground(Ui.background(this));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int pad = Ui.dp(this, 16);
        lp.setMargins(pad, 0, pad, 0);
        root.addView(body, lp);
        setContentView(root);
        /* 内容避让刘海：背景可以延伸到挖孔区，但按钮文字不能被切掉 */
        Cutout.padRoot(this, root, 0);
    }

    /**
     * 状态栏 / 导航栏配色跟随皮肤。
     * 之前只依赖 values / values-night 里写死的 d_bg，换皮肤或手动切深浅后，
     * 底色变了而状态栏没变，会出现「界面一色、状态栏另一色」的割裂。
     */
    protected void applyBarColors() {
        try {
            int bg = Skin.bg(this);
            getWindow().setStatusBarColor(bg);
            if (!(Build.VERSION.SDK_INT >= 28 && Prefs.getB("nav_transparent", true))) {
                getWindow().setNavigationBarColor(bg);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                View dec = getWindow().getDecorView();
                int flags = dec.getSystemUiVisibility();
                if (Skin.isDark(this)) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                dec.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) { }
    }

    /** 禁止截屏开关：默认开启，可在设置里关闭 */
    protected void applySecure() {
        if (Prefs.getB("block_capture", true)) {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** 上次见到的皮肤版本；变了就整页重建，保证换皮肤全局即时生效 */
    private int mySkinVer = -1;

    @Override protected void onResume() {
        super.onResume();
        /* 换皮肤 / 换背景后回到本页：版本号不同 → 重建整页 */
        int v = Skin.ver();
        if (mySkinVer >= 0 && v != mySkinVer) {
            mySkinVer = v;
            Ico.clearCache();
            recreate();
            return;
        }
        mySkinVer = v;
        applySecure();
        if (this instanceof UnlockActivity) { Session.touch(); return; }
        long t = Prefs.getI("lock_ms", 180000);
        if (Session.expired(t) || (Prefs.getB("lock_on_resume", false) && lastPause > 0)) {
            Session.lock();
            startActivity(new android.content.Intent(this, UnlockActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }
        Session.touch();
    }

    private long lastPause;

    /**
     * 统一托管对话框。
     * 之前各页面自己 new ProgressDialog，若耗时任务未结束时用户按返回或旋转屏幕，
     * Activity 已销毁而对话框还挂着，就会抛 WindowLeaked
     * （"Activity ... has leaked window ... that was originally added here"）并崩溃。
     * 现在由基类在销毁时统一收掉。
     */
    private final java.util.List<android.app.Dialog> dialogs = new java.util.ArrayList<>();

    protected android.app.ProgressDialog progress(String msg) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(msg);
        pd.setCancelable(false);
        dialogs.add(pd);
        return pd;
    }

    protected void safeDismiss(android.app.Dialog d) {
        try {
            if (d != null && d.isShowing()) {
                /* 宿主 Activity 已经 finish 的话 dismiss 会抛异常 */
                if (!isFinishing() && !isDestroyed()) d.dismiss();
            }
        } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        for (android.app.Dialog d : dialogs) {
            try { if (d.isShowing()) d.dismiss(); } catch (Exception ignored) { }
        }
        dialogs.clear();
    }

    @Override protected void onPause() {
        super.onPause();
        lastPause = System.currentTimeMillis();
    }

    @Override public void onUserInteraction() { Session.touch(); super.onUserInteraction(); }

    /* ---------------- 通用组件 ---------------- */

    public int accent() { return Skin.accent(this); }
    protected int accent2() { return Skin.accent2(this); }
    protected int accent3() { return Ui.accent2(this); }
    public TextView title(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.attr(this, R.attr.textColor));
        t.setTextSize(size);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    protected TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.attr(this, R.attr.textColor2));
        t.setTextSize(13);
        return t;
    }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /**
     * Android 13+ 复制后系统自己会弹确认条，官方强烈建议移除应用内的
     * 「已复制」Toast，否则用户会看到两次提示。
     *
     * 但本应用的 Toast 还承担一个重要信息：**「N 秒后自动清空」**——
     * 这是安全相关提示，不能因为去重就整个去掉。
     * 所以只在 Android 13+ 去掉纯「已复制」的部分，保留自动清空提示。
     */
    protected void toastCopied(String what) {
        int sec = Prefs.getI("clip_sec", 45);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (sec > 0) toast(sec + " 秒后自动清空剪贴板");
        } else {
            toast("已复制" + what + (sec > 0 ? "，" + sec + " 秒后自动清空" : ""));
        }
    }


    protected android.widget.EditText field(String hint, String value) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint(hint);
        if (value != null) e.setText(value);
        e.setTextColor(Ui.attr(this, R.attr.textColor));
        e.setHintTextColor(Ui.attr(this, R.attr.textColor2));
        /* 输入框用实色底：毛玻璃底在深色下太透，输入内容会浮在背景渐变上看不清 */
        e.setBackground(Ui.field(this, 16));
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        e.setTextSize(15);
        e.setSingleLine(true);
        return e;
    }

    protected android.widget.Button button(String s, int[] colors) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(Ui.gradient(colors, Ui.dp(this, 18), 0));
        b.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        b.setStateListAnimator(null);
        Ui.press(b);
        return b;
    }

    protected TextView chip(String s, boolean active) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (active) {
            t.setTextColor(Color.WHITE);
            t.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        } else {
            t.setTextColor(Ui.attr(this, R.attr.textColor));
            t.setBackground(Ui.glass(this, 999, R.attr.cardColor, R.attr.strokeColor));
        }
        int h = Ui.dp(this, 12);
        t.setPadding(Ui.dp(this, 16), h, Ui.dp(this, 16), h);
        return t;
    }

    /** 自绘图标 */
    protected ImageView icon(String name, int color, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ico.get(this, name, color, sizeDp));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return iv;
    }
    /** 圆形图标底盘（分类用） */
    public FrameLayout iconBadge(String name, long color, int sizeDp) {
        int s = Ui.dp(this, sizeDp);
        FrameLayout f = new FrameLayout(this);
        f.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        int c = (int) color;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Ui.withAlpha(c, Ui.isDark(this) ? 60 : 38));
        View bg = new View(this);
        bg.setLayoutParams(new FrameLayout.LayoutParams(s, s));
        bg.setBackground(g);
        f.addView(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ico.get(this, name, c, sizeDp - 8));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Ui.dp(this, sizeDp - 8), Ui.dp(this, sizeDp - 8), Gravity.CENTER);
        iv.setLayoutParams(il);
        f.addView(iv);
        return f;
    }

    /** 设置项：左侧图标 + 标题 + 副标题，右侧箭头 */
    protected View settingRow(String icName, String k, String v, View.OnClickListener l) {
        return settingRow(icName, k, v, l, accent(), true);
    }

    protected View settingRow(String icName, String k, String v, View.OnClickListener l, int tint, boolean arrow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.glass(this, 18, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 14);
        row.setPadding(p, Ui.dp(this, 13), p, Ui.dp(this, 13));

        FrameLayout badge = iconBadge(icName, tint, 36);
        row.addView(badge);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.setMarginStart(Ui.dp(this, 12));
        mid.setLayoutParams(ml);
        TextView x = new TextView(this);
        x.setText(k);
        x.setTextSize(15);
        x.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        x.setTextColor(Ui.attr(this, R.attr.textColor));
        mid.addView(x);
        if (v != null && !v.isEmpty()) {
            TextView y = new TextView(this);
            y.setText(v);
            y.setTextSize(11.5f);
            y.setTextColor(Ui.attr(this, R.attr.textColor2));
            y.setPadding(0, Ui.dp(this, 2), 0, 0);
            mid.addView(y);
        }
        row.addView(mid);

        if (arrow) {
            ImageView a = new ImageView(this);
            a.setImageDrawable(Ico.get(this, "chevron", Ui.attr(this, R.attr.textColor2), 18));
            a.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            row.addView(a);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        Ui.press(row);
        if (l != null) row.setOnClickListener(l);
        return row;
    }

    /** 小节标题 */
    protected View section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(accent());
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, Ui.dp(this, 4));
        return t;
    }

    /** 卡片容器 */
    protected LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        c.setLayoutParams(lp);
        return c;
    }
}
