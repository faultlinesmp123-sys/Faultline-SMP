package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * THE DROWNED CITY (Deep 1.9.0): a walled stone city on a plateau in the far east of the Dark Ocean, round CX, CZ,
 * sunk long before the Bubble was built. Its people are still in it (DeepMobs' drowned citizens, guards and priests),
 * and so is their king.
 *  - THE WALL: a ring 5 thick and 14 high round the plateau (WALL_IN..WALL_OUT), crenellated, with 16 round towers and a
 *    gatehouse on each axis (a half-raised portcullis of iron bars)
 *  - THE STREETS: a grid (every 28 blocks, 4 wide), two grand avenues crossing at THE PLAZA (a fountain under a statue of
 *    the Drowned King, soul-lantern posts), lamp posts along the avenues
 *  - ~300 BUILDINGS (BLDS, a fixed layout): houses, townhouses, shops, towers, ruins and coral gardens in six stone
 *    palettes, decayed (missing top floors, holes, broken windows, collapsed roofs) and overgrown (seagrass, coral on the
 *    roofs, kelp through the floors), each floor furnished, some with a Dark Loot chest
 *  - THE CATHEDRAL (west): a gothic nave of deepslate with a rose window, pews, an altar and a bell tower
 *  - THE BANK (east): marble columns, a counter, and a VAULT below it
 *  - THE PALACE (north): a grand stair, a colonnade, the throne hall under a prismarine dome (the Drowned King's throne:
 *    THRONE) and corner towers, with the TREASURY below the throne
 *  - VAULT CHESTS (VAULTS): only open for a Drowned Vault Key (City.java checks the position), richer than Dark Loot
 * Biome DEEP_COLD_OCEAN. Pure functions, like the rest of the generator.
 */
final class DrownedCity {
    static final int CX = 2600, CZ = -300;
    static final int R = 200, WALL_IN = 204, WALL_OUT = 209, BLEND = 60, Y = 62, WALL_TOP = Y + 14;
    static final int GRID = 28, STREET = 4;
    // the three great buildings: centre x/z, half width (x) and half depth (z)
    static final int PAL_X = CX, PAL_Z = CZ - 120, PAL_HW = 30, PAL_HD = 22;
    static final int CAT_X = CX - 112, CAT_Z = CZ + 30, CAT_HW = 13, CAT_HD = 27;
    static final int BANK_X = CX + 112, BANK_Z = CZ - 30, BANK_HW = 10, BANK_HD = 10;
    static final int PLAZA_R = 26;

    static final int HOUSE = 0, TOWNHOUSE = 1, SHOP = 2, TOWER = 3, RUIN = 4, GARDEN = 5;

    /** A building: kind, frame origin + rotation, size, floors, palette, a seed. */
    record Bld(int kind, int ox, int oz, int r, int w, int d, int floors, int pal, long h, int x0, int z0, int x1, int z1) { }

    static final List<Bld> BLDS = new ArrayList<>();
    /** Vault chests: world x, y, z (only a Drowned Vault Key opens them). */
    static final List<int[]> VAULTS = new ArrayList<>();
    /** Where the Drowned King sits, and where his court gathers. */
    static final int[] THRONE = {PAL_X, Y + 3, PAL_Z - PAL_HD + 6};

    static {
        for (int bi = -8; bi <= 7; bi++) for (int bj = -8; bj <= 7; bj++) {
            int ox = CX + bi * GRID + STREET, oz = CZ + bj * GRID + STREET; // the block's NW corner (24 x 24)
            int ccx = ox + 12, ccz = oz + 12;
            if (Math.hypot(ccx - CX, ccz - CZ) > R - 18) continue;
            if (Math.hypot(ccx - CX, ccz - CZ) < PLAZA_R + 16) continue;
            if (box(ox, oz, ox + 23, oz + 23, PAL_X - PAL_HW - 4, PAL_Z - PAL_HD - 4, PAL_X + PAL_HW + 4, PAL_Z + PAL_HD + 8)) continue;
            if (box(ox, oz, ox + 23, oz + 23, CAT_X - CAT_HW - 4, CAT_Z - CAT_HD - 10, CAT_X + CAT_HW + 4, CAT_Z + CAT_HD + 4)) continue;
            if (box(ox, oz, ox + 23, oz + 23, BANK_X - BANK_HW - 4, BANK_Z - BANK_HD - 4, BANK_X + BANK_HW + 4, BANK_Z + BANK_HD + 6)) continue;
            for (int li = 0; li < 2; li++) for (int lj = 0; lj < 2; lj++) {
                long h = DeepGenerator.hash(0xC17L, bi * 4 + li, bj * 4 + lj, 3);
                double t = DeepGenerator.unit(h);
                int kind = t < 0.08 ? TOWER : t < 0.33 ? TOWNHOUSE : t < 0.63 ? HOUSE : t < 0.78 ? SHOP : t < 0.92 ? RUIN : GARDEN;
                int w = kind == TOWER ? 7 : kind == SHOP ? 10 : 9, d = kind == TOWER ? 7 : kind == TOWNHOUSE ? 10 : 9;
                int floors = switch (kind) { case TOWER -> 5 + (int) (DeepGenerator.unit(h >>> 3) * 3); case TOWNHOUSE -> 3; case SHOP -> 1 + (int) (DeepGenerator.unit(h >>> 5) * 2); default -> 2; };
                int lx0 = ox + li * 13, lz0 = oz + lj * 13;          // the 11 x 11 lot
                int px = lx0 + (11 - w) / 2, pz = lz0 + (11 - d) / 2;
                int r, fx, fz;
                if (lj == 0) { r = 0; fx = px; fz = pz; }                   // the front faces north (the street above)
                else { r = 2; fx = px + w - 1; fz = pz + d - 1; }             // faces south
                int pal = (int) (DeepGenerator.unit(DeepGenerator.hash(0xC17L, bi, bj, 9 + li)) * 6); // = PALS.length (made below)
                BLDS.add(new Bld(kind, fx, fz, r, w, d, floors, pal, h, px - 1, pz - 1, px + w, pz + d));
            }
        }
        // the treasury (under the throne) and the bank's vault
        int ty = Y - 7;
        for (int k = -2; k <= 2; k += 2) { VAULTS.add(new int[]{PAL_X + k, ty, PAL_Z - PAL_HD + 2}); VAULTS.add(new int[]{PAL_X + k, ty, PAL_Z - PAL_HD + 10}); }
        int by = Y - 6;
        VAULTS.add(new int[]{BANK_X - 4, by, BANK_Z - 4}); VAULTS.add(new int[]{BANK_X + 4, by, BANK_Z - 4});
        VAULTS.add(new int[]{BANK_X - 4, by, BANK_Z + 2}); VAULTS.add(new int[]{BANK_X + 4, by, BANK_Z + 2});
    }

    static boolean box(int ax0, int az0, int ax1, int az1, int bx0, int bz0, int bx1, int bz1) { return ax0 <= bx1 && ax1 >= bx0 && az0 <= bz1 && az1 >= bz0; }

    static boolean vault(int x, int y, int z) { for (int[] v : VAULTS) if (v[0] == x && v[1] == y && v[2] == z) return true; return false; }

    private DrownedCity() { }

    static double weight(int x, int z) { return Regions.weight(x, z, CX, CZ, WALL_OUT + 10, BLEND, 6); }

    static boolean near(int x, int z, int extra) { return Regions.near(x, z, CX, CZ, WALL_OUT + BLEND + 30 + extra); }

    static boolean inside(int x, int z) { return Math.hypot(x - CX, z - CZ) < WALL_IN; }

    // =========================================================================================== the ground
    static double floor(DeepGenerator.Noise n, int x, int z) {
        double d = Math.hypot(x - CX, z - CZ);
        if (d < WALL_OUT + 3) return Y;
        return Y - (d - WALL_OUT - 3) * 0.55 + n.n2(n.dunes, x, z) * 1.2;   // the rubble slope down from the walls
    }

    static boolean avenue(int x, int z) { return Math.abs(x - CX) <= 3 || Math.abs(z - CZ) <= 3; }

    static boolean street(int x, int z) {
        return avenue(x, z) || Math.floorMod(x - CX, GRID) < STREET || Math.floorMod(z - CZ, GRID) < STREET;
    }

    static Material top(DeepGenerator.Noise n, Random rnd, int x, int z, int up) {
        double d = Math.hypot(x - CX, z - CZ);
        if (d >= WALL_OUT + 3) return rnd.nextInt(3) == 0 ? Material.COBBLESTONE : rnd.nextBoolean() ? Material.GRAVEL : Material.ANDESITE;
        if (d < PLAZA_R) {
            int ring = (int) Math.floor(d);
            return ring % 6 == 0 ? Material.POLISHED_ANDESITE : ring % 6 == 3 ? Material.CHISELED_STONE_BRICKS : Material.POLISHED_DIORITE;
        }
        if (avenue(x, z)) {
            boolean edge = Math.abs(x - CX) == 3 || Math.abs(z - CZ) == 3;
            return edge ? Material.POLISHED_ANDESITE : rnd.nextInt(9) == 0 ? Material.CRACKED_STONE_BRICKS : Material.SMOOTH_STONE;
        }
        if (street(x, z)) {
            int r = rnd.nextInt(10);
            return r < 5 ? Material.STONE_BRICKS : r < 7 ? Material.COBBLESTONE : r < 8 ? Material.MOSSY_COBBLESTONE : r < 9 ? Material.CRACKED_STONE_BRICKS : Material.ANDESITE;
        }
        int r = rnd.nextInt(10); // yards between buildings
        return r < 4 ? Material.COBBLESTONE : r < 6 ? Material.MOSSY_COBBLESTONE : r < 8 ? Material.GRAVEL : Material.PACKED_MUD;
    }

    static Material under(Random rnd, int y, int up) { return y > up - 3 ? Material.COBBLESTONE : Material.STONE; }

    static void decorate(DeepGenerator g, DeepGenerator.Noise n, ChunkData d, Random rnd, int lx, int lz, int x, int z, int up) {
        int y = up + 1;
        if (y >= DeepGenerator.SEA - 2 || d.getType(lx, y, lz) != Material.WATER || !d.getType(lx, up, lz).isSolid()) return;
        if (Math.hypot(x - CX, z - CZ) < PLAZA_R || avenue(x, z)) return;
        int r = rnd.nextInt(1000);
        if (r < 110) d.setBlock(lx, y, lz, Material.SEAGRASS);
        else if (r < 125 && d.getType(lx, y + 1, lz) == Material.WATER) { d.setBlock(lx, y, lz, Paint.bd("tall_seagrass[half=lower]")); d.setBlock(lx, y + 1, lz, Paint.bd("tall_seagrass[half=upper]")); }
        else if (r < 133) d.setBlock(lx, y, lz, Paint.bd("sea_pickle[pickles=" + (1 + rnd.nextInt(3)) + ",waterlogged=true]"));
        else if (r < 140 && !street(x, z)) { // kelp in the yards
            int h = 4 + rnd.nextInt(14);
            for (int i = 0; i < h && d.getType(lx, y + i, lz) == Material.WATER; i++) d.setBlock(lx, y + i, lz, i == h - 1 ? Material.KELP : Material.KELP_PLANT);
        }
    }

    // =========================================================================================== palettes
    /** wall, trim, roof stairs, roof block, floor, window pane, door. */
    record Pal(String wall, String trim, String roof, String roofBlock, String floor, String pane, String door) { }

    static final Pal[] PALS = {
            new Pal("stone_bricks", "polished_andesite", "deepslate_tile_stairs", "deepslate_tiles", "spruce_planks", "glass_pane", "spruce_door"),
            new Pal("bricks", "stone_bricks", "dark_oak_stairs", "dark_oak_planks", "oak_planks", "glass_pane", "dark_oak_door"),
            new Pal("deepslate_bricks", "polished_blackstone", "blackstone_stairs", "blackstone", "dark_oak_planks", "gray_stained_glass_pane", "dark_oak_door"),
            new Pal("cut_sandstone", "smooth_sandstone", "sandstone_stairs", "sandstone", "birch_planks", "glass_pane", "birch_door"),
            new Pal("polished_diorite", "quartz_pillar", "prismarine_brick_stairs", "prismarine_bricks", "spruce_planks", "light_blue_stained_glass_pane", "warped_door"),
            new Pal("mud_bricks", "packed_mud", "mud_brick_stairs", "mud_bricks", "jungle_planks", "glass_pane", "jungle_door"),
    };

    // =========================================================================================== drawing
    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        if (!near(p.x0 + 8, p.z0 + 8, 10)) return;
        for (Bld b : BLDS) if (p.touches(b.x0 - 2, b.z0 - 2, b.x1 + 2, b.z1 + 2)) building(p, b);
        if (p.touches(PAL_X - PAL_HW - 8, PAL_Z - PAL_HD - 8, PAL_X + PAL_HW + 8, PAL_Z + PAL_HD + 12)) palace(p);
        if (p.touches(CAT_X - CAT_HW - 4, CAT_Z - CAT_HD - 12, CAT_X + CAT_HW + 4, CAT_Z + CAT_HD + 4)) cathedral(p);
        if (p.touches(BANK_X - BANK_HW - 4, BANK_Z - BANK_HD - 4, BANK_X + BANK_HW + 4, BANK_Z + BANK_HD + 6)) bank(p);
        if (p.touches(CX - PLAZA_R, CZ - PLAZA_R, CX + PLAZA_R, CZ + PLAZA_R)) plaza(p);
        wall(p);
        lamps(p);
    }

    static double u(long h, int k) { return DeepGenerator.unit(DeepGenerator.hash(h, k, 13, 17)); }

    // ---- a building
    static void building(Paint p, Bld b) {
        Paint.Frame f = p.frame(b.ox, b.oz, b.r, true);
        Pal pal = PALS[b.pal];
        int w = b.w, d = b.d, n = b.floors, h = 4;
        int G = Y + 1;
        if (b.kind == GARDEN) { garden(f, b); return; }
        boolean ruin = b.kind == RUIN;
        // the floors that are still standing (the top ones collapsed in some)
        int stand = ruin ? 1 : Math.max(1, n - (u(b.h, 1) < 0.3 ? 1 : 0));
        int door = w / 2;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) f.set(x, Y, z, (x == 0 || z == 0 || x == w - 1 || z == d - 1) ? pal.trim() : pal.floor());
        for (int i = 0; i < stand; i++) {
            int fy = Y + h * i;
            for (int y = fy + 1; y <= fy + h; y++) for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                boolean ex = x == 0 || x == w - 1, ez = z == 0 || z == d - 1;
                if (!ex && !ez) { if (y == fy + h && i < stand - 1 && f.u(x, y, z, 3) > 0.12) f.set(x, y, z, pal.floor()); continue; }
                if (ruin && f.u(x, y, z, 21) < 0.15 + (y - G) * 0.2) continue;
                if (f.u(x, y, z, 22) < 0.05) continue;                                     // holes
                boolean corner = ex && ez, band = y == fy + h;
                String m = corner || band ? pal.trim() : pal.wall();
                if (corner && pal.trim().equals("quartz_pillar")) m = "quartz_pillar[axis=y]";
                // windows: two blocks tall, every other block along the walls, not on the corners
                boolean win = !corner && !band && (y == fy + 2 || y == fy + 3) && ((ex ? z : x) % 2 == 1) && !(i == 0 && ez && z == 0 && Math.abs(x - door) < 2);
                if (win) m = f.u(x, y, z, 23) < 0.35 ? "water" : pal.pane();
                f.set(x, y, z, m);
            }
            furnish(f, b, pal, fy, i);
        }
        // the door (a gap or a broken door)
        if (u(b.h, 4) < 0.5) { f.set(door, G, 0, pal.door() + "[facing=north,half=lower,hinge=left,open=true]"); f.set(door, G + 1, 0, pal.door() + "[facing=north,half=upper,hinge=left,open=true]"); }
        else { f.set(door, G, 0, "water"); f.set(door, G + 1, 0, "water"); }
        if (b.kind == SHOP && !ruin) for (int x = 0; x < w; x++) if (f.u(x, G + 2, -1, 31) > 0.3) f.set(x, G + 3, -1, (x % 2 == 0 ? "red" : "white") + "_wool");
        int top = Y + h * stand;
        if (ruin) { rubble(f, b, pal); return; }
        if (b.kind == TOWER) spire(f, b, pal, top);
        else if (stand == n) roof(f, b, pal, top);
        else for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (f.u(x, top + 1, z, 33) < 0.25) f.set(x, top + 1, z, pal.wall()); // broken stubs where a floor was
        // overgrowth: coral on the roofs, seagrass in the rooms
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (f.u(x, top, z, 41) < 0.06) f.set(x, top + 1, z, f.u(x, top, z, 42) < 0.5 ? "tube_coral_block" : "brain_coral_block");
    }

    static void furnish(Paint.Frame f, Bld b, Pal pal, int fy, int i) {
        int w = b.w, d = b.d;
        int y = fy + 1;
        double r = u(b.h, 50 + i);
        f.set(1, y, d - 2, "bookshelf");
        f.set(w - 2, y, d - 2, r < 0.3 ? "chest[facing=north]" : "barrel[facing=up]");
        f.set(w / 2, y, d / 2, pal.trim().equals("quartz_pillar") ? "polished_diorite_slab[type=bottom]" : "spruce_fence");
        f.set(w / 2, y + 1, d / 2, "spruce_pressure_plate");
        f.set(w / 2 - 1, y, d / 2, "spruce_stairs[facing=east]");
        f.set(w / 2 + 1, y, d / 2, "spruce_stairs[facing=west]");
        if (r > 0.6) f.set(1, y, 1, "sea_pickle[pickles=3]");
        if (u(b.h, 60 + i) < 0.5) f.set(w / 2, fy + 4 - 1, d / 2, "soul_lantern[hanging=true]");
        if (b.floors > 1) for (int yy = fy + 1; yy <= fy + 4; yy++) f.set(1, yy, 1, "ladder[facing=south]");
        if (u(b.h, 70 + i) < 0.4) for (int k = 0; k < 2 + (int) (u(b.h, 71) * 6); k++) f.set(2 + k % (w - 4), y, 2 + k / (w - 4), "seagrass");
    }

    static void roof(Paint.Frame f, Bld b, Pal pal, int top) {
        int w = b.w, d = b.d;
        boolean flat = u(b.h, 80) < 0.3;
        if (flat) { // a flat roof with a parapet
            for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                boolean e = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                if (f.u(x, top + 1, z, 81) < 0.08) continue;
                f.set(x, top + 1, z, e ? pal.trim() : pal.roofBlock());
                if (e && (x + z) % 2 == 0) f.set(x, top + 2, z, pal.wall());
            }
            return;
        }
        // a gable roof running front to back (ridge along local z)
        int half = (w + 1) / 2;
        for (int k = 0; k < half; k++) for (int z = -1; z <= d; z++) {
            int y = top + 1 + k;
            if (f.u(k, y, z, 82) < 0.07 || f.u(w - 1 - k, y, z, 83) < 0.07) continue; // a hole fallen in
            f.set(k, y, z, pal.roof() + "[facing=east]");
            f.set(w - 1 - k, y, z, pal.roof() + "[facing=west]");
            if (z == 0 || z == d - 1) for (int x = k + 1; x < w - 1 - k; x++) f.set(x, y, z, pal.wall()); // the gable ends
        }
        if (w % 2 == 1) for (int z = -1; z <= d; z++) f.set(w / 2, top + half, z, pal.roofBlock());
    }

    static void spire(Paint.Frame f, Bld b, Pal pal, int top) {
        int w = b.w, c = w / 2;
        for (int k = 0; k <= c; k++) {
            int y = top + 1 + k * 2;
            for (int x = k; x < w - k; x++) for (int z = k; z < b.d - k; z++) {
                boolean e = x == k || z == k || x == w - 1 - k || z == b.d - 1 - k;
                if (e) { f.set(x, y, z, pal.roofBlock()); f.set(x, y + 1, z, pal.roofBlock()); }
            }
        }
        f.set(c, top + 2 + c * 2, c, "lightning_rod");
    }

    static void rubble(Paint.Frame f, Bld b, Pal pal) {
        for (int x = -1; x <= b.w; x++) for (int z = -1; z <= b.d; z++)
            if (f.u(x, Y + 1, z, 91) < 0.3) f.set(x, Y + 1, z, f.u(x, Y + 1, z, 92) < 0.5 ? pal.wall() : "cobblestone");
    }

    static void garden(Paint.Frame f, Bld b) {
        for (int x = 0; x < b.w; x++) for (int z = 0; z < b.d; z++) {
            boolean e = x == 0 || z == 0 || x == b.w - 1 || z == b.d - 1;
            f.set(x, Y, z, e ? "stone_bricks" : f.u(x, Y, z, 1) < 0.5 ? "moss_block" : "sand");
            if (e) { if ((x + z) % 2 == 0) f.set(x, Y + 1, z, "stone_brick_wall"); continue; }
            double r = f.u(x, Y + 1, z, 2);
            if (r < 0.2) f.set(x, Y + 1, z, MermaidDen.CORAL_BLOCKS[(int) (r * 25) % 5].getKey().getKey());
            else if (r < 0.45) f.set(x, Y + 1, z, MermaidDen.CORALS[(int) (r * 40) % 10].getKey().getKey());
            else if (r < 0.55) f.set(x, Y + 1, z, "sea_pickle[pickles=4]");
        }
        // a little statue in the middle
        int c = b.w / 2;
        f.set(c, Y + 1, c, "chiseled_stone_bricks"); f.set(c, Y + 2, c, "stone_brick_wall"); f.set(c, Y + 3, c, "polished_andesite");
    }

    // ---- the plaza: a fountain under a statue of the king
    static void plaza(Paint p) {
        Paint.Frame f = p.frame(CX, CZ, 0, true);
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            double d = Math.hypot(x, z);
            if (d <= 6 && d > 5) { f.set(x, Y + 1, z, "stone_bricks"); f.set(x, Y + 2, z, "stone_brick_slab[type=bottom]"); }
            else if (d <= 5) f.set(x, Y, z, "prismarine_bricks");
        }
        // the statue: plinth, robe, arms out, head, crown, trident
        for (int y = Y + 1; y <= Y + 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) f.set(x, y, z, "chiseled_stone_bricks");
        for (int y = Y + 4; y <= Y + 8; y++) { f.set(0, y, 0, "polished_andesite"); if (y < Y + 7) { f.set(-1, y, 0, "polished_andesite"); f.set(1, y, 0, "polished_andesite"); } }
        f.set(-2, Y + 7, 0, "andesite_wall"); f.set(2, Y + 7, 0, "andesite_wall");
        f.set(0, Y + 9, 0, "polished_diorite");
        f.set(0, Y + 10, 0, "gold_block");
        for (int y = Y + 5; y <= Y + 11; y++) f.set(3, y, 0, y == Y + 11 ? "lightning_rod" : "iron_bars");
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int lx = (int) Math.round(Math.cos(a) * (PLAZA_R - 3)), lz = (int) Math.round(Math.sin(a) * (PLAZA_R - 3));
            for (int y = Y + 1; y <= Y + 3; y++) f.set(lx, y, lz, "dark_oak_fence");
            f.set(lx, Y + 4, lz, "soul_lantern[hanging=false]");
        }
    }

    static void lamps(Paint p) {
        for (int s = -1; s <= 1; s += 2) for (int dist = PLAZA_R + 8; dist < R; dist += 14) {
            int[][] spots = {{CX + 5, CZ + s * dist}, {CX - 5, CZ + s * dist}, {CX + s * dist, CZ + 5}, {CX + s * dist, CZ - 5}};
            for (int[] sp : spots) {
                if (!p.touches(sp[0], sp[1], sp[0], sp[1])) continue;
                if (Paint.u(sp[0], Y, sp[1], 5) < 0.2) { p.set(sp[0], Y + 1, sp[1], "dark_oak_fence[waterlogged=true]"); continue; } // a broken one
                for (int y = Y + 1; y <= Y + 3; y++) p.set(sp[0], y, sp[1], "dark_oak_fence[waterlogged=true]");
                p.set(sp[0], Y + 4, sp[1], "soul_lantern[hanging=false,waterlogged=true]");
            }
        }
    }

    // ---- the wall, its towers and gates
    static void wall(Paint p) {
        for (int x = p.x0; x < p.x0 + 16; x++) for (int z = p.z0; z < p.z0 + 16; z++) {
            double d = Math.hypot(x - CX, z - CZ);
            if (d < WALL_IN - 1 || d > WALL_OUT + 7) continue;
            double a = Math.atan2(z - CZ, x - CX);
            boolean gate = Math.abs(x - CX) <= 4 || Math.abs(z - CZ) <= 4;
            // round towers every 22.5 degrees
            double ta = Math.round(a / (Math.PI / 8)) * (Math.PI / 8);
            double tx = CX + Math.cos(ta) * (WALL_IN + 2.5), tz = CZ + Math.sin(ta) * (WALL_IN + 2.5);
            boolean axisTower = Math.abs(Math.sin(ta * 2)) < 0.01; // the ones on the axes stand beside the gates instead
            double td = Math.hypot(x - tx, z - tz);
            if (!axisTower && td <= 5) {
                int topY = WALL_TOP + 8;
                for (int y = Y + 1; y <= topY; y++) p.set(x, y, z, td > 4 ? (y % 5 == 0 ? "polished_andesite" : "stone_bricks") : (y == WALL_TOP || y == topY - 1 ? "stone_bricks" : td > 3 ? "stone_bricks" : "water"));
                for (int k = 0; k < 6; k++) { int y = topY + 1 + k; if (td <= 5 - k) p.set(x, y, z, "deepslate_tiles"); }
                if (td > 4 && Math.floorMod((int) Math.round(Math.atan2(z - tz, x - tx) * 4), 2) == 0) p.set(x, WALL_TOP + 5, z, "water"); // arrow slits
                continue;
            }
            if (d < WALL_IN || d > WALL_OUT) continue;
            if (gate) { // the gatehouse arch: open below, a half raised portcullis
                int lat = Math.min(Math.abs(x - CX), Math.abs(z - CZ));
                for (int y = Y + 1; y <= WALL_TOP + 2; y++) {
                    boolean arch = y <= Y + 7 + (int) Math.round(Math.sqrt(Math.max(0, 16 - lat * lat)) * 0.7);
                    if (lat <= 3 && arch) { if (y >= Y + 5 && (int) Math.round(d) == (WALL_IN + WALL_OUT) / 2) p.set(x, y, z, "iron_bars[waterlogged=true]"); continue; }
                    p.set(x, y, z, y >= WALL_TOP + 1 ? ((x + z) % 2 == 0 ? "stone_bricks" : "water") : y == WALL_TOP ? "polished_andesite" : "stone_bricks");
                }
                continue;
            }
            for (int y = Y + 1; y <= WALL_TOP + 1; y++) {
                String m;
                if (y == WALL_TOP + 1) m = (d > WALL_OUT - 1 || d < WALL_IN + 1) && Math.floorMod((int) Math.round(a * 400), 2) == 0 ? "stone_bricks" : "water"; // crenels
                else if (y == WALL_TOP) m = "polished_andesite";
                else m = Paint.u(x, y, z, 7) < 0.04 ? "water" : Paint.u(x, y, z, 8) < 0.2 ? "mossy_stone_bricks" : "stone_bricks";
                p.set(x, y, z, m);
            }
        }
    }

    // ---- the palace
    static void palace(Paint p) {
        Paint.Frame f = p.frame(PAL_X, PAL_Z, 0, true);
        int hw = PAL_HW, hd = PAL_HD, G = Y + 1;
        // the platform and grand stair (front = +z, toward the plaza)
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd + 6; z++) {
            int lift = z > hd ? Math.max(0, 3 - (z - hd)) : 3;
            for (int y = Y; y <= Y + lift; y++) f.set(x, y, z, y == Y + lift ? (z > hd ? "polished_diorite_stairs[facing=north]" : "polished_diorite") : "stone_bricks");
        }
        int fl = Y + 3;
        // the walls: hall in the middle (x -12..12), wings either side
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd; z++) {
            boolean hall = Math.abs(x) <= 12;
            int top = hall ? fl + 15 : fl + 9;
            boolean edge = Math.abs(x) == hw || Math.abs(z) == hd || Math.abs(x) == 12;
            boolean underDome = hall && x * x + (z + 2) * (z + 2) < 132;
            if (!underDome && Paint.u(PAL_X + x, top + 1, PAL_Z + z, 4) > 0.06) f.set(x, top + 1, z, "polished_diorite_slab[type=bottom]"); // the roof (a few holes)
            if (!edge) { if (!hall && (Math.abs(x) - 13) % 9 == 0) for (int y = fl + 1; y <= top; y++) f.set(x, y, z, "polished_diorite"); continue; }
            for (int y = fl + 1; y <= top; y++) {
                boolean win = !(Math.abs(x) == 12) && y > fl + 2 && y < top - 2 && (Math.abs(z) == hd ? Math.floorMod(x, 4) == 0 : Math.floorMod(z, 4) == 0);
                if (Math.abs(x) == 12 && z > -hd + 2 && z < hd && y < fl + 6) continue; // arches into the wings
                if (Paint.u(PAL_X + x, y, PAL_Z + z, 1) < 0.03) continue;
                f.set(x, y, z, win ? "light_blue_stained_glass_pane" : (y == top ? "gold_block" : (y - fl) % 6 == 0 ? "quartz_bricks" : "polished_diorite"));
            }
        }
        // the colonnade along the front
        for (int x = -hw + 2; x <= hw - 2; x += 4) for (int y = fl + 1; y <= fl + 9; y++) f.set(x, y, hd + 3, "quartz_pillar[axis=y]");
        for (int x = -hw + 2; x <= hw - 2; x++) f.set(x, fl + 10, hd + 3, "quartz_bricks");
        // the doors into the hall
        for (int x = -2; x <= 2; x++) for (int y = fl + 1; y <= fl + 6; y++) f.set(x, y, hd, "water");
        // the dome over the hall (prismarine, a gold ring)
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int k = 0; k <= 12; k++) {
            double dd = Math.sqrt(x * x + z * z + (k * 1.0) * (k * 1.0));
            if (dd > 11.5 && dd <= 12.5) f.set(x, fl + 16 + k, z - 2, k == 0 ? "gold_block" : (k + x + z) % 5 == 0 ? "sea_lantern" : "prismarine_bricks");
        }
        f.set(0, fl + 29, -2, "lightning_rod");
        // the throne on its dais at the back of the hall, carpet up to it
        for (int x = -4; x <= 4; x++) for (int z = -hd + 2; z <= -hd + 7; z++) f.set(x, fl + 1, z, "gold_block");
        for (int z = -hd + 8; z <= hd - 1; z++) for (int x = -1; x <= 1; x++) f.set(x, fl + 1, z, "red_carpet");
        f.set(0, fl + 2, -hd + 6, "quartz_stairs[facing=south]");
        f.set(-1, fl + 2, -hd + 6, "gold_block"); f.set(1, fl + 2, -hd + 6, "gold_block");
        for (int y = fl + 3; y <= fl + 6; y++) f.set(0, y, -hd + 5, y == fl + 6 ? "gold_block" : "quartz_pillar[axis=y]");
        for (int x = -6; x <= 6; x += 12) for (int y = fl + 1; y <= fl + 12; y++) f.set(x, y, -hd + 4, y % 4 == 0 ? "sea_lantern" : "prismarine_bricks");
        // banners of the drowned court
        for (int z = -hd + 4; z <= hd - 4; z += 6) { f.set(-11, fl + 8, z, "purple_wool"); f.set(11, fl + 8, z, "purple_wool"); f.set(-11, fl + 7, z, "purple_wool"); f.set(11, fl + 7, z, "purple_wool"); }
        // corner towers
        int[][] corners = {{-hw, -hd}, {hw, -hd}, {-hw, hd}, {hw, hd}};
        for (int[] c : corners) for (int x = c[0] - 5; x <= c[0] + 5; x++) for (int z = c[1] - 5; z <= c[1] + 5; z++) {
            double dd = Math.hypot(x - c[0], z - c[1]);
            if (dd > 5) continue;
            for (int y = fl + 1; y <= fl + 22; y++) if (dd > 4) f.set(x, y, z, y % 6 == 0 ? "gold_block" : "polished_diorite");
            for (int k = 0; k < 6; k++) if (dd <= 5 - k) f.set(x, fl + 23 + k, z, "prismarine_bricks");
        }
        // the treasury under the throne: a stair down behind it, a vault room
        int ty = Y - 7;
        for (int x = -4; x <= 4; x++) for (int z = -hd + 1; z <= -hd + 11; z++) for (int y = ty - 1; y <= ty + 4; y++) {
            boolean e = Math.abs(x) == 4 || z == -hd + 1 || z == -hd + 11 || y == ty - 1 || y == ty + 4;
            f.set(x, y, z, e ? "polished_blackstone_bricks" : "water");
        }
        for (int y = ty; y <= fl; y++) f.set(3, y, -hd + 9, "ladder[facing=north]"); // the shaft down beside the throne
        f.set(3, fl + 1, -hd + 9, "water");
        for (int[] v : VAULTS) if (v[1] == ty) f.set(v[0] - PAL_X, v[1], v[2] - PAL_Z, "chest[facing=north]");
        for (int x = -3; x <= 3; x += 3) f.set(x, ty + 3, -hd + 6, "soul_lantern[hanging=true]");
    }

    // ---- the cathedral (front = +z, the bell tower at the front)
    static void cathedral(Paint p) {
        Paint.Frame f = p.frame(CAT_X, CAT_Z, 0, true);
        int hw = CAT_HW, hd = CAT_HD, fl = Y, top = Y + 22;
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd; z++) f.set(x, fl, z, Math.abs(x) <= 1 ? "red_terracotta" : "polished_deepslate");
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd; z++) {
            boolean edge = Math.abs(x) == hw || Math.abs(z) == hd;
            if (!edge) continue;
            for (int y = fl + 1; y <= top; y++) {
                boolean lancet = Math.abs(x) == hw && Math.floorMod(z, 6) == 0 && y > fl + 4 && y < top - 3;
                if (Paint.u(CAT_X + x, y, CAT_Z + z, 2) < 0.03) continue;
                f.set(x, y, z, lancet ? (y > top - 7 ? "purple_stained_glass_pane" : "blue_stained_glass_pane") : (Math.floorMod(z, 6) == 0 ? "polished_deepslate" : "deepslate_bricks"));
            }
            // buttresses outside the long walls
            if (Math.abs(x) == hw && Math.floorMod(z, 6) == 3) for (int y = fl + 1; y <= fl + 14; y++) f.set(x + Integer.signum(x) * (1 + (fl + 14 - y) / 5), y, z, "deepslate_bricks");
        }
        // the roof: steep, of deepslate tiles
        for (int k = 0; k <= hw; k++) for (int z = -hd; z <= hd; z++) {
            int y = top + 1 + k;
            if (Paint.u(CAT_X + k, y, CAT_Z + z, 3) < 0.05) continue;
            f.set(-hw + k, y, z, "deepslate_tile_stairs[facing=east]");
            f.set(hw - k, y, z, "deepslate_tile_stairs[facing=west]");
        }
        // the rose window over the doors at the front
        for (int x = -5; x <= 5; x++) for (int y = top - 9; y <= top - 1; y++) {
            double dd = Math.hypot(x, y - (top - 5));
            if (dd <= 4.5) f.set(x, y, hd, dd < 1.2 ? "yellow_stained_glass_pane" : ((int) Math.round(Math.atan2(y - (top - 5), x) * 3) % 2 == 0 ? "red_stained_glass_pane" : "magenta_stained_glass_pane"));
        }
        for (int x = -2; x <= 2; x++) for (int y = fl + 1; y <= fl + 6; y++) f.set(x, y, hd, "water");
        // pews and the altar
        for (int z = -hd + 10; z <= hd - 5; z += 3) for (int x = -hw + 2; x <= hw - 2; x++) if (Math.abs(x) > 2 && Paint.u(CAT_X + x, fl + 1, CAT_Z + z, 4) > 0.15) f.set(x, fl + 1, z, "dark_oak_stairs[facing=south]");
        for (int x = -3; x <= 3; x++) f.set(x, fl + 1, -hd + 4, "polished_blackstone");
        f.set(0, fl + 2, -hd + 4, "candle[candles=4,lit=false,waterlogged=true]");
        for (int y = fl + 2; y <= fl + 10; y++) f.set(0, y, -hd + 1, y == fl + 7 ? "gold_block" : "polished_blackstone_wall");
        f.set(-1, fl + 7, -hd + 1, "polished_blackstone_wall"); f.set(1, fl + 7, -hd + 1, "polished_blackstone_wall");
        for (int z = -hd + 6; z <= hd - 6; z += 8) { f.set(0, top - 1, z, "iron_chain"); f.set(0, top - 2, z, "iron_chain"); f.set(0, top - 3, z, "soul_lantern[hanging=true]"); }
        // the bell tower over the front, with a spire
        int bz0 = hd - 5;
        for (int x = -4; x <= 4; x++) for (int z = bz0 - 4; z <= bz0 + 4; z++) {
            boolean e = Math.abs(x) == 4 || Math.abs(z - bz0) == 4;
            for (int y = top + 1; y <= top + 18; y++) if (e) f.set(x, y, z, (y - top) % 6 == 0 ? "polished_deepslate" : (y > top + 12 && (Math.abs(x) == 4 ? Math.abs(z - bz0) < 2 : Math.abs(x) < 2)) ? "water" : "deepslate_bricks");
        }
        f.set(0, top + 14, bz0, "bell[attachment=ceiling,facing=north]");
        f.set(0, top + 15, bz0, "deepslate_bricks");
        for (int k = 0; k < 5; k++) for (int x = -4 + k; x <= 4 - k; x++) for (int z = bz0 - 4 + k; z <= bz0 + 4 - k; z++) f.set(x, top + 19 + k * 2, z, "deepslate_tiles");
        f.set(0, top + 29, bz0, "lightning_rod");
    }

    // ---- the bank and its vault
    static void bank(Paint p) {
        Paint.Frame f = p.frame(BANK_X, BANK_Z, 0, true);
        int hw = BANK_HW, hd = BANK_HD, fl = Y + 1, top = Y + 12;
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd + 4; z++) { f.set(x, Y, z, "polished_diorite"); f.set(x, fl, z, z > hd ? "polished_diorite_stairs[facing=north]" : "smooth_quartz"); }
        for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd; z++) {
            boolean e = Math.abs(x) == hw || Math.abs(z) == hd;
            if (e) for (int y = fl + 1; y <= top; y++) f.set(x, y, z, y == top ? "quartz_bricks" : "polished_diorite");
            f.set(x, top + 1, z, Math.abs(x) == hw || Math.abs(z) == hd ? "quartz_bricks" : "smooth_quartz_slab[type=bottom]");
        }
        for (int x = -hw + 1; x <= hw - 1; x += 3) for (int y = fl + 1; y <= top - 1; y++) f.set(x, y, hd + 2, "quartz_pillar[axis=y]");
        for (int x = -hw + 1; x <= hw - 1; x++) f.set(x, top, hd + 2, "quartz_bricks");
        for (int x = -2; x <= 2; x++) for (int y = fl + 1; y <= fl + 4; y++) f.set(x, y, hd, "water");
        for (int x = -hw + 2; x <= hw - 2; x++) { f.set(x, fl + 1, 0, "polished_andesite"); f.set(x, fl + 2, 0, "iron_bars"); }
        // the vault below: a big iron door (iron blocks) with a gap pried open, chests along the walls
        int vy = Y - 6;
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 4; z++) for (int y = vy - 1; y <= vy + 4; y++) {
            boolean e = Math.abs(x) == 6 || z == -6 || z == 4 || y == vy - 1 || y == vy + 4;
            f.set(x, y, z, e ? "iron_block" : "water");
        }
        for (int y = vy + 4; y <= fl; y++) f.set(0, y, -2, "ladder[facing=north]"); // the shaft down through the floor
        for (int[] v : VAULTS) if (v[1] == vy) f.set(v[0] - BANK_X, v[1], v[2] - BANK_Z, "chest[facing=north]");
        f.set(0, vy + 3, 0, "soul_lantern[hanging=true]");
        for (int x = -5; x <= 5; x += 10) for (int z = -5; z <= 3; z += 4) f.set(x, vy, z, "gold_block");
    }
}
