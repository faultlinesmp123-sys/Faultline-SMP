package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.Random;

/**
 * THE SULPHUR SEA (Deep 1.4.0): an acid region of the Dark Ocean, around SX, SZ (an organic blob ~R blocks across, its
 * edge eased over BLEND blocks), after the Calamity mod's Sulphurous Sea. The Brine Scourge lives here now.
 *
 *  - the floor rises: basins 25-35 deep between shelves, and ISLANDS breaking the surface (up to ~y 112) of sulphurous
 *    mud and olive shale, crusted with yellow sulphur, cut by beaches of black gravel
 *  - the water is murky green (the column's biome is SWAMP) and acidic: swimming in it poisons you (Ocean.java)
 *  - underwater: a bleached, dead reef (dead coral blocks and fans), toxic kelp, sulphur crystals (ochre froglights on
 *    yellow terracotta), slime, bubbling acid vents, bones
 *  - on the islands: dead acidwood trees with hanging moss, fumaroles (smoking vents), bubbling acid pools ringed with
 *    sulphur, dead bushes, fossils; lily pads drift in the shallows
 *  - SULPHUR COLUMNS: great banded spires climbing out of the sea, veined with glowing green
 *  - RUSTED SHIPWRECKS (oxidised copper hulls, broken masts, a Dark Loot chest), GIANT RIBCAGES, and THE SULPHUR WORKS:
 *    an abandoned mining platform on stilts with a shack, a crane and a rusted tank
 *
 * Pure functions of the seed and position, like the rest of the generator. Nothing here touches the world.
 */
final class Sulphur {
    static final int SX = -520, SZ = 480, R = 230, BLEND = 70;
    static final int WORKS_X = SX + 46, WORKS_Z = SZ - 38;

    private Sulphur() { }

    /** Close enough to the region that another feature (a blue hole, a ruin) should keep away. Cheap: distance only. */
    static boolean near(int x, int z, int extra) { return Math.hypot(x - SX, z - SZ) < R + BLEND + 40 + extra; }

    /** 1 inside the Sulphur Sea, easing to 0 at its edge (the edge wobbles). */
    static double weight(DeepGenerator.Noise n, int x, int z) {
        double d = Math.hypot(x - SX, z - SZ);
        if (d > R + BLEND + 40) return 0;
        d += n.sulphurEdge.noise(x / 90.0, z / 90.0) * 40;
        if (d <= R) return 1;
        if (d >= R + BLEND) return 0;
        double t = 1 - (d - R) / BLEND;
        return t * t * (3 - 2 * t);
    }

    /** The island noise: above ~0.25 the floor breaks the surface. */
    static double island(DeepGenerator.Noise n, int x, int z) { return n.n2(n.sulphur, x, z); }

    /** The floor (top solid block) inside the Sulphur Sea: basins, shelves and islands. */
    static double floor(DeepGenerator.Noise n, int x, int z) {
        double isl = island(n, x, z);
        double h = 66 + Math.max(0, isl + 0.15) * 85 + n.n2(n.dunes, x * 1.3, z * 1.3) * 1.6;
        if (isl > 0.42) h += (isl - 0.42) * 30; // the biggest islands get hills
        // the works stand over open water
        double dw = Math.hypot(x - WORKS_X, z - WORKS_Z);
        if (dw < 30) h = Math.min(h, 74 + dw * 0.4);
        return Math.min(114, h);
    }

