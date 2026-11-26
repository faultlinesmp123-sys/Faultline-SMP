package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Faultline overworld (main world). Draws the land, the Great Sea, the abyss and the holes, the custom biomes'
 * own ground, trees and buildings, the faction sites, pyramids and dungeons; vanilla then does the surface (grass,
 * dirt, sand, deepslate, bedrock), vanilla caves, ores and decorations, structures (villages, mansions...) and mobs.
 * Everything here is a pure function of (seed, x, y, z): any chunk can be generated on its own, in any order.
 */
public final class WorldGen extends ChunkGenerator {

    static final int SEA = Layout.SEA;
    private final Map<Long, Terrain> terrains = new ConcurrentHashMap<>();
    private final Map<Long, Caves> caves = new ConcurrentHashMap<>();

    Terrain terrain(WorldInfo info) { return terrains.computeIfAbsent(info.getSeed(), Terrain::new); }
    Caves caves(WorldInfo info) { return caves.computeIfAbsent(info.getSeed(), Caves::new); }

    // ------------------------------------------------------------------ noise caves

    /** 3D noise caves (cheese caverns + spaghetti tunnels), sampled on a 4 x 8 x 4 grid and interpolated. */
    static final class Caves {
        final SimplexNoiseGenerator cheese, spag1, spag2, roof;
        Caves(long seed) {
            Random r = new Random(seed ^ 0x77CA4E5L);
            cheese = new SimplexNoiseGenerator(r.nextLong());
            spag1 = new SimplexNoiseGenerator(r.nextLong());
            spag2 = new SimplexNoiseGenerator(r.nextLong());
            roof = new SimplexNoiseGenerator(r.nextLong());
        }

        /** > 0 = air. bias raises it (bigger caves: Fibers, the Mire). */
        double density(double x, double y, double z, double bias) {
            double c = cheese.noise(x / 70.0, y / 34.0, z / 70.0) - 0.58 + bias;
            double s = 0.075 - Math.abs(spag1.noise(x / 52.0, y / 26.0, z / 52.0)) - Math.abs(spag2.noise(x / 52.0 + 31, y / 26.0, z / 52.0));
            return Math.max(c, s * 1.6);
        }
    }

