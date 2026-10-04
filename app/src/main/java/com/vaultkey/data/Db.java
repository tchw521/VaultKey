package com.vaultkey.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地库。为支持多设备同步，业务主键一律使用 uuid（跨设备稳定），
 * 自增 _id 只作为本地行号。删除为墓碑（del=1）以便同步传播。
 */
public final class Db extends SQLiteOpenHelper {
    public static final int VER = 8;
    private static Db inst;
    private final byte[] k;
    private final Context ctx;
    private boolean repaired;

    public static synchronized Db get(Context c) {
        if (inst == null || inst.k == null || inst.k != Session.key()) {
            inst = new Db(c.getApplicationContext(), Session.key());
        }
        if (!inst.repaired) {
            inst.repaired = true;
            try {
                inst.repair();
            } catch (Exception e) {
                try {
                    c.getApplicationContext().deleteDatabase("vault.db");
                    inst = new Db(c.getApplicationContext(), Session.key());
                    inst.repaired = true;
                    inst.repair();
                } catch (Exception ignored) { }
            }
        }
        return inst;
    }
    private Db(Context c, byte[] key) {
        super(c, "vault.db", null, VER);
        k = key;
        ctx = c;
    }

    private static final String SQL_CATS = "CREATE TABLE IF NOT EXISTS cats(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " uuid TEXT UNIQUE, name TEXT, icon TEXT, color INTEGER, sort INTEGER, del INTEGER DEFAULT 0, mtime INTEGER,"
            + " kind INTEGER DEFAULT 0)";
    private static final String SQL_ENTRIES = "CREATE TABLE IF NOT EXISTS entries(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " uuid TEXT UNIQUE, cat_uuid TEXT, title TEXT, user TEXT, pass TEXT, url TEXT, notes TEXT, pkg TEXT,"
            + " totp TEXT, fav INTEGER DEFAULT 0, del INTEGER DEFAULT 0, ts INTEGER, mtime INTEGER, uses INTEGER DEFAULT 0,"
            + " kind INTEGER DEFAULT 0)";
    private static final String SQL_META = "CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)";
    private static final String SQL_CARDS = "CREATE TABLE IF NOT EXISTS cards(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " uuid TEXT UNIQUE, kind TEXT, title TEXT, fields TEXT, imgs TEXT, note TEXT,"
            + " fav INTEGER DEFAULT 0, del INTEGER DEFAULT 0, ts INTEGER, mtime INTEGER)";

    /**
     * 每次打开数据库都自检一次分类归属。
     *
     * 只在 MainActivity.onCreate 里跑是不够的 —— 自动填充、悬浮窗、
     * 小部件这些入口不经过 MainActivity，数据就不会被修正。
     * 这里放在开库路径上，任何入口进来都是修好的数据。
     *
     * 代价很小：两条只返回数字的查询，没命中就直接返回。
     */
    @Override public void onOpen(SQLiteDatabase d) {
        super.onOpen(d);
        try { fixCrossKindCats(); } catch (Exception ignored) { }
    }

    @Override public void onCreate(SQLiteDatabase d) {
        try {
            d.execSQL(SQL_CATS);
            d.execSQL(SQL_ENTRIES);
            d.execSQL(SQL_META);
            d.execSQL(SQL_CARDS);
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_e_cat ON entries(cat_uuid)");
            seed(d);
        } catch (Exception ignored) { }
    }

    @Override public void onUpgrade(SQLiteDatabase d, int o, int n) {
        try {
            if (o < 2) {
                // v1 -> v2：主键改为 uuid，无法安全迁移，重建（v1 仅内部测试版）
                d.execSQL("DROP TABLE IF EXISTS cats");
                d.execSQL("DROP TABLE IF EXISTS entries");
                onCreate(d);
                return;
            }
            // v2 -> v3：新增卡包表，账号与分类数据保留
            d.execSQL(SQL_CARDS);
            if (o < 3) return;
            // v3 -> v4：新增标签列
            try { d.execSQL("ALTER TABLE entries ADD COLUMN tags TEXT"); } catch (Exception ignored) { }
            if (o < 4) return;
            // v4 -> v5：新增图标缓存键列
            try { d.execSQL("ALTER TABLE entries ADD COLUMN icon_key TEXT"); } catch (Exception ignored) { }
            if (o < 5) return;
            // v5 -> v6：新增自定义字段列
            try { d.execSQL("ALTER TABLE entries ADD COLUMN custom TEXT"); } catch (Exception ignored) { }
            try { d.execSQL("ALTER TABLE entries ADD COLUMN kind INTEGER DEFAULT 0"); } catch (Exception ignored) { }
            if (o < 6) return;
            // v6 -> v7：新增 kind 列（0=账号 / 1=网址收藏），
            // 与「链接库」共用同一张表，复用分类、标签、图标、搜索能力。
            // 已有行默认 0（账号），不受影响。
            try { d.execSQL("ALTER TABLE entries ADD COLUMN kind INTEGER DEFAULT 0"); } catch (Exception ignored) { }
            if (o < 7) return;
            // v7 -> v8：分类也按库独立。
            // 1) cats 表加 kind 列，已有分类归密码箱（kind=0），你的分类不动
            try { d.execSQL("ALTER TABLE cats ADD COLUMN kind INTEGER DEFAULT 0"); } catch (Exception ignored) { }
            // 2) 为链接库复制一套默认分类，否则链接库一进去就是空的
            try {
                Cursor cc = d.rawQuery("SELECT COUNT(*) FROM cats WHERE kind=1", null);
                int n1 = 0;
                if (cc.moveToFirst()) n1 = cc.getInt(0);
                cc.close();
                if (n1 == 0) seedLinks(d);
            } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    private void repair() {
        SQLiteDatabase d = getWritableDatabase();
        try {
            d.execSQL(SQL_CATS);
            d.execSQL(SQL_ENTRIES);
            d.execSQL(SQL_META);
            d.execSQL(SQL_CARDS);
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_e_cat ON entries(cat_uuid)");
            try { d.execSQL("ALTER TABLE entries ADD COLUMN tags TEXT"); } catch (Exception ignored) { }
            try { d.execSQL("ALTER TABLE entries ADD COLUMN icon_key TEXT"); } catch (Exception ignored) { }
            try { d.execSQL("ALTER TABLE entries ADD COLUMN custom TEXT"); } catch (Exception ignored) { }
            Cursor c = d.rawQuery("SELECT COUNT(*) FROM cats", null);
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            if (n == 0) seed(d);
        } catch (Exception ignored) { }
    }

    /**
     * 账号分类（v2.4 精简）：按「账号归属的服务类型」划分，覆盖绝大多数日常账号。
     * 证件/银行卡一类改由独立的「卡包」模块承载，不再混在账号分类里。
     * 顺序按使用频率排：社交 → 游戏 → 邮箱 → 购物 → 云盘 → 工作 → 金融 → 网站 → 应用 → 其他
     */
    private static final String[][] DEFAULT_CATS = {
            {"社交", "chat", "FF22D3EE"}, {"游戏", "game", "FF7C5CFF"}, {"邮箱", "mail", "FFFF6B9D"},
            {"购物", "shop", "FFF59E0B"}, {"云盘", "cloud", "FF34D399"}, {"工作", "work", "FFFBBF24"},
            {"金融", "bank", "FFF87171"}, {"网站", "web", "FF00B8A0"}, {"应用", "app", "FF5EDCC4"},
            {"其他", "more", "FF94A3B8"}
    };

    /**
     * 播种默认分类。
     * 密码箱与链接库各一套（kind 0 / 1）—— 两套独立，各自增删改不影响对方。
     * 链接库用同一批默认名字（游戏/社交/云盘…），因为链接也基本落在这些领域，
     * 但它们是各自独立的一行，改一个不会影响另一个。
     */
    private void seed(SQLiteDatabase d) {
        for (int k = 0; k < 2; k++) seedKind(d, k);
    }

    /** 给某个库播种一套默认分类 */
    private void seedKind(SQLiteDatabase d, int kind) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < DEFAULT_CATS.length; i++) {
            ContentValues v = new ContentValues();
            v.put("uuid", UUID.randomUUID().toString());
            v.put("name", DEFAULT_CATS[i][0]);
            v.put("icon", DEFAULT_CATS[i][1]);
            v.put("color", hexColor(DEFAULT_CATS[i][2]));
            v.put("sort", i);
            v.put("del", 0);
            v.put("kind", kind);
            v.put("mtime", now);
            d.insert("cats", null, v);
        }
    }

