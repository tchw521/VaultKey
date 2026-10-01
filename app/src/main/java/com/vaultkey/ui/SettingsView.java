package com.vaultkey.ui;

import android.graphics.Bitmap;

import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.crypto.Biometric;
import com.vaultkey.crypto.Crypto;
import com.vaultkey.crypto.KeystoreHelper;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.sync.Sync;
import com.vaultkey.util.Ico;
import com.vaultkey.service.FloatService;
import com.vaultkey.util.Avatar;
import com.vaultkey.util.CircleDrawable;
import com.vaultkey.util.Splash;
import com.vaultkey.util.Updater;
import com.vaultkey.util.Cutout;
import com.vaultkey.util.Skin;
import com.vaultkey.util.IconFill;
import com.vaultkey.util.Ui;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** 设置中心：外观、坚果云、工具、隐私、自动填充、分类、关于 */
public final class SettingsView {
    private final BaseActivity a;
    private final ToolsView.Host host;
    private FrameLayout host2;
    private LinearLayout main;
    private ScrollView mainScroll;
    private View sub;
    private SyncView syncPage;

    /** 授权本地备份文件夹的结果要转给它处理 */
    public SyncView syncPage() {
        if (syncPage == null) syncPage = new SyncView(a, this::onBack, host);
        return syncPage;
    }
    private ToolsView toolsPage;

    private static final long[] PALETTE = {
            0xFF00B8A0L, 0xFF38BDF8L, 0xFF22C55EL, 0xFFF59E0BL,
            0xFFEF4444L, 0xFFA78BFAL, 0xFFFF6B9DL, 0xFF94A3B8L
    };
    private static final String[] ICONS = {"box", "cloud", "image", "audio", "web", "app",
            "game", "bank", "work", "mail", "chat", "shop", "more"};

    public interface OnIdx { void on(int idx); }

    public SettingsView(BaseActivity a, ToolsView.Host host) {
        this.a = a;
        this.host = host;
    }

