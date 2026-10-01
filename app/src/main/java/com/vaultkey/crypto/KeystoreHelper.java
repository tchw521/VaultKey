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
    private static final String KS = "AndroidKeyStore";

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
                kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(true)
                        .setInvalidatedByBiometricEnrollment(false)
                        .build());
                kg.generateKey();
            }
            return (SecretKey) ks.getKey(ALIAS, null);
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
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }
}
