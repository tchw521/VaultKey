package com.vaultkey.ui;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
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
import com.vaultkey.util.Lookup;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在线搜索网址与图标：输入名称 → 搜索候选站点 → 选中后回填网址并缓存图标 */
public final class SitePicker {
    public interface Cb {
        void onPick(Lookup.Hit hit);
    }

    private final BaseActivity a;
    private final Cb cb;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<Lookup.Hit> hits = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private Ad ad;
    private LinearLayout root;
    private TextView status;

    public SitePicker(BaseActivity a, Cb cb) {
        this.a = a;
        this.cb = cb;
    }

    public void show(String preset) {
        Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.glass(a, 26, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 18);
        root.setPadding(p, p, p, p);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(a);
        back.setImageDrawable(Ico.get(a, "back", Ui.attr(a, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        back.setOnClickListener(v -> d.dismiss());
        head.addView(back);
        TextView t = a.title("搜索网址与图标", 20);
        t.setPadding(Ui.dp(a, 10), 0, 0, 0);
        head.addView(t);
        root.addView(head);

        LinearLayout searchRow = new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(a, 14);
        searchRow.setLayoutParams(sl);
        final EditText q = new EditText(a);
        q.setHint("输入应用或网站名，如 百度网盘");
        if (preset != null) q.setText(preset);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        q.setTextColor(Ui.attr(a, R.attr.textColor));
        q.setHintTextColor(Ui.attr(a, R.attr.textColor2));
        q.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
        q.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
        q.setTextSize(14);
        searchRow.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = a.chip("搜索", true);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.setMarginStart(Ui.dp(a, 8));
        go.setLayoutParams(gl);
        go.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
        Ui.press(go);
        go.setOnClickListener(v -> run(d, q.getText().toString()));
        searchRow.addView(go);
        root.addView(searchRow);
        q.setOnEditorActionListener((v, id, ev) -> { run(d, q.getText().toString()); return true; });

        status = new TextView(a);
        status.setTextSize(12);
        status.setTextColor(Ui.attr(a, R.attr.textColor2));
        status.setPadding(Ui.dp(a, 2), Ui.dp(a, 10), 0, Ui.dp(a, 4));
        root.addView(status);

        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setDividerHeight(Ui.dp(a, 6));
        ad = new Ad(d);
        lv.setAdapter(ad);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.FrameLayout fl = new android.widget.FrameLayout(a);
        fl.setBackgroundColor(Ui.withAlpha(Color.BLACK, 140));
        android.widget.FrameLayout.LayoutParams fl2 = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        fl2.setMargins(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        fl.addView(root, fl2);
        fl.setOnClickListener(v -> d.dismiss());
        d.setContentView(fl);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        d.show();
        if (preset != null && !preset.isEmpty()) run(d, preset);
    }

    private void run(Dialog d, String q) {
        String s = q == null ? "" : q.trim();
        if (s.isEmpty()) { a.toast("请输入名称"); return; }
        hits.clear();
        icons.clear();
        ad.notifyDataSetChanged();
        status.setText("正在搜索「" + s + "」…");
        pool.submit(() -> {
            List<Lookup.Hit> r = Lookup.search(s);
            a.runOnUiThread(() -> {
                if (!d.isShowing()) return;
                hits.clear();
                hits.addAll(r);
                ad.notifyDataSetChanged();
                if (r.isEmpty()) {
                    status.setText("没有搜到结果，检查网络后重试，或手动填写网址");
                } else {
                    status.setText("共 " + r.size() + " 个结果，点选即可填入网址");
                    for (Lookup.Hit h : r) loadIcon(h.host);
                }
            });
            return null;
        });
    }

    private void loadIcon(String host) {
        if (icons.containsKey(host)) return;
        icons.put(host, null);
        pool.submit(() -> {
            Bitmap b = Lookup.icon(host);
            if (b == null) return null;
            Icons.cacheHost(a, host, b);
            a.runOnUiThread(() -> { icons.put(host, b); ad.notifyDataSetChanged(); });
            return null;
        });
    }

    private final class Ad extends android.widget.BaseAdapter {
        private final Dialog dlg;
        Ad(Dialog d) { dlg = d; }

        @Override public int getCount() { return hits.size(); }
        @Override public Object getItem(int p) { return hits.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View cv, ViewGroup parent) {
            final Lookup.Hit h = hits.get(p);
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
            int pd = Ui.dp(a, 11);
            l.setPadding(pd, pd, pd, pd);

            ImageView iv = new ImageView(a);
            int s = Ui.dp(a, 38);
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b = icons.get(h.host);
            if (b != null) {
                iv.setImageBitmap(Icons.round(b, Ui.dp(a, 11)));
            } else {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(Ui.withAlpha(Ui.colorOf(h.host), Ui.isDark(a) ? 55 : 40));
                iv.setBackground(g);
                iv.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
                iv.setImageDrawable(Ico.get(a, "web", Ui.colorOf(h.host), 22));
            }
            l.addView(iv);

            LinearLayout tx = new LinearLayout(a);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.setMarginStart(Ui.dp(a, 11));
            tx.setLayoutParams(ml);
            TextView n = new TextView(a);
            n.setText(h.label());
            n.setTextSize(14.5f);
            n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            n.setTextColor(Ui.attr(a, R.attr.textColor));
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(n);
            TextView u = new TextView(a);
            u.setText(h.host);
            u.setTextSize(11);
            u.setTextColor(Ui.attr(a, R.attr.textColor2));
            u.setSingleLine(true);
            tx.addView(u);
            l.addView(tx);

            TextView use = new TextView(a);
            use.setText("使用");
            use.setTextSize(12);
            use.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            use.setTextColor(a.accent());
            use.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 2), 0);
            l.addView(use);

            l.setOnClickListener(v -> {
                dlg.dismiss();
                if (cb != null) cb.onPick(h);
            });
            return l;
        }
    }
}
