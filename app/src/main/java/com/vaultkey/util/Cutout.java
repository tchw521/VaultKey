package com.vaultkey.util;

import android.app.Activity;
import com.vaultkey.data.Prefs;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * 刘海屏 / 挖孔屏 / 水滴屏适配。
 *
 * 需要解决两类问题：
 *   1. 顶部内容被刘海遮住 —— 需要给根布局加顶部 inset
 *   2. 想让背景延伸到刘海区域（沉浸式） —— 需要设置 layoutInDisplayCutoutMode
 *
 * 这里采用「背景延伸、内容避让」：让窗口画到状态栏后面（观感更整体），
 * 但给内容根布局加对应的 padding，保证按钮和文字不会被挖孔切掉。
 */
public final class Cutout {

    /** 是否开启「背景延伸到刘海区」 */
    public static boolean extend(Context c) {
        return Prefs.getB("cutout_extend", true);
    }

    public static void setExtend(boolean on) {
        Prefs.putB("cutout_extend", on);
    }

    /**
     * 允许窗口绘制到刘海区域。
     * 必须在 setContentView 之前调用才稳定生效。
     */
    public static void apply(Activity a) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = extend(a)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            a.getWindow().setAttributes(lp);
        } catch (Exception ignored) { }
    }

    /** 设备是否有刘海（用于设置页文案提示） */
    public static boolean has(Context c) {
        if (Build.VERSION.SDK_INT < 28) {
            /* 部分厂商在 Android 9 以下也有刘海，用常见字段兜底判断 */
            try {
                String s = Settings.Global.getString(c.getContentResolver(), "display_notch_status");
                if ("1".equals(s)) return true;
            } catch (Exception ignored) { }
            return false;
        }
        try {
            Activity a = c instanceof Activity ? (Activity) c : null;
            if (a == null) return false;
            WindowInsets in = a.getWindow().getDecorView().getRootWindowInsets();
            if (in == null) return false;
            DisplayCutout dc = in.getDisplayCutout();
            return dc != null && !dc.getBoundingRects().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 刘海安全区（左/上/右/下 需要避让的像素数），无刘海时返回 0 */
    public static int[] safe(Activity a) {
        int[] r = {0, 0, 0, 0};
        if (Build.VERSION.SDK_INT < 28) return r;
        try {
            WindowInsets in = a.getWindow().getDecorView().getRootWindowInsets();
            if (in == null) return r;
            DisplayCutout dc = in.getDisplayCutout();
            if (dc == null) return r;
            r[1] = dc.getSafeInsetTop();
            r[3] = dc.getSafeInsetBottom();
            r[0] = dc.getSafeInsetLeft();
            r[2] = dc.getSafeInsetRight();
        } catch (Exception ignored) { }
        return r;
    }

    /** 状态栏高度（无刘海的普通机型也需要避让状态栏） */
    public static int statusBarH(Context c) {
        int res = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return res > 0 ? c.getResources().getDimensionPixelSize(res) : 0;
    }

    /**
     * 给根布局加避让 padding。
     *
     * 关键点：只在「延伸到刘海区」时才需要补顶部 padding。
     * 如果没延伸（MODE_DEFAULT），系统已经把整个窗口推到刘海下方了，
     * 再补一次 padding 会导致顶部空出一大块。
     */
    public static void padRoot(final Activity a, final View root, final int baseTop) {
        if (root == null) return;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top = baseTop, bottom = 0, left = 0, right = 0;
                if (Build.VERSION.SDK_INT >= 28 && extend(a)) {
                    try {
                        DisplayCutout dc = in.getDisplayCutout();
                        if (dc != null) {
                            top = baseTop + Math.max(dc.getSafeInsetTop(), statusBarH(a));
                            bottom = dc.getSafeInsetBottom();
                            left = dc.getSafeInsetLeft();
                            right = dc.getSafeInsetRight();
                        }
                    } catch (Exception ignored) { }
                }
                v.setPadding(left, top, right, bottom);
                return in;
            }
        });
        /* 触发一次 insets 分发（有些机型不会自动重发） */
        root.post(new Runnable() {
            @Override public void run() {
                try { root.requestApplyInsets(); } catch (Exception ignored) { }
            }
        });
    }

    /** 取挖孔区域（用于调试显示） */
}
