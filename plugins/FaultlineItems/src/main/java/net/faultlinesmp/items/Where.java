package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /PING and /COORDS (Items 1.2.24, config where:, coords.yml). Bedrock has no F3 screen and Geyser hides its own
 * coordinates on many servers, so:
 *  - /ping [player|all]: your ping, someone's, or everyone's (fastest first; on Bedrock, a menu).
 *  - /coords: a boss bar with your X Y Z, the way you face, the biome and the dimension, updated twice a second; again
 *    to hide it (remembered across relogs). /coords share [player|kingdom|all] tells others where you are (all = chat).
 * Works the same for Java players. where.bedrock-coords-on-join (false) shows the bar to Bedrock players by default.
 */
final class Where implements Listener, CommandExecutor, TabCompleter {

    private final FaultlineItems plugin;
    private final File file;
    private final Set<UUID> showing = new HashSet<>(), hidden = new HashSet<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> lastShare = new HashMap<>();

    Where(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "coords.yml");
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String s : y.getStringList("on")) try { showing.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            for (String s : y.getStringList("off")) try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        for (Player p : Bukkit.getOnlinePlayers()) if (wants(p)) bar(p);
    }

    private boolean wants(Player p) {
        UUID id = p.getUniqueId();
        if (showing.contains(id)) return true;
        if (hidden.contains(id)) return false;
        return plugin.getConfig().getBoolean("where.bedrock-coords-on-join", false) && Edition.bedrock(p);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("on", showing.stream().map(UUID::toString).toList());
        y.set("off", hidden.stream().map(UUID::toString).toList());
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save coords.yml: " + e.getMessage()); }
    }

    static String facing(float yaw) {
        String[] d = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return d[Math.floorMod(Math.round(yaw / 45f), 8)];
    }

    static String dimension(World w) {
        return switch (w.getEnvironment()) { case NETHER -> "Nether"; case THE_END -> "The End"; default -> w.getName().equals("faultline_void") ? "???" : "Overworld"; };
    }

    static String biome(Location l) {
        try {
            String k = l.getBlock().getBiome().getKey().getKey();
            StringBuilder b = new StringBuilder();
            for (String w : k.split("_")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            return b.toString();
        } catch (RuntimeException | LinkageError e) { return "?"; }
    }

    static String spot(Location l) { return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    private void bar(Player p) {
        BossBar b = bars.computeIfAbsent(p.getUniqueId(), k -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        b.setProgress(0);
        if (!b.getPlayers().contains(p)) b.addPlayer(p);
        update(p, b);
    }

    private void update(Player p, BossBar b) {
        Location l = p.getLocation();
        b.setTitle(ChatColor.WHITE + "📍 " + spot(l) + ChatColor.GRAY + "  ·  " + ChatColor.WHITE + facing(l.getYaw())
                + ChatColor.GRAY + "  ·  " + biome(l) + "  ·  " + dimension(l.getWorld()));
    }

    private void tick() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) update(p, e.getValue());
        }
    }

    private void hide(Player p) {
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) b.removeAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && wants(p)) bar(p); }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { hide(e.getPlayer()); lastShare.remove(e.getPlayer().getUniqueId()); }

    void shutdown() { for (BossBar b : bars.values()) b.removeAll(); bars.clear(); }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ping")) return ping(sender, args);
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("share")) return share(p, args.length > 1 ? args[1] : "all");
        UUID id = p.getUniqueId();
        if (bars.containsKey(id)) {
            hide(p); showing.remove(id); hidden.add(id);
            p.sendMessage(ChatColor.GRAY + "Coordinates hidden. " + ChatColor.WHITE + "/coords" + ChatColor.GRAY + " to show them again.");
        } else {
            bar(p); hidden.remove(id); showing.add(id);
            p.sendMessage(ChatColor.AQUA + "Coordinates on (the bar at the top). " + ChatColor.WHITE + "/coords" + ChatColor.GRAY + " hides them, "
                    + ChatColor.WHITE + "/coords share" + ChatColor.GRAY + " tells others where you are.");
        }
        save();
        return true;
    }

    private boolean share(Player p, String to) {
        long now = System.currentTimeMillis();
        Long last = lastShare.get(p.getUniqueId());
        if (last != null && now - last < 5000 && !p.hasPermission("faultline.vanish")) { p.sendMessage(ChatColor.RED + "Wait a few seconds."); return true; }
        lastShare.put(p.getUniqueId(), now);
        Location l = p.getLocation();
        String line = ChatColor.AQUA + "📍 " + p.getName() + ChatColor.GRAY + " is at " + ChatColor.WHITE + spot(l)
                + ChatColor.GRAY + " (" + dimension(l.getWorld()) + ", " + biome(l) + ")";
        switch (to.toLowerCase(Locale.ROOT)) {
            case "all" -> Bukkit.broadcastMessage(line);
            case "kingdom", "k" -> {
                int k = kingdom(p.getUniqueId());
                if (k == 0) { p.sendMessage(ChatColor.RED + "You're not in a kingdom."); return true; }
                int n = 0;
                for (Player o : Bukkit.getOnlinePlayers()) if (kingdom(o.getUniqueId()) == k) { o.sendMessage(ChatColor.GOLD + "[Kingdom] " + line); n++; }
                if (n <= 1) p.sendMessage(ChatColor.GRAY + "Nobody else from your kingdom is online.");
            }
            default -> {
                Player o = Bukkit.getPlayerExact(to);
                if (o == null || (plugin.vanish != null && plugin.vanish.vanished(o) && !Vanish.sees(p))) { p.sendMessage(ChatColor.RED + to + " isn't online."); return true; }
                o.sendMessage(line);
                p.sendMessage(ChatColor.GREEN + "Sent your location to " + o.getName() + ".");
            }
        }
        return true;
    }

    private static int kingdom(UUID id) {
        try {
            org.bukkit.plugin.Plugin k = Bukkit.getPluginManager().getPlugin("FaultlineKingdoms");
            if (k == null || !k.isEnabled()) return 0;
            return (int) k.getClass().getMethod("kingdomOf", UUID.class).invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return 0; }
    }

    static ChatColor pingColor(int ms) { return ms < 80 ? ChatColor.GREEN : ms < 180 ? ChatColor.YELLOW : ChatColor.RED; }

    private boolean ping(CommandSender sender, String[] args) {
        Player self = sender instanceof Player p ? p : null;
        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            List<Player> all = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) if (self == null || plugin.vanish == null || !plugin.vanish.vanished(o) || Vanish.sees(self)) all.add(o);
            all.sort(Comparator.comparingInt(Edition::ping));
            if (self != null && Edition.bedrock(self)) {
                StringBuilder s = new StringBuilder();
                for (Player o : all) s.append(o.getName()).append(": ").append(pingColor(Edition.ping(o)).toString().replace(ChatColor.COLOR_CHAR, '§')).append(Edition.ping(o)).append(" ms§r\n");
                if (BedrockForms.menu(plugin, self, "Ping", s.toString().trim(), List.of("OK"), i -> { }, null)) return true;
            }
            sender.sendMessage(ChatColor.GOLD + "Ping (" + all.size() + " online):");
            for (Player o : all) sender.sendMessage(ChatColor.GRAY + " " + o.getName() + ": " + pingColor(Edition.ping(o)) + Edition.ping(o) + " ms" + (Edition.bedrock(o) ? ChatColor.DARK_GRAY + " (Bedrock)" : ""));
            return true;
        }
        Player t = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : self;
        if (t == null || (self != null && t != self && plugin.vanish != null && plugin.vanish.vanished(t) && !Vanish.sees(self))) {
            sender.sendMessage(ChatColor.RED + (args.length > 0 ? args[0] + " isn't online." : "/ping <player>")); return true;
        }
        sender.sendMessage(ChatColor.GRAY + (t == self ? "Your ping: " : t.getName() + "'s ping: ") + pingColor(Edition.ping(t)) + Edition.ping(t) + " ms"
                + (Edition.bedrock(t) ? ChatColor.DARK_GRAY + " (Bedrock)" : ""));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("coords")) {
            if (args.length == 1) return List.of("share");
            if (args.length == 2) { List<String> l = new ArrayList<>(List.of("all", "kingdom")); for (Player o : Bukkit.getOnlinePlayers()) l.add(o.getName()); return l; }
            return List.of();
        }
        if (args.length == 1) { List<String> l = new ArrayList<>(List.of("all")); for (Player o : Bukkit.getOnlinePlayers()) l.add(o.getName()); return l; }
        return List.of();
    }
}
