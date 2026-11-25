package net.faultlinesmp.world;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.WorldInfo;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;

/**
 * Carved spaces: the faction caverns (Fibers sites, the ice arena) with spiral tunnels down to them, the cathedral
 * canyon's ramp, and keeping noise caves out from under the faction builds.
 */
final class Shapes {
    private Shapes() {}

    /** A spiral tunnel from the surface down to a cavern: centre, helix radius, bottom y. */
    record Spiral(int x, int z, int bottom) {}

    static final Spiral[] SPIRALS = {
            new Spiral(290 + 150, -690, 44),        // into the Fibers faction cavern (east side)
            new Spiral(1790 - 48, 1210, 136),       // into the ice arena cavern
    };
    static final int HELIX_R = 9, PITCH = 9;

    /** How far a great stalactite hangs down over this column (0 = none here). One per 29-block cell, 8-40 long. */
    static double stalactite(Terrain t, int x, int z) {
        int size = 29;
        double best = 0;
        int ci0 = Math.floorDiv(x - 9, size), ci1 = Math.floorDiv(x + 9, size), cj0 = Math.floorDiv(z - 9, size), cj1 = Math.floorDiv(z + 9, size);
        for (int ci = ci0; ci <= ci1; ci++) for (int cj = cj0; cj <= cj1; cj++) {
            long h = WorldGen.hash(t.seed, ci, cj, 0xCA8);
            if ((h & 3) == 0) continue;
            double sx = ci * size + (h >>> 8) % size, sz = cj * size + (h >>> 20) % size;
            double len = 8 + (h >>> 32) % 33, rad = 3 + (h >>> 40) % 5;
            double dd = Math.hypot(x - sx, z - sz) / rad;
            if (dd < 1) best = Math.max(best, len * Math.pow(1 - dd, 1.4));
        }
        return best;
    }

