package com.vaultkey.sync;

import android.content.Context;
import android.util.Base64;
import com.vaultkey.crypto.Crypto;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.webdav.WebDavClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 坚果云双向同步：本地全库 -> 主密钥加密 -> 单文件放到云端；
 * 同步时取回该文件，按 uuid + mtime 与本地逐条合并，再把合并结果传回去。
 * 云端只能看到密文。
 */
public final class Sync {
    /*
     * 远端文件名不再固定：目录名和扩展名都可能因服务器限制而切换
     * （部分网盘拒绝中文目录名、或不接受自定义扩展名）。
     * 实际名称由 putAuto / getAuto 试错后写入 Prefs，见那两个方法。
     */
    public static final String FILE = "vaultkey-sync.vksy";
    private static final byte[] MAGIC = "VKSY".getBytes(StandardCharsets.UTF_8);

    public static final class Result {
        public boolean ok;
        public String msg;
        /** 探测失败时置位：UI 据此提供「跳过写入探测」的快捷入口 */
        public boolean skipProbeHint;
        public int pulled, pushed, removed;
        public Result(boolean o, String m) { ok = o; msg = m; }
    }

    public static boolean configured() {
        return !Prefs.get("dav_user", "").isEmpty() && !Prefs.get("dav_pass", "").isEmpty();
    }

    public static WebDavClient client() {
        return new WebDavClient(Prefs.get("dav_url", WebDavClient.JIANGUOYUN),
                Prefs.get("dav_user", ""), Prefs.get("dav_pass", ""));
    }

    public static String remoteDir() {
        String d = Prefs.get("dav_path", "密盒-VaultKey").trim();
        return d.isEmpty() ? "密盒-VaultKey" : d;
    }

    /* ---------- 文件编解码 ---------- */

    public static byte[] encode(String json) {
        byte[] ct = Crypto.encrypt(Session.key(), json.getBytes(StandardCharsets.UTF_8));
        if (ct == null) return null;
        ByteBuffer b = ByteBuffer.allocate(MAGIC.length + 1 + 8 + ct.length);
        b.put(MAGIC).put((byte) 2).putLong(System.currentTimeMillis()).put(ct);
        return b.array();
    }

    /** 解密失败返回 null（通常是主密码不匹配） */
    public static String decode(byte[] file) {
        if (file == null || file.length < 20) return null;
        for (int i = 0; i < 4; i++) if (file[i] != MAGIC[i]) return null;
        byte[] ct = new byte[file.length - 13];
        System.arraycopy(file, 13, ct, 0, ct.length);
        byte[] p = Crypto.decrypt(Session.key(), ct);
        return p == null ? null : new String(p, StandardCharsets.UTF_8);
    }
    /* ---------- 同步动作 ---------- */

    /** 双向同步：拉 -> 合并 -> 推 */
    /**
     * 把 WebDav 失败的原因翻译成提示。
     * 之前一律是"上传失败，检查网络或权限" —— 其实大多不是权限问题，
     * 而是应用密码填错（401）或服务器地址不对（404 / 找不到主机）。
     */
    private static Result failRes(WebDavClient w, String what) {
        String why = w == null ? null : w.lastError;
        if (why == null || why.isEmpty()) why = "网络异常或服务器拒绝";
        /* 附上服务器原话，403 的真正原因常写在里面 */
        return new Result(false, what + "失败：" + why + (w == null ? "" : extra(w)));
    }

    /**
     * 上传，并自动绕开 403。
     *
     * 坚果云的 403 通常有两种原因，都能自动处理：
     *   1. 目录名含中文 —— 部分服务建目录放行、写文件却拒（已知现象）
     *   2. 扩展名不被接受 —— 换 .txt 再试
     * 所以这里按「目录名候选 × 扩展名候选」依次尝试，成功就记住组合。
     *
     * @return 实际使用的 [目录, 文件名]；全部失败返回 null（原因在 w.lastError）
     */
    private static String[] putAuto(WebDavClient w, String dir, byte[] data) {
        String[] dirs = dirCandidates(dir);
        String[] exts = extCandidates();
        String lastErr = null;
        for (String d : dirs) {
            w.mkdir(d);
            for (String ext : exts) {
                String f = "vaultkey-sync." + ext;
                if (w.put(d + "/" + f, data)) {
                    /* 记住这次成功的组合，下次直接用，不用再试错 */
                    Prefs.put("dav_path", d);
                    Prefs.put("dav_ext", ext);
                    return new String[]{d, f};
                }
                lastErr = w.lastError;
                /* 不是 403 说明不是"目录名/扩展名被拒"，换名也没用，别白试 */
                if (w.lastCode != 403) return null;
            }
        }
        if (lastErr != null) w.lastError = lastErr;
        return null;
    }

