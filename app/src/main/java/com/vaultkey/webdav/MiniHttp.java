package com.vaultkey.webdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 客户端（直连 socket，零第三方依赖）。
 *
 * 为什么不用 HttpURLConnection：
 * 它只允许 OPTIONS / GET / HEAD / POST / PUT / DELETE / TRACE / PATCH 这 8 个方法，
 * 而 WebDAV 需要 MKCOL（建目录）和 PROPFIND（列目录）——
 * 传进去会抛 `ProtocolException: Expected one of [...] but was MKCOL`。
 * 这正是"创建目录失败"的真正原因，跟网络权限没关系。
 *
 * 自己实现的好处：任意方法都能用，且不依赖反射去改系统内部字段
 * （那种做法在高版本 Android 上会被隐藏 API 限制挡住）。
 */
public final class MiniHttp {

    public static final class Resp {
        public int code;
        public byte[] body;
        /** 响应头 Location（重定向用），没有则为 null */
        public String location;
        Resp(int c, byte[] b) { code = c; body = b; }
        Resp(int c, byte[] b, String loc) { code = c; body = b; location = loc; }
    }

    private static final int MAX_REDIRECT = 5;

    /**
     * @param urlStr    完整地址
     * @param method    任意 HTTP 方法（含 MKCOL / PROPFIND）
     * @param body      请求体，可为 null
     * @param headers   额外请求头，成对传入（key, value, key, value ...），可为 null
     * @param connectMs 连接超时
     * @param readMs    读取超时
     */
    public static Resp go(String urlStr, String method, byte[] body,
                          String[] headers, int connectMs, int readMs) throws IOException {
        String cur = urlStr;
        for (int hop = 0; hop < MAX_REDIRECT; hop++) {
            Resp r = once(cur, method, body, headers, connectMs, readMs);
            String loc = r.location;
            boolean redir = (r.code == 301 || r.code == 302 || r.code == 303
                    || r.code == 307 || r.code == 308) && loc != null && !loc.isEmpty();
            if (!redir) return r;
            cur = resolve(cur, loc);
            /* 303 之后按规范改用 GET */
            if (r.code == 303) { method = "GET"; body = null; }
        }
        throw new IOException("重定向次数过多");
    }

    /** 内部：发一次请求。返回 Resp，并把响应头暂存到 lastHeaders 里供重定向判断 */
    private static Resp once(String urlStr, String method, byte[] body,
                             String[] headers, int connectMs, int readMs) throws IOException {
        URL u = new URL(urlStr);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (u.getProtocol().equals("https") ? 443 : 80);
        boolean tls = u.getProtocol().equalsIgnoreCase("https");
        String path = u.getFile();           // 含 query
        if (path == null || path.isEmpty()) path = "/";

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectMs);
            s.setSoTimeout(readMs);
            Socket use = s;
            if (tls) {
                /* 用带 host/port 的重载：这样 SNI 与主机名校验才正确 */
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                use = f.createSocket(s, host, port, true);
                use.setSoTimeout(readMs);
            }
            try {
                OutputStream out = use.getOutputStream();
                StringBuilder h = new StringBuilder();
                h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                h.append("Host: ").append(host);
                if (u.getPort() > 0) h.append(':').append(u.getPort());
                h.append("\r\n");
                h.append("Connection: close\r\n");
                h.append("User-Agent: VaultKey/1.0\r\n");
                if (headers != null) {
                    for (int i = 0; i + 1 < headers.length; i += 2) {
                        if (headers[i] == null || headers[i + 1] == null) continue;
                        h.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
                    }
                }
                if (body != null && body.length > 0) {
                    h.append("Content-Length: ").append(body.length).append("\r\n");
                } else {
                    h.append("Content-Length: 0\r\n");
                }
                h.append("\r\n");
                out.write(h.toString().getBytes(StandardCharsets.UTF_8));
                if (body != null && body.length > 0) out.write(body);
                out.flush();

                return read(use.getInputStream());
            } finally {
                try { use.close(); } catch (IOException ignored) { }
            }
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** 解析状态行 + 响应头 + 响应体（支持 Content-Length 与 chunked） */
    private static Resp read(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) throw new IOException("服务器没有响应");
        /* "HTTP/1.1 401 Unauthorized" */
        int sp1 = line.indexOf(' ');
        int sp2 = sp1 > 0 ? line.indexOf(' ', sp1 + 1) : -1;
        int code;
        try {
            code = Integer.parseInt(sp2 > 0 ? line.substring(sp1 + 1, sp2) : line.substring(sp1 + 1));
        } catch (Exception e) {
            throw new IOException("无法解析响应：" + line);
        }

        int len = -1;
        boolean chunked = false;
        String location = null;
        while (true) {
            String hl = readLine(in);
            if (hl == null || hl.isEmpty()) break;
            String low = hl.toLowerCase(Locale.US);
            if (low.startsWith("content-length:")) {
                try { len = Integer.parseInt(hl.substring(hl.indexOf(':') + 1).trim()); }
                catch (Exception ignored) { }
            } else if (low.startsWith("transfer-encoding:") && low.contains("chunked")) {
                chunked = true;
            } else if (low.startsWith("location:")) {
                location = hl.substring(hl.indexOf(':') + 1).trim();
            }
        }

        byte[] data;
        if (chunked) data = readChunked(in);
        else if (len >= 0) data = readExact(in, len);
        else data = readAll(in);

        return new Resp(code, data, location);
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] b = new byte[Math.max(n, 0)];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return b;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (true) {
            String sz = readLine(in);
            if (sz == null) break;
            int semi = sz.indexOf(';');
            if (semi >= 0) sz = sz.substring(0, semi);
            sz = sz.trim();
            if (sz.isEmpty()) continue;
            int n;
            try { n = Integer.parseInt(sz, 16); } catch (Exception e) { break; }
            if (n == 0) {
                readLine(in);            // trailer
                break;
            }
            byte[] chunk = readExact(in, n);
            o.write(chunk, 0, chunk.length);
            readLine(in);                // 块末尾 CRLF
        }
        return o.toByteArray();
    }

    /**
     * 读一行（去掉行尾 CRLF）。
     * 注意别把整行连带清空 —— 只在末尾是 \r 时截掉最后一个字节。
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int c;
        boolean got = false;
        while ((c = in.read()) != -1) {
            got = true;
            if (c == '\n') break;
            o.write(c);
        }
        if (!got && o.size() == 0) return null;
        byte[] b = o.toByteArray();
        int n = b.length;
        if (n > 0 && b[n - 1] == '\r') n--;
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    /** 把可能相对的 Location 解析成绝对地址 */
    private static String resolve(String base, String loc) {
        try {
            return new URI(base).resolve(loc).toString();
        } catch (Exception e) {
            if (loc.startsWith("http")) return loc;
            return base.endsWith("/") ? base + loc.substring(Math.min(1, loc.length())) : base + loc;
        }
    }

    private MiniHttp() { }
}
