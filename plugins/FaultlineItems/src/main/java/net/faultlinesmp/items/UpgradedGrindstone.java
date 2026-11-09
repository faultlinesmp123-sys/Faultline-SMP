package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 1.3.9: the UPGRADED GRINDSTONE. 8 Blue Obsidian round a grindstone. Placed = a real grindstone (marked in the chunk PDC
 * {@code faultlineitems:upgraded_grindstone}) with a blue model over it for Java players. Right-click = a menu: put an
 * item in the middle, then click any one enchant to take it off, or take off every boss enchant / every normal enchant /
 * everything. Boss enchants come back as their book ({@code return-boss-books}); normal ones give XP like a grindstone
 * ({@code return-vanilla-books: true} = books). Curses stay unless {@code remove-curses}.
 */
final class UpgradedGrindstone implements Listener {

    static final NamespacedKey RECIPE = NamespacedKey.fromString("faultlineitems:upgraded_grindstone");
    static final String DISPLAY_TAG = "faultline_upgraded_grindstone";
    private static final int INPUT = 13, ALL_BOSS = 46, ALL_NORMAL = 48, ALL = 50, CLOSE = 53;

    private final FaultlineItems plugin;
    private final NamespacedKey itemKey, chunkKey;
    private final Map<String, Set<Long>> blocks = new HashMap<>();
    private final Map<String, Map<Long, ItemDisplay>> overlays = new HashMap<>();
    private final Random random = new Random();

