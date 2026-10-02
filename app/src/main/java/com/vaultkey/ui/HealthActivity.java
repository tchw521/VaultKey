package com.vaultkey.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.util.Health;
import com.vaultkey.util.Hibp;
import com.vaultkey.util.Ico;
import com.vaultkey.util.SimpleAdapter;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 密码健康度报告。
 *
 * 泄露比对要联网，所以放后台线程跑，跑完再刷新。
 * 查不动（没网 / 超时）会明确说「没查成」，
 * 不把它显示成「没有泄露」 —— 这两个结论差别很大。
 */
public final class HealthActivity extends BaseActivity {

    private LinearLayout box;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Set<String> breached;
    private String breachErr;
    private boolean breachDone;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.attr(this, R.attr.bgColor));
        int pd = Ui.dp(this, 16);
        root.setPadding(pd, pd, pd, pd);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, Ui.dp(this, 12));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        head.addView(iv);
        TextView t = title("密码健康度", 22);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        Ui.press(head);
        head.setOnClickListener(v -> finish());
        root.addView(head);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
    }

    private void render() {
        box.removeAllViews();
        Health.Report r = Health.check(this, breachDone ? breached : null);

        /* 总分 */
        box.addView(scoreCard(r));

        /* 泄露比对状态 */
        box.addView(section("泄露库比对"));
        box.addView(breachRow(r));

        /* 各类问题 */
        box.addView(section("问题明细"));
        if (r.issues.isEmpty()) {
            TextView ok = new TextView(this);
            ok.setText("没有发现问题");
            ok.setTextSize(14);
            ok.setTextColor(getResources().getColor(R.color.ok));
            box.addView(ok);
        } else {
            String[] kinds = {"breach", "dup", "weak", "old", "empty"};
            for (String k : kinds) {
                if (!r.has(k)) continue;
                box.addView(groupCard(r, k));
            }
        }
    }

    /* ---------------- 总分卡片 ---------------- */

    private View scoreCard(Health.Report r) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));

        TextView n = new TextView(this);
        n.setText(String.valueOf(r.score));
        n.setTextSize(46);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(scoreColor(r.score));
        n.setGravity(Gravity.CENTER);
        c.addView(n);

        TextView v = new TextView(this);
        v.setText(r.verdict());
        v.setTextSize(14);
        v.setTextColor(Ui.attr(this, R.attr.textColor));
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        c.addView(v);

        /* 进度条 */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 4));
        bg.setColor(Ui.withAlpha(Color.GRAY, 60));
        bar.setBackground(bg);
        View fill = new View(this);
        fill.setBackgroundColor(scoreColor(r.score));
        GradientDrawable fg = new GradientDrawable();
        fg.setCornerRadius(Ui.dp(this, 4));
        fg.setColor(scoreColor(r.score));
        fill.setBackground(fg);
        bar.addView(fill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.02f, r.score / 100f)));
        c.addView(bar);

        TextView tot = new TextView(this);
        tot.setText("共 " + r.total + " 个账号");
        tot.setTextSize(11.5f);
        tot.setTextColor(Ui.attr(this, R.attr.textColor2));
        tot.setGravity(Gravity.CENTER);
        tot.setPadding(0, Ui.dp(this, 10), 0, 0);
        c.addView(tot);
        return c;
    }

    private int scoreColor(int s) {
        if (s >= 80) return getResources().getColor(R.color.ok);
        if (s >= 50) return 0xFFFBBF24;
        return getResources().getColor(R.color.bad);
    }

    /* ---------------- 泄露比对 ---------------- */

    private View breachRow(Health.Report r) {
        LinearLayout l = card();
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        if (!breachDone) {
            TextView t = new TextView(this);
            t.setText("尚未比对。会用 k-匿名方式查询：只发送密码哈希的前 5 位，"
                    + "密码原文与账号名都不会离开本机。");
            t.setTextSize(12.5f);
            t.setTextColor(Ui.attr(this, R.attr.textColor));
            t.setPadding(0, 0, 0, Ui.dp(this, 10));
            l.addView(t);

            android.widget.Button b = button("开始比对", new int[]{accent(), accent2()});
            b.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            b.setOnClickListener(v -> runBreach());
            l.addView(b);
            return l;
        }

        if (breachErr != null) {
            TextView t = new TextView(this);
            t.setText("没查成：" + breachErr + "\n这不代表没有泄露，请联网后重试。");
            t.setTextSize(12.5f);
            t.setTextColor(getResources().getColor(R.color.bad));
            t.setPadding(0, 0, 0, Ui.dp(this, 10));
            l.addView(t);

            android.widget.Button b = button("重试", new int[]{accent(), accent2()});
            b.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            b.setOnClickListener(v -> runBreach());
            l.addView(b);
            return l;
        }

        int n = r.of("breach");
        TextView t = new TextView(this);
        t.setText(n == 0 ? "已比对，没有发现泄露的密码" : "已比对，有 " + n + " 个密码出现在泄露库中");
        t.setTextSize(13.5f);
        t.setTextColor(n == 0 ? getResources().getColor(R.color.ok)
                : getResources().getColor(R.color.bad));
        l.addView(t);
        return l;
    }

    private void runBreach() {
        toast("正在比对…");
        pool.execute(() -> {
            List<String> pw = new ArrayList<>();
            for (Db.Entry e : Db.get(this).list(null, false, false, null, 0)) {
                if (e.pass != null && !e.pass.isEmpty()) pw.add(e.pass);
            }
            Hibp.Result res = Hibp.check(pw);
            runOnUiThread(() -> {
                breachDone = res.error == null;
                breachErr = res.error;
                if (breachDone) breached = res.breached;
                render();
                if (breachDone) toast("比对完成");
            });
        });
    }

    /* ---------------- 问题分组 ---------------- */

    private View groupCard(Health.Report r, String kind) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(Health.kindName(kind));
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getResources().getColor(R.color.bad));
        h.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = new TextView(this);
        cnt.setText(r.of(kind) + " 条");
        cnt.setTextSize(13);
        cnt.setTextColor(Ui.attr(this, R.attr.textColor2));
        h.addView(cnt);
        c.addView(h);

        TextView tip = new TextView(this);
        tip.setText(Health.kindTip(kind));
        tip.setTextSize(11.5f);
        tip.setTextColor(Ui.attr(this, R.attr.textColor2));
        tip.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        c.addView(tip);

        List<Health.Issue> mine = new ArrayList<>();
        for (Health.Issue i : r.issues) if (kind.equals(i.kind)) mine.add(i);

        for (Health.Issue i : mine) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            TextView a1 = new TextView(this);
            a1.setText(i.title);
            a1.setTextSize(13.5f);
            a1.setTextColor(Ui.attr(this, R.attr.textColor));
            a1.setSingleLine(true);
            a1.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(a1);
            TextView b1 = new TextView(this);
            b1.setText(i.detail);
            b1.setTextSize(11.5f);
            b1.setTextColor(Ui.attr(this, R.attr.textColor2));
            row.addView(b1);
            c.addView(row);
        }
        return c;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
