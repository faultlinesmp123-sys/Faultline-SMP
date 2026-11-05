package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OLD GEAR (1.2.65): the land bosses (the Demon Eye, Frostbeard, the Dune Devourer / Frostmaw, Don Lorenzo, Rocco
 * Vendetta, Grimtusk, the Leviathan, the Sandworm King, the Lich, the Frost Wyrm, the Stone Golem), the Boss Rush and
 * the boss trophies were removed. Their items are taken away wherever they turn up: a player's inventory, cursor and
 * ender chest (on join, every 30 s, when anything is opened, on pickup), containers (once per chunk, on load), items on
 * the ground, item frames and mobs' hands (once per chunk's entities), deep inside bundles and shulker boxes, and Museum
 * Pedestals (Gear.showChunk). The CRAFTED summons give their main ingredients back (the Sigil its 8 Netherite Blocks and
 * Nether Star, the Vendetta Contract, Warlord's Challenge, Dune Idol and the four wild summons theirs); boss drops,
 * weapons, discs and trophies just go. `old-gear.enabled: false` turns it off. Every removal is logged ("[OldGear]").
 */
final class OldGear implements Listener {

    private final FaultlineItems plugin;
    private final NamespacedKey sweptKey, sweptEntitiesKey;

    /** faultlinebosses keys whose item is gone whatever its value. */
    static final Set<String> BOSS_KEYS = Set.of("suspicious_eye", "twin_eye_staff", "demon_eye_shield", "unholy_arrow", "mortimer_egg",
            "dune_egg", "frostmaw_egg", "captains_armband", "lorenzos_ball", "talon", "dune_idol", "vendetta_item", "warlord_item", "rush_sigil");
    /** faultlinebosses:wild_item values that are still in the game (the Dark Ocean's summons). */
    static final Set<String> WILD_KEEP = Set.of("brine_bait", "umbral_sonar", "sirens_comb", "inkjaw_bait", "glistening_pearl", "mournful_shell");
    /** faultlineitems:gear values that are gone. */
    static final Set<String> GEAR_GONE = Set.of("ancient_gear_part", "disc_demon_eye", "disc_dune", "disc_don", "disc_rocco", "disc_explorer");

    /** What a crafted summon gives back (per item). */
    static final Map<String, List<ItemStack>> REFUND = Map.of(
            "rush_sigil", List.of(new ItemStack(Material.NETHERITE_BLOCK, 8), new ItemStack(Material.NETHER_STAR)),
            "contract", List.of(new ItemStack(Material.GOLD_BLOCK, 4), new ItemStack(Material.NETHERITE_SCRAP, 4), new ItemStack(Material.BOOK)),
            "challenge", List.of(new ItemStack(Material.GOLD_BLOCK, 4), new ItemStack(Material.BLAZE_ROD, 4), new ItemStack(Material.NETHERITE_SCRAP)),
            "dune_idol", List.of(new ItemStack(Material.NETHERITE_INGOT), new ItemStack(Material.DIAMOND)),
            "abyssal_lure", List.of(new ItemStack(Material.HEART_OF_THE_SEA), new ItemStack(Material.NAUTILUS_SHELL, 4), new ItemStack(Material.PRISMARINE_CRYSTALS, 4)),
            "sandworm_drum", List.of(new ItemStack(Material.GOLD_BLOCK), new ItemStack(Material.CHISELED_SANDSTONE, 4), new ItemStack(Material.RABBIT_HIDE, 4)),
            "lich_phylactery", List.of(new ItemStack(Material.TOTEM_OF_UNDYING), new ItemStack(Material.ECHO_SHARD, 4), new ItemStack(Material.BONE_BLOCK, 4)),
            "frozen_horn", List.of(new ItemStack(Material.GOAT_HORN), new ItemStack(Material.DIAMOND, 4), new ItemStack(Material.BLUE_ICE, 4)));

    private static final NamespacedKey WILD = new NamespacedKey("faultlinebosses", "wild_item");
    private static final NamespacedKey VENDETTA = new NamespacedKey("faultlinebosses", "vendetta_item");
    private static final NamespacedKey WARLORD = new NamespacedKey("faultlinebosses", "warlord_item");
    private static final NamespacedKey GEAR = new NamespacedKey("faultlineitems", "gear");
    private static final NamespacedKey CORE = new NamespacedKey("faultlineitems", "ancient_core");
    private static final NamespacedKey TROPHY = new NamespacedKey("faultlineindex", "trophy");
    private static final NamespacedKey IMBUED = new NamespacedKey("faultlineitems", "ankh_imbued");

    OldGear(FaultlineItems plugin) {
        this.plugin = plugin;
        sweptKey = new NamespacedKey(plugin, "oldgear_swept");
        sweptEntitiesKey = new NamespacedKey(plugin, "oldgear_swept_entities");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> guard(() -> { if (on()) for (Player p : Bukkit.getOnlinePlayers()) sweep(p); }), 600L, 600L);
    }

    boolean on() { return plugin.getConfig().getBoolean("old-gear.enabled", true); }

    private void guard(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { plugin.getLogger().warning("[OldGear] " + e); }
    }

    // ===================================================================== what's old

    /** The reason this item is gone ("" = a plain removal, else a REFUND key), or null when it stays. */
    static String why(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        io.papermc.paper.persistence.PersistentDataContainerView c;
        try { c = it.getPersistentDataContainer(); } catch (RuntimeException | NoSuchMethodError e) { return null; }
        if (c == null || c.isEmpty()) return null;
        if (c.has(IMBUED)) return null; // a Shield of the Demon Eye imbued with the Ankh Shield stays (a plain shield now): never eat an Ankh Shield
        for (NamespacedKey k : c.getKeys()) {
            String ns = k.getNamespace(), key = k.getKey();
            if (ns.equals("faultlinebosses") && BOSS_KEYS.contains(key)) {
                if (key.equals("rush_sigil") || key.equals("dune_idol")) return key;
                if (key.equals("vendetta_item") || key.equals("warlord_item")) {
                    String v = c.get(k.equals(VENDETTA) ? VENDETTA : WARLORD, PersistentDataType.STRING);
                    return "contract".equals(v) || "challenge".equals(v) ? v : "";
                }
                return "";
            }
            if (k.equals(WILD)) {
                String v = c.get(WILD, PersistentDataType.STRING);
                if (v == null || WILD_KEEP.contains(v)) return null;
                return REFUND.containsKey(v) ? v : "";
            }
            if (k.equals(GEAR)) {
                String v = c.get(GEAR, PersistentDataType.STRING);
                if (v != null && GEAR_GONE.contains(v)) return "";
            }
            if (k.equals(CORE) || k.equals(TROPHY)) return "";
        }
        return null;
    }

    static boolean old(ItemStack it) { return why(it) != null; }

    static boolean holds(Material m) { String n = m.name(); return n.endsWith("BUNDLE") || n.endsWith("SHULKER_BOX"); }

    /** Takes old gear out of an array (deep: bundles, shulker boxes); refunds go in `back`. Returns how many items went. */
    static int strip(ItemStack[] items, List<ItemStack> back) {
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            String w = why(it);
            if (w != null) {
                n += it.getAmount();
                refund(w, it.getAmount(), back);
                items[i] = null;
                continue;
            }
            n += stripInside(it, back);
        }
        return n;
    }

    static void refund(String why, int amount, List<ItemStack> back) {
        List<ItemStack> r = REFUND.get(why);
        if (r == null) return;
        for (ItemStack s : r) {
            int total = s.getAmount() * amount;
            while (total > 0) {
                int n = Math.min(total, s.getMaxStackSize());
                back.add(new ItemStack(s.getType(), n));
                total -= n;
            }
        }
    }

    /** Old gear inside a bundle or a shulker box item (the item itself stays). */
    static int stripInside(ItemStack it, List<ItemStack> back) {
        if (it == null || !holds(it.getType()) || !it.hasItemMeta()) return 0;
        int n = 0;
        var meta = it.getItemMeta();
        if (meta instanceof BundleMeta bm && bm.hasItems()) {
            List<ItemStack> keep = new ArrayList<>();
            boolean changed = false;
            for (ItemStack in : bm.getItems()) {
                String w = why(in);
                if (w != null) { n += in.getAmount(); refund(w, in.getAmount(), back); changed = true; continue; }
                int inner = stripInside(in, back);
                if (inner > 0) { n += inner; changed = true; }
                keep.add(in);
            }
            if (changed) { bm.setItems(keep); it.setItemMeta(bm); }
        } else if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof org.bukkit.block.Container box) {
            ItemStack[] c = box.getInventory().getContents();
            int inner = strip(c, back);
            if (inner > 0) {
                box.getInventory().setContents(c);
                bsm.setBlockState(box);
                it.setItemMeta(bsm);
                n += inner;
            }
        }
        return n;
    }

    /** Old gear in an inventory (deep); refunds go back into it, anything that doesn't fit drops at `spill`. */
    static int strip(Inventory inv, Location spill) {
        if (inv == null) return 0;
        ItemStack[] c = inv.getContents();
        List<ItemStack> back = new ArrayList<>();
        int n = strip(c, back);
        if (n > 0) {
            inv.setContents(c);
            for (ItemStack left : inv.addItem(back.toArray(new ItemStack[0])).values())
                if (spill != null && spill.getWorld() != null) spill.getWorld().dropItemNaturally(spill, left);
        }
        return n;
    }

    private void report(String who, String where, int n) {
        if (n > 0) plugin.getLogger().info("[OldGear] removed " + n + " item" + (n == 1 ? "" : "s") + " of removed bosses (" + who + ", " + where + ")");
    }

    // ===================================================================== players

    /** One player: inventory, cursor, the storage they have open, ender chest. */
    int sweep(Player p) {
        if (!on()) return 0;
        Location at = p.getLocation();
        int n = strip(p.getInventory(), at);
        ItemStack cur = p.getItemOnCursor();
        List<ItemStack> back = new ArrayList<>();
        String w = why(cur);
        if (w != null) { n += cur.getAmount(); refund(w, cur.getAmount(), back); p.setItemOnCursor(null); }
        else if (stripInside(cur, back) > 0) { n++; p.setItemOnCursor(cur); }
        for (ItemStack left : p.getInventory().addItem(back.toArray(new ItemStack[0])).values()) p.getWorld().dropItemNaturally(at, left);
        try {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top != p.getInventory() && AccessoryStacks.realStorage(top)) n += strip(top, top.getLocation() != null ? top.getLocation() : at);
        } catch (RuntimeException ignored) { }
        n += strip(p.getEnderChest(), at);
        if (n > 0) {
            p.sendMessage(ChatColor.GOLD + "[Faultline] " + ChatColor.GRAY + "The old land bosses, the Boss Rush and the boss trophies are gone: "
                    + ChatColor.RED + n + " of their item" + (n == 1 ? " was" : "s were") + " removed" + ChatColor.GRAY + " (crafted summons gave their materials back).");
            report(p.getName(), "inventory/ender chest", n);
        }
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> guard(() -> { if (p.isOnline()) sweep(p); }), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (!on() || !(e.getPlayer() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> guard(() -> { if (p.isOnline()) sweep(p); }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!on()) return;
        Item item = e.getItem();
        ItemStack it = item.getItemStack();
        List<ItemStack> back = new ArrayList<>();
        String w = why(it);
        if (w != null) {
            e.setCancelled(true);
            refund(w, it.getAmount(), back);
            report(e.getEntity().getName(), "picked up", it.getAmount());
            item.remove();
            for (ItemStack r : back) item.getWorld().dropItem(item.getLocation(), r);
        } else if (stripInside(it, back) > 0) {
            item.setItemStack(it);
            for (ItemStack r : back) item.getWorld().dropItem(item.getLocation(), r);
        }
    }

    // ===================================================================== the world (once per chunk)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!on() || e.isNewChunk()) return;
        Chunk c = e.getChunk();
        if (MaceRules.swept(c, sweptKey)) return;
        Bukkit.getScheduler().runTask(plugin, () -> guard(() -> { if (c.isLoaded()) { sweepChunk(c); MaceRules.mark(c, sweptKey); } }));
    }

    int sweepChunk(Chunk c) {
        int n = 0;
        org.bukkit.block.BlockState[] tiles;
        try { tiles = c.getTileEntities(false); } catch (RuntimeException e) { return 0; }
        for (org.bukkit.block.BlockState t : tiles) {
            if (!(t instanceof org.bukkit.inventory.BlockInventoryHolder h)) continue;
            int k = strip(h.getInventory(), t.getLocation().add(0.5, 1, 0.5));
            if (k > 0) report("container", t.getType().name().toLowerCase() + " at " + t.getWorld().getName() + " " + t.getX() + " " + t.getY() + " " + t.getZ(), k);
            n += k;
        }
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!on() || e.getEntities().isEmpty() || MaceRules.swept(e.getChunk(), sweptEntitiesKey)) return;
        MaceRules.mark(e.getChunk(), sweptEntitiesKey);
        List<Entity> list = new ArrayList<>(e.getEntities());
        Bukkit.getScheduler().runTask(plugin, () -> guard(() -> {
            int n = 0;
            for (Entity en : list) if (en.isValid()) n += sweepEntity(en);
            report("entities", e.getChunk().getWorld().getName() + " chunk " + e.getChunk().getX() + "," + e.getChunk().getZ(), n);
        }));
    }

    /** An item on the ground, a frame, a mob's equipment, an entity's inventory. */
    int sweepEntity(Entity e) {
        int n = 0;
        List<ItemStack> back = new ArrayList<>();
        Location at = e.getLocation();
        if (e instanceof Item item) {
            ItemStack it = item.getItemStack();
            String w = why(it);
            if (w != null) { n += it.getAmount(); refund(w, it.getAmount(), back); item.remove(); }
            else if (stripInside(it, back) > 0) { item.setItemStack(it); n++; }
        } else if (e instanceof ItemFrame f) {
            ItemStack it = f.getItem();
            String w = why(it);
            if (w != null) { n += it.getAmount(); refund(w, it.getAmount(), back); f.setItem(null); }
        } else if (e instanceof org.bukkit.entity.LivingEntity le && !(e instanceof Player) && le.getEquipment() != null) {
            var eq = le.getEquipment();
            for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                try {
                    ItemStack it = eq.getItem(slot);
                    String w = why(it);
                    if (w != null) { n += it.getAmount(); refund(w, it.getAmount(), back); eq.setItem(slot, null); }
                } catch (RuntimeException ignored) { } // a slot this entity doesn't have
            }
        }
        if (e instanceof org.bukkit.inventory.InventoryHolder h && !(e instanceof Player)) {
            try { n += strip(h.getInventory(), at); } catch (RuntimeException ignored) { }
        }
        for (ItemStack r : back) e.getWorld().dropItemNaturally(at, r);
        return n;
    }
}
