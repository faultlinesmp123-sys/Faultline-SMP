package net.faultlinesmp.index;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * THE DISCORD BRIDGE (config discord: in the Index's config.yml), two webhooks:
 *  - PUBLIC (discord.webhook-url): chat, joins/leaves, deaths (player kills too), and every server-wide announcement that
 *    matches discord.forward (boss defeats, achievements, firsts, meteors, the Caravan, weather, invasions...).
 *  - LOGS (discord.logs-webhook-url, staff only): everything the public channel gets, plus who placed / lit TNT (and the
 *    other discord.logs.blocks: TNT minecarts, end crystals...) with name, place, date and time, whispers (/msg /tell /w
 *    /r ...), commands run by admins (op or faultlineindex.logged) and the console.
 * Each channel is its own queue: lines are batched into one message (up to ~1900 characters) every 2.1 s, which keeps
 * under Discord's 30 a minute per webhook even when chat is busy; a 429 waits as long as Discord says. Player text is
 * markdown-escaped and no message can ping anyone (allowed_mentions empty). Off until a URL is set.
 * The URLs are secrets: they live only in the server's config file, never in the repo (it's public).
 */
final class Discord implements Listener {

    static final List<String> DEFAULT_FORWARD = List.of(
            "(?i)has been defeated", "(?i)has fallen", "(?i)has been laid to rest", "(?i)defeated", "(?i)meteor", "(?i)caravan",
            "(?i)achievement", "(?i)is the first to", "(?i)aurora", "(?i)sandstorm", "(?i)blizzard", "(?i)eclipse", "(?i)locust",
            "(?i)ghost ship|wailing mary", "(?i)invasion", "(?i)boss rush");
    static final List<String> DEFAULT_WHISPERS = List.of("msg", "tell", "w", "whisper", "r", "reply", "m", "t", "pm", "dm",
            "emsg", "etell", "ewhisper", "er", "ereply", "message", "teammsg", "tm");
    static final List<String> DEFAULT_IGNORED = List.of("login", "l", "log", "register", "reg", "changepassword", "changepass", "cp");
    static final List<String> DEFAULT_BLOCKS = List.of("TNT", "TNT_MINECART", "END_CRYSTAL", "RESPAWN_ANCHOR");
    static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** One webhook: its own queue, one send at a time. */
    final class Channel {
        final String name;
        volatile String url = "";
        final Deque<String> queue = new ArrayDeque<>();
        final AtomicBoolean sending = new AtomicBoolean();
        volatile long waitUntil, warned;
        int dropped;

        Channel(String name) { this.name = name; }

        boolean on() { return enabled && url != null && (url.startsWith("https://") || url.startsWith("http://127.0.0.1")); }

        void add(String line) {
            if (!on() || line == null || line.isBlank()) return;
            if (line.length() > 1900) line = line.substring(0, 1900);
            synchronized (queue) {
                if (queue.size() >= 400) { queue.poll(); dropped++; }
                queue.add(line);
            }
        }

        int size() { synchronized (queue) { return queue.size(); } }

        void pump() {
            if (System.currentTimeMillis() < waitUntil || !sending.compareAndSet(false, true)) return;
            try { sendBatch(); } finally { sending.set(false); }
        }

        private void sendBatch() {
            List<String> lines = new ArrayList<>();
            int len = 0;
            synchronized (queue) {
                if (dropped > 0) { String d = "*(" + dropped + " lines skipped: too much at once)*"; lines.add(d); len += d.length() + 1; dropped = 0; }
                while (!queue.isEmpty() && len + queue.peek().length() + 1 <= 1900) { String s = queue.poll(); lines.add(s); len += s.length() + 1; }
            }
            if (lines.isEmpty()) return;
            String content = String.join("\n", lines);
            int code = send(url, content, 10);
            if (code == 429) {
                synchronized (queue) { for (int i = lines.size() - 1; i >= 0; i--) queue.addFirst(lines.get(i)); }
                waitUntil = System.currentTimeMillis() + retryAfter;
            } else if (code != 0 && (code < 200 || code >= 300) && System.currentTimeMillis() - warned > 60000) {
                warned = System.currentTimeMillis();
                index.getLogger().warning("Discord " + name + " webhook answered " + code + lastBody);
            }
        }
    }

