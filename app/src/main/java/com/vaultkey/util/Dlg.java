package com.vaultkey.util;

import android.app.AlertDialog;
import android.content.Context;

/**
 * 弹窗工具 —— 把反复手写的 AlertDialog 模板收成一行。
 *
 * 之前 SettingsView 一个类里就有 23 处 AlertDialog.Builder，
 * 每处都是 new Builder → setTitle → setItems/setMessage → show 四步，
 * 真正不同的只有标题和数据。抄多了容易漏掉 setNegativeButton，
 * 导致弹窗只能按返回键关掉（有些机型返回键还会被吞）。
 *
 * 统一走这里后，每个弹窗都默认带「取消」按钮。
 */
public final class Dlg {

    /** 选中某项 */
    public interface OnPick { void on(int which); }

    /** 确定 / 取消 */
    public interface OnYes { void yes(); }

    private Dlg() { }

    /* ---------------- 列表选择 ---------------- */

    /** 简单列表：点一下就执行并关闭 */
    public static void pick(Context c, String title, String[] items, OnPick cb) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setItems(items, (d, w) -> { if (cb != null) cb.on(w); })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单选列表：带当前选中项，选中后关闭 */
    public static void single(Context c, String title, String[] items, int cur, OnPick cb) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setSingleChoiceItems(items, cur, (d, w) -> {
                    if (cb != null) cb.on(w);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 确认 / 提示 ---------------- */

    /**
     * 确认框。okText 传 null 时用「确定」。
     * 危险操作建议把 okText 写具体些（比如「彻底删除」），别只写「确定」。
     */
    public static void confirm(Context c, String title, String msg, String okText, OnYes cb) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(okText == null ? "确定" : okText,
                        (d, w) -> { if (cb != null) cb.yes(); })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 纯提示 */
    public static void info(Context c, String title, String msg) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null)
                .show();
    }

    /** 提示 + 一个中性按钮（比如「去系统设置」） */
    public static void infoWithNeutral(Context c, String title, String msg,
                                       String neutral, OnYes onNeutral) {
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null);
        if (neutral != null) {
            b.setNeutralButton(neutral, (d, w) -> { if (onNeutral != null) onNeutral.yes(); });
        }
        b.show();
    }

    /**
     * 危险操作确认 —— 按钮文案更明确，默认焦点在「取消」上。
     * 用于清空回收站、彻底删除这类不可逆操作。
     */
    public static void danger(Context c, String title, String msg, String okText, OnYes cb) {
        AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(okText, (dd, w) -> { if (cb != null) cb.yes(); })
                .setNegativeButton("取消", null)
                .create();
        d.show();
        /* 焦点给「取消」，避免手快误点 */
        d.getButton(AlertDialog.BUTTON_POSITIVE).setFocusable(false);
    }
}
