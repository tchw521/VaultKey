package com.vaultkey.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** 极简 CSV 解析：兼容 Bitwarden / Chrome / 通用导出格式 */
public final class Csv {

    public static final class Row {
        public String title = "", user = "", pass = "", url = "", notes = "", cat = "", totp = "";
    }

    /** 按 RFC4180 简化解析，支持引号包裹的逗号与换行 */
    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null) return rows;
        List<String> cells = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else inQ = false;
                } else sb.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == ',') { cells.add(sb.toString()); sb.setLength(0); }
                else if (c == '\n') { cells.add(sb.toString()); sb.setLength(0); add(rows, cells); cells.clear(); }
                else if (c != '\r') sb.append(c);
            }
        }
        cells.add(sb.toString());
        add(rows, cells);
        return rows;
    }

    private static void add(List<String[]> rows, List<String> cells) {
        if (cells.size() == 1 && cells.get(0).trim().isEmpty()) return;
        rows.add(cells.toArray(new String[0]));
    }

    public static List<Row> toRows(List<String[]> raw) {
        List<Row> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        String[] head = raw.get(0);
        int iTitle = -1, iUser = -1, iPass = -1, iUrl = -1, iNotes = -1, iCat = -1, iTotp = -1;
        boolean hasHeader = false;
        for (int i = 0; i < head.length; i++) {
            String h = head[i].trim().toLowerCase();
            if (h.equals("name") || h.equals("title") || h.equals("名称") || h.equals("标题")) { iTitle = i; hasHeader = true; }
            else if (h.equals("login_username") || h.equals("username") || h.equals("user") || h.equals("账号") || h.equals("用户名")) { iUser = i; hasHeader = true; }
            else if (h.equals("login_password") || h.equals("password") || h.equals("pass") || h.equals("密码")) { iPass = i; hasHeader = true; }
            else if (h.equals("login_uri") || h.equals("uri") || h.equals("url") || h.equals("网址")) { iUrl = i; hasHeader = true; }
            else if (h.equals("notes") || h.equals("备注")) { iNotes = i; hasHeader = true; }
            else if (h.equals("folder") || h.equals("group") || h.equals("category") || h.equals("分类")) { iCat = i; hasHeader = true; }
            else if (h.equals("login_totp") || h.equals("totp")) { iTotp = i; hasHeader = true; }
        }
        if (!hasHeader) { iTitle = 0; iUser = 1; iPass = 2; iUrl = 3; iNotes = 4; iCat = 5; }
        int start = hasHeader ? 1 : 0;
        for (int r = start; r < raw.size(); r++) {
            String[] a = raw.get(r);
            Row x = new Row();
            x.title = get(a, iTitle);
            x.user = get(a, iUser);
            x.pass = get(a, iPass);
            x.url = get(a, iUrl);
            x.notes = get(a, iNotes);
            x.cat = get(a, iCat);
            x.totp = get(a, iTotp);
            if (x.title.isEmpty() && x.user.isEmpty() && x.pass.isEmpty()) continue;
            out.add(x);
        }
        return out;
    }

    private static String get(String[] a, int i) {
        if (i < 0 || i >= a.length) return "";
        String s = a[i];
        return s == null ? "" : s.trim();
    }

    public static List<Row> read(InputStream in) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return toRows(parse(sb.toString()));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
