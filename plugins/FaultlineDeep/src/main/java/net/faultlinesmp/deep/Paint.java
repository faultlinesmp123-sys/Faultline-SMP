package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the generator's structures (the town in the bubble, the ruins) into one chunk. A structure is drawn whole for
 * every chunk it touches and only the blocks inside this chunk are kept, so it lines up across chunk borders. All the
 * "randomness" (decay, cracked bricks, cobwebs) comes from the block's world position: the same in every chunk.
 */
final class Paint {
    private static final ConcurrentHashMap<String, BlockData> CACHE = new ConcurrentHashMap<>();
    private static final StructureRotation[] ROT = {StructureRotation.NONE, StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180, StructureRotation.COUNTERCLOCKWISE_90};

    static BlockData bd(String s) { return CACHE.computeIfAbsent(s, Paint::make); }

    /** A state this server version doesn't know falls back to the plain block (or air): a bad name must never stop a chunk generating. */
    private static BlockData make(String s) {
        try {
            return Bukkit.createBlockData(s.contains(":") ? s : "minecraft:" + s);
        } catch (RuntimeException e) {
            Material m = Material.matchMaterial(s.replaceAll("\\[.*", ""));
            Bukkit.getLogger().warning("[FaultlineDeep] block state '" + s + "' isn't valid here; using " + (m == null ? "air" : m.name().toLowerCase()));
            return (m == null || !m.isBlock() ? Material.AIR : m).createBlockData();
        }
    }

    /** A block state turned with the structure (stairs, doors, beds, panes...). */
    static BlockData bd(String s, int r) {
        if (r == 0 || s.indexOf('[') < 0) return bd(s);
        String key = s + "#" + r;
        BlockData got = CACHE.get(key);
        if (got != null) return got;
        BlockData b = bd(s).clone(); // (made outside computeIfAbsent: a nested update of the same map throws)
        try { b.rotate(ROT[r]); } catch (RuntimeException ignored) { }
        BlockData had = CACHE.putIfAbsent(key, b);
        return had != null ? had : b;
    }

    /** Underwater: everything that can be waterlogged is, and "air" is water. */
    static String wet(String s) {
        if (s.equals("air") || s.equals("cave_air")) return "water";
        int i = s.indexOf('[');
        String name = i < 0 ? s : s.substring(0, i);
        boolean can = (name.endsWith("_stairs") || name.endsWith("_slab") || name.endsWith("_wall") || name.endsWith("_fence")
                || name.endsWith("_pane") || name.equals("iron_bars") || name.equals("iron_chain") || name.endsWith("_trapdoor")
                || name.equals("ladder") || name.equals("chest") || name.equals("lantern") || name.equals("soul_lantern")
                || name.equals("lightning_rod") || name.equals("sea_pickle") || name.equals("decorated_pot"))
                && !s.contains("waterlogged");
        if (!can) return s;
        return i < 0 ? s + "[waterlogged=true]" : s.substring(0, s.length() - 1) + ",waterlogged=true]";
    }

    final ChunkData d;
    final int x0, z0, minY, maxY;

    Paint(ChunkData d, int chunkX, int chunkZ) {
        this.d = d;
        x0 = chunkX << 4; z0 = chunkZ << 4;
        minY = d.getMinHeight(); maxY = d.getMaxHeight();
    }

    /** Does a world box (inclusive) reach into this chunk? */
    boolean touches(int ax0, int az0, int ax1, int az1) { return ax1 >= x0 && ax0 <= x0 + 15 && az1 >= z0 && az0 <= z0 + 15; }

    boolean in(int x, int y, int z) { return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16 && y >= minY && y < maxY; }

    void set(int x, int y, int z, BlockData b) { if (in(x, y, z)) d.setBlock(x - x0, y, z - z0, b); }

    void set(int x, int y, int z, String s) { if (in(x, y, z)) d.setBlock(x - x0, y, z - z0, bd(s)); }

    Material get(int x, int y, int z) { return in(x, y, z) ? d.getType(x - x0, y, z - z0) : Material.AIR; }

    static double u(int x, int y, int z, int salt) { return DeepGenerator.unit(DeepGenerator.hash(0x51ED270BL + y * 7919L, x, z, salt)); }

    /** Weathering: some stone turns cracked or mossy. */
    static String weather(String s, int x, int y, int z) {
        double r = u(x, y, z, 901);
        return switch (s) {
            case "stone_bricks" -> r < 0.2 ? "cracked_stone_bricks" : r < 0.36 ? "mossy_stone_bricks" : s;
            case "cobblestone" -> r < 0.28 ? "mossy_cobblestone" : s;
            case "deepslate_bricks" -> r < 0.25 ? "cracked_deepslate_bricks" : s;
            case "deepslate_tiles" -> r < 0.2 ? "cracked_deepslate_tiles" : s;
            case "polished_blackstone_bricks" -> r < 0.25 ? "cracked_polished_blackstone_bricks" : s;
            case "prismarine_bricks" -> r < 0.15 ? "prismarine" : s;
            case "bricks" -> r < 0.08 ? "mud_bricks" : s;
            default -> s;
        };
    }

    /**
     * One structure's own frame: local x runs across its front, local z goes back from it (the front faces local -z,
     * "north"), y is the world's. r turns it clockwise in 90 degree steps (r = 1: the front faces east).
     */
    final class Frame {
        final int ox, oz, r;
        final boolean wet;

        Frame(int ox, int oz, int r, boolean wet) { this.ox = ox; this.oz = oz; this.r = r & 3; this.wet = wet; }

        int wx(int lx, int lz) { return switch (r) { case 1 -> ox - lz; case 2 -> ox - lx; case 3 -> ox + lz; default -> ox + lx; }; }

        int wz(int lx, int lz) { return switch (r) { case 1 -> oz + lx; case 2 -> oz - lz; case 3 -> oz - lx; default -> oz + lz; }; }

        void set(int lx, int y, int lz, String s) {
            int x = wx(lx, lz), z = wz(lx, lz);
            if (!in(x, y, z)) return;
            String t = weather(s, x, y, z);
            if (wet) t = wet(t);
            d.setBlock(x - x0, y, z - z0, bd(t, r));
        }

        void air(int lx, int y, int lz) { set(lx, y, lz, "air"); }

        void fill(int lx0, int y0, int lz0, int lx1, int y1, int lz1, String s) {
            for (int x = Math.min(lx0, lx1); x <= Math.max(lx0, lx1); x++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                    for (int z = Math.min(lz0, lz1); z <= Math.max(lz0, lz1); z++) set(x, y, z, s);
        }

        double u(int lx, int y, int lz, int salt) { return Paint.u(wx(lx, lz), y, wz(lx, lz), salt); }

        Material get(int lx, int y, int lz) { return Paint.this.get(wx(lx, lz), y, wz(lx, lz)); }
    }

    Frame frame(int ox, int oz, int r, boolean wet) { return new Frame(ox, oz, r, wet); }
}
