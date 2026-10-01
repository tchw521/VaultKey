package com.vaultkey.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Ico;
import com.vaultkey.util.IconFill;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;

/** 自动获取图标：扫描本机所有账号，本地优先，本地没有才走云端搜索 */
public final class IconFillActivity extends BaseActivity {
    private ListView list;
    private Ad ad;
    private TextView progress, summary;
    private TextView startBtn, stopBtn;
    private android.widget.CheckBox fillUrlBox;
    private final List<Row> rows = new ArrayList<>();
    private Db.Entry[] todo;
    private boolean running;

    private static final class Row {
        String title = "";
        String detail = "";
        Bitmap icon;
        boolean done, ok;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        body.setPadding(0, Ui.dp(this, 8), 0, 0);

        TextView t = title("自动获取图标", 20);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        body.addView(t);

        TextView intro = label("按「已存图标 → 本机 App → 本地缓存 → 网址 → 包名 → 名称搜索」"
                + "的顺序依次尝试，任何一步拿到就停。已有机图标的自动跳过。并发 4 线程，同域名只下一次。");
        intro.setTextSize(11.5f);
        intro.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(intro);

        int total = Db.get(this).count(false, 0);
        int miss = IconFill.missing(this).size();
        summary = new TextView(this);
        summary.setTextSize(12.5f);
        summary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        summary.setTextColor(miss == 0 ? getResources().getColor(R.color.ok) : accent());
        summary.setText(miss == 0 ? "全部 " + total + " 个账号都已有图标，无需处理"
                : "共 " + total + " 个账号，其中 " + miss + " 个还没有图标");
        summary.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(summary);

        fillUrlBox = new android.widget.CheckBox(this);
        fillUrlBox.setText("名称搜索到网址时，顺带填回该账号的网址字段");
        fillUrlBox.setTextSize(12);
        fillUrlBox.setTextColor(Ui.attr(this, R.attr.textColor2));
        fillUrlBox.setPadding(0, 0, 0, Ui.dp(this, 8));
        body.addView(fillUrlBox);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        startBtn = new TextView(this);
        startBtn.setText("开始获取");
        startBtn.setTextSize(14);
        startBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        startBtn.setPadding(Ui.dp(this, 26), Ui.dp(this, 11), Ui.dp(this, 26), Ui.dp(this, 11));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startBtn.setLayoutParams(sl);
        Ui.press(startBtn);
        startBtn.setOnClickListener(v -> start());
        btns.addView(startBtn);

        stopBtn = new TextView(this);
        stopBtn.setText("停止");
        stopBtn.setTextSize(14);
        stopBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stopBtn.setTextColor(getResources().getColor(R.color.bad));
        stopBtn.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Ui.withAlpha(getResources().getColor(R.color.bad), 26));
        sg.setCornerRadius(999f);
        stopBtn.setBackground(sg);
        stopBtn.setPadding(Ui.dp(this, 26), Ui.dp(this, 11), Ui.dp(this, 26), Ui.dp(this, 11));
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stl.setMarginStart(Ui.dp(this, 12));
        stopBtn.setLayoutParams(stl);
        stopBtn.setVisibility(View.GONE);
        Ui.press(stopBtn);
        stopBtn.setOnClickListener(v -> { IconFill.cancel(); toast("已停止"); });
        btns.addView(stopBtn);
        body.addView(btns);

