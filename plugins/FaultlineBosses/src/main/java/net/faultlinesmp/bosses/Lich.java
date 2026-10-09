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
import org.bukkit.boss.BarColor;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
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
 * THE LICH: an undead sorcerer-king, 2,800 health, who haunts the Deep Dark. Break a Cursed Phylactery there to call
 * him. He floats, keeps his distance, and blinks around.
 *   Phase 1: Soul Bolts (homing skulls), Raise the Fallen (Revenants: zombies wearing YOUR faces), Death Ring (a ring of
 *            soul fire races out along the floor: be in the air when it passes), Blink Strike (he appears behind you),
 *            Grasp of the Grave (bone hands burst out of the floor under everyone and hold them).
 *   Phase 2 (60%): he hides his life in three Phylacteries (end crystals, beams to him): he can't be hurt until
 *            they're broken. + Darkness, + Bone Spears (lines of bone spikes run out along the floor at you).
 *   Phase 3 (30%): + Life Drain (beams from everyone near him heal him: break line of sight or get away).
 * 1.5.0: a jointed rig (body, head, two arms, staff, cape) instead of one model, so every move has its own pose.
 */
final class Lich extends Wild.Boss {

    static final int BOLTS = 1, RAISE = 2, RING = 3, BLINK = 4, DARKNESS = 5, DRAIN = 6, GRASP = 7, SPEARS = 8;
    static final String CRYSTAL_TAG = "faultline_lich_crystal", REVENANT_TAG = "faultline_revenant";

    final double k;
    final Wild.Part body, head, armL, armR, staff, cape;
    final List<BlockDisplay> bones = new ArrayList<>();
    final List<Location> marks = new ArrayList<>();
    final List<Vector> dirs = new ArrayList<>();
    Location pos;
    float yaw;
    final List<EnderCrystal> crystals = new ArrayList<>();
    boolean shielded;
    // the current move
    Location mark;
    double ringR;
    final Set<UUID> hitThisMove = new HashSet<>();

    Lich(Wild w, Location at, Player by) {
        super(w, "lich", at, by);
        k = c("scale", 1.3);
        pos = Wild.freeSpot(at, 5, 9); // a free spot in the cave near you (not up on the surface)
        pos.setY(pos.getY() + 0.4);
        yaw = at.getYaw() + 180;
        cape = rig.add("lich_cape", k, pos);
        body = rig.add("lich_body", k, pos);
        head = rig.add("lich_head", k, pos);
        armL = rig.add("lich_arm", k, pos);
        armR = rig.add("lich_arm", k, pos);
        staff = rig.add("lich_staff", k, pos);
        hitbox(pos, (int) Math.round(3 * k), "The Lich");
        hitbox(pos, (int) Math.round(3 * k), "The Lich");
        world.strikeLightningEffect(pos);
        world.spawnParticle(Particle.SOUL, pos.clone().add(0, 1.5, 0), 80, 1, 1.5, 1, 0.05);
        world.playSound(pos, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 3f, 0.6f);
        cd = 50;
    }

    @Override double defHealth() { return 900; } // a mini boss, like the Stone Golem
    @Override BarColor barColor() { return BarColor.PURPLE; }
    @Override String music() { return "minecraft:music_disc.13"; }
    @Override double musicLength() { return 178; }
    @Override Location center() { return pos.clone().add(0, 1.4 * k, 0); }
    @Override boolean valid() { return body.d != null && body.d.isValid(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.DARK_PURPLE + "\"You broke my vessel. Then you will take its place.\"";
            case "phase2" -> ChatColor.DARK_PURPLE + "\"My life is not here. It never was.\" " + ChatColor.GRAY + "(Break the three Phylacteries!)";
            case "phase3" -> ChatColor.RED + "\"Give me your warmth... ALL of it.\"";
            case "death" -> ChatColor.DARK_PURPLE + "\"Impossible... I am... eternal...\"";
            case "leave" -> ChatColor.DARK_PURPLE + "The Lich " + ChatColor.GRAY + "fades back into the dark.";
            default -> null;
        };
    }

