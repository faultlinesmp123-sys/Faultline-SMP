package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.util.ArrayList;
import java.util.List;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * THE BROKEN LANDS (2.0): five huge, FLAT, really broken biomes in the Farlands between 5000 and the border (|x| or |z|).
 * Each one is a wedge of the ring (`CENTERS`, `HALF` degrees each side, a wobbling edge); between the wedges the old
 * Farlands walls carry on (the Rim Citadel, lost villages and lighthouses keep their space there). Pure functions of x, z
 * (the same on every seed), so the generator, the biome provider and the other plugins agree.
 * <ol>
 *   <li>MISSING CHUNKS: flat grassland where whole chunks are gone (void down to the bedrock), shifted up / down, floating over
 *       their own hole, or in the wrong biome.</li>
 *   <li>MISSING TEXTURE WASTES: magenta and black checker ground, floating checker cubes.</li>
 *   <li>THE SMEAR: the land stretched along x into long streaks of repeated blocks; a sudden seam every {@link #SMEAR_SEAM}.</li>
 *   <li>THE UPSIDE DOWN: a dark floor with lava pools under an upside-down landmass (grass on its underside, trees hanging
 *       from it, glowstone), with holes in it.</li>
 *   <li>THE DEBUG FIELDS: superflat ground with floating grids of blocks, like the game's debug world.</li>
 * </ol>
 */
final class Broken {
    private Broken() {}

    enum Kind {
        MISSING_CHUNKS(B.MISSING_CHUNKS, "Missing Chunks"),
        MISSING_TEXTURE(B.MISSING_TEXTURE, "Missing Texture Wastes"),
        SMEAR(B.SMEAR, "The Smear"),
        UPSIDE_DOWN(B.UPSIDE_DOWN, "The Upside Down"),
        DEBUG(B.DEBUG_FIELDS, "The Debug Fields");
        final B biome; final String title;
        Kind(B biome, String title) { this.biome = biome; this.title = title; }
    }

    static final int START = 5000;                 // Chebyshev distance where they begin
    static final int BLEND = 260;                  // the walls come down over this much
    /** Wedge middles in degrees (atan2(z, x)): the Rim Citadel at 90 sits in the gap between 54 and 126. */
    static final double[] CENTERS = {54, 126, 198, 270, 342};
    static final double HALF = 24, EDGE = 3;       // half-width and the soft edge (degrees)
    static final int SMEAR_SEAM = 176;
    static final int FLOOR = Terrain.SEA + 22;     // the typical ground height out here

    private static final SimplexNoiseGenerator N1 = new SimplexNoiseGenerator(0xB40CE11L), N2 = new SimplexNoiseGenerator(0x5E4A3L);

    static double cheb(double x, double z) { return Math.max(Math.abs(x), Math.abs(z)); }

    /** Which wedge (or -1) and how far into it (0..1). */
    static int wedge(double x, double z) {
        double a = Math.toDegrees(Math.atan2(z, x));
        if (a < 0) a += 360;
        a += 6 * N1.noise(x / 700.0, z / 700.0);
        for (int i = 0; i < CENTERS.length; i++) {
            double d = Math.abs(((a - CENTERS[i]) % 360 + 540) % 360 - 180);
            if (d < HALF + EDGE) return i;
        }
        return -1;
    }

    static double angleWeight(double x, double z, int i) {
        double a = Math.toDegrees(Math.atan2(z, x));
        if (a < 0) a += 360;
        a += 6 * N1.noise(x / 700.0, z / 700.0);
        double d = Math.abs(((a - CENTERS[i]) % 360 + 540) % 360 - 180);
        return Terrain.smooth(HALF + EDGE, HALF - EDGE, d);
    }

    /** 0 = ordinary Farlands, 1 = fully broken. */
    static double weight(double x, double z) {
        double c = cheb(x, z);
        if (c < START - 80) return 0;
        int i = wedge(x, z);
        if (i < 0) return 0;
        double edge = START + 40 * N2.noise(x / 300.0, z / 300.0);
        double w = Terrain.smooth(edge, edge + BLEND, c) * angleWeight(x, z, i);
        double cd = RimGen.citadelDist(x, z);
        if (cd < RimGen.CITADEL_PAD + 300) w *= Terrain.smooth(RimGen.CITADEL_PAD + 140, RimGen.CITADEL_PAD + 300, cd);
        return w;
    }

    static Kind kind(double x, double z) {
        if (cheb(x, z) < START - 80) return null;
        int i = wedge(x, z);
        return i < 0 ? null : Kind.values()[i];
    }

    /** The biome at (x, z) when it's broken enough, else null. */
    static B biome(double x, double z) {
        if (weight(x, z) < 0.5) return null;
        Kind k = kind(x, z);
        return k == null ? null : k.biome;
    }

    // ================================================================== heights (Terrain.column)

    /** The ground height of a fully broken column. */
    static int height(Kind k, int x, int z) {
        switch (k) {
            case MISSING_CHUNKS -> {
                int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                long h = hash(0x4D155L, cx, cz, 1);
                int roll = (int) (h & 31);
                if (roll < 4) return -64;                                   // a missing chunk: down to the bedrock
                if (roll < 6) return -64;                                   // a floating chunk (drawn in paint) over its own hole
                int shift = (int) ((h >>> 8) % 7) - 3;                      // -3..3
                return FLOOR + shift * 3 + (int) Math.round(1.5 * N2.noise(x / 40.0, z / 40.0));
            }
            case MISSING_TEXTURE -> { return FLOOR - 4 + (int) Math.round(2 * N1.noise(x / 60.0, z / 60.0)); }
            case SMEAR -> {
                int seam = Math.floorDiv(x, SMEAR_SEAM);
                double n = N1.noise(seam * 7.31, z / 95.0) * 9 + N2.noise(seam * 3.7 + 11, z / 31.0) * 3;
                return FLOOR - 6 + (int) Math.round(n);
            }
            case UPSIDE_DOWN -> { return Terrain.SEA + 6 + (int) Math.round(2 * N1.noise(x / 50.0, z / 50.0)); }
            case DEBUG -> { return Terrain.SEA + 10; }
        }
        return FLOOR;
    }

    /** Called by Terrain.farlandsHeight's caller: the walls' height eased into the broken land's. */
    static double blend(double x, double z, double wall) {
        double w = weight(x, z);
        if (w <= 0) return wall;
        Kind k = kind(x, z);
        if (k == null) return wall;
        int h = height(k, (int) Math.floor(x), (int) Math.floor(z));
        if (h <= -64 && w > 0.98) return -64;                              // the chunk holes stay holes
        if (h <= -64) h = FLOOR;                                           // (their edges blend into land)
        return Terrain.lerp(wall, h, w);
    }

    // ================================================================== painting (WorldGen.generateSurface, after vanilla's surface)

    static final BlockData[] DEBUG_BLOCKS;
    static final String[] SMEAR_STREAKS = {"minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
            "minecraft:deepslate", "minecraft:calcite", "minecraft:dripstone_block", "minecraft:mud", "minecraft:packed_mud", "minecraft:moss_block",
            "minecraft:white_wool", "minecraft:red_wool", "minecraft:cyan_wool", "minecraft:yellow_terracotta", "minecraft:terracotta",
            "minecraft:orange_terracotta", "minecraft:light_blue_concrete", "minecraft:purple_concrete", "minecraft:lime_concrete",
            "minecraft:glass", "minecraft:prismarine", "minecraft:end_stone", "minecraft:netherrack", "minecraft:sandstone",
            "minecraft:red_sandstone", "minecraft:snow_block", "minecraft:packed_ice", "minecraft:coal_ore", "minecraft:mossy_cobblestone",
            "minecraft:bricks", "minecraft:oak_planks", "minecraft:dark_oak_log", "minecraft:magenta_glazed_terracotta"};
    static final String[] WRONG_TOP = {"minecraft:sand", "minecraft:snow_block", "minecraft:netherrack", "minecraft:end_stone", "minecraft:mycelium",
            "minecraft:podzol", "minecraft:red_sand", "minecraft:soul_soil", "minecraft:crimson_nylium", "minecraft:mud"};

    static {
        List<BlockData> list = new ArrayList<>();
        String[] fams = {"_WOOL", "_CONCRETE", "_TERRACOTTA", "_STAINED_GLASS", "_PLANKS", "_LOG", "_BRICKS", "_GLAZED_TERRACOTTA"};
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || !m.isBlock()) continue;
            String n = m.name();
            boolean ok = false;
            for (String f : fams) if (n.endsWith(f)) ok = true;
            if (n.startsWith("STRIPPED_") || n.contains("COPPER") || n.contains("INFESTED")) ok = false;
            if (n.equals("STONE") || n.equals("ANDESITE") || n.equals("DIORITE") || n.equals("GRANITE") || n.equals("TUFF") || n.equals("CALCITE")
                    || n.equals("GLASS") || n.equals("BOOKSHELF") || n.equals("HAY_BLOCK") || n.equals("MELON") || n.equals("PUMPKIN")
                    || n.equals("SPONGE") || n.equals("SLIME_BLOCK") || n.equals("HONEYCOMB_BLOCK") || n.equals("OBSIDIAN") || n.equals("GLOWSTONE")
                    || n.equals("SEA_LANTERN") || n.equals("SHROOMLIGHT") || n.equals("MOSS_BLOCK") || n.equals("BONE_BLOCK") || n.equals("PRISMARINE")
                    || n.equals("PURPUR_BLOCK") || n.equals("END_STONE") || n.equals("NETHERRACK") || n.equals("BASALT") || n.equals("BLACKSTONE")) ok = true;
            if (!ok) continue;
            try { if (!m.isSolid()) continue; list.add(m.createBlockData()); } catch (Throwable ignored) { }
        }
        DEBUG_BLOCKS = list.toArray(new BlockData[0]);
    }

    static void paint(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData data) {
        if (cheb(bx + 8, bz + 8) < START - 120) return;
        Canvas cv = new Canvas(data, bx, bz);
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = bx + lx, z = bz + lz;
            double w = weight(x, z);
            if (w < 0.5) continue;
            Kind k = kind(x, z);
            if (k == null) continue;
            int top = cols[lx * 16 + lz].surface;
            switch (k) {
                case MISSING_CHUNKS -> missingChunks(cv, x, z, top);
                case MISSING_TEXTURE -> missingTexture(cv, x, z, top);
                case SMEAR -> smear(cv, x, z, top);
                case UPSIDE_DOWN -> upsideDown(cv, x, z, top, w);
                case DEBUG -> debug(cv, x, z, top);
            }
        }
        // features drawn whole across chunks
        if (kind(bx + 8, bz + 8) == Kind.MISSING_TEXTURE || kind(bx + 8, bz + 8) == Kind.UPSIDE_DOWN) {
            int cell = 32;
            for (int ci = Math.floorDiv(bx - 12, cell); ci <= Math.floorDiv(bx + 27, cell); ci++)
                for (int cj = Math.floorDiv(bz - 12, cell); cj <= Math.floorDiv(bz + 27, cell); cj++) {
                    long h = hash(0xC0BEL, ci, cj, 2);
                    int x = ci * cell + 6 + (int) ((h >>> 4) % 20), z = cj * cell + 6 + (int) ((h >>> 12) % 20);
                    if (weight(x, z) < 0.9) continue;
                    Kind k = kind(x, z);
                    if (k == Kind.MISSING_TEXTURE && (h & 3) != 0) checkerCube(cv, x, z, h);
                    if (k == Kind.UPSIDE_DOWN && (h & 1) == 0) hangingTree(cv, x, z, h);
                }
        }
    }

    // ---- 1. missing chunks
    static void missingChunks(Canvas cv, int x, int z, int top) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        long h = hash(0x4D155L, cx, cz, 1);
        int roll = (int) (h & 31);
        if (roll >= 4 && roll < 6) {                                       // a floating chunk over its own hole
            int y0 = FLOOR + 10 + (int) ((h >>> 16) % 30);
            cv.column(x, z, y0 - 5, y0 - 1, bd("minecraft:stone"));
            cv.column(x, z, y0 - 1, y0 - 1, bd("minecraft:dirt"));
            cv.set(x, y0, z, bd("minecraft:grass_block"));
            return;
        }
        if (roll < 4 || top <= -60) return;
        if (roll < 9) {                                                    // the wrong biome's ground
            String m = WRONG_TOP[(int) ((h >>> 24) % WRONG_TOP.length)];
            cv.set(x, top, z, bd(m));
            cv.set(x, top - 1, z, bd(m.contains("sand") ? "minecraft:sandstone" : m.contains("snow") ? "minecraft:packed_ice" : m));
            return;
        }
        // chunk-edge seams: a line of bedrock / glass where the chunk meets one at a different height
        boolean edge = Math.floorMod(x, 16) == 0 || Math.floorMod(z, 16) == 0;
        if (edge && ((h >>> 30) & 3) == 0) cv.set(x, top, z, bd("minecraft:bedrock"));
        else if (((hash(0x6E55L, x, z, 3) & 31) == 0)) cv.set(x, top + 1, z, bd("minecraft:short_grass"));
    }

    // ---- 2. missing texture
    static void missingTexture(Canvas cv, int x, int z, int top) {
        boolean a = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0;
        BlockData m = bd(a ? "minecraft:magenta_concrete" : "minecraft:black_concrete");
        for (int y = top - 2; y <= top; y++) cv.set(x, y, z, m);
        if ((hash(0x7E7L, x, z, 4) & 255) == 0) cv.set(x, top + 1, z, bd("minecraft:purple_glazed_terracotta"));
    }

    static void checkerCube(Canvas cv, int x, int z, long h) {
        int s = 3 + (int) ((h >>> 20) % 6), y0 = FLOOR + 6 + (int) ((h >>> 28) % 40);
        for (int dx = 0; dx < s; dx++) for (int dy = 0; dy < s; dy++) for (int dz = 0; dz < s; dz++) {
            boolean a = ((dx + dy + dz) & 1) == 0;
            cv.set(x + dx, y0 + dy, z + dz, bd(a ? "minecraft:magenta_concrete" : "minecraft:black_concrete"));
        }
    }

    // ---- 3. the smear
    static void smear(Canvas cv, int x, int z, int top) {
        int seam = Math.floorDiv(x, SMEAR_SEAM);
        // each z row is one streak of one block, the length of the seam (stretched texture)
        long h = hash(0x5EA4L, seam, z, 5);
        BlockData m = bd(SMEAR_STREAKS[(int) Math.floorMod(h, SMEAR_STREAKS.length)]);
        BlockData m2 = bd(SMEAR_STREAKS[(int) Math.floorMod(h >>> 20, SMEAR_STREAKS.length)]);
        for (int y = top - 5; y <= top; y++) cv.set(x, y, z, y >= top - 1 ? m : m2);
        // the seam itself: a sheer step with a line of bedrock
        if (Math.floorMod(x, SMEAR_SEAM) == 0) cv.set(x, top, z, bd("minecraft:bedrock"));
    }

    // ---- 4. the upside down
    static void upsideDown(Canvas cv, int x, int z, int top, double w) {
        // the floor: dark, with lava pools in the hollows
        BlockData floor = bd(((hash(0x0F1L, x, z, 6) & 3) == 0) ? "minecraft:basalt" : "minecraft:blackstone");
        for (int y = top - 2; y <= top; y++) cv.set(x, y, z, floor);
        if (N2.noise(x / 22.0, z / 22.0) > 0.55) { cv.set(x, top, z, bd("minecraft:lava")); cv.set(x, top - 1, z, bd("minecraft:blackstone")); }
        if (w < 0.85) return;
        // the upside-down land overhead, with holes
        if (N1.noise(x / 46.0 + 30, z / 46.0) > 0.5) return;
        int under = Terrain.SEA + 58 + (int) Math.round(5 * N1.noise(x / 70.0, z / 70.0)), thick = 16 + (int) Math.round(4 * N2.noise(x / 80.0, z / 80.0));
        cv.set(x, under, z, bd("minecraft:grass_block"));                                     // grass on its UNDERSIDE
        cv.column(x, z, under + 1, under + 3, bd("minecraft:dirt"));
        cv.column(x, z, under + 4, under + thick, bd("minecraft:stone"));
        long h = hash(0x6105EL, x, z, 7);
        if ((h & 63) == 0) cv.set(x, under, z, bd("minecraft:glowstone"));
        else if ((h & 63) == 1) cv.set(x, under - 1, z, bd("minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]"));
    }

    static void hangingTree(Canvas cv, int x, int z, long h) {
        if (N1.noise(x / 46.0 + 30, z / 46.0) > 0.45) return;                              // not under a hole
        int under = Terrain.SEA + 58 + (int) Math.round(5 * N1.noise(x / 70.0, z / 70.0));
        int len = 5 + (int) ((h >>> 20) % 5);
        String wood = ((h >>> 26) & 1) == 0 ? "oak" : "birch";
        for (int i = 1; i <= len; i++) cv.set(x, under - i, z, bd("minecraft:" + wood + "_log"));
        int c = under - len;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
            if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) > 4) continue;
            cv.air(x + dx, c + dy, z + dz, bd("minecraft:" + wood + "_leaves[persistent=true]"));
        }
    }

    // ---- 5. the debug fields
    static void debug(Canvas cv, int x, int z, int top) {
        cv.set(x, top, z, bd("minecraft:grass_block"));
        cv.column(x, z, top - 2, top - 1, bd("minecraft:dirt"));
        int px = Math.floorDiv(x, 64), pz = Math.floorDiv(z, 64);
        long ph = hash(0xDEB06L, px, pz, 8);
        if ((ph & 3) == 0) return;                                         // some fields are just superflat
        int lx = Math.floorMod(x, 64), lz = Math.floorMod(z, 64);
        if (lx < 4 || lz < 4 || lx > 59 || lz > 59 || (lx & 1) != 0 || (lz & 1) != 0) return;
        int i = (lx - 4) / 2 + ((lz - 4) / 2) * 28 + (int) ((ph >>> 8) % DEBUG_BLOCKS.length);
        cv.set(x, top + 3, z, DEBUG_BLOCKS[Math.floorMod(i, DEBUG_BLOCKS.length)]);
    }
}
