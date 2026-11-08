package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * `/mimicplayer spectate [name|next|stop]` (1.2.93): an admin watches a mimic from its own eyes (spectator camera on its
 * body), anywhere in any world: it follows the mimic through portals, teleports and pearls. Sneak = free camera round it
 * (pulled back within 64 blocks / into its world), the command again = back in its eyes. An action bar shows who it is,
 * its health and what it's doing. Stop (or quitting, or the mimic going with no other one out) = back where you were in
 * your old game mode; saved in the player's PDC `faultlineitems:mimic_watch` so a crash puts you back on your next join.
 * Changing game mode yourself ends it where you are.
 */
final class MimicWatch implements Listener {
    private final FaultlineItems plugin;
    private final MimicPlayer mimics;
    private final NamespacedKey key;
    final Map<UUID, UUID> watching = new HashMap<>(); // admin -> the mimic's brain
    private final Set<UUID> free = new HashSet<>();
    private boolean switching;

    MimicWatch(FaultlineItems plugin, MimicPlayer mimics) {
        this.plugin = plugin;
        this.mimics = mimics;
        this.key = new NamespacedKey(plugin, "mimic_watch");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Mimics in a fixed order (by name), for "next". */
    List<MimicPlayer.Mimic> ordered() {
        List<MimicPlayer.Mimic> l = new ArrayList<>(mimics.byBrain.values());
        l.sort(Comparator.comparing((MimicPlayer.Mimic m) -> m.name.toLowerCase(Locale.ROOT)).thenComparing(m -> m.brain.getUniqueId()));
        return l;
    }

    void command(Player p, String arg) {
        if (arg != null && (arg.equalsIgnoreCase("stop") || arg.equalsIgnoreCase("off"))) {
            if (!watching.containsKey(p.getUniqueId())) { p.sendMessage(ChatColor.GRAY + "You aren't spectating a mimic."); return; }
            stop(p, true);
            p.sendMessage(ChatColor.GREEN + "Stopped spectating.");
            return;
        }
        List<MimicPlayer.Mimic> all = ordered();
        if (all.isEmpty()) { p.sendMessage(ChatColor.RED + "No mimics out."); return; }
        MimicPlayer.Mimic pick = null;
        UUID now = watching.get(p.getUniqueId());
        if (arg == null || arg.equalsIgnoreCase("next")) {
            if (now == null || arg == null && free.contains(p.getUniqueId()) && mimics.byBrain.containsKey(now)) pick = now == null ? all.get(0) : mimics.byBrain.get(now);
            else {
                int i = 0;
                for (int k = 0; k < all.size(); k++) if (all.get(k).brain.getUniqueId().equals(now)) i = k + 1;
                pick = all.get(i % all.size());
            }
        } else {
            for (MimicPlayer.Mimic m : all) if (m.name.equalsIgnoreCase(arg)) { pick = m; break; }
            if (pick == null) try { int n = Integer.parseInt(arg.replace("#", "")); if (n >= 1 && n <= all.size()) pick = all.get(n - 1); } catch (NumberFormatException ignored) { }
            if (pick == null) { p.sendMessage(ChatColor.RED + "No mimic of " + arg + " out."); return; }
        }
        start(p, pick);
        p.sendMessage(ChatColor.GREEN + "Spectating " + ChatColor.WHITE + pick.name + ChatColor.GREEN + " (" + (all.indexOf(pick) + 1) + "/" + all.size()
                + "). Sneak = free camera, /mimicplayer spectate = back in its eyes, spectate next, spectate stop.");
    }

    void start(Player p, MimicPlayer.Mimic m) {
        if (!watching.containsKey(p.getUniqueId()) && !p.getPersistentDataContainer().has(key)) {
            Location l = p.getLocation();
            p.getPersistentDataContainer().set(key, PersistentDataType.STRING, l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch() + ";" + p.getGameMode().name());
        }
        watching.put(p.getUniqueId(), m.brain.getUniqueId());
        free.remove(p.getUniqueId());
        switching = true;
        try {
            if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
            untarget(p);
            p.teleport(m.body.getLocation());
        } finally { switching = false; }
        // the camera goes on next tick (spectating right after a teleport is dropped by the client)
    }

    void stop(Player p, boolean moveBack) {
        watching.remove(p.getUniqueId());
        free.remove(p.getUniqueId());
        String saved = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        p.getPersistentDataContainer().remove(key);
        switching = true;
        try {
            if (p.getGameMode() == GameMode.SPECTATOR) untarget(p);
            if (!moveBack || saved == null) return;
            String[] f = saved.split(";");
            World w = Bukkit.getWorld(f[0]);
            if (w != null) p.teleport(new Location(w, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5])));
            try { p.setGameMode(GameMode.valueOf(f[6])); } catch (IllegalArgumentException ex) { p.setGameMode(GameMode.SURVIVAL); }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[Mimic] couldn't put " + p.getName() + " back after spectating: " + ex);
        } finally { switching = false; }
    }

    /** Off the camera (never lets a failure there stop the teleport after it). */
    private static void untarget(Player p) {
        try { if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null); } catch (RuntimeException ignored) { }
    }

    void stopAll() {
        for (UUID id : new ArrayList<>(watching.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) stop(p, true); }
        watching.clear();
    }

    private void tick() {
        if (watching.isEmpty()) return;
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(watching.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { watching.remove(e.getKey()); continue; }
            MimicPlayer.Mimic m = mimics.byBrain.get(e.getValue());
            if (m == null || !m.body.isValid()) { // gone (killed, removed): the next one, else back home
                List<MimicPlayer.Mimic> all = ordered();
                if (all.isEmpty()) { stop(p, true); p.sendMessage(ChatColor.YELLOW + "The mimic is gone; no others out. Back where you were."); continue; }
                p.sendMessage(ChatColor.YELLOW + "The mimic is gone: spectating " + all.get(0).name + ".");
                start(p, all.get(0));
                continue;
            }
            Entity body = m.body;
            Location bl = body.getLocation();
            boolean far = p.getWorld() != bl.getWorld() || p.getLocation().distanceSquared(bl) > (free.contains(p.getUniqueId()) ? 64 * 64 : 24 * 24);
            switching = true;
            try {
                if (free.contains(p.getUniqueId())) {
                    if (far) p.teleport(bl.clone().add(0, 3, 0));
                } else if (far) { untarget(p); p.teleport(bl); } // gone far / to another world: over there, the camera back on next tick
                else if (p.getSpectatorTarget() != body) p.setSpectatorTarget(body);
            } catch (RuntimeException ignored) {
            } finally { switching = false; }
            if (m.t % 10 == 0) {
                double hp = m.brain.getHealth(), max = mimics.maxHp(m.brain);
                p.sendActionBar(Component.text(m.name, NamedTextColor.WHITE)
                        .append(Component.text(" (" + m.skill.id() + ")  ", NamedTextColor.GRAY))
                        .append(Component.text("❤ " + Math.round(hp) + "/" + Math.round(max), hp / max > 0.5 ? NamedTextColor.GREEN : hp / max > 0.25 ? NamedTextColor.GOLD : NamedTextColor.RED))
                        .append(Component.text("  " + mimics.doing(m), NamedTextColor.AQUA))
                        .append(Component.text("  " + bl.getWorld().getName() + " " + bl.getBlockX() + " " + bl.getBlockY() + " " + bl.getBlockZ(), NamedTextColor.DARK_GRAY)));
            }
        }
    }

    /** Sneaking out of its eyes = free camera (kept near it). */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        if (switching || !watching.containsKey(e.getPlayer().getUniqueId())) return;
        free.add(e.getPlayer().getUniqueId());
        e.getPlayer().sendActionBar(Component.text("Free camera. /mimicplayer spectate = back in its eyes.", NamedTextColor.GRAY));
    }

    /** You changed game mode yourself: it ends where you are. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        if (switching || !watching.containsKey(e.getPlayer().getUniqueId())) return;
        watching.remove(e.getPlayer().getUniqueId());
        free.remove(e.getPlayer().getUniqueId());
        e.getPlayer().getPersistentDataContainer().remove(key);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (watching.containsKey(e.getPlayer().getUniqueId())) stop(e.getPlayer(), true); }

    /** A crash while spectating: back where they were on their next join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.getPersistentDataContainer().has(key) || watching.containsKey(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) stop(p, true); }, 5L);
    }
}
