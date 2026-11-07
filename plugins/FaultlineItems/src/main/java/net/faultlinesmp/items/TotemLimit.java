package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TOTEM LIMIT (Items 1.2.69, config totem-limit:, data totems.yml).
 * A totem that pops because of ANOTHER PLAYER (melee, arrows/tridents/any projectile they shot, their tamed pets, TNT they
 * lit) counts: 1/4, 2/4, ... Once a player has had `max` (4) totems popped by players, their next totem won't save them
 * from a player ("4/4 Totems has been popped, this totem wont work") and they die. Totems still work against mobs, falls,
 * lava and everything else. Holding a totem while at the limit (offhand or main hand) shows the warning. The count goes
 * back to 0 when they die (`reset-on-death`, true), or when an admin sets it.
 * Admin: /totems <player> | set <player> <n> | reset <player|all> | limit <n|off> | on | off. Players: /totems = their own count.
 */
final class TotemLimit implements Listener, CommandExecutor, TabCompleter {

    private final FaultlineItems plugin;
    private final File file;
    private final Map<UUID, Integer> pops = new HashMap<>();
    /** The last player who hurt each player, and when (backup when the death cause isn't known). */
    private final Map<UUID, UUID> lastBy = new HashMap<>();
    private final Map<UUID, Long> lastAt = new HashMap<>();
    /** Players who already saw the "won't work" warning for the totem they're holding now. */
    private final Set<UUID> warned = new HashSet<>();

    TotemLimit(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "totems.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) try { pops.put(UUID.fromString(k), y.getInt(k)); } catch (IllegalArgumentException ignored) { }
        Bukkit.getScheduler().runTaskTimer(plugin, this::warnHolders, 40L, 10L);
    }

    boolean enabled() { return plugin.getConfig().getBoolean("totem-limit.enabled", true); }

    int max() { return Math.max(0, plugin.getConfig().getInt("totem-limit.max", 4)); }

    int count(UUID id) { return pops.getOrDefault(id, 0); }

