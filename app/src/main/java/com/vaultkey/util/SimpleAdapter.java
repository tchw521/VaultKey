package com.vaultkey.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * 列表适配器通用基类 —— 消掉每处都抄一遍的 BaseAdapter 样板。
 *
 * 之前项目里有 6 个内部类各自实现了 getCount / getItem / getItemId / getView，
 * 四段里三段内容完全一样，只有 getView 不同。抄一遍就多一处要维护的地方，
 * 改一处还得记得改另外五处。
 *
 * 现在子类只需要实现 view()。
 *
 * 用法：
 * <pre>
 *   ad = new SimpleAdapter&lt;Db.Card&gt;() {
 *       public View view(int pos, Db.Card c, View cv, ViewGroup parent) {
 *           // 只写这一行真正有差异的部分
 *       }
 *   };
 *   ad.setData(list);          // 换数据
 *   ad.add(item);              // 追加
 *   ad.at(0);                  // 取元素（不用强转）
 * </pre>
 *
 * 需要自定义 id 的（比如用数据库主键而不是下标）覆写 idOf()。
 */
public abstract class SimpleAdapter<T> extends BaseAdapter {

    private final List<T> rows = new ArrayList<>();

    /* ---------------- 数据 ---------------- */

    public void setData(List<T> d) {
        rows.clear();
        if (d != null) rows.addAll(d);
        onDataChanged();
        notifyDataSetChanged();
    }

    public void add(T t) {
        rows.add(t);
        onDataChanged();
        notifyDataSetChanged();
    }

    public void addAll(List<T> d) {
        if (d == null) return;
        rows.addAll(d);
        onDataChanged();
        notifyDataSetChanged();
    }

    public void remove(T t) {
        rows.remove(t);
        onDataChanged();
        notifyDataSetChanged();
    }

    public void clear() {
        rows.clear();
        onDataChanged();
        notifyDataSetChanged();
    }

    public List<T> data() { return rows; }

    /** 取元素，不用每次都强转 */
    public T at(int pos) {
        return (pos >= 0 && pos < rows.size()) ? rows.get(pos) : null;
    }

    /** 数据变了要做点别的（比如重算字母索引）就覆写这个 */
    protected void onDataChanged() { }

    /* ---------------- BaseAdapter 样板 ---------------- */

    @Override public int getCount() { return rows.size(); }

    @Override public T getItem(int pos) { return at(pos); }

    /** 默认用下标；要用主键就覆写 */
    protected long idOf(T item, int pos) { return pos; }

    @Override public long getItemId(int pos) {
        T t = at(pos);
        return t == null ? pos : idOf(t, pos);
    }

    /** 子类唯一必须实现的方法 */
    public abstract View view(int pos, T item, View cv, ViewGroup parent);

    @Override public View getView(int pos, View cv, ViewGroup parent) {
        return view(pos, at(pos), cv, parent);
    }

    /* ---------------- 多类型行（比如带字母分组头的列表） ---------------- */

    /** 行类型数量，默认 1 种 */
    protected int typeCount() { return 1; }

    /** 某行属于哪种类型，默认全是 0 */
    protected int typeOf(T item, int pos) { return 0; }

    /** 某行是否可点击，默认都行 */
    protected boolean enabled(T item, int pos) { return true; }

    @Override public int getViewTypeCount() { return Math.max(1, typeCount()); }

    @Override public int getItemViewType(int pos) {
        T t = at(pos);
        if (t == null) return 0;
        int n = typeCount();
        int v = typeOf(t, pos);
        /* 越界会让 ListView 直接崩，兜一下 */
        return (v >= 0 && v < n) ? v : 0;
    }

    @Override public boolean isEnabled(int pos) {
        T t = at(pos);
        return t == null || enabled(t, pos);
    }
}
