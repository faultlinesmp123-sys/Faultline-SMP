package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Item;
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
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.3.11: the BLUE OBSIDIAN BED. Three wool over three Blue Obsidian. A real (light blue) bed, marked in the chunk PDC
 * {@code faultlineitems:blue_bed}. In the Overworld it's a bed. In the NETHER and the END it doesn't blow up: right-click
 * sets your respawn point there (player PDC {@code faultlineitems:blue_bed_spawn}; a normal bed / respawn anchor used
 * later replaces it), and during that dimension's NIGHT (FaultlineEvents writes {@code faultlineevents:realm_night} on
 * the world) you sleep in it: enough sleepers = the night passes. Without FaultlineEvents you can always lie down.
 * Sleeping uses the game's own bed ({@code Player#sleep(loc, force)}); if the game refuses, you "rest" instead (held
 * still, eyes shut: metadata {@code faultline_blue_bed_rest}, which FaultlineEvents counts as asleep).
 */
final class BlueBed implements Listener {

    static final NamespacedKey RECIPE = NamespacedKey.fromString("faultlineitems:blue_obsidian_bed");
    static final NamespacedKey REALM_NIGHT = NamespacedKey.fromString("faultlineevents:realm_night");
    static final String REST = "faultline_blue_bed_rest";

    private final FaultlineItems plugin;
    private final NamespacedKey itemKey, chunkKey, spawnKey;
    private final Map<UUID, Location> resting = new HashMap<>();

