package com.vaultkey.util;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 圆形图片 Drawable（居中裁剪）。
 *
 * 没用 RoundedBitmapDrawable —— 那个在 support / androidx 包里，
 * 本项目只依赖 android.jar，不引入任何第三方库。
 *
 * 用 BitmapShader 而不是把 Bitmap 本身裁成圆形：
 * 后者要新建一张透明画布，占内存，且缩放时边缘会有锯齿。
 */
public final class CircleDrawable extends Drawable {

    private final Bitmap bmp;
    private final Paint paint;
    private final RectF rect = new RectF();

    public CircleDrawable(Bitmap b) {
        bmp = b;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(sh);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        /*
         * 把 shader 缩放到目标尺寸：以宽高的较小值为基准做居中裁剪，
         * 这样非正方形图不会被拉变形。
         */
        float scale, dx = 0, dy = 0;
        float bw = bmp.getWidth(), bh = bmp.getHeight();
        float vw = rect.width(), vh = rect.height();
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return;

        scale = Math.max(vw / bw, vh / bh);      // CENTER_CROP
        dx = (vw - bw * scale) * 0.5f;
        dy = (vh - bh * scale) * 0.5f;

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        paint.getShader().setLocalMatrix(m);
    }

    @Override public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        float r = Math.min(rect.width(), rect.height()) / 2f;
        canvas.drawCircle(rect.centerX(), rect.centerY(), r, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public int getIntrinsicWidth() { return bmp.getWidth(); }

    @Override public int getIntrinsicHeight() { return bmp.getHeight(); }
}
