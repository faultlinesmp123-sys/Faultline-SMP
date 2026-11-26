package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * THE FARLANDS' FEATURES (2.0 round 10). Pure functions of the seed and position, drawn whole and clipped per chunk like the
 * rest of the generator. On a grid of {@link #CELL}-block cells across the Farlands ring, each cell holds one of:
 * <ul>
 *   <li>FLOATING CHUNKS: one to three stretched, chunk-shaped slabs of grass / dirt / stone hanging 25-90 blocks over the
 *       walls, ragged underneath; some hide a sealed vault (the best loot: {@code farlands_vault_deep}).</li>
 *   <li>GLITCH PILLARS: 3-4 wide towers from the ground up to 170 blocks over it, the whole column slipping one block
 *       sideways every few layers (the Far Lands' stretched look), banded stone / dirt / grass.</li>
 *   <li>WALL VAULTS: a sealed room inside a wall (deepslate tiles, mossy bricks), one or two chests, a spawner further
 *       out; the only sign is a 3x3 of chiseled bricks on the wall top above it. Better loot past {@link #DEEP} blocks.</li>
 *   <li>LOST EXPLORERS' CAMPS: a wool A-frame tent on flat ground, a cold campfire, a bedroll, a chest (and sometimes a
 *       barrel), bones and a skull where the explorer fell. FaultlineItems puts a journal in the chest.</li>
 * </ul>
 * The loot itself comes from the datapack's loot tables, set by {@code Life.context} when the chunk first loads.
 */
final class FarGen {
    private FarGen() {}

    static final int CELL = 40;
    static final int DEEP = 3500;   // vaults past this distance from 0,0 are "deep" (better loot, a spawner)

    enum Kind { NONE, FLOATING, PILLAR, VAULT, CAMP }

    /** The feature of a cell and its anchor (x, z), or null when that cell has none. */
    static int[] feature(Terrain t, int ci, int cj) {
        long h = hash(t.seed, ci, cj, 0xFA2);
        int x = ci * CELL + 6 + (int) ((h >>> 8) % (CELL - 12)), z = cj * CELL + 6 + (int) ((h >>> 20) % (CELL - 12));
        double d = Math.hypot(x, z);
        if (d > Layout.BORDER - 28 || t.farlands(x, z) < 0.75) return null;
        if (Layout.outerRim(x, z) && (RimGen.citadelDist(x, z) < RimGen.CITADEL_PAD + 70 || !RimGen.villagesNear(t.seed, x, z).isEmpty())) return null;
        int roll = (int) (h & 255);
        Kind k = roll < 70 ? Kind.FLOATING : roll < 120 ? Kind.PILLAR : roll < 170 ? Kind.VAULT : roll < 200 ? Kind.CAMP : Kind.NONE;
        if (k == Kind.NONE) return null;
        return new int[]{k.ordinal(), x, z, (int) ((h >>> 32) & 0x7fffffff)};
    }

    static void paint(Terrain t, int bx, int bz, ChunkData data) {
        // nothing out here near this chunk? (the Farlands start at 3280 from 0,0)
        double far = Math.hypot(Math.max(Math.abs(bx), Math.abs(bx + 15)), Math.max(Math.abs(bz), Math.abs(bz + 15)));
        if (far + 64 < Layout.FARLANDS) return;
        Canvas cv = new Canvas(data, bx, bz);
        int reach = 24;
        int c0 = Math.floorDiv(bx - reach, CELL), c1 = Math.floorDiv(bx + 15 + reach, CELL);
        int r0 = Math.floorDiv(bz - reach, CELL), r1 = Math.floorDiv(bz + 15 + reach, CELL);
        for (int ci = c0; ci <= c1; ci++) for (int cj = r0; cj <= r1; cj++) {
            int[] f = feature(t, ci, cj);
            if (f == null) continue;
            switch (Kind.values()[f[0]]) {
                case FLOATING -> floating(cv, t, f[1], f[2], f[3]);
                case PILLAR -> pillar(cv, t, f[1], f[2], f[3]);
                case VAULT -> vault(cv, t, f[1], f[2], f[3]);
                case CAMP -> camp(cv, t, f[1], f[2], f[3]);
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ floating chunks

    static void floating(Canvas cv, Terrain t, int x, int z, int v) {
        int pieces = 1 + v % 3;
        int ground = t.column(x, z).surface;
        int baseTop = Math.max(ground, 300) + 25 + (v >>> 3) % 66;
        for (int p = 0; p < pieces; p++) {
            int s = v * 31 + p * 977;
            int w = 7 + Math.floorMod(s, 9), dpt = 7 + Math.floorMod(s >>> 4, 9), hgt = 5 + Math.floorMod(s >>> 8, 7);
            int ox = x + (p == 0 ? 0 : Math.floorMod(s >>> 12, 21) - 10), oz = z + (p == 0 ? 0 : Math.floorMod(s >>> 17, 21) - 10);
            int top = Math.min(470, baseTop - p * (6 + Math.floorMod(s >>> 22, 10)));
            int x0 = ox - w / 2, z0 = oz - dpt / 2;
            if (!cv.touches(x0, z0, x0 + w - 1, z0 + dpt - 1)) continue;
            for (int xx = x0; xx < x0 + w; xx++) for (int zz = z0; zz < z0 + dpt; zz++) {
                // ragged underside: edges hang shorter, the middle deeper
                double ex = Math.min(xx - x0, x0 + w - 1 - xx), ez = Math.min(zz - z0, z0 + dpt - 1 - zz);
                long hh = hash(t.seed, xx, zz, 0xF10A + p);
                int depth = (int) Math.max(2, Math.min(hgt, 2 + Math.min(ex, ez) * 1.3 + (hh & 3)));
                for (int k = 0; k < depth; k++) {
                    int y = top - k;
                    BlockData b = k == 0 ? bd("minecraft:grass_block") : k < 3 ? bd("minecraft:dirt") : ore(hash3(t.seed, xx, y, zz, 0xF1));
                    cv.set(xx, y, zz, b);
                }
                if ((hh >>> 8 & 15) == 0) cv.air(xx, top + 1, zz, bd("minecraft:short_grass"));
                else if ((hh >>> 8 & 63) == 1) cv.air(xx, top + 1, zz, bd("minecraft:poppy"));
            }
            // one in four of the biggest slabs hides a sealed vault (deep loot)
            if (p == 0 && hgt >= 8 && w >= 9 && dpt >= 9 && (v >>> 11) % 4 == 0) {
                int cy = top - 5;
                cv.box(ox - 2, cy - 1, oz - 2, ox + 2, cy + 2, oz + 2, bd("minecraft:mossy_stone_bricks"));
                cv.box(ox - 1, cy, oz - 1, ox + 1, cy + 1, oz + 1, bd("minecraft:cave_air"));
                cv.set(ox, cy, oz, bd("minecraft:chest[facing=south]"));
                cv.set(ox, cy, oz - 1, bd("minecraft:lantern[hanging=false]"));
            }
        }
    }

    static BlockData ore(long h) {
        int r = (int) (h & 1023);
        return r < 14 ? bd("minecraft:coal_ore") : r < 22 ? bd("minecraft:iron_ore") : r < 25 ? bd("minecraft:gold_ore")
                : r < 27 ? bd("minecraft:diamond_ore") : r < 29 ? bd("minecraft:emerald_ore") : (h >>> 12 & 7) == 0 ? bd("minecraft:andesite") : bd("minecraft:stone");
    }

    // ------------------------------------------------------------------ glitch pillars

    static void pillar(Canvas cv, Terrain t, int x, int z, int v) {
        int w = 3 + v % 2;
        int ground = t.column(x, z).surface;
        int height = 80 + (v >>> 4) % 90;
        int top = Math.min(470, ground + height);
        int sx = 0, sz = 0, band = 3 + (v >>> 10) % 4;
        if (!cv.touches(x - 30, z - 30, x + 30, z + 30)) return;
        for (int y = ground - 3; y <= top; y++) {
            int k = y - (ground - 3);
            if (k > 0 && k % band == 0) {                      // the slip: one block sideways, alternating ways
                long hh = hash3(t.seed, x, y, z, 0xF11);
                if ((hh & 1) == 0) sx += (hh & 2) == 0 ? 1 : -1; else sz += (hh & 2) == 0 ? 1 : -1;
                sx = Math.max(-18, Math.min(18, sx)); sz = Math.max(-18, Math.min(18, sz));
            }
            BlockData b = (k / band) % 3 == 0 ? bd("minecraft:dirt") : (k / band) % 5 == 1 ? bd("minecraft:mossy_cobblestone") : bd("minecraft:stone");
            for (int a = 0; a < w; a++) for (int c = 0; c < w; c++) {
                int xx = x + sx + a, zz = z + sz + c;
                // every band's top face is grass, like the stretched Far Lands' layers
                BlockData put = (k + 1) % band == 0 ? bd("minecraft:grass_block") : b;
                if (y == top) put = bd("minecraft:grass_block");
                cv.set(xx, y, zz, put);
            }
        }
    }

    // ------------------------------------------------------------------ wall vaults

    /** The vault's room middle (x, y, z), or null when the wall there can't hold one. */
    static int[] vaultSpot(Terrain t, int x, int z) {
        int s = Integer.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx += 5) for (int dz = -5; dz <= 5; dz += 5) s = Math.min(s, t.column(x + dx, z + dz).surface);
        int cy = s - 10;
        if (cy < Terrain.SEA + 14) return null;            // not a wall: a gap
        return new int[]{x, cy, z};
    }

    static void vault(Canvas cv, Terrain t, int x, int z, int v) {
        if (!cv.touches(x - 6, z - 6, x + 6, z + 6)) return;
        int[] at = vaultSpot(t, x, z);
        if (at == null) return;
        int cy = at[1];
        boolean deep = Math.hypot(x, z) > DEEP;
        cv.box(x - 4, cy - 2, z - 4, x + 4, cy + 3, z + 4, bd(deep ? "minecraft:deepslate_tiles" : "minecraft:mossy_stone_bricks"));
        cv.box(x - 3, cy - 1, z - 3, x + 3, cy + 2, z + 3, bd("minecraft:cave_air"));
        cv.box(x - 3, cy - 2, z - 3, x + 3, cy - 2, z + 3, bd(deep ? "minecraft:polished_deepslate" : "minecraft:stone_bricks"));
        for (int a = -3; a <= 3; a += 6) for (int b = -3; b <= 3; b += 6) cv.column(x + a, z + b, cy - 1, cy + 2, bd(deep ? "minecraft:cracked_deepslate_tiles" : "minecraft:cracked_stone_bricks"));
        cv.set(x, cy - 1, z, bd("minecraft:chest[facing=south]"));
        if (v % 3 == 0) cv.set(x + 2, cy - 1, z, bd("minecraft:chest[facing=west]"));
        if (deep) cv.set(x - 2, cy - 1, z - 2, bd("minecraft:spawner"));
        cv.set(x, cy + 2, z, bd("minecraft:soul_lantern[hanging=true]"));
        cv.set(x - 2, cy - 1, z + 2, bd("minecraft:skeleton_skull[rotation=" + (v % 16) + "]"));
        cv.set(x + 2, cy - 1, z + 2, bd("minecraft:bone_block[axis=x]"));
        // the only sign from outside: chiseled bricks on the wall top right above it
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            int sy = t.column(x + a, z + b).surface;
            cv.set(x + a, sy, z + b, bd(a == 0 && b == 0 ? "minecraft:chiseled_stone_bricks" : "minecraft:cracked_stone_bricks"));
        }
    }

    // ------------------------------------------------------------------ lost explorers' camps

    /** A flat spot for a camp near the cell's anchor (often the floor of a gap between two walls): {x, z, ground} or null. */
    static int[] campSpot(Terrain t, int x0, int z0) {
        for (int r = 0; r <= 12; r += 3)
            for (int dx = -r; dx <= r; dx += 3) for (int dz = -r; dz <= r; dz += 3) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = x0 + dx, z = z0 + dz, g = t.column(x, z).surface;
                boolean flat = true;
                for (int a = -4; a <= 4 && flat; a += 2) for (int b = -4; b <= 4; b += 2)
                    if (Math.abs(t.column(x + a, z + b).surface - g) > 3) { flat = false; break; }
                if (flat) return new int[]{x, z, g};
            }
        return null;
    }

    static void camp(Canvas cv, Terrain t, int x0, int z0, int v) {
        if (!cv.touches(x0 - 22, z0 - 22, x0 + 22, z0 + 22)) return;
        int[] spot = campSpot(t, x0, z0);
        if (spot == null) return;
        int x = spot[0], z = spot[1], g = spot[2];
        // level the pad
        for (int a = -5; a <= 5; a++) for (int b = -5; b <= 5; b++) {
            if (a * a + b * b > 30) continue;
            cv.column(x + a, z + b, g - 4, g, bd((hash(t.seed, x + a, z + b, 0xFC) & 3) == 0 ? "minecraft:coarse_dirt" : "minecraft:grass_block"));
            cv.box(x + a, g + 1, z + b, x + a, g + 6, z + b, bd("minecraft:air"));
        }
        String wool = (v & 1) == 0 ? "minecraft:white_wool" : "minecraft:brown_wool";
        boolean alongX = (v >>> 1 & 1) == 0;
        // the A-frame: 5 long, 5 wide at the bottom, 3 tall, open at both ends
        for (int l = -2; l <= 2; l++) for (int s = -2; s <= 2; s++) {
            int hgt = 3 - Math.abs(s);
            int xx = alongX ? x + l : x + s, zz = alongX ? z + s : z + l;
            cv.set(xx, g + hgt, zz, bd(wool));
            if (Math.abs(s) == 2) cv.set(xx, g + 1, zz, bd(wool));
        }
        cv.set(x, g + 1, z, bd("minecraft:chest[facing=" + (alongX ? "east" : "south") + "]"));
        cv.set(alongX ? x - 1 : x - 1, g + 1, alongX ? z + 1 : z - 1, bd("minecraft:red_carpet"));
        cv.set(alongX ? x + 1 : x - 1, g + 1, alongX ? z + 1 : z + 1, bd("minecraft:red_carpet"));
        if ((v >>> 2 & 1) == 0) cv.set(alongX ? x - 2 : x + 1, g + 1, alongX ? z - 1 : z - 2, bd("minecraft:barrel[facing=up]"));
        // outside: a cold campfire, logs to sit on, a lantern, the explorer's bones
        int fx = alongX ? x + 4 : x, fz = alongX ? z : z + 4;
        cv.set(fx, g + 1, fz, bd("minecraft:campfire[lit=false]"));
        cv.set(fx + (alongX ? 0 : 2), g + 1, fz + (alongX ? 2 : 0), bd("minecraft:stripped_spruce_log[axis=" + (alongX ? "x" : "z") + "]"));
        cv.set(fx + (alongX ? 0 : -2), g + 1, fz + (alongX ? -2 : 0), bd("minecraft:lantern"));
        int bxp = alongX ? x + 3 : x + 2, bzp = alongX ? z - 3 : z + 3;
        cv.set(bxp, g + 1, bzp, bd("minecraft:skeleton_skull[rotation=" + (v % 16) + "]"));
        cv.set(bxp + 1, g + 1, bzp, bd("minecraft:bone_block[axis=x]"));
        cv.set(bxp, g + 1, bzp + 1, bd("minecraft:white_carpet"));
    }
}
