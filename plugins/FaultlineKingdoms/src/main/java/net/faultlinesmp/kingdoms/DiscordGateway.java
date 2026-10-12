package net.faultlinesmp.kingdoms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DISCORD SLASH COMMANDS through the Kingdoms bot (config discord.slash-commands, on by default once the bot token and
 * guild id are set). /stats <player> in your Discord server answers with an embed: kingdom, online / last seen, play
 * time, kills, deaths, K/D, kill streak, Black Market tier, mob kills, Index progress, bosses defeated, achievements.
 * The bot keeps a GATEWAY connection (a websocket, no intents needed: slash commands always arrive) with heartbeats and
 * reconnects (backing off up to a minute; a bad token stops it). The command is registered for the guild on start (PUT,
 * shows up at once). Each command is acknowledged straight away (Discord wants an answer within 3 s), the stats are read
 * on the main thread, then the answer is edited in. Other plugins' numbers are read by reflection
 * (LimboBlackMarket's StatsManager/TierManager, FaultlineIndex.discordStats): none of them is a dependency.
 */
final class DiscordGateway {

    private final FaultlineKingdoms pl;
    private final DiscordLink link;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FaultlineKingdoms-Discord");
        t.setDaemon(true);
        return t;
    });
    private volatile WebSocket ws;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile Integer seq;
    private volatile boolean stopped, acked = true;
    private volatile String appId;
    private volatile int backoff = 5;
    /** Bumped once per connection that ends, so a close + an error (or a missed heartbeat) reconnect only once. */
    private final java.util.concurrent.atomic.AtomicInteger gen = new java.util.concurrent.atomic.AtomicInteger();
    volatile String status = "off";

    DiscordGateway(FaultlineKingdoms pl, DiscordLink link) {
        this.pl = pl;
        this.link = link;
    }

    boolean wanted() { return link.on() && pl.getConfig().getBoolean("discord.slash-commands", true); }

    String gatewayUrl() { return pl.getConfig().getString("discord.gateway-url", "wss://gateway.discord.gg/?v=10&encoding=json"); }

    void start() {
        stopped = false;
        if (!wanted()) { status = "off"; return; }
        timer.execute(() -> {
            try { register(); } catch (Exception ex) { pl.getLogger().warning("Couldn't register the Discord /stats command: " + ex.getMessage()); }
            connect();
        });
    }

    void stop() {
        stopped = true;
        status = "off";
        ScheduledFuture<?> h = heartbeat;
        if (h != null) h.cancel(false);
        WebSocket w = ws;
        if (w != null) try { w.sendClose(1000, "bye"); } catch (RuntimeException ignored) { }
        timer.shutdownNow();
    }

    // ---------------------------------------------------------------- the slash command

    void register() throws Exception {
        DiscordLink.Reply me = link.call("GET", "/applications/@me", null);
        if (me.code() != 200) throw new IllegalStateException("GET /applications/@me answered " + me.code());
        appId = JsonParser.parseString(me.body()).getAsJsonObject().get("id").getAsString();
        String cmds = "[{\"name\":\"stats\",\"type\":1,\"description\":\"A player's Faultline SMP stats\",\"options\":[{\"type\":3,"
                + "\"name\":\"player\",\"description\":\"Their Minecraft name\",\"required\":true}]}]";
        DiscordLink.Reply r = link.call("PUT", "/applications/" + appId + "/guilds/" + link.guild() + "/commands", cmds);
        if (r.code() != 200 && r.code() != 201) throw new IllegalStateException("registering /stats answered " + r.code() + ": " + r.body());
        pl.getLogger().info("Discord: /stats is registered in your server.");
    }

    // ---------------------------------------------------------------- the gateway

    private void connect() {
        if (stopped || !wanted()) return;
        status = "connecting";
        int my = gen.get();
        http.newWebSocketBuilder().buildAsync(URI.create(gatewayUrl()), new Listener(my)).whenComplete((w, err) -> {
            if (err != null) { status = "can't connect"; ended(my, "couldn't connect: " + err.getMessage()); }
            else ws = w;
        });
    }

    /** This connection is over: reconnect, once. */
    private void ended(int my, String why) {
        if (gen.compareAndSet(my, my + 1)) retry(why);
    }

    private void retry(String why) {
        ScheduledFuture<?> h = heartbeat;
        if (h != null) h.cancel(false);
        if (stopped) return;
        int wait = backoff;
        backoff = Math.min(60, backoff * 2);
        pl.getLogger().warning("Discord gateway " + why + "; trying again in " + wait + " s.");
        try { timer.schedule(this::connect, wait, TimeUnit.SECONDS); } catch (RuntimeException ignored) { }
    }

    private void send(String json) {
        WebSocket w = ws;
        if (w != null) try { w.sendText(json, true); } catch (RuntimeException ignored) { }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final int my;

        Listener(int my) { this.my = my; }

        @Override public void onOpen(WebSocket w) { ws = w; w.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try { handle(w, JsonParser.parseString(msg).getAsJsonObject(), my); }
                catch (RuntimeException ex) { pl.getLogger().warning("Discord gateway: bad message: " + ex.getMessage()); }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            if (code == 4004) { // authentication failed: retrying won't help
                gen.incrementAndGet();
                status = "bad token";
                pl.getLogger().warning("Discord gateway: the bot token was refused (4004). Check discord.bot-token, then /kingdoms reload.");
                ScheduledFuture<?> h = heartbeat; if (h != null) h.cancel(false);
                return null;
            }
            status = "reconnecting";
            ended(my, "closed (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) { status = "reconnecting"; ended(my, "error: " + error.getMessage()); }
    }

    private void handle(WebSocket w, JsonObject m, int my) {
        int op = m.get("op").getAsInt();
        if (m.has("s") && !m.get("s").isJsonNull()) seq = m.get("s").getAsInt();
        switch (op) {
            case 10 -> { // HELLO: start the heartbeat, then identify
                long every = m.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                ScheduledFuture<?> old = heartbeat;
                if (old != null) old.cancel(false);
                acked = true;
                heartbeat = timer.scheduleAtFixedRate(() -> {
                    if (!acked) { // the last one was never acknowledged: the connection is a zombie
                        try { w.abort(); } catch (RuntimeException ignored) { }
                        status = "reconnecting";
                        ended(my, "missed a heartbeat");
                        return;
                    }
                    acked = false;
                    send("{\"op\":1,\"d\":" + (seq == null ? "null" : seq) + "}");
                }, (long) (every * Math.random()), every, TimeUnit.MILLISECONDS);
                send("{\"op\":2,\"d\":{\"token\":" + new com.google.gson.JsonPrimitive(link.token()) + ",\"intents\":0,"
                        + "\"properties\":{\"os\":\"linux\",\"browser\":\"faultline\",\"device\":\"faultline\"}}}");
            }
            case 11 -> acked = true;
            case 1 -> send("{\"op\":1,\"d\":" + (seq == null ? "null" : seq) + "}");
            case 7, 9 -> { // RECONNECT / INVALID SESSION: a fresh connection
                try { w.sendClose(4000, "reconnect"); } catch (RuntimeException ignored) { }
            }
            case 0 -> {
                String t = m.get("t").getAsString();
                if (t.equals("READY")) { status = "online"; backoff = 5; pl.getLogger().info("Discord gateway connected: /stats is live."); }
                else if (t.equals("INTERACTION_CREATE")) interaction(m.getAsJsonObject("d"));
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- answering /stats

    private void interaction(JsonObject d) {
        if (d.get("type").getAsInt() != 2) return; // application commands only
        JsonObject data = d.getAsJsonObject("data");
        if (!"stats".equals(data.get("name").getAsString())) return;
        String id = d.get("id").getAsString(), token = d.get("token").getAsString();
        String app = d.has("application_id") ? d.get("application_id").getAsString() : appId;
        String name = "";
        if (data.has("options")) for (JsonElement o : data.getAsJsonArray("options"))
            if ("player".equals(o.getAsJsonObject().get("name").getAsString())) name = o.getAsJsonObject().get("value").getAsString().trim();
        String who = name;
        try { link.call("POST", "/interactions/" + id + "/" + token + "/callback", "{\"type\":5}"); } // "thinking..."
        catch (Exception ex) { pl.getLogger().warning("Discord /stats: couldn't acknowledge: " + ex.getMessage()); return; }
        CompletableFuture<JsonObject> embed = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(pl, () -> {
            try { embed.complete(statsEmbed(who)); } catch (Throwable t) { embed.completeExceptionally(t); }
        });
        embed.orTimeout(10, TimeUnit.SECONDS).whenComplete((e, err) -> {
            JsonObject body = new JsonObject();
            if (err != null) body.addProperty("content", "Couldn't read the stats right now.");
            else if (e == null) body.addProperty("content", "Nobody called **" + who.replace("*", "") + "** has played on the server.");
            else { JsonArray a = new JsonArray(); a.add(e); body.add("embeds", a); }
            JsonObject am = new JsonObject(); am.add("parse", new JsonArray());
            body.add("allowed_mentions", am);
            try { link.call("PATCH", "/webhooks/" + app + "/" + token + "/messages/@original", body.toString()); }
            catch (Exception ex) { pl.getLogger().warning("Discord /stats: couldn't answer: " + ex.getMessage()); }
        });
    }

    /** The embed for a player (main thread), or null if they never played here. */
    JsonObject statsEmbed(String name) {
        if (name.isEmpty() || name.length() > 32) return null;
        OfflinePlayer op = Bukkit.getPlayerExact(name);
        if (op == null) op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null || (!op.isOnline() && !op.hasPlayedBefore())) return null;
        String real = op.getName() == null ? name : op.getName();
        Map<String, String> f = new LinkedHashMap<>();
        int k = FaultlineKingdoms.kingdomOf(op.getUniqueId());
        f.put("Kingdom", pl.name(k));
        Player online = op.getPlayer();
        f.put("Status", online != null && !online.hasMetadata("vanished") ? "🟢 Online"
                : op.getLastSeen() > 0 ? "Last seen <t:" + op.getLastSeen() / 1000 + ":R>" : "Offline");
        long ticks = stat(op, Statistic.PLAY_ONE_MINUTE);
        if (ticks >= 0) f.put("Play time", hours(ticks));
        // Black Market: kills / deaths / streak / tier (its own counts: player kills on the server)
        int kills = -1, deaths = -1, streak = -1; String tier = null;
        try {
            org.bukkit.plugin.Plugin bm = Bukkit.getPluginManager().getPlugin("LimboBlackMarket");
            if (bm != null && bm.isEnabled()) {
                Object sm = bm.getClass().getMethod("getStatsManager").invoke(bm);
                kills = (int) sm.getClass().getMethod("getKills", java.util.UUID.class).invoke(sm, op.getUniqueId());
                deaths = (int) sm.getClass().getMethod("getDeaths", java.util.UUID.class).invoke(sm, op.getUniqueId());
                streak = (int) sm.getClass().getMethod("getStreak", java.util.UUID.class).invoke(sm, op.getUniqueId());
                Object tm = bm.getClass().getMethod("getTierManager").invoke(bm);
                tier = String.valueOf(tm.getClass().getMethod("getTier", java.util.UUID.class).invoke(tm, op.getUniqueId()));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        if (kills < 0) { long v = stat(op, Statistic.PLAYER_KILLS); kills = (int) Math.max(0, v); }
        if (deaths < 0) { long v = stat(op, Statistic.DEATHS); deaths = (int) Math.max(0, v); }
        f.put("Kills", String.valueOf(kills));
        f.put("Deaths", String.valueOf(deaths));
        f.put("K/D", deaths == 0 ? String.valueOf(kills) : String.format(java.util.Locale.ROOT, "%.2f", kills / (double) deaths));
        if (streak > 0) f.put("Kill streak", String.valueOf(streak));
        if (tier != null && !tier.equals("null")) f.put("Tier", tier);
        long mobs = stat(op, Statistic.MOB_KILLS);
        if (mobs >= 0) f.put("Mob kills", String.valueOf(mobs));
        try {
            org.bukkit.plugin.Plugin ix = Bukkit.getPluginManager().getPlugin("FaultlineIndex");
            if (ix != null && ix.isEnabled()) {
                @SuppressWarnings("unchecked") Map<String, String> more = (Map<String, String>) ix.getClass().getMethod("discordStats", java.util.UUID.class).invoke(null, op.getUniqueId());
                f.putAll(more);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        JsonObject e = new JsonObject();
        e.addProperty("title", real);
        e.addProperty("color", switch (k) { case 1 -> 0xE53935; case 2 -> 0x1E88E5; case 3 -> 0x43A047; default -> 0x9E9E9E; });
        JsonObject th = new JsonObject();
        th.addProperty("url", "https://mc-heads.net/avatar/" + real.replaceAll("[^A-Za-z0-9_]", "") + "/100");
        e.add("thumbnail", th);
        JsonArray fields = new JsonArray();
        for (var en : f.entrySet()) {
            JsonObject fo = new JsonObject();
            fo.addProperty("name", en.getKey());
            fo.addProperty("value", en.getValue());
            fo.addProperty("inline", true);
            fields.add(fo);
        }
        e.add("fields", fields);
        JsonObject foot = new JsonObject();
        foot.addProperty("text", "Faultline SMP");
        e.add("footer", foot);
        return e;
    }

    private static long stat(OfflinePlayer p, Statistic s) {
        try { return p.getStatistic(s); } catch (RuntimeException | LinkageError ex) { return -1; }
    }

    private static String hours(long ticks) {
        long min = ticks / 20 / 60;
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }
}
