package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.Random;

/**
 * THE REACHES (Deep 1.14.0): the Sunlit Sea's open floor is a patchwork of biomes now, instead of one sandy plain. Every
 * column belongs to the nearest of a jittered grid of sites (CELL blocks apart, a Voronoi map); each site's kind comes from
 * its hash, picked from a list that depends on how far out it is (gentle near the arrival point, strange further out).
 * Floor heights blend between neighbouring sites (exponential weights, so borders are smooth slopes or cliffs, never
 * steps), materials and decoration come from the nearest site. Pure functions of (seed, x, z), like the rest of the
 * generator. The landmarks (Sulphur Sea, Mermaid Den, Bubble, Trench, the outer ring) are blended on top of this, as before.
 *
 *  SHALLOWS  the Sunlit Shallows   round the arrival point: a raised sandy plain only ~45 blocks under the surface, coral
 *  KELP      the Kelpwood          kelp in thick stands right up to the surface, gravel and moss
 *  MEADOW    the Ember Meadows     flat-topped plateaus on sheer cliffs, covered in red grass and fire coral
 *  BULB      the Lantern Groves    lavender floor, trees of tall stalks crowned with glowing caged bulbs
 *  DUNES     the Pale Dunes        rolling dunes and craters, rock arches and old bones, little else
 *  CRAGS     the Needle Fields     a red-brown floor bristling with tall jagged rock needles
 *  PEAKS     the Drowned Peaks     a sunken mountain range climbing to 30 blocks under the surface
 *  GLOW      the Lumen Reef        a deep blue basin under giant glowing domes with trailing strands
 *  ISLES     the Hanging Isles     a deep floor with rock islands floating in the water, mossed and kelped
 *  OPEN      the open sea          the old floor (sand, gravel, prism spires, sea stacks, whale falls)
 * The Sulphur Sea is a MUSHROOM FOREST now too (`mushroom`): giant sulphur-yellow caps on pale stems, in the water and on its
 * islands. Already-generated chunks don't change: delete the faultline_deep world folder to get the new sea.
 */
final class Reaches {
    private Reaches() { }

    enum Kind { SHALLOWS, KELP, MEADOW, BULB, DUNES, CRAGS, PEAKS, GLOW, ISLES, OPEN }

    static final int CELL = 650;
    static final double WARP = 190; // borders wander this far, so regions are organic blobs, not polygons
    static final double SHALLOWS_R = 230, SHALLOWS_BLEND = 70, SOFT = 16;

    private static final Kind[] RING1 = {Kind.KELP, Kind.KELP, Kind.MEADOW, Kind.MEADOW, Kind.DUNES, Kind.BULB, Kind.OPEN};
    private static final Kind[] RING2 = {Kind.BULB, Kind.CRAGS, Kind.GLOW, Kind.ISLES, Kind.DUNES, Kind.MEADOW, Kind.KELP, Kind.OPEN};
    private static final Kind[] RING3 = {Kind.PEAKS, Kind.PEAKS, Kind.CRAGS, Kind.GLOW, Kind.ISLES, Kind.BULB, Kind.OPEN};