    private static long hexColor(String s) {
        try {
            String t = s == null ? "" : s.trim();
            if (t.toLowerCase().startsWith("0x")) t = t.substring(2);
            return Long.parseLong(t, 16) & 0xFFFFFFFFL;
        } catch (Exception e) { return 0xFF7C5CFFL; }
    }

    public static String uuid() { return UUID.randomUUID().toString(); }

    /* ---------------- 分类 ---------------- */

    public static final class Cat {
        public long id;
        public String uuid, name, icon;
        public long color, sort, mtime;
        public boolean del;
        /** 0 = 密码箱的分类，1 = 链接库的分类。两套独立，互不干扰。 */
        public int kind;
    }

    private Cat catRow(Cursor c) {
        Cat x = new Cat();
        x.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        x.uuid = str(c, "uuid");
        x.name = str(c, "name");
        x.icon = str(c, "icon");
        x.color = c.getLong(c.getColumnIndexOrThrow("color"));
        x.sort = c.getLong(c.getColumnIndexOrThrow("sort"));
        x.mtime = c.getLong(c.getColumnIndexOrThrow("mtime"));
        x.del = c.getLong(c.getColumnIndexOrThrow("del")) == 1;
        int ki = c.getColumnIndex("kind");
        x.kind = ki >= 0 ? (c.getLong(ki) == 1 ? 1 : 0) : 0;
        return x;
    }

