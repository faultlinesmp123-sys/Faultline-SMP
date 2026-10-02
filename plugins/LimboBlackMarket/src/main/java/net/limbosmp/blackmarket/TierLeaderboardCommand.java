package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TierLeaderboardCommand implements CommandExecutor {

    private final LimboBlackMarket plugin;

    public TierLeaderboardCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Map<UUID, String> allTiers = plugin.getTierManager().getAllAssignedTiers();

        Map<String, List<String>> namesByTier = new HashMap<>();
        for (Map.Entry<UUID, String> entry : allTiers.entrySet()) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey()); // UUID lookup is local-only, no network call
            String name = player.getName() != null ? player.getName() : entry.getKey().toString();
            namesByTier.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(name);
        }

        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "--- Tier Leaderboard ---");

        // Tiers are numeric strings ("1".."8"), sorted highest first. Sorting
        // numerically (not alphabetically) avoids "10" landing before "2" if
        // higher tiers get added to config.yml later.
        List<String> tierNames = new ArrayList<>(plugin.getMarketConfig().getTierNames());
        tierNames.sort((a, b) -> {
            try {
                return Integer.parseInt(b) - Integer.parseInt(a);
            } catch (NumberFormatException e) {
                return b.compareTo(a);
            }
        });

        for (String tier : tierNames) {
            List<String> names = namesByTier.getOrDefault(tier, List.of());
            String tierDisplay = plugin.getMarketConfig().getDisplayName(tier);
            sender.sendMessage(tierDisplay + ChatColor.GRAY + " - " + ChatColor.WHITE + String.join(", ", names));
        }
        return true;
    }
}
