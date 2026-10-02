package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class MarketHelpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(ChatColor.GOLD + "--- Limbo SMP Black Market ---");
        sender.sendMessage(ChatColor.GRAY + "Bring a Black Market head to staff at the market to trade it in.");
        sender.sendMessage(ChatColor.GRAY + "Quests: craft a " + ChatColor.GOLD + "Quest Book"
                + ChatColor.GRAY + " (Book + Iron Ingot) and right-click it.");
        if (sender.hasPermission("limbomarket.tier")) {
            sender.sendMessage(ChatColor.YELLOW + "/tier get <player>" + ChatColor.GRAY + " - view a player's tier");
            sender.sendMessage(ChatColor.YELLOW + "/tier set <player> <tier>" + ChatColor.GRAY + " - assign a tier");
        }
        return true;
    }
}
