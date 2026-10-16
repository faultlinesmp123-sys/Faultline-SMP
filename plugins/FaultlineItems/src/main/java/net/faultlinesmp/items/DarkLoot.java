package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * DARK LOOT (Items 1.2.41, config dark-loot:): the treasure of the Dark Ocean (FaultlineDeep).
 *  - CHESTS: every chest in the Dark Ocean world that a player didn't place (FaultlineDeep's terrain hides them in the
 *    Crust's caves and the abyss's ocean caves) rolls Dark Loot the first time it's opened. Placed chests are marked
 *    (block PDC faultlineitems:dark_loot_placed) and never roll; a rolled one is marked dark_loot_done.
 *  - THE DARK LOOT BAG (paper, model faultline:dark_loot_bag, PDC dark_loot_bag): right-click = one roll of Dark Loot.
 *    The Dark Ocean's creatures drop it now and then (FaultlineDeep: /darkloot bag 1 <player>).
 *  - A ROLL: accessories, netherite ingots, enchanted books (Protection IV and the like), sometimes a boss enchant book,
 *    sea treasure, and a dark-loot.rare-chance (0.1%) shot at a RARE: the Abyssal Diving Suit, an Elytra, a shulker box of
 *    Totems of Undying, a shulker box of Invisibility potions, or a God Set (maxed netherite armour, sword, pickaxe, axe,
 *    bow and trident). The deep bosses roll rares too: /darkloot rare <player> <chance> (mini bosses 10%, bosses 50%).
 */
final class DarkLoot implements Listener, CommandExecutor {

    static final List<String> RARES = List.of("suit", "elytra", "totems", "invisibility", "godset");
    /** Accessories too strong (or too story-bound) for a chest. */
    static final List<String> NOT_IN_CHESTS = List.of("lostmirror", "ankh", "ankhpiece", "abyssalsuit", "abyssalgear", "weirdclock");

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey bagKey, placedKey, doneKey;

    DarkLoot(FaultlineItems plugin) {
        this.plugin = plugin;
        bagKey = new NamespacedKey(plugin, "dark_loot_bag");
        placedKey = new NamespacedKey(plugin, "dark_loot_placed");
        doneKey = new NamespacedKey(plugin, "dark_loot_done");
    }

    double cfg(String path, double def) { return plugin.getConfig().getDouble("dark-loot." + path, def); }

    String worldName() { return plugin.getConfig().getString("dark-loot.world", "faultline_deep"); }

