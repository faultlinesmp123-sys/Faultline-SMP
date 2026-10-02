package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StatsCommand implements CommandExecutor {

    private final LimboBlackMarket plugin;

    public StatsCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        OfflinePlayer target;

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /stats <player>");
                return true;
            }
            target = player;
        } else {
            // Avoid Bukkit.getOfflinePlayer(String) here — it can block the
            // main thread with a Mojang lookup for unfamiliar names.
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                target = Bukkit.getOfflinePlayerIfCached(args[0]);
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "No record of a player named '" + args[0] + "'.");
                return true;
            }
        }

        int kills = plugin.getStatsManager().getKills(target.getUniqueId());
        int deaths = plugin.getStatsManager().getDeaths(target.getUniqueId());
        double kd = deaths == 0 ? kills : Math.round((kills / (double) deaths) * 100.0) / 100.0;
        String tier = plugin.getTierManager().getTier(target.getUniqueId());
        String tierDisplay = plugin.getMarketConfig().getDisplayName(tier);

        String name = target.getName() != null ? target.getName() : "Unknown";
        sender.sendMessage(ChatColor.GOLD + name + "'s stats:");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Tier: " + ChatColor.RESET + tierDisplay);

        String kingdom = plugin.getKingdomDataReader().getKingdom(target.getUniqueId());
        if (kingdom != null) {
            String formatted = kingdom.charAt(0) + kingdom.substring(1).toLowerCase();
            sender.sendMessage(ChatColor.BLUE + "Kingdom: " + ChatColor.RESET + formatted);
        }

        sender.sendMessage(ChatColor.YELLOW + "Kills: " + ChatColor.WHITE + kills);
        sender.sendMessage(ChatColor.RED + "Deaths: " + ChatColor.WHITE + deaths);
        sender.sendMessage(ChatColor.AQUA + "K/D: " + ChatColor.WHITE + kd);
        return true;
    }
}