    // =========================================================================================== the map
    /** Site of grid cell (gx, gz): {x, z}. */
    static int[] site(long seed, int gx, int gz) {
        int x = gx * CELL + 60 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 401)) * (CELL - 120));
        int z = gz * CELL + 60 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 402)) * (CELL - 120));
        return new int[]{x, z};
    }

    static Kind kindOf(long seed, int gx, int gz) {
        int[] s = site(seed, gx, gz);
        double d = Math.hypot(s[0], s[1]);
        Kind[] list = d < 1100 ? RING1 : d < 2100 ? RING2 : RING3;
        return list[(int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 403)) * list.length)];
    }

    /** How much of the Shallows this column is in (0..1), with a wobbly edge. */
    static double shallows(int x, int z) {
        double d = Math.hypot(x, z);
        if (d > SHALLOWS_R + SHALLOWS_BLEND + 30) return 0;
        double a = Math.atan2(z, x);
        d -= 22 * (0.6 * Math.sin(a * 4 + 0.7) + 0.4 * Math.sin(a * 9 + 2.1));
        if (d <= SHALLOWS_R) return 1;
        if (d >= SHALLOWS_R + SHALLOWS_BLEND) return 0;
        double t = 1 - (d - SHALLOWS_R) / SHALLOWS_BLEND;
        return t * t * (3 - 2 * t);
    }

    /** The domain warp (one noise pair per seed, made once; read-only after, so thread safe). */
    private static volatile Object[] warp;

    static double[] warped(long seed, int x, int z) {
        Object[] w = warp;
        if (w == null || (long) w[0] != seed) {
            java.util.Random r = new java.util.Random(seed ^ 0x2C1B3C6D5EL);
            org.bukkit.util.noise.SimplexOctaveGenerator a = new org.bukkit.util.noise.SimplexOctaveGenerator(r.nextLong(), 3), b = new org.bukkit.util.noise.SimplexOctaveGenerator(r.nextLong(), 3);
            a.setScale(1 / 420.0); b.setScale(1 / 420.0);
            w = new Object[]{seed, a, b};
            warp = w;
        }
        double wx = ((org.bukkit.util.noise.SimplexOctaveGenerator) w[1]).noise(x, z, 0.5, 0.5, true);
        double wz = ((org.bukkit.util.noise.SimplexOctaveGenerator) w[2]).noise(x, z, 0.5, 0.5, true);
        return new double[]{x + wx * WARP, z + wz * WARP};
    }

    /** The kind a column is (the nearest site, or the Shallows round the arrival point). */
    static Kind at(long seed, int x, int z) {
        if (shallows(x, z) > 0.5) return Kind.SHALLOWS;
        return nearest(seed, x, z);
    }

    static Kind nearest(long seed, int x0, int z0) {
        double[] p = warped(seed, x0, z0);
        double x = p[0], z = p[1];
        int gx = (int) Math.floor(x / CELL), gz = (int) Math.floor(z / CELL);
        double best = 1e18;
        Kind k = Kind.OPEN;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int[] s = site(seed, gx + i, gz + j);
            double d = (x - s[0]) * (x - s[0]) + (z - s[1]) * (z - s[1]);
            if (d < best) { best = d; k = kindOf(seed, gx + i, gz + j); }
        }
        return k;
    }

    /** Is this a "quiet" floor where the old open-sea features (ruins, details, stacks, arches, whale falls) still fit? */
    static boolean quiet(long seed, int x, int z) {
        Kind k = at(seed, x, z);
        return k == Kind.OPEN || k == Kind.DUNES || k == Kind.SHALLOWS || k == Kind.KELP;
    }

    // =========================================================================================== floor heights
    /** The floor here: the blended floors of the sites round it (`open` = the old floor, for OPEN sites). */
    static double floor(DeepGenerator.Noise n, int x, int z, double open) {
        double[] p = warped(n.seed, x, z);
        double px = p[0], pz = p[1];
        int gx = (int) Math.floor(px / CELL), gz = (int) Math.floor(pz / CELL);
        double[] dist = new double[9];
        Kind[] kinds = new Kind[9];
        double min = 1e18;
        int c = 0;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++, c++) {
            int[] s = site(n.seed, gx + i, gz + j);
            dist[c] = Math.hypot(px - s[0], pz - s[1]);
            kinds[c] = kindOf(n.seed, gx + i, gz + j);
            min = Math.min(min, dist[c]);
        }
        double[] perKind = new double[Kind.values().length];
        boolean[] done = new boolean[perKind.length];
        double sum = 0, w = 0;
        for (int i = 0; i < 9; i++) {
            double wi = Math.exp(-(dist[i] - min) / SOFT);
            if (wi < 1e-4) continue;
            int o = kinds[i].ordinal();
            if (!done[o]) { perKind[o] = kindFloor(n, kinds[i], x, z, open); done[o] = true; }
            sum += wi * perKind[o];
            w += wi;
        }
        double h = sum / w;
        double s = shallows(x, z);
        if (s > 0) h = h * (1 - s) + kindFloor(n, Kind.SHALLOWS, x, z, open) * s;
        return h;
    }

    static double kindFloor(DeepGenerator.Noise n, Kind k, int x, int z, double open) {
        double hills = n.n2(n.hills, x, z), dunes = n.n2(n.dunes, x, z);
        return switch (k) {
            case SHALLOWS -> DeepGenerator.SEA - 46 + hills * 7 + dunes * 1.5;
            case KELP -> 74 + hills * 9 + dunes * 1.5;
            case MEADOW -> {
                double p = n.n2(n.plateau, x, z);
                double t = Math.max(0, Math.min(1, (p - 0.05) / 0.07));   // a short ramp = a sheer cliff
                double tier = p > 0.45 ? 22 : 0;                            // some plateaus have a second step
                double t2 = Math.max(0, Math.min(1, (p - 0.45) / 0.06));
                yield 62 + hills * 5 + t * (58 + n.n2(n.detail, x, z) * 3) + tier * t2;
            }
            case BULB -> 92 + hills * 14 + dunes * 2;
            case DUNES -> {
                double ridge = 1 - Math.abs(n.n2(n.ridges, x * 2.4, z * 2.4));
                yield 72 + ridge * ridge * 22 + hills * 6 + crater(n.seed, x, z);
            }
            case CRAGS -> 44 + hills * 6 + dunes * 2;
            case PEAKS -> {
                double r = 1 - Math.abs(n.n2(n.ridges, x, z));
                double r2 = 1 - Math.abs(n.n2(n.detail, x * 0.5, z * 0.5));
                yield Math.min(DeepGenerator.SEA - 30, 58 + r * r * r * 170 + r2 * r2 * 26 + hills * 10);
            }
            case GLOW -> 30 + hills * 6 + dunes * 1.2;
            case ISLES -> 38 + hills * 5;
            case OPEN -> open;
        };
    }

    /** The Pale Dunes' craters: a bowl with a raised rim. */
    static double crater(long seed, int x, int z) {
        int gx = Math.floorDiv(x, 96), gz = Math.floorDiv(z, 96);
        double out = 0;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 411)) > 0.45) continue;
            int cx = (gx + i) * 96 + 20 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 412)) * 56);
            int cz = (gz + j) * 96 + 20 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 413)) * 56);
            double r = 9 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 414)) * 12;
            double d = Math.hypot(x - cx, z - cz) / r;
            if (d > 1.6) continue;
            out += d < 1 ? -9 * (1 - d * d) : 4 * Math.max(0, 1 - Math.abs(d - 1.15) / 0.45);
        }
        return out;
    }

    // =========================================================================================== blocks
    static Material top(Kind k, DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        int r = rnd.nextInt(10);
        return switch (k) {
            case SHALLOWS -> r < 7 ? Material.SAND : r < 9 ? Material.SANDSTONE : Material.GRAVEL;
            case KELP -> r < 4 ? Material.GRAVEL : r < 7 ? Material.MOSS_BLOCK : Material.SAND;
            case MEADOW -> up > 100 ? (r < 7 ? Material.CRIMSON_NYLIUM : r < 9 ? Material.RED_TERRACOTTA : Material.MOSS_BLOCK)
                    : (r < 6 ? Material.RED_SAND : Material.TERRACOTTA);
            case BULB -> r < 4 ? Material.PURPLE_TERRACOTTA : r < 7 ? Material.MAGENTA_TERRACOTTA : r < 9 ? Material.LIGHT_BLUE_TERRACOTTA : Material.MOSS_BLOCK;
            case DUNES -> r < 8 ? Material.SAND : Material.SANDSTONE;
            case CRAGS -> r < 4 ? Material.RED_SAND : r < 7 ? Material.COARSE_DIRT : Material.GRANITE;
            case PEAKS -> r < 4 ? Material.STONE : r < 6 ? Material.ANDESITE : r < 8 ? Material.GRAVEL : Material.TUFF;
            case GLOW -> r < 4 ? Material.LIGHT_BLUE_TERRACOTTA : r < 7 ? Material.BLUE_TERRACOTTA : r < 9 ? Material.PRISMARINE : Material.CYAN_TERRACOTTA;
            case ISLES -> r < 6 ? Material.MUD : Material.CLAY;
            case OPEN -> Material.SAND;
        };
    }

    /** The rock under the top (the few blocks under it, and every exposed cliff above y 64). */
    static Material under(Kind k, Random rnd, int y, int up) {
        int b = Math.floorMod(y, 11);
        return switch (k) {
            case SHALLOWS, DUNES -> y > up - 4 ? Material.SANDSTONE : b < 3 ? Material.SMOOTH_SANDSTONE : Material.SANDSTONE;
            case KELP -> rnd.nextInt(8) == 0 ? Material.MOSSY_COBBLESTONE : Material.STONE;
            case MEADOW -> b < 2 ? Material.RED_TERRACOTTA : b < 5 ? Material.TERRACOTTA : b < 6 ? Material.BROWN_TERRACOTTA : b < 9 ? Material.GRANITE : Material.ORANGE_TERRACOTTA;
            case BULB -> b < 4 ? Material.PURPLE_TERRACOTTA : b < 7 ? Material.LIGHT_BLUE_TERRACOTTA : Material.CALCITE;
            case CRAGS -> b < 4 ? Material.BROWN_TERRACOTTA : b < 7 ? Material.GRANITE : Material.RED_TERRACOTTA;
            case PEAKS -> b < 3 ? Material.ANDESITE : b < 4 ? Material.DIORITE : b < 9 ? Material.STONE : Material.TUFF;
            case GLOW -> Material.BLUE_TERRACOTTA;
            case ISLES -> y > up - 3 ? Material.MUD : Material.STONE;
            case OPEN -> Material.SANDSTONE;
        };
    }

    // =========================================================================================== tall features (per block)
    /** What a feature puts at (x, y, z) above the floor, or null. Called only where the block is still water. */
    static Material feature(DeepGenerator g, DeepGenerator.Noise n, Kind k, int x, int y, int z, int up) {
        return switch (k) {
            case BULB -> bulb(g, n, x, y, z);
            case GLOW -> dome(g, n, x, y, z);
            case CRAGS -> needle(n, x, y, z, up);
            default -> null;
        };
    }

    /** How high this kind's features can reach above the floor (the generator scans that far). */
    static int reach(Kind k) {
        return switch (k) { case BULB -> 52; case GLOW -> 30; case CRAGS -> 90; default -> 0; };
    }

    /** The floor under a feature's own site (cached per thread: every block of its column asks). */
    private static final ThreadLocal<java.util.HashMap<Long, Integer>> SITE_FLOOR = ThreadLocal.withInitial(java.util.HashMap::new);

    static int siteFloor(DeepGenerator g, DeepGenerator.Noise n, int sx, int sz) {
        java.util.HashMap<Long, Integer> c = SITE_FLOOR.get();
        if (c.size() > 8192) c.clear();
        long key = ((long) sx << 32) ^ (sz & 0xffffffffL) ^ (n.seed * 31);
        Integer v = c.get(key);
        if (v == null) { v = g.upperFloor(n, sx, sz); c.put(key, v); }
        return v;
    }

    /** Lantern trees: a stalk crowned with a caged, glowing bulb. Grid 22. */
    static Material bulb(DeepGenerator g, DeepGenerator.Noise n, int x, int y, int z) {
        long seed = n.seed;
        int gx = Math.floorDiv(x, 22), gz = Math.floorDiv(z, 22);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = DeepGenerator.hash(seed, gx + i, gz + j, 421);
            if (DeepGenerator.unit(h) > 0.6) continue;
            int sx = (gx + i) * 22 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 422)) * 14);
            int sz = (gz + j) * 22 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 423)) * 14);
            if (Math.abs(x - sx) > 9 || Math.abs(z - sz) > 9) continue;
            double height = 14 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 424)) * 30;
            double R = 3 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 425)) * 3.8;
            double lean = (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 426)) - 0.5) * 0.25;
            double ang = DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 427)) * Math.PI * 2;
            int base = siteFloor(g, n, sx, sz);
            double top = base + height;
            double ax = sx + Math.cos(ang) * lean * (y - base), az = sz + Math.sin(ang) * lean * (y - base);
            // the stalk (thicker at the bottom: roots)
            double dStalk = Math.hypot(x - ax, z - az);
            if (y > base && y < top - R + 1) {
                double sr = y < base + 3 ? 1.6 - (y - base) * 0.3 : 0.6;
                if (dStalk <= sr) return y < base + 3 && dStalk > 0.6 ? Material.MANGROVE_ROOTS : Material.DARK_OAK_WOOD;
            }
            // the bulb: a caged sphere, glowing in patches
            double cx = sx + Math.cos(ang) * lean * height, cz = sz + Math.sin(ang) * lean * height;
            double dx = x - cx, dy = y - top, dz = z - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (Math.abs(d - R) < 0.62) {
                double lon = Math.atan2(dz, dx), lat = Math.asin(Math.max(-1, Math.min(1, dy / Math.max(0.01, d))));
                boolean cage = Math.abs(Math.sin(lon * 3 + lat * 2)) < 0.22 || Math.abs(Math.sin(lat * 4 - lon)) < 0.18;
                if (cage) return Material.DARK_OAK_WOOD;
                long cellH = DeepGenerator.hash(seed, (int) Math.floor(lon * 2.5), (int) Math.floor(lat * 3), (int) h);
                return DeepGenerator.unit(cellH) < 0.4 ? Material.SEA_LANTERN : (cellH & 1) == 0 ? Material.CYAN_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS;
            }
            if (d < R * 0.35) return Material.SEA_LANTERN; // a glowing heart
        }
        return null;
    }

    /** Lumen domes: a glowing cap on a short stem, strands trailing from its rim. Grid 20. */
    static Material dome(DeepGenerator g, DeepGenerator.Noise n, int x, int y, int z) {
        long seed = n.seed;
        int gx = Math.floorDiv(x, 20), gz = Math.floorDiv(z, 20);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = DeepGenerator.hash(seed, gx + i, gz + j, 431);
            if (DeepGenerator.unit(h) > 0.62) continue;
            int sx = (gx + i) * 20 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 432)) * 12);
            int sz = (gz + j) * 20 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 433)) * 12);
            double R = 3.5 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 434)) * 6;
            if (Math.abs(x - sx) > R + 1 || Math.abs(z - sz) > R + 1) continue;
            double stem = 4 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 435)) * 12;
            int up = siteFloor(g, n, sx, sz);
            double cy = up + stem;
            double dx = x - sx, dz = z - sz, flat = Math.hypot(dx, dz);
            if (y > up && y <= cy && flat < 0.8) return Material.PURPUR_PILLAR;
            double dy = (y - cy) / 0.7, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (y >= cy && Math.abs(d - R) < 0.62) {
                long sp = DeepGenerator.hash(seed, x, z * 31 + y, (int) h);
                return DeepGenerator.unit(sp) < 0.28 ? Material.PEARLESCENT_FROGLIGHT : (y - cy > R * 0.45) ? Material.MAGENTA_STAINED_GLASS : Material.PURPLE_STAINED_GLASS;
            }
            // strands hanging from the rim (thin panes, every few blocks round it)
            if (y < cy && y >= cy - (3 + (int) (R * 0.7)) && Math.abs(flat - (R - 0.6)) < 0.5) {
                double a = Math.atan2(dz, dx);
                if (Math.abs(Math.sin(a * Math.max(4, (int) R))) > 0.85) return Material.PURPLE_STAINED_GLASS_PANE;
            }
        }
        return null;
    }

    /** Crag needles: tall jagged cones of banded rock. Grid 15. */
    static Material needle(DeepGenerator.Noise n, int x, int y, int z, int up) {
        long seed = n.seed;
        int gx = Math.floorDiv(x, 15), gz = Math.floorDiv(z, 15);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 441)) > 0.6) continue;
            int sx = (gx + i) * 15 + 3 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 442)) * 9);
            int sz = (gz + j) * 15 + 3 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 443)) * 9);
            double r0 = 2.2 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 444)) * 4.5;
            if (Math.abs(x - sx) > r0 + 2 || Math.abs(z - sz) > r0 + 2) continue;
            double height = 22 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 445)) * 62;
            double lean = (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 446)) - 0.5) * 0.35;
            double ang = DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 447)) * Math.PI * 2;
            int base = up - 2;
            double t = (y - base) / height;
            if (t < 0 || t > 1) continue;
            double ax = sx + Math.cos(ang) * lean * (y - base), az = sz + Math.sin(ang) * lean * (y - base);
            double jag = n.detail.noise(x * 2.5, y * 1.6, z * 2.5, 0.5, 0.5, true) * 0.9;
            double r = r0 * Math.pow(1 - t, 0.8) + 0.4 + jag;
            if (Math.hypot(x - ax, z - az) > r) continue;
            int band = Math.floorMod(y + (int) (jag * 3), 7);
            if (t > 0.92) return Material.FIRE_CORAL_BLOCK;
            return band < 2 ? Material.RED_TERRACOTTA : band < 4 ? Material.BROWN_TERRACOTTA : band < 5 ? Material.DRIPSTONE_BLOCK : Material.GRANITE;
        }
        return null;
    }

    /** Hanging isles: flat-topped rock islands floating in the water, tapering below. Grid 70. The island over this
     *  column: {bottom y, top y, edge (0 = middle .. 1 = rim) x 100}, or null. Independent of y, so the generator asks once. */
    static int[] isle(DeepGenerator.Noise n, int x, int z) {
        long seed = n.seed;
        int gx = Math.floorDiv(x, 70), gz = Math.floorDiv(z, 70);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 451)) > 0.6) continue;
            int sx = (gx + i) * 70 + 18 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 452)) * 34);
            int sz = (gz + j) * 70 + 18 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 453)) * 34);
            double R = 9 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 454)) * 17;
            if (Math.abs(x - sx) > R + 4 || Math.abs(z - sz) > R + 4) continue;
            double d = Math.hypot(x - sx, z - sz) + n.n2(n.detail, x, z) * 3;
            if (d > R) continue;
            int cy = 120 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 455)) * 120); // 120..240
            double t = d / R;
            int topY = cy + (int) Math.round((1 - t * t) * 3);
            int botY = cy - (int) Math.round((1 - t) * R * 0.9);
            return new int[]{botY, topY, (int) (t * 100)};
        }
        return null;
    }

    static Material isleBlock(int[] span, int x, int y) {
        if (y == span[1]) return span[2] < 85 ? Material.MOSS_BLOCK : Material.MOSSY_COBBLESTONE;
        if (y >= span[1] - 2) return Material.DIRT;
        if (y == span[0] && (x & 3) == 0) return Material.SEA_LANTERN; // a few lights under them
        return (y + x) % 5 == 0 ? Material.ANDESITE : Material.STONE;
    }

    /** The Sulphur Sea's mushroom forest: giant sulphur caps on pale stems. Grid 24. */
    static Material mushroom(DeepGenerator g, DeepGenerator.Noise n, int x, int y, int z) {
        long seed = n.seed;
        int gx = Math.floorDiv(x, 24), gz = Math.floorDiv(z, 24);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = DeepGenerator.hash(seed, gx + i, gz + j, 461);
            if (DeepGenerator.unit(h) > 0.55) continue;
            int sx = (gx + i) * 24 + 5 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 462)) * 14);
            int sz = (gz + j) * 24 + 5 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 463)) * 14);
            double R = 4 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 464)) * 6;
            if (Math.abs(x - sx) > R + 1 || Math.abs(z - sz) > R + 1) continue;
            double stem = 8 + DeepGenerator.unit(DeepGenerator.hash(seed, gx + i, gz + j, 465)) * 22;
            int up = siteFloor(g, n, sx, sz);
            double cy = Math.min(318 - R * 0.6, up + stem);
            double dx = x - sx, dz = z - sz, flat = Math.hypot(dx, dz);
            double sr = 1.1 + R * 0.12;
            if (y > up && y < cy && flat <= sr) return Material.MUSHROOM_STEM;
            // the cap: a squashed dome; its underside gills a ring of stem-coloured blocks
            double dy = (y - cy) / 0.55, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (y >= cy && d <= R && d > R - 1.3) {
                long sp = DeepGenerator.hash(seed, x * 7 + y, z, (int) h);
                return DeepGenerator.unit(sp) < 0.16 ? Material.VERDANT_FROGLIGHT : (DeepGenerator.unit(sp) < 0.5 ? Material.YELLOW_TERRACOTTA : Material.LIME_TERRACOTTA);
            }
            if ((int) Math.floor(cy) == y && flat < R - 1 && flat > sr) return Material.OCHRE_FROGLIGHT; // gills: glowing from below
        }
        return null;
    }

    // =========================================================================================== small decoration (on the floor)
    /** Plants and small things on the floor; returns true if it placed something (the old open-sea decoration is skipped). */
    static boolean decorate(Kind k, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up,
                            org.bukkit.block.data.BlockData tallLow, org.bukkit.block.data.BlockData tallHigh, org.bukkit.block.data.BlockData pickles) {
        int y = up + 1;
        if (y >= DeepGenerator.SEA - 2 || d.getType(lx, y, lz) != Material.WATER) return k != Kind.OPEN && k != Kind.SHALLOWS;
        int r = rnd.nextInt(1000);
        switch (k) {
            case KELP -> {
                double kp = n.kelp.noise(x, z, 0.5, 0.5);
                if (r < 380 + (int) (kp * 250)) {
                    int top = DeepGenerator.SEA - 3 - rnd.nextInt(14);
                    for (int yy = y; yy <= top; yy++) {
                        if (d.getType(lx, yy, lz) != Material.WATER) break;
                        d.setBlock(lx, yy, lz, yy == top ? Material.KELP : Material.KELP_PLANT);
                    }
                } else if (r < 700) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 712) d.setBlock(lx, y, lz, pickles);
                else if (r < 718) d.setBlock(lx, y, lz, Material.MOSSY_COBBLESTONE);
            }
            case MEADOW -> {
                if (up > 100) {
                    if (r < 260) d.setBlock(lx, y, lz, Material.FIRE_CORAL_FAN);
                    else if (r < 420) d.setBlock(lx, y, lz, Material.FIRE_CORAL);
                    else if (r < 470) d.setBlock(lx, y, lz, Material.BRAIN_CORAL);
                    else if (r < 620 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, tallLow); d.setBlock(lx, y + 1, lz, tallHigh); }
                    else if (r < 760) d.setBlock(lx, y, lz, Material.SEAGRASS);
                    else if (r < 768) d.setBlock(lx, up, lz, Material.FIRE_CORAL_BLOCK);
                } else if (r < 120) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 135) d.setBlock(lx, y, lz, Material.DEAD_FIRE_CORAL_FAN);
            }
            case BULB -> {
                if (r < 14) { // a little bulb bush: a glowing knob on a root
                    d.setBlock(lx, y, lz, Material.MANGROVE_ROOTS);
                    if (d.getType(lx, y + 1, lz) == Material.WATER) d.setBlock(lx, y + 1, lz, Material.SEA_LANTERN);
                } else if (r < 120) d.setBlock(lx, y, lz, Material.AMETHYST_CLUSTER);
                else if (r < 260 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, tallLow); d.setBlock(lx, y + 1, lz, tallHigh); }
                else if (r < 420) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 440) d.setBlock(lx, y, lz, pickles);
            }
            case DUNES -> {
                if (r < 25) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 28) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
                else if (r < 30) d.setBlock(lx, y, lz, Material.DEAD_BRAIN_CORAL);
            }
            case CRAGS -> {
                if (r < 90) d.setBlock(lx, y, lz, Material.FIRE_CORAL_FAN);
                else if (r < 140) d.setBlock(lx, y, lz, Material.HORN_CORAL);
                else if (r < 260) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 268) d.setBlock(lx, up, lz, Material.MAGMA_BLOCK);
            }
            case PEAKS -> {
                if (r < 60) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 70) d.setBlock(lx, y, lz, pickles);
                else if (r < 80) d.setBlock(lx, y, lz, Material.COBBLESTONE);
            }
            case GLOW -> {
                if (r < 140) d.setBlock(lx, y, lz, Material.TUBE_CORAL_FAN);
                else if (r < 230) d.setBlock(lx, y, lz, Material.BUBBLE_CORAL);
                else if (r < 260) d.setBlock(lx, y, lz, pickles);
                else if (r < 420 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, tallLow); d.setBlock(lx, y + 1, lz, tallHigh); }
                else if (r < 440) d.setBlock(lx, up, lz, Material.TUBE_CORAL_BLOCK);
            }
            case ISLES -> {
                if (r < 150) d.setBlock(lx, y, lz, Material.SEAGRASS);
                else if (r < 165) d.setBlock(lx, y, lz, pickles);
            }
            default -> { return false; }
        }
        return true;
    }

    /** On top of a hanging isle: kelp, grass and pickles. */
    static void dressIsle(ChunkData d, Random rnd, int lx, int lz, int y, org.bukkit.block.data.BlockData pickles) {
        if (y >= DeepGenerator.SEA - 3 || d.getType(lx, y, lz) != Material.WATER) return;
        int r = rnd.nextInt(100);
        if (r < 18) {
            int h = 3 + rnd.nextInt(14);
            for (int i = 0; i < h && y + i < DeepGenerator.SEA - 2 && d.getType(lx, y + i, lz) == Material.WATER; i++)
                d.setBlock(lx, y + i, lz, i == h - 1 ? Material.KELP : Material.KELP_PLANT);
        } else if (r < 50) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 54) d.setBlock(lx, y, lz, pickles);
    }

    // =========================================================================================== for the plugin
    static Biome biome(Kind k) {
        return switch (k) {
            case SHALLOWS -> Biome.WARM_OCEAN;
            case KELP, ISLES -> Biome.OCEAN;
            case MEADOW, BULB, GLOW -> Biome.DEEP_LUKEWARM_OCEAN;
            case PEAKS -> Biome.COLD_OCEAN;
            case DUNES, CRAGS -> Biome.LUKEWARM_OCEAN;
            case OPEN -> Biome.DEEP_OCEAN;
        };
    }

    static String title(Kind k) {
        return switch (k) {
            case SHALLOWS -> "The Sunlit Shallows";
            case KELP -> "The Kelpwood";
            case MEADOW -> "The Ember Meadows";
            case BULB -> "The Lantern Groves";
            case DUNES -> "The Pale Dunes";
            case CRAGS -> "The Needle Fields";
            case PEAKS -> "The Drowned Peaks";
            case GLOW -> "The Lumen Reef";
            case ISLES -> "The Hanging Isles";
            case OPEN -> "The Open Sea";
        };
    }

    static String subtitle(Kind k) {
        return switch (k) {
            case SHALLOWS -> "Warm water, close to the light.";
            case KELP -> "Stay close. Things hide in the stalks.";
            case MEADOW -> "Red grass on high ground.";
            case BULB -> "The trees are glowing.";
            case DUNES -> "Sand, and whatever the sand buried.";
            case CRAGS -> "Mind the needles.";
            case PEAKS -> "A mountain range the sea swallowed.";
            case GLOW -> "Lights in the deep blue.";
            case ISLES -> "The rocks float here. Nobody knows why.";
            case OPEN -> "";
        };
    }

    /** The map colour of a kind (the Abyssal Chart). */
    static java.awt.Color colour(Kind k) {
        return switch (k) {
            case SHALLOWS -> new java.awt.Color(120, 200, 210);
            case KELP -> new java.awt.Color(110, 170, 70);
            case MEADOW -> new java.awt.Color(190, 70, 90);
            case BULB -> new java.awt.Color(165, 140, 220);
            case DUNES -> new java.awt.Color(225, 210, 150);
            case CRAGS -> new java.awt.Color(140, 50, 30);
            case PEAKS -> new java.awt.Color(55, 62, 66);
            case GLOW -> new java.awt.Color(70, 150, 210);
            case ISLES -> new java.awt.Color(170, 120, 90);
            case OPEN -> new java.awt.Color(40, 90, 150);
        };
    }
}
