package com.vaultkey.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 用系统 Keystore 中「需生物识别才可用」的密钥保管主密钥副本 */
public final class KeystoreHelper {
    private static final String ALIAS = "vaultkey_bio_key";
    private static final String ALIAS_FACE = "vaultkey_face_key";
    private static final String KS = "AndroidKeyStore";

    /*
     * 两个密钥槽：
     *
     *   vaultkey_bio_key  —— 指纹用。setUserAuthenticationRequired(true)，
     *                        密钥必须由生物特征认证后才可用，安全性最高。
     *
     *   vaultkey_face_key —— 人脸用。不能设 userAuthenticationRequired，
     *                        因为弱生物特征拿不到 CryptoObject（见 Biometric 的说明）。
     *                        它仍然是 Keystore 硬件密钥，只是少了「必须认证」这层；
     *                        「确认是本人」这一步由人脸认证补上。
     */
    private static SecretKey key() { return key(ALIAS, true); }

    private static SecretKey key(String alias, boolean needAuth) {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (!ks.containsAlias(alias)) {
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
                KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256);
                if (needAuth) {
                    b.setUserAuthenticationRequired(true)
                     .setInvalidatedByBiometricEnrollment(false);
                }
                kg.init(b.build());
                kg.generateKey();
            }
            return (SecretKey) ks.getKey(alias, null);
        } catch (Exception e) { return null; }
    }

    public static Cipher encCipher() {
        try {
            SecretKey sk = key();
            if (sk == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sk);
            return c;
        } catch (Exception e) { return null; }
    }

    public static Cipher decCipher(Context ctx) {
        try {
            SecretKey sk = key();
            if (sk == null) return null;
            String iv = ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).getString("bio_iv", null);
            if (iv == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(128, Crypto.unb64(iv)));
            return c;
        } catch (Exception e) { return null; }
    }

    public static boolean saveBio(Context ctx, Cipher enc, byte[] masterKey) {
        try {
            byte[] out = enc.doFinal(masterKey);
            SharedPreferences p = ctx.getSharedPreferences("vk", Context.MODE_PRIVATE);
            p.edit().putString("bio_mk", Crypto.b64(out)).putString("bio_iv", Crypto.b64(enc.getIV())).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    public static byte[] loadBio(Context ctx, Cipher dec) {
        try {
            String s = ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).getString("bio_mk", null);
            if (s == null || dec == null) return null;
            return dec.doFinal(Crypto.unb64(s));
        } catch (Exception e) { return null; }
    }

    public static boolean hasBio(Context ctx) {
        return ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).contains("bio_mk");
    }

    public static void clearBio(Context ctx) {
        ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).edit().remove("bio_mk").remove("bio_iv").apply();
        drop(ALIAS);
    }

    /* ---------------- 人脸（弱生物） ---------------- */

    /** 人脸模式下保存主密钥副本。调用前必须先通过人脸认证 */
    public static boolean saveFace(Context ctx, byte[] masterKey) {
        try {
            SecretKey sk = key(ALIAS_FACE, false);
            if (sk == null) return false;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sk);
            byte[] out = c.doFinal(masterKey);
            ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).edit()
                    .putString("face_mk", Crypto.b64(out))
                    .putString("face_iv", Crypto.b64(c.getIV()))
                    .apply();
            return true;
        } catch (Exception e) { return false; }
    }

    /** 人脸认证通过后取回主密钥 */
    public static byte[] loadFace(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("vk", Context.MODE_PRIVATE);
            String s = p.getString("face_mk", null);
            String iv = p.getString("face_iv", null);
            if (s == null || iv == null) return null;
            SecretKey sk = key(ALIAS_FACE, false);
            if (sk == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(128, Crypto.unb64(iv)));
            return c.doFinal(Crypto.unb64(s));
        } catch (Exception e) { return null; }
    }

    public static boolean hasFace(Context ctx) {
        return ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).contains("face_mk");
    }

    public static void clearFace(Context ctx) {
        ctx.getSharedPreferences("vk", Context.MODE_PRIVATE).edit()
                .remove("face_mk").remove("face_iv").apply();
        drop(ALIAS_FACE);
    }

    private static void drop(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Exception ignored) { }
    }
}
