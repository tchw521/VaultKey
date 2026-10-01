package com.vaultkey.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.util.LinkedHashMap;

/** 极轻量自绘图标：不引入图片资源，全部用 Canvas 描边绘制，可按任意颜色着色 */
public final class Ico {

    /*
     * 图标缓存。
     *
     * 每次 get() 都要建 Bitmap + Canvas + 两个 Paint，还要跑一遍路径绘制 ——
     * 而同一组（名称/颜色/尺寸）的图标在界面上会反复出现：
     * 导航十几项、批量条、设置列表，切一次分类就全部重画一遍。
     *
     * 用 LinkedHashMap 的 access-order 做 LRU：容量 96 足够覆盖整屏，
     * 颜色随主题变化，换肤时调 clearCache() 清掉。
     */
    private static final int CAP = 96;
    private static final java.util.Map<String, Bitmap> MEM =
            new LinkedHashMap<String, Bitmap>(CAP, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Bitmap> e) {
                    return size() > CAP;
                }
            };

    /** 换肤、换主题色后调用：缓存里的图标都是旧颜色的 */
    public static void clearCache() {
        synchronized (MEM) { MEM.clear(); }
    }

    public static Drawable get(Context c, String name, int color, int sizeDp) {
        String key = (name == null ? "more" : name) + "|" + color + "|" + sizeDp;
        synchronized (MEM) {
            Bitmap hit = MEM.get(key);
            if (hit != null) return new BitmapDrawable(c.getResources(), hit);
        }
        float d = c.getResources().getDisplayMetrics().density;
        int s = Math.max(10, (int) (sizeDp * d + 0.5f));
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.082f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        draw(cv, p, f, s, name == null ? "more" : name);
        synchronized (MEM) { MEM.put(key, b); }
        return new BitmapDrawable(c.getResources(), b);
    }

    /** 所有绘制都在 s×s 的正方形内，坐标用 0..1 比例 */
    private static void draw(Canvas cv, Paint p, Paint f, float s, String n) {
        float k = s;
        switch (n) {
            /* ---- 底部导航 ---- */
            case "box": // 保险箱
                rr(cv, p, .10f, .18f, .90f, .86f, .10f, k);
                ci(cv, p, .36f, .52f, .15f, k);
                ln(cv, p, .36f, .40f, .36f, .64f, k);
                ln(cv, p, .24f, .52f, .48f, .52f, k);
                ln(cv, p, .72f, .26f, .72f, .78f, k);
                break;
            case "all": // 多个账号集合
                rr(cv, p, .16f, .14f, .56f, .40f, .07f, k);
                rr(cv, p, .30f, .34f, .70f, .60f, .07f, k);
                rr(cv, p, .44f, .56f, .84f, .82f, .07f, k);
                break;
            case "list":
                for (int i = 0; i < 3; i++) {
                    float y = .30f + i * .20f;
                    ln(cv, p, .24f, y, .82f, y, k);
                    ci(cv, f, .14f, y, .042f, k);
                }
                break;
            case "settings": // 滑块
                ln(cv, p, .14f, .32f, .86f, .32f, k);
                ci(cv, f, .62f, .32f, .11f, k);
                ln(cv, p, .14f, .68f, .86f, .68f, k);
                ci(cv, f, .38f, .68f, .11f, k);
                break;

            /* ---- 分类 ---- */
            case "game":
                rr(cv, p, .10f, .30f, .90f, .70f, .19f, k);
                ln(cv, p, .28f, .42f, .28f, .58f, k);
                ln(cv, p, .20f, .50f, .36f, .50f, k);
                ci(cv, f, .62f, .48f, .052f, k);
                ci(cv, f, .74f, .56f, .052f, k);
                break;
            case "chat":
                rr(cv, p, .12f, .20f, .88f, .64f, .16f, k);
                ln(cv, p, .30f, .64f, .26f, .82f, k);
                ln(cv, p, .26f, .82f, .50f, .64f, k);
                break;
            case "cloud":
                ci(cv, f, .36f, .50f, .13f, k);
                ci(cv, f, .54f, .45f, .17f, k);
                ci(cv, f, .70f, .53f, .11f, k);
                cv.drawRect(.34f * k, .52f * k, .72f * k, .66f * k, f);
                break;
            case "work": // 公文包
                rr(cv, p, .12f, .32f, .88f, .84f, .08f, k);
                ar(cv, p, .36f, .14f, .64f, .42f, 180, 180, k);
                ln(cv, p, .36f, .28f, .36f, .32f, k);
                ln(cv, p, .64f, .28f, .64f, .32f, k);
                ln(cv, p, .38f, .58f, .62f, .58f, k);
                break;
            case "bank":
                ln(cv, p, .50f, .16f, .90f, .42f, k);
                ln(cv, p, .90f, .42f, .10f, .42f, k);
                ln(cv, p, .10f, .42f, .50f, .16f, k);
                cv.drawRect(.16f * k, .50f * k, .84f * k, .82f * k, p);
                for (int i = 0; i < 3; i++) ln(cv, p, .30f + i * .20f, .58f, .30f + i * .20f, .74f, k);
                break;
            case "mail":
                rr(cv, p, .10f, .24f, .90f, .76f, .08f, k);
                ln(cv, p, .10f, .30f, .50f, .56f, k);
                ln(cv, p, .50f, .56f, .90f, .30f, k);
                break;
            case "shop":
                rr(cv, p, .16f, .34f, .84f, .86f, .07f, k);
                ar(cv, p, .32f, .16f, .68f, .50f, 180, 180, k);
                break;
            case "palette": // 换皮肤：半边实心半边描边的圆形色盘（视觉上更像"皮肤/配色"）
                /* 左半实心、右半描边：一眼能看出是"配色方案"而不是别的圆 */
                cv.drawArc(new RectF(.12f * k, .12f * k, .88f * k, .88f * k), 90, 180, true, f);
                cv.drawArc(new RectF(.12f * k, .12f * k, .88f * k, .88f * k), -90, 180, false, p);
                ci(cv, f, .50f, .34f, .085f, k);
                ci(cv, f, .32f, .68f, .062f, k);
                ci(cv, p, .68f, .68f, .062f, k);
                break;
            case "image":
                rr(cv, p, .12f, .22f, .88f, .78f, .09f, k);
                ci(cv, f, .30f, .38f, .062f, k);
                Path im = new Path();
                im.moveTo(.12f * k, .70f * k);
                im.lineTo(.40f * k, .48f * k);
                im.lineTo(.56f * k, .62f * k);
                im.lineTo(.70f * k, .52f * k);
                im.lineTo(.88f * k, .70f * k);
                cv.drawPath(im, p);
                break;
            case "audio":
                ln(cv, p, .38f, .18f, .38f, .60f, k);
                ci(cv, f, .28f, .62f, .11f, k);
                ln(cv, p, .38f, .18f, .76f, .30f, k);
                ln(cv, p, .76f, .30f, .76f, .52f, k);
                ci(cv, f, .66f, .54f, .11f, k);
                break;
            case "web": {
                ci(cv, p, .50f, .50f, .33f, k);
                ln(cv, p, .17f, .50f, .83f, .50f, k);
                RectF ov = new RectF(.17f * k, .30f * k, .83f * k, .70f * k);
                cv.drawArc(ov, 180, 180, false, p);
                cv.drawArc(ov, 0, 180, false, p);
                break;
            }
            case "link": // 链条：两个相扣的环（网址收藏）
                /* 左上环：开口朝右下 */
                cv.drawArc(new RectF(.20f * k, .20f * k, .66f * k, .66f * k),
                        45, 270, false, f);
                /* 右下环：开口朝左上 */
                cv.drawArc(new RectF(.34f * k, .34f * k, .80f * k, .80f * k),
                        225, 270, false, f);
                /* 中间的连接段，让两环看起来是相扣的 */
                cv.drawLine(.38f * k, .62f * k, .62f * k, .38f * k, f);
                break;
            case "app":
                rr(cv, p, .16f, .16f, .46f, .46f, .08f, k);
                rr(cv, p, .54f, .16f, .84f, .46f, .08f, k);
                rr(cv, p, .16f, .54f, .46f, .84f, .08f, k);
                rr(cv, p, .54f, .54f, .84f, .84f, .08f, k);
                break;
            case "card": // 卡片（带磁条）
                rr(cv, p, .10f, .28f, .90f, .76f, .10f, k);
                cv.drawRect(.10f * k, .36f * k, .90f * k, .46f * k, f);
                cv.drawRect(.20f * k, .58f * k, .48f * k, .66f * k, f);
                cv.drawRect(.60f * k, .58f * k, .78f * k, .66f * k, f);
                break;
            case "car":
                rr(cv, p, .12f, .40f, .88f, .78f, .08f, k);
                ln(cv, p, .12f, .62f, .88f, .62f, k);
                ci(cv, p, .28f, .80f, .075f, k);
                ci(cv, p, .72f, .80f, .075f, k);
                ln(cv, p, .28f, .40f, .36f, .24f, k);
                ln(cv, p, .36f, .24f, .74f, .24f, k);
                ln(cv, p, .74f, .24f, .82f, .40f, k);
                break;
            case "passport":
                rr(cv, p, .24f, .14f, .76f, .86f, .07f, k);
                ci(cv, p, .50f, .38f, .14f, k);
                ln(cv, p, .38f, .60f, .62f, .60f, k);
                ln(cv, p, .38f, .72f, .62f, .72f, k);
                break;
            case "more":
            case "custom":
                ci(cv, f, .22f, .50f, .062f, k);
                ci(cv, f, .50f, .50f, .062f, k);
                ci(cv, f, .78f, .50f, .062f, k);
                break;

            /* ---- 功能 ---- */
            case "sync": {
                RectF ov = new RectF(.16f * k, .16f * k, .84f * k, .84f * k);
                cv.drawArc(ov, 200, 250, false, p);
                cv.drawArc(ov, 20, 250, false, p);
                ln(cv, p, .16f, .38f, .16f, .58f, k);
                ln(cv, p, .06f, .48f, .16f, .38f, k);
                ln(cv, p, .26f, .48f, .16f, .58f, k);
                ln(cv, p, .84f, .42f, .84f, .62f, k);
                ln(cv, p, .74f, .52f, .84f, .42f, k);
                ln(cv, p, .94f, .52f, .84f, .62f, k);
                break;
            }
            case "tools": // 扳手
                ci(cv, p, .30f, .72f, .14f, k);
                ln(cv, p, .40f, .82f, .78f, .44f, k);
                ln(cv, p, .68f, .34f, .88f, .54f, k);
                ln(cv, p, .78f, .44f, .82f, .40f, k);
                break;
            case "key":
                ci(cv, p, .32f, .44f, .15f, k);
                ln(cv, p, .44f, .56f, .84f, .78f, k);
                ln(cv, p, .64f, .62f, .70f, .68f, k);
                ln(cv, p, .76f, .70f, .82f, .76f, k);
                break;
            case "lock":
                rr(cv, p, .20f, .44f, .80f, .86f, .10f, k);
                ar(cv, p, .32f, .14f, .68f, .62f, 180, 180, k);
                ci(cv, f, .50f, .60f, .06f, k);
                ln(cv, p, .50f, .66f, .50f, .74f, k);
                break;
            case "fingerprint": {
                ar(cv, p, .20f, .20f, .80f, .80f, 30, 120, k);
                ar(cv, p, .28f, .28f, .72f, .72f, 40, 100, k);
                ar(cv, p, .36f, .36f, .64f, .64f, 50, 80, k);
                ar(cv, p, .20f, .20f, .80f, .80f, 210, 120, k);
                ar(cv, p, .28f, .28f, .72f, .72f, 220, 100, k);
                break;
            }
            case "camera": // 禁止截屏
                rr(cv, p, .26f, .14f, .74f, .86f, .09f, k);
                ci(cv, f, .50f, .26f, .03f, k);
                ln(cv, p, .12f, .88f, .88f, .12f, k);
                break;
            case "shield": {
                Path sh = new Path();
                sh.moveTo(.50f * k, .12f * k);
                sh.lineTo(.84f * k, .26f * k);
                sh.lineTo(.84f * k, .52f * k);
                sh.lineTo(.50f * k, .88f * k);
                sh.lineTo(.16f * k, .52f * k);
                sh.lineTo(.16f * k, .26f * k);
                sh.close();
                cv.drawPath(sh, p);
                ln(cv, p, .36f, .50f, .46f, .62f, k);
                ln(cv, p, .46f, .62f, .66f, .38f, k);
                break;
            }
            case "add":
                ln(cv, p, .50f, .16f, .50f, .84f, k);
                ln(cv, p, .16f, .50f, .84f, .50f, k);
                break;
            case "search":
                ci(cv, p, .42f, .42f, .24f, k);
                ln(cv, p, .60f, .60f, .84f, .84f, k);
                break;
            case "menu":
                for (int i = 0; i < 3; i++) ln(cv, p, .16f, .30f + i * .20f, .84f, .30f + i * .20f, k);
                break;
            case "back":
                ln(cv, p, .62f, .24f, .34f, .50f, k);
                ln(cv, p, .34f, .50f, .62f, .76f, k);
                ln(cv, p, .34f, .50f, .80f, .50f, k);
                break;
            case "float": // 悬浮窗：一个叠在内容上的小窗（外框 + 右上角折角）
                /* 主窗口 */
                cv.drawRoundRect(new RectF(.24f * k, .30f * k, .76f * k, .74f * k),
                        .06f * k, .06f * k, p);
                /* 窗口内一条标题线 */
                cv.drawLine(.32f * k, .42f * k, .68f * k, .42f * k, p);
                cv.drawLine(.32f * k, .53f * k, .60f * k, .53f * k, p);
                /* 右上角折角：表示"浮在别的内容之上" */
                cv.drawLine(.68f * k, .20f * k, .84f * k, .20f * k, f);
                cv.drawLine(.80f * k, .16f * k, .84f * k, .20f * k, f);
                break;
            case "star": {
                Path st = new Path();
                double[] ang = new double[10];
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    double r = (i % 2 == 0) ? .38 : .16;
                    ang[i] = a;
                    float x = (float) (.50 + r * Math.cos(a)) * k;
                    float y = (float) (.52 + r * Math.sin(a)) * k;
                    if (i == 0) st.moveTo(x, y); else st.lineTo(x, y);
                }
                st.close();
                cv.drawPath(st, p);
                break;
            }
            case "trash":
                rr(cv, p, .24f, .30f, .76f, .86f, .06f, k);
                ln(cv, p, .14f, .30f, .86f, .30f, k);
                ln(cv, p, .38f, .30f, .38f, .18f, k);
                ln(cv, p, .38f, .18f, .62f, .18f, k);
                ln(cv, p, .62f, .18f, .62f, .30f, k);
                ln(cv, p, .40f, .42f, .40f, .74f, k);
                ln(cv, p, .60f, .42f, .60f, .74f, k);
                break;
            case "edit":
                ln(cv, p, .20f, .80f, .62f, .38f, k);
                ln(cv, p, .52f, .28f, .74f, .50f, k);
                ln(cv, p, .68f, .32f, .80f, .20f, k);
                break;
            case "copy":
                rr(cv, p, .30f, .30f, .82f, .82f, .08f, k);
                rr(cv, p, .16f, .16f, .62f, .56f, .08f, k);
                break;
            case "eye": {
                RectF ey = new RectF(.10f * k, .32f * k, .90f * k, .68f * k);
                cv.drawOval(ey, p);
                ci(cv, f, .50f, .50f, .13f, k);
                break;
            }
            case "person":
                ci(cv, p, .50f, .32f, .16f, k);
                ar(cv, p, .20f, .44f, .80f, .92f, 180, 180, k);
                break;
            case "download":
                ln(cv, p, .50f, .18f, .50f, .66f, k);
                ln(cv, p, .28f, .48f, .50f, .68f, k);
                ln(cv, p, .72f, .48f, .50f, .68f, k);
                ln(cv, p, .18f, .82f, .82f, .82f, k);
                break;
            case "upload":
                ln(cv, p, .50f, .68f, .50f, .20f, k);
                ln(cv, p, .28f, .38f, .50f, .18f, k);
                ln(cv, p, .72f, .38f, .50f, .18f, k);
                ln(cv, p, .18f, .82f, .82f, .82f, k);
                break;
            case "chevron":
                ln(cv, p, .36f, .28f, .64f, .50f, k);
                ln(cv, p, .64f, .50f, .36f, .72f, k);
                break;
            case "refresh": {
                RectF ov = new RectF(.16f * k, .16f * k, .84f * k, .84f * k);
                cv.drawArc(ov, 240, 260, false, p);
                ln(cv, p, .72f, .16f, .84f, .30f, k);
                ln(cv, p, .84f, .16f, .72f, .30f, k);
                break;
            }
            default:
                ci(cv, p, .50f, .50f, .30f, k);
                break;
        }
    }

    private static void ln(Canvas cv, Paint p, float x0, float y0, float x1, float y1, float s) {
        cv.drawLine(x0 * s, y0 * s, x1 * s, y1 * s, p);
    }

    private static void ci(Canvas cv, Paint p, float cx, float cy, float r, float s) {
        cv.drawCircle(cx * s, cy * s, r * s, p);
    }

    private static void rr(Canvas cv, Paint p, float x0, float y0, float x1, float y1, float r, float s) {
        cv.drawRoundRect(x0 * s, y0 * s, x1 * s, y1 * s, r * s, r * s, p);
    }

    private static void ar(Canvas cv, Paint p, float x0, float y0, float x1, float y1, float a0, float sw, float s) {
        cv.drawArc(new RectF(x0 * s, y0 * s, x1 * s, y1 * s), a0, sw, false, p);
    }
}
