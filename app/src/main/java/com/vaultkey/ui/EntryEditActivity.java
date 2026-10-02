package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Session;
import com.vaultkey.data.Prefs;
import com.vaultkey.util.IconFill;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Lookup;
import com.vaultkey.util.PasswordGen;
import com.vaultkey.util.Totp;
import com.vaultkey.util.Ui;
import java.util.List;

public final class EntryEditActivity extends BaseActivity {
    private Db.Entry e;
    private EditText title, user, pass, url, notes, totp;
    private TextView catBtn, pkgBtn, strength;
    private boolean fav;
    private List<Db.Cat> cats;
    private String catUuid = "";
    private String pkg = "";
    private java.util.List<String> tags = new java.util.ArrayList<>();
    private LinearLayout tagStrip;
    private final java.util.List<Db.F> customs = new java.util.ArrayList<>();
    private LinearLayout customBox;
    private static final int PICK = 11;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Session.key() == null) { finish(); return; }
        /*
         * 先确定 e，再按 e.kind 取分类。
         * 之前 cats = ...cats(e.kind) 写在这一行之前，而 e 还没赋值（null）——
         * 读 e.kind 直接 NullPointerException，打开编辑页就崩。
         */
        String u = getIntent().getStringExtra("uuid");
        int k0 = getIntent().getIntExtra("kind", -1);
        e = u != null ? Db.get(this).getByUuid(u) : null;
        if (e == null) {
            /* 新建时就要知道是账号还是链接 —— newEntry(0) 会把默认分类
               取成密码箱的，链接挂上去后在链接库里就统计不到。 */
            e = Db.get(this).newEntry(k0 == 1 ? 1 : 0);
            String pre = getIntent().getStringExtra("title");
            String pu = getIntent().getStringExtra("user");
            String pp = getIntent().getStringExtra("pass");
            String purl = getIntent().getStringExtra("url");
            if (pre != null) e.title = pre;
            if (pu != null) e.user = pu;
            if (pp != null) e.pass = pp;
            if (purl != null) e.url = purl;
        }
        /* 分类按条目类型取：账号用密码箱的分类，链接用链接库的 */
        cats = Db.get(this).cats(e.kind);
        catUuid = e.catUuid;
        /* kind：0 = 账号密码，1 = 网址收藏。
           新建时从 Intent 读；编辑已有条目时以条目自身为准。 */
        if (k0 == 1) e.kind = 1;
        String preCat = getIntent().getStringExtra("cat");
        if (preCat != null && !preCat.isEmpty()) catUuid = preCat;
        pkg = e.pkg == null ? "" : e.pkg;
        tags = Db.tagList(e.tags);
        customs.addAll(Db.customs(e.custom));
        fav = e.fav == 1;
        build();
    }

    private void build() {
        body.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 110));
        sv.addView(box);
        body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        boolean editing = getIntent().getStringExtra("uuid") != null;
        String ht = e.kind == 1 ? (editing ? "编辑链接" : "新增链接")
                : (editing ? "编辑账号" : "新增账号");
        TextView head = title(ht, 22);
        head.setPadding(0, 0, 0, Ui.dp(this, 14));
        box.addView(head);

        title = field(e.kind == 1 ? "名称（如 GitHub 开源地址）" : "名称（如 微信 / GitHub）", e.title);
        box.addView(title);

        catBtn = chip(catName(catUuid), false);
        catBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        catBtn.setOnClickListener(v -> pickCat());
        box.addView(labeled("分类", catBtn));

        user = field("账号 / 邮箱 / 手机号", e.user);
        if (e.kind != 1) box.addView(labeled("账号", user));

        LinearLayout passBox = new LinearLayout(this);
        passBox.setOrientation(LinearLayout.HORIZONTAL);
        pass = field("密码", e.pass);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { updStrength(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        passBox.addView(pass, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gen = chip("生成", false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.setMarginStart(Ui.dp(this, 8));
        gen.setOnClickListener(v -> genDialog());
        Ui.press(gen);
        passBox.addView(gen, gl);
        if (e.kind != 1) box.addView(labeled("密码", passBox));

        strength = new TextView(this);
        strength.setTextSize(12);
        strength.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        if (e.kind != 1) box.addView(strength);
        updStrength();

        LinearLayout urlBox = new LinearLayout(this);
        urlBox.setOrientation(LinearLayout.HORIZONTAL);
        url = field(e.kind == 1 ? "链接（https://...）" : "网址（用于自动填充与在线图标）", e.url);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.addView(url, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView find = chip("搜索", false);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.setMarginStart(Ui.dp(this, 8));
        find.setLayoutParams(fl);
        find.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        Ui.press(find);
        find.setOnClickListener(v -> {
            String q = title.getText().toString().trim();
            new SitePicker(this, hit -> {
                url.setText(hit.url);
                // 顺带把搜到的图标写进缓存，列表与详情页会立刻用上
                Icons.cacheHost(this, hit.host, hit.icon);
                // 若本机装了这个 App，顺手关联上
                String guess = guessPkg(hit.host);
                if (guess != null) { pkg = guess; pkgBtn.setText(pkgLabel(pkg)); }
                toast("已填入 " + hit.host);
            }).show(q.isEmpty() ? null : q);
        });
        urlBox.addView(find);

        /* 「打开」按钮：填完链接立刻用浏览器打开验证。
           收藏的链接多是粘贴来的，少个字符或多截一段，
           等到真要用时才发现打不开 —— 当场验证一下最省事。 */
        final TextView open = chip("打开", true);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.setMarginStart(Ui.dp(this, 8));
        open.setLayoutParams(ol);
        open.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        Ui.press(open);
        open.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (u.isEmpty()) { toast("还没填链接"); return; }
            /* 与列表点击打开用同一套补全规则：没写 scheme 的补 https:// */
            boolean has = u.length() > 7 && (u.regionMatches(true, 0, "http://", 0, 7)
                    || (u.length() > 8 && u.regionMatches(true, 0, "https://", 0, 8)));
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(has ? u : "https://" + u)));
            } catch (Exception ex) { toast("打不开这个链接"); }
        });
        urlBox.addView(open);

        box.addView(labeled(e.kind == 1 ? "链接" : "网址", urlBox));

        LinearLayout pkgBox = new LinearLayout(this);
        pkgBox.setOrientation(LinearLayout.HORIZONTAL);
        pkgBtn = chip(pkg.isEmpty() ? "选择本机 App（自动取图标）" : pkgLabel(pkg), false);
        pkgBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pkgBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pkgBtn.setOnClickListener(v -> startActivityForResult(new Intent(this, AppPickerActivity.class), PICK));
        pkgBox.addView(pkgBtn);
        TextView net = chip("在线", false);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.setMarginStart(Ui.dp(this, 8));
        net.setLayoutParams(nl);
        net.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        Ui.press(net);
        net.setOnClickListener(v -> fetchIconOnline());
        pkgBox.addView(net);
        if (e.kind != 1) box.addView(labeled("关联应用", pkgBox));

        tagStrip = new LinearLayout(this);
        tagStrip.setOrientation(LinearLayout.HORIZONTAL);
        tagStrip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        tagRow.addView(tagStrip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tagEdit = chip(tags.isEmpty() ? "添加" : "编辑", false);
        LinearLayout.LayoutParams tel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tel.setMarginStart(Ui.dp(this, 8));
        tagEdit.setLayoutParams(tel);
        tagEdit.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        Ui.press(tagEdit);
        tagEdit.setOnClickListener(v -> TagPicker.show(this, tags, picked -> {
            tags.clear();
            tags.addAll(picked);
            renderTags();
        }));
        tagRow.addView(tagEdit);
        box.addView(labeled("标签", tagRow));
        renderTags();

        totp = field("两步验证密钥 / otpauth 链接（可选）", e.totp);
        if (e.kind != 1) box.addView(labeled("动态口令", totp));

        notes = field("备注（可选）", e.notes);
        notes.setSingleLine(false);
        notes.setMaxLines(5);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setMinHeight(Ui.dp(this, 90));
        notes.setGravity(Gravity.TOP);
        /* 自定义字段 */
        customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionRow("自定义字段", "＋ 添加", v -> addCustom(null, null)));
        box.addView(customBox);
        renderCustoms();

        box.addView(labeled("备注", notes));

        TextView favBtn = chip(fav ? "★ 已收藏" : "☆ 加入收藏", fav);
        favBtn.setOnClickListener(v -> {
            fav = !fav;
            favBtn.setText(fav ? "★ 已收藏" : "☆ 加入收藏");
            favBtn.setTextColor(fav ? Color.WHITE : Ui.attr(this, R.attr.textColor));
            favBtn.setBackground(fav ? Ui.gradient(new int[]{Ui.accent2(this), Ui.accent(this)}, 999f, 0)
                    : Ui.glass(this, 999, R.attr.cardColor, R.attr.strokeColor));
        });
        box.addView(labeled("收藏", favBtn));

        android.widget.Button save = button("保存", new int[]{Ui.accent(this), Ui.accent2(this)});
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sb.topMargin = Ui.dp(this, 18);
        save.setOnClickListener(v -> save_());
        box.addView(save, sb);

        if (getIntent().getStringExtra("uuid") != null) {
            android.widget.Button del = button("删除该账号", new int[]{getResources().getColor(R.color.bad), Ui.accent2(this)});
            del.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            LinearLayout.LayoutParams db = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            db.topMargin = Ui.dp(this, 10);
            del.setOnClickListener(v -> { Db.get(this).softDelete(e.id); toast("已移入回收站"); finish(); });
            box.addView(del, db);
        }
    }

    private View labeled(String text, View v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(text);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        l.addView(t);
        l.addView(v);
        return l;
    }

    private View sectionRow(String title, String act, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(accent());
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView a = chip(act, true);
        a.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        a.setTextSize(11.5f);
        Ui.press(a);
        a.setOnClickListener(l);
        r.addView(a);
        return r;
    }

    /** 加一个自定义字段；传 null 表示新增空白行 */
    private void addCustom(String k, String val) {
        customs.add(new Db.F(k == null ? "" : k, val == null ? "" : val));
        renderCustoms();
    }

    private void renderCustoms() {
        customBox.removeAllViews();
        if (customs.isEmpty()) {
            TextView t = label("可添加安全问题答案、API Token、恢复码等任意字段");
            t.setTextSize(11);
            t.setPadding(Ui.dp(this, 2), 0, 0, 0);
            customBox.addView(t);
            return;
        }
        for (int i = 0; i < customs.size(); i++) {
            final int idx = i;
            final Db.F f = customs.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.glass(this, 16, R.attr.cardColor, R.attr.strokeColor));
            int p = Ui.dp(this, 10);
            row.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 6);
            row.setLayoutParams(lp);

            LinearLayout kv = new LinearLayout(this);
            kv.setOrientation(LinearLayout.VERTICAL);
            kv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final EditText ke = field("字段名", f.k);
            ke.setTextSize(13);
            ke.setSingleLine(true);
            kv.addView(ke);

            final EditText ve = field("值", f.v);
            ve.setTextSize(14);
            ve.setSingleLine(true);
            kv.addView(ve);
            row.addView(kv);

            TextView del = chip("✕", false);
            del.setTextSize(13);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                    Ui.dp(this, 34), Ui.dp(this, 34));
            dl.setMarginStart(Ui.dp(this, 6));
            del.setLayoutParams(dl);
            del.setGravity(Gravity.CENTER);
            del.setTextColor(getResources().getColor(R.color.bad));
            Ui.press(del);
            del.setOnClickListener(v -> {
                customs.remove(idx);
                renderCustoms();
            });
            row.addView(del);

            /* 编辑即回写，避免 renderCustoms 重建时丢失输入 */
            final int fi = idx;
            ke.addTextChangedListener(watch(s2 -> { if (fi < customs.size()) customs.get(fi).k = s2; }));
            ve.addTextChangedListener(watch(s2 -> { if (fi < customs.size()) customs.get(fi).v = s2; }));

            customBox.addView(row);
        }
    }

    private android.text.TextWatcher watch(final OnStr cb) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { cb.on(s.toString()); }
            public void afterTextChanged(android.text.Editable s) { }
        };
    }

    interface OnStr { void on(String s); }

    private void renderTags() {
        tagStrip.removeAllViews();
        if (tags.isEmpty()) {
            TextView t = label("未设置（可用 主号 / 小号 区分同应用的多个账号）");
            t.setTextSize(11);
            tagStrip.addView(t);
            return;
        }
        LinearLayout strip = TagPicker.tagStrip(this, tags);
        tagStrip.addView(strip);
    }

    /** 在线获取图标：先按包名从应用商店取，取不到再按名称搜站点图标 */
    private void fetchIconOnline() {
        final String p = pkg;
        final String n = title.getText().toString().trim();
        final String u = url.getText().toString().trim();
        if (p.isEmpty() && n.isEmpty() && u.isEmpty()) {
            toast("请先填名称或网址，或选择本机 App");
            return;
        }
        final android.app.ProgressDialog pd = progress("正在在线获取图标…");
        pd.show();
        new Thread(() -> {
            android.graphics.Bitmap b = null;
            String key = null;
            if (!p.isEmpty()) {
                b = Lookup.playIcon(p);
                if (b != null) key = "pkg:" + p;
            }
            if (b == null && !u.isEmpty()) {
                String host = Icons.hostOf(u);
                if (host != null) { b = Lookup.icon(host); if (b != null) key = host; }
            }
            if (b == null && !n.isEmpty()) {
                List<Lookup.Hit> hits = Lookup.search(n);
                if (!hits.isEmpty()) {
                    android.graphics.Bitmap hb = Lookup.icon(hits.get(0).host);
                    if (hb != null) {
                        b = hb;
                        key = hits.get(0).host;
                        final String foundUrl = hits.get(0).url;
                        runOnUiThread(() -> {
                            if (u.isEmpty()) url.setText(foundUrl);
                            toast("已按名称搜到 " + hits.get(0).host);
                        });
                    }
                }
            }
            final android.graphics.Bitmap fb = b;
            final String fk = key;
            runOnUiThread(() -> {
                safeDismiss(pd);
                if (fb == null || fk == null) { toast("没取到图标，换用网址或手动选本机 App 试试"); return; }
                Icons.cacheKey(this, fk, fb);
                toast("图标已获取并缓存");
            });
        }).start();
    }

    /** 根据域名猜本机是否装了对应 App：域名主段出现在包名里就算命中 */
    private String guessPkg(String host) {
        if (host == null || host.isEmpty()) return null;
        String main = Icons.mainDomain(host);
        String key = main.split("\\.")[0];
        if (key.length() < 3) return null;
        for (Icons.AppInfo a : Icons.installed(this)) {
            if (a.pkg.toLowerCase().contains(key.toLowerCase())) return a.pkg;
            if (a.name != null && a.name.toLowerCase().contains(key.toLowerCase())) return a.pkg;
        }
        return null;
    }

    private String pkgLabel(String p) {
        String n = Icons.appName(this, p);
        return (n == null ? p : n);
    }

    private String catName(String uuid) {
        for (Db.Cat c : cats) if (c.uuid.equals(uuid)) return c.name;
        return "未分类";
    }

    private void updStrength() {
        int s = PasswordGen.strength(pass.getText().toString());
        strength.setText("强度：" + PasswordGen.label(s) + "  " + s + "/100");
        strength.setTextColor(s < 30 ? getResources().getColor(R.color.bad)
                : s < 60 ? getResources().getColor(R.color.warn) : getResources().getColor(R.color.ok));
    }

    private void pickCat() {
        String[] names = new String[cats.size()];
        int cur = 0;
        for (int i = 0; i < cats.size(); i++) {
            names[i] = cats.get(i).name;
            if (cats.get(i).uuid.equals(catUuid)) cur = i;
        }
        new android.app.AlertDialog.Builder(this).setTitle("选择分类")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    catUuid = cats.get(w).uuid;
                    catBtn.setText(cats.get(w).name);
                    d.dismiss();
                }).show();
    }

    private void genDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), 0);
        TextView view = new TextView(this);
        view.setTextSize(16);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextColor(Ui.attr(this, R.attr.textColor));
        view.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        l.addView(view);
        final int[] len = {16};
        final boolean[] o = {true, true, true, true};
        final TextView sizeV = label("长度 16");
        sizeV.setTextSize(13);
        l.addView(seek("长度", 8, 40, 16, sizeV, v -> { len[0] = v; view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3])); }));
        l.addView(sizeV);
        l.addView(sw("包含小写", true, v -> { o[0] = v; view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3])); }));
        l.addView(sw("包含大写", true, v -> { o[1] = v; view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3])); }));
        l.addView(sw("包含数字", true, v -> { o[2] = v; view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3])); }));
        l.addView(sw("包含符号", true, v -> { o[3] = v; view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3])); }));
        view.setText(PasswordGen.gen(len[0], o[0], o[1], o[2], o[3]));
        new android.app.AlertDialog.Builder(this).setTitle("密码生成器").setView(l)
                .setPositiveButton("使用", (d, w) -> pass.setText(view.getText().toString()))
                .setNegativeButton("关闭", null).show();
    }

    private View seek(String t, int min, int max, int val, TextView label, OnInt cb) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        label.setText(t + " " + val);
        l.addView(label);
        android.widget.SeekBar s = new android.widget.SeekBar(this);
        s.setMax(max - min);
        s.setProgress(val - min);
        s.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean f) { cb.on(min + p); }
            public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        l.addView(s);
        return l;
    }

    interface OnInt { void on(int v); }

    private View sw(String t, boolean init, OnBool cb) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Ui.attr(this, R.attr.textColor));
        tv.setTextSize(14);
        l.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch s = new android.widget.Switch(this);
        s.setChecked(init);
        s.setOnCheckedChangeListener((b, c) -> cb.on(c));
        l.addView(s);
        return l;
    }

    interface OnBool { void on(boolean v); }

    private void save_() {
        e.title = title.getText().toString().trim();
        e.user = user.getText().toString().trim();
        e.pass = pass.getText().toString();
        e.url = url.getText().toString().trim();
        e.notes = notes.getText().toString();
        /* 粘进来的是 otpauth:// 链接时自动取密钥，用户不用自己抠 secret= 后面的部分 */
        String rawTotp = totp.getText().toString().trim();
        if (Totp.isUri(rawTotp)) {
            String sec = Totp.secretFromUri(rawTotp);
            if (sec == null || sec.isEmpty()) {
                toast("这个 otpauth 链接里没有 secret 参数");
                return;
            }
            e.totp = sec;
            /* 标题还空着就顺手用服务商名填上 */
            if (e.title.isEmpty()) {
                String iss = Totp.issuerFromUri(rawTotp);
                if (iss != null && !iss.isEmpty()) e.title = iss;
            }
        } else {
            e.totp = Totp.normalize(rawTotp);
        }
        e.catUuid = catUuid;
        e.pkg = pkg;
        e.tags = Db.packTags(tags);
        e.custom = Db.packCustoms(customs);
        e.fav = fav ? 1 : 0;
        if (e.title.isEmpty()) { toast("请填写名称"); return; }
        /* 链接没有网址就没有意义（点不开），所以必填。
           账号则允许没网址 —— 很多账号本来就只在 App 里用。 */
        if (e.kind == 1 && e.url.isEmpty()) { toast("请填写链接地址"); return; }
        Db.get(this).save(e);
        toast("已保存");
        autoIcon(e);
        finish();
    }

    /** 保存后静默补全图标：本地优先，本地没有才联网，全程后台不打断用户 */
    private void autoIcon(final Db.Entry saved) {
        if (!Prefs.getB("auto_icon", true)) return;
        if (!IconFill.need(this, saved)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                IconFill.Result r = IconFill.one(EntryEditActivity.this, saved, false, null, null);
                if (r.ok && r.icon != null && saved.id > 0) {
                    String k = r.host != null && !r.host.isEmpty() ? r.host
                            : (saved.pkg != null && !saved.pkg.isEmpty() ? "pkg:" + saved.pkg : "");
                    if (!k.isEmpty()) Db.get(EntryEditActivity.this).setIconKey(saved.id, k);
                }
            }
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null) {
            pkg = data.getStringExtra("pkg");
            pkgBtn.setText(pkgLabel(pkg));
            if (title.getText().toString().isEmpty()) {
                String n = Icons.appName(this, pkg);
                if (n != null) title.setText(n);
            }
        }
    }
}
