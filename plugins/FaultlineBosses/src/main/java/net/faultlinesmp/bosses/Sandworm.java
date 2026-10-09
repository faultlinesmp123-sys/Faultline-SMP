package net.faultlinesmp.bosses;

import org.bukkit.entity.EntityType;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE SANDWORM KING: a colossal worm, 3,600 health, that swims through the sand of deserts and badlands. Beat a
 * Sandworm Drum on the sand to call it. It porpoises through the ground (sand sprays where it breaks the surface).
 *   Phase 1: Eruption (a ring under you, then it bursts up there, arcs over and dives back down), Slither (it rides the
 *            surface toward you), Sandstone Boulders (spat up at marked spots), Quicksand (slows you and drags you in).
 *   Phase 2 (60%): + Brood (Sandworm Larvae burrow out) and faster.
 *   Phase 3 (30%): + Tremor (rings race out along the ground: be in the air when one passes).
 *   1.5.0: + Sandblast (it rears up out of the dunes and sweeps a blinding cone of sand across you) from phase 1,
 *          + Devour (a shrinking ring under you, then its maw bursts straight up: caught = swallowed for a few seconds,
 *            then spat out; hit it hard to make it let go) from phase 2.
 */
final class Sandworm extends Wild.Boss {

    static final int ERUPT = 1, SLITHER = 2, BOULDERS = 3, QUICKSAND = 4, BROOD = 5, TREMOR = 6, SANDBLAST = 7, DEVOUR = 8;

    final double k, gap;
    final int segs;
    final double[] gaps;
    final Wild.Part head, tail;
    final List<Wild.Part> body = new ArrayList<>();
    final Wild.Chain chain;
    Location headPos;
    float yaw;
    // the current move
    Location mark, arcFrom;
    Vector arcDir;
    final List<Location> marks = new ArrayList<>();
    final List<BlockDisplay> rocks = new ArrayList<>();
    final Set<UUID> hitThisMove = new HashSet<>();
    double ringR;

    Sandworm(Wild w, Location at, Player by) {
        super(w, "sandworm", at, by);
        k = c("scale", 1.5);
        gap = 2.4 * k;
        segs = (int) Math.max(4, c("segments", 12));
        gaps = new double[segs + 1];
        Arrays.fill(gaps, gap);
        Vector back = by != null ? dirOf(by.getLocation().getYaw()).multiply(-1) : new Vector(1, 0, 0);
        headPos = at.clone().add(dirOf(at.getYaw()).multiply(12));
        headPos.setY(ground(headPos) - 6);
        yaw = yawOf(back.clone().multiply(-1));
        chain = new Wild.Chain(headPos.clone().add(0, -2, 0), segs + 1, gap, back.clone().add(new Vector(0, -0.6, 0)));
        head = rig.add("worm_head", k, headPos);
        for (int i = 0; i < segs; i++) body.add(rig.add("worm_body", k * (1 - 0.025 * i), headPos));
        tail = rig.add("worm_tail", k * 0.8, headPos);
        hitbox(headPos, (int) Math.round(3.4 * k), "The Sandworm King");
        for (int i = 2; i < segs; i += 3) hitbox(headPos, (int) Math.max(2, Math.round(3 * k)), "The Sandworm King");
        world.playSound(at, Sound.ENTITY_WARDEN_DIG, SoundCategory.HOSTILE, 4f, 0.5f);
        cd = 70;
        // it opens with an eruption at whoever called it
        attack = ERUPT; at_(by);
    }

    private void at_(Player by) { focus = by; at = 0; startMove(ERUPT, by); }

