package net.faultlinesmp.deep;

/**
 * THE BLACK MARKET, on the Bubble's plot (x 656..705, z -1001..-952, ground top y -50), and THE PIT, an arena under it.
 *
 *  - outside: a gothic hall of blackstone (x 663..698, z -995..-962) with buttresses, four turrets with spires, purple
 *    lancet windows, a steep deepslate roof, a crimson double door under a pointed arch and, in the front gable, a giant
 *    eye with a slit pupil. A walled forecourt with soul lanterns and two hooded statues.
 *  - inside it's dim: soul-lantern chandeliers hang on chains from timber trusses under the vault. The QUEUE snakes
 *    between chain stanchions on red carpet to the DESK (a service window with a bell). Behind the desk: bookshelves, and
 *    THE TIER WALL: eight columns (tiers 1-8), two empty shulker boxes each, coloured by tier, a candle and a banner over
 *    each. The east side: display cases of heads, a tier chart, tables, and the ELEVATOR (an iron cage with a pulley).
 *  - the elevator (Elevator.java) drops to THE PIT (y -61 floor, 9 high): a fenced fighting pit with an emblem, stands
 *    with seats all round, a throne, cages, banners, bright shroomlights.
 */
final class Market {
    static final int X0 = 663, X1 = 698, Z0 = -995, Z1 = -962;            // the hall's outer walls
    static final int FL = DeepGenerator.FLOOR, G = FL + 1, WT = -37;        // floor top, first air, wall top
    static final int MID = 680;                                             // the door is x 680-681
    // the elevator car (inside size 3 x 3): x 695..697, z -980..-978; floors: the hall (top y -50) and the pit (top y -61)
    static final int CAR_X0 = 695, CAR_X1 = 697, CAR_Z0 = -980, CAR_Z1 = -978, UP_FLOOR = FL, DOWN_FLOOR = -61;
    static final int[] UP_BUTTON = {697, -48, -979}, DOWN_BUTTON = {697, -59, -979};
    // the arena's air: x 661..693, z -996..-961, y -60..-52
    static final int AX0 = 661, AX1 = 693, AZ0 = -996, AZ1 = -961, AY0 = -60, AY1 = -52;
    static final int PX0 = 667, PX1 = 687, PZ0 = -990, PZ1 = -967;          // the pit inside its fence

    static final String[] TIER_COLOR = {"white", "light_gray", "yellow", "lime", "cyan", "blue", "purple", "black"};

    static void paint(Paint p) {
        if (!p.touches(Town.PLOT_X0, Town.PLOT_Z0, Town.PLOT_X1, Town.PLOT_Z1)) return;
        Paint.Frame f = p.frame(0, 0, 0, false); // world coordinates, weathered
        arena(p, f);
        forecourt(p, f);
        hall(p, f);
        interior(p, f);
        elevator(p, f);
        turrets(f);
        roof(f);
    }

    static void set(Paint p, int x, int y, int z, String s) { p.set(x, y, z, s); } // exact (no weathering)

