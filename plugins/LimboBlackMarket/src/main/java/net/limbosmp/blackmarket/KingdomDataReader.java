package net.limbosmp.blackmarket;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kingdom membership lives in a completely separate plugin
 * (FaultlineKingdoms), with no shared API or compile-time dependency
 * between the two — they were built independently. Rather than adding
 * real coupling, this reads FaultlineKingdoms' kingdoms.yml directly off
 * disk, the same soft-dependency pattern used on the Kingdoms side to
 * read this plugin's stats.yml for /kingdomleaderboard.
 *
 * Soft, read-only, and safe if FaultlineKingdoms isn't installed —
 * returns null rather than erroring.
 */
public class KingdomDataReader {

    private final LimboBlackMarket plugin;

    public KingdomDataReader(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Returns the player's kingdom display name (e.g. "SHINIMODORI"), or null if unavailable/teamless. */
    public String getKingdom(UUID playerId) {
        File file = resolveKingdomsFile();
        if (file == null || !file.exists()) return null;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = "players." + playerId + ".kingdom";
        return yaml.contains(path) ? yaml.getString(path) : null;
    }

    /** Returns the UUIDs of a kingdom's Clan Leaders (up to 2), or an empty list if unavailable/none appointed. */
    public List<UUID> getLeaders(String kingdomName) {
        File file = resolveKingdomsFile();
        if (file == null || !file.exists()) return List.of();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> idStrings = yaml.getStringList("leaders." + kingdomName);

        List<UUID> ids = new ArrayList<>();
        for (String idString : idStrings) {
            try {
                ids.add(UUID.fromString(idString));
            } catch (IllegalArgumentException ignored) {
                // Not a valid UUID — skip silently, not our file format to validate.
            }
        }
        return ids;
    }

    private File resolveKingdomsFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null) return null;
        return new File(pluginsFolder, "FaultlineKingdoms" + File.separator + "kingdoms.yml");
    }
}
