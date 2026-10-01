package com.vaultkey.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Attach;
import com.vaultkey.data.Db;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Liquid;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;

/** 新增 / 编辑卡片：字段按卡种模板生成，可增删字段、添加加密图片 */
public final class CardEditActivity extends BaseActivity {
    private static final int REQ_PICK = 77;
    private static final int REQ_OCR = 78;

    private Db.Card card;
    private EditText titleEdit;
    private LinearLayout fieldsBox;
    private LinearLayout imgStrip;
    private final List<Db.F> fields = new ArrayList<>();
    private final List<String> imgs = new ArrayList<>();
    private String kind;

    public static void open(Activity a, long id) {
        a.startActivity(new Intent(a, CardEditActivity.class).putExtra("id", id));
    }

    public static void openNew(Activity a, String kind) {
        a.startActivity(new Intent(a, CardEditActivity.class)
                .putExtra("new", true).putExtra("kind", kind));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        long id = getIntent().getLongExtra("id", -1);
        boolean isNew = getIntent().getBooleanExtra("new", false);
        kind = getIntent().getStringExtra("kind");
        if (isNew || id < 0) {
            card = Db.get(this).newCard(kind);
            fields.addAll(Db.fields(card.fields));
        } else {
            card = Db.get(this).getCard(id);
            if (card == null) { finish(); return; }
            kind = card.kind;
            fields.addAll(Db.fields(card.fields));
            imgs.addAll(Db.imgs(card.imgs));
        }
        build();
    }