    @Override double defHealth() { return 900; } // a mini boss, like the Stone Golem
    @Override BarColor barColor() { return BarColor.YELLOW; }
    @Override String music() { return "minecraft:music_disc.5"; }
    @Override double musicLength() { return 178; }
    @Override Location center() { return headPos.clone(); }
    @Override boolean valid() { return head.d != null && head.d.isValid(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.GOLD + "" + ChatColor.BOLD + "The dunes tremble... the Sandworm King has heard the drum.";
            case "phase2" -> ChatColor.GOLD + "The sand boils with its brood!";
            case "phase3" -> ChatColor.RED + "The Sandworm King shakes the whole desert!";
            case "death" -> ChatColor.GOLD + "The Sandworm King crumbles into the dunes.";
            case "leave" -> ChatColor.GOLD + "The Sandworm King " + ChatColor.GRAY + "sinks deep beneath the sand.";
            default -> null;
        };
    }

    double groundAt(Location l) { return ground(l); }

    // =====================================================================================================
    //  moving through the sand
    // =====================================================================================================
    void steer(Location to, double speed, float turnMax) {
        Vector d = to.toVector().subtract(headPos.toVector());
        yaw = turn(yaw, yawOf(flat(d)), turnMax);
        Location n = headPos.clone().add(dirOf(yaw).multiply(speed));
        double dy = Math.max(-0.45, Math.min(0.45, to.getY() - headPos.getY()));
        n.add(0, dy, 0);
        headPos = n;
    }

    @Override
    void bodyTick(List<Player> a) {
        if (attack < 0 || attack == BOULDERS || attack == QUICKSAND || attack == BROOD || attack == TREMOR) {
            Location c = pilot != null ? pilot.getLocation() : focus != null && focus.isOnline() ? focus.getLocation() : (a.isEmpty() ? home : a.get(0).getLocation());
            double orbitR = attack == BOULDERS || attack == TREMOR ? 4 : 11;
            double ang = ticks * 0.035;
            Location g = c.clone().add(Math.cos(ang) * orbitR, 0, Math.sin(ang) * orbitR);
            double gy = groundAt(g);
            // porpoising: the head arcs out of the sand and back under
            g.setY(gy - 2.2 + Math.sin(ticks * 0.08) * 3.6);
            if (attack == BOULDERS || attack == TREMOR) g.setY(gy + 1.6);
            steer(g, c("speed", 0.38) * (phase >= 2 ? 1.2 : 1), 7);
        }
        double wantShake = attack == TREMOR ? 10 : attack == ERUPT && at > (phase == 3 ? 26 : 36) && at < (phase == 3 ? 46 : 56) ? 7
                : attack == DEVOUR && victim != null ? 9 : attack == SANDBLAST && at > 18 ? 3 : 0;
        double wantLift = attack == BOULDERS && at < 20 ? 1.2 : attack == BROOD ? 0.6 : 0;
        rageShake += (wantShake - rageShake) * 0.2;
        headLift += (wantLift - headLift) * 0.15;
        place();
        // sand where it breaks the surface
        if (ticks % 2 == 0) for (int i = 0; i < chain.pts.size(); i += 2) {
            Location p = chain.pts.get(i);
            double gy = groundAt(p);
            if (Math.abs(p.getY() - gy) < 1.6 * k) {
                BlockData sand = world.getBlockAt(p.getBlockX(), (int) gy - 1, p.getBlockZ()).getBlockData();
                world.spawnParticle(Particle.BLOCK, p.getX(), gy + 0.2, p.getZ(), 10, 1.2, 0.2, 1.2, 0, sand.getMaterial().isSolid() ? sand : Material.SAND.createBlockData());
            }
        }
        if (ticks % 40 == 0) world.playSound(headPos, Sound.BLOCK_SAND_BREAK, SoundCategory.HOSTILE, 3f, 0.4f);
    }

    double rageShake, headLift, aHeadPitch;
    // Devour: who it swallowed, for how long, and how hard they've hit it since
    Player victim;
    int held;
    double struggle;
    int sinkFrom = -1; // dying: segments from this index on have gone under

