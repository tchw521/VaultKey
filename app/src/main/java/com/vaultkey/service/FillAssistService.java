package com.vaultkey.service;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Toast;
import com.vaultkey.R;
import com.vaultkey.data.Db;
import com.vaultkey.data.Prefs;
import com.vaultkey.data.Session;
import com.vaultkey.ui.AssistActivity;
import java.util.ArrayList;
import java.util.List;

/** 无障碍辅助填充：自动填充服务不生效的 App 内也能一键填入 */
public final class FillAssistService extends AccessibilityService {
    static FillAssistService self;
    static AccessibilityNodeInfo userNode, passNode;
    static String curPkg = "", curTitle = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastNotify;

    public static boolean running() { return self != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        Prefs.putB("assist_on", true);
    }

    @Override public void onDestroy() { self = null; Prefs.putB("assist_on", false); super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (!Prefs.getB("assist_enable", true)) return;
        int t = ev.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        h.removeCallbacks(scan);
        h.postDelayed(scan, 500);
    }

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            try {
                if (Session.key() == null) return;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                List<AccessibilityNodeInfo> edits = new ArrayList<>();
                collect(root, edits);
                if (edits.isEmpty()) return;
                userNode = null; passNode = null;
                for (AccessibilityNodeInfo n : edits) {
                    boolean pwd = n.isPassword();
                    if (pwd && passNode == null) passNode = n;
                    else if (!pwd && userNode == null) userNode = n;
                    else if (pwd && passNode != null) { }
                }
                if (userNode == null && passNode == null) return;
                curPkg = pkg;
                if (System.currentTimeMillis() - lastNotify < 20000) return;
                lastNotify = System.currentTimeMillis();
                notifyFill();
            } catch (Exception ignored) { }
        }
    };

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.contains("EditText") && n.isEditable()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    private void notifyFill() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String ch = "vaultkey_fill";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "填充提示", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Intent i = new Intent(this, AssistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 7, i, flags);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, ch)
                : new android.app.Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("密盒：可填充账号")
                .setContentText("点击选择要填入的账号")
                .setContentIntent(pi).setAutoCancel(true);
        nm.notify(77, b.build());
    }

    public static String targetLabel(Context c) {
        if (curPkg.isEmpty()) return "";
        String n = com.vaultkey.util.Icons.appName(c, curPkg);
        return n == null ? curPkg : n;
    }

    /** 由 AssistActivity 调用，执行填充 */
    public static void doFill(Context c, Db.Entry e) {
        FillAssistService s = self;
        if (s == null) { Toast.makeText(c, "辅助填充服务未开启", Toast.LENGTH_SHORT).show(); return; }
        if (Session.key() == null) { Toast.makeText(c, "请先解锁", Toast.LENGTH_SHORT).show(); return; }
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) { Toast.makeText(c, "未获取到输入框", Toast.LENGTH_SHORT).show(); return; }
        List<AccessibilityNodeInfo> edits = new ArrayList<>();
        s.collect(root, edits);
        AccessibilityNodeInfo un = null, pn = null;
        for (AccessibilityNodeInfo n : edits) {
            if (n.isPassword() && pn == null) pn = n;
            else if (!n.isPassword() && un == null) un = n;
        }
        setText(un, e.user);
        setText(pn, e.pass);
        Toast.makeText(c, "已填入 " + e.title, Toast.LENGTH_SHORT).show();
    }

    private static void setText(AccessibilityNodeInfo n, String v) {
        if (n == null || v == null) return;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    @Override public void onInterrupt() { }
}
