package com.vaultkey.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;

/** 标签选择器：预置标签 + 历史标签 + 自定义输入，多选 */
public final class TagPicker {
    public interface Cb {
        void onDone(List<String> tags);
    }

    /** 标签配色：按名称哈希，同一标签颜色始终一致 */
    public static int colorOf(String t) {
        return Ui.colorOf(t);
    }

    public static void show(final BaseActivity a, List<String> current, final Cb cb) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.glass(a, 26, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 18);
        root.setPadding(p, p, p, p);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(a);
        back.setImageDrawable(Ico.get(a, "back", Ui.attr(a, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        back.setOnClickListener(v -> d.dismiss());
        head.addView(back);
        TextView t = a.title("选择标签", 20);
        t.setPadding(Ui.dp(a, 10), 0, 0, 0);
        head.addView(t);
        root.addView(head);

        final List<String> sel = new ArrayList<>();
        if (current != null) sel.addAll(current);
        for (String s : sel) if (!sel.contains(s)) sel.add(s);

        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        final android.widget.Button ok = a.button("确定", new int[]{a.accent(), a.accent2()});

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override public void run() {
                body.removeAllViews();
                body.addView(group(a, "预置标签", java.util.Arrays.asList(Db.PRESET_TAGS), sel, render[0]));
                List<String> hist = Db.get(a).allTags();
                List<String> extra = new ArrayList<>();
                for (String h : hist) {
                    boolean inPreset = false;
                    for (String x : Db.PRESET_TAGS) if (x.equals(h)) inPreset = true;
                    if (!inPreset) extra.add(h);
                }
                if (!extra.isEmpty()) body.addView(group(a, "用过的标签", extra, sel, render[0]));
                ok.setText("确定（" + sel.size() + "）");
            }
        };
        render[0].run();

        LinearLayout add = new LinearLayout(a);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(a, 10);
        add.setLayoutParams(al);
        final EditText e = new EditText(a);
        e.setHint("输入新标签，如 主号");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextColor(Ui.attr(a, R.attr.textColor));
        e.setHintTextColor(Ui.attr(a, R.attr.textColor2));
        e.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
        e.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
        e.setTextSize(14);
        add.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = a.chip("添加", true);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.setMarginStart(Ui.dp(a, 8));
        go.setLayoutParams(gl);
        go.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
        Ui.press(go);
        go.setOnClickListener(v -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return;
            if (!sel.contains(s)) { sel.add(s); render[0].run(); }
            e.setText("");
        });
        add.addView(go);
        root.addView(add);

        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(a, 18);
        ok.setLayoutParams(ol);
        ok.setOnClickListener(v -> {
            d.dismiss();
            if (cb != null) cb.onDone(new ArrayList<>(sel));
        });
        root.addView(ok);

        android.widget.FrameLayout fl = new android.widget.FrameLayout(a);
        fl.setBackgroundColor(Ui.withAlpha(Color.BLACK, 140));
        android.widget.FrameLayout.LayoutParams fl2 = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        fl2.setMargins(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14));
        fl.addView(root, fl2);
        fl.setOnClickListener(v -> d.dismiss());
        d.setContentView(fl);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        d.show();
    }

    private static View group(final BaseActivity a, String title, final List<String> tags,
                              final List<String> sel, final Runnable render) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(a, 12);
        box.setLayoutParams(bl);

        TextView h = new TextView(a);
        h.setText(title);
        h.setTextSize(11);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setTextColor(Ui.attr(a, R.attr.textColor2));
        h.setPadding(Ui.dp(a, 2), 0, 0, Ui.dp(a, 6));
        box.addView(h);

        /* 流式布局：每行最多放 4 个 */
        LinearLayout row = null;
        for (int i = 0; i < tags.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = Ui.dp(a, 6);
                row.setLayoutParams(rl);
                box.addView(row);
            }
            final String tag = tags.get(i);
            final boolean on = sel.contains(tag);
            final TextView c = chipView(a, tag, on);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 4 != 0) cl.setMarginStart(Ui.dp(a, 6));
            c.setLayoutParams(cl);
            Ui.press(c);
            c.setOnClickListener(v -> {
                if (sel.contains(tag)) sel.remove(tag); else sel.add(tag);
                render.run();
            });
            row.addView(c);
        }
        return box;
    }

    /** 单个标签胶囊 */
    public static TextView chipView(BaseActivity a, String tag, boolean on) {
        TextView t = new TextView(a);
        t.setText((on ? "✓ " : "") + tag);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        int col = colorOf(tag);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(999f);
        if (on) {
            g.setColor(Ui.withAlpha(col, Ui.isDark(a) ? 70 : 46));
            g.setStroke(Math.max(1, Ui.dp(a, 1)), col);
            t.setTextColor(col);
        } else {
            g.setColor(Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 26));
            t.setTextColor(Ui.attr(a, R.attr.textColor2));
        }
        t.setBackground(g);
        t.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** 列表条目里的小标签条（只读展示） */
    public static LinearLayout tagStrip(BaseActivity a, List<String> tags) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        if (tags == null || tags.isEmpty()) return l;
        int n = Math.min(3, tags.size());
        for (int i = 0; i < n; i++) {
            TextView t = new TextView(a);
            t.setText(tags.get(i));
            t.setTextSize(9.5f);
            t.setGravity(Gravity.CENTER);
            int col = colorOf(tags.get(i));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(999f);
            g.setColor(Ui.withAlpha(col, Ui.isDark(a) ? 55 : 34));
            t.setBackground(g);
            t.setTextColor(col);
            t.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(Ui.dp(a, 4));
            t.setLayoutParams(lp);
            l.addView(t);
        }
        if (tags.size() > n) {
            TextView more = new TextView(a);
            more.setText("+" + (tags.size() - n));
            more.setTextSize(9.5f);
            more.setTextColor(Ui.attr(a, R.attr.textColor2));
            more.setPadding(Ui.dp(a, 4), 0, 0, 0);
            l.addView(more);
        }
        return l;
    }
}
