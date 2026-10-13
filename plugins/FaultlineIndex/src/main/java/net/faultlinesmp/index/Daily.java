package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE DAILY STATS POST (Index 1.6.20, config daily-stats:, data daily.yml). Every day at daily-stats.hour (9) in
 * daily-stats.zone the Discord gets an embed of the last day: top killer (PvP), most deaths, longest playtime, mob
 * slayer, the RAREST FIND (the Index entry discovered that day that the fewest players have ever found), bosses beaten,
 * achievements earned, and server totals (players, new players, PvP kills, diamonds + debris mined, hours played).
 * It goes to the public webhook, or the logs channel when there's none. Counted live and saved, so a restart loses
 * nothing; a day nobody played posts nothing. /discord daily posts the numbers so far right away (and starts a new day).
 */
final class Daily implements Listener {

    private final FaultlineIndex index;
    private final File file;
    final Map<String, Map<UUID, Integer>> counts = new HashMap<>(); // kills, deaths, mobs, minutes, ores
    final Map<UUID, String> names = new HashMap<>();
    final Set<UUID> played = new LinkedHashSet<>(), fresh = new LinkedHashSet<>();
    final List<String[]> finds = new ArrayList<>();   // {uuid, entry id}
    final List<String> bosses = new ArrayList<>(), achievements = new ArrayList<>();
    private final Map<UUID, Long> sessionStart = new HashMap<>();
    String lastPost = "";

    Daily(FaultlineIndex index) {
        this.index = index;
        file = new File(index.getDataFolder(), "daily.yml");
        load();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) { sessionStart.put(p.getUniqueId(), now); note(p); }
        Bukkit.getScheduler().runTaskTimer(index, this::minute, 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(index, this::save, 20L * 300, 20L * 300);
    }

    ZoneId zone() {
        try { return ZoneId.of(index.getConfig().getString("daily-stats.zone", "America/New_York")); } catch (RuntimeException e) { return ZoneId.systemDefault(); }
    }

    private boolean on() { return index.getConfig().getBoolean("daily-stats.enabled", true); }

    private void add(String what, UUID u, int n) { counts.computeIfAbsent(what, k -> new HashMap<>()).merge(u, n, Integer::sum); }

    private void note(Player p) { names.put(p.getUniqueId(), p.getName()); played.add(p.getUniqueId()); }

    // ---------------------------------------------------------------- counting

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        note(p);
        if (!p.hasPlayedBefore()) fresh.add(p.getUniqueId());
        sessionStart.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { endSession(e.getPlayer().getUniqueId(), System.currentTimeMillis(), true); }

