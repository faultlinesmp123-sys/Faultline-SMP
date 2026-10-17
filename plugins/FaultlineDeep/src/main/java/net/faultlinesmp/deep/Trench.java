package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE TRENCH (Deep 1.8.0): a rift that splits the Dark Ocean from the Sunlit Sea's floor all the way down to the bedrock,
 * winding ~1800 blocks through the far west (x -1250..-1650, z -100..-1650: past the old 1050 border, so those chunks are
 * new). Narrow at the bottom (12-18 wide at y -61), opening up in ledges to ~60 wide at the sea floor; the crust and the
 * Abyss are cut clean through, so from its mouth you look down ~110 blocks into the black. The world can't go below
 * y -64, so the Trench is as deep as the world gets.
 *
 *  - walls of blackstone, deepslate and basalt, streaked with crying obsidian
 *  - its floor: sculk, black sand, bone, hadal vents (magma under bubble columns), pale "hadal coral" (dead coral)
 *  - THE RIBS: every ~140 blocks a giant rib of bone arches from wall to wall, the bones of something far bigger
 *  - at the southern end THE MAW: a round basin 88 wide where the Abyssal Titan sleeps (LX, LZ), ringed by bone tusks
 *
 * Its own creatures live only down here (DeepMobs, zone TRENCH), the pressure is worse (Ocean.trenchPressure), and it's
 * pitch dark. Pure functions of the seed and position, like the rest of the generator.
 */
final class Trench {
    static final int BOTTOM = -61;                       // the floor's top block (bedrock is -64..-62)
    static final int LX = -1460, LZ = -1640, LAIR_R = 44; // the Maw: the Abyssal Titan's lair
    /** The rift's centre line, north to south (a Catmull-Rom curve through these), ending in the Maw. */
    private static final double[][] CTRL = {{-1280, -100}, {-1520, -460}, {-1360, -840}, {-1600, -1220}, {LX, LZ}};
    static final int MAX_HALF = 34;                      // the widest the rift gets (its mouth), for the cheap checks
    static final int RIB_EVERY = 140;
    static final int WALL = 10;                          // how thick its walls are where they hold back the Abyss

    private Trench() { }

    // =========================================================================================== the centre line
    /** Points every ~3 blocks along the centre line, and how far along each one is. */
    static final double[] PX, PZ, S;
    /** 64-block cells -> the segments that pass near them. */
    private static final Map<Long, int[]> GRID = new HashMap<>();
    static final double LENGTH;

