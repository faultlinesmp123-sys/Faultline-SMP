package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * NEW GEAR (the "wild" update):
 *   MINING HELMET      worn: night vision and a headlamp (a light where you look). Iron helmet + glowstone + redstone torch.
 *   LANTERN OF SOULS   held: lights your way, reveals invisible things nearby (they glow), lifts Darkness, and lets you
 *                      board the Ghost Ship (FaultlineShips). Soul lantern ringed with phantom membranes and amethyst.
 *   KNIGHT'S HELM      the Skeleton Knights' great helm (they drop it).
 *   ANCIENT GEAR PART  from the Stone Golem; four of them around an amethyst block (with shards) = an ANCIENT CORE
 *                      accessory (+3 armor, +25% knockback resistance, +2 hearts).
 *   BOSS MUSIC DISCS   each boss theme on a disc (bosses drop them, through the Index). Put one in a jukebox to play it
 *                      (Java and Bedrock: it's the boss's own music), right-click again to take it out.
 *   MUSEUM PEDESTAL    place it, right-click it with anything to put it on show (Java: it floats and turns; Bedrock: a
 *                      stand holds it). Only whoever put it there (or an admin) can take it back.
 *   Rotbeard's Grappling Hook can now be crafted too (a fishing rod, iron, a tripwire hook and a chain).
 */
final class Gear implements Listener, CommandExecutor {

    static final String TAG = "faultline_gear_fx";
    static final Map<String, String[]> DISCS = new LinkedHashMap<>(); // id -> {title, sound}
    static {
        DISCS.put("demon_eye", new String[]{"The Demon Eye: It Sees Everything", "faultline:demon_eye.music"});
        DISCS.put("dune", new String[]{"The Dune Devourer", "faultline:dune.music"});
        DISCS.put("don", new String[]{"Don Lorenzo", "faultline:don.music"});
        DISCS.put("kraken", new String[]{"The Kraken", "faultline:kraken.music"});
        DISCS.put("jacob1", new String[]{"Diamond Jacob I", "faultline:jacob.music1"});
        DISCS.put("jacob2", new String[]{"Diamond Jacob II", "faultline:jacob.music2"});
        DISCS.put("rocco", new String[]{"Rocco Vendetta", "faultline:rocco.music"});
        DISCS.put("explorer", new String[]{"The Lost Explorer: Black Knife", "faultline:explorer.music"});
        DISCS.put("pirates", new String[]{"The Pirate Invasion", "faultline:pirates.music"});
    }

    private final FaultlineItems plugin;
    final NamespacedKey gearKey, discKey, coreKey, pedestalKey, jukeKey, coreArmor, coreKb, coreHealth, mineArmor, knightArmor, knightTough;
    private final Map<UUID, Block> lights = new HashMap<>();
    private final Map<Long, Show> shows = new HashMap<>();
    private int ticks;

    Gear(FaultlineItems plugin) {
        this.plugin = plugin;
        gearKey = new NamespacedKey(plugin, "gear");
        discKey = new NamespacedKey(plugin, "disc");
        coreKey = new NamespacedKey(plugin, "ancient_core");
        pedestalKey = new NamespacedKey(plugin, "pedestals");
        jukeKey = new NamespacedKey(plugin, "jukebox_disc");
        coreArmor = new NamespacedKey(plugin, "ancient_core_armor");
        coreKb = new NamespacedKey(plugin, "ancient_core_knockback");
        coreHealth = new NamespacedKey(plugin, "ancient_core_health");
        mineArmor = new NamespacedKey(plugin, "mining_helmet_armor");
        knightArmor = new NamespacedKey(plugin, "knight_helm_armor");
        knightTough = new NamespacedKey(plugin, "knight_helm_toughness");
        recipes();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) showChunk(c);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    // =====================================================================================================
    //  the items
    // =====================================================================================================
    static final List<String> IDS = new ArrayList<>(List.of("mining_helmet", "lantern_of_souls", "knight_helm", "ancient_gear_part", "ancient_core", "museum_pedestal"));
    static { for (String d : DISCS.keySet()) IDS.add("disc_" + d); }

