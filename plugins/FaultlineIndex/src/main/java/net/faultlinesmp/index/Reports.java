package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /REPORT (Index 1.6.10, config reports:, saved in reports.yml). /report <player> <reason> (or just a message: a bug,
 * a grief) goes to the Discord LOGS channel right away (Discord.report, pinging discord.logs.report-role when set) with
 * both players' coordinates, to every online staff member (faultlineindex.reports), and into reports.yml.
 * reports.cooldown-seconds (60) between reports. Staff: /reports [count] lists the latest.
 */
final class Reports implements CommandExecutor {

    private final FaultlineIndex index;
    private final File file;
    private final Map<UUID, Long> last = new HashMap<>();

    Reports(FaultlineIndex index) {
        this.index = index;
        file = new File(index.getDataFolder(), "reports.yml");
    }

    static String where(Location l) { return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("reports") || command.getName().equalsIgnoreCase("reports")) return list(sender, args);
        if (args.length == 0) { sender.sendMessage(ChatColor.RED + "/report <player> <reason>  or  /report <what happened>"); return true; }
        Player by = sender instanceof Player p ? p : null;
        long now = System.currentTimeMillis(), cd = (long) (index.getConfig().getDouble("reports.cooldown-seconds", 60) * 1000);
        if (by != null && !by.hasPermission("faultlineindex.reports")) {
            Long t = last.get(by.getUniqueId());
            if (t != null && now - t < cd) { sender.sendMessage(ChatColor.RED + "Wait " + ((cd - (now - t)) / 1000 + 1) + "s before reporting again."); return true; }
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        String reason = String.join(" ", target != null ? Arrays.copyOfRange(args, 1, args.length) : args).trim();
        if (target != null && reason.isEmpty()) { sender.sendMessage(ChatColor.RED + "Say why: /report " + target.getName() + " <reason>"); return true; }
        if (reason.length() > 500) reason = reason.substring(0, 500);
        String from = sender.getName() + (by != null ? " (" + where(by.getLocation()) + ")" : "");
        String about = target != null ? target.getName() + " (" + where(target.getLocation()) + ")" : null;
        // Discord
        String text = "🚩 **REPORT** from **" + Discord.esc(sender.getName()) + "**"
                + (by != null ? " at " + where(by.getLocation()) : "")
                + (about != null ? " against **" + Discord.esc(target.getName()) + "** at " + where(target.getLocation()) : "")
                + ": " + Discord.esc(reason);
        boolean posted = index.discord != null && index.discord.report(text);
        // staff in game
        String line = ChatColor.RED + "[Report] " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY
                + (about != null ? " → " + ChatColor.WHITE + about + ChatColor.GRAY : "") + ": " + ChatColor.WHITE + reason;
        int staff = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("faultlineindex.reports") && !p.equals(by)) { p.sendMessage(line); staff++; }
        index.getLogger().info("Report: " + ChatColor.stripColor(line));
        save(from, about, reason, now);
        if (by != null) last.put(by.getUniqueId(), now);
        sender.sendMessage(ChatColor.GREEN + "Thanks, your report was sent to the staff" + (posted ? "" : staff > 0 ? " online" : " (they'll see it when they're on)") + ".");
        return true;
    }

    private void save(String from, String about, String reason, long when) {
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> l = new ArrayList<>(y.getStringList("reports"));
        l.add(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(when)) + " | " + from + " | " + (about == null ? "-" : about) + " | " + reason);
        while (l.size() > 200) l.remove(0);
        y.set("reports", l);
        try { y.save(file); } catch (IOException e) { index.getLogger().warning("Couldn't save reports.yml: " + e.getMessage()); }
    }

    private boolean list(CommandSender sender, String[] args) {
        if (!sender.hasPermission("faultlineindex.reports")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        int n = 10;
        if (args.length > 0) try { n = Math.max(1, Math.min(50, Integer.parseInt(args[0]))); } catch (NumberFormatException ignored) { }
        List<String> l = file.exists() ? YamlConfiguration.loadConfiguration(file).getStringList("reports") : List.of();
        sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Reports " + ChatColor.GRAY + "(latest " + Math.min(n, l.size()) + " of " + l.size() + ")");
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) sender.sendMessage(ChatColor.GRAY + " " + l.get(i));
        return true;
    }
}
