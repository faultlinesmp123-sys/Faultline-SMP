package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * SPAWN PADS (Items 1.3.41, config spawn-pad:; replaced the Blue Obsidian Bed). A round respawn pad in any of the 16
 * colours. The real block is a CARPET of its colour (Bedrock sees that, plus a ring of coloured dust); Java players see a
 * metal-rimmed disc with glowing rings drawn by non-persistent BlockDisplays (tag {@code faultline_spawn_pad}), spawned
 * with the chunk. Pads are recorded in the chunk PDC {@code faultlineitems:spawn_pads} (pairs: packed local position,
 * colour ordinal). Standing on one and right-clicking (anything, or the pad itself; or starting to sneak on it) sets your
 * spawn there (player PDC {@code faultlineitems:spawn_pad} = "world;x;y;z"): you respawn on it after dying, in any
 * dimension. A bed or respawn anchor used later takes over. Anyone can break it (it drops itself); explosions blow it
 * off (it drops), pistons can't move it, water can't wash it away. A dye on a placed pad recolours it. Hard recipe:
 * diamond blocks, a totem, crying obsidian, a respawn anchor, netherite and a recovery compass.
 * Old Blue Obsidian Beds turn into white Spawn Pads (items: on join / any inventory opened / every 10 s; placed: on chunk
 * load, at the bed's head, and a spawn set on one moves to the pad).
 */
final class SpawnPad implements Listener {

    static final NamespacedKey RECIPE = NamespacedKey.fromString("faultlineitems:spawn_pad");
    static final NamespacedKey OLD_RECIPE = NamespacedKey.fromString("faultlineitems:blue_obsidian_bed");
    static final String TAG = "faultline_spawn_pad";
    static final DyeColor[] COLORS = DyeColor.values();

    private final FaultlineItems plugin;
    private final NamespacedKey itemKey, chunkKey, spawnKey, oldItemKey, oldChunkKey, oldSpawnKey;
    /** "world;x;y;z" -> its display pieces, for every pad in a loaded chunk. */
    private final Map<String, List<BlockDisplay>> shown = new HashMap<>();
    private final Map<String, Long> lastSet = new HashMap<>();

    SpawnPad(FaultlineItems plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "spawn_pad");
        chunkKey = new NamespacedKey(plugin, "spawn_pads");
        spawnKey = new NamespacedKey(plugin, "spawn_pad");
        oldItemKey = new NamespacedKey(plugin, "blue_bed");
        oldChunkKey = new NamespacedKey(plugin, "blue_bed");
        oldSpawnKey = new NamespacedKey(plugin, "blue_bed_spawn");
        try { Bukkit.removeRecipe(OLD_RECIPE); } catch (RuntimeException ignored) { }
        try {
            ShapedRecipe r = new ShapedRecipe(RECIPE, item(DyeColor.WHITE, 1));
            r.shape("DTD", "CRC", "NKN");
            r.setIngredient('D', Material.DIAMOND_BLOCK);
            r.setIngredient('T', Material.TOTEM_OF_UNDYING);
            r.setIngredient('C', Material.CRYING_OBSIDIAN);
            r.setIngredient('R', Material.RESPAWN_ANCHOR);
            r.setIngredient('N', Material.NETHERITE_INGOT);
            r.setIngredient('K', Material.RECOVERY_COMPASS);
            FaultlineItems.addRecipeSafely(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("[SpawnPad] recipe: " + e); }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) try { load(c); } catch (RuntimeException ex) { plugin.getLogger().warning("[SpawnPad] " + ex); }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) convertItems(p.getInventory()); }, 200L, 200L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("spawn-pad.enabled", true); }

    // ---------------------------------------------------------------- items

    static Material carpet(DyeColor c) { return Material.valueOf(c.name() + "_CARPET"); }
    static Material concrete(DyeColor c) { return Material.valueOf(c.name() + "_CONCRETE"); }

    static String nice(DyeColor c) {
        String s = c.name().toLowerCase().replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    ItemStack item(DyeColor color, int amount) {
        ItemStack it = new ItemStack(carpet(color), Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Spawn Pad " + ChatColor.GRAY + "(" + nice(color) + ")");
        m.setLore(List.of(ChatColor.GRAY + "Place it, stand on it and right-click:",
                ChatColor.GRAY + "you respawn on it when you die.",
                ChatColor.GRAY + "Works in every dimension. Anyone can break it.",
                ChatColor.DARK_GRAY + "Right-click a placed pad with a dye to recolour it."));
        try { m.setEnchantmentGlintOverride(true); } catch (RuntimeException | LinkageError ignored) { }
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, color.name());
        it.setItemMeta(m);
        return it;
    }

    /** The give command's types: every colour by name. */
    Map<String, Function<FaultlineItems, ItemStack>> types() {
        Map<String, Function<FaultlineItems, ItemStack>> m = new LinkedHashMap<>();
        for (DyeColor c : COLORS) m.put(c.name().toLowerCase(), pl -> item(c, 1));
        return m;
    }

    DyeColor colorOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String s = it.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return DyeColor.valueOf(s); } catch (IllegalArgumentException e) { return DyeColor.WHITE; }
    }

    private boolean oldBed(ItemStack it) {
        return it != null && it.getType() == Material.LIGHT_BLUE_BED && it.hasItemMeta() && it.getPersistentDataContainer().has(oldItemKey, PersistentDataType.BYTE);
    }

    /** Old Blue Obsidian Beds -> white Spawn Pads. */
    void convertItems(Inventory inv) {
        if (inv == null) return;
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) if (oldBed(all[i])) inv.setItem(i, item(DyeColor.WHITE, all[i].getAmount()));
    }

    // ---------------------------------------------------------------- placed pads

    private static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }
    private static String key(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    private int[] read(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(chunkKey, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    /** The pad's colour, or null when this block isn't a pad. */
    DyeColor padAt(Block b) {
        int l = local(b);
        int[] a = read(b.getChunk());
        for (int i = 0; i + 1 < a.length; i += 2) if (a[i] == l) return COLORS[Math.floorMod(a[i + 1], COLORS.length)];
        return null;
    }

    private void mark(Block b, DyeColor color) {
        Chunk c = b.getChunk();
        int l = local(b);
        List<Integer> list = new ArrayList<>();
        int[] a = read(c);
        for (int i = 0; i + 1 < a.length; i += 2) if (a[i] != l) { list.add(a[i]); list.add(a[i + 1]); }
        if (color != null) { list.add(l); list.add(color.ordinal()); }
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (list.isEmpty()) pdc.remove(chunkKey);
        else pdc.set(chunkKey, PersistentDataType.INTEGER_ARRAY, list.stream().mapToInt(Integer::intValue).toArray());
    }

    private static Location center(Block b, double up) { return new Location(b.getWorld(), b.getX() + 0.5, b.getY() + up, b.getZ() + 0.5); }

    // ---- the model (Java)

    private void unshow(String k) {
        List<BlockDisplay> l = shown.remove(k);
        if (l != null) for (BlockDisplay d : l) if (d.isValid()) d.remove();
    }

    private void show(Block b, DyeColor color) {
        String k = key(b);
        unshow(k);
        List<BlockDisplay> parts = new ArrayList<>();
        Location at = center(b, 0.004);
        boolean darkPad = color == DyeColor.BLACK || color == DyeColor.GRAY;
        BlockData base = (darkPad ? Material.LIGHT_GRAY_CONCRETE : Material.BLACK_CONCRETE).createBlockData();
        BlockData glow = concrete(color).createBlockData();
        BlockData rim = Material.IRON_BLOCK.createBlockData();
        BlockData bolt = Material.POLISHED_DEEPSLATE.createBlockData();
        // dark base disc
        ring(parts, at, base, 0.0, 0.84, 0.0, 0.07, 1, false);
        // silver rim, a bit taller
        ring(parts, at, rim, 0.82, 0.98, 0.0, 0.15, 14, false);
        // glowing rings + the middle
        ring(parts, at, glow, 0.62, 0.72, 0.065, 0.025, 12, true);
        ring(parts, at, glow, 0.38, 0.47, 0.065, 0.025, 9, true);
        ring(parts, at, glow, 0.0, 0.2, 0.065, 0.03, 1, true);
        // arrows between the rings (four little glowing bars pointing round)
        for (int i = 0; i < 4; i++) parts.add(box(at, glow, i * Math.PI / 2 + 0.3, 0.55, 0.20, 0.06, 0.07, 0.025, true));
        // four bolts on the rim
        for (int i = 0; i < 4; i++) parts.add(box(at, bolt, i * Math.PI / 2 + Math.PI / 4, 0.9, 0.08, 0.08, 0.15, 0.04, false));
        // a light strip on the rim's front
        parts.add(box(at, glow, Math.PI / 2, 0.985, 0.22, 0.01, 0.03, 0.06, true));
        shown.put(k, parts);
    }

    /** Segments of a flat ring between r0 and r1 (n = 1 and r0 = 0: an octagon of two boxes). */
    private void ring(List<BlockDisplay> parts, Location at, BlockData data, double r0, double r1, double y, double h, int n, boolean lit) {
        if (n <= 1) {
            double s = r1 * 2 * 0.83;
            for (int i = 0; i < 2; i++) parts.add(box(at, data, i * Math.PI / 4, 0, s, s, y, h, lit));
            return;
        }
        double rm = (r0 + r1) / 2, w = r1 - r0, len = 2 * Math.PI * rm / n * 1.12;
        for (int i = 0; i < n; i++) parts.add(box(at, data, 2 * Math.PI * i / n, rm, len, w, y, h, lit));
    }

    /** One box: `len` along the tangent, `w` along the radius, centred at radius rm, angle phi. */
    private BlockDisplay box(Location at, BlockData data, double phi, double rm, double len, double w, double y, double h, boolean lit) {
        float a = (float) (Math.PI / 2 - phi);
        Quaternionf q = new Quaternionf().rotateY(a);
        Vector3f half = q.transform(new Vector3f((float) len / 2, (float) h / 2, (float) w / 2));
        Vector3f c = new Vector3f((float) (rm * Math.cos(phi)), (float) (y + h / 2), (float) (rm * Math.sin(phi)));
        Vector3f t = c.sub(half);
        Location l = at.clone();
        l.setYaw(0); l.setPitch(0);
        return at.getWorld().spawn(l, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setBlock(data);
            d.setTransformation(new Transformation(t, q, new Vector3f((float) len, (float) h, (float) w), new Quaternionf()));
            d.setViewRange(0.6f);
            if (lit) d.setBrightness(new Display.Brightness(15, 15));
        });
    }

    private void load(Chunk c) {
        convertOldBeds(c);
        int[] a = read(c);
        if (a.length == 0) return;
        World w = c.getWorld();
        List<Integer> keep = new ArrayList<>();
        boolean dirty = false;
        for (int i = 0; i + 1 < a.length; i += 2) {
            int l = a[i];
            Block b = w.getBlockAt((c.getX() << 4) | (l & 15), (l >> 8) - 2048, (c.getZ() << 4) | ((l >> 4) & 15));
            DyeColor col = COLORS[Math.floorMod(a[i + 1], COLORS.length)];
            if (!b.getType().name().endsWith("_CARPET")) { dirty = true; continue; } // gone while we weren't looking
            keep.add(l); keep.add(a[i + 1]);
            show(b, col);
        }
        if (dirty) {
            if (keep.isEmpty()) c.getPersistentDataContainer().remove(chunkKey);
            else c.getPersistentDataContainer().set(chunkKey, PersistentDataType.INTEGER_ARRAY, keep.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> { if (c.isLoaded()) try { load(c); } catch (RuntimeException ex) { plugin.getLogger().warning("[SpawnPad] " + ex); } });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        String w = e.getWorld().getName() + ";";
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        for (String k : new ArrayList<>(shown.keySet())) {
            if (!k.startsWith(w)) continue;
            String[] p = k.split(";");
            if ((Integer.parseInt(p[1]) >> 4) == cx && (Integer.parseInt(p[3]) >> 4) == cz) unshow(k);
        }
    }

    void shutdown() {
        for (String k : new ArrayList<>(shown.keySet())) unshow(k);
        for (World w : Bukkit.getWorlds()) for (Entity en : w.getEntitiesByClass(BlockDisplay.class)) if (en.getScoreboardTags().contains(TAG)) en.remove();
    }

    // ---- placing, breaking, recolouring

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        DyeColor col = colorOf(e.getItemInHand());
        if (col == null) { if (padAt(b) != null) { mark(b, null); unshow(key(b)); } return; }
        if (!on()) { e.setCancelled(true); e.getPlayer().sendMessage(ChatColor.RED + "Spawn Pads are turned off."); return; }
        mark(b, col);
        show(b, col);
        b.getWorld().playSound(center(b, 0.2), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.4f);
        e.getPlayer().sendMessage(ChatColor.AQUA + "Spawn Pad placed. " + ChatColor.GRAY + "Stand on it and right-click to make it your spawn.");
    }

    /** Takes the pad away; drops it unless `drop` is false. */
    private void destroy(Block b, boolean drop) {
        DyeColor col = padAt(b);
        if (col == null) return;
        mark(b, null);
        unshow(key(b));
        if (b.getType().name().endsWith("_CARPET")) b.setType(Material.AIR, false);
        if (drop) b.getWorld().dropItemNaturally(center(b, 0.3), item(col, 1));
        b.getWorld().playSound(center(b, 0.2), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 1.2f);
        b.getWorld().spawnParticle(Particle.DUST, center(b, 0.2), 20, 0.4, 0.1, 0.4, 0, new Particle.DustOptions(rgb(col), 1.2f));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (padAt(b) != null) {
            e.setDropItems(false);
            boolean drop = e.getPlayer().getGameMode() != GameMode.CREATIVE;
            Bukkit.getScheduler().runTask(plugin, () -> destroy(b, drop));
            return;
        }
        Block up = b.getRelative(0, 1, 0); // its support goes: the carpet would pop off as a plain carpet
        if (padAt(up) != null) destroy(up, true);
    }

    /** Anything else that knocks the carpet off (a fluid it was missed by, a support pulled away...) drops the pad. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (!it.getType().name().endsWith("_CARPET") || colorOf(it) != null) return;
        Block b = e.getLocation().getBlock();
        DyeColor col = padAt(b);
        if (col == null || it.getType() != carpet(col)) return;
        e.getEntity().setItemStack(item(col, 1));
        mark(b, null);
        unshow(key(b));
    }

    private void blast(List<Block> list) {
        for (Block b : new ArrayList<>(list)) if (padAt(b) != null) { list.remove(b); destroy(b, true); }
    }

    @EventHandler(ignoreCancelled = true) public void onBoom(EntityExplodeEvent e) { blast(e.blockList()); }
    @EventHandler(ignoreCancelled = true) public void onBoom(BlockExplodeEvent e) { blast(e.blockList()); }
    @EventHandler(ignoreCancelled = true) public void onPush(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (padAt(b) != null || padAt(b.getRelative(0, 1, 0)) != null) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true) public void onPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (padAt(b) != null || padAt(b.getRelative(0, 1, 0)) != null) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true) public void onFlow(BlockFromToEvent e) { if (padAt(e.getToBlock()) != null) e.setCancelled(true); }

    // ---------------------------------------------------------------- using it

    /** The pad the player is standing on (or right beside, within reach of its rim), or null. */
    Block standingOn(Player p) {
        Location l = p.getLocation();
        Block feet = l.getBlock();
        Block best = null;
        double bestD = 1.05 * 1.05;
        for (int dy = 0; dy >= -1; dy--) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block b = feet.getRelative(dx, dy, dz);
            if (padAt(b) == null) continue;
            if (Math.abs(l.getY() - (b.getY() + 0.0625)) > 0.7) continue;
            double ddx = l.getX() - (b.getX() + 0.5), ddz = l.getZ() - (b.getZ() + 0.5), d = ddx * ddx + ddz * ddz;
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        // a dye on a pad = recolour it
        if (clicked != null && padAt(clicked) != null && e.getItem() != null && e.getItem().getType().name().endsWith("_DYE")) {
            DyeColor to;
            try { to = DyeColor.valueOf(e.getItem().getType().name().replace("_DYE", "")); } catch (IllegalArgumentException ex) { return; }
            e.setCancelled(true);
            if (to == padAt(clicked)) return;
            clicked.setType(carpet(to), false);
            mark(clicked, to);
            show(clicked, to);
            if (p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
            clicked.getWorld().playSound(center(clicked, 0.2), Sound.ITEM_DYE_USE, 1f, 1f);
            return;
        }
        Block pad = standingOn(p);
        if (pad == null && clicked != null && padAt(clicked) != null && p.getLocation().distanceSquared(center(clicked, 0.06)) <= 1.6 * 1.6) pad = clicked;
        if (pad == null) return;
        if (setSpawn(p, pad)) { e.setCancelled(true); e.setUseItemInHand(org.bukkit.event.Event.Result.DENY); }
    }

    /** Starting to sneak on a pad sets it too (some Bedrock controls don't send a right-click on what you stand on). */
    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Block pad = standingOn(e.getPlayer());
        if (pad != null) setSpawn(e.getPlayer(), pad);
    }

    /** True if it was a new spawn. */
    boolean setSpawn(Player p, Block pad) {
        if (!on()) return false;
        String spot = key(pad);
        if (spot.equals(p.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING))) {
            bar(p, ChatColor.GRAY + "This Spawn Pad is already your spawn.");
            return false;
        }
        Long last = lastSet.get(p.getUniqueId() + spot);
        if (last != null && System.currentTimeMillis() - last < 500) return false;
        lastSet.put(p.getUniqueId() + spot, System.currentTimeMillis());
        p.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, spot);
        try { p.setRespawnLocation(null, true); } catch (RuntimeException | LinkageError ignored) { } // the pad is THE spawn now
        DyeColor col = padAt(pad);
        p.sendMessage(ChatColor.AQUA + "Spawn set! " + ChatColor.GRAY + "You'll respawn on this Spawn Pad.");
        bar(p, ChatColor.AQUA + "Spawn set");
        p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.9f, 1.2f);
        burst(pad, col);
        return true;
    }

    private void burst(Block pad, DyeColor col) {
        Particle.DustOptions dust = new Particle.DustOptions(rgb(col), 1.4f);
        for (int i = 0; i < 24; i++) {
            double a = 2 * Math.PI * i / 24;
            pad.getWorld().spawnParticle(Particle.DUST, center(pad, 0.15).add(Math.cos(a) * 0.8, 0, Math.sin(a) * 0.8), 1, 0, 0.05, 0, 0, dust);
        }
        pad.getWorld().spawnParticle(Particle.END_ROD, center(pad, 0.3), 12, 0.2, 0.4, 0.2, 0.03);
    }

    static Color rgb(DyeColor c) {
        Color f = c.getColor();
        return c == DyeColor.BLACK ? Color.fromRGB(60, 60, 70) : f;
    }

    /** Pads near players: a slow swirl of their colour (Bedrock sees the pad's shape this way too). */
    private int t;
    private void tick() {
        t++;
        if (shown.isEmpty()) return;
        for (Map.Entry<String, List<BlockDisplay>> en : new ArrayList<>(shown.entrySet())) {
            String[] k = en.getKey().split(";");
            World w = Bukkit.getWorld(k[0]);
            if (w == null) continue;
            Block b = w.getBlockAt(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
            if (!b.getType().name().endsWith("_CARPET")) { mark(b, null); unshow(en.getKey()); continue; } // gone (no drop: we never saw how)
            Location c = center(b, 0.12);
            boolean near = false, bedrock = false;
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 24 * 24) { near = true; if (Edition.bedrock(p)) bedrock = true; }
            if (!near) continue;
            DyeColor col = padAt(b);
            if (col == null) continue;
            Particle.DustOptions dust = new Particle.DustOptions(rgb(col), bedrock ? 1.0f : 0.7f);
            int n = bedrock ? 12 : 3;
            for (int i = 0; i < n; i++) {
                double a = t * 0.35 + 2 * Math.PI * i / n;
                double r = bedrock ? 0.85 : 0.67;
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, dust);
            }
        }
    }

    // ---------------------------------------------------------------- respawning on it

    void forget(Player p) { p.getPersistentDataContainer().remove(spawnKey); }

    /** A bed or respawn anchor set later takes over. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent e) {
        String c = e.getCause().name();
        if (c.equals("BED") || c.equals("RESPAWN_ANCHOR")) forget(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        String s = p.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING);
        if (s == null || !on()) return;
        try { if (e.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return; } catch (RuntimeException | LinkageError ignored) { }
        String[] a = s.split(";");
        World w = a.length == 4 ? Bukkit.getWorld(a[0]) : null;
        if (w == null) return;
        Block pad;
        try { pad = w.getBlockAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])); } catch (NumberFormatException ex) { forget(p); return; }
        DyeColor col = padAt(pad);
        if (col == null || !pad.getType().name().endsWith("_CARPET")) {
            forget(p);
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(ChatColor.RED + "Your Spawn Pad was destroyed."));
            return;
        }
        if (!free(pad.getRelative(0, 1, 0)) || !free(pad.getRelative(0, 2, 0))) {
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(ChatColor.GRAY + "Your Spawn Pad is blocked, so you couldn't respawn on it."));
            return;
        }
        Location at = center(pad, 0.07);
        at.setYaw(p.getLocation().getYaw());
        e.setRespawnLocation(at);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.7f, 1.6f);
            burst(pad, col);
        }, 2L);
    }

    private static boolean free(Block b) { return !b.getType().isSolid() && !b.isLiquid() && b.getType() != Material.FIRE && b.getType() != Material.SOUL_FIRE && b.getType() != Material.LAVA; }

    private static void bar(Player p, String s) { p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(s)); }

    // ---------------------------------------------------------------- old Blue Obsidian Beds

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        convertItems(p.getInventory());
        convertItems(p.getEnderChest());
        String old = p.getPersistentDataContainer().get(oldSpawnKey, PersistentDataType.STRING);
        if (old != null) {
            p.getPersistentDataContainer().remove(oldSpawnKey);
            if (!p.getPersistentDataContainer().has(spawnKey, PersistentDataType.STRING)) p.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, old);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        convertItems(e.getView().getTopInventory());
        convertItems(e.getPlayer().getInventory());
    }

    /** Placed Blue Obsidian Beds become a white Spawn Pad where the head was. */
    private void convertOldBeds(Chunk c) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        int[] a = pdc.get(oldChunkKey, PersistentDataType.INTEGER_ARRAY);
        if (a == null) return;
        pdc.remove(oldChunkKey);
        World w = c.getWorld();
        for (int l : a) {
            Block b = w.getBlockAt((c.getX() << 4) | (l & 15), (l >> 8) - 2048, (c.getZ() << 4) | ((l >> 4) & 15));
            if (b.getType() != Material.LIGHT_BLUE_BED || !(b.getBlockData() instanceof Bed bed)) continue;
            Block head = bed.getPart() == Bed.Part.HEAD ? b : b.getRelative(bed.getFacing());
            Block foot = bed.getPart() == Bed.Part.HEAD ? b.getRelative(bed.getFacing().getOppositeFace()) : b;
            if (head.getType() != Material.LIGHT_BLUE_BED) head = b;
            if (foot.getType() == Material.LIGHT_BLUE_BED) foot.setType(Material.AIR, false);
            if (head.getType() == Material.LIGHT_BLUE_BED) head.setType(Material.AIR, false);
            Block below = head.getRelative(0, -1, 0);
            if (below.getType().isSolid()) {
                head.setType(carpet(DyeColor.WHITE), false);
                mark(head, DyeColor.WHITE);
            } else {
                w.dropItemNaturally(center(head, 0.3), item(DyeColor.WHITE, 1));
            }
            plugin.getLogger().info("[SpawnPad] a Blue Obsidian Bed at " + key(head) + " is a Spawn Pad now");
        }
    }
}
