package com.vaultkey.util;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP，零依赖实现 */
public final class Totp {
    public static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s-]", "").toUpperCase();
    }

    public static String code(String secret, long timeMs) {
        try {
            byte[] key = decodeBase32(normalize(secret));
            long step = timeMs / 1000 / 30;
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (step & 0xff); step >>>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int otp = bin % 1000000;
            return String.format("%06d", otp);
        } catch (Exception e) { return "------"; }
    }

    public static int remain() {
        return (int) (30 - (System.currentTimeMillis() / 1000) % 30);
    }

    private static byte[] decodeBase32(String s) {
        String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder bits = new StringBuilder();
        for (char c : s.toCharArray()) {
            int v = A.indexOf(c);
            if (v < 0) continue;
            String b = Integer.toBinaryString(v);
            while (b.length() < 5) b = "0" + b;
            bits.append(b);
        }
        int len = bits.length() / 8;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return out;
    }
}
