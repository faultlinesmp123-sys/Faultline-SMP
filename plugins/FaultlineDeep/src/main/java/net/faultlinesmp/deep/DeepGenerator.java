package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexNoiseGenerator;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.List;
import java.util.Random;

/**
 * THE DARK OCEAN's terrain (world faultline_deep). Water everywhere, nothing above the surface. Top to bottom:
 *
 *  - the surface at y = SEA (300 since 1.5.0, was 100); you arrive at 0, 300, 0. The Sunlit Sea's floor stays ~46, so it's
 *    ~250 blocks of open water down to it; the Sulphur Sea rises on cliffs to just under the surface
 *  - THE SUNLIT SEA (the floor ~38-60): sand dunes, gravel and clay, kelp forests, a warm coral reef round 0,0, the
 *    "Sunken Sea" fields (navystone = cyan terracotta, prism crystal spires of prismarine with sea-lantern cores),
 *    stone arches, sea stacks that climb toward the light, whale falls (bone ribs) and blue holes
 *  - THE CRUST: tuff and deepslate, honeycombed with winding caves (two-noise "spaghetti" tunnels and a few caverns),
 *    lit by glow lichen and sea pickles, with Dark Loot chests in its pockets. The blue holes are CHIMNEYS: twisting
 *    shafts from the sea floor down through the crust. You can only reach the abyss through caves.
 *  - THE ABYSS (from y = -50 up to the crust's underside at ~5-20): pitch dark, stalactites hanging from the ceiling,
 *    a floor of black sand (black concrete powder, always on solid ground so it never falls) with seagrass and kelp,
 *    hydrothermal vents (magma + bubble columns), sea-pickle and crying-obsidian glows, sculk, basalt pillars from
 *    floor to ceiling, and rock MOUNDS with ocean caves through them (more chests)
 *  - the build area: a flat 150 x 150 floor, its top block at exactly y = -50, centred on 681, -951. Over it stands THE
 *    BUBBLE (Town.java): a glass dome with no water inside, an abandoned town and a 50 x 50 plot to build on
 *  - abandoned RUINS all over the floor, Sunlit Sea and Abyss (Ruins.java)
 *
 * Everything is a pure function of (seed, x, y, z), so features that cross chunk borders (spires, arches, chimneys)
 * line up without a populator. Runs on the world generation threads: nothing here touches the world.
 */
final class DeepGenerator extends ChunkGenerator {

    static final int SEA = 300, FLOOR = -50; // 1.5.0: the surface went up from 100 to 300
    static final int FLAT_X = 681, FLAT_Z = -951, FLAT_HALF = 75, FLAT_BLEND = 28;

    private volatile Noise noise;

    private Noise noise(WorldInfo info) {
        Noise n = noise;
        if (n == null || n.seed != info.getSeed()) { n = new Noise(info.getSeed()); noise = n; }
        return n;
    }

    /** All the noise fields, made once per seed. SimplexNoiseGenerator is read-only after construction: thread safe. */
    static final class Noise {
        final long seed;
        final SimplexOctaveGenerator hills, dunes, abyss, ceil, region, kelp, mound, ridge, canyon, vents, coral;
        final SimplexNoiseGenerator c1, c2, c3, m1, m2, warpX, warpZ;
        final SimplexOctaveGenerator sulphur;         // 1.4.0 (made last: everything before keeps its old shape)
        final SimplexNoiseGenerator sulphurEdge;

        Noise(long seed) {
            this.seed = seed;
            Random r = new Random(seed ^ 0x5DEECE66DL);
            hills = oct(r, 4, 1 / 220.0);
            dunes = oct(r, 2, 1 / 26.0);
            abyss = oct(r, 3, 1 / 90.0);
            ceil = oct(r, 3, 1 / 70.0);
            region = oct(r, 2, 1 / 420.0);
            kelp = oct(r, 2, 1 / 60.0);
            mound = oct(r, 3, 1 / 110.0);
            ridge = oct(r, 2, 1 / 160.0);
            canyon = oct(r, 2, 1 / 260.0);
            vents = oct(r, 2, 1 / 75.0);
            coral = oct(r, 2, 1 / 18.0);
            c1 = new SimplexNoiseGenerator(r.nextLong());
            c2 = new SimplexNoiseGenerator(r.nextLong());
            c3 = new SimplexNoiseGenerator(r.nextLong());
            m1 = new SimplexNoiseGenerator(r.nextLong());
            m2 = new SimplexNoiseGenerator(r.nextLong());
            warpX = new SimplexNoiseGenerator(r.nextLong());
            warpZ = new SimplexNoiseGenerator(r.nextLong());
            sulphur = oct(r, 3, 1 / 88.0);
            sulphurEdge = new SimplexNoiseGenerator(r.nextLong());
        }

        private static SimplexOctaveGenerator oct(Random r, int octaves, double scale) {
            SimplexOctaveGenerator g = new SimplexOctaveGenerator(r.nextLong(), octaves);
            g.setScale(scale);
            return g;
        }

        double n2(SimplexOctaveGenerator g, double x, double z) { return g.noise(x, z, 0.5, 0.5, true); }

        long seed() { return seed; }
    }

    // =========================================================================================== shape
    /** How much of the flat build area this column is in: 1 inside, easing to 0 over FLAT_BLEND blocks outside. */
    static double flatWeight(int x, int z) {
        double dx = Math.max(0, Math.abs(x - FLAT_X) - FLAT_HALF), dz = Math.max(0, Math.abs(z - FLAT_Z) - FLAT_HALF);
        double d = Math.hypot(dx, dz);
        if (d >= FLAT_BLEND) return 0;
        double t = 1 - d / FLAT_BLEND;
        return t * t * (3 - 2 * t);
    }

