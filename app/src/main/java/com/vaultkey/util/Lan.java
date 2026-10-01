package com.vaultkey.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 换机局域网直传。
 *
 * 场景：换新手机时要把整个密码库搬过去。走坚果云要配服务器地址、申请应用密码、
 * 填账号，很多人卡在这儿就放弃了；导出 CSV 再导入又麻烦且容易出错。
 *
 * 这里做局域网直传：两台手机连同一 WiFi，一台「发送」一台「接收」，配对码确认后
 * 整个数据库直接过去，一分钟搞定，不需要任何服务器。
 *
 * 为什么可以直传数据库文件：库里的标题 / 账号 / 密码 / 备注**本来就是 AES 密文**，
 * 传输的始终是密文，同一 WiFi 上的其他人就算抓到包也解不开（没有主密钥）。
 * 配对码只用来防止连错设备，不承担加密职责。
 *
 * 未引任何第三方库：UDP 广播做发现，TCP 做传输，纯 Java 实现。
 */
public final class Lan {

    public static final int PORT = 45987;
    public static final int BEACON_PORT = 45988;
    private static final String MAGIC = "VAULTKEY1";
    private static final int SO_TIMEOUT = 12000;

    /* ---------------- 发送端 ---------------- */

    public static final class Server {
        private ServerSocket ss;
        private final AtomicBoolean run = new AtomicBoolean(true);
        private Thread acceptThread, beaconThread;
        public final int port;
        public final String code;

        Server(int port, String code) { this.port = port; this.code = code; }

        /**
         * @param file  要传的文件（数据库）
         * @param extra 附带的元数据（密钥派生参数等，非机密）
         */
        public void start(final Context c, final File file, final byte[] extra,
                          final Cb cb) {
            acceptThread = new Thread(() -> {
                try {
                    ss = new ServerSocket(port);
                    ss.setReuseAddress(true);
                } catch (Exception e) {
                    cb.err("起不了服务，换个 WiFi 再试");
                    return;
                }
                while (run.get()) {
                    try {
                        final Socket s = ss.accept();
                        s.setSoTimeout(SO_TIMEOUT);
                        handle(s, file, extra, cb);
                    } catch (Exception e) {
                        if (run.get()) { /* 正常关闭或单次失败 */ }
                    }
                }
            });
            acceptThread.start();

            /* UDP 广播：让接收方能自动发现这台设备 */
            beaconThread = new Thread(() -> {
                try {
                    DatagramSocket ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.setBroadcast(true);
                    ds.bind(new InetSocketAddress(BEACON_PORT));
                    byte[] buf = new byte[1024];
                    while (run.get()) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try { ds.receive(p); } catch (Exception e) { continue; }
                        String q = new String(p.getData(), 0, p.getLength()).trim();
                        if (!q.equals("VAULTKEY?DISCOVER")) continue;
                        String rep = "VAULTKEY!HERE:" + port + ":" + code.length();
                        byte[] out = rep.getBytes("UTF-8");
                        DatagramPacket rp = new DatagramPacket(out, out.length,
                                p.getAddress(), p.getPort());
                        try { ds.send(rp); } catch (Exception ignored) { }
                    }
                    ds.close();
                } catch (Exception ignored) { }
            });
            beaconThread.start();
        }

        /** 单个连接：校验配对码 → 传文件 */
        private void handle(Socket s, File file, byte[] extra, Cb cb) {
            try {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());

                /* 客户端先发 MAGIC + 配对码 */
                String hello = in.readUTF();
                if (!hello.startsWith(MAGIC)) {
                    out.writeUTF("NO");
                    out.flush();
                    s.close();
                    return;
                }
                String got = hello.substring(MAGIC.length());
                if (!got.equals(code)) {
                    out.writeUTF("NO");
                    out.flush();
                    s.close();
                    cb.err("配对码不对，已拒绝");
                    return;
                }
                out.writeUTF("OK");
                out.flush();

                cb.state("对方已连接，正在传输…");

                long total = file.length();
                out.writeLong(total);
                out.writeInt(extra == null ? 0 : extra.length);
                if (extra != null && extra.length > 0) out.write(extra);

                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                int n;
                while ((n = fis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    cb.progress((int) (sent * 100 / Math.max(1, total)));
                }
                fis.close();
                out.flush();
                cb.done("传输完成");
                s.close();
            } catch (Exception e) {
                cb.err("传输中断：" + e.getMessage());
            }
        }

