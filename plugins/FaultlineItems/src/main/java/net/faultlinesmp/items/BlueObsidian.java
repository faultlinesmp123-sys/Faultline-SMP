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
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) chunkIn(c);
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 40L, 40L);
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
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay d = (ItemDisplay) b.getWorld().spawnEntity(at, EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(DISPLAY_TAG);
        d.addScoreboardTag("faultline_no_bedrock_fx"); // Bedrock sees the real block
        d.setPersistent(false);
        d.setItemStack(item(1));
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
        try { d.setBrightness(new Display.Brightness(Math.max(fb, 4), fs)); } catch (RuntimeException ignored) { }
        m.put(key(b), d);
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
        Portal made = null;
        for (Axis axis : new Axis[]{Axis.X, Axis.Z}) {
            made = fill(start, axis);
            if (made != null) break;
        }
        if (made == null) {
            p.sendActionBar(Component.text("That isn't a closed Blue Obsidian frame (light it from the inside).", NamedTextColor.RED));
            return;
        }
        open(made);
        if (p.getGameMode() != GameMode.CREATIVE) {
            if (hand.getType() == Material.FIRE_CHARGE) hand.setAmount(hand.getAmount() - 1);
            else p.damageItemStack(EquipmentSlot.HAND, 1);
        }
        World w = clicked.getWorld();
        Location c = centre(made, w);
        w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 0.8f, 1.4f);
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 80, 1.2, 1.2, 1.2, 0.05);
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
        return m.isAir() || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LIGHT;
    }

    Portal cellOwner(Block b) {
        Map<Long, Portal> m = cells.get(b.getWorld().getName());
        return m == null ? null : m.get(key(b));
    }

    void open(Portal p) {
        portals.put(p.id, p);
        Map<Long, Portal> m = cells.computeIfAbsent(p.world, w -> new HashMap<>());
        for (long k : p.inside) m.put(k, p);
        World w = Bukkit.getWorld(p.world);
        if (w != null) for (long k : p.inside) setPortalBlock(w.getBlockAt(kx(k), ky(k), kz(k)), p.axis);
        save();
    }

    private void setPortalBlock(Block b, Axis axis) {
        if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) return;
        b.setType(Material.NETHER_PORTAL, false);
        if (b.getBlockData() instanceof Orientable o) {
            o.setAxis(axis);
            b.setBlockData(o, false);
        }
    }

    void close(Portal p, boolean effects) {
        portals.remove(p.id);
        Map<Long, Portal> m = cells.get(p.world);
        World w = Bukkit.getWorld(p.world);
        for (long k : p.inside) {
            if (m != null) m.remove(k);
            if (w == null || !w.isChunkLoaded(kx(k) >> 4, kz(k) >> 4)) continue;
            Block b = w.getBlockAt(kx(k), ky(k), kz(k));
            if (b.getType() == Material.NETHER_PORTAL) b.setType(Material.AIR, false);
        }
        if (effects && w != null) w.playSound(centre(p, w), Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 0.6f);
        save();
    }

    static Location centre(Portal p, World w) {
        double x = 0, y = 0, z = 0;
        for (long k : p.inside) { x += kx(k); y += ky(k); z += kz(k); }
        int n = Math.max(1, p.inside.size());
        return new Location(w, x / n + 0.5, y / n + 0.5, z / n + 0.5);
    }

    /** Our portal blocks don't break when something next to them changes (they have no vanilla obsidian frame). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.NETHER_PORTAL && cellOwner(b) != null) e.setCancelled(true);
    }

    /** Vanilla never builds a nether portal inside our frame (and a frame lit "the vanilla way" isn't ours). */
    @EventHandler(ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        for (org.bukkit.block.BlockState s : e.getBlocks()) if (cellOwner(s.getBlock()) != null) { e.setCancelled(true); return; }
    }

    /** Every 2 s: a frame with a block gone closes; a portal block knocked out (a frame still whole) comes back. */
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
            for (long k : p.inside) {
                if (!w.isChunkLoaded(kx(k) >> 4, kz(k) >> 4)) continue;
                Block b = w.getBlockAt(kx(k), ky(k), kz(k));
                if (b.getType() != Material.NETHER_PORTAL && fillable(b)) setPortalBlock(b, p.axis);
            }
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                Location c = centre(p, w);
                if (w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4))
                    w.spawnParticle(Particle.DUST, c, 12, 1, 1, 1, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(70, 110, 255), 1.2f));
            }
        }
    }

    // ---------------------------------------------------------------- going through

    Portal portalAt(Location l) {
        Block b = l.getBlock();
        Portal p = cellOwner(b);
        if (p == null) p = cellOwner(b.getRelative(BlockFace.UP));
        if (p == null) p = cellOwner(b.getRelative(BlockFace.DOWN));
        return p;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (portalAt(e.getFrom()) == null) return;
        e.setCancelled(true);
        send(p);
    }

    /** Items, mobs and minecarts don't go through (vanilla would carry them to the Nether). */
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
                Map<Long, Portal> m = cells.computeIfAbsent(p.world, w -> new HashMap<>());
                for (long k : p.inside) m.put(k, p);
            } catch (RuntimeException ex) { plugin.getLogger().warning("[Blue Obsidian] bad portal " + id + " in blue_portals.yml: " + ex); }
        }
    }

    void shutdown() {
        for (Map<Long, ItemDisplay> m : overlays.values()) for (ItemDisplay d : m.values()) if (d.isValid()) d.remove();
        overlays.clear();
    }

    /** Leftover overlays from a crash (they're non-persistent, but just in case). */
    static boolean ours(Entity e) { return e.getScoreboardTags().contains(DISPLAY_TAG); }
}
