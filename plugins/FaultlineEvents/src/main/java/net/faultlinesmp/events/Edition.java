package net.faultlinesmp.events;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bedrock (Geyser/Floodgate) or Java: a Floodgate UUID, or Floodgate's API by reflection. A yes is cached. */
final class Edition {
    private static final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    static boolean bedrock(Player p) {
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0) return true;
        Boolean c = cache.get(id);
        if (c != null) return c;
        boolean yes = false;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            yes = (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, id);
        } catch (Throwable ignored) { }
        if (yes) cache.put(id, true);
        return yes;
    }
}
