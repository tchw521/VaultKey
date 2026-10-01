package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Ico;
import com.vaultkey.util.IndexBar;
import com.vaultkey.util.Ui;

/**
 * 「密码箱 / 链接库」共用的页面。
 *
 * 这两个页的界面与交互本来就应当一致（左侧分类导航 + 右侧搜索 + A-Z 列表），
 * 早期各写一套导致样式跑偏，所以抽成同一个类，**靠 kind 区分内容**：
 *
 *   kind = 0  密码箱：账号 + 卡包 + 找回 + 回收站
 *   kind = 1  链接库：链接 + 回收站（**无卡包、无找回**）
 *
 * 差异只有三处，其余代码完全共用：
 *   1. 列表用 {@code new VaultList(a, kind)}，链接模式下点条目直接开浏览器
 *   2. 卡包区（CardList + 回收站的「账号/卡片」切换）只在 kind=0 建
 *   3. 导航由 {@link Nav} 按 kind 渲染
 */
public final class VaultPage {

    /** 页面状态变化时通知宿主（更新标题、按钮、批量条） */
    public interface Host {
        void onState();
        void goCats(int kind);
        void goRecover();
        void addCard();
        /** 勾选项变化（批量操作条的计数要跟着变） */
        void onSelChanged();
    }

    private final BaseActivity a;
    private final int kind;
    private final Host host;

    private LinearLayout navHost;
    private LinearLayout navList;
    private int navW;
    private boolean navShown = true;
    private FrameLayout contentArea;
    private FrameListHost listHost;
    private VaultList list;
    private CardList cards;              // 仅 kind=0
    private IndexBar indexBar;
    private TextView bubble;
    private EditText search;
    private LinearLayout trashSwitch;    // 仅 kind=0

    private String curCat = "";
    private String curCatName = "全部";
    private int curMode = 0;             // 0 分类 1 收藏 2 回收站
    private int curZone = 0;             // 0 账号 1 卡包
    private String curCardKind = "";
    private boolean trashShowCards;

    public VaultPage(BaseActivity a, int kind, Host host) {
        this.a = a;
        this.kind = kind;
        this.host = host;
    }

    public int kind() { return kind; }

    /* ---------------- 构建 ---------------- */

    public View view() {
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.HORIZONTAL);

        int sw = a.getResources().getDisplayMetrics().widthPixels;
        navW = Math.max(Ui.dp(a, 108), Math.min((int) (sw * 0.32f), Ui.dp(a, 140)));

