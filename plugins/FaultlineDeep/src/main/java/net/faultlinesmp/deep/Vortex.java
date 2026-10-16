package net.faultlinesmp.deep;

import io.papermc.paper.math.Position;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE WHIRLPOOL (Deep 1.5.0): a real funnel of water. The sea is sucked down into a cone (radius `radius`, `depth` deep
 * at the eye) that opens over a few seconds and closes again at the end. The cone is FAKE air sent to everyone close by
 * (Player#sendMultiBlockChange): the world itself never changes, so nothing floods, flows or needs putting back after a
 * crash, and a player inside it really falls (their client sees air), slides round the walls and drops into the eye.
 * Rings of spray, mist, drips and bubbles spiral down the walls; the roar of the water.
 */
final class Vortex {
    final Location center; // the top water block in the middle
    final int surf;
    final double maxR, maxD;
    double r, d;            // now
    int t;
    boolean closing;
    private Map<Position, BlockData> cells = Map.of();
    private final Set<UUID> viewers = new HashSet<>();
    private boolean changed = true;

    Vortex(Location spot, double radius, double depth) {
        World w = spot.getWorld();
        int x = spot.getBlockX(), z = spot.getBlockZ(), y = spot.getBlockY();
        // the surface: the top water block of this column (from a little above the spot)
        int top = y + 3;
        while (top > y - 6 && !water(w.getBlockAt(x, top, z))) top--;
        if (!water(w.getBlockAt(x, top, z))) top = y;
        surf = top;
        int deep = 0;
        while (deep < depth && water(w.getBlockAt(x, top - deep - 1, z))) deep++;
        maxD = Math.max(5, deep); // the eye stops at the sea floor
        maxR = radius;
        center = new Location(w, x + 0.5, surf, z + 0.5);
    }

    static boolean water(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged() && !m.isSolid();
    }

    /** How deep the cone is at distance x from the middle (0 outside it). */
    double depthAt(double x) {
        if (x >= r || r <= 0) return 0;
        double t = 1 - x / r;
        return Math.max(x < r - 0.6 ? 1 : 0, d * Math.pow(t, 1.7));
    }

    /** True if l is inside the open cone (in the air of the funnel, under the old surface). */
    boolean inside(Location l) {
        double x = Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ());
        return l.getY() <= surf + 1.2 && l.getY() > surf + 1 - depthAt(x) - 0.5;
    }

    /** At the bottom of the eye. */
    boolean atEye(Location l) {
        double x = Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ());
        return x < 2.6 && l.getY() < surf + 3 - d;
    }

    double dist(Location l) { return Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ()); }

    /** One tick: open (or close) the cone, send it, draw it. */
    void tick() {
        t++;
        double or = r, od = d;
        if (closing) { r = Math.max(0, r - maxR / 30); d = Math.max(0, d - maxD / 20); }
        else { r = Math.min(maxR, maxR * t / 60.0); d = Math.min(maxD, maxD * Math.max(0, t - 10) / 70.0); }
        if (Math.abs(or - r) > 0.25 || Math.abs(od - d) > 0.25 || t % 40 == 0) { rebuild(); changed = true; }
        if (changed || t % 5 == 0) send();
        draw();
    }

    private boolean sent;

    private void rebuild() {
        sent = false;
        World w = center.getWorld();
        Map<Position, BlockData> out = new HashMap<>();
        BlockData air = Material.AIR.createBlockData();
        int cx = center.getBlockX(), cz = center.getBlockZ(), ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            int dd = (int) Math.round(depthAt(Math.hypot(dx, dz)));
            for (int y = surf; y > surf - dd; y--) {
                Block b = w.getBlockAt(cx + dx, y, cz + dz);
                if (!water(b)) break;
                out.put(Position.block(cx + dx, y, cz + dz), air);
            }
        }
        // cells the cone no longer covers go back to what's really there
        Map<Position, BlockData> old = cells;
        cells = out;
        restoreFor(old, out);
    }

    private final Map<Position, BlockData> pendingRestore = new HashMap<>();

    private void restoreFor(Map<Position, BlockData> old, Map<Position, BlockData> now) {
        World w = center.getWorld();
        for (Position p : old.keySet()) if (!now.containsKey(p))
            pendingRestore.put(p, w.getBlockAt(p.blockX(), p.blockY(), p.blockZ()).getBlockData());
    }

    private void send() {
        changed = false;
        World w = center.getWorld();
        Set<UUID> still = new HashSet<>();
        boolean refresh = t % 40 == 0;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(center) > 96 * 96) continue;
            still.add(p.getUniqueId());
            // only when the shape changed, to someone who just came close, or a refresh every 2 s (a block update
            // from the server can put real water back on a client)
            if (!sent || refresh || !viewers.contains(p.getUniqueId())) {
                Map<Position, BlockData> m = new HashMap<>(pendingRestore);
                m.putAll(cells);
                if (!m.isEmpty()) try { p.sendMultiBlockChange(m); } catch (Throwable ignored) { }
            }
        }
        sent = true;
        // anyone who walked off gets the real sea back
        for (UUID id : viewers) if (!still.contains(id)) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(w)) realTo(p);
        }
        viewers.clear(); viewers.addAll(still);
        pendingRestore.clear();
    }

    private void realTo(Player p) {
        World w = center.getWorld();
        Map<Position, BlockData> m = new HashMap<>();
        for (Position q : cells.keySet()) m.put(q, w.getBlockAt(q.blockX(), q.blockY(), q.blockZ()).getBlockData());
        if (!m.isEmpty()) try { p.sendMultiBlockChange(m); } catch (Throwable ignored) { }
    }

    /** The sea comes back for everyone (the end, or the plugin stopping). */
    void clear() {
        World w = center.getWorld();
        for (UUID id : viewers) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(w)) realTo(p);
        }
        viewers.clear();
        cells = Map.of();
    }

    boolean closed() { return closing && r <= 0.01; }

    // ------------------------------------------------------------------------------------------- the look and the sound
    private void draw() {
        World w = center.getWorld();
        if (r < 0.5) return;
        double spin = t * 0.22;
        // spiral arms of spray down the walls into the eye
        if (t % 2 == 0) for (int arm = 0; arm < 4; arm++) for (int k = 0; k <= 14; k++) {
            double s = k / 14.0, x = r * (1 - s) + 0.5, a = spin * (1 + s * 1.5) + arm * Math.PI / 2 + s * 5;
            double y = surf + 1.05 - depthAt(x) + 0.1;
            Location l = center.clone().add(Math.cos(a) * x, 0, Math.sin(a) * x); l.setY(y);
            w.spawnParticle(k % 3 == 0 ? Particle.BUBBLE_POP : Particle.SPLASH, l, 2, 0.12, 0.05, 0.12, 0.02);
            if (k % 5 == 2) w.spawnParticle(Particle.FALLING_WATER, l.clone().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0);
        }
        // the foaming rim
        int n = (int) (r * 5);
        for (int i = 0; i < n; i++) {
            double a = -spin * 0.8 + i * Math.PI * 2 / n;
            Location l = center.clone().add(Math.cos(a) * r, 1.05, Math.sin(a) * r);
            w.spawnParticle(Particle.DUST, l, 1, 0.15, 0.04, 0.15, 0, new Particle.DustOptions(Color.fromRGB(235, 245, 255), 1.6f));
            if (i % 4 == 0) w.spawnParticle(Particle.SPLASH, l, 3, 0.2, 0.05, 0.2, 0.05);
        }
        // mist over it, and the dark eye
        if (t % 4 == 0) w.spawnParticle(Particle.CLOUD, center.clone().add(0, 1.3, 0), 4, r * 0.4, 0.2, r * 0.4, 0.01);
        Location eye = center.clone().add(0, 1 - d, 0);
        w.spawnParticle(Particle.DUST, eye, 10, 0.9, 0.3, 0.9, 0, new Particle.DustOptions(Color.fromRGB(8, 14, 40), 2.4f));
        if (t % 3 == 0) w.spawnParticle(Particle.BUBBLE_COLUMN_UP, eye, 6, 0.6, 0.4, 0.6, 0.05);
        // while it opens, the water rushes in from all round
        if (!closing && r < maxR) for (int i = 0; i < 16; i++) {
            double a = Math.random() * Math.PI * 2, x = r + 1 + Math.random() * 3;
            Location l = center.clone().add(Math.cos(a) * x, 1.0, Math.sin(a) * x);
            w.spawnParticle(Particle.SPLASH, l, 3, 0.1, 0.05, 0.1, 0.1);
        }
        if (t % 20 == 0) w.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.AMBIENT, 3f, 0.5f);
        if (t % 30 == 5) w.playSound(center, Sound.WEATHER_RAIN_ABOVE, SoundCategory.AMBIENT, 2.5f, 0.5f);
        if (t % 50 == 0) w.playSound(center, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE, SoundCategory.AMBIENT, 2f, 0.6f);
        if (t == 1) w.playSound(center, Sound.AMBIENT_UNDERWATER_ENTER, SoundCategory.AMBIENT, 3f, 0.4f);
    }
}
