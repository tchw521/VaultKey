package com.vaultkey.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Ui;

import java.util.Map;

/**
 * 左侧分类导航的渲染器，密码箱与链接库**共用同一份**。
 *
 * 之前两边各写一套，样式很快就跑偏了（角标大小、行高、分组标题不一致）。
 * 抽出来之后只要改这里，两边同时生效。
 *
 * 内容差异只由 kind 决定：
 *   kind = 0（密码箱）：账号区（含「找回」）+ 卡包区 + 回收站
 *   kind = 1（链接库）：链接区 + 回收站 —— **没有卡包区**
 *
 * 另外链接库也不需要「找回」（那是按域名聚合账号，解决"不知道用哪个邮箱注册"，
 * 链接本身就是网址，没有这个痛点）。
 */
public final class Nav {

    public interface Cb {
        /** 选中某个分类；uuid 为空表示「全部」 */
        void onCat(String uuid, String name);
        void onFav(String name);
        void onTrash(String name);
        /** 仅密码箱：按域名聚合找回账号 */
        void onRecover();
        /** 仅密码箱：卡包 */
        void onCardAll();
        void onCardKind(String kind);
    }

    /** 当前选择态，一次性传进来，避免回调里反复读可变字段 */
    public static final class State {
        public String curCat = "";
        public int curMode;        // 0 分类 1 收藏 2 回收站
        public int curZone;        // 0 账号 1 卡包
        public String curCardKind = "";
    }

    public static void render(BaseActivity a, LinearLayout navList, int kind, State st, Cb cb) {
        if (navList == null) return;
        navList.removeAllViews();
        Db db = Db.get(a);

        /* 一次查询取回本库的全部导航计数（分类计数 + 总数 + 收藏 + 回收站），
           取代原先 4~5 次分散查询 —— 其中链接库那边还是把整表拉到内存里数，
           是最重的一处。切分类、增删条目都会重画导航，这里省下来的很明显。 */
        Db.NavCounts nc = db.navCounts(kind, db.defaultCatUuid(kind));
        Map<String, Integer> cnts = nc.byCat;

        if (kind == 0) {
            /* ---------- 密码箱：账号区 ---------- */
            navList.addView(zoneTitle(a, "账号"));
            navList.addView(navItem(a, "all", "全部", nc.total, 0xFF00B8A0L,
                    st.curZone == 0 && st.curMode == 0 && st.curCat.isEmpty(),
                    () -> cb.onCat("", "全部")));
            for (Db.Cat c : db.cats(kind)) {
                final String u = c.uuid;
                Integer n = cnts.get(u);
                navList.addView(navItem(a, c.icon == null ? "more" : c.icon, c.name,
                        n == null ? 0 : n, c.color,
                        st.curZone == 0 && st.curMode == 0 && st.curCat.equals(u),
                        () -> cb.onCat(u, c.name)));
            }
            navList.addView(navItem(a, "star", "收藏", nc.fav, 0xFFF59E0BL,
                    st.curZone == 0 && st.curMode == 1, () -> cb.onFav("收藏")));
            navList.addView(navItem(a, "web", "聚合", 0, 0xFF38BDF8L, false, cb::onRecover));
        } else {
            /* ---------- 链接库：链接区 ---------- */
            navList.addView(zoneTitle(a, "链接"));
            navList.addView(navItem(a, "link", "全部", nc.total, 0xFF00B8A0L,
                    st.curMode == 0 && st.curCat.isEmpty(), () -> cb.onCat("", "全部")));
            /* 必须传 kind：db.cats() 无参版本返回的是密码箱的分类（kind=0），
               这里如果不传，链接库导航列的就是密码箱那套分类，
               跟弹窗里看到的对不上。 */
            /* 全部列出，不做"空分类隐藏"。
               之前这里有一句 `if (cnt == 0 && ...) continue;`，
               结果弹窗里能看到 10 个分类，导航里只显示有内容的那几个 ——
               两边对不上，看着像分类丢了。密码箱分支本来就没有这个过滤，
               现在两边统一：无论有没有内容都显示，计数为 0 就显示 0。 */
            for (Db.Cat c : db.cats(kind)) {
                final String u = c.uuid;
                Integer n = cnts.get(u);
                navList.addView(navItem(a, c.icon == null ? "more" : c.icon, c.name,
                        n == null ? 0 : n, c.color,
                        st.curMode == 0 && st.curCat.equals(u),
                        () -> cb.onCat(u, c.name)));
            }
            navList.addView(navItem(a, "star", "收藏", nc.fav, 0xFFF59E0BL,
                    st.curMode == 1, () -> cb.onFav("收藏")));
        }

        /* ---------- 卡包区：仅密码箱 ---------- */
        if (kind == 0) {
            navList.addView(divider(a));
            navList.addView(zoneTitle(a, "卡包"));
            int cardAll = db.countCards(false);
            navList.addView(navItem(a, "card", "全部卡片", cardAll, 0xFFA78BFA,
                    st.curZone == 1 && st.curCardKind.isEmpty() && st.curMode == 0,
                    cb::onCardAll));
            Map<String, Integer> kindCnts = db.countsByKind(false);
            for (int i = 0; i < Db.CARD_KINDS.length; i++) {
                final String k = Db.CARD_KINDS[i];
                Integer kv = kindCnts.get(k);
                int cnt = kv == null ? 0 : kv;
                if (cnt == 0 && !k.equals(st.curCardKind)) continue;
                navList.addView(navItem(a, Db.CARD_ICONS[i], k, cnt, kindColor(k),
                        st.curZone == 1 && st.curCardKind.equals(k),
                        () -> cb.onCardKind(k)));
            }
        }

        /* ---------- 回收站：两边都有 ---------- */
        navList.addView(divider(a));
        /* 各库只统计自己删掉的东西：密码箱含卡片，链接库只算链接 */
        int trashN = kind == 0 ? nc.trash + db.countCards(true) : nc.trash;
        navList.addView(navItem(a, "trash", "回收站", trashN, 0xFF94A3B8L,
                st.curMode == 2, () -> cb.onTrash("回收站")));
    }

