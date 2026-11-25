package net.faultlinesmp.items;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * THE QUEEN SPIDER'S BODY (Items 1.3.44; 1.3.46: real legs). Each foot is PLANTED in the world and the leg reaches it by
 * two-bone IK (femur + tibia, the tarsus held near upright); when the body has moved / turned away from a foot's rest spot it
 * STEPS (an arc, alternating tetrapod groups: one group in the air at a time), so she walks, turns on the spot and stands on
 * uneven ground for real. The body sits on its feet (height, pitch and roll from where they stand), the head tracks her
 * target, the abdomen lags behind turns, idle legs fidget and tap, hits make her flinch. Sleep / death / rear blend over it.
 * (1.3.44: 31 ItemDisplays (models from tools/queen_spider_models.py: thorax, head, eyes,
 * 2 fangs, 2 palps, abdomen, 8 x femur / tibia / tarsus) posed every tick from a matrix chain (setTransformationMatrix),
 * all standing at the hitbox spider's feet. Angles are eased toward each animation's targets: idle breathing, an 8-leg
 * alternating-tetrapod walk driven by how far she really moved, sleep (curled up, slow breaths, dim eyes), wake (rears up),
 * bite (lunge + fangs), rear (front legs high: sweeps, stomps), spit (abdomen up), slam, brood (abdomen pulsing), death
 * (rolls over, legs curl). The joints and rest angles match the preview in queen_spider_models.py.
 */
final class QueenRig {
    static final String TAG = "faultline_queen_part";
    static final String[] PARTS = {"thorax", "head", "eyes", "abdomen", "fang", "fang", "palp", "palp"};
    static final double[][] HIPS = {{5, 0.55}, {2, 1.25}, {-1, 1.9}, {-4, 2.5}}; // z on the thorax, yaw out from the front
    static final double FEMUR = 18, TIBIA = 20;
    // stand / sleep rest angles (radians) and body heights (pixels)
    static final double ELEV = -0.95, KNEE = 1.6, FOOT = 0.45, BODY = 11.2;
    static final double S_ELEV = -0.5, S_KNEE = 2.5, S_FOOT = 1.4, S_BODY = 5;

    static final double TARSUS = 15, FOOT_ABS = 1.3; // tarsus length (pixels) and its angle from level (radians, ~75 degrees down)

    final FaultlineItems plugin;
    final LivingEntity body;
    final double k;                 // blocks per pixel
    final List<ItemDisplay> parts = new ArrayList<>();
    Location at;                    // where she stands (the hitbox's feet)
    float yaw;
    String anim = "idle";
    int animT, animLen;
    boolean dying, gone;
    int deathT;
    long t;
    double walk;                    // 0..1 how much she's walking (eased)
    Location last;
    double vx, vz;                  // her velocity (blocks / tick, smoothed)
    // eased pose values
    double fold, rear, lunge, abd, fangs, pitch, roll, lift, pulse, slam, flinch, headYaw, headPitch, abdYaw, turnRate;
    double gPitch, gRoll, gY;      // from where the feet stand
    private float eyeBright = -1;
    private final java.util.Random rnd = new java.util.Random();
    private boolean planted;
    final String prefix;
    final int glow;

    /** One leg: where its foot is in the world, and the step it's taking. */
    private static final class Leg {
        int idx, sd, group;
        double fx, fy, fz;          // planted foot (world)
        double ax, ay, az, bx, by, bz; // a step from a to b
        int stepT = -1, stepLen;
        double tap;                 // a fidget: 0..1 lift
        int tapT = -1;
    }
    private final Leg[] legs = new Leg[8];

    QueenRig(FaultlineItems plugin, LivingEntity body, double scale) { this(plugin, body, scale, "queen/", -1); }

    /** 1.3.47: any spider on this rig: `prefix` = its models (queen/, nestspider/venom/...), `glow` = a fixed brightness or -1. */
    QueenRig(FaultlineItems plugin, LivingEntity body, double scale, String prefix, int glow) {
        this.plugin = plugin;
        this.prefix = prefix;
        this.glow = glow;
        this.body = body;
        this.k = 0.026 * scale;
        this.at = body.getLocation();
        this.yaw = at.getYaw();
        World w = at.getWorld();
        int n = PARTS.length + 24;
        for (int i = 0; i < n; i++) {
            String model = i < PARTS.length ? PARTS[i] : new String[]{"femur", "tibia", "tarsus"}[(i - PARTS.length) % 3];
            Location l = at.clone(); l.setYaw(0); l.setPitch(0);
            ItemDisplay d = w.spawn(l, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.addScoreboardTag(TAG);
                e.addScoreboardTag("faultline_no_bedrock_fx");
                e.setItemStack(item(prefix + model));
                if (glow >= 0) e.setBrightness(new Display.Brightness(glow, glow));
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setViewRange(2f);
                e.setDisplayWidth((float) (k * 100)); e.setDisplayHeight((float) (k * 60)); // not culled when the origin leaves the screen
            });
            parts.add(d);
        }
        int li = 0;
        for (int leg = 0; leg < 4; leg++) for (int sd : new int[]{1, -1}) {
            Leg g = new Leg();
            g.idx = leg; g.sd = sd; g.group = (leg + (sd > 0 ? 0 : 1)) % 2;
            legs[li++] = g;
        }
        last = at.clone();
    }

    static ItemStack item(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", model));
        it.setItemMeta(m);
        return it;
    }

    void play(String a, int ticks) { anim = a; animT = 0; animLen = ticks; }
    boolean busy() { return animT < animLen && !anim.equals("idle") && !anim.equals("sleep"); }

    /** A hit: she flinches (bigger hits, bigger flinch). */
    void hurt(double amount) { flinch = Math.min(1, flinch + 0.25 + amount * 0.03); }

    void remove() {
        gone = true;
        for (ItemDisplay d : parts) if (d.isValid()) d.remove();
        parts.clear();
    }

    /** She died: the body keeps going (rolls over, curls up) for 3 seconds, then it's gone. */
    void die() { dying = true; deathT = 0; play("death", 60); }

    private static double ease(double cur, double want, double rate) { return cur + (want - cur) * rate; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double smooth(double f) { f = clamp(f, 0, 1); return f * f * (3 - 2 * f); }

    void tick() {
        if (gone) return;
        t++;
        double dYaw = 0;
        if (!dying) {
            if (!body.isValid()) { remove(); return; }
            Location b = body.getLocation();
            boolean same = last.getWorld() == b.getWorld();
            double mx = same ? b.getX() - last.getX() : 0, mz = same ? b.getZ() - last.getZ() : 0;
            if (Math.hypot(mx, mz) > 3) { mx = 0; mz = 0; planted = false; } // a teleport: feet land again
            vx = ease(vx, mx, 0.4); vz = ease(vz, mz, 0.4);
            last = b.clone();
            at = b;
            float by;
            try { by = body.getBodyYaw(); } catch (Throwable e) { by = b.getYaw(); }
            float dy = ((by - yaw) % 360 + 540) % 360 - 180;
            float turn = (float) clamp(dy * 0.3, -14, 14);
            yaw += turn;
            dYaw = turn;
            walk = ease(walk, Math.min(1, Math.hypot(vx, vz) / (k * 1.2)), 0.25);
        } else {
            deathT++;
            if (deathT > 70) { remove(); return; }
        }
        turnRate = ease(turnRate, dYaw, 0.2);
        if (animT < animLen) animT++;
        else if (!anim.equals("sleep")) anim = "idle";
        double f = animLen <= 0 ? 1 : animT / (double) animLen; // 0..1 through the animation
        // targets: each move is wind-up -> strike -> recover, eased
        double wFold = 0, wRear = 0, wLunge = 0, wAbd = 0, wFang = 0.12 + 0.1 * Math.sin(t * 0.13) * Math.sin(t * 0.031), wPitch = 0, wRoll = 0, wPulse = 0, wSlam = 0;
        switch (anim) {
            case "sleep" -> { wFold = 1; wFang = 0; }
            case "wake" -> { // the legs unfold, she shakes herself, rears up screaming, comes down
                double up = smooth(f / 0.35) * (1 - smooth((f - 0.75) / 0.25));
                wRear = up; wFang = f > 0.3 ? 1 : 0.3; wPitch = -0.4 * up; wRoll = f < 0.3 ? Math.sin(f * 60) * 0.08 : 0;
            }
            case "bite" -> { // pull back, snap forward, hold, back
                wLunge = f < 0.4 ? -0.5 * smooth(f / 0.4) : f < 0.6 ? 1.1 : 1.1 * (1 - smooth((f - 0.6) / 0.4));
                wFang = f < 0.45 ? 1 : f < 0.6 ? 0 : 0.3; wPitch = f < 0.4 ? -0.2 : f < 0.65 ? 0.18 : 0;
            }
            case "rear" -> {
                double up = smooth(f / 0.3) * (1 - smooth((f - 0.7) / 0.2));
                wRear = up; wPitch = -0.5 * up + (f > 0.7 && f < 0.85 ? 0.25 : 0); wFang = 0.9;
            }
            case "slam" -> {
                double up = smooth(f / 0.4) * (f < 0.45 ? 1 : 0);
                wRear = up; wPitch = f < 0.45 ? -0.55 * up : f < 0.7 ? 0.32 : 0; wSlam = f > 0.45 && f < 0.7 ? 1 : 0;
            }
            case "spit" -> { // abdomen swings up over her back, a jerk forward as it fires
                wAbd = f < 0.55 ? smooth(f / 0.4) : f < 0.7 ? 1.15 : 1 - smooth((f - 0.7) / 0.3);
                wPitch = f < 0.55 ? -0.15 : 0.08; wFang = 0.7; wLunge = f > 0.55 && f < 0.7 ? 0.35 : 0;
            }
            case "brood" -> { wPulse = 1; wAbd = 0.45; wPitch = 0.08; }
            case "crouch" -> { wPitch = 0.18; wFold = 0.4; wFang = 0.6; }
            case "death" -> { wRoll = Math.min(Math.PI, deathT * 0.07 * (1 + deathT * 0.01)); wFold = 0.9; }
            default -> { }
        }
        double r = anim.equals("sleep") ? 0.05 : 0.22;
        fold = ease(fold, wFold, r); rear = ease(rear, wRear, 0.25); lunge = ease(lunge, wLunge, 0.35); abd = ease(abd, wAbd, 0.22);
        fangs = ease(fangs, wFang, 0.4); pitch = ease(pitch, wPitch, 0.22); roll = ease(roll, wRoll, 0.12); pulse = ease(pulse, wPulse, 0.2);
        slam = ease(slam, wSlam, 0.5);
        flinch *= 0.82;
        lift = walk * (1 - fold);
        // the head looks at her prey, the abdomen trails behind her turns
        double hy = 0, hp = 0;
        if (!dying && fold < 0.5 && body instanceof org.bukkit.entity.Mob mob && mob.getTarget() != null && mob.getTarget().getWorld() == at.getWorld()) {
            Location tg = mob.getTarget().getEyeLocation();
            double ang = Math.toDegrees(Math.atan2(-(tg.getX() - at.getX()), tg.getZ() - at.getZ()));
            hy = clamp(Math.toRadians(((ang - yaw) % 360 + 540) % 360 - 180), -0.55, 0.55);
            double dist = Math.hypot(tg.getX() - at.getX(), tg.getZ() - at.getZ());
            hp = clamp(-Math.atan2(tg.getY() - (at.getY() + BODY * k), Math.max(1, dist)), -0.45, 0.45);
        } else if (fold < 0.5) hy = Math.sin(t * 0.021) * 0.25 * Math.sin(t * 0.007); // looking around
        headYaw = ease(headYaw, hy, 0.15); headPitch = ease(headPitch, hp, 0.12);
        abdYaw = ease(abdYaw, clamp(-Math.toRadians(turnRate) * 2.2, -0.45, 0.45) + Math.sin(t * 0.05) * 0.04, 0.12);
        feet();
        draw();
    }

    // ============================================================================================ feet
    /** The thorax frame (blocks, relative to `at`) for a body height / pitch / roll. */
    private Matrix4f thoraxFrame(double bodyY, double p, double rl) {
        return new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale((float) k)
                .translate(0, (float) bodyY, (float) (lunge * 5)).rotateX((float) p).rotateZ((float) rl);
    }

    /** A leg's resting foot spot (world), ahead by `lead` ticks of her motion, on the ground. */
    private double[] home(Leg g, Matrix4f level, double lead) {
        double spread = QueenRig.HIPS[g.idx][1];
        double a1 = ELEV, a2 = ELEV + KNEE, a3 = a2 + FOOT;
        double along = FEMUR * Math.cos(a1) + TIBIA * Math.cos(a2) + TARSUS * Math.cos(a3) - 2;
        Vector3f p = new Vector3f((float) (6.5 * g.sd + Math.sin(spread) * along * g.sd), 0, (float) (HIPS[g.idx][0] + Math.cos(spread) * along));
        level.transformPosition(p);
        double x = at.getX() + p.x + vx * lead, z = at.getZ() + p.z + vz * lead;
        return new double[]{x, ground(x, at.getY(), z), z};
    }

    /** The top of the ground under (x, z), searched from 2 blocks above `y` to 3 below; none = `y`. */
    private double ground(double x, double y, double z) {
        World w = at.getWorld();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), top = (int) Math.floor(y) + 2;
        for (int yy = top; yy >= top - 5; yy--) {
            org.bukkit.block.Block b = w.getBlockAt(bx, yy, bz);
            Material m = b.getType();
            if (m.isSolid() && m != Material.COBWEB) {
                try { return b.getBoundingBox().getMaxY(); } catch (RuntimeException e) { return yy + 1; }
            }
        }
        return y;
    }

    private void feet() {
        Matrix4f level = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale((float) k);
        double speed = Math.hypot(vx, vz);
        int stepLen = (int) clamp(9 - speed / k * 0.5, 4, 9);
        if (!planted) {
            for (Leg g : legs) { double[] h = home(g, level, 0); g.fx = h[0]; g.fy = h[1]; g.fz = h[2]; g.stepT = -1; }
            planted = true;
        }
        boolean still = dying || fold > 0.5;
        int[] busy = new int[2];
        for (Leg g : legs) if (g.stepT >= 0) busy[g.group]++;
        double thresh = 9 * k;
        Leg pick = null; double far = 0;
        for (Leg g : legs) {
            if (g.stepT >= 0) {
                g.stepT++;
                double s = smooth(g.stepT / (double) g.stepLen);
                double arc = Math.sin(Math.PI * Math.min(1, g.stepT / (double) g.stepLen)) * (6 + 6 * Math.min(1, speed / (k * 2))) * k;
                g.fx = g.ax + (g.bx - g.ax) * s; g.fz = g.az + (g.bz - g.az) * s;
                g.fy = g.ay + (g.by - g.ay) * s + arc;
                if (g.stepT >= g.stepLen) {
                    g.stepT = -1; g.fx = g.bx; g.fy = g.by; g.fz = g.bz;
                    if (speed > k * 0.3 && rnd.nextInt(3) == 0) at.getWorld().playSound(new Location(at.getWorld(), g.fx, g.fy, g.fz),
                            org.bukkit.Sound.ENTITY_SPIDER_STEP, org.bukkit.SoundCategory.HOSTILE, (float) Math.min(0.35, k * 3), (float) (0.45 + 0.08 / k * 0.0 + rnd.nextFloat() * 0.2 + (k < 0.05 ? 0.5 : 0)));
                }
                continue;
            }
            if (still) continue;
            double[] h = home(g, level, 0);
            double d = Math.hypot(g.fx - h[0], g.fz - h[2]) + Math.abs(g.fy - h[1]) * 0.5;
            if (d > thresh * 4) { g.fx = h[0]; g.fy = h[1]; g.fz = h[2]; continue; } // far behind (knocked away): it lands there
            if (d > thresh && busy[1 - g.group] == 0 && d > far) { far = d; pick = g; }
        }
        if (pick != null) { // that leg's whole group steps (a tetrapod gait)
            for (Leg g : legs) if (g.group == pick.group && g.stepT < 0) {
                double[] h = home(g, level, stepLen * 0.9);
                double[] h0 = home(g, level, 0);
                if (Math.hypot(g.fx - h0[0], g.fz - h0[2]) < thresh * 0.35) continue; // already near home
                g.ax = g.fx; g.ay = g.fy; g.az = g.fz; g.bx = h[0]; g.by = h[1]; g.bz = h[2];
                g.stepLen = stepLen + rnd.nextInt(2); g.stepT = 0;
            }
        }
        // fidgets: an idle leg taps the ground now and then
        for (Leg g : legs) {
            if (g.tapT >= 0) { g.tapT++; g.tap = Math.max(0, Math.sin(g.tapT / 14.0 * Math.PI * 2)) * 0.5; if (g.tapT >= 14) { g.tapT = -1; g.tap = 0; } }
        }
        if (!still && walk < 0.1 && "idle".equals(anim) && rnd.nextInt(90) == 0) {
            Leg g = legs[rnd.nextInt(4)]; // a front pair leg
            if (g.stepT < 0 && g.tapT < 0) g.tapT = 0;
        }
        // the body sits on its feet: height, pitch (front vs back), roll (left vs right)
        double front = 0, back = 0, left = 0, right = 0, sum = 0;
        for (Leg g : legs) {
            double y = (g.stepT >= 0 ? Math.min(g.ay, g.by) : g.fy) - at.getY();
            sum += y;
            if (g.idx <= 1) front += y; else back += y;
            if (g.sd > 0) right += y; else left += y;
        }
        double up = sum / 8 / k;
        if (still) up = 0;
        gY = ease(gY, clamp(up, -20, 20), 0.2);
        gPitch = ease(gPitch, still ? 0 : clamp(-Math.atan2((front - back) / 4, 1.0) * 0.8, -0.4, 0.4), 0.15);
        gRoll = ease(gRoll, still ? 0 : clamp(Math.atan2((right - left) / 4, 1.0) * 0.8, -0.35, 0.35), 0.15);
    }

    /** Two-bone IK for a leg in its own plane: angles {yaw, elev, knee, foot}, or null when it can't. */
    private double[] solve(Leg g, Matrix4f thorax) {
        Matrix4f inv = new Matrix4f(thorax).invert();
        Vector3f p = new Vector3f((float) (g.fx - at.getX()), (float) (g.fy - at.getY()), (float) (g.fz - at.getZ()));
        inv.transformPosition(p);
        double rx = p.x - 6.5 * g.sd, ry = p.y - 1, rz = p.z - HIPS[g.idx][0];
        double yawL = Math.atan2(rx, rz);
        double along = Math.hypot(rx, rz), down = -ry;
        if (!Double.isFinite(yawL + along + down)) return null;
        double ea = along - TARSUS * Math.cos(FOOT_ABS), ed = down - TARSUS * Math.sin(FOOT_ABS);
        double d = clamp(Math.hypot(ea, ed), Math.abs(FEMUR - TIBIA) + 0.5, FEMUR + TIBIA - 0.3);
        double phi = Math.atan2(ed, ea);
        double alpha = Math.acos(clamp((FEMUR * FEMUR + d * d - TIBIA * TIBIA) / (2 * FEMUR * d), -1, 1));
        double a1 = phi - alpha;
        double kx = FEMUR * Math.cos(a1), ky = FEMUR * Math.sin(a1);
        double a2 = Math.atan2(ed - ky, ea - kx);
        return new double[]{yawL, a1, a2 - a1, FOOT_ABS - a2};
    }

    /** Every part's matrix: thorax -> head/eyes/fangs/palps/abdomen; thorax -> 8 x femur -> tibia -> tarsus. */
    private void draw() {
        double breath = Math.sin(t * (fold > 0.5 ? 0.04 : 0.09)) * (fold > 0.5 ? 0.6 : 0.3);
        int stepping = 0;
        for (Leg g : legs) if (g.stepT >= 0) stepping++;
        double bob = -stepping * 0.25 * lift;
        double bodyY = BODY + gY + (S_BODY - BODY) * fold + bob + breath - slam * 3 + rear * 2.5 - flinch * 2;
        double p = pitch + gPitch * (1 - fold) - flinch * 0.25, rl = roll + gRoll * (1 - fold);
        Matrix4f thorax = thoraxFrame(bodyY, p, rl);
        int i = 0;
        set(i++, thorax);
        Matrix4f head = new Matrix4f(thorax).translate(0, 1, 7).rotateY((float) headYaw)
                .rotateX((float) (-pitch * 0.4 + headPitch + Math.sin(t * 0.05) * 0.04 - flinch * 0.3));
        set(i++, head);
        set(i++, head); // eyes
        Matrix4f abdomen = new Matrix4f(thorax).translate(0, 3, -6).rotateY((float) abdYaw)
                .rotateX((float) (-0.25 - abd * 0.7 + Math.sin(t * 0.06) * 0.03 + flinch * 0.2)).translate(0, 2, -12);
        float s = (float) (1 + 0.025 * Math.sin(t * (fold > 0.5 ? 0.04 : 0.09)) + pulse * 0.08 * Math.sin(t * 0.8));
        abdomen.scale(s);
        set(i++, abdomen);
        for (int sd : new int[]{1, -1}) { // fangs: idle chewing, wide on a bite
            double chew = Math.max(0, Math.sin(t * 0.35 + sd)) * 0.08 * (1 - fold);
            set(i++, new Matrix4f(head).translate(2.2f * sd, -2, 5).rotateY((float) ((0.18 + fangs * 0.55 + chew) * sd)).rotateX((float) (0.25 + fangs * 0.45)));
        }
        for (int sd : new int[]{1, -1}) { // palps: feel the ground, groom, raised in a rear
            double wig = Math.sin(t * 0.2 + sd * 1.3) * 0.15 + Math.sin(t * 0.47 + sd) * 0.06 + walk * Math.sin(t * 0.4 + sd) * 0.2;
            set(i++, new Matrix4f(head).translate(4f * sd, -2, 3).rotateY((float) ((0.35 + fangs * 0.15) * sd)).rotateX((float) (0.5 + wig - rear * 0.7)));
        }
        for (Leg g : legs) {
            int leg = g.idx, sd = g.sd;
            double[] ik = dying || fold > 0.98 ? null : solve(g, thorax);
            double rY = HIPS[leg][1] * sd, rE = ELEV, rK = KNEE, rF = FOOT;
            double yawL = ik == null ? rY : ik[0], elev = ik == null ? rE : ik[1], knee = ik == null ? rK : ik[2], foot = ik == null ? rF : ik[3];
            // sleep: tucked in
            double sY = HIPS[leg][1] * 0.8 * sd;
            yawL += (sY - yawL) * fold; elev += (S_ELEV - elev) * fold; knee += (S_KNEE - knee) * fold; foot += (S_FOOT - foot) * fold;
            // rearing: the front legs up and spread
            double raise = leg == 0 ? rear : leg == 1 ? rear * 0.55 : 0;
            if (raise > 0) {
                double ry = (HIPS[leg][1] + 0.25) * sd;
                yawL += (ry - yawL) * raise; elev += (-2.0 - elev) * raise; knee += (1.0 - knee) * raise; foot += (0.25 - foot) * raise;
                elev += Math.sin(t * 0.5 + leg + sd) * 0.06 * raise; // flailing a little
            }
            if (g.tap > 0) { elev -= g.tap * 0.6; knee += g.tap * 0.3; }
            if (slam > 0 && leg <= 1) knee += slam * 0.3;
            double twitch = fold > 0.5 ? Math.sin(t * 0.03 + leg * 1.7 + sd) * 0.03 : 0;
            elev += twitch;
            if (dying) { knee = Math.min(2.9, knee + deathT * 0.03); foot = Math.min(1.8, foot + deathT * 0.02); elev = Math.max(-1.2, elev - Math.sin(deathT * 0.6 + leg) * 0.05 * Math.max(0, 1 - deathT / 50.0)); }
            Matrix4f femur = new Matrix4f(thorax).translate(6.5f * sd, 1, (float) HIPS[leg][0]).rotateY((float) yawL).rotateX((float) elev);
            set(i++, femur);
            Matrix4f tibia = new Matrix4f(femur).translate(0, 0, (float) FEMUR).rotateX((float) knee);
            set(i++, tibia);
            set(i++, new Matrix4f(tibia).translate(0, 0, (float) TIBIA).rotateX((float) foot));
        }
        // the eyes glow when awake
        float eb = glow >= 0 ? Math.max(glow, fold > 0.6 ? 4 : 15) : fold > 0.6 ? 4 : 15;
        if (eb != eyeBright && parts.size() > 2) { eyeBright = eb; parts.get(2).setBrightness(new Display.Brightness((int) eb, (int) eb)); }
        Location to = at.clone(); to.setYaw(0); to.setPitch(0);
        boolean near = false;
        for (Player pl : at.getWorld().getPlayers()) if (pl.getLocation().distanceSquared(at) < 96 * 96) { near = true; break; }
        if (near ? t % 2 != 0 : t % 10 != 0) return; // a pose every 2 ticks close up (interpolated), every half second otherwise
        for (int n = 0; n < parts.size(); n++) {
            ItemDisplay d = parts.get(n);
            Matrix4f m = pending.get(n);
            if (!d.isValid() || m == null) continue;
            if (d.getLocation().distanceSquared(to) > 0.0004) d.teleport(to);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(near ? 2 : 10);
            d.setTransformationMatrix(m);
        }
    }

    private final java.util.Map<Integer, Matrix4f> pending = new java.util.HashMap<>();

    /** The display's matrix: the part's pixel frame x16 (a model's 16 units = 1), turned half way round (item displays draw it turned). */
    private void set(int i, Matrix4f partFrame) {
        pending.put(i, new Matrix4f(partFrame).scale(16).rotateY((float) Math.PI));
    }
}
