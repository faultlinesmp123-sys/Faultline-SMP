package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.*;

/**
 * /serverstats: the whole server in numbers (for players, and for video stats): how many have joined, total playtime,
 * player kills, deaths, mobs killed, bosses defeated (Index credit), meteors claimed, skeleton ships sunk, locust swarms
 * driven off, Mimics killed, Ghost Ships beaten, achievements earned; and the top 3 for kills, boss defeats, playtime
 * and meteors. Worked out at most once a minute (every player who ever joined is counted).
 */
final class ServerStats implements CommandExecutor {

    private final FaultlineIndex index;
    private List<String> cached;
    private long cachedAt;

    ServerStats(FaultlineIndex index) { this.index = index; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (cached == null || System.currentTimeMillis() - cachedAt > 60000) { cached = build(); cachedAt = System.currentTimeMillis(); }
        for (String l : cached) sender.sendMessage(l);
        return true;
    }

    static int stat(OfflinePlayer p, Statistic s) {
        try { return p.getStatistic(s); } catch (RuntimeException e) { return 0; }
    }

    List<String> build() {
        OfflinePlayer[] all = Bukkit.getOfflinePlayers();
        long ticks = 0, pk = 0, deaths = 0, mobs = 0;
        Map<String, Long> kills = new HashMap<>(), time = new HashMap<>(), bosses = new HashMap<>(), meteors = new HashMap<>();
        for (OfflinePlayer p : all) {
            String n = p.getName() == null ? p.getUniqueId().toString().substring(0, 8) : p.getName();
            int t = stat(p, Statistic.PLAY_ONE_MINUTE), k = stat(p, Statistic.PLAYER_KILLS);
            ticks += t; pk += k; deaths += stat(p, Statistic.DEATHS); mobs += stat(p, Statistic.MOB_KILLS);
            kills.put(n, (long) k);
            time.put(n, (long) t);
            bosses.put(n, (long) index.bossKills(p.getUniqueId()));
            meteors.put(n, (long) index.achievements.stat(p.getUniqueId(), "meteors"));
        }
        long bossTotal = bosses.values().stream().mapToLong(Long::longValue).sum();
        long meteorTotal = meteors.values().stream().mapToLong(Long::longValue).sum();
        long ships = sum("skeleton_ships"), swarms = sum("locust_swarms"), mimics = sum("mimics"), ghosts = sum("ghost_ships"), trades = sum("caravan_trades");
        long ach = index.achievements.achieved.values().stream().mapToLong(Set::size).sum();
        List<String> out = new ArrayList<>();
        String bar = ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "                                        ";
        out.add(bar);
        out.add(ChatColor.GOLD + "" + ChatColor.BOLD + "  FAULTLINE SMP " + ChatColor.GRAY + "in numbers");
        out.add(line("Players", all.length) + "   " + line("Playtime", ticks / 72000) + ChatColor.GRAY + " h");
        out.add(line("Player kills", pk) + "   " + line("Deaths", deaths));
        out.add(line("Mobs killed", mobs) + "   " + line("Bosses defeated", bossTotal));
        out.add(line("Meteors claimed", meteorTotal) + "   " + line("Skeleton ships sunk", ships));
        out.add(line("Locust swarms", swarms) + "   " + line("Mimics", mimics) + "   " + line("Ghost Ships", ghosts));
        out.add(line("Caravan trades", trades) + "   " + line("Achievements", ach));
        out.add(ChatColor.GOLD + "  Top killers: " + top(kills, ""));
        out.add(ChatColor.GOLD + "  Top boss hunters: " + top(bosses, ""));
        out.add(ChatColor.GOLD + "  Most played: " + top(time, "h"));
        out.add(ChatColor.GOLD + "  Star collectors: " + top(meteors, ""));
        out.add(bar);
        return out;
    }

    long sum(String stat) {
        long n = 0;
        for (Map<String, Integer> m : index.achievements.stats.values()) n += m.getOrDefault(stat, 0);
        return n;
    }

    static String line(String what, long n) {
        return ChatColor.GRAY + "  " + what + ": " + ChatColor.WHITE + String.format(Locale.ROOT, "%,d", n);
    }

    static String top(Map<String, Long> m, String unit) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.removeIf(e -> e.getValue() <= 0);
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (l.isEmpty()) return ChatColor.DARK_GRAY + "nobody yet";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, l.size()); i++) {
            if (i > 0) sb.append(ChatColor.DARK_GRAY).append(", ");
            long v = unit.equals("h") ? l.get(i).getValue() / 72000 : l.get(i).getValue();
            sb.append(ChatColor.WHITE).append(l.get(i).getKey()).append(ChatColor.GRAY).append(" (").append(v).append(unit).append(")");
        }
        return sb.toString();
    }
}