        progress = new TextView(this);
        progress.setTextSize(12);
        progress.setTextColor(Ui.attr(this, R.attr.textColor2));
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        progress.setVisibility(View.GONE);
        body.addView(progress);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 6));
        list.setVerticalScrollBarEnabled(false);
        ad = new Ad();
        list.setAdapter(ad);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        ll.topMargin = Ui.dp(this, 4);
        body.addView(list, ll);

        if (miss == 0) {
            startBtn.setAlpha(0.45f);
            startBtn.setEnabled(false);
        }
    }

    private void start() {
        List<Db.Entry> miss = IconFill.missing(this);
        if (miss.isEmpty()) { toast("都已获取过了"); return; }
        todo = miss.toArray(new Db.Entry[0]);
        rows.clear();
        for (Db.Entry e : todo) {
            Row r = new Row();
            r.title = e.title == null || e.title.isEmpty() ? e.user : e.title;
            r.detail = "等待中";
            rows.add(r);
        }
        ad.notifyDataSetChanged();

        running = true;
        startBtn.setVisibility(View.GONE);
        stopBtn.setVisibility(View.VISIBLE);
        fillUrlBox.setEnabled(false);
        progress.setVisibility(View.VISIBLE);

        final boolean fillUrl = fillUrlBox.isChecked();
        IconFill.run(this, miss, fillUrl, new IconFill.Cb() {
            @Override public void onOne(IconFill.Result r, int done, int total) {
                for (int i = 0; i < todo.length; i++) {
                    if (todo[i].uuid.equals(r.uuid)) {
                        Row row = rows.get(i);
                        row.done = true;
                        row.ok = r.ok;
                        row.icon = r.icon;
                        row.detail = r.ok
                                ? (IconFill.fromName(r.from) + (r.host.isEmpty() ? "" : " · " + r.host))
                                : "没找到，可手动填网址或选本机 App";
                        break;
                    }
                }
                ad.notifyDataSetChanged();
                progress.setText("正在处理 " + done + " / " + total
                        + (r.ok ? " · " + IconFill.fromName(r.from) : ""));
            }

            @Override public void onDone(int ok, int fail, boolean cancelled) {
                running = false;
                startBtn.setVisibility(View.VISIBLE);
                stopBtn.setVisibility(View.GONE);
                fillUrlBox.setEnabled(true);
                progress.setText(cancelled ? "已停止" : "完成：成功 " + ok + " 个，失败 " + fail + " 个");
                int miss = IconFill.missing(IconFillActivity.this).size();
                summary.setText(miss == 0 ? "全部账号都已有图标" : "还有 " + miss + " 个没有图标，可再点一次重试");
                summary.setTextColor(miss == 0 ? getResources().getColor(R.color.ok) : accent());
                startBtn.setAlpha(miss == 0 ? 0.45f : 1f);
                startBtn.setEnabled(miss > 0);
                toast("完成：成功 " + ok + "，失败 " + fail + "。返回列表即可看到新图标");
            }
        });
    }

    @Override public void onBackPressed() {
        if (running) {
            new android.app.AlertDialog.Builder(this).setTitle("正在获取图标")
                    .setMessage("确定停止并退出吗？已获取的图标会保留。")
                    .setPositiveButton("停止并退出", (d, w) -> { IconFill.cancel(); finish(); })
                    .setNegativeButton("继续", null).show();
            return;
        }
        super.onBackPressed();
    }

    private final class Ad extends android.widget.BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View cv, ViewGroup parent) {
            Row r = rows.get(p);
            LinearLayout l = new LinearLayout(IconFillActivity.this);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackground(Ui.glass(IconFillActivity.this, 16, R.attr.cardColor, R.attr.strokeColor));
            int pd = Ui.dp(IconFillActivity.this, 11);
            l.setPadding(pd, pd, pd, pd);

            android.widget.FrameLayout badge = new android.widget.FrameLayout(IconFillActivity.this);
            int bs = Ui.dp(IconFillActivity.this, 38);
            badge.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            ImageView iv = new ImageView(IconFillActivity.this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(bs, bs));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (r.icon != null) {
                iv.setImageBitmap(Icons.round(r.icon, Ui.dp(IconFillActivity.this, 11)));
            } else {
                int c = Ui.colorOf(r.title);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(Ui.withAlpha(c, 42));
                iv.setBackground(g);
                iv.setPadding(Ui.dp(IconFillActivity.this, 8), Ui.dp(IconFillActivity.this, 8),
                        Ui.dp(IconFillActivity.this, 8), Ui.dp(IconFillActivity.this, 8));
                iv.setImageDrawable(Ico.get(IconFillActivity.this, r.done && !r.ok ? "more" : "web", c, 20));
            }
            badge.addView(iv);
            l.addView(badge);

            LinearLayout tx = new LinearLayout(IconFillActivity.this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.setMarginStart(Ui.dp(IconFillActivity.this, 11));
            tx.setLayoutParams(ml);
            TextView n = new TextView(IconFillActivity.this);
            n.setText(r.title);
            n.setTextSize(14.5f);
            n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            n.setTextColor(Ui.attr(IconFillActivity.this, R.attr.textColor));
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(n);
            TextView s = new TextView(IconFillActivity.this);
            s.setText(r.detail);
            s.setTextSize(11);
            s.setTextColor(r.done && r.ok ? accent() : Ui.attr(IconFillActivity.this, R.attr.textColor2));
            s.setSingleLine(true);
            tx.addView(s);
            l.addView(tx);
            return l;
        }
    }
}
