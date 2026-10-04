package com.vaultkey.util;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vaultkey.R;
import com.vaultkey.ui.BaseActivity;

/**
 * 设置类界面的通用零件。
 *
 * 原先这些都在 SettingsView 里，其他页面想用只能复制一遍。
 * 现在抽出来共用 —— 子页容器、说明文字、单选行、分段开关、开关行
 * 这几样在任何「设置感」的界面里都会用到。
 */
public final class SettingsKit {

    private SettingsKit() { }

    /** 索引回调 */
    public interface OnIdx { void on(int i); }

    /** 布尔回调 */
    public interface OnBool { void on(boolean on); }

    /* ---------------- 子页容器 ---------------- */

    /**
     * 建一个带标题的子页容器。
     * 返回的 LinearLayout 用 getTag() 挂着内容盒，之后用 box(p) 取。
     */
    public static LinearLayout page(BaseActivity a, String title) {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Ui.attr(a, R.attr.bgColor));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        p.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        TextView t = a.title(title, 20);
        t.setPadding(0, 0, 0, Ui.dp(a, 12));
        box.addView(t);
        p.setTag(box);
        return p;
    }

    /** 取子页的内容盒 */
    public static LinearLayout box(LinearLayout p) {
        return (LinearLayout) p.getTag();
    }

    /* ---------------- 说明文字 ---------------- */

    /** 灰色的补充说明，放在一组设置下面 */
    public static TextView note(Context a, String txt) {
        TextView n = new TextView(a);
        n.setText(txt);
        n.setTextSize(11.5f);
        n.setTextColor(Ui.attr(a, R.attr.textColor2));
        n.setPadding(Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2), 0);
        return n;
    }

    /* ---------------- 单选行 ---------------- */

    /** 一组文字，选中项打勾高亮 */
    public static View pickRow(BaseActivity a, String[] names, int sel, final OnIdx cb) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText((i == sel ? "✓  " : "    ") + names[i]);
            t.setTextSize(14);
            t.setTextColor(i == sel ? Ui.accent(a) : Ui.attr(a, R.attr.textColor));
            t.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
            t.setOnClickListener(v -> cb.on(idx));
            l.addView(t);
        }
        l.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
        return l;
    }

    /** 横向分段开关，三选一那种 */
    public static View segRow(BaseActivity a, String[] names, int sel, final OnIdx cb) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 10);
        l.setPadding(p, p, p, p);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(names[i]);
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(android.view.Gravity.CENTER);
            boolean on = i == sel;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(999f);
            g.setColor(on ? Ui.withAlpha(a.accent(), 40) : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 22));
            if (on) g.setStroke(Math.max(1, Ui.dp(a, 1)), a.accent());
            t.setBackground(g);
            t.setTextColor(on ? a.accent() : Ui.attr(a, R.attr.textColor2));
            t.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(Ui.dp(a, 6));
            t.setLayoutParams(lp);
            Ui.press(t);
            t.setOnClickListener(x -> cb.on(idx));
            l.addView(t);
        }
        return l;
    }

    /* ---------------- 开关行 ---------------- */

    /** 带图标和副标题的开关行 */
    public static View toggleRow(BaseActivity a, String ic, String k, String v,
                                 boolean init, final OnBool cb) {
        return toggleRow(a, ic, k, v, init, cb, null);
    }

    /**
     * 开关行。
     *
     * @param after 开关变化后要重画整个界面的回调；传 null 表示只更新本行
     */
    public static View toggleRow(BaseActivity a, String ic, String k, String v,
                                 boolean init, final OnBool cb, final Runnable after) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.glass(a, 18, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 14);
        row.setPadding(p, Ui.dp(a, 13), p, Ui.dp(a, 13));
        row.addView(a.iconBadge(ic, init ? 0xFF00B8A0L : 0xFF94A3B8L, 36));

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.setMarginStart(Ui.dp(a, 12));
        mid.setLayoutParams(ml);
        TextView x = new TextView(a);
        x.setText(k);
        x.setTextSize(15);
        x.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        x.setTextColor(Ui.attr(a, R.attr.textColor));
        mid.addView(x);
        TextView y = new TextView(a);
        y.setText(v);
        y.setTextSize(11.5f);
        y.setTextColor(Ui.attr(a, R.attr.textColor2));
        y.setPadding(0, Ui.dp(a, 2), 0, 0);
        mid.addView(y);
        row.addView(mid);

        final boolean[] st = {init};
        final TextView sw = sw(a, init);
        sw.setOnClickListener(z -> {
            st[0] = !st[0];
            cb.on(st[0]);
            sw.setText(st[0] ? "开" : "关");
            sw.setBackground(swBg(a, st[0]));
            if (after != null) after.run();
        });
        row.addView(sw);
        Ui.press(row);
        return row;
    }

    private static TextView sw(BaseActivity a, boolean on) {
        TextView t = new TextView(a);
        t.setText(on ? "开" : "关");
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(on ? 0xFF00B8A0 : Ui.attr(a, R.attr.textColor2));
        t.setBackground(swBg(a, on));
        t.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
        return t;
    }

    private static android.graphics.drawable.GradientDrawable swBg(BaseActivity a, boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(999f);
        g.setColor(on ? Ui.withAlpha(0xFF00B8A0, 40) : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 22));
        if (on) g.setStroke(Math.max(1, Ui.dp(a, 1)), 0xFF00B8A0);
        return g;
    }
}
