package com.vaultkey.data;

import android.content.Context;
import com.vaultkey.crypto.Crypto;
import com.vaultkey.sync.Sync;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动备份到已授权的本地文件夹。
 *
 * 复用说明：
 * - 文件内容直接复用 Sync.encode()（同一套加密格式），不另写一份打包逻辑。
 *   好处是本地备份和云端同步的文件格式一致，将来互相导入不用转换。
 * - 写文件复用 BackupDir.write()，包括它内部"先删同名文件"的处理。
 *
 * ── 为什么加密 ──
 * 备份落在公共文件夹里，虽然需要授权才能访问，但别的 App 仍可能扫到
 * （比如某些清理工具、文件管理器）。所以本地备份和云端同步一样加密，
 * 没有主密码打不开。
 *
 * ── 保留策略 ──
 * 只保留最近 N 份，避免文件夹无限膨胀。按文件名排序（名字里带时间戳），
 * 排序即时间序。
 */
public final class AutoBackup {

    private static final String PREFIX = "密盒备份-";
    private static final String EXT = ".vkbak";
    private static final String MIME = "application/octet-stream";
    private static final long DAY = 24L * 3600 * 1000;

    private AutoBackup() { }

    public static boolean enabled(Context c) {
        return Prefs.getB("auto_backup", false);
    }

    public static void setEnabled(Context c, boolean on) {
        Prefs.putB("auto_backup", on);
    }

    /** 保留份数，默认 7 */
    public static int keep(Context c) {
        return Prefs.getI("auto_backup_keep", 7);
    }

    public static void setKeep(Context c, int n) {
        Prefs.putI("auto_backup_keep", Math.max(1, Math.min(50, n)));
    }

    /* ---------------- 执行 ---------------- */

    public static final class Result {
        public final boolean ok;
        public final String msg;

        Result(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    /**
     * 进入应用时调用，带节流：默认每天最多一次。
     * 不满足条件就静默返回，不打扰用户。
     *
     * 真正的活儿放后台线程 —— 导出 + 加密 + 写文件这一串在解锁后跑，
     * 放主线程会让解锁后第一屏卡住，库大的时候尤其明显。
     * 判断条件仍在主线程做，因为只是读几个 Prefs，很快。
     */
    public static Result maybeRun(Context c) {
        if (!enabled(c)) return new Result(false, "未开启");
        if (!BackupDir.has(c)) return new Result(false, "未授权备份文件夹");
        if (Session.key() == null) return new Result(false, "未解锁");

        long last = 0;
        try { last = Long.parseLong(Prefs.get("last_backup", "0")); } catch (Exception ignored) { }
        if (System.currentTimeMillis() - last < DAY) return new Result(false, "今天已备份");

        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            try { runNow(app); } catch (Exception ignored) { }
        });
        return new Result(true, "后台备份中");
    }

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 立即备份（无视节流） */
    public static Result runNow(Context c) {
        if (!BackupDir.has(c)) return new Result(false, "未授权备份文件夹");
        if (!BackupDir.alive(c)) return new Result(false, "备份文件夹已失效，请重新选择");
        if (Session.key() == null) return new Result(false, "未解锁，请先解锁");

        String json = Db.get(c).exportJson();
        if (json == null) return new Result(false, "导出数据失败");

        /* 与云端同步同一套加密格式 */
        byte[] data = Sync.encode(json);
        if (data == null) return new Result(false, "加密失败");

        String name = PREFIX + stamp() + EXT;
        if (BackupDir.write(c, name, MIME, data) == null) {
            return new Result(false, "写入失败，检查文件夹权限");
        }

        Prefs.put("last_backup", String.valueOf(System.currentTimeMillis()));
        prune(c, keep(c));
        return new Result(true, "已备份到 " + BackupDir.name(c));
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date());
    }

    /** 只保留最近 keep 份，多的删掉 */
    public static int prune(Context c, int keep) {
        List<BackupDir.Item> items = BackupDir.list(c);
        if (items == null || items.isEmpty()) return 0;

        List<String> mine = new ArrayList<>();
        for (BackupDir.Item it : items) {
            if (it.name != null && it.name.startsWith(PREFIX) && it.name.endsWith(EXT)) {
                mine.add(it.name);
            }
        }
        if (mine.size() <= keep) return 0;

        /* 文件名里带时间戳，字典序即时间序 */
        Collections.sort(mine, Collections.reverseOrder());

        int removed = 0;
        for (int i = keep; i < mine.size(); i++) {
            if (BackupDir.delete(c, mine.get(i))) removed++;
        }
        return removed;
    }

    /** 上次备份的时间描述 */
    public static String lastText() {
        long t = 0;
        try { t = Long.parseLong(Prefs.get("last_backup", "0")); } catch (Exception ignored) { }
        if (t == 0) return "从未备份";
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1) return "刚刚备份";
        if (min < 60) return min + " 分钟前备份";
        if (min < 1440) return (min / 60) + " 小时前备份";
        return (min / 1440) + " 天前备份";
    }
}