    static {
        List<double[]> pts = new ArrayList<>();
        double[][] c = new double[CTRL.length + 2][];
        c[0] = CTRL[0]; System.arraycopy(CTRL, 0, c, 1, CTRL.length); c[c.length - 1] = CTRL[CTRL.length - 1];
        for (int i = 1; i < c.length - 2; i++) {
            double seg = Math.hypot(c[i + 1][0] - c[i][0], c[i + 1][1] - c[i][1]);
            int steps = (int) Math.ceil(seg / 3);
            for (int k = 0; k < steps; k++) {
                double t = k / (double) steps, t2 = t * t, t3 = t2 * t;
                double[] p = new double[2];
                for (int a = 0; a < 2; a++)
                    p[a] = 0.5 * (2 * c[i][a] + (-c[i - 1][a] + c[i + 1][a]) * t
                            + (2 * c[i - 1][a] - 5 * c[i][a] + 4 * c[i + 1][a] - c[i + 2][a]) * t2
                            + (-c[i - 1][a] + 3 * c[i][a] - 3 * c[i + 1][a] + c[i + 2][a]) * t3);
                pts.add(p);
            }
        }
        pts.add(CTRL[CTRL.length - 1].clone());
        PX = new double[pts.size()]; PZ = new double[pts.size()]; S = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            PX[i] = pts.get(i)[0]; PZ[i] = pts.get(i)[1];
            S[i] = i == 0 ? 0 : S[i - 1] + Math.hypot(PX[i] - PX[i - 1], PZ[i] - PZ[i - 1]);
        }
        LENGTH = S[S.length - 1];
        Map<Long, List<Integer>> g = new HashMap<>();
        int reach = MAX_HALF + WALL + 6;
        for (int i = 0; i + 1 < PX.length; i++) {
            int x0 = (int) Math.floor((Math.min(PX[i], PX[i + 1]) - reach) / 64), x1 = (int) Math.floor((Math.max(PX[i], PX[i + 1]) + reach) / 64);
            int z0 = (int) Math.floor((Math.min(PZ[i], PZ[i + 1]) - reach) / 64), z1 = (int) Math.floor((Math.max(PZ[i], PZ[i + 1]) + reach) / 64);
            for (int gx = x0; gx <= x1; gx++) for (int gz = z0; gz <= z1; gz++) g.computeIfAbsent(key(gx, gz), k -> new ArrayList<>()).add(i);
        }
        for (var e : g.entrySet()) GRID.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
    }

    private static long key(int gx, int gz) { return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL); }

    /** Where a column sits against the rift: distance from the centre line, how far along it, which side (+1 / -1). */
    record Spot(double d, double s, double side) { }

    /** The nearest point of the centre line, or null when the column is nowhere near it. */
    static Spot spot(int x, int z) {
        int[] segs = GRID.get(key(Math.floorDiv(x, 64), Math.floorDiv(z, 64)));
        if (segs == null) return null;
        double best = 1e18, bs = 0, side = 1;
        for (int i : segs) {
            double ax = PX[i], az = PZ[i], bx = PX[i + 1], bz = PZ[i + 1];
            double vx = bx - ax, vz = bz - az, len2 = vx * vx + vz * vz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / len2));
            double qx = ax + vx * t, qz = az + vz * t, d2 = (x - qx) * (x - qx) + (z - qz) * (z - qz);
            if (d2 < best) { best = d2; bs = S[i] + Math.sqrt(len2) * t; side = vx * (z - az) - vz * (x - ax) >= 0 ? 1 : -1; }
        }
        double d = Math.sqrt(best);
        return d > MAX_HALF + WALL + 4 ? null : new Spot(d, bs, side);
    }

    /** Close enough that another feature (a blue hole) should keep away. */
    static boolean near(int x, int z, int extra) {
        if (Math.hypot(x - LX, z - LZ) < LAIR_R + 20 + extra) return true;
        if (x > -1150 + extra || x < -1750 - extra || z > 0 + extra || z < -1750 - extra) return false;
        Spot s = spot(x, z);
        return s != null && s.d < MAX_HALF + WALL + 3 + extra;
    }

    // =========================================================================================== the shape
    /** The rift's half width at height y (it opens up in ledges toward the top). */
    static double half(DeepGenerator.Noise n, Spot s, int x, int y, int z) {
        double base = 6.5 + (n.warpX.noise(s.s / 90.0, 3.7) + 1) * 1.8;         // 6.5..10 at the bottom
        double up = y - BOTTOM;
        double h = base + up * 0.1 + Math.floor(up / 13) * 1.3;                // ledges
        h += n.c1.noise(x / 28.0, y / 16.0, z / 28.0) * 1.8;                   // rough walls
        return Math.min(MAX_HALF, h);
    }

    /** The Maw's radius at height y (a bowl). */
    static double lairR(int y) { return Math.min(LAIR_R + 6, LAIR_R * 0.72 + (y - BOTTOM) * 0.35); }

    /** Open water at (x, y, z)? (only up to `top`, the Sunlit Sea's floor) */
    static boolean open(DeepGenerator.Noise n, Spot s, int x, int y, int z, int top) {
        if (y <= BOTTOM || y > top) return false;
        if (s != null && s.d < half(n, s, x, y, z)) return true;
        return Math.hypot(x - LX, z - LZ) < lairR(y);
    }

    /** Is the point in the Trench (in its water, below the Sunlit Sea's floor)? For zones: cheap enough per player. */
    static boolean inside(DeepGenerator.Noise n, int x, int y, int z, int top) {
        if (y > top || y <= BOTTOM) return false;
        if (Math.hypot(x - LX, z - LZ) < lairR(y) + 1) return true;
        if (!near(x, z, 0)) return false;
        Spot s = spot(x, z);
        return s != null && s.d < half(n, s, x, y, z) + 1;
    }

    // =========================================================================================== carving a column
    /** Cuts the rift through one column, after everything else is in it. */
    static void carve(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int top, int minY) {
        double dl = Math.hypot(x - LX, z - LZ);
        Spot s = spot(x, z);
        if (s == null && dl > LAIR_R + WALL + 10) return;
        boolean any = false;
        for (int y = BOTTOM + 1; y <= top; y++) {
            boolean o = open(n, s, x, y, z, top);
            if (o) { d.setBlock(lx, y, lz, Material.WATER); any = true; continue; }
            // the walls: solid dark rock WALL blocks thick on each side, all the way down, so the rift is a canyon of its own
            // and not a slot over the open Abyss (the Abyss's water beside it is filled in)
            double hw = s == null ? -99 : half(n, s, x, y, z), lr = lairR(y);
            double wt = WALL + n.warpZ.noise(x / 40.0, z / 40.0) * 3;
            if ((s != null && s.d < hw + wt) || dl < lr + wt) {
                Material m = d.getType(lx, y, lz);
                if (m.isSolid() ? (s != null && s.d < hw + 2.2) || dl < lr + 2.2 : y < top - 1) d.setBlock(lx, y, lz, wallBlock(rnd, y));
            }
        }
        if (!open(n, s, x, BOTTOM + 1, z, top)) return;
        // the floor
        for (int y = minY + 3; y <= BOTTOM; y++) d.setBlock(lx, y, lz, y == BOTTOM ? floorBlock(n, rnd, x, z) : y > BOTTOM - 2 ? Material.BLACKSTONE : Material.DEEPSLATE);
        decorate(g, n, d, rnd, lx, lz, x, z, s, dl, top);
        if (any && s != null) rib(n, d, lx, lz, x, z, s, top);
    }

    static Material wallBlock(Random rnd, int y) {
        int r = rnd.nextInt(100);
        if (r < 3) return Material.CRYING_OBSIDIAN;
        if (r < 14) return Material.BASALT;
        if (r < 24) return Material.POLISHED_BLACKSTONE;
        return (Math.floorMod(y, 7) < 3) ? Material.BLACKSTONE : Material.DEEPSLATE;
    }

    static Material floorBlock(DeepGenerator.Noise n, Random rnd, int x, int z) {
        double v = n.n2(n.vents, x + 3100, z - 2900);
        int r = rnd.nextInt(100);
        if (v > 0.35) return r < 30 ? Material.SCULK : r < 45 ? Material.MAGMA_BLOCK : Material.BLACKSTONE;
        if (r < 30) return Material.SCULK;
        if (r < 60) return Material.BLACK_CONCRETE_POWDER;
        if (r < 70) return Material.BONE_BLOCK;
        if (r < 85) return Material.DEEPSLATE;
        return Material.TUFF;
    }

    private static final Material[] HADAL = {Material.DEAD_BRAIN_CORAL_BLOCK, Material.DEAD_TUBE_CORAL_BLOCK, Material.DEAD_HORN_CORAL_BLOCK};
    private static final Material[] HADAL_FAN = {Material.DEAD_BRAIN_CORAL_FAN, Material.DEAD_TUBE_CORAL_FAN, Material.DEAD_HORN_CORAL, Material.DEAD_FIRE_CORAL};

    private static BlockData bubble, sculkVein, pickles, tip, frustum, glow;

    private static void blocks() {
        if (glow != null) return;
        synchronized (Trench.class) {
            if (glow != null) return;
            bubble = bd("minecraft:bubble_column[drag=false]", Material.BUBBLE_COLUMN);
            sculkVein = bd("minecraft:sculk_vein[down=true,waterlogged=true]", Material.SCULK_VEIN);
            pickles = bd("minecraft:sea_pickle[pickles=2,waterlogged=true]", Material.SEA_PICKLE);
            tip = bd("minecraft:pointed_dripstone[vertical_direction=up,thickness=tip,waterlogged=true]", Material.POINTED_DRIPSTONE);
            frustum = bd("minecraft:pointed_dripstone[vertical_direction=up,thickness=frustum,waterlogged=true]", Material.POINTED_DRIPSTONE);
            glow = bd("minecraft:glow_lichen[down=true,waterlogged=true]", Material.GLOW_LICHEN);
        }
    }

    /** A block state, or the plain block if this server doesn't know the state (generation must never fail on a name). */
    private static BlockData bd(String spec, Material plain) {
        try { return org.bukkit.Bukkit.createBlockData(spec); } catch (RuntimeException e) { return plain.createBlockData(); }
    }

    /** The Trench's floor: vents, bone spikes, hadal coral, sculk, a few pale lights. The Maw gets bone tusks round its rim. */
    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, Spot s, double dl, int top) {
        blocks();
        int y = BOTTOM + 1;
        if (d.getType(lx, y, lz) != Material.WATER) return;
        Material below = d.getType(lx, BOTTOM, lz);
        // the Maw: its centre is clear (the Titan rises there); bone tusks curve up round the rim
        if (dl < LAIR_R + 4) {
            if (dl < 10) { d.setBlock(lx, BOTTOM, lz, dl < 3 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE); return; }
            double ang = Math.atan2(z - LZ, x - LX);
            int tusk = (int) Math.round(ang / (Math.PI * 2) * 16);
            double ta = tusk * Math.PI * 2 / 16, rr = LAIR_R * 0.62;
            double tx = LX + Math.cos(ta) * rr, tz = LZ + Math.sin(ta) * rr;
            if (Math.hypot(x - tx, z - tz) < 2.2) {
                for (int i = 0; i < 26; i++) { // leaning inward as it climbs
                    double k = i / 26.0;
                    double cx = tx - Math.cos(ta) * k * k * 9, cz = tz - Math.sin(ta) * k * k * 9;
                    if (Math.hypot(x - cx, z - cz) < 2.2 - k * 1.6 && d.getType(lx, y + i, lz) == Material.WATER) d.setBlock(lx, y + i, lz, Material.BONE_BLOCK);
                }
                return;
            }
            if (rnd.nextInt(30) == 0) d.setBlock(lx, y, lz, sculkVein);
            return;
        }
        if (below == Material.MAGMA_BLOCK) { // a hadal vent: bubbles stream up the rift
            for (int i = 0; i < 24 && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, bubble);
            return;
        }
        int r = rnd.nextInt(1000);
        if (r < 6) { // bone spikes
            int h = 2 + rnd.nextInt(5);
            for (int i = 0; i < h && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, Material.BONE_BLOCK);
        } else if (r < 30) d.setBlock(lx, y, lz, HADAL_FAN[rnd.nextInt(HADAL_FAN.length)]);
        else if (r < 45) { d.setBlock(lx, BOTTOM, lz, HADAL[rnd.nextInt(HADAL.length)]); }
        else if (r < 60) d.setBlock(lx, y, lz, sculkVein);
        else if (r < 64) d.setBlock(lx, y, lz, pickles);
        else if (r < 70 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, frustum); d.setBlock(lx, y + 1, lz, tip); }
        else if (r < 74) d.setBlock(lx, y, lz, glow);
    }

    /** One of THE RIBS: a bone arch from wall to wall every RIB_EVERY blocks along the rift. */
    static void rib(DeepGenerator.Noise n, ChunkData d, int lx, int lz, int x, int z, Spot s, int top) {
        double k = Math.round(s.s / RIB_EVERY) * RIB_EVERY;
        if (k < RIB_EVERY * 0.5 || k > LENGTH - LAIR_R - 30 || Math.abs(s.s - k) > 1.6) return;
        int cy = BOTTOM + 22 + (int) ((n.warpZ.noise(k / 50.0, 1.3) + 1) * 14); // where the arch springs from
        double across = s.d * s.side;
        for (int y = cy; y <= Math.min(top - 2, cy + 40); y++) {
            double R = half(n, s, x, cy, z) + 1;
            double rr = Math.hypot(across, (y - cy) * 1.15);
            if (Math.abs(rr - R) < 1.4 && d.getType(lx, y, lz) == Material.WATER) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
        }
    }
}
