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

    /* ---------------- otpauth:// 支持 ---------------- */

    /**
     * 从 otpauth:// 链接里取出密钥。
     *
     * 标准格式：otpauth://totp/服务商:账号?secret=XXXX&issuer=服务商
     *
     * 为什么做这个而不是扫码：扫码需要相机权限 + 二维码解码器，
     * 本项目零依赖，自己实现 QR 解码不现实（要写 finder pattern 定位、
     * Reed-Solomon 纠错、位流解析，上千行且难以验证）。
     *
     * 而多数网站在显示二维码的旁边就提供「无法扫码？点此查看密钥」
     * 或直接给出 otpauth 链接。粘进来即可，比手抄密钥省事得多。
     *
     * @return 密钥原文；不是 otpauth 链接或解析不出 secret 就返回 null
     */
    public static String secretFromUri(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.toLowerCase().startsWith("otpauth://")) return null;

        int q = t.indexOf('?');
        if (q < 0) return null;
        String query = t.substring(q + 1);

        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if ("secret".equalsIgnoreCase(k) && !v.isEmpty()) {
                return normalize(decodeUrl(v));
            }
        }
        return null;
    }

    /** 从 otpauth 链接里取服务商名，用于自动填标题 */
    public static String issuerFromUri(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.toLowerCase().startsWith("otpauth://")) return null;

        /* 优先取 issuer 参数 */
        int q = t.indexOf('?');
        if (q >= 0) {
            for (String part : t.substring(q + 1).split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                if ("issuer".equalsIgnoreCase(part.substring(0, eq).trim())) {
                    String v = decodeUrl(part.substring(eq + 1).trim());
                    if (!v.isEmpty()) return v;
                }
            }
        }
        /* 退而求其次：路径里 "服务商:账号" 的前半段 */
        int scheme = t.indexOf("otpauth://");
        int slash = t.indexOf('/', scheme + 10);
        if (slash >= 0) {
            String label = t.substring(slash + 1);
            int amp = label.indexOf('?');
            if (amp > 0) label = label.substring(0, amp);
            int colon = label.indexOf(':');
            if (colon > 0) return decodeUrl(label.substring(0, colon));
            if (!label.isEmpty()) return decodeUrl(label);
        }
        return null;
    }

    /** 粘贴进来的可能是 URL 编码过的（空格变 %20 等） */
    private static String decodeUrl(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 判断一段文本是不是 otpauth 链接 */
    public static boolean isUri(String s) {
        return s != null && s.trim().toLowerCase().startsWith("otpauth://");
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
