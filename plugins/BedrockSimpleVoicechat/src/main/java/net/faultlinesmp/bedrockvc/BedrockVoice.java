package net.faultlinesmp.bedrockvc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * BedrockSimpleVoicechat: Simple Voice Chat for players who can't install the mod: Bedrock on PC, phones, Xbox,
 * PlayStation and the Switch, and Java players without the mod. They open a web page (scan the QR map with a phone, or
 * type the link + a code) and that page is their microphone and headset: their voice goes into Simple Voice Chat as
 * their character, and they hear everyone near them in 3D.
 */
public final class BedrockVoice extends JavaPlugin implements Listener, TabCompleter {
    record Pos(UUID world, double x, double y, double z, float yaw) { }

    private volatile VoicechatServerApi api;
    private WebServer web;
    private volatile String publicUrl = "";
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Pos> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Pos> entities = new ConcurrentHashMap<>();
    private final Set<UUID> wantedEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final SecureRandom rnd = new SecureRandom();
    private ScheduledExecutorService mixer;
    private NamespacedKey mapKey;
    private MapView qrView;
    private File devicesFile;
    private YamlConfiguration devices;
    private volatile boolean debug;
    private String webStatus = "starting";

    // pairing: one live link per player
    private record Link(UUID player, String token, String code, long until) { }
    private final Map<UUID, Link> links = new ConcurrentHashMap<>();
    private final Map<String, int[]> codeTries = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeDefaults();
        debug = getConfig().getBoolean("debug", false);
        mapKey = new NamespacedKey(this, "qr_map");
        devicesFile = new File(getDataFolder(), "devices.yml");
        devices = devicesFile.isFile() ? YamlConfiguration.loadConfiguration(devicesFile) : new YamlConfiguration();

