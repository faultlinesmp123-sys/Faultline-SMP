package net.faultlinesmp.bosses;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE STONE GOLEM (2.0 overhaul): an ancient guardian of the caves, a mini boss (900 health, +25% per extra fighter). It
 * wakes by itself when someone explores a cave (or /stonegolem summon).
 *
 * The body is a real jointed rig (tools/wild_assets.py golem(): body, head, a hinged jaw, upper arms, forearms with the
 * fists, thighs, shins): hips, waist twist, neck, jaw, shoulders, ELBOWS and KNEES. Its legs are solved so the feet stay
 * under it when it crouches; every joint follows its target on its own spring (heavy arms lag and swing through, the
 * legs are planted, the head turns last), so nothing snaps.
 *
 * Moves: Punch, Backhand Sweep, Ground Pound (an expanding shockwave: jump it), Tremor Stomps, Boulder Toss (it rips the
 * rock out of the floor; the rock splits on impact), Crystal Spikes (one line, three from phase 2), Rampage (a charge
 * that stuns it if it rams a wall: hit it then), Grab (lifts you in its fist, roars, slams you; hit it hard to break
 * free), Cave-In (a roar that brings stalactites down on everyone), Overgrowth (phase 2: heals until you break the moss)
 * and Core Pulse (phase 3: three amber shockwaves). Tuned so a player in full Protection II diamond can beat it: no
 * move hits for more than ~10 before armour.
 */
final class StoneGolem extends Wild.Boss {

    static final int POUND = 1, BOULDER = 2, SPIKES = 3, OVERGROWTH = 4, SWEEP = 5, CHARGE = 6, STOMP = 7, GRAB = 8,
            CAVEIN = 9, CORE = 10, PUNCH = 11;

    // ---- the joints (degrees; drop / lift in blocks at scale 1)
    static final int DROP = 0, PITCH = 1, ROLL = 2, TWIST = 3, AL = 4, AR = 5, SL = 6, SR = 7, EL = 8, ER = 9, HYAW = 10,
            HPITCH = 11, JAW = 12, TL = 13, TR = 14, KL = 15, KR = 16, STANCE = 17, N = 18;
    /** Spring stiffness / damping per joint: a planted heavy body, arms that lag and swing through, a head that turns last. */
    static final double[] K = {0.30, 0.22, 0.20, 0.18, 0.16, 0.16, 0.18, 0.18, 0.20, 0.20, 0.12, 0.13, 0.30, 0.34, 0.34, 0.34, 0.34, 0.25};
    static final double[] D = {0.50, 0.46, 0.50, 0.44, 0.38, 0.38, 0.42, 0.42, 0.40, 0.40, 0.50, 0.50, 0.45, 0.52, 0.52, 0.52, 0.52, 0.5};
    final double[] cur = new double[N], vel = new double[N], tgt = new double[N];
    double snap = 1;

    final double k;
    final IronGolem mover;
    final Wild.Part bodyP, headP, jawP, armL, armR, foreL, foreR, legL, legR, shinL, shinR;
    double walk, healLeft, staggerDamage, idleBeatAt, breakFree;
    int idleBeat = -1, idleT, lastMove, stun;
    boolean punchRight, wallHit;
    final List<BlockDisplay> rocks = new ArrayList<>();
    BlockDisplay heldRock;
    Location mark, fistR, fistL, footL, footR;
    Vector lineDir;
    Player grabbed;
    final Set<UUID> hitThisMove = new HashSet<>();
    double ringR;

