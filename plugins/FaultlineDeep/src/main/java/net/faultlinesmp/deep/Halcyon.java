package net.faultlinesmp.deep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HALCYON (Deep 1.17.0, rebuilt in 1.18.0): a living city under glass far out in the Dark Ocean's south-west, round HX, HZ
 * (floor top y HF). Pure functions like Town / the regions: every chunk it touches draws the whole of it and keeps its own.
 *  - THREE DOMES joined by glass HALLWAYS: the MAIN dome (radius 100, 72 high), the AGRI dome to the south-west (farms, the
 *    orchard the woodcutters fell, the barn and its bunks, a silo, a windmill, beehives) and the GUARD dome to the south-east
 *    (barracks, armory, training yard, archery range, watchtower). Every shape is one envelope: a block is air only when all
 *    six neighbours are inside the envelope too, so the sea never touches the dry air (`air()`).
 *  - THE MAIN DOME: the plaza (fountain, diver statue, bubble tubes, the meeting BELL), the ring road, avenues, diagonal paths,
 *    16 houses, the Bio-Center (N), the AQUARIUM (E: one tank per creature, a main floor and a BASEMENT), the Kelp & Kettle,
 *    the Town Hall, the dock office, the market, the Park with its pond, the School + playground, the Clinic, the Greenhouse,
 *    the miners' bunkhouse, the building site, gardens everywhere else (trees, flower beds, benches), and the MINE: a pithead
 *    and headframe over a timbered stair tunnel into a coal gallery and a DIVE ROOM whose moon pool drops into a flooded
 *    tunnel that runs out to the crust's real caves (`diveTunnel`, worked out per seed), where the divers mine.
 *  - outside the south gate: the DOCK, where the patrol subs berth.
 */
final class Halcyon {
    static final int HX = -1800, HZ = 2200, HF = 70, G = HF + 1;
    static final double A = 100, B = 72, SHELL = 1.6;
    static final double R = 232, BLEND = 60;

    private Halcyon() { }

    // =========================================================================================== the envelope
    /** A dome (relative centre, radius across, height). */
    record Dome(double cx, double cz, double a, double b) {
        double e(double x, double y, double z) { double dx = (x - cx) / a, dy = (y - HF) / b, dz = (z - cz) / a; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
        double in() { return 1 - SHELL / a; }
    }

    /** A glass hallway between two relative points (floor at HF). */
    record Hall(double x0, double z0, double x1, double z1) {
        /** {along 0..1, sideways distance}. */
        double[] at(double x, double z) {
            double dx = x1 - x0, dz = z1 - z0, len2 = dx * dx + dz * dz;
            double t = Math.max(0, Math.min(1, ((x - x0) * dx + (z - z0) * dz) / len2));
            double px = x0 + dx * t, pz = z0 + dz * t;
            return new double[]{t, Math.hypot(x - px, z - pz), t * Math.sqrt(len2)};
        }
        double inner(double x, double y, double z) { double[] a = at(x, z); double h = y - HF; return (a[1] / 2.7) * (a[1] / 2.7) + (h / 4.4) * (h / 4.4); }
        double outer(double x, double y, double z) { double[] a = at(x, z); double h = y - HF; return (a[1] / 3.8) * (a[1] / 3.8) + (h / 5.5) * (h / 5.5); }
    }

    static final Dome MAIN = new Dome(0, 0, A, B), AGRI = new Dome(-134, 117, 48, 32), GUARD = new Dome(128, 112, 42, 28);
    static final Dome[] DOMES = {MAIN, AGRI, GUARD};
    static final Hall HALL_AGRI = new Hall(-68, 59, -110, 96), HALL_GUARD = new Hall(68, 59, 104, 91);
    static final Hall[] HALLS = {HALL_AGRI, HALL_GUARD};

    /** Relative coordinates: inside any dome's or hallway's dry air (y >= G). */
    static boolean innerRel(double x, double y, double z) {
        if (y < G - 0.5) return false;
        for (Dome d : DOMES) if (d.e(x, y, z) < d.in()) return true;
        for (Hall h : HALLS) if (h.inner(x, y, z) < 1) return true;
        return false;
    }

    /** Relative: inside the envelope (air or its glass). */
    static boolean outerRel(double x, double y, double z) {
        for (Dome d : DOMES) if (d.e(x, y, z) <= 1) return true;
        for (Hall h : HALLS) if (h.outer(x, y, z) <= 1) return true;
        return false;
    }

    /** Relative: is this block (its centre) dry air? */
    static boolean air(int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (!innerRel(cx, cy, cz)) return false;
        return outerRel(cx + 1, cy, cz) && outerRel(cx - 1, cy, cz) && outerRel(cx, cy + 1, cz) && outerRel(cx, cy - 1, cz) && outerRel(cx, cy, cz + 1) && outerRel(cx, cy, cz - 1);
    }

    static double weight(int x, int z) { return Regions.weight(x, z, HX, HZ, R, BLEND, 6); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, HX, HZ, R + BLEND + 40 + extra); }

    static double floor() { return HF; }

    /** The MAIN dome's normalised distance (absolute coordinates): < 1 inside. */
    static double e(double x, double y, double z) { return MAIN.e(x - HX, y, z - HZ); }

    /** Absolute: in the city's dry air (any dome or hallway, the mine, the aquarium's basement). */
    static boolean inside(double x, double y, double z) {
        double rx = x - HX, rz = z - HZ;
        if (y > HF - 0.5 && innerRel(rx, y, rz)) return true;
        if (rx >= MINE_X0 && rx <= MINE_X1 + 1 && rz >= MINE_Z0 && rz <= MINE_Z1 + 1 && y >= HF - 26 && y < HF + 1) return true;
        return rx >= BASE_X0 && rx <= BASE_X1 + 1 && rz >= BASE_Z0 && rz <= BASE_Z1 + 1 && y >= HF - 11 && y < HF + 1;
    }

    /** Anywhere the guards care about: the envelope (and a little round it), the mine, the basement, the dock. */
    static boolean grounds(double x, double y, double z) {
        if (inside(x, y, z)) return true;
        double rx = x - HX, rz = z - HZ, yy = Math.max(y, HF);
        if (y > HF - 25) {
            for (Dome d : DOMES) if (d.e(rx, yy, rz) < 1.06) return true;
            for (Hall h : HALLS) if (h.outer(rx, yy, rz) < 1.3) return true;
        }
        return Math.abs(rx) <= 14 && rz >= 96 && rz <= 126 && y > HF - 3 && y < HF + 12; // the dock
    }

    /** Absolute: within any dome or hallway, stretched by `m` (0.08 = 8% bigger): the subs and dome kits keep out of this. */
    static boolean envelope(double x, double y, double z, double m) {
        double rx = x - HX, rz = z - HZ, yy = Math.max(y, HF);
        for (Dome d : DOMES) if (d.e(rx, yy, rz) < 1 + m) return true;
        for (Hall hl : HALLS) if (hl.outer(rx, yy, rz) < (1 + m) * (1 + m)) return true;
        return false;
    }

    /** Absolute: a block of the shell (glass, ribs, the hallways' skin). */
    static boolean shellBlock(int x, int y, int z) {
        int rx = x - HX, rz = z - HZ;
        return y >= G && outerRel(rx + 0.5, y + 0.5, rz + 0.5) && !air(rx, y, rz);
    }

    /** The main dome's inner height over the ground at a column (relative). */
    static double headroom(double dx, double dz) {
        double h = 1 - (dx * dx + dz * dz) / (A * A);
        return h <= 0 ? 0 : B * Math.sqrt(h) - 3;
    }

    static double headroom(Dome d, double dx, double dz) {
        double x = (dx - d.cx()) / d.a(), z = (dz - d.cz()) / d.a(), h = 1 - x * x - z * z;
        return h <= 0 ? 0 : d.b() * Math.sqrt(h) - 3;
    }


