package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.Random;

/**
 * THE GLASS REEF (Deep 1.9.0): a reef of living crystal in the south-east of the Dark Ocean, round CX, CZ.
 *  - THE COLOUR WHEEL: every glass thing takes its colour from where it stands: the angle round the centre plus the
 *    distance out (hue()), so the whole reef turns through the rainbow in arms spiralling out from the middle
 *  - a white floor of calcite and quartz, crossed by glowing seams where the colours change and by mosaic rings of
 *    stained glass lit from underneath (sea lanterns under the glass)
 *  - GLASS CORAL TREES: branching trunks of stained glass with froglights glowing in their joints and glass fronds
 *  - PRISM SPIRES: hexagonal crystals, banded through the colours as they rise, a column of light inside
 *  - TERRARIUMS: glass bubbles on stalks with real air inside (the air never touches the water: every air block's
 *    neighbours are glass or more air), a moss floor, flowers, a little azalea and a lantern
 *  - CRYSTAL ARCHES of white and pale blue glass
 *  - THE PRISM SPIRE in the middle: a hexagonal glass tower from the floor to just under the surface, hollow and DRY
 *    inside (a door at its foot keeps the sea out), ladders up a lit core and a viewing room at the top
 * Biome WARM_OCEAN (clear water). Pure functions, like the rest of the generator.
 */
final class GlassReef {
    static final int CX = 1700, CZ = 2600, R = 290, BLEND = 60;
    static final int SPIRE_R = 15, SPIRE_TOP = DeepGenerator.SEA - 6, PLAZA_R = 34;
    static final String[] HUES = {"red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"};
    static final String[] FROG = {"ochre_froglight", "verdant_froglight", "pearlescent_froglight"};

    private GlassReef() { }

