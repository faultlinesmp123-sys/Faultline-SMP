package net.faultlinesmp.skills;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * FAULTLINE SKILLS (1.0.0): four skill trees, MINING, BUILDING, FARMING and REDSTONE (Tree.java). Each levels 1-50 from
 * what you do (xp to go from level L to L+1 = `xp.curve` 100 x L^1.4), gives a skill point a level in that tree, and
 * spends them on six perks in order (each needs the one before it and a tree level).
 * ANTI-ABUSE: nothing counts in creative. Blocks players place that could be mined/harvested again for XP (stone, ores,
 * logs, melons, pumpkins, cane, cactus, bamboo...) are marked in the chunk PDC (`faultlineskills:placed`, packed local
 * positions, moved with pistons) and give no Mining/Farming XP. Building and Redstone XP is paid `building.settle-seconds`
 * (60) after a block is placed, only if it's still there (no place/break loops), capped per hour; crops only count fully
 * grown; breeding and crafting are capped per hour / day.
 * /skills (a chest menu, a Bedrock form), /skills top <tree>, admin /skills give|reset. Index stat `skill_<tree>` +1 a level.
 */
public final class FaultlineSkills extends JavaPlugin implements Listener {

    static FaultlineSkills instance;
    final Random random = new Random();
    final Map<UUID, Data> data = new HashMap<>();
    final ArrayDeque<Pending> pending = new ArrayDeque<>();
    final Map<UUID, Long> barAt = new HashMap<>();
    final Map<Location, Boolean> naturalBreak = new HashMap<>(); // BlockBreakEvent -> BlockDropItemEvent
    final Set<UUID> sweeping = new HashSet<>();
    NamespacedKey placedKey, reachKey, speedKey;
    File file;
    int ticks;

    static final class Data {
        final double[] xp = new double[Tree.values().length];
        final Set<String> perks = new HashSet<>();
        String name = "?";
        long hour; int builtThisHour, bredThisHour;
        long day; int craftedToday;
    }

    record Pending(UUID who, Block block, Material type, long at, Tree tree, double xp) { }

    static final Set<Material> REDSTONE = Set.of(Material.REDSTONE_WIRE, Material.REDSTONE, Material.REPEATER, Material.COMPARATOR, Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH, Material.PISTON, Material.STICKY_PISTON, Material.OBSERVER, Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.REDSTONE_LAMP, Material.LEVER, Material.TARGET, Material.DAYLIGHT_DETECTOR, Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL, Material.TRIPWIRE_HOOK, Material.REDSTONE_BLOCK, Material.NOTE_BLOCK, Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR,
            Material.CRAFTER, Material.LIGHTNING_ROD, Material.TRAPPED_CHEST, Material.SLIME_BLOCK, Material.HONEY_BLOCK);
    static final Map<Material, Double> ORES = new HashMap<>();
    static {
        ORES.put(Material.COAL_ORE, 2.0); ORES.put(Material.COPPER_ORE, 2.0); ORES.put(Material.IRON_ORE, 4.0); ORES.put(Material.GOLD_ORE, 6.0);
        ORES.put(Material.REDSTONE_ORE, 4.0); ORES.put(Material.LAPIS_ORE, 5.0); ORES.put(Material.DIAMOND_ORE, 12.0); ORES.put(Material.EMERALD_ORE, 15.0);
        ORES.put(Material.DEEPSLATE_COAL_ORE, 3.0); ORES.put(Material.DEEPSLATE_COPPER_ORE, 3.0); ORES.put(Material.DEEPSLATE_IRON_ORE, 5.0);
        ORES.put(Material.DEEPSLATE_GOLD_ORE, 7.0); ORES.put(Material.DEEPSLATE_REDSTONE_ORE, 5.0); ORES.put(Material.DEEPSLATE_LAPIS_ORE, 6.0);
        ORES.put(Material.DEEPSLATE_DIAMOND_ORE, 13.0); ORES.put(Material.DEEPSLATE_EMERALD_ORE, 16.0); ORES.put(Material.NETHER_GOLD_ORE, 3.0);
        ORES.put(Material.NETHER_QUARTZ_ORE, 2.0); ORES.put(Material.ANCIENT_DEBRIS, 20.0); ORES.put(Material.AMETHYST_CLUSTER, 2.0);
    }
    static final Set<Material> STONE = Set.of(Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
            Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT, Material.CALCITE, Material.END_STONE, Material.SANDSTONE, Material.DRIPSTONE_BLOCK);
    static final Set<Material> GROWN = Set.of(Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.COCOA);
    static final Set<Material> FARM_FOOD = Set.of(Material.BREAD, Material.BAKED_POTATO, Material.CARROT, Material.BEETROOT, Material.BEETROOT_SOUP,
            Material.PUMPKIN_PIE, Material.MELON_SLICE, Material.APPLE, Material.COOKIE, Material.GOLDEN_CARROT, Material.SWEET_BERRIES, Material.POTATO);

