package com.vaultkey.crypto;

import android.app.Activity;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import javax.crypto.Cipher;

/**
 * 生物识别统一封装：指纹（强）与人脸（弱）。
 *
 * ── 一个绕不开的 Android 限制 ──
 *
 * CryptoObject（把密钥交出去解密）**只能配 Class 3 / BIOMETRIC_STRONG**。
 * 而人脸在绝大多数设备上是 Class 1 / BIOMETRIC_WEAK —— 系统禁止给它配密钥。
 * 这是 Android 平台的规定，不是本 App 的限制，所有人脸解锁的密码管理器都受此约束。
 *
 * 因此两种模式的安全级别不同：
 *
 *   指纹（STRONG）→ 带 CryptoObject → 密钥由硬件保管，认证通过才解密
 *   人脸（WEAK）  → 不带密钥       → 先验证是本人，再从 Keystore 取主密钥
 *
 * 人脸那一步少了「密钥必须由生物特征解锁」这一层，所以设置里会明确标注，
 * 让用户自己选。默认仍是指纹。
 *
 * ── 关于「适配各大品牌」 ──
 *
 * 走的是标准 BiometricPrompt，这是唯一可行路径。华为 / 小米 / OPPO / vivo
 * 近年的机型基本都已把人脸接入标准通道（WEAK），能直接用。
 * 各家厂商的私有人脸 SDK 都是闭源的、需要申请权限和应用签名，开源项目接不了。
 */
public final class Biometric {

    public interface Cb { void ok(Cipher c); void fail(String msg); }

    /** 只要结果、不要密钥（人脸用） */
    public interface SimpleCb { void ok(); void fail(String msg); }

    public static final int NONE = 0, STRONG = 1, WEAK = 2;

    /* ---------------- 能力检测 ---------------- */

    /** 设备有没有 Class 3 生物特征（通常是指纹）。API 30+ 才问得准 */
    public static boolean canStrong(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricManager bm =
                        (android.hardware.biometrics.BiometricManager)
                                c.getSystemService(Context.BIOMETRIC_SERVICE);
                if (bm == null) return false;
                int r = bm.canAuthenticate(android.hardware.biometrics.BiometricManager
                        .Authenticators.BIOMETRIC_STRONG);
                return r == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Throwable ignored) { }
        }
        try {
            FingerprintManager fm = (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) { return false; }
    }

    /**
     * 设备有没有可用的弱生物特征（人脸）。
     * 注意：只问「有没有录入」，不问「是不是人脸」——系统不透露具体类型，
     * 所以提示文案统一写「人脸 / 生物识别」，不写死。
     */
    public static boolean canWeak(Context c) {
        if (Build.VERSION.SDK_INT < 30) return false;   // WEAK 常量 API 30 才有
        try {
            android.hardware.biometrics.BiometricManager bm =
                    (android.hardware.biometrics.BiometricManager)
                            c.getSystemService(Context.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            int r = bm.canAuthenticate(android.hardware.biometrics.BiometricManager
                    .Authenticators.BIOMETRIC_WEAK);
            return r == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) { return false; }
    }

    /** 设备当前可用的最高级别 */
    public static int level(Context c) {
        if (canStrong(c)) return STRONG;
        if (canWeak(c)) return WEAK;
        return NONE;
    }

    public static String levelText(Context c) {
        switch (level(c)) {
            case STRONG: return "指纹";
            case WEAK:   return "人脸";
            default:     return "不支持";
        }
    }

    /* ---------------- 强认证（指纹，可带密钥） ---------------- */

    public static void auth(Activity a, Cipher cipher, Cb cb) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle("指纹解锁密盒")
                                .setSubtitle("验证已录入的指纹")
                                .setNegativeButton("用主密码", a.getMainExecutor(), (d, w) -> cb.fail("cancel"));
                /* 显式限定 STRONG：避免有些设备混进弱认证后拿不到 CryptoObject */
                b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager
                        .Authenticators.BIOMETRIC_STRONG);
                android.hardware.biometrics.BiometricPrompt p = b.build();
                p.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),
                        new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                                Cipher c = null;
                                try { c = r.getCryptoObject().getCipher(); } catch (Exception ignored) { }
                                cb.ok(c);
                            }
                            @Override public void onAuthenticationError(int code, CharSequence msg) {
                                cb.fail(msg == null ? "错误" : msg.toString());
                            }
                            @Override public void onAuthenticationFailed() { }
                        });
                return;
            } catch (Throwable ignored) { }
        }
        try {
            FingerprintManager fm = (FingerprintManager) a.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null || !fm.isHardwareDetected() || !fm.hasEnrolledFingerprints()) {
                cb.fail("no_hw"); return;
            }
            CancellationSignal cs = new CancellationSignal();
            fm.authenticate(new FingerprintManager.CryptoObject(cipher), cs, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult r) {
                            Cipher c = null;
                            try { c = r.getCryptoObject().getCipher(); } catch (Exception ignored) { }
                            cb.ok(c);
                        }
                        @Override public void onAuthenticationError(int code, CharSequence msg) {
                            cb.fail(msg == null ? "错误" : msg.toString());
                        }
                    }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) { cb.fail("unavailable"); }
    }

    /* ---------------- 弱认证（人脸，不带密钥） ---------------- */

    /**
     * 人脸 / 弱生物认证。
     * 不带 CryptoObject —— 系统不允许给弱认证配密钥，硬配会直接抛异常。
     * 认证通过后由调用方自行取主密钥。
     */
    public static void authWeak(Activity a, SimpleCb cb) {
        if (Build.VERSION.SDK_INT < 30) { cb.fail("系统版本过低（需 Android 11+）"); return; }
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle("人脸解锁密盒")
                            .setSubtitle("看一眼即可解锁")
                            .setNegativeButton("用主密码", a.getMainExecutor(), (d, w) -> cb.fail("cancel"));
            /* WEAK 才会带上人脸；只允许 WEAK 而排除 STRONG，
               否则已录指纹的设备会优先弹指纹框。 */
            b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager
                    .Authenticators.BIOMETRIC_WEAK);
            android.hardware.biometrics.BiometricPrompt p = b.build();
            p.authenticate(new CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            cb.ok();
                        }
                        @Override public void onAuthenticationError(int code, CharSequence msg) {
                            cb.fail(msg == null ? "错误" : msg.toString());
                        }
                        @Override public void onAuthenticationFailed() { }
                    });
        } catch (Throwable t) {
            cb.fail("人脸不可用");
        }
    }
}
