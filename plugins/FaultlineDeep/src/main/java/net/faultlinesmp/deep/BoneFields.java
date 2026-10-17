package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * THE BONE FIELDS (Deep 1.9.0): a graveyard of leviathans on the western sea floor, round CX, CZ.
 *  - a dark silt floor (gravel, tuff, mud, andesite) so the white bones stand out, with dead coral, pale seagrass and the
 *    odd sea pickle glowing between the bones
 *  - THE SKELETONS (SKELETONS, a fixed list): spines winding 60-130 blocks over the floor, vertebrae with spikes, great
 *    ribs arching up out of the sand, front flippers like fans of finger bones, and a skull with empty eye sockets and a
 *    lower jaw lined with teeth, half buried or lying open
 *  - VERTEBRA COLUMNS: single backbones standing upright like totems
 *  - FOSSIL CLUSTERS: knots of bone blocks half sunk in the floor. Mining a generated bone block here can give an
 *    Ancient Fossil (FaultlineDeep's Fossils listener; placed bone never does)
 * Biome DEEP_COLD_OCEAN (cold, clear). Pure functions, like the rest of the generator.
 */
final class BoneFields {
    static final int CX = -2600, CZ = 900, R = 310, BLEND = 70;

    /** A skeleton: head position, heading, length, size, how deep it's buried (0..1), a seed for its wobble. */
    record Skeleton(int x, int z, double heading, int length, double size, double buried, long h) { }

    static final List<Skeleton> SKELETONS = new ArrayList<>();
    static {
        double[][] s = {
                {-2560, 860, 0.4, 120, 1.9, 0.15}, {-2700, 760, 2.1, 90, 1.4, 0.3}, {-2440, 1010, 3.6, 80, 1.2, 0.1},
                {-2740, 1060, 5.0, 130, 2.2, 0.35}, {-2480, 720, 1.2, 70, 1.0, 0.0}, {-2610, 1130, 4.3, 95, 1.5, 0.2},
                {-2820, 880, 0.9, 60, 0.9, 0.45}, {-2380, 870, 2.8, 75, 1.1, 0.25}, {-2640, 980, 5.9, 105, 1.7, 0.05}};
        long k = 0;
        for (double[] a : s) SKELETONS.add(new Skeleton((int) a[0], (int) a[1], a[2], (int) a[3], a[4], a[5], 0xB0E5L + 31 * k++));
    }

    private BoneFields() { }

    static double weight(int x, int z) { return Regions.weight(x, z, CX, CZ, R, BLEND, 20); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, CX, CZ, R + BLEND + 40 + extra); }

    // =========================================================================================== the floor
    static double floor(DeepGenerator.Noise n, int x, int z) {
        return 42 + n.n2(n.hills, x * 0.9, z * 0.9) * 6 + n.n2(n.dunes, x * 1.4, z * 1.4) * 2.4;
    }

    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        int r = rnd.nextInt(100);
        double p = n.n2(n.kelp, x - 3100, z);
        if (p > 0.5) return r < 70 ? Material.GRAVEL : Material.TUFF;
        // a dark silt floor, so the white bones stand out against it
        return r < 34 ? Material.GRAVEL : r < 54 ? Material.TUFF : r < 68 ? Material.MUD : r < 82 ? Material.ANDESITE : r < 92 ? Material.LIGHT_GRAY_TERRACOTTA : r < 97 ? Material.CALCITE : Material.BONE_BLOCK;
    }

    static Material under(Random rnd, int y, int up) { return y > up - 3 ? Material.PACKED_MUD : Material.STONE; }

    static final String[] DEAD = {"dead_brain_coral", "dead_tube_coral", "dead_horn_coral", "dead_fire_coral", "dead_bubble_coral",
            "dead_brain_coral_fan", "dead_tube_coral_fan", "dead_horn_coral_fan"};

    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        int y = up + 1;
        if (y >= DeepGenerator.SEA - 2 || d.getType(lx, y, lz) != Material.WATER || !d.getType(lx, up, lz).isSolid()) return;
        int r = rnd.nextInt(1000);
        double cl = n.n2(n.mound, x * 2.2, z * 2.2);
        if (cl > 0.62 && r < 300) { // a fossil cluster: bone half sunk in the floor
            d.setBlock(lx, up, lz, Paint.bd("bone_block[axis=" + (rnd.nextBoolean() ? "x" : "z") + "]"));
            if (r < 120) d.setBlock(lx, y, lz, Paint.bd("bone_block[axis=y]"));
            return;
        }
        if (n.n2(n.kelp, x * 2.6 + 700, z * 2.6) > 0.66) { // 1.10.0: tar seeps, black and glossy, ringed in mud
            d.setBlock(lx, up, lz, n.n2(n.kelp, x * 2.6 + 700, z * 2.6) > 0.72 ? Material.BLACK_CONCRETE : Material.MUD);
            if (r < 6) d.setBlock(lx, y, lz, Paint.bd("skeleton_skull[rotation=" + rnd.nextInt(16) + "]")); // something got stuck
            return;
        }
        if (r >= 163 && r < 175 && d.getType(lx, y + 1, lz) == Material.WATER) { // a tooth the size of a man, point up
            d.setBlock(lx, y, lz, Paint.bd("bone_block[axis=y]"));
            boolean tall = r < 168 && d.getType(lx, y + 2, lz) == Material.WATER;
            if (tall) d.setBlock(lx, y + 1, lz, Paint.bd("pointed_dripstone[vertical_direction=up,thickness=frustum,waterlogged=true]"));
            d.setBlock(lx, y + (tall ? 2 : 1), lz, Paint.bd("pointed_dripstone[vertical_direction=up,thickness=tip,waterlogged=true]"));
            return;
        }
        if (r < 60) d.setBlock(lx, y, lz, Paint.bd(DEAD[rnd.nextInt(DEAD.length)] + "[waterlogged=true]"));
        else if (r < 150) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 160) d.setBlock(lx, y, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(4)) + ",waterlogged=true]"));
        else if (r < 163) d.setBlock(lx, y, lz, Paint.bd("skeleton_skull[rotation=" + rnd.nextInt(16) + "]"));
    }

    // =========================================================================================== the skeletons
    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!near(p.x0 + 8, p.z0 + 8, 40)) return;
        details(g, n, p);
        for (Skeleton s : SKELETONS) {
            int reach = (int) (s.length + 30 * s.size);
            if (p.touches(s.x - reach, s.z - reach, s.x + reach, s.z + reach)) skeleton(g, n, p, s);
        }
        // vertebra columns
        for (int gx = Math.floorDiv(p.x0 - 6, 46); gx <= Math.floorDiv(p.x0 + 21, 46); gx++)
            for (int gz = Math.floorDiv(p.z0 - 6, 46); gz <= Math.floorDiv(p.z0 + 21, 46); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 601);
                if (DeepGenerator.unit(h) > 0.4) continue;
                int vx = gx * 46 + 6 + (int) (DeepGenerator.unit(DeepGenerator.hash(n.seed, gx, gz, 602)) * 34);
                int vz = gz * 46 + 6 + (int) (DeepGenerator.unit(DeepGenerator.hash(n.seed, gx, gz, 603)) * 34);
                if (weight(vx, vz) < 0.9 || !p.touches(vx - 4, vz - 4, vx + 4, vz + 4)) continue;
                column(g, n, p, vx, vz, DeepGenerator.hash(n.seed, gx, gz, 604));
            }
    }

    /** 1.10.0: the whalers who came for the bones, and the shrines of whoever mourned them. */
    static void details(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        for (int gx = Math.floorDiv(p.x0 - 16, 150); gx <= Math.floorDiv(p.x0 + 31, 150); gx++)
            for (int gz = Math.floorDiv(p.z0 - 16, 150); gz <= Math.floorDiv(p.z0 + 31, 150); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 611);
                if (DeepGenerator.unit(h) > 0.55) continue;
                int x = gx * 150 + 25 + (int) (ur(h, 1) * 100), z = gz * 150 + 25 + (int) (ur(h, 2) * 100);
                if (weight(x, z) < 0.85 || !p.touches(x - 16, z - 16, x + 16, z + 16) || nearBones(x, z, 22)) continue;
                whaler(g, n, p.frame(x, z, (int) (ur(h, 3) * 4), true), g.upperFloor(n, x, z), h);
            }
        for (int gx = Math.floorDiv(p.x0 - 10, 110); gx <= Math.floorDiv(p.x0 + 25, 110); gx++)
            for (int gz = Math.floorDiv(p.z0 - 10, 110); gz <= Math.floorDiv(p.z0 + 25, 110); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 621);
                if (DeepGenerator.unit(h) > 0.4) continue;
                int x = gx * 110 + 15 + (int) (ur(h, 1) * 80), z = gz * 110 + 15 + (int) (ur(h, 2) * 80);
                if (weight(x, z) < 0.85 || !p.touches(x - 9, z - 9, x + 9, z + 9) || nearBones(x, z, 14)) continue;
                shrine(g, n, p, x, z, h);
            }
    }

    /** Is a big skeleton's spine near here (so a wreck or shrine doesn't land inside it)? */
    static boolean nearBones(int x, int z, double r) {
        for (Skeleton s : SKELETONS) for (double t = 0; t <= 1; t += 0.05) {
            double[] c = spine(s, t);
            if (Math.hypot(x - c[0], z - c[1]) < r + 12 * s.size) return true;
        }
        return false;
    }

    /** A whaling ship rotted down to its keel and ribs, a harpoon gun, the try-pots, a torn sail. */
    static void whaler(DeepGenerator g, DeepGenerator.Noise n, Paint.Frame f, int b, long h) {
        int L = 20;
        for (int z = 0; z <= L; z++) {
            f.set(0, b + 1, z, "stripped_dark_oak_log[axis=z]");                                     // the keel
            if (z % 3 != 0) continue;
            int half = (int) Math.round(1.5 + Math.sin(Math.PI * z / L) * 3.5);
            for (int side = -1; side <= 1; side += 2) { // a rib: out along the bottom, then up the side
                int top = b + 3 + (int) Math.round(Math.sin(Math.PI * z / L) * 2);
                for (int k = 1; k <= half; k++) f.set(side * k, b + 1 + (k == half ? 1 : 0), z, "dark_oak_fence");
                for (int y = b + 3; y <= top; y++) { if (f.u(side * half, y, z, 31) < 0.2) break; f.set(side * half, y, z, "dark_oak_fence"); } // (rotted off)
            }
        }
        for (int z = 6; z <= 12; z++) for (int x = -2; x <= 2; x++) if (f.u(x, b + 2, z, 32) < 0.5) f.set(x, b + 2, z, "dark_oak_slab[type=bottom]"); // what's left of the deck
        for (int y = b + 2; y <= b + 10; y++) if (y < b + 7 || ur(h, 5) < 0.5) f.set(0, y, 10, "stripped_dark_oak_log[axis=y]"); // the mast stump
        for (int x = -3; x <= 3; x++) for (int y = b + 4; y <= b + 7; y++) if (f.u(x, y, 11, 33) < 0.45) f.set(x, y, 11, "light_gray_wool"); // the sail
        // the harpoon gun at the bow
        f.set(0, b + 2, L - 1, "dark_oak_fence"); f.set(0, b + 3, L - 1, "dispenser[facing=south]");
        for (int k = 1; k <= 3; k++) f.set(0, b + 3, L - 1 + k, k == 3 ? "lightning_rod[facing=south]" : "iron_chain[axis=z]");
        // the try-pots (where the blubber was boiled) amidships
        f.set(-1, b + 2, 4, "cauldron"); f.set(1, b + 2, 4, "cauldron"); f.set(0, b + 2, 4, "bricks");
        for (int i = 0; i < 6; i++) {
            int x = (int) (ur(h, 40 + i) * 13) - 6, z = (int) (ur(h, 50 + i) * 26) - 3;
            put(f, x, g.upperFloor(n, f.wx(x, z), f.wz(x, z)) + 1, z, i % 2 == 0 ? "barrel[facing=up]" : "iron_chain[axis=x]");
        }
        // harpoons still stuck in a vertebra beside it
        f.set(6, b + 1, 8, "bone_block[axis=y]"); f.set(6, b + 2, 8, "bone_block[axis=y]");
        f.set(5, b + 2, 8, "lightning_rod[facing=west]"); f.set(7, b + 1, 8, "lightning_rod[facing=east]");
    }

    static void put(Paint.Frame f, int x, int y, int z, String s) {
        Material m = f.get(x, y, z);
        if (m == Material.WATER || m == Material.SEAGRASS || m.name().startsWith("DEAD_") || m == Material.SEA_PICKLE) f.set(x, y, z, s);
    }

    /** A ring of bone pillars topped with skulls round a little altar, candles and soul lanterns on it. */
    static void shrine(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, long h) {
        int fy = g.upperFloor(n, cx, cz);
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            double d = Math.hypot(x, z);
            if (d <= 6.2 && p.in(cx + x, fy, cz + z)) p.set(cx + x, g.upperFloor(n, cx + x, cz + z), cz + z, d > 5.2 ? "tuff_bricks" : d < 1.6 ? "chiseled_tuff" : "polished_tuff");
        }
        for (int k = 0; k < 6; k++) {
            double a = k * Math.PI / 3 + ur(h, 3);
            int x = cx + (int) Math.round(Math.cos(a) * 5), z = cz + (int) Math.round(Math.sin(a) * 5);
            int y0 = g.upperFloor(n, x, z) + 1, hh = 4 + (int) (ur(h, 10 + k) * 3);
            for (int y = y0; y < y0 + hh; y++) put(p, x, y, z, "bone_block[axis=y]");
            put(p, x, y0 + hh, z, ur(h, 20 + k) < 0.75 ? "skeleton_skull[rotation=" + (int) (ur(h, 30 + k) * 16) + "]" : "wither_skeleton_skull[rotation=0]");
        }
        put(p, cx, fy + 1, cz, "polished_blackstone"); put(p, cx, fy + 2, cz, "polished_blackstone_slab[type=bottom]");
        put(p, cx + 1, fy + 1, cz, "candle[candles=3,lit=false,waterlogged=true]"); put(p, cx - 1, fy + 1, cz, "soul_lantern[hanging=false]");
        put(p, cx, fy + 1, cz + 1, "candle[candles=2,lit=false,waterlogged=true]"); put(p, cx, fy + 1, cz - 1, "soul_lantern[hanging=false]");
        put(p, cx, fy + 3, cz, "skeleton_skull[rotation=" + (int) (ur(h, 4) * 16) + "]");
    }

    static void put(Paint p, int x, int y, int z, String s) {
        if (!p.in(x, y, z)) return;
        Material m = p.get(x, y, z);
        if (m == Material.WATER || m == Material.SEAGRASS || m.name().startsWith("DEAD_") || m == Material.SEA_PICKLE) p.set(x, y, z, Paint.wet(s));
    }

    static void ball(Paint p, double cx, double cy, double cz, double r, String s) {
        for (int x = (int) Math.floor(cx - r); x <= cx + r; x++) for (int y = (int) Math.floor(cy - r); y <= cy + r; y++) for (int z = (int) Math.floor(cz - r); z <= cz + r; z++)
            if (sq(x + 0.5 - cx) + sq(y + 0.5 - cy) + sq(z + 0.5 - cz) <= r * r) put(p, x, y, z, s);
    }

    static double sq(double v) { return v * v; }

    static double ur(long h, int k) { return DeepGenerator.unit(DeepGenerator.hash(h, k, 7, 11)); }

    /** The spine's centre line: from the skull (t = 0) to the tail tip (t = 1). */
    static double[] spine(Skeleton s, double t) {
        double along = t * s.length;
        double wob = Math.sin(t * Math.PI * 2.2 + ur(s.h, 1) * 6) * 9 * s.size * t;
        double ux = Math.cos(s.heading), uz = Math.sin(s.heading);
        return new double[]{s.x - ux * along - uz * wob, s.z - uz * along + ux * wob};
    }

    static void skeleton(DeepGenerator g, DeepGenerator.Noise n, Paint p, Skeleton s) {
        double k = s.size;
        int steps = s.length * 2;
        double[] prev = spine(s, 0);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double[] c = spine(s, t);
            int reachHere = (int) (16 * k) + 6;
            if (!p.touches((int) c[0] - reachHere, (int) c[1] - reachHere, (int) c[0] + reachHere, (int) c[1] + reachHere)) { prev = c; continue; } // nothing of it lands in this chunk
            int fx = (int) Math.floor(c[0]), fz = (int) Math.floor(c[1]);
            int floor = g.upperFloor(n, fx, fz);
            double sink = s.buried * 3 * k;
            double taper = t < 0.15 ? 1 : 1 - (t - 0.15) * 0.85;   // the tail thins
            double vr = Math.max(0.6, 1.4 * k * taper);
            double cy = floor + 1 + vr - sink;
            double dx = c[0] - prev[0], dz = c[1] - prev[1], dl = Math.max(1e-6, Math.hypot(dx, dz));
            double nx = -dz / dl, nz = dx / dl;                         // the sideways direction
            int seg = (int) Math.round(t * s.length);
            // the backbone: a thin cord with a vertebra (a knot of bone) every 3 blocks
            put(p, fx, (int) Math.floor(cy), fz, "bone_block[axis=" + (Math.abs(dx) > Math.abs(dz) ? "x" : "z") + "]");
            if (seg % 3 == 0 && i % 2 == 0) ball(p, c[0], cy, c[1], Math.max(0.9, vr * 0.75), "bone_block[axis=y]");
            // spikes on top of every other vertebra
            if (seg % 3 == 0 && i % 2 == 0) for (int u = 1; u <= Math.max(1, (int) (3 * k * taper)); u++) put(p, fx, (int) Math.floor(cy + vr + u - 0.5), fz, "bone_block[axis=y]");
            // ribs: arches out of the sand over the front half
            if (t > 0.12 && t < 0.58 && seg % 5 == 0 && i % 2 == 0) {
                double ribR = (5 + 7 * Math.sin((t - 0.12) / 0.46 * Math.PI)) * k;
                for (int side = -1; side <= 1; side += 2) for (double a = 0; a <= Math.PI * 0.95; a += 0.05) {
                    double rx = c[0] + nx * side * Math.sin(a) * ribR, rz = c[1] + nz * side * Math.sin(a) * ribR;
                    double ry = cy + Math.cos(a) * ribR * 0.9 - ribR * 0.25;
                    if (ry < floor - 1) continue;
                    put(p, (int) Math.floor(rx), (int) Math.floor(ry), (int) Math.floor(rz), "bone_block[axis=y]");
                }
            }
            // front flippers: fans of finger bones
            if (Math.abs(t - 0.16) < 0.003 + 0.5 / steps) for (int side = -1; side <= 1; side += 2) for (int fgr = 0; fgr < 4; fgr++) {
                double fa = (fgr - 1.5) * 0.28;
                double ax = nx * side * Math.cos(fa) + (-dx / dl) * Math.sin(fa) * 0.8, az = nz * side * Math.cos(fa) + (-dz / dl) * Math.sin(fa) * 0.8;
                for (double r = 1; r < 9 * k; r += 0.5) put(p, (int) Math.floor(c[0] + ax * r), (int) Math.floor(cy - 0.5 - r * 0.25), (int) Math.floor(c[1] + az * r), "bone_block[axis=y]");
            }
            prev = c;
        }
        skull(g, n, p, s);
    }

    /** The skull at the head: an elongated hollow dome with eye sockets, and a lower jaw lined with teeth. */
    static void skull(DeepGenerator g, DeepGenerator.Noise n, Paint p, Skeleton s) {
        double k = s.size;
        double ux = Math.cos(s.heading), uz = Math.sin(s.heading);
        double hx = s.x + ux * 6 * k, hz = s.z + uz * 6 * k;               // in front of the first vertebra
        double L = 9 * k, W = 4.2 * k, H = 3.6 * k;
        if (!p.touches((int) (hx - L - 4), (int) (hz - L - 4), (int) (hx + L + 4), (int) (hz + L + 4))) return;
        int floor = g.upperFloor(n, (int) Math.floor(hx), (int) Math.floor(hz));
        double cy = floor + 1 + H * 0.7 - s.buried * 2 * k;
        int reach = (int) Math.ceil(L + 3);
        for (int x = (int) hx - reach; x <= hx + reach; x++) for (int z = (int) hz - reach; z <= hz + reach; z++) for (int y = (int) (cy - H - 2); y <= cy + H + 2; y++) {
            double rx = (x + 0.5 - hx), rz = (z + 0.5 - hz);
            double a = rx * ux + rz * uz, b = -rx * uz + rz * ux;            // a: along (+ = snout), b: across
            double ry = y + 0.5 - cy;
            double taper = 1 - Math.max(0, a) / L * 0.55;                    // narrower toward the snout
            double e = sq(a / L) + sq(b / (W * taper)) + sq(ry / (H * taper));
            if (e > 1 || ry < -H * 0.25) continue;                          // the dome (open underneath)
            boolean shell = e > 0.78;
            if (!shell) continue;
            // eye sockets: two holes high on the sides, a little back from the middle
            double ea = a + L * 0.15, eb = Math.abs(b) - W * 0.62, er = ry - H * 0.25;
            if (sq(ea / (1.6 * k)) + sq(eb / (1.4 * k)) + sq(er / (1.4 * k)) < 1) continue;
            put(p, x, y, z, "bone_block[axis=y]");
        }
        // the lower jaw, lying open on the floor, with teeth along its edges
        double jaw = 0.35;
        for (double t = -L * 0.2; t < L * 1.05; t += 0.5) for (int side = -1; side <= 1; side += 2) {
            double w = W * 0.85 * (1 - Math.max(0, t) / L * 0.6);
            double jx = hx + ux * t - uz * side * w, jz = hz + uz * t + ux * side * w;
            double jy = cy - H * 0.3 - Math.max(0, t) * jaw;
            put(p, (int) Math.floor(jx), (int) Math.floor(jy), (int) Math.floor(jz), "bone_block[axis=y]");
            if (((int) Math.round(t * 2)) % 3 == 0 && t > 0) put(p, (int) Math.floor(jx), (int) Math.floor(jy) + 1, (int) Math.floor(jz), "pointed_dripstone[vertical_direction=up,thickness=tip,waterlogged=true]");
        }
    }

    /** A single backbone stood on end. */
    static void column(DeepGenerator g, DeepGenerator.Noise n, Paint p, int vx, int vz, long h) {
        int base = g.upperFloor(n, vx, vz) + 1;
        int height = 8 + (int) (ur(h, 1) * 14);
        for (int i = 0; i < height; i++) {
            int y = base + i;
            put(p, vx, y, vz, "bone_block[axis=y]");
            if (i % 3 == 1) for (int k = 0; k < 4; k++) {
                int ox = k == 0 ? 1 : k == 1 ? -1 : 0, oz = k == 2 ? 1 : k == 3 ? -1 : 0;
                put(p, vx + ox, y, vz + oz, "bone_block[axis=" + (ox != 0 ? "x" : "z") + "]");
            }
        }
        put(p, vx, base + height, vz, "skeleton_skull[rotation=" + (int) (ur(h, 2) * 16) + "]");
    }
}
