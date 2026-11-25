package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * Generated buildings: the three Deep Desert pyramids (a three-floor maze over an abandoned Egyptian underworld),
 * the rare dungeons (only in the 10 Faultline biomes, mostly trial spawners), the five admin-block cores, the sky
 * isles (+ the stair tower and bridges), the illager cities and the abandoned villages of the Deep Forest.
 * Pure functions of the seed and position, like the rest of the generator.
 */
final class Structures {
    private Structures() {}

    static void paint(Terrain t, int bx, int bz, Terrain.Col[] cols, ChunkData data) {
        Canvas cv = new Canvas(data, bx, bz);
        for (int i = 0; i < Layout.PYRAMIDS.length; i++) pyramid(cv, t, i);
        dungeons(cv, t);
        for (Layout.Core c : Layout.CORES) core(cv, t, c);
        skyIsles(cv, t);
        illagerCities(cv, t);
        abandonedVillages(cv, t);
    }

    // ================================================================== PYRAMIDS

    static final int PH = 70;            // half size of the base (141 wide)
    static final int PYR_H = 70;
    static final int[] FLOORS = {1, 9, 17};  // maze floors (y above the base)
    static final int CELL = 4;           // maze cell = 3 corridor + 1 wall

    static int pyramidBase(Terrain t, int i) { return t.column(Layout.PYRAMIDS[i][0], Layout.PYRAMIDS[i][1]).surface; }

    /** The pyramid's whole box incl. the underworld below it (for protection). */
    static int[] pyramidBox(Terrain t, int i) {
        int x = Layout.PYRAMIDS[i][0], z = Layout.PYRAMIDS[i][1], y = pyramidBase(t, i);
        return new int[]{x - PH - 2, y - 70, z - PH - 2, x + PH + 2, y + PYR_H + 2, z + PH + 2};
    }

