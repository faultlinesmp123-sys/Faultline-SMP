package net.faultlinesmp.deep;

import java.util.ArrayList;
import java.util.List;

/**
 * HALCYON (Deep 1.17.0): a living city under a glass dome far out in the Dark Ocean's south-west, round HX, HZ (floor top
 * y HF). Pure functions like Town / the regions: every chunk it touches draws the whole of it and keeps its own blocks.
 *  - THE DOME: an ellipsoid 100 across (radius) and 72 high, glass panels between prismarine ribs and sea-lantern bands,
 *    a lantern crown. Air only where all six neighbours are inside it too: the sea never touches the dry air.
 *  - THE PLAZA in the middle: a fountain with a diver statue and four bubble tubes, benches, planters, lamps.
 *  - THE RING ROAD (r 30..35) and four AVENUES; DIAGONAL PATHS out to the mine (NW), the building site (NE), the farms (SW)
 *    and the tree grove (SE). Citizens walk only on these (Citizens.java plans along them).
 *  - 16 HOUSES round the ring, each with beds (the citizens sleep in them), a table, a stove, a barrel.
 *  - THE BIO-CENTER (north): white halls, a great green tank and 12 specimen jars (the bosses, tiny: Exhibits.java).
 *  - THE AQUARIUM (east): ten big tanks of sea water (every Dark Ocean creature swims in them: Exhibits.java).
 *  - THE KELP & KETTLE tavern and the GUARD BARRACKS on the south avenue, the DOCK OFFICE by the south gate, market stalls
 *    on the west avenue, the farms, the grove, the coal mine (a stair tunnel under the floor into a gallery of coal),
 *    the building site the builders work on, two gates (south: the dock, west) with doors in their outer face.
 *  - outside the south gate: THE DOCK, a lit landing pad on the sea floor where the patrol subs berth (SubFleet.java).
 */
final class Halcyon {
    static final int HX = -1800, HZ = 2200, HF = 70, G = HF + 1;
    static final double A = 100, B = 72, SHELL = 1.6;
    static final double R = 118, BLEND = 60;

    private Halcyon() { }

