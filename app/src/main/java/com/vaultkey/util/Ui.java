package com.vaultkey.util;

import com.vaultkey.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import java.util.Locale;

public final class Ui {
    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int attr(Context c, int a) {
        /* 皮肤优先：主题色相关属性直接由 Skin 计算，切换皮肤即时生效 */
        switch (a) {
            case R.attr.bgColor: return Skin.bg(c);
            case R.attr.bgColor2: return Skin.bg2(c);
            case R.attr.cardColor: return Skin.card(c);
            case R.attr.cardColor2: return Skin.card2(c);
            case R.attr.textColor: return Skin.text(c);
            case R.attr.textColor2: return Skin.text2(c);
            case R.attr.strokeColor: return Skin.stroke(c);
        }
        TypedArray t = c.obtainStyledAttributes(new int[]{a});
        int v = t.getColor(0, Color.GRAY);
        t.recycle();
        return v;
    }

    public static int accent(Context c) { return Skin.accent(c); }
    public static int accent2(Context c) { return Skin.accent2(c); }

    /** 液态玻璃卡片：半透明底色 + 斜向折射高光 + 顶部亮边 + 底部暗边 */
    public static Drawable glass(Context c, int radiusDp, int fillAttr, int strokeAttr) {
        return Liquid.glass(c, radiusDp);
    }

    /**
     * 输入框 / 表单字段的底色。
     *
     * 为什么不能直接用 glass()：深色模式下卡片填充只有约 10% 不透明
     * （Color.argb(26,255,255,255)），拿它当输入框背景时几乎全透明，
     * 输入的内容浮在页面渐变背景上，可读性很差 ——
     * 这就是「添加账号密码页面发虚、影响显示」的根因。
     *
     * 列表卡片要毛玻璃观感没问题（内容静态、有间距），
     * 但输入框是要逐字阅读的交互元素，可读性优先于通透感。
     * 这里改用不透明实色，只留描边和一道很淡的顶部高光维持质感。
     */
    public static Drawable field(Context c, int radiusDp) {
        float r = dp(c, radiusDp);
        boolean dark = isDark(c);
        GradientDrawable base = new GradientDrawable();
        base.setColor(Skin.fieldBg(c));
        base.setCornerRadius(r);
        base.setStroke(Math.max(1, dp(c, 1)), Skin.stroke(c));

        /* 只留一道很淡的顶部高光，保留一点玻璃感又不干扰文字 */
        ShapeDrawable gloss = new ShapeDrawable(new RoundRectShape(
                new float[]{r, r, r, r, r, r, r, r}, null, null));
        gloss.getPaint().setShader(new android.graphics.LinearGradient(0, 0, 0, dp(c, 40),
                Color.argb(dark ? 18 : 44, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP));

        return new LayerDrawable(new Drawable[]{base, gloss});
    }

    /**
     * 弹窗 / 底部面板的底色（不透明）。
     *
     * 与 field() 同理但更"重"一层：弹窗是浮在列表之上的，若用毛玻璃底
     * （深色下仅约 10% 不透明），背后的账号列表会整片透出来，
     * 文字压在两层内容上完全没法读 —— 这就是「详情弹窗和账号列表打架」的根因。
     *
     * 取值比 fieldBg 再抬一档，让弹窗从内容区里明确"浮起"，
     * 同时保留描边与顶部高光，不至于像一块死板的色块。
     */
    public static Drawable sheet(Context c, int radiusDp) {
        float r = dp(c, radiusDp);
        boolean dark = isDark(c);
        int fill = Skin.sheetBg(c);
        int stroke = Skin.stroke(c);

        GradientDrawable base = new GradientDrawable();
        base.setColor(fill);
        base.setCornerRadius(r);
        base.setStroke(Math.max(1, dp(c, 1)), stroke);

        ShapeDrawable gloss = new ShapeDrawable(new RoundRectShape(
                new float[]{r, r, r, r, r, r, r, r}, null, null));
        gloss.getPaint().setShader(new android.graphics.LinearGradient(0, 0, 0, dp(c, 60),
                Color.argb(dark ? 22 : 52, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP));

        return new LayerDrawable(new Drawable[]{base, gloss});
    }

    /**
     * 弹窗内部的子块（账号行 / 密码行）底色。
     *
     * 这类小块若也用毛玻璃，会在已经不透明的弹窗底上出现深浅不一的花斑，
     * 看起来像脏了。这里用「比弹窗底再抬一档」的实色，层次清楚。
     */
    public static Drawable inner(Context c, int radiusDp) {
        float r = dp(c, radiusDp);
        GradientDrawable base = new GradientDrawable();
        base.setColor(Skin.innerBg(c));
        base.setCornerRadius(r);
        base.setStroke(Math.max(1, dp(c, 1)), Skin.stroke(c));
        return base;
    }



    public static Drawable roundRect(int color, float r) {
        ShapeDrawable s = new ShapeDrawable(new RoundRectShape(new float[]{r, r, r, r, r, r, r, r}, null, null));
        s.getPaint().setColor(color);
        return s;
    }

    public static Drawable gradient(int[] colors, float r, int angleDeg) {
        GradientDrawable g = new GradientDrawable(orient(angleDeg), colors);
        g.setCornerRadius(r);
        return g;
    }

    private static GradientDrawable.Orientation orient(int deg) {
        switch (deg) {
            case 0: return GradientDrawable.Orientation.LEFT_RIGHT;
            case 45: return GradientDrawable.Orientation.TL_BR;
            case 90: return GradientDrawable.Orientation.TOP_BOTTOM;
            case 135: return GradientDrawable.Orientation.TR_BL;
            default: return GradientDrawable.Orientation.BL_TR;
        }
    }

    /** 背景：有自定义背景图时用图 + 遮罩，否则底色 + 两团柔光 */
    public static Drawable background(Context c) {
        Bitmap custom = Skin.bgBitmap(c);
        if (custom != null) {
            return bgWithImage(c, custom);
        }
        int bg = attr(c, R.attr.bgColor);
        int a1 = Skin.accent(c);
        int a2 = Skin.accent2(c);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bg, mix(bg, a1, 0.10f), bg});
        GradientDrawable g1 = new GradientDrawable();
        g1.setShape(GradientDrawable.OVAL);
        g1.setColor(withAlpha(a1, isDark(c) ? 90 : 60));
        GradientDrawable g2 = new GradientDrawable();
        g2.setShape(GradientDrawable.OVAL);
        g2.setColor(withAlpha(a2, isDark(c) ? 70 : 45));
        LayerDrawable l = new LayerDrawable(new Drawable[]{base, g1, g2});
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        l.setLayerInset(1, -w / 4, -h / 6, w / 2, h / 2);
        l.setLayerInset(2, w / 2, h / 4, -w / 5, h / 6);
        return l;
    }