    // =========================================================================================== lifecycle
    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        placedKey = new NamespacedKey(this, "placed");
        reachKey = new NamespacedKey(this, "reach");
        speedKey = new NamespacedKey(this, "quick_builder");
        file = new File(getDataFolder(), "players.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) strip(p);
        save();
    }

    double cfg(String path, double def) { return getConfig().getDouble(path, def); }

    Data data(UUID id) { return data.computeIfAbsent(id, k -> new Data()); }

    // =========================================================================================== levels
    double need(int level) { return cfg("xp.base", 100) * Math.pow(level, cfg("xp.exponent", 1.4)); }

    int max() { return (int) cfg("max-level", 50); }

    int level(double xp) {
        int l = 1;
        while (l < max() && xp >= need(l)) { xp -= need(l); l++; }
        return l;
    }

    /** XP into the current level, and the level's size. */
    double[] progress(double xp) {
        int l = 1;
        while (l < max() && xp >= need(l)) { xp -= need(l); l++; }
        return new double[]{xp, l >= max() ? 1 : need(l)};
    }

    int level(UUID id, Tree t) { return level(data(id).xp[t.ordinal()]); }

    boolean has(Player p, String perk) { return data(p.getUniqueId()).perks.contains(perk); }

    int spent(Data d, Tree t) { int s = 0; for (Tree.Perk k : t.perks) if (d.perks.contains(k.id())) s += k.cost(); return s; }

    int points(UUID id, Tree t) { Data d = data(id); return level(d.xp[t.ordinal()]) - spent(d, t); }

    static boolean counts(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    void gain(Player p, Tree t, double amount) {
        if (amount <= 0 || !counts(p) || !getConfig().getBoolean("trees." + t.key() + ".enabled", true)) return;
        amount *= cfg("trees." + t.key() + ".multiplier", 1.0);
        Data d = data(p.getUniqueId());
        d.name = p.getName();
        int before = level(d.xp[t.ordinal()]);
        d.xp[t.ordinal()] += amount;
        int after = level(d.xp[t.ordinal()]);
        if (after > before) {
            p.sendTitle(t.color + t.title + " " + after, ChatColor.GRAY + "+" + (after - before) + " skill point" + (after - before > 1 ? "s" : "") + "  (/skills)", 5, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            for (Tree.Perk k : t.perks) if (k.level() > before && k.level() <= after)
                p.sendMessage(t.color + "" + ChatColor.BOLD + k.name() + ChatColor.GRAY + " can be unlocked now. " + ChatColor.YELLOW + "/skills");
            dispatch("index stat " + p.getName() + " skill_" + t.key() + " " + (after - before));
        }
        long now = System.currentTimeMillis();
        if (now - barAt.getOrDefault(p.getUniqueId(), 0L) > 1500) {
            barAt.put(p.getUniqueId(), now);
            double[] pr = progress(d.xp[t.ordinal()]);
            p.sendActionBar(t.color + "+" + fmt(amount) + " " + t.title + ChatColor.GRAY + "  Lvl " + after + "  " + (int) (100 * pr[0] / pr[1]) + "%");
        }
    }

    static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v); }

    void dispatch(String cmd) {
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") == null) return;
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (RuntimeException ignored) { }
    }

    // =========================================================================================== placed-block marks
    static int local(Block b) { return ((b.getX() & 15) << 16) | ((b.getZ() & 15) << 12) | ((b.getY() + 2048) & 4095); }

    static boolean tracked(Material m) { return ORES.containsKey(m) || STONE.contains(m) || GROWN.contains(m) || m.name().endsWith("_LOG") || m.name().endsWith("_STEM") || m == Material.COBBLESTONE; }

