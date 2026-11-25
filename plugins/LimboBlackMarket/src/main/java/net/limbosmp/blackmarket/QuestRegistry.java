package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every quest on the server, defined in one place. A quest is just an
 * ordered list of steps plus a reward — the engine in QuestService handles
 * progress, accepting, cooldowns, etc. generically for all of them.
 *
 * To add a quest later: add one more q(...) call in the constructor, then
 * the menu pages itself (28 a page).
 */
public class QuestRegistry {

    public enum Objective { KILL_MOB, KILL_PLAYER, MINE, WIN_RAID }

    public enum Difficulty {
        EASY(ChatColor.GREEN, "Easy"),
        MEDIUM(ChatColor.YELLOW, "Medium"),
        HARD(ChatColor.RED, "Hard"),
        EXTREME(ChatColor.DARK_PURPLE, "Extreme");

        public final ChatColor color;
        public final String label;

        Difficulty(ChatColor color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    /**
     * One step of a quest. Only the fields relevant to its objective are used:
     * mobs for KILL_MOB, minTier/uniqueVictims for KILL_PLAYER, block for MINE.
     */
    /**
     * requiredTag: for custom mobs (FaultlineItems' Vampire is a tagged Wither
     * Skeleton or Bat). null = a plain vanilla mob, and tagged custom mobs DON'T
     * count for it (a Vampire isn't "a Wither Skeleton" for quest purposes).
     */
    public record Step(Objective objective, int count, Set<EntityType> mobs, int minTier,
                       boolean uniqueVictims, Material block, String requiredTag, String description) {

        static Step killMobs(int count, String description, EntityType first, EntityType... rest) {
            return new Step(Objective.KILL_MOB, count, EnumSet.of(first, rest), 0, false, null, null, description);
        }

        static Step killCustomMob(int count, String description, String tag, EntityType first, EntityType... rest) {
            return new Step(Objective.KILL_MOB, count, EnumSet.of(first, rest), 0, false, null, tag, description);
        }

        static Step killPlayers(int count, int minTier, boolean uniqueVictims, String description) {
            return new Step(Objective.KILL_PLAYER, count, Set.of(), minTier, uniqueVictims, null, null, description);
        }

        static Step mine(int count, Material block, String description) {
            return new Step(Objective.MINE, count, Set.of(), 0, false, block, null, description);
        }

        static Step winRaids(int count, String description) {
            return new Step(Objective.WIN_RAID, count, Set.of(), 0, false, null, null, description);
        }
    }

    /** Scoreboard tags FaultlineItems puts on its custom mobs. */
    public static final String VAMPIRE_TAG = "faultline_vampire";
    public static final String FROST_WRAITH_TAG = "faultline_frost_wraith";
    /** Custom mobs never count toward plain vanilla-mob steps (a Frost Wraith isn't "a Skeleton"). */
    public static final Set<String> CUSTOM_MOB_TAGS = Set.of(VAMPIRE_TAG, FROST_WRAITH_TAG);
    /** 2.0 tags from the other plugins (Raids' Elites, Items' Queen Spider + nest brood, FaultlineWorld's Zombie Giant). */
    public static final String ELITE_TAG = "faultline_elite", QUEEN_TAG = "faultline_queen_spider",
            NEST_BROOD_TAG = "faultline_nest_brood", GIANT_TAG = "faultline_zombie_giant";

    /** A mine step for an ore also counts its deepslate twin (and the other way round). */
    static boolean sameOre(Material want, Material got) {
        if (want == got) return true;
        return twin(want) == got;
    }

    static Material twin(Material m) {
        if (m == null) return null;
        String n = m.name();
        Material t = n.startsWith("DEEPSLATE_") ? Material.matchMaterial(n.substring(10)) : Material.matchMaterial("DEEPSLATE_" + n);
        return t;
    }

    public record Quest(String id, String name, Material icon, Difficulty difficulty, List<Step> steps,
                        String rewardText, Consumer<Player> reward, double defaultCooldownHours, boolean oneTime) {}

    private static final List<Material> LOGS = List.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG);

