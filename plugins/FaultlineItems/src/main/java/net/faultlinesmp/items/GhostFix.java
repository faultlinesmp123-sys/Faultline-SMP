package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GHOST BLOCKS (Items 1.3.30, config ghost-fix:). A "ghost block" is the client showing something the server doesn't have
 * (a trapdoor it thinks is open, a block it thinks it placed, a shelf with the wrong items): the client guesses the result of
 * a click, and when the server answers differently (a cancelled click, another plugin, ViaVersion translating new blocks for
 * an older client, the pie chart limit) the answer can get lost. So after EVERY right-click on a block, a cancelled place and
 * a cancelled break, the real blocks (the clicked one, the one on the clicked face, a door's / bed's other half) are sent to
 * that player again a moment later, with their block entity data (shelf items, sign text). Costs a few block packets a click.
 * /ghost (/fixblocks): everything within ghost-fix.command-radius (6) of you is sent again (once every 3 s).
 */
final class GhostFix implements Listener, CommandExecutor {

    private final FaultlineItems plugin;
    private final Map<UUID, Long> lastCmd = new HashMap<>();

    GhostFix(FaultlineItems plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("ghost-fix.enabled", true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerInteractEvent e) {
        if (!on() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Block b = e.getClickedBlock();
        Set<Block> set = around(b);
        set.addAll(around(b.getRelative(e.getBlockFace()))); // where a placed block / bucket would have gone
        later(p, set);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!on() || !e.isCancelled()) return;
        Set<Block> set = around(e.getBlockPlaced());
        set.addAll(around(e.getBlockAgainst()));
        later(e.getPlayer(), set);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (!on() || !e.isCancelled()) return;
        later(e.getPlayer(), around(e.getBlock()));
    }

    /** The block and the other half of a door / bed / tall plant. */
    private static Set<Block> around(Block b) {
        Set<Block> s = new LinkedHashSet<>();
        s.add(b);
        var d = b.getBlockData();
        if (d instanceof Bisected bi) s.add(b.getRelative(bi.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP));
        if (d instanceof Bed bed) s.add(b.getRelative(bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing()));
        return s;
    }

    private void later(Player p, Set<Block> blocks) {
        long delay = Math.max(1, plugin.getConfig().getLong("ghost-fix.delay-ticks", 2));
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(p, blocks), delay);
    }

    static void send(Player p, Iterable<Block> blocks) {
        if (!p.isOnline()) return;
        World w = p.getWorld();
        Map<io.papermc.paper.math.Position, org.bukkit.block.data.BlockData> batch = new HashMap<>();
        java.util.List<Block> tiles = new java.util.ArrayList<>();
        for (Block b : blocks) {
            if (b.getWorld() != w || !w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            batch.put(io.papermc.paper.math.Position.block(b.getX(), b.getY(), b.getZ()), b.getBlockData());
            if (b.getType().isBlock() && hasTile(b)) tiles.add(b);
        }
        if (batch.isEmpty()) return;
        try { p.sendMultiBlockChange(batch); }
        catch (Throwable t) { for (var en : batch.entrySet()) p.sendBlockChange(en.getKey().toLocation(w), en.getValue()); }
        for (Block b : tiles) // block entity data (shelf items, sign text, banner patterns)
            try { if (b.getState(false) instanceof TileState ts) p.sendBlockUpdate(new Location(w, b.getX(), b.getY(), b.getZ()), ts); } catch (Throwable ignored) { }
    }

    private static boolean hasTile(Block b) {
        String n = b.getType().name();
        return n.endsWith("SHELF") || n.endsWith("SIGN") || n.endsWith("BANNER") || n.endsWith("_HEAD") || n.endsWith("_SKULL")
                || n.endsWith("CHEST") || n.endsWith("SHULKER_BOX") || n.contains("CAMPFIRE") || n.equals("LECTERN")
                || n.equals("DECORATED_POT") || n.equals("CHISELED_BOOKSHELF") || n.equals("SPAWNER") || n.equals("BEEHIVE")
                || n.equals("BEE_NEST") || n.equals("JUKEBOX") || n.equals("CRAFTER") || n.endsWith("BED");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        long now = System.currentTimeMillis();
        Long last = lastCmd.get(p.getUniqueId());
        if (last != null && now - last < 3000) { p.sendMessage(ChatColor.GRAY + "Give it a second."); return true; }
        lastCmd.put(p.getUniqueId(), now);
        int r = Math.max(1, Math.min(10, plugin.getConfig().getInt("ghost-fix.command-radius", 6)));
        Block c = p.getLocation().getBlock();
        Set<Block> all = new LinkedHashSet<>();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            int yy = c.getY() + y;
            if (yy < p.getWorld().getMinHeight() || yy >= p.getWorld().getMaxHeight()) continue;
            all.add(c.getRelative(x, y, z));
        }
        send(p, all);
        p.sendMessage(ChatColor.GREEN + "Blocks around you re-sent. Ghost blocks should be gone.");
        return true;
    }
}
