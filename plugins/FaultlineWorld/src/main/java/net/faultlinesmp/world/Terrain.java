package net.faultlinesmp.world;

import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.util.Random;

/**
 * The shape of the Faultline overworld as pure functions of (seed, x, z): land/sea, surface height, sea floor,
 * the abyss under the open sea, the holes, rivers and which biome a column is. Thread safe (chunks generate on many
 * threads): only read-only noise tables.
 */
final class Terrain {
    static final int SEA = Layout.SEA;

    final long seed;
    private final SimplexNoiseGenerator warpX, warpZ, coast, hills, hills2, ridge, ridge2, mtnMask, river, temp, humid,
            floorN, abyssN, pillarN, farN, detail, valley, dune, blobWarp, mesa;

    Terrain(long seed) {
        this.seed = seed;
        Random r = new Random(seed ^ 0x2F7A11L);
        warpX = n(r); warpZ = n(r); coast = n(r); hills = n(r); hills2 = n(r); ridge = n(r); ridge2 = n(r); mtnMask = n(r);
        river = n(r); temp = n(r); humid = n(r); floorN = n(r); abyssN = n(r); pillarN = n(r); farN = n(r); detail = n(r);
        valley = n(r); dune = n(r); blobWarp = n(r); mesa = n(r);
    }

    private static SimplexNoiseGenerator n(Random r) { return new SimplexNoiseGenerator(r.nextLong()); }

    static double fbm(SimplexNoiseGenerator g, double x, double z, int oct) {
        double s = 0, a = 1, f = 1, norm = 0;
        for (int i = 0; i < oct; i++) { s += a * g.noise(x * f, z * f); norm += a; a *= 0.5; f *= 2; }
        return s / norm;
    }

    /** A general detail noise (-1..1) for patchy ground. */
    double fbmPublic(double x, double z) { return fbm(detail, x, z, 2); }

    static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ------------------------------------------------------------------ land / sea

    /** Coast value: > 0 land, < 0 sea; about the signed distance to the shore in blocks / 160, clamped to [-1, 1]. */
    double coast(double x, double z) {
        double wx = x + 130 * warpX.noise(x / 640.0, z / 640.0), wz = z + 130 * warpZ.noise(x / 640.0, z / 640.0);
        double best = -1;
        for (Layout.Isle is : Layout.ISLES) {
            double d = Layout.dist(wx, wz, is.x(), is.z());
            double rr = is.r() + 240 * fbm(coast, wx / 520.0, wz / 520.0, 3) + 60 * coast.noise(wx / 90.0, wz / 90.0);
            best = Math.max(best, Math.max(-1, Math.min(1, (rr - d) / 160)));
        }
        return Math.max(best, farlands(x, z) * 2 - 1);
    }

    /** 0..1: how far into the Farlands ring (the rim of the world). */
    double farlands(double x, double z) {
        double d = Math.hypot(x, z) + 70 * farN.noise(x / 300.0, z / 300.0);
        return smooth(Layout.FARLANDS - 40, Layout.FARLANDS + 60, d);
    }

