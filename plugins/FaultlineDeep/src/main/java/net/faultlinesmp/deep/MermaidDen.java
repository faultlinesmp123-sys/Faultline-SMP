package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * THE MERMAID DEN (Deep 1.7.0): the mermaids' city, in the Sunlit Sea round MX, MZ (beyond the old world border: the
 * border is 2000 now). A crater of living reef: a ring wall of coral rises from the deep sea floor almost to the surface,
 * cut by four gates (on the axes), and inside it a lagoon floor of pale sand ~40 blocks under the surface.
 *
 *  - THE PALACE in the middle: a raised round plaza with a mosaic floor (a pearl at its heart, eight spokes), tiers of
 *    seats all round it, Coralia's throne on the north side under a giant scallop shell, and a colonnade of sixteen
 *    columns holding up a floating crown ring hung with jelly lamps. This is where she rises (her arena).
 *  - HOUSES on three rings: conch-shell spirals, pearl-domed cottages and coral towers in pink, lilac, aqua and peach,
 *    with round windows, lamps inside, and a chest in some (Dark Loot, like every chest down here)
 *  - avenues from the plaza to the gates and two ring roads, lined with glowing jelly lamps
 *  - giant coral trees, giant open clams with a pearl, coral gardens, sea pickles, seagrass, a kelp forest belt inside
 *    the wall, and the reef wall itself alive with coral
 *
 * Pure functions of the position (the layout doesn't depend on the seed, so Den.java knows where the houses are).
 */
final class MermaidDen {
    static final int MX = 1200, MZ = 700, R = 150, RIM = 172, BLEND = 55;
    static final int FLOOR = DeepGenerator.SEA - 42;  // the lagoon floor (top block)
    static final int PLAZA = FLOOR + 3;                // the palace plateau's top
    static final int RIDGE = DeepGenerator.SEA - 10;   // the reef wall's crest
    static final int PALACE_R = 30;

    private MermaidDen() { }

    /** Close enough that another feature (a blue hole, a ruin, a sea stack) should keep away. Cheap: distance only. */
    static boolean near(int x, int z, int extra) { return Math.hypot(x - MX, z - MZ) < RIM + BLEND + 20 + extra; }

    static double wobble(double a) { return 7 * Math.sin(a * 5 + 1.3) + 4 * Math.sin(a * 11 + 0.4); }

    /** 1 inside the reef wall, easing to 0 down its outer cliff. */
    static double weight(int x, int z) {
        double d = Math.hypot(x - MX, z - MZ);
        if (d > RIM + BLEND + 12) return 0;
        d -= wobble(Math.atan2(z - MZ, x - MX)) * 0.6;
        if (d <= RIM) return 1;
        if (d >= RIM + BLEND) return 0;
        double t = 1 - (d - RIM) / BLEND;
        return t * t * (3 - 2 * t);
    }

    /** How far into a gate corridor (the four axis notches through the wall): 1 in the middle, 0 outside. */
    static double gate(int x, int z) {
        double g = Math.min(Math.abs(x - MX), Math.abs(z - MZ));
        if (g >= 11) return 0;
        if (g <= 7) return 1;
        double t = (11 - g) / 4;
        return t * t * (3 - 2 * t);
    }

    /** The floor (top solid block) inside the Den. */
    static double floor(DeepGenerator.Noise n, int x, int z) {
        double dx = x - MX, dz = z - MZ, d = Math.hypot(dx, dz), a = Math.atan2(dz, dx);
        if (d < PALACE_R) return PLAZA;
        if (d < PALACE_R + 4) return PLAZA - (d - PALACE_R) * 0.75;   // the plateau's edge
        if (d < R) return FLOOR + n.n2(n.dunes, x * 1.6, z * 1.6) * 1.1 + (d > 64 ? 1 : 0) + (d > 104 ? 1 : 0);   // gentle terraces
        double t = Math.max(0, Math.min(1, (d - wobble(a) * 0.6 - R) / (RIM - R)));
        t = t * t * (3 - 2 * t);
        double ridge = RIDGE + 3 * Math.sin(a * 7) + n.n2(n.dunes, x * 2.0, z * 2.0) * 2;
        ridge += (FLOOR + 2 - ridge) * gate(x, z); // the gates
        return FLOOR + 2 + (ridge - FLOOR - 2) * t;
    }

    // =========================================================================================== blocks
    static final Material[] CORAL_BLOCKS = {Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK,
            Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK};
    static final Material[] CORALS = {Material.TUBE_CORAL, Material.BRAIN_CORAL, Material.BUBBLE_CORAL, Material.FIRE_CORAL, Material.HORN_CORAL,
            Material.TUBE_CORAL_FAN, Material.BRAIN_CORAL_FAN, Material.BUBBLE_CORAL_FAN, Material.FIRE_CORAL_FAN, Material.HORN_CORAL_FAN};

    static boolean avenue(int x, int z) {
        double d = Math.hypot(x - MX, z - MZ);
        return d > PALACE_R + 2 && d < R + 8 && (Math.abs(x - MX) <= 2 || Math.abs(z - MZ) <= 2);
    }

    static boolean ringRoad(int x, int z) {
        double d = Math.hypot(x - MX, z - MZ);
        return d >= 74 && d < 77 || d >= 113 && d < 116;
    }

    /** The top block of a column. */
    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        double d = Math.hypot(x - MX, z - MZ);
        if (avenue(x, z)) return Math.abs(x - MX) == 2 || Math.abs(z - MZ) == 2 ? Material.PRISMARINE_BRICKS : Material.SMOOTH_SANDSTONE;
        if (ringRoad(x, z)) return Math.floorMod(x * 31 + z * 17, 23) == 0 ? Material.SEA_LANTERN : Material.PRISMARINE_BRICKS;
        if (d >= R - 2) { // the reef wall: living coral
            int r = rnd.nextInt(10);
            return r < 6 ? CORAL_BLOCKS[rnd.nextInt(CORAL_BLOCKS.length)] : r < 8 ? Material.PRISMARINE : r < 9 ? Material.DARK_PRISMARINE : Material.SAND;
        }
        double patch = n.n2(n.dunes, x * 0.7 + 900, z * 0.7);
        if (patch > 0.42) return CORAL_BLOCKS[Math.floorMod(x / 3 + z / 3, CORAL_BLOCKS.length)];   // coral gardens
        int r = rnd.nextInt(100);
        if (r < 6) return Material.CALCITE;
        if (r < 12) return Material.SMOOTH_SANDSTONE;
        return Material.SAND;
    }

    static Material under(Random rnd, int y, int up) { return y > up - 3 ? Material.SAND : Material.SANDSTONE; }

    /** Seagrass, sea pickles (their glow lights the lagoon), corals, a kelp forest round the inside of the wall. */
    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        if (up + 2 > DeepGenerator.SEA || d.getType(lx, up + 1, lz) != Material.WATER) return;
        double dd = Math.hypot(x - MX, z - MZ);
        if (dd < PALACE_R + 4 || avenue(x, z) || ringRoad(x, z)) return;
        Material topM = d.getType(lx, up, lz);
        if (!topM.isSolid()) return;
        int r = rnd.nextInt(1000);
        if (dd > R - 34 && dd < R - 4 && n.n2(n.dunes, x * 0.4, z * 0.4 + 300) > -0.1 && r < 380) { // the kelp forest
            int h = 6 + rnd.nextInt(18);
            for (int i = 1; i <= h && up + i < DeepGenerator.SEA - 1; i++) d.setBlock(lx, up + i, lz, i == h ? Material.KELP : Material.KELP_PLANT);
            return;
        }
        if (dd >= R - 2 || n.n2(n.dunes, x * 0.7 + 900, z * 0.7) > 0.42) { // on the reef / in a coral garden
            if (r < 420) d.setBlock(lx, up + 1, lz, Paint.bd(CORALS[rnd.nextInt(CORALS.length)].getKey().getKey()));
            else if (r < 470) d.setBlock(lx, up + 1, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(4)) + ",waterlogged=true]"));
            return;
        }
        if (r < 150) d.setBlock(lx, up + 1, lz, Material.SEAGRASS);
        else if (r < 175) { d.setBlock(lx, up + 1, lz, Paint.bd("tall_seagrass[half=lower]")); d.setBlock(lx, up + 2, lz, Paint.bd("tall_seagrass[half=upper]")); }
        else if (r < 196) d.setBlock(lx, up + 1, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(4)) + ",waterlogged=true]"));
        else if (r < 206) d.setBlock(lx, up + 1, lz, Paint.bd(CORALS[rnd.nextInt(CORALS.length)].getKey().getKey()));
    }

    // =========================================================================================== the layout
    /** A house: centre, which way its door faces (toward the plaza), its type and colours. */
    record House(int x, int z, int rot, int type, int palette) { }

    static final List<House> HOUSES = new ArrayList<>();
    static final List<int[]> TREES = new ArrayList<>(), LAMPS = new ArrayList<>(), CLAMS = new ArrayList<>();

    static {
        int[][] rings = {{52, 10}, {94, 14}, {134, 14}};
        int i = 0;
        for (int[] ring : rings) {
            for (int k = 0; k < ring[1]; k++) {
                double a = (k + 0.5) * 2 * Math.PI / ring[1] + ring[0] * 0.01;
                double deg = Math.toDegrees(a) % 90;
                if (deg < 9 || deg > 81) continue; // keep the avenues clear
                int x = MX + (int) Math.round(Math.cos(a) * ring[0]), z = MZ + (int) Math.round(Math.sin(a) * ring[0]);
                // the door faces the plaza: the frame's front is -z; r turns it clockwise (r = 1: front faces east)
                double fx = MX - x, fz = MZ - z;
                int rot = Math.abs(fx) > Math.abs(fz) ? (fx > 0 ? 1 : 3) : (fz > 0 ? 2 : 0);
                HOUSES.add(new House(x, z, rot, i % 3, (i * 7 + k) % 4));
                i++;
            }
        }
        for (int k = 0; k < 18; k++) { // coral trees between the rings
            double a = (k + 0.25) * 2 * Math.PI / 18, rr = k % 2 == 0 ? 73 : 114;
            rr += k % 3 == 0 ? -9 : 9;
            TREES.add(new int[]{MX + (int) Math.round(Math.cos(a) * rr), MZ + (int) Math.round(Math.sin(a) * rr), k});
        }
        for (int s = -1; s <= 1; s += 2) for (int dist = 38; dist <= R; dist += 14) { // lamps along the avenues, both sides
            LAMPS.add(new int[]{MX + 5, MZ + s * dist}); LAMPS.add(new int[]{MX - 5, MZ + s * dist});
            LAMPS.add(new int[]{MX + s * dist, MZ + 5}); LAMPS.add(new int[]{MX + s * dist, MZ - 5});
        }
        for (int k = 0; k < 24; k++) { // and round the ring roads
            double a = (k + 0.5) * 2 * Math.PI / 24;
            LAMPS.add(new int[]{MX + (int) Math.round(Math.cos(a) * 79), MZ + (int) Math.round(Math.sin(a) * 79)});
            LAMPS.add(new int[]{MX + (int) Math.round(Math.cos(a + 0.13) * 118), MZ + (int) Math.round(Math.sin(a + 0.13) * 118)});
        }
        double[][] clam = {{0.5, 64}, {1.9, 66}, {3.3, 105}, {4.4, 104}, {5.6, 140}, {2.6, 141}};
        for (double[] c : clam) CLAMS.add(new int[]{MX + (int) Math.round(Math.cos(c[0]) * c[1]), MZ + (int) Math.round(Math.sin(c[0]) * c[1])});
    }

    /** The throne (where Coralia rises) and the middle of the plaza. */
    static int[] throne() { return new int[]{MX, PLAZA + 3, MZ - 19}; }

    // =========================================================================================== drawing
    static final String[][] PALETTES = {
            {"pink_terracotta", "white_terracotta", "pink_stained_glass", "magenta_terracotta"},           // coral pink
            {"magenta_terracotta", "pink_terracotta", "magenta_stained_glass", "purple_terracotta"},      // lilac
            {"light_blue_terracotta", "white_terracotta", "light_blue_stained_glass", "cyan_terracotta"},  // aqua
            {"orange_terracotta", "white_terracotta", "orange_stained_glass", "yellow_terracotta"}};       // peach

    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!near(p.x0 + 8, p.z0 + 8, 10)) return;
        if (p.touches(MX - 40, MZ - 40, MX + 40, MZ + 40)) palace(p);
        for (House h : HOUSES) if (p.touches(h.x - 9, h.z - 9, h.x + 9, h.z + 9)) house(g, n, p, h);
        for (int[] t : TREES) if (p.touches(t[0] - 9, t[1] - 9, t[0] + 9, t[1] + 9)) tree(g, n, p, t[0], t[1], t[2]);
        for (int[] l : LAMPS) if (p.touches(l[0] - 2, l[1] - 2, l[0] + 2, l[1] + 2)) lamp(p, l[0], g.upperFloor(n, l[0], l[1]) + 1, l[1]);
        for (int[] c : CLAMS) if (p.touches(c[0] - 6, c[1] - 6, c[0] + 6, c[1] + 6)) clam(g, n, p, c[0], c[1]);
        for (int k = 0; k < 4; k++) {
            int gx = MX + (k == 0 ? R + 6 : k == 1 ? -(R + 6) : 0), gz = MZ + (k == 2 ? R + 6 : k == 3 ? -(R + 6) : 0);
            if (p.touches(gx - 12, gz - 12, gx + 12, gz + 12)) gateArch(p, gx, gz, k < 2);
        }
    }

    // ---- the palace
    static void palace(Paint p) {
        int y0 = PLAZA;
        for (int x = MX - 34; x <= MX + 34; x++) for (int z = MZ - 34; z <= MZ + 34; z++) {
            if (!p.in(x, y0, z)) continue;
            int dx = x - MX, dz = z - MZ;
            double r = Math.hypot(dx, dz), a = Math.toDegrees(Math.atan2(dz, dx));
            if (r <= 16.5) { // the mosaic: a pearl at the heart, a quartz ring, eight spokes, a prismarine field
                String m;
                if (r <= 2.2) m = "pearlescent_froglight";
                else if (r <= 4.2) m = "quartz_block";
                else if (Math.abs(((a % 45) + 45) % 45 - 22.5) > 20.5) m = r > 13 ? "sea_lantern" : "dark_prismarine";
                else if (r >= 9 && r < 10.5) m = "prismarine_bricks";
                else if (r >= 15) m = "quartz_bricks";
                else m = ((int) (r * 2) + (int) (a / 15)) % 7 == 0 ? "light_blue_glazed_terracotta" : "prismarine";
                p.set(x, y0, z, m);
            } else if (r <= 27) { // tiers of seats, aisles at the four axes (none on the north: the throne)
                boolean aisle = (Math.abs(dx) <= 2 && dz > 0) || Math.abs(dz) <= 2;
                p.set(x, y0, z, "quartz_bricks");
                if (aisle) continue;
                int tier = (int) ((r - 16.5) / 2.1);
                for (int y = 1; y <= tier; y++) p.set(x, y0 + y, z, y == tier ? (tier % 2 == 0 ? "dark_prismarine" : "prismarine_bricks") : "prismarine_bricks");
            }
        }
        // the colonnade and its floating crown ring, hung with jelly lamps
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            int cx = MX + (int) Math.round(Math.cos(a) * 29), cz = MZ + (int) Math.round(Math.sin(a) * 29);
            for (int y = 1; y <= 17; y++) p.set(cx, y0 + y, cz, y == 17 ? "sea_lantern" : y == 1 || y == 16 ? "chiseled_quartz_block" : "quartz_pillar");
            p.set(cx, y0, cz, "quartz_bricks");
        }
        for (int i = 0; i < 360; i++) {
            double a = Math.toRadians(i);
            for (double rr = 27.5; rr <= 30.5; rr += 1) p.set(MX + (int) Math.round(Math.cos(a) * rr), y0 + 18, MZ + (int) Math.round(Math.sin(a) * rr), rr == 30.5 ? "prismarine_bricks" : "dark_prismarine");
        }
        for (int k = 0; k < 8; k++) {
            double a = (k + 0.5) * Math.PI / 4;
            int lx = MX + (int) Math.round(Math.cos(a) * 29), lz = MZ + (int) Math.round(Math.sin(a) * 29);
            for (int y = 15; y <= 17; y++) p.set(lx, y0 + y, lz, Paint.wet("iron_chain[axis=y]"));
            p.set(lx, y0 + 14, lz, "sea_lantern");
            p.set(lx, y0 + 13, lz, "pink_stained_glass");
        }
        // the throne: a dais, a quartz seat, and a giant scallop shell behind it with a great pearl
        int tz = MZ - 19;
        for (int x = MX - 5; x <= MX + 5; x++) for (int z = tz - 3; z <= tz + 3; z++) {
            p.set(x, y0 + 1, z, "quartz_block");
            if (Math.abs(x - MX) <= 3 && z <= tz + 1) p.set(x, y0 + 2, z, "smooth_quartz");
        }
        for (int x = MX - 3; x <= MX + 3; x++) p.set(x, y0 + 2, tz + 2, Paint.wet("quartz_stairs[facing=north]"));
        p.set(MX, y0 + 3, tz, Paint.wet("quartz_stairs[facing=north]"));
        p.set(MX - 1, y0 + 3, tz, Paint.wet("quartz_stairs[facing=east]"));
        p.set(MX + 1, y0 + 3, tz, Paint.wet("quartz_stairs[facing=west]"));
        for (int y = 3; y <= 6; y++) p.set(MX, y0 + y, tz - 1, y == 6 ? "gold_block" : "quartz_pillar");
        int sz = MZ - 23, sy = y0 + 3;
        for (int x = MX - 12; x <= MX + 12; x++) for (int y = sy; y <= sy + 13; y++) {
            double dx = x - MX, dy = y - sy, rr = Math.hypot(dx, dy);
            if (rr > 12.3) continue;
            double ang = Math.toDegrees(Math.atan2(dy, dx));
            boolean rib = ((int) Math.floor(ang / 12)) % 2 == 0;
            boolean scallop = rr > 11.2 && ((int) (ang / 6)) % 2 == 0;
            if (rr > 11.2 && !scallop) continue;
            p.set(x, y, sz, rr < 2.5 ? "pearlescent_froglight" : rib ? "pink_terracotta" : "white_terracotta");
            if (rr > 4 && rr < 11 && rib) p.set(x, y, sz + 1, "pink_terracotta"); // the ribs stand out
        }
        for (int x = MX - 1; x <= MX + 1; x++) for (int y = sy + 1; y <= sy + 3; y++) for (int z = sz - 2; z <= sz - 1; z++) p.set(x, y, z, "pearlescent_froglight"); // the great pearl
    }

    // ---- houses
    static void house(DeepGenerator g, DeepGenerator.Noise n, Paint p, House h) {
        int base = g.upperFloor(n, h.x, h.z) + 1;
        String[] c = PALETTES[h.palette];
        Paint.Frame f = p.frame(h.x, h.z, h.rot, true);
        // a footing so it never hangs over a terrace step
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) if (x * x + z * z <= 26) { f.set(x, base - 1, z, "smooth_sandstone"); f.set(x, base - 2, z, "sandstone"); }
        long hh = DeepGenerator.hash(0x4D45524DL, h.x, h.z, 7);
        switch (h.type) {
            case 0 -> conch(f, base, c, hh);
            case 1 -> pearlDome(f, base, c, hh);
            default -> coralTower(f, base, c, hh);
        }
    }

    /** A conch-shell spiral: stacked rings narrowing as they rise, striped, a door and round windows, a lamp inside. */
    static void conch(Paint.Frame f, int y0, String[] c, long hh) {
        int H = 9;
        for (int y = 0; y <= H; y++) {
            double r = 4.6 - y * 0.45;
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                double d = Math.hypot(x, z);
                if (Math.abs(d - r) > 0.75) continue;
                double ang = Math.atan2(z, x);
                boolean stripe = ((int) Math.floor((ang + Math.PI) / (Math.PI / 4) + y * 0.5)) % 2 == 0;
                boolean door = z < 0 && Math.abs(x) <= 1 && y <= 2;
                boolean window = y == 3 && Math.abs(z) <= 1 && Math.abs(x) >= 3;
                if (door) continue;
                f.set(x, y0 + y, z, window ? c[2] : stripe ? c[0] : c[1]);
            }
            if (y == 0) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (x * x + z * z <= 10) f.set(x, y0 - 1, z, "smooth_quartz");
        }
        f.set(0, y0 + H + 1, 0, c[3]);
        f.set(0, y0 + H + 2, 0, "pearlescent_froglight");
        f.set(0, y0 + 5, 0, "sea_lantern");
        f.set(0, y0, 2, Paint.wet("sea_pickle[pickles=3]"));
        if ((hh & 3) == 0) f.set(-2, y0, 1, "chest[facing=east]");
        f.set(2, y0, 1, c[0].replace("terracotta", "carpet"));
    }

    /** A cottage with a pearl dome: round calcite walls, round windows, a dome banded with nacre, an arched door. */
    static void pearlDome(Paint.Frame f, int y0, String[] c, long hh) {
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            double d = Math.hypot(x, z);
            if (d <= 4.5) f.set(x, y0 - 1, z, "smooth_quartz");
            for (int y = 0; y <= 8; y++) {
                boolean wall = y <= 3 && Math.abs(d - 4.2) <= 0.6;
                double s = Math.sqrt(d * d + Math.pow(y - 3, 2) * 1.6);
                boolean dome = y >= 4 && Math.abs(s - 4.4) <= 0.6;
                if (!wall && !dome) continue;
                boolean door = z < 0 && Math.abs(x) <= 1 && y <= 2;
                if (door) continue;
                boolean window = y == 2 && (Math.abs(x) <= 0 || Math.abs(z) <= 0) && !(z < 0 && x == 0);
                String m = window ? c[2] : dome ? (y == 6 ? "pearlescent_froglight" : y == 4 ? c[3] : c[0]) : y == 0 ? c[3] : "calcite";
                f.set(x, y0 + y, z, m);
            }
        }
        f.set(0, y0 + 3, -4, c[3]); f.set(-1, y0 + 3, -4, c[3]); f.set(1, y0 + 3, -4, c[3]); // the door's arch
        f.set(0, y0 + 9, 0, "end_rod");
        f.set(0, y0 + 6, 0, "sea_lantern");
        f.set(1, y0, 2, Paint.wet("sea_pickle[pickles=4]"));
        if ((hh & 3) == 1) f.set(-2, y0, 2, "chest[facing=south]");
        f.set(-1, y0, -1, c[0].replace("terracotta", "carpet"));
        f.set(0, y0, -1, c[0].replace("terracotta", "carpet"));
    }

    /** A coral tower: prismarine walls, a band of lit windows, a balcony ring, a spiralling coral cone roof. */
    static void coralTower(Paint.Frame f, int y0, String[] c, long hh) {
        int H = 10;
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
            if (corner) continue;
            boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
            f.set(x, y0 - 1, z, "dark_prismarine");
            if (!edge) continue;
            for (int y = 0; y < H; y++) {
                boolean door = z == -3 && Math.abs(x) <= 1 && y <= 2;
                if (door) continue;
                boolean window = (y == 4 || y == 7) && (Math.abs(x) == 0 || Math.abs(z) == 0);
                f.set(x, y0 + y, z, window ? "sea_lantern" : y % 4 == 3 ? c[3] : "prismarine_bricks");
            }
        }
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) // the balcony ring
            if (Math.max(Math.abs(x), Math.abs(z)) == 4) f.set(x, y0 + H, z, "dark_prismarine_slab[type=bottom]");
        String[] cone = {"tube_coral_block", "brain_coral_block", "bubble_coral_block", "horn_coral_block"};
        for (int y = 0; y <= 5; y++) {
            int r = 3 - y / 2;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != r && y < 5) continue;
                f.set(x, y0 + H + y, z, cone[Math.floorMod(x + z + y, cone.length)]);
            }
        }
        f.set(0, y0 + H + 6, 0, "sea_lantern");
        f.set(0, y0 + H + 7, 0, "tube_coral_fan");
        f.set(0, y0 + 2, 0, "sea_lantern");
        if ((hh & 3) == 2) f.set(1, y0, 1, "chest[facing=west]");
        f.set(-1, y0, 1, Paint.wet("sea_pickle[pickles=2]"));
    }

    // ---- coral trees, lamps, clams, gates
    static void tree(DeepGenerator g, DeepGenerator.Noise n, Paint p, int x, int z, int k) {
        int y0 = g.upperFloor(n, x, z) + 1;
        String block = CORAL_BLOCKS[k % CORAL_BLOCKS.length].getKey().getKey();
        String alt = CORAL_BLOCKS[(k + 2) % CORAL_BLOCKS.length].getKey().getKey();
        int H = 8 + k % 5;
        for (int y = 0; y < H; y++) {
            p.set(x, y0 + y, z, block);
            if (y < 2) { p.set(x + 1, y0 + y, z, block); p.set(x, y0 + y, z + 1, block); } // a thicker foot
        }
        int branches = 3 + k % 2;
        for (int b = 0; b < branches; b++) {
            double a = b * 2 * Math.PI / branches + k;
            int len = 4 + (k + b) % 3;
            int ex = x, ey = y0 + H - 1, ez = z;
            for (int i = 1; i <= len; i++) {
                ex = x + (int) Math.round(Math.cos(a) * i);
                ez = z + (int) Math.round(Math.sin(a) * i);
                ey = y0 + H - 1 + (i + 1) / 2;
                p.set(ex, ey, ez, block);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) // the crown at its end
                if (Math.abs(dx) + Math.abs(dz) + dy <= 2) p.set(ex + dx, ey + dy, ez + dz, (dx + dz + dy) % 2 == 0 ? alt : block);
            p.set(ex, ey + 2, ez, CORALS[5 + (k + b) % 5].getKey().getKey());
        }
        p.set(x, y0 + H, z, "sea_lantern"); // a glowing heart in the crown
    }

    /** A jelly lamp: a prismarine post, a glowing sea lantern bell in pink glass, chains trailing down like tendrils. */
    static void lamp(Paint p, int x, int y0, int z) {
        for (int y = 0; y < 3; y++) p.set(x, y0 + y, z, Paint.wet("prismarine_wall[up=true]"));
        p.set(x, y0 + 3, z, "sea_lantern");
        p.set(x, y0 + 4, z, "pink_stained_glass");
        p.set(x + 1, y0 + 3, z, "pink_stained_glass"); p.set(x - 1, y0 + 3, z, "pink_stained_glass");
        p.set(x, y0 + 3, z + 1, "pink_stained_glass"); p.set(x, y0 + 3, z - 1, "pink_stained_glass");
        p.set(x + 1, y0 + 2, z, Paint.wet("iron_chain[axis=y]")); p.set(x - 1, y0 + 2, z, Paint.wet("iron_chain[axis=y]"));
    }

    /** A giant clam lying open with a pearl inside. */
    static void clam(DeepGenerator g, DeepGenerator.Noise n, Paint p, int cx, int cz) {
        int y0 = g.upperFloor(n, cx, cz) + 1;
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            double e = (x * x) / 25.0 + (z * z) / 16.0;
            if (e > 1) continue;
            p.set(cx + x, y0, cz + z, e > 0.7 ? "white_terracotta" : "purple_terracotta");      // the lower shell
            if (z >= 2 && e > 0.55) for (int y = 1; y <= 4 + (int) ((1 - Math.abs(x) / 5.0) * 3); y++)  // the upper shell, standing open
                p.set(cx + x, y0 + y, cz + z + 1, y % 2 == 0 ? "magenta_terracotta" : "purple_terracotta");
        }
        for (int x = 0; x <= 1; x++) for (int z = -1; z <= 0; z++) for (int y = 1; y <= 2; y++) p.set(cx + x, y0 + y, cz + z, "pearlescent_froglight");
    }

    /** A gate through the reef wall: two quartz pillars and an arch crowned with a shell. */
    static void gateArch(Paint p, int gx, int gz, boolean alongX) {
        int y0 = FLOOR + 3;
        for (int s = -1; s <= 1; s += 2) for (int y = 0; y <= 14; y++) {
            int x = alongX ? gx : gx + s * 7, z = alongX ? gz + s * 7 : gz;
            p.set(x, y0 + y, z, y == 14 ? "sea_lantern" : "quartz_pillar");
        }
        for (int i = -7; i <= 7; i++) {
            int y = y0 + 15 + (int) Math.round(Math.sqrt(49 - i * i) * 0.5);
            int x = alongX ? gx : gx + i, z = alongX ? gz + i : gz;
            p.set(x, y, z, "quartz_bricks");
            p.set(x, y - 1, z, Math.abs(i) % 3 == 0 ? "sea_lantern" : "smooth_quartz");
        }
        int top = y0 + 19;
        for (int a = -3; a <= 3; a++) for (int b = 0; b <= 3; b++) if (Math.hypot(a, b) <= 3.4) {
            int x = alongX ? gx : gx + a, z = alongX ? gz + a : gz;
            p.set(x, top + b, z, b == 0 && a == 0 ? "pearlescent_froglight" : ((a + 3) % 2 == 0 ? "pink_terracotta" : "white_terracotta"));
        }
    }
}
