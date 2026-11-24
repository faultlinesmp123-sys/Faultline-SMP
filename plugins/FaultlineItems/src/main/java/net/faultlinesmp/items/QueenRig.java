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

import java.util.ArrayList;
import java.util.List;

/**
 * THE QUEEN SPIDER'S BODY (Items 1.3.44). 31 ItemDisplays (models from tools/queen_spider_models.py: thorax, head, eyes,
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

    final FaultlineItems plugin;
    final LivingEntity body;
    final double k;                 // blocks per pixel
    final List<ItemDisplay> parts = new ArrayList<>();
    Location at;                    // where she stands (the hitbox's feet), eased yaw in `yaw`
    float yaw;
    String anim = "idle";
    int animT, animLen;
    boolean dying, gone;
    int deathT;
    long t;
    double gait, walk;             // walk = 0..1 how much she's walking (eased)
    Location last;
    // eased pose values
    double fold, rear, lunge, abd, fangs, pitch, roll, lift, pulse, slam;
    private float eyeBright = -1;

    QueenRig(FaultlineItems plugin, LivingEntity body, double scale) {
        this.plugin = plugin;
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
                e.setItemStack(item(model));
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setViewRange(2f);
                e.setDisplayWidth((float) (k * 100)); e.setDisplayHeight((float) (k * 60)); // not culled when the origin leaves the screen
            });
            parts.add(d);
        }
        last = at.clone();
    }

    static ItemStack item(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "queen/" + model));
        it.setItemMeta(m);
        return it;
    }

    void play(String a, int ticks) { anim = a; animT = 0; animLen = ticks; }
    boolean busy() { return animT < animLen && !anim.equals("idle") && !anim.equals("sleep"); }

    void remove() {
        gone = true;
        for (ItemDisplay d : parts) if (d.isValid()) d.remove();
        parts.clear();
    }

    /** She died: the body keeps going (rolls over, curls up) for 3 seconds, then it's gone. */
    void die() { dying = true; deathT = 0; play("death", 60); }

    private static double ease(double cur, double want, double rate) { return cur + (want - cur) * rate; }

    void tick() {
        if (gone) return;
        t++;
        if (!dying) {
            if (!body.isValid()) { remove(); return; }
            Location b = body.getLocation();
            double moved = last.getWorld() == b.getWorld() ? Math.hypot(b.getX() - last.getX(), b.getZ() - last.getZ()) : 0;
            last = b.clone();
            at = b;
            float by;
            try { by = body.getBodyYaw(); } catch (Throwable e) { by = b.getYaw(); }
            float dy = ((by - yaw) % 360 + 540) % 360 - 180;
            yaw += dy * 0.35f;
            walk = ease(walk, Math.min(1, moved / (k * 1.2)), 0.25);
            gait += moved / k / 26 * Math.PI * 2 * 0.5 + Math.abs(dy) * 0.004;
        } else {
            deathT++;
            if (deathT > 70) { remove(); return; }
        }
        if (animT < animLen) animT++;
        else if (!anim.equals("sleep")) anim = "idle";
        double f = animLen <= 0 ? 1 : animT / (double) animLen; // 0..1 through the animation
        // targets
        double wFold = 0, wRear = 0, wLunge = 0, wAbd = 0, wFang = 0.1 + 0.08 * Math.sin(t * 0.15), wPitch = 0, wRoll = 0, wPulse = 0, wSlam = 0;
        switch (anim) {
            case "sleep" -> { wFold = 1; wFang = 0; }
            case "wake" -> { wRear = f < 0.7 ? 1 : 0.3; wFang = 1; wPitch = -0.35 * (f < 0.7 ? 1 : 0.3); }
            case "bite" -> { wLunge = f < 0.45 ? -0.4 : f < 0.7 ? 1 : 0.2; wFang = f < 0.5 ? 1 : 0; wPitch = f < 0.45 ? -0.15 : 0.15; }
            case "rear" -> { wRear = f < 0.75 ? 1 : 0; wPitch = f < 0.75 ? -0.45 : 0.25; wFang = 0.8; }
            case "slam" -> { wRear = f < 0.45 ? 1 : 0; wPitch = f < 0.45 ? -0.5 : 0.3; wSlam = f > 0.45 && f < 0.7 ? 1 : 0; }
            case "spit" -> { wAbd = f < 0.6 ? 0.9 : 0; wPitch = -0.12; wFang = 0.7; }
            case "brood" -> { wPulse = 1; wAbd = 0.4; }
            case "crouch" -> { wPitch = 0.15; wFold = 0.35; wFang = 0.6; }
            case "death" -> { wRoll = Math.min(Math.PI, deathT * 0.09); wFold = 0.9; }
            default -> { }
        }
        double r = anim.equals("sleep") ? 0.05 : 0.22;
        fold = ease(fold, wFold, r); rear = ease(rear, wRear, 0.25); lunge = ease(lunge, wLunge, 0.3); abd = ease(abd, wAbd, 0.2);
        fangs = ease(fangs, wFang, 0.35); pitch = ease(pitch, wPitch, 0.22); roll = ease(roll, wRoll, 0.12); pulse = ease(pulse, wPulse, 0.2);
        slam = ease(slam, wSlam, 0.5);
        lift = walk * (1 - fold);
        draw();
    }

    /** Every part's matrix: thorax -> head/eyes/fangs/palps/abdomen; thorax -> 8 x femur -> tibia -> tarsus. */
    private void draw() {
        double breath = Math.sin(t * (fold > 0.5 ? 0.04 : 0.09)) * (fold > 0.5 ? 0.6 : 0.3);
        double bob = Math.sin(gait * 2) * 0.6 * lift;
        double bodyY = BODY + (S_BODY - BODY) * fold + bob + breath - slam * 3 + rear * 2;
        Matrix4f root = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale((float) k);
        Matrix4f thorax = new Matrix4f(root).translate(0, (float) bodyY, (float) (lunge * 5))
                .rotateX((float) (pitch)).rotateZ((float) roll);
        int i = 0;
        set(i++, thorax);
        Matrix4f head = new Matrix4f(thorax).translate(0, 1, 7).rotateX((float) (-pitch * 0.4 + Math.sin(t * 0.05) * 0.04));
        set(i++, head);
        set(i++, head); // eyes
        Matrix4f abdomen = new Matrix4f(thorax).translate(0, 3, -6).rotateX((float) (-0.25 - abd * 0.7 + Math.sin(t * 0.06) * 0.03))
                .translate(0, 2, -12);
        float s = (float) (1 + 0.025 * Math.sin(t * (fold > 0.5 ? 0.04 : 0.09)) + pulse * 0.08 * Math.sin(t * 0.8));
        abdomen.scale(s);
        set(i++, abdomen);
        for (int sd : new int[]{1, -1}) {
            set(i++, new Matrix4f(thorax).translate(2.2f * sd, -1, 12).rotateY((float) ((0.18 + fangs * 0.5) * sd)).rotateX((float) (0.25 + fangs * 0.45)));
        }
        for (int sd : new int[]{1, -1}) {
            double wig = Math.sin(t * 0.2 + sd) * 0.15 + Math.sin(gait + sd) * 0.25 * lift;
            set(i++, new Matrix4f(thorax).translate(4f * sd, -1, 10).rotateY((float) (0.35 * sd)).rotateX((float) (0.5 + wig - rear * 0.6)));
        }
        for (int leg = 0; leg < 4; leg++) {
            for (int sd : new int[]{1, -1}) {
                int group = (leg + (sd > 0 ? 0 : 1)) % 2;
                double ph = gait + group * Math.PI;
                double swing = Math.sin(ph) * 0.32 * lift;
                double up = Math.max(0, Math.cos(ph)) * 0.45 * lift;
                double raise = leg == 0 ? rear : leg == 1 ? rear * 0.55 : 0;
                double twitch = fold > 0.5 ? Math.sin(t * 0.03 + leg * 1.7 + sd) * 0.03 : Math.sin(t * 0.07 + leg * 1.3 + sd * 2) * 0.03;
                double elev = ELEV + (S_ELEV - ELEV) * fold - up - raise * 1.25 + twitch;
                double knee = KNEE + (S_KNEE - KNEE) * fold + up * 0.5 - raise * 0.9 + slam * 0.3;
                double foot = FOOT + (S_FOOT - FOOT) * fold + raise * 0.4;
                if (dying) { knee = Math.min(2.9, knee + deathT * 0.03); foot = Math.min(1.8, foot + deathT * 0.02); }
                double spread = HIPS[leg][1] - swing;
                Matrix4f femur = new Matrix4f(thorax).translate(6.5f * sd, 1, (float) HIPS[leg][0]).rotateY((float) (spread * sd)).rotateX((float) elev);
                set(i++, femur);
                Matrix4f tibia = new Matrix4f(femur).translate(0, 0, (float) FEMUR).rotateX((float) knee);
                set(i++, tibia);
                set(i++, new Matrix4f(tibia).translate(0, 0, (float) TIBIA).rotateX((float) foot));
            }
        }
        // the eyes glow when awake
        float eb = fold > 0.6 ? 4 : 15;
        if (eb != eyeBright && parts.size() > 2) { eyeBright = eb; parts.get(2).setBrightness(new Display.Brightness((int) eb, (int) eb)); }
        Location to = at.clone(); to.setYaw(0); to.setPitch(0);
        boolean near = false;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 96 * 96) { near = true; break; }
        if (!near && t % 10 != 0) return; // nobody close: a pose every half second is enough
        for (int k = 0; k < parts.size(); k++) {
            ItemDisplay d = parts.get(k);
            Matrix4f m = pending.get(k);
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
