package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LAND CANNONS (Ships 1.6.0, config land-cannon:). A Ship Cannon right-clicked on any block (not a ship's cannon spot)
 * is set down there: on a castle wall, a dock, a cliff. The cell gets a barrier (solid, and what you click), the cannon
 * is an ItemDisplay that TURNS: hold a Cannonball within land-cannon.reach blocks and the nearest one you may use follows
 * your view (pitch land-cannon.min/max-pitch), with a dotted arc only you see showing where it'll land; right-click
 * fires it (same cannonball as the ships: an explosion that doesn't break blocks, ship damage, land-cannon.reload-seconds).
 * Owner + their /ship crew use it (land-cannon.owner-only), sneak + punch (owner/admin) picks it up, and it drops when
 * the block under it is broken or blown up. Saved in cannons.yml; displays are spawned with their chunk.
 * Bedrock players can't see displays: they get a fake dispenser facing the barrel in the barrier's place.
 */
final class LandCannons implements Listener {

    static final String TAG = "faultline_land_cannon";
    static final String SKIP_FX = "faultline_no_bedrock_fx"; // FaultlineBosses' BedrockFx leaves it alone (Bedrock has the dispenser)

    static final class Cannon {
        final UUID id;
        final String world;
        final int x, y, z;
        UUID owner;
        float yaw, pitch;
        long readyAt;
        ItemDisplay display;
        Cannon(UUID id, String world, int x, int y, int z) { this.id = id; this.world = world; this.x = x; this.y = y; this.z = z; }
        Location center() { World w = Bukkit.getWorld(world); return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5); }
        long key() { return FaultlineShips.blockKey(x, y, z); }
    }

    private final FaultlineShips pl;
    private final File file;
    final Map<Long, Cannon> byBlock = new HashMap<>();
    private final Map<UUID, Cannon> aiming = new HashMap<>();
    private boolean dirty;

    LandCannons(FaultlineShips pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "cannons.yml");
        load();
        for (World w : Bukkit.getWorlds()) for (var ch : w.getLoadedChunks()) chunkIn(ch);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(pl, this::bedrockUpkeep, 40L, 60L);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { if (dirty) save(); }, 200L, 200L);
    }

    private double cfg(String k, double d) { return pl.getConfig().getDouble("land-cannon." + k, d); }

    // ---------------------------------------------------------------- saving

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("cannons");
        if (cs == null) return;
        for (String k : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(k);
            try {
                Cannon can = new Cannon(UUID.fromString(k), c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z"));
                String o = c.getString("owner");
                can.owner = o == null ? null : UUID.fromString(o);
                can.yaw = (float) c.getDouble("yaw"); can.pitch = (float) c.getDouble("pitch");
                byBlock.put(can.key(), can);
            } catch (RuntimeException ex) { pl.getLogger().warning("Bad land cannon in cannons.yml: " + k); }
        }
    }

    void save() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Cannon c : byBlock.values()) {
            String b = "cannons." + c.id + ".";
            y.set(b + "world", c.world); y.set(b + "x", c.x); y.set(b + "y", c.y); y.set(b + "z", c.z);
            if (c.owner != null) y.set(b + "owner", c.owner.toString());
            y.set(b + "yaw", c.yaw); y.set(b + "pitch", c.pitch);
        }
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save cannons.yml: " + e.getMessage()); }
    }

    void shutdown() {
        for (Cannon c : byBlock.values()) if (c.display != null && c.display.isValid()) c.display.remove();
        save();
    }

    // ---------------------------------------------------------------- placing / removing

    Cannon at(Block b) { return b == null ? null : byBlock.get(FaultlineShips.blockKey(b.getX(), b.getY(), b.getZ())); }

    /** A Ship Cannon right-clicked on a block face: set it down in front of that face. */
    boolean place(Player p, Block clicked, BlockFace face, ItemStack hand) {
        if (!pl.getConfig().getBoolean("land-cannon.enabled", true)) return false;
        Block cell = clicked.getRelative(face);
        if (!cell.getType().isAir() && cell.getType() != Material.WATER && !org.bukkit.Tag.REPLACEABLE.isTagged(cell.getType())) {
            p.sendActionBar(Component.text("No room for a cannon there.", NamedTextColor.RED));
            return true;
        }
        if (pl.shipCellAt(cell, null) != null || pl.shipOver(cell) != null) {
            p.sendActionBar(Component.text("Ship cannons go in a ship's glowing cannon spots.", NamedTextColor.YELLOW));
            return true;
        }
        if (!cell.getWorld().getNearbyEntities(cell.getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5, e -> e instanceof org.bukkit.entity.LivingEntity).isEmpty()) {
            p.sendActionBar(Component.text("Something is standing there.", NamedTextColor.RED));
            return true;
        }
        int max = (int) cfg("max-per-player", 12);
        if (max > 0 && !p.hasPermission("faultlineships.admin") && byBlock.values().stream().filter(c -> p.getUniqueId().equals(c.owner)).count() >= max) {
            p.sendActionBar(Component.text("You already have " + max + " cannons placed.", NamedTextColor.RED));
            return true;
        }
        // other plugins (claims, spawn protection) get their say, as for any block
        org.bukkit.block.BlockState before = cell.getState();
        cell.setType(Material.BARRIER, false);
        org.bukkit.event.block.BlockPlaceEvent ev = new org.bukkit.event.block.BlockPlaceEvent(cell, before, clicked, hand, p, true, org.bukkit.inventory.EquipmentSlot.HAND);
        placing = true;
        try { Bukkit.getPluginManager().callEvent(ev); } finally { placing = false; }
        if (ev.isCancelled()) { before.update(true, false); return true; }
        Cannon c = new Cannon(UUID.randomUUID(), cell.getWorld().getName(), cell.getX(), cell.getY(), cell.getZ());
        c.owner = p.getUniqueId();
        Vector out = face.getDirection();
        c.yaw = face == BlockFace.UP || face == BlockFace.DOWN ? p.getLocation().getYaw() : yawOf(out);
        c.pitch = 0;
        byBlock.put(c.key(), c);
        spawn(c);
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        cell.getWorld().playSound(cell.getLocation(), Sound.BLOCK_ANVIL_PLACE, SoundCategory.BLOCKS, 0.7f, 0.8f);
        p.sendActionBar(Component.text("Cannon set. Hold a Cannonball near it to aim, right-click to fire. Sneak + punch picks it up.", NamedTextColor.GREEN));
        dirty = true;
        return true;
    }

    boolean placing; // FaultlineShips.onPlace must ignore the BlockPlaceEvent we fire for claim plugins

    void remove(Cannon c, boolean drop) {
        byBlock.remove(c.key());
        aiming.values().removeIf(x -> x == c);
        if (c.display != null && c.display.isValid()) c.display.remove();
        Location at = c.center();
        if (at != null) {
            Block b = at.getBlock();
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
            if (drop) at.getWorld().dropItemNaturally(at, pl.cannon());
            for (Player p : at.getWorld().getPlayers()) if (FaultlineShips.bedrock(p) && p.getLocation().distanceSquared(at) < 128 * 128) p.sendBlockChange(at, b.getBlockData());
        }
        dirty = true;
    }

    static float yawOf(Vector v) { return (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())); }

    // ---------------------------------------------------------------- the display

    void spawn(Cannon c) {
        Location at = c.center();
        if (at == null || !at.getChunk().isLoaded()) return;
        if (c.display != null && c.display.isValid()) return;
        c.display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag(SKIP_FX);
            d.setItemStack(pl.cannonModel());
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setRotation(c.yaw, 0);
            d.setTransformation(transform(c.pitch));
        });
    }

    /** The model points at +z; pitch tips it about its trunnions (the model's centre), so the carriage stays on the block. */
    static Transformation transform(float pitch) {
        float s = 1.0f;
        Quaternionf q = new Quaternionf().rotateX((float) Math.toRadians(pitch));
        return new Transformation(new Vector3f(0, 0, 0), q, new Vector3f(s, s, s), new Quaternionf());
    }

    void turn(Cannon c, float yaw, float pitch) {
        if (Math.abs(c.yaw - yaw) < 0.5 && Math.abs(c.pitch - pitch) < 0.5) return;
        c.yaw = yaw; c.pitch = pitch;
        if (c.display != null && c.display.isValid()) {
            c.display.setRotation(yaw, 0);
            c.display.setInterpolationDelay(0);
            c.display.setTransformation(transform(pitch));
        }
        dirty = true;
    }

    // ---------------------------------------------------------------- aiming + firing

    boolean mayUse(Player p, Cannon c) {
        if (p.hasPermission("faultlineships.admin") || c.owner == null || c.owner.equals(p.getUniqueId())) return true;
        if (!pl.getConfig().getBoolean("land-cannon.owner-only", true)) return true;
        var crew = pl.crews.get(c.owner);
        return crew != null && crew.contains(p.getUniqueId());
    }

    /** The cannon this player is working: the one they're aiming, else the nearest within reach they may use. */
    Cannon nearest(Player p) {
        double reach = cfg("reach", 3.5), best = reach * reach;
        Cannon pick = null;
        Location l = p.getLocation();
        for (Cannon c : byBlock.values()) {
            if (!c.world.equals(l.getWorld().getName())) continue;
            double dx = c.x + 0.5 - l.getX(), dy = c.y + 0.5 - l.getY(), dz = c.z + 0.5 - l.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= best && mayUse(p, c)) { best = d; pick = c; }
        }
        return pick;
    }

    float[] aimFor(Player p) {
        Location eye = p.getEyeLocation();
        float pitch = Math.max((float) cfg("max-pitch-up", -45), Math.min((float) cfg("max-pitch-down", 20), eye.getPitch()));
        return new float[]{eye.getYaw(), pitch};
    }

    static Vector dirOf(float yaw, float pitch) {
        double y = Math.toRadians(yaw), pt = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(pt), -Math.sin(pt), Math.cos(y) * Math.cos(pt));
    }

    Location muzzle(Cannon c) {
        Location at = c.center();
        return at.add(dirOf(c.yaw, c.pitch).multiply(0.95)).add(0, 0.05, 0);
    }

    /** Right-click with a Cannonball near a land cannon. Returns false when there's none (the ships get a go). */
    boolean fire(Player p) {
        Cannon c = aiming.get(p.getUniqueId());
        if (c == null || !byBlock.containsValue(c)) c = nearest(p);
        if (c == null) return false;
        long now = pl.now;
        if (c.readyAt > now) { p.sendActionBar(Component.text("Reloading...", NamedTextColor.YELLOW)); return true; }
        float[] a = aimFor(p);
        turn(c, a[0], a[1]);
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
        c.readyAt = now + (long) (cfg("reload-seconds", 4) * 20);
        pl.launchTagged("land:" + c.id, muzzle(c), dirOf(c.yaw, c.pitch).multiply(cfg("speed", 2.6)), p);
        if (c.display != null && c.display.isValid()) { // recoil
            ItemDisplay d = c.display;
            Cannon fired = c;
            Transformation t = transform(c.pitch);
            Vector back = new Vector(0, 0, -0.18);
            d.setInterpolationDelay(0); d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(new Vector3f(0, (float) back.getY(), (float) back.getZ()), t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (d.isValid()) { d.setInterpolationDuration(6); d.setInterpolationDelay(0); d.setTransformation(transform(fired.pitch)); } }, 2L);
        }
        return true;
    }

    void tick() {
        aiming.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!pl.isBall(p.getInventory().getItemInMainHand()) || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (pl.ridingOn(p) != null) continue;
            Cannon c = nearest(p);
            if (c == null) continue;
            aiming.put(p.getUniqueId(), c);
            float[] a = aimFor(p);
            turn(c, a[0], a[1]);
            if (pl.now % 4 == 0) arc(p, c);
            if (pl.now % 10 == 0) p.sendActionBar(Component.text(c.readyAt > pl.now ? "Reloading..." : "Aiming: right-click to fire", c.readyAt > pl.now ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        }
    }

    /** Where the ball would go: a snowball's flight (gravity 0.03, drag 0.99), drawn for this player only. */
    void arc(Player p, Cannon c) {
        Location pos = muzzle(c);
        Vector v = dirOf(c.yaw, c.pitch).multiply(cfg("speed", 2.6));
        World w = pos.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 120, 40), 0.7f);
        for (int i = 0; i < 80; i++) {
            pos.add(v);
            v.multiply(0.99).subtract(new Vector(0, 0.03, 0));
            if (i % 2 == 1) p.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, dust);
            Block b = pos.getBlock();
            if (b.getType().isSolid() && b.getType() != Material.BARRIER) {
                p.spawnParticle(Particle.DUST, pos, 8, 0.4, 0.2, 0.4, 0, new Particle.DustOptions(Color.RED, 1.2f));
                break;
            }
            if (pos.getY() < w.getMinHeight()) break;
        }
    }

    // ---------------------------------------------------------------- Bedrock

    BlockData bedrockLook(Cannon c) {
        Vector d = dirOf(c.yaw, 0);
        String facing = Math.abs(d.getX()) > Math.abs(d.getZ()) ? (d.getX() > 0 ? "east" : "west") : (d.getZ() > 0 ? "south" : "north");
        return Bukkit.createBlockData("minecraft:dispenser[facing=" + facing + "]");
    }

    void bedrockUpkeep() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!FaultlineShips.bedrock(p)) continue;
            for (Cannon c : byBlock.values()) {
                Location at = c.center();
                if (at == null || !at.getWorld().equals(p.getWorld()) || at.distanceSquared(p.getLocation()) > 64 * 64) continue;
                p.sendBlockChange(at, bedrockLook(c));
            }
        }
    }

    // ---------------------------------------------------------------- events

    void chunkIn(org.bukkit.Chunk ch) {
        for (Entity e : ch.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove(); // left over from a crash
        String w = ch.getWorld().getName();
        for (Cannon c : byBlock.values()) if (c.world.equals(w) && (c.x >> 4) == ch.getX() && (c.z >> 4) == ch.getZ()) { c.display = null; spawn(c); }
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) { chunkIn(e.getChunk()); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        String w = e.getWorld().getName();
        for (Cannon c : byBlock.values())
            if (c.world.equals(w) && (c.x >> 4) == e.getChunk().getX() && (c.z >> 4) == e.getChunk().getZ() && c.display != null) { c.display.remove(); c.display = null; }
    }

    /** Sneak + punch: the owner (or an admin) picks it up. Punching it otherwise does nothing (it's a barrier). */
    boolean punch(Player p, Block b) {
        Cannon c = at(b);
        if (c == null) return false;
        if (!p.isSneaking()) { p.sendActionBar(Component.text("Sneak + punch to pick the cannon up.", NamedTextColor.GRAY)); return true; }
        if (!(p.getUniqueId().equals(c.owner) || p.hasPermission("faultlineships.admin"))) { p.sendActionBar(Component.text("That isn't your cannon.", NamedTextColor.RED)); return true; }
        remove(c, false);
        if (p.getGameMode() != GameMode.CREATIVE) p.getInventory().addItem(pl.cannon()).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
        return true;
    }

    /** The block it stands on (or hangs from) is gone: the cannon falls off as an item. */
    void supportGone(Block support) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
            Cannon c = at(support.getRelative(f));
            if (c == null) continue;
            Block cell = support.getRelative(f);
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!byBlock.containsValue(c)) return;
                boolean held = false;
                for (BlockFace g : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
                    Block n = cell.getRelative(g);
                    if (n.getType().isSolid() && n.getType() != Material.BARRIER) { held = true; break; }
                }
                if (!held) remove(c, true);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Cannon c = at(e.getBlock());
        if (c != null) { // only an admin in creative can break a barrier: take the cannon with it
            remove(c, e.getPlayer().getGameMode() != GameMode.CREATIVE);
            return;
        }
        supportGone(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { exploded(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { exploded(e.blockList()); }

    private void exploded(List<Block> blocks) {
        for (Block b : new ArrayList<>(blocks)) { if (at(b) == null) supportGone(b); }
        blocks.removeIf(b -> at(b) != null); // the cannon's barrier stays
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (at(b) != null || hasCannonOn(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (at(b) != null || hasCannonOn(b)) { e.setCancelled(true); return; }
    }

    private boolean hasCannonOn(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN})
            if (at(b.getRelative(f)) != null) return true;
        return false;
    }

    String describe(Cannon c) {
        return ChatColor.GRAY + c.world + " " + c.x + " " + c.y + " " + c.z + (c.owner != null ? " (" + Bukkit.getOfflinePlayer(c.owner).getName() + ")" : "");
    }
}