    private final LimboBlackMarket plugin;
    private final Random random = new Random();
    private final List<Quest> quests = new ArrayList<>();

    public QuestRegistry(LimboBlackMarket plugin) {
        this.plugin = plugin;

        // Order here = order shown in the menu (roughly easiest to hardest).

        q("zombie-slayer", "Zombie Slayer", Material.ROTTEN_FLESH, Difficulty.EASY,
                List.of(zombies(5)),
                "8 Logs (random wood type)",
                p -> give(p, new ItemStack(LOGS.get(random.nextInt(LOGS.size())), 8)),
                0, false);

        q("daily", "Daily Quest", Material.CLOCK, Difficulty.MEDIUM,
                List.of(zombies(15),
                        Step.killPlayers(5, 2, false, "Kill 5 Tier 2+ Players"),
                        Step.mine(3, Material.ANCIENT_DEBRIS, "Mine 3 Ancient Debris")),
                "1 Diamond Block",
                p -> give(p, new ItemStack(Material.DIAMOND_BLOCK, 1)),
                12, false);

        // Harder than before: 3 DIFFERENT Tier 3+ players (was 1), 1 hour cooldown (was 10 min).
        q("golden-apple", "Golden Apple Hunt", Material.GOLDEN_APPLE, Difficulty.MEDIUM,
                List.of(Step.killPlayers(3, 3, true, "Kill 3 different Tier 3+ Players")),
                "4 Golden Apples",
                p -> give(p, new ItemStack(Material.GOLDEN_APPLE, 4)),
                1, false);

        q("raid-breaker", "Raid Breaker", Material.CROSSBOW, Difficulty.MEDIUM,
                List.of(Step.killMobs(25, "Kill 25 Raiders (Pillager, Vindicator, Evoker, Ravager)",
                                EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER),
                        Step.winRaids(1, "Win a Raid")),
                "Totem of Undying + 32 Emeralds + 16 Bottles o' Enchanting",
                p -> {
                    give(p, new ItemStack(Material.TOTEM_OF_UNDYING, 1));
                    give(p, new ItemStack(Material.EMERALD, 32));
                    give(p, new ItemStack(Material.EXPERIENCE_BOTTLE, 16));
                },
                24, false);

        q("necromancer-trial", "Necromancer's Trial", Material.WITHER_SKELETON_SKULL, Difficulty.MEDIUM,
                List.of(Step.killMobs(25, "Kill 25 Skeletons", EntityType.SKELETON, EntityType.STRAY),
                        Step.killMobs(10, "Kill 10 Wither Skeletons", EntityType.WITHER_SKELETON),
                        Step.killCustomMob(1, "Kill a Vampire (hides among cave bats)", VAMPIRE_TAG,
                                EntityType.WITHER_SKELETON, EntityType.BAT),
                        Step.killMobs(1, "Kill the Wither", EntityType.WITHER)),
                "Unlocks the Necromancer Staff (forever)",
                this::unlockNecromancer,
                0, true);

        q("nether-conqueror", "Nether Conqueror", Material.BLAZE_ROD, Difficulty.HARD,
                List.of(Step.killMobs(20, "Kill 20 Blazes", EntityType.BLAZE),
                        Step.killMobs(10, "Kill 10 Wither Skeletons", EntityType.WITHER_SKELETON),
                        Step.killMobs(5, "Kill 5 Ghasts", EntityType.GHAST)),
                "1 Netherite Upgrade Template",
                p -> give(p, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1)),
                24, false);

        q("end-walker", "End Walker", Material.SHULKER_SHELL, Difficulty.HARD,
                List.of(Step.killMobs(50, "Kill 50 Endermen", EntityType.ENDERMAN),
                        Step.killMobs(15, "Kill 15 Shulkers", EntityType.SHULKER)),
                "1 Mythic Goodie Bag",
                p -> giveMythicBags(p, 1),
                24, false);

        q("bounty-hunter", "Bounty Hunter", Material.NETHERITE_SWORD, Difficulty.HARD,
                List.of(Step.killPlayers(5, 4, false, "Kill 5 Tier 4+ Players")),
                "1 Netherite Upgrade Template",
                p -> give(p, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1)),
                72, false);

