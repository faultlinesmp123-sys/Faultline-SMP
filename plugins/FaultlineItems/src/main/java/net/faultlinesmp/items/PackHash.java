package net.faultlinesmp.items;

import org.bukkit.Bukkit;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The real SHA-1 of each resource pack (Items 1.2.50). The hash in config.yml had to be updated by hand every time a zip
 * changed; when it didn't match the file on GitHub, every player's game rejected that pack (the Dark Ocean's music went
 * silent). Now the plugin downloads each pack url itself (off the main thread) at startup, hashes it, and checks again
 * every `item-textures.auto-hash-minutes` (10) with an ETag request (304 = unchanged, nothing downloaded). The pack is
 * sent with the hash it really has; a config hash that disagrees is ignored with a warning.
 */
final class PackHash {
    private PackHash() { }

    private record Known(String sha1, String etag) { }
    private static final Map<String, Known> KNOWN = new ConcurrentHashMap<>();

    /** The url's real hash, if it has been worked out (null = not yet / couldn't download: use the config's). */
    static String of(String url) {
        Known k = url == null ? null : KNOWN.get(url);
        return k == null ? null : k.sha1;
    }

    /** Fetch (or re-check) a url. Blocking: call it off the main thread. */
    static void refresh(String url, Logger log) {
        if (url == null || url.isBlank()) return;
        try {
            Known was = KNOWN.get(url);
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "FaultlineItems");
            if (was != null && was.etag != null) c.setRequestProperty("If-None-Match", was.etag);
            int code = c.getResponseCode();
            if (code == 304) return;
            if (code != 200) { log.warning("[Resource pack] couldn't check " + url + " (HTTP " + code + "): using the hash in config.yml"); return; }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long size = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) { md.update(buf, 0, n); size += n; }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            KNOWN.put(url, new Known(sha, c.getHeaderField("ETag")));
            if (was == null || !was.sha1.equals(sha))
                log.info("[Resource pack] " + url.substring(url.lastIndexOf('/') + 1) + ": " + (size / 1024) + " KB, SHA-1 " + sha + (was == null ? "" : " (it changed)"));
        } catch (Exception e) {
            log.warning("[Resource pack] couldn't download " + url + " to check its hash (" + e + "): using the hash in config.yml");
        }
    }

    /** The hash to send: the real one if known (warning once if config.yml disagrees), else config.yml's. */
    static String pick(String url, String configHex, String key, Logger log) {
        String real = of(url);
        String cfg = configHex == null ? "" : configHex.trim().toLowerCase(java.util.Locale.ROOT);
        if (real == null) return cfg;
        if (!cfg.isEmpty() && !cfg.equals(real) && WARNED.add(key + cfg))
            log.warning("[Resource pack] item-textures." + key + " in config.yml (" + cfg + ") doesn't match the file at the url (" + real
                    + "). Sending the real one. (Fix the config, or leave it: auto-hash handles it.)");
        return real;
    }

    private static final java.util.Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Starts the checks: now, then every few minutes. */
    static void start(FaultlineItems plugin) {
        if (!plugin.getConfig().getBoolean("item-textures.auto-hash", true)) return;
        long every = Math.max(1, plugin.getConfig().getLong("item-textures.auto-hash-minutes", 10)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            refresh(plugin.getConfig().getString("item-textures.url", ""), plugin.getLogger());
            refresh(plugin.getConfig().getString("item-textures.music-url", ""), plugin.getLogger());
        }, 1L, every);
    }
}