    /** Which island a point belongs to (nearest centre), or -1 in the open sea / Farlands. */
    int isle(double x, double z) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = 0; i < Layout.ISLES.size(); i++) {
            Layout.Isle is = Layout.ISLES.get(i);
            double d = Layout.dist(x, z, is.x(), is.z()) / is.r();
            if (d < bd) { bd = d; best = i; }
        }
        return bd < 1.6 ? best : -1;
    }

    /** Weight 0..1 of a blob (warped circle). */
    double blob(Layout.Blob b, double x, double z) {
        double d = Layout.dist(x, z, b.x(), b.z());
        if (d > b.r() * 1.5) return 0;
        double w = b.r() * (1 + 0.28 * blobWarp.noise(x / 160.0 + b.x() / 7.0, z / 160.0));
        return smooth(w, w * 0.78, d);
    }

    Layout.Blob surfaceBlob(double x, double z) {
        Layout.Blob best = null; double bw = 0.5;
        for (Layout.Blob b : Layout.SURFACE) { double w = blob(b, x, z); if (w > bw) { bw = w; best = b; } }
        return best;
    }

    double surfaceWeight(B kind, double x, double z) {
        double w = 0;
        for (Layout.Blob b : Layout.SURFACE) if (b.biome() == kind) w = Math.max(w, blob(b, x, z));
        return w;
    }

    // ------------------------------------------------------------------ heights

    /** A column: its surface (top solid block), sea floor, water level, abyss band and biome. */
    static final class Col {
        int surface;          // y of the top solid block
        boolean sea;          // under the sea (water from surface+1 to SEA-1... SEA level water)
        boolean river;
        boolean dry;          // below sea level but landlocked (a faction site's basin / crater / the canyon): air, not water
        int abyssTop = Integer.MIN_VALUE, abyssBottom = Integer.MIN_VALUE; // water band (inclusive) under the open sea
        boolean hole;         // a hole down to the abyss
        B biome;
        double coast, farlands;
        int isle;
    }

    Col column(int x, int z) {
        Col c = new Col();
        double co = coast(x, z);
        c.coast = co;
        c.farlands = farlands(x, z);
        c.isle = isle(x, z);
        Layout.Blob blob = co > -0.45 ? surfaceBlob(x, z) : null;
        int climate = c.isle >= 0 ? Layout.ISLES.get(c.isle).climate() : 0;

        if (c.farlands > 0.5) {
            c.surface = farlandsHeight(x, z);
            c.biome = B.FARLANDS;
            return c;
        }
        if (co > 0) {
            double h = landHeight(x, z, co, climate, blob);
            // rivers
            double rv = Math.abs(fbm(river, x / 900.0, z / 900.0, 2));
            if (co > 0.12 && rv < 0.03 && (blob == null || blob.biome() != B.DEEP_DESERT && blob.biome() != B.WASTELAND)) {
                double t = 1 - rv / 0.03;
                // AUDIT: rivers fade out before the dug-out faction sites and the canyon. One running up to their edge left a
                // wall of river water standing next to open air below sea level: the first block update floods the site.
                h = lerp(h, SEA - 4, smooth(0, 0.7, t) * smooth(250, 225, h) * riverFade(x, z));
                if (h < SEA - 1) c.river = true;
            }
            c.surface = (int) Math.round(h);
            if (inCanyon(x, z)) c.surface = Math.min(c.surface, canyonFloor(x, z));
            if (c.surface < SEA) { if (dry(x, z)) c.dry = true; else c.sea = true; }
        } else {
            c.sea = true;
            c.surface = seaFloor(x, z, co);
        }
        // sea next to the Wasteland stays the acid sea
        c.biome = biome(x, z, c, climate, blob);
        if (c.sea && !c.river) abyss(x, z, c);
        return c;
    }

    double landHeight(double x, double z, double co, int climate, Layout.Blob blob) {
        double inland = smooth(0, 0.9, co);
        double h = SEA + 1 + 10 * inland;                                   // beach ramp
        h += 18 * inland * (0.5 + 0.5 * fbm(hills, x / 260.0, z / 260.0, 4));
        h += 7 * inland * fbm(hills2, x / 70.0, z / 70.0, 2);
        // mountains: big ridged ranges (way bigger than vanilla), strongest on the cold island and island A's west
        double m = smooth(0.15, 0.6, fbm(mtnMask, x / 1100.0, z / 1100.0, 2) + (climate == 2 ? 0.18 : climate == 1 ? -0.12 : 0));
        m = Math.max(m, surfaceWeight(B.JAGGED_PEAKS, x, z));
        m = Math.max(m, 0.85 * surfaceWeight(B.FROZEN_PEAKS, x, z));
        double rg = 1 - Math.abs(fbm(ridge, x / 340.0, z / 340.0, 3));
        double rg2 = 1 - Math.abs(ridge2.noise(x / 120.0, z / 120.0));
        h += inland * m * (25 + 78 * rg * rg + 10 * rg2);
        if (blob != null) {
            double w = blob(blob, x, z);
            switch (blob.biome()) {
                case WARTSFIELD -> h = lerp(h, SEA + 6 + 10 * Math.abs(valley.noise(x / 90.0, z / 90.0)), w * 0.85); // the valley
                case DEEP_DESERT -> h = lerp(h, SEA + 14 + 22 * Math.abs(dune.noise(x / 60.0, z / 110.0)) + 10 * fbm(dune, x / 300.0, z / 300.0, 2), w);
                case WASTELAND -> h = lerp(h, SEA + 2 + 9 * fbm(valley, x / 70.0, z / 70.0, 2), w);
                case INFERNO_FOREST -> h = lerp(h, h + 8 * Math.max(0, mesa.noise(x / 50.0, z / 50.0)) * 2, w);
                case SKY_ISLES -> h = lerp(h, SEA + 12 + 6 * hills.noise(x / 90.0, z / 90.0), w);
                default -> { }
            }
        }
        if (climate == 1 && (blob == null)) { // badlands-style plateaus on the hot island
            double p = mesa.noise(x / 210.0, z / 210.0);
            if (p > 0.35) h += Math.min(24, (p - 0.35) * 90);
        }
        h = shapeSites(x, z, h, true);
        // AUDIT: the coastline moves with the seed, and a site's dug-out bowl could reach the beach: land below sea level right
        // next to the sea (it would pour in). A rim always stands: nothing is below sea level within ~24 blocks of the shore, and
        // from there the ground may only drop one block per block.
        h = Math.max(h, SEA + 1 - Math.max(0, (co - 0.15) * 160));
        return Math.min(310, h);
    }

    int seaFloor(double x, double z, double co) {
        double off = (-co) * 160;                          // blocks from the shore, up to 160
        double far = -coast(x, z) >= 1 ? distanceToLand(x, z) : off;
        double depth = 6 + Math.min(150, far * 0.42) + 14 * fbm(floorN, x / 180.0, z / 180.0, 3);
        double y = SEA - depth;
        for (int[] h : Layout.HOLES) {                       // bowls round the holes
            double d = Layout.dist(x, z, h[0], h[1]);
            if (d < 260) y = Math.min(y, lerp(20, y, smooth(Layout.HOLE_R, 260, d)));
        }
        y = shapeSites(x, z, y, false);
        return (int) Math.round(Math.max(-58, y));
    }

    /** Rough distance to the nearest island shore (for the open sea's depth). */
    double distanceToLand(double x, double z) {
        double best = Double.MAX_VALUE;
        for (Layout.Isle is : Layout.ISLES) best = Math.min(best, Layout.dist(x, z, is.x(), is.z()) - is.r() + 160);
        best = Math.min(best, Layout.FARLANDS - Math.hypot(x, z));
        return Math.max(160, best);
    }

    /** The open sea's abyss: a flooded, pitch-black layer from about y -50 to y -1 with rock pillars (and diamonds). */
    void abyss(double x, double z, Col c) {
        double far = distanceToLand(x, z);
        boolean holeHere = false;
        for (int[] h : Layout.HOLES) if (Layout.dist(x, z, h[0], h[1]) < Layout.HOLE_R + 6 * floorN.noise(x / 9.0, z / 9.0)) holeHere = true;
        if (far < 330 && !holeHere) return;
        if (c.biome == B.WASTELAND) return;
        double edge = smooth(330, 420, far);
        if (holeHere) edge = 1;
        if (edge <= 0) return;
        int top = (int) Math.round(-1 - 3 * abyssN.noise(x / 40.0, z / 40.0) - 18 * (1 - edge));
        int bot = (int) Math.round(-50 + 4 * abyssN.noise(x / 55.0 + 9, z / 55.0) + 18 * (1 - edge));
        if (pillarN.noise(x / 26.0, z / 26.0) > 0.62 && !holeHere) return;         // rock pillars
        if (top - bot < 4) return;
        c.abyssTop = top;
        c.abyssBottom = bot;
        c.hole = holeHere;
    }

    int farlandsHeight(double x, double z) {
        // the Far Lands look: tall stacked walls with gaps, getting wilder further out
        double d = Math.hypot(x, z);
        double wild = smooth(Layout.FARLANDS, Layout.BORDER, d);
        double a = Math.atan2(z, x);
        double stripe = Math.abs(Math.sin(d / 5.5 + 3 * farN.noise(x / 80.0, z / 80.0)));
        double cross = Math.abs(Math.sin(a * 900 / (1 + wild * 0.5)));
        double h = SEA + 20 + 80 * stripe * (0.6 + 0.4 * cross) + 25 * wild;
        if (stripe < 0.12) h = SEA + 6;                    // the gaps between walls
        return (int) Math.min(312, h);
    }

    // ------------------------------------------------------------------ fixed sites

    /** Flattens / digs the ground under the faction builds (land = true for land columns, false for the sea floor). */
    double shapeSites(double x, double z, double h, boolean land) {
        for (Layout.Site s : Layout.SITES) {
            if (s.r() <= 0) continue;
            double d = Layout.dist(x, z, s.x(), s.z());
            if (d > s.r() * 1.9) continue;
            double ground = s.y() - 1;
            switch (s.shape()) {
                case FLAT, BASIN -> h = lerp(ground, h, smooth(s.r(), s.r() * (s.shape() == Layout.Shape.BASIN ? 1.9 : 1.6), d));
                case SEABED -> { if (!land) h = lerp(ground, h, smooth(s.r(), s.r() * 1.5, d)); }
                case CRATER -> h = lerp(s.y() + 19, h, smooth(s.r(), s.r() * 1.7, d));
                default -> { }
            }
        }
        // spawn plateau
        double ds = Layout.dist(x, z, Layout.SPAWN_X, Layout.SPAWN_Z);
        if (land && ds < 120) h = lerp(Layout.SPAWN_Y, h, smooth(40, 120, ds));
        return h;
    }

    /** 0 at (and inside) a dry site's edge or the canyon, 1 from a little further out. */
    static double riverFade(int x, int z) {
        double f = 1;
        for (Layout.Site s : Layout.SITES) {
            if (s.r() <= 0) continue;
            Layout.Shape sh = s.shape();
            if (sh != Layout.Shape.FLAT && sh != Layout.Shape.BASIN && sh != Layout.Shape.CRATER) continue;
            f = Math.min(f, smooth(s.r() * 2.0 + 6, s.r() * 2.0 + 46, Layout.dist(x, z, s.x(), s.z())));
        }
        int ex = Math.abs(x - Layout.CANYON_X) - Layout.CANYON_HX, ez = Math.abs(z - Layout.CANYON_Z) - Layout.CANYON_HZ;
        f = Math.min(f, smooth(6, 46, Math.max(ex, ez)));
        return f;
    }

    /** Faction sites dug below sea level stay dry (they are surrounded by land). */
    static boolean dry(int x, int z) {
        if (inCanyon(x, z)) return true;
        for (Layout.Site s : Layout.SITES) {
            if (s.r() <= 0) continue;
            Layout.Shape sh = s.shape();
            if (sh != Layout.Shape.FLAT && sh != Layout.Shape.BASIN && sh != Layout.Shape.CRATER) continue;
            if (Layout.dist(x, z, s.x(), s.z()) < s.r() * 1.95) return true;
        }
        return false;
    }

    static boolean inCanyon(int x, int z) {
        return Math.abs(x - Layout.CANYON_X) <= Layout.CANYON_HX && Math.abs(z - Layout.CANYON_Z) <= Layout.CANYON_HZ;
    }

    /** Canyon floor at this column (or Integer.MAX_VALUE outside). Walls step down in terraces toward the middle. */
    static int canyonFloor(int x, int z) {
        int ex = Layout.CANYON_HX - Math.abs(x - Layout.CANYON_X), ez = Layout.CANYON_HZ - Math.abs(z - Layout.CANYON_Z);
        int e = Math.min(ex, ez);
        if (e < 0) return Integer.MAX_VALUE;
        if (e >= 14) return Layout.CANYON_FLOOR;
        return Layout.CANYON_FLOOR + (14 - e) * 15;        // steep terraced walls
    }

    // ------------------------------------------------------------------ biomes

    B biome(double x, double z, Col c, int climate, Layout.Blob blob) {
        if (c.farlands > 0.5) return B.FARLANDS;
        if (blob != null && blob.biome() == B.WASTELAND && blob(blob, x, z) > 0.5) return B.WASTELAND;
        if (c.dry && blob != null) return blob.biome() == B.JAGGED_PEAKS ? B.WINDSWEPT_HILLS : blob.biome() == B.FROZEN_PEAKS ? B.SNOWY_SLOPES : blob.biome();
        if (c.sea && !c.river) {
            if (surfaceWeight(B.WASTELAND, x, z) > 0.35) return B.WASTELAND;      // the acid sea
            int depth = SEA - c.surface;
            if (depth > 45) return B.GREAT_SEA;
            return switch (climate) { case 1 -> depth < 20 ? B.WARM_OCEAN : B.LUKEWARM_OCEAN; case 2 -> B.COLD_OCEAN; default -> B.OCEAN; };
        }
        if (c.river) return climate == 2 ? B.FROZEN_RIVER : B.RIVER;
        if (c.surface <= SEA + 2 && c.coast < 0.08) return climate == 2 ? B.SNOWY_BEACH : (c.surface > SEA + 1 ? B.STONY_SHORE : B.BEACH);
        if (blob != null) {
            B k = blob.biome();
            if (k == B.JAGGED_PEAKS) return c.surface > 270 ? B.JAGGED_PEAKS : c.surface > 245 ? B.STONY_PEAKS : B.WINDSWEPT_HILLS;
            if (k == B.FROZEN_PEAKS) return c.surface > 265 ? B.FROZEN_PEAKS : c.surface > 240 ? B.SNOWY_SLOPES : B.SNOWY_TAIGA;
            if (k == B.JUNGLE) {
                double v = humid.noise(x / 120.0, z / 120.0);
                return v > 0.45 ? B.BAMBOO_JUNGLE : B.JUNGLE;
            }
            return k;
        }
        double t = temp.noise(x / 520.0, z / 520.0), hu = humid.noise(x / 430.0, z / 430.0);
        int y = c.surface;
        switch (climate) {
            case 1 -> { // hot
                if (y > 238) return B.SAVANNA_PLATEAU;
                if (hu > 0.45) return c.coast < 0.25 ? B.MANGROVE_SWAMP : B.SPARSE_JUNGLE;
                if (t > 0.35) return mesa.noise(x / 210.0, z / 210.0) > 0.35 ? (hu < -0.3 ? B.ERODED_BADLANDS : B.WOODED_BADLANDS) : B.BADLANDS;
                if (t < -0.25) return B.SAVANNA;
                return hu < 0 ? B.DESERT : B.SAVANNA;
            }
            case 2 -> { // cold
                if (y > 275) return B.FROZEN_PEAKS;
                if (y > 250) return B.SNOWY_SLOPES;
                if (y > 236) return B.GROVE;
                if (t > 0.4) return hu > 0.2 ? B.OLD_GROWTH_SPRUCE : B.TAIGA;
                if (hu < -0.45) return B.ICE_SPIKES;
                return hu > 0 ? B.SNOWY_TAIGA : B.SNOWY_PLAINS;
            }
            default -> { // temperate
                if (y > 282) return B.JAGGED_PEAKS;
                if (y > 262) return B.STONY_PEAKS;
                if (y > 245) return hu > 0.1 ? B.CHERRY_GROVE : B.MEADOW;
                if (hu > 0.5 && c.coast < 0.3) return B.SWAMP;
                if (t > 0.45) return hu > 0 ? B.FLOWER_FOREST : B.SUNFLOWER_PLAINS;
                if (t < -0.4) return B.TAIGA;
                if (hu > 0.15) return hu > 0.35 ? B.BIRCH_FOREST : B.FOREST;
                return B.PLAINS;
            }
        }
    }

    /** The underground biome at (x, y, z), or null when it is the column's surface biome. */
    B underground(int x, int y, int z, Col c) {
        if (c.abyssTop != Integer.MIN_VALUE && y <= c.abyssTop + 2 && y >= c.abyssBottom - 2) return B.ABYSS;
        if (c.sea || c.farlands > 0.5) return null;
        if (y > c.surface - 14) return null;
        for (Layout.Blob b : Layout.UNDERGROUND) {
            if (blob(b, x, z) <= 0.5) continue;
            if (b.biome() == B.FIBERS && y >= 18 && y <= 180) return B.FIBERS;
            if (b.biome() == B.MIRE && y >= -44 && y <= 64) return B.MIRE;
        }
        if (c.biome == B.SPIDER && y >= 60) return B.SPIDER_CAVES;
        if (y < -8 && fbm(temp, x / 300.0 + 40, z / 300.0, 2) > 0.3) return B.DEEP_DARK;
        double v = humid.noise(x / 180.0 + 11, z / 180.0 - 7);
        if (y < 150 && v > 0.42) return B.LUSH_CAVES;
        if (y < 150 && v < -0.42) return B.DRIPSTONE_CAVES;
        return null;
    }
}
