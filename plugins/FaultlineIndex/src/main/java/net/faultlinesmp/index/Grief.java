package net.faultlinesmp.index;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GRIEF LOGS for the Discord STAFF LOGS channel (Index 1.6.14, config discord.logs.grief.*), next to the TNT logs:
 *  - lava poured and fires lit by players (not a Nether portal, a campfire or a candle), one line per player per
 *    grief.throttle-seconds with a count, so a lava-cast farm doesn't flood it;
 *  - a Wither built (the nearest player within 12 blocks);
 *  - someone else's CONTAINER broken or looted: every container records who placed it (block PDC
 *    faultlineindex:placer, from now on), and opening one you didn't place takes a snapshot; on closing, whatever
 *    left it is logged ("Alice took 12 Diamond, 3 Golden Apple from Bob's chest at ..."). Same-kingdom players can be
 *    skipped (grief.ignore-same-kingdom). Containers placed before this update have no owner and aren't logged.
 */
final class Grief implements Listener {

    private final FaultlineIndex index;
    private final NamespacedKey placer;
    private final Map<UUID, Snapshot> open = new HashMap<>();
    private final Map<String, long[]> throttle = new HashMap<>(); // "player|kind" -> {last post ms, count since}

    record Snapshot(UUID owner, String ownerName, String what, Location at, Map<Material, Integer> before) { }

    Grief(FaultlineIndex index) {
        this.index = index;
        placer = new NamespacedKey(index, "placer");
    }

    private boolean on(String what) {
        Discord d = index.discord;
        return d != null && d.logs.on() && index.getConfig().getBoolean("discord.logs.grief." + what, true);
    }

    private void log(String text) { index.discord.log(text); }

    /** One line per player per kind per throttle window; extras are counted into the next line. */
    private void throttled(Player p, String kind, String text) {
        long now = System.currentTimeMillis(), win = (long) (index.getConfig().getDouble("discord.logs.grief.throttle-seconds", 10) * 1000);
        long[] t = throttle.computeIfAbsent(p.getUniqueId() + "|" + kind, k -> new long[]{0, 0});
        if (now - t[0] < win) { t[1]++; return; }
        log(text + (t[1] > 0 ? " (+" + t[1] + " more since the last line)" : ""));
        t[0] = now; t[1] = 0;
    }

