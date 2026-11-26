package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * RIPTIDE LOCK (Items 1.3.41, config riptide-lock:). A Riptide trident does nothing while you're in combat: starting to
 * charge it is denied (PlayerInteractEvent, so Java and Bedrock alike never even wind up) and, in case a charge got through,
 * the launch itself is cancelled (PlayerRiptideEvent). In combat = PvPManager's tag (`PvpLink.inCombat`), the built-in combat tag (CombatTag), or a player fight in the last combat window (Truce). Creative /
 * spectator are never locked.
 */
final class RiptideLock implements Listener {
    private final FaultlineItems plugin;

    RiptideLock(FaultlineItems plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("riptide-lock.enabled", true); }

    static boolean riptide(ItemStack it) {
        return it != null && it.getType() == Material.TRIDENT && it.getEnchantmentLevel(Enchantment.RIPTIDE) > 0;
    }

    /** True while the player is tagged in combat by anything we know of. */
    boolean inCombat(Player p) {
        try { if (plugin.combatTag != null && plugin.combatTag.left(p) > 0) return true; } catch (RuntimeException ignored) { }
        try { if (plugin.truce != null && !plugin.truce.opponents(p.getUniqueId()).isEmpty()) return true; } catch (RuntimeException ignored) { }
        return PvpLink.inCombat(p);
    }

    private boolean locked(Player p) {
        return on() && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR && inCombat(p);
    }

    private void tell(Player p) {
        p.sendActionBar(net.kyori.adventure.text.Component.text("Riptide doesn't work in combat.", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!riptide(e.getItem()) || !locked(e.getPlayer())) return;
        e.setUseItemInHand(Event.Result.DENY);
        tell(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent e) {
        if (!locked(e.getPlayer())) return;
        e.setCancelled(true);
        tell(e.getPlayer());
    }
}
