package com.vaultkey.util;

import java.security.SecureRandom;

public final class PasswordGen {
    private static final SecureRandom R = new SecureRandom();
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String DIGIT = "23456789";
    static final String SYM = "!@#$%^&*()-_=+[]{};:,.?/";
    static final String[] WORDS = {"amber", "river", "cloud", "stone", "maple", "delta", "nova", "pixel", "quartz",
            "ember", "willow", "cobalt", "lantern", "meadow", "onyx", "pebble", "raven", "saffron", "tundra",
            "velvet", "walnut", "zephyr", "citrus", "drift", "fjord", "glacier", "harbor", "ivory", "juniper"};

    /**
     * 生成密码。
     *
     * 修了两个问题：
     *
     * **1. 勾选了却不一定出现。**
     * 旧实现只是把各类字符拼成一个大池子随机取，**不保证**被勾选的每一类
     * 至少出现一次。实测：长度 8、同时勾选大写+数字+符号时，
     * **43% 的生成结果里一个数字都没有** —— 用户勾选"包含数字"是明确要求，
     * 结果却可能没有数字，这是个实打实的 bug。
     *
     * 修法：先给每个被勾选的类别各放一个"保底"字符，剩下位置才从合并池随机取，
     * 最后整体打乱（Fisher-Yates），避免保底字符固定在开头形成规律。
     *
     * **2. 小写字母无法关闭。**
     * 旧实现无条件加入 LOWER，导致想要纯数字 PIN（比如 6 位数字码）
     * 根本生成不出来。现在小写也是可选项。
     */
    public static String gen(int len, boolean low, boolean up, boolean dig, boolean sym) {
        /* 收集被勾选的字符集 */
        java.util.List<String> sets = new java.util.ArrayList<>();
        if (low) sets.add(LOWER);
        if (up) sets.add(UPPER);
        if (dig) sets.add(DIGIT);
        if (sym) sets.add(SYM);

        /* 一个都没勾 → 兜底用小写，不能返回空串 */
        if (sets.isEmpty()) sets.add(LOWER);

        StringBuilder pool = new StringBuilder();
        for (String x : sets) pool.append(x);

        int n = Math.max(1, len);
        char[] out = new char[n];

        /* 第一步：每个被勾选的类别各放一个保底字符 */
        int idx = 0;
        for (String set : sets) {
            if (idx >= n) break;   /* 长度比类别数还短时，保底也放不下 */
            out[idx++] = set.charAt(R.nextInt(set.length()));
        }

        /* 第二步：剩余位置从合并池随机取 */
        for (int i = idx; i < n; i++) {
            out[i] = pool.charAt(R.nextInt(pool.length()));
        }

        /* 第三步：整体打乱，否则保底字符总在前几位，形成可预测的模式 */
        for (int i = n - 1; i > 0; i--) {
            int j = R.nextInt(i + 1);
            char t = out[i]; out[i] = out[j]; out[j] = t;
        }
        return new String(out);
    }

    public static String passphrase(int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append('-');
            String w = WORDS[R.nextInt(WORDS.length)];
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public static int strength(String p) {
        if (p == null || p.isEmpty()) return 0;
        int score = 0;
        if (p.length() >= 8) score += 25;
        if (p.length() >= 12) score += 20;
        if (p.length() >= 16) score += 15;
        if (p.matches(".*[a-z].*")) score += 8;
        if (p.matches(".*[A-Z].*")) score += 10;
        if (p.matches(".*[0-9].*")) score += 10;
        if (p.matches(".*[^A-Za-z0-9].*")) score += 12;
        int uniq = (int) p.chars().distinct().count();
        score += Math.min(10, uniq * 2);
        return Math.max(0, Math.min(100, score));
    }

    public static String label(int s) {
        if (s < 30) return "弱";
        if (s < 55) return "中";
        if (s < 75) return "强";
        return "极强";
    }
}