    void set(UUID id, int n) {
        if (n <= 0) pops.remove(id); else pops.put(id, n);
        save();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : pops.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException e) { plugin.getLogger().warning("[Totems] couldn't save totems.yml: " + e.getMessage()); }
    }

    private static boolean counted(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    /** The player behind a hit (themselves, their projectile, their pet, their TNT), or null. */
    static Player playerBehind(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.isTamed() && t.getOwner() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    private String line(int n) { return n + "/" + max(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player by = playerBehind(e.getDamager());
        if (by == null || by.equals(victim)) return;
        lastBy.put(victim.getUniqueId(), by.getUniqueId());
        lastAt.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    /** Was this player's fatal hit (the one popping the totem) from another player? */
    private boolean byPlayer(Player p) {
        EntityDamageEvent last = p.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent ed) {
            Player by = playerBehind(ed.getDamager());
            return by != null && !by.equals(p);
        }
        if (last != null) return false; // fall, lava, a mob...: not a player
        Long at = lastAt.get(p.getUniqueId());
        return at != null && System.currentTimeMillis() - at < 600;
    }

    /** Fires only when a totem is about to save them (vanilla cancels it when there's none). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (!enabled() || !(e.getEntity() instanceof Player p) || !counted(p) || !byPlayer(p)) return;
        int n = count(p.getUniqueId()), max = max();
        if (n >= max) {
            e.setCancelled(true); // the totem does nothing: they die
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + line(max) + " Totems has been popped, this totem wont work");
            return;
        }
        n++;
        set(p.getUniqueId(), n);
        p.sendMessage(ChatColor.GOLD + "Totem popped by a player: " + ChatColor.YELLOW + line(n)
                + (n >= max ? ChatColor.RED + " (your next totem won't work against players)" : ""));
        p.sendActionBar(Meteor.legacy(ChatColor.GOLD + "Totems popped: " + ChatColor.YELLOW + line(n)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("totem-limit.reset-on-death", true) && count(e.getEntity().getUniqueId()) > 0) set(e.getEntity().getUniqueId(), 0);
        warned.remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastBy.remove(id); lastAt.remove(id); warned.remove(id);
    }

    /** Holding a totem while at the limit: warn once per totem picked up. */
    private void warnHolders() {
        if (!enabled()) return;
        int max = max();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean holds = p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                    || p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING;
            if (!holds || !counted(p) || count(id) < max) { warned.remove(id); continue; }
            if (!warned.add(id)) continue;
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + line(max) + " Totems has been popped, this totem wont work"
                    + ChatColor.GRAY + " (against players, until you die)");
            p.sendActionBar(Meteor.legacy(ChatColor.RED + line(max) + " Totems has been popped, this totem wont work"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
    }

    // =========================================================================================== /totems
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission("items.totems");
        if (args.length == 0 || !admin) {
            if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.YELLOW + "/totems <player> | set <player> <n> | reset <player|all> | limit <n|off> | on | off"); return true; }
            sender.sendMessage(!enabled() ? ChatColor.GRAY + "There's no totem limit right now."
                    : ChatColor.GOLD + "Totems popped by players: " + ChatColor.YELLOW + line(count(p.getUniqueId())) + ChatColor.GRAY + " (resets when you die)");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "set" -> {
                if (args.length < 3) { sender.sendMessage(ChatColor.YELLOW + "/totems set <player> <n>"); return true; }
                OfflinePlayer t = find(args[1]);
                if (t == null) { sender.sendMessage(ChatColor.RED + "No player called " + args[1] + "."); return true; }
                int n;
                try { n = Math.max(0, Integer.parseInt(args[2])); } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "That's not a number."); return true; }
                set(t.getUniqueId(), n);
                warned.remove(t.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + t.getName() + "'s totems popped: " + line(n));
                if (t.getPlayer() != null && t.getPlayer() != sender) t.getPlayer().sendMessage(ChatColor.GOLD + "An admin set your totems popped to " + ChatColor.YELLOW + line(n));
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/totems reset <player|all>"); return true; }
                if (args[1].equalsIgnoreCase("all")) { pops.clear(); warned.clear(); save(); sender.sendMessage(ChatColor.GREEN + "Everyone's totem count is back to 0."); return true; }
                OfflinePlayer t = find(args[1]);
                if (t == null) { sender.sendMessage(ChatColor.RED + "No player called " + args[1] + "."); return true; }
                set(t.getUniqueId(), 0);
                warned.remove(t.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + t.getName() + "'s totems popped: " + line(0));
                if (t.getPlayer() != null && t.getPlayer() != sender) t.getPlayer().sendMessage(ChatColor.GOLD + "An admin reset your totems popped to " + ChatColor.YELLOW + line(0));
            }
            case "limit", "max" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.GOLD + "Totem limit: " + (enabled() ? max() : "off") + ChatColor.GRAY + "  /totems limit <n|off>"); return true; }
                if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("none")) {
                    plugin.getConfig().set("totem-limit.enabled", false);
                } else {
                    int n;
                    try { n = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "/totems limit <n|off>"); return true; }
                    plugin.getConfig().set("totem-limit.enabled", true);
                    plugin.getConfig().set("totem-limit.max", n);
                }
                plugin.saveConfig();
                warned.clear();
                sender.sendMessage(ChatColor.GREEN + "Totem limit: " + (enabled() ? max() + " pops by players" : "off"));
            }
            case "on", "off" -> {
                plugin.getConfig().set("totem-limit.enabled", sub.equals("on"));
                plugin.saveConfig();
                warned.clear();
                sender.sendMessage(ChatColor.GREEN + "Totem limit " + (enabled() ? "on (" + max() + ")" : "off"));
            }
            default -> {
                OfflinePlayer t = find(args[0]);
                if (t == null) { sender.sendMessage(ChatColor.YELLOW + "/totems <player> | set <player> <n> | reset <player|all> | limit <n|off> | on | off"); return true; }
                sender.sendMessage(ChatColor.GOLD + t.getName() + ": " + ChatColor.YELLOW + line(count(t.getUniqueId())) + ChatColor.GRAY + (enabled() ? "" : " (limit off)"));
            }
        }
        return true;
    }

    private static OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) if (o.getName() != null && o.getName().equalsIgnoreCase(name)) return o;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("items.totems")) return out;
        if (args.length == 1) {
            for (String s : List.of("set", "reset", "limit", "on", "off")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        } else if (args.length == 2 && List.of("set", "reset").contains(args[0].toLowerCase())) {
            if (args[0].equalsIgnoreCase("reset") && "all".startsWith(args[1].toLowerCase())) out.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            for (int i = 0; i <= Math.max(4, max()); i++) out.add(String.valueOf(i));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("limit")) {
            out.addAll(List.of("1", "2", "3", "4", "5", "off"));
        }
        return out;
    }
}
