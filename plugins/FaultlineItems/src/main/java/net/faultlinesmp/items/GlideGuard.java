package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * GLIDE GUARD (Items 1.3.40, config glide-guard:). The "fly like creative in survival" glitch: glide with an elytra, swap a
 * chestplate on mid-air, log out. The player's file is saved with the gliding flag still on, and they come back gliding
 * with no elytra = free flight. Every 2 ticks (and 1 / 10 / 40 ticks after joining) a survival / adventure player who is
 * gliding without a working glider (an elytra or any item with the glider component, not broken) has the glide ended; a
 * player quitting in that state is fixed before their file is written. Logged once per player per minute.
 */
final class GlideGuard implements Listener {
    private final FaultlineItems plugin;
    private final java.util.Map<java.util.UUID, Long> logged = new java.util.HashMap<>();

    GlideGuard(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!on()) return;
            for (Player p : Bukkit.getOnlinePlayers()) try { check(p); } catch (RuntimeException ignored) { }
        }, 40L, 2L);
    }

    boolean on() { return plugin.getConfig().getBoolean("glide-guard.enabled", true); }

    /** True if it ended an illegal glide. */
    boolean check(Player p) {
        if (!p.isGliding() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (canGlide(p)) return false;
        p.setGliding(false);
        long now = System.currentTimeMillis();
        Long last = logged.get(p.getUniqueId());
        if (last == null || now - last > 60_000) {
            logged.put(p.getUniqueId(), now);
            plugin.getLogger().info("[GlideGuard] ended " + p.getName() + "'s glide with no elytra on");
        }
        return true;
    }

    static boolean canGlide(Player p) {
        EntityEquipment eq = p.getEquipment();
        if (eq == null) return false;
        for (ItemStack it : new ItemStack[]{eq.getChestplate(), eq.getHelmet(), eq.getLeggings(), eq.getBoots()}) {
            if (it == null || it.getType().isAir()) continue;
            boolean glider = it.getType() == Material.ELYTRA;
            if (!glider) try { glider = it.hasData(io.papermc.paper.datacomponent.DataComponentTypes.GLIDER); } catch (Throwable ignored) { }
            if (!glider) continue;
            // a broken elytra (1 durability left) can't glide
            if (it.getItemMeta() instanceof Damageable d && it.getType().getMaxDurability() > 0
                    && d.getDamage() >= it.getType().getMaxDurability() - 1) continue;
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (long d : new long[]{1L, 10L, 40L}) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && on()) check(p); }, d);
    }

    /** Fixed before the player's file is saved, so they can't come back gliding. LOWEST = before the save at the end. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        if (on()) try { check(e.getPlayer()); } catch (RuntimeException ignored) { }
        logged.remove(e.getPlayer().getUniqueId());
    }
}
