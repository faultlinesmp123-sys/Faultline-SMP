package net.faultlinesmp.index;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Index for BEDROCK players, as native forms (menus of buttons) instead of the book. Geyser can't follow the book's
 * links (the cover's chapter links ran /index open, which Bedrock can't click), so a Bedrock player could open the cover
 * and never get any further. Cover (progress, Achievements, every chapter) -> chapter (every entry, ??? for creatures
 * not defeated yet) -> the entry's page (Back goes to its chapter). Falls back to the book when no form can be sent.
 */
final class IndexForms {
    private final FaultlineIndex ix;

    IndexForms(FaultlineIndex ix) { this.ix = ix; }

    /** Should this player get forms? (on Bedrock, and Floodgate or Geyser can send them) */
    boolean use(Player p) {
        return ix.getConfig().getBoolean("bedrock-forms", true) && Edition.bedrock(p) && BedrockForms.available();
    }

    private static String count(long have, long all) { return (have == all ? "§2" : "§8") + have + "/" + all; }

    boolean cover(Player p) {
        UUID id = p.getUniqueId();
        int itemsHave = 0, itemsAll = 0, mobsHave = 0, mobsAll = 0;
        for (FaultlineIndex.Entry e : ix.entries) {
            if (e.creature) { mobsAll++; if (ix.has(id, e)) mobsHave++; }
            else { itemsAll++; if (ix.has(id, e)) itemsHave++; }
        }
        int achDone = ix.achievements.doneCount(id), achAll = ix.achievements.goals.size();
        String content = "§lItems:§r " + count(itemsHave, itemsAll) + "§r\n§lCreatures:§r " + count(mobsHave, mobsAll)
                + "§r\n§lAchievements:§r " + count(achDone, achAll) + "§r\n\nPick a chapter.";
        Map<String, List<FaultlineIndex.Entry>> by = ix.chapterMap();
        List<String> buttons = new ArrayList<>();
        List<FaultlineIndex.Chapter> order = new ArrayList<>();
        buttons.add("§6§lAchievements§r\n" + count(achDone, achAll));
        for (FaultlineIndex.Chapter c : ix.chapters) {
            List<FaultlineIndex.Entry> list = by.getOrDefault(c.id(), List.of());
            long have = list.stream().filter(e -> ix.has(id, e)).count();
            buttons.add("§l" + c.title() + "§r\n" + count(have, list.size()));
            order.add(c);
        }
        boolean sent = BedrockForms.menu(ix, p, "Faultline Index", content, buttons, i -> {
            if (i == 0) achievements(p);
            else if (i - 1 < order.size()) chapter(p, order.get(i - 1));
        }, null);
        if (sent) p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
        return sent;
    }

    boolean chapter(Player p, FaultlineIndex.Chapter c) {
        UUID id = p.getUniqueId();
        List<FaultlineIndex.Entry> list = ix.chapterMap().getOrDefault(c.id(), List.of());
        List<String> buttons = new ArrayList<>();
        buttons.add("§l« Contents");
        for (FaultlineIndex.Entry e : list) {
            boolean got = ix.has(id, e);
            if (e.creature && !got) buttons.add("§8???");
            else buttons.add((got ? "§2✔ " : "§8") + e.name);
        }
        long have = list.stream().filter(e -> ix.has(id, e)).count();
        return BedrockForms.menu(ix, p, c.title(), "Found " + have + " of " + list.size() + ".", buttons, i -> {
            if (i == 0) cover(p);
            else if (i - 1 < list.size()) entry(p, c, list.get(i - 1));
        }, null);
    }

    boolean entry(Player p, FaultlineIndex.Chapter c, FaultlineIndex.Entry e) {
        UUID id = p.getUniqueId();
        boolean got = ix.has(id, e);
        StringBuilder s = new StringBuilder();
        if (e.creature && !got) {
            s.append("§7Not defeated yet.\n\n§oDefeat one to fill in this page with what it does and what it drops.");
        } else {
            if (e.creature) {
                int n = ix.kills.getOrDefault(id, Map.of()).getOrDefault(e.id, 0);
                s.append("friends".equals(e.chapter) ? "§2✔ Met" : "§2✔ Defeated" + (n > 1 ? " x" + n : ""));
            } else s.append(got ? "§2✔ Collected" : "§8Not found yet");
            s.append("§r\n\n");
            if (e.creature) {
                section(s, "Where to find it", "§9", e.where);
                section(s, "What it does", "§5", e.does);
                if (!e.drops.isEmpty()) {
                    s.append("§6§lDrops§r\n");
                    for (String d : e.drops) {
                        String[] parts = d.split("\\|");
                        String chance = parts.length > 1 && !parts[1].isEmpty() && !parts[1].equals("-") ? " §6" + parts[1] : "";
                        s.append("• ").append(parts[0]).append(chance).append("§r\n");
                    }
                }
            } else {
                section(s, "How to get it", "§9", e.how);
                section(s, "What it does", "§5", e.does);
            }
        }
        String title = e.creature && !got ? "???" : e.name;
        return BedrockForms.menu(ix, p, title, s.toString().trim(), List.of("§l« Back to " + c.title()), i -> chapter(p, c), null);
    }

    private static void section(StringBuilder s, String heading, String color, String text) {
        if (text == null || text.isBlank()) return;
        s.append(color).append("§l").append(heading).append("§r\n").append(text).append("\n\n");
    }

    boolean achievements(Player p) {
        UUID u = p.getUniqueId();
        Achievements a = ix.achievements;
        List<String> buttons = new ArrayList<>();
        buttons.add("§l« Contents");
        for (Achievements.Goal g : a.goals) {
            boolean d = a.done(u, g);
            int[] pr = a.progress(u, g);
            buttons.add(d ? "§2✔ " + g.name : g.name + "\n§8" + Math.min(pr[0], pr[1]) + "/" + pr[1]);
        }
        return BedrockForms.menu(ix, p, "Achievements", "Done " + a.doneCount(u) + " of " + a.goals.size() + ".", buttons, i -> {
            if (i == 0) cover(p);
            else if (i - 1 < a.goals.size()) goal(p, a.goals.get(i - 1));
        }, null);
    }

    boolean goal(Player p, Achievements.Goal g) {
        Achievements a = ix.achievements;
        UUID u = p.getUniqueId();
        boolean d = a.done(u, g);
        int[] pr = a.progress(u, g);
        String rw = a.rewardText(g);
        String s = (g.desc == null ? "" : g.desc + "\n\n") + (d ? "§2✔ Done!" : "§eProgress: " + Math.min(pr[0], pr[1]) + "/" + pr[1])
                + (rw.isEmpty() ? "" : "§r\n§6Reward: " + rw);
        return BedrockForms.menu(ix, p, g.name, s, List.of("§l« Back to Achievements"), i -> achievements(p), null);
    }
}