    /** Maze walls per floor: walls[f][cx][cz] = this cell's east / south walls (bit 1 east, bit 2 south), from a DFS. */
    static int[][][] maze(long seed, int pyr) {
        long key = seed * 31 + pyr;
        return MAZE_CACHE.computeIfAbsent(key, k -> {
            int[][][] out = new int[FLOORS.length][][];
            for (int f = 0; f < FLOORS.length; f++) {
                int half = PH - 4 - FLOORS[f];            // inside the outer wall at that height
                int n = Math.max(3, (2 * half) / CELL);
                int[][] w = new int[n][n];
                for (int[] row : w) Arrays.fill(row, 3);
                boolean[][] seen = new boolean[n][n];
                Random r = new Random(seed ^ (pyr * 7919L) ^ (f * 104729L));
                Deque<int[]> st = new ArrayDeque<>();
                st.push(new int[]{n / 2, n / 2}); seen[n / 2][n / 2] = true;
                while (!st.isEmpty()) {
                    int[] c = st.peek();
                    List<int[]> nb = new ArrayList<>();
                    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : dirs) { int a = c[0] + d[0], b = c[1] + d[1]; if (a >= 0 && b >= 0 && a < n && b < n && !seen[a][b]) nb.add(new int[]{a, b}); }
                    if (nb.isEmpty()) { st.pop(); continue; }
                    int[] nx = nb.get(r.nextInt(nb.size()));
                    if (nx[0] > c[0]) w[c[0]][c[1]] &= ~1; else if (nx[0] < c[0]) w[nx[0]][nx[1]] &= ~1;
                    else if (nx[1] > c[1]) w[c[0]][c[1]] &= ~2; else w[nx[0]][nx[1]] &= ~2;
                    seen[nx[0]][nx[1]] = true; st.push(nx);
                }
                // a few extra openings (loops) so it isn't one long corridor
                for (int q = 0; q < n * n / 9; q++) { int a = r.nextInt(n), b = r.nextInt(n); w[a][b] &= ~(1 << r.nextInt(2)); }
                out[f] = w;
            }
            return out;
        });
    }
    private static final Map<Long, int[][][]> MAZE_CACHE = new ConcurrentHashMap<>();

    static void pyramid(Canvas cv, Terrain t, int i) {
        int px = Layout.PYRAMIDS[i][0], pz = Layout.PYRAMIDS[i][1];
        if (!cv.touches(px - PH - 4, pz - PH - 4, px + PH + 4, pz + PH + 4)) return;
        int y0 = pyramidBase(t, i);
        int[][][] mz = maze(t.seed, i);
        BlockData smooth = bd("minecraft:smooth_sandstone"), cut = bd("minecraft:cut_sandstone"), chis = bd("minecraft:chiseled_sandstone"),
                ss = bd("minecraft:sandstone"), air = WorldGen.Blocks.CAVE_AIR, terra = bd("minecraft:orange_terracotta"),
                gold = bd("minecraft:gold_block"), floorB = bd("minecraft:smooth_sandstone"), lamp = bd("minecraft:sea_lantern");
        String[] glyph = {"minecraft:orange_glazed_terracotta", "minecraft:blue_glazed_terracotta", "minecraft:cyan_glazed_terracotta", "minecraft:light_blue_glazed_terracotta"};
        for (int x = Math.max(cv.bx, px - PH - 3); x <= Math.min(cv.bx + 15, px + PH + 3); x++)
            for (int z = Math.max(cv.bz, pz - PH - 3); z <= Math.min(cv.bz + 15, pz + PH + 3); z++) {
                int dx = x - px, dz = z - pz, e = Math.max(Math.abs(dx), Math.abs(dz));
                if (e > PH) continue;
                int ground = t.column(x, z).surface;
                // foundation down to the ground, the whole body solid first, then hollowed out
                cv.column(x, z, Math.min(ground, y0) - 3, y0, ss);
                int top = y0 + (PH - e);                           // a stepped pyramid: 1 up per 1 in
                if (top > y0 + PYR_H) top = y0 + PYR_H;
                for (int y = y0 + 1; y <= top; y++) {
                    BlockData b = (y - y0) % 10 == 0 ? cut : (y - y0) % 10 == 5 && e == PH - (y - y0) ? terra : smooth;
                    cv.set(x, y, z, b);
                }
                if (e == PH - (top - y0) && (top - y0) % 10 == 0) cv.set(x, top, z, chis);
                if (top >= y0 + PYR_H - 1 && e <= 1) cv.set(x, top, z, gold);   // the gold capstone
                // the maze floors
                for (int f = 0; f < FLOORS.length; f++) {
                    int fy = y0 + FLOORS[f];
                    int half = PH - 4 - FLOORS[f];
                    if (e > half) continue;
                    int n = mz[f].length, ox = px - (n * CELL) / 2, oz = pz - (n * CELL) / 2;
                    int gx = x - ox, gz = z - oz;
                    if (gx < 0 || gz < 0 || gx >= n * CELL || gz >= n * CELL) continue;
                    int cxi = gx / CELL, czi = gz / CELL, ix = gx % CELL, iz = gz % CELL;
                    boolean wall = ix == CELL - 1 && ((mz[f][cxi][czi] & 1) != 0 || iz == CELL - 1)
                            || iz == CELL - 1 && ((mz[f][cxi][czi] & 2) != 0 || ix == CELL - 1);
                    cv.set(x, fy, z, floorB);
                    for (int y = fy + 1; y <= fy + 5; y++) {
                        if (y == fy + 5) { cv.set(x, y, z, cut); continue; }
                        if (wall) {
                            long hh = hash3(t.seed, x, y, z, 0x9A1);
                            cv.set(x, y, z, y == fy + 3 && (hh & 7) == 0 ? bd(glyph[(int) ((hh >>> 3) & 3)]) : y == fy + 2 && (hh & 31) == 1 ? chis : cut);
                        } else cv.set(x, y, z, air);
                    }
                    if (!wall && ix == 1 && iz == 1) {
                        long hh = hash(t.seed ^ f, cxi, czi, 0x9A2 + i);
                        int r = (int) (hh & 255);
                        if (r < 6) cv.set(x, fy + 1, z, bd("minecraft:chest[facing=north]"));
                        else if (r < 9) cv.set(x, fy + 1, z, bd("minecraft:spawner"));
                        else if (r < 22) cv.set(x, fy + 1, z, bd("minecraft:decorated_pot"));
                        else if (r < 30) cv.set(x, fy + 1, z, bd("minecraft:skeleton_skull[rotation=" + (hh >>> 8 & 15) + "]"));
                        else if (r < 34) cv.set(x, fy + 4, z, lamp);
                        else if (r < 40) cv.set(x, fy + 1, z, bd("minecraft:cobweb"));
                    }
                    // stairs up / down in the middle cell (the centre is always open)
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) for (int y = fy + 1; y <= fy + 5; y++) cv.set(x, y, z, air);
                }
                // the entrance: a grand doorway in the north face down a corridor to the first floor
                if (Math.abs(dx) <= 2 && dz < 0 && dz >= -PH) {
                    for (int y = y0 + 2; y <= y0 + 6; y++) cv.set(x, y, z, (Math.abs(dx) == 2 || y == y0 + 6) && dz < -PH + 5 ? chis : air);
                    if (dz >= -PH + 5 && dz < -(PH - 4 - FLOORS[0])) for (int y = y0 + 2; y <= y0 + 5; y++) cv.set(x, y, z, Math.abs(dx) == 2 ? cut : air);
                    cv.set(x, y0 + 1, z, floorB);
                }
                // the stairwell in the centre between floors and down to the underworld
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    for (int y = y0 - 52; y <= y0 + FLOORS[FLOORS.length - 1] + 4; y++) cv.set(x, y, z, air);
                    // a ladder on the north side of the well all the way down
                    if (dz == -1 && dx == 0) for (int y = y0 - 52; y <= y0 + FLOORS[FLOORS.length - 1] + 4; y++) cv.set(x, y, z, bd("minecraft:ladder[facing=south]"));
                }
                if (Math.abs(dx) == 2 && Math.abs(dz) <= 2 || Math.abs(dz) == 2 && Math.abs(dx) <= 2)
                    for (int y = y0 - 52; y <= y0; y++) cv.set(x, y, z, cut);              // the well's lining
            }
        underworld(cv, t, i, px, pz, y0 - 55);
    }

    /** Under each pyramid: a great dark chamber with abandoned Egyptian houses, spawners, trial spawners and vaults. */
    static void underworld(Canvas cv, Terrain t, int i, int px, int pz, int fy) {
        int R = 56, H = 26;
        if (!cv.touches(px - R, pz - R, px + R, pz + R)) return;
        BlockData air = WorldGen.Blocks.CAVE_AIR, sand = bd("minecraft:smooth_sandstone"), cut = bd("minecraft:cut_sandstone");
        for (int x = Math.max(cv.bx, px - R); x <= Math.min(cv.bx + 15, px + R); x++)
            for (int z = Math.max(cv.bz, pz - R); z <= Math.min(cv.bz + 15, pz + R); z++) {
                double d = Layout.dist(x, z, px, pz) / R;
                if (d > 1) continue;
                int ceil = fy + (int) (H * Math.sqrt(1 - d * d)) + 2;
                cv.set(x, fy, z, sand);
                for (int y = fy + 1; y <= ceil; y++) cv.set(x, y, z, air);
                cv.set(x, ceil + 1, z, bd("minecraft:sandstone"));
                if (d > 0.96) for (int y = fy; y <= ceil; y++) cv.set(x, y, z, cut);
                // pillars in a ring
                double a = Math.atan2(z - pz, x - px);
                if (Math.abs(d - 0.8) < 0.035 && Math.abs(Math.sin(a * 6)) < 0.08) for (int y = fy + 1; y <= ceil; y++) cv.set(x, y, z, bd("minecraft:chiseled_sandstone"));
            }
        // abandoned houses round the edge
        for (int k = 0; k < 8; k++) {
            double a = k / 8.0 * Math.PI * 2 + 0.3;
            int hx = px + (int) (Math.cos(a) * 34) - 4, hz = pz + (int) (Math.sin(a) * 34) - 4;
            egyptHouse(cv, t, hx, fy + 1, hz, hash(t.seed, i, k, 0xE91));
        }
        // the obelisk in the middle, trial spawners and vaults round it
        for (int y = fy + 1; y < fy + 18; y++) {
            int w = y < fy + 3 ? 2 : 1;
            for (int dx = -w; dx <= w; dx++) for (int dz = -w; dz <= w; dz++) cv.set(px + dx + 6, y, pz + dz, bd(y > fy + 15 ? "minecraft:gold_block" : "minecraft:chiseled_sandstone"));
        }
        int[][] ts = {{-14, -14}, {14, -14}, {-14, 14}, {14, 14}, {0, -22}, {0, 22}};
        for (int[] p : ts) { cv.set(px + p[0], fy + 1, pz + p[1], bd("minecraft:trial_spawner")); cv.set(px + p[0], fy, pz + p[1], bd("minecraft:chiseled_tuff")); }
        cv.set(px - 4, fy + 1, pz, bd("minecraft:vault[facing=east]"));
        cv.set(px + 16, fy + 1, pz, bd("minecraft:vault[facing=west]"));
        cv.set(px - 20, fy + 1, pz - 6, bd("minecraft:spawner"));
        cv.set(px + 20, fy + 1, pz + 6, bd("minecraft:spawner"));
    }

    static void egyptHouse(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int w = 8, l = 8, H = 5;
        for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < l; dz++) for (int dy = 0; dy <= H; dy++) {
            boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == l - 1;
            long hh = hash3(h, x + dx, y + dy, z + dz, 0xE92);
            BlockData b;
            if (dy == H) b = (hh & 7) == 0 ? WorldGen.Blocks.CAVE_AIR : bd("minecraft:smooth_sandstone_slab");
            else if (edge) b = (hh & 15) == 0 && dy > 1 ? WorldGen.Blocks.CAVE_AIR : bd(dy == 0 ? "minecraft:cut_sandstone" : "minecraft:sandstone");
            else b = WorldGen.Blocks.CAVE_AIR;
            cv.set(x + dx, y + dy, z + dz, b);
        }
        for (int dy = 0; dy < 3; dy++) cv.set(x + w / 2, y + dy, z, WorldGen.Blocks.CAVE_AIR);
        cv.set(x + 1, y, z + l - 2, bd("minecraft:chest[facing=north]"));
        cv.set(x + w - 2, y, z + 1, bd("minecraft:decorated_pot"));
        cv.set(x + w - 2, y, z + l - 2, bd("minecraft:cobweb"));
    }

    static boolean inPyramid(Terrain t, int x, int y, int z) {
        for (int i = 0; i < Layout.PYRAMIDS.length; i++) {
            // the cheap x/z test first: this runs for every block broken / placed / blown up on the server
            if (Math.abs(x - Layout.PYRAMIDS[i][0]) > PH + 2 || Math.abs(z - Layout.PYRAMIDS[i][1]) > PH + 2) continue;
            int[] b = pyramidBox(t, i);
            if (y >= b[1] && y <= b[4]) return true;
        }
        return false;
    }

    // ================================================================== DUNGEONS

    static final int DUNGEON_CELL = 336;

    /** A dungeon's anchor (x, y, z) in its cell, or null. Only in the 10 Faultline biomes; about 1 in 3 cells. */
    static int[] dungeonAt(Terrain t, int ci, int cj) {
        long h = hash(t.seed, ci, cj, 0xD6E);
        if ((h & 255) > 85) return null;
        int x = ci * DUNGEON_CELL + 40 + (int) ((h >>> 8) % (DUNGEON_CELL - 80)), z = cj * DUNGEON_CELL + 40 + (int) ((h >>> 20) % (DUNGEON_CELL - 80));
        if (Math.hypot(x, z) > Layout.BORDER - 60) return null;
        Terrain.Col c = t.column(x, z);
        if (!c.biome.faultlineTen()) return null;
        if (Sites.near(x, z, 80)) return null;
        for (int[] p : Layout.PYRAMIDS) if (Layout.dist(x, z, p[0], p[1]) < 140) return null;
        int y;
        if (c.sea) {
            if (c.abyssTop == Integer.MIN_VALUE || c.surface - c.abyssTop < 40) return null;
            y = c.abyssTop + 12;                      // in the crust between the abyss and the sea floor
        } else y = c.surface - 38 - (int) ((h >>> 32) % 30);
        return new int[]{x, y, z, (int) ((h >>> 40) & 0xFFFF)};
    }

    static void dungeons(Canvas cv, Terrain t) {
        int R = 30;
        int c0 = Math.floorDiv(cv.bx - R, DUNGEON_CELL), c1 = Math.floorDiv(cv.bx + 15 + R, DUNGEON_CELL);
        int d0 = Math.floorDiv(cv.bz - R, DUNGEON_CELL), d1 = Math.floorDiv(cv.bz + 15 + R, DUNGEON_CELL);
        for (int ci = c0; ci <= c1; ci++) for (int cj = d0; cj <= d1; cj++) {
            int[] p = dungeonAt(t, ci, cj);
            if (p == null || !cv.touches(p[0] - R, p[2] - R, p[0] + R, p[2] + R)) continue;
            dungeon(cv, t, p[0], p[1], p[2], p[3]);
        }
    }

    /** Spawner spots of a dungeon (relative), the same list the runtime uses to set their mobs. */
    static final int[][] DUNGEON_TRIALS = {{0, 0}, {-17, 0}, {17, 0}, {0, -17}};
    static final int[][] DUNGEON_SPAWNERS = {{0, 17}};

    static void dungeon(Canvas cv, Terrain t, int x, int y, int z, int v) {
        BlockData air = WorldGen.Blocks.CAVE_AIR, wall = bd("minecraft:tuff_bricks"), floor = bd("minecraft:polished_tuff"),
                trim = bd("minecraft:chiseled_tuff_bricks"), copper = bd("minecraft:waxed_oxidized_cut_copper"), bulb = bd("minecraft:waxed_copper_bulb[lit=true]");
        // the hall + four rooms joined by corridors (a plus shape)
        int[][] rooms = {{0, 0, 8}, {-17, 0, 5}, {17, 0, 5}, {0, -17, 5}, {0, 17, 5}};
        for (int[] r : rooms) {
            int rx = x + r[0], rz = z + r[1], s = r[2], H = s == 8 ? 8 : 6;
            for (int dx = -s - 1; dx <= s + 1; dx++) for (int dz = -s - 1; dz <= s + 1; dz++) for (int dy = -1; dy <= H + 1; dy++) {
                boolean shell = Math.abs(dx) == s + 1 || Math.abs(dz) == s + 1 || dy == -1 || dy == H + 1;
                BlockData b = shell ? (dy == -1 ? floor : (dy == H + 1 ? copper : (Math.abs(dx) == s + 1 && Math.abs(dz) == s + 1 ? trim : wall))) : air;
                cv.set(rx + dx, y + dy, rz + dz, b);
            }
            for (int k : new int[]{-s + 1, s - 1}) for (int k2 : new int[]{-s + 1, s - 1}) cv.set(rx + k, y + H, rz + k2, bulb);
        }
        for (int a = -12; a <= 12; a++) for (int w = -1; w <= 1; w++) for (int dy = 0; dy <= 3; dy++) {
            cv.set(x + a, y + dy, z + w, air); cv.set(x + w, y + dy, z + a, air);
        }
        for (int[] p : DUNGEON_TRIALS) { cv.set(x + p[0], y, z + p[1], bd("minecraft:trial_spawner")); }
        for (int[] p : DUNGEON_SPAWNERS) cv.set(x + p[0], y, z + p[1], bd("minecraft:spawner"));
        cv.set(x + 3, y, z + 3, bd("minecraft:vault[facing=north]"));
        cv.set(x - 3, y, z - 3, bd("minecraft:chest[facing=south]"));
        cv.set(x + 17 + 3, y, z + 3, bd("minecraft:chest[facing=west]"));
        cv.set(x - 3, y, z + 17 - 3, bd("minecraft:decorated_pot"));
        // a shaft up to the surface so it can be found (a cracked well head with a ladder)
        if ((v & 1) == 0) {
            int sx = x + 17, sz = z;
            int top = t.column(sx, sz).surface;
            for (int yy = y; yy <= top + 1; yy++) {
                cv.set(sx, yy, sz + 2, bd("minecraft:ladder[facing=north]"));
                cv.set(sx, yy, sz + 3, wall);
                cv.set(sx + 1, yy, sz + 2, wall); cv.set(sx - 1, yy, sz + 2, wall);
            }
            cv.set(sx, top + 1, sz + 1, bd("minecraft:mossy_stone_bricks"));
        }
    }

    // ================================================================== ADMIN CORES

    static int coreBase(Terrain t, Layout.Core c) { return c.y() != 0 ? c.y() : t.column(c.x(), c.z()).surface + 1; }

    /** A faction's protected hall: bedrock shell, command block pillars, a structure/jigsaw floor, lit by light blocks. */
    static void core(Canvas cv, Terrain t, Layout.Core c) {
        int S = 11, H = 13;
        if (!cv.touches(c.x() - S - 2, c.z() - S - 2, c.x() + S + 2, c.z() + S + 2)) return;
        int y = coreBase(t, c);
        BlockData bed = bd("minecraft:bedrock"), air = WorldGen.Blocks.AIR, light = bd("minecraft:light[level=15]");
        for (int dx = -S; dx <= S; dx++) for (int dz = -S; dz <= S; dz++) {
            int x = c.x() + dx, z = c.z() + dz;
            boolean edge = Math.abs(dx) == S || Math.abs(dz) == S;
            if (c.y() == 0) cv.column(x, z, y - 8, y - 1, bed);          // its foundation
            for (int dy = 0; dy <= H; dy++) {
                BlockData b;
                if (dy == 0) b = (dx + dz & 1) == 0 ? bd("minecraft:structure_block") : bd("minecraft:jigsaw[orientation=up_north]");
                else if (dy == H) b = bed;
                else if (edge) {
                    boolean window = dy >= 4 && dy <= 8 && (Math.abs(dx) % 4 == 2 || Math.abs(dz) % 4 == 2) && !(Math.abs(dx) == S && Math.abs(dz) == S);
                    b = window ? bd("minecraft:command_block[facing=up]") : bed;
                } else if ((Math.abs(dx) == S - 3 && Math.abs(dz) == S - 3)) {
                    b = bd(dy % 3 == 0 ? "minecraft:repeating_command_block[facing=up]" : dy % 3 == 1 ? "minecraft:chain_command_block[facing=up]" : "minecraft:command_block[facing=up]");
                } else b = dy == H - 1 && (dx % 3 == 0 && dz % 3 == 0) ? light : air;
                cv.set(x, y + dy, z, b);
            }
        }
        // altar: jigsaw + structure blocks under a bedrock crown
        for (int dy = 1; dy <= 3; dy++) cv.set(c.x(), y + dy, c.z(), bd(dy == 3 ? "minecraft:structure_block" : "minecraft:jigsaw[orientation=up_north]"));
        cv.set(c.x(), y + 4, c.z(), bed);
        // the entrance (south), 3 wide and 4 tall
        for (int dx = -1; dx <= 1; dx++) for (int dy = 1; dy <= 4; dy++) cv.set(c.x() + dx, y + dy, c.z() + S, air);
        // a floating core gets its own little island under it
        if (c.y() != 0) skyIsland(cv, t, c.x(), c.z(), y - 1, S + 5, 0x51C);
        shrine(cv, t, c, y, S, H);
    }

    static boolean inCore(Terrain t, int x, int y, int z) {
        for (Layout.Core c : Layout.CORES) {
            if (Math.abs(x - c.x()) > 15 || Math.abs(z - c.z()) > 15) continue;
            int b = coreBase(t, c);
            if (y >= b - 9 && y <= b + 26) return true;
        }
        return false;
    }

    /** Per faction: wall, trim, roof, stair, lamp, the glass of its colour. */
    static String[] theme(String faction) {
        return switch (faction) {
            case "mountain" -> new String[]{"minecraft:stone_bricks", "minecraft:polished_deepslate", "minecraft:deepslate_tiles", "minecraft:stone_brick_stairs", "minecraft:lantern", "minecraft:red_stained_glass", "minecraft:red_wool"};
            case "jungle" -> new String[]{"minecraft:mossy_stone_bricks", "minecraft:stripped_jungle_log", "minecraft:jungle_planks", "minecraft:mossy_stone_brick_stairs", "minecraft:lantern", "minecraft:lime_stained_glass", "minecraft:green_wool"};
            case "ice" -> new String[]{"minecraft:packed_ice", "minecraft:blue_ice", "minecraft:snow_block", "minecraft:quartz_stairs", "minecraft:soul_lantern", "minecraft:light_blue_stained_glass", "minecraft:light_blue_wool"};
            case "fibers" -> new String[]{"minecraft:mud_bricks", "minecraft:stripped_mangrove_log", "minecraft:mangrove_planks", "minecraft:mud_brick_stairs", "minecraft:soul_lantern", "minecraft:purple_stained_glass", "minecraft:purple_wool"};
            default -> new String[]{"minecraft:quartz_bricks", "minecraft:chiseled_quartz_block", "minecraft:smooth_quartz", "minecraft:quartz_stairs", "minecraft:lantern", "minecraft:yellow_stained_glass", "minecraft:yellow_wool"};
        };
    }

    /**
     * The faction's SHRINE round its admin core (2.0 audit: the core was a bare bedrock cube sticking out of the hillside).
     * A skin of the faction's own stone over the bedrock with tall slits that show the command blocks behind, four corner
     * towers with lamps, a plinth with steps up to the doorway, a porch, a stepped roof rising to a lit lantern of the
     * faction's colour, and its colours hanging beside the door. The bedrock room inside is untouched.
     */
    static void shrine(Canvas cv, Terrain t, Layout.Core c, int y, int S, int H) {
        String[] th = theme(c.faction());
        BlockData wall = bd(th[0]), trim = bd(th[1]), roof = bd(th[2]), lamp = bd(th[4]), glass = bd(th[5]), cloth = bd(th[6]), air = WorldGen.Blocks.AIR;
        int O = S + 1;
        for (int dx = -O - 3; dx <= O + 3; dx++) for (int dz = -O - 3; dz <= O + 3; dz++) {
            int x = c.x() + dx, z = c.z() + dz;
            if (!cv.in(x, y, z)) continue;
            int ax = Math.abs(dx), az = Math.abs(dz), r = Math.max(ax, az);
            boolean door = ax <= 1 && dz > 0;
            boolean tower = ax >= O - 1 && az >= O - 1 && r <= O + 1 && ax >= O - 1 && az >= O - 1;
            if (r > O + 1 + (tower ? 0 : 2)) continue;
            if (c.y() == 0 && r >= O) cv.column(x, z, y - 9, y - 1, r == O || tower ? wall : trim);                 // footing down into the slope
            if (tower && r >= O) {                                               // the four corner towers
                for (int dy = 0; dy <= H + 3; dy++) cv.set(x, y + dy, z, dy % 5 == 4 ? trim : wall);
                if (ax == O && az == O) { cv.set(x, y + H + 4, z, trim); cv.set(x, y + H + 5, z, lamp); }
                else cv.set(x, y + H + 4, z, (dx + dz & 1) == 0 ? trim : air);                                       // battlements
                continue;
            }
            if (r == O) {                                                        // the skin, with slits over the command blocks
                for (int dy = 0; dy <= H; dy++) {
                    boolean slit = dy >= 4 && dy <= 8 && (ax == O ? az % 4 == 2 : ax % 4 == 2);
                    if (door && dy >= 1 && dy <= 4) { cv.set(x, y + dy, z, air); continue; }
                    if (door && dy == 5) { cv.set(x, y + dy, z, trim); continue; }
                    cv.set(x, y + dy, z, slit ? air : dy == 0 || dy == H || dy == 3 || dy == 9 ? trim : wall);
                }
            } else if (r == O + 1) {                                             // the plinth
                cv.set(x, y, z, trim);
                if (door) { for (int dy = 1; dy <= 4; dy++) cv.set(x, y + dy, z, air); }
                else if ((ax == 2 && dz > 0)) { for (int dy = 1; dy <= 5; dy++) cv.set(x, y + dy, z, trim); cv.set(x, y + 6, z, lamp); }   // porch posts
                else if ((ax + az) % 6 == 0) cv.set(x, y + 1, z, trim);
            } else if (r == O + 2 && c.y() == 0) {
                cv.set(x, y - 1, z, trim);
                if (door) for (int dy = 0; dy <= 4; dy++) cv.set(x, y + dy, z, air);
            } else if (r == O + 3 && c.y() == 0 && door) {
                for (int dy = -1; dy <= 3; dy++) cv.set(x, y + dy, z, air);
                cv.set(x, y - 2, z, trim);
            }
        }
        // the faction's colours beside the door
        for (int side = -1; side <= 1; side += 2) for (int dy = 6; dy <= 10; dy++) cv.set(c.x() + side * 3, y + dy, c.z() + O + 1, dy == 10 ? bd(th[1]) : cloth);
        // the stepped roof and its lantern
        for (int k = 1; k <= 6; k++) {
            int hs = O - k * 2 + 1;
            if (hs < 1) break;
            for (int dx = -hs; dx <= hs; dx++) for (int dz = -hs; dz <= hs; dz++) {
                boolean rim = Math.abs(dx) == hs || Math.abs(dz) == hs;
                cv.set(c.x() + dx, y + H + k, c.z() + dz, rim ? ((dx + dz & 3) == 0 ? trim : roof) : roof);
            }
        }
        int top = y + H + 6;
        for (int dy = 1; dy <= 3; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            boolean mid = dx == 0 && dz == 0, corner = dx != 0 && dz != 0;
            cv.set(c.x() + dx, top + dy, c.z() + dz, mid ? bd(dy == 3 ? th[1] : "minecraft:sea_lantern") : corner ? trim : dy == 3 ? trim : glass);
        }
        cv.set(c.x(), top + 4, c.z(), trim); cv.set(c.x(), top + 5, c.z(), bd("minecraft:lightning_rod"));
    }

    // ================================================================== SKY ISLES

    record Isle(int x, int z, int top, int r, long h) {}

    /** The designated islands (under the sky faction's builds), then the random ones round them. */
    static final int[][] SKY_FIXED = {{1655, 160, 240, 60}, {1290, 120, 250, 38}, {1520, 380, 232, 22}};

    static List<Isle> isles(Terrain t, int x0, int z0, int x1, int z1) {
        List<Isle> out = new ArrayList<>();
        for (int[] f : SKY_FIXED) out.add(new Isle(f[0], f[1], f[2], f[3], hash(t.seed, f[0], f[1], 0x5F0)));
        int size = 64;
        for (int ci = Math.floorDiv(x0 - 40, size); ci <= Math.floorDiv(x1 + 40, size); ci++)
            for (int cj = Math.floorDiv(z0 - 40, size); cj <= Math.floorDiv(z1 + 40, size); cj++) {
                long h = hash(t.seed, ci, cj, 0x5F1);
                if ((h & 255) > 190) continue;
                int x = ci * size + (int) ((h >>> 8) % size), z = cj * size + (int) ((h >>> 20) % size);
                if (t.surfaceWeight(B.SKY_ISLES, x, z) < 0.6) continue;
                boolean clash = false;
                for (int[] f : SKY_FIXED) if (Layout.dist(x, z, f[0], f[1]) < f[3] + 34) clash = true;
                if (Math.abs(x - 1450) < 130 && Math.abs(z - 240) < 135) clash = true;   // the Greek statue (206 x 213)
                if (Layout.dist(x, z, 1380, 430) < 40) clash = true;                   // the sky core's island
                if (clash) continue;
                out.add(new Isle(x, z, 226 + (int) ((h >>> 32) % 66), 9 + (int) ((h >>> 40) % 22), h));
            }
        return out;
    }

    static boolean skyIsleAt(Terrain t, int x, int y, int z) {
        if (t.surfaceWeight(B.SKY_ISLES, x, z) < 0.3) return false;
        for (Isle i : isles(t, x, z, x, z)) {
            if (Layout.dist(x, z, i.x(), i.z()) <= i.r() + 4 && y >= i.top() - i.r() * 1.3 && y <= i.top() + 40) return true;
        }
        return false;
    }

    static void skyIsles(Canvas cv, Terrain t) {
        if (t.surfaceWeight(B.SKY_ISLES, cv.bx + 8, cv.bz + 8) < 0.2 && !cv.touches(1240, 70, 1730, 420)) return;
        for (Isle i : isles(t, cv.bx, cv.bz, cv.bx + 15, cv.bz + 15)) {
            if (!cv.touches(i.x() - i.r() - 8, i.z() - i.r() - 8, i.x() + i.r() + 8, i.z() + i.r() + 8)) continue;
            skyIsland(cv, t, i.x(), i.z(), i.top(), i.r(), i.h());
        }
        // the stair tower from the crater floor up to the castle island, and bridges between the fixed islands
        stairTower(cv, t, 1588, 200, 108, 240);
        bridge(cv, t, 1290 + 36, 120, 250, 1655 - 58, 160, 240);
        bridge(cv, t, 1655 - 20, 160 + 56, 240, 1520, 380 - 20, 232);
    }

    static void skyIsland(Canvas cv, Terrain t, int x, int z, int top, int r, long h) {
        BlockData grass = bd("minecraft:grass_block"), dirt = bd("minecraft:dirt"), stone = bd("minecraft:stone"), moss = bd("minecraft:moss_block");
        int R = r + 4;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            int wx = x + dx, wz = z + dz;
            if (!cv.inXZ(wx, wz)) continue;
            double wob = 0.18 * Math.sin(Math.atan2(dz, dx) * 5 + (h & 7)) + 0.08 * Math.sin(Math.atan2(dz, dx) * 13 + (h >>> 3 & 7));
            double d = Math.hypot(dx, dz) / (r * (1 + wob));
            if (d > 1) continue;
            int surf = top + (int) Math.round(2 * (1 - d * d));
            int bottom = top - (int) Math.round(r * 1.25 * Math.pow(1 - d, 0.8)) - 2;
            for (int y = bottom; y <= surf; y++) cv.set(wx, y, wz, y == surf ? grass : y > surf - 3 ? dirt : ((hash3(h, wx, y, wz, 1) & 15) == 0 ? moss : stone));
            long hh = hash(h, wx, wz, 0x5F2);
            if ((hh & 63) == 0 && d < 0.8) smallTree(cv, wx, surf + 1, wz, hh);
            else if ((hh & 7) == 1) cv.air(wx, surf + 1, wz, bd("minecraft:short_grass"));
            else if ((hh & 31) == 2) cv.air(wx, surf + 1, wz, bd((hh >>> 8 & 1) == 0 ? "minecraft:cornflower" : "minecraft:oxeye_daisy"));
            if (d > 0.85 && (hh >>> 12 & 7) == 0) for (int k = 1; k <= 1 + (hh >>> 16 & 7); k++) cv.air(wx, bottom - k, wz, bd("minecraft:hanging_roots"));
        }
    }

    static void smallTree(Canvas cv, int x, int y, int z, long h) {
        int H = 4 + (int) (h >>> 20 & 3);
        String log = (h >>> 24 & 1) == 0 ? "minecraft:birch_log" : "minecraft:oak_log", lv = log.replace("_log", "_leaves[persistent=true]");
        for (int dy = 0; dy < H; dy++) cv.set(x, y + dy, z, bd(log));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = H - 2; dy <= H + 1; dy++) {
            if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - H + 1) > 3) continue;
            cv.air(x + dx, y + dy, z + dz, bd(lv));
        }
    }

    static void stairTower(Canvas cv, Terrain t, int x, int z, int y0, int y1) {
        if (!cv.touches(x - 5, z - 5, x + 5, z + 5)) return;
        BlockData brick = bd("minecraft:stone_bricks"), air = WorldGen.Blocks.AIR;
        for (int y = y0; y <= y1; y++) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 4.4) continue;
                if (d > 3.5) cv.set(x + dx, y, z + dz, (y % 6 == 0 && (dx == 0 || dz == 0)) ? bd("minecraft:tinted_glass") : brick);
                else if (d < 1.2) cv.set(x + dx, y, z + dz, brick);
                else {
                    double a = Math.atan2(dz, dx); if (a < 0) a += Math.PI * 2;
                    double step = ((y - y0) % 8) / 8.0 * Math.PI * 2;
                    double diff = Math.abs(a - step);
                    cv.set(x + dx, y, z + dz, diff < 0.8 || diff > Math.PI * 2 - 0.8 ? bd("minecraft:stone_brick_slab[type=top]") : air);
                }
            }
        }
        for (int dy = 1; dy <= 3; dy++) cv.set(x, y0 + dy, z - 4, air);  // door at the bottom
        for (int dy = 1; dy <= 3; dy++) cv.set(x + 4, y1 + dy - 2, z, air); // and out at the top toward the island
    }

    static void bridge(Canvas cv, Terrain t, int x0, int z0, int y0, int x1, int z1, int y1) {
        int n = (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0));
        int minX = Math.min(x0, x1) - 3, maxX = Math.max(x0, x1) + 3, minZ = Math.min(z0, z1) - 3, maxZ = Math.max(z0, z1) + 3;
        if (!cv.touches(minX, minZ, maxX, maxZ)) return;
        for (int i = 0; i <= n; i++) {
            double f = i / (double) n;
            int x = (int) Math.round(x0 + (x1 - x0) * f), z = (int) Math.round(z0 + (z1 - z0) * f);
            int y = (int) Math.round(y0 + (y1 - y0) * f - 3 * Math.sin(f * Math.PI));
            for (int w = -1; w <= 1; w++) {
                int bx = x + (Math.abs(x1 - x0) < Math.abs(z1 - z0) ? w : 0), bz = z + (Math.abs(x1 - x0) < Math.abs(z1 - z0) ? 0 : w);
                cv.set(bx, y, bz, bd("minecraft:spruce_slab[type=top]"));
                if (w != 0 && i % 2 == 0) cv.set(bx, y + 1, bz, bd("minecraft:spruce_fence"));
                if (w != 0 && i % 10 == 0) { cv.set(bx, y + 2, bz, bd("minecraft:spruce_fence")); cv.set(bx, y + 3, bz, bd("minecraft:lantern")); }
            }
        }
    }

    // ================================================================== ILLAGER CITIES (Deep Forest)

    static int[][] cities() {
        List<int[]> out = new ArrayList<>();
        for (Layout.Blob b : Layout.SURFACE) if (b.biome() == B.DEEP_FOREST) out.add(new int[]{(int) b.x() + 40, (int) b.z() + 30});
        return out.toArray(new int[0][]);
    }

    static final int CITY_R = 44;

    static void illagerCities(Canvas cv, Terrain t) {
        for (int[] c : cities()) {
            if (!cv.touches(c[0] - CITY_R - 2, c[1] - CITY_R - 2, c[0] + CITY_R + 2, c[1] + CITY_R + 2)) continue;
            int y = t.column(c[0], c[1]).surface;
            BlockData log = bd("minecraft:dark_oak_log"), plank = bd("minecraft:dark_oak_planks"), cob = bd("minecraft:cobblestone"),
                    air = WorldGen.Blocks.AIR, path = bd("minecraft:dirt_path");
            for (int x = Math.max(cv.bx, c[0] - CITY_R); x <= Math.min(cv.bx + 15, c[0] + CITY_R); x++)
                for (int z = Math.max(cv.bz, c[1] - CITY_R); z <= Math.min(cv.bz + 15, c[1] + CITY_R); z++) {
                    int dx = x - c[0], dz = z - c[1], e = Math.max(Math.abs(dx), Math.abs(dz));
                    // levelled yard, cleared of trees
                    cv.column(x, z, y - 4, y, bd("minecraft:dirt"));
                    cv.set(x, y, z, Math.abs(dx) <= 1 || Math.abs(dz) <= 1 ? path : bd("minecraft:coarse_dirt"));
                    cv.box(x, y + 1, z, x, y + 40, z, air);
                    // the palisade
                    if (e == CITY_R) {
                        boolean gate = Math.abs(dx) <= 2 && dz == CITY_R;
                        for (int dy = 1; dy <= 7; dy++) cv.set(x, y + dy, z, gate ? air : log);
                        if (!gate && (x + z & 1) == 0) cv.set(x, y + 8, z, bd("minecraft:dark_oak_fence"));
                    }
                }
            // houses
            int[][] houses = {{-28, -28}, {14, -30}, {-30, 10}, {20, 14}, {-12, 24}, {26, -8}};
            for (int k = 0; k < houses.length; k++) illagerHouse(cv, t, c[0] + houses[k][0], y + 1, c[1] + houses[k][1], hash(t.seed, k, c[0], 0x111));
            // the watchtower (pillager-outpost style) in the middle
            for (int dy = 0; dy < 22; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3, corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                int x = c[0] + dx - 8, z = c[1] + dz - 8, yy = y + 1 + dy;
                if (dy == 21) cv.set(x, yy, z, edge ? bd("minecraft:dark_oak_slab") : plank);   // a fence drawn here has no connections: loose posts
                else if (corner) cv.set(x, yy, z, log);
                else if (edge) cv.set(x, yy, z, dy % 7 == 3 && (dx == 0 || dz == 0) ? air : (dy < 6 ? cob : plank));
                else cv.set(x, yy, z, dy % 7 == 0 ? plank : air);
                if (!edge && dx == 0 && dz == 2 && dy < 21) cv.set(x, yy, z, bd("minecraft:ladder[facing=north]"));
            }
            cv.set(c[0] - 8, y + 23, c[1] - 8, bd("minecraft:white_banner"));
            // cages and targets
            for (int k = 0; k < 2; k++) {
                int x = c[0] + 6 + k * 6, z = c[1] - 18;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 3; dy++) {
                    boolean e = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    cv.set(x + dx, y + dy, z + dz, dy == 3 ? plank : e ? bd("minecraft:waxed_oxidized_copper_grate") : air);
                }
            }
            cv.set(c[0] + 20, y + 1, c[1] + 30, bd("minecraft:target"));
            cv.set(c[0] + 22, y + 1, c[1] + 30, bd("minecraft:target"));
        }
    }

    static void illagerHouse(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int w = 9, l = 7, H = 5;
        for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < l; dz++) {
            for (int dy = -1; dy <= H + 2; dy++) {
                boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == l - 1, corner = (dx == 0 || dx == w - 1) && (dz == 0 || dz == l - 1);
                BlockData b;
                if (dy == -1) b = bd("minecraft:cobblestone");
                else if (dy >= H) { int roofLevel = Math.min(dz, l - 1 - dz); b = dy - H <= roofLevel ? bd("minecraft:dark_oak_planks") : WorldGen.Blocks.AIR; if (dy - H < roofLevel && !edge) b = WorldGen.Blocks.AIR; }
                else if (corner) b = bd("minecraft:dark_oak_log");
                else if (edge) b = dy == 2 && (dx == 3 || dx == 5) ? bd("minecraft:glass") : bd(dy == 0 ? "minecraft:cobblestone" : "minecraft:dark_oak_planks");
                else b = WorldGen.Blocks.AIR;
                cv.set(x + dx, y + dy, z + dz, b);
            }
        }
        cv.set(x + 4, y, z, WorldGen.Blocks.AIR); cv.set(x + 4, y + 1, z, WorldGen.Blocks.AIR);
        cv.set(x + 1, y, z + l - 2, bd("minecraft:chest[facing=north]"));
        cv.set(x + w - 2, y, z + l - 2, bd("minecraft:crafting_table"));
        cv.set(x + w - 2, y, z + 1, bd("minecraft:fletching_table"));
    }

    // ================================================================== ABANDONED VILLAGES (Deep Forest)

    static void abandonedVillages(Canvas cv, Terrain t) {
        int size = 260, R = 40;
        for (int ci = Math.floorDiv(cv.bx - R, size); ci <= Math.floorDiv(cv.bx + 15 + R, size); ci++)
            for (int cj = Math.floorDiv(cv.bz - R, size); cj <= Math.floorDiv(cv.bz + 15 + R, size); cj++) {
                long h = hash(t.seed, ci, cj, 0xAB1);
                if ((h & 255) > 150) continue;
                int x = ci * size + 50 + (int) ((h >>> 8) % (size - 100)), z = cj * size + 50 + (int) ((h >>> 20) % (size - 100));
                if (!cv.touches(x - R, z - R, x + R, z + R)) continue;
                Terrain.Col c = t.column(x, z);
                if (c.biome != B.DEEP_FOREST || c.sea) continue;
                boolean nearCity = false;
                for (int[] city : cities()) if (Layout.dist(x, z, city[0], city[1]) < 120) nearCity = true;
                if (nearCity) continue;
                // a well and 5 huts round it
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    boolean e = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    int yy = t.column(x + dx, z + dz).surface;
                    cv.set(x + dx, yy, z + dz, e ? bd("minecraft:mossy_cobblestone") : bd("minecraft:water"));
                }
                for (int k = 0; k < 5; k++) {
                    double a = k / 5.0 * Math.PI * 2 + (h & 7);
                    int hx = x + (int) (Math.cos(a) * 22) - 3, hz = z + (int) (Math.sin(a) * 22) - 3;
                    int yy = t.column(hx + 3, hz + 3).surface + 1;
                    for (int dx = -1; dx <= 7; dx++) for (int dz = -1; dz <= 6; dz++) { cv.column(hx + dx, hz + dz, yy - 3, yy - 1, bd("minecraft:dirt")); cv.box(hx + dx, yy, hz + dz, hx + dx, yy + 18, hz + dz, WorldGen.Blocks.AIR); }
                    Decor.hut(cv, hx, yy, hz, hash(h, k, 0, 0xAB2));
                }
            }
    }
}
