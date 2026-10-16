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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE DARK OCEAN'S BOSSES (Bosses 1.6.0; FaultlineDeep's world). All fight underwater in full 3D: they swim around you,
 * up and down, through the open water (never into rock). Built on the wild bosses (Wild.java: the rig, the fight loop,
 * music, boss form, loot), each with the moves of the game it's inspired by plus two new ones:
 *
 *  THE BRINE SCOURGE (boss, Aquatic Scourge; the Kraken's music, which IS that theme): a huge sea worm.
 *     Charge, Acid Spit, Tooth Spray, Sulphur Bubbles; NEW Brine Geysers (scalding columns from the sea floor under you),
 *     Corroding Coil (it circles you, shrinking a ring of acid).
 *  THE UMBRAL LEVIATHAN (boss, Below Zero's Shadow Leviathan): a serpent of shadow with glowing eyes.
 *     Shadow Charge, the Grab (it seizes you: hit it hard to break free), Vanish (into the dark, out behind you), Shriek;
 *     NEW Eclipse Coil (a ring of shadow closes: get out before it does), Abyssal Echo (sonar marks you, lances follow).
 *  CORALIA, SIREN OF THE SHOALS (mini boss, Cuphead's Cala Maria): a giant mermaid.
 *     Thralls of the Shoal, Petrifying Gaze (LOOK AWAY when her eyes glow), Eel Lash, Bubble Barrage;
 *     NEW Siren Song (it pulls you to her), Coral Spires (bursting up from the floor under you).
 *  OLD INKJAW (mini boss, 500 health): a giant octopus.
 *     Tentacle Slam, Ink Cloud, the Grip, Beak Bite; NEW Ink Jet (a dash leaving a trail of ink), Tentacle Cage.
 *  THE GRANDMAW CLAM (mini boss, the Calamity mod's Giant Clam; the Sunlit Sea only): a clam the size of a house.
 *     Shell Up (85% less damage while shut), Slam (it leaps and crashes), Pearl Ring, Pearl Shot;
 *     NEW Riptide Snap (it sucks you in and snaps shut), Brine Bubble (traps you in a rising bubble).
 *  THE LAMENT (mini boss, Sea of Solitude's great fish): a huge fish made of grief and dark water.
 *     Lunge, the Hunt (gone into the dark... then from behind), Wail, Whispers (Hollow Kin);
 *     NEW Sorrow Tide (a wave that drives you away), Light Eater (it snuffs your lights and hunts whoever carries one).
 *
 * Summon items come from the Dark Ocean's creatures (FaultlineDeep), and each can turn up on its own (0.1% per player
 * every 5 minutes in the Dark Ocean: Wild.deepSpawns). Minis roll a Dark Loot rare 10%, bosses 50%.
 */
final class DeepBosses {
    private DeepBosses() { }

    static final String SHOT_TAG = "faultline_deep_shot";
    static final List<String> KINDS = List.of("scourge", "umbral", "coralia", "inkjaw", "grandmaw", "lament");
    static final Set<String> BOSSES = Set.of("scourge", "umbral"); // the rest are mini bosses

    static String world(FaultlineBosses pl) { return pl.getConfig().getString("wild.deep-world", "faultline_deep"); }

    /** Sunlit Sea (y >= 35) or deeper. */
    static boolean sunlit(Location l) { return l.getY() >= 35; }

    /** Out in the open sea: nothing solid between here and the surface (not in the Crust's caves or the Abyss). */
    static boolean openSea(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY() + 1; y < w.getMaxHeight(); y++) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isAir()) return true;
            if (m.isSolid()) return false;
        }
        return true;
    }

    /** The sea floor within `max` blocks under l, or null. */
    static Location floorNear(Location l, int max) {
        Location c = l.clone();
        for (int i = 0; i < max; i++, c.add(0, -1, 0)) if (c.getBlock().getType().isSolid()) return c;
        return null;
    }

    // =====================================================================================================
    //  the shared swimmer
    // =====================================================================================================
    abstract static class Swimmer extends Wild.Boss {
        Location pos;
        float yaw, pitch;
        final double k;
        double orbit;
        final Set<UUID> hitOnce = new HashSet<>();

        Swimmer(Wild w, String kind, Location at, Player by, double defScale) {
            super(w, kind, at, by);
            k = c("scale", defScale);
            Location spot = openWater(at, 7, 15, Math.max(1, (int) Math.round(1.3 * k)));
            if (spot == null) throw new IllegalStateException("it needs open water all around (a big enough pocket of sea) near you");
            pos = spot;
            yaw = yawOf(flat(at.toVector().subtract(spot.toVector())));
            cd = 60;
        }

        @Override Location center() { return pos.clone(); }

        static boolean wet(Block b) { return Leviathan.sea(b); }

        /** Water at l and at r blocks out along each axis. */
        static boolean open(Location l, int r) {
            World w = l.getWorld();
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            if (!wet(w.getBlockAt(x, y, z))) return false;
            int[][] d = {{r, 0, 0}, {-r, 0, 0}, {0, r, 0}, {0, -r, 0}, {0, 0, r}, {0, 0, -r}};
            for (int[] o : d) {
                if (!w.isChunkLoaded((x + o[0]) >> 4, (z + o[2]) >> 4)) return false;
                if (!wet(w.getBlockAt(x + o[0], y + o[1], z + o[2]))) return false;
            }
            return true;
        }

        /** A pocket of open water between min and max blocks from l (near its height). */
        static Location openWater(Location l, double min, double max, int r) {
            Random rnd = new Random();
            for (int i = 0; i < 60; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = min + rnd.nextDouble() * (max - min);
                Location c = l.clone().add(Math.cos(a) * d, (rnd.nextDouble() - 0.4) * 6, Math.sin(a) * d);
                if (open(c, r)) return c.getBlock().getLocation().add(0.5, 0.5, 0.5);
            }
            for (int i = 0; i < 20; i++) { // tighter, any height
                Location c = l.clone().add(rnd.nextGaussian() * 4, rnd.nextGaussian() * 3, rnd.nextGaussian() * 4);
                if (open(c, Math.max(1, r - 1))) return c.getBlock().getLocation().add(0.5, 0.5, 0.5);
            }
            return null;
        }

        int room() { return Math.max(1, (int) Math.round(1.1 * k)); }

        /** A direction, safe when the two points are the same (normalising 0 gives NaN, which real Paper throws on). */
        static Vector unit(Vector v) { return v.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : v.normalize(); }

        static Vector dir3(float yaw, float pitch) {
            double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
            return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        }

        /** Swim toward a spot: turning at most `turn` degrees a tick, never into rock (it slides up or around instead). */
        void swimTo(Location to, double speed, float turn) {
            Vector d = to.toVector().subtract(pos.toVector());
            if (d.lengthSquared() < speed * speed) { if (open(to, room())) pos = to.clone(); return; }
            yaw = turn(yaw, yawOf(flat(d)), turn);
            float wantPitch = Math.max(-55, Math.min(55, pitchOf(d)));
            pitch += Math.max(-turn, Math.min(turn, wantPitch - pitch));
            Location n = pos.clone().add(dir3(yaw, pitch).multiply(speed));
            if (open(n, room())) { pos = n; return; }
            for (Vector alt : new Vector[]{new Vector(0, speed, 0), dirOf(yaw + 70).multiply(speed), dirOf(yaw - 70).multiply(speed), new Vector(0, -speed, 0)}) {
                Location a = pos.clone().add(alt);
                if (open(a, room())) { pos = a; yaw += alt.getY() == 0 ? 10 : 0; return; }
            }
        }

        /** Where to be when not mid-move: circling whoever it's after (or the pilot), a little above them. */
        Location circle(double r, double up, double speedRad) {
            Location c = pilot != null ? pilot.getLocation() : focus != null && focus.isOnline() && focus.getWorld().equals(world) ? focus.getLocation() : null;
            if (c == null) { List<Player> a = active(); c = a.isEmpty() ? home : a.get(0).getLocation(); }
            orbit += speedRad;
            return c.clone().add(Math.cos(orbit) * r, up + Math.sin(ticks * 0.05) * 2, Math.sin(orbit) * r);
        }

        Player targetOr(Player t) {
            if (t != null && t.isOnline() && t.getWorld().equals(world)) return t;
            List<Player> a = active();
            return a.isEmpty() ? null : a.get(random.nextInt(a.size()));
        }

        /** Players inside a sphere. */
        List<Player> within(Location c, double r) {
            List<Player> out = new ArrayList<>();
            for (Player p : active()) if (p.getLocation().add(0, 0.9, 0).distanceSquared(c) <= r * r) out.add(p);
            return out;
        }

        /** Each player at most once per move. */
        void strike(Location c, double r, double dmg, Guard g, Vector push) {
            for (Player p : within(c, r)) if (hitOnce.add(p.getUniqueId())) {
                hit(p, dmg, c.toVector(), g);
                if (push != null) Safe.vel(p, push.clone());
            }
        }

        /** The sea floor under a spot (the first solid block going down, +1), or the spot itself. */
        Location floorUnder(Location l, int max) {
            Location c = l.clone();
            for (int i = 0; i < max; i++) {
                if (c.getBlock().getType().isSolid()) return c.add(0, 1, 0).getBlock().getLocation().add(0.5, 0, 0.5);
                c.add(0, -1, 0);
            }
            return l.clone();
        }

        /** A 3D warning line of particles. */
        void warn3d(Location from, Vector dir, double len, Color col) {
            Vector d = dir.clone().normalize();
            for (double t = 0; t < len; t += 0.8) {
                Location l = from.clone().add(d.clone().multiply(t));
                world.spawnParticle(Particle.DUST, l, 1, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(col, 1.5f));
            }
        }

        void sphereFx(Location c, double r, Particle p, int n) {
            for (int i = 0; i < n; i++) {
                double u = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - u * u);
                world.spawnParticle(p, c.getX() + s * Math.cos(a) * r, c.getY() + u * r, c.getZ() + s * Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }

        /**
         * A bolt that flies straight through the water (thrown projectiles barely move underwater): moved every tick,
         * hits the first player it touches. homing > 0 turns it toward its target that much a tick.
         */
        final Map<UUID, Integer> boltHit = new HashMap<>();

        void bolt(Location from, Vector vel, int life, double radius, double dmg, Particle trail, ItemStack look, Player homeOn, double homing, Consumer<Player> onHit) {
            Location p = from.clone();
            Vector v = vel.clone();
            ItemDisplay d = look == null ? null : world.spawn(p, ItemDisplay.class, e -> {
                e.setItemStack(look);
                e.setPersistent(false);
                e.addScoreboardTag(Wild.FX_TAG);
                e.setTeleportDuration(1);
                float s = (float) Math.max(0.5, radius);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            });
            int[] t = {0};
            fx.add(() -> {
                if (dying || ++t[0] > life) { if (d != null) d.remove(); return true; }
                if (homeOn != null && homing > 0 && homeOn.isOnline() && homeOn.getWorld().equals(world)) {
                    Vector want = homeOn.getLocation().add(0, 1, 0).toVector().subtract(p.toVector());
                    if (want.lengthSquared() > 1e-4) { // (right on top of them: normalising 0 = NaN, which Paper throws on)
                        want.normalize().multiply(v.length());
                        v.multiply(1 - homing).add(want.multiply(homing));
                    }
                }
                p.add(v);
                // off into unloaded water, or a broken number: gone (getBlock would load / generate the chunk)
                if (!Double.isFinite(p.getX() + p.getY() + p.getZ()) || !world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) { if (d != null) d.remove(); return true; }
                if (p.getBlock().getType().isSolid()) { world.spawnParticle(Particle.BUBBLE_POP, p, 6, 0.2, 0.2, 0.2, 0.02); if (d != null) d.remove(); return true; }
                if (trail != null) world.spawnParticle(trail, p, 2, 0.05, 0.05, 0.05, 0);
                if (d != null && d.isValid()) d.teleport(p);
                for (Player pl2 : active()) if (pl2.getLocation().add(0, 0.9, 0).distanceSquared(p) < (radius + 0.6) * (radius + 0.6)) {
                    // a volley that lands all at once (point blank) counts once every 8 ticks, like vanilla's hurt time
                    Integer last = boltHit.get(pl2.getUniqueId());
                    if (last == null || ticks - last >= 8) { boltHit.put(pl2.getUniqueId(), ticks); hit(pl2, dmg, p.toVector(), Guard.BLOCKABLE); }
                    if (onHit != null) onHit.accept(pl2);
                    world.spawnParticle(Particle.BUBBLE_POP, p, 10, 0.3, 0.3, 0.3, 0.05);
                    if (d != null) d.remove();
                    return true;
                }
                return false;
            });
        }

        /** A ring of particles around a vertical axis. */
        void ring3(Location c, double r, Particle p) {
            int n = (int) Math.max(10, r * 5);
            for (int i = 0; i < n; i++) { double a = i * Math.PI * 2 / n; world.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0); }
        }

        void darkLoot(Player p, double rareChance, int bags) {
            pl.console("darkloot rare " + p.getName() + " " + c("rewards.dark-loot-rare-chance", rareChance), p);
            int n = (int) c("rewards.dark-loot-bags", bags);
            if (n > 0) pl.console("darkloot bag " + n + " " + p.getName(), p);
        }

        @Override void deathAnim(int t) {
            pos = pos.clone().add(0, -0.06, 0);
            pose(t);
            if (t % 3 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos, 20, 1.5 * k, 1, 1.5 * k, 0.1);
            if (t % 10 == 0) world.playSound(pos, Sound.ENTITY_ELDER_GUARDIAN_DEATH, SoundCategory.HOSTILE, 2.5f, 0.6f);
        }

        /** Draw the rig where the body is now (also called while dying). */
        abstract void pose(int deathT);

        @Override boolean valid() { return !rig.parts.isEmpty() && (rig.parts.get(0).d != null && rig.parts.get(0).d.isValid() || rig.parts.get(0).stand != null && rig.parts.get(0).stand.isValid()); }
    }

    /** A long body (worm, serpent, fish): a head that leads and a chain of segments that follow it. */
    abstract static class Serpent extends Swimmer {
        final List<Wild.Part> segs = new ArrayList<>();
        Wild.Part head, tail;
        Wild.Chain chain;
        double[] gaps;
        double aJaw;

        Serpent(Wild w, String kind, Location at, Player by, double defScale) { super(w, kind, at, by, defScale); }

        void build(String headModel, String segModel, String tailModel, int n, double gap, double taper, int hitEvery, String name) {
            gaps = new double[n + 1];
            for (int i = 0; i <= n; i++) gaps[i] = gap * k * (1 - taper * i * 0.5);
            Vector back = dirOf(yaw).multiply(-1);
            chain = new Wild.Chain(pos, n + 1, gap * k, back);
            head = rig.add(headModel, k, pos);
            for (int i = 0; i < n; i++) segs.add(rig.add(segModel, k * (1 - taper * i), pos));
            tail = rig.add(tailModel, k * (1 - taper * n), pos);
            hitbox(pos, Math.max(2, (int) Math.round(3 * k)), name);
            for (int i = 2; i < n; i += hitEvery) hitbox(pos, Math.max(2, (int) Math.round(2.6 * k * (1 - taper * i))), name);
        }

        @Override
        void pose(int deathT) {
            chain.lead(pos, gaps);
            double roll = deathT > 0 ? Math.min(160, deathT * 3) : Math.sin(ticks * 0.08) * 6;
            float hp = Math.max(-60, Math.min(60, pitch)) - (float) (flinchAmt() * 15);
            head.pose(pos, yaw, hp, (float) roll, null);
            for (int i = 0; i < segs.size(); i++) {
                Location a = chain.pts.get(i), b = chain.pts.get(i + 1);
                Vector f = a.toVector().subtract(b.toVector());
                float sway = (float) (Math.sin(ticks * 0.15 - i * 0.7) * 8);
                segs.get(i).pose(b, yawOf(flat(f)) + sway * 0.3f, Math.max(-60, Math.min(60, pitchOf(f))), (float) (roll * Math.min(1, 0.3 + i * 0.08)) + sway, null);
            }
            Location tb = chain.pts.get(chain.pts.size() - 1), ta = chain.pts.get(chain.pts.size() - 2);
            Vector tf = ta.toVector().subtract(tb.toVector());
            tail.pose(tb, yawOf(flat(tf)) + (float) (Math.sin(ticks * 0.2) * 20), Math.max(-60, Math.min(60, pitchOf(tf))), (float) roll, null);
            if (!hitboxes.isEmpty()) hitboxes.get(0).teleport(pos.clone().add(0, -0.9 * k, 0));
            int h = 1;
            for (int i = 2; i < chain.pts.size() && h < hitboxes.size(); i += Math.max(1, (chain.pts.size() - 2) / Math.max(1, hitboxes.size() - 1)), h++)
                hitboxes.get(h).teleport(chain.pts.get(i).clone().add(0, -0.8 * k, 0));
        }

        /** Body segments near a spot hurt (used by coils and charges). */
        void bodyStrike(double r, double dmg) {
            for (int i = 1; i < chain.pts.size(); i += 2) strike(chain.pts.get(i), r, dmg, Guard.BLOCKABLE, null);
        }
    }

    // =====================================================================================================
    //  THE BRINE SCOURGE
    // =====================================================================================================
    static final class Scourge extends Serpent {
        /*
         * After Infernum's Aquatic Scourge. Phase 1 runs a fixed cycle: Radiation Pulse, Rubble Charges, Radioactive
         * Breath, Rubble Charges, Toxic Bubble Spin. Phase 2 (60%) runs the same cycle harder (more globs, more rubble,
         * a longer spin, acid clouds that linger). At 25% the sea's acidity spikes: it swims up to the surface under a
         * storm and fights there: Frenzied Charges, Acid Rain, Acid Rain, Sulphurous Typhoon. In phases 1-2 it has to be
         * fought UNDER the water: anyone above the surface makes it enraged and invincible. Sulphuric Poisoning (a meter
         * per fighter) fills from its pulses, globs and bubbles; fresh-water bubbles rising from below clear some of it.
         */
        static final int PULSE = 1, RUBBLE = 2, BREATH = 3, SPIN = 4, FRENZY = 5, ACID_RAIN = 6, TYPHOON = 7, ASCEND = 8;
        static final int[] CYCLE = {PULSE, RUBBLE, BREATH, RUBBLE, SPIN};
        static final int[] FINAL = {FRENZY, ACID_RAIN, ACID_RAIN, TYPHOON};
        int step;
        final Map<UUID, Double> poison = new HashMap<>();
        final Set<UUID> storm = new HashSet<>();
        boolean enraged, ascended;
        int finalTicks;
        // move state
        Vector lineDir;
        Location way, typhoon;
        int charge, chargeT, stunT;
        double spinAng;
        final Wild.Part headB, mandL, mandR;
        double aMand = 12, wantMand = 12;

        Scourge(Wild w, Location at, Player by) {
            super(w, "scourge", at, by, 1.8);
            build("scourge_head", "scourge_body", "scourge_tail", (int) c("segments", 18), 1.9, 0.02, 3, "The Brine Scourge");
            headB = rig.add("scourge_head_b", k, pos);  // Bedrock: one head with the mandibles as animated bones
            mandL = rig.add("scourge_mandible", k, pos); // Java: the two great mandibles, opening and snapping
            mandR = rig.add("scourge_mandible_r", k, pos);
            world.playSound(pos, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 4f, 0.4f);
            world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos, 200, 3, 3, 3, 0.2);
        }

        @Override double defHealth() { return 2400; }
        @Override double phaseAt(int p) { return p == 2 ? c("phase2-at", 0.6) : c("phase3-at", 0.25); }
        @Override BarColor barColor() { return BarColor.GREEN; }
        @Override String music() { return "faultline:kraken.music"; } // "Outcast of the Sulphurous Seas"
        @Override double musicLength() { return 397.22; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "The water turns sour and yellow... THE BRINE SCOURGE uncoils from the deep!";
                case "phase2" -> ChatColor.DARK_GREEN + "The Brine Scourge thrashes. Its acid lingers in the water now.";
                case "phase3" -> ChatColor.RED + "" + ChatColor.BOLD + "The sea's acidity spikes! " + ChatColor.GREEN + "The Brine Scourge rises for the surface. Follow it up!";
                case "death" -> ChatColor.DARK_GREEN + "The Brine Scourge's corpse dissolves into the acid it came from.";
                case "leave" -> ChatColor.DARK_GREEN + "The Brine Scourge " + ChatColor.GRAY + "sinks back into the sulphur dark.";
                default -> null;
            };
        }

        double spd() { return (enraged ? 1.5 : 1) * (phase >= 2 ? 1.15 : 1); }

        @Override void hit(Player p, double dmg, Vector from, Guard g) { super.hit(p, dmg * (enraged ? 1.5 : 1), from, g); }

        @Override double damageTaken(double amount) {
            if (enraged || attack == ASCEND) return 0; // above the water / rising: nothing gets through
            return amount;
        }

        Location mouth() { return Lich.joint(pos, Lich.frame(yaw, pitch, 0), 2.9, 0, 0, k); }

        static double surfaceY(World w, Location l) { return w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), org.bukkit.HeightMap.WORLD_SURFACE); }

        /** Out of the water: the eyes are above the topmost block of their column (open air over the sea, or a raft). */
        boolean surfaced(Player p) {
            Location e = p.getEyeLocation();
            return e.getY() >= surfaceY(world, e) + 0.3 && !p.isInWater();
        }

        // ---------------------------------------------------------------- every tick
        @Override
        void bodyTick(List<Player> a) {
            if (phase < 3 && ticks % 10 == 0) enrage(a);
            poisonTick(a);
            if (phase == 3 && ascended) { finalTicks++; stormTick(a); }
            if (attack < 0) {
                Location c = circle(c("orbit-radius", 14), 3, phase == 3 ? 0.03 : 0.02);
                if (phase == 3 && ascended) c.setY(surfaceY(world, c) - 2.5);
                swimTo(c, c("speed", 0.45) * spd(), 7);
                wantMand = 12 + Math.sin(ticks * 0.1) * 8;
            }
            aMand += (wantMand - aMand) * 0.3;
            pose(0);
            if (ticks % 4 == 0) world.spawnParticle(Particle.DUST, chain.pts.get(random.nextInt(chain.pts.size())), 2, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(Color.fromRGB(150, 210, 70), 1.4f));
            if (enraged && ticks % 3 == 0) world.spawnParticle(Particle.DUST, pos, 8, 1.2 * k, 1.2 * k, 1.2 * k, 0, new Particle.DustOptions(Color.fromRGB(220, 40, 40), 2f));
            if (ticks % 40 == 0) world.playSound(pos, Sound.ENTITY_GUARDIAN_AMBIENT, SoundCategory.HOSTILE, 2f, 0.4f);
        }

        void enrage(List<Player> a) {
            boolean any = false;
            for (Player p : a) if (surfaced(p)) { any = true; break; }
            if (any == enraged) return;
            enraged = any;
            if (enraged) say(ChatColor.RED + "" + ChatColor.BOLD + "The Brine Scourge is ENRAGED! " + ChatColor.GRAY + "It can't be hurt while anyone is above the water. Get back under!");
            else say(ChatColor.GREEN + "The Brine Scourge calms. Everyone is back under the water.");
            world.playSound(pos, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, enraged ? 0.5f : 0.8f);
        }

        void poisonAdd(Player p, double v) { poison.merge(p.getUniqueId(), v, (x, y) -> Math.max(0, Math.min(1, x + y))); }

        void poisonTick(List<Player> a) {
            if (ticks % 10 != 0) return;
            for (Player p : a) {
                UUID id = p.getUniqueId();
                double v = poison.getOrDefault(id, 0.0);
                if (phase == 3 && ascended) // the acidity rises faster and faster: only the air above the water is safe
                    v += p.isInWater() ? c("final-acidity", 0.003) * Math.pow(1.045, finalTicks / 20.0) : -0.02;
                else if (v < 1) v -= 0.0025; // a full meter stays full until a fresh bubble (or a breath of air) brings it down
                else if (ticks % 200 == 0) v = 0.97;
                v = Math.max(0, Math.min(1, v));
                poison.put(id, v);
                if (v >= 1 && ticks % 20 == 0) {
                    hit(p, c("poison-damage", 3), p.getLocation().toVector(), Guard.UNBLOCKABLE);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, true, false, false));
                } else if (v >= 0.6) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 0, true, false, false));
                if (v > 0.005) {
                    int bars = (int) Math.round(v * 20);
                    ChatColor col = v < 0.5 ? ChatColor.GREEN : v < 0.8 ? ChatColor.YELLOW : ChatColor.RED;
                    p.sendActionBar(legacy(ChatColor.DARK_GREEN + "☣ Sulphuric Poisoning " + col + "|".repeat(bars) + ChatColor.DARK_GRAY + "|".repeat(20 - bars)
                            + " " + col + (int) Math.round(v * 100) + "%"));
                }
            }
        }

        void stormTick(List<Player> a) {
            for (Player p : a) if (storm.add(p.getUniqueId())) p.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
            if (ticks % 140 == 0 && !a.isEmpty()) {
                Player p = a.get(random.nextInt(a.size()));
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1f, 0.7f);
                world.spawnParticle(Particle.FLASH, p.getLocation().add(random.nextGaussian() * 20, 18, random.nextGaussian() * 20), 1);
            }
        }

        @Override void cleanup() {
            for (UUID id : storm) { Player p = Bukkit.getPlayer(id); if (p != null) p.resetPlayerWeather(); }
            storm.clear();
        }

        @Override void onPhase(int p) {
            step = 0;
            if (p == 3) { ascended = false; cd = 1; if (enraged) { enraged = false; say(ChatColor.GREEN + "The Brine Scourge forgets its rage: it rises for the surface."); } }
        }

        // ---------------------------------------------------------------- the rig
        @Override
        void pose(int deathT) {
            super.pose(deathT);
            double roll = deathT > 0 ? Math.min(160, deathT * 3) : Math.sin(ticks * 0.08) * 6;
            float hp = Math.max(-60, Math.min(60, pitch)) - (float) (flinchAmt() * 15);
            headB.pose(pos, yaw, hp, (float) roll, null);
            Quaternionf q = Lich.frame(yaw, hp, (float) roll);
            double open = deathT > 0 ? 55 : aMand;
            mandL.pose(Lich.joint(pos, q, 0.55, 0, -1.0, k), yaw, hp, (float) roll, new Quaternionf().rotationY((float) Math.toRadians(open)));
            mandR.pose(Lich.joint(pos, q, 0.55, 0, 1.0, k), yaw, hp, (float) roll, new Quaternionf().rotationY((float) Math.toRadians(-open)));
        }

        @Override void deathAnim(int t) { // the corpse dissolves in its own acid
            pos = pos.clone().add(0, -0.05, 0);
            for (Wild.Part p : rig.parts) p.resize(p.k * 0.985);
            pose(t);
            if (t % 2 == 0) for (int i = 0; i < chain.pts.size(); i += 3)
                world.spawnParticle(Particle.DUST, chain.pts.get(i), 4, 0.8, 0.8, 0.8, 0, new Particle.DustOptions(Color.fromRGB(150, 210, 70), 1.8f));
            if (t % 3 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos, 20, 1.5 * k, 1, 1.5 * k, 0.1);
            if (t % 10 == 0) world.playSound(pos, Sound.BLOCK_LAVA_EXTINGUISH, SoundCategory.HOSTILE, 2.5f, 0.5f);
        }

        // ---------------------------------------------------------------- the moves
        @Override
        int pick(List<Player> a) {
            if (phase == 3 && !ascended) return ASCEND;
            int[] cyc = phase == 3 ? FINAL : CYCLE;
            return cyc[step++ % cyc.length];
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear();
            charge = 0; chargeT = 0; stunT = 0; way = null; typhoon = null;
            switch (move) {
                case PULSE -> say(ChatColor.GREEN + "Its body glows: RADIATION PULSE. " + ChatColor.GRAY + "Catch the blue bubbles rising from below to clear the poison.");
                case BREATH -> say(ChatColor.GREEN + "RADIOACTIVE BREATH. " + ChatColor.GRAY + "Watch for green lines: rubble is falling.");
                case SPIN -> say(ChatColor.GREEN + "It circles you: TOXIC BUBBLE SPIN.");
                case FRENZY -> say(ChatColor.RED + "It charges again and again!");
                case ACID_RAIN -> say(ChatColor.GREEN + "It dives under you... ACID RAIN!");
                case TYPHOON -> say(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "It spins into a SULPHUROUS TYPHOON!");
                default -> { }
            }
        }

        @Override
        void moveTick(List<Player> a) {
            Player t = targetOr(focus);
            switch (attack) {
                case PULSE -> pulse(t, a);
                case RUBBLE -> rubbleCharges(t, 5, phase >= 2 ? 10 : 14, 34, 1.45);
                case BREATH -> breath(t, a);
                case SPIN -> spin(t, phase >= 2 ? 130 : 90);
                case ASCEND -> ascend(a);
                case FRENZY -> { rubbleCharges(t, 99, 8, 18, 1.75); if (at >= 180) end(); }
                case ACID_RAIN -> acidRain(t);
                case TYPHOON -> typhoon(t, a);
                default -> end();
            }
        }

        /** Radiation Pulse (8 s): it advances on you, firing spreads of acid globs; each spread is a green pulse of poison. */
        void pulse(Player t, List<Player> a) {
            if (t != null) {
                Location tl = t.getLocation().add(0, 1, 0);
                swimTo(tl.clone().add(unit(pos.toVector().subtract(tl.toVector())).multiply(7)), 0.35 * spd(), 8);
            }
            wantMand = 25 + Math.sin(at * 0.4) * 12;
            if (at % 20 == 10 && t != null) {
                int n = phase >= 2 ? 6 : 4;
                Location m = mouth();
                Vector base = unit(t.getLocation().add(0, 1, 0).toVector().subtract(m.toVector()));
                for (int i = 0; i < n; i++) acidGlob(m, base.clone().rotateAroundY(Math.toRadians((i - (n - 1) / 2.0) * 9)).multiply(0.75));
                // the pulse: a green ring out of its head, and the poison with it
                Location c = pos.clone();
                int[] r = {0};
                fx.add(() -> { r[0]++; ring3(c, r[0] * 1.4, Particle.HAPPY_VILLAGER); return r[0] >= 10; });
                for (Player p : within(c, 14)) poisonAdd(p, c("pulse-poison", 0.08));
                world.playSound(c, Sound.BLOCK_CONDUIT_ATTACK_TARGET, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            if (at % 30 == 0) for (Player p : a) freshBubble(p);
            if (at >= 160) end();
        }

        /** Rubble Charges: lunges leaving acid behind; slamming into rock stuns it, then it spits a spread of rubble. */
        void rubbleCharges(Player t, int charges, int wind, int dash, double speed) {
            if (t == null) { end(); return; }
            if (stunT > 0) {
                wantMand = 55;
                if (stunT % 4 == 0) world.spawnParticle(Particle.CRIT, pos, 10, 1, 1, 1, 0.1);
                if (--stunT == 0) { rubbleSpread(t, phase >= 2 ? 13 : 9); nextCharge(charges); }
                return;
            }
            chargeT++;
            if (chargeT == 1) {
                lineDir = unit(t.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector()));
                world.playSound(pos, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            if (chargeT <= wind) {
                yaw = turn(yaw, yawOf(flat(lineDir)), 20); pitch = pitchOf(lineDir);
                wantMand = 45;
                if (chargeT % 2 == 0) warn3d(pos, lineDir, 24, Color.fromRGB(150, 220, 60));
                return;
            }
            wantMand = 8;
            yaw = yawOf(flat(lineDir)); pitch = pitchOf(lineDir);
            Location n = pos.clone().add(lineDir.clone().multiply(speed * spd()));
            if (!open(n, 1)) { // into the rock: stunned for a second
                stunT = 20;
                Block b = n.getBlock();
                world.spawnParticle(Particle.BLOCK, n, 40, 1, 1, 1, 0.1, b.getType().isSolid() ? b.getBlockData() : Material.STONE.createBlockData());
                world.playSound(n, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.HOSTILE, 3f, 0.5f);
                return;
            }
            pos = n;
            if (chargeT % 3 == 0) acidCloud(pos.clone().add(lineDir.clone().multiply(-2 * k)));
            world.spawnParticle(Particle.BUBBLE, pos, 16, 1, 1, 1, 0.1);
            strike(pos, 2.6 * k, c("moves.charge", 14), Guard.HEAVY, lineDir.clone().multiply(1.1).setY(0.4));
            bodyStrike(1.6 * k, c("moves.body", 6));
            if (chargeT > wind + dash) nextCharge(charges);
        }

        void nextCharge(int charges) {
            charge++; chargeT = 0; hitOnce.clear();
            if (charge >= charges) end();
        }

        void rubbleSpread(Player t, int n) {
            Location m = mouth();
            Vector base = unit(t.getLocation().add(0, 1, 0).toVector().subtract(m.toVector()));
            for (int i = 0; i < n; i++) {
                Vector v = base.clone().rotateAroundY(Math.toRadians((i - (n - 1) / 2.0) * 10)).multiply(0.8);
                bolt(m.clone(), v, 50, 0.5, c("moves.rubble", 8), Particle.CRIT, new ItemStack(Material.COBBLED_DEEPSLATE), null, 0, null);
            }
            world.playSound(m, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
        }

        /** Radioactive Breath (8 s): sharp turns, spitting acid; every 1.5 s rubble drops from above on green lines. */
        void breath(Player t, List<Player> a) {
            if (t == null) { end(); return; }
            Location tl = t.getLocation().add(0, 1, 0);
            if (way == null || at % 24 == 0) {
                for (int i = 0; i < 8; i++) {
                    Location c = tl.clone().add(random.nextGaussian() * 9, random.nextGaussian() * 4, random.nextGaussian() * 9);
                    if (open(c, 1)) { way = c; break; }
                }
                if (way == null) way = tl.clone();
            }
            swimTo(way, 0.75 * spd(), 24);
            wantMand = 40;
            if (at % 4 == 0) {
                Location m = mouth();
                Vector v = unit(tl.toVector().subtract(m.toVector())).add(new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).multiply(0.12));
                acidGlob(m, unit(v).multiply(0.85));
            }
            if (at % 30 == 0) {
                int n = phase >= 2 ? 7 : 5;
                for (int i = 0; i < n; i++) {
                    Location s = i == 0 ? tl.clone() : tl.clone().add(random.nextGaussian() * 6, 0, random.nextGaussian() * 6);
                    fallingRubble(s);
                }
            }
            if (at >= 160) end();
        }

        void fallingRubble(Location s) {
            Location floor = floorUnder(s.clone(), 40);
            Location top = s.clone().add(0, 16, 0);
            int[] age = {0};
            fx.add(() -> {
                age[0]++;
                if (age[0] <= 20) {
                    if (age[0] % 2 == 0) for (double y = floor.getY(); y <= top.getY(); y += 0.8)
                        world.spawnParticle(Particle.DUST, top.getX(), y, top.getZ(), 1, 0.02, 0, 0.02, 0, new Particle.DustOptions(Color.fromRGB(120, 230, 60), 1.3f));
                    return false;
                }
                bolt(top.clone(), new Vector(0, -0.9, 0), 40, 0.9, c("moves.breath-rubble", 9), Particle.CRIT, new ItemStack(Material.COBBLED_DEEPSLATE), null, 0, null);
                return true;
            });
        }

        /** Toxic Bubble Spin: it circles you fast, firing toxic bubbles in. */
        void spin(Player t, int dur) {
            if (t == null) { end(); return; }
            spinAng += 0.12 * spd();
            Location c = t.getLocation().add(Math.cos(spinAng) * 10, 1 + Math.sin(at * 0.1) * 2, Math.sin(spinAng) * 10);
            swimTo(c, 1.1 * spd(), 30);
            wantMand = 30;
            if (at % 5 == 0) toxicBubble(mouth(), t);
            if (at >= dur) end();
        }

        /** The final phase begins: it swims to the surface (it can't be hurt while it rises). */
        void ascend(List<Player> a) {
            for (Player p : a) if (storm.add(p.getUniqueId())) p.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
            Location s = pos.clone();
            s.setY(surfaceY(world, s) - 2);
            swimTo(s, 0.7, 14);
            wantMand = 60;
            if (at % 10 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos, 40, 2, 2, 2, 0.3);
            if (pos.getY() >= surfaceY(world, pos) - 3.5 || at > 220) { ascended = true; finalTicks = 0; end(); }
        }

        /** Acid Rain: it dives under you, then lunges up out of the sea, flinging acid globs that rain back down. */
        void acidRain(Player t) {
            if (t == null) { end(); return; }
            double sy = surfaceY(world, pos);
            if (at < 40) {
                Location below = t.getLocation().add(0, -14, 0);
                if (!open(below, 1)) below = t.getLocation().add(0, -7, 0);
                swimTo(below, 1.0 * spd(), 20);
                wantMand = 15;
                return;
            }
            if (at == 40) {
                lineDir = unit(t.getLocation().add(0, 12, 0).toVector().subtract(pos.toVector()));
                world.playSound(pos, Sound.ENTITY_DOLPHIN_JUMP, SoundCategory.HOSTILE, 3f, 0.4f);
            }
            if (at <= 70) { // up and out of the water
                yaw = yawOf(flat(lineDir)); pitch = pitchOf(lineDir);
                Location n = pos.clone().add(lineDir.clone().multiply(1.3));
                if (!n.getBlock().getType().isSolid()) pos = n;
                wantMand = 60;
                strike(pos, 2.6 * k, c("moves.charge", 14), Guard.HEAVY, new Vector(0, 1.0, 0));
                if (pos.getY() > sy && at % 3 == 0) for (int i = 0; i < 3; i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    lob(mouth(), new Vector(Math.cos(ang) * 0.45, 0.5 + random.nextDouble() * 0.3, Math.sin(ang) * 0.45));
                }
                return;
            }
            // back down into the sea
            pitch = 60;
            Location n = pos.clone().add(0, -0.9, 0);
            if (!n.getBlock().getType().isSolid()) pos = n;
            if (pos.getY() < sy - 3 || at > 120) end();
        }

        /** Sulphurous Typhoon: it spins into a typhoon that drifts after you, spitting acid; it circles it, spraying bubbles. */
        void typhoon(Player t, List<Player> a) {
            if (t == null) { end(); return; }
            if (typhoon == null) { typhoon = pos.clone(); typhoon.setY(surfaceY(world, pos)); world.playSound(pos, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.HOSTILE, 3f, 0.5f); }
            Vector drift = t.getLocation().toVector().subtract(typhoon.toVector()).setY(0);
            if (drift.lengthSquared() > 1) typhoon.add(drift.normalize().multiply(0.11 * spd()));
            double base = typhoon.getY() - 12, topY = typhoon.getY() + 10;
            if (at % 2 == 0) for (double y = base; y <= topY; y += 1.5) {
                double r = 2 + (y - base) / (topY - base) * 3, ang = at * 0.35 + y;
                world.spawnParticle(y > typhoon.getY() ? Particle.CLOUD : Particle.BUBBLE, typhoon.getX() + Math.cos(ang) * r, y, typhoon.getZ() + Math.sin(ang) * r, 1, 0, 0, 0, 0);
                world.spawnParticle(Particle.DUST, typhoon.getX() + Math.cos(ang + Math.PI) * r, y, typhoon.getZ() + Math.sin(ang + Math.PI) * r, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(150, 210, 70), 1.6f));
            }
            for (Player p : a) {
                Location l = p.getLocation();
                double dx = typhoon.getX() - l.getX(), dz = typhoon.getZ() - l.getZ(), d = Math.hypot(dx, dz);
                if (d > 6.5 || l.getY() < base - 1 || l.getY() > topY + 2) continue;
                Vector in = new Vector(dx, 0, dz).multiply(0.06).add(new Vector(-dz, 0, dx).multiply(0.05)).setY(0.08);
                Safe.vel(p, p.getVelocity().multiply(0.6).add(in));
                if (d < 3.2 && at % 10 == 0) { hit(p, c("moves.typhoon", 5), typhoon.toVector(), Guard.UNBLOCKABLE); poisonAdd(p, 0.04); }
            }
            if (at % 15 == 0) acidGlob(typhoon.clone().add(0, 2, 0), unit(t.getLocation().add(0, 1, 0).toVector().subtract(typhoon.toVector())).multiply(0.7));
            spinAng += 0.09 * spd();
            Location c = typhoon.clone().add(Math.cos(spinAng) * 8, -2, Math.sin(spinAng) * 8);
            swimTo(c, 1.0 * spd(), 25);
            if (at % 8 == 0) toxicBubble(mouth(), t);
            if (at % 30 == 0) world.playSound(typhoon, Sound.ITEM_ELYTRA_FLYING, SoundCategory.HOSTILE, 2f, 0.6f);
            if (at >= 220) end();
        }

        // ---------------------------------------------------------------- what it fires
        void acidGlob(Location from, Vector v) {
            bolt(from.clone(), v, 60, 0.45, c("moves.acid-glob", 7), Particle.ITEM_SLIME, new ItemStack(Material.SLIME_BALL), null, 0, p -> {
                poisonAdd(p, c("glob-poison", 0.05));
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
                if (phase >= 2) acidCloud(p.getLocation());
            });
        }

        void toxicBubble(Location from, Player t) {
            Vector v = unit(t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector())).multiply(0.3);
            bolt(from.clone(), v, 120, 0.8, c("moves.bubble", 7), Particle.BUBBLE, new ItemStack(Material.LIME_STAINED_GLASS), t, 0.03, p -> {
                poisonAdd(p, c("bubble-poison", 0.06));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
            });
            world.playSound(from, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.HOSTILE, 2f, 0.5f);
        }

        /** A glob that arcs and falls (Acid Rain). */
        void lob(Location from, Vector vel) {
            Location p = from.clone();
            Vector v = vel.clone();
            int[] age = {0};
            fx.add(() -> {
                if (dying || ++age[0] > 100) return true;
                v.setY(v.getY() - 0.035);
                p.add(v);
                if (!Double.isFinite(p.getY()) || !world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4) || p.getBlock().getType().isSolid()) return true;
                world.spawnParticle(Particle.ITEM_SLIME, p, 2, 0.05, 0.05, 0.05, 0);
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(160, 220, 60), 1.5f));
                for (Player pl2 : active()) if (pl2.getLocation().add(0, 0.9, 0).distanceSquared(p) < 1.4) {
                    hit(pl2, c("moves.acid-rain", 7), p.toVector(), Guard.BLOCKABLE);
                    poisonAdd(pl2, 0.05);
                    return true;
                }
                return false;
            });
        }

        void acidCloud(Location l) {
            world.spawn(l, AreaEffectCloud.class, cl -> {
                cl.setRadius(phase >= 2 ? 2.6f : 2.0f); cl.setDuration(phase >= 2 ? 160 : 80); cl.setRadiusPerTick(-0.008f);
                cl.setColor(Color.fromRGB(160, 210, 60));
                cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 0), true);
                cl.addScoreboardTag(Wild.FX_TAG);
            });
            for (Player p : within(l, 2.4)) poisonAdd(p, 0.02);
        }

        /** A bubble of clean water rising from below a player: swim into it to clear some poison. */
        void freshBubble(Player t) {
            Location s = t.getLocation().add(random.nextGaussian() * 2.5, -7, random.nextGaussian() * 2.5);
            if (!open(s, 0)) s = t.getLocation().add(random.nextGaussian() * 2, -2, random.nextGaussian() * 2);
            Location p = s.clone();
            ItemDisplay d = world.spawn(p, ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS));
                e.setPersistent(false);
                e.addScoreboardTag(Wild.FX_TAG);
                e.setTeleportDuration(2);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
            });
            int[] age = {0};
            fx.add(() -> {
                if (++age[0] > 110 || !d.isValid()) { d.remove(); return true; }
                p.add(0, 0.12, 0);
                if (p.getBlock().getType().isSolid()) { d.remove(); return true; }
                if (age[0] % 2 == 0) d.teleport(p);
                world.spawnParticle(Particle.BUBBLE, p, 3, 0.25, 0.25, 0.25, 0.02);
                for (Player pl2 : active()) if (pl2.getLocation().add(0, 0.9, 0).distanceSquared(p) < 2.2) {
                    poisonAdd(pl2, -c("fresh-bubble", 0.3));
                    pl2.playSound(pl2.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1f, 1.4f);
                    pl2.sendActionBar(legacy(ChatColor.AQUA + "Fresh water! " + ChatColor.GRAY + "The poison eases."));
                    d.remove();
                    return true;
                }
                return false;
            });
        }

        @Override void drops(Player p, Location at) { darkLoot(p, 0.5, 3); }

        @Override
        List<MoveSlot> morphMoves() {
            if (phase == 3) return List.of(new MoveSlot(FRENZY, "Frenzied Charges", Material.PRISMARINE_SHARD), new MoveSlot(ACID_RAIN, "Acid Rain", Material.SLIME_BALL),
                    new MoveSlot(TYPHOON, "Sulphurous Typhoon", Material.WIND_CHARGE));
            return List.of(new MoveSlot(PULSE, "Radiation Pulse", Material.LIME_DYE), new MoveSlot(RUBBLE, "Rubble Charges", Material.COBBLED_DEEPSLATE),
                    new MoveSlot(BREATH, "Radioactive Breath", Material.GREEN_DYE), new MoveSlot(SPIN, "Toxic Bubble Spin", Material.LIME_STAINED_GLASS));
        }
    }

    // =====================================================================================================
    //  THE UMBRAL LEVIATHAN
    // =====================================================================================================
    static final class Umbral extends Serpent {
        static final int CHARGE = 1, GRAB = 2, VANISH = 3, SHRIEK = 4, ECLIPSE = 5, ECHO = 6;
        Location lineFrom, mark;
        Vector lineDir;
        Player held;
        double heldHp;
        boolean hidden;
        final Map<UUID, Location> marked = new HashMap<>();

        Umbral(Wild w, Location at, Player by) {
            super(w, "umbral", at, by, 1.8);
            build("umbral_head", "umbral_body", "umbral_tail", (int) c("segments", 14), 2.0, 0.03, 3, "The Umbral Leviathan");
            world.playSound(pos, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 4f, 0.5f);
        }

        @Override double defHealth() { return 2600; }
        @Override BarColor barColor() { return BarColor.PURPLE; }
        @Override String music() { return "minecraft:music_disc.5"; }
        @Override double musicLength() { return 178; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Two eyes open in the dark... THE UMBRAL LEVIATHAN has found you.";
                case "phase2" -> ChatColor.DARK_PURPLE + "The shadow around it thickens.";
                case "phase3" -> ChatColor.RED + "The Umbral Leviathan shrieks, and the dark answers!";
                case "death" -> ChatColor.DARK_PURPLE + "The Umbral Leviathan comes apart like smoke in the water.";
                case "leave" -> ChatColor.DARK_PURPLE + "The Umbral Leviathan " + ChatColor.GRAY + "melts back into the dark.";
                default -> null;
            };
        }

        @Override double damageTaken(double amount) {
            if (hidden) return 0; // nothing to hit in the dark
            if (held != null) { heldHp += amount; if (heldHp >= maxHp * c("grab-break-share", 0.05)) release(true); }
            return amount;
        }

        @Override
        void bodyTick(List<Player> a) {
            if (attack < 0 || attack == SHRIEK || attack == ECHO) swimTo(circle(c("orbit-radius", 15), 2, phase == 3 ? 0.032 : 0.022), c("speed", 0.48) * (phase == 3 ? 1.25 : 1), 7);
            pose(0);
            if (!hidden && ticks % 3 == 0) {
                Location p = chain.pts.get(random.nextInt(chain.pts.size()));
                world.spawnParticle(Particle.SQUID_INK, p, 2, 0.6 * k, 0.6 * k, 0.6 * k, 0.01);
                world.spawnParticle(Particle.DUST, pos.clone().add(dir3(yaw, pitch).multiply(1.2 * k)).add(0, 0.4 * k, 0), 2, 0.3, 0.1, 0.3, 0, new Particle.DustOptions(Color.fromRGB(150, 60, 255), 1.2f));
            }
            if (held != null) holdTick();
        }

        void holdTick() {
            if (!held.isOnline() || held.isDead() || !survival(held) || !held.getWorld().equals(world)) { release(false); return; }
            Location mouth = pos.clone().add(dir3(yaw, pitch).multiply(2.2 * k));
            held.teleport(mouth.setDirection(pos.toVector().subtract(mouth.toVector())));
            if (ticks % 10 == 0) hit(held, c("moves.grab-per-half-second", 2.5), pos.toVector(), Guard.UNBLOCKABLE);
        }

        void release(boolean broke) {
            Player h = held;
            held = null;
            if (h == null || !h.isOnline()) return;
            Safe.vel(h, dir3(yaw, pitch).multiply(1.2).setY(0.3));
            h.sendActionBar(legacy(broke ? ChatColor.GREEN + "You tore free!" : ChatColor.GRAY + "It lets you go."));
        }

        @Override
        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(CHARGE, GRAB, VANISH, SHRIEK, ECHO));
            if (phase >= 2) { pool.add(ECLIPSE); pool.add(VANISH); }
            if (phase == 3) { pool.add(CHARGE); pool.add(ECHO); }
            return pool.get(random.nextInt(pool.size()));
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear();
            t = targetOr(t);
            focus = t;
            Location tl = t != null ? t.getLocation().add(0, 1, 0) : pos.clone().add(dirOf(yaw).multiply(10));
            switch (move) {
                case CHARGE -> {
                    lineDir = tl.toVector().subtract(pos.toVector());
                    if (lineDir.lengthSquared() < 0.01) lineDir = dirOf(yaw);
                    lineDir.normalize();
                    lineFrom = tl.clone().add(lineDir.clone().multiply(-15));
                    world.playSound(pos, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
                }
                case GRAB -> { heldHp = 0; world.playSound(pos, Sound.ENTITY_WARDEN_ANGRY, SoundCategory.HOSTILE, 3f, 0.6f); }
                case VANISH -> { hide(true); world.playSound(pos, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 2f, 0.5f); }
                case ECLIPSE -> { mark = tl.clone(); say(ChatColor.DARK_PURPLE + "A ring of shadow closes around you. GET OUT."); }
                case ECHO -> { marked.clear(); say(ChatColor.LIGHT_PURPLE + "It listens..."); }
                default -> { }
            }
        }

        void hide(boolean h) {
            hidden = h;
            for (Wild.Part p : rig.parts) p.hide(h);
        }

        @Override
        void moveTick(List<Player> a) {
            Player t = focus;
            switch (attack) {
                case CHARGE -> {
                    int wind = phase == 3 ? 20 : 30;
                    if (at <= wind) { swimTo(lineFrom, 0.85, 14); if (at % 2 == 0) warn3d(lineFrom, lineDir, 32, Color.fromRGB(140, 60, 220)); return; }
                    yaw = yawOf(flat(lineDir)); pitch = pitchOf(lineDir);
                    Location n = pos.clone().add(lineDir.clone().multiply(c("charge-speed", 1.35)));
                    if (open(n, 1)) pos = n; else { end(); return; }
                    strike(pos, 2.8 * k, c("moves.charge", 17), Guard.HEAVY, lineDir.clone().multiply(1.1).setY(0.3));
                    if (at > wind + 28) end();
                }
                case GRAB -> {
                    if (held == null && t != null) {
                        swimTo(t.getLocation().add(0, 1, 0), 0.75, 16);
                        if (pos.distanceSquared(t.getLocation().add(0, 1, 0)) < Math.pow(3 * k, 2)) {
                            held = t; heldHp = 0;
                            t.sendActionBar(legacy(ChatColor.DARK_PURPLE + "It has you! " + ChatColor.WHITE + "Hit it hard to break free!"));
                            world.playSound(pos, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 3f, 0.4f);
                        }
                    }
                    if (held != null) swimTo(circle(10, 0, 0.06), 0.6, 12); // drags you around
                    if (at > (held != null ? 120 : 60)) { release(false); end(); }
                }
                case VANISH -> {
                    if (t == null) { hide(false); end(); return; }
                    Vector behind = t.getLocation().getDirection().setY(0);
                    Location bl = t.getLocation().add(0, 1, 0).add(behind.lengthSquared() < 0.01 ? new Vector(0, 0, -4) : behind.normalize().multiply(-5));
                    if (at < 40) { if (open(bl, 1)) pos = bl; if (at % 4 == 0) world.spawnParticle(Particle.SQUID_INK, t.getLocation().add(0, 1, 0), 8, 3, 2, 3, 0.01); return; }
                    if (at == 40) {
                        hide(false);
                        Vector to = t.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector());
                        yaw = yawOf(flat(to)); pitch = pitchOf(to);
                        world.playSound(pos, Sound.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 3f, 0.5f);
                    }
                    if (at > 40 && at < 52) { pos = pos.clone().add(dir3(yaw, pitch).multiply(0.6)); strike(pos, 2.6 * k, c("moves.ambush", 13), Guard.HEAVY, null); }
                    if (at > 56) end();
                }
                case SHRIEK -> {
                    if (at == 16) {
                        world.playSound(pos, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 4f, 0.6f);
                        world.spawnParticle(Particle.SONIC_BOOM, pos, 1);
                        for (Player p : within(pos, c("shriek-radius", 26))) {
                            hit(p, c("moves.shriek", 5), pos.toVector(), Guard.UNBLOCKABLE);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                        }
                    }
                    if (at > 30) end();
                }
                case ECLIPSE -> {
                    double r = c("eclipse-radius", 7);
                    double ang = at * 0.2;
                    swimTo(mark.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 1.0, 32);
                    if (at % 2 == 0) { ring3(mark.clone().add(0, -1, 0), r, Particle.SQUID_INK); ring3(mark.clone().add(0, 2, 0), r, Particle.SQUID_INK); }
                    if (at == 80) {
                        world.playSound(mark, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 3f, 0.4f);
                        sphereFx(mark, r, Particle.SQUID_INK, 120);
                        for (Player p : a) {
                            Location l = p.getLocation();
                            if (Math.hypot(l.getX() - mark.getX(), l.getZ() - mark.getZ()) < r && Math.abs(l.getY() - mark.getY()) < 5)
                                hit(p, c("moves.eclipse", 14), mark.toVector(), Guard.UNBLOCKABLE);
                        }
                    }
                    if (at > 90) end();
                }
                case ECHO -> {
                    if (at % 25 == 0 && at <= 50) {
                        world.playSound(pos, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, 3f, 0.7f);
                        sphereFx(pos, 6 + at / 5.0, Particle.SCULK_CHARGE_POP, 90);
                        for (Player p : within(pos, 30)) {
                            marked.put(p.getUniqueId(), p.getLocation().clone());
                            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 50, 0, false, false));
                        }
                    }
                    if (at > 50 && at < 80 && at % 4 == 0) for (Location m : marked.values()) warnRing(floorUnder(m, 30), 2, Color.PURPLE);
                    if (at == 80) for (var e : marked.entrySet()) {
                        Player p = Bukkit.getPlayer(e.getKey());
                        Location m = e.getValue();
                        for (int y = -10; y < 6; y++) world.spawnParticle(Particle.DUST, m.clone().add(0, y, 0), 3, 0.2, 0.3, 0.2, 0, new Particle.DustOptions(Color.fromRGB(110, 40, 200), 2f));
                        if (p != null && p.getLocation().distanceSquared(m) < 2.2 * 2.2) hit(p, c("moves.echo", 10), m.toVector(), Guard.UNBLOCKABLE);
                    }
                    if (at > 86) end();
                }
                default -> end();
            }
        }

        @Override void end() { if (hidden) hide(false); if (held != null && attack == GRAB) release(false); super.end(); }

        @Override void drops(Player p, Location at) { darkLoot(p, 0.5, 3); }

        @Override void cleanup() { release(false); }

        @Override
        List<MoveSlot> morphMoves() {
            List<MoveSlot> m = new ArrayList<>(List.of(new MoveSlot(CHARGE, "Shadow Charge", Material.ECHO_SHARD), new MoveSlot(GRAB, "The Grab", Material.LEAD),
                    new MoveSlot(VANISH, "Vanish", Material.BLACK_DYE), new MoveSlot(SHRIEK, "Shriek", Material.SCULK_SHRIEKER),
                    new MoveSlot(ECHO, "Abyssal Echo", Material.SCULK_SENSOR)));
            if (phase >= 2) m.add(new MoveSlot(ECLIPSE, "Eclipse Coil", Material.OBSIDIAN));
            return m;
        }
    }

    // =====================================================================================================
    //  CORALIA, SIREN OF THE SHOALS
    // =====================================================================================================
    static final class Coralia extends Swimmer {
        static final int THRALLS = 1, GAZE = 2, EELS = 3, BUBBLES = 4, SONG = 5, SPIRES = 6;
        final Wild.Part tailP, torso, headP, armL, armR;
        final List<Location> marks = new ArrayList<>();
        final List<Vector> eelDirs = new ArrayList<>();
        final List<BlockDisplay> spires = new ArrayList<>();
        double aArmL, aArmR, aTail;

        Coralia(Wild w, Location at, Player by) {
            super(w, "coralia", at, by, 2.2);
            tailP = rig.add("coralia_tail", k, pos);
            torso = rig.add("coralia_torso", k, pos);
            headP = rig.add("coralia_head", k, pos);
            armL = rig.add("coralia_arm", k, pos);
            armR = rig.add("coralia_arm", k, pos);
            hitbox(pos, Math.max(2, (int) Math.round(2.4 * k)), "Coralia");
            hitbox(pos, Math.max(2, (int) Math.round(2.0 * k)), "Coralia");
            world.playSound(pos, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.HOSTILE, 3f, 0.5f);
        }

        @Override double defHealth() { return 900; }
        @Override BarColor barColor() { return BarColor.PINK; }

        /** Phase 3: like Cala Maria, she turns into her Medusa form: green skin, a nest of snakes, fangs, claws. */
        @Override void onPhase(int p) {
            if (p < 3) return;
            torso.swap("coralia_torso_medusa"); headP.swap("coralia_head_medusa");
            armL.swap("coralia_arm_medusa"); armR.swap("coralia_arm_medusa");
            Location h = Lich.joint(pos, Lich.frame(yaw, 0, 0), 0, 1.6, 0, k);
            world.spawnParticle(Particle.DUST, h, 60, 0.8 * k, 0.8 * k, 0.8 * k, 0, new Particle.DustOptions(Color.fromRGB(150, 100, 202), 2f));
            world.spawnParticle(Particle.BUBBLE_POP, h, 40, 0.8 * k, 0.8 * k, 0.8 * k, 0.1);
            world.playSound(pos, Sound.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 3f, 0.6f);
            world.playSound(pos, Sound.ENTITY_CAT_HISS, SoundCategory.HOSTILE, 3f, 0.5f);
        }
        @Override String music() { return "minecraft:music_disc.mellohi"; }
        @Override double musicLength() { return 96; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "A song drifts through the water... CORALIA, SIREN OF THE SHOALS rises!";
                case "phase2" -> ChatColor.LIGHT_PURPLE + "Coralia: \"You're still listening? How sweet.\"";
                case "phase3" -> ChatColor.RED + "Coralia: \"ENOUGH! Turn to stone, all of you!\"";
                case "death" -> ChatColor.LIGHT_PURPLE + "Coralia's song breaks, and she sinks into the coral.";
                case "leave" -> ChatColor.LIGHT_PURPLE + "Coralia " + ChatColor.GRAY + "laughs, and swims off into the blue.";
                default -> null;
            };
        }

        @Override
        void bodyTick(List<Player> a) {
            if (attack < 0 || attack == GAZE || attack == BUBBLES || attack == THRALLS || attack == SPIRES)
                swimTo(circle(c("orbit-radius", 11), 3, 0.018), c("speed", 0.36), 6);
            if (attack == SONG && focus != null) { Vector to = focus.getLocation().toVector().subtract(pos.toVector()); yaw = turn(yaw, yawOf(flat(to)), 8); }
            pose(0);
        }

        @Override
        void pose(int deathT) {
            double sway = Math.sin(ticks * 0.1);
            aTail = sway * 22 - flinchAmt() * 20;
            float bodyPitch = (float) Math.max(-35, Math.min(35, pitch * 0.5)), roll = (float) (deathT > 0 ? Math.min(120, deathT * 2.5) : sway * 4);
            Quaternionf bq = Lich.frame(yaw, bodyPitch, roll);
            tailP.pose(pos, yaw, bodyPitch, roll, Lich.rotZ(aTail));
            torso.pose(pos, yaw, bodyPitch, roll, null);
            Location neck = Lich.joint(pos, bq, 0, 1.25, 0, k);
            headP.pose(neck, yaw, bodyPitch, roll, null);
            double wantL = 20 + sway * 10, wantR = 20 - sway * 10;
            if (attack == GAZE) { wantL = 120; wantR = 120; }
            if (attack == SONG) { wantL = 80 + Math.sin(ticks * 0.3) * 20; wantR = 80 - Math.sin(ticks * 0.3) * 20; }
            if (attack == EELS || attack == SPIRES) { wantL = 150; wantR = 150; }
            aArmL += (wantL - aArmL) * 0.2; aArmR += (wantR - aArmR) * 0.2;
            armL.pose(Lich.joint(pos, bq, 0, 1.0, -0.55, k), yaw, bodyPitch, roll, Lich.rotX(aArmL * 0.6).mul(Lich.rotZ(aArmL * 0.5)));
            armR.pose(Lich.joint(pos, bq, 0, 1.0, 0.55, k), yaw, bodyPitch, roll, Lich.rotX(-aArmR * 0.6).mul(Lich.rotZ(aArmR * 0.5)));
            if (hitboxes.size() > 1) { hitboxes.get(0).teleport(pos.clone().add(0, 0.2 * k, 0)); hitboxes.get(1).teleport(Lich.joint(pos, bq, -1.6, -0.3, 0, k)); }
        }

        @Override
        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(THRALLS, GAZE, EELS, BUBBLES, SPIRES));
            if (minions.size() >= 4) pool.remove(Integer.valueOf(THRALLS));
            if (phase >= 2) { pool.add(SONG); pool.add(GAZE); }
            return pool.get(random.nextInt(pool.size()));
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear(); marks.clear(); eelDirs.clear();
            t = targetOr(t); focus = t;
            switch (move) {
                case GAZE -> { say(ChatColor.LIGHT_PURPLE + "Her eyes begin to glow. " + ChatColor.WHITE + "LOOK AWAY!"); world.playSound(pos, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 2f, 1.4f); }
                case EELS -> { for (Player p : active()) eelDirs.add(unit(p.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector()))); }
                case SONG -> { say(ChatColor.LIGHT_PURPLE + "Coralia sings... and the song pulls at you."); world.playSound(pos, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, SoundCategory.HOSTILE, 3f, 0.6f); }
                case SPIRES -> { for (Player p : active()) marks.add(floorUnder(p.getLocation(), 30)); }
                default -> { }
            }
        }

        @Override
        void moveTick(List<Player> a) {
            switch (attack) {
                case THRALLS -> {
                    if (at == 10) for (int i = 0; i < 2 + phase; i++) {
                        Location l = openWater(pos, 2, 5, 1);
                        if (l == null) continue;
                        Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                        Drowned d = minion(l, EntityType.DROWNED, Drowned.class, ChatColor.LIGHT_PURPLE + "Thrall of the Shoal", 24, m -> {
                            m.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT)); m.getEquipment().setItemInMainHandDropChance(0);
                            m.getEquipment().setHelmet(new ItemStack(Material.BRAIN_CORAL_BLOCK)); m.getEquipment().setHelmetDropChance(0);
                            m.setShouldBurnInDay(false);
                        });
                        if (t != null) d.setTarget(t);
                        world.spawnParticle(Particle.BUBBLE_POP, l, 20, 0.4, 0.8, 0.4, 0.05);
                    }
                    if (at > 20) end();
                }
                case GAZE -> {
                    if (at < 34) { if (at % 4 == 0) world.spawnParticle(Particle.END_ROD, Lich.joint(pos, Lich.frame(yaw, 0, 0), 0.3, 1.6, 0, k), 4, 0.2, 0.1, 0.2, 0.01); return; }
                    if (at == 34) {
                        world.playSound(pos, Sound.BLOCK_STONE_PLACE, SoundCategory.HOSTILE, 3f, 0.5f);
                        Location eyes = Lich.joint(pos, Lich.frame(yaw, 0, 0), 0.3, 1.6, 0, k);
                        for (Player p : within(pos, 34)) {
                            Vector look = p.getEyeLocation().getDirection(), toHer = unit(eyes.toVector().subtract(p.getEyeLocation().toVector()));
                            if (look.dot(toHer) < c("gaze-dot", 0.55)) continue; // looked away
                            hit(p, c("moves.gaze", 4), pos.toVector(), Guard.UNBLOCKABLE);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) c("petrify-ticks", 50), 6));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, (int) c("petrify-ticks", 50), 4));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, (int) c("petrify-ticks", 50), 2));
                            world.spawnParticle(Particle.BLOCK, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0, Material.STONE.createBlockData());
                            p.sendActionBar(legacy(ChatColor.GRAY + "You met her eyes... and turned to stone!"));
                        }
                    }
                    if (at > 44) end();
                }
                case EELS -> {
                    if (at < 26) { if (at % 3 == 0) for (Vector d : eelDirs) warn3d(pos, d, 26, Color.YELLOW); return; }
                    if (at == 26) {
                        world.playSound(pos, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 2f, 1.6f);
                        for (Vector d : eelDirs) {
                            for (double t2 = 0; t2 < 26; t2 += 1.0) world.spawnParticle(Particle.ELECTRIC_SPARK, pos.clone().add(d.clone().multiply(t2)), 3, 0.2, 0.2, 0.2, 0.05);
                            for (Player p : active()) {
                                Vector rel = p.getLocation().add(0, 0.9, 0).toVector().subtract(pos.toVector());
                                double along = rel.dot(d);
                                if (along < 0 || along > 26) continue;
                                if (rel.clone().subtract(d.clone().multiply(along)).length() < 1.6 && hitOnce.add(p.getUniqueId())) {
                                    hit(p, c("moves.eel", 9), pos.toVector(), Guard.UNBLOCKABLE);
                                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
                                }
                            }
                        }
                    }
                    if (at > 34) end();
                }
                case BUBBLES -> {
                    if (at % 8 == 0 && at <= 40) {
                        Player t = targetOr(null);
                        if (t != null) bolt(Lich.joint(pos, Lich.frame(yaw, 0, 0), 0.6, 1.2, 0, k), unit(t.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector())).multiply(0.35),
                                90, 0.6, c("moves.bubble", 6), Particle.BUBBLE, new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS), t, 0.06, null);
                        world.playSound(pos, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.HOSTILE, 2f, 1.2f);
                    }
                    if (at > 50) end();
                }
                case SONG -> {
                    if (at % 6 == 0) for (double r = 2; r < 18; r += 4) ring3(pos, r + (at % 24) / 6.0, Particle.NOTE);
                    for (Player p : within(pos, c("song-radius", 20))) {
                        Vector in = pos.toVector().subtract(p.getLocation().toVector());
                        double d = in.length();
                        if (d > 0.5) Safe.vel(p, p.getVelocity().multiply(0.7).add(in.normalize().multiply(c("song-pull", 0.12))));
                        if (at % 30 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
                        if (d < 3 * k && at % 20 == 0) hit(p, c("moves.song", 3), pos.toVector(), Guard.UNBLOCKABLE);
                    }
                    if (at > 80) end();
                }
                case SPIRES -> {
                    if (at < 28) { if (at % 3 == 0) for (Location m : marks) warnRing(m, 1.8, Color.fromRGB(255, 120, 160)); return; }
                    if (at == 28) {
                        world.playSound(pos, Sound.BLOCK_CORAL_BLOCK_BREAK, SoundCategory.HOSTILE, 3f, 0.6f);
                        Material[] coral = {Material.BRAIN_CORAL_BLOCK, Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK, Material.TUBE_CORAL_BLOCK};
                        for (Location m : marks) {
                            BlockData bd = coral[random.nextInt(coral.length)].createBlockData();
                            BlockDisplay sp = world.spawn(m.clone().add(-0.6, 0, -0.6), BlockDisplay.class, d -> {
                                d.setBlock(bd); d.setPersistent(false); d.addScoreboardTag(Wild.FX_TAG);
                                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2f, 0.1f, 1.2f), new Quaternionf()));
                                d.setInterpolationDuration(4);
                            });
                            sp.setInterpolationDelay(0);
                            sp.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2f, 5f, 1.2f), new Quaternionf()));
                            spires.add(sp);
                            for (Player p : active()) {
                                Location l = p.getLocation();
                                if (Math.hypot(l.getX() - m.getX(), l.getZ() - m.getZ()) < 1.7 && l.getY() >= m.getY() - 1 && l.getY() < m.getY() + 5) {
                                    hit(p, c("moves.spire", 10), m.toVector(), Guard.BLOCKABLE);
                                    Safe.vel(p, new Vector(0, 1.1, 0));
                                }
                            }
                        }
                    }
                    if (at == 70) { for (BlockDisplay d : spires) if (d.isValid()) d.remove(); spires.clear(); }
                    if (at > 70) end();
                }
                default -> end();
            }
        }

        @Override void cleanup() { for (BlockDisplay d : spires) if (d.isValid()) d.remove(); spires.clear(); }

        @Override void drops(Player p, Location at) { darkLoot(p, 0.1, 1); }

        @Override
        List<MoveSlot> morphMoves() {
            List<MoveSlot> m = new ArrayList<>(List.of(new MoveSlot(THRALLS, "Thralls of the Shoal", Material.DROWNED_SPAWN_EGG), new MoveSlot(GAZE, "Petrifying Gaze", Material.ENDER_EYE),
                    new MoveSlot(EELS, "Eel Lash", Material.LIGHTNING_ROD), new MoveSlot(BUBBLES, "Bubble Barrage", Material.LIGHT_BLUE_STAINED_GLASS),
                    new MoveSlot(SPIRES, "Coral Spires", Material.BRAIN_CORAL_BLOCK)));
            if (phase >= 2) m.add(new MoveSlot(SONG, "Siren Song", Material.NOTE_BLOCK));
            return m;
        }
    }

    // =====================================================================================================
    //  OLD INKJAW
    // =====================================================================================================
    static final class Inkjaw extends Swimmer {
        static final int SLAM = 1, INK = 2, GRIP = 3, BITE = 4, JET = 5, CAGE = 6;
        static final int ARMS = 8, SEGS = 3;
        final Wild.Part body;
        final Wild.Part[][] arms = new Wild.Part[ARMS][SEGS];
        int slamArm = -1;
        Location mark, lineTo;
        Player gripped;
        double gripHp;
        final List<Location> cage = new ArrayList<>();

        Inkjaw(Wild w, Location at, Player by) {
            super(w, "inkjaw", at, by, 1.6);
            body = rig.add("inkjaw_body", k, pos);
            for (int i = 0; i < ARMS; i++) for (int s = 0; s < SEGS; s++) arms[i][s] = rig.add("inkjaw_tentacle", k * (1 - s * 0.22), pos);
            hitbox(pos, Math.max(2, (int) Math.round(2.6 * k)), "Old Inkjaw");
            world.playSound(pos, Sound.ENTITY_SQUID_AMBIENT, SoundCategory.HOSTILE, 3f, 0.3f);
        }

        @Override double defHealth() { return 500; }
        @Override double perFighter() { return c("health-per-extra-fighter", 0.2); }
        @Override BarColor barColor() { return BarColor.RED; }
        @Override String music() { return "minecraft:music_disc.far"; }
        @Override double musicLength() { return 174; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.RED + "" + ChatColor.BOLD + "Eight arms unfurl from the rocks... OLD INKJAW wants a word.";
                case "phase2" -> ChatColor.RED + "Old Inkjaw flushes a deep, angry red.";
                case "phase3" -> ChatColor.DARK_RED + "Old Inkjaw thrashes every arm at once!";
                case "death" -> ChatColor.RED + "Old Inkjaw lets go, at last, and drifts down into the ink.";
                case "leave" -> ChatColor.RED + "Old Inkjaw " + ChatColor.GRAY + "squeezes back into its crevice.";
                default -> null;
            };
        }

        @Override double damageTaken(double amount) {
            if (gripped != null) { gripHp += amount; if (gripHp >= c("grip-break", 40)) releaseGrip(true); }
            return amount;
        }

        @Override
        void bodyTick(List<Player> a) {
            if (attack < 0 || attack == INK || attack == CAGE || attack == SLAM) swimTo(circle(c("orbit-radius", 9), 2, 0.016), c("speed", 0.3), 6);
            pose(0);
            if (gripped != null) gripTick();
        }

        /** Each arm hangs from the body's rim and curls with a slow wave; the slamming arm reaches for its mark. */
        @Override
        void pose(int deathT) {
            float roll = (float) (deathT > 0 ? Math.min(140, deathT * 2.5) : Math.sin(ticks * 0.06) * 5);
            float bp = (float) Math.max(-30, Math.min(30, pitch * 0.5)) - (float) (flinchAmt() * 12);
            body.pose(pos, yaw, bp, roll, null);
            Quaternionf bq = Lich.frame(yaw, bp, roll);
            for (int i = 0; i < ARMS; i++) {
                double ang = i * 2 * Math.PI / ARMS;
                Location root = Lich.joint(pos, bq, Math.cos(ang) * 0.7, -0.6, Math.sin(ang) * 0.7, k);
                Location p = root;
                double outYaw = yaw + Math.toDegrees(ang) - 90;
                for (int s = 0; s < SEGS; s++) {
                    double curl = 45 + Math.sin(ticks * 0.12 + i * 0.8 + s * 0.6) * 18 + s * 12;
                    float py = (float) outYaw, pp = (float) curl;
                    if (i == slamArm && attack == SLAM && mark != null) { // this arm reaches for the mark
                        Vector to = mark.toVector().subtract(p.toVector());
                        double lift = at < 22 ? -70 : 60;
                        py = yawOf(flat(to)); pp = (float) (at < 22 ? lift : pitchOf(to));
                    }
                    if (gripped != null && i == 0) { Vector to = gripped.getLocation().add(0, 1, 0).toVector().subtract(p.toVector()); py = yawOf(flat(to)); pp = pitchOf(to); }
                    double len = 1.25 * k * (1 - s * 0.22);
                    arms[i][s].pose(p, py, pp, 0, null);
                    p = p.clone().add(dir3(py, pp).multiply(len));
                }
                if (i == slamArm) lineTo = p;
            }
            if (!hitboxes.isEmpty()) hitboxes.get(0).teleport(pos.clone().add(0, -0.6 * k, 0));
        }

        void gripTick() {
            if (!gripped.isOnline() || gripped.isDead() || !survival(gripped) || !gripped.getWorld().equals(world)) { releaseGrip(false); return; }
            Location beak = pos.clone().add(0, -1.2 * k, 0).add(dir3(yaw, 0).multiply(0.8 * k));
            Vector pull = beak.toVector().subtract(gripped.getLocation().toVector());
            Safe.vel(gripped, pull.lengthSquared() > 0.25 ? pull.normalize().multiply(0.35) : new Vector());
            if (ticks % 15 == 0 && pull.lengthSquared() < 9) { hit(gripped, c("moves.grip-bite", 6), pos.toVector(), Guard.UNBLOCKABLE); world.playSound(pos, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 2f, 0.6f); }
        }

        void releaseGrip(boolean broke) {
            Player g = gripped; gripped = null;
            if (g != null && g.isOnline()) g.sendActionBar(legacy(broke ? ChatColor.GREEN + "The arm lets go!" : ChatColor.GRAY + "It drops you."));
        }

        @Override
        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(SLAM, INK, GRIP, BITE, JET));
            if (phase >= 2) { pool.add(CAGE); pool.add(SLAM); }
            return pool.get(random.nextInt(pool.size()));
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear(); cage.clear();
            t = targetOr(t); focus = t;
            switch (move) {
                case SLAM -> { slamArm = random.nextInt(ARMS); mark = t != null ? t.getLocation().add(0, 0.5, 0) : pos.clone(); }
                case GRIP -> gripHp = 0;
                case JET -> { mark = t != null ? t.getLocation().add(0, 1, 0) : pos.clone().add(dirOf(yaw).multiply(10)); world.playSound(pos, Sound.ENTITY_SQUID_SQUIRT, SoundCategory.HOSTILE, 3f, 0.4f); }
                case CAGE -> {
                    if (t != null) for (int i = 0; i < 8; i++) { double ang = i * Math.PI / 4; cage.add(t.getLocation().add(Math.cos(ang) * 4.5, 0.5, Math.sin(ang) * 4.5)); }
                    mark = t != null ? t.getLocation() : pos.clone();
                    say(ChatColor.RED + "Arms close in around you...");
                }
                default -> { }
            }
        }

        @Override
        void moveTick(List<Player> a) {
            Player t = focus;
            switch (attack) {
                case SLAM -> {
                    if (at < 22) { if (at % 3 == 0) warnRing(mark, 2.6, Color.RED); return; }
                    if (at == 26) {
                        world.playSound(mark, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.7f);
                        world.spawnParticle(Particle.BUBBLE_POP, mark, 80, 2, 1, 2, 0.2);
                        strike(mark, 2.8, c("moves.slam", 11), Guard.HEAVY, new Vector(0, -0.6, 0));
                    }
                    if (at > 36) { slamArm = -1; end(); }
                }
                case INK -> {
                    if (at == 8) {
                        world.playSound(pos, Sound.ENTITY_SQUID_SQUIRT, SoundCategory.HOSTILE, 3f, 0.5f);
                        world.spawnParticle(Particle.SQUID_INK, pos, 200, 5, 3, 5, 0.05);
                        for (Player p : within(pos, c("ink-radius", 12))) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
                        }
                    }
                    if (at > 20) end();
                }
                case GRIP -> {
                    if (gripped == null && t != null) {
                        swimTo(t.getLocation().add(0, 3, 0), 0.5, 12);
                        if (pos.distanceSquared(t.getLocation()) < Math.pow(4 * k, 2)) { gripped = t; t.sendActionBar(legacy(ChatColor.RED + "An arm wraps around you! " + ChatColor.WHITE + "Hit Inkjaw to make it let go!")); }
                    }
                    if (at > (gripped != null ? 110 : 60)) { releaseGrip(false); end(); }
                }
                case BITE -> {
                    if (t == null) { end(); return; }
                    if (at < 16) { swimTo(t.getLocation().add(0, 1, 0), 0.25, 10); return; }
                    if (at < 28) { swimTo(t.getLocation().add(0, 1, 0), 0.9, 16); strike(pos.clone().add(0, -1 * k, 0), 2.4 * k, c("moves.bite", 12), Guard.BLOCKABLE, null); }
                    if (at > 34) end();
                }
                case JET -> {
                    if (at < 10) { swimTo(mark, 0.1, 20); return; }
                    Vector d = mark.toVector().subtract(pos.toVector());
                    if (d.lengthSquared() < 1 || at > 40) { end(); return; }
                    Location n = pos.clone().add(d.normalize().multiply(c("jet-speed", 1.2)));
                    if (open(n, 1)) pos = n; else { end(); return; }
                    yaw = yawOf(flat(d)); pitch = pitchOf(d);
                    world.spawnParticle(Particle.SQUID_INK, pos, 25, 0.8, 0.8, 0.8, 0.02);
                    if (at % 4 == 0) world.spawn(pos.clone(), AreaEffectCloud.class, cl -> {
                        cl.setRadius(2f); cl.setDuration(80); cl.setColor(Color.fromRGB(20, 20, 30));
                        cl.addCustomEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0), true);
                        cl.addCustomEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0), true);
                        cl.addScoreboardTag(Wild.FX_TAG);
                    });
                    strike(pos, 2.4 * k, c("moves.jet", 10), Guard.HEAVY, d.clone().normalize().multiply(0.8));
                }
                case CAGE -> {
                    if (at < 40) {
                        if (at % 3 == 0) for (int i = 0; i < cage.size(); i++) {
                            Location cl = cage.get(i), toward = mark.clone().add(0, 0.5, 0);
                            Location c2 = cl.clone().add(toward.toVector().subtract(cl.toVector()).multiply(at / 45.0));
                            for (int y = -1; y < 4; y++) world.spawnParticle(Particle.DUST, c2.clone().add(0, y, 0), 1, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(180, 40, 40), 1.6f));
                        }
                        return;
                    }
                    if (at == 40) {
                        world.playSound(mark, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 3f, 0.5f);
                        for (Player p : a) if (p.getLocation().distanceSquared(mark) < 4.5 * 4.5) { hit(p, c("moves.cage", 13), mark.toVector(), Guard.UNBLOCKABLE); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2)); }
                    }
                    if (at > 48) end();
                }
                default -> end();
            }
        }

        @Override void end() { if (attack == GRIP) releaseGrip(false); super.end(); }

        @Override void cleanup() { releaseGrip(false); }

        @Override void drops(Player p, Location at) {
            pl.dropLocked(at, p, new ItemStack(Material.INK_SAC, 16 + random.nextInt(16)));
            darkLoot(p, 0.1, 1);
        }

        @Override
        List<MoveSlot> morphMoves() {
            List<MoveSlot> m = new ArrayList<>(List.of(new MoveSlot(SLAM, "Tentacle Slam", Material.KELP), new MoveSlot(INK, "Ink Cloud", Material.INK_SAC),
                    new MoveSlot(GRIP, "The Grip", Material.LEAD), new MoveSlot(BITE, "Beak Bite", Material.BONE), new MoveSlot(JET, "Ink Jet", Material.GLOW_INK_SAC)));
            if (phase >= 2) m.add(new MoveSlot(CAGE, "Tentacle Cage", Material.IRON_BARS));
            return m;
        }
    }

    // =====================================================================================================
    //  THE GRANDMAW CLAM
    // =====================================================================================================
    static final class Grandmaw extends Swimmer {
        static final int SHELL = 1, SLAM = 2, RING = 3, SHOT = 4, RIPTIDE = 5, BUBBLE = 6;
        final Wild.Part shell, lid, pearl;
        double open = 25, wantOpen = 25;
        boolean shut;
        Location mark, from;
        Player trapped;
        ItemDisplay bubbleFx;

        Grandmaw(Wild w, Location at, Player by) {
            super(w, "grandmaw", at, by, 2.4);
            pos = floorUnder(pos, 30); // it sits on the sea floor
            shell = rig.add("grandmaw_shell", k, pos);
            lid = rig.add("grandmaw_lid", k, pos);
            pearl = rig.add("grandmaw_pearl", k, pos);
            hitbox(pos.clone().add(0, 0.6 * k, 0), Math.max(2, (int) Math.round(3 * k)), "The Grandmaw Clam");
            world.playSound(pos, Sound.BLOCK_SHULKER_BOX_OPEN, SoundCategory.HOSTILE, 3f, 0.3f);
        }

        @Override double defHealth() { return 900; }
        @Override BarColor barColor() { return BarColor.WHITE; }
        @Override String music() { return "minecraft:music_disc.stal"; }
        @Override double musicLength() { return 150; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.AQUA + "" + ChatColor.BOLD + "The sand shifts... THE GRANDMAW CLAM yawns open!";
                case "phase2" -> ChatColor.AQUA + "The Grandmaw Clam clacks its shell in warning.";
                case "phase3" -> ChatColor.RED + "The Grandmaw Clam is furious! Pearls everywhere!";
                case "death" -> ChatColor.AQUA + "The Grandmaw Clam falls open, its great pearl dimming.";
                case "leave" -> ChatColor.AQUA + "The Grandmaw Clam " + ChatColor.GRAY + "shuts tight and buries itself in the sand.";
                default -> null;
            };
        }

        @Override double damageTaken(double amount) { return shut ? amount * (1 - c("shut-reduction", 0.85)) : amount; }

        @Override void bodyTick(List<Player> a) {
            if (focus != null && focus.isOnline() && focus.getWorld().equals(world)) yaw = turn(yaw, yawOf(flat(focus.getLocation().toVector().subtract(pos.toVector()))), 3);
            if (trapped != null) bubbleTick();
            pose(0);
        }

        @Override
        void pose(int deathT) {
            if (deathT > 0) wantOpen = 70;
            open += (wantOpen - open) * (wantOpen < open ? 0.5 : 0.15);
            shut = open < 6;
            float roll = (float) (flinchAmt() * 6);
            shell.pose(pos, yaw, 0, roll, null);
            Quaternionf q = Lich.frame(yaw, 0, roll);
            lid.pose(Lich.joint(pos, q, -1.0, 0.75, 0, k), yaw, 0, roll, Lich.rotZ(open));
            pearl.pose(Lich.joint(pos, q, 0.1, 0.75 + Math.sin(ticks * 0.08) * 0.05, 0, k), yaw + ticks, 0, 0, null);
            pearl.hide(open < 10);
            if (!hitboxes.isEmpty()) hitboxes.get(0).teleport(pos.clone().add(0, 0.4 * k, 0));
        }

        @Override
        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(SHELL, SLAM, RING, SHOT, RIPTIDE, BUBBLE));
            if (phase == 3) { pool.add(RING); pool.add(SLAM); }
            return pool.get(random.nextInt(pool.size()));
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear();
            t = targetOr(t); focus = t;
            switch (move) {
                case SHELL -> { wantOpen = 0; world.playSound(pos, Sound.BLOCK_SHULKER_BOX_CLOSE, SoundCategory.HOSTILE, 3f, 0.4f); }
                case SLAM -> { from = pos.clone(); mark = t != null ? floorUnder(t.getLocation(), 30) : pos.clone(); wantOpen = 4; }
                case RIPTIDE -> { wantOpen = 70; say(ChatColor.AQUA + "The Grandmaw Clam gapes wide... the water rushes in!"); }
                case BUBBLE -> trapped = null;
                default -> wantOpen = 40;
            }
        }

        @Override
        void moveTick(List<Player> a) {
            Player t = focus;
            switch (attack) {
                case SHELL -> { if (at > 80) { wantOpen = 25; end(); } }
                case SLAM -> {
                    int dur = 34;
                    if (at < 12) { if (at % 3 == 0) warnRing(mark, 5, Color.WHITE); return; }
                    double f = Math.min(1, (at - 12) / (double) dur);
                    Location p = from.clone().add(mark.toVector().subtract(from.toVector()).multiply(f));
                    p.add(0, Math.sin(Math.PI * f) * c("slam-height", 8), 0);
                    pos = p;
                    if (f >= 1) {
                        pos = mark.clone();
                        world.playSound(pos, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.6f);
                        world.spawnParticle(Particle.BLOCK, pos, 120, 3, 0.5, 3, 0, Material.SAND.createBlockData());
                        for (Player pp : a) if (pp.getLocation().distanceSquared(pos) < 25) { hit(pp, c("moves.slam", 13), pos.toVector(), Guard.HEAVY); Safe.vel(pp, flat(pp.getLocation().toVector().subtract(pos.toVector())).multiply(1.2).setY(0.8)); }
                        wantOpen = 25;
                        end();
                    }
                }
                case RING -> {
                    if (at == 14) {
                        int n = 10 + phase * 3;
                        world.playSound(pos, Sound.ENTITY_SHULKER_SHOOT, SoundCategory.HOSTILE, 3f, 0.6f);
                        for (int i = 0; i < n; i++) {
                            double ang = i * Math.PI * 2 / n;
                            bolt(pos.clone().add(0, 1 * k, 0), new Vector(Math.cos(ang), 0.05, Math.sin(ang)).multiply(0.5), 60, 0.4, c("moves.pearl", 6), Particle.END_ROD, new ItemStack(Material.SNOWBALL), null, 0, null);
                        }
                    }
                    if (at > 26) { wantOpen = 25; end(); }
                }
                case SHOT -> {
                    if (at % 8 == 0 && at <= 32) for (Player p : a) {
                        Vector v = unit(p.getLocation().add(0, 1, 0).toVector().subtract(pos.clone().add(0, k, 0).toVector())).multiply(0.8);
                        bolt(pos.clone().add(0, 1 * k, 0), v, 60, 0.45, c("moves.pearl-shot", 7), Particle.END_ROD, new ItemStack(Material.SNOWBALL), null, 0, null);
                    }
                    if (at > 40) { wantOpen = 25; end(); }
                }
                case RIPTIDE -> {
                    if (at < 70) {
                        if (at % 3 == 0) world.spawnParticle(Particle.BUBBLE, pos.clone().add(0, k, 0), 30, 6, 3, 6, 0.3);
                        for (Player p : within(pos, c("riptide-radius", 16))) {
                            Vector in = pos.clone().add(0, k, 0).toVector().subtract(p.getLocation().toVector());
                            if (in.lengthSquared() > 0.25) Safe.vel(p, p.getVelocity().multiply(0.8).add(in.normalize().multiply(c("riptide-pull", 0.11))));
                        }
                        return;
                    }
                    if (at == 70) {
                        wantOpen = 0;
                        world.playSound(pos, Sound.BLOCK_SHULKER_BOX_CLOSE, SoundCategory.HOSTILE, 4f, 0.3f);
                        strike(pos.clone().add(0, k, 0), 3.8 * k, c("moves.snap", 14), Guard.HEAVY, new Vector(0, 0.7, 0));
                    }
                    if (at > 84) { wantOpen = 25; end(); }
                }
                case BUBBLE -> {
                    if (at == 10 && t != null) {
                        trapped = t;
                        bubbleFx = world.spawn(t.getLocation().add(0, 1, 0), ItemDisplay.class, d -> {
                            d.setItemStack(new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS)); d.setPersistent(false); d.addScoreboardTag(Wild.FX_TAG);
                            d.setTeleportDuration(2);
                            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.2f, 2.2f, 2.2f), new Quaternionf()));
                        });
                        t.sendActionBar(legacy(ChatColor.AQUA + "You're caught in a Brine Bubble!"));
                    }
                    if (at > 80) { popBubble(true); end(); }
                }
                default -> end();
            }
        }

        void bubbleTick() {
            if (!trapped.isOnline() || trapped.isDead() || !trapped.getWorld().equals(world)) { popBubble(false); return; }
            Safe.vel(trapped, new Vector(0, 0.12, 0));
            if (bubbleFx != null && bubbleFx.isValid()) bubbleFx.teleport(trapped.getLocation().add(0, 1, 0));
            if (ticks % 5 == 0) world.spawnParticle(Particle.BUBBLE, trapped.getLocation().add(0, 1, 0), 6, 0.6, 0.6, 0.6, 0.02);
        }

        void popBubble(boolean hurt) {
            Player t = trapped; trapped = null;
            if (bubbleFx != null && bubbleFx.isValid()) bubbleFx.remove();
            bubbleFx = null;
            if (t != null && t.isOnline()) {
                world.spawnParticle(Particle.BUBBLE_POP, t.getLocation().add(0, 1, 0), 40, 0.8, 0.8, 0.8, 0.1);
                world.playSound(t.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.HOSTILE, 2f, 0.5f);
                if (hurt) hit(t, c("moves.bubble-pop", 8), t.getLocation().toVector(), Guard.UNBLOCKABLE);
            }
        }

        @Override void end() { if (attack == BUBBLE) popBubble(false); super.end(); }

        @Override void cleanup() { popBubble(false); }

        @Override void drops(Player p, Location at) {
            if (random.nextDouble() < c("rewards.shield-of-the-ocean-chance", 0.5)) {
                pl.console("giveaccessory shield 1 " + p.getName(), p);
                p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Shield of the Ocean" + ChatColor.GREEN + " dropped for you!");
            }
            pl.dropLocked(at, p, new ItemStack(Material.PRISMARINE_CRYSTALS, 8 + random.nextInt(8)));
            darkLoot(p, 0.1, 1);
        }

        @Override
        List<MoveSlot> morphMoves() {
            return List.of(new MoveSlot(SHELL, "Shell Up", Material.SHULKER_SHELL), new MoveSlot(SLAM, "Slam", Material.ANVIL),
                    new MoveSlot(RING, "Pearl Ring", Material.SNOWBALL), new MoveSlot(SHOT, "Pearl Shot", Material.ENDER_PEARL),
                    new MoveSlot(RIPTIDE, "Riptide Snap", Material.HEART_OF_THE_SEA), new MoveSlot(BUBBLE, "Brine Bubble", Material.LIGHT_BLUE_STAINED_GLASS));
        }
    }

    // =====================================================================================================
    //  THE LAMENT
    // =====================================================================================================
    static final class Lament extends Serpent {
        static final int LUNGE = 1, HUNT = 2, WAIL = 3, WHISPERS = 4, TIDE = 5, LIGHTEATER = 6;
        boolean hidden;

        Lament(Wild w, Location at, Player by) {
            super(w, "lament", at, by, 2.6);
            build("lament_head", "lament_body", "lament_tail", 2, 2.3, 0.12, 1, "The Lament");
            world.playSound(pos, Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 3f, 0.5f);
        }

        @Override double defHealth() { return 900; }
        @Override BarColor barColor() { return BarColor.BLUE; }
        @Override String music() { return "minecraft:music_disc.11"; }
        @Override double musicLength() { return 71; }

        @Override String line(String what) {
            return switch (what) {
                case "spawn" -> ChatColor.BLUE + "" + ChatColor.BOLD + "Something grieves in the dark... THE LAMENT is coming.";
                case "phase2" -> ChatColor.BLUE + "The Lament's sorrow turns to rage.";
                case "phase3" -> ChatColor.DARK_BLUE + "The Lament howls, and the dark howls with it.";
                case "death" -> ChatColor.BLUE + "The Lament goes quiet. For the first time in a long time, the dark is just dark.";
                case "leave" -> ChatColor.BLUE + "The Lament " + ChatColor.GRAY + "sinks away, still weeping.";
                default -> null;
            };
        }

        @Override double damageTaken(double amount) { return hidden ? amount * 0.2 : amount; }

        void hide(boolean h) { hidden = h; for (Wild.Part p : rig.parts) p.hide(h); }

        @Override
        void bodyTick(List<Player> a) {
            if (attack < 0 || attack == WAIL || attack == WHISPERS || attack == LIGHTEATER) swimTo(circle(c("orbit-radius", 13), 2, 0.02), c("speed", 0.4) * (phase == 3 ? 1.2 : 1), 6);
            pose(0);
            if (!hidden && ticks % 5 == 0) world.spawnParticle(Particle.SQUID_INK, chain.pts.get(random.nextInt(chain.pts.size())), 2, 1, 1, 1, 0.01);
        }

        @Override
        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(LUNGE, HUNT, WAIL, TIDE, LIGHTEATER));
            if (minions.size() < 4) pool.add(WHISPERS);
            if (phase >= 2) { pool.add(HUNT); pool.add(LUNGE); }
            return pool.get(random.nextInt(pool.size()));
        }

        @Override
        void startMove(int move, Player t) {
            hitOnce.clear();
            focus = targetOr(t);
            if (move == HUNT) { hide(true); say(ChatColor.BLUE + "The Lament slips into the dark... where is it?"); }
            if (move == WAIL) world.playSound(pos, Sound.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 3f, 0.4f);
        }

        @Override
        void moveTick(List<Player> a) {
            Player t = focus;
            switch (attack) {
                case LUNGE -> {
                    if (t == null) { end(); return; }
                    if (at < 18) { swimTo(t.getLocation().add(0, 1, 0).add(dirOf(yaw).multiply(-10)), 0.4, 12); return; }
                    if (at < 34) { swimTo(t.getLocation().add(0, 1, 0), 1.2, 18); strike(pos, 2.6 * k, c("moves.lunge", 13), Guard.HEAVY, null); }
                    if (at > 40) end();
                }
                case HUNT -> {
                    if (t == null) { hide(false); end(); return; }
                    if (at < 60) { swimTo(t.getLocation().add(Math.cos(at * 0.1) * 14, 1, Math.sin(at * 0.1) * 14), 0.9, 20); if (at % 10 == 0) t.playSound(t.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.6f); return; }
                    if (at == 60) { hide(false); world.playSound(pos, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.8f); }
                    if (at < 76) { swimTo(t.getLocation().add(0, 1, 0), 1.3, 22); strike(pos, 2.8 * k, c("moves.hunt", 14), Guard.HEAVY, null); }
                    if (at > 80) end();
                }
                case WAIL -> {
                    if (at == 14) for (Player p : within(pos, c("wail-radius", 26))) {
                        hit(p, c("moves.wail", 4), pos.toVector(), Guard.UNBLOCKABLE);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0));
                    }
                    if (at > 26) end();
                }
                case WHISPERS -> {
                    if (at == 10) for (int i = 0; i < 2 + phase; i++) {
                        Location l = openWater(pos, 3, 6, 1);
                        if (l == null) continue;
                        Player tt = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                        Drowned d = minion(l, EntityType.DROWNED, Drowned.class, ChatColor.DARK_GRAY + "Hollow Kin", 18, m -> {
                            m.getEquipment().setHelmet(new ItemStack(Material.BLACK_STAINED_GLASS)); m.getEquipment().setHelmetDropChance(0);
                            m.setShouldBurnInDay(false);
                            m.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
                        });
                        if (tt != null) d.setTarget(tt);
                        world.spawnParticle(Particle.SQUID_INK, l, 20, 0.4, 0.8, 0.4, 0.02);
                    }
                    if (at > 20) end();
                }
                case TIDE -> {
                    if (at < 20) { if (at % 4 == 0) sphereFx(pos, 3 + at / 4.0, Particle.BUBBLE, 40); return; }
                    if (at == 20) {
                        world.playSound(pos, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.HOSTILE, 3f, 0.4f);
                        for (Player p : within(pos, c("tide-radius", 18))) {
                            hit(p, c("moves.tide", 6), pos.toVector(), Guard.UNBLOCKABLE);
                            Vector away = p.getLocation().toVector().subtract(pos.toVector());
                            Safe.vel(p, (away.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : away.normalize()).multiply(1.6).setY(0.3));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                        }
                    }
                    if (at > 30) end();
                }
                case LIGHTEATER -> {
                    if (at == 12) {
                        world.playSound(pos, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.HOSTILE, 3f, 0.4f);
                        for (Player p : within(pos, 30)) {
                            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                            p.removePotionEffect(PotionEffectType.CONDUIT_POWER);
                            if (holdsLight(p)) {
                                hit(p, c("moves.light-eater", 8), pos.toVector(), Guard.UNBLOCKABLE);
                                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
                                p.sendActionBar(legacy(ChatColor.BLUE + "It hates your light."));
                                focus = p;
                            }
                        }
                    }
                    if (at > 20 && focus != null) { swimTo(focus.getLocation().add(0, 1, 0), 0.9, 14); strike(pos, 2.4 * k, c("moves.light-bite", 9), Guard.BLOCKABLE, null); }
                    if (at > 44) end();
                }
                default -> end();
            }
        }

        static boolean holdsLight(Player p) {
            Material a = p.getInventory().getItemInMainHand().getType(), b = p.getInventory().getItemInOffHand().getType();
            return a == Material.TORCH || b == Material.TORCH || a == Material.LANTERN || b == Material.LANTERN || a == Material.SEA_LANTERN || b == Material.SEA_LANTERN
                    || a == Material.GLOWSTONE || b == Material.GLOWSTONE || a == Material.SEA_PICKLE || b == Material.SEA_PICKLE || a == Material.SOUL_LANTERN || b == Material.SOUL_LANTERN
                    || a == Material.SOUL_TORCH || b == Material.SOUL_TORCH;
        }

        @Override void end() { if (hidden) hide(false); super.end(); }

        @Override void drops(Player p, Location at) {
            if (random.nextDouble() < 0.3) pl.dropLocked(at, p, new ItemStack(Material.ECHO_SHARD, 1 + random.nextInt(2)));
            darkLoot(p, 0.1, 1);
        }

        @Override
        List<MoveSlot> morphMoves() {
            List<MoveSlot> m = new ArrayList<>(List.of(new MoveSlot(LUNGE, "Lunge", Material.COD), new MoveSlot(HUNT, "The Hunt", Material.BLACK_DYE),
                    new MoveSlot(WAIL, "Wail", Material.GHAST_TEAR), new MoveSlot(WHISPERS, "Whispers", Material.SCULK), new MoveSlot(TIDE, "Sorrow Tide", Material.WATER_BUCKET),
                    new MoveSlot(LIGHTEATER, "Light Eater", Material.SOUL_LANTERN)));
            return m;
        }
    }
}
