package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.Random;

/**
 * THE FROZEN SHELF (Deep 1.9.0): an ice cap over the far north of the Dark Ocean, round CX, CZ.
 *  - the cap: 3-7 blocks of ice at the surface (packed ice on top, clear ice under it with veins of blue ice), snow on
 *    top, thinning to drifting pack ice round the edge. Under it the water is dark: the only light comes down the
 *    BREATHING HOLES (round gaps in the ice every ~34 blocks) and from the odd sea pickle on the floor
 *  - ICEBERGS: cones of packed / blue ice up to 18 blocks over the cap, each with a keel twice as deep hanging under it
 *  - icicles of packed ice hang from the underside; the floor is cold gravel, andesite and clay with blue ice boulders
 *  - THE RIMEBACK'S HOLLOW at the centre: a bowl in the sea floor ringed by great pillars of blue ice hanging from the
 *    cap, and the wreck of a longship half sealed in ice. The Rimeback (DeepMobs, a mini boss) lives here
 * Biome FROZEN_OCEAN. Pure functions, like the rest of the generator.
 */
final class FrozenShelf {
    static final int CX = 0, CZ = -2600, R = 300, BLEND = 70, LAIR_R = 50;

    private FrozenShelf() { }

    static double weight(int x, int z) { return Regions.weight(x, z, CX, CZ, R, BLEND, 18); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, CX, CZ, R + BLEND + 40 + extra); }

    static boolean lair(int x, int z) { return Math.hypot(x - CX, z - CZ) < LAIR_R; }

    // =========================================================================================== the floor
    static double floor(DeepGenerator.Noise n, int x, int z) {
        double h = 36 + n.n2(n.hills, x * 1.3, z * 1.3) * 9 + n.n2(n.dunes, x, z) * 1.5;
        double d = Math.hypot(x - CX, z - CZ);
        if (d < LAIR_R + 24) { // the Hollow: a bowl
            double t = 1 - Math.max(0, d - LAIR_R) / 24.0;
            h -= 10 * t * t * (3 - 2 * t);
        }
        return h;
    }

    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        int r = rnd.nextInt(100);
        if (lair(x, z)) return r < 30 ? Material.PACKED_ICE : r < 45 ? Material.BLUE_ICE : r < 75 ? Material.GRAVEL : Material.ANDESITE;
        double p = n.n2(n.kelp, x + 4200, z);
        if (p > 0.45) return r < 60 ? Material.CLAY : Material.GRAVEL;
        return r < 45 ? Material.GRAVEL : r < 62 ? Material.ANDESITE : r < 75 ? Material.DIORITE : r < 82 ? Material.TUFF : r < 90 ? Material.STONE : Material.COBBLESTONE;
    }

    static Material under(Random rnd, int y, int up) { return y > up - 3 ? Material.GRAVEL : Material.STONE; }

    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        int y = up + 1;
        if (y >= DeepGenerator.SEA - 12 || d.getType(lx, y, lz) != Material.WATER || !d.getType(lx, up, lz).isSolid()) return;
        int r = rnd.nextInt(1000);
        if (r < 14) { // a blue ice boulder
            int h = 1 + rnd.nextInt(3);
            for (int i = 0; i < h && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, i == h - 1 && rnd.nextBoolean() ? Material.PACKED_ICE : Material.BLUE_ICE);
        } else if (r < 90) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 100) d.setBlock(lx, y, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(4)) + ",waterlogged=true]"));
        else if (r < 104) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
        else if (r < 112) { // short cold kelp
            int h = 2 + rnd.nextInt(6);
            for (int i = 0; i < h && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, i == h - 1 ? Material.KELP : Material.KELP_PLANT);
        }
    }

    // =========================================================================================== the ice cap
    /** A breathing hole: open water through the ice. */
    static boolean hole(DeepGenerator.Noise n, int x, int z) {
        int gx = Math.floorDiv(x, 34), gz = Math.floorDiv(z, 34);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = DeepGenerator.hash(n.seed, gx + i, gz + j, 401);
            if (DeepGenerator.unit(h) > 0.5) continue;
            double hx = (gx + i) * 34 + 6 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 402)) * 22;
            double hz = (gz + j) * 34 + 6 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 403)) * 22;
            double r = 2.5 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 404)) * 3 + n.c3.noise(x / 3.0, 1.1, z / 3.0) * 0.6;
            if (Math.hypot(x - hx, z - hz) < r) return true;
        }
        return false;
    }

    /** An iceberg's height over the cap at this column (0 = none). */
    static int berg(DeepGenerator.Noise n, int x, int z) {
        int gx = Math.floorDiv(x, 70), gz = Math.floorDiv(z, 70);
        int best = 0;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = DeepGenerator.hash(n.seed, gx + i, gz + j, 411);
            if (DeepGenerator.unit(h) > 0.45) continue;
            double bx = (gx + i) * 70 + 15 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 412)) * 40;
            double bz = (gz + j) * 70 + 15 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 413)) * 40;
            if (lair((int) bx, (int) bz)) continue;
            double r = 6 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 414)) * 8;
            double top = 6 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx + i, gz + j, 415)) * 12;
            double d = Math.hypot(x - bx, z - bz) + n.c1.noise(x / 5.0, 2.2, z / 5.0) * 1.6;
            if (d >= r) continue;
            double t = 1 - d / r;
            best = Math.max(best, (int) Math.round(top * Math.pow(t, 0.8)));
        }
        return Math.min(best, 318 - DeepGenerator.SEA);
    }

    /** The cap's thickness here (0 = open water). */
    static int thickness(DeepGenerator.Noise n, int x, int z, double w) {
        if (w <= 0.05) return 0;
        double fr = n.c3.noise(x / 18.0, 3.3, z / 18.0);
        if (w < 0.55 && fr < 0.15 + (0.55 - w) * 1.4) return 0;   // pack ice breaking up round the edge
        int t = 3 + (int) Math.round((n.c2.noise(x / 40.0, 7.7, z / 40.0) + 1) * 2);
        if (w < 0.55) t = Math.max(1, (int) Math.round(t * w / 0.55));
        return t;
    }

    /** Lays the cap, bergs, keels and icicles over one column, after the water is in. */
    static void cap(DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up, double w) {
        int t = thickness(n, x, z, w);
        if (t <= 0) return;
        if (w > 0.5 && hole(n, x, z)) return;
        int sea = DeepGenerator.SEA;
        int bh = w > 0.6 ? berg(n, x, z) : 0;
        int keel = bh > 0 ? (int) (bh * 2.2) : 0;
        int bottom = sea - Math.max(t, keel) + 1;
        bottom = Math.max(bottom, up + 3);
        for (int y = bottom; y <= sea; y++) {
            Material m;
            if (y <= sea - t) m = rnd.nextInt(4) == 0 ? Material.BLUE_ICE : Material.PACKED_ICE;                 // a berg's keel
            else if (y == sea) m = Material.PACKED_ICE;
            else m = n.c1.noise(x / 9.0, y / 4.0, z / 9.0) > 0.45 ? Material.BLUE_ICE : Material.ICE;
            d.setBlock(lx, y, lz, m);
        }
        if (bh > 0) {
            for (int i = 1; i <= bh; i++) {
                int y = sea + i;
                Material m = i == bh ? Material.SNOW_BLOCK : n.c2.noise(x / 6.0, y / 5.0, z / 6.0) > 0.35 ? Material.BLUE_ICE : Material.PACKED_ICE;
                d.setBlock(lx, y, lz, m);
            }
        } else if (sea + 2 <= 318) {
            if (w > 0.65) d.setBlock(lx, sea + 1, lz, Material.SNOW_BLOCK);
            if (w > 0.65 && rnd.nextInt(10) < 4) d.setBlock(lx, sea + (w > 0.65 ? 2 : 1), lz, Paint.bd("snow[layers=" + (1 + rnd.nextInt(3)) + "]"));
        }
        // icicles under the cap
        int under = bottom - 1;
        double dl = Math.hypot(x - CX, z - CZ);
        int len = 0;
        if (dl < LAIR_R + 10) { // the Hollow's great hanging pillars of blue ice
            int gx = Math.floorDiv(x, 11), gz = Math.floorDiv(z, 11);
            double px = gx * 11 + 5.5, pz = gz * 11 + 5.5;
            if (DeepGenerator.unit(DeepGenerator.hash(n.seed, gx, gz, 421)) < 0.55) {
                double pr = 1.4 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx, gz, 422)) * 1.8;
                double dd = Math.hypot(x - px, z - pz);
                if (dd < pr) len = (int) ((40 + DeepGenerator.unit(DeepGenerator.hash(n.seed, gx, gz, 423)) * 150) * (1 - dd / pr * 0.5));
            }
        } else if (rnd.nextInt(7) == 0) len = 1 + rnd.nextInt(rnd.nextInt(5) == 0 ? 9 : 4);
        for (int i = 0; i < len; i++) {
            int y = under - i;
            if (y <= up + 4 || d.getType(lx, y, lz) != Material.WATER) break;
            d.setBlock(lx, y, lz, i > len - 3 ? Material.ICE : dl < LAIR_R + 10 ? Material.BLUE_ICE : Material.PACKED_ICE);
        }
    }

    // =========================================================================================== the Hollow's wreck
    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!p.touches(CX - 22, CZ - 22, CX + 22, CZ + 22)) return;
        int fy = g.upperFloor(n, CX, CZ) + 1;
        Paint.Frame f = p.frame(CX - 4, CZ - 14, 0, true);
        // a longship of dark oak, keel along local z, its bow up and its stern sunk in ice
        for (int z = 0; z <= 28; z++) {
            double s = Math.sin(Math.PI * z / 28);
            int half = (int) Math.round(1 + s * 3.5);
            int lift = z < 4 ? 4 - z : 0;
            for (int x = 4 - half; x <= 4 + half; x++) {
                boolean edge = x == 4 - half || x == 4 + half;
                f.set(x, fy + lift, z, edge ? "stripped_dark_oak_log[axis=z]" : "dark_oak_planks");
                if (edge) { f.set(x, fy + 1 + lift, z, "dark_oak_planks"); if (z % 3 == 0) f.set(x, fy + 2 + lift, z, "dark_oak_fence"); }
            }
            if (z > 20) for (int x = 4 - half; x <= 4 + half; x++) for (int y = fy; y <= fy + 3; y++) if (f.u(x, y, z, 5) < 0.75) f.set(x, y, z, "blue_ice");
        }
        for (int y = fy + 1; y <= fy + 13; y++) f.set(4, y, 12, "stripped_dark_oak_log[axis=y]");   // the mast
        for (int x = 1; x <= 7; x++) f.set(x, fy + 11, 12, "stripped_dark_oak_log[axis=x]");       // the yard
        for (int x = 2; x <= 6; x++) for (int y = fy + 5; y <= fy + 10; y++) if (f.u(x, y, 12, 7) < 0.55) f.set(x, y, 12, "white_wool"); // the torn sail
        f.set(4, fy + 4, 0, "skeleton_skull[rotation=8]");
        f.set(3, fy + 1, 8, "chest[facing=south]");
        f.set(5, fy + 1, 16, "barrel[facing=up]");
        f.set(2, fy + 1, 18, "lantern[hanging=false]");
    }
}
