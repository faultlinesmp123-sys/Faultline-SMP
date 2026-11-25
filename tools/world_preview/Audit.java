package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.generator.WorldInfo;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * A deeper look than Scan: for every place worth checking (sites, shrines, the sky city, the canyon, every biome, random
 * spots) it generates a square of chunks and reports
 *   FLOAT   clusters of blocks touching nothing that holds them up (not the floor of the world, not the edge of the square),
 *           by size, with what they are made of (the sky islands are expected to float: they are listed under SKY)
 *   FALL    sand / gravel / concrete powder / anvils over air or water
 *   PLANT   plants, carpets, snow, saplings, flowers on something they can't stand on
 *   LADDER  ladders with nothing behind them
 *   TORCH   standing torches / campfires with nothing under them
 *   WATER / LAVA  a fluid next to air it would flow into
 * Usage: Audit <seed> [nameFilter]
 */
public class Audit {
    static final int MINY = -64, MAXY = 528, H = MAXY - MINY;

    static boolean empty(Material m) {
        return m == null || m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.LIGHT || m == Material.BUBBLE_COLUMN;
    }

    static final Set<Material> NEEDS_GROUND = EnumSet.noneOf(Material.class);
    static void initPlants() {
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_")) continue;
            String n = m.name();
            if (n.endsWith("_SAPLING") || n.endsWith("_CARPET") || n.endsWith("_TULIP") || n.endsWith("_MUSHROOM") && !n.contains("BLOCK") && !n.contains("STEM")
                    || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS") || n.equals("FERN") || n.equals("LARGE_FERN") || n.equals("DEAD_BUSH")
                    || n.equals("DANDELION") || n.equals("POPPY") || n.equals("BLUE_ORCHID") || n.equals("ALLIUM") || n.equals("AZURE_BLUET")
                    || n.equals("OXEYE_DAISY") || n.equals("CORNFLOWER") || n.equals("LILY_OF_THE_VALLEY") || n.equals("PINK_PETALS")
                    || n.equals("SNOW") || n.equals("SWEET_BERRY_BUSH") || n.equals("SUGAR_CANE") || n.equals("CACTUS") || n.equals("BAMBOO")
                    || n.equals("CRIMSON_ROOTS") || n.equals("WARPED_ROOTS") || n.equals("NETHER_SPROUTS") || n.equals("AZALEA") || n.equals("FLOWERING_AZALEA")
                    || n.equals("SUNFLOWER") || n.equals("LILAC") || n.equals("ROSE_BUSH") || n.equals("PEONY") || n.equals("RAIL") || n.equals("TORCHFLOWER")
                    || n.equals("WITHER_ROSE") || n.equals("SHORT_DRY_GRASS") || n.equals("TALL_DRY_GRASS") || n.equals("BUSH") || n.equals("FIREFLY_BUSH")
                    || n.equals("LEAF_LITTER") || n.equals("WILDFLOWERS") || n.equals("CAMPFIRE") || n.equals("SOUL_CAMPFIRE") || n.equals("TORCH") || n.equals("SOUL_TORCH"))
                NEEDS_GROUND.add(m);
        }
    }

    /** What may hold a plant up: anything solid, or more of the same tall plant (cane, cactus, bamboo, doubles). */
    static boolean holds(Material below, Material m) {
        if (below == null) return true;
        if (below == m) return true;
        String bn = below.name(), mn = m.name();
        if (mn.equals("SNOW")) return below.isSolid() || below.name().endsWith("_LEAVES");
        if (mn.endsWith("_CARPET")) return !below.isAir();
        if (bn.endsWith("_LEAVES") && (mn.equals("SNOW"))) return true;
        return below.isSolid() || bn.equals("FARMLAND") || bn.equals("DIRT_PATH") || bn.equals("MUD") || bn.endsWith("_SLAB") || bn.endsWith("_STAIRS");
    }

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        org.mockbukkit.mockbukkit.MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public org.bukkit.block.data.BlockData createBlockData(String data) {
                try { return super.createBlockData(data); }
                catch (Throwable e) { Material m = Material.matchMaterial(data.contains("[") ? data.substring(0, data.indexOf('[')) : data); return super.createBlockData(m == null ? Material.STONE : m); }
            }
        });
        WorldInfo wi = (WorldInfo) Proxy.newProxyInstance(Audit.class.getClassLoader(), new Class<?>[]{WorldInfo.class}, (o, m, a) -> switch (m.getName()) {
            case "getSeed" -> seed; case "getMinHeight" -> MINY; case "getMaxHeight" -> MAXY; case "getName" -> "world"; case "hashCode" -> 1; default -> null; });
        initPlants();
        Sites.load(java.util.logging.Logger.getLogger("audit"));
        WorldGen g = new WorldGen();
        Terrain t = g.terrain(wi);
        List<Object[]> spots = new ArrayList<>();
        for (Layout.Site s : Layout.SITES) spots.add(new Object[]{s.file(), s.x(), s.z(), 4});
        for (Layout.Core c : Layout.CORES) spots.add(new Object[]{"core:" + c.faction(), c.x(), c.z(), 2});
        for (int i = 0; i < Layout.PYRAMIDS.length; i++) spots.add(new Object[]{"pyramid" + i, Layout.PYRAMIDS[i][0], Layout.PYRAMIDS[i][1], 4});
        spots.add(new Object[]{"sky:ladder", Structures.LADDER_X, Structures.LADDER_Z, 3});
        spots.add(new Object[]{"sky:north", 2100, -1300, 4});
        spots.add(new Object[]{"sky:west", 1800, -1000, 4});
        spots.add(new Object[]{"sky:south", 2100, -700, 4});
        spots.add(new Object[]{"sky:east", 2420, -980, 4});
        spots.add(new Object[]{"canyon:stairs", Layout.CANYON_X - Layout.CANYON_HX + 20, Layout.CANYON_Z - Layout.CANYON_HZ + 17, 3});
        spots.add(new Object[]{"canyon:mid", Layout.CANYON_X + 80, Layout.CANYON_Z, 3});
        spots.add(new Object[]{"spawn", Layout.SPAWN_X, Layout.SPAWN_Z, 3});
        for (int[] c : Structures.cities()) spots.add(new Object[]{"city", c[0], c[1], 5});
        for (Layout.Blob b : Layout.SURFACE) spots.add(new Object[]{"surf:" + b.biome(), (int) b.x() + 60, (int) b.z() - 40, 3});
        for (Layout.Blob b : Layout.UNDERGROUND) spots.add(new Object[]{"under:" + b.biome(), (int) b.x(), (int) b.z(), 3});
        for (Layout.Cavern c : Layout.CAVERNS) spots.add(new Object[]{"cavern", c.x(), c.z(), 4});
        for (Layout.Road r : Layout.ROADS) spots.add(new Object[]{"road:" + r.faction(), (r.x0() + r.x1()) / 2, (r.z0() + r.z1()) / 2, 2});
        Random rnd = new Random(seed ^ 99);
        for (int i = 0; i < 10; i++) spots.add(new Object[]{"random", rnd.nextInt(6400) - 3200, rnd.nextInt(6400) - 3200, 3});

        int problems = 0;
        for (Object[] sp : spots) {
            String name = (String) sp[0];
            if (args.length > 1 && !name.contains(args[1])) continue;
            int cx = (int) sp[1] >> 4, cz = (int) sp[2] >> 4, R = (int) sp[3];
            int W = (2 * R + 1) * 16, X0 = (cx - R) * 16, Z0 = (cz - R) * 16;
            Material[] vol = new Material[W * W * H];
            for (int i = -R; i <= R; i++) for (int j = -R; j <= R; j++) {
                Material[] c = WorldPreview.chunk(g, wi, cx + i, cz + j);
                int ox = (i + R) * 16, oz = (j + R) * 16;
                for (int y = 0; y < H; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    vol[(y * W + (oz + z)) * W + ox + x] = c[(y * 16 + z) * 16 + x];
            }
            // ---- floating clusters: flood from the world floor and the square's sides, what's left floats
            boolean[] seen = new boolean[vol.length];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int y = 0; y < H; y++) for (int a = 0; a < W; a++) for (int idx : new int[]{(y * W + a) * W, (y * W + a) * W + W - 1, (y * W) * W + a, (y * W + W - 1) * W + a})
                if (!seen[idx] && !empty(vol[idx]) && (y == 0 || true)) { seen[idx] = true; q.add(idx); }
            for (int z = 0; z < W; z++) for (int x = 0; x < W; x++) { int idx = z * W + x; if (!seen[idx] && !empty(vol[idx])) { seen[idx] = true; q.add(idx); } }
            flood(vol, seen, q, W);
            Map<String, Integer> floatMat = new TreeMap<>();
            List<String> floats = new ArrayList<>();
            int floatBlocks = 0, skyBlocks = 0, clusters = 0;
            for (int i = 0; i < vol.length; i++) {
                if (seen[i] || empty(vol[i])) continue;
                seen[i] = true; q.add(i);
                List<Integer> comp = flood(vol, seen, q, W);
                int y = i / (W * W), z = (i / W) % W, x = i % W;
                int wx = X0 + x, wy = y + MINY, wz = Z0 + z;
                Map<Material, Integer> mats = new HashMap<>();
                for (int k : comp) mats.merge(vol[k], 1, Integer::sum);
                Material top = mats.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                boolean sky = wy > Layout.SEA + 10 && t.surfaceWeight(B.SKY_ISLES, wx, wz) > 0.15;
                if (sky && comp.size() > 40) { skyBlocks += comp.size(); continue; }
                clusters++; floatBlocks += comp.size();
                for (var e : mats.entrySet()) floatMat.merge(e.getKey().name(), e.getValue(), Integer::sum);
                if (floats.size() < 6) floats.add(comp.size() + "x" + top + "@" + wx + "," + wy + "," + wz);
            }
            // ---- local checks
            int fall = 0, plant = 0, ladder = 0, water = 0, lava = 0;
            String eFall = "", ePlant = "", eLadder = "", eWater = "", eLava = "";
            for (int y = 1; y < H - 1; y++) for (int z = 1; z < W - 1; z++) for (int x = 1; x < W - 1; x++) {
                int i = (y * W + z) * W + x;
                Material m = vol[i];
                if (m == null || m.isAir()) continue;
                Material below = vol[i - W * W];
                String n = m.name();
                if ((m == Material.SAND || m == Material.GRAVEL || m == Material.RED_SAND || m == Material.SUSPICIOUS_SAND || m == Material.SUSPICIOUS_GRAVEL || n.endsWith("CONCRETE_POWDER") || n.endsWith("ANVIL"))
                        && (below.isAir() || below == Material.WATER || below == Material.LAVA)) { if (fall++ == 0) eFall = n + "@" + (X0 + x) + "," + (y + MINY) + "," + (Z0 + z); }
                else if (NEEDS_GROUND.contains(m) && !holds(below, m)) { if (plant++ < 1) ePlant = n + " on " + below + "@" + (X0 + x) + "," + (y + MINY) + "," + (Z0 + z); }
                else if (m == Material.LADDER) {
                    boolean back = vol[i + 1].isSolid() || vol[i - 1].isSolid() || vol[i + W].isSolid() || vol[i - W].isSolid();
                    if (!back && ladder++ == 0) eLadder = (X0 + x) + "," + (y + MINY) + "," + (Z0 + z);
                } else if (m == Material.WATER || m == Material.LAVA) {
                    boolean leak = below.isAir() || vol[i + 1].isAir() || vol[i - 1].isAir() || vol[i + W].isAir() || vol[i - W].isAir();
                    if (leak) { if (m == Material.WATER) { if (water++ == 0) eWater = (X0 + x) + "," + (y + MINY) + "," + (Z0 + z); } else if (lava++ == 0) eLava = (X0 + x) + "," + (y + MINY) + "," + (Z0 + z); }
                }
            }
            boolean bad = clusters > 0 || fall > 0 || plant > 0 || ladder > 0 || water > 0 || lava > 0;
            if (bad) problems++;
            StringBuilder sb = new StringBuilder(String.format("%-30s", name));
            if (clusters > 0) sb.append(" FLOAT ").append(clusters).append(" clusters/").append(floatBlocks).append(" blocks ").append(top(floatMat)).append(" e.g. ").append(floats);
            if (skyBlocks > 0) sb.append(" (sky islands ").append(skyBlocks).append(")");
            if (fall > 0) sb.append(" FALL ").append(fall).append(" ").append(eFall);
            if (plant > 0) sb.append(" PLANT ").append(plant).append(" ").append(ePlant);
            if (ladder > 0) sb.append(" LADDER ").append(ladder).append(" ").append(eLadder);
            if (water > 0) sb.append(" WATER ").append(water).append(" ").append(eWater);
            if (lava > 0) sb.append(" LAVA ").append(lava).append(" ").append(eLava);
            if (!bad) sb.append(" ok");
            System.out.println(sb);
        }
        System.out.println("AREAS WITH PROBLEMS: " + problems);
        Runtime.getRuntime().halt(0);
    }

    static String top(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, l.size()); i++) sb.append(i > 0 ? " " : "").append(l.get(i).getKey()).append("=").append(l.get(i).getValue());
        return sb.append("]").toString();
    }

    static List<Integer> flood(Material[] vol, boolean[] seen, ArrayDeque<Integer> q, int W) {
        List<Integer> comp = new ArrayList<>();
        int plane = W * W;
        while (!q.isEmpty()) {
            int i = q.poll();
            comp.add(i);
            int x = i % W, z = (i / W) % W, y = i / plane;
            if (x > 0) push(vol, seen, q, i - 1);
            if (x < W - 1) push(vol, seen, q, i + 1);
            if (z > 0) push(vol, seen, q, i - W);
            if (z < W - 1) push(vol, seen, q, i + W);
            if (y > 0) push(vol, seen, q, i - plane);
            if (y < H - 1) push(vol, seen, q, i + plane);
        }
        return comp;
    }

    static void push(Material[] vol, boolean[] seen, ArrayDeque<Integer> q, int i) {
        if (!seen[i] && !empty(vol[i])) { seen[i] = true; q.add(i); }
    }
}