    // =========================================================================================== outside
    private static void forecourt(Paint p, Paint.Frame f) {
        // the alleys round the hall: dark earth, gravel, the odd barrel and cobweb
        for (int x = Town.PLOT_X0; x <= Town.PLOT_X1; x++) for (int z = Town.PLOT_Z0; z <= Town.PLOT_Z1; z++) {
            if (x >= X0 - 2 && x <= X1 + 2 && z >= Z0 - 2 && z <= Z1) continue;
            double r = Paint.u(x, FL, z, 501);
            boolean path = z > Z1 && x >= MID - 3 && x <= MID + 4;
            if (path) { set(p, x, FL, z, x == MID || x == MID + 1 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks"); continue; }
            set(p, x, FL, z, r < 0.4 ? "coarse_dirt" : r < 0.6 ? "soul_soil" : r < 0.8 ? "polished_blackstone" : "gravel");
            double q = Paint.u(x, G, z, 502);
            if (q < 0.05) set(p, x, G, z, "dead_bush"); else if (q < 0.07) set(p, x, G, z, "cobweb");
            else if (q < 0.08 && z < Z1) set(p, x, G, z, "barrel[facing=up]");
        }
        // an iron fence along the front of the plot with a gate, pillars topped with skulls
        int fz = Town.PLOT_Z1 - 1;
        for (int x = Town.PLOT_X0 + 1; x <= Town.PLOT_X1 - 1; x++) {
            if (x >= MID - 3 && x <= MID + 4) continue;
            boolean post = (x - Town.PLOT_X0) % 6 == 1 || x == MID - 4 || x == MID + 5;
            if (post) {
                for (int y = G; y <= G + 2; y++) f.set(x, y, fz, "polished_blackstone_bricks");
                set(p, x, G + 3, fz, x == MID - 4 || x == MID + 5 ? "wither_skeleton_skull[rotation=0]" : "soul_lantern[hanging=false]");
            } else {
                set(p, x, G, fz, "polished_blackstone_brick_wall");
                set(p, x, G + 1, fz, "iron_bars[east=true,west=true]");
                set(p, x, G + 2, fz, "iron_bars[east=true,west=true]");
            }
        }
        for (int x : new int[]{MID - 4, MID + 5}) for (int y = G; y <= G + 4; y++) f.set(x, y, fz, y == G + 4 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
        set(p, MID - 4, G + 5, fz, "wither_skeleton_skull[rotation=0]"); set(p, MID + 5, G + 5, fz, "wither_skeleton_skull[rotation=0]");
        // soul lanterns on posts along the path
        for (int z = Z1 + 2; z < fz; z += 3) for (int x : new int[]{MID - 3, MID + 4}) {
            set(p, x, G, z, "polished_blackstone_wall"); set(p, x, G + 1, z, "polished_blackstone_wall"); set(p, x, G + 2, z, "soul_lantern[hanging=false]");
        }
        // the hooded watchers either side of the door
        for (int x : new int[]{MID - 2, MID + 3}) {
            int z = Z1 + 3;
            f.set(x, G, z, "polished_blackstone_bricks"); f.set(x, G + 1, z, "chiseled_polished_blackstone");
            set(p, x, G + 2, z, "black_wool"); set(p, x, G + 3, z, "black_wool");
            set(p, x, G + 4, z, "wither_skeleton_skull[rotation=0]");
            set(p, x - 1, G + 3, z, "polished_blackstone_wall"); set(p, x + 1, G + 3, z, "polished_blackstone_wall"); // shoulders
        }
    }

    private static void hall(Paint p, Paint.Frame f) {
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
            boolean wall = x == X0 || x == X1 || z == Z0 || z == Z1;
            if (!wall) continue;
            for (int y = G; y <= WT; y++) {
                String m = y == G ? "polished_deepslate" : y == WT ? "deepslate_tiles" : (y - G) % 6 == 5 ? "deepslate_tiles" : "polished_blackstone_bricks";
                f.set(x, y, z, m);
            }
        }
        // buttresses along the front and the sides
        for (int x = X0 + 6; x < X1 - 3; x += 6) if (x < MID - 3 || x > MID + 4) buttress(p, f, x, Z1, 0, 1);
        for (int z = Z0 + 6; z < Z1 - 2; z += 6) { buttress(p, f, X0, z, -1, 0); buttress(p, f, X1, z, 1, 0); }
        // tall lancet windows of purple glass on the sides (not where the elevator stands)
        for (int z : new int[]{-970, -976, -982, -988}) {
            lancet(p, X0, z, true);
            if (z == -970 || z == -988) lancet(p, X1, z, true);
        }
        // the great door under a pointed arch (highest in the middle): crimson doors, a purple transom, a gilded keystone
        for (int y = G; y <= G + 7; y++) for (int x = MID - 3; x <= MID + 4; x++) {
            int dx = Math.min(x - (MID - 3), (MID + 4) - x), k = y - G; // dx: 0 at the jambs, 3 in the middle
            String m;
            if (dx == 0) m = "polished_basalt[axis=y]";
            else if (k > 3 + dx) m = (dx == 3 && k == 7) ? "gilded_blackstone" : "polished_blackstone_bricks";
            else if (k == 3 + dx) m = "polished_blackstone_brick_stairs[facing=" + (x <= MID ? "east" : "west") + ",half=top,shape=straight]";
            else if (dx == 3 && k <= 1) m = "crimson_door[facing=north,half=" + (k == 0 ? "lower" : "upper") + ",hinge=" + (x == MID ? "left" : "right") + ",open=false]";
            else if (dx == 3 && k == 2) m = "crimson_planks";
            else if (dx == 3) m = "purple_stained_glass_pane[east=true,west=true]";
            else m = dx == 1 ? "chiseled_polished_blackstone" : "crimson_planks";
            set(p, x, y, Z1, m);
            if (dx == 0) set(p, x, y, Z1 + 1, "polished_basalt[axis=y]");
        }
        set(p, MID - 3, G + 8, Z1 + 1, "soul_lantern[hanging=false]"); set(p, MID + 4, G + 8, Z1 + 1, "soul_lantern[hanging=false]");
        // a step down from the door: stairs
        for (int x = MID - 2; x <= MID + 3; x++) set(p, x, FL, Z1 + 1, "polished_blackstone_brick_stairs[facing=north,half=bottom,shape=straight]");
    }

    private static void buttress(Paint p, Paint.Frame f, int x, int z, int dx, int dz) {
        for (int y = G; y <= WT; y++) {
            f.set(x + dx, y, z + dz, y % 4 == 0 ? "polished_basalt[axis=y]" : "polished_blackstone_bricks");
            if (y <= WT - 6) f.set(x + 2 * dx, y, z + 2 * dz, "polished_blackstone_bricks");
        }
        String face = dx > 0 ? "west" : dx < 0 ? "east" : dz > 0 ? "north" : "south";
        set(p, x + 2 * dx, WT - 5, z + 2 * dz, "polished_blackstone_brick_stairs[facing=" + face + ",half=bottom,shape=straight]");
        set(p, x + dx, WT + 1, z + dz, "polished_blackstone_wall");
        set(p, x + dx, WT + 2, z + dz, "soul_lantern[hanging=false]");
    }

    private static void lancet(Paint p, int x, int z, boolean alongZ) {
        String pane = (alongZ ? "[north=true,south=true]" : "[east=true,west=true]");
        for (int y = G + 2; y <= G + 8; y++) set(p, x, y, z, (y % 2 == 0 ? "purple" : "magenta") + "_stained_glass_pane" + pane);
        set(p, x, G + 9, z, "chiseled_polished_blackstone");
    }

    // =========================================================================================== inside
    private static void interior(Paint p, Paint.Frame f) {
        int ix0 = X0 + 1, ix1 = X1 - 1, iz0 = Z0 + 1, iz1 = Z1 - 1;
        // clear and lay the floor: a checker of polished deepslate and blackstone
        for (int x = ix0; x <= ix1; x++) for (int z = iz0; z <= iz1; z++) {
            for (int y = G; y <= WT + 20; y++) set(p, x, y, z, "air");
            set(p, x, FL, z, ((x >> 1) + (z >> 1)) % 2 == 0 ? "polished_deepslate" : "polished_blackstone");
        }
        // engaged pillars along the side walls, timber trusses across, chandeliers hanging from them
        for (int z = -967; z >= -991; z -= 6) {
            for (int x : new int[]{ix0, ix1}) if (!(x == ix1 && z == -979)) for (int y = G; y <= WT; y++) set(p, x, y, z, "polished_basalt[axis=y]");
            for (int x = ix0; x <= ix1; x++) set(p, x, WT + 1, z, "dark_oak_log[axis=x]");
            for (int y = WT + 2; y <= WT + 16; y++) { set(p, MID, y, z, "dark_oak_log[axis=y]"); set(p, MID + 1, y, z, "dark_oak_log[axis=y]"); }
            for (int x : new int[]{671, 690}) {
                for (int y = WT - 4; y <= WT; y++) set(p, x, y, z, "iron_chain[axis=y]");
                set(p, x, WT - 5, z, "soul_lantern[hanging=true]");
            }
            set(p, MID, WT, z, "iron_chain[axis=y]"); set(p, MID, WT - 1, z, "iron_chain[axis=y]"); set(p, MID, WT - 2, z, "lantern[hanging=true]");
        }
        // the main aisle from the door to the desk: a red runner
        for (int z = iz1; z >= -987; z--) for (int x = MID; x <= MID + 1; x++) set(p, x, G, z, "red_carpet");
        queue(p);
        desk(p);
        tierWall(p);
        lounge(p);
        // cobwebs high in the corners
        for (int x = ix0; x <= ix1; x++) for (int z = iz0; z <= iz1; z++) {
            boolean edge = x <= ix0 + 1 || x >= ix1 - 1 || z <= iz0 + 1 || z >= iz1 - 1;
            if (edge && Paint.u(x, WT, z, 511) < 0.25) set(p, x, WT, z, "cobweb");
        }
    }

    /** Chain stanchions snaking back and forth on red carpet; you go in at the front, come out at the desk. */
    private static void queue(Paint p) {
        int qx0 = 665, qx1 = 678, qz1 = -965, qz0 = -986;
        for (int x = qx0; x <= qx1; x++) for (int z = qz0; z <= qz1; z++) set(p, x, G, z, "red_carpet");
        int[] rows = {-968, -971, -974, -977, -980, -983};
        for (int i = 0; i < rows.length; i++) {
            int z = rows[i], a = i % 2 == 0 ? qx0 + 3 : qx0, b = i % 2 == 0 ? qx1 : qx1 - 3;
            for (int x = a; x <= b; x++) {
                boolean post = (x - a) % 3 == 0 || x == b;
                set(p, x, G, z, post ? "dark_oak_fence" : "iron_chain[axis=x]");
                if (post && (x - a) % 6 == 0) set(p, x, G + 1, z, "soul_lantern[hanging=false]");
            }
        }
        // the east side of the queue: a stanchion line (in at the front)
        for (int z = -968; z >= qz0; z--) {
            boolean post = (z + 968) % 3 == 0 || z == qz0;
            set(p, qx1 + 1, G, z, post ? "dark_oak_fence" : "iron_chain[axis=z]");
        }
        // the hooded doormen inside, either side of the aisle
        for (int x : new int[]{MID - 2, MID + 3}) {
            set(p, x, G, Z1 - 2, "black_wool"); set(p, x, G + 1, Z1 - 2, "black_wool"); set(p, x, G + 2, Z1 - 2, "wither_skeleton_skull[rotation=0]");
        }
    }

    /** The desk across the hall: polished blackstone with a dark oak top, a service window with a bell. */
    private static void desk(Paint p) {
        int dz = -989;
        for (int x = 665; x <= 692; x++) { set(p, x, G, dz, "polished_blackstone_bricks"); set(p, x, G + 1, dz, "dark_oak_slab[type=top]"); }
        set(p, 693, G, dz, "dark_oak_fence_gate[facing=south,open=false]");
        for (int x = 694; x <= 697; x++) set(p, x, G, dz, "polished_blackstone_brick_wall");
        // what's on the desk
        String[] top = {"purple_candle[candles=3,lit=true]", "player_head[rotation=0]", "decorated_pot[facing=south]", "skeleton_skull[rotation=0]",
                "potted_wither_rose", "brewing_stand", "player_head[rotation=1]", "amethyst_cluster[facing=up]", "black_candle[candles=4,lit=true]"};
        int[] at = {666, 668, 670, 672, 683, 685, 687, 689, 691};
        for (int i = 0; i < at.length; i++) set(p, at[i], G + 2, dz, top[i]);
        // the service window: a bell, a red runner in front, the ledger behind
        set(p, 677, G + 2, dz, "bell[attachment=floor,facing=south]");
        set(p, 676, G + 2, dz, "candle[candles=2,lit=true]"); set(p, 678, G + 2, dz, "candle[candles=1,lit=true]");
        for (int x = 676; x <= 678; x++) { set(p, x, G, dz + 1, "red_carpet"); set(p, x, G, dz + 2, "red_carpet"); }
        set(p, 677, G, dz - 1, "lectern[facing=south]");
        set(p, 677, G, dz - 2, "dark_oak_stairs[facing=north,half=bottom,shape=straight]"); // the keeper's stool
        // a chain and lantern over the window
        for (int y = G + 4; y <= WT; y++) set(p, 677, y, dz, "iron_chain[axis=y]");
        set(p, 677, G + 3, dz, "soul_lantern[hanging=true]");
        // behind the desk: vaults, an ender chest, barrels, a cauldron
        set(p, 666, G, -993, "vault[facing=south,vault_state=inactive]"); set(p, 667, G, -993, "vault[facing=south,vault_state=inactive]");
        set(p, 668, G, -993, "ender_chest[facing=south]"); set(p, 666, G + 1, -993, "barrel[facing=up]");
        set(p, 692, G, -993, "barrel[facing=south]"); set(p, 692, G + 1, -993, "barrel[facing=south]"); set(p, 693, G, -993, "cauldron");
        set(p, 694, G, -993, "barrel[facing=up]"); set(p, 694, G + 1, -993, "decorated_pot[facing=south]");
    }

    /** Bookshelves along the back wall round the TIER WALL: 8 columns, 2 shulker boxes each. */
    private static void tierWall(Paint p) {
        int z = Z0 + 1; // -994
        for (int x = X0 + 1; x <= X1 - 1; x++) for (int y = G; y <= G + 6; y++) {
            if (x >= 672 && x <= 689) continue;
            double r = Paint.u(x, y, z, 521);
            set(p, x, y, z, r < 0.2 ? "chiseled_bookshelf[facing=south]" : "bookshelf");
        }
        for (int x = X0 + 1; x <= X1 - 1; x++) if (x < 672 || x > 689) {
            double r = Paint.u(x, G + 7, z, 522);
            if (r < 0.2) set(p, x, G + 7, z, "wither_skeleton_skull[rotation=0]");
            else if (r < 0.35) set(p, x, G + 7, z, "candle[candles=" + (1 + (int) (r * 20) % 4) + ",lit=true]");
            else if (r < 0.45) set(p, x, G + 7, z, "decorated_pot[facing=south]");
        }
        // rolling ladders against the shelves
        for (int x : new int[]{670, 691}) for (int y = G; y <= G + 3; y++) set(p, x, y, z + 1, "ladder[facing=south]");
        // the frame
        for (int y = G; y <= G + 6; y++) { set(p, 672, y, z, "dark_oak_log[axis=y]"); set(p, 689, y, z, "dark_oak_log[axis=y]"); }
        for (int x = 672; x <= 689; x++) { set(p, x, G + 6, z, "dark_oak_log[axis=x]"); set(p, x, G + 7, z, "polished_blackstone_brick_wall"); }
        for (int t = 0; t < 8; t++) {
            int x = 673 + t * 2;
            String c = TIER_COLOR[t];
            set(p, x, G, z, "polished_blackstone_bricks");
            set(p, x, G + 1, z, c + "_shulker_box[facing=south]");
            set(p, x, G + 2, z, "dark_oak_planks");
            set(p, x, G + 3, z, c + "_shulker_box[facing=south]");
            set(p, x, G + 4, z, "dark_oak_planks");
            set(p, x, G + 5, z, (c.equals("white") ? "" : c + "_") + "candle[candles=" + (1 + t / 2) + ",lit=true]");
            if (x + 1 <= 688) for (int y = G; y <= G + 5; y++) set(p, x + 1, y, z, "dark_oak_log[axis=y]");
            set(p, x, G + 9, z, c + "_wall_banner[facing=south]");
        }
        // over it all: a dragon's head on the wall between two crying obsidian blocks
        set(p, 680, G + 11, z, "dragon_wall_head[facing=south]");
        set(p, 679, G + 11, z, "crying_obsidian"); set(p, 681, G + 11, z, "crying_obsidian"); set(p, 680, G + 12, z, "crying_obsidian");
    }

    /** The east side: cases of heads, the tier chart, tables. */
    private static void lounge(Paint p) {
        String[] heads = {"zombie_head[rotation=0]", "creeper_head[rotation=0]", "piglin_head[rotation=0]", "player_head[rotation=0]", "wither_skeleton_skull[rotation=0]", "skeleton_skull[rotation=0]"};
        int k = 0;
        for (int x : new int[]{686, 691}) for (int z : new int[]{-967, -971, -975}) {
            set(p, x, G, z, "polished_blackstone_bricks");
            set(p, x, G + 1, z, heads[k++ % heads.length]);
            set(p, x, G + 2, z, "tinted_glass");
            set(p, x - 1, G + 1, z, "glass_pane[north=true,south=true]"); set(p, x + 1, G + 1, z, "glass_pane[north=true,south=true]");
            set(p, x, G + 1, z - 1, "glass_pane[east=true,west=true]"); set(p, x, G + 1, z + 1, "glass_pane[east=true,west=true]");
            set(p, x - 1, G, z, "polished_blackstone_brick_slab[type=bottom]"); set(p, x + 1, G, z, "polished_blackstone_brick_slab[type=bottom]");
            set(p, x, G, z - 1, "polished_blackstone_brick_slab[type=bottom]"); set(p, x, G, z + 1, "polished_blackstone_brick_slab[type=bottom]");
        }
        // the tier chart against the front wall: eight columns, one taller per tier, a candle on each
        for (int t = 0; t < 8; t++) {
            int x = 686 + t, z = Z1 - 1;
            for (int y = G; y < G + t + 1; y++) set(p, x, y, z, y == G + t ? TIER_COLOR[t] + "_concrete" : "polished_blackstone");
            set(p, x, G + t + 1, z, (TIER_COLOR[t].equals("white") ? "" : TIER_COLOR[t] + "_") + "candle[candles=1,lit=true]");
        }
        set(p, 693, G + 9, Z1 - 1, "soul_lantern[hanging=false]");
        // two tables with chairs
        for (int[] t : new int[][]{{686, -984}, {691, -985}}) {
            set(p, t[0], G, t[1], "dark_oak_fence"); set(p, t[0], G + 1, t[1], "black_carpet");
            set(p, t[0] - 1, G, t[1], "dark_oak_stairs[facing=west,half=bottom,shape=straight]");
            set(p, t[0] + 1, G, t[1], "dark_oak_stairs[facing=east,half=bottom,shape=straight]");
            set(p, t[0], G, t[1] - 1, "dark_oak_stairs[facing=north,half=bottom,shape=straight]");
            set(p, t[0], G + 2, t[1], "red_candle[candles=2,lit=true]");
        }
        // a rug of black and red under the cases
        for (int x = 684; x <= 693; x++) for (int z = -978; z <= -965; z++)
            if (p.get(x, G, z).isAir()) set(p, x, G, z, (x + z) % 2 == 0 ? "black_carpet" : "red_carpet");
    }

    // =========================================================================================== the elevator
    private static void elevator(Paint p, Paint.Frame f) {
        for (int level = 0; level < 2; level++) {
            int floor = level == 0 ? UP_FLOOR : DOWN_FLOOR, g = floor + 1;
            for (int x = CAR_X0 - 1; x <= CAR_X1 + 1; x++) for (int z = CAR_Z0 - 1; z <= CAR_Z1 + 1; z++) for (int y = floor; y <= g + 3; y++) {
                boolean inside = x >= CAR_X0 && x <= CAR_X1 && z >= CAR_Z0 && z <= CAR_Z1;
                String m;
                if (y == floor) m = inside ? ((x + z) % 2 == 0 ? "polished_deepslate" : "iron_block") : "polished_blackstone_bricks";
                else if (y == g + 3) m = "polished_blackstone_bricks";
                else if (inside) m = "air";
                else if (x == CAR_X0 - 1 && z == (CAR_Z0 + CAR_Z1) / 2 && y <= g + 1) m = "air";                 // the doorway
                else if (x == CAR_X0 - 1 && y == g + 1 && (z == CAR_Z0 || z == CAR_Z1)) m = "iron_bars[north=true,south=true]";
                else if (x == CAR_X0 - 1 && (z == CAR_Z0 - 1 || z == CAR_Z1 + 1)) m = "chiseled_polished_blackstone";
                else m = "polished_blackstone_bricks";
                set(p, x, y, z, m);
            }
            set(p, CAR_X0 - 1, g + 2, (CAR_Z0 + CAR_Z1) / 2, "gilded_blackstone");
            int[] b = level == 0 ? UP_BUTTON : DOWN_BUTTON;
            set(p, b[0], b[1], b[2], "polished_blackstone_button[face=wall,facing=west,powered=false]");
            set(p, CAR_X0, g + 2, CAR_Z0, "soul_lantern[hanging=true]");
        }
        // above the hall's car: an iron cage, the rope, and the pulley wheel up under the roof
        int top = UP_FLOOR + 5;
        for (int y = top; y <= WT; y++) {
            for (int z = CAR_Z0 - 1; z <= CAR_Z1 + 1; z++) {
                boolean corner = z == CAR_Z0 - 1 || z == CAR_Z1 + 1;
                set(p, CAR_X0 - 1, y, z, corner ? "polished_blackstone_wall" : "iron_bars[north=true,south=true]");
            }
            for (int x = CAR_X0; x <= CAR_X1; x++) {
                set(p, x, y, CAR_Z0 - 1, x == CAR_X0 ? "polished_blackstone_wall" : "iron_bars[east=true,west=true]");
                set(p, x, y, CAR_Z1 + 1, x == CAR_X0 ? "polished_blackstone_wall" : "iron_bars[east=true,west=true]");
            }
            set(p, CAR_X0 + 1, y, (CAR_Z0 + CAR_Z1) / 2, "iron_chain[axis=y]");
        }
        set(p, CAR_X0 + 1, WT + 1, (CAR_Z0 + CAR_Z1) / 2, "grindstone[face=floor,facing=north]");
        set(p, CAR_X0 + 1, WT + 2, (CAR_Z0 + CAR_Z1) / 2, "iron_chain[axis=y]");
    }

    // =========================================================================================== turrets + roof
    private static void turrets(Paint.Frame f) {
        int[][] corners = {{X0, Z0}, {X1, Z0}, {X0, Z1}, {X1, Z1}};
        int top = WT + 8;
        for (int[] c : corners) {
            for (int x = c[0] - 2; x <= c[0] + 2; x++) for (int z = c[1] - 2; z <= c[1] + 2; z++) {
                boolean edge = Math.abs(x - c[0]) == 2 || Math.abs(z - c[1]) == 2;
                boolean corner = Math.abs(x - c[0]) == 2 && Math.abs(z - c[1]) == 2;
                boolean inHall = x > X0 && x < X1 && z > Z0 && z < Z1;
                for (int y = G; y <= top; y++) {
                    if (!edge) { if (!inHall || y > WT) f.set(x, y, z, "polished_blackstone_bricks"); continue; }
                    boolean slit = !corner && (x == c[0] || z == c[1]) && y % 5 == 2 && y > G + 2;
                    f.set(x, y, z, corner ? "polished_basalt[axis=y]" : slit ? "purple_stained_glass" : y == top ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
                }
                if (edge && !corner && (x + z) % 2 == 0) f.set(x, top + 1, z, "polished_blackstone_wall"); // crenels
            }
            for (int k = 0; k <= 3; k++) for (int x = c[0] - 2 + k; x <= c[0] + 2 - k; x++) for (int z = c[1] - 2 + k; z <= c[1] + 2 - k; z++) {
                boolean e = x == c[0] - 2 + k || x == c[0] + 2 - k || z == c[1] - 2 + k || z == c[1] + 2 - k;
                if (k == 0 && !e) continue;
                if (!e) { f.set(x, top + 2 + k, z, "deepslate_tiles"); continue; }
                String fc = x == c[0] - 2 + k ? "east" : x == c[0] + 2 - k ? "west" : z == c[1] - 2 + k ? "south" : "north";
                f.set(x, top + 2 + k, z, "deepslate_tile_stairs[facing=" + fc + ",half=bottom,shape=straight]");
            }
            f.set(c[0], top + 6, c[1], "deepslate_tile_wall"); f.set(c[0], top + 7, c[1], "lightning_rod");
            f.set(c[0], top + 1, c[1], "soul_lantern[hanging=false]");
        }
    }

    /** A steep roof, ridge running front to back, gables at the front (with THE EYE) and the back. */
    private static void roof(Paint.Frame f) {
        for (int k = 0; ; k++) {
            int xw = X0 - 1 + k, xe = X1 + 1 - k, y = WT + 1 + k;
            if (xw > xe) break;
            for (int z = Z0 - 1; z <= Z1 + 1; z++) {
                if (xw == xe) { f.set(xw, y, z, "deepslate_tiles"); continue; }
                f.set(xw, y, z, "deepslate_tile_stairs[facing=east,half=bottom,shape=straight]");
                f.set(xe, y, z, "deepslate_tile_stairs[facing=west,half=bottom,shape=straight]");
                if (xw + 1 == xe) { f.set(xw, y + 1, z, "deepslate_tile_slab[type=bottom]"); f.set(xe, y + 1, z, "deepslate_tile_slab[type=bottom]"); }
            }
            for (int x = xw + 1; x < xe; x++) for (int z : new int[]{Z0, Z1}) f.set(x, y, z, "polished_blackstone_bricks"); // the gables
        }
        // THE EYE in the front gable: an almond eye 14 wide, a bloodshot white, a glowing purple iris, a slit pupil, lashes
        double cx = MID + 0.5, cy = WT + 7;
        for (int x = MID - 8; x <= MID + 9; x++) for (int y = WT + 1; y <= WT + 13; y++) {
            double dx = x - cx, dy = y - cy, lid = 3.8 * (1 - (dx / 7.2) * (dx / 7.2)), r = Math.hypot(dx, dy * 1.1);
            String m = null;
            if (Math.abs(dy) < lid) {
                if (Math.abs(dx) < 0.8 && r < 2.9) m = "black_stained_glass";                 // the slit pupil
                else if (r < 2.0) m = "magenta_stained_glass";
                else if (r < 2.9) m = "purple_stained_glass";
                else m = Paint.u(x, y, Z1, 541) < 0.3 ? "red_stained_glass" : "white_stained_glass";
                f.set(x, y, Z1 - 1, "air");
                f.set(x, y, Z1 - 2, r < 2.9 ? "crying_obsidian" : "shroomlight"); // a glowing panel behind it: the eye shines both ways
            } else if (Math.abs(dy) < lid + 1.2 && Math.abs(dx) < 7.6) m = "chiseled_polished_blackstone";   // the lids
            if (m != null) f.set(x, y, Z1, m);
        }
        for (int k = -2; k <= 2; k++) { // lashes over the upper lid
            int x = MID + (k <= 0 ? k * 3 : k * 3 + 1), y = (int) Math.round(cy + 3.8 * (1 - Math.pow((x - cx) / 7.2, 2)) + 1.5);
            f.set(x, y, Z1, "deepslate_tiles"); f.set(x + (k < 0 ? -1 : k > 0 ? 1 : 0), y + 1, Z1, "deepslate_tiles");
        }
    }

    // =========================================================================================== the pit
    private static void arena(Paint p, Paint.Frame f) {
        // dig it out: walls of blackstone, a tiled ceiling with shroomlights
        for (int x = AX0 - 1; x <= AX1 + 1; x++) for (int z = AZ0 - 1; z <= AZ1 + 1; z++) for (int y = AY0 - 1; y <= AY1 + 1; y++) {
            boolean wall = x == AX0 - 1 || x == AX1 + 1 || z == AZ0 - 1 || z == AZ1 + 1;
            String m;
            if (y == AY1 + 1) m = (Math.floorMod(x - AX0, 6) == 2 && Math.floorMod(z - AZ0, 6) == 2) ? "shroomlight" : "deepslate_tiles";
            else if (wall) m = y == AY0 - 1 ? "polished_blackstone_bricks" : (y - AY0) % 4 == 3 ? "deepslate_tiles" : "polished_blackstone_bricks";
            else if (y == AY0 - 1) m = "polished_blackstone";
            else m = "air";
            if (wall || y == AY1 + 1) f.set(x, y, z, m); else set(p, x, y, z, m);
        }
        // the pit floor: gritty red sand round an emblem
        int cx = (PX0 + PX1) / 2, cz = (PZ0 + PZ1) / 2;
        for (int x = PX0; x <= PX1; x++) for (int z = PZ0; z <= PZ1; z++) {
            double r = Math.hypot(x - cx, (z - cz - 0.5)), q = Paint.u(x, AY0 - 1, z, 531);
            String m = r < 1.6 ? "chiseled_polished_blackstone" : r < 4.6 ? ((int) (Math.atan2(z - cz, x - cx) * 4 / Math.PI + 8) % 2 == 0 ? "red_nether_bricks" : "crimson_planks")
                    : r < 5.6 ? "polished_blackstone" : q < 0.5 ? "smooth_red_sandstone" : q < 0.8 ? "red_sandstone" : "coarse_dirt";
            set(p, x, AY0 - 1, z, m);
        }
        // the fence round the pit: a low wall, iron bars on top, a gate in the middle of each side
        for (int x = PX0 - 1; x <= PX1 + 1; x++) for (int z = PZ0 - 1; z <= PZ1 + 1; z++) {
            boolean ex = x == PX0 - 1 || x == PX1 + 1, ez = z == PZ0 - 1 || z == PZ1 + 1;
            if (!ex && !ez) continue;
            boolean gate = (ez && x == cx) || (ex && z == -979);
            if (gate) {
                set(p, x, AY0, z, "dark_oak_fence_gate[facing=" + (ez ? "south" : "east") + ",open=false]");
                continue;
            }
            set(p, x, AY0, z, "polished_blackstone_brick_wall");
            set(p, x, AY0 + 1, z, ex && ez ? "polished_blackstone_wall" : ez ? "iron_bars[east=true,west=true]" : "iron_bars[north=true,south=true]");
            if (ex && ez) set(p, x, AY0 + 2, z, "soul_lantern[hanging=false]");
        }
        // seats all round, facing the pit (two rows), with aisles at the gates and the lift
        for (int x = AX0; x <= AX1; x++) for (int z = AZ0; z <= AZ1; z++) {
            if (x >= PX0 - 1 && x <= PX1 + 1 && z >= PZ0 - 1 && z <= PZ1 + 1) continue;
            int dw = x < PX0 ? PX0 - 1 - x : x > PX1 ? x - PX1 - 1 : 99, dn = z < PZ0 ? PZ0 - 1 - z : z > PZ1 ? z - PZ1 - 1 : 99;
            int d = Math.min(dw, dn);
            boolean aisle = Math.abs(x - cx) <= 1 && dn < 99 && dw == 99 || Math.abs(z + 979) <= 1 && dw < 99 && dn == 99;
            if (aisle || dw < 99 && dn < 99) continue;
            String face = dw < 99 ? (x < PX0 ? "west" : "east") : (z < PZ0 ? "north" : "south");
            if (d == 2) set(p, x, AY0, z, "polished_blackstone_brick_stairs[facing=" + face + ",half=bottom,shape=straight]");
            else if (d == 4) { set(p, x, AY0, z, "polished_blackstone_bricks"); set(p, x, AY0 + 1, z, "polished_blackstone_brick_stairs[facing=" + face + ",half=bottom,shape=straight]"); }
            else if (d == 5) { set(p, x, AY0, z, "polished_blackstone_bricks"); }
        }
        // pillars and banners along the walls, chains and lanterns from the ceiling
        for (int x = AX0 + 3; x <= AX1 - 3; x += 6) for (int z : new int[]{AZ0, AZ1}) {
            for (int y = AY0; y <= AY1; y++) set(p, x, y, z, "polished_basalt[axis=y]");
            set(p, x + 2, AY1 - 1, z, "red_wall_banner[facing=" + (z == AZ0 ? "south" : "north") + "]");
        }
        for (int z = AZ0 + 3; z <= AZ1 - 3; z += 6) for (int x : new int[]{AX0, AX1}) {
            if (x == AX1 && Math.abs(z + 979) <= 2) continue;
            for (int y = AY0; y <= AY1; y++) set(p, x, y, z, "polished_basalt[axis=y]");
            if (Math.abs(z + 2 + 979) > 2 || x == AX0) set(p, x, AY1 - 1, z + 2, "black_wall_banner[facing=" + (x == AX0 ? "east" : "west") + "]");
        }
        for (int x = PX0 + 2; x <= PX1 - 2; x += 6) for (int z = PZ0 + 3; z <= PZ1 - 3; z += 6) {
            set(p, x, AY1, z, "iron_chain[axis=y]"); set(p, x, AY1 - 1, z, "lantern[hanging=true]");
        }
        // the throne at the north stands
        int tx = cx, tz = AZ0 + 1;
        for (int x = tx - 2; x <= tx + 2; x++) for (int z = tz; z <= tz + 2; z++) set(p, x, AY0, z, "polished_blackstone");
        set(p, tx, AY0 + 1, tz + 1, "crimson_stairs[facing=north,half=bottom,shape=straight]");
        set(p, tx, AY0 + 1, tz, "crimson_planks"); set(p, tx, AY0 + 2, tz, "crimson_planks"); set(p, tx, AY0 + 3, tz, "gilded_blackstone");
        set(p, tx - 1, AY0 + 1, tz + 1, "polished_blackstone_wall"); set(p, tx + 1, AY0 + 1, tz + 1, "polished_blackstone_wall");
        set(p, tx - 1, AY0 + 2, tz + 1, "soul_lantern[hanging=false]"); set(p, tx + 1, AY0 + 2, tz + 1, "soul_lantern[hanging=false]");
        set(p, tx, AY0 + 4, tz, "wither_skeleton_skull[rotation=0]");
        for (int x = tx - 2; x <= tx + 2; x++) set(p, x, AY0, tz + 3, "polished_blackstone_brick_stairs[facing=north,half=bottom,shape=straight]");
        // cages in the far corners, each with a skull and a chain
        for (int[] c : new int[][]{{AX0 + 1, AZ0 + 1}, {AX0 + 1, AZ1 - 3}}) {
            for (int x = c[0]; x <= c[0] + 2; x++) for (int z = c[1]; z <= c[1] + 2; z++) for (int y = AY0; y <= AY0 + 2; y++) {
                boolean edge = x == c[0] || x == c[0] + 2 || z == c[1] || z == c[1] + 2;
                set(p, x, y, z, edge ? "iron_bars" : "air");
            }
            set(p, c[0] + 1, AY0, c[1] + 1, "skeleton_skull[rotation=3]");
            for (int y = AY0 + 3; y <= AY1; y++) set(p, c[0] + 1, y, c[1] + 1, "iron_chain[axis=y]");
        }
        // podiums with soul fire in the near corners
        for (int[] c : new int[][]{{AX1 - 2, AZ0 + 2}, {AX1 - 2, AZ1 - 2}}) {
            set(p, c[0], AY0, c[1], "gilded_blackstone"); set(p, c[0], AY0 + 1, c[1], "soul_lantern[hanging=false]");
        }
    }
}