        public void stop() {
            run.set(false);
            try { if (ss != null) ss.close(); } catch (Exception ignored) { }
            if (acceptThread != null) acceptThread.interrupt();
            if (beaconThread != null) beaconThread.interrupt();
        }
    }

    public static Server serve(Context c, File file, byte[] extra, String code, Cb cb) {
        Server s = new Server(PORT, code);
        s.start(c, file, extra, cb);
        return s;
    }

    /* ---------------- 发现 ---------------- */

    public interface Found {
        void on(String host, int port);
        void end(int count);
    }

    /**
     * 在同一 WiFi 下广播找发送方。
     * 先广播到 255.255.255.255，若没响应再试子网广播地址（部分路由器会拦前者）。
     */
    public static void discover(final Context c, final Found f) {
        new Thread(() -> {
            int count = 0;
            try {
                DatagramSocket ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(BEACON_PORT + 1));
                ds.setSoTimeout(1200);

                java.util.List<String> targets = new java.util.ArrayList<>();
                targets.add("255.255.255.255");
                String sub = subnetBroadcast(c);
                if (sub != null && !sub.equals("255.255.255.255")) targets.add(sub);

                for (String t : targets) {
                    byte[] q = "VAULTKEY?DISCOVER".getBytes("UTF-8");
                    DatagramPacket p = new DatagramPacket(q, q.length,
                            InetAddress.getByName(t), BEACON_PORT);
                    try { ds.send(p); } catch (Exception ignored) { }

                    long end = System.currentTimeMillis() + 1800;
                    while (System.currentTimeMillis() < end) {
                        byte[] buf = new byte[512];
                        DatagramPacket rp = new DatagramPacket(buf, buf.length);
                        try { ds.receive(rp); } catch (Exception e) { break; }
                        String s = new String(rp.getData(), 0, rp.getLength()).trim();
                        if (!s.startsWith("VAULTKEY!HERE")) continue;
                        String[] a = s.split(":");
                        if (a.length < 2) continue;
                        String host = rp.getAddress().getHostAddress();
                        int port = Integer.parseInt(a[1]);
                        final String h = host;
                        count++;
                        cbMain(() -> f.on(h, port));
                    }
                    if (count > 0) break;
                }
                ds.close();
            } catch (Exception ignored) { }
            final int n = count;
            cbMain(() -> f.end(n));
        }).start();
    }

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static void cbMain(Runnable r) { MAIN.post(r); }

    /** 子网广播地址（如 192.168.1.255） */
    private static String subnetBroadcast(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getDhcpInfo().ipAddress;
            if (ip == 0) return null;
            int mask = wm.getDhcpInfo().netmask;
            if (mask == 0) mask = 0x00FFFFFF;
            int bcast = (ip & mask) | (~mask);
            return (bcast & 0xFF) + "." + ((bcast >> 8) & 0xFF)
                    + "." + ((bcast >> 16) & 0xFF) + "." + ((bcast >> 24) & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    /** 本机 IP，展示给用户看 */
    public static String myIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getDhcpInfo().ipAddress;
            if (ip == 0) return null;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF)
                    + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 接收端 ---------------- */

    public interface Cb {
        void state(String s);
        void progress(int pct);
        void done(String msg);
        void err(String msg);
    }

    /**
     * 从发送方拉文件。
     * @param saveTo 保存位置（先存临时文件，导入成功再替换）
     */
    public static void fetch(final String host, final int port, final String code,
                             final File saveTo, final Cb cb) {
        new Thread(() -> {
            try {
                cbMain(() -> cb.state("正在连接…"));
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 6000);
                s.setSoTimeout(30000);

                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());

                out.writeUTF(MAGIC + code);
                out.flush();

                String r = in.readUTF();
                if (!"OK".equals(r)) {
                    cbMain(() -> cb.err("配对码不对"));
                    s.close();
                    return;
                }

                long total = in.readLong();
                int extraLen = in.readInt();
                final byte[] extra = new byte[extraLen];
                if (extraLen > 0) in.readFully(extra);

                cbMain(() -> cb.state("正在接收…"));
                FileOutputStream fos = new FileOutputStream(saveTo);
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                while (got < total) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, total - got));
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    got += n;
                    final int pct = (int) (got * 100 / Math.max(1, total));
                    cbMain(() -> cb.progress(pct));
                }
                fos.close();
                s.close();

                if (got < total) {
                    cbMain(() -> cb.err("接收中断，请重试"));
                    return;
                }
                String meta;
                try { meta = new String(extra, "UTF-8"); } catch (Exception e) { meta = ""; }
                final String metaF = meta;
                cbMain(() -> cb.done(metaF));
            } catch (Exception e) {
                final String m = e.getMessage();
                cbMain(() -> cb.err("连接失败：" + (m == null ? "请确认两台手机在同一 WiFi" : m)));
            }
        }).start();
    }

    /** 生成 6 位配对码 */
    public static String newCode() {
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }
}
