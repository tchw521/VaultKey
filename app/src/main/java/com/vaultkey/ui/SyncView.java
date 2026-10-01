package com.vaultkey.ui;

import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vaultkey.R;
import com.vaultkey.data.BackupDir;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.sync.Sync;
import com.vaultkey.util.Ico;
import com.vaultkey.util.Ui;
import com.vaultkey.webdav.WebDavClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 坚果云（WebDAV）双向同步设置 */
public final class SyncView {
    private final BaseActivity a;
    private final Runnable onBack;
    private LinearLayout box;
    private EditText server, user, pass, dirEdit;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    /** 复用 ToolsView.Host：里面已含回收站 / 导入导出 / Bitwarden 全部回调 */
    private final ToolsView.Host host;

    public SyncView(BaseActivity a, Runnable onBack, ToolsView.Host host) {
        this.a = a; this.onBack = onBack; this.host = host;
    }

    public View view() {
        ScrollView sv = new ScrollView(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 110));
        sv.addView(box);
        render();
        return sv;
    }

    public void refresh() { if (box != null) render(); }

    private void render() {
        box.removeAllViews();

        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 0, 0, Ui.dp(a, 6));
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageDrawable(Ico.get(a, "back", Ui.attr(a, R.attr.textColor), 22));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        r.addView(iv);
        TextView x = a.title("坚果云同步", 22);
        x.setPadding(Ui.dp(a, 10), 0, 0, 0);
        r.addView(x);
        Ui.press(r);
        r.setOnClickListener(v -> { if (onBack != null) onBack.run(); });
        box.addView(r);

        TextView tip = a.label("在坚果云网页端「账户信息 → 安全选项 → 添加应用」生成应用密码，不要用登录密码。账号、分类、卡片三者的元数据会一起同步，上传前已用主密钥加密，坚果云只能看到密文。注意：卡片附件图片只存本机，不会随同步上传。");
        tip.setTextSize(11.5f);
        tip.setLineSpacing(Ui.dp(a, 2), 1.1f);
        tip.setPadding(0, 0, 0, Ui.dp(a, 10));
        box.addView(tip);

        /* 状态 */
        LinearLayout st = a.card();
        TextView s1 = new TextView(a);
        s1.setText(Sync.configured() ? "已配置 · " + Sync.lastSyncText() : "尚未配置坚果云账号");
        s1.setTextSize(15);
        s1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        s1.setTextColor(Ui.attr(a, R.attr.textColor));
        st.addView(s1);
        TextView s2 = a.label("本机账号 " + Db.get(a).count(false, -1) + " 条 · 卡片 " + Db.get(a).countCards(false)
                + " 张 · 回收站 " + (Db.get(a).count(true, -1) + Db.get(a).countCards(true)) + " 条");
        s2.setTextSize(12);
        s2.setPadding(0, Ui.dp(a, 4), 0, 0);
        st.addView(s2);
        box.addView(st);

        android.widget.Button go = a.button("立即双向同步", new int[]{a.accent(), a.accent2()});
        go.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 14));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = Ui.dp(a, 10);
        go.setLayoutParams(gl);
        go.setOnClickListener(v -> {
            if (!Sync.configured()) { a.toast("请先填写账号与应用密码并保存"); return; }
            doJob(() -> Sync.run(a));
        });
        box.addView(go);

        /* 连接自检：同步失败时最需要的是「到底哪一步不通」，
           而不是一句笼统的"检查网络或权限"。 */
        android.widget.Button diag = a.button("检测连接", null);
        diag.setTextColor(Ui.attr(a, R.attr.textColor));
        diag.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = Ui.dp(a, 8);
        diag.setLayoutParams(dl);
        diag.setOnClickListener(v -> {
            if (!Sync.configured()) { a.toast("请先填写账号与应用密码并保存"); return; }
            doJob(() -> Sync.diagnose(a));
        });
        box.addView(diag);

        box.addView(a.section("账号配置"));
        LinearLayout c = a.card();
        server = a.field("WebDAV 地址", Prefs.get("dav_url", WebDavClient.JIANGUOYUN));
        c.addView(server);
        user = a.field("坚果云账号（邮箱）", Prefs.get("dav_user", ""));
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        c.addView(user, lp(10));
        pass = a.field("应用密码", Prefs.get("dav_pass", ""));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(pass, lp(10));
        dirEdit = a.field("云端目录", Prefs.get("dav_path", "密盒-VaultKey"));
        c.addView(dirEdit, lp(10));
        android.widget.Button sv = a.button("保存配置", new int[]{a.accent3(), a.accent()});
        sv.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        sv.setLayoutParams(lp(14));
        sv.setOnClickListener(v -> {
            String u = server.getText().toString().trim();
            if (!u.endsWith("/")) u = u + "/";
            Prefs.put("dav_url", u);
            Prefs.put("dav_user", user.getText().toString().trim());
            Prefs.put("dav_pass", pass.getText().toString().trim());
            Prefs.put("dav_path", dirEdit.getText().toString().trim());
            a.toast("已保存");
            render();
        });
        c.addView(sv);
        box.addView(c);

        box.addView(a.section("数据"));
        box.addView(localBackupRow());
        box.addView(a.settingRow("trash", "回收站", "查看或恢复已删除的账号", v -> host.showTrash()));
        box.addView(a.settingRow("download", "导入 Bitwarden", "从 Bitwarden 导出文件导入（.json）",
                v -> host.importBitwarden()));
        box.addView(a.settingRow("upload", "导出 Bitwarden", "导出为可在其他密码器导入的标准格式",
                v -> host.exportBitwarden()));
        box.addView(a.settingRow("download", "导入 CSV", "从 Chrome / 其他密码器导入",
                v -> host.importCsv()));
        box.addView(a.settingRow("upload", "导出 CSV", "导出为明文 CSV 文件",
                v -> host.exportCsv()));

        box.addView(a.section("高级"));
        box.addView(a.settingRow("copy", "跳过写入探测",
                Prefs.getB("dav_skip_probe", false)
                        ? "已开启（不写测试文件，直接同步）" : "已关闭（推荐）", v -> {
            Prefs.putB("dav_skip_probe", !Prefs.getB("dav_skip_probe", false));
            render();
        }, Prefs.getB("dav_skip_probe", false) ? a.accent3() : 0, false));
        box.addView(a.settingRow("refresh", "进入应用时自动同步",
                Prefs.getB("auto_sync", false) ? "已开启" : "已关闭", v -> {
            Prefs.putB("auto_sync", !Prefs.getB("auto_sync", false));
            render();
        }, a.accent3(), false));
        box.addView(a.settingRow("download", "历史版本", "查看并恢复云端历史备份", v -> listHistory()));
        box.addView(a.settingRow("upload", "以本机覆盖云端", "把本机数据完整推到云端", v -> confirmUp()));
        box.addView(a.settingRow("download", "以云端覆盖本机", "放弃本机改动，拉取云端数据", v -> confirmDown()));
        box.addView(a.settingRow("copy", "保留历史份数", "当前保留 " + Prefs.getI("keep_backups", 10) + " 份", v -> {
            String[] o = {"5 份", "10 份", "20 份", "50 份"};
            final int[] vv = {5, 10, 20, 50};
            new android.app.AlertDialog.Builder(a).setTitle("保留历史份数").setItems(o, (d, w) -> {
                Prefs.putI("keep_backups", vv[w]);
                render();
            }).show();
        }));
    }

    /**
     * 本地备份文件夹。
     *
     * 之前每次导出都要手动挑一次位置，备份散落各处。授权一个固定文件夹后，
     * 导出默认写到那里 —— 配合 FolderSync 之类的工具同步该目录就等于云备份，
     * 换机时也能直接拷走。
     *
     * 注意：只放导出的备份文件。主数据库仍在 app 私有目录，不搬。
     */
    private View localBackupRow() {
        boolean has = BackupDir.has(a);
        String sub;
        if (!has) sub = "未授权，导出时会弹文件选择器";
        else if (!BackupDir.alive(a))
            sub = "⚠ 「" + BackupDir.name(a) + "」已不可访问，请重新授权";
        else sub = "已授权：" + BackupDir.name(a);

        final int[] tint = {BackupDir.has(a) && BackupDir.alive(a) ? a.accent3() : 0};
        View v = a.settingRow("folder", "本地备份文件夹", sub, x -> pickBackupDir(),
                tint[0], false);
        /* 长按：重新授权 / 取消授权 */
        v.setOnLongClickListener(x -> {
            if (!BackupDir.has(a)) { pickBackupDir(); return true; }
            String[] o = {"重新选择文件夹", "打开该文件夹", "取消授权"};
            new android.app.AlertDialog.Builder(a).setTitle("本地备份文件夹").setItems(o, (d, w) -> {
                if (w == 0) pickBackupDir();
                else if (w == 1) {
                    if (!BackupDir.view(a)) a.toast("打不开，可能已被删除");
                } else { BackupDir.release(a); a.toast("已取消授权"); render(); }
            }).show();
            return true;
        });
        return v;
    }

    private void pickBackupDir() {
        try {
            a.startActivityForResult(BackupDir.pickIntent(), ToolsView.REQ_BACKUP_DIR);
        } catch (Exception e) { a.toast("无法打开文件夹选择器"); }
    }

    /** 由 Activity 在 onActivityResult 里转进来 */
    public boolean onPickDirResult(int req, Intent data) {
        if (req != ToolsView.REQ_BACKUP_DIR) return false;
        if (data == null || data.getData() == null) return true;
        BackupDir.save(a, data.getData());
        a.toast(BackupDir.alive(a) ? "已授权「" + BackupDir.name(a) + "」" : "授权了，但读不到该目录");
        render();
        return true;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.dp(a, top);
        return l;
    }

    private void confirmUp() {
        new android.app.AlertDialog.Builder(a).setTitle("以本机覆盖云端？").setMessage("云端数据将被本机数据替换")
                .setPositiveButton("确定", (d, w) -> doJob(() -> Sync.forceUpload(a)))
                .setNegativeButton("取消", null).show();
    }

    private void confirmDown() {
        new android.app.AlertDialog.Builder(a).setTitle("以云端覆盖本机？").setMessage("本机数据将被云端数据替换")
                .setPositiveButton("确定", (d, w) -> doJob(() -> Sync.forceDownload(a)))
                .setNegativeButton("取消", null).show();
    }

    private void doJob(java.util.concurrent.Callable<Sync.Result> job) {
        final android.app.ProgressDialog pd = a.progress("正在同步…");
        pd.show();
        pool.submit(() -> {
            Sync.Result r;
            try { r = job.call(); } catch (Exception e) { r = new Sync.Result(false, "同步异常"); }
            final Sync.Result rr = r;
            a.runOnUiThread(() -> {
                a.safeDismiss(pd);
                String m = rr.msg;
                if (rr.pulled > 0 || rr.removed > 0) {
                    m += "\n拉回 " + rr.pulled + " 项" + (rr.removed > 0 ? "，删除 " + rr.removed + " 项" : "");
                }
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(a)
                        .setTitle(rr.ok ? "同步完成" : "同步未完成")
                        .setMessage(m)
                        .setPositiveButton("好", (d, w) -> render());
                /*
                 * 探测失败时给一个「跳过写入探测」的快捷入口。
                 * 前两步（访问根目录、建目录）都过了，说明账号和路径没问题，
                 * 卡住的可能只是这个服务器不接受探针文件 ——
                 * 与其让用户干瞪眼，不如让他一键跳过，直接试着同步。
                 */
                if (rr.skipProbeHint && !Prefs.getB("dav_skip_probe", false)) {
                    b.setNeutralButton("跳过探测重试", (d, w) -> {
                        Prefs.putB("dav_skip_probe", true);
                        a.toast("已开启「跳过写入探测」，重新检测…");
                        render();
                        doJob(() -> Sync.diagnose(a));
                    });
                }
                b.show();
            });
            return null;
        });
    }

    private void listHistory() {
        if (!Sync.configured()) { a.toast("请先配置账号"); return; }
        final android.app.ProgressDialog pd = a.progress("读取云端…");
        pd.show();
        pool.submit(() -> {
            WebDavClient w = Sync.client();
            List<WebDavClient.Item> items = w.list(Sync.remoteDir() + "/history");
            List<String> names = new ArrayList<>();
            if (items != null) for (WebDavClient.Item it : items) names.add(it.name);
            Collections.sort(names, Collections.<String>reverseOrder());
            final List<String> nn = names;
            a.runOnUiThread(() -> {
                a.safeDismiss(pd);
                if (nn.isEmpty()) { a.toast("云端暂无历史版本"); return; }
                new android.app.AlertDialog.Builder(a).setTitle("历史版本（" + nn.size() + "）")
                        .setItems(nn.toArray(new String[0]), (d2, w2) -> {
                            new android.app.AlertDialog.Builder(a).setTitle("恢复该版本？")
                                    .setMessage("会用该版本覆盖本机全部数据")
                                    .setPositiveButton("恢复", (d3, w3) -> restoreOne(nn.get(w2)))
                                    .setNegativeButton("取消", null).show();
                        }).show();
            });
            return null;
        });
    }

    private void restoreOne(String name) {
        final android.app.ProgressDialog pd = a.progress("恢复中…");
        pd.show();
        pool.submit(() -> {
            WebDavClient w = Sync.client();
            byte[] f = w.get(Sync.remoteDir() + "/history/" + name);
            String json = Sync.decode(f);
            final boolean ok = json != null && Db.get(a).replaceAll(json);
            a.runOnUiThread(() -> {
                a.safeDismiss(pd);
                a.toast(ok ? "已恢复" : "恢复失败：该版本可能由其他主密码创建");
                render();
            });
            return null;
        });
    }
}
