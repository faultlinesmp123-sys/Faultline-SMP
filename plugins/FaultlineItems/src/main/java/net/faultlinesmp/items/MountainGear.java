package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 2.0 MOUNTAIN GEAR (config {@code mountain-gear:}). Everything for climbing mountains:
 * <ul>
 * <li>MOUNTAIN ARMOR: iron armor (iron's armour points, toughness and durability; a copper Wayfinder trim to tell it apart),
 *     item PDC {@code faultlineitems:mountain_armor} = helmet/chestplate/leggings/boots. Helmet: no freezing. Chestplate: 20%
 *     less fall damage. Leggings: step up whole blocks. Boots: +3 safe fall distance. All four: CLIMB WALLS (walk into one to
 *     go up, sneak to hang on it).</li>
 * <li>ACCESSORIES: Mountaineer's Gloves (climb walls without the set; faster with it), Goat Hoof Charm (jump higher, 30% less
 *     fall damage), Alpine Harness (40% less fall damage; a fall that would kill you leaves you on 1 heart, once a minute).</li>
 * <li>GEAR: the Ice Axe (an iron pickaxe; right-click a wall to dig in and hang, Jump to leap up off it) and the Rope Ladder
 *     (right-click a wall: a ladder runs up it, or down it when you look down; it's gone after 2 minutes, drops nothing).</li>
 * <li>MOUNTAIN ZOMBIES: natural zombies in mountain biomes (`zombies.chance` 25%) wear 2-4 pieces of it, maybe an Ice Axe,
 *     climb walls after you, and killed by a player drop ONE random thing they had 10% of the time (`zombies.drop-chance`).</li>
 * </ul>
 * Everything can also be crafted.
 */
final class MountainGear implements Listener, CommandExecutor, TabCompleter {

    static final String ZOMBIE_TAG = "faultline_mountain_zombie";
    static final List<String> IDS = List.of("helmet", "chestplate", "leggings", "boots", "iceaxe", "rope", "gloves", "hoof", "harness");
    private static final Set<String> MOUNTAIN_BIOMES = Set.of("jagged_peaks", "frozen_peaks", "stony_peaks", "snowy_slopes", "grove",
            "meadow", "cherry_grove", "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna");

    private final FaultlineItems plugin;
    final NamespacedKey armorKey, axeKey, ropeKey, glovesKey, hoofKey, harnessKey, stuffKey;
    private final NamespacedKey stepMod, fallMod, jumpMod;
    private final Random rnd = new Random();

    /** players who can climb right now (set / gloves), refreshed every 10 ticks: uuid -> climb speed. */
    private final Map<UUID, Double> climbers = new HashMap<>();
    /** pieces of mountain armor each player wears (refreshed every 10 ticks). */
    private final Map<UUID, Set<String>> worn = new HashMap<>();
    /** players hanging on the Ice Axe: uuid -> tick it ends. */
    private final Map<UUID, Long> hanging = new HashMap<>();
    private final Map<UUID, Long> axeReady = new HashMap<>();
    private final Map<UUID, Long> harnessReady = new HashMap<>();
    /** rope ladder cells -> server tick they go away. */
    private final Map<Block, Long> ropes = new HashMap<>();
    private final Set<UUID> zombies = new HashSet<>();
    private long ticks;

    MountainGear(FaultlineItems plugin) {
        this.plugin = plugin;
        armorKey = new NamespacedKey(plugin, "mountain_armor");
        axeKey = new NamespacedKey(plugin, "ice_axe");
        ropeKey = new NamespacedKey(plugin, "rope_ladder");
        glovesKey = new NamespacedKey(plugin, "mountaineer_gloves");
        hoofKey = new NamespacedKey(plugin, "goat_hoof_charm");
        harnessKey = new NamespacedKey(plugin, "alpine_harness");
        stuffKey = new NamespacedKey(plugin, "mountain_stuff");
        stepMod = new NamespacedKey(plugin, "mountain_step");
        fallMod = new NamespacedKey(plugin, "mountain_fall");
        jumpMod = new NamespacedKey(plugin, "goat_hoof_jump");
        recipes();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 1);
    }

    boolean on() { return plugin.getConfig().getBoolean("mountain-gear.enabled", true); }
    private double cfg(String path, double def) { return plugin.getConfig().getDouble("mountain-gear." + path, def); }

    // =================================================================== items

    ItemStack item(String id) {
        return switch (id) {
            case "helmet" -> armor(Material.IRON_HELMET, "helmet", "Mountain Helmet", "No freezing in powder snow.");
            case "chestplate" -> armor(Material.IRON_CHESTPLATE, "chestplate", "Mountain Chestplate", "20% less fall damage.");
            case "leggings" -> armor(Material.IRON_LEGGINGS, "leggings", "Mountain Leggings", "Step up whole blocks without jumping.");
            case "boots" -> armor(Material.IRON_BOOTS, "boots", "Mountain Boots", "Fall 3 more blocks before it hurts.");
            case "iceaxe" -> iceAxe();
            case "rope" -> rope(1);
            case "gloves" -> accessory(Material.LEATHER, glovesKey, ChatColor.GOLD + "" + ChatColor.BOLD + "Mountaineer's Gloves",
                    ChatColor.GREEN + "Walk into a wall to climb it,",
                    ChatColor.GREEN + "sneak to hang on it.",
                    ChatColor.GREEN + "With the Mountain Armor set: faster.");
            case "hoof" -> accessory(Material.RABBIT_FOOT, hoofKey, ChatColor.WHITE + "" + ChatColor.BOLD + "Goat Hoof Charm",
                    ChatColor.GREEN + "Jump 15% higher.",
                    ChatColor.GREEN + "30% less fall damage.");
            case "harness" -> accessory(Material.LEAD, harnessKey, ChatColor.YELLOW + "" + ChatColor.BOLD + "Alpine Harness",
                    ChatColor.GREEN + "40% less fall damage.",
                    ChatColor.GREEN + "A fall that would kill you leaves",
                    ChatColor.GREEN + "you on 1 heart (once a minute).");
            default -> null;
        };
    }

    private ItemStack armor(Material mat, String piece, String name, String perk) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GRAY + "" + ChatColor.BOLD + name);
        m.setLore(List.of(ChatColor.DARK_GRAY + "Iron armor for climbers.",
                ChatColor.GREEN + perk,
                ChatColor.GOLD + "Full set: climb walls (walk into one),",
                ChatColor.GOLD + "sneak to hang on it."));
        m.getPersistentDataContainer().set(armorKey, PersistentDataType.STRING, piece);
        if (m instanceof ArmorMeta am) {
            try { am.setTrim(new ArmorTrim(TrimMaterial.COPPER, TrimPattern.WAYFINDER)); am.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM); }
            catch (Throwable ignored) { }
        }
        it.setItemMeta(m);
        return it;
    }

    ItemStack iceAxe() {
        ItemStack it = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Ice Axe");
        m.setLore(List.of(ChatColor.DARK_GRAY + "Mines like an iron pickaxe.",
                ChatColor.GREEN + "Right-click a wall: dig in and hang on it.",
                ChatColor.GREEN + "Jump while hanging: leap up off it.",
                ChatColor.GREEN + "Sneak: let go."));
        m.getPersistentDataContainer().set(axeKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack rope(int n) {
        ItemStack it = new ItemStack(Material.LADDER, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Rope Ladder");
        m.setLore(List.of(ChatColor.GREEN + "Right-click a wall: a ladder runs up it",
                ChatColor.GREEN + "(look down first: down it), up to " + (int) cfg("rope.length", 16) + " blocks.",
                ChatColor.GRAY + "Comes loose after 2 minutes."));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(ropeKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack accessory(Material mat, NamespacedKey key, String name, String... lore) {
        ItemStack it = FaultlineItems.NewAccessoryItems.make(plugin, mat, name, key, lore);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.minecraft(mat.name().toLowerCase(Locale.ROOT)));   // vanilla look (no texture in the pack)
        it.setItemMeta(m);
        return it;
    }

    String pieceOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        return it.getPersistentDataContainer().get(armorKey, PersistentDataType.STRING);
    }
    boolean isAxe(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(axeKey, PersistentDataType.BYTE); }
    boolean isRope(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(ropeKey, PersistentDataType.BYTE); }

    private void recipes() {
        try {
            String[][] armor = {{"helmet", "IRON_HELMET"}, {"chestplate", "IRON_CHESTPLATE"}, {"leggings", "IRON_LEGGINGS"}, {"boots", "IRON_BOOTS"}};
            for (String[] a : armor) {
                ShapedRecipe r = new ShapedRecipe(new NamespacedKey(plugin, "mountain_" + a[0]), item(a[0]));
                r.shape("SLS", "LXL", "SLS");
                r.setIngredient('S', Material.STRING);
                r.setIngredient('L', Material.LEATHER);
                r.setIngredient('X', Material.valueOf(a[1]));
                add(r);
            }
            ShapedRecipe axe = new ShapedRecipe(new NamespacedKey(plugin, "ice_axe"), iceAxe());
            axe.shape("IIF", " S ", " S ");
            axe.setIngredient('I', Material.IRON_INGOT); axe.setIngredient('F', Material.FLINT); axe.setIngredient('S', Material.STICK);
            add(axe);
            ShapedRecipe rope = new ShapedRecipe(new NamespacedKey(plugin, "rope_ladder"), rope(4));
            rope.shape("STS", "STS", "STS");
            rope.setIngredient('S', Material.STRING); rope.setIngredient('T', Material.STICK);
            add(rope);
            ShapedRecipe gloves = new ShapedRecipe(new NamespacedKey(plugin, "mountaineer_gloves"), item("gloves"));
            gloves.shape("L L", "LIL", "F F");
            gloves.setIngredient('L', Material.LEATHER); gloves.setIngredient('I', Material.IRON_INGOT); gloves.setIngredient('F', Material.FLINT);
            add(gloves);
            ShapedRecipe hoof = new ShapedRecipe(new NamespacedKey(plugin, "goat_hoof_charm"), item("hoof"));
            hoof.shape(" F ", "FRF", " F ");
            hoof.setIngredient('F', Material.FEATHER); hoof.setIngredient('R', Material.RABBIT_FOOT);
            add(hoof);
            ShapedRecipe harness = new ShapedRecipe(new NamespacedKey(plugin, "alpine_harness"), item("harness"));
            harness.shape("LTL", "T T", "LTL");
            harness.setIngredient('L', Material.LEAD); harness.setIngredient('T', Material.STRING);
            add(harness);
        } catch (RuntimeException e) { plugin.getLogger().warning("Mountain gear recipes: " + e.getMessage()); }
    }

    private static void add(ShapedRecipe r) { if (Bukkit.getRecipe(r.getKey()) == null) Bukkit.addRecipe(r); }

    // =================================================================== tick

    void tick() {
        ticks++;
        if (!on()) return;
        if (ticks % 10 == 0) refresh();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Long until = hanging.get(id);
            if (until != null) { hang(p, until); continue; }
            Double speed = climbers.get(id);
            if (speed != null) climb(p, speed);
        }
        if (ticks % 5 == 0) zombieTick();
        if (ticks % 20 == 0 && !ropes.isEmpty()) {
            for (Iterator<Map.Entry<Block, Long>> it = ropes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Block, Long> e = it.next();
                if (e.getValue() > ticks) continue;
                it.remove();
                Block b = e.getKey();
                if (b.getType() == Material.LADDER) b.setType(Material.AIR, false);
            }
        }
    }

    private void refresh() {
        climbers.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> set = new HashSet<>();
            for (ItemStack a : p.getInventory().getArmorContents()) { String s = pieceOf(a); if (s != null) set.add(s); }
            worn.put(p.getUniqueId(), set);
            boolean full = set.size() == 4;
            boolean gloves = plugin.getAccessoryManager().hasEquipped(p, glovesKey);
            if (full || gloves) climbers.put(p.getUniqueId(), full && gloves ? cfg("climb.speed-both", 0.28) : full ? cfg("climb.speed-set", 0.2) : cfg("climb.speed-gloves", 0.15));
            mod(p, Attribute.STEP_HEIGHT, stepMod, set.contains("leggings") ? cfg("leggings-step", 0.5) : 0);
            mod(p, Attribute.SAFE_FALL_DISTANCE, fallMod, set.contains("boots") ? cfg("boots-safe-fall", 3) : 0);
            mod(p, Attribute.JUMP_STRENGTH, jumpMod, plugin.getAccessoryManager().hasEquipped(p, hoofKey) ? cfg("hoof.jump", 0.15) : 0);
        }
    }

    /** Puts (or takes off) one of our attribute modifiers. JUMP_STRENGTH is a multiplier, the others add. */
    private void mod(Player p, Attribute attr, NamespacedKey key, double amount) {
        AttributeInstance a;
        try { a = p.getAttribute(attr); } catch (Throwable t) { return; }
        if (a == null) return;
        AttributeModifier old = null;
        for (AttributeModifier m : a.getModifiers()) if (m.getKey().equals(key)) old = m;
        if (old != null && Math.abs(old.getAmount() - amount) < 1e-6) return;
        if (old != null) a.removeModifier(old);
        if (amount == 0) return;
        AttributeModifier.Operation op = attr == Attribute.JUMP_STRENGTH ? AttributeModifier.Operation.MULTIPLY_SCALAR_1 : AttributeModifier.Operation.ADD_NUMBER;
        a.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    void strip(Player p) {
        mod(p, Attribute.STEP_HEIGHT, stepMod, 0);
        mod(p, Attribute.SAFE_FALL_DISTANCE, fallMod, 0);
        mod(p, Attribute.JUMP_STRENGTH, jumpMod, 0);
    }

    private static boolean canClimb(Player p) {
        GameMode g = p.getGameMode();
        return g != GameMode.SPECTATOR && !p.isFlying() && !p.isGliding() && !p.isSwimming() && !p.isInsideVehicle() && !p.isDead();
    }

    /** A solid block right in front of this entity (toward `yaw`), at its feet or its head. */
    static boolean wallAhead(Entity e, float yaw) {
        double r = Math.toRadians(yaw);
        double dx = -Math.sin(r), dz = Math.cos(r);
        Location l = e.getLocation();
        double reach = e.getWidth() / 2 + 0.35;
        for (double y : new double[]{0.2, Math.min(1.1, e.getHeight() - 0.3)}) {
            Block b = l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx * reach), (int) Math.floor(l.getY() + y), (int) Math.floor(l.getZ() + dz * reach));
            if (b.getType().isSolid() && b.getType() != Material.LADDER) return true;
        }
        return false;
    }

    private void climb(Player p, double speed) {
        if (!canClimb(p) || !wallAhead(p, p.getLocation().getYaw())) return;
        boolean forward, sneak;
        try { forward = p.getCurrentInput().isForward(); sneak = p.getCurrentInput().isSneak(); }
        catch (Throwable t) { forward = false; sneak = p.isSneaking(); }
        Vector v = p.getVelocity();
        if (forward && !sneak) {
            Safe.vel(p, new Vector(v.getX() * 0.5, speed, v.getZ() * 0.5));
            p.setFallDistance(0);
        } else if (sneak && !p.isOnGround()) {
            Safe.vel(p, new Vector(0, 0, 0));   // hang on the wall
            p.setFallDistance(0);
        }
    }

    // =================================================================== the Ice Axe

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (!on() || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isRope(hand) && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            placeRope(p, e.getClickedBlock(), e.getBlockFace(), hand);
            return;
        }
        if (!isAxe(hand)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (p.getGameMode() == GameMode.SPECTATOR || p.isInsideVehicle() || p.isGliding()) return;
        if (hanging.containsKey(p.getUniqueId())) return;
        if (ticks < axeReady.getOrDefault(p.getUniqueId(), 0L)) return;
        Block wall = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (wall == null) { wall = lookedAt(p, 2.5); face = null; }
        if (wall == null || !wall.getType().isSolid()) return;
        if (face == BlockFace.UP || (p.isOnGround() && face != null && face != BlockFace.DOWN && wall.getY() < p.getLocation().getBlockY())) return; // not on the ground in front of you
        if (p.isOnGround()) return;   // dig in only while off the ground (climbing / jumping / falling)
        e.setCancelled(true);
        hanging.put(p.getUniqueId(), ticks + (long) (cfg("ice-axe.hang-seconds", 6) * 20));
        p.setFallDistance(0);
        Safe.vel(p, new Vector(0, 0, 0));
        p.swingMainHand();
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_STONE_HIT, 1f, 0.7f);
        p.getWorld().spawnParticle(Particle.BLOCK, wall.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, wall.getBlockData());
        if (hand.getItemMeta() instanceof Damageable d && p.getGameMode() == GameMode.SURVIVAL) {
            ItemStack used = hand.damage(1, p);
            p.getInventory().setItemInMainHand(used);
        }
    }

    private void hang(Player p, long until) {
        UUID id = p.getUniqueId();
        boolean jump, sneak;
        try { jump = p.getCurrentInput().isJump(); sneak = p.getCurrentInput().isSneak(); }
        catch (Throwable t) { jump = false; sneak = p.isSneaking(); }
        boolean stillAxe = isAxe(p.getInventory().getItemInMainHand());
        if (ticks >= until || sneak || !stillAxe || !p.isOnline() || p.isDead() || p.isInsideVehicle() || p.isOnGround()) {
            hanging.remove(id);
            axeReady.put(id, ticks + 20);
            return;
        }
        if (jump && ticks > until - (long) (cfg("ice-axe.hang-seconds", 6) * 20) + 4) {
            hanging.remove(id);
            axeReady.put(id, ticks + 30);
            Vector look = p.getLocation().getDirection().setY(0);
            if (look.lengthSquared() > 1e-6) look.normalize().multiply(-0.15);   // a little push back off the wall
            Safe.vel(p, look.setY(cfg("ice-axe.leap", 0.75)));
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.4f);
            return;
        }
        Safe.vel(p, new Vector(0, 0, 0));
        p.setFallDistance(0);
    }

    /** A plain block walk along the look (no ray trace needed). */
    private static Block lookedAt(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        for (double t = 0.2; t <= range; t += 0.2) {
            Block b = eye.getWorld().getBlockAt(eye.clone().add(d.clone().multiply(t)));
            if (b.getType().isSolid()) return b;
        }
        return null;
    }

    // =================================================================== the rope ladder

    private void placeRope(Player p, Block clicked, BlockFace face, ItemStack hand) {
        if (face == null || face == BlockFace.UP || face == BlockFace.DOWN || !clicked.getType().isSolid()) {
            p.sendActionBar(ChatColor.GRAY + "Click the side of a wall.");
            return;
        }
        boolean down = p.getLocation().getPitch() > 40;
        int len = (int) cfg("rope.length", 16);
        Block wall = clicked;
        List<Block> cells = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            Block cell = wall.getRelative(face);
            if (!cell.getType().isAir() && cell.getType() != Material.CAVE_AIR) break;
            if (!wall.getType().isSolid()) break;
            cells.add(cell);
            wall = wall.getRelative(down ? BlockFace.DOWN : BlockFace.UP);
        }
        if (cells.isEmpty()) { p.sendActionBar(ChatColor.GRAY + "No room for a ladder there."); return; }
        long gone = ticks + (long) (cfg("rope.seconds", 120) * 20);
        for (Block c : cells) {
            c.setType(Material.LADDER, false);
            if (c.getBlockData() instanceof Ladder l) { l.setFacing(face); c.setBlockData(l, false); }
            ropes.put(c, gone);
        }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        p.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_LADDER_PLACE, 1f, 0.8f);
        p.swingMainHand();
    }

    @EventHandler(ignoreCancelled = true)
    public void onRopeBreak(BlockBreakEvent e) {
        if (ropes.remove(e.getBlock()) != null) e.setDropItems(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRopeDrop(BlockDropItemEvent e) {
        if (ropes.containsKey(e.getBlock())) e.getItems().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onRopePhysics(BlockPhysicsEvent e) {
        if (ropes.containsKey(e.getBlock()) && e.getBlock().getType() == Material.LADDER) {
            // the wall behind it went: it just goes, nothing drops
            Block b = e.getBlock();
            if (b.getBlockData() instanceof Ladder l && !b.getRelative(l.getFacing().getOppositeFace()).getType().isSolid()) {
                e.setCancelled(true);
                ropes.remove(b);
                b.setType(Material.AIR, false);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(org.bukkit.event.block.BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (ropes.containsKey(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonBack(org.bukkit.event.block.BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (ropes.containsKey(b)) { e.setCancelled(true); return; }
    }

    /** Explosions: our ladders just vanish (a blown-up ladder would drop one). */
    @EventHandler(ignoreCancelled = true)
    public void onBoom(org.bukkit.event.entity.EntityExplodeEvent e) { unrope(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBoom(org.bukkit.event.block.BlockExplodeEvent e) { unrope(e.blockList()); }

    private void unrope(List<Block> list) {
        if (ropes.isEmpty()) return;
        for (Iterator<Block> it = list.iterator(); it.hasNext(); ) {
            Block b = it.next();
            if (ropes.remove(b) != null) { it.remove(); if (b.getType() == Material.LADDER) b.setType(Material.AIR, false); }
        }
    }

    // =================================================================== falls, freezing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!on() || !(e.getEntity() instanceof Player p)) return;
        Set<String> set = worn.getOrDefault(p.getUniqueId(), Set.of());
        if (e.getCause() == EntityDamageEvent.DamageCause.FREEZE && set.contains("helmet")) {
            e.setCancelled(true);
            p.setFreezeTicks(0);
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        double mult = 1;
        if (set.contains("chestplate")) mult *= 1 - cfg("chestplate-fall-reduction", 0.2);
        if (plugin.getAccessoryManager().hasEquipped(p, hoofKey)) mult *= 1 - cfg("hoof.fall-reduction", 0.3);
        boolean harness = plugin.getAccessoryManager().hasEquipped(p, harnessKey);
        if (harness) mult *= 1 - cfg("harness.fall-reduction", 0.4);
        double dmg = e.getDamage() * mult;
        if (harness && dmg >= p.getHealth() + p.getAbsorptionAmount() && ticks >= harnessReady.getOrDefault(p.getUniqueId(), 0L)) {
            harnessReady.put(p.getUniqueId(), ticks + (long) (cfg("harness.cooldown-seconds", 60) * 20));
            dmg = Math.max(0, p.getHealth() + p.getAbsorptionAmount() - 2);
            p.sendActionBar(ChatColor.YELLOW + "Your Alpine Harness caught you!");
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.6f);
        }
        if (dmg <= 0) e.setCancelled(true); else e.setDamage(dmg);
    }

    // =================================================================== mountain zombies

    static boolean mountainBiome(Location l) {
        try { return MOUNTAIN_BIOMES.contains(l.getBlock().getBiome().getKey().getKey()); } catch (Throwable t) { return false; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on() || e.getEntityType() != EntityType.ZOMBIE || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        for (String t : e.getEntity().getScoreboardTags()) if (t.startsWith("faultline")) return;
        if (!mountainBiome(e.getLocation()) || rnd.nextDouble() >= cfg("zombies.chance", 0.25)) return;
        makeZombie((Zombie) e.getEntity());
    }

    /** Dresses a zombie as a Mountain Zombie. */
    void makeZombie(Zombie z) {
        z.addScoreboardTag(ZOMBIE_TAG);
        z.setCustomName("Mountain Zombie");
        z.setCustomNameVisible(false);
        z.setAdult();
        List<String> pieces = new ArrayList<>(List.of("helmet", "chestplate", "leggings", "boots"));
        Collections.shuffle(pieces, rnd);
        int n = 2 + rnd.nextInt(3);
        List<String> stuff = new ArrayList<>();
        EntityEquipment eq = z.getEquipment();
        for (int i = 0; i < n; i++) {
            String p = pieces.get(i);
            stuff.add(p);
            ItemStack it = item(p);
            switch (p) {
                case "helmet" -> { eq.setHelmet(it); eq.setHelmetDropChance(0); }
                case "chestplate" -> { eq.setChestplate(it); eq.setChestplateDropChance(0); }
                case "leggings" -> { eq.setLeggings(it); eq.setLeggingsDropChance(0); }
                default -> { eq.setBoots(it); eq.setBootsDropChance(0); }
            }
        }
        if (rnd.nextDouble() < 0.5) { eq.setItemInMainHand(iceAxe()); eq.setItemInMainHandDropChance(0); stuff.add("iceaxe"); }
        // what's in its pack (not shown): a rope ladder or one of the accessories
        String[] pack = {"rope", "rope", "rope", "gloves", "hoof", "harness"};
        stuff.add(pack[rnd.nextInt(pack.length)]);
        z.getPersistentDataContainer().set(stuffKey, PersistentDataType.STRING, String.join(",", stuff));
        zombies.add(z.getUniqueId());
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getScoreboardTags().contains(ZOMBIE_TAG)) zombies.add(en.getUniqueId());
    }

    /** Mountain zombies climb walls after whoever they're chasing. */
    private void zombieTick() {
        if (zombies.isEmpty()) return;
        for (Iterator<UUID> it = zombies.iterator(); it.hasNext(); ) {
            Entity en = Bukkit.getEntity(it.next());
            if (!(en instanceof Zombie z) || !z.isValid()) { it.remove(); continue; }
            LivingEntity tg = z.getTarget();
            if (tg == null || tg.getWorld() != z.getWorld()) continue;
            Location zl = z.getLocation(), tl = tg.getLocation();
            if (tl.getY() < zl.getY() + 1.2 || zl.distanceSquared(tl) > 24 * 24) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-(tl.getX() - zl.getX()), tl.getZ() - zl.getZ()));
            if (!wallAhead(z, yaw)) continue;
            Safe.vel(z, new Vector(0, cfg("zombies.climb-speed", 0.22), 0));
            z.setFallDistance(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ZOMBIE_TAG)) return;
        zombies.remove(e.getEntity().getUniqueId());
        if (e.getEntity().getKiller() == null) return;
        if (rnd.nextDouble() >= cfg("zombies.drop-chance", 0.10)) return;
        String s = e.getEntity().getPersistentDataContainer().get(stuffKey, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return;
        String[] stuff = s.split(",");
        String pick = stuff[rnd.nextInt(stuff.length)];
        ItemStack drop = pick.equals("rope") ? rope(2 + rnd.nextInt(3)) : item(pick);
        if (drop == null) return;
        if (drop.getItemMeta() instanceof Damageable d && drop.getType().getMaxDurability() > 0) {   // a bit worn, like a mob's drop
            d.setDamage((int) (drop.getType().getMaxDurability() * (0.1 + rnd.nextDouble() * 0.4)));
            drop.setItemMeta(d);
        }
        e.getDrops().add(drop);
    }

    // =================================================================== misc

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        climbers.remove(id); worn.remove(id); hanging.remove(id); axeReady.remove(id);
    }

    void shutdown() {
        for (Block b : ropes.keySet()) if (b.getType() == Material.LADDER) b.setType(Material.AIR, false);
        ropes.clear();
        for (Player p : Bukkit.getOnlinePlayers()) strip(p);
    }

    // =================================================================== /mountaingear

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.mountaingear")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
            if (t == null) { s.sendMessage(ChatColor.RED + "Who?"); return true; }
            List<String> ids = a[1].equalsIgnoreCase("all") ? IDS : List.of(a[1].toLowerCase(Locale.ROOT));
            for (String id : ids) {
                ItemStack it = id.equals("rope") ? rope(16) : item(id);
                if (it == null) { s.sendMessage(ChatColor.RED + "Unknown: " + id + " (" + String.join(", ", IDS) + ")"); return true; }
                for (ItemStack left : t.getInventory().addItem(it).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
            }
            s.sendMessage(ChatColor.GREEN + "Gave " + t.getName() + " " + a[1] + ".");
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("zombie")) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            int n = a.length >= 2 ? Math.max(1, Math.min(20, parse(a[1]))) : 1;
            for (int i = 0; i < n; i++) {
                Zombie z = (Zombie) p.getWorld().spawnEntity(p.getLocation().add(p.getLocation().getDirection().setY(0).multiply(3)), EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.COMMAND);
                makeZombie(z);
            }
            s.sendMessage(ChatColor.GREEN + "Spawned " + n + " Mountain Zombie" + (n == 1 ? "" : "s") + ".");
            return true;
        }
        s.sendMessage(ChatColor.YELLOW + "/" + label + " give <" + String.join("|", IDS) + "|all> [player]");
        s.sendMessage(ChatColor.YELLOW + "/" + label + " zombie [n]");
        return true;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.mountaingear")) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("give", "zombie"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) { List<String> l = new ArrayList<>(IDS); l.add("all"); return Legendary.filter(l, a[1]); }
        return null;
    }
}
