package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * What players DO, for the hunting mimics (`/mimicplayer hunt`): minutes per world and per 64-block area of each world
 * (where people hang out), and every PORTAL a player has gone through (the block they stepped into and where they came
 * out), so a mimic can follow the same way. Saved in `mimic_world.yml`.
 */
final class MimicHunt implements Listener {
    static final int CELL = 64;
    private final FaultlineItems plugin;
    private final File file;
    final Map<String, Double> worldMinutes = new HashMap<>();
    final Map<String, Map<String, Double>> cells = new HashMap<>(); // world -> "cx,cz" -> minutes
    final List<Portal> portals = new ArrayList<>();
    private final Map<String, Location> strongholds = new HashMap<>(); // world -> an end portal frame found there
    private final Set<String> searching = new HashSet<>();
    private final Map<String, Location> centers = new HashMap<>(); // world -> its nearest stronghold (located)
    /** 1.2.93: the last portal each player took (a mimic hunting them takes the same one). */
    final Map<UUID, Portal> lastUsed = new HashMap<>();

    record Portal(Location from, Location to, Material type) { }

    MimicHunt(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mimic_world.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    /** Once a minute: each player in survival/adventure adds a minute to their world and their area of it. Older time fades slowly. */
    void sample() {
        for (Map.Entry<String, Double> e : worldMinutes.entrySet()) e.setValue(e.getValue() * 0.999); // ~2 days half-life
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            note(p.getLocation(), 1);
        }
    }

    void note(Location l, double minutes) {
        String w = l.getWorld().getName();
        worldMinutes.merge(w, minutes, Double::sum);
        String key = Math.floorDiv(l.getBlockX(), CELL) + "," + Math.floorDiv(l.getBlockZ(), CELL);
        cells.computeIfAbsent(w, k -> new HashMap<>()).merge(key, minutes, Double::sum);
    }

    /** The world players spend the most time in (of those loaded). */
    World favouriteWorld() {
        World best = null; double bm = -1;
        for (World w : Bukkit.getWorlds()) {
            double m = worldMinutes.getOrDefault(w.getName(), 0.0);
            if (m > bm) { bm = m; best = w; }
        }
        return best;
    }

    /** The middle of the area of that world players hang out in most (its surface), or its spawn. */
    Location hotspot(World w) {
        Map<String, Double> c = cells.get(w.getName());
        if (c == null || c.isEmpty()) return w.getSpawnLocation();
        String best = Collections.max(c.entrySet(), Map.Entry.comparingByValue()).getKey();
        String[] xz = best.split(",");
        int x = Integer.parseInt(xz[0]) * CELL + CELL / 2, z = Integer.parseInt(xz[1]) * CELL + CELL / 2;
        return new Location(w, x + 0.5, w.getSpawnLocation().getY(), z + 0.5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null || from.getWorld() == to.getWorld()) return;
        Block b = portalBlock(from);
        if (b == null) return;
        record(b.getLocation().add(0.5, 0, 0.5), to.clone(), b.getType());
        lastUsed.put(e.getPlayer().getUniqueId(), new Portal(b.getLocation().add(0.5, 0, 0.5), to.clone(), b.getType()));
    }

