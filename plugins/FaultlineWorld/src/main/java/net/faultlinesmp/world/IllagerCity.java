package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;

import static net.faultlinesmp.world.WorldGen.Blocks.bd;
import static net.faultlinesmp.world.WorldGen.hash;
import static net.faultlinesmp.world.WorldGen.hash3;

/**
 * 2.0 round 9: THE ILLAGER CITIES (the Deep Forest's two strongholds), rebuilt as dark-fantasy fortresses: a ring of jagged
 * leaning crags round a hollow (one gate, south), and inside it
 *   - the WITCH KEEP on a rock pinnacle at the back (spired keep, four turrets, green-lit windows), reached by a ramp winding
 *     round the pinnacle, and a stone bridge from the keep to a LONE TOWER on a second, taller needle of rock;
 *   - the BUNKER: a brutalist concrete fortress in stepped tiers, cantilevered balconies, zig-zag stairs climbing its face,
 *     a wild garden on its roof (the pillagers' arsenal);
 *   - the COOLING TOWER: a hyperbolic concrete shell with zig-zag stairs up its outside to a lit platform;
 *   - the GLOW POOL: green-lit water with a rock in it, a waterfall pouring off the rock into the pool;
 *   - gothic houses (stone below, dark timber above, steep roofs, purple glass), lamp posts with green lights, thorn arches
 *     over the avenue, cages and targets.
 * Drawn per chunk from world positions (like every other feature), the ground is flattened by Terrain (cityHeight).
 */
final class IllagerCity {
    private IllagerCity() {}

    static final int R = Structures.CITY_R;            // the crag ring's outer edge
    static final BlockData AIR = null;

    static BlockData air() { return WorldGen.Blocks.AIR; }

    static void paint(Canvas cv, Terrain t) {
        for (int[] c : Structures.cities()) {
            if (!cv.touches(c[0] - R - 6, c[1] - R - 6, c[0] + R + 6, c[1] + R + 6)) continue;
            int y = t.column(c[0], c[1]).surface;
            long h = hash(t.seed, c[0], c[1], 0xC17);
            ground(cv, t, c[0], y, c[1], h);
            crags(cv, t, c[0], y, c[1], h);
            gate(cv, c[0], y, c[1] + R - 8);
            keep(cv, t, c[0], y, c[1] - 40, h);
            bunker(cv, t, c[0] - 38, y, c[1] - 2, h);
            coolingTower(cv, c[0] + 38, y, c[1] + 4, h);
            pool(cv, c[0], y, c[1] + 24);
            houses(cv, t, c[0], y, c[1], h);
            thorns(cv, c[0], y, c[1]);
            lamps(cv, c[0], y, c[1]);
            cages(cv, c[0] - 20, y, c[1] + 30);
        }
    }

    // ------------------------------------------------------------------ the ground, the avenue, the ring road

    static void ground(Canvas cv, Terrain t, int cx, int y, int cz, long h) {
        for (int x = Math.max(cv.bx, cx - R); x <= Math.min(cv.bx + 15, cx + R); x++)
            for (int z = Math.max(cv.bz, cz - R); z <= Math.min(cv.bz + 15, cz + R); z++) {
                double d = Layout.dist(x, z, cx, cz);
                if (d > R - 10) continue;
                cv.column(x, z, y - 3, y - 1, bd("minecraft:dirt"));
                cv.column(x, z, y + 1, y + 60, air());           // clear what the forest put here
                long k = hash(t.seed, x, z, 0xC18);
                boolean avenue = Math.abs(x - cx) <= 2 && z > cz - 28, ring = Math.abs(d - 30) < 2;
                String top;
                if (avenue || ring) top = (k & 7) == 0 ? "minecraft:mossy_cobblestone" : (k & 7) < 3 ? "minecraft:cobbled_deepslate" : (k & 7) < 5 ? "minecraft:deepslate_tiles" : "minecraft:polished_deepslate";
                else {
                    double n = Math.sin(x * 0.13 + z * 0.07) + Math.sin(z * 0.11 - x * 0.05);
                    top = n > 1.2 ? "minecraft:moss_block" : n < -1.3 ? "minecraft:podzol" : (k & 15) == 0 ? "minecraft:coarse_dirt" : "minecraft:grass_block";
                }
                cv.set(x, y, z, bd(top));
                if (!avenue && !ring && top.endsWith("grass_block") && (k >>> 8 & 15) < 3) cv.set(x, y + 1, z, bd((k >>> 12 & 3) == 0 ? "minecraft:fern" : "minecraft:short_grass"));
            }
    }

    // ------------------------------------------------------------------ the crags

