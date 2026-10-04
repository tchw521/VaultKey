package com.vaultkey.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Attach;
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
 * 附件管理：看全部附件、占了多少空间、清理孤儿。
 *
 * 最有价值的是「孤儿清理」—— 文件还在但已无卡片引用的那些。
 * 它们加密躺在磁盘上，界面里根本看不到，只有这里能找出来。
 */
public final class AttachActivity extends BaseActivity {

    /**
     * 缩略图解线程池。
     *
     * 必须是实例字段，不能是 static —— 之前写成 static，而 onDestroy 里调了
     * shutdownNow()，导致第二次打开本页时线程池已关闭，
     * execute() 抛 RejectedExecutionException，所有缩略图都不显示。
     */
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private final List<Attach.Item> rows = new ArrayList<>();
    /** 进入页面时算一次，别在 view() 里逐行查卡片表 */
    private final Map<String, String> owners = new HashMap<>();
    private SimpleAdapter<Attach.Item> ad;
    private TextView head;
    private TextView empty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.attr(this, R.attr.bgColor));
        int pd = Ui.dp(this, 16);
        root.setPadding(pd, pd, pd, pd);

        root.addView(backRow("附件管理"));

        head = new TextView(this);
        head.setTextSize(13);
        head.setTextColor(Ui.attr(this, R.attr.textColor2));
        head.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(head);

        /* 清理孤儿按钮 */
        final android.widget.Button clean = button("清理孤儿附件", new int[]{accent(), accent2()});
        clean.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        clean.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clean.setOnClickListener(v -> cleanOrphans());
        root.addView(clean);

        empty = new TextView(this);
        empty.setText("还没有任何附件");
        empty.setTextSize(13);
        empty.setTextColor(Ui.attr(this, R.attr.textColor2));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(empty);

        android.widget.ListView lv = new android.widget.ListView(this);
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

    private SimpleAdapter<Attach.Item> makeAd() {
        return new SimpleAdapter<Attach.Item>() {
            @Override public View view(int pos, Attach.Item it, View cv, ViewGroup parent) {
                LinearLayout l = new LinearLayout(AttachActivity.this);
                l.setOrientation(LinearLayout.HORIZONTAL);
                l.setGravity(Gravity.CENTER_VERTICAL);
                l.setBackground(Ui.glass(AttachActivity.this, 16,
                        R.attr.cardColor, R.attr.strokeColor));
                int p = Ui.dp(AttachActivity.this, 12);
                l.setPadding(p, p, p, p);

                /* 缩略图。解密要一点时间，放后台 */
                ImageView iv = new ImageView(AttachActivity.this);
                int s = Ui.dp(AttachActivity.this, 48);
                iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(Ui.dp(AttachActivity.this, 10));
                g.setColor(Ui.attr(AttachActivity.this, R.attr.cardColor2));
                iv.setBackground(g);
                iv.setImageDrawable(Ico.get(AttachActivity.this, "image",
                        Ui.attr(AttachActivity.this, R.attr.textColor2), 22));
                final String want = it.name;
                iv.setTag(want);
                /* 解密缩略图放后台。用线程池而不是每行一条裸线程 ——
                   列表滚动时每条可见行都会触发一次，裸线程能瞬间开几十条 */
                pool.execute(() -> {
                    android.graphics.Bitmap bm = Attach.load(AttachActivity.this, want, 120);
                    if (bm == null) return;
                    runOnUiThread(() -> {
                        if (want.equals(iv.getTag())) {
                            iv.setImageBitmap(bm);
                            iv.setPadding(0, 0, 0, 0);
                        }
                    });
                });
                l.addView(iv);

                LinearLayout tx = new LinearLayout(AttachActivity.this);
                tx.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ml.setMarginStart(Ui.dp(AttachActivity.this, 12));
                tx.setLayoutParams(ml);

                String own = ownerOf(it.name);
                TextView n = new TextView(AttachActivity.this);
                n.setText(own.isEmpty() ? "（未被任何卡片引用）" : own);
                n.setTextSize(14.5f);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                n.setTextColor(own.isEmpty()
                        ? getResources().getColor(R.color.bad)
                        : Ui.attr(AttachActivity.this, R.attr.textColor));
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(n);

                TextView c = new TextView(AttachActivity.this);
                c.setText(Attach.sizeText(it.size) + " · " + date(it.when));
                c.setTextSize(11.5f);
                c.setTextColor(Ui.attr(AttachActivity.this, R.attr.textColor2));
                tx.addView(c);
                l.addView(tx);
                return l;
            }
        };
    }

    private void act(int pos) {
        Attach.Item it = ad.at(pos);
        if (it == null) return;
        String own = ownerOf(it.name);
        String[] items = own.isEmpty()
                ? new String[]{"查看大图", "删除这个文件"}
                : new String[]{"查看大图", "删除（会同时从卡片上移除）"};
        Dlg.pick(this, own.isEmpty() ? "孤儿附件" : own, items, w -> {
            if (w == 0) ImageViewActivity.show(this, it.name, it.name);
            else confirmDelete(it, own);
        });
    }

    private void confirmDelete(Attach.Item it, String own) {
        String msg = own.isEmpty()
                ? "这个文件已经没有卡片引用，删除可释放 " + Attach.sizeText(it.size)
                : "会从「" + own + "」上移除这张图片，不可恢复。";
        Dlg.danger(this, "删除附件", msg, "删除", () -> {
            /* 先从卡片上摘掉，再删文件；顺序反了会留悬空引用 */
            if (!own.isEmpty()) {
                boolean dirty = false;
                for (Db.Card cd : Db.get(this).cardsRaw()) {
                    List<String> imgs = new ArrayList<>(Db.imgs(cd.imgs));
                    if (imgs.remove(it.name)) {
                        cd.imgs = String.join(",", imgs);
                        Db.get(this).saveCard(cd);
                        dirty = true;
                    }
                }
                if (dirty) toast("已从卡片移除");
            }
            Attach.remove(this, it.name);
            reload();
        });
    }

    private void cleanOrphans() {
        List<Attach.Item> orphans = Attach.orphans(this);
        if (orphans.isEmpty()) { toast("没有孤儿附件"); return; }
        long total = 0;
        for (Attach.Item it : orphans) total += it.size;
        Dlg.danger(this, "清理孤儿附件",
                "有 " + orphans.size() + " 个文件已无卡片引用，共 "
                        + Attach.sizeText(total) + "。\n删除后可释放这些空间。",
                "清理", () -> {
            int n = 0;
            for (Attach.Item it : orphans) { Attach.remove(this, it.name); n++; }
            toast("已清理 " + n + " 个文件");
            reload();
        });
    }

    /** 查本地 Map，没有就是孤儿 */
    private String ownerOf(String name) {
        String s = owners.get(name);
        return s == null ? "" : s;
    }

    private void reload() {
        rows.clear();
        owners.clear();
        owners.putAll(Attach.ownerMap(this));
        rows.addAll(Attach.listAll(this));
        ad.setData(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        List<Attach.Item> orphans = Attach.orphans(this);
        head.setText("共 " + rows.size() + " 个附件 · " + Attach.sizeText(Attach.totalSize(this))
                + (orphans.isEmpty() ? "" : " · " + orphans.size() + " 个孤儿待清理"));
    }

    private String date(long ms) {
        if (ms <= 0) return "未知时间";
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date(ms));
    }

    private View backRow(String t) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 0, 0, Ui.dp(this, 12));
        ImageView iv = new ImageView(this);
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
    @Override protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

}
