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
 * and meteors. Worked out at most once every 5 minutes (every player who ever joined is counted). Offline players' vanilla
 * stats are read from world/stats/<uuid>.json off the main thread (OfflinePlayer#getStatistic reads that file on the main
 * thread for each call: with hundreds of players /serverstats froze the server).
 */
final class ServerStats implements CommandExecutor {

    private final FaultlineIndex index;
    private List<String> cached;
    private long cachedAt;
    private List<CommandSender> waiting;

    ServerStats(FaultlineIndex index) { this.index = index; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (cached != null && System.currentTimeMillis() - cachedAt <= 300000) { for (String l : cached) sender.sendMessage(l); return true; }
        if (waiting != null) { waiting.add(sender); return true; }
        waiting = new ArrayList<>(List.of(sender));
        sender.sendMessage(ChatColor.GRAY + "Counting...");
        // main thread: the Index's own numbers and the online players' stats; then the stats files elsewhere
        List<Row> rows = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            Row r = new Row(p.getUniqueId(), p.getName() == null ? p.getUniqueId().toString().substring(0, 8) : p.getName(),
                    index.bossKills(p.getUniqueId()), index.achievements.stat(p.getUniqueId(), "meteors"));
            if (p.isOnline()) r.vanilla = new long[]{stat(p, Statistic.PLAY_ONE_MINUTE), stat(p, Statistic.PLAYER_KILLS), stat(p, Statistic.DEATHS), stat(p, Statistic.MOB_KILLS)};
            rows.add(r);
        }
        long ships = sum("skeleton_ships"), swarms = sum("locust_swarms"), mimics = sum("mimics"), ghosts = sum("ghost_ships"), trades = sum("caravan_trades");
        long ach = index.achievements.achieved.values().stream().mapToLong(Set::size).sum();
        // 26.x keeps them in players/stats, older versions in stats
        java.io.File dir = null;
        try {
            java.io.File wf = Bukkit.getWorlds().get(0).getWorldFolder();
            dir = new java.io.File(wf, "players/stats");
            if (!dir.isDirectory()) dir = new java.io.File(wf, "stats");
        } catch (RuntimeException ignored) { } // no world folder: offline players count as zeros
        java.io.File statsDir = dir;
        Bukkit.getScheduler().runTaskAsynchronously(index, () -> {
            for (Row r : rows) if (r.vanilla == null) r.vanilla = statsDir == null ? new long[4] : readStats(new java.io.File(statsDir, r.id + ".json"));
            List<String> out = build(rows, new long[]{ships, swarms, mimics, ghosts, trades, ach});
            Bukkit.getScheduler().runTask(index, () -> {
                cached = out; cachedAt = System.currentTimeMillis();
                List<CommandSender> to = waiting; waiting = null;
                for (CommandSender s : to) {
                    if (s instanceof org.bukkit.entity.Player pl && !pl.isOnline()) continue;
                    for (String l : out) s.sendMessage(l);
                }
            });
        });
        return true;
    }

    static final class Row {
        final UUID id; final String name; final long bosses, meteors;
        long[] vanilla; // play time (ticks), player kills, deaths, mob kills
        Row(UUID id, String name, long bosses, long meteors) { this.id = id; this.name = name; this.bosses = bosses; this.meteors = meteors; }
    }

    /** The four numbers from a vanilla stats file (minecraft:custom), zeros if it's missing or unreadable. */
    static long[] readStats(java.io.File f) {
        long[] v = new long[4];
        if (!f.isFile()) return v;
        try (java.io.Reader in = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject custom = com.google.gson.JsonParser.parseReader(in).getAsJsonObject()
                    .getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
            if (custom == null) return v;
            String[] keys = {"minecraft:play_time", "minecraft:player_kills", "minecraft:deaths", "minecraft:mob_kills"};
            for (int i = 0; i < 4; i++) if (custom.has(keys[i])) v[i] = custom.get(keys[i]).getAsLong();
            if (v[0] == 0 && custom.has("minecraft:play_one_minute")) v[0] = custom.get("minecraft:play_one_minute").getAsLong(); // older files
        } catch (Exception | LinkageError ignored) { }
        return v;
    }

    static int stat(OfflinePlayer p, Statistic s) {
        try { return p.getStatistic(s); } catch (RuntimeException e) { return 0; }
    }

    static List<String> build(List<Row> rows, long[] extra) {
        long ticks = 0, pk = 0, deaths = 0, mobs = 0;
        Map<String, Long> kills = new HashMap<>(), time = new HashMap<>(), bosses = new HashMap<>(), meteors = new HashMap<>();
        for (Row r : rows) {
            long[] v = r.vanilla == null ? new long[4] : r.vanilla;
            ticks += v[0]; pk += v[1]; deaths += v[2]; mobs += v[3];
            kills.put(r.name, v[1]);
            time.put(r.name, v[0]);
            bosses.put(r.name, r.bosses);
            meteors.put(r.name, r.meteors);
        }
        long bossTotal = bosses.values().stream().mapToLong(Long::longValue).sum();
        long meteorTotal = meteors.values().stream().mapToLong(Long::longValue).sum();
        long ships = extra[0], swarms = extra[1], mimics = extra[2], ghosts = extra[3], trades = extra[4], ach = extra[5];
        List<String> out = new ArrayList<>();
        String bar = ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "                                        ";
        out.add(bar);
        out.add(ChatColor.GOLD + "" + ChatColor.BOLD + "  FAULTLINE SMP " + ChatColor.GRAY + "in numbers");
        out.add(line("Players", rows.size()) + "   " + line("Playtime", ticks / 72000) + ChatColor.GRAY + " h");
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