    /**
     * 目录名候选：**先用你配置的那个**，403 才退到纯英文版。
     *
     * 之前为了让"自动修复"更容易成功，把英文版排在了前面 —— 现在尾部斜杠
     * 这个真因已经修好，中文目录名本来就能用，不该再擅自给你改名。
     */
    private static String[] dirCandidates(String dir) {
        String alt = asciiFallback(dir);
        if (alt == null || alt.equals(dir)) return new String[]{dir};
        return new String[]{dir, alt};
    }

    /** 扩展名候选：优先记住的，再试 vksy / txt */
    private static String[] extCandidates() {
        String cur = Prefs.get("dav_ext", "vksy");
        if (cur == null || cur.isEmpty()) cur = "vksy";
        if (cur.equals("vksy")) return new String[]{"vksy", "txt"};
        return new String[]{cur, "vksy", "txt"};
    }

    /**
     * 下载：在所有候选组合里找已存在的备份文件。
     * 之前用固定 dir + 固定文件名，一旦目录名被自动改过，就再也读不回来。
     */
    private static byte[] getAuto(WebDavClient w, String dir) {
        for (String d : dirCandidates(dir)) {
            for (String ext : extCandidates()) {
                byte[] b = w.get(d + "/" + "vaultkey-sync." + ext);
                if (b != null) {
                    Prefs.put("dav_path", d);
                    Prefs.put("dav_ext", ext);
                    return b;
                }
                /* 404 是正常的（还没这个文件），继续找；真错误就停 */
                if (w.lastError != null && w.lastCode != 404) return null;
            }
        }
        return null;
    }

    public static Result run(Context c) {
        if (!configured()) return new Result(false, "未配置坚果云账号");
        if (Session.key() == null) return new Result(false, "请先解锁");
        WebDavClient w = client();
        String dir = remoteDir();
        try {
            byte[] remote = getAuto(w, dir);
            Result r = new Result(true, "");
            if (remote == null) {
                // 云端还没有：直接推本地
                String json = Db.get(c).exportJson();
                if (json == null) return new Result(false, "本地数据读取失败");
                byte[] up = encode(json);
                String[] t = up == null ? null : putAuto(w, dir, up);
                if (t == null) return failRes(w, "上传");
                r.pushed = Db.get(c).count(false, -1);
                r.msg = "已上传本地数据";
                afterSync(c, w, t[0], up);
                return r;
            }
            String rj = decode(remote);
            if (rj == null) {
                return new Result(false, "云端文件无法解密：可能由另一个主密码创建");
            }
            int[] m = Db.get(c).mergeJson(rj);
            r.pulled = m[0];
            r.removed = m[1];
            String json = Db.get(c).exportJson();
            byte[] up = encode(json);
            String[] t = up == null ? null : putAuto(w, dir, up);
            if (t == null) {
                r.ok = false;
                r.msg = "合并完成，但回传失败：" + (w.lastError == null ? "网络异常" : w.lastError)
                        + extra(w);
                return r;
            }
            r.pushed = 1;
            r.msg = "同步完成";
            afterSync(c, w, t[0], up);
            return r;
        } catch (Exception e) {
            return new Result(false, "同步失败：" + (e.getMessage() == null ? "网络异常" : e.getMessage()));
        }
    }

