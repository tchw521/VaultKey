package com.vaultkey.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 泄露库比对（HaveIBeenPwned）。
 *
 * ── 为什么可以放心用 ──
 *
 * 走的是 k-匿名方案：只把密码 SHA-1 的**前 5 位**发给服务器，
 * 服务器返回所有以这 5 位开头的哈希后缀，比对在**本机**完成。
 *
 *   发出的：  5 个字符（比如 "5BAA6"）
 *   收到的：  约 500~1000 条哈希后缀（无法反推原始密码）
 *   不外传：  完整哈希、密码原文、账号名 —— 一个都不发
 *
 * 就算请求被中间人看到，也只能知道"某个密码的前 5 位哈希"，
 * 无法还原密码。这是目前业界通行做法。
 *
 * ── 失败处理 ──
 * 网络不通 / 超时就返回 null，调用方按"本次没查"处理，
 * 不把"查不了"当成"没泄露" —— 这两个结论完全不同，不能混。
 */
public final class Hibp {

    private static final String API = "https://api.pwnedpasswords.com/range/";
    private static final int TIMEOUT = 12000;

    /** 一次查询的结果 */
    public static final class Result {
        /** 确认已泄露的密码原文集合 */
        public final Set<String> breached = new HashSet<>();
        /** null=查询成功；非 null=失败原因 */
        public final String error;
        /** 本次实际检查了多少条 */
        public final int checked;

        Result(String error, int checked) { this.error = error; this.checked = checked; }
    }

    private Hibp() { }

    /**
     * 批量检查。每个密码一次网络请求，量大会慢，
     * 所以只查去重后的密码（同一密码查一次就够）。
     *
     * @param passwords 待查的密码原文
     * @return 结果；error 非 null 表示查询失败，不要据此判断"没泄露"
     */
    public static Result check(List<String> passwords) {
        /* 去重 */
        List<String> uniq = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : passwords) {
            if (p == null || p.isEmpty()) continue;
            if (seen.add(p)) uniq.add(p);
        }
        if (uniq.isEmpty()) return new Result(null, 0);

        /* 先算好全部哈希，网络失败时不浪费已算的结果 */
        List<String> full = new ArrayList<>(uniq.size());
        for (String p : uniq) {
            String h = sha1(p);
            if (h == null) return new Result("哈希计算失败", 0);
            full.add(h);
        }

        Set<String> bad = new HashSet<>();
        String err = null;

        for (int i = 0; i < uniq.size(); i++) {
            String h = full.get(i);
            String prefix = h.substring(0, 5);
            String suffix = h.substring(5).toUpperCase();

            try {
                List<String> tails = query(prefix);
                if (tails == null) { err = "网络请求失败"; break; }
                for (String t : tails) {
                    /* 返回形如 "1E4C9...:1234"（后缀:出现次数），只取冒号前 */
                    int c = t.indexOf(':');
                    String tail = (c > 0 ? t.substring(0, c) : t).trim().toUpperCase();
                    if (tail.equals(suffix)) { bad.add(uniq.get(i)); break; }
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                break;
            }
        }

        return new Result(err, uniq.size());
    }

    /** 取某前缀下的全部后缀 */
    private static List<String> query(String prefix) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API + prefix).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "VaultKey-Android");
            c.setRequestProperty("Add-Padding", "true");   // 加噪声，防通过响应大小推断
            int code = c.getResponseCode();
            if (code != 200) return null;

            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();

            List<String> out = new ArrayList<>();
            for (String line : sb.toString().split("\n")) {
                if (!line.trim().isEmpty()) out.add(line);
            }
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
