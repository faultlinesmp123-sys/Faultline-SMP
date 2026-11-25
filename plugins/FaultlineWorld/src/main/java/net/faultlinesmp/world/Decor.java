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
        cells(cv, t, B.JUNGLE, 36, 12, 0x7A1, 150, Decor::kapok);
        cells(cv, t, B.BAMBOO_JUNGLE, 48, 12, 0x7A1, 110, Decor::kapok);
        for (B j : new B[]{B.JUNGLE, B.BAMBOO_JUNGLE}) {
            cells(cv, t, j, 58, 6, 0x7A2, 110, Decor::idol);
            cells(cv, t, j, 44, 7, 0x7A3, 120, Decor::ruinArch);
            cells(cv, t, j, 19, 5, 0x7A4, 130, Decor::mossyLog);
            cells(cv, t, j, 15, 4, 0x7A5, 140, Decor::jungleBush);
        }
        for (B m : MOUNTAINS) {
            cells(cv, t, m, 13, 5, 0x3A1, 150, Decor::boulder);
            cells(cv, t, m, 44, 6, 0x3A2, 130, Decor::rockSpire);
            cells(cv, t, m, 27, 4, 0x3A3, 120, Decor::oreOutcrop);
            cells(cv, t, m, 120, 6, 0x3A5, 90, Decor::watchtower);
        }
        cells(cv, t, B.WINDSWEPT_HILLS, 9, 5, 0x3A4, 150, Decor::alpinePine);
        cells(cv, t, B.SNOWY_SLOPES, 12, 5, 0x3A4, 110, Decor::alpinePine);
        cavernFloors(cv, t);
        roads(cv, t, cols);
        cells(cv, t, B.SPIDER, 11, 12, 0x5D7, 185, Decor::spiderTree);
        cells(cv, t, B.SPIDER, 38, 10, 0x5DA, 120, Decor::greatWeb);
        cells(cv, t, B.SPIDER, 64, 7, 0x5DB, 110, Decor::nestMound);
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
            if (c.biome != biome || c.dry && Terrain.inCanyon(x, z)) continue;
            if (biome != B.GREAT_SEA && (c.sea || c.river)) continue;
            if (onRoad(x, z, reach)) continue;                                   // nothing grows on a faction road
            if (c.hole) continue;        // AUDIT: a hole has no floor: rocks drawn at its "floor" height hung in open water
            if (Sites.near(x, z, reach + 4)) continue;
            boolean city = false;                                                // round 7: trees stood 40 blocks over the flattened cities
            for (int[] cc : Structures.cities()) if (Layout.dist(x, z, cc[0], cc[1]) < Structures.CITY_R + reach + 6) city = true;
            if (city) continue;
            f.draw(cv, t, x, z, c.surface, h >>> 32);
        }
    }

    // ------------------------------------------------------------------ ground

    static void ground(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData d) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            Terrain.Col c = cols[lx * 16 + lz];
            int x = bx + lx, z = bz + lz, y = c.surface;
            if (y < d.getMinHeight() + 2 || y >= d.getMaxHeight() - 2) continue;
            if (Sites.near(x, z, 0)) continue;     // round 7: ferns grew under Himeji's moat water (the build is drawn after)
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
                    // grey, dead ground: pale moss in drifts, podzol and coarse dirt between, silk and bones lying about
                    double n = t.fbmPublic(x / 22.0 + 300, z / 22.0);
                    String top = n > 0.28 ? "minecraft:pale_moss_block" : r < 80 ? "minecraft:podzol" : r < 165 ? "minecraft:coarse_dirt" : r < 195 ? "minecraft:gravel" : r < 215 ? "minecraft:rooted_dirt" : "minecraft:grass_block";
                    d.setBlock(lx, y, lz, bd(top));
                    for (int i = 1; i < 3; i++) d.setBlock(lx, y - i, lz, bd("minecraft:dirt"));
                    int q = (int) (h >>> 12 & 1023);
                    if (q < 14) d.setBlock(lx, y + 1, lz, bd("minecraft:cobweb"));
                    else if (q < 34) d.setBlock(lx, y + 1, lz, bd("minecraft:dead_bush"));
                    else if (q < 90 && n > 0.2) d.setBlock(lx, y + 1, lz, bd("minecraft:pale_moss_carpet"));
                    else if (q < 96) d.setBlock(lx, y + 1, lz, bd("minecraft:white_carpet"));           // a sheet of silk
                    else if (q < 99) d.setBlock(lx, y, lz, bd("minecraft:bone_block"));
                    else if (q < 101) d.setBlock(lx, y + 1, lz, bd("minecraft:skeleton_skull[rotation=" + (q * 7 & 15) + "]"));
                    else if (q < 104) { d.setBlock(lx, y + 1, lz, bd("minecraft:white_wool")); d.setBlock(lx, y + 2, lz, bd("minecraft:cobweb")); }   // a small cocoon
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
                case JUNGLE, BAMBOO_JUNGLE -> {
                    // the jungle floor: leaf litter, moss, mud in the dips, ferns, melons and flowers under the trees
                    if (c.sea || c.river) break;   // round 7: the whole jungle is inside Himeji's dry zone: it used to stay bare
                    double n = t.fbmPublic(x / 19.0 + 500, z / 19.0);
                    int q = (int) (h >>> 12 & 1023);
                    if (n > 0.3) d.setBlock(lx, y, lz, bd("minecraft:moss_block"));
                    else if (n < -0.4) d.setBlock(lx, y, lz, bd(q < 500 ? "minecraft:mud" : "minecraft:podzol"));
                    else if (r < 40) d.setBlock(lx, y, lz, bd("minecraft:podzol"));
                    else if (r < 60) d.setBlock(lx, y, lz, bd("minecraft:rooted_dirt"));
                    if (!d.getType(lx, y + 1, lz).isAir()) break;
                    if (q < 130) d.setBlock(lx, y + 1, lz, bd("minecraft:fern"));
                    else if (q < 170 && d.getType(lx, y + 2, lz).isAir()) { d.setBlock(lx, y + 1, lz, bd("minecraft:large_fern[half=lower]")); d.setBlock(lx, y + 2, lz, bd("minecraft:large_fern[half=upper]")); }
                    else if (q < 210 && n > 0.2) d.setBlock(lx, y + 1, lz, bd("minecraft:moss_carpet"));
                    else if (q < 216) d.setBlock(lx, y + 1, lz, bd("minecraft:melon"));
                    else if (q < 232) d.setBlock(lx, y + 1, lz, bd(q < 220 ? "minecraft:blue_orchid" : q < 224 ? "minecraft:allium" : q < 228 ? "minecraft:red_tulip" : "minecraft:orange_tulip"));
                    else if (q < 240) d.setBlock(lx, y + 1, lz, bd("minecraft:pink_petals[flower_amount=" + (1 + (q & 3)) + "]"));
                    else if (q < 244) d.setBlock(lx, y + 1, lz, bd("minecraft:mossy_cobblestone"));
                }
                case JAGGED_PEAKS, STONY_PEAKS, WINDSWEPT_HILLS, FROZEN_PEAKS, SNOWY_SLOPES -> mountain(t, bx, bz, cols, d, lx, lz, c, h);
                case FARLANDS -> {
                    // stone faces between the grass tops
                    if ((h >>> 9 & 7) == 0) d.setBlock(lx, y, lz, bd("minecraft:stone"));
                }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ faction roads

    static boolean onRoad(int x, int z, int pad) {
        for (Layout.Road r : Layout.ROADS) {
            if (x < Math.min(r.x0(), r.x1()) - 4 - pad || x > Math.max(r.x0(), r.x1()) + 4 + pad || z < Math.min(r.z0(), r.z1()) - 4 - pad || z > Math.max(r.z0(), r.z1()) + 4 + pad) continue;
            if (Layout.segDist(x, z, r.x0(), r.z0(), r.x1(), r.z1(), null) < 3 + Math.min(pad, 4)) return true;
        }
        return false;
    }

    static String[] paving(String faction) {
        return switch (faction) {
            case "mountain" -> new String[]{"minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:gravel", "minecraft:cracked_stone_bricks", "minecraft:cobblestone_wall"};
            case "jungle" -> new String[]{"minecraft:mossy_cobblestone", "minecraft:dirt_path", "minecraft:mossy_stone_bricks", "minecraft:coarse_dirt", "minecraft:mossy_cobblestone_wall"};
            case "ice" -> new String[]{"minecraft:polished_andesite", "minecraft:andesite", "minecraft:stone_bricks", "minecraft:gravel", "minecraft:andesite_wall"};
            case "sky" -> new String[]{"minecraft:smooth_quartz", "minecraft:quartz_bricks", "minecraft:calcite", "minecraft:diorite", "minecraft:diorite_wall"};
            case "fibers" -> new String[]{"minecraft:mud_bricks", "minecraft:packed_mud", "minecraft:mud_bricks", "minecraft:rooted_dirt", "minecraft:mud_brick_wall"};
            default -> new String[]{"minecraft:dirt_path", "minecraft:gravel", "minecraft:coarse_dirt", "minecraft:cobblestone", "minecraft:cobblestone_wall"};
        };
    }

    /**
     * The faction roads (Layout.ROADS): a paved way 3 wide joining each faction's builds, statue and shrine, in that
     * faction's own stone, with a lamp post every 14 blocks, a plank bridge wherever it crosses water, and anything growing
     * on it cleared. The ground under it was evened out by Terrain, so it is a walkable slope.
     */
    static void roads(Canvas cv, Terrain t, Terrain.Col[] cols) {
        for (Layout.Road r : Layout.ROADS) {
            if (!cv.touches(Math.min(r.x0(), r.x1()) - 4, Math.min(r.z0(), r.z1()) - 4, Math.max(r.x0(), r.x1()) + 4, Math.max(r.z0(), r.z1()) + 4)) continue;
            String[] pv = paving(r.faction());
            double len = Math.hypot(r.x1() - r.x0(), r.z1() - r.z0());
            double[] sp = new double[1];
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = cv.bx + lx, z = cv.bz + lz;
                double d = Layout.segDist(x, z, r.x0(), r.z0(), r.x1(), r.z1(), sp);
                if (d > 3.2) continue;
                Terrain.Col c = cols[lx * 16 + lz];
                if (c.farlands > 0.5) continue;
                boolean water = (c.sea || c.river) && !c.dry;
                int y = water ? Layout.SEA : c.surface;
                long h = hash(t.seed, x, z, 0x70AD);
                if (d <= 1.7) {
                    int q = (int) (h & 15);
                    cv.set(x, y, z, bd(water ? "minecraft:spruce_planks" : pv[q < 8 ? 0 : q < 12 ? 1 : q < 14 ? 2 : 3]));
                    for (int k = 1; k <= 4; k++) { Material m = cv.type(x, y + k, z); if (!m.isAir() && (!m.isSolid() || m.name().endsWith("_LEAVES") || m.name().endsWith("_LOG") || m == Material.COBWEB || m == Material.SNOW)) cv.set(x, y + k, z, WorldGen.Blocks.AIR); }
                } else if (d > 2.2 && d <= 3.2 && !water) {
                    int along = (int) Math.round(sp[0] * len);
                    if (along % 14 == 0 && (h >>> 8 & 1) == 0 && cv.air(x, y + 1, z)) {
                        cv.set(x, y + 1, z, bd(pv[4])); cv.set(x, y + 2, z, bd(pv[4])); cv.set(x, y + 3, z, bd("minecraft:lantern"));
                    }
                } else if (water && d <= 2.6) {
                    cv.set(x, y + 1, z, bd("minecraft:spruce_slab"));                       // the bridge's kerb
                    cv.set(x, y, z, bd("minecraft:spruce_planks"));
                }
            }
        }
    }

    // ------------------------------------------------------------------ the jungle

    /**
     * A kapok: the jungle's giant. A 3 x 3 trunk 30-50 tall on buttress roots, three wide flat tiers of canopy, lianas
     * hanging from every tier (some with glow berries), and now and then an abandoned hut high in the branches.
     */
    static void kapok(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = Math.min(30 + (int) (h >>> 4 & 15) + (int) (h >>> 8 & 7), 300 - base);
        if (H < 24) return;
        String log = "minecraft:jungle_log[axis=y]", wood = "minecraft:jungle_wood", lv = "minecraft:jungle_leaves[persistent=true]";
        for (int y = base - 2; y <= base + H; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (y > base + H * 0.6 && Math.abs(dx) + Math.abs(dz) == 2) continue;                // slimmer higher up
            if (y > base + H - 5 && (dx != 0 || dz != 0)) continue;
            cv.set(x + dx, y, z + dz, bd(log));
        }
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {                           // buttress roots
            int rl = 3 + (int) (hash(t.seed, x + o[0], z + o[1], 0x7A6) & 3);
            for (int i = 2; i <= 1 + rl; i++) {
                int rx = x + o[0] * i, rz = z + o[1] * i, g = t.column(rx, rz).surface;
                for (int y = g; y <= Math.max(g, base + (rl + 2 - i) * 2); y++) cv.soft(rx, y, rz, bd(wood + "[axis=y]"));
            }
        }
        for (int tier = 0; tier < 3; tier++) {
            int ty = base + H - tier * 8 - 1, R = 6 + tier * 2 + (int) (h >>> (20 + tier) & 1);
            if (tier > 0) for (int b = 0; b < 4; b++) {                                          // boughs out to the lower tiers
                double a = b * Math.PI / 2 + tier * 0.7 + (h >>> 30 & 3) * 0.2;
                branch(cv, wood, x, ty - 2, z, x + (int) Math.round(Math.cos(a) * (R - 2)), ty, z + (int) Math.round(Math.sin(a) * (R - 2)));
            }
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                double dd = Math.hypot(dx, dz) / R + ((hash(t.seed, x + dx + tier * 31, z + dz, 0x7A7) & 7) - 3.5) * 0.03;
                if (dd > 1) continue;
                cv.air(x + dx, ty, z + dz, bd(lv));
                if (dd < 0.75) cv.air(x + dx, ty + 1, z + dz, bd(lv));
                if (dd < 0.4) cv.air(x + dx, ty + 2, z + dz, bd(lv));
                long hh = hash3(t.seed, x + dx, ty, z + dz, 0x7A8);
                if (dd > 0.35 && (hh & 15) == 0) {                                               // a liana
                    int ll = 3 + (int) (hh >>> 4 & 15);
                    for (int k = 1; k <= ll; k++) {
                        if (!cv.air(x + dx, ty - k, z + dz)) break;
                        boolean tip = k == ll || !cv.air(x + dx, ty - k - 1, z + dz);
                        boolean berry = (hh >>> (8 + k) & 7) == 0;
                        cv.set(x + dx, ty - k, z + dz, bd(tip ? "minecraft:cave_vines[berries=" + berry + "]" : "minecraft:cave_vines_plant[berries=" + berry + "]"));
                        if (tip) break;
                    }
                }
            }
        }
        if ((h >>> 40 & 3) == 0) {                                                               // an abandoned hut in the branches
            int hy = base + H - 17;
            for (int dx = 2; dx <= 6; dx++) for (int dz = -2; dz <= 2; dz++) {
                cv.set(x + dx, hy, z + dz, bd("minecraft:jungle_planks"));
                boolean edge = dx == 2 || dx == 6 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= 3; dy++) cv.set(x + dx, hy + dy, z + dz, edge && !(dx == 2 && dz == 0 && dy < 3) && !(dy == 2 && (dz == 0 || dx == 4)) ? bd((hash3(t.seed, x + dx, hy + dy, z + dz, 0x7A9) & 3) == 0 ? "minecraft:mossy_cobblestone" : "minecraft:jungle_planks") : WorldGen.Blocks.AIR);
                cv.set(x + dx, hy + 4, z + dz, bd("minecraft:jungle_slab"));
            }
            cv.set(x + 5, hy + 1, z + 1, bd("minecraft:chest[facing=west]"));
            cv.set(x + 5, hy + 1, z - 1, bd("minecraft:barrel[facing=up]"));
            for (int y = base + 1; y <= hy; y++) cv.soft(x + 2, y, z, bd("minecraft:ladder[facing=east]"));
        }
    }

    /** An overgrown stone idol: a great weathered head staring out of the undergrowth. */
    static void idol(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 9 + (int) (h >>> 4 & 3);
        boolean alongX = (h >>> 8 & 1) == 0;
        for (int dy = -2; dy <= H; dy++) {
            double f = Math.max(0, dy) / (double) H;
            double wx = 2.6 - f * f * 1.2, wz = 2.2 - f * f * 0.9;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                double a = alongX ? dx / wx : dz / wx, b = alongX ? dz / wz : dx / wz;
                if (a * a + b * b > 1) continue;
                int q = (int) (hash3(t.seed, x + dx, base + dy, z + dz, 0x7AA) & 15);
                cv.set(x + dx, base + dy, z + dz, bd(q < 6 ? "minecraft:mossy_cobblestone" : q < 10 ? "minecraft:cobblestone" : q < 13 ? "minecraft:mossy_stone_bricks" : q < 15 ? "minecraft:andesite" : "minecraft:moss_block"));
            }
        }
        // the face (on the +z or +x side): two hollow eyes, a long nose, a brow
        int ey = base + (int) (H * 0.62), fz = 2;
        for (int side = -1; side <= 1; side += 2) {
            int px = alongX ? x + fz : x + side, pz = alongX ? z + side : z + fz;
            cv.set(px, ey, pz, bd("minecraft:air")); cv.set(alongX ? px - 1 : px, ey, alongX ? pz : pz - 1, bd("minecraft:shroomlight"));
        }
        for (int dy = -3; dy <= 0; dy++) cv.set(alongX ? x + fz + 1 : x, ey + dy - 1, alongX ? z : z + fz + 1, bd("minecraft:cobblestone"));
        for (int w = -2; w <= 2; w++) cv.set(alongX ? x + fz + 1 : x + w, ey + 1, alongX ? z + w : z + fz + 1, bd("minecraft:mossy_stone_bricks"));
        cv.air(x, base + H + 1, z, bd("minecraft:jungle_leaves[persistent=true]")); cv.air(x + 1, base + H + 1, z, bd("minecraft:moss_carpet"));
    }

    /** What's left of an old gateway: two mossy pillars, one broken, and half the arch still spanning them. */
    static void ruinArch(Canvas cv, Terrain t, int x, int z, int base, long h) {
        boolean alongX = (h >>> 4 & 1) == 0;
        int H = 7 + (int) (h >>> 5 & 3), W = 3;
        for (int side = -1; side <= 1; side += 2) {
            int px = alongX ? x + side * W : x, pz = alongX ? z : z + side * W;
            int g = t.column(px, pz).surface, top = side < 0 ? H : H - 2 - (int) (h >>> 8 & 3);
            for (int y = g - 1; y <= base + top; y++) for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                int q = (int) (hash3(t.seed, px + a, y, pz + b, 0x7AB) & 7);
                cv.set(px + a, y, pz + b, bd(q < 3 ? "minecraft:mossy_stone_bricks" : q < 5 ? "minecraft:cracked_stone_bricks" : q < 7 ? "minecraft:stone_bricks" : "minecraft:mossy_cobblestone"));
            }
            if (side < 0) cv.set(px, base + top + 1, pz, bd("minecraft:chiseled_stone_bricks"));
        }
        for (int i = -W; i <= 1; i++) cv.set(alongX ? x + i : x, base + H, alongX ? z : z + i, bd(i % 2 == 0 ? "minecraft:mossy_stone_bricks" : "minecraft:stone_bricks"));
        for (int i = -W; i <= 1; i++) if ((hash(t.seed, x + i, z, 0x7AC) & 1) == 0) strand(cv, alongX ? x + i : x, base + H - 1, alongX ? z : z + i, 1 + (int) (hash(t.seed, x, z + i, 0x7AD) & 3), false);
        // fallen blocks round its foot
        for (int k = 0; k < 5; k++) {
            int fx = x + (int) (hash(t.seed, x, k, 0x7AE) % 7) - 3, fz = z + (int) (hash(t.seed, k, z, 0x7AF) % 7) - 3;
            cv.air(fx, t.column(fx, fz).surface + 1, fz, bd(k % 2 == 0 ? "minecraft:mossy_cobblestone" : "minecraft:cracked_stone_bricks"));
        }
    }

    static void mossyLog(Canvas cv, Terrain t, int x, int z, int base, long h) {
        boolean alongX = (h >>> 4 & 1) == 0;
        int len = 3 + (int) (h >>> 5 & 3);
        for (int i = -len / 2; i <= len / 2; i++) {
            int fx = alongX ? x + i : x, fz = alongX ? z : z + i, fy = t.column(fx, fz).surface + 1;
            cv.soft(fx, fy, fz, bd("minecraft:jungle_log[axis=" + (alongX ? "x" : "z") + "]"));
            int q = (int) (hash(t.seed, fx, fz, 0x7B0) & 7);
            if (q < 3) cv.air(fx, fy + 1, fz, bd("minecraft:moss_carpet")); else if (q == 3) cv.air(fx, fy + 1, fz, bd("minecraft:brown_mushroom")); else if (q == 4) cv.air(fx, fy + 1, fz, bd("minecraft:red_mushroom"));
        }
    }

    /** Undergrowth: a low bush of leaves round a short stem. */
    static void jungleBush(Canvas cv, Terrain t, int x, int z, int base, long h) {
        cv.soft(x, base + 1, z, bd("minecraft:jungle_log[axis=y]"));
        String lv = (h >>> 4 & 3) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : (h >>> 4 & 3) == 1 ? "minecraft:azalea_leaves[persistent=true]" : "minecraft:jungle_leaves[persistent=true]";
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 1; dy <= 3; dy++) {
            if (dx * dx + dz * dz + (dy - 2) * (dy - 2) * 2 > 5 + (hash3(t.seed, x + dx, base + dy, z + dz, 0x7B1) & 1)) continue;
            cv.air(x + dx, t.column(x + dx, z + dz).surface + dy, z + dz, bd(lv));
        }
    }

    // ------------------------------------------------------------------ the mountains

    static final B[] MOUNTAINS = {B.JAGGED_PEAKS, B.STONY_PEAKS, B.WINDSWEPT_HILLS, B.FROZEN_PEAKS, B.SNOWY_SLOPES};
    static final String[] STRATA = {"minecraft:stone", "minecraft:andesite", "minecraft:stone", "minecraft:tuff", "minecraft:stone", "minecraft:cobblestone", "minecraft:andesite", "minecraft:stone", "minecraft:calcite"};

    /**
     * The mountains' rock (2.0, the owner asked for a lot more detail: they were plain stone and snow). Cliffs show their
     * layers: every exposed face is banded in stone, andesite, tuff, cobble and a thin calcite seam, the bands tilting gently
     * across the range, with cracks, coal, iron and the odd emerald showing. Gentle ground below the snow is scree, coarse dirt,
     * mossy rock and tufts of grass; ledges on the cold peaks keep their snow and ice.
     */
    static void mountain(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData d, int lx, int lz, Terrain.Col c, long h) {
        if (Terrain.inCanyon(bx + lx, bz + lz)) return;      // the cathedral canyon paints its own ice walls (Shapes)
        int x = bx + lx, z = bz + lz, y = c.surface;
        if (c.sea || c.river || Sites.near(x, z, 3)) return;
        int low = y;
        for (int[] o : WorldGen.NEIGHBOURS) {
            int nx = lx + o[0], nz = lz + o[1];
            Terrain.Col n = nx >= 0 && nx < 16 && nz >= 0 && nz < 16 ? cols[nx * 16 + nz] : t.column(x + o[0], z + o[1]);
            if (n.surface < low) low = n.surface;
        }
        int drop = y - low, r = (int) (h & 255);
        boolean cold = c.biome == B.JAGGED_PEAKS || c.biome == B.FROZEN_PEAKS || c.biome == B.SNOWY_SLOPES;
        if (drop >= 3) {
            double tilt = t.fbmPublic(x / 90.0 + 11, z / 90.0) * 9 + x * 0.04;
            for (int yy = y; yy >= Math.max(low - 1, y - 48); yy--) {
                long hh = hash3(t.seed, x, yy, z, 0x3A0);
                int q = (int) (hh & 1023);
                String b = STRATA[Math.floorMod((int) Math.floor((yy + tilt) / 3.0), STRATA.length)];
                if (b.endsWith("calcite") && !cold) b = "minecraft:diorite";
                if (q < 60) b = "minecraft:cobblestone";
                else if (q < 80) b = "minecraft:gravel".equals(b) ? b : "minecraft:mossy_cobblestone";
                else if (q < 104) b = "minecraft:coal_ore";
                else if (q < 116) b = "minecraft:iron_ore";
                else if (q < 122) b = "minecraft:copper_ore";
                else if (q < 124) b = "minecraft:emerald_ore";
                if (q >= 60 && q < 80 && cold) b = "minecraft:packed_ice";
                d.setBlock(lx, yy, lz, bd(b));
            }
            if (cold && drop < 7 && (r & 3) != 0) d.setBlock(lx, y, lz, bd("minecraft:snow_block"));     // snow lying on the ledges
            return;
        }
        if (cold) {
            if (r < 8) d.setBlock(lx, y, lz, bd("minecraft:packed_ice"));
            else if (r < 20) d.setBlock(lx, y, lz, bd("minecraft:powder_snow"));
            return;
        }
        Material top = d.getType(lx, y, lz);
        if (r < 26) d.setBlock(lx, y, lz, bd("minecraft:gravel"));
        else if (r < 48) d.setBlock(lx, y, lz, bd("minecraft:coarse_dirt"));
        else if (r < 62) d.setBlock(lx, y, lz, bd("minecraft:mossy_cobblestone"));
        else if (r < 74) d.setBlock(lx, y, lz, bd("minecraft:andesite"));
        else if (top == Material.GRASS_BLOCK && d.getType(lx, y + 1, lz).isAir()) {
            if (r > 228) d.setBlock(lx, y + 1, lz, bd("minecraft:short_grass"));
            else if (r > 222) d.setBlock(lx, y + 1, lz, bd("minecraft:fern"));
            else if (r > 219) d.setBlock(lx, y + 1, lz, bd((h >>> 9 & 1) == 0 ? "minecraft:azure_bluet" : "minecraft:oxeye_daisy"));
        }
    }

    /** A boulder, half sunk in the slope. */
    static void boulder(Canvas cv, Terrain t, int x, int z, int base, long h) {
        double R = 1.4 + (h >>> 4 & 7) * 0.3;
        int n = (int) Math.ceil(R);
        boolean cold = base > 262;
        for (int dx = -n; dx <= n; dx++) for (int dz = -n; dz <= n; dz++) for (int dy = -n; dy <= n; dy++) {
            double dd = dx * dx + dy * dy * 1.5 + dz * dz + ((hash3(t.seed, x + dx, base + dy, z + dz, 0x3A6) & 3) - 1.5) * 0.6;
            if (dd > R * R) continue;
            int q = (int) (hash3(t.seed, x + dx, base + dy, z + dz, 0x3A7) & 15);
            cv.set(x + dx, base + (int) (R * 0.4) + dy, z + dz, bd(q < 6 ? "minecraft:andesite" : q < 10 ? "minecraft:cobblestone" : q < 13 ? "minecraft:stone" : cold ? "minecraft:snow_block" : "minecraft:mossy_cobblestone"));
        }
    }

    /** A spire of rock standing out of the mountainside, capped with snow up high. */
    static void rockSpire(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 9 + (int) (h >>> 4 & 15);
        double R = 2.2 + (h >>> 8 & 3) * 0.5;
        double lx = ((h >>> 12 & 7) - 3.5) / 14.0, lz = ((h >>> 15 & 7) - 3.5) / 14.0;
        for (int dy = -3; dy <= H; dy++) {
            double f = Math.max(0, dy) / (double) H, r = R * (1 - f * f) + 0.4;
            int cx = x + (int) Math.round(lx * dy), cz = z + (int) Math.round(lz * dy), n = (int) Math.ceil(r);
            for (int dx = -n; dx <= n; dx++) for (int dz = -n; dz <= n; dz++) {
                if (dx * dx + dz * dz > r * r + ((hash3(t.seed, cx + dx, base + dy, cz + dz, 0x3A8) & 3) - 1.5) * 0.5) continue;
                int q = (int) (hash3(t.seed, cx + dx, base + dy, cz + dz, 0x3A9) & 15);
                String b = STRATA[Math.floorMod((base + dy) / 3, STRATA.length)];
                if (q < 3) b = "minecraft:cobblestone"; else if (q == 3) b = "minecraft:coal_ore";
                cv.set(cx + dx, base + dy, cz + dz, bd(b.endsWith("calcite") ? "minecraft:andesite" : b));
            }
            if (dy == H && base + H > 255) cv.air(cx, base + H + 1, cz, bd("minecraft:snow_block"));
        }
    }

    /** Ore showing at the surface: a small vein to find from far off. */
    static void oreOutcrop(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int kind = (int) (h >>> 4 & 15);
        String ore = kind < 7 ? "minecraft:coal_ore" : kind < 12 ? "minecraft:iron_ore" : kind < 15 ? "minecraft:copper_ore" : "minecraft:emerald_ore";
        int n = 4 + (int) (h >>> 8 & 7);
        int px = x, py = base, pz = z;
        for (int i = 0; i < n; i++) {
            long hh = hash3(t.seed, px, py + i, pz, 0x3AA);
            if (!cv.air(px, py, pz) && cv.type(px, py, pz) != Material.SNOW_BLOCK || cv.type(px, py, pz) == Material.SNOW_BLOCK) cv.set(px, py, pz, bd(ore));
            switch ((int) (hh & 7)) { case 0, 1 -> px++; case 2, 3 -> px--; case 4 -> pz++; case 5 -> pz--; default -> py--; }
            if (py < base - 2) py = base - 2;
            int g = t.column(px, pz).surface;
            if (py > g) py = g;
        }
    }

    /** A pine on the lower slopes: a straight spruce with a narrow cone of needles. */
    static void alpinePine(Canvas cv, Terrain t, int x, int z, int base, long h) {
        if (base > 258) return;
        Material ground = cv.type(x, base, z);
        if (cv.in(x, base, z) && ground != Material.GRASS_BLOCK && ground != Material.COARSE_DIRT && ground != Material.SNOW_BLOCK && ground != Material.DIRT && ground != Material.PODZOL) return;
        int H = 6 + (int) (h >>> 4 & 7);
        for (int y = base + 1; y <= base + H; y++) cv.soft(x, y, z, bd("minecraft:spruce_log[axis=y]"));
        String lv = "minecraft:spruce_leaves[persistent=true]";
        for (int dy = 2; dy <= H + 1; dy++) {
            int r = dy > H ? 0 : dy == H ? 1 : ((H - dy) % 2 == 0 ? 2 : 1) + (dy < H / 2 ? 1 : 0);
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r && r > 0) continue;
                cv.air(x + dx, base + dy, z + dz, bd(lv));
            }
        }
        cv.air(x, base + H + 2, z, bd(lv));
    }

    /** A ruined watchtower of the mountain folk: a round stone tower, its top broken, a ladder, a chest and a dead campfire. */
    static void watchtower(Canvas cv, Terrain t, int x, int z, int base, long h) {
        for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) if (Math.abs(t.column(x + dx, z + dz).surface - base) > 4) return;   // too steep
        int H = 9 + (int) (h >>> 4 & 3);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            double dd = Math.hypot(dx, dz);
            if (dd > 3.5) continue;
            boolean wall = dd > 2.4;
            int g = t.column(x + dx, z + dz).surface;
            for (int y = Math.min(g, base) - 1; y <= base; y++) cv.set(x + dx, y, z + dz, bd("minecraft:cobblestone"));
            int broke = H - (int) (hash(t.seed, x + dx, z + dz, 0x3AB) & 3) - (dx > 0 ? 2 : 0);
            for (int y = base + 1; y <= base + H; y++) {
                if (!wall) { cv.set(x + dx, y, z + dz, WorldGen.Blocks.AIR); continue; }
                if (y > base + broke) { cv.soft(x + dx, y, z + dz, WorldGen.Blocks.AIR); continue; }
                int q = (int) (hash3(t.seed, x + dx, y, z + dz, 0x3AC) & 15);
                boolean slit = (y - base) % 4 == 3 && (dx == 0 || dz == 0);
                cv.set(x + dx, y, z + dz, slit ? WorldGen.Blocks.AIR : bd(q < 4 ? "minecraft:cracked_stone_bricks" : q < 7 ? "minecraft:mossy_stone_bricks" : q < 9 ? "minecraft:cobblestone" : "minecraft:stone_bricks"));
            }
            if (!wall && Math.abs(dx) + Math.abs(dz) <= 3) cv.set(x + dx, base + 5, z + dz, bd(dx == -1 && dz == 0 ? "minecraft:air" : "minecraft:spruce_planks"));   // a floor half way up
        }
        for (int y = base + 1; y <= base + 5; y++) cv.set(x - 1, y, z, bd("minecraft:ladder[facing=east]"));
        cv.set(x - 2, base + 1, z, bd("minecraft:stone_bricks")); for (int y = base + 2; y <= base + 5; y++) cv.set(x - 2, y, z, bd("minecraft:stone_bricks"));
        for (int y = base + 1; y <= base + 2; y++) cv.set(x, y, z + 3, WorldGen.Blocks.AIR);                                        // the door
        cv.set(x + 1, base + 1, z - 1, bd("minecraft:chest[facing=south]"));
        cv.set(x + 1, base + 1, z + 1, bd("minecraft:campfire[lit=false]"));
        cv.set(x, base + 6, z + 1, bd("minecraft:barrel[facing=up]"));
    }

    // ------------------------------------------------------------------ the great caverns' floors

    /**
     * The Fibers' great caverns had dead flat floors (2.0 audit, from the owner's screenshot). Now: low mossy mounds, root
     * pillars running from the floor to the roof, clusters of glowing mushrooms, boulders, and puddles of mud.
     */
    static void cavernFloors(Canvas cv, Terrain t) {
        for (Layout.Cavern cav : Layout.CAVERNS) {
            if (!cv.touches(cav.x() - cav.r(), cav.z() - cav.r(), cav.x() + cav.r(), cav.z() + cav.r())) continue;
            boolean fibers = cav.r() > 100;
            // rolling ground: hills, hollows with mud and shallow pools, terraces
            for (int x = cv.bx; x <= cv.bx + 15; x++) for (int z = cv.bz; z <= cv.bz + 15; z++) {
                if (Layout.dist(x, z, cav.x(), cav.z()) > cav.r() - 3 || Sites.near(x, z, 5)) continue;
                int g = groundIn(cv, x, z, cav.floor());
                if (g == Integer.MIN_VALUE) continue;
                double n = hill(t, x, z);
                double pd = fibers ? fiberPath(x, z) : 99;                  // the faction's paths cross the cavern on the level
                int up = (int) Math.round(Math.max(0, n) * (fibers ? 9 : 3) * Terrain.smooth(2.5, 12, pd));
                long hh = hash(t.seed, x, z, 0xF1D);
                if (pd <= 1.8) {
                    int q0 = (int) (hh & 15);
                    cv.set(x, g - 1, z, bd(q0 < 9 ? "minecraft:mud_bricks" : q0 < 13 ? "minecraft:packed_mud" : "minecraft:rooted_dirt"));
                    for (int k = 0; k <= 3; k++) if (!cv.air(x, g + k, z) && !cv.type(x, g + k, z).isSolid()) cv.set(x, g + k, z, WorldGen.Blocks.CAVE_AIR);
                    continue;
                }
                if (pd > 2.4 && pd <= 3.3 && (hash(t.seed, x / 2, z / 2, 0xF29) & 15) == 0 && up == 0) {     // lamps along the paths
                    cv.set(x, g, z, bd("minecraft:mud_brick_wall")); cv.set(x, g + 1, z, bd("minecraft:mud_brick_wall")); cv.set(x, g + 2, z, bd("minecraft:lantern"));
                    continue;
                }
                if (pd < 3.3 && n < -0.5) n = -0.4;                         // no pools at the path's edge
                int q = (int) (hh & 255);
                for (int k = 0; k < up; k++) {
                    boolean topK = k == up - 1;
                    cv.set(x, g + k, z, bd(topK ? (q < 140 ? "minecraft:moss_block" : q < 190 ? "minecraft:rooted_dirt" : q < 225 ? "minecraft:mud" : "minecraft:podzol") : (k > up - 4 ? "minecraft:rooted_dirt" : "minecraft:packed_mud")));
                }
                if (up == 0 && n < -0.5) {
                    // a pool only where every neighbour is a hollow too (so its rim is mud at the same level: nothing to run out)
                    // a pool only where the cell is boxed in: solid under it and on all four sides right now (the floor is uneven by
                    // a block and caves run under it), and not on the chunk's edge, where the neighbours can't be read
                    int ex = x & 15, ez = z & 15;
                    boolean pool = n < -0.68 && ex > 0 && ex < 15 && ez > 0 && ez < 15 && boxed(cv, x, g - 1, z);
                    cv.set(x, g - 1, z, pool ? WorldGen.Blocks.WATER : bd("minecraft:mud"));
                    if (pool) cv.set(x, g, z, q < 30 ? bd("minecraft:lily_pad") : WorldGen.Blocks.CAVE_AIR);    // whatever grew here goes
                    continue;
                }
                int ty = g + up;
                if (!cv.air(x, ty, z) || !cv.type(x, ty - 1, z).isSolid()) continue;   // round 7: plants stood on moss carpet / azaleas
                if (q < 9) cv.set(x, ty, z, bd("minecraft:azalea"));
                else if (q < 13) cv.set(x, ty, z, bd("minecraft:flowering_azalea"));
                else if (q < 42) cv.set(x, ty, z, bd("minecraft:moss_carpet"));
                else if (q < 62) cv.set(x, ty, z, bd("minecraft:short_grass"));
                else if (q < 70) cv.set(x, ty, z, bd("minecraft:fern"));
                else if (q < 74) cv.set(x, ty, z, bd("minecraft:brown_mushroom"));
                else if (q < 77) cv.set(x, ty, z, bd("minecraft:red_mushroom"));
                else if (q < 80 && cv.air(x, ty + 1, z)) { cv.set(x, ty, z, bd("minecraft:large_fern[half=lower]")); cv.set(x, ty + 1, z, bd("minecraft:large_fern[half=upper]")); }
                else if (q < 82) cv.set(x, ty, z, bd("minecraft:glow_lichen[down=true]"));
            }
            // features on a grid
            int size = 15, reach = 8;
            int cx0 = Math.floorDiv(cv.bx - reach, size), cx1 = Math.floorDiv(cv.bx + 15 + reach, size);
            int cz0 = Math.floorDiv(cv.bz - reach, size), cz1 = Math.floorDiv(cv.bz + 15 + reach, size);
            for (int ci = cx0; ci <= cx1; ci++) for (int cj = cz0; cj <= cz1; cj++) {
                long h = hash(t.seed, ci, cj, 0xF1E);
                int x = ci * size + (int) ((h >>> 8) % size), z = cj * size + (int) ((h >>> 20) % size);
                if (Layout.dist(x, z, cav.x(), cav.z()) > cav.r() - 10 || Sites.near(x, z, 9)) continue;
                if (fibers && fiberPath(x, z) < 7) continue;
                int floor = cav.floor() + 1 + (int) Math.round(Math.max(0, hill(t, x, z)) * (fibers ? 9 : 3));
                int kind = (int) (h & 15);
                if (kind <= 3) rootPillar(cv, t, x, z, floor, cav, h);
                else if (kind <= 6) giantShroom(cv, t, x, z, floor, h);
                else if (kind <= 8) stalagmite(cv, t, x, z, floor, h);
                else if (kind <= 10) glowCluster(cv, t, x, z, floor, h);
                else if (kind == 11) boulder(cv, t, x, z, floor, h >>> 3);
                else if (kind == 12 && fibers) fallenRoot(cv, t, x, z, floor, h);
            }
        }
    }

    static double fiberPath(int x, int z) {
        double best = 99;
        for (int[] p : Layout.FIBER_PATHS) best = Math.min(best, Layout.segDist(x, z, p[0], p[1], p[2], p[3], null));
        return best;
    }

    static boolean boxed(Canvas cv, int x, int y, int z) {
        for (int[] o : SIDES) { Material m = cv.type(x + o[0], y, z + o[1]); if (!m.isSolid() && m != Material.WATER) return false; }
        return cv.type(x, y - 1, z).isSolid();
    }

    static double hill(Terrain t, int x, int z) { return t.fbmPublic(x / 26.0 + 70, z / 26.0 + 3) + 0.45 * t.fbmPublic(x / 8.0, z / 8.0 + 40); }

    /** The first open cell over the cavern's floor in this column (the floor is uneven by a block), or MIN_VALUE. */
    static int groundIn(Canvas cv, int x, int z, int floor) {
        for (int y = floor; y <= floor + 3; y++) if (cv.air(x, y, z) && !cv.air(x, y - 1, z)) return y;
        return Integer.MIN_VALUE;
    }

    /** A pillar of roots from the floor to the roof, waisted in the middle, lit by growths in it. */
    static void rootPillar(Canvas cv, Terrain t, int x, int z, int floor, Layout.Cavern cav, long h) {
        double R = 1.5 + (h >>> 32 & 3) * 0.5;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (!cv.in(x + dx, floor, z + dz)) continue;
            double dd = Math.hypot(dx, dz);
            int solid = 0;
            for (int y = floor - 3; y < cav.ceil() + 14; y++) {
                double f = (y - floor) / (double) Math.max(1, cav.ceil() - 30 - floor);
                double rr = R * (0.5 + 2.6 * (Math.min(1, Math.max(0, f)) - 0.5) * (Math.min(1, Math.max(0, f)) - 0.5) * 2) + ((hash3(t.seed, x + dx, y / 3, z + dz, 0xF25) & 3) - 1.5) * 0.25;
                if (dd > rr) continue;
                if (!cv.air(x + dx, y, z + dz)) { if (y > floor + 12 && ++solid > 3) break; continue; }
                int q = (int) (hash3(t.seed, x + dx, y, z + dz, 0xF1F) & 31);
                cv.set(x + dx, y, z + dz, bd(q < 17 ? "minecraft:mangrove_roots" : q < 24 ? "minecraft:muddy_mangrove_roots" : q < 28 ? "minecraft:mangrove_wood" : q < 30 ? "minecraft:rooted_dirt" : "minecraft:shroomlight"));
            }
        }
    }

    /** A giant mushroom of the deep: a pale stem and a wide cap with glowing gills. */
    static void giantShroom(Canvas cv, Terrain t, int x, int z, int floor, long h) {
        int H = 6 + (int) (h >>> 32 & 7), R = 3 + (int) (h >>> 36 & 3);
        boolean red = (h >>> 40 & 3) == 0;
        for (int y = floor - 2; y < floor + H; y++) cv.soft(x, y, z, bd("minecraft:mushroom_stem"));
        String cap = red ? "minecraft:red_mushroom_block" : "minecraft:brown_mushroom_block";
        int top = floor + H;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double dd = Math.hypot(dx, dz) / R;
            if (dd > 1.05) continue;
            int droop = red ? (int) Math.round(dd * dd * 3) : 0;
            cv.air(x + dx, top - droop, z + dz, bd(cap));
            if (red && dd > 0.7) cv.air(x + dx, top - droop - 1, z + dz, bd(cap));
            if (dd < 0.8 && (dx != 0 || dz != 0) && (dx + dz & 1) == 0) cv.air(x + dx, top - droop - 1, z + dz, bd("minecraft:shroomlight"));   // the gills
        }
    }

    /** A stalagmite of packed earth and roots. */
    static void stalagmite(Canvas cv, Terrain t, int x, int z, int floor, long h) {
        int H = 6 + (int) (h >>> 32 & 15);
        double R = 2 + (h >>> 36 & 3) * 0.6;
        for (int dy = -3; dy <= H; dy++) {
            double rr = R * (1 - Math.max(0, dy) / (double) H) + 0.3;
            int n = (int) Math.ceil(rr);
            for (int dx = -n; dx <= n; dx++) for (int dz = -n; dz <= n; dz++) {
                if (dx * dx + dz * dz > rr * rr) continue;
                int q = (int) (hash3(t.seed, x + dx, floor + dy, z + dz, 0xF26) & 15);
                cv.soft(x + dx, floor + dy, z + dz, bd(q < 6 ? "minecraft:packed_mud" : q < 10 ? "minecraft:rooted_dirt" : q < 13 ? "minecraft:moss_block" : q < 15 ? "minecraft:mud" : "minecraft:ochre_froglight"));
            }
        }
    }

    /** A cluster of small glowing mushrooms. */
    static void glowCluster(Canvas cv, Terrain t, int x, int z, int floor, long h) {
        for (int m = 0; m < 4 + (int) (h >>> 34 & 3); m++) {
            int mx = x + (int) (hash(t.seed, x, m, 0xF20) % 7) - 3, mz = z + (int) (hash(t.seed, m, z, 0xF21) % 7) - 3;
            int hgt = 1 + (int) (hash(t.seed, mx, mz, 0xF22) & 3), my = floor - 2;
            while (my < floor + 12 && cv.in(mx, my, mz) && !cv.air(mx, my, mz)) my++;
            if (!cv.in(mx, my, mz)) continue;
            for (int k = 0; k < hgt; k++) cv.air(mx, my + k, mz, bd("minecraft:mushroom_stem"));
            cv.air(mx, my + hgt, mz, bd("minecraft:shroomlight"));
            for (int[] o : SIDES) cv.air(mx + o[0], my + hgt, mz + o[1], bd("minecraft:brown_mushroom_block"));
            cv.air(mx, my + hgt + 1, mz, bd("minecraft:brown_mushroom_block"));
        }
    }

    /** A great root lying across the floor, arched in the middle so you can walk under it. */
    static void fallenRoot(Canvas cv, Terrain t, int x, int z, int floor, long h) {
        boolean alongX = (h >>> 32 & 1) == 0;
        int len = 7;
        for (int i = -len; i <= len; i++) {
            int rise = (int) Math.round(4.5 * Math.cos(i / (double) len * Math.PI / 2));
            int px = alongX ? x + i : x, pz = alongX ? z : z + i;
            for (int w = 0; w <= 1; w++) for (int k = 0; k <= 1; k++)
                cv.soft(alongX ? px : px + w, floor + rise + k, alongX ? pz + w : pz, bd("minecraft:mangrove_wood[axis=" + (alongX ? "x" : "z") + "]"));
            if (Math.abs(i) == len) for (int y = floor - 3; y < floor + rise; y++) cv.soft(px, y, pz, bd("minecraft:mangrove_roots"));
            if ((hash(t.seed, px, pz, 0xF27) & 3) == 0) strand(cv, px, floor + rise - 1, pz, 1 + (int) (hash(t.seed, px, pz, 0xF28) & 3), true);
        }
    }

    // ------------------------------------------------------------------ underground: Fibers, the Mire, spider caves

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
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
                    // 2.0: the walls. Bare stone beside the open air becomes packed earth, moss and roots.
                    for (int[] o : SIDES) {
                        int nx = lx + o[0], nz = lz + o[1];
                        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                        Material side = d.getType(nx, y, nz);
                        if (side != Material.STONE && side != Material.DEEPSLATE && side != Material.TUFF) continue;
                        int w = (int) (hash3(t.seed, x + o[0], y, z + o[1], 0xF23) & 31);
                        double band = t.fbmPublic((x + o[0]) / 14.0, (z + o[1]) / 14.0 + y / 9.0);
                        d.setBlock(nx, y, nz, bd(w < 2 ? "minecraft:shroomlight" : w < 4 ? "minecraft:mangrove_roots" : band > 0.25 ? "minecraft:moss_block" : band > -0.1 ? "minecraft:rooted_dirt" : band > -0.4 ? "minecraft:mud" : w < 16 ? "minecraft:packed_mud" : "minecraft:muddy_mangrove_roots"));
                    }
                    // 2.0: light. Glowing growths set in the roof, and glow-berry vines hanging from it.
                    if (above.isSolid() && above != Material.SHROOMLIGHT) {
                        double glow = t.fbmPublic(x / 11.0 + 200, z / 11.0 + y / 20.0);
                        if (glow > 0.42 && r < 420) d.setBlock(lx, y + 1, lz, bd((r & 1) == 0 ? "minecraft:shroomlight" : "minecraft:ochre_froglight"));
                        else if (r >= 400 && r < 438) {
                            int len = 2 + (int) (h >>> 20 & 7);
                            for (int k = 0; k < len; k++) {
                                if (y - k < -40 || !d.getType(lx, y - k, lz).isAir()) break;
                                boolean tip = k == len - 1 || !d.getType(lx, y - k - 1, lz).isAir();
                                boolean berry = (hash3(t.seed, x, y - k, z, 0xF24) & 3) != 0;
                                d.setBlock(lx, y - k, lz, bd(tip ? "minecraft:cave_vines[berries=" + berry + "]" : "minecraft:cave_vines_plant[berries=" + berry + "]"));
                                if (tip) break;
                            }
                            continue;
                        }
                    }
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
                    if (((hash(t.seed, x, z, 0xF1C)) & 63) == 0 && above.isSolid()) {
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
                    if (r < 60 && (below.isSolid() || above.isSolid())) d.setBlock(lx, y, lz, bd("minecraft:cobweb"));   // round 7: not hanging in mid-air
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
            // round 7: the base was a fixed height, so huts and roots hung in the caverns: stand on the real cave floor,
            // and only where the hut's / tree's corners stand on it too
            int fl = WorldGen.caveFloor(t, x, base + 12, z, 30);
            if (fl == Integer.MIN_VALUE || fl < -46) continue;
            boolean flat = true;
            for (int[] o : new int[][]{{-1, -1}, {7, 0}, {0, 6}, {7, 6}, {2, 2}}) if (Math.abs(WorldGen.caveFloor(t, x + o[0], fl + 4, z + o[1], 10) - fl) > 2) flat = false;
            if (!flat) continue;
            base = fl + 1;
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
            branch(cv, log.replace("_log", "_wood"), x, top - 4 - b * 3, z, x + (int) Math.round(Math.cos(a) * 4), top - 2 - b * 3, z + (int) Math.round(Math.sin(a) * 4));
        }
    }

    /** Wartsfield Valley: white/brown banded trunks that twist up to wide flat umbrella canopies of pale leaves. */
    static void umbrellaTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 13 + (int) (h % 9);
        double ph = (h >>> 6 & 63) / 10.0, lean = 0.12 + (h >>> 12 & 7) / 40.0;
        int px = x, pz = z;
        for (int i = 0; i <= H; i++) {
            // AUDIT: the trunk leaned by jumping sideways, so its pieces only touched at the corners (or not at all). It now
            // moves one block at a time, and every sideways step is filled in at that height.
            int wx = x + (int) Math.round(Math.sin(ph + i * 0.35) * lean * i * 0.5);
            int wz = z + (int) Math.round(Math.cos(ph + i * 0.27) * lean * i * 0.5);
            String b = (i / 2) % 3 == 0 ? "minecraft:mushroom_stem" : (i / 2) % 3 == 1 ? "minecraft:birch_wood" : "minecraft:stripped_birch_wood";
            for (int guard = 0; guard < 8; guard++) {
                cv.set(px, base + i, pz, bd(b));
                cv.set(px + 1, base + i, pz, bd(b));
                if (px == wx && pz == wz) break;
                if (px != wx) px += Integer.signum(wx - px); else pz += Integer.signum(wz - pz);
            }
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
            ex = x + (int) Math.round(Math.cos(a) * len); ez = z + (int) Math.round(Math.sin(a) * len);
            branch(cv, log.replace("_log", "_wood"), x, y, z, ex, y + len / 3, ez);     // connected (it was drawn in loose steps)
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
            int len = 2 + (int) ((h >>> (30 + b)) & 1);
            // AUDIT: these were loose fence posts too (floating bits); now a connected charred branch
            branch(cv, "minecraft:stripped_dark_oak_log", x, by, z, x + (int) Math.round(Math.cos(a) * len), by + len / 2 + 1, z + (int) Math.round(Math.sin(a) * len));
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
        int campY = t.column(x + R + 4, z).surface;
        if (((h >>> 12) & 3) == 0 && Math.abs(campY - base) <= 2) { // a campsite beside it, on its own ground (it hung in caves / sat in hills)
            int cx = x + R + 4, cz = z;
            base = campY;
            cv.set(cx, base + 1, cz, bd("minecraft:campfire[lit=true]"));
            cv.set(cx + 2, base + 1, cz + 1, bd("minecraft:barrel[facing=up]"));
            cv.set(cx - 2, base + 1, cz - 1, bd("minecraft:spruce_log[axis=x]"));
            cv.set(cx - 1, base + 1, cz - 1, bd("minecraft:spruce_log[axis=x]"));
            cv.set(cx + 1, base + 1, cz - 2, bd("minecraft:brown_bed[part=foot,facing=south]"));
            cv.set(cx + 1, base + 1, cz - 1, bd("minecraft:brown_bed[part=head,facing=south]"));
        }
    }

    /** The spider woods: dead twisted trees wrapped in cobwebs, a web curtain and an egg sac. */
    /** A branch of logs from a to b where every block touches the one before it face to face (no floating pieces),
     *  each log lying along the way it runs. */
    static void branch(Canvas cv, String log, int x0, int y0, int z0, int x1, int y1, int z1) {
        int x = x0, y = y0, z = z0;
        for (int guard = 0; guard < 64 && (x != x1 || y != y1 || z != z1); guard++) {
            int dx = x1 - x, dy = y1 - y, dz = z1 - z;
            String axis;
            if (Math.abs(dx) >= Math.abs(dz) && Math.abs(dx) >= Math.abs(dy)) { x += Integer.signum(dx); axis = "x"; }
            else if (Math.abs(dz) >= Math.abs(dy)) { z += Integer.signum(dz); axis = "z"; }
            else { y += Integer.signum(dy); axis = "y"; }
            cv.soft(x, y, z, bd(log + "[axis=" + axis + "]"));
        }
    }

    /** A clump of web (and a few dead leaves) round a point. */
    static void webClump(Canvas cv, Terrain t, int x, int y, int z, double r, int leafEvery) {
        int R = (int) Math.ceil(r);
        for (int dx = -R; dx <= R; dx++) for (int dy = -R; dy <= R; dy++) for (int dz = -R; dz <= R; dz++) {
            if (dx * dx + dy * dy * 1.6 + dz * dz > r * r) continue;
            long hh = hash3(t.seed, x + dx, y + dy, z + dz, 0x5D8);
            if ((hh & 3) == 0) cv.air(x + dx, y + dy, z + dz, bd("minecraft:cobweb"));
            else if (leafEvery > 0 && (hh >>> 2) % leafEvery == 0) cv.air(x + dx, y + dy, z + dz, bd("minecraft:pale_oak_leaves[persistent=true]"));
        }
    }

    /** A strand hanging down from under a block: silk, or pale moss. */
    static void strand(Canvas cv, int x, int y, int z, int len, boolean moss) {
        for (int k = 0; k < len; k++) {
            if (!cv.air(x, y - k, z)) return;
            cv.air(x, y - k, z, bd(moss ? "minecraft:pale_hanging_moss[tip=" + (k == len - 1) + "]" : "minecraft:cobweb"));
        }
    }

    /**
     * The spider woods (2.0 audit: the first version drew its branches as loose fence posts, so they floated in pieces, and
     * had no crowns). Dead pale trees with real branches, each ending in a crown of web and a few dead leaves, silk
     * curtains sagging between branches, strands hanging down; great trees with thick trunks and roots; stumps with
     * cocoons; fallen trunks.
     */
    static void spiderTree(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int kind = (int) (h & 15);
        String log = "minecraft:pale_oak_log", wood = "minecraft:pale_oak_wood";
        if (kind == 0 || kind == 1) {                    // a stump with a cocoon beside it
            int H = 1 + (int) (h >>> 4 & 1);
            for (int y = base + 1; y <= base + H; y++) cv.soft(x, y, z, bd(log + "[axis=y]"));
            cv.air(x, base + H + 1, z, bd("minecraft:cobweb"));
            int cx = x + ((h >>> 6 & 1) == 0 ? 1 : -1), cz = z + ((h >>> 7 & 1) == 0 ? 1 : -1);
            int cy = t.column(cx, cz).surface + 1;
            cv.air(cx, cy, cz, bd("minecraft:white_wool")); cv.air(cx, cy + 1, cz, bd("minecraft:white_wool")); cv.air(cx, cy + 2, cz, bd("minecraft:cobweb"));
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if ((hash(t.seed, cx + o[0], cz + o[1], 0x5D9) & 1) == 0) cv.air(cx + o[0], t.column(cx + o[0], cz + o[1]).surface + 1, cz + o[1], bd("minecraft:cobweb"));
            return;
        }
        if (kind == 2) {                                 // a fallen trunk, webbed over
            boolean alongX = (h >>> 4 & 1) == 0;
            int len = 4 + (int) (h >>> 5 & 3);
            for (int i = -len / 2; i <= len / 2; i++) {
                int fx = alongX ? x + i : x, fz = alongX ? z : z + i, fy = t.column(fx, fz).surface + 1;
                cv.soft(fx, fy, fz, bd(log + "[axis=" + (alongX ? "x" : "z") + "]"));
                if ((hash(t.seed, fx, fz, 0x5DC) & 3) == 0) cv.air(fx, fy + 1, fz, bd("minecraft:cobweb"));
                else if ((hash(t.seed, fx, fz, 0x5DC) & 7) == 1) cv.air(fx, fy + 1, fz, bd("minecraft:pale_moss_carpet"));
            }
            return;
        }
        boolean great = kind == 3;                       // a great tree: 2 x 2 trunk, roots, more and longer branches
        int H = great ? 17 + (int) (h >>> 4 & 7) : 9 + (int) (h >>> 4 & 7);
        int w = great ? 1 : 0;
        for (int y = base - 1; y <= base + H; y++) for (int ax = 0; ax <= w; ax++) for (int az = 0; az <= w; az++) cv.set(x + ax, y, z + az, bd(log + "[axis=y]"));
        // roots spreading out over the ground
        for (int[] o : new int[][]{{-1, 0}, {w + 1, 0}, {0, -1}, {0, w + 1}, {w + 1, w}, {w, w + 1}}) {
            if ((hash(t.seed, x + o[0], z + o[1], 0x5DD) & 3) > (great ? 2 : 0)) continue;
            int ry = t.column(x + o[0], z + o[1]).surface + 1;
            if (Math.abs(ry - (base + 1)) > 2) continue;
            for (int y = ry; y <= Math.max(ry, base + 1 + (great ? 1 : 0)); y++) cv.soft(x + o[0], y, z + o[1], bd(wood + "[axis=y]"));
        }
        int n = great ? 5 + (int) (h >>> 8 & 1) : 3 + (int) (h >>> 8 & 1);
        int[][] tips = new int[n][];
        for (int b = 0; b < n; b++) {
            double a = (b + ((h >>> (10 + b * 5)) & 31) / 31.0 * 0.8) / n * Math.PI * 2;
            int len = (great ? 5 : 3) + (int) ((h >>> (36 + b * 2)) & 3);
            int by = base + (int) (H * (0.5 + 0.5 * (b + 1) / (double) n));
            int tx = x + (int) Math.round(Math.cos(a) * len), tz = z + (int) Math.round(Math.sin(a) * len), ty = by + 1 + len / 2;
            branch(cv, log, x + (Math.cos(a) > 0 ? w : 0), by, z + (Math.sin(a) > 0 ? w : 0), tx, ty, tz);
            tips[b] = new int[]{tx, ty, tz};
            webClump(cv, t, tx, ty + 1, tz, great ? 2.6 : 2.1, 5);
            int sl = 1 + (int) ((h >>> (48 + b * 2)) & 3);
            strand(cv, tx, ty - 1, tz, sl + (great ? 2 : 0), (h >>> (56 + b) & 1) == 0);
        }
        webClump(cv, t, x, base + H + 1, z, great ? 3.0 : 2.2, 4);            // the crown
        // silk curtains sagging between neighbouring branch tips
        for (int b = 0; b < n; b++) {
            if ((h >>> (20 + b) & 1) == 0) continue;
            int[] p = tips[b], q = tips[(b + 1) % n];
            int steps = Math.max(Math.abs(p[0] - q[0]), Math.abs(p[2] - q[2])) * 2;
            if (steps == 0 || steps > 26) continue;
            for (int k = 1; k < steps; k++) {
                double f = k / (double) steps, sag = Math.sin(f * Math.PI) * (2 + steps / 8.0);
                cv.air((int) Math.round(p[0] + (q[0] - p[0]) * f), (int) Math.round(p[1] + (q[1] - p[1]) * f - sag), (int) Math.round(p[2] + (q[2] - p[2]) * f), bd("minecraft:cobweb"));
            }
        }
        if (great && (h >>> 44 & 1) == 0) {              // an egg sac on the trunk
            int ey = base + 3 + (int) (h >>> 45 & 3);
            cv.air(x - 1, ey, z, bd("minecraft:white_wool")); cv.air(x - 1, ey + 1, z, bd("minecraft:white_wool")); cv.air(x - 1, ey + 2, z, bd("minecraft:cobweb")); cv.air(x - 1, ey - 1, z, bd("minecraft:cobweb"));
        }
    }

    /** A great orb web strung between two tall dead trunks: spokes and rings of silk in an upright wheel. */
    static void greatWeb(Canvas cv, Terrain t, int x, int z, int base, long h) {
        boolean alongX = (h >>> 3 & 1) == 0;
        int R = 5 + (int) (h >>> 4 & 1) * 2;
        int cy = base + R + 3;
        String log = "minecraft:pale_oak_log[axis=y]";
        for (int side = -1; side <= 1; side += 2) {
            int px = alongX ? x + side * (R + 1) : x, pz = alongX ? z : z + side * (R + 1);
            int g = t.column(px, pz).surface;
            if (Math.abs(g - base) > 5) return;                                   // too steep for it
            for (int y = g; y <= cy + R + 1; y++) cv.set(px, y, pz, bd(log));
            webClump(cv, t, px, cy + R + 2, pz, 1.8, 0);
        }
        for (int u = -R; u <= R; u++) for (int v = -R; v <= R; v++) {
            double d = Math.hypot(u, v);
            if (d > R + 0.4) continue;
            boolean spoke = u == 0 || v == 0 || Math.abs(u) == Math.abs(v);
            int ring = (int) Math.round(d);
            boolean onRing = ring % 2 == 0 && Math.abs(d - ring) < 0.5 && ring > 0;
            if (!(spoke || onRing)) continue;
            if ((hash3(t.seed, x + u, cy + v, z, 0x5DE) & 15) == 0) continue;     // a few torn strands
            cv.air(alongX ? x + u : x, cy + v, alongX ? z : z + u, bd("minecraft:cobweb"));
        }
        // the silk it's tied down with
        for (int side = -1; side <= 1; side += 2) for (int k = 1; k <= 3; k++)
            cv.air(alongX ? x + side * (R - k) : x, cy - R - k, alongX ? z : z + side * (R - k), bd("minecraft:cobweb"));
        cv.air(x, cy, z, bd("minecraft:white_wool"));                              // something wrapped up in the middle
    }

    /** A brood mound: a low hollow dome of pale moss, cobble and silk with a spider spawner and a chest inside. */
    static void nestMound(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int R = 4;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            int g = t.column(x + dx, z + dz).surface;
            if (Math.abs(g - base) > 3) return;
        }
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) for (int dy = 0; dy <= R; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.5);
            if (d > R + 0.3) continue;
            int wx = x + dx, wy = base + 1 + dy, wz = z + dz;
            if (d < R - 1.1) { cv.set(wx, wy, wz, WorldGen.Blocks.CAVE_AIR); continue; }
            long hh = hash3(t.seed, wx, wy, wz, 0x5DF);
            int r = (int) (hh & 15);
            cv.set(wx, wy, wz, bd(r < 7 ? "minecraft:pale_moss_block" : r < 11 ? "minecraft:mossy_cobblestone" : r < 13 ? "minecraft:white_wool" : r < 15 ? "minecraft:cobblestone" : "minecraft:cobweb"));
        }
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) if (dx * dx + dz * dz <= R * R) for (int y = base; y > base - 4 && cv.air(x + dx, y, z + dz); y--) cv.set(x + dx, y, z + dz, bd("minecraft:coarse_dirt"));
        // the way in (2 high), and what's inside
        for (int k = 2; k <= R + 1; k++) { cv.set(x + k, base + 1, z, WorldGen.Blocks.CAVE_AIR); cv.set(x + k, base + 2, z, WorldGen.Blocks.CAVE_AIR); }
        cv.set(x, base + 1, z, bd("minecraft:spawner"));
        cv.set(x - 2, base + 1, z, bd("minecraft:chest[facing=east]"));
        cv.set(x, base + 1, z - 2, bd("minecraft:cobweb")); cv.set(x, base + 1, z + 2, bd("minecraft:cobweb")); cv.set(x - 1, base + 2, z + 1, bd("minecraft:cobweb"));
        cv.set(x, base + 2, z - 1, bd("minecraft:white_wool")); cv.set(x, base + 1, z - 1, bd("minecraft:white_wool"));           // an egg sac
    }

    /** The Wasteland: sulphur mushrooms (yellow / lime caps, glowing rims) on pale stems. */
    static void shroom(Canvas cv, Terrain t, int x, int z, int base, long h) {
        int H = 8 + (int) (h % 11);
        int R = 3 + (int) ((h >>> 5) % 5);
        boolean yellow = (h >>> 9 & 1) == 0;
        int stemTop = base + H + (int) (R / 2.0 + 1) - 1;     // round 7: the cap sat 1-2 blocks over the stem's end (floating caps)
        for (int y = base; y <= stemTop; y++) { cv.set(x, y, z, bd("minecraft:mushroom_stem")); if (y < base + 2) for (int k = 0; k < 4; k++) cv.soft(x + (k == 0 ? 1 : k == 1 ? -1 : 0), y, z + (k == 2 ? 1 : k == 3 ? -1 : 0), bd("minecraft:mushroom_stem")); }
        int top = base + H;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) for (int dy = -1; dy <= R / 2 + 1; dy++) {
            double d = Math.hypot(dx, dz) / R;
            double cap = (1 - d * d) * (R / 2.0 + 1);
            if (d > 1 || dy > cap || dy < cap - 1.6) continue;
            String b = d > 0.85 ? "minecraft:verdant_froglight" : (hash3(t.seed, x + dx, top + dy, z + dz, 0xA59) & 7) == 0 ? "minecraft:bone_block" : yellow ? "minecraft:yellow_terracotta" : "minecraft:lime_terracotta";
            cv.air(x + dx, top + dy, z + dz, bd(b));
        }
        for (int dx = -R + 1; dx <= R - 1; dx++) for (int dz = -R + 1; dz <= R - 1; dz++) // gills underneath
            if (Math.hypot(dx, dz) < R - 1 && (dx + dz & 1) == 0) {
                double dd = Math.hypot(dx, dz) / R, cap = (1 - dd * dd) * (R / 2.0 + 1);
                int under = top + (int) Math.ceil(cap - 1.6) - 1;           // round 7: right under the cap (they hung in the air)
                cv.air(x + dx, under, z + dz, bd("minecraft:ochre_froglight"));
            }
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
        // kelp beside it, from ITS floor (it started at the rock's height and floated over lower ground)
        int kx = x + R + 1, kf = base + 1;
        while (kf > base - 30 && cv.type(kx, kf - 1, z) == Material.WATER) kf--;
        if (cv.inXZ(kx, z) && cv.type(kx, kf - 1, z).isSolid())
            for (int k = 0; k < 12; k++) if (cv.type(kx, kf + k, z) == Material.WATER) cv.set(kx, kf + k, z, bd(k == 11 || cv.type(kx, kf + k + 1, z) != Material.WATER ? "minecraft:kelp" : "minecraft:kelp_plant")); else break;
    }
}