    ItemStack item(String id) {
        if (id.startsWith("disc_")) return disc(id.substring(5));
        ItemStack s;
        ItemMeta m;
        switch (id) {
            case "mining_helmet" -> {
                s = new ItemStack(Material.PAPER); m = s.getItemMeta();
                m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Mining Helmet");
                m.setLore(List.of(ChatColor.GRAY + "Wear it: night vision, and a headlamp", ChatColor.GRAY + "that lights up whatever you look at.", ChatColor.BLUE + "+2 Armor"));
                m.setItemModel(new NamespacedKey("faultline", "mining_helmet"));
                headgear(m);
                m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(mineArmor, 2, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
                m.setMaxStackSize(1);
            }
            case "knight_helm" -> {
                s = new ItemStack(Material.PAPER); m = s.getItemMeta();
                m.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Knight's Helm");
                m.setLore(List.of(ChatColor.GRAY + "A Skeleton Knight's great helm,", ChatColor.GRAY + "red plume and all.", ChatColor.BLUE + "+3 Armor, +1 Toughness"));
                m.setItemModel(new NamespacedKey("faultline", "knight_helm"));
                headgear(m);
                m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(knightArmor, 3, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
                m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(knightTough, 1, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
                m.setMaxStackSize(1);
            }
            case "lantern_of_souls" -> {
                s = new ItemStack(Material.PAPER); m = s.getItemMeta();
                m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Lantern of Souls");
                m.setLore(List.of(ChatColor.GRAY + "Hold it to light the dark and see", ChatColor.GRAY + "what hides from the living eye.",
                        ChatColor.GREEN + "Reveals invisible creatures nearby,", ChatColor.GREEN + "lifts Darkness, and lets you board", ChatColor.GREEN + "the Ghost Ship."));
                m.setItemModel(new NamespacedKey("faultline", "lantern_of_souls"));
                m.setMaxStackSize(1);
            }
            case "ancient_gear_part" -> {
                s = new ItemStack(Material.PAPER); m = s.getItemMeta();
                m.setDisplayName(ChatColor.GREEN + "Ancient Gear Part");
                m.setLore(List.of(ChatColor.GRAY + "A cog from a Stone Golem's core.", ChatColor.GRAY + "Four around an amethyst block make", ChatColor.GRAY + "an Ancient Core."));
                m.setItemModel(new NamespacedKey("faultline", "ancient_gear_part"));
                m.setMaxStackSize(64);
            }
            case "ancient_core" -> {
                return FaultlineItems.NewAccessoryItems.make(plugin, Material.PAPER, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Ancient Core", coreKey,
                        ChatColor.GREEN + "+3 armor, +25% knockback resistance,", ChatColor.GREEN + "+2 hearts.",
                        ChatColor.DARK_GRAY + "Built from a Stone Golem's gears.");
            }
            case "museum_pedestal" -> {
                s = new ItemStack(Material.POLISHED_BLACKSTONE_BRICK_WALL); m = s.getItemMeta();
                m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Museum Pedestal");
                m.setLore(List.of(ChatColor.GRAY + "Place it, then right-click it with", ChatColor.GRAY + "anything to put it on show.", ChatColor.DARK_GRAY + "Only you (or an admin) can take it back."));
                m.setItemModel(new NamespacedKey("faultline", "museum_pedestal"));
            }
            default -> { return null; }
        }
        m.getPersistentDataContainer().set(gearKey, PersistentDataType.STRING, id);
        s.setItemMeta(m);
        return s;
    }

    /** Worn on the head (Java and Bedrock both draw the 3D model there). */
    void headgear(ItemMeta m) {
        try {
            EquippableComponent eq = m.getEquippable();
            eq.setSlot(EquipmentSlot.HEAD);
            eq.setEquipSound(Sound.ITEM_ARMOR_EQUIP_IRON);
            m.setEquippable(eq);
        } catch (RuntimeException | NoSuchMethodError ignored) { }
    }

    ItemStack disc(String id) {
        String[] d = DISCS.get(id);
        if (d == null) return null;
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Music Disc");
        m.setLore(List.of(ChatColor.GRAY + d[0], ChatColor.DARK_GRAY + "Put it in a jukebox."));
        m.setItemModel(new NamespacedKey("faultline", "disc/disc_" + id));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(gearKey, PersistentDataType.STRING, "disc_" + id);
        m.getPersistentDataContainer().set(discKey, PersistentDataType.STRING, id);
        s.setItemMeta(m);
        return s;
    }

    String id(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        PersistentDataContainer c = s.getItemMeta().getPersistentDataContainer();
        if (c.has(coreKey, PersistentDataType.BYTE)) return "ancient_core";
        return c.get(gearKey, PersistentDataType.STRING);
    }

    void recipes() {
        ShapelessRecipe helmet = new ShapelessRecipe(new NamespacedKey(plugin, "mining_helmet"), item("mining_helmet"));
        helmet.addIngredient(Material.IRON_HELMET); helmet.addIngredient(Material.GLOWSTONE); helmet.addIngredient(Material.REDSTONE_TORCH);
        FaultlineItems.addRecipeSafely(helmet);
        ShapedRecipe lantern = new ShapedRecipe(new NamespacedKey(plugin, "lantern_of_souls"), item("lantern_of_souls"));
        lantern.shape("PAP", "ASA", "PAP");
        lantern.setIngredient('P', Material.PHANTOM_MEMBRANE); lantern.setIngredient('A', Material.AMETHYST_SHARD); lantern.setIngredient('S', Material.SOUL_LANTERN);
        FaultlineItems.addRecipeSafely(lantern);
        ShapedRecipe core = new ShapedRecipe(new NamespacedKey(plugin, "ancient_core"), item("ancient_core"));
        core.shape("GAG", "ABA", "GAG");
        core.setIngredient('G', new RecipeChoice.ExactChoice(item("ancient_gear_part")));
        core.setIngredient('A', Material.AMETHYST_SHARD); core.setIngredient('B', Material.AMETHYST_BLOCK);
        FaultlineItems.addRecipeSafely(core);
        ShapedRecipe pedestal = new ShapedRecipe(new NamespacedKey(plugin, "museum_pedestal"), withAmount(item("museum_pedestal"), 2));
        pedestal.shape("GQG", " W ", "BBB");
        pedestal.setIngredient('G', Material.GOLD_INGOT); pedestal.setIngredient('Q', Material.QUARTZ_SLAB);
        pedestal.setIngredient('W', Material.POLISHED_BLACKSTONE_BRICK_WALL); pedestal.setIngredient('B', Material.POLISHED_BLACKSTONE_BRICKS);
        FaultlineItems.addRecipeSafely(pedestal);
        ShapedRecipe hook = new ShapedRecipe(new NamespacedKey(plugin, "craft_grappling_hook"), FaultlineItems.GrapplingHookItem.create(plugin));
        hook.shape("TCT", "IRI", " I ");
        hook.setIngredient('T', Material.TRIPWIRE_HOOK);
        Material chain = Material.matchMaterial("IRON_CHAIN") != null ? Material.matchMaterial("IRON_CHAIN") : Material.matchMaterial("CHAIN"); // renamed in 1.21.9
        hook.setIngredient('C', chain);
        hook.setIngredient('I', Material.IRON_INGOT); hook.setIngredient('R', Material.FISHING_ROD);
        FaultlineItems.addRecipeSafely(hook);
    }

    static ItemStack withAmount(ItemStack s, int n) { s.setAmount(n); return s; }

    /** Our paper-based items are never plain paper in a recipe (books, maps...). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof org.bukkit.Keyed k && k.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))) return; // ours
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || !it.hasItemMeta()) continue;
            PersistentDataContainer c = it.getItemMeta().getPersistentDataContainer();
            boolean bossItem = c.getKeys().stream().anyMatch(key -> key.getNamespace().equals("faultlinebosses") && key.getKey().equals("wild_item"));
            if (c.has(gearKey, PersistentDataType.STRING) || bossItem) { e.getInventory().setResult(null); return; }
        }
    }

    // =====================================================================================================
    //  worn / held effects
    // =====================================================================================================
    private void tick() {
        ticks++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { tickPlayer(p); } catch (RuntimeException e) {
                if (ticks % 1200 == 0) plugin.getLogger().log(java.util.logging.Level.WARNING, "[Gear] " + p.getName() + ":", e);
            }
        }
        if (ticks % 2 == 0) for (Show s : shows.values()) s.turn(ticks);
    }

    private void tickPlayer(Player p) {
        {
            String head = id(p.getInventory().getHelmet());
            boolean lantern = "lantern_of_souls".equals(id(p.getInventory().getItemInMainHand())) || "lantern_of_souls".equals(id(p.getInventory().getItemInOffHand()));
            Block want = null;
            if ("mining_helmet".equals(head)) {
                if (ticks % 4 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 320, 0, true, false, true));
                org.bukkit.util.RayTraceResult hit = p.rayTraceBlocks(plugin.getConfig().getDouble("gear.headlamp-reach", 12));
                if (hit != null && hit.getHitBlock() != null) {
                    Block face = hit.getHitBlock().getRelative(hit.getHitBlockFace() != null ? hit.getHitBlockFace() : org.bukkit.block.BlockFace.UP);
                    if (face.getType().isAir()) want = face;
                }
                if (want == null) { Block eye = p.getEyeLocation().getBlock(); if (eye.getType().isAir()) want = eye; }
            }
            if (lantern) {
                if (want == null) { Block eye = p.getEyeLocation().getBlock(); if (eye.getType().isAir()) want = eye; }
                p.removePotionEffect(PotionEffectType.DARKNESS);
                if (ticks % 4 == 0) for (Entity e : p.getNearbyEntities(16, 8, 16)) {
                    if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
                    if (!le.isInvisible() && !le.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
                    le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, true, false, false));
                    p.spawnParticle(Particle.SOUL_FIRE_FLAME, le.getLocation().add(0, le.getHeight() / 2, 0), 6, 0.3, le.getHeight() / 3, 0.3, 0.01);
                }
            }
            light(p, want);
            if (ticks % 4 == 0) {
                boolean core = plugin.getAccessoryManager().hasEquipped(p, coreKey);
                modifier(p, Attribute.ARMOR, coreArmor, 3, AttributeModifier.Operation.ADD_NUMBER, core);
                modifier(p, Attribute.KNOCKBACK_RESISTANCE, coreKb, 0.25, AttributeModifier.Operation.ADD_NUMBER, core);
                modifier(p, Attribute.MAX_HEALTH, coreHealth, 4, AttributeModifier.Operation.ADD_NUMBER, core);
            }
        }
    }

    void modifier(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op, boolean want) {
        AttributeInstance ai = p.getAttribute(a);
        if (ai == null) return;
        AttributeModifier existing = null;
        for (AttributeModifier m : ai.getModifiers()) if (m.getKey().equals(key)) existing = m;
        if (want && existing == null) ai.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
        else if (!want && existing != null) ai.removeModifier(existing);
    }

    /** One light block per player, moved as they look around (only ever into air; put back the moment it moves). */
    void light(Player p, Block want) {
        Block old = lights.get(p.getUniqueId());
        if (old != null && old.equals(want)) return;
        if (old != null && old.getType() == Material.LIGHT) old.setType(Material.AIR, false);
        lights.remove(p.getUniqueId());
        if (want == null || !want.getType().isAir()) return;
        Light l = (Light) Material.LIGHT.createBlockData();
        l.setLevel((int) Math.max(1, Math.min(15, plugin.getConfig().getDouble("gear.light-level", 13))));
        want.setBlockData(l, false);
        lights.put(p.getUniqueId(), want);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { light(e.getPlayer(), null); }

    // =====================================================================================================
    //  boss music discs
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onJukebox(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() == Material.JUKEBOX && b.getState() instanceof Jukebox jb) {
            Player p = e.getPlayer();
            String stored = jb.getPersistentDataContainer().get(jukeKey, PersistentDataType.STRING);
            if (stored != null) { // ours is in: take it out
                e.setCancelled(true);
                eject(b, jb, stored);
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            String id = id(hand);
            if (id == null || !id.startsWith("disc_") || jb.hasRecord()) return;
            e.setCancelled(true);
            String disc = id.substring(5);
            jb.getPersistentDataContainer().set(jukeKey, PersistentDataType.STRING, disc);
            jb.update();
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            String[] d = DISCS.get(disc);
            Location c = b.getLocation().add(0.5, 1, 0.5);
            b.getWorld().playSound(c, d[1], SoundCategory.RECORDS, 4f, 1f);
            for (Player q : b.getWorld().getPlayers()) if (q.getLocation().distanceSquared(c) < 64 * 64) q.sendActionBar(Meteor.legacy(ChatColor.LIGHT_PURPLE + "Now Playing: " + d[0]));
            b.getWorld().spawnParticle(Particle.NOTE, c, 6, 0.5, 0.3, 0.5, 1);
        }
    }

    void eject(Block b, Jukebox jb, String disc) {
        jb.getPersistentDataContainer().remove(jukeKey);
        jb.update();
        String[] d = DISCS.get(disc);
        if (d != null) for (Player q : b.getWorld().getPlayers()) if (q.getLocation().distanceSquared(b.getLocation()) < 96 * 96) q.stopSound(d[1], SoundCategory.RECORDS);
        ItemStack it = disc(disc);
        if (it != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1.1, 0.5), it);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.JUKEBOX && b.getState() instanceof Jukebox jb) {
            String stored = jb.getPersistentDataContainer().get(jukeKey, PersistentDataType.STRING);
            if (stored != null) eject(b, jb, stored);
            return;
        }
        // a pedestal: its exhibit and the pedestal itself come back
        String exhibit = pedestal(b);
        if (exhibit == null) return;
        e.setDropItems(false);
        ItemStack shown = decode(exhibit);
        if (shown != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), shown);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item("museum_pedestal"));
        setPedestal(b, null, false);
    }

