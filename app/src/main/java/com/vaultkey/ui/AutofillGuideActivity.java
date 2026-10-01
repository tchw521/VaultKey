package com.vaultkey.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Prefs;
import com.vaultkey.service.FillAssistService;
import com.vaultkey.util.Ui;

/** 自动填充开启引导：系统自动填充 + 无障碍辅助填充 */
public final class AutofillGuideActivity extends BaseActivity {
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 40));
        sv.addView(box);
        body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (box != null) render();
    }

    private void render() {
        box.removeAllViews();
        TextView t = title("自动填充", 22);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        box.addView(t);
        TextView tip = new TextView(this);
        tip.setText("两种方式互补，建议都开启：系统自动填充覆盖大多数 App 与网页；辅助填充用于系统自动填充不生效的登录框（如游戏内登录、自定义 WebView）。");
        tip.setTextSize(12);
        tip.setTextColor(Ui.attr(this, R.attr.textColor2));
        tip.setLineSpacing(Ui.dp(this, 2), 1.1f);
        tip.setPadding(0, 0, 0, Ui.dp(this, 12));
        box.addView(tip);

        /* 系统自动填充 */
        box.addView(step(1, "开启系统自动填充服务",
                "系统设置 → 系统 → 语言与输入法 → 自动填充服务 → 选择「密盒」。之后在登录框点击即可选择账号，解锁后自动填入。",
                "前往系统设置", v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                .setData(android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent("android.settings.AUTOFILL_SETTINGS")); }
                        catch (Exception e2) { toast("请手动在系统设置中开启"); }
                    }
                }));

        /* 辅助填充 */
        boolean on = FillAssistService.running();
        box.addView(step(2, "开启无障碍辅助填充" + (on ? "（已开启）" : ""),
                "系统设置 → 无障碍 → 已下载的服务 → 密盒 → 打开。检测到登录界面时会发一条通知，点通知选账号即可填入。",
                on ? "已开启，前往管理" : "前往无障碍设置", v -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception e) { toast("请手动在系统设置中开启"); }
                }));

        /* 开关 */
        TextView off = chip("暂停自动填充：" + (Prefs.getB("autofill_off", false) ? "已暂停" : "工作中"), Prefs.getB("autofill_off", false));
        off.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(this, 16);
        off.setLayoutParams(ol);
        off.setOnClickListener(v -> {
            boolean p = !Prefs.getB("autofill_off", false);
            Prefs.putB("autofill_off", p);
            off.setText("暂停自动填充：" + (p ? "已暂停" : "工作中"));
            off.setTextColor(p ? Color.WHITE : Ui.attr(this, R.attr.textColor));
            off.setBackground(p ? Ui.gradient(new int[]{getResources().getColor(R.color.bad), Ui.accent2(this)}, 999f, 0)
                    : Ui.glass(this, 999, R.attr.cardColor, R.attr.strokeColor));
        });
        box.addView(off);

        /* 匹配说明 */
        TextView note = new TextView(this);
        note.setText("匹配规则：优先按关联的本机 App 包名，其次按网址主域名。可在编辑条目时填写「关联应用」或「网址」来提高命中率。");
        note.setTextSize(11);
        note.setTextColor(Ui.attr(this, R.attr.textColor2));
        note.setLineSpacing(Ui.dp(this, 2), 1.1f);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), 0, 0);
        box.addView(note);
    }

    private View step(int n, String title, String desc, String btn, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        c.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText("步骤 " + n + " · " + title);
        h.setTextSize(15);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setTextColor(Ui.attr(this, R.attr.textColor));
        c.addView(h);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(Ui.attr(this, R.attr.textColor2));
        d.setLineSpacing(Ui.dp(this, 2), 1.1f);
        d.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        c.addView(d);

        android.widget.Button b = button(btn, new int[]{Ui.accent(this), Ui.accent2(this)});
        b.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(l);
        c.addView(b);
        return c;
    }
}
