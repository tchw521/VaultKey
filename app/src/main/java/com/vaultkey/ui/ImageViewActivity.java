package com.vaultkey.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Attach;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Ui;
import java.util.List;

/** 全屏查看加密图片：左右切换，长按保存禁止 */
public final class ImageViewActivity extends BaseActivity {
    private List<String> names;
    private int idx;
    private ImageView iv;
    private TextView counter;

    public static void show(Activity a, String csv, String cur) {
        Intent i = new Intent(a, ImageViewActivity.class);
        i.putExtra("imgs", csv);
        i.putExtra("cur", cur);
        a.startActivity(i);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        names = com.vaultkey.data.Db.imgs(getIntent().getStringExtra("imgs"));
        String cur = getIntent().getStringExtra("cur");
        idx = Math.max(0, names.indexOf(cur == null ? "" : cur));
        if (names.isEmpty()) { finish(); return; }

        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);

        iv = new ImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.withAlpha(Color.BLACK, 140));
        int p = Ui.dp(this, 14);
        bar.setPadding(p, p, p, p);
        ImageView close = new ImageView(this);
        close.setImageDrawable(Ico.get(this, "back", Color.WHITE, 22));
        close.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        counter = new TextView(this);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(14);
        counter.setPadding(Ui.dp(this, 14), 0, 0, 0);
        bar.addView(counter);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        f.addView(bar, bl);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, 0, 0, Ui.dp(this, 8));
        android.widget.Button prev = new android.widget.Button(this);
        prev.setText("上一张");
        prev.setTextColor(Color.WHITE);
        prev.setBackgroundColor(Color.TRANSPARENT);
        prev.setOnClickListener(v -> { if (idx > 0) { idx--; load(); } });
        nav.addView(prev);
        android.widget.Button next = new android.widget.Button(this);
        next.setText("下一张");
        next.setTextColor(Color.WHITE);
        next.setBackgroundColor(Color.TRANSPARENT);
        next.setOnClickListener(v -> { if (idx < names.size() - 1) { idx++; load(); } });
        nav.addView(next);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        f.addView(nav, nl);

        setContentView(f);
        load();
    }

    private void load() {
        counter.setText((idx + 1) + " / " + names.size());
        final String name = names.get(idx);
        iv.setTag(name);
        new Thread(() -> {
            Bitmap b = Attach.load(this, name, 1600);
            if (b == null) return;
            runOnUiThread(() -> { if (name.equals(iv.getTag())) iv.setImageBitmap(b); });
        }).start();
    }
}