        BukkitVoicechatService svc = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (svc == null) {
            getLogger().severe("Simple Voice Chat (voicechat-bukkit) isn't installed or didn't start: BedrockSimpleVoicechat can't work without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        svc.registerPlugin(new VoiceBridge(this));

        getServer().getPluginManager().registerEvents(this, this);
        org.bukkit.command.PluginCommand cmd = getCommand("vc");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }

        mixer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "BedrockVoice-mixer"); t.setDaemon(true); t.setPriority(Thread.MAX_PRIORITY - 1); return t; });
        mixer.scheduleAtFixedRate(this::mixAll, 20, 20, TimeUnit.MILLISECONDS);

        Bukkit.getScheduler().runTaskTimer(this, this::trackPositions, 1, 2);
        Bukkit.getScheduler().runTaskTimer(this, this::uiTick, 10, 5);
        Bukkit.getScheduler().runTaskTimer(this, this::slowTick, 20, 20);
        Bukkit.getScheduler().runTask(this, this::setupMap);
        startWeb();
    }

    @Override
    public void onDisable() {
        if (mixer != null) mixer.shutdownNow();
        for (Session s : sessions.values()) {
            WebServer.Ws w = s.ws;
            if (w != null) { w.text(json("t", "bye", "msg", "The server is restarting. This page reconnects by itself.")); }
            s.detach(api);
        }
        if (web != null) web.stop();
        saveDevices();
    }

    /** New keys from the jar's config.yml show up in an older server config (without touching what's set). */
    private void mergeDefaults() {
        try (var in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            getConfig().setDefaults(def);
            getConfig().options().copyDefaults(true);
            saveConfig();
        } catch (Exception ignored) { }
    }

    void setApi(VoicechatServerApi a) {
        api = a;
        getLogger().info("Hooked into Simple Voice Chat.");
        Bukkit.getScheduler().runTask(this, () -> { for (Session s : sessions.values()) tryAttach(s); });
    }

    void voiceStopped() {
        VoicechatServerApi a = api;
        for (Session s : sessions.values()) s.detach(a);
        api = null;
    }

    VoicechatServerApi api() { return api; }
    void debug(String s) { if (debug) getLogger().info("[debug] " + s); }
    boolean trustProxy() { return getConfig().getBoolean("web.trust-proxy", false); }
    double falloff() { return Math.max(0.2, getConfig().getDouble("audio.falloff", 1.0)); }
    double stereo() { return Math.max(0, Math.min(1, getConfig().getDouble("audio.stereo", 0.85))); }
    int maxPending() { return 24000; }

    // ------------------------------------------------------------------ the web server

    private void startWeb() {
        int port = getConfig().getInt("web.port", 24455);
        String bind = getConfig().getString("web.bind", "0.0.0.0");
        String mode = getConfig().getString("web.tls.mode", "auto");
        Thread t = new Thread(() -> {
            try {
                String host = getConfig().getString("web.public-host", "").trim();
                String fixedUrl = getConfig().getString("web.public-url", "").trim();
                if (host.isEmpty() && fixedUrl.isEmpty()) {
                    String ip = Bukkit.getIp();
                    if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0")) host = ip;
                    else if (getConfig().getBoolean("web.detect-public-ip", true)) host = detectIp();
                }
                if (host.isEmpty()) host = "localhost";
                javax.net.ssl.SSLContext tls = null;
                // behind a tunnel / reverse proxy (public-url set) the proxy does https: plain http here unless a cert is given
                String useMode = fixedUrl.isEmpty() ? mode : (mode.equalsIgnoreCase("pem") || mode.equalsIgnoreCase("pkcs12") ? mode : "off");
                try { tls = Tls.load(this, useMode, host); }
                catch (Exception e) { getLogger().warning("HTTPS couldn't start (" + e.getMessage() + "): falling back to plain http. Phones won't allow the mic without https."); }
                web = new WebServer(this, bind, port, tls, this::openSocket);
                web.start();
                if (!fixedUrl.isEmpty()) publicUrl = fixedUrl.replaceAll("/+$", "");
                else publicUrl = (tls != null ? "https://" : "http://") + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
                webStatus = "running on port " + port + (tls != null ? " (https)" : " (http)");
                getLogger().info("Voice page: " + publicUrl + "  (open TCP port " + port + " in your firewall)");
                if (tls == null && fixedUrl.isEmpty())
                    getLogger().warning("The voice page is plain http: browsers only give the mic to https pages. Set web.tls.mode to auto or pem.");
            } catch (Exception e) {
                webStatus = "FAILED: " + e.getMessage();
                getLogger().severe("The voice web server couldn't start on port " + port + ": " + e.getMessage());
            }
        }, "BedrockVoice-start");
        t.setDaemon(true);
        t.start();
    }

    private String detectIp() {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (String u : new String[]{"https://api.ipify.org", "https://checkip.amazonaws.com"}) {
            try {
                String ip = c.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(6)).build(), HttpResponse.BodyHandlers.ofString()).body().trim();
                if (ip.matches("[0-9a-fA-F.:]{3,45}")) return ip;
            } catch (Exception ignored) { }
        }
        return "";
    }

    String fillPage(String html) {
        return html.replace("{{SERVER}}", esc(getConfig().getString("server-name", "Minecraft")))
            .replace("{{ACCENT}}", esc(getConfig().getString("web.accent-color", "#4ade80")));
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    // ------------------------------------------------------------------ a page connects

    private WebServer.WsListener openSocket(WebServer.Ws ws, String ip) {
        return new WebServer.WsListener() {
            volatile Session s;
            final long opened = System.currentTimeMillis();

            @Override public void onText(String msg) {
                JsonObject o;
                try { o = JsonParser.parseString(msg).getAsJsonObject(); } catch (Exception e) { return; }
                String t = str(o, "t");
                if (s == null) {
                    if (System.currentTimeMillis() - opened > 60000) { ws.close(); return; }
                    if (t.equals("ping")) { ws.text(json("t", "pong", "n", o.get("n"))); return; }
                    if (!t.equals("auth")) return;
                    Session got = authenticate(ws, ip, o);
                    if (got != null) s = got;
                    return;
                }
                handle(s, ws, t, o);
            }

            @Override public void onBinary(byte[] data) {
                Session x = s;
                if (x == null || x.ws != ws || data.length < 2) return;
                if (data[0] == 1) x.mic(data);
            }

            @Override public void onClosed() {
                Session x = s;
                if (x == null || x.ws != ws) return;
                x.ws = null;
                Bukkit.getScheduler().runTask(BedrockVoice.this, () -> {
                    if (x.ws != null) return;
                    x.detach(api);
                    Player p = Bukkit.getPlayer(x.id);
                    if (p != null && getConfig().getBoolean("ingame.notify", true)) p.sendMessage(msg("disconnected"));
                });
            }
        };
    }

    private Session authenticate(WebServer.Ws ws, String ip, JsonObject o) {
        UUID who = null;
        boolean fresh = false;
        String token = str(o, "token"), code = str(o, "code").replaceAll("[^0-9]", ""), key = str(o, "device");
        if (!token.isEmpty()) {
            Link l = links.values().stream().filter(x -> x.token.equals(token)).findFirst().orElse(null);
            if (l != null && l.until > System.currentTimeMillis()) { who = l.player; fresh = true; links.remove(l.player); }
            else { ws.text(json("t", "authfail", "msg", "That link has run out. Type /vc in game for a new one.")); }
        } else if (!code.isEmpty()) {
            if (!codeAllowed(ip)) { ws.text(json("t", "authfail", "msg", "Too many tries. Wait a few minutes.")); return null; }
            Link l = links.values().stream().filter(x -> x.code.equals(code)).findFirst().orElse(null);
            if (l != null && l.until > System.currentTimeMillis()) { who = l.player; fresh = true; links.remove(l.player); }
            else ws.text(json("t", "authfail", "msg", "Wrong or expired code. Type /vc in game to get one."));
        } else if (!key.isEmpty()) {
            who = deviceOwner(key);
            if (who == null) ws.text(json("t", "authfail", "forget", true, "msg", "This device isn't linked any more. Type /vc in game."));
        }
        if (who == null) return null;
        UUID id = who;
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse(names.getOrDefault(id, "player"));
        Session s = sessions.computeIfAbsent(id, k -> new Session(this, k, name));
        WebServer.Ws old = s.ws;
        if (old != null && old != ws) { old.text(json("t", "replaced", "msg", "You opened voice chat somewhere else.")); old.close(); }
        s.ws = ws;
        s.name = name;
        int rate = o.has("rate") ? o.get("rate").getAsInt() : getConfig().getInt("audio.sample-rate", 24000);
        s.rate = Audio.validRate(Math.min(rate, getConfig().getInt("audio.max-sample-rate", 48000)));
        String codec = getConfig().getString("audio.codec", "ulaw");
        if (codec.equalsIgnoreCase("auto")) codec = str(o, "codec").isEmpty() ? "ulaw" : str(o, "codec");
        s.ulaw = !codec.equalsIgnoreCase("pcm16");
        s.device = str(o, "ua");
        if (o.has("vols") && o.get("vols").isJsonObject()) {
            for (var e : o.getAsJsonObject("vols").entrySet()) try { s.vols.put(UUID.fromString(e.getKey()), Math.max(0, Math.min(2, e.getValue().getAsFloat()))); } catch (Exception ignored) { }
        }
        if (o.has("muted")) s.muted = o.get("muted").getAsBoolean();
        if (o.has("deafened")) s.deafened = o.get("deafened").getAsBoolean();
        JsonObject w = new JsonObject();
        w.addProperty("t", "welcome");
        w.addProperty("name", name);
        w.addProperty("uuid", id.toString());
        w.addProperty("server", getConfig().getString("server-name", "Minecraft"));
        w.addProperty("rate", s.rate);
        w.addProperty("codec", s.ulaw ? "ulaw" : "pcm16");
        w.addProperty("muted", s.muted);
        w.addProperty("deafened", s.deafened);
        w.addProperty("ptt", getConfig().getBoolean("web.default-push-to-talk", false));
        if (fresh && getConfig().getBoolean("remember-devices", true)) w.addProperty("deviceKey", newDevice(id, s.device));
        ws.text(w.toString());
        Bukkit.getScheduler().runTask(this, () -> {
            tryAttach(s);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (getConfig().getBoolean("ingame.notify", true)) p.sendMessage(msg("connected"));
                if (getConfig().getBoolean("qr.remove-on-connect", true)) takeMaps(p);
            }
        });
        getLogger().info(name + " connected voice from " + (s.device.isEmpty() ? "a browser" : s.device));
        return s;
    }

    private boolean codeAllowed(String ip) {
        long now = System.currentTimeMillis();
        if (codeTries.size() > 5000) codeTries.clear();
        int[] t = codeTries.computeIfAbsent(ip, k -> new int[]{(int) (now / 600000), 0});
        synchronized (t) {
            if (t[0] != (int) (now / 600000)) { t[0] = (int) (now / 600000); t[1] = 0; }
            return ++t[1] <= 10;
        }
    }

    private void handle(Session s, WebServer.Ws ws, String t, JsonObject o) {
        switch (t) {
            case "ping" -> ws.text(json("t", "pong", "n", o.get("n")));
            case "end" -> s.micEnd();
            case "mute" -> { s.muted = o.get("on").getAsBoolean(); if (s.muted) s.micEnd(); pushStatus(s); }
            case "deafen" -> { boolean on = o.get("on").getAsBoolean(); Bukkit.getScheduler().runTask(this, () -> { s.setDeafened(on); pushStatus(s); }); }
            case "master" -> s.master = Math.max(0, Math.min(2f, o.get("v").getAsFloat()));
            case "vol" -> { try { s.vols.put(UUID.fromString(str(o, "id")), Math.max(0, Math.min(2f, o.get("v").getAsFloat()))); } catch (Exception ignored) { } }
            case "group" -> Bukkit.getScheduler().runTask(this, () -> group(s, o));
            case "forget" -> { forgetDevice(s.id, str(o, "device")); }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ attaching to Simple Voice Chat

    private void tryAttach(Session s) {
        if (!s.live()) return;
        Player p = Bukkit.getPlayer(s.id);
        if (p == null) { pushStatus(s); return; }
        if (!p.hasPermission("bedrockvc.use")) { send(s, json("t", "status", "online", true, "attached", false, "msg", "You don't have permission to use voice chat.")); return; }
        if (getConfig().getBoolean("only-bedrock", false) && !Edition.bedrock(p)) { send(s, json("t", "status", "online", true, "attached", false, "msg", "This page is for Bedrock players. Install the Simple Voice Chat mod.")); return; }
        String why = s.attach(api);
        if (why != null) debug("attach " + s.name + ": " + why);
        pushStatus(s, why);
    }

    private void pushStatus(Session s) { pushStatus(s, null); }

    private void pushStatus(Session s, String why) {
        Player p = Bukkit.getPlayer(s.id);
        JsonObject o = new JsonObject();
        o.addProperty("t", "status");
        o.addProperty("online", p != null);
        o.addProperty("attached", s.attached());
        o.addProperty("muted", s.muted);
        o.addProperty("deafened", s.deafened);
        if (p == null) o.addProperty("msg", "Join the server to talk. This page connects by itself when you do.");
        else if (why != null) o.addProperty("msg", why);
        else if (!s.attached()) o.addProperty("msg", "Connecting to the voice server...");
        send(s, o.toString());
    }

    private void send(Session s, String text) { WebServer.Ws w = s.ws; if (w != null) w.text(text); }

    private void mixAll() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            try { s.mixTick(now); } catch (Throwable t) { debug("mix " + s.name + ": " + t); }
        }
    }

    // ------------------------------------------------------------------ positions (main thread)

    private void trackPositions() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location e = p.getEyeLocation();
            positions.put(p.getUniqueId(), new Pos(p.getWorld().getUID(), e.getX(), e.getY(), e.getZ(), e.getYaw()));
            names.put(p.getUniqueId(), p.getName());
        }
        if (!wantedEntities.isEmpty() || !entities.isEmpty()) {
            Set<UUID> ask = new HashSet<>(wantedEntities);
            wantedEntities.clear();
            ask.addAll(entities.keySet());
            int n = 0;
            for (UUID id : ask) {
                if (n++ > 200) break;
                Entity en = Bukkit.getEntity(id);
                if (en == null || !en.isValid()) { entities.remove(id); continue; }
                Location l = en.getLocation();
                entities.put(id, new Pos(en.getWorld().getUID(), l.getX(), l.getY() + en.getHeight() * 0.85, l.getZ(), l.getYaw()));
            }
        }
    }

    Pos pos(UUID id) { return positions.get(id); }

    Pos entityPos(UUID id) {
        Pos p = entities.get(id);
        if (p == null && entities.size() < 500) wantedEntities.add(id);
        return p;
    }

    // ------------------------------------------------------------------ page + in-game updates

    private final Map<UUID, String> lastTalk = new HashMap<>();
    private final Map<UUID, Long> lastBar = new HashMap<>();

    private void uiTick() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (!s.live() || !s.attached()) continue;
            List<String> ids = new ArrayList<>();
            List<String> who = new ArrayList<>();
            for (var e : s.talking.entrySet()) {
                if (now - e.getValue() < 350) { ids.add(e.getKey().toString()); who.add(names.getOrDefault(e.getKey(), "?")); }
                else if (now - e.getValue() > 5000) s.talking.remove(e.getKey());
            }
            Collections.sort(ids);
            String key = String.join(",", ids) + (now - s.selfTalkAt < 350 ? "+me" : "");
            if (!key.equals(lastTalk.get(s.id))) {
                lastTalk.put(s.id, key);
                JsonObject o = new JsonObject();
                o.addProperty("t", "talk");
                JsonArray a = new JsonArray();
                ids.forEach(a::add);
                o.add("ids", a);
                o.addProperty("me", now - s.selfTalkAt < 350);
                send(s, o.toString());
            }
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && getConfig().getBoolean("ingame.action-bar", true)) {
                String bar = null;
                if (!who.isEmpty()) bar = ChatColor.GREEN + "♪ " + ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, who);
                if (s.deafened) bar = ChatColor.RED + "Voice: deafened" + (bar == null ? "" : ChatColor.DARK_GRAY + "  |  " + bar);
                else if (s.muted) bar = ChatColor.RED + "Voice: muted" + (bar == null ? "" : ChatColor.DARK_GRAY + "  |  " + bar);
                Long lb = lastBar.get(s.id);
                if (bar != null && (lb == null || now - lb > 1500 || !key.equals(lastTalk.get(s.id)))) {
                    p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar));
                    lastBar.put(s.id, now);
                }
            }
        }
    }

    private int slow;

    private void slowTick() {
        slow++;
        VoicechatServerApi a = api;
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (!s.live()) {
                if (s.attached()) s.detach(a);
                if (s.ws == null && System.currentTimeMillis() - s.lastMicFrame > 300000) it.remove();
                continue;
            }
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && !s.attached()) tryAttach(s);
            if (p == null && s.attached()) { s.detach(a); pushStatus(s); }
            if (p != null && s.attached()) sendNearby(s, p, slow % 3 == 0);
        }
    }

    private void sendNearby(Session s, Player me, boolean groups) {
        VoicechatServerApi a = api;
        double dist = a == null ? 48 : a.getVoiceChatDistance();
        JsonObject o = new JsonObject();
        o.addProperty("t", "nearby");
        o.addProperty("distance", dist);
        JsonArray arr = new JsonArray();
        VoicechatConnection mine = s.connection();
        Group myGroup = mine == null ? null : mine.getGroup();
        for (Player p : me.getWorld().getPlayers()) {
            if (p == me || !me.canSee(p)) continue;
            double d = p.getLocation().distance(me.getLocation());
            VoicechatConnection c = a == null ? null : a.getConnectionOf(p.getUniqueId());
            Group g = c == null ? null : c.getGroup();
            boolean sameGroup = myGroup != null && g != null && g.getId().equals(myGroup.getId());
            if (d > dist * 1.5 && !sameGroup) continue;
            JsonObject x = new JsonObject();
            x.addProperty("id", p.getUniqueId().toString());
            x.addProperty("name", p.getName());
            x.addProperty("d", Math.round(d));
            x.addProperty("bedrock", Edition.bedrock(p));
            Session other = sessions.get(p.getUniqueId());
            x.addProperty("voice", c != null && (c.isInstalled() && c.isConnected() || other != null && other.attached()));
            x.addProperty("group", sameGroup);
            arr.add(x);
            if (arr.size() >= 40) break;
        }
        o.add("list", arr);
        o.addProperty("group", myGroup == null ? "" : myGroup.getName());
        o.addProperty("groupId", myGroup == null ? "" : myGroup.getId().toString());
        o.addProperty("world", me.getWorld().getName());
        send(s, o.toString());
        if (groups && a != null) {
            JsonObject g = new JsonObject();
            g.addProperty("t", "groups");
            JsonArray ga = new JsonArray();
            Map<UUID, Integer> counts = new HashMap<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                VoicechatConnection c = a.getConnectionOf(p.getUniqueId());
                if (c != null && c.getGroup() != null) counts.merge(c.getGroup().getId(), 1, Integer::sum);
            }
            for (Group gr : a.getGroups()) {
                if (gr.isHidden()) continue;
                JsonObject x = new JsonObject();
                x.addProperty("id", gr.getId().toString());
                x.addProperty("name", gr.getName());
                x.addProperty("password", gr.hasPassword());
                x.addProperty("type", typeName(gr.getType()));
                x.addProperty("members", counts.getOrDefault(gr.getId(), 0));
                ga.add(x);
            }
            g.add("list", ga);
            send(s, g.toString());
        }
    }

    private static String typeName(Group.Type t) {
        return t == Group.Type.OPEN ? "open" : t == Group.Type.ISOLATED ? "isolated" : "normal";
    }

    private void group(Session s, JsonObject o) {
        VoicechatServerApi a = api;
        VoicechatConnection c = s.connection();
        if (a == null || c == null) return;
        String op = str(o, "op");
        try {
            switch (op) {
                case "leave" -> c.setGroup(null);
                case "join" -> {
                    Group g = a.getGroup(UUID.fromString(str(o, "id")));
                    if (g == null) { send(s, json("t", "toast", "msg", "That group is gone.")); return; }
                    if (g.hasPassword() && !Objects.equals(password(g), str(o, "pw"))) { send(s, json("t", "toast", "msg", "Wrong group password.")); return; }
                    c.setGroup(g);
                }
                case "create" -> {
                    String name = str(o, "name").trim();
                    if (name.isEmpty() || name.length() > 24) { send(s, json("t", "toast", "msg", "Give the group a name (up to 24 letters).")); return; }
                    String pw = str(o, "pw");
                    Group.Type type = switch (str(o, "type")) { case "open" -> Group.Type.OPEN; case "isolated" -> Group.Type.ISOLATED; default -> Group.Type.NORMAL; };
                    Group g = a.groupBuilder().setName(name).setPassword(pw.isEmpty() ? null : pw).setType(type).setPersistent(false).build();
                    c.setGroup(g);
                }
                default -> { return; }
            }
        } catch (Throwable t) {
            send(s, json("t", "toast", "msg", "Couldn't change group: " + t.getMessage()));
            return;
        }
        Player p = Bukkit.getPlayer(s.id);
        if (p != null) sendNearby(s, p, true);
    }

    /** The real password of a group (the API only says whether it has one): GroupImpl.group.getPassword(). */
    private static String password(Group g) {
        try {
            for (var f : g.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object inner = f.get(g);
                if (inner == null) continue;
                try { return (String) inner.getClass().getMethod("getPassword").invoke(inner); } catch (NoSuchMethodException ignored) { }
            }
        } catch (Throwable ignored) { }
        return "\u0000no";
    }

    // ------------------------------------------------------------------ players

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            Session s = sessions.get(p.getUniqueId());
            if (s != null && s.live()) { tryAttach(s); p.sendMessage(msg("connected")); return; }
            if (getConfig().getBoolean("ingame.join-hint", true) && p.hasPermission("bedrockvc.use") && needsBridge(p))
                p.sendMessage(msg("join-hint"));
        }, 60);
    }

    private boolean needsBridge(Player p) {
        if (Edition.bedrock(p)) return true;
        if (getConfig().getBoolean("only-bedrock", false)) return false;
        VoicechatServerApi a = api;
        VoicechatConnection c = a == null ? null : a.getConnectionOf(p.getUniqueId());
        return getConfig().getBoolean("ingame.join-hint-java", false) && (c == null || !c.isInstalled());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        positions.remove(id);
        Edition.forget(id);
        takeMaps(e.getPlayer());
        Session s = sessions.get(id);
        if (s != null) { s.detach(api); Bukkit.getScheduler().runTask(this, () -> pushStatus(s)); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isQrMap(e.getItemDrop().getItemStack())) e.getItemDrop().remove();
    }

    // ------------------------------------------------------------------ /vc

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("bedrockvc.admin");
        switch (sub) {
            case "", "link", "connect", "join" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("In game only."); return true; }
                if (!p.hasPermission("bedrockvc.use")) { p.sendMessage(msg("no-permission")); return true; }
                link(p);
            }
            case "qr", "map" -> {
                if (!(sender instanceof Player p)) return true;
                Link l = linkFor(p.getUniqueId());
                giveMap(p);
                p.sendMessage(ChatColor.GRAY + "Code: " + ChatColor.WHITE + ChatColor.BOLD + l.code);
            }
            case "mute", "unmute", "deafen", "undeafen" -> {
                if (!(sender instanceof Player p)) return true;
                Session s = sessions.get(p.getUniqueId());
                if (s == null || !s.live()) { p.sendMessage(msg("not-connected")); return true; }
                if (sub.equals("mute") || sub.equals("unmute")) { s.muted = sub.equals("mute"); if (s.muted) s.micEnd(); }
                else s.setDeafened(sub.equals("deafen"));
                pushStatus(s);
                p.sendMessage(ChatColor.GRAY + "Voice: " + (s.deafened ? ChatColor.RED + "deafened" : s.muted ? ChatColor.RED + "muted" : ChatColor.GREEN + "on"));
            }
            case "status" -> {
                if (args.length > 1 && admin) { statusOf(sender, args[1]); return true; }
                if (sender instanceof Player p) statusOf(sender, p.getName());
                if (admin) {
                    sender.sendMessage(ChatColor.GRAY + "Web: " + webStatus + " " + publicUrl + ChatColor.GRAY + " | voice API: " + (api != null ? "hooked" : "NOT hooked"));
                    long n = sessions.values().stream().filter(Session::attached).count();
                    sender.sendMessage(ChatColor.GRAY + "Players talking through the page: " + ChatColor.WHITE + n);
                }
            }
            case "list" -> {
                if (!admin) return true;
                sender.sendMessage(ChatColor.GOLD + "Voice page users:");
                for (Session s : sessions.values())
                    sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + s.name + ChatColor.GRAY + (s.attached() ? " talking" : s.live() ? " page open (not in game)" : " gone")
                        + (s.muted ? ", muted" : "") + (s.deafened ? ", deafened" : "") + " (" + s.rate / 1000 + " kHz " + (s.ulaw ? "mu-law" : "pcm") + ", out " + s.stats_framesOut + " in " + s.stats_framesIn + " drop " + s.stats_dropped + ") " + s.device);
            }
            case "kick" -> {
                if (!admin || args.length < 2) return true;
                Player t = Bukkit.getPlayerExact(args[1]);
                Session s = t == null ? null : sessions.get(t.getUniqueId());
                if (s == null) { sender.sendMessage(ChatColor.RED + "Not connected."); return true; }
                WebServer.Ws w = s.ws;
                if (w != null) { w.text(json("t", "bye", "msg", "Staff disconnected your voice chat.")); w.close(); }
                s.detach(api);
                sender.sendMessage(ChatColor.GREEN + "Disconnected " + s.name + "'s voice page.");
            }
            case "forget", "unlink" -> {
                if (!(sender instanceof Player p)) return true;
                devices.set("players." + p.getUniqueId(), null);
                saveDevices();
                Session s = sessions.get(p.getUniqueId());
                if (s != null && s.ws != null) { s.ws.text(json("t", "bye", "forget", true, "msg", "You unlinked this device. Type /vc in game to link again.")); s.ws.close(); }
                p.sendMessage(ChatColor.GREEN + "Every device you linked for voice chat is forgotten.");
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                mergeDefaults();
                debug = getConfig().getBoolean("debug", false);
                if (web != null) web.clearCache();
                sender.sendMessage(ChatColor.GREEN + "Reloaded. (Port / HTTPS changes need a restart.)");
            }
            case "help" -> help(sender, admin);
            default -> help(sender, admin);
        }
        return true;
    }

    private void help(CommandSender s, boolean admin) {
        s.sendMessage(ChatColor.GOLD + "Voice chat for Bedrock + no-mod players");
        s.sendMessage(ChatColor.YELLOW + "/vc" + ChatColor.GRAY + " - get your link, code and QR map");
        s.sendMessage(ChatColor.YELLOW + "/vc mute | unmute | deafen | undeafen");
        s.sendMessage(ChatColor.YELLOW + "/vc status" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/vc forget" + ChatColor.GRAY + " (unlink your devices)");
        if (admin) s.sendMessage(ChatColor.YELLOW + "/vc list | kick <player> | status [player] | reload");
    }

    private void statusOf(CommandSender to, String name) {
        Player p = Bukkit.getPlayerExact(name);
        Session s = p == null ? null : sessions.get(p.getUniqueId());
        if (s == null || !s.live()) { to.sendMessage(ChatColor.GRAY + name + ": " + ChatColor.RED + "voice page not open"); return; }
        to.sendMessage(ChatColor.GRAY + name + ": " + (s.attached() ? ChatColor.GREEN + "talking through the page" : ChatColor.YELLOW + "page open, not hooked in")
            + ChatColor.GRAY + (s.muted ? " (muted)" : "") + (s.deafened ? " (deafened)" : ""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> l = new ArrayList<>(List.of("qr", "mute", "unmute", "deafen", "undeafen", "status", "forget", "help"));
            if (sender.hasPermission("bedrockvc.admin")) l.addAll(List.of("list", "kick", "reload"));
            return l.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("kick") || args[0].equalsIgnoreCase("status")) && sender.hasPermission("bedrockvc.admin"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private Link linkFor(UUID id) {
        Link l = links.get(id);
        if (l != null && l.until > System.currentTimeMillis() + 60000) return l;
        byte[] b = new byte[15];
        rnd.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String code;
        Set<String> used = new HashSet<>();
        links.values().forEach(x -> used.add(x.code));
        do code = String.format("%06d", rnd.nextInt(1_000_000)); while (used.contains(code));
        l = new Link(id, token, code, System.currentTimeMillis() + getConfig().getInt("link-minutes", 15) * 60000L);
        links.put(id, l);
        return l;
    }

    String linkUrl(UUID id) { return publicUrl + "/#t=" + linkFor(id).token; }

    private void link(Player p) {
        if (publicUrl.isEmpty()) { p.sendMessage(ChatColor.RED + "The voice page isn't running: " + webStatus); return; }
        VoicechatServerApi a = api;
        VoicechatConnection c = a == null ? null : a.getConnectionOf(p.getUniqueId());
        if (c != null && c.isInstalled()) { p.sendMessage(msg("has-mod")); return; }
        Link l = linkFor(p.getUniqueId());
        String url = publicUrl + "/#t=" + l.token;
        boolean bedrock = Edition.bedrock(p);
        String device = deviceOs(p);
        boolean console = device.matches("(?i).*(xbox|ps4|ps5|playstation|switch|nx|tvos|fire ?tv).*");
        if (getConfig().getBoolean("qr.give-map", true)) giveMap(p);
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Voice chat" + ChatColor.GRAY + " (for players without the mod)");
        if (bedrock || console) p.sendMessage(ChatColor.GRAY + "Scan the " + ChatColor.WHITE + "QR map" + ChatColor.GRAY + " in your hand with your phone camera"
            + (console ? ChatColor.GRAY + ": your phone becomes your mic + headset." : "."));
        TextComponent t = new TextComponent(ChatColor.GRAY + "Or open " + ChatColor.AQUA + ChatColor.UNDERLINE + publicUrl);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Opens voice chat, already logged in")));
        p.spigot().sendMessage(t);
        p.sendMessage(ChatColor.GRAY + "and type the code " + ChatColor.WHITE + "" + ChatColor.BOLD + spaced(l.code) + ChatColor.GRAY + " (works for " + getConfig().getInt("link-minutes", 15) + " min).");
        if (bedrock && getConfig().getBoolean("bedrock-form", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> BedrockForms.menu(this, p, "Voice Chat",
                "§7Talk to everyone with Simple Voice Chat.\n\n§fOn your phone, scan the §bQR map§f in your hand,\n§for go to:\n\n§b" + publicUrl
                    + "\n\n§fand type the code:\n\n§l§e" + spaced(l.code) + "§r\n\n§7The page becomes your mic and headset. Keep it open while you play."
                    + (console ? "\n\n§7Playing on a console? Use your phone with headphones." : ""),
                List.of("Got it", "Give me the QR map again"), i -> { if (i == 1) giveMap(p); }, null), 2);
        }
    }

    private static String spaced(String code) { return code.substring(0, 3) + " " + code.substring(3); }

    /** Floodgate's device for a Bedrock player ("Xbox One", "Nintendo Switch", "Android"...), or "". */
    static String deviceOs(Player p) {
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = c.getMethod("getInstance").invoke(null);
            Object fp = c.getMethod("getPlayer", UUID.class).invoke(api, p.getUniqueId());
            if (fp == null) return "";
            Object os = fp.getClass().getMethod("getDeviceOs").invoke(fp);
            return os == null ? "" : os.toString();
        } catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------ the QR map

    private void setupMap() {
        File f = new File(getDataFolder(), "data.yml");
        YamlConfiguration d = YamlConfiguration.loadConfiguration(f);
        int id = d.getInt("map-id", -1);
        MapView v = id >= 0 ? Bukkit.getMap(id) : null;
        if (v == null) {
            World w = Bukkit.getWorlds().get(0);
            v = Bukkit.createMap(w);
            d.set("map-id", v.getId());
            try { d.save(f); } catch (Exception ignored) { }
        }
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        try { v.setTrackingPosition(false); v.setUnlimitedTracking(false); v.setLocked(true); } catch (Throwable ignored) { }
        v.addRenderer(new QrRenderer());
        qrView = v;
    }

    /** Draws each viewer's OWN link (a contextual renderer: one map id for everyone). */
    private final class QrRenderer extends MapRenderer {
        private final Map<UUID, String> drawn = new HashMap<>();
        QrRenderer() { super(true); }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            Link l = links.get(player.getUniqueId());
            String url = l == null ? publicUrl : publicUrl + "/#t=" + l.token;
            if (url.equals(drawn.get(player.getUniqueId()))) return;
            drawn.put(player.getUniqueId(), url);
            java.awt.Color white = new java.awt.Color(255, 255, 255), black = new java.awt.Color(0, 0, 0);
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixelColor(x, y, white);
            if (url.isEmpty()) return;
            QrCode q = QrCode.encode(url, QrCode.Ecc.L);
            int scale = Math.max(1, 128 / (q.size + 4));
            int off = (128 - q.size * scale) / 2;
            for (int y = 0; y < q.size; y++) for (int x = 0; x < q.size; x++) {
                if (!q.get(x, y)) continue;
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) canvas.setPixelColor(off + x * scale + dx, off + y * scale + dy, black);
            }
        }
    }

    private void giveMap(Player p) {
        if (qrView == null) return;
        takeMaps(p);
        ItemStack it = new ItemStack(Material.FILLED_MAP);
        MapMeta m = (MapMeta) it.getItemMeta();
        m.setMapView(qrView);
        m.setDisplayName(ChatColor.AQUA + "Voice Chat QR");
        m.setLore(List.of(ChatColor.GRAY + "Scan it with your phone camera", ChatColor.GRAY + "to open voice chat.", ChatColor.DARK_GRAY + "Disappears once you connect."));
        m.getPersistentDataContainer().set(mapKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) p.getInventory().setItemInMainHand(it);
        else {
            int free = p.getInventory().firstEmpty();
            if (free < 0) { p.sendMessage(ChatColor.RED + "Your inventory is full: make room for the QR map, or use the link + code."); return; }
            // put it in the hand so it's on screen, move the held item to the free slot
            p.getInventory().setItem(free, hand);
            p.getInventory().setItemInMainHand(it);
        }
        p.sendMessage(ChatColor.GRAY + "Hold the map up (it's in your hand) and point your phone camera at the screen.");
    }

    private boolean isQrMap(ItemStack it) {
        return it != null && it.getType() == Material.FILLED_MAP && it.hasItemMeta() && it.getPersistentDataContainer().has(mapKey, PersistentDataType.BYTE);
    }

    private void takeMaps(Player p) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (isQrMap(c[i])) p.getInventory().setItem(i, null);
        if (isQrMap(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    // ------------------------------------------------------------------ remembered devices

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private synchronized String newDevice(UUID id, String label) {
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String base = "players." + id;
        List<Map<?, ?>> list = new ArrayList<>(devices.getMapList(base));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hash", sha(key));
        d.put("label", label.length() > 60 ? label.substring(0, 60) : label);
        d.put("since", System.currentTimeMillis());
        list.add(d);
        while (list.size() > 5) list.remove(0);
        devices.set(base, list);
        saveDevicesAsync();
        return key;
    }

    private synchronized UUID deviceOwner(String key) {
        String h = sha(key);
        var sec = devices.getConfigurationSection("players");
        if (sec == null) return null;
        for (String uuid : sec.getKeys(false)) {
            for (Map<?, ?> m : devices.getMapList("players." + uuid)) if (h.equals(m.get("hash"))) {
                try { return UUID.fromString(uuid); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private synchronized void forgetDevice(UUID id, String key) {
        if (key.isEmpty()) return;
        String h = sha(key);
        List<Map<?, ?>> list = new ArrayList<>(devices.getMapList("players." + id));
        list.removeIf(m -> h.equals(m.get("hash")));
        devices.set("players." + id, list.isEmpty() ? null : list);
        saveDevicesAsync();
    }

    private void saveDevicesAsync() {
        if (!isEnabled()) { saveDevices(); return; }
        Bukkit.getScheduler().runTaskAsynchronously(this, this::saveDevices);
    }

    private synchronized void saveDevices() {
        try {
            getDataFolder().mkdirs();
            File tmp = new File(getDataFolder(), "devices.yml.tmp");
            Files.writeString(tmp.toPath(), devices.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(tmp.toPath(), devicesFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception e) { Files.move(tmp.toPath(), devicesFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            getLogger().warning("Couldn't save devices.yml: " + e);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private String msg(String key) {
        String s = getConfig().getString("messages." + key, key);
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String str(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch (Exception e) { return ""; }
    }

    private String json(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String k = String.valueOf(kv[i]);
            if (v instanceof Boolean b) o.addProperty(k, b);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof com.google.gson.JsonElement j) o.add(k, j);
            else o.addProperty(k, v == null ? null : String.valueOf(v));
        }
        return o.toString();
    }
}