    /** 按库取分类：kind 0 = 密码箱，1 = 链接库。两套完全独立。 */
    /**
     * 把挂错库的分类纠正回来。
     *
     * 早期版本新建链接时默认分类取的是密码箱那套（defaultCatUuid() 写死 0），
     * 于是这些链接的 cat_uuid 指向密码箱的分类。链接库用自己的分类一统计，
     * 这些链接在"全部"里能看到，点任何分类都找不到 —— 等于分类失效。
     *
     * 这里把它们改挂到本库的默认分类下，一次性修好存量数据。
     */
    public void fixCrossKindCats() {
        dropCache();
        try {
            SQLiteDatabase d = getWritableDatabase();
            for (int kind = 0; kind < 2; kind++) {
                java.util.Set<String> mine = new java.util.HashSet<>();
                for (Cat c : cats(kind)) mine.add(c.uuid);
                if (mine.isEmpty()) continue;
                String other = defaultCatUuid(kind);
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < mine.size(); i++) { if (i > 0) in.append(','); in.append('?'); }
                /* 找出 kind 对不上、或 cat_uuid 不属于本库分类的条目 */
                Cursor c = d.rawQuery("SELECT uuid, cat_uuid FROM entries WHERE kind="
                        + kind + " AND (cat_uuid IS NULL OR cat_uuid='' OR cat_uuid NOT IN ("
                        + in + "))", mine.toArray(new String[0]));
                java.util.List<String> bad = new java.util.ArrayList<>();
                while (c.moveToNext()) bad.add(c.getString(0));
                c.close();
                if (bad.isEmpty()) continue;
                ContentValues v = new ContentValues();
                v.put("cat_uuid", other);
                for (String uu : bad) d.update("entries", v, "uuid=?", new String[]{uu});
            }
        } catch (Exception ignored) { }
    }

    /** 给链接库补一套默认分类（迁移时用，不动已有的密码箱分类） */
    private void seedLinks(SQLiteDatabase d) { seedKind(d, 1); }

    public List<Cat> cats(int kind) {
        List<Cat> l = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query("cats", null, "del=0 AND kind=?",
                    new String[]{kind + ""}, null, null, "sort ASC, _id ASC");
            while (c.moveToNext()) {
                Cat x = catRow(c);
                if (!x.del) l.add(x);
            }
            c.close();
        } catch (Exception ignored) { }
        sortCats(l);
        return l;
    }

    /**
     * 分类排序：按名称 A→Z，「其他」永远排最后。
     *
     * 放在这里统一排，导航和「自定义分类」弹窗用的是同一个 cats()，
     * 两边顺序必然一致 —— 分开排迟早会对不上。
     *
     * 用 Collator 而不是 String.compareTo：分类名多是中文，
     * 后者按 Unicode 码点排，中文会挤在一起且顺序没意义；
     * Collator 按拼音排，才符合"按首字母"的预期。
     */
    private void sortCats(List<Cat> l) {
        if (l == null || l.size() < 2) return;
        final java.text.Collator col =
                java.text.Collator.getInstance(java.util.Locale.CHINA);
        /*
         * 注意：这里**绝对不能**调 defaultCatUuid() ——
         * 它内部会调 cats()，cats() 又调 sortCats()，形成无限递归。
         * 上一版就是这么写的，启动直接 StackOverflowError 闪退。
         * 列表已经取出来了，直接在里面找「其他」即可，不需要再查库。
         */
        final String otherUuid = otherUuidIn(l);
        l.sort((x, y) -> {
            boolean ox = isOther(x, otherUuid);
            boolean oy = isOther(y, otherUuid);
            if (ox != oy) return ox ? 1 : -1;   // 「其他」沉底
            int r = col.compare(x.name == null ? "" : x.name, y.name == null ? "" : y.name);
            if (r != 0) return r;
            /* 同名时退回顺序号，保证稳定（否则每次重画可能跳来跳去） */
            return Long.compare(x.sort, y.sort);
        });
    }

    /** 在已取出的列表里找「其他」的 uuid；找不到返回 null（随后按名字判定） */
    private static String otherUuidIn(List<Cat> l) {
        if (l == null) return null;
        for (Cat c : l) if (c != null && c.name != null && c.name.trim().equals("其他"))
            return c.uuid;
        return null;
    }

    /** 「其他」的判定：既认默认 uuid，也认名字（用户可能改名或导入过） */
    private static boolean isOther(Cat c, String otherUuid) {
        if (c == null) return false;
        if (otherUuid != null && otherUuid.equals(c.uuid)) return true;
        return c.name != null && c.name.trim().equals("其他");
    }

    public List<Cat> cats() { return cats(0); }

    /** 含墓碑，仅同步用 */
    public List<Cat> catsRaw() {
        List<Cat> l = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query("cats", null, null, null, null, null, "_id ASC");
            while (c.moveToNext()) l.add(catRow(c));
            c.close();
        } catch (Exception ignored) { }
        return l;
    }

    /** @param kind 0 = 建到密码箱，1 = 建到链接库 */
    public String addCat(String name, String icon, long color, int kind) {
        String u = uuid();
        ContentValues v = new ContentValues();
        v.put("uuid", u);
        v.put("name", name);
        v.put("icon", icon == null ? "custom" : icon);
        v.put("color", color);
        v.put("sort", System.currentTimeMillis() / 1000);
        v.put("del", 0);
        v.put("kind", kind);
        v.put("mtime", System.currentTimeMillis());
        getWritableDatabase().insert("cats", null, v);
        return u;
    }

    public String addCat(String name, String icon, long color) {
        return addCat(name, icon, color, 0);
    }

    public void updateCat(String u, String name, String icon, long color) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("icon", icon);
        v.put("color", color);
        v.put("mtime", System.currentTimeMillis());
        getWritableDatabase().update("cats", v, "uuid=?", new String[]{u});
    }

    public void delCat(String u) {
        dropCache();
        ContentValues v = new ContentValues();
        v.put("del", 1);
        v.put("mtime", System.currentTimeMillis());
        getWritableDatabase().update("cats", v, "uuid=?", new String[]{u});
        ContentValues e = new ContentValues();
        e.put("del", 1);
        e.put("mtime", System.currentTimeMillis());
        getWritableDatabase().update("entries", e, "cat_uuid=? AND del=0", new String[]{u});
    }
    /** 在指定库里按名字找分类（导入时用），找不到返回 null */
    public String catUuidByName(String name, int kind) {
        for (Cat c : cats(kind)) if (name.equals(c.name)) return c.uuid;
        return null;
    }

    public String catUuidByName(String name) { return catUuidByName(name, 0); }

    /** @param kind 取哪个库的默认分类 */
    public String defaultCatUuid(int kind) {
        List<Cat> cs = cats(kind);
        if (cs.isEmpty()) return addCat("其他", "more", 0xFF94A3B8L, kind);
        for (Cat c : cs) if ("其他".equals(c.name)) return c.uuid;
        return cs.get(cs.size() - 1).uuid;
    }

    public String defaultCatUuid() { return defaultCatUuid(0); }

    /* ---------------- 条目 ---------------- */

    public static final class Entry {
        public long id, fav, ts, mtime, uses;
        public String uuid, catUuid, title, user, pass, url, notes, pkg, totp, tags, iconKey, custom;
        /** 0 = 账号密码，1 = 网址收藏。共用一个表，以便复用分类、标签、图标、搜索 */
        public int kind;
        public transient String catName;   // 仅导入时临时用，不入库
        public boolean del;
    }

    public Entry newEntry() { return newEntry(0); }

    public Entry newEntry(int kind) {
        Entry e = new Entry();
        e.kind = kind;
        e.uuid = uuid();
        e.catUuid = defaultCatUuid(kind);
        e.title = ""; e.user = ""; e.pass = ""; e.url = ""; e.notes = ""; e.totp = ""; e.pkg = ""; e.tags = "";
        e.iconKey = ""; e.custom = "";
        e.mtime = System.currentTimeMillis();
        return e;
    }

    private String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String s = c.getString(i);
        return s == null ? "" : s;
    }

    private static String nn(String s) { return s == null ? "" : s; }

    private Entry row(Cursor c) {
        Entry e = new Entry();
        e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        e.uuid = str(c, "uuid");
        e.catUuid = str(c, "cat_uuid");
        /* 解密失败（换了密钥、数据损坏）时 dec 可能返回 null，
           后面 e.title.isEmpty() 这类调用就会炸。统一兜底成空串。 */
        e.title = nn(CryptoHolder.dec(str(c, "title")));
        e.user = nn(CryptoHolder.dec(str(c, "user")));
        e.pass = nn(CryptoHolder.dec(str(c, "pass")));
        e.url = nn(CryptoHolder.dec(str(c, "url")));
        e.notes = nn(CryptoHolder.dec(str(c, "notes")));
        e.totp = nn(CryptoHolder.dec(str(c, "totp")));
        e.pkg = nn(str(c, "pkg"));
        e.tags = str(c, "tags");
        if (e.tags == null) e.tags = "";
        e.iconKey = str(c, "icon_key");
        if (e.iconKey == null) e.iconKey = "";
        e.custom = str(c, "custom");
        if (e.custom == null) e.custom = "";
        int ki = c.getColumnIndex("kind");
        e.kind = ki >= 0 ? c.getLong(ki) == 1 ? 1 : 0 : 0;
        e.fav = c.getLong(c.getColumnIndexOrThrow("fav"));
        e.del = c.getLong(c.getColumnIndexOrThrow("del")) == 1;
        e.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        e.mtime = c.getLong(c.getColumnIndexOrThrow("mtime"));
        e.uses = c.getLong(c.getColumnIndexOrThrow("uses"));
        return e;
    }

    /**
     * @param kind 0 = 账号密码，1 = 网址收藏，传 -1 表示不限（用于搜索全部 / 回收站）
     */
    public List<Entry> list(String catUuid, boolean favOnly, boolean trash, String q, int kind) {
        List<Entry> all = raw();
        List<Entry> out = new ArrayList<>();
        String query = q == null ? "" : q.trim().toLowerCase();
        /*
         * 筛选某个分类时，把「归属不明」的条目（分类为空、或指向另一个库的分类）
         * 一并归入本库的默认分类（通常是「其他」）。
         *
         * 这样即使个别条目的 cat_uuid 是历史遗留的错值，
         * 在导航里看到的计数和点进分类看到的条目也是对得上的 ——
         * 之前的表现是"全部里有、点分类却空"，看着像数据丢了。
         */
        boolean wantFallback = catUuid != null && !catUuid.isEmpty()
                && catUuid.equals(defaultCatUuid(kind));
        java.util.Set<String> mine = null;
        if (wantFallback) {
            mine = new java.util.HashSet<>();
            for (Cat c : cats(kind)) mine.add(c.uuid);
        }
        for (Entry e : all) {
            if (e.del != trash) continue;
            if (kind >= 0 && e.kind != kind) continue;
            if (catUuid != null && !catUuid.isEmpty() && !catUuid.equals(e.catUuid)) {
                boolean orphan = mine != null
                        && (e.catUuid == null || e.catUuid.isEmpty() || !mine.contains(e.catUuid));
                if (!orphan) continue;
            }
            if (favOnly && e.fav != 1) continue;
            if (!query.isEmpty()) {
                String hay = (e.title + " " + e.user + " " + e.url + " " + e.notes + " " + e.pkg
                        + " " + (e.tags == null ? "" : e.tags)
                        + " " + customValues(e.custom)).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            out.add(e);
        }
        out.sort((a, b) -> {
            if (a.fav != b.fav) return Long.compare(b.fav, a.fav);
            return Long.compare(b.mtime, a.mtime);
        });
        return out;
    }

    /*
     * 全表缓存。
     *
     * raw() 要把每一行都解密（含 custom 的 JSON 解析），而它被调用得非常频繁：
     * 切一次分类 → 导航重画 + 列表刷新 + 卡片刷新，每次都是一整轮解密。
     * 数据只在下面这几个写操作里变，所以加一层缓存、写入即失效即可。
     *
     * 注意返回的是快照：调用方只读、不改动里面的 Entry（要改请用 getByUuid 后 save）。
     */
    private volatile List<Entry> cache;
    private volatile java.util.List<Card> cardCache;

    /**
     * 清缓存。写操作后必须调，否则会读到旧数据。
     * 卡片表和账号表共用一个开关 —— 两个缓存同时失效，
     * 代价远小于漏清一个导致的数据不一致。
     */
    private void dropCache() { cache = null; cardCache = null; }

    public List<Entry> raw() {
        List<Entry> c0 = cache;
        if (c0 != null) return c0;
        List<Entry> l = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query("entries", null, null, null, null, null, "mtime DESC");
            while (c.moveToNext()) l.add(row(c));
            c.close();
        } catch (Exception ignored) { }
        cache = l;
        return l;
    }

    public Entry get(long id) {
        try {
            Cursor c = getReadableDatabase().query("entries", null, "_id=?", new String[]{id + ""}, null, null, null);
            Entry e = null;
            if (c.moveToFirst()) e = row(c);
            c.close();
            return e;
        } catch (Exception e2) { return null; }
    }

    public Entry getByUuid(String u) {
        if (u == null) return null;
        try {
            Cursor c = getReadableDatabase().query("entries", null, "uuid=?", new String[]{u}, null, null, null);
            Entry e = null;
            if (c.moveToFirst()) e = row(c);
            c.close();
            return e;
        } catch (Exception e2) { return null; }
    }

    public long save(Entry e) {
        dropCache();
        if (e.uuid == null || e.uuid.isEmpty()) e.uuid = uuid();
        /* 默认分类必须取本库的：之前固定用 defaultCatUuid()（密码箱），
           导致新建的链接被挂到密码箱的分类下 —— 链接库用自己的分类一统计，
           "全部"里有这条链接，但点任何分类都找不到它。 */
        if (e.catUuid == null || e.catUuid.isEmpty()) e.catUuid = defaultCatUuid(e.kind);
        e.mtime = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("uuid", e.uuid);
        v.put("cat_uuid", e.catUuid);
        v.put("title", CryptoHolder.enc(e.title));
        v.put("user", CryptoHolder.enc(e.user));
        v.put("pass", CryptoHolder.enc(e.pass));
        v.put("url", CryptoHolder.enc(e.url));
        v.put("notes", CryptoHolder.enc(e.notes));
        v.put("pkg", e.pkg);
        v.put("tags", e.tags == null ? "" : e.tags);
        v.put("icon_key", e.iconKey == null ? "" : e.iconKey);
        v.put("custom", e.custom == null ? "" : e.custom);
        v.put("totp", CryptoHolder.enc(e.totp));
        v.put("kind", e.kind == 1 ? 1 : 0);
        v.put("fav", e.fav);
        v.put("del", e.del ? 1 : 0);
        v.put("mtime", e.mtime);
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.query("entries", new String[]{"_id"}, "uuid=?", new String[]{e.uuid}, null, null, null);
        boolean exists = c.moveToFirst();
        long id = exists ? c.getLong(0) : -1;
        c.close();
        if (exists) {
            d.update("entries", v, "uuid=?", new String[]{e.uuid});
            return id;
        }
        v.put("ts", System.currentTimeMillis());
        v.put("uses", 0);
        return d.insert("entries", null, v);
    }

    /** 只更新图标缓存键，不改 mtime（避免影响同步的 mtime 比较） */
    public void setIconKey(long id, String key) {
        try {
            ContentValues v = new ContentValues();
            v.put("icon_key", key == null ? "" : key);
            getWritableDatabase().update("entries", v, "_id=?", new String[]{id + ""});
        } catch (Exception ignored) { }
    }

    public void touchUse(long id) {
        dropCache();
        try {
            getWritableDatabase().execSQL("UPDATE entries SET uses=uses+1 WHERE _id=?", new Object[]{id});
        } catch (Exception ignored) { }
    }

    public void softDelete(long id) {
        dropCache();
        try {
            getWritableDatabase().execSQL("UPDATE entries SET del=1, mtime=? WHERE _id=?",
                    new Object[]{System.currentTimeMillis(), id});
        } catch (Exception ignored) { }
    }

    public void restore(long id) {
        dropCache();
        try {
            getWritableDatabase().execSQL("UPDATE entries SET del=0, mtime=? WHERE _id=?",
                    new Object[]{System.currentTimeMillis(), id});
        } catch (Exception ignored) { }
    }

    public void hardDelete(long id) {
        dropCache();
        try { getWritableDatabase().delete("entries", "_id=?", new String[]{id + ""}); } catch (Exception ignored) { }
    }

    public void emptyTrash() {
        dropCache();
        try { getWritableDatabase().delete("entries", "del=1", null); } catch (Exception ignored) { }
    }

    /**
     * 按分类统计条数（一次 SQL GROUP BY）。
     * 之前 renderNav() 对每个分类各调一次 list()，等于分类数 × 全表扫描，
     * 条目多了以后每次重绘导航栏都要读几十遍表，明显卡顿。
     */
    /**
     * 各分类条数（一次 GROUP BY，替代"每个分类各扫一遍全表"）。
     *
     * @param kind 0 账号 / 1 链接 —— 必须过滤，否则链接的条目会被算进密码箱的分类计数。
     */

    /**
     * 一次查询取出左侧导航需要的**全部**计数。
     *
     * 之前 renderNav() 是分开查的：分类计数一次、总一次、收藏一次、
     * 回收站一次 —— 链接库那边还是把全表拉到内存再数（最重的一处）。
     * 每次切分类、增删条目都要重画导航，等于反复全表扫描。
     *
     * 这里用一条 GROUP BY 同时算出分类计数与总数，收藏/回收站各一条 COUNT，
     * 共 3 条 SQL 且都不拉行数据（只返回数字）。
     */
    public static final class NavCounts {
        public final java.util.Map<String, Integer> byCat = new java.util.HashMap<>();
        public int total, fav, trash;
    }

    /**
     * @param fallback 归属不明的条目（分类为空、或指向另一个库的分类）
     *                 计入这个 uuid —— 通常是本库的「其他」。
     *                 没有这层兜底，这类条目就会"全部里有、分类里找不到"。
     */
    private static String placeholders(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) b.append(','); b.append('?'); }
        return b.toString();
    }

    public NavCounts navCounts(int kind, String fallback) {
        NavCounts r = new NavCounts();
        SQLiteDatabase d = null;
        try {
            d = getReadableDatabase();
            java.util.List<String> mine = new java.util.ArrayList<>();
            for (Cat c : cats(kind)) mine.add(c.uuid);
            String in = placeholders(mine.size());
            String expr;
            String[] args;
            if (!mine.isEmpty()) {
                /* cat_uuid 不在本库分类里 → 归到 fallback */
                expr = "CASE WHEN cat_uuid IN (" + in + ") THEN cat_uuid ELSE ? END";
                args = new String[mine.size() + 2];
                for (int i = 0; i < mine.size(); i++) args[i] = mine.get(i);
                args[mine.size()] = fallback;
                args[mine.size() + 1] = kind + "";
            } else {
                expr = "?";
                args = new String[]{fallback, kind + ""};
            }
            Cursor c = d.rawQuery(
                    "SELECT " + expr + ", COUNT(*) FROM entries WHERE del=0 AND kind=? GROUP BY 1",
                    args);
            while (c.moveToNext()) {
                String k = c.getString(0);
                r.byCat.put(k == null ? "" : k, c.getInt(1));
                r.total += c.getInt(1);
            }
            c.close();
            c = d.rawQuery(
                    "SELECT COUNT(*) FROM entries WHERE del=0 AND fav=1 AND kind=?",
                    new String[]{kind + ""});
            if (c.moveToFirst()) r.fav = c.getInt(0);
            c.close();
            c = d.rawQuery(
                    "SELECT COUNT(*) FROM entries WHERE del=1 AND kind=?",
                    new String[]{kind + ""});
            if (c.moveToFirst()) r.trash = c.getInt(0);
            c.close();
        } catch (Exception ignored) { }
        return r;
    }

    /** 收藏条数（SQL COUNT，避免拉全表）。按 kind 隔离两个库。 */

    /**
     * 用外部传入的数据库文件替换本机库（换机直传用）。
     *
     * 必须先 close 再覆盖：SQLiteOpenHelper 持有打开的连接，
     * 不关掉就直接替换文件，新连接读到的可能是旧缓存或半截文件。
     * 换完还要清掉单例，否则后续 Db.get() 拿到的还是指向旧文件的实例。
     */
    public static synchronized boolean replace(Context c, File src) {
        try {
            if (inst != null) { try { inst.close(); } catch (Exception ignored) { } inst = null; }
            File dst = c.getApplicationContext().getDatabasePath("vault.db");
            if (dst.exists()) {
                /* 先改名而不是直接删：万一拷贝中途失败，还有得救 */
                File bak = new File(dst.getParentFile(), "vault.db.bak");
                if (bak.exists()) bak.delete();
                dst.renameTo(bak);
            }
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();

            /* 清掉 WAL / SHM，避免残留的旧日志把新库搞坏 */
            new File(dst.getAbsolutePath() + "-wal").delete();
            new File(dst.getAbsolutePath() + "-shm").delete();
            new File(dst.getParentFile(), "vault.db.bak").delete();
            return true;
        } catch (Exception e) {
            /* 失败就把备份放回去 */
            try {
                File dst = c.getApplicationContext().getDatabasePath("vault.db");
                File bak = new File(dst.getParentFile(), "vault.db.bak");
                if (bak.exists() && !dst.exists()) bak.renameTo(dst);
            } catch (Exception ignored) { }
            return false;
        }
    }

    /** 总数。按 kind 隔离，否则密码箱「全部」会把链接也数进去。 */
    public int count(boolean trash, int kind) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM entries WHERE del=?"
                            + (kind >= 0 ? " AND kind=?" : ""),
                    kind >= 0 ? new String[]{trash ? "1" : "0", kind + ""}
                              : new String[]{trash ? "1" : "0"});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Exception e) { return 0; }
    }

    /* ---------------- 标签 ---------------- */

    public static final String SEP = "\u0001";
    /** 预置标签：用于区分同一应用/网站的大小号 */
    public static final String[] PRESET_TAGS = {"主号", "小号", "常用", "备用", "工作", "家人", "已弃用"};

    public static java.util.List<String> tagList(String raw) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return l;
        for (String t : raw.split(SEP)) {
            String x = t.trim();
            if (!x.isEmpty()) l.add(x);
        }
        return l;
    }

    public static String packTags(java.util.List<String> ts) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (ts != null) for (String t : ts) {
            if (t != null && !t.trim().isEmpty()) set.add(t.trim());
        }
        StringBuilder sb = new StringBuilder();
        for (String t : set) { if (sb.length() > 0) sb.append(SEP); sb.append(t); }
        return sb.toString();
    }

    /** 全库出现过的标签（按使用次数降序） */
    public java.util.List<String> allTags() {
        java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<>();
        for (Entry e : raw()) {
            for (String t : tagList(e.tags)) {
                Integer c = cnt.get(t);
                cnt.put(t, c == null ? 1 : c + 1);
            }
        }
        java.util.List<java.util.Map.Entry<String, Integer>> l =
                new java.util.ArrayList<>(cnt.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
            @Override public int compare(java.util.Map.Entry<String, Integer> a,
                                         java.util.Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> m : l) out.add(m.getKey());
        return out;
    }
    /* ---------------- 自定义字段 ---------------- */

    /** 自定义字段：k\u0002v 为一组，组间用 \u0001 分隔（值会被加密存储） */
    public static java.util.List<F> customs(String raw) {
        java.util.List<F> l = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return l;
        for (String part : raw.split("\u0001")) {
            String[] kv = part.split("\u0002", -1);
            String k = kv.length > 0 ? kv[0] : "";
            String v = kv.length > 1 ? CryptoHolder.dec(kv[1]) : "";
            if (v == null) v = "";
            if (k.isEmpty() && v.isEmpty()) continue;
            l.add(new F(k, v));
        }
        return l;
    }

    public static String packCustoms(java.util.List<F> fs) {
        StringBuilder sb = new StringBuilder();
        if (fs == null) return "";
        for (F f : fs) {
            if (f == null) continue;
            if ((f.k == null || f.k.isEmpty()) && (f.v == null || f.v.isEmpty())) continue;
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(f.k == null ? "" : f.k).append('\u0002');
            String enc = CryptoHolder.enc(f.v == null ? "" : f.v);
            sb.append(enc == null ? "" : enc);
        }
        return sb.toString();
    }

    /** 自定义字段的纯文本（供搜索用） */
    public static String customValues(String raw) {
        StringBuilder sb = new StringBuilder();
        for (F f : customs(raw)) {
            sb.append(' ').append(f.k).append(' ').append(f.v);
        }
        return sb.toString();
    }

    /* ---------------- 批量操作 ---------------- */

    /** 批量移动分类。返回影响条数 */
    public int moveTo(java.util.Collection<Long> ids, String catUuid) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        SQLiteDatabase d = getWritableDatabase();
        try {
            d.beginTransaction();
            for (Long id : ids) {
                ContentValues v = new ContentValues();
                v.put("cat_uuid", catUuid);
                v.put("mtime", System.currentTimeMillis());
                n += d.update("entries", v, "_id=?", new String[]{id + ""});
            }
            d.setTransactionSuccessful();
        } catch (Exception ignored) { } finally { d.endTransaction(); }
        return n;
    }

    /** 批量追加标签（保留原有） */
    public int addTags(java.util.Collection<Long> ids, java.util.List<String> tags) {
        if (ids == null || ids.isEmpty() || tags == null || tags.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            Entry e = get(id);
            if (e == null) continue;
            java.util.List<String> cur = tagList(e.tags);
            cur.addAll(tags);
            e.tags = packTags(cur);
            save(e);
            n++;
        }
        return n;
    }

    /** 批量替换标签 */
    public int setTags(java.util.Collection<Long> ids, java.util.List<String> tags) {
        dropCache();
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            Entry e = get(id);
            if (e == null) continue;
            e.tags = packTags(tags);
            save(e);
            n++;
        }
        return n;
    }

    /**
     * 全库重命名标签。
     * 用的是「逐条取出 → 替换 → 存回」，因为标签是打包在一个字符串字段里的，
     * 没法用一条 SQL 改。条目多的库会慢一点，但改名不常做，可接受。
     *
     * @return 受影响的条目数
     */
    public int renameTag(String from, String to) {
        if (from == null || from.isEmpty() || to == null || to.trim().isEmpty()) return 0;
        String dst = to.trim();
        if (from.equals(dst)) return 0;
        dropCache();
        int n = 0;
        for (Entry e : raw()) {
            java.util.List<String> ts = tagList(e.tags);
            boolean hit = false;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String t : ts) {
                if (from.equals(t)) { out.add(dst); hit = true; }
                else out.add(t);
            }
            if (hit) {
                e.tags = packTags(out);
                save(e);
                n++;
            }
        }
        return n;
    }

    /** 全库删除某个标签（只摘标签，不动条目本身） */
    public int removeTag(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        dropCache();
        int n = 0;
        for (Entry e : raw()) {
            java.util.List<String> ts = tagList(e.tags);
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String t : ts) if (!tag.equals(t)) out.add(t);
            if (out.size() != ts.size()) {
                e.tags = packTags(out);
                save(e);
                n++;
            }
        }
        return n;
    }

    /** 某个标签用了多少次。只在单次查询时用；批量场景请用 tagCounts() */
    public int tagCount(String tag) {
        if (tag == null) return 0;
        Integer n = tagCounts().get(tag);
        return n == null ? 0 : n;
    }

    /**
     * 一次性算出全部标签的使用次数。
     *
     * 列表里逐行调 tagCount() 是 O(行数 × 标签数) 次全表遍历 ——
     * 滚一屏能跑出上千次。这里一次遍历搞定，之后查 Map 即可。
     */
    public java.util.Map<String, Integer> tagCounts() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (Entry e : raw()) {
            for (String t : tagList(e.tags)) {
                Integer c = m.get(t);
                m.put(t, c == null ? 1 : c + 1);
            }
        }
        return m;
    }

    /** 批量设置收藏 */
    public int setFav(java.util.Collection<Long> ids, boolean fav) {
        dropCache();
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        SQLiteDatabase d = getWritableDatabase();
        try {
            d.beginTransaction();
            for (Long id : ids) {
                ContentValues v = new ContentValues();
                v.put("fav", fav ? 1 : 0);
                v.put("mtime", System.currentTimeMillis());
                n += d.update("entries", v, "_id=?", new String[]{id + ""});
            }
            d.setTransactionSuccessful();
        } catch (Exception ignored) { } finally { d.endTransaction(); }
        return n;
    }

    /** 批量删除（软删） */
    /** 批量恢复（回收站 → 正常）。返回实际恢复条数。 */
    public int restore(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) { restore(id); n++; }
        return n;
    }

    /** 批量彻底删除（不可恢复）。返回实际删除条数。 */
    public int hardDelete(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) { hardDelete(id); n++; }
        return n;
    }

    public int delete(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) { softDelete(id); n++; }
        return n;
    }

    /* ---------------- 卡包 ---------------- */

    public static final class F {
        public String k, v;
        public F() { }
        public F(String k, String v) { this.k = k; this.v = v; }
    }

    public static final class Card {
        public long id, fav, ts, mtime;
        public String uuid, kind, title, fields, imgs, note;
        public boolean del;
    }

    public static final String[] CARD_KINDS = {"身份证", "银行卡", "社保卡", "驾驶证", "护照", "会员卡", "自定义"};
    public static final String[] CARD_ICONS = {"person", "bank", "shield", "car", "passport", "star", "more"};

    /** 各卡种的默认字段模板 */
    public static String[] tpl(String kind) {
        switch (kind == null ? "" : kind) {
            case "身份证": return new String[]{"姓名", "身份证号", "签发机关", "有效期"};
            case "银行卡": return new String[]{"持卡人", "卡号", "开户行", "有效期", "CVV", "预留手机号"};
            case "社保卡": return new String[]{"姓名", "社保编号", "参保地", "发卡日期"};
            case "驾驶证": return new String[]{"姓名", "证号", "准驾车型", "有效期"};
            case "护照": return new String[]{"姓名", "护照号", "签发国", "有效期"};
            case "会员卡": return new String[]{"会员名", "卡号", "商家", "有效期"};
            default: return new String[]{"名称", "编号", "备注"};
        }
    }

    public Card newCard(String kind) {
        Card c = new Card();
        c.uuid = uuid();
        c.kind = kind == null || kind.isEmpty() ? "自定义" : kind;
        c.title = "";
        c.note = "";
        c.imgs = "";
        StringBuilder sb = new StringBuilder();
        for (String f : tpl(c.kind)) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(f).append('\u0002');
        }
        c.fields = sb.toString();
        c.mtime = System.currentTimeMillis();
        return c;
    }

    public static java.util.List<F> fields(String raw) {
        java.util.List<F> l = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return l;
        for (String part : raw.split("\u0001")) {
            String[] kv = part.split("\u0002", -1);
            String k = kv.length > 0 ? kv[0] : "";
            String v = kv.length > 1 ? CryptoHolder.dec(kv[1]) : "";
            if (v == null) v = "";
            l.add(new F(k, v));
        }
        return l;
    }

    public static String packFields(java.util.List<F> fs) {
        StringBuilder sb = new StringBuilder();
        for (F f : fs) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(f.k == null ? "" : f.k).append('\u0002');
            String enc = CryptoHolder.enc(f.v == null ? "" : f.v);
            sb.append(enc == null ? "" : enc);
        }
        return sb.toString();
    }

    public static java.util.List<String> imgs(String raw) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return l;
        for (String n : raw.split(",")) if (!n.trim().isEmpty()) l.add(n.trim());
        return l;
    }

    private Card cardRow(Cursor c) {
        Card x = new Card();
        x.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        x.uuid = str(c, "uuid");
        x.kind = str(c, "kind");
        x.title = CryptoHolder.dec(str(c, "title"));
        if (x.title == null) x.title = "";
        x.fields = str(c, "fields");
        x.imgs = str(c, "imgs");
        x.note = CryptoHolder.dec(str(c, "note"));
        if (x.note == null) x.note = "";
        x.fav = c.getLong(c.getColumnIndexOrThrow("fav"));
        x.del = c.getLong(c.getColumnIndexOrThrow("del")) == 1;
        x.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        x.mtime = c.getLong(c.getColumnIndexOrThrow("mtime"));
        return x;
    }

    public java.util.List<Card> cards(String kind, boolean trash, String q) {
        java.util.List<Card> l = new java.util.ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query("cards", null, null, null, null, null, "mtime DESC");
            while (c.moveToNext()) {
                Card x = cardRow(c);
                if (x.del != trash) continue;
                if (kind != null && !kind.isEmpty() && !kind.equals(x.kind)) continue;
                if (q != null && !q.trim().isEmpty()) {
                    String hay = (x.title + " " + x.note + " " + x.kind).toLowerCase();
                    boolean hit = hay.contains(q.trim().toLowerCase());
                    if (!hit) {
                        for (F f : fields(x.fields)) {
                            if (f.v.toLowerCase().contains(q.trim().toLowerCase())) { hit = true; break; }
                        }
                    }
                    if (!hit) continue;
                }
                l.add(x);
            }
            c.close();
        } catch (Exception ignored) { }
        return l;
    }

    /**
     * 全部卡片。
     *
     * 加了缓存是因为它会被反复调用：附件管理页每一行都要算「这张图属于哪张卡片」，
     * 不缓存的话滚一屏就是几十次全表查询 + 解密。
     */
    public java.util.List<Card> cardsRaw() {
        java.util.List<Card> c0 = cardCache;
        if (c0 != null) return c0;
        java.util.List<Card> l = new java.util.ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query("cards", null, null, null, null, null, "_id ASC");
            while (c.moveToNext()) l.add(cardRow(c));
            c.close();
        } catch (Exception ignored) { }
        cardCache = l;
        return l;
    }

    public Card getCard(long id) {
        try {
            Cursor c = getReadableDatabase().query("cards", null, "_id=?", new String[]{id + ""}, null, null, null);
            Card x = null;
            if (c.moveToFirst()) x = cardRow(c);
            c.close();
            return x;
        } catch (Exception e) { return null; }
    }

    public long saveCard(Card c) {
        dropCache();
        if (c.uuid == null || c.uuid.isEmpty()) c.uuid = uuid();
        c.mtime = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("uuid", c.uuid);
        v.put("kind", c.kind);
        v.put("title", CryptoHolder.enc(c.title));
        v.put("fields", c.fields);
        v.put("imgs", c.imgs == null ? "" : c.imgs);
        v.put("note", CryptoHolder.enc(c.note));
        v.put("fav", c.fav);
        v.put("del", c.del ? 1 : 0);
        v.put("mtime", c.mtime);
        SQLiteDatabase d = getWritableDatabase();
        Cursor q = d.query("cards", new String[]{"_id"}, "uuid=?", new String[]{c.uuid}, null, null, null);
        boolean ex = q.moveToFirst();
        long id = ex ? q.getLong(0) : -1;
        q.close();
        if (ex) { d.update("cards", v, "uuid=?", new String[]{c.uuid}); return id; }
        v.put("ts", System.currentTimeMillis());
        return d.insert("cards", null, v);
    }

    public void softDeleteCard(long id) {
        try {
            getWritableDatabase().execSQL("UPDATE cards SET del=1, mtime=? WHERE _id=?",
                    new Object[]{System.currentTimeMillis(), id});
        } catch (Exception ignored) { }
    }

    public void restoreCard(long id) {
        try {
            getWritableDatabase().execSQL("UPDATE cards SET del=0, mtime=? WHERE _id=?",
                    new Object[]{System.currentTimeMillis(), id});
        } catch (Exception ignored) { }
    }

    public void hardDeleteCard(long id) {
        dropCache();
        try { getWritableDatabase().delete("cards", "_id=?", new String[]{id + ""}); } catch (Exception ignored) { }
    }

    /** 按卡种统计（一次 GROUP BY），避免导航栏对每个卡种各扫一遍表 */
    public java.util.Map<String, Integer> countsByKind(boolean trash) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT kind, COUNT(*) FROM cards WHERE del=? GROUP BY kind",
                    new String[]{trash ? "1" : "0"});
            while (c.moveToNext()) {
                String k = c.getString(0);
                m.put(k == null ? "" : k, c.getInt(1));
            }
        } catch (Exception ignored) { }
        if (c != null) try { c.close(); } catch (Exception ignored) { }
        return m;
    }

    public int countCards(boolean trash) {
        try {
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cards WHERE del=?",
                    new String[]{trash ? "1" : "0"});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Exception e) { return 0; }
    }

    /* ---------------- 同步：导出 / 导入 ---------------- */

    /** 导出为 JSON 字符串（明文字段均为本地密文，同步文件不含明文） */
    public String exportJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 3);
            root.put("device", android.os.Build.MODEL == null ? "android" : android.os.Build.MODEL);
            JSONArray cs = new JSONArray();
            for (Cat c : catsRaw()) {
                JSONObject o = new JSONObject();
                o.put("u", c.uuid);
                o.put("n", c.name);
                o.put("i", c.icon);
                o.put("c", c.color);
                o.put("s", c.sort);
                o.put("d", c.del ? 1 : 0);
                o.put("m", c.mtime);
                cs.put(o);
            }
            root.put("cats", cs);
            JSONArray es = new JSONArray();
            for (Entry e : raw()) {
                JSONObject o = new JSONObject();
                o.put("u", e.uuid);
                o.put("cat", e.catUuid);
                o.put("t", encRaw(e.title));
                o.put("us", encRaw(e.user));
                o.put("p", encRaw(e.pass));
                o.put("ur", encRaw(e.url));
                o.put("no", encRaw(e.notes));
                o.put("pk", e.pkg);
                o.put("tg", e.tags == null ? "" : e.tags);
                o.put("to", encRaw(e.totp));
                o.put("f", e.fav);
                o.put("d", e.del ? 1 : 0);
                o.put("m", e.mtime);
                es.put(o);
            }
            root.put("entries", es);
            JSONArray ks = new JSONArray();
            for (Card c : cardsRaw()) {
                JSONObject o = new JSONObject();
                o.put("u", c.uuid);
                o.put("k", c.kind);
                o.put("t", encRaw(c.title));
                o.put("f", c.fields);
                o.put("g", c.imgs);
                o.put("no", encRaw(c.note));
                o.put("fv", c.fav);
                o.put("d", c.del ? 1 : 0);
                o.put("m", c.mtime);
                ks.put(o);
            }
            root.put("cards", ks);
            return root.toString();
        } catch (Exception e) { return null; }
    }

    private String encRaw(String s) {
        String v = CryptoHolder.enc(s);
        return v == null ? "" : v;
    }

    /** 合并远端数据：按 uuid 逐条比较 mtime，新的胜出。返回 [新增/更新, 删除] 数量 */
    public int[] mergeJson(String json) {
        int changed = 0, removed = 0;
        if (json == null || json.isEmpty()) return new int[]{0, 0};
        try {
            JSONObject root = new JSONObject(json);
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                JSONArray cs = root.optJSONArray("cats");
                if (cs != null) {
                    for (int i = 0; i < cs.length(); i++) {
                        JSONObject o = cs.getJSONObject(i);
                        String u = o.optString("u", "");
                        if (u.isEmpty()) continue;
                        long m = o.optLong("m", 0);
                        Cursor c = d.query("cats", new String[]{"mtime"}, "uuid=?", new String[]{u}, null, null, null);
                        long local = -1;
                        if (c.moveToFirst()) local = c.getLong(0);
                        c.close();
                        if (local >= m) continue;
                        ContentValues v = new ContentValues();
                        v.put("uuid", u);
                        v.put("name", o.optString("n", "未命名"));
                        v.put("icon", o.optString("i", "custom"));
                        v.put("color", o.optLong("c", 0xFF7C5CFFL));
                        v.put("sort", o.optLong("s", 0));
                        v.put("del", o.optInt("d", 0));
                        v.put("mtime", m);
                        if (local < 0) d.insert("cats", null, v); else d.update("cats", v, "uuid=?", new String[]{u});
                        changed++;
                    }
                }
                JSONArray es = root.optJSONArray("entries");
                if (es != null) {
                    for (int i = 0; i < es.length(); i++) {
                        JSONObject o = es.getJSONObject(i);
                        String u = o.optString("u", "");
                        if (u.isEmpty()) continue;
                        long m = o.optLong("m", 0);
                        Cursor c = d.query("entries", new String[]{"mtime", "del"}, "uuid=?", new String[]{u}, null, null, null);
                        long local = -1;
                        int localDel = 0;
                        if (c.moveToFirst()) { local = c.getLong(0); localDel = c.getInt(1); }
                        c.close();
                        if (local >= m) continue;
                        int remoteDel = o.optInt("d", 0);
                        ContentValues v = new ContentValues();
                        v.put("uuid", u);
                        v.put("cat_uuid", o.optString("cat", defaultCatUuid()));
                        v.put("title", o.optString("t", ""));
                        v.put("user", o.optString("us", ""));
                        v.put("pass", o.optString("p", ""));
                        v.put("url", o.optString("ur", ""));
                        v.put("notes", o.optString("no", ""));
                        v.put("pkg", o.optString("pk", ""));
                        v.put("tags", o.optString("tg", ""));
                        v.put("totp", o.optString("to", ""));
                        v.put("fav", o.optInt("f", 0));
                        v.put("del", remoteDel);
                        v.put("mtime", m);
                        if (local < 0) {
                            v.put("ts", m);
                            v.put("uses", 0);
                            d.insert("entries", null, v);
                            changed++;
                        } else {
                            d.update("entries", v, "uuid=?", new String[]{u});
                            if (remoteDel == 1 && localDel == 0) removed++; else changed++;
                        }
                    }
                }
                JSONArray ks = root.optJSONArray("cards");
                if (ks != null) {
                    for (int i = 0; i < ks.length(); i++) {
                        JSONObject o = ks.getJSONObject(i);
                        String u = o.optString("u", "");
                        if (u.isEmpty()) continue;
                        long m = o.optLong("m", 0);
                        Cursor c = d.query("cards", new String[]{"mtime", "del"}, "uuid=?", new String[]{u}, null, null, null);
                        long local = -1;
                        int localDel = 0;
                        if (c.moveToFirst()) { local = c.getLong(0); localDel = c.getInt(1); }
                        c.close();
                        if (local >= m) continue;
                        int remoteDel = o.optInt("d", 0);
                        ContentValues v = new ContentValues();
                        v.put("uuid", u);
                        v.put("kind", o.optString("k", "自定义"));
                        v.put("title", o.optString("t", ""));
                        v.put("fields", o.optString("f", ""));
                        v.put("imgs", o.optString("g", ""));
                        v.put("note", o.optString("no", ""));
                        v.put("fav", o.optInt("fv", 0));
                        v.put("del", remoteDel);
                        v.put("mtime", m);
                        if (local < 0) {
                            v.put("ts", m);
                            d.insert("cards", null, v);
                            changed++;
                        } else {
                            d.update("cards", v, "uuid=?", new String[]{u});
                            if (remoteDel == 1 && localDel == 0) removed++; else changed++;
                        }
                    }
                }
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        } catch (Exception ignored) { }
        return new int[]{changed, removed};
    }

    /** 整体覆盖（用于首次从云端恢复） */
    public boolean replaceAll(String json) {
        dropCache();
        if (json == null) return false;
        try {
            SQLiteDatabase d = getWritableDatabase();
            d.delete("entries", null, null);
            d.delete("cats", null, null);
            d.delete("cards", null, null);
            repair();
        } catch (Exception ignored) { }
        int[] r = mergeJson(json);
        return r[0] > 0 || r[1] > 0 || true;
    }

    public void meta(String key, String val) {
        try {
            ContentValues v = new ContentValues();
            v.put("k", key);
            v.put("v", val);
            getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) { }
    }

    public String meta(String key) {
        try {
            Cursor c = getReadableDatabase().query("meta", new String[]{"v"}, "k=?", new String[]{key}, null, null, null);
            String v = null;
            if (c.moveToFirst()) v = c.getString(0);
            c.close();
            return v;
        } catch (Exception e) { return null; }
    }
}
