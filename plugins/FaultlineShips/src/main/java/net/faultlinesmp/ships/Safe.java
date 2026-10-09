package net.faultlinesmp.ships;

import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * Velocity that can't crash. A push is often "away from a point" normalised, and when the entity stands exactly on that
 * point the vector is 0/0 = NaN. Paper refuses a non-finite velocity (IllegalArgumentException: x not finite), so the
 * whole move threw, every tick, until the boss was removed; MockBukkit accepts it, so tests never saw it.
 * A non-finite push becomes a plain hop (whatever finite upward part it had, else 0.4).
 */
final class Safe {
    private Safe() { }

    static void vel(Entity e, Vector v) {
        if (e == null || v == null) return;
        if (Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ())) { e.setVelocity(v); return; }
        double y = Double.isFinite(v.getY()) ? v.getY() : 0.4;
        e.setVelocity(new Vector(0, y, 0));
    }
}
