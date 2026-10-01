package com.vaultkey.ui;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Skin;
import com.vaultkey.util.Ui;

/** 皮肤：内置方案 + 自定义主色/辅色/明暗/底色 */
public final class SkinActivity extends BaseActivity {
    private LinearLayout grid;
    private Skin.Theme cur;
    private int ca, ca2;
    private int cmode;
    private int cbase;
    private static final int REQ_BG = 91;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = Skin.current(this);
        ca = cur.accent;
        ca2 = cur.accent2;
        cmode = Skin.mode();
        cbase = cur.base;

        ScrollView sv = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 100));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        back.setOnClickListener(x -> finish());
        head.addView(back);
        TextView t = title("换皮肤", 22);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        v.addView(head);

        /* 当前效果预览 */
        v.addView(sec("当前效果"));
        v.addView(buildPreview());

        /* 内置方案 */
        v.addView(sec("内置方案"));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        v.addView(grid);
        renderGrid();

        /* 自定义 */
        v.addView(sec("自定义配色"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.glass(this, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(paletteRow("主色", true));
        card.addView(paletteRow("辅色", false));

        card.addView(miniLabel("明暗"));
        card.addView(toggleRow(new String[]{"跟随系统", "浅色", "深色"}, cmode));

        card.addView(miniLabel("底色"));
        card.addView(toggleRow(new String[]{"跟随主色", "纯白", "纯黑"}, cbase));

        card.addView(miniLabel("主色明度"));
        card.addView(slider());

        v.addView(card);

        /* 背景图 */
        v.addView(sec("背景"));
        LinearLayout bgCard = new LinearLayout(this);
        bgCard.setOrientation(LinearLayout.VERTICAL);
        bgCard.setBackground(Ui.glass(this, 20, R.attr.cardColor, R.attr.strokeColor));
        bgCard.setPadding(p, p, p, p);
        bgCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView bgState = new TextView(this);
        bgState.setText(Skin.hasBg(this) ? "已设置自定义背景" : "未设置，使用纯色渐变背景");
        bgState.setTextSize(12.5f);
        bgState.setTextColor(Ui.attr(this, R.attr.textColor));
        bgCard.addView(bgState);

        LinearLayout bgBtns = new LinearLayout(this);
        bgBtns.setOrientation(LinearLayout.HORIZONTAL);
        bgBtns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bbl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbl.topMargin = Ui.dp(this, 12);
        bgBtns.setLayoutParams(bbl);

        TextView pick = new TextView(this);
        pick.setText("从相册选图");
        pick.setTextSize(13);
        pick.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pick.setTextColor(android.graphics.Color.WHITE);
        pick.setGravity(Gravity.CENTER);
        pick.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        pick.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        pick.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.press(pick);
        pick.setOnClickListener(x -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(i, REQ_BG); } catch (Exception e) { toast("打不开相册"); }
        });
        bgBtns.addView(pick);

        TextView clr = new TextView(this);
        clr.setText("清除背景");
        clr.setTextSize(13);
        clr.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clr.setTextColor(getResources().getColor(R.color.bad));
        clr.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
        cg.setCornerRadius(999f);
        cg.setColor(Ui.withAlpha(getResources().getColor(R.color.bad), 26));
        clr.setBackground(cg);
        clr.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.dp(this, 10));
        clr.setLayoutParams(cl);
        Ui.press(clr);
        clr.setOnClickListener(x -> {
            Skin.clearBg(this);
            toast("已清除背景");
            Ico.clearCache();
            recreate();
        });
        bgBtns.addView(clr);
        bgCard.addView(bgBtns);

        /* 遮罩浓度：浓度越高图片越淡、文字越清楚 */
        TextView dt = new TextView(this);
        dt.setText("图片浓度（越低图片越淡、文字越清晰）");
        dt.setTextSize(11.5f);
        dt.setTextColor(Ui.attr(this, R.attr.textColor2));
        dt.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 2));
        bgCard.addView(dt);

        SeekBar dim = new SeekBar(this);
        dim.setMax(70);
        dim.setProgress(Skin.bgDim(this));
        final TextView dv = new TextView(this);
        dv.setText("遮罩 " + Skin.bgDim(this) + "%");
        dv.setTextSize(11);
        dv.setTextColor(Ui.attr(this, R.attr.textColor2));
        bgCard.addView(dv);
        dim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean f) { dv.setText("遮罩 " + pr + "%"); }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) {
                Skin.setBgDim(SkinActivity.this, sb.getProgress());
                Ico.clearCache();
            recreate();
            }
        });
        bgCard.addView(dim);

        TextView tip = new TextView(this);
        tip.setText("背景图只存本应用私有目录，不会进系统相册，也不上传");
        tip.setTextSize(10.5f);
        tip.setTextColor(Ui.attr(this, R.attr.textColor2));
        tip.setPadding(0, Ui.dp(this, 10), 0, 0);
        bgCard.addView(tip);
        v.addView(bgCard);

        android.widget.Button apply = button("应用自定义皮肤", new int[]{accent(), accent2()});
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(this, 18);
        apply.setLayoutParams(al);
        apply.setOnClickListener(x -> {
            Skin.setCustom(this, ca, ca2, cbase);
            toast("已应用");
            Ico.clearCache();
            recreate();
        });
        v.addView(apply);

        /* 恢复默认 */
        TextView reset = new TextView(this);
        reset.setText("恢复默认薄荷清新");
        reset.setTextSize(13);
        reset.setTextColor(getResources().getColor(R.color.bad));
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, Ui.dp(this, 18), 0, 0);
        Ui.press(reset);
        reset.setOnClickListener(x -> {
            Skin.apply(this, Skin.THEMES[0]);
            Ico.clearCache();
            recreate();
        });
        v.addView(reset);

        sv.addView(v);
        body.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.popIn(v, 0);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BG || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.app.ProgressDialog pd = progress("处理图片…");
        pd.show();
        new Thread(() -> {
            Bitmap b = null;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                if (in != null) {
                    b = android.graphics.BitmapFactory.decodeStream(in);
                    in.close();
                }
            } catch (Exception ignored) { }
            final boolean ok = b != null && Skin.setBg(SkinActivity.this, b);
            if (b != null) b.recycle();
            runOnUiThread(() -> {
                safeDismiss(pd);
                toast(ok ? "背景已设置" : "图片处理失败");
                if (ok) recreate();
            });
        }).start();
    }

    private View sec(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(accent());
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    private View miniLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(Ui.attr(this, R.attr.textColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        t.setLayoutParams(lp);
        return t;
    }

    private View buildPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.glass(this, 22, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.FrameLayout badge = new android.widget.FrameLayout(this);
        int bs = Ui.dp(this, 40);
        badge.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Ui.withAlpha(accent(), 40));
        badge.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(Ico.get(this, "box", accent(), 22));
        ic.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER));
        badge.addView(ic);
        row.addView(badge);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.setMarginStart(Ui.dp(this, 12));
        mid.setLayoutParams(ml);
        TextView n = new TextView(this);
        n.setText("示例账号");
        n.setTextSize(15);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setTextColor(Ui.attr(this, R.attr.textColor));
        mid.addView(n);
        TextView s = new TextView(this);
        s.setText("user@example.com");
        s.setTextSize(11.5f);
        s.setTextColor(Ui.attr(this, R.attr.textColor2));
        mid.addView(s);
        row.addView(mid);
        box.addView(row);

        android.widget.Button b = new android.widget.Button(this);
        b.setText("主按钮");
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 14);
        b.setLayoutParams(bl);
        box.addView(b);
        return box;
    }

    private void renderGrid() {
        grid.removeAllViews();
        String key = Skin.current(this).key;
        LinearLayout row = null;
        for (int i = 0; i < Skin.THEMES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = Ui.dp(this, 8);
                row.setLayoutParams(rl);
                grid.addView(row);
            }
            final Skin.Theme th = Skin.THEMES[i];
            final boolean on = th.key.equals(key);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 3 != 0) cll.setMarginStart(Ui.dp(this, 8));
            cell.setLayoutParams(cll);
            cell.setBackground(Ui.glass(this, 16,
                    on ? R.attr.cardColor2 : R.attr.cardColor, R.attr.strokeColor));
            int p = Ui.dp(this, 10);
            cell.setPadding(p, p, p, p);

            View dot = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            dl.bottomMargin = Ui.dp(this, 6);
            dot.setLayoutParams(dl);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{th.accent, th.accent2});
            g.setShape(GradientDrawable.OVAL);
            dot.setBackground(g);
            cell.addView(dot);

            TextView nm = new TextView(this);
            nm.setText(th.name);
            nm.setTextSize(11);
            nm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nm.setGravity(Gravity.CENTER);
            nm.setTextColor(on ? Skin.accent(this) : Ui.attr(this, R.attr.textColor));
            nm.setSingleLine(true);
            cell.addView(nm);
            TextView dk = new TextView(this);
            dk.setText(th.dark ? "深色" : "浅色");
            dk.setTextSize(9.5f);
            dk.setGravity(Gravity.CENTER);
            dk.setTextColor(Ui.attr(this, R.attr.textColor2));
            cell.addView(dk);

            Ui.press(cell);
            cell.setOnClickListener(x -> {
                Skin.apply(this, th);
                ca = th.accent; ca2 = th.accent2; cbase = th.base;
                toast("已切换到「" + th.name + "」");
                Ico.clearCache();
            recreate();
            });
            row.addView(cell);
        }
    }

    private View paletteRow(String label, final boolean isMain) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 4);
        box.setLayoutParams(bl);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setTextColor(Ui.attr(this, R.attr.textColor));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View swatch = new View(this);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 20)));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 6));
        g.setColor(isMain ? ca : ca2);
        swatch.setBackground(g);
        top.addView(swatch);
        box.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(rl);
        for (int i = 0; i < Skin.PALETTE.length; i++) {
            final int col = Skin.PALETTE[i];
            View sw = new View(this);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1f);
            if (i > 0) ll.setMarginStart(Ui.dp(this, 6));
            sw.setLayoutParams(ll);
            GradientDrawable sg = new GradientDrawable();
            sg.setCornerRadius(Ui.dp(this, 8));
            sg.setColor(col);
            sw.setBackground(sg);
            Ui.press(sw);
            sw.setOnClickListener(x -> {
                if (isMain) ca = col; else ca2 = col;
                Skin.setCustom(this, ca, ca2, cbase);
                Ico.clearCache();
            recreate();
            });
            row.addView(sw);
        }
        box.addView(row);
        return box;
    }

    private View toggleRow(String[] names, int sel) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        l.setLayoutParams(lp);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            boolean on = i == sel;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(999f);
            g.setColor(on ? Ui.withAlpha(accent(), 40) : Ui.withAlpha(Ui.attr(this, R.attr.textColor2), 22));
            if (on) g.setStroke(Math.max(1, Ui.dp(this, 1)), accent());
            t.setBackground(g);
            t.setTextColor(on ? accent() : Ui.attr(this, R.attr.textColor2));
            t.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tl.setMarginStart(Ui.dp(this, 6));
            t.setLayoutParams(tl);
            Ui.press(t);
            t.setOnClickListener(x -> {
                if (names.length == 3 && names[0].equals("跟随系统")) {
                    /* 明暗三档：直接改明暗模式，不动配色 */
                    cmode = idx;
                    Skin.setMode(cmode);
                } else {
                    cbase = idx;
                    Skin.setCustom(this, ca, ca2, cbase);
                }
                Ico.clearCache();
            recreate();
            });
            l.addView(t);
        }
        return l;
    }

    private View slider() {
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(50);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                float k = (p - 50) / 50f;
                int base = cur.accent;
                if (k >= 0) ca = Skin.mix(base, Color.WHITE, k * 0.7f);
                else ca = Skin.mix(base, Color.BLACK, -k * 0.7f);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                Skin.setCustom(SkinActivity.this, ca, ca2, cbase);
                Ico.clearCache();
            recreate();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        sb.setLayoutParams(lp);
        return sb;
    }
}