    void place() {
        chain.lead(headPos, gaps);
        // What's drawn rides on the chain: a sideways slither running down the body, a ripple of the armour rings
        // (each segment swells and eases in turn) and a slow twist. The chain and hitboxes stay where they are.
        int n = chain.pts.size();
        List<Location> shown = new ArrayList<>(n);
        double travel = ticks * (attack == SLITHER ? 0.45 : 0.22);
        for (int i = 0; i < n; i++) {
            Location p = chain.pts.get(i).clone();
            if (i > 0) {
                Vector f = flat(chain.facing(i));
                Vector side = new Vector(-f.getZ(), 0, f.getX());
                double amp = (dying ? 0.15 : 0.45) * k * Math.min(1, i / 3.0);
                p.add(side.multiply(Math.sin(travel - i * 0.75) * amp));
            }
            if (sinkFrom >= 0 && i >= sinkFrom) p.add(0, -3.5 * k, 0);
            shown.add(p);
        }
        Vector hf = shown.get(0).toVector().subtract(shown.get(1).toVector());
        float hp = pitchOf(hf.lengthSquared() < 1e-4 ? dirOf(yaw) : hf);
        double fl = flinchAmt();
        float headRoll = (float) (Math.sin(ticks * 1.7) * rageShake + Math.sin(ticks * 0.11) * 6 + fl * 12);
        Location hpPos = shown.get(0).clone().add(0, headLift, 0);
        if (attack == SANDBLAST && at > 12) hp = 35;                 // reared up, maw aimed down at you
        if (attack == DEVOUR && at > 30) hp = victim != null ? -75 + (float) Math.sin(ticks * 1.1) * 8 : -80;   // straight up, gulping
        aHeadPitch += (hp - headLift * 12 - fl * 10 - aHeadPitch) * 0.35;
        head.pose(hpPos, yaw, (float) Math.max(-85, Math.min(80, aHeadPitch)), headRoll, null);
        for (int i = 0; i < segs; i++) {
            Vector sf = shown.get(i).toVector().subtract(shown.get(i + 1).toVector());
            Wild.Part b = body.get(i);
            b.k = k * (1 - 0.025 * i) * (1 + 0.07 * Math.sin(ticks * 0.3 - i * 0.9));
            b.pose(shown.get(i + 1), yawOf(flat(sf)), Math.max(-80, Math.min(80, pitchOf(sf))), (float) (Math.sin(travel * 0.6 - i * 0.5) * 10), null);
        }
        Vector tf = shown.get(segs).toVector().subtract(shown.get(segs + 1).toVector());
        tail.pose(shown.get(segs + 1), yawOf(flat(tf)), Math.max(-80, Math.min(80, pitchOf(tf))), (float) (Math.sin(ticks * 0.25) * 25), null);
        if (!hitboxes.isEmpty()) hitboxes.get(0).teleport(headPos.clone().add(0, -1.6 * k, 0));
        for (int h = 1, i = 3; h < hitboxes.size() && i < chain.pts.size(); h++, i += 3)
            hitboxes.get(h).teleport(chain.pts.get(i).clone().add(0, -1.4 * k, 0));
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(ERUPT, ERUPT, SLITHER, BOULDERS, QUICKSAND, SANDBLAST));
        if (phase >= 2 && minions.size() < 6) pool.add(BROOD);
        if (phase >= 2) { pool.add(DEVOUR); pool.add(DEVOUR); }
        if (phase == 3) { pool.add(TREMOR); pool.add(ERUPT); }
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        marks.clear(); hitThisMove.clear(); ringR = 0;
        Location tl = t != null ? t.getLocation() : headPos.clone().add(dirOf(yaw).multiply(8));
        switch (move) {
            case ERUPT -> {
                mark = tl.clone();
                mark.setY(groundAt(mark));
                world.playSound(mark, Sound.BLOCK_SAND_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            case SLITHER -> {
                arcDir = flat(tl.toVector().subtract(headPos.toVector()));
                world.playSound(headPos, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
            }
            case BOULDERS -> {
                for (Player p : active()) marks.add(p.getLocation().clone().add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5));
                for (int i = 0; i < 2 + phase; i++) marks.add(tl.clone().add(random.nextGaussian() * 7, 0, random.nextGaussian() * 7));
                for (Location m : marks) m.setY(groundAt(m));
            }
            case QUICKSAND -> { mark = tl.clone(); mark.setY(groundAt(mark)); world.playSound(mark, Sound.BLOCK_SAND_STEP, SoundCategory.HOSTILE, 3f, 0.4f); }
            case BROOD -> world.playSound(headPos, Sound.ENTITY_SILVERFISH_AMBIENT, SoundCategory.HOSTILE, 3f, 0.5f);
            case TREMOR -> { mark = headPos.clone(); mark.setY(groundAt(mark)); world.playSound(mark, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.6f); }
            case SANDBLAST -> {
                mark = tl.clone();
                // it comes up 9 blocks from the target, on the side it's already on
                arcFrom = tl.clone().add(flat(headPos.toVector().subtract(tl.toVector())).multiply(9));
                arcFrom.setY(groundAt(arcFrom));
                arcDir = flat(tl.toVector().subtract(arcFrom.toVector()));
                world.playSound(headPos, Sound.BLOCK_SAND_BREAK, SoundCategory.HOSTILE, 3f, 0.3f);
            }
            case DEVOUR -> {
                mark = tl.clone(); mark.setY(groundAt(mark));
                victim = null; held = 0; struggle = 0;
                world.playSound(mark, Sound.ENTITY_WARDEN_DIG, SoundCategory.HOSTILE, 3f, 0.6f);
            }
            default -> { }
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case ERUPT -> erupt();
            case SLITHER -> slither();
            case BOULDERS -> boulders();
            case QUICKSAND -> quicksand(a);
            case BROOD -> brood(a);
            case TREMOR -> tremor(a);
            case SANDBLAST -> sandblast(a);
            case DEVOUR -> devour(a);
            default -> end();
        }
    }

    /** A ring under the target while it tunnels there, then it bursts out, arcs over and dives back in. */
    void erupt() {
        int wind = phase == 3 ? 26 : 36;
        if (at <= wind) {
            Location under = mark.clone().add(0, -7 * k, 0);
            steer(under, 0.9, 20);
            if (at % 3 == 0) { warnRing(mark, 3.5, Color.fromRGB(230, 170, 60)); world.spawnParticle(Particle.FALLING_DUST, mark.clone().add(0, 0.3, 0), 12, 1.5, 0.1, 1.5, 0, Material.SAND.createBlockData()); }
            if (at == wind) { arcFrom = headPos.clone(); arcDir = flat(dirOf(yaw)); }
            return;
        }
        int t = at - wind, dur = 40;
        double f = Math.min(1, t / (double) dur);
        double up = 10 * k;
        Location p = mark.clone().add(arcDir.clone().multiply(-6 + 14 * f));
        p.setY(mark.getY() - 6 * k + (up + 6 * k) * Math.sin(Math.PI * f) * 1.0 + (f > 0.5 ? -6 * k * (f - 0.5) : 0));
        Vector dir = p.toVector().subtract(headPos.toVector());
        if (dir.lengthSquared() > 1e-3) yaw = yawOf(flat(dir));
        headPos = p;
        if (t == 3) {
            world.spawnParticle(Particle.BLOCK, mark.clone().add(0, 0.5, 0), 160, 2.5, 1, 2.5, 0.2, Material.SAND.createBlockData());
            world.spawnParticle(Particle.EXPLOSION, mark, 4, 1.5, 0.5, 1.5, 0);
            world.playSound(mark, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.5f);
            for (Player pp : near(mark, 3.8, 6)) { hit(pp, c("moves.eruption", 14), mark.toVector(), Guard.HEAVY); Safe.vel(pp, new Vector(0, 1.3, 0)); }
        }
        for (Player pp : near(headPos.clone().add(0, -2, 0), 2.6 * k, 5)) if (hitThisMove.add(pp.getUniqueId())) hit(pp, c("moves.eruption-body", 9), headPos.toVector(), Guard.BLOCKABLE);
        if (t >= dur) end();
    }

    /** Rides the surface toward the target, everything it passes over is hit. */
    void slither() {
        if (at < 14) { if (at % 2 == 0) warnLine(headPos, arcDir, 26, Color.fromRGB(230, 120, 40)); return; }
        Location n = headPos.clone().add(arcDir.clone().multiply(c("slither-speed", 0.75)));
        n.setY(groundAt(n) + 1.2 * k);
        yaw = yawOf(arcDir);
        headPos = n;
        for (int i = 0; i < chain.pts.size(); i += 2) for (Player p : near(chain.pts.get(i).clone().add(0, -1.5, 0), 2.4 * k, 4))
            if (hitThisMove.add(p.getUniqueId())) { hit(p, c("moves.slither", 10), chain.pts.get(i).toVector(), Guard.BLOCKABLE); Safe.vel(p, flat(p.getLocation().toVector().subtract(n.toVector())).multiply(1.1).setY(0.5)); }
        if (at > 14 + 40) end();
    }

    /** Its head breaks the surface and it spits sandstone boulders at marked spots. */
    void boulders() {
        if (at < 16) { if (at % 3 == 0) for (Location m : marks) warnRing(m, 2.4, Color.ORANGE); return; }
        if (at == 16) {
            world.playSound(headPos, Sound.ENTITY_LLAMA_SPIT, SoundCategory.HOSTILE, 3f, 0.3f);
            for (Location m : marks) {
                Location from = headPos.clone().add(0, 1.5 * k, 0);
                BlockDisplay rock = world.spawn(from, BlockDisplay.class, d -> {
                    d.setBlock(Material.SANDSTONE.createBlockData());
                    d.setTransformation(new Transformation(new Vector3f(-0.6f, -0.6f, -0.6f), new Quaternionf(), new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
                    d.setTeleportDuration(1);
                    d.setPersistent(false);
                    d.addScoreboardTag(Wild.FX_TAG);
                });
                rocks.add(rock);
                int[] t = {0};
                fx.add(() -> {
                    t[0]++;
                    double f = t[0] / 22.0;
                    Location p = from.clone().add(m.toVector().subtract(from.toVector()).multiply(f));
                    p.add(0, Math.sin(Math.PI * f) * 8, 0);
                    rock.teleport(p);
                    if (f < 1) return false;
                    rock.remove();
                    world.spawnParticle(Particle.BLOCK, m, 40, 1, 0.4, 1, 0, Material.SANDSTONE.createBlockData());
                    world.playSound(m, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
                    for (Player pp : near(m, 2.6, 4)) hit(pp, c("moves.boulder", 11), m.toVector().add(new Vector(0, 3, 0)), Guard.HEAVY);
                    return true;
                });
            }
        }
        if (at > 46) end();
    }

    /** A pit that slows you and drags you to its middle. */
    void quicksand(List<Player> a) {
        double r = c("quicksand-radius", 6);
        if (at % 2 == 0) for (int i = 0; i < 16; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * r;
            world.spawnParticle(Particle.FALLING_DUST, mark.getX() + Math.cos(ang) * d, mark.getY() + 0.2, mark.getZ() + Math.sin(ang) * d, 1, 0, 0, 0, 0, Material.SAND.createBlockData());
        }
        if (at % 5 == 0) for (Player p : near(mark, r, 3)) {
            Vector to = mark.toVector().subtract(p.getLocation().toVector()).setY(0);
            double d = to.length();
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 2));
            if (d > 0.5) Safe.vel(p, p.getVelocity().add(to.normalize().multiply(0.18)));
            if (at % 20 == 0) hit(p, d < 1.6 ? c("moves.quicksand-center", 6) : c("moves.quicksand", 1.5), mark.toVector(), Guard.UNBLOCKABLE);
        }
        if (at % 20 == 0) warnRing(mark, r, Color.fromRGB(200, 160, 80));
        if (at > 110) end();
    }

    void brood(List<Player> a) {
        if (at == 12) {
            int n = 2 + Math.min(4, a.size());
            for (int i = 0; i < n; i++) {
                Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                Location l = (t != null ? t.getLocation() : headPos).clone().add(random.nextGaussian() * 5, 0, random.nextGaussian() * 5);
                l.setY(groundAt(l));
                Silverfish s = minion(l, EntityType.SILVERFISH, Silverfish.class, ChatColor.GOLD + "Sandworm Larva", 24, m -> {
                    attr(m, Attribute.SCALE, 2.4);
                    attr(m, Attribute.ATTACK_DAMAGE, 5);
                    attr(m, Attribute.MOVEMENT_SPEED, 0.3);
                    m.addScoreboardTag("faultline_sandworm_larva");
                });
                if (t != null) s.setTarget(t);
                world.spawnParticle(Particle.BLOCK, l, 30, 0.6, 0.4, 0.6, 0, Material.SAND.createBlockData());
            }
        }
        if (at > 24) end();
    }

    /** Rings race out along the ground; anyone standing when one passes is thrown. */
    void tremor(List<Player> a) {
        if (at < 14) return;
        ringR += 0.55;
        warnRing(mark, ringR, Color.fromRGB(220, 140, 40));
        if (at % 2 == 0) {
            for (int i = 0; i < 24; i++) {
                double ang = i * Math.PI / 12;
                world.spawnParticle(Particle.BLOCK, mark.getX() + Math.cos(ang) * ringR, mark.getY() + 0.3, mark.getZ() + Math.sin(ang) * ringR, 2, 0.1, 0.2, 0.1, 0, Material.SAND.createBlockData());
            }
        }
        for (Player p : a) {
            double d = Math.hypot(p.getLocation().getX() - mark.getX(), p.getLocation().getZ() - mark.getZ());
            @SuppressWarnings("deprecation") boolean onGround = p.isOnGround();
            if (Math.abs(d - ringR) < 0.8 && onGround && Math.abs(p.getLocation().getY() - mark.getY()) < 2.5 && hitThisMove.add(p.getUniqueId())) {
                hit(p, c("moves.tremor", 9), mark.toVector(), Guard.UNBLOCKABLE);
                Safe.vel(p, new Vector(0, 0.9, 0));
            }
        }
        if (ringR > 22) end();
    }


    /** It rears up out of the dunes and sweeps a cone of blasting sand across the target. */
    void sandblast(List<Player> a) {
        int rise = 18, dur = 46;
        if (at <= rise) { // tunnel there and rear up
            Location up = arcFrom.clone().add(0, (at < 10 ? -3 : 3.2) * k, 0);
            steer(up, 0.9, 25);
            if (at % 3 == 0) world.spawnParticle(Particle.BLOCK, arcFrom.clone().add(0, 0.4, 0), 25, 1.5, 0.3, 1.5, 0.1, Material.SAND.createBlockData());
            return;
        }
        int t = at - rise;
        double sweep = -45 + 90 * Math.min(1, t / (double) dur);  // from one side of you to the other
        yaw = yawOf(arcDir) + (float) sweep;
        headPos = arcFrom.clone().add(0, 3.2 * k + Math.sin(ticks * 0.6) * 0.15, 0);
        Vector dir = dirOf(yaw).add(new Vector(0, -0.35, 0)).normalize();
        Location mouth = headPos.clone().add(dirOf(yaw).multiply(1.8 * k));
        for (int i = 0; i < 8; i++) {
            Vector d = dir.clone().add(new Vector(random.nextGaussian() * 0.16, random.nextGaussian() * 0.1, random.nextGaussian() * 0.16)).normalize();
            world.spawnParticle(Particle.FALLING_DUST, mouth.clone().add(d.clone().multiply(random.nextDouble() * 12)), 1, 0.2, 0.2, 0.2, 0, Material.SAND.createBlockData());
            world.spawnParticle(Particle.CLOUD, mouth, 0, d.getX(), d.getY(), d.getZ(), 0.6);
        }
        if (t % 6 == 0) world.playSound(mouth, Sound.BLOCK_SAND_FALL, SoundCategory.HOSTILE, 3f, 0.4f);
        if (t % 4 == 0) for (Player p : a) {
            Vector to = p.getEyeLocation().toVector().subtract(mouth.toVector());
            double d = to.length();
            if (d > c("sandblast-range", 16) || d < 0.1) continue;
            Vector fl = flat(to);
            if (fl.dot(dirOf(yaw)) < Math.cos(Math.toRadians(20))) continue;
            hit(p, c("moves.sandblast", 3), mouth.toVector(), Guard.BLOCKABLE);
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
            Safe.vel(p, p.getVelocity().add(fl.multiply(0.3)));
        }
        if (t >= dur) end();
    }

    /** A shrinking ring under the target, then the maw bursts straight up: whoever's still in it is swallowed. */
    void devour(List<Player> a) {
        int wind = phase == 3 ? 24 : 30;
        if (at <= wind) {
            steer(mark.clone().add(0, -7 * k, 0), 0.9, 25);
            double r = 3.5 - 2.0 * at / wind;
            if (at % 2 == 0) { warnRing(mark, r, Color.fromRGB(200, 60, 40)); world.spawnParticle(Particle.FALLING_DUST, mark.clone().add(0, 0.3, 0), 10, r * 0.5, 0.1, r * 0.5, 0, Material.SAND.createBlockData()); }
            if (at == wind) { headPos = mark.clone().add(0, -6 * k, 0); yaw = yawOf(dirOf(yaw)); }
            return;
        }
        int t = at - wind;
        int hold = (int) c("devour-hold-ticks", 50);
        if (t <= 8) { // the burst, straight up
            headPos = mark.clone().add(0, -6 * k + (13 * k) * t / 8.0, 0);
            if (t == 1) {
                world.spawnParticle(Particle.BLOCK, mark.clone().add(0, 0.5, 0), 200, 2, 1, 2, 0.3, Material.SAND.createBlockData());
                world.playSound(mark, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.4f);
                for (Player p : near(mark, 1.8, 4)) {
                    if (victim == null && survival(p)) {
                        victim = p;
                        if (p.isInsideVehicle()) p.leaveVehicle();
                        p.sendTitle(ChatColor.GOLD + "Swallowed!", ChatColor.GRAY + "Hit it hard to make it let go", 0, 30, 10);
                        world.playSound(mark, Sound.ENTITY_GENERIC_EAT, SoundCategory.HOSTILE, 3f, 0.4f);
                    } else hit(p, c("moves.devour-burst", 12), mark.toVector(), Guard.HEAVY);
                }
            }
        }
        if (victim != null) {
            boolean gone = !victim.isOnline() || victim.isDead() || !survival(victim) || !victim.getWorld().equals(world);
            if (!gone) {
                held++;
                Location in = headPos.clone().add(0, -0.3, 0);
                in.setYaw(victim.getLocation().getYaw()); in.setPitch(victim.getLocation().getPitch());
                victim.teleport(in);
                victim.setFallDistance(0);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false));
                if (held % 10 == 0) { hit(victim, c("moves.devour", 3), headPos.toVector(), Guard.UNBLOCKABLE); world.playSound(headPos, Sound.ENTITY_GENERIC_EAT, SoundCategory.HOSTILE, 2f, 0.5f); }
                if (held % 4 == 0) world.spawnParticle(Particle.BLOCK, headPos, 12, 0.8, 0.8, 0.8, 0, Material.SAND.createBlockData());
            }
            boolean choke = struggle >= maxHp * c("devour-break", 0.04);
            if (gone || held >= hold || choke) {
                if (!gone) { // spat out
                    Vector out = dirOf(yaw + 180 * random.nextFloat()).multiply(1.1).setY(0.9);
                    victim.teleport(headPos.clone().add(out.clone().setY(0).normalize().multiply(2 * k)).add(0, 0.5, 0));
                    Safe.vel(victim, out);
                    victim.setFallDistance(0);
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
                    world.playSound(headPos, Sound.ENTITY_LLAMA_SPIT, SoundCategory.HOSTILE, 3f, 0.3f);
                    if (choke) say(ChatColor.GOLD + "It chokes and spits " + victim.getName() + " out!");
                }
                victim = null;
                held = Integer.MAX_VALUE / 2; // done holding: it sinks back from here
                t = Math.max(t, 9);
                at = wind + Math.max(t, 9);
                sinkAt = at;
            }
            return;
        }
        if (t > 8) { // nobody (or no longer anybody) in its mouth: back under
            if (sinkAt < 0) sinkAt = at;
            headPos = headPos.clone().add(0, -0.45, 0);
            if (at - sinkAt > 22) { sinkAt = -1; end(); }
        }
    }

