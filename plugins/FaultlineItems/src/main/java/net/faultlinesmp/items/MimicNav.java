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
    enum Type { WALK, STEP, DROP, DIG, DIGDOWN, PILLAR, CLIMB, SWIM }

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
    private final Map<Long, Byte> cache = new HashMap<>();
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    MimicNav(World w, Rules rules) { this.w = w; this.rules = rules; }

    // ---- cells. bits: 1 passable, 2 solid floor-able, 4 hazard, 8 water, 16 climbable, 32 closed door, 64 fence-like, 128 cobweb
    private byte cell(int x, int y, int z) {
        long k = key(x, y, z);
        Byte c = cache.get(k);
        if (c != null) return c;
        byte v = 0;
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) v = 2; // out of the world / unloaded = a wall
        else {
            Block b = w.getBlockAt(x, y, z);
            Material t = b.getType();
            if (MimicPlayer.HAZARD.contains(t)) v |= 4;
            if (t == Material.WATER || t == Material.BUBBLE_COLUMN || (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged() && !t.isSolid())) v |= 8 | 1;
            if (t == Material.LADDER || t == Material.VINE || t == Material.SCAFFOLDING || Tag.CLIMBABLE.isTagged(t)) v |= 16 | 1;
            if (t == Material.COBWEB) v |= (byte) (128 | 1);
            if (Tag.FENCES.isTagged(t) || Tag.WALLS.isTagged(t) || Tag.FENCE_GATES.isTagged(t)) v |= 64;
            if (b.getBlockData() instanceof org.bukkit.block.data.Openable o && t != Material.IRON_DOOR && t != Material.IRON_TRAPDOOR && !(b.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor)) {
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
        // never opens a hole into lava or water
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            Material n = w.getBlockAt(x + d[0], y + d[1], z + d[2]).getType();
            if (n == Material.LAVA || n == Material.WATER) return -1;
        }
        return 1 + t / 4.0 * (natural(b.getType()) ? 1 : 4) + (natural(b.getType()) ? 0 : 6); // it'd rather use the door than tear a house open
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

    private Node node(Node from, int x, int y, int z, double cost, Type type, List<Block> dig, Block door, int gx, int gy, int gz) {
        long k = key(x, y, z);
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
                int max = rules.maxDrop();
                for (int k = 1; k <= Math.max(max, 40); k++) {
                    int ly = y - k;
                    if (hazard(nx, ly, nz)) break;
                    if (water(nx, ly, nz)) { out.add(node(c, nx, ly, nz, base + k * 0.15 + 1, Type.DROP, List.of(), null, gx, gy, gz)); break; }
                    if (!pass(nx, ly, nz)) break;
                    if (floor(nx, ly - 1, nz)) {
                        if (k <= max) out.add(node(c, nx, ly, nz, base + k * 0.25 + Math.max(0, k - 3) * 1.5, Type.DROP, List.of(), null, gx, gy, gz));
                        break;
                    }
                }
                continue;
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
