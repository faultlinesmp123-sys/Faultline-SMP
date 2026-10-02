package net.limbosmp.blackmarket;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class LimboBlackMarket extends JavaPlugin {
    /** Adds a recipe, replacing any copy left over from before a reload ("Duplicate recipe" would stop the plugin from starting). */
    static void addRecipeSafely(org.bukkit.inventory.Recipe recipe) {
        if (recipe instanceof org.bukkit.Keyed keyed) org.bukkit.Bukkit.removeRecipe(keyed.getKey());
        try {
            org.bukkit.Bukkit.addRecipe(recipe);
        } catch (IllegalStateException e) {
            org.bukkit.Bukkit.getLogger().warning("Couldn't add a recipe: " + e.getMessage());
        }
    }


    private TierManager tierManager;
    private MarketConfig marketConfig;
    private StatsManager statsManager;
    private KillStreakConfig killStreakConfig;
    private QuestRegistry questRegistry;
    private QuestDataManager questDataManager;
    private QuestService questService;
    private KingdomDataReader kingdomDataReader;
    private AntiFarmManager antiFarmManager;
    private FriendlyFireLogger friendlyFireLogger;
    private NamespacedKey headTierKey;
    private NamespacedKey questBookKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.headTierKey = new NamespacedKey(this, "head_tier");
        this.questBookKey = new NamespacedKey(this, "quest_book");
        this.tierManager = new TierManager(this);
        this.marketConfig = new MarketConfig(this);
        this.statsManager = new StatsManager(this);
        this.killStreakConfig = new KillStreakConfig(this);
        this.questRegistry = new QuestRegistry(this);
        this.questDataManager = new QuestDataManager(this);
        this.questService = new QuestService(this, questRegistry, questDataManager);
        this.kingdomDataReader = new KingdomDataReader(this);
        this.antiFarmManager = new AntiFarmManager(this);
        this.friendlyFireLogger = new FriendlyFireLogger(this);

        getServer().getPluginManager().registerEvents(new HeadTagger(this), this);
        getServer().getPluginManager().registerEvents(new StatsListener(this), this);

        // Quests — opened with the craftable Quest Book (the /quest command is gone).
        QuestMenuListener questMenu = new QuestMenuListener(this, questService);
        getServer().getPluginManager().registerEvents(questMenu, this);
        getServer().getPluginManager().registerEvents(new QuestProgressListener(this, questService), this);
        getServer().getPluginManager().registerEvents(new QuestBook(this, questMenu), this);
        getServer().removeRecipe(questBookKey); // no-op normally; prevents a crash on /reload
        LimboBlackMarket.addRecipeSafely(QuestBook.recipe(this));
        getServer().getOnlinePlayers().forEach(p -> p.discoverRecipe(questBookKey));

        getCommand("tier").setExecutor(new TierCommand(this));
        getCommand("givequestbook").setExecutor(new GiveQuestBookCommand(this));
        getCommand("markethelp").setExecutor(new MarketHelpCommand());
        getCommand("stats").setExecutor(new StatsCommand(this));
        getCommand("leaderboard").setExecutor(new LeaderboardCommand(this));
        getCommand("tierleaderboard").setExecutor(new TierLeaderboardCommand(this));
        getCommand("faultlinereload").setExecutor(new FaultlineReloadCommand(this));

        // Stats/quest progress update in memory on every kill/death to avoid
        // blocking disk I/O during PvP — this periodically flushes those
        // changes to disk instead, plus a final save in onDisable.
        getServer().getScheduler().runTaskTimerAsynchronously(this, statsManager::save, 20L * 60L * 5L, 20L * 60L * 5L);
        // Quest progress: main thread only (accept/complete/abandon also save instantly).
        getServer().getScheduler().runTaskTimer(this, questDataManager::save, 20L * 60L, 20L * 60L);

        getLogger().info("Faultline Black Market enabled — " + marketConfig.getTierNames().size() + " tiers loaded.");
    }

    @Override
    public void onDisable() {
        if (tierManager != null) {
            tierManager.save();
        }
        if (statsManager != null) {
            statsManager.save();
        }
        if (questDataManager != null) {
            questDataManager.save();
        }
    }

    public TierManager getTierManager() {
        return tierManager;
    }

    public MarketConfig getMarketConfig() {
        return marketConfig;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public KillStreakConfig getKillStreakConfig() {
        return killStreakConfig;
    }

    public QuestService getQuestService() {
        return questService;
    }

    public NamespacedKey getQuestBookKey() {
        return questBookKey;
    }

    public KingdomDataReader getKingdomDataReader() {
        return kingdomDataReader;
    }

    public AntiFarmManager getAntiFarmManager() {
        return antiFarmManager;
    }

    public FriendlyFireLogger getFriendlyFireLogger() {
        return friendlyFireLogger;
    }

    public NamespacedKey getHeadTierKey() {
        return headTierKey;
    }
}