    static void crags(Canvas cv, Terrain t, int cx, int y, int cz, long h) {
        // a low rock wall all round (the ring), the gate gap facing south
        for (int x = Math.max(cv.bx, cx - R); x <= Math.min(cv.bx + 15, cx + R); x++)
            for (int z = Math.max(cv.bz, cz - R); z <= Math.min(cv.bz + 15, cz + R); z++) {
                double d = Layout.dist(x, z, cx, cz), a = Math.atan2(z - cz, x - cx);
                if (d < R - 12 || d > R) continue;
                if (Math.abs(a - Math.PI / 2) < 0.2) continue;            // the gate
                double band = 1 - Math.abs(d - (R - 6)) / 6.0;
                int top = y + (int) Math.round(band * (9 + 5 * Math.sin(a * 7 + (h & 7)) + 3 * Math.sin(a * 19)));
                for (int yy = y - 3; yy <= top; yy++) cv.set(x, yy, z, rock(t, x, yy, z));
                if (top > y && (hash(t.seed, x, z, 0xC19) & 7) == 0) cv.set(x, top + 1, z, bd("minecraft:moss_carpet"));
            }
        // the great leaning spires on the ring
        int n = 26;
        for (int i = 0; i < n; i++) {
            long k = hash(h, i, 0, 0xC1A);
            double a = i * Math.PI * 2 / n + ((k & 63) / 63.0 - 0.5) * 0.18;
            if (Math.abs(Math.atan2(Math.sin(a), Math.cos(a)) - Math.PI / 2) < 0.32) continue;
            double r = R - 6 + ((k >>> 6) & 7) - 3;
            int x = cx + (int) Math.round(Math.cos(a) * r), z = cz + (int) Math.round(Math.sin(a) * r);
            int tall = 22 + (int) ((k >>> 10) % 34);
            double base = 4 + (k >>> 16) % 5;
            double lean = ((k >>> 20) % 7 - 1) / 12.0;                   // mostly leaning outward, some inward
            spire(cv, t, x, y - 3, z, base, tall + 3, Math.cos(a) * lean, Math.sin(a) * lean, k);
        }
    }

