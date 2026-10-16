package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;

/**
 * Keeps the Bubble from flooding and the Black Market from being robbed. Without `faultlinedeep.market` (ops have it):
 * the dome's glass and the gatehouses can't be broken (one hole and the sea pours into the whole town); in the market's
 * plot and the Pit nothing can be broken or placed, no buckets emptied, and only doors, buttons, gates, the bell, the
 * lectern and the ender chest can be used (never the shulker boxes, barrels, pots...). Explosions and pistons never
 * touch any of it. `market.protect: false` turns it off.
 */
final class MarketGuard implements Listener {
    final FaultlineDeep pl;

    MarketGuard(FaultlineDeep pl) { this.pl = pl; }

    boolean on() { return pl.getConfig().getBoolean("market.protect", true); }

    boolean deep(Block b) { return pl.world != null && b.getWorld().equals(pl.world); }

    static boolean staff(Player p) { return p.hasPermission("faultlinedeep.market"); }

    /** The dome's shell (glass and ribs) and the four gatehouses. */
    static boolean shell(int x, int y, int z) {
        if (y < Town.FLOOR) return false;
        double e = Town.e(x, y, z);
        if (e <= 1.0001 && e >= 1 - Town.SHELL / Town.A - 0.04) return true;
        for (int[] gt : Town.GATES) {
            int[] d = Town.gateDoor(gt);
            int door = gt[0] == 0 ? Math.abs(d[0] - Town.CX) : Math.abs(d[1] - Town.CZ);
            int s = gt[0] == 0 ? (x - Town.CX) * gt[1] : (z - Town.CZ) * gt[1], q = gt[0] == 0 ? z - gt[2] : x - gt[2];
            if (s >= door - 8 && s <= door && Math.abs(q) <= 3 && y <= Town.G + 4) return true;
        }
        return false;
    }

    /** The market's plot, all the way up, and the Pit and the lift under it. */
    static boolean market(int x, int y, int z) {
        if (x >= Town.PLOT_X0 && x <= Town.PLOT_X1 && z >= Town.PLOT_Z0 && z <= Town.PLOT_Z1 && y >= Market.AY0 - 2 && y <= 10) return true;
        return x >= Market.CAR_X0 - 1 && x <= Market.CAR_X1 + 1 && z >= Market.CAR_Z0 - 1 && z <= Market.CAR_Z1 + 1 && y >= Market.DOWN_FLOOR && y <= Market.UP_FLOOR + 5;
    }

    static boolean guarded(Block b) { return shell(b.getX(), b.getY(), b.getZ()) || market(b.getX(), b.getY(), b.getZ()); }

    void deny(Player p, String why) { p.sendActionBar(Component.text(why, NamedTextColor.DARK_PURPLE)); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!on() || !deep(e.getBlock()) || staff(e.getPlayer())) return;
        Block b = e.getBlock();
        if (shell(b.getX(), b.getY(), b.getZ())) { e.setCancelled(true); deny(e.getPlayer(), "The bubble holds. Break it and the sea comes in."); }
        else if (market(b.getX(), b.getY(), b.getZ())) { e.setCancelled(true); deny(e.getPlayer(), "The Black Market isn't yours to tear down."); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!on() || !deep(b) || staff(e.getPlayer())) return;
        if (market(b.getX(), b.getY(), b.getZ())) { e.setCancelled(true); deny(e.getPlayer(), "You can't build in the Black Market."); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        if (!on() || !deep(b) || staff(e.getPlayer())) return;
        if (market(b.getX(), b.getY(), b.getZ())) { e.setCancelled(true); deny(e.getPlayer(), "Not in the Black Market."); }
    }

    static boolean usable(String m) {
        return m.endsWith("_DOOR") || m.endsWith("_BUTTON") || m.endsWith("_FENCE_GATE") || m.equals("BELL") || m.equals("LECTERN")
                || m.equals("ENDER_CHEST") || m.endsWith("_TRAPDOOR");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!on() || !deep(b) || staff(e.getPlayer()) || !market(b.getX(), b.getY(), b.getZ())) return;
        if (usable(b.getType().name())) return;
        if (!b.getType().isInteractable()) return; // plain blocks: nothing to take (placing is stopped by onPlace)
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (b.getType().name().endsWith("SHULKER_BOX")) deny(e.getPlayer(), "Hands off the stock. Line up and ask at the desk.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (on() && pl.world != null && e.getEntity().getWorld().equals(pl.world)) e.blockList().removeIf(MarketGuard::guarded); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (on() && deep(e.getBlock())) e.blockList().removeIf(MarketGuard::guarded); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) { if (on() && deep(e.getBlock()) && moves(e.getBlocks(), e)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) { if (on() && deep(e.getBlock()) && moves(e.getBlocks(), e)) e.setCancelled(true); }

    static boolean moves(List<Block> blocks, org.bukkit.event.block.BlockPistonEvent e) {
        for (Block b : blocks) if (guarded(b) || guarded(b.getRelative(e.getDirection()))) return true;
        return false;
    }

    static boolean at(Location l) { return market(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
}
