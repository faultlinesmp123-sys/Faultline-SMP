package net.faultlinesmp.items;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/**
 * The mimic's pathfinder: A* over the block grid with a player's moves. A node is a cell its feet stand in.
 * WALK (8 ways, no corner cutting), STEP (onto stairs / slabs / anything 1 up), DROP (off a ledge, as far as its health or a
 * water landing allows), DOOR (a closed wooden door / gate in the way: opened), DIG (mines what's in the way with the right
 * tool; player-built blocks cost a lot more, so it takes the door), DIGDOWN (mines the floor to get down), PILLAR (jump +
 * block under it), CLIMB (ladders / vines), SWIM. Too far / no way = the partial path that gets closest, so it always moves.
 */
final class MimicNav {
    enum Type { WALK, STEP, DROP, DIG, DIGDOWN, PILLAR, CLIMB, SWIM, GAP, BRIDGE }

    static final class Step {
        final int x, y, z;
        final Type type;
        final List<Block> dig;
        final Block door;
        Step(int x, int y, int z, Type type, List<Block> dig, Block door) { this.x = x; this.y = y; this.z = z; this.type = type; this.dig = dig; this.door = door; }
        double cx() { return x + 0.5; }
        double cz() { return z + 0.5; }
        public String toString() { return type + "(" + x + "," + y + "," + z + (dig.isEmpty() ? "" : " dig" + dig.size()) + (door != null ? " door" : "") + ")"; }
    }

    /** What the walker can do. */
    interface Rules {
        /** Ticks to mine this block with what it carries, or -1 = it can't. */
        int mineTicks(Block b);
        /** The furthest it drops onto solid ground. */
        int maxDrop();
        /** Blocks to pillar with. */
        boolean canPillar();
        /** Blocks to bridge with (over gaps, water, lava). */
        default boolean canBridge() { return canPillar(); }
        /** The most fall damage it'll take for a shortcut. */
        default double maxFallDamage() { return Math.max(0, maxDrop() - 3); }
        /** A penalty for this edge (an edge that got it stuck), keyed by from/to. */
        double penalty(long from, long to);
    }

    static final class Result {
        final List<Step> steps;
        final boolean reached;
        final int expanded;
        Result(List<Step> steps, boolean reached, int expanded) { this.steps = steps; this.reached = reached; this.expanded = expanded; }
    }

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    private final World w;
    private final Rules rules;
    /**
     * 1.2.95: cells are shared by every search in a world for half a second (`CELLS`, a primitive long -> byte table: the old
     * Map<Long, Byte> boxed every lookup, and each search read every block again). `dirty(w)` drops a world's table when a
     * mimic changes a block itself.
     */
    private final Cells cache;
    private static final Map<World, Cells> CELLS = new HashMap<>();
    static int clock;

