package com.vaultkey.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 OCR：识别印刷体数字与大写字母。纯 Java，零依赖，完全离线。
 *
 * 流程：灰度 → 3x3 中值滤波 → 分块自适应二值化 → 去小连通域 → 倾斜校正
 *      → 投影分割（行/字符） → 归一化网格特征 → 与模板匹配
 *
 * 模板不是内置的数据文件，而是运行时用系统字体渲染生成的：
 * 36 个字符 × 3 套字体，共 108 个模板，不占 APK 体积，
 * 且模板字体与目标图片字体天然接近，匹配率高。
 */
public final class MiniOcr {
    private static final int N = 16;                       // 特征网格
    private static final String CHARS = "0123456789XABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String DIGITS = "0123456789X";     // 卡号 / 身份证专用
    public static final String FULL = CHARS;               // 护照号等含字母

    private static Map<Character, List<double[]>> tpl;
    private static boolean ready;

    public static final class Box {
        public int x0, y0, x1, y1;
    }

    public static final class Line {
        public String text;
        public List<Box> boxes;
        Line(String t, List<Box> b) { text = t; boxes = b; }
    }

    /* ---------------- 对外主入口 ---------------- */

    /** 识别图片，返回逐行文本。charset 限制匹配字符集（传 null 表示全部） */
    public static List<Line> read(Context c, Bitmap src, String charset) {
        ensure(c);
        Bitmap b = shrink(src, 1280);
        int w = b.getWidth(), h = b.getHeight();
        int[] a = new int[w * h];
        b.getPixels(a, 0, w, 0, 0, w, h);

        int[] g = median(toGray(a), w, h);
        boolean[] d = binarize(g, w, h);
        dropSmall(d, w, h, Math.max(4, (w * h) / 20000));
        d = deskew(d, w, h);

        int minRow = Math.max(6, h / 60);
        List<Line> out = new ArrayList<>();
        for (int[] r : rows(d, w, h)) {
            if (r[1] - r[0] < minRow) continue;
            StringBuilder sb = new StringBuilder();
            List<Box> boxes = new ArrayList<>();
            for (int[] col : cols(d, w, h, r[0], r[1])) {
                int cw = col[1] - col[0], ch = r[1] - r[0];
                if (cw < 2 || ch < minRow) continue;
                if ((double) ch / cw > 6 || (double) cw / ch > 4) continue;
                double[] f = feat(d, w, h, col[0], r[0], col[1], r[1]);
                if (f == null) continue;
                char m = match(f, charset);
                sb.append(m);
                Box bx = new Box();
                bx.x0 = col[0]; bx.y0 = r[0]; bx.x1 = col[1]; bx.y1 = r[1];
                boxes.add(bx);
            }
            if (sb.length() > 0) out.add(new Line(sb.toString(), boxes));
        }
        return out;
    }

