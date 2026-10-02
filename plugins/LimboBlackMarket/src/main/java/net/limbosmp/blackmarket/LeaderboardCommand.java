package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.UUID;

public class LeaderboardCommand implements CommandExecutor {

    private static final int TOP_SIZE = 10;

    private final LimboBlackMarket plugin;

    public LeaderboardCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<UUID> top = plugin.getStatsManager().getTopKills(TOP_SIZE);

        if (top.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No kills recorded yet.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "--- Top Kills ---");
        int rank = 1;
        for (UUID id : top) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(id); // UUID lookups are local-only, no network call
            String name = player.getName() != null ? player.getName() : "Unknown";
            int kills = plugin.getStatsManager().getKills(id);
            String tier = plugin.getTierManager().getTier(id);
            String tierDisplay = plugin.getMarketConfig().getDisplayName(tier);
            sender.sendMessage(ChatColor.YELLOW + "#" + rank + " " + ChatColor.WHITE + name
                    + ChatColor.GRAY + " - " + tierDisplay + ChatColor.GRAY + " - " + ChatColor.WHITE + kills
                    + ChatColor.GRAY + " kills");
            rank++;
        }
        return true;
    }
}
