package com.vaultkey.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.util.Domain;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Icons;
import com.vaultkey.util.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * 找回账号：按域名聚合。
 *
 * 解决的是登录时最常卡住的那一步 —— **不知道自己当初用哪个邮箱 / 手机号注册的**。
 * 以前只能一个个点开条目比对，账号一多根本记不清。
 *
 * 这里把同一主域名下的账号聚成一组（pan.baidu.com 与 tieba.baidu.com 算同一家），
 * 组内直接列出每条绑的账号名，一眼找到要用的那个。
 */
public final class RecoverActivity extends BaseActivity {

    private LinearLayout listBox;
    private EditText search;
    private TextView count;
    private final Handler h = new Handler(Looper.getMainLooper());
    private List<Db.Entry> all = new ArrayList<>();

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageDrawable(Ico.get(this, "back", Ui.attr(this, R.attr.textColor), 22));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = title("找回账号", 20);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(t);
        body.addView(head);

        TextView intro = label("登录时不知道用的是哪个邮箱 / 手机号？这里按网站把账号归在一起，"
                + "直接看清每个号绑的是什么。");
        intro.setTextSize(11.5f);
        intro.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        body.addView(intro);

        search = new EditText(this);
        search.setHint("搜索网站 / 备注，如 百度");
        search.setTextSize(14);
        search.setTextColor(Ui.attr(this, R.attr.textColor));
        search.setHintTextColor(Ui.attr(this, R.attr.textColor2));
        search.setBackground(Ui.field(this, 16));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d2) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d2) { render(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        body.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        count = new TextView(this);
        count.setTextSize(11);
        count.setTextColor(Ui.attr(this, R.attr.textColor2));
        count.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, Ui.dp(this, 4));
        body.addView(count);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, Ui.dp(this, 100));
        sv.addView(listBox);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(this, 4);
        body.addView(sv, sl);

        load();
    }

    private void load() {
        all = Db.get(this).list(null, false, false, null, 0);
        render();
    }

    private void render() {
        if (listBox == null) return;
        listBox.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase();

        List<Db.Entry> src = new ArrayList<>();
        for (Db.Entry e : all) {
            if (q.isEmpty()) { src.add(e); continue; }
            if (e.title.toLowerCase().contains(q)
                    || e.url.toLowerCase().contains(q)
                    || e.user.toLowerCase().contains(q)
                    || e.notes.toLowerCase().contains(q)) src.add(e);
        }

        List<Domain.Group> groups = Domain.group(src);

        int totalEntry = 0;
        for (Domain.Group g : groups) totalEntry += g.size();
        count.setText("共 " + groups.size() + " 个网站 · " + totalEntry + " 个账号");

        if (groups.isEmpty()) {
            TextView e = new TextView(this);
            e.setText(q.isEmpty() ? "还没有账号" : "没找到匹配的账号");
            e.setTextSize(12);
            e.setTextColor(Ui.attr(this, R.attr.textColor2));
            e.setPadding(Ui.dp(this, 4), Ui.dp(this, 24), 0, 0);
            listBox.addView(e);
            return;
        }

        for (Domain.Group g : groups) listBox.addView(groupCard(g));
    }

    /* ---------------- 组卡片 ---------------- */

    private View groupCard(final Domain.Group g) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.sheet(this, 18));
        int p = Ui.dp(this, 12);
        card.setPadding(p, p, p, Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);

        /* 组头：图标 + 域名 + 数量 */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, Ui.dp(this, 6));

        final ImageView ic = new ImageView(this);
        int s = Ui.dp(this, 34);
        ic.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        /* 先给个按域名算色的字母块，图标下载到了再替换 */
        int col = Ui.colorOf(g.domain);
        GradientDrawable ig = new GradientDrawable();
        ig.setColor(Ui.withAlpha(col, Ui.isDark(this) ? 70 : 48));
        ig.setCornerRadius(Ui.dp(this, 11));
        ic.setBackground(ig);
        ic.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        ic.setImageDrawable(Ico.get(this, "web", col, 20));
        if (!g.sampleUrl.isEmpty()) {
            Icons.favicon(this, g.sampleUrl, b -> {
                if (b != null) {
                    ic.setImageBitmap(Icons.round(b, Ui.dp(RecoverActivity.this, 11)));
                    ic.setBackground(null);
                    ic.setPadding(0, 0, 0, 0);
                }
            });
        }
        head.addView(ic);

        TextView name = new TextView(this);
        name.setText(g.domain.isEmpty() ? "未填网址 / 仅本机 App" : g.domain);
        name.setTextSize(14);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(Ui.attr(this, R.attr.textColor));
        name.setSingleLine(true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(this, 10));
        name.setLayoutParams(nl);
        head.addView(name);

        /* 数量角标：多个账号时才显示，突出"这家有好几个号" */
        if (g.size() > 1) {
            TextView badge = new TextView(this);
            badge.setText(g.size() + " 个号");
            badge.setTextSize(11);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setTextColor(accent());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(999f);
            bg.setColor(Ui.withAlpha(accent(), Ui.isDark(this) ? 46 : 34));
            badge.setBackground(bg);
            badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4));
            head.addView(badge);
        }
        card.addView(head);

        /* 组内每条 */
        for (Db.Entry e : g.items) card.addView(itemRow(e));

        /* 有网址时给个"打开网站" */
        if (!g.sampleUrl.isEmpty()) {
            TextView open = new TextView(this);
            open.setText("打开 " + g.domain);
            open.setTextSize(11.5f);
            open.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            open.setTextColor(accent());
            open.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, Ui.dp(this, 2));
            Ui.press(open);
            final String url = g.sampleUrl.startsWith("http")
                    ? g.sampleUrl : "https://" + g.sampleUrl;
            open.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ex) {
                    toast("打不开这个网址");
                }
            });
            card.addView(open);
        }
        return card;
    }

    /** 组内一行：名称 + 绑定账号（脱敏）+ 复制 */
    private View itemRow(final Db.Entry e) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.inner(this, 12));
        int p = Ui.dp(this, 10);
        r.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 5);
        r.setLayoutParams(lp);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(e.title.isEmpty() ? e.user : e.title);
        n.setTextSize(13.5f);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setTextColor(Ui.attr(this, R.attr.textColor));
        n.setSingleLine(true);
        txt.addView(n);

        /* 核心价值：这一行就是"绑的什么账号" */
        TextView u = new TextView(this);
        u.setText(Domain.hint(e.user));
        u.setTextSize(11.5f);
        u.setTextColor(Ui.attr(this, R.attr.textColor2));
        u.setSingleLine(true);
        u.setPadding(0, Ui.dp(this, 2), 0, 0);
        txt.addView(u);

        r.addView(txt);

        if (e.fav > 0) {
            ImageView st = new ImageView(this);
            st.setImageDrawable(Ico.get(this, "star", accent(), 15));
            st.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15)));
            LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) st.getLayoutParams();
            sl.setMarginEnd(Ui.dp(this, 6));
            r.addView(st);
        }

        TextView cp = new TextView(this);
        cp.setText("复制");
        cp.setTextSize(11f);
        cp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cp.setTextColor(accent());
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999f);
        g.setColor(Ui.withAlpha(accent(), Ui.isDark(this) ? 40 : 30));
        cp.setBackground(g);
        cp.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        cp.setOnClickListener(v -> copy(e.user, "账号"));
        r.addView(cp);

        /* 点整行也复制账号 —— 这个视图的主要用途就是拿账号去登录 */
        r.setOnClickListener(v -> copy(e.user, "账号"));
        r.setOnLongClickListener(v -> { menu(e); return true; });
        return r;
    }

    private void menu(final Db.Entry e) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, search);
        pm.getMenu().add("复制账号");
        pm.getMenu().add("复制密码");
        pm.getMenu().add("编辑这条");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getTitle().toString()) {
                case "复制账号": copy(e.user, "账号"); break;
                case "复制密码": copy(e.pass, "密码"); break;
                case "编辑这条":
                    startActivity(new Intent(this, EntryEditActivity.class)
                            .putExtra("uuid", e.uuid));
                    break;
            }
            return true;
        });
        pm.show();
    }

    /* ---------------- 复制 ---------------- */

    private final Runnable clearClip = new Runnable() {
        @Override public void run() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) { }
        }
    };

    private void copy(String s, String what) {
        if (s == null || s.isEmpty()) { toast("这条没有" + what); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("vaultkey", s));
            toast("已复制" + what);
            h.removeCallbacks(clearClip);
            h.postDelayed(clearClip, Prefs.getI("clip_sec", 45) * 1000L);
        } catch (Exception ex) {
            toast("复制失败");
        }
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(clearClip);
        super.onDestroy();
    }
}
