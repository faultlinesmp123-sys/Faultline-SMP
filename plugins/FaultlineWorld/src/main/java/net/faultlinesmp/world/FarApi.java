package net.faultlinesmp.world;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * For other plugins (FaultlineItems' Rim Chart reaches it by reflection): the Farlands' places near a spot, worked out from the
 * same pure functions the generator draws them with. Each entry is "kind;x;y;z" (kind = vault, camp, village, lighthouse,
 * citadel). Empty when the world isn't a Faultline world.
 */
public final class FarApi {
    private FarApi() {}

    public static List<String> near(World w, int x, int z, int r) {
        List<String> out = new ArrayList<>();
        FaultlineWorld pl = FaultlineWorld.INSTANCE;
        if (pl == null || w == null || !(w.getGenerator() instanceof WorldGen)) return out;
        Terrain t = pl.gen.terrain(w);
        int c0 = Math.floorDiv(x - r, FarGen.CELL), c1 = Math.floorDiv(x + r, FarGen.CELL);
        int d0 = Math.floorDiv(z - r, FarGen.CELL), d1 = Math.floorDiv(z + r, FarGen.CELL);
        for (int ci = c0; ci <= c1; ci++) for (int cj = d0; cj <= d1; cj++) {
            int[] f = FarGen.feature(t, ci, cj);
            if (f == null || Math.hypot(f[1] - x, f[2] - z) > r) continue;
            FarGen.Kind k = FarGen.Kind.values()[f[0]];
            if (k == FarGen.Kind.VAULT) {
                int[] at = FarGen.vaultSpot(t, f[1], f[2]);
                if (at != null) out.add("vault;" + at[0] + ";" + at[1] + ";" + at[2]);
            } else if (k == FarGen.Kind.CAMP) {
                int[] at = FarGen.campSpot(t, f[1], f[2]);
                if (at != null) out.add("camp;" + at[0] + ";" + (at[2] + 1) + ";" + at[1]);
            }
        }
        int v0 = Math.floorDiv(x - r, RimGen.VILLAGE_CELL), v1 = Math.floorDiv(x + r, RimGen.VILLAGE_CELL);
        int w0 = Math.floorDiv(z - r, RimGen.VILLAGE_CELL), w1 = Math.floorDiv(z + r, RimGen.VILLAGE_CELL);
        for (int ci = v0; ci <= v1; ci++) for (int cj = w0; cj <= w1; cj++) {
            int[] v = RimGen.villageAt(t.seed, ci, cj);
            if (v != null && Math.hypot(v[0] - x, v[1] - z) <= r) out.add("village;" + v[0] + ";" + (Terrain.SEA + 19) + ";" + v[1]);
        }
        for (int[] l : RimGen.lightsNear(t.seed, x, z, r)) { int[] lamp = RimGen.lamp(t, l); out.add("lighthouse;" + lamp[0] + ";" + lamp[1] + ";" + lamp[2]); }
        if (Math.hypot(RimGen.CITADEL[0] - x, RimGen.CITADEL[1] - z) <= Math.max(r, 600))
            out.add("citadel;" + RimGen.CITADEL[0] + ";" + (RimGen.CITADEL_Y + 1) + ";" + RimGen.CITADEL[1]);
        return out;
    }
}
