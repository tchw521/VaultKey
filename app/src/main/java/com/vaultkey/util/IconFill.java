package com.vaultkey.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import com.vaultkey.data.Db;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动图标补全：本地优先，本地没有再走云端。并发执行。
 *
 * 判定顺序（每个账号）：
 *   1. 本地：条目已存 iconKey（上次取到过的）→ 直接用
 *   2. 本地：关联了本机 App → 系统图标
 *   3. 本地：缓存里已有该域名 / pkg:包名
 *   4. 云端：按网址域名取 favicon
 *   5. 云端：按包名取应用图标
 *   6. 云端：按名称搜索取首个结果
 *
 * 加速措施：
 *   - 本机已装包名集合只查一次 PackageManager（原先每条查一次，是主要瓶颈）
 *   - 同一域名只真正下载一次，其余条目复用结果
 *   - 并发 4 线程（原先串行）
 *   - 网络超时收紧到 3s 连接 / 4s 读取（原先 6s / 8s）
 */
public final class IconFill {
    public static final class Result {
        public String uuid = "";
        public String title = "";
        public int from = NONE;
        public String host = "";
        public String url = "";
        public Bitmap icon;
        public boolean ok;
    }

    public static final int NONE = 0;
    public static final int CACHED_KEY = 1;  // 条目已存图标键
    public static final int LOCAL_APP = 2;   // 本机 App 图标
    public static final int CACHE = 3;       // 已有缓存
    public static final int CLOUD_HOST = 4;
    public static final int CLOUD_PKG = 5;
    public static final int CLOUD_SEARCH = 6;

    public static String fromName(int f) {
        switch (f) {
            case CACHED_KEY: return "本机已存";
            case LOCAL_APP: return "本机 App";
            case CACHE: return "已有缓存";
            case CLOUD_HOST: return "网址在线";
            case CLOUD_PKG: return "包名在线";
            case CLOUD_SEARCH: return "名称搜索";
            default: return "未获取";
        }
    }

    public interface Cb {
        void onOne(Result r, int done, int total);
        void onDone(int ok, int fail, boolean cancelled);
    }

    private static ExecutorService POOL;
    private static volatile Future<?> cur;
    private static volatile boolean cancelled;

    private static ExecutorService pool() {
        if (POOL == null || POOL.isShutdown()) {
            POOL = Executors.newFixedThreadPool(4);
        }
        return POOL;
    }

    public static boolean running() {
        return cur != null && !cur.isDone();
    }

    public static void cancel() { cancelled = true; }

    /* ---------------- 本地判定（不涉及网络） ---------------- */

    /** 一次性取到本机已装包名集合，供批量判定复用 */
    private static Set<String> installedSet(Context c) {
        Set<String> s = new HashSet<>();
        try {
            PackageManager pm = c.getPackageManager();
            List<android.content.pm.ApplicationInfo> as =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (as != null) for (android.content.pm.ApplicationInfo a : as) s.add(a.packageName);
        } catch (Exception ignored) { }
        return s;
    }

    /** 这个条目当前是否有可用图标（本地判定，不联网） */
    public static boolean hasIcon(Context c, Db.Entry e) {
        return hasIcon(c, e, null);
    }

    private static boolean hasIcon(Context c, Db.Entry e, Set<String> inst) {
        if (e == null) return false;
        // 已存过图标键 → 一定已有图标
        if (e.iconKey != null && !e.iconKey.isEmpty() && Icons.peek(c, e.iconKey) != null) return true;
        if (e.pkg != null && !e.pkg.isEmpty()) {
            if (inst != null) {
                if (inst.contains(e.pkg)) return true;
            } else if (Icons.appIcon(c, e.pkg) != null) return true;
            if (Icons.peek(c, "pkg:" + e.pkg) != null) return true;
        }
        String host = Icons.hostOf(e.url);
        if (host != null && Icons.peek(c, host) != null) return true;
        return false;
    }

    /** 列出所有尚未获得图标的账号（一次 PackageManager 查询，快） */
    public static List<Db.Entry> missing(Context c) {
        Set<String> inst = installedSet(c);
        List<Db.Entry> out = new ArrayList<>();
        for (Db.Entry e : Db.get(c).list(null, false, false, null, 0)) {
            if (!hasIcon(c, e, inst)) out.add(e);
        }
        return out;
    }

    public static boolean need(Context c, Db.Entry e) {
        return !hasIcon(c, e);
    }

    /* ---------------- 批量执行 ---------------- */

