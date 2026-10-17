package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TierCommand implements CommandExecutor, TabCompleter {

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
            sender.sendMessage(ChatColor.YELLOW + "Usage: /tier <set|get|remove> <player> [tier]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("remove") || sub.equals("delete") || sub.equals("unset")) {
            // works for banned players and names the server never cached: matched against the tier list itself
            UUID id = plugin.getTierManager().findAssigned(args[1]);
            if (id == null) {
                OfflinePlayer p = Bukkit.getPlayerExact(args[1]);
                if (p == null) p = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (p != null && plugin.getTierManager().hasTier(p.getUniqueId())) id = p.getUniqueId();
            }
            if (id == null || !plugin.getTierManager().removeTier(id)) {
                sender.sendMessage(ChatColor.RED + "'" + args[1] + "' isn't on the tier list. "
                        + ChatColor.GRAY + "Use the name (or UUID) exactly as /tierleaderboard shows it.");
                return true;
            }
            String name = Bukkit.getOfflinePlayer(id).getName();
            sender.sendMessage(ChatColor.GREEN + "Removed " + (name != null ? name : id.toString()) + " from the tier list.");
            return true;
        }

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
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /tier <set|get|remove> <player> [tier]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("limbomarket.tier")) return out;
        if (args.length == 1) {
            for (String s : List.of("set", "get", "remove")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2) {
            String pre = args[1].toLowerCase();
            if (args[0].equalsIgnoreCase("remove")) {
                for (UUID id : plugin.getTierManager().getAllAssignedTiers().keySet()) {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    String s = name != null ? name : id.toString();
                    if (s.toLowerCase().startsWith(pre)) out.add(s);
                }
            } else {
                for (var p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(pre)) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            for (String t : plugin.getMarketConfig().getTierNames()) if (t.toLowerCase().startsWith(args[2].toLowerCase())) out.add(t);
        }
        return out;
    }
}
