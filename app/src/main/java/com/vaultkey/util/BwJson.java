package com.vaultkey.util;

import com.vaultkey.data.Db;
import java.util.ArrayList;
import java.util.List;

/**
 * Bitwarden 官方导出格式（未加密 JSON）的解析与生成。
 *
 * 格式要点：
 *   {"folders":[{id,name}],"items":[{id,type,favorite,name,notes,
 *     "login":{uris:[{uri}],username,password,totp},
 *     "fields":[{name,value,type}],folderId}]}
 *
 * type：1=登录  2=安全笔记  3=卡片  4=身份
 * 为了不依赖任何 JSON 库，这里用极简递归下降解析器手写，
 * 只处理 Bitwarden 导出文件实际会出现的结构（对象/数组/字符串/数字/布尔/null）。
 * 自己在 JSON 里手写的字符串转义都完整处理，不会漏字段。
 */
public final class BwJson {

    /* ==================== 极简 JSON 解析 ==================== */

    public static final class J {
        /** null=对象 map / 数组 list / 字符串 / Double / Boolean */
        public Object v;
        public J(Object o) { v = o; }

        @SuppressWarnings("unchecked")
        public boolean isObj() { return v instanceof java.util.Map; }

        @SuppressWarnings("unchecked")
        public boolean isArr() { return v instanceof List; }

        @SuppressWarnings("unchecked")
        public java.util.Map<String, J> obj() { return (java.util.Map<String, J>) v; }

        @SuppressWarnings("unchecked")
        public List<J> arr() { return (List<J>) v; }

        public String str() { return v == null ? "" : String.valueOf(v); }

        public J get(String k) {
            if (!isObj()) return null;
            return obj().get(k);
        }

        /** 取字符串，取不到返回 def */
        public String s(String k, String def) {
            J x = get(k);
            return x == null ? def : x.str();
        }

    }

    private static final class P {
        private final String s;
        private int i;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        J val() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return new J(obj());
            if (c == '[') return new J(arr());
            if (c == '"') return new J(str());
            if (c == 't') { i += 4; return new J(Boolean.TRUE); }
            if (c == 'f') { i += 5; return new J(Boolean.FALSE); }
            if (c == 'n') { i += 4; return new J(null); }
            return num();
        }