    /**
     * 连接自检：不传真实数据，只验证「地址通不通 + 账号密码对不对 + 目录能不能建」。
     *
     * 同步失败时最难判断的就是这三者哪个出了岔子 ——
     * 以前只给一句"检查网络或权限"，用户只能一个个试。
     */
    public static Result diagnose(Context c) {
        if (!configured()) return new Result(false, "还没填账号和应用密码");
        WebDavClient w = client();
        String dir = remoteDir();
        StringBuilder log = new StringBuilder();
        String baseUrl = Prefs.get("dav_url", WebDavClient.JIANGUOYUN);

        /* 每一步都把 URL、状态码、服务器原文记下来。
           之前只说"哪一步失败"，用户还是不知道为什么 ——
           403 的真正原因往往写在服务器返回的 XML 里，得显示出来。 */

        /* 第一步：列根目录，验证地址 + 账号密码 */
        java.util.List<WebDavClient.Item> root = w.list("");
        log.append("① 访问根目录 ").append(baseUrl).append("\n").append("   ");
        if (w.lastError != null) {
            return new Result(false, "连不上服务器" + "\n\n" + log
                    + "   " + w.lastError + extra(w));
        }
        log.append("通过（").append(root == null ? 0 : root.size()).append(" 项）").append("\n\n");
        log.append("② 建目录「").append(dir).append("」").append("\n").append("   ");
        if (!w.mkdir(dir)) {
            return new Result(false, "创建目录失败" + "\n\n" + log
                    + (w.lastError == null ? "未知原因" : w.lastError) + extra(w));
        }
        log.append("通过").append("\n\n");

        /*
         * 第三步：写探针再读回，验证读写权限。
         *
         * 这步可以被跳过（设置里的「跳过写入探测」）。有些 WebDAV 服务
         * 限制很死（比如坚果云拒绝点开头的隐藏文件），探测反而成了唯一
         * 的失败点 —— 前两步都通过说明目录是可用的，直接同步通常就能成。
         */
        if (Prefs.getB("dav_skip_probe", false)) {
            log.append("③ 写入测试 —— 已跳过（未验证写入）").append("\n\n")
                    .append("提示：探针已不带点，坚果云能直接通过，建议关掉该开关再测一次").append("\n\n");
        } else {
            /*
             * 探针文件名**不能以点开头**：坚果云明确不支持隐藏文件，
             * 上传 `.xxx` 会直接 403 "OperationNotAllowed ... can not be uploaded"。
             * 之前探针叫 `.vaultkey-probe`，所以这里永远失败 ——
             * 而真正的同步文件 vaultkey-sync.vksy 并不是隐藏文件，其实能上传。
             */
            String[] cands = {"vaultkey-probe.vksy", "vaultkey-probe.txt"};
            String used = null;
            String lastWhy = null;
            String lastExtra = null;
            for (String cn : cands) {
                String probe = dir + "/" + cn;
                if (!w.put(probe, "ok".getBytes(StandardCharsets.UTF_8))) {
                    lastWhy = w.lastError == null ? "未知原因" : w.lastError;
                    lastExtra = extra(w);
                    continue;
                }
                byte[] back = w.get(probe);
                w.delete(probe);
                if (back != null && "ok".equals(new String(back, StandardCharsets.UTF_8))) {
                    used = cn;
                    break;
                }
                lastWhy = "写进去了但读不回来";
                lastExtra = extra(w);
            }

            if (used == null) {
                String why = lastWhy == null ? "未知原因" : lastWhy;
                /*
                 * 探测失败不代表同步一定会失败 —— 前两步（访问根目录、建目录）
                 * 都通过了，说明账号和路径没问题，很可能只是这个服务器
                 * 不接受探针文件。所以这里给出「跳过探测」的快捷入口，
                 * 而不是让用户干瞪眼。
                 */
                String alt = asciiFallback(dir);
                if (w.lastCode == 403 && alt != null) {
                    WebDavClient w2 = client();
                    if (w2.mkdir(alt) && w2.put(alt + "/vaultkey-probe.txt",
                            "ok".getBytes(StandardCharsets.UTF_8))) {
                        byte[] b2 = w2.get(alt + "/vaultkey-probe.txt");
                        w2.delete(alt + "/vaultkey-probe.txt");
                        if (b2 != null && "ok".equals(new String(b2, StandardCharsets.UTF_8))) {
                            Prefs.put("dav_path", alt);
                            return new Result(true, "已自动修复" + "\n\n"
                                    + "目录名「" + dir + "」含中文，服务器拒绝了写入。"
                                    + "\n" + "已改用「" + alt + "」，现在可以同步了。");
                        }
                    }
                }
                final String whyF = why;
                Result r = new Result(false, "写入测试失败" + "\n\n" + log + why
                        + (lastExtra == null ? "" : lastExtra));
                r.skipProbeHint = true;
                return r;
            }

            /* .vksy 被拒但 .txt 可以 —— 记住以后用 .txt */
            if (used.endsWith(".txt")) {
                Prefs.put("dav_ext", "txt");
                log.append("③ 写入测试 通过（.vksy 被服务器拒绝，已改用 .txt）").append("\n\n");
            } else {
                Prefs.put("dav_ext", "vksy");
                log.append("③ 写入测试 通过").append("\n\n");
            }
        }

        return new Result(true, "连接正常" + "\n\n" + log
                + "账号：" + Prefs.get("dav_user", "")
                + "\n" + "目录：" + dir);
    }

