package com.vaultkey.crypto;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import javax.crypto.Cipher;

/**
 * 生物识别统一封装：指纹（强）与人脸（弱）。
 *
 * ── 一个绕不开的 Android 限制 ──
 *
 * CryptoObject（把密钥交出去解密）只能配 Class 3 / BIOMETRIC_STRONG。
 * 而人脸在多数设备上是 Class 1 / BIOMETRIC_WEAK —— 系统禁止给它配密钥。
 * 这是平台规定，不是本 App 的限制。
 *
 *   指纹（STRONG）→ 带 CryptoObject → 密钥由硬件保管，认证通过才解密
 *   人脸（WEAK）  → 不带密钥       → 先验证是本人，再从 Keystore 取主密钥
 *
 * ── 为什么人脸要「多档回退」 ──
 *
 * 各家 ROM 对 BiometricPrompt 的实现差异极大：
 *   · 有的把人脸注册为 WEAK，有的注册为 STRONG（3D 结构光）
 *   · 有的单独传 WEAK 会直接报 ERROR_NO_BIOMETRICS
 *   · 有的必须带上 DEVICE_CREDENTIAL 才肯弹人脸框
 *
 * 所以不能只试一种组合。authWeak 会依次尝试多档，上一档失败自动降级，
 * 全程记录错误码，最后一次性展示 —— 而不是让用户猜。
 */
public final class Biometric {

    public interface Cb { void ok(Cipher c); void fail(String msg); }

    /** 只要结果、不要密钥（人脸用） */
    public interface SimpleCb { void ok(); void fail(String msg); }

    public static final int NONE = 0, STRONG = 1, WEAK = 2;

    private static final int A_STRONG = 15;    // 0x0F
    private static final int A_WEAK   = 255;   // 0xFF
    private static final int A_CRED   = 32768; // 0x8000

    /* ---------------- 能力检测 ---------------- */

