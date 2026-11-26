package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The server's combat plugin, PvPManager by ChanceSD (2.0; it replaced DonutCombat), reached by reflection so FaultlineItems
 * never needs it to load. Its public API: {@code CombatPlayer.get(Player)}, {@code isInCombat()}, {@code tag(boolean attacker,
 * CombatPlayer enemy)}, {@code untag(UntagReason.PLUGIN_API)}, {@code removeEnemy}, {@code getEnemies()}; the tag length is its
 * config's {@code Combat Tag.Time}. Every call fails quietly (one warning) if a PvPManager update changes those names.
 */
final class PvpLink {
    private PvpLink() { }

    private static Plugin pm;
    private static Class<?> cpClass;
    private static Method get, isIn, tag, untag, removeEnemy, getEnemies, getPlayer;
    private static Object apiReason;
    private static boolean warned;

    /** PvPManager, when it's installed and on. */
    static Plugin plugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("PvPManager");
        return p != null && p.isEnabled() ? p : null;
    }

    static boolean present() { return plugin() != null && ready(); }

    private static boolean ready() {
        Plugin p = plugin();
        if (p == null) return false;
        if (p == pm && get != null) return true;
        try {
            ClassLoader cl = p.getClass().getClassLoader();
            cpClass = Class.forName("me.chancesd.pvpmanager.player.CombatPlayer", true, cl);
            Class<?> reason = Class.forName("me.chancesd.pvpmanager.player.UntagReason", true, cl);
            get = cpClass.getMethod("get", Player.class);
            isIn = cpClass.getMethod("isInCombat");
            tag = cpClass.getMethod("tag", boolean.class, cpClass);
            untag = cpClass.getMethod("untag", reason);
            removeEnemy = cpClass.getMethod("removeEnemy", cpClass);
            getEnemies = cpClass.getMethod("getEnemies");
            getPlayer = cpClass.getMethod("getPlayer");
            for (Object c : reason.getEnumConstants()) if (c.toString().equals("PLUGIN_API")) apiReason = c;
            pm = p;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn("couldn't find PvPManager's API (" + e + ")");
            get = null;
            return false;
        }
    }

    private static Object cp(Player p) throws ReflectiveOperationException { return get.invoke(null, p); }

    private static void warn(String what) {
        if (warned) return;
        warned = true;
        Logger l = Bukkit.getLogger();
        l.warning("[FaultlineItems] " + what + ": the mb truce, mimic tags and the Riptide lock use our own combat tag instead");
    }

    /** In PvPManager's combat right now. */
    static boolean inCombat(Player p) {
        if (!present()) return false;
        try { Object c = cp(p); return c != null && (boolean) isIn.invoke(c); }
        catch (ReflectiveOperationException | RuntimeException e) { warn("PvPManager isInCombat failed (" + e + ")"); return false; }
    }

    /** Puts p in combat (enemy = who they fight; null = a mimic, so they're their own "enemy" and the tag just runs). */
    static boolean tag(Player p, Player enemy) {
        if (!present()) return false;
        try {
            Object c = cp(p);
            if (c == null) return false;
            Object e = enemy != null ? cp(enemy) : c;
            tag.invoke(c, false, e);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) { warn("PvPManager tag failed (" + e + ")"); return false; }
    }

    /** Takes p out of combat. */
    static void untag(Player p) {
        if (!present()) return;
        try { Object c = cp(p); if (c != null) untag.invoke(c, apiReason); }
        catch (ReflectiveOperationException | RuntimeException e) { warn("PvPManager untag failed (" + e + ")"); }
    }

    /** Forgets that a and b are enemies (both ways), without untagging either. */
    static void dropPair(Player a, Player b) {
        if (!present()) return;
        try {
            Object ca = cp(a), cb = cp(b);
            if (ca != null && cb != null) { removeEnemy.invoke(ca, cb); removeEnemy.invoke(cb, ca); }
        } catch (ReflectiveOperationException | RuntimeException e) { warn("PvPManager removeEnemy failed (" + e + ")"); }
    }

    /** How many enemies PvPManager has on p besides `except` (null = all). */
    static int otherEnemies(Player p, Player except) {
        if (!present()) return 0;
        try {
            Object c = cp(p);
            if (c == null) return 0;
            int n = 0;
            for (Object e : (Set<?>) getEnemies.invoke(c)) {
                Object ep = getPlayer.invoke(e);
                if (ep != null && ep != p && ep != except) n++;
            }
            return n;
        } catch (ReflectiveOperationException | RuntimeException e) { return 0; }
    }

    /** PvPManager's tag length in seconds (its `Combat Tag.Time`), or 0 without it. */
    static int seconds() {
        Plugin p = plugin();
        return p == null ? 0 : p.getConfig().getInt("Combat Tag.Time", 15);
    }
}
