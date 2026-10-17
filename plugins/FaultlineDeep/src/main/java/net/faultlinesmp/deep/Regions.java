package net.faultlinesmp.deep;

/**
 * The Dark Ocean's outer ring (Deep 1.9.0): the border went from 2000 to 3000, and four regions sit in the new band
 * (every one of them at least 2200 out on one axis, past where the old border could have generated chunks):
 *  - the FROZEN SHELF (FrozenShelf.java) to the north, an ice cap over the sea
 *  - the DROWNED CITY (DrownedCity.java) to the east, a sunken stone city
 *  - the GLASS REEF (GlassReef.java) to the south-east, a reef of living crystal
 *  - the BONE FIELDS (BoneFields.java) to the west, a graveyard of leviathans
 * Each is a round region with a wobbly eased edge; weight() is 1 inside, 0 outside. The generator blends each one's floor
 * in, keeps the open sea's features (stacks, arches, spires, whale falls, blue holes) out, and draws its own structures.
 */
final class Regions {
    private Regions() { }

    /** 1 inside radius r, easing to 0 over blend, with a wobbly edge (seeded by the angle only: the same on every seed). */
    static double weight(int x, int z, int cx, int cz, double r, double blend, double wob) {
        double dx = x - cx, dz = z - cz, d = Math.hypot(dx, dz);
        if (d > r + blend + wob + 2) return 0;
        double a = Math.atan2(dz, dx);
        d -= wob * (0.6 * Math.sin(a * 5 + cx * 0.001) + 0.4 * Math.sin(a * 11 + cz * 0.001));
        if (d <= r) return 1;
        if (d >= r + blend) return 0;
        double t = 1 - (d - r) / blend;
        return t * t * (3 - 2 * t);
    }

    static boolean near(int x, int z, int cx, int cz, double reach) { return Math.abs(x - cx) < reach && Math.abs(z - cz) < reach && Math.hypot(x - cx, z - cz) < reach; }

    /** Near any of the four (other features keep away). */
    static boolean nearAny(int x, int z, int extra) {
        return FrozenShelf.near(x, z, extra) || DrownedCity.near(x, z, extra) || GlassReef.near(x, z, extra) || BoneFields.near(x, z, extra);
    }

    /** Which region a column is in (for titles, music, spawns, the map), or null. */
    static String at(int x, int z) {
        if (FrozenShelf.weight(x, z) > 0.4) return "frozen";
        if (DrownedCity.weight(x, z) > 0.4) return "city";
        if (GlassReef.weight(x, z) > 0.4) return "glass";
        if (BoneFields.weight(x, z) > 0.4) return "bones";
        return null;
    }

    static String title(String region) {
        return switch (region) {
            case "frozen" -> "The Frozen Shelf";
            case "city" -> "The Drowned City";
            case "glass" -> "The Glass Reef";
            case "bones" -> "The Bone Fields";
            default -> "";
        };
    }

    static String subtitle(String region) {
        return switch (region) {
            case "frozen" -> "The sea freezes over. Mind your air under the ice.";
            case "city" -> "Its people never left.";
            case "glass" -> "Everything here is made of light.";
            case "bones" -> "Something killed all of them.";
            default -> "";
        };
    }
}
