package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Deep 1.19.3: FaultlineItems' abyssal Spider Nests are dry pockets carved in the abyss. A spot inside one counts as dry
 * (`Market.dry`): no pitch-black Abyss darkness (the nest has its own), no Pressure Surge, your things drop normally.
 * Asked through `net.faultlinesmp.items.NestApi` by reflection, so neither plugin needs the other.
 */
final class NestLink {
    private static Method method;
    private static Plugin owner;
    private static long triedAt = Long.MIN_VALUE;

    private NestLink() { }

    static boolean inNest(double x, double y, double z) {
        Plugin p = Bukkit.getPluginManager().getPlugin("FaultlineItems");
        if (p == null || !p.isEnabled()) return false;
        if (p != owner) { method = null; owner = p; triedAt = Long.MIN_VALUE; } // a reload swapped its classes
        if (method == null) {
            long now = System.currentTimeMillis();
            if (now - triedAt < 60000) return false;
            triedAt = now;
            try { method = Class.forName("net.faultlinesmp.items.NestApi", true, p.getClass().getClassLoader()).getMethod("inAbyssNest", double.class, double.class, double.class); }
            catch (Throwable e) { return false; }
        }
        try { return (Boolean) method.invoke(null, x, y, z); } catch (Throwable e) { return false; }
    }
}
