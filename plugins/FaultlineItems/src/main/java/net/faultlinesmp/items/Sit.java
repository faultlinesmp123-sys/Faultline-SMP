package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /SIT (Items 1.2.18, config sit:). Right-click the top of a stair or a bottom slab with an empty hand (not sneaking) to sit
 * on it, facing the way the stair faces; /sit sits you down where you stand. The seat is an invisible marker armor stand
 * (Bedrock can ride those too), tag faultline_seat, non-persistent; getting up (sneak) puts you back on top of the block,
 * breaking the block stands you up, one sitter per block. Never on a FaultlineShips ship (its stairs move away with it).
 */
final class Sit implements Listener, CommandExecutor {

    static final String TAG = "faultline_seat";
    private final FaultlineItems plugin;
    private final Map<UUID, Location> seatedOn = new HashMap<>(); // seat entity -> the block it's on (null for /sit)

    Sit(FaultlineItems plugin) { this.plugin = plugin; }

    boolean on() { return plugin.getConfig().getBoolean("sit.enabled", true); }

    static boolean seat(Entity e) { return e != null && e.getScoreboardTags().contains(TAG); }

    ArmorStand sitDown(Player p, Location at, float yaw, Block block) {
        if (p.isInsideVehicle() || p.isGliding() || p.isSleeping()) return null;
        at.setYaw(yaw);
        ArmorStand a = at.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setPersistent(false);
            s.setMarker(true);
            s.setInvisible(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setSmall(true);
            s.setBasePlate(false);
            s.addScoreboardTag(TAG);
            s.setRotation(yaw, 0);
        });
        a.addPassenger(p);
        seatedOn.put(a.getUniqueId(), block == null ? null : block.getLocation());
        return a;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(PlayerInteractEvent e) {
        if (!on() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!plugin.getConfig().getBoolean("sit.click-stairs", true)) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || !p.getInventory().getItemInMainHand().getType().isAir() || p.getGameMode() == GameMode.SPECTATOR) return;
        if (e.getBlockFace() != BlockFace.UP && !(e.getClickedBlock().getBlockData() instanceof Stairs)) return;
        Block b = e.getClickedBlock();
        float yaw;
        if (b.getBlockData() instanceof Stairs st) {
            if (st.getHalf() != Bisected.Half.BOTTOM) return;
            yaw = yawOf(st.getFacing().getOppositeFace()); // you sit with your back to the high part
        } else if (b.getBlockData() instanceof Slab sl) {
            if (sl.getType() != Slab.Type.BOTTOM) return;
            yaw = p.getLocation().getYaw();
        } else return;
        if (b.getRelative(BlockFace.UP).getType().isSolid()) return;
        if (taken(b) || onShip(b) || p.getLocation().distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > 9) return;
        e.setCancelled(true);
        sitDown(p, b.getLocation().add(0.5, 0.3, 0.5), yaw, b);
    }

    static float yawOf(BlockFace f) {
        return switch (f) { case SOUTH -> 0; case WEST -> 90; case NORTH -> 180; case EAST -> -90; default -> 0; };
    }

    boolean taken(Block b) {
        for (Location l : seatedOn.values()) if (l != null && l.equals(b.getLocation())) return true;
        return false;
    }

    static boolean onShip(Block b) {
        for (Entity e : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5))
            if (e.getScoreboardTags().contains("faultline_ship")) return true;
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!on()) { p.sendMessage(ChatColor.RED + "Sitting is turned off."); return true; }
        if (p.getVehicle() != null && seat(p.getVehicle())) { p.leaveVehicle(); return true; }
        Block under = p.getLocation().subtract(0, 0.1, 0).getBlock();
        if (!under.getType().isSolid() || p.isInWater() || p.isGliding()) { p.sendMessage(ChatColor.RED + "Stand on solid ground to sit."); return true; }
        double y = under.getBlockData() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM ? under.getY() + 0.3
                : under.getBlockData() instanceof Stairs st && st.getHalf() == Bisected.Half.BOTTOM ? under.getY() + 0.3
                : p.getLocation().getY() - 0.2;
        Location at = p.getLocation().clone();
        at.setY(y);
        if (sitDown(p, at, p.getLocation().getYaw(), null) == null) p.sendMessage(ChatColor.RED + "You can't sit right now.");
        return true;
    }

    @EventHandler
    public void onGetUp(EntityDismountEvent e) {
        Entity seat = e.getDismounted();
        if (!seat(seat) || !(e.getEntity() instanceof Player p)) return;
        Location block = seatedOn.remove(seat.getUniqueId());
        Location up = block != null ? block.clone().add(0.5, 1.0, 0.5) : seat.getLocation().clone().add(0, 0.5, 0);
        up.setYaw(p.getLocation().getYaw());
        up.setPitch(p.getLocation().getPitch());
        Bukkit.getScheduler().runTask(plugin, () -> {
            seat.remove();
            if (p.isOnline() && !p.isInsideVehicle()) p.teleport(up);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        for (var it = seatedOn.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue() == null || !en.getValue().equals(e.getBlock().getLocation())) continue;
            Entity s = Bukkit.getEntity(en.getKey());
            it.remove();
            if (s != null) { s.eject(); s.remove(); }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Entity v = e.getPlayer().getVehicle();
        if (seat(v)) { seatedOn.remove(v.getUniqueId()); v.eject(); v.remove(); }
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        for (Entity en : e.getChunk().getEntities()) if (seat(en) && en.getPassengers().isEmpty()) en.remove();
    }

    void shutdown() {
        for (UUID id : seatedOn.keySet()) { Entity s = Bukkit.getEntity(id); if (s != null) { s.eject(); s.remove(); } }
        seatedOn.clear();
    }

    /** Seats nobody is on any more (a missed dismount) go away. */
    void sweep() {
        for (var it = seatedOn.keySet().iterator(); it.hasNext(); ) {
            Entity s = Bukkit.getEntity(it.next());
            if (s == null || s.getPassengers().isEmpty()) { if (s != null) s.remove(); it.remove(); }
        }
    }
}
