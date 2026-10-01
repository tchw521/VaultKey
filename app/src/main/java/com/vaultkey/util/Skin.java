package com.vaultkey.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.File;
import com.vaultkey.R;
import com.vaultkey.data.Prefs;

/** 主题皮肤：内置方案 + 自定义配色。存 Prefs，切换即时生效 */
public final class Skin {

    /** 一套皮肤：主色、辅色、底色模式 */
    public static final class Theme {
        public String key, name;
        public int accent, accent2;
        public boolean dark;
        /** 底色：0=跟随主色调 1=纯白 2=纯黑 */
        public int base;

        Theme(String k, String n, int a, int a2, boolean d, int b) {
            key = k; name = n; accent = a; accent2 = a2; dark = d; base = b;
        }
    }

    public static final Theme[] THEMES = {
            new Theme("mint", "薄荷清新", 0xFF00B8A0, 0xFF5EDCC4, false, 0),
            new Theme("ocean", "深海幽蓝", 0xFF2E7DF6, 0xFF6FB1FF, false, 0),
            new Theme("grape", "葡萄紫韵", 0xFF7C5CFF, 0xFFB39DFF, false, 0),
            new Theme("sakura", "樱花粉", 0xFFF06292, 0xFFFFA0C0, false, 0),
            new Theme("amber", "琥珀暖橙", 0xFFF59E0B, 0xFFFFCB6B, false, 0),
            new Theme("forest", "森林墨绿", 0xFF16A34A, 0xFF6EE7A8, false, 0),
            new Theme("night", "暗夜薄荷", 0xFF00E0C0, 0xFF5EDCC4, true, 2),
            new Theme("midnight", "午夜幽蓝", 0xFF4C9AFF, 0xFF7FC4FF, true, 2),
            new Theme("ink", "墨黑（省电）", 0xFFE8E8E8, 0xFFB0B0B0, true, 2),
    };

    public static final String CUSTOM = "custom";

    /** 明暗模式：0=跟随系统 1=浅色 2=深色。这是深浅的唯一来源 */
    public static final int MODE_SYSTEM = 0, MODE_LIGHT = 1, MODE_DARK = 2;

    public static int mode() {
        migrateOnce();
        return Prefs.getI("skin_mode", MODE_SYSTEM);
    }

    /**
     * 老版本（2.10 及更早）的明暗存在另一个 pref「theme」里。
     * 升级后如果直接读新的 skin_mode，用户原来的深浅选择会丢失、回到跟随系统。
     * 这里做一次迁移：只在从没设过 skin_mode 时才搬过来。
     */
    private static boolean migrated;
    private static void migrateOnce() {
        if (migrated || Prefs.has("skin_mode")) { migrated = true; return; }
        int t = Prefs.getI("theme", 0);
        if (t == 1 || t == 2) Prefs.putI("skin_mode", t);
        migrated = true;
    }

    public static void setMode(int m) {
        Prefs.putI("skin_mode", m);
        bump();
    }