    /** "Sunken Sea" fields (navystone, prism spires) vs plain sand: -1..1. */
    double region(Noise n, int x, int z) { return n.n2(n.region, x, z); }

    /** The warm reef round the spawn point: 1 at 0,0, fading out by 140 blocks. */
    static double reef(int x, int z) {
        double d = Math.hypot(x, z);
        return d > 140 ? 0 : 1 - d / 140;
    }

    /** The Sunlit Sea's floor (the top solid block). */
    int upperFloor(Noise n, int x, int z) {
        double h = 46 + n.n2(n.hills, x, z) * 11 + n.n2(n.dunes, x, z) * 2.2;
        double m = n.n2(n.mound, x + 4000, z - 3000);
        if (m > 0.45) h += Math.floor((m - 0.45) * 60 / 3) * 3; // seamounts, terraced
        double cy = 1 - Math.abs(n.n2(n.canyon, x, z));
        if (cy > 0.88) h -= (cy - 0.88) * 110;                  // canyons winding across the sea floor
        h += reef(x, z) * 8;                                    // the reef rises toward the light
        h = Math.max(30, Math.min(86, h));
        double sw = Sulphur.weight(n, x, z);                    // the Sulphur Sea: shelves and islands out of the water
        if (sw > 0) h = h * (1 - sw) + Sulphur.floor(n, x, z) * sw;
        double mw = MermaidDen.weight(x, z);                    // the Mermaid Den: a reef crater rising almost to the surface
        if (mw > 0) h = h * (1 - mw) + MermaidDen.floor(n, x, z) * mw;
        return (int) Math.round(h);
    }

    /** The underside of the crust (the abyss's ceiling). */
    int abyssCeiling(Noise n, int x, int z) {
        double c = 12 + n.n2(n.ceil, x, z) * 7;
        c = c + (8 - c) * flatWeight(x, z); // over the build area: high enough for 50+ blocks of open water
        return (int) Math.round(c);
    }

    /** The abyss floor (the top solid block), mounds aside. */
    int abyssFloor(Noise n, int x, int z) {
        double f = FLOOR + n.n2(n.abyss, x, z) * 3.2 + n.n2(n.dunes, x * 0.7, z * 0.7) * 1.2;
        double rg = 1 - Math.abs(n.n2(n.ridge, x, z)); // ridges of rock across the floor
        if (rg > 0.82) f += (rg - 0.82) * 70;
        double w = flatWeight(x, z);
        return (int) Math.round(f * (1 - w) + FLOOR * w);
    }

    /** A rock mound on the abyss floor (its top), or Integer.MIN_VALUE: ocean caves run through these. */
    int moundTop(Noise n, int x, int z) {
        if (flatWeight(x, z) > 0) return Integer.MIN_VALUE;
        double m = n.n2(n.mound, x, z);
        if (m < 0.38) return Integer.MIN_VALUE;
        return FLOOR + (int) Math.round((m - 0.38) * 70);
    }

    // =========================================================================================== features by grid cell
    static long hash(long seed, int a, int b, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + a * 0x632BE59BD9B4E019L + b * 0x85157AF5L + salt * 0x27D4EB2FL;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return h;
    }

