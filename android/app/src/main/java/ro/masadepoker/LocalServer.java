package ro.masadepoker;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Același server mic ca în MasaDePoker.cs, doar pe 127.0.0.1 (nu se vede din rețea):
 *   /          jocul cu boți
 *   /jarvis    Jarvis
 *   /ws        WebSocket care leagă jocul de Jarvis
 *   /data/...  datele lui Jarvis, ca fișiere JSON în memoria aplicației
 *   /frame     ultima imagine capturată de pe ecran (pentru Jarvis Vision)
 */
public final class LocalServer {
    private static LocalServer instance;
    private static final Pattern DATA_NAME = Pattern.compile("^[a-z0-9-]{1,40}$");

    private final Context app;
    private volatile ServerSocket server;
    private volatile int port;
    private final List<WsClient> clients = new ArrayList<>();

    // ultima captură de ecran (JPEG) și numărul ei
    private volatile byte[] frame;
    private volatile long frameSeq;

    private static final class WsClient {
        Socket socket;
        OutputStream out;
        String role;
        final Object lock = new Object();
    }

    private LocalServer(Context c) { app = c.getApplicationContext(); }

    public static synchronized LocalServer get(Context c) {
        if (instance == null) {
            LocalServer s = new LocalServer(c);
            s.start();
            instance = s;
        }
        return instance;
    }

    public String base() { return "http://127.0.0.1:" + port; }

    public void setFrame(byte[] jpeg) { frame = jpeg; frameSeq++; }
    public void clearFrame() { frame = null; }

