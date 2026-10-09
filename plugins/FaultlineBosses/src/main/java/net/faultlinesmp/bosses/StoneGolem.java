package net.faultlinesmp.bosses;

import org.bukkit.entity.EntityType;

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
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
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
 * THE STONE GOLEM: an old guardian of the lush caves, a mini boss (900 health). It wakes by itself when someone explores
 * a lush cave (or /stonegolem summon). Moss and amethyst grow on it; its eyes glow amber.
 *   Ground Pound (arms up, then a shockwave ring), Boulder Throw, Crystal Spikes (amethyst bursts up along a line),
 *   and below 60% Overgrowth (it roots itself and heals until you hit it hard enough).
 * It drops Ancient Gear Parts (craft four with an amethyst block into an Ancient Core accessory, FaultlineItems).
 * A real (invisible) iron golem walks it around, so it can be hit like any mob.
 */
final class StoneGolem extends Wild.Boss {

    static final int POUND = 1, BOULDER = 2, SPIKES = 3, OVERGROWTH = 4;

    final double k;
    final IronGolem mover;
    final Wild.Part bodyP, headP, armL, armR, legL, legR;
    double walk, healLeft, staggerDamage;
    /** The pose, eased toward a target every tick (degrees; drop in blocks). */
    double aL, aR, sL, sR, lL, lR, bPitch, bRoll, drop, hYaw, hPitch;
    int flinch;
    final List<BlockDisplay> rocks = new ArrayList<>();
    Location mark;
    Vector lineDir;
    final Set<UUID> hitThisMove = new HashSet<>();

    StoneGolem(Wild w, Location at, Player by) {
        super(w, "golem", at, by);
        k = c("scale", 1.0);
        Location spot = at.clone();
        mover = Wild.spawnAs(world, spot, EntityType.IRON_GOLEM, IronGolem.class, g -> {
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
            attr(g, Attribute.ATTACK_DAMAGE, c("melee-damage", 12));
            attr(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            attr(g, Attribute.FOLLOW_RANGE, 40);
        });
        hideForGood(mover);
        hitboxes.add(mover);
        Location l = mover.getLocation();
        bodyP = rig.add("golem_body", k, l);
        headP = rig.add("golem_head", k, l);
        armL = rig.add("golem_arm", k, l);
        armR = rig.add("golem_arm", k, l);
        legL = rig.add("golem_leg", k, l);
        legR = rig.add("golem_leg", k, l);
        world.playSound(l, Sound.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.HOSTILE, 3f, 0.4f);
        world.spawnParticle(Particle.BLOCK, l.clone().add(0, 2, 0), 80, 1.2, 1.5, 1.2, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        cd = 40;
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
            case "phase2" -> ChatColor.DARK_GREEN + "Moss creeps over the Stone Golem's wounds.";
            case "phase3" -> ChatColor.RED + "The Stone Golem's core blazes!";
            case "death" -> ChatColor.DARK_GREEN + "The Stone Golem crumbles into rubble and moss.";
            case "leave" -> ChatColor.DARK_GREEN + "The Stone Golem " + ChatColor.GRAY + "sinks back into the cave wall.";
            default -> null;
        };
    }

    /** It wakes by itself near someone exploring a lush cave. */
    static void naturalSpawns(Wild w) {
        double chance = w.c("golem.natural-chance-per-minute", 0.03);
        if (w.bosses.containsKey("golem") || chance <= 0) return;
        if (System.currentTimeMillis() < w.cooldownUntil.getOrDefault("golem", 0L)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (p.getLocation().getBlock().getBiome() != Biome.LUSH_CAVES) continue;
            if (w.random.nextDouble() >= chance) continue;
            Location spot = openFloor(p.getLocation(), 9, 16);
            if (spot == null) continue;
            w.summon("golem", spot, p);
            return;
        }
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
    @Override
    void bodyTick(List<Player> a) {
        boolean busy = attack == POUND || attack == OVERGROWTH || attack == SPIKES && at < 20;
        mover.setAware(!busy && pilot == null);
        if (pilot != null && !busy) {
            Location want = pilot.getLocation();
            Vector to = want.toVector().subtract(mover.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 9) walkTo(want);
        } else if (!busy && ticks % 20 == 0) {
            Player t = target(a);
            if (t != null) mover.setTarget(t);
        }
        Location l = mover.getLocation();
        Vector v = mover.getVelocity().setY(0);
        double moving = attack >= 0 && attack != BOULDER ? 0 : Math.min(1, v.length() * 8);
        walk += 0.1 + moving * 0.14;
        animate(l, moving);
        if (phase == 3 && ticks % 4 == 0) world.spawnParticle(Particle.DUST, center(), 2, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(Color.ORANGE, 1.4f));
        if (moving > 0.3 && ticks % 12 == 0) {
            world.playSound(l, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 2f, 0.5f);
            world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.1, 0), 6, 0.6, 0.05, 0.6, 0, Material.MOSS_BLOCK.createBlockData());
        }
    }