        /* ---- 左侧分类导航 ---- */
        navHost = new LinearLayout(a);
        navHost.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ng = new GradientDrawable();
        ng.setColor(Ui.attr(a, R.attr.cardColor));
        ng.setCornerRadius(Ui.dp(a, 18));
        ng.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.attr(a, R.attr.strokeColor));
        navHost.setBackground(ng);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                navW, ViewGroup.LayoutParams.MATCH_PARENT);
        nl.setMargins(0, 0, Ui.dp(a, 10), Ui.dp(a, 4));
        navHost.setLayoutParams(nl);

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        navList = new LinearLayout(a);
        navList.setOrientation(LinearLayout.VERTICAL);
        navList.setPadding(Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 12));
        sv.addView(navList);
        navHost.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView manage = new TextView(a);
        manage.setText("＋ 管理");
        manage.setTextSize(11.5f);
        manage.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        manage.setTextColor(a.accent());
        manage.setGravity(Gravity.CENTER);
        manage.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 12));
        Ui.press(manage);
        manage.setOnClickListener(v -> host.goCats(kind));
        navHost.addView(manage);

        page.addView(navHost);

        /* ---- 右侧内容 ---- */
        contentArea = new FrameLayout(a);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        search = a.field(kind == 1 ? "搜索名称 / 网址 / 备注 / 标签" : "搜索账号 / 网站 / 备注", null);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int x, int y, int z) {
                list.setQuery(s.toString());
                if (cards != null) cards.setQuery(s.toString());
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        v.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* 回收站的「账号 / 卡片」切换：链接没有卡片，不建 */
        if (kind == 0) {
            trashSwitch = new LinearLayout(a);
            trashSwitch.setOrientation(LinearLayout.HORIZONTAL);
            trashSwitch.setGravity(Gravity.CENTER_VERTICAL);
            trashSwitch.setVisibility(View.GONE);
            LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tsl.topMargin = Ui.dp(a, 8);
            trashSwitch.setLayoutParams(tsl);
            v.addView(trashSwitch);
        }

        list = new VaultList(a, kind);
        list.setGrouped(true);
        list.setOnChanged(new Runnable() {
            @Override public void run() { renderNav(); if (host != null) host.onState(); }
        });
        /* 勾选状态变化 → 让宿主更新批量操作条的数量与显隐 */
        list.setOnSelChanged(new Runnable() {
            @Override public void run() { if (host != null) host.onSelChanged(); }
        });

        if (kind == 0) {
            cards = new CardList(a);
            cards.setOnChanged(new Runnable() {
                @Override public void run() { renderNav(); if (host != null) host.onState(); }
            });
            listHost = new FrameListHost(a, list.view(), cards.view());
        } else {
            listHost = new FrameListHost(a, list.view(), null);
        }
        v.addView(listHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contentArea.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        indexBar = new IndexBar(a);
        indexBar.setCb(new IndexBar.Cb() {
            @Override public void onPick(String letter, int index) {
                list.jumpTo(letter);
                showBubble(letter);
            }
            @Override public void onUp() { hideBubble(); }
        });
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(
                Ui.dp(a, 30), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        il.setMargins(0, Ui.dp(a, 4), 0, Ui.dp(a, 20));
        contentArea.addView(indexBar, il);

        bubble = new TextView(a);
        int bs = Ui.dp(a, 76);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextSize(30);
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubble.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.withAlpha(a.accent(), 220));
        bubble.setBackground(g);
        bubble.setAlpha(0f);
        bubble.setVisibility(View.GONE);
        contentArea.addView(bubble, new FrameLayout.LayoutParams(bs, bs, Gravity.CENTER));

        page.addView(contentArea);

        renderNav();
        return page;
    }

    private void showBubble(String letter) {
        bubble.setText(letter);
        bubble.setVisibility(View.VISIBLE);
        bubble.animate().alpha(1f).setDuration(90).start();
    }

    private void hideBubble() {
        bubble.animate().alpha(0f).setDuration(180).start();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (bubble.getAlpha() < 0.02f) bubble.setVisibility(View.GONE);
            }
        }, 220);
    }

    /* ---------------- 导航开合 ---------------- */

    public boolean isNavShown() { return navShown; }

    public void setNav(boolean show) { applyNav(show); }

    public void toggleNav() { applyNav(!navShown); }

    /** 导航开合：内容区宽度变化 + 淡入，避免生硬地跳一下 */
    private void applyNav(boolean show) {
        navShown = show;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) navHost.getLayoutParams();
        lp.width = show ? navW : 0;
        lp.setMargins(0, 0, show ? Ui.dp(a, 10) : 0, Ui.dp(a, 4));
        navHost.setLayoutParams(lp);
        if (show) {
            navHost.setVisibility(View.VISIBLE);
            navHost.setAlpha(0f);
            navHost.animate().alpha(1f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            navHost.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                @Override public void run() {
                    if (!navShown) navHost.setVisibility(View.GONE);
                }
            }).start();
        }
        if (host != null) host.onState();
    }

    /* ---------------- 导航内容 ---------------- */

    public void renderNav() {
        if (navList == null) return;
        Nav.State st = new Nav.State();
        st.curCat = curCat;
        st.curMode = curMode;
        st.curZone = curZone;
        st.curCardKind = curCardKind;
        Nav.render(a, navList, kind, st, new Nav.Cb() {
            @Override public void onCat(String uuid, String name) { selectCat(uuid, name); }
            @Override public void onFav(String name) { selectMode(1, name); }
            @Override public void onTrash(String name) { selectMode(2, name); }
            @Override public void onRecover() { host.goRecover(); }
            @Override public void onCardAll() { selectCardKind("", "卡包"); }
            @Override public void onCardKind(String k) { selectCardKind(k, k); }
        });
    }

    public void selectCat(String uuid, String name) {
        curZone = 0;
        curMode = 0;
        trashShowCards = false;
        curCat = uuid;
        curCatName = name;
        listHost.show(true);
        list.setFavOnly(false);
        list.setTrash(false);
        list.setGrouped(true);
        list.setCat(uuid);
        list.setQuery(search.getText().toString());
        list.refresh();
        renderNav();
        renderTrashSwitch();
        indexBar.setVisibility(View.VISIBLE);
        if (host != null) host.onState();
    }

    public void selectMode(int mode, String name) { selectMode(mode, name, false); }

    /**
     * @param cardsToo 回收站专用：true 表示展示「已删除的卡片」而非「已删除的账号」。
     *                 之前回收站只显示账号，被删掉的卡片既看不到也恢复不了，等于永久丢失。
     */
    public void selectMode(int mode, String name, boolean cardsToo) {
        curMode = mode;
        curCat = "";
        curZone = 0;
        curCardKind = "";
        curCatName = name;
        trashShowCards = (mode == 2) && cardsToo;
        list.setCat("");
        /* 两个标志独立设置：收藏只看 favOnly，回收站只看 trash */
        list.setFavOnly(mode == 1);
        list.setTrash(mode == 2 && !trashShowCards);
        list.setGrouped(mode != 2);
        if (cards != null) {
            cards.setTrash(trashShowCards);
            cards.setKind("");
            cards.refresh();
        }
        listHost.show(!trashShowCards);
        list.setQuery(search.getText().toString());
        list.refresh();
        renderNav();
        indexBar.setVisibility(mode == 2 ? View.GONE : View.VISIBLE);
        renderTrashSwitch();
        if (host != null) host.onState();
    }

    /** 回收站顶部：账号 / 卡片 二选一（仅密码箱） */
    private void renderTrashSwitch() {
        if (trashSwitch == null) return;
        trashSwitch.removeAllViews();
        if (curMode != 2) { trashSwitch.setVisibility(View.GONE); return; }
        trashSwitch.setVisibility(View.VISIBLE);
        String[] ns = {"账号", "卡片"};
        for (int i = 0; i < 2; i++) {
            final boolean wantCards = i == 1;
            TextView t = new TextView(a);
            t.setText(ns[i] + "(" + (wantCards ? Db.get(a).countCards(true)
                    : Db.get(a).count(true, 0)) + ")");
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            boolean on = trashShowCards == wantCards;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(999f);
            g.setColor(on ? Ui.withAlpha(a.accent(), 42)
                    : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 20));
            if (on) g.setStroke(Math.max(1, Ui.dp(a, 1)), a.accent());
            t.setBackground(g);
            t.setTextColor(on ? a.accent() : Ui.attr(a, R.attr.textColor2));
            t.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(Ui.dp(a, 8));
            t.setLayoutParams(lp);
            Ui.press(t);
            t.setOnClickListener(v -> selectMode(2, "回收站", wantCards));
            trashSwitch.addView(t);
        }
    }

    public void selectCardKind(String k, String name) {
        if (cards == null) return;
        curZone = 1;
        curMode = 0;
        trashShowCards = false;
        curCardKind = k;
        curCatName = name;
        listHost.show(false);
        cards.setTrash(false);
        cards.setKind(k);
        cards.setQuery(search.getText().toString());
        cards.refresh();
        renderNav();
        renderTrashSwitch();
        indexBar.setVisibility(View.GONE);
        if (host != null) host.onState();
    }

    /* ---------------- 对外接口 ---------------- */

    public String title() { return curCatName; }

    public VaultList list() { return list; }

    public CardList cards() { return cards; }



    public void refresh() {
        list.refresh();
        if (cards != null) cards.refresh();
        renderNav();
    }

    /** 返回键：先退回「全部 / 非卡包」，返回 true 表示已消费 */
    public boolean onBack() {
        if (curMode != 0) { selectCat("", "全部"); return true; }
        if (!curCat.isEmpty()) { selectCat("", "全部"); return true; }
        if (curZone == 1 && !curCardKind.isEmpty()) { selectCardKind("", "卡包"); return true; }
        return false;
    }

    /** 右下角加号：密码箱加账号 / 卡，链接库加链接 */
    public void add() {
        if (kind == 1) {
            Intent i = new Intent(a, EntryEditActivity.class);
            i.putExtra("kind", 1);
            if (!curCat.isEmpty()) i.putExtra("cat", curCat);
            a.startActivity(i);
            return;
        }
        if (curZone == 1) { host.addCard(); return; }
        Intent i = new Intent(a, EntryEditActivity.class);
        if (curMode == 0 && !curCat.isEmpty()) i.putExtra("cat", curCat);
        a.startActivity(i);
    }

    /** 小部件 / 快捷方式进来时聚焦搜索框 */
    public void focusSearch() {
        if (search == null) return;
        search.requestFocus();
        search.postDelayed(new Runnable() {
            @Override public void run() {
                android.view.inputmethod.InputMethodManager im =
                        (android.view.inputmethod.InputMethodManager)
                                a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (im != null) {
                    im.showSoftInput(search,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }, 220);
    }

    /** 供宿主更新 ☰ 图标用 */
    public static android.graphics.drawable.Drawable menuIcon(BaseActivity a, boolean shown) {
        return Ico.get(a, "menu", shown ? a.accent() : Ui.attr(a, R.attr.textColor), 24);
    }
}
