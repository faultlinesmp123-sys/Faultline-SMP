package net.limbosmp.blackmarket;

import org.bukkit.configuration.ConfigurationSection;

import java.util.TreeMap;

/**
 * Loads the kill-streak milestone messages from config.yml into a
 * threshold -> message-template map, parsed once at startup rather than
 * re-parsed on every single kill.
 */
public class KillStreakConfig {

    private final LimboBlackMarket plugin;
    private final TreeMap<Integer, String> thresholds = new TreeMap<>();

    public KillStreakConfig(LimboBlackMarket plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        thresholds.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("kill-streaks");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                int threshold = Integer.parseInt(key);
                thresholds.put(threshold, section.getString(key));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid kill-streak threshold key in config.yml: " + key);
            }
        }
    }

    /** Returns the message template for this exact streak count, or null if it's not a configured milestone. */
    public String getMessageFor(int streak) {
        return thresholds.get(streak);
    }
}
