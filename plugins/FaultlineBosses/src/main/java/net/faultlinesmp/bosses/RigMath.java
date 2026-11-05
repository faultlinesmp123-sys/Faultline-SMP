package net.faultlinesmp.bosses;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shared helpers the Dark Ocean bosses use (2.0.0: they lived in Lich.java, Leviathan.java and Vendetta.java, which are
 * gone with the land bosses): rig frames and joints, open sea water, and a flat facing direction.
 */
final class RigMath {
    private RigMath() { }

    static Quaternionf frame(float yaw, float pitch, float roll) {
        return new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll)).mul(Wild.Q0);
    }

    /** A point given in a part's own frame (q = that part's full turn), in blocks at scale kk, from its origin o. */
    static Location joint(Location o, Quaternionf q, double[] p, double kk) { return joint(o, q, p[0], p[1], p[2], kk); }

    static Location joint(Location o, Quaternionf q, double x, double y, double z, double kk) {
        Vector3f v = new Vector3f((float) (x * kk), (float) (y * kk), (float) (z * kk));
        q.transform(v);
        return o.clone().add(v.x, v.y, v.z);
    }

    static Quaternionf rotZ(double deg) { return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 0, 0, 1)); }
    static Quaternionf rotX(double deg) { return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 1, 0, 0)); }

    /** The top of a column of water at least 8 deep near l (the spot just above its top water block), or null. */
    static Location seaSpot(Location l, int radius) {
        World w = l.getWorld();
        for (int r = 0; r <= radius; r += 3) {
            for (int a = 0; a < (r == 0 ? 1 : 12); a++) {
                double ang = a * Math.PI / 6;
                int x = (int) Math.floor(l.getX() + Math.cos(ang) * r), z = (int) Math.floor(l.getZ() + Math.sin(ang) * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                Block b = w.getBlockAt(x, top, z);
                if (!sea(b)) continue;
                int depth = 0;
                while (depth < 12 && sea(w.getBlockAt(x, top - depth, z))) depth++;
                if (depth >= 8) return new Location(w, x + 0.5, top + 1, z + 0.5);
            }
        }
        return null;
    }

    /** Open sea water: water, and what grows or bubbles in it (kelp, seagrass, bubble columns, waterlogged plants). */
    static boolean sea(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return !m.isSolid() && b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    /** Where a location faces, flattened (never a zero vector, so never NaN). */
    static Vector flatDir(Location l) {
        Vector d = l.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(-Math.sin(Math.toRadians(l.getYaw())), 0, Math.cos(Math.toRadians(l.getYaw())));
        return d.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : d.normalize();
    }
}
