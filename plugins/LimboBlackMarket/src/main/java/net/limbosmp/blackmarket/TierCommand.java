package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class TierCommand implements CommandExecutor {

    private final LimboBlackMarket plugin;

    public TierCommand(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("limbomarket.tier")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /tier <set|get> <player> [tier]");
            return true;
        }

        String sub = args[0].toLowerCase();

        // Bukkit.getOfflinePlayer(String) can silently make a blocking web
        // request to Mojang on the main thread the FIRST time an unfamiliar
        // name is looked up, freezing the server for everyone. We avoid that
        // by only resolving players who are online or already known locally
        // (i.e. have joined before) — which covers every real use case here,
        // since you can't have dropped a head on this server without joining it.
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
        }
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No record of a player named '" + args[1]
                    + "' — check the spelling, or they haven't joined the server yet.");
            return true;
        }

        switch (sub) {
            case "get" -> {
                String tier = plugin.getTierManager().getTier(target.getUniqueId());
                sender.sendMessage(ChatColor.GRAY + (target.getName() != null ? target.getName() : args[1])
                        + "'s tier: " + plugin.getMarketConfig().getDisplayName(tier));
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /tier set <player> <tier>");
                    return true;
                }
                String tierName = args[2].toUpperCase();
                boolean ok = plugin.getTierManager().setTier(target.getUniqueId(), tierName);
                if (!ok) {
                    sender.sendMessage(ChatColor.RED + "Unknown tier '" + tierName + "'. Valid tiers: "
                            + String.join(", ", plugin.getMarketConfig().getTierNames()));
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "Set " + (target.getName() != null ? target.getName() : args[1])
                        + "'s tier to " + plugin.getMarketConfig().getDisplayName(tierName));
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /tier <set|get> <player> [tier]");
        }
        return true;
    }
}