    // =====================================================================================================
    //  animation: every joint eases toward where the current move wants it (no snapping)
    // =====================================================================================================
    static double ease(double cur, double to, double rate) { return cur + (to - cur) * rate; }

    void animate(Location l, double moving) {
        double swing = Math.sin(walk) * moving;
        // targets: idle breathing + walking
        double tAL = -swing * 22 + Math.sin(ticks * 0.05) * 3, tAR = swing * 22 - Math.sin(ticks * 0.05) * 3;
        double tSL = 6 + Math.sin(ticks * 0.05) * 2, tSR = 6 + Math.sin(ticks * 0.05) * 2;
        double tLL = swing * 26, tLR = -swing * 26;
        double tPitch = moving * 5, tRoll = Math.cos(walk) * 3 * moving;
        double tDrop = Math.sin(ticks * 0.06) * 0.04 - Math.abs(Math.cos(walk)) * 0.12 * moving;
        double rate = 0.22;
        int t = at;
        switch (attack) {
            case POUND -> {
                if (t < 20) { tAL = tAR = 165; tSL = tSR = 18; tPitch = -10; tDrop = 0.1; rate = 0.12; }          // arms up, leaning back
                else if (t < 24) { tAL = tAR = 170; tSL = tSR = 14; tPitch = -12; tDrop = 0.15; }
                else if (t < 34) { tAL = tAR = -8; tSL = tSR = 10; tPitch = 20; tDrop = 0.45; rate = 0.6; }        // SLAM
                else { tAL = tAR = 10; tPitch = 8; tDrop = 0.2; rate = 0.1; }
            }
            case BOULDER -> {
                if (t < 10) { tAR = 55; tSR = 5; tAL = 20; tPitch = 24; tDrop = 0.35; rate = 0.2; }              // bend down for the rock
                else if (t < 18) { tAR = 175; tSR = 12; tAL = 35; tPitch = -14; tDrop = 0.05; rate = 0.18; }      // heave it overhead
                else if (t < 26) { tAR = 70; tSR = 6; tAL = -25; tPitch = 16; tDrop = 0.2; rate = 0.55; }         // THROW
                else { tAR = 15; tAL = 0; tPitch = 4; rate = 0.1; }
            }
            case SPIKES -> {
                if (t < 14) { tAL = tAR = 120; tSL = tSR = 25; tPitch = -6; tDrop = 0.1; rate = 0.15; }
                else if (t < 20) { tAL = tAR = 35; tSL = tSR = 8; tPitch = 26; tDrop = 0.6; rate = 0.6; }          // fists into the ground
                else { tAL = tAR = 38; tPitch = 24; tDrop = 0.55; rate = 0.15; }                                   // held there while they burst
            }
            case OVERGROWTH -> {
                double pulse = Math.sin(t * 0.15);
                tAL = tAR = 30; tSL = tSR = 14; tLL = 38; tLR = -28; tPitch = 18 + pulse * 2; tDrop = 0.75 + pulse * 0.03; rate = 0.08;  // kneels, rooted
            }
            default -> { }
        }
        if (flinch > 0) { flinch--; tPitch -= 7; tAL -= 12; tAR -= 12; rate = Math.max(rate, 0.4); }
        aL = ease(aL, tAL, rate); aR = ease(aR, tAR, rate); sL = ease(sL, tSL, rate); sR = ease(sR, tSR, rate);
        lL = ease(lL, tLL, rate); lR = ease(lR, tLR, rate); bPitch = ease(bPitch, tPitch, rate); bRoll = ease(bRoll, tRoll, rate);
        drop = ease(drop, tDrop, rate);
        // the head looks at whoever it's fighting (within its neck's reach), and down when kneeling
        float yaw = l.getYaw();
        double wantYaw = 0, wantPitch = attack == OVERGROWTH ? 22 : attack == POUND && at >= 24 && at < 34 ? 18 : 0;
        Player look = focus != null && focus.isOnline() && focus.getWorld().equals(world) ? focus : target(active());
        if (look != null) {
            Vector to = look.getEyeLocation().toVector().subtract(l.clone().add(0, 5.2 * k, 0).toVector());
            wantYaw = Math.max(-55, Math.min(55, wrap(yawOf(flat(to)) - yaw)));
            if (attack != OVERGROWTH) wantPitch = Math.max(-25, Math.min(30, pitchOf(to)));
        }
        hYaw = ease(hYaw, wantYaw, 0.15); hPitch = ease(hPitch, wantPitch, 0.15);
        place(l, yaw);
    }

    static double wrap(double d) { d = ((d % 360) + 540) % 360 - 180; return d; }