    static void carve(Terrain t, WorldInfo info, int bx, int bz, Terrain.Col[] cols, ChunkData data) {
        Canvas cv = new Canvas(data, bx, bz);

        // caverns
        for (Layout.Cavern c : Layout.CAVERNS) {
            if (!cv.touches(c.x() - c.r(), c.z() - c.r(), c.x() + c.r(), c.z() + c.r())) continue;
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = bx + lx, z = bz + lz;
                double d = Layout.dist(x, z, c.x(), c.z()) / c.r();
                if (d >= 1) continue;
                long h = WorldGen.hash(t.seed, x, z, 0xCA7);
                int floor = c.floor() + (d > 0.8 ? (int) ((d - 0.8) * 30) : 0) + (int) (h & 1);
                int ceil = (int) (c.floor() + (c.ceil() - c.floor()) * Math.sqrt(1 - d * d)) - (int) ((h >>> 3) & 3);
                // 2.0 (the owner's screenshot: the roof was one smooth, featureless dome): the roof rolls, and great
                // stalactites hang from it. Not over the faction builds (the tall statue reaches nearly to the roof).
                if (c.r() > 100 && !Sites.near(x, z, 12)) {
                    ceil -= (int) Math.round(7 + 7 * t.fbmPublic(x / 31.0 + 90, z / 31.0) + 3 * t.fbmPublic(x / 9.0, z / 9.0 + 60));
                    ceil -= (int) Math.round(stalactite(t, x, z));
                    if (ceil < floor + 9) ceil = floor + 9;
                }
                for (int y = floor + 1; y <= ceil; y++) data.setBlock(lx, y, lz, air);
            }
        }
        // spiral tunnels (surface -> cavern), lit with lanterns on the inner wall
        for (Spiral s : SPIRALS) {
            int R = HELIX_R + 4;
            if (!cv.touches(s.x() - R, s.z() - R, s.x() + R, s.z() + R)) continue;
            int top = t.column(s.x(), s.z()).surface + 2;
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = bx + lx, z = bz + lz;
                double dx = x - s.x(), dz = z - s.z(), rr = Math.hypot(dx, dz);
                if (Math.abs(rr - HELIX_R) > 2.6) continue;
                double a = Math.atan2(dz, dx);
                if (a < 0) a += Math.PI * 2;
                double base = top - a / (Math.PI * 2) * PITCH;             // first turn's height at this angle
                for (int k = 0; ; k++) {
                    double yc = base - k * PITCH;
                    if (yc < s.bottom() - 3) break;
                    int y0 = (int) Math.floor(yc - 1), y1 = (int) Math.floor(yc + 2);
                    for (int y = Math.max(s.bottom(), y0); y <= y1; y++) data.setBlock(lx, y, lz, air);
                    data.setBlock(lx, Math.max(s.bottom() - 1, y0 - 1), lz, bd("minecraft:polished_deepslate"));
                    if (Math.abs(rr - (HELIX_R - 2.4)) < 0.6 && ((int) (a * 10) % 9 == 0)) data.setBlock(lx, y1, lz, bd("minecraft:lantern[hanging=true]"));
                }
            }
        }
        // canyon walls: ice and snow instead of bare stone
        if (cv.touches(Layout.CANYON_X - Layout.CANYON_HX, Layout.CANYON_Z - Layout.CANYON_HZ, Layout.CANYON_X + Layout.CANYON_HX, Layout.CANYON_Z + Layout.CANYON_HZ)) {
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = bx + lx, z = bz + lz;
                if (!Terrain.inCanyon(x, z)) continue;
                int f = Math.min(Terrain.canyonFloor(x, z), cols[lz * 16 + lx].surface);
                long h = WorldGen.hash(t.seed, x, z, 0x1CE);
                if (f <= Layout.CANYON_FLOOR) {
                    // the floor: snow with frozen pools and veins of clear ice, drifts on top
                    double n = Math.sin(x * 0.07 + Math.sin(z * 0.05) * 2) + Math.sin(z * 0.09 - x * 0.03);
                    String top = n > 1.25 ? "minecraft:blue_ice" : n > 0.95 ? "minecraft:packed_ice" : n < -1.5 ? "minecraft:packed_ice" : "minecraft:snow_block";
                    data.setBlock(lx, f, lz, bd(top));
                    data.setBlock(lx, f - 1, lz, bd("minecraft:packed_ice"));
                    if (top.endsWith("snow_block") && (h & 7) < 3) data.setBlock(lx, f + 1, lz, bd("minecraft:snow[layers=" + (1 + (int) ((h >>> 5) % 3)) + "]"));
                    continue;
                }
                // the wall: bands of packed ice, blue ice, snow and frozen rock, deep enough that no bare stone shows
                int deep = 0;
                for (int[] nb : NEAR) deep = Math.max(deep, f - Math.min(Terrain.canyonFloor(x + nb[0], z + nb[1]), 320));
                deep = Math.min(Math.max(deep, 6) + 3, f - Layout.CANYON_FLOOR);
                for (int y = f - deep; y <= f; y++) {
                    int band = Math.floorMod(y + (int) (3 * Math.sin(x * 0.05 + z * 0.04)), 23);
                    long hy = WorldGen.hash(t.seed, x, y, z);
                    String m = band < 2 ? "minecraft:snow_block" : band == 9 || band == 10 ? "minecraft:blue_ice" : band == 16 ? "minecraft:calcite"
                            : (hy & 15) == 0 ? "minecraft:blue_ice" : (hy & 15) == 1 ? "minecraft:ice" : "minecraft:packed_ice";
                    data.setBlock(lx, y, lz, bd(m));
                }
                data.setBlock(lx, f, lz, bd("minecraft:snow_block"));
            }
            canyonDetail(cv, t);
        }
        // the cathedral canyon's staircase: real stairs down its north wall, landings every 16 steps, all the way to the floor
        int x0 = Layout.CANYON_X - Layout.CANYON_HX, zr = Layout.CANYON_Z - Layout.CANYON_HZ + 17;
        if (cv.touches(x0 - 44, zr - 1, Layout.CANYON_X + Layout.CANYON_HX, zr + 3)) {
            int rim = t.column(x0 - 1, zr + 1).surface;
            BlockData ice = bd("minecraft:packed_ice"), step = bd("minecraft:polished_diorite_stairs[facing=west]"), flat = bd("minecraft:polished_diorite");
            for (int i = 0; ; i++) {
                int x = x0 + i, y = Terrain.rampY(rim, i);
                if (y <= Layout.CANYON_FLOOR || x > Layout.CANYON_X + Layout.CANYON_HX - 16) break;
                if (!cv.touches(x, zr, x, zr + 3)) continue;
                boolean land = Terrain.rampFlat(rim, i) || i == 0;
                for (int z = zr; z <= zr + 3; z++) {
                    cv.column(x, z, Layout.CANYON_FLOOR, y - 1, ice);
                    cv.column(x, z, y + 1, y + 6, air);             // through the wall near the top
                    if (z == zr + 3) {                               // the parapet on the open side
                        cv.set(x, y, z, ice);
                        cv.set(x, y + 1, z, i % 2 == 0 ? bd("minecraft:polished_diorite") : bd("minecraft:polished_diorite_slab"));
                        if (i % 8 == 0) cv.set(x, y + 2, z, bd("minecraft:soul_lantern"));
                    } else cv.set(x, y, z, land ? flat : step);
                }
            }
            // the way onto it from the land: level with the top step, then following the ground one block at a time
            int y = rim;
            for (int i = 1; i <= 44; i++) {
                int x = x0 - i, g = t.column(x, zr + 1).surface;
                if (i > 3) y += Integer.compare(g, y);
                if (i > 3 && g == y && Math.abs(t.column(x - 1, zr + 1).surface - y) <= 1) break;
                BlockData b = i <= 3 || y == g ? flat : g > y ? bd("minecraft:polished_diorite_stairs[facing=west]") : bd("minecraft:polished_diorite_stairs[facing=east]");
                for (int z = zr; z <= zr + 2; z++) {
                    cv.column(x, z, Math.min(g, y) - 3, y - 1, ice);
                    cv.set(x, g > y ? y : y, z, b);
                    cv.column(x, z, y + 1, Math.max(g, y) + 5, air);
                }
            }
        }
    }

    static final BlockData air = WorldGen.Blocks.CAVE_AIR;
    static final int[][] NEAR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The canyon floor: ice spikes, frozen boulders, a lantern-lit way from the foot of the stairs to the cathedral. */
    static void canyonDetail(Canvas cv, Terrain t) {
        int F = Layout.CANYON_FLOOR;
        int x0 = Layout.CANYON_X - Layout.CANYON_HX, zr = Layout.CANYON_Z - Layout.CANYON_HZ + 17;
        int[] box = Sites.box(Layout.SITES.stream().filter(s -> s.file().equals("ice/cathedral")).findFirst().orElse(null));
        // ---- the way: from the foot of the stairs south to the middle line, then west to the cathedral
        int rim = t.column(x0 - 1, zr + 1).surface, foot = 0;
        while (Terrain.rampY(rim, foot) > F && x0 + foot <= Layout.CANYON_X + Layout.CANYON_HX - 16) foot++;
        int fx = x0 + foot + 1, mz = Layout.CANYON_Z;
        int endX = box != null ? Math.min(fx, box[3] + 2) : Layout.CANYON_X;
        for (int z = zr; z <= mz + 1; z++) for (int x = fx - 1; x <= fx + 1; x++) way(cv, t, x, z, F, x == fx && z % 9 == 0 ? 0 : -1);
        for (int x = endX; x <= fx + 1; x++) for (int z = mz - 1; z <= mz + 1; z++) way(cv, t, x, z, F, -1);
        for (int z = zr + 4; z <= mz; z += 9) { lamp(cv, fx - 3, F, z); lamp(cv, fx + 3, F, z); }
        for (int x = endX + 4; x <= fx - 4; x += 9) { lamp(cv, x, F, mz - 3); lamp(cv, x, F, mz + 3); }
        // ---- spikes and boulders, in 20-block cells
        for (int cx = Math.floorDiv(cv.bx - 12, 20); cx <= Math.floorDiv(cv.bx + 27, 20); cx++)
            for (int cz = Math.floorDiv(cv.bz - 12, 20); cz <= Math.floorDiv(cv.bz + 27, 20); cz++) {
                long h = WorldGen.hash(t.seed, cx, cz, 0x5C1E);
                int x = cx * 20 + 4 + (int) (h & 11), z = cz * 20 + 4 + (int) ((h >>> 6) & 11);
                if (!Terrain.inCanyon(x, z)) continue;
                boolean clear = true;
                for (int dx = -9; dx <= 9 && clear; dx += 3) for (int dz = -9; dz <= 9; dz += 3)
                    if (!Terrain.inCanyon(x + dx, z + dz) || Terrain.canyonFloor(x + dx, z + dz) > F) { clear = false; break; }
                if (!clear) continue;
                if (box != null && x > box[0] - 14 && x < box[3] + 14 && z > box[2] - 14 && z < box[5] + 14) continue;
                if (z < zr + 16 || Math.abs(x - fx) < 10 && z < mz + 10 || Math.abs(z - mz) < 10 && x > endX - 8 && x < fx + 10) continue;
                int kind = (int) ((h >>> 12) % 10);
                if (kind < 4) iceSpike(cv, x, F + 1, z, h);
                else if (kind < 6) { iceSpike(cv, x, F + 1, z, h); iceSpike(cv, x + 4, F + 1, z + 3, h >>> 7); iceSpike(cv, x - 3, F + 1, z + 4, h >>> 13); }
                else if (kind < 8) iceBoulder(cv, x, F + 1, z, h);
                else if (kind == 8) frozenPillars(cv, x, F + 1, z, h);
            }
    }

    static void way(Canvas cv, Terrain t, int x, int z, int F, int unused) {
        if (!Terrain.inCanyon(x, z) || Terrain.canyonFloor(x, z) > F) return;
        long h = WorldGen.hash(t.seed, x, z, 0x3A7);
        cv.set(x, F, z, bd((h & 7) == 0 ? "minecraft:packed_ice" : (h & 7) < 3 ? "minecraft:diorite" : "minecraft:polished_diorite"));
        cv.set(x, F + 1, z, air);
    }

    static void lamp(Canvas cv, int x, int F, int z) {
        if (!Terrain.inCanyon(x, z) || Terrain.canyonFloor(x, z) > F) return;
        cv.set(x, F + 1, z, bd("minecraft:polished_diorite"));
        cv.set(x, F + 2, z, bd("minecraft:packed_ice"));
        cv.set(x, F + 3, z, bd("minecraft:packed_ice"));
        cv.set(x, F + 4, z, bd("minecraft:soul_lantern"));
    }

    /** A tapering spike of packed ice with a blue heart, leaning a little. */
    static void iceSpike(Canvas cv, int x, int y, int z, long h) {
        int tall = 9 + (int) ((h >>> 20) % 22);
        double r0 = 1.6 + tall / 9.0, lx = ((h >>> 28) % 7 - 3) / 14.0, lz = ((h >>> 32) % 7 - 3) / 14.0;
        for (int dy = -1; dy <= tall; dy++) {
            double r = r0 * (1 - (double) Math.max(0, dy) / tall), ox = lx * dy, oz = lz * dy;
            int rr = (int) Math.ceil(r);
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > r + 0.3) continue;
                cv.set(x + (int) Math.round(ox) + dx, y + dy, z + (int) Math.round(oz) + dz, bd(d < r * 0.45 ? "minecraft:blue_ice" : "minecraft:packed_ice"));
            }
        }
    }

    static void iceBoulder(Canvas cv, int x, int y, int z, long h) {
        double r = 2.2 + (h >>> 20) % 3;
        int rr = (int) Math.ceil(r);
        for (int dx = -rr; dx <= rr; dx++) for (int dy = -1; dy <= rr; dy++) for (int dz = -rr; dz <= rr; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy * 1.6 + dz * dz);
            if (d > r) continue;
            cv.set(x + dx, y + dy, z + dz, bd(d < r - 1.2 ? "minecraft:blue_ice" : dy >= rr - 2 ? "minecraft:snow_block" : "minecraft:packed_ice"));
        }
    }

    /** A ring of broken ice columns round a soul fire: something stood here once. */
    static void frozenPillars(Canvas cv, int x, int y, int z, long h) {
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            int px = x + (int) Math.round(Math.cos(a) * 6), pz = z + (int) Math.round(Math.sin(a) * 6);
            int tall = 3 + (int) ((h >>> (i * 4)) % 8);
            for (int dy = 0; dy < tall; dy++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++)
                cv.set(px + dx, y + dy, pz + dz, bd(dy == 0 || dy == tall - 1 ? "minecraft:chiseled_quartz_block" : "minecraft:quartz_pillar"));
        }
        cv.set(x, y, z, bd("minecraft:soul_soil"));
        cv.set(x, y + 1, z, bd("minecraft:soul_fire"));
        for (int[] n : NEAR) cv.set(x + n[0], y, z + n[1], bd("minecraft:polished_diorite_slab"));
    }
}
