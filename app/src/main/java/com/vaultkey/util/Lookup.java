package com.vaultkey.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线搜索：按名称搜网址、搜图标。纯 HttpURLConnection + 正则解析，不引第三方库。
 * 三个搜索源依次回退（DuckDuckGo Lite → DuckDuckGo HTML → Bing），国内网络也能用。
 */
public final class Lookup {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    public static final class Hit {
        public String title = "";
        public String url = "";
        public String host = "";
        public Bitmap icon;

        public String label() {
            String t = title.isEmpty() ? host : title;
            return t;
        }
    }

    /* ---------------- 搜索 ---------------- */

    /** 内置常用站点：名称关键词 -> 官方网址。无需联网即可命中，体积小、结果准确 */
    private static final String[][] KNOWN = {
            {"微信", "https://weixin.qq.com/"}, {"QQ", "https://im.qq.com/"},
            {"支付宝", "https://www.alipay.com/"}, {"淘宝", "https://www.taobao.com/"},
            {"天猫", "https://www.tmall.com/"}, {"京东", "https://www.jd.com/"},
            {"拼多多", "https://mobile.yangkeduo.com/"}, {"抖音", "https://www.douyin.com/"},
            {"快手", "https://www.kuaishou.com/"}, {"微博", "https://weibo.com/"},
            {"小红书", "https://www.xiaohongshu.com/"}, {"哔哩哔哩", "https://www.bilibili.com/"},
            {"B站", "https://www.bilibili.com/"}, {"知乎", "https://www.zhihu.com/"},
            {"百度网盘", "https://pan.baidu.com/"}, {"百度", "https://www.baidu.com/"},
            {"网易云音乐", "https://music.163.com/"}, {"QQ音乐", "https://y.qq.com/"},
            {"腾讯视频", "https://v.qq.com/"}, {"爱奇艺", "https://www.iqiyi.com/"},
            {"优酷", "https://www.youku.com/"}, {"芒果", "https://www.mgtv.com/"},
            {"网易邮箱", "https://mail.163.com/"}, {"QQ邮箱", "https://mail.qq.com/"},
            {"Gmail", "https://mail.google.com/"}, {"Outlook", "https://outlook.live.com/"},
            {"GitHub", "https://github.com/"}, {"GitLab", "https://gitlab.com/"},
            {"Gitee", "https://gitee.com/"}, {"Google", "https://www.google.com/"},
            {"Microsoft", "https://www.microsoft.com/"}, {"Apple", "https://www.apple.com.cn/"},
            {"Steam", "https://store.steampowered.com/"}, {"Epic", "https://store.epicgames.com/"},
            {"小米", "https://www.mi.com/"}, {"华为", "https://www.huawei.com/"},
            {"阿里云", "https://www.aliyun.com/"}, {"腾讯云", "https://cloud.tencent.com/"},
            {"中国移动", "https://www.10086.cn/"}, {"中国联通", "https://www.10010.com/"},
            {"中国电信", "https://www.189.cn/"}, {"12306", "https://www.12306.cn/"},
            {"滴滴", "https://www.didiglobal.com/"}, {"美团", "https://www.meituan.com/"},
            {"饿了么", "https://www.ele.me/"}, {"携程", "https://www.ctrip.com/"},
            {"去哪儿", "https://www.qunar.com/"}, {"飞猪", "https://www.alitrip.com/"},
            {"高德", "https://www.amap.com/"}, {"招商银行", "https://www.cmbchina.com/"},
            {"工商银行", "https://www.icbc.com.cn/"}, {"建设银行", "https://www.ccb.com/"},
            {"中国银行", "https://www.boc.cn/"}, {"农业银行", "https://www.abchina.com/"},
            {"坚果云", "https://www.jianguoyun.com/"}, {"阿里云盘", "https://www.alipan.com/"},
            {"微云", "https://www.weiyun.com/"}, {"OneDrive", "https://onedrive.live.com/"},
            {"Google Drive", "https://drive.google.com/"}, {"Dropbox", "https://www.dropbox.com/"},
            {"iCloud", "https://www.icloud.com/"}, {"123云盘", "https://www.123pan.com/"},
            {"迅雷", "https://www.xunlei.com/"}, {"有道", "https://www.youdao.com/"},
            {"豆瓣", "https://www.douban.com/"}, {"起点", "https://www.qidian.com/"},
            {"今日头条", "https://www.toutiao.com/"}, {"网易", "https://www.163.com/"},
            {"新浪", "https://www.sina.com.cn/"}, {"搜狐", "https://www.sohu.com/"},
            {"淘宝联盟", "https://pub.alimama.com/"}, {"闲鱼", "https://www.goofish.com/"},
            {"Steam 社区", "https://steamcommunity.com/"}, {"Discord", "https://discord.com/"},
            {"Telegram", "https://web.telegram.org/"}, {"Twitter", "https://twitter.com/"},
            {"Facebook", "https://www.facebook.com/"}, {"Instagram", "https://www.instagram.com/"},
            {"Netflix", "https://www.netflix.com/"}, {"Spotify", "https://open.spotify.com/"},
            {"ChatGPT", "https://chat.openai.com/"}, {"Claude", "https://claude.ai/"},
            {"Notion", "https://www.notion.so/"}, {"Figma", "https://www.figma.com/"},
            {"Stack Overflow", "https://stackoverflow.com/"}, {"知乎日报", "https://daily.zhihu.com/"}
    };