    private final FaultlineIndex index;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    final Channel pub = new Channel("public"), logs = new Channel("logs");
    private volatile List<Pattern> patterns = new ArrayList<>();
    private volatile Set<String> whispers = Set.of(), ignored = Set.of();
    private volatile Set<Material> blocks = Set.of();
    private volatile boolean enabled = true, chat = true, joins = true, deaths = true, logMirror = true, logWhispers = true,
            logAdmin = true, logAll = false, logConsole = true, logTnt = true;
    private volatile String username = "Faultline SMP";
    volatile String suggestUrl = "";
    private volatile long retryAfter = 2000;
    private volatile String lastBody = "";

    Discord(FaultlineIndex index) {
        this.index = index;
        reload();
        Bukkit.getScheduler().runTaskTimerAsynchronously(index, () -> { pub.pump(); logs.pump(); }, 42L, 42L);
    }

    void reload() {
        var c = index.getConfig();
        List<Pattern> p = new ArrayList<>();
        for (String s : c.isList("discord.forward") ? c.getStringList("discord.forward") : DEFAULT_FORWARD) {
            try { p.add(Pattern.compile(s)); } catch (RuntimeException e) { index.getLogger().warning("Bad discord.forward pattern: " + s); }
        }
        patterns = p;
        enabled = c.getBoolean("discord.enabled", true);
        pub.url = c.getString("discord.webhook-url", "");
        logs.url = c.getString("discord.logs-webhook-url", "");
        suggestUrl = c.getString("discord.suggestions-webhook-url", "");
        username = c.getString("discord.username", "Faultline SMP");
        chat = c.getBoolean("discord.public.chat", true);
        joins = c.getBoolean("discord.public.joins", true);
        deaths = c.getBoolean("discord.public.deaths", true);
        logMirror = c.getBoolean("discord.logs.mirror-public", true);
        logWhispers = c.getBoolean("discord.logs.whispers", true);
        logAdmin = c.getBoolean("discord.logs.admin-commands", true);
        logAll = c.getBoolean("discord.logs.all-commands", false);
        logConsole = c.getBoolean("discord.logs.console-commands", true);
        logTnt = c.getBoolean("discord.logs.tnt", true);
        whispers = lower(c.isList("discord.logs.whisper-commands") ? c.getStringList("discord.logs.whisper-commands") : DEFAULT_WHISPERS);
        ignored = lower(c.isList("discord.logs.ignore-commands") ? c.getStringList("discord.logs.ignore-commands") : DEFAULT_IGNORED);
        Set<Material> b = new HashSet<>();
        for (String s : c.isList("discord.logs.blocks") ? c.getStringList("discord.logs.blocks") : DEFAULT_BLOCKS) {
            Material m = Material.matchMaterial(s);
            if (m != null) b.add(m); else index.getLogger().warning("Unknown discord.logs.blocks material: " + s);
        }
        blocks = b;
    }