    static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }

    /** A chimney (blue hole) in this 144-block cell: {x, z} of its mouth, or null (about 2 cells in 3 have one). */
    static int[] chimney(long seed, int cx, int cz) {
        long h = hash(seed, cx, cz, 11);
        if (unit(h) > 0.66) return null;
        int x = cx * 144 + 24 + (int) (unit(hash(seed, cx, cz, 12)) * 96), z = cz * 144 + 24 + (int) (unit(hash(seed, cx, cz, 13)) * 96);
        if (flatWeight(x, z) > 0 || Sulphur.near(x, z, 30) || MermaidDen.near(x, z, 30) || Trench.near(x, z, 30)) return null;
        return new int[]{x, z};
    }

    /** The chimneys whose winding shaft can reach this column (usually none). */
    static java.util.List<int[]> chimneysNear(long seed, int x, int z) {
        java.util.List<int[]> out = null;
        int cx = Math.floorDiv(x, 144), cz = Math.floorDiv(z, 144);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int[] c = chimney(seed, cx + i, cz + j);
            if (c == null || Math.abs(x - c[0]) > 22 || Math.abs(z - c[1]) > 22) continue;
            if (out == null) out = new java.util.ArrayList<>(2);
            out.add(c);
        }
        return out == null ? java.util.List.of() : out;
    }

    /** Distance from (x, y, z) to the nearest of those chimneys' winding axes (sideways). */
    double chimneyDist(Noise n, java.util.List<int[]> near, int x, int y, int z) {
        double best = 1e9;
        for (int[] c : near) {
            // the shaft wanders as it goes down
            double ox = n.warpX.noise(c[0] * 0.01, y / 22.0) * 9, oz = n.warpZ.noise(c[1] * 0.01, y / 22.0) * 9;
            best = Math.min(best, Math.hypot(x - (c[0] + ox), z - (c[1] + oz)));
        }
        return best;
    }

    /** Prism crystal spires (Sunken Sea fields): true where (x, y, z) is inside one. 0 = no, 1 = shell, 2 = glowing core. */
    int spire(Noise n, int x, int y, int z, int floor) {
        int gx = Math.floorDiv(x, 18), gz = Math.floorDiv(z, 18);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = hash(n.seed, gx + i, gz + j, 21);
            if (unit(h) > 0.55) continue;
            int sx = (gx + i) * 18 + 3 + (int) (unit(hash(n.seed, gx + i, gz + j, 22)) * 12);
            int sz = (gz + j) * 18 + 3 + (int) (unit(hash(n.seed, gx + i, gz + j, 23)) * 12);
            if (region(n, sx, sz) < 0.15) continue;
            double height = 8 + unit(hash(n.seed, gx + i, gz + j, 24)) * 24, r0 = 1.8 + unit(hash(n.seed, gx + i, gz + j, 25)) * 2.6;
            double lean = (unit(hash(n.seed, gx + i, gz + j, 26)) - 0.5) * 0.7, ang = unit(hash(n.seed, gx + i, gz + j, 27)) * Math.PI * 2;
            int base = floor - 1;
            double t = (y - base) / height;
            if (t < 0 || t > 1) continue;
            double ax = sx + Math.cos(ang) * lean * (y - base), az = sz + Math.sin(ang) * lean * (y - base);
            double r = r0 * (1 - t) + 0.35;
            double d = Math.hypot(x - ax, z - az);
            if (d <= r) return d < r * 0.4 && t < 0.85 ? 2 : 1;
        }
        return 0;
    }

    /** Stone arches: a half ring standing on the sea floor. */
    boolean arch(Noise n, int x, int y, int z, int floor) {
        int gx = Math.floorDiv(x, 70), gz = Math.floorDiv(z, 70);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = hash(n.seed, gx + i, gz + j, 31);
            if (unit(h) > 0.35) continue;
            int ax = (gx + i) * 70 + 15 + (int) (unit(hash(n.seed, gx + i, gz + j, 32)) * 40);
            int az = (gz + j) * 70 + 15 + (int) (unit(hash(n.seed, gx + i, gz + j, 33)) * 40);
            double R = 9 + unit(hash(n.seed, gx + i, gz + j, 34)) * 10, th = 2.2 + unit(hash(n.seed, gx + i, gz + j, 35)) * 1.5;
            double a = unit(hash(n.seed, gx + i, gz + j, 36)) * Math.PI;
            double ux = Math.cos(a), uz = Math.sin(a);
            double along = (x - ax) * ux + (z - az) * uz, across = -(x - ax) * uz + (z - az) * ux;
            if (Math.abs(across) > th) continue;
            double baseY = floor - 2;
            double rr = Math.hypot(along, (y - baseY));
            if (y >= baseY && Math.abs(rr - R) < th) return true;
        }
        return false;
    }

    /** Sea stacks: tall rock pillars climbing toward the light (never out of the water). Their top, or MIN. */
    int stackTop(Noise n, int x, int z) {
        int gx = Math.floorDiv(x, 48), gz = Math.floorDiv(z, 48);
        int best = Integer.MIN_VALUE;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = hash(n.seed, gx + i, gz + j, 41);
            if (unit(h) > 0.3) continue;
            if (Sulphur.near((gx + i) * 48 + 24, (gz + j) * 48 + 24, 0)) continue; // the Sulphur Sea has its own columns
            if (MermaidDen.near((gx + i) * 48 + 24, (gz + j) * 48 + 24, 0)) continue; // and the Den is the mermaids' (1.7.0)
            int sx = (gx + i) * 48 + 8 + (int) (unit(hash(n.seed, gx + i, gz + j, 42)) * 32);
            int sz = (gz + j) * 48 + 8 + (int) (unit(hash(n.seed, gx + i, gz + j, 43)) * 32);
            double r = 2.5 + unit(hash(n.seed, gx + i, gz + j, 44)) * 3.5;
            double d = Math.hypot(x - sx, z - sz) + n.dunes.noise(x, z, 0.5, 0.5) * 0.8;
            if (d > r) continue;
            int top = 70 + (int) (unit(hash(n.seed, gx + i, gz + j, 45)) * 22) - (int) (d * 1.5);
            best = Math.max(best, Math.min(SEA - 6, top));
        }
        return best;
    }

    /** Whale falls: a spine with arching ribs of bone on the sea floor. */
    boolean whale(Noise n, int x, int y, int z, int floor) {
        int gx = Math.floorDiv(x, 120), gz = Math.floorDiv(z, 120);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = hash(n.seed, gx + i, gz + j, 51);
            if (unit(h) > 0.4) continue;
            int wx = (gx + i) * 120 + 30 + (int) (unit(hash(n.seed, gx + i, gz + j, 52)) * 60);
            int wz = (gz + j) * 120 + 30 + (int) (unit(hash(n.seed, gx + i, gz + j, 53)) * 60);
            double a = unit(hash(n.seed, gx + i, gz + j, 54)) * Math.PI;
            double ux = Math.cos(a), uz = Math.sin(a);
            double along = (x - wx) * ux + (z - wz) * uz, across = -(x - wx) * uz + (z - wz) * ux;
            if (Math.abs(along) > 16) continue;
            int base = floor + 1;
            if (Math.abs(across) < 0.8 && y == base) return true; // the spine
            // a rib every 3 blocks along it, an arch 5 wide, getting smaller toward the tail
            if (Math.floorMod((int) Math.round(along), 3) != 0) continue;
            double R = 5.5 - Math.max(0, along) * 0.18;
            double rr = Math.hypot(across, y - base);
            if (y >= base && Math.abs(rr - R) < 0.7) return true;
        }
        return false;
    }

    /** The abyss's giant skeletons (bigger and rarer than the Sunlit Sea's whale falls). */
    boolean whaleAbyss(Noise n, int x, int floorTop, int z) { return whaleCell(n, x, z) != null; }

    private double[] whaleCell(Noise n, int x, int z) {
        int gx = Math.floorDiv(x, 200), gz = Math.floorDiv(z, 200);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (unit(hash(n.seed, gx + i, gz + j, 81)) > 0.6) continue;
            int wx = (gx + i) * 200 + 40 + (int) (unit(hash(n.seed, gx + i, gz + j, 82)) * 120);
            int wz = (gz + j) * 200 + 40 + (int) (unit(hash(n.seed, gx + i, gz + j, 83)) * 120);
            if (flatWeight(wx, wz) > 0) continue;
            double a = unit(hash(n.seed, gx + i, gz + j, 84)) * Math.PI, ux = Math.cos(a), uz = Math.sin(a);
            double along = (x - wx) * ux + (z - wz) * uz, across = -(x - wx) * uz + (z - wz) * ux;
            if (Math.abs(along) <= 28 && Math.abs(across) <= 9) return new double[]{along, across};
        }
        return null;
    }

    boolean whaleAbyssAt(Noise n, int x, int y, int z, int floorTop) {
        double[] c = whaleCell(n, x, z);
        if (c == null) return false;
        double along = c[0], across = c[1];
        int base = floorTop - 1;
        if (Math.abs(across) < 1.2 && y <= base + 2) return true; // the spine
        if (Math.floorMod((int) Math.round(along), 4) != 0 || along < -22) return false;
        double R = 8.5 - Math.max(0, along) * 0.2;
        double rr = Math.hypot(across, y - base);
        return y >= base && Math.abs(rr - R) < 0.8;
    }

    // =========================================================================================== blocks
    private static BlockData bd(String s) { return Bukkit.createBlockData(s); }

    private volatile BlockData chest;
    private BlockData glowLichenFloor, dripTip, dripFrustum, pickles, bubble, kelpTop, tallGrassLow, tallGrassHigh;

    /**
     * Chunks generate on several threads at once: made once, under a lock, with `chest` (volatile) written LAST, so a
     * thread that sees it set sees all the others too (before, a second thread could get past the check mid-setup and
     * hit a null, and that chunk failed to generate).
     */
    private void blocks() {
        if (chest != null) return;
        synchronized (this) {
            if (chest != null) return;
            makeBlocks();
        }
    }

    private void makeBlocks() {
        BlockData c = bd("minecraft:chest[waterlogged=true]");
        glowLichenFloor = bd("minecraft:glow_lichen[down=true,waterlogged=true]");
        dripTip = bd("minecraft:pointed_dripstone[vertical_direction=down,thickness=tip,waterlogged=true]");
        dripFrustum = bd("minecraft:pointed_dripstone[vertical_direction=down,thickness=frustum,waterlogged=true]");
        pickles = bd("minecraft:sea_pickle[pickles=4,waterlogged=true]");
        bubble = bd("minecraft:bubble_column[drag=false]");
        kelpTop = bd("minecraft:kelp[age=25]");
        tallGrassLow = bd("minecraft:tall_seagrass[half=lower]");
        tallGrassHigh = bd("minecraft:tall_seagrass[half=upper]");
        chest = c;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        blocks();
        Noise n = noise(info);
        int minY = Math.max(info.getMinHeight(), -64);
        Random rnd = new Random(hash(n.seed, chunkX, chunkZ, 99));
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = (chunkX << 4) + lx, z = (chunkZ << 4) + lz;
            column(n, data, rnd, minY, lx, lz, x, z);
        }
        chests(n, data, rnd, chunkX, chunkZ, minY);
        Paint p = new Paint(data, chunkX, chunkZ);
        Ruins.paint(this, n, p);  // abandoned ruins over the whole ocean floor
        Sulphur.paint(this, n, p); // the Sulphur Sea's columns, wrecks, ribcages, trees, fumaroles, pools and the Works
        Town.paint(this, p);      // the bubble and its drowned town over the build area
        MermaidDen.paint(this, n, p); // the Mermaid Den: the palace, the houses, coral trees, lamps, clams, the gates (1.7.0)
    }

    private void column(Noise n, ChunkData d, Random rnd, int minY, int lx, int lz, int x, int z) {
        int up = upperFloor(n, x, z), ceil = abyssCeiling(n, x, z), floor = abyssFloor(n, x, z), mound = moundTop(n, x, z);
        if (up - ceil < 14) ceil = up - 14;
        int floorTop = Math.max(floor, mound == Integer.MIN_VALUE ? floor : mound);
        double reg = region(n, x, z), reef = reef(x, z), sw = Sulphur.weight(n, x, z), mw = MermaidDen.weight(x, z);
        boolean sulphur = sw > 0.35, den = mw > 0.35;
        boolean sunken = reg > 0.15 && !sulphur && !den, warm = reef > 0.15 && !sulphur && !den;
        // the Trench's mouth (1.8.0): nothing stands on a floor that's about to be cut away
        Trench.Spot ts = Trench.near(x, z, 0) ? Trench.spot(x, z) : null;
        boolean mouth = Math.hypot(x - Trench.LX, z - Trench.LZ) < Trench.LAIR_R + Trench.WALL + 16 || ts != null && ts.d() < Trench.half(n, ts, x, up, z) + 4;
        // bedrock
        for (int y = minY; y <= minY + 2; y++) d.setBlock(lx, y, lz, y == minY || rnd.nextInt(3) > 0 ? Material.BEDROCK : Material.DEEPSLATE);
        // ---- the abyss floor + mounds
        for (int y = minY + 3; y <= floorTop; y++) {
            Material m;
            if (y > floor) { // inside a mound: blackstone with ocean caves running through
                double cv = Math.abs(n.m1.noise(x / 20.0, y / 12.0, z / 20.0)) + Math.abs(n.m2.noise(x / 20.0, y / 12.0, z / 20.0));
                if (cv < 0.22 && y < floorTop - 1 && y > floor + 1) { d.setBlock(lx, y, lz, Material.WATER); continue; }
                m = rnd.nextInt(40) == 0 ? Material.CRYING_OBSIDIAN : rnd.nextInt(6) == 0 ? Material.BASALT : Material.BLACKSTONE;
            } else if (y >= floor - 1) m = Material.BLACK_CONCRETE_POWDER; // the black sand
            else if (y >= floor - 4) m = Material.BLACKSTONE;
            else m = rnd.nextInt(10) == 0 ? Material.TUFF : Material.DEEPSLATE;
            d.setBlock(lx, y, lz, m);
        }
        // the black sand must never hang over a cave: give it a blackstone floor if the block under it is water
        // (it can't be: the floor is solid all the way down; this is only for the mound case below)
        // ---- the abyss water, up to the ceiling
        for (int y = floorTop + 1; y < ceil; y++) d.setBlock(lx, y, lz, Material.WATER);
        // ---- the crust
        java.util.List<int[]> chims = chimneysNear(n.seed, x, z);
        int band = (int) (n.dunes.noise(x, z, 0.5, 0.5) * 3);
        for (int y = ceil; y <= up; y++) {
            boolean cave = false;
            // (no caves near an island's surface: their water would spill out; 1.5.2: and none in the raised Sulphur plateau above
            // the old crust band, which made its chunks cost 3x to generate: 3 noise lookups a block, 250 blocks a column)
            if (y > ceil + 1 && y < up - 3 && !((sw > 0 || mw > 0) && (y > SEA - 14 || y > 70))) {
                double a = n.c1.noise(x / 44.0, y / 22.0, z / 44.0), b = n.c2.noise(x / 44.0, y / 22.0, z / 44.0);
                cave = Math.abs(a) < 0.1 && Math.abs(b) < 0.1;                                     // winding tunnels
                if (!cave) cave = n.c3.noise(x / 60.0, y / 26.0, z / 60.0) > 0.64;                  // caverns
            }
            // the chimneys: from just under the sea floor all the way down into the abyss
            double cd = chims.isEmpty() ? 1e9 : chimneyDist(n, chims, x, y, z);
            double cr = 3.6 + (y > up - 8 ? (y - (up - 8)) * 0.6 : 0); // the mouth opens wider (a blue hole)
            if (cd < cr) cave = true;
            if (cave) { d.setBlock(lx, y, lz, Material.WATER); continue; }
            Material m;
            if (y > up - 1) m = sulphur ? Sulphur.top(n, rnd, x, z, up) : den ? MermaidDen.top(n, rnd, x, z, up) : surface(n, rnd, x, z, sunken, warm, up);
            else if (sulphur && y > up - 8) m = Sulphur.under(rnd, y, up);
            else if (den && y > up - 8) m = MermaidDen.under(rnd, y, up);
            else if (y > up - 4) m = sunken ? Material.CYAN_TERRACOTTA : warm ? Material.SAND : Material.SANDSTONE;
            else if (cd < cr + 1.5) m = Material.DEEPSLATE_TILES;                                   // a chimney's walls
            else m = (Math.floorMod(y + band, 9) < 3) ? Material.DEEPSLATE : Material.TUFF;
            if (m == Material.TUFF && rnd.nextInt(90) == 0) m = Material.CALCITE;
            d.setBlock(lx, y, lz, m);
        }
        // ---- the Sunlit Sea
        for (int y = up + 1; y <= SEA; y++) d.setBlock(lx, y, lz, Material.WATER);
        // sea stacks
        int stack = mouth ? Integer.MIN_VALUE : stackTop(n, x, z);
        if (stack > up) for (int y = up + 1; y <= stack; y++)
            d.setBlock(lx, y, lz, y > stack - 2 ? Material.MOSSY_COBBLESTONE : (y % 5 == 0 ? Material.ANDESITE : Material.STONE));
        // the rest, block by block above the floor (spires, arches, whale falls), only where it's still water
        for (int y = up + 1; y <= Math.min(SEA - 3, up + 40) && !mouth; y++) {
            if (d.getType(lx, y, lz) != Material.WATER) continue;
            int sp = sunken ? spire(n, x, y, z, up) : 0;
            if (sp > 0) { d.setBlock(lx, y, lz, sp == 2 ? Material.SEA_LANTERN : (y + x + z) % 3 == 0 ? Material.DARK_PRISMARINE : Material.PRISMARINE); continue; }
            if (sulphur || mw > 0) continue;
            if (y <= up + 26 && arch(n, x, y, z, up)) { d.setBlock(lx, y, lz, y % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE); continue; }
            if (y <= up + 8 && whale(n, x, y, z, up)) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
        }
        if (sulphur) Sulphur.decorate(this, n, d, rnd, lx, lz, x, z, up);
        else if (den) MermaidDen.decorate(this, n, d, rnd, lx, lz, x, z, up);
        else if (mw <= 0 && !mouth) decorateUpper(n, d, rnd, lx, lz, x, z, up, sunken, warm);
        decorateCrust(n, d, rnd, lx, lz, ceil, up);
        decorateAbyss(n, d, rnd, lx, lz, x, z, floorTop, ceil);
        if (ts != null || mouth) Trench.carve(this, n, d, rnd, lx, lz, x, z, up, minY); // last: the rift cuts through all of it
    }

    private Material surface(Noise n, Random rnd, int x, int z, boolean sunken, boolean warm, int up) {
        if (sunken) { // the Sunken Sea: navystone and glowing teal sand
            int r = rnd.nextInt(10);
            return r < 3 ? Material.WARPED_WART_BLOCK : r < 6 ? Material.PRISMARINE : r < 7 ? Material.LIGHT_BLUE_TERRACOTTA : Material.CYAN_TERRACOTTA;
        }
        if (warm) return rnd.nextInt(4) == 0 ? Material.SAND : Material.SANDSTONE;
        double p = n.kelp.noise(x + 900, z, 0.5, 0.5);
        if (p > 0.55) return Material.GRAVEL;
        if (p < -0.6) return Material.CLAY;
        return up < 42 ? Material.MUD : Material.SAND;
    }

    private static final Material[] CORAL_BLOCKS = {Material.BRAIN_CORAL_BLOCK, Material.TUBE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK,
            Material.FIRE_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK};
    private static final Material[] CORALS = {Material.BRAIN_CORAL, Material.TUBE_CORAL, Material.HORN_CORAL, Material.FIRE_CORAL,
            Material.BUBBLE_CORAL, Material.BRAIN_CORAL_FAN, Material.TUBE_CORAL_FAN, Material.HORN_CORAL_FAN};

    private void decorateUpper(Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up, boolean sunken, boolean warm) {
        int y = up + 1;
        if (y >= SEA - 2 || d.getType(lx, y, lz) != Material.WATER) return;
        Material below = d.getType(lx, up, lz);
        if (!below.isSolid()) return;
        double clump = n.n2(n.coral, x, z);
        if (warm && clump > 0.1 && rnd.nextDouble() < reef(x, z) * 1.3) { // the reef: coral heads in clumps, one kind each
            int kind = Math.floorMod((int) Math.floor(n.n2(n.coral, x + 500, z) * 3 + 3), CORAL_BLOCKS.length);
            if (clump > 0.35) { // a coral head: a little mound of coral block
                int hh = 1 + (int) ((clump - 0.35) * 8);
                for (int i = 0; i < hh && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, CORAL_BLOCKS[kind]);
                if (d.getType(lx, y + hh, lz) == Material.WATER) d.setBlock(lx, y + hh, lz, CORALS[(kind + rnd.nextInt(2) * 5) % CORALS.length]);
            } else d.setBlock(lx, y, lz, CORALS[(kind + rnd.nextInt(3) * 5) % CORALS.length]);
            return;
        }
        double k = n.kelp.noise(x, z, 0.5, 0.5);
        if (!sunken && k > 0.25 && rnd.nextDouble() < (k - 0.25) * 1.6 && below != Material.GRAVEL) { // kelp forests
            int h = 6 + rnd.nextInt(Math.max(1, Math.min(34, SEA - 4 - y)));
            for (int i = 0; i < h; i++) {
                if (y + i >= SEA - 2 || d.getType(lx, y + i, lz) != Material.WATER) break;
                d.setBlock(lx, y + i, lz, i == h - 1 || y + i + 1 >= SEA - 2 ? Material.KELP : Material.KELP_PLANT);
            }
            return;
        }
        int r = rnd.nextInt(100);
        if (r < 14) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 18 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, tallGrassLow); d.setBlock(lx, y + 1, lz, tallGrassHigh); }
        else if (r < 19 && sunken) d.setBlock(lx, y, lz, pickles);
        else if (r == 19 && sunken) d.setBlock(lx, y, lz, Material.AMETHYST_CLUSTER);
    }

    private void decorateCrust(Noise n, ChunkData d, Random rnd, int lx, int lz, int ceil, int up) {
        for (int y = ceil + 2; y < up - 3; y++) {
            if (d.getType(lx, y, lz) != Material.WATER) continue;
            Material below = d.getType(lx, y - 1, lz), above = d.getType(lx, y + 1, lz);
            if (below.isSolid() && below != Material.CHEST) {
                int r = rnd.nextInt(100);
                if (r < 9) d.setBlock(lx, y, lz, glowLichenFloor);
                else if (r < 11) d.setBlock(lx, y, lz, pickles);
                else if (r < 12) d.setBlock(lx, y, lz, Material.SEAGRASS);
            } else if (above.isSolid() && rnd.nextInt(14) == 0 && d.getType(lx, y - 1, lz) == Material.WATER) {
                d.setBlock(lx, y, lz, dripFrustum);
                d.setBlock(lx, y - 1, lz, dripTip);
            }
        }
    }

    /** Hanging rock cones from the abyss ceiling: their lowest point here, or Integer.MAX_VALUE. */
    int hangBottom(Noise n, int x, int z, int ceil) {
        if (flatWeight(x, z) > 0) return Integer.MAX_VALUE;
        int gx = Math.floorDiv(x, 34), gz = Math.floorDiv(z, 34);
        int best = Integer.MAX_VALUE;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            long h = hash(n.seed, gx + i, gz + j, 71);
            if (unit(h) > 0.4) continue;
            int hx = (gx + i) * 34 + 6 + (int) (unit(hash(n.seed, gx + i, gz + j, 72)) * 22);
            int hz = (gz + j) * 34 + 6 + (int) (unit(hash(n.seed, gx + i, gz + j, 73)) * 22);
            double r = 4 + unit(hash(n.seed, gx + i, gz + j, 74)) * 5, len = 8 + unit(hash(n.seed, gx + i, gz + j, 75)) * 16;
            double dd = Math.hypot(x - hx, z - hz);
            if (dd > r) continue;
            best = Math.min(best, ceil - (int) (len * (1 - dd / r)));
        }
        return best;
    }

    private void decorateAbyss(Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int floorTop, int ceil) {
        if (flatWeight(x, z) > 0.5) return; // the build area stays clean: black sand and open water, nothing else
        // hanging rock cones
        int hb = hangBottom(n, x, z, ceil);
        if (hb != Integer.MAX_VALUE) for (int y = Math.max(hb, floorTop + 6); y < ceil; y++)
            d.setBlock(lx, y, lz, y == Math.max(hb, floorTop + 6) && rnd.nextInt(12) == 0 ? Material.SHROOMLIGHT : (y & 1) == 0 ? Material.DEEPSLATE : Material.TUFF);
        // giant bones of something long dead, half buried in the black sand
        if (whaleAbyss(n, x, floorTop, z)) for (int y = floorTop + 1; y <= floorTop + 9; y++) if (whaleAbyssAt(n, x, y, z, floorTop)) d.setBlock(lx, y, lz, Material.BONE_BLOCK);
        // stalactites from the ceiling (2-5 long), a few of them glowing at the tip
        if (rnd.nextInt(9) == 0 && flatWeight(x, z) == 0) {
            int len = 2 + rnd.nextInt(4);
            for (int i = 1; i <= len; i++) {
                int y = ceil - i;
                if (y <= floorTop + 3 || d.getType(lx, y, lz) != Material.WATER) break;
                d.setBlock(lx, y, lz, i == len ? dripTip : dripFrustum);
            }
        }
        // basalt pillars from the floor to the ceiling
        int gx = Math.floorDiv(x, 40), gz = Math.floorDiv(z, 40);
        long h = hash(n.seed, gx, gz, 61);
        if (unit(h) < 0.35 && flatWeight(x, z) == 0) {
            int px = gx * 40 + 6 + (int) (unit(hash(n.seed, gx, gz, 62)) * 28), pz = gz * 40 + 6 + (int) (unit(hash(n.seed, gx, gz, 63)) * 28);
            double r = 1.5 + unit(hash(n.seed, gx, gz, 64)) * 2.5;
            if (Math.hypot(x - px, z - pz) < r)
                for (int y = floorTop + 1; y < ceil; y++) d.setBlock(lx, y, lz, (y & 3) == 0 ? Material.POLISHED_BASALT : Material.BASALT);
        }
        int y = floorTop + 1;
        if (d.getType(lx, y, lz) != Material.WATER) return;
        Material below = d.getType(lx, floorTop, lz);
        if (below != Material.BLACK_CONCRETE_POWDER && below != Material.BLACKSTONE && below != Material.BASALT) return;
        int r = rnd.nextInt(1000);
        double vf = n.n2(n.vents, x, z), garden = n.n2(n.vents, x + 7000, z - 7000);
        if (vf > 0.55 && r < 22 && below == Material.BLACK_CONCRETE_POWDER) { // a hydrothermal vent field
            int stack = 1 + rnd.nextInt(4); // a little chimney of basalt, magma at the top, bubbles streaming up
            for (int i = 0; i < stack - 1; i++) d.setBlock(lx, y + i, lz, Material.BASALT);
            d.setBlock(lx, y + stack - 1, lz, Material.MAGMA_BLOCK);
            d.setBlock(lx, floorTop, lz, Material.BLACKSTONE);
            for (int i = stack; i < stack + 16 && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, bubble);
            return;
        }
        if (vf > 0.55 && r < 80) { d.setBlock(lx, floorTop, lz, rnd.nextBoolean() ? Material.MAGMA_BLOCK : Material.BLACKSTONE); return; }
        if (garden > 0.6 && r < 120) { // a crystal garden: amethyst and crying obsidian glowing purple in the dark
            if (r < 30) d.setBlock(lx, floorTop, lz, Material.CRYING_OBSIDIAN);
            else if (r < 60) d.setBlock(lx, floorTop, lz, Material.AMETHYST_BLOCK);
            d.setBlock(lx, y, lz, Material.AMETHYST_CLUSTER);
            return;
        }
        if (r < 150) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 175 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, tallGrassLow); d.setBlock(lx, y + 1, lz, tallGrassHigh); }
        else if (r < 190) d.setBlock(lx, y, lz, pickles);
        else if (r < 196) { // kelp in the dark
            int hh = 3 + rnd.nextInt(10);
            for (int i = 0; i < hh && d.getType(lx, y + i, lz) == Material.WATER && y + i < ceil - 2; i++) d.setBlock(lx, y + i, lz, i == hh - 1 ? Material.KELP : Material.KELP_PLANT);
        } else if (r < 206) d.setBlock(lx, floorTop, lz, Material.SCULK);
        else if (r < 208) d.setBlock(lx, y, lz, Material.SCULK_VEIN);
    }

    /** Dark Loot chests in cave pockets (the crust's caves and the mounds' tunnels): FaultlineItems fills them on first open. */
    private void chests(Noise n, ChunkData d, Random rnd, int chunkX, int chunkZ, int minY) {
        if (rnd.nextDouble() > 0.35) return;
        for (int tries = 0; tries < 24; tries++) {
            int lx = 1 + rnd.nextInt(14), lz = 1 + rnd.nextInt(14);
            int x = (chunkX << 4) + lx, z = (chunkZ << 4) + lz;
            if (flatWeight(x, z) > 0) continue;
            int up = upperFloor(n, x, z), ceil = abyssCeiling(n, x, z);
            int y = rnd.nextBoolean() ? ceil + 2 + rnd.nextInt(Math.max(1, up - ceil - 6)) : FLOOR + 2 + rnd.nextInt(18);
            if (y <= minY + 4) continue;
            if (d.getType(lx, y, lz) != Material.WATER || !d.getType(lx, y - 1, lz).isSolid()) continue;
            // inside rock: something solid overhead within 6 blocks (a cave, not the open sea)
            boolean roof = false;
            for (int i = 1; i <= 6 && !roof; i++) roof = d.getType(lx, y + i, lz).isSolid();
            if (!roof) continue;
            d.setBlock(lx, y, lz, chest);
            return;
        }
    }

    // =========================================================================================== for the plugin
    enum Zone { SUNLIT, CAVES, ABYSS, SULPHUR, TRENCH }

    /** Which part of the Dark Ocean a spot is in (by the terrain's own layers, so it works before a chunk loads). */
    Zone zone(long seed, int x, int y, int z) {
        Noise n = noiseFor(seed);
        int up = upperFloor(n, x, z), ceil = abyssCeiling(n, x, z);
        if (up - ceil < 14) ceil = up - 14;
        if (y > up) return Sulphur.weight(n, x, z) > 0.35 ? Zone.SULPHUR : Zone.SUNLIT;
        if (Trench.inside(n, x, y, z, up)) return Zone.TRENCH;
        if (y >= ceil) return Zone.CAVES;
        return Zone.ABYSS;
    }

    /** The Sunlit Sea's floor height (the top solid block) at a column. */
    int seaFloor(long seed, int x, int z) { return upperFloor(noiseFor(seed), x, z); }

    /** How much of the Sulphur Sea a column is in (0..1). */
    double sulphur(long seed, int x, int z) { return Sulphur.weight(noiseFor(seed), x, z); }

    /** A tall pocket of open Abyss water near (x0, z0) (the Deep Boss Rush): {x, y, z}, or null. */
    int[] abyssPocket(long seed, int x0, int z0) {
        Noise n = noiseFor(seed);
        for (int r = 0; r < 480; r += 16) for (int a = 0; a < 360; a += r == 0 ? 360 : 20) {
            int x = x0 + (int) Math.round(Math.cos(Math.toRadians(a)) * r), z = z0 + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
            if (flatWeight(x, z) > 0 || Trench.near(x, z, 30) || MermaidDen.near(x, z, 0) || Sulphur.near(x, z, 0)) continue;
            if (moundTop(n, x, z) != Integer.MIN_VALUE || hangBottom(n, x, z, abyssCeiling(n, x, z)) != Integer.MAX_VALUE) continue;
            int up = upperFloor(n, x, z), ceil = abyssCeiling(n, x, z);
            if (up - ceil < 14) ceil = up - 14;
            int fl = abyssFloor(n, x, z);
            if (ceil - fl < 26) continue;
            return new int[]{x, fl + (ceil - fl) / 2, z};
        }
        return null;
    }

    /** Is (x, y, z) in the Trench's water? */
    boolean trench(long seed, int x, int y, int z) { Noise n = noiseFor(seed); return Trench.inside(n, x, y, z, upperFloor(n, x, z)); }

    /** The abyss's ceiling at a column. */
    int ceiling(long seed, int x, int z) { return abyssCeiling(noiseFor(seed), x, z); }

    private Noise noiseFor(long seed) {
        Noise n = noise;
        if (n == null || n.seed != seed) { n = new Noise(seed); noise = n; }
        return n;
    }

    // =========================================================================================== settings
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return true; }  // squid, fish, dolphins, drowned (the rest is filtered)
    @Override public boolean shouldGenerateStructures() { return false; }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) { return new Biomes(this); }

    /** Biomes by depth: the Sunlit Sea's oceans, and the abyss (cold, deep) under the crust. */
    static final class Biomes extends BiomeProvider {
        final DeepGenerator g;
        Biomes(DeepGenerator g) { this.g = g; }

        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            Noise n = g.noise(info);
            if (y < g.abyssCeiling(n, x, z)) return Biome.DEEP_COLD_OCEAN;
            if (Sulphur.weight(n, x, z) > 0.35) return Biome.SWAMP; // murky green water: the Sulphur Sea
            if (MermaidDen.weight(x, z) > 0.35) return Biome.WARM_OCEAN; // clear tropical water: the Mermaid Den
            if (reef(x, z) > 0.15) return Biome.WARM_OCEAN;
            return g.region(n, x, z) > 0.15 ? Biome.DEEP_LUKEWARM_OCEAN : Biome.DEEP_OCEAN;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            return List.of(Biome.DEEP_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.WARM_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.SWAMP);
        }
    }
}