    /** 先在内置表里直查（离线可用、结果准确），搜不到再联网搜索 */
    private static List<Hit> known(String q) {
        List<Hit> l = new ArrayList<>();
        String s = q.toLowerCase();
        for (String[] kv : KNOWN) {
            if (kv.length < 2 || kv[0] == null || kv[1] == null) continue;
            String k = kv[0].toLowerCase();
            if (k.equals(s) || s.contains(k) || k.contains(s)) {
                Hit h = new Hit();
                h.title = kv[0];
                h.url = kv[1];
                h.host = Icons.hostOf(kv[1]);
                if (h.host != null) l.add(h);
            }
        }
        // 关键词越长越精确，排在前面（"QQ音乐" 优先于 "QQ"）
        try {
            java.util.Collections.sort(l, new java.util.Comparator<Hit>() {
                @Override public int compare(Hit a, Hit b) {
                    return Integer.compare(b.title.length(), a.title.length());
                }
            });
        } catch (Exception ignored) { }
        return l;
    }

    /** 按关键词搜索，返回候选站点（含标题、网址、域名）。失败返回空列表 */
    public static List<Hit> search(String q) {
        String s = q == null ? "" : q.trim();
        if (s.isEmpty()) return new ArrayList<>();
        List<Hit> r = known(s);
        if (!r.isEmpty()) return r;
        r = ddgLite(s);
        if (r.isEmpty()) r = ddgHtml(s);
        if (r.isEmpty()) r = bing(s);
        // 去重：同一域名只保留第一个
        List<Hit> out = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        for (Hit h : r) {
            if (h.host == null || h.host.isEmpty()) continue;
            if (hosts.contains(h.host)) continue;
            hosts.add(h.host);
            out.add(h);
        }
        return out;
    }

    private static List<Hit> ddgLite(String q) {
        return parse(get("https://lite.duckduckgo.com/lite/?q=" + enc(q)), "result-link");
    }

    private static List<Hit> ddgHtml(String q) {
        return parse(get("https://html.duckduckgo.com/html/?q=" + enc(q)), "result__a");
    }

    private static List<Hit> bing(String q) {
        String html = get("https://www.bing.com/search?q=" + enc(q) + "&setlang=zh-CN");
        if (html == null) return new ArrayList<>();
        List<Hit> l = new ArrayList<>();
        Matcher m = Pattern.compile("<h2>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find() && l.size() < 12) {
            String u = decode(m.group(1));
            String host = host(u);
            if (host == null) continue;
            Hit h = new Hit();
            h.url = norm(u);
            h.host = host;
            h.title = strip(m.group(2));
            l.add(h);
        }
        return l;
    }