    private static Set<String> lower(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(x.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        return s;
    }

    boolean on() { return pub.on() || logs.on(); }

    // ---------------------------------------------------------------- posting

    /** A line for the public channel (and the logs, which mirror it). */
    void post(String text) {
        pub.add(text);
        if (logMirror) logs.add(text);
    }

    /** A line for the logs channel only, stamped with the date and time. */
    void log(String text) { logs.add(text + " " + stamp()); }

    /** Discord shows {@code <t:..:f>} in each reader's own time zone; the UTC text stays readable in a copy-paste. */
    static String stamp() {
        Instant now = Instant.now();
        return "<t:" + now.getEpochSecond() + ":f> (" + UTC.format(now) + ")";
    }

    int queued() { return pub.size(); }

    String status() {
        String sg = suggestUrl != null && (suggestUrl.startsWith("https://") || suggestUrl.startsWith("http://127.0.0.1")) ? "own channel"
                : pub.on() ? "public channel" : "off";
        return "public " + (pub.on() ? "on (" + pub.size() + " queued)" : "off") + ", logs " + (logs.on() ? "on (" + logs.size() + " queued)" : "off")
                + ", suggestions " + sg;
    }

    // ---------------------------------------------------------------- public

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        if (!on()) return;
        String text = plain(e.message());
        if (text.isEmpty()) return;
        for (Pattern p : patterns) if (p.matcher(text).find()) { post("📢 " + esc(text)); return; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!chat || !on()) return;
        String msg = plain(e.message());
        if (msg.isEmpty()) return;
        post("**" + esc(e.getPlayer().getName()) + "**: " + esc(msg));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!joins || !on() || vanished(e.getPlayer())) return;
        post("➕ **" + esc(e.getPlayer().getName()) + "** joined (" + visible(null) + " online)");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!joins || !on() || vanished(e.getPlayer())) return;
        post("➖ **" + esc(e.getPlayer().getName()) + "** left (" + visible(e.getPlayer()) + " online)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!deaths || !on()) return;
        Component dm = e.deathMessage();
        String text = dm == null ? "" : plain(dm);
        if (text.isEmpty()) text = e.getEntity().getName() + " died";
        Player killer = e.getEntity().getKiller();
        post((killer != null && killer != e.getEntity() ? "⚔️ " : "💀 ") + esc(text));
        if (killer != null && killer != e.getEntity()) {
            Location l = e.getEntity().getLocation();
            log("⚔️ PvP: **" + esc(killer.getName()) + "** killed **" + esc(e.getEntity().getName()) + "** at " + where(l));
        }
    }

    // ---------------------------------------------------------------- logs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!logTnt || !logs.on() || !blocks.contains(e.getBlockPlaced().getType())) return;
        log("🧨 **" + esc(e.getPlayer().getName()) + "** placed " + pretty(e.getBlockPlaced().getType()) + " at " + where(e.getBlockPlaced().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceEntity(EntityPlaceEvent e) {
        if (!logTnt || !logs.on() || e.getPlayer() == null) return;
        Material as = switch (e.getEntity().getType().name()) {
            case "TNT_MINECART" -> Material.TNT_MINECART;
            case "END_CRYSTAL" -> Material.END_CRYSTAL;
            default -> null;
        };
        if (as == null || !blocks.contains(as)) return;
        log("🧨 **" + esc(e.getPlayer().getName()) + "** placed " + pretty(as) + " at " + where(e.getEntity().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent e) {
        if (!logTnt || !logs.on() || !blocks.contains(Material.TNT)) return;
        Entity by = e.getPrimingEntity();
        if (by instanceof Projectile pr && pr.getShooter() instanceof Player shooter) by = shooter;
        if (!(by instanceof Player p)) return;
        log("🔥 **" + esc(p.getName()) + "** lit TNT at " + where(e.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!logs.on()) return;
        String line = e.getMessage().startsWith("/") ? e.getMessage().substring(1) : e.getMessage();
        String label = label(line);
        if (label.isEmpty() || ignored.contains(label)) return;
        Player p = e.getPlayer();
        if (whispers.contains(label)) {
            if (logWhispers) log("💬 **" + esc(p.getName()) + "** whispered: `" + code(line) + "`");
            return;
        }
        if (logAll || (logAdmin && admin(p))) log("🛠️ **" + esc(p.getName()) + "** ran `/" + code(line) + "`");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsole(ServerCommandEvent e) {
        if (!logConsole || !logs.on()) return;
        CommandSender s = e.getSender();
        // the console and RCON (a panel, a remote tool); never command blocks / command minecarts (they'd flood it)
        if (s instanceof org.bukkit.command.BlockCommandSender || s instanceof org.bukkit.entity.minecart.CommandMinecart) return;
        if (!(s instanceof ConsoleCommandSender) && !(s instanceof org.bukkit.command.RemoteConsoleCommandSender)) return;
        String line = e.getCommand().startsWith("/") ? e.getCommand().substring(1) : e.getCommand();
        String label = label(line);
        if (label.isEmpty() || ignored.contains(label)) return;
        log("🖥️ " + (s instanceof org.bukkit.command.RemoteConsoleCommandSender ? "RCON" : "CONSOLE") + " ran `/" + code(line) + "`");
    }

    /** Vanished staff (FaultlineItems /vanish, EssentialsX, SuperVanish all set this metadata). */
    static boolean vanished(Player p) {
        for (var v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        return false;
    }

    /**
     * A player report: its own message straight to the logs channel (not batched), pinging discord.logs.report-role
     * when set (the only mention this bridge ever allows). Returns false when the logs channel is off.
     */
    boolean report(String text) {
        if (!logs.on()) return false;
        String role = index.getConfig().getString("discord.logs.report-role", "");
        boolean ping = role != null && role.matches("\\d{5,25}");
        String content = (ping ? "<@&" + role + "> " : "") + text + " " + stamp();
        String allowed = ping ? "{\"roles\":[\"" + role + "\"]}" : "{\"parse\":[]}";
        String url = logs.url;
        Bukkit.getScheduler().runTaskAsynchronously(index, () -> sendRaw(url, content.length() > 1900 ? content.substring(0, 1900) : content, allowed));
        return true;
    }

    /** Where /suggest posts: its own webhook, else the public channel (null = nowhere). */
    String suggestTarget() {
        if (!enabled) return null;
        String u = suggestUrl;
        if (u != null && (u.startsWith("https://") || u.startsWith("http://127.0.0.1"))) return u;
        return pub.on() ? pub.url : null;
    }

    /** A /suggest as its own embed (number, text, who, their head), so people can react to vote on it. */
    boolean suggest(int n, String player, String text) {
        String url = suggestTarget();
        if (url == null) return false;
        String head = "https://mc-heads.net/avatar/" + player.replaceAll("[^A-Za-z0-9_]", "") + "/64";
        String body = "{\"username\":" + json(username) + ",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"title\":" + json("💡 Suggestion #" + n) + ",\"description\":" + json(esc(text))
                + ",\"color\":16761095,\"author\":{\"name\":" + json(player) + ",\"icon_url\":" + json(head) + "}"
                + ",\"footer\":{\"text\":\"/suggest in game · react to vote\"}"
                + ",\"timestamp\":" + json(java.time.Instant.now().toString()) + "}]}";
        Bukkit.getScheduler().runTaskAsynchronously(index, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 300) index.getLogger().warning("Discord suggestion answered " + res.statusCode() + ": " + res.body());
            } catch (Exception ex) { index.getLogger().warning("Discord suggestion failed: " + ex.getMessage()); }
        });
        return true;
    }

    private void sendRaw(String url, String content, String allowedMentions) {
        String body = "{\"username\":" + json(username) + ",\"content\":" + json(content) + ",\"allowed_mentions\":" + allowedMentions + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) index.getLogger().warning("Discord report answered " + res.statusCode());
        } catch (Exception ex) { index.getLogger().warning("Discord report failed: " + ex.getMessage()); }
    }

    /** Players online that everyone can see (vanished staff don't count), leaving one out. */
    static int visible(Player leaving) {
        int n = 0;
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(leaving) && !vanished(o)) n++;
        return n;
    }

    static boolean admin(Player p) { return p.isOp() || p.hasPermission("faultlineindex.logged"); }

    /** The command's name, lowercase, without a namespace ("minecraft:msg" = "msg"). */
    static String label(String line) {
        String l = line.trim();
        int sp = l.indexOf(' ');
        if (sp >= 0) l = l.substring(0, sp);
        int colon = l.indexOf(':');
        if (colon >= 0) l = l.substring(colon + 1);
        return l.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- server

    /** Posted straight away (the scheduler won't run again during shutdown). */
    void serverStopping() {
        String text = "🔴 The server is stopping.";
        for (Channel c : List.of(pub, logs)) {
            if (!c.on()) continue;
            List<String> rest = new ArrayList<>();
            synchronized (c.queue) { rest.addAll(c.queue); c.queue.clear(); }
            rest.add(c == logs ? text + " " + stamp() : text);
            String all = String.join("\n", rest);
            send(c.url, all.length() > 1900 ? all.substring(all.length() - 1900) : all, 3);
        }
    }

    void serverStarted() {
        post("🟢 The server is online.");
    }

    // ---------------------------------------------------------------- helpers

    static String plain(Component c) {
        return ChatColor.stripColor(PlainTextComponentSerializer.plainText().serialize(c)).trim();
    }

    /** Markdown-escaped: a player can't bold, strike, spoiler or quote their way around the format. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            if ("\\*_~`|>#[]".indexOf(c) >= 0) b.append('\\');
            if (c == '\n' || c == '\r') { b.append(' '); continue; }
            b.append(c);
        }
        return b.toString();
    }

    /** Inside a `code span`: backticks would end it. */
    static String code(String s) { return s.replace('`', '\'').replace('\n', ' '); }

    static String where(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.startsWith("tnt") ? "TNT" + s.substring(3) : s;
    }

    /** Sends one message; returns the HTTP code (0 = failed to send). */
    private int send(String url, String content, int timeoutSeconds) {
        String body = "{\"username\":" + json(username) + ",\"content\":" + json(content) + ",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) {
                long wait = 2000;
                try { wait = (long) (Double.parseDouble(res.headers().firstValue("Retry-After").orElse("2")) * 1000); } catch (RuntimeException ignored) { }
                retryAfter = Math.max(500, Math.min(60000, wait));
            }
            String b = res.body() == null ? "" : res.body();
            lastBody = b.isEmpty() ? "" : ": " + (b.length() > 200 ? b.substring(0, 200) : b);
            return res.statusCode();
        } catch (Exception ex) {
            if (System.currentTimeMillis() - pub.warned > 60000) {
                pub.warned = System.currentTimeMillis();
                index.getLogger().warning("Discord webhook failed: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
            return 0;
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
}
