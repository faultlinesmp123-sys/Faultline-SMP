package net.limbosmp.blackmarket;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Stores each player's Black Market tier, assigned manually by staff.
 * Backed by a flat tiers.yml file: uuid -> tier name.
 */
public class TierManager {

    private final LimboBlackMarket plugin;
    private final File file;
    private final Map<UUID, String> tiers = new HashMap<>();

    public TierManager(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tiers.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                tiers.put(UUID.fromString(key), yaml.getString(key));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in tiers.yml: " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : tiers.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save tiers.yml", e);
        }
    }

    /** Returns the player's tier, or the configured default tier if unset. */
    public String getTier(UUID playerId) {
        return tiers.getOrDefault(playerId, plugin.getMarketConfig().getDefaultTier());
    }

    public String getTier(OfflinePlayer player) {
        return getTier(player.getUniqueId());
    }

    /** Returns true if this tier name is valid per config.yml. */
    public boolean setTier(UUID playerId, String tierName) {
        if (!plugin.getMarketConfig().getTierNames().contains(tierName)) {
            return false;
        }
        tiers.put(playerId, tierName);
        save();
        return true;
    }

    public boolean hasTier(UUID playerId) {
        return tiers.containsKey(playerId);
    }

    /** Read-only view of every player who has been explicitly assigned a tier. */
    public Map<UUID, String> getAllAssignedTiers() {
        return java.util.Collections.unmodifiableMap(tiers);
    }
}
