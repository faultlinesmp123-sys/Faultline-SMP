package net.limbosmp.blackmarket;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tracks kills, deaths, and current kill streak per player, backed by
 * stats.yml.
 *
 * IMPORTANT: these update in memory only — save() is NOT called on every
 * kill/death. On a busy PvP server that could mean dozens of synchronous
 * disk writes per second, which blocks the main thread and causes real
 * lag. Instead, LimboBlackMarket schedules a periodic ASYNC autosave plus
 * a save on shutdown (see onDisable). Because save() reads these maps
 * from a different thread than the one that writes to them (main thread,
 * via addKill/addDeath/etc.), all three maps must be thread-safe — hence
 * ConcurrentHashMap rather than plain HashMap.
 */
public class StatsManager {

    private final LimboBlackMarket plugin;
    private final File file;
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public StatsManager(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                kills.put(uuid, yaml.getInt(key + ".kills", 0));
                deaths.put(uuid, yaml.getInt(key + ".deaths", 0));
                streaks.put(uuid, yaml.getInt(key + ".streak", 0));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in stats.yml: " + key);
            }
        }
    }

    /** Writes to disk only if something has changed since the last save. */
    public synchronized void save() { // BUG FIX: the async autosave and the shutdown save could write the file at once
        if (!dirty) return;
        // BUG FIX: clear the flag BEFORE snapshotting. This runs off the main thread,
        // so a kill landing mid-save used to get marked "saved" without being written
        // (and a shutdown right after would skip it). Now it just re-flags as dirty.
        dirty = false;

        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> allIds = new HashSet<>();
        allIds.addAll(kills.keySet());
        allIds.addAll(deaths.keySet());
        allIds.addAll(streaks.keySet());
        for (UUID id : allIds) {
            yaml.set(id + ".kills", kills.getOrDefault(id, 0));
            yaml.set(id + ".deaths", deaths.getOrDefault(id, 0));
            yaml.set(id + ".streak", streaks.getOrDefault(id, 0));
        }
        try {
            // BUG FIX: written to a temp file and moved over, so a crash mid-write can't leave stats.yml half written
            File tmp = new File(file.getParentFile(), "stats.yml.tmp");
            yaml.save(tmp);
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty = true; // try again next time
            plugin.getLogger().log(Level.SEVERE, "Failed to save stats.yml", e);
        }
    }

    public int getKills(UUID id) {
        return kills.getOrDefault(id, 0);
    }

    public int getDeaths(UUID id) {
        return deaths.getOrDefault(id, 0);
    }

    public int getStreak(UUID id) {
        return streaks.getOrDefault(id, 0);
    }

    public void addKill(UUID id) {
        kills.merge(id, 1, Integer::sum);
        dirty = true;
    }

    public void addDeath(UUID id) {
        deaths.merge(id, 1, Integer::sum);
        dirty = true;
    }

    /** Increments this player's kill streak and returns the new value. */
    public int incrementStreak(UUID id) {
        int newStreak = streaks.merge(id, 1, Integer::sum);
        dirty = true;
        return newStreak;
    }

    /** Resets this player's kill streak to 0 (called on death). */
    public void resetStreak(UUID id) {
        if (streaks.put(id, 0) != null) {
            dirty = true;
        }
    }

    /** Returns up to `limit` player UUIDs sorted by kills, highest first. */
    public List<UUID> getTopKills(int limit) {
        return kills.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }
}