    // Portul se deschide pe un fir separat: Android nu permite operații de rețea pe firul principal.
    private void start() {
        final IOException[] last = new IOException[1];
        Thread bind = new Thread(() -> {
            for (int p = 47123; p < 47143; p++) {
                try {
                    server = new ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"));
                    port = p;
                    return;
                } catch (IOException e) { last[0] = e; }
            }
        }, "masa-bind");
        bind.start();
        try { bind.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (server == null) throw new RuntimeException("Serverul local nu a putut porni", last[0]);
        Thread t = new Thread(this::acceptLoop, "masa-server");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (true) {
            final Socket c;
            try { c = server.accept(); } catch (IOException e) { return; }
            Thread t = new Thread(() -> handle(c), "masa-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------- HTTP ----------

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int a = 0, b = 0, c = 0, d;
        while (buf.size() < 16384) {
            d = in.read();
            if (d < 0) return null;
            buf.write(d);
            if (a == 13 && b == 10 && c == 13 && d == 10) {
                byte[] all = buf.toByteArray();
                return new String(all, 0, all.length - 4, StandardCharsets.ISO_8859_1);
            }
            a = b; b = c; c = d;
        }
        return null;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r <= 0) throw new IOException("închis");
            got += r;
        }
        return b;
    }

    private static boolean localOrigin(String origin) {
        return origin == null || origin.startsWith("http://127.0.0.1:") || origin.startsWith("http://localhost:");
    }

    private void handle(Socket c) {
        try {
            c.setTcpNoDelay(true);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            String head = readHead(in);
            if (head == null) { c.close(); return; }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            String method = req[0];
            String path = req.length > 1 ? req[1] : "/";
            String query = "";
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(Locale.ROOT), lines[i].substring(k + 1).trim());
            }
            int qi = path.indexOf('?');
            if (qi >= 0) { query = path.substring(qi + 1); path = path.substring(0, qi); }
            String origin = h.get("origin");

            if (path.equals("/ws") && h.containsKey("sec-websocket-key")) {
                if (!localOrigin(origin)) { respond(out, 403, "text/plain; charset=utf-8", utf8("Interzis"), null); c.close(); return; }
                session(c, in, out, h.get("sec-websocket-key"), query.contains("role=jarvis") ? "jarvis" : "game");
                return;
            }
            if (method.equals("GET") && path.equals("/ping")) respond(out, 200, "text/plain", utf8("masa-de-poker"), null);
            else if (method.equals("GET") && path.equals("/")) respond(out, 200, "text/html; charset=utf-8", asset("masa-de-poker.html"), null);
            else if (method.equals("GET") && path.equals("/jarvis")) respond(out, 200, "text/html; charset=utf-8", asset("jarvis.html"), null);
            else if (method.equals("GET") && path.equals("/frame")) serveFrame(out, origin);
            else if (path.startsWith("/data/")) data(in, out, method, path.substring(6), h, origin);
            else respond(out, 404, "text/plain; charset=utf-8", utf8("Nu există"), null);
            c.close();
        } catch (Exception e) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private void serveFrame(OutputStream out, String origin) throws IOException {
        byte[] f = frame;
        if (!localOrigin(origin)) { respond(out, 403, "text/plain", utf8("Interzis"), null); return; }
        if (f == null) { respond(out, 204, "text/plain", new byte[0], null); return; }
        respond(out, 200, "image/jpeg", f, "X-Seq: " + frameSeq + "\r\nAccess-Control-Expose-Headers: X-Seq\r\n");
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream s = app.getAssets().open(name)) { return readAll(s); }
    }

    private static byte[] readAll(InputStream s) throws IOException {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = s.read(b)) > 0) m.write(b, 0, r);
        return m.toByteArray();
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    // Datele lui Jarvis. Scrierea cere antetul X-Masa și o origine locală, ca alte pagini să nu poată scrie aici.
    private void data(InputStream in, OutputStream out, String method, String name, Map<String, String> h, String origin) throws IOException {
        if (!DATA_NAME.matcher(name).matches()) { respond(out, 400, "text/plain", utf8("nume greșit"), null); return; }
        File dir = new File(app.getFilesDir(), "date");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, name + ".json");
        if (method.equals("GET")) {
            if (!localOrigin(origin)) { respond(out, 403, "text/plain", utf8("Interzis"), null); return; }
            respond(out, 200, "application/json; charset=utf-8", file.exists() ? Files.readAllBytes(file.toPath()) : utf8("{}"), null);
            return;
        }
        if (method.equals("POST")) {
            String len = h.get("content-length");
            if (!localOrigin(origin) || !h.containsKey("x-masa") || len == null) { respond(out, 403, "text/plain", utf8("Interzis"), null); return; }
            int n = Integer.parseInt(len.trim());
            if (n < 0 || n > 20 * 1024 * 1024) { respond(out, 413, "text/plain", utf8("prea mare"), null); return; }
            byte[] body = readN(in, n);
            synchronized (this) {
                File tmp = new File(dir, name + ".json.tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(body); fo.getFD().sync(); }
                if (file.exists()) {
                    File bak = new File(dir, name + ".json.bak");
                    //noinspection ResultOfMethodCallIgnored
                    bak.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(bak);
                }
                if (!tmp.renameTo(file)) throw new IOException("nu pot salva");
            }
            respond(out, 200, "application/json", utf8("{\"ok\":true}"), null);
            return;
        }
        respond(out, 405, "text/plain", utf8("metodă nepermisă"), null);
    }

    private static void respond(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length +
                "\r\nCache-Control: no-cache\r\nConnection: close\r\n" + (extra != null ? extra : "") + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // ---------- WebSocket: tot ce trimite jocul ajunge la Jarvis și invers ----------

    private void session(Socket c, InputStream in, OutputStream out, String key, String role) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        String accept = android.util.Base64.encodeToString(
                sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)), android.util.Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        WsClient me = new WsClient();
        me.socket = c; me.out = out; me.role = role;
        synchronized (clients) { clients.add(me); }
        peers();
        try {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                byte[] hd = readN(in, 2);
                boolean fin = (hd[0] & 0x80) != 0;
                int op = hd[0] & 0x0F;
                boolean masked = (hd[1] & 0x80) != 0;
                long len = hd[1] & 0x7F;
                if (len == 126) { byte[] e = readN(in, 2); len = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF); }
                else if (len == 127) { byte[] e = readN(in, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (e[i] & 0xFF); }
                if (len > 8 * 1024 * 1024) break;
                byte[] mask = masked ? readN(in, 4) : null;
                byte[] data = readN(in, (int) len);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if (op == 8) break;
                if (op == 9) { send(me, data, 0xA); continue; }
                if (op == 0xA) continue;
                msg.write(data, 0, data.length);
                if (fin) {
                    byte[] whole = msg.toByteArray();
                    msg.reset();
                    String target = me.role.equals("game") ? "jarvis" : "game";
                    for (WsClient w : snapshot()) if (w.role.equals(target)) send(w, whole, 1);
                }
            }
        } catch (Exception ignored) { }
        synchronized (clients) { clients.remove(me); }
        try { c.close(); } catch (IOException ignored) { }
        peers();
    }

    private List<WsClient> snapshot() { synchronized (clients) { return new ArrayList<>(clients); } }

    private void peers() {
        List<WsClient> all = snapshot();
        int g = 0, j = 0;
        for (WsClient w : all) { if (w.role.equals("game")) g++; else j++; }
        byte[] m = utf8("{\"t\":\"peers\",\"game\":" + g + ",\"jarvis\":" + j + "}");
        for (WsClient w : all) send(w, m, 1);
    }

    private static void send(WsClient w, byte[] payload, int op) {
        try {
            synchronized (w.lock) {
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x80 | op);
                if (payload.length < 126) f.write(payload.length);
                else if (payload.length < 65536) { f.write(126); f.write(payload.length >> 8); f.write(payload.length & 0xFF); }
                else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) (((long) payload.length >> (8 * i)) & 0xFF)); }
                f.write(payload, 0, payload.length);
                w.out.write(f.toByteArray());
                w.out.flush();
            }
        } catch (IOException ignored) { }
    }
}
