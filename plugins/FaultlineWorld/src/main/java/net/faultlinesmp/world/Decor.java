package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * The custom biomes' own look: ground, trees, rocks and underground dressing. Features sit on a jittered grid per
 * biome and are drawn whole into every chunk they touch (pure functions of their cell), so nothing is cut at chunk
 * edges. Vanilla biomes are left to vanilla.
 */
final class Decor {
    private Decor() {}

    interface Feature { void draw(Canvas cv, Terrain t, int x, int z, int base, long h); }

    static void paint(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData data) {
        Canvas cv = new Canvas(data, bx, bz);
        ground(t, bx, bz, cols, data);
        underground(t, bx, bz, cols, data);
        // surface features (biome, cell size, reach, salt, chance /256, feature)
        cells(cv, t, B.DARK_FOREST, 8, 11, 0xDA1, 230, Decor::darkTree);
        cells(cv, t, B.WARTSFIELD, 14, 11, 0xBA2, 170, Decor::umbrellaTree);
        cells(cv, t, B.DEEP_FOREST, 15, 12, 0xDE3, 230, Decor::giantTree);
        cells(cv, t, B.DEEP_FOREST, 46, 9, 0xDE4, 150, Decor::spire);
        cells(cv, t, B.INFERNO_FOREST, 7, 5, 0x1F5, 150, Decor::burntTree);
        cells(cv, t, B.INFERNO_FOREST, 60, 10, 0x1F6, 140, Decor::outcrop);
        cells(cv, t, B.SPIDER, 10, 8, 0x5D7, 200, Decor::spiderTree);
        cells(cv, t, B.WASTELAND, 17, 10, 0xA58, 170, Decor::shroom);
        cells(cv, t, B.DEEP_DESERT, 44, 8, 0xD59, 120, Decor::sandRock);
        cells(cv, t, B.GREAT_SEA, 40, 6, 0x6E5, 90, Decor::seaRock);
    }

    // ------------------------------------------------------------------ grid

    static void cells(Canvas cv, Terrain t, B biome, int size, int reach, int salt, int chance, Feature f) {
        int cx0 = Math.floorDiv(cv.bx - reach, size), cx1 = Math.floorDiv(cv.bx + 15 + reach, size);
        int cz0 = Math.floorDiv(cv.bz - reach, size), cz1 = Math.floorDiv(cv.bz + 15 + reach, size);
        for (int ci = cx0; ci <= cx1; ci++) for (int cj = cz0; cj <= cz1; cj++) {
            long h = hash(t.seed, ci, cj, salt);
            if ((h & 255) >= chance) continue;
            int x = ci * size + (int) ((h >>> 8) % size), z = cj * size + (int) ((h >>> 20) % size);
            if (x + reach < cv.bx || x - reach > cv.bx + 15 || z + reach < cv.bz || z - reach > cv.bz + 15) continue;
            Terrain.Col c = t.column(x, z);
            if (c.biome != biome || c.dry && biome != B.WASTELAND) continue;
            if (biome != B.GREAT_SEA && (c.sea || c.river)) continue;
            if (Sites.near(x, z, reach + 4)) continue;
            f.draw(cv, t, x, z, c.surface, h >>> 32);
        }
    }

    // ------------------------------------------------------------------ ground