    private void endSession(UUID u, long now, boolean leaving) {
        Long s = leaving ? sessionStart.remove(u) : sessionStart.put(u, now);
        if (s != null && now > s) add("minutes", u, (int) ((now - s) / 60000));
        if (s != null && !leaving) sessionStart.put(u, now - (now - s) % 60000); // keep the leftover seconds
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        note(p);
        add("deaths", p.getUniqueId(), 1);
        Player k = p.getKiller();
        if (k != null && k != p) { note(k); add("kills", k.getUniqueId(), 1); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMob(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player || !(e.getEntity() instanceof Enemy)) return;
        Player k = e.getEntity().getKiller();
        if (k != null) add("mobs", k.getUniqueId(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOre(BlockBreakEvent e) {
        Material m = e.getBlock().getType();
        if (e.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE) add("diamonds", e.getPlayer().getUniqueId(), 1);
        else if (m == Material.ANCIENT_DEBRIS) add("debris", e.getPlayer().getUniqueId(), 1);
    }

    void found(Player p, FaultlineIndex.Entry e) { note(p); finds.add(new String[]{p.getUniqueId().toString(), e.id}); }

    void boss(Player p, FaultlineIndex.Entry e) { note(p); bosses.add(p.getName() + ": " + e.name); }

    void achievement(Player p, String name) { note(p); achievements.add(p.getName() + ": " + name); }

    // ---------------------------------------------------------------- posting

    void minute() {
        if (!on()) return;
        ZonedDateTime now = ZonedDateTime.now(zone());
        String today = now.toLocalDate().toString();
        if (lastPost.isEmpty()) { lastPost = today; return; } // first run: count from now
        if (today.equals(lastPost) || now.getHour() < index.getConfig().getInt("daily-stats.hour", 9)) return;
        post(now.toLocalDate().minusDays(1));
        lastPost = today;
        save();
    }

    Map.Entry<UUID, Integer> top(String what) {
        Map<UUID, Integer> m = counts.get(what);
        if (m == null) return null;
        return m.entrySet().stream().filter(x -> x.getValue() > 0).max(Map.Entry.comparingByValue()).orElse(null);
    }

    int total(String what) { Map<UUID, Integer> m = counts.get(what); return m == null ? 0 : m.values().stream().mapToInt(Integer::intValue).sum(); }

    String name(UUID u) {
        String n = names.get(u);
        if (n == null) n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? "someone" : n;
    }

    static String time(int minutes) { return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m"; }

    /** The rarest Index find of the day: {player, entry name, players who have it}. */
    String[] rarest() {
        String[] best = null;
        int fewest = Integer.MAX_VALUE;
        for (String[] f : finds) {
            FaultlineIndex.Entry e = index.entryById(f[1]);
            if (e == null) continue;
            int have = Math.max(1, index.howManyFound(f[1]));
            if (have < fewest) { fewest = have; best = new String[]{name(UUID.fromString(f[0])), e.name, String.valueOf(have)}; }
        }
        return best;
    }

    /** The embed's lines (also used by the test and /discord daily). */
    List<String[]> fields() {
        long now = System.currentTimeMillis();
        for (UUID u : new ArrayList<>(sessionStart.keySet())) endSession(u, now, false);
        List<String[]> f = new ArrayList<>();
        var k = top("kills");
        if (k != null) f.add(new String[]{"🗡️ Top killer", Discord.esc(name(k.getKey())) + " (" + k.getValue() + " kill" + (k.getValue() == 1 ? "" : "s") + ")"});
        var d = top("deaths");
        if (d != null) f.add(new String[]{"💀 Most deaths", Discord.esc(name(d.getKey())) + " (" + d.getValue() + ")"});
        var t = top("minutes");
        if (t != null) f.add(new String[]{"⏱️ Longest playtime", Discord.esc(name(t.getKey())) + " (" + time(t.getValue()) + ")"});
        var m = top("mobs");
        if (m != null) f.add(new String[]{"🧟 Mob slayer", Discord.esc(name(m.getKey())) + " (" + m.getValue() + " monsters)"});
        String[] r = rarest();
        if (r != null) f.add(new String[]{"✨ Rarest find", Discord.esc(r[0]) + " found **" + Discord.esc(r[1]) + "** (" + (r[2].equals("1") ? "the only one who has it" : "only " + r[2] + " players have it") + ")"});
        if (!bosses.isEmpty()) f.add(new String[]{"👑 Bosses beaten", Discord.esc(String.join(", ", bosses.subList(0, Math.min(8, bosses.size())))) + (bosses.size() > 8 ? " +" + (bosses.size() - 8) + " more" : "")});
        if (!achievements.isEmpty()) f.add(new String[]{"🏆 Achievements", Discord.esc(String.join(", ", achievements.subList(0, Math.min(8, achievements.size())))) + (achievements.size() > 8 ? " +" + (achievements.size() - 8) + " more" : "")});
        f.add(new String[]{"📈 Server", played.size() + " players (" + fresh.size() + " new) · " + total("kills") + " PvP kills · " + total("deaths") + " deaths · "
                + total("diamonds") + " diamond ore · " + total("debris") + " ancient debris · " + time(total("minutes")) + " played"});
        return f;
    }

    /** Posts and starts a new day. False = nothing to post or nowhere to post it. */
    boolean post(LocalDate day) {
        if (played.isEmpty()) { reset(); return false; }
        List<String[]> f = fields();
        String title = "📊 Faultline SMP: " + day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH));
        boolean ok = index.discord != null && index.discord.embed(title, f, 0xF4B400);
        reset();
        return ok;
    }

    void reset() {
        counts.clear(); played.clear(); fresh.clear(); finds.clear(); bosses.clear(); achievements.clear();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) { sessionStart.put(p.getUniqueId(), now); note(p); }
    }

    // ---------------------------------------------------------------- saving

    void save() {
        long now = System.currentTimeMillis();
        for (UUID u : new ArrayList<>(sessionStart.keySet())) endSession(u, now, false);
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-post", lastPost);
        counts.forEach((what, m) -> m.forEach((u, n) -> y.set("counts." + what + "." + u, n)));
        names.forEach((u, n) -> y.set("names." + u, n));
        y.set("played", played.stream().map(UUID::toString).toList());
        y.set("new", fresh.stream().map(UUID::toString).toList());
        y.set("finds", finds.stream().map(a -> a[0] + ";" + a[1]).toList());
        y.set("bosses", bosses);
        y.set("achievements", achievements);
        try { y.save(file); } catch (IOException e) { index.getLogger().warning("Couldn't save daily.yml: " + e.getMessage()); }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        lastPost = y.getString("last-post", "");
        ConfigurationSection c = y.getConfigurationSection("counts");
        if (c != null) for (String what : c.getKeys(false)) {
            ConfigurationSection m = c.getConfigurationSection(what);
            if (m != null) for (String u : m.getKeys(false)) try { add(what, UUID.fromString(u), m.getInt(u)); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection n = y.getConfigurationSection("names");
        if (n != null) for (String u : n.getKeys(false)) try { names.put(UUID.fromString(u), n.getString(u)); } catch (IllegalArgumentException ignored) { }
        for (String s : y.getStringList("played")) try { played.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        for (String s : y.getStringList("new")) try { fresh.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        for (String s : y.getStringList("finds")) { String[] a = s.split(";", 2); if (a.length == 2) finds.add(a); }
        bosses.addAll(y.getStringList("bosses"));
        achievements.addAll(y.getStringList("achievements"));
    }
}
