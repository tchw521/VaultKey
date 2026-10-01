package com.vaultkey.util;

import android.content.Context;
import com.vaultkey.data.Db;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 账号匹配：给定域名 / 包名，从库里找出最可能的账号。
 *
 * 抽出来的原因：自动填充服务和悬浮窗都要用同一套匹配规则。
 * 之前这份逻辑写在 VaultAutofillService 内部，悬浮窗没法复用，
 * 若复制一份就会出现「填充给出的候选和悬浮窗不一致」的问题。
 */
public final class Match {

    public static final class Hit {
        public final Db.Entry e;
        public final int score;
        Hit(Db.Entry e, int s) { this.e = e; score = s; }
    }

    /** 最多返回几条候选 */
    private static final int MAX = 8;

    /**
     * 匹配规则（分数越高越可能是当前应用/网站）：
     *   关联 App 包名 100 > 完整域名 90 > 主域名 60 > 名称包含关键词 30
     */
    public static List<Hit> find(Context c, String domain, String pkg) {
        List<Hit> out = new ArrayList<>();
        if (c == null) return out;
        try {
            List<Db.Entry> all = Db.get(c).list(null, false, false, null, 0);
            String host = domain == null ? "" : domain.toLowerCase();
            String pk = pkg == null ? "" : pkg.toLowerCase();
            String main = host.isEmpty() ? "" : Icons.mainDomain(host);
            for (Db.Entry e : all) {
                int sc = 0;
                if (!pk.isEmpty() && e.pkg != null && pk.equals(e.pkg.toLowerCase())) sc = 100;
                else if (!host.isEmpty() && !e.url.isEmpty()
                        && host.equals(Icons.hostOf(e.url))) sc = 90;
                else if (!main.isEmpty() && !e.url.isEmpty()
                        && main.equals(Icons.mainDomain(Icons.hostOf(e.url)))) sc = 60;
                else if (!host.isEmpty() && e.title != null
                        && e.title.toLowerCase().contains(main.isEmpty() ? host : main)) sc = 30;
                if (sc > 0) out.add(new Hit(e, sc));
            }
            Collections.sort(out, new Comparator<Hit>() {
                @Override public int compare(Hit a, Hit b) {
                    if (a.score != b.score) return b.score - a.score;
                    return Long.compare(b.e.mtime, a.e.mtime);
                }
            });
            if (out.size() > MAX) return out.subList(0, MAX);
        } catch (Exception ignored) { }
        return out;
    }

    /** 只取账号名列表，给悬浮窗快速展示用 */
    public static List<Db.Entry> entries(Context c, String domain, String pkg) {
        List<Db.Entry> out = new ArrayList<>();
        for (Hit h : find(c, domain, pkg)) out.add(h.e);
        return out;
    }
}