    static void ground(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData d) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Terrain.Col c = cols[lx * 16 + lz];
            int x = bx + lx, z = bz + lz, y = c.surface;
            if (y < d.getMinHeight() + 2 || y >= d.getMaxHeight() - 2) continue;
            long h = hash(t.seed, x, z, 0x6A0);
            int r = (int) (h & 255);
            switch (c.biome) {
                case DEEP_DESERT -> {
                    for (int i = 0; i < 5; i++) d.setBlock(lx, y - i, lz, bd("minecraft:sand"));
                    for (int i = 5; i < 14; i++) d.setBlock(lx, y - i, lz, bd("minecraft:sandstone"));
                    if (r < 3) d.setBlock(lx, y + 1, lz, bd("minecraft:dead_bush"));
                    else if (r < 5) { d.setBlock(lx, y + 1, lz, bd("minecraft:cactus")); d.setBlock(lx, y + 2, lz, bd("minecraft:cactus")); }
                }
                case WARTSFIELD -> {
                    d.setBlock(lx, y, lz, bd("minecraft:crimson_nylium"));
                    for (int i = 1; i < 4; i++) d.setBlock(lx, y - i, lz, bd("minecraft:dirt"));
                    if (r < 150) d.setBlock(lx, y + 1, lz, bd("minecraft:crimson_roots"));
                    else if (r < 160) d.setBlock(lx, y + 1, lz, bd(r < 155 ? "minecraft:poppy" : "minecraft:red_tulip"));
                    else if (r < 162) { d.setBlock(lx, y + 1, lz, bd("minecraft:rose_bush[half=lower]")); d.setBlock(lx, y + 2, lz, bd("minecraft:rose_bush[half=upper]")); }
                }
                case INFERNO_FOREST -> {
                    double n = t.fbmPublic(x / 18.0, z / 18.0);
                    String top = n > 0.35 ? "minecraft:blackstone" : n > 0.1 ? "minecraft:soul_soil" : n > -0.2 ? "minecraft:coarse_dirt" : n > -0.45 ? "minecraft:tuff" : "minecraft:basalt";
                    d.setBlock(lx, y, lz, bd(r < 5 ? "minecraft:magma_block" : top));
                    for (int i = 1; i < 4; i++) d.setBlock(lx, y - i, lz, bd("minecraft:coarse_dirt"));
                    if (r > 245) d.setBlock(lx, y + 1, lz, bd("minecraft:dead_bush"));
                }
                case WASTELAND -> {
                    if (c.sea) { d.setBlock(lx, y, lz, bd(r < 128 ? "minecraft:mud" : "minecraft:green_terracotta")); break; }
                    double n = t.fbmPublic(x / 14.0 + 50, z / 14.0);
                    String top = n > 0.45 ? "minecraft:yellow_terracotta" : n > 0.15 ? "minecraft:green_terracotta" : n > -0.25 ? "minecraft:mud" : "minecraft:moss_block";
                    d.setBlock(lx, y, lz, bd(top));
                    for (int i = 1; i < 4; i++) d.setBlock(lx, y - i, lz, bd("minecraft:mud"));
                    if (r < 4) d.setBlock(lx, y + 1, lz, bd("minecraft:dead_bush"));
                    else if (r < 6) d.setBlock(lx, y, lz, bd("minecraft:ochre_froglight"));
                }
                case SPIDER -> {
                    String top = r < 90 ? "minecraft:podzol" : r < 170 ? "minecraft:coarse_dirt" : r < 200 ? "minecraft:gravel" : "minecraft:grass_block";
                    d.setBlock(lx, y, lz, bd(top));
                    for (int i = 1; i < 3; i++) d.setBlock(lx, y - i, lz, bd("minecraft:dirt"));
                    if (r > 248) d.setBlock(lx, y + 1, lz, bd("minecraft:cobweb"));
                    else if (r > 238) d.setBlock(lx, y + 1, lz, bd("minecraft:dead_bush"));
                }
                case DARK_FOREST -> {
                    if (r < 40) d.setBlock(lx, y, lz, bd("minecraft:moss_block"));
                    else if (r < 70) d.setBlock(lx, y, lz, bd("minecraft:podzol"));
                    if (r > 80 && r < 220) d.setBlock(lx, y + 1, lz, bd(r < 120 ? "minecraft:fern" : "minecraft:short_grass"));
                    else if (r >= 220 && r < 232) { d.setBlock(lx, y + 1, lz, bd("minecraft:tall_grass[half=lower]")); d.setBlock(lx, y + 2, lz, bd("minecraft:tall_grass[half=upper]")); }
                }
                case DEEP_FOREST -> {
                    if (r < 90) d.setBlock(lx, y, lz, bd("minecraft:podzol"));
                    else if (r < 120) d.setBlock(lx, y, lz, bd("minecraft:coarse_dirt"));
                    else if (r < 140) d.setBlock(lx, y, lz, bd("minecraft:moss_block"));
                    if (r > 150 && r < 200) d.setBlock(lx, y + 1, lz, bd("minecraft:fern"));
                    else if (r >= 200 && r < 206) { d.setBlock(lx, y + 1, lz, bd("minecraft:large_fern[half=lower]")); d.setBlock(lx, y + 2, lz, bd("minecraft:large_fern[half=upper]")); }
                }
                case GREAT_SEA -> {
                    if (c.hole) break;   // AUDIT: a hole is open water all the way down; the sand floor was drawn across it as a lid
                    double n = t.fbmPublic(x / 40.0, z / 40.0);
                    d.setBlock(lx, y, lz, bd(n > 0.3 ? "minecraft:gravel" : n > -0.35 ? "minecraft:sand" : "minecraft:clay"));
                    d.setBlock(lx, y - 1, lz, bd("minecraft:sand"));
                    if (r < 20 && y < 190) d.setBlock(lx, y + 1, lz, bd("minecraft:seagrass"));
                }
                case FARLANDS -> {
                    // stone faces between the grass tops
                    if ((h >>> 9 & 7) == 0) d.setBlock(lx, y, lz, bd("minecraft:stone"));
                }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ underground: Fibers, the Mire, spider caves

    static final String[] FIBER_ORES = {"minecraft:iron_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore",
            "minecraft:redstone_ore", "minecraft:lapis_ore", "minecraft:coal_ore", "minecraft:emerald_ore", "minecraft:diamond_ore"};

    static void underground(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData d) {
        Canvas cv = new Canvas(d, bx, bz);
        boolean any = false;
        for (Layout.Blob b : Layout.UNDERGROUND) if (cv.touches((int) (b.x() - b.r() * 1.3), (int) (b.z() - b.r() * 1.3), (int) (b.x() + b.r() * 1.3), (int) (b.z() + b.r() * 1.3))) any = true;
        boolean spider = false;
        for (Terrain.Col c : cols) if (c.biome == B.SPIDER) { spider = true; break; }
        if (!any && !spider) return;
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Terrain.Col c = cols[lx * 16 + lz];
            if (c.sea) continue;
            int x = bx + lx, z = bz + lz;
            int top = Math.min(c.surface - 10, 185);
            for (int y = -44; y < top; y++) {
                Material m = d.getType(lx, y, lz);
                if (!m.isAir()) continue;
                B u = t.underground(x, y, z, c);
                Material below = d.getType(lx, y - 1, lz), above = d.getType(lx, y + 1, lz);
                long h = hash3(t.seed, x, y, z, 0xF1B);
                int r = (int) (h & 1023);
                if (u == B.FIBERS) {
                    if (below.isSolid() && below != Material.MUD) {
                        d.setBlock(lx, y - 1, lz, bd(r < 600 ? "minecraft:moss_block" : r < 800 ? "minecraft:rooted_dirt" : "minecraft:mud"));
                        if (r < 90) d.setBlock(lx, y, lz, bd("minecraft:moss_carpet"));
                        else if (r < 110) d.setBlock(lx, y, lz, bd("minecraft:azalea"));
                        else if (r < 118) d.setBlock(lx, y, lz, bd("minecraft:flowering_azalea"));
                    } else if (above.isSolid()) {
                        if (r < 300) d.setBlock(lx, y, lz, bd("minecraft:hanging_roots"));
                        else if (r < 330) d.setBlock(lx, y + 1, lz, bd(FIBER_ORES[(int) ((h >>> 12) % FIBER_ORES.length)]));
                        else if (r < 360) d.setBlock(lx, y, lz, bd("minecraft:glow_lichen[up=true]"));
                        else if (r < 372) d.setBlock(lx, y, lz, bd("minecraft:spore_blossom"));
                    }
                    // the fibers: root strands hanging all the way down
                    if (((hash(t.seed, x, z, 0xF1C)) & 127) == 0 && above.isSolid()) {
                        for (int k = 0; k < 140; k++) {
                            int yy = y - k;
                            if (yy < -40 || !d.getType(lx, yy, lz).isAir()) break;
                            d.setBlock(lx, yy, lz, bd(k % 7 == 6 ? "minecraft:mangrove_roots" : (k & 1) == 0 ? "minecraft:pale_hanging_moss" : "minecraft:mangrove_roots"));
                        }
                    }
                } else if (u == B.MIRE) {
                    if (below.isSolid()) {
                        d.setBlock(lx, y - 1, lz, bd(r < 700 ? "minecraft:mud" : "minecraft:muddy_mangrove_roots"));
                        if (r < 40) d.setBlock(lx, y, lz, bd("minecraft:brown_mushroom"));
                        else if (r < 52) d.setBlock(lx, y - 1, lz, bd("minecraft:verdant_froglight"));
                        else if (r < 64) d.setBlock(lx, y, lz, bd("minecraft:moss_carpet"));
                    } else if (above.isSolid() && r < 200) d.setBlock(lx, y, lz, bd(r < 30 ? "minecraft:glow_lichen[up=true]" : "minecraft:hanging_roots"));
                } else if (u == B.SPIDER_CAVES || (c.biome == B.SPIDER && y > 60)) {
                    if (r < 60) d.setBlock(lx, y, lz, bd("minecraft:cobweb"));
                }
            }
        }
        // Mire trees (ores in their trunks) and abandoned huts
        mireTrees(cv, t);
    }

