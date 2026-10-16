package net.faultlinesmp.deep;

import java.util.ArrayList;
import java.util.List;

/**
 * THE DROWNED TOWN: a glass bubble over the Dark Ocean's flat build area (681, -951, floor y -50). Inside there's no
 * water: an abandoned town of timber, brick and prismarine houses and shops along its streets, a plaza with a dry
 * fountain, market stalls and a clock tower, and a clean 50 x 50 plot (smooth stone, top at y -50) kept free to build on.
 *
 *  - the dome: an ellipsoid 72 blocks across (radius) and 54 high, glass with prismarine ribs and sea-lantern bands.
 *    A block inside is air only when all six of its neighbours are inside the dome too, so no water can ever touch it.
 *  - four gatehouses (north, south, east, west) with doors in their outer face: water never flows into a door block,
 *    so you swim up, open the door and walk in dry.
 *  - the layout is fixed (the same on every seed), made once by packing lots along the streets (`LOTS`).
 */
final class Town {
    static final int CX = DeepGenerator.FLAT_X, CZ = DeepGenerator.FLAT_Z, FLOOR = DeepGenerator.FLOOR; // ground top y -50
    static final double A = 72, B = 54;          // dome radius across / height
    static final int G = FLOOR + 1;              // the first air block on the ground (-49)
    static final double SHELL = 1.6;             // shell thickness, in blocks across

    /** The 50 x 50 build plot (inclusive), its top at y -50. */
    static final int PLOT_X0 = 656, PLOT_Z0 = -1001, PLOT_X1 = 705, PLOT_Z1 = -952;

    /** Normalised distance from the dome's centre: < 1 inside. */
    static double e(double x, double y, double z) {
        double dx = (x - CX) / A, dy = (y - FLOOR) / B, dz = (z - CZ) / A;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Inside the bubble's dry air (players, for the Ocean's rules). */
    static boolean inside(double x, double y, double z) { return y > FLOOR - 0.5 && e(x, y, z) < 1 - SHELL / A; }

    /** The dome's inner height above the ground at a column (how tall a building may be there). */
    static double headroom(double x, double z) {
        double dx = (x - CX) / A, dz = (z - CZ) / A, h = 1 - dx * dx - dz * dz;
        return h <= 0 ? 0 : B * Math.sqrt(h) - 3;
    }

    // =========================================================================================== layout
    static final int HOUSE = 0, TOWNHOUSE = 1, SMITH = 2, BAKERY = 3, STORE = 4, TAVERN = 5, LIBRARY = 6, POTIONS = 7, RUIN = 8;
    static final String[] KIND_NAMES = {"house", "townhouse", "blacksmith", "bakery", "general store", "tavern", "library", "apothecary", "ruin"};

    /** One building: its frame (corner of local 0,0 + rotation), size, floors, palette, world box. */
    record Lot(int kind, int ox, int oz, int r, int w, int d, int floors, int pal, int x0, int z0, int x1, int z1) { }

    record Rect(int x0, int z0, int x1, int z1) {
        boolean overlaps(Rect o, int gap) { return x0 - gap <= o.x1 && x1 + gap >= o.x0 && z0 - gap <= o.z1 && z1 + gap >= o.z0; }
        boolean has(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }
    }

    // streets (paved), the plaza, the plot's border path
    static final Rect AVENUE = new Rect(600, -945, 762, -941), SOUTH_AVE = new Rect(679, -940, 683, -868),
            NORTH_PATH = new Rect(679, -1032, 683, -1006), SOUTH_ST = new Rect(606, -917, 756, -914),
            WEST_LANE = new Rect(628, -1006, 630, -946), EAST_LANE = new Rect(732, -1006, 734, -946),
            PLAZA = new Rect(671, -937, 691, -918), RING = new Rect(651, -1006, 710, -947), BORDER = new Rect(655, -1002, 706, -951),
            TOWER = new Rect(686, -936, 690, -932);
    static final Rect[] STREETS = {AVENUE, SOUTH_AVE, NORTH_PATH, SOUTH_ST, WEST_LANE, EAST_LANE};

    static final int PAL_COUNT = 6; // = PALS.length (PALS is made after the layout)
    static final List<Lot> LOTS = new ArrayList<>();
    static final int[] SEQUENCE = {STORE, HOUSE, BAKERY, TOWNHOUSE, SMITH, HOUSE, TAVERN, RUIN, LIBRARY, HOUSE, POTIONS, TOWNHOUSE,
            HOUSE, RUIN, HOUSE, STORE, TOWNHOUSE, HOUSE, BAKERY, RUIN, HOUSE, SMITH, TOWNHOUSE, HOUSE, POTIONS, HOUSE, RUIN, LIBRARY, HOUSE};

    static int[] size(int kind, int v) {
        return switch (kind) { // w, d, floors
            case TOWNHOUSE -> new int[]{7, 9, 3};
            case SMITH -> new int[]{9, 9, 1};
            case BAKERY -> new int[]{8, 9, 2};
            case STORE -> new int[]{9, 10, 2};
            case TAVERN -> new int[]{11, 11, 2};
            case LIBRARY -> new int[]{9, 11, 2};
            case POTIONS -> new int[]{7, 9, 2};
            default -> new int[]{7 + (v % 3), 8 + (v % 2) * 2, 2};
        };
    }

    static int height(int d, int floors) { return 4 * floors + (d + 1) / 2 + 2; }

    static {
        List<Rect> taken = new ArrayList<>(List.of(STREETS));
        taken.add(PLAZA); taken.add(RING);
        int[] idx = {0};
        // street sides: r, the front line (world), the along range (world, inclusive)
        int[][] sides = {
                {2, -947, 604, 626}, {2, -947, 632, 649}, {2, -947, 712, 730}, {2, -947, 736, 758},
                {0, -939, 604, 677}, {0, -939, 685, 758},
                {2, -919, 606, 677}, {2, -919, 685, 756},
                {0, -912, 606, 677}, {0, -912, 685, 756},
                {1, 626, -1006, -949}, {3, 632, -1006, -949}, {1, 649, -1006, -949},
                {3, 712, -1006, -949}, {1, 730, -1006, -949}, {3, 736, -1006, -949},
                {2, -1008, 606, 677}, {2, -1008, 685, 756},
                {1, 677, -910, -870}, {3, 685, -910, -870},
        };
        for (int[] s : sides) pack(s[0], s[1], s[2], s[3], taken, idx);
    }

