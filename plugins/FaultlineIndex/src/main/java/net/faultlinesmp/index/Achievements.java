package net.faultlinesmp.index;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Achievements: goals in index.yml (achievements:), shown in their own book from the Index cover. Each one checks the
 * player's Index progress (what they've collected and defeated, and how many) or a counter other plugins bump with
 * "/index stat <player> <name> [amount]" (meteors claimed, Caravan trades, skeleton ships sunk, Boss Rushes finished).
 * Finishing one tells the whole server and hands out its reward: items, XP, and sometimes a cosmetic (through
 * FaultlineCosmetics' "cosmetic unlock" command).
 */
final class Achievements {

    static final class Goal {
        String id, name, icon, desc, chapter, stat, cosmetic;
        List<String> defeat = List.of(), items = List.of();
        String need = "all"; // all | any | <total kills>
        int count, found, foundItems, foundCreatures, xp;
    }

    private static final TextColor INK = TextColor.color(0x2B1D14), FADED = TextColor.color(0x8A7B6B),
            DONE = TextColor.color(0x2E7D32), TITLE = TextColor.color(0x8B1A1A), REWARD = TextColor.color(0x9A6A00);

    private final FaultlineIndex index;
    final List<Goal> goals = new ArrayList<>();
    final Map<UUID, Map<String, Integer>> stats = new HashMap<>();
    final Map<UUID, Set<String>> achieved = new HashMap<>();

    Achievements(FaultlineIndex index) { this.index = index; }

    @SuppressWarnings("unchecked")
    void load(YamlConfiguration yml) {
        goals.clear();
        for (Map<?, ?> m : yml.getMapList("achievements")) {
            Goal g = new Goal();
            g.id = str(m, "id"); g.name = str(m, "name"); g.icon = str(m, "icon"); g.desc = str(m, "desc");
            g.chapter = str(m, "chapter"); g.stat = str(m, "stat");
            if (m.get("defeat") instanceof List<?> l) g.defeat = l.stream().map(String::valueOf).toList();
            if (m.get("need") != null) g.need = String.valueOf(m.get("need"));
            g.count = num(m, "count"); g.found = num(m, "found"); g.foundItems = num(m, "found-items"); g.foundCreatures = num(m, "found-creatures");
            if (m.get("reward") instanceof Map<?, ?> r) {
                g.cosmetic = str(r, "cosmetic");
                g.xp = num(r, "xp");
                if (r.get("items") instanceof List<?> l) g.items = l.stream().map(String::valueOf).toList();
            }
            if (g.id == null || g.name == null) { index.getLogger().warning("An achievement needs an id and a name: " + m); continue; }
            goals.add(g);
        }
    }

    private static String str(Map<?, ?> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }
    private static int num(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? 0 : Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ saving (inside progress.yml)

    void loadProgress(YamlConfiguration yml) {
        stats.clear(); achieved.clear();
        var sec = yml.getConfigurationSection("achievements");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            UUID u;
            try { u = UUID.fromString(id); } catch (IllegalArgumentException e) { continue; }
            achieved.put(u, new HashSet<>(sec.getStringList(id + ".done")));
            Map<String, Integer> st = new HashMap<>();
            var ss = sec.getConfigurationSection(id + ".stats");
            if (ss != null) for (String k : ss.getKeys(false)) st.put(k, ss.getInt(k));
            stats.put(u, st);
        }
    }

    void saveProgress(YamlConfiguration yml) {
        Set<UUID> ids = new HashSet<>(achieved.keySet());
        ids.addAll(stats.keySet());
        for (UUID u : ids) {
            Set<String> done = achieved.get(u);
            if (done != null && !done.isEmpty()) yml.set("achievements." + u + ".done", new ArrayList<>(done));
            Map<String, Integer> st = stats.get(u);
            if (st != null) for (Map.Entry<String, Integer> e : st.entrySet()) yml.set("achievements." + u + ".stats." + e.getKey(), e.getValue());
        }
    }

    void reset(UUID u) { stats.remove(u); achieved.remove(u); }

    // ------------------------------------------------------------------ progress

    int stat(UUID u, String name) { return stats.getOrDefault(u, Map.of()).getOrDefault(name, 0); }

    void addStat(Player p, String name, int amount) {
        stats.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(name, amount, Integer::sum);
        index.markDirty();
        check(p);
    }

    boolean done(UUID u, Goal g) { return achieved.getOrDefault(u, Set.of()).contains(g.id); }

    /** {have, need} for a goal. */
    int[] progress(UUID u, Goal g) {
        if (g.stat != null) return new int[]{stat(u, g.stat), Math.max(1, g.count)};
        if (!g.defeat.isEmpty()) {
            if (g.need.equalsIgnoreCase("any")) return new int[]{g.defeat.stream().anyMatch(e -> index.hasEntry(u, e)) ? 1 : 0, 1};
            if (g.need.equalsIgnoreCase("all")) return new int[]{(int) g.defeat.stream().filter(e -> index.hasEntry(u, e)).count(), g.defeat.size()};
            int need = 1;
            try { need = Integer.parseInt(g.need); } catch (NumberFormatException ignored) { }
            int total = 0;
            for (String e : g.defeat) total += Math.max(index.killCount(u, e), index.hasEntry(u, e) ? 1 : 0);
            return new int[]{total, need};
        }
        if (g.chapter != null) return index.chapterProgress(u, g.chapter);
        if (g.found > 0) return new int[]{index.foundCount(u, null), g.found};
        if (g.foundItems > 0) return new int[]{index.foundCount(u, false), g.foundItems};
        if (g.foundCreatures > 0) return new int[]{index.foundCount(u, true), g.foundCreatures};
        return new int[]{0, 1};
    }

    /** Checks every goal for this player; anything newly finished is announced and rewarded. */
    void check(Player p) {
        UUID u = p.getUniqueId();
        for (Goal g : goals) {
            if (done(u, g)) continue;
            int[] pr = progress(u, g);
            if (pr[0] < pr[1]) continue;
            achieved.computeIfAbsent(u, k -> new HashSet<>()).add(g.id);
            index.markDirty();
            complete(p, g);
        }
    }

    private void complete(Player p, Goal g) {
        Component name = Component.text(g.name, NamedTextColor.GOLD, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text(g.desc == null ? g.name : g.desc, NamedTextColor.GRAY)));
        Component all = Component.text("✦ ", NamedTextColor.GOLD).append(Component.text(p.getName(), NamedTextColor.WHITE))
                .append(Component.text(" earned the achievement ", NamedTextColor.YELLOW)).append(name);
        for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(all);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        p.showTitle(net.kyori.adventure.title.Title.title(Component.text("Achievement!", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(g.name, NamedTextColor.YELLOW)));
        List<String> got = new ArrayList<>();
        for (String spec : g.items) {
            String[] kv = spec.split(":");
            Material m = Material.matchMaterial(kv[0].trim());
            if (m == null || !m.isItem()) { index.getLogger().warning("Achievement " + g.id + ": unknown reward item " + spec); continue; }
            int n = 1;
            try { n = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1; } catch (NumberFormatException ignored) { }
            for (int left = n; left > 0; left -= m.getMaxStackSize()) {
                ItemStack it = new ItemStack(m, Math.min(left, m.getMaxStackSize()));
                p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            }
            got.add(n + " " + pretty(m));
        }
        if (g.xp > 0) { p.giveExp(g.xp); got.add(g.xp + " XP"); }
        if (g.cosmetic != null) {
            boolean ok = Bukkit.getPluginManager().getPlugin("FaultlineCosmetics") != null
                    && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cosmetic unlock " + p.getName() + " " + g.cosmetic);
            if (!ok) index.getLogger().warning("Couldn't unlock cosmetic " + g.cosmetic + " for " + p.getName() + " (is FaultlineCosmetics installed?)");
        }
        if (!got.isEmpty()) p.sendMessage(Component.text("  Reward: ", NamedTextColor.GRAY).append(Component.text(String.join(", ", got), NamedTextColor.WHITE)));
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    String rewardText(Goal g) {
        List<String> r = new ArrayList<>();
        if (g.cosmetic != null) r.add("a cosmetic");
        for (String spec : g.items) {
            String[] kv = spec.split(":");
            Material m = Material.matchMaterial(kv[0].trim());
            if (m != null) r.add((kv.length > 1 ? kv[1].trim() : "1") + " " + pretty(m));
        }
        if (g.xp > 0) r.add(g.xp + " XP");
        return String.join(", ", r);
    }

    // ------------------------------------------------------------------ the book

    int doneCount(UUID u) { return (int) goals.stream().filter(g -> done(u, g)).count(); }

    void open(Player p) {
        UUID u = p.getUniqueId();
        int per = 11;
        List<Component> pages = new ArrayList<>();
        int pagesN = Math.max(1, (goals.size() + per - 1) / per);
        for (int pg = 0; pg < pagesN; pg++) {
            List<Component> lines = new ArrayList<>();
            lines.add(index.iconComponent("check", false).append(Component.text(" Achievements " + doneCount(u) + "/" + goals.size(), TITLE, TextDecoration.BOLD)));
            lines.add(Component.empty());
            for (int i = pg * per; i < Math.min(goals.size(), (pg + 1) * per); i++) lines.add(row(u, goals.get(i)));
            while (lines.size() < 13) lines.add(Component.empty());
            lines.add(Component.text("« Contents", TextColor.color(0x3949AB)).clickEvent(ClickEvent.runCommand("/index"))
                    .hoverEvent(HoverEvent.showText(Component.text("Back to the cover"))));
            pages.add(FaultlineIndex.pageOf(lines));
        }
        p.openBook(Book.book(Component.text("Achievements"), Component.text("Faultline SMP"), pages));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    private Component row(UUID u, Goal g) {
        boolean d = done(u, g);
        int[] pr = progress(u, g);
        Component hover = Component.text(g.name, NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.newline())
                .append(Component.text(g.desc == null ? "" : g.desc, NamedTextColor.GRAY)).append(Component.newline())
                .append(Component.text(d ? "Done!" : "Progress: " + Math.min(pr[0], pr[1]) + "/" + pr[1], d ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        String rw = rewardText(g);
        if (!rw.isEmpty()) hover = hover.append(Component.newline()).append(Component.text("Reward: " + rw, NamedTextColor.GOLD));
        String label = FaultlineIndex.fitText(g.name, d ? 92 : 70);
        Component line = index.iconComponent(d ? "check" : "box", false).append(Component.text(" "))
                .append(index.iconComponent(g.icon == null ? "box" : g.icon, false))
                .append(Component.text(" " + label, d ? DONE : INK));
        if (!d) line = line.append(Component.text(" " + Math.min(pr[0], pr[1]) + "/" + pr[1], FADED));
        return line.hoverEvent(HoverEvent.showText(hover));
    }
}
