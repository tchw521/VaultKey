package com.vaultkey.util;

/**
 * 取首字母：英文取字母，中文按 GB2312 一级字库（按拼音排序）二分定位拼音首字母，
 * 数字与符号归入 '#'。不引入任何第三方库与字典文件。
 */
public final class Pinyin {
    /** GB2312 一级字库中各拼音首字母的起始汉字（A B C D E F G H J K L M N O P Q R S T W X Y Z） */
    private static final String BOUND = "啊芭擦搭蛾发噶哈击喀垃妈拿哦啪期然撒塌挖昔压匝";
    private static final String LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ";

    public static final String INDEX = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";

    /** 取用于排序的首字母，返回 'A'-'Z' 或 '#' */
    public static char first(String s) {
        if (s == null || s.isEmpty()) return '#';
        char c = s.charAt(0);
        if (c >= 'a' && c <= 'z') return (char) (c - ('a' - 'A'));
        if (c >= 'A' && c <= 'Z') return c;
        if (isCjk(c)) {
            char p = byGb(c);
            return p == 0 ? '#' : p;
        }
        return '#';
    }

    /** 排序键：# 排在最后 */
    public static int order(String s) {
        char c = first(s);
        return c == '#' ? 26 : (c - 'A');
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static char byGb(char c) {
        int code = gb(c);
        if (code < 0xB0A1 || code > 0xD7F9) return 0; // 不在一级字库内，无法按序定位
        for (int i = 0; i < BOUND.length(); i++) {
            if (code < gb(BOUND.charAt(i))) {
                return i == 0 ? 'A' : LETTERS.charAt(i - 1);
            }
        }
        return 'Z';
    }

    private static int gb(char c) {
        try {
            byte[] b = String.valueOf(c).getBytes("GB2312");
            if (b == null || b.length < 2) return -1;
            return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        } catch (Exception e) {
            return -1;
        }
    }
}