    private static void pack(int r, int front, int a0, int a1, List<Rect> taken, int[] idx) {
        boolean up = r == 0 || r == 1; // local x runs toward +x (r 0) / +z (r 1); else toward -x / -z
        int pos = up ? a0 : a1;
        int tries = 0;
        while ((up ? pos <= a1 : pos >= a0) && tries++ < 400) {
            boolean placed = false;
            for (int attempt = 0; attempt < 3 && !placed; attempt++) {
                int kind = attempt == 0 ? SEQUENCE[idx[0] % SEQUENCE.length] : attempt == 1 ? HOUSE : RUIN;
                int[] sz = size(kind, idx[0] + attempt);
                if (attempt == 2) sz = new int[]{7, 8, 2};
                int w = sz[0], d = sz[1];
                int ox, oz;
                if (r == 0) { ox = pos; oz = front; } else if (r == 2) { ox = pos; oz = front; } else if (r == 1) { ox = front; oz = pos; } else { ox = front; oz = pos; }
                int cx0 = Integer.MAX_VALUE, cz0 = Integer.MAX_VALUE, cx1 = Integer.MIN_VALUE, cz1 = Integer.MIN_VALUE;
                for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
                    int x = wx(ox, oz, r, c[0], c[1]), z = wz(ox, oz, r, c[0], c[1]);
                    cx0 = Math.min(cx0, x); cz0 = Math.min(cz0, z); cx1 = Math.max(cx1, x); cz1 = Math.max(cz1, z);
                }
                int along1 = up ? (r == 0 ? cx1 : cz1) : (r == 2 ? cx0 : cz0);
                if (up ? along1 > a1 : along1 < a0) continue;
                Rect box = new Rect(cx0, cz0, cx1, cz1);
                boolean ok = true;
                for (Rect t : taken) if (box.overlaps(t, 1)) { ok = false; break; }
                int h = height(d, sz[2]);
                if (ok) for (int[] c : new int[][]{{cx0 - 1, cz0 - 1}, {cx1 + 1, cz0 - 1}, {cx0 - 1, cz1 + 1}, {cx1 + 1, cz1 + 1}})
                    if (headroom(c[0], c[1]) < h + 1) { ok = false; break; }
                if (!ok) continue;
                int pal = (int) (DeepGenerator.unit(DeepGenerator.hash(77, ox, oz, 5)) * PAL_COUNT);
                if (kind == SMITH) pal = 2;
                LOTS.add(new Lot(kind, ox, oz, r, w, d, sz[2], pal, cx0, cz0, cx1, cz1));
                taken.add(box);
                if (attempt == 0) idx[0]++;
                int gap = 1 + (int) (DeepGenerator.unit(DeepGenerator.hash(78, ox, oz, 6)) * 2);
                pos += (up ? 1 : -1) * (w + gap);
                placed = true;
            }
            if (!placed) pos += up ? 1 : -1;
        }
    }

    static int wx(int ox, int oz, int r, int lx, int lz) { return switch (r) { case 1 -> ox - lz; case 2 -> ox - lx; case 3 -> ox + lz; default -> ox + lx; }; }

    static int wz(int ox, int oz, int r, int lx, int lz) { return switch (r) { case 1 -> oz + lx; case 2 -> oz - lz; case 3 -> oz - lx; default -> oz + lz; }; }

    // =========================================================================================== palettes
    /** post, infill (upper floors), lower floor walls, floors, roof stairs, ridge/gable, door, trapdoor, fence, foundation. */
    record Pal(String post, String beamWood, String infill, String lower, String floor, String roof, String ridge, String gable,
               String door, String trap, String fence) { }

    static final Pal[] PALS = {
            new Pal("dark_oak_log", "dark_oak_log", "white_terracotta", "stone_bricks", "spruce_planks", "deepslate_tile_stairs", "deepslate_tiles", "dark_oak_planks", "dark_oak_door", "dark_oak_trapdoor", "dark_oak_fence"),
            new Pal("spruce_log", "spruce_log", "spruce_planks", "cobblestone", "oak_planks", "dark_oak_stairs", "dark_oak_planks", "spruce_planks", "spruce_door", "spruce_trapdoor", "spruce_fence"),
            new Pal("stone_bricks", "stripped_dark_oak_log", "bricks", "bricks", "dark_oak_planks", "spruce_stairs", "spruce_planks", "bricks", "spruce_door", "spruce_trapdoor", "spruce_fence"),
            new Pal("stripped_oak_log", "stripped_oak_log", "packed_mud", "mud_bricks", "birch_planks", "brick_stairs", "bricks", "mud_bricks", "oak_door", "oak_trapdoor", "oak_fence"),
            new Pal("dark_prismarine", "stripped_warped_hyphae", "prismarine_bricks", "dark_prismarine", "warped_planks", "dark_prismarine_stairs", "dark_prismarine", "prismarine_bricks", "warped_door", "warped_trapdoor", "warped_fence"),
            new Pal("polished_deepslate", "stripped_spruce_log", "deepslate_bricks", "deepslate_tiles", "spruce_planks", "cobbled_deepslate_stairs", "cobbled_deepslate", "deepslate_bricks", "spruce_door", "spruce_trapdoor", "spruce_fence"),
    };

    static final String[] COLORS = {"red", "blue", "green", "cyan", "purple", "orange", "brown", "light_gray", "yellow", "lime"};

    // =========================================================================================== painting
    static void paint(DeepGenerator g, Paint p) {
        if (!p.touches((int) (CX - A - 8), (int) (CZ - A - 8), (int) (CX + A + 8), (int) (CZ + A + 8))) return;
        dome(p);
        ground(p);
        for (Lot l : LOTS) if (p.touches(l.x0 - 3, l.z0 - 3, l.x1 + 3, l.z1 + 3)) building(p, l);
        plaza(p);
        Market.paint(p); // the Black Market on the plot, the Pit under it
        lamps(p);
        gates(p);
    }

    /** The bubble: air inside, glass + ribs for the shell. */
    private static void dome(Paint p) {
        double in = 1 - SHELL / A;
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx, z = p.z0 + lz;
            if (e(x, FLOOR, z) > 1) continue;
            for (int y = G; y <= FLOOR + B + 1; y++) {
                double ev = e(x, y, z);
                if (ev > 1) break;
                boolean air = ev < in && e(x + 1, y, z) <= 1 && e(x - 1, y, z) <= 1 && e(x, y + 1, z) <= 1 && e(x, y, z + 1) <= 1 && e(x, y, z - 1) <= 1;
                p.set(x, y, z, air ? "air" : shell(x, y, z));
            }
        }
    }

    private static String shell(int x, int y, int z) {
        double dx = x - CX, dz = z - CZ, hr = Math.hypot(dx, dz), dy = y - FLOOR;
        if (hr < 2.6) return "sea_lantern";                      // the crown
        if (y <= G + 1) return "dark_prismarine";               // the base ring
        double ang = Math.atan2(dz, dx), seg = Math.PI / 8;
        boolean rib = hr > 5 && Math.abs(Math.IEEEremainder(ang, seg)) * hr < 0.75;
        boolean band = Math.abs(dy - 15) < 0.7 || Math.abs(dy - 31) < 0.7 || Math.abs(dy - 45) < 0.7;
        if (rib && band) return "sea_lantern";
        if (rib) return Math.floorMod(y, 6) == 0 ? "sea_lantern" : "dark_prismarine";
        if (band) return "prismarine_bricks";
        double r = Paint.u(x, y, z, 7);
        return r < 0.55 ? "glass" : r < 0.85 ? "light_blue_stained_glass" : r < 0.97 ? "cyan_stained_glass" : "tinted_glass";
    }

    static boolean street(int x, int z) {
        for (Rect s : STREETS) if (s.has(x, z)) return true;
        return RING.has(x, z) && !BORDER.has(x, z);
    }

    /** The floor inside: overgrown earth, paved streets, the plaza, the plot. */
    private static void ground(Paint p) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = p.x0 + lx, z = p.z0 + lz;
            if (e(x, FLOOR, z) > 1 - SHELL / A) continue;
            double r = Paint.u(x, FLOOR, z, 11);
            String top, under = "dirt", deco = null;
            if (x >= PLOT_X0 && x <= PLOT_X1 && z >= PLOT_Z0 && z <= PLOT_Z1) { top = "smooth_stone"; under = "stone"; }
            else if (BORDER.has(x, z)) top = "chiseled_stone_bricks";
            else if (PLAZA.has(x, z)) {
                double d = Math.hypot(x - 681, z + 927); // rings round the fountain
                top = ((int) d) % 3 == 0 ? "polished_andesite" : r < 0.15 ? "cracked_stone_bricks" : r < 0.3 ? "mossy_stone_bricks" : "stone_bricks";
                under = "stone";
            } else if (street(x, z)) {
                top = r < 0.38 ? "stone_bricks" : r < 0.52 ? "cracked_stone_bricks" : r < 0.66 ? "mossy_stone_bricks" : r < 0.82 ? "cobblestone"
                        : r < 0.93 ? "andesite" : "gravel";
                under = "stone";
            } else {
                top = r < 0.34 ? "coarse_dirt" : r < 0.54 ? "podzol" : r < 0.68 ? "rooted_dirt" : r < 0.9 ? "moss_block" : "gravel";
                double q = Paint.u(x, G, z, 12);
                if (q < 0.07) deco = "moss_carpet"; else if (q < 0.085) deco = "dead_bush"; else if (q < 0.092) deco = "brown_mushroom";
                else if (q < 0.096) deco = "fern";
            }
            p.set(x, FLOOR, z, top);
            p.set(x, FLOOR - 1, z, under);
            if (deco != null && p.get(x, G, z).isAir()) p.set(x, G, z, deco);
        }
    }

    // =========================================================================================== buildings
    private static void building(Paint p, Lot l) {
        Paint.Frame f = p.frame(l.ox, l.oz, l.r, false);
        Pal pal = PALS[l.pal];
        int w = l.w, d = l.d, n = l.floors, kind = l.kind;
        boolean ruin = kind == RUIN;
        int wallTop = FLOOR + 4 * n;              // the beam row over the top floor
        int door = w / 2;
        boolean shop = kind == STORE || kind == BAKERY || kind == POTIONS || kind == TAVERN;
        // clear the lot (ground decorations) and lay the foundation + ground floor
        f.fill(-1, G, -1, w, G, d, "air");
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            f.set(x, FLOOR - 1, z, "cobblestone");
            f.set(x, FLOOR, z, edge ? (kind == SMITH ? "cobblestone" : "stone_bricks") : kind == SMITH ? (f.u(x, FLOOR, z, 3) < 0.5 ? "stone" : "cobblestone") : pal.floor());
        }
        // walls, floor by floor
        for (int i = 0; i < n; i++) {
            int fy = FLOOR + 4 * i;
            for (int y = fy + 1; y <= fy + 4; y++)
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    boolean ex = x == 0 || x == w - 1, ez = z == 0 || z == d - 1;
                    if (!ex && !ez) {
                        if (y == fy + 4) f.set(x, y, z, i == n - 1 ? pal.gable() : pal.floor()); // ceiling / next floor
                        continue;
                    }
                    if (ruin && f.u(x, y, z, 21) < 0.05 + Math.max(0, y - (G + 2)) * 0.13) continue;
                    String m;
                    if (ex && ez) m = pal.post().endsWith("_log") || pal.post().contains("hyphae") ? pal.post() + "[axis=y]" : pal.post();
                    else if (y == fy + 4) m = pal.beamWood() + "[axis=" + (ez ? "x" : "z") + "]";
                    else m = i == 0 ? pal.lower() : pal.infill();
                    if (!ruin && !(ex && ez) && y != fy + 4 && f.u(x, y, z, 22) < 0.025) continue; // holes
                    f.set(x, y, z, m);
                }
            windows(f, l, pal, i, fy, door, shop);
        }
        // the door
        double du = f.u(door, G, 0, 31);
        if (du > 0.18) {
            String open = du > 0.75 ? "true" : "false";
            f.set(door, G, 0, pal.door() + "[facing=south,half=lower,hinge=left,open=" + open + "]");
            f.set(door, G + 1, 0, pal.door() + "[facing=south,half=upper,hinge=left,open=" + open + "]");
        } else { f.air(door, G, 0); f.air(door, G + 1, 0); }
        if (kind == SMITH) { f.air(door - 1, G, 0); f.air(door + 1, G, 0); f.air(door - 1, G + 1, 0); f.air(door + 1, G + 1, 0); }
        // shop awning / porch hood
        if (shop || kind == STORE) {
            String a = COLORS[(l.pal + kind) % COLORS.length], b2 = kind == BAKERY ? "orange" : "white";
            for (int x = 0; x < w; x++) {
                if (ruin || f.u(x, G + 3, -1, 41) < 0.12) continue;
                String c = (x % 2 == 0 ? a : b2) + "_wool";
                f.set(x, G + 3, -1, c);
                if (f.u(x, G + 2, -2, 42) > 0.2) f.set(x, G + 2, -2, (x % 2 == 0 ? a : b2) + "_carpet");
            }
            f.set(0, G, -2, pal.fence()); f.set(0, G + 1, -2, pal.fence()); f.set(0, G + 2, -2, (a) + "_wool");
            f.set(w - 1, G, -2, pal.fence()); f.set(w - 1, G + 1, -2, pal.fence()); f.set(w - 1, G + 2, -2, (w % 2 == 0 ? b2 : a) + "_wool");
        } else if (!ruin) {
            for (int x = door - 1; x <= door + 1; x++) f.set(x, G + 2, -1, pal.roof() + "[facing=south,half=top,shape=straight]");
        }
        // a step of stairs/slab at the door and a barrel or two by it
        if (!ruin && f.u(door, G, -1, 43) < 0.5) f.set(door + (w > 7 ? 2 : 1), G, -1, "barrel[facing=up]");
        // ladder up through the floors
        if (n > 1) for (int y = G; y <= FLOOR + 4 * (n - 1); y++) f.set(1, y, d - 2, "ladder[facing=north]");
        // fireplace + chimney
        boolean chimney = kind == SMITH || kind == BAKERY || kind == TAVERN || (kind != LIBRARY && f.u(0, 0, 0, 51) < 0.55);
        if (chimney && !ruin) {
            int cx = w - 2;
            f.set(cx - 1, G, d - 2, "bricks");
            f.set(cx, G, d - 2, kind == SMITH ? "magma_block" : "campfire[lit=false,facing=north]");
            f.set(cx - 1, G + 1, d - 2, "brick_stairs[facing=south,half=top]");
            f.set(cx, G + 1, d - 2, "air");
            f.set(cx, G + 2, d - 2, "bricks");
            int top = roofY(wallTop, d, d - 1) + 2;
            for (int y = FLOOR; y <= top; y++) f.set(cx, y, d - 1, y == top ? "brick_wall" : "bricks");
        }
        for (int i = 0; i < n; i++) furnish(f, l, i, FLOOR + 4 * i, door, chimney);
        roof(f, l, pal, wallTop, ruin);
        if (chimney && !ruin) { // the chimney goes through the roof
            int top = roofY(wallTop, d, d - 1) + 2;
            for (int y = wallTop + 1; y <= top; y++) f.set(w - 2, y, d - 1, y == top ? "brick_wall" : "bricks");
        }
        if (ruin) rubble(f, l);
    }

    private static int roofY(int wallTop, int d, int z) { int k = Math.min(z + 1, d - z); return wallTop + 1 + k; }

    private static void windows(Paint.Frame f, Lot l, Pal pal, int i, int fy, int door, boolean shop) {
        int w = l.w, d = l.d;
        String pane = l.kind == SMITH ? "iron_bars" : "glass_pane";
        for (int side = 0; side < 4; side++) { // front, back (along local x), left, right (along z)
            int len = side < 2 ? w : d;
            boolean bigFront = shop && i == 0 && side == 0; // a shop window across the whole front
            String conn = side < 2 ? "[east=true,west=true]" : "[north=true,south=true]";
            for (int a = 1; a <= len - 2; a++) {
                boolean win = bigFront || (len <= 8 ? a == 2 || a == len - 3 : (a - 2) % 3 == 0 && a <= len - 3);
                if (!win) continue;
                if (side == 0 && i == 0 && Math.abs(a - door) <= (bigFront ? 0 : 1)) continue;
                int x = side < 2 ? a : side == 2 ? 0 : w - 1;
                int z = side == 0 ? 0 : side == 1 ? d - 1 : a;
                if (side == 1 && x == w - 2) continue; // the chimney
                for (int y = fy + 2; y <= fy + 3; y++) {
                    double r = f.u(x, y, z, 61);
                    if (l.kind == RUIN || r < 0.2) f.air(x, y, z);
                    else if (r < 0.28) f.set(x, y, z, "cobweb");
                    else f.set(x, y, z, pane + conn);
                }
                // shutters beside the upper floors' front windows
                if (side == 0 && i > 0 && l.kind != RUIN && f.u(x, fy, z, 63) < 0.7)
                    for (int dx : new int[]{-1, 1}) {
                        if (x + dx <= 0 || x + dx >= w - 1 || f.u(x + dx, fy, z, 64) < 0.25) continue;
                        f.set(x + dx, fy + 2, -1, pal.trap() + "[facing=north,open=true,half=top]");
                        f.set(x + dx, fy + 3, -1, pal.trap() + "[facing=north,open=true,half=bottom]");
                    }
            }
        }
    }

    private static void roof(Paint.Frame f, Lot l, Pal pal, int wallTop, boolean ruin) {
        int w = l.w, d = l.d;
        for (int k = 0; ; k++) {
            int zf = -1 + k, zb = d - k, y = wallTop + 1 + k;
            if (zf > zb) break;
            for (int x = -1; x <= w; x++) {
                boolean gone = ruin ? f.u(x, y, zf, 71) < 0.7 : f.u(x, y, zf, 71) < 0.05;
                boolean gone2 = ruin ? f.u(x, y, zb, 72) < 0.7 : f.u(x, y, zb, 72) < 0.05;
                if (zf == zb) { if (!gone) f.set(x, y, zf, pal.ridge()); continue; }
                if (!gone) f.set(x, y, zf, pal.roof() + "[facing=south,half=bottom,shape=straight]");
                if (!gone2) f.set(x, y, zb, pal.roof() + "[facing=north,half=bottom,shape=straight]");
                if (zf + 1 == zb && !ruin) { // an even depth: a ridge of slabs over the last two
                    String slab = pal.roof().replace("_stairs", "_slab");
                    f.set(x, y + 1, zf, slab + "[type=bottom]"); f.set(x, y + 1, zb, slab + "[type=bottom]");
                }
                if ((x == 0 || x == w - 1) && !(ruin && y > wallTop + 2)) for (int z = zf + 1; z < zb; z++) f.set(x, y, z, pal.gable());
            }
            // upside-down eaves under the overhang at the front and back
            if (k == 0 && !ruin) for (int x = 0; x < w; x++) {
                f.set(x, wallTop, -1, pal.roof() + "[facing=north,half=top,shape=straight]");
                f.set(x, wallTop, d, pal.roof() + "[facing=south,half=top,shape=straight]");
            }
            // an attic window in the gables
            if (k == 2 && !ruin) { int mz = d / 2; f.set(0, y, mz, "glass_pane[north=true,south=true]"); f.set(w - 1, y, mz, "glass_pane[north=true,south=true]"); }
        }
    }

    /** Fallen stone, gravel and cobwebs in a collapsed house. */
    private static void rubble(Paint.Frame f, Lot l) {
        for (int x = 1; x < l.w - 1; x++) for (int z = 1; z < l.d - 1; z++) {
            double r = f.u(x, G, z, 81);
            if (r < 0.18) f.set(x, G, z, r < 0.08 ? "cobblestone" : r < 0.13 ? "mossy_cobblestone" : "stone_brick_slab[type=bottom]");
            else if (r < 0.24) f.set(x, G, z, "cobweb");
            else if (r < 0.27) f.set(x, G, z, "skeleton_skull[rotation=" + (int) (r * 100) % 16 + "]");
            // the upper floor fell in: holes
            for (int i = 1; i < l.floors; i++) if (f.u(x, FLOOR + 4 * i, z, 82) < 0.55) f.air(x, FLOOR + 4 * i, z);
        }
    }

    // ------------------------------------------------------------------------------------------- interiors
    private static void furnish(Paint.Frame f, Lot l, int i, int fy, int door, boolean chimney) {
        int w = l.w, d = l.d, y = fy + 1, kind = l.kind;
        String bed = COLORS[(l.pal * 3 + i) % COLORS.length];
        String rug = COLORS[(l.pal + 5 + i) % COLORS.length];
        int backMax = chimney && i == 0 ? w - 3 : w - 2;
        if (kind == RUIN) { if (i == 0) put(f, 2, y, d - 2, "chest[facing=north]", 0.5); return; }
        if (i == 0) {
            switch (kind) {
                case SMITH -> {
                    f.set(w / 2, y, d / 2, "chipped_anvil[facing=east]");
                    f.set(w / 2 + 1, y, d / 2 + 1, "grindstone[face=floor,facing=north]");
                    f.set(2, y, d - 2, "blast_furnace[facing=north]"); f.set(3, y, d - 2, "smithing_table");
                    f.set(4, y, d - 2, "furnace[facing=north]"); f.set(1, y, 2, "cauldron");
                    f.set(1, y, 3, "barrel[facing=east]"); f.set(1, y + 1, 3, "barrel[facing=east]"); put(f, 1, y, 4, "chest[facing=east]", 0);
                    f.set(w - 2, y, 2, "damaged_anvil[facing=north]"); f.set(w - 2, y, 3, "stonecutter[facing=west]");
                    f.set(w / 2, y + 2, d / 2 - 1, "iron_chain"); f.set(w / 2, y + 1, d / 2 - 1, "lantern[hanging=true]");
                }
                case BAKERY, STORE, POTIONS -> {
                    // a counter across the shop with a gap at the right end
                    int cz = 3;
                    for (int x = 1; x <= w - 3; x++) f.set(x, y, cz, "spruce_stairs[facing=north,half=top,shape=straight]");
                    if (kind == BAKERY) {
                        f.set(2, y + 1, cz, "cake[bites=" + (int) (f.u(2, y, cz, 91) * 6) + "]");
                        f.set(2, y, d - 2, "smoker[facing=north]"); f.set(3, y, d - 2, "smoker[facing=north]");
                        f.set(4, y, d - 2, "barrel[facing=up]"); f.set(w - 2, y, cz + 1, "hay_block[axis=y]"); f.set(w - 2, y + 1, cz + 1, "hay_block[axis=x]");
                        f.set(1, y, cz + 2, "composter"); put(f, w - 2, y, cz + 2, "chest[facing=west]", 0);
                        f.set(w - 2, y, 1, "barrel[facing=up]"); f.set(1, y, 1, "barrel[facing=up]"); f.set(1, y + 1, 1, "potted_dead_bush");
                    } else if (kind == STORE) {
                        f.set(1, y + 1, cz, "decorated_pot[facing=north]"); f.set(3, y + 1, cz, "flower_pot");
                        for (int x = 2; x <= backMax; x++) {
                            f.set(x, y, d - 2, x % 2 == 0 ? "barrel[facing=north]" : "chiseled_bookshelf[facing=north]");
                            f.set(x, y + 1, d - 2, x % 2 == 0 ? "chiseled_bookshelf[facing=north]" : "barrel[facing=north]");
                        }
                        put(f, w - 2, y, cz + 1, "chest[facing=west]", 0);
                        f.set(1, y, 1, "pumpkin"); f.set(w - 2, y, 1, "melon"); f.set(w - 2, y + 1, 1, "carved_pumpkin[facing=north]");
                        f.set(2, y, 1, "decorated_pot[facing=north]");
                    } else { // the apothecary
                        f.set(1, y + 1, cz, "brewing_stand"); f.set(3, y + 1, cz, "potted_crimson_fungus");
                        f.set(2, y, d - 2, "cauldron"); f.set(3, y, d - 2, "bookshelf");
                        f.set(w - 2, y, cz + 1, "soul_sand"); f.set(w - 2, y + 1, cz + 1, "nether_wart[age=3]");
                        f.set(w - 2, y, cz + 2, "soul_sand"); f.set(w - 2, y + 1, cz + 2, "nether_wart[age=2]");
                        f.set(1, y, 1, "potted_red_mushroom"); f.set(w - 2, y, 1, "potted_warped_fungus");
                        f.set(1, y, cz + 1, "brewing_stand"); put(f, 1, y, cz + 2, "chest[facing=east]", 0);
                    }
                }
                case TAVERN -> {
                    for (int x = 2; x <= w - 3; x++) f.set(x, y, d - 4, "dark_oak_stairs[facing=north,half=top,shape=straight]");
                    for (int x = 2; x <= w - 3; x++) { f.set(x, y, d - 2, "barrel[facing=north]"); if (x % 2 == 0) f.set(x, y + 1, d - 2, "barrel[facing=north]"); }
                    f.set(3, y + 1, d - 4, "brewing_stand"); f.set(5, y + 1, d - 4, "candle[candles=3,lit=false]");
                    table(f, 3, y, 3, rug); table(f, w - 4, y, 3, rug); table(f, w / 2, y, d / 2, rug);
                    put(f, w - 2, y, d - 3, "chest[facing=west]", 0); f.set(1, y, 1, "cauldron");
                    f.set(w / 2, y + 2, 2, "lantern[hanging=true]"); f.set(w / 2, y + 2, d - 5, "lantern[hanging=true]");
                }
                case LIBRARY -> {
                    for (int z = 2; z <= d - 3; z++) for (int dy = 0; dy < 2; dy++) {
                        f.set(1, y + dy, z, (z + dy) % 4 == 0 ? "chiseled_bookshelf[facing=east]" : "bookshelf");
                        f.set(w - 2, y + dy, z, (z + dy) % 5 == 0 ? "chiseled_bookshelf[facing=west]" : "bookshelf");
                    }
                    f.set(w / 2 - 1, y, d / 2, "lectern[facing=south]"); f.set(w / 2 + 1, y, d / 2, "lectern[facing=south]");
                    f.set(w / 2, y, d - 3, "spruce_slab[type=top]"); f.set(w / 2, y + 1, d - 3, "candle[candles=4,lit=false]");
                    put(f, w / 2 + 1, y, d - 2, "chest[facing=north]", 0); f.set(w / 2 - 1, y, d - 2, "cartography_table");
                    f.set(w / 2, y + 2, d / 2, "lantern[hanging=true]");
                }
                default -> { // a home's ground floor
                    table(f, w / 2, y, d / 2, rug);
                    String[] back = {"crafting_table", "furnace[facing=north]", "barrel[facing=up]", "chest[facing=north]", "bookshelf"};
                    for (int x = 2, k = (int) (f.u(0, y, 0, 92) * 5); x <= backMax; x++, k++) put(f, x, y, d - 2, back[k % back.length], 0);
                    f.set(1, y, 2, "bookshelf"); f.set(1, y + 1, 2, "bookshelf");
                    if (d > 8) f.set(1, y, 3, "barrel[facing=east]");
                    f.set(w - 2, y, 1, "barrel[facing=up]"); f.set(w - 2, y + 1, 1, f.u(1, y, 1, 93) < 0.5 ? "potted_dead_bush" : "potted_fern");
                    if (f.u(2, y, 2, 94) < 0.5) f.set(w / 2, y + 2, d / 2, "lantern[hanging=true]");
                }
            }
        } else if (kind == TAVERN || (kind == STORE && i == 1) || (kind == LIBRARY)) {
            if (kind == STORE) { // the stockroom
                for (int x = 2; x < w - 1; x++) for (int dy = 0; dy < 2; dy++) if ((x + dy) % 3 != 0) f.set(x, y + dy, d - 2, "barrel[facing=north]");
                f.set(w - 2, y, 2, "hay_block[axis=y]"); put(f, w - 2, y, 3, "chest[facing=west]", 0); f.set(2, y, 2, "barrel[facing=up]");
            } else if (kind == LIBRARY) {
                for (int x = 2; x < w - 2; x++) f.set(x, y, d - 2, x % 2 == 0 ? "bookshelf" : "chiseled_bookshelf[facing=north]");
                bed(f, w - 2, y, 2, bed); put(f, w - 2, y, 4, "chest[facing=west]", 0);
                f.set(w / 2, y, d / 2, "enchanting_table");
            } else { // tavern rooms: beds along both sides
                bed(f, w - 2, y, 2, bed); bed(f, w - 2, y, 5, COLORS[(l.pal + 7) % COLORS.length]); bed(f, 2, y, 2, COLORS[(l.pal + 2) % COLORS.length]);
                put(f, w - 3, y, 2, "chest[facing=north]", 0.3); f.set(3, y, 2, "barrel[facing=up]");
            }
        } else { // a bedroom
            bed(f, w - 2, y, d - 3, bed);
            if (w >= 9) bed(f, w - 4, y, d - 3, COLORS[(l.pal + 4) % COLORS.length]);
            put(f, w - 2, y, d - 4 > 1 ? d - 4 : 1, "chest[facing=west]", 0.35);
            f.set(w - 3 > 1 ? w - 3 : 2, y, d - 2, "barrel[facing=up]");
            f.set(2, y, d - 2, f.u(2, y, 3, 95) < 0.5 ? "loom[facing=north]" : "bookshelf");
            f.set(1, y, 2, "bookshelf");
            for (int x = w / 2 - 1; x <= w / 2; x++) for (int z = 2; z <= 3; z++) if (f.u(x, y, z, 96) > 0.25) f.set(x, y, z, rug + "_carpet");
        }
        // cobwebs high in the corners, moss creeping over the floor
        for (int x = 1; x < w - 1; x++) for (int z = 1; z < d - 1; z++) {
            boolean corner = (x == 1 || x == w - 2) && (z == 1 || z == d - 2);
            if (x == 1 && z == d - 2) continue; // the ladder
            double r = f.u(x, fy + 3, z, 97);
            if ((corner && r < 0.6) || r < 0.04) if (f.get(x, fy + 3, z).isAir()) f.set(x, fy + 3, z, "cobweb");
            double q = f.u(x, y, z, 98);
            if (q < 0.05 && f.get(x, y, z).isAir() && !(x == door && z <= 2)) f.set(x, y, z, "moss_carpet");
        }
    }

    /** Places s unless the roll says skip. A chest is a Dark Loot chest (FaultlineItems fills it on first open), so only 30% stay chests; the rest are empty barrels. */
    private static void put(Paint.Frame f, int x, int y, int z, String s, double skip) {
        if (f.u(x, y, z, 99) < skip) return;
        if (s.startsWith("chest") && f.u(x, y, z, 100) > 0.3) s = "barrel[facing=up]";
        f.set(x, y, z, s);
    }

    /** A table (a fence and a carpet) with a chair (stairs) either side. */
    private static void table(Paint.Frame f, int x, int y, int z, String rug) {
        f.set(x, y, z, "spruce_fence"); f.set(x, y + 1, z, rug + "_carpet");
        f.set(x - 1, y, z, "spruce_stairs[facing=west,half=bottom,shape=straight]");
        f.set(x + 1, y, z, "spruce_stairs[facing=east,half=bottom,shape=straight]");
    }

    /** A bed along local z, its head toward +z (the back). */
    private static void bed(Paint.Frame f, int x, int y, int z, String color) {
        f.set(x, y, z, color + "_bed[part=foot,facing=south]");
        f.set(x, y, z + 1, color + "_bed[part=head,facing=south]");
    }

    // =========================================================================================== the plaza
    private static void plaza(Paint p) {
        if (!p.touches(PLAZA.x0, PLAZA.z0, PLAZA.x1, PLAZA.z1)) return;
        int fx = 681, fz = -927;
        Paint.Frame f = p.frame(fx, fz, 0, false);
        // the fountain: a round basin with still water, a column in the middle with a sea lantern on top
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            double r = Math.hypot(x, z);
            if (r < 3.5) { f.set(x, FLOOR, z, "prismarine_bricks"); f.set(x, G, z, "water"); }
            else if (r < 4.6) { f.set(x, G, z, "stone_bricks"); f.set(x, G + 1, z, "stone_brick_slab[type=bottom]"); }
        }
        f.set(0, G, 0, "chiseled_stone_bricks"); f.set(0, G + 1, 0, "stone_brick_wall"); f.set(0, G + 2, 0, "stone_brick_wall");
        f.set(0, G + 3, 0, "prismarine_bricks"); f.set(0, G + 4, 0, "sea_lantern"); f.set(0, G + 5, 0, "prismarine_wall");
        for (int[] c : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) f.set(c[0], G + 3, c[1], "prismarine_stairs[facing=" + dir(-c[0], -c[1]) + ",half=top,shape=straight]");
        // benches facing the fountain
        for (int[] c : new int[][]{{0, -7}, {0, 7}, {-7, 0}, {7, 0}}) {
            int ax = c[1] != 0 ? 1 : 0, az = c[0] != 0 ? 1 : 0;
            for (int k = -1; k <= 1; k++) f.set(c[0] + ax * k, G, c[1] + az * k, "spruce_stairs[facing=" + dir(Integer.signum(c[0]), Integer.signum(c[1])) + ",half=bottom,shape=straight]");
        }
        // market stalls in three corners
        stall(f, -9, -9, "red"); stall(f, -9, 6, "yellow"); stall(f, 5, 6, "blue");
        // the clock tower in the fourth
        clockTower(p.frame(TOWER.x0, TOWER.z0, 0, false));
    }

    static String dir(int dx, int dz) { return dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north"; }

    private static void stall(Paint.Frame f, int x0, int z0, String color) {
        for (int[] c : new int[][]{{0, 0}, {3, 0}, {0, 2}, {3, 2}}) for (int y = G; y <= G + 2; y++) f.set(x0 + c[0], y, z0 + c[1], "spruce_fence");
        for (int x = 0; x <= 3; x++) for (int z = 0; z <= 2; z++) f.set(x0 + x, G + 3, z0 + z, ((x + z) % 2 == 0 ? color : "white") + "_wool");
        f.set(x0 + 1, G, z0, "barrel[facing=up]"); f.set(x0 + 2, G, z0, "composter");
        String[] goods = {"melon", "pumpkin", "hay_block[axis=y]", "decorated_pot[facing=north]"};
        f.set(x0 + 1, G + 1, z0, goods[Math.floorMod(x0 + z0, 4)]);
        f.set(x0 + 1, G, z0 + 2, "barrel[facing=up]"); f.set(x0 + 2, G, z0 + 2, goods[Math.floorMod(x0 + z0 + 1, 4)]);
    }

    private static void clockTower(Paint.Frame f) {
        int top = G + 20, belfry = top - 5;
        for (int y = FLOOR; y <= top; y++) for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            boolean edge = x == 0 || z == 0 || x == 4 || z == 4, corner = (x == 0 || x == 4) && (z == 0 || z == 4);
            String m;
            if (y == FLOOR) m = "polished_andesite";
            else if (!edge) m = y == belfry ? "spruce_planks" : "air";
            else if (corner) m = "polished_andesite";
            else if (y >= top - 4 && y <= top - 3) m = "air";                          // the belfry's open arches
            else if (y == top - 2) m = "stone_brick_slab[type=top]";
            else m = y % 6 == 0 ? "chiseled_stone_bricks" : "stone_bricks";
            f.set(x, y, z, m);
        }
        f.set(2, G, 4, "spruce_door[facing=north,half=lower,hinge=left,open=true]"); // the doorway faces the fountain
        f.set(2, G + 1, 4, "spruce_door[facing=north,half=upper,hinge=left,open=true]");
        for (int y = G; y <= belfry; y++) f.set(2, y, 1, "ladder[facing=south]");
        f.set(2, top - 1, 2, "bell[attachment=ceiling,facing=north]");
        f.set(3, belfry + 1, 3, "lantern[hanging=false]");
        // the four clock faces
        int cy = top - 8;
        for (int[] s : new int[][]{{2, -1, 0}, {2, 5, 0}, {-1, 2, 1}, {5, 2, 1}})
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                int x = s[2] == 0 ? s[0] + a : s[0], z = s[2] == 0 ? s[1] : s[1] + a;
                boolean hand = (a == 0 && b >= 0) || (b == 0 && a == 1);
                f.set(x, cy + b, z, hand ? "black_concrete" : "smooth_quartz");
            }
        // a stone pyramid roof with a spire
        for (int k = 0; k <= 2; k++) for (int x = -1 + k; x <= 5 - k; x++) for (int z = -1 + k; z <= 5 - k; z++) {
            boolean e = x == -1 + k || x == 5 - k || z == -1 + k || z == 5 - k;
            if (!e) { f.set(x, top + k, z, "deepslate_tiles"); continue; }
            String fc = x == -1 + k ? "east" : x == 5 - k ? "west" : z == -1 + k ? "south" : "north";
            f.set(x, top + k, z, "deepslate_tile_stairs[facing=" + fc + ",half=bottom,shape=straight]");
        }
        f.set(2, top + 3, 2, "deepslate_tile_wall"); f.set(2, top + 4, 2, "lightning_rod");
    }

    // =========================================================================================== lamp posts
    private static void lamps(Paint p) {
        int[][] posts = lampSpots();
        for (int[] s : posts) {
            if (!p.in(s[0], G, s[1])) continue;
            if (!p.get(s[0], G, s[1]).isAir() && p.get(s[0], G, s[1]) != org.bukkit.Material.MOSS_CARPET) continue;
            p.set(s[0], G, s[1], "stone_brick_wall");
            for (int y = G + 1; y <= G + 3; y++) p.set(s[0], y, s[1], "dark_oak_fence");
            p.set(s[0], G + 4, s[1], Paint.u(s[0], 0, s[1], 5) < 0.12 ? "dark_oak_fence" : "lantern[hanging=false]"); // a few lamps are broken
        }
    }

    private static int[][] SPOTS;

    static synchronized int[][] lampSpots() {
        if (SPOTS != null) return SPOTS;
        List<int[]> out = new ArrayList<>();
        for (int x = 606; x <= 756; x += 9) { out.add(new int[]{x, -946}); out.add(new int[]{x + 4, -940}); }
        for (int x = 610; x <= 752; x += 9) { out.add(new int[]{x, -918}); out.add(new int[]{x + 4, -913}); }
        for (int z = -936; z >= -1030; z -= 9) { if (z > -945 || z < -1006) { out.add(new int[]{678, z}); out.add(new int[]{684, z}); } }
        for (int z = -910; z <= -870; z += 8) { out.add(new int[]{678, z}); out.add(new int[]{684, z}); }
        for (int z = -948; z >= -1004; z -= 9) { out.add(new int[]{627, z}); out.add(new int[]{631, z}); out.add(new int[]{731, z}); out.add(new int[]{735, z}); }
        out.add(new int[]{PLOT_X0 - 2, PLOT_Z0 - 2}); out.add(new int[]{PLOT_X1 + 2, PLOT_Z0 - 2});
        out.add(new int[]{PLOT_X0 - 2, PLOT_Z1 + 2}); out.add(new int[]{PLOT_X1 + 2, PLOT_Z1 + 2});
        for (int x = PLOT_X0 + 10; x < PLOT_X1; x += 10) { out.add(new int[]{x, PLOT_Z0 - 3}); out.add(new int[]{x, PLOT_Z1 + 3}); }
        for (int z = PLOT_Z0 + 10; z < PLOT_Z1; z += 10) { out.add(new int[]{PLOT_X0 - 3, z}); out.add(new int[]{PLOT_X1 + 3, z}); }
        out.removeIf(s -> headroom(s[0], s[1]) < 8 || inLot(s[0], s[1]) || (PLAZA.has(s[0], s[1])));
        return SPOTS = out.toArray(new int[0][]);
    }

    static boolean inLot(int x, int z) {
        for (Lot l : LOTS) if (x >= l.x0 - 1 && x <= l.x1 + 1 && z >= l.z0 - 2 && z <= l.z1 + 2) return true;
        return false;
    }

    // =========================================================================================== the gates
    /** {axis (0 = x, 1 = z), sign, the across coordinate}: E and W on the avenue, N and S on the north path / south avenue. */
    static final int[][] GATES = {{0, 1, -943}, {0, -1, -943}, {1, 1, 681}, {1, -1, 681}};

    /** Where a gate's door is (world x, z) and which way is out. */
    static int[] gateDoor(int[] gt) {
        int axis = gt[0], sign = gt[1], c = gt[2];
        int k = 0;
        while (k < 80) {
            int nx = axis == 0 ? CX + sign * (k + 1) : c, nz = axis == 0 ? c : CZ + sign * (k + 1);
            if (e(nx, G, nz) > 1) break;
            k++;
        }
        int door = k + 2;
        return axis == 0 ? new int[]{CX + sign * door, c} : new int[]{c, CZ + sign * door};
    }

    private static void gates(Paint p) {
        for (int[] gt : GATES) {
            int axis = gt[0], sign = gt[1], c = gt[2];
            int[] dpos = gateDoor(gt);
            int door = axis == 0 ? Math.abs(dpos[0] - CX) : Math.abs(dpos[1] - CZ);
            // in the gate's own frame: s = outward from the centre, q = across
            for (int s = door - 8; s <= door + 14; s++) for (int q = -3; q <= 3; q++) {
                int x = axis == 0 ? CX + sign * s : c + q, z = axis == 0 ? c + q : CZ + sign * s;
                if (!p.in(x, G, z)) continue;
                if (s > door) { // the approach outside: a lit path on the sea floor, lanterns every third block
                    if (Math.abs(q) > 1) continue;
                    for (int y = G + 6; y >= FLOOR - 4; y--) {
                        org.bukkit.Material m = p.get(x, y, z);
                        if (m.isSolid()) { p.set(x, y, z, s % 3 == 0 && q == 0 ? "sea_lantern" : "polished_deepslate"); break; }
                    }
                    continue;
                }
                boolean passage = Math.abs(q) <= 1;
                for (int y = FLOOR; y <= G + 4; y++) {
                    String m;
                    if (y == FLOOR) m = passage ? "polished_deepslate" : "polished_blackstone_bricks";
                    else if (passage && s < door && y <= G + 2) m = "air";
                    else if (passage && s == door && y <= G + 1)
                        m = "dark_oak_door[facing=" + inward(axis, sign) + ",half=" + (y == G ? "lower" : "upper") + ",hinge=" + (q > 0 ? "right" : "left") + ",open=false]";
                    else if (s == door && Math.abs(q) == 3) m = "dark_prismarine";
                    else if (s == door && q == 0 && y == G + 3) m = "sea_lantern";
                    else m = "polished_blackstone_bricks";
                    p.set(x, y, z, Paint.weather(m, x, y, z));
                }
            }
        }
    }

    static String inward(int axis, int sign) { return axis == 0 ? (sign > 0 ? "west" : "east") : (sign > 0 ? "north" : "south"); }

    /** Where to put a player who wants to visit (the plot's south-west corner, on the path). */
    static int[] plotSpawn() { return new int[]{(PLOT_X0 + PLOT_X1) / 2, G, PLOT_Z1 + 2}; }
}
