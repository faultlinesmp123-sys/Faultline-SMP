package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.ArrayList;
import java.util.List;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * THE OUTER RIM (2.0, the ring added when the border went from 7500 to 10000: only {@link Layout#outerRim}, so only fresh chunks
 * on the live server, which had everything inside 7500 pregenerated). Pure functions of the seed, drawn whole and clipped per chunk:
 * <ul>
 *   <li>THE RIM CITADEL ({@link #CITADEL}): a huge ruined fortress of deepslate and blackstone on a levelled plateau: a 150-block
 *       wall with four towers, a gatehouse facing the world's middle, a courtyard of broken pillars, and a 46-tall keep with four
 *       floors of loot, a vault under it and spawners.</li>
 *   <li>GLITCHED PATCHES (cells of {@link #GLITCH_CELL}): ground tiled in the wrong blocks (grass, sand, end stone, netherrack,
 *       the magenta/black "missing texture" checker), upside-down trees hanging from floating slabs, floating ice blobs and
 *       concrete columns.</li>
 *   <li>LOST VILLAGES (cells of {@link #VILLAGE_CELL}): a village on a pad that a Farlands wall has eaten half of: ruined houses
 *       round a well, beds, chests; FaultlineWorld puts two Lost Villagers at the well (FaultlineItems gives them trades).</li>
 *   <li>RIM LIGHTHOUSES (cells of {@link #LIGHT_CELL}): striped towers on the walls with an unlit campfire in the lamp room. Light it
 *       and the area round it is safe for an hour (Life: no monster spawns, no Rim pressure / storms within {@link #LIGHT_RADIUS}).</li>
 * </ul>
 */
final class RimGen {
    private RimGen() {}

    /** The Citadel's middle (x, z): due south of 0,0, deep in the Outer Rim. */
    static final int[] CITADEL = {0, 4560};
    static final int CITADEL_Y = Terrain.SEA + 60;      // the plateau's top
    static final int CITADEL_PAD = 100;                 // flat out to here, eased to the walls by CITADEL_PAD + 40
    static final int GLITCH_CELL = 220, VILLAGE_CELL = 420, LIGHT_CELL = 300;
    static final int LIGHT_RADIUS = 96;
    static final int VILLAGE_PAD = 34;

    static double citadelDist(double x, double z) { return Math.hypot(x - CITADEL[0], z - CITADEL[1]); }
    static boolean inCitadel(double x, double z) { return citadelDist(x, z) <= 80; }

    // ================================================================== where things are (pure)

    /** A glitched patch in a cell: {x, z, radius, variant} or null. */
    static int[] glitchAt(long seed, int ci, int cj) {
        long h = hash(seed, ci, cj, 0x611C);
        if ((h & 255) > 80) return null;
        int x = ci * GLITCH_CELL + 50 + (int) ((h >>> 8) % (GLITCH_CELL - 100)), z = cj * GLITCH_CELL + 50 + (int) ((h >>> 20) % (GLITCH_CELL - 100));
        if (!usable(x, z, 60)) return null;
        for (int[] v : villagesNear(seed, x, z)) return null;                         // never on a village...
        if (!villagesNear(seed, x + 60, z).isEmpty() || !villagesNear(seed, x - 60, z).isEmpty()
                || !villagesNear(seed, x, z + 60).isEmpty() || !villagesNear(seed, x, z - 60).isEmpty()) return null;
        if (!lightsNear(seed, x, z, 70).isEmpty()) return null;                       // ...or a lighthouse
        return new int[]{x, z, 25 + (int) ((h >>> 32) % 20), (int) ((h >>> 40) & 0xFFFF)};
    }

    /** A lost village in a cell: {x, z, cut angle (degrees), variant} or null. */
    static int[] villageAt(long seed, int ci, int cj) {
        long h = hash(seed, ci, cj, 0x7111);
        if ((h & 255) > 95) return null;
        int x = ci * VILLAGE_CELL + 80 + (int) ((h >>> 8) % (VILLAGE_CELL - 160)), z = cj * VILLAGE_CELL + 80 + (int) ((h >>> 20) % (VILLAGE_CELL - 160));
        if (!usable(x, z, 90)) return null;
        return new int[]{x, z, (int) ((h >>> 32) % 360), (int) ((h >>> 44) & 0xFFFF)};
    }

    /** A lighthouse in a cell: {x, z, variant} or null. */
    static int[] lightAt(long seed, int ci, int cj) {
        long h = hash(seed, ci, cj, 0x1167);
        if ((h & 255) > 110) return null;
        int x = ci * LIGHT_CELL + 40 + (int) ((h >>> 8) % (LIGHT_CELL - 80)), z = cj * LIGHT_CELL + 40 + (int) ((h >>> 20) % (LIGHT_CELL - 80));
        if (!usable(x, z, 30)) return null;
        return new int[]{x, z, (int) ((h >>> 32) & 0xFFFF)};
    }

    /** In the Outer Rim, `margin` blocks inside the border, clear of the Citadel. */
    static boolean usable(int x, int z, int margin) {
        double cheb = Math.max(Math.abs(x), Math.abs(z));
        return cheb >= Layout.RIM + margin && cheb <= Layout.BORDER - margin && citadelDist(x, z) > CITADEL_PAD + 80 + margin;
    }

    /** Every village whose pad could reach (x, z). */
    static List<int[]> villagesNear(long seed, double x, double z) {
        List<int[]> out = new ArrayList<>(1);
        int ci = Math.floorDiv((int) x, VILLAGE_CELL), cj = Math.floorDiv((int) z, VILLAGE_CELL);
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            int[] v = villageAt(seed, ci + a, cj + b);
            if (v != null && Math.hypot(v[0] - x, v[1] - z) < VILLAGE_PAD + 30) out.add(v);
        }
        return out;
    }

    /** The lighthouse within LIGHT_RADIUS-ish of (x, z) in its 3x3 cells. */
    static List<int[]> lightsNear(long seed, double x, double z, double r) {
        List<int[]> out = new ArrayList<>(2);
        int ci = Math.floorDiv((int) x, LIGHT_CELL), cj = Math.floorDiv((int) z, LIGHT_CELL);
        int span = (int) Math.ceil(r / LIGHT_CELL) + 1;
        for (int a = -span; a <= span; a++) for (int b = -span; b <= span; b++) {
            int[] v = lightAt(seed, ci + a, cj + b);
            if (v != null && Math.hypot(v[0] - x, v[1] - z) <= r) out.add(v);
        }
        return out;
    }

    // ================================================================== terrain hooks (Terrain.farlandsHeight)

    /** Reshapes the Farlands' wall height for the Citadel plateau and the village pads. */
    static double shape(long seed, double x, double z, double h) {
        if (!Layout.outerRim(x, z)) return h;
        double cd = citadelDist(x, z);
        if (cd < CITADEL_PAD + 40) h = lerp(CITADEL_Y, h, smooth(CITADEL_PAD, CITADEL_PAD + 40, cd));
        for (int[] v : villagesNear(seed, x, z)) {
            double d = Math.hypot(v[0] - x, v[1] - z);
            // the pad only on one side of the cut: past it the wall carries on through the village ("eaten")
            double a = Math.toRadians(v[2]);
            double side = (x - v[0]) * Math.cos(a) + (z - v[1]) * Math.sin(a);
            double keep = smooth(4, -4, side);                 // 1 on the village side, 0 past the cut
            double pad = (1 - smooth(VILLAGE_PAD, VILLAGE_PAD + 22, d)) * keep;
            h = lerp(h, Terrain.SEA + 18, pad);
        }
        return h;
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ================================================================== painting

    static void paint(Terrain t, int bx, int bz, ChunkData data) {
        if (Math.max(Math.max(Math.abs(bx), Math.abs(bx + 15)), Math.max(Math.abs(bz), Math.abs(bz + 15))) + 60 < Layout.RIM) return;
        Canvas cv = new Canvas(data, bx, bz);
        if (cv.touches(CITADEL[0] - 90, CITADEL[1] - 90, CITADEL[0] + 90, CITADEL[1] + 90)) citadel(cv, t);
        int g0 = Math.floorDiv(bx - 50, GLITCH_CELL), g1 = Math.floorDiv(bx + 65, GLITCH_CELL), h0 = Math.floorDiv(bz - 50, GLITCH_CELL), h1 = Math.floorDiv(bz + 65, GLITCH_CELL);
        for (int ci = g0; ci <= g1; ci++) for (int cj = h0; cj <= h1; cj++) { int[] g = glitchAt(t.seed, ci, cj); if (g != null) glitch(cv, t, g); }
        int v0 = Math.floorDiv(bx - 40, VILLAGE_CELL), v1 = Math.floorDiv(bx + 55, VILLAGE_CELL), w0 = Math.floorDiv(bz - 40, VILLAGE_CELL), w1 = Math.floorDiv(bz + 55, VILLAGE_CELL);
        for (int ci = v0; ci <= v1; ci++) for (int cj = w0; cj <= w1; cj++) { int[] v = villageAt(t.seed, ci, cj); if (v != null) village(cv, t, v); }
        int l0 = Math.floorDiv(bx - 8, LIGHT_CELL), l1 = Math.floorDiv(bx + 23, LIGHT_CELL), m0 = Math.floorDiv(bz - 8, LIGHT_CELL), m1 = Math.floorDiv(bz + 23, LIGHT_CELL);
        for (int ci = l0; ci <= l1; ci++) for (int cj = m0; cj <= m1; cj++) { int[] l = lightAt(t.seed, ci, cj); if (l != null) lighthouse(cv, t, l); }
    }

    // ------------------------------------------------------------------ the Rim Citadel

    static final int WALL = 72, WALL_H = 22, KEEP = 20, KEEP_H = 46;

    /** Ruin: a missing block here (holes in clusters, more toward the top). */
    static boolean gone(long seed, int x, int y, int z, double top) {
        // only small single holes low down (the ragged tops come from each column's own height): nothing is left floating
        if (top > -0.15) return false;
        long h = hash3(seed, x, y, z, 0xC17A);
        return (h & 1023) < 0.03 * 1024;
    }

    static void citadel(Canvas cv, Terrain t) {
        int cx = CITADEL[0], cz = CITADEL[1], g = CITADEL_Y;
        long s = t.seed;
        // the plaza
        for (int x = cx - 92; x <= cx + 92; x++) for (int z = cz - 92; z <= cz + 92; z++) {
            if (!cv.inXZ(x, z)) continue;
            double d = Math.hypot(x - cx, z - cz);
            if (d > 92) continue;
            long h = hash(s, x, z, 0xC1A0);
            String top = d < 68 ? ((h & 7) < 2 ? "minecraft:cracked_deepslate_tiles" : (h & 7) < 5 ? "minecraft:deepslate_tiles" : "minecraft:polished_blackstone_bricks")
                    : ((h & 3) == 0 ? "minecraft:coarse_dirt" : (h & 3) == 1 ? "minecraft:tuff" : "minecraft:grass_block");
            cv.column(x, z, g - 6, g - 1, bd("minecraft:deepslate"));
            cv.set(x, g, z, bd(top));
            cv.box(x, g + 1, z, x, g + 3, z, bd("minecraft:air"));
        }
        // the outer wall (a square ring) with ragged tops
        for (int x = cx - WALL; x <= cx + WALL; x++) for (int z = cz - WALL; z <= cz + WALL; z++) {
            int ex = Math.abs(x - cx), ez = Math.abs(z - cz);
            if (Math.max(ex, ez) < WALL - 3 || !cv.inXZ(x, z)) continue;
            boolean gate = z < cz && ex <= 4 && ez >= WALL - 3;           // the gatehouse faces the world's middle (north)
            int top = g + WALL_H - (int) Math.floorMod(hash(s, x, z, 0xC1A1), 7L);
            for (int y = g + 1; y <= top; y++) {
                if (gate && y <= g + 12) continue;
                if (gone(s, x, y, z, (y - g) / (double) WALL_H - 0.5)) continue;
                String b = (y - g) % 6 == 0 ? "minecraft:polished_blackstone_bricks" : (hash3(s, x, y, z, 0xC1A2) & 3) == 0 ? "minecraft:cracked_deepslate_bricks" : "minecraft:deepslate_bricks";
                cv.set(x, y, z, bd(b));
            }
            if ((x + z) % 2 == 0 && Math.max(ex, ez) == WALL && cv.type(x, top, z).isSolid()) cv.set(x, top + 1, z, bd("minecraft:deepslate_brick_wall"));
        }
        // the towers on the corners
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) tower(cv, s, cx + c[0] * WALL, cz + c[1] * WALL, g, 8, WALL_H + 14);
        tower(cv, s, cx - 9, cz - WALL, g, 5, WALL_H + 8);    // the gatehouse towers
        tower(cv, s, cx + 9, cz - WALL, g, 5, WALL_H + 8);
        // the courtyard: broken pillars in two rows along the way in
        for (int k = 0; k < 6; k++) for (int side : new int[]{-1, 1}) {
            int px = cx + side * 10, pz = cz - WALL + 12 + k * 9;
            int ph = 4 + (int) Math.floorMod(hash(s, px, pz, 0xC1A3), 10L);
            for (int y = g + 1; y <= g + ph; y++) for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) cv.set(px + a, y, pz + b, bd("minecraft:polished_blackstone"));
            cv.set(px, g + 1, pz + 2, bd("minecraft:chiseled_polished_blackstone"));
        }
        // crypt hatches and soul fires about the yard
        for (int k = 0; k < 10; k++) {
            long h = hash(s, k, 7, 0xC1A4);
            int x = cx - 55 + (int) Math.floorMod(h, (long) (110)), z = cz - 55 + (int) ((h >>> 12) % 110);
            if (Math.abs(x - cx) < KEEP + 4 && Math.abs(z - cz) < KEEP + 4) continue;
            if ((h >>> 30 & 1) == 0) { cv.set(x, g + 1, z, bd("minecraft:soul_campfire[lit=true]")); }
            else { cv.set(x, g + 1, z, bd("minecraft:skeleton_skull[rotation=" + (h >>> 34 & 15) + "]")); cv.set(x + 1, g + 1, z, bd("minecraft:bone_block[axis=x]")); }
        }
        keep(cv, s, cx, cz, g);
    }

    static void tower(Canvas cv, long s, int tx, int tz, int g, int r, int h) {
        if (!cv.touches(tx - r - 1, tz - r - 1, tx + r + 1, tz + r + 1)) return;
        for (int x = tx - r; x <= tx + r; x++) for (int z = tz - r; z <= tz + r; z++) {
            double d = Math.hypot(x - tx, z - tz);
            if (d > r + 0.5) continue;
            int top = g + h - (int) Math.floorMod(hash(s, x, z, 0xC1B0), 4L);
            boolean wall = d > r - 1.5;
            for (int y = g + 1; y <= top; y++) {
                if (!wall) { if ((y - g) % 9 == 0 && y <= g + h - 6) cv.set(x, y, z, bd("minecraft:deepslate_tiles")); else cv.set(x, y, z, bd("minecraft:air")); continue; }
                if (gone(s, x, y, z, (y - g) / (double) h - 0.55)) continue;
                boolean slit = (y - g) % 7 == 4 && (Math.abs(x - tx) <= 0 || Math.abs(z - tz) <= 0);
                cv.set(x, y, z, bd(slit ? "minecraft:air" : (y - g) % 8 == 0 ? "minecraft:polished_blackstone_bricks" : "minecraft:deepslate_bricks"));
            }
            if (wall && (x + z) % 2 == 0 && cv.type(x, top, z).isSolid()) cv.set(x, top + 1, z, bd("minecraft:polished_blackstone_brick_wall"));
        }
        cv.column(tx, tz, g + 1, g + h - 2, bd("minecraft:ladder[facing=north]"));
        cv.column(tx, tz + 1, g + 1, g + h - 2, bd("minecraft:deepslate_bricks"));
    }

    static void keep(Canvas cv, long s, int cx, int cz, int g) {
        int x0 = cx - KEEP, x1 = cx + KEEP, z0 = cz - KEEP, z1 = cz + KEEP;
        if (!cv.touches(x0 - 1, z0 - 1, x1 + 1, z1 + 1)) return;
        // the vault under it
        cv.box(cx - 8, g - 12, cz - 8, cx + 8, g - 2, cz + 8, bd("minecraft:polished_deepslate"));
        cv.box(cx - 7, g - 11, cz - 7, cx + 7, g - 3, cz + 7, bd("minecraft:cave_air"));
        cv.set(cx, g - 11, cz, bd("minecraft:chest[facing=north]"));
        cv.set(cx + 2, g - 11, cz, bd("minecraft:chest[facing=north]"));
        cv.set(cx - 2, g - 11, cz, bd("minecraft:chest[facing=north]"));
        cv.set(cx - 5, g - 11, cz - 5, bd("minecraft:spawner"));
        cv.set(cx + 5, g - 11, cz + 5, bd("minecraft:spawner"));
        cv.set(cx, g - 3, cz, bd("minecraft:soul_lantern[hanging=true]"));
        cv.column(cx, cz - 7, g - 11, g, bd("minecraft:ladder[facing=south]"));
        cv.set(cx, g, cz - 7, bd("minecraft:air"));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!cv.inXZ(x, z)) continue;
            boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
            boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
            int top = g + KEEP_H + (corner ? 6 : 0) - (wall ? (int) Math.floorMod(hash(s, x, z, 0xC1C0), 5L) : 0);
            for (int y = g + 1; y <= top; y++) {
                int fl = (y - g) % 11;
                if (!wall) {
                    if (fl == 0 && y < g + KEEP_H) cv.set(x, y, z, bd((x + z) % 2 == 0 ? "minecraft:deepslate_tiles" : "minecraft:polished_deepslate"));
                    else cv.set(x, y, z, bd("minecraft:air"));
                    continue;
                }
                boolean door = z == z0 && Math.abs(x - cx) <= 2 && y <= g + 6;
                boolean window = (fl >= 4 && fl <= 6) && ((x - cx) % 6 == 0 || (z - cz) % 6 == 0) && !corner;
                if (door || window) continue;
                if (gone(s, x, y, z, (y - g) / (double) KEEP_H - 0.6)) continue;
                cv.set(x, y, z, bd(fl == 0 ? "minecraft:polished_blackstone_bricks" : (hash3(s, x, y, z, 0xC1C1) & 7) == 0 ? "minecraft:cracked_polished_blackstone_bricks" : "minecraft:deepslate_bricks"));
            }
        }
        // ladders up the inside, a hole in every floor beside them
        for (int y = g + 1; y < g + KEEP_H; y++) {
            cv.set(cx + KEEP, y, cz + 3, bd("minecraft:deepslate_bricks"));
            cv.set(cx + KEEP - 1, y, cz + 3, bd("minecraft:ladder[facing=west]"));
        }
        // each floor: chests, a spawner on the upper two, soul lanterns, rubble
        for (int f = 0; f < 4; f++) {
            int fy = g + 1 + f * 11;
            cv.set(cx - 6, fy, cz + 6, bd("minecraft:chest[facing=east]"));
            if (f >= 1) cv.set(cx + 6, fy, cz - 6, bd("minecraft:chest[facing=west]"));
            if (f >= 2) cv.set(cx, fy, cz, bd("minecraft:spawner"));
            if (f < 3) {
                cv.set(cx - 6, fy + 9, cz - 6, bd("minecraft:soul_lantern[hanging=true]"));
                cv.set(cx + 6, fy + 9, cz + 6, bd("minecraft:soul_lantern[hanging=true]"));
            } else {
                cv.set(cx - 6, fy, cz - 6, bd("minecraft:soul_lantern[hanging=false]"));
                cv.set(cx + 6, fy, cz + 6, bd("minecraft:soul_lantern[hanging=false]"));
            }
            for (int k = 0; k < 6; k++) {
                long h = hash(s, f, k, 0xC1C2);
                int x = cx - KEEP + 2 + (int) Math.floorMod(h, (long) ((KEEP * 2 - 4))), z = cz - KEEP + 2 + (int) ((h >>> 16) % (KEEP * 2 - 4));
                if ((h >>> 40 & 1) == 0) cv.set(x, fy, z, bd("minecraft:cobbled_deepslate_slab"));
                else if (f < 3) cv.set(x, fy + 9, z, bd("minecraft:cobweb"));
            }
        }
        // the throne at the top
        int ty = g + 1 + 3 * 11;
        cv.box(cx - 2, ty, cz + KEEP - 5, cx + 2, ty, cz + KEEP - 3, bd("minecraft:polished_blackstone"));
        cv.set(cx, ty + 1, cz + KEEP - 3, bd("minecraft:blackstone_stairs[facing=north]"));
        cv.set(cx - 1, ty + 1, cz + KEEP - 3, bd("minecraft:polished_blackstone_wall"));
        cv.set(cx + 1, ty + 1, cz + KEEP - 3, bd("minecraft:polished_blackstone_wall"));
        cv.set(cx, ty + 1, cz + KEEP - 5, bd("minecraft:chest[facing=north]"));
    }

    // ------------------------------------------------------------------ glitched patches

    static final String[] GLITCH = {"minecraft:grass_block", "minecraft:sand", "minecraft:end_stone", "minecraft:netherrack", "minecraft:mycelium",
            "minecraft:snow_block", "minecraft:red_sand", "minecraft:podzol", "minecraft:prismarine", "minecraft:crimson_nylium"};

    static void glitch(Canvas cv, Terrain t, int[] gl) {
        int x0 = gl[0], z0 = gl[1], r = gl[2], v = gl[3];
        long s = t.seed;
        if (!cv.touches(x0 - r - 10, z0 - r - 10, x0 + r + 10, z0 + r + 10)) return;
        // the ground: tiles of the wrong blocks, and a magenta/black checker in the middle
        for (int x = x0 - r; x <= x0 + r; x++) for (int z = z0 - r; z <= z0 + r; z++) {
            if (!cv.inXZ(x, z)) continue;
            double d = Math.hypot(x - x0, z - z0) + 6 * Math.sin(x * 0.31 + v) * Math.cos(z * 0.27);
            if (d > r) continue;
            int y = t.column(x, z).surface;
            String b;
            if (d < r * 0.25) b = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0 ? "minecraft:magenta_concrete" : "minecraft:black_concrete";
            else b = GLITCH[(int) Math.floorMod(hash(s, Math.floorDiv(x, 3), Math.floorDiv(z, 3), 0x6170 + v), (long) GLITCH.length)];
            cv.set(x, y, z, bd(b));
            // a few tiles stuck a block or two too high / low
            long h = hash(s, x, z, 0x6171);
            if ((h & 31) == 0) cv.set(x, y + 1 + (int) (h >>> 5 & 1), z, bd(b));
        }
        // upside-down trees hanging from floating slabs
        int trees = 3 + v % 4;
        for (int k = 0; k < trees; k++) {
            long h = hash(s, v, k, 0x6172);
            int tx = x0 - r / 2 + (int) Math.floorMod(h, (long) (Math.max(1, r))), tz = z0 - r / 2 + (int) ((h >>> 16) % Math.max(1, r));
            int ground = t.column(tx, tz).surface;
            int slab = ground + 18 + (int) ((h >>> 32) % 14);
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
                cv.set(tx + a, slab, tz + b, bd("minecraft:dirt"));
                cv.set(tx + a, slab - 1, tz + b, bd("minecraft:grass_block"));   // grass facing the ground
            }
            int len = 5 + (int) ((h >>> 40) % 3);
            cv.column(tx, tz, slab - 1 - len, slab - 2, bd("minecraft:oak_log"));
            int ly = slab - 1 - len;
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) for (int c = -2; c <= 0; c++) {
                if (Math.abs(a) + Math.abs(b) + Math.abs(c) > 3) continue;
                cv.air(tx + a, ly + c, tz + b, bd("minecraft:oak_leaves[persistent=true]"));
            }
        }
        // floating blue-ice blobs and coloured columns standing on nothing
        for (int k = 0; k < 5; k++) {
            long h = hash(s, v, k, 0x6173);
            int bx = x0 - r + (int) Math.floorMod(h, (long) ((2L * r))), bz = z0 - r + (int) ((h >>> 16) % (2L * r));
            int y = t.column(bx, bz).surface + 8 + (int) ((h >>> 32) % 16);
            if ((h >>> 50 & 1) == 0) {
                for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) for (int c = -2; c <= 2; c++)
                    if (a * a + b * b + c * c <= 5) cv.set(bx + a, y + c, bz + b, bd("minecraft:blue_ice"));
            } else {
                String col = new String[]{"minecraft:lime_concrete", "minecraft:cyan_concrete", "minecraft:orange_concrete", "minecraft:purple_concrete"}[(int) (h >>> 52 & 3)];
                cv.column(bx, bz, y, y + 6 + (int) (h >>> 54 & 7), bd(col));
            }
        }
    }

    // ------------------------------------------------------------------ lost villages

    static void village(Canvas cv, Terrain t, int[] vl) {
        int x0 = vl[0], z0 = vl[1], v = vl[3];
        if (!cv.touches(x0 - VILLAGE_PAD - 4, z0 - VILLAGE_PAD - 4, x0 + VILLAGE_PAD + 4, z0 + VILLAGE_PAD + 4)) return;
        long s = t.seed;
        double a = Math.toRadians(vl[2]), ca = Math.cos(a), sa = Math.sin(a);
        int g = Terrain.SEA + 18;
        // the well in the middle
        well(cv, x0, z0, g);
        // houses on a ring round it; only on the village side of the cut (the wall ate the rest)
        int n = 7;
        for (int k = 0; k < n; k++) {
            double ang = Math.PI * 2 * k / n + (v % 100) / 100.0;
            int hx = x0 + (int) Math.round(Math.cos(ang) * 17), hz = z0 + (int) Math.round(Math.sin(ang) * 17);
            house(cv, s, hx, hz, g, k, ca, sa, x0, z0, v);
        }
        // a path ring
        for (int x = x0 - 12; x <= x0 + 12; x++) for (int z = z0 - 12; z <= z0 + 12; z++) {
            double d = Math.hypot(x - x0, z - z0);
            if (d < 9 || d > 11 || !cv.inXZ(x, z)) continue;
            if ((x - x0) * ca + (z - z0) * sa > 0) continue;
            cv.set(x, g, z, bd((hash(s, x, z, 0x7112) & 3) == 0 ? "minecraft:coarse_dirt" : "minecraft:dirt_path"));
        }
    }

    static void well(Canvas cv, int x, int z, int g) {
        for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
            boolean rim = Math.abs(a) == 2 || Math.abs(b) == 2;
            cv.column(x + a, z + b, g - 7, g - 5, bd("minecraft:mossy_cobblestone"));   // a solid floor (caves under the pad)
            cv.column(x + a, z + b, g - 4, g, bd(rim ? "minecraft:mossy_cobblestone" : "minecraft:water"));
            if (rim) cv.set(x + a, g + 1, z + b, bd("minecraft:cobblestone_wall"));
        }
        for (int a : new int[]{-2, 2}) for (int b : new int[]{-2, 2}) cv.column(x + a, z + b, g + 2, g + 4, bd("minecraft:oak_log"));
        cv.box(x - 2, g + 5, z - 2, x + 2, g + 5, z + 2, bd("minecraft:mossy_cobblestone_slab"));
    }

    static void house(Canvas cv, long s, int hx, int hz, int g, int k, double ca, double sa, int vx, int vz, int v) {
        int w = 3, dpt = 3, h = 5;
        if (!cv.touches(hx - w - 1, hz - dpt - 1, hx + w + 1, hz + dpt + 1)) return;
        String[] wood = {"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks"};
        String plank = wood[(k + v) % 3];
        for (int x = hx - w; x <= hx + w; x++) for (int z = hz - dpt; z <= hz + dpt; z++) {
            if ((x - vx) * ca + (z - vz) * sa > -1) continue;          // eaten by the wall
            boolean edge = Math.abs(x - hx) == w || Math.abs(z - hz) == dpt;
            boolean corner = Math.abs(x - hx) == w && Math.abs(z - hz) == dpt;
            cv.set(x, g, z, bd("minecraft:cobblestone"));
            int top = g + h - (int) Math.floorMod(hash(s, x, z, 0x7113), 3L);
            for (int y = g + 1; y <= g + h; y++) {
                if (!edge) { cv.set(x, y, z, bd("minecraft:air")); continue; }
                if (y > top) continue;
                boolean door = z == hz - dpt && x == hx && y <= g + 2;
                boolean win = y == g + 2 && !corner && (x == hx || z == hz) && !door;
                if (door) continue;
                cv.set(x, y, z, bd(win ? "minecraft:air" : corner ? "minecraft:stripped_oak_log" : (hash3(s, x, y, z, 0x7114) & 7) == 0 ? "minecraft:mossy_cobblestone" : plank));
            }
        }
        if ((hx - vx) * ca + (hz - vz) * sa > -2) return;
        cv.set(hx + w - 1, g + 1, hz + dpt - 1, bd("minecraft:chest[facing=west]"));
        cv.set(hx - w + 1, g + 1, hz + dpt - 1, bd("minecraft:red_bed[facing=south,part=head]"));
        cv.set(hx - w + 1, g + 1, hz + dpt - 2, bd("minecraft:red_bed[facing=south,part=foot]"));
        cv.set(hx + w - 1, g + 1, hz - dpt + 1, bd("minecraft:crafting_table"));
        cv.set(hx + w - 1, g + 2, hz + dpt - 1, bd("minecraft:lantern[hanging=false]"));   // on the chest (the roofs fell in)
        if (k % 3 == 0) cv.set(hx, g + 1, hz, bd("minecraft:cobweb"));
    }

    // ------------------------------------------------------------------ rim lighthouses

    static final int LIGHT_H = 28;

    /** The lamp (the campfire players light): {x, y, z}. */
    static int[] lamp(Terrain t, int[] l) {
        int base = t.column(l[0], l[1]).surface;
        return new int[]{l[0], base + LIGHT_H + 2, l[1]};
    }

    static void lighthouse(Canvas cv, Terrain t, int[] l) {
        int x0 = l[0], z0 = l[1];
        if (!cv.touches(x0 - 5, z0 - 5, x0 + 5, z0 + 5)) return;
        int base = t.column(x0, z0).surface;
        int r = 3;
        for (int x = x0 - r - 1; x <= x0 + r + 1; x++) for (int z = z0 - r - 1; z <= z0 + r + 1; z++) {
            double d = Math.hypot(x - x0, z - z0);
            if (d > r + 1.2) continue;
            cv.column(x, z, base - 4, base, bd("minecraft:stone_bricks"));   // footing
            if (d > r + 0.5) continue;
            boolean wall = d > r - 1;
            for (int y = base + 1; y <= base + LIGHT_H; y++) {
                if (!wall) { cv.set(x, y, z, bd("minecraft:air")); continue; }
                boolean door = z == z0 - r && x == x0 && y <= base + 2;
                if (door) { cv.set(x, y, z, bd("minecraft:air")); continue; }
                int band = (y - base) / 4;
                cv.set(x, y, z, bd(band % 2 == 0 ? "minecraft:white_terracotta" : "minecraft:red_terracotta"));
            }
            cv.set(x, base + LIGHT_H + 1, z, bd("minecraft:polished_andesite"));   // the gallery floor
        }
        cv.column(x0 + r - 1, z0, base + 1, base + LIGHT_H, bd("minecraft:ladder[facing=west]"));
        cv.set(x0 + r - 1, base + LIGHT_H + 1, z0, bd("minecraft:ladder[facing=west]"));
        // the lamp room: glass all round, a roof, the unlit campfire on a stand
        int ly = base + LIGHT_H + 2;
        for (int x = x0 - 2; x <= x0 + 2; x++) for (int z = z0 - 2; z <= z0 + 2; z++) {
            boolean edge = Math.abs(x - x0) == 2 || Math.abs(z - z0) == 2;
            for (int y = ly; y <= ly + 3; y++) {
                if (edge) cv.set(x, y, z, bd(Math.abs(x - x0) == 2 && Math.abs(z - z0) == 2 ? "minecraft:iron_block" : "minecraft:glass"));
                else cv.set(x, y, z, bd("minecraft:air"));
            }
            cv.set(x, ly + 4, z, bd("minecraft:red_terracotta"));
        }
        cv.set(x0, ly + 5, z0, bd("minecraft:lightning_rod"));
        cv.set(x0, ly, z0, bd("minecraft:campfire[lit=false]"));
        cv.set(x0 + 1, ly, z0, bd("minecraft:air"));   // room for the climber
        cv.set(x0 - 1, ly, z0 + 1, bd("minecraft:barrel[facing=up]"));
    }
}
