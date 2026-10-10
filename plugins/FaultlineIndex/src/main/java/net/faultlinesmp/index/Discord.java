package net.faultlinesmp.index;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.BroadcastMessageEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * THE DISCORD BRIDGE (config discord: in the Index's config.yml): the server's big moments go to a Discord channel
 * through a webhook. Every server-wide announcement that matches one of discord.forward (boss defeats, meteors, the
 * Caravan, achievements, firsts, weather events, the Ghost Ship...) is posted, colours stripped, at most one message a
 * second and a half (Discord's rate limit). Off until a webhook URL is set. /discord test posts a test message.
 */
final class Discord implements Listener {

    static final List<String> DEFAULT_FORWARD = List.of(
            "(?i)has been defeated", "(?i)has fallen", "(?i)has been laid to rest", "(?i)defeated", "(?i)meteor", "(?i)caravan",
            "(?i)achievement", "(?i)is the first to", "(?i)aurora", "(?i)sandstorm", "(?i)blizzard", "(?i)eclipse", "(?i)locust",
            "(?i)ghost ship|wailing mary", "(?i)invasion", "(?i)boss rush");

    private final FaultlineIndex index;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Deque<String> queue = new ArrayDeque<>();
    private List<Pattern> patterns = new ArrayList<>();
    private long sent;

    Discord(FaultlineIndex index) {
        this.index = index;
        reload();
        Bukkit.getScheduler().runTaskTimerAsynchronously(index, this::pump, 30L, 30L);
    }

    void reload() {
        List<Pattern> p = new ArrayList<>();
        List<String> list = index.getConfig().isList("discord.forward") ? index.getConfig().getStringList("discord.forward") : DEFAULT_FORWARD;
        for (String s : list) {
            try { p.add(Pattern.compile(s)); } catch (RuntimeException e) { index.getLogger().warning("Bad discord.forward pattern: " + s); }
        }
        patterns = p;
    }

    boolean on() {
        String url = index.getConfig().getString("discord.webhook-url", "");
        return index.getConfig().getBoolean("discord.enabled", true) && url != null && url.startsWith("https://");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        if (!on()) return;
        String text = ChatColor.stripColor(PlainTextComponentSerializer.plainText().serialize(e.message())).trim();
        if (text.isEmpty()) return;
        for (Pattern p : patterns) if (p.matcher(text).find()) { post(text); return; }
    }

    /** Queue a message for the channel. */
    void post(String text) {
        if (!on()) return;
        synchronized (queue) {
            if (queue.size() < 40) queue.add(text.length() > 1900 ? text.substring(0, 1900) : text);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();

    // BUG FIX: a slow webhook (up to 10 s a send) overlapped the next run (every 1.5 s): several sends at once, posted
    // out of order. One at a time now.
    private void pump() {
        if (!sending.compareAndSet(false, true)) return;
        try { pumpOne(); } finally { sending.set(false); }
    }

    private void pumpOne() {
        String next;
        synchronized (queue) { next = queue.poll(); }
        if (next == null) return;
        String url = index.getConfig().getString("discord.webhook-url", "");
        String name = index.getConfig().getString("discord.username", "Faultline SMP");
        String body = "{\"username\":" + json(name) + ",\"content\":" + json(next) + ",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) synchronized (queue) { queue.addFirst(next); } // rate limited: try again next time
            else if (res.statusCode() >= 300 && System.currentTimeMillis() - sent > 60000) index.getLogger().warning("Discord webhook answered " + res.statusCode() + ": " + res.body());
            sent = System.currentTimeMillis();
        } catch (Exception ex) {
            if (System.currentTimeMillis() - sent > 60000) index.getLogger().warning("Discord webhook failed: " + ex.getMessage());
            sent = System.currentTimeMillis();
        }
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    int queued() { synchronized (queue) { return queue.size(); } }
}
