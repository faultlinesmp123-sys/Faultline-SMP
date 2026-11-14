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
 *  - ENDER PEARLS (1.2.69): one thrown every ender-pearl-cooldown-seconds (5; was 7 until 1.3.16) per player. The game's own item cooldown is
 *    set to it (the pearl greys out, Java and Bedrock), and a throw that still gets through early is cancelled. The time
 *    left is kept over a relog.
 * Creative and spectator players are never limited.
 */
final class PvpLimits implements Listener {

    private final FaultlineItems plugin;
    private final Map<UUID, Long> lungeReady = new HashMap<>(), webReady = new HashMap<>(), pearlReady = new HashMap<>();
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

    long pearlMs() { return (long) (Balance.num(plugin, "pvp-limits.ender-pearl-cooldown-seconds", 7, 5) * 1000); } // 1.3.16: 7 -> 5 s (an old 7 reads as 5)

    /** A throw while the pearl is still cooling down (the client's cooldown can be dodged): cancelled. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPearlUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getItem() == null || e.getItem().getType() != Material.ENDER_PEARL || !e.getAction().isRightClick() || !limited(e.getPlayer())) return;
        Long ready = pearlReady.get(e.getPlayer().getUniqueId());
        long now = System.currentTimeMillis();
        if (ready == null || ready <= now || pearlMs() <= 0) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        e.getPlayer().setCooldown(Material.ENDER_PEARL, (int) Math.ceil((ready - now) / 50.0));
        e.getPlayer().sendActionBar(Meteor.legacy(ChatColor.RED + "Ender Pearl ready in " + String.format("%.1f", (ready - now) / 1000.0) + " s"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlThrow(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl) || !(pearl.getShooter() instanceof Player p) || !limited(p)) return;
        long cd = pearlMs();
        if (cd <= 0) return;
        pearlReady.put(p.getUniqueId(), System.currentTimeMillis() + cd);
        int ticks = (int) Math.ceil(cd / 50.0);
        // the game puts its own 1 s cooldown on after this event: ours goes on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) p.setCooldown(Material.ENDER_PEARL, ticks); });
    }

    /** Back on: a pearl still cooling down when they left greys out again. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Long ready = pearlReady.get(e.getPlayer().getUniqueId());
        long now = System.currentTimeMillis();
        if (ready == null) return;
        if (ready <= now) { pearlReady.remove(e.getPlayer().getUniqueId()); return; }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.setCooldown(Material.ENDER_PEARL, (int) Math.ceil((ready - System.currentTimeMillis()) / 50.0)); }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        webReady.remove(e.getPlayer().getUniqueId());
        lungeReady.remove(e.getPlayer().getUniqueId());
    }
}