    /** 通用解析：找出含指定 class 的 <a> 标签 */
    private static List<Hit> parse(String html, String cls) {
        List<Hit> l = new ArrayList<>();
        if (html == null) return l;
        String low = html.toLowerCase();
        int from = 0;
        while (l.size() < 12) {
            int i = low.indexOf("<a ", from);
            if (i < 0) break;
            int end = low.indexOf("</a>", i);
            if (end < 0) break;
            String chunk = html.substring(i, end);
            from = end + 4;
            if (chunk.indexOf(cls) < 0) continue;
            String href = attr(chunk, "href");
            if (href == null) continue;
            href = decode(href);
            String host = host(href);
            if (host == null) continue;
            int gt = chunk.indexOf('>');
            String text = gt >= 0 ? strip(chunk.substring(gt + 1)) : "";
            Hit h = new Hit();
            h.url = norm(href);
            h.host = host;
            h.title = text;
            l.add(h);
        }
        return l;
    }

    /* ---------------- 图标 ---------------- */

    /** 按域名取站点图标，多源回退；返回 null 表示都没取到 */
    public static Bitmap icon(String host) {
        if (host == null || host.isEmpty()) return null;
        /* 按「命中率 / 速度」排序：先免费的国内可用源，再官方 favicon，最后兜底 */
        String[] urls = {
                "https://api.iowen.cn/favicon/" + host + ".png",
                "https://" + host + "/favicon.ico",
                "https://icons.duckduckgo.com/ip3/" + host + ".png",
                "https://www.google.com/s2/favicons?domain=" + host + "&sz=96",
                "https://favicon.im/" + host,
                "https://" + host + "/apple-touch-icon.png"
        };
        for (String u : urls) {
            Bitmap b = img(u);
            if (b != null && b.getWidth() > 0) return b;
        }
        return null;
    }

    /** 按包名从 Google Play 取应用图标（国内可能不通，取不到返回 null） */
    public static Bitmap playIcon(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String html = get("https://play.google.com/store/apps/details?id=" + enc(pkg) + "&hl=zh", 4000, 6000);
        if (html == null) return null;
        Matcher m = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        String u = null;
        if (m.find()) u = m.group(1);
        if (u == null) {
            m = Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) u = m.group(1);
        }
        if (u == null) return null;
        return img(decode(u));
    }

    /* ---------------- HTTP ---------------- */

    private static String get(String u) { return get(u, 4000, 6000); }

    private static String get(String u, int ct, int rt) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(ct);
            c.setReadTimeout(rt);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            return new String(o.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static Bitmap img(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    /* ---------------- 小工具 ---------------- */

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** DuckDuckGo 的结果链接包了一层 /l/?uddg=，需要解出来 */
    private static String decode(String href) {
        String s = href;
        int i = s.indexOf("uddg=");
        if (i >= 0) {
            int amp = s.indexOf('&', i);
            String raw = amp > 0 ? s.substring(i + 5, amp) : s.substring(i + 5);
            try { s = URLDecoder.decode(raw, "UTF-8"); } catch (Exception e) { s = raw; }
        }
        s = s.replace("&amp;", "&");
        return s;
    }

    private static String norm(String u) {
        String s = u.trim();
        if (s.startsWith("//")) s = "https:" + s;
        if (!s.startsWith("http")) s = "https://" + s;
        return s;
    }

    private static String host(String u) {
        String h = Icons.hostOf(u);
        if (h == null) return null;
        if (h.equals("duckduckgo.com") || h.equals("www.bing.com") || h.equals("bing.com")
                || h.equals("google.com") || h.equals("www.google.com")) return null;
        return h;
    }

    private static String strip(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]+>", "").trim();
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return t;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
