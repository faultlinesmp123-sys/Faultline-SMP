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
    final Wild.Part bodyP, armL, armR, legL, legR;
    double walk, healLeft, staggerDamage;
    final List<BlockDisplay> rocks = new ArrayList<>();
    Location mark;
    Vector lineDir;
    final Set<UUID> hitThisMove = new HashSet<>();

    StoneGolem(Wild w, Location at, Player by) {
        super(w, "golem", at, by);
        k = c("scale", 1.0);
        Location spot = at.clone();
        mover = world.spawn(spot, IronGolem.class, g -> {
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
        double moving = Math.min(1, v.length() * 8);
        walk += 0.12 + moving * 0.12;
        float yaw = l.getYaw();
        float swing = (float) (Math.sin(walk) * 28 * moving);
        Location hip = l.clone().add(0, 2.3 * k, 0);
        bodyP.pose(hip, yaw, (float) (attack == POUND && at < 24 ? -12 : 0), 0, null);
        legL.pose(offset(hip, yaw, 0, 0, -0.6 * k), yaw, 0, 0, rotZ(swing));
        legR.pose(offset(hip, yaw, 0, 0, 0.6 * k), yaw, 0, 0, rotZ(-swing));
        float armPose = armAngle();
        Location shL = offset(hip, yaw, 0, 2.3 * k, -2.1 * k), shR = offset(hip, yaw, 0, 2.3 * k, 2.1 * k);
        armL.pose(shL, yaw, 0, 0, rotZ(armPose != 0 ? armPose : -swing));
        armR.pose(shR, yaw, 0, 0, rotZ(armPose != 0 ? armPose : swing));
        if (phase == 3 && ticks % 4 == 0) world.spawnParticle(Particle.DUST, center(), 2, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(Color.ORANGE, 1.4f));
        if (moving > 0.3 && ticks % 12 == 0) world.playSound(l, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 2f, 0.5f);
    }

    void walkTo(Location l) {
        mover.getPathfinder().moveTo(l, 1.0);
    }

    void stopWalking() {
        mover.getPathfinder().stopPathfinding();
    }

    /** A turn about its own sideways axis (the part swings forward/back like a leg or an arm). */
    static Quaternionf rotZ(float deg) { return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 0, 0, 1)); }

    float armAngle() {
        if (attack == POUND) return at < 24 ? 160 : at < 30 ? 160 - (at - 24) * 30 : 0;      // up overhead, then down
        if (attack == BOULDER) return at < 18 ? 150 : at < 22 ? 150 - (at - 18) * 45 : 0;     // the throw
        if (attack == OVERGROWTH) return 40;
        return 0;
    }

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
                p.setVelocity(flat(p.getLocation().toVector().subtract(l.toVector())).multiply(1.1).setY(0.8));
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
            for (Player p : near(l, 1.7, 3)) if (hitThisMove.add(p.getUniqueId())) { hit(p, c("moves.crystal-spikes", 10), l.toVector(), Guard.UNBLOCKABLE); p.setVelocity(new Vector(0, 0.9, 0)); }
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
        bodyP.pose(l.clone().add(0, 2.3 * k - Math.min(1.5, t * 0.03), 0), l.getYaw(), Math.min(60, t * 1.2f), 0, null);
    }

    @Override
    void removeEverything() {
        for (BlockDisplay r : rocks) if (r.isValid()) r.remove();
        rocks.clear();
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
