package com.vaultkey.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Dlg;
import com.vaultkey.util.Ico;
import com.vaultkey.util.SimpleAdapter;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * 标签统一管理：改名、删除、看每个标签用了多少条。
 *
 * 之前标签只能逐条编辑，删掉一个错字标签得把用过的条目一个个翻出来改。
 */
public final class TagManagerActivity extends BaseActivity {

    private final List<String> tags = new ArrayList<>();
    /** 进入页面时算一次，别在 view() 里逐行查库 */
    private final Map<String, Integer> counts = new HashMap<>();
    private SimpleAdapter<String> ad;
    private TextView empty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.attr(this, R.attr.bgColor));
        int pd = Ui.dp(this, 16);
        root.setPadding(pd, pd, pd, pd);

        root.addView(backRow("标签管理"));

        empty = new TextView(this);
        empty.setText("还没有任何标签");
        empty.setTextSize(13);
        empty.setTextColor(Ui.attr(this, R.attr.textColor2));
        empty.setPadding(0, Ui.dp(this, 20), 0, 0);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(Ui.dp(this, 8));
        ad = makeAd();
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, v, pos, id) -> act(pos));
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        reload();
    }

    private SimpleAdapter<String> makeAd() {
        return new SimpleAdapter<String>() {
            @Override public View view(int pos, String tag, View cv, ViewGroup parent) {
                LinearLayout l = new LinearLayout(TagManagerActivity.this);
                l.setOrientation(LinearLayout.HORIZONTAL);
                l.setGravity(Gravity.CENTER_VERTICAL);
                l.setBackground(Ui.glass(TagManagerActivity.this, 16,
                        R.attr.cardColor, R.attr.strokeColor));
                int p = Ui.dp(TagManagerActivity.this, 13);
                l.setPadding(p, p, p, p);

                /* 标签色块，用标签名算一个稳定颜色 */
                int col = Ui.colorOf(tag);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.dp(TagManagerActivity.this, 8));
                g.setColor(Ui.withAlpha(col, 45));
                TextView chip = new TextView(TagManagerActivity.this);
                chip.setText(String.valueOf(tag.isEmpty() ? '?' : tag.charAt(0)));
                chip.setTextSize(13);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setTextColor(col);
                chip.setGravity(Gravity.CENTER);
                chip.setBackground(g);
                int s = Ui.dp(TagManagerActivity.this, 36);
                chip.setLayoutParams(new LinearLayout.LayoutParams(s, s));
                l.addView(chip);

                LinearLayout tx = new LinearLayout(TagManagerActivity.this);
                tx.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ml.setMarginStart(Ui.dp(TagManagerActivity.this, 12));
                tx.setLayoutParams(ml);

                TextView n = new TextView(TagManagerActivity.this);
                n.setText(tag);
                n.setTextSize(15);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                n.setTextColor(Ui.attr(TagManagerActivity.this, R.attr.textColor));
                n.setSingleLine(true);
                n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.addView(n);

                TextView c = new TextView(TagManagerActivity.this);
                Integer cn = counts.get(tag);
                c.setText((cn == null ? 0 : cn) + " 个账号");
                c.setTextSize(11.5f);
                c.setTextColor(Ui.attr(TagManagerActivity.this, R.attr.textColor2));
                tx.addView(c);
                l.addView(tx);

                android.widget.ImageView more = new android.widget.ImageView(
                        TagManagerActivity.this);
                more.setImageDrawable(Ico.get(TagManagerActivity.this, "more",
                        Ui.attr(TagManagerActivity.this, R.attr.textColor2), 20));
                l.addView(more);
                return l;
            }
        };
    }

    private void act(int pos) {
        String tag = ad.at(pos);
        if (tag == null) return;
        Integer cn = counts.get(tag);
        int cnt = cn == null ? 0 : cn;
        Dlg.pick(this, tag + "（" + cnt + " 个账号）",
                new String[]{"重命名", "删除这个标签", "查看这些账号"},
                w -> {
                    if (w == 0) rename(tag);
                    else if (w == 1) remove(tag);
                    else viewEntries(tag);
                });
    }

    private void rename(final String tag) {
        EditText et = new EditText(this);
        et.setText(tag);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        et.setSelection(et.getText().length());

        new android.app.AlertDialog.Builder(this)
                .setTitle("重命名标签")
                .setMessage("使用这个标签的账号会一起更新")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String to = et.getText().toString().trim();
                    if (to.isEmpty()) { toast("名称不能为空"); return; }
                    if (to.equals(tag)) return;
                    int n = Db.get(this).renameTag(tag, to);
                    toast("已重命名，" + n + " 个账号已更新");
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void remove(String tag) {
        Integer cn = counts.get(tag);
        int cnt = cn == null ? 0 : cn;
        Dlg.danger(this, "删除标签",
                "从 " + cnt + " 个账号上移除「" + tag + "」。\n账号本身不会删除。",
                "移除", () -> {
            int n = Db.get(this).removeTag(tag);
            toast("已从 " + n + " 个账号移除");
            reload();
        });
    }

    /** 跳回主页并按标签筛选 */
    private void viewEntries(String tag) {
        setResult(RESULT_OK, new android.content.Intent().putExtra("tag", tag));
        finish();
    }

    private void reload() {
        tags.clear();
        counts.clear();
        counts.putAll(Db.get(this).tagCounts());
        List<String> all = Db.get(this).allTags();
        if (all != null) tags.addAll(all);
        ad.setData(tags);
        empty.setVisibility(tags.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View backRow(String t) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 0, 0, Ui.dp(this, 12));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        r.addView(iv);
        TextView x = title(t, 22);
        x.setPadding(Ui.dp(this, 10), 0, 0, 0);
        r.addView(x);
        Ui.press(r);
        r.setOnClickListener(v -> finish());
        return r;
    }
}