    UpgradedGrindstone(FaultlineItems plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "upgraded_grindstone");
        chunkKey = new NamespacedKey(plugin, "upgraded_grindstone");
        try {
            ShapedRecipe r = new ShapedRecipe(RECIPE, item(1));
            r.shape("BBB", "BGB", "BBB");
            r.setIngredient('B', new RecipeChoice.ExactChoice(plugin.blueObsidian.item(1)));
            r.setIngredient('G', Material.GRINDSTONE);
            FaultlineItems.addRecipeSafely(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("[Grindstone] recipe: " + e); }
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) chunkIn(c);
        Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 100L, 100L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("upgraded-grindstone.enabled", true); }
    private boolean opt(String k, boolean d) { return plugin.getConfig().getBoolean("upgraded-grindstone." + k, d); }

    // ---------------------------------------------------------------- the item

    ItemStack item(int amount) {
        ItemStack it = new ItemStack(Material.GRINDSTONE, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Upgraded Grindstone");
        m.setLore(List.of(ChatColor.GRAY + "Takes enchants off your gear:",
                ChatColor.GRAY + "one at a time, every " + ChatColor.LIGHT_PURPLE + "boss enchant" + ChatColor.GRAY + ",",
                ChatColor.GRAY + "every normal enchant, or all of them.",
                ChatColor.DARK_GRAY + "Boss enchants come back as books."));
        m.setItemModel(new NamespacedKey("faultline", "upgraded_grindstone"));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) {
        return it != null && it.getType() == Material.GRINDSTONE && it.hasItemMeta() && it.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- placed blocks

    static long key(Block b) { return BlueObsidian.key(b); }
    private static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }

    boolean isOurs(Block b) {
        if (b.getType() != Material.GRINDSTONE) return false;
        Set<Long> s = blocks.get(b.getWorld().getName());
        return s != null && s.contains(key(b));
    }

    private int[] read(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(chunkKey, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    private void mark(Block b, boolean add) {
        Chunk c = b.getChunk();
        int l = local(b);
        List<Integer> list = new ArrayList<>();
        for (int v : read(c)) if (v != l) list.add(v);
        if (add) list.add(l);
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (list.isEmpty()) pdc.remove(chunkKey);
        else pdc.set(chunkKey, PersistentDataType.INTEGER_ARRAY, list.stream().mapToInt(Integer::intValue).toArray());
        Set<Long> s = blocks.computeIfAbsent(b.getWorld().getName(), w -> new HashSet<>());
        if (add) { s.add(key(b)); overlay(b); } else { s.remove(key(b)); unoverlay(b.getWorld().getName(), key(b)); }
    }

    void chunkIn(Chunk c) {
        int[] a = read(c);
        if (a.length == 0) return;
        World w = c.getWorld();
        Set<Long> s = blocks.computeIfAbsent(w.getName(), x -> new HashSet<>());
        List<Integer> keep = new ArrayList<>();
        for (int v : a) {
            Block b = w.getBlockAt((c.getX() << 4) + (v & 15), (v >> 8) - 2048, (c.getZ() << 4) + (v >> 4 & 15));
            if (b.getType() != Material.GRINDSTONE) continue; // gone while we weren't looking (WorldEdit...)
            keep.add(v);
            s.add(key(b));
            overlay(b);
        }
        if (keep.size() != a.length) {
            if (keep.isEmpty()) c.getPersistentDataContainer().remove(chunkKey);
            else c.getPersistentDataContainer().set(chunkKey, PersistentDataType.INTEGER_ARRAY, keep.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    void chunkOut(Chunk c) {
        String w = c.getWorld().getName();
        Set<Long> s = blocks.get(w);
        if (s == null) return;
        for (int v : read(c)) {
            long k = BlueObsidian.key((c.getX() << 4) + (v & 15), (v >> 8) - 2048, (c.getZ() << 4) + (v >> 4 & 15));
            s.remove(k);
            unoverlay(w, k);
        }
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) { chunkIn(e.getChunk()); }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) { chunkOut(e.getChunk()); }

    /** The block's turn, as its blockstate turns the vanilla model (x then y, like BlockModelRotation). */
    static Quaternionf turn(BlockData d) {
        int x = 0, y = 0;
        BlockFace f = d instanceof Directional dir ? dir.getFacing() : BlockFace.NORTH;
        FaceAttachable.AttachedFace a = d instanceof FaceAttachable fa ? fa.getAttachedFace() : FaceAttachable.AttachedFace.FLOOR;
        int fy = switch (f) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
        switch (a) {
            case FLOOR -> y = fy;
            case WALL -> { x = 90; y = fy; }
            case CEILING -> { x = 180; y = (fy + 180) % 360; }
        }
        return new Quaternionf().rotateYXZ((float) Math.toRadians(-y), (float) Math.toRadians(-x), 0f);
    }

    private void overlay(Block b) {
        Map<Long, ItemDisplay> m = overlays.computeIfAbsent(b.getWorld().getName(), w -> new HashMap<>());
        ItemDisplay old = m.get(key(b));
        if (old != null && old.isValid()) return;
        Location at = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        ItemDisplay d = (ItemDisplay) b.getWorld().spawnEntity(at, EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(DISPLAY_TAG);
        d.addScoreboardTag("faultline_no_bedrock_fx"); // Bedrock sees the real grindstone
        d.setPersistent(false);
        d.setItemStack(BlueObsidian.model("upgraded_grindstone"));
        try { d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE); } catch (RuntimeException ignored) { }
        float s = 1.01f;
        d.setTransformation(new Transformation(new Vector3f(), turn(b.getBlockData()), new Vector3f(s, s, s), new Quaternionf()));
        light(b, d);
        m.put(key(b), d);
    }

    private static void light(Block b, ItemDisplay d) {
        int bl = 0, sl = 0;
        try { bl = b.getLightFromBlocks(); sl = b.getLightFromSky(); } catch (RuntimeException ignored) { }
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.getType().isOccluding()) continue;
            try { bl = Math.max(bl, n.getLightFromBlocks()); sl = Math.max(sl, n.getLightFromSky()); } catch (RuntimeException ignored) { }
        }
        try { d.setBrightness(new Display.Brightness(Math.max(bl, 3), sl)); } catch (RuntimeException ignored) { }
    }

    private void unoverlay(String world, long k) {
        Map<Long, ItemDisplay> m = overlays.get(world);
        if (m == null) return;
        ItemDisplay d = m.remove(k);
        if (d != null && d.isValid()) d.remove();
    }

    /** Every 5 s: light follows the time of day; a block gone without an event loses its mark. */
    private void upkeep() {
        for (var we : new ArrayList<>(overlays.entrySet())) {
            World w = Bukkit.getWorld(we.getKey());
            if (w == null) continue;
            for (var e : new ArrayList<>(we.getValue().entrySet())) {
                long k = e.getKey();
                Block b = w.getBlockAt(BlueObsidian.kx(k), BlueObsidian.ky(k), BlueObsidian.kz(k));
                if (!b.getChunk().isLoaded()) continue;
                if (b.getType() != Material.GRINDSTONE) { mark(b, false); continue; }
                ItemDisplay d = e.getValue();
                if (d == null || !d.isValid()) { we.getValue().remove(k); overlay(b); } else light(b, d);
            }
        }
    }

    void shutdown() {
        for (Map<Long, ItemDisplay> m : overlays.values()) for (ItemDisplay d : m.values()) if (d != null && d.isValid()) d.remove();
        overlays.clear();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isItem(e.getItemInHand())) mark(e.getBlockPlaced(), true);
    }

    private final Set<Long> breaking = new HashSet<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isOurs(b)) return;
        mark(b, false);
        breaking.add(key(b));
        Bukkit.getScheduler().runTask(plugin, () -> breaking.remove(key(b)));
    }

    /** Broken = the Upgraded Grindstone drops, not a plain one. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!breaking.remove(key(e.getBlock()))) return;
        for (Item it : e.getItems()) if (it.getItemStack().getType() == Material.GRINDSTONE && !isItem(it.getItemStack())) {
            it.setItemStack(item(it.getItemStack().getAmount()));
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isOurs); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isOurs); }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (isOurs(b)) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (isOurs(b)) { e.setCancelled(true); return; } }

    // ---------------------------------------------------------------- the menu

    static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, String> buttons = new HashMap<>();
        boolean done;
        @Override public Inventory getInventory() { return inv; }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!isOurs(e.getClickedBlock())) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock() && !e.getItem().getType().isAir()) return; // building beside it
        e.setCancelled(true);
        if (!on()) { p.sendActionBar(Meteor.legacy(ChatColor.GRAY + "The Upgraded Grindstone is turned off.")); return; }
        open(p);
    }

    void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, ChatColor.DARK_AQUA + "Upgraded Grindstone");
        render(m);
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.6f, 1.3f);
    }

    private static ItemStack pane(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        m.setHideTooltip(name.isEmpty());
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        m.setLore(List.of(lore));
        it.setItemMeta(m);
        return it;
    }

    /** Normal enchants on an item, or stored in a book. */
    static Map<Enchantment, Integer> normal(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return new LinkedHashMap<>();
        ItemMeta m = it.getItemMeta();
        if (m instanceof EnchantmentStorageMeta s) return new LinkedHashMap<>(s.getStoredEnchants());
        return new LinkedHashMap<>(m.getEnchants());
    }

    private boolean bossBook(ItemStack it) {
        return it != null && it.getType() == Material.ENCHANTED_BOOK && !plugin.enchants.read(it, true).isEmpty();
    }

    private static String nice(Enchantment e) {
        String k = e.getKey().getKey();
        StringBuilder s = new StringBuilder();
        for (String w : k.split("_")) if (!w.isEmpty()) s.append(s.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return s.toString();
    }

    private boolean removable(Enchantment e) { return !e.isCursed() || opt("remove-curses", false); }

    void render(Menu m) {
        Inventory inv = m.inv;
        ItemStack input = inv.getItem(INPUT);
        m.buttons.clear();
        ItemStack bg = pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "), frame = pane(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) if (i != INPUT) inv.setItem(i, i / 9 < 3 && Math.abs(i % 9 - 4) <= 1 && i / 9 <= 2 ? frame : bg);
        inv.setItem(4, button(Material.GRINDSTONE, ChatColor.AQUA + "Put an item in the middle",
                ChatColor.GRAY + "Then click an enchant below to take it off,",
                ChatColor.GRAY + "or use the buttons at the bottom.",
                ChatColor.LIGHT_PURPLE + "Boss enchants" + ChatColor.GRAY + " come back as their book.",
                opt("return-vanilla-books", false) ? ChatColor.GRAY + "Normal enchants come back as books." : ChatColor.GRAY + "Normal enchants give XP, like a grindstone.",
                opt("remove-curses", false) ? ChatColor.GRAY + "Curses can be taken off too." : ChatColor.DARK_GRAY + "Curses can't be taken off."));
        inv.setItem(CLOSE, button(Material.BARRIER, ChatColor.RED + "Close"));
        if (input == null || input.getType().isAir()) return;
        if (input.getAmount() > 1) { inv.setItem(31, button(Material.PAPER, ChatColor.YELLOW + "One item at a time")); return; }
        if (bossBook(input)) { inv.setItem(31, button(Material.PAPER, ChatColor.YELLOW + "That's already a boss enchant book")); return; }
        int slot = 27;
        Map<String, Integer> boss = plugin.enchants.read(input, false);
        for (var e : boss.entrySet()) {
            if (slot > 44) break;
            Enchants.Def d = Enchants.DEFS.get(e.getKey());
            inv.setItem(slot, button(Material.ENCHANTED_BOOK, d.color() + "✦ " + d.name() + " " + Enchants.roman(e.getValue()) + ChatColor.GRAY + " (Boss)",
                    ChatColor.YELLOW + "Click to take it off",
                    opt("return-boss-books", true) ? ChatColor.DARK_GRAY + "You get the book back." : ChatColor.DARK_GRAY + "It's gone for good."));
            m.buttons.put(slot++, "b:" + e.getKey());
        }
        for (var e : normal(input).entrySet()) {
            if (slot > 44) break;
            boolean ok = removable(e.getKey());
            String lvl = e.getKey().getMaxLevel() > 1 || e.getValue() > 1 ? " " + Enchants.roman(e.getValue()) : "";
            inv.setItem(slot, button(ok ? Material.BOOK : Material.RED_STAINED_GLASS_PANE,
                    (e.getKey().isCursed() ? ChatColor.RED : ChatColor.WHITE) + nice(e.getKey()) + lvl,
                    ok ? ChatColor.YELLOW + "Click to take it off" : ChatColor.DARK_GRAY + "Curses can't be taken off"));
            if (ok) m.buttons.put(slot, "v:" + e.getKey().getKey());
            slot++;
        }
        if (slot == 27) inv.setItem(31, button(Material.PAPER, ChatColor.GRAY + "No enchants on this"));
        if (!boss.isEmpty()) { inv.setItem(ALL_BOSS, button(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "Take off every boss enchant")); m.buttons.put(ALL_BOSS, "allboss"); }
        if (normal(input).keySet().stream().anyMatch(this::removable)) {
            inv.setItem(ALL_NORMAL, button(Material.ENCHANTING_TABLE, ChatColor.AQUA + "Take off every normal enchant"));
            m.buttons.put(ALL_NORMAL, "allnormal");
        }
        if (m.buttons.containsKey(ALL_BOSS) || m.buttons.containsKey(ALL_NORMAL)) {
            inv.setItem(ALL, button(Material.LAVA_BUCKET, ChatColor.RED + "Take off everything"));
            m.buttons.put(ALL, "all");
        }
    }

    private void later(Menu m) { Bukkit.getScheduler().runTask(plugin, () -> { if (!m.done) render(m); }); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (raw >= 54) { // their own inventory
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack cur = e.getCurrentItem();
                ItemStack in = m.inv.getItem(INPUT);
                if (cur != null && !cur.getType().isAir() && (in == null || in.getType().isAir())) {
                    m.inv.setItem(INPUT, cur.clone());
                    e.setCurrentItem(null);
                    render(m);
                }
            }
            return;
        }
        if (raw == INPUT) { later(m); return; } // put in / take out like a normal slot
        e.setCancelled(true);
        if (raw == CLOSE) { p.closeInventory(); return; }
        String b = m.buttons.get(raw);
        if (b == null) return;
        ItemStack input = m.inv.getItem(INPUT);
        if (input == null || input.getType().isAir() || input.getAmount() != 1) { render(m); return; }
        int n = strip(p, input, b);
        if (input.getType() == Material.ENCHANTED_BOOK && normal(input).isEmpty() && plugin.enchants.read(input, false).isEmpty())
            input = new ItemStack(Material.BOOK); // an emptied book is a plain book, like a grindstone
        m.inv.setItem(INPUT, input);
        render(m);
        if (n > 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
            Location l = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.ENCHANT, l, 30, 0.5, 0.6, 0.5, 0.6);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        for (int s : e.getRawSlots()) if (s < 54 && s != INPUT) { e.setCancelled(true); return; }
        if (e.getRawSlots().contains(INPUT)) later(m);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !(e.getPlayer() instanceof Player p)) return;
        m.done = true;
        ItemStack in = m.inv.getItem(INPUT);
        m.inv.clear();
        if (in != null && !in.getType().isAir()) give(p, in);
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    /** Takes off what the button says; returns how many enchants came off. Books / XP go to the player. */
    int strip(Player p, ItemStack it, String what) {
        int n = 0, xp = 0;
        boolean bossAll = what.equals("allboss") || what.equals("all"), normalAll = what.equals("allnormal") || what.equals("all");
        Map<String, Integer> boss = plugin.enchants.read(it, false);
        Map<String, Integer> keep = new LinkedHashMap<>(boss);
        for (var e : boss.entrySet()) {
            if (!bossAll && !what.equals("b:" + e.getKey())) continue;
            keep.remove(e.getKey());
            n++;
            if (opt("return-boss-books", true)) { ItemStack book = plugin.enchants.book(e.getKey(), e.getValue()); if (book != null) give(p, book); }
        }
        if (keep.size() != boss.size()) plugin.enchants.write(it, keep);
        Map<Enchantment, Integer> norm = normal(it);
        ItemMeta m = it.getItemMeta();
        boolean any = false;
        for (var e : norm.entrySet()) {
            if (!removable(e.getKey()) || !normalAll && !what.equals("v:" + e.getKey().getKey())) continue;
            if (m instanceof EnchantmentStorageMeta s) s.removeStoredEnchant(e.getKey()); else m.removeEnchant(e.getKey());
            any = true;
            n++;
            if (opt("return-vanilla-books", false)) {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta bm = (EnchantmentStorageMeta) book.getItemMeta();
                bm.addStoredEnchant(e.getKey(), e.getValue(), true);
                book.setItemMeta(bm);
                give(p, book);
            } else {
                try { xp += e.getKey().getMinModifiedCost(e.getValue()); } catch (RuntimeException | LinkageError ex) { xp += 1 + e.getValue() * 10; }
            }
        }
        if (any) {
            int left = m instanceof EnchantmentStorageMeta s ? s.getStoredEnchants().size() : m.getEnchants().size();
            if (m instanceof Repairable r) r.setRepairCost((1 << Math.min(left, 30)) - 1); // like a grindstone: from what's left
            it.setItemMeta(m);
        }
        if (xp > 0) {
            int got = (int) Math.ceil(xp / 2.0) + random.nextInt(xp - (int) Math.ceil(xp / 2.0) + 1);
            try {
                ExperienceOrb orb = (ExperienceOrb) p.getWorld().spawnEntity(p.getLocation(), EntityType.EXPERIENCE_ORB);
                orb.setExperience(got);
            } catch (RuntimeException ex) { p.giveExp(got); }
        }
        return n;
    }
}