    private void build() {
        body.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 110));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        back.setOnClickListener(x -> finish());
        head.addView(back);
        TextView t = title(card.id > 0 ? "编辑卡片" : "新增卡片", 22);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        v.addView(head);

        /* 卡种选择 */
        v.addView(secTitle("卡种"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout ks = new LinearLayout(this);
        ks.setOrientation(LinearLayout.HORIZONTAL);
        ks.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        for (int i = 0; i < Db.CARD_KINDS.length; i++) {
            final String k = Db.CARD_KINDS[i];
            TextView c = chip(k, k.equals(kind));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.setMarginEnd(Ui.dp(this, 8));
            c.setLayoutParams(cl);
            Ui.press(c);
            c.setOnClickListener(x -> {
                if (k.equals(kind)) return;
                kind = k;
                fields.clear();
                for (String f : Db.tpl(k)) fields.add(new Db.F(f, ""));
                build();
            });
            ks.addView(c);
        }
        hs.addView(ks);
        v.addView(hs);

        /* 名称 */
        v.addView(section("名称"));
        titleEdit = field("例如：我的工商银行卡", card.title);
        v.addView(titleEdit);

        /* 字段 */
        v.addView(section("卡面信息"));
        LinearLayout card1 = new LinearLayout(this);
        card1.setOrientation(LinearLayout.VERTICAL);
        card1.setBackground(Liquid.glass(this, 20));
        int p = Ui.dp(this, 14);
        card1.setPadding(p, p, p, p);
        fieldsBox = new LinearLayout(this);
        fieldsBox.setOrientation(LinearLayout.VERTICAL);
        card1.addView(fieldsBox);
        renderFields();
        v.addView(card1);

        TextView addF = new TextView(this);
        addF.setText("＋ 添加字段");
        addF.setTextSize(13);
        addF.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        addF.setTextColor(accent());
        addF.setGravity(Gravity.CENTER);
        addF.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        Ui.press(addF);
        addF.setOnClickListener(x -> {
            final EditText ne = field("字段名称", null);
            new android.app.AlertDialog.Builder(this).setTitle("新字段").setView(wrap(ne))
                    .setPositiveButton("添加", (d, w) -> {
                        String n = ne.getText().toString().trim();
                        if (n.isEmpty()) return;
                        fields.add(new Db.F(n, ""));
                        renderFields();
                    }).setNegativeButton("取消", null).show();
        });
        v.addView(addF);

        /* 图片 */
        v.addView(section("附件图片（加密保存）"));
        LinearLayout card2 = new LinearLayout(this);
        card2.setOrientation(LinearLayout.VERTICAL);
        card2.setBackground(Liquid.glass(this, 20));
        card2.setPadding(p, p, p, p);
        imgStrip = new LinearLayout(this);
        imgStrip.setOrientation(LinearLayout.HORIZONTAL);
        card2.addView(imgStrip);
        renderImgs();

        TextView addI = new TextView(this);
        addI.setText("＋ 从相册添加");
        addI.setTextSize(13);
        addI.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        addI.setTextColor(accent());
        addI.setGravity(Gravity.CENTER);
        addI.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        Ui.press(addI);
        addI.setOnClickListener(x -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(i, REQ_PICK); } catch (Exception e) { toast("无法打开相册"); }
        });
        card2.addView(addI);
        TextView tip = label("图片会压缩后 AES 加密存到本机，不会上传到任何地方");
        tip.setTextSize(11);
        tip.setPadding(0, Ui.dp(this, 6), 0, 0);
        card2.addView(tip);
        v.addView(card2);

        /* 备注 */
        v.addView(section("备注"));
        TextView ocrBtn = chip("📷 拍照 / 选图识别", false);
        ocrBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(this, 12);
        ocrBtn.setLayoutParams(ol);
        ocrBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        Ui.press(ocrBtn);
        ocrBtn.setOnClickListener(ov -> startActivityForResult(
                new Intent(this, CardOcrActivity.class).putExtra(CardOcrActivity.EXTRA_KIND, kind),
                REQ_OCR));
        v.addView(ocrBtn);

        final EditText note = field("可选", card.note);
        v.addView(note);

        /* 保存 */
        android.widget.Button save = button("保存", new int[]{accent(), accent2()});
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 22);
        save.setLayoutParams(sl);
        save.setOnClickListener(x -> {
            card.kind = kind == null ? "自定义" : kind;
            card.title = titleEdit.getText().toString().trim();
            card.note = note.getText().toString();
            for (int i = 0; i < fields.size(); i++) {
                View vv = fieldsBox.getChildAt(i);
                if (vv instanceof LinearLayout && ((LinearLayout) vv).getChildCount() > 1) {
                    View inner = ((LinearLayout) vv).getChildAt(1);
                    if (inner instanceof EditText) fields.get(i).v = ((EditText) inner).getText().toString();
                }
            }
            card.fields = Db.packFields(fields);
            StringBuilder sb = new StringBuilder();
            for (String s : imgs) { if (sb.length() > 0) sb.append(','); sb.append(s); }
            card.imgs = sb.toString();
            if (card.title.isEmpty()) card.title = card.kind;
            Db.get(this).saveCard(card);
            toast("已保存");
            finish();
        });
        v.addView(save);

        if (card.id > 0) {
            TextView del = new TextView(this);
            del.setText("删除这张卡");
            del.setTextSize(13);
            del.setTextColor(getResources().getColor(R.color.bad));
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, Ui.dp(this, 18), 0, 0);
            del.setOnClickListener(x -> {
                Db.get(this).softDeleteCard(card.id);
                finish();
            });
            v.addView(del);
        }

        sv.addView(v);
        body.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.popIn(v, 0);
    }

    private View secTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(accent());
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    private View wrap(View v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), 0);
        l.addView(v);
        return l;
    }

    private void renderFields() {
        fieldsBox.removeAllViews();
        for (int i = 0; i < fields.size(); i++) {
            final int idx = i;
            final Db.F f = fields.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = Ui.dp(this, 8);
            row.setLayoutParams(rl);

            TextView k = new TextView(this);
            k.setText(f.k);
            k.setTextSize(13);
            k.setTextColor(Ui.attr(this, R.attr.textColor2));
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(Ui.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
            k.setLayoutParams(kl);
            row.addView(k);

            EditText e = new EditText(this);
            e.setText(f.v);
            e.setHint(f.k);
            e.setTextColor(Ui.attr(this, R.attr.textColor));
            e.setHintTextColor(Ui.attr(this, R.attr.textColor2));
            e.setBackground(Ui.glass(this, 12, R.attr.cardColor2, R.attr.strokeColor));
            e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            e.setTextSize(14);
            e.setSingleLine(true);
            if (f.k.contains("号") || f.k.contains("编号")) {
                e.setInputType(InputType.TYPE_CLASS_TEXT);
            }
            e.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(e);

            ImageView rm = new ImageView(this);
            rm.setImageDrawable(Ico.get(this, "trash", Ui.attr(this, R.attr.textColor2), 16));
            rm.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
            LinearLayout.LayoutParams rml = (LinearLayout.LayoutParams) rm.getLayoutParams();
            rml.setMarginStart(Ui.dp(this, 8));
            rm.setLayoutParams(rml);
            rm.setOnClickListener(x -> { fields.remove(idx); renderFields(); });
            row.addView(rm);

            fieldsBox.addView(row);
        }
    }

    private void renderImgs() {
        imgStrip.removeAllViews();
        if (imgs.isEmpty()) {
            TextView t = label("暂无图片");
            t.setTextSize(12);
            imgStrip.addView(t);
            return;
        }
        for (int i = 0; i < imgs.size(); i++) {
            final String name = imgs.get(i);
            final int idx = i;
            android.widget.FrameLayout f = new android.widget.FrameLayout(this);
            int w = Ui.dp(this, 96);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(w, Ui.dp(this, 72));
            if (i > 0) fl.setMarginStart(Ui.dp(this, 8));
            f.setLayoutParams(fl);

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(w, Ui.dp(this, 72)));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.attr(this, R.attr.cardColor2));
            g.setCornerRadius(Ui.dp(this, 10));
            iv.setBackground(g);
            iv.setTag(name);
            f.addView(iv);
            new Thread(() -> {
                Bitmap b = Attach.load(this, name, 400);
                if (b == null) return;
                runOnUiThread(() -> { if (name.equals(iv.getTag())) iv.setImageBitmap(b); });
            }).start();

            ImageView x = new ImageView(this);
            int xs = Ui.dp(this, 22);
            android.widget.FrameLayout.LayoutParams xl = new android.widget.FrameLayout.LayoutParams(
                    xs, xs, Gravity.TOP | Gravity.END);
            x.setLayoutParams(xl);
            GradientDrawable xg = new GradientDrawable();
            xg.setShape(GradientDrawable.OVAL);
            xg.setColor(Ui.withAlpha(Color.BLACK, 150));
            x.setBackground(xg);
            x.setImageDrawable(Ico.get(this, "add", Color.WHITE, 12));
            x.setRotation(45);
            x.setOnClickListener(vv -> {
                imgs.remove(idx);
                Attach.remove(this, name);
                renderImgs();
            });
            f.addView(x);

            iv.setOnClickListener(vv -> ImageViewActivity.show(this, String.join(",", imgs), name));
            imgStrip.addView(f);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OCR && res == RESULT_OK && data != null) {
            String packed = data.getStringExtra(CardOcrActivity.EXTRA_FIELDS);
            if (packed == null || packed.isEmpty()) return;
            String[] kv = packed.split("\u0001");
            int added = 0;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = kv[i], val = kv[i + 1];
                boolean hit = false;
                for (Db.F f : fields) {
                    if (f.k != null && f.k.equals(k)) { f.v = val; hit = true; break; }
                }
                if (!hit) fields.add(new Db.F(k, val));
                added++;
            }
            renderFields();
            toast("已填入 " + added + " 个字段，请核对");
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        final android.app.ProgressDialog pd = progress("正在加密保存…");
        pd.show();
        new Thread(() -> {
            String name = Attach.save(this, uri);
            runOnUiThread(() -> {
                safeDismiss(pd);
                if (name == null) { toast("保存失败，可能不是有效图片"); return; }
                imgs.add(name);
                renderImgs();
                toast("已加密保存");
            });
        }).start();
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
    }
}
