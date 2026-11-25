package net.faultlinesmp.world;

import java.util.List;

/**
 * Where everything fixed in the Faultline overworld is. The world is a 7500 x 7500 square (border radius 3750) of
 * Great Sea with three big islands: A (north, temperate, spawn), B (south-west, hot), C (south-east, cold), the
 * Farlands round the rim and three holes down to the abyss in the open sea. Pure data: the generator, the biome
 * provider and the plugin all read it, so a position always means the same thing.
 */
final class Layout {
    private Layout() {}

    static final int SEA = 200;          // sea level (the datapack's overworld noise settings say the same)
    static final int BORDER = 3750;      // border radius (7500 x 7500)
    static final int FARLANDS = 3280;    // the Farlands start this far from 0,0
    static final int SPAWN_X = 250, SPAWN_Z = -1250, SPAWN_Y = 249; // top block; players stand on 250

    /** An island: centre, radius, climate (0 temperate, 1 hot, 2 cold). */
    record Isle(String id, double x, double z, double r, int climate) {}

    static final List<Isle> ISLES = List.of(
            new Isle("A", 0, -1450, 1150, 0),
            new Isle("B", -1300, 900, 1150, 1),
            new Isle("C", 1300, 900, 1150, 2));

    /** A blob of a custom (or forced vanilla) surface biome. */
    record Blob(B biome, double x, double z, double r) {}

    static final List<Blob> SURFACE = List.of(
            // island A (temperate)
            new Blob(B.DARK_FOREST, 450, -1750, 300),
            new Blob(B.WARTSFIELD, -250, -1150, 270),
            new Blob(B.DEEP_FOREST, 720, -1330, 290),
            new Blob(B.SPIDER, 160, -860, 220),
            new Blob(B.DEEP_DESERT, -700, -1010, 270),
            new Blob(B.JAGGED_PEAKS, -760, -1680, 360),   // the mountain faction's range
            // island B (hot)
            new Blob(B.DEEP_DESERT, -640, 1320, 330),
            new Blob(B.INFERNO_FOREST, -1760, 620, 330),
            new Blob(B.SPIDER, -900, 380, 200),
            new Blob(B.JUNGLE, -1450, 1350, 430),          // the jungle faction
            // island C (cold)
            new Blob(B.WASTELAND, 2250, 950, 660),        // runs out into the sea: the acid sea
            new Blob(B.DEEP_DESERT, 820, 1560, 270),
            new Blob(B.DEEP_FOREST, 900, 640, 250),
            new Blob(B.INFERNO_FOREST, 1950, 1850, 230),
            new Blob(B.FROZEN_PEAKS, 1480, 1450, 420),     // the ice faction
            new Blob(B.SKY_ISLES, 2100, -1000, 430));      // the sky faction: islands over the open Great Sea, north-east of island C

    static final List<Blob> UNDERGROUND = List.of(
            new Blob(B.FIBERS, 300, -700, 340),
            new Blob(B.FIBERS, -1000, 520, 270),
            new Blob(B.FIBERS, 1000, 1120, 250),
            new Blob(B.MIRE, -1050, 1050, 380),
            new Blob(B.MIRE, -420, -1520, 270),
            new Blob(B.MIRE, 1750, 560, 220));

    /** The three holes in the Great Sea down to the abyss; at the bottom of each, the way to the Dark Ocean. */
    static final int[][] HOLES = {{0, 250}, {-2550, -1150}, {2550, -1150}};
    static final int HOLE_R = 46;

    /** The three Deep Desert pyramids (one per island; never anywhere else). */
    static final int[][] PYRAMIDS = {{-700, -1010}, {-640, 1320}, {820, 1560}};

    // ------------------------------------------------------------------ faction sites
    /** How a site shapes the ground under it. */
    enum Shape { FLAT, BASIN, CANYON, SEABED, CAVERN, SKY, CRATER }

    /**
     * A schematic placed by the generator. x/z = the schematic's centre; y = where its lowest layer goes; r = the radius
     * of ground shaped round it; ruin = abandoned (castles/bases; statues stay whole).
     */
    record Site(String faction, String file, int x, int y, int z, int r, Shape shape, boolean ruin) {}

