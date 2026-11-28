package net.faultlinesmp.bedrockvc;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * HTTPS for the voice page. Phones only let a page use the microphone over https, so the server needs a certificate:
 *  - pem:     your own (Let's Encrypt fullchain.pem + privkey.pem, e.g. from certbot or win-acme): no warning at all.
 *  - pkcs12:  a .p12 / .pfx file and its password.
 *  - auto:    a self-signed certificate made here (pure Java, saved as selfsigned.p12): works, but browsers show a
 *             "not private" page once; tap Advanced -> Proceed. Then the mic works.
 *  - off:     plain http (only for use behind an https tunnel / reverse proxy, e.g. Cloudflare Tunnel or playit.gg).
 */
final class Tls {
    private Tls() {}

    static SSLContext load(BedrockVoice pl, String mode, String host) throws Exception {
        File dir = pl.getDataFolder();
        KeyStore ks;
        char[] pass;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "off", "none", "http" -> { return null; }
            case "pkcs12", "pfx", "p12" -> {
                File f = file(dir, pl.getConfig().getString("web.tls.pkcs12-file", "cert.p12"));
                pass = pl.getConfig().getString("web.tls.pkcs12-password", "").toCharArray();
                ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = new FileInputStream(f)) { ks.load(in, pass); }
            }
            case "pem" -> {
                File certF = file(dir, pl.getConfig().getString("web.tls.cert-file", "fullchain.pem"));
                File keyF = file(dir, pl.getConfig().getString("web.tls.key-file", "privkey.pem"));
                List<Certificate> chain = new ArrayList<>();
                try (InputStream in = new FileInputStream(certF)) {
                    chain.addAll(CertificateFactory.getInstance("X.509").generateCertificates(in));
                }
                if (chain.isEmpty()) throw new IOException("no certificate in " + certF);
                PrivateKey key = pemKey(Files.readString(keyF.toPath(), StandardCharsets.US_ASCII));
                pass = randomPass();
                ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                ks.setKeyEntry("bvc", key, pass, chain.toArray(new Certificate[0]));
            }
            default -> { // auto
                File f = new File(dir, "selfsigned.p12");
                File pf = new File(dir, "selfsigned.pass");
                File hf = new File(dir, "selfsigned.host");
                String want = host == null ? "" : host;
                boolean remake = !f.isFile() || !pf.isFile() || !hf.isFile() || !Files.readString(hf.toPath()).trim().equals(want);
                if (!remake) {
                    pass = Files.readString(pf.toPath()).trim().toCharArray();
                    ks = KeyStore.getInstance("PKCS12");
                    try (InputStream in = new FileInputStream(f)) { ks.load(in, pass); }
                    X509Certificate c = (X509Certificate) ks.getCertificate("bvc");
                    if (c == null || c.getNotAfter().getTime() < System.currentTimeMillis() + 86400000L * 30) remake = true;
                } else ks = null;
                if (remake) {
                    pass = randomPass();
                    ks = selfSigned(want, pass);
                    dir.mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) { ks.store(out, pass); }
                    Files.writeString(pf.toPath(), new String(pass));
                    Files.writeString(hf.toPath(), want);
                    pl.getLogger().info("Made a self-signed certificate for '" + (want.isEmpty() ? "localhost" : want) + "' (selfsigned.p12).");
                } else pass = Files.readString(pf.toPath()).trim().toCharArray();
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    private static File file(File dir, String name) {
        File f = new File(name);
        return f.isAbsolute() ? f : new File(dir, name);
    }

    private static char[] randomPass() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).toCharArray();
    }

    /** PKCS#8 ("PRIVATE KEY", RSA or EC) or PKCS#1 ("RSA PRIVATE KEY"). */
    static PrivateKey pemKey(String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        if (pem.contains("BEGIN EC PRIVATE KEY"))
            throw new IOException("EC keys in SEC1 form aren't supported: convert with `openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out key.pem`");
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        if (pkcs1) {
            byte[] algId = {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
            der = seq(cat(new byte[]{0x02, 0x01, 0x00}, algId, tlv(0x04, der)));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        for (String alg : new String[]{"RSA", "EC", "Ed25519"}) {
            try { return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der)); } catch (Exception ignored) { }
        }
        throw new IOException("Couldn't read the private key");
    }

    /** A 10-year self-signed RSA certificate, built by hand in DER (no keytool, no internal JDK classes). */
    static KeyStore selfSigned(String host, char[] pass) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        byte[] sha256rsa = seq(cat(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00}));
        String cn = host == null || host.isBlank() ? "localhost" : host;
        byte[] name = seq(set(seq(cat(oid(new int[]{2, 5, 4, 3}), tlv(0x0C, ("BedrockVoice " + cn).getBytes(StandardCharsets.UTF_8))))));
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'");
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = seq(cat(tlv(0x17, utc.format(new Date(now - 86400000L)).getBytes(StandardCharsets.US_ASCII)),
            tlv(0x17, utc.format(new Date(now + 86400000L * 3650)).getBytes(StandardCharsets.US_ASCII))));
        // subjectAltName: the host (DNS or IP) + localhost + 127.0.0.1
        ByteArrayOutputStream san = new ByteArrayOutputStream();
        Set<String> names = new LinkedHashSet<>(List.of(cn, "localhost", "127.0.0.1"));
        for (String n : names) {
            if (n.matches("[0-9.]+") || n.contains(":")) {
                try { san.write(tlv(0x87, InetAddress.getByName(n).getAddress())); } catch (Exception ignored) { }
            } else san.write(tlv(0x82, n.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] sanExt = seq(cat(oid(new int[]{2, 5, 29, 17}), tlv(0x04, seq(san.toByteArray()))));
        byte[] ekuExt = seq(cat(oid(new int[]{2, 5, 29, 37}), tlv(0x04, seq(oid(new int[]{1, 3, 6, 1, 5, 5, 7, 3, 1})))));
        byte[] exts = tlv(0xA3, seq(cat(sanExt, ekuExt)));
        byte[] serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = seq(cat(tlv(0xA0, new byte[]{0x02, 0x01, 0x02}), tlv(0x02, serial), sha256rsa, name, validity, name,
            kp.getPublic().getEncoded(), exts));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign();
        byte[] cert = seq(cat(tbs, sha256rsa, tlv(0x03, cat(new byte[]{0}, s))));
        Certificate c = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("bvc", kp.getPrivate(), pass, new Certificate[]{c});
        return ks;
    }

    // --- tiny DER writer ---
    static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >>> 8); o.write(n & 0xFF); }
        else { o.write(0x83); o.write(n >>> 16); o.write((n >>> 8) & 0xFF); o.write(n & 0xFF); }
        o.writeBytes(v);
        return o.toByteArray();
    }
    static byte[] seq(byte[] v) { return tlv(0x30, v); }
    static byte[] set(byte[] v) { return tlv(0x31, v); }
    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
    static byte[] oid(int[] arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int k = 0;
            do { tmp[k++] = (byte) (v & 0x7F); v >>>= 7; } while (v != 0);
            for (int j = k - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }
}
