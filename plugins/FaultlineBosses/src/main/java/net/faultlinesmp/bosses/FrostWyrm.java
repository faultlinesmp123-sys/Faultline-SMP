package net.faultlinesmp.bosses;

import org.bukkit.entity.EntityType;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Stray;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE FROST WYRM: an ice dragon, 3,200 health, that rules the frozen peaks. Sound a Frozen Horn up there to call it.
 * It circles high above you (bows and tridents!), hovers to breathe, swoops, and lands to roar.
 *   Phase 1: Frost Breath (a freezing cone), Icicle Rain (marked spots), Dive Bomb (a swoop down a line),
 *            Glacial Roar (it lands: ice spikes burst out of the ground around it).
 *   Phase 2 (60%): + Frost Archers (strays) and it flies faster.
 *   Phase 3 (30%): + a Blizzard around it (snow, cold, slowness the whole fight).
 */
final class FrostWyrm extends Wild.Boss {

    static final int BREATH = 1, ICICLES = 2, DIVE = 3, ROAR = 4, ARCHERS = 5;

    final double k;
    final Wild.Part bodyP, headP, wingL, wingR;
    final List<Wild.Part> tailP = new ArrayList<>();
    final List<Wild.Part> neckP = new ArrayList<>();
    Location headAt;
    final Wild.Chain tail;
    final double[] tailGaps;
    Location pos;
    float yaw, pitch;
    double flap, orbit;
    boolean landed;
    // the current move
    Location mark, lineFrom;
    Vector lineDir;
    final List<Location> marks = new ArrayList<>();
    final List<BlockDisplay> ice = new ArrayList<>();
    final Set<UUID> hitThisMove = new HashSet<>();

