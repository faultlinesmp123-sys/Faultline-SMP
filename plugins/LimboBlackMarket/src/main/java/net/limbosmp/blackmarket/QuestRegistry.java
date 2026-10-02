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
 * give it a slot in QuestMenuHolder.QUEST_SLOTS.
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
                if (step.objective() == Objective.MINE) blocks.add(step.block());
            }
        }
        return blocks;
    }

    // ---------- rewards ----------

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
