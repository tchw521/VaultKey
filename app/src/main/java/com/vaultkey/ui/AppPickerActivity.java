package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 选择本机应用（本地获取系统图标）。若被系统包可见性限制挡住，提供手动填包名的兜底 */
public final class AppPickerActivity extends BaseActivity {
    private final List<Icons.AppInfo> all = new ArrayList<>();
    private final List<Icons.AppInfo> shown = new ArrayList<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Ad ad;
    private TextView hint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        body.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView t = title("选择本机应用", 20);
        t.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(t);

        EditText s = field("搜索应用名或包名", null);
        s.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence c, int a, int b2, int c2) { filter(c.toString()); }
        });
        body.addView(s);

        hint = new TextView(this);
        hint.setTextSize(11.5f);
        hint.setTextColor(Ui.attr(this, R.attr.textColor2));
        hint.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, 0);
        hint.setVisibility(View.GONE);
        body.addView(hint);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(Ui.dp(this, 6));
        lv.setVerticalScrollBarEnabled(false);
        ad = new Ad();
        lv.setAdapter(ad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 6);
        body.addView(lv, lp);

        TextView manual = new TextView(this);
        manual.setText("手动填写包名");
        manual.setTextSize(13);
        manual.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        manual.setTextColor(accent());
        manual.setGravity(Gravity.CENTER);
        manual.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        Ui.press(manual);
        manual.setOnClickListener(v -> manualDialog());
        body.addView(manual);

        load();
    }

    private void load() {
        hint.setVisibility(View.VISIBLE);
        hint.setText("正在读取本机应用…");
        pool.execute(() -> {
            List<Icons.AppInfo> l = Icons.installed(this);
            runOnUiThread(() -> {
                all.clear(); all.addAll(l); shown.clear(); shown.addAll(l);
                ad.notifyDataSetChanged();
                if (l.isEmpty()) {
                    hint.setText("没读到应用列表。安卓 11 起系统限制了应用可见性，"
                            + "若本页为空可点下方「手动填写包名」，或用「网址」替代关联。");
                } else {
                    hint.setText("共 " + l.size() + " 个应用，点击即可关联并取用其图标");
                }
            });
        });
    }

    private void manualDialog() {
        final EditText e = new EditText(this);
        e.setHint("例如 com.tencent.mm");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextColor(Ui.attr(this, R.attr.textColor));
        e.setHintTextColor(Ui.attr(this, R.attr.textColor2));
        e.setBackground(Ui.glass(this, 14, R.attr.cardColor, R.attr.strokeColor));
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), 0);
        l.addView(e);
        new android.app.AlertDialog.Builder(this).setTitle("填写包名").setView(l)
                .setPositiveButton("确定", (d, w) -> {
                    String p = e.getText().toString().trim();
                    if (p.isEmpty()) return;
                    String n = Icons.appName(this, p);
                    if (n == null) {
                        toast("本机没有这个包名的应用（仍可保存，图标稍后在线获取）");
                        n = p;
                    }
                    setResult(RESULT_OK, new Intent().putExtra("pkg", p).putExtra("name", n));
                    finish();
                })
                .setNegativeButton("取消", null).show();
    }

    private void filter(String q) {
        shown.clear();
        String s = q == null ? "" : q.toLowerCase();
        for (Icons.AppInfo a : all) {
            if (s.isEmpty()
                    || (a.name != null && a.name.toLowerCase().contains(s))
                    || a.pkg.toLowerCase().contains(s)) shown.add(a);
        }
        ad.notifyDataSetChanged();
    }

    final class Ad extends android.widget.BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View cv, ViewGroup parent) {
            Icons.AppInfo a = shown.get(p);
            LinearLayout l = new LinearLayout(AppPickerActivity.this);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackground(Ui.glass(AppPickerActivity.this, 18, R.attr.cardColor, R.attr.strokeColor));
            int pd = Ui.dp(AppPickerActivity.this, 12);
            l.setPadding(pd, pd, pd, pd);

            android.widget.FrameLayout badge = new android.widget.FrameLayout(AppPickerActivity.this);
            int bs = Ui.dp(AppPickerActivity.this, 40);
            badge.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            ImageView iv = new ImageView(AppPickerActivity.this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(bs, bs));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (a.icon != null) {
                iv.setImageDrawable(a.icon);
            } else {
                int c = Ui.colorOf(a.pkg);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(Ui.withAlpha(c, 45));
                iv.setBackground(g);
                iv.setPadding(Ui.dp(AppPickerActivity.this, 9), Ui.dp(AppPickerActivity.this, 9),
                        Ui.dp(AppPickerActivity.this, 9), Ui.dp(AppPickerActivity.this, 9));
                iv.setImageDrawable(Ico.get(AppPickerActivity.this, "app", c, 22));
            }
            badge.addView(iv);
            l.addView(badge);

            LinearLayout tx = new LinearLayout(AppPickerActivity.this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.setMarginStart(Ui.dp(AppPickerActivity.this, 12));
            tx.setLayoutParams(ml);
            TextView n = new TextView(AppPickerActivity.this);
            n.setText(a.name);
            n.setTextSize(15);
            n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            n.setTextColor(Ui.attr(AppPickerActivity.this, R.attr.textColor));
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(n);
            TextView pk = new TextView(AppPickerActivity.this);
            pk.setText(a.pkg);
            pk.setTextSize(11);
            pk.setTextColor(Ui.attr(AppPickerActivity.this, R.attr.textColor2));
            pk.setSingleLine(true);
            pk.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tx.addView(pk);
            l.addView(tx);

            l.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra("pkg", a.pkg).putExtra("name", a.name));
                finish();
            });
            return l;
        }
    }

    static abstract class SimpleTextWatcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        public void onTextChanged(CharSequence s, int a, int b, int c) { }
        public void afterTextChanged(android.text.Editable s) { }
    }
}
