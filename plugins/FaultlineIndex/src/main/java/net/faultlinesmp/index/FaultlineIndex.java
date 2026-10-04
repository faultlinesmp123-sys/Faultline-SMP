package net.faultlinesmp.index;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * THE FAULTLINE INDEX — an in-game book of every item, boss, and enemy.
 *
 * Content lives in index.yml INSIDE the jar (so every update ships its new entries;
 * the server copy is never used). Icons come from the "faultline:index" font in the
 * FaultlineItemTextures resource pack; the glyph for each icon is in glyphs.yml.
 *
 * Items are always readable; their check turns green once the player has held one.
 * Creatures show ??? until the player has helped defeat one (killer + everyone who
 * damaged it), then reveal their portrait, what they do, and every drop with its chance.
 */
public final class FaultlineIndex extends JavaPlugin implements Listener {
    /** Adds a recipe, replacing any copy left over from before a reload ("Duplicate recipe" would stop the plugin from starting). */
    static void addRecipeSafely(org.bukkit.inventory.Recipe recipe) {
        if (recipe instanceof org.bukkit.Keyed keyed) org.bukkit.Bukkit.removeRecipe(keyed.getKey());
        try {
            org.bukkit.Bukkit.addRecipe(recipe);
        } catch (IllegalStateException e) {
            org.bukkit.Bukkit.getLogger().warning("Couldn't add a recipe: " + e.getMessage());
        }
    }


    private static final Key ICON_FONT = Key.key("faultline", "index");
    private static final int PAGE_WIDTH = 114;   // usable pixels per book line
    private static final int PAGE_LINES = 14;    // lines per book page

    // colors that read well on the book's cream paper
    private static final TextColor INK = TextColor.color(0x2B1D14);
    private static final TextColor FADED = TextColor.color(0x8A7B6B);
    private static final TextColor FOUND = TextColor.color(0x2E7D32);
    private static final TextColor TITLE = TextColor.color(0x8B1A1A);
    private static final TextColor HEAD_HOW = TextColor.color(0x1F5F8B);
    private static final TextColor HEAD_DOES = TextColor.color(0x6A2C8C);
    private static final TextColor HEAD_DROPS = TextColor.color(0x9A6A00);
    private static final TextColor LINK = TextColor.color(0x3949AB);

    record Chapter(String id, String title, boolean creature, String icon) {}

    static final class Entry {
        String id, chapter, name, icon, how, does, where, tag, nameMatch;
        NamespacedKey key;
        Integer keyValue;
        String keyText; // "<plugin>:<key>=<word>": items tagged with a text value (e.g. Diamond Jacob's items)
        EntityType type;
        List<String> drops = List.of();
        boolean creature;
    }

    private final List<Chapter> chapters = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<NamespacedKey, List<Entry>> byKey = new HashMap<>();
    private final Map<String, int[]> glyphs = new HashMap<>();

    // per-player progress
    private final Map<UUID, Set<String>> found = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> kills = new HashMap<>();
    private final Set<UUID> gotBook = new HashSet<>();
    private final Map<UUID, Set<UUID>> damagers = new HashMap<>(); // creature -> players who hit it
    private File dataFile;
    private boolean dirty;

    private NamespacedKey bookKey;

    // ===================== LIFECYCLE =====================

