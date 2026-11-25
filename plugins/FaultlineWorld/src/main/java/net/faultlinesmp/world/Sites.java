package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.io.InputStream;
import java.util.*;
import java.util.logging.Logger;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * The faction builds (the owner's schematics, in the plugin jar under factions/). Castles and bases are left
 * abandoned (cracked, mossy, holes, broken glass, no lights, webs) with an entrance carved into the ground floor;
 * statues stay whole. Loaded once on the main thread before the world generates.
 */
final class Sites {
    private Sites() {}

    static final Map<String, Schematic> LOADED = new HashMap<>();
    /** Per site: local cells (x, y, z packed) carved for the entrance. */
    static final Map<String, Set<Long>> ENTRANCES = new HashMap<>();
    static volatile boolean ready;

    static void load(Logger log) {
        long t0 = System.currentTimeMillis();
        for (Layout.Site s : Layout.SITES) {
            if (LOADED.containsKey(s.file())) continue;
            try (InputStream in = Sites.class.getResourceAsStream("/factions/" + s.file() + ".litematic")) {
                if (in == null) { log.warning("[World] missing schematic factions/" + s.file()); continue; }
                Schematic sc = Schematic.read(s.file(), in);
                sc.palette = new BlockData[sc.paletteNames.length];
                for (int i = 0; i < sc.palette.length; i++) sc.palette[i] = bd(sc.paletteNames[i]);
                sc.findPlugs();
                LOADED.put(s.file(), sc);
                if (s.ruin()) ENTRANCES.put(s.file(), entrance(sc));
            } catch (Throwable e) {
                log.warning("[World] couldn't read " + s.file() + ": " + e);
            }
        }
        ready = true;
        log.info("[World] faction builds loaded: " + LOADED.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** True when a column is within reach of a faction build (keeps trees / caves / dungeons off them). */
    static boolean near(int x, int z, int pad) {
        for (Layout.Site s : Layout.SITES) {
            Schematic sc = LOADED.get(s.file());
            int hx = sc != null ? sc.sx / 2 : Math.max(20, s.r()), hz = sc != null ? sc.sz / 2 : Math.max(20, s.r());
            if (Math.abs(x - s.x()) <= hx + pad && Math.abs(z - s.z()) <= hz + pad) return true;
        }
        for (Layout.Core c : Layout.CORES) if (Math.abs(x - c.x()) <= 12 + pad && Math.abs(z - c.z()) <= 12 + pad) return true;
        return false;
    }

    static void paint(Terrain t, int bx, int bz, ChunkData data) {
        if (!ready) return;
        Canvas cv = new Canvas(data, bx, bz);
        for (Layout.Site s : Layout.SITES) {
            Schematic sc = LOADED.get(s.file());
            if (sc == null) continue;
            int ox = s.x() - sc.sx / 2, oz = s.z() - sc.sz / 2, oy = s.y();
            if (!cv.touches(ox, oz, ox + sc.sx - 1, oz + sc.sz - 1)) continue;
            Set<Long> door = ENTRANCES.getOrDefault(s.file(), Set.of());
            boolean fillAir = s.ruin() || s.shape() == Layout.Shape.SEABED;
            for (int x = Math.max(cv.bx, ox); x <= Math.min(cv.bx + 15, ox + sc.sx - 1); x++)
                for (int z = Math.max(cv.bz, oz); z <= Math.min(cv.bz + 15, oz + sc.sz - 1); z++) {
                    int lx = x - ox, lz = z - oz, c = lx * sc.sz + lz;
                    if (sc.colMin[c] < 0) continue;
                    // inside the build's span the ground / sea goes (rooms are air, not rock or water)
                    // AUDIT: a build standing in the sea (the Giant Tower) is FLOODED below sea level: its rooms used to be
                    // cut out as air inside the water, a curtain of 260,000 water-next-to-air faces that all start flowing.
                    boolean sunk = s.shape() == Layout.Shape.SEABED;
                    if (fillAir) for (int y = sc.colMin[c]; y <= sc.colMax[c]; y++) if (!sunk || oy + y >= Layout.SEA) cv.set(x, oy + y, z, WorldGen.Blocks.AIR);
                    for (int i = sc.colStart[c]; i < sc.colStart[c + 1]; i++) {
                        int ly = sc.ys[i], y = oy + ly;
                        BlockData b = finalBlock(t, s, sc, x, y, z, lx, ly, lz, sc.palette[sc.idx[i]]);
                        if (b != null) cv.set(x, y, z, b);
                    }
                    // a foundation so nothing floats (castles / statues on the ground)
                    // AUDIT: only under the build's FOOT. It used to hang a 40-block stone column under every overhang too (a
                    // statue's arms and wings, the great ring, balconies, roof eaves).
                    if (sc.colMin[c] <= sc.floorY + 2 && (s.shape() == Layout.Shape.FLAT || s.shape() == Layout.Shape.BASIN || s.shape() == Layout.Shape.CRATER || s.shape() == Layout.Shape.CANYON)) {
                        int base = oy + sc.colMin[c] - 1;
                        for (int y = base; y > base - 40 && cv.type(x, y, z).isAir(); y--) cv.set(x, y, z, bd("minecraft:stone"));
                    }
                    // cobwebs in the abandoned rooms
                    if (s.ruin()) for (int y = sc.colMin[c] + 1; y < sc.colMax[c]; y++) {
                        int wy = oy + y;
                        if (cv.type(x, wy, z).isAir() && !cv.type(x, wy + 1, z).isAir() && (hash3(t.seed, x, wy, z, 0x5EB) & 63) == 0) cv.set(x, wy, z, bd("minecraft:cobweb"));
                    }
                }
            // the entrance (under the sea it stays water)
            for (long k : door) {
                int lx = (int) (k >> 40), ly = (int) ((k >> 20) & 0xFFFFF), lz = (int) (k & 0xFFFFF);
                if (s.shape() == Layout.Shape.SEABED && oy + ly < Layout.SEA) cv.set(ox + lx, oy + ly, oz + lz, WorldGen.Blocks.WATER);
                else if (!sc.besideSource(lx, ly, lz)) cv.set(ox + lx, oy + ly, oz + lz, WorldGen.Blocks.AIR);
            }
            // AUDIT: the builds' own water (moats, ponds, fountains) ran out wherever the cell beside or under a source was
            // open: at the edge of the schematic, or where the builder left a gap. Those cells get a block.
            if (!sc.plugs.isEmpty() && s.shape() != Layout.Shape.SEABED) {
                BlockData plug = bd(s.ruin() ? "minecraft:mossy_cobblestone" : "minecraft:stone_bricks");
                for (long k : sc.plugs) {
                    int x = ox + (int) (k >> 40) - 1, y = oy + (int) ((k >> 20) & 0xFFFFF) - 1, z = oz + (int) (k & 0xFFFFF) - 1;
                    if (cv.in(x, y, z) && cv.type(x, y, z).isAir()) cv.set(x, y, z, plug);
                }
            }
        }
    }

    /**
     * AUDIT: vanilla's decoration step runs AFTER we draw the builds, and it treats their plain stone like any rock: ore, gravel,
     * dirt, granite blobs in the walls, water springs out of them (the Himeji castle is 19% plain stone, the Giant Tower 38%,
     * the Ghost statue 19%). So once a chunk is fully generated, every cell of a build that isn't what the build says is put
     * back. Returns how many blocks it fixed. Only cells the build owns (never the air round it: snow, vines, trees stay).
     */
    static int heal(Terrain t, org.bukkit.Chunk ch) {
        if (!ready) return 0;
        int bx = ch.getX() << 4, bz = ch.getZ() << 4, fixed = 0;
        int minY = ch.getWorld().getMinHeight(), maxY = ch.getWorld().getMaxHeight();
        for (Layout.Site s : Layout.SITES) {
            Schematic sc = LOADED.get(s.file());
            if (sc == null) continue;
            int ox = s.x() - sc.sx / 2, oz = s.z() - sc.sz / 2, oy = s.y();
            if (ox > bx + 15 || ox + sc.sx - 1 < bx || oz > bz + 15 || oz + sc.sz - 1 < bz) continue;
            Set<Long> door = ENTRANCES.getOrDefault(s.file(), Set.of());
            for (int x = Math.max(bx, ox); x <= Math.min(bx + 15, ox + sc.sx - 1); x++)
                for (int z = Math.max(bz, oz); z <= Math.min(bz + 15, oz + sc.sz - 1); z++) {
                    int lx = x - ox, lz = z - oz, c = lx * sc.sz + lz;
                    if (sc.colMin[c] < 0) continue;
                    for (int i = sc.colStart[c]; i < sc.colStart[c + 1]; i++) {
                        int ly = sc.ys[i], y = oy + ly;
                        if (y < minY || y >= maxY) continue;
                        BlockData b = finalBlock(t, s, sc, x, y, z, lx, ly, lz, sc.palette[sc.idx[i]]);
                        if (b == null || door.contains(pack(lx, ly, lz))) continue;
                        org.bukkit.block.Block at = ch.getBlock(x & 15, y, z & 15);
                        Material want = b.getMaterial(), have = at.getType();
                        if (have == want) continue;
                        if (want.isAir() && !(have == Material.WATER || have == Material.LAVA)) continue;   // a hole in a ruin: only springs are undone
                        at.setBlockData(b, false);
                        fixed++;
                    }
                }
        }
        return fixed;
    }

    private static final Map<BlockData, BlockData> WET = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What a build's cell really becomes in the world (the same answer for {@link #paint} and {@link #heal}); null = leave
     * what's there. Ruins decay, but never where that would let the build's own water out. Under the sea (a sunk build) holes
     * stay water, waterloggable blocks are waterlogged, and things water would wash away aren't placed.
     */
    static BlockData finalBlock(Terrain t, Layout.Site s, Schematic sc, int x, int y, int z, int lx, int ly, int lz, BlockData raw) {
        BlockData b = raw;
        if (s.ruin()) {
            b = ruin(t, s, sc, x, y, z, ly, raw);
            if (b != raw && (b == null || b.getMaterial().isAir()) && sc.besideSource(lx, ly, lz)) b = raw;
        }
        if (b == null) return null;
        if (s.shape() == Layout.Shape.SEABED && y < Layout.SEA) {
            Material m = b.getMaterial();
            if (m.isAir() || m == Material.WATER) return null;
            if (b instanceof org.bukkit.block.data.Waterlogged) {
                return WET.computeIfAbsent(b, k -> { try { org.bukkit.block.data.Waterlogged w = (org.bukkit.block.data.Waterlogged) k.clone(); w.setWaterlogged(true); return w; } catch (Throwable e) { return k; } });
            }
            if (!m.isSolid()) return null;
        }
        return b;
    }

    static final Set<Material> LIGHTS = EnumSet.of(Material.TORCH, Material.WALL_TORCH, Material.LANTERN, Material.SOUL_LANTERN,
            Material.SOUL_TORCH, Material.SOUL_WALL_TORCH, Material.GLOWSTONE, Material.SEA_LANTERN, Material.SHROOMLIGHT, Material.REDSTONE_LAMP,
            Material.CANDLE, Material.END_ROD, Material.JACK_O_LANTERN);

    /** The abandoned look: holes (more toward the top), cracked / mossy stone, broken glass, no lights. null = leave air. */
    static BlockData ruin(Terrain t, Layout.Site s, Schematic sc, int x, int y, int z, int ly, BlockData b) {
        Material m = b.getMaterial();
        if (ly <= sc.floorY + 1) return b;                                   // keep the floor whole
        double f = (double) ly / Math.max(1, sc.sy);
        long cl = hash3(t.seed ^ s.file().hashCode(), x >> 2, y >> 2, z >> 2, 0x2A1);   // 4x4x4 clusters
        long one = hash3(t.seed, x, y, z, 0x2A2);
        double hole = 0.03 + 0.20 * f * f;
        if ((cl & 1023) / 1024.0 < hole && (one & 3) != 0) return WorldGen.Blocks.AIR;
        String n = m.name();
        int r = (int) (one >>> 8 & 255);
        if (LIGHTS.contains(m) || n.endsWith("_CANDLE")) return r < 200 ? WorldGen.Blocks.AIR : b;
        if (n.contains("GLASS")) return r < 110 ? WorldGen.Blocks.AIR : b;
        if (n.endsWith("_BED")) return WorldGen.Blocks.AIR;
        if (n.endsWith("_CARPET")) return r < 120 ? WorldGen.Blocks.AIR : b;
        if (m == Material.STONE_BRICKS) return r < 70 ? bd("minecraft:cracked_stone_bricks") : r < 130 ? bd("minecraft:mossy_stone_bricks") : b;
        if (m == Material.COBBLESTONE) return r < 90 ? bd("minecraft:mossy_cobblestone") : b;
        if (m == Material.DEEPSLATE_BRICKS) return r < 80 ? bd("minecraft:cracked_deepslate_bricks") : b;
        if (m == Material.DEEPSLATE_TILES) return r < 80 ? bd("minecraft:cracked_deepslate_tiles") : b;
        if (m == Material.POLISHED_BLACKSTONE_BRICKS) return r < 80 ? bd("minecraft:cracked_polished_blackstone_bricks") : b;
        if (m == Material.NETHER_BRICKS) return r < 80 ? bd("minecraft:cracked_nether_bricks") : b;
        if (n.endsWith("_PLANKS") && r < 25) return WorldGen.Blocks.AIR;
        return b;
    }

    /**
     * A doorway 3 wide and 4 tall into the ground floor, from the south (+z) side: from the build's outside edge,
     * straight in through its walls until there's room inside. Pure function of the schematic.
     */
    static Set<Long> entrance(Schematic sc) {
        int gy = sc.floorY + 1;
        int cx = sc.sx / 2;
        List<Long> best = null;
        // the middle, then offsets, from the south and the north side: the SHORTEST way in wins. A way that is already open
        // (no wall at all before the inside) means the build needs no doorway.
        for (int side : new int[]{1, -1}) for (int off : new int[]{0, 6, -6, 12, -12, 20, -20, 30, -30}) {
            int x = cx + off;
            if (x < 2 || x > sc.sx - 3) continue;
            List<Long> cut = new ArrayList<>();
            boolean hitWall = false, inside = false;
            int start = side == 1 ? sc.sz - 1 : 0, open = 0;
            for (int k = 0; k < Math.min(70, sc.sz); k++) {
                int z = start - side * k;
                boolean solid = false;
                for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 4; dy++) if (solidAt(sc, x + dx, gy + dy, z)) solid = true;
                if (solid) { hitWall = true; open = 0; for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 4; dy++) cut.add(pack(x + dx, gy + dy, z)); }
                else if (hitWall && ++open >= 3) { inside = true; break; }       // 3 clear blocks in a row = a room, not a crack
            }
            if (hitWall && inside && cut.size() <= 3 * 4 * 14 && (best == null || cut.size() < best.size())) best = cut;   // deeper than 14 = not a doorway, a tunnel through the build (the ship)
        }
        return best == null ? new HashSet<>() : new HashSet<>(best);
    }

    static boolean solidAt(Schematic sc, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= sc.sx || z >= sc.sz) return false;
        int c = x * sc.sz + z;
        for (int i = sc.colStart[c]; i < sc.colStart[c + 1]; i++) if (sc.ys[i] == y) return true;
        return false;
    }

    static long pack(int x, int y, int z) { return ((long) x << 40) | ((long) y << 20) | (z & 0xFFFFF); }

    /** The world box of a site (for protection of statues / loot), or null when its schematic didn't load. */
    static int[] box(Layout.Site s) {
        Schematic sc = LOADED.get(s.file());
        if (sc == null) return null;
        int ox = s.x() - sc.sx / 2, oz = s.z() - sc.sz / 2;
        return new int[]{ox, s.y(), oz, ox + sc.sx - 1, s.y() + sc.sy - 1, oz + sc.sz - 1};
    }
}