    static double weight(int x, int z) { return Regions.weight(x, z, CX, CZ, R, BLEND, 16); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, CX, CZ, R + BLEND + 40 + extra); }

    /** The colour wheel: 0..10, turning with the angle and stepping outward in a spiral. */
    static double wheel(int x, int z) {
        double a = Math.atan2(z - CZ, x - CX), d = Math.hypot(x - CX, z - CZ);
        return a / (2 * Math.PI) * HUES.length + d / 38.0;
    }

    static int hue(int x, int z) { return Math.floorMod((int) Math.floor(wheel(x, z)), HUES.length); }

    static String glass(int h) { return HUES[Math.floorMod(h, HUES.length)] + "_stained_glass"; }

    static String pane(int h) { return HUES[Math.floorMod(h, HUES.length)] + "_stained_glass_pane[waterlogged=true]"; }

    static String frog(int h) { return FROG[Math.floorMod(h, 3)]; }

    // =========================================================================================== the floor
    static double floor(DeepGenerator.Noise n, int x, int z) {
        double h = 54 + n.n2(n.hills, x * 0.8, z * 0.8) * 5 + n.n2(n.dunes, x, z) * 1.2;
        h = Math.floor(h / 2) * 2 + 0.5;                                  // soft white terraces
        double d = Math.hypot(x - CX, z - CZ);
        if (d < PLAZA_R + 26) {                                          // the plaza round the Prism Spire
            double t = 1 - Math.max(0, d - PLAZA_R) / 26.0;
            t = t * t * (3 - 2 * t);
            h = h * (1 - t) + 60 * t;
        }
        return h;
    }

    static boolean seam(int x, int z) {
        double v = wheel(x, z);
        double f = v - Math.floor(v);
        return f < 0.045 || f > 0.985;
    }

    static boolean mosaic(int x, int z) {
        double d = Math.hypot(x - CX, z - CZ);
        return d > PLAZA_R && Math.floorMod((int) d, 29) < 2;
    }

    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        double d = Math.hypot(x - CX, z - CZ);
        if (d < PLAZA_R) return (Math.floorMod((int) Math.floor(d), 4) == 0) ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ;
        if (seam(x, z)) return Material.SEA_LANTERN;
        if (mosaic(x, z)) return Material.matchMaterial(glass(hue(x, z)).toUpperCase());
        int r = rnd.nextInt(100);
        return r < 52 ? Material.CALCITE : r < 66 ? Material.SMOOTH_QUARTZ : r < 76 ? Material.QUARTZ_BLOCK : r < 86 ? Material.DIORITE : r < 93 ? Material.WHITE_TERRACOTTA : Material.GLASS;
    }

    static Material under(Random rnd, int y, int up) { return y == up - 1 ? Material.SEA_LANTERN : y > up - 4 ? Material.CALCITE : Material.STONE; }

    /** Glass fronds, amethyst, sea pickles. */
    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        int y = up + 1;
        if (y >= DeepGenerator.SEA - 2 || d.getType(lx, y, lz) != Material.WATER || !d.getType(lx, up, lz).isSolid()) return;
        if (Math.hypot(x - CX, z - CZ) < PLAZA_R + 2) return;
        // the block under the top: a lantern only under the glass mosaic (else the plain calcite)
        if (!d.getType(lx, up, lz).name().endsWith("STAINED_GLASS") && d.getType(lx, up - 1, lz) == Material.SEA_LANTERN) d.setBlock(lx, up - 1, lz, Material.CALCITE);
        int r = rnd.nextInt(1000), h = hue(x, z);
        if (r < 22) { // glass fronds, 1-3 tall
            int t = 1 + rnd.nextInt(3);
            for (int i = 0; i < t && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, Paint.bd(pane(h + (i == t - 1 ? 1 : 0))));
        } else if (r < 34) d.setBlock(lx, y, lz, Paint.bd("amethyst_cluster[facing=up,waterlogged=true]"));
        else if (r < 46) d.setBlock(lx, y, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(4)) + ",waterlogged=true]"));
        else if (r < 52) d.setBlock(lx, y, lz, Paint.bd("small_amethyst_bud[facing=up,waterlogged=true]"));
    }

    // =========================================================================================== structures
    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!near(p.x0 + 8, p.z0 + 8, 30)) return;
        long seed = n.seed;
        // glass coral trees
        for (int gx = Math.floorDiv(p.x0 - 12, 20); gx <= Math.floorDiv(p.x0 + 27, 20); gx++)
            for (int gz = Math.floorDiv(p.z0 - 12, 20); gz <= Math.floorDiv(p.z0 + 27, 20); gz++) {
                if (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 501)) > 0.62) continue;
                int tx = gx * 20 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 502)) * 12);
                int tz = gz * 20 + 4 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 503)) * 12);
                if (weight(tx, tz) < 0.85 || Math.hypot(tx - CX, tz - CZ) < PLAZA_R + 6) continue;
                if (p.touches(tx - 11, tz - 11, tx + 11, tz + 11)) tree(g, n, p, tx, tz, DeepGenerator.hash(seed, gx, gz, 504));
            }
        // prism spires
        for (int gx = Math.floorDiv(p.x0 - 8, 64); gx <= Math.floorDiv(p.x0 + 23, 64); gx++)
            for (int gz = Math.floorDiv(p.z0 - 8, 64); gz <= Math.floorDiv(p.z0 + 23, 64); gz++) {
                if (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 511)) > 0.5) continue;
                int sx = gx * 64 + 10 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 512)) * 44);
                int sz = gz * 64 + 10 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 513)) * 44);
                if (weight(sx, sz) < 0.9 || Math.hypot(sx - CX, sz - CZ) < PLAZA_R + 14) continue;
                if (p.touches(sx - 7, sz - 7, sx + 7, sz + 7)) crystal(g, n, p, sx, sz, DeepGenerator.hash(seed, gx, gz, 514));
            }
        // terrariums
        for (int gx = Math.floorDiv(p.x0 - 10, 52); gx <= Math.floorDiv(p.x0 + 25, 52); gx++)
            for (int gz = Math.floorDiv(p.z0 - 10, 52); gz <= Math.floorDiv(p.z0 + 25, 52); gz++) {
                if (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 521)) > 0.32) continue;
                int bx = gx * 52 + 12 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 522)) * 28);
                int bz = gz * 52 + 12 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 523)) * 28);
                if (weight(bx, bz) < 0.9 || Math.hypot(bx - CX, bz - CZ) < PLAZA_R + 16) continue;
                if (p.touches(bx - 8, bz - 8, bx + 8, bz + 8)) terrarium(g, n, p, bx, bz, DeepGenerator.hash(seed, gx, gz, 524));
            }
        // crystal arches
        for (int gx = Math.floorDiv(p.x0 - 20, 90); gx <= Math.floorDiv(p.x0 + 35, 90); gx++)
            for (int gz = Math.floorDiv(p.z0 - 20, 90); gz <= Math.floorDiv(p.z0 + 35, 90); gz++) {
                if (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 531)) > 0.3) continue;
                int ax = gx * 90 + 20 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 532)) * 50);
                int az = gz * 90 + 20 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, 533)) * 50);
                if (weight(ax, az) < 0.9 || Math.hypot(ax - CX, az - CZ) < PLAZA_R + 24) continue;
                if (p.touches(ax - 18, az - 18, ax + 18, az + 18)) arch(g, n, p, ax, az, DeepGenerator.hash(seed, gx, gz, 534));
            }
        if (p.touches(CX - SPIRE_R - 2, CZ - SPIRE_R - 2, CX + SPIRE_R + 2, CZ + SPIRE_R + 2)) spire(p);
    }

    /** Only into water: never through the floor or another structure. */
    static void wet(Paint p, int x, int y, int z, String s) {
        if (p.in(x, y, z) && p.get(x, y, z) == Material.WATER) p.set(x, y, z, s);
    }

    static double ur(long h, int k) { return DeepGenerator.unit(DeepGenerator.hash(h, k, 3, 5)); }

    // ---- a glass coral tree
    static void tree(DeepGenerator g, DeepGenerator.Noise n, Paint p, int tx, int tz, long h) {
        int base = g.upperFloor(n, tx, tz) + 1;
        int hue = hue(tx, tz);
        int trunk = 5 + (int) (ur(h, 1) * 7);
        boolean thick = ur(h, 2) < 0.4;
        for (int y = base; y < base + trunk; y++) {
            wet(p, tx, y, tz, glass(hue));
            if (thick) { wet(p, tx + 1, y, tz, glass(hue)); wet(p, tx, y, tz + 1, glass(hue)); wet(p, tx + 1, y, tz + 1, glass(hue)); }
            if ((y - base) % 4 == 2) wet(p, tx, y, tz, frog(hue));
        }
        int arms = 3 + (int) (ur(h, 3) * 3);
        for (int a = 0; a < arms; a++) {
            double ang = (a + ur(h, 10 + a) * 0.6) * 2 * Math.PI / arms;
            branch(p, tx + 0.5, base + trunk - 1 - (a % 2), tz + 0.5, Math.cos(ang), Math.sin(ang), 4 + ur(h, 20 + a) * 4, 2, hue + (a % 2 == 0 ? 1 : -1), h + a * 97);
        }
        wet(p, tx, base + trunk, tz, frog(hue + 1));
    }

    static void branch(Paint p, double x, double y, double z, double dx, double dz, double len, int depth, int hue, long h) {
        double rise = 0.55 + ur(h, 1) * 0.5;
        double steps = len * 1.6;
        double cx = x, cy = y, cz = z;
        for (int i = 0; i < steps; i++) {
            cx += dx * 0.62; cz += dz * 0.62; cy += rise * 0.62;
            wet(p, (int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz), glass(hue));
        }
        int ex = (int) Math.floor(cx), ey = (int) Math.floor(cy), ez = (int) Math.floor(cz);
        wet(p, ex, ey, ez, frog(hue));
        // fronds round the tip
        for (int k = 0; k < 4; k++) {
            int ox = k == 0 ? 1 : k == 1 ? -1 : 0, oz = k == 2 ? 1 : k == 3 ? -1 : 0;
            if (ur(h, 40 + k) < 0.7) wet(p, ex + ox, ey, ez + oz, pane(hue + 1));
        }
        if (ur(h, 50) < 0.6) wet(p, ex, ey + 1, ez, "amethyst_cluster[facing=up,waterlogged=true]");
        if (depth <= 0) return;
        double a0 = Math.atan2(dz, dx);
        for (int k = -1; k <= 1; k += 2) {
            double a = a0 + k * (0.55 + ur(h, 60 + k) * 0.4);
            branch(p, cx, cy, cz, Math.cos(a), Math.sin(a), len * 0.62, depth - 1, hue + k, h * 31 + k);
        }
    }

    // ---- a prism spire: a hexagonal crystal banded through the colours
    static double hex(double u, double v) {
        return Math.max(Math.abs(u), Math.max(Math.abs(u * 0.5 + v * 0.866), Math.abs(u * 0.5 - v * 0.866)));
    }

    static void crystal(DeepGenerator g, DeepGenerator.Noise n, Paint p, int sx, int sz, long h) {
        int base = g.upperFloor(n, sx, sz) + 1;
        double r0 = 2.5 + ur(h, 1) * 2.2;
        int height = 16 + (int) (ur(h, 2) * 30);
        double ang = ur(h, 3) * Math.PI, ca = Math.cos(ang), sa = Math.sin(ang);
        double lean = (ur(h, 4) - 0.5) * 0.25;
        int hue0 = hue(sx, sz);
        for (int y = base - 1; y < base + height; y++) {
            double t = (y - base) / (double) height;
            double r = t < 0.7 ? r0 : r0 * (1 - (t - 0.7) / 0.3) + 0.2;
            double ox = lean * (y - base) * ca, oz = lean * (y - base) * sa;
            int band = hue0 + Math.max(0, y - base) / 4;
            for (int x = (int) Math.floor(sx - r0 - 4); x <= sx + r0 + 4; x++) for (int z = (int) Math.floor(sz - r0 - 4); z <= sz + r0 + 4; z++) {
                if (!p.in(x, y, z)) continue;
                double u = (x + 0.5 - sx - ox) * ca + (z + 0.5 - sz - oz) * sa, v = -(x + 0.5 - sx - ox) * sa + (z + 0.5 - sz - oz) * ca;
                double hd = hex(u, v);
                if (hd > r) continue;
                String s;
                if (hd < 0.9 && t < 0.85) s = "sea_lantern";                       // the column of light inside
                else if (hd > r - 1.0) {
                    boolean edge = Math.abs(Math.abs(Math.atan2(v, u)) % (Math.PI / 3) - Math.PI / 6) > Math.PI / 6 - 0.18;
                    s = edge ? "white_stained_glass" : glass(band);
                } else s = "glass";
                p.set(x, y, z, s);
            }
        }
    }

    // ---- a terrarium: a glass bubble on a stalk with real air and a little garden inside
    static void terrarium(DeepGenerator g, DeepGenerator.Noise n, Paint p, int bx, int bz, long h) {
        int base = g.upperFloor(n, bx, bz) + 1;
        double r = 4 + ur(h, 1) * 2.4;
        int stalk = 2 + (int) (ur(h, 2) * 3);
        double cy = base + stalk + r - 0.5;
        int hue = hue(bx, bz);
        for (int y = base; y < base + stalk + 1; y++) { p.set(bx, y, bz, glass(hue)); if (y == base) for (int k = 0; k < 4; k++) wet(p, bx + (k == 0 ? 1 : k == 1 ? -1 : 0), y, bz + (k == 2 ? 1 : k == 3 ? -1 : 0), glass(hue + 1)); }
        int ri = (int) Math.ceil(r) + 1;
        int floorY = (int) Math.floor(cy - r * 0.45);
        for (int x = bx - ri; x <= bx + ri; x++) for (int y = (int) Math.floor(cy - ri); y <= cy + ri; y++) for (int z = bz - ri; z <= bz + ri; z++) {
            if (!p.in(x, y, z)) continue;
            double dd = Math.sqrt(sq(x + 0.5 - (bx + 0.5)) + sq(y + 0.5 - cy) + sq(z + 0.5 - (bz + 0.5)));
            if (dd > r) continue;
            if (dd > r - 1.05) { // the shell: clear glass, a ring of colour round its middle
                boolean band = Math.abs(y + 0.5 - cy) < 0.8;
                p.set(x, y, z, band ? glass(hue) : (Paint.u(x, y, z, 77) < 0.08 ? "light_blue_stained_glass" : "glass"));
                continue;
            }
            // inside: dry. (Every block here has only shell or more inside round it: the sea can't get in.)
            if (y < floorY) p.set(x, y, z, "dirt");
            else if (y == floorY) p.set(x, y, z, "moss_block");
            else p.set(x, y, z, "air");
        }
        // the garden
        String[] flowers = {"allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "pink_tulip", "blue_orchid"};
        for (int k = 0; k < 6; k++) {
            int fx = bx + (int) Math.round((ur(h, 10 + k) - 0.5) * (r * 1.1)), fz = bz + (int) Math.round((ur(h, 20 + k) - 0.5) * (r * 1.1));
            if (p.in(fx, floorY + 1, fz) && p.get(fx, floorY + 1, fz) == Material.AIR && p.get(fx, floorY, fz) == Material.MOSS_BLOCK)
                p.set(fx, floorY + 1, fz, k == 0 ? (ur(h, 3) < 0.5 ? "flowering_azalea" : "azalea") : flowers[(int) (ur(h, 30 + k) * flowers.length)]);
        }
        int topY = (int) Math.floor(cy + r - 1.5);
        if (p.in(bx, topY, bz) && p.get(bx, topY, bz) == Material.AIR) p.set(bx, topY, bz, "lantern[hanging=true]");
        if (p.in(bx, topY + 1, bz)) p.set(bx, topY + 1, bz, "glass");
    }

    static double sq(double v) { return v * v; }

    // ---- a crystal arch
    static void arch(DeepGenerator g, DeepGenerator.Noise n, Paint p, int ax, int az, long h) {
        int base = g.upperFloor(n, ax, az);
        double R = 9 + ur(h, 1) * 7, th = 1.5;
        double a = ur(h, 2) * Math.PI, ux = Math.cos(a), uz = Math.sin(a);
        for (int x = ax - 18; x <= ax + 18; x++) for (int z = az - 18; z <= az + 18; z++) {
            double along = (x - ax) * ux + (z - az) * uz, across = -(x - ax) * uz + (z - az) * ux;
            if (Math.abs(across) > th) continue;
            for (int y = base; y <= base + R + 2; y++) {
                if (!p.in(x, y, z)) continue;
                double rr = Math.hypot(along, y - base);
                if (Math.abs(rr - R) >= th) continue;
                boolean key = Math.abs(along) < 1 && y > base + R - 2;
                p.set(x, y, z, key ? "sea_lantern" : Math.floorMod((int) Math.round(Math.atan2(y - base, along) * 6), 2) == 0 ? "white_stained_glass" : "light_blue_stained_glass");
            }
        }
    }

    // ---- the Prism Spire
    /** Inside the spire's dry hall (for the Ocean's rules: no pressure, things dropped don't sink). */
    static boolean insideSpire(double x, double y, double z) {
        if (y < 61 || y > SPIRE_TOP - 1) return false;
        double r = spireR((int) Math.floor(y)) - 2.2;
        return hex(x - CX - 0.5, z - CZ - 0.5) < r;
    }

    static double spireR(int y) {
        double t = (y - 60) / (double) (SPIRE_TOP - 60);
        return Math.max(4.5, SPIRE_R * (1 - t * 0.55));
    }

    static void spire(Paint p) {
        int y0 = 60, door = 0;
        for (int y = y0; y <= SPIRE_TOP + 1; y++) {
            double r = spireR(y);
            boolean roof = y >= SPIRE_TOP;
            for (int x = CX - SPIRE_R - 1; x <= CX + SPIRE_R + 1; x++) for (int z = CZ - SPIRE_R - 1; z <= CZ + SPIRE_R + 1; z++) {
                if (!p.in(x, y, z)) continue;
                double hd = hex(x - CX, z - CZ);
                if (hd > r) continue;
                String s;
                if (roof || y == y0) s = y == y0 ? (Math.floorMod((int) hd, 3) == 0 ? "sea_lantern" : "smooth_quartz") : "glass";
                else if (hd > r - 1) s = glass((y - y0) / 6);                                        // the outer skin, banded
                else if (hd > r - 2) s = (y % 12 == 0) ? "sea_lantern" : "glass";                     // the inner skin
                else if (hd < 1.6) s = "quartz_pillar[axis=y]";                                        // the core
                else s = "air";                                                                       // the dry hall
                p.set(x, y, z, s);
            }
            // a light every few floors and ladders up the core (on its south face)
            if (y > y0 && !roof) {
                p.set(CX, y, CZ + 2, "ladder[facing=south]");
                if (y % 8 == 0) for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3;
                    int lx = CX + (int) Math.round(Math.cos(a) * (r - 2.6)), lz = CZ + (int) Math.round(Math.sin(a) * (r - 2.6));
                    if (p.in(lx, y, lz)) p.set(lx, y, lz, frog(k));
                }
            }
        }
        // landings every 40 blocks: a ring of quartz slabs round the core to stand on
        for (int y = y0 + 40; y < SPIRE_TOP - 6; y += 40) for (int x = CX - 4; x <= CX + 4; x++) for (int z = CZ - 4; z <= CZ + 4; z++) {
            double hd = hex(x - CX, z - CZ);
            if (hd >= 1.6 && hd < 4 && !(x == CX && z == CZ + 2) && p.in(x, y, z)) p.set(x, y, z, "smooth_quartz_slab[type=bottom]");
        }
        // the viewing room at the top: a floor and benches under the glass roof
        int vy = SPIRE_TOP - 6;
        for (int x = CX - 8; x <= CX + 8; x++) for (int z = CZ - 8; z <= CZ + 8; z++) {
            double hd = hex(x - CX, z - CZ);
            if (hd < spireR(vy) - 2 && hd >= 1.6 && !(x == CX && z == CZ + 2) && p.in(x, vy, z)) p.set(x, vy, z, Math.floorMod(x + z, 2) == 0 ? "smooth_quartz" : "white_carpet");
        }
        // the door, facing south, with a lit step out on the plaza
        int dz = CZ + (int) Math.floor(SPIRE_R * 0.866) ;
        for (int z = CZ + 2; z <= CZ + SPIRE_R + 1; z++) for (int y = y0 + 1; y <= y0 + 2; y++) {
            if (!p.in(CX, y, z)) continue;
            double hd = hex(0, z - CZ);
            if (hd > spireR(y) - 2 && hd <= spireR(y)) {
                boolean outer = hd > spireR(y) - 1;
                p.set(CX, y, z, outer ? (y == y0 + 1 ? "dark_oak_door[facing=south,half=lower,hinge=left]" : "dark_oak_door[facing=south,half=upper,hinge=left]") : "air");
            }
        }
        door = dz;
    }
}
