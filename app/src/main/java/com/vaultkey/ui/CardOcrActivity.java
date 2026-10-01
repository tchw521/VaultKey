package com.vaultkey.ui;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.util.Ico;
import com.vaultkey.util.MiniOcr;
import com.vaultkey.util.Ui;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 卡包 OCR：拍照 / 相册 / 系统分享 → 本地识别 → 确认填入。全程离线 */
public final class CardOcrActivity extends BaseActivity {
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_FIELDS = "fields";   // 返回 "k\u0001v\u0001k\u0001v..."
    public static final String EXTRA_TITLE = "title";

    private static final int REQ_CAM = 61, REQ_PICK = 62;

    private String kind = "身份证";
    private Bitmap bmp;
    private final List<String[]> found = new ArrayList<>();   // {字段名, 值}
    private final List<EditText> edits = new ArrayList<>();
    private LinearLayout resultBox;
    private TextView status;
    private View confirmBtn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String k = getIntent().getStringExtra(EXTRA_KIND);
        if (k != null) kind = k;

        body.setPadding(0, Ui.dp(this, 8), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = title("识别" + kind, 20);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        body.addView(head);

        TextView intro = label("拍照或选图后，在本地识别卡号 / 证件号。"
                + "识别不会联网，图片也不上传。识别结果请自行核对。");
        intro.setTextSize(11.5f);
        intro.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        body.addView(intro);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER);
        acts.addView(act("拍照", v -> openCamera()));
        acts.addView(act("从相册选", v -> openGallery()));
        body.addView(acts);

