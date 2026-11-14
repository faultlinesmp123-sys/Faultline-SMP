package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /offtp <player> [last|bed|death] (aliases /otp, /tpoffline), permission items.offlinetp (op): teleports staff to where an
 * OFFLINE player logged out (our own record from the quit, else the position in their player file), their bed / respawn
 * point, or where they last died. An online player = a plain teleport to them. Logout spots are kept in offline_spots.yml
 * (name, world, x y z, look, time) so names tab-complete and the exact world is known even for players Paper hasn't cached.
 */
final class OfflineTp implements Listener, TabExecutor {

    private final FaultlineItems plugin;
    private final File file;
    private final YamlConfiguration data;

    OfflineTp(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "offline_spots.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Location l = p.getLocation();
        String k = "spots." + p.getUniqueId();
        data.set(k + ".name", p.getName());
        data.set(k + ".world", l.getWorld().getName());
        data.set(k + ".x", l.getX()); data.set(k + ".y", l.getY()); data.set(k + ".z", l.getZ());
        data.set(k + ".yaw", (double) l.getYaw()); data.set(k + ".pitch", (double) l.getPitch());
        data.set(k + ".time", System.currentTimeMillis());
        save();
    }

    void save() {
        SafeYaml.write(plugin, file, data.saveToString()); // 1.3.19: atomic, off the main thread
    }

    /** The saved logout spot (null = none / its world isn't loaded). */
    Location saved(UUID id) {
        String k = "spots." + id;
        if (!data.contains(k + ".world")) return null;
        World w = Bukkit.getWorld(data.getString(k + ".world", ""));
        if (w == null) return null;
        return new Location(w, data.getDouble(k + ".x"), data.getDouble(k + ".y"), data.getDouble(k + ".z"),
                (float) data.getDouble(k + ".yaw"), (float) data.getDouble(k + ".pitch"));
    }

    /** By name: online, our records (exact name, any case), then Paper's user cache. Never a blocking web lookup. */
    OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        var sec = data.getConfigurationSection("spots");
        if (sec != null) for (String id : sec.getKeys(false))
            if (name.equalsIgnoreCase(sec.getString(id + ".name", ""))) {
                try { return Bukkit.getOfflinePlayer(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
            }
        try { return Bukkit.getOfflinePlayerIfCached(name); } catch (Throwable t) { return null; }
    }

    static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h " + (s % 3600) / 60 + "m ago";
        return s / 86400 + "d " + (s % 86400) / 3600 + "h ago";
    }

    static String where(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.offlinetp")) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
        if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return true; }
        if (a.length == 0) { s.sendMessage(ChatColor.YELLOW + "/" + label + " <player> [last|bed|death]"); return true; }
        String mode = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "last";
        OfflinePlayer t = find(a[0]);
        if (t == null || (!t.isOnline() && !t.hasPlayedBefore() && saved(t.getUniqueId()) == null)) {
            s.sendMessage(ChatColor.RED + a[0] + " has never played here (or isn't in the user cache)."); return true;
        }
        String name = t.getName() != null ? t.getName() : a[0];
        Location to; String what;
        switch (mode) {
            case "bed", "spawn", "respawn" -> { to = t.getRespawnLocation(); what = "respawn point"; }
            case "death", "died" -> { to = t.getLastDeathLocation(); what = "last death"; }
            default -> {
                if (t instanceof Player on && on.isOnline()) { to = on.getLocation(); what = "position (online)"; }
                else {
                    Location ours = saved(t.getUniqueId());
                    Location file = null;
                    try { file = t.getLocation(); } catch (Throwable ignored) { } // reads their player data file
                    to = ours != null ? ours : file;
                    long when = data.getLong("spots." + t.getUniqueId() + ".time", 0);
                    what = "logout spot" + (ours != null && when > 0 ? " (" + ago(when) + ")" : "");
                }
            }
        }
        if (to == null || to.getWorld() == null) { s.sendMessage(ChatColor.RED + name + " has no " + what.replaceAll(" \\(.*", "") + " saved."); return true; }
        final Location dest = to.clone();
        final String msg = ChatColor.GREEN + "Teleported to " + name + "'s " + what + ChatColor.GRAY + " (" + where(dest) + ")";
        try {
            p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (ok) p.sendMessage(msg); else p.sendMessage(ChatColor.RED + "Teleport was cancelled (another plugin blocked it).");
            });
        } catch (RuntimeException ex) { // no async teleport here (tests): a plain one
            if (p.teleport(dest, PlayerTeleportEvent.TeleportCause.COMMAND)) p.sendMessage(msg);
            else p.sendMessage(ChatColor.RED + "Teleport was cancelled (another plugin blocked it).");
        }
        plugin.getLogger().info("[OfflineTp] " + p.getName() + " -> " + name + "'s " + what + " " + where(dest));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("items.offlinetp")) return out;
        if (a.length == 1) {
            String pre = a[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
            var sec = data.getConfigurationSection("spots");
            if (sec != null) for (String id : sec.getKeys(false)) {
                String n = sec.getString(id + ".name", "");
                if (!n.isEmpty() && n.toLowerCase(Locale.ROOT).startsWith(pre) && !out.contains(n)) out.add(n);
                if (out.size() >= 60) break;
            }
        } else if (a.length == 2) for (String m : new String[]{"last", "bed", "death"}) if (m.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(m);
        return out;
    }
}
