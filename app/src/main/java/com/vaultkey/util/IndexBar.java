package com.vaultkey.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** 右侧悬浮 A-Z 字母索引条：支持点击定位、按住上下滑动实时跳转 */
public final class IndexBar extends View {
    public static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
    };

    public interface Cb {
        void onPick(String letter, int index);
        void onUp();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Cb cb;
    private int sel = -1;
    private int accent, normal, hiBg;
    private float cellH;

    public IndexBar(Context c) {
        super(c);
        accent = c.getResources().getColor(com.vaultkey.R.color.accent);
        normal = Ui.attr(c, com.vaultkey.R.attr.textColor2);
        hiBg = Ui.withAlpha(accent, 46);
        p.setTextAlign(Paint.Align.CENTER);
    }

    public void setCb(Cb cb) { this.cb = cb; }
    @Override protected void onMeasure(int w, int h) {
        int want = Ui.dp(getContext(), 30);
        setMeasuredDimension(Math.max(want, w), h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cellH = h * 1f / LETTERS.length;
    }

    @Override protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(Ui.dp(getContext(), 10.5f));
        for (int i = 0; i < LETTERS.length; i++) {
            float cy = cellH * i + cellH / 2f;
            if (i == sel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(hiBg);
                float r = Math.min(cellH, getWidth()) * 0.46f;
                cv.drawRoundRect(new RectF(cx - r, cy - r, cx + r, cy + r), r, r, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == sel ? accent : normal);
            p.setTypeface(i == sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText(LETTERS[i], cx, cy - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int idx = hit(e.getY());
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                setSelected(idx);
                if (cb != null && idx >= 0) cb.onPick(LETTERS[idx], idx);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (idx != sel) {
                    setSelected(idx);
                    if (cb != null && idx >= 0) cb.onPick(LETTERS[idx], idx);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (cb != null) cb.onUp();
                setSelected(-1);
                return true;
        }
        return true;
    }

    private void setSelected(int i) {
        sel = i;
        invalidate();
    }

    private int hit(float y) {
        if (cellH <= 0) return -1;
        int i = (int) (y / cellH);
        if (i < 0) i = 0;
        if (i >= LETTERS.length) i = LETTERS.length - 1;
        return i;
    }

}
