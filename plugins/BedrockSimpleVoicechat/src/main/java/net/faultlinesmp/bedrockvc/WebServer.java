package net.faultlinesmp.bedrockvc;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP(S) + WebSocket server, no libraries: serves the voice page from the jar and upgrades /ws to a WebSocket.
 * One thread per connection (a handful of voice clients, plus page loads). Anything else on the port gets a 404.
 */
final class WebServer {
    interface Handler {
        /** A WebSocket opened: return the socket's listener. */
        WsListener open(Ws ws, String ip);
    }
    interface WsListener {
        void onText(String msg);
        void onBinary(byte[] data);
        void onClosed();
    }

    private final BedrockVoice pl;
    private final int port;
    private final String bind;
    private final SSLContext tls;
    private final Handler handler;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "BedrockVoice-web");
        t.setDaemon(true);
        return t;
    });
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> hits = new ConcurrentHashMap<>();
    private volatile boolean running;

    // ------------------------------------------------------------------ security
    /** Connections open per address: one address can't take every slot (a slow-connection flood). */
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    static final int MAX_PER_IP = 16, MAX_OPEN = 400;
    /** Strike points per address (bad codes, wrong keys, floods, bad frames); 20 in 10 minutes = banned 15 minutes. */
    private final Map<String, long[]> strikes = new ConcurrentHashMap<>();
    private final Map<String, Long> banned = new ConcurrentHashMap<>();
    static final int BAN_AT = 20;
    static final long STRIKE_WINDOW = 600_000L, BAN_MS = 900_000L;

    boolean banned(String ip) {
        Long until = banned.get(ip);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        banned.remove(ip);
        return false;
    }

    /** Adds strike points to an address; enough of them bans it for a while (logged). */
    void strike(String ip, int points, String why) {
        if (ip == null || isLocal(ip)) return;
        long now = System.currentTimeMillis();
        if (strikes.size() > 20000) strikes.clear();
        long[] st = strikes.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (st) {
            if (now - st[0] > STRIKE_WINDOW) { st[0] = now; st[1] = 0; }
            st[1] += points;
            if (st[1] >= BAN_AT && !banned(ip)) {
                banned.put(ip, now + BAN_MS);
                st[1] = 0;
                pl.getLogger().warning("[Security] " + ip + " is blocked for 15 minutes (" + why + ").");
                for (Socket s : open) if (ip.equals(s.getInetAddress().getHostAddress())) try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    int bannedCount() { long now = System.currentTimeMillis(); banned.values().removeIf(t -> t < now); return banned.size(); }
    void unbanAll() { banned.clear(); strikes.clear(); }

    /** The server's own machine / LAN (Geyser, a reverse proxy, the owner testing) is never banned. */
    static boolean isLocal(String ip) {
        try { InetAddress a = InetAddress.getByName(ip); return a.isLoopbackAddress() || a.isAnyLocalAddress(); }
        catch (Exception e) { return false; }
    }

    WebServer(BedrockVoice pl, String bind, int port, SSLContext tls, Handler handler) {
        this.pl = pl; this.bind = bind; this.port = port; this.tls = tls; this.handler = handler;
    }

    boolean secure() { return tls != null; }

    void start() throws IOException {
        InetAddress addr = bind == null || bind.isBlank() || bind.equals("0.0.0.0") ? null : InetAddress.getByName(bind);
        if (tls != null) {
            SSLServerSocket s = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(port, 64, addr);
            s.setEnabledProtocols(Arrays.stream(s.getSupportedProtocols()).filter(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2")).toArray(String[]::new));
            server = s;
        } else {
            server = new ServerSocket(port, 64, addr);
        }
        running = true;
        Thread t = new Thread(this::acceptLoop, "BedrockVoice-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : open) try { s.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                String ip = s.getInetAddress().getHostAddress();
                // dropped before the TLS handshake: costs a banned / flooding address nothing to try, and us nothing
                if (open.size() >= MAX_OPEN || banned(ip)) { s.close(); continue; }
                AtomicInteger n = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
                if (n.incrementAndGet() > MAX_PER_IP) {
                    n.decrementAndGet();
                    strike(ip, 1, "too many connections at once");
                    s.close();
                    continue;
                }
                pool.submit(() -> { try { serve(s); } finally { if (n.decrementAndGet() <= 0) perIp.remove(ip, n); } });
            } catch (IOException e) {
                if (running) pl.getLogger().fine("accept: " + e);
            }
        }
    }

    /** At most 120 requests a minute per address (page loads, code tries), so nobody can hammer the port. */
    private boolean allowed(String ip) {
        long minute = System.currentTimeMillis() / 60000;
        if (hits.size() > 5000) hits.clear();
        int[] h = hits.computeIfAbsent(ip, k -> new int[]{(int) minute, 0});
        synchronized (h) {
            if (h[0] != (int) minute) { h[0] = (int) minute; h[1] = 0; }
            if (++h[1] <= 120) return true;
            if (h[1] % 20 == 0) strike(ip, 4, "request flood");
            return false;
        }
    }

    private void serve(Socket s) {
        open.add(s);
        String ip = s.getInetAddress().getHostAddress();
        try {
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            // the whole request head must arrive within 10 s and 16 KB (a byte-at-a-time "slowloris" can't hold a slot)
            long deadline = System.currentTimeMillis() + 10000;
            int[] budget = {16384};
            String line = readLine(in, deadline, budget);
            if (line == null) return;
            String[] req = line.split(" ");
            if (req.length != 3 || !req[2].startsWith("HTTP/1.") || !req[1].startsWith("/")) { strike(ip, 2, "bad request"); return; }
            Map<String, String> headers = new HashMap<>();
            for (String h; (h = readLine(in, deadline, budget)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
                if (headers.size() > 48) { strike(ip, 2, "too many headers"); return; }
            }
            String fwd = headers.get("x-forwarded-for");
            if (fwd != null && pl.trustProxy()) ip = fwd.split(",")[0].trim();
            if (!allowed(ip)) { respond(out, 429, "text/plain", "Slow down".getBytes(StandardCharsets.UTF_8)); return; }
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/ws") && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                if (!originOk(headers)) {
                    strike(ip, 5, "voice socket from another website");
                    respond(out, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                upgrade(s, in, out, headers, ip);
                return;
            }
            if (!req[0].equals("GET") && !req[0].equals("HEAD")) { respond(out, 405, "text/plain", new byte[0]); return; }
            Asset a = asset(path);
            if (a == null) { strike(ip, 1, "probing for files"); respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
            respond(out, 200, a.type, req[0].equals("HEAD") ? new byte[0] : a.data);
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            open.remove(s);
        }
    }

    private record Asset(String type, byte[] data) { }
    private final Map<String, Asset> assets = new ConcurrentHashMap<>();

    private Asset asset(String path) {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.equals("/favicon.ico")) path = "/icon.svg";
        if (!path.matches("/[a-z0-9_.-]+")) return null;
        return assets.computeIfAbsent(path, k -> {
            try (InputStream in = pl.getResource("web" + k)) {
                if (in == null) return null;
                byte[] data = in.readAllBytes();
                if (k.endsWith(".html")) data = pl.fillPage(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                String type = k.endsWith(".html") ? "text/html; charset=utf-8" : k.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : k.endsWith(".css") ? "text/css; charset=utf-8" : k.endsWith(".svg") ? "image/svg+xml" : k.endsWith(".json") ? "application/manifest+json"
                    : k.endsWith(".png") ? "image/png" : "application/octet-stream";
                return new Asset(type, data);
            } catch (IOException e) { return null; }
        });
    }

    void clearCache() { assets.clear(); }

    /**
     * Only the voice page itself may open a voice socket: a browser always sends the page's Origin, so another website
     * (which could otherwise make a visitor's browser connect) is refused. Same host as the request, or the public url.
     */
    private boolean originOk(Map<String, String> headers) {
        String origin = headers.get("origin");
        if (origin == null || origin.isEmpty()) return pl.getConfig().getBoolean("security.allow-no-origin", false);
        String o = origin.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        String host = headers.getOrDefault("host", "").toLowerCase(Locale.ROOT);
        if (!host.isEmpty() && (o.equals("https://" + host) || o.equals("http://" + host))) return true;
        String pub = pl.publicUrl().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        return !pub.isEmpty() && o.equals(pub);
    }

    /** Locks the page down: nothing but its own files run, nobody can frame it, real certificates get HSTS. */
    private String securityHeaders() {
        return "\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY"
            + "\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline';"
            + " img-src 'self' data: https://mc-heads.net; connect-src 'self' wss: ws:; manifest-src 'self'; media-src 'self' blob:;"
            + " base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
            + "\r\nCross-Origin-Opener-Policy: same-origin\r\nCross-Origin-Resource-Policy: same-origin"
            + "\r\nPermissions-Policy: microphone=(self), camera=(), geolocation=(), payment=(), usb=()"
            + (pl.realCertificate() ? "\r\nStrict-Transport-Security: max-age=31536000" : "");
    }

    private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = switch (code) { case 200 -> "OK"; case 403 -> "Forbidden"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed"; case 429 -> "Too Many Requests"; default -> "Error"; };
        String h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store" + securityHeaders() + "\r\nServer: voice\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in, long deadline, int[] budget) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (--budget[0] < 0) throw new IOException("request too big");
            if (System.currentTimeMillis() > deadline) throw new IOException("request too slow");
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c == -1 && sb.isEmpty() ? null : sb.toString();
    }

    private void upgrade(Socket s, InputStream in, OutputStream out, Map<String, String> headers, String ip) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) { respond(out, 405, "text/plain", new byte[0]); return; }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) { throw new IOException(e); }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
            + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        s.setSoTimeout(30000); // the page pings every 5 s
        Ws ws = new Ws(s, in, out);
        ws.ip = ip;
        ws.server = this;
        WsListener l = handler.open(ws, ip);
        try {
            ws.readLoop(l);
        } finally {
            ws.close();
            try { l.onClosed(); } catch (Throwable t) { pl.getLogger().warning("voice socket close: " + t); }
        }
    }

    /** One WebSocket. Reads happen on the connection's own thread; writes are queued for a writer thread. */
    static final class Ws {
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed;
        /** Bytes waiting to be written (a slow phone): audio is dropped past a limit instead of building up a delay. */
        private final AtomicInteger pending = new AtomicInteger();
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile Thread writer;

        Ws(Socket s, InputStream in, OutputStream out) { this.s = s; this.in = in; this.out = out; }

        String ip;
        WebServer server;
        /** Frames this second: the page sends ~50 mic frames a second plus a few messages; far past that is a flood. */
        private long rateSecond;
        private int rateCount, textCount;
        static final int MAX_FRAME = 64 * 1024, MAX_TEXT = 8 * 1024, MAX_FRAMES_PER_SECOND = 200, MAX_TEXTS_PER_SECOND = 30;

        private void abuse(int points, String why) throws IOException {
            if (server != null) server.strike(ip, points, why);
            throw new IOException(why);
        }

        boolean closed() { return closed; }
        int pending() { return pending.get(); }

        void readLoop(WsListener l) throws IOException {
            ByteArrayOutputStream frag = null;
            int fragOp = 0;
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) return;
                int b1 = in.read();
                if (b1 < 0) return;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                if (!masked) abuse(5, "unmasked voice frame"); // browsers always mask; RFC 6455 requires it
                if ((b0 & 0x70) != 0) abuse(5, "bad voice frame");
                long len = b1 & 0x7F;
                if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF); }
                if (len > MAX_FRAME || len < 0) abuse(5, "oversized voice frame");
                if (op >= 0x8 && (len > 125 || !fin)) abuse(5, "bad control frame");
                long sec = System.currentTimeMillis() / 1000;
                if (sec != rateSecond) { rateSecond = sec; rateCount = 0; textCount = 0; }
                if (++rateCount > MAX_FRAMES_PER_SECOND) abuse(6, "voice frame flood");
                if (op == 0x1 && ++textCount > MAX_TEXTS_PER_SECOND) abuse(6, "message flood");
                byte[] mask = new byte[4];
                if (masked) readFully(mask);
                byte[] data = new byte[(int) len];
                readFully(data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                switch (op) {
                    case 0x8 -> { send(0x8, new byte[0]); try { Thread.sleep(50); } catch (InterruptedException ignored) { } return; }
                    case 0x9 -> send(0xA, data);
                    case 0xA -> { }
                    case 0x0, 0x1, 0x2 -> {
                        if (op != 0) { fragOp = op; frag = null; }
                        if (!fin) {
                            if (frag == null) frag = new ByteArrayOutputStream();
                            frag.write(data);
                            if (frag.size() > MAX_FRAME) abuse(5, "oversized message");
                            continue;
                        }
                        if (frag != null) { frag.write(data); data = frag.toByteArray(); frag = null; }
                        if (fragOp == 0x1) {
                            if (data.length > MAX_TEXT) abuse(5, "oversized message");
                            l.onText(new String(data, StandardCharsets.UTF_8));
                        }
                        else l.onBinary(data);
                    }
                    default -> abuse(5, "bad opcode");
                }
            }
        }

        private void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }

        void text(String msg) { send(0x1, msg.getBytes(StandardCharsets.UTF_8)); }
        void binary(byte[] data) { send(0x2, data); }

        /** Queues a frame; one writer thread per socket sends them, so a slow phone never holds up the mixer. */
        void send(int op, byte[] data) {
            if (closed) return;
            byte[] f = frame(op, data);
            pending.addAndGet(f.length);
            queue.add(f);
            if (writer == null) startWriter();
        }

        private synchronized void startWriter() {
            if (writer != null) return;
            writer = new Thread(() -> {
                try {
                    while (!closed) {
                        byte[] f = queue.poll(5, TimeUnit.SECONDS);
                        if (f == null) continue;
                        do {
                            out.write(f);
                            pending.addAndGet(-f.length);
                        } while ((f = queue.poll()) != null);
                        out.flush();
                    }
                } catch (InterruptedException | IOException e) {
                    close();
                }
            }, "BedrockVoice-ws-writer");
            writer.setDaemon(true);
            writer.start();
        }

        static byte[] frame(int op, byte[] data) {
            int head = data.length < 126 ? 2 : data.length < 65536 ? 4 : 10;
            byte[] f = new byte[head + data.length];
            f[0] = (byte) (0x80 | op);
            if (data.length < 126) f[1] = (byte) data.length;
            else if (data.length < 65536) { f[1] = 126; f[2] = (byte) (data.length >>> 8); f[3] = (byte) data.length; }
            else { f[1] = 127; for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((long) data.length >>> ((7 - i) * 8)); }
            System.arraycopy(data, 0, f, head, data.length);
            return f;
        }

        void close() {
            if (closed) return;
            closed = true;
            queue.clear();
            pending.set(0);
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
