package net.faultlinesmp.bosses;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE LEVIATHAN: a sea serpent, 2,400 health (+20% per extra fighter), that lives in the deep ocean and hunts ships.
 * Drop an Abyssal Lure over open ocean (from a ship, a boat or swimming), or meet it at random while sailing the deep
 * ocean at night. Its head and spine breach the waves as it circles you.
 *   Phase 1: Tidal Ram (it dives, a line shows, it surges along it: ships in the way take a big hit), Water Spout
 *            (geysers under you), Bile (poison globs), Abyssal Roar (thrown back, slowed).
 *   Phase 2 (60%): + the Coil (it circles you tight and crushes: ships inside take damage every second),
 *            Tail Sweep, and it calls Drowned up from the deep.
 *   Phase 3 (30%): faster, rams more.
 * Hits near a ship's hull break that part of the ship (FaultlineShips through ShipLink).
 */
final class Leviathan extends Wild.Boss {

    static final int RAM = 1, SPOUT = 2, COIL = 3, ROAR = 4, BILE = 5, TAIL = 6, DROWNED = 7;

    final int segs;
    final double k, gap;
    final Wild.Part head, tail;
    final List<Wild.Part> body = new ArrayList<>();
    final Wild.Chain chain;
    final double[] gaps;
    Location headPos;
    float yaw;
    double surfaceY, orbit;
    boolean submerged;
    // the current move
    Location lineFrom, mark;
    Vector lineDir;
    final List<Location> marks = new ArrayList<>();
    final Set<String> shipsHit = new HashSet<>();
    final Set<UUID> hitThisMove = new HashSet<>();

    Leviathan(Wild w, Location at, Player by) {
        super(w, "leviathan", at, by);
        Location spot = seaSpot(at, 24);
        if (spot == null) throw new IllegalStateException("it needs open sea water at least 8 blocks deep within 24 blocks (in a loaded area)");
        surfaceY = spot.getY();
        k = c("scale", 1.0);
        gap = 2.2 * k;
        segs = (int) Math.max(4, c("segments", 10));
        gaps = new double[segs + 1];
        for (int i = 0; i <= segs; i++) gaps[i] = gap * (i == 0 ? 1.15 : 1.0);
        Vector away = at.toVector().subtract(spot.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        headPos = spot.clone().add(0, -6, 0); // it rises out of the deep
        yaw = yawOf(away.clone().multiply(-1));
        chain = new Wild.Chain(headPos, segs + 1, gap, away); // the body trails back toward where it was called
        head = rig.add("lev_head", k, headPos);
        for (int i = 0; i < segs; i++) body.add(rig.add("lev_body", k * (1.0 - 0.035 * i), headPos));
        tail = rig.add("lev_tail", k * 0.75, headPos);
        hitbox(headPos, (int) Math.round(4 * k), "The Leviathan");
        for (int i = 1; i < segs; i += 2) hitbox(headPos, (int) Math.max(2, Math.round(4 * k * (1.0 - 0.035 * i))), "The Leviathan");
        world.playSound(spot, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 4f, 0.5f);
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, spot, 200, 3, 2, 3, 0.2);
        cd = 80;
    }

    @Override double defHealth() { return 2400; }
    @Override BarColor barColor() { return BarColor.BLUE; }
    @Override String music() { return "minecraft:music_disc.creator"; }
    @Override double musicLength() { return 176; }
    @Override Location center() { return headPos.clone(); }
    @Override boolean valid() { return head.d != null && head.d.isValid(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The sea heaves... something vast rises beneath you.";
            case "phase2" -> ChatColor.DARK_AQUA + "The Leviathan coils, and the deep answers its call.";
            case "phase3" -> ChatColor.RED + "The Leviathan thrashes in fury!";
            case "death" -> ChatColor.DARK_AQUA + "The Leviathan sinks into the dark, never to rise again.";
            case "leave" -> ChatColor.DARK_AQUA + "The Leviathan " + ChatColor.GRAY + "loses interest and sinks back into the deep.";
            default -> null;
        };
    }

    // =====================================================================================================
    //  where it can live
    // =====================================================================================================
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

    boolean water(Location l) {
        return sea(world.getBlockAt(l.getBlockX(), (int) Math.floor(surfaceY) - 1, l.getBlockZ()));
    }

