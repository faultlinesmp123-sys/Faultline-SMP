package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads tier definitions (name + display name) from config.yml.
 * The Black Market payout itself is handed out manually by staff —
 * this class only tracks what tiers exist and how to display them.
 */
public class MarketConfig {

    private final LimboBlackMarket plugin;

    private String defaultTier;
    private final LinkedHashMap<String, String> displayNames = new LinkedHashMap<>();

    public MarketConfig(LimboBlackMarket plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.reloadConfig();
        var config = plugin.getConfig();

        defaultTier = config.getString("default-tier", "1");

        displayNames.clear();

        ConfigurationSection tiersSection = config.getConfigurationSection("tiers");
        if (tiersSection == null) {
            plugin.getLogger().warning("config.yml has no 'tiers' section — Black Market will have no tiers!");
            return;
        }

        for (String tierName : tiersSection.getKeys(false)) {
            ConfigurationSection tier = tiersSection.getConfigurationSection(tierName);
            if (tier == null) continue;
            displayNames.put(tierName, tier.getString("display-name", tierName));
        }
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    public Set<String> getTierNames() {
        return new LinkedHashSet<>(displayNames.keySet());
    }

    public String getDisplayName(String tierName) {
        return ChatColor.translateAlternateColorCodes('&', displayNames.getOrDefault(tierName, tierName));
    }
}
