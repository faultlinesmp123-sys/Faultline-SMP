package net.faultlinesmp.world;

/**
 * Every biome the Faultline overworld uses. Custom ones live in the faultline datapack (tools/world_datapack.py);
 * {@link #fallback} is the vanilla biome used when the datapack isn't loaded (the world still generates, it just shows
 * vanilla colours and names).
 */
enum B {
    // ---- vanilla
    PLAINS("minecraft:plains"), SUNFLOWER_PLAINS("minecraft:sunflower_plains"), FOREST("minecraft:forest"),
    BIRCH_FOREST("minecraft:birch_forest"), FLOWER_FOREST("minecraft:flower_forest"), MEADOW("minecraft:meadow"),
    TAIGA("minecraft:taiga"), OLD_GROWTH_SPRUCE("minecraft:old_growth_spruce_taiga"), WINDSWEPT_HILLS("minecraft:windswept_hills"),
    JAGGED_PEAKS("minecraft:jagged_peaks"), STONY_PEAKS("minecraft:stony_peaks"), CHERRY_GROVE("minecraft:cherry_grove"),
    RIVER("minecraft:river"), FROZEN_RIVER("minecraft:frozen_river"), BEACH("minecraft:beach"), SNOWY_BEACH("minecraft:snowy_beach"),
    STONY_SHORE("minecraft:stony_shore"),
    DESERT("minecraft:desert"), SAVANNA("minecraft:savanna"), SAVANNA_PLATEAU("minecraft:savanna_plateau"),
    BADLANDS("minecraft:badlands"), ERODED_BADLANDS("minecraft:eroded_badlands"), WOODED_BADLANDS("minecraft:wooded_badlands"),
    JUNGLE("minecraft:jungle"), BAMBOO_JUNGLE("minecraft:bamboo_jungle"), SPARSE_JUNGLE("minecraft:sparse_jungle"),
    MANGROVE_SWAMP("minecraft:mangrove_swamp"), SWAMP("minecraft:swamp"),
    SNOWY_PLAINS("minecraft:snowy_plains"), SNOWY_TAIGA("minecraft:snowy_taiga"), ICE_SPIKES("minecraft:ice_spikes"),
    GROVE("minecraft:grove"), SNOWY_SLOPES("minecraft:snowy_slopes"), FROZEN_PEAKS("minecraft:frozen_peaks"),
    OCEAN("minecraft:ocean"), LUKEWARM_OCEAN("minecraft:lukewarm_ocean"), WARM_OCEAN("minecraft:warm_ocean"),
    COLD_OCEAN("minecraft:cold_ocean"), FROZEN_OCEAN("minecraft:frozen_ocean"),
    DRIPSTONE_CAVES("minecraft:dripstone_caves"), LUSH_CAVES("minecraft:lush_caves"), DEEP_DARK("minecraft:deep_dark"),
    // ---- Faultline (datapack)
    GREAT_SEA("faultline:great_sea", "minecraft:deep_ocean"),
    ABYSS("faultline:abyss", "minecraft:deep_cold_ocean"),
    WASTELAND("faultline:wasteland", "minecraft:swamp"),
    DEEP_DESERT("faultline:deep_desert", "minecraft:desert"),
    DEEP_FOREST("faultline:deep_forest", "minecraft:old_growth_pine_taiga"),
    INFERNO_FOREST("faultline:inferno_forest", "minecraft:savanna"),
    DARK_FOREST("faultline:dark_forest", "minecraft:dark_forest"),
    WARTSFIELD("faultline:wartsfield_valley", "minecraft:plains"),
    SPIDER("faultline:spider_woods", "minecraft:dark_forest"),
    SPIDER_CAVES("faultline:spider_caves", "minecraft:dripstone_caves"),
    FIBERS("faultline:fibers", "minecraft:lush_caves"),
    MIRE("faultline:mire", "minecraft:lush_caves"),
    FARLANDS("faultline:farlands", "minecraft:windswept_hills"),
    SKY_ISLES("faultline:sky_isles", "minecraft:meadow"),
    // ---- the Broken Lands (2.0): five flat, broken wedges of the Farlands between 5000 and the border (Broken.java)
    MISSING_CHUNKS("faultline:missing_chunks", "minecraft:plains"),
    MISSING_TEXTURE("faultline:missing_texture", "minecraft:plains"),
    SMEAR("faultline:the_smear", "minecraft:plains"),
    UPSIDE_DOWN("faultline:upside_down", "minecraft:plains"),
    DEBUG_FIELDS("faultline:debug_fields", "minecraft:plains");

    final String key;
    final String fallback;

    B(String key) { this(key, key); }
    B(String key, String fallback) { this.key = key; this.fallback = fallback; }

    /** The Farlands and its broken biomes (mobs don't burn, the extra spawns, the Elites...). */
    boolean farlands() { return this == FARLANDS || this == MISSING_CHUNKS || this == MISSING_TEXTURE || this == SMEAR || this == UPSIDE_DOWN || this == DEBUG_FIELDS; }

    boolean custom() { return key.startsWith("faultline:"); }

    /** The 10 new biomes the owner asked for (dungeons only generate in these). */
    boolean faultlineTen() {
        return switch (this) {
            case SPIDER, SPIDER_CAVES, DARK_FOREST, WARTSFIELD, GREAT_SEA, FIBERS, DEEP_DESERT, DEEP_FOREST, INFERNO_FOREST, MIRE, WASTELAND -> true;
            default -> false;
        };
    }
}