    /**
     * Open sea water: water, and what grows or bubbles in it (kelp, seagrass, bubble columns, waterlogged plants).
     * BUG FIX (1.4.9): only plain WATER counted, so over a kelp forest or a seagrass bed no column reached 8 deep and
     * the Leviathan "couldn't appear there (it needs open water)" in the middle of a deep ocean; swimming, it also took
     * kelp reaching the surface for the coast and turned back.
     */
    static boolean sea(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return !m.isSolid() && b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    /** It may come by itself: someone sailing the deep ocean at night. */
    static void naturalSpawns(Wild w) {
        if (w.bosses.containsKey("leviathan") || w.c("leviathan.natural-chance", 0.004) <= 0) return;
        if (System.currentTimeMillis() < w.cooldownUntil.getOrDefault("leviathan", 0L)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            long t = p.getWorld().getTime();
            if (t < 13000 || t > 23000) continue;
            Biome b = p.getLocation().getBlock().getBiome();
            if (b != Biome.DEEP_OCEAN && b != Biome.DEEP_COLD_OCEAN && b != Biome.DEEP_LUKEWARM_OCEAN && b != Biome.DEEP_FROZEN_OCEAN) continue;
            if (ShipLink.of(p) == null) continue;
            if (w.random.nextDouble() >= w.c("leviathan.natural-chance", 0.004)) continue;
            p.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.ITALIC + "Something enormous passes under the hull...");
            w.summon("leviathan", p.getLocation(), p);
            return;
        }
    }

    // =====================================================================================================
    //  swimming
    // =====================================================================================================
    Location goal() {
        Location c = focus != null && focus.isOnline() && focus.getWorld().equals(world) ? focus.getLocation() : null;
        if (pilot != null) c = pilot.getLocation();
        if (c == null) {
            List<Player> a = active();
            c = a.isEmpty() ? home : a.get(0).getLocation();
        }
        orbit += (phase == 3 ? 0.03 : 0.02);
        double r = c("orbit-radius", 15);
        Location g = c.clone().add(Math.cos(orbit) * r, 0, Math.sin(orbit) * r);
        g.setY(surfaceY - 0.6 + Math.sin(ticks * 0.11) * 0.9);
        return g;
    }

    /** Head toward a spot, turning at most `turn` degrees a tick. */
    void swim(Location to, double speed, float turn) {
        Vector d = to.toVector().subtract(headPos.toVector());
        Vector f = flat(d);
        yaw = turn(yaw, yawOf(f), turn);
        Vector step = dirOf(yaw).multiply(speed);
        double dy = Math.max(-0.35, Math.min(0.35, to.getY() - headPos.getY()));
        Location n = headPos.clone().add(step).add(0, dy, 0);
        if (!water(n) && attack != RAM) { // the coast: turn back out to sea
            yaw += 12;
            n = headPos.clone().add(dirOf(yaw).multiply(speed * 0.5));
        }
        headPos = n;
    }

    @Override
    void bodyTick(List<Player> a) {
        if (attack < 0 || attack == ROAR || attack == BILE || attack == SPOUT || attack == DROWNED) {
            double speed = c("speed", 0.42) * (phase == 3 ? 1.25 : 1);
            if (pilot != null) {
                Location p = pilot.getLocation().clone();
                p.setY(surfaceY - 0.5);
                if (p.distanceSquared(headPos) > 9) swim(p, speed, 9);
            } else swim(goal(), speed, 6);
        }
        place();
        if (ticks % 30 == 0) world.playSound(headPos, Sound.ENTITY_DOLPHIN_SPLASH, SoundCategory.HOSTILE, 2f, 0.4f);
        if (ticks % 3 == 0) for (Location p : chain.pts) if (Math.abs(p.getY() - surfaceY) < 1.4)
            world.spawnParticle(Particle.SPLASH, p.getX(), surfaceY, p.getZ(), 3, 0.8, 0.1, 0.8, 0);
    }

