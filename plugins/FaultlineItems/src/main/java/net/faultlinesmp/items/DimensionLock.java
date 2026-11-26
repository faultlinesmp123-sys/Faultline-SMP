package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;

/**
 * Nether / End lock (2.0, config {@code dimension-lock:}): portals can still be BUILT and lit, but while a dimension is locked
 * nobody goes through a portal INTO it (players, mobs, items, minecarts...). Leaving it (a portal back out) always works, and
 * commands like /tp aren't touched. Admins: {@code /dimlock [nether|end|all] [on|off]} (perm {@code items.dimlock}); the
 * switch is written to the config so it survives restarts. {@code faultline.dimlock.bypass} (default false) walks through.
 */
final class DimensionLock implements Listener, CommandExecutor, TabCompleter {

    private final FaultlineItems plugin;
    private final Map<UUID, Long> told = new HashMap<>();

    DimensionLock(FaultlineItems plugin) { this.plugin = plugin; }

    boolean locked(World.Environment env) {
        if (!plugin.getConfig().getBoolean("dimension-lock.enabled", true)) return false;
        return switch (env) {
            case NETHER -> plugin.getConfig().getBoolean("dimension-lock.nether", true);
            case THE_END -> plugin.getConfig().getBoolean("dimension-lock.end", true);
            default -> false;
        };
    }

    /** Where a portal trip from `from` leads, by the portal type (the target can be null before the game works it out). */
    static World.Environment into(World from, Location to, PlayerTeleportEvent.TeleportCause cause) {
        if (to != null && to.getWorld() != null && to.getWorld() != from) return to.getWorld().getEnvironment();
        World.Environment here = from.getEnvironment();
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return here == World.Environment.NETHER ? World.Environment.NORMAL : World.Environment.NETHER;
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) return here == World.Environment.THE_END ? World.Environment.NORMAL : World.Environment.THE_END;
        return here;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        World.Environment dest = into(p.getWorld(), e.getTo(), e.getCause());
        if (dest == p.getWorld().getEnvironment() || !locked(dest) || p.hasPermission("faultline.dimlock.bypass")) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - told.getOrDefault(p.getUniqueId(), 0L) > 4000) {
            told.put(p.getUniqueId(), now);
            p.sendActionBar(ChatColor.RED + "The " + (dest == World.Environment.NETHER ? "Nether" : "End") + " is locked right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        World from = e.getFrom().getWorld();
        Location to = e.getTo();
        if (from == null || to == null || to.getWorld() == null || to.getWorld() == from) return;
        if (locked(to.getWorld().getEnvironment())) e.setCancelled(true);
    }

    // ================================================================= /dimlock

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.dimlock")) {
            s.sendMessage(ChatColor.GRAY + "Nether: " + state(World.Environment.NETHER) + ChatColor.GRAY + ", End: " + state(World.Environment.THE_END));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(ChatColor.GRAY + "Nether: " + state(World.Environment.NETHER) + ChatColor.GRAY + ", End: " + state(World.Environment.THE_END));
            s.sendMessage(ChatColor.DARK_GRAY + "/" + label + " <nether|end|all> <on|off>  (on = locked)");
            return true;
        }
        String which = a[0].toLowerCase(Locale.ROOT);
        if (!which.equals("nether") && !which.equals("end") && !which.equals("all")) { s.sendMessage(ChatColor.YELLOW + "/" + label + " <nether|end|all> <on|off>"); return true; }
        Boolean lock = null;
        if (a.length > 1) {
            String v = a[1].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("lock") || v.equals("true")) lock = true;
            else if (v.equals("off") || v.equals("unlock") || v.equals("false")) lock = false;
        }
        plugin.getConfig().set("dimension-lock.enabled", true);
        for (String k : which.equals("all") ? List.of("nether", "end") : List.of(which)) {
            boolean v = lock != null ? lock : !plugin.getConfig().getBoolean("dimension-lock." + k, true);   // no on/off = flip it
            plugin.getConfig().set("dimension-lock." + k, v);
        }
        plugin.saveConfig();
        String msg = "Nether: " + state(World.Environment.NETHER) + ChatColor.GRAY + ", End: " + state(World.Environment.THE_END);
        s.sendMessage(ChatColor.GREEN + "Done. " + ChatColor.GRAY + msg);
        plugin.getLogger().info("[DimLock] " + s.getName() + ": " + ChatColor.stripColor(msg));
        return true;
    }

    String state(World.Environment env) { return locked(env) ? ChatColor.RED + "LOCKED" : ChatColor.GREEN + "open"; }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.dimlock")) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("nether", "end", "all"), a[0]);
        if (a.length == 2) return Legendary.filter(List.of("on", "off"), a[1]);
        return List.of();
    }
}
