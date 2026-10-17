package net.faultlinesmp.deep;

import org.bukkit.Material;

/**
 * THE DETAIL PASS (Deep 1.10.0): small landmarks over the parts of the Dark Ocean that had none. Drawn like the ruins:
 * fixed grid cells, one structure drawn whole for every chunk it touches. Everything goes only into water (`put`), so
 * nothing ever cuts into the terrain, a ruin or another structure.
 *  - THE SUNLIT SEA (cell SUN): a lost anchor with its chain, a capsized boat and its cargo, a fallen lighthouse (the lamp
 *    still glows), a giant nautilus shell, a stone head buried to the chin, a spilled cargo field
 *  - THE ABYSS (cell ABYSS): glowcap groves (giant mushrooms: light in the dark), obelisks in a ring of standing stones,
 *    cages hanging from the ceiling on chains, sculk gardens round a catalyst
 *  - THE TRENCH: old DESCENT RIGS on the rim every RIG blocks along it: a timber derrick with a winch, a boom over the
 *    rift and a chain dropping into the black to a cage platform with a soul lantern
 *  - THE SULPHUR SEA: toxic drum dumps (rusted copper drums leaking a lime slick)
 *  - THE MERMAID DEN: four statues of past queens round the palace and bubble fountains rising from it to the surface
 * Pure functions of the seed and position, like the rest of the generator.
 */
final class Details {
    static final int SUN = 120, ABYSS = 110, RIG = 260;
    static final int ANCHOR = 0, BOAT = 1, LIGHTHOUSE = 2, SHELL = 3, HEAD = 4, CARGO = 5;
    static final int GLOWCAP = 0, OBELISK = 1, CAGE = 2, SCULK = 3;

    private Details() { }

    static double u(long h, int k) { return DeepGenerator.unit(DeepGenerator.hash(h, k, 41, 43)); }

    /** What a structure may write over: water and the soft things growing in it. */
    static boolean soft(Material m) {
        return m == Material.WATER || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEA_PICKLE || m == Material.GLOW_LICHEN || m == Material.SCULK_VEIN;
    }

    static void put(Paint p, int x, int y, int z, String s) { if (p.in(x, y, z) && soft(p.get(x, y, z))) p.set(x, y, z, Paint.wet(s)); }

