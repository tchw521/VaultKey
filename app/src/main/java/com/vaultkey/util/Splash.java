package com.vaultkey.util;

import android.content.Context;
import com.vaultkey.data.Prefs;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 启动图：用户可自选一张图作为开屏画面。
 *
 * 存放位置用 app 私有 files 目录而不是相册：开屏图会被频繁读取，
 * 放私有目录既不受存储权限影响，也不会被其他应用扫到。
 */
public final class Splash {

    private static File file(Context c) {
        return new File(c.getFilesDir(), "splash.jpg");
    }

    public static boolean has(Context c) { return file(c).exists(); }

    /** 保存启动图：压到最长边 1080，质量 85% */
    public static boolean set(Context c, Bitmap src) {
        if (src == null) return false;
        try {
            int max = 1080;
            int w = src.getWidth(), h = src.getHeight();
            float f = Math.min(1f, (float) max / Math.max(w, h));
            Bitmap b = src;
            if (f < 1f) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postScale(f, f);
                Bitmap s = Bitmap.createBitmap(b, 0, 0, w, h, m, true);
                if (s != null) b = s;
            }
            File f2 = file(c);
            FileOutputStream o = new FileOutputStream(f2);
            boolean ok = b.compress(Bitmap.CompressFormat.JPEG, 85, o);
            o.close();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取启动图（已按采样解码，避免大图占内存） */
    public static Bitmap get(Context c, int maxSide) {
        if (!has(c)) return null;
        try {
            File f = file(c);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            int sample = 1;
            while (Math.max(w, h) / sample > maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context c) {
        try { file(c).delete(); } catch (Exception ignored) { }
    }

    /* ---- 开关与时长 ---- */

    public static boolean enabled(Context c) { return Prefs.getB("splash_on", true); }

    public static void setEnabled(Context c, boolean on) { Prefs.putB("splash_on", on); }

    /** 停留时长（毫秒）：0=短 1=默认 2=长 */
    public static long duration(Context c) {
        switch (Prefs.getI("splash_ms", 1)) {
            case 0: return 900;
            case 2: return 2600;
            default: return 1600;
        }
    }

    public static void setDuration(Context c, int idx) { Prefs.putI("splash_ms", idx); }
}