    @Override
    public void onEnable() {
        bookKey = new NamespacedKey(this, "index_book");
        loadContent();
        dataFile = new File(getDataFolder(), "progress.yml");
        loadProgress();

        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(this, "index_book"), indexBook());
        recipe.addIngredient(Material.LEATHER);
        recipe.addIngredient(Material.BOOK);
        FaultlineIndex.addRecipeSafely(recipe);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("index").setExecutor(new IndexCommand());
        getServer().getScheduler().runTaskTimer(this, this::scanInventories, 40L, 40L);
        getServer().getScheduler().runTaskTimer(this, () -> { if (dirty) saveProgress(); }, 20L * 60, 20L * 60);
        // creatures that despawn or unload never fire a death event; forget who hit them
        getServer().getScheduler().runTaskTimer(this, () -> damagers.keySet().removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        }), 20L * 60, 20L * 60);
        getLogger().info("Faultline Index loaded: " + entries.size() + " entries in " + chapters.size() + " chapters.");
    }

    @Override
    public void onDisable() {
        saveProgress();
    }

    private void loadContent() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("index.yml"), StandardCharsets.UTF_8));
        for (Map<?, ?> c : yml.getMapList("chapters")) {
            chapters.add(new Chapter(str(c, "id"), str(c, "title"), "creature".equals(str(c, "kind")), str(c, "icon")));
        }
        Set<String> ids = new HashSet<>();
        for (Map<?, ?> m : yml.getMapList("entries")) {
            Entry e = new Entry();
            e.chapter = str(m, "chapter");
            e.name = str(m, "name");
            // an explicit id keeps players' progress when an entry is renamed
            e.id = str(m, "id") != null ? str(m, "id") : e.name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
            if (!ids.add(e.id)) getLogger().warning("Duplicate Index entry: " + e.name);
            e.icon = str(m, "icon");
            e.how = str(m, "how");
            e.does = str(m, "does");
            e.where = str(m, "where");
            e.creature = chapters.stream().anyMatch(ch -> ch.id().equals(e.chapter) && ch.creature());
            String detect = str(m, "detect");
            if (detect != null) {
                String[] kv = detect.split("=");
                e.key = NamespacedKey.fromString(kv[0]);
                if (kv.length > 1) {
                    try { e.keyValue = Integer.parseInt(kv[1]); } catch (NumberFormatException ex) { e.keyText = kv[1]; }
                }
                byKey.computeIfAbsent(e.key, k -> new ArrayList<>()).add(e);
            }
            e.tag = str(m, "detect-tag");
            e.nameMatch = str(m, "detect-name");
            String type = str(m, "detect-type");
            if (type != null) e.type = EntityType.valueOf(type);
            if (m.get("drops") instanceof List<?> list) e.drops = list.stream().map(String::valueOf).toList();
            entries.add(e);
        }
        YamlConfiguration g = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("glyphs.yml"), StandardCharsets.UTF_8));
        for (String name : g.getKeys(false)) {
            glyphs.put(name, new int[]{g.getInt(name + ".small"), g.getInt(name + ".large")});
        }
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ===================== PROGRESS SAVE/LOAD =====================

    private void loadProgress() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : yml.getStringList("got-book")) gotBook.add(UUID.fromString(s));
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) return;
        for (String id : players.getKeys(false)) {
            UUID uuid = UUID.fromString(id);
            found.put(uuid, new HashSet<>(players.getStringList(id + ".found")));
            Map<String, Integer> k = new HashMap<>();
            ConfigurationSection ks = players.getConfigurationSection(id + ".kills");
            if (ks != null) for (String e : ks.getKeys(false)) k.put(e, ks.getInt(e));
            kills.put(uuid, k);
        }
    }

    private void saveProgress() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("got-book", gotBook.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Set<String>> e : found.entrySet()) {
            yml.set("players." + e.getKey() + ".found", new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<UUID, Map<String, Integer>> e : kills.entrySet()) {
            for (Map.Entry<String, Integer> k : e.getValue().entrySet()) {
                yml.set("players." + e.getKey() + ".kills." + k.getKey(), k.getValue());
            }
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Couldn't save Index progress", ex);
        }
    }

    // ===================== THE BOOK ITEM =====================

    ItemStack indexBook() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "faultline_index"));
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Faultline Index");
        meta.setLore(List.of(ChatColor.GRAY + "Every item, boss, and enemy", ChatColor.GRAY + "on the server.",
                ChatColor.YELLOW + "Right-click to open"));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isIndexBook(ItemStack item) {
        return item != null && item.getType() == Material.BOOK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.discoverRecipe(new NamespacedKey(this, "index_book"));
        if (gotBook.add(p.getUniqueId())) { // first time: everyone starts with one
            p.getInventory().addItem(indexBook()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            p.sendMessage(ChatColor.GOLD + "You received the " + ChatColor.BOLD + "Faultline Index" + ChatColor.GOLD
                    + ". Right-click it to see every item, boss, and enemy!");
            dirty = true;
        }
    }

    @EventHandler
    public void onOpen(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isIndexBook(event.getItem())) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        open(event.getPlayer());
    }

    /** The Index is never an ingredient (it's a Book underneath: bookshelves, Quest Books...). */
    @EventHandler
    public void onCraftGuard(PrepareItemCraftEvent event) {
        for (ItemStack i : event.getInventory().getMatrix()) {
            if (isIndexBook(i)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ===================== DISCOVERY: ITEMS =====================

    /** Returns the entries this item counts for (usually 0 or 1). */
    private List<Entry> itemEntries(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return List.of();
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        List<Entry> result = new ArrayList<>();
        for (NamespacedKey k : pdc.getKeys()) {
            List<Entry> list = byKey.get(k);
            if (list == null) continue;
            for (Entry e : list) {
                if (e.keyText != null) {
                    if (pdc.has(k, PersistentDataType.STRING) && e.keyText.equals(pdc.get(k, PersistentDataType.STRING))) result.add(e);
                } else if (e.keyValue == null) {
                    result.add(e);
                } else {
                    Integer v = pdc.get(k, PersistentDataType.INTEGER);
                    if (e.keyValue.equals(v)) result.add(e);
                }
            }
        }
        return result;
    }

    private void checkItem(Player p, ItemStack item) {
        for (Entry e : itemEntries(item)) unlock(p, e, false);
    }

    private void scanInventories() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack i : p.getInventory().getContents()) checkItem(p, i);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) checkItem(p, event.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player p) checkItem(p, event.getCurrentItem());
    }

    // ===================== DISCOVERY: CREATURES =====================

    private Entry creatureEntry(Entity e) {
        for (Entry entry : entries) {
            if (!entry.creature) continue;
            if (entry.tag == null && entry.nameMatch == null && entry.type == null) continue;
            if (entry.tag != null && !e.getScoreboardTags().contains(entry.tag)) continue;
            if (entry.type != null && e.getType() != entry.type) continue;
            if (entry.nameMatch != null) {
                String name = e.getCustomName() == null ? null : ChatColor.stripColor(e.getCustomName());
                if (!entry.nameMatch.equals(name)) continue;
            }
            return entry;
        }
        return null;
    }

    /** Everyone who helps fight a creature gets credit when it dies (important for bosses). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || event.getEntity() instanceof Player) return;
        if (creatureEntry(event.getEntity()) == null) return;
        damagers.computeIfAbsent(event.getEntity().getUniqueId(), k -> new HashSet<>()).add(attacker.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Set<UUID> helpers = damagers.remove(dead.getUniqueId());
        Entry entry = creatureEntry(dead);
        if (entry == null) return;
        Set<UUID> credit = helpers == null ? new HashSet<>() : new HashSet<>(helpers);
        if (dead.getKiller() != null) credit.add(dead.getKiller().getUniqueId());
        for (UUID id : credit) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            kills.computeIfAbsent(id, k -> new HashMap<>()).merge(entry.id, 1, Integer::sum);
            unlock(p, entry, true);
        }
        dirty = true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (dirty) saveProgress();
    }

    /** First discovery: tell the player, with a click-to-open link. */
    private void unlock(Player p, Entry e, boolean creature) {
        if (!found.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(e.id)) return;
        dirty = true;
        Component msg = Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text(creature ? "New creature in your Index: " : "New item in your Index: ", NamedTextColor.YELLOW))
                .append(Component.text(e.name, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  [open]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/index"))
                        .hoverEvent(HoverEvent.showText(Component.text("Open your Faultline Index"))));
        p.sendMessage(msg);
        p.playSound(p.getLocation(), creature ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.6f, creature ? 1f : 1.6f);
    }

    private boolean has(UUID player, Entry e) {
        Set<String> s = found.get(player);
        return s != null && s.contains(e.id);
    }

    // ===================== RENDERING THE BOOK =====================

    /**
     * An icon, wrapped in an empty parent. Text inherits the font of whatever it's
     * appended to, so appending words straight onto the glyph turned every letter into
     * the icon font (boxes). With the wrapper, the glyph and the words are siblings and
     * the words keep the normal font.
     */
    private Component icon(String name, boolean large) {
        int[] g = glyphs.get(name);
        if (g == null) g = glyphs.get("unknown");
        Component glyph = Component.text(Character.toString(large ? g[1] : g[0])).font(ICON_FONT).color(NamedTextColor.WHITE);
        return Component.empty().append(glyph);
    }

    /** Approximate width of text in Minecraft's default font, in pixels (bold adds 1 per character). */
    private static int width(String text, boolean bold) {
        int w = 0;
        for (char c : text.toCharArray()) {
            int cw = switch (c) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l', '`' -> 3;
                case 't', 'I', '[', ']', ' ', '(', ')', '*', '"' -> 4;
                case 'f', 'k', '<', '>', '{', '}' -> 5;
                case '@', '~', '•' -> 7;
                default -> 6;
            };
            w += cw + (bold && c != ' ' ? 1 : 0);
        }
        return w;
    }

    private static List<String> wrap(String text, int maxWidth, boolean bold) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (width(tryLine, bold) <= maxWidth || line.isEmpty()) {
                line = new StringBuilder(tryLine);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String fit(String text, int maxWidth, boolean bold) {
        if (width(text, bold) <= maxWidth) return text;
        String t = text;
        while (!t.isEmpty() && width(t + "…", bold) > maxWidth) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    private static Component page(List<Component> lines) {
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(Component.newline());
            b.append(lines.get(i));
        }
        return b.build();
    }

    private static final Component BLANK = Component.empty();

    /**
     * The cover: progress plus a link to each chapter. Each chapter opens as its OWN book
     * (a Minecraft book holds at most 100 pages, and the Index keeps growing with updates).
     */
    void open(Player p) {
        UUID id = p.getUniqueId();
        Map<String, List<Entry>> byChapter = chapterMap();
        p.openBook(Book.book(Component.text("Faultline Index"), Component.text("Faultline SMP"), List.of(cover(id, byChapter))));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    private Map<String, List<Entry>> chapterMap() {
        Map<String, List<Entry>> byChapter = new LinkedHashMap<>();
        for (Chapter c : chapters) byChapter.put(c.id(), new ArrayList<>());
        for (Entry e : entries) byChapter.get(e.chapter).add(e);
        return byChapter;
    }

    /** One chapter as a book: its list page(s) first, then every entry's page(s). */
    void openChapter(Player p, String chapterId) {
        Chapter c = chapters.stream().filter(x -> x.id().equalsIgnoreCase(chapterId)).findFirst().orElse(null);
        if (c == null) {
            open(p);
            return;
        }
        UUID id = p.getUniqueId();
        List<Entry> list = chapterMap().get(c.id());

        int perList = 11;
        int listPages = Math.max(1, (list.size() + perList - 1) / perList);
        Map<Entry, List<List<Component>>> entryPages = new LinkedHashMap<>();
        Map<Entry, Integer> entryStart = new HashMap<>();
        int pageNo = listPages + 1;
        for (Entry e : list) {
            List<List<Component>> ep = entryLines(id, e);
            entryPages.put(e, ep);
            entryStart.put(e, pageNo);
            pageNo += ep.size();
        }

        List<Component> pages = new ArrayList<>();
        for (int lp = 0; lp < listPages; lp++) {
            List<Component> lines = new ArrayList<>();
            lines.add(icon(c.icon(), false).append(Component.text(" " + c.title() + (listPages > 1 ? " " + (lp + 1) + "/" + listPages : ""),
                    TITLE, TextDecoration.BOLD)));
            lines.add(BLANK);
            for (int i = lp * perList; i < Math.min(list.size(), (lp + 1) * perList); i++) lines.add(row(id, list.get(i), entryStart.get(list.get(i))));
            while (lines.size() < PAGE_LINES - 1) lines.add(BLANK);
            lines.add(contentsLink());
            pages.add(page(lines));
        }
        for (Entry e : list) {
            for (List<Component> lines : entryPages.get(e)) {
                List<Component> full = new ArrayList<>(lines);
                while (full.size() < PAGE_LINES - 1) full.add(BLANK);
                full.add(link("« Back to " + fit(c.title(), 60, false), 1));
                pages.add(page(full));
            }
        }
        p.openBook(Book.book(Component.text(c.title()), Component.text("Faultline SMP"), pages));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.1f);
    }

    private Component contentsLink() {
        return Component.text("« Contents", LINK).clickEvent(ClickEvent.runCommand("/index"))
                .hoverEvent(HoverEvent.showText(Component.text("Back to the cover")));
    }

    private Component link(String text, int page) {
        return Component.text(text, LINK).clickEvent(ClickEvent.changePage(page))
                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + page)));
    }

    private Component cover(UUID id, Map<String, List<Entry>> byChapter) {
        int itemsHave = 0, itemsAll = 0, mobsHave = 0, mobsAll = 0;
        for (Entry e : entries) {
            if (e.creature) { mobsAll++; if (has(id, e)) mobsHave++; }
            else { itemsAll++; if (has(id, e)) itemsHave++; }
        }
        List<Component> lines = new ArrayList<>();
        lines.add(icon("faultline_index", true).append(Component.text("  FAULTLINE", TITLE, TextDecoration.BOLD)));
        lines.add(Component.text("       INDEX", TITLE, TextDecoration.BOLD));
        lines.add(BLANK);
        lines.add(progress("Items", itemsHave, itemsAll));
        lines.add(progress("Creatures", mobsHave, mobsAll));
        lines.add(BLANK);
        lines.add(Component.text("Chapters", INK, TextDecoration.UNDERLINED));
        for (Map.Entry<String, List<Entry>> ch : byChapter.entrySet()) {
            Chapter c = chapters.stream().filter(x -> x.id().equals(ch.getKey())).findFirst().orElseThrow();
            long have = ch.getValue().stream().filter(e -> has(id, e)).count();
            boolean done = have == ch.getValue().size();
            lines.add(icon(c.icon(), false)
                    .append(Component.text(" " + fit(c.title(), 70, false), done ? FOUND : LINK))
                    .append(Component.text(" " + have + "/" + ch.getValue().size(), FADED))
                    .clickEvent(ClickEvent.runCommand("/index open " + c.id()))
                    .hoverEvent(HoverEvent.showText(Component.text("Open " + c.title()))));
        }
        return page(lines);
    }

    private Component progress(String label, int have, int all) {
        boolean done = have == all;
        return icon(done ? "check" : "box", false)
                .append(Component.text(" " + label + ": ", INK))
                .append(Component.text(have + "/" + all, done ? FOUND : TITLE, TextDecoration.BOLD));
    }

    /** One line in a chapter list: [check] [icon] Name (click to open). Undefeated creatures are ???. */
    private Component row(UUID id, Entry e, int page) {
        boolean got = has(id, e);
        boolean hidden = e.creature && !got;
        String name = hidden ? "???" : fit(e.name, 86, false);
        Component hover = hidden
                ? Component.text("Not defeated yet", NamedTextColor.GRAY)
                : Component.text(e.name, NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.newline())
                        .append(Component.text(got ? ("friends".equals(e.chapter) ? "Met" : e.creature ? "Defeated" : "Collected") : "Not found yet",
                                got ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        return icon(got ? "check" : "box", false)
                .append(Component.text(" "))
                .append(icon(hidden ? "unknown" : e.icon, false))
                .append(Component.text(" " + name, got ? FOUND : (hidden ? FADED : INK)))
                .clickEvent(ClickEvent.changePage(page))
                .hoverEvent(HoverEvent.showText(hover));
    }

    /** An entry's content, split into pages of (PAGE_LINES - 1) lines (the last line is the Back link). */
    private List<List<Component>> entryLines(UUID id, Entry e) {
        boolean got = has(id, e);
        List<Component> lines = new ArrayList<>();
        String indent = "     "; // clears the 16px portrait on the second line

        if (e.creature && !got) {
            lines.add(icon("unknown", true).append(Component.text("  ???", FADED, TextDecoration.BOLD)));
            lines.add(Component.text(indent).append(icon("box", false)).append(Component.text(" Not defeated", FADED)));
            lines.add(BLANK);
            for (String l : wrap("Defeat one to fill in this page with what it does and what it drops.", PAGE_WIDTH, false)) {
                lines.add(Component.text(l, FADED, TextDecoration.ITALIC));
            }
            return List.of(lines);
        }

        List<String> nameLines = wrap(e.name, PAGE_WIDTH - 22, true);
        lines.add(icon(e.icon, true).append(Component.text("  " + nameLines.get(0), INK, TextDecoration.BOLD)));
        if (nameLines.size() > 1) lines.add(Component.text(indent + nameLines.get(1), INK, TextDecoration.BOLD));
        String status;
        if (e.creature) {
            int n = kills.getOrDefault(id, Map.of()).getOrDefault(e.id, 0);
            status = "friends".equals(e.chapter) ? " Met" : " Defeated" + (n > 1 ? " x" + n : "");
        } else {
            status = got ? " Collected" : " Not found yet";
        }
        lines.add(Component.text(indent).append(icon(got ? "check" : "box", false)).append(Component.text(status, got ? FOUND : FADED)));
        lines.add(BLANK);

        if (e.creature) {
            section(lines, "Where to find it", HEAD_HOW, e.where);
            section(lines, "What it does", HEAD_DOES, e.does);
            lines.add(Component.text("Drops", HEAD_DROPS, TextDecoration.BOLD));
            for (String d : e.drops) {
                String[] parts = d.split("\\|");
                String chance = parts.length > 1 ? parts[1] : "";
                List<String> wrapped = wrap("• " + parts[0], PAGE_WIDTH - width(" " + chance, true), false);
                for (int i = 0; i < wrapped.size(); i++) {
                    Component line = Component.text(wrapped.get(i), INK);
                    if (i == wrapped.size() - 1 && !chance.isEmpty() && !chance.equals("-")) {
                        line = line.append(Component.text(" " + chance, HEAD_DROPS, TextDecoration.BOLD));
                    }
                    lines.add(line);
                }
            }
        } else {
            section(lines, "How to get it", HEAD_HOW, e.how);
            section(lines, "What it does", HEAD_DOES, e.does);
        }

        // split into pages: the first page fits PAGE_LINES - 1 lines (the last line is the Back link);
        // continuation pages spend one of those on a "(cont.)" header
        int per = PAGE_LINES - 1;
        List<List<Component>> pages = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            boolean first = pages.isEmpty();
            int room = first ? per : per - 1;
            List<Component> chunk = new ArrayList<>();
            if (!first) chunk.add(Component.text(e.name + " (cont.)", FADED, TextDecoration.ITALIC));
            chunk.addAll(lines.subList(i, Math.min(lines.size(), i + room)));
            pages.add(chunk);
            i += room;
        }
        return pages;
    }

    private void section(List<Component> lines, String heading, TextColor color, String text) {
        if (text == null || text.isBlank()) return;
        lines.add(Component.text(heading, color, TextDecoration.BOLD));
        for (String l : wrap(text, PAGE_WIDTH, false)) lines.add(Component.text(l, INK));
        lines.add(BLANK);
    }

    // ===================== COMMAND =====================

    private class IndexCommand implements CommandExecutor {
        /**
         * Any error is written to the console in full AND shown in chat (just the cause and where it happened), instead of
         * Minecraft's bare "An unexpected error occurred", so it can be reported from a screenshot.
         */
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            try {
                return run(sender, args);
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.SEVERE, "/index " + String.join(" ", args) + " failed for " + sender.getName(), t);
                StackTraceElement where = null;
                for (StackTraceElement el : t.getStackTrace()) if (el.getClassName().startsWith("net.faultlinesmp")) { where = el; break; }
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                sender.sendMessage(ChatColor.RED + "The Index hit an error: " + ChatColor.GRAY + root.getClass().getSimpleName() + ": " + root.getMessage());
                if (where != null) sender.sendMessage(ChatColor.DARK_GRAY + "at " + where.getMethodName() + " (line " + where.getLineNumber() + ")"
                        + (root != t ? ", " + t.getClass().getSimpleName() : ""));
                return true;
            }
        }

        private boolean run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                if (sender instanceof Player p) open(p);
                else sender.sendMessage("Only players can open the Index.");
                return true;
            }
            if (args[0].equalsIgnoreCase("open")) {
                if (sender instanceof Player p) openChapter(p, args.length > 1 ? args[1] : "");
                return true;
            }
            if (!sender.hasPermission("faultlineindex.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage(ChatColor.YELLOW + "/index | /index give [player] | /index reset [player]");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "give" -> {
                    target.getInventory().addItem(indexBook()).values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                    sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " a Faultline Index.");
                }
                case "reset" -> {
                    found.remove(target.getUniqueId());
                    kills.remove(target.getUniqueId());
                    dirty = true;
                    sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s Index progress.");
                }
                case "discover" -> { // other plugins unlock entries this way (e.g. meeting the Skeleton Wanderer)
                    String want = args.length > 2 ? args[2].toLowerCase() : "";
                    Entry e = entries.stream().filter(x -> x.id.equalsIgnoreCase(want)).findFirst().orElse(null);
                    if (e == null) { sender.sendMessage(ChatColor.RED + "No Index entry called " + want + "."); return true; }
                    unlock(target, e, e.creature);
                    dirty = true;
                }
                default -> sender.sendMessage(ChatColor.YELLOW + "/index | /index give [player] | /index reset [player] | /index discover <player> <entry>");
            }
            return true;
        }
    }
}
