package net.faultlinesmp.world;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * The Outer Rim at runtime (2.0):
 * <ul>
 *   <li>RIM LIGHTHOUSES: light the campfire in a lighthouse's lamp room (flint and steel, a fire charge) and it burns for
 *       {@code rim.lighthouse-minutes} (60). While lit, nothing hostile spawns naturally within {@link RimGen#LIGHT_RADIUS} and
 *       players there carry the metadata {@code faultline_rim_safe} (FaultlineItems skips the Rim pressure, static storms, fake
 *       players and gravity anomalies for them). Then it goes out. Lit lighthouses are kept in the world PDC
 *       ({@code faultlineworld:rim_lights} "x,z=until;...").</li>
 *   <li>LOST VILLAGERS: the first time a lost village's middle chunk loads, two villagers (tag {@code faultline_lost_villager},
 *       persistent, invulnerable, no AI) stand by the well. FaultlineItems gives them their trades.</li>
 *   <li>THE RIM CITADEL: a title the first time each session someone walks in.</li>
 * </ul>
 */
final class Rim implements Listener {
    static final String VILLAGER_TAG = "faultline_lost_villager";
    static final String SAFE_META = "faultline_rim_safe";

    private final FaultlineWorld plugin;
    private final NamespacedKey lightsKey, villagersKey;
    private final Map<String, Long> lit = new HashMap<>();   // "x,z" -> until (ms)
    private final Set<UUID> safe = new HashSet<>(), inCitadel = new HashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean loaded;

