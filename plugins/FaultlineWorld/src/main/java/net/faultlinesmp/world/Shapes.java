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

    static void carve(Terrain t, WorldInfo info, int bx, int bz, Terrain.Col[] cols, ChunkData data) {
        Canvas cv = new Canvas(data, bx, bz);
        BlockData air = WorldGen.Blocks.CAVE_AIR;
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
                int f = Terrain.canyonFloor(x, z);
                if (f <= Layout.CANYON_FLOOR) { data.setBlock(lx, f, lz, bd("minecraft:snow_block")); continue; }
                long h = WorldGen.hash(t.seed, x, z, 0x1CE);
                for (int y = f - 14; y <= f; y++) data.setBlock(lx, y, lz, ((h >>> (y & 31)) & 3) == 0 ? bd("minecraft:blue_ice") : bd("minecraft:packed_ice"));
                data.setBlock(lx, f, lz, bd("minecraft:snow_block"));
            }
        }
        // the cathedral canyon: a long ramp down its north wall + ice walls
        int x0 = Layout.CANYON_X - Layout.CANYON_HX, zr = Layout.CANYON_Z - Layout.CANYON_HZ + 15;
        if (cv.touches(x0 - 2, zr - 1, Layout.CANYON_X + Layout.CANYON_HX, zr + 3)) {
            int rim = t.column(x0 - 3, zr).surface;
            BlockData ice = bd("minecraft:packed_ice"), stair = bd("minecraft:blue_ice");
            for (int x = x0; x <= Layout.CANYON_X + Layout.CANYON_HX - 14; x++) {
                int y = (int) Math.round(rim - (x - x0) * 0.72);
                if (y < Layout.CANYON_FLOOR + 1) break;
                for (int z = zr; z <= zr + 3; z++) {
                    cv.column(x, z, Layout.CANYON_FLOOR, y - 1, ice);
                    cv.column(x, z, y + 1, y + 4, air);             // through the terraced walls near the top
                    cv.set(x, y, z, z == zr + 3 ? bd("minecraft:packed_ice") : stair);
                    if (z == zr + 3 && x % 2 == 0) cv.set(x, y + 1, z, bd("minecraft:spruce_fence"));
                    if (z == zr + 3 && x % 12 == 0) cv.set(x, y + 2, z, bd("minecraft:lantern"));
                }
            }
        }
    }
}
