package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BLUE OBSIDIAN (Items 1.2.75, config blue-obsidian:, data blue_portals.yml).
 * Crafted from 8 Obsidian around a Lapis Lazuli. Placed, it's a real CRYING_OBSIDIAN block (hard, blast-proof, immovable)
 * remembered in the chunk's PDC (faultlineitems:blue_obsidian) with a blue-obsidian ItemDisplay over it for Java players
 * (model faultline:blue_obsidian); breaking it drops Blue Obsidian, not crying obsidian.
 * A closed frame of Blue Obsidian, any shape (a ring works), lit with Flint and Steel or a Fire Charge, opens a portal, but
 * ONLY IN THE END. Stepping through takes you to the Overworld at a random spot within blue-obsidian.max-xz (500) of 0, 0
 * on the surface. Nothing is built or remembered on the Overworld side: no portal back.
 */
final class BlueObsidian implements Listener {

    static final String DISPLAY_TAG = "faultline_blue_obsidian";

    private final FaultlineItems plugin;
    final NamespacedKey itemKey, chunkKey, recipeKey;
    private final File file;
    /** world name -> block keys of placed Blue Obsidian in loaded chunks. */
    final Map<String, Set<Long>> blocks = new HashMap<>();
    /** world name -> block key -> its overlay. */
    private final Map<String, Map<Long, ItemDisplay>> overlays = new HashMap<>();
    /** Open portals. */
    final Map<UUID, Portal> portals = new LinkedHashMap<>();
    /** world name -> portal cell -> portal. */
    private final Map<String, Map<Long, Portal>> cells = new HashMap<>();
    private final Set<UUID> travelling = new HashSet<>();

    static final class Portal {
        final UUID id; final String world; final Axis axis;
        final Set<Long> inside = new HashSet<>(), frame = new HashSet<>();
        /** 1.2.76: the glowing blue sheet in each cell (Java), spawned with its chunk. */
        final Map<Long, ItemDisplay> sheets = new HashMap<>();
        boolean ready = true;          // false while the summoning animation plays
        Portal(UUID id, String world, Axis axis) { this.id = id; this.world = world; this.axis = axis; }
    }