    int sinkAt = -1;

    @Override
    double damageTaken(double amount) {
        if (attack == DEVOUR && victim != null) struggle += amount;
        return amount;
    }

    @Override
    void deathAnim(int t) {
        if (victim != null) { // it dies with someone in its mouth: let them down gently
            victim.setFallDistance(0);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0));
            victim = null;
        }
        // it rears up, thrashing, then goes under from the tail forward, ring by ring
        headPos = headPos.clone().add(0, t < 30 ? 0.2 : -0.35, 0);
        rageShake = t < 30 ? 12 : 4;
        if (t >= 30) sinkFrom = Math.max(1, chain.pts.size() - 1 - (t - 30) / 3);
        place();
        if (t % 3 == 0) world.spawnParticle(Particle.BLOCK, headPos, 30, 2, 1, 2, 0.1, Material.SAND.createBlockData());
        if (t % 12 == 0) world.playSound(headPos, Sound.ENTITY_RAVAGER_DEATH, SoundCategory.HOSTILE, 3f, 0.4f);
    }

    @Override
    void removeEverything() {
        quietly(() -> { for (BlockDisplay r : rocks) if (r.isValid()) r.remove(); rocks.clear(); });
        quietly(() -> { if (victim != null) { victim.setFallDistance(0); victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0)); victim = null; } });
        super.removeEverything();
    }

    @Override void drops(Player p, Location at) { maybeDrop(p, at, "sandworm_fang", c("rewards.fang-chance", 0.25)); }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(ERUPT, "Eruption", Material.SAND));
        m.add(new MoveSlot(SLITHER, "Slither", Material.SANDSTONE));
        m.add(new MoveSlot(BOULDERS, "Sandstone Boulders", Material.CHISELED_SANDSTONE));
        m.add(new MoveSlot(QUICKSAND, "Quicksand", Material.SOUL_SAND));
        if (phase >= 2) m.add(new MoveSlot(BROOD, "Brood", Material.SILVERFISH_SPAWN_EGG));
        m.add(new MoveSlot(SANDBLAST, "Sandblast", Material.SUSPICIOUS_SAND));
        if (phase >= 2) m.add(new MoveSlot(DEVOUR, "Devour", Material.ROTTEN_FLESH));
        if (phase == 3) m.add(new MoveSlot(TREMOR, "Tremor", Material.CRACKED_STONE_BRICKS));
        return m;
    }

}
