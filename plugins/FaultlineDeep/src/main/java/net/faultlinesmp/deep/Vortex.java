package net.faultlinesmp.deep;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE WHIRLPOOL (Deep 1.5.0, reworked in 1.16.0): a real funnel of water. The sea is sucked down into a cone (radius `radius`,
 * `depth` deep at the eye) that opens over a few seconds and closes again at the end. The cone is FAKE air sent to everyone
 * who has its chunk (Player#sendMultiBlockChange): the world itself never changes, so nothing floods, flows or needs putting
 * back after a crash, and a player inside it really falls (their client sees air), slides round the walls and drops into
 * the eye.
 *
 * What makes it its own thing (1.16.0):
 *  - THE BEAM: a fake beacon under the sea floor below the eye (3x3 iron base, light blue glass in the floor over it), so
 *    every client draws a real light-blue beacon beam up into the sky, seen from as far as their render distance. Plus a
 *    column of long-distance dust for anyone whose game doesn't draw it (Bedrock).
 *  - DEBRIS: planks, logs, barrels, kelp and coral tumbling round the walls, faster and faster, down into the eye.
 *  - THE EYE: a black disc ringed in a cyan glow, turning at the bottom.
 *  - THE SOUND: a layered roar (the whirlpool's churn, rushing water, gulps, splashes at the rim, a deep hum and a groan
 *    from below); players further off hear a distant roar from its direction.
 */
final class Vortex {
    static final String TAG = "faultline_whirlpool";

    final Location center; // the top water block in the middle
    final int surf;
    final int floorY;      // the top of the sea floor under the eye
    final double maxR, maxD;
    double r, d;            // now
    int t;
    boolean closing;
    private Map<Position, BlockData> cells = Map.of();
    private final Map<Position, BlockData> beam = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private boolean changed = true;
    private final Random random = new Random();
    private final List<Debris> debris = new ArrayList<>();
    private BlockDisplay eye;
    final int debrisCount;
    final double beamHeight;

    private static final Material[] DEBRIS = {Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.DARK_OAK_LOG, Material.BARREL,
            Material.OAK_FENCE, Material.KELP_PLANT, Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.PRISMARINE,
            Material.SPRUCE_LOG, Material.CHEST, Material.DRIED_KELP_BLOCK};

    private final class Debris {
        BlockDisplay d;
        double s, a, speed, spin;
        Debris(double s) { this.s = s; this.a = random.nextDouble() * Math.PI * 2; this.speed = 0.004 + random.nextDouble() * 0.005; this.spin = 0.06 + random.nextDouble() * 0.05; }
    }

    Vortex(Location spot, double radius, double depth, int debrisCount, double beamHeight) {
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
        int f = surf;
        while (f > w.getMinHeight() + 3 && water(w.getBlockAt(x, f, z))) f--;
        floorY = f;
        this.debrisCount = debrisCount;
        this.beamHeight = beamHeight;
        // the beam: beacon in the ground under the eye, light blue glass in the floor above it, a 3x3 iron base under it
        if (w.getBlockAt(x, f, z).getType().isSolid() && w.getBlockAt(x, f - 1, z).getType().isSolid()) {
            beam.put(Position.block(x, f, z), Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            beam.put(Position.block(x, f - 1, z), Material.BEACON.createBlockData());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                beam.put(Position.block(x + dx, f - 2, z + dz), Material.IRON_BLOCK.createBlockData());
        }
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
        return d >= Math.min(5, maxD * 0.6) && x < 2.6 && l.getY() < surf + 3 - d; // (only once the funnel is really open)
    }

    double dist(Location l) { return Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ()); }

    /** One tick: open (or close) the cone, send it, draw it. */
    void tick() {
        t++;
        double or = r, od = d;
        if (closing) { r = Math.max(0, r - maxR / 60); d = Math.max(0, d - maxD / 40); }
        else { r = Math.min(maxR, maxR * t / 80.0); d = Math.min(maxD, maxD * Math.max(0, t - 10) / 90.0); }
        if (Math.abs(or - r) > 0.25 || Math.abs(od - d) > 0.25 || t % 40 == 0) { rebuild(); changed = true; }
        if (changed || t % 10 == 0) send();
        draw();
        moveDebris();
        sound();
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
        for (Position p : old.keySet()) if (!out.containsKey(p))
            pendingRestore.put(p, w.getBlockAt(p.blockX(), p.blockY(), p.blockZ()).getBlockData());
    }

    private final Map<Position, BlockData> pendingRestore = new HashMap<>();

    /** Who gets the fake blocks: anyone whose game has the middle chunk (so the beam shows from their render distance). */
    private boolean viewer(Player p) {
        if (!p.getWorld().equals(center.getWorld())) return false;
        try {
            long key = ((long) (center.getBlockZ() >> 4) << 32) | ((center.getBlockX() >> 4) & 0xFFFFFFFFL);
            if (p.isChunkSent(key)) return true;
        } catch (Throwable ignored) { }
        return p.getLocation().distanceSquared(center) < 160 * 160;
    }

    private void send() {
        changed = false;
        World w = center.getWorld();
        Set<UUID> still = new HashSet<>();
        boolean refresh = t % 40 == 0;
        for (Player p : w.getPlayers()) {
            if (!viewer(p)) continue;
            still.add(p.getUniqueId());
            // only when the shape changed, to someone who just got the chunk, or a refresh every 2 s (a block update or a
            // chunk sent again by the server puts real water back on a client)
            if (!sent || refresh || !viewers.contains(p.getUniqueId())) {
                Map<Position, BlockData> m = new HashMap<>(pendingRestore);
                m.putAll(cells);
                if (!closing || r > 1) m.putAll(beam);
                if (!m.isEmpty()) try { p.sendMultiBlockChange(m); } catch (Throwable ignored) { }
            }
        }
        sent = true;
        // anyone who went off gets the real sea back
        for (UUID id : viewers) if (!still.contains(id)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(w)) realTo(p);
        }
        viewers.clear(); viewers.addAll(still);
        pendingRestore.clear();
    }

    private void realTo(Player p) {
        World w = center.getWorld();
        Map<Position, BlockData> m = new HashMap<>();
        for (Position q : cells.keySet()) m.put(q, w.getBlockAt(q.blockX(), q.blockY(), q.blockZ()).getBlockData());
        for (Position q : beam.keySet()) m.put(q, w.getBlockAt(q.blockX(), q.blockY(), q.blockZ()).getBlockData());
        if (!m.isEmpty()) try { p.sendMultiBlockChange(m); } catch (Throwable ignored) { }
    }

    /** The sea comes back for everyone (the end, or the plugin stopping), the debris and the eye go. */
    void clear() {
        World w = center.getWorld();
        for (Player p : w.getPlayers()) if (viewers.contains(p.getUniqueId()) || viewer(p)) realTo(p);
        viewers.clear();
        cells = Map.of();
        for (Debris b : debris) if (b.d != null && b.d.isValid()) b.d.remove();
        debris.clear();
        if (eye != null && eye.isValid()) eye.remove();
        eye = null;
    }

    boolean closed() { return closing && r <= 0.01; }

    // ------------------------------------------------------------------------------------------- debris and the eye
    private BlockDisplay display(Location at, Material m, float scale) {
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setBlock(m.createBlockData());
            d.setTeleportDuration(2);
            d.setViewRange(1.5f);
            d.setTransformation(new Transformation(new Vector3f(-scale / 2, -scale / 2, -scale / 2),
                    new AxisAngle4f((float) (random.nextDouble() * Math.PI), random.nextFloat() - 0.5f, 1f, random.nextFloat() - 0.5f).normalize(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    private Location onWall(double x, double a) {
        Location l = center.clone().add(Math.cos(a) * x, 0, Math.sin(a) * x);
        l.setY(surf + 1.15 - depthAt(x) + 0.15);
        l.setYaw((float) Math.toDegrees(a));
        return l;
    }

    private void moveDebris() {
        if (t % 2 != 0) return;
        World w = center.getWorld();
        if (!closing && r >= 3 && debris.size() < debrisCount && t % 6 == 0) {
            Debris b = new Debris(random.nextDouble() * 0.3);
            b.d = display(onWall(r * (1 - b.s), b.a), DEBRIS[random.nextInt(DEBRIS.length)], 0.35f + random.nextFloat() * 0.4f);
            debris.add(b);
        }
        for (Debris b : debris) {
            if (b.d == null || !b.d.isValid()) continue;
            b.s += b.speed * 2;
            b.a += (b.spin + 0.3 * b.s * b.s) * 2;              // faster and faster toward the eye
            double x = Math.max(0.4, r * (1 - b.s));
            if (b.s >= 1 || closing && r < 1.5) { // swallowed: back out at the rim as something else
                w.spawnParticle(Particle.BUBBLE_POP, b.d.getLocation(), 6, 0.2, 0.2, 0.2, 0.05);
                if (closing) { b.d.remove(); continue; }
                b.s = 0; b.a = random.nextDouble() * Math.PI * 2;
                b.d.setBlock(DEBRIS[random.nextInt(DEBRIS.length)].createBlockData());
                b.d.teleport(onWall(r, b.a));
                continue;
            }
            Location l = onWall(x, b.a);
            l.setPitch((float) (Math.sin(t * 0.2 + b.a) * 40));
            b.d.teleport(l);
        }
        // the eye: a black disc ringed in cyan, turning at the bottom
        if (r >= 2 && d >= 2) {
            Location at = center.clone(); at.setY(surf + 1 - d + 0.08); at.setYaw((t * 9) % 360);
            if (eye == null || !eye.isValid()) {
                eye = at.getWorld().spawn(at, BlockDisplay.class, e -> {
                    e.setPersistent(false);
                    e.addScoreboardTag(TAG);
                    e.addScoreboardTag("faultline_no_bedrock_fx");
                    e.setBlock(Material.BLACK_CONCRETE.createBlockData());
                    e.setGlowing(true);
                    e.setGlowColorOverride(Color.fromRGB(70, 230, 255));
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setTeleportDuration(2);
                    e.setViewRange(1.5f);
                    e.setTransformation(new Transformation(new Vector3f(-1.6f, 0, -1.6f), new AxisAngle4f(), new Vector3f(3.2f, 0.06f, 3.2f), new AxisAngle4f()));
                });
            } else eye.teleport(at);
        } else if (eye != null && eye.isValid()) { eye.remove(); eye = null; }
    }

    // ------------------------------------------------------------------------------------------- the look
    private static final Particle.DustOptions FOAM = new Particle.DustOptions(Color.fromRGB(235, 245, 255), 1.6f),
            TEAL = new Particle.DustOptions(Color.fromRGB(60, 200, 230), 1.3f),
            DEEP = new Particle.DustOptions(Color.fromRGB(8, 14, 40), 2.4f),
            BEAM = new Particle.DustOptions(Color.fromRGB(120, 215, 255), 3.0f);

    private void draw() {
        World w = center.getWorld();
        // the beam for anyone far off (and for games that don't draw the fake beacon): long-distance dust up into the sky
        if (t % 5 == 0 && (!closing || r > 1)) try {
            for (double y = surf + 2; y < surf + 2 + beamHeight; y += 2.5)
                w.spawnParticle(Particle.DUST, center.getX(), y, center.getZ(), 1, 0.12, 0.6, 0.12, 0, BEAM, true);
        } catch (RuntimeException ignored) { } // (test servers without long-distance particles)
        if (r < 0.5) return;
        double spin = t * 0.22;
        // spiral arms of spray and teal water down the walls into the eye
        if (t % 2 == 0) for (int arm = 0; arm < 5; arm++) for (int k = 0; k <= 14; k++) {
            double s = k / 14.0, x = r * (1 - s) + 0.5, a = spin * (1 + s * 1.5) + arm * Math.PI * 2 / 5 + s * 5;
            Location l = onWall(x, a);
            w.spawnParticle(k % 3 == 0 ? Particle.BUBBLE_POP : Particle.SPLASH, l, 2, 0.12, 0.05, 0.12, 0.02);
            if (k % 2 == 1) w.spawnParticle(Particle.DUST, l, 1, 0.1, 0.05, 0.1, 0, TEAL);
            if (k % 5 == 2) w.spawnParticle(Particle.FALLING_WATER, l.clone().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0);
        }
        // the foaming rim, turning the other way
        int n = (int) (r * 5);
        for (int i = 0; i < n; i++) {
            double a = -spin * 0.8 + i * Math.PI * 2 / n;
            Location l = center.clone().add(Math.cos(a) * r, 1.05, Math.sin(a) * r);
            w.spawnParticle(Particle.DUST, l, 1, 0.15, 0.04, 0.15, 0, FOAM);
            if (i % 4 == 0) w.spawnParticle(Particle.SPLASH, l, 3, 0.2, 0.05, 0.2, 0.05);
        }
        // a ring of glowing sea-spray hanging over it, and mist
        if (t % 3 == 0) for (int i = 0; i < 10; i++) {
            double a = spin * 0.5 + i * Math.PI / 5;
            w.spawnParticle(Particle.GLOW, center.clone().add(Math.cos(a) * r * 0.7, 2.2 + Math.sin(t * 0.1 + i), Math.sin(a) * r * 0.7), 1, 0.1, 0.1, 0.1, 0);
        }
        if (t % 4 == 0) w.spawnParticle(Particle.CLOUD, center.clone().add(0, 1.3, 0), 4, r * 0.4, 0.2, r * 0.4, 0.01);
        Location eyeAt = center.clone().add(0, 1 - d, 0);
        w.spawnParticle(Particle.DUST, eyeAt, 10, 0.9, 0.3, 0.9, 0, DEEP);
        if (t % 3 == 0) w.spawnParticle(Particle.BUBBLE_COLUMN_UP, eyeAt, 6, 0.6, 0.4, 0.6, 0.05);
        // while it opens, the water rushes in from all round
        if (!closing && r < maxR) for (int i = 0; i < 16; i++) {
            double a = random.nextDouble() * Math.PI * 2, x = r + 1 + random.nextDouble() * 3;
            w.spawnParticle(Particle.SPLASH, center.clone().add(Math.cos(a) * x, 1.0, Math.sin(a) * x), 3, 0.1, 0.05, 0.1, 0.1);
        }
    }

    // ------------------------------------------------------------------------------------------- the sound
    private void sound() {
        World w = center.getWorld();
        if (r < 0.5) return;
        Location eyeAt = center.clone().add(0, 1 - d, 0);
        float loud = (float) Math.min(1, r / Math.max(1, maxR));
        if (t == 1) {
            w.playSound(center, Sound.AMBIENT_UNDERWATER_ENTER, SoundCategory.AMBIENT, 4f, 0.4f);
            w.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.AMBIENT, 3f, 0.5f);
        }
        if (t % 20 == 0) w.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.AMBIENT, 4f * loud, 0.5f);   // the churn
        if (t % 10 == 5) w.playSound(eyeAt, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundCategory.AMBIENT, 3f * loud, 0.55f + random.nextFloat() * 0.2f);
        if (t % 30 == 3) w.playSound(center, Sound.WEATHER_RAIN, SoundCategory.AMBIENT, 3f * loud, 0.45f);               // rushing water
        if (t % 25 == 12) w.playSound(eyeAt, Sound.ITEM_BUCKET_EMPTY, SoundCategory.AMBIENT, 2.5f * loud, 0.5f);         // gulps
        if (t % 15 == 7) { // splashes round the rim
            double a = random.nextDouble() * Math.PI * 2;
            w.playSound(center.clone().add(Math.cos(a) * r, 1, Math.sin(a) * r), Sound.ENTITY_GENERIC_SPLASH, SoundCategory.AMBIENT, 1.8f, 0.6f + random.nextFloat() * 0.4f);
        }
        if (t % 60 == 30) w.playSound(eyeAt, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, SoundCategory.AMBIENT, 3f * loud, 0.5f);  // the deep hum
        if (t % 120 == 60) w.playSound(eyeAt, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, SoundCategory.AMBIENT, 2.5f * loud, 0.5f); // a groan from below
        // further off (40-200 blocks): a distant roar from its direction
        if (t % 40 == 20) for (Player p : w.getPlayers()) {
            double dd = p.getLocation().distance(center);
            if (dd < 40 || dd > 200) continue;
            Location from = p.getEyeLocation().add(center.toVector().subtract(p.getEyeLocation().toVector()).normalize().multiply(12));
            float vol = (float) (0.9 * (1 - (dd - 40) / 160)) * loud;
            p.playSound(from, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.AMBIENT, vol, 0.45f);
            p.playSound(from, Sound.WEATHER_RAIN, SoundCategory.AMBIENT, vol * 0.6f, 0.4f);
        }
    }
}