        q("warden-slayer", "Warden Slayer", Material.ECHO_SHARD, Difficulty.EXTREME,
                List.of(Step.killMobs(1, "Kill a Warden", EntityType.WARDEN)),
                "3 Mythic Goodie Bags",
                p -> giveMythicBags(p, 3),
                72, false);

        q("elytra", "Elytra Quest", Material.ELYTRA, Difficulty.EXTREME,
                List.of(Step.killMobs(5, "Kill 5 Withers", EntityType.WITHER),
                        Step.killMobs(2, "Kill 2 Wardens", EntityType.WARDEN),
                        Step.killMobs(3, "Kill 3 Elder Guardians", EntityType.ELDER_GUARDIAN),
                        Step.killMobs(5, "Kill 5 Ravagers", EntityType.RAVAGER),
                        Step.killPlayers(3, 4, false, "Kill 3 Tier 4+ Players"),
                        Step.mine(32, Material.ANCIENT_DEBRIS, "Mine 32 Ancient Debris")),
                "1 Elytra",
                p -> give(p, new ItemStack(Material.ELYTRA, 1)),
                0, true);

        // The hardest quest on the server: a ranger's whole life of hunting, for the Ranger's Oath Pendant
        // (otherwise a 3% Evoker drop). One-time, no cooldown — once it's yours, it's yours.
        q("rangers-oath", "The Ranger's Oath", Material.ECHO_SHARD, Difficulty.EXTREME,
                List.of(Step.killMobs(1000, "Kill 1000 Skeletons", EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED),
                        Step.killMobs(100, "Kill 100 Evokers", EntityType.EVOKER),
                        Step.killMobs(75, "Kill 75 Breezes", EntityType.BREEZE),
                        Step.killMobs(75, "Kill 75 Phantoms", EntityType.PHANTOM),
                        Step.killMobs(5, "Kill 5 Wardens", EntityType.WARDEN),
                        Step.killMobs(3, "Kill 3 Withers", EntityType.WITHER),
                        Step.killPlayers(15, 4, true, "Kill 15 different Tier 4+ Players")),
                "Unlocks the Ranger's Oath Pendant",
                this::giveRangersOath,
                0, true);

        // ---------- 2.0: the new world ----------
        q("first-steps", "First Steps", Material.WOODEN_PICKAXE, Difficulty.EASY,
                List.of(Step.mine(32, Material.COAL_ORE, "Mine 32 Coal Ore"),
                        Step.mine(16, Material.IRON_ORE, "Mine 16 Iron Ore")),
                "16 Bread + 8 Iron Ingots",
                p -> { give(p, new ItemStack(Material.BREAD, 16)); give(p, new ItemStack(Material.IRON_INGOT, 8)); },
                6, false);

        q("night-watch", "Night Watch", Material.LANTERN, Difficulty.EASY,
                List.of(zombies(20),
                        Step.killMobs(15, "Kill 15 Skeletons", EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED),
                        Step.killMobs(10, "Kill 10 Creepers", EntityType.CREEPER)),
                "16 Arrows + 12 Golden Carrots",
                p -> { give(p, new ItemStack(Material.ARROW, 16)); give(p, new ItemStack(Material.GOLDEN_CARROT, 12)); },
                8, false);

        q("web-cutter", "Web Cutter", Material.COBWEB, Difficulty.EASY,
                List.of(Step.killMobs(30, "Kill 30 Spiders or Cave Spiders", EntityType.SPIDER, EntityType.CAVE_SPIDER)),
                "32 String + 4 Golden Apples",
                p -> { give(p, new ItemStack(Material.STRING, 32)); give(p, new ItemStack(Material.GOLDEN_APPLE, 4)); },
                12, false);

