package com.vaultkey.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import com.vaultkey.R;
import com.vaultkey.data.Prefs;
import com.vaultkey.ui.UnlockActivity;

/**
 * 桌面小部件：点一下直接进密盒并聚焦搜索框。
 *
 * 安全取舍：小部件上**绝不显示任何账号或密码** —— 桌面会被其他应用读到、
 * 也会被截屏进最近任务。它只做一件事：跳到搜索页。所有内容仍需解锁后查看。
 */
public final class SearchWidget extends AppWidgetProvider {
    public static final String ACTION_OPEN = "com.vaultkey.app.WIDGET_OPEN";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Prefs.init(c);
        for (int id : ids) updateOne(c, m, id);
    }

    private static void updateOne(Context c, AppWidgetManager m, int id) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_search);
        rv.setOnClickPendingIntent(R.id.w_root, pi(c));
        rv.setOnClickPendingIntent(R.id.w_go, pi(c));
        rv.setOnClickPendingIntent(R.id.w_icon, pi(c));
        m.updateAppWidget(id, rv);
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, UnlockActivity.class)
                .setAction(ACTION_OPEN)
                .putExtra("search", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 7001, i, f);
    }

    /** 数据变化时可调用，强制刷新全部小部件 */
    public static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            ComponentName cn = new ComponentName(c, SearchWidget.class);
            int[] ids = m.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;
            for (int id : ids) updateOne(c, m, id);
        } catch (Exception ignored) { }
    }
}
