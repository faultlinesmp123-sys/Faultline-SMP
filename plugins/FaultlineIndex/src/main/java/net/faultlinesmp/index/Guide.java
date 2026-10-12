package net.faultlinesmp.index;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /GUIDE (Index 1.6.14): the server tutorial, from guide.yml in the jar (getting started, the Index, the Quest Book,
 * the Black Market, bosses, raids/events/ships, items and cosmetics). Java: a book with a contents page that links to
 * every chapter. Bedrock: menus (chapter list -> the chapter's text). The Bosses chapter ends with every boss in the
 * Index and how to find or summon it (its "where" text), so it stays up to date. /guide <chapter> opens one directly;
 * new players are told about it on their first join.
 */
final class Guide implements CommandExecutor, TabCompleter {

    record Chapter(String id, String title, String icon, String text) { }

    private static final TextColor INK = TextColor.color(0x2B1D14), TITLE = TextColor.color(0x8B1A1A), HEAD = TextColor.color(0x1F5F8B),
            LINK = TextColor.color(0x3949AB), FADED = TextColor.color(0x8A7B6B);

    private final FaultlineIndex index;
    final List<Chapter> chapters = new ArrayList<>();

    Guide(FaultlineIndex index) {
        this.index = index;
        try (var r = new InputStreamReader(index.getResource("guide.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(r);
            for (Map<?, ?> m : y.getMapList("chapters")) {
                chapters.add(new Chapter(String.valueOf(m.get("id")), String.valueOf(m.get("title")),
                        m.get("icon") == null ? "faultline_index" : String.valueOf(m.get("icon")), String.valueOf(m.get("text"))));
            }
        } catch (Exception e) { index.getLogger().warning("Couldn't read guide.yml: " + e.getMessage()); }
    }

    /** A chapter's text, plus (for bosses) every boss entry and its "where". Lines: "#" heading, "-" bullet, "" break. */
    List<String> source(Chapter c) {
        List<String> out = new ArrayList<>(List.of(c.text().split("\n", -1)));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        if (c.id().equals("bosses")) {
            for (FaultlineIndex.Entry e : index.entries) {
                if (!"bosses".equals(e.chapter) || e.where == null || e.where.isBlank()) continue;
                out.add("");
                out.add("## " + e.name);
                out.add(e.where);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Java: the book

    List<Component> chapterLines(Chapter c) {
        List<Component> lines = new ArrayList<>();
        lines.add(index.iconComponent(c.icon(), false).append(Component.text(" " + FaultlineIndex.fitText(c.title(), 100), TITLE, TextDecoration.BOLD)));
        lines.add(Component.empty());
        for (String raw : source(c)) {
            String l = raw.strip();
            if (l.isEmpty()) { if (!lines.isEmpty() && !lines.get(lines.size() - 1).equals(Component.empty())) lines.add(Component.empty()); continue; }
            if (l.startsWith("## ")) { for (String w : FaultlineIndex.wrapText(l.substring(3), true)) lines.add(Component.text(w, TITLE, TextDecoration.BOLD)); continue; }
            if (l.startsWith("# ")) { for (String w : FaultlineIndex.wrapText(l.substring(2), true)) lines.add(Component.text(w, HEAD, TextDecoration.BOLD)); continue; }
            boolean bullet = l.startsWith("- ");
            List<String> w = FaultlineIndex.wrapText(bullet ? "• " + l.substring(2) : l, false);
            for (int i = 0; i < w.size(); i++) lines.add(Component.text((bullet && i > 0 ? "  " : "") + w.get(i), INK));
        }
        return lines;
    }

    void openBook(Player p, String only) {
        int per = FaultlineIndex.LINES_PER_PAGE - 1; // the last line of every page is the way back
        List<Component> pages = new ArrayList<>();
        List<Integer> start = new ArrayList<>();
        int pageNo = 2;
        List<List<List<Component>>> chunks = new ArrayList<>();
        for (Chapter c : chapters) {
            List<Component> lines = chapterLines(c);
            List<List<Component>> cp = new ArrayList<>();
            for (int i = 0; i < lines.size(); i += per) cp.add(new ArrayList<>(lines.subList(i, Math.min(lines.size(), i + per))));
            chunks.add(cp);
            start.add(pageNo);
            pageNo += cp.size();
        }
        // contents
        List<Component> toc = new ArrayList<>();
        toc.add(index.iconComponent("faultline_index", false).append(Component.text(" Faultline Guide", TITLE, TextDecoration.BOLD)));
        toc.add(Component.text("How everything works", FADED, TextDecoration.ITALIC));
        toc.add(Component.empty());
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            toc.add(index.iconComponent(c.icon(), false).append(Component.text(" " + FaultlineIndex.fitText(c.title(), 96), LINK))
                    .clickEvent(ClickEvent.changePage(start.get(i))).hoverEvent(HoverEvent.showText(Component.text("Go to " + c.title()))));
        }
        pages.add(FaultlineIndex.pageOf(toc));
        for (List<List<Component>> cp : chunks) {
            for (List<Component> pg : cp) {
                List<Component> full = new ArrayList<>(pg);
                while (full.size() < per) full.add(Component.empty());
                full.add(Component.text("« Contents", LINK).clickEvent(ClickEvent.changePage(1)).hoverEvent(HoverEvent.showText(Component.text("Back to the contents"))));
                pages.add(FaultlineIndex.pageOf(full));
            }
        }
        if (only != null) { // /guide <chapter>: just that chapter's pages, in a book of its own
            for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).id().equals(only)) {
                List<Component> just = new ArrayList<>();
                for (List<Component> pg : chunks.get(i)) just.add(FaultlineIndex.pageOf(pg));
                p.openBook(Book.book(Component.text(chapters.get(i).title()), Component.text("Faultline SMP"), just));
                return;
            }
        }
        p.openBook(Book.book(Component.text("Faultline Guide"), Component.text("Faultline SMP"), pages));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    // ---------------------------------------------------------------- Bedrock: menus

    static String bedrockText(List<String> src) {
        StringBuilder s = new StringBuilder();
        for (String raw : src) {
            String l = raw.strip();
            if (l.startsWith("## ")) s.append("\n§6§l").append(l.substring(3)).append("§r\n");
            else if (l.startsWith("# ")) s.append("\n§b§l").append(l.substring(2)).append("§r\n");
            else if (l.startsWith("- ")) s.append("• ").append(l.substring(2)).append("\n");
            else if (l.isEmpty()) s.append("\n");
            else s.append(l).append("\n");
        }
        return s.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    boolean menu(Player p) {
        List<String> buttons = new ArrayList<>();
        for (Chapter c : chapters) buttons.add("§l" + c.title());
        return BedrockForms.menu(index, p, "Faultline Guide", "How everything on the server works. Pick a chapter.", buttons,
                i -> { if (i < chapters.size()) chapter(p, chapters.get(i)); }, null);
    }

    boolean chapter(Player p, Chapter c) {
        return BedrockForms.menu(index, p, c.title(), bedrockText(source(c)), List.of("§l« Contents"), i -> menu(p), null);
    }

    // ---------------------------------------------------------------- the command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            for (Chapter c : chapters) sender.sendMessage(c.id() + ": " + c.title());
            return true;
        }
        String want = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;
        Chapter pick = null;
        if (want != null) for (Chapter c : chapters) if (c.id().equals(want) || c.title().toLowerCase(Locale.ROOT).startsWith(want)) { pick = c; break; }
        if (want != null && pick == null) { p.sendMessage(ChatColor.RED + "No chapter called " + args[0] + ". Try: " + String.join(", ", chapters.stream().map(Chapter::id).toList())); return true; }
        if (index.forms.use(p) && (pick == null ? menu(p) : chapter(p, pick))) return true;
        openBook(p, pick == null ? null : pick.id());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? chapters.stream().map(Chapter::id).filter(id -> id.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
    }
}
