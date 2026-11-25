package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * THE SPIDER NEST (Items 1.3.44, config spider-nest:, data spider_nests.yml). An admin makes one (/adminmenu's cobweb, or
 * /queenspider nest): a web-choked cave network carved under where they stand (28 blocks down from the surface, or right
 * there underground) after Terraria's spider caves: a THRONE CAVERN in the middle (the Queen sleeps on a mound of web and
 * bones), 10-14 chambers branching off it joined by tunnels, a sloping tunnel up to the surface. Walls of deepslate / tuff /
 * cobbled deepslate (only natural stone is replaced: ores stay, fluids next to it are walled off), cobwebs thick in every
 * corner and ceiling, hanging web columns, white silk threads (persistent BlockDisplays) slung across the chambers, glowing
 * egg clutches (ochre froglights), soul lanterns on chains, bones, skulls, cave spider spawners.
 * LOOT: generated cobwebs (chunk PDC `faultlineitems:nest_webs`) give a loot roll 18% of the time when broken (iron,
 * copper, gold, diamonds...); DEAD BODIES (a skeleton skull on bones, chunk PDC `nest_bodies`) give a big roll when broken;
 * DECORATED POTS hold a roll (break them); chests a double roll. Rolls can hold Cloud Potions, Darkness Potions and
 * accessories. Cobwebs players place never count.
 * THE QUEEN: QueenSpiderManager.sleepingQueen on the throne, asleep until hit; respawns `respawn-minutes` 120 after she dies,
 * once her throne is loaded and nobody is within 24. Her chunk unloading takes her away (she comes back asleep, healed).
 * Generation runs a slice a tick (`blocks-per-tick` 20000) after the chunks are loaded in the background.
 */
final class SpiderNest implements Listener {

    final FaultlineItems plugin;
    final QueenSpiderManager queens;
    private final File file;
    private final YamlConfiguration data;
    final Map<String, Nest> nests = new LinkedHashMap<>();
    private final Random random = new Random();
    private final NamespacedKey webKey, bodyKey;
    boolean building;
    final NestBrood brood; // 1.3.47: the brood, dens, quakes, dark, music

    static final class Nest {
        String id, world;
        int cx, cy, cz, tx, ty, tz, radius;
        int minX, minY, minZ, maxX, maxY, maxZ; // the box it was carved in (0s for 1.3.44 nests)
        final List<int[]> dens = new ArrayList<>(), sleeps = new ArrayList<>(); // 1.3.47: brood dens, sleeping beds
        final Map<Integer, Long> denReady = new HashMap<>(); // den index -> when it can wake again
        int mouthX = Integer.MIN_VALUE, mouthZ, mouthY = Integer.MIN_VALUE; // where a way in ends (1.3.49: underground)
        long respawnAt;
        transient QueenSpiderManager.Queen queen;
        Location throne(World w) { return new Location(w, tx + 0.5, ty, tz + 0.5); }
    }