    StoneGolem(Wild w, Location at, Player by) {
        super(w, "golem", at, by);
        k = c("scale", 1.0);
        mover = Wild.spawnAs(world, at.clone(), EntityType.IRON_GOLEM, IronGolem.class, g -> {
            g.setPlayerCreated(false);
            g.setPersistent(false);
            g.setRemoveWhenFarAway(false);
            g.setSilent(true);
            g.addScoreboardTag(Wild.HIT_TAG);
            g.addScoreboardTag("faultline_stone_golem");
            g.setCustomName(ChatColor.DARK_GREEN + "The Stone Golem");
            g.setCustomNameVisible(false);
            attr(g, Attribute.SCALE, 2.0 * k);
            attr(g, Attribute.MAX_HEALTH, 1000);
            g.setHealth(1000);
            attr(g, Attribute.MOVEMENT_SPEED, c("walk-speed", 0.23));
            attr(g, Attribute.ATTACK_DAMAGE, c("melee-damage", 6));
            attr(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            attr(g, Attribute.FOLLOW_RANGE, 40);
        });
        hideForGood(mover);
        hitboxes.add(mover);
        Location l = mover.getLocation();
        bodyP = rig.add("golem_body", k, l);
        headP = rig.add("golem_head", k, l);
        jawP = rig.add("golem_jaw", k, l);
        armL = rig.add("golem_arm", k, l);
        armR = rig.add("golem_arm", k, l);
        foreL = rig.add("golem_forearm", k, l);
        foreR = rig.add("golem_forearm", k, l);
        legL = rig.add("golem_leg", k, l);
        legR = rig.add("golem_leg", k, l);
        shinL = rig.add("golem_shin", k, l);
        shinR = rig.add("golem_shin", k, l);
        // it wakes up: buried to the waist, it pulls itself out of the floor
        cur[DROP] = 1.6; cur[PITCH] = 30; cur[AL] = cur[AR] = 60; cur[EL] = cur[ER] = 60; cur[HPITCH] = 30;
        world.playSound(l, Sound.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.HOSTILE, 3f, 0.4f);
        world.playSound(l, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 2f, 0.7f);
        world.spawnParticle(Particle.BLOCK, l.clone().add(0, 1, 0), 120, 1.6, 1.0, 1.6, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        cd = 50;
    }

    @Override double defHealth() { return 900; }
    @Override BarColor barColor() { return BarColor.GREEN; }
    @Override String music() { return "minecraft:music_disc.precipice"; }
    @Override double musicLength() { return 299; }
    @Override Location center() { return mover.getLocation().add(0, 2.5 * k, 0); }
    @Override boolean valid() { return mover.isValid() && !mover.isDead(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "The cave wall cracks open... an ancient Stone Golem wakes.";
            case "phase2" -> ChatColor.DARK_GREEN + "Moss creeps over the Stone Golem's wounds, and the cave shakes.";
            case "phase3" -> ChatColor.GOLD + "The Stone Golem's core blazes through its cracks!";
            case "death" -> ChatColor.DARK_GREEN + "The Stone Golem crumbles into rubble and moss.";
            case "leave" -> ChatColor.DARK_GREEN + "The Stone Golem " + ChatColor.GRAY + "sinks back into the cave wall.";
            default -> null;
        };
    }

    @Override
    void onPhase(int p) {
        world.playSound(center(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        if (p == 3) {
            attr(mover, Attribute.MOVEMENT_SPEED, c("walk-speed", 0.23) * c("phase3-speed", 1.2));
            world.spawnParticle(Particle.FLAME, center(), 60, 1.2, 1.5, 1.2, 0.05);
        }
        if (attack < 0) cd = Math.min(cd, 10);
    }

    // =====================================================================================================
    //  spawning in caves
    // =====================================================================================================
    /**
     * It wakes by itself near someone exploring a cave (2.0): lush caves roll golem.natural-chance-per-minute,
     * any other cave (12+ blocks of ground over your head) golem.cave-chance-per-minute.
     */
    static void naturalSpawns(Wild w) {
        double lush = w.c("golem.natural-chance-per-minute", 0.03), cave = w.c("golem.cave-chance-per-minute", 0.004);
        if (w.bosses.containsKey("golem") || (lush <= 0 && cave <= 0)) return;
        if (System.currentTimeMillis() < w.cooldownUntil.getOrDefault("golem", 0L)) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(online, w.random);
        for (Player p : online) {
            if (!survival(p) || p.isDead() || p.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) continue;
            Location l = p.getLocation();
            if (!cave(l)) continue;
            double chance = l.getBlock().getBiome() == Biome.LUSH_CAVES ? lush : cave;
            if (w.random.nextDouble() >= chance) continue;
            Location spot = openFloor(l, 9, 16);
            if (spot == null) continue;
            w.summon("golem", spot, p);
            return;
        }
    }

    /** Underground in a cave: an Overworld (not the Dark Ocean / void), 12+ blocks of ground overhead (3+ in a lush cave). */
    static boolean cave(Location l) {
        World w = l.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL || Wild.voidWorld(w)) return false;
        Block b = l.getBlock();
        if (b.isLiquid()) return false;
        int under = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) - l.getBlockY();   // ground over your head (a ravine floor = 0)
        if (under < 3 || (under < 12 && b.getBiome() != Biome.LUSH_CAVES)) return false;
        for (int dy = 1; dy <= 16; dy++) if (b.getRelative(0, dy, 0).getType().isSolid()) return true;   // a real cave ceiling (not open air under a floating island)
        return false;
    }

    /** Room for it (3x3, 5 tall, a floor) somewhere between min and max blocks from l, or null. */
    static Location openFloor(Location l, int min, int max) {
        World w = l.getWorld();
        Random r = new Random();
        for (int tries = 0; tries < 30; tries++) {
            double ang = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
            int x = (int) Math.floor(l.getX() + Math.cos(ang) * d), z = (int) Math.floor(l.getZ() + Math.sin(ang) * d);
            for (int y = l.getBlockY() + 4; y >= l.getBlockY() - 6; y--) {
                if (!w.getBlockAt(x, y - 1, z).getType().isSolid()) continue;
                boolean ok = true;
                for (int dx = -1; dx <= 1 && ok; dx++) for (int dz = -1; dz <= 1 && ok; dz++) for (int dy = 0; dy < 5 && ok; dy++) {
                    Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (b.getType().isSolid() || b.isLiquid()) ok = false;
                }
                if (ok) return new Location(w, x + 0.5, y, z + 0.5);
                break;
            }
        }
        return null;
    }

    // =====================================================================================================
    //  walking + the rig
    // =====================================================================================================
    boolean busy() { return attack >= 0 && attack != PUNCH || stun > 0; }

    @Override
    void bodyTick(List<Player> a) {
        boolean busy = busy();
        mover.setAware(!busy && pilot == null);
        if (stun > 0) stun--;
        if (pilot != null && !busy) {
            Location want = pilot.getLocation();
            if (want.toVector().subtract(mover.getLocation().toVector()).setY(0).lengthSquared() > 9) walkTo(want);
        } else if (!busy && ticks % 20 == 0) {
            Player t = target(a);
            if (t != null) mover.setTarget(t);
        }
        if (busy && attack != CHARGE && focus != null && focus.isOnline() && focus.getWorld().equals(world)
                && (attack != GRAB || grabbed == null)) turnToward(focus.getLocation(), attack == SWEEP || attack == POUND ? 2 : 4);
        Location l = mover.getLocation();
        Vector v = mover.getVelocity().setY(0);
        double moving = attack == CHARGE && at >= 18 ? 1 : busy ? 0 : Math.min(1, v.length() * 7);
        walk += attack == CHARGE && at >= 18 ? 0.42 : 0.06 + moving * 0.16;
        animate(l, moving);
        if (phase == 3 && ticks % 4 == 0) world.spawnParticle(Particle.DUST, center(), 2, 0.7, 0.8, 0.7, 0, new Particle.DustOptions(Color.ORANGE, 1.4f));
        if (phase == 3 && ticks % 9 == 0) world.spawnParticle(Particle.SMALL_FLAME, chest(), 3, 0.15, 0.25, 0.15, 0.01);
        if (moving > 0.3 && ticks % 2 == 0) footsteps(moving);
        heldRockTick();
        grabTick();
    }

    /** A footfall: dust, a thud and a little tremor for anyone close, on the beat of the walk cycle. */
    double lastStep;
    void footsteps(double moving) {
        double ph = Math.floor(walk / Math.PI);
        if (ph == lastStep) return;
        lastStep = ph;
        Location f = ((int) ph & 1) == 0 ? footL : footR;
        if (f == null) return;
        world.playSound(f, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 2.2f, 0.45f);
        world.playSound(f, Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.HOSTILE, 1.6f, 0.5f);
        world.spawnParticle(Particle.BLOCK, f, 10, 0.5, 0.05, 0.5, 0, Material.MOSS_BLOCK.createBlockData());
        world.spawnParticle(Particle.BLOCK, f, 6, 0.5, 0.05, 0.5, 0, Material.STONE.createBlockData());
    }

    void turnToward(Location t, double maxDeg) {
        Location l = mover.getLocation();
        float want = yawOf(flat(t.toVector().subtract(l.toVector())));
        double d = wrap(want - l.getYaw());
        float yaw = (float) (l.getYaw() + Math.max(-maxDeg, Math.min(maxDeg, d)));
        try { mover.setRotation(yaw, 0); } catch (RuntimeException e) { l.setYaw(yaw); mover.teleport(l); }
    }

    // =====================================================================================================
    //  animation: targets per move, then every joint chases its target on a spring
    // =====================================================================================================
    static double lerp(double a, double b, double t) { return a + (b - a) * Math.max(0, Math.min(1, t)); }
    /** 0 before s, 1 after e, eased in between. */
    static double ramp(int t, int s, int e) { double x = Math.max(0, Math.min(1, (t - s) / (double) Math.max(1, e - s))); return x * x * (3 - 2 * x); }

    void base(double moving) {
        double br = Math.sin(ticks * 0.045), br2 = Math.sin(ticks * 0.017 + 1.3);   // slow breathing, a slower sway
        double sw = Math.sin(walk), cs = Math.cos(walk);
        Arrays.fill(tgt, 0);
        tgt[DROP] = 0.03 * br + moving * (0.06 + 0.1 * (0.5 - 0.5 * Math.cos(2 * walk))) + 0.08;   // a slight crouch, lowest at each footfall
        tgt[PITCH] = 6 + br * 1.2 + moving * 6;                                     // hunched, leaning into the walk
        tgt[ROLL] = moving * 3.5 * sw + br2 * 0.8;
        tgt[TWIST] = -moving * 7 * sw;                                              // shoulders turn against the hips
        tgt[AL] = -moving * 18 * Math.sin(walk - 0.45) + br * 2;                    // arms swing opposite the legs, a beat late
        tgt[AR] = moving * 18 * Math.sin(walk - 0.45) - br * 2;
        tgt[SL] = 8 + br * 1.5; tgt[SR] = 8 + br * 1.5;
        tgt[EL] = 18 + moving * 10 * Math.max(0, Math.sin(walk - 0.45)) + br * 2;  // elbows always a little bent (it's heavy)
        tgt[ER] = 18 + moving * 10 * Math.max(0, -Math.sin(walk - 0.45)) + br * 2;
        tgt[TL] = moving * 24 * sw; tgt[TR] = -moving * 24 * sw;
        tgt[KL] = moving * 32 * Math.max(0, cs); tgt[KR] = moving * 32 * Math.max(0, -cs);   // the swinging leg lifts its foot
        tgt[JAW] = 2 + Math.max(0, br) * 2;
        tgt[STANCE] = 4;
        snap = 1;
        if (moving < 0.2 && attack < 0 && stun <= 0) idle();
    }

    /** Standing still it isn't a statue: it looks round, rolls its shoulders, flexes its fists, huffs dust. */
    void idle() {
        if (idleBeat < 0 && ticks > idleBeatAt) { idleBeat = random.nextInt(4); idleT = 0; }
        if (idleBeat < 0) return;
        idleT++;
        double u = Math.sin(Math.PI * Math.min(1, idleT / 50.0));
        switch (idleBeat) {
            case 0 -> tgt[HYAW] += (idleT < 25 ? 40 : -35) * u;                                     // a look round
            case 1 -> { tgt[AL] += 14 * u; tgt[AR] += 14 * u; tgt[SL] += 12 * u; tgt[SR] += 12 * u; tgt[PITCH] -= 4 * u; }  // shoulders rolled back
            case 2 -> { tgt[EL] += 55 * u; tgt[ER] += 55 * u; tgt[AL] += 20 * u; tgt[AR] += 20 * u; }      // it flexes, fists up
            default -> {                                                                              // a huff
                tgt[JAW] += 18 * u; tgt[DROP] -= 0.08 * u; tgt[HPITCH] -= 8 * u;
                if (idleT == 20) { world.spawnParticle(Particle.CLOUD, mouth(), 6, 0.15, 0.1, 0.15, 0.02); world.playSound(center(), Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.HOSTILE, 1f, 0.4f); }
            }
        }
        if (idleT == 28 && idleBeat == 2) world.playSound(center(), Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 1.2f, 0.5f);
        if (idleT >= 50) { idleBeat = -1; idleBeatAt = ticks + 80 + random.nextInt(100); }
    }

    void animate(Location l, double moving) {
        base(moving);
        int t = at;
        if (stun > 0) stunned();
        else switch (attack) {
            case PUNCH -> punchPose(t);
            case SWEEP -> sweepPose(t);
            case POUND -> poundPose(t);
            case STOMP -> stompPose(t);
            case BOULDER -> boulderPose(t);
            case SPIKES -> spikesPose(t);
            case CHARGE -> chargePose(t);
            case GRAB -> grabPose(t);
            case CAVEIN -> roarPose(t);
            case CORE -> corePose(t);
            case OVERGROWTH -> overgrowthPose(t);
            default -> { }
        }
        if (ticks < 40) {                                                             // pulling itself out of the floor
            double u = 1 - ramp(ticks, 0, 40);
            tgt[DROP] += 1.4 * u; tgt[PITCH] += 22 * u; tgt[AL] += 50 * u; tgt[AR] += 50 * u; tgt[EL] += 40 * u; tgt[ER] += 40 * u;
            if (ticks % 5 == 0) world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.4, 0), 25, 1.4, 0.3, 1.4, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        }
        double fl = flinchAmt();
        if (fl > 0) { tgt[PITCH] -= 9 * fl; tgt[AL] -= 16 * fl; tgt[AR] -= 16 * fl; tgt[HPITCH] -= 12 * fl; tgt[JAW] += 14 * fl; tgt[TWIST] += 6 * fl; }
        // the head follows whoever it's fighting (as far as its neck turns), and keeps level as the body leans
        float yaw = l.getYaw();
        Player look = grabbed != null ? grabbed : focus != null && focus.isOnline() && focus.getWorld().equals(world) ? focus : target(active());
        if (look != null && attack != CHARGE && attack != OVERGROWTH && stun <= 0) {
            Vector to = look.getEyeLocation().toVector().subtract(l.clone().add(0, 5.3 * k, 0).toVector());
            tgt[HYAW] += Math.max(-50, Math.min(50, wrap(yawOf(flat(to)) - yaw - cur[TWIST] * 0.6)));
            tgt[HPITCH] += Math.max(-30, Math.min(35, pitchOf(to))) - tgt[PITCH] * 0.6;
        }
        for (int i = 0; i < N; i++) {
            double kk = Math.min(0.85, K[i] * snap);
            vel[i] = vel[i] * (1 - D[i]) + (tgt[i] - cur[i]) * kk;
            cur[i] += vel[i];
        }
        cur[EL] = Math.max(-5, cur[EL]); cur[ER] = Math.max(-5, cur[ER]);   // elbows don't bend backwards
        cur[JAW] = Math.max(0, cur[JAW]);
        place(l, yaw);
    }

    static double wrap(double d) { return ((d % 360) + 540) % 360 - 180; }

    // ---- the poses of each move (they add to / replace the base targets)
    void punchPose(int t) {
        int a = punchRight ? AR : AL, e = punchRight ? ER : EL, s = punchRight ? 1 : -1;
        if (t < 10) {                                                     // the fist draws back, the shoulders turn
            double u = ramp(t, 0, 9);
            tgt[a] = lerp(tgt[a], -35, u); tgt[e] = lerp(tgt[e], 105, u); tgt[TWIST] = 24 * s * u; tgt[PITCH] = 4; tgt[DROP] = 0.2;
        } else if (t < 15) {                                              // STRIKE
            snap = 2.6;
            tgt[a] = 88; tgt[e] = 8; tgt[TWIST] = -26 * s; tgt[PITCH] = 18; tgt[DROP] = 0.28;
            tgt[punchRight ? TL : TR] = 18; tgt[punchRight ? TR : TL] = -12;
        } else { tgt[a] = 40; tgt[e] = 40; tgt[TWIST] = -10 * s; tgt[PITCH] = 10; }
    }

    void sweepPose(int t) {
        if (t < 16) {                                                     // the left arm swings out wide and back
            double u = ramp(t, 0, 15);
            tgt[SL] = lerp(tgt[SL], 78, u); tgt[AL] = lerp(tgt[AL], 25, u); tgt[EL] = 25; tgt[TWIST] = 38 * u; tgt[ROLL] = -5 * u; tgt[DROP] = 0.25;
        } else if (t < 22) {                                              // the backhand: across its front
            snap = 2.4;
            tgt[SL] = -18; tgt[AL] = 82; tgt[EL] = 8; tgt[TWIST] = -42; tgt[ROLL] = 6; tgt[PITCH] = 12; tgt[DROP] = 0.3;
        } else { tgt[SL] = 20; tgt[AL] = 50; tgt[EL] = 30; tgt[TWIST] = -20; }
    }

    void poundPose(int t) {
        if (t < 22) {                                                     // both fists high overhead, rearing back, roaring
            double u = ramp(t, 0, 18);
            tgt[AL] = tgt[AR] = lerp(20, 168, u); tgt[EL] = tgt[ER] = lerp(20, 35, u); tgt[SL] = tgt[SR] = 12;
            tgt[PITCH] = -14 * u; tgt[DROP] = -0.05 * u; tgt[JAW] = t > 6 ? 30 : 0; tgt[HPITCH] = -15 * u;
        } else if (t < 28) {                                              // SLAM
            snap = 3.0;
            tgt[AL] = tgt[AR] = 18; tgt[EL] = tgt[ER] = 6; tgt[PITCH] = 26; tgt[DROP] = 0.85; tgt[JAW] = 8;
            tgt[TL] = tgt[TR] = 8;
        } else if (t < 40) { tgt[AL] = tgt[AR] = 22; tgt[EL] = tgt[ER] = 10; tgt[PITCH] = 30; tgt[DROP] = 0.8; }
        else { tgt[AL] = tgt[AR] = 30; tgt[PITCH] = 14; tgt[DROP] = 0.35; }
    }

    void stompPose(int t) {
        int i = Math.min(2, t / 16), u0 = t - i * 16;
        boolean left = i % 2 == 0;
        int th = left ? TL : TR, kn = left ? KL : KR;
        double lift = u0 < 9 ? ramp(u0, 0, 8) : 0;
        if (u0 >= 9 && u0 < 12) snap = 2.6;
        tgt[th] = 55 * lift; tgt[kn] = 75 * lift;
        tgt[ROLL] = (left ? 7 : -7) * lift;                               // its weight onto the other leg
        tgt[DROP] = 0.15 + (u0 >= 9 && u0 < 14 ? 0.3 : 0);
        tgt[AL] = tgt[AR] = 35 * lift + 10; tgt[SL] = tgt[SR] = 22 * lift + 8; tgt[EL] = tgt[ER] = 45;
        tgt[PITCH] = 10 + (u0 >= 9 ? 8 : 0);
    }

    void boulderPose(int t) {
        if (t < 12) {                                                     // bends down, digs its fingers into the floor
            double u = ramp(t, 0, 10);
            tgt[PITCH] = 38 * u; tgt[DROP] = 0.75 * u; tgt[AR] = 55 * u; tgt[ER] = 15; tgt[AL] = 30 * u; tgt[TWIST] = 10 * u;
        } else if (t < 22) {                                              // tears it out and heaves it overhead
            double u = ramp(t, 12, 21);
            tgt[PITCH] = lerp(38, -16, u); tgt[DROP] = lerp(0.75, 0.0, u); tgt[AR] = lerp(55, 172, u); tgt[ER] = lerp(15, 45, u);
            tgt[AL] = lerp(30, 140, u); tgt[EL] = 40; tgt[TWIST] = lerp(10, 25, u); tgt[JAW] = 25 * u;
        } else if (t < 28) {                                              // THROW
            snap = 2.6;
            tgt[AR] = 70; tgt[ER] = 0; tgt[AL] = -20; tgt[EL] = 20; tgt[PITCH] = 22; tgt[TWIST] = -28; tgt[DROP] = 0.3; tgt[TR] = 20;
        } else { tgt[AR] = 25; tgt[AL] = 5; tgt[PITCH] = 8; }
    }

    void spikesPose(int t) {
        if (t < 14) { double u = ramp(t, 0, 12); tgt[AL] = tgt[AR] = 125 * u; tgt[EL] = tgt[ER] = 45; tgt[SL] = tgt[SR] = 24; tgt[PITCH] = -8 * u; tgt[JAW] = 20 * u; }
        else if (t < 20) { snap = 2.8; tgt[AL] = tgt[AR] = 38; tgt[EL] = tgt[ER] = 4; tgt[PITCH] = 34; tgt[DROP] = 0.8; }
        else { tgt[AL] = tgt[AR] = 40; tgt[EL] = tgt[ER] = 6; tgt[PITCH] = 32; tgt[DROP] = 0.78; }      // held there while they burst
    }

    void chargePose(int t) {
        if (t < 18) {                                                     // head down, it scrapes the floor like a bull
            double u = ramp(t, 0, 10);
            tgt[PITCH] = 30 * u; tgt[DROP] = 0.45 * u; tgt[HPITCH] = 14; tgt[AL] = tgt[AR] = -20 * u; tgt[EL] = tgt[ER] = 50;
            double paw = Math.max(0, Math.sin(t * 0.7));
            tgt[TR] = -30 * paw; tgt[KR] = 25 * paw; tgt[JAW] = t > 4 && t < 14 ? 34 : 6;
        } else {                                                          // running: big strides, arms pumping back
            double sw = Math.sin(walk);
            tgt[PITCH] = 32; tgt[DROP] = 0.4 + 0.12 * Math.abs(sw); tgt[TL] = 40 * sw; tgt[TR] = -40 * sw;
            tgt[KL] = 55 * Math.max(0, Math.cos(walk)); tgt[KR] = 55 * Math.max(0, -Math.cos(walk));
            tgt[AL] = -45 + 25 * sw; tgt[AR] = -45 - 25 * sw; tgt[EL] = tgt[ER] = 55; tgt[TWIST] = -10 * sw; tgt[HPITCH] = 10;
        }
    }

    void stunned() {
        double wob = Math.sin(ticks * 0.35);
        tgt[PITCH] = -12; tgt[DROP] = 0.6; tgt[ROLL] = 7 * wob; tgt[HYAW] = 25 * wob; tgt[HPITCH] = -12; tgt[JAW] = 22;
        tgt[AL] = tgt[AR] = 8; tgt[EL] = tgt[ER] = 20; tgt[SL] = tgt[SR] = 28; tgt[TL] = 30; tgt[TR] = 20; tgt[KL] = 45; tgt[KR] = 30;
        if (ticks % 6 == 0) world.spawnParticle(Particle.CRIT, headTop(), 4, 0.5, 0.1, 0.5, 0.05);
    }

    void grabPose(int t) {
        if (t < 12) { double u = ramp(t, 0, 10); tgt[AL] = tgt[AR] = 80 * u; tgt[EL] = tgt[ER] = 15; tgt[SL] = tgt[SR] = 18; tgt[PITCH] = 26 * u; tgt[DROP] = 0.35 * u; tgt[JAW] = 20 * u; }
        else if (grabbed == null) { tgt[AL] = tgt[AR] = 30; tgt[EL] = tgt[ER] = 6; tgt[PITCH] = 32; tgt[DROP] = 0.8; snap = 2.2; }   // missed: fists into the floor
        else if (t < 40) {                                                // lifts them high in its right fist, roars in their face
            double u = ramp(t, 12, 22), shake = t > 22 ? Math.sin(t * 1.3) * 8 : 0;
            tgt[AR] = lerp(80, 150, u) + shake; tgt[ER] = lerp(15, 50, u); tgt[SR] = 6; tgt[AL] = 20; tgt[EL] = 50;
            tgt[PITCH] = lerp(26, -10, u); tgt[DROP] = 0.1; tgt[JAW] = t > 22 ? 42 : 10; tgt[TWIST] = 12;
        } else { snap = 2.8; tgt[AR] = 30; tgt[ER] = 4; tgt[PITCH] = 36; tgt[DROP] = 0.8; tgt[AL] = 20; tgt[TWIST] = -10; }   // SLAM
    }

    void roarPose(int t) {
        double u = ramp(t, 0, 12);
        tgt[PITCH] = -20 * u; tgt[DROP] = -0.05 * u; tgt[AL] = tgt[AR] = 70 * u; tgt[SL] = tgt[SR] = 62 * u; tgt[EL] = tgt[ER] = 40 * u;
        tgt[JAW] = t > 8 && t < 40 ? 48 : 5; tgt[HPITCH] = -25 * u;
        if (t > 12 && t < 40) { double sh = Math.sin(t * 1.7) * 4; tgt[ROLL] = sh; tgt[HYAW] = sh * 2; }
    }

    void corePose(int t) {
        double u = ramp(t, 0, 16), pulse = (t - 20) % 12 < 4 && t >= 20 ? 1 : 0;
        tgt[DROP] = 0.95 * u; tgt[PITCH] = -6 * u - 8 * pulse; tgt[AL] = tgt[AR] = 30 * u; tgt[SL] = tgt[SR] = 58 * u + 10 * pulse;
        tgt[EL] = tgt[ER] = 40; tgt[TL] = 40 * u; tgt[TR] = 40 * u; tgt[JAW] = 30 * u + 15 * pulse; tgt[HPITCH] = -20 * u;
        if (pulse > 0) snap = 2;
    }

    void overgrowthPose(int t) {
        double pulse = Math.sin(t * 0.12);
        tgt[AL] = tgt[AR] = 30; tgt[EL] = tgt[ER] = 30; tgt[SL] = tgt[SR] = 14; tgt[TL] = 48; tgt[TR] = 10; tgt[KL] = 0;
        tgt[PITCH] = 20 + pulse * 2; tgt[DROP] = 0.95 + pulse * 0.03; tgt[HPITCH] = 28; tgt[JAW] = 4;   // kneels on one knee, rooted
    }

    // ---- placing the parts: hip -> waist twist -> chest -> neck / shoulders -> elbows; pelvis -> hips -> knees
    Location hip, neck;
    Quaternionf chestQ;

    void place(Location l, float yaw) {
        float pitch = (float) cur[PITCH], roll = (float) cur[ROLL];
        hip = l.clone().add(0, (2.3 - cur[DROP]) * k, 0);
        Quaternionf tw = new Quaternionf().rotationY((float) Math.toRadians(cur[TWIST]));
        Quaternionf F = RigMath.frame(yaw, pitch, roll);
        chestQ = new Quaternionf(F).mul(tw);
        bodyP.pose(hip, yaw, pitch, roll, tw);
        // shoulders and the neck ride the chest
        neck = RigMath.joint(hip, chestQ, 0.05, 2.86, 0, k);
        Quaternionf head = new Quaternionf(tw).mul(new Quaternionf().rotationY((float) Math.toRadians(cur[HYAW]))).mul(RigMath.rotZ(-cur[HPITCH]));
        headP.pose(neck, yaw, pitch, roll, head);
        Quaternionf hq = new Quaternionf(F).mul(head);
        jawP.pose(RigMath.joint(neck, hq, -0.1, 0.24, 0, k), yaw, pitch, roll, new Quaternionf(head).mul(RigMath.rotZ(-cur[JAW])));
        fistL = arm(armL, foreL, F, tw, yaw, pitch, roll, -1, cur[AL], cur[SL], cur[EL]);
        fistR = arm(armR, foreR, F, tw, yaw, pitch, roll, 1, cur[AR], cur[SR], cur[ER]);
        // legs hang from the pelvis (which doesn't lean with the chest): solved so the feet stay under it as it crouches
        float pr = (float) (roll * 0.4);
        Quaternionf P = RigMath.frame(yaw, 0, pr);
        double h = Math.max(1.0, Math.min(2.29, 2.3 - cur[DROP] + 0.0)), L1 = 1.2, L2 = 1.1;
        double a = Math.toDegrees(Math.acos(clamp((L1 * L1 + h * h - L2 * L2) / (2 * L1 * h))));
        double b = Math.toDegrees(Math.acos(clamp((L2 * L2 + h * h - L1 * L1) / (2 * L2 * h))));
        footL = leg(legL, shinL, P, yaw, pr, -1, a + cur[TL] + cur[PITCH] * 0.15, a + b + cur[KL], cur[STANCE]);
        footR = leg(legR, shinR, P, yaw, pr, 1, a + cur[TR] + cur[PITCH] * 0.15, a + b + cur[KR], cur[STANCE]);
    }

    static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

    /** One arm; side -1 left, +1 right. Returns the middle of the fist. */
    Location arm(Wild.Part up, Wild.Part fore, Quaternionf F, Quaternionf tw, float yaw, float pitch, float roll, int side, double swing, double spread, double elbow) {
        Location sh = RigMath.joint(hip, chestQ, 0.05, 2.5, side * 2.12, k);
        Quaternionf ql = new Quaternionf(tw).mul(RigMath.rotZ(swing)).mul(RigMath.rotX(-side * spread));
        up.pose(sh, yaw, pitch, roll, ql);
        Location el = RigMath.joint(sh, new Quaternionf(F).mul(ql), 0, -1.75, 0, k);
        Quaternionf qf = new Quaternionf(ql).mul(RigMath.rotZ(elbow));
        fore.pose(el, yaw, pitch, roll, qf);
        return RigMath.joint(el, new Quaternionf(F).mul(qf), 0.05, -1.8, 0, k);
    }

    /** One leg: the thigh swung forward by thigh degrees, the shin bent back by knee. Returns the sole. */
    Location leg(Wild.Part thighP, Wild.Part shinP, Quaternionf P, float yaw, float roll, int side, double thigh, double knee, double stance) {
        Location hj = RigMath.joint(hip, P, 0, 0, side * 0.66, k);
        Quaternionf ql = RigMath.rotZ(thigh).mul(RigMath.rotX(-side * stance));
        thighP.pose(hj, yaw, 0, roll, ql);
        Location kn = RigMath.joint(hj, new Quaternionf(P).mul(ql), 0, -1.2, 0, k);
        Quaternionf qs = new Quaternionf(ql).mul(RigMath.rotZ(-knee));
        shinP.pose(kn, yaw, 0, roll, qs);
        return RigMath.joint(kn, new Quaternionf(P).mul(qs), 0.15, -1.05, 0, k);
    }

    Location chest() { return chestQ == null ? center() : RigMath.joint(hip, chestQ, 1.45, 2.05, 0, k); }
    Location mouth() { return neck == null ? center() : neck.clone().add(dirOf(mover.getLocation().getYaw()).multiply(0.9 * k)).add(0, 0.2 * k, 0); }
    Location headTop() { return neck == null ? center().add(0, 3, 0) : neck.clone().add(0, 1.7 * k, 0); }

    void walkTo(Location l) {
        try { mover.getPathfinder().moveTo(l, 1.0); } catch (RuntimeException ignored) { }
    }

    void stopWalking() {
        try { mover.getPathfinder().stopPathfinding(); } catch (RuntimeException ignored) { }
        Safe.vel(mover, new Vector(0, Math.min(0, mover.getVelocity().getY()), 0));
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    double dmg(String key, double def) { return c("moves." + key, def); }

    @Override
    int pick(List<Player> a) {
        Player t = target(a);
        double d = t == null ? 8 : Math.sqrt(t.getLocation().distanceSquared(mover.getLocation()));
        List<Integer> pool = new ArrayList<>();
        if (d < 4.8) pool.addAll(List.of(PUNCH, PUNCH, SWEEP, GRAB, POUND, STOMP));
        else if (d < 11) pool.addAll(List.of(POUND, STOMP, SPIKES, CHARGE, GRAB, BOULDER));
        else pool.addAll(List.of(BOULDER, BOULDER, SPIKES, CHARGE));
        if (phase >= 2) pool.add(CAVEIN);
        if (phase >= 2 && hp < maxHp * 0.8 && lastMove != OVERGROWTH) pool.add(OVERGROWTH);
        if (phase >= 3) { pool.add(CORE); pool.add(CORE); }
        if (pool.size() > 1) pool.removeIf(m -> m == lastMove && m != PUNCH);
        int m = pool.get(random.nextInt(pool.size()));
        lastMove = m;
        return m;
    }

    @Override
    int nextCd() {
        int base = super.nextCd();
        return attack == PUNCH || lastMove == PUNCH ? Math.max(10, base / 3) : base;
    }

    @Override
    void startMove(int move, Player t) {
        hitThisMove.clear();
        idleBeat = -1;
        wallHit = false;
        Location tl = t != null ? t.getLocation() : mover.getLocation().add(dirOf(mover.getLocation().getYaw()).multiply(6));
        switch (move) {
            case PUNCH -> { punchRight = !punchRight; world.playSound(center(), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.6f); }
            case SWEEP -> { stopWalking(); world.playSound(center(), Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 2f, 0.5f); }
            case POUND -> { stopWalking(); world.playSound(center(), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 3f, 0.4f); }
            case STOMP -> stopWalking();
            case BOULDER -> { stopWalking(); mark = tl.clone(); }
            case SPIKES -> { stopWalking(); mark = mover.getLocation(); lineDir = flat(tl.toVector().subtract(mark.toVector())); }
            case CHARGE -> { stopWalking(); lineDir = flat(tl.toVector().subtract(mover.getLocation().toVector())); }
            case GRAB -> { stopWalking(); grabbed = null; }
            case CAVEIN, CORE -> stopWalking();
            case OVERGROWTH -> {
                healLeft = maxHp * c("overgrowth-heal", 0.06);
                staggerDamage = maxHp * c("overgrowth-break", 0.05);
                stopWalking();
                world.playSound(center(), Sound.BLOCK_MOSS_PLACE, SoundCategory.HOSTILE, 3f, 0.5f);
                say(ChatColor.GRAY + "It kneels, and moss crawls over its cracks... (hit it hard to stop it)");
            }
            default -> { }
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case PUNCH -> punch();
            case SWEEP -> sweep();
            case POUND -> pound();
            case STOMP -> stomp();
            case BOULDER -> boulder();
            case SPIKES -> spikes();
            case CHARGE -> charge();
            case GRAB -> grab();
            case CAVEIN -> caveIn(a);
            case CORE -> core();
            case OVERGROWTH -> overgrowth();
            default -> end();
        }
    }

    /** Everyone in front of it (within r, inside the cone dot >= minDot). */
    List<Player> inFront(double r, double minDot) {
        Location l = mover.getLocation();
        Vector f = dirOf(l.getYaw());
        List<Player> out = new ArrayList<>();
        for (Player p : near(l, r, 6)) {
            Vector to = flat(p.getLocation().toVector().subtract(l.toVector()));
            if (to.lengthSquared() < 1e-6 || to.dot(f) >= minDot) out.add(p);
        }
        return out;
    }

    void punch() {
        if (at == 12) {
            Location f = punchRight ? fistR : fistL;
            world.playSound(center(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 2f, 0.5f);
            if (f != null) world.spawnParticle(Particle.BLOCK, f, 12, 0.3, 0.3, 0.3, 0, Material.STONE.createBlockData());
            for (Player p : inFront(4.6, 0.55)) {
                hit(p, dmg("punch", 6), center().toVector(), Guard.HEAVY);
                Safe.vel(p, dirOf(mover.getLocation().getYaw()).multiply(1.1).setY(0.45));
            }
        }
        if (at > 26) end();
    }

    void sweep() {
        Location l = mover.getLocation();
        if (at < 16 && at % 3 == 0) {                                         // a telegraph: the arc it's about to sweep
            Vector f = dirOf(l.getYaw());
            for (int i = -6; i <= 6; i++) {
                double ang = Math.toRadians(i * 15);
                Vector d = f.clone().rotateAroundY(ang).multiply(5.2);
                world.spawnParticle(Particle.DUST, l.clone().add(d).add(0, 0.2, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(150, 120, 80), 1.6f));
            }
        }
        if (at == 18) {
            world.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2.5f, 0.4f);
            world.spawnParticle(Particle.SWEEP_ATTACK, center().add(dirOf(l.getYaw()).multiply(2.5)), 5, 1.5, 0.4, 1.5, 0);
            Vector side = dirOf(l.getYaw() - 90);                             // knocked out to its left
            for (Player p : inFront(5.8, -0.15)) {
                hit(p, dmg("sweep", 8), center().toVector(), Guard.HEAVY);
                Safe.vel(p, side.clone().multiply(1.3).setY(0.55));
            }
        }
        if (at > 36) end();
    }

    void pound() {
        Location l = mover.getLocation();
        Location c = l.clone().add(dirOf(l.getYaw()).multiply(2.2 * k));
        c.setY(ground(c));
        if (at < 22) { if (at % 3 == 0) warnRing(c, 8, Color.fromRGB(150, 120, 80)); if (at == 8) world.playSound(l, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.6f); return; }
        if (at == 25) {
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.5f);
            world.playSound(l, Sound.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 2.5f, 0.5f);
            world.spawnParticle(Particle.BLOCK, c.clone().add(0, 0.3, 0), 160, 2.5, 0.3, 2.5, 0, Material.MOSSY_COBBLESTONE.createBlockData());
            world.spawnParticle(Particle.EXPLOSION, c, 3, 1.2, 0.3, 1.2, 0);
            for (Player p : near(c, 2.8, 3)) if (hitThisMove.add(p.getUniqueId())) { hit(p, dmg("ground-pound", 10), c.toVector(), Guard.HEAVY); Safe.vel(p, new Vector(0, 0.8, 0)); }
            ringR = 1.5;
            mark = c;
        }
        if (at > 25 && at <= 38) shockwave(mark, 0.55, 8.5, dmg("shockwave", 7), Material.MOSSY_COBBLESTONE, Color.fromRGB(140, 120, 90));
        if (at > 46) end();
    }

    /** A shockwave ring rolling out along the ground: whoever is standing on the ground where it passes is hit (jump it). */
    void shockwave(Location c, double speed, double max, double dmg, Material m, Color col) {
        if (c == null || ringR > max) return;
        ringR += speed;
        int n = (int) (ringR * 5);
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n;
            Location p = c.clone().add(Math.cos(ang) * ringR, 0, Math.sin(ang) * ringR);
            p.setY(ground(p) + 0.15);
            world.spawnParticle(Particle.BLOCK, p, 2, 0.15, 0.1, 0.15, 0, m.createBlockData());
            if (i % 2 == 0) world.spawnParticle(Particle.DUST, p.add(0, 0.2, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.5f));
        }
        if (((int) (ringR * 2)) % 3 == 0) world.playSound(c, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 1.5f, 0.5f);
        for (Player p : near(c, ringR + 0.5, 3)) {
            Location pl0 = p.getLocation();
            double dx = pl0.getX() - c.getX(), dz = pl0.getZ() - c.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            if (d < ringR - 1.0) continue;
            boolean grounded = pl0.getY() - ground(pl0) < 0.4;
            if (!grounded || !hitThisMove.add(p.getUniqueId())) continue;
            hit(p, dmg, c.toVector(), Guard.UNBLOCKABLE);
            Safe.vel(p, new Vector(dx, 0, dz).normalize().multiply(0.6).setY(0.7));
        }
    }

