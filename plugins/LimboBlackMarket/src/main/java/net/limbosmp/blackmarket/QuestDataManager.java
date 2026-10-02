package net.limbosmp.blackmarket;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Stores every player's quest state in quest_data.yml. Only ever touched
 * from the main server thread (no async saves — that was a real bug in the
 * accessory system, not repeating it here).
 *
 * Cooldowns are stored as "when did you last complete this quest" rather
 * than "when does your cooldown end", so changing a cooldown in config.yml
 * applies to everyone immediately.
 */
public class QuestDataManager {

    public static class PlayerQuestData {
        String activeQuest;
        int step;
        int progress;
        /** Victims already counted for the CURRENT step (for "different players" steps). */
        final Set<String> victims = new HashSet<>();
        final Map<String, Long> lastCompleted = new HashMap<>();
        final Set<String> completedOnce = new HashSet<>();

        void clearActive() {
            activeQuest = null;
            step = 0;
            progress = 0;
            victims.clear();
        }
    }

    private final LimboBlackMarket plugin;
    private final File file;
    private final Map<UUID, PlayerQuestData> players = new HashMap<>();
    private boolean dirty = false;

    public QuestDataManager(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "quest_data.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            PlayerQuestData data = new PlayerQuestData();
            data.activeQuest = sec.getString("active");
            data.step = sec.getInt("step");
            data.progress = sec.getInt("progress");
            data.victims.addAll(sec.getStringList("victims"));
            data.completedOnce.addAll(sec.getStringList("completed-once"));

            ConfigurationSection last = sec.getConfigurationSection("last-completed");
            if (last != null) {
                for (String questId : last.getKeys(false)) {
                    data.lastCompleted.put(questId, last.getLong(questId));
                }
            }
            players.put(id, data);
        }
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerQuestData> entry : players.entrySet()) {
            String base = "players." + entry.getKey();
            PlayerQuestData data = entry.getValue();
            yaml.set(base + ".active", data.activeQuest);
            yaml.set(base + ".step", data.step);
            yaml.set(base + ".progress", data.progress);
            yaml.set(base + ".victims", new ArrayList<>(data.victims));
            yaml.set(base + ".completed-once", new ArrayList<>(data.completedOnce));
            for (Map.Entry<String, Long> done : data.lastCompleted.entrySet()) {
                yaml.set(base + ".last-completed." + done.getKey(), done.getValue());
            }
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save quest_data.yml", e);
        }
    }

    public PlayerQuestData get(UUID playerId) {
        return players.computeIfAbsent(playerId, k -> new PlayerQuestData());
    }

    public void markDirty() {
        dirty = true;
    }

    /** Marks dirty and writes to disk right now — for accept/abandon/complete, which shouldn't be lost to a crash. */
    public void saveNow() {
        dirty = true;
        save();
    }
}
