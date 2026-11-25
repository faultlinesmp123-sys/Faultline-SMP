package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.generator.WorldInfo;
import java.lang.reflect.Proxy;
import java.util.*;

/** Generates a square of chunks and looks for things that go wrong the moment the world ticks. */
public class Scan {
    static final int MINY = -64, MAXY = 320, H = MAXY - MINY;
    static Map<Long, Material[]> chunks = new HashMap<>();
    static WorldGen g; static WorldInfo wi;
    static Material at(int x, int y, int z) {
        if (y < MINY || y >= MAXY) return Material.AIR;
        Material[] a = chunks.get(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
        if (a == null) return null;
        return a[((y - MINY) * 16 + (z & 15)) * 16 + (x & 15)];
    }
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        org.mockbukkit.mockbukkit.MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public org.bukkit.block.data.BlockData createBlockData(String data) {
                try { return super.createBlockData(data); }
                catch (Throwable e) { Material m = Material.matchMaterial(data.contains("[") ? data.substring(0, data.indexOf('[')) : data); return super.createBlockData(m == null ? Material.STONE : m); }
            }
        });
        wi = (WorldInfo) Proxy.newProxyInstance(Scan.class.getClassLoader(), new Class<?>[]{WorldInfo.class}, (o, m, a) -> switch (m.getName()) {
            case "getSeed" -> seed; case "getMinHeight" -> MINY; case "getMaxHeight" -> MAXY; case "getName" -> "world"; case "hashCode" -> 1; default -> null; });
        Sites.load(java.util.logging.Logger.getLogger("scan"));
        g = new WorldGen();
        List<Object[]> spots = new ArrayList<>();
        for (Layout.Site s : Layout.SITES) spots.add(new Object[]{s.file(), s.x(), s.z(), 7});
        for (int i = 0; i < Layout.PYRAMIDS.length; i++) spots.add(new Object[]{"pyramid" + i, Layout.PYRAMIDS[i][0], Layout.PYRAMIDS[i][1], 6});
        for (int[] h : Layout.HOLES) spots.add(new Object[]{"hole", h[0], h[1], 5});
        for (Layout.Core c : Layout.CORES) spots.add(new Object[]{"core", c.x(), c.z(), 2});
        spots.add(new Object[]{"spawn", Layout.SPAWN_X, Layout.SPAWN_Z, 5});
        spots.add(new Object[]{"canyonW", Layout.CANYON_X - Layout.CANYON_HX, Layout.CANYON_Z, 5});
        Random r = new Random(7);
        for (int i = 0; i < 14; i++) spots.add(new Object[]{"random", r.nextInt(7000) - 3500, r.nextInt(7000) - 3500, 4});
        for (Layout.Blob b : Layout.UNDERGROUND) spots.add(new Object[]{"under:" + b.biome(), (int) b.x(), (int) b.z(), 4});
        for (Layout.Blob b : Layout.SURFACE) spots.add(new Object[]{"surf:" + b.biome(), (int) b.x(), (int) b.z(), 3});
        long only = args.length > 1 ? 1 : 0;
        for (Object[] sp : spots) {
            String name = (String) sp[0]; int cx = (int) sp[1] >> 4, cz = (int) sp[2] >> 4, R = (int) sp[3];
            if (args.length > 1 && !name.contains(args[1])) continue;
            chunks.clear();
            long t0 = System.nanoTime();
            for (int i = -R; i <= R; i++) for (int j = -R; j <= R; j++) chunks.put(((long) (cx + i) << 32) ^ ((cz + j) & 0xffffffffL), WorldPreview.chunk(g, wi, cx + i, cz + j));
            double ms = (System.nanoTime() - t0) / 1e6 / chunks.size();
            int wa = 0, la = 0, fall = 0, plant = 0; long stone = 0, water = 0; int[] ew = null, ef = null, el = null, ep = null;
            Map<String, Integer> waY = new TreeMap<>();
            int x0 = (cx - R) * 16 + 1, x1 = (cx + R) * 16 + 14, z0 = (cz - R) * 16 + 1, z1 = (cz + R) * 16 + 14;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = MINY + 1; y < MAXY - 1; y++) {
                Material m = at(x, y, z);
                if (m == Material.STONE) stone++; else if (m == Material.WATER) water++;
                if (m == Material.WATER || m == Material.LAVA) {
                    boolean leak = at(x, y - 1, z).isAir() || at(x + 1, y, z).isAir() || at(x - 1, y, z).isAir() || at(x, y, z + 1).isAir() || at(x, y, z - 1).isAir();
                    if (leak) { if (m == Material.WATER) { wa++; if (ew == null) ew = new int[]{x, y, z}; waY.merge("y" + (y / 20 * 20), 1, Integer::sum); } else { la++; if (el == null) el = new int[]{x, y, z}; } }
                } else if ((m == Material.SAND || m == Material.GRAVEL || m == Material.RED_SAND || m.name().endsWith("CONCRETE_POWDER") || m == Material.ANVIL) && (at(x, y - 1, z).isAir() || at(x, y - 1, z) == Material.WATER)) { fall++; if (ef == null) ef = new int[]{x, y, z}; }
                else if ((m == Material.DEAD_BUSH || m == Material.FERN || m == Material.SHORT_GRASS || m == Material.CACTUS || m == Material.AZALEA || m == Material.MOSS_CARPET || m == Material.BROWN_MUSHROOM || m == Material.CRIMSON_ROOTS || m == Material.POPPY)
                        && !at(x, y - 1, z).isSolid() && at(x, y - 1, z) != m) { plant++; if (ep == null) ep = new int[]{x, y, z}; }
            }
            System.out.printf("%-34s %5.1fms stone=%dk water=%dk water|air=%d %s %s lava|air=%d %s falling=%d %s plantNoGround=%d %s%n", name, ms, stone / 1000, water / 1000, wa, ew == null ? "" : Arrays.toString(ew), waY, la, el == null ? "" : Arrays.toString(el), fall, ef == null ? "" : Arrays.toString(ef), plant, ep == null ? "" : Arrays.toString(ep));
        }
        Runtime.getRuntime().halt(0);
    }
}
