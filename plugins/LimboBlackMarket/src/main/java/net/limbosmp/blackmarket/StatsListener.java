package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Updates kills/deaths/streaks whenever a player dies, broadcasts a kill
 * announcement for every real PvP kill, and broadcasts a separate
 * milestone message whenever a killer's streak exactly hits a configured
 * threshold (see kill-streaks in config.yml).
 *
 * If the killer has Invisibility active at the moment of the kill, neither
 * message is broadcast at all — being invisible should actually mean
 * something, and even a "???" with their tier and kill count gave them away.
 * The kill still counts toward their stats and streak.
 *
 * Anti-farm: kills, streaks, and the announcement always count no matter
 * how often the same victim is killed — this listener does not check or
 * mark anti-farm state at all. QuestProgressListener owns that entirely,
 * since the cooldown only ever gates quest progress.
 *
 * Friendly fire: if killer and victim share a kingdom, it's logged and
 * Clan Leaders of that kingdom get notified — visibility only, doesn't
 * block anything.
 */
public class StatsListener implements Listener {


    private final LimboBlackMarket plugin;

    public StatsListener(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        plugin.getStatsManager().addDeath(victim.getUniqueId());
        plugin.getStatsManager().resetStreak(victim.getUniqueId());

        Player killer = victim.getKiller();
        if (killer == null) return;

        checkFriendlyFire(killer, victim);

        plugin.getStatsManager().addKill(killer.getUniqueId());
        int newStreak = plugin.getStatsManager().incrementStreak(killer.getUniqueId());

        String tier = plugin.getTierManager().getTier(killer.getUniqueId());
        String tierDisplay = plugin.getMarketConfig().getDisplayName(tier);
        int killerKills = plugin.getStatsManager().getKills(killer.getUniqueId());

        // Invisible killers get NO announcement at all (and no streak message). Showing
        // "??? killed X (Tier 6, 6 kills)" gave away who it was through the tier and
        // kill count. Stats and streaks above still count, just silently.
        if (killer.hasPotionEffect(PotionEffectType.INVISIBILITY)) return;
        String killerDisplayName = killer.getName();

        String announcement = ChatColor.DARK_RED + "[Faultline] " + ChatColor.RESET
                + killerDisplayName + ChatColor.GRAY + " killed " + ChatColor.RESET + victim.getName()
                + ChatColor.GRAY + "! (" + tierDisplay + ChatColor.GRAY + ", " + killerKills + " kills)";
        Bukkit.broadcastMessage(announcement);

        String streakTemplate = plugin.getKillStreakConfig().getMessageFor(newStreak);
        if (streakTemplate != null) {
            String streakMessage = ChatColor.translateAlternateColorCodes('&', streakTemplate
                    .replace("%player%", killer.getName())
                    .replace("%streak%", String.valueOf(newStreak)));
            Bukkit.broadcastMessage(streakMessage);
        }
    }

    private void checkFriendlyFire(Player killer, Player victim) {
        String killerKingdom = plugin.getKingdomDataReader().getKingdom(killer.getUniqueId());
        if (killerKingdom == null) return;

        String victimKingdom = plugin.getKingdomDataReader().getKingdom(victim.getUniqueId());
        if (killerKingdom.equals(victimKingdom)) {
            plugin.getFriendlyFireLogger().logFriendlyFire(killer, victim, killerKingdom);
        }
    }
}
