package com.vaultkey.webdav;

import android.util.Base64;
import android.util.Xml;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;

/** 极简 WebDAV 客户端（适配坚果云），零第三方依赖 */
public final class WebDavClient {

    /**
     * 最后一次失败的原因。
     *
     * 之前所有方法都是 `catch (Exception e) { return false; }` ——
     * 异常被完全吞掉，上层只能显示"上传失败，检查网络或权限"这种笼统提示，
     * 用户根本不知道是密码错了、地址错了、还是真没网。
     * 现在把状态码和异常都记下来，交给上层翻译成人话。
     */
    public int lastCode = 0;          // HTTP 状态码，0 = 没连上
    public String lastError = null;   // 可读的错误原因
    /** 服务器返回的原始内容片段 —— 403/400 时通常带着真正的原因 */
    public String lastBody = null;
    /** 最后一次请求的完整 URL，诊断时给用户核对 */
    public String lastUrl = null;

    private void fail(Exception e, int code) {
        lastCode = code;
        lastError = describe(e, code);
    }

    /**
     * 取响应体里的可读片段。
     * WebDAV 出错时（403/409）通常返回 XML，里面有 <s:message> 说明真正原因，
     * 比我们靠状态码猜准确得多。
     */
    private static String snippet(byte[] b) {
        if (b == null || b.length == 0) return null;
        String t;
        try { t = new String(b, "UTF-8"); } catch (Exception e) { return null; }
        String m = tag(t, "message");
        if (m != null) return m;
        String e2 = tag(t, "exception");
        if (e2 != null) return e2;
        t = t.replaceAll("<[^>]+>", " ").replaceAll("\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : (t.isEmpty() ? null : t);
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name);
        if (a < 0) return null;
        int gt = xml.indexOf('>', a);
        if (gt < 0) return null;
        int b = xml.indexOf("</" + name, gt);
        if (b < 0) return null;
        String v = xml.substring(gt + 1, b).trim();
        v = v.replaceAll("<[^>]+>", " ").replaceAll("\s+", " ").trim();
        return v.isEmpty() ? null : v;
    }

    private static String describe(Exception e, int code) {
        if (e instanceof java.net.UnknownHostException)
            return "找不到服务器（" + e.getMessage() + "）—— 检查是否联网，或服务器地址是否填错";
        if (e instanceof java.net.SocketTimeoutException)
            return "连接超时 —— 网络不通或服务器响应太慢";
        if (e instanceof java.net.ConnectException)
            return "连不上服务器 —— 检查网络，或服务器地址/端口是否正确";
        if (e instanceof javax.net.ssl.SSLException
                || e instanceof javax.net.ssl.SSLHandshakeException)
            return "HTTPS 握手失败 —— 服务器地址必须用 https:// 开头";
        if (e instanceof java.io.FileNotFoundException)
            return "服务器上找不到这个文件（404）";
        if (e instanceof java.net.MalformedURLException)
            return "服务器地址格式不对 —— 应该像 https://dav.jianguoyun.com/dav/";
        if (e instanceof android.os.NetworkOnMainThreadException)
            return "内部错误：在主线程做了网络操作";
        String cls = e == null ? "" : e.getClass().getSimpleName();
        String msg = e == null ? "" : (e.getMessage() == null ? "" : e.getMessage());
        if (code == 401)
            return "认证失败（HTTP 401）—— 账号或应用密码不对，"
                    + "注意要用坚果云生成的「应用密码」，不是登录密码";
        /*
         * 403 和 401 完全不同，之前合并成一句提示是错的，会把人往"改密码"上带：
         *   401 = 服务器不认识你（账号密码错）
         *   403 = 服务器认出你了，但拒绝这次操作（路径/权限/客户端被拒）
         * 密码错的话第一步就该 401，不会前三步都过、只在写入时 403。
         */
        if (code == 403)
            return "服务器拒绝了这个操作（HTTP 403）—— 账号密码是对的，"
                    + "问题在路径或权限";
        if (code == 404)
            return "路径不存在（HTTP 404）—— 服务器地址或云端目录不对";
        if (code == 405) return "该目录已存在或不允许此操作（HTTP 405）";
        if (code == 409)
            return "父目录不存在（HTTP 409）—— 先确认云端目录的上级路径可用";
        if (code == 423) return "资源被锁定（HTTP 423）—— 稍后再试";
        if (code >= 500) return "服务器出错（HTTP " + code + "）—— 稍后再试";
        if (code > 0) return "服务器返回 HTTP " + code;
        return (cls + " " + msg).trim().isEmpty() ? "未知错误" : (cls + "：" + msg).trim();
    }

    public static final String JIANGUOYUN = "https://dav.jianguoyun.com/dav/";
    public static final class Item {
        public String name, path;
        public long size;
        public boolean dir;
    }

    private final String user, pass, base;

    public WebDavClient(String server, String user, String pass) {
        this.user = user; this.pass = pass;
        this.base = server.endsWith("/") ? server : server + "/";
    }

    /**
     * 发一次请求。用自建的 MiniHttp 而不是 HttpURLConnection ——
     * 后者不支持 MKCOL / PROPFIND，会直接抛 ProtocolException。
     */
    private MiniHttp.Resp req(String url, String method, byte[] body, String[] extra)
            throws Exception {
        String[] h = {"Authorization", "Basic " + auth()};
        if (extra != null && extra.length > 0) {
            String[] all = new String[h.length + extra.length];
            System.arraycopy(h, 0, all, 0, h.length);
            System.arraycopy(extra, 0, all, h.length, extra.length);
            h = all;
        }
        lastUrl = url;
        MiniHttp.Resp r = MiniHttp.go(url, method, body, h, 12000, 30000);
        lastBody = snippet(r.body);
        return r;
    }

