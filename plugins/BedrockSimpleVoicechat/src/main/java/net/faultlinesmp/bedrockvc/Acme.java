package net.faultlinesmp.bedrockvc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small Let's Encrypt (ACME, RFC 8555) client: gets a real, free certificate for the server's domain so browsers show no
 * "not private" warning. Uses the HTTP-01 challenge: Let's Encrypt fetches http://DOMAIN/.well-known/acme-challenge/TOKEN,
 * so TCP port 80 must be open while it checks (we listen there only for the minute it takes). The certificate lasts 90
 * days and is renewed 30 days before it runs out. Files: letsencrypt/fullchain.pem, privkey.pem, account.key.
 */
final class Acme {
    static final String LIVE = "https://acme-v02.api.letsencrypt.org/directory";

    private final BedrockVoice pl;
    private final String directory;
    private final int challengePort;
    private final File dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private String nonce;
    private String kid;
    private KeyPair account;
    private JsonObject dirJson;

    Acme(BedrockVoice pl) {
        this.pl = pl;
        this.directory = pl.getConfig().getString("web.tls.letsencrypt.directory", LIVE);
        this.challengePort = pl.getConfig().getInt("web.tls.letsencrypt.challenge-port", 80);
        this.dir = new File(pl.getDataFolder(), "letsencrypt");
    }

    File certFile() { return new File(dir, "fullchain.pem"); }
    File keyFile() { return new File(dir, "privkey.pem"); }
    File hostFile() { return new File(dir, "domain.txt"); }

