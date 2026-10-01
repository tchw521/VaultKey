package com.vaultkey.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** 内容区容器：账号列表与卡包列表叠放，按当前区域切换可见性 */
final class FrameListHost extends FrameLayout {
    private final View acc;
    private final View card;

    FrameListHost(Context c, View accountView, View cardView) {
        super(c);
        acc = accountView;
        card = cardView;
        addView(acc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        /* 链接库没有卡包，cardView 传 null —— 此时不添加第二层，
           show() 也只管账号层，避免空指针 */
        if (card != null) {
            addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        show(true);
    }

    void show(boolean isAccount) {
        acc.setVisibility(isAccount ? View.VISIBLE : View.GONE);
        if (card != null) card.setVisibility(isAccount ? View.GONE : View.VISIBLE);
    }
}
