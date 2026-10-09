package net.faultlinesmp.bosses;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Is this player on Bedrock (through Geyser)? A Floodgate UUID has a top half of 0, but a Bedrock player who LINKED a Java
 * account plays with that Java UUID, and Geyser without Floodgate uses real Java UUIDs too. Checking only the UUID hid
 * every Bedrock-only stand-in, model and effect from those players. So Floodgate and Geyser are asked as well (by
 * reflection: neither is a hard dependency). A yes is remembered; a no is re-asked after 5 s (a session may not be
 * registered yet right at login).
 */
final class Edition {
    private Edition() {}

    private static final Map<UUID, Long> NO_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> YES = new ConcurrentHashMap<>();
    private static volatile Object floodgate, geyser;
    private static volatile Method floodgateCheck, geyserCheck;
    private static volatile boolean looked;

    static boolean bedrock(Player p) { return p != null && bedrock(p.getUniqueId()); }

    static boolean bedrock(UUID id) {
        if (id.getMostSignificantBits() == 0) return true; // a Floodgate UUID
        if (YES.containsKey(id)) return true;
        Long until = NO_UNTIL.get(id);
        long now = System.currentTimeMillis();
        if (until != null && until > now) return false;
        boolean yes = ask(id);
        if (yes) { YES.put(id, true); NO_UNTIL.remove(id); }
        else NO_UNTIL.put(id, now + 5000);
        return yes;
    }

    /** Forget a player who left (a UUID can come back on the other edition). */
    static void forget(UUID id) { YES.remove(id); NO_UNTIL.remove(id); }

    private static void look() {
        if (looked) return;
        looked = true;
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgate = c.getMethod("getInstance").invoke(null);
            floodgateCheck = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) { }
        try {
            Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyser = c.getMethod("api").invoke(null);
            geyserCheck = c.getMethod("isBedrockPlayer", UUID.class);
        } catch (Throwable ignored) { }
    }

    private static boolean ask(UUID id) {
        look();
        try { if (floodgateCheck != null && Boolean.TRUE.equals(floodgateCheck.invoke(floodgate, id))) return true; } catch (Throwable ignored) { }
        try { if (geyserCheck != null && Boolean.TRUE.equals(geyserCheck.invoke(geyser, id))) return true; } catch (Throwable ignored) { }
        return false;
    }

    /** What the checks can use, for /bedrockcheck. */
    static String sources() { look(); return (floodgateCheck != null ? "Floodgate API" : "no Floodgate API") + ", " + (geyserCheck != null ? "Geyser API" : "no Geyser API"); }
}
