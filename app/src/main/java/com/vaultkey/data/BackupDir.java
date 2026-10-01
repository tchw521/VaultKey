package com.vaultkey.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地备份目录（用户授权的固定文件夹）。
 *
 * 之前每次导出都要弹一次系统文件选择器、手动挑位置，导出的备份散落在
 * 下载目录各个角落。授权一次之后，备份都写到同一个地方：
 *   - 不用每次选路径
 *   - 用 FolderSync 之类的工具同步这个文件夹，就等于有了云备份
 *   - 换机时直接拷走整个文件夹
 *
 * 重要：这里只放**导出的备份文件**，主数据库仍在 app 私有目录，不动。
 * 主库放公共区会让任何其他应用看到"你有多少条账号、什么时候改过"。
 */
public final class BackupDir {

    private static final String K_URI = "backup_dir_uri";
    private static final String K_NAME = "backup_dir_name";

    public static boolean has(Context c) {
        String s = Prefs.get(K_URI, "");
        return !s.isEmpty();
    }

    public static String name(Context c) { return Prefs.get(K_NAME, ""); }

    /** 保存授权结果。调用方需已拿到 ACTION_OPEN_DOCUMENT_TREE 的返回。 */
    public static boolean save(Context c, Uri tree) {
        if (tree == null) return false;
        try {
            /* 不加这个，重启手机后就没权限了 —— 备份会静默失败 */
            c.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
        Prefs.put(K_URI, tree.toString());
        Prefs.put(K_NAME, displayOf(c, tree));
        return true;
    }

    public static Uri uri(Context c) {
        String s = Prefs.get(K_URI, "");
        if (s.isEmpty()) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    public static void clear(Context c) {
        Prefs.put(K_URI, "");
        Prefs.put(K_NAME, "");
    }

    private static String displayOf(Context c, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            android.database.Cursor cu = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cu != null) {
                if (cu.moveToFirst()) {
                    String n = cu.getString(0);
                    cu.close();
                    return n == null ? "备份文件夹" : n;
                }
                cu.close();
            }
        } catch (Exception ignored) { }
        return "备份文件夹";
    }

    /**
     * 目录是否还可用。
     *
     * 这是最容易被忽略、也最容易出问题的地方：文件夹可能被移动、删除，
     * 或者用户清了某个 app 的数据导致权限回收。不检测的话备份会静默失败 ——
     * 用户以为一直在备份，其实一个都没写进去。
     */
    public static boolean alive(Context c) {
        Uri t = uri(c);
        if (t == null) return false;
        try {
            ContentResolver r = c.getContentResolver();
            /* 权限被回收时 query 会抛 SecurityException 或返回 null */
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t,
                    DocumentsContract.getTreeDocumentId(t));
            android.database.Cursor cu = r.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null);
            if (cu == null) return false;
            cu.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把权限也一并释放（切换文件夹时用） */
    public static void release(Context c) {
        Uri t = uri(c);
        if (t != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(t,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        clear(c);
    }

    /**
     * 写入一个备份文件。同名则覆盖。
     * @return 成功返回文件 Uri，失败返回 null
     */
    public static Uri write(Context c, String fileName, String mime, byte[] data) {
        Uri t = uri(c);
        if (t == null) return null;
        try {
            ContentResolver r = c.getContentResolver();
            /* 先删掉同名旧文件，否则会生成 "xxx (1).csv" 这种副本 */
            for (Item it : list(c)) {
                if (fileName.equals(it.name)) {
                    try { DocumentsContract.deleteDocument(r, it.uri); } catch (Exception ignored) { }
                }
            }
            Uri f = DocumentsContract.createDocument(r, t, mime, fileName);
            if (f == null) return null;
            java.io.OutputStream o = r.openOutputStream(f);
            if (o == null) return null;
            o.write(data);
            o.flush();
            o.close();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Item {
        public String name;
        public long size;
        public long when;
        public Uri uri;
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        Uri t = uri(c);
        if (t == null) return out;
        try {
            ContentResolver r = c.getContentResolver();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t,
                    DocumentsContract.getTreeDocumentId(t));
            android.database.Cursor cu = r.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            }, null, null, null);
            if (cu == null) return out;
            while (cu.moveToNext()) {
                Item it = new Item();
                String id = cu.getString(0);
                it.uri = DocumentsContract.buildDocumentUriUsingTree(t, id);
                it.name = cu.getString(1);
                it.size = cu.isNull(2) ? 0 : cu.getLong(2);
                it.when = cu.isNull(3) ? 0 : cu.getLong(3);
                out.add(it);
            }
            cu.close();
        } catch (Exception ignored) { }
        return out;
    }

    /** 发起授权（调用方 startActivityForResult） */
    public static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return i;
    }

    /** 打开系统文件管理器查看该目录 */
    public static boolean view(Context c) {
        Uri t = uri(c);
        if (t == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(DocumentsContract.buildDocumentUriUsingTree(t,
                    DocumentsContract.getTreeDocumentId(t)), "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private BackupDir() { }
}
