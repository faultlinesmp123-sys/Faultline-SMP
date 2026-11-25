package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;

import java.util.*;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * 2.0 round 7: THE ABANDONED STEAM CITY of the Sky kingdom. Every floating island round the Greek statue carries a piece of a
 * dead brass-and-brick city: clock towers, factory halls, workshops, boiler towers, gear pylons, street lamps, copper pipes
 * everywhere, chimneys; all of it weathered, holed, overgrown and full of cobwebs. Every island is joined to the rest by old
 * plank bridges (a spanning tree over the whole city, so you can walk from the ladder to any island), a derelict airship
 * hangs over the castle and a mooring mast stands on the ship's island.
 */
final class SkyCity {
    private SkyCity() {}

    // ------------------------------------------------------------------ the whole city, once per seed

    record Plan(List<Structures.Isle> isles, List<int[]> bridges) {}

    private static final Map<Long, Plan> PLANS = new HashMap<>();

    /** Every island in the city + the bridges {x0, z0, y0, x1, z1, y1}. Computed once per seed. */
    static Plan plan(Terrain t) {
        synchronized (PLANS) {
            Plan p = PLANS.get(t.seed);
            if (p != null) return p;
            List<Structures.Isle> all = new ArrayList<>(Structures.isles(t, 1500, -1500, 2700, -500));
            int[] core = Structures.SKY_CORE;
            all.add(new Structures.Isle(core[0], core[1], core[2], core[3], 0));
            int n = all.size();
            // Kruskal over every pair close enough, with a slope a plank bridge can climb without jumping
            List<double[]> edges = new ArrayList<>();
            for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
                Structures.Isle A = all.get(a), B2 = all.get(b);
                double d = Layout.dist(A.x(), A.z(), B2.x(), B2.z()), gap = d - 0.62 * (A.r() + B2.r());
                if (gap > 130 || gap < 2) continue;
                if (Math.abs(A.top() - B2.top()) > 0.42 * gap) continue;
                if (Layout.segDist(2100, -1000, A.x(), A.z(), B2.x(), B2.z(), null) < 112) continue;   // never through the statue
                edges.add(new double[]{gap, a, b});
            }
            edges.sort(Comparator.comparingDouble(e -> e[0]));
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            // the ring round the statue is always there (fixed isles 0-2 come first in isles(), the core is last)
            List<int[]> bridges = new ArrayList<>();
            for (int[] br : Structures.SKY_BRIDGES) {
                int a = br[0] == 3 ? n - 1 : br[0], b = br[1] == 3 ? n - 1 : br[1];
                union(parent, a, b);
            }
            for (double[] e : edges) {
                int a = (int) e[1], b = (int) e[2];
                if (find(parent, a) == find(parent, b)) continue;
                union(parent, a, b);
                Structures.Isle A = all.get(a), B2 = all.get(b);
                int[] ea = end(A, B2), eb = end(B2, A);
                bridges.add(new int[]{ea[0], ea[1], ea[2], eb[0], eb[1], eb[2]});
            }
            p = new Plan(all, bridges);
            PLANS.put(t.seed, p);
            return p;
        }
    }

    static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    static int[] end(Structures.Isle a, Structures.Isle b) {
        double d = Layout.dist(a.x(), a.z(), b.x(), b.z()), f = a.r() * 0.62 / d;
        return new int[]{(int) Math.round(a.x() + (b.x() - a.x()) * f), (int) Math.round(a.z() + (b.z() - a.z()) * f), a.top() + 2};
    }

    // ------------------------------------------------------------------ drawing

    static void paint(Canvas cv, Terrain t) {
        if (!cv.touches(1480, -1520, 2720, -480)) return;
        Plan p = plan(t);
        for (int[] b : p.bridges()) Structures.bridge(cv, t, b[0], b[1], b[2], b[3], b[4], b[5]);
        for (int i = 3; i < p.isles().size() - 1; i++) {                 // the random islands (not the fixed three, not the core)
            Structures.Isle s = p.isles().get(i);
            if (!cv.touches(s.x() - s.r() - 12, s.z() - s.r() - 12, s.x() + s.r() + 12, s.z() + s.r() + 12)) continue;
            building(cv, t, s);
        }
        int[] m = Structures.SKY_FIXED[2];
        mast(cv, t, m[0] + 6, m[1] - 6, m[2] + 2);
        airship(cv, t, 2300, -1230, 300, 0x41A);
        airship(cv, t, 1880, -900, 296, 0x41B);
    }

    /** A building that fits the island: the bigger the island, the bigger the ruin. */
    static void building(Canvas cv, Terrain t, Structures.Isle s) {
        long h = s.h();
        int y = s.top() + 3, x = s.x(), z = s.z(), r = s.r();
        int kind = (int) ((h >>> 44) % 4);
        // the street round it: cobbles and lamps
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d = Math.hypot(dx, dz) / r;
            if (d > 0.58 || !cv.inXZ(x + dx, z + dz)) continue;
            int gy = top(cv, x + dx, z + dz, s.top() + 4);
            if (gy < s.top()) continue;
            long hh = hash(h, x + dx, z + dz, 0x5C17);
            if (d < 0.5) cv.set(x + dx, gy, z + dz, bd((hh & 7) == 0 ? "minecraft:mossy_cobblestone" : (hh & 7) < 3 ? "minecraft:cobblestone" : (hh & 7) == 3 ? "minecraft:gravel" : "minecraft:stone_bricks"));
            if (d < 0.58) for (int k = 1; k <= 3; k++) if (!cv.air(x + dx, gy + k, z + dz)) cv.set(x + dx, gy + k, z + dz, WorldGen.Blocks.AIR);
        }
        if (r >= 24) { if (kind < 2) clockTower(cv, t, x, y, z, h); else factory(cv, t, x, y, z, h); }
        else if (r >= 16) { if (kind < 2) workshop(cv, t, x, y, z, h); else boiler(cv, t, x, y, z, h); }
        else if (r >= 12) gearPylon(cv, t, x, y, z, h);
        else lampPost(cv, x, y, z, h);
        if (r >= 16) for (int a = 0; a < 3; a++) {                        // lamps round the street
            double ang = (h >>> (a * 5) & 31) / 31.0 * Math.PI * 2;
            lampPost(cv, x + (int) Math.round(Math.cos(ang) * r * 0.5), y, z + (int) Math.round(Math.sin(ang) * r * 0.5), h >>> a);
        }
    }

    /** The highest solid block at x, z below `from` (the island's turf), or MIN. */
    static int top(Canvas cv, int x, int z, int from) {
        for (int y = from; y > from - 12; y--) if (!cv.air(x, y, z) && cv.type(x, y, z).isSolid()) return y;
        return Integer.MIN_VALUE;
    }

    /** Abandoned: some blocks never placed (holes), some swapped for their cracked / mossy / weathered selves. */
    static void put(Canvas cv, int x, int y, int z, String b, long h) {
        long k = hash3(h, x, y, z, 0xDECA);
        if ((k & 31) < 2) return;                                         // a hole
        if ((k & 31) == 2) b = b.replace("minecraft:bricks", "minecraft:mud_bricks").replace("stone_bricks", "cracked_stone_bricks")
                .replace("polished_blackstone_bricks", "cracked_polished_blackstone_bricks");
        else if ((k & 31) == 3) b = b.replace("exposed_", "oxidized_").replace("weathered_", "oxidized_");
        cv.set(x, y, z, bd(b));
    }

    static final String BRICK = "minecraft:bricks", TRIM = "minecraft:polished_blackstone_bricks", COPPER = "minecraft:weathered_cut_copper",
            ROOF = "minecraft:oxidized_cut_copper", GRATE = "minecraft:exposed_copper_grate", GLASS = "minecraft:orange_stained_glass",
            FLOOR = "minecraft:spruce_planks";

    /** A box of walls with floors, windows, a doorway, cobwebs and junk inside. */
    static void shell(Canvas cv, int x0, int y0, int z0, int w, int d, int h, long seed, String wall, int floorEvery) {
        for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) {
            int x = x0 + dx, y = y0 + dy, z = z0 + dz;
            boolean ex = dx == 0 || dx == w - 1, ez = dz == 0 || dz == d - 1;
            if (dy == 0 || (dy % floorEvery == 0 && !(ex || ez))) {
                if (dy == 0 || (hash3(seed, x, y, z, 0xF1) & 7) != 0) cv.set(x, y, z, bd(dy == 0 ? TRIM : FLOOR));   // rotten floors have gaps
                continue;
            }
            if (ex && ez) { put(cv, x, y, z, dy % 4 == 0 ? COPPER : TRIM, seed); continue; }
            if (ex || ez) {
                int along = ex ? dz : dx, fl = dy % floorEvery;
                boolean window = fl >= 2 && fl <= 3 && along % 3 == 1 && along > 0 && along < (ex ? d : w) - 1;
                if (window) { if ((hash3(seed, x, y, z, 0x91) & 3) != 0) cv.set(x, y, z, bd(GLASS)); else cv.set(x, y, z, WorldGen.Blocks.AIR); }
                else put(cv, x, y, z, fl == 0 || fl == floorEvery - 1 && along % 6 == 0 ? COPPER : wall, seed);
                continue;
            }
            long k = hash3(seed, x, y, z, 0x1D);
            int fl = dy % floorEvery;
            if (fl == 1 && (k & 63) == 0) cv.set(x, y, z, bd((k >>> 8 & 3) == 0 ? "minecraft:chest[facing=south]" : (k >>> 8 & 3) == 1 ? "minecraft:barrel[facing=up]" : (k >>> 8 & 3) == 2 ? "minecraft:anvil" : "minecraft:cauldron"));
            else if (fl == floorEvery - 1 && (k & 7) == 1) cv.set(x, y, z, bd("minecraft:cobweb"));
            else if (fl == 1 && (k & 31) == 2) cv.set(x, y, z, bd("minecraft:cobweb"));
            else cv.set(x, y, z, WorldGen.Blocks.AIR);
        }
        // the doorway (south), 2 wide, 3 tall, and a broken door leaf beside it
        int mx = x0 + w / 2;
        for (int dy = 1; dy <= 3; dy++) for (int dx = -1; dx <= 0; dx++) cv.set(mx + dx, y0 + dy, z0 + d - 1, WorldGen.Blocks.AIR);
        cv.set(mx + 1, y0 + 1, z0 + d, bd("minecraft:exposed_copper_trapdoor[facing=north,half=bottom,open=true]"));
        // stairs between floors: a ladder in the north-west corner
        for (int dy = 1; dy < h - 1; dy++) cv.set(x0 + 1, y0 + dy, z0 + 1, bd("minecraft:ladder[facing=south]"));
        for (int dy = floorEvery; dy < h - 1; dy += floorEvery) { cv.set(x0 + 1, y0 + dy, z0 + 1, bd("minecraft:ladder[facing=south]")); cv.set(x0 + 2, y0 + dy, z0 + 1, WorldGen.Blocks.AIR); }
    }

    /** A copper pipe: a vertical run of lightning rods with a grate collar every 5. */
    static void pipe(Canvas cv, int x, int y0, int y1, int z, long h) {
        for (int y = y0; y <= y1; y++) cv.set(x, y, z, bd(y % 5 == 0 ? GRATE : "minecraft:lightning_rod[facing=up]"));
    }

    /** A gear standing in the X (axis 0) or Z plane: a rim of teeth round spokes and a hub. */
    static void gear(Canvas cv, int cx, int cy, int cz, double r, boolean xPlane, long h) {
        int R = (int) Math.ceil(r + 1);
        for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
            double d = Math.hypot(a, b), ang = Math.atan2(b, a);
            boolean tooth = d > r && d <= r + 1 && Math.cos(ang * 8) > 0.35;
            boolean rim = d > r - 1.1 && d <= r, hub = d <= 1.2, spoke = d < r && (Math.abs(a) < 0.6 || Math.abs(b) < 0.6);
            if (!(tooth || rim || hub || spoke)) continue;
            String m = hub ? "minecraft:polished_blackstone" : tooth || rim ? "minecraft:exposed_copper" : "minecraft:weathered_copper";
            if (xPlane) cv.set(cx + a, cy + b, cz, bd(m)); else cv.set(cx, cy + b, cz + a, bd(m));
        }
    }

    /** A clock face set in a wall: quartz dial, copper rim, stopped hands. */
    static void clock(Canvas cv, int cx, int cy, int cz, boolean xPlane, int sx) {
        for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) {
            double d = Math.hypot(a, b);
            if (d > 3.3) continue;
            String m = d > 2.4 ? "minecraft:exposed_copper" : "minecraft:smooth_quartz";
            if (a == 0 && b >= 0 && b <= 2 || b == 0 && a >= 0 && a <= 1) m = "minecraft:polished_blackstone";
            if (xPlane) cv.set(cx + a * sx, cy + b, cz, bd(m)); else cv.set(cx, cy + b, cz + a * sx, bd(m));
        }
    }

    /** A copper dome (half sphere) with a lightning rod spire. */
    static void dome(Canvas cv, int cx, int y, int cz, int r, long h) {
        for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) for (int c = 0; c <= r; c++) {
            double d = Math.sqrt(a * a + b * b + c * c);
            if (d > r + 0.4 || d < r - 0.7) continue;
            put(cv, cx + a, y + c, cz + b, (c & 1) == 0 && (Math.abs(a) == 0 || Math.abs(b) == 0) ? COPPER : ROOF, h);
        }
        cv.set(cx, y + r + 1, cz, bd(GRATE));
        for (int k = 2; k <= 4; k++) cv.set(cx, y + r + k, cz, bd("minecraft:lightning_rod[facing=up]"));
    }

    static void chimney(Canvas cv, int x, int y0, int y1, int z, long h) {
        for (int y = y0; y <= y1; y++) for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) put(cv, x + a, y, z + b, y >= y1 - 1 ? TRIM : BRICK, h);
        if ((h & 3) == 0) cv.set(x, y1 + 1, z, bd("minecraft:campfire[lit=true,signal_fire=true]"));   // still steaming
    }

    // ---------------------------------------------------------------- the buildings

    static void clockTower(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int w = 9, tall = 30 + (int) (h >>> 20 & 7);
        int x0 = x - 4, z0 = z - 4;
        shell(cv, x0, y, z0, w, w, tall, h, BRICK, 6);
        for (int k = 0; k < 4; k++) {                                     // buttress pipes on the corners
            int px = k % 2 == 0 ? x0 - 1 : x0 + w, pz = k < 2 ? z0 - 1 : z0 + w;
            pipe(cv, px, y + 1, y + tall - 4, pz, h);
        }
        int cy = y + tall - 6;
        clock(cv, x, cy, z0 - 1, true, -1);
        clock(cv, x, cy, z0 + w, true, 1);
        clock(cv, x0 - 1, cy, z, false, 1);
        clock(cv, x0 + w, cy, z, false, -1);
        for (int a = -5; a <= 5; a++) for (int b = -5; b <= 5; b++) if (Math.max(Math.abs(a), Math.abs(b)) == 5) put(cv, x + a, y + tall, z + b, TRIM, h);   // the gallery
        dome(cv, x, y + tall, z, 4, h);
        gear(cv, x, y + 9, z0 - 1, 3, true, h);
    }

    static void factory(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int w = 21, d = 13, tall = 12;
        int x0 = x - 10, z0 = z - 6;
        shell(cv, x0, y, z0, w, d, tall, h, BRICK, 6);
        for (int dx = -1; dx <= w; dx++) for (int k = 0; k <= d / 2 + 1; k++) {    // a saw-tooth copper roof (two pitches)
            for (int side = 0; side <= 1; side++) {
                int zz = side == 0 ? z0 - 1 + k : z0 + d - k;
                put(cv, x0 + dx, y + tall + k, zz, ROOF + "_stairs[facing=" + (side == 0 ? "south" : "north") + "]", h);
            }
        }
        for (int k = 0; k <= d / 2; k++) for (int dz = k; dz < d - k; dz++) for (int side : new int[]{0, w - 1}) put(cv, x0 + side, y + tall + k, z0 + dz, BRICK, h);
        chimney(cv, x0 + 3, y + tall, y + tall + 14, z0 + 2, h);
        chimney(cv, x0 + w - 5, y + tall, y + tall + 11, z0 + 2, h >>> 3);
        gear(cv, x0 + w / 2, y + 7, z0 + d, 3.5, true, h);
        for (int k = 0; k < 4; k++) { int px = x0 + 2 + k * 5; pipe(cv, px, y + 1, y + tall - 1, z0 + d, h); }
        // a boiler outside the east end
        boilerTank(cv, x0 + w + 2, y + 1, z, 2, 9, h);
        cv.set(x0 + w, y + 6, z, bd("minecraft:lightning_rod[facing=east]"));
    }

    static void workshop(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int w = 11, d = 9, tall = 7;
        int x0 = x - 5, z0 = z - 4;
        shell(cv, x0, y, z0, w, d, tall, h, BRICK, 6);
        for (int k = 0; k <= d / 2 + 1; k++) for (int dx = -1; dx <= w; dx++) {
            put(cv, x0 + dx, y + tall + k, z0 - 1 + k, ROOF + "_stairs[facing=south]", h);
            put(cv, x0 + dx, y + tall + k, z0 + d - k, ROOF + "_stairs[facing=north]", h);
        }
        for (int k = 0; k <= d / 2; k++) for (int dz = k; dz < d - k; dz++) { put(cv, x0, y + tall + k, z0 + dz, BRICK, h); put(cv, x0 + w - 1, y + tall + k, z0 + dz, BRICK, h); }
        chimney(cv, x0 + w - 3, y + tall, y + tall + 7, z0 + 1, h);
        gear(cv, x0 - 1, y + 5, z, 2.5, false, h);
        pipe(cv, x0 + 2, y + 1, y + tall + 4, z0 + d, h);
        cv.set(x0 + 2, y + tall + 5, z0 + d, bd("minecraft:copper_bulb[lit=false]"));
    }

    static void boiler(Canvas cv, Terrain t, int x, int y, int z, long h) {
        for (int k = 0; k < 4; k++) {                                     // four iron legs
            int lx = x + (k % 2 == 0 ? -3 : 3), lz = z + (k < 2 ? -3 : 3);
            for (int dy = 0; dy < 8; dy++) put(cv, lx, y + dy, lz, "minecraft:polished_blackstone", h);
        }
        boilerTank(cv, x, y + 8, z, 4, 10, h);
        pipe(cv, x + 5, y, y + 14, z, h);
        for (int dy = 0; dy <= 8; dy++) cv.set(x, y + dy, z, bd(dy % 3 == 0 ? GRATE : "minecraft:lightning_rod[facing=up]"));
    }

    /** An upright copper tank, riveted bands, a dome, a valve wheel. */
    static void boilerTank(Canvas cv, int x, int y, int z, int r, int tall, long h) {
        for (int dy = 0; dy < tall; dy++) for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
            double d = Math.hypot(a, b);
            if (d > r + 0.4) continue;
            if (d > r - 0.7 || dy == 0) put(cv, x + a, y + dy, z + b, dy % 4 == 0 ? "minecraft:exposed_cut_copper" : "minecraft:weathered_copper", h);
            else cv.set(x + a, y + dy, z + b, WorldGen.Blocks.AIR);
        }
        dome(cv, x, y + tall, z, r, h);
        gear(cv, x, y + tall / 2, z - r - 1, 1.5, true, h);
    }

    static void gearPylon(Canvas cv, Terrain t, int x, int y, int z, long h) {
        for (int dy = 0; dy < 10; dy++) for (int a = 0; a <= 1; a++) put(cv, x + a, y + dy, z, dy % 4 == 0 ? COPPER : TRIM, h);
        boolean xPlane = (h >>> 9 & 1) == 0;
        gear(cv, x, y + 12, z + (xPlane ? 1 : 0), 4, xPlane, h);
        gear(cv, x + (xPlane ? 6 : 0), y + 6, z + (xPlane ? 1 : 6), 2, xPlane, h >>> 4);
        cv.set(x, y + 17, z, bd("minecraft:copper_bulb[lit=false]"));
    }

    static void lampPost(Canvas cv, int x, int y, int z, long h) {
        for (int dy = 0; dy < 4; dy++) cv.set(x, y + dy, z, bd(dy == 0 ? TRIM : "minecraft:lightning_rod[facing=up]"));
        cv.set(x, y + 4, z, bd((h & 3) == 0 ? "minecraft:lantern" : "minecraft:copper_bulb[lit=false]"));
    }

    /** The mooring mast on the ship's island: a lattice tower with a platform and a winch. */
    static void mast(Canvas cv, Terrain t, int x, int y, int z) {
        if (!cv.touches(x - 6, z - 6, x + 6, z + 6)) return;
        for (int dy = 0; dy < 24; dy++) for (int a = -2; a <= 2; a += 4) for (int b = -2; b <= 2; b += 4) cv.set(x + a, y + dy, z + b, bd(dy % 6 == 0 ? COPPER : "minecraft:polished_blackstone_bricks"));
        for (int dy = 6; dy < 24; dy += 6) for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) if (Math.max(Math.abs(a), Math.abs(b)) == 2) cv.set(x + a, y + dy, z + b, bd(COPPER));
        for (int a = -4; a <= 4; a++) for (int b = -4; b <= 4; b++) if (Math.hypot(a, b) < 4.5) cv.set(x + a, y + 24, z + b, bd("minecraft:spruce_slab[type=bottom]"));
        for (int dy = 1; dy < 24; dy++) cv.set(x, y + dy, z, bd("minecraft:ladder[facing=south]"));
        cv.set(x, y + 23, z - 1, bd(COPPER));
        gear(cv, x, y + 27, z, 2, true, 0x3A);
        cv.set(x + 3, y + 25, z, bd("minecraft:lantern"));
    }

    /** A derelict airship: a torn canvas envelope with brass ribs over a hanging gondola, a stopped propeller. */
    static void airship(Canvas cv, Terrain t, int x, int z, int y, int salt) {
        int L = 22, R = 6;
        if (!cv.touches(x - L - 6, z - R - 2, x + L + 6, z + R + 2)) return;
        long h = hash(t.seed, x, z, salt);
        for (int dx = -L; dx <= L; dx++) {
            double rr = R * Math.sqrt(Math.max(0, 1 - (dx / (double) L) * (dx / (double) L)));
            for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
                double d = Math.hypot(a, b);
                if (d > rr + 0.4 || d < rr - 0.8) continue;
                long k = hash3(h, x + dx, y + b, z + a, 0xA1);
                if ((k & 15) < 2 && b > -2) continue;                       // torn
                String m = dx % 6 == 0 ? "minecraft:exposed_cut_copper" : (k & 15) == 3 ? "minecraft:brown_wool" : "minecraft:white_wool";
                cv.set(x + dx, y + b, z + a, bd(m));
            }
        }
        // the gondola: a little hull of dark oak with a cabin
        int gy = y - R - 5;
        for (int dx = -9; dx <= 9; dx++) for (int a = -2; a <= 2; a++) {
            boolean side = Math.abs(a) == 2 || Math.abs(dx) == 9;
            cv.set(x + dx, gy, z + a, bd("minecraft:dark_oak_planks"));
            if (side) cv.set(x + dx, gy + 1, z + a, bd("minecraft:dark_oak_slab[type=bottom]"));
        }
        for (int dx = -4; dx <= 2; dx++) for (int a = -2; a <= 2; a++) for (int dy = 1; dy <= 3; dy++) {
            boolean wall = Math.abs(a) == 2 || dx == -4 || dx == 2;
            if (wall) cv.set(x + dx, gy + dy, z + a, bd(dy == 2 && dx % 2 == 0 ? GLASS : "minecraft:spruce_planks"));
            else if (dy == 1 && dx == 0 && a == 0) cv.set(x + dx, gy + dy, z + a, bd("minecraft:chest[facing=east]"));
        }
        for (int dx = -4; dx <= 2; dx++) for (int a = -2; a <= 2; a++) cv.set(x + dx, gy + 4, z + a, bd(ROOF));
        for (int dx = -8; dx <= 8; dx += 8) for (int dy = gy + 1; dy < y - R + 1; dy++) { cv.set(x + dx, dy, z - 2, bd("minecraft:dark_oak_log")); cv.set(x + dx, dy, z + 2, bd("minecraft:dark_oak_log")); }
        gear(cv, x - L - 2, y, z, 3, false, h);                           // the stopped propeller
        for (int k = 1; k <= 2; k++) cv.set(x - L - 1 + k, y, z, bd("minecraft:lightning_rod[facing=east]"));
    }
}
