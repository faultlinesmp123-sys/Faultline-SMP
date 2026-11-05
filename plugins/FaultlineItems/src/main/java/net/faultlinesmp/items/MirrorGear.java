package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Mirror..?? (dropped by Diamond Jacob, every fighter). 1.2.66: a plain ITEM, not an accessory: right-clicked (FaultlineDeep)
 * it's the way to the abandoned kingdom and the Dark Ocean, once you've beaten Jacob, and the way back up. Its old worn power
 * (reflecting a big hit) is gone. Old Mirrors are rebuilt (`convert`: no accessory tag, new lore) wherever they're found in a
 * player's inventory / ender chest, and one left in an accessory slot is taken out (`unslot`, 30 ticks after join).
 */
final class MirrorGear implements Listener {

    private final FaultlineItems plugin;
    final NamespacedKey key;

    MirrorGear(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "lost_mirror");
    }

    private static final List<String> LORE = List.of(
            ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "You look into it. Something looks back.",
            ChatColor.AQUA + "Right-click: go to the abandoned kingdom",
            ChatColor.AQUA + "(needs Diamond Jacob beaten).",
            ChatColor.AQUA + "In the Dark Ocean: right-click to go back up.",
            ChatColor.DARK_GRAY + "Dropped by Diamond Jacob.");

    ItemStack item() {
        ItemStack item = new ItemStack(Material.GLASS_PANE);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Mirror..??");
        m.setLore(LORE);
        m.addEnchant(Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.setMaxStackSize(1);
        m.setItemModel(new NamespacedKey("faultline", key.getKey()));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(m);
        return item;
    }

    boolean is(ItemStack it) {
        return it != null && it.getType() != Material.AIR && it.getPersistentDataContainer().has(key);
    }

    /** An old (accessory) Mirror becomes the plain item. True if it changed. */
    boolean convert(ItemStack it) {
        if (!is(it)) return false;
        ItemMeta m = it.getItemMeta();
        boolean changed = m.getPersistentDataContainer().has(plugin.getAccessoryKey());
        if (changed) m.getPersistentDataContainer().remove(plugin.getAccessoryKey());
        if (!LORE.equals(m.getLore())) { m.setLore(LORE); changed = true; }
        if (changed) it.setItemMeta(m);
        return changed;
    }

    private void convertAll(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return;
        ItemStack[] c = inv.getContents();
        boolean any = false;
        for (ItemStack it : c) any |= convert(it);
        if (any) inv.setContents(c);
    }

    /** A Mirror still in an accessory slot goes to the inventory (an open Wardrobe is closed first, or it would save it back). */
    void unslot(Player p) {
        var am = plugin.getAccessoryManager();
        var top = p.getOpenInventory() == null ? null : p.getOpenInventory().getTopInventory();
        ItemStack[] slots = am.getSlots(p.getUniqueId());
        boolean found = false;
        for (int i = 0; slots != null && i < slots.length; i++) if (is(slots[i])) found = true;
        if (!found) return;
        if (top != null && top.getHolder() instanceof FaultlineItems.AccessoryGuiHolder) p.closeInventory();
        slots = am.getSlots(p.getUniqueId());
        for (int i = 0; slots != null && i < slots.length; i++) {
            if (!is(slots[i])) continue;
            ItemStack m = slots[i].clone();
            convert(m);
            slots[i] = null;
            p.getInventory().addItem(m).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        am.setSlots(p.getUniqueId(), slots);
        p.sendMessage(ChatColor.WHITE + "The Mirror..?? " + ChatColor.GRAY + "isn't an accessory any more: it's been taken out of your accessory slot and put in your inventory.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            try { unslot(p); convertAll(p.getInventory()); convertAll(p.getEnderChest()); }
            catch (RuntimeException ex) { plugin.getLogger().warning("[Mirror] " + ex); }
        }, 30L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            try {
                convertAll(p.getInventory());
                var top = p.getOpenInventory().getTopInventory();
                if (top != null && AccessoryStacks.realStorage(top)) convertAll(top);
            } catch (RuntimeException ignored) { }
        });
    }
}
