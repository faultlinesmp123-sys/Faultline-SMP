package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MACE RULES (config mace:). Maces can't be crafted, and a mace only ever lives in a player's own inventory: it can't go
 * into a chest, barrel, shulker box, ender chest, hopper, dropper, dispenser, crafter, a donkey/llama/mule's chest, a
 * chest minecart or boat, a ship's hold or any other plugin storage, a bundle, an item frame, an armor stand, a
 * decorated pot, a shelf, or an allay. Hoppers and hopper minecarts can't pick a dropped mace up or move one along, and
 * mobs can't pick one up. Workstations still work (anvil, grindstone, enchanting table, smithing table...): the mace
 * comes back when you close them. Creative players and faultline.mace.bypass skip all of it.
 *
 * Every way to get a mace in vanilla is the crafting recipe (Heavy Core + Breeze Rod): no loot table, trade or mob
 * drop has one. With crafting off (the recipe is removed from the server and the crafting table / Crafter block are
 * guarded too), only maces that already exist and admin-given ones (/give, creative) remain. Maces players already put
 * in bundles are taken out of the bundle when they join; ones already in chests can still be taken out.
 */
final class MaceRules implements Listener {

    private final FaultlineItems plugin;

    /** Workstations: the item sits there only while the screen is open (it comes back on close). */
    private static final Set<InventoryType> WORKSTATIONS = EnumSet.noneOf(InventoryType.class);
    static {
        for (String n : List.of("CRAFTING", "PLAYER", "CREATIVE", "WORKBENCH", "ANVIL", "GRINDSTONE", "ENCHANTING", "SMITHING",
                "SMITHING_NEW", "CARTOGRAPHY", "LOOM", "STONECUTTER", "MERCHANT")) {
            try { WORKSTATIONS.add(InventoryType.valueOf(n)); } catch (IllegalArgumentException ignored) { }
        }
    }

    MaceRules(FaultlineItems plugin) {
        this.plugin = plugin;
        if (on("no-crafting")) removeRecipe();
    }

    private boolean on(String what) {
        return plugin.getConfig().getBoolean("mace.enabled", true) && plugin.getConfig().getBoolean("mace." + what, true);
    }

    static boolean mace(ItemStack it) { return it != null && it.getType() == Material.MACE; }

