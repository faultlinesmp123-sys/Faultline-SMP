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
                if (open.size() > 400) { s.close(); continue; }
                pool.submit(() -> serve(s));
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
            return ++h[1] <= 120;
        }
    }

    private void serve(Socket s) {
        open.add(s);
        String ip = s.getInetAddress().getHostAddress();
        try {
            s.setSoTimeout(15000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] req = line.split(" ");
            if (req.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
                if (headers.size() > 64) return;
            }
            String fwd = headers.get("x-forwarded-for");
            if (fwd != null && pl.trustProxy()) ip = fwd.split(",")[0].trim();
            if (!allowed(ip)) { respond(out, 429, "text/plain", "Slow down".getBytes(StandardCharsets.UTF_8)); return; }
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/ws") && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                upgrade(s, in, out, headers, ip);
                return;
            }
            if (!req[0].equals("GET") && !req[0].equals("HEAD")) { respond(out, 405, "text/plain", new byte[0]); return; }
            Asset a = asset(path);
            if (a == null) { respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
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

    private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = switch (code) { case 200 -> "OK"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed"; case 429 -> "Too Many Requests"; default -> "Error"; };
        String h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
            + "\r\nPermissions-Policy: microphone=(self)\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
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
                long len = b1 & 0x7F;
                if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF); }
                if (len > 1 << 20 || len < 0) throw new IOException("frame too big");
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
                            if (frag.size() > 1 << 20) throw new IOException("message too big");
                            continue;
                        }
                        if (frag != null) { frag.write(data); data = frag.toByteArray(); frag = null; }
                        if (fragOp == 0x1) l.onText(new String(data, StandardCharsets.UTF_8));
                        else l.onBinary(data);
                    }
                    default -> throw new IOException("bad opcode");
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