    // =========================================================================================== shape
    static double weight(int x, int z) { return Regions.weight(x, z, HX, HZ, R, BLEND, 6); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, HX, HZ, R + BLEND + 40 + extra); }

    static double floor() { return HF; }

    static double e(double x, double y, double z) {
        double dx = (x - HX) / A, dy = (y - HF) / B, dz = (z - HZ) / A;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** In the city's dry air (or down the mine). */
    static boolean inside(double x, double y, double z) {
        if (y > HF - 0.5 && e(x, y, z) < 1 - SHELL / A) return true;
        double dx = x - HX, dz = z - HZ;
        return dx >= MINE_X0 && dx <= MINE_X1 && dz >= MINE_Z0 && dz <= MINE_Z1 && y >= HF - 22 && y < HF + 1;
    }

    /** Anywhere the city's guards care about: the dome, its shell, the gates, the dock. */
    static boolean grounds(double x, double y, double z) {
        if (inside(x, y, z)) return true;
        double dx = x - HX, dz = z - HZ;
        if (e(x, Math.max(y, HF), z) < 1.06 && y > HF - 25) return true;
        return Math.abs(dx) <= 14 && dz >= 96 && dz <= 126 && y > HF - 3 && y < HF + 12; // the dock
    }

    static double headroom(double dx, double dz) {
        double h = 1 - (dx * dx + dz * dz) / (A * A);
        return h <= 0 ? 0 : B * Math.sqrt(h) - 3;
    }

    // =========================================================================================== layout (relative to HX, HZ)
    static final int RING_IN = 30, RING_OUT = 35, PLAZA_R = 26;
    static final double RING_R = 32.5;
    static final int LAB_X0 = -22, LAB_X1 = 22, LAB_Z0 = -88, LAB_Z1 = -52;
    static final int AQ_X0 = 52, AQ_X1 = 88, AQ_Z0 = -22, AQ_Z1 = 22;
    static final int TAV_X0 = -26, TAV_X1 = -8, BAR_X0 = 8, BAR_X1 = 26, SA_Z0 = 54, SA_Z1 = 72;
    static final int DOCK_X0 = 6, DOCK_X1 = 16, DOCK_Z0 = 80, DOCK_Z1 = 90;
    static final int FARM_X0 = -84, FARM_X1 = -44, FARM_Z0 = 36, FARM_Z1 = 82;
    static final int GROVE_X0 = 44, GROVE_X1 = 84, GROVE_Z0 = 36, GROVE_Z1 = 82;
    static final int SITE_X0 = 38, SITE_X1 = 52, SITE_Z0 = -66, SITE_Z1 = -54;
    static final int HEAD_X0 = -60, HEAD_X1 = -48, HEAD_Z0 = -60, HEAD_Z1 = -48;
    static final int MINE_X0 = -88, MINE_X1 = -48, MINE_Z0 = -68, MINE_Z1 = -44;
    static final int TUNNEL_Z0 = -56, TUNNEL_Z1 = -54, TUNNEL_X_TOP = -52, DEPTH = 16;
    static final int GAL_X0 = -84, GAL_X1 = -68, GAL_Z0 = -64, GAL_Z1 = -48;
    static final int MARKET_X0 = -88, MARKET_X1 = -56;

    /** The 16 house angles (degrees: 0 = +x, 90 = +z) at r 43, clear of the avenues and the diagonal paths. */
    static final int[] HOUSE_ANGLES = {15, 30, 60, 75, 105, 120, 150, 165, 195, 210, 240, 255, 285, 300, 330, 345};
    static final double HOUSE_R = 43;
    static final int HW = 9, HD = 8;

    /** One house: its frame (Paint.Frame: front faces local -z, r turns it), its world box, its beds. */
    record House(int i, int ox, int oz, int r, int x0, int z0, int x1, int z1, int beds, int pal) {
        int[] world(int lx, int lz) { return new int[]{wx(ox, r, lx, lz), wz(oz, r, lx, lz)}; }
        /** The head of bed b (0 or 1): where its sleeper lies. */
        int[] bed(int b) { return world(b == 0 ? 1 : HW - 2, HD - 2); }
        int[] table() { return world(4, 4); }
        int[] door() { return world(4, 0); }
        int[] porch() { return world(4, -2); }
    }

    static int wx(int ox, int r, int lx, int lz) { return switch (r) { case 1 -> ox - lz; case 2 -> ox - lx; case 3 -> ox + lz; default -> ox + lx; }; }

    static int wz(int oz, int r, int lx, int lz) { return switch (r) { case 1 -> oz + lx; case 2 -> oz - lz; case 3 -> oz - lx; default -> oz + lz; }; }

    static final List<House> HOUSES = new ArrayList<>();

    static {
        for (int i = 0; i < HOUSE_ANGLES.length; i++) {
            double a = Math.toRadians(HOUSE_ANGLES[i]);
            int cx = HX + (int) Math.round(Math.cos(a) * HOUSE_R), cz = HZ + (int) Math.round(Math.sin(a) * HOUSE_R);
            double c = Math.cos(a), s = Math.sin(a);
            // the front faces the middle: r 0 = front north (-z), 1 = east, 2 = south, 3 = west
            int r = Math.abs(c) > Math.abs(s) ? (c > 0 ? 3 : 1) : (s > 0 ? 0 : 2);
            int ox0 = wx(0, r, HW / 2, HD / 2), oz0 = wz(0, r, HW / 2, HD / 2);
            int ox = cx - ox0, oz = cz - oz0;
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] k : new int[][]{{0, 0}, {HW - 1, 0}, {0, HD - 1}, {HW - 1, HD - 1}}) {
                int x = wx(ox, r, k[0], k[1]), z = wz(oz, r, k[0], k[1]);
                x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z);
            }
            HOUSES.add(new House(i, ox, oz, r, x0, z0, x1, z1, i % 3 == 0 ? 1 : 2, i % 4));
        }
    }

    // specimen jars in the Bio-Center: {kind, rel x, rel z} (the middle of each jar; the Titan's great tank is the centre)
    static final String[] JAR_KINDS = {"scourge", "umbral", "coralia", "inkjaw", "grandmaw", "lament", "kraken", "jacob", "goblin", "rimeback", "king", "abyssal"};
    static final int[][] JARS = {{-17, -84}, {-17, -78}, {-17, -72}, {-17, -66}, {-17, -60}, {17, -84}, {17, -78}, {17, -72}, {17, -66}, {17, -60}, {-8, -84}, {8, -84}};
    static final int TANK_X = 0, TANK_Z = -70, TANK_R = 4;
    static final int JAR_Y0 = G + 1, JAR_Y1 = G + 5; // water inside, the jar's bottom/top caps outside that

    /** The aquarium's tanks: water boxes {x0, y0, z0, x1, y1, z1} (relative x/z, absolute y), inclusive. */
    static final int[][] TANKS;

    static {
        List<int[]> t = new ArrayList<>();
        int[][] spans = {{54, 61}, {63, 70}, {72, 79}, {81, 87}};
        for (int[] s : spans) t.add(new int[]{s[0], G, -21, s[1], G + 8, -16});
        for (int[] s : spans) t.add(new int[]{s[0], G, 16, s[1], G + 8, 21});
        t.add(new int[]{61, G, -4, 69, G + 8, 4});
        t.add(new int[]{71, G, -4, 79, G + 8, 4});
        TANKS = t.toArray(new int[0][]);
    }

    // =========================================================================================== where things are (world)
    static int[] w(int dx, int dz) { return new int[]{HX + dx, HZ + dz}; }

    static int[] labLectern() { return w(4, -55); }
    static int[] aquaLectern(int i) { return w(56, i == 0 ? 4 : -4); }
    static int[] dockMaster() { return w(15, 85); }
    static int[] curator() { return w(57, 6); }
    static int[] spawnSpot() { return w(0, 22); }

    // =========================================================================================== painting
    static void paint(DeepGenerator g, Paint p) {
        if (!p.touches(HX - 130, HZ - 130, HX + 130, HZ + 130)) return;
        mineRock(p);
        dome(p);
        ground(p);
        plaza(p);
        for (House h : HOUSES) if (p.touches(h.x0 - 3, h.z0 - 3, h.x1 + 3, h.z1 + 3)) house(p, h);
        if (p.touches(HX + LAB_X0 - 2, HZ + LAB_Z0 - 2, HX + LAB_X1 + 2, HZ + LAB_Z1 + 2)) lab(p);
        if (p.touches(HX + AQ_X0 - 2, HZ + AQ_Z0 - 2, HX + AQ_X1 + 2, HZ + AQ_Z1 + 2)) aquarium(p);
        tavern(p);
        barracks(p);
        dockOffice(p);
        farms(p);
        grove(p);
        site(p);
        mine(p);
        market(p);
        lamps(p);
        gates(p);
        dock(p);
    }

    static void set(Paint p, int dx, int y, int dz, String s) { p.set(HX + dx, y, HZ + dz, s); }

    static void fill(Paint p, int x0, int y0, int z0, int x1, int y1, int z1, String s) {
        int ax0 = Math.max(Math.min(x0, x1), p.x0 - HX), ax1 = Math.min(Math.max(x0, x1), p.x0 + 15 - HX);
        int az0 = Math.max(Math.min(z0, z1), p.z0 - HZ), az1 = Math.min(Math.max(z0, z1), p.z0 + 15 - HZ);
        for (int x = ax0; x <= ax1; x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = az0; z <= az1; z++) set(p, x, y, z, s);
    }

    static double u(int dx, int y, int dz, int salt) { return Paint.u(HX + dx, y, HZ + dz, salt); }

    // ---------------------------------------------------------------------------- the dome
    private static void dome(Paint p) {
        double in = 1 - SHELL / A;
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx, z = p.z0 + lz;
            if (e(x, HF, z) > 1) continue;
            for (int y = G; y <= HF + B + 1; y++) {
                double ev = e(x, y, z);
                if (ev > 1) break;
                boolean air = ev < in && e(x + 1, y, z) <= 1 && e(x - 1, y, z) <= 1 && e(x, y + 1, z) <= 1 && e(x, y, z + 1) <= 1 && e(x, y, z - 1) <= 1;
                p.set(x, y, z, air ? "air" : shell(x, y, z));
            }
        }
    }

    private static String shell(int x, int y, int z) {
        double dx = x - HX, dz = z - HZ, hr = Math.hypot(dx, dz), dy = y - HF;
        if (hr < 3.2) return "sea_lantern";
        if (y <= G + 2) return y == G + 2 ? "prismarine_bricks" : "dark_prismarine";
        double ang = Math.atan2(dz, dx), seg = Math.PI / 12;
        boolean rib = hr > 6 && Math.abs(Math.IEEEremainder(ang, seg)) * hr < 0.8;
        boolean band = Math.abs(dy - 12) < 0.7 || Math.abs(dy - 28) < 0.7 || Math.abs(dy - 44) < 0.7 || Math.abs(dy - 58) < 0.7;
        if (rib && band) return "sea_lantern";
        if (rib) return Math.floorMod(y, 7) == 0 ? "sea_lantern" : "dark_prismarine";
        if (band) return "prismarine_bricks";
        // panels: lighter toward the top, a few tinted ones
        double r = Paint.u(x, y, z, 17), top = dy / B;
        if (r < 0.05) return "tinted_glass";
        if (r < 0.2 + top * 0.3) return "glass";
        if (r < 0.75) return "light_blue_stained_glass";
        return "cyan_stained_glass";
    }

    // ---------------------------------------------------------------------------- the ground
    static double rr(int dx, int dz) { return Math.hypot(dx, dz); }

    static boolean avenue(int dx, int dz) {
        if (Math.abs(dx) <= 2 && dz <= -PLAZA_R && dz >= LAB_Z1) return true;            // north, to the Bio-Center
        if (Math.abs(dx) <= 2 && dz >= PLAZA_R && dz <= 104) return true;                 // south, to the dock gate
        if (Math.abs(dz) <= 2 && dx >= PLAZA_R && dx <= AQ_X0) return true;              // east, to the aquarium
        return Math.abs(dz) <= 2 && dx <= -PLAZA_R && dx >= -104;                         // west, past the market to the gate
    }

    /** The diagonal paths: from the ring out to the mine, the site, the farms and the grove. */
    static final double[][] DIAG = {{-45, -45}, {45, -52}, {-46, 40}, {46, 40}};

    static boolean diag(int dx, int dz) {
        double r = rr(dx, dz);
        if (r < RING_OUT - 1) return false;
        for (double[] d : DIAG) {
            double len = Math.hypot(d[0], d[1]), ux = d[0] / len, uz = d[1] / len;
            double t = dx * ux + dz * uz;
            if (t < RING_IN || t > len + 1) continue;
            if (Math.abs(-dx * uz + dz * ux) <= 1.5) return true;
        }
        return false;
    }

    static boolean street(int dx, int dz) {
        double r = rr(dx, dz);
        return r >= RING_IN && r <= RING_OUT || avenue(dx, dz) || diag(dx, dz);
    }

    private static void ground(Paint p) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx, z = p.z0 + lz, dx = x - HX, dz = z - HZ;
            if (e(x, HF, z) > 1 - SHELL / A) continue;
            double r = rr(dx, dz), q = u(dx, HF, dz, 3);
            String top, under = "dirt", deco = null;
            if (r < PLAZA_R) {
                int ring = (int) r;
                top = ring % 4 == 0 ? "polished_diorite" : ring % 4 == 2 ? "smooth_stone" : (Math.floorMod((int) Math.floor(Math.atan2(dz, dx) * 8 / Math.PI), 2) == 0 ? "polished_andesite" : "calcite");
                under = "stone";
            } else if (street(dx, dz)) {
                boolean edge = r >= RING_IN && r <= RING_OUT && (r < RING_IN + 0.9 || r > RING_OUT - 0.9);
                top = edge ? "polished_deepslate" : q < 0.55 ? "smooth_stone" : q < 0.8 ? "polished_andesite" : "andesite";
                under = "stone";
            } else {
                top = q < 0.62 ? "moss_block" : q < 0.8 ? "rooted_dirt" : q < 0.92 ? "moss_block" : "coarse_dirt";
                double d2 = u(dx, G, dz, 4);
                if (d2 < 0.16) deco = "short_grass"; else if (d2 < 0.2) deco = "moss_carpet"; else if (d2 < 0.215) deco = "fern";
                else if (d2 < 0.225) deco = "dandelion"; else if (d2 < 0.235) deco = "azure_bluet"; else if (d2 < 0.24) deco = "cornflower";
            }
            p.set(x, HF, z, top);
            p.set(x, HF - 1, z, under);
            p.set(x, HF - 2, z, "stone");
            if (deco != null && p.get(x, G, z).isAir()) p.set(x, G, z, deco);
        }
    }

    // ---------------------------------------------------------------------------- the plaza
    private static void plaza(Paint p) {
        if (!p.touches(HX - PLAZA_R, HZ - PLAZA_R, HX + PLAZA_R, HZ + PLAZA_R)) return;
        // the basin
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
            double r = rr(dx, dz);
            if (r < 9) { set(p, dx, HF, dz, "prismarine_bricks"); set(p, dx, HF - 1, dz, "stone"); set(p, dx, G, dz, "water"); }
            else if (r < 10.2) { set(p, dx, G, dz, "quartz_bricks"); set(p, dx, G + 1, dz, "smooth_quartz_slab[type=bottom]"); }
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (rr(dx, dz) < 3.2 && rr(dx, dz) > 2) set(p, dx, HF, dz, "sea_lantern");
        // the plinth and THE DIVER: a founder in a copper diving suit holding a lamp up
        fill(p, -1, G, -1, 1, G + 2, 1, "chiseled_quartz_block");
        fill(p, -2, G + 3, -2, 2, G + 3, 2, "smooth_quartz");
        for (int dz : new int[]{-1, 1}) { set(p, 0, G + 4, dz, "polished_diorite"); set(p, 0, G + 5, dz, "polished_diorite"); } // legs
        fill(p, 0, G + 6, -1, 0, G + 8, 1, "waxed_copper_block");                                                             // the suit
        set(p, -1, G + 7, -1, "waxed_cut_copper"); set(p, -1, G + 7, 1, "waxed_cut_copper");                                    // the belt, the tank
        set(p, 1, G + 7, 0, "waxed_copper_block"); set(p, 1, G + 8, 0, "waxed_copper_block");                                   // the air tank on its back
        set(p, 0, G + 7, -2, "waxed_cut_copper"); set(p, 0, G + 8, -2, "waxed_cut_copper"); set(p, 0, G + 9, -2, "waxed_cut_copper"); // the raised arm
        set(p, 0, G + 10, -2, "sea_lantern");                                                                                   // the lamp
        set(p, 0, G + 9, 0, "waxed_copper_block"); set(p, -1, G + 9, 0, "light_blue_stained_glass");                           // the helmet + visor
        set(p, 0, G + 10, 0, "waxed_cut_copper_slab[type=bottom]");
        // four bubble tubes round the basin
        for (int k = 0; k < 4; k++) {
            int tx = k == 0 || k == 2 ? 5 : -5, tz = k == 0 || k == 3 ? 5 : -5;
            set(p, tx, HF, tz, "soul_sand");
            for (int y = G; y <= G + 9; y++) {
                set(p, tx, y, tz, "bubble_column[drag=false]");
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(p, tx + o[0], y, tz + o[1], y == G ? "quartz_bricks" : "light_blue_stained_glass");
            }
            set(p, tx, G + 10, tz, "sea_lantern");
        }
        // benches and planters on the plaza ring
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI / 6 + Math.PI / 12;
            int bx = (int) Math.round(Math.cos(a) * 16), bz = (int) Math.round(Math.sin(a) * 16);
            boolean along = Math.abs(Math.cos(a)) < 0.7;
            for (int t = -1; t <= 1; t++) {
                int x = bx + (along ? t : 0), z = bz + (along ? 0 : t);
                String face = along ? (bz > 0 ? "north" : "south") : (bx > 0 ? "west" : "east");
                set(p, x, G, z, "spruce_stairs[facing=" + face + ",half=bottom,shape=straight]");
            }
            int px = (int) Math.round(Math.cos(a + Math.PI / 12) * 20), pz = (int) Math.round(Math.sin(a + Math.PI / 12) * 20);
            set(p, px, G, pz, "moss_block");
            set(p, px, G + 1, pz, k % 3 == 0 ? "flowering_azalea" : k % 3 == 1 ? "azalea" : "rose_bush[half=lower]");
            if (k % 3 == 2) set(p, px, G + 2, pz, "rose_bush[half=upper]");
        }
    }

    // ---------------------------------------------------------------------------- houses
    static final String[][] HPAL = {
            {"dark_oak_log", "white_terracotta", "stone_bricks", "deepslate_tile_stairs", "deepslate_tiles", "dark_oak_planks", "red"},
            {"spruce_log", "light_gray_terracotta", "cobblestone", "dark_oak_stairs", "dark_oak_planks", "spruce_planks", "blue"},
            {"stripped_dark_oak_log", "white_concrete", "bricks", "brick_stairs", "bricks", "dark_oak_planks", "green"},
            {"dark_oak_log", "smooth_sandstone", "polished_andesite", "prismarine_brick_stairs", "dark_prismarine", "spruce_planks", "cyan"},
    };

    private static void house(Paint p, House h) {
        Paint.Frame f = p.frame(h.ox, h.oz, h.r, false);
        String[] P = HPAL[h.pal];
        String post = P[0] + "[axis=y]", infill = P[1], lower = P[2], roof = P[3], ridge = P[4], gable = P[5], bed = P[6];
        int top = G + 4;
        f.fill(-1, G, -2, HW, G + 12, HD, "air");
        for (int x = -1; x <= HW; x++) for (int z = -2; z <= HD; z++) f.set(x, HF, z, z < 0 ? "smooth_stone" : "moss_block");
        for (int x = 0; x < HW; x++) for (int z = 0; z < HD; z++) {
            boolean edge = x == 0 || z == 0 || x == HW - 1 || z == HD - 1;
            f.set(x, HF, z, edge ? "stone_bricks" : "spruce_planks");
            f.set(x, HF - 1, z, "cobblestone");
        }
        for (int y = G; y <= top; y++) for (int x = 0; x < HW; x++) for (int z = 0; z < HD; z++) {
            boolean ex = x == 0 || x == HW - 1, ez = z == 0 || z == HD - 1;
            if (!ex && !ez) { if (y == top) f.set(x, y, z, "spruce_planks"); continue; }
            String m;
            if (ex && ez) m = post;
            else if (y == top) m = P[0] + "[axis=" + (ez ? "x" : "z") + "]";
            else if (y == G) m = lower;
            else m = infill;
            f.set(x, y, z, m);
        }
        // a loft floor under the roof
        // the gable roof: ridge along local x
        for (int k = 0; k <= HD / 2; k++) {
            int y = top + 1 + k;
            for (int x = -1; x <= HW; x++) {
                int zf = k - 1, zb = HD - k;
                if (zf < zb) {
                    f.set(x, y, zf, roof + "[facing=south,half=bottom,shape=straight]");
                    f.set(x, y, zb, roof + "[facing=north,half=bottom,shape=straight]");
                } else f.set(x, y, zf, ridge);
            }
            for (int z = k; z <= HD - 1 - k; z++) { f.set(0, y, z, gable); f.set(HW - 1, y, z, gable); }
        }
        // windows
        for (int y = G + 1; y <= G + 2; y++) {
            f.set(2, y, 0, "glass_pane[east=true,west=true]"); f.set(HW - 3, y, 0, "glass_pane[east=true,west=true]");
            f.set(2, y, HD - 1, "glass_pane[east=true,west=true]"); f.set(HW - 3, y, HD - 1, "glass_pane[east=true,west=true]");
            f.set(0, y, 3, "glass_pane[north=true,south=true]"); f.set(HW - 1, y, 3, "glass_pane[north=true,south=true]");
        }
        f.set(0, top + 2, HD / 2, "glass"); f.set(HW - 1, top + 2, HD / 2, "glass");
        // window boxes
        f.set(2, G, -1, "moss_block"); f.set(HW - 3, G, -1, "moss_block");
        f.set(2, G + 1, -1, "red_tulip"); f.set(HW - 3, G + 1, -1, "oxeye_daisy");
        // the door (always open: a friendly town) and a little porch roof
        f.set(4, G, 0, "spruce_door[facing=north,half=lower,hinge=left,open=true]");
        f.set(4, G + 1, 0, "spruce_door[facing=north,half=upper,hinge=left,open=true]");
        for (int x = 3; x <= 5; x++) f.set(x, G + 3, -1, roof + "[facing=south,half=top,shape=straight]");
        f.set(4, G + 2, -1, "lantern[hanging=true]");
        // inside: beds, a table, the stove, a barrel, a chest of drawers, a lamp
        f.set(1, G, HD - 3, bed + "_bed[facing=south,part=foot]"); f.set(1, G, HD - 2, bed + "_bed[facing=south,part=head]");
        if (h.beds > 1) { f.set(HW - 2, G, HD - 3, bed + "_bed[facing=south,part=foot]"); f.set(HW - 2, G, HD - 2, bed + "_bed[facing=south,part=head]"); }
        else { f.set(HW - 2, G, HD - 2, "bookshelf"); f.set(HW - 2, G + 1, HD - 2, "potted_red_tulip"); }
        f.set(4, G, 4, "spruce_fence"); f.set(4, G + 1, 4, "spruce_pressure_plate");
        f.set(3, G, 4, "spruce_stairs[facing=east,half=bottom,shape=straight]"); f.set(5, G, 4, "spruce_stairs[facing=west,half=bottom,shape=straight]");
        f.set(1, G, 1, "smoker[facing=east,lit=false]"); f.set(1, G, 2, "barrel[facing=up]");
        f.set(HW - 2, G, 1, "crafting_table"); f.set(HW - 2, G, 2, "spruce_trapdoor[facing=west,half=bottom,open=false]");
        f.set(4, G + 3, 4, "lantern[hanging=true]");
        f.set(4, G + 3, 2, "lantern[hanging=true]");
        f.set(4, G, HD - 2, P[6] + "_carpet");
    }

    // ---------------------------------------------------------------------------- the Bio-Center
    private static void lab(Paint p) {
        int x0 = LAB_X0, x1 = LAB_X1, z0 = LAB_Z0, z1 = LAB_Z1, top = G + 12;
        fill(p, x0, G, z0, x1, top + 12, z1, "air");
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            set(p, x, HF, z, (Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0) ? "cyan_terracotta" : "white_concrete");
            set(p, x, HF - 1, z, "stone");
        }
        for (int y = G; y <= top; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
            if (!ex && !ez) continue;
            String m;
            int along = ez ? x : z;
            if (y <= G) m = "polished_deepslate";
            else if (ex && ez) m = "quartz_pillar[axis=y]";
            else if (Math.floorMod(along, 5) == 0) m = "quartz_pillar[axis=y]";
            else if (y >= G + 3 && y <= G + 8) m = (y == G + 3 || y == G + 8) ? "smooth_quartz" : "light_blue_stained_glass";
            else if (y == top) m = "sea_lantern";
            else m = "white_concrete";
            set(p, x, y, z, m);
        }
        // the roof: white with glass skylights, a lit edge, and a glass dome over the great tank
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            boolean sky = !edge && Math.floorMod(x + 2, 8) < 3 && Math.floorMod(z + 2, 8) < 3;
            set(p, x, top + 1, z, edge ? "smooth_quartz_slab[type=bottom]" : sky ? "glass" : "white_concrete");
            if (!edge && Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 3) set(p, x, top, z, "sea_lantern");
        }
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) for (int y = top + 1; y <= top + 10; y++) {
            double d = Math.sqrt(dx * dx + dz * dz + (y - top - 1) * (y - top - 1.0) * 1.2);
            if (d > 9.5 || d < 8.5) continue;
            set(p, TANK_X + dx, y, TANK_Z + dz, Math.floorMod(dx + dz, 6) == 0 ? "sea_lantern" : "lime_stained_glass");
        }
        // the entrance: a 5-wide opening, lit pillars, steps
        fill(p, -2, G, z1, 2, G + 4, z1, "air");
        for (int sx : new int[]{-3, 3}) { fill(p, sx, G, z1, sx, G + 5, z1, "quartz_pillar[axis=y]"); set(p, sx, G + 6, z1, "sea_lantern"); set(p, sx, G, z1 + 1, "sea_lantern"); }
        fill(p, -2, G + 5, z1, 2, G + 5, z1, "chiseled_quartz_block");
        for (int dx = -2; dx <= 2; dx++) set(p, dx, HF, z1 + 1, "polished_diorite");
        // THE GREAT TANK: a glass cylinder of green water, the Abyssal Titan's specimen inside
        for (int dx = -TANK_R - 1; dx <= TANK_R + 1; dx++) for (int dz = -TANK_R - 1; dz <= TANK_R + 1; dz++) {
            double r = rr(dx, dz);
            if (r > TANK_R + 0.6) continue;
            int x = TANK_X + dx, z = TANK_Z + dz;
            set(p, x, G, z, r > TANK_R - 0.4 ? "polished_deepslate" : "verdant_froglight");
            for (int y = G + 1; y <= G + 10; y++) set(p, x, y, z, r > TANK_R - 0.4 ? (y % 3 == 0 ? "lime_stained_glass" : "green_stained_glass") : "water");
            set(p, x, G + 11, z, r > TANK_R - 0.4 ? "polished_deepslate" : "sea_lantern");
        }
        for (int[] c : new int[][]{{TANK_R + 1, 0}, {-TANK_R - 1, 0}, {0, TANK_R + 1}, {0, -TANK_R - 1}})
            for (int y = G; y <= G + 11; y++) set(p, TANK_X + c[0], y, TANK_Z + c[1], y % 2 == 0 ? "iron_bars" : "polished_deepslate_wall");
        // specimen jars
        for (int[] j : JARS) jar(p, j[0], j[1]);
        // research benches by the door, consoles on the back wall
        for (int sx : new int[]{-1, 1}) {
            int bx = sx * 10;
            for (int t = -2; t <= 2; t++) { set(p, bx + t, G, -57, "polished_deepslate"); set(p, bx + t, G + 1, -57, "smooth_stone_slab[type=bottom]"); }
            set(p, bx - 1, G + 1, -57, "brewing_stand"); set(p, bx + 1, G + 1, -57, "water_cauldron[level=3]");
            set(p, bx, G + 1, -57, "potted_dead_bush");
            set(p, bx, G, -56, "quartz_stairs[facing=north,half=bottom,shape=straight]");
        }
        for (int x = -6; x <= 6; x++) for (int y = G + 2; y <= G + 6; y++) set(p, x, y, z0 + 1, (x + y) % 3 == 0 ? "light_blue_stained_glass" : y == G + 2 || y == G + 6 || Math.abs(x) == 6 ? "polished_deepslate" : "black_stained_glass");
        for (int x = -5; x <= 5; x++) set(p, x, G, z0 + 2, "polished_deepslate");
        for (int x = -5; x <= 5; x += 2) set(p, x, G + 1, z0 + 2, "sculk_sensor");
        set(p, 4, G, -55, "lectern[facing=south,has_book=true]");
    }

    /** A specimen jar: 5 x 5 outside, water inside it, a glowing base and a lid. */
    private static void jar(Paint p, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2, wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            int x = cx + dx, z = cz + dz;
            set(p, x, G, z, corner ? "polished_deepslate" : wall ? "polished_deepslate" : "verdant_froglight");
            for (int y = JAR_Y0; y <= JAR_Y1; y++) set(p, x, y, z, corner ? "iron_bars" : wall ? "lime_stained_glass" : "water");
            set(p, x, JAR_Y1 + 1, z, corner || wall ? "polished_deepslate" : "sea_lantern");
        }
        set(p, cx, JAR_Y1 + 2, cz, "iron_chain");
        set(p, cx, JAR_Y1 + 3, cz, "iron_chain");
    }

    // ---------------------------------------------------------------------------- the aquarium
    private static void aquarium(Paint p) {
        int x0 = AQ_X0, x1 = AQ_X1, z0 = AQ_Z0, z1 = AQ_Z1, top = G + 11;
        fill(p, x0, G, z0, x1, top + 3, z1, "air");
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) { set(p, x, HF, z, (x + z) % 2 == 0 ? "dark_prismarine" : "prismarine_bricks"); set(p, x, HF - 1, z, "stone"); }
        for (int y = G; y <= top; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
            if (!ex && !ez) continue;
            int along = ez ? x : z;
            String m = ex && ez ? "dark_prismarine" : Math.floorMod(along, 6) == 0 ? "dark_prismarine" : y >= G + 9 && y <= G + 10 ? "light_blue_stained_glass" : y == top ? "sea_lantern" : y <= G + 1 ? "dark_prismarine" : "prismarine_bricks";
            set(p, x, y, z, m);
        }
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            set(p, x, top + 1, z, edge ? "dark_prismarine_slab[type=bottom]" : Math.floorMod(x, 4) == 0 || Math.floorMod(z, 4) == 0 ? "prismarine_bricks" : "light_blue_stained_glass");
            if (!edge && Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 3) set(p, x, top, z, "sea_lantern");
        }
        // pillars between the wall tanks
        for (int px : new int[]{62, 71, 80}) for (int y = G; y <= top; y++) for (int z : new int[]{-21, -20, -19, -18, -17, -16, -15, 15, 16, 17, 18, 19, 20, 21}) set(p, px, y, z, "dark_prismarine");
        for (int y = G; y <= G + 9; y++) for (int z = -5; z <= 5; z++) set(p, 70, y, z, "dark_prismarine");
        // the tanks: a sand floor lit from below, sea water, glass in front and on top
        for (int[] t : TANKS) {
            int tx0 = t[0], ty0 = t[1], tz0 = t[2], tx1 = t[3], ty1 = t[4], tz1 = t[5];
            for (int x = tx0 - 1; x <= tx1 + 1; x++) for (int z = tz0 - 1; z <= tz1 + 1; z++) {
                boolean in = x >= tx0 && x <= tx1 && z >= tz0 && z <= tz1;
                if (!in) {
                    if (x < x0 + 1 || x > x1 - 1 || z < z0 + 1 || z > z1 - 1) continue;          // the hall's own wall
                    String cur = p.get(HX + x, G + 3, HZ + z).name();
                    if (cur.contains("DARK_PRISMARINE")) continue;                                   // a pillar
                    for (int y = ty0; y <= ty1 + 1; y++) set(p, x, y, z, y == ty0 ? "dark_prismarine" : "glass");
                    continue;
                }
                set(p, x, HF, z, Math.floorMod(x * 7 + z * 3, 9) == 0 ? "sea_lantern" : u(x, HF, z, 5) < 0.3 ? "gravel" : "sand");
                for (int y = ty0; y <= ty1; y++) set(p, x, y, z, "water");
                set(p, x, ty1 + 1, z, "glass");
                double q = u(x, G, z, 6);
                if (q < 0.14) set(p, x, ty0, z, "seagrass");
                else if (q < 0.18) { set(p, x, ty0, z, "tall_seagrass[half=lower]"); set(p, x, ty0 + 1, z, "tall_seagrass[half=upper]"); }
                else if (q < 0.21) set(p, x, ty0, z, new String[]{"tube_coral", "brain_coral", "bubble_coral", "fire_coral", "horn_coral"}[Math.floorMod(x + z, 5)]);
                else if (q < 0.235) set(p, x, ty0, z, "sea_pickle[pickles=3,waterlogged=true]");
            }
        }
        // the entrance (west face), benches down the walkways
        fill(p, x0, G, -2, x0, G + 4, 2, "air");
        for (int dz = -2; dz <= 2; dz++) set(p, x0 - 1, HF, dz, "dark_prismarine");
        for (int sz : new int[]{-3, 3}) { fill(p, x0, G, sz, x0, G + 5, sz, "dark_prismarine"); set(p, x0, G + 6, sz, "sea_lantern"); }
        for (int x = 64; x <= 78; x += 7) for (int sz : new int[]{-8, 8}) for (int t = 0; t <= 1; t++)
            set(p, x + t, G, sz, "dark_oak_stairs[facing=" + (sz < 0 ? "north" : "south") + ",half=bottom,shape=straight]");
        set(p, aquaLecternRel(0)[0], G, aquaLecternRel(0)[1], "lectern[facing=west,has_book=true]");
        set(p, aquaLecternRel(1)[0], G, aquaLecternRel(1)[1], "lectern[facing=west,has_book=true]");
    }

    static int[] aquaLecternRel(int i) { return new int[]{56, i == 0 ? 4 : -4}; }

    // ---------------------------------------------------------------------------- the south avenue: tavern, barracks
    private static void hall(Paint p, int x0, int z0, int x1, int z1, int doorX, String wall, String trim, String floorM, String roofM) {
        if (!p.touches(HX + x0 - 2, HZ + z0 - 2, HX + x1 + 2, HZ + z1 + 2)) return;
        int top = G + 6;
        fill(p, x0, G, z0, x1, top + 6, z1, "air");
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(p, x, HF, z, floorM);
        for (int y = G; y <= top; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
            if (!ex && !ez) continue;
            int along = ez ? x : z;
            String m = (ex && ez) || Math.floorMod(along, 4) == 0 ? trim : (y >= G + 2 && y <= G + 3) ? "glass" : wall;
            if (y == top) m = trim;
            set(p, x, y, z, m);
        }
        // hip roof
        for (int k = 0; k <= Math.min(x1 - x0, z1 - z0) / 2; k++) {
            int y = top + 1 + k / 2;
            for (int x = x0 - 1 + k; x <= x1 + 1 - k; x++) for (int z = z0 - 1 + k; z <= z1 + 1 - k; z++) {
                boolean e2 = x == x0 - 1 + k || x == x1 + 1 - k || z == z0 - 1 + k || z == z1 + 1 - k;
                if (e2) set(p, x, y, z, roofM);
            }
        }
        // a door opening in the middle of the face at doorX
        int mz = (z0 + z1) / 2;
        fill(p, doorX, G, mz - 1, doorX, G + 3, mz + 1, "air");
        set(p, doorX + (doorX == x0 ? -1 : 1), G + 3, mz, "lantern[hanging=false]");
        for (int x = x0 + 3; x < x1; x += 5) for (int z = z0 + 3; z < z1; z += 5) set(p, x, top - 1, z, "lantern[hanging=true]");
    }

    private static void tavern(Paint p) {
        hall(p, TAV_X0, SA_Z0, TAV_X1, SA_Z1, TAV_X1, "spruce_planks", "stripped_spruce_log[axis=y]", "spruce_planks", "dark_oak_planks");
        if (!p.touches(HX + TAV_X0, HZ + SA_Z0, HX + TAV_X1, HZ + SA_Z1)) return;
        // the bar along the back, tables, a hearth
        for (int z = SA_Z0 + 3; z <= SA_Z1 - 3; z++) { set(p, TAV_X0 + 3, G, z, "barrel[facing=up]"); set(p, TAV_X0 + 4, G, z, "spruce_planks"); set(p, TAV_X0 + 4, G + 1, z, "spruce_slab[type=bottom]"); }
        set(p, TAV_X0 + 1, G, SA_Z0 + 2, "campfire[lit=true,facing=east]"); set(p, TAV_X0 + 1, G, SA_Z0 + 3, "smoker[facing=east,lit=true]");
        for (int[] t : new int[][]{{-18, 58}, {-13, 58}, {-18, 66}, {-13, 66}}) {
            set(p, t[0], G, t[1], "spruce_fence"); set(p, t[0], G + 1, t[1], "spruce_pressure_plate");
            set(p, t[0] - 1, G, t[1], "spruce_stairs[facing=east,half=bottom,shape=straight]");
            set(p, t[0] + 1, G, t[1], "spruce_stairs[facing=west,half=bottom,shape=straight]");
        }
    }

    private static void barracks(Paint p) {
        hall(p, BAR_X0, SA_Z0, BAR_X1, SA_Z1, BAR_X0, "stone_bricks", "polished_blackstone_bricks", "polished_andesite", "deepslate_tiles");
        if (!p.touches(HX + BAR_X0, HZ + SA_Z0, HX + BAR_X1, HZ + SA_Z1)) return;
        // proper beds: foot one block in from the wall, head against it
        for (int i = 0; i < 4; i++) {
            int x = BAR_X0 + 4 + i * 4;
            set(p, x, G, SA_Z0 + 2, "blue_bed[facing=north,part=foot]"); set(p, x, G, SA_Z0 + 1, "blue_bed[facing=north,part=head]");
            set(p, x, G, SA_Z1 - 2, "blue_bed[facing=south,part=foot]"); set(p, x, G, SA_Z1 - 1, "blue_bed[facing=south,part=head]");
        }
        for (int z = SA_Z0 + 5; z <= SA_Z1 - 5; z += 4) { set(p, BAR_X1 - 2, G, z, "hay_block[axis=y]"); set(p, BAR_X1 - 2, G + 1, z, "carved_pumpkin[facing=west]"); }
        set(p, BAR_X1 - 1, G, (SA_Z0 + SA_Z1) / 2, "smithing_table");
        set(p, BAR_X1 - 1, G, (SA_Z0 + SA_Z1) / 2 + 1, "grindstone[face=floor,facing=west]");
    }

    /** The guard barracks' beds: {x, z} relative (the head). */
    static int[][] barrackBeds() {
        int[][] b = new int[8][];
        for (int i = 0; i < 4; i++) { b[i] = new int[]{BAR_X0 + 4 + i * 4, SA_Z0 + 1}; b[i + 4] = new int[]{BAR_X0 + 4 + i * 4, SA_Z1 - 1}; }
        return b;
    }

    private static void dockOffice(Paint p) {
        hall(p, DOCK_X0, DOCK_Z0, DOCK_X1, DOCK_Z1, DOCK_X0, "light_gray_concrete", "waxed_cut_copper", "polished_andesite", "waxed_cut_copper");
        if (!p.touches(HX + DOCK_X0, HZ + DOCK_Z0, HX + DOCK_X1, HZ + DOCK_Z1)) return;
        for (int z = DOCK_Z0 + 2; z <= DOCK_Z1 - 2; z++) set(p, DOCK_X0 + 7, G, z, "waxed_copper_block");
        set(p, DOCK_X1 - 1, G, DOCK_Z0 + 1, "cartography_table");
        set(p, DOCK_X1 - 1, G, DOCK_Z1 - 1, "anvil[facing=north]");
        set(p, DOCK_X1 - 1, G + 2, (DOCK_Z0 + DOCK_Z1) / 2, "sea_lantern");
    }

    // ---------------------------------------------------------------------------- the farms (SW)
    /** A farm cell: what grows there (row pattern: lit path, crop, water, crop). */
    static boolean farmCell(int dx, int dz) {
        return dx >= FARM_X0 + 2 && dx <= FARM_X1 - 2 && dz >= FARM_Z0 + 2 && dz <= FARM_Z1 - 2 && headroom(dx, dz) > 8 && !diag(dx, dz)
                && e(HX + dx + 0.5, HF + 0.5, HZ + dz + 0.5) < 0.95;
    }

    static int farmRow(int dz) { return Math.floorMod(dz - FARM_Z0, 4); }

    static String crop(int dx, int dz) {
        int band = Math.floorMod((dx - FARM_X0) / 10, 3);
        return band == 0 ? "wheat" : band == 1 ? "carrots" : "potatoes";
    }

    private static void farms(Paint p) {
        if (!p.touches(HX + FARM_X0, HZ + FARM_Z0, HX + FARM_X1, HZ + FARM_Z1)) return;
        for (int dx = FARM_X0; dx <= FARM_X1; dx++) for (int dz = FARM_Z0; dz <= FARM_Z1; dz++) {
            if (!p.in(HX + dx, HF, HZ + dz) || !farmCell(dx, dz)) continue;
            int row = farmRow(dz);
            set(p, dx, G, dz, "air");
            if (row == 0) set(p, dx, HF, dz, "pearlescent_froglight");              // a glowing path (light for the crops)
            else if (row == 2) set(p, dx, HF, dz, "water");
            else {
                set(p, dx, HF, dz, "farmland[moisture=7]");
                int age = (int) (u(dx, G, dz, 8) * 8);
                String c = crop(dx, dz);
                set(p, dx, G, dz, c + "[age=" + Math.min(7, age) + "]");
            }
        }
        for (int[] h : new int[][]{{-50, 37}, {-51, 37}, {-50, 38}}) { set(p, h[0], G, h[1], "hay_block[axis=y]"); }
        set(p, -52, G, 37, "composter"); set(p, -53, G, 37, "barrel[facing=up]");
    }

    // ---------------------------------------------------------------------------- the grove (SE)
    static final List<int[]> TREES = new ArrayList<>();

    static {
        for (int gx = GROVE_X0 + 4; gx <= GROVE_X1 - 4; gx += 7) for (int gz = GROVE_Z0 + 4; gz <= GROVE_Z1 - 4; gz += 7) {
            int x = gx + (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 1)) * 3) - 1, z = gz + (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 2)) * 3) - 1;
            if (headroom(x, z) < 14 || diag(x, z) || diag(x + 2, z) || diag(x, z + 2) || diag(x - 2, z) || diag(x, z - 2)) continue;
            TREES.add(new int[]{x, z, (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 3)) * 3)});
        }
    }

    private static void grove(Paint p) {
        if (!p.touches(HX + GROVE_X0 - 4, HZ + GROVE_Z0 - 4, HX + GROVE_X1 + 4, HZ + GROVE_Z1 + 4)) return;
        for (int[] t : TREES) {
            String log = t[2] == 0 ? "oak_log" : t[2] == 1 ? "birch_log" : "spruce_log", leaf = t[2] == 0 ? "oak_leaves" : t[2] == 1 ? "birch_leaves" : "spruce_leaves";
            int h = 5 + Math.floorMod(t[0] + t[1], 3);
            for (int y = G; y < G + h; y++) set(p, t[0], y, t[1], log + "[axis=y]");
            int cy = G + h - 1;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 2; dy++) {
                double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.6);
                if (d > 2.9 || dx == 0 && dz == 0 && dy <= 0) continue;
                if (t[2] == 2 && Math.hypot(dx, dz) > 2.6 - dy * 0.6) continue;
                set(p, t[0] + dx, cy + dy, t[1] + dz, leaf + "[persistent=true]");
            }
            set(p, t[0], cy + 1, t[1], leaf + "[persistent=true]");
            set(p, t[0] + 1, HF, t[1] + 1, "sea_lantern"); // a lamp in the roots: light for the grove
        }
        // the woodcutters' corner: a chopping block, a log pile
        set(p, 41, G, 31, "stripped_oak_log[axis=y]");
        for (int x = 44; x <= 47; x++) for (int y = G; y <= G + 1; y++) set(p, x, y, 35, "oak_log[axis=x]");
    }

    // ---------------------------------------------------------------------------- the building site (NE)
    private static void site(Paint p) {
        if (!p.touches(HX + SITE_X0 - 2, HZ + SITE_Z0 - 2, HX + SITE_X1 + 2, HZ + SITE_Z1 + 2)) return;
        for (int x = SITE_X0; x <= SITE_X1; x++) for (int z = SITE_Z0; z <= SITE_Z1; z++) { set(p, x, HF, z, "gravel"); for (int y = G; y <= G + 8; y++) set(p, x, y, z, "air"); }
        for (int x = SITE_X0 + 1; x <= SITE_X1 - 1; x++) for (int z = SITE_Z0 + 1; z <= SITE_Z1 - 1; z++) set(p, x, HF, z, "smooth_stone");
        // scaffolding at the corners, piles of bricks and planks
        for (int[] c : new int[][]{{SITE_X0, SITE_Z0}, {SITE_X1, SITE_Z0}, {SITE_X0, SITE_Z1}, {SITE_X1, SITE_Z1}})
            for (int y = G; y <= G + 6; y++) set(p, c[0], y, c[1], "scaffolding[bottom=false,distance=0]");
        set(p, SITE_X0 - 1, G, SITE_Z1 + 1, "bricks"); set(p, SITE_X0 - 2, G, SITE_Z1 + 1, "bricks"); set(p, SITE_X0 - 1, G + 1, SITE_Z1 + 1, "brick_slab[type=bottom]");
        set(p, SITE_X1 + 1, G, SITE_Z1 + 1, "spruce_planks"); set(p, SITE_X1 + 1, G + 1, SITE_Z1 + 1, "spruce_slab[type=bottom]");
        set(p, SITE_X0 - 1, G, SITE_Z1 + 2, "crafting_table");
    }

    /** What the builders build, block by block (Citizens): a little brick house on the site. {rel x, y, rel z, block}. */
    static final List<Object[]> PLAN = new ArrayList<>();

    static {
        int x0 = SITE_X0 + 2, x1 = SITE_X1 - 2, z0 = SITE_Z0 + 2, z1 = SITE_Z1 - 2;
        for (int y = G; y <= G + 4; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
            if (!ex && !ez) continue;
            boolean door = z == z1 && x == (x0 + x1) / 2 && y <= G + 1;
            if (door) continue;
            boolean win = y == G + 2 && !(ex && ez) && Math.floorMod(ez ? x : z, 3) == 0;
            PLAN.add(new Object[]{x, y, z, ex && ez ? "spruce_log[axis=y]" : y == G + 4 ? "spruce_planks" : win ? "glass" : "bricks"});
        }
        for (int k = 0; k <= (z1 - z0) / 2 + 1; k++) for (int x = x0 - 1; x <= x1 + 1; x++) {
            int za = z0 - 1 + k, zb = z1 + 1 - k, y = G + 5 + k;
            if (za < zb) { PLAN.add(new Object[]{x, y, za, "brick_stairs[facing=south,half=bottom,shape=straight]"}); PLAN.add(new Object[]{x, y, zb, "brick_stairs[facing=north,half=bottom,shape=straight]"}); }
            else if (za == zb) PLAN.add(new Object[]{x, y, za, "bricks"});
        }
    }

    // ---------------------------------------------------------------------------- the coal mine (NW, under the floor)
    /** Solid rock round the whole mine first (the crust's caves and the sea must never reach its tunnels). */
    private static void mineRock(Paint p) {
        if (!p.touches(HX + MINE_X0, HZ + MINE_Z0, HX + MINE_X1, HZ + MINE_Z1)) return;
        for (int x = MINE_X0; x <= MINE_X1; x++) for (int z = MINE_Z0; z <= MINE_Z1; z++)
            for (int y = HF - 24; y <= HF - 1; y++) set(p, x, y, z, u(x, y, z, 9) < 0.18 ? "tuff" : y < HF - 12 ? "deepslate" : "stone");
    }

    private static void mine(Paint p) {
        if (!p.touches(HX + MINE_X0, HZ + MINE_Z0, HX + MINE_X1, HZ + MINE_Z1)) return;
        // the headframe over the shaft
        for (int[] c : new int[][]{{HEAD_X0, HEAD_Z0}, {HEAD_X1, HEAD_Z0}, {HEAD_X0, HEAD_Z1}, {HEAD_X1, HEAD_Z1}})
            for (int y = G; y <= G + 9; y++) set(p, c[0], y, c[1], "dark_oak_log[axis=y]");
        for (int x = HEAD_X0; x <= HEAD_X1; x++) { set(p, x, G + 10, HEAD_Z0, "dark_oak_log[axis=x]"); set(p, x, G + 10, HEAD_Z1, "dark_oak_log[axis=x]"); }
        for (int z = HEAD_Z0; z <= HEAD_Z1; z++) { set(p, HEAD_X0, G + 10, z, "dark_oak_log[axis=z]"); set(p, HEAD_X1, G + 10, z, "dark_oak_log[axis=z]"); }
        for (int x = HEAD_X0 + 2; x <= HEAD_X1 - 2; x += 3) set(p, x, G + 10, (HEAD_Z0 + HEAD_Z1) / 2, "dark_oak_planks");
        set(p, -55, G + 9, -55, "iron_chain"); set(p, -55, G + 8, -55, "iron_chain"); set(p, -55, G + 7, -55, "lantern[hanging=true]");
        // the stair tunnel down, west
        for (int i = 0; i <= DEPTH; i++) {
            int x = TUNNEL_X_TOP - i, fy = HF - i;
            for (int z = TUNNEL_Z0 - 1; z <= TUNNEL_Z1 + 1; z++) {
                boolean wall = z == TUNNEL_Z0 - 1 || z == TUNNEL_Z1 + 1;
                set(p, x, fy, z, wall ? "stripped_dark_oak_log[axis=y]" : "polished_andesite");
                for (int y = fy + 1; y <= fy + 4; y++) {
                    if (wall) { if (y <= HF) set(p, x, y, z, i % 4 == 0 ? "dark_oak_log[axis=y]" : "stone"); continue; }
                    set(p, x, y, z, y == fy + 4 && i % 4 == 0 ? "dark_oak_planks" : "air");
                }
                if (fy + 5 <= HF && !wall) set(p, x, fy + 5, z, "stone");
            }
            if (i % 4 == 2) set(p, x, fy + 4, (TUNNEL_Z0 + TUNNEL_Z1) / 2, "lantern[hanging=true]"); // up in the roof, over their heads
        }
        // the coal gallery
        int gy = HF - DEPTH;
        for (int x = GAL_X0; x <= GAL_X1; x++) for (int z = GAL_Z0; z <= GAL_Z1; z++) {
            boolean wall = x == GAL_X0 || x == GAL_X1 || z == GAL_Z0 || z == GAL_Z1;
            set(p, x, gy, z, wall ? "deepslate" : "polished_andesite");
            for (int y = gy + 1; y <= gy + 5; y++) {
                if (wall) { set(p, x, y, z, u(x, y, z, 10) < 0.45 ? "coal_ore" : u(x, y, z, 11) < 0.1 ? "iron_ore" : "stone"); continue; }
                boolean pillar = Math.floorMod(x - GAL_X0, 6) == 3 && Math.floorMod(z - GAL_Z0, 6) == 3 && x < GAL_X1 - 3; // (none by the tunnel's mouth)
                set(p, x, y, z, pillar ? (u(x, y, z, 12) < 0.5 ? "coal_ore" : "dark_oak_log[axis=y]") : y == gy + 5 && Math.floorMod(x - GAL_X0, 4) == 0 ? "dark_oak_planks" : "air");
            }
            set(p, x, gy + 6, z, "stone");
        }
        for (int x = GAL_X0 + 1; x < GAL_X1; x++) set(p, x, gy + 1, (GAL_Z0 + GAL_Z1) / 2, "rail[shape=east_west]");
        for (int x = GAL_X0 + 2; x < GAL_X1; x += 5) for (int z : new int[]{GAL_Z0 + 2, GAL_Z1 - 2}) set(p, x, gy + 4, z, "lantern[hanging=true]");
        // the tunnel's mouth into the gallery
        for (int z = TUNNEL_Z0; z <= TUNNEL_Z1; z++) for (int y = gy + 1; y <= gy + 3; y++) set(p, GAL_X1, y, z, "air");
    }

    /** The coal faces miners work at: {rel x, y, rel z} (the ore block), and where they stand. */
    static int[][] coalFaces() {
        int gy = HF - DEPTH;
        return new int[][]{{GAL_X0, gy + 2, GAL_Z0 + 4, GAL_X0 + 1, gy + 1, GAL_Z0 + 4}, {GAL_X0, gy + 2, GAL_Z1 - 4, GAL_X0 + 1, gy + 1, GAL_Z1 - 4},
                {GAL_X0 + 5, gy + 2, GAL_Z0, GAL_X0 + 5, gy + 1, GAL_Z0 + 1}, {GAL_X0 + 10, gy + 2, GAL_Z1, GAL_X0 + 10, gy + 1, GAL_Z1 - 1}};
    }

    // ---------------------------------------------------------------------------- the market (west avenue)
    static final String[] STALL_COLORS = {"red", "yellow", "blue", "green", "orange", "purple"};

    static int[][] stalls() { // {rel x, rel z (the counter's middle), side}
        return new int[][]{{-62, -6, -1}, {-72, -6, -1}, {-82, -6, -1}, {-62, 6, 1}, {-72, 6, 1}, {-82, 6, 1}};
    }

    private static void market(Paint p) {
        if (!p.touches(HX + MARKET_X0, HZ - 12, HX + MARKET_X1, HZ + 12)) return;
        int i = 0;
        for (int[] s : stalls()) {
            String c = STALL_COLORS[i++ % STALL_COLORS.length];
            int sx = s[0], sz = s[1], side = s[2];
            for (int dx = -2; dx <= 2; dx++) {
                set(p, sx + dx, G, sz, dx == 0 ? "barrel[facing=up]" : "spruce_planks");
                set(p, sx + dx, G + 1, sz, dx == -1 ? "melon" : dx == 1 ? "pumpkin" : "air");
                for (int dz = 0; dz <= 2; dz++) set(p, sx + dx, G + 4, sz + side * dz, ((dx + dz) % 2 == 0 ? c : "white") + "_wool");
            }
            for (int[] post : new int[][]{{-2, 0}, {2, 0}, {-2, 2}, {2, 2}}) for (int y = G + 1; y <= G + 3; y++) set(p, sx + post[0], y, sz + side * post[1], "spruce_fence");
        }
    }

    // ---------------------------------------------------------------------------- lamps
    static final List<int[]> LAMPS = new ArrayList<>();

    static {
        for (int k = 0; k < 24; k++) {
            double a = Math.toRadians(k * 15 + 7.5);
            for (double r : new double[]{RING_IN - 1.5, RING_OUT + 1.5}) {
                int x = (int) Math.round(Math.cos(a) * r), z = (int) Math.round(Math.sin(a) * r);
                if (!street(x, z) && !blocked(x, z)) LAMPS.add(new int[]{x, z});
            }
        }
        for (int d = 40; d <= 100; d += 8) for (int s : new int[]{-4, 4}) {
            LAMPS.add(new int[]{s, d}); LAMPS.add(new int[]{-d, s});
        }
        LAMPS.removeIf(l -> headroom(l[0], l[1]) < 7 || blocked(l[0], l[1]) || street(l[0], l[1]));
    }

    static boolean blocked(int dx, int dz) {
        for (House h : HOUSES) if (HX + dx >= h.x0 - 1 && HX + dx <= h.x1 + 1 && HZ + dz >= h.z0 - 3 && HZ + dz <= h.z1 + 1) return true;
        if (dx >= TAV_X0 - 1 && dx <= TAV_X1 + 1 && dz >= SA_Z0 - 1 && dz <= SA_Z1 + 1) return true;
        if (dx >= BAR_X0 - 1 && dx <= BAR_X1 + 1 && dz >= SA_Z0 - 1 && dz <= SA_Z1 + 1) return true;
        if (dx >= DOCK_X0 - 1 && dx <= DOCK_X1 + 1 && dz >= DOCK_Z0 - 1 && dz <= DOCK_Z1 + 1) return true;
        if (dx >= MARKET_X0 && dx <= MARKET_X1 && Math.abs(dz) <= 9) return true;
        return dx >= FARM_X0 && dx <= FARM_X1 && dz >= FARM_Z0 && dz <= FARM_Z1;
    }

    private static void lamps(Paint p) {
        for (int[] l : LAMPS) {
            if (!p.in(HX + l[0], G, HZ + l[1])) continue;
            set(p, l[0], G, l[1], "polished_deepslate_wall");
            set(p, l[0], G + 1, l[1], "iron_bars"); set(p, l[0], G + 2, l[1], "iron_bars");
            set(p, l[0], G + 3, l[1], "sea_lantern");
            set(p, l[0], G + 4, l[1], "waxed_cut_copper_slab[type=bottom]");
        }
    }

    // ---------------------------------------------------------------------------- the gates and the dock
    /** {axis (0 = x, 1 = z), sign}: the west gate and the south (dock) gate. */
    static final int[][] GATES = {{0, -1}, {1, 1}};

    static int gateDoor(int[] gt) {
        int k = 0;
        while (k < 120) {
            int nx = gt[0] == 0 ? HX + gt[1] * (k + 1) : HX, nz = gt[0] == 0 ? HZ : HZ + gt[1] * (k + 1);
            if (e(nx, G, nz) > 1) break;
            k++;
        }
        return k + 2;
    }

    private static void gates(Paint p) {
        for (int[] gt : GATES) {
            int axis = gt[0], sign = gt[1], door = gateDoor(gt);
            for (int s = door - 9; s <= door + 3; s++) for (int q = -3; q <= 3; q++) {
                int dx = axis == 0 ? sign * s : q, dz = axis == 0 ? q : sign * s;
                if (!p.in(HX + dx, G, HZ + dz)) continue;
                boolean passage = Math.abs(q) <= 1;
                if (s > door) { set(p, dx, HF, dz, passage ? (s % 2 == 0 ? "sea_lantern" : "polished_deepslate") : "dark_prismarine"); continue; }
                for (int y = HF; y <= G + 5; y++) {
                    String m;
                    if (y == HF) m = passage ? "polished_deepslate" : "dark_prismarine";
                    else if (passage && s < door && y <= G + 2) m = "air";
                    else if (passage && s == door && y <= G + 1)
                        m = "dark_oak_door[facing=" + inward(axis, sign) + ",half=" + (y == G ? "lower" : "upper") + ",hinge=" + (q > 0 ? "right" : "left") + ",open=false]";
                    else if (s == door && Math.abs(q) == 3) m = "sea_lantern";
                    else if (s == door && q == 0 && y == G + 3) m = "sea_lantern";
                    else m = y == G + 5 ? "dark_prismarine_slab[type=bottom]" : "prismarine_bricks";
                    set(p, dx, y, dz, m);
                }
            }
        }
    }

    static String inward(int axis, int sign) { return axis == 0 ? (sign > 0 ? "west" : "east") : (sign > 0 ? "north" : "south"); }

    /** The patrol subs' berths outside the south gate: {rel x, y, rel z}. */
    static int[][] berths() { return new int[][]{{-8, G + 3, 114}, {0, G + 3, 118}, {8, G + 3, 114}, {0, G + 3, 108}}; }

    private static void dock(Paint p) {
        if (!p.touches(HX - 16, HZ + 100, HX + 16, HZ + 128)) return;
        for (int dx = -14; dx <= 14; dx++) for (int dz = 104; dz <= 126; dz++) {
            double r = Math.hypot(dx, (dz - 115) * 1.2);
            if (r > 14.5) continue;
            set(p, dx, HF, dz, r > 13.5 ? "sea_lantern" : Math.floorMod(dx + dz, 4) == 0 ? "dark_prismarine" : "polished_deepslate");
        }
        for (int[] b : berths()) {
            for (int[] o : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
                set(p, b[0] + o[0], G, b[2] + o[1], "polished_deepslate_wall[waterlogged=true]");
                set(p, b[0] + o[0], G + 1, b[2] + o[1], "iron_bars[waterlogged=true]");
                set(p, b[0] + o[0], G + 2, b[2] + o[1], "sea_lantern");
            }
        }
    }
}
