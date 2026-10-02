package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.util.Ui;
import com.vaultkey.crypto.Biometric;
import com.vaultkey.crypto.Crypto;
import com.vaultkey.crypto.KeystoreHelper;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

public final class UnlockActivity extends BaseActivity {
    private boolean setup;
    private boolean bioTried;
    private EditText pwd, pwd2;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setup = !Prefs.has("mk");
        build();
    }

    private void build() {
        body.removeAllViews();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int gap = Ui.dp(this, 12);
        body.setPadding(0, Ui.dp(this, 60), 0, 0);

        android.widget.ImageView big = new android.widget.ImageView(this);
        int bs = Ui.dp(this, 64);
        android.widget.LinearLayout.LayoutParams bsl = new LinearLayout.LayoutParams(bs, bs);
        bsl.gravity = Gravity.CENTER;
        bsl.bottomMargin = Ui.dp(this, 14);
        big.setLayoutParams(bsl);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Ui.withAlpha(Ui.accent(this), Ui.isDark(this) ? 60 : 34));
        View bgv = new View(this);
        android.widget.FrameLayout badge = new android.widget.FrameLayout(this);
        badge.setLayoutParams(bsl);
        bgv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(bs, bs));
        bgv.setBackground(bg);
        badge.addView(bgv);
        android.widget.ImageView bi = new android.widget.ImageView(this);
        bi.setImageDrawable(com.vaultkey.util.Ico.get(this, setup ? "shield" : "lock",
                Ui.accent(this), 32));
        android.widget.FrameLayout.LayoutParams bil = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER);
        bi.setLayoutParams(bil);
        badge.addView(bi);
        body.addView(badge);

        TextView logo = new TextView(this);
        logo.setText(setup ? "创建你的密盒" : "密盒");
        logo.setTextSize(26);
        logo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        logo.setTextColor(Ui.attr(this, R.attr.textColor));
        logo.setGravity(Gravity.CENTER);
        body.addView(logo);

        TextView sub = new TextView(this);
        sub.setText(setup ? "主密码是唯一解密密钥，务必牢记\n忘记主密码 = 数据永久丢失" : "输入主密码解锁");
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(Ui.attr(this, R.attr.textColor2));
        sub.setTextSize(13);
        sub.setPadding(0, gap, 0, Ui.dp(this, 28));
        body.addView(sub);

        pwd = field(setup ? "设置主密码（至少 6 位）" : "主密码", null);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        body.addView(pwd, lp);

        if (setup) {
            pwd2 = field("再输入一次", null);
            pwd2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2.topMargin = gap;
            body.addView(pwd2, l2);
        }

        android.widget.Button go = button(setup ? "创建并使用" : "解锁",
                new int[]{Ui.accent(this), Ui.accent2(this)});
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lb.topMargin = Ui.dp(this, 20);
        go.setOnClickListener(v -> { if (setup) doSetup(); else doUnlock(); });
        body.addView(go, lb);

        if (!setup && (KeystoreHelper.hasBio(this) || KeystoreHelper.hasFace(this))) {
            LinearLayout fp = new LinearLayout(this);
            fp.setOrientation(LinearLayout.HORIZONTAL);
            fp.setGravity(Gravity.CENTER);
            fp.setBackground(Ui.glass(this, 999, R.attr.cardColor, R.attr.strokeColor));
            int fpw = Ui.dp(this, 200);
            LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(fpw, Ui.dp(this, 46));
            lf.topMargin = Ui.dp(this, 16);
            lf.gravity = Gravity.CENTER;
            fp.setLayoutParams(lf);
            android.widget.ImageView fi = new android.widget.ImageView(this);
            fi.setImageDrawable(com.vaultkey.util.Ico.get(this,
                    KeystoreHelper.hasBio(this) ? "fingerprint" : "person",
                    Ui.accent(this), 22));
            fi.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            fp.addView(fi);
            TextView ft = new TextView(this);
            ft.setText(KeystoreHelper.hasBio(this) ? "用指纹解锁" : "用人脸解锁");
            ft.setTextSize(14);
            ft.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ft.setTextColor(Ui.accent(this));
            ft.setPadding(Ui.dp(this, 10), 0, 0, 0);
            fp.addView(ft);
            Ui.press(fp);
            fp.setOnClickListener(v -> {
                if (KeystoreHelper.hasBio(this)) bioUnlock(); else faceUnlock();
            });
            body.addView(fp);
        }

        if (setup) {
            TextView tip = new TextView(this);
            tip.setText("提示：建议 8 位以上并包含字母与数字");
            tip.setTextColor(Ui.attr(this, R.attr.textColor2));
            tip.setTextSize(12);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, Ui.dp(this, 18), 0, 0);
            body.addView(tip);
        } else {
            TextView reset = new TextView(this);
            reset.setText("忘记主密码？清除数据重建");
            reset.setTextColor(Ui.attr(this, R.attr.textColor2));
            reset.setTextSize(12);
            reset.setGravity(Gravity.CENTER);
            reset.setPadding(0, Ui.dp(this, 22), 0, 0);
            reset.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("清除本地数据？")
                    .setMessage("将删除本机所有密码数据，且无法恢复。若已开启坚果云备份，可在重新设置主密码后无法解密旧备份。")
                    .setPositiveButton("确认清除", (d, w) -> {
                        Prefs.remove("mk"); Prefs.remove("salt"); KeystoreHelper.clearBio(this);
                        deleteDatabase("vault.db");
                        Session.lock();
                        recreate();
                    })
                    .setNegativeButton("取消", null).show());
            body.addView(reset);
        }
    }

    private void doSetup() {
        final String p1 = pwd.getText().toString();
        String p2 = pwd2 == null ? "" : pwd2.getText().toString();
        if (p1.length() < 6) { toast("主密码至少 6 位"); return; }
        if (!p1.equals(p2)) { toast("两次输入不一致"); return; }
        final android.app.ProgressDialog pd = progress("正在生成密钥…");
        pd.show();
        pool.execute(() -> {
            byte[] salt = Crypto.random(16);
            SecretKey kek = Crypto.derive(p1.toCharArray(), salt);
            byte[] mk = Crypto.masterKey();
            byte[] wrapped = Crypto.encrypt(kek.getEncoded(), mk);
            Prefs.put("salt", Crypto.b64(salt));
            Prefs.put("mk", Crypto.b64(wrapped));
            Prefs.putI("iter", Crypto.ITER);
            Session.set(mk);
            runOnUiThread(() -> {
                safeDismiss(pd);
                askBio(mk);
            });
        });
    }

    private void askBio(byte[] mk) {
        Cipher c = KeystoreHelper.encCipher();
        if (c == null) { enter(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("启用指纹解锁？")
                .setMessage("下次可用指纹快速解锁密盒")
                .setPositiveButton("启用", (d, w) -> Biometric.auth(this, c, new Biometric.Cb() {
                    @Override public void ok(Cipher cc) {
                        if (cc != null) KeystoreHelper.saveBio(UnlockActivity.this, cc, mk);
                        toast("已启用指纹解锁");
                        enter();
                    }
                    @Override public void fail(String m) { toast("未启用：" + m); enter(); }
                }))
                .setNegativeButton("暂不", (d, w) -> enter())
                .show();
    }

    private void enter() {
        Session.touch();
        if (com.vaultkey.service.VaultAutofillService.AutofillSave.has()) {
            startActivity(new Intent(this, SaveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        /* 小部件 / 自动填充要求直达搜索：落一次性标记，MainActivity 回前台时消费 */
        Intent src = getIntent();
        boolean wantSearch = src != null
                && (src.getBooleanExtra("search", false)
                || com.vaultkey.widget.SearchWidget.ACTION_OPEN.equals(src.getAction())
                || isDeep("vaultkey://search"));
        boolean wantNew = src != null && isDeep("vaultkey://new");
        if (wantSearch) Prefs.putB("pending_search", true);
        if (wantNew) Prefs.putB("pending_new", true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** 自动填充验证分支：解锁后结束自己，系统会重新发起填充请求 */
    private void authDone() {
        if (com.vaultkey.service.VaultAutofillService.AutofillSave.has()) {
            startActivity(new Intent(this, SaveActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        setResult(RESULT_OK);
        finish();
    }

    private void doUnlock() {
        final String p = pwd.getText().toString();
        if (p.isEmpty()) { toast("请输入主密码"); return; }
        final android.app.ProgressDialog pd = progress("解密中…");
        pd.show();
        pool.execute(() -> {
            byte[] salt = Crypto.unb64(Prefs.get("salt", ""));
            SecretKey kek = Crypto.derive(p.toCharArray(), salt);
            byte[] mk = Crypto.decrypt(kek.getEncoded(), Crypto.unb64(Prefs.get("mk", "")));
            runOnUiThread(() -> {
                safeDismiss(pd);
                if (mk == null) { toast("主密码错误"); return; }
                Session.set(mk);
                if (getIntent().getBooleanExtra("auth", false)) { authDone(); return; }
                enter();
            });
        });
    }

    /** 人脸解锁：认证通过后直接取主密钥（弱生物特征拿不到 CryptoObject） */
    private void faceUnlock() {
        Biometric.authWeak(this, new Biometric.SimpleCb() {
            @Override public void ok() {
                byte[] mk = KeystoreHelper.loadFace(UnlockActivity.this);
                if (mk == null) { toast("人脸校验失败，请用主密码"); return; }
                Session.set(mk);
                if (getIntent().getBooleanExtra("auth", false)) { authDone(); return; }
                enter();
            }
            @Override public void fail(String m) {
                if ("cancel".equals(m)) return;
                /* 人脸没通过但装了指纹 —— 给一次指纹机会，别直接逼用户输主密码 */
                if (KeystoreHelper.hasBio(UnlockActivity.this)) {
                    body.post(UnlockActivity.this::bioUnlock);
                    return;
                }
                new android.app.AlertDialog.Builder(UnlockActivity.this)
                        .setTitle("人脸解锁失败")
                        .setMessage("系统返回：\n" + m + "\n\n"
                                + "可去系统设置确认人脸已录入，或改用主密码。")
                        .setPositiveButton("用主密码", null)
                        .setNeutralButton("系统设置",
                                (d, w) -> Biometric.openEnroll(UnlockActivity.this))
                        .show();
            }
        });
    }

    private void bioUnlock() {
        Cipher c = KeystoreHelper.decCipher(this);
        if (c == null) { toast("指纹不可用，请用主密码"); return; }
        Biometric.auth(this, c, new Biometric.Cb() {
            @Override public void ok(Cipher cc) {
                byte[] mk = KeystoreHelper.loadBio(UnlockActivity.this, cc);
                if (mk == null) { toast("指纹校验失败"); return; }
                Session.set(mk);
                if (getIntent().getBooleanExtra("auth", false)) { authDone(); return; }
                enter();
            }
            @Override public void fail(String m) { if (!"cancel".equals(m)) toast(m); }
        });
    }

    /** 判断 intent 是否携带指定的 deep link（长按快捷方式用） */
    private boolean isDeep(String uri) {
        Intent i = getIntent();
        if (i == null) return false;
        android.net.Uri d = i.getData();
        return d != null && uri.equals(d.toString());
    }

    @Override protected void onResume() {
        super.onResume();
        Session.touch();
        boolean auth = getIntent().getBooleanExtra("auth", false);
        if (!setup && Session.key() != null && !Session.expired(Prefs.getI("lock_ms", 180000)) && !auth) {
            enter();
            return;
        }
        /*
         * 启动即自动弹出生物识别（只自动弹一次，失败后改用手输主密码）。
         *
         * 优先指纹：它能带 CryptoObject，安全性更高。
         * 没开指纹但开了人脸时才走人脸 —— 反过来会让已有指纹的用户
         * 白白损失一层保护。
         */
        if (!setup && !auth && !bioTried && Session.key() == null) {
            boolean hasFp = KeystoreHelper.hasBio(this);
            boolean hasFace = KeystoreHelper.hasFace(this);
            int mode = Prefs.getI("unlock_mode", 0);   // 0 人脸优先 / 1 指纹优先 / 2 不自动
            if (mode == 2 || (!hasFp && !hasFace)) return;

            bioTried = true;
            body.post(() -> {
                /* 人脸优先：先试人脸，没开人脸再退回指纹 */
                if (mode == 0) { if (hasFace) faceUnlock(); else bioUnlock(); }
                else           { if (hasFp)   bioUnlock(); else faceUnlock(); }
            });
        }
    }

    @Override protected void onPause() {
        super.onPause();
        com.vaultkey.sync.Sync.maybeAuto(this);
        com.vaultkey.data.AutoBackup.maybeRun(this);
    }
}
