package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * THE MUSEUM (the "wild" update), kept by the Index:
 *   (BOSS TROPHIES were removed in 1.8.0 with the land bosses; FaultlineItems' OldGear takes old ones away.)
 *   MUSIC DISCS     bosses with their own theme drop it as a disc sometimes (FaultlineItems /givedisc; jukeboxes play it).
 *   HALL OF FIRSTS  /museum: every boss and every item in the Index, with who got there first on the whole server and
 *                   when. Firsts are announced (and posted to Discord when the bridge is on).
 * Saved in museum.yml (firsts).
 */
final class Museum implements Listener {

    /** Boss entry -> its music disc(s) (FaultlineItems Gear.DISCS). */
    static final Map<String, List<String>> DISCS = Map.of(
            "the_abyssal_kraken", List.of("kraken"), "diamond_jacob", List.of("jacob1", "jacob2"),
            "the_skeleton_captain", List.of("pirates"), "ghost_captain", List.of("pirates"));

    private final FaultlineIndex index;
    private final File file;
    final Map<String, String> firsts = new HashMap<>(); // entry id -> "player|epoch millis"
    private final Random random = new Random();
    private boolean dirty;
    private final Map<String, Long> recent = new HashMap<>();

    Museum(FaultlineIndex index) {
        this.index = index;
        file = new File(index.getDataFolder(), "museum.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(index, () -> { if (dirty) save(); }, 1200L, 1200L);
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = SafeYaml.load(index, file).yaml;
        var f = y.getConfigurationSection("firsts");
        if (f != null) for (String k : f.getKeys(false)) firsts.put(k, f.getString(k));
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : firsts.entrySet()) y.set("firsts." + e.getKey(), e.getValue());
        SafeYaml.write(index, file, y.saveToString()); dirty = false;
    }

    // =====================================================================================================
    //  hooks from the Index
    // =====================================================================================================
    /** Someone found an entry for the first time (for them): is it a first for the whole server? */
    void found(Player p, FaultlineIndex.Entry e) {
        if (firsts.containsKey(e.id)) return;
        firsts.put(e.id, p.getName() + "|" + System.currentTimeMillis());
        dirty = true;
        if (!index.getConfig().getBoolean("museum.announce-firsts", true)) return;
        String what = e.creature ? (e.chapter.equals("bosses") ? "the first to defeat " : "the first to defeat a ") : "the first to find ";
        String line = ChatColor.GOLD + "✦ " + ChatColor.YELLOW + p.getName() + " is " + what + ChatColor.WHITE + ChatColor.BOLD + e.name + ChatColor.YELLOW + "! " + ChatColor.GRAY + "(/museum)";
        if (e.chapter.equals("bosses") || index.getConfig().getBoolean("museum.announce-item-firsts", false) || e.creature) Bukkit.broadcastMessage(line);
    }

    /** A player was credited with defeating a boss (its disc / enchant book rolls). */
    void bossDefeated(Player p, FaultlineIndex.Entry e) {
        if (!e.chapter.equals("bosses")) return;
        // the kill credit and the boss's own "index discover" both land here: one roll per defeat
        Long last = recent.put(p.getUniqueId() + e.id, System.currentTimeMillis());
        if (last != null && System.currentTimeMillis() - last < 10000) return;
        if (index.daily != null) index.daily.boss(p, e);
        List<String> discs = DISCS.get(e.id);
        if (discs != null && random.nextDouble() < index.getConfig().getDouble("museum.disc-chance", 0.2)
                && Bukkit.getPluginManager().getPlugin("FaultlineItems") != null) {
            String d = discs.get(random.nextInt(discs.size()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givedisc " + d + " " + p.getName());
        }
        // boss enchant books (FaultlineItems 1.2.17 decides which and how likely: enchants.boss-drops)
        if (Bukkit.getPluginManager().getPlugin("FaultlineItems") != null && Bukkit.getPluginCommand("fenchant") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fenchant bossdrop " + p.getName() + " " + e.id);
    }

    // =====================================================================================================
    //  /museum: the Hall of Firsts
    // =====================================================================================================
    static final class Hall implements InventoryHolder {
        final int page; final boolean bosses; Inventory inv;
        Hall(int page, boolean bosses) { this.page = page; this.bosses = bosses; }
        @Override public Inventory getInventory() { return inv; }
    }

    void open(Player p, boolean bosses, int page) {
        List<FaultlineIndex.Entry> list = new ArrayList<>();
        for (FaultlineIndex.Entry e : index.entries()) if (bosses == e.chapter.equals("bosses") && (bosses || !e.creature)) list.add(e);
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(pages - 1, page));
        Hall h = new Hall(page, bosses);
        Inventory inv = Bukkit.createInventory(h, 54, (bosses ? "Hall of Firsts: Bosses" : "Hall of Firsts: Items") + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : ""));
        h.inv = inv;
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy");
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            FaultlineIndex.Entry e = list.get(page * 45 + i);
            String first = firsts.get(e.id);
            ItemStack icon;
            icon = new ItemStack(first != null ? (bosses ? Material.WITHER_SKELETON_SKULL : Material.ITEM_FRAME) : Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = icon.getItemMeta();
            m.setDisplayName((first != null ? ChatColor.GOLD : ChatColor.GRAY) + e.name);
            List<String> lore = new ArrayList<>();
            if (first != null) {
                String[] parts = first.split("\\|");
                String when = parts.length > 1 ? fmt.format(new Date(Long.parseLong(parts[1]))) : "";
                lore.add(ChatColor.YELLOW + (bosses || e.creature ? "First defeated by " : "First found by ") + ChatColor.WHITE + parts[0]);
                if (!when.isEmpty()) lore.add(ChatColor.GRAY + when);
            } else lore.add(ChatColor.DARK_GRAY + (bosses ? "Nobody has defeated it yet." : "Nobody has found one yet."));
            m.setLore(lore);
            m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            icon.setItemMeta(m);
            inv.setItem(i, icon);
        }
        inv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Previous page"));
        inv.setItem(49, button(bosses ? Material.ITEM_FRAME : Material.WITHER_SKELETON_SKULL, ChatColor.GOLD + (bosses ? "Items" : "Bosses")));
        inv.setItem(53, button(Material.ARROW, ChatColor.YELLOW + "Next page"));
        long done = list.stream().filter(e -> firsts.containsKey(e.id)).count();
        inv.setItem(47, button(Material.GOLD_INGOT, ChatColor.GOLD + "" + done + " / " + list.size() + (bosses ? " bosses defeated" : " items found") + " on the server"));
        p.openInventory(inv);
    }

    static ItemStack button(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name);
        it.setItemMeta(im);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Hall h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() >= 54) return;
        switch (e.getRawSlot()) {
            case 45 -> open(p, h.bosses, h.page - 1);
            case 53 -> open(p, h.bosses, h.page + 1);
            case 49 -> open(p, !h.bosses, 0);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Hall) e.setCancelled(true); }
}
