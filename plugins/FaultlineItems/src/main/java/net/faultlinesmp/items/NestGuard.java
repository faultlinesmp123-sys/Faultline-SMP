package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * NO SHULKER BOXES INSIDE SHULKER BOXES (Items 1.3.28, config nest-guard:).
 * Vanilla refuses it, but they turned up anyway (a Bedrock/Geyser inventory glitch, a hacked client, a dupe). Every way in
 * is closed and every nested one found is taken apart:
 *  - a shulker box's own menu (a placed box) refuses shulker boxes: place, shift-click, number keys, F swap, drag; a tick
 *    after any click there the box is checked anyway and anything that slipped in goes back to the clicker;
 *  - hoppers / droppers can't push one into a shulker box block;
 *  - a shulker box ITEM holding shulker boxes (at any depth, also inside bundles in it), or a bundle holding one, has them
 *    taken out: from players on join, every few seconds, on pickup and whenever they open anything; from real storage
 *    when it's opened. Taken-out boxes go to the player (full = dropped at their feet) or are dropped beside the container,
 *    with their own contents kept. Never deleted. Logged "[NestGuard]".
 * The fast check reads the item's container component (no meta copy); only an item that really nests gets rebuilt.
 */
final class NestGuard implements Listener {

    private final FaultlineItems plugin;