    /** Put every part where the pose says: legs from the hip, the torso leaning on them, arms and head riding the torso. */
    void place(Location l, float yaw) {
        Location hip = l.clone().add(0, (2.3 - drop) * k, 0);
        double p = Math.toRadians(bPitch);
        bodyP.pose(hip, yaw, (float) bPitch, (float) bRoll, null);
        legL.pose(offset(hip, yaw, 0, 0, -0.65 * k), yaw, 0, 0, rotZ((float) lL));
        legR.pose(offset(hip, yaw, 0, 0, 0.65 * k), yaw, 0, 0, rotZ((float) lR));
        // a point on the torso h blocks up, after the lean (nose-down pitch tips the top forward)
        double sh = 2.45 * k, top = 2.82 * k;
        Location shL = offset(hip, yaw, Math.sin(p) * sh, Math.cos(p) * sh, -2.15 * k);
        Location shR = offset(hip, yaw, Math.sin(p) * sh, Math.cos(p) * sh, 2.15 * k);
        armL.pose(shL, yaw, (float) bPitch, 0, rotZ((float) aL).mul(rotX((float) sL)));
        armR.pose(shR, yaw, (float) bPitch, 0, rotZ((float) aR).mul(rotX((float) -sR)));
        Location neck = offset(hip, yaw, Math.sin(p) * top, Math.cos(p) * top, 0);
        headP.pose(neck, (float) (yaw + hYaw), (float) (bPitch + hPitch), 0, null);
    }

