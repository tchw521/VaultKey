package com.vaultkey.util;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 可拖动的容器。
 *
 * 为什么不用「给根布局挂 OnTouchListener」这种简单做法：
 * 一旦 OnTouchListener 在 ACTION_DOWN 就返回 true，事件就被父布局吃掉了，
 * 内部的「复制」按钮永远收不到点击 —— 要么能拖不能点，要么能点不能拖。
 *
 * 正确做法是重写 onInterceptTouchEvent：
 *   - 手指刚按下、以及位移很小时 → **不拦截**，事件正常派发给子 View（按钮可点）
 *   - 位移超过阈值、确认是拖动 → 才拦截，之后的手势归自己处理
 * 这样点按钮和拖窗口可以共存。
 *
 * 另外长按也在这里做。注意 View 自带的长按检测（CheckForLongPress）实现在
 * View.onTouchEvent() 里，一旦有拦截器参与就可能被跳过，所以统一交给
 * GestureDetector —— 它内部是官方那套延时检测，移动超阈值会自动取消。
 */
public class DragLayout extends LinearLayout {

    public interface Callback {
        /** 手指按下 */
        void onDown(float rawX, float rawY);
        /** 拖动中，(dx,dy) 是相对按下起点的总位移 */
        void onMove(float dx, float dy, float rawX, float rawY);
        /** 抬起 / 取消 */
        void onUp(float dx, float dy, float rawX, float rawY,
                  boolean moved, boolean longPressed, boolean cancelled);
        /** 长按触发 */
        void onLongPress();
        /** 轻点（未移动、未长按、时长够短） */
        void onTap();
    }

    private Callback cb;
    private final GestureDetector gd;
    private final boolean[] longPressed = new boolean[1];

    private float sx, sy;
    private long downMs;
    private boolean moved;
    private boolean dragging;

    /** 内部是否有可纵向滚动的子视图（有则竖向手势优先给滚动） */
    private boolean innerScrollable;

    private static final float SLOP = 8f;
    private static final int TAP_MS = 400;

    public DragLayout(Context c) {
        super(c);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public void onLongPress(MotionEvent e) {
                if (moved) return;
                longPressed[0] = true;
                if (cb != null) cb.onLongPress();
            }
        });
        gd.setIsLongpressEnabled(true);
    }

    public void setCallback(Callback c) { this.cb = c; }

    /** 构建面板内容后调用，让容器知道内部能不能滚动 */
    public void setInnerScrollable(boolean b) { innerScrollable = b; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sx = e.getRawX(); sy = e.getRawY();
                downMs = System.currentTimeMillis();
                moved = false;
                dragging = false;
                longPressed[0] = false;
                gd.onTouchEvent(e);
                if (cb != null) cb.onDown(sx, sy);
                /* 不拦截：先让子 View 有机会处理点击 */
                return false;

            case MotionEvent.ACTION_MOVE: {
                gd.onTouchEvent(e);
                float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                float adx = Math.abs(dx), ady = Math.abs(dy);
                if (adx > SLOP || ady > SLOP) moved = true;

                /* 内部有可滚动内容、且手势以竖向为主 → 让位给滚动 */
                if (innerScrollable && ady > adx) return false;

                if (adx > SLOP || ady > SLOP) {
                    dragging = true;
                    return true;      // 确认是拖动，开始拦截
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gd.onTouchEvent(e);
                return dragging;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (!moved && (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP)) moved = true;
                if (cb != null) cb.onMove(dx, dy, e.getRawX(), e.getRawY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (cb != null) {
                    cb.onUp(dx, dy, e.getRawX(), e.getRawY(),
                            moved, longPressed[0], cancelled);
                }
                if (!cancelled && !moved && !longPressed[0]
                        && System.currentTimeMillis() - downMs < TAP_MS) {
                    if (cb != null) cb.onTap();
                }
                dragging = false;
                moved = false;
                longPressed[0] = false;
                return true;
            }
        }
        return false;
    }

    /** 供外部判断：内部是否有滚动视图 */
    public static boolean hasScrollable(View v) {
        if (v == null) return false;
        if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasScrollable(g.getChildAt(i))) return true;
            }
        }
        return false;
    }
}