    public View view() {
        host2 = new FrameLayout(a);
        mainScroll = new ScrollView(a);
        main = new LinearLayout(a);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 110));
        mainScroll.addView(main);
        host2.addView(mainScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
        return host2;
    }

    public void refresh() { if (main != null) render(); }

    /** 返回 true 表示消费了返回键 */
    public boolean onBack() {
        if (sub != null && sub.getVisibility() == View.VISIBLE) {
            sub.setVisibility(View.GONE);
            mainScroll.setVisibility(View.VISIBLE);
            return true;
        }
        return false;
    }

    private void openSub(View v) {
        if (sub != null) host2.removeView(sub);
        sub = v;
        host2.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainScroll.setVisibility(View.GONE);
        v.setVisibility(View.VISIBLE);
    }

    /* ---------------- 主页面 ---------------- */

    private void render() {
        main.removeAllViews();
        main.addView(titleRow());

        main.addView(jianguoyunCard());

        main.addView(statsCard());
        main.addView(a.section("通用"));
        main.addView(a.settingRow("sync", "坚果云同步", Sync.configured() ? Sync.lastSyncText() : "未配置账号",
                v -> {
                    if (syncPage == null) syncPage = new SyncView(a, this::onBack, host);
                    syncPage.refresh();
                    openSub(syncPage.view());
                }));
        main.addView(a.settingRow("tools", "工具箱", "密码生成 · 强度检测 · 安全体检", v -> {
            if (toolsPage == null) toolsPage = new ToolsView(a, host, this::onBack);
            toolsPage.refresh();
            openSub(toolsPage.view());
        }));
        main.addView(a.settingRow("shield", "严格识别登录框",
                Prefs.getB("af_strict", true) ? "已开启，聊天框等不会误报" : "已关闭，任何输入框都可能提示填充",
                v -> {
                    Prefs.putB("af_strict", !Prefs.getB("af_strict", true));
                    render();
                }));
        main.addView(a.settingRow("lock", "自动填充", "系统自动填充 + 无障碍辅助",
                v -> a.startActivity(new Intent(a, AutofillGuideActivity.class))));
        main.addView(a.settingRow("app", "自定义分类", "新建 / 改名 / 换图标 / 换色 / 删除", v -> cats(0, null)));
        int miss = IconFill.missing(a).size();
        main.addView(a.settingRow("image", "一键自动获取图标",
                miss == 0 ? "所有账号都已有图标" : miss + " 个账号还没有图标，点此一键补全",
                v -> a.startActivity(new android.content.Intent(a, IconFillActivity.class))));
        main.addView(a.settingRow("app", "悬浮窗",
                FloatService.enabled()
                        ? (FloatService.canDraw(a) ? "已开启，在其他应用上方可直接取账号" : "已开启，但还缺悬浮窗权限")
                        : "在其他应用的登录界面上方显示匹配账号",
                v -> floatDialog()));
        main.addView(a.settingRow("image", "启动页",
                Splash.enabled(a) ? (Splash.has(a) ? "自定义开屏图 · 带动画" : "默认标志 · 带动画")
                        : "已关闭，启动直接进解锁",
                v -> splashPage()));

        main.addView(a.settingRow("edit", "批量处理账号",
                "多选后批量改分类 / 标签 / 收藏 / 删除",
                v -> { host.showBatch(); }));

        main.addView(a.section("换机 / 查找"));
        main.addView(a.settingRow("web", "找回账号",
                "登录时不知道用的哪个邮箱？按网站聚合查看",
                v -> a.startActivity(new Intent(a, RecoverActivity.class))));
        main.addView(a.settingRow("sync", "换机直传",
                "两台手机连同一 WiFi，配对码确认后整个密码库直接过去",
                v -> new android.app.AlertDialog.Builder(a).setTitle("换机直传")
                        .setItems(new String[]{"我是旧手机，发送出去", "我是新手机，接收过来"},
                                (d, w) -> a.startActivity(new Intent(a, TransferActivity.class)
                                        .putExtra(TransferActivity.EXTRA_MODE, w == 0 ? "send" : "recv")))
                        .show()));
        /* 原先这里有「回收站 / 导入数据 / 导出数据」三项，现已移除：
           - 回收站：左侧导航栏底部本来就有，且回收站内已能切换账号与卡片，重复入口
           - 导入 / 导出：与「坚果云同步」页里的同类项重复，两个入口容易让人分不清
             「本地文件导入导出」和「云端同步」的区别。功能未删，仍在坚果云同步页内。 */

        /* 刘海屏：只在设备确实有刘海时才显示这一项，避免对多数用户造成干扰 */
        if (Cutout.has(a)) {
            main.addView(a.section("显示"));
            main.addView(toggleRow("app", "延伸到刘海区域",
                    Cutout.extend(a) ? "背景铺满到挖孔两侧，内容自动避让" : "内容整体下移，避开刘海",
                    Cutout.extend(a), on -> {
                Cutout.setExtend(on);
                Ico.clearCache();
                a.recreate();
            }));
        }

        main.addView(a.section("隐私设置"));
        main.addView(toggleRow("camera", "禁止截屏",
                Prefs.getB("block_capture", true) ? "已开启，界面无法被截屏或录屏" : "已关闭，允许截屏",
                Prefs.getB("block_capture", true), (on) -> {
            Prefs.putB("block_capture", on);
            a.applySecure();
            render();
        }));
        main.addView(a.settingRow("key", "自动锁定", lockName(), v -> {
            String[] o = {"1 分钟", "3 分钟", "5 分钟", "15 分钟", "30 分钟", "永不（不推荐）"};
            final int[] vv = {60000, 180000, 300000, 900000, 1800000, Integer.MAX_VALUE};
            new android.app.AlertDialog.Builder(a).setTitle("自动锁定").setItems(o, (d, w) -> {
                Prefs.putI("lock_ms", vv[w]);
                render();
            }).show();
        }));
        main.addView(toggleRow("shield", "切到后台立即锁定", "离开界面立刻上锁",
                Prefs.getB("lock_on_resume", false), (on) -> {
            Prefs.putB("lock_on_resume", on);
            render();
        }));
        main.addView(a.settingRow("copy", "剪贴板自动清空", Prefs.getI("clip_sec", 45) + " 秒后清空", v -> {
            String[] o = {"30 秒", "45 秒", "60 秒", "120 秒"};
            final int[] vv = {30, 45, 60, 120};
            new android.app.AlertDialog.Builder(a).setTitle("剪贴板清空").setItems(o, (d, w) -> {
                Prefs.putI("clip_sec", vv[w]);
                render();
            }).show();
        }));
        main.addView(a.settingRow("edit", "修改主密码", "全部数据会重新加密", v -> changePwd()));
        main.addView(switchRow("image", "新增账号后自动获取图标",
                "保存后后台补图标，本地优先", "auto_icon", true));
        main.addView(a.settingRow("fingerprint", "指纹解锁",
                KeystoreHelper.hasBio(a) ? "已启用，点击关闭"
                        : (Biometric.canStrong(a) ? "未启用，点击开启" : "该设备不支持"),
                v -> toggleBio()));
        main.addView(a.settingRow("person", "人脸解锁",
                faceSub(), v -> toggleFace()));
        main.addView(a.settingRow("float", "启动自动解锁",
                unlockModeText(), v -> pickUnlockMode()));
        main.addView(a.settingRow("info", "生物识别自检",
                "看设备到底支持哪种、开了哪个", v ->
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("生物识别自检")
                                .setMessage(Biometric.diag(a))
                                .setPositiveButton("好", null)
                                .setNeutralButton("系统设置", (x, y) -> Biometric.openEnroll(a))
                                .show()));

        /* 「外观」整组已移除：换皮肤挪到顶栏右上角的调色板图标。
           设置中心只保留「关于 / 清空回收站」等真正适合放设置的内容。 */

        main.addView(a.section("关于"));
        main.addView(a.settingRow("shield", "关于应用", "v" + appVer() + " · 开发者 tchw521", v -> aboutDialog()));
        main.addView(a.settingRow("download", "下载最新版", "GitHub Releases / 蓝奏云网盘", v -> checkUpdate()));
        main.addView(a.settingRow("cloud", "蓝奏云下载", "国内直连更快（密码 1111）", v -> lanzouDialog()));
        main.addView(a.settingRow("gift", "赞助作者", "如果密盒帮到了你", v -> sponsorDialog()));
        main.addView(a.settingRow("trash", "清空回收站", "彻底删除已删除的账号与卡片", v -> {
            Db.get(a).emptyTrash();
            for (Db.Card c : Db.get(a).cardsRaw()) if (c.del) Db.get(a).hardDeleteCard(c.id);
            a.toast("已清空");
        }));
    }

    private View statsCard() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setBackground(Ui.glass(a, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        c.setLayoutParams(lp);
        int acc = Db.get(a).count(false, -1);
        int card = Db.get(a).countCards(false);
        int cats = Db.get(a).cats(0).size() + Db.get(a).cats(1).size();
        c.addView(statCell(acc + "", "账号", 0xFF00B8A0L));
        c.addView(statCell(card + "", "卡片", 0xFFA78BFAL));
        c.addView(statCell(cats + "", "分类", 0xFF38BDF8L));
        return c;
    }

    private View statCell(String n, String label, long color) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(a);
        t.setText(n);
        t.setTextSize(22);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor((int) color);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView s = new TextView(a);
        s.setText(label);
        s.setTextSize(11);
        s.setTextColor(Ui.attr(a, R.attr.textColor2));
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(a, 2), 0, 0);
        l.addView(s);
        return l;
    }

    private View switchRow(String icon, String name, String sub, final String key, boolean def) {
        return a.settingRow(icon, name, sub + "（当前：" + (Prefs.getB(key, def) ? "开" : "关") + "）", v -> {
            Prefs.putB(key, !Prefs.getB(key, def));
            render();
            a.toast(Prefs.getB(key, def) ? "已开启" : "已关闭");
        });
    }

    /** 界面显示的版本号：直接读 PackageManager，与构建时写入的版本一致 */
    private String appVer() {
        try {
            android.content.pm.PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return pi.versionName == null ? "2.8.0" : pi.versionName;
        } catch (Exception e) {
            return "2.8.0";
        }
    }

    private String lockName() {
        int ms = Prefs.getI("lock_ms", 180000);
        return ms == Integer.MAX_VALUE ? "永不" : (ms / 60000) + " 分钟";
    }

    /**
     * 设置中心顶栏：标题 + 右上角「换皮肤」图标。
     * 同类 App 普遍把外观入口做成顶栏图标而不是列表项。
     */
    private View titleRow() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.bottomMargin = Ui.dp(a, 10);
        row.setLayoutParams(rl);

        /* 顶栏「设置中心」标题去掉：底部 Tab 已经高亮标明了当前在哪，
           顶上再来一行大标题是重复。这里留一个空的可伸展占位，
           把右侧的「外观」入口顶到右边。 */
        View spacer = new View(a);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);

        /* 皮肤入口：圆角胶囊底 + 调色板图标，点开就是换皮肤页 */
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(999f);
        g.setColor(Ui.withAlpha(a.accent(), 34));
        box.setBackground(g);
        box.setPadding(Ui.dp(a, 10), Ui.dp(a, 7), Ui.dp(a, 10), Ui.dp(a, 7));

        ImageView ic = new ImageView(a);
        ic.setImageDrawable(Ico.get(a, "palette", a.accent(), 19));
        ic.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 19), Ui.dp(a, 19)));
        box.addView(ic);

        TextView lb = new TextView(a);
        lb.setText(Skin.current(a).name);
        lb.setTextSize(11.5f);
        lb.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lb.setTextColor(a.accent());
        lb.setPadding(Ui.dp(a, 5), 0, 0, 0);
        lb.setMaxWidth(Ui.dp(a, 72));
        lb.setSingleLine(true);
        box.addView(lb);

        Ui.press(box);
        box.setOnClickListener(v ->
                a.startActivity(new android.content.Intent(a, SkinActivity.class)));
        row.addView(box);
        return row;
    }

    /* ---------------- 悬浮窗 ---------------- */

    private void floatDialog() {
        boolean can = FloatService.canDraw(a);
        if (!FloatService.enabled()) {
            /* 首次开启：先说明用途，再要权限 */
            new android.app.AlertDialog.Builder(a)
                    .setTitle("开启悬浮窗")
                    .setMessage("开启后，在其他应用的登录界面上方会浮出一个小窗，"
                            + "直接显示匹配到的账号，点一下就能复制，不用切回密盒。\n\n"
                            + "需要「显示在其他应用上层」权限，密盒不会读取你输入的任何内容。")
                    .setPositiveButton("开启悬浮窗", (d, w) -> {
                        FloatService.setEnabled(true);
                        if (!FloatService.canDraw(a)) FloatService.request(a);
                        else FloatService.show(a, "", "");
                        render();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!can) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("还缺悬浮窗权限")
                    .setMessage("请在接下来的系统设置页里，找到密盒并打开「显示在其他应用上层」。")
                    .setPositiveButton("去设置", (d, w) -> FloatService.request(a))
                    .setNegativeButton("关闭悬浮窗", (d, w) -> {
                        FloatService.setEnabled(false);
                        FloatService.stop(a);
                        render();
                    })
                    .show();
            return;
        }
        /* 已可用：给开关与自动弹出选项 */
        String[] o = {"关闭悬浮窗", "关闭自动弹出", "开启自动弹出"};
        int cur = FloatService.autoPopup() ? 2 : 1;
        new android.app.AlertDialog.Builder(a).setTitle("悬浮窗")
                .setSingleChoiceItems(o, cur, (d, w) -> {
                    if (w == 0) {
                        FloatService.setEnabled(false);
                        FloatService.stop(a);
                    } else {
                        FloatService.setEnabled(true);
                        FloatService.setAutoPopup(w == 2);
                    }
                    d.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 启动页 ---------------- */

    private void splashPage() {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(null);
        p.setPadding(Ui.dp(a, 2), Ui.dp(a, 8), Ui.dp(a, 2), Ui.dp(a, 110));

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        p.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        TextView t = a.title("启动页", 20);
        t.setPadding(0, 0, 0, Ui.dp(a, 12));
        box.addView(t);

        box.addView(a.section("开屏图"));

        /* 预览 + 选图 */
        ImageView pv = new ImageView(a);
        Bitmap bm = Splash.get(a, 400);
        if (bm != null) {
            pv.setImageBitmap(bm);
            pv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            pv.setImageDrawable(a.getResources().getDrawable(R.drawable.ic_launcher_foreground));
            pv.setScaleType(ImageView.ScaleType.CENTER);
        }
        android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
        pg.setCornerRadius(Ui.dp(a, 14));
        pg.setColor(Ui.attr(a, R.attr.cardColor2));
        pg.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.attr(a, R.attr.strokeColor));
        pv.setBackground(pg);
        int ph = Ui.dp(a, 150);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ph);
        pl.bottomMargin = Ui.dp(a, 10);
        pv.setLayoutParams(pl);
        box.addView(pv);

        android.widget.Button pick = a.button("选择开屏图", new int[]{a.accent(), a.accent2()});
        pick.setOnClickListener(v -> pickSplash());
        box.addView(pick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (Splash.has(a)) {
            android.widget.Button clr = a.button("清除，用默认标志", new int[]{
                    a.getResources().getColor(R.color.bad), a.accent2()});
            clr.setOnClickListener(v -> {
                Splash.clear(a);
                a.toast("已恢复默认");
                splashPage();
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Ui.dp(a, 8);
            box.addView(clr, cl);
        }

        box.addView(a.section("显示"));
        box.addView(toggleRow("image", "显示启动页",
                Splash.enabled(a) ? "开屏动画后进入" : "已关闭，启动直接进解锁",
                Splash.enabled(a), on -> {
            Splash.setEnabled(a, on);
            // 保持子页
        }));

        box.addView(a.section("停留时长"));
        final int[] idx = {Prefs.getI("splash_ms", 1)};
        box.addView(toggleRow2(new String[]{"短 0.9s", "默认 1.6s", "长 2.6s"}, idx[0], i -> {
            Splash.setDuration(a, i);
        }));

        box.addView(a.section("说明"));
        TextView note = new TextView(a);
        note.setText("开屏图只存在本机，不会随坚果云同步，也不上传任何服务器。"
                + "图片会按最长边 1080 压缩后保存。");
        note.setTextSize(11.5f);
        note.setTextColor(Ui.attr(a, R.attr.textColor2));
        note.setPadding(Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2), 0);
        box.addView(note);

        openSub(p);
    }

    private View toggleRow2(String[] names, int sel, final OnIdx cb) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBackground(Ui.glass(a, 16, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 10);
        l.setPadding(p, p, p, p);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(names[i]);
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            boolean on = i == sel;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(999f);
            g.setColor(on ? Ui.withAlpha(a.accent(), 40) : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 22));
            if (on) g.setStroke(Math.max(1, Ui.dp(a, 1)), a.accent());
            t.setBackground(g);
            t.setTextColor(on ? a.accent() : Ui.attr(a, R.attr.textColor2));
            t.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(Ui.dp(a, 6));
            t.setLayoutParams(lp);
            Ui.press(t);
            t.setOnClickListener(x -> {
                for (int k = 0; k < l.getChildCount(); k++) {
                    View c = l.getChildAt(k);
                    if (!(c instanceof TextView)) continue;
                    boolean s2 = k == idx;
                    android.graphics.drawable.GradientDrawable gg = new android.graphics.drawable.GradientDrawable();
                    gg.setCornerRadius(999f);
                    gg.setColor(s2 ? Ui.withAlpha(a.accent(), 40)
                            : Ui.withAlpha(Ui.attr(a, R.attr.textColor2), 22));
                    if (s2) gg.setStroke(Math.max(1, Ui.dp(a, 1)), a.accent());
                    c.setBackground(gg);
                    ((TextView) c).setTextColor(s2 ? a.accent() : Ui.attr(a, R.attr.textColor2));
                }
                cb.on(idx);
            });
            l.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 8);
        l.setLayoutParams(lp);
        return l;
    }

    private static final int REQ_SPLASH = 9001, REQ_AVATAR = 9002;

    private void pickSplash() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            a.startActivityForResult(i, REQ_SPLASH);
        } catch (Exception e) {
            a.toast("打不开相册");
        }
    }

    /** 由 MainActivity 的 onActivityResult 转进来 */
    public boolean onPickResult(int req, Intent data) {
        if (req != REQ_SPLASH && req != REQ_AVATAR) return false;
        if (data == null || data.getData() == null) return true;
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(
                    a.getContentResolver().openInputStream(data.getData()));
            if (b == null) { a.toast("读不到这张图"); return true; }
            if (req == REQ_AVATAR) {
                boolean ok = Avatar.set(a, b);
                b.recycle();
                a.toast(ok ? "头像已更新" : "保存失败");
                if (ok) render();
            } else {
                boolean ok = Splash.set(a, b);
                b.recycle();
                a.toast(ok ? "已设为开屏图" : "保存失败");
                if (ok) splashPage();
            }
        } catch (Exception e) {
            a.toast("保存失败");
        }
        return true;
    }

    /* ---------------- 头像 ---------------- */

    private void pickAvatar() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            a.startActivityForResult(i, REQ_AVATAR);
        } catch (Exception e) {
            a.toast("打不开相册");
        }
    }

    /** 头像菜单：换一张 / 移除 */
    private void avatarMenu() {
        String[] o = Avatar.has(a)
                ? new String[]{"从相册选一张", "移除头像"}
                : new String[]{"从相册选一张"};
        new android.app.AlertDialog.Builder(a)
                .setTitle(Prefs.get("dav_user", "").isEmpty() ? "头像" : Prefs.get("dav_user", ""))
                .setItems(o, (d, w) -> {
                    if (w == 0) pickAvatar();
                    else {
                        Avatar.clear(a);
                        a.toast("已移除头像");
                        render();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /* ---------------- 坚果云卡片（参考同类 App 布局） ---------------- */

    private View jianguoyunCard() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, 20, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        /*
         * 头像 + 账号信息。参考常见「个人资料」页的排布：
         * 左边圆形头像、右边两行文字，点击头像可以换图。
         *
         * 头像放大到 56dp —— 44 在密集的卡片里显得局促，
         * 而且自定义头像太小根本看不清。
         */
        final int as = Ui.dp(a, 56);
        FrameLayout avatar = new FrameLayout(a);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(as, as));

        android.graphics.Bitmap av = Avatar.get(a);
        if (av != null) {
            /* 有自定义头像：直接显示，圆形裁切用 RoundedBitmapDrawable */
            ImageView iv = new ImageView(a);
            iv.setLayoutParams(new FrameLayout.LayoutParams(as, as));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(new CircleDrawable(av));
            avatar.addView(iv);
        } else {
            /* 没头像：用账号首字/首字母做占位，比一个通用云图标更有辨识度 */
            String who = Sync.configured() ? Prefs.get("dav_user", "") : "";
            String letter = "?";
            if (!who.isEmpty()) {
                letter = who.substring(0, 1).toUpperCase();
            }
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Ui.withAlpha(a.accent(), Ui.isDark(a) ? 60 : 34));
            View bg = new View(a);
            bg.setLayoutParams(new FrameLayout.LayoutParams(as, as));
            bg.setBackground(g);
            avatar.addView(bg);

            TextView lt = new TextView(a);
            lt.setLayoutParams(new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
            lt.setText(letter);
            lt.setTextSize(22);
            lt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            lt.setTextColor(a.accent());
            lt.setGravity(Gravity.CENTER);
            avatar.addView(lt);
        }
        /* 右下角小徽标：提示这个头像是能改的 */
        TextView badge = new TextView(a);
        int bs = Ui.dp(a, 20);
        FrameLayout.LayoutParams bl2 = new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
        badge.setLayoutParams(bl2);
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setShape(GradientDrawable.OVAL);
        bg2.setColor(a.accent());
        bg2.setStroke(Ui.dp(a, 2), Ui.attr(a, R.attr.cardColor));
        badge.setBackground(bg2);
        badge.setText("\u270F");
        badge.setTextSize(9);
        badge.setTextColor(0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        avatar.addView(badge);

        avatar.setOnClickListener(v -> avatarMenu());
        head.addView(avatar);

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.setMarginStart(Ui.dp(a, 14));
        mid.setLayoutParams(ml);
        TextView n = new TextView(a);
        n.setText(Sync.configured() ? Prefs.get("dav_user", "") : "未添加坚果云账户");
        n.setTextSize(15);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setTextColor(Ui.attr(a, R.attr.textColor));
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(n);
        TextView s = new TextView(a);
        s.setText(Sync.configured() ? "上次同步：" + Sync.lastSyncText().replace("同步", "") : "配置后可在多设备间同步");
        s.setTextSize(11.5f);
        s.setTextColor(Ui.attr(a, R.attr.textColor2));
        s.setPadding(0, Ui.dp(a, 3), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(s);
        /* 第三行：进头像设置的入口，沿用参考图「管理个人资料」的说法 */
        TextView prof = new TextView(a);
        prof.setText("管理个人资料 \u203A");
        prof.setTextSize(11.5f);
        prof.setTextColor(a.accent());
        prof.setPadding(0, Ui.dp(a, 5), 0, 0);
        prof.setOnClickListener(v -> avatarMenu());
        mid.addView(prof);
        head.addView(mid);
        c.addView(head);

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(a, 14);
        btns.setLayoutParams(bl);

        android.widget.Button up = a.button("同步至坚果云", new int[]{a.accent(), a.accent2()});
        up.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        up.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        up.setTextSize(13);
        up.setOnClickListener(v -> {
            if (!Sync.configured()) { a.toast("请先配置坚果云账号"); openSync(); return; }
            syncNow(false);
        });
        btns.addView(up);

        android.widget.Button down = a.button("坚果云至本地", new int[]{a.accent2(), a.accent3()});
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dl.setMarginStart(Ui.dp(a, 10));
        down.setLayoutParams(dl);
        down.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        down.setTextSize(13);
        down.setOnClickListener(v -> {
            if (!Sync.configured()) { a.toast("请先配置坚果云账号"); openSync(); return; }
            new android.app.AlertDialog.Builder(a).setTitle("以云端覆盖本机？")
                    .setMessage("本机数据将被云端数据替换")
                    .setPositiveButton("确定", (d, w) -> syncNow(true))
                    .setNegativeButton("取消", null).show();
        });
        btns.addView(down);
        c.addView(btns);
        return c;
    }

    private void openSync() {
        if (syncPage == null) syncPage = new SyncView(a, this::onBack, host);
        syncPage.refresh();
        openSub(syncPage.view());
    }

    private void syncNow(boolean download) {
        final android.app.ProgressDialog pd = a.progress(download ? "正在拉取…" : "正在同步…");
        pd.show();
        new Thread(() -> {
            Sync.Result r = download ? Sync.forceDownload(a) : Sync.run(a);
            a.runOnUiThread(() -> {
                a.safeDismiss(pd);
                new android.app.AlertDialog.Builder(a)
                        .setTitle(r.ok ? "完成" : "未完成")
                        .setMessage(r.msg)
                        .setPositiveButton("好", (d, w) -> render())
                        .show();
            });
        }).start();
    }

    private View toggleRow(String ic, String k, String v, boolean init, OnBool cb) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.glass(a, 18, R.attr.cardColor, R.attr.strokeColor));
        int p = Ui.dp(a, 14);
        row.setPadding(p, Ui.dp(a, 13), p, Ui.dp(a, 13));
        row.addView(a.iconBadge(ic, init ? 0xFF00B8A0L : 0xFF94A3B8L, 36));

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.setMarginStart(Ui.dp(a, 12));
        mid.setLayoutParams(ml);
        TextView x = new TextView(a);
        x.setText(k);
        x.setTextSize(15);
        x.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        x.setTextColor(Ui.attr(a, R.attr.textColor));
        mid.addView(x);
        TextView y = new TextView(a);
        y.setText(v);
        y.setTextSize(11.5f);
        y.setTextColor(Ui.attr(a, R.attr.textColor2));
        y.setPadding(0, Ui.dp(a, 2), 0, 0);
        mid.addView(y);
        row.addView(mid);

        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(init);
        sw.setOnCheckedChangeListener((b, c) -> { cb.on(c); });
        row.addView(sw);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    interface OnBool { void on(boolean v); }

    /* ---------------- 自定义分类 ---------------- */

    /**
     * @param kind 0 = 从密码箱进来，1 = 从链接库进来。
     *
     * 分类表是**共用**的（这是"链接库用同样分类"的要求），所以这里只能有一套分类。
     * 但用户从链接库点进来时，如果看到的标题和计数都跟密码箱一样，
     * 就会以为"管理的是密码库的分类"。所以按入口标注上下文，
     * 并把该分类下的两种条目数都列出来，一眼能看出是同一套、各自有多少。
     */
    /** @param done 分类增删改后回调（让宿主刷新两侧导航） */
    public void showCats(int kind, Runnable done) { cats(kind, done); }

    public void showCats(int kind) { cats(kind, null); }

    public void showCats() { cats(0, null); }

    private void cats(final int kind, final Runnable done) {
        List<Db.Cat> cs = Db.get(a).cats(kind);
        /* 只显示分类名，后面不跟数量。
           "n 账号 / n 链接"这类数字在这里既容易误导（像是另一个库的统计），
           又没什么用 —— 分类的条目数在左侧导航里已经看得到。 */
        String[] names = new String[cs.size() + 1];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i).name;
        names[cs.size()] = "＋ 新建分类";
        String title = kind == 1 ? "自定义分类 · 链接库" : "自定义分类 · 密码箱";
        new android.app.AlertDialog.Builder(a).setTitle(title).setItems(names, (d, w) -> {
            if (w == cs.size()) editCat(null, kind, done);
            else editCat(cs.get(w), kind, done);
        }).show();
    }

    private void editCat(final Db.Cat c, final int kind, final Runnable done) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), 0);
        final EditText name = a.field("分类名称", c == null ? "" : c.name);
        l.addView(name);

        TextView ct = a.label("颜色");
        ct.setTextSize(12);
        ct.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));
        l.addView(ct);
        LinearLayout colors = new LinearLayout(a);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER_VERTICAL);
        final long[] pick = {c == null ? PALETTE[0] : c.color};
        final View[] dots = new View[PALETTE.length];
        int s = Ui.dp(a, 30);
        for (int i = 0; i < PALETTE.length; i++) {
            final int idx = i;
            View v = new View(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(0, 0, Ui.dp(a, 8), 0);
            v.setLayoutParams(lp);
            v.setBackground(Ui.roundRect((int) PALETTE[i], s / 2f));
            v.setOnClickListener(x -> {
                pick[0] = PALETTE[idx];
                for (int k = 0; k < dots.length; k++) dots[k].setPadding(0, 0, 0, 0);
                v.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
            });
            dots[i] = v;
            colors.addView(v);
        }
        l.addView(colors);

        TextView it = a.label("图标");
        it.setTextSize(12);
        it.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));
        l.addView(it);
        android.widget.GridView gv = new android.widget.GridView(a);
        gv.setNumColumns(6);
        final String[] pickIc = {c == null ? "box" : (c.icon == null ? "box" : c.icon)};
        gv.setAdapter(new android.widget.BaseAdapter() {
            public int getCount() { return ICONS.length; }
            public Object getItem(int p) { return ICONS[p]; }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                ImageView iv = new ImageView(a);
                iv.setImageDrawable(Ico.get(a, ICONS[p], (int) pick[0], 26));
                iv.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
                if (ICONS[p].equals(pickIc[0])) {
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(Ui.withAlpha((int) pick[0], 50));
                    g.setCornerRadius(Ui.dp(a, 10));
                    iv.setBackground(g);
                }
                iv.setOnClickListener(v -> { pickIc[0] = ICONS[p]; notifyDataSetChanged(); });
                return iv;
            }
        });
        l.addView(gv);

        boolean has = c != null;
        new android.app.AlertDialog.Builder(a)
                .setTitle(has ? "编辑分类" : "新建分类")
                .setView(l)
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { a.toast("请输入名称"); return; }
                    if (has) Db.get(a).updateCat(c.uuid, n, pickIc[0], pick[0]);
                    else Db.get(a).addCat(n, pickIc[0], pick[0], kind);
                    a.toast("已保存");
                    render();
                    if (done != null) done.run();
                })
                .setNeutralButton(has ? "删除" : null, has ? (d, w) -> {
                    new android.app.AlertDialog.Builder(a).setTitle("删除分类？")
                            .setMessage(kind == 1 ? "该分类下的链接会一并移入回收站" : "该分类下的账号会一并移入回收站")
                            .setPositiveButton("删除", (d2, w2) -> {
                                Db.get(a).delCat(c.uuid);
                                render();
                                if (done != null) done.run();
                            })
                            .setNegativeButton("取消", null).show();
                } : null)
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 主密码 / 指纹 ---------------- */

    private void changePwd() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(a, 18), Ui.dp(a, 8), Ui.dp(a, 18), 0);
        final EditText old = a.field("当前主密码", null);
        final EditText nw = a.field("新主密码（至少 6 位）", null);
        final EditText nw2 = a.field("确认新主密码", null);
        int p = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        old.setInputType(p); nw.setInputType(p); nw2.setInputType(p);
        l.addView(old); l.addView(nw); l.addView(nw2);
        new android.app.AlertDialog.Builder(a).setTitle("修改主密码").setView(l)
                .setPositiveButton("确定", (d, w) -> {
                    String o = old.getText().toString(), p1 = nw.getText().toString(), p2 = nw2.getText().toString();
                    if (p1.length() < 6) { a.toast("新密码至少 6 位"); return; }
                    if (!p1.equals(p2)) { a.toast("两次不一致"); return; }
                    byte[] salt = Crypto.unb64(Prefs.get("salt", ""));
                    SecretKey kek = Crypto.derive(o.toCharArray(), salt);
                    byte[] mk = Crypto.decrypt(kek.getEncoded(), Crypto.unb64(Prefs.get("mk", "")));
                    if (mk == null) { a.toast("当前密码错误"); return; }
                    byte[] ns = Crypto.random(16);
                    SecretKey nkek = Crypto.derive(p1.toCharArray(), ns);
                    Prefs.put("salt", Crypto.b64(ns));
                    Prefs.put("mk", Crypto.b64(Crypto.encrypt(nkek.getEncoded(), mk)));
                    KeystoreHelper.clearBio(a);
                    a.toast("已更新，请重新启用指纹");
                }).setNegativeButton("取消", null).show();
    }

    /* ---------------- 人脸解锁 ---------------- */

    /* ---------------- 启动自动解锁 ---------------- */

    private String unlockModeText() {
        switch (Prefs.getI("unlock_mode", 0)) {
            case 0:  return "人脸优先";
            case 1:  return "指纹优先";
            default: return "关闭";
        }
    }

    private void pickUnlockMode() {
        String[] items = {"人脸优先（启动先看脸）", "指纹优先（更安全）", "关闭（总是输主密码）"};
        int cur = Prefs.getI("unlock_mode", 0);
        new android.app.AlertDialog.Builder(a)
                .setTitle("启动自动解锁")
                .setSingleChoiceItems(items, cur, (d, w) -> {
                    Prefs.putI("unlock_mode", w);
                    d.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String faceSub() {
        if (KeystoreHelper.hasFace(a)) return "已启用，点击关闭";
        if (Build.VERSION.SDK_INT < 30) return "需 Android 11+";
        return Biometric.canWeak(a) ? "未启用，点击开启" : "未录入人脸";
    }

    private void toggleFace() {
        if (KeystoreHelper.hasFace(a)) {
            KeystoreHelper.clearFace(a);
            a.toast("已关闭人脸解锁");
            render();
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            a.toast("人脸解锁需 Android 11 及以上");
            return;
        }
        /* canWeak 在有指纹的设备上也返回 true，所以这里不能拿它拦人 ——
           真正能不能弹人脸，只有真去 authenticate 才知道。 */
        if (!Biometric.canWeak(a)) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("未检测到生物特征")
                    .setMessage("系统里没有录入指纹或人脸。\n\n"
                            + "要去系统设置里录人脸吗？录完回来再点这里启用。")
                    .setPositiveButton("去设置", (d, w) -> Biometric.openEnroll(a))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new android.app.AlertDialog.Builder(a)
                .setTitle("启用人脸解锁？")
                .setMessage("人脸属于「弱生物特征」，Android 不允许用它直接解密密钥，\n"
                        + "所以人脸认证通过后，主密钥仍从硬件 Keystore 取出。\n\n"
                        + "安全性略低于指纹（少了「密钥必须由生物特征解锁」这层）。\n"
                        + "如果你的手机同时有指纹，建议优先用指纹。")
                .setPositiveButton("仍要启用", (d, w) -> Biometric.authWeak(a,
                        new Biometric.SimpleCb() {
                            @Override public void ok() {
                                byte[] mk = Session.key();
                                if (mk != null && KeystoreHelper.saveFace(a, mk)) {
                                    a.toast("已启用人脸解锁");
                                } else a.toast("启用失败");
                                render();
                            }
                            @Override public void fail(String m) {
                                if ("cancel".equals(m)) return;
                                new android.app.AlertDialog.Builder(a)
                                        .setTitle("人脸启用失败")
                                        .setMessage("系统返回：\n" + m + "\n\n"
                                                + "可先去系统设置确认人脸已录入，再回来重试。")
                                        .setPositiveButton("去系统设置",
                                                (x, y) -> Biometric.openEnroll(a))
                                        .setNegativeButton("关闭", null)
                                        .show();
                            }
                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleBio() {
        if (KeystoreHelper.hasBio(a)) {
            KeystoreHelper.clearBio(a);
            a.toast("已关闭指纹解锁");
            render();
            return;
        }
        Cipher c = KeystoreHelper.encCipher();
        if (c == null) { a.toast("该设备不支持生物识别"); return; }
        Biometric.auth(a, c, new Biometric.Cb() {
            @Override public void ok(Cipher cc) {
                if (cc != null && KeystoreHelper.saveBio(a, cc, Session.key())) a.toast("已启用指纹解锁");
                else a.toast("启用失败");
                render();
            }
            @Override public void fail(String m) { a.toast("未启用：" + m); }
        });
    }

    /** 主题切换：用配置上下文实现，无需第三方库 */
    public static Context wrap(Context c) {
        /* 以 Skin 的深浅为准设置 uiMode，让 values-night 里的状态栏/导航栏配色
           与界面实际配色保持一致（否则会出现「界面深色但状态栏文字还是黑的」）。 */
        int night = Skin.isDark(c) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return c.createConfigurationContext(cfg);
    }

    /* ---------------- 关于 / 免责声明 ---------------- */

    private void aboutDialog() {
        String msg = "密盒 VaultKey v" + appVer() + "\n"
                + "开发者：tchw521\n"
                + "开源地址：github.com/tchw521/VaultKey\n"
                + "\n"
                + "── 技术 ──\n"
                + "端到端加密：AES-256-GCM\n"
                + "密钥派生：PBKDF2-HMAC-SHA256 " + (Crypto.ITER / 1000) + "k 次\n"
                + "主密钥由 Android Keystore 保护，支持指纹解锁\n"
                + "数据仅存本机；同步文件在上传前已用主密钥加密\n"
                + "\n"
                + "── 免责声明 ──\n"
                + "本软件按「原样」提供，不作任何明示或暗示的担保，\n"
                + "包括但不限于对特定用途的适用性、不侵权性。\n"
                + "\n"
                + "作者不对以下情况负责：\n"
                + "· 因设备故障、误操作、软件缺陷导致的数据丢失\n"
                + "· 因忘记主密码而无法解密（主密码不上传、无法找回）\n"
                + "· 因使用本软件产生的任何直接或间接损失\n"
                + "\n"
                + "请务必自行备份。主密码一旦遗忘，数据将无法恢复。\n"
                + "\n"
                + "完整条款见开源仓库 LICENSE（MIT）。";
        new android.app.AlertDialog.Builder(a)
                .setTitle("关于密盒")
                .setMessage(msg)
                .setPositiveButton("好", null)
                .setNegativeButton("检查更新", (d, w) -> checkUpdate())
                .setNeutralButton("赞助", (d, w) -> sponsorDialog())
                .show();
    }

    /* ---------------- 检查更新 ---------------- */

    private void checkUpdate() {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(a);
        pd.setMessage("正在检查…");
        pd.setCancelable(true);
        pd.show();

        final String cur = appVer();
        new Thread(() -> {
            Updater.Info info = Updater.fetch();
            a.runOnUiThread(() -> {
                try { pd.dismiss(); } catch (Exception ignored) { }
                if (!info.ok) {
                    new android.app.AlertDialog.Builder(a)
                            .setTitle("检查失败")
                            .setMessage(info.error == null ? "未知原因" : info.error)
                            .setPositiveButton("好", null)
                            .setNeutralButton("去网页看", (d, w) ->
                                    Updater.open(a, "https://github.com/" + Updater.REPO + "/releases"))
                            .show();
                    return;
                }
                boolean newer = Updater.compare(info.version, cur) > 0;
                showUpdateResult(cur, info, newer);
            });
        }).start();
    }

    private void showUpdateResult(String cur, Updater.Info info, boolean newer) {
        if (!newer) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("已是最新版")
                    .setMessage("当前 v" + cur + "\n云端 v" + info.version + "\n\n无需更新")
                    .setPositiveButton("好", null)
                    .setNeutralButton("下载页", (d, w) -> openDownload(info))
                    .show();
            return;
        }
        StringBuilder m = new StringBuilder();
        m.append("当前 v").append(cur).append("\n")
         .append("最新 v").append(info.version).append("\n");
        if (info.apkSize > 0)
            m.append("大小 ").append(info.apkSize / 1024).append(" KB").append("\n");
        if (info.notes != null && !info.notes.trim().isEmpty()) {
            String n = info.notes.trim();
            /* Release 说明可能很长，截断避免弹窗滚不动 */
            if (n.length() > 400) n = n.substring(0, 400) + "…";
            m.append("\n").append(n);
        }
        new android.app.AlertDialog.Builder(a)
                .setTitle("发现新版本")
                .setMessage(m.toString())
                .setPositiveButton("下载", (d, w) -> openDownload(info))
                .setNegativeButton("以后", null)
                .setNeutralButton("蓝奏云", (d, w) -> lanzouDialog())
                .show();
    }

    /** 打开下载页。优先 APK 直链，没有就打开 Release 页面 */
    private void openDownload(Updater.Info info) {
        String u = (info != null && info.apkUrl != null && !info.apkUrl.isEmpty())
                ? info.apkUrl : "https://github.com/" + Updater.REPO + "/releases";
        Updater.open(a, u);
    }

    /** 蓝奏云备用下载：国内网络更稳，且能避免 GitHub 直连不畅 */
    private void lanzouDialog() {
        String msg = "蓝奏云备用下载地址：\n\n"
                + Updater.LANZOU_URL + "\n\n"
                + "提取密码：" + Updater.LANZOU_PWD + "\n\n"
                + "点「打开」会用浏览器进入，输入密码后即可下载。";
        new android.app.AlertDialog.Builder(a)
                .setTitle("蓝奏云下载")
                .setMessage(msg)
                .setPositiveButton("打开", (d, w) -> Updater.open(a, Updater.LANZOU_URL))
                .setNeutralButton("复制密码", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pwd", Updater.LANZOU_PWD));
                    a.toast("密码已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /* ---------------- 赞助 ---------------- */

    private void sponsorDialog() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 18);
        root.setPadding(p, p, p, p);

        TextView tip = new TextView(a);
        tip.setText("如果密盒帮到了你，可以请作者喝杯咖啡 ☕");
        tip.setTextSize(13);
        tip.setTextColor(Ui.attr(a, R.attr.textColor2));
        tip.setGravity(android.view.Gravity.CENTER);
        root.addView(tip);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setPadding(0, Ui.dp(a, 14), 0, 0);
        row.addView(payCell("支付宝", R.drawable.sponsor_alipay));
        row.addView(payCell("微信支付", R.drawable.sponsor_wechat));
        root.addView(row);

        TextView note = new TextView(a);
        note.setText("长按二维码可保存到相册，再用对应 App 扫一扫");
        note.setTextSize(11);
        note.setTextColor(Ui.attr(a, R.attr.textColor2));
        note.setGravity(android.view.Gravity.CENTER);
        note.setPadding(0, Ui.dp(a, 14), 0, 0);
        root.addView(note);

        new android.app.AlertDialog.Builder(a)
                .setTitle("赞助作者")
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 一张支付二维码：图 + 名称，长按保存到相册 */
    private View payCell(String name, int resId) {
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);

        int sz = Ui.dp(a, 130);
        ImageView iv = new ImageView(a);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageResource(resId);
        iv.setBackground(Ui.glass(a, 14, R.attr.cardColor, R.attr.strokeColor));
        iv.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));

        /* 长按保存：扫码要切到另一个 App，在本弹窗里没法直接扫 */
        iv.setOnLongClickListener(v -> {
            savePayQr(resId, name);
            return true;
        });
        cell.addView(iv);

        TextView t = new TextView(a);
        t.setText(name);
        t.setTextSize(12);
        t.setTextColor(Ui.attr(a, R.attr.textColor));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(a, 8), 0, 0);
        cell.addView(t);
        return cell;
    }

    /** 把二维码存到相册（Android 9 及以下需要存储权限，失败就提示） */
    private void savePayQr(int resId, String name) {
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeResource(
                    a.getResources(), resId);
            String path = android.provider.MediaStore.Images.Media.insertImage(
                    a.getContentResolver(), b, "密盒赞助-" + name, "密盒赞助二维码");
            if (path != null) a.toast("已保存到相册");
            else a.toast("保存失败");
        } catch (Exception e) {
            a.toast("保存失败，可截图后扫码");
        }
    }

}