package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * No survival/adventure player keeps the "allowed to fly" flag without a reason. The Moon Stone's Bat Form used to save
 * that flag when it started and put it back when it ended, so a flag left on from anywhere (an older bug, a crash in the
 * middle of a bat form, another plugin) was carried on forever: double-tap space = creative flight in survival.
 * The flag is saved in the player's file, so it survived restarts too.
 *
 * Allowed: creative/spectator, Bat Form, a Cloud Potion running, and anyone with faultline.fly / essentials.fly (or op),
 * so admin /fly keeps working. Everyone else: flight off (with Slow Falling if they were up in the air).
 */
final class FlightGuard {
    private FlightGuard() { }

    /** Someone who may fly in survival for reasons outside this plugin (/fly). */
    static boolean mayFly(Player p) {
        return p.isOp() || p.hasPermission("faultline.fly") || p.hasPermission("essentials.fly") || p.hasPermission("bosses.admin");
    }

    static void tick(FaultlineItems plugin) {
        if (!plugin.getConfig().getBoolean("flight-guard.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) check(plugin, p);
    }

    /** True if it took flight away. */
    static boolean check(FaultlineItems plugin, Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (!p.getAllowFlight() && !p.isFlying()) return false;
        if (plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(p)) return false;
        if (plugin.getDoubleJumpManager() != null && plugin.getDoubleJumpManager().isActive(p.getUniqueId())) {
            // the Cloud Potion only needs the flag for its double jump, never real flight (its listener cancels that)
            if (p.isFlying()) p.setFlying(false);
            return false;
        }
        if (mayFly(p)) return false;
        boolean wasFlying = p.isFlying();
        p.setFlying(false);
        p.setAllowFlight(false);
        if (wasFlying || !p.isOnGround()) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 6, 0, false, false, true));
        plugin.getLogger().info("Flight guard: took survival flight away from " + p.getName() + (wasFlying ? " (was flying)" : "") + ".");
        p.sendMessage(ChatColor.GRAY + "Your flight has been switched off (it was left on by a glitch).");
        return true;
    }
}
