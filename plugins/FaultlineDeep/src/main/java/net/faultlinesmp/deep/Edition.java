package net.faultlinesmp.deep;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bedrock detection (Floodgate UUID, or Floodgate's / Geyser's API by reflection; a yes is cached) and safe velocities. */
final class Edition {
    private Edition() { }

    private static final Map<UUID, Long> no = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> yes = ConcurrentHashMap.newKeySet();

    static boolean bedrock(Player p) {
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0) return true;
        if (yes.contains(id)) return true;
        Long at = no.get(id);
        if (at != null && System.currentTimeMillis() - at < 5000) return false;
        boolean b = api(id);
        if (b) yes.add(id); else no.put(id, System.currentTimeMillis());
        return b;
    }

    private static boolean api(UUID id) {
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = c.getMethod("getInstance").invoke(null);
            if ((boolean) c.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, id)) return true;
        } catch (Throwable ignored) { }
        try {
            Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object inst = c.getMethod("api").invoke(null);
            Object conn = c.getMethod("connectionByUuid", UUID.class).invoke(inst, id);
            if (conn != null) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Paper throws on a non-finite velocity: a push "away from" a point you stand on is 0/0. Then: a plain hop. */
    static void vel(Entity e, Vector v) {
        if (!Double.isFinite(v.getX()) || !Double.isFinite(v.getY()) || !Double.isFinite(v.getZ())) v = new Vector(0, 0.3, 0);
        e.setVelocity(v);
    }
}
