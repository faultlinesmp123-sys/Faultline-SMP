package net.faultlinesmp.items;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE WARDROBE (1.2.46): /accessories, and /cosmetics on Java. One 6-row menu with a custom background (tools/gui_assets.py:
 * font faultline:gui, one glyph per tab, drawn through the title) laid out like this (chest grid, col c / row r):
 *
 *   col 0      tabs: Accessories, Hat, Neck, Back, Body; row 5 = take every cosmetic off
 *   col 1      the open tab's list: your 3 ACCESSORY slots (put them in / take them out, as before), or that slot's
 *              cosmetics (owned first, then locked) from FaultlineCosmetics, click to wear / take off
 *   col 2      scroll up (row 0) / down (row 5) through a long cosmetic list
 *   col 3      your ARMOUR: helmet, chestplate, leggings, boots: take pieces off and put them on right here (the
 *              Abyssal Diving Suit fits the chestplate slot too)
 *   cols 4-7   the black preview: your outfit from the front (col 5) and the back (col 7), as items, since a chest menu
 *              can't draw a player
 *   col 8      the eye (hide / show everything you wear without taking it off), the seasonal shop, help
 *
 * Everything else holds an invisible filler (model faultline:gui/blank, no tooltip). Bedrock can't draw the background:
 * it gets glass panes, a plain title and pane placeholders in the empty accessory slots. FaultlineCosmetics is reached by
 * reflection (its public static wardrobe* methods), so either plugin works alone.
 */
final class Wardrobe implements Listener {

    static final String[] TABS = {"accessories", "hat", "neck", "back", "body"};
    static final String[] TAB_NAMES = {"Accessories", "Hat", "Neck", "Back", "Body"};
    static final int[] TAB_AT = {0, 9, 18, 27, 36};
    static final int TAKE_ALL = 45, UP = 2, DOWN = 47, EYE = 8, SHOP = 17, HELP = 53;
    static final int[] LIST = {1, 10, 19, 28, 37, 46};
    static final int[] ARMOR = {3, 12, 21, 30};
    static final EquipmentSlot[] ARMOR_SLOT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    static final String[] ARMOR_NAME = {"Helmet", "Chestplate", "Leggings", "Boots"};
    static final int[] FRONT = {5, 14, 23, 32}, BACK = {7, 16, 25, 34};
    static final int NECK_FRONT = 13;

    /** The open menu. tab 0 = accessories: then its LIST rows 0-2 hold the real accessory items (AccessoryManager reads them). */
    static final class Menu extends FaultlineItems.AccessoryGuiHolder {
        final UUID owner;
        int tab, scroll;
        final Map<Integer, String> ids = new HashMap<>();
        Menu(UUID owner, int tab) { this.owner = owner; this.tab = tab; }
        @Override boolean holdsAccessories() { return tab == 0; }
    }

    private final FaultlineItems plugin;

    Wardrobe(FaultlineItems plugin) { this.plugin = plugin; }

    // ============================================================================================ opening
    void open(Player p, int tab) {
        tab = Math.max(0, Math.min(TABS.length - 1, tab));
        Menu m = new Menu(p.getUniqueId(), tab);
        boolean bedrock = Edition.bedrock(p);
        Component title = bedrock || !plugin.getConfig().getBoolean("wardrobe.custom-background", true)
                ? Component.text("Wardrobe: " + TAB_NAMES[tab], NamedTextColor.DARK_GRAY)
                : Component.text("" + (char) (0xF810 + tab)).font(Key.key("faultline", "gui")).color(NamedTextColor.WHITE);
        Inventory inv = Bukkit.createInventory(m, 54, title);
        m.inventory = inv;
        if (tab == 0) {
            ItemStack[] worn = plugin.getAccessoryManager().getSlots(p.getUniqueId());
            for (int i = 0; i < FaultlineItems.AccessoryManager.SLOT_COUNT; i++) inv.setItem(LIST[i], i < worn.length && worn[i] != null ? worn[i] : emptyAccessory(p));
        }
        fill(p, m);
        p.openInventory(inv);
    }

