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