    // =====================================================================================================
    //  museum pedestals (remembered in their chunk; the exhibit floats above)
    // =====================================================================================================
    static long local(Block b) { return (b.getX() & 15) | ((long) (b.getZ() & 15) << 4) | ((long) (b.getY() + 4096) << 8); }

    static long worldKey(Block b) { return ((long) b.getWorld().getName().hashCode() << 40) ^ ((long) b.getX() << 20) ^ ((long) b.getZ() << 2) ^ b.getY(); }

    NamespacedKey slotKey(Block b) { return new NamespacedKey(plugin, "pedestal_" + local(b)); }

    /** "" for an empty pedestal, the exhibit (encoded) for a full one, null if it isn't a pedestal. */
    String pedestal(Block b) {
        PersistentDataContainer c = b.getChunk().getPersistentDataContainer();
        long[] all = c.getOrDefault(pedestalKey, PersistentDataType.LONG_ARRAY, new long[0]);
        long me = local(b);
        for (long v : all) if (v == me) return c.getOrDefault(slotKey(b), PersistentDataType.STRING, "");
        return null;
    }

    void setPedestal(Block b, String exhibit, boolean exists) {
        PersistentDataContainer c = b.getChunk().getPersistentDataContainer();
        long[] all = c.getOrDefault(pedestalKey, PersistentDataType.LONG_ARRAY, new long[0]);
        long me = local(b);
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (long v : all) set.add(v);
        if (exists) set.add(me); else set.remove(me);
        if (set.isEmpty()) c.remove(pedestalKey); else c.set(pedestalKey, PersistentDataType.LONG_ARRAY, set.stream().mapToLong(Long::longValue).toArray());
        if (exists && exhibit != null && !exhibit.isEmpty()) c.set(slotKey(b), PersistentDataType.STRING, exhibit);
        else c.remove(slotKey(b));
        Show old = shows.remove(worldKey(b));
        if (old != null) old.remove();
        if (exists && exhibit != null && !exhibit.isEmpty()) { ItemStack it = decode(exhibit); if (it != null) shows.put(worldKey(b), new Show(b, it)); }
    }

