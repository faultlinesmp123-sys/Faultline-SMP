package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;

/**
 * COMBAT TAG for fights with a MIMIC (1.2.86, config mimic-player.combat-tag:). With DONUTCOMBAT on the server (the owner's
 * combat plugin; its own listener only tags player-vs-player hits) the mimic's hits call ITS public `tagPlayer(Player)` by
 * reflection, so it's DonutCombat's real tag (its duration, action bar, blocked commands, combat-log kill) and nothing below
 * runs; `use-donutcombat: false` = always the built-in tag. Without it: the built-in tag. A mimic hitting you (melee, its arrows /
 * tridents / TNT) or you hitting a mimic = in combat for `seconds` (30): an action bar countdown, the teleport commands in
 * `blocked-commands` (DonutTPA's tpa / tpahere / tpaccept and aliases, home, spawn, warp, rtp, back) refused, and any
 * command / plugin teleport over `max-teleport` (16) blocks cancelled (a TPA accepted just before the fight finishing its
 * warmup mid-fight). DonutTPA itself has no combat system, so this is where it lives. Ender pearls still work.
 */
final class CombatTag implements Listener {

    private final FaultlineItems plugin;
    private final MimicPlayer mimics;
    private final Map<UUID, Long> until = new HashMap<>();

    CombatTag(FaultlineItems plugin, MimicPlayer mimics) {
        this.plugin = plugin;
        this.mimics = mimics;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    boolean on() { return plugin.getConfig().getBoolean("mimic-player.combat-tag.enabled", true); }
    long ms() { return (long) (Balance.num(plugin, "mimic-player.combat-tag.seconds", 30, 120) * 1000L); } // 1.3.20: 2 minutes (an old 30 reads as 120)

    void clear(UUID id) { until.remove(id); }

    private java.lang.reflect.Method donutTag;
    private org.bukkit.plugin.Plugin donut;

    /** DonutCombat's tagPlayer(Player), when it's installed and enabled. */
    private boolean donutCombat(Player p) {
        if (!plugin.getConfig().getBoolean("mimic-player.combat-tag.use-donutcombat", true)) return false;
        org.bukkit.plugin.Plugin dc = Bukkit.getPluginManager().getPlugin("DonutCombat");
        if (dc == null || !dc.isEnabled()) return false;
        try {
            if (donut != dc || donutTag == null) { donut = dc; donutTag = dc.getClass().getMethod("tagPlayer", Player.class); }
            donutTag.invoke(dc, p);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("[MimicPlayer] couldn't tag " + p.getName() + " through DonutCombat (" + e + "): using the built-in combat tag");
            return false;
        }
    }

    void tag(Player p) {
        if (!on() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        try { if (plugin.camera != null) plugin.camera.noteFight(p.getUniqueId()); } catch (RuntimeException ignored) { }
        try { if (plugin.truce != null) plugin.truce.noteMimic(p); } catch (RuntimeException ignored) { }
        if (donutCombat(p)) return; // DonutCombat runs the whole tag
        boolean was = left(p) > 0;
        until.put(p.getUniqueId(), System.currentTimeMillis() + ms());
        if (!was) p.sendMessage(Component.text("You're in combat with a mimic: no teleporting for " + ms() / 1000 + "s.", NamedTextColor.RED));
    }

    /** Seconds of combat left (0 = not in combat). */
    long left(Player p) {
        Long u = until.get(p.getUniqueId());
        if (u == null) return 0;
        long l = (u - System.currentTimeMillis() + 999) / 1000;
        if (l <= 0) { until.remove(p.getUniqueId()); return 0; }
        return l;
    }

    private void tick() {
        if (until.isEmpty()) return;
        for (UUID id : new ArrayList<>(until.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { until.remove(id); continue; }
            long l = left(p);
            if (l > 0) p.sendActionBar(Component.text("⚔ In combat: " + l + "s", NamedTextColor.RED));
            else p.sendActionBar(Component.text("You're out of combat.", NamedTextColor.GREEN));
        }
    }

    private boolean isMimic(Entity e) {
        if (e == null) return false;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity sh) e = sh;
        return mimics.byBrain.containsKey(e.getUniqueId()) || mimics.byBody.containsKey(e.getUniqueId());
    }

    private Player playerOf(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (mimics.byBrain.isEmpty()) return;
        Entity src = e.getDamager();
        if (e.getEntity() instanceof Player victim && (isMimic(src) || src.getScoreboardTags().contains(MimicPlayer.TAG))) tag(victim); // its hits, shots, TNT
        Player attacker = playerOf(src);
        if (attacker != null && isMimic(e.getEntity())) tag(attacker);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        long l = left(e.getPlayer());
        if (l <= 0 || e.getPlayer().hasPermission("faultline.combat.bypass")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        List<String> blocked = plugin.getConfig().getStringList("mimic-player.combat-tag.blocked-commands");
        if (blocked.isEmpty()) blocked = List.of("tpa", "tpahere", "tpaccept", "tpaaccept", "tpyes", "home", "homes", "spawn", "warp", "rtp", "back");
        if (!blocked.contains(cmd)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("You can't do that in combat (" + l + "s left).", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        long l = left(e.getPlayer());
        if (l <= 0 || e.getPlayer().hasPermission("faultline.combat.bypass")) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        if (e.getTo() == null || e.getFrom().getWorld() == e.getTo().getWorld() && e.getFrom().distance(e.getTo()) <= plugin.getConfig().getDouble("mimic-player.combat-tag.max-teleport", 16)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("Teleport cancelled: you're in combat (" + l + "s left).", NamedTextColor.RED));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { until.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) { until.remove(e.getEntity().getUniqueId()); } // dying ends the fight
}