    public static void run(final Context c, List<Db.Entry> list, final boolean fillUrl, final Cb cb) {
        cancelled = false;
        final int total = list.size();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger ok = new AtomicInteger(0);
        final AtomicInteger fail = new AtomicInteger(0);

        /* 同一域名只下载一次：key -> 已完成的 Future 结果 */
        final Map<String, Bitmap> shared = new ConcurrentHashMap<>();
        final Set<String> pkgInstalled = installedSet(c);

        cur = pool().submit(new Runnable() {
            @Override public void run() {
                List<Thread> ts = new ArrayList<>();
                /* 分 4 组并行，每组串行处理自己那部分，避免线程过多 */
                int g = Math.min(4, Math.max(1, total));
                for (int i = 0; i < g; i++) {
                    final List<Db.Entry> part = new ArrayList<>();
                    for (int j = i; j < total; j += g) part.add(list.get(j));
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() {
                            for (Db.Entry e : part) {
                                if (cancelled) break;
                                Result r = one(c, e, fillUrl, shared, pkgInstalled);
                                /* 把结果写回条目：只改 icon_key，不动 mtime */
                                if (r.ok && r.icon != null && e.id > 0) {
                                    String key = !r.host.isEmpty() ? r.host
                                            : (e.pkg != null && !e.pkg.isEmpty() ? "pkg:" + e.pkg : "");
                                    if (!key.isEmpty()) {
                                        Db.get(c).setIconKey(e.id, key);
                                        r.host = key;
                                    }
                                }
                                if (r.ok) ok.incrementAndGet(); else fail.incrementAndGet();
                                final Result fr = r;
                                final int fd = done.incrementAndGet();
                                h.post(new Runnable() {
                                    @Override public void run() { cb.onOne(fr, fd, total); }
                                });
                            }
                        }
                    });
                    ts.add(t);
                    t.start();
                }
                for (Thread t : ts) {
                    try { t.join(); } catch (InterruptedException ignored) { }
                }
                final int fok = ok.get(), ffail = fail.get();
                final boolean fc = cancelled;
                h.post(new Runnable() {
                    @Override public void run() { cb.onDone(fok, ffail, fc); }
                });
            }
        });
    }

    /** 单条补全（同步，后台线程调用） */
    public static Result one(Context c, Db.Entry e, boolean fillUrl,
                             Map<String, Bitmap> shared, Set<String> installed) {
        Result r = new Result();
        r.uuid = e.uuid;
        r.title = e.title == null || e.title.isEmpty() ? e.user : e.title;

        /* --- 1. 本地：条目已存的图标键 --- */
        if (e.iconKey != null && !e.iconKey.isEmpty()) {
            Bitmap b = Icons.peek(c, e.iconKey);
            if (b != null) { r.from = CACHED_KEY; r.ok = true; r.icon = b; r.host = e.iconKey; return r; }
        }

        /* --- 2. 本地：本机 App 图标 --- */
        if (e.pkg != null && !e.pkg.isEmpty()) {
            boolean has = installed != null ? installed.contains(e.pkg) : Icons.appIcon(c, e.pkg) != null;
            if (has) { r.from = LOCAL_APP; r.ok = true; return r; }
        }

        /* --- 3. 本地：域名缓存 --- */
        String host = Icons.hostOf(e.url);
        if (host != null) {
            Bitmap b = Icons.peek(c, host);
            if (b != null) { r.from = CACHE; r.ok = true; r.icon = b; r.host = host; return r; }
        }

        /* --- 4. 云端：按域名取 favicon（同域名复用，只下一次） --- */
        if (host != null) {
            Bitmap b = sharedGet(c, host, shared);
            if (b != null) {
                Icons.cacheHost(c, host, b);
                r.from = CLOUD_HOST; r.ok = true; r.icon = b; r.host = host;
                return r;
            }
        }

        /* --- 5. 云端：按包名 --- */
        if (e.pkg != null && !e.pkg.isEmpty()) {
            String k = "pkg:" + e.pkg;
            Bitmap b = sharedGet(c, k, shared);
            if (b == null) {
                b = Lookup.playIcon(e.pkg);
                if (b != null && shared != null) shared.put(k, b);
            }
            if (b != null) {
                Icons.cacheKey(c, k, b);
                r.from = CLOUD_PKG; r.ok = true; r.icon = b; r.host = k;
                return r;
            }
        }

        /* --- 6. 云端：按名称搜索 --- */
        String key = r.title;
        if (key == null || key.isEmpty()) return r;
        List<Lookup.Hit> hits = Lookup.search(key);
        if (hits.isEmpty()) return r;
        for (Lookup.Hit hit : hits) {
            if (cancelled) break;
            Bitmap b = sharedGet(c, hit.host, shared);
            if (b != null) {
                Icons.cacheHost(c, hit.host, b);
                r.from = CLOUD_SEARCH;
                r.ok = true;
                r.icon = b;
                r.host = hit.host;
                r.url = hit.url;
                if (fillUrl && (e.url == null || e.url.isEmpty())) {
                    e.url = hit.url;
                    Db.get(c).save(e);
                }
                return r;
            }
        }
        return r;
    }

    /** 共享下载：同一个 key 并发只触发一次真实网络请求 */
    private static Bitmap sharedGet(Context c, String key, Map<String, Bitmap> shared) {
        if (key == null || key.isEmpty()) return null;
        if (shared != null) {
            Bitmap b = shared.get(key);
            if (b != null) return b;
        }
        Bitmap b = Icons.peek(c, key);
        if (b != null) return b;
        b = Lookup.icon(key);
        if (b != null && shared != null) shared.put(key, b);
        return b;
    }
}
