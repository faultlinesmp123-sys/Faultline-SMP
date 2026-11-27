package net.faultlinesmp.items;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 2.0: fewer hostile mobs everywhere. A share ({@code mob-spawning.reduce}, 0.5 = half) of NATURAL / PATROL / REINFORCEMENT
 * hostile spawns is cancelled, in every world. That covers vanilla's own spawns and every plugin's extra "natural" spawns
 * (FaultlineWorld's biome waves, the Blood Moon's extras, the Farlands ghasts). Spawners, eggs, raids, bosses and plugin
 * CUSTOM spawns are never touched. {@code /mobspawns [percent]} (op) shows / sets how many are cut.
 */
final class MobThrottle implements Listener, CommandExecutor {
    static final Set<SpawnReason> CUT = EnumSet.of(SpawnReason.NATURAL, SpawnReason.PATROL, SpawnReason.REINFORCEMENTS);
    private final FaultlineItems plugin;

    MobThrottle(FaultlineItems plugin) { this.plugin = plugin; }

    double reduce() {
        if (!plugin.getConfig().getBoolean("mob-spawning.enabled", true)) return 0;
        return Math.max(0, Math.min(1, plugin.getConfig().getDouble("mob-spawning.reduce", 0.5)));
    }

    /**
     * TORCHES WORK: no hostile spawns where any block light reaches (vanilla's own rule for monsters), also for the spawns our
     * plugins make with a NATURAL reason (FaultlineWorld's biome waves in the Farlands / Mire / Inferno..., Blood Moon / Fog /
     * realm extras, nest ambient mobs) and the Farlands' Wall Crawler packs. Ghasts in the open air are left alone.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLight(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || !plugin.getConfig().getBoolean("mob-spawning.torches-stop-spawns", true)) return;
        boolean ours = CUT.contains(e.getSpawnReason())
                || (e.getSpawnReason() == SpawnReason.CUSTOM && e.getEntity().getScoreboardTags().contains(Farlands.CRAWLER));
        if (!ours) return;
        int limit = plugin.getConfig().getInt("mob-spawning.light-limit", 0);
        org.bukkit.block.Block b = e.getLocation().getBlock();
        int light = Math.max(b.getLightFromBlocks(), b.getRelative(0, 1, 0).getLightFromBlocks());
        if (light > limit) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || !CUT.contains(e.getSpawnReason())) return;
        double r = reduce();
        if (r > 0 && ThreadLocalRandom.current().nextDouble() < r) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || !s.hasPermission("items.mobspawns")) {
            s.sendMessage(ChatColor.GOLD + "Hostile mob spawns are cut by " + ChatColor.WHITE + Math.round(reduce() * 100) + "%"
                    + ChatColor.GRAY + " (natural spawns only; spawners, eggs and raids untouched).");
            return true;
        }
        double pct;
        try { pct = Double.parseDouble(a[0].replace("%", "")); } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "/mobspawns <0-100>"); return true; }
        pct = Math.max(0, Math.min(100, pct));
        plugin.getConfig().set("mob-spawning.enabled", true);
        plugin.getConfig().set("mob-spawning.reduce", pct / 100.0);
        plugin.saveConfig();
        s.sendMessage(ChatColor.GREEN + "Hostile natural spawns are now cut by " + Math.round(pct) + "%.");
        return true;
    }
}