    SpiderNest(FaultlineItems plugin, QueenSpiderManager queens) {
        this.plugin = plugin;
        this.queens = queens;
        queens.nests = this;
        brood = new NestBrood(plugin, this);
        webKey = new NamespacedKey(plugin, "nest_webs");
        bodyKey = new NamespacedKey(plugin, "nest_bodies");
        file = new File(plugin.getDataFolder(), "spider_nests.yml");
        data = SafeYaml.load(plugin, file).yaml;
        var sec = data.getConfigurationSection("nests");
        if (sec != null) for (String id : sec.getKeys(false)) {
            var c = sec.getConfigurationSection(id);
            Nest n = new Nest();
            n.id = id; n.world = c.getString("world");
            n.cx = c.getInt("cx"); n.cy = c.getInt("cy"); n.cz = c.getInt("cz");
            n.tx = c.getInt("tx"); n.ty = c.getInt("ty"); n.tz = c.getInt("tz");
            n.radius = c.getInt("radius", 80); n.respawnAt = c.getLong("respawn-at");
            n.minX = c.getInt("box.min-x"); n.minY = c.getInt("box.min-y"); n.minZ = c.getInt("box.min-z");
            n.maxX = c.getInt("box.max-x"); n.maxY = c.getInt("box.max-y"); n.maxZ = c.getInt("box.max-z");
            n.mouthX = c.getInt("mouth-x", Integer.MIN_VALUE); n.mouthZ = c.getInt("mouth-z"); n.mouthY = c.getInt("mouth-y", Integer.MIN_VALUE);
            for (String d : c.getStringList("dens")) n.dens.add(parse3(d));
            for (String d : c.getStringList("sleeps")) n.sleeps.add(parse3(d));
            var dr = c.getConfigurationSection("den-ready");
            if (dr != null) for (String k : dr.getKeys(false)) try { n.denReady.put(Integer.parseInt(k), dr.getLong(k)); } catch (NumberFormatException ignored) { }
            nests.put(id, n);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) cleanOld(c); }, 40L);
    }

    void save() {
        data.set("nests", null);
        for (Nest n : nests.values()) {
            String k = "nests." + n.id + ".";
            data.set(k + "world", n.world);
            data.set(k + "cx", n.cx); data.set(k + "cy", n.cy); data.set(k + "cz", n.cz);
            data.set(k + "tx", n.tx); data.set(k + "ty", n.ty); data.set(k + "tz", n.tz);
            data.set(k + "radius", n.radius); data.set(k + "respawn-at", n.respawnAt);
            if (n.maxX != n.minX) {
                data.set(k + "box.min-x", n.minX); data.set(k + "box.min-y", n.minY); data.set(k + "box.min-z", n.minZ);
                data.set(k + "box.max-x", n.maxX); data.set(k + "box.max-y", n.maxY); data.set(k + "box.max-z", n.maxZ);
            }
            if (n.mouthX != Integer.MIN_VALUE) { data.set(k + "mouth-x", n.mouthX); data.set(k + "mouth-z", n.mouthZ); data.set(k + "mouth-y", n.mouthY); }
            data.set(k + "dens", n.dens.stream().map(d -> d[0] + "," + d[1] + "," + d[2]).toList());
            data.set(k + "sleeps", n.sleeps.stream().map(d -> d[0] + "," + d[1] + "," + d[2]).toList());
            for (var e : n.denReady.entrySet()) data.set(k + "den-ready." + e.getKey(), e.getValue());
        }
        SafeYaml.write(plugin, file, data.saveToString());
    }

    static int[] parse3(String s) { String[] p = s.split(","); return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())}; }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("spider-nest." + k, d); }

    // ============================================================================================ the plan (pure)
    /** A room: an ellipsoid. A tunnel: a capsule between two room centres. */
    record Room(double x, double y, double z, double rx, double ry, double rz, String kind) {
        Room(double x, double y, double z, double rx, double ry, double rz) { this(x, y, z, rx, ry, rz, "plain"); }
        Room kind(String k) { return new Room(x, y, z, rx, ry, rz, k); }
        boolean holds(double px, double py, double pz, double pad) {
            double dx = (px - x) / (rx + pad), dy = (py - y) / (ry + pad), dz = (pz - z) / (rz + pad);
            return dx * dx + dy * dy + dz * dz < 1;
        }
    }
    /** 1.3.47: what each chamber is for (the throne is "throne"). */
    static final String[] KINDS = {"den", "den", "den", "den", "den", "den", "larder", "larder", "sleep", "sleep", "hatchery", "hatchery", "pillars", "pillars", "pillars"};
    record Tunnel(double ax, double ay, double az, double bx, double by, double bz, double r) { }

    static final class Plan {
        final List<Room> rooms = new ArrayList<>();
        final List<Tunnel> tunnels = new ArrayList<>();
        final List<Tunnel> entrance = new ArrayList<>(); // 1.3.46: the spiral way in (kept clear and lit)
        int mouthX, mouthZ, mouthY;
        final List<double[]> gates = new ArrayList<>(); // 1.3.49: the far ends of the gateway tunnels (x, y, z, heading)
        final long seed;
        int surface = Integer.MIN_VALUE; // the ground the way in comes up to (MIN = none)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Plan(long seed) { this.seed = seed; }

        void grow(double x, double y, double z, double r) {
            minX = Math.min(minX, (int) Math.floor(x - r) - 3); maxX = Math.max(maxX, (int) Math.ceil(x + r) + 3);
            minY = Math.min(minY, (int) Math.floor(y - r) - 3); maxY = Math.max(maxY, (int) Math.ceil(y + r) + 3);
            minZ = Math.min(minZ, (int) Math.floor(z - r) - 3); maxZ = Math.max(maxZ, (int) Math.ceil(z + r) + 3);
        }

        /** Cheap smooth 3D value noise in -1..1. */
        double noise(double x, double y, double z) {
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
            double fx = x - x0, fy = y - y0, fz = z - z0;
            fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
            double v = 0;
            for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
                double w = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
                v += w * hash(x0 + dx, y0 + dy, z0 + dz);
            }
            return v;
        }

        double hash(int x, int y, int z) {
            long h = seed ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
            h = (h ^ (h >>> 13)) * 0x5bd1e995L; h ^= h >>> 15;
            return ((h & 0xFFFF) / 32767.5) - 1;
        }

        boolean cave(int x, int y, int z) {
            double n = noise(x / 6.0, y / 5.0, z / 6.0) * 0.22 + noise(x / 2.5, y / 2.5, z / 2.5) * 0.08;
            for (Room r : rooms) {
                double dx = (x + 0.5 - r.x) / r.rx, dy = (y + 0.5 - r.y) / r.ry, dz = (z + 0.5 - r.z) / r.rz;
                double d = dx * dx + dy * dy * (dy < 0 ? 1.6 : 1) + dz * dz; // flatter floors
                if (d < 1 + n) return true;
            }
            for (Tunnel t : tunnels) if (inTunnel(t, x, y, z, n, 0)) return true;
            for (Tunnel t : entrance) if (inTunnel(t, x, y, z, n * 0.3, 0)) return true;
            return false;
        }

        boolean inEntrance(int x, int y, int z) {
            for (Tunnel t : entrance) if (inTunnel(t, x, y, z, 0, 1.5)) return true;
            return false;
        }

        static boolean inTunnel(Tunnel t, int x, int y, int z, double n, double pad) {
            {
                double vx = t.bx - t.ax, vy = t.by - t.ay, vz = t.bz - t.az, len2 = vx * vx + vy * vy + vz * vz;
                double px = x + 0.5 - t.ax, py = y + 0.5 - t.ay, pz = z + 0.5 - t.az;
                double k = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * vx + py * vy + pz * vz) / len2));
                double ex = px - vx * k, ey = py - vy * k, ez = pz - vz * k;
                double rr = t.r * (1 + n * 0.6) + pad;
                return ex * ex + ey * ey * 1.4 + ez * ez < rr * rr;
            }
        }
    }

    static double clampY(double y, double ry, int lo, int hi) { return Math.max(lo + ry + 2, Math.min(hi - ry - 2, y)); }

    /** The layout around (cx, cy, cz); `surface` = the y of the ground above for the way in (or Integer.MIN_VALUE). */
    static Plan plan(long seed, int cx, int cy, int cz, int sx, int sz, int surface) { return plan(seed, cx, cy, cz, sx, sz, surface, -50, -1); }

    /**
     * 1.3.47: bigger (18-24 chambers out to 110 blocks), every chamber between `lo` and `hi` (y -50 .. -1 by default), each
     * with a job: brood dens (trial-chamber waves), larders, sleeping dens, hatcheries, bone-pillar halls.
     */
    static Plan plan(long seed, int cx, int cy, int cz, int sx, int sz, int surface, int lo, int hi) {
        Plan p = new Plan(seed);
        Random r = new Random(seed);
        Room throne = new Room(cx + 0.5, clampY(cy, 11, lo, hi), cz + 0.5, 20, 11, 19, "throne");
        p.rooms.add(throne);
        p.grow(throne.x, throne.y, throne.z, 21);
        int n = 18 + r.nextInt(7);
        List<Room> placed = new ArrayList<>(List.of(throne));
        for (int i = 0; i < n * 2 && placed.size() <= n; i++) {
            Room from = placed.get(r.nextInt(placed.size()));
            boolean big = r.nextDouble() < 0.3;
            double rx = (big ? 10 : 6) + r.nextDouble() * 6, ry = (big ? 5.5 : 4) + r.nextDouble() * 3, rz = (big ? 10 : 6) + r.nextDouble() * 6;
            Room room = null;
            for (int tries = 0; tries < 24 && room == null; tries++) {
                double a = r.nextDouble() * Math.PI * 2, d = 22 + r.nextDouble() * 16;
                double x = from.x + Math.cos(a) * d, z = from.z + Math.sin(a) * d, y = clampY(from.y + (r.nextDouble() - 0.5) * 16, ry, lo, hi);
                if (Math.hypot(x - cx, z - cz) > 110) continue;
                boolean overlaps = false;
                for (Room o : placed) if (Math.hypot(o.x - x, o.z - z) < (o.rx + rx) * 0.8) overlaps = true;
                if (!overlaps) room = new Room(x, y, z, rx, ry, rz);
            }
            if (room == null) continue;
            placed.add(room);
            p.rooms.add(room);
            p.grow(room.x, room.y, room.z, Math.max(room.rx, room.rz) + 1);
            p.tunnels.add(new Tunnel(from.x, from.y - from.ry * 0.4, from.z, room.x, room.y - room.ry * 0.4, room.z, 2.4 + r.nextDouble() * 1.4));
        }
        for (int i = 0; i < 6 && placed.size() > 3; i++) { // loops, so it's a network, not a tree
            Room a = placed.get(1 + r.nextInt(placed.size() - 1)), b = placed.get(1 + r.nextInt(placed.size() - 1));
            if (a != b && Math.hypot(a.x - b.x, a.z - b.z) < 45) p.tunnels.add(new Tunnel(a.x, a.y - a.ry * 0.4, a.z, b.x, b.y - b.ry * 0.4, b.z, 2.3));
        }
        // the jobs: the biggest rooms get the pillar halls, the rest shuffled
        List<Integer> idx = new ArrayList<>();
        for (int i = 1; i < p.rooms.size(); i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(p.rooms.get(b).rx * p.rooms.get(b).rz, p.rooms.get(a).rx * p.rooms.get(a).rz));
        List<String> jobs = new ArrayList<>(List.of(KINDS));
        List<String> rest = new ArrayList<>(jobs.subList(0, jobs.size() - 3));
        java.util.Collections.shuffle(rest, r);
        for (int k = 0; k < idx.size(); k++) {
            String kind = k < 3 ? "pillars" : k - 3 < rest.size() ? rest.get(k - 3) : "plain";
            p.rooms.set(idx.get(k), p.rooms.get(idx.get(k)).kind(kind));
        }
        p.surface = surface;
        // 1.3.49: the ways in are UNDERGROUND only (no hole in the surface): 4 GATEWAY tunnels run out from the outermost chambers
        // in four directions, 44 blocks each, wandering and rising a little, into the rock round the nest (where they meet the
        // caves already down there); a bone gate stands at each one's far end.
        // 1.3.49: 3 GATEWAYS, all underground, from the outermost chambers (never near the throne) winding out 80-110 blocks
        // beyond the nest: you come in from far off and have to find your way through it to her.
        double a0 = r.nextDouble() * Math.PI * 2;
        for (int g = 0; g < 3; g++) {
            double ang = a0 + g * Math.PI * 2 / 3;
            double dx0 = Math.cos(ang), dz0 = Math.sin(ang);
            Room from = null; double best = -1e9;
            for (Room o : p.rooms) {
                if (o == throne || Math.hypot(o.x - throne.x, o.z - throne.z) < 45) continue;
                double proj = (o.x - cx) * dx0 + (o.z - cz) * dz0;
                if (proj > best) { best = proj; from = o; }
            }
            if (from == null) continue;
            double px = from.x + dx0 * from.rx * 0.6, pz = from.z + dz0 * from.rz * 0.6, py = from.y - from.ry * 0.4;
            double heading = ang;
            int segs = 13 + r.nextInt(5);
            for (int i = 0; i < segs; i++) {
                heading += (r.nextDouble() - 0.5) * 0.6;
                double nx = px + Math.cos(heading) * 6.5, nz = pz + Math.sin(heading) * 6.5;
                double ny = Math.max(lo + 4, Math.min(hi - 5, py + (r.nextDouble() - 0.45) * 2.2));
                p.entrance.add(new Tunnel(px, py, pz, nx, ny, nz, 2.4));
                p.grow(nx, ny, nz, 4);
                px = nx; py = ny; pz = nz;
            }
            p.gates.add(new double[]{px, py, pz, heading});
            if (p.mouthX == 0 && p.mouthZ == 0) { p.mouthX = (int) Math.floor(px); p.mouthZ = (int) Math.floor(pz); p.mouthY = (int) Math.floor(py); }
        }
        return p;
    }

    // ============================================================================================ building it
    static final Set<Material> NATURAL = Set.of(Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DIRT, Material.GRAVEL, Material.TUFF, Material.CALCITE, Material.COBBLESTONE, Material.SAND, Material.SANDSTONE,
            Material.CLAY, Material.SMOOTH_BASALT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MOSS_BLOCK, Material.DRIPSTONE_BLOCK,
            Material.GRASS_BLOCK, Material.PODZOL, Material.MUD, Material.INFESTED_STONE, Material.INFESTED_DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.MOSSY_COBBLESTONE);

    /**
     * 1.3.45: what carving may remove: natural stone, ores, dirt and gravel, fluids, plants, amethyst, dripstone, sculk...
     * NEVER bedrock, players' builds, chests or other block entities, or other structures (trial chambers, mineshafts,
     * strongholds, ancient cities): those stay standing inside the cave. (1.3.44 turned every block in the way into air.)
     */
    static boolean carvable(Material m) {
        if (NATURAL.contains(m) || m.isAir()) return true;
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
        String n = m.name();
        if (n.endsWith("_ORE")) return true;
        if (n.startsWith("AMETHYST") || n.endsWith("_AMETHYST_BUD") || n.equals("BUDDING_AMETHYST") || n.equals("AMETHYST_CLUSTER")) return true;
        if (n.contains("DRIPSTONE") || n.startsWith("SCULK") && !n.equals("SCULK_SHRIEKER") || n.equals("GLOW_LICHEN") || n.equals("MOSS_CARPET")
                || n.equals("MAGMA_BLOCK") || n.equals("POWDER_SNOW") || n.equals("PACKED_ICE") || n.equals("ICE") || n.equals("SNOW_BLOCK")
                || n.equals("RED_SAND") || n.equals("TERRACOTTA") || n.endsWith("_TERRACOTTA") && !n.contains("GLAZED")) return true;
        // plants and other soft natural things (vines, roots, kelp, grass...): anything not solid
        return !m.isSolid() && !m.isInteractable() && !n.contains("RAIL") && !n.contains("TORCH") && !n.contains("SIGN") && !n.contains("BANNER")
                && !n.contains("CARPET") && !n.contains("PRESSURE_PLATE") && !n.contains("BUTTON") && !n.contains("REDSTONE") && !n.equals("COBWEB") && !n.contains("LADDER");
    }

    /** /queenspider nest (or the admin menu): makes one under this player. */
    void create(Player p) { create(p, false); }

    void create(Player p, boolean force) {
        if (building) { p.sendMessage(ChatColor.RED + "A nest is already being built."); return; }
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) { p.sendMessage(ChatColor.RED + "Spider Nests go in the Overworld."); return; }
        Location l = p.getLocation();
        if (!force) for (Nest o : nests.values()) // 1.3.46: one nest per area (clicking the menu twice made two nests and two queens)
            if (o.world.equals(w.getName()) && Math.hypot(o.cx - l.getX(), o.cz - l.getZ()) < o.radius + 90) {
                p.sendMessage(ChatColor.RED + "There's already a nest here (" + o.id + "). " + ChatColor.GRAY + "/queenspider nest delete " + o.id + " removes it, or /queenspider nest here force.");
                return;
            }
        building = true;
        tryAt(p, w, l.getBlockX(), l.getBlockZ(), 0);
    }

    /** Spots tried round the admin, in order (1.3.49): here, then further out in rings, until one is clear of structures. */
    static final int[][] TRIES = {{0, 0}, {180, 0}, {-180, 0}, {0, 180}, {0, -180}, {180, 180}, {-180, -180}, {180, -180}, {-180, 180},
            {360, 0}, {-360, 0}, {0, 360}, {0, -360}};

    /** The structures a nest never cuts into (`spider-nest.avoid-structures`). */
    private Set<String> avoid() {
        List<String> l = plugin.getConfig().getStringList("spider-nest.avoid-structures");
        if (l.isEmpty()) l = List.of("trial_chambers", "ancient_city", "stronghold");
        Set<String> out = new HashSet<>();
        for (String x : l) out.add(x.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", ""));
        return out;
    }

    /** A structure from the avoid list in the nest's box (the chunks are loaded), or null. */
    private String blocked(World w, Plan plan) {
        Set<String> av = avoid();
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(plan.minX - 8, plan.minY - 8, plan.minZ - 8, plan.maxX + 8, plan.maxY + 8, plan.maxZ + 8);
        try {
            for (int x = plan.minX >> 4; x <= plan.maxX >> 4; x++) for (int z = plan.minZ >> 4; z <= plan.maxZ >> 4; z++)
                for (var gs : w.getStructures(x, z)) {
                    String key = gs.getStructure().getKey().getKey();
                    if (av.contains(key) && gs.getBoundingBox().overlaps(box)) return key.replace('_', ' ');
                }
        } catch (RuntimeException | LinkageError e) { return null; } // can't look (an old server / a test): build anyway
        return null;
    }

    private void tryAt(Player p, World w, int bx, int bz, int attempt) {
        int sx = bx + TRIES[attempt][0], sz = bz + TRIES[attempt][1];
        int surface = w.getHighestBlockYAt(sx, sz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        // 1.3.47: always between y -50 and -1 (`min-y` / `max-y`), whatever the admin stands on
        int lo = Math.max(w.getMinHeight() + 4, (int) cfg("min-y", -50)), hi = Math.max(lo + 30, (int) cfg("max-y", -1));
        int cy = (lo + hi) / 2 - 2;
        long seed = random.nextLong();
        Plan plan = plan(seed, sx, cy, sz, sx, sz, surface, lo, hi);
        if (attempt == 0) p.sendMessage(ChatColor.DARK_PURPLE + "Weaving a Spider Nest between y " + lo + " and " + hi + " under you (" + plan.rooms.size() + " chambers)...");
        List<java.util.concurrent.CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int x = plan.minX >> 4; x <= plan.maxX >> 4; x++) for (int z = plan.minZ >> 4; z <= plan.maxZ >> 4; z++) {
            try { loads.add(w.getChunkAtAsync(x, z, true)); } catch (RuntimeException e) { loads.add(java.util.concurrent.CompletableFuture.completedFuture(w.getChunkAt(x, z))); }
        }
        java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String hit = blocked(w, plan);
                    if (hit != null) {
                        if (attempt + 1 < TRIES.length) {
                            p.sendMessage(ChatColor.GRAY + "A " + hit + " is in the way there: moving the nest further out...");
                            tryAt(p, w, bx, bz, attempt + 1);
                        } else {
                            building = false;
                            p.sendMessage(ChatColor.RED + "Every spot near you hits a " + hit + ". Try somewhere else.");
                        }
                        return;
                    }
                    List<Chunk> held = new ArrayList<>();
                    for (var f : loads) try { Chunk c = f.getNow(null); if (c != null && c.addPluginChunkTicket(plugin)) held.add(c); } catch (RuntimeException ignored) { }
                    if (attempt > 0) p.sendMessage(ChatColor.DARK_PURPLE + "Building it at " + sx + " " + sz + " instead (clear of structures).");
                    build(p, w, plan, sx, sz, held);
                }));
    }

    // ---------------------------------------------------------------- every block the nest changes is recorded (its undo file)
    private Map<Long, BlockData> undo;

    private void put(Block b, BlockData d) {
        if (undo != null) undo.putIfAbsent(key(b.getX(), b.getY(), b.getZ()), b.getBlockData());
        b.setBlockData(d, false);
    }

    private void put(Block b, Material m) {
        if (undo != null) undo.putIfAbsent(key(b.getX(), b.getY(), b.getZ()), b.getBlockData());
        b.setType(m, false);
    }

    static int kx(long k) { return (int) (k >> 38) << 6 >> 6; }
    static int kz(long k) { return (int) ((k >> 12) & 0x3FFFFFF) << 6 >> 6; }
    static int ky(long k) { return (int) (k & 0xFFF) << 20 >> 20; }

    File undoFile(String id) { return new File(plugin.getDataFolder(), "spider_nests/" + id + ".undo.gz"); }

    /** Carves and decorates, a slice a tick; then the throne, the queen and the save. */
    void build(CommandSender by, World w, Plan plan, int sx, int sz, List<Chunk> held) {
        Random r = new Random(plan.seed);
        undo = new LinkedHashMap<>();
        buildId = "nest" + (nests.size() + 1) + "_" + Integer.toHexString(new Random(plan.seed ^ 77).nextInt(0xFFFF));
        List<int[]> air = new ArrayList<>();
        Set<Long> airSet = new HashSet<>();
        for (int x = plan.minX; x <= plan.maxX; x++) for (int z = plan.minZ; z <= plan.maxZ; z++) for (int y = plan.minY; y <= plan.maxY; y++) {
            if (y <= w.getMinHeight() + 2 || y >= w.getMaxHeight() - 2) continue;
            if (plan.cave(x, y, z)) { air.add(new int[]{x, y, z}); airSet.add(key(x, y, z)); }
        }
        int per = (int) cfg("blocks-per-tick", 20000);
        int[] stage = {0}, idx = {0};
        Map<String, List<Integer>> webs = new HashMap<>(), bodies = new HashMap<>();
        BlockData caveAir = Material.CAVE_AIR.createBlockData();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                int done = 0;
                if (stage[0] == 0) { // carve
                    while (idx[0] < air.size() && done < per) {
                        int[] c = air.get(idx[0]++);
                        Block b = w.getBlockAt(c[0], c[1], c[2]);
                        Material m = b.getType();
                        if (m.isAir() && c[1] < (plan.surface == Integer.MIN_VALUE ? Integer.MAX_VALUE : plan.surface)) { if (m != Material.CAVE_AIR) put(b, caveAir); }
                        else if (!m.isAir() && carvable(m)) put(b, caveAir);
                        done++;
                    }
                    if (idx[0] >= air.size()) { stage[0] = 1; idx[0] = 0; }
                } else if (stage[0] == 1) { // walls, floors, ceilings
                    while (idx[0] < air.size() && done < per / 6) {
                        int[] c = air.get(idx[0]++);
                        for (int[] d : SIX) {
                            int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                            if (airSet.contains(key(x, y, z))) continue;
                            Block b = w.getBlockAt(x, y, z);
                            Material m = b.getType();
                            boolean deep = plan.surface == Integer.MIN_VALUE || y < plan.surface - 4;
                            if (b.isLiquid() || m == Material.WATER || m == Material.LAVA) put(b, Material.TUFF);
                            else if (m.isAir()) { if (deep) put(b, Material.DEEPSLATE); } // a natural cave next door: wall it (never the way in at the surface)
                            else if (NATURAL.contains(m) && deep) put(b, wallFor(plan, x, y, z, d[1], r));
                            done++;
                        }
                    }
                    if (idx[0] >= air.size()) { stage[0] = 2; idx[0] = 0; }
                } else if (stage[0] == 2) { // decorate
                    while (idx[0] < air.size() && done < per / 4) {
                        int[] c = air.get(idx[0]++);
                        decorate(w, plan, airSet, c, r, webs, bodies);
                        done++;
                    }
                    if (idx[0] >= air.size()) stage[0] = 3;
                } else {
                    task.cancel();
                    finish(by, w, plan, airSet, r, webs, bodies, held, sx, sz);
                }
            } catch (RuntimeException e) {
                task.cancel();
                building = false;
                undo = null;
                for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] building failed", e);
                by.sendMessage(ChatColor.RED + "The nest couldn't be finished: " + e.getMessage());
            }
        }, 1L, 1L);
    }

    /** The nest's own stone: floors of gravel / coarse dirt / tuff, ceilings with silk sheets, walls of deepslate with calcite veins. */
    private static Material wallFor(Plan plan, int x, int y, int z, int dy, Random r) {
        double n1 = plan.noise(x / 4.0, y / 4.0, z / 4.0), n2 = plan.noise(x / 7.0 + 50, y / 5.0, z / 7.0 + 50);
        if (dy < 0) { // the floor under a cave cell
            if (n1 > 0.32) return Material.COARSE_DIRT;
            if (n1 < -0.36) return Material.GRAVEL;
            if (n2 > 0.4) return Material.TUFF;
            return r.nextDouble() < 0.12 ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE;
        }
        if (dy > 0 && n2 > 0.5) return Material.WHITE_WOOL; // silk sheets stuck to the ceiling
        if (Math.abs(plan.noise(x / 9.0 - 30, y / 3.0, z / 9.0 + 7)) < 0.035) return Material.CALCITE; // pale veins
        if (n1 > 0.38) return Material.TUFF;
        if (n1 < -0.42) return Material.COBBLED_DEEPSLATE;
        if (n2 < -0.5 && dy == 0) return Material.WHITE_WOOL; // silk plastered on the walls
        return r.nextDouble() < 0.05 ? Material.DEEPSLATE_TILES : Material.DEEPSLATE;
    }

    static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    static final org.bukkit.block.BlockFace[] FACES4 = {org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.NORTH};

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    // ---------------------------------------------------------------- 1.3.47: decoration (dark: the eggs glow but light nothing)
    String buildId = "nest";
    final List<int[]> buildDens = new ArrayList<>(), buildSleeps = new ArrayList<>();
    static final String WEB_ITEM_TAG = "faultline_nest_webitem";
    final List<org.bukkit.entity.ItemDisplay> buildWebItems = new ArrayList<>();

    static Room roomAt(Plan plan, int x, int y, int z) {
        for (Room rm : plan.rooms) if (rm.holds(x + 0.5, y + 0.5, z + 0.5, 1.5)) return rm;
        return null;
    }

    private void decorate(World w, Plan plan, Set<Long> air, int[] c, Random r, Map<String, List<Integer>> webs, Map<String, List<Integer>> bodies) {
        int x = c[0], y = c[1], z = c[2];
        if (plan.surface != Integer.MIN_VALUE && y >= plan.surface - 1) return; // nothing in the open air at the way in
        boolean floor = !air.contains(key(x, y - 1, z)), ceil = !air.contains(key(x, y + 1, z));
        int walls = 0;
        org.bukkit.block.BlockFace wallFace = null;
        for (int i = 0; i < 4; i++) {
            int[] d = SIX[i < 2 ? i : i + 2];
            if (!air.contains(key(x + d[0], y, z + d[2]))) { walls++; wallFace = FACES4[i]; }
        }
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isAir()) return;
        double roll = r.nextDouble();
        if (plan.inEntrance(x, y, z)) { // the way in: kept walkable, a few soul lanterns, webs in the corners
            if (ceil && roll < 0.03 && air.contains(key(x, y - 1, z)) && air.contains(key(x, y - 2, z))) {
                put(b, chain());
                put(b.getRelative(0, -1, 0), hangingLantern());
            } else if (!floor && walls >= 2 && roll < 0.08) web(b, r, webs, false);
            else if (floor && walls >= 1 && roll < 0.01) put(b, Material.SKELETON_SKULL);
            return;
        }
        Room rm = roomAt(plan, x, y, z);
        String kind = rm == null ? "tunnel" : rm.kind();
        if (kind.equals("throne") && Math.hypot(x + 0.5 - rm.x(), z + 0.5 - rm.z()) < 11) return; // her nest is built in finish()
        if (floor) {
            Block under = b.getRelative(0, -1, 0);
            if (under.getType().isAir() || !under.getType().isSolid()) return;
            double egg = kind.equals("hatchery") ? 0.06 : kind.equals("sleep") ? 0.015 : 0.005;
            double bones = kind.equals("larder") ? 0.05 : 0.006, corpse = kind.equals("larder") ? 0.025 : 0.004;
            if (roll < egg) eggClutch(b, air, r, webs);
            else if ((roll -= egg) < bones) put(b, Material.BONE_BLOCK);
            else if ((roll -= bones) < corpse) { body(b, r); mark(bodies, b); }
            else if ((roll -= corpse) < 0.005) pot(b, r);
            else if ((roll -= 0.005) < 0.006) put(b, r.nextBoolean() ? Material.BROWN_MUSHROOM : Material.DEAD_BUSH);
            else if ((roll -= 0.006) < 0.03) dripstone(w, air, b, false, 1 + r.nextInt(walls >= 1 ? 3 : 2));
            else if ((roll -= 0.03) < 0.07 + walls * 0.09 + (kind.equals("sleep") ? 0.1 : 0)) web(b, r, webs, true);
            else if (roll < 0.1 + walls * 0.09) put(b, Material.WHITE_CARPET); // silk on the floor
        } else if (ceil) {
            double cocoon = kind.equals("larder") ? 0.05 : 0.008;
            if (roll < 0.0012) { // a rare soul lantern on a chain (it's dark down here)
                Block l = b;
                int len = 1 + r.nextInt(3);
                for (int i = 0; i < len && air.contains(key(x, l.getY() - 1, z)); i++) { put(l, chain()); l = l.getRelative(0, -1, 0); }
                put(l, hangingLantern());
            } else if ((roll -= 0.0012) < cocoon && air.contains(key(x, y - 1, z)) && air.contains(key(x, y - 2, z)) && air.contains(key(x, y - 3, z)) && air.contains(key(x, y - 4, z))) {
                cocoon(b, r, bodies, webs, kind.equals("larder")); // a wrapped body hanging from the ceiling (cut it open: a body's loot)
            } else if ((roll -= cocoon) < 0.035) { // a hanging column of web
                Block l = b;
                int len = 2 + r.nextInt(6);
                for (int i = 0; i < len && l.getType().isAir() && air.contains(key(x, l.getY(), z)); i++) { web(l, r, webs, false); l = l.getRelative(0, -1, 0); }
            } else if ((roll -= 0.035) < 0.08) dripstone(w, air, b, true, 1 + r.nextInt(3));
            else if (roll < 0.22 + walls * 0.1) web(b, r, webs, true);
        } else if (walls >= 2 && roll < 0.33) web(b, r, webs, true);
        else if (walls == 1 && roll < 0.04) web(b, r, webs, true);
        else if (walls >= 1 && roll < 0.048 && wallFace != null) lichen(b, wallFace);
    }

    /** A cobweb (a loot web); sometimes something is caught in it, and you can SEE it hanging in the web. */
    private void web(Block b, Random r, Map<String, List<Integer>> webs, boolean mayHold) {
        put(b, Material.COBWEB);
        mark(webs, b);
        if (mayHold && r.nextDouble() < cfg("web-item-chance", 0.035)) {
            List<ItemStack> l = roll(r, 0);
            if (!l.isEmpty()) webItem(b, l.get(0), r);
        }
    }

    void webItem(Block b, ItemStack item, Random r) {
        Location at = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        float yaw = (float) (r.nextDouble() * Math.PI * 2), tilt = (float) ((r.nextDouble() - 0.5) * 0.9);
        buildWebItems.add(b.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.addScoreboardTag(SILK_TAG); d.addScoreboardTag(SILK_TAG + "_" + buildId); d.addScoreboardTag(WEB_ITEM_TAG);
            d.setItemStack(item);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(7, 0));
            d.setViewRange(0.35f);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(yaw).rotateZ(tilt), new Vector3f(0.42f, 0.42f, 0.42f), new Quaternionf()));
        }));
    }

    private static Material chain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private static BlockData hangingLantern() {
        BlockData lan = Material.SOUL_LANTERN.createBlockData();
        if (lan instanceof org.bukkit.block.data.type.Lantern ln) ln.setHanging(true);
        return lan;
    }

    /** A clutch of eggs: 2-5 glowing egg displays in a heap (they glow, but light nothing: the nest stays dark), web over it. */
    private void eggClutch(Block b, Set<Long> air, Random r, Map<String, List<Integer>> webs) {
        Material egg = r.nextDouble() < 0.65 ? Material.OCHRE_FROGLIGHT : Material.PEARLESCENT_FROGLIGHT;
        int n = 2 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            float s = 0.28f + r.nextFloat() * 0.22f;
            double ox = 0.15 + r.nextDouble() * (0.7 - s), oz = 0.15 + r.nextDouble() * (0.7 - s), oy = i > 2 ? 0.25 : 0;
            Location at = new Location(b.getWorld(), b.getX() + ox, b.getY() + oy, b.getZ() + oz);
            b.getWorld().spawn(at, BlockDisplay.class, d -> {
                d.setPersistent(true);
                d.addScoreboardTag(SILK_TAG); d.addScoreboardTag(SILK_TAG + "_" + buildId);
                d.setBlock(egg.createBlockData());
                d.setBrightness(new org.bukkit.entity.Display.Brightness(10, 0));
                d.setViewRange(0.4f);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(r.nextFloat()), new Vector3f(s, s * 1.15f, s), new Quaternionf()));
            });
        }
        Block up = b.getRelative(0, 1, 0);
        if (r.nextDouble() < 0.5 && up.getType().isAir() && air.contains(key(up.getX(), up.getY(), up.getZ()))) web(up, r, webs, false);
        else if (r.nextDouble() < 0.6) web(b, r, webs, false);
    }

    /** A cocoon: a web strand, two blocks of wool (the wrapped body), in larders a head poking out of it. The lower wool is a body. */
    private void cocoon(Block top, Random r, Map<String, List<Integer>> bodies, Map<String, List<Integer>> webs, boolean head) {
        Block l = top;
        if (r.nextBoolean()) { put(l, Material.COBWEB); mark(webs, l); l = l.getRelative(0, -1, 0); }
        put(l, Material.WHITE_WOOL);
        Block low = l.getRelative(0, -1, 0);
        put(low, Material.WHITE_WOOL);
        mark(bodies, low);
        Block drip = low.getRelative(0, -1, 0);
        if (head && drip.getType().isAir()) {
            Material[] heads = {Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.CREEPER_HEAD, Material.PIGLIN_HEAD, Material.ZOMBIE_HEAD};
            BlockData hd = heads[r.nextInt(heads.length)].createBlockData();
            if (hd instanceof Rotatable rot) rot.setRotation(org.bukkit.block.BlockFace.values()[r.nextInt(4)]);
            put(drip, hd);
        } else if (r.nextDouble() < 0.5 && drip.getType().isAir()) { put(drip, Material.COBWEB); mark(webs, drip); }
    }

    /** A dripstone spike `len` long hanging down from a ceiling cell or standing up from a floor cell. */
    private void dripstone(World w, Set<Long> air, Block start, boolean down, int len) {
        String[] th = len >= 3 ? new String[]{"base", "middle", "frustum", "tip"} : len == 2 ? new String[]{"frustum", "tip"} : new String[]{"tip"};
        int dir = down ? -1 : 1;
        Block b = start;
        for (int i = 0; i < th.length; i++) {
            if (!b.getType().isAir() || !air.contains(key(b.getX(), b.getY(), b.getZ()))) return;
            Block next = b.getRelative(0, dir, 0);
            String t = th[i];
            if (i < th.length - 1 && (!next.getType().isAir() || !air.contains(key(next.getX(), next.getY(), next.getZ())))) t = "tip";
            try { put(b, Bukkit.createBlockData("minecraft:pointed_dripstone[vertical_direction=" + (down ? "down" : "up") + ",thickness=" + t + "]")); }
            catch (RuntimeException | LinkageError e) { return; }
            if (t.equals("tip")) return;
            b = next;
        }
    }

    private void lichen(Block b, org.bukkit.block.BlockFace face) {
        try {
            BlockData d = Material.GLOW_LICHEN.createBlockData();
            if (d instanceof org.bukkit.block.data.MultipleFacing mf) { mf.setFace(face, true); put(b, mf); }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private void mark(Map<String, List<Integer>> map, Block b) {
        Chunk c = b.getChunk();
        map.computeIfAbsent(c.getX() + "," + c.getZ(), k -> new ArrayList<>()).add(local(b));
    }

    static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }

    private void body(Block b, Random r) {
        BlockData skull = Material.SKELETON_SKULL.createBlockData();
        if (skull instanceof Rotatable rot) rot.setRotation(org.bukkit.block.BlockFace.values()[r.nextInt(4)]);
        put(b, skull);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Block n = b.getRelative(d[0], 0, d[1]);
            if (n.getType().isAir() && n.getRelative(0, -1, 0).getType().isSolid() && r.nextDouble() < 0.5)
                put(n, r.nextBoolean() ? Material.BONE_BLOCK : Material.COBWEB);
        }
    }

    private void pot(Block b, Random r) {
        put(b, Material.DECORATED_POT);
        try {
            if (b.getState() instanceof DecoratedPot pot) {
                List<ItemStack> l = roll(r, 1);
                if (!l.isEmpty()) pot.getSnapshotInventory().setItem(l.get(0)); // the snapshot: update() writes it (the live one would be overwritten)
                pot.update(true, false);
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    /** The floor (top air cell over solid ground) of a column in the cave, scanning down from y. */
    private static Block floorAt(World w, Set<Long> air, int x, int y, int z, int range) {
        for (int yy = y; yy > y - range; yy--)
            if (air.contains(key(x, yy, z)) && !air.contains(key(x, yy - 1, z)) && w.getBlockAt(x, yy - 1, z).getType().isSolid()) return w.getBlockAt(x, yy, z);
        return null;
    }

    private void spawner(Block f, EntityType type) {
        put(f, Material.SPAWNER);
        try { if (f.getState() instanceof CreatureSpawner sp) { sp.setSpawnedType(type); sp.update(true, false); } } catch (RuntimeException ignored) { }
    }

    private void finish(CommandSender by, World w, Plan plan, Set<Long> air, Random r, Map<String, List<Integer>> webs, Map<String, List<Integer>> bodies,
                        List<Chunk> held, int sx, int sz) {
        Room throne = plan.rooms.get(0);
        String id = buildId;
        int tx = (int) Math.floor(throne.x), tz = (int) Math.floor(throne.z);
        int ty = (int) Math.floor(throne.y);
        while (ty > plan.minY && air.contains(key(tx, ty - 1, tz))) ty--;
        // THE QUEEN'S NEST: a bowl of bone 19 across, lined with silk, rim piled with skulls and webs; a web canopy over it
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
            double d = Math.hypot(dx, dz) + plan.noise((tx + dx) / 3.0, ty, (tz + dz) / 3.0) * 0.8;
            if (d > 11.5) continue;
            // 1.3.49: a rim you can climb from either side (it was a sheer 5-block wall outside)
            int h = d < 5.5 ? 1 : d < 8.5 ? 1 + (int) Math.round((d - 5.5)) : Math.max(0, 4 - (int) Math.round((d - 8.5) * 1.4));
            if (h <= 0) continue;
            Block floor = floorAt(w, air, tx + dx, ty + 3, tz + dz, 8);
            int base = floor == null ? ty : floor.getY();
            for (int dy = 0; dy < h; dy++) {
                Block b = w.getBlockAt(tx + dx, base + dy, tz + dz);
                if (!air.contains(key(b.getX(), b.getY(), b.getZ()))) continue;
                Material m = d < 5.5 ? (r.nextDouble() < 0.85 ? Material.WHITE_WOOL : Material.BONE_BLOCK)
                        : dy == h - 1 && r.nextDouble() < 0.25 ? Material.WHITE_WOOL : Material.BONE_BLOCK;
                put(b, m);
            }
            if (d > 6.5 && d < 9.5) {
                Block top = w.getBlockAt(tx + dx, base + h, tz + dz);
                if (top.getType().isAir() && air.contains(key(top.getX(), top.getY(), top.getZ()))) {
                    double q = r.nextDouble();
                    if (q < 0.18) put(top, Material.SKELETON_SKULL); else if (q < 0.55) web(top, r, webs, true);
                }
            }
        }
        int throneY = ty + 1;
        for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) for (int dy = 4; dy <= 15; dy++) { // the canopy
            double d = Math.sqrt(dx * dx + dz * dz + (dy - 1.0) * (dy - 1.0));
            if (Math.abs(d - 13) > 0.6 || r.nextDouble() > 0.45) continue;
            Block b = w.getBlockAt(tx + dx, ty + dy, tz + dz);
            if (b.getType().isAir() && air.contains(key(b.getX(), b.getY(), b.getZ()))) web(b, r, webs, false);
        }
        for (int i = 0; i < 7; i++) { // the ribs: bone arcs from beyond the bowl curling in over her
            double a = i * Math.PI * 2 / 7 + r.nextDouble() * 0.3;
            for (double s = 0; s <= 1.0; s += 0.035) {
                double rr = 15 - 9 * s, hh = Math.sin(s * Math.PI * 0.62) * 12;
                int bx = (int) Math.floor(tx + 0.5 + Math.cos(a) * rr), bz = (int) Math.floor(tz + 0.5 + Math.sin(a) * rr), byy = ty + (int) Math.round(hh);
                Block b = w.getBlockAt(bx, byy, bz);
                if (air.contains(key(bx, byy, bz)) && (b.getType().isAir() || b.getType() == Material.COBWEB)) put(b, Material.BONE_BLOCK);
            }
            double ea = a + Math.PI / 7; // an egg clutch between two ribs
            Block e = floorAt(w, air, (int) Math.floor(tx + 0.5 + Math.cos(ea) * 12), ty + 6, (int) Math.floor(tz + 0.5 + Math.sin(ea) * 12), 12);
            if (e != null && e.getType().isAir()) eggClutch(e, air, r, webs);
        }
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy <= 9; dy++) { // room for her
            Block b = w.getBlockAt(tx + dx, throneY + dy, tz + dz);
            Material m = b.getType();
            if (m == Material.COBWEB || m == Material.SKELETON_SKULL || (m == Material.BONE_BLOCK || m == Material.WHITE_WOOL) && dy > 0)
                put(b, Material.CAVE_AIR);
        }
        pillars(w, air, throne, r, webs, 8, 16);
        // the chambers, each with its job
        buildDens.clear(); buildSleeps.clear();
        EntityType[] spawnTypes = {EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ZOMBIE, EntityType.SKELETON};
        for (int i = 1; i < plan.rooms.size(); i++) {
            Room room = plan.rooms.get(i);
            switch (room.kind()) {
                case "pillars" -> pillars(w, air, room, r, webs, 0, 0);
                case "larder" -> larder(w, air, room, r, webs, bodies);
                case "sleep" -> sleepDen(w, air, room, r, webs);
                case "hatchery" -> hatchery(w, air, room, r, webs);
                case "den" -> broodDen(w, air, room, r, webs);
                default -> { }
            }
            Block f = floorIn(w, air, room);
            if (f != null && !room.kind().equals("den")) {
                if (i % 2 == 1) {
                    put(f, Material.CHEST);
                    if (f.getState() instanceof Chest ch) { // three rolls (a body's), scattered through the chest
                        var inv = ch.getSnapshotInventory();
                        for (int k = 0; k < 3; k++) for (ItemStack it : roll(r, 2)) inv.setItem(r.nextInt(inv.getSize()), it);
                        ch.update(true, false);
                    }
                    for (int[] d : SIX) { Block n = f.getRelative(d[0], d[1], d[2]); if (d[1] == 0 && n.getType().isAir() && r.nextBoolean()) web(n, r, webs, false); }
                } else if (room.kind().equals("plain") && i % 4 == 0) { // 1.3.49: fewer spawners (the nest was crawling)
                    spawner(f, spawnTypes[r.nextInt(spawnTypes.length)]);
                    for (int[] d : SIX) { Block n = f.getRelative(d[0], d[1], d[2]); if (n.getType().isAir() && d[1] >= 0) web(n, r, webs, false); }
                }
            }
            silk(w, air, room, r, id);
        }
        for (int i = 0; i < 6; i++) silk(w, air, throne, r, id);
        walkway(w, plan);
        for (double[] g : plan.gates) gate(w, air, g, r, webs);
        // a web that later building replaced (bones, the walkway, a gate) takes its caught item with it
        for (org.bukkit.entity.ItemDisplay d : buildWebItems) if (d.isValid() && d.getLocation().getBlock().getType() != Material.COBWEB) d.remove();
        buildWebItems.clear();
        // the loot marks into each chunk
        writeMarks(w, webs, webKey);
        writeMarks(w, bodies, bodyKey);
        Nest n = new Nest();
        n.id = id;
        n.world = w.getName();
        n.cx = (int) throne.x; n.cy = (int) throne.y; n.cz = (int) throne.z;
        n.tx = tx; n.ty = throneY; n.tz = tz;
        n.radius = Math.max(plan.maxX - plan.minX, plan.maxZ - plan.minZ) / 2 + 4;
        n.minX = plan.minX; n.minY = plan.minY; n.minZ = plan.minZ; n.maxX = plan.maxX; n.maxY = plan.maxY; n.maxZ = plan.maxZ;
        n.mouthX = !plan.entrance.isEmpty() ? plan.mouthX : Integer.MIN_VALUE;
        n.mouthZ = plan.mouthZ;
        n.mouthY = plan.mouthY;
        n.dens.addAll(buildDens);
        n.sleeps.addAll(buildSleeps);
        n.respawnAt = 0;
        nests.put(n.id, n);
        save();
        writeUndo(n.id, w);
        try { spawnQueen(n); } catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] the queen couldn't be placed (she'll try again)", e); }
        for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
        building = false;
        by.sendMessage(ChatColor.DARK_PURPLE + "The Spider Nest is done (" + air.size() + " blocks of cave, " + plan.rooms.size() + " chambers, " + n.dens.size() + " brood dens). "
                + ChatColor.GRAY + "Her throne is at " + tx + " " + throneY + " " + tz + ". Id: " + n.id
                + (n.mouthX != Integer.MIN_VALUE ? ". Its 3 ways in are long underground tunnels (one ends at " + n.mouthX + " " + n.mouthY + " " + n.mouthZ + ")." : "."));
        plugin.getLogger().info("[SpiderNest] made " + n.id + " at " + w.getName() + " " + tx + " " + throneY + " " + tz);
    }

    /** Bone pillars floor to ceiling (some snapped off, a skull on the stump), webs and bones round their feet. */
    private void pillars(World w, Set<Long> air, Room room, Random r, Map<String, List<Integer>> webs, double minR, int count) {
        int n = count > 0 ? count : (int) Math.max(4, Math.min(9, room.rx() * room.rz() / 22));
        for (int i = 0, tries = 0; i < n && tries < n * 8; tries++) {
            double a = r.nextDouble() * Math.PI * 2, d = minR > 0 ? minR + r.nextDouble() * 3 : r.nextDouble() * 0.8;
            int x = (int) Math.floor(room.x() + Math.cos(a) * (minR > 0 ? d : d * room.rx())), z = (int) Math.floor(room.z() + Math.sin(a) * (minR > 0 ? d : d * room.rz()));
            Block f = floorAt(w, air, x, (int) room.y() + 4, z, (int) room.ry() + 10);
            if (f == null || !f.getType().isAir()) continue;
            i++;
            boolean broken = r.nextDouble() < 0.3;
            int top = 0;
            while (top < 22 && air.contains(key(x, f.getY() + top, z))) top++;
            int h = broken ? Math.max(1, top / 2 + r.nextInt(2)) : top;
            boolean thick = !broken && r.nextDouble() < 0.4;
            for (int k = 0; k < h; k++) {
                put(w.getBlockAt(x, f.getY() + k, z), Material.BONE_BLOCK);
                if (thick && (k < 2 || k >= h - 2)) for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Block s = w.getBlockAt(x + dd[0], f.getY() + k, z + dd[1]);
                    if (s.getType().isAir() && air.contains(key(s.getX(), s.getY(), s.getZ()))) put(s, Material.BONE_BLOCK);
                }
            }
            if (broken) { Block cap = w.getBlockAt(x, f.getY() + h, z); if (cap.getType().isAir()) put(cap, Material.SKELETON_SKULL); }
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
                Block s = w.getBlockAt(x + dd[0], f.getY(), z + dd[1]);
                if (s.getType().isAir() && air.contains(key(s.getX(), s.getY(), s.getZ())) && r.nextDouble() < 0.45) web(s, r, webs, true);
            }
        }
    }

    /** The larder: cocooned prey hanging in rows (heads poking out), bone piles, scraps on the floor. */
    private void larder(World w, Set<Long> air, Room room, Random r, Map<String, List<Integer>> webs, Map<String, List<Integer>> bodies) {
        for (int i = 0; i < 12; i++) {
            Location c = surface(w, air, room, r, 1);
            if (c == null) continue;
            Block top = c.getBlock().getRelative(0, -1, 0);
            if (top.getType().isAir() && air.contains(key(top.getX(), top.getY() - 4, top.getZ()))) cocoon(top, r, bodies, webs, true);
        }
        Material[] scraps = {Material.ROTTEN_FLESH, Material.BONE, Material.ROTTEN_FLESH, Material.SPIDER_EYE, Material.BEEF, Material.LEATHER};
        for (int i = 0; i < 9; i++) {
            Block f = floorIn(w, air, room);
            if (f == null) continue;
            put(f, Material.BONE_BLOCK);
            for (int k = 0; k < 3; k++) {
                Location at = new Location(w, f.getX() + 0.2 + r.nextDouble() * 0.6, f.getY() + 1.02, f.getZ() + 0.2 + r.nextDouble() * 0.6);
                Material m = scraps[r.nextInt(scraps.length)];
                w.spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
                    d.setPersistent(true);
                    d.addScoreboardTag(SILK_TAG); d.addScoreboardTag(SILK_TAG + "_" + buildId);
                    d.setItemStack(new ItemStack(m));
                    d.setViewRange(0.35f);
                    d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (Math.PI / 2)).rotateZ(r.nextFloat() * 6f), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
                });
            }
        }
    }

    /** A sleeping den: silk beds (a ring of wool round carpet) under web hammocks. Brood sleep here (they wake when you come close). */
    private void sleepDen(World w, Set<Long> air, Room room, Random r, Map<String, List<Integer>> webs) {
        for (int i = 0; i < 5; i++) {
            Block f = floorIn(w, air, room);
            if (f == null) continue;
            buildSleeps.add(new int[]{f.getX(), f.getY(), f.getZ()});
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Block b = f.getRelative(dx, 0, dz);
                if (!b.getType().isAir() || !air.contains(key(b.getX(), b.getY(), b.getZ())) || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
                put(b, dx == 0 && dz == 0 ? Material.WHITE_CARPET : Material.WHITE_WOOL);
            }
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) { // the hammock over it
                Block b = f.getRelative(dx, 3 + (Math.abs(dx) + Math.abs(dz) > 2 ? 1 : 0), dz);
                if (b.getType().isAir() && air.contains(key(b.getX(), b.getY(), b.getZ())) && r.nextDouble() < 0.7) web(b, r, webs, false);
            }
        }
    }

    /** A hatchery: egg clutches everywhere, silk egg sacs (wool blobs in web) on the floor and the walls. */
    private void hatchery(World w, Set<Long> air, Room room, Random r, Map<String, List<Integer>> webs) {
        for (int i = 0; i < 14; i++) {
            Block f = floorIn(w, air, room);
            if (f != null && f.getType().isAir()) eggClutch(f, air, r, webs);
        }
        for (int i = 0; i < 5; i++) {
            Block f = floorIn(w, air, room);
            if (f == null) continue;
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) {
                Block b = f.getRelative(dx, dy, dz);
                if (b.getType().isAir() && air.contains(key(b.getX(), b.getY(), b.getZ())) && (dy == 0 || r.nextBoolean())) put(b, Material.WHITE_WOOL);
            }
            for (int[] d : new int[][]{{-1, 0}, {2, 0}, {0, -1}, {0, 2}}) {
                Block b = f.getRelative(d[0], 0, d[1]);
                if (b.getType().isAir() && air.contains(key(b.getX(), b.getY(), b.getZ()))) web(b, r, webs, false);
            }
        }
    }

    /**
     * A BROOD DEN (trial-chamber style): a bone dais with a great egg sac on it. A player stepping near wakes it: waves of spiders,
     * cave spiders, zombies, skeletons and the Queen's brood; clear them all and the den pays out (NestBrood).
     */
    private void broodDen(World w, Set<Long> air, Room room, Random r, Map<String, List<Integer>> webs) {
        Block f = floorAt(w, air, (int) Math.floor(room.x()), (int) room.y() + 3, (int) Math.floor(room.z()), (int) room.ry() + 8);
        if (f == null) f = floorIn(w, air, room);
        if (f == null) return;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 3) continue;
            Block b = f.getRelative(dx, -1, dz);
            put(b, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? Material.BONE_BLOCK : Material.POLISHED_DEEPSLATE);
            Block a = f.getRelative(dx, 0, dz);
            if (a.getType() != Material.CAVE_AIR && a.getType() != Material.AIR && air.contains(key(a.getX(), a.getY(), a.getZ()))) put(a, Material.CAVE_AIR);
        }
        for (int i = 0; i < 6; i++) { // bone candlesticks round the dais
            double a = i * Math.PI / 3;
            Block b = floorAt(w, air, (int) Math.floor(f.getX() + 0.5 + Math.cos(a) * 4.5), f.getY() + 2, (int) Math.floor(f.getZ() + 0.5 + Math.sin(a) * 4.5), 5);
            if (b == null || !b.getType().isAir()) continue;
            put(b, Material.BONE_BLOCK);
            Block t = b.getRelative(0, 1, 0);
            if (t.getType().isAir()) put(t, Material.SKELETON_SKULL);
        }
        Location sac = new Location(w, f.getX() + 0.5, f.getY(), f.getZ() + 0.5);
        w.spawn(sac, BlockDisplay.class, d -> { // the great egg sac
            d.setPersistent(true);
            d.addScoreboardTag(SILK_TAG); d.addScoreboardTag(SILK_TAG + "_" + buildId); d.addScoreboardTag(DEN_SAC_TAG);
            d.setBlock(Material.WHITE_WOOL.createBlockData());
            d.setBrightness(new org.bukkit.entity.Display.Brightness(9, 0));
            d.setTransformation(new Transformation(new Vector3f(-0.7f, 0, -0.7f), new Quaternionf(), new Vector3f(1.4f, 1.9f, 1.4f), new Quaternionf()));
        });
        w.spawn(sac.clone().add(0, 0.6, 0), BlockDisplay.class, d -> { // its glowing heart
            d.setPersistent(true);
            d.addScoreboardTag(SILK_TAG); d.addScoreboardTag(SILK_TAG + "_" + buildId); d.addScoreboardTag(DEN_SAC_TAG);
            d.setBlock(Material.VERDANT_FROGLIGHT.createBlockData());
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 0));
            d.setTransformation(new Transformation(new Vector3f(-0.4f, 0, -0.4f), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
        });
        buildDens.add(new int[]{f.getX(), f.getY(), f.getZ()});
    }

    static final String DEN_SAC_TAG = "faultline_nest_densac";

    /**
     * The way in is always walkable: under the spiral's middle a floor 3 wide (where the ramp crosses a chamber or a lower turn
     * it's a SILK BRIDGE of white wool), and 3 blocks of head room over it with nothing in the way.
     */
    private void walkway(World w, Plan plan) {
        Set<Long> laid = new HashSet<>();
        for (Tunnel t : plan.entrance) {
            double len = Math.sqrt(Math.pow(t.bx() - t.ax(), 2) + Math.pow(t.by() - t.ay(), 2) + Math.pow(t.bz() - t.az(), 2));
            int steps = Math.max(1, (int) Math.ceil(len / 0.4));
            for (int i = 0; i <= steps; i++) {
                double f = i / (double) steps;
                double x = t.ax() + (t.bx() - t.ax()) * f, y = t.ay() + (t.by() - t.ay()) * f, z = t.az() + (t.bz() - t.az()) * f;
                int fy = (int) Math.ceil(y - 2.61) - 1;
                if (plan.surface != Integer.MIN_VALUE && fy >= plan.surface - 1) continue; // up at the mouth: the ground is there
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 2) continue;
                    int bx = (int) Math.floor(x) + dx, bz = (int) Math.floor(z) + dz;
                    if (!laid.add(key(bx, fy, bz))) continue;
                    Block floor = w.getBlockAt(bx, fy, bz);
                    if (!floor.getType().isSolid() || floor.getType() == Material.COBWEB) put(floor, floor.getRelative(0, -1, 0).getType().isSolid() ? Material.COBBLED_DEEPSLATE : Material.WHITE_WOOL);
                    for (int up = 1; up <= 3; up++) {
                        Block b = w.getBlockAt(bx, fy + up, bz);
                        Material m = b.getType();
                        if (!m.isAir() && (NEST_MADE.contains(m) || m == chain() || m == Material.POINTED_DRIPSTONE || m == Material.GLOW_LICHEN || m == Material.BROWN_MUSHROOM || m == Material.DEAD_BUSH || carvable(m)))
                            put(b, Material.CAVE_AIR);
                    }
                }
            }
        }
    }

    /** A bone gate at a gateway tunnel's far end: bone fangs from the walls pointing in, skulls, webs across the corners. */
    private void gate(World w, Set<Long> air, double[] g, Random r, Map<String, List<Integer>> webs) {
        int gx = (int) Math.floor(g[0]), gy = (int) Math.floor(g[1]), gz = (int) Math.floor(g[2]);
        double hx = Math.cos(g[3]), hz = Math.sin(g[3]);
        for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
            int x = gx + dx, y = gy + dy, z = gz + dz;
            if (!air.contains(key(x, y, z))) continue;
            double along = dx * hx + dz * hz, side = Math.abs(-dx * hz + dz * hx);
            if (Math.abs(along) > 1.2) continue; // a ring across the tunnel
            boolean wall = false;
            for (int[] d : SIX) if (!air.contains(key(x + d[0], y + d[1], z + d[2]))) wall = true;
            if (!wall || side < 1.2 && dy > -2 && dy < 2) continue; // never in the middle of the way through
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isAir()) continue;
            double q = r.nextDouble();
            if (q < 0.55) put(b, Material.BONE_BLOCK);
            else if (q < 0.75) web(b, r, webs, false);
            else if (q < 0.82 && !air.contains(key(x, y - 1, z))) put(b, Material.SKELETON_SKULL);
        }
    }

    /** The mouth on the surface: bone fangs round the hole, webs and silk spilling out of it, a few skulls. */
    private void mouth(World w, Plan plan, Set<Long> air, Random r, Map<String, List<Integer>> webs) {
        if (plan.surface == Integer.MIN_VALUE || plan.entrance.isEmpty()) return;
        Tunnel first = plan.entrance.get(0);
        double cx = first.ax(), cz = first.az();
        for (int i = 0; i < 9; i++) { // fangs: curved bone spikes leaning over the hole
            double a = i * Math.PI * 2 / 9 + r.nextDouble() * 0.4;
            double rr = 4.6 + r.nextDouble() * 0.8;
            int bx = (int) Math.floor(cx + Math.cos(a) * rr), bz = (int) Math.floor(cz + Math.sin(a) * rr);
            int gy = w.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(gy - plan.surface) > 6) continue;
            int h = 2 + r.nextInt(3);
            for (int k = 0; k < h; k++) {
                double lean = k / (double) h * 1.4;
                int px = (int) Math.floor(cx + Math.cos(a) * (rr - lean)), pz = (int) Math.floor(cz + Math.sin(a) * (rr - lean));
                Block b = w.getBlockAt(px, gy + k, pz);
                if (b.getType().isAir() || b.isPassable() && !b.isLiquid()) put(b, k == h - 1 && r.nextDouble() < 0.4 ? Material.SKELETON_SKULL : Material.BONE_BLOCK);
            }
        }
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) { // webs and silk on the ground round it
            double d = Math.hypot(dx, dz);
            if (d < 3.5 || d > 9.5 || r.nextDouble() > 0.42 - d * 0.03) continue;
            int x = (int) Math.floor(cx) + dx, z = (int) Math.floor(cz) + dz;
            int gy = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(gy - plan.surface) > 5 || air.contains(key(x, gy - 1, z))) continue;
            Block b = w.getBlockAt(x, gy, z);
            if (!b.getType().isAir() && !(b.isPassable() && !b.isLiquid() && b.getType() != Material.COBWEB)) continue;
            if (!b.getRelative(0, -1, 0).getType().isSolid()) continue;
            double q = r.nextDouble();
            if (q < 0.55) { put(b, Material.COBWEB); mark(webs, b); }
            else if (q < 0.85) put(b, Material.WHITE_CARPET);
            else if (q < 0.93) put(b, Material.BONE_BLOCK);
            else put(b, Material.SKELETON_SKULL);
        }
    }

    /** Writes the undo record (every original block) to spider_nests/<id>.undo.gz, off the main thread. */
    private void writeUndo(String id, World w) {
        Map<Long, BlockData> u = undo;
        undo = null;
        if (u == null) return;
        Map<BlockData, String> names = new HashMap<>();
        StringBuilder sb = new StringBuilder(u.size() * 32);
        sb.append(w.getName()).append('\n');
        for (var e : u.entrySet()) {
            long k = e.getKey();
            sb.append(kx(k)).append(' ').append(ky(k)).append(' ').append(kz(k)).append(' ')
                    .append(names.computeIfAbsent(e.getValue(), BlockData::getAsString)).append('\n');
        }
        File f = undoFile(id);
        byte[] text = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Runnable job = () -> {
            try {
                f.getParentFile().mkdirs();
                File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
                try (var out = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(tmp))) { out.write(text); }
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException | RuntimeException ex) { plugin.getLogger().warning("[SpiderNest] couldn't save the undo record for " + id + ": " + ex); }
        };
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, job); else job.run();
    }

    /** A floor cell near the middle of a room. */
    private Block floorIn(World w, Set<Long> air, Room room) {
        for (int tries = 0; tries < 30; tries++) {
            int x = (int) Math.floor(room.x + (random.nextDouble() - 0.5) * room.rx), z = (int) Math.floor(room.z + (random.nextDouble() - 0.5) * room.rz);
            for (int y = (int) room.y; y > room.y - room.ry - 3; y--) {
                if (air.contains(key(x, y, z)) && !air.contains(key(x, y - 1, z))) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir() && b.getRelative(0, -1, 0).getType().isSolid()) return b;
                }
            }
        }
        return null;
    }

    /** Silk threads slung across a chamber: thin white BlockDisplays from the ceiling down to a wall or the floor. */
    private void silk(World w, Set<Long> air, Room room, Random r, String id) {
        for (int i = 0; i < 4; i++) {
            Location a = surface(w, air, room, r, 1), b = surface(w, air, room, r, -1);
            if (a == null || b == null) continue;
            org.bukkit.util.Vector d = b.toVector().subtract(a.toVector());
            float len = (float) d.length();
            if (len < 3) continue;
            Location at = a.clone(); at.setYaw(0); at.setPitch(0);
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize());
            w.spawn(at, BlockDisplay.class, e -> {
                e.setPersistent(true);
                e.addScoreboardTag(SILK_TAG);
                e.addScoreboardTag(SILK_TAG + "_" + id);
                e.setBlock(Material.WHITE_CONCRETE.createBlockData());
                e.setBrightness(new org.bukkit.entity.Display.Brightness(6, 6));
                e.setViewRange(0.5f);
                e.setTransformation(new Transformation(q.transform(new Vector3f(-0.03f, -0.03f, 0)), q, new Vector3f(0.06f, 0.06f, len), new Quaternionf()));
            });
        }
    }

    static final String SILK_TAG = "faultline_nest_silk";

    /** A point on the ceiling (dir 1) or the floor / a wall (dir -1) of a room. */
    private Location surface(World w, Set<Long> air, Room room, Random r, int dir) {
        for (int tries = 0; tries < 20; tries++) {
            int x = (int) Math.floor(room.x + (r.nextDouble() - 0.5) * room.rx * 1.4), z = (int) Math.floor(room.z + (r.nextDouble() - 0.5) * room.rz * 1.4);
            int y = (int) room.y;
            if (!air.contains(key(x, y, z))) continue;
            while (air.contains(key(x, y + dir, z)) && Math.abs(y - room.y) < 20) y += dir;
            return new Location(w, x + 0.5, dir > 0 ? y + 1 : y, z + 0.5);
        }
        return null;
    }

    private void writeMarks(World w, Map<String, List<Integer>> map, NamespacedKey k) {
        for (var e : map.entrySet()) {
            String[] p = e.getKey().split(",");
            Chunk c = w.getChunkAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            PersistentDataContainer pdc = c.getPersistentDataContainer();
            int[] old = pdc.get(k, PersistentDataType.INTEGER_ARRAY);
            Set<Integer> all = new HashSet<>();
            if (old != null) for (int v : old) all.add(v);
            all.addAll(e.getValue());
            pdc.set(k, PersistentDataType.INTEGER_ARRAY, all.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private boolean takeMark(Block b, NamespacedKey k) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] a = pdc.get(k, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return false;
        int l = local(b);
        for (int i = 0; i < a.length; i++) if (a[i] == l) {
            int[] n = new int[a.length - 1];
            System.arraycopy(a, 0, n, 0, i);
            System.arraycopy(a, i + 1, n, i, a.length - i - 1);
            if (n.length == 0) pdc.remove(k); else pdc.set(k, PersistentDataType.INTEGER_ARRAY, n);
            return true;
        }
        return false;
    }

    // ============================================================================================ loot
    static final String[] ACCESSORIES = {"claws", "bezoar", "discount", "jelly", "shield", "lavacharm", "scarf", "spelunker", "harpy", "monocle",
            "quickdraw", "pouch", "windrunner", "fang", "pauldron", "lantern", "greenthumb", "treasure", "smelter", "featherfall"};

    /** One loot roll. tier 0 = a web, 1 = a pot, 2 = a body or a chest. */
    List<ItemStack> roll(Random r, int tier) {
        List<ItemStack> out = new ArrayList<>();
        double t = tier;
        out.add(switch (r.nextInt(6)) {
            case 0 -> new ItemStack(Material.IRON_INGOT, 1 + r.nextInt(3 + tier * 2));
            case 1 -> new ItemStack(Material.RAW_COPPER, 2 + r.nextInt(5 + tier * 2));
            case 2 -> new ItemStack(Material.GOLD_NUGGET, 3 + r.nextInt(6));
            case 3 -> new ItemStack(Material.STRING, 2 + r.nextInt(5));
            case 4 -> new ItemStack(Material.SPIDER_EYE, 1 + r.nextInt(3));
            default -> new ItemStack(Material.COPPER_INGOT, 1 + r.nextInt(3 + tier));
        });
        if (r.nextDouble() < 0.05 + 0.12 * t) out.add(new ItemStack(Material.DIAMOND, 1 + r.nextInt(1 + tier)));
        if (r.nextDouble() < 0.04 + 0.08 * t) out.add(new ItemStack(Material.GOLD_INGOT, 1 + r.nextInt(3)));
        if (r.nextDouble() < 0.03 + 0.06 * t) out.add(new ItemStack(Material.EMERALD, 1 + r.nextInt(4)));
        if (r.nextDouble() < 0.02 + 0.05 * t) out.add(make("cloud"));
        if (r.nextDouble() < 0.015 + 0.045 * t) out.add(make(r.nextDouble() < 0.25 ? "darkness_final" : "darkness_stage1"));
        if (r.nextDouble() < 0.006 + 0.03 * t) out.add(make("acc:" + ACCESSORIES[r.nextInt(ACCESSORIES.length)]));
        if (tier >= 2 && r.nextDouble() < 0.08) out.add(new ItemStack(Material.GOLDEN_APPLE, 1));
        out.removeIf(i -> i == null || i.getType().isAir());
        return out;
    }

    private ItemStack make(String what) {
        try {
            Function<FaultlineItems, ItemStack> f = switch (what) {
                case "cloud" -> FaultlineItems.CloudPotionItem::create;
                case "darkness_final" -> FaultlineItems.ItemCatalog.DARKNESS_TYPES.get("final");
                case "darkness_stage1" -> FaultlineItems.ItemCatalog.DARKNESS_TYPES.get("stage1");
                default -> what.startsWith("acc:") ? FaultlineItems.ItemCatalog.ACCESSORY_TYPES.get(what.substring(4)) : null;
            };
            return f == null ? null : f.apply(plugin);
        } catch (RuntimeException | LinkageError e) { return null; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        if (m != Material.COBWEB && m != Material.SKELETON_SKULL && m != Material.WHITE_WOOL) return;
        if (nests.isEmpty()) return;
        NamespacedKey k = m == Material.COBWEB ? webKey : bodyKey;
        if (!takeMark(b, k)) return;
        if (e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        List<ItemStack> drop;
        if (m == Material.COBWEB) {
            if (random.nextDouble() >= cfg("web-loot-chance", 0.18)) return;
            drop = roll(random, 0);
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOOL_BREAK, 1f, 0.6f);
        } else {
            e.setDropItems(false);
            drop = roll(random, 2);
            b.getWorld().playSound(b.getLocation(), Sound.ENTITY_SKELETON_DEATH, 0.8f, 0.6f);
            e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(m == Material.WHITE_WOOL ? "You cut the cocoon open..." : "You search the remains...", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
        Location at = b.getLocation().add(0.5, 0.4, 0.5);
        for (ItemStack it : drop) b.getWorld().dropItemNaturally(at, it);
        b.getWorld().spawnParticle(Particle.WHITE_ASH, at, 12, 0.3, 0.3, 0.3, 0);
    }

    /** 1.3.49: right-click a web to pull out what's caught in it (the web stays). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onWebPick(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.COBWEB || nests.isEmpty()) return;
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 0.55, 0.55, 0.55))
            if (en instanceof org.bukkit.entity.ItemDisplay d && en.getScoreboardTags().contains(WEB_ITEM_TAG) && d.isValid()) {
                e.setCancelled(true);
                ItemStack it = d.getItemStack();
                d.remove();
                for (ItemStack left : e.getPlayer().getInventory().addItem(it).values()) b.getWorld().dropItemNaturally(e.getPlayer().getLocation(), left);
                e.getPlayer().playSound(b.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
                e.getPlayer().playSound(b.getLocation(), Sound.BLOCK_WOOL_BREAK, 0.6f, 1.3f);
                b.getWorld().spawnParticle(Particle.ITEM, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.02, new ItemStack(Material.COBWEB));
                return;
            }
    }

    /** The item you could see in a web drops when the web really breaks (MONITOR: never when a claim plugin stopped it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWebItem(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.COBWEB || nests.isEmpty()) return;
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 0.55, 0.55, 0.55))
            if (en instanceof org.bukkit.entity.ItemDisplay d && en.getScoreboardTags().contains(WEB_ITEM_TAG)) {
                if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) b.getWorld().dropItemNaturally(d.getLocation(), d.getItemStack());
                d.remove();
            }
    }

    // ============================================================================================ the queen, ambience
    Nest nestAt(Location l) {
        for (Nest n : nests.values()) {
            if (!n.world.equals(l.getWorld().getName())) continue;
            if (Math.hypot(l.getX() - n.cx, l.getZ() - n.cz) < n.radius && Math.abs(l.getY() - n.cy) < 40 && l.getBlock().getLightFromSky() == 0) return n;
        }
        return null;
    }

    /** Within a nest's reach (its radius + `extra` across, 60 up or down), sky or not. */
    boolean near(Location l, double extra) {
        for (Nest n : nests.values()) {
            if (!n.world.equals(l.getWorld().getName())) continue;
            if (Math.hypot(l.getX() - n.cx, l.getZ() - n.cz) < n.radius + extra && Math.abs(l.getY() - n.cy) < 60) return true;
        }
        return false;
    }

    private final Map<UUID, String> inside = new HashMap<>();

    void spawnQueen(Nest n) {
        World w = Bukkit.getWorld(n.world);
        if (w == null) return;
        n.queen = queens.sleepingQueen(n.throne(w), n.id);
    }

    void queenDied(String id) {
        Nest n = nests.get(id);
        if (n == null) return;
        n.queen = null;
        n.respawnAt = System.currentTimeMillis() + (long) (cfg("respawn-minutes", 120) * 60000);
        save();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Nest n : nests.values()) {
            World w = Bukkit.getWorld(n.world);
            if (w == null) continue;
            if (n.queen != null && (!n.queen.body.isValid() && (n.queen.rig == null || n.queen.rig.gone))) n.queen = null;
            // 1.3.46: never more than one queen per nest (adopt a lost one, remove extras)
            for (QueenSpiderManager.Queen q : new ArrayList<>(queens.queens.values())) {
                if (!n.id.equals(q.nestId) || q == n.queen || !q.body.isValid()) continue;
                if (n.queen == null) n.queen = q; else { queens.remove(q); plugin.getLogger().info("[SpiderNest] removed an extra queen in " + n.id); }
            }
            if (n.queen == null && now >= n.respawnAt && w.isChunkLoaded(n.tx >> 4, n.tz >> 4)) {
                boolean someone = false;
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(n.throne(w)) < 24 * 24) someone = true;
                if (!someone) spawnQueen(n);
            }
        }
        // ambience and brood for players inside
        Map<String, List<Player>> in = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Nest n = nestAt(p.getLocation());
            String was = inside.get(p.getUniqueId());
            if (n == null) { if (was != null) { inside.remove(p.getUniqueId()); brood.left(p); } continue; }
            in.computeIfAbsent(n.id, k -> new ArrayList<>()).add(p);
            if (!n.id.equals(was)) {
                inside.put(p.getUniqueId(), n.id);
                p.sendTitle(ChatColor.DARK_PURPLE + "Spider Nest", ChatColor.GRAY + "Something very large is asleep down here.", 10, 50, 15);
                p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.6f);
            }
            if (random.nextDouble() < 0.25) p.playSound(p.getLocation().add(random.nextGaussian() * 8, random.nextGaussian() * 3, random.nextGaussian() * 8), Sound.ENTITY_SPIDER_AMBIENT, SoundCategory.HOSTILE, 0.5f, 0.6f + random.nextFloat() * 0.6f);
            p.spawnParticle(Particle.WHITE_ASH, p.getLocation().add(0, 1.5, 0), 10, 5, 2, 5, 0);
            try {
                brood.insideTick(p, n, queens);
                brood.ambient(p, n);
                brood.beds(p, n);
                webItems(p);
            } catch (RuntimeException e) { plugin.getLogger().warning("[SpiderNest] " + e); }
        }
        for (Nest n : nests.values()) brood.quakeTick(n, in.getOrDefault(n.id, List.of()));
    }

    /** Items caught in webs whose web is gone (water, an explosion, a piston) drop where they hung. */
    private void webItems(Player p) {
        for (Entity e : p.getNearbyEntities(12, 8, 12)) {
            if (!(e instanceof org.bukkit.entity.ItemDisplay d) || !e.getScoreboardTags().contains(WEB_ITEM_TAG)) continue;
            if (d.getLocation().getBlock().getType() == Material.COBWEB) continue;
            if (plugin.spiderForm != null && plugin.spiderForm.parted(d.getLocation().getBlock())) continue;
            d.getWorld().dropItemNaturally(d.getLocation(), d.getItemStack());
            d.remove();
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        for (Nest n : nests.values()) {
            if (n.queen == null || !n.world.equals(e.getWorld().getName())) continue;
            if (e.getChunk().getX() == n.tx >> 4 && e.getChunk().getZ() == n.tz >> 4 || n.queen.body.isValid() && n.queen.body.getLocation().getChunk().equals(e.getChunk())) {
                queens.remove(n.queen);
                n.queen = null; // back asleep (and healed) when it loads again
            }
        }
    }

    /** Cobwebs players place are never loot webs (the mark is taken off if one somehow sits there). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() == Material.COBWEB && !nests.isEmpty()) takeMark(e.getBlockPlaced(), webKey);
    }

    // ============================================================================================ old nests
    /** 1.3.44 nests had sniffer eggs on their floors (red blocks that hatch into sniffers): turned into egg clutches once. */
    @EventHandler
    public void onLoad(org.bukkit.event.world.ChunkLoadEvent e) { if (!nests.isEmpty() && !e.isNewChunk()) cleanOld(e.getChunk()); }

    private void cleanOld(Chunk c) {
        NamespacedKey done = new NamespacedKey(plugin, "nest_v2");
        for (Nest n : nests.values()) {
            if (!n.world.equals(c.getWorld().getName())) continue;
            int bx = c.getX() << 4, bz = c.getZ() << 4;
            if (bx + 16 < n.cx - n.radius || bx > n.cx + n.radius || bz + 16 < n.cz - n.radius || bz > n.cz + n.radius) continue;
            if (c.getPersistentDataContainer().has(done, PersistentDataType.BYTE)) return;
            c.getPersistentDataContainer().set(done, PersistentDataType.BYTE, (byte) 1);
            int lo = Math.max(c.getWorld().getMinHeight(), n.cy - 40), hi = Math.min(c.getWorld().getMaxHeight() - 1, n.cy + 40);
            int fixed = 0;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = lo; y <= hi; y++) {
                Block b = c.getBlock(x, y, z);
                if (b.getType() == Material.SNIFFER_EGG) { b.setType(Material.OCHRE_FROGLIGHT, false); fixed++; }
            }
            if (fixed > 0) plugin.getLogger().info("[SpiderNest] " + fixed + " sniffer eggs in " + n.id + " turned into egg clutches");
            return;
        }
    }

    // ============================================================================================ /queenspider nest
    /** The nest named, else the one you're in / the nearest within 300 blocks. */
    private Nest pick(CommandSender s, String[] a, int at) {
        if (a.length > at && nests.containsKey(a[at])) return nests.get(a[at]);
        if (!(s instanceof Player p)) return null;
        Nest best = null; double bd = 300;
        for (Nest n : nests.values()) {
            if (!n.world.equals(p.getWorld().getName())) continue;
            double d = Math.hypot(p.getLocation().getX() - n.cx, p.getLocation().getZ() - n.cz) - n.radius;
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    boolean command(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "help";
        switch (sub) {
            case "here", "create", "make" -> {
                if (s instanceof Player p) create(p, a.length > 2 && a[2].equalsIgnoreCase("force")); else s.sendMessage("Players only.");
            }
            case "list" -> {
                if (nests.isEmpty()) s.sendMessage(ChatColor.GRAY + "No nests.");
                for (Nest n : nests.values()) s.sendMessage(ChatColor.DARK_PURPLE + n.id + ChatColor.GRAY + " " + n.world + " " + n.tx + " " + n.ty + " " + n.tz
                        + (n.mouthX != Integer.MIN_VALUE ? " (a way in at " + n.mouthX + " " + (n.mouthY != Integer.MIN_VALUE ? n.mouthY + " " : "") + n.mouthZ + ")" : "")
                        + (n.queen != null ? (n.queen.asleep ? " (queen asleep)" : " (queen awake)") : n.respawnAt > System.currentTimeMillis() ? " (queen back in " + (n.respawnAt - System.currentTimeMillis()) / 60000 + " min)" : " (queen comes when loaded)"));
            }
            case "tp" -> {
                Nest n = pick(s, a, 2);
                if (n == null || !(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "/queenspider nest tp [id]"); return true; }
                World w = Bukkit.getWorld(n.world);
                if (w == null) { s.sendMessage(ChatColor.RED + "That world isn't loaded."); return true; }
                if (a.length > 3 && a[3].equalsIgnoreCase("entrance") && n.mouthX != Integer.MIN_VALUE)
                    p.teleport(new Location(w, n.mouthX + 0.5, n.mouthY != Integer.MIN_VALUE ? n.mouthY - 1 : w.getHighestBlockYAt(n.mouthX + 9, n.mouthZ) + 1, n.mouthZ + 0.5));
                else p.teleport(new Location(w, n.tx + 0.5, n.ty, n.tz + 10.5));
            }
            case "respawn" -> {
                Nest n = pick(s, a, 2);
                if (n == null) { s.sendMessage(ChatColor.RED + "/queenspider nest respawn [id]"); return true; }
                for (QueenSpiderManager.Queen q : new ArrayList<>(queens.queens.values())) if (n.id.equals(q.nestId)) queens.remove(q);
                n.queen = null; n.respawnAt = 0; save();
                s.sendMessage(ChatColor.GREEN + "She'll be back asleep on her throne in a moment.");
            }
            case "forget" -> {
                Nest n = pick(s, a, 2);
                if (n == null) { s.sendMessage(ChatColor.RED + "/queenspider nest forget [id] (the caves stay; she stops coming back)"); return true; }
                for (QueenSpiderManager.Queen q : new ArrayList<>(queens.queens.values())) if (n.id.equals(q.nestId)) queens.remove(q);
                nests.remove(n.id); save();
                s.sendMessage(ChatColor.YELLOW + "Forgot " + n.id + ". The caves stay as they are.");
            }
            case "delete", "remove", "destroy" -> {
                Nest n = pick(s, a, 2);
                if (n == null) { s.sendMessage(ChatColor.RED + "No nest near you. /queenspider nest delete <id> (see /queenspider nest list)"); return true; }
                if (deleting) { s.sendMessage(ChatColor.RED + "A nest is already being removed."); return true; }
                delete(s, n);
            }
            default -> s.sendMessage(ChatColor.YELLOW + "/queenspider nest [here [force]|list|tp [id] [entrance]|respawn [id]|delete [id]|forget [id]]"
                    + ChatColor.GRAY + " (delete puts the ground back the way it was; forget only stops the queen)");
        }
        return true;
    }

    private boolean deleting;

    /**
     * Takes a nest out of the world: its queen, its silk, its loot marks, and every block it changed put back from its undo
     * record (1.3.46 nests). A 1.3.44 nest has no record: its caves are filled with deepslate and its webs / wool / eggs /
     * lanterns / bones / skulls / pots / spawners taken out (chests stay), below the surface.
     */
    void delete(CommandSender s, Nest n) {
        World w = Bukkit.getWorld(n.world);
        if (w == null) { s.sendMessage(ChatColor.RED + "That world isn't loaded."); return; }
        deleting = true;
        for (QueenSpiderManager.Queen q : new ArrayList<>(queens.queens.values())) if (n.id.equals(q.nestId)) queens.remove(q);
        brood.removeNest(n.id);
        n.queen = null;
        nests.remove(n.id);
        save();
        File f = undoFile(n.id);
        s.sendMessage(ChatColor.DARK_PURPLE + "Removing " + n.id + (f.exists() ? " (putting every block back)..." : " (an old nest: filling its caves in)..."));
        int x0, z0, x1, z1;
        if (n.maxX != n.minX) { x0 = n.minX; z0 = n.minZ; x1 = n.maxX; z1 = n.maxZ; }
        else { x0 = n.cx - n.radius - 8; z0 = n.cz - n.radius - 8; x1 = n.cx + n.radius + 8; z1 = n.cz + n.radius + 8; }
        if (n.mouthX != Integer.MIN_VALUE) { x0 = Math.min(x0, n.mouthX - 12); z0 = Math.min(z0, n.mouthZ - 12); x1 = Math.max(x1, n.mouthX + 12); z1 = Math.max(z1, n.mouthZ + 12); }
        final int fx0 = x0, fz0 = z0, fx1 = x1, fz1 = z1;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = null;
            if (f.exists()) try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f)), java.nio.charset.StandardCharsets.UTF_8))) {
                lines = new ArrayList<>();
                in.readLine(); // the world
                for (String l; (l = in.readLine()) != null; ) if (!l.isBlank()) lines.add(l);
            } catch (java.io.IOException | RuntimeException ex) { plugin.getLogger().warning("[SpiderNest] couldn't read " + f + ": " + ex); lines = null; }
            List<String> rec = lines;
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<java.util.concurrent.CompletableFuture<Chunk>> loads = new ArrayList<>();
                for (int cx = fx0 >> 4; cx <= fx1 >> 4; cx++) for (int cz = fz0 >> 4; cz <= fz1 >> 4; cz++) {
                    try { loads.add(w.getChunkAtAsync(cx, cz, true)); } catch (RuntimeException ex) { loads.add(java.util.concurrent.CompletableFuture.completedFuture(w.getChunkAt(cx, cz))); }
                }
                java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((ok, err) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            List<Chunk> held = new ArrayList<>();
                            for (var fu : loads) try { Chunk c = fu.getNow(null); if (c != null && c.addPluginChunkTicket(plugin)) held.add(c); } catch (RuntimeException ignored) { }
                            for (int cx = fx0 >> 4; cx <= fx1 >> 4; cx++) for (int cz = fz0 >> 4; cz <= fz1 >> 4; cz++) { // silk, loot marks
                                if (!w.isChunkLoaded(cx, cz)) continue;
                                Chunk c = w.getChunkAt(cx, cz);
                                for (Entity e : c.getEntities()) if (e.getScoreboardTags().contains(SILK_TAG)) e.remove();
                                c.getPersistentDataContainer().remove(webKey);
                                c.getPersistentDataContainer().remove(bodyKey);
                            }
                            if (rec != null) restore(s, w, n, rec, held, f);
                            else fill(s, w, n, held, fx0, fz0, fx1, fz1);
                        }));
            });
        });
    }

    private void restore(CommandSender s, World w, Nest n, List<String> rec, List<Chunk> held, File f) {
        int per = (int) cfg("blocks-per-tick", 20000);
        int[] i = {0};
        Map<String, BlockData> cache = new HashMap<>();
        // block entities first (chests, spawners, pots) so their contents don't spill: emptied, then everything set back
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                int done = 0;
                while (i[0] < rec.size() && done < per) {
                    String[] p = rec.get(i[0]++).split(" ", 4);
                    Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    if (b.getState() instanceof org.bukkit.block.Container ct) ct.getInventory().clear();
                    BlockData d = cache.computeIfAbsent(p[3], x -> { try { return Bukkit.createBlockData(x); } catch (RuntimeException e) { return Material.STONE.createBlockData(); } });
                    b.setBlockData(d, false);
                    done++;
                }
                if (i[0] >= rec.size()) {
                    task.cancel();
                    for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                    f.delete();
                    deleting = false;
                    s.sendMessage(ChatColor.GREEN + "The nest " + n.id + " is gone: " + rec.size() + " blocks put back the way they were.");
                    plugin.getLogger().info("[SpiderNest] deleted " + n.id + " (" + rec.size() + " blocks restored)");
                }
            } catch (RuntimeException e) {
                task.cancel(); deleting = false;
                for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] delete failed", e);
                s.sendMessage(ChatColor.RED + "Removing the nest failed: " + e);
            }
        }, 1L, 1L);
    }

    static final Set<Material> NEST_MADE = Set.of(Material.COBWEB, Material.WHITE_WOOL, Material.WHITE_CARPET, Material.SNIFFER_EGG,
            Material.OCHRE_FROGLIGHT, Material.PEARLESCENT_FROGLIGHT, Material.SOUL_LANTERN, Material.SKELETON_SKULL, Material.BONE_BLOCK,
            Material.DECORATED_POT, Material.SPAWNER, Material.CAVE_AIR);

    /** A 1.3.44 nest (no undo record): its cave filled with deepslate below the surface, its decorations with it. */
    private void fill(CommandSender s, World w, Nest n, List<Chunk> held, int x0, int z0, int x1, int z1) {
        Material chain = chain();
        int lo = Math.max(w.getMinHeight() + 1, n.cy - 30), hi = Math.min(w.getMaxHeight() - 2, n.cy + 30);
        List<int[]> cols = new ArrayList<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) if (Math.hypot(x - n.cx, z - n.cz) <= n.radius + 6) cols.add(new int[]{x, z});
        int per = Math.max(1, (int) cfg("blocks-per-tick", 20000) / (hi - lo + 1));
        int[] i = {0}, filled = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                int done = 0;
                while (i[0] < cols.size() && done < per) {
                    int[] c = cols.get(i[0]++);
                    int top = w.getHighestBlockYAt(c[0], c[1], org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) - 5;
                    for (int y = lo; y <= Math.min(hi, top); y++) {
                        Block b = w.getBlockAt(c[0], y, c[1]);
                        Material m = b.getType();
                        if (NEST_MADE.contains(m) || m == chain) {
                            if (b.getState() instanceof org.bukkit.block.Container ct) ct.getInventory().clear();
                            b.setType(y < 0 ? Material.DEEPSLATE : Material.STONE, false);
                            filled[0]++;
                        }
                    }
                    done++;
                }
                if (i[0] >= cols.size()) {
                    task.cancel();
                    for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                    deleting = false;
                    s.sendMessage(ChatColor.GREEN + "The old nest " + n.id + " is filled in (" + filled[0] + " blocks). Its chests stay where they were.");
                    plugin.getLogger().info("[SpiderNest] deleted old nest " + n.id + " (" + filled[0] + " blocks filled)");
                }
            } catch (RuntimeException e) {
                task.cancel(); deleting = false;
                for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] delete failed", e);
                s.sendMessage(ChatColor.RED + "Removing the nest failed: " + e);
            }
        }, 1L, 1L);
    }

    void shutdown() {
        brood.removeAll();
        for (Nest n : nests.values()) if (n.queen != null) { queens.remove(n.queen); n.queen = null; }
        save();
    }

    ItemStack menuItem() {
        ItemStack it = new ItemStack(Material.COBWEB);
        var m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Spawn a Spider Nest");
        m.setLore(List.of(ChatColor.GRAY + "Click: carves a Spider Nest biome under you", ChatColor.GRAY + "(34 blocks down, a spiral ramp up to here).",
                ChatColor.GRAY + "The Queen sleeps on her throne in it.", ChatColor.GRAY + "/queenspider nest delete puts it all back."));
        it.setItemMeta(m);
        return it;
    }
}
