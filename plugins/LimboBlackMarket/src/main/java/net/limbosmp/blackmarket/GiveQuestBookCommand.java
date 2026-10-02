package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /givequestbook [amount] [player] — amount and player in either order.
 * Also used by FaultlineItems' /itemsmenu to hand out real Quest Books.
 */
public class GiveQuestBookCommand implements CommandExecutor {

    private final LimboBlackMarket plugin;

    public GiveQuestBookCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("limbomarket.givequestbook")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        Player target = sender instanceof Player p ? p : null;
        int amount = 1;
        for (String arg : args) {
            Player online = Bukkit.getPlayerExact(arg);
            if (online != null) {
                target = online;
            } else {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(arg)));
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "'" + arg + "' isn't an online player or a number.");
                    return false;
                }
            }
        }
        if (target == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /givequestbook [amount] [player]");
            return false;
        }
        ItemStack book = QuestBook.create(plugin);
        book.setAmount(amount);
        Player receiver = target;
        receiver.getInventory().addItem(book).values()
                .forEach(left -> receiver.getWorld().dropItemNaturally(receiver.getLocation(), left));
        sender.sendMessage(ChatColor.GREEN + "Gave " + receiver.getName() + " " + amount + "x Quest Book.");
        return true;
    }
}