    boolean placed(Block b) {
        int[] a = b.getChunk().getPersistentDataContainer().get(placedKey, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return false;
        int me = local(b);
        for (int v : a) if (v == me) return true;
        return false;
    }

    void mark(Block b, boolean on) {
        Chunk c = b.getChunk();
        int[] a = c.getPersistentDataContainer().getOrDefault(placedKey, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int me = local(b);
        int at = -1;
        for (int i = 0; i < a.length; i++) if (a[i] == me) { at = i; break; }
        if (on && at < 0) {
            int[] n = Arrays.copyOf(a, a.length + 1);
            n[a.length] = me;
            c.getPersistentDataContainer().set(placedKey, PersistentDataType.INTEGER_ARRAY, n);
        } else if (!on && at >= 0) {
            int[] n = new int[a.length - 1];
            System.arraycopy(a, 0, n, 0, at);
            System.arraycopy(a, at + 1, n, at, a.length - at - 1);
            if (n.length == 0) c.getPersistentDataContainer().remove(placedKey);
            else c.getPersistentDataContainer().set(placedKey, PersistentDataType.INTEGER_ARRAY, n);
        }
    }

    void movePlaced(List<Block> blocks, org.bukkit.block.BlockFace dir) {
        List<Block> was = new ArrayList<>();
        for (Block b : blocks) if (tracked(b.getType()) && placed(b)) was.add(b);
        for (Block b : was) mark(b, false);
        for (Block b : was) mark(b.getRelative(dir), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { movePlaced(e.getBlocks(), e.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { movePlaced(e.getBlocks(), e.getDirection()); }

    // =========================================================================================== building + redstone
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (tracked(b.getType())) mark(b, true);
        if (!counts(p)) return;
        boolean red = REDSTONE.contains(b.getType());
        pending.add(new Pending(p.getUniqueId(), b, b.getType(), ticks, red ? Tree.REDSTONE : Tree.BUILDING, red ? cfg("redstone.place-xp", 3) : cfg("building.place-xp", 1)));
        // not used up: Thrifty / Master Builder, Wired (dust), Spare Parts (components). Plain items only (no shulker contents, no custom items)
        ItemStack hand = e.getItemInHand();
        if (hand.hasItemMeta()) return;
        double chance = 0;
        if (red) {
            if (b.getType() == Material.REDSTONE_WIRE && has(p, "wired")) chance = 0.10;
            if (has(p, "spare_parts")) chance = Math.max(chance, 0.25);
        } else chance = has(p, "master_builder") ? 0.12 : has(p, "thrifty") ? 0.05 : 0;
        if (chance > 0 && random.nextDouble() < chance) {
            ItemStack back = new ItemStack(hand.getType(), 1);
            Bukkit.getScheduler().runTask(this, () -> p.getInventory().addItem(back).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x)));
        }
    }

    /** Every second: blocks placed `settle-seconds` ago that are still there pay out (capped per hour). */
    void settle() {
        int settle = (int) cfg("building.settle-seconds", 60);
        long hour = System.currentTimeMillis() / 3_600_000;
        while (!pending.isEmpty() && ticks - pending.peek().at() >= settle) {
            Pending pd = pending.poll();
            Player p = Bukkit.getPlayer(pd.who());
            Block b = pd.block();
            if (p == null || b.getWorld().getBlockAt(b.getX(), b.getY(), b.getZ()).getType() != pd.type()) continue;
            Data d = data(pd.who());
            if (d.hour != hour) { d.hour = hour; d.builtThisHour = 0; d.bredThisHour = 0; }
            if (d.builtThisHour >= cfg("building.max-per-hour", 2500)) continue;
            d.builtThisHour++;
            gain(p, pd.tree(), pd.xp());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !counts(p)) return;
        ItemStack r = e.getRecipe().getResult();
        if (!REDSTONE.contains(r.getType())) return;
        Data d = data(p.getUniqueId());
        long day = System.currentTimeMillis() / 86_400_000;
        if (d.day != day) { d.day = day; d.craftedToday = 0; }
        if (d.craftedToday < cfg("redstone.craft-per-day", 64)) {
            d.craftedToday += r.getAmount();
            gain(p, Tree.REDSTONE, cfg("redstone.craft-xp", 2) * r.getAmount());
        }
        if (has(p, "tinkerer") && !e.isShiftClick() && random.nextDouble() < 0.10)
            Bukkit.getScheduler().runTask(this, () -> p.getInventory().addItem(new ItemStack(r.getType(), 1)).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x)));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEye(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        if (!p.getInventory().getItemInMainHand().getType().isAir() || !has(p, "engineer_eye")) return;
        Block b = e.getClickedBlock();
        if (!REDSTONE.contains(b.getType())) return;
        BlockData bd = b.getBlockData();
        StringBuilder s = new StringBuilder(ChatColor.RED + b.getType().name().toLowerCase().replace('_', ' ') + ChatColor.GRAY + "  power " + b.getBlockPower());
        if (bd instanceof org.bukkit.block.data.AnaloguePowerable ap) s.append("  signal ").append(ap.getPower());
        if (bd instanceof Repeater r) s.append("  delay ").append(r.getDelay()).append(r.isLocked() ? " (locked)" : "");
        if (bd instanceof Comparator c) s.append("  mode ").append(c.getMode().name().toLowerCase());
        if (bd instanceof Powerable pw) s.append(pw.isPowered() ? "  ON" : "  off");
        p.sendActionBar(s.toString());
    }

    @EventHandler(ignoreCancelled = true)
    public void onRide(VehicleEnterEvent e) {
        if (e.getVehicle() instanceof Minecart m && e.getEntered() instanceof Player p)
            m.setMaxSpeed(0.4 * (has(p, "master_engineer") ? 2 : has(p, "rail_rider") ? 1.5 : 1));
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeave(VehicleExitEvent e) { if (e.getVehicle() instanceof Minecart m) m.setMaxSpeed(0.4); }

    // =========================================================================================== mining + farming
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        Material m = b.getType();
        boolean wasPlaced = tracked(m) && placed(b);
        if (wasPlaced) mark(b, false);
        if (!counts(p)) return;
        naturalBreak.put(b.getLocation(), !wasPlaced);
        if (wasPlaced) return;
        if (ORES.containsKey(m)) gain(p, Tree.MINING, ORES.get(m));
        else if (STONE.contains(m)) {
            gain(p, Tree.MINING, cfg("mining.stone-xp", 0.2));
            if (has(p, "quick_hands")) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, true, false, true));
        }
        if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() && m != Material.SUGAR_CANE && m != Material.CACTUS && m != Material.BAMBOO) {
            gain(p, Tree.FARMING, m == Material.NETHER_WART ? 2 : cfg("farming.crop-xp", 2));
            ItemStack tool = p.getInventory().getItemInMainHand();
            boolean hoe = tool.getType().name().endsWith("_HOE");
            if (hoe && has(p, "replanter") && m != Material.COCOA) {
                BlockData fresh = b.getBlockData().clone();
                ((Ageable) fresh).setAge(0);
                Bukkit.getScheduler().runTask(this, () -> { if (b.getType().isAir()) b.setBlockData(fresh); });
            }
            if (hoe && has(p, "sweep") && sweeping.add(p.getUniqueId())) {
                try {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Block o = b.getRelative(dx, 0, dz);
                        if (o.getType() == m && o.getBlockData() instanceof Ageable oa && oa.getAge() >= oa.getMaximumAge()) p.breakBlock(o);
                    }
                } finally { sweeping.remove(p.getUniqueId()); }
            }
        } else if (GROWN.contains(m) && m != Material.COCOA) gain(p, Tree.FARMING, m == Material.MELON || m == Material.PUMPKIN ? 3 : 0.5);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Boolean natural = naturalBreak.remove(e.getBlock().getLocation());
        Player p = e.getPlayer();
        if (natural == null || !natural) return;
        Material m = e.getBlockState().getType();
        boolean crop = e.getBlockState().getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() || m == Material.MELON || m == Material.PUMPKIN;
        if (ORES.containsKey(m)) {
            double lucky = has(p, "master_miner") ? 0.15 : has(p, "lucky_strike") ? 0.05 : 0;
            boolean silk = p.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
            if (!silk && lucky > 0 && random.nextDouble() < lucky) for (Item it : new ArrayList<>(e.getItems()))
                e.getBlock().getWorld().dropItemNaturally(it.getLocation(), it.getItemStack().clone());
            if (has(p, "smelter")) for (Item it : e.getItems()) {
                Material raw = it.getItemStack().getType();
                Material ingot = raw == Material.RAW_IRON ? Material.IRON_INGOT : raw == Material.RAW_GOLD ? Material.GOLD_INGOT : raw == Material.RAW_COPPER ? Material.COPPER_INGOT : null;
                if (ingot != null && random.nextDouble() < 0.10) it.setItemStack(new ItemStack(ingot, it.getItemStack().getAmount()));
            }
        }
        if (crop && !e.getItems().isEmpty()) {
            double extra = has(p, "bountiful") ? 0.20 : has(p, "green_fingers") ? 0.10 : 0;
            if (extra > 0 && random.nextDouble() < extra) {
                Item first = e.getItems().get(0);
                ItemStack one = first.getItemStack().clone(); one.setAmount(1);
                e.getBlock().getWorld().dropItemNaturally(first.getLocation(), one);
            }
            if (has(p, "replanter") && p.getInventory().getItemInMainHand().getType().name().endsWith("_HOE")) { // the replanted seed comes out of the drops
                Material seed = seed(m);
                for (Item it : e.getItems()) if (it.getItemStack().getType() == seed) {
                    ItemStack s = it.getItemStack();
                    if (s.getAmount() > 1) { s.setAmount(s.getAmount() - 1); it.setItemStack(s); } else it.remove();
                    break;
                }
            }
        }
    }

    static Material seed(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> Material.AIR;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p) || !counts(p)) return;
        Data d = data(p.getUniqueId());
        long hour = System.currentTimeMillis() / 3_600_000;
        if (d.hour != hour) { d.hour = hour; d.builtThisHour = 0; d.bredThisHour = 0; }
        if (d.bredThisHour++ < cfg("farming.breeds-per-hour", 60)) gain(p, Tree.FARMING, cfg("farming.breed-xp", 5));
        if (has(p, "whisperer")) {
            e.setExperience(e.getExperience() * 2);
            if (random.nextDouble() < 0.10 && e.getEntity() instanceof org.bukkit.entity.Ageable baby) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (baby.getWorld().spawnEntity(baby.getLocation(), baby.getType()) instanceof org.bukkit.entity.Ageable twin) twin.setBaby();
                });
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (has(p, "master_farmer") && FARM_FOOD.contains(e.getItem().getType()))
            Bukkit.getScheduler().runTask(this, () -> p.setFoodLevel(Math.min(20, p.getFoodLevel() + 2)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent e) {
        if (e.getItem().getType().name().endsWith("_PICKAXE") && has(e.getPlayer(), "tough_picks") && random.nextDouble() < 0.20) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p) || !has(p, "featherweight")) return;
        if (p.getInventory().getItemInMainHand().getType().isBlock() && !p.getInventory().getItemInMainHand().getType().isAir() && p.getFallDistance() <= 8) e.setCancelled(true);
    }

    // =========================================================================================== every second
    void tick() {
        ticks++;
        settle();
        if (naturalBreak.size() > 512) naturalBreak.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Data d = data.get(p.getUniqueId());
            if (d == null || d.perks.isEmpty()) continue;
            Location l = p.getLocation();
            ItemStack hand = p.getInventory().getItemInMainHand();
            boolean overworld = p.getWorld().getEnvironment() == World.Environment.NORMAL;
            if (d.perks.contains("night_eyes") && overworld && l.getY() < 0 && !p.hasMetadata("faultline_abyss_dark")) {
                PotionEffect nv = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (nv == null || nv.isAmbient() && nv.getDuration() < 260) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, true));
            }
            if (d.perks.contains("master_miner") && hand.getType().name().endsWith("_PICKAXE") && l.getY() < 40)
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 50, 1, true, false, true));
            if (d.perks.contains("master_engineer") && REDSTONE.contains(hand.getType()))
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 50, 1, true, false, true));
            double reach = d.perks.contains("master_builder") ? 3 : d.perks.contains("long_reach") ? 2 : d.perks.contains("reach") ? 1 : 0;
            modifier(p, Attribute.BLOCK_INTERACTION_RANGE, reachKey, reach, AttributeModifier.Operation.ADD_NUMBER);
            boolean block = hand.getType().isBlock() && !hand.getType().isAir();
            modifier(p, Attribute.MOVEMENT_SPEED, speedKey, d.perks.contains("quick_builder") && block ? 0.10 : 0, AttributeModifier.Operation.ADD_SCALAR);
        }
    }

    /** Puts (or takes off, at 0) our modifier, replacing one whose amount changed. */
    static void modifier(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance ai = p.getAttribute(a);
        if (ai == null) return;
        AttributeModifier cur = ai.getModifier(key);
        if (cur != null && (amount == 0 || cur.getAmount() != amount)) { ai.removeModifier(key); cur = null; }
        if (cur == null && amount != 0) ai.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    void strip(Player p) {
        modifier(p, Attribute.BLOCK_INTERACTION_RANGE, reachKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        modifier(p, Attribute.MOVEMENT_SPEED, speedKey, 0, AttributeModifier.Operation.ADD_SCALAR);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { strip(e.getPlayer()); }

    // =========================================================================================== unlocking
    /** Why a perk can't be unlocked, or null (and it's unlocked). */
    String unlock(Player p, Tree t, int i) {
        Data d = data(p.getUniqueId());
        Tree.Perk k = t.perks.get(i);
        if (d.perks.contains(k.id())) return "You already have " + k.name() + ".";
        if (i > 0 && !d.perks.contains(t.perks.get(i - 1).id())) return "Unlock " + t.perks.get(i - 1).name() + " first.";
        int lvl = level(d.xp[t.ordinal()]);
        if (lvl < k.level()) return k.name() + " needs " + t.title + " level " + k.level() + " (you're " + lvl + ").";
        int pts = lvl - spent(d, t);
        if (pts < k.cost()) return k.name() + " costs " + k.cost() + " points (you have " + pts + ").";
        d.perks.add(k.id());
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.sendMessage(t.color + "" + ChatColor.BOLD + k.name() + ChatColor.GRAY + " unlocked: " + k.about());
        dispatch("index stat " + p.getName() + " skill_perks 1");
        save();
        return null;
    }

    // =========================================================================================== the menu
    static final class Menu implements InventoryHolder {
        final UUID who; final boolean mine; Inventory inv;
        Menu(UUID who, boolean mine) { this.who = who; this.mine = mine; }
        @Override public Inventory getInventory() { return inv; }
    }

    void open(Player viewer, UUID who) {
        boolean mine = viewer.getUniqueId().equals(who);
        if (mine && getConfig().getBoolean("bedrock-forms", true) && Edition.bedrock(viewer) && BedrockForms.available()) { forms(viewer); return; }
        Menu m = new Menu(who, mine);
        Inventory inv = Bukkit.createInventory(m, 54, ChatColor.DARK_GRAY + "Skills" + (mine ? "" : " - " + data(who).name));
        m.inv = inv;
        Data d = data(who);
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        int row = 0;
        for (Tree t : Tree.values()) {
            int base = 9 * (row + (row >= 2 ? 1 : 0)); // rows 0, 1, 3, 4 (row 2 = a gap)
            double[] pr = progress(d.xp[t.ordinal()]);
            int lvl = level(d.xp[t.ordinal()]);
            inv.setItem(base, icon(t.icon, t.color + "" + ChatColor.BOLD + t.title + " " + lvl, List.of(
                    ChatColor.GRAY + t.how, bar(pr[0] / pr[1]) + ChatColor.GRAY + " " + (lvl >= max() ? "MAX" : (int) pr[0] + "/" + (int) pr[1]),
                    ChatColor.YELLOW + "Points: " + (lvl - spent(d, t)))));
            for (int i = 0; i < t.perks.size(); i++) {
                Tree.Perk k = t.perks.get(i);
                boolean owned = d.perks.contains(k.id());
                boolean open = !owned && (i == 0 || d.perks.contains(t.perks.get(i - 1).id())) && lvl >= k.level() && lvl - spent(d, t) >= k.cost();
                List<String> lore = new ArrayList<>(List.of(ChatColor.GRAY + k.about(), "",
                        ChatColor.DARK_GRAY + "Needs level " + k.level() + ", costs " + k.cost() + " point" + (k.cost() > 1 ? "s" : "")));
                lore.add(owned ? ChatColor.GREEN + "Unlocked" : open ? ChatColor.YELLOW + "Click to unlock" : ChatColor.RED + "Locked");
                ItemStack it = icon(owned ? k.icon() : open ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                        (owned ? ChatColor.GREEN : open ? ChatColor.YELLOW : ChatColor.RED) + k.name(), lore);
                inv.setItem(base + 2 + i, it);
            }
            row++;
        }
        inv.setItem(49, icon(Material.BOOK, ChatColor.AQUA + "How skills work", List.of(
                ChatColor.GRAY + "Each tree levels from what you do.", ChatColor.GRAY + "A level = a point in that tree.",
                ChatColor.GRAY + "Perks unlock in order, left to right.", ChatColor.DARK_GRAY + "Nothing counts in creative. Blocks you",
                ChatColor.DARK_GRAY + "placed give no mining/farming XP, and", ChatColor.DARK_GRAY + "building XP waits until a block stays a minute.")));
        viewer.openInventory(inv);
    }

    static String bar(double f) {
        int n = (int) Math.round(Math.max(0, Math.min(1, f)) * 20);
        return ChatColor.GREEN + "|".repeat(n) + ChatColor.DARK_GRAY + "|".repeat(20 - n);
    }

    static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!m.mine || !(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot(), row = slot / 9, col = slot % 9;
        int tree = row == 0 ? 0 : row == 1 ? 1 : row == 3 ? 2 : row == 4 ? 3 : -1;
        if (tree < 0 || col < 2 || col > 7) return;
        String why = unlock(p, Tree.values()[tree], col - 2);
        if (why != null) { p.sendMessage(ChatColor.RED + why); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f); }
        open(p, p.getUniqueId());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // =========================================================================================== Bedrock
    void forms(Player p) {
        Data d = data(p.getUniqueId());
        List<String> buttons = new ArrayList<>();
        for (Tree t : Tree.values()) buttons.add(t.title + " " + level(d.xp[t.ordinal()]) + "\nPoints: " + points(p.getUniqueId(), t));
        BedrockForms.menu(this, p, "Skills", "Each tree levels from what you do. A level = a point in that tree; perks unlock in order.", buttons,
                i -> treeForm(p, Tree.values()[i]), null);
    }

    void treeForm(Player p, Tree t) {
        Data d = data(p.getUniqueId());
        double[] pr = progress(d.xp[t.ordinal()]);
        List<String> buttons = new ArrayList<>();
        for (Tree.Perk k : t.perks) buttons.add((d.perks.contains(k.id()) ? "[Have] " : "") + k.name() + "\nLvl " + k.level() + ", " + k.cost() + " pt");
        buttons.add("Back");
        String text = t.title + " level " + level(d.xp[t.ordinal()]) + " (" + (int) (100 * pr[0] / pr[1]) + "%), " + points(p.getUniqueId(), t) + " points.\n" + t.how + "\n";
        StringBuilder sb = new StringBuilder(text);
        for (Tree.Perk k : t.perks) sb.append("\n").append(k.name()).append(": ").append(k.about());
        BedrockForms.menu(this, p, t.title, sb.toString(), buttons, i -> {
            if (i >= t.perks.size()) { forms(p); return; }
            String why = unlock(p, t, i);
            if (why != null) p.sendMessage(ChatColor.RED + why);
            treeForm(p, t);
        }, null);
    }

    // =========================================================================================== /skills
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player self = sender instanceof Player p ? p : null;
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        boolean admin = sender.hasPermission("faultlineskills.admin");
        switch (sub) {
            case "" -> { if (self == null) sender.sendMessage("Players only."); else open(self, self.getUniqueId()); }
            case "top" -> {
                Tree t = args.length > 1 ? Tree.of(args[1]) : null;
                if (t == null) { sender.sendMessage(ChatColor.YELLOW + "/skills top <mining|building|farming|redstone>"); return true; }
                List<Map.Entry<UUID, Data>> list = new ArrayList<>(data.entrySet());
                list.sort((a, b) -> Double.compare(b.getValue().xp[t.ordinal()], a.getValue().xp[t.ordinal()]));
                sender.sendMessage(t.color + "" + ChatColor.BOLD + "Top " + t.title);
                for (int i = 0; i < Math.min(10, list.size()); i++) {
                    Data d = list.get(i).getValue();
                    if (d.xp[t.ordinal()] <= 0) break;
                    sender.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE + d.name + ChatColor.GRAY + "  level " + level(d.xp[t.ordinal()]) + " (" + (int) d.xp[t.ordinal()] + " xp)");
                }
            }
            case "give" -> { // /skills give <player> <tree> <xp>
                if (!admin) { sender.sendMessage(ChatColor.RED + "Admins only."); return true; }
                Player to = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                Tree t = args.length > 2 ? Tree.of(args[2]) : null;
                double n;
                try { n = args.length > 3 ? Double.parseDouble(args[3]) : 0; } catch (NumberFormatException ex) { n = 0; }
                if (to == null || t == null || n == 0) { sender.sendMessage(ChatColor.YELLOW + "/skills give <player> <tree> <xp>"); return true; }
                Data d = data(to.getUniqueId());
                int before = level(d.xp[t.ordinal()]);
                d.xp[t.ordinal()] = Math.max(0, d.xp[t.ordinal()] + n);
                int after = level(d.xp[t.ordinal()]);
                if (after > before) dispatch("index stat " + to.getName() + " skill_" + t.key() + " " + (after - before));
                sender.sendMessage(ChatColor.GREEN + to.getName() + "'s " + t.title + " is level " + after + " now.");
                save();
            }
            case "reset" -> { // /skills reset <player> [tree|perks]
                if (!admin) { sender.sendMessage(ChatColor.RED + "Admins only."); return true; }
                Player to = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (to == null) { sender.sendMessage(ChatColor.YELLOW + "/skills reset <player> [tree|perks]"); return true; }
                Data d = data(to.getUniqueId());
                String what = args.length > 2 ? args[2].toLowerCase() : "all";
                Tree t = Tree.of(what);
                if (what.equals("perks")) d.perks.clear();
                else if (t != null) { d.xp[t.ordinal()] = 0; for (Tree.Perk k : t.perks) d.perks.remove(k.id()); }
                else { Arrays.fill(d.xp, 0); d.perks.clear(); }
                strip(to);
                sender.sendMessage(ChatColor.GREEN + "Reset " + to.getName() + "'s " + what + ".");
                save();
            }
            default -> {
                Player who = Bukkit.getPlayerExact(args[0]);
                if (who != null && self != null && admin) open(self, who.getUniqueId());
                else sender.sendMessage(ChatColor.YELLOW + "/skills" + ChatColor.GRAY + ", /skills top <tree>" + (admin ? ", /skills <player>, /skills give <player> <tree> <xp>, /skills reset <player> [tree|perks]" : ""));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("faultlineskills.admin");
        if (args.length == 1) { out.add("top"); if (admin) { out.add("give"); out.add("reset"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); } }
        else if (args.length == 2 && args[0].equalsIgnoreCase("top")) for (Tree t : Tree.values()) out.add(t.key());
        else if (args.length == 2 && admin) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (args.length == 3 && admin) { for (Tree t : Tree.values()) out.add(t.key()); if (args[0].equalsIgnoreCase("reset")) out.add("perks"); }
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }

    // =========================================================================================== players.yml
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> en : data.entrySet()) {
            String p = en.getKey() + ".";
            Data d = en.getValue();
            y.set(p + "name", d.name);
            for (Tree t : Tree.values()) if (d.xp[t.ordinal()] > 0) y.set(p + "xp." + t.key(), d.xp[t.ordinal()]);
            if (!d.perks.isEmpty()) y.set(p + "perks", new ArrayList<>(d.perks));
        }
        try { y.save(file); } catch (IOException e) { getLogger().warning("Couldn't save players.yml: " + e.getMessage()); }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) try {
            Data d = data(UUID.fromString(k));
            d.name = y.getString(k + ".name", "?");
            ConfigurationSection xs = y.getConfigurationSection(k + ".xp");
            if (xs != null) for (String t : xs.getKeys(false)) { Tree tr = Tree.of(t); if (tr != null) d.xp[tr.ordinal()] = xs.getDouble(t); }
            d.perks.addAll(y.getStringList(k + ".perks"));
        } catch (IllegalArgumentException ignored) { }
    }

    /** For other plugins (by reflection): a player's level in a tree ("mining", "building", "farming", "redstone"). */
    public static int skillLevel(UUID id, String tree) {
        Tree t = Tree.of(tree);
        return instance == null || t == null ? 0 : instance.level(id, t);
    }
}
