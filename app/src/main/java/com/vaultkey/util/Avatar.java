package com.vaultkey.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 自定义头像。
 *
 * 存放位置同启动图：app 私有 files 目录。
 * 不放相册 —— 头像会被频繁读取，放私有目录不受存储权限影响，
 * 也不会被其他应用扫到。
 *
 * 尺寸：存 256×256 居中裁剪的方图就够了。头像在界面上最大也就 56dp，
 * 再大纯属浪费空间和解码时间。
 */
public final class Avatar {

    private static final int SIZE = 256;
    private static final int QUALITY = 90;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "avatar.jpg");
    }

    public static boolean has(Context c) { return file(c).exists(); }

    /** 保存头像：居中裁剪成正方形 → 缩到 256 → JPEG 90% */
    public static boolean set(Context c, Bitmap src) {
        if (src == null) return false;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return false;

            /* 居中裁剪正方形：直接缩放会把人拉变形 */
            int side = Math.min(w, h);
            int x = (w - side) / 2;
            int y = (h - side) / 2;
            Bitmap sq = Bitmap.createBitmap(src, x, y, side, side);
            if (sq == null) return false;

            Bitmap out = sq;
            if (side > SIZE) {
                float f = (float) SIZE / side;
                Matrix m = new Matrix();
                m.postScale(f, f);
                Bitmap s = Bitmap.createBitmap(sq, 0, 0, side, side, m, true);
                if (s != null) { out = s; }
            }

            File f = file(c);
            FileOutputStream o = new FileOutputStream(f);
            boolean ok = out.compress(Bitmap.CompressFormat.JPEG, QUALITY, o);
            try { o.close(); } catch (Exception ignored) { }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取头像（已按 256 缩放，不会很大）。没有则返回 null */
    public static Bitmap get(Context c) {
        if (!has(c)) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file(c).getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean clear(Context c) {
        try { return file(c).delete(); } catch (Exception e) { return false; }
    }
}