        q("prospector", "Prospector", Material.IRON_PICKAXE, Difficulty.MEDIUM,
                List.of(Step.mine(12, Material.DIAMOND_ORE, "Mine 12 Diamond Ore (y 0-35, or round the Abyss)"),
                        Step.mine(24, Material.GOLD_ORE, "Mine 24 Gold Ore"),
                        Step.mine(16, Material.LAPIS_ORE, "Mine 16 Lapis Ore")),
                "1 Diamond Block + 16 Bottles o' Enchanting",
                p -> { give(p, new ItemStack(Material.DIAMOND_BLOCK, 1)); give(p, new ItemStack(Material.EXPERIENCE_BOTTLE, 16)); },
                12, false);

        q("emerald-run", "Emerald Run", Material.EMERALD_ORE, Difficulty.MEDIUM,
                List.of(Step.mine(10, Material.EMERALD_ORE, "Mine 10 Emerald Ore (the big mountains)")),
                "32 Emeralds",
                p -> give(p, new ItemStack(Material.EMERALD, 32)),
                12, false);

        q("illager-purge", "Illager Purge", Material.IRON_AXE, Difficulty.MEDIUM,
                List.of(Step.killMobs(40, "Kill 40 Pillagers or Vindicators (the Deep Forest's city)", EntityType.PILLAGER, EntityType.VINDICATOR),
                        Step.killMobs(5, "Kill 5 Evokers", EntityType.EVOKER)),
                "2 Totems of Undying",
                p -> give(p, new ItemStack(Material.TOTEM_OF_UNDYING, 2)),
                24, false);

        q("ash-walker", "Ash Walker", Material.CHARCOAL, Difficulty.MEDIUM,
                List.of(Step.killMobs(60, "Kill 60 monsters (the Inferno Forest never sleeps)",
                        EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
                        EntityType.SPIDER, EntityType.WITCH, EntityType.ENDERMAN)),
                "Fire Resistance x3 + 16 Golden Carrots",
                p -> {
                    for (int i = 0; i < 3; i++) give(p, potion(org.bukkit.potion.PotionType.LONG_FIRE_RESISTANCE));
                    give(p, new ItemStack(Material.GOLDEN_CARROT, 16));
                },
                12, false);

        q("mire-diver", "Mire Diver", Material.MUD, Difficulty.MEDIUM,
                List.of(Step.killMobs(40, "Kill 40 Zombies, Drowned or Slimes", EntityType.ZOMBIE, EntityType.DROWNED,
                                EntityType.ZOMBIE_VILLAGER, EntityType.SLIME),
                        Step.killMobs(10, "Kill 10 Witches", EntityType.WITCH)),
                "16 Glow Berries + 2 Diamonds + Night Vision x2",
                p -> {
                    give(p, new ItemStack(Material.GLOW_BERRIES, 16)); give(p, new ItemStack(Material.DIAMOND, 2));
                    for (int i = 0; i < 2; i++) give(p, potion(org.bukkit.potion.PotionType.LONG_NIGHT_VISION));
                },
                12, false);

        q("tomb-raider", "Tomb Raider", Material.CHISELED_SANDSTONE, Difficulty.HARD,
                List.of(Step.killMobs(60, "Kill 60 Husks (the pyramids' halls)", EntityType.HUSK),
                        Step.killMobs(20, "Kill 20 Skeletons or Bogged", EntityType.SKELETON, EntityType.BOGGED, EntityType.STRAY),
                        Step.killMobs(6, "Kill 6 Breezes (the trial rooms below)", EntityType.BREEZE)),
                "1 Heavy Core + 1 Mythic Goodie Bag",
                p -> { give(p, new ItemStack(Material.HEAVY_CORE, 1)); giveMythicBags(p, 1); },
                48, false);

        q("deep-forest-lord", "Lord of the Deep Forest", Material.TOTEM_OF_UNDYING, Difficulty.HARD,
                List.of(Step.killMobs(80, "Kill 80 Illagers", EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER),
                        Step.killMobs(4, "Kill 4 Ravagers", EntityType.RAVAGER),
                        Step.winRaids(2, "Win 2 Raids")),
                "1 Netherite Ingot + 2 Totems of Undying",
                p -> { give(p, new ItemStack(Material.NETHERITE_INGOT, 1)); give(p, new ItemStack(Material.TOTEM_OF_UNDYING, 2)); },
                72, false);

