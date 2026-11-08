package net.faultlinesmp.kingdoms;

import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KINGDOM CHAT (Kingdoms 1.4.0, config kingdom-chat:). /kc <message> = only your kingdom sees it; /kc alone = everything
 * you type goes to your kingdom until /kc again. /ac = your kingdom + its allies (treaties). Staff with kingdoms.spy see
 * every kingdom's chat (/kingdoms spy toggles it). Each kingdom can have its own Discord channel
 * (discord.kingdom-chat.channels.1/2/3): what's said in /kc is posted there by the bot (batched, no pings), and what
 * people type in that channel shows up for that kingdom only (DiscordGateway.message -> fromDiscord).
 */
final class KingdomChat implements Listener {

    private final FaultlineKingdoms pl;
    /** Players whose normal chat goes to their kingdom ("k") or their alliance ("a"). */
    final Map<UUID, String> mode = new ConcurrentHashMap<>();
    final Set<UUID> spyOff = ConcurrentHashMap.newKeySet();
    private final Map<String, ConcurrentLinkedQueue<String>> outbox = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "FaultlineKingdoms-KChat"); t.setDaemon(true); return t; });

    KingdomChat(FaultlineKingdoms pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimerAsynchronously(pl, this::flush, 40L, 30L); // every 1.5 s
    }

    void stop() { sender.shutdownNow(); }

    boolean on() { return pl.getConfig().getBoolean("kingdom-chat.enabled", true); }

    /** discord.kingdom-chat.channels.k, or "" */
    String channel(int k) {
        if (!pl.getConfig().getBoolean("discord.kingdom-chat.enabled", true)) return "";
        String c = pl.getConfig().getString("discord.kingdom-chat.channels." + k, "");
        return c == null || !c.trim().matches("\\d{5,25}") ? "" : c.trim();
    }

    boolean anyChannel() { for (int k = 1; k <= 3; k++) if (!channel(k).isEmpty()) return true; return false; }

    int kingdomOfChannel(String id) {
        if (id == null) return 0;
        for (int k = 1; k <= 3; k++) if (id.equals(channel(k))) return k;
        return 0;
    }

    String tag(int k) {
        String s = pl.getConfig().getString("kingdom-chat.tags." + k, "");
        return s == null || s.isEmpty() ? pl.name(k) : s;
    }

    static String clean(String s) { return s.replace('§', '&').replace('\n', ' ').trim(); }

    boolean spies(Player p) { return p.hasPermission("kingdoms.spy") && !spyOff.contains(p.getUniqueId()); }

    /** Sends a line to kingdom k (and its allies if alliance), spies get a grey copy. */
    void deliver(int k, boolean alliance, String line) {
        Set<Integer> to = new HashSet<>();
        to.add(k);
        if (alliance) for (int o = 1; o <= 3; o++) if (o != k && pl.allied(k, o)) to.add(o);
        for (Player p : Bukkit.getOnlinePlayers()) {
            int pk = FaultlineKingdoms.kingdomOf(p.getUniqueId());
            if (to.contains(pk)) p.sendMessage(line);
            else if (spies(p)) p.sendMessage(ChatColor.DARK_GRAY + "[spy] " + ChatColor.stripColor(line));
        }
        Bukkit.getConsoleSender().sendMessage("[KingdomChat] " + ChatColor.stripColor(line));
    }

    /** A player says something in kingdom (or alliance) chat. Main thread. */
    void say(Player p, String text, boolean alliance) {
        int k = FaultlineKingdoms.kingdomOf(p.getUniqueId());
        if (k == 0) { p.sendMessage(ChatColor.RED + "You're not in a kingdom (outlaws have no kingdom chat)."); mode.remove(p.getUniqueId()); return; }
        text = clean(text);
        if (text.isEmpty()) return;
        int max = pl.getConfig().getInt("kingdom-chat.max-length", 256);
        if (text.length() > max) text = text.substring(0, max);
        ChatColor c = pl.color(k);
        String line = alliance
                ? ChatColor.DARK_AQUA + "[Alliance] " + c + "[" + tag(k) + "] " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + ": " + ChatColor.AQUA + text
                : c + "[" + tag(k) + "] " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + ": " + c + text;
        deliver(k, alliance, line);
        String post = (alliance ? "[Alliance] " : "") + "**" + esc(p.getName()) + "**: " + esc(text);
        queue(k, post);
        if (alliance) for (int o = 1; o <= 3; o++) if (o != k && pl.allied(k, o)) queue(o, "[Alliance] [" + esc(tag(k)) + "] **" + esc(p.getName()) + "**: " + esc(text));
    }

    static String esc(String s) { return s.replaceAll("([*_`~|>\\\\])", "\\\\$1").replace("@", "@​"); }

    void queue(int k, String text) {
        String ch = channel(k);
        if (ch.isEmpty() || !pl.discord.on()) return;
        outbox.computeIfAbsent(ch, x -> new ConcurrentLinkedQueue<>()).add(text);
    }

    private void flush() {
        for (var e : outbox.entrySet()) {
            ConcurrentLinkedQueue<String> q = e.getValue();
            if (q.isEmpty()) continue;
            StringBuilder b = new StringBuilder();
            String s;
            while ((s = q.peek()) != null && b.length() + s.length() + 1 < 1900) { q.poll(); if (b.length() > 0) b.append('\n'); b.append(s); }
            if (b.length() == 0) { q.poll(); continue; } // one line over the limit: drop it
            String channel = e.getKey(), content = b.toString();
            sender.execute(() -> {
                JsonObject body = new JsonObject();
                body.addProperty("content", content);
                JsonObject am = new JsonObject();
                am.add("parse", new com.google.gson.JsonArray());
                body.add("allowed_mentions", am);
                try {
                    DiscordLink.Reply r = pl.discord.call("POST", "/channels/" + channel + "/messages", body.toString());
                    if (r.code() >= 300) pl.getLogger().warning("Kingdom chat -> Discord channel " + channel + ": HTTP " + r.code() + " (does the bot see that channel and have Send Messages?)");
                } catch (Exception ex) { pl.getLogger().warning("Kingdom chat -> Discord: " + ex.getMessage()); }
            });
        }
    }

    /** Someone typed in a kingdom's Discord channel (gateway thread). */
    void fromDiscord(int k, String name, String text) {
        String fmt = pl.getConfig().getString("discord.kingdom-chat.format", "&9[Discord] {kingdom} &f{name}&7: {color}{message}");
        ChatColor c = pl.color(k);
        String line = ChatColor.translateAlternateColorCodes('&', fmt.replace("{name}", name.replace('§', '&').replace("&", ""))
                .replace("{kingdom}", c + "[" + tag(k) + "]").replace("{color}", c.toString()).replace("{message}", "\u0000")).replace("\u0000", text);
        Bukkit.getScheduler().runTask(pl, () -> deliver(k, false, line));
    }

    // ---------------------------------------------------------------- chat mode

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String m = mode.get(e.getPlayer().getUniqueId());
        if (m == null || !on()) return;
        e.setCancelled(true); // never reaches public chat or the Discord chat mirror
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) say(p, text, m.equals("a")); });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { mode.remove(e.getPlayer().getUniqueId()); }

    /** /kc [msg], /ac [msg]. */
    boolean command(Player p, String[] args, boolean alliance) {
        if (!on()) { p.sendMessage(ChatColor.RED + "Kingdom chat is turned off."); return true; }
        if (FaultlineKingdoms.kingdomOf(p.getUniqueId()) == 0) { p.sendMessage(ChatColor.RED + "You're not in a kingdom."); return true; }
        if (args.length == 0) {
            String want = alliance ? "a" : "k";
            if (want.equals(mode.get(p.getUniqueId()))) {
                mode.remove(p.getUniqueId());
                p.sendMessage(ChatColor.GRAY + "Your chat goes to everyone again.");
            } else {
                mode.put(p.getUniqueId(), want);
                p.sendMessage(ChatColor.GREEN + "Everything you type now goes to " + (alliance ? "your alliance" : "your kingdom")
                        + ChatColor.GRAY + " (/" + (alliance ? "ac" : "kc") + " again to talk to everyone).");
            }
            return true;
        }
        say(p, String.join(" ", args), alliance);
        return true;
    }
}