    BlueBed(FaultlineItems plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "blue_bed");
        chunkKey = new NamespacedKey(plugin, "blue_bed");
        spawnKey = new NamespacedKey(plugin, "blue_bed_spawn");
        try {
            ShapedRecipe r = new ShapedRecipe(RECIPE, item(1));
            r.shape("WWW", "BBB");
            r.setIngredient('W', new RecipeChoice.MaterialChoice(Tag.WOOL));
            r.setIngredient('B', new RecipeChoice.ExactChoice(plugin.blueObsidian.item(1)));
            FaultlineItems.addRecipeSafely(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("[BlueBed] recipe: " + e); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::restTick, 20L, 10L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("blue-bed.enabled", true); }

    ItemStack item(int amount) {
        ItemStack it = new ItemStack(Material.LIGHT_BLUE_BED, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Blue Obsidian Bed");
        m.setLore(List.of(ChatColor.GRAY + "A bed that works in the " + ChatColor.RED + "Nether" + ChatColor.GRAY + " and the " + ChatColor.LIGHT_PURPLE + "End" + ChatColor.GRAY + ".",
                ChatColor.GRAY + "It never explodes. Right-click sets your",
                ChatColor.GRAY + "respawn point there, and at night you can",
                ChatColor.GRAY + "sleep through to that dimension's dawn."));
        try { m.setEnchantmentGlintOverride(true); } catch (RuntimeException | LinkageError ignored) { }
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) {
        return it != null && it.getType() == Material.LIGHT_BLUE_BED && it.hasItemMeta() && it.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- placed beds (both halves are marked)

    private static int local(Block b) { return (b.getX() & 15) | (b.getZ() & 15) << 4 | (b.getY() + 2048) << 8; }

    private int[] read(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(chunkKey, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    private boolean marked(Block b) {
        int l = local(b);
        for (int v : read(b.getChunk())) if (v == l) return true;
        return false;
    }

    boolean isOurs(Block b) { return b.getType() == Material.LIGHT_BLUE_BED && marked(b); }

    private void mark(Block b, boolean add) {
        Chunk c = b.getChunk();
        int l = local(b);
        List<Integer> list = new ArrayList<>();
        for (int v : read(c)) if (v != l) list.add(v);
        if (add) list.add(l);
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (list.isEmpty()) pdc.remove(chunkKey);
        else pdc.set(chunkKey, PersistentDataType.INTEGER_ARRAY, list.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The other half of a bed block, or null. */
    private static Block other(Block b) {
        if (!(b.getBlockData() instanceof Bed bed)) return null;
        Block o = b.getRelative(bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace());
        return o.getType() == b.getType() ? o : null;
    }

    private static Block head(Block b) {
        if (b.getBlockData() instanceof Bed bed && bed.getPart() == Bed.Part.FOOT) { Block o = other(b); return o == null ? b : o; }
        return b;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isItem(e.getItemInHand())) { // something else built where one of ours once stood
            if (marked(b)) mark(b, false);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getType() != Material.LIGHT_BLUE_BED) return;
            mark(b, true);
            Block o = other(b);
            if (o != null) mark(o, true);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isOurs(b)) return;
        Block o = other(b);
        // the drop is swapped in onDrop (which still needs the mark): unmark a tick later
        Bukkit.getScheduler().runTask(plugin, () -> { mark(b, false); if (o != null) mark(o, false); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (e.getBlockState().getType() != Material.LIGHT_BLUE_BED) return;
        if (!marked(e.getBlock())) return;
        for (Item it : e.getItems()) if (it.getItemStack().getType() == Material.LIGHT_BLUE_BED) it.setItemStack(item(it.getItemStack().getAmount()));
    }

    /** A bed broken any other way (its other half went, water, a piston) drops as a plain bed: swap that one too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (it.getType() != Material.LIGHT_BLUE_BED || isItem(it)) return;
        Block b = e.getLocation().getBlock();
        if (!marked(b) || b.getType() == Material.LIGHT_BLUE_BED) return;
        e.getEntity().setItemStack(item(it.getAmount()));
        mark(b, false);
    }

    /** A normal bed or a respawn anchor set later takes over. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent e) {
        String c = e.getCause().name();
        if (c.equals("BED") || c.equals("RESPAWN_ANCHOR")) forget(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true) public void onBoom(EntityExplodeEvent e) { e.blockList().removeIf(this::isOurs); }
    @EventHandler(ignoreCancelled = true) public void onBoom(BlockExplodeEvent e) { e.blockList().removeIf(this::isOurs); }
    @EventHandler(ignoreCancelled = true) public void onPush(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (isOurs(b)) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true) public void onPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (isOurs(b)) { e.setCancelled(true); return; } }

    // ---------------------------------------------------------------- using it

    static boolean otherRealm(World w) { return w.getEnvironment() == World.Environment.NETHER || w.getEnvironment() == World.Environment.THE_END; }

    /** Night there? No FaultlineEvents (no flag ever written) = always. */
    private boolean night(World w) {
        if (Bukkit.getPluginManager().getPlugin("FaultlineEvents") == null) return true;
        Byte b = w.getPersistentDataContainer().get(REALM_NIGHT, PersistentDataType.BYTE);
        return b == null ? !plugin.getConfig().getBoolean("blue-bed.needs-night", true) : b != 0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.LIGHT_BLUE_BED || !otherRealm(b.getWorld()) || !isOurs(b)) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return; // building against it
        e.setCancelled(true); // a bed clicked here explodes
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (!on()) { p.sendMessage(ChatColor.RED + "Blue Obsidian Beds are turned off."); return; }
        Block h = head(b);
        if (p.getLocation().distanceSquared(at(h, 0.5)) > 5 * 5) { bar(p, ChatColor.GRAY + "You're too far from the bed."); return; }
        String spot = h.getWorld().getName() + ";" + h.getX() + ";" + h.getY() + ";" + h.getZ();
        if (!spot.equals(p.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING))) {
            p.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, spot);
            p.sendMessage(ChatColor.AQUA + "Respawn point set " + ChatColor.GRAY + "(Blue Obsidian Bed, " + realm(h.getWorld()) + ").");
            p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.8f, 1.3f);
            h.getWorld().spawnParticle(Particle.GLOW, at(h, 0.8), 14, 0.4, 0.2, 0.4, 0.01);
        }
        if (p.isSleeping() || resting.containsKey(p.getUniqueId())) return;
        if (!night(h.getWorld())) { bar(p, ChatColor.GRAY + "You can only sleep here during the " + realm(h.getWorld()) + "'s night."); return; }
        if (h.getBlockData() instanceof Bed bed && bed.isOccupied()) { bar(p, ChatColor.GRAY + "This bed is occupied."); return; }
        boolean slept = false;
        try { slept = p.sleep(new Location(h.getWorld(), h.getX(), h.getY(), h.getZ()), true); } catch (RuntimeException | LinkageError ex) { /* rest instead */ }
        if (!slept) rest(p, h);
    }

    private static Location at(Block b, double up) { return new Location(b.getWorld(), b.getX() + 0.5, b.getY() + up, b.getZ() + 0.5); }
    private static String realm(World w) { return w.getEnvironment() == World.Environment.NETHER ? "Nether" : w.getEnvironment() == World.Environment.THE_END ? "End" : "Overworld"; }
    private static void bar(Player p, String s) { p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(s)); }

    // ---------------------------------------------------------------- resting (when the game won't let them lie down)

    private void rest(Player p, Block h) {
        Location at = at(h, 0.6);
        at.setYaw(p.getLocation().getYaw());
        at.setPitch(p.getLocation().getPitch());
        p.teleport(at);
        resting.put(p.getUniqueId(), at);
        p.setMetadata(REST, new FixedMetadataValue(plugin, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, true, false, false));
        bar(p, ChatColor.AQUA + "Resting... " + ChatColor.GRAY + "(move to get up)");
    }

    private void wake(Player p) {
        if (resting.remove(p.getUniqueId()) == null) return;
        p.removeMetadata(REST, plugin);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void restTick() {
        for (UUID id : new ArrayList<>(resting.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Location at = resting.get(id);
            if (p == null || !p.isOnline() || p.isDead() || !p.getWorld().equals(at.getWorld()) || !night(p.getWorld())) {
                if (p != null) wake(p); else resting.remove(id);
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, true, false, false));
            bar(p, ChatColor.AQUA + "Resting... " + ChatColor.GRAY + "(move to get up)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location at = resting.get(e.getPlayer().getUniqueId());
        if (at == null || e.getTo() == null) return;
        if (!e.getTo().getWorld().equals(at.getWorld()) || e.getTo().distanceSquared(at) > 0.6 * 0.6) wake(e.getPlayer());
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { wake(e.getPlayer()); }

    void shutdown() { for (UUID id : new ArrayList<>(resting.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) wake(p); } resting.clear(); }

    // ---------------------------------------------------------------- respawning at it

    void forget(Player p) { p.getPersistentDataContainer().remove(spawnKey); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        String s = p.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING);
        if (s == null || !on()) return;
        try { if (e.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return; } catch (RuntimeException | LinkageError ignored) { }
        String[] a = s.split(";");
        World w = a.length == 4 ? Bukkit.getWorld(a[0]) : null;
        if (w == null) return;
        Block h;
        try { h = w.getBlockAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])); } catch (NumberFormatException ex) { forget(p); return; }
        if (!isOurs(h)) {
            forget(p);
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(ChatColor.GRAY + "Your Blue Obsidian Bed is gone."));
            return;
        }
        Location at = stand(h);
        if (at == null) {
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(ChatColor.GRAY + "Your Blue Obsidian Bed is blocked."));
            return;
        }
        e.setRespawnLocation(at);
    }

    /** A safe spot beside the bed (or on it). */
    private static Location stand(Block h) {
        Block o = other(h);
        List<Block> beds = new ArrayList<>(List.of(h));
        if (o != null) beds.add(o);
        for (Block bed : beds) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
            Block feet = bed.getRelative(d[0], 0, d[1]);
            if (feet.getType() == Material.LIGHT_BLUE_BED) continue;
            if (free(feet) && free(feet.getRelative(0, 1, 0)) && feet.getRelative(0, -1, 0).getType().isSolid() && !bad(feet.getRelative(0, -1, 0)))
                return new Location(h.getWorld(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        }
        if (free(h.getRelative(0, 1, 0)) && free(h.getRelative(0, 2, 0))) return new Location(h.getWorld(), h.getX() + 0.5, h.getY() + 0.6, h.getZ() + 0.5);
        return null;
    }

    private static boolean free(Block b) { return !b.getType().isSolid() && !b.isLiquid() && b.getType() != Material.FIRE && b.getType() != Material.SOUL_FIRE; }
    private static boolean bad(Block b) { return b.getType() == Material.MAGMA_BLOCK || b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE; }
}
