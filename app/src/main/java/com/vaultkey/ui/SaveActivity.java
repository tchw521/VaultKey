package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.service.VaultAutofillService;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Ui;
import java.util.List;

/** 登录成功后询问是否把账号保存到密盒 */
public final class SaveActivity extends BaseActivity {
    private String catUuid = "";
    private TextView catBtn;
    private List<Db.Cat> cats;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        VaultAutofillService.AutofillSave.Data d = VaultAutofillService.AutofillSave.pending;
        if (d == null || Session.key() == null) { finish(); return; }
        cats = Db.get(this).cats(0);
        catUuid = Db.get(this).defaultCatUuid();
        build(d);
    }

    private void build(VaultAutofillService.AutofillSave.Data d) {
        body.removeAllViews();
        body.setPadding(0, Ui.dp(this, 24), 0, 0);

        TextView t = title("保存到密盒？", 21);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        body.addView(t);

        TextView sub = label("检测到新的登录账号，是否保存");
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, Ui.dp(this, 14));
        body.addView(sub);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.glass(this, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);
        body.addView(card);

        String from = d.domain != null && !d.domain.isEmpty() ? d.domain
                : (d.pkg == null || d.pkg.isEmpty() ? "" : Icons.appName(this, d.pkg));
        if (from == null) from = "";
        String name = from.isEmpty() ? (d.user == null ? "新账号" : d.user) : from;
        card.addView(infoRow("名称", name));
        card.addView(infoRow("账号", d.user == null ? "" : d.user));
        card.addView(infoRow("密码", d.pass == null ? "" : d.pass.replaceAll(".", "•")));
        if (d.pkg != null && !d.pkg.isEmpty()) card.addView(infoRow("应用", d.pkg));

        catBtn = chip(catName(catUuid), false);
        catBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        catBtn.setOnClickListener(v -> pick());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView lb = label("分类");
        lb.setPadding(Ui.dp(this, 2), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        wrap.addView(lb);
        wrap.addView(catBtn);
        body.addView(wrap);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(this, 20);
        acts.setLayoutParams(al);
        android.widget.Button save = button("保存", new int[]{Ui.accent(this), Ui.accent2(this)});
        save.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        save.setOnClickListener(v -> {
            VaultAutofillService.AutofillSave.Data dd = VaultAutofillService.AutofillSave.take();
            if (dd == null) { finish(); return; }
            Db.Entry e = Db.get(this).newEntry();
            e.title = name;
            e.user = dd.user == null ? "" : dd.user;
            e.pass = dd.pass == null ? "" : dd.pass;
            e.url = dd.domain == null ? "" : "https://" + dd.domain;
            e.pkg = dd.pkg == null ? "" : dd.pkg;
            e.catUuid = catUuid;
            Db.get(this).save(e);
            toast("已保存");
            finish();
        });
        acts.addView(save);
        android.widget.Button no = button("不保存", new int[]{getResources().getColor(R.color.bad), Ui.accent2(this)});
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(this, 12));
        no.setLayoutParams(nl);
        no.setOnClickListener(v -> { VaultAutofillService.AutofillSave.take(); finish(); });
        acts.addView(no);
        body.addView(acts);

        TextView never = new TextView(this);
        never.setText("不再询问保存");
        never.setTextSize(12);
        never.setTextColor(Ui.attr(this, R.attr.textColor2));
        never.setGravity(Gravity.CENTER);
        never.setPadding(0, Ui.dp(this, 16), 0, 0);
        never.setOnClickListener(v -> {
            Prefs.putB("offer_save", false);
            VaultAutofillService.AutofillSave.take();
            toast("已关闭自动保存提示");
            finish();
        });
        body.addView(never);
    }

    private View infoRow(String k, String v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextSize(12);
        a.setTextColor(Ui.attr(this, R.attr.textColor2));
        a.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(a);
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextSize(14);
        b.setTextColor(Ui.attr(this, R.attr.textColor));
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        l.addView(b);
        return l;
    }

    private String catName(String uuid) {
        for (Db.Cat c : cats) if (c.uuid.equals(uuid)) return c.name;
        return "未分类";
    }

    private void pick() {
        String[] names = new String[cats.size()];
        int cur = 0;
        for (int i = 0; i < cats.size(); i++) {
            names[i] = cats.get(i).name;
            if (cats.get(i).uuid.equals(catUuid)) cur = i;
        }
        new android.app.AlertDialog.Builder(this).setTitle("选择分类")
                .setSingleChoiceItems(names, cur, (d, w) -> { catUuid = cats.get(w).uuid; catBtn.setText(cats.get(w).name); d.dismiss(); })
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Session.key() == null) {
            startActivity(new Intent(this, UnlockActivity.class).putExtra("auth", true));
            finish();
        }
    }
}