    private static boolean bypass(Entity e) {
        return e instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("faultline.mace.bypass"));
    }

    private static void no(Player p, String what) {
        p.sendActionBar(ChatColor.RED + "A mace can't go " + what + ChatColor.GRAY + " (it stays in your inventory).");
    }

    // ===================================================================== crafting

    void removeRecipe() {
        try { Bukkit.removeRecipe(NamespacedKey.minecraft("mace")); } catch (RuntimeException ignored) { }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        if (!on("no-crafting") || !mace(e.getInventory().getResult())) return;
        if (e.getView().getPlayer() instanceof Player p && p.hasPermission("faultline.mace.bypass")) return;
        e.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (on("no-crafting") && mace(e.getResult())) e.setCancelled(true);
    }

    // ===================================================================== containers

    /** The top of this view is somewhere a mace may not be left. */
    private static boolean storage(Inventory top) {
        return top != null && !WORKSTATIONS.contains(top.getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!on("inventory-only") || !(e.getWhoClicked() instanceof Player p) || bypass(p)) return;
        InventoryAction a = e.getAction();
        ItemStack cursor = e.getCursor(), current = e.getCurrentItem();
        // bundles, anywhere (your own inventory too): no mace goes into one
        if (a.name().contains("INTO_BUNDLE") || bundleClick(e)) {
            if (mace(cursor) || mace(current)) { e.setCancelled(true); no(p, "in a bundle"); }
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (!storage(top)) return;
        boolean clickedTop = e.getRawSlot() >= 0 && e.getRawSlot() < top.getSize();
        if (current == null && !clickedTop && e.getRawSlot() >= 0) current = e.getView().getItem(e.getRawSlot());
        // shift-click from your inventory into the container
        if (!clickedTop && e.getRawSlot() >= 0 && (a == InventoryAction.MOVE_TO_OTHER_INVENTORY || e.isShiftClick()) && mace(current)) {
            e.setCancelled(true); no(p, "in there"); return;
        }
        if (!clickedTop) return;
        // a mace on the cursor put down in the container
        if (mace(cursor) && (a.name().startsWith("PLACE_") || a == InventoryAction.SWAP_WITH_CURSOR)) { e.setCancelled(true); no(p, "in there"); return; }
        // number keys / F (offhand) swapping a mace into a container slot
        if (a == InventoryAction.HOTBAR_SWAP || a.name().equals("HOTBAR_MOVE_AND_READD") || e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack swapped = e.getClick() == ClickType.SWAP_OFFHAND ? p.getInventory().getItemInOffHand()
                    : e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
            if (mace(swapped)) { e.setCancelled(true); no(p, "in there"); }
        }
    }

    /** A bundle clicked onto a mace, or a mace clicked onto a bundle (older API names). */
    private static boolean bundleClick(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor(), current = e.getCurrentItem();
        boolean bundleInvolved = isBundle(cursor) && mace(current) || isBundle(current) && mace(cursor);
        return bundleInvolved && (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.LEFT);
    }

    static boolean isBundle(ItemStack it) { return it != null && it.getItemMeta() instanceof BundleMeta; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!on("inventory-only") || !(e.getWhoClicked() instanceof Player p) || bypass(p)) return;
        if (!mace(e.getOldCursor())) return;
        Inventory top = e.getView().getTopInventory();
        if (!storage(top)) return;
        for (int raw : e.getRawSlots()) if (raw < top.getSize()) { e.setCancelled(true); no(p, "in there"); return; }
    }

    /** Hoppers (and hopper minecarts) can't suck a dropped mace up... */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (on("inventory-only") && mace(e.getItem().getItemStack())) e.setCancelled(true);
    }

    /** ...or pass one along from a container it was left in before this. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (on("inventory-only") && mace(e.getItem())) e.setCancelled(true);
    }

    /** Only players pick maces up (no allays, foxes, zombies... carrying it off). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobPickup(EntityPickupItemEvent e) {
        if (on("inventory-only") && !(e.getEntity() instanceof Player) && mace(e.getItem().getItemStack())) e.setCancelled(true);
    }

    // ===================================================================== frames, stands, pots, shelves, allays

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!on("inventory-only") || bypass(p)) return;
        ItemStack hand = e.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!mace(hand)) return;
        Entity t = e.getRightClicked();
        if (t instanceof ItemFrame) { e.setCancelled(true); no(p, "in an item frame"); }
        else if (t instanceof Allay) { e.setCancelled(true); no(p, "to an allay"); }
        else if (t instanceof org.bukkit.entity.AbstractHorse || t instanceof org.bukkit.entity.minecart.StorageMinecart
                || t instanceof org.bukkit.entity.ChestBoat) {
            // opening their chest is fine (the click rules above guard it); nothing else to do
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (on("inventory-only") && !bypass(e.getPlayer()) && mace(e.getPlayerItem())) { e.setCancelled(true); no(e.getPlayer(), "on an armor stand"); }
    }

    /** Decorated pots and shelves take the item in your hand on a right-click. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !on("inventory-only") || bypass(e.getPlayer())) return;
        if (!mace(e.getItem())) return;
        String n = e.getClickedBlock().getType().name();
        if (n.equals("DECORATED_POT") || n.endsWith("_SHELF") || n.equals("CHISELED_BOOKSHELF")) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
            no(e.getPlayer(), n.equals("DECORATED_POT") ? "in a pot" : "on a shelf");
        }
    }

    // ===================================================================== maces already hidden in bundles

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!on("inventory-only")) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) unbundle(p); }, 40L);
    }

    /** Takes maces out of every bundle in the player's inventory (into the inventory, or at their feet when it's full). */
    int unbundle(Player p) {
        int n = 0;
        ItemStack[] contents = p.getInventory().getContents();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isBundle(it)) continue;
            BundleMeta bm = (BundleMeta) it.getItemMeta();
            List<ItemStack> keep = new ArrayList<>();
            boolean changed = false;
            for (ItemStack in : bm.getItems()) {
                if (mace(in)) { out.add(in); changed = true; n += in.getAmount(); } else keep.add(in);
            }
            if (!changed) continue;
            bm.setItems(keep);
            it.setItemMeta(bm);
            p.getInventory().setItem(i, it);
        }
        for (ItemStack m : out) p.getInventory().addItem(m).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        if (n > 0) p.sendMessage(ChatColor.GOLD + "Maces can't be kept in bundles: " + ChatColor.GRAY + "your mace was taken out of the bundle.");
        return n;
    }
}