    static void put(Paint.Frame f, int x, int y, int z, String s) { if (soft(f.get(x, y, z))) f.set(x, y, z, s); }

    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        sunlit(g, n, p);
        abyss(g, n, p);
        if (Trench.near(p.x0 + 8, p.z0 + 8, 60)) rigs(g, n, p);
        if (Sulphur.near(p.x0 + 8, p.z0 + 8, 20)) drums(g, n, p);
        if (MermaidDen.near(p.x0 + 8, p.z0 + 8, 0)) den(g, n, p);
    }

    // =========================================================================================== where
    /** Plain open sea: nowhere near a region, the Den, the Sulphur Sea, the Trench, the Bubble, a blue hole or a ruin. */
    static boolean plain(long seed, int x, int z, int room) {
        if (Math.abs(x) > 2940 || Math.abs(z) > 2940 || Math.hypot(x, z) < 90) return false;
        if (Sulphur.near(x, z, room) || MermaidDen.near(x, z, room) || Regions.nearAny(x, z, room) || Trench.near(x, z, room + 10)) return false;
        if (Math.max(Math.abs(x - DeepGenerator.FLAT_X), Math.abs(z - DeepGenerator.FLAT_Z)) < DeepGenerator.FLAT_HALF + DeepGenerator.FLAT_BLEND + 30) return false;
        int cx = Math.floorDiv(x, 144), cz = Math.floorDiv(z, 144);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int[] c = DeepGenerator.chimney(seed, cx + i, cz + j);
            if (c != null && Math.hypot(x - c[0], z - c[1]) < 36 + room) return false;
        }
        for (boolean ab : new boolean[]{false, true}) {
            int cell = ab ? Ruins.ABYSS : Ruins.SUN, gx = Math.floorDiv(x, cell), gz = Math.floorDiv(z, cell);
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                Ruins.Site s = Ruins.site(seed, gx + i, gz + j, ab);
                if (s != null && Math.hypot(x - s.x(), z - s.z()) < Ruins.REACH + room + 6) return false;
            }
        }
        return true;
    }

    // =========================================================================================== the Sunlit Sea
    static void sunlit(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int reach = 40;
        for (int gx = Math.floorDiv(p.x0 - reach, SUN); gx <= Math.floorDiv(p.x0 + 15 + reach, SUN); gx++)
            for (int gz = Math.floorDiv(p.z0 - reach, SUN); gz <= Math.floorDiv(p.z0 + 15 + reach, SUN); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 801);
                if (DeepGenerator.unit(h) > 0.42) continue;
                int x = gx * SUN + 24 + (int) (u(h, 1) * (SUN - 48)), z = gz * SUN + 24 + (int) (u(h, 2) * (SUN - 48));
                if (!p.touches(x - reach, z - reach, x + reach, z + reach) || !plain(n.seed, x, z, 12)) continue;
                int b = g.upperFloor(n, x, z);
                if (DeepGenerator.SEA - 4 - b < 30) continue;
                int type = (int) (u(h, 3) * 6), r = (int) (u(h, 4) * 4);
                Paint.Frame f = p.frame(x, z, r, true);
                if (type == LIGHTHOUSE) clearing(p, f.wx(0, 13), f.wz(0, 13), b, 26);
                else clearing(p, x, z, b, 16);
                switch (type) {
                    case ANCHOR -> anchor(f, b, h);
                    case BOAT -> boat(g, n, f, b, h);
                    case LIGHTHOUSE -> lighthouse(f, b, h);
                    case SHELL -> shell(f, b, h);
                    case HEAD -> head(f, b, h);
                    default -> cargo(g, n, f, b, h);
                }
            }
    }

    /** Kelp cleared from round a landmark (a ragged clearing), so it can be seen. */
    static void clearing(Paint p, int cx, int cz, int b, int R) {
        for (int x = Math.max(p.x0, cx - R); x <= Math.min(p.x0 + 15, cx + R); x++) for (int z = Math.max(p.z0, cz - R); z <= Math.min(p.z0 + 15, cz + R); z++) {
            if (Math.hypot(x - cx, z - cz) > R - Paint.u(x, 0, z, 16) * 4) continue;
            for (int y = b - 6; y <= b + 44; y++) {
                Material m = p.get(x, y, z);
                if (m == Material.KELP || m == Material.KELP_PLANT) p.set(x, y, z, "water");
            }
        }
    }

    /** A great iron anchor stood on its crown, half its stock rotted away, the chain trailing off over the sand. */
    static void anchor(Paint.Frame f, int b, long h) {
        for (int y = b; y <= b + 10; y++) f.set(0, y, 0, "iron_block");                       // the shank
        int[][] arm = {{1, 1}, {2, 1}, {3, 2}, {3, 3}, {4, 4}};                                 // the arms (crown at b)
        for (int[] a : arm) { f.set(a[0], b + a[1], 0, "iron_block"); f.set(-a[0], b + a[1], 0, "iron_block"); }
        f.set(4, b + 5, 0, "iron_bars"); f.set(-4, b + 5, 0, "iron_bars");                     // the flukes' tips
        f.set(3, b + 4, 0, "heavy_weighted_pressure_plate"); f.set(-3, b + 4, 0, "heavy_weighted_pressure_plate");
        for (int z = -3; z <= 3; z++) if (u(h, 10 + z) > 0.3) f.set(0, b + 8, z, "stripped_dark_oak_log[axis=z]"); // the stock
        for (int k : new int[]{-1, 1}) { f.set(k, b + 11, 0, "iron_block"); f.set(k, b + 13, 0, "iron_block"); } // the ring
        f.set(0, b + 13, 0, "iron_block");
        for (int x = 2; x <= 16; x++) {                                                         // the chain, sagging down
            int y = Math.max(b + 1, b + 12 - (int) Math.round((x - 1) * (x - 1) / 14.0));
            f.set(x, y, 0, y > b + 1 ? "iron_chain[axis=x]" : "iron_chain[axis=z]");
        }
        for (int i = 0; i < 6; i++) if (u(h, 20 + i) < 0.6) put(f, (int) (u(h, 30 + i) * 7) - 3, b + 1, (int) (u(h, 40 + i) * 5) - 2, i % 2 == 0 ? "sea_pickle[pickles=2]" : "tube_coral");
    }

    /** A boat floating keel up on the floor, cargo spilled out round it. */
    static void boat(DeepGenerator g, DeepGenerator.Noise n, Paint.Frame f, int b, long h) {
        for (int z = 0; z <= 12; z++) {
            int half = (int) Math.round(1 + Math.sin(Math.PI * z / 12) * 2.6);
            for (int x = -half; x <= half; x++) {
                boolean edge = Math.abs(x) == half;
                if (f.u(x, b + 3, z, 3) < 0.1) continue;                                    // holes stove in
                f.set(x, b + 3, z, "spruce_planks");                                           // the bottom, upside down
                if (edge) { f.set(x, b + 2, z, "spruce_planks"); f.set(x, b + 1, z, "stripped_spruce_log[axis=z]"); }
            }
            f.set(0, b + 4, z, "stripped_spruce_log[axis=z]");                                 // the keel on top
        }
        f.set(0, b + 4, -1, "spruce_fence"); f.set(0, b + 4, 13, "spruce_fence");
        f.set(-1, b + 1, 4, "barrel[facing=up]"); f.set(1, b + 1, 7, "decorated_pot");        // what's left under it
        f.set(0, b + 1, 9, "skeleton_skull[rotation=4]");
        // cargo spilled out over the sand
        for (int i = 0; i < 9; i++) {
            int x = (int) (u(h, 50 + i) * 15) - 7, z = (int) (u(h, 60 + i) * 18) - 3;
            if (Math.abs(x) <= 3 && z >= 0 && z <= 12) continue;
            int fy = g.upperFloor(n, f.wx(x, z), f.wz(x, z)) + 1;
            String m = switch (i % 5) { case 0 -> "barrel[facing=" + (u(h, 70 + i) < 0.5 ? "east" : "up") + "]"; case 1 -> "decorated_pot";
                case 2 -> "oak_trapdoor[half=bottom]"; case 3 -> "spruce_slab[type=bottom]"; default -> "lantern[hanging=false]"; };
            put(f, x, fy, z, m);
        }
        for (int k = 0; k < 4; k++) put(f, 4 + k, g.upperFloor(n, f.wx(4 + k, 2), f.wz(4 + k, 2)) + 1, 2, "spruce_slab[type=bottom]"); // an oar
    }

    /** A lighthouse fallen on its side: the stump still standing, the striped tower across the floor, the lamp aglow. */
    static void lighthouse(Paint.Frame f, int b, long h) {
        double R = 3.4;
        int len = 24 + (int) (u(h, 5) * 6), cy = b + 3;
        // the stump
        int stump = 4 + (int) (u(h, 6) * 4);
        for (int x = -4; x <= 4; x++) for (int z = -10; z <= -2; z++) {
            double d = Math.hypot(x, z + 6);
            if (d > R + 0.5) continue;
            for (int y = b - 1; y <= b + stump - (d > R - 0.5 ? (int) (f.u(x, 0, z, 7) * 3) : 0); y++)
                f.set(x, y, z, d > R - 0.6 ? ((y - b) / 3 % 2 == 0 ? "white_terracotta" : "red_terracotta") : "water");
        }
        // the fallen tower along local z: a hollow tube, striped, with windows, a few stairs left inside
        for (int z = 0; z <= len; z++) for (int x = -4; x <= 4; x++) for (int y = cy - 4; y <= cy + 4; y++) {
            double d = Math.hypot(x, y - cy);
            if (d > R + 0.5 || d < R - 0.6) continue;
            if (f.u(x, y, z, 8) < 0.06) continue;                                                // cracks
            boolean window = z % 7 == 3 && Math.abs(y - cy) <= 1 && Math.abs(x) >= 3;
            f.set(x, y, z, window ? "glass_pane" : (z / 4) % 2 == 0 ? "white_terracotta" : "red_terracotta");
        }
        for (int z = 2; z < len; z += 3) put(f, (z % 2 == 0 ? 1 : -1), cy - 2, z, "stone_brick_stairs[facing=south]");
        // the lamp room at the end: a gallery of iron bars, glass, the lens still burning, a copper cap
        int lz = len + 1;
        for (int z = lz; z <= lz + 4; z++) for (int x = -3; x <= 3; x++) for (int y = cy - 3; y <= cy + 3; y++) {
            double d = Math.hypot(x, y - cy);
            if (d > 3.2) continue;
            if (z == lz) { if (d > 2.4) f.set(x, y, z, "iron_bars"); continue; }
            if (d > 2.2) f.set(x, y, z, z == lz + 4 ? "oxidized_cut_copper" : "glass");
        }
        f.set(0, cy, lz + 2, "sea_lantern"); f.set(0, cy, lz + 1, "glowstone"); f.set(0, cy, lz + 3, "sea_lantern");
        for (int k = 0; k < 3; k++) f.set(0, cy, lz + 5 + k, k == 2 ? "lightning_rod[facing=south]" : "oxidized_copper");
    }

    /** A nautilus shell as tall as a house, lying on its edge, coiled in cream and rust bands. */
    static void shell(Paint.Frame f, int b, long h) {
        double k = 0.18, base = 1.25;
        int top = 0;
        for (double t = 0; t < 3 * Math.PI; t += 0.035) {
            double e = Math.exp(k * t), r = base * e, rt = 0.62 * e;
            double cx = Math.cos(t) * r, cy = b + 6.8 + Math.sin(t) * r;
            boolean open = t > 3 * Math.PI - 1.1;                                                // the aperture: hollow
            String band = ((int) (t * 2.2)) % 2 == 0 ? "white_terracotta" : "orange_terracotta";
            for (int x = (int) Math.floor(cx - rt); x <= cx + rt; x++) for (int y = (int) Math.floor(cy - rt); y <= cy + rt; y++)
                for (int z = (int) Math.floor(-rt); z <= rt; z++) {
                    double d = Math.sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5) * (z + 0.5));
                    if (d > rt) continue;
                    if (open && d < rt - 1.1) continue;
                    if (y < b - 1) continue;
                    f.set(x, y, z, open && d >= rt - 1.1 ? "pink_terracotta" : band);
                    top = Math.max(top, y);
                }
        }
        put(f, 0, b + 1, 4, "sea_pickle[pickles=4]");
        put(f, -2, b + 1, 3, "sea_pickle[pickles=2]");
    }

    /** A giant stone head buried to the chin, eyes hollow and lit from within, moss on its brow. */
    static void head(Paint.Frame f, int b, long h) {
        for (int y = b - 2; y <= b + 9; y++) for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) {
            boolean corner = Math.abs(x) == 3 && Math.abs(z) == 2;
            if (corner && y > b + 7) continue;
            f.set(x, y, z, y >= b + 9 ? "mossy_cobblestone" : f.u(x, y, z, 9) < 0.18 ? "cobbled_deepslate" : "tuff");
        }
        for (int x = -3; x <= 3; x++) f.set(x, b + 7, -3, "tuff_brick_slab[type=top]");             // the brow
        for (int y = b + 3; y <= b + 6; y++) f.set(0, y, -3, "tuff_bricks");                       // the nose
        f.set(0, b + 3, -4, "tuff_brick_stairs[facing=south,half=bottom]");
        for (int s = -1; s <= 1; s += 2) { f.set(2 * s, b + 6, -2, "water"); f.set(2 * s, b + 6, -1, "sea_lantern"); } // hollow, glowing eyes
        for (int x = -1; x <= 1; x++) f.set(x, b + 1, -3, "polished_tuff");                        // the lips
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (Math.hypot(x, z) <= 2.3) f.set(x + 1, b + 10, z, "red_sandstone"); // the topknot, slipped
        f.set(1, b + 11, 0, "cut_red_sandstone");
        for (int i = 0; i < 4; i++) put(f, (int) (u(h, 80 + i) * 9) - 4, b + 10, (int) (u(h, 84 + i) * 5) - 2, "seagrass");
    }

    /** Crates and barrels fallen from a ship that never arrived, scattered over the floor. */
    static void cargo(DeepGenerator g, DeepGenerator.Noise n, Paint.Frame f, int b, long h) {
        for (int i = 0; i < 18; i++) {
            double a = u(h, 100 + i) * Math.PI * 2, d = 1 + u(h, 120 + i) * 11;
            int x = (int) Math.round(Math.cos(a) * d), z = (int) Math.round(Math.sin(a) * d);
            int fy = g.upperFloor(n, f.wx(x, z), f.wz(x, z)) + 1;
            double t = u(h, 140 + i);
            if (t < 0.3) { // a crate: planks with trapdoor faces
                put(f, x, fy, z, "stripped_oak_wood");
                if (t < 0.12) put(f, x, fy + 1, z, "stripped_oak_wood");
            } else if (t < 0.55) put(f, x, fy, z, "barrel[facing=" + (t < 0.42 ? "up" : "north") + "]");
            else if (t < 0.7) put(f, x, fy, z, "decorated_pot");
            else if (t < 0.8) put(f, x, fy, z, "hay_block");
            else if (t < 0.9) put(f, x, fy, z, "iron_chain[axis=x]");
            else put(f, x, fy, z, "oak_trapdoor[half=bottom]");
        }
        put(f, 0, g.upperFloor(n, f.wx(0, 0), f.wz(0, 0)) + 1, 0, "lantern[hanging=false]");
    }

    // =========================================================================================== the Abyss
    static void abyss(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int reach = 16;
        for (int gx = Math.floorDiv(p.x0 - reach, ABYSS); gx <= Math.floorDiv(p.x0 + 15 + reach, ABYSS); gx++)
            for (int gz = Math.floorDiv(p.z0 - reach, ABYSS); gz <= Math.floorDiv(p.z0 + 15 + reach, ABYSS); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 811);
                if (DeepGenerator.unit(h) > 0.5) continue;
                int x = gx * ABYSS + 20 + (int) (u(h, 1) * (ABYSS - 40)), z = gz * ABYSS + 20 + (int) (u(h, 2) * (ABYSS - 40));
                if (!p.touches(x - reach, z - reach, x + reach, z + reach)) continue;
                if (Math.abs(x) > 2940 || Math.abs(z) > 2940 || DeepGenerator.flatWeight(x, z) > 0 || Trench.near(x, z, 20)) continue;
                if (Math.max(Math.abs(x - DeepGenerator.FLAT_X), Math.abs(z - DeepGenerator.FLAT_Z)) < DeepGenerator.FLAT_HALF + 60) continue;
                int floor = g.abyssFloor(n, x, z), mound = g.moundTop(n, x, z);
                if (mound != Integer.MIN_VALUE && mound > floor) continue;                    // not on a mound (caves run through those)
                int up = g.upperFloor(n, x, z), ceil = g.abyssCeiling(n, x, z);
                if (up - ceil < 14) ceil = up - 14;
                int room = ceil - floor;
                if (room < 16) continue;
                int type = (int) (u(h, 3) * 4);
                switch (type) {
                    case GLOWCAP -> glowcaps(g, n, p, x, z, h);
                    case OBELISK -> obelisk(g, n, p, x, z, floor, h);
                    case CAGE -> { if (room >= 24) cage(p, x, z, floor, ceil, h); else glowcaps(g, n, p, x, z, h); }
                    default -> sculk(g, n, p, x, z, h);
                }
            }
    }

    static int abyssTop(DeepGenerator g, DeepGenerator.Noise n, int x, int z) {
        int floor = g.abyssFloor(n, x, z), mound = g.moundTop(n, x, z);
        return mound == Integer.MIN_VALUE ? floor : Math.max(floor, mound);
    }

    /** Giant mushrooms with glowing caps: the only light for a long way. */
    static void glowcaps(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, long h) {
        int count = 5 + (int) (u(h, 5) * 5);
        for (int i = 0; i < count; i++) {
            double a = u(h, 10 + i) * Math.PI * 2, d = i == 0 ? 0 : 3 + u(h, 30 + i) * 9;
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            int fy = abyssTop(g, n, x, z) + 1;
            int stem = (i == 0 ? 9 : 3) + (int) (u(h, 50 + i) * (i == 0 ? 6 : 6));
            double cr = i == 0 ? 4.4 : 1.6 + u(h, 70 + i) * 1.8;
            for (int y = fy; y < fy + stem; y++) put(p, x, y, z, "mushroom_stem");
            int capY = fy + stem;
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                double dd = Math.hypot(dx, dz);
                if (dd > cr) continue;
                int lift = (int) Math.round((1 - dd / cr) * Math.min(2.5, cr * 0.6));
                boolean spot = Paint.u(x + dx, capY + lift, z + dz, 12) < 0.3;
                put(p, x + dx, capY + lift, z + dz, spot ? "shroomlight" : "warped_wart_block");
                if (dd > cr - 1.1) put(p, x + dx, capY + lift - 1, z + dz, "warped_wart_block");          // the rim curls down
                else if (Paint.u(x + dx, capY, z + dz, 13) < 0.25) put(p, x + dx, capY + lift - 1, z + dz, "glow_lichen[down=false,up=true]");
            }
        }
        for (int i = 0; i < 14; i++) {
            int x = cx + (int) (u(h, 90 + i) * 21) - 10, z = cz + (int) (u(h, 110 + i) * 21) - 10;
            put(p, x, abyssTop(g, n, x, z) + 1, z, i % 3 == 0 ? "sea_pickle[pickles=3]" : "glow_lichen[down=true]");
        }
    }

    /** A tapering obelisk of blackstone with crying obsidian runes, in a ring of standing stones lit by soul lanterns. */
    static void obelisk(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, int floor, long h) {
        int H = 14 + (int) (u(h, 6) * 8);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            put(p, cx + x, floor + 1, cz + z, Math.abs(x) == 2 && Math.abs(z) == 2 ? "chiseled_polished_blackstone" : "polished_blackstone");
            if (Math.abs(x) == 2 && Math.abs(z) == 2) put(p, cx + x, floor + 2, cz + z, "soul_lantern[hanging=false]");
        }
        for (int y = floor + 2; y <= floor + 1 + H; y++) {
            int w = y < floor + 2 + H * 2 / 3 ? 1 : 0;
            boolean rune = (y - floor) % 4 == 0;
            for (int x = -w; x <= w; x++) for (int z = -w; z <= w; z++)
                put(p, cx + x, y, cz + z, rune && (x + z + y) % 2 == 0 ? "crying_obsidian" : "polished_blackstone_bricks");
        }
        put(p, cx, floor + 2 + H, cz, "gilded_blackstone");
        for (int k = 0; k < 6; k++) {
            double a = k * Math.PI / 3 + u(h, 7);
            int x = cx + (int) Math.round(Math.cos(a) * 8), z = cz + (int) Math.round(Math.sin(a) * 8);
            int fy = abyssTop(g, n, x, z) + 1, sh = 3 + (int) (u(h, 20 + k) * 4);
            for (int y = fy; y < fy + sh; y++) put(p, x, y, z, y == fy + sh - 1 && k % 2 == 0 ? "crying_obsidian" : "blackstone");
            if (k % 2 == 1) put(p, x, fy + sh, z, "soul_lantern[hanging=false]");
        }
    }

    /** An iron cage hanging from the ceiling on a long chain; something was left in it. */
    static void cage(Paint p, int cx, int cz, int floor, int ceil, long h) {
        int bottom = floor + 6 + (int) (u(h, 8) * Math.max(1, ceil - floor - 22));
        int top = bottom + 4;
        for (int y = top + 1; y < ceil; y++) put(p, cx, y, cz, "iron_chain[axis=y]");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            put(p, cx + x, bottom, cz + z, "polished_blackstone_slab[type=top]");
            put(p, cx + x, top, cz + z, "polished_blackstone_slab[type=bottom]");
            if (x != 0 || z != 0) for (int y = bottom + 1; y < top; y++) put(p, cx + x, y, cz + z, "iron_bars");
        }
        put(p, cx, bottom + 1, cz, u(h, 9) < 0.5 ? "skeleton_skull[rotation=" + (int) (u(h, 10) * 16) + "]" : "soul_lantern[hanging=false]");
        put(p, cx, top - 1, cz, "soul_lantern[hanging=true]");
    }

    /** A creeping patch of sculk round a catalyst, with a few sensors twitching in the silence. */
    static void sculk(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz, long h) {
        double R = 6 + u(h, 11) * 4;
        for (int x = (int) -R; x <= R; x++) for (int z = (int) -R; z <= R; z++) {
            double d = Math.hypot(x, z) + Paint.u(cx + x, 0, cz + z, 14) * 2;
            if (d > R) continue;
            int fy = abyssTop(g, n, cx + x, cz + z);
            if (!p.in(cx + x, fy, cz + z)) continue;
            Material t = p.get(cx + x, fy, cz + z);
            if (t == Material.BLACK_CONCRETE_POWDER || t == Material.BLACKSTONE || t == Material.BASALT) p.set(cx + x, fy, cz + z, "sculk");
            double r = Paint.u(cx + x, fy, cz + z, 15);
            if (r < 0.04 && d > 2) put(p, cx + x, fy + 1, cz + z, "sculk_sensor");
            else if (r < 0.2) put(p, cx + x, fy + 1, cz + z, "sculk_vein[down=true]");
            else if (r < 0.24) put(p, cx + x, fy + 1, cz + z, "bone_block[axis=" + (r < 0.22 ? "x" : "z") + "]");
        }
        put(p, cx, abyssTop(g, n, cx, cz) + 1, cz, "sculk_catalyst");
    }

    // =========================================================================================== the Trench's descent rigs
    static void rigs(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        for (double s = 150; s < Trench.LENGTH - 220; s += RIG) {
            int k = 0;
            while (k < Trench.S.length - 2 && Trench.S[k + 1] < s) k++;
            double cx = Trench.PX[k], cz = Trench.PZ[k], vx = Trench.PX[k + 1] - cx, vz = Trench.PZ[k + 1] - cz, l = Math.hypot(vx, vz);
            if (l == 0) continue;
            vx /= l; vz /= l;
            double side = ((int) (s / RIG)) % 2 == 0 ? 1 : -1, nx = -vz * side, nz = vx * side;
            if (!p.touches((int) cx - 60, (int) cz - 60, (int) cx + 60, (int) cz + 60)) continue;
            int top = g.upperFloor(n, (int) cx, (int) cz);
            double rim = Trench.half(n, new Trench.Spot(0, s, 1), (int) cx, top, (int) cz);
            int bx = (int) Math.round(cx + nx * (rim + 4)), bz = (int) Math.round(cz + nz * (rim + 4));
            int gy = g.upperFloor(n, bx, bz);
            long h = DeepGenerator.hash(n.seed, (int) s, 7, 821);
            // the derrick: two legs leaning in, a cross beam, a pulley, a winch drum at its foot
            for (int y = gy + 1; y <= gy + 9; y++) for (int sgn = -1; sgn <= 1; sgn += 2) {
                int off = (int) Math.round(sgn * (2.5 - (y - gy) * 0.22));
                put(p, bx + (int) Math.round(vx * off), y, bz + (int) Math.round(vz * off), "stripped_spruce_log[axis=y]");
            }
            put(p, bx, gy + 10, bz, "stripped_spruce_log[axis=y]");
            put(p, bx - (int) Math.round(nx * 2), gy + 1, bz - (int) Math.round(nz * 2), "barrel[facing=up]");
            put(p, bx + (int) Math.round(vx * 2) - (int) Math.round(nx), gy + 1, bz + (int) Math.round(vz * 2) - (int) Math.round(nz), "grindstone[face=floor,facing=north]");
            // the boom, out over the rift
            int reach = 8;
            for (int i = 0; i <= reach; i++) put(p, bx - (int) Math.round(nx * i), gy + 10, bz - (int) Math.round(nz * i), i == reach ? "dark_oak_planks" : "spruce_fence");
            int hx = bx - (int) Math.round(nx * reach), hz = bz - (int) Math.round(nz * reach);
            // the chain into the dark, a cage platform at its end
            int drop = 40 + (int) (u(h, 1) * 50), end = gy + 9;
            for (int y = gy + 9; y > gy + 9 - drop; y--) {
                if (!p.in(hx, y, hz)) { end = y; continue; }
                if (!soft(p.get(hx, y, hz))) break;
                p.set(hx, y, hz, "iron_chain[axis=y]");
                end = y;
            }
            int py = end - 1;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                put(p, hx + x, py, hz + z, "spruce_slab[type=top]");
                if (x != 0 || z != 0) put(p, hx + x, py + 1, hz + z, (x == 0 || z == 0) && u(h, 2 + x + z * 3) < 0.4 ? "water" : "spruce_fence");
            }
            put(p, hx, py - 1, hz, "soul_lantern[hanging=true]");
        }
    }

    // =========================================================================================== the Sulphur Sea's drum dumps
    static void drums(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int cell = 64;
        for (int gx = Math.floorDiv(p.x0 - 10, cell); gx <= Math.floorDiv(p.x0 + 25, cell); gx++)
            for (int gz = Math.floorDiv(p.z0 - 10, cell); gz <= Math.floorDiv(p.z0 + 25, cell); gz++) {
                long h = DeepGenerator.hash(n.seed, gx, gz, 831);
                if (DeepGenerator.unit(h) > 0.35) continue;
                int cx = gx * cell + 12 + (int) (u(h, 1) * (cell - 24)), cz = gz * cell + 12 + (int) (u(h, 2) * (cell - 24));
                if (!p.touches(cx - 10, cz - 10, cx + 10, cz + 10) || Sulphur.weight(n, cx, cz) < 0.75) continue;
                if (Math.hypot(cx - Sulphur.WORKS_X, cz - Sulphur.WORKS_Z) < 45) continue;
                for (int i = 0; i < 12; i++) {
                    double a = u(h, 10 + i) * Math.PI * 2, d = u(h, 30 + i) * 7;
                    int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
                    int fy = g.upperFloor(n, x, z);
                    if (fy >= DeepGenerator.SEA - 2) continue;
                    String drum = u(h, 50 + i) < 0.5 ? "oxidized_copper" : u(h, 50 + i) < 0.8 ? "weathered_cut_copper" : "exposed_copper_grate";
                    put(p, x, fy + 1, z, drum);
                    if (u(h, 70 + i) < 0.35) put(p, x, fy + 2, z, drum);
                    if (p.in(x + 1, fy, z) && p.get(x + 1, fy, z).isSolid()) p.set(x + 1, fy, z, "lime_terracotta");      // the slick
                    if (p.in(x, fy, z - 1) && p.get(x, fy, z - 1).isSolid()) p.set(x, fy, z - 1, i % 3 == 0 ? "verdant_froglight" : "lime_terracotta");
                }
            }
    }

    // =========================================================================================== the Mermaid Den
    static void den(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int mx = MermaidDen.MX, mz = MermaidDen.MZ, d = MermaidDen.PALACE_R + 22; // (outside the palace's square)
        // four queens of old, between the avenues, facing the palace
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4 + k * Math.PI / 2;
            int sx = mx + (int) Math.round(Math.cos(a) * d), sz = mz + (int) Math.round(Math.sin(a) * d);
            if (!p.touches(sx - 3, sz - 3, sx + 3, sz + 3)) continue;
            int fy = g.upperFloor(n, sx, sz) + 1;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) { put(p, sx + x, fy, sz + z, "prismarine_bricks"); put(p, sx + x, fy + 1, sz + z, Math.abs(x) + Math.abs(z) == 2 ? "prismarine_brick_slab[type=bottom]" : "dark_prismarine"); }
            int y = fy + 2;
            put(p, sx, y, sz, "calcite"); put(p, sx, y + 1, sz, "calcite");                      // the tail coiled on the plinth
            put(p, sx + 1, y, sz, "smooth_quartz_stairs[facing=west]"); put(p, sx - 1, y, sz, "smooth_quartz_stairs[facing=east]");
            for (int yy = y + 2; yy <= y + 4; yy++) put(p, sx, yy, sz, "smooth_quartz");         // the body
            put(p, sx + 1, y + 4, sz, "quartz_slab[type=top]"); put(p, sx - 1, y + 4, sz, "quartz_slab[type=top]"); // arms
            put(p, sx, y + 5, sz, "pearlescent_froglight");                                       // the head (glows)
            put(p, sx, y + 6, sz, "gold_block");                                                  // the crown
            put(p, sx + 2, y + 3, sz, "lightning_rod[facing=up]");                                // her trident
            put(p, sx + 2, y + 2, sz, "lightning_rod[facing=up]");
            put(p, sx - 2, fy + 2, sz, "sea_pickle[pickles=4]"); put(p, sx, fy + 2, sz + 2, "sea_pickle[pickles=4]");
        }
        // bubble fountains: a ring of soul sand vents round the palace, columns of bubbles up to the surface
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + Math.PI / 8;
            int bx = mx + (int) Math.round(Math.cos(a) * (MermaidDen.PALACE_R + 7)), bz = mz + (int) Math.round(Math.sin(a) * (MermaidDen.PALACE_R + 7));
            if (!p.in(bx, 0, bz)) continue;
            int fy = g.upperFloor(n, bx, bz);
            if (!soft(p.get(bx, fy + 1, bz))) continue;
            p.set(bx, fy, bz, "soul_sand");
            for (int y = fy + 1; y < DeepGenerator.SEA && soft(p.get(bx, y, bz)); y++) p.set(bx, y, bz, "bubble_column[drag=false]");
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) put(p, bx + o[0], fy + 1, bz + o[1], "prismarine_wall");
        }
    }
}