    void stomp() {
        int i = at / 16, u = at % 16;
        int count = phase >= 2 ? 3 : 2;
        if (i >= count) { if (at > count * 16 + 8) end(); return; }
        Location foot = i % 2 == 0 ? footL : footR;
        if (u == 2 && foot != null) warnRing(foot, i == count - 1 && phase >= 2 ? 6 : 4.2, Color.fromRGB(120, 140, 90));
        if (u == 10 && foot != null) {
            double r = i == count - 1 && phase >= 2 ? 6 : 4.2;
            world.playSound(foot, Sound.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 2f, 0.6f);
            world.playSound(foot, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 1.2f, 0.4f);
            world.spawnParticle(Particle.BLOCK, foot, 60, r / 2, 0.2, r / 2, 0, Material.STONE.createBlockData());
            for (Player p : near(foot, r, 2.5)) {
                if (p.getLocation().getY() - ground(p.getLocation()) > 0.5) continue;   // jumped it
                hit(p, dmg("stomp", 5), foot.toVector(), Guard.UNBLOCKABLE);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
                Safe.vel(p, new Vector(0, 0.45, 0));
            }
        }
    }

    void boulder() {
        if (at == 8 && fistR != null) {
            world.playSound(fistR, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2.5f, 0.4f);
            world.spawnParticle(Particle.BLOCK, fistR, 50, 0.8, 0.3, 0.8, 0, Material.STONE.createBlockData());
            heldRock = rock(fistR, 1.9);                                      // torn out of the floor
        }
        if (at < 24 && at % 3 == 0 && mark != null) {
            if (focus != null && focus.isValid() && focus.getWorld().equals(world)) {   // it leads a moving target a little
                mark = focus.getLocation().add(focus.getVelocity().setY(0).multiply(12));
                mark.setY(ground(mark));
            }
            warnRing(mark, 3.2, Color.fromRGB(110, 140, 90));
        }
        if (at == 24 && heldRock != null) {
            BlockDisplay r = heldRock; heldRock = null;
            Location from = r.getLocation(), target = mark.clone();
            world.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.6f);
            throwRock(r, from, target, 22, 7, 1.9, dmg("boulder", 9), 3.2, true);
        }
        if (at > 44) end();
    }

    BlockDisplay rock(Location at, double size) {
        float s = (float) size, h = -s / 2;
        BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(Material.MOSSY_COBBLESTONE.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(h, h, h), new Quaternionf().rotateXYZ(0.3f, 0.6f, 0.2f), new Vector3f(s, s, s), new Quaternionf()));
            e.setTeleportDuration(1);
            e.setPersistent(false);
            e.addScoreboardTag(Wild.FX_TAG);
        });
        rocks.add(d);
        return d;
    }

    void heldRockTick() {
        if (heldRock == null) return;
        if (!heldRock.isValid() || attack != BOULDER) { heldRock.remove(); heldRock = null; return; }
        if (fistR != null) heldRock.teleport(fistR.clone().add(0, 0.6 * k, 0));
    }

    /** A rock flying in an arc to target over `ticks`; on landing it hurts within r (and the big one splits into three). */
    void throwRock(BlockDisplay r, Location from, Location target, int ticks, double arc, double size, double damage, double radius, boolean split) {
        int[] t = {0};
        float[] spin = {0};
        fx.add(() -> {
            t[0]++;
            if (!r.isValid()) return true;
            double f = Math.min(1, t[0] / (double) ticks);
            Location p = from.clone().add(target.toVector().subtract(from.toVector()).multiply(f));
            p.add(0, Math.sin(Math.PI * f) * arc, 0);
            r.teleport(p);
            spin[0] += 0.35f;
            float h = (float) (-size / 2);
            r.setInterpolationDelay(0); r.setInterpolationDuration(1);
            r.setTransformation(new Transformation(new Vector3f(h, h, h), new Quaternionf().rotateXYZ(spin[0], spin[0] * 0.7f, 0), new Vector3f((float) size, (float) size, (float) size), new Quaternionf()));
            if (f < 1) return false;
            r.remove();
            world.spawnParticle(Particle.BLOCK, target, (int) (40 * size), size * 0.6, 0.4, size * 0.6, 0, Material.MOSSY_COBBLESTONE.createBlockData());
            world.playSound(target, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2.5f, 0.5f);
            world.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1f, 1.4f);
            for (Player pp : near(target, radius, 4)) hit(pp, damage, target.toVector().add(new Vector(0, 3, 0)), Guard.HEAVY);
            if (split) for (int i = 0; i < 3; i++) {                           // it shatters: three chunks bounce on
                double ang = random.nextDouble() * Math.PI * 2, d = 3 + random.nextDouble() * 2.5;
                Location to = target.clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
                to.setY(ground(to));
                BlockDisplay small = rock(target.clone().add(0, 0.5, 0), 0.8);
                throwRock(small, target.clone().add(0, 0.5, 0), to, 12, 2.5, 0.8, dmg("boulder-chunk", 4), 1.6, false);
            }
            return true;
        });
    }

    /** Amethyst bursts up along a line, one after another (three lines from phase 2). */
    void spikes() {
        int lines = phase >= 2 ? 3 : 1;
        if (at < 16) {
            if (at % 2 == 0) for (int i = 0; i < lines; i++) warnLine(mark, dir(i, lines), 18, Color.fromRGB(170, 100, 230));
            return;
        }
        int step = at - 16;
        if (step == 0) world.playSound(mark, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 3f, 0.5f);
        if (step % 2 == 0 && step / 2 < 9) {
            double d = 2.5 + step;
            for (int i = 0; i < lines; i++) {
                Location l = mark.clone().add(dir(i, lines).multiply(d));
                l.setY(ground(l));
                crystal(l, 1.4 + random.nextDouble() * 0.4, 2.2 + random.nextDouble() * 0.9);
                world.playSound(l, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 2f, 0.6f);
                world.spawnParticle(Particle.BLOCK, l, 10, 0.4, 0.4, 0.4, 0, Material.AMETHYST_BLOCK.createBlockData());
                for (Player p : near(l, 1.7, 3)) if (hitThisMove.add(p.getUniqueId())) { hit(p, dmg("crystal-spikes", 7), l.toVector(), Guard.UNBLOCKABLE); Safe.vel(p, new Vector(0, 0.85, 0)); }
            }
        }
        if (step > 24) end();
    }

    Vector dir(int i, int lines) { return lineDir.clone().rotateAroundY(Math.toRadians(lines == 1 ? 0 : (i - 1) * 25)); }

    /** An amethyst cluster that grows up out of the floor, glitters, then sinks back. */
    void crystal(Location l, double w, double h) {
        float fw = (float) w, fh = (float) h;
        float tilt = (float) ((random.nextDouble() - 0.5) * 0.35);
        BlockDisplay c = world.spawn(l, BlockDisplay.class, e -> {
            e.setBlock(Material.AMETHYST_CLUSTER.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-fw / 2, -0.2f, -fw / 2), new Quaternionf().rotateZ(tilt), new Vector3f(fw, 0.05f, fw), new Quaternionf()));
            e.setPersistent(false);
            e.addScoreboardTag(Wild.FX_TAG);
            e.setBrightness(new Display.Brightness(14, 14));
        });
        rocks.add(c);
        later(1, () -> { if (!c.isValid()) return; c.setInterpolationDelay(0); c.setInterpolationDuration(3);
            c.setTransformation(new Transformation(new Vector3f(-fw / 2, 0, -fw / 2), new Quaternionf().rotateZ(tilt), new Vector3f(fw, fh, fw), new Quaternionf())); });
        later(30, () -> { if (!c.isValid()) return; c.setInterpolationDelay(0); c.setInterpolationDuration(8);
            c.setTransformation(new Transformation(new Vector3f(-fw / 2, -0.4f, -fw / 2), new Quaternionf().rotateZ(tilt), new Vector3f(fw * 0.6f, 0.05f, fw * 0.6f), new Quaternionf())); });
        later(40, () -> { if (c.isValid()) c.remove(); });
    }

    /** RAMPAGE: it paws the floor, then charges in a straight line; ramming a wall stuns it (hit it hard then). */
    void charge() {
        Location l = mover.getLocation();
        if (at < 18) {
            if (at % 3 == 0) warnLine(l, lineDir, 14, Color.fromRGB(180, 90, 60));
            if (at == 5) world.playSound(l, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2.5f, 0.5f);
            if (at % 6 == 0) world.spawnParticle(Particle.BLOCK, l, 14, 0.8, 0.1, 0.8, 0, Material.STONE.createBlockData());
            if (lineDir != null) turnToward(l.clone().add(lineDir), 10);
            return;
        }
        Vector d = lineDir.clone().multiply(c("charge-speed", 0.6));
        Safe.vel(mover, new Vector(d.getX(), Math.min(0, mover.getVelocity().getY()), d.getZ()));
        if (at % 2 == 0) world.spawnParticle(Particle.BLOCK, l, 8, 0.8, 0.1, 0.8, 0, Material.MOSS_BLOCK.createBlockData());
        for (Player p : near(l.clone().add(lineDir.clone().multiply(1.2)), 2.6, 4))
            if (hitThisMove.add(p.getUniqueId())) {
                hit(p, dmg("rampage", 9), l.toVector(), Guard.HEAVY);
                Safe.vel(p, lineDir.clone().multiply(1.4).setY(0.7));
                world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 2f, 0.5f);
            }
        // a wall right ahead (knee or chest height): it slams into it
        Location ahead = l.clone().add(lineDir.clone().multiply(2.0 * k));
        boolean wall = ahead.clone().add(0, 1.2, 0).getBlock().getType().isSolid() || ahead.clone().add(0, 3, 0).getBlock().getType().isSolid();
        if (wall && at > 20) {
            wallHit = true;
            stopWalking();
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.6f);
            world.playSound(l, Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 2.5f, 0.4f);
            world.spawnParticle(Particle.BLOCK, ahead.add(0, 2, 0), 120, 1.2, 1.5, 1.2, 0, Material.STONE.createBlockData());
            stun = (int) c("charge-stun-ticks", 50);
            say(ChatColor.GRAY + "It slams into the wall and staggers! " + ChatColor.YELLOW + "Hit it now!");
            for (int i = 0; i < 4; i++) {                                     // the ceiling rains rubble on it
                Location r = l.clone().add(random.nextGaussian() * 3, 0, random.nextGaussian() * 3);
                r.setY(ground(r));
                falling(r, 9 + random.nextInt(4), dmg("rubble", 4), 1.4, 6);
            }
            end();
            return;
        }
        if (at > 18 + c("charge-ticks", 28)) { stopWalking(); end(); }
    }

    /** GRAB: it lunges, lifts whoever it catches in its fist, roars in their face and slams them down (hit it to escape). */
    void grab() {
        Location l = mover.getLocation();
        if (at == 6) Safe.vel(mover, dirOf(l.getYaw()).multiply(0.5).setY(0.1));
        if (at == 12) {
            Player best = null; double bd = 99;
            for (Player p : inFront(5.0, 0.55)) {
                double dd = p.getLocation().distanceSquared(l);
                if (dd < bd && p.getVehicle() == null) { bd = dd; best = p; }
            }
            if (best == null) {
                world.playSound(l, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
                Location c = l.clone().add(dirOf(l.getYaw()).multiply(2.5 * k));
                c.setY(ground(c));
                world.spawnParticle(Particle.BLOCK, c, 50, 1.2, 0.2, 1.2, 0, Material.STONE.createBlockData());
                for (Player p : near(c, 3, 2.5)) hit(p, dmg("grab-miss", 5), c.toVector(), Guard.HEAVY);
                at = 100;   // straight to the end
            } else {
                grabbed = best;
                breakFree = maxHp * c("grab-break-share", 0.03);
                world.playSound(best.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.5f);
                best.sendActionBar(legacy(ChatColor.RED + "It's got you! " + ChatColor.GRAY + "Your friends can hit it to break you free."));
            }
        }
        if (grabbed != null && at == 24) {
            world.playSound(mouth(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
            world.spawnParticle(Particle.CLOUD, mouth(), 20, 0.3, 0.3, 0.3, 0.1);
        }
        if (grabbed != null && at == 44) {
            Player p = grabbed; release(false);
            Location c = l.clone().add(dirOf(l.getYaw()).multiply(2.8 * k));
            c.setY(ground(c));
            world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.6f);
            world.spawnParticle(Particle.BLOCK, c, 80, 1.2, 0.3, 1.2, 0, Material.MOSSY_COBBLESTONE.createBlockData());
            if (phase >= 3) {                                                 // phase 3: thrown instead
                p.teleport(withLook(fistR != null ? fistR : c, p));
                Safe.vel(p, dirOf(l.getYaw()).multiply(1.6).setY(0.9));
                hit(p, dmg("grab-throw", 6), l.toVector(), Guard.UNBLOCKABLE);
            } else {
                p.teleport(withLook(c.clone().add(0, 0.2, 0), p));
                hit(p, dmg("grab-slam", 9), c.toVector().add(new Vector(0, 3, 0)), Guard.UNBLOCKABLE);
                Safe.vel(p, new Vector(0, 0.5, 0));
            }
        }
        if (at > 60) end();
    }

    static Location withLook(Location at, Player p) { Location l = at.clone(); l.setYaw(p.getLocation().getYaw()); l.setPitch(p.getLocation().getPitch()); return l; }

    void grabTick() {
        if (grabbed == null) return;
        Player p = grabbed;
        if (!p.isOnline() || p.isDead() || !p.getWorld().equals(world) || !survival(p) || attack != GRAB) { release(true); return; }
        if (fistR == null) return;
        Location hold = fistR.clone().add(0, -0.6, 0);
        if (!Double.isFinite(hold.getX() + hold.getY() + hold.getZ())) return;
        p.teleport(withLook(hold, p));
        Safe.vel(p, new Vector());
        p.setFallDistance(0);
    }

    void release(boolean drop) {
        Player p = grabbed;
        grabbed = null;
        if (p != null && drop && p.isOnline()) { p.setFallDistance(0); Safe.vel(p, new Vector(0, 0.2, 0)); }
    }

    /** CAVE-IN: a roar that pushes everyone back and shakes stalactites down on them. */
    void caveIn(List<Player> a) {
        Location l = mover.getLocation();
        if (at == 10) {
            world.playSound(l, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.4f);
            world.playSound(l, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
            world.spawnParticle(Particle.SONIC_BOOM, mouth(), 1);
            for (Player p : near(l, 7, 4)) {
                Vector away = flat(p.getLocation().toVector().subtract(l.toVector()));
                Safe.vel(p, away.multiply(0.9).setY(0.35));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, true));
            }
            int per = phase >= 3 ? 5 : 3;
            for (Player p : a) for (int i = 0; i < per; i++) {
                Location spot = p.getLocation().add(i == 0 ? 0 : random.nextGaussian() * 2.6, 0, i == 0 ? 0 : random.nextGaussian() * 2.6);
                spot.setY(ground(spot));
                falling(spot, 26 + i * 4, dmg("cave-in", 7), 1.7, 14);
            }
        }
        if (at > 10 && at < 40 && at % 3 == 0) world.spawnParticle(Particle.BLOCK, l.clone().add(0, 6, 0), 20, 8, 1, 8, 0, Material.STONE.createBlockData());
        if (at > 64) end();
    }

    /** A stalactite falling onto spot after `delay` ticks of warning (dropping `fall` blocks): hurts within r. */
    void falling(Location spot, int delay, double damage, double r, int fall) {
        int[] t = {0};
        BlockDisplay[] d = {null};
        double top = spot.getY() + fall;
        for (int dy = 2; dy <= fall; dy++) if (spot.clone().add(0, dy, 0).getBlock().getType().isSolid()) { top = spot.getY() + dy - 1; break; }
        double startY = top;
        fx.add(() -> {
            t[0]++;
            if (t[0] < delay) { if (t[0] % 3 == 0) warnRing(spot, r, Color.fromRGB(130, 110, 90)); return false; }
            if (d[0] == null) {
                BlockData bd;
                try { bd = Bukkit.createBlockData("minecraft:pointed_dripstone[vertical_direction=down,thickness=frustum]"); }
                catch (RuntimeException e) { bd = Material.COBBLESTONE.createBlockData(); }
                BlockData fb = bd;
                d[0] = world.spawn(spot.clone().add(0, startY - spot.getY(), 0), BlockDisplay.class, e -> {
                    e.setBlock(fb);
                    e.setTransformation(new Transformation(new Vector3f(-0.7f, -1.6f, -0.7f), new Quaternionf(), new Vector3f(1.4f, 2.2f, 1.4f), new Quaternionf()));
                    e.setTeleportDuration(1);
                    e.setPersistent(false);
                    e.addScoreboardTag(Wild.FX_TAG);
                });
                rocks.add(d[0]);
                world.playSound(spot, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
            }
            int ft = t[0] - delay;
            double y = startY - 0.06 * ft * ft;
            if (y > spot.getY() + 0.3) { d[0].teleport(spot.clone().add(0, y - spot.getY(), 0)); return false; }
            d[0].remove();
            world.playSound(spot, Sound.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.HOSTILE, 2f, 0.6f);
            world.spawnParticle(Particle.BLOCK, spot, 30, r * 0.5, 0.3, r * 0.5, 0, Material.DRIPSTONE_BLOCK.createBlockData());
            for (Player p : near(spot, r, 3)) hit(p, damage, spot.toVector().add(new Vector(0, 4, 0)), Guard.HEAVY);
            return true;
        });
    }

    /** CORE PULSE (phase 3): it kneels, its core flares, and three amber shockwaves roll out (jump each one). */
    void core() {
        Location l = mover.getLocation();
        Location c = l.clone(); c.setY(ground(c));
        if (at < 20) {
            if (at % 2 == 0) {
                Location ch = chest();
                for (int i = 0; i < 4; i++) {
                    Vector o = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().multiply(3);
                    world.spawnParticle(Particle.FLAME, ch.clone().add(o), 0, -o.getX(), -o.getY(), -o.getZ(), 0.08);
                }
            }
            if (at % 4 == 0) warnRing(c, 9, Color.ORANGE);
            if (at == 4) world.playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 2.5f, 0.5f);
            return;
        }
        int p = (at - 20) / 12, u = (at - 20) % 12;
        if (p < 3) {
            if (u == 0) {
                ringR = 1.0; hitThisMove.clear(); mark = c;
                world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.7f);
                world.playSound(l, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.HOSTILE, 2.5f, 0.6f);
                world.spawnParticle(Particle.FLAME, chest(), 40, 0.4, 0.4, 0.4, 0.15);
            }
            shockwave(mark, 0.8, 9.5, dmg("core-pulse", 7), Material.ORANGE_CONCRETE_POWDER, Color.ORANGE);
        }
        if (at > 20 + 36 + 14) end();
    }

    void overgrowth() {
        if (at % 4 == 0) world.spawnParticle(Particle.BLOCK, center(), 8, 1, 1.5, 1, 0, Material.MOSS_BLOCK.createBlockData());
        if (at % 10 == 0 && healLeft > 0) {
            double h = Math.min(healLeft, maxHp * 0.01);
            hp = Math.min(maxHp, hp + h);
            healLeft -= h;
            world.spawnParticle(Particle.HAPPY_VILLAGER, center(), 10, 1, 1.5, 1, 0);
        }
        if (healLeft <= 0 || at > 140) end();
    }

    @Override
    double damageTaken(double amount) {
        if (attack == OVERGROWTH) {
            staggerDamage -= amount;
            if (staggerDamage <= 0) {
                world.playSound(center(), Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 3f, 0.6f);
                say(ChatColor.GRAY + "The moss tears away!");
                stun = 30;
                end();
            }
        }
        if (grabbed != null) {
            breakFree -= amount;
            if (breakFree <= 0) {
                world.playSound(center(), Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 2.5f, 0.6f);
                grabbed.sendActionBar(legacy(ChatColor.GREEN + "You break free!"));
                release(true);
                stun = 25;
            }
        }
        return stun > 0 ? amount * c("stunned-damage-taken", 1.35) : amount;
    }

    @Override
    void end() {
        if (grabbed != null) release(true);
        if (heldRock != null) { heldRock.remove(); heldRock = null; }
        super.end();
    }

    // =====================================================================================================
    //  death: it staggers, its knees buckle, it topples forward and crumbles into the moss
    // =====================================================================================================
    @Override
    void deathAnim(int t) {
        mover.setAI(false);
        if (grabbed != null) release(true);
        Location l = mover.getLocation();
        Arrays.fill(tgt, 0);
        snap = 1;
        if (t < 16) {                                                         // staggers back, arms flung out
            tgt[PITCH] = -18; tgt[DROP] = 0.2; tgt[AL] = tgt[AR] = 40; tgt[SL] = tgt[SR] = 55; tgt[EL] = tgt[ER] = 30; tgt[JAW] = 40; tgt[HPITCH] = -25;
            tgt[ROLL] = Math.sin(t * 0.6) * 6;
        } else if (t < 36) {                                                  // the knees go
            tgt[PITCH] = 10; tgt[DROP] = 1.1; tgt[AL] = tgt[AR] = 10; tgt[SL] = tgt[SR] = 20; tgt[EL] = tgt[ER] = 25; tgt[JAW] = 25; tgt[HPITCH] = 20;
            tgt[TL] = tgt[TR] = 70;
        } else {                                                              // topples forward onto its face
            double f = ramp(t, 36, 56);
            tgt[PITCH] = 10 + 75 * f; tgt[DROP] = 1.1 + 0.9 * f + (t > 60 ? (t - 60) * 0.05 : 0); tgt[AL] = tgt[AR] = 10 + 70 * f; tgt[EL] = tgt[ER] = 10;
            tgt[SL] = tgt[SR] = 30; tgt[JAW] = 15; tgt[HPITCH] = 10; tgt[TL] = tgt[TR] = 70; tgt[ROLL] = 6 * f;
            snap = t < 56 ? 1.4 : 1;
        }
        for (int i = 0; i < N; i++) { vel[i] = vel[i] * (1 - D[i]) + (tgt[i] - cur[i]) * Math.min(0.85, K[i] * snap); cur[i] += vel[i]; }
        place(l, l.getYaw());
        if (t % 3 == 0) world.spawnParticle(Particle.BLOCK, center(), 24, 1, 1.5, 1, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        if (t % 10 == 0) world.playSound(l, Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 2.5f, 0.5f);
        if (t == 2) world.playSound(l, Sound.ENTITY_RAVAGER_DEATH, SoundCategory.HOSTILE, 2.5f, 0.5f);
        if (t == 56) {
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.4f);
            world.playSound(l, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 3f, 0.4f);
            world.spawnParticle(Particle.BLOCK, l.clone().add(dirOf(l.getYaw()).multiply(3)).add(0, 0.3, 0), 200, 2.5, 0.4, 2.5, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        }
        if (t > 56 && t % 4 == 0) world.spawnParticle(Particle.BLOCK, l.clone().add(dirOf(l.getYaw()).multiply(2.5)), 30, 2, 0.6, 2, 0, Material.STONE.createBlockData());
    }

    @Override
    void removeEverything() {
        quietly(() -> release(true));
        quietly(() -> { for (BlockDisplay r : rocks) if (r.isValid()) r.remove(); rocks.clear(); heldRock = null; });
        super.removeEverything();
    }

    @Override
    void drops(Player p, Location at) {
        int n = (int) c("rewards.gear-parts-min", 2) + random.nextInt((int) Math.max(1, c("rewards.gear-parts-extra", 3)));
        pl.console("giveancientgear " + n + " " + p.getName(), p);
    }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(PUNCH, "Punch", Material.STONE));
        m.add(new MoveSlot(SWEEP, "Backhand Sweep", Material.COBBLESTONE_WALL));
        m.add(new MoveSlot(POUND, "Ground Pound", Material.MOSSY_COBBLESTONE));
        m.add(new MoveSlot(STOMP, "Tremor Stomps", Material.DEEPSLATE));
        m.add(new MoveSlot(BOULDER, "Boulder Toss", Material.COBBLESTONE));
        m.add(new MoveSlot(SPIKES, "Crystal Spikes", Material.AMETHYST_CLUSTER));
        m.add(new MoveSlot(CHARGE, "Rampage", Material.IRON_BLOCK));
        m.add(new MoveSlot(GRAB, "Grab", Material.LEAD));
        if (phase >= 2) { m.add(new MoveSlot(CAVEIN, "Cave-In", Material.POINTED_DRIPSTONE)); m.add(new MoveSlot(OVERGROWTH, "Overgrowth", Material.MOSS_BLOCK)); }
        if (phase >= 3) m.add(new MoveSlot(CORE, "Core Pulse", Material.MAGMA_BLOCK));
        return m;
    }
}
