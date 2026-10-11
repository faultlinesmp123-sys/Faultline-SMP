package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * FISHING TOURNAMENTS + RECORDS (Items 1.2.18, config fishing:, records in fishing.yml).
 * Every fish anyone catches gets a weight (each species its own range, big ones rare, 1% "monster" x1.5). Beat the
 * all-time record for that species and the whole server hears about it, and that fish becomes a Trophy Fish (its weight,
 * who and when on it). A TOURNAMENT (/fishing start [minutes], or every fishing.auto-every-hours) scores each player's
 * single best fish in points (weight / the species' top weight x 100, so a big Tropical Fish counts like a big Salmon);
 * a boss bar shows time and the leader; at the end the top 3 get fishing.rewards.*, the winner index stat fishing_wins.
 * /fishing [top|records], admin /fishing start [minutes] | stop.
 */
final class Fishing implements Listener, CommandExecutor, TabCompleter {

    /** species -> {min kg, max kg} */
    static final Map<Material, double[]> SPECIES = new LinkedHashMap<>();
    static {
        SPECIES.put(Material.COD, new double[]{0.4, 4.5});
        SPECIES.put(Material.SALMON, new double[]{0.8, 9.0});
        SPECIES.put(Material.PUFFERFISH, new double[]{0.3, 2.5});
        SPECIES.put(Material.TROPICAL_FISH, new double[]{0.05, 0.6});
    }

    record Catch(UUID who, String name, Material species, double kg, double points) { }
    record Record(double kg, String name, long when) { }

    private final FaultlineItems plugin;
    private final File file;
    final NamespacedKey weightKey;
    private final Random random = new Random();
    final Map<Material, Record> records = new HashMap<>();

    // the tournament
    long endsAt, lengthMs;
    final Map<UUID, Catch> best = new HashMap<>();
    BossBar bar;
    private long nextAuto;

    Fishing(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "fishing.yml");
        weightKey = new NamespacedKey(plugin, "fish_weight");
        load();
        scheduleAuto();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    boolean running() { return endsAt > 0; }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Material m : SPECIES.keySet()) {
            String b = "records." + m.name();
            if (y.contains(b + ".kg")) records.put(m, new Record(y.getDouble(b + ".kg"), y.getString(b + ".name", "?"), y.getLong(b + ".when")));
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : records.entrySet()) {
            String b = "records." + e.getKey().name();
            y.set(b + ".kg", e.getValue().kg()); y.set(b + ".name", e.getValue().name()); y.set(b + ".when", e.getValue().when());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save fishing.yml: " + ex.getMessage()); }
    }

    private void scheduleAuto() {
        double h = plugin.getConfig().getDouble("fishing.auto-every-hours", 0);
        nextAuto = h > 0 ? System.currentTimeMillis() + (long) (h * 3600000) : 0;
    }

    static String name(Material m) {
        return switch (m) { case COD -> "Cod"; case SALMON -> "Salmon"; case PUFFERFISH -> "Pufferfish"; case TROPICAL_FISH -> "Tropical Fish"; default -> m.name(); };
    }

    static String kg(double v) { return String.format(Locale.ROOT, "%.2f kg", v); }

    /** A weight for a fresh catch: mostly small, big ones rare, now and then a monster. */
    double weigh(Material m) {
        double[] r = SPECIES.get(m);
        double w = r[0] + (r[1] - r[0]) * Math.pow(random.nextDouble(), 2.2);
        if (random.nextDouble() < plugin.getConfig().getDouble("fishing.monster-chance", 0.01)) w *= 1.5;
        return Math.round(w * 100) / 100.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item item)) return;
        if (!plugin.getConfig().getBoolean("fishing.enabled", true)) return;
        ItemStack fish = item.getItemStack();
        if (!SPECIES.containsKey(fish.getType())) return;
        caught(e.getPlayer(), item, fish.getType(), weigh(fish.getType()));
    }

    void caught(Player p, Item item, Material species, double kg) {
        double points = Math.round(kg / SPECIES.get(species)[1] * 1000) / 10.0;
        p.sendActionBar(ChatColor.AQUA + "You caught a " + ChatColor.WHITE + kg(kg) + " " + name(species)
                + (running() ? ChatColor.GRAY + " (" + points + " points)" : ""));
        Record rec = records.get(species);
        boolean record = rec == null || kg > rec.kg();
        if (record) {
            records.put(species, new Record(kg, p.getName(), System.currentTimeMillis()));
            save();
            if (rec != null || plugin.getConfig().getBoolean("fishing.announce-first-records", false))
                Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 NEW RECORD: " + ChatColor.WHITE + p.getName() + ChatColor.AQUA + " caught a "
                        + ChatColor.WHITE + kg(kg) + " " + name(species) + ChatColor.GRAY + (rec != null ? " (old record " + kg(rec.kg()) + " by " + rec.name() + ")" : ""));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.4f);
        }
        if (item != null && (record || (running() && plugin.getConfig().getBoolean("fishing.tag-fish-in-tournament", true)))) {
            ItemStack fish = item.getItemStack().clone();
            ItemMeta m = fish.getItemMeta();
            if (record) m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Trophy " + name(species));
            m.setLore(List.of(ChatColor.GRAY + kg(kg) + (record ? ChatColor.GOLD + " (server record)" : ""),
                    ChatColor.DARK_GRAY + "Caught by " + p.getName() + ", " + new SimpleDateFormat("d MMM yyyy").format(new Date())));
            m.getPersistentDataContainer().set(weightKey, PersistentDataType.DOUBLE, kg);
            fish.setItemMeta(m);
            item.setItemStack(fish);
        }
        if (running()) {
            Catch c = best.get(p.getUniqueId());
            if (c == null || points > c.points()) {
                best.put(p.getUniqueId(), new Catch(p.getUniqueId(), p.getName(), species, kg, points));
                if (bar != null && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            }
        }
    }

    List<Catch> standings() {
        List<Catch> l = new ArrayList<>(best.values());
        l.sort((a, b) -> Double.compare(b.points(), a.points()));
        return l;
    }

    boolean start(int minutes) {
        if (running()) return false;
        best.clear();
        lengthMs = minutes * 60000L;
        endsAt = System.currentTimeMillis() + lengthMs;
        bar = Bukkit.createBossBar(ChatColor.AQUA + "Fishing Tournament", BarColor.BLUE, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 A FISHING TOURNAMENT has begun! " + ChatColor.GRAY + minutes
                + " minutes: your single best fish counts (points = weight against that species' biggest). /fishing top");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 0.8f);
        return true;
    }

    void stop(boolean award) {
        if (!running()) return;
        endsAt = 0;
        if (bar != null) { bar.removeAll(); bar = null; }
        List<Catch> top = standings();
        if (!award || top.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 The fishing tournament is over" + (top.isEmpty() ? ": nobody caught anything." : "."));
            best.clear();
            return;
        }
        StringBuilder s = new StringBuilder(ChatColor.AQUA + "🎣 The fishing tournament is over!");
        String[] keys = {"first", "second", "third"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            Catch c = top.get(i);
            s.append("\n ").append(ChatColor.GOLD).append(i + 1).append(". ").append(ChatColor.WHITE).append(c.name()).append(ChatColor.GRAY)
                    .append(" ").append(kg(c.kg())).append(" ").append(name(c.species())).append(" (").append(c.points()).append(" points)");
            Player p = Bukkit.getPlayer(c.who());
            for (String cmd : plugin.getConfig().getStringList("fishing.rewards." + keys[i]))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", c.name()));
            if (i == 0 && Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + c.name() + " fishing_wins 1");
            if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        Bukkit.broadcastMessage(s.toString());
        best.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!running() && nextAuto > 0 && now >= nextAuto) {
            if (Bukkit.getOnlinePlayers().size() >= plugin.getConfig().getInt("fishing.auto-min-players", 2))
                start(plugin.getConfig().getInt("fishing.minutes", 15));
            scheduleAuto();
        }
        if (!running()) return;
        if (now >= endsAt) { stop(true); return; }
        long left = (endsAt - now) / 1000;
        List<Catch> top = standings();
        bar.setTitle(ChatColor.AQUA + "Fishing Tournament " + ChatColor.WHITE + String.format("%d:%02d", left / 60, left % 60)
                + (top.isEmpty() ? "" : ChatColor.GRAY + "  ·  leader " + ChatColor.WHITE + top.get(0).name() + ChatColor.GRAY + " " + kg(top.get(0).kg()) + " " + name(top.get(0).species())));
        bar.setProgress(Math.max(0, Math.min(1, (endsAt - now) / (double) Math.max(1, lengthMs))));
        for (Player p : Bukkit.getOnlinePlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }

    void shutdown() { if (running()) stop(false); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? (running() ? "top" : "records") : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("items.fishing.admin");
        switch (sub) {
            case "start" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                int min = plugin.getConfig().getInt("fishing.minutes", 15);
                if (args.length > 1) try { min = Math.max(1, Math.min(180, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) { }
                if (!start(min)) sender.sendMessage(ChatColor.RED + "A tournament is already on (/fishing stop).");
            }
            case "stop" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                if (!running()) sender.sendMessage(ChatColor.RED + "No tournament is on.");
                else stop(args.length > 1 && args[1].equalsIgnoreCase("award"));
            }
            case "top" -> {
                if (!running()) { sender.sendMessage(ChatColor.GRAY + "No tournament is on. /fishing records for the all-time biggest."); return true; }
                List<Catch> top = standings();
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "🎣 Tournament standings");
                for (int i = 0; i < Math.min(10, top.size()); i++) { Catch c = top.get(i);
                    sender.sendMessage(ChatColor.GRAY + " " + (i + 1) + ". " + ChatColor.WHITE + c.name() + ChatColor.GRAY + " " + kg(c.kg()) + " " + name(c.species()) + " (" + c.points() + ")"); }
                if (top.isEmpty()) sender.sendMessage(ChatColor.GRAY + " Nobody has caught anything yet.");
            }
            default -> {
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "🎣 Server fishing records");
                for (Material m : SPECIES.keySet()) {
                    Record r = records.get(m);
                    sender.sendMessage(ChatColor.GRAY + " " + name(m) + ": " + (r == null ? "none yet" : ChatColor.WHITE + kg(r.kg()) + ChatColor.GRAY + " by " + r.name()));
                }
                if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Admin: /fishing start [minutes] | stop [award]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("items.fishing.admin") ? List.of("top", "records", "start", "stop") : List.of("top", "records");
        return List.of();
    }
}
