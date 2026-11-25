package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/** Draws in world coordinates into one chunk (anything outside it is skipped), so every feature can be drawn whole. */
final class Canvas {
    final ChunkData d;
    final int bx, bz, minY, maxY;

    Canvas(ChunkData d, int bx, int bz) {
        this.d = d; this.bx = bx; this.bz = bz; this.minY = d.getMinHeight(); this.maxY = d.getMaxHeight();
    }

    boolean in(int x, int y, int z) {
        return x >= bx && x < bx + 16 && z >= bz && z < bz + 16 && y >= minY && y < maxY;
    }

    boolean inXZ(int x, int z) { return x >= bx && x < bx + 16 && z >= bz && z < bz + 16; }

    /** True when a box (world coords, inclusive) touches this chunk. */
    boolean touches(int x0, int z0, int x1, int z1) { return x1 >= bx && x0 < bx + 16 && z1 >= bz && z0 < bz + 16; }

    void set(int x, int y, int z, BlockData b) { if (in(x, y, z)) d.setBlock(x - bx, y, z - bz, b); }

    Material type(int x, int y, int z) { return in(x, y, z) ? d.getType(x - bx, y, z - bz) : Material.VOID_AIR; }

    boolean air(int x, int y, int z) { Material m = type(x, y, z); return m.isAir(); }

    /** Only into air / water / plants (trees never cut into rock or buildings). */
    void soft(int x, int y, int z, BlockData b) {
        if (!in(x, y, z)) return;
        Material m = d.getType(x - bx, y, z - bz);
        if (m.isAir() || m == Material.WATER || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN
                || m == Material.SNOW || m.name().endsWith("_LEAVES") && !b.getMaterial().name().endsWith("_LEAVES"))
            d.setBlock(x - bx, y, z - bz, b);
    }

    /** Only into air (leaves never replace water or each other). */
    void air(int x, int y, int z, BlockData b) {
        if (in(x, y, z) && d.getType(x - bx, y, z - bz).isAir()) d.setBlock(x - bx, y, z - bz, b);
    }

    /** Fill a vertical run (inclusive). */
    void column(int x, int z, int y0, int y1, BlockData b) {
        if (!inXZ(x, z)) return;
        int a = Math.max(minY, Math.min(y0, y1)), c = Math.min(maxY - 1, Math.max(y0, y1));
        for (int y = a; y <= c; y++) d.setBlock(x - bx, y, z - bz, b);
    }

    void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockData b) {
        for (int x = Math.max(x0, bx); x <= Math.min(x1, bx + 15); x++)
            for (int z = Math.max(z0, bz); z <= Math.min(z1, bz + 15); z++)
                column(x, z, y0, y1, b);
    }
}
