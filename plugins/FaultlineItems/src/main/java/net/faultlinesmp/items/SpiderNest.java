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

    static final class Nest {
        String id, world;
        int cx, cy, cz, tx, ty, tz, radius;
        long respawnAt;
        transient QueenSpiderManager.Queen queen;
        Location throne(World w) { return new Location(w, tx + 0.5, ty, tz + 0.5); }
    }

    SpiderNest(FaultlineItems plugin, QueenSpiderManager queens) {
        this.plugin = plugin;
        this.queens = queens;
        queens.nests = this;
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
            nests.put(id, n);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 40L);
    }

    void save() {
        data.set("nests", null);
        for (Nest n : nests.values()) {
            String k = "nests." + n.id + ".";
            data.set(k + "world", n.world);
            data.set(k + "cx", n.cx); data.set(k + "cy", n.cy); data.set(k + "cz", n.cz);
            data.set(k + "tx", n.tx); data.set(k + "ty", n.ty); data.set(k + "tz", n.tz);
            data.set(k + "radius", n.radius); data.set(k + "respawn-at", n.respawnAt);
        }
        SafeYaml.write(plugin, file, data.saveToString());
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("spider-nest." + k, d); }

    // ============================================================================================ the plan (pure)
    /** A room: an ellipsoid. A tunnel: a capsule between two room centres. */
    record Room(double x, double y, double z, double rx, double ry, double rz) { }
    record Tunnel(double ax, double ay, double az, double bx, double by, double bz, double r) { }

    static final class Plan {
        final List<Room> rooms = new ArrayList<>();
        final List<Tunnel> tunnels = new ArrayList<>();
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
            for (Tunnel t : tunnels) {
                double vx = t.bx - t.ax, vy = t.by - t.ay, vz = t.bz - t.az, len2 = vx * vx + vy * vy + vz * vz;
                double px = x + 0.5 - t.ax, py = y + 0.5 - t.ay, pz = z + 0.5 - t.az;
                double k = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * vx + py * vy + pz * vz) / len2));
                double ex = px - vx * k, ey = py - vy * k, ez = pz - vz * k;
                double rr = t.r * (1 + n * 0.6);
                if (ex * ex + ey * ey * 1.4 + ez * ez < rr * rr) return true;
            }
            return false;
        }
    }

    /** The layout around (cx, cy, cz); `surface` = the y of the ground above for the way in (or Integer.MIN_VALUE). */
    static Plan plan(long seed, int cx, int cy, int cz, int sx, int sz, int surface) {
        Plan p = new Plan(seed);
        Random r = new Random(seed);
        Room throne = new Room(cx + 0.5, cy + 5, cz + 0.5, 16, 9, 15);
        p.rooms.add(throne);
        p.grow(throne.x, throne.y, throne.z, 17);
        int n = 10 + r.nextInt(5);
        List<Room> placed = new ArrayList<>(List.of(throne));
        for (int i = 0; i < n; i++) {
            Room from = placed.get(r.nextInt(Math.min(placed.size(), 1 + i / 2 + 1)));
            double rx = 6 + r.nextDouble() * 6, ry = 3.5 + r.nextDouble() * 3.5, rz = 6 + r.nextDouble() * 6;
            Room room = null;
            for (int tries = 0; tries < 20 && room == null; tries++) {
                double a = r.nextDouble() * Math.PI * 2, d = 20 + r.nextDouble() * 14;
                double x = from.x + Math.cos(a) * d, z = from.z + Math.sin(a) * d, y = from.y + (r.nextDouble() - 0.5) * 14;
                if (Math.hypot(x - cx, z - cz) > 72) continue;
                boolean overlaps = false;
                for (Room o : placed) if (Math.hypot(o.x - x, o.z - z) < (o.rx + rx) * 0.75) overlaps = true;
                if (!overlaps) room = new Room(x, y, z, rx, ry, rz);
            }
            if (room == null) continue;
            placed.add(room);
            p.rooms.add(room);
            p.grow(room.x, room.y, room.z, Math.max(room.rx, room.rz) + 1);
            p.tunnels.add(new Tunnel(from.x, from.y - from.ry * 0.4, from.z, room.x, room.y - room.ry * 0.4, room.z, 2.4 + r.nextDouble() * 1.2));
        }
        for (int i = 0; i < 3 && placed.size() > 3; i++) { // a few loops, so it's a network, not a tree
            Room a = placed.get(1 + r.nextInt(placed.size() - 1)), b = placed.get(1 + r.nextInt(placed.size() - 1));
            if (a != b && Math.hypot(a.x - b.x, a.z - b.z) < 40) p.tunnels.add(new Tunnel(a.x, a.y - a.ry * 0.4, a.z, b.x, b.y - b.ry * 0.4, b.z, 2.2));
        }
        p.surface = surface;
        if (surface != Integer.MIN_VALUE) { // the way in: from the room nearest the admin, sloping up to where they stood
            Room best = throne; double bd = Double.MAX_VALUE;
            for (Room o : placed) { double d = Math.hypot(o.x - sx, o.z - sz); if (d < bd && o != throne) { bd = d; best = o; } }
            double x0 = best.x, y0 = best.y, z0 = best.z;
            int steps = 4;
            for (int i = 0; i < steps; i++) {
                double f0 = i / (double) steps, f1 = (i + 1) / (double) steps;
                double ax = x0 + (sx + 0.5 - x0) * f0, ay = y0 + (surface + 2 - y0) * f0, az = z0 + (sz + 0.5 - z0) * f0;
                double bx = x0 + (sx + 0.5 - x0) * f1, by = y0 + (surface + 2 - y0) * f1, bz = z0 + (sz + 0.5 - z0) * f1;
                p.tunnels.add(new Tunnel(ax, ay, az, bx, by, bz, 2.6));
                p.grow(bx, by, bz, 4);
            }
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
    void create(Player p) {
        if (building) { p.sendMessage(ChatColor.RED + "A nest is already being built."); return; }
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) { p.sendMessage(ChatColor.RED + "Spider Nests go in the Overworld."); return; }
        Location l = p.getLocation();
        int sx = l.getBlockX(), sz = l.getBlockZ();
        boolean underground = l.getBlock().getLightFromSky() == 0;
        int surface = underground ? Integer.MIN_VALUE : w.getHighestBlockYAt(sx, sz);
        int cy = underground ? l.getBlockY() - 4 : Math.max(w.getMinHeight() + 20, surface - (int) cfg("depth", 34));
        long seed = random.nextLong();
        Plan plan = plan(seed, sx, cy, sz, sx, sz, surface);
        building = true;
        p.sendMessage(ChatColor.DARK_PURPLE + "Weaving a Spider Nest " + (underground ? "around you" : (l.getBlockY() - cy) + " blocks under you") + "...");
        List<java.util.concurrent.CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int x = plan.minX >> 4; x <= plan.maxX >> 4; x++) for (int z = plan.minZ >> 4; z <= plan.maxZ >> 4; z++) {
            try { loads.add(w.getChunkAtAsync(x, z, true)); } catch (RuntimeException e) { loads.add(java.util.concurrent.CompletableFuture.completedFuture(w.getChunkAt(x, z))); }
        }
        java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    List<Chunk> held = new ArrayList<>();
                    for (var f : loads) try { Chunk c = f.getNow(null); if (c != null && c.addPluginChunkTicket(plugin)) held.add(c); } catch (RuntimeException ignored) { }
                    build(p, w, plan, sx, sz, held);
                }));
    }

    /** Carves and decorates, a slice a tick; then the throne, the queen and the save. */
    void build(CommandSender by, World w, Plan plan, int sx, int sz, List<Chunk> held) {
        Random r = new Random(plan.seed);
        List<int[]> air = new ArrayList<>();
        Set<Long> airSet = new HashSet<>();
        for (int x = plan.minX; x <= plan.maxX; x++) for (int z = plan.minZ; z <= plan.maxZ; z++) for (int y = plan.minY; y <= plan.maxY; y++) {
            if (y <= w.getMinHeight() + 2 || y >= w.getMaxHeight() - 2) continue;
            if (plan.cave(x, y, z)) { air.add(new int[]{x, y, z}); airSet.add(key(x, y, z)); }
        }
        int per = (int) cfg("blocks-per-tick", 20000);
        int[] stage = {0}, idx = {0};
        List<int[]> lining = new ArrayList<>();
        Map<String, List<Integer>> webs = new HashMap<>(), bodies = new HashMap<>();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                int done = 0;
                if (stage[0] == 0) { // carve
                    while (idx[0] < air.size() && done < per) {
                        int[] c = air.get(idx[0]++);
                        Block b = w.getBlockAt(c[0], c[1], c[2]);
                        if (b.getType() != Material.AIR && b.getType() != Material.CAVE_AIR && carvable(b.getType())) b.setType(Material.CAVE_AIR, false);
                        done++;
                    }
                    if (idx[0] >= air.size()) { stage[0] = 1; idx[0] = 0; }
                } else if (stage[0] == 1) { // walls: natural stone round the caves becomes the nest's stone; fluids walled off
                    while (idx[0] < air.size() && done < per / 6) {
                        int[] c = air.get(idx[0]++);
                        for (int[] d : SIX) {
                            int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                            if (airSet.contains(key(x, y, z))) continue;
                            Block b = w.getBlockAt(x, y, z);
                            Material m = b.getType();
                            if (b.isLiquid() || m == Material.WATER || m == Material.LAVA) b.setType(Material.TUFF, false);
                            else if (m.isAir()) { if (plan.surface == Integer.MIN_VALUE || y < plan.surface - 4) b.setType(Material.DEEPSLATE, false); } // a natural cave next door: wall it (never the way in at the surface)
                            else if (NATURAL.contains(m)) {
                                double nv = plan.noise(x / 4.0, y / 4.0, z / 4.0);
                                b.setType(nv > 0.35 ? Material.TUFF : nv < -0.4 ? Material.COBBLED_DEEPSLATE : r.nextDouble() < 0.05 ? Material.DEEPSLATE_TILES : Material.DEEPSLATE, false);
                            }
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
                for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] building failed", e);
                by.sendMessage(ChatColor.RED + "The nest couldn't be finished: " + e.getMessage());
            }
        }, 1L, 1L);
    }

    static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    private void decorate(World w, Plan plan, Set<Long> air, int[] c, Random r, Map<String, List<Integer>> webs, Map<String, List<Integer>> bodies) {
        int x = c[0], y = c[1], z = c[2];
        if (plan.surface != Integer.MIN_VALUE && y >= plan.surface - 1) return; // nothing in the open air at the way in
        boolean floor = !air.contains(key(x, y - 1, z)), ceil = !air.contains(key(x, y + 1, z));
        int walls = 0;
        for (int[] d : SIX) if (d[1] == 0 && !air.contains(key(x + d[0], y, z + d[2]))) walls++;
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isAir()) return;
        double roll = r.nextDouble();
        if (floor) {
            Block under = b.getRelative(0, -1, 0);
            if (under.getType().isAir() || !under.getType().isSolid()) return;
            if (roll < 0.012) setMark(b, Material.OCHRE_FROGLIGHT, null, null);                  // a glowing egg clutch
            else if (roll < 0.018) setMark(b, Material.BONE_BLOCK, null, null);
            else if (roll < 0.022) { body(b, r); mark(bodies, b); }
            else if (roll < 0.027) pot(b, r);
            else if (roll < 0.09 + walls * 0.08) { b.setType(Material.COBWEB, false); mark(webs, b); }
            else if (roll < 0.12) b.setType(Material.WHITE_CARPET, false);                       // silk on the floor
            else if (roll < 0.13) b.setType(Material.SNIFFER_EGG, false);
        } else if (ceil) {
            if (roll < 0.006) { // a soul lantern on a chain
                Block l = b;
                int len = 1 + r.nextInt(3);
                for (int i = 0; i < len && air.contains(key(x, l.getY() - 1, z)); i++) { l.setType(chain(), false); l = l.getRelative(0, -1, 0); }
                BlockData lan = Material.SOUL_LANTERN.createBlockData();
                if (lan instanceof org.bukkit.block.data.type.Lantern ln) ln.setHanging(true);
                l.setBlockData(lan, false);
            } else if (roll < 0.03) { // a hanging column of web
                Block l = b;
                int len = 2 + r.nextInt(6);
                for (int i = 0; i < len && l.getType().isAir() && air.contains(key(x, l.getY(), z)); i++) { l.setType(Material.COBWEB, false); mark(webs, l); l = l.getRelative(0, -1, 0); }
            } else if (roll < 0.26 + walls * 0.1) { b.setType(Material.COBWEB, false); mark(webs, b); }
        } else if (walls >= 2 && roll < 0.35) { b.setType(Material.COBWEB, false); mark(webs, b); }
        else if (walls == 1 && roll < 0.04) { b.setType(Material.COBWEB, false); mark(webs, b); }
    }

    private static Material chain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private void setMark(Block b, Material m, Object a, Object c) { b.setType(m, false); }

    private void mark(Map<String, List<Integer>> map, Block b) {
        Chunk c = b.getChunk();
        map.computeIfAbsent(c.getX() + "," + c.getZ(), k -> new ArrayList<>()).add(local(b));
    }

    static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }

    private void body(Block b, Random r) {
        b.setType(Material.SKELETON_SKULL, false);
        if (b.getBlockData() instanceof Rotatable rot) { rot.setRotation(org.bukkit.block.BlockFace.values()[r.nextInt(4)]); b.setBlockData(rot, false); }
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Block n = b.getRelative(d[0], 0, d[1]);
            if (n.getType().isAir() && n.getRelative(0, -1, 0).getType().isSolid() && r.nextDouble() < 0.5)
                n.setType(r.nextBoolean() ? Material.BONE_BLOCK : Material.COBWEB, false);
        }
    }

    private void pot(Block b, Random r) {
        b.setType(Material.DECORATED_POT, false);
        try {
            if (b.getState() instanceof DecoratedPot pot) {
                List<ItemStack> l = roll(r, 1);
                if (!l.isEmpty()) pot.getSnapshotInventory().setItem(l.get(0)); // the snapshot: update() writes it (the live one would be overwritten)
                pot.update(true, false);
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private void finish(CommandSender by, World w, Plan plan, Set<Long> air, Random r, Map<String, List<Integer>> webs, Map<String, List<Integer>> bodies,
                        List<Chunk> held, int sx, int sz) {
        Room throne = plan.rooms.get(0);
        // the throne: a mound of web, wool and bones, and the floor under her
        int tx = (int) Math.floor(throne.x), tz = (int) Math.floor(throne.z);
        int ty = (int) Math.floor(throne.y);
        while (ty > plan.minY && air.contains(key(tx, ty - 1, tz))) ty--;
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 7) continue;
            int h = (int) Math.round(2.2 * (1 - d / 7.5));
            for (int dy = 0; dy < h; dy++) {
                Block b = w.getBlockAt(tx + dx, ty + dy, tz + dz);
                if (!air.contains(key(b.getX(), b.getY(), b.getZ()))) continue;
                b.setType(dy == h - 1 ? (r.nextDouble() < 0.6 ? Material.WHITE_WOOL : Material.BONE_BLOCK) : Material.WHITE_WOOL, false);
            }
            if (d > 3.5 && r.nextDouble() < 0.35) {
                Block top = w.getBlockAt(tx + dx, ty + Math.max(0, h), tz + dz);
                if (top.getType().isAir() && air.contains(key(top.getX(), top.getY(), top.getZ()))) { top.setType(r.nextDouble() < 0.3 ? Material.SKELETON_SKULL : Material.COBWEB, false); if (top.getType() == Material.COBWEB) mark(webs, top); }
            }
        }
        int throneY = ty + 2;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 6; dy++) { // room for her
            Block b = w.getBlockAt(tx + dx, throneY + dy, tz + dz);
            if (b.getType() == Material.COBWEB || b.getType() == Material.SKELETON_SKULL) b.setType(Material.CAVE_AIR, false);
        }
        // chests and spawners in the chambers
        for (int i = 1; i < plan.rooms.size(); i++) {
            Room room = plan.rooms.get(i);
            Block f = floorIn(w, air, room);
            if (f == null) continue;
            if (i % 2 == 1 && i <= 11) {
                f.setType(Material.CHEST, false);
                if (f.getState() instanceof Chest ch) { // three rolls (a body's), scattered through the chest
                    var inv = ch.getSnapshotInventory();
                    for (int k = 0; k < 3; k++) for (ItemStack it : roll(r, 2)) inv.setItem(r.nextInt(inv.getSize()), it);
                    ch.update(true, false);
                }
            } else if (i % 3 == 2) {
                f.setType(Material.SPAWNER, false);
                if (f.getState() instanceof CreatureSpawner sp) { sp.setSpawnedType(EntityType.CAVE_SPIDER); sp.update(true, false); }
                for (int[] d : SIX) { Block n = f.getRelative(d[0], d[1], d[2]); if (n.getType().isAir() && d[1] >= 0) { n.setType(Material.COBWEB, false); mark(webs, n); } }
            }
            silk(w, air, room, r);
        }
        silk(w, air, throne, r);
        silk(w, air, throne, r);
        // the loot marks into each chunk
        writeMarks(w, webs, webKey);
        writeMarks(w, bodies, bodyKey);
        Nest n = new Nest();
        n.id = "nest" + (nests.size() + 1) + "_" + Integer.toHexString(r.nextInt(0xFFFF));
        n.world = w.getName();
        n.cx = (int) throne.x; n.cy = (int) throne.y; n.cz = (int) throne.z;
        n.tx = tx; n.ty = throneY; n.tz = tz;
        n.radius = Math.max(plan.maxX - plan.minX, plan.maxZ - plan.minZ) / 2 + 4;
        n.respawnAt = 0;
        nests.put(n.id, n);
        save();
        try { spawnQueen(n); } catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[SpiderNest] the queen couldn't be placed (she'll try again)", e); }
        for (Chunk c : held) try { c.removePluginChunkTicket(plugin); } catch (RuntimeException ignored) { }
        building = false;
        by.sendMessage(ChatColor.DARK_PURPLE + "The Spider Nest is done (" + air.size() + " blocks of cave, " + plan.rooms.size() + " chambers). "
                + ChatColor.GRAY + "Her throne is at " + tx + " " + throneY + " " + tz + ". Id: " + n.id);
        plugin.getLogger().info("[SpiderNest] made " + n.id + " at " + w.getName() + " " + tx + " " + throneY + " " + tz);
    }

    /** A floor cell near the middle of a room. */
    private Block floorIn(World w, Set<Long> air, Room room) {
        for (int tries = 0; tries < 30; tries++) {
            int x = (int) Math.floor(room.x + (random.nextDouble() - 0.5) * room.rx), z = (int) Math.floor(room.z + (random.nextDouble() - 0.5) * room.rz);
            for (int y = (int) room.y; y > room.y - room.ry - 3; y--) {
                if (air.contains(key(x, y, z)) && !air.contains(key(x, y - 1, z))) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getRelative(0, -1, 0).getType().isSolid()) return b;
                }
            }
        }
        return null;
    }

    /** Silk threads slung across a chamber: thin white BlockDisplays from the ceiling down to a wall or the floor. */
    private void silk(World w, Set<Long> air, Room room, Random r) {
        for (int i = 0; i < 3; i++) {
            Location a = surface(w, air, room, r, 1), b = surface(w, air, room, r, -1);
            if (a == null || b == null) continue;
            org.bukkit.util.Vector d = b.toVector().subtract(a.toVector());
            float len = (float) d.length();
            if (len < 3) continue;
            Location at = a.clone(); at.setYaw(0); at.setPitch(0);
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize());
            w.spawn(at, BlockDisplay.class, e -> {
                e.setPersistent(true);
                e.addScoreboardTag("faultline_nest_silk");
                e.setBlock(Material.WHITE_CONCRETE.createBlockData());
                e.setBrightness(new org.bukkit.entity.Display.Brightness(6, 6));
                e.setViewRange(0.5f);
                e.setTransformation(new Transformation(q.transform(new Vector3f(-0.03f, -0.03f, 0)), q, new Vector3f(0.06f, 0.06f, len), new Quaternionf()));
            });
        }
    }

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
        if (m != Material.COBWEB && m != Material.SKELETON_SKULL) return;
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
            e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("You search the remains...", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
        Location at = b.getLocation().add(0.5, 0.4, 0.5);
        for (ItemStack it : drop) b.getWorld().dropItemNaturally(at, it);
        b.getWorld().spawnParticle(Particle.WHITE_ASH, at, 12, 0.3, 0.3, 0.3, 0);
    }

    // ============================================================================================ the queen, ambience
    Nest nestAt(Location l) {
        for (Nest n : nests.values()) {
            if (!n.world.equals(l.getWorld().getName())) continue;
            if (Math.hypot(l.getX() - n.cx, l.getZ() - n.cz) < n.radius && Math.abs(l.getY() - n.cy) < 40 && l.getBlock().getLightFromSky() == 0) return n;
        }
        return null;
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
            if (n.queen == null && now >= n.respawnAt && w.isChunkLoaded(n.tx >> 4, n.tz >> 4)) {
                boolean someone = false;
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(n.throne(w)) < 24 * 24) someone = true;
                if (!someone) spawnQueen(n);
            }
        }
        // ambience and brood for players inside
        for (Player p : Bukkit.getOnlinePlayers()) {
            Nest n = nestAt(p.getLocation());
            String was = inside.get(p.getUniqueId());
            if (n == null) { inside.remove(p.getUniqueId()); continue; }
            if (!n.id.equals(was)) {
                inside.put(p.getUniqueId(), n.id);
                p.sendTitle(ChatColor.DARK_PURPLE + "Spider Nest", ChatColor.GRAY + "Something very large is asleep down here.", 10, 50, 15);
                p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.6f);
            }
            if (random.nextDouble() < 0.25) p.playSound(p.getLocation().add(random.nextGaussian() * 8, random.nextGaussian() * 3, random.nextGaussian() * 8), Sound.ENTITY_SPIDER_AMBIENT, SoundCategory.HOSTILE, 0.5f, 0.6f + random.nextFloat() * 0.6f);
            p.spawnParticle(Particle.WHITE_ASH, p.getLocation().add(0, 1.5, 0), 10, 5, 2, 5, 0);
            if (p.getGameMode() == org.bukkit.GameMode.SURVIVAL && random.nextDouble() < cfg("spider-spawn-chance", 0.12)) {
                int near = 0;
                for (Entity e : p.getNearbyEntities(20, 8, 20)) if (e.getType() == EntityType.SPIDER || e.getType() == EntityType.CAVE_SPIDER) near++;
                if (near < cfg("spider-cap", 6)) {
                    Location at = p.getLocation().add(random.nextGaussian() * 9, 0, random.nextGaussian() * 9);
                    Block b = at.getBlock();
                    for (int i = 0; i < 6 && !b.getType().isAir(); i++) b = b.getRelative(0, 1, 0);
                    if (b.getType().isAir() && b.getRelative(0, -1, 0).getType().isSolid() && b.getLightFromBlocks() < 8 && b.getLocation().distanceSquared(p.getLocation()) > 25)
                        b.getWorld().spawnEntity(b.getLocation().add(0.5, 0, 0.5), random.nextDouble() < 0.6 ? EntityType.CAVE_SPIDER : EntityType.SPIDER, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
                }
            }
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

    // ============================================================================================ /queenspider nest
    boolean command(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "here";
        switch (sub) {
            case "here", "create", "make" -> { if (s instanceof Player p) create(p); else s.sendMessage("Players only."); }
            case "list" -> {
                if (nests.isEmpty()) s.sendMessage(ChatColor.GRAY + "No nests.");
                for (Nest n : nests.values()) s.sendMessage(ChatColor.DARK_PURPLE + n.id + ChatColor.GRAY + " " + n.world + " " + n.tx + " " + n.ty + " " + n.tz
                        + (n.queen != null ? (n.queen.asleep ? " (queen asleep)" : " (queen awake)") : n.respawnAt > System.currentTimeMillis() ? " (queen back in " + (n.respawnAt - System.currentTimeMillis()) / 60000 + " min)" : " (queen comes when loaded)"));
            }
            case "tp" -> {
                Nest n = a.length > 2 ? nests.get(a[2]) : nests.values().stream().findFirst().orElse(null);
                if (n == null || !(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "/queenspider nest tp <id>"); return true; }
                p.teleport(new Location(Bukkit.getWorld(n.world), n.tx + 0.5, n.ty, n.tz + 10.5));
            }
            case "respawn" -> {
                Nest n = a.length > 2 ? nests.get(a[2]) : null;
                if (n == null) { s.sendMessage(ChatColor.RED + "/queenspider nest respawn <id>"); return true; }
                if (n.queen != null) queens.remove(n.queen);
                n.queen = null; n.respawnAt = 0; save();
                s.sendMessage(ChatColor.GREEN + "She'll be back asleep on her throne in a moment.");
            }
            case "remove", "forget" -> {
                Nest n = a.length > 2 ? nests.get(a[2]) : null;
                if (n == null) { s.sendMessage(ChatColor.RED + "/queenspider nest remove <id> (the caves stay; she stops coming back)"); return true; }
                if (n.queen != null) queens.remove(n.queen);
                nests.remove(n.id); save();
                s.sendMessage(ChatColor.YELLOW + "Forgot " + n.id + ". The caves stay as they are.");
            }
            default -> s.sendMessage(ChatColor.YELLOW + "/queenspider nest [here|list|tp <id>|respawn <id>|remove <id>]");
        }
        return true;
    }

    void shutdown() {
        for (Nest n : nests.values()) if (n.queen != null) { queens.remove(n.queen); n.queen = null; }
        save();
    }

    ItemStack menuItem() {
        ItemStack it = new ItemStack(Material.COBWEB);
        var m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Spawn a Spider Nest");
        m.setLore(List.of(ChatColor.GRAY + "Click: carves a Spider Nest biome under you", ChatColor.GRAY + "(28+ blocks down, a tunnel up to here).",
                ChatColor.GRAY + "The Queen sleeps on her throne in it.", ChatColor.RED + "It's permanent: the caves stay."));
        it.setItemMeta(m);
        return it;
    }
}