        status = new TextView(this);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Ui.attr(this, R.attr.textColor2));
        status.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        status.setVisibility(View.GONE);
        body.addView(status);

        ScrollView sv = new ScrollView(this);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(0, 0, 0, Ui.dp(this, 100));
        sv.addView(resultBox);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(this, 6);
        body.addView(sv, sl);

        /* 系统分享进来的图片直接开跑 */
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction()) && in.getType() != null
                && in.getType().startsWith("image/")) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                Bitmap s = load(u);
                if (s != null) { bmp = s; runOcr(); }
                else status.setText("读不到这张图片");
            }
        }
    }

    private View act(String name, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        android.graphics.Typeface tf = android.graphics.Typeface.DEFAULT_BOLD;
        t.setTypeface(tf);
        t.setTextColor(android.graphics.Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        t.setPadding(Ui.dp(this, 22), Ui.dp(this, 11), Ui.dp(this, 22), Ui.dp(this, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        Ui.press(t);
        t.setOnClickListener(l);
        return t;
    }

    private void openCamera() {
        try {
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(i, REQ_CAM);
        } catch (Exception e) {
            toast("没有可用的相机");
        }
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("打不开相册");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        Bitmap b = null;
        if (req == REQ_CAM) {
            if (data != null && data.getExtras() != null) {
                Object o = data.getExtras().get("data");
                if (o instanceof Bitmap) b = (Bitmap) o;
            }
            if (b == null) toast("相机没返回图片，可改用「从相册选」");
        } else if (req == REQ_PICK && data != null && data.getData() != null) {
            b = load(data.getData());
        }
        if (b != null) { bmp = b; runOcr(); }
    }

    private Bitmap load(Uri u) {
        try {
            InputStream in = getContentResolver().openInputStream(u);
            if (in == null) return null;
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- OCR ---------------- */

    private void runOcr() {
        if (bmp == null) return;
        status.setVisibility(View.VISIBLE);
        status.setText("正在本地识别…");
        resultBox.removeAllViews();
        confirmBtn = null;

        final ProgressDialog pd = progress("识别中…");
        pd.show();

        final String k = kind;
        new Thread(() -> {
            String charset = "护照".equals(k) ? MiniOcr.FULL : MiniOcr.DIGITS;
            List<MiniOcr.Line> lines = MiniOcr.read(this, bmp, charset);
            String raw = MiniOcr.flat(lines);
            final List<String[]> res = parse(k, raw);
            final String rawF = raw;
            runOnUiThread(() -> {
                safeDismiss(pd);
                if (res.isEmpty()) {
                    status.setText("没识别出可填入的号码。试试正对光线重拍，或手动填写。");
                    TextView dbg = new TextView(this);
                    dbg.setText("识别到的原始文本：\n" + rawF.trim());
                    dbg.setTextSize(11);
                    dbg.setTextColor(Ui.attr(this, R.attr.textColor2));
                    dbg.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
                    resultBox.addView(dbg);
                    return;
                }
                status.setText("识别完成，核对后填入");
                render(res, rawF);
            });
        }).start();
    }

    /** 按卡种从原始文本里提取字段 */
    private List<String[]> parse(String kind, String raw) {
        List<String[]> out = new ArrayList<>();
        switch (kind) {
            case "身份证": {
                String id = MiniOcr.pickId(raw);
                if (id != null) {
                    out.add(new String[]{"身份证号", id});
                    String b = MiniOcr.idBirth(id);
                    if (b != null) out.add(new String[]{"出生日期", b});
                    String g = MiniOcr.idGender(id);
                    if (g != null) out.add(new String[]{"性别", g});
                }
                break;
            }
            case "银行卡": {
                String no = MiniOcr.pickCard(raw);
                if (no != null) out.add(new String[]{"卡号", no});
                String ex = MiniOcr.pickExpiry(raw);
                if (ex != null) out.add(new String[]{"有效期", ex});
                break;
            }
            case "社保卡": {
                String id = MiniOcr.pickId(raw);
                if (id != null) out.add(new String[]{"社保编号", id});
                else {
                    String no = longestDigits(raw, 9, 20);
                    if (no != null) out.add(new String[]{"社保编号", no});
                }
                break;
            }
            case "驾驶证": {
                String id = MiniOcr.pickId(raw);
                if (id != null) out.add(new String[]{"证号", id});
                String ex = MiniOcr.pickExpiry(raw);
                if (ex != null) out.add(new String[]{"有效期", ex});
                break;
            }
            case "护照": {
                String p = MiniOcr.pickPassport(raw);
                if (p != null) out.add(new String[]{"护照号", p});
                String ex = MiniOcr.pickExpiry(raw);
                if (ex != null) out.add(new String[]{"有效期", ex});
                break;
            }
            default: {
                String no = MiniOcr.pickCard(raw);
                if (no != null) out.add(new String[]{"编号", no});
                else {
                    String n2 = longestDigits(raw, 6, 24);
                    if (n2 != null) out.add(new String[]{"编号", n2});
                }
                break;
            }
        }
        return out;
    }

    /** 取最长的一段纯数字（长度在 [min,max] 内），用于没有校验位的编号 */
    private static String longestDigits(String raw, int min, int max) {
        String best = null;
        for (String seg : raw.split("[^0-9]+")) {
            if (seg.length() < min || seg.length() > max) continue;
            if (best == null || seg.length() > best.length()) best = seg;
        }
        return best;
    }

    private void render(List<String[]> res, String raw) {
        found.clear();
        edits.clear();
        resultBox.removeAllViews();

        TextView h = new TextView(this);
        h.setText("识别结果（可修改）");
        h.setTextSize(12);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setTextColor(accent());
        h.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 8));
        resultBox.addView(h);

        for (String[] kv : res) {
            found.add(kv);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.glass(this, 16, R.attr.cardColor, R.attr.strokeColor));
            int p = Ui.dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 8);
            row.setLayoutParams(lp);

            TextView n = new TextView(this);
            n.setText(kv[0]);
            n.setTextSize(11.5f);
            n.setTextColor(Ui.attr(this, R.attr.textColor2));
            row.addView(n);

            EditText e = new EditText(this);
            e.setText(kv[1]);
            e.setTextSize(15);
            e.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            e.setTextColor(Ui.attr(this, R.attr.textColor));
            e.setBackground(null);
            e.setPadding(0, Ui.dp(this, 4), 0, 0);
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            row.addView(e);
            edits.add(e);

            resultBox.addView(row);
        }

        TextView dbg = new TextView(this);
        dbg.setText("原始识别文本：" + raw.trim());
        dbg.setTextSize(10.5f);
        dbg.setTextColor(Ui.attr(this, R.attr.textColor2));
        dbg.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, Ui.dp(this, 12));
        resultBox.addView(dbg);

        android.widget.Button ok = button("填入这张卡片", new int[]{accent(), accent2()});
        ok.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ok.setOnClickListener(v -> confirm());
        confirmBtn = ok;
        resultBox.addView(ok);
    }

    private void confirm() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < found.size(); i++) {
            String v = edits.get(i).getText().toString().trim();
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(found.get(i)[0]).append('\u0001').append(v);
        }
        if (sb.length() == 0) { toast("没有可填入的内容"); return; }
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_FIELDS, sb.toString()));
        finish();
    }
}