    static int tabOf(String arg) {
        if (arg == null) return 0;
        String a = arg.toLowerCase(java.util.Locale.ROOT);
        if (a.equals("cosmetics") || a.equals("cosmetic")) return 1;
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(a)) return i;
        return 0;
    }

    /** (Re)draws everything except the accessory slots themselves. */
    void fill(Player p, Menu m) {
        Inventory inv = m.inventory;
        boolean bedrock = Edition.bedrock(p);
        ItemStack blank = blank(bedrock);
        for (int i = 0; i < 54; i++) {
            if (m.tab == 0 && (i == LIST[0] || i == LIST[1] || i == LIST[2])) continue;
            inv.setItem(i, blank);
        }
        boolean cos = Cosmetics.present();
        for (int t = 0; t < TABS.length; t++)
            inv.setItem(TAB_AT[t], button(bedrock ? (t == m.tab ? Material.LIME_STAINED_GLASS_PANE : tabMaterial(t)) : Material.PAPER, bedrock,
                    (t == m.tab ? ChatColor.GREEN + "▶ " : ChatColor.YELLOW) + TAB_NAMES[t] + (t == 0 ? "" : " cosmetics"),
                    t == 0 ? ChatColor.GRAY + "Your 3 accessory slots." : cos ? ChatColor.GRAY + "Cosmetics show over your armour" : ChatColor.RED + "FaultlineCosmetics isn't installed.",
                    t == 0 ? "" : ChatColor.GRAY + "and don't change your stats."));
        m.ids.clear();
        if (m.tab == 0) {
            for (int r = 3; r < LIST.length; r++) inv.setItem(LIST[r], blank);
        } else if (cos) {
            List<String> list = Cosmetics.list(p.getUniqueId(), TABS[m.tab]);
            m.scroll = Math.max(0, Math.min(m.scroll, Math.max(0, list.size() - LIST.length)));
            for (int r = 0; r < LIST.length; r++) {
                int n = m.scroll + r;
                if (n >= list.size()) { inv.setItem(LIST[r], bedrock ? null : blank); continue; }
                ItemStack icon = Cosmetics.icon(p.getUniqueId(), list.get(n));
                if (icon == null) continue;
                inv.setItem(LIST[r], icon);
                m.ids.put(LIST[r], list.get(n));
            }
            if (list.isEmpty()) inv.setItem(LIST[0], button(Material.GRAY_DYE, bedrock, ChatColor.GRAY + "No " + TAB_NAMES[m.tab].toLowerCase() + " cosmetics yet"));
            if (m.scroll > 0) inv.setItem(UP, button(bedrock ? Material.ARROW : Material.PAPER, bedrock, ChatColor.YELLOW + "Scroll up"));
            if (m.scroll + LIST.length < list.size()) inv.setItem(DOWN, button(bedrock ? Material.ARROW : Material.PAPER, bedrock, ChatColor.YELLOW + "Scroll down",
                    ChatColor.GRAY + "" + (list.size() - m.scroll - LIST.length) + " more"));
        }
        // the armour, live
        PlayerInventory pi = p.getInventory();
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack a = pi.getItem(ARMOR_SLOT[i]);
            inv.setItem(ARMOR[i], a != null && !a.getType().isAir() ? a.clone() : bedrock ? button(Material.GRAY_STAINED_GLASS_PANE, true,
                    ChatColor.GRAY + ARMOR_NAME[i] + ": empty", ChatColor.DARK_GRAY + "Click with a " + ARMOR_NAME[i].toLowerCase() + " to put it on.") : null);
        }
        preview(p, m, bedrock);
        // col 8
        boolean hidden = cos && Cosmetics.hidden(p.getUniqueId());
        inv.setItem(EYE, button(bedrock ? (hidden ? Material.ENDER_PEARL : Material.ENDER_EYE) : Material.PAPER, bedrock,
                hidden ? ChatColor.RED + "Cosmetics hidden" : ChatColor.GREEN + "Cosmetics shown",
                ChatColor.GRAY + "Click to " + (hidden ? "show" : "hide") + " everything you wear", ChatColor.GRAY + "(nothing is taken off)."));
        inv.setItem(SHOP, button(bedrock ? Material.EMERALD : Material.PAPER, bedrock, ChatColor.GOLD + "Seasonal Shop",
                ChatColor.GRAY + "Halloween and Winter cosmetics,", ChatColor.GRAY + "bought with that season's tokens."));
        inv.setItem(TAKE_ALL, button(bedrock ? Material.BARRIER : Material.PAPER, bedrock, ChatColor.RED + "Take every cosmetic off"));
        inv.setItem(HELP, button(bedrock ? Material.BOOK : Material.PAPER, bedrock, ChatColor.GOLD + "The Wardrobe",
                ChatColor.GRAY + "Left: your 3 accessories, and your", ChatColor.GRAY + "cosmetics by slot (the tabs).",
                ChatColor.GRAY + "Middle: your armour; put pieces on", ChatColor.GRAY + "and take them off here.",
                ChatColor.GRAY + "The black window shows your outfit", ChatColor.GRAY + "from the front and the back.",
                ChatColor.GRAY + "Accessories don't stack: one of each."));
    }

    /** The front and back views: what others see on you (a hidden or missing cosmetic falls back to the armour under it). */
    private void preview(Player p, Menu m, boolean bedrock) {
        Inventory inv = m.inventory;
        PlayerInventory pi = p.getInventory();
        boolean cos = Cosmetics.present(), hidden = cos && Cosmetics.hidden(p.getUniqueId());
        String hat = cos && !hidden ? Cosmetics.worn(p.getUniqueId(), "hat") : null;
        String body = cos && !hidden ? Cosmetics.worn(p.getUniqueId(), "body") : null;
        String neck = cos && !hidden ? Cosmetics.worn(p.getUniqueId(), "neck") : null;
        String back = cos && !hidden ? Cosmetics.worn(p.getUniqueId(), "back") : null;
        ItemStack head = hat != null ? Cosmetics.icon(p.getUniqueId(), hat) : armorOr(pi.getHelmet(), skull(p));
        ItemStack chest = body != null ? Cosmetics.icon(p.getUniqueId(), body) : armorOr(pi.getChestplate(), null);
        ItemStack legs = armorOr(pi.getLeggings(), null), boots = armorOr(pi.getBoots(), null);
        ItemStack[] front = {head, chest, legs, boots};
        ItemStack[] rear = {head, back != null ? Cosmetics.icon(p.getUniqueId(), back) : chest, legs, boots};
        for (int i = 0; i < 4; i++) {
            inv.setItem(FRONT[i], label(front[i], "Front"));
            inv.setItem(BACK[i], label(rear[i], "Back"));
        }
        if (neck != null) inv.setItem(NECK_FRONT, label(Cosmetics.icon(p.getUniqueId(), neck), "Front"));
    }

    private static ItemStack armorOr(ItemStack a, ItemStack or) { return a != null && !a.getType().isAir() ? a.clone() : or; }

    private static ItemStack label(ItemStack it, String view) {
        if (it == null) return null;
        ItemStack c = it.clone();
        ItemMeta im = c.getItemMeta();
        if (im == null) return c;
        List<String> lore = im.hasLore() ? new ArrayList<>(im.getLore()) : new ArrayList<>();
        lore.add(0, ChatColor.DARK_GRAY + "Preview (" + view + ")");
        im.setLore(lore);
        c.setItemMeta(im);
        return c;
    }

    private static ItemStack skull(Player p) {
        ItemStack s = new ItemStack(Material.PLAYER_HEAD);
        if (s.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(p);
            sm.setDisplayName(ChatColor.WHITE + p.getName());
            s.setItemMeta(sm);
        }
        return s;
    }

    private static Material tabMaterial(int t) {
        return switch (t) { case 0 -> Material.GOLD_NUGGET; case 1 -> Material.LEATHER_HELMET; case 2 -> Material.STRING; case 3 -> Material.WHITE_BANNER; default -> Material.LEATHER_CHESTPLATE; };
    }

    ItemStack emptyAccessory(Player p) {
        if (!Edition.bedrock(p)) return null; // Java: the background draws the empty slot
        return button(Material.LIME_STAINED_GLASS_PANE, true, ChatColor.GREEN + "Empty Accessory Slot", ChatColor.GRAY + "Click with an accessory to wear it.");
    }

    /** An invisible, tooltip-less filler (Java), or a pane (Bedrock). */
    private static ItemStack blank(boolean bedrock) {
        ItemStack it = new ItemStack(bedrock ? Material.BLACK_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(" ");
        if (!bedrock) { im.setItemModel(new NamespacedKey("faultline", "gui/blank")); im.setHideTooltip(true); }
        it.setItemMeta(im);
        return it;
    }

    /** A button. On Java a plain PAPER button is drawn by the background, so it gets the invisible model. */
    private static ItemStack button(Material mat, boolean bedrock, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name);
        List<String> l = new ArrayList<>();
        for (String s : lore) if (!s.isEmpty()) l.add(s);
        im.setLore(l);
        im.addItemFlags(ItemFlag.values());
        if (!bedrock && mat == Material.PAPER) im.setItemModel(new NamespacedKey("faultline", "gui/blank"));
        it.setItemMeta(im);
        return it;
    }

    // ============================================================================================ clicks
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory() != top) { // their own inventory
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; } // would vacuum the menu's items
            if (e.isShiftClick()) { e.setCancelled(true); shiftIn(p, m, e.getClickedInventory().getItem(e.getSlot()), e.getSlot()); }
            return;
        }
        e.setCancelled(true);
        int slot = e.getSlot();
        for (int t = 0; t < TAB_AT.length; t++) if (slot == TAB_AT[t]) {
            if (t != m.tab) { saveAccessories(p, m); click(p); open(p, t); }
            return;
        }
        if (m.tab == 0 && FaultlineItems.AccessoryGuiHolder.indexOf(slot) >= 0) { accessoryClick(p, m, e); refreshLater(p, m); return; }
        for (int i = 0; i < ARMOR.length; i++) if (slot == ARMOR[i]) { armorClick(p, i); refreshLater(p, m); return; }
        if (slot == UP && m.tab > 0) { m.scroll = Math.max(0, m.scroll - 3); click(p); fill(p, m); return; }
        if (slot == DOWN && m.tab > 0) { m.scroll += 3; click(p); fill(p, m); return; }
        if (slot == EYE && Cosmetics.present()) { Cosmetics.setHidden(p, !Cosmetics.hidden(p.getUniqueId())); fill(p, m); return; }
        if (slot == TAKE_ALL && Cosmetics.present()) { Cosmetics.takeOff(p, null); fill(p, m); return; }
        if (slot == SHOP && Cosmetics.present()) {
            saveAccessories(p, m);
            if (!Cosmetics.shop(p)) {
                p.sendMessage(ChatColor.GRAY + "The Seasonal Shop is closed: it opens for Halloween (Oct 1 - Nov 2) and Winter (Dec 1 - Jan 6).");
                open(p, m.tab); // the accessories were just saved out of this menu: reopen it with them back in
            }
            return;
        }
        String id = m.ids.get(slot);
        if (id != null) { Cosmetics.click(p, id); fill(p, m); }
    }

    private void click(Player p) { p.playSound(p, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f); }

    /** Accessory slots: the same rules as the old 3-slot menu (one at a time, accessories only, no copies of one worn). */
    private void accessoryClick(Player p, Menu m, InventoryClickEvent e) {
        Inventory inv = m.inventory;
        int slot = e.getSlot();
        ItemStack cursor = e.getCursor(), cur = inv.getItem(slot);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean has = FaultlineItems.AccessoryManager.isAccessory(plugin, cur);
        if (cursorEmpty) {
            if (has) { p.setItemOnCursor(cur); inv.setItem(slot, emptyAccessory(p)); }
            return;
        }
        if (!FaultlineItems.AccessoryManager.isAccessory(plugin, cursor)) { p.sendMessage(ChatColor.RED + "Only accessory items can go in these slots."); return; }
        ItemStack single = cursor.clone();
        single.setAmount(1);
        if (has && cur.isSimilar(single)) return;
        for (int other : FaultlineItems.AccessoryGuiHolder.SLOTS) {
            if (other == slot) continue;
            ItemStack o = inv.getItem(other);
            if (FaultlineItems.AccessoryManager.isAccessory(plugin, o) && FaultlineItems.AccessoryManager.same(plugin, o, single)) {
                p.sendMessage(ChatColor.RED + "You're already wearing " + (single.getItemMeta().hasDisplayName() ? single.getItemMeta().getDisplayName() : "that accessory")
                        + ChatColor.RED + ". Accessories don't stack.");
                return;
            }
        }
        ItemStack rest = null;
        if (cursor.getAmount() > 1) { rest = cursor.clone(); rest.setAmount(cursor.getAmount() - 1); }
        if (has && rest == null) p.setItemOnCursor(cur);
        else {
            p.setItemOnCursor(rest);
            if (has) giveBack(p, cur);
        }
        inv.setItem(slot, single);
        p.playSound(p, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.7f, 1.3f);
    }

    /** Armour slots: take the piece onto the cursor, or put the cursor's piece on (swapping). */
    private void armorClick(Player p, int i) {
        PlayerInventory pi = p.getInventory();
        EquipmentSlot es = ARMOR_SLOT[i];
        ItemStack worn = pi.getItem(es), cursor = p.getItemOnCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean wearing = worn != null && !worn.getType().isAir();
        if (wearing && worn.containsEnchantment(Enchantment.BINDING_CURSE) && p.getGameMode() != GameMode.CREATIVE) {
            p.sendMessage(ChatColor.RED + "That " + ARMOR_NAME[i].toLowerCase() + " has the Curse of Binding.");
            return;
        }
        if (cursorEmpty) {
            if (!wearing) return;
            pi.setItem(es, null);
            p.setItemOnCursor(worn);
            p.playSound(p, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.9f);
            return;
        }
        if (!fits(cursor, es)) { p.sendMessage(ChatColor.RED + "That doesn't go in the " + ARMOR_NAME[i].toLowerCase() + " slot."); return; }
        ItemStack one = cursor.clone(); one.setAmount(1);
        ItemStack rest = null;
        if (cursor.getAmount() > 1) { rest = cursor.clone(); rest.setAmount(cursor.getAmount() - 1); }
        pi.setItem(es, one);
        if (rest == null) p.setItemOnCursor(wearing ? worn : null);
        else { p.setItemOnCursor(rest); if (wearing) giveBack(p, worn); }
        p.playSound(p, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.1f);
    }

    /** Where an item is worn: its equippable component (the Diving Suit, custom gear), else vanilla's slot for it. */
    boolean fits(ItemStack it, EquipmentSlot es) {
        if (it == null || it.getType().isAir()) return false;
        if (es == EquipmentSlot.CHEST && plugin.getDivingGear() != null && plugin.getDivingGear().isSuit(it)) return true;
        ItemMeta im = it.getItemMeta();
        if (im != null && im.hasEquippable()) return im.getEquippable().getSlot() == es;
        if (es == EquipmentSlot.HEAD && (it.getType() == Material.CARVED_PUMPKIN || it.getType().name().endsWith("_HEAD") || it.getType().name().endsWith("_SKULL"))) return true;
        try { return it.getType().getEquipmentSlot() == es; }
        catch (RuntimeException ex) { // (a test server without item data): by name
            String n = it.getType().name();
            return switch (es) {
                case HEAD -> n.endsWith("_HELMET");
                case CHEST -> n.endsWith("_CHESTPLATE") || n.equals("ELYTRA");
                case LEGS -> n.endsWith("_LEGGINGS");
                case FEET -> n.endsWith("_BOOTS");
                default -> false;
            };
        }
    }

    /** Shift-click from your inventory: an accessory into a free accessory slot, a piece of armour onto you. */
    private void shiftIn(Player p, Menu m, ItemStack it, int from) {
        if (it == null || it.getType().isAir()) return;
        PlayerInventory pi = p.getInventory();
        if (m.tab == 0 && FaultlineItems.AccessoryManager.isAccessory(plugin, it)) {
            Inventory inv = m.inventory;
            for (int s : FaultlineItems.AccessoryGuiHolder.SLOTS) {
                ItemStack o = inv.getItem(s);
                if (FaultlineItems.AccessoryManager.isAccessory(plugin, o) && FaultlineItems.AccessoryManager.same(plugin, o, it)) {
                    p.sendMessage(ChatColor.RED + "You're already wearing that accessory. Accessories don't stack."); return;
                }
            }
            for (int s : FaultlineItems.AccessoryGuiHolder.SLOTS) {
                if (FaultlineItems.AccessoryManager.isAccessory(plugin, inv.getItem(s))) continue;
                ItemStack one = it.clone(); one.setAmount(1);
                inv.setItem(s, one);
                it.setAmount(it.getAmount() - 1);
                pi.setItem(from, it.getAmount() <= 0 ? null : it);
                p.playSound(p, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.7f, 1.3f);
                refreshLater(p, m);
                return;
            }
            p.sendMessage(ChatColor.RED + "All 3 accessory slots are full.");
            return;
        }
        for (int i = 0; i < ARMOR_SLOT.length; i++) {
            EquipmentSlot es = ARMOR_SLOT[i];
            if (!fits(it, es)) continue;
            ItemStack worn = pi.getItem(es);
            if (worn != null && !worn.getType().isAir()) return;
            ItemStack one = it.clone(); one.setAmount(1);
            pi.setItem(es, one);
            it.setAmount(it.getAmount() - 1);
            pi.setItem(from, it.getAmount() <= 0 ? null : it);
            p.playSound(p, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.1f);
            refreshLater(p, m);
            return;
        }
    }

    private void giveBack(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void refreshLater(Player p, Menu m) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory t = p.isOnline() ? p.getOpenInventory().getTopInventory() : null;
            if (t != null && t.getHolder() == m) fill(p, m);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < size) { e.setCancelled(true); return; } // only drags inside your own inventory
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && e.getPlayer() instanceof Player p) saveAccessories(p, m);
    }

    /** Saves what's in the accessory slots (only while the Accessories tab is open: the other tabs don't hold them). */
    void saveAccessories(Player p, Menu m) {
        if (m.tab != 0 || m.saved) return;
        m.saved = true;
        Inventory inv = m.inventory;
        ItemStack[] save = new ItemStack[FaultlineItems.AccessoryManager.SLOT_COUNT];
        for (int i = 0; i < FaultlineItems.AccessoryGuiHolder.SLOTS.length; i++) {
            ItemStack it = inv.getItem(FaultlineItems.AccessoryGuiHolder.SLOTS[i]);
            save[i] = FaultlineItems.AccessoryManager.isAccessory(plugin, it) ? it : null;
            if (save[i] == null) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) if (save[j] != null && FaultlineItems.AccessoryManager.same(plugin, save[j], save[i])) dup = true;
            if (dup) {
                giveBack(p, save[i]);
                p.sendMessage(ChatColor.RED + "Accessories don't stack: the second copy went back to your inventory.");
                save[i] = null;
                continue;
            }
            AccessoryStacks.refresh(plugin, save[i]);
        }
        plugin.getAccessoryManager().setSlots(p.getUniqueId(), save);
        // the slots are now saved: clear them from this (closing / switching) menu so nothing can be counted twice
        for (int s : FaultlineItems.AccessoryGuiHolder.SLOTS) inv.setItem(s, null);
    }

    // ============================================================================================ FaultlineCosmetics, by reflection
    static final class Cosmetics {
        private static Plugin cached;
        private static final Map<String, Method> methods = new HashMap<>();

        static boolean present() { Plugin p = plugin(); return p != null && p.isEnabled(); }

        private static Plugin plugin() {
            if (cached == null || !cached.isEnabled()) { cached = Bukkit.getPluginManager().getPlugin("FaultlineCosmetics"); methods.clear(); }
            return cached;
        }

        private static Object call(String name, Class<?>[] types, Object... args) {
            Plugin p = plugin();
            if (p == null || !p.isEnabled()) return null;
            try {
                Method m = methods.get(name);
                if (m == null) { m = p.getClass().getMethod(name, types); methods.put(name, m); }
                return m.invoke(null, args);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                Bukkit.getLogger().warning("[FaultlineItems] Wardrobe: FaultlineCosmetics." + name + " failed: " + ex);
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static List<String> list(UUID id, String slot) { Object o = call("wardrobeList", new Class<?>[]{UUID.class, String.class}, id, slot); return o instanceof List<?> l ? (List<String>) l : List.of(); }
        static String worn(UUID id, String slot) { return (String) call("wardrobeWorn", new Class<?>[]{UUID.class, String.class}, id, slot); }
        static ItemStack icon(UUID id, String cos) { return (ItemStack) call("wardrobeIcon", new Class<?>[]{UUID.class, String.class}, id, cos); }
        static void click(Player p, String cos) { call("wardrobeClick", new Class<?>[]{Player.class, String.class}, p, cos); }
        static void takeOff(Player p, String slot) { call("wardrobeTakeOff", new Class<?>[]{Player.class, String.class}, p, slot); }
        static boolean hidden(UUID id) { return Boolean.TRUE.equals(call("wardrobeHidden", new Class<?>[]{UUID.class}, id)); }
        static void setHidden(Player p, boolean h) { call("wardrobeSetHidden", new Class<?>[]{Player.class, boolean.class}, p, h); }
        static boolean shop(Player p) { return Boolean.TRUE.equals(call("wardrobeShop", new Class<?>[]{Player.class}, p)); }
    }
}
