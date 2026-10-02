package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Logs it to friendly-fire.log (so staff have a record even if no leader
 * was online to see it) and pings any online Clan Leaders of the shared
 * kingdom in real time. Doesn't block the kill from counting toward
 * stats — this is visibility/moderation info, not a punishment mechanic.
 */
public class FriendlyFireLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LimboBlackMarket plugin;
    private final File logFile;

    public FriendlyFireLogger(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "friendly-fire.log");
    }

    public void logFriendlyFire(Player killer, Player victim, String kingdomName) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String line = timestamp + " | " + killer.getName() + " killed " + victim.getName()
                + " (both " + kingdomName + ")";

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(line + System.lineSeparator());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write friendly-fire.log", e);
        }

        String kingdomDisplay = kingdomName.charAt(0) + kingdomName.substring(1).toLowerCase();
        String alert = ChatColor.RED + "[Friendly Fire] " + ChatColor.RESET + killer.getName()
                + ChatColor.GRAY + " killed fellow " + kingdomDisplay + " member " + ChatColor.RESET + victim.getName();

        for (UUID leaderId : plugin.getKingdomDataReader().getLeaders(kingdomName)) {
            Player leader = Bukkit.getPlayer(leaderId);
            if (leader != null && leader.isOnline()) {
                leader.sendMessage(alert);
            }
        }
    }
}