    // =====================================================================================================
    //  floating
    // =====================================================================================================
    @Override
    void bodyTick(List<Player> a) {
        Player t = pilot != null ? pilot : focus != null && focus.isOnline() && a.contains(focus) ? focus : a.isEmpty() ? null : a.get(0);
        if (attack != BLINK && t != null) {
            Vector to = t.getLocation().toVector().subtract(pos.toVector()).setY(0);
            double d = to.length();
            yaw = turn(yaw, yawOf(flat(to)), 10);
            double want = pilot != null ? 2.5 : c("keep-distance", 9);
            Vector step;
            if (pilot != null) step = d > want ? flat(to).multiply(Math.min(0.4, d - want)) : new Vector();
            else if (d > want + 2) step = flat(to).multiply(0.16);
            else if (d < want - 3) step = flat(to).multiply(-0.16);
            else step = new Vector(-to.getZ(), 0, to.getX()).normalize().multiply(0.06); // drift sideways
            // never through the cave walls: only where he fits (try the move, then each half of it)
            for (Vector v : new Vector[]{step, new Vector(step.getX(), 0, 0), new Vector(0, 0, step.getZ())}) {
                if (v.lengthSquared() < 1e-6) continue;
                Location next = pos.clone().add(v);
                if (Wild.fits(world, next, pos.getY(), 3)) { pos.add(v); break; }
            }
        }
        double gy = ground(pos);
        pos.setY(gy + 0.5 + Math.sin(ticks * 0.07) * 0.25);
        bones.removeIf(b -> !b.isValid());
        animate(t);
        for (int i = 0; i < hitboxes.size(); i++) hitboxes.get(i).teleport(pos.clone().add(0, i * 1.5 * k, 0));
        if (ticks % 3 == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos.clone().add(0, 0.2, 0), 2, 0.4, 0.1, 0.4, 0.01);
        if (ticks % 4 == 0 && lantern != null) world.spawnParticle(Particle.SOUL_FIRE_FLAME, lantern, 1, 0.04, 0.04, 0.04, 0.005);
        if (ticks % 7 == 0 && eyes != null) world.spawnParticle(Particle.DUST, eyes, 1, 0.08, 0.02, 0.08, 0, new Particle.DustOptions(Color.fromRGB(110, 255, 196), 0.7f));
        // the phylacteries' beams
        crystals.removeIf(cr -> !cr.isValid());
        if (shielded) {
            for (EnderCrystal cr : crystals) cr.setBeamTarget(center().getBlock().getLocation());
            if (crystals.isEmpty()) {
                shielded = false;
                say(ChatColor.LIGHT_PURPLE + "\"No... NO! My vessels!\"");
                world.playSound(pos, Sound.ENTITY_WITHER_HURT, SoundCategory.HOSTILE, 3f, 0.5f);
            } else if (ticks % 10 == 0) world.spawnParticle(Particle.ENCHANT, center(), 30, 0.8, 1.2, 0.8, 0.5);
        }
    }

    // =====================================================================================================
    //  the rig. Joints in the body's own frame (+x front, +y up, +z right), in blocks at scale 1 (tools/wild_assets.py
    //  lich()). Every angle eases toward the current move's target pose, so nothing snaps.
    // =====================================================================================================
    static final double WAIST = 2.02, HAND = 1.12, STAFF_LOW = 1.75;
    static final double[] SH_L = {0.0, 0.8, -0.5}, SH_R = {0.0, 0.8, 0.5}, NECK = {0.02, 0.86, 0}, CAPE = {-0.3, 0.9, 0};
    static final double[] LANTERN = {0.31, 0.77, 0};

    /** The eased pose: body lift/lean/roll/scale/spin, head turn, arms (swing forward, spread out), staff tilt, cape. */
    static final class Pose {
        double lift, lean, roll, scale = 1, spin, headYaw, headPitch, lSwing = 12, lSpread = 10, rSwing = 22, rSpread = 6, staff, cape = 10;

        void ease(Pose to, double e, double fast) {
            lift += (to.lift - lift) * e; lean += (to.lean - lean) * e; roll += (to.roll - roll) * e; spin += (to.spin - spin) * e;
            headYaw += (to.headYaw - headYaw) * e; headPitch += (to.headPitch - headPitch) * e;
            lSwing += (to.lSwing - lSwing) * fast; lSpread += (to.lSpread - lSpread) * fast;
            rSwing += (to.rSwing - rSwing) * fast; rSpread += (to.rSpread - rSpread) * fast;
            staff += (to.staff - staff) * fast; cape += (to.cape - cape) * 0.12;
        }
    }

    final Pose cur = new Pose();
    Location lastPos, lantern, eyes, handL, handR;

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

    /** Where the rig is drawn this tick. */
    void draw(Pose p, double kk) {
        float y = (float) (yaw + p.spin);
        Location waist = pos.clone().add(0, WAIST * kk + p.lift, 0);
        Quaternionf bq = frame(y, (float) p.lean, (float) p.roll);
        for (Wild.Part part : rig.parts) part.k = kk;
        body.pose(waist, y, (float) p.lean, (float) p.roll, null);
        Location neck = joint(waist, bq, NECK, kk);
        head.pose(neck, (float) (y + p.headYaw), (float) (p.lean + p.headPitch), (float) (p.roll * 0.5), null);
        eyes = joint(neck, frame((float) (y + p.headYaw), (float) (p.lean + p.headPitch), (float) (p.roll * 0.5)), 0.22, 0.28, 0, kk);
        cape.pose(joint(waist, bq, CAPE, kk), y, (float) p.lean, (float) p.roll, rotZ(-p.cape));
        Quaternionf lq = rotZ(p.lSwing).mul(rotX(p.lSpread)), rq = rotZ(p.rSwing).mul(rotX(-p.rSpread));
        Location shL = joint(waist, bq, SH_L, kk), shR = joint(waist, bq, SH_R, kk);
        armL.pose(shL, y, (float) p.lean, (float) p.roll, lq);
        armR.pose(shR, y, (float) p.lean, (float) p.roll, rq);
        handL = joint(shL, new Quaternionf(bq).mul(lq), 0, -HAND, 0, kk);
        handR = joint(shR, new Quaternionf(bq).mul(rq), 0, -HAND, 0, kk);
        if (staffDown == null) {
            staff.pose(handR, y, (float) p.lean, (float) p.roll, rotZ(p.staff));
            lantern = joint(handR, frame(y, (float) p.lean, (float) p.roll).mul(rotZ(p.staff)), LANTERN, kk);
        }
    }

