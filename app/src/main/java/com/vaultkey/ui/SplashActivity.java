package com.vaultkey.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Session;
import com.vaultkey.util.Skin;
import com.vaultkey.util.Splash;
import com.vaultkey.util.Ui;
import com.vaultkey.data.Prefs;

/**
 * 启动页：自定义开屏图 + 入场动画。
 *
 * 动画分三层，错开时间形成层次：
 *   1. 开屏图：淡入 + 从 0.88 放大到 1（Overshoot 回弹）
 *   2. 应用名 + 标语：延迟后淡入并上移
 *   3. 底部渐变光带：横向扫过（循环一次）
 *
 * 全部用 ViewPropertyAnimator，不用属性动画 XML，也不引第三方库。
 */
public final class SplashActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean jumped;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 启动页不截屏保护：开屏图是用户自己的图，没必要禁止；
           但为避免在最近任务里留下明文，仍跟随应用设置 */
        try { getWindow().setStatusBarColor(Skin.bg(this)); } catch (Exception ignored) { }

        /* 没开启动页，或已解锁状态直接进入 —— 不打断已有会话 */
        if (!Splash.enabled(this)) { go(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Skin.bg(this));

        /* --- 开屏图 --- */
        Bitmap bm = Splash.get(this, 1200);
        ImageView img = new ImageView(this);
        if (bm != null) {
            img.setImageBitmap(bm);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            /* 没自定义图时画默认的盾牌标志，不至于是一片纯色 */
            img.setImageDrawable(defaultMark());
            img.setScaleType(ImageView.ScaleType.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(img, lp);
        }

        /* --- 底部渐变遮罩：让文字在任何图上都能看清 --- */
        View scrim = new View(this) {
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                Shader sh = new LinearGradient(0, getHeight() * 0.55f, 0, getHeight(),
                        0x00000000, 0xAA000000, Shader.TileMode.CLAMP);
                p.setShader(sh);
                c.drawRect(0, getHeight() * 0.55f, getWidth(), getHeight(), p);
            }
        };
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* --- 文字区 --- */
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.gravity = Gravity.BOTTOM;
        tl.bottomMargin = Ui.dp(this, 46);
        txt.setLayoutParams(tl);

        TextView name = new TextView(this);
        name.setText("密盒");
        name.setTextSize(26);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setLetterSpacing(0.12f);
        txt.addView(name);

        TextView sub = new TextView(this);
        sub.setText("端到端加密 · AES-256-GCM");
        sub.setTextSize(11.5f);
        sub.setTextColor(0xCCFFFFFF);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        txt.addView(sub);
        root.addView(txt);

        /* --- 底部光带：横向扫过 --- */
        final View beam = new View(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 2));
        bl.gravity = Gravity.BOTTOM;
        bl.bottomMargin = Ui.dp(this, 28);
        beam.setLayoutParams(bl);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 1));
        bg.setColors(new int[]{0x0000B8A0, 0xFF00B8A0, 0x0000B8A0});
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
        beam.setBackground(bg);
        beam.setAlpha(0f);
        root.addView(beam);

        setContentView(root);

        /* ---------------- 动画 ---------------- */

        /* 1. 开屏图：淡入 + 回弹放大 */
        img.setAlpha(0f);
        img.setScaleX(0.88f);
        img.setScaleY(0.88f);
        img.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520)
                .setInterpolator(new OvershootInterpolator(0.9f))
                .start();

        /* 2. 文字：延迟淡入并上移 */
        txt.setAlpha(0f);
        txt.setTranslationY(Ui.dp(this, 18));
        txt.animate().alpha(1f).translationY(0)
                .setStartDelay(280)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        /* 3. 光带：淡入后横向扫过 */
        int sw = getResources().getDisplayMetrics().widthPixels;
        beam.setTranslationX(-sw / 2f - Ui.dp(this, 60));
        beam.animate().alpha(1f).setStartDelay(400).setDuration(200).start();
        beam.animate().translationX(sw / 2f + Ui.dp(this, 60))
                .setStartDelay(600)
                .setDuration(760)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        beam.animate().alpha(0f).setDuration(180).start();
                    }
                })
                .start();

        /* 到点进入解锁页 */
        h.postDelayed(new Runnable() {
            @Override public void run() { go(); }
        }, Splash.duration(this));
    }

    /** 没自定义图时的默认标志：盾牌 + 中心圆点，与启动图标同构 */
    private Drawable defaultMark() {
        int s = Ui.dp(this, 168);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.045f);
        p.setColor(0xFFFFFFFF);
        /* 盾牌 */
        android.graphics.Path sh = new android.graphics.Path();
        float cx = s / 2f;
        sh.moveTo(cx, s * 0.14f);
        sh.lineTo(s * 0.80f, s * 0.27f);
        sh.lineTo(s * 0.80f, s * 0.56f);
        sh.cubicTo(s * 0.80f, s * 0.75f, s * 0.68f, s * 0.86f, cx, s * 0.92f);
        sh.cubicTo(s * 0.32f, s * 0.86f, s * 0.20f, s * 0.75f, s * 0.20f, s * 0.56f);
        sh.lineTo(s * 0.20f, s * 0.27f);
        sh.close();
        c.drawPath(sh, p);
        /* 外圈 */
        p.setStrokeWidth(s * 0.038f);
        p.setColor(0xDDFFFFFF);
        c.drawCircle(cx, s * 0.50f, s * 0.155f, p);
        /* 内圈 */
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF12181C);
        c.drawCircle(cx, s * 0.50f, s * 0.105f, p);
        /* 中心点 */
        p.setColor(Skin.accent(this));
        c.drawCircle(cx, s * 0.50f, s * 0.048f, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    private void go() {
        if (jumped) return;
        jumped = true;
        Intent i;
        /* 已解锁且未过期 → 直接进主界面；否则走解锁 */
        if (Session.key() != null && !Session.expired(Prefs.getI("lock_ms", 180000))) {
            i = new Intent(this, MainActivity.class);
        } else {
            i = new Intent(this, UnlockActivity.class);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        /* 淡出过渡，避免硬切 */
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override public void onBackPressed() {
        /* 启动页点返回直接退出，不卡在开屏 */
        go();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
