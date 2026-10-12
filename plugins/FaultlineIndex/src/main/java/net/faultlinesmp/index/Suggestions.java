package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /SUGGEST (Index 1.6.13, config suggestions:, saved in suggestions.yml). /suggest <idea> posts it to Discord as its own
 * numbered embed (discord.suggestions-webhook-url, a channel of its own so people can react to vote; without it, the
 * public channel) and tells online staff (faultlineindex.reports). suggestions.cooldown-seconds (300) between
 * suggestions, at least suggestions.min-length (10) characters. Staff: /suggestions [count] lists the latest.
 */
final class Suggestions implements CommandExecutor {

    private final FaultlineIndex index;
    private final File file;
    private final Map<UUID, Long> last = new HashMap<>();

    Suggestions(FaultlineIndex index) {
        this.index = index;
        file = new File(index.getDataFolder(), "suggestions.yml");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("suggestions")) return list(sender, args);
        String text = String.join(" ", args).trim();
        int min = index.getConfig().getInt("suggestions.min-length", 10);
        if (text.length() < min) { sender.sendMessage(ChatColor.RED + "/suggest <your idea>  (at least " + min + " characters)"); return true; }
        if (text.length() > 1000) text = text.substring(0, 1000);
        Player by = sender instanceof Player p ? p : null;
        long now = System.currentTimeMillis(), cd = (long) (index.getConfig().getDouble("suggestions.cooldown-seconds", 300) * 1000);
        if (by != null && !by.hasPermission("faultlineindex.reports")) {
            Long t = last.get(by.getUniqueId());
            if (t != null && now - t < cd) {
                long s = (cd - (now - t)) / 1000 + 1;
                sender.sendMessage(ChatColor.RED + "Wait " + (s >= 60 ? (s + 59) / 60 + " min" : s + " s") + " before suggesting again.");
                return true;
            }
        }
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        int n = y.getInt("next", 1);
        List<String> l = new ArrayList<>(y.getStringList("suggestions"));
        l.add("#" + n + " | " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(now)) + " | " + sender.getName() + " | " + text);
        while (l.size() > 500) l.remove(0);
        y.set("next", n + 1);
        y.set("suggestions", l);
        try { y.save(file); } catch (IOException e) { index.getLogger().warning("Couldn't save suggestions.yml: " + e.getMessage()); }
        if (by != null) last.put(by.getUniqueId(), now);
        boolean posted = index.discord != null && index.discord.suggest(n, sender.getName(), text);
        String line = ChatColor.GOLD + "[Suggestion #" + n + "] " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ": " + ChatColor.WHITE + text;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("faultlineindex.reports") && !p.equals(by)) p.sendMessage(line);
        index.getLogger().info("Suggestion: " + ChatColor.stripColor(line));
        sender.sendMessage(ChatColor.GREEN + "Thanks! Suggestion #" + n + (posted ? " was posted in the Discord." : " was sent to the staff."));
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (!sender.hasPermission("faultlineindex.reports")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        int n = 10;
        if (args.length > 0) try { n = Math.max(1, Math.min(50, Integer.parseInt(args[0]))); } catch (NumberFormatException ignored) { }
        List<String> l = file.exists() ? YamlConfiguration.loadConfiguration(file).getStringList("suggestions") : List.of();
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Suggestions " + ChatColor.GRAY + "(latest " + Math.min(n, l.size()) + " of " + l.size() + ")");
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) sender.sendMessage(ChatColor.GRAY + " " + l.get(i));
        return true;
    }
}