    // =========================================================================================== layout (relative to HX, HZ)
    static final int RING_IN = 30, RING_OUT = 35, PLAZA_R = 26;
    static final double RING_R = 32.5;
    static final int LAB_X0 = -22, LAB_X1 = 22, LAB_Z0 = -88, LAB_Z1 = -52;
    static final int AQ_X0 = 52, AQ_X1 = 88, AQ_Z0 = -22, AQ_Z1 = 22;
    static final int BASE_X0 = 53, BASE_X1 = 95, BASE_Z0 = -21, BASE_Z1 = 21, BASE_FLOOR = HF - 7; // the aquarium's basement
    static final int TAV_X0 = -26, TAV_X1 = -8, TH_X0 = 8, TH_X1 = 26, SA_Z0 = 54, SA_Z1 = 72;
    static final int DOCK_X0 = 6, DOCK_X1 = 16, DOCK_Z0 = 80, DOCK_Z1 = 90;
    static final int SITE_X0 = 38, SITE_X1 = 52, SITE_Z0 = -66, SITE_Z1 = -54;
    static final int MINE_X0 = -100, MINE_X1 = -46, MINE_Z0 = -72, MINE_Z1 = -40;
    static final int TUNNEL_Z0 = -56, TUNNEL_Z1 = -54, TUNNEL_X_TOP = -52, DEPTH = 16, GY = HF - DEPTH; // the gallery's floor
    static final int GAL_X0 = -86, GAL_X1 = -68, GAL_Z0 = -66, GAL_Z1 = -46;
    static final int DIVE_X0 = -99, DIVE_X1 = -87, DIVE_Z0 = -62, DIVE_Z1 = -50, POOL_X = -94, POOL_Z = -56, POOL_BOTTOM = HF - 24;
    static final int MARKET_X0 = -88, MARKET_X1 = -56;
    static final int PARK_X = -74, PARK_Z = 30, POND_R = 6;
    static final int SCH_X0 = -52, SCH_X1 = -36, SCH_Z0 = 66, SCH_Z1 = 80;
    static final int CLI_X0 = 30, CLI_X1 = 46, CLI_Z0 = 60, CLI_Z1 = 76;
    static final int GH_X0 = 56, GH_X1 = 80, GH_Z0 = 28, GH_Z1 = 44;
    static final int BUNK_X0 = -44, BUNK_X1 = -32, BUNK_Z0 = -68, BUNK_Z1 = -58;   // the miners' bunkhouse
    static final int[] BELL = {0, -13};

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
        double angle() { return HOUSE_ANGLES[i]; }
    }

    static int wx(int ox, int r, int lx, int lz) { return switch (r) { case 1 -> ox - lz; case 2 -> ox - lx; case 3 -> ox + lz; default -> ox + lx; }; }

    static int wz(int oz, int r, int lx, int lz) { return switch (r) { case 1 -> oz + lx; case 2 -> oz - lz; case 3 -> oz - lx; default -> oz + lz; }; }

    static final List<House> HOUSES = new ArrayList<>();

    static {
        for (int i = 0; i < HOUSE_ANGLES.length; i++) {
            double a = Math.toRadians(HOUSE_ANGLES[i]);
            int cx = HX + (int) Math.round(Math.cos(a) * HOUSE_R), cz = HZ + (int) Math.round(Math.sin(a) * HOUSE_R);
            double c = Math.cos(a), s = Math.sin(a);
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
    static final int JAR_Y0 = G + 1, JAR_Y1 = G + 5;

    // =========================================================================================== the aquarium's tanks
    /**
     * One creature's tank: the water box (relative x/z, absolute y, inclusive), the creature that lives in it, and the signs
     * on its front glass ({x, y, z, facing}: facing 0 north, 1 south).
     */
    record Tank(int x0, int y0, int z0, int x1, int y1, int z1, String kind, int[][] signs, boolean basement) {
        double cx() { return (x0 + x1 + 1) / 2.0; }
        double cy() { return (y0 + y1 + 1) / 2.0; }
        double cz() { return (z0 + z1 + 1) / 2.0; }
    }

    /** Water z ranges of the six rows, and their front glass rows (+ which way their signs face). */
    static final int[][] ROWS = { // {water z0, z1, sign z (north side, facing north) or 99, sign z (south side, facing south) or 99}
            {-21, -19, 99, -17}, {-13, -11, -15, -9}, {-5, -3, -7, -1}, {3, 5, 1, 7}, {11, 13, 9, 15}, {19, 21, 17, 99}};
    static final List<Tank> TANKS = new ArrayList<>();

    static {
        List<String> top = new ArrayList<>(), deep = new ArrayList<>();
        for (DeepMobs.Kind k : DeepMobs.KINDS.values()) {
            if (DeepMobs.PLAIN.contains(k.id()) || k.id().equals("goblin") || k.id().equals("rimeback")) continue;
            if (k.zone() == DeepGenerator.Zone.SUNLIT) top.add(k.id()); else deep.add(k.id());
        }
        int ti = 0, di = 0;
        for (int level = 0; level < 2; level++) {
            boolean base = level == 1;
            int n = base ? 9 : 7, y0 = base ? BASE_FLOOR + 1 : G;
            for (int[] row : ROWS) for (int i = 0; i < n; i++) {
                int x0 = 57 + 4 * i;
                String kind = base ? (di < deep.size() ? deep.get(di++) : null) : (ti < top.size() ? top.get(ti++) : null);
                List<int[]> signs = new ArrayList<>();
                if (row[2] != 99) signs.add(new int[]{x0 + 1, y0 + 3, row[2], 0});
                if (row[3] != 99) signs.add(new int[]{x0 + 1, y0 + 3, row[3], 1});
                TANKS.add(new Tank(x0, y0, row[0], x0 + 2, y0 + 2, row[1], kind, signs.toArray(new int[0][]), base));
            }
        }
    }

    // =========================================================================================== where things are (world)
    static int[] w(int dx, int dz) { return new int[]{HX + dx, HZ + dz}; }

    static int[] labLectern() { return w(4, -55); }
    static int[] aquaLecternRel(int i) { return new int[]{53, i == 0 ? -3 : -6}; }
    static int[] dockMaster() { return w(15, 85); }
    static int[] curator() { return w(54, -5); }
    static int[] spawnSpot() { return w(0, 22); }

    // =========================================================================================== painting
    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!p.touches(HX - 200, HZ - 120, HX + 190, HZ + 180)) return;
        mineRock(p);
        basementRock(p);
        envelope(p);
        ground(p);
        plaza(p);
        plazaBell(p);
        for (House h : HOUSES) if (p.touches(h.x0 - 3, h.z0 - 3, h.x1 + 3, h.z1 + 3)) house(p, h);
        if (p.touches(HX + LAB_X0 - 2, HZ + LAB_Z0 - 2, HX + LAB_X1 + 2, HZ + LAB_Z1 + 2)) lab(p);
        if (p.touches(HX + AQ_X0 - 2, HZ + AQ_Z0 - 2, HX + BASE_X1 + 3, HZ + AQ_Z1 + 2)) aquarium(p);
        tavern(p);
        townHall(p);
        dockOffice(p);
        school(p);
        clinic(p);
        greenhouse(p);
        bunkhouse(p);
        park(p);
        site(p);
        mine(p);
        diveTunnel(g, n, p);
        market(p);
        gardens(p);
        lamps(p);
        gates(p);
        dock(p);
        agri(p);
        guard(p);
    }

    static void set(Paint p, int dx, int y, int dz, String s) { p.set(HX + dx, y, HZ + dz, s); }

    static void fill(Paint p, int x0, int y0, int z0, int x1, int y1, int z1, String s) {
        int ax0 = Math.max(Math.min(x0, x1), p.x0 - HX), ax1 = Math.min(Math.max(x0, x1), p.x0 + 15 - HX);
        int az0 = Math.max(Math.min(z0, z1), p.z0 - HZ), az1 = Math.min(Math.max(z0, z1), p.z0 + 15 - HZ);
        for (int x = ax0; x <= ax1; x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = az0; z <= az1; z++) set(p, x, y, z, s);
    }

    static boolean touchesRel(Paint p, int x0, int z0, int x1, int z1) { return p.touches(HX + x0, HZ + z0, HX + x1, HZ + z1); }

    static double u(int dx, int y, int dz, int salt) { return Paint.u(HX + dx, y, HZ + dz, salt); }

    // ---------------------------------------------------------------------------- the envelope: air inside, glass round it
    private static void envelope(Paint p) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx - HX, z = p.z0 + lz - HZ;
            if (!outerRel(x + 0.5, HF + 0.5, z + 0.5)) continue;
            for (int y = G; y <= HF + B + 1; y++) {
                if (!outerRel(x + 0.5, y + 0.5, z + 0.5)) { if (y > G + 6) break; continue; }
                set(p, x, y, z, air(x, y, z) ? "air" : shell(x, y, z));
            }
        }
    }

    private static String shell(int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        for (Dome d : DOMES) {
            double ev = d.e(cx, cy, cz);
            if (ev <= 1 && ev >= d.in() - 0.03) return domeShell(d, x, y, z);
        }
        // a hallway: glass, with a ring of prismarine (a sea lantern at its top) every 5 blocks
        for (Hall h : HALLS) {
            if (h.outer(cx, cy, cz) > 1) continue;
            double[] a = h.at(cx, cz);
            boolean ring = Math.floorMod((int) Math.floor(a[2]), 5) == 0;
            if (y <= G) return "dark_prismarine";
            if (ring) return y >= HF + 5 ? "sea_lantern" : "prismarine_bricks";
            return Paint.u(HX + x, y, HZ + z, 31) < 0.7 ? "light_blue_stained_glass" : "glass";
        }
        return "glass";
    }

    private static String domeShell(Dome d, int x, int y, int z) {
        double dx = x - d.cx(), dz = z - d.cz(), hr = Math.hypot(dx, dz), dy = y - HF;
        if (hr < Math.max(2, d.a() * 0.032)) return "sea_lantern";
        if (y <= G + 2) return y == G + 2 ? "prismarine_bricks" : "dark_prismarine";
        double ang = Math.atan2(dz, dx), seg = d == MAIN ? Math.PI / 12 : Math.PI / 8;
        boolean rib = hr > 6 && Math.abs(Math.IEEEremainder(ang, seg)) * hr < 0.8;
        double step = d.b() / 5.0;
        boolean band = Math.abs(Math.IEEEremainder(dy, step)) < 0.6 && dy > 4;
        if (rib && band) return "sea_lantern";
        if (rib) return Math.floorMod(y, 7) == 0 ? "sea_lantern" : "dark_prismarine";
        if (band) return "prismarine_bricks";
        double r = Paint.u(HX + x, y, HZ + z, 17), top = dy / d.b();
        if (r < 0.05) return "tinted_glass";
        if (r < 0.2 + top * 0.3) return "glass";
        if (r < 0.75) return "light_blue_stained_glass";
        return "cyan_stained_glass";
    }


    // ---------------------------------------------------------------------------- streets and the ground
    static double rr(int dx, int dz) { return Math.hypot(dx, dz); }

    static boolean avenue(int dx, int dz) {
        if (Math.abs(dx) <= 2 && dz <= -PLAZA_R && dz >= LAB_Z1) return true;            // north, to the Bio-Center
        if (Math.abs(dx) <= 2 && dz >= PLAZA_R && dz <= 104) return true;                 // south, to the dock gate
        if (Math.abs(dz) <= 2 && dx >= PLAZA_R && dx <= AQ_X0) return true;              // east, to the Aquarium
        if (Math.abs(dz) <= 2 && dx <= -PLAZA_R && dx >= -104) return true;              // west, past the market to the gate
        if (Math.abs(dz - 75) <= 1 && dx >= -36 && dx <= 2) return true;                 // the school lane
        if (Math.abs(dx - 50) <= 1 && dz >= 2 && dz <= 36 || Math.abs(dz - 36) <= 1 && dx >= 50 && dx <= 55) return true; // the greenhouse lane
        if (Math.abs(dx + 77) <= 1 && dz >= 2 && dz <= 20) return true;                  // the park lane
        if (Math.abs(dz + 55) <= 1 && dx >= -52 && dx <= -46) return true;               // the mine lane (to the pithead)
        if (Math.abs(dx + 47) <= 1 && dz >= -63 && dz <= -44) return true;               //   and on to the bunkhouse
        return Math.abs(dz - 78) <= 1 && dx >= 2 && dx <= 29 || Math.abs(dx - 28) <= 1 && dz >= 67 && dz <= 78; // the clinic lane
    }

    /** The diagonal paths: from the ring out to the mine, the site, and (through the hallways) the Agri and Guard domes. */
    static final double[][] DIAG = {{-45, -45}, {45, -52}, {-68, 59}, {68, 59}};

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

    static final String[] FLOWERS = {"poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "allium", "pink_tulip", "lily_of_the_valley", "red_tulip", "blue_orchid"};

    private static void ground(Paint p) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx, z = p.z0 + lz, dx = x - HX, dz = z - HZ;
            if (!innerRel(dx + 0.5, G + 0.5, dz + 0.5)) continue;
            double q = u(dx, HF, dz, 3);
            String top, under = "dirt", deco = null, deco2 = null;
            boolean inMain = MAIN.e(dx + 0.5, HF + 0.5, dz + 0.5) < MAIN.in(), inAgri = AGRI.e(dx + 0.5, HF + 0.5, dz + 0.5) < AGRI.in(),
                    inGuard = GUARD.e(dx + 0.5, HF + 0.5, dz + 0.5) < GUARD.in();
            if (!inMain && !inAgri && !inGuard) { // a hallway
                double[] a = (HALL_AGRI.inner(dx + 0.5, G, dz + 0.5) < 1 ? HALL_AGRI : HALL_GUARD).at(dx + 0.5, dz + 0.5);
                top = a[1] < 0.8 && Math.floorMod((int) a[2], 4) == 0 ? "sea_lantern" : a[1] < 1.6 ? "polished_andesite" : "dark_prismarine";
                under = "stone";
            } else if (inMain && rr(dx, dz) < PLAZA_R) {
                int ring = (int) rr(dx, dz);
                top = ring % 4 == 0 ? "polished_diorite" : ring % 4 == 2 ? "smooth_stone" : (Math.floorMod((int) Math.floor(Math.atan2(dz, dx) * 8 / Math.PI), 2) == 0 ? "polished_andesite" : "calcite");
                under = "stone";
            } else if (inMain && street(dx, dz)) {
                double r = rr(dx, dz);
                boolean edge = r >= RING_IN && r <= RING_OUT && (r < RING_IN + 0.9 || r > RING_OUT - 0.9);
                top = edge ? "polished_deepslate" : q < 0.55 ? "smooth_stone" : q < 0.8 ? "polished_andesite" : "andesite";
                under = "stone";
            } else {
                top = q < 0.5 ? "grass_block" : q < 0.72 ? "moss_block" : q < 0.86 ? "rooted_dirt" : q < 0.95 ? "grass_block" : "coarse_dirt";
                double d2 = u(dx, G, dz, 4);
                if (d2 < 0.30) deco = "short_grass";
                else if (d2 < 0.34) { deco = "tall_grass[half=lower]"; deco2 = "tall_grass[half=upper]"; }
                else if (d2 < 0.40) deco = FLOWERS[(int) (u(dx, G, dz, 5) * FLOWERS.length)];
                else if (d2 < 0.43) deco = "fern";
                else if (d2 < 0.46) deco = "moss_carpet";
                else if (d2 < 0.475) deco = "pink_petals[flower_amount=4,facing=north]";
                else if (d2 < 0.48) { deco = "large_fern[half=lower]"; deco2 = "large_fern[half=upper]"; }
            }
            p.set(x, HF, z, top);
            p.set(x, HF - 1, z, under);
            p.set(x, HF - 2, z, "stone");
            if (deco != null && p.get(x, G, z).isAir() && (deco2 == null || p.get(x, G + 1, z).isAir())) {
                p.set(x, G, z, deco);
                if (deco2 != null) p.set(x, G + 1, z, deco2);
            }
        }
    }


    // ---------------------------------------------------------------------------- the aquarium: two floors of tanks
    /** Solid rock round the basement first (the crust's caves never reach it). */
    private static void basementRock(Paint p) {
        if (!touchesRel(p, BASE_X0 - 2, BASE_Z0 - 2, BASE_X1 + 2, BASE_Z1 + 2)) return;
        fill(p, BASE_X0 - 2, HF - 11, BASE_Z0 - 2, BASE_X1 + 2, HF - 1, BASE_Z1 + 2, "deepslate");
    }

    /** What a tank's bed is made of, by where its creature lives. */
    static String[] tankDecor(String kind) {
        DeepMobs.Kind k = kind == null ? null : DeepMobs.KINDS.get(kind);
        if (k == null) return new String[]{"sand", "seagrass", "sea_lantern"};
        if (k.id().startsWith("mer")) return new String[]{"prismarine", "tube_coral", "sea_lantern"};
        String reg = switch (k.id()) {
            case "marroweel", "scavcrab", "ossuaryray" -> "bones";
            case "glassjelly", "prismfish", "crystalcrab", "shardback" -> "glass";
            case "narwhal", "iceseal", "frostorca", "rimejelly" -> "ice";
            case "magmaeel", "cindercrab" -> "fire";
            default -> "";
        };
        return switch (reg) {
            case "bones" -> new String[]{"gravel", "bone_block", "sea_lantern"};
            case "glass" -> new String[]{"calcite", "amethyst_cluster[facing=up,waterlogged=true]", "pearlescent_froglight"};
            case "ice" -> new String[]{"snow_block", "blue_ice", "sea_lantern"};
            case "fire" -> new String[]{"basalt[axis=y]", "magma_block", "shroomlight"};
            default -> switch (k.zone()) {
                case SULPHUR -> new String[]{"yellow_terracotta", "dead_brain_coral", "verdant_froglight"};
                case ABYSS -> new String[]{"deepslate", "sculk", "ochre_froglight"};
                case CAVES -> new String[]{"tuff", "glow_lichen[down=true,waterlogged=true]", "verdant_froglight"};
                case TRENCH -> new String[]{"blackstone", "magma_block", "shroomlight"};
                default -> new String[]{"sand", "tall_seagrass", "sea_lantern"};
            };
        };
    }

    private static void aquarium(Paint p) {
        int x0 = AQ_X0, x1 = AQ_X1, z0 = AQ_Z0, z1 = AQ_Z1, top = G + 11;
        // the hall above
        fill(p, x0, G, z0, x1, top + 3, z1, "air");
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(p, x, HF, z, (x + z) % 2 == 0 ? "dark_prismarine" : "prismarine_bricks");
        for (int y = G; y <= top; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
            if (!ex && !ez) continue;
            int along = ez ? x : z;
            String m = ex && ez ? "dark_prismarine" : Math.floorMod(along, 6) == 0 ? "dark_prismarine" : y >= G + 7 && y <= G + 9 ? "light_blue_stained_glass" : y == top ? "sea_lantern" : y <= G + 1 ? "dark_prismarine" : "prismarine_bricks";
            set(p, x, y, z, m);
        }
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            set(p, x, top + 1, z, edge ? "dark_prismarine_slab[type=bottom]" : Math.floorMod(x, 4) == 0 || Math.floorMod(z, 4) == 0 ? "prismarine_bricks" : "light_blue_stained_glass");
            if (!edge && Math.floorMod(x, 4) == 2 && Math.floorMod(z, 4) == 2) set(p, x, top, z, "sea_lantern");
        }
        // arches over the centre walkway
        for (int ax = 58; ax <= 82; ax += 8) for (int y = G + 5; y <= G + 6; y++) for (int dz = -1; dz <= 1; dz++) set(p, ax, y, dz, y == G + 6 ? "prismarine_brick_slab[type=top]" : dz == 0 ? "sea_lantern" : "prismarine_brick_stairs[facing=" + (dz < 0 ? "south" : "north") + ",half=top,shape=straight]");
        // the basement: a hall of deepslate under the floor
        int bf = BASE_FLOOR;
        fill(p, BASE_X0, bf + 1, BASE_Z0, BASE_X1, HF - 2, BASE_Z1, "air");
        for (int x = BASE_X0; x <= BASE_X1; x++) for (int z = BASE_Z0; z <= BASE_Z1; z++) {
            set(p, x, bf, z, (x + z) % 2 == 0 ? "polished_deepslate" : "deepslate_tiles");
            set(p, x, HF - 1, z, Math.floorMod(x, 5) == 0 && Math.floorMod(z, 6) == 0 ? "sea_lantern" : "deepslate_tiles");
        }
        for (int y = bf + 1; y <= HF - 2; y++) for (int z = BASE_Z0 - 1; z <= BASE_Z1 + 1; z++) {
            set(p, BASE_X0 - 1, y, z, "polished_deepslate"); set(p, BASE_X1 + 1, y, z, "polished_deepslate");
        }
        for (int y = bf + 1; y <= HF - 2; y++) for (int x = BASE_X0 - 1; x <= BASE_X1 + 1; x++) {
            set(p, x, y, BASE_Z0 - 1, Math.floorMod(x, 6) == 0 && y == bf + 3 ? "soul_lantern[hanging=false]" : "polished_deepslate");
            set(p, x, y, BASE_Z1 + 1, Math.floorMod(x, 6) == 0 && y == bf + 3 ? "soul_lantern[hanging=false]" : "polished_deepslate");
        }
        // the stairs down, in the west aisle (x 53..54, z 9..15)
        for (int k = 0; k < 7; k++) {
            int z = 9 + k, fy = HF - (k + 1);
            for (int x = 53; x <= 54; x++) {
                for (int y = fy - 1; y >= bf; y--) set(p, x, y, z, "polished_deepslate");
                set(p, x, fy, z, "polished_deepslate_stairs[facing=north,half=bottom,shape=straight]");
                for (int y = fy + 1; y <= HF + 3; y++) if (y <= HF) set(p, x, y, z, "air");
            }
        }
        for (int x = 53; x <= 54; x++) for (int z = 16; z <= 17; z++) set(p, x, bf, z, "polished_deepslate");
        // every tank: a bed for its creature, sea water, dark prismarine frames, glass in front and on top, its signs
        for (Tank t : TANKS) {
            if (!touchesRel(p, t.x0() - 2, t.z0() - 2, t.x1() + 2, t.z1() + 2)) continue;
            String[] dec = tankDecor(t.kind());
            int fy = t.y0() - 1;
            for (int x = t.x0() - 1; x <= t.x1() + 1; x++) for (int z = t.z0() - 1; z <= t.z1() + 1; z++) {
                boolean in = x >= t.x0() && x <= t.x1() && z >= t.z0() && z <= t.z1();
                boolean post = (x == t.x0() - 1 || x == t.x1() + 1);
                boolean wallRow = t.z0() == -21 || t.z1() == 21;
                if (in) {
                    set(p, x, fy, z, x == t.x0() + 1 && z == t.z0() + 1 ? dec[2] : dec[0]);
                    for (int y = t.y0(); y <= t.y1(); y++) set(p, x, y, z, "water");
                    set(p, x, t.y1() + 1, z, "glass");
                    continue;
                }
                if (wallRow && (z < -21 || z > 21)) continue; // the hall's own wall behind
                for (int y = fy; y <= t.y1() + 1; y++) set(p, x, y, z, post ? (y == t.y1() + 1 ? "sea_lantern" : "dark_prismarine") : y == fy ? "dark_prismarine" : "glass");
                set(p, x, t.y1() + 2, z, post ? "dark_prismarine_slab[type=bottom]" : "air");
            }
            // a little scenery in the water
            double q = u(t.x0(), t.y0(), t.z0(), 6);
            if (dec[1].contains("seagrass") || dec[1].contains("coral") || dec[1].contains("lichen") || dec[1].contains("amethyst")) set(p, t.x0(), t.y0(), t.z1(), dec[1]);
            else set(p, t.x1(), t.y0(), t.z0(), dec[1]);
            if (q < 0.5 && !dec[1].contains("tall")) set(p, t.x1(), t.y0(), t.z1(), "sea_pickle[pickles=" + (1 + (int) (q * 6)) + ",waterlogged=true]");
            else if (dec[1].equals("tall_seagrass")) { set(p, t.x0(), t.y0(), t.z1(), "tall_seagrass[half=lower]"); set(p, t.x0(), t.y0() + 1, t.z1(), "tall_seagrass[half=upper]"); }
            for (int[] s : t.signs()) set(p, s[0], s[1], s[2], "oak_wall_sign[facing=" + (s[3] == 0 ? "north" : "south") + "]");
        }
        // the entrance (west face), lecterns and benches
        fill(p, x0, G, -1, x0, G + 4, 1, "air");
        for (int dz = -1; dz <= 1; dz++) set(p, x0 - 1, HF, dz, "dark_prismarine");
        for (int sz : new int[]{-2, 2}) { fill(p, x0, G, sz, x0, G + 5, sz, "dark_prismarine"); set(p, x0, G + 6, sz, "sea_lantern"); }
        for (int i = 0; i < 2; i++) { int[] l = aquaLecternRel(i); set(p, l[0], G, l[1], "lectern[facing=east,has_book=true]"); }
        for (int bx = 86; bx <= 87; bx++) for (int bz : new int[]{-8, 8}) set(p, bx, G, bz, "dark_oak_stairs[facing=east,half=bottom,shape=straight]");
        for (int bx = 93; bx <= 94; bx++) for (int bz : new int[]{-8, 8}) set(p, bx, bf + 1, bz, "dark_oak_stairs[facing=east,half=bottom,shape=straight]");
    }


    // ---------------------------------------------------------------------------- the town's other buildings
    private static void townHall(Paint p) {
        hall(p, TH_X0, SA_Z0, TH_X1, SA_Z1, TH_X0, "white_terracotta", "polished_andesite", "polished_andesite", "deepslate_tiles");
        if (!touchesRel(p, TH_X0 - 2, SA_Z0 - 2, TH_X1 + 2, SA_Z1 + 2)) return;
        // the council table, the mayor's desk, banners, a clock tower over the door side
        for (int x = 13; x <= 21; x++) { set(p, x, G, 62, "dark_oak_planks"); set(p, x, G, 64, "dark_oak_planks"); set(p, x, G + 1, 62, "dark_oak_slab[type=bottom]"); }
        for (int x = 13; x <= 21; x += 2) { set(p, x, G, 61, "dark_oak_stairs[facing=south,half=bottom,shape=straight]"); set(p, x, G, 65, "dark_oak_stairs[facing=north,half=bottom,shape=straight]"); }
        set(p, 24, G, 63, "lectern[facing=west,has_book=false]");
        for (int z = 56; z <= 70; z += 7) set(p, TH_X1 - 1, G + 3, z, "cyan_wall_banner[facing=west]");
        int top = G + 18;
        for (int y = G + 7; y <= top; y++) for (int x = TH_X0; x <= TH_X0 + 4; x++) for (int z = 61; z <= 65; z++) {
            boolean edge = x == TH_X0 || x == TH_X0 + 4 || z == 61 || z == 65;
            if (!edge) continue;
            boolean corner = (x == TH_X0 || x == TH_X0 + 4) && (z == 61 || z == 65);
            set(p, x, y, z, corner ? "polished_andesite" : y >= top - 3 && y <= top - 2 ? "air" : "white_terracotta");
        }
        set(p, TH_X0 + 2, top - 1, 63, "bell[attachment=ceiling,facing=north]");
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) set(p, TH_X0 - 1, top - 6 + b, 63 + a, a == 0 && b >= 0 || b == 0 && a == 1 ? "black_concrete" : "smooth_quartz");
        fill(p, TH_X0 - 1, top + 1, 60, TH_X0 + 5, top + 1, 66, "deepslate_tile_slab[type=bottom]");
        set(p, TH_X0 + 2, top + 2, 63, "lightning_rod");
    }

    private static void school(Paint p) {
        hall(p, SCH_X0, SCH_Z0, SCH_X1, SCH_Z1, SCH_X1, "yellow_terracotta", "stripped_birch_log[axis=y]", "birch_planks", "red_terracotta");
        if (!touchesRel(p, SCH_X0 - 2, SCH_Z0 - 2, SCH_X1 + 12, SCH_Z1 + 14)) return;
        // desks in rows, the blackboard, bookshelves
        for (int x = SCH_X0 + 4; x <= SCH_X1 - 5; x += 3) for (int z = SCH_Z0 + 3; z <= SCH_Z1 - 3; z += 3) {
            set(p, x, G, z, "birch_fence"); set(p, x, G + 1, z, "birch_pressure_plate"); set(p, x + 1, G, z, "birch_stairs[facing=west,half=bottom,shape=straight]");
        }
        for (int z = SCH_Z0 + 2; z <= SCH_Z1 - 2; z++) { set(p, SCH_X0 + 1, G + 1, z, "black_concrete"); set(p, SCH_X0 + 1, G + 2, z, "black_concrete"); set(p, SCH_X0 + 1, G, z, "bookshelf"); }
        // the playground east of it
        int px0 = -34, px1 = -28, pz0 = 78, pz1 = 88;
        for (int x = px0; x <= px1; x++) for (int z = pz0; z <= pz1; z++) {
            if (headroom(x, z) < 6) continue;
            set(p, x, HF, z, (x + z) % 2 == 0 ? "red_concrete_powder" : "sand");
            if (x == px0 || x == px1 || z == pz1) set(p, x, G, z, "oak_fence");
        }
        for (int y = G; y <= G + 3; y++) { set(p, px0 + 2, y, pz0 + 3, "oak_fence"); set(p, px0 + 4, y, pz0 + 3, "oak_fence"); }  // the swings
        set(p, px0 + 2, G + 4, pz0 + 3, "oak_planks"); set(p, px0 + 3, G + 4, pz0 + 3, "oak_planks"); set(p, px0 + 4, G + 4, pz0 + 3, "oak_planks");
        set(p, px0 + 3, G + 3, pz0 + 3, "iron_chain"); set(p, px0 + 3, G + 2, pz0 + 3, "iron_chain"); set(p, px0 + 3, G + 1, pz0 + 3, "oak_pressure_plate");
        for (int k = 0; k < 4; k++) set(p, px0 + 2 + k, G + 3 - k, pz0 + 7, "smooth_quartz_stairs[facing=west,half=bottom,shape=straight]"); // the slide
        for (int y = G; y <= G + 3; y++) set(p, px0 + 1, y, pz0 + 7, y == G + 3 ? "oak_planks" : "ladder[facing=west]");
        set(p, px0, G, pz0 + 7, "oak_planks");
    }

    private static void clinic(Paint p) {
        hall(p, CLI_X0, CLI_Z0, CLI_X1, CLI_Z1, CLI_X0, "white_concrete", "light_gray_concrete", "white_concrete", "white_concrete");
        if (!touchesRel(p, CLI_X0 - 2, CLI_Z0 - 2, CLI_X1 + 2, CLI_Z1 + 2)) return;
        for (int z = CLI_Z0 + 2; z <= CLI_Z1 - 2; z += 3) { set(p, CLI_X1 - 2, G, z, "white_bed[facing=east,part=foot]"); set(p, CLI_X1 - 1, G, z, "white_bed[facing=east,part=head]"); }
        set(p, CLI_X0 + 3, G, CLI_Z0 + 2, "brewing_stand"); set(p, CLI_X0 + 4, G, CLI_Z0 + 2, "water_cauldron[level=3]");
        // the red cross on the roof's front
        for (int k = -2; k <= 2; k++) { set(p, CLI_X0 - 1, G + 5 + k, 68, "red_concrete"); set(p, CLI_X0 - 1, G + 5, 68 + k, "red_concrete"); }
    }

    private static void greenhouse(Paint p) {
        if (!touchesRel(p, GH_X0 - 2, GH_Z0 - 2, GH_X1 + 2, GH_Z1 + 2)) return;
        int top = G + 6;
        fill(p, GH_X0, G, GH_Z0, GH_X1, top + 4, GH_Z1, "air");
        for (int x = GH_X0; x <= GH_X1; x++) for (int z = GH_Z0; z <= GH_Z1; z++) {
            boolean ex = x == GH_X0 || x == GH_X1, ez = z == GH_Z0 || z == GH_Z1;
            set(p, x, HF, z, ex || ez ? "polished_andesite" : Math.floorMod(z - GH_Z0, 4) == 0 ? "smooth_stone" : "moss_block");
            for (int y = G; y <= top; y++) if (ex || ez) set(p, x, y, z, (ex && ez) || Math.floorMod(ez ? x : z, 4) == 0 ? "white_concrete" : y == G ? "white_concrete" : "glass");
            int k = Math.min(Math.min(x - GH_X0, GH_X1 - x), 4);
            if (!ex && !ez) set(p, x, top + Math.min(k, (GH_Z1 - GH_Z0) / 2), z, "air");
            int rz = Math.min(z - GH_Z0, GH_Z1 - z);
            set(p, x, top + 1 + Math.min(rz, 3), z, Math.floorMod(x, 4) == 0 ? "white_concrete" : "glass");
        }
        // beds of flowers and little trees inside, a fountain
        for (int x = GH_X0 + 2; x <= GH_X1 - 2; x++) for (int z = GH_Z0 + 1; z <= GH_Z1 - 1; z++) {
            if (Math.floorMod(z - GH_Z0, 4) == 0) continue;
            double q = u(x, G, z, 41);
            set(p, x, G, z, q < 0.55 ? FLOWERS[(int) (q * 100) % FLOWERS.length] : q < 0.7 ? "short_grass" : q < 0.75 ? "flowering_azalea" : "air");
        }
        fill(p, GH_X0 - 1 + 1, G, 35, GH_X0, G + 3, 37, "air"); // its door (west)
        set(p, (GH_X0 + GH_X1) / 2, G, 36, "water_cauldron[level=3]");
    }

    /** The miners' and divers' bunkhouse by the pithead (6 beds, door on its west face). */
    private static void bunkhouse(Paint p) {
        hall(p, BUNK_X0, BUNK_Z0, BUNK_X1, BUNK_Z1, BUNK_X0, "spruce_planks", "dark_oak_log[axis=y]", "spruce_planks", "dark_oak_planks");
        if (!touchesRel(p, BUNK_X0 - 2, BUNK_Z0 - 2, BUNK_X1 + 2, BUNK_Z1 + 2)) return;
        for (int[] b : bunkBeds()) {
            boolean north = b[2] == 0;
            String f = north ? "north" : "south";
            set(p, b[0], G, b[1], "brown_bed[facing=" + f + ",part=head]"); set(p, b[0], G, b[1] + (north ? 1 : -1), "brown_bed[facing=" + f + ",part=foot]");
        }
        set(p, BUNK_X1 - 1, G, BUNK_Z0 + 1, "barrel[facing=up]"); set(p, BUNK_X1 - 1, G, BUNK_Z0 + 2, "smoker[facing=west,lit=false]");
        set(p, BUNK_X1 - 1, G, BUNK_Z1 - 1, "crafting_table");
    }

    /** The bunkhouse beds: {rel x, rel z (the head), 0 north wall / 1 south wall}. */
    static int[][] bunkBeds() { return new int[][]{{-42, BUNK_Z0 + 1, 0}, {-40, BUNK_Z0 + 1, 0}, {-38, BUNK_Z0 + 1, 0}, {-36, BUNK_Z0 + 1, 0}, {-40, BUNK_Z1 - 1, 1}, {-36, BUNK_Z1 - 1, 1}}; }

    // ---------------------------------------------------------------------------- the park
    private static void park(Paint p) {
        if (!touchesRel(p, PARK_X - 20, PARK_Z - 20, PARK_X + 20, PARK_Z + 20)) return;
        for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
            int x = PARK_X + dx, z = PARK_Z + dz;
            double r = Math.hypot(dx, dz);
            if (headroom(x, z) < 5 || street(x, z)) continue;
            if (r < POND_R) { set(p, x, HF, z, "water"); set(p, x, HF - 1, z, r < POND_R - 2 ? "water" : "sand"); set(p, x, HF - 2, z, "clay"); set(p, x, G, z, "air");
                if (u(x, G, z, 51) < 0.07) set(p, x, G, z, "lily_pad"); continue; }
            if (r < POND_R + 1) { set(p, x, HF, z, "sand"); set(p, x, G, z, u(x, G, z, 52) < 0.3 ? "sugar_cane" : "air"); continue; }
            if (r >= 9 && r < 10.5) { set(p, x, HF, z, "gravel"); set(p, x, G, z, "air"); continue; }
        }
        // benches round the path, a gazebo, trees
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int bx = PARK_X + (int) Math.round(Math.cos(a) * 11.5), bz = PARK_Z + (int) Math.round(Math.sin(a) * 11.5);
            if (!street(bx, bz)) set(p, bx, G, bz, "spruce_stairs[facing=" + (Math.abs(Math.cos(a)) > 0.7 ? (Math.cos(a) > 0 ? "west" : "east") : (Math.sin(a) > 0 ? "north" : "south")) + ",half=bottom,shape=straight]");
        }
        int gx = PARK_X - 14, gz = PARK_Z - 10;
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) for (int y = G; y <= G + 3; y++) set(p, gx + c[0], y, gz + c[1], "oak_fence");
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) { int k = Math.max(Math.abs(dx), Math.abs(dz)); set(p, gx + dx, G + 4 + (3 - k) / 2, gz + dz, k == 3 ? "dark_oak_slab[type=bottom]" : "dark_oak_planks"); set(p, gx + dx, HF, gz + dz, "spruce_planks"); }
        set(p, gx, G + 3, gz, "lantern[hanging=true]");
        for (int[] t : new int[][]{{PARK_X + 14, PARK_Z + 2}, {PARK_X - 4, PARK_Z + 14}, {PARK_X + 8, PARK_Z - 13}, {PARK_X - 15, PARK_Z + 6}})
            if (headroom(t[0], t[1]) > 12 && !street(t[0], t[1])) for (Object[] b : decorTree(t[0], t[1], Math.floorMod(t[0] + t[1], 3))) set(p, (int) b[0], (int) b[1], (int) b[2], (String) b[3]);
    }

    /** A little decorative tree (cherry, azalea or birch): {x, y, z, block}. */
    static List<Object[]> decorTree(int x, int z, int kind) {
        List<Object[]> out = new ArrayList<>();
        String log = kind == 0 ? "cherry_log" : kind == 1 ? "oak_log" : "birch_log", leaf = kind == 0 ? "cherry_leaves" : kind == 1 ? "flowering_azalea_leaves" : "birch_leaves";
        int h = 4 + Math.floorMod(x * 3 + z, 2);
        for (int y = G; y < G + h; y++) out.add(new Object[]{x, y, z, log + "[axis=y]"});
        int cy = G + h - 1;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 2; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.5);
            if (d > 2.4 || dx == 0 && dz == 0 && dy <= 0) continue;
            out.add(new Object[]{x + dx, cy + dy, z + dz, leaf + "[persistent=true]"});
        }
        return out;
    }

    // ---------------------------------------------------------------------------- gardens in the gaps
    /** Every building's box (relative, with a margin): gardens, lamps and trees keep out. */
    static final List<int[]> BOXES = new ArrayList<>();

    static {
        for (House h : HOUSES) BOXES.add(new int[]{h.x0() - HX - 2, h.z0() - HZ - 3, h.x1() - HX + 2, h.z1() - HZ + 2});
        int[][] b = {{LAB_X0, LAB_Z0, LAB_X1, LAB_Z1 + 2}, {AQ_X0 - 2, AQ_Z0, AQ_X1, AQ_Z1}, {TAV_X0, SA_Z0, TAV_X1 + 2, SA_Z1}, {TH_X0 - 2, SA_Z0, TH_X1, SA_Z1},
                {DOCK_X0 - 2, DOCK_Z0, DOCK_X1, DOCK_Z1}, {SITE_X0 - 3, SITE_Z0, SITE_X1 + 2, SITE_Z1 + 3}, {-64, -62, -44, -46}, {MARKET_X0, -10, MARKET_X1, 10},
                {PARK_X - 18, PARK_Z - 18, PARK_X + 18, PARK_Z + 18}, {SCH_X0, SCH_Z0, SCH_X1 + 2, SCH_Z1}, {-35, 76, -27, 89}, {CLI_X0 - 2, CLI_Z0, CLI_X1, CLI_Z1},
                {GH_X0 - 2, GH_Z0, GH_X1, GH_Z1}, {BUNK_X0 - 2, BUNK_Z0, BUNK_X1, BUNK_Z1}, {-60, -63, -45, -48}, {-12, -12, 12, 12}};
        for (int[] x : b) BOXES.add(new int[]{x[0] - 1, x[1] - 1, x[2] + 1, x[3] + 1});
    }

    static boolean blocked(int dx, int dz) {
        for (int[] b : BOXES) if (dx >= b[0] && dx <= b[2] && dz >= b[1] && dz <= b[3]) return true;
        return false;
    }

    /** One feature per 9-block cell of open lawn: a little tree, a flower bed, a bench and lamp, a hedge, a statue, a well. */
    private static void gardens(Paint p) {
        int cx0 = Math.floorDiv(p.x0 - HX - 6, 9), cz0 = Math.floorDiv(p.z0 - HZ - 6, 9);
        for (int cx = cx0; cx <= cx0 + 3; cx++) for (int cz = cz0; cz <= cz0 + 3; cz++) {
            double r = DeepGenerator.unit(DeepGenerator.hash(4401, cx, cz, 1));
            if (r > 0.55) continue;
            int x = cx * 9 + 2 + (int) (DeepGenerator.unit(DeepGenerator.hash(4401, cx, cz, 2)) * 5), z = cz * 9 + 2 + (int) (DeepGenerator.unit(DeepGenerator.hash(4401, cx, cz, 3)) * 5);
            if (MAIN.e(x, HF + 0.5, z) > 0.9 || headroom(x, z) < 10) continue;
            boolean clear = true;
            for (int dx = -3; dx <= 3 && clear; dx++) for (int dz = -3; dz <= 3 && clear; dz++) if (street(x + dx, z + dz) || blocked(x + dx, z + dz) || rr(x + dx, z + dz) < PLAZA_R + 1) clear = false;
            if (!clear) continue;
            int kind = (int) (DeepGenerator.unit(DeepGenerator.hash(4401, cx, cz, 4)) * 6);
            switch (kind) {
                case 0, 1 -> { for (Object[] b : decorTree(x, z, kind)) set(p, (int) b[0], (int) b[1], (int) b[2], (String) b[3]); }
                case 2 -> { // a flower bed
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) {
                        boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 1;
                        set(p, x + dx, HF, z + dz, edge ? "stone_bricks" : "grass_block");
                        set(p, x + dx, G, z + dz, edge ? "stone_brick_slab[type=bottom]" : FLOWERS[Math.floorMod(x + dx * 3 + z, FLOWERS.length)]);
                    }
                }
                case 3 -> { // a bench under a lamp
                    for (int dx = -1; dx <= 1; dx++) set(p, x + dx, G, z, "spruce_stairs[facing=north,half=bottom,shape=straight]");
                    set(p, x + 2, G, z, "polished_deepslate_wall"); set(p, x + 2, G + 1, z, "iron_bars"); set(p, x + 2, G + 2, z, "sea_lantern");
                }
                case 4 -> { // a hedge with a gap
                    for (int dx = -3; dx <= 3; dx++) if (dx != 0) { set(p, x + dx, G, z, "oak_leaves[persistent=true]"); set(p, x + dx, G + 1, z, "oak_leaves[persistent=true]"); }
                }
                default -> { // a little well
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(p, x + dx, G, z + dz, dx == 0 && dz == 0 ? "water_cauldron[level=3]" : "cobblestone_wall");
                    for (int y = G + 1; y <= G + 2; y++) { set(p, x - 1, y, z - 1, "spruce_fence"); set(p, x + 1, y, z + 1, "spruce_fence"); }
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(p, x + dx, G + 3, z + dz, "spruce_slab[type=bottom]");
                }
            }
        }
    }


    // ---------------------------------------------------------------------------- the mine (NW, under the floor)
    static final int[] HEADF = {-64, -62, -60, -58};      // the headframe tower {x0, z0, x1, z1} over the shaft
    static final int SHAFT_X = -62, SHAFT_Z = -60;         // the shaft's middle (3x3)

    /** The ore faces the miners work: {x, y, z (the ore), stand x, stand z, kind}. Kinds: see ORE_KINDS. */
    static final String[] ORE_KINDS = {"coal_ore", "iron_ore", "copper_ore", "gold_ore", "coal_ore", "redstone_ore", "iron_ore", "lapis_ore"};
    static final List<int[]> ORES = new ArrayList<>();
    static final int[][] GAL_PILLARS = {{-80, -62}, {-80, -50}, {-74, -62}, {-74, -50}};

    static {
        int k = 0;
        for (int x : new int[]{-84, -78, -72}) {
            ORES.add(new int[]{x, GY + 2, GAL_Z0, x, GAL_Z0 + 1, k++ % ORE_KINDS.length});
            ORES.add(new int[]{x, GY + 2, GAL_Z1, x, GAL_Z1 - 1, k++ % ORE_KINDS.length});
        }
        ORES.add(new int[]{GAL_X0, GY + 2, -62, GAL_X0 + 1, -62, k++ % ORE_KINDS.length});
        ORES.add(new int[]{GAL_X0, GY + 2, -50, GAL_X0 + 1, -50, k % ORE_KINDS.length});
    }

    static String oreAt(int[] o) { return ORE_KINDS[o[5]]; }

    /** Solid rock round the whole mine first (the crust's caves and the sea must never reach its tunnels). */
    private static void mineRock(Paint p) {
        if (!touchesRel(p, MINE_X0, MINE_Z0, MINE_X1, MINE_Z1)) return;
        for (int x = MINE_X0; x <= MINE_X1; x++) for (int z = MINE_Z0; z <= MINE_Z1; z++) {
            if (!p.in(HX + x, HF, HZ + z)) continue;
            for (int y = HF - 26; y <= HF - 1; y++) {
                double q = u(x, y, z, 9);
                set(p, x, y, z, q < 0.012 ? "coal_ore" : q < 0.018 ? "iron_ore" : q < 0.16 ? "tuff" : q < 0.22 ? "andesite" : y < HF - 12 ? "deepslate" : "stone");
            }
        }
    }

    private static String wallRock(int x, int y, int z) {
        double q = u(x, y, z, 10);
        return q < 0.10 ? "coal_ore" : q < 0.14 ? "iron_ore" : q < 0.16 ? "copper_ore" : q < 0.3 ? "tuff" : q < 0.42 ? "cobblestone" : q < 0.5 ? "andesite" : "stone";
    }

    private static void mine(Paint p) {
        if (!touchesRel(p, MINE_X0, MINE_Z0, MINE_X1, MINE_Z1)) return;
        pithead(p);
        headframe(p);
        gallery(p);
        stairTunnel(p);
        shaft(p);
        diveRoom(p);
    }

    /** A timber shed over the stair tunnel's mouth, with railings round the open pit. */
    private static void pithead(Paint p) {
        for (int[] c : new int[][]{{-58, -59}, {-48, -59}, {-58, -51}, {-48, -51}, {-53, -59}, {-53, -51}})
            for (int y = G; y <= G + 5; y++) set(p, c[0], y, c[1], "stripped_dark_oak_log[axis=y]");
        for (int x = -59; x <= -47; x++) {
            set(p, x, G + 6, -59, "dark_oak_log[axis=x]"); set(p, x, G + 6, -51, "dark_oak_log[axis=x]");
            for (int k = 0; k <= 5; k++) {
                set(p, x, G + 6 + k, -60 + k, k == 5 ? "dark_oak_planks" : "dark_oak_stairs[facing=south,half=bottom,shape=straight]");
                set(p, x, G + 6 + k, -50 - k, k == 5 ? "dark_oak_planks" : "dark_oak_stairs[facing=north,half=bottom,shape=straight]");
            }
        }
        set(p, -53, G + 5, -55, "lantern[hanging=true]");
        for (int x = -57; x <= -53; x++) { set(p, x, G, -57, "dark_oak_fence"); set(p, x, G, -53, "dark_oak_fence"); }
        for (int z = -56; z <= -54; z++) set(p, -57, G, z, "dark_oak_fence");
        // tools and supplies under the roof
        set(p, -49, G, -58, "barrel[facing=up]"); set(p, -50, G, -58, "barrel[facing=up]"); set(p, -49, G + 1, -58, "barrel[facing=up]");
        set(p, -57, G, -58, "grindstone[face=floor,facing=east]"); set(p, -56, G, -58, "anvil[facing=east]");
        for (int x = -50; x <= -48; x++) set(p, x, G, -52, "oak_log[axis=x]");
        set(p, -49, G + 1, -52, "oak_log[axis=x]");
        set(p, -52, G + 3, -59, "oak_wall_sign[facing=south]");
    }

    /** The headframe: a 16-tall timber tower over the vertical shaft, braced, a platform and the winding wheel on top. */
    private static void headframe(Paint p) {
        int x0 = HEADF[0], z0 = HEADF[1], x1 = HEADF[2], z1 = HEADF[3], top = G + 15;
        for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) for (int y = G; y <= top; y++) set(p, c[0], y, c[1], "dark_oak_log[axis=y]");
        for (int yb = G + 4; yb <= top; yb += 4) {
            for (int x = x0 + 1; x < x1; x++) { set(p, x, yb, z0, "dark_oak_log[axis=x]"); set(p, x, yb, z1, "dark_oak_log[axis=x]"); }
            for (int z = z0 + 1; z < z1; z++) { set(p, x0, yb, z, "dark_oak_log[axis=z]"); set(p, x1, yb, z, "dark_oak_log[axis=z]"); }
            // X braces under each beam (fences, a lattice)
            for (int d = 1; d <= 3; d++) {
                int y = yb - 4 + d;
                if (y < G + 1) continue;
                set(p, x0 + d, y, z0, "dark_oak_fence"); set(p, x1 - d, y, z1, "dark_oak_fence");
                set(p, x0, y, z1 - d, "dark_oak_fence"); set(p, x1, y, z0 + d, "dark_oak_fence");
            }
        }
        // the platform, its railing, the wheel and the rope down the shaft
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) {
            boolean edge = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
            set(p, x, top + 1, z, x == SHAFT_X && z == SHAFT_Z ? "dark_oak_trapdoor[facing=north,half=top,open=false]" : "spruce_planks");
            if (edge) set(p, x, top + 2, z, "spruce_fence");
        }
        int wy = top + 5;
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) {
            double r = Math.hypot(dx, dy);
            String m = r > 2.4 && r < 3.4 ? "dark_oak_planks" : (dx == 0 || dy == 0) && r <= 2.4 ? (r < 0.5 ? "polished_deepslate" : "spruce_fence") : null;
            if (m != null) set(p, SHAFT_X + dx, wy + dy, z0 + 1, m);
        }
        for (int y = top + 2; y <= wy - 3; y++) { set(p, SHAFT_X, y, z0 + 2, "dark_oak_fence"); }
        set(p, SHAFT_X, wy, z0 + 2, "dark_oak_log[axis=z]");
        set(p, SHAFT_X + 3, wy, z0 + 2, "iron_chain[axis=y]");
        for (int y = G; y <= top; y++) set(p, SHAFT_X, y, SHAFT_Z, "iron_chain[axis=y]");
        set(p, SHAFT_X, G - 1, SHAFT_Z, "air");
        // a fence ring round the shaft's mouth, a gate on the east
        for (int x = x0 + 1; x < x1; x++) { set(p, x, G, z0, "spruce_fence"); set(p, x, G, z1, "spruce_fence"); }
        for (int z = z0 + 1; z < z1; z++) { set(p, x0, G, z, "spruce_fence"); set(p, x1, G, z, z == SHAFT_Z ? "spruce_fence_gate[facing=east,open=false]" : "spruce_fence"); }
    }

    /** The vertical shaft under the headframe (a ladder, the rope) and a drift from its foot into the gallery. */
    private static void shaft(Paint p) {
        for (int y = GY; y <= HF; y++) for (int x = HEADF[0]; x <= HEADF[2]; x++) for (int z = HEADF[1]; z <= HEADF[3]; z++) {
            boolean wall = x == HEADF[0] || x == HEADF[2] || z == HEADF[1] || z == HEADF[3];
            if (y == HF && !wall) { set(p, x, y, z, "air"); continue; }
            if (y == HF) continue;
            if (wall) set(p, x, y, z, Math.floorMod(y, 5) == 0 ? "stripped_dark_oak_log[axis=y]" : u(x, y, z, 13) < 0.3 ? "mossy_stone_bricks" : "stone_bricks");
            else if (y == GY) set(p, x, y, z, "polished_andesite");
            else set(p, x, y, z, x == SHAFT_X && z == HEADF[1] + 1 ? "ladder[facing=south]" : x == SHAFT_X && z == SHAFT_Z ? (y == GY + 2 ? "lantern[hanging=true]" : y < GY + 2 ? "air" : "iron_chain[axis=y]") : "air");
        }
        // the drift: x -67..-61 at z -61..-59
        for (int x = -68; x <= -63; x++) for (int z = -61; z <= -59; z++) {
            set(p, x, GY, z, "polished_andesite");
            for (int y = GY + 1; y <= GY + 3; y++) set(p, x, y, z, "air");
            set(p, x, GY + 4, z, x % 2 == 0 ? "dark_oak_planks" : "stone");
        }
        for (int x = -67; x <= -63; x += 2) for (int y = GY + 1; y <= GY + 3; y++) { set(p, x, y, -62, "stripped_dark_oak_log[axis=y]"); set(p, x, y, -58, "stripped_dark_oak_log[axis=y]"); }
        set(p, -65, GY + 3, -60, "lantern[hanging=true]");
    }

    /** The stair tunnel from the pithead down west to the gallery: timber sets every 3 steps, lanterns in the roof. */
    private static void stairTunnel(Paint p) {
        for (int i = 0; i <= DEPTH; i++) {
            int x = TUNNEL_X_TOP - i, fy = HF - i;
            for (int z = TUNNEL_Z0 - 1; z <= TUNNEL_Z1 + 1; z++) {
                boolean wall = z == TUNNEL_Z0 - 1 || z == TUNNEL_Z1 + 1;
                set(p, x, fy, z, wall ? "stripped_dark_oak_log[axis=y]" : z == (TUNNEL_Z0 + TUNNEL_Z1) / 2 ? "polished_andesite" : "andesite");
                for (int y = fy + 1; y <= fy + 4; y++) {
                    if (wall) { if (y <= HF) set(p, x, y, z, i % 3 == 0 ? "stripped_dark_oak_log[axis=y]" : wallRock(x, y, z)); continue; }
                    set(p, x, y, z, y == fy + 4 && i % 3 == 0 && i > 1 ? "dark_oak_planks" : "air");
                }
                if (fy + 5 <= HF) set(p, x, fy + 5, z, i % 3 == 0 ? "dark_oak_planks" : "stone");
            }
            if (i % 4 == 2) set(p, x, fy + 4, (TUNNEL_Z0 + TUNNEL_Z1) / 2, "lantern[hanging=true]");
        }
        for (int z = TUNNEL_Z0; z <= TUNNEL_Z1; z++) for (int y = GY + 1; y <= GY + 3; y++) set(p, GAL_X1, y, z, "air");
    }

    /** The gallery: 17 x 19 under 5 of rock, timber pillars, roof beams, a rail line, a smithy corner and the ore faces. */
    private static void gallery(Paint p) {
        for (int x = GAL_X0; x <= GAL_X1; x++) for (int z = GAL_Z0; z <= GAL_Z1; z++) {
            boolean wall = x == GAL_X0 || x == GAL_X1 || z == GAL_Z0 || z == GAL_Z1;
            set(p, x, GY, z, wall ? "deepslate" : u(x, GY, z, 14) < 0.25 ? "gravel" : u(x, GY, z, 15) < 0.4 ? "andesite" : "polished_andesite");
            for (int y = GY + 1; y <= GY + 5; y++) {
                if (wall) { set(p, x, y, z, wallRock(x, y, z)); continue; }
                set(p, x, y, z, y == GY + 5 && Math.floorMod(x - GAL_X0, 4) == 1 ? "dark_oak_planks" : "air");
            }
            set(p, x, GY + 6, z, "stone");
        }
        // timber sets along the walls (posts + the beam over them)
        for (int x = GAL_X0 + 1; x < GAL_X1; x += 4) for (int z : new int[]{GAL_Z0 + 1, GAL_Z1 - 1}) {
            boolean ore = false;
            for (int[] o : ORES) if (Math.abs(o[3] - x) <= 1 && o[4] == z) ore = true;
            if (ore) continue;
            for (int y = GY + 1; y <= GY + 4; y++) set(p, x, y, z, "stripped_dark_oak_log[axis=y]");
        }
        for (int[] c : GAL_PILLARS) for (int y = GY + 1; y <= GY + 5; y++) set(p, c[0], y, c[1], "dark_oak_log[axis=y]");
        // the ore faces: what the miners dig (and what grows back)
        for (int[] o : ORES) { set(p, o[0], o[1], o[2], oreAt(o)); set(p, o[0], o[1] - 1, o[2], wallRock(o[0], o[1] - 1, o[2]).endsWith("ore") ? "stone" : wallRock(o[0], o[1] - 1, o[2])); }
        // the rail line from the tunnel's mouth to the west wall, a buffer at its end
        for (int x = GAL_X0 + 2; x < GAL_X1; x++) set(p, x, GY + 1, -56, "rail[shape=east_west]");
        // lanterns, a smithy corner, supplies
        for (int x = GAL_X0 + 3; x < GAL_X1; x += 5) for (int z : new int[]{-62, -50}) set(p, x, GY + 4, z, "lantern[hanging=true]");
        set(p, -70, GY + 1, -65, "anvil[facing=north]"); set(p, -71, GY + 1, -65, "smithing_table"); set(p, -69, GY + 1, -64, "blast_furnace[facing=west,lit=true]");
        set(p, -69, GY + 1, -65, "barrel[facing=up]"); set(p, -69, GY + 1, -47, "barrel[facing=up]"); set(p, -70, GY + 1, -47, "barrel[facing=up]"); set(p, -69, GY + 2, -47, "barrel[facing=up]");
        set(p, -71, GY + 1, -47, "crafting_table");
        set(p, -82, GY + 1, -52, "coal_block"); set(p, -81, GY + 1, -52, "coal_block"); set(p, -82, GY + 2, -52, "coal_block");
        // the doorway west into the dive room
        for (int z = -57; z <= -55; z++) for (int y = GY + 1; y <= GY + 3; y++) { set(p, GAL_X0, y, z, "air"); set(p, DIVE_X1, y, z, "air"); }
    }

    /** The dive room: lockers of diving gear, benches, a winch, and the MOON POOL down to the flooded tunnel. */
    private static void diveRoom(Paint p) {
        int x0 = DIVE_X0, x1 = DIVE_X1, z0 = DIVE_Z0, z1 = DIVE_Z1;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
            if (x == x1 && z >= -57 && z <= -55) { set(p, x, GY, z, "dark_prismarine"); continue; } // the doorway (cut by the gallery)
            set(p, x, GY, z, wall ? "dark_prismarine" : (x + z) % 2 == 0 ? "prismarine_bricks" : "dark_prismarine");
            for (int y = GY + 1; y <= GY + 5; y++) {
                if (wall) { boolean post = (x == x0 || x == x1) && Math.floorMod(z, 4) == 0 || (z == z0 || z == z1) && Math.floorMod(x, 4) == 0;
                    set(p, x, y, z, post ? "dark_prismarine" : y == GY + 3 ? "waxed_cut_copper" : "prismarine_bricks"); continue; }
                set(p, x, y, z, "air");
            }
            set(p, x, GY + 6, z, !wall && Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0 ? "sea_lantern" : "prismarine_bricks");
        }
        // the moon pool: 3 x 3 of water from the floor down to the tunnel, a prismarine shaft, a ladder
        for (int y = POOL_BOTTOM - 1; y <= GY; y++) for (int x = POOL_X - 2; x <= POOL_X + 2; x++) for (int z = POOL_Z - 2; z <= POOL_Z + 2; z++) {
            boolean ring = Math.abs(x - POOL_X) == 2 || Math.abs(z - POOL_Z) == 2;
            if (y == POOL_BOTTOM - 1) { set(p, x, y, z, "dark_prismarine"); continue; }
            if (ring) { set(p, x, y, z, y == GY ? "sea_lantern" : Math.floorMod(y, 4) == 0 ? "sea_lantern" : "prismarine_bricks"); continue; }
            set(p, x, y, z, x == POOL_X + 1 && z == POOL_Z ? "ladder[facing=west,waterlogged=true]" : "water");
        }
        for (int x = POOL_X - 2; x <= POOL_X + 2; x++) for (int z = POOL_Z - 2; z <= POOL_Z + 2; z++) {
            boolean ring = Math.abs(x - POOL_X) == 2 || Math.abs(z - POOL_Z) == 2;
            if (ring && !(x == POOL_X + 2 && Math.abs(z - POOL_Z) <= 1)) set(p, x, GY + 1, z, "iron_bars");
        }
        // a winch and its rope over the pool
        set(p, POOL_X, GY + 5, POOL_Z, "iron_chain[axis=y]"); set(p, POOL_X, GY + 4, POOL_Z, "lantern[hanging=true]");
        set(p, POOL_X - 3, GY + 1, POOL_Z, "grindstone[face=floor,facing=east]");
        // suit lockers on the north wall: copper suits with glass visors
        for (int x = x0 + 2; x <= x1 - 2; x += 2) {
            set(p, x, GY + 1, z0 + 1, "waxed_cut_copper"); set(p, x, GY + 2, z0 + 1, "waxed_copper_block");
            set(p, x, GY + 3, z0 + 1, "light_blue_stained_glass"); set(p, x + 1, GY + 1, z0 + 1, "iron_bars");
        }
        // benches and tanks on the south wall
        for (int x = x0 + 2; x <= x1 - 3; x++) set(p, x, GY + 1, z1 - 1, x % 3 == 0 ? "waxed_copper_block" : "spruce_stairs[facing=north,half=bottom,shape=straight]");
        set(p, x0 + 1, GY + 1, z1 - 1, "barrel[facing=up]"); set(p, x0 + 1, GY + 2, z1 - 1, "barrel[facing=up]");
        set(p, x0 + 1, GY + 3, -56, "oak_wall_sign[facing=east]");
    }

    // ---------------------------------------------------------------------------- the dive tunnel to the crust's caves
    /** {start x, y, z, end x, y, z, found (1) or a grotto (0)}, relative x/z, per world seed. */
    private static final Map<Long, int[]> DIVE = new ConcurrentHashMap<>();

    /** Where the flooded tunnel runs: from the moon pool's foot west, then straight to the nearest real cave. */
    static int[] diveRoute(DeepGenerator g, DeepGenerator.Noise n) {
        return DIVE.computeIfAbsent(n.seed, s -> {
            int sx = MINE_X0 - 3, sy = POOL_BOTTOM + 1, sz = POOL_Z;
            int[] best = null; double bd = 1e9;
            for (int x = -176; x <= -110; x += 3) for (int z = -130; z <= -10; z += 3) for (int y = 30; y <= 52; y += 2) {
                double d = Math.hypot(Math.hypot(x - sx, z - sz), (y - sy) * 1.5);
                if (d >= bd) continue;
                int ax = HX + x, az = HZ + z;
                if (!g.crustCaveAt(n, ax, y, az) || !g.crustCaveAt(n, ax, y + 2, az) || !g.crustCaveAt(n, ax + 2, y, az) || !g.crustCaveAt(n, ax, y, az + 2)) continue;
                best = new int[]{x, y, z}; bd = d;
            }
            if (best == null) return new int[]{sx, sy, sz, -140, 40, -56, 0};
            return new int[]{sx, sy, sz, best[0], best[1], best[2], 1};
        });
    }

    /** Points along the flooded tunnel (relative x, y, z), half a block apart. */
    static List<double[]> divePath(DeepGenerator g, DeepGenerator.Noise n) {
        int[] r = diveRoute(g, n);
        List<double[]> pts = new ArrayList<>();
        double[] a = {POOL_X + 0.5, POOL_BOTTOM + 1.5, POOL_Z + 0.5}, b = {r[0] + 0.5, r[1] + 0.5, r[2] + 0.5}, c = {r[3] + 0.5, r[4] + 0.5, r[5] + 0.5};
        for (double[][] seg : new double[][][]{{a, b}, {b, c}}) {
            double len = Math.sqrt(Math.pow(seg[1][0] - seg[0][0], 2) + Math.pow(seg[1][1] - seg[0][1], 2) + Math.pow(seg[1][2] - seg[0][2], 2));
            int steps = Math.max(1, (int) Math.ceil(len * 2));
            for (int i = 0; i <= steps; i++) { double t = i / (double) steps; pts.add(new double[]{seg[0][0] + (seg[1][0] - seg[0][0]) * t, seg[0][1] + (seg[1][1] - seg[0][1]) * t, seg[0][2] + (seg[1][2] - seg[0][2]) * t}); }
        }
        return pts;
    }

    static final String[] DIVE_ORES = {"deepslate_iron_ore", "deepslate_copper_ore", "deepslate_gold_ore", "deepslate_lapis_ore", "deepslate_redstone_ore", "deepslate_iron_ore", "deepslate_diamond_ore"};

    private static void diveTunnel(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        int[] r = diveRoute(g, n);
        int bx0 = Math.min(POOL_X, r[3]) - 8, bx1 = Math.max(POOL_X, r[3]) + 8, bz0 = Math.min(POOL_Z, r[5]) - 8, bz1 = Math.max(POOL_Z, r[5]) + 8;
        if (!touchesRel(p, bx0, bz0, bx1, bz1)) return;
        List<double[]> pts = divePath(g, n);
        int i = 0;
        for (double[] q : pts) {
            int cx = (int) Math.floor(q[0]), cy = (int) Math.floor(q[1]), cz = (int) Math.floor(q[2]);
            if (touchesRel(p, cx - 2, cz - 2, cx + 2, cz + 2)) {
                for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                    double d = Math.sqrt(Math.pow(cx + dx + 0.5 - q[0], 2) + Math.pow(cy + dy + 0.5 - q[1], 2) + Math.pow(cz + dz + 0.5 - q[2], 2));
                    if (d <= 1.6) set(p, cx + dx, cy + dy, cz + dz, "water");
                }
                if (i % 12 == 0 && i > 4) set(p, cx, cy - 2, cz, "sea_lantern");
            }
            i++;
        }
        // the end: a grotto if no cave was found, and a pocket of ore in the rock round it either way
        int ex = r[3], ey = r[4], ez = r[5];
        if (!touchesRel(p, ex - 7, ez - 7, ex + 7, ez + 7)) return;
        for (int dx = -6; dx <= 6; dx++) for (int dy = -5; dy <= 5; dy++) for (int dz = -6; dz <= 6; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy * 1.6 + dz * dz);
            int x = ex + dx, y = ey + dy, z = ez + dz;
            if (!p.in(HX + x, y, HZ + z)) continue;
            if (r[6] == 0 && d <= 4.5) { set(p, x, y, z, "water"); continue; }
            if (d < 2.5 || d > 6) continue;
            var cur = p.get(HX + x, y, HZ + z);
            if (!cur.isSolid() || cur == org.bukkit.Material.SEA_LANTERN) continue;
            boolean faces = false;
            for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (p.in(HX + nx, ny, HZ + nz) && p.get(HX + nx, ny, HZ + nz) == org.bukkit.Material.WATER) faces = true;
            }
            if (!faces) continue;
            double q = u(x, y, z, 21);
            if (q < 0.35) set(p, x, y, z, DIVE_ORES[(int) (u(x, y, z, 22) * DIVE_ORES.length)]);
        }
        if (p.in(HX + ex, ey - 3, HZ + ez) && r[6] == 0) set(p, ex, ey - 5, ez, "sea_lantern");
    }


    static final String[] STALL_COLORS = {"red", "yellow", "blue", "green", "orange", "purple"};

    /** The meeting bell on the plaza's north side (villagers gather round it in the evening). */
    private static void plazaBell(Paint p) {
        if (!touchesRel(p, BELL[0] - 2, BELL[1] - 2, BELL[0] + 2, BELL[1] + 2)) return;
        set(p, BELL[0], G, BELL[1], "chiseled_stone_bricks");
        set(p, BELL[0], G + 1, BELL[1], "bell[attachment=floor,facing=north]");
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(p, BELL[0] + o[0], G, BELL[1] + o[1], "stone_brick_slab[type=bottom]");
    }

    // ---------------------------------------------------------------------------- lamps
    static final List<int[]> LAMPS = new ArrayList<>();

    static {
        for (int k = 0; k < 24; k++) {
            double a = Math.toRadians(k * 15 + 7.5);
            for (double r : new double[]{RING_IN - 1.5, RING_OUT + 1.5}) LAMPS.add(new int[]{(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)});
        }
        for (int d = 40; d <= 100; d += 8) for (int s : new int[]{-4, 4}) { LAMPS.add(new int[]{s, d}); LAMPS.add(new int[]{-d, s}); LAMPS.add(new int[]{d, s}); LAMPS.add(new int[]{s, -d}); }
        LAMPS.removeIf(l -> headroom(l[0], l[1]) < 7 || blocked(l[0], l[1]) || street(l[0], l[1]) || l[0] >= AQ_X0 - 2 && l[0] <= AQ_X1 && Math.abs(l[1]) <= 23);
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

    static String inward(int axis, int sign) { return axis == 0 ? (sign > 0 ? "west" : "east") : (sign > 0 ? "north" : "south"); }

    /** The patrol subs' berths outside the south gate: {rel x, y, rel z}. */
    static int[][] berths() { return new int[][]{{-8, G + 3, 114}, {0, G + 3, 118}, {8, G + 3, 114}, {0, G + 3, 108}}; }

    // ============================================================================ THE AGRI DOME (south-west)
    static int ax(int lx) { return (int) AGRI.cx() + lx; }
    static int az(int lz) { return (int) AGRI.cz() + lz; }
    static final int BARN_X0 = 4, BARN_X1 = 18, BARN_Z0 = -38, BARN_Z1 = -27;  // local to the Agri dome

    static boolean agriLane(int lx, int lz) {
        if (Math.abs(lx) <= 1 && lz >= -40 && lz <= 40) return true;                 // north-south
        if (Math.abs(lz) <= 1 && lx >= -40 && lx <= 40) return true;                 // east-west
        if (Math.abs(lz + 33) <= 1 && lx >= 1 && lx <= BARN_X0) return true;          // to the barn's door
        // from the hallway's mouth (24, -21) to the middle
        double t = (lx * 24 + lz * -21) / (24.0 * 24 + 21 * 21);
        return t >= 0 && t <= 1.1 && Math.abs(lx * -21 - lz * 24) / Math.hypot(24, 21) <= 1.3;
    }

    /** A field cell (relative coordinates): farmland, a water row or a glowing path. */
    static boolean farmCell(int dx, int dz) {
        int lx = dx - (int) AGRI.cx(), lz = dz - (int) AGRI.cz();
        return lz >= 4 && lz <= 40 && Math.abs(lx) >= 3 && Math.abs(lx) <= 40 && headroom(AGRI, dx, dz) > 5 && !agriLane(lx, lz)
                && !(lx >= 24 && lx <= 36 && lz >= 6 && lz <= 18); // the windmill's yard
    }

    static int farmRow(int dz) { return Math.floorMod(dz - (int) AGRI.cz(), 4); }

    static String crop(int dx, int dz) {
        int lx = dx - (int) AGRI.cx();
        int band = Math.floorMod(Math.floorDiv(lx + 40, 10), 4);
        return band == 0 ? "wheat" : band == 1 ? "carrots" : band == 2 ? "potatoes" : "beetroots";
    }

    static int maxAge(String crop) { return crop.equals("beetroots") ? 3 : 7; }

    /** The orchard the woodcutters fell (relative x, z, kind 0 oak / 1 birch / 2 spruce / 3 cherry). */
    static final List<int[]> TREES = new ArrayList<>();

    static {
        for (int gx = -36; gx <= -8; gx += 7) for (int gz = -36; gz <= -6; gz += 7) {
            int lx = gx + (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 1)) * 3) - 1, lz = gz + (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 2)) * 3) - 1;
            int x = ax(lx), z = az(lz);
            if (headroom(AGRI, x, z) < 13 || agriLane(lx, lz) || agriLane(lx + 3, lz) || agriLane(lx, lz + 3) || agriLane(lx - 3, lz) || agriLane(lx, lz - 3)) continue;
            TREES.add(new int[]{x, z, (int) (DeepGenerator.unit(DeepGenerator.hash(41, gx, gz, 3)) * 4)});
        }
    }

    /** Every block of one orchard tree: {x, y, z, block} (relative x/z). Logs first, then leaves. */
    static List<Object[]> treeBlocks(int[] t) {
        List<Object[]> out = new ArrayList<>();
        String w = switch (t[2]) { case 1 -> "birch"; case 2 -> "spruce"; case 3 -> "cherry"; default -> "oak"; };
        int h = 5 + Math.floorMod(t[0] + t[1], 3);
        for (int y = G; y < G + h; y++) out.add(new Object[]{t[0], y, t[1], w + "_log[axis=y]"});
        int cy = G + h - 1;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 2; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.6);
            if (d > 2.9 || dx == 0 && dz == 0 && dy <= 0) continue;
            if (t[2] == 2 && Math.hypot(dx, dz) > 2.6 - dy * 0.6) continue;
            if (Paint.u(HX + t[0] + dx, cy + dy, HZ + t[1] + dz, 77) < 0.08) continue;
            out.add(new Object[]{t[0] + dx, cy + dy, t[1] + dz, w + "_leaves[persistent=true]"});
        }
        out.add(new Object[]{t[0], cy + 1, t[1], w + "_leaves[persistent=true]"});
        return out;
    }

    static String sapling(int[] t) { return switch (t[2]) { case 1 -> "birch_sapling"; case 2 -> "spruce_sapling"; case 3 -> "cherry_sapling"; default -> "oak_sapling"; }; }

    /** The barn's bunks: {rel x, rel z} of each head (8). */
    static int[][] agriBeds() {
        List<int[]> b = new ArrayList<>();
        for (int lx = BARN_X0 + 2; lx <= BARN_X1 - 2; lx += 2) b.add(new int[]{ax(lx), az(BARN_Z0 + 1), 0});
        b.add(new int[]{ax(12), az(BARN_Z1 - 1), 1}); b.add(new int[]{ax(14), az(BARN_Z1 - 1), 1});
        return b.toArray(new int[0][]);
    }

    /** The woodcutters' log pile + chopping block (rel). */
    static final int[] LOG_PILE = {ax(-4), az(-24)};
    static final int[] CHOP = {ax(-3), az(-21)};

    private static void agri(Paint p) {
        int cx = (int) AGRI.cx(), cz = (int) AGRI.cz();
        if (!touchesRel(p, cx - 50, cz - 50, cx + 50, cz + 50)) return;
        // lanes (gravel paths with a lamp every 8)
        for (int lx = -44; lx <= 44; lx++) for (int lz = -44; lz <= 44; lz++) {
            int x = cx + lx, z = cz + lz;
            if (!p.in(HX + x, HF, HZ + z) || headroom(AGRI, x, z) < 3 || AGRI.e(x + 0.5, G + 0.5, z + 0.5) > AGRI.in()) continue;
            if (agriLane(lx, lz)) { set(p, x, HF, z, (lx + lz) % 5 == 0 ? "coarse_dirt" : "dirt_path"); set(p, x, G, z, "air"); set(p, x, G + 1, z, "air"); continue; }
            if (farmCell(x, z)) {
                int row = farmRow(z);
                set(p, x, G, z, "air"); set(p, x, G + 1, z, "air");
                if (row == 0) set(p, x, HF, z, "pearlescent_froglight");
                else if (row == 2) set(p, x, HF, z, "water");
                else {
                    set(p, x, HF, z, "farmland[moisture=7]");
                    String c = crop(x, z);
                    set(p, x, G, z, c + "[age=" + (int) (u(x, G, z, 8) * (maxAge(c) + 1)) + "]");
                }
            } else if (lz >= 2 && lz <= 42 && Math.abs(lx) >= 2 && Math.abs(lx) <= 41 && headroom(AGRI, x, z) > 5 && !(lx >= 24 && lx <= 36 && lz >= 6 && lz <= 18)) {
                set(p, x, G, z, "air"); // the fields' edge: bare
            }
        }
        for (int k = -38; k <= 38; k += 8) for (int[] l : new int[][]{{2, k}, {k, 2}}) {
            int x = cx + l[0], z = cz + l[1];
            if (headroom(AGRI, x, z) < 6 || agriLane(l[0], l[1]) || farmCell(x, z)) continue;
            set(p, x, G, z, "spruce_fence"); set(p, x, G + 1, z, "spruce_fence"); set(p, x, G + 2, z, "lantern[hanging=false]");
        }
        // the orchard
        for (int[] t : TREES) if (touchesRel(p, t[0] - 4, t[1] - 4, t[0] + 4, t[1] + 4)) {
            for (Object[] b : treeBlocks(t)) set(p, (int) b[0], (int) b[1], (int) b[2], (String) b[3]);
            set(p, t[0] + 1, HF, t[1] + 1, "sea_lantern");
        }
        set(p, CHOP[0], G, CHOP[1], "stripped_oak_log[axis=y]");
        for (int x = LOG_PILE[0] - 2; x <= LOG_PILE[0] + 1; x++) for (int y = G; y <= G + 1; y++) set(p, x, y, LOG_PILE[1], "oak_log[axis=x]");
        // beehives on posts round the orchard's edge, flowers under them
        for (int[] h : new int[][]{{-8, -2}, {-16, -2}, {-24, -2}, {-32, -2}}) {
            set(p, ax(h[0]), G, az(h[1]), "spruce_fence"); set(p, ax(h[0]), G + 1, az(h[1]), "beehive[facing=south,honey_level=3]");
            set(p, ax(h[0]) + 1, G, az(h[1]), "cornflower"); set(p, ax(h[0]) - 1, G, az(h[1]), "allium");
        }
        // the barn (bunks for the farmers and woodcutters), hay, a silo, a windmill
        hall(p, ax(BARN_X0), az(BARN_Z0), ax(BARN_X1), az(BARN_Z1), ax(BARN_X0), "red_terracotta", "stripped_spruce_log[axis=y]", "spruce_planks", "dark_oak_planks");
        for (int[] b : agriBeds()) {
            boolean north = b[2] == 0;
            set(p, b[0], G, b[1], "red_bed[facing=" + (north ? "north" : "south") + ",part=head]");
            set(p, b[0], G, b[1] + (north ? 1 : -1), "red_bed[facing=" + (north ? "north" : "south") + ",part=foot]");
        }
        for (int lx = BARN_X0 + 1; lx <= BARN_X0 + 3; lx++) set(p, ax(lx), G, az(BARN_Z1 - 1), "hay_block[axis=y]");
        set(p, ax(BARN_X1 - 1), G, az(-28), "composter"); set(p, ax(BARN_X1 - 1), G, az(-29), "barrel[facing=up]");
        silo(p, ax(22), az(-6));
        windmill(p, ax(30), az(12));
        for (int[] h : new int[][]{{8, -20}, {9, -20}, {8, -19}}) set(p, ax(h[0]), G, az(h[1]), "hay_block[axis=y]");
    }

    private static void silo(Paint p, int x, int z) {
        if (!touchesRel(p, x - 5, z - 5, x + 5, z + 5)) return;
        int h = 14;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            double r = Math.hypot(dx, dz);
            if (r > 3.3) continue;
            for (int y = G; y <= G + h; y++) set(p, x + dx, y, z + dz, r > 2.3 ? (Math.floorMod(y, 4) == 0 ? "iron_block" : "white_terracotta") : "hay_block[axis=y]");
            int cap = (int) Math.round(3.3 - r);
            for (int k = 0; k <= cap; k++) set(p, x + dx, G + h + 1 + k, z + dz, "waxed_cut_copper");
        }
    }

    private static void windmill(Paint p, int x, int z) {
        if (!touchesRel(p, x - 7, z - 7, x + 7, z + 7)) return;
        int h = 11;
        for (int y = G; y <= G + h; y++) {
            int r = y < G + 5 ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                set(p, x + dx, y, z + dz, !edge ? "air" : (Math.abs(dx) == r && Math.abs(dz) == r) ? "stripped_spruce_log[axis=y]" : y == G + 3 && dz == 0 && dx == -r ? "glass_pane" : "white_terracotta");
            }
        }
        set(p, x - 2, G, z, "spruce_door[facing=east,half=lower,hinge=left,open=false]"); set(p, x - 2, G + 1, z, "spruce_door[facing=east,half=upper,hinge=left,open=false]");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) set(p, x + dx, G + h + 1, z + dz, Math.abs(dx) + Math.abs(dz) <= 3 ? "dark_oak_planks" : "air");
        set(p, x, G + h + 2, z, "dark_oak_slab[type=bottom]");
        // the sails, on the north face
        int hy = G + h - 1, fz = z - 2;
        set(p, x, hy, fz, "dark_oak_log[axis=z]");
        for (int k = 1; k <= 5; k++) for (int[] d : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
            if (hy + d[1] * k < G + 1) continue;
            set(p, x + d[0] * k, hy + d[1] * k, fz - 1, "spruce_fence");
            if (k >= 2) set(p, x + d[0] * k + d[0], hy + d[1] * k, fz - 1, "white_wool");
        }
    }

    // ============================================================================ THE GUARD DOME (south-east)
    static int gx(int lx) { return (int) GUARD.cx() + lx; }
    static int gz(int lz) { return (int) GUARD.cz() + lz; }
    static final int BAR_X0 = -6, BAR_X1 = 16, BAR_Z0 = 4, BAR_Z1 = 22;     // barracks, local
    static final int ARM_X0 = -22, ARM_X1 = -10, ARM_Z0 = 6, ARM_Z1 = 18;   // armory, local
    static final int[] TOWER = {24, 12};

    static boolean guardLane(int lx, int lz) {
        if (Math.abs(lz - 1) <= 1 && lx >= -24 && lx <= 26) return true;
        if (Math.abs(lx + 8) <= 1 && lz >= 1 && lz <= 14) return true;
        double t = (lx * -24 + lz * -21) / (24.0 * 24 + 21 * 21);
        return t >= 0 && t <= 1.1 && Math.abs(lx * -21 - lz * -24) / Math.hypot(24, 21) <= 1.3;
    }

    /** The barracks' beds: {rel x, rel z, 0 north / 1 south} (10). */
    static int[][] guardBeds() {
        List<int[]> b = new ArrayList<>();
        for (int lx = BAR_X0 + 4; lx <= BAR_X1 - 2; lx += 4) { b.add(new int[]{gx(lx), gz(BAR_Z0 + 1), 0}); b.add(new int[]{gx(lx), gz(BAR_Z1 - 1), 1}); }
        return b.toArray(new int[0][]);
    }

    /** Training dummies (hay + target) and the archery targets: {rel x, rel z}. */
    static final int[][] DUMMIES = {{gx(-2), gz(-10)}, {gx(2), gz(-10)}, {gx(6), gz(-10)}, {gx(10), gz(-10)}};
    static final int[][] TARGETS = {{gx(14), gz(-26)}, {gx(14), gz(-22)}, {gx(14), gz(-18)}};

    private static void guard(Paint p) {
        int cx = (int) GUARD.cx(), cz = (int) GUARD.cz();
        if (!touchesRel(p, cx - 44, cz - 44, cx + 44, cz + 44)) return;
        for (int lx = -40; lx <= 40; lx++) for (int lz = -40; lz <= 40; lz++) {
            int x = cx + lx, z = cz + lz;
            if (!p.in(HX + x, HF, HZ + z) || GUARD.e(x + 0.5, G + 0.5, z + 0.5) > GUARD.in()) continue;
            if (guardLane(lx, lz)) { set(p, x, HF, z, "polished_andesite"); set(p, x, G, z, "air"); set(p, x, G + 1, z, "air"); }
            else if (lx >= -12 && lx <= 16 && lz >= -16 && lz <= -4) { set(p, x, HF, z, (lx + lz) % 3 == 0 ? "gravel" : "coarse_dirt"); set(p, x, G, z, "air"); set(p, x, G + 1, z, "air"); }
            else if (lx >= -12 && lx <= 18 && lz >= -30 && lz <= -17) { set(p, x, HF, z, "packed_mud"); set(p, x, G, z, "air"); set(p, x, G + 1, z, "air"); }
        }
        // the training yard: dummies on posts, fences round it
        for (int[] d : DUMMIES) { set(p, d[0], G, d[1], "oak_fence"); set(p, d[0], G + 1, d[1], "hay_block[axis=y]"); set(p, d[0], G + 2, d[1], "carved_pumpkin[facing=south]"); }
        for (int lx = -12; lx <= 16; lx++) { if (Math.abs(lx + 8) > 1 && !guardLane(lx, -3)) set(p, gx(lx), G, gz(-3), "spruce_fence"); }
        // the archery range: targets at one end, a shooting line at the other
        for (int[] t : TARGETS) { set(p, t[0], G, t[1], "hay_block[axis=y]"); set(p, t[0], G + 1, t[1], "target"); }
        for (int lz = -28; lz <= -16; lz++) set(p, gx(-10), G, gz(lz), "spruce_slab[type=bottom]");
        // the barracks + the armory
        hall(p, gx(BAR_X0), gz(BAR_Z0), gx(BAR_X1), gz(BAR_Z1), gx(BAR_X0), "stone_bricks", "polished_deepslate", "spruce_planks", "deepslate_tiles");
        for (int[] b : guardBeds()) {
            boolean north = b[2] == 0;
            set(p, b[0], G, b[1], "blue_bed[facing=" + (north ? "north" : "south") + ",part=head]");
            set(p, b[0], G, b[1] + (north ? 1 : -1), "blue_bed[facing=" + (north ? "north" : "south") + ",part=foot]");
        }
        for (int z = gz(BAR_Z0 + 4); z <= gz(BAR_Z1 - 4); z += 4) set(p, gx(BAR_X1 - 1), G + 3, z, "blue_wall_banner[facing=west]");
        hall(p, gx(ARM_X0), gz(ARM_Z0), gx(ARM_X1), gz(ARM_Z1), gx(ARM_X1), "polished_andesite", "dark_oak_log[axis=y]", "stone_bricks", "deepslate_tiles");
        set(p, gx(ARM_X0 + 2), G, gz(ARM_Z0 + 2), "anvil[facing=north]"); set(p, gx(ARM_X0 + 3), G, gz(ARM_Z0 + 2), "smithing_table");
        set(p, gx(ARM_X0 + 4), G, gz(ARM_Z0 + 2), "grindstone[face=floor,facing=north]");
        for (int lz = ARM_Z0 + 2; lz <= ARM_Z1 - 2; lz += 2) { set(p, gx(ARM_X0 + 1), G, gz(lz), "barrel[facing=east]"); set(p, gx(ARM_X0 + 1), G + 1, gz(lz), "barrel[facing=east]"); }
        // the watchtower (ladder inside)
        int tx = gx(TOWER[0]), tz = gz(TOWER[1]), th = 13;
        for (int y = G; y <= G + th; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            boolean mid = dx == 0 && dz == 0;
            set(p, tx + dx, y, tz + dz, mid ? (y < G + th ? "ladder[facing=north]" : "spruce_trapdoor[facing=north,half=top,open=false]")
                    : dx == 0 && dz == -1 && y <= G + 1 ? "air" : Math.abs(dx) + Math.abs(dz) == 2 ? "stripped_spruce_log[axis=y]" : "stone_bricks");
        }
        set(p, tx, G, tz - 1, "spruce_door[facing=south,half=lower,hinge=left,open=false]"); set(p, tx, G + 1, tz - 1, "spruce_door[facing=south,half=upper,hinge=left,open=false]");
        set(p, tx, G, tz, "air"); set(p, tx, G + 1, tz, "ladder[facing=north]");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0) continue;
            set(p, tx + dx, G + th, tz + dz, "spruce_planks");
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) set(p, tx + dx, G + th + 1, tz + dz, "spruce_fence");
        }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) { set(p, tx + c[0], G + th + 2, tz + c[1], "spruce_fence"); }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) set(p, tx + dx, G + th + 3, tz + dz, "dark_oak_slab[type=bottom]");
        set(p, tx, G + th + 2, tz, "lantern[hanging=true]");
        // the flag pole
        for (int y = G; y <= G + 8; y++) set(p, gx(4), y, gz(-2), "iron_bars");
        set(p, gx(4), G + 9, gz(-2), "sea_lantern");
        set(p, gx(5), G + 8, gz(-2), "blue_wall_banner[facing=east]");
        for (int k = -24; k <= 24; k += 8) { int x = gx(k), z = gz(3); if (!guardLane(k, 3)) { set(p, x, G, z, "polished_deepslate_wall"); set(p, x, G + 1, z, "iron_bars"); set(p, x, G + 2, z, "sea_lantern"); } }
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


    private static void dockOffice(Paint p) {
        hall(p, DOCK_X0, DOCK_Z0, DOCK_X1, DOCK_Z1, DOCK_X0, "light_gray_concrete", "waxed_cut_copper", "polished_andesite", "waxed_cut_copper");
        if (!p.touches(HX + DOCK_X0, HZ + DOCK_Z0, HX + DOCK_X1, HZ + DOCK_Z1)) return;
        for (int z = DOCK_Z0 + 2; z <= DOCK_Z1 - 2; z++) set(p, DOCK_X0 + 7, G, z, "waxed_copper_block");
        set(p, DOCK_X1 - 1, G, DOCK_Z0 + 1, "cartography_table");
        set(p, DOCK_X1 - 1, G, DOCK_Z1 - 1, "anvil[facing=north]");
        set(p, DOCK_X1 - 1, G + 2, (DOCK_Z0 + DOCK_Z1) / 2, "sea_lantern");
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


    static int[][] stalls() { // {rel x, rel z (the counter's middle), side}
        return new int[][]{{-62, -6, -1}, {-72, -6, -1}, {-82, -6, -1}, {-62, 6, 1}, {-72, 6, 1}, {-82, 6, 1}};
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