    /** 目录名含非 ASCII 字符时，返回一个可用的纯英文替代名 */
    private static String asciiFallback(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        if (isAscii(dir)) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < dir.length(); i++) {
            char c = dir.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_')) b.append(c);
        }
        String r = b.toString();
        /* 去掉前导的 - / _ / 数字：以它们开头的目录名有些服务器不接受 */
        while (!r.isEmpty() && !Character.isLetter(r.charAt(0))) r = r.substring(1);
        return r.isEmpty() ? "VaultKey" : r;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) return false;
        return true;
    }

    /** 把服务器返回的原始说明拼到提示里（403 的真正原因往往写在里面） */
    private static String extra(WebDavClient w) {
        StringBuilder b = new StringBuilder();
        if (w.lastUrl != null) b.append("\n\n").append("请求地址：").append("\n").append(w.lastUrl);
        if (w.lastBody != null && !w.lastBody.isEmpty())
            b.append("\n\n").append("服务器返回：").append("\n").append(w.lastBody);
        return b.toString();
    }

    /** 以云端为准，覆盖本地 */
    public static Result forceDownload(Context c) {
        if (!configured()) return new Result(false, "未配置坚果云账号");
        WebDavClient w = client();
        String dir = remoteDir();
        byte[] remote = getAuto(w, dir);
        if (remote == null) return new Result(false,
                w.lastError == null ? "云端还没有备份文件" : "读取云端失败：" + w.lastError + extra(w));
        String rj = decode(remote);
        if (rj == null) return new Result(false, "云端文件无法解密：可能由另一个主密码创建");
        Db.get(c).replaceAll(rj);
        Result r = new Result(true, "已从云端恢复");
        r.pulled = Db.get(c).count(false, -1);
        Prefs.put("last_sync", String.valueOf(System.currentTimeMillis()));
        return r;
    }

    /** 以本地为准，覆盖云端 */
    public static Result forceUpload(Context c) {
        if (!configured()) return new Result(false, "未配置坚果云账号");
        WebDavClient w = client();
        String dir = remoteDir();
        String json = Db.get(c).exportJson();
        byte[] up = encode(json);
        String[] t = up == null ? null : putAuto(w, dir, up);
        if (t == null) return failRes(w, "上传");
        Result r = new Result(true, "已覆盖云端");
        r.pushed = 1;
        afterSync(c, w, t[0], up);
        return r;
    }

    /** 上传后：留一份带时间戳的历史副本，并清理超出保留数量的旧副本 */
    private static void afterSync(Context c, WebDavClient w, String dir, byte[] data) {
        Prefs.put("last_sync", String.valueOf(System.currentTimeMillis()));
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(new Date());
        w.mkdir(dir + "/history");
        w.put(dir + "/history/" + stamp + ".vksy", data);
        try {
            List<WebDavClient.Item> items = w.list(dir + "/history");
            List<String> names = new ArrayList<>();
            /* list 失败时返回 null（错误信息在 lastError），不能当空列表遍历 */
            if (items != null) for (WebDavClient.Item it : items) names.add(it.name);
            Collections.sort(names, Collections.<String>reverseOrder());
            int keep = Prefs.getI("keep_backups", 10);
            for (int i = keep; i < names.size(); i++) w.delete(dir + "/history/" + names.get(i));
        } catch (Exception ignored) { }
    }

    /** 自动同步（进入应用时触发，带节流） */
    public static void maybeAuto(Context c) {
        if (!Prefs.getB("auto_sync", false)) return;
        if (!configured() || Session.key() == null) return;
        long last = 0;
        try { last = Long.parseLong(Prefs.get("last_sync", "0")); } catch (Exception ignored) { }
        if (System.currentTimeMillis() - last < 5 * 60 * 1000L) return;
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try { run(c.getApplicationContext()); } catch (Exception ignored) { }
        });
    }

    public static String lastSyncText() {
        long t = 0;
        try { t = Long.parseLong(Prefs.get("last_sync", "0")); } catch (Exception ignored) { }
        if (t == 0) return "从未同步";
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1) return "刚刚同步";
        if (min < 60) return min + " 分钟前同步";
        if (min < 1440) return (min / 60) + " 小时前同步";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t)) + " 同步";
    }
}