    NestGuard(FaultlineItems plugin) {
        this.plugin = plugin;
        long every = Math.max(20, plugin.getConfig().getLong("nest-guard.sweep-seconds", 5) * 20);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!on()) return;
            for (Player p : Bukkit.getOnlinePlayers()) sweepPlayer(p);
        }, every, every);
    }

    private boolean on() { return plugin.getConfig().getBoolean("nest-guard.enabled", true); }

    static boolean shulker(ItemStack it) { return it != null && Tag.SHULKER_BOXES.isTagged(it.getType()); }
    private static boolean bundle(ItemStack it) { return it != null && Tag.ITEMS_BUNDLES.isTagged(it.getType()); }

    /** Cheap: does this shulker box / bundle hold a shulker box somewhere inside? */
    static boolean nests(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        boolean box = shulker(it), bag = bundle(it);
        if (!box && !bag) return false;
        List<ItemStack> inside = null;
        try {
            if (box) {
                io.papermc.paper.datacomponent.item.ItemContainerContents c = it.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER);
                inside = c == null ? slowContents(it) : c.contents(); // no component = empty (or a server that doesn't fill it)
            } else {
                io.papermc.paper.datacomponent.item.BundleContents c = it.getData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS);
                inside = c == null ? slowContents(it) : c.contents();
            }
        } catch (Throwable t) {
            inside = slowContents(it); // component API missing: read the meta
        }
        if (inside == null) return false;
        for (ItemStack in : inside) {
            if (in == null) continue;
            if (shulker(in)) return true;
            if (bundle(in) && nests(in)) return true;
        }
        return false;
    }

    private static List<ItemStack> slowContents(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        List<ItemStack> out = new ArrayList<>();
        if (m instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox sb) {
            for (ItemStack in : sb.getInventory().getContents()) if (in != null) out.add(in);
        } else if (m instanceof BundleMeta bm) out.addAll(bm.getItems());
        return out;
    }

    /** Takes every shulker box out of this box / bundle (and out of bundles inside it) into out. True = changed. */
    static boolean strip(ItemStack it, List<ItemStack> out) {
        if (!nests(it)) return false;
        ItemMeta m = it.getItemMeta();
        boolean changed = false;
        if (m instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox sb) {
            Inventory inv = sb.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack in = inv.getItem(i);
                if (in == null) continue;
                if (shulker(in)) {
                    strip(in, out); // its own nested boxes come out too
                    out.add(in);
                    inv.setItem(i, null);
                    changed = true;
                } else if (bundle(in) && strip(in, out)) {
                    inv.setItem(i, in);
                    changed = true;
                }
            }
            if (changed) { bsm.setBlockState(sb); it.setItemMeta(bsm); }
        } else if (m instanceof BundleMeta bm) {
            List<ItemStack> keep = new ArrayList<>();
            for (ItemStack in : bm.getItems()) {
                if (shulker(in)) { strip(in, out); out.add(in); changed = true; }
                else { if (bundle(in) && strip(in, out)) changed = true; keep.add(in); }
            }
            if (changed) { bm.setItems(keep); it.setItemMeta(bm); }
        }
        return changed;
    }

    /** Fixes every slot of an inventory; returns the boxes taken out. */
    private List<ItemStack> fix(Inventory inv) {
        List<ItemStack> out = new ArrayList<>();
        if (inv == null) return out;
        boolean boxMenu = inv.getType() == InventoryType.SHULKER_BOX;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (boxMenu && shulker(it)) { // a shulker box sitting in a placed shulker box
                strip(it, out);
                out.add(it);
                inv.setItem(i, null);
                continue;
            }
            if (strip(it, out)) inv.setItem(i, it);
        }
        return out;
    }

    private void sweepPlayer(Player p) {
        List<ItemStack> out = fix(p.getInventory());
        out.addAll(fix(p.getEnderChest()));
        ItemStack cur = p.getItemOnCursor();
        if (shulker(cur) || bundle(cur)) { if (strip(cur, out)) p.setItemOnCursor(cur); }
        give(p, out, "the inventory");
    }

    private void give(Player p, List<ItemStack> out, String where) {
        if (out.isEmpty()) return;
        int n = out.stream().mapToInt(ItemStack::getAmount).sum();
        for (ItemStack left : p.getInventory().addItem(out.toArray(new ItemStack[0])).values())
            p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(ChatColor.YELLOW + "Shulker boxes can't go inside shulker boxes. " + n + " taken out and given back to you.");
        plugin.getLogger().info("[NestGuard] took " + n + " shulker box(es) out of nested boxes in " + where + " of " + p.getName());
    }

    private void drop(Location at, List<ItemStack> out, String what) {
        if (out.isEmpty() || at == null || at.getWorld() == null) return;
        for (ItemStack it : out) at.getWorld().dropItemNaturally(at, it);
        plugin.getLogger().info("[NestGuard] dropped " + out.size() + " shulker box(es) taken out of " + what + " at "
                + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
    }

    private static boolean realStorage(Inventory inv) {
        if (inv == null || inv.getHolder(false) == null) return false;
        Object h = inv.getHolder(false);
        return h instanceof org.bukkit.block.BlockState || h instanceof org.bukkit.block.DoubleChest
                || h instanceof org.bukkit.entity.Entity && !(h instanceof HumanEntity);
    }

    // ---------------- the ways in ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!on() || !(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getType() != InventoryType.SHULKER_BOX) return;
        Inventory clicked = e.getClickedInventory();
        boolean intoTop = false;
        switch (e.getAction()) {
            case MOVE_TO_OTHER_INVENTORY -> intoTop = clicked != top && shulker(e.getCurrentItem());
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> intoTop = clicked == top && shulker(e.getCursor());
            case HOTBAR_SWAP -> {
                if (clicked == top) {
                    ItemStack hb = e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton())
                            : p.getInventory().getItemInOffHand();
                    intoTop = shulker(hb);
                }
            }
            default -> { }
        }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && clicked == top)
            intoTop = intoTop || shulker(p.getInventory().getItemInOffHand());
        if (intoTop) e.setCancelled(true);
        // whatever got through anyway (client glitches, other plugins) comes back next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory() == top) give(p, fix(top), "a shulker box");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!on()) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getType() != InventoryType.SHULKER_BOX || !shulker(e.getOldCursor())) return;
        for (int raw : e.getRawSlots()) if (raw < top.getSize()) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!on()) return;
        if (e.getDestination().getType() == InventoryType.SHULKER_BOX && shulker(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (!on() || !(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            sweepPlayer(p);
            if (realStorage(top)) {
                List<ItemStack> out = fix(top);
                if (!out.isEmpty()) give(p, out, "a container they opened");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!on() || !(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        if (!realStorage(top)) return;
        List<ItemStack> out = fix(top);
        if (out.isEmpty()) return;
        if (p.isOnline()) give(p, out, "a container");
        else drop(top.getLocation(), out, "a container");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!on() || !(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        if (!nests(item.getItemStack())) return;
        Bukkit.getScheduler().runTask(plugin, () -> sweepPlayer(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!on()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sweepPlayer(p); }, 40);
    }
}
