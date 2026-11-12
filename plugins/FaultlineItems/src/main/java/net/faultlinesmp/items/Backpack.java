package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

/**
 * THE BACKPACK (Items 1.2.78, config backpack:). An accessory (worn in /accessories) that gives 9 extra slots: /backpack
 * (/bp) opens them, or right-click the air while sneaking with an empty hand. The contents belong to the PLAYER (player PDC
 * faultlineitems:backpack_items, saved on every change), not the item, so a backpack can never carry a copy of its contents
 * somewhere else; without one worn, the slots can't be opened (they're kept). Dying drops what's in it, like the accessory
 * itself (keepInventory keeps both). A backpack can't go in a backpack, and race-lent items can't either.
 * Recipe: leather, string, a chest (LSL / LCL / LLL).
 */
final class Backpack implements Listener, CommandExecutor {

    static final int SIZE = 9;
    private final FaultlineItems plugin;
    final NamespacedKey key, dataKey;

    Backpack(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "backpack");
        dataKey = new NamespacedKey(plugin, "backpack_items");
        try {
            NamespacedKey rk = new NamespacedKey(plugin, "backpack_recipe");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, item());
                r.shape("LSL", "LCL", "LLL");
                r.setIngredient('L', Material.LEATHER);
                r.setIngredient('S', Material.STRING);
                r.setIngredient('C', Material.CHEST);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { plugin.getLogger().warning("Backpack recipe: " + e.getMessage()); }
    }

    ItemStack item() {
        return FaultlineItems.NewAccessoryItems.make(plugin, Material.LEATHER, ChatColor.GOLD + "" + ChatColor.BOLD + "Backpack", key,
                ChatColor.GREEN + "9 extra slots while you wear it.",
                ChatColor.GREEN + "/backpack, or sneak + right-click the air",
                ChatColor.GREEN + "with an empty hand.",
                ChatColor.DARK_GRAY + "What's inside drops when you die.");
    }

    boolean isBackpack(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    boolean wearing(Player p) { return plugin.getAccessoryManager().hasEquipped(p, key); }

    static final class Holder implements InventoryHolder {
        final java.util.UUID owner;
        Inventory inv;
        Holder(java.util.UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inv; }
    }

    ItemStack[] load(Player p) {
        ItemStack[] out = new ItemStack[SIZE];
        byte[] b = p.getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (b == null || b.length == 0) return out;
        try {
            ItemStack[] got = ItemStack.deserializeItemsFromBytes(b);
            for (int i = 0; i < Math.min(SIZE, got.length); i++) out[i] = got[i] == null || got[i].getType().isAir() ? null : got[i];
        } catch (RuntimeException e) { plugin.getLogger().warning("[Backpack] couldn't read " + p.getName() + "'s backpack: " + e); }
        return out;
    }

    void store(Player p, ItemStack[] items) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        boolean empty = true;
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE && i < items.length; i++) {
            ItemStack it = items[i];
            if (it != null && !it.getType().isAir()) { copy[i] = it.clone(); empty = false; } else copy[i] = new ItemStack(Material.AIR);
        }
        if (empty) { pdc.remove(dataKey); return; }
        byte[] raw;
        try { raw = ItemStack.serializeItemsAsBytes(copy); }
        catch (RuntimeException ex) {
            // 1.3.12: one item the game can't write used to make EVERY save throw: the old contents stayed stored while the
            // new ones sat in the menu (what was taken out came back on the next open = a dupe). Now that item is handed
            // back and the rest is saved.
            for (int i = 0; i < SIZE; i++) {
                if (copy[i].getType().isAir()) continue;
                try { copy[i].serializeAsBytes(); }
                catch (RuntimeException bad) {
                    ItemStack out = copy[i];
                    copy[i] = new ItemStack(Material.AIR);
                    if (i < items.length) items[i] = null;
                    plugin.getLogger().warning("[Backpack] " + p.getName() + "'s " + out.getType() + " x" + out.getAmount() + " can't be saved (" + bad + "): put back in their inventory.");
                    out.setAmount(Math.max(1, Math.min(out.getAmount(), out.getMaxStackSize())));
                    for (ItemStack rest : p.getInventory().addItem(out).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
            }
            try { raw = ItemStack.serializeItemsAsBytes(copy); }
            catch (RuntimeException again) { plugin.getLogger().warning("[Backpack] couldn't save " + p.getName() + "'s backpack: " + again); pdc.remove(dataKey); return; }
        }
        pdc.set(dataKey, PersistentDataType.BYTE_ARRAY, raw);
    }

    void open(Player p) {
        if (!plugin.getConfig().getBoolean("backpack.enabled", true)) { p.sendMessage(ChatColor.RED + "Backpacks are turned off."); return; }
        if (!wearing(p)) { p.sendMessage(ChatColor.RED + "You're not wearing a Backpack (put one in /accessories)."); return; }
        Holder h = new Holder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, SIZE, ChatColor.DARK_GRAY + "Backpack");
        h.inv = inv;
        inv.setContents(load(p));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.1f);
    }

    private void saveFrom(Player p, Inventory inv) {
        ItemStack[] now = Arrays.copyOf(inv.getContents(), SIZE);
        store(p, now);
        for (int i = 0; i < SIZE; i++) { ItemStack was = inv.getItem(i); if (now[i] == null && was != null && !was.getType().isAir()) inv.setItem(i, null); } // handed back
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        open(p);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR || !e.getPlayer().isSneaking()) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !e.getPlayer().getInventory().getItemInMainHand().getType().isAir()) return;
        if (wearing(e.getPlayer())) open(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeldUse(PlayerInteractEvent e) {
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && isBackpack(e.getItem()))
            e.getPlayer().sendActionBar(ChatColor.GOLD + "Wear it in /accessories, then /backpack opens it.");
    }

    boolean refused(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (isBackpack(it)) return true;
        if (!it.hasItemMeta()) return false;
        return it.getPersistentDataContainer().has(new NamespacedKey(plugin, "race_lent"), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        boolean top = e.getRawSlot() >= 0 && e.getRawSlot() < SIZE;
        ItemStack incoming = null;
        if (top) {
            incoming = e.getCursor();
            if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) incoming = p.getInventory().getItem(e.getHotbarButton());
            if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) incoming = p.getInventory().getItemInOffHand();
        } else if (e.isShiftClick()) incoming = e.getCurrentItem();
        if (refused(incoming)) { e.setCancelled(true); p.sendActionBar(ChatColor.RED + "That can't go in a backpack."); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory() == h.inv) saveFrom(p, h.inv); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        boolean intoTop = e.getRawSlots().stream().anyMatch(s -> s < SIZE);
        if (intoTop && refused(e.getOldCursor())) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory() == h.inv) saveFrom(p, h.inv); });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h && e.getPlayer() instanceof Player p && p.getUniqueId().equals(h.owner)) {
            if (h.inv == null) return;
            saveFrom(p, h.inv);
            h.inv = null; // closed: later events for it are ignored
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Inventory top = e.getPlayer().getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof Holder h && h.inv != null) { saveFrom(e.getPlayer(), h.inv); h.inv = null; }
    }

    /** Dying drops what's in the backpack (keepInventory keeps it). Runs before the accessory drops. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (e.getKeepInventory() || !plugin.getConfig().getBoolean("backpack.drop-on-death", true)) return;
        ItemStack[] items;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof Holder h && h.inv != null) {
            items = Arrays.copyOf(h.inv.getContents(), SIZE);
            h.inv.clear();
            h.inv = null;
        } else items = load(p);
        for (ItemStack it : items) if (it != null && !it.getType().isAir()) e.getDrops().add(it);
        p.getPersistentDataContainer().remove(dataKey);
    }
}
