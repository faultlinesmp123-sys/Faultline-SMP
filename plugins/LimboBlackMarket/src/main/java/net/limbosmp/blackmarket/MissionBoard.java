package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * 2.0.0 THE MISSION BOARD: players post jobs for each other. {@code /missions post <item> <amount>} puts the stack in
 * your main hand up as the reward (held by the board) and asks for that many plain items of that kind. Anyone opening
 * the board ({@code /missions}, or the sign in the Quest Book menu) who carries what's asked clicks the job: the items
 * go to the poster (straight into their inventory if online, else kept until {@code /missions claim} / their next join),
 * the reward goes to them. Your own job clicked = cancelled, reward back. Jobs run out after {@code missions.expire-days}
 * (the reward goes back). Only plain items count as delivery (no custom name, lore or plugin data), so nobody can hand
 * in a renamed fake. Data: {@code missions.yml} through SafeYaml.
 */
public final class MissionBoard implements Listener, TabExecutor {

    static final class Mission {
        String id; UUID poster; String posterName; Material want; int amount; ItemStack reward; long posted, expires;
    }

    static final class BoardHolder implements InventoryHolder {
        final int page; final Map<Integer, String> slots = new HashMap<>(); Inventory inv;
        BoardHolder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int PER_PAGE = 45, PREV = 45, INFO = 49, NEXT = 53;

    private final LimboBlackMarket plugin;
    private final File file;
    private final LinkedHashMap<String, Mission> missions = new LinkedHashMap<>();
    /** What players are owed (delivered items, returned rewards) while they were offline or full. */
    private final Map<UUID, List<ItemStack>> owed = new HashMap<>();
    private int nextId = 1;

    public MissionBoard(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "missions.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20 * 60, 20 * 60 * 5);
    }

    int maxPerPlayer() { return Math.max(1, plugin.getConfig().getInt("missions.max-per-player", 5)); }
    long expireMs() { return (long) (Math.max(1, plugin.getConfig().getDouble("missions.expire-days", 7)) * 86_400_000L); }
    boolean enabled() { return plugin.getConfig().getBoolean("missions.enabled", true); }

    // ---------- data ----------