    /**
     * 1.2.93: the best recorded portal from this world into that one for a trip from `me` to `dest` (in `to`): the walk to it
     * plus the walk from where it comes out to `dest`. No dest = the nearest.
     */
    Portal bestPortal(World from, World to, Location me, Location dest) {
        Portal best = null; double bd = Double.MAX_VALUE;
        for (Portal p : portals) {
            if (p.from.getWorld() != from || p.to.getWorld() != to) continue;
            double d = flat(p.from, me) + (dest != null && dest.getWorld() == to ? flat(p.to, dest) : 0);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    static double flat(Location a, Location b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }

    void record(Location from, Location to, Material type) {
        portals.removeIf(p -> p.from.getWorld() == from.getWorld() && p.to.getWorld() == to.getWorld() && p.from.distanceSquared(from) < 16);
        portals.add(new Portal(from, to, type));
        while (portals.size() > 200) portals.remove(0);
    }

    /** The portal block a player stood in when the portal fired (they can be a block off). */
    static Block portalBlock(Location at) {
        Block f = at.getBlock();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
            Material t = f.getRelative(dx, dy, dz).getType();
            if (t == Material.NETHER_PORTAL || t == Material.END_PORTAL || t == Material.END_GATEWAY) return f.getRelative(dx, dy, dz);
        }
        return null;
    }

    /** The nearest portal players have taken from this world into that one. */
    Portal portalTo(World from, World to, Location near) {
        Portal best = null; double bd = Double.MAX_VALUE;
        for (Portal p : portals) {
            if (p.from.getWorld() != from || p.to.getWorld() != to) continue;
            double d = p.from.distanceSquared(near);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Any recorded route out of this world (e.g. the End's exit portal back to the Overworld). */
    Portal anyPortalTo(World from, World.Environment env, Location near) {
        Portal best = null; double bd = Double.MAX_VALUE;
        for (Portal p : portals) {
            if (p.from.getWorld() != from || p.to.getWorld().getEnvironment() != env) continue;
            double d = p.from.distanceSquared(near);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /**
     * No player has used an End portal here yet: the nearest STRONGHOLD (located once, cached), then its portal room found in
     * chunk snapshots off the main thread. `done` gets an END_PORTAL_FRAME's location, or null.
     */
    void findEndPortal(World w, Location near, Consumer<Location> done) {
        Location cached = strongholds.get(w.getName());
        if (cached != null) { done.accept(cached); return; }
        Location c;
        try {
            var r = w.locateNearestStructure(near, org.bukkit.generator.structure.StructureType.STRONGHOLD, 100, false);
            c = r == null ? null : r.getLocation();
        } catch (RuntimeException | LinkageError ex) { c = null; }
        if (c == null) { done.accept(null); return; }
        centers.put(w.getName(), c);
        scanAround(w, c, 5, done); // 1.2.93: 11x11 chunks (3 round missed portal rooms far off the stronghold's start)
    }

    /** 1.2.93: where the nearest stronghold is (known once it's been looked up), to head there while the portal room is found. */
    Location strongholdCenter(World w) { return centers.get(w.getName()); }

    /** Looks for an END_PORTAL_FRAME in chunk snapshots round `c` (r chunks round), off the main thread. */
    void scanAround(World w, Location c, int r, Consumer<Location> done) {
        if (!searching.add(w.getName())) { done.accept(null); return; }
        int cx = c.getBlockX() >> 4, cz = c.getBlockZ() >> 4;
        List<ChunkSnapshot> snaps = Collections.synchronizedList(new ArrayList<>());
        int[] left = {(2 * r + 1) * (2 * r + 1)};
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            w.getChunkAtAsync(cx + dx, cz + dz, true).thenAccept(ch -> {
                snaps.add(ch.getChunkSnapshot(false, false, false));
                if (--left[0] == 0) scan(w, snaps, done);
            }).exceptionally(ex -> { if (--left[0] == 0) scan(w, snaps, done); return null; });
        }
    }

    private void scan(World w, List<ChunkSnapshot> snaps, Consumer<Location> done) {
        int min = w.getMinHeight();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location found = null;
            outer:
            for (ChunkSnapshot s : new ArrayList<>(snaps)) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = min; y < 90; y++) {
                if (s.getBlockType(x, y, z) == Material.END_PORTAL_FRAME) { found = new Location(w, s.getX() * 16 + x + 0.5, y, s.getZ() * 16 + z + 0.5); break outer; }
            }
            Location f = found;
            Bukkit.getScheduler().runTask(plugin, () -> {
                searching.remove(w.getName());
                if (f != null) strongholds.put(w.getName(), f);
                done.accept(f);
            });
        });
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection wm = y.getConfigurationSection("worlds");
        if (wm != null) for (String k : wm.getKeys(false)) worldMinutes.put(k, wm.getDouble(k));
        ConfigurationSection cs = y.getConfigurationSection("cells");
        if (cs != null) for (String w : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(w);
            if (c == null) continue;
            Map<String, Double> m = new HashMap<>();
            for (String k : c.getKeys(false)) m.put(k.replace('_', ','), c.getDouble(k));
            cells.put(w, m);
        }
        for (Map<?, ?> p : y.getMapList("portals")) {
            try {
                World fw = Bukkit.getWorld(String.valueOf(p.get("from-world"))), tw = Bukkit.getWorld(String.valueOf(p.get("to-world")));
                if (fw == null || tw == null) continue;
                Location f = new Location(fw, num(p.get("fx")), num(p.get("fy")), num(p.get("fz")));
                Location t = new Location(tw, num(p.get("tx")), num(p.get("ty")), num(p.get("tz")));
                portals.add(new Portal(f, t, Material.valueOf(String.valueOf(p.get("type")))));
            } catch (RuntimeException ignored) { }
        }
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o)); }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Double> e : worldMinutes.entrySet()) y.set("worlds." + e.getKey(), Math.round(e.getValue() * 10) / 10.0);
        for (Map.Entry<String, Map<String, Double>> e : cells.entrySet()) {
            // keep the 300 busiest areas a world
            List<Map.Entry<String, Double>> top = new ArrayList<>(e.getValue().entrySet());
            top.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            for (Map.Entry<String, Double> c : top.subList(0, Math.min(300, top.size()))) y.set("cells." + e.getKey() + "." + c.getKey().replace(',', '_'), Math.round(c.getValue() * 10) / 10.0);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Portal p : portals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from-world", p.from.getWorld().getName()); m.put("fx", p.from.getX()); m.put("fy", p.from.getY()); m.put("fz", p.from.getZ());
            m.put("to-world", p.to.getWorld().getName()); m.put("tx", p.to.getX()); m.put("ty", p.to.getY()); m.put("tz", p.to.getZ());
            m.put("type", p.type.name());
            list.add(m);
        }
        y.set("portals", list);
        SafeYaml.write(plugin, file, y.saveToString()); // 1.3.19: atomic, off the main thread
    }
}
