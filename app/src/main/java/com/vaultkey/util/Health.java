package com.vaultkey.util;

import android.content.Context;
import com.vaultkey.data.Db;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 密码健康度体检 —— 把「弱密码 / 重复 / 长期未改 / 已泄露」四类问题查出来并打分。
 *
 * 复用说明：强度判定直接调 PasswordGen.strength()，不另写一套规则，
 * 免得工具箱里的强度条和这里算出不同的结果。
 */
public final class Health {

    /** 一类问题 */
    public static final class Issue {
        public final String kind;      // weak / dup / old / breach / empty
        public final String title;     // 账号名
        public final String detail;    // 补充说明
        public final Db.Entry entry;   // 便于点击跳转

        Issue(String kind, String title, String detail, Db.Entry entry) {
            this.kind = kind; this.title = title; this.detail = detail; this.entry = entry;
        }
    }

    /** 体检结果 */
    public static final class Report {
        public final int total;
        public final int score;              // 0~100
        public final List<Issue> issues;
        public final Map<String, Integer> counts;   // 各类问题数量

        Report(int total, int score, List<Issue> issues, Map<String, Integer> counts) {
            this.total = total; this.score = score; this.issues = issues; this.counts = counts;
        }

        public boolean has(String kind) {
            Integer n = counts.get(kind);
            return n != null && n > 0;
        }

        public int of(String kind) {
            Integer n = counts.get(kind);
            return n == null ? 0 : n;
        }

        /** 一句话结论 */
        public String verdict() {
            if (total == 0) return "还没有账号";
            if (issues.isEmpty()) return "全部正常，继续保持";
            if (score >= 80) return "总体良好，有少量待改进";
            if (score >= 50) return "存在一些风险，建议处理";
            return "风险较多，建议尽快处理";
        }
    }

    private static final long YEAR = 365L * 24 * 3600 * 1000;

    private Health() { }

    /**
     * 体检。breached 由外部查完泄露库后传进来（网络请求要异步做），
     * 传 null 表示本次没查。
     */
    public static Report check(Context c, Set<String> breached) {
        List<Db.Entry> all = Db.get(c).list(null, false, false, null, 0);

        List<Issue> issues = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        counts.put("weak", 0); counts.put("dup", 0);
        counts.put("old", 0);  counts.put("breach", 0);
        counts.put("empty", 0);

        /* 重复密码：按密码原文分组，同一密码出现 2 次以上才算问题 */
        Map<String, List<Db.Entry>> byPass = new HashMap<>();
        for (Db.Entry e : all) {
            if (e.pass == null || e.pass.isEmpty()) continue;
            List<Db.Entry> l = byPass.get(e.pass);
            if (l == null) { l = new ArrayList<>(); byPass.put(e.pass, l); }
            l.add(e);
        }

        long now = System.currentTimeMillis();
        Set<String> dupDone = new HashSet<>();

        for (Db.Entry e : all) {
            String name = (e.title == null || e.title.isEmpty())
                    ? (e.user == null ? "未命名" : e.user) : e.title;

            /* 空密码 */
            if (e.pass == null || e.pass.isEmpty()) {
                counts.put("empty", counts.get("empty") + 1);
                issues.add(new Issue("empty", name, "没有保存密码", e));
                continue;
            }

            /* 弱密码 */
            int st = PasswordGen.strength(e.pass);
            if (st < 45) {
                counts.put("weak", counts.get("weak") + 1);
                issues.add(new Issue("weak", name, "强度 " + st + " 分（低于 45）", e));
            }

            /* 重复密码：每组只报一次，把所有用到的账号列出来 */
            List<Db.Entry> same = byPass.get(e.pass);
            if (same != null && same.size() > 1 && dupDone.add(e.pass)) {
                counts.put("dup", counts.get("dup") + same.size());
                StringBuilder sb = new StringBuilder("与 ");
                for (int i = 0; i < same.size(); i++) {
                    if (i > 0) sb.append("、");
                    Db.Entry o = same.get(i);
                    sb.append((o.title == null || o.title.isEmpty()) ? o.user : o.title);
                    if (i == 3 && same.size() > 5) { sb.append(" 等 ").append(same.size()).append(" 个"); break; }
                }
                sb.append(" 相同");
                issues.add(new Issue("dup", name, sb.toString(), e));
            }

            /* 长期未改 */
            long days = (now - e.mtime) / (24 * 3600 * 1000);
            if (now - e.mtime > YEAR) {
                counts.put("old", counts.get("old") + 1);
                issues.add(new Issue("old", name, days / 365 + " 年未更新", e));
            }

            /* 已泄露 */
            if (breached != null && breached.contains(e.pass)) {
                counts.put("breach", counts.get("breach") + 1);
                issues.add(new Issue("breach", name, "出现在公开泄露库中，务必修改", e));
            }
        }

        return new Report(all.size(), score(all.size(), counts), issues, counts);
    }

    /**
     * 打分。扣分按「影响面」加权：
     * 泄露最重（凭据已公开），重复次之（一处泄露全崩），弱密码和长期未改再次。
     */
    private static int score(int total, Map<String, Integer> counts) {
        if (total == 0) return 100;
        double bad = counts.get("breach") * 1.0
                   + counts.get("dup") * 0.7
                   + counts.get("weak") * 0.5
                   + counts.get("old") * 0.25
                   + counts.get("empty") * 0.5;
        /* 问题占比换算成扣分，最多扣 100 */
        double ratio = bad / total;
        int s = (int) Math.round(100 - Math.min(100, ratio * 130));
        return Math.max(0, Math.min(100, s));
    }

    public static String kindName(String k) {
        switch (k) {
            case "breach": return "已泄露";
            case "dup":    return "重复密码";
            case "weak":   return "弱密码";
            case "old":    return "长期未改";
            case "empty":  return "缺密码";
            default:       return k;
        }
    }

    /** 各类问题的说明，报告里给用户看 */
    public static String kindTip(String k) {
        switch (k) {
            case "breach": return "这些密码出现在公开泄露库里，等同于已经公开，请优先修改。";
            case "dup":    return "多个账号共用同一密码，任一泄露则全部受影响。";
            case "weak":   return "强度低于 45 分，容易被猜到或暴力破解。";
            case "old":    return "超过一年没改，建议定期更换。";
            case "empty":  return "这条没存密码，补上才能用自动填充。";
            default:       return "";
        }
    }
}