    private String auth() {
        return Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
    }

    /**
     * 拼接 URL。
     *
     * 关键：只有**目录**才以斜杠结尾，**文件绝对不能带尾部斜杠**。
     *
     * 之前这个方法给每一段都补了 '/'，文件名后面也补 ——
     * 于是 `VaultKey/vaultkey-sync.txt` 变成 `.../vaultkey-sync.txt/`，
     * 在 WebDAV 里这是"集合（目录）"地址。往集合上 PUT 会被拒：
     *   HTTP 403 "The collection resource of this location can not be uploaded"
     * 建目录（MKCOL）反而正常，所以现象是"前两步都过、只卡在写入"。
     */
    private String url(String p) { return build(p, false); }

    /** 目录地址：必须以斜杠结尾，否则服务器会当文件处理 */
    private String urlDir(String p) { return build(p, true); }

    private String build(String p, boolean trailing) {
        String path = p == null ? "" : p;
        while (path.startsWith("/")) path = path.substring(1);
        StringBuilder sb = new StringBuilder(base);
        String[] seg = path.split("/");
        for (String s : seg) {
            if (s.isEmpty()) continue;
            sb.append(enc(s)).append('/');
        }
        if (!trailing && sb.length() > 0 && sb.charAt(sb.length() - 1) == '/'
                && !(p == null || p.isEmpty())) {
            sb.deleteCharAt(sb.length() - 1);   // 文件：去掉尾部斜杠
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    public boolean mkdir(String p) {
        try {
            MiniHttp.Resp r = req(urlDir(p), "MKCOL", null, null);
            /* 201 新建成功；405 已存在（Method Not Allowed），也算达到目的 */
            if (r.code == 201 || r.code == 200 || r.code == 405) { lastError = null; return true; }
            fail(null, r.code);
            return false;
        } catch (Exception e) { fail(e, 0); return false; }
    }

    public boolean put(String p, byte[] data) {
        try {
            MiniHttp.Resp r = req(url(p), "PUT", data,
                    new String[]{"Content-Type", "application/octet-stream"});
            if (r.code == 200 || r.code == 201 || r.code == 204 || r.code == 207) {
                lastError = null; return true;
            }
            fail(null, r.code);
            return false;
        } catch (Exception e) { fail(e, 0); return false; }
    }

    public byte[] get(String p) {
        try {
            MiniHttp.Resp r = req(url(p), "GET", null, null);
            if (r.code != 200) {
                /* 404 是正常情况（首次同步还没有文件），不算错误 */
                if (r.code == 404) { lastError = null; return null; }
                fail(null, r.code);
                return null;
            }
            lastError = null;
            return r.body;
        } catch (Exception e) { fail(e, 0); return null; }
    }

    public boolean delete(String p) {
        try {
            MiniHttp.Resp r = req(url(p), "DELETE", null, null);
            if (r.code == 200 || r.code == 204 || r.code == 404) { lastError = null; return true; }
            fail(null, r.code);
            return false;
        } catch (Exception e) { fail(e, 0); return false; }
    }

    /**
     * 列目录。返回 null 表示请求本身失败了（错误信息在 lastError）。
     *
     * 之前失败会返回空列表且吞掉异常 —— 上层看不出"连不上"和"目录是空的"
     * 的区别，诊断就失去意义了。
     */
    public List<Item> list(String p) {
        List<Item> out = new ArrayList<>();
        try {
            byte[] xml = ("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:getlastmodified/><d:getcontentlength/><d:resourcetype/></d:prop>"
                    + "</d:propfind>").getBytes(StandardCharsets.UTF_8);
            MiniHttp.Resp r = req(urlDir(p), "PROPFIND", xml,
                    new String[]{"Depth", "1", "Content-Type", "application/xml"});
            if (r.code != 207) {
                /* 404 = 目录不存在，对诊断来说是个明确结论，不算"失败" */
                if (r.code == 404) { lastError = null; return out; }
                fail(null, r.code);
                return null;
            }
            parse(new java.io.ByteArrayInputStream(r.body), out);
            lastError = null;
            return out;
        } catch (Exception e) { fail(e, 0); return null; }
    }

    private void parse(InputStream in, List<Item> out) throws Exception {
        XmlPullParser x = Xml.newPullParser();
        x.setInput(in, "UTF-8");
        Item cur = null;
        int ev = x.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            String n = x.getName();
            if (ev == XmlPullParser.START_TAG) {
                if (n != null && n.endsWith("response")) cur = new Item();
                else if (cur != null && n != null && n.endsWith("href")) {
                    cur.path = x.nextText();
                    cur.name = cur.path;
                    if (cur.name.endsWith("/")) cur.name = cur.name.substring(0, cur.name.length() - 1);
                    int i = cur.name.lastIndexOf('/');
                    if (i >= 0) cur.name = cur.name.substring(i + 1);
                    try { cur.name = java.net.URLDecoder.decode(cur.name, "UTF-8"); } catch (Exception ignored) { }
                } else if (cur != null && n != null && n.endsWith("getcontentlength")) {
                    try { cur.size = Long.parseLong(x.nextText().trim()); } catch (Exception ignored) { }
                } else if (cur != null && n != null && n.endsWith("collection")) cur.dir = true;
            } else if (ev == XmlPullParser.END_TAG && cur != null && n != null && n.endsWith("response")) {
                if (!cur.dir && cur.name != null && !cur.name.isEmpty()) out.add(cur);
                cur = null;
            }
            ev = x.next();
            if (ev == XmlPullParser.START_TAG && cur == null && x.getName() != null && x.getName().endsWith("response")) { }
        }
    }

}