    private void load() {
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection ms = y.getConfigurationSection("missions");
        if (ms != null) for (String id : ms.getKeys(false)) {
            try {
                ConfigurationSection s = ms.getConfigurationSection(id);
                Mission m = new Mission();
                m.id = id;
                m.poster = UUID.fromString(s.getString("poster"));
                m.posterName = s.getString("poster-name", "?");
                m.want = Material.matchMaterial(s.getString("want", ""));
                m.amount = s.getInt("amount");
                m.reward = s.getItemStack("reward");
                m.posted = s.getLong("posted");
                m.expires = s.getLong("expires");
                if (m.want == null || m.reward == null || m.amount <= 0) {
                    if (m.reward != null) owe(m.poster, m.reward);
                    continue;
                }
                missions.put(id, m);
            } catch (RuntimeException e) { plugin.getLogger().warning("[Missions] skipped a broken mission " + id + ": " + e); }
        }
        ConfigurationSection os = y.getConfigurationSection("owed");
        if (os != null) for (String u : os.getKeys(false)) {
            try {
                List<ItemStack> list = new ArrayList<>();
                for (Object o : os.getList(u, List.of())) if (o instanceof ItemStack it) list.add(it);
                if (!list.isEmpty()) owed.put(UUID.fromString(u), list);
            } catch (RuntimeException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Mission m : missions.values()) {
            String p = "missions." + m.id + ".";
            y.set(p + "poster", m.poster.toString());
            y.set(p + "poster-name", m.posterName);
            y.set(p + "want", m.want.name());
            y.set(p + "amount", m.amount);
            y.set(p + "reward", m.reward);
            y.set(p + "posted", m.posted);
            y.set(p + "expires", m.expires);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : owed.entrySet()) if (!e.getValue().isEmpty()) y.set("owed." + e.getKey(), e.getValue());
        try { SafeYaml.save(plugin, y, file); } catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Couldn't save missions.yml", e); }
    }

    /** Board file and the player's own file together, so a crash can't leave the item in both places (or neither). */
    private void saveWith(Player... players) {
        save();
        for (Player p : players) if (p != null && p.isOnline()) try { p.saveData(); } catch (Throwable ignored) { }
    }

    private void owe(UUID who, ItemStack it) {
        if (it == null || it.getType().isAir() || it.getAmount() <= 0) return;
        owed.computeIfAbsent(who, k -> new ArrayList<>()).add(it);
    }

    /** Hands an item to a player now if they're online and have room, else it waits in their owed list. */
    private void deliver(UUID who, ItemStack it) {
        Player p = Bukkit.getPlayer(who);
        if (p == null) { owe(who, it); return; }
        for (ItemStack left : p.getInventory().addItem(it).values()) owe(who, left);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Mission> it = missions.values().iterator(); it.hasNext(); ) {
            Mission m = it.next();
            if (m.expires > now) continue;
            it.remove();
            deliver(m.poster, m.reward);
            Player p = Bukkit.getPlayer(m.poster);
            if (p != null) p.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GRAY + "Nobody took your job for "
                    + m.amount + " " + nice(m.want) + ". Your reward is back.");
            changed = true;
        }
        if (changed) save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            List<ItemStack> list = owed.get(p.getUniqueId());
            if (list != null && !list.isEmpty())
                p.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.YELLOW + "You have " + list.size()
                        + " thing(s) waiting from the Mission Board. " + ChatColor.WHITE + "/missions claim");
        }, 60);
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!enabled()) { p.sendMessage(ChatColor.RED + "The Mission Board is closed."); return true; }
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open", "board" -> open(p, 0);
            case "post" -> post(p, args);
            case "claim" -> claim(p);
            case "mine", "list" -> mine(p);
            case "cancel" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "/missions cancel <id>  (see /missions mine)"); return true; }
                cancel(p, args[1].replace("#", ""));
            }
            default -> p.sendMessage(ChatColor.GOLD + "Mission Board: " + ChatColor.GRAY
                    + "/missions, /missions post <item> <amount> (reward = the stack in your hand), /missions mine, /missions cancel <id>, /missions claim");
        }
        return true;
    }

    private void post(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage(ChatColor.RED + "/missions post <item> <amount>  - hold the reward in your main hand"); return; }
        Material want = Material.matchMaterial(args[1]);
        if (want == null || !want.isItem() || want.isAir()) { p.sendMessage(ChatColor.RED + "Unknown item: " + args[1]); return; }
        int amount;
        try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Amount must be a number."); return; }
        if (amount < 1 || amount > 36 * want.getMaxStackSize()) { p.sendMessage(ChatColor.RED + "Amount must be 1-" + 36 * want.getMaxStackSize() + "."); return; }
        long mineCount = missions.values().stream().filter(m -> m.poster.equals(p.getUniqueId())).count();
        if (mineCount >= maxPerPlayer()) { p.sendMessage(ChatColor.RED + "You already have " + mineCount + " jobs up (max " + maxPerPlayer() + ")."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) { p.sendMessage(ChatColor.RED + "Hold the reward in your main hand."); return; }
        if (hand.getType() == want && plain(hand)) { p.sendMessage(ChatColor.RED + "The reward can't be the same thing you're asking for."); return; }
        Mission m = new Mission();
        m.id = String.valueOf(nextId++);
        m.poster = p.getUniqueId();
        m.posterName = p.getName();
        m.want = want;
        m.amount = amount;
        m.reward = hand.clone();
        m.posted = System.currentTimeMillis();
        m.expires = m.posted + expireMs();
        p.getInventory().setItemInMainHand(null);
        missions.put(m.id, m);
        saveWith(p);
        p.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GREEN + "Posted job #" + m.id + ": bring " + amount + " " + nice(want)
                + " for " + describe(m.reward) + ".");
        p.playSound(p, Sound.ITEM_BOOK_PUT, 1f, 1f);
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p)
            o.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GRAY + p.getName() + " wants " + amount + " " + nice(want)
                    + " for " + describe(m.reward) + ChatColor.GRAY + ". " + ChatColor.WHITE + "/missions");
        plugin.getLogger().info("[Missions] " + p.getName() + " posted #" + m.id + ": " + amount + " " + want + " for " + describe(m.reward));
    }

    private void claim(Player p) {
        List<ItemStack> list = owed.remove(p.getUniqueId());
        if (list == null || list.isEmpty()) { p.sendMessage(ChatColor.GRAY + "Nothing waiting for you."); return; }
        int given = 0;
        for (ItemStack it : list) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (left.isEmpty()) given++;
            for (ItemStack l : left.values()) owe(p.getUniqueId(), l);
        }
        saveWith(p);
        if (owed.containsKey(p.getUniqueId())) p.sendMessage(ChatColor.YELLOW + "Your inventory filled up: the rest is still waiting (/missions claim).");
        else p.sendMessage(ChatColor.GREEN + "Claimed everything (" + given + ").");
    }

    private void mine(Player p) {
        boolean any = false;
        for (Mission m : missions.values()) if (m.poster.equals(p.getUniqueId())) {
            any = true;
            p.sendMessage(ChatColor.GOLD + "#" + m.id + ChatColor.GRAY + " " + m.amount + " " + nice(m.want) + " for "
                    + describe(m.reward) + ChatColor.DARK_GRAY + " (ends in " + QuestService.formatDuration(m.expires - System.currentTimeMillis()) + ")");
        }
        if (!any) p.sendMessage(ChatColor.GRAY + "You have no jobs up. /missions post <item> <amount>");
    }

    private void cancel(Player p, String id) {
        Mission m = missions.get(id);
        if (m == null || (!m.poster.equals(p.getUniqueId()) && !p.hasPermission("limboblackmarket.missions.admin"))) {
            p.sendMessage(ChatColor.RED + "No job #" + id + " of yours."); return;
        }
        missions.remove(id);
        deliver(m.poster, m.reward);
        saveWith(p, Bukkit.getPlayer(m.poster));
        p.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GRAY + "Job #" + id + " taken down, reward returned.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("post", "mine", "cancel", "claim"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("post")) {
            List<String> out = new ArrayList<>();
            String a = args[1].toLowerCase(Locale.ROOT);
            for (Material m : Material.values()) if (!m.isLegacy() && m.isItem() && !m.isAir() && m.name().toLowerCase(Locale.ROOT).startsWith(a)) {
                out.add(m.name().toLowerCase(Locale.ROOT));
                if (out.size() > 40) break;
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player p) {
            List<String> out = new ArrayList<>();
            for (Mission m : missions.values()) if (m.poster.equals(p.getUniqueId())) out.add(m.id);
            return out;
        }
        return List.of();
    }

    private static List<String> filter(List<String> in, String a) {
        List<String> out = new ArrayList<>();
        for (String s : in) if (s.startsWith(a.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }

    // ---------- the board ----------

    public void open(Player p, int page) {
        List<Mission> list = new ArrayList<>(missions.values());
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        BoardHolder h = new BoardHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Mission Board");
        h.inv = inv;
        ItemStack filler = QuestMenuHolder.item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < list.size(); i++) {
            Mission m = list.get(page * PER_PAGE + i);
            boolean own = m.poster.equals(p.getUniqueId());
            int have = count(p.getInventory(), m.want);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Bring: " + ChatColor.WHITE + m.amount + " " + nice(m.want));
            lore.add(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + describe(m.reward));
            lore.add(ChatColor.GRAY + "Posted by " + ChatColor.YELLOW + m.posterName);
            lore.add(ChatColor.DARK_GRAY + "Ends in " + QuestService.formatDuration(m.expires - System.currentTimeMillis()) + "  (#" + m.id + ")");
            lore.add("");
            if (own) lore.add(ChatColor.RED + "Your job. Click to take it down.");
            else if (have >= m.amount) lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to hand in");
            else lore.add(ChatColor.GRAY + "You have " + have + "/" + m.amount + " (plain items only)");
            ItemStack icon = QuestMenuHolder.item(m.want.isItem() ? m.want : Material.PAPER,
                    ChatColor.GOLD + "" + ChatColor.BOLD + m.amount + "x " + nice(m.want), lore);
            icon.setAmount(Math.max(1, Math.min(m.want.getMaxStackSize(), m.amount)));
            inv.setItem(i, icon);
            h.slots.put(i, m.id);
        }
        if (page > 0) inv.setItem(PREV, QuestMenuHolder.item(Material.ARROW, ChatColor.YELLOW + "Previous page", List.of()));
        if (page < pages - 1) inv.setItem(NEXT, QuestMenuHolder.item(Material.ARROW, ChatColor.YELLOW + "Next page", List.of()));
        inv.setItem(INFO, QuestMenuHolder.item(Material.OAK_HANGING_SIGN, ChatColor.GOLD + "" + ChatColor.BOLD + "Post a job",
                List.of(ChatColor.GRAY + "Hold the reward in your hand and type", ChatColor.WHITE + "/missions post <item> <amount>",
                        "", ChatColor.GRAY + "" + list.size() + " job(s) up, page " + (page + 1) + "/" + pages)));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof BoardHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot == PREV || slot == NEXT) {
            ItemStack it = e.getInventory().getItem(slot);
            if (it == null || it.getType() != Material.ARROW) return;
            int to = h.page + (slot == NEXT ? 1 : -1);
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) open(p, to); });
            return;
        }
        String id = h.slots.get(slot);
        if (id == null) return;
        Mission m = missions.get(id);
        if (m == null) { p.sendMessage(ChatColor.RED + "That job is gone."); refresh(p, h.page); return; }
        if (m.poster.equals(p.getUniqueId())) { cancel(p, id); refresh(p, h.page); return; }
        if (count(p.getInventory(), m.want) < m.amount) {
            p.sendMessage(ChatColor.RED + "You need " + m.amount + " plain " + nice(m.want) + ".");
            p.playSound(p, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        // take them, then pay out
        missions.remove(id);
        // AUDIT: the poster gets the very stacks that were handed in (it used to be brand-new items: a worn-out tool
        // handed in came out fresh, and an enchanted one lost its enchants)
        for (ItemStack given : take(p.getInventory(), m.want, m.amount)) deliver(m.poster, given);
        deliver(p.getUniqueId(), m.reward);
        saveWith(p, Bukkit.getPlayer(m.poster));
        p.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GREEN + "Job done! You got " + describe(m.reward) + ".");
        p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        Player poster = Bukkit.getPlayer(m.poster);
        if (poster != null) poster.sendMessage(ChatColor.GOLD + "[Missions] " + ChatColor.GREEN + p.getName() + " brought your "
                + m.amount + " " + nice(m.want) + ".");
        if (Bukkit.getPluginCommand("index") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " missions_done 1");
        plugin.getLogger().info("[Missions] " + p.getName() + " did #" + id + " for " + m.posterName);
        refresh(p, h.page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof BoardHolder) e.setCancelled(true);
    }

    private void refresh(Player p, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) open(p, page); });
    }

    // ---------- items ----------

    /**
     * What a delivery may be made of: no custom name, lore or model, no plugin data (the Index's dupe serial is fine: every
     * tool, totem and shulker in a survival inventory carries one) and not worn (nobody gets paid for a nearly broken tool).
     */
    static boolean plain(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return true;
        ItemMeta m = it.getItemMeta();
        if (m.hasDisplayName() || m.hasLore() || m.hasItemModel() || m.hasCustomModelData()) return false;
        if (m instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage() && d.getDamage() > 0) return false;
        for (org.bukkit.NamespacedKey k : it.getPersistentDataContainer().getKeys())
            if (!(k.getNamespace().equals("faultlineindex") && k.getKey().equals("uid"))) return false;
        return true;
    }

    static int count(PlayerInventory inv, Material want) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && it.getType() == want && plain(it)) n += it.getAmount();
        return n;
    }

    /** Takes that many out of the inventory and returns the stacks taken. */
    static List<ItemStack> take(PlayerInventory inv, Material want, int amount) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length && amount > 0; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType() != want || !plain(it)) continue;
            int t = Math.min(amount, it.getAmount());
            amount -= t;
            ItemStack got = it.clone(); got.setAmount(t); out.add(got);
            if (t >= it.getAmount()) c[i] = null; else it.setAmount(it.getAmount() - t);
        }
        inv.setStorageContents(c);
        return out;
    }

    static String nice(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) { if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return b.toString();
    }

    static String describe(ItemStack it) {
        String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(it.getItemMeta().getDisplayName()) : nice(it.getType());
        return it.getAmount() + "x " + name;
    }

    /** For other code that wants the count of posted jobs. */
    int size() { return missions.size(); }

}