    void place() {
        chain.lead(headPos, gaps);
        Vector hf = chain.facing(0);
        float hy = yawOf(flat(hf)), hp = attack == ROAR ? -35 : pitchOf(hf.lengthSquared() < 1e-4 ? dirOf(yaw) : hf);
        head.pose(headPos, yaw, Math.max(-40, Math.min(40, hp)), 0, null);
        // The spine breaches: segments near the surface rise in travelling humps (a wave running down the body), so the
        // body shows above the water and not just the head (before 1.4.7 the whole body swam ~1 block under the surface).
        List<Location> shown = new ArrayList<>(chain.pts.size());
        shown.add(headPos);
        for (int i = 1; i < chain.pts.size(); i++) shown.add(chain.pts.get(i).clone().add(0, hump(i, chain.pts.get(i)), 0));
        for (int i = 0; i < segs; i++) {
            Vector f = shown.get(i).toVector().subtract(shown.get(i + 1).toVector());
            body.get(i).pose(shown.get(i + 1), yawOf(flat(f)), Math.max(-50, Math.min(50, pitchOf(f))), (float) (Math.sin(ticks * 0.15 + i * 0.6) * 6), null);
        }
        Vector tf = shown.get(segs).toVector().subtract(shown.get(segs + 1).toVector());
        tail.pose(shown.get(segs + 1), yawOf(flat(tf)), Math.max(-50, Math.min(50, pitchOf(tf))), (float) (Math.sin(ticks * 0.2) * 20), null);
        // hitboxes: the head, then every other segment
        if (!hitboxes.isEmpty()) hitboxes.get(0).teleport(headPos.clone().add(0, -1.0 * k, 0));
        for (int h = 1, i = 1; h < hitboxes.size() && i < chain.pts.size(); h++, i += 2)
            hitboxes.get(h).teleport(chain.pts.get(i).clone().add(0, -0.9 * k, 0));
    }

