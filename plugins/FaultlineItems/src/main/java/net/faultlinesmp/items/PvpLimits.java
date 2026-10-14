package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PVP LIMITS (Items 1.2.37, config pvp-limits:).
 *  - SPEAR LUNGE: once every lunge-cooldown-seconds (5) per player. A lunge on cooldown is cancelled (the jab still
 *    hits, you just don't fly forward) and the action bar says when it's back. Paper's EntityLungeEvent only exists in
 *    the 26.x API, and the release jars are built against 1.21.11, so it's registered by name at startup.
 *  - COBWEBS: one placed every cobweb-place-cooldown-ms (250) per player (web spam in fights).
 * Creative and spectator players are never limited.
 */
final class PvpLimits implements Listener {

    private final FaultlineItems plugin;
    private final Map<UUID, Long> lungeReady = new HashMap<>(), webReady = new HashMap<>();
    boolean lungeHooked;

    PvpLimits(FaultlineItems plugin) {
        this.plugin = plugin;
        hookLunge();
    }

    private boolean limited(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    @SuppressWarnings("unchecked")
    private void hookLunge() {
        Class<?> cls;
        try { cls = Class.forName("io.papermc.paper.event.entity.EntityLungeEvent"); } catch (ClassNotFoundException e) { return; } // older server: no lunges
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) cls, this, EventPriority.HIGH, (l, e) -> {
            if (!cls.isInstance(e) || !(e instanceof Cancellable c) || c.isCancelled()) return;
            Entity who = ((EntityEvent) e).getEntity();
            if (who instanceof Player p && !lunge(p)) c.setCancelled(true);
        }, plugin, false);
        lungeHooked = true;
    }

    /** May this player lunge now? (true starts the cooldown) */
    boolean lunge(Player p) {
        if (!limited(p)) return true;
        long now = System.currentTimeMillis(), cd = (long) (plugin.getConfig().getDouble("pvp-limits.lunge-cooldown-seconds", 5) * 1000);
        if (cd <= 0) return true;
        Long ready = lungeReady.get(p.getUniqueId());
        if (ready != null && ready > now) {
            p.sendActionBar(ChatColor.RED + "Lunge ready in " + String.format("%.1f", (ready - now) / 1000.0) + " s");
            return false;
        }
        lungeReady.put(p.getUniqueId(), now + cd);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.COBWEB || !limited(e.getPlayer())) return;
        long now = System.currentTimeMillis(), cd = plugin.getConfig().getLong("pvp-limits.cobweb-place-cooldown-ms", 250);
        if (cd <= 0) return;
        UUID id = e.getPlayer().getUniqueId();
        Long ready = webReady.get(id);
        if (ready != null && ready > now) { e.setCancelled(true); return; }
        webReady.put(id, now + cd);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        webReady.remove(e.getPlayer().getUniqueId());
        lungeReady.remove(e.getPlayer().getUniqueId());
    }
}