    static Quaternionf rotX(float deg) { return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 1, 0, 0)); }

    void walkTo(Location l) {
        mover.getPathfinder().moveTo(l, 1.0);
    }

    void stopWalking() {
        mover.getPathfinder().stopPathfinding();
    }

    /** A turn about its own sideways axis (the part swings forward/back like a leg or an arm). */
    static Quaternionf rotZ(float deg) { return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 0, 0, 1)); }


    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(POUND, BOULDER, SPIKES, POUND));
        if (phase >= 2 && hp < maxHp * 0.8) pool.add(OVERGROWTH);
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        hitThisMove.clear();
        Location tl = t != null ? t.getLocation() : center().add(dirOf(mover.getLocation().getYaw()).multiply(6));
        switch (move) {
            case POUND -> { stopWalking(); world.playSound(center(), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 3f, 0.4f); }
            case BOULDER -> { mark = tl.clone(); mark.setY(ground(mark)); }
            case SPIKES -> { mark = mover.getLocation(); lineDir = flat(tl.toVector().subtract(mark.toVector())); faceTo(tl); }
            case OVERGROWTH -> {
                healLeft = maxHp * c("overgrowth-heal", 0.06);
                staggerDamage = maxHp * c("overgrowth-break", 0.05);
                stopWalking();
                world.playSound(center(), Sound.BLOCK_MOSS_PLACE, SoundCategory.HOSTILE, 3f, 0.5f);
                say(ChatColor.GRAY + "It roots itself, and moss crawls over its cracks... (hit it hard to stop it)");
            }
            default -> { }
        }
    }

    void faceTo(Location t) {
        Location l = mover.getLocation();
        l.setYaw(yawOf(flat(t.toVector().subtract(l.toVector()))));
        mover.teleport(l);
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case POUND -> pound(a);
            case BOULDER -> boulder();
            case SPIKES -> spikes(a);
            case OVERGROWTH -> overgrowth();
            default -> end();
        }
    }

    void pound(List<Player> a) {
        Location l = mover.getLocation();
        if (at < 24) { if (at % 3 == 0) warnRing(l, 6.5, Color.fromRGB(150, 120, 80)); return; }
        if (at == 27) {
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.5f);
            world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 140, 3, 0.3, 3, 0, Material.MOSSY_COBBLESTONE.createBlockData());
            world.spawnParticle(Particle.EXPLOSION, l, 3, 1.5, 0.3, 1.5, 0);
            for (Player p : near(l, 6.5, 3)) {
                hit(p, c("moves.ground-pound", 14), l.toVector(), Guard.HEAVY);
                Safe.vel(p, flat(p.getLocation().toVector().subtract(l.toVector())).multiply(1.1).setY(0.8));
            }
        }
        if (at > 40) end();
    }

    void boulder() {
        if (at < 18) { if (at % 3 == 0) warnRing(mark, 3, Color.fromRGB(110, 140, 90)); return; }
        if (at == 18) {
            Location from = center().add(0, 2.5 * k, 0);
            BlockDisplay rock = world.spawn(from, BlockDisplay.class, d -> {
                d.setBlock(Material.MOSSY_COBBLESTONE.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(-0.9f, -0.9f, -0.9f), new Quaternionf(), new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf()));
                d.setTeleportDuration(1);
                d.setPersistent(false);
                d.addScoreboardTag(Wild.FX_TAG);
            });
            rocks.add(rock);
            Location target = mark.clone();
            int[] t = {0};
            fx.add(() -> {
                t[0]++;
                double f = t[0] / 20.0;
                Location p = from.clone().add(target.toVector().subtract(from.toVector()).multiply(f));
                p.add(0, Math.sin(Math.PI * f) * 6, 0);
                rock.teleport(p);
                if (f < 1) return false;
                rock.remove();
                world.spawnParticle(Particle.BLOCK, target, 60, 1.2, 0.4, 1.2, 0, Material.MOSSY_COBBLESTONE.createBlockData());
                world.playSound(target, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2.5f, 0.5f);
                for (Player pp : near(target, 3, 4)) hit(pp, c("moves.boulder", 12), target.toVector().add(new Vector(0, 3, 0)), Guard.HEAVY);
                return true;
            });
            world.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.6f);
        }
        if (at > 42) end();
    }

    /** Amethyst bursts up along a line, one block after another. */
    void spikes(List<Player> a) {
        if (at < 16) { if (at % 2 == 0) warnLine(mark, lineDir, 18, Color.fromRGB(170, 100, 230)); return; }
        int step = at - 16;
        if (step % 2 == 0 && step / 2 < 9) {
            double d = 2 + step;
            Location l = mark.clone().add(lineDir.clone().multiply(d));
            l.setY(ground(l));
            BlockDisplay c = world.spawn(l, BlockDisplay.class, e -> {
                e.setBlock(Material.AMETHYST_CLUSTER.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(-0.75f, 0, -0.75f), new Quaternionf(), new Vector3f(1.5f, 2.4f, 1.5f), new Quaternionf()));
                e.setPersistent(false);
                e.addScoreboardTag(Wild.FX_TAG);
                e.setBrightness(new org.bukkit.entity.Display.Brightness(14, 14));
            });
            rocks.add(c);
            later(30, () -> { if (c.isValid()) c.remove(); });
            world.playSound(l, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 2f, 0.6f);
            world.spawnParticle(Particle.BLOCK, l, 10, 0.4, 0.4, 0.4, 0, Material.AMETHYST_BLOCK.createBlockData());
            for (Player p : near(l, 1.7, 3)) if (hitThisMove.add(p.getUniqueId())) { hit(p, c("moves.crystal-spikes", 10), l.toVector(), Guard.UNBLOCKABLE); Safe.vel(p, new Vector(0, 0.9, 0)); }
        }
        if (step > 22) end();
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
        if (amount >= 6 && flinch <= 0) flinch = 6;
        if (attack == OVERGROWTH) {
            staggerDamage -= amount;
            if (staggerDamage <= 0) {
                world.playSound(center(), Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 3f, 0.6f);
                say(ChatColor.GRAY + "The moss tears away!");
                mover.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3, false, false));
                end();
            }
        }
        return amount;
    }

    @Override
    void deathAnim(int t) {
        mover.setAI(false);
        Location l = mover.getLocation();
        if (t % 3 == 0) world.spawnParticle(Particle.BLOCK, center(), 30, 1, 1.5, 1, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        if (t % 12 == 0) world.playSound(l, Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 2.5f, 0.5f);
        if (t == 34) {
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.4f);
            world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 160, 2.5, 0.3, 2.5, 0, Material.MOSSY_COBBLESTONE.createBlockData());
        }
        // it sags to its knees, its arms go limp, then it topples forward into the moss
        double f = Math.min(1, t / 36.0);
        aL = ease(aL, -5, 0.15); aR = ease(aR, 5, 0.15); sL = ease(sL, 4, 0.15); sR = ease(sR, 4, 0.15);
        lL = ease(lL, 45, 0.12); lR = ease(lR, -20, 0.12);
        bPitch = ease(bPitch, 20 + f * f * 65, 0.25); bRoll = ease(bRoll, 6, 0.1);
        drop = ease(drop, 0.8 + f * 0.9, 0.2);
        hPitch = ease(hPitch, 30, 0.1); hYaw = ease(hYaw, 0, 0.1);
        place(l, l.getYaw());
    }

    @Override
    void removeEverything() {
        quietly(() -> { for (BlockDisplay r : rocks) if (r.isValid()) r.remove(); rocks.clear(); });
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
        m.add(new MoveSlot(POUND, "Ground Pound", Material.MOSSY_COBBLESTONE));
        m.add(new MoveSlot(BOULDER, "Boulder Throw", Material.COBBLESTONE));
        m.add(new MoveSlot(SPIKES, "Crystal Spikes", Material.AMETHYST_CLUSTER));
        if (phase >= 2) m.add(new MoveSlot(OVERGROWTH, "Overgrowth", Material.MOSS_BLOCK));
        return m;
    }
}