    static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

    static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try { int bar = s.indexOf('|'); return ItemStack.deserializeBytes(Base64.getDecoder().decode(bar >= 0 ? s.substring(bar + 1) : s)); } catch (RuntimeException e) { return null; }
    }

    static String owner(String s) { int bar = s == null ? -1 : s.indexOf('|'); return bar < 0 ? null : s.substring(0, bar); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!"museum_pedestal".equals(id(e.getItemInHand()))) return;
        setPedestal(e.getBlockPlaced(), "", true);
        e.getPlayer().sendActionBar(Meteor.legacy(ChatColor.GOLD + "Right-click the pedestal with anything to put it on show."));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPedestal(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        String exhibit = pedestal(b);
        if (exhibit == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (exhibit.isEmpty()) {
            if (hand.getType().isAir()) { p.sendActionBar(Meteor.legacy(ChatColor.GRAY + "Hold something to put it on show.")); return; }
            ItemStack one = hand.clone(); one.setAmount(1);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            setPedestal(b, p.getUniqueId() + "|" + encode(one), true);
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1.2f);
            return;
        }
        String own = owner(exhibit);
        if (own != null && !own.equals(p.getUniqueId().toString()) && !p.hasPermission("items.fgear")) {
            OfflineName n = new OfflineName(own);
            p.sendActionBar(Meteor.legacy(ChatColor.GRAY + "On show by " + n + ". Only they can take it back."));
            return;
        }
        ItemStack it = decode(exhibit);
        setPedestal(b, "", true);
        if (it != null) p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
    }

    record OfflineName(String uuid) {
        @Override public String toString() {
            try { String n = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName(); return n == null ? "someone" : n; } catch (RuntimeException e) { return "someone"; }
        }
    }

    /** An exhibit: a turning ItemDisplay for Java players, a small stand holding it for Bedrock ones. */
    final class Show {
        final ItemDisplay d;
        final ArmorStand stand;
        Show(Block b, ItemStack it) {
            Location at = b.getLocation().add(0.5, 1.45, 0.5);
            d = b.getWorld().spawn(at, ItemDisplay.class, x -> {
                x.setItemStack(it);
                x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                x.setPersistent(false);
                x.addScoreboardTag(TAG);
                x.setBrightness(new Display.Brightness(15, 15));
                x.setInterpolationDuration(10);
                x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
                x.setGlowing(it.getEnchantments().size() > 0);
            });
            stand = b.getWorld().spawn(b.getLocation().add(0.5, 0.55, 0.5), ArmorStand.class, s -> {
                s.setVisibleByDefault(false);
                s.setPersistent(false);
                s.addScoreboardTag(TAG);
                s.setInvisible(true);
                s.setGravity(false);
                s.setSmall(true);
                s.setMarker(false);
                s.getEquipment().setHelmet(it.clone());
                String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : null;
                if (name != null) { s.setCustomName(name); s.setCustomNameVisible(true); }
            });
            for (Player p : b.getWorld().getPlayers()) if (Weather.bedrock(p)) p.showEntity(plugin, stand);
        }
        void turn(int t) {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(0, (float) Math.sin(t * 0.05) * 0.05f, 0), new Quaternionf().rotationY(t * 0.05f), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        }
        void remove() { if (d.isValid()) d.remove(); if (stand.isValid()) stand.remove(); }
    }

    void showChunk(Chunk ch) {
        try {
            PersistentDataContainer c = ch.getPersistentDataContainer();
            long[] all = c.get(pedestalKey, PersistentDataType.LONG_ARRAY);
            if (all == null) return;
            for (long v : all) {
                Block b = ch.getWorld().getBlockAt((ch.getX() << 4) | (int) (v & 15), (int) (v >> 8) - 4096, (ch.getZ() << 4) | (int) ((v >> 4) & 15));
                String ex = c.getOrDefault(slotKey(b), PersistentDataType.STRING, "");
                if (ex.isEmpty() || shows.containsKey(worldKey(b))) continue;
                ItemStack it = decode(ex);
                if (it != null) shows.put(worldKey(b), new Show(b, it));
            }
        } catch (RuntimeException ignored) { }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) { showChunk(e.getChunk()); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (Iterator<Show> it = shows.values().iterator(); it.hasNext(); ) {
            Show s = it.next();
            if (!s.d.isValid() || s.d.getLocation().getChunk().equals(e.getChunk())) { s.remove(); it.remove(); }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Show s : shows.values()) if (s.stand.isValid()) { if (Weather.bedrock(p)) p.showEntity(plugin, s.stand); else p.hideEntity(plugin, s.stand); }
    }

    void shutdown() {
        for (UUID u : new ArrayList<>(lights.keySet())) { Block b = lights.remove(u); if (b != null && b.getType() == Material.LIGHT) b.setType(Material.AIR, false); }
        for (Show s : shows.values()) s.remove();
        shows.clear();
    }

    // =====================================================================================================
    //  /fgear <id> [amount] [player], /giveancientgear <n> <player>, /givedisc <id|random> <player>
    // =====================================================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.fgear")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String name = command.getName().toLowerCase(Locale.ROOT);
        String id;
        int argAt;
        if (name.equals("giveancientgear")) { id = "ancient_gear_part"; argAt = 0; }
        else if (name.equals("givedisc")) {
            if (args.length < 1) { sender.sendMessage(ChatColor.YELLOW + "/givedisc <" + String.join("|", DISCS.keySet()) + "|random> [player]"); return true; }
            String d = args[0].toLowerCase(Locale.ROOT);
            if (d.equals("random")) d = new ArrayList<>(DISCS.keySet()).get(new Random().nextInt(DISCS.size()));
            id = "disc_" + d; argAt = 1;
        } else {
            if (args.length < 1) { sender.sendMessage(ChatColor.YELLOW + "/fgear <" + String.join("|", IDS) + "> [amount] [player]"); return true; }
            id = args[0].toLowerCase(Locale.ROOT); argAt = 1;
        }
        ItemStack it = item(id);
        if (it == null) { sender.sendMessage(ChatColor.RED + "Unknown: " + id); return true; }
        int amount = 1;
        Player target = sender instanceof Player p ? p : null;
        for (int i = argAt; i < args.length; i++) {
            Player o = Bukkit.getPlayerExact(args[i]);
            if (o != null) target = o;
            else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
        }
        if (target == null) { sender.sendMessage("Who should get it?"); return true; }
        Player to = target;
        for (int i = 0; i < amount; i++) to.getInventory().addItem(item(id)).values().forEach(left -> to.getWorld().dropItemNaturally(to.getLocation(), left));
        if (!name.equals("givedisc") && !name.equals("giveancientgear")) sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + id + " to " + target.getName() + ".");
        else if (name.equals("givedisc")) target.sendMessage(ChatColor.LIGHT_PURPLE + "A music disc dropped for you: " + ChatColor.WHITE + DISCS.get(id.substring(5))[0]);
        return true;
    }

}