    // =========================================================================================== the bag
    ItemStack bag() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Dark Loot Bag");
        m.setLore(List.of(ChatColor.GRAY + "Right-click to open.",
                ChatColor.DARK_AQUA + "Something the Dark Ocean was keeping.",
                ChatColor.DARK_GRAY + "Accessories, netherite, books... and, rarely,",
                ChatColor.DARK_GRAY + "something nobody should have."));
        m.setItemModel(new NamespacedKey("faultline", "dark_loot_bag"));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(16);
        m.getPersistentDataContainer().set(bagKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isBag(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(bagKey, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBag(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (!isBag(hand)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        hand.setAmount(hand.getAmount() - 1);
        List<ItemStack> got = roll();
        for (ItemStack it : got) p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 0.6f);
        p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, 1f, 1f);
        p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0.05);
        p.sendMessage(ChatColor.DARK_AQUA + "Dark Loot: " + ChatColor.GRAY + describe(got));
    }

    static String describe(List<ItemStack> items) {
        List<String> names = new ArrayList<>();
        for (ItemStack it : items) {
            String n = it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? ChatColor.stripColor(it.getItemMeta().getDisplayName())
                    : it.getType().name().toLowerCase().replace('_', ' ');
            names.add((it.getAmount() > 1 ? it.getAmount() + " " : "") + n);
        }
        return String.join(", ", names);
    }

    // =========================================================================================== a roll
    static final Object[][] BOOKS = {
            {Enchantment.PROTECTION, 4}, {Enchantment.SHARPNESS, 5}, {Enchantment.EFFICIENCY, 5}, {Enchantment.UNBREAKING, 3},
            {Enchantment.MENDING, 1}, {Enchantment.FEATHER_FALLING, 4}, {Enchantment.DEPTH_STRIDER, 3}, {Enchantment.RESPIRATION, 3},
            {Enchantment.AQUA_AFFINITY, 1}, {Enchantment.POWER, 5}, {Enchantment.LOOTING, 3}, {Enchantment.FORTUNE, 3},
            {Enchantment.SILK_TOUCH, 1}, {Enchantment.RIPTIDE, 3}, {Enchantment.LOYALTY, 3}, {Enchantment.IMPALING, 5}};

    List<ItemStack> roll() {
        List<ItemStack> out = new ArrayList<>();
        // accessories
        int acc = random.nextDouble() < cfg("accessory-chance", 0.35) ? 1 + (random.nextDouble() < 0.2 ? 1 : 0) : 0;
        for (int i = 0; i < acc; i++) { ItemStack a = accessory(); if (a != null) out.add(a); }
        if (random.nextDouble() < cfg("netherite-chance", 0.4)) out.add(new ItemStack(Material.NETHERITE_INGOT, 1 + random.nextInt((int) cfg("netherite-max", 2))));
        int books = random.nextDouble() < cfg("book-chance", 0.6) ? 1 + (random.nextDouble() < 0.3 ? 1 : 0) : 0;
        for (int i = 0; i < books; i++) {
            Object[] b = BOOKS[random.nextInt(BOOKS.length)];
            out.add(book((Enchantment) b[0], (int) b[1]));
        }
        if (plugin.enchants != null && random.nextDouble() < cfg("boss-book-chance", 0.12)) {
            List<String> ids = new ArrayList<>(Enchants.DEFS.keySet());
            ItemStack bb = plugin.enchants.book(ids.get(random.nextInt(ids.size())), 1);
            if (bb != null) out.add(bb);
        }
        // the sea's own treasure
        if (random.nextDouble() < 0.5) out.add(new ItemStack(Material.DIAMOND, 1 + random.nextInt(4)));
        if (random.nextDouble() < 0.6) out.add(new ItemStack(Material.EMERALD, 4 + random.nextInt(9)));
        if (random.nextDouble() < 0.4) out.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 4 + random.nextInt(9)));
        if (random.nextDouble() < 0.3) out.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 4 + random.nextInt(8)));
        if (random.nextDouble() < 0.2) out.add(new ItemStack(Material.NAUTILUS_SHELL, 1 + random.nextInt(2)));
        if (random.nextDouble() < 0.2) out.add(new ItemStack(Material.GOLDEN_APPLE, 1 + random.nextInt(2)));
        if (random.nextDouble() < 0.03) out.add(new ItemStack(Material.HEART_OF_THE_SEA, 1));
        if (random.nextDouble() < cfg("rare-chance", 0.001)) out.addAll(rare(RARES.get(random.nextInt(RARES.size()))));
        if (out.isEmpty()) out.add(new ItemStack(Material.PRISMARINE_SHARD, 3 + random.nextInt(6)));
        return out;
    }

    ItemStack accessory() {
        List<String> names = new ArrayList<>();
        for (String k : FaultlineItems.ItemCatalog.ACCESSORY_TYPES.keySet())
            if (!NOT_IN_CHESTS.contains(k) && !plugin.getConfig().getStringList("dark-loot.never-accessories").contains(k)) names.add(k);
        if (names.isEmpty()) return null;
        Function<FaultlineItems, ItemStack> f = FaultlineItems.ItemCatalog.ACCESSORY_TYPES.get(names.get(random.nextInt(names.size())));
        try { return f.apply(plugin); } catch (RuntimeException e) { return null; }
    }

    static ItemStack book(Enchantment ench, int level) {
        ItemStack b = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta m = (EnchantmentStorageMeta) b.getItemMeta();
        m.addStoredEnchant(ench, level, true);
        b.setItemMeta(m);
        return b;
    }

    /** One of the 0.1% treasures. */
    List<ItemStack> rare(String which) {
        List<ItemStack> out = new ArrayList<>();
        switch (which) {
            case "suit" -> out.add(plugin.getDivingGear().item(DivingGear.Piece.SUIT));
            case "elytra" -> out.add(new ItemStack(Material.ELYTRA));
            case "totems" -> out.add(shulker(Material.CYAN_SHULKER_BOX, ChatColor.GOLD + "" + ChatColor.BOLD + "Shulker of Totems", new ItemStack(Material.TOTEM_OF_UNDYING)));
            case "invisibility" -> {
                ItemStack pot = new ItemStack(Material.POTION);
                PotionMeta pm = (PotionMeta) pot.getItemMeta();
                pm.setBasePotionType(PotionType.LONG_INVISIBILITY);
                pot.setItemMeta(pm);
                out.add(shulker(Material.LIGHT_GRAY_SHULKER_BOX, ChatColor.GRAY + "" + ChatColor.BOLD + "Shulker of Invisibility", pot));
            }
            case "godset" -> out.addAll(godSet());
            default -> { }
        }
        return out;
    }

    static ItemStack shulker(Material box, String name, ItemStack fill) {
        ItemStack it = new ItemStack(box);
        BlockStateMeta m = (BlockStateMeta) it.getItemMeta();
        if (m.getBlockState() instanceof ShulkerBox sb) {
            for (int i = 0; i < 27; i++) sb.getInventory().setItem(i, fill.clone());
            m.setBlockState(sb);
        }
        m.setDisplayName(name);
        it.setItemMeta(m);
        return it;
    }

    static List<ItemStack> godSet() {
        List<ItemStack> out = new ArrayList<>();
        out.add(god(Material.NETHERITE_HELMET, "God Helmet", Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1)));
        out.add(god(Material.NETHERITE_CHESTPLATE, "God Chestplate", Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        out.add(god(Material.NETHERITE_LEGGINGS, "God Leggings", Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        out.add(god(Material.NETHERITE_BOOTS, "God Boots", Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.FEATHER_FALLING, 4, Enchantment.DEPTH_STRIDER, 3)));
        out.add(god(Material.NETHERITE_SWORD, "God Sword", Map.of(Enchantment.SHARPNESS, 5, Enchantment.LOOTING, 3, Enchantment.FIRE_ASPECT, 2, Enchantment.SWEEPING_EDGE, 3, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        out.add(god(Material.NETHERITE_PICKAXE, "God Pickaxe", Map.of(Enchantment.EFFICIENCY, 5, Enchantment.FORTUNE, 3, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        out.add(god(Material.NETHERITE_AXE, "God Axe", Map.of(Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        out.add(god(Material.BOW, "God Bow", Map.of(Enchantment.POWER, 5, Enchantment.PUNCH, 2, Enchantment.FLAME, 1, Enchantment.INFINITY, 1, Enchantment.UNBREAKING, 3)));
        out.add(god(Material.TRIDENT, "God Trident", Map.of(Enchantment.LOYALTY, 3, Enchantment.IMPALING, 5, Enchantment.CHANNELING, 1, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)));
        return out;
    }

    static ItemStack god(Material m, String name, Map<Enchantment, Integer> ench) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
        meta.setLore(List.of(ChatColor.DARK_AQUA + "Buried treasure from the Dark Ocean."));
        for (var e : ench.entrySet()) meta.addEnchant(e.getKey(), e.getValue(), true);
        it.setItemMeta(meta);
        return it;
    }

    // =========================================================================================== chests
    boolean deep(Block b) { return b.getWorld().getName().equals(worldName()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!deep(b) || !(b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST || b.getType() == Material.BARREL)) return;
        Bukkit.getScheduler().runTask(plugin, () -> mark(b, placedKey));
    }

    void mark(Block b, NamespacedKey key) {
        BlockState s = b.getState();
        if (!(s instanceof TileState ts)) return;
        ts.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        ts.update(true, false);
    }

    boolean has(Block b, NamespacedKey key) { return b.getState() instanceof TileState ts && ts.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        List<Chest> sides = new ArrayList<>();
        if (h instanceof Chest c) sides.add(c);
        else if (h instanceof DoubleChest dc) { if (dc.getLeftSide() instanceof Chest l) sides.add(l); if (dc.getRightSide() instanceof Chest r) sides.add(r); }
        if (sides.isEmpty() || !deep(sides.get(0).getBlock())) return;
        boolean rolled = false;
        for (Chest c : sides) {
            Block b = c.getBlock();
            if (has(b, placedKey) || has(b, doneKey)) continue;
            mark(b, doneKey);
            fill(c.getBlock().getState() instanceof Chest fresh ? fresh.getBlockInventory() : c.getBlockInventory());
            rolled = true;
        }
        if (rolled && e.getPlayer() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, 1f, 1.2f);
            p.sendActionBar(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Dark Loot!");
            p.getWorld().spawnParticle(Particle.BUBBLE_POP, sides.get(0).getLocation().add(0.5, 1, 0.5), 30, 0.4, 0.3, 0.4, 0.05);
        }
    }

    void fill(Inventory inv) {
        List<ItemStack> loot = roll();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) free.add(i);
        java.util.Collections.shuffle(free, random);
        int i = 0;
        for (ItemStack it : loot) { if (i >= free.size()) break; inv.setItem(free.get(i++), it); }
    }

    // =========================================================================================== /darkloot
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.darkloot")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        Player self = sender instanceof Player p ? p : null;
        switch (sub) {
            case "bag" -> { // /darkloot bag [amount] [player]
                int n = 1; Player to = self;
                for (int i = 1; i < args.length; i++) { Player o = Bukkit.getPlayerExact(args[i]); if (o != null) to = o; else try { n = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { } }
                if (to == null) return false;
                ItemStack b = bag(); b.setAmount(n);
                Player dest = to;
                dest.getInventory().addItem(b).values().forEach(l -> dest.getWorld().dropItemNaturally(dest.getLocation(), l));
                if (sender != dest && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) sender.sendMessage(ChatColor.GREEN + "Gave " + n + " Dark Loot Bag(s) to " + dest.getName() + ".");
                else if (sender instanceof org.bukkit.command.ConsoleCommandSender) dest.sendMessage(ChatColor.DARK_AQUA + "A Dark Loot Bag washes up for you.");
            }
            case "rare" -> { // /darkloot rare <player> [chance]: a boss's shot at a rare
                Player to = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (to == null) return false;
                double chance = 1;
                if (args.length > 2) try { chance = Double.parseDouble(args[2]); } catch (NumberFormatException ignored) { }
                if (random.nextDouble() >= chance) return true;
                String which = RARES.get(random.nextInt(RARES.size()));
                List<ItemStack> got = rare(which);
                Player dest = to;
                for (ItemStack it : got) dest.getInventory().addItem(it).values().forEach(l -> dest.getWorld().dropItemNaturally(dest.getLocation(), l));
                Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "✦ " + dest.getName() + ChatColor.AQUA + " pulled a rare from the Dark Ocean: " + ChatColor.GOLD + describe(got));
            }
            case "give" -> { // /darkloot give <suit|elytra|totems|invisibility|godset> [player]
                String which = args.length > 1 ? args[1].toLowerCase() : "";
                if (!RARES.contains(which)) { sender.sendMessage(ChatColor.YELLOW + "/darkloot give <" + String.join("|", RARES) + "> [player]"); return true; }
                Player to = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : self;
                if (to == null) return false;
                Player dest = to;
                for (ItemStack it : rare(which)) dest.getInventory().addItem(it).values().forEach(l -> dest.getWorld().dropItemNaturally(dest.getLocation(), l));
            }
            case "roll" -> {
                Player to = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (to == null) return false;
                List<ItemStack> got = roll();
                Player dest = to;
                for (ItemStack it : got) dest.getInventory().addItem(it).values().forEach(l -> dest.getWorld().dropItemNaturally(dest.getLocation(), l));
                sender.sendMessage(ChatColor.DARK_AQUA + "Rolled: " + ChatColor.GRAY + describe(got));
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/darkloot <bag [amount] [player]|rare <player> [chance]|give <rare> [player]|roll [player]>");
        }
        return true;
    }
}
