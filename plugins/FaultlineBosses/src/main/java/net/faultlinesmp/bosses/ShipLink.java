package net.faultlinesmp.bosses;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * FaultlineShips' ShipsApi, reached by reflection (no hard dependency: without the ships plugin, only vanilla boats
 * count). A "vessel" is either a FaultlineShips ship (by id) or a vanilla boat entity.
 */
final class ShipLink {
    private static Class<?> api;
    private static boolean looked;

    private static Class<?> api() {
        if (looked) return api;
        looked = true;
        Plugin ships = Bukkit.getPluginManager().getPlugin("FaultlineShips");
        if (ships == null || !ships.isEnabled()) return null;
        try { api = Class.forName("net.faultlinesmp.ships.ShipsApi", true, ships.getClass().getClassLoader()); }
        catch (Throwable t) { api = null; }
        return api;
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        Class<?> a = api();
        if (a == null) return null;
        try {
            Method m = a.getMethod(name, types);
            return m.invoke(null, args);
        } catch (Throwable t) { return null; }
    }

    /** What a player is sailing: a ship, a boat, or nothing. */
    static Vessel of(Player p) {
        if (p == null) return null;
        Entity v = p.getVehicle();
        if (v instanceof Boat b) return new Vessel(null, b);
        Object id = call("shipOf", new Class<?>[]{Player.class}, p);
        return id instanceof UUID u ? new Vessel(u, null) : null;
    }

    static final class Vessel {
        final UUID ship; final Boat boat;
        Vessel(UUID ship, Boat boat) { this.ship = ship; this.boat = boat; }

        Location center() {
            if (boat != null) return boat.isValid() ? boat.getLocation() : null;
            Object o = call("center", new Class<?>[]{UUID.class}, ship);
            return o instanceof Location l ? l : null;
        }

        double radius() {
            if (boat != null) return 1.5;
            Object o = call("radius", new Class<?>[]{UUID.class}, ship);
            return o instanceof Double d ? d : 4;
        }

        boolean alive() {
            if (boat != null) return boat.isValid() && !boat.isDead();
            return Boolean.TRUE.equals(call("alive", new Class<?>[]{UUID.class}, ship));
        }

        double hp() { Object o = boat != null ? null : call("hp", new Class<?>[]{UUID.class}, ship); return o instanceof Double d ? d : 0; }
        double maxHp() { Object o = boat != null ? null : call("maxHp", new Class<?>[]{UUID.class}, ship); return o instanceof Double d ? d : 0; }

        String title() {
            if (boat != null) return "boat";
            Object o = call("title", new Class<?>[]{UUID.class}, ship);
            return o instanceof String s ? s : "ship";
        }

        double distanceToHull(Location at) {
            if (boat != null) return boat.isValid() && boat.getWorld().equals(at.getWorld()) ? boat.getLocation().distance(at) : Double.MAX_VALUE;
            Object o = call("distanceToHull", new Class<?>[]{UUID.class, Location.class}, ship, at);
            return o instanceof Double d ? d : Double.MAX_VALUE;
        }

        /** Hurts a ship by this fraction of its health (the parts nearest the spot break); a boat just breaks. */
        boolean hit(Location at, double fraction) {
            if (boat != null) {
                if (!boat.isValid()) return false;
                Location l = boat.getLocation();
                boat.eject();
                l.getWorld().playSound(l, org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.5f, 0.8f);
                l.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, l, 40, 0.8, 0.4, 0.8, 0, org.bukkit.Material.OAK_PLANKS.createBlockData());
                boat.remove();
                return true;
            }
            double max = maxHp();
            return Boolean.TRUE.equals(call("damage", new Class<?>[]{UUID.class, double.class, Location.class}, ship, Math.max(1, max * fraction), at));
        }

        boolean same(Vessel o) { return o != null && (ship != null ? ship.equals(o.ship) : boat != null && o.boat != null && boat.getUniqueId().equals(o.boat.getUniqueId())); }
    }
}