    /** A jagged rock needle: a tapering, leaning stack of rough disks. */
    static void spire(Canvas cv, Terrain t, int x, int y0, int z, double base, int tall, double lx, double lz, long k) {
        int reach = (int) Math.ceil(base + Math.max(Math.abs(lx), Math.abs(lz)) * tall + 2);
        if (!cv.touches(x - reach, z - reach, x + reach, z + reach)) return;
        for (int dy = 0; dy <= tall; dy++) {
            double f = dy / (double) tall, r = base * Math.pow(1 - f, 0.75) + 0.6;
            double ox = lx * dy, oz = lz * dy;
            int rr = (int) Math.ceil(r + 1);
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                double ang = Math.atan2(dz, dx), rough = r * (1 + 0.22 * Math.sin(ang * 3 + (k & 15) + dy * 0.21) + 0.12 * Math.sin(ang * 7 + dy * 0.5));
                if (Math.hypot(dx - (ox - Math.round(ox)), dz - (oz - Math.round(oz))) > rough) continue;
                int wx = x + (int) Math.round(ox) + dx, wz = z + (int) Math.round(oz) + dz, wy = y0 + dy;
                cv.set(wx, wy, wz, rock(t, wx, wy, wz));
            }
        }
    }

    static BlockData rock(Terrain t, int x, int y, int z) {
        int band = Math.floorMod(y + (int) (2 * Math.sin(x * 0.09 + z * 0.07)), 11);
        long k = hash3(t.seed, x, y, z, 0xC1B);
        if ((k & 31) == 0) return bd("minecraft:verdant_froglight");     // green veins
        return bd(band < 3 ? "minecraft:blackstone" : band < 6 ? "minecraft:deepslate" : band < 8 ? "minecraft:tuff" : (k & 3) == 0 ? "minecraft:basalt" : "minecraft:cobbled_deepslate");
    }

    // ------------------------------------------------------------------ the gate

    static void gate(Canvas cv, int x, int y, int z) {
        if (!cv.touches(x - 12, z - 6, x + 12, z + 6)) return;
        for (int side = -1; side <= 1; side += 2) {
            int tx = x + side * 7;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 18; dy++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (dy == 18) { if (edge && (dx + dz & 1) == 0) cv.set(tx + dx, y + dy, z + dz, bd("minecraft:polished_blackstone_bricks")); continue; }
                cv.set(tx + dx, y + dy, z + dz, edge || dy <= 0 || dy == 17 ? bd(dy % 6 == 5 ? "minecraft:chiseled_polished_blackstone" : "minecraft:polished_blackstone_bricks") : air());
            }
            for (int dy : new int[]{6, 11}) cv.set(tx + side * 2, y + dy, z, bd("minecraft:purple_stained_glass"));
            cv.set(tx, y + 19, z, bd("minecraft:verdant_froglight"));
        }
        // the arch and the portcullis frame
        for (int dx = -5; dx <= 5; dx++) {
            int top = 12 - (int) Math.round(Math.sqrt(Math.max(0, 25 - dx * dx)) * 0.9);
            for (int dz = -1; dz <= 1; dz++) for (int dy = top; dy <= 14; dy++) cv.set(x + dx, y + dy, z + dz, bd(dy == 14 ? "minecraft:polished_blackstone_brick_slab[type=bottom]" : "minecraft:polished_blackstone_bricks"));
            cv.set(x + dx, y + top - 1, z, bd("minecraft:polished_blackstone_brick_slab[type=top]"));
        }
        for (int dz = -6; dz <= 6; dz++) for (int dx = -2; dx <= 2; dx++) cv.set(x + dx, y, z + dz, bd("minecraft:polished_deepslate"));
    }

    // ------------------------------------------------------------------ the witch keep on its pinnacle

    static void keep(Canvas cv, Terrain t, int x, int y, int z, long h) {
        int H = 30;                                                     // the pinnacle's top
        if (cv.touches(x - 36, z - 32, x + 36, z + 30)) {
            // the pinnacle: a rough column of rock, wide at the foot, a flat top
            for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
                double ang = Math.atan2(dz, dx), d = Math.hypot(dx, dz);
                for (int dy = -3; dy <= H; dy++) {
                    double r = 12.5 + 4.5 * Math.pow(1 - Math.max(0, dy) / (double) H, 2) + 1.2 * Math.sin(ang * 5 + dy * 0.3);
                    if (d <= r) cv.set(x + dx, y + dy, z + dz, rock(t, x + dx, y + dy, z + dz));
                }
            }
            // the ramp: up round the pinnacle, 1.25 turns, 3 wide, slab steps
            int steps = 520;
            for (int i = 0; i <= steps; i++) {
                double f = i / (double) steps, a = Math.PI / 2 + f * Math.PI * 2.5;   // starts facing south (the avenue)
                double py = y + 1 + f * (H - 1);
                int yb = (int) Math.floor(py);
                boolean upper = py - yb >= 0.5;
                double rIn = 12.5 + 4.5 * Math.pow(1 - Math.max(0, yb - y) / (double) H, 2) + 1.2;
                for (double rr = rIn + 0.6; rr <= rIn + 3.4; rr += 0.7) {
                    int bx = x + (int) Math.round(Math.cos(a) * rr), bz = z + (int) Math.round(Math.sin(a) * rr);
                    cv.set(bx, yb, bz, bd(upper ? "minecraft:cobbled_deepslate_slab[type=top]" : "minecraft:cobbled_deepslate_slab[type=bottom]"));
                    cv.set(bx, yb - 1, bz, rock(t, bx, yb - 1, bz));
                    for (int k = 1; k <= 3; k++) if (!cv.air(bx, yb + k, bz)) cv.set(bx, yb + k, bz, air());
                }
                if (i % 26 == 0) {
                    int lx = x + (int) Math.round(Math.cos(a) * (rIn + 4.2)), lz = z + (int) Math.round(Math.sin(a) * (rIn + 4.2));
                    cv.set(lx, yb, lz, bd("minecraft:polished_blackstone"));
                    cv.set(lx, yb + 1, lz, bd("minecraft:polished_blackstone"));
                    cv.set(lx, yb + 2, lz, bd("minecraft:verdant_froglight"));
                }
            }
        }
        int top = y + H;
        // the keep: 11 x 11, 26 tall, floors every 6, green-lit windows, a spire
        int kx = x, kz = z;
        if (cv.touches(kx - 9, kz - 9, kx + 9, kz + 9)) {
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = 1; dy <= 26; dy++) {
                boolean edge = Math.abs(dx) == 5 || Math.abs(dz) == 5, corner = Math.abs(dx) == 5 && Math.abs(dz) == 5;
                String b;
                if (corner) b = "minecraft:chiseled_polished_blackstone";
                else if (edge) {
                    int along = Math.abs(dx) == 5 ? dz : dx;
                    boolean win = dy % 6 >= 2 && dy % 6 <= 4 && (along == -2 || along == 2);
                    b = win ? (dy % 6 == 3 ? "minecraft:verdant_froglight" : "minecraft:purple_stained_glass") : dy % 6 == 0 ? "minecraft:polished_blackstone" : "minecraft:polished_blackstone_bricks";
                } else b = dy % 6 == 0 ? "minecraft:dark_oak_planks" : null;
                cv.set(kx + dx, top + dy, kz + dz, b == null ? air() : bd(b));
            }
            for (int dy = 1; dy <= 25; dy++) cv.set(kx - 4, top + dy, kz - 3, bd("minecraft:ladder[facing=east]"));
            for (int dy = 6; dy <= 25; dy += 6) cv.set(kx - 4, top + dy, kz - 3, bd("minecraft:ladder[facing=east]"));
            for (int dy = 1; dy <= 3; dy++) for (int dx = -1; dx <= 1; dx++) cv.set(kx + dx, top + dy, kz + 5, air());   // the door (south)
            for (int dy = 13; dy <= 15; dy++) for (int dz = -3; dz <= -1; dz++) cv.set(kx + 5, top + dy, kz + dz, air());   // out to the bridge (east)
            cone(cv, kx, top + 27, kz, 6.5, 20, "minecraft:deepslate_tiles", "minecraft:polished_blackstone");
            for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) turret(cv, kx + sx * 6, top, kz + sz * 6, 34 + (sx + sz == 0 ? 6 : 0));
            cv.set(kx, top + 1, kz, bd("minecraft:chest[facing=south]"));
            cv.set(kx + 2, top + 1, kz - 2, bd("minecraft:lectern[facing=south]"));
            cv.set(kx - 2, top + 7, kz + 2, bd("minecraft:chest[facing=north]"));
            cv.set(kx + 3, top + 13, kz, bd("minecraft:barrel[facing=up]"));
        }
        // the lone tower on its needle (east-north-east), and the bridge to it
        int tx = x + 26, tz = z - 10, tTop = y + 44;
        spire(cv, t, tx, y - 3, tz, 6.5, tTop - y + 3, 0, 0, h ^ 0x7);
        if (cv.touches(tx - 6, tz - 6, tx + 6, tz + 6)) {
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 3.4) continue;
                for (int dy = -6; dy <= 0; dy++) cv.set(tx + dx, tTop + dy, tz + dz, rock(t, tx + dx, tTop + dy, tz + dz));
                for (int dy = 1; dy <= 16; dy++) cv.set(tx + dx, tTop + dy, tz + dz, d > 2.5 ? bd(dy % 5 == 3 && (dx == 0 || dz == 0) ? "minecraft:purple_stained_glass" : "minecraft:polished_blackstone_bricks") : air());
            }
            for (int dy = 1; dy <= 3; dy++) cv.set(tx - 3, tTop + dy, tz, air());
            cone(cv, tx, tTop + 17, tz, 4.5, 14, "minecraft:deepslate_tiles", "minecraft:polished_blackstone");
            cv.set(tx, tTop + 1, tz, bd("minecraft:chest[facing=west]"));
            cv.set(tx, tTop + 8, tz, bd("minecraft:verdant_froglight"));
        }
        stoneBridge(cv, kx + 6, top + 13, kz - 2, tx - 4, tTop + 1, tz);
    }

    static void turret(Canvas cv, int x, int y0, int z, int tall) {
        if (!cv.touches(x - 5, z - 5, x + 5, z + 5)) return;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 2.4) continue;
            for (int dy = 1; dy <= tall; dy++)
                cv.set(x + dx, y0 + dy, z + dz, d > 1.5 ? bd(dy % 7 == 4 && (dx == 0 || dz == 0) ? "minecraft:verdant_froglight" : "minecraft:polished_blackstone_bricks") : air());
        }
        cone(cv, x, y0 + tall + 1, z, 3.5, 12, "minecraft:deepslate_tiles", "minecraft:polished_blackstone");
    }

    /** A pointed roof: a stepped cone, a spike on top. */
    static void cone(Canvas cv, int x, int y0, int z, double r0, int tall, String m, String tip) {
        for (int dy = 0; dy < tall; dy++) {
            double r = r0 * (1 - dy / (double) tall);
            int rr = (int) Math.ceil(r);
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) if (Math.hypot(dx, dz) <= r + 0.3) cv.set(x + dx, y0 + dy, z + dz, bd(m));
        }
        for (int k = 0; k < 4; k++) cv.set(x, y0 + tall + k, z, bd(k == 3 ? "minecraft:lightning_rod[facing=up]" : tip));
    }

    /** A stone bridge: a 3-wide deck with parapets, a hanging arch under it. */
    static void stoneBridge(Canvas cv, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (!cv.touches(Math.min(x0, x1) - 3, Math.min(z0, z1) - 3, Math.max(x0, x1) + 3, Math.max(z0, z1) + 3)) return;
        double len = Math.hypot(x1 - x0, z1 - z0);
        int n = (int) Math.ceil(len * 2);
        double ux = (x1 - x0) / len, uz = (z1 - z0) / len;
        for (int i = 0; i <= n; i++) {
            double f = i / (double) n, px = x0 + (x1 - x0) * f, pz = z0 + (z1 - z0) * f;
            int yy = (int) Math.round(y0 + (y1 - y0) * f);
            int sag = (int) Math.round(5 * Math.sin(f * Math.PI));
            for (int w = -2; w <= 2; w++) {
                int bx = (int) Math.round(px - uz * w), bz = (int) Math.round(pz + ux * w);
                cv.set(bx, yy - 1, bz, bd("minecraft:polished_blackstone_bricks"));
                if (Math.abs(w) == 2) cv.set(bx, yy, bz, bd("minecraft:polished_blackstone_bricks"));
                else for (int k = 0; k <= 3; k++) cv.set(bx, yy + k, bz, air());
                for (int k = 2; k <= sag + 1; k++) if (Math.abs(w) <= 1) cv.set(bx, yy - k, bz, bd("minecraft:polished_blackstone_bricks"));
            }
        }
    }

    // ------------------------------------------------------------------ the bunker (brutalist, tiered, zig-zag stairs)

    static final String[] CONCRETE = {"minecraft:light_gray_concrete", "minecraft:smooth_stone", "minecraft:polished_andesite", "minecraft:gray_concrete"};

    static void bunker(Canvas cv, Terrain t, int x, int y, int z, long h) {
        if (!cv.touches(x - 16, z - 16, x + 16, z + 20)) return;
        int tiers = 4, th = 12;
        for (int k = 0; k < tiers; k++) {
            int hs = 11 - k * 2, y0 = y + k * th;
            for (int dx = -hs; dx <= hs; dx++) for (int dz = -hs; dz <= hs; dz++) for (int dy = 0; dy <= th; dy++) {
                boolean edge = Math.abs(dx) == hs || Math.abs(dz) == hs;
                int wy = y0 + dy;
                String b;
                if (dy == 0 || dy == th) b = "minecraft:smooth_stone";
                else if (edge) {
                    int along = Math.abs(dx) == hs ? dz : dx;
                    boolean win = (dy == 4 || dy == 5 || dy == 8) && Math.floorMod(along, 4) == 1;
                    long q = hash3(h, x + dx, wy, z + dz, 0xB0);
                    b = win ? ((q & 3) == 0 ? null : "minecraft:tinted_glass") : dy % 5 == 0 ? "minecraft:polished_andesite" : CONCRETE[(int) (q & 1)];
                    if (b != null && (q & 63) == 1) b = "minecraft:mossy_stone_bricks";          // weathered
                } else b = (dy == 1 && (hash3(h, x + dx, wy, z + dz, 0xB1) & 63) == 0) ? "minecraft:cobweb" : null;
                cv.set(x + dx, wy, z + dz, b == null ? air() : bd(b));
            }
            // the cantilevered balcony round the tier's top, a parapet on it
            int bh = hs + 2, by = y0 + th;
            for (int dx = -bh; dx <= bh; dx++) for (int dz = -bh; dz <= bh; dz++) {
                int e = Math.max(Math.abs(dx), Math.abs(dz));
                if (e <= hs || e > bh) continue;
                cv.set(x + dx, by, z + dz, bd("minecraft:smooth_stone"));
                if (e == bh) cv.set(x + dx, by + 1, z + dz, bd((Math.abs(dx) + Math.abs(dz)) % 3 == 0 ? "minecraft:polished_andesite" : "minecraft:smooth_stone_slab[type=bottom]"));
                if (e == bh && Math.abs(dx) == bh && Math.abs(dz) == bh) for (int dy = -3; dy < 0; dy++) cv.set(x + dx, by + dy, z + dz, bd("minecraft:polished_andesite"));   // brackets
            }
            // the door into this tier from its balcony / the ground (south)
            for (int dy = 1; dy <= 3; dy++) for (int dx = 0; dx <= 1; dx++) cv.set(x + dx, y0 + dy, z + hs, air());
            // zig-zag stairs up the south face, from this tier's floor to its balcony: 3 flights of 4, crossing in an X
            final int faceZ = z + hs + 1;
            zigzag(cv, x - 8, y0, 3, yy -> faceZ);
        }
        // the roof garden
        int top = y + tiers * th, hs = 11 - (tiers - 1) * 2 - 1;
        for (int dx = -hs; dx <= hs; dx++) for (int dz = -hs; dz <= hs; dz++) {
            long q = hash(h, x + dx, z + dz, 0xB2);
            cv.set(x + dx, top + 1, z + dz, bd((q & 3) == 0 ? "minecraft:moss_block" : "minecraft:grass_block"));
            if ((q >>> 4 & 7) == 0) cv.set(x + dx, top + 2, z + dz, bd("minecraft:azalea"));
            else if ((q >>> 4 & 7) == 1) cv.set(x + dx, top + 2, z + dz, bd("minecraft:short_grass"));
        }
        for (int k = 0; k < 3; k++) {
            int ox = x - 3 + k * 3, oz = z - 2 + (k % 2) * 4;
            for (int dy = 2; dy <= 5; dy++) cv.set(ox, top + dy, oz, bd("minecraft:oak_log"));
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 5; dy <= 7; dy++)
                if (Math.abs(dx) + Math.abs(dz) + (dy - 5) <= 3) cv.air(ox + dx, top + dy, oz + dz, bd("minecraft:azalea_leaves[persistent=true]"));
        }
        // ivy down the walls (vines hang from the balconies)
        for (int k = 0; k < 4; k++) for (int dy = 2; dy <= 9; dy++) cv.set(x - 12, y + dy, z - 6 + k * 4, bd("minecraft:vine[east=true]"));
        cv.set(x + 3, y + 1, z - 3, bd("minecraft:chest[facing=south]"));
        cv.set(x - 3, y + 12, z + 3, bd("minecraft:chest[facing=north]"));
        cv.set(x, y + 23, z, bd("minecraft:barrel[facing=up]"));
    }

    /**
     * Zig-zag stairs on a facade: flights of 4 steps (each a stair + a top slab, 8 blocks long), going east in the lane against
     * the wall and west in the lane outside it, so they cross in an X; a landing over both lanes at every turn.
     */
    static void zigzag(Canvas cv, int xa, int y0, int flights, java.util.function.IntUnaryOperator face) {
        for (int f = 0; f < flights; f++) {
            boolean east = f % 2 == 0;
            int lane = east ? 0 : 2;
            for (int r = 0; r < 4; r++) {
                int ys = y0 + f * 4 + r + 1, xs = east ? xa + 2 * r : xa + 7 - 2 * r, nx = xs + (east ? 1 : -1);
                int z0 = face.applyAsInt(ys) + lane;
                for (int w = 0; w <= 1; w++) {
                    cv.set(xs, ys, z0 + w, bd("minecraft:polished_andesite_stairs[facing=" + (east ? "east" : "west") + "]"));
                    cv.set(nx, ys, z0 + w, bd("minecraft:smooth_stone_slab[type=top]"));
                    for (int k = 1; k <= 3; k++) { cv.set(xs, ys + k, z0 + w, air()); cv.set(nx, ys + k, z0 + w, air()); }
                }
                cv.set(xs, ys - 1, z0 + (east ? 0 : 1), bd("minecraft:polished_andesite"));            // the stringer under it
            }
            int ly = y0 + f * 4 + 4, z0 = face.applyAsInt(ly);
            int lx0 = east ? xa + 8 : xa - 2;
            for (int lx = lx0; lx <= lx0 + 1; lx++) for (int w = 0; w <= 3; w++) {
                cv.set(lx, ly, z0 + w, bd("minecraft:smooth_stone_slab[type=top]"));
                for (int k = 1; k <= 3; k++) cv.set(lx, ly + k, z0 + w, air());
            }
            for (int w = 0; w <= 3; w++) cv.set(east ? lx0 + 1 : lx0, ly + 1, z0 + w, bd("minecraft:polished_andesite_slab[type=bottom]"));   // the rail at the turn, on the landing
        }
    }

    // ------------------------------------------------------------------ the cooling tower

    static double towerR(double f) { return f < 0.7 ? 8.5 + 4.5 * Math.pow((0.7 - f) / 0.7, 2) : 8.5 + 1.5 * Math.pow((f - 0.7) / 0.3, 2); }

    static void coolingTower(Canvas cv, int x, int y, int z, long h) {
        int H = 56;
        if (!cv.touches(x - 16, z - 16, x + 16, z + 20)) return;
        for (int dy = 0; dy <= H; dy++) {
            double r = towerR(dy / (double) H);
            int rr = (int) Math.ceil(r + 1);
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                double d = Math.hypot(dx, dz), ang = Math.atan2(dz, dx);
                if (d > r + 0.5) continue;
                if (d < r - 0.6) { if (dy > 0) cv.set(x + dx, y + dy, z + dz, air()); continue; }   // (its floor stays)
                if (dy <= 3 && Math.cos(ang * 8) > 0.7) { cv.set(x + dx, y + dy, z + dz, air()); continue; }   // the open legs
                long q = hash3(h, x + dx, y + dy, z + dz, 0xC0);
                String b = dy == H ? "minecraft:polished_andesite" : Math.sin(ang * 23 + dx) > 0.92 ? "minecraft:gray_concrete" : (q & 15) == 0 ? "minecraft:cyan_terracotta" : "minecraft:light_gray_concrete";
                cv.set(x + dx, y + dy, z + dz, bd(b));
            }
        }
        // zig-zag stairs up the south side, following the curve of the shell
        zigzag(cv, x - 4, y, (H - 4) / 4, yy -> z + (int) Math.round(towerR((yy - y) / (double) H)) + 1);
        // the platform on top and its green beacon
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (Math.hypot(dx, dz) < 3.4) cv.set(x + dx, y + H - 2, z + dz, bd("minecraft:smooth_stone"));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) cv.set(x + dx, y + H - 1, z + dz, bd("minecraft:verdant_froglight"));
        for (int k = 0; k <= 8; k++) { cv.set(x + 4 + k, y + H - 2, z, bd("minecraft:smooth_stone_slab[type=top]")); }   // a catwalk to the rim
    }

    // ------------------------------------------------------------------ the glow pool and its waterfall

    static void pool(Canvas cv, int x, int y, int z) {
        if (!cv.touches(x - 13, z - 13, x + 13, z + 13)) return;
        for (int dx = -11; dx <= 11; dx++) for (int dz = -11; dz <= 11; dz++) {
            double d = Math.hypot(dx, dz) + 0.8 * Math.sin(Math.atan2(dz, dx) * 4);
            if (d > 11) continue;
            int wx = x + dx, wz = z + dz;
            if (d > 9.5) { cv.column(wx, wz, y - 4, y, bd("minecraft:polished_deepslate")); cv.set(wx, y + 1, wz, bd("minecraft:deepslate_tile_slab[type=bottom]")); continue; }
            cv.set(wx, y - 4, wz, bd((dx + dz & 3) == 0 ? "minecraft:sea_lantern" : "minecraft:verdant_froglight"));
            cv.column(wx, wz, y - 3, y, WorldGen.Blocks.WATER);
            cv.column(wx, wz, y + 1, y + 3, air());
        }
        // the rock in the pool (north part) with a waterfall pouring south off its top
        int rx = x, rz = z - 5;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 3.3) continue;
            int tall = 9 - (int) Math.round(d * 1.6);
            for (int dy = -3; dy <= tall; dy++) cv.set(rx + dx, y + dy, rz + dz, bd(dy > tall - 2 ? "minecraft:mossy_cobblestone" : "minecraft:tuff"));
        }
        // the spring: a basin cut into the rock's south shoulder (rock on three sides and under it), and its fall into the pool
        int sy = y + 5, sz = rz + 3;
        cv.set(rx, sy, sz, WorldGen.Blocks.WATER);
        cv.set(rx - 1, sy, sz, bd("minecraft:mossy_cobblestone")); cv.set(rx + 1, sy, sz, bd("minecraft:mossy_cobblestone"));
        cv.set(rx, sy, sz - 1, bd("minecraft:mossy_cobblestone")); cv.set(rx, sy - 1, sz, bd("minecraft:tuff"));
        for (int dy = sy; dy > y; dy--) cv.set(rx, dy, sz + 1, bd("minecraft:water[level=8]"));
    }

    // ------------------------------------------------------------------ houses

    static void houses(Canvas cv, Terrain t, int cx, int y, int cz, long h) {
        int[][] spots = {{-16, 36}, {-30, 38}, {-44, 30}, {10, 38}, {24, 36}, {38, 30}, {16, 50}, {-12, 50}, {-18, -12}, {12, -14}, {14, 8}, {-22, 6}, {40, -24}, {-40, -28}};
        for (int i = 0; i < spots.length; i++) {
            long k = hash(h, i, 1, 0xC20);
            house(cv, t, cx + spots[i][0], y + 1, cz + spots[i][1], k, (k & 1) == 0);
        }
    }

    static void house(Canvas cv, Terrain t, int x, int y, int z, long k, boolean tower) {
        int w = 7, l = 9, H1 = 4, H2 = 4;
        if (!cv.touches(x - 4, z - 4, x + w + 4, z + l + 4)) return;
        String glass = (k >>> 3 & 1) == 0 ? "minecraft:purple_stained_glass" : "minecraft:green_stained_glass";
        for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < l; dz++) {
            boolean edgeX = dx == 0 || dx == w - 1, edgeZ = dz == 0 || dz == l - 1, edge = edgeX || edgeZ, corner = edgeX && edgeZ;
            cv.set(x + dx, y - 1, z + dz, bd("minecraft:polished_deepslate"));
            for (int dy = 0; dy < H1 + H2; dy++) {
                String b;
                if (!edge) b = dy == H1 ? "minecraft:dark_oak_planks" : null;
                else if (dy < H1) b = dy == 1 && !corner && (edgeX ? dz % 3 == 1 : dx % 3 == 1) ? glass : corner ? "minecraft:polished_deepslate" : "minecraft:deepslate_bricks";
                else b = corner || dy == H1 ? "minecraft:dark_oak_log" : dy == H1 + 2 && !corner && (edgeX ? dz % 3 == 1 : dx % 3 == 1) ? glass : "minecraft:dark_oak_planks";
                cv.set(x + dx, y + dy, z + dz, b == null ? air() : bd(b));
            }
        }
        // the steep roof (ridge along z), gable ends of planks
        int ry = y + H1 + H2;
        for (int s = 0; s <= w / 2 + 1; s++) for (int dz = -1; dz <= l; dz++) {
            int left = -1 + s, right = w - s;
            if (left > right) break;
            for (int r2 = 0; r2 < 2; r2++) {
                int yy = ry + s * 2 + r2;
                if (left == right) { cv.set(x + left, yy, z + dz, bd("minecraft:deepslate_tile_slab[type=bottom]")); continue; }
                cv.set(x + left, yy, z + dz, bd("minecraft:deepslate_tile_stairs[facing=east]"));
                cv.set(x + right, yy, z + dz, bd("minecraft:deepslate_tile_stairs[facing=west]"));
                if (dz == 0 || dz == l - 1) for (int fx = left + 1; fx < right; fx++) cv.set(x + fx, yy, z + dz, bd("minecraft:dark_oak_planks"));
            }
        }
        // the door (south), a lamp beside it, a chimney
        cv.set(x + 3, y, z + l - 1, bd("minecraft:dark_oak_door[half=lower,facing=south]"));
        cv.set(x + 3, y + 1, z + l - 1, bd("minecraft:dark_oak_door[half=upper,facing=south]"));
        cv.set(x + 5, y + 2, z + l, bd("minecraft:soul_lantern"));
        cv.set(x + 5, y + 1, z + l, bd("minecraft:polished_blackstone_brick_slab[type=top]"));
        cv.set(x + 5, y, z + l, bd("minecraft:polished_blackstone"));
        for (int dy = 0; dy < 15; dy++) cv.set(x + 1, ry + dy - 6, z + 2, bd("minecraft:deepslate_bricks"));
        if ((k & 6) == 0) cv.set(x + 1, ry + 9, z + 2, bd("minecraft:campfire[lit=true,signal_fire=false]"));
        cv.set(x + 2, y, z + 1, bd("minecraft:chest[facing=south]"));
        cv.set(x + 4, y, z + 1, bd((k >>> 6 & 1) == 0 ? "minecraft:fletching_table" : "minecraft:smithing_table"));
        cv.set(x + 5, y + H1 + 1, z + 6, bd("minecraft:cobweb"));
        // a corner tower with a spire
        if (tower) {
            int tx = x + w - 1, tz = z;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy < H1 + H2 + 7; dy++)
                cv.set(tx + dx, y + dy, tz + dz, dx == 0 && dz == 0 ? air() : bd(dy % 5 == 3 && (dx == 0 || dz == 0) ? glass : "minecraft:deepslate_bricks"));
            cone(cv, tx, y + H1 + H2 + 7, tz, 2.4, 7, "minecraft:deepslate_tiles", "minecraft:polished_blackstone");
        }
    }

    // ------------------------------------------------------------------ thorn arches, lamps, cages

    static void thorns(Canvas cv, int cx, int y, int cz) {
        for (int z : new int[]{cz + 8, cz - 8, cz + 40, cz + 54}) {
            if (!cv.touches(cx - 9, z - 2, cx + 9, z + 2)) continue;
            int px = cx - 6, py = y + 1;
            for (int s = 1; s <= 12; s++) {
                double a = Math.PI * s / 12;
                int nx = cx + (int) Math.round(-Math.cos(a) * 6), ny = y + 1 + (int) Math.round(Math.sin(a) * 9);
                Decor.branch(cv, "minecraft:dark_oak_wood", px, py, z, nx, ny, z);
                if (s % 3 == 1) Decor.branch(cv, "minecraft:dark_oak_wood", nx, ny, z, nx + (s < 6 ? -2 : 2), ny + 1, z + (s % 2 == 0 ? 1 : -1));   // a thorn
                px = nx; py = ny;
            }
            cv.air(cx, y + 9, z, bd("minecraft:verdant_froglight"));   // hanging right under the arch's crown
        }
    }

    static void lamps(Canvas cv, int cx, int y, int cz) {
        for (int z = cz + 60; z > cz - 26; z -= 9) for (int side = -1; side <= 1; side += 2) lamp(cv, cx, cz, cx + side * 4, y, z);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            lamp(cv, cx, cz, cx + (int) Math.round(Math.cos(a) * 33), y, cz + (int) Math.round(Math.sin(a) * 33));
        }
    }

    /** True where a lamp would stand in the pool, a tower, the bunker or the keep's rock. */
    static boolean taken(int cx, int cz, int x, int z) {
        return Layout.dist(x, z, cx, cz + 24) < 13 || Layout.dist(x, z, cx + 38, cz + 4) < 15 || Math.abs(x - (cx - 38)) < 16 && Math.abs(z - (cz - 2)) < 18
                || Layout.dist(x, z, cx, cz - 40) < 21;
    }

    static void lamp(Canvas cv, int cx, int cz, int x, int y, int z) {
        if (!cv.inXZ(x, z) || taken(cx, cz, x, z)) return;
        cv.set(x, y + 1, z, bd("minecraft:polished_blackstone"));
        cv.set(x, y + 2, z, bd("minecraft:polished_blackstone"));
        cv.set(x, y + 3, z, bd("minecraft:polished_blackstone"));
        cv.set(x, y + 4, z, bd("minecraft:verdant_froglight"));
        cv.set(x, y + 5, z, bd("minecraft:polished_blackstone_slab[type=bottom]"));
    }

    static void cages(Canvas cv, int x, int y, int z) {
        if (!cv.touches(x - 4, z - 4, x + 12, z + 4)) return;
        for (int k = 0; k < 2; k++) {
            int cx = x + k * 6;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 3; dy++) {
                boolean e = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                cv.set(cx + dx, y + dy, z + dz, dy == 3 ? bd("minecraft:dark_oak_planks") : e ? bd("minecraft:waxed_oxidized_copper_grate") : air());
            }
        }
        cv.set(x + 11, y + 1, z, bd("minecraft:target"));
        cv.set(x + 11, y + 1, z + 2, bd("minecraft:target"));
    }
}