    // ------------------------------------------------------------------ main

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        Blocks.init();
        Terrain t = terrain(info);
        Caves cv = caves(info);
        int bx = chunkX << 4, bz = chunkZ << 4;
        int minY = data.getMinHeight(), maxY = data.getMaxHeight();
        Terrain.Col[] cols = cols(t, chunkX, chunkZ);
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Terrain.Col c = cols[lx * 16 + lz];
            int x = bx + lx, z = bz + lz;
            int top = Math.min(c.surface, maxY - 1);
            // rock
            data.setRegion(lx, minY, lz, lx + 1, top + 1, lz + 1, Blocks.STONE);
            // sea / rivers
            if ((c.sea || c.river) && !c.dry) data.setRegion(lx, top + 1, lz, lx + 1, SEA, lz + 1, Blocks.WATER);
            // the abyss (+ holes up to the sea floor)
            if (c.abyssTop != Integer.MIN_VALUE) {
                int to = c.hole ? top : c.abyssTop;
                data.setRegion(lx, c.abyssBottom, lz, lx + 1, to + 1, lz + 1, Blocks.WATER);
                // diamonds round the abyss (in its ceiling and floor rock)
                long h = hash(t.seed, x, z, 0xD1A);
                if ((h & 31) == 0) data.setBlock(lx, c.abyssTop + 1 + (int) ((h >>> 5) & 3), lz, Blocks.DIAMOND);
                if (((h >>> 9) & 31) == 0) data.setBlock(lx, c.abyssBottom - 1 - (int) ((h >>> 14) & 2), lz, Blocks.DIAMOND);
                if (((h >>> 17) & 63) == 0) data.setBlock(lx, c.abyssBottom - 1, lz, Blocks.MAGMA);
                if (((h >>> 23) & 127) == 0) data.setBlock(lx, c.abyssBottom - 1, lz, Blocks.SEA_LANTERN);
            }
        }
    }

    /** A chunk's columns, kept between generateNoise and generateSurface (the terrain maths is the costly part). */
    private final Map<Long, Terrain.Col[]> colCache = new ConcurrentHashMap<>();

    private Terrain.Col[] cols(Terrain t, int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL) ^ (t.seed * 31);
        if (colCache.size() > 4096) colCache.clear();     // chunks that never reached the surface step
        return colCache.computeIfAbsent(key, k -> {
            Terrain.Col[] cols = new Terrain.Col[256];
            int bx = chunkX << 4, bz = chunkZ << 4;
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) cols[lx * 16 + lz] = t.column(bx + lx, bz + lz);
            return cols;
        });
    }

    /**
     * AUDIT: everything but the bare rock and water happens HERE, after vanilla has laid its surface (grass, dirt, sand,
     * deepslate, bedrock) on our rock. Before, the caves, the faction builds and the custom ground were all there when
     * vanilla's surface pass ran, and that pass turns any plain stone with air above it into grass with dirt under it: grassy
     * cave floors all through the islands, grass and dirt on every stone wall and floor of the castles, ferns on dirt.
     */
    @Override
    public void generateSurface(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        Blocks.init();
        Terrain t = terrain(info);
        Caves cv = caves(info);
        int bx = chunkX << 4, bz = chunkZ << 4;
        int minY = data.getMinHeight(), maxY = data.getMaxHeight();
        Terrain.Col[] cols = cols(t, chunkX, chunkZ);
        colCache.remove(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL) ^ (t.seed * 31));

        // cave densities on a coarse grid (x, z every 4; y every 8)
        int gy0 = minY, gyN = (maxY - minY) / 8 + 1;
        double[][][] grid = new double[5][5][gyN];
        double[][] bias = new double[5][5];
        for (int gx = 0; gx < 5; gx++) for (int gz = 0; gz < 5; gz++) {
            int x = bx + gx * 4, z = bz + gz * 4;
            bias[gx][gz] = caveBias(t, x, z);
            for (int gy = 0; gy < gyN; gy++) grid[gx][gz][gy] = cv.density(x, gy0 + gy * 8, z, 0);
        }

        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Terrain.Col c = cols[lx * 16 + lz];
            int x = bx + lx, z = bz + lz;
            int top = Math.min(c.surface, maxY - 1);
            // noise caves under land (not under the sea, the Farlands or anything near the surface)
            if (!c.sea && !c.dry && c.farlands < 0.5 && !Sites.near(x, z, 6)) {
                double fb = interp2(bias, lx, lz);
                int ceil = top - 12 - (fb > 0.2 ? 14 : 0);
                // AUDIT: a cave under the shore or a river bank opened sideways into the water column next door (a wall of
                // water into the cave). Next to water, caves stay below that neighbour's bed.
                if (c.wetNear) for (int[] o : NEIGHBOURS) {
                    int nx = lx + o[0], nz = lz + o[1];
                    Terrain.Col n = nx >= 0 && nx < 16 && nz >= 0 && nz < 16 ? cols[nx * 16 + nz] : t.column(x + o[0], z + o[1]);
                    if ((n.sea || n.river) && !n.dry) ceil = Math.min(ceil, n.surface - 3);
                }
                ceil = Math.min(ceil, cavernCap(x, z));
                for (int y = minY + 6; y <= ceil; y++) {
                    double d = interp(grid, lx, lz, (y - gy0) / 8.0, gyN) + fb * bandWeight(t, x, y, z, c);
                    if (d > 0) data.setBlock(lx, y, lz, y < -54 ? Blocks.LAVA : Blocks.CAVE_AIR);
                }
            }
        }

        Shapes.carve(t, info, bx, bz, cols, data);          // caverns, canyon stairs, holes' rims
        Decor.paint(t, bx, bz, cols, data);                 // custom biomes' ground, trees, rocks...
        Structures.paint(t, bx, bz, cols, data);            // pyramids, dungeons, admin cores, sky isles
        Sites.paint(t, bx, bz, data);                       // the faction builds
        FarGen.paint(t, bx, bz, data);                      // the Farlands: floating chunks, glitch pillars, vaults, camps
        settle(data, minY, maxY);
    }

    /**
     * Round 7 (audit): sand, gravel, concrete powder and anvils left over air or water fall the moment the chunk ticks
     * (pyramids' carved rooms under the desert's sand, a ruin's hole under a build's gravel). The last thing a chunk does:
     * they become their solid cousins.
     */
    static void settle(ChunkData data, int minY, int maxY) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Material below = data.getType(lx, minY, lz);
            for (int y = minY + 1; y < maxY; y++) {
                Material m = data.getType(lx, y, lz);
                if ((m.hasGravity() || m.name().endsWith("ANVIL")) && (below.isAir() || below == Material.WATER || below == Material.LAVA)) {
                    String n = m.name();
                    String to = n.equals("SAND") || n.equals("SUSPICIOUS_SAND") ? "minecraft:sandstone" : n.equals("RED_SAND") ? "minecraft:red_sandstone"
                            : n.endsWith("CONCRETE_POWDER") ? "minecraft:" + n.toLowerCase().replace("_powder", "") : n.endsWith("ANVIL") ? "minecraft:barrel"
                            : n.contains("GRAVEL") ? "minecraft:andesite" : null;
                    if (to != null) { data.setBlock(lx, y, lz, Blocks.bd(to)); m = data.getType(lx, y, lz); }
                }
                below = m;
            }
        }
    }

    static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Round 7: noise caves stay 12 blocks below a cavern's floor. They used to hollow out under it and leave the cavern
     * floor (and the Fibers' paths, mushrooms, pools) as a thin crust floating over a hole.
     */
    static int cavernCap(int x, int z) {
        int cap = Integer.MAX_VALUE;
        for (Layout.Cavern c : Layout.CAVERNS) if (Layout.dist(x, z, c.x(), c.z()) < c.r() + 14) cap = Math.min(cap, c.floor() - 12);
        return cap;
    }

    private static final Map<Long, Caves> CAVES = new ConcurrentHashMap<>();

    /**
     * True when the noise caves made (x, y, z) air: the same sums the chunk generator does (grid corners every 4 x 8 x 4,
     * interpolated the same way), so a feature drawn across several chunks can find the real cave floor in all of them.
     * (Caverns, the canyon and features carved later aren't included.)
     */
    static boolean caveAt(Terrain t, int x, int y, int z) {
        Terrain.Col c = t.column(x, z);
        if (c.sea || c.dry || c.farlands >= 0.5 || Sites.near(x, z, 6)) return false;
        int minY = -64;
        if (y < minY + 6) return false;
        int bx = Math.floorDiv(x, 16) * 16, bz = Math.floorDiv(z, 16) * 16, lx = x - bx, lz = z - bz;
        int gx = lx / 4, gz = lz / 4;
        double tx = (lx - gx * 4) / 4.0, tz = (lz - gz * 4) / 4.0;
        double b00 = caveBias(t, bx + gx * 4, bz + gz * 4), b10 = caveBias(t, bx + gx * 4 + 4, bz + gz * 4);
        double b01 = caveBias(t, bx + gx * 4, bz + gz * 4 + 4), b11 = caveBias(t, bx + gx * 4 + 4, bz + gz * 4 + 4);
        double fb = (b00 + (b10 - b00) * tx) + ((b01 + (b11 - b01) * tx) - (b00 + (b10 - b00) * tx)) * tz;
        int top = Math.min(c.surface, Layout.MAX_Y);
        int ceil = top - 12 - (fb > 0.2 ? 14 : 0);
        if (c.wetNear) for (int[] o : NEIGHBOURS) {
            Terrain.Col n = t.column(x + o[0], z + o[1]);
            if ((n.sea || n.river) && !n.dry) ceil = Math.min(ceil, n.surface - 3);
        }
        ceil = Math.min(ceil, cavernCap(x, z));
        if (y > ceil) return false;
        Caves cv = CAVES.computeIfAbsent(t.seed, Caves::new);
        double fy = (y - minY) / 8.0;
        int n = (Layout.WORLD_TOP - minY) / 8 + 1, iy = Math.max(0, Math.min(n - 2, (int) fy));
        double ty = fy - iy;
        double[] v = new double[8];
        for (int k = 0; k < 8; k++) v[k] = cv.density(bx + (gx + (k & 1)) * 4, minY + (iy + (k >> 2)) * 8, bz + (gz + (k >> 1 & 1)) * 4, 0);
        double c00 = v[0] + (v[1] - v[0]) * tx, c10 = v[2] + (v[3] - v[2]) * tx, c01 = v[4] + (v[5] - v[4]) * tx, c11 = v[6] + (v[7] - v[6]) * tx;
        double a = c00 + (c10 - c00) * tz, bb = c01 + (c11 - c01) * tz;
        return a + (bb - a) * ty + fb * bandWeight(t, x, y, z, c) > 0;
    }

    /** The cave floor under (x, from, z): the first rock with cave air over it, searching down `depth` blocks, or MIN_VALUE. */
    static int caveFloor(Terrain t, int x, int from, int z, int depth) {
        boolean airAbove = caveAt(t, x, from + 1, z);
        for (int y = from; y > from - depth; y--) {
            boolean air = caveAt(t, x, y, z);
            if (!air && airAbove) return y;
            airAbove = air;
        }
        return Integer.MIN_VALUE;
    }

    /** How much bigger caves get here (Fibers / Mire regions); 0 elsewhere. */
    static double caveBias(Terrain t, int x, int z) {
        double b = 0;
        for (Layout.Blob bl : Layout.UNDERGROUND) {
            double w = t.blob(bl, x, z);
            if (w > 0) b = Math.max(b, w * (bl.biome() == B.FIBERS ? 0.55 : 0.42));
        }
        return b;
    }

    /** The Fibers / Mire bias only applies in their height bands. */
    static double bandWeight(Terrain t, int x, int y, int z, Terrain.Col c) {
        B u = t.underground(x, y, z, c);
        if (u == B.FIBERS) return Terrain.smooth(18, 40, y) * Terrain.smooth(185, 160, y);
        if (u == B.MIRE) return Terrain.smooth(-44, -30, y) * Terrain.smooth(66, 50, y);
        return 0;
    }

    static double interp2(double[][] g, int lx, int lz) {
        double fx = lx / 4.0, fz = lz / 4.0;
        int ix = Math.min(3, (int) fx), iz = Math.min(3, (int) fz);
        double tx = fx - ix, tz = fz - iz;
        double a = g[ix][iz] + (g[ix + 1][iz] - g[ix][iz]) * tx, b = g[ix][iz + 1] + (g[ix + 1][iz + 1] - g[ix][iz + 1]) * tx;
        return a + (b - a) * tz;
    }

    static double interp(double[][][] g, int lx, int lz, double fy, int n) {
        double fx = lx / 4.0, fz = lz / 4.0;
        int ix = Math.min(3, (int) fx), iz = Math.min(3, (int) fz), iy = Math.max(0, Math.min(n - 2, (int) fy));
        double tx = fx - ix, tz = fz - iz, ty = fy - iy;
        double c00 = g[ix][iz][iy] + (g[ix + 1][iz][iy] - g[ix][iz][iy]) * tx;
        double c10 = g[ix][iz + 1][iy] + (g[ix + 1][iz + 1][iy] - g[ix][iz + 1][iy]) * tx;
        double c01 = g[ix][iz][iy + 1] + (g[ix + 1][iz][iy + 1] - g[ix][iz][iy + 1]) * tx;
        double c11 = g[ix][iz + 1][iy + 1] + (g[ix + 1][iz + 1][iy + 1] - g[ix][iz + 1][iy + 1]) * tx;
        double a = c00 + (c10 - c00) * tz, b = c01 + (c11 - c01) * tz;
        return a + (b - a) * ty;
    }

    static long hash(long seed, int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ ((long) salt * 0x165667B19E3779F9L);
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }

    static long hash3(long seed, int x, int y, int z, int salt) { return hash(seed ^ (y * 0x2545F4914F6CDD1DL), x, z, salt); }

    // vanilla does the rest on top of our rock
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return true; }
    // AUDIT: vanilla's carvers fill what they dig below "sea level" (200 here) with water: flooded tunnels all through the
    // islands. Our own noise caves are the caves.
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return true; }
    @Override public boolean shouldGenerateMobs() { return true; }
    @Override public boolean shouldGenerateStructures() { return true; }

    /**
     * AUDIT: without this, vanilla asks ITS OWN terrain for the ground height when it places villages, outposts, mansions,
     * temples, shipwrecks... (about y 64-100), so they'd all have been buried inside the islands (ground is y 200+).
     */
    @Override
    public int getBaseHeight(WorldInfo info, Random random, int x, int z, org.bukkit.HeightMap heightMap) {
        Terrain.Col c = terrain(info).column(x, z);
        int solid = Math.min(c.surface, info.getMaxHeight() - 1) + 1;
        if (heightMap == org.bukkit.HeightMap.OCEAN_FLOOR || heightMap == org.bukkit.HeightMap.OCEAN_FLOOR_WG) return solid;
        return (c.sea || c.river) && !c.dry ? Math.max(solid, SEA) : solid;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) { return new Biomes(this); }

    @Override
    public org.bukkit.Location getFixedSpawnLocation(org.bukkit.World world, Random random) {
        return new org.bukkit.Location(world, Layout.SPAWN_X + 0.5, Layout.SPAWN_Y + 1, Layout.SPAWN_Z + 0.5);
    }

    // ------------------------------------------------------------------ biomes

    /** Maps our {@link B} ids to registered biomes (custom ones fall back to vanilla when the datapack is missing). */
    static final class BiomeMap {
        static final Map<B, Biome> MAP = new EnumMap<>(B.class);
        static volatile boolean ready;
        static boolean missingDatapack;

        static synchronized void init() {
            if (ready) return;
            for (B b : B.values()) {
                Biome bio = lookup(b.key);
                if (bio == null) { missingDatapack |= b.custom(); bio = lookup(b.fallback); }
                if (bio == null) bio = Biome.PLAINS;
                MAP.put(b, bio);
            }
            ready = true;
        }

        static Biome lookup(String key) {
            try { return Registry.BIOME.get(NamespacedKey.fromString(key)); } catch (Throwable e) { return null; }
        }

        static Biome of(B b) { if (!ready) init(); return MAP.get(b); }
    }

    static final class Biomes extends BiomeProvider {
        final WorldGen g;
        private final ThreadLocal<Map<Long, Terrain.Col>> cache = ThreadLocal.withInitial(() -> new LinkedHashMap<>(512, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Terrain.Col> e) { return size() > 4096; }
        });
        Biomes(WorldGen g) { this.g = g; }

        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            Terrain t = g.terrain(info);
            long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            Terrain.Col c = cache.get().get(k);
            if (c == null) { c = t.column(x, z); cache.get().put(k, c); }
            B u = t.underground(x, y, z, c);
            if (u == null && y > SEA + 12 && Structures.skyIsleAt(t, x, y, z)) u = B.SKY_ISLES;
            return BiomeMap.of(u != null ? u : c.biome);
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            List<Biome> out = new ArrayList<>();
            for (B b : B.values()) { Biome bio = BiomeMap.of(b); if (!out.contains(bio)) out.add(bio); }
            return out;
        }
    }

    // ------------------------------------------------------------------ block data

    /** Block states, made once on the first chunk (several generator threads may arrive at once). */
    static final class Blocks {
        static volatile boolean done;
        static BlockData STONE, WATER, CAVE_AIR, AIR, LAVA, DIAMOND, MAGMA, SEA_LANTERN;

        static void init() {
            if (done) return;
            synchronized (Blocks.class) {
                if (done) return;
                STONE = Material.STONE.createBlockData(); WATER = Material.WATER.createBlockData();
                CAVE_AIR = Material.CAVE_AIR.createBlockData(); AIR = Material.AIR.createBlockData();
                LAVA = Material.LAVA.createBlockData(); DIAMOND = Material.DEEPSLATE_DIAMOND_ORE.createBlockData();
                MAGMA = Material.MAGMA_BLOCK.createBlockData(); SEA_LANTERN = Material.SEA_LANTERN.createBlockData();
                done = true;
            }
        }

        private static final Map<String, BlockData> PARSED = new ConcurrentHashMap<>();

        /** A block state from a string ("minecraft:oak_stairs[facing=east]"), cached; a bad one falls back to its plain block. */
        static BlockData bd(String s) {
            BlockData d = PARSED.get(s);
            if (d != null) return d;
            try { d = org.bukkit.Bukkit.createBlockData(s); }
            catch (Throwable e) {
                Material m = Material.matchMaterial(s.contains("[") ? s.substring(0, s.indexOf('[')) : s);
                d = (m != null && m.isBlock() ? m : Material.STONE).createBlockData();
            }
            PARSED.put(s, d);
            return d;
        }

        static BlockData bd(Material m) { return bd(m.getKey().toString()); }
    }
}