    static final class Cells {
        long[] keys = new long[4096]; byte[] vals = new byte[4096]; boolean[] used = new boolean[4096]; int size, born = clock; Cells edges;
        byte get(long k) { int m = keys.length - 1, i = mix(k) & m; while (used[i]) { if (keys[i] == k) return vals[i]; i = (i + 1) & m; } return -1; }
        void put(long k, byte v) {
            if (size * 2 >= keys.length) grow();
            int m = keys.length - 1, i = mix(k) & m;
            while (used[i]) { if (keys[i] == k) { vals[i] = v; return; } i = (i + 1) & m; }
            used[i] = true; keys[i] = k; vals[i] = v; size++;
        }
        private void grow() {
            long[] ok = keys; byte[] ov = vals; boolean[] ou = used;
            keys = new long[ok.length * 2]; vals = new byte[ok.length * 2]; used = new boolean[ok.length * 2]; size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], ov[i]);
        }
        private static int mix(long k) { k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33; return (int) k; }
    }

    private int startX, startZ;

    static void dirty(World w) { CELLS.remove(w); }

    private static Cells cellsFor(World w) {
        Cells c = CELLS.get(w);
        if (c == null || clock - c.born > 10 || c.size > 400_000) { c = new Cells(); CELLS.put(w, c); }
        return c;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    MimicNav(World w, Rules rules) { this.w = w; this.rules = rules; this.cache = cellsFor(w); }

    // ---- cells. bits: 1 passable, 2 solid floor-able, 4 hazard, 8 water, 16 climbable, 32 closed door, 64 fence-like, 128 cobweb
    private byte cell(int x, int y, int z) {
        long k = key(x, y, z);
        byte c = cache.get(k);
        if (c != -1) return c;
        byte v = 0;
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) v = 2; // out of the world / unloaded = a wall
        else {
            Block b = w.getBlockAt(x, y, z);
            Material t = b.getType();
            boolean plain = t.isAir() || t == Material.STONE || t == Material.DIRT || t == Material.GRASS_BLOCK || t == Material.DEEPSLATE;
            org.bukkit.block.data.BlockData bd = plain ? null : b.getBlockData(); // read once (it makes a copy on a real server)
            if (MimicPlayer.HAZARD.contains(t)) v |= 4;
            if (t == Material.WATER || t == Material.BUBBLE_COLUMN || (bd instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged() && !t.isSolid())) v |= 8 | 1;
            if (t == Material.LADDER || t == Material.VINE || t == Material.SCAFFOLDING || Tag.CLIMBABLE.isTagged(t)) v |= 16 | 1;
            if (t == Material.COBWEB) v |= (byte) (128 | 1);
            if (Tag.FENCES.isTagged(t) || Tag.WALLS.isTagged(t) || Tag.FENCE_GATES.isTagged(t)) v |= 64;
            if (bd instanceof org.bukkit.block.data.Openable o && t != Material.IRON_DOOR && t != Material.IRON_TRAPDOOR && !(bd instanceof org.bukkit.block.data.type.TrapDoor)) {
                if (o.isOpen()) v |= 1; else v |= 32;
            } else if (!t.isSolid()) { if ((v & 4) == 0) v |= 1; }
            else v |= 2;
        }
        cache.put(k, v);
        return v;
    }
    private boolean pass(int x, int y, int z) { byte c = cell(x, y, z); return (c & 1) != 0 && (c & 4) == 0; }
    private boolean passOrDoor(int x, int y, int z) { byte c = cell(x, y, z); return ((c & 1) != 0 || (c & 32) != 0) && (c & 4) == 0; }
    private boolean floor(int x, int y, int z) { byte c = cell(x, y, z); return (c & 2) != 0 && (c & 64) == 0 && (c & 4) == 0; }
    private boolean hazard(int x, int y, int z) { return (cell(x, y, z) & 4) != 0; }
    private boolean water(int x, int y, int z) { return (cell(x, y, z) & 8) != 0; }
    private boolean climb(int x, int y, int z) { return (cell(x, y, z) & 16) != 0; }
    private boolean door(int x, int y, int z) { return (cell(x, y, z) & 32) != 0; }
    private boolean web(int x, int y, int z) { return (cell(x, y, z) & 128) != 0; }

    /** Feet here: room for a body and something to stand on (or water / a ladder). */
    boolean standable(int x, int y, int z) {
        if (!passOrDoor(x, y, z) || !passOrDoor(x, y + 1, z)) return false;
        if (water(x, y, z) || climb(x, y, z)) return true;
        return floor(x, y - 1, z);
    }

    /** The cost to mine this cell clear (0 = already clear, -1 = can't). */
    private double clearCost(int x, int y, int z) {
        if (passOrDoor(x, y, z)) return 0;
        if (hazard(x, y, z)) return -1;
        Block b = w.getBlockAt(x, y, z);
        int t = rules.mineTicks(b);
        if (t < 0) return -1;
        // never opens a hole into lava or water (1.2.93: below too: mining a floor over lava)
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}}) {
            if (y + d[1] < w.getMinHeight() || y + d[1] >= w.getMaxHeight()) continue;
            Material n = w.getBlockAt(x + d[0], y + d[1], z + d[2]).getType();
            if (n == Material.LAVA || n == Material.WATER) return -1;
        }
        // sand / gravel stacked over it falls in: each one has to be dug too (1.2.93)
        double extra = 0;
        for (int k = 1; k <= 6 && y + k < w.getMaxHeight(); k++) {
            Block up = w.getBlockAt(x, y + k, z);
            if (!up.getType().hasGravity()) break;
            int ut = rules.mineTicks(up);
            if (ut < 0) return -1;
            extra += 1 + ut / 4.0;
        }
        return 1 + t / 4.0 * (natural(b.getType()) ? 1 : 4) + (natural(b.getType()) ? 0 : 6) + extra; // it'd rather use the door than tear a house open
    }

    static boolean natural(Material t) {
        String n = t.name();
        return Tag.BASE_STONE_OVERWORLD.isTagged(t) || Tag.BASE_STONE_NETHER.isTagged(t) || Tag.DIRT.isTagged(t) || Tag.SAND.isTagged(t)
                || Tag.LEAVES.isTagged(t) || Tag.LOGS.isTagged(t) || n.endsWith("_ORE") || t == Material.GRAVEL || t == Material.CLAY
                || t == Material.SNOW_BLOCK || t == Material.SANDSTONE || t == Material.RED_SANDSTONE || t == Material.TERRACOTTA
                || n.endsWith("_TERRACOTTA") && !n.contains("GLAZED") || t == Material.COBBLESTONE || t == Material.MOSSY_COBBLESTONE
                || t == Material.CALCITE || t == Material.DRIPSTONE_BLOCK || t == Material.MUD || t == Material.ICE || t == Material.PACKED_ICE;
    }

    private record Node(long key, int x, int y, int z, double g, double f, Node parent, Step step) { }

    /**
     * From where its feet are to anywhere within `near` (flat) and 1 up/down of the goal. `budget` = nodes it may look at.
     */
    Result find(int sx, int sy, int sz, int gx, int gy, int gz, double near, int budget, int range) {
        startX = sx; startZ = sz;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(key(sx, sy, sz), sx, sy, sz, 0, h(sx, sy, sz, gx, gy, gz), null, null);
        open.add(start);
        best.put(start.key, 0.0);
        Node closest = start;
        double closestH = h(sx, sy, sz, gx, gy, gz);
        int n = 0;
        while (!open.isEmpty() && n < budget) {
            Node c = open.poll();
            Double bg = best.get(c.key);
            if (bg != null && c.g > bg + 1e-9) continue;
            n++;
            double flat = Math.hypot(c.x - gx, c.z - gz);
            if (flat <= near && Math.abs(c.y - gy) <= 1) return new Result(trace(c), true, n);
            double hc = h(c.x, c.y, c.z, gx, gy, gz);
            if (hc < closestH) { closestH = hc; closest = c; }
            for (Node nb : neighbours(c, gx, gy, gz)) {
                if (Math.abs(nb.x - sx) > range || Math.abs(nb.z - sz) > range) continue;
                Double old = best.get(nb.key);
                if (old != null && old <= nb.g) continue;
                best.put(nb.key, nb.g);
                open.add(nb);
            }
        }
        return new Result(trace(closest), false, n);
    }

    private static double h(int x, int y, int z, int gx, int gy, int gz) {
        double dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        double flat = Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
        return (flat + Math.abs(y - gy) * 1.2) * 1.25; // a little greedy: fast, still good paths
    }

    private List<Step> trace(Node c) {
        LinkedList<Step> out = new LinkedList<>();
        for (Node p = c; p != null && p.step != null; p = p.parent) out.addFirst(p.step);
        return new ArrayList<>(out);
    }

    /** Slow ground costs more (soul sand, honey, mud... ), so it goes round when it can. */
    private double terrain(int x, int y, int z) {
        Material t = w.getBlockAt(x, y - 1, z).getType();
        if (t == Material.SOUL_SAND || t == Material.HONEY_BLOCK) return 1.5;
        if (t == Material.SLIME_BLOCK) return 0.8;
        return 0;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * 1.2.96 edge-aware routes: a cell beside a ledge (2+ blocks of air under the side cell) or lava / fire costs a little
     * more, so it walks down the middle of a bridge / away from the lava when there's room (a knockback or a slip there hurts).
     */
    private double edge(int x, int y, int z) {
        long k = key(x, y, z);
        if (cache.edges == null) cache.edges = new Cells(); // its own table, dropped with the cells
        byte got = cache.edges.get(k);
        if (got != -1) return got / 10.0;
        double c = edge0(x, y, z);
        cache.edges.put(k, (byte) Math.round(c * 10));
        return c;
    }

    private double edge0(int x, int y, int z) {
        double c = 0;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (hazard(nx, y, nz) || hazard(nx, y - 1, nz)) c += 1.2;
            else if (pass(nx, y, nz) && pass(nx, y - 1, nz) && pass(nx, y - 2, nz) && !water(nx, y - 1, nz) && !water(nx, y - 2, nz)) {
                c += 0.35;
                // 1.2.98: a long fall / the void right beside it (32+ blocks of nothing): a knockback there kills, keep well off it
                boolean deep = true;
                for (int yy = y - 3; yy >= Math.max(w.getMinHeight(), y - 32); yy--) if (!pass(nx, yy, nz) || water(nx, yy, nz)) { deep = false; break; }
                if (deep) c += 1.6;
            }
        }
        return Math.min(c, 4.0);
    }

    /** Fall damage a drop of k blocks onto this block does (hay / beds / honey soften it, slime and water take it all). */
    private double fallDamage(int x, int landY, int z, int k) {
        if (water(x, landY, z)) return 0;
        Material t = w.getBlockAt(x, landY - 1, z).getType();
        double mult = t == Material.SLIME_BLOCK || t == Material.POWDER_SNOW || t == Material.COBWEB ? 0 : t == Material.HAY_BLOCK || t == Material.HONEY_BLOCK ? 0.2 : Tag.BEDS.isTagged(t) ? 0.5 : 1;
        return Math.max(0, k - 3) * mult;
    }

    private Node node(Node from, int x, int y, int z, double cost, Type type, List<Block> dig, Block door, int gx, int gy, int gz) {
        long k = key(x, y, z);
        if (type != Type.SWIM && type != Type.CLIMB) cost += terrain(x, y, z);
        if ((type == Type.WALK || type == Type.STEP) && Math.abs(x - startX) + Math.abs(z - startZ) < 20) cost += edge(x, y, z); // near it, where a slip matters now
        double g = from.g + cost + rules.penalty(from.key, k);
        return new Node(k, x, y, z, g, g + h(x, y, z, gx, gy, gz), from, new Step(x, y, z, type, dig, door));
    }

    private List<Node> neighbours(Node c, int gx, int gy, int gz) {
        List<Node> out = new ArrayList<>(16);
        int x = c.x, y = c.y, z = c.z;
        boolean inWater = water(x, y, z);
        if (inWater) { // swimming: any way through water / onto a bank
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (water(nx, ny, nz) && passOrDoor(nx, ny + 1, nz) || standable(nx, ny, nz)) out.add(node(c, nx, ny, nz, 2, Type.SWIM, List.of(), null, gx, gy, gz));
                else if (d[1] == 0 && standable(nx, ny + 1, nz) && pass(x, y + 2, z)) out.add(node(c, nx, ny + 1, nz, 2.5, Type.STEP, List.of(), null, gx, gy, gz));
            }
            return out;
        }
        if (climb(x, y, z)) { // ladders / vines
            if (passOrDoor(x, y + 1, z) && passOrDoor(x, y + 2, z) || climb(x, y + 1, z)) out.add(node(c, x, y + 1, z, 1.3, Type.CLIMB, List.of(), null, gx, gy, gz));
            if (climb(x, y - 1, z) || standable(x, y - 1, z)) out.add(node(c, x, y - 1, z, 1.1, Type.CLIMB, List.of(), null, gx, gy, gz));
        }
        for (int i = 0; i < DIRS.length; i++) {
            int dx = DIRS[i][0], dz = DIRS[i][1];
            int nx = x + dx, nz = z + dz;
            boolean diag = dx != 0 && dz != 0;
            double base = diag ? 1.414 : 1;
            if (diag) { // no corner cutting: both sides open at feet and head
                if (!pass(x + dx, y, z) || !pass(x + dx, y + 1, z) || !pass(x, y, z + dz) || !pass(x, y + 1, z + dz)) continue;
                if (standable(nx, y, nz) && !door(nx, y, nz)) out.add(node(c, nx, y, nz, base + (web(nx, y, nz) ? 8 : 0), Type.WALK, List.of(), null, gx, gy, gz));
                continue;
            }
            // WALK / DOOR on the level
            if (standable(nx, y, nz)) {
                Block dr = door(nx, y, nz) ? w.getBlockAt(nx, y, nz) : null;
                out.add(node(c, nx, y, nz, base + (dr != null ? 1 : 0) + (web(nx, y, nz) ? 8 : 0), Type.WALK, List.of(), dr, gx, gy, gz));
                continue;
            }
            // STEP up one (stairs, slabs, a block, a ledge): its own head room to jump, room up there
            if (floor(nx, y, nz) && pass(nx, y + 1, nz) && pass(nx, y + 2, nz) && (pass(x, y + 2, z) || halfStep(nx, y, nz))) {
                out.add(node(c, nx, y + 1, nz, base + 0.6, Type.STEP, List.of(), null, gx, gy, gz));
                continue;
            }
            // DROP off a ledge: the way out is open, falls to the first floor below
            if (pass(nx, y, nz) && pass(nx, y + 1, nz) && !floor(nx, y - 1, nz)) {
                double maxDmg = rules.maxFallDamage();
                for (int k = 1; k <= 60; k++) {
                    int ly = y - k;
                    if (hazard(nx, ly, nz)) break;
                    if (water(nx, ly, nz)) { out.add(node(c, nx, ly, nz, base + k * 0.15 + 1, Type.DROP, List.of(), null, gx, gy, gz)); break; }
                    if (!pass(nx, ly, nz)) break;
                    if (floor(nx, ly - 1, nz)) {
                        double dmg = fallDamage(nx, ly, nz, k);
                        if (dmg <= maxDmg) out.add(node(c, nx, ly, nz, base + k * 0.25 + dmg * 1.5, Type.DROP, List.of(), null, gx, gy, gz));
                        break;
                    }
                }
                // PARKOUR: a sprint-jump across the gap (1-3 wide; 1 up over 1-2; 1 down over 1-3)
                if (pass(x, y + 2, z)) {
                    for (int g = 1; g <= 3; g++) {
                        int mx = x + dx * g, mz = z + dz * g;
                        if (!pass(mx, y, mz) || !pass(mx, y + 1, mz) || !pass(mx, y + 2, mz) || floor(mx, y - 1, mz) && g > 1) break;
                        int lx = x + dx * (g + 1), lz = z + dz * (g + 1);
                        for (int dyl : new int[]{0, 1, -1}) {
                            if (dyl == 1 && g > 2) continue;
                            if (!standable(lx, y + dyl, lz) || door(lx, y + dyl, lz) || !pass(lx, y + dyl + 2, lz) && dyl >= 0) continue;
                            out.add(node(c, lx, y + dyl, lz, (g + 1) + 1.2 + (g == 3 ? 1 : 0) + (dyl == 1 ? 0.8 : 0), Type.GAP, List.of(), null, gx, gy, gz));
                            break;
                        }
                    }
                }
                // BRIDGE: a block placed under the next cell (over a gap, water or lava), like a player
                if (rules.canBridge() && replaceable(nx, y - 1, nz)) out.add(node(c, nx, y, nz, base + 2.2, Type.BRIDGE, List.of(), null, gx, gy, gz));
                continue;
            }
            // water / lava on the level: bridge it (lava never gets walked into)
            if (rules.canBridge() && (water(nx, y - 1, nz) || hazard(nx, y - 1, nz)) && pass(nx, y, nz) && pass(nx, y + 1, nz) && replaceable(nx, y - 1, nz)) {
                out.add(node(c, nx, y, nz, base + 2.2, Type.BRIDGE, List.of(), null, gx, gy, gz));
            }
            // DIG through (feet and head), on the level
            if (floor(nx, y - 1, nz)) {
                double a = clearCost(nx, y, nz), b = clearCost(nx, y + 1, nz);
                if (a >= 0 && b >= 0 && a + b > 0) out.add(node(c, nx, y, nz, base + a + b, Type.DIG, digList(nx, y, nz, a, b), null, gx, gy, gz));
            }
            // DIG up a step (a staircase through rock): the block over its head, the two above the next block
            if (floor(nx, y, nz)) {
                double o = clearCost(x, y + 2, z), a = clearCost(nx, y + 1, nz), b = clearCost(nx, y + 2, nz);
                if (o >= 0 && a >= 0 && b >= 0 && o + a + b > 0) {
                    List<Block> d = new ArrayList<>();
                    if (o > 0) d.add(w.getBlockAt(x, y + 2, z));
                    if (b > 0) d.add(w.getBlockAt(nx, y + 2, nz));
                    if (a > 0) d.add(w.getBlockAt(nx, y + 1, nz));
                    out.add(node(c, nx, y + 1, nz, base + 0.6 + o + a + b, Type.STEP, d, null, gx, gy, gz));
                }
            }
        }
        // 1.2.93 DIG DOWN A STEP: a staircase down through rock (head, feet, then the block it steps down onto), never a pit
        for (int i = 0; i < DIRS.length; i++) {
            int dx = DIRS[i][0], dz = DIRS[i][1];
            if (dx != 0 && dz != 0) continue;
            int nx = x + dx, nz = z + dz;
            if (!floor(nx, y - 2, nz) || hazard(nx, y - 2, nz)) continue;
            double h = clearCost(nx, y + 1, nz), a = clearCost(nx, y, nz), b = clearCost(nx, y - 1, nz);
            if (h < 0 || a < 0 || b < 0 || h + a + b == 0) continue;
            List<Block> d = new ArrayList<>(3);
            if (h > 0) d.add(w.getBlockAt(nx, y + 1, nz));
            if (a > 0) d.add(w.getBlockAt(nx, y, nz));
            if (b > 0) d.add(w.getBlockAt(nx, y - 1, nz));
            out.add(node(c, nx, y - 1, nz, 1.3 + h + a + b, Type.DIG, d, null, gx, gy, gz));
        }
        // DIGDOWN: mines the floor and drops into the hole
        if (!water(x, y - 1, z) && floor(x, y - 1, z)) {
            double a = clearCost(x, y - 1, z);
            if (a > 0) {
                int max = rules.maxDrop();
                for (int k = 2; k <= max + 1; k++) { // where it lands
                    int ly = y - k;
                    if (k > 2 && !pass(x, ly + 1, z)) break;
                    if (floor(x, ly, z) || water(x, ly + 1, z)) { out.add(node(c, x, ly + 1, z, 1 + a + (k - 2) * 0.3, Type.DIGDOWN, List.of(w.getBlockAt(x, y - 1, z)), null, gx, gy, gz)); break; }
                    if (hazard(x, ly, z)) break;
                }
            }
        }
        // PILLAR: jump and put a block under itself (only worth it toward something above)
        if (rules.canPillar() && gy > y && pass(x, y + 2, z) && floor(x, y - 1, z)) out.add(node(c, x, y + 1, z, 3.5, Type.PILLAR, List.of(), null, gx, gy, gz));
        return out;
    }

    /** Something a block can be put into: air, water, lava (not another block). */
    private boolean replaceable(int x, int y, int z) {
        if (y < w.getMinHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material t = w.getBlockAt(x, y, z).getType();
        return t.isAir() || t == Material.WATER || t == Material.LAVA;
    }

    private boolean halfStep(int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Slab s) return s.getType() == org.bukkit.block.data.type.Slab.Type.BOTTOM;
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Stairs s) return s.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM;
        return false;
    }

    private List<Block> digList(int x, int y, int z, double a, double b) {
        List<Block> d = new ArrayList<>(2);
        if (b > 0) d.add(w.getBlockAt(x, y + 1, z)); // the head block first (like a player tunnelling)
        if (a > 0) d.add(w.getBlockAt(x, y, z));
        return d;
    }
}
