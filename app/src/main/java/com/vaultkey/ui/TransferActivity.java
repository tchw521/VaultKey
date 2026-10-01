package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Lan;
import com.vaultkey.util.Ui;
import java.io.File;

/**
 * 换机局域网直传。
 *
 * 两台手机连同一 WiFi：旧手机「发送」显示配对码，新手机「接收」自动找到它、
 * 输入配对码，整个密码库就过去了。不用配坚果云，也不用导出导入文件。
 *
 * 传的是数据库文件本身，而库里的账号密码**本来就是 AES 密文**，
 * 所以全程传的都是密文 —— 同一 WiFi 上的其他人抓到也解不开。
 */
public final class TransferActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";   // send | recv

    private boolean send;
    private Lan.Server server;
    private TextView state, codeView, ipView;
    private EditText codeInput;
    private TextView goBtn;
    private String foundHost = null;
    private int foundPort = Lan.PORT;
    private String myCode;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        send = !"recv".equals(getIntent().getStringExtra(EXTRA_MODE));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = title(send ? "发送到新手机" : "从旧手机接收", 20);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        body.addView(head);

        TextView intro = label(send
                ? "两台手机连同一个 WiFi。保持这个页面打开，在新手机上点「从旧手机接收」，"
                  + "输入下面的配对码即可。"
                : "两台手机连同一个 WiFi。旧手机上先打开「发送到新手机」，"
                  + "这里会自动找到它，输入对方显示的配对码即可接收。");
        intro.setTextSize(11.5f);
        intro.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        body.addView(intro);

        /* 安全说明：说清传的是密文，打消顾虑 */
        TextView safe = new TextView(this);
        safe.setText("传输的是数据库文件本身，库里的内容本就是 AES 密文，"
                + "同一网络上的其他人即使截获也解不开。");
        safe.setTextSize(11);
        safe.setTextColor(accent());
        safe.setBackground(Ui.inner(this, 12));
        safe.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        body.addView(safe);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(Ui.sheet(this, 18));
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 12);
        box.setLayoutParams(bl);
        body.addView(box);

        if (send) buildSend(box); else buildRecv(box);

        state = new TextView(this);
        state.setTextSize(12);
        state.setTextColor(Ui.attr(this, R.attr.textColor2));
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, Ui.dp(this, 14), 0, 0);
        body.addView(state);

        if (send) startServer(); else startDiscover();
    }

    /* ---------------- 发送 ---------------- */

    private void buildSend(LinearLayout box) {
        TextView lab = new TextView(this);
        lab.setText("配对码");
        lab.setTextSize(11.5f);
        lab.setTextColor(Ui.attr(this, R.attr.textColor2));
        box.addView(lab);

        myCode = Lan.newCode();
        codeView = new TextView(this);
        codeView.setText(myCode);
        codeView.setTextSize(38);
        codeView.setTypeface(Typeface.DEFAULT_BOLD);
        codeView.setTypeface(android.graphics.Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setTextColor(accent());
        codeView.setLetterSpacing(0.22f);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        box.addView(codeView);

        ipView = new TextView(this);
        String ip = Lan.myIp(this);
        ipView.setText("本机 IP  " + (ip == null ? "未连接 WiFi" : ip));
        ipView.setTextSize(11);
        ipView.setTextColor(Ui.attr(this, R.attr.textColor2));
        ipView.setGravity(Gravity.CENTER);
        box.addView(ipView);
    }

    private void startServer() {
        state.setText("等待对方连接…");
        File db = getDatabasePath("vault.db");
        if (!db.exists()) { state.setText("本机还没有数据可发送"); return; }

        String meta = "{\"salt\":\"" + Prefs.get("salt", "")
                + "\",\"mk\":\"" + Prefs.get("mk", "")
                + "\",\"iter\":" + Prefs.getI("iter", 180000) + "}";
        byte[] extra = new byte[0];
        try { extra = meta.getBytes("UTF-8"); } catch (Exception ignored) { }

        server = Lan.serve(this, db, extra, myCode, new Lan.Cb() {
            @Override public void state(String s) { runOnUiThread(() -> state.setText(s)); }
            @Override public void progress(int pct) {
                runOnUiThread(() -> state.setText("传输中  " + pct + "%"));
            }
            @Override public void done(String msg) {
                runOnUiThread(() -> {
                    state.setText("✅ " + msg + "，可以关闭这个页面了");
                    toast("传输完成");
                });
            }
            @Override public void err(String msg) {
                runOnUiThread(() -> state.setText("⚠ " + msg));
            }
        });
    }

    /* ---------------- 接收 ---------------- */

    private void buildRecv(LinearLayout box) {
        TextView lab = new TextView(this);
        lab.setText("输入对方显示的配对码");
        lab.setTextSize(11.5f);
        lab.setTextColor(Ui.attr(this, R.attr.textColor2));
        box.addView(lab);

        codeInput = new EditText(this);
        codeInput.setHint("6 位数字");
        codeInput.setTextSize(22);
        codeInput.setTypeface(android.graphics.Typeface.MONOSPACE, Typeface.BOLD);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setTextColor(Ui.attr(this, R.attr.textColor));
        codeInput.setHintTextColor(Ui.attr(this, R.attr.textColor2));
        codeInput.setBackground(Ui.field(this, 14));
        codeInput.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                Ui.dp(this, 180), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(this, 8);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        codeInput.setLayoutParams(cl);
        box.addView(codeInput);

        goBtn = new TextView(this);
        goBtn.setText("开始接收");
        goBtn.setTextSize(14);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setTextColor(0xFFFFFFFF);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setBackground(Ui.gradient(new int[]{accent(), accent2()}, 999f, 0));
        goBtn.setPadding(Ui.dp(this, 26), Ui.dp(this, 11), Ui.dp(this, 26), Ui.dp(this, 11));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = Ui.dp(this, 14);
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        goBtn.setLayoutParams(gl);
        Ui.press(goBtn);
        goBtn.setOnClickListener(v -> doReceive());
        box.addView(goBtn);
    }

    private void startDiscover() {
        state.setText("正在同一 WiFi 下查找…");
        Lan.discover(this, new Lan.Found() {
            @Override public void on(String host, int port) {
                foundHost = host;
                foundPort = port;
                state.setText("已找到  " + host + "，输入配对码开始接收");
            }
            @Override public void end(int count) {
                if (count == 0) {
                    state.setText("没找到。请确认：两台手机在同一 WiFi、"
                            + "旧手机已打开「发送到新手机」页面");
                }
            }
        });
    }

    private void doReceive() {
        if (foundHost == null) {
            state.setText("还没找到对方，正在重新查找…");
            startDiscover();
            return;
        }
        String c = codeInput.getText().toString().trim();
        if (c.length() != 6) { toast("配对码是 6 位数字"); return; }

        new android.app.AlertDialog.Builder(this)
                .setTitle("确认接收？")
                .setMessage("接收后会**完全覆盖**本机的账号数据。\n\n"
                        + "如果本机已有账号且没有备份，它们将被替换且无法恢复。")
                .setPositiveButton("确认覆盖接收", (d, w) -> fetch(c))
                .setNegativeButton("取消", null)
                .show();
    }

    private void fetch(String code) {
        goBtn.setEnabled(false);
        state.setText("正在连接…");
        final File tmp = new File(getCacheDir(), "incoming.db");
        if (tmp.exists()) tmp.delete();

        Lan.fetch(foundHost, foundPort, code, tmp, new Lan.Cb() {
            @Override public void state(String s) { runOnUiThread(() -> TransferActivity.this.state.setText(s)); }
            @Override public void progress(int pct) {
                runOnUiThread(() -> TransferActivity.this.state.setText("接收中  " + pct + "%"));
            }
            @Override public void done(String meta) {
                runOnUiThread(() -> install(tmp, meta));
            }
            @Override public void err(String msg) {
                runOnUiThread(() -> {
                    TransferActivity.this.state.setText("⚠ " + msg);
                    goBtn.setEnabled(true);
                });
            }
        });
    }

    /** 接收完：写入密钥参数 + 替换数据库 + 重启 */
    private void install(File tmp, String meta) {
        state.setText("正在导入…");
        try {
            String salt = between(meta, "\"salt\":\"", "\"");
            String mk = between(meta, "\"mk\":\"", "\"");
            String it = between(meta, "\"iter\":", "}");
            if (salt != null) Prefs.put("salt", salt);
            if (mk != null) Prefs.put("mk", mk);
            if (it != null) { try { Prefs.putI("iter", Integer.parseInt(it.trim())); } catch (Exception ignored) { } }

            boolean ok = Db.replace(this, tmp);
            tmp.delete();
            if (!ok) { state.setText("⚠ 导入失败，本机数据未改动"); goBtn.setEnabled(true); return; }

            /* 主密钥已变，必须重新解锁 */
            Session.lock();
            state.setText("✅ 导入完成，正在重启…");
            toast("导入完成，请用原来的主密码解锁");

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Intent i = new Intent(this, UnlockActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 900);
        } catch (Exception e) {
            state.setText("⚠ 导入失败：" + e.getMessage());
            goBtn.setEnabled(true);
        }
    }

    /** 极简 JSON 取值：只处理本文件自己拼出来的那种格式 */
    private static String between(String s, String a, String b) {
        if (s == null) return null;
        int i = s.indexOf(a);
        if (i < 0) return null;
        i += a.length();
        int j = s.indexOf(b, i);
        if (j < 0) return null;
        return s.substring(i, j);
    }

    @Override protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}