    /** 背景图 + 主色遮罩：遮罩保证文字始终可读，浓度可调 */
    private static Drawable bgWithImage(Context c, Bitmap bmp) {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        /* 居中裁剪 */
        float sr = (float) bmp.getWidth() / bmp.getHeight();
        float dr = (float) w / h;
        int sw, sh, sx, sy;
        if (sr > dr) {                    // 图更宽，裁左右
            sh = bmp.getHeight();
            sw = (int) (sh * dr);
            sx = (bmp.getWidth() - sw) / 2;
            sy = 0;
        } else {                          // 图更高，裁上下
            sw = bmp.getWidth();
            sh = (int) (sw / dr);
            sx = 0;
            sy = (bmp.getHeight() - sh) / 2;
        }
        if (sw <= 0 || sh <= 0 || sx < 0 || sy < 0
                || sx + sw > bmp.getWidth() || sy + sh > bmp.getHeight()) {
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), bmp);
        }
        Bitmap cut = Bitmap.createBitmap(bmp, sx, sy, sw, sh);
        android.graphics.drawable.BitmapDrawable img =
                new android.graphics.drawable.BitmapDrawable(c.getResources(), cut);
        img.setGravity(android.view.Gravity.FILL);

        int dim = Skin.bgDim(c);
        GradientDrawable mask = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Skin.bg(c), mix(Skin.bg(c), Skin.accent(c), 0.06f), Skin.bg(c)});
        mask.setAlpha(Math.round(255 * dim / 100f));
        return new LayerDrawable(new Drawable[]{img, mask});
    }

    public static boolean isDark(Context c) {
        return Skin.isDark(c);
    }

    public static int withAlpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int mix(int a, int b, float f) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    /** Q 弹：点击回弹 + 入场弹出 */
    public static void popIn(View v, long delay) {
        v.setScaleX(0.85f); v.setScaleY(0.85f); v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(delay).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    public static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(280)
                            .setInterpolator(new OvershootInterpolator(3.0f)).start();
                    break;
            }
            return false;
        });
    }

    /** 由名称生成稳定的鲜艳色 */
    public static int colorOf(String s) {
        int h = s == null ? 0 : Math.abs(s.hashCode());
        float hue = (h % 360);
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.95f});
    }


}