        q("abyss-gazer", "Abyss Gazer", Material.SCULK, Difficulty.HARD,
                List.of(Step.mine(20, Material.DIAMOND_ORE, "Mine 20 Diamond Ore"),
                        Step.killMobs(30, "Kill 30 Drowned or Guardians", EntityType.DROWNED, EntityType.GUARDIAN),
                        Step.killMobs(1, "Kill an Elder Guardian", EntityType.ELDER_GUARDIAN)),
                "2 Diamond Blocks + a Heart of the Sea",
                p -> { give(p, new ItemStack(Material.DIAMOND_BLOCK, 2)); give(p, new ItemStack(Material.HEART_OF_THE_SEA, 1)); },
                72, false);

        q("elite-hunter", "Elite Hunter", Material.GOLDEN_SWORD, Difficulty.HARD,
                List.of(Step.killCustomMob(15, "Kill 15 Elite mobs (the Farlands are full of them)", ELITE_TAG,
                        EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER, EntityType.SKELETON,
                        EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON, EntityType.CREEPER, EntityType.SPIDER,
                        EntityType.CAVE_SPIDER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                        EntityType.BLAZE, EntityType.PIGLIN_BRUTE, EntityType.SILVERFISH)),
                "2 Mythic Goodie Bags",
                p -> giveMythicBags(p, 2),
                48, false);

