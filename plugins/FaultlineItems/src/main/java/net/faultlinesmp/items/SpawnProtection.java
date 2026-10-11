package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ANTI SPAWN-KILL (Items 1.2.17, config spawn-protection:). For `seconds` (30) after respawning, players can't hurt you
 * (melee, arrows, potions, anything a player is behind) and, with block-mobs, monsters don't hurt or target you. It ends
 * early the moment YOU hit a player (or any creature, with ends-on-any-attack), so it can't be used to fight from safety.
 * An action bar counts it down; a faint ring of particles shows others you're protected.
 */
final class SpawnProtection implements Listener {

    private final FaultlineItems plugin;
    private final Map<UUID, Long> until = new HashMap<>();

    SpawnProtection(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("spawn-protection.enabled", true); }
    private double seconds() { return plugin.getConfig().getDouble("spawn-protection.seconds", 30); }

    boolean protectedNow(Player p) {
        Long t = until.get(p.getUniqueId());
        if (t == null) return false;
        if (System.currentTimeMillis() >= t) { until.remove(p.getUniqueId()); return false; }
        return true;
    }

    void protect(Player p) { until.put(p.getUniqueId(), System.currentTimeMillis() + (long) (seconds() * 1000)); }

    void end(Player p, String why) {
        if (until.remove(p.getUniqueId()) != null && why != null) {
            p.sendActionBar(ChatColor.RED + "Spawn protection ended " + ChatColor.GRAY + "(" + why + ")");
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.4f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!on()) return;
        Player p = e.getPlayer();
        protect(p);
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(ChatColor.AQUA + "You're protected for " + (int) seconds()
                + " seconds " + ChatColor.GRAY + "(players can't hurt you; attacking ends it)."));
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { until.remove(e.getPlayer().getUniqueId()); }

    private static Player playerBehind(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.Tameable pet && pet.getOwner() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!on()) return;
        Player attacker = playerBehind(e.getDamager());
        // the protected player attacks: protection ends (a player always; any creature if configured)
        if (attacker != null && protectedNow(attacker) && !attacker.equals(e.getEntity())
                && (e.getEntity() instanceof Player || plugin.getConfig().getBoolean("spawn-protection.ends-on-any-attack", false))) {
            end(attacker, "you attacked");
        }
        if (!(e.getEntity() instanceof Player victim) || !protectedNow(victim)) return;
        if (attacker != null && !attacker.equals(victim)) {
            e.setCancelled(true);
            if (attacker.getGameMode() != GameMode.CREATIVE)
                attacker.sendActionBar(ChatColor.RED + victim.getName() + " just respawned " + ChatColor.GRAY + "(protected for " + left(victim) + "s)");
        } else if (attacker == null && plugin.getConfig().getBoolean("spawn-protection.block-mobs", false) && e.getDamager() instanceof org.bukkit.entity.Enemy) {
            e.setCancelled(true);
        }
    }

    /** Splash potions (poison, weakness, slowness...) don't deal a damage event: take the protected out of the splash. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        if (!on() || !(e.getPotion().getShooter() instanceof Player thrower)) return;
        for (org.bukkit.entity.LivingEntity hit : new java.util.ArrayList<>(e.getAffectedEntities())) {
            if (hit instanceof Player v && !v.equals(thrower) && protectedNow(v)) e.setIntensity(v, 0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (on() && plugin.getConfig().getBoolean("spawn-protection.block-mobs", false) && e.getTarget() instanceof Player p && protectedNow(p)) e.setCancelled(true);
    }

    private long left(Player p) { Long t = until.get(p.getUniqueId()); return t == null ? 0 : Math.max(0, (t - System.currentTimeMillis() + 999) / 1000); }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!protectedNow(p)) continue;
            p.sendActionBar(ChatColor.AQUA + "Spawn protection: " + ChatColor.WHITE + left(p) + "s");
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 0.1, 0), 4, 0.5, 0.05, 0.5, 0.01);
        }
    }
}
