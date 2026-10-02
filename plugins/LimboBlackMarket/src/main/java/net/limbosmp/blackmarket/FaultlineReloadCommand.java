package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * /faultlinereload (or /freload): reloads config.yml for EVERY Faultline plugin at
 * once. Works without any compile-time dependency on the others:
 * org.bukkit.plugin.Plugin itself declares reloadConfig(), so any plugin can be
 * reloaded through the PluginManager just by name. Plugins that aren't installed
 * are skipped. All of them read their settings live, so changes apply right away
 * (exception: turning End Crystal crafting back ON in FaultlineRules needs a
 * restart, since the recipe is removed at startup).
 */
public class FaultlineReloadCommand implements CommandExecutor {

    private static final String[] OTHER_PLUGIN_NAMES = {
            "FaultlineItems", "FaultlineEvents", "FaultlineRules", "FaultlineRaids", "FaultlineBosses"};

    private final LimboBlackMarket plugin;

    public FaultlineReloadCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("limbomarket.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        int reloaded = 0;
        try {
            plugin.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "  \u2714 LimboBlackMarket");
            reloaded++;
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "  \u2716 LimboBlackMarket: " + e.getMessage());
        }

        for (String name : OTHER_PLUGIN_NAMES) {
            Plugin other = plugin.getServer().getPluginManager().getPlugin(name);
            if (other == null || !other.isEnabled()) {
                sender.sendMessage(ChatColor.DARK_GRAY + "  - " + name + " (not installed, skipped)");
                continue;
            }
            try {
                other.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "  \u2714 " + name);
                reloaded++;
            } catch (Exception e) {
                // A typo in a config.yml shows up here instead of silently doing nothing.
                sender.sendMessage(ChatColor.RED + "  \u2716 " + name + ": " + e.getMessage());
            }
        }
        sender.sendMessage(ChatColor.GOLD + "Reloaded " + reloaded + " Faultline config(s).");
        return true;
    }
}