    /**
     * 是否深色。
     *
     * 之前这里只信皮肤自带的 dark 字段；而设置里另有一个「主题」开关，写的是
     * 另一个 pref，走的是 values-night 那套。两套系统各管各的，且所有颜色
     * 最终都由 Skin 计算（优先级更高），于是「主题」怎么切、界面都不变 ——
     * 这就是「深浅模式不生效」的根因。
     * 现在统一：皮肤管配色，skin_mode 管明暗，互不冲突。
     */
    public static boolean isDark(Context c) {
        int m = mode();
        if (m == MODE_LIGHT) return false;
        if (m == MODE_DARK) return true;
        try {
            int ui = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }

    public static Theme current(Context c) {
        boolean dark = isDark(c);
        String k = Prefs.getS("skin", "mint");
        if (CUSTOM.equals(k)) {
            int a = Prefs.getI("skin_a", 0xFF00B8A0);
            int a2 = Prefs.getI("skin_a2", 0xFF5EDCC4);
            return new Theme(CUSTOM, "自定义", a, a2, dark, Prefs.getI("skin_base", 0));
        }
        for (Theme t : THEMES) if (t.key.equals(k)) {
            /* 皮肤自带的明暗仅作初值，实际以 skin_mode 为准 */
            return new Theme(t.key, t.name, t.accent, t.accent2, dark, t.base);
        }
        return THEMES[0];
    }


    /** 皮肤版本号：每次改动 +1，各 Activity 在 onResume 比对，不同则整页重建 */
    private static final String VER_KEY = "skin_ver";

    public static int ver() { return Prefs.getI(VER_KEY, 0); }

    /** 任何改动皮肤的操作都必须调它，否则界面不会刷新 */
    private static void bump() { Prefs.putI(VER_KEY, ver() + 1); }

    public static void apply(Context c, Theme t) {
        Prefs.putS("skin", t.key);
        bump();
        if (CUSTOM.equals(t.key)) {
            Prefs.putI("skin_a", t.accent);
            Prefs.putI("skin_a2", t.accent2);
            Prefs.putI("skin_base", t.base);
        } else {
            /* 选内置皮肤时把它的明暗写成初始值，用户之后仍可在「明暗」里改 */
            Prefs.putI("skin_mode", t.dark ? MODE_DARK : MODE_LIGHT);
        }
    }

    public static void setCustom(Context c, int a, int a2, int base) {
        apply(c, new Theme(CUSTOM, "自定义", a, a2, isDark(c), base));
    }

    /* ---------------- 背景图 ---------------- */

    /** 背景图存放位置（app 私有文件，不进相册） */
    private static File bgFile(Context c) {
        return new File(c.getFilesDir(), "skin_bg.jpg");
    }

    public static boolean hasBg(Context c) { return bgFile(c).exists(); }

    /** 把 Bitmap 存成背景图。返回是否成功 */
    public static boolean setBg(Context c, Bitmap b) {
        try {
            int max = 1280;
            int w = b.getWidth(), h = b.getHeight();
            int m = Math.max(w, h);
            Bitmap t = b;
            if (m > max) {
                float k = (float) max / m;
                t = Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
            }
            File f = bgFile(c);
            java.io.OutputStream o = new java.io.FileOutputStream(f);
            t.compress(Bitmap.CompressFormat.JPEG, 88, o);
            o.close();
            if (t != b) t.recycle();
            Prefs.putI("skin_bg_dim", Prefs.getI("skin_bg_dim", 34));
            bump();
            return true;
        } catch (Exception e) { return false; }
    }

    public static void clearBg(Context c) {
        try { bgFile(c).delete(); } catch (Exception ignored) { }
        bump();
    }

    /** 背景图上的遮罩浓度 0~70，越大底色越重、图片越淡（保证文字可读） */
    public static int bgDim(Context c) { return Prefs.getI("skin_bg_dim", 34); }

    public static void setBgDim(Context c, int v) {
        Prefs.putI("skin_bg_dim", Math.max(0, Math.min(70, v)));
        bump();
    }

    /** 读取背景图（已按屏幕尺寸采样，避免 OOM） */
    public static Bitmap bgBitmap(Context c) {
        if (!hasBg(c)) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(bgFile(c).getAbsolutePath(), o);
            int sw = c.getResources().getDisplayMetrics().widthPixels;
            int sh = c.getResources().getDisplayMetrics().heightPixels;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > Math.max(sw, sh)) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(bgFile(c).getAbsolutePath(), o);
        } catch (Exception e) { return null; }
    }

    public static int accent(Context c) {
        return current(c).accent;
    }

    public static int accent2(Context c) {
        return current(c).accent2;
    }

    /** 9 宫格默认色板 */
    public static final int[] PALETTE = {
            0xFF00B8A0, 0xFF2E7DF6, 0xFF7C5CFF, 0xFFF06292,
            0xFFF59E0B, 0xFF16A34A, 0xFF38BDF8, 0xFFEF4444, 0xFF94A3B8
    };

    /* ---------------- 派生色 ---------------- */

    /**
     * 底色。
     * 「纯白」只在浅色生效、「纯黑」只在深色生效：反过来会让底色与文字同为浅色
     * （或同为深色），文字直接看不见。例如暗夜薄荷这类 base=2 的皮肤，
     * 用户手动切到浅色时，曾经会得到黑底 + 白字卡片 + 白字 → 全白一片。
     */
    public static int bg(Context c) {
        Theme t = current(c);
        if (t.base == 1 && !t.dark) return 0xFFFAFCFC;
        if (t.base == 2 && t.dark) return 0xFF000000;
        return t.dark ? mix(0xFF08110F, t.accent, 0.07f) : mix(0xFFF4FAF9, t.accent, 0.06f);
    }

    public static int bg2(Context c) {
        Theme t = current(c);
        if (t.base == 1 && !t.dark) return 0xFFFFFFFF;
        if (t.base == 2 && t.dark) return 0xFF0A0A0A;
        return t.dark ? mix(0xFF0E1A18, t.accent, 0.10f) : 0xFFFFFFFF;
    }

    public static int card(Context c) {
        Theme t = current(c);
        return t.dark ? Color.argb(26, 255, 255, 255) : Color.argb(240, 255, 255, 255);
    }

    /**
     * 输入框 / 表单字段的底色（不透明）。
     *
     * 注意与 card() 的区别：card() 在深色下只有约 10% 不透明，是给列表卡片
     * 做毛玻璃用的；输入框要逐字阅读，必须是不透明实色，否则文字压在页面
     * 背景的渐变和柔光上会发虚、看不清。
     *
     * 取值：在页面底色基础上抬一档亮度，让输入框从背景里"浮"出来，
     * 同时与背景同色系，不至于突兀。
     */
    public static int fieldBg(Context c) {
        Theme t = current(c);
        int b = bg(c);
        if (t.dark) {
            /* 深色：底色抬亮约 8%，得到比背景略浅的实色 */
            return mix(b, 0xFFFFFFFF, 0.09f) | 0xFF000000;
        }
        /* 浅色：接近纯白，与页面浅底拉开一点层次 */
        return mix(0xFFFFFFFF, t.accent, 0.02f) | 0xFF000000;
    }

    /**
     * 弹窗 / 浮层的底色（不透明）。比 fieldBg 再抬一档，
     * 让弹窗从下方内容里明确"浮起"，避免背后的列表透出来干扰阅读。
     */
    public static int sheetBg(Context c) {
        Theme t = current(c);
        int b = bg(c);
        if (t.dark) return mix(b, 0xFFFFFFFF, 0.14f) | 0xFF000000;
        return mix(0xFFFFFFFF, t.accent, 0.015f) | 0xFF000000;
    }

    /** 弹窗内部子块底色：比 sheetBg 再抬一档，形成 "弹窗 → 行" 的层次 */
    public static int innerBg(Context c) {
        Theme t = current(c);
        int b = bg(c);
        if (t.dark) return mix(b, 0xFFFFFFFF, 0.20f) | 0xFF000000;
        return mix(0xFFFFFFFF, t.accent, 0.055f) | 0xFF000000;
    }

    public static int card2(Context c) {
        Theme t = current(c);
        return t.dark ? Color.argb(34, 255, 255, 255) : mix(0xFFF7FBFA, t.accent, 0.08f);
    }

    /** 文字色只由明暗决定：深色配浅字，浅色配深字，保证任何底色下都可读 */
    public static int text(Context c) {
        Theme t = current(c);
        if (t.dark) return t.base == 2 ? 0xFFF2F2F2 : 0xFFEFF6F4;
        return mix(0xFF0E1A18, t.accent, 0.06f);
    }

    public static int text2(Context c) {
        Theme t = current(c);
        if (t.dark) return t.base == 2 ? 0xFF9AA3A1 : 0xFF93A6A2;
        return mix(0xFF6A7C79, t.accent, 0.10f);
    }

    public static int stroke(Context c) {
        Theme t = current(c);
        return t.dark ? Color.argb(40, 255, 255, 255)
                : Ui.withAlpha(t.accent, 90);
    }

    public static int mix(int a, int b, float f) {
        int r = (int) (Color.red(a) * (1 - f) + Color.red(b) * f);
        int g = (int) (Color.green(a) * (1 - f) + Color.green(b) * f);
        int bl = (int) (Color.blue(a) * (1 - f) + Color.blue(b) * f);
        return Color.rgb(clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
