package com.vaultkey.ui;

import android.graphics.Typeface;
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
import com.vaultkey.util.PasswordGen;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 工具箱：密码生成器、强度检测、安全体检、回收站 */
public final class ToolsView {
    /** 授权本地备份文件夹的请求码（由 Activity 转给 SyncView 处理） */
    public static final int REQ_BACKUP_DIR = 77;

    public interface Host {
        void showTrash();
        void exportCsv();
        void importCsv();
        void showBatch();
        void exportBitwarden();
        void importBitwarden();
    }

    private final BaseActivity a;
    private final Host host;
    private final Runnable onBack;
    private LinearLayout box;
    private TextView genOut;
    private final int[] len = {16};
    private final boolean[] opt = {true, true, true, true};
    private LinearLayout auditView;

    public ToolsView(BaseActivity a, Host host, Runnable onBack) {
        this.a = a; this.host = host; this.onBack = onBack;
    }

    public View view() {
        ScrollView sv = new ScrollView(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 110));
        sv.addView(box);
        render();
        return sv;
    }

    public void refresh() { if (box != null) render(); }

    private void render() {
        box.removeAllViews();
        box.addView(backRow("工具箱"));

        /* 密码生成器 */
        box.addView(a.section("密码生成器"));
        LinearLayout card = a.card();
        genOut = new TextView(a);
        genOut.setText(PasswordGen.gen(len[0], opt[0], opt[1], opt[2], opt[3]));
        genOut.setTextSize(18);
        genOut.setTypeface(Typeface.MONOSPACE);
        genOut.setTextColor(Ui.attr(a, R.attr.textColor));
        genOut.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 10));
        card.addView(genOut);

        final TextView lenLabel = a.label("长度 " + len[0]);
        lenLabel.setTextSize(13);
        card.addView(lenLabel);
        android.widget.SeekBar sb = new android.widget.SeekBar(a);
        sb.setMax(32);
        sb.setProgress(len[0] - 8);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean f) {
                len[0] = p + 8;
                lenLabel.setText("长度 " + len[0]);
                regen();
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) { }
            public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        card.addView(sb);
        card.addView(toggle("包含小写字母", 0));
        card.addView(toggle("包含大写字母", 1));
        card.addView(toggle("包含数字", 2));
        card.addView(toggle("包含符号", 3));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(a, 12);
        row.setLayoutParams(rl);
        android.widget.Button re = a.button("换一个", new int[]{a.accent(), a.accent2()});
        re.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        re.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        row.addView(re);
        android.widget.Button cp = a.button("复制", new int[]{a.accent3(), a.accent()});
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.dp(a, 10));
        cp.setLayoutParams(cl);
        cp.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        row.addView(cp);
        card.addView(row);
        re.setOnClickListener(v -> regen());
        cp.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("密码", genOut.getText().toString()));
                a.toast("已复制");
            }
        });

        final TextView passphrase = new TextView(a);
        passphrase.setText("或用易记口令：" + PasswordGen.passphrase(4));
        passphrase.setTextSize(12);
        passphrase.setTextColor(a.accent());
        passphrase.setPadding(0, Ui.dp(a, 10), 0, 0);
        passphrase.setOnClickListener(v -> passphrase.setText("或用易记口令：" + PasswordGen.passphrase(4)));
        card.addView(passphrase);
        box.addView(card);

        /* 强度检测 */
        box.addView(a.section("密码强度检测"));
        LinearLayout c2 = a.card();
        final EditText in = a.field("输入要检测的密码", null);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        c2.addView(in);
        final TextView res = new TextView(a);
        res.setTextSize(13);
        res.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), 0, 0);
        c2.addView(res);
        in.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int x, int y, int z) {
                int sc = PasswordGen.strength(s.toString());
                res.setText("强度：" + PasswordGen.label(sc) + "   " + sc + "/100");
                res.setTextColor(sc < 30 ? a.getResources().getColor(R.color.bad)
                        : sc < 60 ? a.getResources().getColor(R.color.warn) : a.getResources().getColor(R.color.ok));
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        box.addView(c2);

        /* 安全体检 */
        box.addView(a.section("安全体检"));
        auditView = a.card();
        box.addView(auditView);
        renderAudit();
        android.widget.Button au = a.button("重新体检", new int[]{a.accent(), a.accent2()});
        au.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(a, 8);
        au.setLayoutParams(al);
        au.setOnClickListener(v -> renderAudit());
        box.addView(au);

        /* 完整报告（含泄露库比对）单独开一页，这里只放入口 */
        android.widget.Button full = a.button("查看完整报告", new int[]{a.accent(), a.accent2()});
        full.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(a, 8);
        full.setLayoutParams(fl);
        full.setOnClickListener(v ->
                a.startActivity(new android.content.Intent(a, HealthActivity.class)));
        box.addView(full);

    }

    private View backRow(String t) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 0, 0, Ui.dp(a, 6));
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageDrawable(com.vaultkey.util.Ico.get(a, "back", Ui.attr(a, R.attr.textColor), 22));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        r.addView(iv);
        TextView x = a.title(t, 22);
        x.setPadding(Ui.dp(a, 10), 0, 0, 0);
        r.addView(x);
        Ui.press(r);
        r.setOnClickListener(v -> { if (onBack != null) onBack.run(); });
        return r;
    }

    private void regen() {
        if (genOut != null) genOut.setText(PasswordGen.gen(len[0], opt[0], opt[1], opt[2], opt[3]));
    }

    private void renderAudit() {
        if (auditView == null) return;
        auditView.removeAllViews();
        List<Db.Entry> all = Db.get(a).list(null, false, false, null, 0);
        int weak = 0, dup = 0, old = 0;
        HashSet<String> seen = new HashSet<>();
        List<String> dupNames = new ArrayList<>();
        long year = 365L * 24 * 3600 * 1000;
        for (Db.Entry e : all) {
            if (PasswordGen.strength(e.pass) < 45) weak++;
            if (!e.pass.isEmpty() && !seen.add(e.pass)) { dup++; dupNames.add(e.title); }
            if (System.currentTimeMillis() - e.mtime > year) old++;
        }
        auditView.addView(statRow("账号总数", all.size() + ""));
        auditView.addView(statRow("弱密码", weak + "", weak > 0));
        auditView.addView(statRow("重复密码", dup + "", dup > 0));
        auditView.addView(statRow("一年未更新", old + "", old > 0));
        if (!dupNames.isEmpty()) {
            TextView d = a.label("重复项：" + android.text.TextUtils.join("、", dupNames.subList(0, Math.min(5, dupNames.size()))));
            d.setTextSize(11);
            d.setPadding(0, Ui.dp(a, 6), 0, 0);
            auditView.addView(d);
        }
    }

    private View statRow(String k, String v) { return statRow(k, v, false); }

    private View statRow(String k, String v, boolean warn) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        TextView a1 = new TextView(a);
        a1.setText(k);
        a1.setTextSize(14);
        a1.setTextColor(Ui.attr(a, R.attr.textColor));
        l.addView(a1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b1 = new TextView(a);
        b1.setText(v);
        b1.setTextSize(14);
        b1.setTypeface(Typeface.DEFAULT_BOLD);
        b1.setTextColor(warn ? a.getResources().getColor(R.color.bad) : a.getResources().getColor(R.color.ok));
        l.addView(b1);
        return l;
    }

    private View toggle(String text, final int idx) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.attr(a, R.attr.textColor));
        l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(opt[idx]);
        sw.setOnCheckedChangeListener((b, c) -> { opt[idx] = c; regen(); });
        l.addView(sw);
        return l;
    }
}
