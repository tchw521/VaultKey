package com.vaultkey.util;

import com.vaultkey.data.Db;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按域名聚合账号。
 *
 * 解决的真实痛点：登录某个网站时，**不知道自己当初用哪个邮箱 / 手机号注册的**。
 * 现在的做法是一个个点开条目比对，账号多了根本记不清。
 *
 * 聚合后：搜「百度」→ 百度系所有账号聚成一组，每组下列出每条分别绑的
 * 账号名（邮箱 / 手机号），一眼就能找到要用的那个。
 *
 * 关键点：**用主域名聚合而不是完整 host**。
 * pan.baidu.com 和 tieba.baidu.com 应该算同一家，
 * 否则同一个网站下分散着好几条，等于没聚合。
 */
public final class Domain {

    /**
     * 常见的双段后缀。
     *
     * 直接取「倒数两段」会把 example.com.cn 解析成 com.cn —— 那就把
     * 所有 .com.cn 的网站都归到一组了，完全错乱。必须识别双段后缀。
     */
    private static final String[] DOUBLE_SUFFIX = {
            "com.cn", "com.hk", "com.tw", "com.au", "com.br", "com.mx", "com.sg",
            "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn", "co.jp", "co.uk",
            "co.kr", "co.nz", "com.tr", "ne.jp", "or.jp", "or.kr", "org.uk",
            "me.uk", "ac.uk", "gov.uk", "net.au", "org.au", "com.ar"
    };

    /**
     * 取主域名（可注册域）。
     * pan.baidu.com → baidu.com
     * example.com.cn → example.com.cn
     * www.taobao.com → taobao.com
     */
    public static String regDomain(String urlOrHost) {
        if (urlOrHost == null) return "";
        String h = urlOrHost;
        int i = h.indexOf("://");
        if (i >= 0) h = h.substring(i + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int at = h.indexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        h = h.trim().toLowerCase();
        if (h.isEmpty()) return "";
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.indexOf('.') < 0) return "";   // 没有点，不是域名

        String[] a = h.split("\\.");
        if (a.length < 2) return "";

        /* 纯 IP（如 192.168.1.1）不是域名：最后一段是纯数字就判定为 IP。
           不排除的话 192.168.1.1 会被解析成 "1.1"，凭空造出一个假域名分组。 */
        String last = a[a.length - 1];
        boolean allDigit = true;
        for (int j = 0; j < last.length(); j++) {
            if (!Character.isDigit(last.charAt(j))) { allDigit = false; break; }
        }
        if (allDigit) return "";

        /* 最后两段是否是已知双段后缀？是则取三段 */
        String last2 = a[a.length - 2] + "." + a[a.length - 1];
        for (String s : DOUBLE_SUFFIX) {
            if (s.equals(last2) && a.length >= 3) {
                return a[a.length - 3] + "." + last2;
            }
        }
        return last2;
    }

    /** 聚合后的一组 */
    public static final class Group {
        public final String domain;        // 主域名
        public final List<Db.Entry> items; // 该域名下的条目
        public final String sampleUrl;     // 取一条完整网址用于图标 / 打开

        Group(String d, List<Db.Entry> items) {
            this.domain = d;
            this.items = items;
            String u = "";
            for (Db.Entry e : items) {
                if (e.url != null && !e.url.isEmpty()) { u = e.url; break; }
            }
            this.sampleUrl = u;
        }

        public int size() { return items.size(); }
    }

    /**
     * 把条目按主域名分组。
     *
     * 无网址的条目（纯 App 账号、卡包）归到「无网址」组，不丢弃 ——
     * 否则用户搜不到它们会以为丢了。
     */
    public static List<Group> group(List<Db.Entry> entries) {
        Map<String, List<Db.Entry>> map = new LinkedHashMap<>();
        List<Db.Entry> noUrl = new ArrayList<>();

        for (Db.Entry e : entries) {
            String d = regDomain(e.url);
            if (d.isEmpty()) {
                /* 没网址就退回用标题猜：标题里有域名的话也能归组 */
                d = regDomain(e.title);
            }
            if (d.isEmpty()) { noUrl.add(e); continue; }
            List<Db.Entry> l = map.get(d);
            if (l == null) { l = new ArrayList<>(); map.put(d, l); }
            l.add(e);
        }

        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Db.Entry>> en : map.entrySet()) {
            List<Db.Entry> items = en.getValue();
            /* 组内排序：收藏优先，再按最近使用 */
            Collections.sort(items, new Comparator<Db.Entry>() {
                @Override public int compare(Db.Entry a, Db.Entry b) {
                    if (a.fav != b.fav) return Long.compare(b.fav, a.fav);
                    return Long.compare(b.uses, a.uses);
                }
            });
            out.add(new Group(en.getKey(), items));
        }

        /* 组间排序：条目多的组排前面（大站更可能是你要找的） */
        Collections.sort(out, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                if (a.size() != b.size()) return b.size() - a.size();
                return a.domain.compareTo(b.domain);
            }
        });

        if (!noUrl.isEmpty()) out.add(new Group("", noUrl));
        return out;
    }

    /**
     * 账号名脱敏：邮箱保留首字符与域名，手机号保留前 3 后 4。
     *
     * 这个视图的核心价值就是「看清自己绑了什么账号」，如果全打码就没意义了。
     * 所以只做很轻的遮蔽：够你认出是哪个邮箱，又不完整暴露。
     */
    public static String hint(String user) {
        if (user == null || user.isEmpty()) return "（未填账号）";
        String s = user.trim();
        int at = s.indexOf('@');
        if (at > 0) {
            String name = s.substring(0, at);
            String tail = s.substring(at);
            if (name.length() <= 2) return name.charAt(0) + "***" + tail;
            return name.substring(0, 2) + "***" + name.charAt(name.length() - 1) + tail;
        }
        /* 纯数字按手机号处理 */
        boolean digit = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) { digit = false; break; }
        }
        if (digit && s.length() >= 7) {
            return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
        }
        if (s.length() <= 2) return s.charAt(0) + "*";
        return s.substring(0, 2) + "***" + s.charAt(s.length() - 1);
    }
}