        q("farlands-legend", "Farlands Legend", Material.NETHERITE_HELMET, Difficulty.EXTREME,
                List.of(Step.killCustomMob(100, "Kill 100 Elite mobs", ELITE_TAG,
                        EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER, EntityType.SKELETON,
                        EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON, EntityType.CREEPER, EntityType.SPIDER,
                        EntityType.CAVE_SPIDER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                        EntityType.BLAZE, EntityType.PIGLIN_BRUTE, EntityType.SILVERFISH),
                        Step.killPlayers(5, 3, true, "Kill 5 different Tier 3+ Players")),
                "1 Netherite Upgrade Template + 2 Netherite Ingots + 3 Mythic Goodie Bags",
                p -> {
                    give(p, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1));
                    give(p, new ItemStack(Material.NETHERITE_INGOT, 2)); giveMythicBags(p, 3);
                },
                0, true);

        q("queen-slayer", "Queen Slayer", Material.SPIDER_EYE, Difficulty.EXTREME,
                List.of(Step.killCustomMob(20, "Kill 20 of the Queen's brood", NEST_BROOD_TAG, EntityType.SPIDER, EntityType.CAVE_SPIDER),
                        Step.killCustomMob(1, "Kill a Queen Spider (the Fibers, or a nest)", QUEEN_TAG, EntityType.SPIDER, EntityType.CAVE_SPIDER)),
                "3 Mythic Goodie Bags + 1 Netherite Ingot",
                p -> { giveMythicBags(p, 3); give(p, new ItemStack(Material.NETHERITE_INGOT, 1)); },
                96, false);

        q("giant-killer", "Giant Killer", Material.ZOMBIE_HEAD, Difficulty.EXTREME,
                List.of(Step.killCustomMob(1, "Kill a Zombie Giant (deep in the Fibers)", GIANT_TAG, EntityType.GIANT)),
                "4 Mythic Goodie Bags + 1 Enchanted Golden Apple",
                p -> { giveMythicBags(p, 4); give(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1)); },
                0, true);

        q("warlord", "Warlord", Material.NETHERITE_AXE, Difficulty.EXTREME,
                List.of(Step.killPlayers(25, 3, true, "Kill 25 different Tier 3+ Players")),
                "2 Netherite Upgrade Templates + 4 Mythic Goodie Bags",
                p -> { give(p, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2)); giveMythicBags(p, 4); },
                0, true);
    }

    private Step zombies(int count) {
        return Step.killMobs(count, "Kill " + count + " Zombies",
                EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED);
    }

    private void q(String id, String name, Material icon, Difficulty difficulty, List<Step> steps,
                   String rewardText, Consumer<Player> reward, double cooldownHours, boolean oneTime) {
        quests.add(new Quest(id, name, icon, difficulty, steps, rewardText, reward, cooldownHours, oneTime));
    }

    public List<Quest> all() {
        return quests;
    }

    public Quest get(String id) {
        if (id == null) return null;
        for (Quest quest : quests) {
            if (quest.id().equals(id)) return quest;
        }
        return null;
    }

    /** Cooldown after completing, overridable per quest in config.yml (quests.<id>.cooldown-hours). */
    public long cooldownMillis(Quest quest) {
        double hours = plugin.getConfig().getDouble("quests." + quest.id() + ".cooldown-hours", quest.defaultCooldownHours());
        return (long) (hours * 3_600_000L);
    }

    /** Every block type some quest asks you to mine — these get placed-block protection. */
    public Set<Material> trackedMineBlocks() {
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (Quest quest : quests) {
            for (Step step : quest.steps()) {
                if (step.objective() == Objective.MINE) {
                    blocks.add(step.block());
                    Material t = twin(step.block());
                    if (t != null) blocks.add(t);
                }
            }
        }
        return blocks;
    }

    // ---------- rewards ----------

    private static ItemStack potion(org.bukkit.potion.PotionType type) {
        ItemStack it = new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta m = (org.bukkit.inventory.meta.PotionMeta) it.getItemMeta();
        m.setBasePotionType(type);
        it.setItemMeta(m);
        return it;
    }

    private void give(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    /**
     * The staff itself lives in FaultlineItems, which keeps the unlock list.
     * If that fails (plugin missing), staff can run the command by hand.
     */
    private void unlockNecromancer(Player player) {
        Plugin items = Bukkit.getPluginManager().getPlugin("FaultlineItems");
        boolean unlocked = items != null && items.isEnabled()
                && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unlocknecromancer " + player.getName());
        if (!unlocked) {
            plugin.getLogger().warning("Couldn't unlock the Necromancer Staff for " + player.getName()
                    + " (is FaultlineItems installed?) — run /unlocknecromancer " + player.getName() + " manually.");
            player.sendMessage(ChatColor.RED + "Something went wrong unlocking your staff. Ask staff to run /unlocknecromancer "
                    + player.getName() + ".");
        }
    }

    /**
     * The Ranger's Oath Pendant lives in FaultlineItems; this asks that plugin to hand one over through its
     * /giveaccessory console command. If FaultlineItems is missing (or the command fails), staff can give it by hand.
     */
    private void giveRangersOath(Player player) {
        Plugin items = Bukkit.getPluginManager().getPlugin("FaultlineItems");
        boolean given = items != null && items.isEnabled()
                && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giveaccessory oath " + player.getName());
        if (!given) {
            plugin.getLogger().warning("Couldn't give " + player.getName()
                    + " a Ranger's Oath Pendant (is FaultlineItems installed?) — run /giveaccessory oath " + player.getName() + " manually.");
            player.sendMessage(ChatColor.RED + "Something went wrong giving your Ranger's Oath Pendant. Ask staff to run /giveaccessory oath "
                    + player.getName() + ".");
        }
    }

    /**
     * The Mythic Goodie Bag lives in FaultlineItems, so this asks that plugin
     * to hand one over via its console command. If FaultlineItems isn't
     * installed (or the command fails), the player still gets paid.
     */
    private void giveMythicBags(Player player, int count) {
        Plugin items = Bukkit.getPluginManager().getPlugin("FaultlineItems");
        for (int i = 0; i < count; i++) {
            boolean given = items != null && items.isEnabled()
                    && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givemythicbag " + player.getName());

            if (!given) {
                plugin.getLogger().warning("Couldn't give " + player.getName()
                        + " a Mythic Goodie Bag (is FaultlineItems installed?) — gave 2 Diamond Blocks instead.");
                give(player, new ItemStack(Material.DIAMOND_BLOCK, 2));
            }
        }
    }
}