    /** The pose each move wants (the idle one, plus whatever the move does with it). */
    Pose target() {
        Pose t = new Pose();
        t.lift = Math.sin(ticks * 0.07) * 0.1;
        t.roll = Math.sin(ticks * 0.05) * 3;
        t.lSwing = 14 + Math.sin(ticks * 0.06) * 5; t.lSpread = 12 + Math.sin(ticks * 0.05) * 4;
        t.rSwing = 24 + Math.sin(ticks * 0.06 + 1) * 3; t.rSpread = 8;
        t.staff = 3 + Math.sin(ticks * 0.06 + 1) * 2;
        // lean into the drift: forward when closing in, back when backing off; the cape trails behind
        double fwd = 0, side = 0;
        if (lastPos != null && lastPos.getWorld() == pos.getWorld()) {
            Vector mv = pos.toVector().subtract(lastPos.toVector()).setY(0);
            fwd = mv.dot(dirOf(yaw)); side = mv.dot(dirOf(yaw + 90));
        }
        lastPos = pos.clone();
        t.lean += Math.max(-8, Math.min(10, fwd * 60));
        t.roll += Math.max(-8, Math.min(8, side * 60));
        t.cape = 8 + Math.min(40, Math.abs(fwd) * 160 + Math.abs(side) * 60) + Math.sin(ticks * 0.13) * 4 + Math.sin(ticks * 0.31) * 2;
        // the head watches whoever he's after
        if (focus != null && focus.isOnline() && focus.getWorld().equals(world)) {
            Vector to = focus.getEyeLocation().toVector().subtract(center().add(0, 1.6 * k, 0).toVector());
            t.headYaw = Math.max(-50, Math.min(50, ((yawOf(to) - yaw + 540) % 360) - 180));
            t.headPitch = Math.max(-25, Math.min(30, pitchOf(to)));
        }
        switch (attack) {
            case BOLTS -> { // the left hand draws the skull back... and throws it
                int ph = at % 8;
                t.lift += 0.3;
                if (ph < 4) { t.lSwing = 50; t.lSpread = 40; t.lean -= 6; } else { t.lSwing = 98; t.lSpread = 4; t.lean += 8; }
            }
            case RAISE -> { // he rises with both arms high, staff lifted... then drives them down and the dead come up
                if (at < 16) {
                    t.lift += 0.9 * Math.min(1, at / 10.0); t.lean -= 14; t.headPitch = -25;
                    t.lSwing = 165; t.lSpread = 28; t.rSwing = 150; t.rSpread = 18; t.staff = -8;
                    t.roll += Math.sin(at * 0.6) * 4;
                } else { t.lift -= 0.3; t.lean += 20; t.headPitch = 25; t.lSwing = 30; t.lSpread = 55; t.rSwing = 35; t.rSpread = 25; t.staff = 10; }
            }
            case RING -> { // arms flung wide, palms out, turning slowly while the soul fire spreads
                t.lift += at < 18 ? 0.7 * at / 18.0 : 0.7;
                t.spin = at < 18 ? at * 4 : 72 + (at - 18) * 2;
                t.lean -= 6; t.headPitch = -10;
                t.lSwing = 25; t.lSpread = 78; t.rSwing = 22; t.rSpread = 62; t.staff = 0;
            }
            case BLINK -> { // fades to a speck, back behind you, staff overhead... and down
                if (at >= 15 && at < 19) { t.rSwing = 165; t.rSpread = 10; t.staff = -35; t.lean -= 14; t.lSwing = 40; t.lSpread = 35; }
                else if (at >= 19 && at <= 26) { t.rSwing = 55; t.rSpread = 0; t.staff = 105; t.lean += 26; t.lSwing = -20; t.lSpread = 30; }
            }
            case DARKNESS -> { // hunched over, arms crossed over the gathering dark... then thrown wide
                if (at < 10) { t.lean += 16; t.lift -= 0.25; t.headPitch = 30; t.lSwing = 60; t.lSpread = -38; t.rSwing = 55; t.rSpread = -22; t.staff = 20; }
                else { t.lean -= 22; t.lift += 0.3; t.headPitch = -25; t.lSwing = 70; t.lSpread = 88; t.rSwing = 60; t.rSpread = 65; t.staff = -10; }
            }
            case DRAIN -> { // hanging high, head back, both hands clawing at you, shuddering as he feeds
                t.lift += 1.1; t.lean -= 10; t.headPitch = -20; t.roll += Math.sin(ticks * 0.9) * 3;
                t.lSwing = 82 + Math.sin(ticks * 0.7) * 6; t.lSpread = 35; t.rSwing = 60; t.rSpread = 30; t.staff = 35;
            }
            case GRASP -> { // reaches down with the left hand, fingers to the floor... then yanks it up and the hands come
                if (at < 20) { t.lean += 22; t.lift -= 0.2; t.headPitch = 30; t.lSwing = 38 + Math.sin(at * 0.8) * 4; t.lSpread = 18; t.staff = 8; }
                else if (at < 34) { t.lean -= 12; t.headPitch = -10; t.lSwing = 140; t.lSpread = 10; t.rSwing = 30; }
            }
            case SPEARS -> { // the staff goes up high in both... and is driven into the floor
                if (at < 18) { t.lean -= 10; t.lift += 0.35; t.rSwing = 150; t.rSpread = 8; t.staff = -5; t.lSwing = 130; t.lSpread = 30; t.headPitch = -12; }
                else if (at < 30) { t.lean += 18; t.lift -= 0.35; t.rSwing = 38; t.rSpread = 2; t.staff = 0; t.lSwing = 30; t.lSpread = 45; t.headPitch = 20; }
            }
            default -> { }
        }
        if (shielded) { t.lift += 0.4; t.lSpread = Math.max(t.lSpread, 35); t.rSpread = Math.max(t.rSpread, 22); } // held up by the beams
        double f = flinchAmt();
        t.lean -= f * 14; t.roll += f * (ticks % 2 == 0 ? 5 : -5); t.headPitch -= f * 20; t.lSpread += f * 25;
        if (attack == BLINK) t.scale = at <= 8 ? Math.max(0.08, 1 - at / 8.0) : at <= 14 ? Math.min(1, (at - 8) / 6.0) : 1;
        return t;
    }

