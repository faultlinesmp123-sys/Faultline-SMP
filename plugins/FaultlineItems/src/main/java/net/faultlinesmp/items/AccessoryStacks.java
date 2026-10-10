package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 1.2.12: accessories don't stack (they used to stack to 16). New ones are made with a max stack size of 1; old stacks
 * are split up when a player joins, opens a container, or picks one up (the extras go into empty slots, or drop at their
 * feet when there's no room). The lore of nerfed / changed accessories (Cleft Horn, Abyssal Diving Suit) is refreshed too.
 */
final class AccessoryStacks implements Listener {

    private final FaultlineItems plugin;

    AccessoryStacks(FaultlineItems plugin) { this.plugin = plugin; }

    /** Max stack 1, current lore for the changed ones. Returns true if anything changed. */
    static boolean refresh(FaultlineItems plugin, ItemStack item) {
        if (!FaultlineItems.AccessoryManager.isAccessory(plugin, item)) return false;
        ItemMeta m = item.getItemMeta();
        boolean changed = false;
        if (!m.hasMaxStackSize() || m.getMaxStackSize() != 1) { m.setMaxStackSize(1); changed = true; }
        for (ItemStack fresh : changedOnes(plugin)) {
            if (fresh == null || !FaultlineItems.AccessoryManager.same(plugin, fresh, item)) continue;
            List<String> lore = fresh.getItemMeta().getLore();
            if (lore != null && !lore.equals(m.getLore())) { m.setLore(lore); changed = true; }
        }
        if (changed) item.setItemMeta(m);
        return changed;
    }

    private static List<ItemStack> changedOnes(FaultlineItems plugin) {
        ItemStack suit = null;
        try { suit = plugin.getDivingGear().item(DivingGear.Piece.SUIT); } catch (RuntimeException ignored) { }
        return java.util.Arrays.asList(FaultlineItems.NewAccessoryItems.cleftHorn(plugin), suit);
    }

    /** Splits every stacked accessory in the inventory; extras go to empty slots, then the player, then the ground. */
    void fix(Inventory inv, Player p) {
        if (inv == null) return;
        int extra = 0;
        ItemStack[] contents = inv.getStorageContents();
        java.util.List<ItemStack> spill = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!FaultlineItems.AccessoryManager.isAccessory(plugin, it)) continue;
            boolean changed = refresh(plugin, it);
            if (it.getAmount() > 1) {
                for (int k = 1; k < it.getAmount(); k++) { ItemStack one = it.clone(); one.setAmount(1); spill.add(one); }
                extra += it.getAmount() - 1;
                it.setAmount(1);
                changed = true;
            }
            if (changed) contents[i] = it;
        }
        for (ItemStack one : spill) {
            int free = -1;
            for (int i = 0; i < contents.length; i++) if (contents[i] == null || contents[i].getType().isAir()) { free = i; break; }
            if (free >= 0) { contents[free] = one; continue; }
            Location at = p != null ? p.getLocation() : inv.getLocation();
            if (at != null && at.getWorld() != null) at.getWorld().dropItemNaturally(at, one);
        }
        inv.setStorageContents(contents);
        if (extra > 0 && p != null)
            p.sendMessage(ChatColor.GOLD + "Accessories don't stack anymore: " + ChatColor.GRAY + extra + " stacked " + (extra == 1 ? "copy was" : "copies were")
                    + " split into their own slots" + ChatColor.DARK_GRAY + " (anything without room was dropped at your feet).");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { fix(p.getInventory(), p); fix(p.getEnderChest(), p); } }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        // BUG FIX (dupe): only real storage (chests, barrels, shulkers, hoppers, chest minecarts/boats, the ender chest).
        // A plugin menu (null holder or its own holder) that SHOWS accessories in stacks would otherwise be "split" and
        // the extra copies handed to the player.
        boolean storage = realStorage(top);
        Bukkit.getScheduler().runTask(plugin, () -> { if (storage) fix(top, p); fix(p.getInventory(), p); });
    }

    static boolean realStorage(Inventory inv) {
        if (inv.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) return true;
        org.bukkit.inventory.InventoryHolder h;
        try { h = inv.getHolder(false); } catch (NoSuchMethodError | RuntimeException e) { h = inv.getHolder(); }
        return h instanceof org.bukkit.block.Container || h instanceof org.bukkit.block.DoubleChest
                || h instanceof org.bukkit.entity.minecart.StorageMinecart || h instanceof org.bukkit.entity.minecart.HopperMinecart
                || h instanceof org.bukkit.entity.ChestBoat;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !FaultlineItems.AccessoryManager.isAccessory(plugin, e.getItem().getItemStack())) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) fix(p.getInventory(), p); });
    }
}
