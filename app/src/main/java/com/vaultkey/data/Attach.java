package com.vaultkey.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import com.vaultkey.crypto.Crypto;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 图片附件：用主密钥 AES 加密后存本机文件，只把文件名存在数据库里 */
public final class Attach {
    private static final String DIR = "att";
    /** 最长边限制，超过则等比压缩；质量 82，实测一张身份证照片约 60–120KB */
    private static final int MAX = 1280;
    private static final int QUALITY = 82;

    private static File dir(Context c) {
        File f = new File(c.getFilesDir(), DIR);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** 从 Uri 读取 → 压缩 → 加密 → 落盘。返回文件名，失败返回 null */
    public static String save(Context c, Uri uri) {
        byte[] raw = compress(c, uri);
        if (raw == null) return null;
        byte[] enc = Crypto.encrypt(Session.key(), raw);
        if (enc == null) return null;
        String name = System.currentTimeMillis() + "_" + Integer.toHexString(enc.length) + ".vka";
        try {
            FileOutputStream o = new FileOutputStream(new File(dir(c), name));
            o.write(enc);
            o.close();
            return name;
        } catch (Exception e) { return null; }
    }

    /** 读回并解密为 Bitmap（已按目标尺寸采样，避免 OOM） */
    public static Bitmap load(Context c, String name, int targetPx) {
        if (name == null || name.isEmpty()) return null;
        try {
            byte[] enc = readAll(new File(dir(c), name));
            byte[] raw = Crypto.decrypt(Session.key(), enc);
            if (raw == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int s = Math.max(1, Math.max(o.outWidth, o.outHeight) / Math.max(1, targetPx));
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        } catch (Exception e) { return null; }
    }

    public static void remove(Context c, String name) {
        if (name == null || name.isEmpty()) return;
        try { new File(dir(c), name).delete(); } catch (Exception ignored) { }
    }

    /* ---------------- 附件管理页用 ---------------- */

    /** 一个附件文件 */
    public static final class Item {
        public final String name;
        public final long size;      // 加密后占用的磁盘大小
        public final long when;      // 文件名里带的时间戳

        Item(String name, long size, long when) {
            this.name = name; this.size = size; this.when = when;
        }
    }

    /** 磁盘上的全部附件，按时间倒序 */
    public static List<Item> listAll(Context c) {
        List<Item> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile()) continue;
            long when = 0;
            try {
                /* 文件名形如 1735000000000_1a2b.vka */
                String n = f.getName();
                int u = n.indexOf('_');
                if (u > 0) when = Long.parseLong(n.substring(0, u));
            } catch (Exception ignored) { }
            out.add(new Item(f.getName(), f.length(), when));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /**
     * 找出孤儿附件：文件还在，但已经没有任何卡片引用它。
     *
     * 常见成因：删卡片时只删了数据库记录、编辑时中途退出、导入数据覆盖了 imgs 字段。
     * 这些文件加密后少则几十 KB，多则上百 KB，攒多了挺占地方。
     */
    public static List<Item> orphans(Context c) {
        Set<String> used = new HashSet<>();
        for (Db.Card cd : Db.get(c).cardsRaw()) {
            for (String s : Db.imgs(cd.imgs)) {
                if (s != null && !s.isEmpty()) used.add(s);
            }
        }
        List<Item> out = new ArrayList<>();
        for (Item it : listAll(c)) if (!used.contains(it.name)) out.add(it);
        return out;
    }

    /** 附件总共占了多少磁盘 */
    public static long totalSize(Context c) {
        long n = 0;
        for (Item it : listAll(c)) n += it.size;
        return n;
    }

    /** 哪些卡片引用了这个附件（用于删除前提示） */
    public static String owners(Context c, String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Db.Card cd : Db.get(c).cardsRaw()) {
            for (String s : Db.imgs(cd.imgs)) {
                if (name.equals(s)) {
                    if (sb.length() > 0) sb.append("、");
                    sb.append(cd.title == null || cd.title.isEmpty() ? "未命名卡片" : cd.title);
                    break;
                }
            }
        }
        return sb.toString();
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static byte[] compress(Context c, Uri uri) {
        try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            java.io.InputStream i1 = c.getContentResolver().openInputStream(uri);
            if (i1 == null) return null;
            BitmapFactory.decodeStream(i1, null, bo);
            i1.close();
            int w = bo.outWidth, h = bo.outHeight;
            if (w <= 0 || h <= 0) return null;

            java.io.InputStream i2 = c.getContentResolver().openInputStream(uri);
            if (i2 == null) return null;
            Bitmap b;
            if (Math.max(w, h) > MAX) {
                float k = MAX * 1f / Math.max(w, h);
                Bitmap t = BitmapFactory.decodeStream(i2);
                i2.close();
                if (t == null) return null;
                Matrix m = new Matrix();
                m.setScale(k, k);
                b = Bitmap.createBitmap(t, 0, 0, t.getWidth(), t.getHeight(), m, true);
                if (b != t) t.recycle();
            } else {
                b = BitmapFactory.decodeStream(i2);
                i2.close();
            }
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
            b.recycle();
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
        byte[] b = new byte[(int) r.length()];
        r.readFully(b);
        r.close();
        return b;
    }
}