    /**
     * How far segment i is lifted to break the surface: a wave of humps travelling down the body while it swims at the
     * surface. Nothing while it's down deep (diving for a Tidal Ram, rising from the deep).
     */
    double hump(int i, Location p) {
        if (submerged || dying) return 0;
        double below = surfaceY - p.getY(); // how deep that point swims
        if (below > 3.5 || below < -1) return 0;
        double fade = Math.min(1, (3.5 - below) / 2.0);
        double wave = Math.max(0, Math.sin(i * 0.95 - ticks * 0.14));
        return fade * (0.2 * k + wave * c("hump-height", 1.5) * k);
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(RAM, SPOUT, BILE, ROAR));
        if (phase >= 2) { pool.add(COIL); pool.add(TAIL); if (minions.size() < 4) pool.add(DROWNED); }
        if (phase == 3) { pool.add(RAM); pool.add(RAM); }
        if (attack >= 0) return -1;
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        marks.clear(); shipsHit.clear(); hitThisMove.clear();
        Location tl = t != null ? t.getLocation() : headPos.clone().add(dirOf(yaw).multiply(10));
        switch (move) {
            case RAM -> {
                submerged = true;
                Vector d = flat(tl.toVector().subtract(headPos.toVector()));
                lineFrom = tl.clone().add(d.clone().multiply(-18));
                lineFrom.setY(surfaceY - 5);
                lineDir = d;
                world.playSound(headPos, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            case SPOUT -> {
                for (Player p : active()) marks.add(p.getLocation().clone());
                for (int i = 0; i < 2 + phase; i++) marks.add(tl.clone().add(random.nextGaussian() * 6, 0, random.nextGaussian() * 6));
                for (Location m : marks) m.setY(Math.max(surfaceY, ground(m)));
                world.playSound(tl, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, SoundCategory.HOSTILE, 3f, 0.6f);
            }
            case COIL -> {
                mark = tl.clone();
                mark.setY(surfaceY - 0.6);
                world.playSound(headPos, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 3f, 0.7f);
            }
            case ROAR -> world.playSound(headPos, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 4f, 0.4f);
            case TAIL -> world.playSound(chain.pts.get(chain.pts.size() - 1), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 3f, 0.5f);
            case DROWNED -> world.playSound(headPos, Sound.ENTITY_DROWNED_AMBIENT_WATER, SoundCategory.HOSTILE, 3f, 0.5f);
            default -> { }
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case RAM -> ram(a);
            case SPOUT -> spout();
            case COIL -> coil(a);
            case ROAR -> roar(a);
            case BILE -> bile(a);
            case TAIL -> tailSweep(a);
            case DROWNED -> drowned(a);
            default -> end();
        }
    }

    /** Hits a ship near this spot once per move (a fraction of its health; the parts there break). */
    void shipHit(Location at, double frac, double reach) {
        for (Player p : active()) {
            ShipLink.Vessel v = ShipLink.of(p);
            if (v == null) continue;
            String id = v.ship != null ? v.ship.toString() : v.boat.getUniqueId().toString();
            if (shipsHit.contains(id) || v.distanceToHull(at) > reach) continue;
            shipsHit.add(id);
            if (v.hit(at, frac)) for (Player q : active()) if (v.same(ShipLink.of(q)) || v.boat != null && q.getVehicle() == v.boat)
                q.sendActionBar(legacy(ChatColor.RED + "The Leviathan slams into the " + v.title() + "!"));
        }
    }

    void ram(List<Player> a) {
        int wind = phase == 3 ? 24 : 34;
        if (at <= wind) {
            swim(lineFrom, 0.7, 12); // dive and get into position
            headPos.setY(Math.max(surfaceY - 6, headPos.getY() - 0.3));
            if (at % 2 == 0) warnLine(lineFrom, lineDir, 40, Color.fromRGB(40, 200, 255));
            return;
        }
        if (at == wind + 1) { headPos = lineFrom.clone(); yaw = yawOf(lineDir); world.playSound(headPos, Sound.ENTITY_GENERIC_SPLASH, SoundCategory.HOSTILE, 4f, 0.5f); }
        submerged = false;
        headPos = headPos.clone().add(lineDir.clone().multiply(c("ram-speed", 1.25)));
        headPos.setY(Math.min(surfaceY + 0.4, headPos.getY() + 0.5));
        world.spawnParticle(Particle.SPLASH, headPos, 40, 1.2, 0.6, 1.2, 0.3);
        for (Player p : near(headPos, 3.2 * k, 5)) if (hitThisMove.add(p.getUniqueId())) {
            hit(p, c("moves.ram", 15), headPos.toVector(), Guard.HEAVY);
            p.setVelocity(lineDir.clone().multiply(1.2).setY(0.9));
        }
        shipHit(headPos, c("ship-damage.ram", 0.1), 4 * k);
        if (at > wind + 34) end();
    }

    void spout() {
        if (at < 30) { if (at % 3 == 0) for (Location m : marks) { warnRing(m, 2.2, Color.AQUA); world.spawnParticle(Particle.BUBBLE_POP, m, 6, 1, 0.2, 1, 0.05); } return; }
        if (at == 30) for (Location m : marks) {
            world.spawnParticle(Particle.SPLASH, m.clone().add(0, 2, 0), 200, 0.6, 3, 0.6, 0.4);
            world.spawnParticle(Particle.CLOUD, m.clone().add(0, 4, 0), 30, 0.6, 2, 0.6, 0.05);
            world.playSound(m, Sound.ENTITY_GENERIC_SPLASH, SoundCategory.HOSTILE, 3f, 0.6f);
            for (Player p : near(m, 2.3, 4)) { hit(p, c("moves.spout", 9), m.toVector(), Guard.UNBLOCKABLE); p.setVelocity(new Vector(0, 1.4, 0)); }
            shipHit(m, c("ship-damage.spout", 0.03), 2.5);
        }
        if (at > 40) end();
    }

    void coil(List<Player> a) {
        double r = c("coil-radius", 7);
        double ang = at * 0.16;
        Location to = mark.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
        to.setY(surfaceY - 0.4);
        swim(to, 0.9, 25);
        if (at % 20 == 0) {
            for (Player p : a) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - mark.getX(), l.getZ() - mark.getZ()) < r + 1) {
                    hit(p, c("moves.coil", 4), mark.toVector(), Guard.UNBLOCKABLE);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 2));
                }
            }
            shipsHit.clear();
            shipHit(mark, c("ship-damage.coil-per-second", 0.02), r + 2);
            world.playSound(mark, Sound.BLOCK_WOOD_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
        }
        if (at > 100) end();
    }

    void roar(List<Player> a) {
        if (at < 20) { headPos.setY(Math.min(surfaceY + 2.5, headPos.getY() + 0.15)); return; }
        if (at == 20) {
            world.playSound(headPos, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4f, 0.5f);
            world.spawnParticle(Particle.SONIC_BOOM, headPos, 1);
            for (Player p : a) {
                Vector to = p.getLocation().toVector().subtract(headPos.toVector());
                if (to.lengthSquared() > 20 * 20) continue;
                hit(p, c("moves.roar", 6), headPos.toVector(), Guard.UNBLOCKABLE);
                p.setVelocity(flat(to).multiply(1.4).setY(0.5));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            }
        }
        if (at > 34) end();
    }

    void bile(List<Player> a) {
        if (at % 8 == 0 && at <= 16) {
            for (Player p : a) {
                if (p.getLocation().distanceSquared(headPos) > 40 * 40) continue;
                Location from = headPos.clone().add(0, 1.2 * k, 0);
                Vector v = p.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
                double dist = v.length();
                v.normalize().multiply(Math.min(2.2, 0.8 + dist * 0.05)).add(new Vector(0, 0.18 + dist * 0.01, 0));
                Snowball s = world.spawn(from, Snowball.class, b -> {
                    b.setItem(new ItemStack(Material.SLIME_BALL));
                    b.addScoreboardTag(Wild.FX_TAG);
                    b.addScoreboardTag("faultline_leviathan_bile");
                });
                s.setVelocity(v);
                if (source() != null) s.setShooter(source());
            }
            world.playSound(headPos, Sound.ENTITY_LLAMA_SPIT, SoundCategory.HOSTILE, 3f, 0.4f);
        }
        if (at > 30) end();
    }

    /** Bile lands: a lingering cloud of poison. */
    static void bileLands(Entity proj) {
        Location l = proj.getLocation();
        l.getWorld().spawn(l, AreaEffectCloud.class, c -> {
            c.setRadius(2.6f);
            c.setDuration(100);
            c.setRadiusPerTick(-0.01f);
            c.setColor(Color.fromRGB(90, 200, 60));
            c.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 1), true);
            c.addScoreboardTag(Wild.FX_TAG);
        });
    }

    void tailSweep(List<Player> a) {
        Location tl = chain.pts.get(chain.pts.size() - 1);
        if (at < 18) { if (at % 3 == 0) warnRing(tl.clone().add(0, Math.max(0, surfaceY - tl.getY()), 0), 7.5, Color.RED); return; }
        if (at == 18) {
            world.playSound(tl, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.6f);
            world.spawnParticle(Particle.SPLASH, tl, 200, 4, 0.5, 4, 0.5);
            for (Player p : near(tl.clone().add(0, Math.max(0, surfaceY - tl.getY()), 0), 7.5, 5)) {
                hit(p, c("moves.tail", 12), tl.toVector(), Guard.HEAVY);
                p.setVelocity(flat(p.getLocation().toVector().subtract(tl.toVector())).multiply(1.5).setY(0.7));
            }
            shipHit(tl, c("ship-damage.tail", 0.06), 6);
        }
        if (at > 26) end();
    }

    void drowned(List<Player> a) {
        if (at == 10) {
            int n = 2 + Math.min(4, a.size());
            for (int i = 0; i < n; i++) {
                Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                Location l = (t != null ? t.getLocation() : headPos).clone().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
                l.setY(Math.max(surfaceY - 1, ground(l)));
                Drowned d = minion(l, Drowned.class, ChatColor.DARK_AQUA + "Drowned of the Deep", 30, m -> {
                    m.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                    m.getEquipment().setItemInMainHandDropChance(0);
                    m.setShouldBurnInDay(false);
                });
                if (t != null) d.setTarget(t);
                world.spawnParticle(Particle.BUBBLE_POP, l, 30, 0.5, 1, 0.5, 0.1);
            }
        }
        if (at > 20) end();
    }

    @Override
    void deathAnim(int t) {
        headPos = headPos.clone().add(0, -0.08, 0);
        place();
        if (t % 3 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, headPos, 30, 2, 1, 2, 0.1);
        if (t % 10 == 0) world.playSound(headPos, Sound.ENTITY_ELDER_GUARDIAN_DEATH, SoundCategory.HOSTILE, 2.5f, 0.5f);
    }

    @Override
    double damageTaken(double amount) {
        return submerged ? amount * 0.5 : amount; // diving: harder to hurt
    }

    @Override
    void drops(Player p, Location at) {
        maybeDrop(p, at, "tidebreaker", c("rewards.tidebreaker-chance", 0.25));
    }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(RAM, "Tidal Ram", Material.PRISMARINE_SHARD));
        m.add(new MoveSlot(SPOUT, "Water Spout", Material.WATER_BUCKET));
        m.add(new MoveSlot(BILE, "Bile", Material.SLIME_BALL));
        m.add(new MoveSlot(ROAR, "Abyssal Roar", Material.NAUTILUS_SHELL));
        if (phase >= 2) {
            m.add(new MoveSlot(COIL, "The Coil", Material.KELP));
            m.add(new MoveSlot(TAIL, "Tail Sweep", Material.TRIDENT));
            m.add(new MoveSlot(DROWNED, "Call the Drowned", Material.DROWNED_SPAWN_EGG));
        }
        return m;
    }
}
