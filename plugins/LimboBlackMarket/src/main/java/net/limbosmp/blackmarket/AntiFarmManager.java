package net.limbosmp.blackmarket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents kill-farming (two players, or a player and an alt, repeatedly
 * killing each other) from inflating quest progress. Tracks the last time
 * THIS SPECIFIC killer -> victim pairing was credited; another kill of
 * the same victim within the cooldown window still happens normally and
 * still counts toward kills/streaks (StatsListener never checks this at
 * all) — the gate only applies to quest steps that require killing players.
 *
 * QuestProgressListener owns marking exclusively, and marks EVERY player
 * kill regardless of tier or active quest — otherwise farming a low-tier
 * player would never get flagged at all.
 *
 * Direction matters: A repeatedly killing B is gated, but B killing A in
 * between is a separate pairing and always counts — this only stops one
 * attacker farming one victim, not normal back-and-forth PvP between two
 * genuinely active players trading kills.
 */
public class AntiFarmManager {

    // The same kill can be checked right after it was marked (same server
    // tick). This grace window makes sure that one kill still reads as
    // "not farmed" — only a genuinely LATER kill of the same victim does.
    private static final long GRACE_MS = 3000;

    private final LimboBlackMarket plugin;
    private final Map<String, Long> lastCreditTime = new ConcurrentHashMap<>();

    public AntiFarmManager(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    private String pairKey(UUID killer, UUID victim) {
        return killer + ":" + victim;
    }

    /** Read-only check — does not mark anything. Safe to call from multiple listeners for the same kill. */
    public boolean isFarmedKill(UUID killer, UUID victim) {
        Long last = lastCreditTime.get(pairKey(killer, victim));
        if (last == null) return false;

        long elapsed = System.currentTimeMillis() - last;
        if (elapsed < GRACE_MS) return false; // this is the same kill event being checked again, not a repeat

        long cooldownMs = plugin.getConfig().getLong("anti-farm-cooldown-minutes", 10) * 60_000L;
        return elapsed < cooldownMs;
    }

    /**
     * Call exactly once per actual credited kill (see QuestProgressListener) —
     * do NOT call this from every listener that reacts to a kill, only
     * from the one place that owns "was this kill legitimate."
     */
    public void markCredited(UUID killer, UUID victim) {
        lastCreditTime.put(pairKey(killer, victim), System.currentTimeMillis());
    }

    /** Seconds left before this specific pairing is farm-eligible again. */
    public long getCooldownRemainingSeconds(UUID killer, UUID victim) {
        Long last = lastCreditTime.get(pairKey(killer, victim));
        if (last == null) return 0;
        long cooldownMs = plugin.getConfig().getLong("anti-farm-cooldown-minutes", 10) * 60_000L;
        long remaining = cooldownMs - (System.currentTimeMillis() - last);
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }
}
