package com.vaultkey.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Attach;
import com.vaultkey.data.Db;
import com.vaultkey.data.Session;
import com.vaultkey.util.Ico;
import com.vaultkey.util.SettingsKit;
import com.vaultkey.util.Liquid;
import com.vaultkey.util.Ui;
import com.vaultkey.util.SimpleAdapter;
import java.util.ArrayList;
import java.util.List;

/** 卡包列表：身份证 / 银行卡 / 社保卡 / 驾驶证 / 护照 / 会员卡 / 自定义 */
public final class CardList {
    private final BaseActivity a;
    private final SimpleAdapter<Db.Card> ad = makeAd();
    private ListView list;
    private TextView emptyView;
    private String kind = "";
    private String query = "";
    private boolean trash;
    private Runnable onChanged;

    public CardList(BaseActivity a) { this.a = a; }

    public void setOnChanged(Runnable r) { onChanged = r; }
    public void setKind(String k) { kind = k == null ? "" : k; }
    public void setTrash(boolean t) { trash = t; }
    public boolean isTrashMode() { return trash; }

    public void setQuery(String q) {
        String n = q == null ? "" : q;
        if (!n.equals(query)) { query = n; refresh(); }
        else query = n;
    }

    public View view() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        list = new ListView(a);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(a, 10));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(ad);
        list.setPadding(Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 90));
        list.setClipToPadding(false);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refresh();
        return box;
    }

    public void refresh() {
        if (list == null || Session.key() == null) return;
        List<Db.Card> data = Db.get(a).cards(kind.isEmpty() ? null : kind, trash, query);
        ad.setData(data);
        if (emptyView == null) {
            emptyView = new TextView(a);
            emptyView.setTextColor(Ui.attr(a, R.attr.textColor2));
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(0, Ui.dp(a, 50), 0, 0);
            emptyView.setTextSize(13);
            ((ViewGroup) list.getParent()).addView(emptyView,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        emptyView.setText(trash ? "回收站是空的" : query.isEmpty()
                ? (kind.isEmpty() ? "还没有卡片，点 ➕ 添加身份证、银行卡…" : "该类型下暂无卡片")
                : "没有匹配的卡片");
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /* ---------------- 适配器 ---------------- */

    /** 只写 view()，其余样板由 SimpleAdapter 提供 */
    private SimpleAdapter<Db.Card> makeAd() {
        return new SimpleAdapter<Db.Card>() {
        /** 用数据库主键当 id，而不是下标 */
        @Override protected long idOf(Db.Card c, int p) { return c.id; }

        @Override public View view(int p, final Db.Card c, View cv, ViewGroup parent) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            int pd = Ui.dp(a, 14);
            card.setPadding(pd, pd, pd, pd);

            /* 卡片头：图标 + 名称 + 类型 */
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            int col = colorOfKind(c.kind);
            FrameBadge badge = new FrameBadge(a, c);
            head.addView(badge);

            LinearLayout mid = new LinearLayout(a);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.setMarginStart(Ui.dp(a, 12));
            mid.setLayoutParams(ml);
            TextView t = new TextView(a);
            t.setText(c.title.isEmpty() ? c.kind : c.title);
            t.setTextSize(16);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.attr(a, R.attr.textColor));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(t);
            TextView s = new TextView(a);
            s.setText(c.kind + " · " + preview(c));
            s.setTextSize(11.5f);

            s.setTextColor(Ui.attr(a, R.attr.textColor2));

            /* 灰色说明文字，样式统一走 SettingsKit.note 的同款参数 */
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(s);
            head.addView(mid);
            card.addView(head);

            /* 缩略图：最多显示 3 张 */
            List<String> imgs = Db.imgs(c.imgs);
            if (!imgs.isEmpty()) {
                LinearLayout strip = new LinearLayout(a);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 72));
                sl.topMargin = Ui.dp(a, 12);
                strip.setLayoutParams(sl);
                int n = Math.min(3, imgs.size());
                for (int i = 0; i < n; i++) {
                    final String name = imgs.get(i);
                    ImageView iv = new ImageView(a);
                    int w = Ui.dp(a, 104);
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(w, Ui.dp(a, 72));
                    if (i > 0) il.setMarginStart(Ui.dp(a, 8));
                    iv.setLayoutParams(il);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(Ui.attr(a, R.attr.cardColor2));
                    g.setCornerRadius(Ui.dp(a, 10));
                    iv.setBackground(g);
                    iv.setClipToOutline(true);
                    final Bitmap[] wait = new Bitmap[1];
                    iv.setTag(name);
                    new Thread(() -> {
                        Bitmap b = Attach.load(a, name, 400);
                        if (b == null) return;
                        a.runOnUiThread(() -> { if (name.equals(iv.getTag())) iv.setImageBitmap(b); });
                    }).start();
                    iv.setOnClickListener(v -> ImageViewActivity.show(a, c.imgs, name));
                    strip.addView(iv);
                }
                card.addView(strip);
            }

            /* 卡体：渐变色块，突出「卡」的质感 */
            LinearLayout plate = new LinearLayout(a);
            plate.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable pg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Ui.withAlpha(col, Ui.isDark(a) ? 90 : 60),
                            Ui.withAlpha(col, Ui.isDark(a) ? 40 : 26)});
            pg.setCornerRadius(Ui.dp(a, 14));
            plate.setBackground(pg);
            int pp = Ui.dp(a, 10);
            plate.setPadding(pp, pp, pp, pp);
            LinearLayout.LayoutParams pll = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pll.topMargin = Ui.dp(a, 12);
            plate.setLayoutParams(pll);

            List<Db.F> fs = Db.fields(c.fields);
            int shown = 0;
            for (Db.F f : fs) {
                if (f.v == null || f.v.isEmpty()) continue;
                if (shown >= 2) break;
                LinearLayout line = new LinearLayout(a);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView k = new TextView(a);
                k.setText(f.k);
                k.setTextSize(11);
                k.setTextColor(Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 200));
                line.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView v = new TextView(a);
                v.setText(mask(f.k, f.v));
                v.setTextSize(12.5f);
                v.setTypeface(Typeface.MONOSPACE);
                v.setTextColor(Ui.attr(a, R.attr.textColor));
                v.setSingleLine(true);
                v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                line.addView(v);
                plate.addView(line);
                shown++;
            }
            if (shown > 0) card.addView(plate);

            card.setBackground(Liquid.glass(a, 22));
            card.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setOnClickListener(x -> CardEditActivity.open(a, c.id));
            card.setOnLongClickListener(x -> {
                android.widget.PopupMenu pm = new android.widget.PopupMenu(a, x);
                pm.getMenu().add("编辑");
                if (trash) { pm.getMenu().add("恢复"); pm.getMenu().add("彻底删除"); }
                else pm.getMenu().add("删除");
                pm.setOnMenuItemClickListener(it -> {
                    switch (it.getTitle().toString()) {
                        case "编辑": CardEditActivity.open(a, c.id); break;
                        case "删除":
                            Db.get(a).softDeleteCard(c.id);
                            refresh();
                            if (onChanged != null) onChanged.run();
                            break;
                        case "恢复":
                            Db.get(a).restoreCard(c.id);
                            refresh();
                            if (onChanged != null) onChanged.run();
                            break;
                        case "彻底删除":
                            Db.get(a).hardDeleteCard(c.id);
                            refresh();
                            if (onChanged != null) onChanged.run();
                            break;
                    }
                    return true;
                });
                pm.show();
                return true;
            });
            return card;
        }

        private String preview(Db.Card c) {
            List<Db.F> fs = Db.fields(c.fields);
            for (Db.F f : fs) if (f.v != null && !f.v.isEmpty()) return f.k + " " + mask(f.k, f.v);
            return "未填写";
        }

        /** 证件号/卡号脱敏显示，点开详情才可见完整 */
        private String mask(String k, String v) {
            if (k == null || v == null) return v;
            if (k.contains("号") || k.contains("编号") || k.contains("证号")) {
                int n = v.length();
                if (n <= 8) return v;
                return v.substring(0, 4) + " **** **** " + v.substring(n - 4);
            }
            return v;
        }

        private int colorOfKind(String kind) {
            switch (kind == null ? "" : kind) {
                case "身份证": return 0xFF38BDF8;
                case "银行卡": return 0xFFF87171;
                case "社保卡": return 0xFF22C55E;
                case "驾驶证": return 0xFFFBBF24;
                case "护照": return 0xFFA78BFA;
                case "会员卡": return 0xFFFF6B9D;
                default: return 0xFF00B8A0;
            }
        }

        /** 圆形玻璃图标徽章 */
        private final class FrameBadge extends android.widget.FrameLayout {
            FrameBadge(Context ctx, Db.Card c) {
                super(ctx);
                int s = Ui.dp(ctx, 44);
                setLayoutParams(new LinearLayout.LayoutParams(s, s));
                int col = colorOfKind(c.kind);
                setBackground(Liquid.blob(ctx, 44, col));
                String ic = "more";
                for (int i = 0; i < Db.CARD_KINDS.length; i++) {
                    if (Db.CARD_KINDS[i].equals(c.kind)) ic = Db.CARD_ICONS[i];
                }
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(Ico.get(ctx, ic, Color.WHITE, 22));
                android.widget.FrameLayout.LayoutParams il = new android.widget.FrameLayout.LayoutParams(
                        Ui.dp(ctx, 22), Ui.dp(ctx, 22), Gravity.CENTER);
                iv.setLayoutParams(il);
                addView(iv);
            }
        }
        };
    }
}