    public static boolean canStrong(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricManager bm = bm(c);
                if (bm != null)
                    return bm.canAuthenticate(A_STRONG) == 0;
            } catch (Throwable ignored) { }
        }
        try {
            FingerprintManager fm = (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) { return false; }
    }

    /**
     * ⚠️ WEAK(0xFF) 的位完全包含 STRONG(0x0F)，所以这个查询在有指纹的设备上
     * 也返回 true —— 它问的是「有没有任何生物特征」，不是「有没有人脸」。
     * Android 不提供按类型筛选的 API。
     */
    public static boolean canWeak(Context c) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            android.hardware.biometrics.BiometricManager bm = bm(c);
            return bm != null && bm.canAuthenticate(A_WEAK) == 0;
        } catch (Throwable t) { return false; }
    }

    public static int level(Context c) {
        if (canStrong(c)) return STRONG;
        if (canWeak(c)) return WEAK;
        return NONE;
    }

    private static android.hardware.biometrics.BiometricManager bm(Context c) {
        return (android.hardware.biometrics.BiometricManager)
                c.getSystemService(Context.BIOMETRIC_SERVICE);
    }

    /* ---------------- 强认证（指纹，带密钥） ---------------- */

    public static void auth(Activity a, Cipher cipher, Cb cb) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle("指纹解锁密盒")
                                .setSubtitle("验证已录入的指纹")
                                .setNegativeButton("用主密码", a.getMainExecutor(), (d, w) -> cb.fail("cancel"));
                b.setAllowedAuthenticators(A_STRONG);
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
            fm.authenticate(new FingerprintManager.CryptoObject(cipher), new CancellationSignal(), 0,
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

    /* ---------------- 人脸（多档回退） ---------------- */

    /**
     * 人脸解锁。依次尝试多档 authenticator 组合，上一档失败自动降级。
     * 不带 CryptoObject —— 弱生物特征配不了密钥，硬配会抛异常。
     */
    public static void authWeak(Activity a, SimpleCb cb) {
        if (Build.VERSION.SDK_INT < 30) { cb.fail("系统版本过低（需 Android 11+）"); return; }
        new Chain(a, cb).next(0);
    }

    /** 回退链：每档失败后自动试下一档，全程记录原因 */
    private static final class Chain {
        private final Activity a;
        private final SimpleCb cb;
        private final StringBuilder log = new StringBuilder();

        Chain(Activity a, SimpleCb cb) { this.a = a; this.cb = cb; }

        /** 各档的 authenticator 组合与说明 */
        private static final int[] COMBOS = {
                A_WEAK,                 // ① 标准弱生物：多数机型的人脸走这里
                A_WEAK | A_CRED,        // ② 部分 ROM 需带凭据兜底才肯弹
                A_STRONG,               // ③ 人脸被注册为强生物（3D 结构光）
                A_STRONG | A_CRED,      // ④ 最后一档：凭据兜底
        };
        private static final String[] NAMES = {
                "弱生物", "弱生物+密码", "强生物", "强生物+密码"
        };

        void next(final int idx) {
            if (idx >= COMBOS.length) {
                cb.fail(log.length() == 0 ? "全部方式均不可用" : log.toString());
                return;
            }
            final int combo = COMBOS[idx];
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle("人脸解锁密盒")
                                .setSubtitle("看一眼即可解锁")
                                .setNegativeButton("用主密码", a.getMainExecutor(),
                                        (d, w) -> cb.fail("cancel"));
                b.setAllowedAuthenticators(combo);
                android.hardware.biometrics.BiometricPrompt p = b.build();
                p.authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                                cb.ok();
                            }
                            @Override public void onAuthenticationError(int code, CharSequence msg) {
                                String m = msg == null ? "" : msg.toString();
                                /* 用户主动取消就别再往下试了 */
                                if (code == 5 || code == 10 || code == 13) { cb.fail("cancel"); return; }
                                if (log.length() > 0) log.append("\n");
                                log.append(NAMES[idx]).append("(").append(code).append("): ").append(m);
                                next(idx + 1);
                            }
                            @Override public void onAuthenticationFailed() {
                                /* 认不出来：不算系统错误，让用户再试，不降级 */
                            }
                        });
            } catch (Throwable t) {
                if (log.length() > 0) log.append("\n");
                log.append(NAMES[idx]).append(": ").append(t.getClass().getSimpleName());
                next(idx + 1);
            }
        }
    }

    /* ---------------- 系统设置入口 ---------------- */

    /** 跳系统的「指纹、面部与密码」页，让用户确认人脸已录入 */
    public static void openEnroll(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_BIOMETRIC_ENROLL));
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
            catch (Exception ignored) { }
        }
    }

    /* ---------------- 诊断 ---------------- */

    private static String codeText(int r) {
        switch (r) {
            case 0:  return "可用";
            case 1:  return "无生物识别硬件";
            case 11: return "未录入任何生物特征";
            case 12: return "暂不可用（稍后再试）";
            case 15: return "需要系统安全更新";
            default: return "未知状态（" + r + "）";
        }
    }

    /** 设置页自检：把设备到底支持什么一次列清楚 */
    public static String diag(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("Android ").append(Build.VERSION.RELEASE)
         .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
         .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricManager m = bm(c);
                if (m != null) {
                    int[] combos = {A_WEAK, A_STRONG, A_WEAK | A_CRED, A_STRONG | A_CRED};
                    String[] names = {"弱生物(WEAK)", "强生物(STRONG)",
                                      "弱生物+密码", "强生物+密码"};
                    for (int i = 0; i < combos.length; i++) {
                        s.append(names[i]).append(": ")
                         .append(codeText(m.canAuthenticate(combos[i]))).append("\n");
                    }
                }
            } catch (Throwable t) {
                s.append("查询失败: ").append(t.getMessage()).append("\n");
            }
        } else {
            s.append("API < 30，弱生物通道不可用\n");
        }
        s.append("\n本机已保存副本：\n")
         .append("· 指纹: ").append(KeystoreHelper.hasBio(c) ? "有" : "无").append("\n")
         .append("· 人脸: ").append(KeystoreHelper.hasFace(c) ? "有" : "无").append("\n");
        return s.toString();
    }
}