    static final String[] TRUNK_ORES = {"minecraft:iron_ore", "minecraft:iron_ore", "minecraft:iron_ore", "minecraft:gold_ore",
            "minecraft:coal_ore", "minecraft:coal_ore", "minecraft:copper_ore", "minecraft:diamond_ore"};

    static void mireTrees(Canvas cv, Terrain t) {
        int size = 22, reach = 9;
        int cx0 = Math.floorDiv(cv.bx - reach, size), cx1 = Math.floorDiv(cv.bx + 15 + reach, size);
        int cz0 = Math.floorDiv(cv.bz - reach, size), cz1 = Math.floorDiv(cv.bz + 15 + reach, size);
        for (int ci = cx0; ci <= cx1; ci++) for (int cj = cz0; cj <= cz1; cj++) {
            long h = hash(t.seed, ci, cj, 0x313E);
            if ((h & 255) > 170) continue;
            int x = ci * size + (int) ((h >>> 8) % size), z = cj * size + (int) ((h >>> 20) % size);
            if (!cv.touches(x - reach, z - reach, x + reach, z + reach)) continue;
            Terrain.Col c = t.column(x, z);
            int base = -28 + (int) ((h >>> 30) % 10);
            if (t.underground(x, base, z, c) != B.MIRE) continue;
            int height = 22 + (int) ((h >>> 36) % 18);
            boolean hut = ((h >>> 44) & 7) == 0;
            if (hut) { hut(cv, x, base, z, h); continue; }
            // trunk: mangrove log 2x2 with ores in it, roots round the foot, a glowing canopy up top
            for (int y = base - 2; y <= base + height; y++) {
                for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                    long hh = hash3(t.seed, x + dx, y, z + dz, 0x313F);
                    String b = (hh & 7) < 3 ? TRUNK_ORES[(int) ((hh >>> 5) % TRUNK_ORES.length)] : "minecraft:mangrove_log";
                    cv.set(x + dx, y, z + dz, bd(b));
                }
            }
            for (int k = 0; k < 4; k++) {
                int dx = k < 2 ? -1 : 2, dz = (k & 1) == 0 ? -1 : 2;
                for (int y = base - 2; y < base + 3; y++) cv.air(x + dx, y, z + dz, bd("minecraft:muddy_mangrove_roots"));
            }
            int top = base + height;
            for (int dx = -7; dx <= 8; dx++) for (int dz = -7; dz <= 8; dz++) for (int dy = -2; dy <= 3; dy++) {
                double d = Math.hypot(dx - 0.5, dz - 0.5) / 7.5 + Math.abs(dy - 0.5) / 3.5;
                if (d > 1) continue;
                long hh = hash3(t.seed, x + dx, top + dy, z + dz, 0x3140);
                String b = (hh & 31) == 0 ? "minecraft:ochre_froglight" : (hh & 31) == 1 ? "minecraft:pearlescent_froglight" : "minecraft:mangrove_leaves[persistent=true]";
                cv.air(x + dx, top + dy, z + dz, bd(b));
            }
        }
    }

    /** An abandoned hut (the Mire / the Deep Forest): planks and cobble, a hole in the roof, a chest, webs. */
    static void hut(Canvas cv, int x, int base, int z, long h) {
        int w = 7, l = 6;
        String wall = ((h >>> 3) & 1) == 0 ? "minecraft:spruce_planks" : "minecraft:mossy_cobblestone";
        for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < l; dz++) {
            cv.set(x + dx, base - 1, z + dz, bd("minecraft:cobblestone"));
            boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == l - 1;
            for (int dy = 0; dy < 5; dy++) {
                boolean broken = ((hash3(h, x + dx, dy, z + dz, 7) & 7) == 0);
                if (dy == 4) cv.set(x + dx, base + dy, z + dz, broken ? WorldGen.Blocks.CAVE_AIR : bd("minecraft:spruce_slab"));
                else if (edge) cv.set(x + dx, base + dy, z + dz, broken && dy > 1 ? WorldGen.Blocks.CAVE_AIR : bd(dx == 0 && dz == 0 || dx == w - 1 && dz == l - 1 || dx == 0 && dz == l - 1 || dx == w - 1 && dz == 0 ? "minecraft:stripped_spruce_log" : wall));
                else cv.set(x + dx, base + dy, z + dz, ((hash3(h, x + dx, dy, z + dz, 9) & 15) == 0) ? bd("minecraft:cobweb") : WorldGen.Blocks.CAVE_AIR);
            }
        }
        cv.set(x + 3, base, z, WorldGen.Blocks.CAVE_AIR); cv.set(x + 3, base + 1, z, WorldGen.Blocks.CAVE_AIR); // the doorway
        cv.set(x + 1, base, z + l - 2, bd("minecraft:chest[facing=north]"));
        cv.set(x + w - 2, base, z + l - 2, bd("minecraft:crafting_table"));
        cv.set(x + w - 2, base + 1, z + 1, bd("minecraft:cobweb"));
    }

    // ------------------------------------------------------------------ trees and rocks

    static final String[] DARK_LEAVES = {"minecraft:dark_oak_leaves[persistent=true]", "minecraft:dark_oak_leaves[persistent=true]",
            "minecraft:azalea_leaves[persistent=true]", "minecraft:dark_oak_leaves[persistent=true]", "minecraft:flowering_azalea_leaves[persistent=true]",
            "minecraft:spruce_leaves[persistent=true]"};

    /** The Dark Forest: tall straight dark trunks (20-32), a high thin canopy of dark / blue-green / purple-flecked leaves. */
    static void darkTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 20 + (int) (h % 13);
        boolean fat = (h >>> 5 & 3) != 0;
        String log = (h >>> 8 & 3) == 0 ? "minecraft:spruce_log" : "minecraft:dark_oak_log";
        int s = fat ? 2 : 1;
        for (int y = base - 1; y <= base + H; y++) for (int dx = 0; dx < s; dx++) for (int dz = 0; dz < s; dz++) cv.set(x + dx, y, z + dz, bd(log));
        if (fat) for (int k = 0; k < 4; k++) { // root flare
            int dx = (k & 1) == 0 ? -1 : 2, dz = k < 2 ? 0 : 1;
            for (int y = base; y < base + 1 + (int) ((h >>> (10 + k)) & 1); y++) cv.soft(x + dx, y, z + dz, bd("minecraft:dark_oak_wood"));
        }
        int top = base + H;
        int R = 6 + (int) ((h >>> 14) % 3);
        for (int dx = -R; dx <= R + s - 1; dx++) for (int dz = -R; dz <= R + s - 1; dz++) for (int dy = -3; dy <= 2; dy++) {
            double d = Math.hypot(dx - (s - 1) / 2.0, dz - (s - 1) / 2.0) / R;
            double lim = dy >= 1 ? 0.55 : dy == 0 ? 0.95 : dy == -1 ? 1.0 : dy == -2 ? 0.8 : 0.45;
            if (d > lim) continue;
            long hh = hash3(t.seed, x + dx, top + dy, z + dz, 0xDA2);
            if ((hh & 7) == 0 && d > 0.7) continue;                       // ragged edge
            cv.air(x + dx, top + dy, z + dz, bd(DARK_LEAVES[(int) ((hh >>> 4) % DARK_LEAVES.length)]));
        }
        // a couple of side branches under the canopy
        for (int b = 0; b < 2; b++) {
            double a = ((h >>> (20 + b * 5)) & 31) / 31.0 * Math.PI * 2;
            for (int i = 1; i < 5; i++) cv.soft(x + (int) Math.round(Math.cos(a) * i), top - 4 - b * 3 + i / 2, z + (int) Math.round(Math.sin(a) * i), bd(log.replace("_log", "_wood")));
        }
    }

    /** Wartsfield Valley: white/brown banded trunks that twist up to wide flat umbrella canopies of pale leaves. */
    static void umbrellaTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 13 + (int) (h % 9);
        double ph = (h >>> 6 & 63) / 10.0, lean = 0.12 + (h >>> 12 & 7) / 40.0;
        int px = x, pz = z;
        for (int i = 0; i <= H; i++) {
            px = x + (int) Math.round(Math.sin(ph + i * 0.35) * lean * i);
            pz = z + (int) Math.round(Math.cos(ph + i * 0.27) * lean * i);
            String b = (i / 2) % 3 == 0 ? "minecraft:mushroom_stem" : (i / 2) % 3 == 1 ? "minecraft:birch_wood" : "minecraft:stripped_birch_wood";
            cv.set(px, base + i, pz, bd(b));
            cv.set(px + 1, base + i, pz, bd(b));
        }
        int top = base + H + 1;
        int R = 6 + (int) ((h >>> 18) % 4);
        for (int tier = 0; tier < (((h >>> 22) & 1) == 0 ? 1 : 2); tier++) {
            int ty = top - tier * 5, rr = R - tier * 2;
            for (int dx = -rr; dx <= rr + 1; dx++) for (int dz = -rr; dz <= rr; dz++) {
                double d = Math.hypot(dx - 0.5, dz) / rr;
                if (d > 1) continue;
                long hh = hash3(t.seed, px + dx, ty, pz + dz, 0xBA3);
                String lv = (hh & 3) == 0 ? "minecraft:birch_leaves[persistent=true]" : "minecraft:pale_oak_leaves[persistent=true]";
                cv.air(px + dx, ty, pz + dz, bd(lv));
                if (d < 0.75) cv.air(px + dx, ty + 1, pz + dz, bd(lv));
                if (d < 0.35) cv.air(px + dx, ty + 2, pz + dz, bd(lv));
                if (d > 0.85 && (hh >>> 3 & 3) == 0) cv.air(px + dx, ty - 1, pz + dz, bd(lv));
            }
        }
    }

    /** The Deep Forest: huge 3x3 trunks 38-60 tall with branch clusters and a spire top. */
    static void giantTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = Math.min(38 + (int) (h % 23), 312 - base);
        if (H < 20) return;
        String log = (h >>> 5 & 1) == 0 ? "minecraft:spruce_log" : "minecraft:oak_log";
        String leaves = log.contains("spruce") ? "minecraft:spruce_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]";
        for (int y = base - 2; y <= base + H; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (y > base + H - 8 && Math.abs(dx) + Math.abs(dz) == 2) continue;      // narrower near the top
            cv.set(x + dx, y, z + dz, bd(log));
        }
        for (int k = 0; k < 4; k++) for (int y = base; y < base + 3; y++) cv.soft(x + (k < 2 ? (k == 0 ? -2 : 2) : 0), y, z + (k >= 2 ? (k == 2 ? -2 : 2) : 0), bd(log.replace("_log", "_wood")));
        for (int y = base + 14; y < base + H; y += 6) {
            double a = (hash(t.seed, x, y, 0xDE5) & 255) / 255.0 * Math.PI * 2;
            int len = 4 + (int) ((base + H - y) / 6);
            int ex = x, ez = z;
            for (int i = 2; i <= len; i++) { ex = x + (int) Math.round(Math.cos(a) * i); ez = z + (int) Math.round(Math.sin(a) * i); cv.soft(ex, y + i / 3, ez, bd(log.replace("_log", "_wood"))); }
            blobLeaves(cv, t, ex, y + len / 3 + 1, ez, 3.2, leaves);
        }
        for (int dy = 0; dy < 10; dy++) { // the crown: a cone
            double r = 5 - dy * 0.5;
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) if (Math.hypot(dx, dz) <= r) cv.air(x + dx, base + H - 4 + dy, z + dz, bd(leaves));
        }
    }

    static void blobLeaves(Canvas cv, Terrain t, int x, int y, int z, double r, String leaves) {
        int R = (int) Math.ceil(r);
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) for (int dy = -R / 2; dy <= R / 2 + 1; dy++) {
            if (Math.sqrt(dx * dx + dz * dz + dy * dy * 2.5) > r) continue;
            cv.air(x + dx, y + dy, z + dz, bd(leaves));
        }
    }

    /** The Deep Forest's tall rocks: tapering needles of stone / tuff / andesite with moss. */
    static void spire(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = Math.min(26 + (int) (h % 46), 314 - base);
        double r0 = 3.5 + (h >>> 8 & 3);
        String[] band = {"minecraft:stone", "minecraft:andesite", "minecraft:tuff", "minecraft:stone", "minecraft:cobblestone"};
        int R = (int) Math.ceil(r0) + 1;
        for (int dy = -4; dy <= H; dy++) {
            double r = r0 * (1 - Math.max(0, dy) / (double) (H + 2)) + 0.6;
            double ox = Math.sin(dy / 9.0 + h) * 1.2, oz = Math.cos(dy / 11.0 + h) * 1.2;
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                if (Math.hypot(dx - ox, dz - oz) > r) continue;
                long hh = hash3(t.seed, x + dx, base + dy, z + dz, 0xDE6);
                String b = (hh & 15) == 0 ? "minecraft:mossy_cobblestone" : band[(base + dy) / 3 % band.length];
                cv.set(x + dx, base + dy, z + dz, bd(b));
            }
        }
    }

    /** The Inferno Forest: charred trunks with bare branches. */
    static void burntTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 6 + (int) (h % 9);
        String trunk = (h >>> 4 & 3) == 0 ? "minecraft:blackstone" : "minecraft:stripped_dark_oak_log";
        for (int y = base; y <= base + H; y++) cv.set(x, y, z, bd(trunk));
        if ((h >>> 6 & 3) == 0) cv.set(x, base + H + 1, z, bd("minecraft:coal_block"));
        for (int b = 0; b < 3; b++) {
            double a = ((h >>> (10 + b * 6)) & 63) / 63.0 * Math.PI * 2;
            int by = base + H - 2 - b * 2;
            for (int i = 1; i <= 2 + (int) ((h >>> (30 + b)) & 1); i++)
                cv.soft(x + (int) Math.round(Math.cos(a) * i), by + i / 2, z + (int) Math.round(Math.sin(a) * i), bd("minecraft:dark_oak_fence"));
        }
    }

    /** The Inferno Forest's rocky places: a rough basalt mound, sometimes opening into a cave going down. */
    static void outcrop(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int R = 6 + (int) (h % 4), H = 7 + (int) ((h >>> 4) % 8);
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double d = Math.hypot(dx, dz) / R;
            if (d > 1) continue;
            int top = base + (int) Math.round(H * (1 - d * d)) + (int) (hash(t.seed, x + dx, z + dz, 0x1F7) & 1);
            long hh = hash(t.seed, x + dx, z + dz, 0x1F8);
            cv.column(x + dx, z + dz, base - 2, top, bd((hh & 3) == 0 ? "minecraft:blackstone" : (hh & 3) == 1 ? "minecraft:tuff" : "minecraft:basalt"));
        }
        if (((h >>> 10) & 1) == 0) { // a cave mouth going down to the caves below
            for (int k = 0; k < 40; k++) {
                int yy = base + 2 - k;
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                    if (dx * dx + dz * dz <= 5) cv.set(x + dx + k / 4, yy, z + dz, WorldGen.Blocks.CAVE_AIR);
            }
        }
        if (((h >>> 12) & 3) == 0) { // a campsite beside it
            int cx = x + R + 4, cz = z;
            cv.set(cx, base + 1, cz, bd("minecraft:campfire[lit=true]"));
            cv.set(cx + 2, base + 1, cz + 1, bd("minecraft:barrel[facing=up]"));
            cv.set(cx - 2, base + 1, cz - 1, bd("minecraft:spruce_log[axis=x]"));
            cv.set(cx - 1, base + 1, cz - 1, bd("minecraft:spruce_log[axis=x]"));
            cv.set(cx + 1, base + 1, cz - 2, bd("minecraft:brown_bed[part=foot,facing=south]"));
            cv.set(cx + 1, base + 1, cz - 1, bd("minecraft:brown_bed[part=head,facing=south]"));
        }
    }

    /** The spider woods: dead twisted trees wrapped in cobwebs, a web curtain and an egg sac. */
    static void spiderTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 8 + (int) (h % 7);
        for (int y = base; y <= base + H; y++) cv.set(x, y, z, bd("minecraft:stripped_oak_log"));
        for (int b = 0; b < 4; b++) {
            double a = ((h >>> (6 + b * 5)) & 31) / 31.0 * Math.PI * 2;
            int by = base + H - b * 2;
            int ex = x, ez = z, ey = by;
            for (int i = 1; i <= 3; i++) {
                ex = x + (int) Math.round(Math.cos(a) * i); ez = z + (int) Math.round(Math.sin(a) * i); ey = by + i / 2;
                cv.soft(ex, ey, ez, bd("minecraft:oak_fence"));
            }
            for (int k = 1; k <= 1 + (int) ((h >>> (30 + b)) & 3); k++) cv.air(ex, ey - k, ez, bd("minecraft:cobweb"));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (((hash3(t.seed, ex + dx, ey, ez + dz, 0x5D8)) & 3) == 0) cv.air(ex + dx, ey + 1, ez + dz, bd("minecraft:cobweb"));
        }
        if ((h >>> 40 & 3) == 0) { // egg sac
            cv.air(x + 1, base + 3, z, bd("minecraft:white_wool"));
            cv.air(x + 1, base + 2, z, bd("minecraft:mushroom_stem"));
            cv.air(x + 1, base + 4, z, bd("minecraft:cobweb"));
        }
    }

    /** The Wasteland: sulphur mushrooms (yellow / lime caps, glowing rims) on pale stems. */
    static void shroom(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 8 + (int) (h % 11);
        int R = 3 + (int) ((h >>> 5) % 5);
        boolean yellow = (h >>> 9 & 1) == 0;
        for (int y = base; y <= base + H; y++) { cv.set(x, y, z, bd("minecraft:mushroom_stem")); if (y < base + 2) for (int k = 0; k < 4; k++) cv.soft(x + (k == 0 ? 1 : k == 1 ? -1 : 0), y, z + (k == 2 ? 1 : k == 3 ? -1 : 0), bd("minecraft:mushroom_stem")); }
        int top = base + H;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) for (int dy = -1; dy <= R / 2 + 1; dy++) {
            double d = Math.hypot(dx, dz) / R;
            double cap = (1 - d * d) * (R / 2.0 + 1);
            if (d > 1 || dy > cap || dy < cap - 1.6) continue;
            String b = d > 0.85 ? "minecraft:verdant_froglight" : (hash3(t.seed, x + dx, top + dy, z + dz, 0xA59) & 7) == 0 ? "minecraft:bone_block" : yellow ? "minecraft:yellow_terracotta" : "minecraft:lime_terracotta";
            cv.air(x + dx, top + dy, z + dz, bd(b));
        }
        for (int dx = -R + 1; dx <= R - 1; dx++) for (int dz = -R + 1; dz <= R - 1; dz++) // gills underneath
            if (Math.hypot(dx, dz) < R - 1 && (dx + dz & 1) == 0) cv.air(x + dx, top - 1, z + dz, bd("minecraft:ochre_froglight"));
    }

    static void sandRock(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int R = 4 + (int) (h % 5), H = 6 + (int) ((h >>> 4) % 14);
        boolean arch = ((h >>> 9) & 3) == 0;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double d = Math.hypot(dx, dz) / R;
            if (d > 1) continue;
            int top = base + (int) Math.round(H * (1 - d * 0.4));
            for (int y = base - 3; y <= top; y++) {
                if (arch && Math.abs(dz) <= 1 && y < base + H - 3 && y > base) continue;
                cv.set(x + dx, y, z + dz, bd((y / 3 & 1) == 0 ? "minecraft:sandstone" : "minecraft:smooth_sandstone"));
            }
            cv.set(x + dx, top, z + dz, bd("minecraft:sand"));
        }
    }

    static void seaRock(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int R = 3 + (int) (h % 4), H = 5 + (int) ((h >>> 4) % 12);
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double d = Math.hypot(dx, dz) / R;
            if (d > 1) continue;
            int top = base + (int) Math.round(H * (1 - d * d));
            for (int y = base - 2; y <= Math.min(top, Layout.SEA - 3); y++)
                cv.set(x + dx, y, z + dz, bd(((hash3(t.seed, x + dx, y, z + dz, 0x6E6)) & 7) == 0 ? "minecraft:prismarine" : "minecraft:stone"));
        }
        for (int k = 0; k < 12; k++) if (cv.type(x + R + 1, base + 1 + k, z) == Material.WATER) cv.set(x + R + 1, base + 1 + k, z, bd(k == 11 ? "minecraft:kelp" : "minecraft:kelp_plant"));
    }
}
