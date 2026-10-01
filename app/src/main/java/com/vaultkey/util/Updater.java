package com.vaultkey.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新。
 *
 * 数据源：GitHub Releases API（只读，不需要 token）
 *   https://api.github.com/repos/tchw521/VaultKey/releases/latest
 *
 * 用系统自带的 HttpURLConnection + org.json，不引第三方库。
 */
public final class Updater {

    /** 发布仓库 —— 改这里就能换到 fork 的地址 */
    public static final String REPO = "tchw521/VaultKey";

    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    /** 蓝奏云备用下载（网盘直链，无需梯子） */
    public static final String LANZOU_URL = "https://zalm.lanzouv.com/b0he8noif";
    public static final String LANZOU_PWD = "1111";

    public static final class Info {
        public boolean ok;          // 是否成功取到
        public String version;      // 远端版本号，如 3.14.0
        public String name;         // Release 标题
        public String notes;        // 更新说明
        public String apkUrl;       // APK 下载直链
        public long apkSize;
        public String pageUrl;      // Release 页面
        public String error;
    }

    /** 同步请求，必须在子线程调用 */
    public static Info fetch() {
        Info i = new Info();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "VaultKey/" + REPO);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                i.error = "服务器返回 " + code;
                return i;
            }
            String body = readAll(c.getInputStream());
            JSONObject o = new JSONObject(body);

            i.version = o.optString("tag_name", "").trim();
            /* tag 可能带 v 前缀，去掉 */
            while (i.version.startsWith("v") || i.version.startsWith("V"))
                i.version = i.version.substring(1);
            i.name = o.optString("name", "");
            i.notes = o.optString("body", "");
            i.pageUrl = o.optString("html_url", RELEASES_PAGE);

            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject a = assets.getJSONObject(k);
                    String n = a.optString("name", "");
                    if (n.endsWith(".apk")) {
                        i.apkUrl = a.optString("browser_download_url", "");
                        i.apkSize = a.optLong("size", 0);
                        break;
                    }
                }
            }
            if (i.apkUrl == null || i.apkUrl.isEmpty()) i.apkUrl = i.pageUrl;
            i.ok = !i.version.isEmpty();
            if (!i.ok) i.error = "返回里没有版本号";
        } catch (Exception e) {
            i.error = msgOf(e);
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Exception ignored) { } }
        }
        return i;
    }

    /**
     * 比较版本号：返回 >0 表示 a 更新。
     * 逐段按数字比，长度不同补 0 —— 不能用字符串比较，
     * 否则 "3.9.0" > "3.10.0" 会判断错（字符串里 '9' > '1'）。
     */
    public static int compare(String a, String b) {
        String[] x = split(a), y = split(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p - q;
        }
        return 0;
    }

    private static String[] split(String v) {
        if (v == null) return new String[0];
        String s = v.trim();
        while (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        /* 去掉 -beta 之类的后缀，只留数字段 */
        int cut = s.indexOf('-');
        if (cut > 0) s = s.substring(0, cut);
        return s.isEmpty() ? new String[0] : s.split("\\.");
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** 用浏览器打开 */
    public static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        try { in.close(); } catch (Exception ignored) { }
        return new String(o.toByteArray(), "UTF-8");
    }

    private static String msgOf(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        /* 把最可能的两种失败翻译成人话 */
        String low = m.toLowerCase();
        if (low.contains("unable to resolve") || low.contains("unknownhost"))
            return "连不上 GitHub，检查网络";
        if (low.contains("timed out") || low.contains("timeout"))
            return "连接超时";
        return m;
    }
}