    // ---------------------------------------------------------------- lava, fire, withers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLava(PlayerBucketEmptyEvent e) {
        if (e.getBucket() != Material.LAVA_BUCKET || !on("lava")) return;
        throttled(e.getPlayer(), "lava", "🌋 **" + Discord.esc(e.getPlayer().getName()) + "** poured lava at " + Discord.where(e.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFire(BlockIgniteEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !on("fire")) return;
        Block b = e.getBlock();
        Material below = b.getRelative(0, -1, 0).getType();
        if (below == Material.OBSIDIAN || below == Material.NETHERRACK || below == Material.SOUL_SAND || below == Material.SOUL_SOIL
                || below == Material.MAGMA_BLOCK) return; // portals, Nether fires, soul fire decorations
        String t = b.getType().name();
        if (t.contains("CAMPFIRE") || t.contains("CANDLE")) return;
        throttled(p, "fire", "🔥 **" + Discord.esc(p.getName()) + "** lit a fire at " + Discord.where(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWither(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.WITHER || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_WITHER || !on("wither")) return;
        Player near = null; double best = 144;
        for (Player p : e.getLocation().getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d < best) { best = d; near = p; }
        }
        log("💀 A **Wither** was built at " + Discord.where(e.getLocation()) + (near != null ? " (by **" + Discord.esc(near.getName()) + "**)" : ""));
    }

    // ---------------------------------------------------------------- containers

    /** Blocks that are containers (1.6.15: checked before getState(), which copies a whole block entity, on every block). */
    static boolean container(Material m) {
        if (org.bukkit.Tag.SHULKER_BOXES.isTagged(m)) return true;
        return CONTAINERS.contains(m);
    }

    static final java.util.Set<Material> CONTAINERS = java.util.Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.HOPPER,
            Material.DROPPER, Material.DISPENSER, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND, Material.CRAFTER);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!container(e.getBlockPlaced().getType())) return;
        BlockState st = e.getBlockPlaced().getState();
        if (!(st instanceof Container c)) return;
        c.getPersistentDataContainer().set(placer, PersistentDataType.STRING, e.getPlayer().getUniqueId() + ";" + e.getPlayer().getName());
        c.update();
    }

    /** Who placed this container: {uuid, name}, or null (placed before 1.6.14, or by a machine). */
    String[] owner(BlockState st) {
        if (!(st instanceof Container c)) return null;
        String v = c.getPersistentDataContainer().get(placer, PersistentDataType.STRING);
        if (v == null || !v.contains(";")) return null;
        return v.split(";", 2);
    }

    private boolean skip(Player p, String[] owner) {
        if (owner == null || owner[0].equals(p.getUniqueId().toString())) return true;
        if (index.getConfig().getBoolean("discord.logs.grief.ignore-same-kingdom", false)) {
            try {
                int a = kingdom(p.getUniqueId()), b = kingdom(UUID.fromString(owner[0]));
                if (a > 0 && a == b) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    static int kingdom(UUID id) {
        try {
            org.bukkit.plugin.Plugin k = org.bukkit.Bukkit.getPluginManager().getPlugin("FaultlineKingdoms");
            if (k == null || !k.isEnabled()) return 0;
            return (int) k.getClass().getMethod("kingdomOf", UUID.class).invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return 0; }
    }

    static String kind(Material m) { return Discord.pretty(m).toLowerCase(java.util.Locale.ROOT); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!container(e.getBlock().getType())) return;
        BlockState st = e.getBlock().getState();
        String[] o = owner(st);
        if (skip(e.getPlayer(), o) || !on("containers")) return;
        int items = 0;
        for (ItemStack it : ((Container) st).getInventory().getContents()) if (it != null && !it.getType().isAir()) items += it.getAmount();
        log("🪓 **" + Discord.esc(e.getPlayer().getName()) + "** broke **" + Discord.esc(o[1]) + "**'s " + kind(st.getType())
                + " at " + Discord.where(e.getBlock().getLocation()) + (items > 0 ? " (" + items + " items spilled)" : ""));
    }

    /** The owner of an opened container, either half of a double chest. */
    private BlockState ownedState(InventoryHolder h) {
        if (h instanceof DoubleChest dc) {
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()})
                if (side instanceof BlockState bs && owner(bs) != null) return bs;
            return dc.getLeftSide() instanceof BlockState bs ? bs : null;
        }
        return h instanceof BlockState bs ? bs : null;
    }

    private static Map<Material, Integer> count(Inventory inv) {
        Map<Material, Integer> m = new HashMap<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) m.merge(it.getType(), it.getAmount(), Integer::sum);
        return m;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !on("containers")) return;
        InventoryHolder h;
        try { h = e.getInventory().getHolder(false); } catch (RuntimeException ex) { h = e.getInventory().getHolder(); } // no snapshot where it can
        BlockState st = ownedState(h);
        String[] o = st == null ? null : owner(st);
        if (skip(p, o)) return;
        open.put(p.getUniqueId(), new Snapshot(UUID.fromString(o[0]), o[1], kind(st.getType()), st.getLocation(), count(e.getInventory())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Snapshot s = open.remove(e.getPlayer().getUniqueId());
        if (s == null) return;
        Map<Material, Integer> after = count(e.getInventory());
        Map<Material, Integer> taken = new LinkedHashMap<>();
        s.before().forEach((m, n) -> { int left = after.getOrDefault(m, 0); if (left < n) taken.put(m, n - left); });
        if (taken.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (var t : taken.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).toList()) {
            if (shown++ == 8) { sb.append(", ..."); break; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.getValue()).append(' ').append(Discord.pretty(t.getKey()));
        }
        log("📦 **" + Discord.esc(e.getPlayer().getName()) + "** took " + sb + " from **" + Discord.esc(s.ownerName()) + "**'s " + s.what()
                + " at " + Discord.where(s.at()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        throttle.keySet().removeIf(k -> k.startsWith(e.getPlayer().getUniqueId() + "|"));
    }
}