    Rim(FaultlineWorld plugin) {
        this.plugin = plugin;
        lightsKey = new NamespacedKey(plugin, "rim_lights");
        villagersKey = new NamespacedKey(plugin, "rim_villagers");
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::second, 60, 20));
    }

    void stop() {
        for (BukkitTask t : tasks) t.cancel();
        for (UUID id : safe) { Player p = Bukkit.getPlayer(id); if (p != null) p.removeMetadata(SAFE_META, plugin); }
        save();
    }

    private World main() {
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() instanceof WorldGen) return w;
        return null;
    }

    private Terrain terrain(World w) { return plugin.gen.terrain(w); }
    private int minutes() { return plugin.getConfig().getInt("rim.lighthouse-minutes", 60); }

    // ------------------------------------------------------------------ lighthouses

    private void load(World w) {
        if (loaded) return;
        loaded = true;
        String s = w.getPersistentDataContainer().get(lightsKey, PersistentDataType.STRING);
        if (s == null) return;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            try { lit.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
    }

    private void save() {
        World w = main();
        if (w == null) return;
        StringBuilder sb = new StringBuilder();
        for (var e : lit.entrySet()) { if (sb.length() > 0) sb.append(';'); sb.append(e.getKey()).append('=').append(e.getValue()); }
        if (sb.length() == 0) w.getPersistentDataContainer().remove(lightsKey);
        else w.getPersistentDataContainer().set(lightsKey, PersistentDataType.STRING, sb.toString());
    }

    /** True when a lit lighthouse covers (x, z). */
    boolean safeAt(World w, double x, double z) {
        if (lit.isEmpty() || w != main()) return false;
        long now = System.currentTimeMillis();
        for (int[] l : RimGen.lightsNear(terrain(w).seed, x, z, RimGen.LIGHT_RADIUS)) {
            Long until = lit.get(l[0] + "," + l[1]);
            if (until != null && until > now) return true;
        }
        return false;
    }

    private void second() {
        World w = main();
        if (w == null) return;
        load(w);
        Terrain t = terrain(w);
        long now = System.currentTimeMillis();
        boolean changed = false;
        // each lighthouse near a player: is its campfire lit? (players light it with flint and steel)
        Set<String> seen = new HashSet<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (!Layout.outerRim(l.getX(), l.getZ())) {
                if (safe.remove(p.getUniqueId())) p.removeMetadata(SAFE_META, plugin);
                inCitadel.remove(p.getUniqueId());
                continue;
            }
            for (int[] lh : RimGen.lightsNear(t.seed, l.getX(), l.getZ(), 64)) {
                String key = lh[0] + "," + lh[1];
                if (!seen.add(key)) continue;
                int[] lamp = RimGen.lamp(t, lh);
                if (!w.isChunkLoaded(lamp[0] >> 4, lamp[2] >> 4)) continue;
                Block b = w.getBlockAt(lamp[0], lamp[1], lamp[2]);
                if (!(b.getBlockData() instanceof Lightable fire) || b.getType() != Material.CAMPFIRE) continue;
                Long until = lit.get(key);
                if (fire.isLit() && (until == null || until <= now)) {
                    lit.put(key, now + minutes() * 60_000L);
                    changed = true;
                    for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(b.getLocation()) < 200 * 200) {
                        q.sendTitle(ChatColor.GOLD + "The lighthouse is lit", ChatColor.GRAY + "The Rim keeps away from it for an hour.", 10, 50, 15);
                        q.playSound(q.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.8f);
                    }
                } else if (until != null && until <= now) {
                    lit.remove(key);
                    changed = true;
                    if (fire.isLit()) { fire.setLit(false); b.setBlockData(fire); }
                    w.playSound(b.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 2f, 0.6f);
                } else if (until != null && fire.isLit() && now % 4 == 0) {
                    w.spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 2.5, 0.5), 6, 0.6, 1.5, 0.6, 0.02);
                }
            }
            boolean s = safeAt(w, l.getX(), l.getZ());
            if (s && safe.add(p.getUniqueId())) { p.setMetadata(SAFE_META, new FixedMetadataValue(plugin, true)); p.sendActionBar(ChatColor.GOLD + "A lit lighthouse keeps the Rim away."); }
            else if (!s && safe.remove(p.getUniqueId())) p.removeMetadata(SAFE_META, plugin);
            // the Citadel's title
            boolean in = RimGen.inCitadel(l.getX(), l.getZ());
            if (in && inCitadel.add(p.getUniqueId()))
                p.sendTitle(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "The Rim Citadel", ChatColor.GRAY + "Whoever held the edge of the world.", 10, 60, 20);
            else if (!in && RimGen.citadelDist(l.getX(), l.getZ()) > 120) inCitadel.remove(p.getUniqueId());
        }
        // lighthouses that ran out where nobody was
        for (Iterator<Map.Entry<String, Long>> it = lit.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() <= now - 3600_000L) { it.remove(); changed = true; }
        if (changed) save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (lit.isEmpty() || !(e.getEntity() instanceof Enemy)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.PATROL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        Location l = e.getLocation();
        if (Layout.outerRim(l.getX(), l.getZ()) && safeAt(l.getWorld(), l.getX(), l.getZ())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        safe.remove(e.getPlayer().getUniqueId());
        inCitadel.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeMetadata(SAFE_META, plugin);
    }

    // ------------------------------------------------------------------ lost villagers

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (w != main()) return;
        Chunk ch = e.getChunk();
        int bx = ch.getX() << 4, bz = ch.getZ() << 4;
        if (!Layout.outerRim(bx + 8, bz + 8) || ch.getPersistentDataContainer().has(villagersKey, PersistentDataType.BYTE)) return;
        ch.getPersistentDataContainer().set(villagersKey, PersistentDataType.BYTE, (byte) 1);
        Terrain t = terrain(w);
        for (int[] v : RimGen.villagesNear(t.seed, bx + 8, bz + 8)) {
            if (v[0] >> 4 != ch.getX() || v[1] >> 4 != ch.getZ()) continue;
            int g = Terrain.SEA + 18;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (int k = 0; k < 2; k++) {
                    Location at = new Location(w, v[0] + (k == 0 ? 3.5 : -2.5), g + 1, v[1] + 3.5);
                    Villager vi = (Villager) w.spawnEntity(at, EntityType.VILLAGER, CreatureSpawnEvent.SpawnReason.CUSTOM);
                    vi.addScoreboardTag(VILLAGER_TAG);
                    vi.setCustomName("Lost Villager");
                    vi.setCustomNameVisible(true);
                    vi.setPersistent(true);
                    vi.setRemoveWhenFarAway(false);
                    vi.setInvulnerable(true);
                    vi.setAI(false);
                    vi.setSilent(false);
                    try { vi.setProfession(Villager.Profession.NITWIT); } catch (Throwable ignored) { }
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(VILLAGER_TAG)) e.setCancelled(true);
    }
}
