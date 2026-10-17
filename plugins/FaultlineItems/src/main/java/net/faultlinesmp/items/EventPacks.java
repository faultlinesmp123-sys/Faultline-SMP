package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * EVENT SKY PACKS + THE PACK SHIELD (Items 1.2.59).
 *
 * While an event is on, everyone in its world (Java only) gets that event's own small pack on top of FaultlineSMP.zip,
 * and it's taken off again when the event ends:
 *  - blood-moon (FaultlineEvents' BLOOD_MOON on the main world): FaultlineBloodMoon.zip, the red moon
 *  - eclipse (Weather's Eclipse in its world): FaultlineEclipse.zip, a black sun with a burning corona where the moon is
 *  - aurora (Weather's Aurora in its world): FaultlineAurora.zip, green / violet aurora curtains for clouds
 * (made by tools/event_packs.py; url/hash under event-packs.<id>, the hash checked like the main pack's by PackHash).
 * Someone joining or arriving mid-event gets it once their main pack has loaded. Each pack's own id (EVENT_IDS) keeps it
 * apart from the main pack, so adding or removing it never touches the others.
 *
 * Any pack being added or removed makes the game reload all its packs: the loading screen. THE PACK SHIELD covers that
 * for every pack, ours or anyone's (the join-time packs too): from the moment a pack is sent (or the game says it ACCEPTED
 * one) until it reports it loaded (or failed), plus `grace-seconds`, that player
 *  - has no mobs spawning within `radius` of them (natural, spawners, patrols, reinforcements...; plugin / egg spawns aren't
 *    touched),
 *  - is ignored by every mob (targeting cancelled),
 *  - takes no damage at all.
 * A removed pack sends no "loaded" answer, so a removal is covered for `removal-seconds`; nothing is shielded longer than
 * `max-seconds`.
 */
final class EventPacks implements Listener {
    static final Map<String, UUID> EVENT_IDS = new LinkedHashMap<>();
    static {
        EVENT_IDS.put("blood-moon", UUID.fromString("b10d0000-6d6f-4f6e-8a1b-000000000001"));
        EVENT_IDS.put("eclipse", UUID.fromString("ec11b5e0-6d6f-4f6e-8a1b-000000000002"));
        EVENT_IDS.put("aurora", UUID.fromString("a0a0a000-6d6f-4f6e-8a1b-000000000003"));
    }
    static final String RAW = "https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/main/";
    static final Map<String, String[]> DEFAULTS = Map.of(
            "blood-moon", new String[]{RAW + "FaultlineBloodMoon.zip", "2bd923ab60ea489e8cd0b2940799b2e04dfb4ebe"},
            "eclipse", new String[]{RAW + "FaultlineEclipse.zip", "90ac668c24a2408a3170e471ef677f8e0ec7048c"},
            "aurora", new String[]{RAW + "FaultlineAurora.zip", "e60868fe219c3b070ccd8b92a319cc6c92daeab7"});
    /** What counts as a natural spawn the shield stops. */
    static final Set<String> BLOCKED = Set.of("NATURAL", "SPAWNER", "PATROL", "REINFORCEMENTS", "VILLAGE_INVASION", "JOCKEY", "MOUNT",
            "TRAP", "TRIAL_SPAWNER", "ENDER_PEARL", "SILVERFISH_BLOCK", "LIGHTNING", "SLIME_SPLIT", "RAID", "DEFAULT");

    final FaultlineItems plugin;
    /** The event packs each player has on now. */
    final Map<UUID, Set<String>> applied = new HashMap<>();
    /** Players whose main pack has finished loading (event packs wait for it, so they land on top of it). */
    final Set<UUID> ready = new HashSet<>();
    /** Pack ids each player is loading right now, and when their shield must end at the latest / ends after loading. */
    final Map<UUID, Set<UUID>> pending = new HashMap<>();
    final Map<UUID, Long> hardUntil = new HashMap<>(), graceUntil = new HashMap<>();
    /** Server ticks since start (the shield's clock). */
    long now;

    EventPacks(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> now++, 1L, 1L); // server ticks: lag stretches the shield, never shortens it
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[Event packs] " + e); }
        }, 40L, 20L);
    }

    double cfg(String path, double def) { return plugin.getConfig().getDouble(path, def); }

    String url(String id) { return plugin.getConfig().getString("event-packs." + id + ".url", DEFAULTS.get(id)[0]); }

    String hash(String id) {
        String h = plugin.getConfig().getString("event-packs." + id + ".hash", DEFAULTS.get(id)[1]);
        return PackHash.pick(url(id), h, "event-packs." + id + ".hash", plugin.getLogger());
    }

    boolean sends(String id) {
        return plugin.getConfig().getBoolean("event-packs.enabled", true) && plugin.getConfig().getBoolean("event-packs." + id + ".enabled", true)
                && url(id) != null && !url(id).isBlank();
    }

    // =========================================================================================== which events are on
    /** The world an event is on in now (null = it isn't on). */
    World eventWorld(String id) {
        if (id.equals("blood-moon")) return plugin.isEventActive("BLOOD_MOON") && !Bukkit.getWorlds().isEmpty() ? Bukkit.getWorlds().get(0) : null;
        if (plugin.weather == null) return null;
        Weather.Event e = plugin.weather.active.get(id);
        return e == null ? null : e.world;
    }

    void tick() {
        Map<String, World> on = new HashMap<>();
        for (String id : EVENT_IDS.keySet()) { World w = sends(id) ? eventWorld(id) : null; if (w != null) on.put(id, w); }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Edition.bedrock(p)) continue;
            Set<String> has = applied.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            for (String id : new HashSet<>(has)) if (!on.containsKey(id)) { has.remove(id); remove(p, id); } // the event is over
            boolean isReady = ready.contains(p.getUniqueId()) || !plugin.getConfig().getBoolean("item-textures.enabled", true) || p.getTicksLived() > 20 * 90;
            if (!isReady || shielded(p)) continue; // one load at a time
            for (var e : on.entrySet()) {
                if (has.contains(e.getKey()) || !p.getWorld().equals(e.getValue())) continue;
                has.add(e.getKey());
                send(p, e.getKey());
                break;
            }
        }
        // shields that ran out
        pending.keySet().removeIf(id -> hardUntil.getOrDefault(id, 0L) < now);
        hardUntil.keySet().removeIf(id -> hardUntil.get(id) < now && !pending.containsKey(id));
        graceUntil.keySet().removeIf(id -> graceUntil.get(id) < now);
    }

    void send(Player p, String id) {
        UUID pack = EVENT_IDS.get(id);
        try {
            String h = hash(id);
            byte[] bytes = h == null || h.isBlank() ? new byte[0] : java.util.HexFormat.of().parseHex(h);
            loading(p, pack, cfg("pack-shield.max-seconds", 120));
            p.addResourcePack(pack, url(id), bytes, null, false);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[Event packs] couldn't send the " + id + " pack to " + p.getName() + ": " + e.getMessage());
            done(p, pack);
        }
    }

    void remove(Player p, String id) {
        UUID pack = EVENT_IDS.get(id);
        try {
            // no "loaded" answer comes back for a removal: shield them for a while
            loading(p, pack, Math.min(cfg("pack-shield.max-seconds", 120), cfg("pack-shield.removal-seconds", 25)));
            p.removeResourcePack(pack);
        } catch (RuntimeException | NoSuchMethodError e) {
            done(p, pack);
        }
    }

    // =========================================================================================== the shield
    boolean shieldOn() { return plugin.getConfig().getBoolean("pack-shield.enabled", true); }

    void loading(Player p, UUID pack, double seconds) {
        boolean fresh = !pending.containsKey(p.getUniqueId());
        pending.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(pack);
        long until = now + (long) (seconds * 20);
        if (fresh) hardUntil.put(p.getUniqueId(), until); // a new load: the old cap doesn't carry over
        else hardUntil.merge(p.getUniqueId(), until, Math::max);
    }

    void done(Player p, UUID pack) {
        Set<UUID> s = pending.get(p.getUniqueId());
        if (s != null) { s.remove(pack); if (s.isEmpty()) { pending.remove(p.getUniqueId()); hardUntil.remove(p.getUniqueId()); } }
        graceUntil.put(p.getUniqueId(), now + (long) (cfg("pack-shield.grace-seconds", 3) * 20));
    }

    boolean shielded(Player p) {
        if (!shieldOn() || p == null) return false;
        UUID id = p.getUniqueId();
        return (pending.containsKey(id) && hardUntil.getOrDefault(id, 0L) >= now) || graceUntil.getOrDefault(id, 0L) >= now;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        UUID pack;
        try { pack = e.getID(); } catch (RuntimeException | NoSuchMethodError ex) { pack = new UUID(0, 0); }
        if (pack == null) pack = new UUID(0, 0);
        switch (e.getStatus().name()) {
            case "ACCEPTED", "DOWNLOADED" -> loading(p, pack, cfg("pack-shield.max-seconds", 120));
            case "SUCCESSFULLY_LOADED" -> {
                done(p, pack);
                if (pack.equals(FaultlineItems.ItemTextureManager.PACK_ID)) ready.add(p.getUniqueId());
            }
            default -> { // DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED
                done(p, pack);
                if (pack.equals(FaultlineItems.ItemTextureManager.PACK_ID)) ready.add(p.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!shieldOn() || pending.isEmpty() && graceUntil.isEmpty()) return;
        if (!(e.getEntity() instanceof Enemy) || !BLOCKED.contains(e.getSpawnReason().name())) return;
        Location at = e.getLocation();
        double r = cfg("pack-shield.radius", 96), r2 = r * r;
        for (Player p : at.getWorld().getPlayers()) {
            if (!shielded(p)) continue;
            double dx = p.getLocation().getX() - at.getX(), dz = p.getLocation().getZ() - at.getZ();
            if (dx * dx + dz * dz <= r2) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && shielded(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && shielded(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        applied.remove(id); ready.remove(id); pending.remove(id); hardUntil.remove(id); graceUntil.remove(id);
    }

    String status(Player p) {
        Set<String> has = applied.getOrDefault(p.getUniqueId(), Set.of());
        return ChatColor.GRAY + "Event packs on: " + ChatColor.WHITE + (has.isEmpty() ? "none" : String.join(", ", has))
                + ChatColor.GRAY + (shielded(p) ? " (shielded: loading)" : "");
    }
}