    static final List<Site> SITES = List.of(
            // MOUNTAIN (island A, west range) + the Giant Tower out of the sea off the west coast
            new Site("mountain", "mountain/fantasy_castle", -800, 221, -1650, 80, Shape.FLAT, true),
            new Site("mountain", "mountain/angel_statue", -620, 184, -1520, 85, Shape.BASIN, false),
            new Site("mountain", "mountain/giant_tower", -1700, -58, -1750, 150, Shape.SEABED, true),
            // FIBERS (island A, underground)
            new Site("fibers", "fibers/mega_underground_base", 360, 41, -690, 0, Shape.CAVERN, true),
            new Site("fibers", "fibers/fantasy_underground_hall", 220, 41, -760, 0, Shape.CAVERN, true),
            new Site("fibers", "fibers/death_statue", 290, 41, -600, 0, Shape.CAVERN, false),
            // JUNGLE (island B, south)
            new Site("jungle", "jungle/himeji_castle", -1500, 186, 1360, 190, Shape.FLAT, true),
            new Site("jungle", "jungle/ghost_statue", -1230, 188, 1180, 70, Shape.BASIN, false),
            new Site("jungle", "jungle/starry_night_town", -1310, 205, 1560, 45, Shape.FLAT, true),
            // ICE (island C, south)
            new Site("ice", "ice/cathedral", 1560, 201, 1450, 185, Shape.FLAT, true),   // on a plateau in the ice mountains; 316 tall: its spire tip is y 516 (Bedrock sees to 511)
            new Site("ice", "ice/sightless_angel_statue", 1290, 190, 1660, 80, Shape.BASIN, false),
            new Site("ice", "ice/ocean_arena", 1790, 135, 1210, 50, Shape.CAVERN, true),
            // SKY (over the open Great Sea: the statue stands in the water, the islands ring it high above)
            new Site("sky", "sky/greek_statue", 2100, 280, -1000, 0, Shape.SKY, false),     // on its own floating island (top y 300; its model starts 20 layers up)
            new Site("sky", "sky/factory_castle", 2320, 383, -1110, 0, Shape.SKY, true),
            new Site("sky", "sky/ship", 2150, 398, -790, 0, Shape.SKY, true),
            new Site("sky", "sky/yellow_ballroom", 1940, 426, -1160, 0, Shape.SKY, true));

    /** Each faction's admin-block hall (bedrock + command/structure/jigsaw blocks, lit by light blocks). */
    /** A faction road: a straight path between two of its places (x0, z0) -> (x1, z1). */
    record Road(String faction, int x0, int z0, int x1, int z1) {}

    /** 2.0 (the owner: "all the paths have to connect to the statue and everything for each faction"). */
    static final List<Road> ROADS = List.of(
            new Road("mountain", -800, -1650, -620, -1520),      // castle - angel statue
            new Road("mountain", -800, -1650, -940, -1542),      // castle - shrine (its door faces south)
            new Road("mountain", -620, -1520, -940, -1542),      // statue - shrine
            new Road("jungle", -1500, 1360, -1230, 1180),        // Himeji - ghost statue
            new Road("jungle", -1500, 1360, -1310, 1560),        // Himeji - the town
            new Road("jungle", -1500, 1360, -1720, 1268),        // Himeji - shrine
            new Road("jungle", -1230, 1180, -1310, 1560),        // statue - the town
            new Road("ice", 1385, 1450, 1300, 1438),             // the cathedral - shrine
            new Road("ice", 1300, 1438, 1290, 1660),             // shrine - sightless angel
            new Road("ice", 1385, 1450, 1742, 1210),             // the cathedral - the way down to the arena
            new Road("fibers", 453, -690, 360, -690),            // (surface) the way down - over the base
            new Road("spawn", 250, -1250, 440, -690));           // spawn - the way down to the Fibers

    /** Paths on the floor of the Fibers' great cavern (y 41): the way down, the base, the hall, the statue, the shrine. */
    static final int[][] FIBER_PATHS = {{449, -690, 360, -690}, {360, -690, 220, -760}, {360, -690, 290, -600}, {290, -600, 430, -592}, {430, -592, 449, -690}, {220, -760, 290, -600}};

    /** Distance from (x, z) to a segment, and how far along it (out[0] = 0..1). */
    static double segDist(double x, double z, double x0, double z0, double x1, double z1, double[] out) {
        double vx = x1 - x0, vz = z1 - z0, l2 = vx * vx + vz * vz;
        double s = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * vx + (z - z0) * vz) / l2));
        if (out != null) out[0] = s;
        return Math.hypot(x - (x0 + vx * s), z - (z0 + vz * s));
    }

    /** 2.0: the owner took the abyss and its three holes back out of the overworld ("just the Great Sea, with the whirlpool"):
     *  the way down to the Dark Ocean is FaultlineDeep's whirlpool. true brings the abyss layer and the holes back. */
    static final boolean ABYSS = false;

    record Core(String faction, int x, int z, int y) {}

    static final List<Core> CORES = List.of(
            new Core("mountain", -940, -1560, 0),   // y 0 = on the ground there
            new Core("fibers", 430, -610, 41),
            new Core("jungle", -1720, 1250, 0),
            new Core("ice", 1300, 1420, 0),
            new Core("sky", 2010, -830, 440));        // floats over the crater

    /** Big caverns carved for the Fibers sites and the arena (centre, floor y, ceiling y, radius). */
    record Cavern(int x, int z, int floor, int ceil, int r) {}

    static final List<Cavern> CAVERNS = List.of(
            new Cavern(290, -690, 40, 178, 175),
            new Cavern(1790, 1210, 134, 199, 52));

    /** The cathedral canyon: centre x/z, half sizes, floor y. */
    /** Round 7: the world is 640 tall (the datapack's overworld dimension type): y -64..575. */
    static final int WORLD_TOP = 528, MAX_Y = WORLD_TOP - 1;
    /** Geyser (since its Oct 2024 builds) shows Bedrock players the Overworld up to y 511: everything that matters stays under it. */
    static final int BEDROCK_TOP = 511;
    /** The cathedral moved onto a mountain: the canyon it stood in is gone (false). */
    static final boolean CANYON = false;
    static final int CANYON_X = 1560, CANYON_Z = 1450, CANYON_HX = 200, CANYON_HZ = 112, CANYON_FLOOR = 2;

    static double dist(double ax, double az, double bx, double bz) { return Math.hypot(ax - bx, az - bz); }
}