    BlueObsidian(FaultlineItems plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "blue_obsidian");
        chunkKey = new NamespacedKey(plugin, "blue_obsidian");
        recipeKey = new NamespacedKey(plugin, "blue_obsidian");
        file = new File(plugin.getDataFolder(), "blue_portals.yml");
        ShapedRecipe r = new ShapedRecipe(recipeKey, item(1));
        r.shape("OOO", "OLO", "OOO");
        r.setIngredient('O', Material.OBSIDIAN);
        r.setIngredient('L', Material.LAPIS_LAZULI);
        try { Bukkit.addRecipe(r); } catch (IllegalStateException ignored) { } // a /reload
        load(); // first, so frames of open portals spawn lit
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) chunkIn(c);
        Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::travelTick, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambient, 5L, 5L);
    }

    boolean enabled() { return plugin.getConfig().getBoolean("blue-obsidian.enabled", true); }

    // ---------------------------------------------------------------- the item

    ItemStack item(int amount) {
        ItemStack it = new ItemStack(Material.CRYING_OBSIDIAN, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Blue Obsidian", NamedTextColor.BLUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Build a closed frame of it in the End", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("and light it: a portal to the Overworld.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("faultline", "blue_obsidian"));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) {
        if (it == null || it.getType() != Material.CRYING_OBSIDIAN || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    /** Blue Obsidian never goes into vanilla recipes (respawn anchors take crying obsidian). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r instanceof ShapedRecipe s && s.getKey().equals(recipeKey)) return;
        for (ItemStack it : e.getInventory().getMatrix()) if (isItem(it)) { e.getInventory().setResult(null); return; }
    }

    // ---------------------------------------------------------------- placed blocks

    static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF); }
    static long key(Block b) { return key(b.getX(), b.getY(), b.getZ()); }
    static int kx(long k) { return (int) (k >> 38); }
    static int kz(long k) { return (int) (k << 26 >> 38); }
    static int ky(long k) { return (int) (k << 52 >> 52); }

    boolean isBlue(Block b) {
        if (b.getType() != Material.CRYING_OBSIDIAN) return false;
        Set<Long> s = blocks.get(b.getWorld().getName());
        return s != null && s.contains(key(b));
    }

    private int[] read(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(chunkKey, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    private static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }

    private void mark(Block b, boolean on) {
        Chunk c = b.getChunk();
        int l = local(b);
        List<Integer> list = new ArrayList<>();
        for (int v : read(c)) if (v != l) list.add(v);
        if (on) list.add(l);
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (list.isEmpty()) pdc.remove(chunkKey);
        else pdc.set(chunkKey, PersistentDataType.INTEGER_ARRAY, list.stream().mapToInt(Integer::intValue).toArray());
        Set<Long> s = blocks.computeIfAbsent(b.getWorld().getName(), w -> new HashSet<>());
        if (on) { s.add(key(b)); overlay(b); }
        else { s.remove(key(b)); unoverlay(b.getWorld().getName(), key(b)); }
    }

    void chunkIn(Chunk c) {
        int[] a = read(c);
        if (a.length == 0) return;
        World w = c.getWorld();
        Set<Long> s = blocks.computeIfAbsent(w.getName(), x -> new HashSet<>());
        boolean dirty = false;
        List<Integer> keep = new ArrayList<>();
        for (int v : a) {
            Block b = w.getBlockAt((c.getX() << 4) + (v & 15), (v >> 8) - 2048, (c.getZ() << 4) + (v >> 4 & 15));
            if (b.getType() != Material.CRYING_OBSIDIAN) { dirty = true; continue; } // gone while we weren't looking (WorldEdit...)
            keep.add(v);
            s.add(key(b));
            overlay(b);
        }
        if (dirty) {
            if (keep.isEmpty()) c.getPersistentDataContainer().remove(chunkKey);
            else c.getPersistentDataContainer().set(chunkKey, PersistentDataType.INTEGER_ARRAY, keep.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    void chunkOut(Chunk c) {
        String w = c.getWorld().getName();
        Set<Long> s = blocks.get(w);
        if (s == null) return;
        for (int v : read(c)) {
            long k = key((c.getX() << 4) + (v & 15), (v >> 8) - 2048, (c.getZ() << 4) + (v >> 4 & 15));
            s.remove(k);
            unoverlay(w, k);
        }
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) { chunkIn(e.getChunk()); }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) { chunkOut(e.getChunk()); }

    /** The blue look for Java players: an ItemDisplay a hair bigger than the block, lit like the air beside it. */
    private void overlay(Block b) {
        Map<Long, ItemDisplay> m = overlays.computeIfAbsent(b.getWorld().getName(), w -> new HashMap<>());
        ItemDisplay old = m.get(key(b));
        if (old != null && old.isValid()) return;
        Location at = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        ItemDisplay d = (ItemDisplay) b.getWorld().spawnEntity(at, EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(DISPLAY_TAG);
        d.addScoreboardTag("faultline_no_bedrock_fx"); // Bedrock sees the real block
        d.setPersistent(false);
        boolean lit = isLit(b.getWorld().getName(), key(b));
        d.setItemStack(model(lit ? "blue_obsidian_lit" : "blue_obsidian"));
        try { d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE); } catch (RuntimeException ignored) { }
        float s = 1.004f;
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        int bl = 0, sl = 0;
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.getType().isOccluding()) continue;
            try { bl = Math.max(bl, n.getLightFromBlocks()); sl = Math.max(sl, n.getLightFromSky()); } catch (RuntimeException ignored) { }
        }
        int fb = bl, fs = sl;
        d.setInterpolationDuration(3);
        try { d.setBrightness(lit ? new Display.Brightness(15, 15) : new Display.Brightness(Math.max(fb, 4), fs)); } catch (RuntimeException ignored) { }
        darkLight.put(key(b), Math.max(fb, 4) * 16 + fs);
        m.put(key(b), d);
    }

    private final Map<Long, Integer> darkLight = new HashMap<>();
    /** world -> frame blocks of open portals (they glow). */
    private final Map<String, Set<Long>> lit = new HashMap<>();

    boolean isLit(String world, long k) {
        Set<Long> s = lit.get(world);
        return s != null && s.contains(k);
    }

    /** A frame block lights up (glowing veins, full brightness) or goes dark again. */
    void setLit(String world, long k, boolean on) {
        Set<Long> s = lit.computeIfAbsent(world, w -> new HashSet<>());
        if (on ? !s.add(k) : !s.remove(k)) return;
        Map<Long, ItemDisplay> m = overlays.get(world);
        ItemDisplay d = m == null ? null : m.get(k);
        if (d == null || !d.isValid()) return;
        d.setItemStack(model(on ? "blue_obsidian_lit" : "blue_obsidian"));
        int dl = darkLight.getOrDefault(k, 4 * 16);
        try { d.setBrightness(on ? new Display.Brightness(15, 15) : new Display.Brightness(dl / 16, dl % 16)); } catch (RuntimeException ignored) { }
    }

    /** What a display shows: an item with one of our models (PAPER, so translucent textures stay translucent). */
    static ItemStack model(String id) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", id));
        it.setItemMeta(m);
        return it;
    }

    private void unoverlay(String world, long k) {
        Map<Long, ItemDisplay> m = overlays.get(world);
        if (m == null) return;
        ItemDisplay d = m.remove(k);
        if (d != null && d.isValid()) d.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isItem(e.getItemInHand())) return;
        mark(e.getBlockPlaced(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isBlue(b)) return;
        mark(b, false);
        for (Portal p : new ArrayList<>(portals.values())) if (p.world.equals(b.getWorld().getName()) && p.frame.contains(key(b))) close(p, true);
        breaking.put(key(b), b.getWorld().getName());
    }

    private final Map<Long, String> breaking = new HashMap<>();

    /** A broken Blue Obsidian block drops Blue Obsidian (the tool rules for crying obsidian still apply). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Block b = e.getBlock();
        String w = breaking.remove(key(b));
        if (w == null || !w.equals(b.getWorld().getName())) return;
        for (Item i : e.getItems()) {
            ItemStack s = i.getItemStack();
            if (s.getType() == Material.CRYING_OBSIDIAN && !isItem(s)) i.setItemStack(item(s.getAmount()));
        }
    }

    // ---------------------------------------------------------------- lighting a portal

    @EventHandler(priority = EventPriority.HIGH)
    public void onLight(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        ItemStack hand = e.getItem();
        if (hand == null || (hand.getType() != Material.FLINT_AND_STEEL && hand.getType() != Material.FIRE_CHARGE)) return;
        Block clicked = e.getClickedBlock();
        if (!isBlue(clicked)) return;
        Player p = e.getPlayer();
        e.setCancelled(true); // no fire on our frame
        if (!enabled()) return;
        if (clicked.getWorld().getEnvironment() != World.Environment.THE_END) {
            p.sendActionBar(Component.text("Blue Obsidian portals only open in the End.", NamedTextColor.BLUE));
            return;
        }
        Block start = clicked.getRelative(e.getBlockFace());
        if (cellOwner(start) != null) return; // already open (or opening)
        Portal made = null;
        for (Axis axis : new Axis[]{Axis.X, Axis.Z}) {
            made = fill(start, axis);
            if (made != null) break;
        }
        if (made == null) {
            p.sendActionBar(Component.text("That isn't a closed Blue Obsidian frame (light it from the inside).", NamedTextColor.RED));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) {
            if (hand.getType() == Material.FIRE_CHARGE) hand.setAmount(hand.getAmount() - 1);
            else p.damageItemStack(EquipmentSlot.HAND, 1);
        }
        summon(made, clicked);
    }

    /** The air enclosed by Blue Obsidian in one vertical plane, flood-filled from start; null if it leaks or is too big. */
    Portal fill(Block start, Axis axis) {
        if (!fillable(start)) return null;
        int max = plugin.getConfig().getInt("blue-obsidian.max-portal-cells", 441);
        World w = start.getWorld();
        Portal p = new Portal(UUID.randomUUID(), w.getName(), axis);
        Deque<Block> todo = new ArrayDeque<>();
        todo.add(start);
        p.inside.add(key(start));
        BlockFace[] dirs = axis == Axis.X
                ? new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST}
                : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH};
        while (!todo.isEmpty()) {
            Block b = todo.poll();
            for (BlockFace f : dirs) {
                Block n = b.getRelative(f);
                long k = key(n);
                if (p.inside.contains(k) || p.frame.contains(k)) continue;
                if (n.getY() < w.getMinHeight() || n.getY() >= w.getMaxHeight()) return null;
                if (isBlue(n)) { p.frame.add(k); continue; }
                if (!fillable(n) || cellOwner(n) != null) return null;
                p.inside.add(k);
                if (p.inside.size() > max) return null;
                todo.add(n);
            }
        }
        return p.inside.size() >= plugin.getConfig().getInt("blue-obsidian.min-portal-cells", 2) ? p : null;
    }

    private static boolean fillable(Block b) {
        Material m = b.getType();
        return m.isAir() || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LIGHT || m == Material.NETHER_PORTAL;
    }

    Portal cellOwner(Block b) {
        Map<Long, Portal> m = cells.get(b.getWorld().getName());
        return m == null ? null : m.get(key(b));
    }

    static final org.bukkit.Color CYAN = org.bukkit.Color.fromRGB(90, 210, 255), DEEP = org.bukkit.Color.fromRGB(40, 80, 255),
            WHITE = org.bukkit.Color.fromRGB(220, 245, 255);

    Location at(World w, long k) { return new Location(w, kx(k) + 0.5, ky(k) + 0.5, kz(k) + 0.5); }

    /** Claims the cells at once (nobody lights it twice), then plays the summoning; it works once that's done. */
    void summon(Portal p, Block clicked) {
        p.ready = false;
        portals.put(p.id, p);
        Map<Long, Portal> m = cells.computeIfAbsent(p.world, w -> new HashMap<>());
        for (long k : p.inside) m.put(k, p);
        save();
        World w = clicked.getWorld();
        Location c = centre(p, w);
        // the frame lights up as a charge runs round it both ways from where it was lit
        List<List<Long>> layers = frameLayers(p, key(clicked));
        // the inside appears from the middle out
        List<Long> order = new ArrayList<>(p.inside);
        order.sort(Comparator.comparingDouble(k -> at(w, k).distanceSquared(c)));
        double radius = Math.sqrt(order.isEmpty() ? 1 : at(w, order.get(order.size() - 1)).distanceSquared(c)) + 0.5;
        int frameTicks = layers.size() * 2, gather = 12, fillTicks = Math.max(8, Math.min(24, order.size() / 3)), burst = 14;
        Location lit0 = new Location(clicked.getWorld(), clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5);
        w.playSound(lit0, Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1f, 0.8f);
        w.playSound(lit0, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1f, 1.3f);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, lit0, 25, 0.3, 0.3, 0.3, 0.08);
        w.spawnParticle(Particle.DUST, lit0, 20, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(CYAN, 1.6f));
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0, filled = 0;
            @Override public void run() {
                if (portals.get(p.id) != p) { cancel(); return; } // broken while it opened
                if (t < frameTicks) {                       // 1) the charge runs round the frame
                    if (t % 2 == 0) {
                        int li = t / 2;
                        float prog = layers.isEmpty() ? 1 : (float) li / layers.size();
                        for (long k : layers.get(li)) {
                            setLit(p.world, k, true);
                            Location l = at(w, k);
                            w.spawnParticle(Particle.ELECTRIC_SPARK, l, 6, 0.35, 0.35, 0.35, 0.05);
                            w.spawnParticle(Particle.DUST, l, 5, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(CYAN, 1.3f));
                            w.spawnParticle(Particle.END_ROD, l, 1, 0.2, 0.2, 0.2, 0.01);
                        }
                        Location l = at(w, layers.get(li).get(0));
                        w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.2f, 0.6f + 1.3f * prog);
                        if (li % 3 == 0) w.playSound(l, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.5f, 1.0f + prog);
                    }
                } else if (t < frameTicks + gather) {       // 2) light pours from the frame into the middle
                    int g = t - frameTicks;
                    if (g == 0) {
                        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.5f, 0.7f);
                        w.playSound(c, Sound.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 0.6f, 1.6f);
                    }
                    for (long k : p.frame) {
                        if (ThreadLocalRandom.current().nextInt(3) != 0) continue;
                        Location l = at(w, k);
                        org.bukkit.util.Vector v = c.toVector().subtract(l.toVector());
                        w.spawnParticle(Particle.END_ROD, l, 0, v.getX(), v.getY(), v.getZ(), 0.07);
                    }
                    w.spawnParticle(Particle.DUST, c, 6 + g, 0.15 * g / 4.0, 0.15 * g / 4.0, 0.15 * g / 4.0, 0, new Particle.DustOptions(WHITE, 2f));
                } else if (t < frameTicks + gather + fillTicks) { // 3) the sheet grows out from the middle
                    int f = t - frameTicks - gather;
                    int want = (int) Math.ceil(order.size() * (f + 1) / (double) fillTicks);
                    for (; filled < want && filled < order.size(); filled++) {
                        long k = order.get(filled);
                        Block b = w.getBlockAt(kx(k), ky(k), kz(k));
                        light(b);
                        sheet(p, w, k, true);
                        if (filled % 3 == 0) w.spawnParticle(Particle.DUST, at(w, k), 3, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(DEEP, 1.4f));
                    }
                    if (f % 4 == 0) w.playSound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.BLOCKS, 0.8f, 0.8f + f / (float) fillTicks);
                } else if (t < frameTicks + gather + fillTicks + burst) { // 4) the burst
                    int bt = t - frameTicks - gather - fillTicks;
                    if (bt == 0) {
                        p.ready = true;
                        w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 1.2f, 1.25f);
                        w.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 1.5f, 1.4f);
                        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.35f, 1.9f);
                        w.spawnParticle(Particle.FIREWORK, c, 60, radius * 0.3, radius * 0.3, radius * 0.3, 0.25);
                        w.spawnParticle(Particle.REVERSE_PORTAL, c, 120, radius * 0.4, radius * 0.4, radius * 0.4, 0.4);
                        for (long k : p.frame) w.spawnParticle(Particle.END_ROD, at(w, k), 2, 0.2, 0.2, 0.2, 0.06);
                    }
                    ring(p, w, c, radius + bt * 0.6, bt < 7 ? CYAN : DEEP); // a shockwave ring in the portal's plane
                } else { cancel(); }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Frame blocks in rings of distance (26-neighbour steps) from where it was lit. */
    List<List<Long>> frameLayers(Portal p, long from) {
        List<List<Long>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> cur = new ArrayList<>();
        long s0 = p.frame.contains(from) ? from : p.frame.iterator().next();
        cur.add(s0); seen.add(s0);
        while (!cur.isEmpty()) {
            out.add(cur);
            List<Long> next = new ArrayList<>();
            for (long k : cur) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                long n = key(kx(k) + dx, ky(k) + dy, kz(k) + dz);
                if (p.frame.contains(n) && seen.add(n)) next.add(n);
            }
            cur = next;
        }
        List<Long> rest = new ArrayList<>(); // frame bits not touching the rest (shouldn't happen)
        for (long k : p.frame) if (!seen.contains(k)) rest.add(k);
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** A ring of light in the portal's plane round c. */
    void ring(Portal p, World w, Location c, double r, org.bukkit.Color col) {
        int n = (int) Math.max(16, r * 7);
        Particle.DustOptions o = new Particle.DustOptions(col, 1.5f);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n, u = Math.cos(a) * r, v = Math.sin(a) * r;
            Location l = p.axis == Axis.X ? c.clone().add(u, v, 0) : c.clone().add(0, v, u);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, o);
        }
    }

    /** An invisible light in the cell, so the portal lights its surroundings. */
    private void light(Block b) {
        if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) || !fillable(b)) return;
        b.setType(Material.LIGHT, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.Levelled lv) {
            lv.setLevel(Math.min(lv.getMaximumLevel(), plugin.getConfig().getInt("blue-obsidian.portal-light", 11)));
            b.setBlockData(lv, false);
        }
    }

    /** The glowing blue sheet in one cell: grows from nothing when `grow`, else appears at full size (chunk loads). */
    void sheet(Portal p, World w, long k, boolean grow) {
        ItemDisplay old = p.sheets.get(k);
        if (old != null && old.isValid()) return;
        ItemDisplay d = (ItemDisplay) w.spawnEntity(at(w, k), EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(DISPLAY_TAG);
        d.addScoreboardTag("faultline_blue_portal");
        d.addScoreboardTag("faultline_no_bedrock_fx");
        d.setPersistent(false);
        d.setItemStack(model("blue_portal"));
        try { d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE); } catch (RuntimeException ignored) { }
        try { d.setBrightness(new Display.Brightness(15, 15)); } catch (RuntimeException ignored) { }
        Quaternionf q = p.axis == Axis.X ? new Quaternionf() : new Quaternionf().rotateY((float) (Math.PI / 2));
        float full = 1.001f;
        if (grow) {
            d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!d.isValid()) return;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(7);
                d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(full, full, full), new Quaternionf()));
            }, 2L);
        } else d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(full, full, full), new Quaternionf()));
        p.sheets.put(k, d);
    }

    void close(Portal p, boolean effects) {
        portals.remove(p.id);
        Map<Long, Portal> m = cells.get(p.world);
        World w = Bukkit.getWorld(p.world);
        for (long k : p.inside) {
            if (m != null) m.remove(k);
            if (w == null || !w.isChunkLoaded(kx(k) >> 4, kz(k) >> 4)) continue;
            Block b = w.getBlockAt(kx(k), ky(k), kz(k));
            if (b.getType() == Material.LIGHT || b.getType() == Material.NETHER_PORTAL) b.setType(Material.AIR, false);
        }
        // the sheet shrinks away, the frame goes dark
        for (ItemDisplay d : p.sheets.values()) {
            if (!d.isValid()) continue;
            Transformation tr = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(6);
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(0.01f, 0.01f, 0.01f), tr.getRightRotation()));
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.isValid()) d.remove(); }, 7L);
        }
        p.sheets.clear();
        for (long k : p.frame) setLit(p.world, k, false);
        if (effects && w != null) {
            Location c = centre(p, w);
            w.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 1.2f, 0.8f);
            w.playSound(c, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 0.6f);
            w.spawnParticle(Particle.DUST, c, 40, 1.2, 1.2, 1.2, 0, new Particle.DustOptions(DEEP, 1.5f));
            w.spawnParticle(Particle.REVERSE_PORTAL, c, 60, 1, 1, 1, 0.2);
        }
        save();
    }

    static Location centre(Portal p, World w) {
        double x = 0, y = 0, z = 0;
        for (long k : p.inside) { x += kx(k); y += ky(k); z += kz(k); }
        int n = Math.max(1, p.inside.size());
        return new Location(w, x / n + 0.5, y / n + 0.5, z / n + 0.5);
    }

    /**
     * Every 2 s: a frame with a block gone closes; light knocked out of a cell (a frame still whole) comes back, and so do the
     * sheets of a chunk that loaded again. 1.2.75's purple portal blocks turn into the new blue portal.
     */
    void upkeep() {
        for (Portal p : new ArrayList<>(portals.values())) {
            World w = Bukkit.getWorld(p.world);
            if (w == null) continue;
            boolean broken = false;
            for (long k : p.frame) {
                if (!w.isChunkLoaded(kx(k) >> 4, kz(k) >> 4)) continue;
                if (!isBlue(w.getBlockAt(kx(k), ky(k), kz(k)))) { broken = true; break; }
            }
            if (broken) { close(p, true); continue; }
            if (!p.ready) continue;
            for (long k : p.frame) setLit(p.world, k, true);
            for (long k : p.inside) {
                if (!w.isChunkLoaded(kx(k) >> 4, kz(k) >> 4)) continue;
                Block b = w.getBlockAt(kx(k), ky(k), kz(k));
                if (b.getType() != Material.LIGHT && fillable(b)) light(b);
                sheet(p, w, k, false);
            }
        }
    }

    /** Every 5 ticks round open portals: drifting blue motes, sparks on the frame, a low hum now and then. */
    void ambient() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Portal p : portals.values()) {
            if (!p.ready) continue;
            World w = Bukkit.getWorld(p.world);
            if (w == null) continue;
            Location c = centre(p, w);
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            boolean watched = false;
            for (Player pl : w.getPlayers()) if (pl.getLocation().distanceSquared(c) < 48 * 48) { watched = true; break; }
            if (!watched) continue;
            List<Long> in = new ArrayList<>(p.inside);
            for (int i = 0; i < Math.min(4, in.size()); i++) {
                Location l = at(w, in.get(r.nextInt(in.size())));
                w.spawnParticle(Particle.DUST, l, 1, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(r.nextBoolean() ? CYAN : DEEP, 1.1f));
                if (r.nextInt(3) == 0) w.spawnParticle(Particle.REVERSE_PORTAL, l, 1, 0.3, 0.3, 0.3, 0.02);
            }
            List<Long> fr = new ArrayList<>(p.frame);
            if (!fr.isEmpty() && r.nextInt(2) == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, at(w, fr.get(r.nextInt(fr.size()))), 1, 0.4, 0.4, 0.4, 0.02);
            if (r.nextInt(16) == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 0.6f, 1.3f);
        }
    }

    // ---------------------------------------------------------------- going through

    Portal portalAt(Location l) {
        Block b = l.getBlock();
        Portal p = cellOwner(b);
        if (p == null) p = cellOwner(b.getRelative(BlockFace.UP));
        return p;
    }

    private final Map<UUID, Integer> charge = new HashMap<>();

    /** Every 2 ticks: standing in an open portal charges up (creative: at once), then you're pulled through. */
    void travelTick() {
        if (portals.isEmpty()) { charge.clear(); return; }
        int need = Math.max(2, plugin.getConfig().getInt("blue-obsidian.travel-ticks", 30));
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (!cells.containsKey(pl.getWorld().getName()) || pl.getGameMode() == GameMode.SPECTATOR || pl.isDead()) { charge.remove(pl.getUniqueId()); continue; }
            Portal p = cellOwner(pl.getLocation().getBlock());
            if (p == null) p = cellOwner(pl.getEyeLocation().getBlock());
            if (p == null || !p.ready || travelling.contains(pl.getUniqueId())) { charge.remove(pl.getUniqueId()); continue; }
            int c = charge.merge(pl.getUniqueId(), 2, Integer::sum);
            Location l = pl.getLocation().add(0, 1, 0);
            if (c == 2) pl.playSound(l, Sound.BLOCK_PORTAL_TRIGGER, SoundCategory.PLAYERS, 0.5f, 1.6f);
            pl.getWorld().spawnParticle(Particle.DUST, l, 4, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(CYAN, 1.2f));
            pl.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l, 3, 0.3, 0.6, 0.3, 0.05);
            if (c >= need || pl.getGameMode() == GameMode.CREATIVE) {
                charge.remove(pl.getUniqueId());
                pl.getWorld().spawnParticle(Particle.FIREWORK, l, 20, 0.3, 0.6, 0.3, 0.15);
                pl.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 0.7f);
                send(pl);
            }
        }
    }

    /** 1.2.75's purple portal blocks are gone, but just in case vanilla ever asks about one of our cells. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (portalAt(e.getFrom()) == null) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent e) {
        if (portalAt(e.getFrom()) != null) e.setCancelled(true);
    }

    World overworld() {
        String name = plugin.getConfig().getString("blue-obsidian.world", "");
        World w = name.isEmpty() ? null : Bukkit.getWorld(name);
        if (w != null) return w;
        for (World x : Bukkit.getWorlds()) if (x.getEnvironment() == World.Environment.NORMAL) return x;
        return Bukkit.getWorlds().get(0);
    }

    /** To a random surface spot within max-xz of 0, 0 in the Overworld. Nothing is built there: no way back. */
    void send(Player p) {
        if (!travelling.add(p.getUniqueId())) return;
        World w = overworld();
        int max = Math.max(1, plugin.getConfig().getInt("blue-obsidian.max-xz", 500));
        p.sendActionBar(Component.text("The portal pulls you through...", NamedTextColor.BLUE));
        tryAt(p, w, max, 0);
    }

    private void tryAt(Player p, World w, int max, int attempt) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = r.nextInt(-max, max + 1), z = r.nextInt(-max, max + 1);
        java.util.concurrent.CompletableFuture<Chunk> f = null;
        try { f = w.getChunkAtAsync(x >> 4, z >> 4, true); } catch (RuntimeException ignored) { }
        if (f == null) { w.getChunkAt(x >> 4, z >> 4); landed(p, w, max, attempt, x, z); return; } // no async loading here
        f.thenAccept(ch -> landed(p, w, max, attempt, x, z)).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> { travelling.remove(p.getUniqueId()); if (p.isOnline()) arrive(p, w.getSpawnLocation()); });
            return null;
        });
    }

    private void landed(Player p, World w, int max, int attempt, int x, int z) {
        if (!p.isOnline()) { travelling.remove(p.getUniqueId()); return; }
        Location spot = surface(w, x, z);
        if (spot == null && attempt < 15) { tryAt(p, w, max, attempt + 1); return; }
        if (spot == null) spot = w.getSpawnLocation();
        arrive(p, spot);
    }

    /** The top of the column if it's safe to stand on (not water, lava, fire, cactus, powder snow, magma...). */
    static Location surface(World w, int x, int z) {
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top.getY() <= w.getMinHeight()) return null;
        Material m = top.getType();
        if (top.isLiquid() || m == Material.LAVA || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.POWDER_SNOW
                || m == Material.SWEET_BERRY_BUSH || m == Material.POINTED_DRIPSTONE || !m.isSolid()) return null;
        Block feet = top.getRelative(BlockFace.UP), head = feet.getRelative(BlockFace.UP);
        if (feet.getType().isSolid() || head.getType().isSolid() || feet.isLiquid()) return null;
        return new Location(w, x + 0.5, top.getY() + 1, z + 0.5);
    }

    private void arrive(Player p, Location spot) {
        travelling.remove(p.getUniqueId());
        spot.setYaw(p.getLocation().getYaw());
        p.setFallDistance(0);
        p.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
        p.playSound(spot, Sound.BLOCK_PORTAL_TRAVEL, SoundCategory.PLAYERS, 0.5f, 1.3f);
        p.showTitle(Title.title(Component.empty(), Component.text("You fall out of the End...", NamedTextColor.BLUE),
                Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(600))));
        p.sendMessage(ChatColor.BLUE + "You came out at " + spot.getBlockX() + " " + spot.getBlockY() + " " + spot.getBlockZ() + ".");
    }

    // ---------------------------------------------------------------- saving

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Portal p : portals.values()) {
            String b = "portals." + p.id;
            y.set(b + ".world", p.world);
            y.set(b + ".axis", p.axis.name());
            y.set(b + ".inside", new ArrayList<>(p.inside));
            y.set(b + ".frame", new ArrayList<>(p.frame));
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save blue_portals.yml: " + ex.getMessage()); }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("portals");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            try {
                Portal p = new Portal(UUID.fromString(id), s.getString(id + ".world"), Axis.valueOf(s.getString(id + ".axis", "X")));
                for (Object o : s.getList(id + ".inside", List.of())) p.inside.add(((Number) o).longValue());
                for (Object o : s.getList(id + ".frame", List.of())) p.frame.add(((Number) o).longValue());
                if (p.world == null || p.inside.isEmpty()) continue;
                portals.put(p.id, p);
                lit.computeIfAbsent(p.world, w -> new HashSet<>()).addAll(p.frame);
                Map<Long, Portal> m = cells.computeIfAbsent(p.world, w -> new HashMap<>());
                for (long k : p.inside) m.put(k, p);
            } catch (RuntimeException ex) { plugin.getLogger().warning("[Blue Obsidian] bad portal " + id + " in blue_portals.yml: " + ex); }
        }
    }

    void shutdown() {
        for (Portal p : portals.values()) { for (ItemDisplay d : p.sheets.values()) if (d.isValid()) d.remove(); p.sheets.clear(); }
        for (Map<Long, ItemDisplay> m : overlays.values()) for (ItemDisplay d : m.values()) if (d.isValid()) d.remove();
        overlays.clear();
    }

    /** Leftover overlays from a crash (they're non-persistent, but just in case). */
    static boolean ours(Entity e) { return e.getScoreboardTags().contains(DISPLAY_TAG); }
}