    void animate(Player focusNow) {
        Pose want = target();
        cur.ease(want, 0.22, 0.3);
        cur.scale += (want.scale - cur.scale) * (attack == BLINK ? 0.6 : 0.22);
        draw(cur, k * cur.scale);
    }

    @Override
    double damageTaken(double amount) {
        if (shielded) {
            if (ticks % 10 == 0) world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.HOSTILE, 2f, 0.6f);
            for (Player p : active()) if (p.getLocation().distanceSquared(pos) < 12 * 12) p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "His life is in the Phylacteries: break them first!"));
            return 0;
        }
        return amount;
    }

    @Override
    void onPhase(int p) {
        if (p == 2) {
            shielded = true;
            for (int i = 0; i < 3; i++) {
                double ang = i * Math.PI * 2 / 3 + random.nextDouble();
                Location l = home.clone().add(Math.cos(ang) * c("crystal-distance", 12), 0, Math.sin(ang) * c("crystal-distance", 12));
                l.setY(ground(l) + 1);
                EnderCrystal cr = Wild.spawnAs(world, l, EntityType.END_CRYSTAL, EnderCrystal.class, e -> {
                    e.setShowingBottom(false);
                    e.setPersistent(false);
                    e.addScoreboardTag(Wild.FX_TAG);
                    e.addScoreboardTag(CRYSTAL_TAG);
                    e.setCustomName(ChatColor.LIGHT_PURPLE + "Phylactery");
                });
                crystals.add(cr);
                world.spawnParticle(Particle.REVERSE_PORTAL, l, 60, 0.5, 1, 0.5, 0.1);
            }
        }
    }

    /** A Phylactery was hit by a player: it shatters. */
    void crystalBroken(EnderCrystal cr) {
        if (!crystals.remove(cr)) return;
        world.spawnParticle(Particle.EXPLOSION, cr.getLocation(), 2, 0.4, 0.4, 0.4, 0);
        world.spawnParticle(Particle.SOUL, cr.getLocation(), 40, 0.6, 0.8, 0.6, 0.05);
        world.playSound(cr.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
        cr.remove();
        if (!crystals.isEmpty()) say(ChatColor.GRAY + "A Phylactery shatters. (" + crystals.size() + " left)");
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(BOLTS, BOLTS, RING, BLINK));
        if (minions.size() < 3 + a.size()) pool.add(RAISE);
        pool.add(GRASP);
        if (phase >= 2) { pool.add(DARKNESS); pool.add(SPEARS); pool.add(SPEARS); }
        if (phase == 3) { pool.add(DRAIN); pool.add(DRAIN); }
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        hitThisMove.clear(); ringR = 0; marks.clear(); dirs.clear();
        switch (move) {
            case GRASP -> {
                for (Player p : active()) { Location m = p.getLocation().clone(); m.setY(ground(m)); marks.add(m); }
                if (marks.isEmpty() && t != null) { Location m = t.getLocation().clone(); m.setY(ground(m)); marks.add(m); }
                world.playSound(pos, Sound.ENTITY_WARDEN_DIG, SoundCategory.HOSTILE, 2.5f, 0.7f);
            }
            case SPEARS -> {
                Location from = pos.clone(); from.setY(ground(from));
                List<Player> ps = new ArrayList<>(active());
                Collections.shuffle(ps, random);
                for (Player p : ps.subList(0, Math.min(4, ps.size()))) { marks.add(from.clone()); dirs.add(flat(p.getLocation().toVector().subtract(from.toVector()))); }
                if (dirs.isEmpty()) { marks.add(from.clone()); dirs.add(dirOf(yaw)); }
                world.playSound(pos, Sound.ENTITY_SKELETON_AMBIENT, SoundCategory.HOSTILE, 3f, 0.4f);
            }
            case RING -> { mark = pos.clone(); mark.setY(ground(mark)); world.playSound(pos, Sound.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 3f, 0.6f); }
            case RAISE -> world.playSound(pos, Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 3f, 0.6f);
            case BLINK -> { mark = t != null ? t.getLocation().clone() : pos.clone(); world.playSound(pos, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2f, 0.5f); }
            case DARKNESS -> world.playSound(pos, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.6f);
            case DRAIN -> world.playSound(pos, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 3f, 0.5f);
            default -> world.playSound(pos, Sound.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 2f, 1.3f);
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case BOLTS -> bolts(a);
            case RAISE -> raise(a);
            case RING -> ring(a);
            case BLINK -> blink(a);
            case DARKNESS -> darkness(a);
            case DRAIN -> drain(a);
            case GRASP -> grasp(a);
            case SPEARS -> spears(a);
            default -> end();
        }
    }

    void bolts(List<Player> a) {
        int n = phase >= 2 ? 5 : 3;
        if (at % 8 == 4 && at / 8 < n) {
            Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
            if (t != null) {
                Location from = handL != null ? handL.clone().add(dirOf(yaw).multiply(0.4)) : center().add(0, 1.0 * k, 0).add(dirOf(yaw));
                Vector v = t.getEyeLocation().toVector().subtract(from.toVector()).normalize().multiply(0.8);
                WitherSkull s = world.spawn(from, WitherSkull.class, sk -> {
                    sk.setYield(0);
                    sk.setIsIncendiary(false);
                    sk.setCharged(phase == 3);
                    sk.addScoreboardTag(Wild.FX_TAG);
                    sk.addScoreboardTag("faultline_lich_bolt");
                });
                s.setDirection(v);
                s.setAcceleration(v.clone().multiply(0.1));
                if (source() != null) s.setShooter(source());
                Player target = t;
                int[] life = {0};
                fx.add(() -> { // they home in a little
                    if (!s.isValid() || ++life[0] > 80) { if (s.isValid()) s.remove(); return true; }
                    if (target.isOnline() && target.getWorld().equals(world)) {
                        Vector want = target.getEyeLocation().toVector().subtract(s.getLocation().toVector()).normalize();
                        Vector cur = s.getVelocity().lengthSquared() > 1e-4 ? s.getVelocity().clone().normalize() : want;
                        Vector nv = cur.multiply(0.88).add(want.multiply(0.12)).normalize().multiply(0.75);
                        Safe.vel(s, nv); s.setDirection(nv);
                    }
                    world.spawnParticle(Particle.SOUL, s.getLocation(), 2, 0.1, 0.1, 0.1, 0.01);
                    return false;
                });
            }
            world.playSound(pos, Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 2f, 0.8f);
        }
        if (at > n * 8 + 8) end();
    }

    /** A Lich bolt lands. */
    static void boltLands(Wild w, ProjectileHitEvent e) {
        e.setCancelled(true);
        Entity pr = e.getEntity();
        pr.getWorld().spawnParticle(Particle.SOUL, pr.getLocation(), 20, 0.5, 0.5, 0.5, 0.03);
        pr.getWorld().playSound(pr.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        Wild.Boss b = w.get("lich");
        if (e.getHitEntity() instanceof Player p && b != null) {
            b.hit(p, b.c("moves.soul-bolt", 7), pr.getLocation().toVector(), Guard.BLOCKABLE);
            if (survival(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
        }
        pr.remove();
    }

    /** Revenants: zombies wearing the faces of the people fighting him. */
    void raise(List<Player> a) {
        if (at == 16) {
            int n = 2 + Math.min(4, a.size());
            for (int i = 0; i < n; i++) {
                Player face = a.isEmpty() ? null : a.get(i % a.size());
                Location l = pos.clone().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
                l.setY(ground(l));
                Zombie z = minion(l, EntityType.ZOMBIE, Zombie.class, ChatColor.DARK_PURPLE + "Revenant" + (face != null ? " of " + face.getName() : ""), 30, m -> {
                    m.setShouldBurnInDay(false);
                    m.setAdult();
                    m.setCanPickupItems(false);
                    m.addScoreboardTag(REVENANT_TAG);
                    attr(m, Attribute.MOVEMENT_SPEED, 0.27);
                    attr(m, Attribute.ATTACK_DAMAGE, 6);
                    if (face != null) {
                        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                        SkullMeta sm = (SkullMeta) head.getItemMeta();
                        sm.setOwningPlayer(face);
                        head.setItemMeta(sm);
                        m.getEquipment().setHelmet(head);
                    }
                    m.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    m.getEquipment().setHelmetDropChance(0); m.getEquipment().setChestplateDropChance(0);
                });
                if (face != null) z.setTarget(face);
                world.spawnParticle(Particle.SCULK_SOUL, l.clone().add(0, 0.5, 0), 20, 0.4, 0.6, 0.4, 0.02);
            }
        }
        if (at > 26) end();
    }

    /** A ring of soul fire runs out along the floor: jump it. */
    void ring(List<Player> a) {
        if (at < 18) { if (at % 3 == 0) warnRing(mark, 2, Color.fromRGB(90, 255, 200)); return; }
        ringR += 0.45;
        int n = (int) Math.max(16, ringR * 8);
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, mark.getX() + Math.cos(ang) * ringR, mark.getY() + 0.2, mark.getZ() + Math.sin(ang) * ringR, 1, 0, 0.05, 0, 0);
        }
        for (Player p : a) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - mark.getX(), l.getZ() - mark.getZ());
            if (Math.abs(d - ringR) < 0.7 && l.getY() - mark.getY() < 0.6 && hitThisMove.add(p.getUniqueId())) {
                hit(p, c("moves.death-ring", 10), mark.toVector(), Guard.UNBLOCKABLE);
                p.setFireTicks(60);
            }
        }
        if (ringR > 18) end();
    }

    /** He vanishes and reappears behind his target, then strikes. */
    void blink(List<Player> a) {
        if (at == 8) {
            world.spawnParticle(Particle.LARGE_SMOKE, center(), 30, 0.5, 1, 0.5, 0.02);
            Location behind = mark.clone().add(dirOf(mark.getYaw()).multiply(-2.5));
            behind.setY(ground(behind) + 0.5);
            pos = behind;
            yaw = mark.getYaw();
            world.playSound(pos, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2f, 0.7f);
            world.spawnParticle(Particle.REVERSE_PORTAL, center(), 40, 0.5, 1, 0.5, 0.1);
        }
        if (at == 20) {
            world.playSound(pos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2f, 0.5f);
            Location front = pos.clone().add(dirOf(yaw).multiply(2));
            world.spawnParticle(Particle.SWEEP_ATTACK, front.clone().add(0, 1, 0), 3, 0.6, 0.3, 0.6, 0);
            for (Player p : near(front, 2.6, 3)) hit(p, c("moves.blink-strike", 12), pos.toVector(), Guard.BLOCKABLE);
        }
        if (at > 28) end();
    }

    void darkness(List<Player> a) {
        if (at == 10) for (Player p : a) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0));
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 1f, 0.5f);
        }
        if (at > 20) end();
    }

    /** Beams from everyone in reach: they lose health, he gains it. */
    void drain(List<Player> a) {
        double r = c("drain-radius", 14);
        Location c = center().add(0, 1.1 * k, 0);
        for (Player p : a) {
            if (p.getLocation().distanceSquared(c) > r * r || !p.hasLineOfSight(hitboxes.get(0))) continue;
            Vector d = p.getEyeLocation().toVector().subtract(c.toVector());
            double len = d.length();
            d.normalize();
            if (at % 2 == 0) for (double t = 0; t < len; t += 0.6)
                world.spawnParticle(Particle.DUST, c.clone().add(d.clone().multiply(t)), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(120, 255, 140), 1f));
            if (at % 20 == 10) {
                hit(p, c("moves.drain", 3), c.toVector(), Guard.UNBLOCKABLE);
                hp = Math.min(maxHp, hp + c("drain-heal", 25));
            }
        }
        if (at > 100) end();
    }


    // ---------- Grasp of the Grave (1.5.0)
    static final BlockData BONE = Material.BONE_BLOCK.createBlockData();

    /** A bone display: w x len x w, standing on `at`, tipped `tilt` degrees about the horizontal axis `axis`. */
    BlockDisplay bone(Location at, float w, float len, Vector axis, double tilt) {
        Quaternionf q = new Quaternionf(new AxisAngle4f((float) Math.toRadians(tilt), (float) axis.getX(), 0, (float) axis.getZ()));
        Vector3f off = q.transform(new Vector3f(-w / 2, 0, -w / 2));
        BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(BONE);
            e.setTransformation(new Transformation(off, q, new Vector3f(w, 0.01f, w), new Quaternionf()));
            e.setInterpolationDuration(3);
            e.setPersistent(false);
            e.addScoreboardTag(Wild.FX_TAG);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(10, 10));
        });
        bones.add(d);
        return d;
    }

    /** Grow / tip a bone display (interpolated over `ticks`). */
    static void reshape(BlockDisplay d, float w, float len, Vector axis, double tilt, int ticks) {
        if (!d.isValid()) return;
        Quaternionf q = new Quaternionf(new AxisAngle4f((float) Math.toRadians(tilt), (float) axis.getX(), 0, (float) axis.getZ()));
        Vector3f off = q.transform(new Vector3f(-w / 2, 0, -w / 2));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(off, q, new Vector3f(w, len, w), new Quaternionf()));
    }

    /** Bone hands burst out of the floor under everyone (rings warn first), close on whoever's still there and hold them. */
    void grasp(List<Player> a) {
        if (at < 20) {
            if (at % 3 == 0) for (Location m : marks) {
                warnRing(m, 1.7, Color.fromRGB(150, 110, 200));
                world.spawnParticle(Particle.SCULK_SOUL, m.clone().add(0, 0.2, 0), 3, 0.6, 0.05, 0.6, 0.01);
            }
            if (at == 12) for (Location m : marks) world.playSound(m, Sound.BLOCK_BONE_BLOCK_BREAK, SoundCategory.HOSTILE, 1.5f, 0.5f);
            return;
        }
        if (at == 20) for (Location m : marks) {
            world.spawnParticle(Particle.BLOCK, m.clone().add(0, 0.3, 0), 30, 0.7, 0.2, 0.7, 0, BONE);
            world.spawnParticle(Particle.SOUL, m.clone().add(0, 0.6, 0), 14, 0.5, 0.5, 0.5, 0.03);
            world.playSound(m, Sound.ENTITY_SKELETON_HURT, SoundCategory.HOSTILE, 2f, 0.5f);
            // five fingers in a ring, leaning in over the spot
            List<BlockDisplay> hand = new ArrayList<>();
            List<Vector> axes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                double ang = i * Math.PI * 2 / 5 + random.nextDouble() * 0.3;
                Location base = m.clone().add(Math.cos(ang) * 0.75, 0, Math.sin(ang) * 0.75);
                Vector axis = new Vector(-Math.sin(ang), 0, Math.cos(ang)); // the tangent: tipping about it leans the finger in
                hand.add(bone(base, 0.2f, 1.6f, axis, 18));
                axes.add(axis);
            }
            int[] t = {0};
            fx.add(() -> {
                t[0]++;
                if (t[0] == 1) for (int i = 0; i < hand.size(); i++) reshape(hand.get(i), 0.2f, 1.7f + (i % 2) * 0.3f, axes.get(i), 22, 3);   // burst up
                if (t[0] == 6) for (int i = 0; i < hand.size(); i++) reshape(hand.get(i), 0.2f, 1.7f + (i % 2) * 0.3f, axes.get(i), 42, 4);  // and close
                if (t[0] == 46) for (int i = 0; i < hand.size(); i++) reshape(hand.get(i), 0.2f, 0.01f, axes.get(i), 42, 6);                  // sink back
                if (t[0] < 54) return false;
                for (BlockDisplay d : hand) if (d.isValid()) d.remove();
                return true;
            });
            for (Player p : near(m, 1.7, 3)) {
                hit(p, c("moves.grasp", 8), m.toVector(), Guard.UNBLOCKABLE);
                if (survival(p)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 6));
                    Safe.vel(p, new Vector(0, -0.2, 0));
                    p.sendActionBar(legacy(ChatColor.DARK_PURPLE + "The grave holds you!"));
                }
            }
        }
        if (at > 34) end();
    }

    // ---------- Bone Spears (1.5.0)
    /** Lines of bone spikes burst out of the floor one after another, running out from him at the people he picked. */
    void spears(List<Player> a) {
        if (at < 18) {
            if (at % 2 == 0) for (int i = 0; i < marks.size(); i++) warnLine(marks.get(i), dirs.get(i), 22, Color.fromRGB(230, 220, 190));
            return;
        }
        if (at == 18) {
            world.playSound(pos, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 2f, 0.5f);
            world.spawnParticle(Particle.SOUL, pos.clone().add(0, 0.3, 0), 30, 1, 0.2, 1, 0.08);
        }
        int step = at - 18;
        double d = 1.5 + step * 1.0;
        if (d <= 22) for (int i = 0; i < marks.size(); i++) {
            Vector dir = dirs.get(i);
            Location l = marks.get(i).clone().add(dir.clone().multiply(d));
            l.setY(ground(l));
            Vector axis = new Vector(-dir.getZ(), 0, dir.getX());
            float len = (float) (1.6 + random.nextDouble() * 0.9);
            BlockDisplay sp = bone(l, 0.45f, len, axis, 14 + random.nextGaussian() * 6);
            int[] t = {0};
            double tilt = 14 + random.nextGaussian() * 6;
            fx.add(() -> {
                t[0]++;
                if (t[0] == 1) reshape(sp, 0.45f, len, axis, tilt, 2);
                if (t[0] == 30) reshape(sp, 0.45f, 0.01f, axis, tilt, 8);
                if (t[0] < 40) return false;
                if (sp.isValid()) sp.remove();
                return true;
            });
            world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 8, 0.3, 0.2, 0.3, 0, BONE);
            if (step % 3 == 0) world.playSound(l, Sound.BLOCK_BONE_BLOCK_PLACE, SoundCategory.HOSTILE, 1.6f, 0.6f);
            for (Player p : near(l, 1.2, 2.5)) if (hitThisMove.add(p.getUniqueId())) {
                hit(p, c("moves.bone-spear", 10), l.toVector(), Guard.HEAVY);
                Safe.vel(p, dir.clone().multiply(0.4).setY(0.8));
            }
        }
        if (d > 24) end();
    }

    Location staffDown;

    @Override
    void deathAnim(int t) {
        // he shudders and rises, his arms fall limp and the staff drops from his hand; he crumples forward as the soul
        // leaves (head last), and dwindles to nothing
        Pose p = new Pose();
        p.lift = Math.min(1.5, t * 0.03);
        p.lean = Math.min(70, Math.max(0, t - 30) * 2.5);
        p.roll = Math.sin(t * 1.3) * 4;
        p.spin = Math.sin(t * 0.8) * (t < 30 ? 6 : 1);
        p.headPitch = t < 30 ? -25 + Math.sin(t * 0.9) * 8 : Math.min(55, (t - 30) * 3);
        p.lSwing = t < 30 ? 120 : Math.sin(t * 0.3) * 6; p.lSpread = t < 30 ? 50 : 4;
        p.rSwing = t < 30 ? 100 : Math.sin(t * 0.3 + 1) * 6; p.rSpread = t < 30 ? 40 : 4;
        p.cape = 4 + Math.sin(t * 0.2) * 3;
        cur.ease(p, 0.2, 0.15);
        double shrink = t < 50 ? 1 : Math.max(0.05, 1 - (t - 50) / 30.0);
        draw(cur, k * shrink);
        if (t == 24 && handR != null) { staffDown = handR.clone(); world.playSound(staffDown, Sound.BLOCK_WOOD_FALL, SoundCategory.HOSTILE, 2f, 0.5f); }
        if (staffDown != null) { // it topples over from its foot
            double fall = Math.min(84, Math.pow(Math.max(0, t - 24), 2) * 0.12);
            Location foot = staffDown.clone().add(0, -STAFF_LOW * k, 0);
            foot.setY(Math.max(ground(foot), foot.getY() - (t - 24) * 0.12));
            Quaternionf q = frame(yaw + 40, 0, 0).mul(rotZ(fall));
            staff.k = k;
            staff.pose(joint(foot, q, 0, STAFF_LOW, 0, k), yaw + 40, 0, 0, rotZ(fall));
            if (fall >= 84 && t % 40 == 0) world.playSound(foot, Sound.BLOCK_WOOD_HIT, SoundCategory.HOSTILE, 1f, 0.6f);
        }
        if (t % 2 == 0) world.spawnParticle(Particle.SOUL, center(), 10, 0.5, 1, 0.5, 0.05);
        if (t % 15 == 0) world.playSound(pos, Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 1.5f, 1.4f);
    }

    @Override
    Collection<UUID> extra() { List<UUID> u = new ArrayList<>(); for (EnderCrystal cr : crystals) u.add(cr.getUniqueId()); return u; }

    @Override
    void removeEverything() {
        quietly(() -> { for (EnderCrystal cr : crystals) if (cr.isValid()) cr.remove(); crystals.clear(); });
        quietly(() -> { for (BlockDisplay b : bones) if (b.isValid()) b.remove(); bones.clear(); });
        super.removeEverything();
    }

    @Override void drops(Player p, Location at) { maybeDrop(p, at, "lich_staff", c("rewards.staff-chance", 0.25)); }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(BOLTS, "Soul Bolts", Material.WITHER_SKELETON_SKULL));
        m.add(new MoveSlot(RAISE, "Raise the Fallen", Material.ZOMBIE_HEAD));
        m.add(new MoveSlot(RING, "Death Ring", Material.SOUL_CAMPFIRE));
        m.add(new MoveSlot(BLINK, "Blink Strike", Material.ENDER_PEARL));
        m.add(new MoveSlot(GRASP, "Grasp of the Grave", Material.BONE));
        if (phase >= 2) {
            m.add(new MoveSlot(DARKNESS, "Darkness", Material.SCULK_SHRIEKER));
            m.add(new MoveSlot(SPEARS, "Bone Spears", Material.BONE_BLOCK));
        }
        if (phase == 3) m.add(new MoveSlot(DRAIN, "Life Drain", Material.GHAST_TEAR));
        return m;
    }
}
