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
 * MACE RULES (config mace:). mace.mode:
 *   off      (default since 1.2.55) nothing: maces are crafted and used freely (MaceEnchants caps their enchantments)
 *   remove   (1.2.15, needs mace.really-remove: true) maces are gone from the server: every mace anywhere is deleted (see the REMOVE section)
 *   restrict (1.2.14) the rules below: no crafting, and a mace only lives in a player's own inventory
 *
 * RESTRICT: maces can't be crafted, and a mace only ever lives in a player's own inventory: it can't go
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
        sweptKey = new org.bukkit.NamespacedKey(plugin, "mace_swept");
        sweptEntitiesKey = new org.bukkit.NamespacedKey(plugin, "mace_swept_entities");
        if (on("no-crafting")) removeRecipe();
        // the sweep: every online player every 2 seconds; the worlds already loaded once, right after startup
        Bukkit.getScheduler().runTaskTimer(plugin, () -> guard(this::sweepPlayers), 40L, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> guard(this::sweepLoaded), 60L);
    }

    private long errAt;
    private void guard(Runnable r) {
        try { r.run(); } catch (RuntimeException | LinkageError e) {
            if (System.currentTimeMillis() - errAt > 60000) { errAt = System.currentTimeMillis(); plugin.getLogger().log(java.util.logging.Level.WARNING, "[Maces] sweep error", e); }
        }
    }

    String mode() {
        if (!plugin.getConfig().getBoolean("mace.enabled", true)) return "off";
        String m = plugin.getConfig().getString("mace.mode", "off");
        m = m == null ? "off" : m.toLowerCase(java.util.Locale.ROOT);
        // 1.2.55: maces are back (craft and use them freely). A server config still holding 1.2.15's default "remove" reads
        // as "off"; to delete maces again set mace.really-remove: true (or mode: restrict for the 1.2.14 rules)
        if (m.equals("remove") && !plugin.getConfig().getBoolean("mace.really-remove", false)) m = "off";
        return m.equals("remove") || m.equals("restrict") ? m : "off"; // (an unquoted off reads as "false")
    }

    boolean removing() { return mode().equals("remove"); }

    private boolean on(String what) {
        String m = mode();
        if (m.equals("off")) return false;
        if (m.equals("remove")) return true; // removal includes every restriction (belt and braces between sweeps)
        return plugin.getConfig().getBoolean("mace." + what, true);
    }

    static boolean mace(ItemStack it) { return it != null && it.getType() == Material.MACE; }

    static boolean holds(Material m) { String n = m.name(); return n.endsWith("BUNDLE") || n.endsWith("SHULKER_BOX"); }

    private boolean bypass(Entity e) {
        if (!(e instanceof Player p)) return false;
        if (p.hasPermission("faultline.mace.bypass")) return true;
        return !removing() && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR); // removal: creative too
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


    // ===================================================================== REMOVE (mace.mode: remove)
    //
    // Every mace is deleted wherever it turns up: players' inventories (armor, offhand, the cursor, the 2x2 grid, an open
    // workstation), ender chests, inside bundles and shulker boxes (nested), every block container (chests, barrels,
    // shulkers, hoppers, droppers, dispensers, crafters, pots, shelves...) as its chunk loads, entities as they load
    // (items on the ground, item frames, armor stands, mobs' hands, allays, villagers, donkey/llama/mule chests, chest
    // minecarts and boats), new item drops, mob and block drops, loot chests as they fill, fishing. A mace used in the
    // second between sweeps does nothing (the hit is cancelled and the mace taken). Each removal is logged.

    private static final String TAG = ChatColor.GOLD + "[Faultline] " + ChatColor.GRAY;

    /** Deletes maces in an array of items (deep: bundles, shulker boxes). Returns how many maces went. */
    static int strip(ItemStack[] items) {
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            if (mace(it)) { n += it.getAmount(); items[i] = null; continue; }
            n += stripInside(it);
        }
        return n;
    }

    /** Maces inside a bundle or a shulker box item (the item itself stays). */
    static int stripInside(ItemStack it) {
        // only bundles and shulker boxes can hold items: don't unpack every enchanted/named item (getItemMeta copies it)
        if (it == null || !holds(it.getType()) || !it.hasItemMeta()) return 0;
        int n = 0;
        var meta = it.getItemMeta();
        if (meta instanceof BundleMeta bm && bm.hasItems()) {
            List<ItemStack> keep = new ArrayList<>();
            boolean changed = false;
            for (ItemStack in : bm.getItems()) {
                if (mace(in)) { n += in.getAmount(); changed = true; continue; }
                int inner = stripInside(in);
                if (inner > 0) { n += inner; changed = true; }
                keep.add(in);
            }
            if (changed) { bm.setItems(keep); it.setItemMeta(bm); }
        } else if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof org.bukkit.block.Container box) {
            ItemStack[] c = box.getInventory().getContents();
            int inner = strip(c);
            if (inner > 0) {
                box.getInventory().setContents(c);
                bsm.setBlockState(box);
                it.setItemMeta(bsm);
                n += inner;
            }
        }
        return n;
    }

    /** Deletes maces in an inventory (deep). */
    static int strip(Inventory inv) {
        if (inv == null) return 0;
        ItemStack[] c = inv.getContents();
        int n = strip(c);
        if (n > 0) inv.setContents(c);
        return n;
    }

    private void report(String who, String where, int n) {
        if (n <= 0) return;
        plugin.getLogger().info("[Maces] removed " + n + " mace" + (n == 1 ? "" : "s") + " (" + who + ", " + where + ")");
    }

    /** One player: inventory, cursor, open grid/workstation or real storage, ender chest. */
    int sweep(Player p) {
        if (!removing() || p.hasPermission("faultline.mace.bypass")) return 0;
        int n = strip(p.getInventory());
        ItemStack cur = p.getItemOnCursor();
        if (mace(cur)) { n += cur.getAmount(); p.setItemOnCursor(null); }
        else if (stripInside(cur) > 0) { n++; p.setItemOnCursor(cur); }
        Inventory top = p.getOpenInventory() == null ? null : p.getOpenInventory().getTopInventory();
        if (top != null && top != p.getInventory() && (WORKSTATIONS.contains(top.getType()) || AccessoryStacks.realStorage(top))) n += strip(top);
        n += strip(p.getEnderChest());
        if (n > 0) {
            p.sendMessage(TAG + "Maces are disabled on Faultline SMP. " + ChatColor.RED + n + " mace" + (n == 1 ? " was" : "s were") + " removed" + ChatColor.GRAY + ".");
            report(p.getName(), "inventory/ender chest", n);
        }
        return n;
    }

    private void sweepPlayers() {
        if (!removing()) return;
        for (Player p : Bukkit.getOnlinePlayers()) sweep(p);
    }

    private void sweepLoaded() {
        if (!removing()) return;
        int n = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk c : w.getLoadedChunks()) if (!swept(c, sweptKey)) { n += sweepChunk(c, false); mark(c, sweptKey); }
            for (Entity e : w.getEntities()) n += sweepEntity(e);
        }
        report("startup", "loaded worlds", n);
    }

    /** Every block container in the chunk (chests, barrels, shulkers, hoppers, pots, shelves, crafters...). */
    int sweepChunk(org.bukkit.Chunk c, boolean log) {
        int n = 0;
        org.bukkit.block.BlockState[] tiles;
        try { tiles = c.getTileEntities(false); } catch (RuntimeException e) { return 0; }
        for (org.bukkit.block.BlockState t : tiles) {
            if (!(t instanceof org.bukkit.inventory.BlockInventoryHolder h)) continue;
            int k = strip(h.getInventory());
            if (k > 0 && log) report("container", t.getType().name().toLowerCase() + " at " + t.getWorld().getName() + " " + t.getX() + " " + t.getY() + " " + t.getZ(), k);
            n += k;
        }
        return n;
    }

    /** One entity: an item on the ground, a frame, anything with equipment or an inventory. */
    int sweepEntity(Entity e) {
        int n = 0;
        if (e instanceof org.bukkit.entity.Item item) {
            ItemStack it = item.getItemStack();
            if (mace(it)) { n += it.getAmount(); item.remove(); return n; }
            int k = stripInside(it);
            if (k > 0) { item.setItemStack(it); n += k; }
            return n;
        }
        if (e instanceof ItemFrame f) {
            ItemStack it = f.getItem();
            if (mace(it)) { n += it.getAmount(); f.setItem(null); }
            else { int k = stripInside(it); if (k > 0) { f.setItem(it); n += k; } }
        }
        if (e instanceof org.bukkit.entity.LivingEntity le && !(e instanceof Player) && le.getEquipment() != null) {
            var eq = le.getEquipment();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    ItemStack it = eq.getItem(slot);
                    if (mace(it)) { n += it.getAmount(); eq.setItem(slot, null); }
                } catch (RuntimeException ignored) { } // a slot this entity doesn't have
            }
        }
        if (e instanceof org.bukkit.inventory.InventoryHolder h && !(e instanceof Player)) {
            try { n += strip(h.getInventory()); } catch (RuntimeException ignored) { }
        }
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
        if (!removing() || e.isNewChunk()) return; // a freshly generated chunk has no maces
        org.bukkit.Chunk c = e.getChunk();
        if (swept(c, sweptKey)) return; // every container here was checked once already (nothing can put a mace back)
        Bukkit.getScheduler().runTask(plugin, () -> guard(() -> { if (c.isLoaded()) { sweepChunk(c, true); mark(c, sweptKey); } }));
    }

    private org.bukkit.NamespacedKey sweptKey, sweptEntitiesKey;

    static boolean swept(org.bukkit.Chunk c, org.bukkit.NamespacedKey k) {
        try { return c.getPersistentDataContainer().has(k, org.bukkit.persistence.PersistentDataType.BYTE); } catch (RuntimeException e) { return false; }
    }

    static void mark(org.bukkit.Chunk c, org.bukkit.NamespacedKey k) {
        try { c.getPersistentDataContainer().set(k, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1); } catch (RuntimeException ignored) { }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        if (!removing() || e.getEntities().isEmpty() || swept(e.getChunk(), sweptEntitiesKey)) return;
        mark(e.getChunk(), sweptEntitiesKey);
        List<Entity> list = new ArrayList<>(e.getEntities());
        Bukkit.getScheduler().runTask(plugin, () -> guard(() -> {
            int n = 0;
            for (Entity en : list) if (en.isValid()) n += sweepEntity(en);
            report("entities", e.getChunk().getWorld().getName() + " chunk " + e.getChunk().getX() + "," + e.getChunk().getZ(), n);
        }));
    }

    /** No new mace items in the world (dropped, spawned, fished, spat out by anything). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent e) {
        if (!removing()) return;
        ItemStack it = e.getEntity().getItemStack();
        if (mace(it)) { e.setCancelled(true); report("item", "spawned in the world", it.getAmount()); return; }
        if (stripInside(it) > 0) e.getEntity().setItemStack(it);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (!removing()) return;
        Entity en = e.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> { if (en.isValid()) report("mob", en.getType().name().toLowerCase(), sweepEntity(en)); });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathDrops(org.bukkit.event.entity.EntityDeathEvent e) {
        if (!removing()) return;
        int n = 0;
        for (java.util.Iterator<ItemStack> i = e.getDrops().iterator(); i.hasNext(); ) {
            ItemStack it = i.next();
            if (mace(it)) { n += it.getAmount(); i.remove(); } else n += stripInside(it);
        }
        report(e.getEntity().getName(), "death drops", n);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrops(org.bukkit.event.block.BlockDropItemEvent e) {
        if (!removing()) return;
        e.getItems().removeIf(item -> mace(item.getItemStack()));
        for (org.bukkit.entity.Item item : e.getItems()) { ItemStack it = item.getItemStack(); if (stripInside(it) > 0) item.setItemStack(it); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoot(org.bukkit.event.world.LootGenerateEvent e) {
        if (!removing()) return;
        List<ItemStack> loot = new ArrayList<>(e.getLoot());
        int before = loot.size();
        loot.removeIf(MaceRules::mace);
        if (loot.size() != before) e.setLoot(loot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (removing() && e.getCaught() instanceof org.bukkit.entity.Item item && mace(item.getItemStack())) item.remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        if (removing() && mace(e.getItem())) {
            e.setCancelled(true);
            if (e.getBlock().getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder h) strip(h.getInventory());
        }
    }

    /** A player picking a mace up: it vanishes instead (and anything inside a picked-up bundle/shulker is cleaned). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent e) {
        if (!removing() || !(e.getEntity() instanceof Player p) || p.hasPermission("faultline.mace.bypass")) return;
        ItemStack it = e.getItem().getItemStack();
        if (mace(it)) { e.setCancelled(true); e.getItem().remove(); report(p.getName(), "picked up", it.getAmount()); return; }
        if (stripInside(it) > 0) e.getItem().setItemStack(it);
    }

    /** Used between two sweeps: the swing does nothing and the mace is taken right away. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSmash(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!removing() || !(e.getDamager() instanceof Player p) || p.hasPermission("faultline.mace.bypass")) return;
        if (mace(p.getInventory().getItemInMainHand())) { e.setCancelled(true); sweep(p); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (removing() && mace(e.getItem()) && !e.getPlayer().hasPermission("faultline.mace.bypass")) { e.setCancelled(true); sweep(e.getPlayer()); }
    }

    @EventHandler
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (removing() && mace(e.getPlayer().getInventory().getItem(e.getNewSlot()))) sweep(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpenSweep(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!removing() || !(e.getPlayer() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) sweep(p); });
    }

    // ===================================================================== maces already hidden in bundles

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (removing()) { Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sweep(p); }, 20L); return; }
        if (!on("inventory-only")) return;
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