    // =========================================================================================== blocks
    /** The top block of a column. */
    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        double crust = n.n2(n.sulphur, x * 3.1 + 500, z * 3.1);
        if (up > DeepGenerator.SEA) { // on an island
            if (up <= DeepGenerator.SEA + 1) return rnd.nextInt(3) == 0 ? Material.GRAVEL : rnd.nextBoolean() ? Material.MUD : Material.GREEN_TERRACOTTA; // the beach
            if (crust > 0.35) return rnd.nextInt(6) == 0 ? Material.OCHRE_FROGLIGHT : Material.YELLOW_TERRACOTTA; // sulphur crust
            if (crust < -0.4) return Material.MOSS_BLOCK;
            int r = rnd.nextInt(10);
            return r < 4 ? Material.PACKED_MUD : r < 7 ? Material.GREEN_TERRACOTTA : r < 9 ? Material.ROOTED_DIRT : Material.COARSE_DIRT;
        }
        if (up >= DeepGenerator.SEA - 1) return Material.MUD;
        if (crust > 0.45) return Material.YELLOW_TERRACOTTA;
        int r = rnd.nextInt(100);
        if (r < 34) return Material.GREEN_TERRACOTTA;
        if (r < 50) return Material.MUD;
        if (r < 60) return Material.PACKED_MUD;
        if (r < 70) return Material.LIME_TERRACOTTA;
        if (r < 80) return Material.GRAVEL;
        if (r < 90) return DEAD_CORAL_BLOCKS[rnd.nextInt(DEAD_CORAL_BLOCKS.length)];
        return Material.TUFF;
    }

    /** The few blocks under the top. */
    static Material under(Random rnd, int y, int up) {
        if (y > up - 3) return rnd.nextInt(4) == 0 ? Material.MUD : Material.GREEN_TERRACOTTA;
        return (y & 3) == 0 ? Material.MUD_BRICKS : rnd.nextInt(5) == 0 ? Material.YELLOW_TERRACOTTA : Material.TUFF;
    }

    static final Material[] DEAD_CORAL_BLOCKS = {Material.DEAD_BRAIN_CORAL_BLOCK, Material.DEAD_TUBE_CORAL_BLOCK, Material.DEAD_HORN_CORAL_BLOCK,
            Material.DEAD_FIRE_CORAL_BLOCK, Material.DEAD_BUBBLE_CORAL_BLOCK};
    static final Material[] DEAD_CORALS = {Material.DEAD_BRAIN_CORAL, Material.DEAD_TUBE_CORAL, Material.DEAD_HORN_CORAL, Material.DEAD_FIRE_CORAL,
            Material.DEAD_BUBBLE_CORAL, Material.DEAD_BRAIN_CORAL_FAN, Material.DEAD_TUBE_CORAL_FAN, Material.DEAD_HORN_CORAL_FAN, Material.DEAD_FIRE_CORAL_FAN};

    /** One column's small things: on the floor under the water, or on an island. */
    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        int y = up + 1;
        Material below = d.getType(lx, up, lz);
        if (!below.isSolid()) return;
        if (up >= DeepGenerator.SEA) { // ---- an island
            if (d.getType(lx, y, lz) != Material.AIR) return;
            int r = rnd.nextInt(1000);
            if (r < 40 && below != Material.OCHRE_FROGLIGHT) d.setBlock(lx, y, lz, Material.DEAD_BUSH);
            else if (r < 52) d.setBlock(lx, y, lz, Material.BROWN_MUSHROOM);
            else if (r < 60) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
            else if (r < 66) { d.setBlock(lx, y, lz, Material.OCHRE_FROGLIGHT); if (rnd.nextBoolean()) d.setBlock(lx, y + 1, lz, Material.OCHRE_FROGLIGHT); }
            else if (r < 90 && below == Material.MOSS_BLOCK) d.setBlock(lx, y, lz, Material.MOSS_CARPET);
            else if (r < 96) d.setBlock(lx, y, lz, Paint.bd("minecraft:short_dry_grass"));
            return;
        }
        // ---- under the water
        if (d.getType(lx, y, lz) != Material.WATER) return;
        int depth = DeepGenerator.SEA - up;
        if (depth <= 6 && rnd.nextInt(60) == 0 && d.getType(lx, DeepGenerator.SEA, lz) == Material.WATER && d.getType(lx, DeepGenerator.SEA + 1, lz) == Material.AIR)
            d.setBlock(lx, DeepGenerator.SEA + 1, lz, Material.LILY_PAD); // the shallows
        int r = rnd.nextInt(1000);
        double reef = n.n2(n.coral, x + 9000, z);
        if (reef > 0.2 && r < 260) { // the dead reef
            if (reef > 0.45 && r < 90) {
                int hh = 1 + rnd.nextInt(3);
                for (int i = 0; i < hh && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, DEAD_CORAL_BLOCKS[rnd.nextInt(DEAD_CORAL_BLOCKS.length)]);
                if (d.getType(lx, y + hh, lz) == Material.WATER) d.setBlock(lx, y + hh, lz, DEAD_CORALS[rnd.nextInt(DEAD_CORALS.length)]);
            } else d.setBlock(lx, y, lz, DEAD_CORALS[rnd.nextInt(DEAD_CORALS.length)]);
            return;
        }
        if (r < 70) { d.setBlock(lx, y, lz, Material.SEAGRASS); return; }
        if (r < 82 && depth > 8) { // toxic kelp
            int h = 4 + rnd.nextInt(Math.max(1, depth - 6));
            for (int i = 0; i < h; i++) {
                if (y + i >= DeepGenerator.SEA - 1 || d.getType(lx, y + i, lz) != Material.WATER) break;
                d.setBlock(lx, y + i, lz, i == h - 1 || y + i + 1 >= DeepGenerator.SEA - 1 ? Material.KELP : Material.KELP_PLANT);
            }
            return;
        }
        if (r < 92) { d.setBlock(lx, up, lz, Material.YELLOW_TERRACOTTA); d.setBlock(lx, y, lz, Material.OCHRE_FROGLIGHT); return; } // sulphur crystals
        if (r < 97) { d.setBlock(lx, y, lz, Paint.bd("minecraft:sea_pickle[pickles=3,waterlogged=true]")); return; }
        if (r < 101) { d.setBlock(lx, y, lz, Material.SLIME_BLOCK); return; }
        if (r < 105) { d.setBlock(lx, y, lz, Material.BONE_BLOCK); return; }
        if (r < 109 && depth > 4) { // a bubbling acid vent
            d.setBlock(lx, up, lz, Material.MAGMA_BLOCK);
            for (int i = 0; i < depth - 1 && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, Paint.bd("minecraft:bubble_column[drag=false]"));
        }
    }

    // =========================================================================================== structures
    static final int CELL_TREE = 14, CELL_COL = 70, CELL_WRECK = 120, CELL_RIB = 96, CELL_FUME = 36, CELL_POOL = 30;

    static double u(long seed, int a, int b, int salt) { return DeepGenerator.unit(DeepGenerator.hash(seed, a, b, salt)); }

    /** Writes into a block, wet below the surface (air -> water, waterlogged where it can be). */
    static void put(Paint p, int x, int y, int z, String s) {
        if (y <= DeepGenerator.SEA) s = Paint.wet(s);
        else if (s.equals("water")) s = "air"; // above the sea: a hollow is dry (water up there would pour out)
        p.set(x, y, z, s);
    }

    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!near(p.x0 + 8, p.z0 + 8, 40)) return;
        long seed = n.seed;
        // ---- sulphur columns
        for (int gx = Math.floorDiv(p.x0 - 12, CELL_COL); gx <= Math.floorDiv(p.x0 + 27, CELL_COL); gx++)
            for (int gz = Math.floorDiv(p.z0 - 12, CELL_COL); gz <= Math.floorDiv(p.z0 + 27, CELL_COL); gz++) {
                if (u(seed, gx, gz, 601) > 0.55) continue;
                int cx = gx * CELL_COL + 14 + (int) (u(seed, gx, gz, 602) * (CELL_COL - 28)), cz = gz * CELL_COL + 14 + (int) (u(seed, gx, gz, 603) * (CELL_COL - 28));
                if (weight(n, cx, cz) < 0.8 || Math.hypot(cx - WORKS_X, cz - WORKS_Z) < 40) continue;
                column(g, n, p, cx, cz, gx, gz);
            }
        // ---- shipwrecks
        for (int gx = Math.floorDiv(p.x0 - 20, CELL_WRECK); gx <= Math.floorDiv(p.x0 + 35, CELL_WRECK); gx++)
            for (int gz = Math.floorDiv(p.z0 - 20, CELL_WRECK); gz <= Math.floorDiv(p.z0 + 35, CELL_WRECK); gz++) {
                if (u(seed, gx, gz, 611) > 0.7) continue;
                int cx = gx * CELL_WRECK + 25 + (int) (u(seed, gx, gz, 612) * (CELL_WRECK - 50)), cz = gz * CELL_WRECK + 25 + (int) (u(seed, gx, gz, 613) * (CELL_WRECK - 50));
                if (weight(n, cx, cz) < 0.7 || !p.touches(cx - 20, cz - 20, cx + 20, cz + 20)) continue;
                int b = g.upperFloor(n, cx, cz);
                if (b > DeepGenerator.SEA - 9 || Math.hypot(cx - WORKS_X, cz - WORKS_Z) < 45) continue;
                wreck(p, cx, cz, b, u(seed, gx, gz, 614), DeepGenerator.hash(seed, gx, gz, 615));
            }
        // ---- giant ribcages
        for (int gx = Math.floorDiv(p.x0 - 16, CELL_RIB); gx <= Math.floorDiv(p.x0 + 31, CELL_RIB); gx++)
            for (int gz = Math.floorDiv(p.z0 - 16, CELL_RIB); gz <= Math.floorDiv(p.z0 + 31, CELL_RIB); gz++) {
                if (u(seed, gx, gz, 621) > 0.5) continue;
                int cx = gx * CELL_RIB + 20 + (int) (u(seed, gx, gz, 622) * (CELL_RIB - 40)), cz = gz * CELL_RIB + 20 + (int) (u(seed, gx, gz, 623) * (CELL_RIB - 40));
                if (weight(n, cx, cz) < 0.7 || !p.touches(cx - 16, cz - 16, cx + 16, cz + 16)) continue;
                ribcage(g, n, p, cx, cz, u(seed, gx, gz, 624) * Math.PI);
            }
        // ---- acidwood trees, fumaroles, acid pools (islands only)
        for (int gx = Math.floorDiv(p.x0 - 6, CELL_TREE); gx <= Math.floorDiv(p.x0 + 21, CELL_TREE); gx++)
            for (int gz = Math.floorDiv(p.z0 - 6, CELL_TREE); gz <= Math.floorDiv(p.z0 + 21, CELL_TREE); gz++) {
                if (u(seed, gx, gz, 631) > 0.45) continue;
                int cx = gx * CELL_TREE + 3 + (int) (u(seed, gx, gz, 632) * (CELL_TREE - 6)), cz = gz * CELL_TREE + 3 + (int) (u(seed, gx, gz, 633) * (CELL_TREE - 6));
                if (weight(n, cx, cz) < 0.6) continue;
                int b = g.upperFloor(n, cx, cz);
                if (b < DeepGenerator.SEA + 1) continue;
                tree(p, cx, cz, b, DeepGenerator.hash(seed, gx, gz, 634));
            }
        for (int gx = Math.floorDiv(p.x0 - 6, CELL_FUME); gx <= Math.floorDiv(p.x0 + 21, CELL_FUME); gx++)
            for (int gz = Math.floorDiv(p.z0 - 6, CELL_FUME); gz <= Math.floorDiv(p.z0 + 21, CELL_FUME); gz++) {
                if (u(seed, gx, gz, 641) > 0.4) continue;
                int cx = gx * CELL_FUME + 6 + (int) (u(seed, gx, gz, 642) * (CELL_FUME - 12)), cz = gz * CELL_FUME + 6 + (int) (u(seed, gx, gz, 643) * (CELL_FUME - 12));
                if (weight(n, cx, cz) < 0.6) continue;
                int b = g.upperFloor(n, cx, cz);
                if (b < DeepGenerator.SEA + 2) continue;
                if (u(seed, gx, gz, 644) < 0.5) fumarole(p, cx, cz, b, DeepGenerator.hash(seed, gx, gz, 645));
                else pool(g, n, p, cx, cz, b, DeepGenerator.hash(seed, gx, gz, 646));
            }
        // ---- the Sulphur Works
        if (p.touches(WORKS_X - 24, WORKS_Z - 24, WORKS_X + 24, WORKS_Z + 24)) works(g, n, p);
    }

    /** A sulphur column: a banded spire from the floor up out of the sea, veined with glowing green, crowned with spikes. */
    static void column(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, int gx, int gz) {
        long seed = n.seed;
        int b = g.upperFloor(n, cx, cz) - 2;
        int topY = DeepGenerator.SEA + 6 + (int) (u(seed, gx, gz, 604) * 26);
        double r0 = 4 + u(seed, gx, gz, 605) * 3.5;
        double lean = (u(seed, gx, gz, 606) - 0.5) * 0.25, ang = u(seed, gx, gz, 607) * Math.PI * 2;
        if (!p.touches(cx - 12, cz - 12, cx + 12, cz + 12)) return;
        String[] bands = {"tuff", "green_terracotta", "mud_bricks", "tuff", "packed_mud", "yellow_terracotta", "tuff", "green_terracotta"};
        for (int y = b; y <= topY; y++) {
            double t = (y - b) / (double) (topY - b);
            double r = r0 * (1 - t * 0.7) + Math.sin(y * 0.7 + gx) * 0.4;
            if (t > 0.9) r *= (1 - (t - 0.9) * 6);
            double ax = cx + Math.cos(ang) * lean * (y - b), az = cz + Math.sin(ang) * lean * (y - b);
            int ri = (int) Math.ceil(r);
            for (int x = (int) ax - ri; x <= (int) ax + ri; x++) for (int z = (int) az - ri; z <= (int) az + ri; z++) {
                double dd = Math.hypot(x - ax, z - az) + Paint.u(x, y, z, 607) * 0.6;
                if (dd > r) continue;
                String s = bands[Math.floorMod(y / 3, bands.length)];
                double v = Paint.u(x, y, z, 608);
                if (dd > r - 1.2 && v < 0.07) s = "verdant_froglight";      // glowing veins
                else if (dd > r - 1.2 && v < 0.12) s = "slime_block";
                p.set(x, y, z, s);
            }
        }
        // spikes around the crown, a fumarole on top
        for (int i = 0; i < 5; i++) {
            double a = ang + i * Math.PI * 2 / 5;
            int sx = (int) Math.round(cx + Math.cos(a) * r0 * 0.5 + Math.cos(ang) * lean * (topY - b)), sz = (int) Math.round(cz + Math.sin(a) * r0 * 0.5 + Math.sin(ang) * lean * (topY - b));
            int h = 2 + (int) (Paint.u(sx, topY, sz, 609) * 4);
            for (int k = 0; k < h; k++) p.set(sx, topY - 2 + k, sz, k == h - 1 ? "pointed_dripstone[vertical_direction=up,thickness=tip]" : "tuff");
        }
    }

    /** A rusted shipwreck lying on the floor, listing to one side. */
    static void wreck(Paint p, int cx, int cz, int b, double a01, long h) {
        double a = a01 * Math.PI;
        double ux = Math.cos(a), uz = Math.sin(a);
        int len = 15 + (int) (DeepGenerator.unit(h) * 7);
        int list = DeepGenerator.unit(h >>> 7) < 0.5 ? 1 : -1;
        for (int i = -len / 2; i <= len / 2; i++) {
            double t = (i + len / 2.0) / len; // 0 stern .. 1 bow
            double half = 3.4 * Math.sin(Math.min(1, t * 1.15 + 0.08) * Math.PI) + 0.6;
            for (int j = -4; j <= 4; j++) {
                if (Math.abs(j) > half + 0.5) continue;
                int x = (int) Math.round(cx + ux * i - uz * j), z = (int) Math.round(cz + uz * i + ux * j);
                int yb = b + 1 + (int) Math.round(Math.max(0, j * list) * 0.35); // listing
                boolean side = Math.abs(j) >= half - 0.5;
                int wall = side ? 4 + (t > 0.85 ? 1 : 0) : 0;
                double broken = Paint.u(x, b, z, 671);
                put(p, x, yb, z, broken < 0.12 ? "water" : Paint.u(x, yb, z, 672) < 0.5 ? "oxidized_cut_copper" : "weathered_cut_copper"); // the hull bottom
                for (int k = 1; k <= wall; k++) {
                    if (broken < 0.18 && k > 1) break;
                    double v = Paint.u(x, yb + k, z, 673);
                    put(p, x, yb + k, z, k == wall ? "dark_oak_slab[type=bottom]" : v < 0.15 ? "iron_bars" : v < 0.6 ? "oxidized_copper" : v < 0.85 ? "weathered_copper" : "dark_oak_planks");
                }
                if (!side && Math.abs(i) % 5 == 0 && Paint.u(x, b, z, 674) < 0.7) put(p, x, yb + 3, z, "dark_oak_planks"); // what's left of the deck
            }
        }
        // the broken mast, a chest, some cargo
        int mx = (int) Math.round(cx + ux), mz = (int) Math.round(cz + uz);
        int mh = 6 + (int) (DeepGenerator.unit(h >>> 13) * 10);
        for (int k = 1; k <= mh; k++) put(p, mx, b + 1 + k, mz, "stripped_dark_oak_log");
        put(p, mx, b + 2 + mh, mz, "iron_chain[axis=y]");
        int kx = (int) Math.round(cx - ux * 4), kz = (int) Math.round(cz - uz * 4);
        put(p, kx, b + 2, kz, "chest[facing=north]");
        put(p, kx + 1, b + 2, kz, "barrel[facing=up]");
        put(p, kx, b + 2, kz + 1, "barrel[facing=up]");
        put(p, (int) Math.round(cx + ux * 5), b + 2, (int) Math.round(cz + uz * 5), "lantern[hanging=false]");
    }

    /** Ribs of something enormous arching out of the floor. */
    static void ribcage(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, double a) {
        double ux = Math.cos(a), uz = Math.sin(a);
        int b = g.upperFloor(n, cx, cz);
        for (int i = -14; i <= 14; i++) {
            int x = (int) Math.round(cx + ux * i), z = (int) Math.round(cz + uz * i);
            put(p, x, b + 1, z, "bone_block[axis=y]"); // the spine
            if (Math.floorMod(i, 4) != 0 || i > 10) continue;
            double R = 9 - Math.max(0, i) * 0.35;
            for (double th = 0; th <= Math.PI; th += 0.06) {
                double across = Math.cos(th) * R, up = Math.sin(th) * R * 1.15;
                int rx = (int) Math.round(x - uz * across), rz = (int) Math.round(z + ux * across);
                int ry = b + 1 + (int) Math.round(up);
                if (ry > DeepGenerator.SEA + 6) continue;
                put(p, rx, ry, rz, "bone_block");
            }
        }
        // the skull at the front
        int hx = (int) Math.round(cx + ux * 17), hz = (int) Math.round(cz + uz * 17);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 4; dy++) {
            double e = (dx * dx) / 9.0 + (dz * dz) / 9.0 + ((dy - 2) * (dy - 2)) / 4.0;
            if (e > 1) continue;
            String s = e > 0.6 ? "bone_block" : "water";
            if (dy == 2 && Math.abs(dx) == 2 && dz == 0) s = "verdant_froglight"; // eye sockets glowing
            put(p, hx + dx, b + 1 + dy, hz + dz, s);
        }
    }

    /** A dead acidwood tree: a crooked black trunk, a few bare boughs, roots, hanging moss and sickly leaves. */
    static void tree(Paint p, int cx, int cz, int b, long h) {
        int ht = 5 + (int) (DeepGenerator.unit(h) * 6);
        double lean = DeepGenerator.unit(h >>> 5) * Math.PI * 2;
        int x = cx, z = cz;
        for (int k = 1; k <= ht; k++) {
            if (k > 2 && DeepGenerator.unit(h >>> (k + 9)) < 0.3) { x += (int) Math.round(Math.cos(lean)); z += (int) Math.round(Math.sin(lean)); }
            p.set(x, b + k, z, "dark_oak_log[axis=y]");
        }
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (DeepGenerator.unit(h >>> (o[0] + 2 * o[1] + 20)) < 0.6) p.set(cx + o[0], b + 1, cz + o[1], "mangrove_roots");
        int boughs = 2 + (int) (DeepGenerator.unit(h >>> 30) * 3);
        for (int i = 0; i < boughs; i++) {
            double a = lean + i * 2.3;
            int by = b + ht - (int) (DeepGenerator.unit(h >>> (31 + i)) * 3);
            int bx = x, bz = z;
            for (int k = 1; k <= 3; k++) {
                bx = x + (int) Math.round(Math.cos(a) * k); bz = z + (int) Math.round(Math.sin(a) * k);
                p.set(bx, by + (k == 3 ? 1 : 0), bz, "dark_oak_log[axis=" + (Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? "x" : "z") + "]");
            }
            if (DeepGenerator.unit(h >>> (40 + i)) < 0.6) { p.set(bx, by - 1, bz, "pale_hanging_moss[tip=false]"); p.set(bx, by - 2, bz, "pale_hanging_moss[tip=true]"); }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (Paint.u(bx + dx, by + 2, bz + dz, 681) < 0.35) p.set(bx + dx, by + 2, bz + dz, "mangrove_leaves[persistent=true]");
        }
    }

    /** A fumarole: a cone of tuff and sulphur over a smouldering vent that smokes (a lit campfire down its throat). */
    static void fumarole(Paint p, int cx, int cz, int b, long h) {
        int ht = 2 + (int) (DeepGenerator.unit(h) * 3);
        for (int dy = 0; dy <= ht; dy++) {
            int r = ht + 1 - dy;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                boolean throat = dx == 0 && dz == 0;
                String s = throat ? (dy == 0 ? "campfire[lit=true,signal_fire=true]" : "air") : Paint.u(cx + dx, b + dy, cz + dz, 691) < 0.3 ? "yellow_terracotta" : "tuff";
                if (throat && dy == 0) p.set(cx, b, cz, "hay_block"); // what makes the smoke rise so high
                p.set(cx + dx, b + 1 + dy, cz + dz, s);
            }
        }
        for (int i = 0; i < 6; i++) { // sulphur crusted round it
            int x = cx + (int) Math.round((Paint.u(cx, i, cz, 692) - 0.5) * 9), z = cz + (int) Math.round((Paint.u(cx, i, cz, 693) - 0.5) * 9);
            p.set(x, b, z, "yellow_terracotta");
        }
    }

    /** An acid pool sunk into an island: green water bubbling up off a magma floor, ringed with sulphur and glow. */
    static void pool(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, int b, long h) {
        double r = 2.2 + DeepGenerator.unit(h) * 2.4;
        int ri = (int) Math.ceil(r) + 1;
        // the whole rim must stand at least as high as the water, or it would spill down the island's side
        for (int dx = -ri - 1; dx <= ri + 1; dx++) for (int dz = -ri - 1; dz <= ri + 1; dz++)
            if (Math.hypot(dx, dz) <= r + 2.2 && g.upperFloor(n, cx + dx, cz + dz) < b) { fumarole(p, cx, cz, b, h); return; }
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            double d = Math.hypot(dx, dz);
            int x = cx + dx, z = cz + dz;
            int top = g.upperFloor(n, x, z);
            if (d <= r) {
                for (int y = b - 2; y <= b; y++) p.set(x, y, z, y == b - 2 ? (Paint.u(x, y, z, 701) < 0.3 ? "magma_block" : "mud") : "water");
                for (int y = b + 1; y <= top + 2; y++) p.set(x, y, z, "air");
                if (Paint.u(x, b, z, 702) < 0.25) p.set(x, b - 1, z, "bubble_column[drag=false]");
            } else if (d <= r + 1.1) {
                p.set(x, b, z, Paint.u(x, b, z, 703) < 0.2 ? "ochre_froglight" : "yellow_terracotta");
            }
        }
    }

    /** THE SULPHUR WORKS: an abandoned mining platform on stilts over the water. Planks rotted through, copper gone green. */
    static void works(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int deck = DeepGenerator.SEA + 2, x0 = WORKS_X - 14, z0 = WORKS_Z - 10, x1 = WORKS_X + 14, z1 = WORKS_Z + 10;
        // stilts down to the floor
        for (int x = x0; x <= x1; x += 4) for (int z = z0; z <= z1; z += 4) {
            int fl = g.upperFloor(n, x, z);
            for (int y = fl + 1; y < deck; y++) put(p, x, y, z, y > DeepGenerator.SEA - 1 ? "stripped_dark_oak_log" : (y & 1) == 0 ? "dark_oak_log" : "dark_oak_fence");
        }
        // the deck, rotted through in places
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double v = Paint.u(x, deck, z, 711);
            if (v < 0.08) continue;
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            p.set(x, deck, z, edge ? "dark_oak_planks" : v < 0.2 ? "dark_oak_slab[type=top]" : v < 0.7 ? "spruce_planks" : "dark_oak_planks");
            if (edge && Paint.u(x, deck + 1, z, 712) < 0.7) p.set(x, deck + 1, z, "dark_oak_fence");
        }
        // the shack (west end)
        int sx0 = x0 + 2, sx1 = x0 + 9, sz0 = z0 + 2, sz1 = z0 + 9;
        for (int x = sx0; x <= sx1; x++) for (int z = sz0; z <= sz1; z++) for (int y = deck + 1; y <= deck + 5; y++) {
            boolean wall = x == sx0 || x == sx1 || z == sz0 || z == sz1;
            if (y == deck + 5) { p.set(x, y, z, Paint.u(x, y, z, 713) < 0.15 ? "air" : "oxidized_cut_copper_slab[type=bottom]"); continue; }
            if (!wall) { p.set(x, y, z, "air"); continue; }
            boolean corner = (x == sx0 || x == sx1) && (z == sz0 || z == sz1);
            boolean door = z == sz1 && x == sx0 + 3 && y <= deck + 2;
            boolean window = y == deck + 3 && !corner && (x == sx0 + 2 || x == sx1 - 2 || z == sz0 + 4);
            p.set(x, y, z, door ? "air" : corner ? "stripped_dark_oak_log" : window ? (Paint.u(x, y, z, 714) < 0.4 ? "air" : "iron_bars") : Paint.u(x, y, z, 715) < 0.5 ? "oxidized_copper" : "weathered_copper");
        }
        p.set(sx0 + 1, deck + 1, sz0 + 1, "chest[facing=south]");
        p.set(sx0 + 2, deck + 1, sz0 + 1, "barrel[facing=up]");
        p.set(sx0 + 1, deck + 1, sz0 + 2, "barrel[facing=up]");
        p.set(sx1 - 1, deck + 1, sz0 + 1, "crafting_table");
        p.set(sx1 - 1, deck + 1, sz1 - 1, "red_bed[facing=south,part=foot]");
        p.set(sx1 - 1, deck + 1, sz1 - 2, "red_bed[facing=south,part=head]");
        p.set(sx0 + 4, deck + 4, sz0 + 4, "lantern[hanging=true]");
        p.set(sx1 - 2, deck + 1, sz0 + 1, "cauldron");
        p.set(sx0 + 1, deck + 2, sz1 - 1, "bookshelf");
        // the rusted tank (east end): a big copper cylinder streaked green, full of sulphur
        int tx = x1 - 6, tz = WORKS_Z;
        for (int y = deck + 1; y <= deck + 9; y++) for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 4.3) continue;
            String s = d > 3.4 ? (Paint.u(tx + dx, y, tz + dz, 716) < 0.5 ? "oxidized_copper" : "exposed_copper") : y < deck + 7 ? "yellow_terracotta" : "air";
            if (d > 3.4 && y % 3 == 0) s = "oxidized_cut_copper";
            p.set(tx + dx, y, tz + dz, s);
        }
        for (int y = deck + 1; y <= deck + 8; y++) p.set(tx - 5, y, tz, "ladder[facing=west]");
        // the crane: a tower, an arm out over the water, a chain and a hook (an anvil)
        int cx = WORKS_X, cz = z0 + 1;
        for (int y = deck + 1; y <= deck + 14; y++) for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}})
            p.set(cx + o[0], y, cz + o[1], (y & 1) == 0 ? "iron_bars" : "oxidized_copper_grate");
        for (int k = -2; k <= 12; k++) p.set(cx, deck + 15, cz - k, k == -2 ? "oxidized_copper" : "oxidized_cut_copper_slab[type=bottom]");
        for (int y = deck - 3; y <= deck + 14; y++) put(p, cx, y, cz - 11, "iron_chain[axis=y]");
        put(p, cx, deck - 4, cz - 11, "anvil[facing=north]");
        // rails, crates, warning lights
        for (int x = x0 + 11; x <= x1 - 10; x++) p.set(x, deck + 1, WORKS_Z + 4, "rail[shape=east_west]");
        p.set(x1 - 10, deck + 1, WORKS_Z + 4, "rail[shape=east_west]");
        for (int[] o : new int[][]{{x0 + 12, z1 - 2}, {x0 + 13, z1 - 2}, {x0 + 12, z1 - 3}}) p.set(o[0], deck + 1, o[1], "barrel[facing=up]");
        for (int[] o : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) { p.set(o[0], deck + 2, o[1], "dark_oak_fence"); p.set(o[0], deck + 3, o[1], "lantern[hanging=false]"); }
    }
}
