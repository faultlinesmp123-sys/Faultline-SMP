package net.faultlinesmp.ships;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * For other plugins (FaultlineBosses' Kraken at sea), called by reflection so there's no hard dependency: the ship a
 * player is on, where it is, how hurt it is, and hurting it. Everything is by ship id and is safe to call with an id
 * that's gone (null / false / 0). Skeleton ships are never "a player's ship".
 */
public final class ShipsApi {
    private ShipsApi() {}

    private static Ship get(UUID id) {
        FaultlineShips pl = FaultlineShips.instance;
        return pl == null || id == null ? null : pl.ships.get(id);
    }

    /** The ship this player is sailing or standing on (not a skeleton ship), or null. */
    public static UUID shipOf(Player p) {
        FaultlineShips pl = FaultlineShips.instance;
        if (pl == null || p == null) return null;
        Ship s = pl.ridingOn(p);
        if (s == null) s = pl.pirates.standingOn(p);
        return s == null || s.ai != null || s.building ? null : s.id;
    }

    /** The middle of its deck, or null if it's gone. */
    public static Location center(UUID id) { Ship s = get(id); return s == null ? null : s.center(); }

    /** About how far its hull reaches from its middle. */
    public static double radius(UUID id) { Ship s = get(id); return s == null ? 0 : s.radius(); }

    /** Still afloat (not wrecked, not scrapped). */
    public static boolean alive(UUID id) { Ship s = get(id); return s != null && !s.wrecked; }

    public static double hp(UUID id) { Ship s = get(id); return s == null ? 0 : s.hp; }

    public static double maxHp(UUID id) { Ship s = get(id); return s == null ? 0 : s.maxHp(); }

    public static String title(UUID id) { Ship s = get(id); return s == null ? "ship" : s.title(); }

    /** How far this spot is from the nearest block of its hull (big if it's gone). */
    public static double distanceToHull(UUID id, Location at) {
        Ship s = get(id);
        if (s == null || at == null || !at.getWorld().getName().equals(s.world)) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < s.blocks.length; i++) {
            if (s.blocks[i] == null || s.broken.contains(i)) continue;
            best = Math.min(best, s.cellLoc(i).distanceSquared(at));
        }
        return Math.sqrt(best);
    }

    /** Hurts it (the parts nearest the spot break). True if it took the hit. */
    public static boolean damage(UUID id, double amount, Location at) {
        Ship s = get(id);
        if (s == null || s.wrecked || s.building) return false;
        s.damage(amount, null, at);
        return true;
    }
}