    FrostWyrm(Wild w, Location at, Player by) {
        super(w, "frostwyrm", at, by);
        k = c("scale", 1.4);
        pos = at.clone().add(dirOf(at.getYaw()).multiply(-20));
        pos.setY(ground(at) + 18);
        pos.setY(Math.max(pos.getY(), terrainTop(pos, 3 * k) + 6 * k)); // never born inside a mountain
        yaw = at.getYaw();
        bodyP = rig.add("wyrm_body", k, pos);
        for (int i = 0; i < 3; i++) neckP.add(rig.add("wyrm_neck", k * (1 - 0.06 * i), pos));
        headP = rig.add("wyrm_head", k, pos);
        wingL = rig.add("wyrm_wing_l", k * 1.2, pos);
        wingR = rig.add("wyrm_wing_r", k * 1.2, pos);
        int n = (int) Math.max(3, c("tail-segments", 6));
        tailGaps = new double[n + 1];
        for (int i = 0; i < n; i++) { tailP.add(rig.add(i == n - 1 ? "wyrm_tail_tip" : "wyrm_tail", k * (1.0 - 0.1 * i), pos)); tailGaps[i] = 1.05 * k * (1.0 - 0.08 * i); }
        tailGaps[n] = 0.8 * k;
        tail = new Wild.Chain(pos, n, 1.0 * k, dirOf(yaw).multiply(-1));
        hitbox(pos, (int) Math.round(3.5 * k), "The Frost Wyrm");
        hitbox(pos, (int) Math.round(2 * k), "The Frost Wyrm");
        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4f, 1.4f);
        cd = 60;
    }

    @Override double defHealth() { return 900; } // a mini boss, like the Stone Golem
    @Override BarColor barColor() { return BarColor.BLUE; }
    @Override String music() { return "minecraft:music_disc.relic"; }
    @Override double musicLength() { return 218; }
    @Override Location center() { return pos.clone(); }
    static BlockData icicle;
    static BlockData icicleData() {
        if (icicle == null) {
            try { icicle = Bukkit.createBlockData("minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]"); }
            catch (RuntimeException e) { icicle = Material.POINTED_DRIPSTONE.createBlockData(); }
        }
        return icicle;
    }

    @Override boolean valid() { return bodyP.d != null && bodyP.d.isValid(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.AQUA + "" + ChatColor.BOLD + "The sky freezes over... the Frost Wyrm answers the horn.";
            case "phase2" -> ChatColor.AQUA + "The Frost Wyrm calls its archers from the snow!";
            case "phase3" -> ChatColor.RED + "A blizzard howls around the Frost Wyrm!";
            case "death" -> ChatColor.AQUA + "The Frost Wyrm crashes into the snow and shatters like ice.";
            case "leave" -> ChatColor.AQUA + "The Frost Wyrm " + ChatColor.GRAY + "flies off into the clouds.";
            default -> null;
        };
    }

    // =====================================================================================================
    //  flying
    // =====================================================================================================
    Location fightCenter(List<Player> a) {
        if (pilot != null) return pilot.getLocation();
        if (a.isEmpty()) return home;
        double x = 0, y = 0, z = 0;
        for (Player p : a) { x += p.getLocation().getX(); y += p.getLocation().getY(); z += p.getLocation().getZ(); }
        return new Location(world, x / a.size(), y / a.size(), z / a.size());
    }

    /** The highest terrain under/around p (a 3x3 of columns, wings span far), so it flies over mountains, not through them. */
    double terrainTop(Location p, double reach) {
        double top = world.getMinHeight();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            top = Math.max(top, world.getHighestBlockYAt((int) Math.floor(p.getX() + dx * reach), (int) Math.floor(p.getZ() + dz * reach), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        return top;
    }

    void fly(Location to, double speed, float turnMax) { fly(to, speed, turnMax, false); }

    /** landing: straight to `to` (it's coming down on purpose), no terrain clearance. */
    void fly(Location to, double speed, float turnMax, boolean landing) {
        // keep clear of the terrain where it's going and where it is (legs hang 3.5 blocks below the body)
        double clear = 4.5 * k;
        to = to.clone();
        double floor = -1e9;
        if (!landing) {
            to.setY(Math.max(to.getY(), terrainTop(to, 3 * k) + clear));
            Location ahead = pos.clone().add(dirOf(yaw).multiply(5 * k));
            floor = Math.max(terrainTop(pos, 3 * k), terrainTop(ahead, 3 * k)) + clear;
            if (pos.getY() < floor) to.setY(Math.max(to.getY(), floor + 2));
        }
        Vector d = to.toVector().subtract(pos.toVector());
        yaw = turn(yaw, yawOf(flat(d)), turnMax);
        double dy = Math.max(-0.5, Math.min(pos.getY() < floor ? 1.0 : 0.5, d.getY() * 0.08));
        pitch = (float) Math.max(-25, Math.min(25, -dy * 60));
        if (!landing && pos.getY() < floor) speed *= 0.15; // a wall of rock ahead: rise first, then go on
        pos = pos.clone().add(dirOf(yaw).multiply(speed)).add(0, dy, 0);
    }

    @Override
    void bodyTick(List<Player> a) {
        if (!landed && (attack < 0 || attack == ICICLES || attack == ARCHERS)) {
            Location c = fightCenter(a);
            if (pilot != null) {
                Location to = c.clone().add(0, 4, 0);
                if (to.distanceSquared(pos) > 16) fly(to, 0.5, 10); else flap *= 0.98;
            } else {
                orbit += 0.025 * (phase >= 2 ? 1.3 : 1);
                double r = c("orbit-radius", 18);
                Location to = c.clone().add(Math.cos(orbit) * r, 0, Math.sin(orbit) * r);
                to.setY(ground(c) + c("flight-height", 12) + Math.sin(ticks * 0.05) * 2);
                fly(to, c("speed", 0.55) * (phase >= 2 ? 1.2 : 1), 6);
            }
        }
        flap += landed ? 0.04 : attack == BREATH ? 0.35 : 0.22;
        place();
        if (phase == 3 && ticks % 40 == 0) blizzard(a);
        if (ticks % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos, 4, 1.5, 0.5, 1.5, 0.02);
        if (!landed && ticks % 18 == 0) world.playSound(pos, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 3f, 1.2f);
    }

    /** Where the head is (its neck joint); the mouth is ~3 blocks further on. */
    Location headBase() { return headAt != null ? headAt.clone() : offset(pos, yaw, 4.0 * k, 1.6 * k, 0); }

    Location mouth() { return offset(headBase(), yaw, 2.8 * k, -0.4 * k, 0); }

    // eased pose values (1.4.12): the neck used to SNAP between its breath / roar / flying shapes
    double aFwd = 2.4, aUp = 1.3, aHeadPitch, aBank, aWing, aLook;
    float lastYaw = Float.NaN;
    boolean deathLimp;

    void place() {
        // banking: it leans into its turns (from how fast its heading is changing), eased
        if (Float.isNaN(lastYaw)) lastYaw = yaw;
        double dyaw = ((yaw - lastYaw + 540) % 360) - 180;
        lastYaw = yaw;
        double bank = landed ? 0 : Math.max(-28, Math.min(28, -dyaw * 4));
        aBank += (bank - aBank) * 0.12;
        double fl0 = flinchAmt();
        // a wingbeat: a quick powerful downstroke, a slower recovery; the body rises a little on each downstroke
        double beat = Math.sin(flap) + 0.3 * Math.sin(2 * flap);
        double bob = landed ? Math.sin(ticks * 0.06) * 0.05 * k : -beat * 0.22 * k;
        Location bodyAt = pos.clone().add(0, bob, 0);
        bodyP.pose(bodyAt, yaw, (float) (pitch - fl0 * 8), (float) (aBank + fl0 * 6), null);
        // the neck: three segments along a curve from the chest up to the head (low and forward to breathe, high to roar)
        boolean breath = attack == BREATH, roaring = attack == ROAR && landed;
        double wantFwd = breath ? 2.9 : roaring ? 2.5 : attack == DIVE ? 2.8 : 2.4;
        double wantUp = breath ? 0.1 : roaring ? 1.9 : attack == DIVE ? 0.5 : 1.3;
        aFwd += (wantFwd - aFwd) * 0.15; aUp += (wantUp - aUp) * 0.15;
        double fwd = aFwd, up = aUp - fl0 * 0.5;
        // the head turns to watch whoever it's after (up to 35 degrees off its heading)
        double look = 0;
        if (attack != BREATH && focus != null && focus.isOnline() && focus.getWorld().equals(world)) { // breathing: the head points where the breath goes
            Vector to = focus.getLocation().toVector().subtract(pos.toVector()).setY(0);
            if (to.lengthSquared() > 1) look = Math.max(-35, Math.min(35, ((yawOf(to) - yaw + 540) % 360) - 180));
        }
        aLook += (look - aLook) * 0.12;
        double sway = Math.sin(ticks * 0.07) * 0.25 + Math.sin(Math.toRadians(aLook)) * 0.8;
        Location base = offset(pos, yaw, 1.75 * k, 0.35 * k, 0);
        Location head = offset(pos, yaw, (1.75 + fwd) * k, (0.35 + up) * k, sway * k);
        Location ctrl = offset(pos, yaw, (1.75 + fwd * 0.45) * k, (0.35 + up * 0.95) * k, sway * 0.4 * k);
        for (int i = 0; i < neckP.size(); i++) {
            double t = (i + 0.5) / neckP.size(), u = 1 - t;
            Vector pt = base.toVector().multiply(u * u).add(ctrl.toVector().multiply(2 * u * t)).add(head.toVector().multiply(t * t));
            Vector d = ctrl.toVector().subtract(base.toVector()).multiply(2 * u).add(head.toVector().subtract(ctrl.toVector()).multiply(2 * t));
            neckP.get(i).pose(pt.toLocation(world), yawOf(flat(d)), Math.max(-70, Math.min(70, pitchOf(d) + pitch)), 0, null);
        }
        headAt = head;
        double wantHeadPitch = breath ? 25 : roaring ? -30 : attack == DIVE ? 18 : pitch + 8 - fl0 * 20;
        aHeadPitch += (wantHeadPitch - aHeadPitch) * 0.2;
        headP.pose(head, (float) (yaw + aLook * 0.6 + sway * 4), (float) aHeadPitch, (float) (aBank * 0.5), null);
        // wings: full beats in the air, swept back into a dive, folded up on the ground, limp in death
        double wantWing = deathLimp ? 70 + Math.sin(flap) * 6
                : landed ? 62 + Math.sin(flap) * 4
                : attack == DIVE ? 48 + Math.sin(flap * 2) * 3
                : beat * 34;
        aWing += (wantWing - aWing) * (landed || attack == DIVE || deathLimp ? 0.15 : 0.6);
        float fl = (float) aWing;
        Location shL = offset(pos, yaw, 0.35 * k, 0.65 * k, -0.95 * k), shR = offset(pos, yaw, 0.35 * k, 0.65 * k, 0.95 * k);
        // a flap turns each wing about its own front-back axis (left wing tip up = roll one way, right the other)
        shL.add(0, bob, 0); shR.add(0, bob, 0);
        wingL.pose(shL, yaw, pitch, (float) aBank, new Quaternionf(new AxisAngle4f((float) Math.toRadians(fl), 1, 0, 0)));
        wingR.pose(shR, yaw, pitch, (float) aBank, new Quaternionf(new AxisAngle4f((float) Math.toRadians(-fl), 1, 0, 0)));
        // the tail swings opposite the head's sway and whips harder in a dive
        double tailSwing = Math.sin(ticks * 0.09) * (attack == DIVE ? 0.9 : 0.45) * k - Math.sin(Math.toRadians(aLook)) * 0.5;
        tail.lead(offset(pos, yaw, -2.2 * k, (landed ? -0.6 * k : 0) + bob, tailSwing), tailGaps);
        for (int i = 0; i < tailP.size(); i++) {
            Vector f = tail.facing(i + 1);
            tailP.get(i).pose(tail.pts.get(i + 1), yawOf(flat(f)), Math.max(-60, Math.min(60, pitchOf(f))), 0, null);
        }
        hitboxes.get(0).teleport(pos.clone().add(0, -0.9 * k, 0));
        hitboxes.get(1).teleport(offset(head, yaw, 1.4 * k, -0.6 * k, 0));
    }

    void blizzard(List<Player> a) {
        for (Player p : a) {
            world.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 2, 0), 40, 4, 2, 4, 0.05);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0, false, false, true));
            p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + 30));
        }
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(BREATH, BREATH, ICICLES, DIVE, ROAR));
        if (phase >= 2 && minions.size() < 4) pool.add(ARCHERS);
        if (phase == 3) pool.add(DIVE);
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        marks.clear(); hitThisMove.clear();
        Location tl = t != null ? t.getLocation() : pos.clone().add(dirOf(yaw).multiply(10)).add(0, -10, 0);
        switch (move) {
            case BREATH -> { mark = tl.clone(); world.playSound(pos, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 3f, 1.6f); }
            case ICICLES -> {
                for (Player p : active()) marks.add(p.getLocation().clone());
                for (int i = 0; i < 3 + phase * 2; i++) marks.add(tl.clone().add(random.nextGaussian() * 7, 0, random.nextGaussian() * 7));
                for (Location m : marks) m.setY(ground(m));
                world.playSound(pos, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            case DIVE -> {
                lineDir = flat(tl.toVector().subtract(pos.toVector()));
                lineFrom = tl.clone().add(lineDir.clone().multiply(-16));
                lineFrom.setY(ground(lineFrom) + 14);
                mark = tl.clone(); mark.setY(ground(mark));
                world.playSound(pos, Sound.ENTITY_PHANTOM_SWOOP, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            case ROAR -> { mark = tl.clone().add(flat(pos.toVector().subtract(tl.toVector())).multiply(7)); mark.setY(ground(mark)); }
            case ARCHERS -> world.playSound(pos, Sound.ENTITY_STRAY_AMBIENT, SoundCategory.HOSTILE, 3f, 0.6f);
            default -> { }
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case BREATH -> breath(a);
            case ICICLES -> icicles();
            case DIVE -> dive(a);
            case ROAR -> roar(a);
            case ARCHERS -> archers(a);
            default -> end();
        }
    }

    /** It hovers in front of its target and breathes a cone of frost down at them. */
    void breath(List<Player> a) {
        Location hover = mark.clone().add(flat(pos.toVector().subtract(mark.toVector())).multiply(9));
        hover.setY(ground(mark) + 7);
        if (at < 30) { fly(hover, 0.7, 14); if (focus != null && focus.isOnline()) mark = focus.getLocation(); return; }
        yaw = turn(yaw, yawOf(flat(mark.toVector().subtract(pos.toVector()))), 4);
        Location mouth = offset(headBase(), yaw, 3.2 * k, -0.4 * k, 0);
        Vector dir = mark.clone().add(0, 0.5, 0).toVector().subtract(mouth.toVector()).normalize();
        for (int i = 0; i < 6; i++) {
            Vector d = dir.clone().add(new Vector(random.nextGaussian() * 0.12, random.nextGaussian() * 0.12, random.nextGaussian() * 0.12)).normalize();
            world.spawnParticle(Particle.SNOWFLAKE, mouth, 0, d.getX(), d.getY(), d.getZ(), 0.9);
            world.spawnParticle(Particle.CLOUD, mouth, 0, d.getX(), d.getY(), d.getZ(), 0.5);
        }
        if (at % 6 == 0) world.playSound(mouth, Sound.BLOCK_POWDER_SNOW_STEP, SoundCategory.HOSTILE, 2f, 0.5f);
        if (at % 5 == 0) for (Player p : a) {
            Vector to = p.getEyeLocation().toVector().subtract(mouth.toVector());
            double d = to.length();
            if (d > 18 || to.normalize().dot(dir) < Math.cos(Math.toRadians(17))) continue;
            hit(p, c("moves.breath", 3), mouth.toVector(), Guard.BLOCKABLE);
            p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 80, p.getFreezeTicks() + 40));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        }
        if (at > 30 + 60) end();
    }

    void icicles() {
        if (at < 26) { if (at % 3 == 0) for (Location m : marks) warnRing(m, 1.6, Color.fromRGB(170, 230, 255)); return; }
        if (at == 26) for (Location m : marks) {
            // from a string: Paper 26.2 moved PointedDripstone.Thickness to Speleothem.Thickness, so the typed call breaks one API or the other
            BlockData drip = icicleData();
            BlockDisplay d = world.spawn(m.clone().add(0, 14, 0), BlockDisplay.class, e -> {
                e.setBlock(Material.PACKED_ICE.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(-0.3f, 0, -0.3f), new Quaternionf(), new Vector3f(0.6f, 1.6f, 0.6f), new Quaternionf()));
                e.setTeleportDuration(1);
                e.setPersistent(false);
                e.addScoreboardTag(Wild.FX_TAG);
            });
            ice.add(d);
            int[] t = {0};
            fx.add(() -> {
                t[0]++;
                double f = Math.min(1, t[0] / 14.0);
                d.teleport(m.clone().add(0, 14 * (1 - f * f), 0));
                if (f < 1) return false;
                d.remove();
                world.spawnParticle(Particle.BLOCK, m.clone().add(0, 0.3, 0), 30, 0.6, 0.3, 0.6, 0, Material.PACKED_ICE.createBlockData());
                world.spawnParticle(Particle.BLOCK, m.clone().add(0, 0.3, 0), 10, 0.3, 0.3, 0.3, 0, drip);
                world.playSound(m, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.6f, 0.8f);
                for (Player p : near(m, 1.9, 3)) {
                    hit(p, c("moves.icicle", 10), m.toVector().add(new Vector(0, 4, 0)), Guard.HEAVY);
                    p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 60, p.getFreezeTicks() + 60));
                }
                return true;
            });
        }
        if (at > 50) end();
    }

    /** It climbs, then swoops down the line (red), through anyone on it, and back up. */
    void dive(List<Player> a) {
        int wind = 30;
        if (at <= wind) {
            fly(lineFrom, 0.9, 20);
            if (at % 2 == 0) warnLine(mark.clone().add(lineDir.clone().multiply(-10)), lineDir, 22, Color.RED);
            return;
        }
        int t = at - wind, dur = 34;
        double f = t / (double) dur;
        Location p = lineFrom.clone().add(lineDir.clone().multiply(32 * f));
        p.setY(lineFrom.getY() - (lineFrom.getY() - mark.getY() - 1.5) * Math.sin(Math.PI * f));
        Vector v = p.toVector().subtract(pos.toVector());
        yaw = yawOf(lineDir);
        pitch = (float) Math.max(-40, Math.min(40, pitchOf(v)));
        pos = p;
        world.spawnParticle(Particle.SNOWFLAKE, pos, 20, 1.5, 0.5, 1.5, 0.1);
        for (Player pp : near(pos.clone().add(0, -2.5, 0), 3.2 * k, 5)) if (hitThisMove.add(pp.getUniqueId())) {
            hit(pp, c("moves.dive", 14), pos.toVector(), Guard.HEAVY);
            Safe.vel(pp, lineDir.clone().multiply(1.3).setY(0.8));
        }
        if (t >= dur) { pitch = 0; end(); }
    }

    /** It lands, roars, and spikes of ice burst out of the ground in rings around it. */
    void roar(List<Player> a) {
        if (!landed) {
            Location to = mark.clone().add(0, 2.45 * k, 0);
            fly(to, 0.8, 16, true);
            if (pos.distanceSquared(to) < 1.5 || at > 70) { landed = true; at = 1000; world.playSound(pos, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 3f, 0.5f); }
            return;
        }
        int t = at - 1000;
        if (t == 10) {
            world.playSound(pos, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4f, 0.8f);
            world.spawnParticle(Particle.SONIC_BOOM, headBase().add(0, 0.5, 0), 1);
        }
        if (t >= 14 && t <= 38 && t % 8 == 6) {
            double r = 3 + (t - 14) / 8.0 * 3.5;
            int n = (int) (r * 2.2);
            for (int i = 0; i < n; i++) {
                double ang = i * Math.PI * 2 / n + t;
                Location l = mark.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                l.setY(ground(l));
                spike(l);
            }
            for (Player p : a) {
                double d = Math.hypot(p.getLocation().getX() - mark.getX(), p.getLocation().getZ() - mark.getZ());
                if (Math.abs(d - r) < 1.4 && Math.abs(p.getLocation().getY() - mark.getY()) < 3) {
                    hit(p, c("moves.ice-spikes", 11), mark.toVector(), Guard.UNBLOCKABLE);
                    Safe.vel(p, new Vector(0, 1.0, 0));
                }
            }
        }
        if (t > 60) { landed = false; end(); }
    }

    void spike(Location l) {
        BlockDisplay d = world.spawn(l, BlockDisplay.class, e -> {
            e.setBlock(Material.PACKED_ICE.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.4f, -2f, -0.4f), new Quaternionf().rotateY(random.nextFloat()), new Vector3f(0.8f, 2.2f, 0.8f), new Quaternionf()));
            e.setInterpolationDuration(4);
            e.setPersistent(false);
            e.addScoreboardTag(Wild.FX_TAG);
        });
        ice.add(d);
        int[] t = {0};
        fx.add(() -> {
            t[0]++;
            if (t[0] == 2) { d.setInterpolationDelay(0); Transformation tr = d.getTransformation(); d.setTransformation(new Transformation(new Vector3f(-0.4f, 0, -0.4f), tr.getLeftRotation(), tr.getScale(), new Quaternionf())); }
            if (t[0] == 40) { d.setInterpolationDelay(0); Transformation tr = d.getTransformation(); d.setTransformation(new Transformation(new Vector3f(-0.4f, -2.4f, -0.4f), tr.getLeftRotation(), tr.getScale(), new Quaternionf())); }
            if (t[0] < 46) return false;
            d.remove();
            return true;
        });
        world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.5, 0), 6, 0.3, 0.3, 0.3, 0, Material.PACKED_ICE.createBlockData());
    }

    void archers(List<Player> a) {
        if (at == 10) {
            int n = 1 + Math.min(3, a.size());
            for (int i = 0; i < n; i++) {
                Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                Location l = (t != null ? t.getLocation() : pos).clone().add(random.nextGaussian() * 8, 0, random.nextGaussian() * 8);
                l.setY(ground(l));
                Stray s = minion(l, EntityType.STRAY, Stray.class, ChatColor.AQUA + "Wyrmguard Archer", 26, m -> {
                    m.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                    m.getEquipment().setItemInMainHandDropChance(0);
                    m.setShouldBurnInDay(false);
                    attr(m, Attribute.FOLLOW_RANGE, 40);
                });
                if (t != null) s.setTarget(t);
                world.spawnParticle(Particle.SNOWFLAKE, l.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.05);
            }
        }
        if (at > 20) end();
    }

    @Override
    void deathAnim(int t) {
        deathLimp = true;
        if (t % 2 == 0) yaw += 4; // spiralling down
        double gy = ground(pos);
        if (pos.getY() > gy + 1.2 * k) pos = pos.clone().add(dirOf(yaw).multiply(0.2)).add(0, -0.35, 0);
        pitch = Math.min(40, pitch + 1.5f);
        flap += 0.05;
        place();
        if (t % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos, 30, 2, 1, 2, 0.1);
        if (t % 15 == 0) world.playSound(pos, Sound.ENTITY_ENDER_DRAGON_HURT, SoundCategory.HOSTILE, 3f, 1.3f);
    }

    @Override
    void removeEverything() {
        quietly(() -> { for (BlockDisplay d : ice) if (d.isValid()) d.remove(); ice.clear(); });
        super.removeEverything();
    }

    @Override void drops(Player p, Location at) { maybeDrop(p, at, "glacial_fang", c("rewards.fang-chance", 0.25)); }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(BREATH, "Frost Breath", Material.POWDER_SNOW_BUCKET));
        m.add(new MoveSlot(ICICLES, "Icicle Rain", Material.POINTED_DRIPSTONE));
        m.add(new MoveSlot(DIVE, "Dive Bomb", Material.PHANTOM_MEMBRANE));
        m.add(new MoveSlot(ROAR, "Glacial Roar", Material.PACKED_ICE));
        if (phase >= 2) m.add(new MoveSlot(ARCHERS, "Frost Archers", Material.STRAY_SPAWN_EGG));
        return m;
    }
}
