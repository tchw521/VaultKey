package com.vaultkey.crypto;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
    public static final int ITER = 180000;
    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    public static byte[] masterKey() { return random(32); }

    public static SecretKey derive(char[] pwd, byte[] salt) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec s = new javax.crypto.spec.PBEKeySpec(pwd, salt, ITER, 256);
            byte[] k = f.generateSecret(s).getEncoded();
            s.clearPassword();
            return new SecretKeySpec(k, "AES");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 输出 nonce(12) + ciphertext */
    public static byte[] encrypt(byte[] key, byte[] plain) {
        try {
            byte[] nonce = random(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] decrypt(byte[] key, byte[] blob) {
        try {
            if (blob == null || blob.length < 13) return null;
            byte[] nonce = new byte[12];
            System.arraycopy(blob, 0, nonce, 0, 12);
            byte[] ct = new byte[blob.length - 12];
            System.arraycopy(blob, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c.doFinal(ct);
        } catch (Exception e) { return null; }
    }

    public static String encStr(byte[] key, String s) {
        if (s == null) return null;
        byte[] b = Crypto.encrypt(key, s.getBytes(StandardCharsets.UTF_8));
        return b == null ? null : Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static String decStr(byte[] key, String s) {
        if (s == null || s.isEmpty()) return "";
        byte[] b = Base64.decode(s, Base64.NO_WRAP);
        byte[] p = Crypto.decrypt(key, b);
        return p == null ? "" : new String(p, StandardCharsets.UTF_8);
    }

    public static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    public static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
