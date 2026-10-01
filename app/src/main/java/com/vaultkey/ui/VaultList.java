package com.vaultkey.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Icons;
import com.vaultkey.util.PasswordGen;
import com.vaultkey.util.Pinyin;
import com.vaultkey.util.Totp;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 账号列表：支持 A-Z 拼音分组（带字母分隔条）与平铺两种模式 */
public final class VaultList {
    private final BaseActivity a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Ad ad = new Ad();
    private ListView list;
    private TextView emptyView;

    private String catUuid = "";
    private String query = "";
    private boolean favOnly, trash, grouped = true;
    private boolean selectMode;
    private final java.util.Set<Long> selected = new java.util.LinkedHashSet<>();
    private Runnable onSelChanged;
    private List<Db.Cat> cats = new ArrayList<>();
    private Runnable onChanged;

    /** 字母 -> 该字母首个条目在适配器中的位置（含分隔条） */
    private final Map<String, Integer> letterPos = new HashMap<>();

    /** 0 = 账号密码，1 = 网址收藏。同一个列表组件服务两个 Tab，靠它区分 */
    private final int kind;

    public VaultList(BaseActivity a) { this(a, 0); }

    public VaultList(BaseActivity a, int kind) {
        this.a = a;
        this.kind = kind;
        clearClip = () -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                try { cm.setPrimaryClip(ClipData.newPlainText("", "")); } catch (Exception ignored) { }
            }
        };
    }

    public void setOnChanged(Runnable r) { onChanged = r; }
    public void setOnSelChanged(Runnable r) { onSelChanged = r; }

    public boolean isSelectMode() { return selectMode; }
    public java.util.Set<Long> selected() { return selected; }

    /**
     * 长按条目 → 「选择多项」：直接进入多选态并选中这一条。
     * 右上角原本有个「选择」按钮，挤在标题旁边不好看，这里改成长按触发。
     */
    public void enterSelectWith(long id) {
        setSelectMode(true);
        selected.add(id);
        if (ad != null) ad.notifyDataSetChanged();
        if (onSelChanged != null) onSelChanged.run();
    }

    public void setSelectMode(boolean on) {
        selectMode = on;
        if (!on) selected.clear();
        if (ad != null) ad.notifyDataSetChanged();
        if (onSelChanged != null) onSelChanged.run();
    }

    public void selectAll(boolean all) {
        selected.clear();
        if (all) {
            for (Row r : ad.rows) if (!r.isHeader() && r.e != null) selected.add(r.e.id);
        }
        ad.notifyDataSetChanged();
        if (onSelChanged != null) onSelChanged.run();
    }

    private void toggleSel(long id) {
        if (selected.contains(id)) selected.remove(id); else selected.add(id);
        ad.notifyDataSetChanged();
        if (onSelChanged != null) onSelChanged.run();
    }
    public void setGrouped(boolean g) { grouped = g; }
    public void setCat(String uuid) { catUuid = uuid == null ? "" : uuid; }
    /**
     * 注意：这两个 setter 曾经互相清空对方（setFavOnly 里写 trash=false，反之亦然），
     * 而调用方是「先设一个再设另一个」，结果后一次调用把前一个标志又清掉了，
     * 两个标志同时为 false → 列表退化成「全部」。
     * 这是「收藏/回收站里明明没有却显示所有账号」的根因。现在两者互相独立。
     */
    public void setFavOnly(boolean on) { favOnly = on; }
    public void setTrash(boolean on) { trash = on; }
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
        list.setDividerHeight(Ui.dp(a, 8));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(ad);
        list.setPadding(Ui.dp(a, 2), 0, Ui.dp(a, 30), Ui.dp(a, 90));
        list.setClipToPadding(false);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refresh();
        return box;
    }

    public void refresh() {
        if (list == null || Session.key() == null) return;
        cats = Db.get(a).cats(kind);
                /*
         * 回收站也按 kind 过滤 —— 两个库彻底隔离。
         *
         * 之前是 trash ? -1 : kind（不限类型），理由是怕"删了链接找不到"。
         * 但现在链接库有自己的回收站入口，隔离后各自只显示自己删掉的东西：
         * 密码箱回收站 = 删掉的账号 + 卡片，链接库回收站 = 删掉的链接。
         * 反而不容易在密码箱里翻到一堆链接、在链接库里翻到一堆账号。
         */
        List<Db.Entry> data = Db.get(a).list(
                catUuid.isEmpty() ? null : catUuid, favOnly, trash, query, kind);
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
        /* 空态提示按内容类型区分：链接库显示"还没有链接"，
           否则链接库里也提示"还没有账号"，跟内容对不上 */
        String thing = kind == 1 ? "链接" : "账号";
        emptyView.setText(trash ? "回收站是空的"
                : favOnly ? (query.isEmpty() ? "还没有收藏" : "没有匹配的收藏")
                : query.isEmpty() ? "还没有" + thing + "，点右下角 + 添加"
                : "没有匹配的" + thing);
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 字母索引跳转：返回该字母分隔条的位置，-1 表示没有 */
    public int positionOfLetter(String letter) {
        Integer p = letterPos.get(letter);
        return p == null ? -1 : p;
    }

    public void jumpTo(String letter) {
        int p = positionOfLetter(letter);
        if (p >= 0) list.setSelectionFromTop(p, 0);
    }

    /* ---------------- 复制 ---------------- */

    void copy(String label, String val) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        try { cm.setPrimaryClip(ClipData.newPlainText(label, val)); } catch (Exception e) { return; }
        a.toast(label + " 已复制，" + Prefs.getI("clip_sec", 45) + " 秒后清空");
        h.removeCallbacks(clearClip);
        h.postDelayed(clearClip, Prefs.getI("clip_sec", 45) * 1000L);
    }

    private final Runnable clearClip;

    /* ---------------- 详情 ---------------- */

    /**
     * 把这条浮成小窗（列表右侧按钮）。
     *
     * 用途：准备去别的应用登录时，先把账号密码浮出来，
     * 切过去之后直接从悬浮窗复制，不用再切回密盒翻找。
     *
     * 需要先有悬浮窗权限。没开过的话直接引导去开 ——
     * 静默失败会让用户以为按钮坏了。
     */
    /**
     * 用浏览器打开网址。
     *
     * 没写 scheme 时补 https:// —— 直接 Uri.parse("github.com/xxx") 虽然不抛异常，
     * 但 startActivity 会找不到能处理它的 Activity，表现就是「点了没反应」。
     * 而 "github.com/xxx" 恰恰是收藏链接时最常见的输入形式。
     *
     * 判定用 equalsIgnoreCase 而非 contains：大写写成 "HTTPS://" 时
     * startsWith("https://") 认不出来，会被补成 "https://HTTPS://..."。
     */
    private void openUrl(Db.Entry e) {
        String u = e.url == null ? "" : e.url.trim();
        if (u.isEmpty()) { a.toast("这条还没有填网址"); return; }
        boolean hasScheme = u.length() > 7
                && (u.regionMatches(true, 0, "http://", 0, 7)
                || (u.length() > 8 && u.regionMatches(true, 0, "https://", 0, 8)));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(hasScheme ? u : "https://" + u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception ex) {
            a.toast("打不开这个网址");
        }
    }

    private void floatEntry(final Db.Entry e) {
        if (!com.vaultkey.service.FloatService.canDraw(a)) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("开启悬浮窗")
                    .setMessage("开启后，可以把这条账号密码浮成小窗。"
                            + "切到别的应用登录时，直接从小窗复制，不用再切回密盒。\n\n"
                            + "密盒不会读取你在其他应用输入的任何内容。")
                    .setPositiveButton("开启悬浮窗", (d, w) -> {
                        com.vaultkey.service.FloatService.setEnabled(true);
                        com.vaultkey.service.FloatService.request(a);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        com.vaultkey.service.FloatService.setEnabled(true);
        com.vaultkey.service.FloatService.showEntry(a, e.uuid);
        a.toast("已浮出，切到别的 app 就能直接复制");
    }

    private void showEntry(Db.Entry e) {
        android.app.Dialog d = new android.app.Dialog(a, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        /* 实色底：毛玻璃底会让背后的账号列表透上来，两层内容叠一起就没法读了 */
        wrap.setBackground(Ui.sheet(a, 28));
        wrap.setPadding(Ui.dp(a, 20), Ui.dp(a, 20), Ui.dp(a, 20), Ui.dp(a, 20));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, Ui.dp(a, 12));
        FrameBadge badge = new FrameBadge(a, e);
        head.addView(badge);
        TextView ti = new TextView(a);
        ti.setText(e.title.isEmpty() ? e.user : e.title);
        ti.setTextSize(20);
        ti.setTypeface(Typeface.DEFAULT_BOLD);
        ti.setTextColor(Ui.attr(a, R.attr.textColor));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(a, 12));
        ti.setLayoutParams(tl);
        head.addView(ti);
        wrap.addView(head);

        /* 网址收藏没有账号密码，显示空行只会让人困惑。
           链接模式下把「网址」提到最前面，它才是主内容。 */
        if (e.kind == 1) {
            if (e.kind != 1 && !e.url.isEmpty()) wrap.addView(row("网址", e.url, true));
        } else {
            wrap.addView(row("账号", e.user, true));
            wrap.addView(passRow(e));
        }
        if (!e.totp.isEmpty()) {
            TextView otp = new TextView(a);
            otp.setTextSize(15);
            otp.setTypeface(Typeface.MONOSPACE);
            otp.setTextColor(Ui.accent(a));
            otp.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4));
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                if (!d.isShowing()) return;
                otp.setText("动态验证码  " + Totp.code(e.totp, System.currentTimeMillis()) + "   (" + Totp.remain() + "s)");
                h.postDelayed(tick[0], 1000);
            };
            tick[0].run();
            wrap.addView(otp);
        }
        java.util.List<String> tg = Db.tagList(e.tags);
        if (!tg.isEmpty()) {
            LinearLayout tr = new LinearLayout(a);
            tr.setOrientation(LinearLayout.HORIZONTAL);
            tr.setGravity(Gravity.CENTER_VERTICAL);
            tr.setPadding(0, Ui.dp(a, 12), 0, 0);
            TextView tl2 = new TextView(a);
            tl2.setText("标签：");
            tl2.setTextSize(12);
            tl2.setTextColor(Ui.attr(a, R.attr.textColor2));
            tr.addView(tl2);
            LinearLayout strip = TagPicker.tagStrip(a, tg);
            tr.addView(strip);
            wrap.addView(tr);
        }
        java.util.List<Db.F> cs = Db.customs(e.custom);
        if (!cs.isEmpty()) {
            for (Db.F f : cs) {
                if (f.k == null || f.k.isEmpty()) continue;
                /* 字段名带 pass/密钥/token 等字样时默认遮住，点一下才显示 */
                boolean secret = f.k.toLowerCase().contains("pass") || f.k.toLowerCase().contains("密")
                        || f.k.toLowerCase().contains("key") || f.k.toLowerCase().contains("token")
                        || f.k.toLowerCase().contains("secret") || f.k.toLowerCase().contains("恢复码");
                wrap.addView(row(f.k, f.v, false, secret));
            }
        }
        if (!e.url.isEmpty()) wrap.addView(row("网址", e.url, true));
        if (!e.notes.isEmpty()) wrap.addView(row("备注", e.notes, true));
        wrap.addView(row("分类", catName(e.catUuid), false));
        wrap.addView(row("更新", android.text.format.DateFormat.getDateFormat(a).format(new java.util.Date(e.mtime)), false));

        if (!e.url.isEmpty() || (e.pkg != null && !e.pkg.isEmpty())) {
            android.widget.Button open = a.button("打开 " + (!e.url.isEmpty() ? e.url : Icons.appName(a, e.pkg)),
                    new int[]{Ui.accent2(a), Ui.accent(a)});
            open.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ol.topMargin = Ui.dp(a, 10);
            open.setOnClickListener(v -> {
                try {
                    if (e.pkg != null && !e.pkg.isEmpty()) {
                        Intent li = a.getPackageManager().getLaunchIntentForPackage(e.pkg);
                        if (li != null) { a.startActivity(li); return; }
                    }
                    if (!e.url.isEmpty()) a.startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(e.url.startsWith("http") ? e.url : "https://" + e.url)));
                } catch (Exception ex) { a.toast("无法打开"); }
            });
            wrap.addView(open, ol);
        }

        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, Ui.dp(a, 18), 0, 0);
        android.widget.Button edit = a.button("编辑", new int[]{Ui.accent(a), Ui.accent2(a)});
        edit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        edit.setOnClickListener(v -> { d.dismiss(); a.startActivity(new Intent(a, EntryEditActivity.class).putExtra("uuid", e.uuid)); });
        acts.addView(edit);
        android.widget.Button del = a.button(trash ? "彻底删除" : "删除", new int[]{a.getResources().getColor(R.color.bad), Ui.accent2(a)});
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dl.setMarginStart(Ui.dp(a, 12));
        del.setLayoutParams(dl);
        del.setOnClickListener(v -> {
            d.dismiss();
            if (trash) Db.get(a).hardDelete(e.id); else Db.get(a).softDelete(e.id);
            refresh();
            if (onChanged != null) onChanged.run();
        });
        acts.addView(del);
        wrap.addView(acts);

        android.widget.Button close = new android.widget.Button(a);
        close.setText("关闭");
        close.setTextColor(Ui.attr(a, R.attr.textColor2));
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(0, Ui.dp(a, 10), 0, 0);
        close.setOnClickListener(v -> d.dismiss());
        wrap.addView(close);

        android.widget.FrameLayout fl = new android.widget.FrameLayout(a);
        fl.setBackgroundColor(Ui.withAlpha(Color.BLACK, 140));
        android.widget.FrameLayout.LayoutParams wlp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wlp.setMargins(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        fl.addView(wrap, wlp);
        fl.setOnClickListener(v -> d.dismiss());
        d.setContentView(fl);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        d.show();
        Ui.popIn(wrap, 0);
    }

    private String catName(String uuid) {
        for (Db.Cat c : cats) if (c.uuid.equals(uuid)) return c.name;
        /* 兜底：万一分类在另一个库里（比如导入的数据），也别显示"未分类" */
        for (Db.Cat c : Db.get(a).cats(kind == 1 ? 0 : 1)) if (c.uuid.equals(uuid)) return c.name;
        return "未分类";
    }

    /** secret=true 时值默认打码，点击才显形 */
    private View row(String label, String value, boolean copyable, boolean secret) {
        final boolean[] shown = {!secret};
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));

        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setTextColor(Ui.attr(a, R.attr.textColor2));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 78), ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(t);

        final TextView v = new TextView(a);
        v.setText(shown[0] ? value : "••••••••");
        v.setTextSize(13.5f);
        v.setTextColor(Ui.attr(a, R.attr.textColor));
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vl.setMarginStart(Ui.dp(a, 6));
        v.setLayoutParams(vl);
        l.addView(v);

        if (secret) {
            v.setOnClickListener(x -> {
                shown[0] = !shown[0];
                v.setText(shown[0] ? value : "••••••••");
            });
        }
        if (copyable) {
            TextView c = new TextView(a);
            c.setText("复制");
            c.setTextSize(11.5f);
            c.setTextColor(Ui.accent(a));
            c.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
            Ui.press(c);
            c.setOnClickListener(x -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
                a.toastCopied("");
            });
            l.addView(c);
        }
        return l;
    }

    private View row(String label, String value, boolean copyable) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.inner(a, 14));
        l.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(11);
        t.setTextColor(Ui.attr(a, R.attr.textColor2));
        l.addView(t);
        TextView b = new TextView(a);
        b.setText(value);
        b.setTextSize(15);
        b.setTextColor(Ui.attr(a, R.attr.textColor));
        b.setPadding(0, Ui.dp(a, 2), 0, 0);
        l.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        l.setLayoutParams(lp);
        if (copyable) l.setOnClickListener(v -> copy(label, value));
        return l;
    }

    private View passRow(Db.Entry e) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.inner(a, 14));
        l.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        TextView t = new TextView(a);
        t.setText("密码");
        t.setTextSize(11);
        t.setTextColor(Ui.attr(a, R.attr.textColor2));
        l.addView(t);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = new TextView(a);
        b.setText("••••••••");
        b.setTextSize(15);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(Ui.attr(a, R.attr.textColor));
        line.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView eye = new TextView(a);
        eye.setText("显示");
        eye.setTextSize(12);
        eye.setTextColor(Ui.accent(a));
        eye.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        final boolean[] shown = {false};
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            b.setText(shown[0] ? e.pass : "••••••••");
            eye.setText(shown[0] ? "隐藏" : "显示");
        });
        line.addView(eye);
        l.addView(line);
        int s = PasswordGen.strength(e.pass);
        TextView st = new TextView(a);
        st.setText("强度：" + PasswordGen.label(s) + "  " + s + "/100");
        st.setTextSize(11);
        st.setTextColor(s < 30 ? a.getResources().getColor(R.color.bad)
                : s < 60 ? a.getResources().getColor(R.color.warn) : a.getResources().getColor(R.color.ok));
        st.setPadding(0, Ui.dp(a, 6), 0, 0);
        l.addView(st);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        l.setLayoutParams(lp);
        l.setOnClickListener(v -> copy("密码", e.pass));
        return l;
    }

    /* ---------------- 适配器 ---------------- */

    private static final class Row {
        String header;
        Db.Entry e;
        Row(String h) { header = h; }
        Row(Db.Entry e) { this.e = e; }
        boolean isHeader() { return header != null; }
    }

    private final class Ad extends BaseAdapter {
        private final List<Row> rows = new ArrayList<>();

        void setData(List<Db.Entry> data) {
            rows.clear();
            letterPos.clear();
            List<Db.Entry> sorted = new ArrayList<>(data);
            if (grouped) {
                sorted.sort((x, y) -> {
                    int o = Integer.compare(Pinyin.order(nameOf(x)), Pinyin.order(nameOf(y)));
                    if (o != 0) return o;
                    return nameOf(x).compareToIgnoreCase(nameOf(y));
                });
                String last = null;
                for (Db.Entry e : sorted) {
                    String L = String.valueOf(Pinyin.first(nameOf(e)));
                    if (!L.equals(last)) {
                        letterPos.putIfAbsent(L, rows.size());
                        rows.add(new Row(L));
                        last = L;
                    }
                    rows.add(new Row(e));
                }
            } else {
                sorted.sort((x, y) -> Long.compare(y.mtime, x.mtime));
                for (Db.Entry e : sorted) rows.add(new Row(e));
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getItemViewType(int p) { return rows.get(p).isHeader() ? 0 : 1; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public boolean isEnabled(int p) { return !rows.get(p).isHeader(); }

        @Override public View getView(int p, View cv, ViewGroup parent) {
            Row r = rows.get(p);
            if (r.isHeader()) return headerView(r.header);
            return entryView(r.e);
        }

        private View headerView(String letter) {
            TextView t = new TextView(a);
            t.setText(letter);
            t.setTextSize(12);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.accent(a));
            t.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), 0, Ui.dp(a, 6));
            t.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return t;
        }

        private View entryView(Db.Entry e) {
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackground(Ui.glass(a, 20, R.attr.cardColor, R.attr.strokeColor));
            int pd = Ui.dp(a, 12);
            l.setPadding(pd, pd, pd, pd);

            ImageView icon = new ImageView(a);
            int s = Ui.dp(a, 44);
            icon.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bindIcon(icon, e);
            l.addView(icon);

            LinearLayout mid = new LinearLayout(a);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.setMarginStart(Ui.dp(a, 12));
            mid.setLayoutParams(ml);
            TextView t1 = new TextView(a);
            t1.setText((e.fav == 1 ? "★ " : "") + (e.title.isEmpty() ? e.user : e.title));
            t1.setTextSize(16);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setTextColor(Ui.attr(a, R.attr.textColor));
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(t1);
            TextView t2 = new TextView(a);
            String sub = e.user.isEmpty() ? e.url : e.user;
            if (sub.isEmpty()) sub = e.notes;
            t2.setText(sub);
            t2.setTextSize(12);
            t2.setTextColor(Ui.attr(a, R.attr.textColor2));
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(t2);
            java.util.List<String> tg = Db.tagList(e.tags);
            if (!tg.isEmpty()) {
                LinearLayout strip = TagPicker.tagStrip(a, tg);
                LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stl.topMargin = Ui.dp(a, 4);
                strip.setLayoutParams(stl);
                mid.addView(strip);
            }
            l.addView(mid);

            /* 列表右侧悬浮按钮：点一下把这条的账号密码浮成小窗，
               之后切到别的应用粘贴时不用再回来找。
               链接没有账号密码，悬浮它没意义，所以链接模式不显示。 */
            if (!selectMode && kind != 1) {
                final android.widget.ImageView fb = new android.widget.ImageView(a);
                int fbs = Ui.dp(a, 30);
                fb.setLayoutParams(new LinearLayout.LayoutParams(fbs, fbs));
                fb.setScaleType(ImageView.ScaleType.CENTER);
                fb.setImageDrawable(Ico.get(a, "float", Ui.attr(a, R.attr.textColor2), 17));
                android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable();
                fg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                fg.setColor(Ui.withAlpha(a.accent(), Ui.isDark(a) ? 40 : 26));
                fb.setBackground(fg);
                fb.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
                fb.setContentDescription("悬浮显示");
                Ui.press(fb);
                fb.setOnClickListener(v -> floatEntry(e));
                LinearLayout.LayoutParams fbl = (LinearLayout.LayoutParams) fb.getLayoutParams();
                fbl.setMarginStart(Ui.dp(a, 6));
                l.addView(fb);
            }

            if (!e.totp.isEmpty()) {
                TextView otp = new TextView(a);
                otp.setText(Totp.code(e.totp, System.currentTimeMillis()));
                otp.setTextSize(13);
                otp.setTypeface(Typeface.MONOSPACE);
                otp.setTextColor(Ui.accent(a));
                otp.setPadding(Ui.dp(a, 8), 0, 0, 0);
                l.addView(otp);
            }

            if (selectMode) {
                final android.widget.CheckBox cb = new android.widget.CheckBox(a);
                cb.setChecked(selected.contains(e.id));
                cb.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cb.setClickable(false);
                l.addView(cb, 0);
                boolean on = selected.contains(e.id);
                android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
                sg.setColor(on ? Ui.withAlpha(a.accent(), Ui.isDark(a) ? 40 : 26)
                        : Ui.attr(a, R.attr.cardColor));
                sg.setCornerRadius(Ui.dp(a, 20));
                sg.setStroke(Math.max(1, Ui.dp(a, 1)), on ? a.accent() : Ui.attr(a, R.attr.strokeColor));
                l.setBackground(sg);
            }

            l.setOnClickListener(v -> {
                if (selectMode) { toggleSel(e.id); return; }
                Db.get(a).touchUse(e.id);
                /* 网址收藏：点一下直接用浏览器打开。
                   收藏链接就是为了快速跳转，多数一层弹窗都是多余的步骤。
                   要看备注 / 标签时走长按菜单里的「详情」。 */
                if (kind == 1 && !trash) { openUrl(e); return; }
                showEntry(e);
            });
            l.setOnLongClickListener(v -> {
                if (selectMode) { toggleSel(e.id); return true; }
                PopupMenu pm = new PopupMenu(a, v);
                if (kind == 1 && !trash) {
                    /* 链接的操作集合：和账号完全不同，没有账号密码可复制 */
                    pm.getMenu().add("打开链接");
                    pm.getMenu().add("复制链接");
                } else {
                    pm.getMenu().add("复制账号");
                    pm.getMenu().add("复制密码");
                }
                pm.getMenu().add("详情");
                if (!trash) pm.getMenu().add("编辑");
                /* 回收站也要能多选：否则只能一条条恢复/彻底删除，很慢 */
                pm.getMenu().add("选择多项");
                if (trash) { pm.getMenu().add("恢复"); pm.getMenu().add("彻底删除"); }
                else pm.getMenu().add("删除");
                pm.setOnMenuItemClickListener(it -> {
                    switch (it.getTitle().toString()) {
                        case "打开链接": openUrl(e); break;
                        case "复制链接": copy("网址", e.url); break;
                        case "详情": showEntry(e); break;
                        case "复制账号": copy("账号", e.user); break;
                        case "复制密码": copy("密码", e.pass); break;
                        case "编辑": a.startActivity(new Intent(a, EntryEditActivity.class).putExtra("uuid", e.uuid)); break;
                        case "选择多项": enterSelectWith(e.id); break;
                        case "删除": Db.get(a).softDelete(e.id); refresh(); if (onChanged != null) onChanged.run(); break;
                        case "恢复": Db.get(a).restore(e.id); refresh(); if (onChanged != null) onChanged.run(); break;
                        case "彻底删除": Db.get(a).hardDelete(e.id); refresh(); if (onChanged != null) onChanged.run(); break;
                    }
                    return true;
                });
                pm.show();
                return true;
            });
            l.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return l;
        }

        private void bindIcon(ImageView iv, Db.Entry e) {
            int r = Ui.dp(a, 14);
            /* 1) 条目自己存过图标键（在线取到过的），优先用 —— 即使没网址、本机没装 */
            if (e.iconKey != null && !e.iconKey.isEmpty()) {
                Bitmap cached = Icons.peek(a, e.iconKey);
                if (cached != null) {
                    iv.setImageBitmap(Icons.round(cached, r));
                    iv.setBackground(Ui.roundRect(Color.TRANSPARENT, r));
                    iv.setPadding(0, 0, 0, 0);
                    return;
                }
            }
            Drawable d = Icons.appIcon(a, e.pkg);
            if (d == null && e.pkg != null && !e.pkg.isEmpty()) {
                // 本机没装这个 App 时，用之前在线获取并缓存的图标
                Bitmap cached = Icons.peek(a, "pkg:" + e.pkg);
                if (cached != null) {
                    iv.setImageBitmap(Icons.round(cached, r));
                    iv.setBackground(Ui.roundRect(Color.TRANSPARENT, r));
                    iv.setPadding(0, 0, 0, 0);
                    return;
                }
            }
            if (d != null) {
                iv.setImageDrawable(d);
                iv.setBackground(Ui.roundRect(Color.TRANSPARENT, r));
                iv.setPadding(0, 0, 0, 0);
                return;
            }
            Db.Cat cat = catOf(e.catUuid);
            int c = cat != null ? (int) cat.color : Ui.colorOf(e.title + e.url);
            String ic = cat != null && cat.icon != null && !cat.icon.isEmpty() ? cat.icon : "more";
            iv.setBackground(Ui.roundRect(Ui.withAlpha(c, Ui.isDark(a) ? 55 : 40), r));
            int pad = Ui.dp(a, 10);
            iv.setPadding(pad, pad, pad, pad);
            iv.setImageDrawable(Ico.get(a, ic, c, 24));
            if (!e.url.isEmpty()) {
                Icons.favicon(a, e.url, b -> {
                    if (b != null) {
                        iv.setImageBitmap(Icons.round(b, Ui.dp(a, 14)));
                        iv.setPadding(0, 0, 0, 0);
                    }
                });
            }
        }

        private Db.Cat catOf(String uuid) {
            for (Db.Cat c : cats) if (c.uuid.equals(uuid)) return c;
            return null;
        }
    }

    private String nameOf(Db.Entry e) {
        String s = e.title == null ? "" : e.title.trim();
        if (s.isEmpty()) s = e.user == null ? "" : e.user.trim();
        return s.isEmpty() ? "#" : s;
    }

    /** 详情头部图标：本机 App 图标 / 网址 favicon / 分类图标 */
    private final class FrameBadge extends android.widget.FrameLayout {
        FrameBadge(Context c, Db.Entry e) {
            super(c);
            int s = Ui.dp(c, 52);
            setLayoutParams(new LinearLayout.LayoutParams(s, s));
            ImageView iv = new ImageView(c);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(s, s));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable d = Icons.appIcon(c, e.pkg);
            if (d != null) {
                iv.setImageDrawable(d);
            } else {
                Db.Cat cat = null;
                for (Db.Cat x : cats) if (x.uuid.equals(e.catUuid)) cat = x;
                int col = cat != null ? (int) cat.color : Ui.colorOf(e.title + e.url);
                String icn = cat != null && cat.icon != null ? cat.icon : "more";
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(Ui.withAlpha(col, Ui.isDark(c) ? 60 : 42));
                g.setCornerRadius(Ui.dp(c, 16));
                iv.setBackground(g);
                int pad = Ui.dp(c, 12);
                iv.setPadding(pad, pad, pad, pad);
                iv.setImageDrawable(Ico.get(c, icn, col, 28));
                if (!e.url.isEmpty()) {
                    Icons.favicon(c, e.url, b -> {
                        if (b != null) {
                            iv.setImageBitmap(Icons.round(b, Ui.dp(c, 16)));
                            iv.setPadding(0, 0, 0, 0);
                        }
                    });
                }
            }
            addView(iv);
        }
    }
}