    /** A domain Let's Encrypt can certify: a real name, not an IP / localhost. */
    static boolean certifiable(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.contains(":")) return false;
        if (h.matches("[0-9.]+")) return false;
        return h.contains(".") && h.matches("[a-z0-9.-]+");
    }

    /** Days left on the saved certificate for this host, or -1 when there's none / it's for another host. */
    long daysLeft(String host) {
        try {
            if (!certFile().isFile() || !keyFile().isFile() || !hostFile().isFile()) return -1;
            if (!Files.readString(hostFile().toPath()).trim().equalsIgnoreCase(host)) return -1;
            try (InputStream in = Files.newInputStream(certFile().toPath())) {
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                return (c.getNotAfter().getTime() - System.currentTimeMillis()) / 86400000L;
            }
        } catch (Exception e) { return -1; }
    }

    /** Gets a certificate for {@code host} and saves it; throws with a readable reason when it can't. */
    void obtain(String host) throws Exception {
        dir.mkdirs();
        dirJson = JsonParser.parseString(get(directory)).getAsJsonObject();
        account = accountKey();
        newNonce();
        // account (an existing one for this key is returned as-is)
        JsonObject acct = new JsonObject();
        acct.addProperty("termsOfServiceAgreed", true);
        String email = pl.getConfig().getString("web.tls.letsencrypt.email", "").trim();
        if (!email.isEmpty()) { JsonArray c = new JsonArray(); c.add("mailto:" + email); acct.add("contact", c); }
        HttpResponse<String> r = post(url("newAccount"), acct.toString(), true);
        kid = r.headers().firstValue("Location").orElseThrow(() -> new IOException("Let's Encrypt gave no account url"));

        // order
        JsonObject ord = new JsonObject();
        JsonArray ids = new JsonArray();
        JsonObject id = new JsonObject(); id.addProperty("type", "dns"); id.addProperty("value", host); ids.add(id);
        ord.add("identifiers", ids);
        r = post(url("newOrder"), ord.toString(), false);
        String orderUrl = r.headers().firstValue("Location").orElseThrow(() -> new IOException("no order url"));
        JsonObject order = JsonParser.parseString(r.body()).getAsJsonObject();

        HttpServer challenge = null;
        try {
            for (JsonElement a : order.getAsJsonArray("authorizations")) {
                String authzUrl = a.getAsString();
                JsonObject authz = JsonParser.parseString(post(authzUrl, null, false).body()).getAsJsonObject();
                if ("valid".equals(str(authz, "status"))) continue;
                JsonObject ch = null;
                for (JsonElement c : authz.getAsJsonArray("challenges"))
                    if ("http-01".equals(str(c.getAsJsonObject(), "type"))) ch = c.getAsJsonObject();
                if (ch == null) throw new IOException("Let's Encrypt offered no http-01 challenge");
                String token = str(ch, "token");
                tokens.put(token, token + "." + thumbprint());
                if (challenge == null) challenge = serveChallenges();
                post(str(ch, "url"), "{}", false);
                authz = poll(authzUrl, "pending", "processing");
                if (!"valid".equals(str(authz, "status"))) throw new IOException("Let's Encrypt couldn't check the domain: " + problem(authz));
            }
        } finally {
            if (challenge != null) challenge.stop(0);
            tokens.clear();
        }

        // finalize with a CSR for a fresh certificate key
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair certKey = g.generateKeyPair();
        JsonObject fin = new JsonObject();
        fin.addProperty("csr", b64(csr(host, certKey)));
        post(str(order, "finalize"), fin.toString(), false);
        order = poll(orderUrl, "pending", "ready", "processing");
        if (!"valid".equals(str(order, "status"))) throw new IOException("the order failed: " + problem(order));
        String chain = post(str(order, "certificate"), null, false).body();
        if (!chain.contains("BEGIN CERTIFICATE")) throw new IOException("no certificate came back");
        try (InputStream in = new ByteArrayInputStream(chain.getBytes(StandardCharsets.US_ASCII))) {
            Collection<? extends Certificate> cs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (cs.isEmpty()) throw new IOException("the certificate couldn't be read");
        }
        write(keyFile(), pem("PRIVATE KEY", certKey.getPrivate().getEncoded()));
        write(certFile(), chain);
        write(hostFile(), host);
    }

    // ------------------------------------------------------------------ the HTTP-01 answer on port 80

    private HttpServer serveChallenges() throws IOException {
        HttpServer s;
        try { s = HttpServer.create(new InetSocketAddress(challengePort), 16); }
        catch (IOException e) {
            throw new IOException("port " + challengePort + " is in use or not allowed (" + e.getMessage() + "). Let's Encrypt checks the domain on port 80:"
                + " stop whatever uses it (IIS, another web server) for a minute, or use web.tls.mode: pem with your own certificate");
        }
        s.createContext("/.well-known/acme-challenge/", ex -> {
            String path = ex.getRequestURI().getPath();
            String token = path.substring(path.lastIndexOf('/') + 1);
            String ans = tokens.get(token);
            byte[] body = (ans == null ? "not found" : ans).getBytes(StandardCharsets.US_ASCII);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(ans == null ? 404 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }

    // ------------------------------------------------------------------ JWS

    private HttpResponse<String> post(String url, String payload, boolean useJwk) throws Exception {
        for (int attempt = 0; ; attempt++) {
            JsonObject prot = new JsonObject();
            prot.addProperty("alg", "RS256");
            if (useJwk) prot.add("jwk", jwk()); else prot.addProperty("kid", kid);
            prot.addProperty("nonce", nonce);
            prot.addProperty("url", url);
            String p64 = b64(prot.toString().getBytes(StandardCharsets.UTF_8));
            String b64Payload = payload == null ? "" : b64(payload.getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(account.getPrivate());
            s.update((p64 + "." + b64Payload).getBytes(StandardCharsets.US_ASCII));
            JsonObject jws = new JsonObject();
            jws.addProperty("protected", p64);
            jws.addProperty("payload", b64Payload);
            jws.addProperty("signature", b64(s.sign()));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/jose+json").header("User-Agent", "BedrockSimpleVoicechat")
                .POST(HttpRequest.BodyPublishers.ofString(jws.toString())).build(), HttpResponse.BodyHandlers.ofString());
            r.headers().firstValue("Replay-Nonce").ifPresent(n -> nonce = n);
            if (r.statusCode() < 400) return r;
            String body = r.body();
            if (attempt < 3 && body.contains("badNonce")) continue;
            throw new IOException("Let's Encrypt said " + r.statusCode() + ": " + detail(body));
        }
    }

    private JsonObject poll(String url, String... waiting) throws Exception {
        Set<String> wait = Set.of(waiting);
        for (int i = 0; i < 40; i++) {
            JsonObject o = JsonParser.parseString(post(url, null, false).body()).getAsJsonObject();
            if (!wait.contains(str(o, "status"))) return o;
            Thread.sleep(i < 5 ? 1000 : 3000);
        }
        throw new IOException("Let's Encrypt took too long");
    }

    private void newNonce() throws Exception {
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url("newNonce"))).timeout(Duration.ofSeconds(20))
            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        nonce = r.headers().firstValue("Replay-Nonce").orElseThrow(() -> new IOException("no nonce from Let's Encrypt"));
    }

    private String get(String u) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw new IOException("couldn't reach Let's Encrypt (" + r.statusCode() + ")");
        return r.body();
    }

    private String url(String name) throws IOException {
        JsonElement e = dirJson.get(name);
        if (e == null) throw new IOException("the ACME directory has no " + name);
        return e.getAsString();
    }

    private JsonObject jwk() {
        RSAPublicKey k = (RSAPublicKey) account.getPublic();
        JsonObject j = new JsonObject();
        j.addProperty("e", b64(unsigned(k.getPublicExponent())));
        j.addProperty("kty", "RSA");
        j.addProperty("n", b64(unsigned(k.getModulus())));
        return j;
    }

    /** RFC 7638: SHA-256 of the JWK's required members in lexicographic order, no spaces. */
    private String thumbprint() throws Exception {
        RSAPublicKey k = (RSAPublicKey) account.getPublic();
        String json = "{\"e\":\"" + b64(unsigned(k.getPublicExponent())) + "\",\"kty\":\"RSA\",\"n\":\"" + b64(unsigned(k.getModulus())) + "\"}";
        return b64(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private KeyPair accountKey() throws Exception {
        File f = new File(dir, "account.key");
        if (f.isFile()) {
            PrivateKey priv = Tls.pemKey(Files.readString(f.toPath(), StandardCharsets.US_ASCII));
            java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) priv;
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(pub, priv);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        write(f, pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        return kp;
    }

    // ------------------------------------------------------------------ CSR (PKCS#10), built in DER like the self-signed cert

    static byte[] csr(String host, KeyPair kp) throws Exception {
        byte[] name = Tls.seq(Tls.set(Tls.seq(Tls.cat(Tls.oid(new int[]{2, 5, 4, 3}), Tls.tlv(0x0C, host.getBytes(StandardCharsets.UTF_8))))));
        byte[] san = Tls.seq(Tls.cat(Tls.oid(new int[]{2, 5, 29, 17}), Tls.tlv(0x04, Tls.seq(Tls.tlv(0x82, host.getBytes(StandardCharsets.US_ASCII))))));
        byte[] extReq = Tls.seq(Tls.cat(Tls.oid(new int[]{1, 2, 840, 113549, 1, 9, 14}), Tls.set(Tls.seq(san))));
        byte[] info = Tls.seq(Tls.cat(new byte[]{0x02, 0x01, 0x00}, name, kp.getPublic().getEncoded(), Tls.tlv(0xA0, extReq)));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(info);
        byte[] alg = Tls.seq(Tls.cat(Tls.oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00}));
        return Tls.seq(Tls.cat(info, alg, Tls.tlv(0x03, Tls.cat(new byte[]{0}, s.sign()))));
    }

    // ------------------------------------------------------------------ bits

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.US_ASCII);
        try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? "" : e.getAsString(); }

    private static String problem(JsonObject o) {
        if (o.has("challenges")) for (JsonElement c : o.getAsJsonArray("challenges")) {
            JsonObject co = c.getAsJsonObject();
            if (co.has("error")) return detail(co.get("error").toString());
        }
        if (o.has("error")) return detail(o.get("error").toString());
        return str(o, "status");
    }

    private static String detail(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String d = str(o, "detail");
            return d.isEmpty() ? body : d;
        } catch (Exception e) { return body.length() > 300 ? body.substring(0, 300) : body; }
    }
}