    /** 把多行的识别结果拼成一整串（便于用校验位扫描） */
    public static String flat(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.text).append(' ');
        return sb.toString();
    }

    /* ---------------- 模板：用系统字体渲染生成 ---------------- */

    private static void ensure(Context c) {
        if (ready) return;
        tpl = new HashMap<>();
        String[] tn = {"sans-serif", "serif", "monospace"};
        for (String name : tn) {
            Typeface tf = Typeface.create(name, Typeface.NORMAL);
            for (int i = 0; i < CHARS.length(); i++) {
                char ch = CHARS.charAt(i);
                Bitmap bmp = render(ch, tf, 64);
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] a = new int[w * h];
                bmp.getPixels(a, 0, w, 0, 0, w, h);
                boolean[] d = binarize(toGray(a), w, h);
                double[] f = featOf(d, w, h);
                if (f == null) continue;
                List<double[]> l = tpl.get(ch);
                if (l == null) { l = new ArrayList<>(); tpl.put(ch, l); }
                l.add(f);
                bmp.recycle();
            }
        }
        ready = true;
    }

    private static Bitmap render(char ch, Typeface tf, int size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(Color.BLACK);
        int w = (int) (p.measureText(String.valueOf(ch)) + size * 0.5f);
        int h = (int) (size * 1.6f);
        if (w < 8) w = 8;
        if (h < 8) h = 8;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = h / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(String.valueOf(ch), (w - p.measureText(String.valueOf(ch))) / 2f, base, p);
        return b;
    }

    private static char match(double[] f, String charset) {
        char best = '?';
        double bv = Double.MAX_VALUE;
        for (Map.Entry<Character, List<double[]>> e : tpl.entrySet()) {
            if (charset != null && charset.indexOf(e.getKey()) < 0) continue;
            for (double[] t : e.getValue()) {
                double v = dist(f, t);
                if (v < bv) { bv = v; best = e.getKey(); }
            }
        }
        return best;
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < N * N; i++) { double t = a[i] - b[i]; s += t * t; }
        double hd = a[N * N] - b[N * N];
        s += hd * hd * 1.6;
        double ar = a[N * N + 1] - b[N * N + 1];
        s += ar * ar * 0.8;
        return s;
    }

    /* ---------------- 图像处理 ---------------- */

    private static Bitmap shrink(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= max) return src;
        float k = (float) max / m;
        int nw = Math.max(1, (int) (w * k)), nh = Math.max(1, (int) (h * k));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static int[] toGray(int[] a) {
        int[] g = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            int c = a[i];
            g[i] = (int) (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255));
        }
        return g;
    }

    private static int[] median(int[] g, int w, int h) {
        int[] out = new int[w * h];
        int[] win = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) win[n++] = g[y * w + x];
                        else win[n++] = g[ny * w + nx];
                    }
                }
                java.util.Arrays.sort(win);
                out[y * w + x] = win[4];
            }
        }
        return out;
    }

    private static boolean[] binarize(int[] g, int w, int h) {
        boolean[] d = new boolean[w * h];
        long[] S = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += g[y * w + x];
                S[(y + 1) * (w + 1) + (x + 1)] = S[y * (w + 1) + (x + 1)] + row;
            }
        }
        int bs = Math.max(16, Math.min(w, h) / 8);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - bs / 2), x2 = Math.min(w - 1, x + bs / 2);
                int y1 = Math.max(0, y - bs / 2), y2 = Math.min(h - 1, y + bs / 2);
                long s = S[(y2 + 1) * (w + 1) + (x2 + 1)] - S[y1 * (w + 1) + (x2 + 1)]
                        - S[(y2 + 1) * (w + 1) + x1] + S[y1 * (w + 1) + x1];
                long area = (long) (x2 - x1 + 1) * (y2 - y1 + 1);
                d[y * w + x] = g[y * w + x] < s / area - 12;
            }
        }
        return d;
    }

    private static void dropSmall(boolean[] d, int w, int h, int minArea) {
        boolean[] vis = new boolean[w * h];
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        for (int i = 0; i < w * h; i++) {
            if (!d[i] || vis[i]) continue;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            List<Integer> cells = new ArrayList<>();
            vis[i] = true;
            q.add(i);
            cells.add(i);
            while (!q.isEmpty()) {
                int p = q.poll();
                int x = p % w, y = p / w;
                for (int k = 0; k < 4; k++) {
                    int nx = x + dx[k], ny = y + dy[k];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int np = ny * w + nx;
                    if (d[np] && !vis[np]) { vis[np] = true; q.add(np); cells.add(np); }
                }
            }
            if (cells.size() < minArea) for (int c : cells) d[c] = false;
        }
    }

    private static double projVar(boolean[] d, int w, int h) {
        double[] p = new double[h];
        for (int y = 0; y < h; y++) {
            int c = 0;
            for (int x = 0; x < w; x++) if (d[y * w + x]) c++;
            p[y] = c;
        }
        double m = 0;
        for (double v : p) m += v;
        m /= h;
        double s = 0;
        for (double v : p) s += (v - m) * (v - m);
        return s;
    }

    private static boolean[] rotBin(boolean[] d, int w, int h, double deg) {
        boolean[] o = new boolean[w * h];
        double rad = Math.toRadians(-deg);
        double cs = Math.cos(rad), sn = Math.sin(rad);
        double cx = w / 2.0, cy = h / 2.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double rx = (x - cx) * cs - (y - cy) * sn + cx;
                double ry = (x - cx) * sn + (y - cy) * cs + cy;
                int ix = (int) Math.round(rx), iy = (int) Math.round(ry);
                if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                o[y * w + x] = d[iy * w + ix];
            }
        }
        return o;
    }

    private static boolean[] deskew(boolean[] d, int w, int h) {
        int sw = Math.max(32, w / 2), sh = Math.max(32, h / 2);
        boolean[] sd = new boolean[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int sy = Math.min(h - 1, y * 2);
                int sx = Math.min(w - 1, x * 2);
                sd[y * sw + x] = d[sy * w + sx];
            }
        }
        double bestA = 0, bestV = -1;
        for (double a = -8; a <= 8; a += 0.5) {
            double v = projVar(rotBin(sd, sw, sh, a), sw, sh);
            if (v > bestV) { bestV = v; bestA = a; }
        }
        if (Math.abs(bestA) < 0.4) return d;
        return rotBin(d, w, h, bestA);
    }

    private static List<int[]> rows(boolean[] d, int w, int h) {
        int[] proj = new int[h];
        for (int y = 0; y < h; y++) {
            int c = 0;
            for (int x = 0; x < w; x++) if (d[y * w + x]) c++;
            proj[y] = c;
        }
        List<int[]> out = new ArrayList<>();
        int s = -1;
        for (int y = 0; y < h; y++) {
            boolean on = proj[y] > 1;
            if (on && s < 0) s = y;
            if (!on && s >= 0) { if (y - s >= 4) out.add(new int[]{s, y}); s = -1; }
        }
        if (s >= 0 && h - s >= 4) out.add(new int[]{s, h});
        return out;
    }

    private static List<int[]> cols(boolean[] d, int w, int h, int y0, int y1) {
        int[] proj = new int[w];
        for (int x = 0; x < w; x++) {
            int c = 0;
            for (int y = y0; y < y1; y++) if (d[y * w + x]) c++;
            proj[x] = c;
        }
        List<int[]> raw = new ArrayList<>();
        int s = -1;
        for (int x = 0; x < w; x++) {
            boolean on = proj[x] > 0;
            if (on && s < 0) s = x;
            if (!on && s >= 0) { raw.add(new int[]{s, x}); s = -1; }
        }
        if (s >= 0) raw.add(new int[]{s, w});

        if (raw.size() >= 2) {
            List<Integer> ws = new ArrayList<>();
            for (int[] r : raw) ws.add(r[1] - r[0]);
            java.util.Collections.sort(ws);
            int med = ws.get(ws.size() / 2);
            List<int[]> out = new ArrayList<>();
            for (int[] r : raw) {
                int ww = r[1] - r[0];
                if (ww > med * 1.7 && med > 2) {
                    int n = Math.min(4, Math.round((float) ww / med));
                    int prev = r[0];
                    for (int k = 1; k < n; k++) {
                        int target = r[0] + ww * k / n;
                        int best = target, bv = Integer.MAX_VALUE;
                        for (int x = target - med / 2; x <= target + med / 2; x++) {
                            if (x <= prev || x >= r[1]) continue;
                            if (proj[x] < bv) { bv = proj[x]; best = x; }
                        }
                        out.add(new int[]{prev, best});
                        prev = best;
                    }
                    out.add(new int[]{prev, r[1]});
                } else out.add(r);
            }
            return out;
        }
        return raw;
    }

    private static double[] feat(boolean[] d, int w, int h, int x0, int y0, int x1, int y1) {
        int cw = x1 - x0, ch = y1 - y0;
        if (cw <= 0 || ch <= 0) return null;
        boolean[] sub = new boolean[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                sub[y * cw + x] = d[(y0 + y) * w + (x0 + x)];
            }
        }
        double[] f = new double[N * N + 2];
        for (int gy = 0; gy < N; gy++) {
            for (int gx = 0; gx < N; gx++) {
                int cnt = 0, tot = 0;
                int sx0 = cw * gx / N, sx1 = Math.max(sx0 + 1, cw * (gx + 1) / N);
                int sy0 = ch * gy / N, sy1 = Math.max(sy0 + 1, ch * (gy + 1) / N);
                for (int y = sy0; y < sy1; y++) {
                    for (int x = sx0; x < sx1; x++) { tot++; if (sub[y * cw + x]) cnt++; }
                }
                f[gy * N + gx] = tot == 0 ? 0 : (double) cnt / tot;
            }
        }
        f[N * N] = holes(sub, cw, ch);
        f[N * N + 1] = (double) cw / ch;
        return f;
    }

    private static double[] featOf(boolean[] d, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (d[y * w + x]) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return null;
        return feat(d, w, h, x0, y0, x1 + 1, y1 + 1);
    }

    private static int holes(boolean[] fg, int w, int h) {
        boolean[] vis = new boolean[w * h];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        for (int x = 0; x < w; x++) {
            if (!fg[x] && !vis[x]) { vis[x] = true; q.add(x); }
            int i2 = (h - 1) * w + x;
            if (!fg[i2] && !vis[i2]) { vis[i2] = true; q.add(i2); }
        }
        for (int y = 0; y < h; y++) {
            int i1 = y * w;
            if (!fg[i1] && !vis[i1]) { vis[i1] = true; q.add(i1); }
            int i2 = y * w + w - 1;
            if (!fg[i2] && !vis[i2]) { vis[i2] = true; q.add(i2); }
        }
        while (!q.isEmpty()) {
            int p = q.poll();
            int x = p % w, y = p / w;
            for (int k = 0; k < 4; k++) {
                int nx = x + dx[k], ny = y + dy[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int np = ny * w + nx;
                if (!fg[np] && !vis[np]) { vis[np] = true; q.add(np); }
            }
        }
        int n = 0;
        for (int i = 0; i < w * h; i++) {
            if (!fg[i] && !vis[i]) {
                n++;
                vis[i] = true;
                q.add(i);
                while (!q.isEmpty()) {
                    int p = q.poll();
                    int x = p % w, y = p / w;
                    for (int k = 0; k < 4; k++) {
                        int nx = x + dx[k], ny = y + dy[k];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int np = ny * w + nx;
                        if (!fg[np] && !vis[np]) { vis[np] = true; q.add(np); }
                    }
                }
            }
        }
        return n;
    }

    /* ---------------- 校验与字段提取 ---------------- */

    public static boolean luhn(String s) {
        if (s == null || s.length() < 13) return false;
        int sum = 0, alt = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int n = s.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (alt == 1) { n *= 2; if (n > 9) n -= 9; }
            alt ^= 1;
            sum += n;
        }
        return sum % 10 == 0;
    }

    public static boolean idOk(String s) {
        if (s == null || s.length() != 18) return false;
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] ck = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) return false;
            sum += d * w[i];
        }
        char last = s.charAt(17);
        if (last >= 'a' && last <= 'z') last = (char) (last - 32);
        return ck[sum % 11] == last;
    }

    /** 扫出符合 Luhn 的银行卡号 */
    public static String pickCard(String raw) {
        if (raw == null) return null;
        for (String seg : raw.split("[^0-9]+")) {
            if (seg.isEmpty()) continue;
            for (int len : new int[]{19, 17, 16, 15, 14, 13}) {
                for (int i = 0; i + len <= seg.length(); i++) {
                    String sub = seg.substring(i, i + len);
                    if (luhn(sub)) return sub;
                }
            }
        }
        return null;
    }

    /** 扫出校验位正确的身份证号 */
    public static String pickId(String raw) {
        if (raw == null) return null;
        for (String seg : raw.split("[^0-9Xx]+")) {
            for (int i = 0; i + 18 <= seg.length(); i++) {
                String sub = seg.substring(i, i + 18).toUpperCase();
                if (idOk(sub)) return sub;
            }
        }
        return null;
    }

    /** 扫出 MM/YY 或 MM/YYYY 形式的有效期 */
    public static String pickExpiry(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(0[1-9]|1[0-2])[/-](20)?([0-9]{2})").matcher(s);
        if (m.find()) {
            String mm = m.group(1);
            String yy = m.group(3);
            return mm + "/" + yy;
        }
        return null;
    }

    /** 扫出护照号（字母开头 + 数字） */
    public static String pickPassport(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Z][0-9]{7,9}").matcher(raw.replace(" ", "").toUpperCase());
        if (m.find()) return m.group();
        return null;
    }

    /** 身份证号 -> 出生日期 + 性别，顺带算出来给用户确认 */
    public static String idBirth(String id) {
        if (!idOk(id)) return null;
        return id.substring(6, 10) + "-" + id.substring(10, 12) + "-" + id.substring(12, 14);
    }

    public static String idGender(String id) {
        if (!idOk(id)) return null;
        int n = id.charAt(16) - '0';
        return (n % 2 == 1) ? "男" : "女";
    }
}
