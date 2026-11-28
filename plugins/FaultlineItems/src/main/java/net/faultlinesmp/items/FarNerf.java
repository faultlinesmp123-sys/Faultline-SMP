package net.faultlinesmp.items;

import java.util.HashMap;
import java.util.Map;

/**
 * The Farlands nerf (owner: too much). Every Farlands value here reads its OLD default in a server config as the new one, so
 * live servers get the nerf without editing config.yml; a value the owner picked themself is kept.
 */
final class FarNerf {
    private FarNerf() {}

    private static final Map<String, double[]> T = new HashMap<>();
    private static void n(String path, double old, double now) { T.put(path, new double[]{old, now}); }
    static {
        n("insanity.rise-per-second", 0.6, 0.3);
        n("insanity.fall-per-second", 1.5, 2.5);
        n("insanity.insane-damage", 2, 1);
        n("insanity.insane-damage-seconds", 15, 30);
        n("farlands-dread.stillness.chance", 0.05, 0.02);
        n("farlands-dread.chunk-rot.chance", 0.06, 0.03);
        n("farlands-dread.long-night.chance", 0.02, 0.01);
        n("farlands-dread.long-night.insanity-per-second", 0.4, 0.2);
        n("farlands-dread.long-night.cap", 10, 5);
        n("farlands-dread.echo-storm.chance", 0.03, 0.015);
        n("farlands-dread.faceless.chance", 0.03, 0.015);
        n("farlands-dread.faceless.damage", 7, 5);
        n("farlands-dread.displace.chance", 0.05, 0.02);
        n("farlands-dread.sleep.chance", 0.35, 0.2);
        n("outer-rim.depth.health", 0.6, 0.3);
        n("outer-rim.depth.damage", 0.4, 0.2);
        n("outer-rim.storm.chance", 0.15, 0.06);
        n("outer-rim.fakes.chance", 0.08, 0.04);
        n("outer-rim.siege.chance", 0.06, 0.03);
        n("farlands-night.blood-rain.chance", 0.08, 0.04);
        n("farlands-night.blood-rain.mob-health", 0.5, 0.3);
        n("farlands-night.blood-rain.mob-damage", 0.3, 0.15);
        n("farlands-night.collapse.chance", 0.04, 0.02);
        n("farlands.mob-health", 0.25, 0.15);
        n("farlands.mob-damage", 0.25, 0.15);
        n("farlands.crawlers.spider-share", 0.3, 0.15);
        n("farlands.crawlers.pack-chance", 0.3, 0.15);
        n("farlands.crawlers.max-near", 4, 2);
        n("farlands.crawlers.drop-damage", 9, 6);
        n("farlands.ghasts.chance", 0.25, 0.12);
        n("farlands.ghasts.max", 2, 1);
        n("mob-spawning.reduce", 0.5, 0.65);
    }

    /** The config value at path, with the nerf applied (def = the caller's old literal default). */
    static double get(FaultlineItems p, String path, double def) {
        double[] t = T.get(path);
        if (t == null) return p.getConfig().getDouble(path, def);
        double v = p.getConfig().getDouble(path, t[1]);
        return Math.abs(v - t[0]) < 1e-9 ? t[1] : v;
    }
}
