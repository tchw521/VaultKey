package com.vaultkey.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 图标获取：本机 App 图标（本地）+ 网站 favicon（在线，多源回退 + 双层缓存） */
public final class Icons {
    public static final class AppInfo {
        public String pkg, name;
        public Drawable icon;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final int MAX_CACHE = 120;

    private static final Map<String, Bitmap> MEM = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > MAX_CACHE; }
    };

    public interface Cb { void onDone(Bitmap b); }

    /* ---------------- 本机 App（本地获取） ---------------- */

    /**
     * 读取本机已安装应用。
     * Android 11+ 有包可见性限制：优先用 queryIntentActivities（配合 manifest 的 <queries> 声明），
     * 拿不到再退回 getInstalledApplications，最后兜底 getInstalledPackages。
     */
    public static List<AppInfo> installed(Context c) {
        List<AppInfo> l = new ArrayList<>();
        PackageManager pm = c.getPackageManager();

        // 1) 带启动器的应用（用户最关心的那批，且 <queries> 声明后一定可见）
        try {
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> rs = pm.queryIntentActivities(main, 0);
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (rs != null) {
                for (android.content.pm.ResolveInfo r : rs) {
                    try {
                        String pk = r.activityInfo.packageName;
                        if (pk == null || !seen.add(pk)) continue;
                        if (pk.equals(c.getPackageName())) continue;
                        AppInfo a = new AppInfo();
                        a.pkg = pk;
                        a.name = r.loadLabel(pm).toString();
                        a.icon = r.loadIcon(pm);
                        l.add(a);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }

        // 2) 全部已安装应用（含无图标的系统服务），补充 1 里拿不到的
        try {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (AppInfo a : l) have.add(a.pkg);
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps != null) {
                for (ApplicationInfo ai : apps) {
                    try {
                        if (have.contains(ai.packageName)) continue;
                        boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        if (sys && !isKnown(ai.packageName)) continue;
                        AppInfo a = new AppInfo();
                        a.pkg = ai.packageName;
                        a.name = ai.loadLabel(pm).toString();
                        a.icon = ai.loadIcon(pm);
                        l.add(a);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }

        // 3) 最后兜底：只列包名（仍可能被可见性过滤）
        if (l.isEmpty()) {
            try {
                List<android.content.pm.PackageInfo> ps = pm.getInstalledPackages(0);
                if (ps != null) {
                    for (android.content.pm.PackageInfo pi : ps) {
                        try {
                            AppInfo a = new AppInfo();
                            a.pkg = pi.packageName;
                            a.name = pi.applicationInfo != null
                                    ? pi.applicationInfo.loadLabel(pm).toString() : pi.packageName;
                            a.icon = pi.applicationInfo != null ? pi.applicationInfo.loadIcon(pm) : null;
                            l.add(a);
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
        }

        try {
            Collections.sort(l, new Comparator<AppInfo>() {
                @Override public int compare(AppInfo a, AppInfo b) { return a.name.compareToIgnoreCase(b.name); }
            });
        } catch (Exception ignored) { }
        return l;
    }

    private static boolean isKnown(String p) {
        return p.startsWith("com.android") || p.startsWith("com.google") || p.startsWith("com.miui")
                || p.startsWith("com.huawei") || p.startsWith("com.coloros");
    }

    public static Drawable appIcon(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try { return c.getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    public static String appName(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return null; }
    }

    /* ---------------- 网站图标（在线获取） ---------------- */

    public static void favicon(Context c, String url, Cb cb) {
        String host = hostOf(url);
        if (host == null) { cb.onDone(null); return; }
        Bitmap m = MEM.get(host);
        if (m != null) { cb.onDone(m); return; }
        File f = file(c, host);
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                MEM.put(host, b);
                cb.onDone(b);
                return;
            }
        }
        POOL.execute(() -> {
            Bitmap b = download(host);
            if (b != null) {
                MEM.put(host, b);
                try {
                    File dir = f.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    FileOutputStream o = new FileOutputStream(f);
                    b.compress(Bitmap.CompressFormat.PNG, 90, o);
                    o.close();
                } catch (Exception ignored) { }
            }
            H.post(() -> cb.onDone(b));
        });
    }

    /** 按任意 key 缓存（域名或 "pkg:xxx"） */
    public static void cacheKey(Context c, String key, Bitmap b) {
        if (key == null || b == null) return;
        MEM.put(key, b);
        try {
            File f = file(c, key);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 90, o);
            o.close();
        } catch (Exception ignored) { }
    }

    /** 把在线取到的图标写入双层缓存（供搜索选中的站点图标复用） */
    public static void cacheHost(Context c, String host, Bitmap b) {
        cacheKey(c, host, b);
    }

    /** 内存/磁盘里是否已有这个 key 的图标 */
    public static Bitmap peek(Context c, String key) {
        if (key == null) return null;
        Bitmap m = MEM.get(key);
        if (m != null) return m;
        File f = file(c, key);
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) { MEM.put(key, b); return b; }
        }
        return null;
    }

    private static Bitmap download(String host) {
        String[] urls = {
                "https://" + host + "/favicon.ico",
                "https://icons.duckduckgo.com/ip3/" + host + ".png",
                "https://www.google.com/s2/favicons?domain=" + host + "&sz=96"
        };
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VaultKey");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                InputStream in = c.getInputStream();
                Bitmap b = BitmapFactory.decodeStream(in);
                in.close();
                c.disconnect();
                if (b != null && b.getWidth() > 0) return square(b);
            } catch (Exception ignored) {
                if (c != null) try { c.disconnect(); } catch (Exception ignored2) { }
            }
        }
        return null;
    }

    private static Bitmap square(Bitmap src) {
        int size = 96;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        int w = src.getWidth(), h = src.getHeight();
        int s = Math.min(w, h);
        int x = (w - s) / 2, y = (h - s) / 2;
        cv.drawBitmap(src, new Rect(x, y, x + s, y + s), new Rect(0, 0, size, size), null);
        return out;
    }

    /** 圆角裁剪，列表/详情页统一外观 */
    public static Bitmap round(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(b, 0, 0, p);
        return out;
    }

    private static File file(Context c, String host) {
        return new File(c.getFilesDir(), "icons/" + safe(host) + ".png");
    }

    private static String safe(String s) {
        return s.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public static String hostOf(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        s = s.toLowerCase();
        if (s.isEmpty() || s.indexOf('.') < 0) return null;
        return s;
    }

    /** 主域名，用于自动填充匹配（a.login.xx.com -> xx.com） */
    public static String mainDomain(String host) {
        if (host == null) return "";
        String h = host.startsWith("www.") ? host.substring(4) : host;
        String[] a = h.split("\\.");
        if (a.length >= 2) return a[a.length - 2] + "." + a[a.length - 1];
        return h;
    }
}