    /* ---------------- 组件 ---------------- */

    public static View zoneTitle(BaseActivity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(10.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.attr(a, R.attr.textColor2));
        t.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), 0, Ui.dp(a, 4));
        return t;
    }

    public static View divider(BaseActivity a) {
        View v = new View(a);
        v.setBackgroundColor(Ui.attr(a, R.attr.strokeColor));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 1));
        l.setMargins(Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        v.setLayoutParams(l);
        return v;
    }

    /** 导航项：图标（分类色）+ 名称 + 数量角标 */
    public static View navItem(BaseActivity a, String ic, String name, int count,
                               long color, boolean active, Runnable go) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 44));
        lp.topMargin = Ui.dp(a, 3);
        row.setLayoutParams(lp);
        if (active) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.withAlpha(a.accent(), Ui.isDark(a) ? 45 : 28));
            g.setCornerRadius(Ui.dp(a, 12));
            row.setBackground(g);
        }
        row.setPadding(Ui.dp(a, 7), 0, Ui.dp(a, 6), 0);

        row.addView(a.iconBadge(ic, active ? 0xFF00B8A0L : color, 30));

        TextView t = new TextView(a);
        t.setText(name);
        t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(active ? a.accent() : Ui.attr(a, R.attr.textColor));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(a, 6));
        t.setLayoutParams(tl);
        row.addView(t);

        row.addView(countBadge(a, count, active));

        Ui.press(row);
        row.setOnClickListener(v -> go.run());
        return row;
    }

    public static TextView countBadge(BaseActivity a, int n, boolean active) {
        TextView t = new TextView(a);
        t.setText(n + "");
        t.setTextSize(10.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(active ? a.accent() : Ui.attr(a, R.attr.textColor2));
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(active ? Ui.withAlpha(a.accent(), 40)
                : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 30));
        g.setCornerRadius(999f);
        t.setBackground(g);
        t.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2));
        t.setMinWidth(Ui.dp(a, 20));
        return t;
    }

    static long kindColor(String kind) {
        for (int i = 0; i < Db.CARD_KINDS.length; i++) {
            if (Db.CARD_KINDS[i].equals(kind)) {
                long[] cs = {0xFF38BDF8L, 0xFF22C55EL, 0xFFF59E0BL, 0xFFA78BFAL,
                        0xFFFF6B9DL, 0xFF00B8A0L, 0xFF94A3B8L};
                return cs[i % cs.length];
            }
        }
        return 0xFF94A3B8L;
    }

    private Nav() { }
}