        private java.util.Map<String, J> obj() {
            java.util.Map<String, J> m = new java.util.LinkedHashMap<>();
            i++;                                  // 吞掉 '{'
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = str();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                J v = val();
                m.put(k, v);
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        private List<J> arr() {
            List<J> l = new ArrayList<>();
            i++;                                  // 吞掉 '['
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        private String str() {
            StringBuilder sb = new StringBuilder();
            if (i >= s.length() || s.charAt(i) != '"') return "";
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '/': sb.append('/'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u': {
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (Exception ignored) { }
                            i += 4;
                        }
                        break;
                    }
                    default: sb.append(e); break;
                }
            }
            return sb.toString();
        }

        private J num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            try { return new J(Double.parseDouble(t)); } catch (Exception e) { return new J(null); }
        }
    }

    public static J parse(String text) {
        if (text == null) return null;
        try { return new P(text).val(); } catch (Exception e) { return null; }
    }

    /* ==================== 导出 ==================== */

    /** 单条记录：用于导出前组装 */
    public static final class Item {
        public String folder = "";
        public String name = "", user = "", pass = "", url = "", notes = "", totp = "";
        public final List<String[]> fields = new ArrayList<>();   // {name, value}
        public boolean fav;

        public Item(String n) { name = n; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 生成 Bitwarden 兼容 JSON。folders 会自动按条目里的分类名去重生成 */
    public static String write(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"folders\": [");
        java.util.Map<String, String> fmap = new java.util.LinkedHashMap<>();
        int fi = 0;
        for (Item it : items) {
            String f = it.folder == null ? "" : it.folder.trim();
            if (f.isEmpty() || fmap.containsKey(f)) continue;
            String id = "f" + (fi++);
            fmap.put(f, id);
        }
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : fmap.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\n    {\"id\": \"").append(e.getValue())
                    .append("\", \"name\": \"").append(esc(e.getKey())).append("\"}");
        }
        sb.append("\n  ],\n  \"items\": [");

        boolean f2 = true;
        for (Item it : items) {
            if (!f2) sb.append(',');
            f2 = false;
            String fid = it.folder == null ? null : fmap.get(it.folder.trim());
            sb.append("\n    {\n      \"id\": \"").append("i").append(Math.abs(it.name.hashCode()))
                    .append("\",\n      \"type\": 1,\n      \"name\": \"").append(esc(it.name))
                    .append("\",\n      \"notes\": ").append(it.notes == null ? "null" : "\"" + esc(it.notes) + "\"")
                    .append(",\n      \"favorite\": ").append(it.fav);
            if (fid != null) sb.append(",\n      \"folderId\": \"").append(fid).append('"');
            sb.append(",\n      \"login\": {\n        \"username\": ")
                    .append(it.user == null || it.user.isEmpty() ? "null" : "\"" + esc(it.user) + "\"")
                    .append(",\n        \"password\": ")
                    .append(it.pass == null || it.pass.isEmpty() ? "null" : "\"" + esc(it.pass) + "\"")
                    .append(",\n        \"totp\": ")
                    .append(it.totp == null || it.totp.isEmpty() ? "null" : "\"" + esc(it.totp) + "\"");
            if (it.url != null && !it.url.isEmpty()) {
                sb.append(",\n        \"uris\": [{\"match\": null, \"uri\": \"")
                        .append(esc(it.url)).append("\"}]");
            }
            sb.append("\n      }");
            /* 自定义字段 */
            if (!it.fields.isEmpty()) {
                sb.append(",\n      \"fields\": [");
                for (int i = 0; i < it.fields.size(); i++) {
                    String[] f = it.fields.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("\n        {\"name\": \"").append(esc(f[0]))
                            .append("\", \"value\": \"").append(esc(f.length > 1 ? f[1] : ""))
                            .append("\", \"type\": 0}");
                }
                sb.append("\n      ]");
            }
            sb.append("\n    }");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /* ==================== 导入 ==================== */

    /** 解析 Bitwarden 导出文件，返回可直接入库的条目草稿 */
    public static List<Db.Entry> read(String text, java.util.Map<String, String> folderNames) {
        List<Db.Entry> out = new ArrayList<>();
        J root = parse(text);
        if (root == null || !root.isObj()) return out;

        /* folder id -> 名称 */
        java.util.Map<String, String> fmap = new java.util.LinkedHashMap<>();
        J folders = root.get("folders");
        if (folders != null && folders.isArr()) {
            for (J f : folders.arr()) {
                if (f == null || !f.isObj()) continue;
                String id = f.s("id", "");
                String nm = f.s("name", "");
                if (!id.isEmpty() && !nm.isEmpty()) fmap.put(id, nm);
            }
        }
        if (folderNames != null) folderNames.clear();

        J items = root.get("items");
        if (items == null || !items.isArr()) return out;
        for (J it : items.arr()) {
            if (it == null || !it.isObj()) continue;
            int type = 0;
            try { type = (int) (double) Double.parseDouble(it.s("type", "0")); } catch (Exception ignored) { }
            /* 只收登录项和笔记（卡片/身份归入密盒卡包更合适，这里跳过避免脏数据） */
            if (type != 1 && type != 2) continue;

            Db.Entry e = new Db.Entry();
            e.uuid = "";
            e.title = it.s("name", "");
            e.notes = it.s("notes", "");
            e.fav = Boolean.TRUE.equals(it.get("favorite") == null ? null : it.get("favorite").v) ? 1 : 0;

            J login = it.get("login");
            if (login != null && login.isObj()) {
                e.user = login.s("username", "");
                e.pass = login.s("password", "");
                e.totp = com.vaultkey.util.Totp.normalize(login.s("totp", ""));
                J uris = login.get("uris");
                if (uris != null && uris.isArr() && !uris.arr().isEmpty()) {
                    J u0 = uris.arr().get(0);
                    if (u0 != null && u0.isObj()) e.url = u0.s("uri", "");
                }
            }
            if (e.title.isEmpty()) e.title = e.user;
            if (e.title.isEmpty()) continue;

            /* 分类：folderId → 名称 */
            String fid = it.s("folderId", "");
            if (!fid.isEmpty() && fmap.containsKey(fid)) {
                String nm = fmap.get(fid);
                if (folderNames != null) folderNames.put(e.title + "\u0000" + e.user, nm);
                e.catName = nm;
            }

            /* 自定义字段 */
            J fields = it.get("fields");
            List<Db.F> cs = new ArrayList<>();
            if (fields != null && fields.isArr()) {
                for (J f : fields.arr()) {
                    if (f == null || !f.isObj()) continue;
                    String k = f.s("name", "");
                    String v = f.s("value", "");
                    if (k.isEmpty() && v.isEmpty()) continue;
                    cs.add(new Db.F(k, v));
                }
            }
            e.custom = Db.packCustoms(cs);
            out.add(e);
        }
        return out;
    }
}
