package com.vaultkey.crypto;

import android.app.Activity;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import javax.crypto.Cipher;

/** 生物识别统一封装：Android 11+ 用 BiometricPrompt，9/10 用 FingerprintManager，均支持 Crypto 密钥 */
public final class Biometric {
    public interface Cb { void ok(Cipher c); void fail(String msg); }

    public static void auth(Activity a, Cipher cipher, Cb cb) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle("指纹解锁密盒")
                                .setSubtitle("验证已录入的指纹")
                                .setNegativeButton("用主密码", a.getMainExecutor(), (d, w) -> cb.fail("cancel"));
                android.hardware.biometrics.BiometricPrompt p = b.build();
                p.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),
                        new android.os.CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                                Cipher c = null;
                                try { c = r.getCryptoObject().getCipher(); } catch (Exception ignored) { }
                                cb.ok(c);
                            }
                            @Override public void onAuthenticationError(int code, CharSequence msg) { cb.fail(msg == null ? "错误" : msg.toString()); }
                            @Override public void onAuthenticationFailed() { }
                        });
                return;
            } catch (Throwable ignored) { }
        }
        try {
            FingerprintManager fm = (FingerprintManager) a.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null || !fm.isHardwareDetected() || !fm.hasEnrolledFingerprints()) { cb.fail("no_hw"); return; }
            CancellationSignal cs = new CancellationSignal();
            fm.authenticate(new FingerprintManager.CryptoObject(cipher), cs, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult r) {
                            Cipher c = null;
                            try { c = r.getCryptoObject().getCipher(); } catch (Exception ignored) { }
                            cb.ok(c);
                        }
                        @Override public void onAuthenticationError(int code, CharSequence msg) { cb.fail(msg == null ? "错误" : msg.toString()); }
                    }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) { cb.fail("unavailable"); }
    }
}
