package com.vaultkey.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.view.View;

/**
 * 液态玻璃（Liquid Glass）：用纯 Canvas 绘制模拟 iOS 26 的折射质感
 * —— 半透明底色 + 斜向高光渐变 + 顶部 1px 亮边 + 底部内阴影 + 外缘柔光圈。
 * 不依赖 RenderEffect（Android 12+ 才有），全版本可用。
 */
public final class Liquid {

    /** 主玻璃卡片：返回可直接在 setBackground 上使用的 Drawable */
    public static Drawable glass(Context c, int radiusDp) {
        float r = Ui.dp(c, radiusDp);
        int fill = Ui.attr(c, com.vaultkey.R.attr.cardColor);
        int stroke = Ui.attr(c, com.vaultkey.R.attr.strokeColor);
        boolean dark = Ui.isDark(c);

        // 底层：半透明填充 + 描边
        GradientDrawable base = new GradientDrawable();
        base.setColor(fill);
        base.setCornerRadius(r);
        base.setStroke(Math.max(1, Ui.dp(c, 1)), stroke);

        // 折射层：斜向高光（左上亮 → 右下暗）
        ShapeDrawable gloss = new ShapeDrawable(new RoundRectShape(new float[]{
                r, r, r, r, r, r, r, r}, null, null));
        gloss.getPaint().setShader(new LinearGradient(0, 0, 0, Ui.dp(c, 160),
                Color.argb(dark ? 46 : 78, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));

        // 边缘层：顶部亮边 + 底部暗边
        ShapeDrawable edge = new ShapeDrawable(new RoundRectShape(new float[]{
                r, r, r, r, r, r, r, r}, null, null));
        edge.getPaint().setShader(new LinearGradient(0, 0, 0, Ui.dp(c, 90),
                Color.argb(dark ? 70 : 130, 255, 255, 255),
                Color.argb(dark ? 40 : 26, 0, 0, 0), Shader.TileMode.CLAMP));
        edge.getPaint().setStyle(Paint.Style.STROKE);
        edge.getPaint().setStrokeWidth(Math.max(1, Ui.dp(c, 1.1f)));

        LayerDrawable l = new LayerDrawable(new Drawable[]{base, gloss, edge});
        return l;
    }

    /** 圆形玻璃按钮底（加号、图标徽章） */
    public static Drawable blob(Context c, int sizeDp, int tint) {
        return new BlobDrawable(c, sizeDp, tint);
    }

    /** 绘制在 View 上的圆形玻璃（实时，支持着色变化） */
    public static final class BlobDrawable extends Drawable {
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint;
        private final float pad;

        BlobDrawable(Context c, int sizeDp, int tint) {
            this.tint = tint;
            pad = Ui.dp(c, 2);
            float s = Ui.dp(c, sizeDp);
            gloss.setShader(new LinearGradient(0, 0, 0, s,
                    Color.argb(90, 255, 255, 255), Color.argb(10, 255, 255, 255), Shader.TileMode.CLAMP));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, Ui.dp(c, 1.2f)));
            edge.setShader(new LinearGradient(0, 0, 0, s,
                    Color.argb(160, 255, 255, 255), Color.argb(50, 255, 255, 255), Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas cv) {
            RectF b = new RectF(getBounds());
            RectF inner = new RectF(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad);
            float r = Math.min(inner.width(), inner.height()) / 2f;

            halo.setShader(new RadialGradient(inner.centerX(), inner.centerY(),
                    Math.max(inner.width(), inner.height()) * 0.8f,
                    Ui.withAlpha(tint, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(inner.centerX(), inner.centerY(),
                    Math.max(inner.width(), inner.height()) * 0.78f, halo);

            fill.setColor(Ui.withAlpha(tint, 200));
            cv.drawRoundRect(inner, r, r, fill);
            cv.drawRoundRect(inner, r, r, gloss);
            cv.drawRoundRect(inner, r, r, edge);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { fill.setColorFilter(f); }
    }

}
