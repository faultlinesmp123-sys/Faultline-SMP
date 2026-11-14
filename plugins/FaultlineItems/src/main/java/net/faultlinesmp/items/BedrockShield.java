package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 1.3.16: BEDROCK PLAYERS RAISE THEIR SHIELD WITH RIGHT-CLICK (hold use), like Java, not only by sneaking. Bedrock only
 * sends a use when you press on something, and keeps sending it while you hold the button on a block, so: a right-click
 * with a shield in either hand starts the server-side block (`startUsingItem`), and a FAKE BARRIER (sent only to that
 * player, into real air, a couple of blocks ahead of their eyes; Geyser draws it invisible) gives the held button something
 * to keep pressing on. Every use refreshes the hold; `hold-grace-ticks` (6) with no use = the shield comes down and the fake
 * block goes. Also down: the shield leaves the hand, it's on cooldown (an axe disabled it), the hotbar changes, creative /
 * spectator, quit. Sneak-blocking (Geyser's own) still works. `bedrock-shield.enabled`.
 */
final class BedrockShield implements Listener {
    private final FaultlineItems plugin;
    private final Map<UUID, Hold> holds = new HashMap<>();
    private long tick;

    static final class Hold {
        EquipmentSlot hand;
        long last;
        Location fake;
        BlockData real;
    }

    BedrockShield(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean on() { return plugin.getConfig().getBoolean("bedrock-shield.enabled", true); }

    static EquipmentSlot shieldHand(Player p) {
        if (p.getInventory().getItemInMainHand().getType() == Material.SHIELD) return EquipmentSlot.HAND;
        if (p.getInventory().getItemInOffHand().getType() == Material.SHIELD) return EquipmentSlot.OFF_HAND;
        return null;
    }

    boolean able(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && p.getCooldown(Material.SHIELD) <= 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        if (!on() || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (!Edition.bedrock(p)) return;
        Hold h = holds.get(p.getUniqueId());
        if (h != null) { h.last = tick; return; } // held: keep it up
        EquipmentSlot hand = shieldHand(p);
        if (hand == null || !able(p)) return;
        // clicking a block you can use (door, chest, button...) does that instead: only raise when it did nothing / on our fake block
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()
                && e.useInteractedBlock() != org.bukkit.event.Event.Result.DENY && hand == EquipmentSlot.HAND && !p.isSneaking()) return;
        // something else to use in the main hand (food, a bow, a potion): that wins over an off-hand shield
        if (hand == EquipmentSlot.OFF_HAND && usable(p.getInventory().getItemInMainHand())) return;
        h = new Hold();
        h.hand = hand;
        h.last = tick;
        holds.put(p.getUniqueId(), h);
        raise(p, h);
    }

    static boolean usable(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        Material m = it.getType();
        return m.isEdible() || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT || m == Material.POTION || m == Material.ENDER_PEARL
                || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION || m == Material.SNOWBALL || m == Material.EGG || m.name().endsWith("_SPEAR")
                || m == Material.WIND_CHARGE || m == Material.FISHING_ROD || m == Material.SPYGLASS || m == Material.GOAT_HORN || m.isBlock();
    }

    void raise(Player p, Hold h) {
        try { if (!p.isHandRaised()) p.startUsingItem(h.hand); } catch (Throwable ignored) { }
    }

    void lower(Player p, Hold h) {
        clearFake(p, h);
        try { if (p.isHandRaised() && p.getActiveItem().getType() == Material.SHIELD) p.clearActiveItem(); } catch (Throwable ignored) { }
    }

    /** The fake barrier the held button keeps pressing on: 2 blocks out along the look, only into real air. */
    void placeFake(Player p, Hold h) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        Location want = null;
        for (double r = 1.6; r <= 3.0; r += 0.35) {
            Location l = eye.clone().add(d.clone().multiply(r));
            Block b = l.getBlock();
            if (!b.getType().isAir()) break; // a real block there: Bedrock already presses on it
            if (b.getY() == p.getLocation().getBlockY() || b.getY() == p.getLocation().getBlockY() + 1) {
                double dx = b.getX() + 0.5 - p.getLocation().getX(), dz = b.getZ() + 0.5 - p.getLocation().getZ();
                if (dx * dx + dz * dz < 1.2) continue; // never inside their own body
            }
            want = b.getLocation();
            break;
        }
        if (want != null && h.fake != null && want.equals(h.fake)) return;
        clearFake(p, h);
        if (want == null) return;
        h.fake = want;
        h.real = want.getBlock().getBlockData();
        p.sendBlockChange(want, Material.BARRIER.createBlockData());
    }

    void clearFake(Player p, Hold h) {
        if (h.fake == null) return;
        if (p.isOnline() && p.getWorld().equals(h.fake.getWorld())) p.sendBlockChange(h.fake, h.fake.getBlock().getBlockData());
        h.fake = null;
    }

    void tick() {
        tick++;
        if (holds.isEmpty()) return;
        int grace = Math.max(2, plugin.getConfig().getInt("bedrock-shield.hold-grace-ticks", 6));
        for (Iterator<Map.Entry<UUID, Hold>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Hold> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Hold h = en.getValue();
            if (p == null) { it.remove(); continue; }
            EquipmentSlot hand = shieldHand(p);
            if (!on() || tick - h.last > grace || hand != h.hand || !able(p)) { lower(p, h); it.remove(); continue; }
            raise(p, h);
            if (tick % 2 == 0) placeFake(p, h);
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Hold h = holds.remove(e.getPlayer().getUniqueId());
        if (h != null) lower(e.getPlayer(), h);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Hold h = holds.remove(e.getPlayer().getUniqueId());
        if (h != null) clearFake(e.getPlayer(), h);
    }

    void disable() {
        for (Map.Entry<UUID, Hold> en : holds.entrySet()) { Player p = Bukkit.getPlayer(en.getKey()); if (p != null) lower(p, en.getValue()); }
        holds.clear();
    }
}
