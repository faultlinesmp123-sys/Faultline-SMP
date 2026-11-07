package net.faultlinesmp.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * THE BENDY EMOTE BODY (Items 1.2.72). Eleven item displays, each holding the player's own head (so every client has their
 * skin) with an item model from FaultlineEmotes.zip that stretches the head cube into one body part. Each part's translation
 * is lowered by 1024 * its part id; the pack's core shader (tools/emote_pack.py, after Stable Player Display, CC0) puts it
 * back and paints that part of the skin on it. Joints: pelvis -> waist -> chest -> head / upper arms -> forearms, and
 * pelvis -> thighs -> shins, so elbows and knees bend. Poses are joint angles in degrees (see Pose); every update is
 * interpolated by the client over 2 ticks.
 *
 * Rig frame (the display's own axes before the yaw): x = the body's right, y = up, -z = its front (an item display draws a
 * player head that way round). Angles: rotX +a swings a hanging part (arm, leg) FORWARD and tips an upright part (waist,
 * chest, head) BACK; rotY +a turns to the body's left; rotZ +a moves a hanging part's end to the body's right.
 */
final class EmoteRig {

    enum Part {
        HEAD(0, "head", false), R_ARM(1, "right_arm", true), L_ARM(2, "left_arm", true), CHEST(3, "torso", true),
        R_LEG(4, "right_leg", true), L_LEG(5, "left_leg", true), R_FORE(6, "right_forearm", true), L_FORE(7, "left_forearm", true),
        WAIST(8, "waist", true), R_SHIN(9, "lower_right_leg", true), L_SHIN(10, "lower_left_leg", true);
        final int id; final String model; final boolean squash;
        Part(int id, String model, boolean squash) { this.id = id; this.model = model; this.squash = squash; }
    }

    /** One frame: root offset (blocks: right, up, forward), body yaw (degrees, + = left) and joint angles. */
    static final class Pose {
        double right, up, fwd;
        float yaw;
        final float[] pelvis = new float[3];
        final float[] j = new float[Part.values().length * 3];
        Pose set(Part p, double x, double y, double z) { int i = p.ordinal() * 3; j[i] = (float) x; j[i + 1] = (float) y; j[i + 2] = (float) z; return this; }
        Pose add(Part p, double x, double y, double z) { int i = p.ordinal() * 3; j[i] += (float) x; j[i + 1] += (float) y; j[i + 2] += (float) z; return this; }
        float x(Part p) { return j[p.ordinal() * 3]; }
        float y(Part p) { return j[p.ordinal() * 3 + 1]; }
        float z(Part p) { return j[p.ordinal() * 3 + 2]; }

        /** A limb: forward swing, outward (away from the body: right side +z, left side -z), and twist. */
        Pose limb(Part p, double forward, double outward, double twist) {
            boolean right = p == Part.R_ARM || p == Part.R_FORE || p == Part.R_LEG || p == Part.R_SHIN;
            return set(p, forward, twist * (right ? 1 : -1), right ? outward : -outward);
        }
        /** Lean an upright part forward (+) / back (-), turn it left (+), tilt it to its right (+). */
        Pose lean(Part p, double forward, double turn, double tilt) { return set(p, -forward, turn, -tilt); }

        static Pose lerp(Pose a, Pose b, float k) {
            Pose o = new Pose();
            o.right = a.right + (b.right - a.right) * k; o.up = a.up + (b.up - a.up) * k; o.fwd = a.fwd + (b.fwd - a.fwd) * k;
            o.yaw = a.yaw + (b.yaw - a.yaw) * k;
            for (int i = 0; i < 3; i++) o.pelvis[i] = a.pelvis[i] + (b.pelvis[i] - a.pelvis[i]) * k;
            for (int i = 0; i < o.j.length; i++) o.j[i] = a.j[i] + (b.j[i] - a.j[i]) * k;
            return o;
        }
    }

    static final String TAG = "faultline_emote_rig";
    static final float L = 0.3515625f;              // one half-limb (6 px at the player's 15/16 scale)
    static final float SHOULDER_DROP = 0.1171875f;  // the shoulder joint sits 2 px under the top of the arm
    static final double SPACING = 1024;

    final ItemDisplay[] parts = new ItemDisplay[Part.values().length];
    final boolean slim;
    private final Location origin;

    /** No entities: only the maths (previews, tests). */
    EmoteRig(Location base, boolean slim) { this.slim = slim; this.origin = base.clone(); }

    EmoteRig(Player p, Location base, boolean slim) {
        this.slim = slim;
        this.origin = base.clone();
        origin.setYaw(0); origin.setPitch(0);
        World w = base.getWorld();
        for (Part part : Part.values()) {
            ItemDisplay d = (ItemDisplay) w.spawnEntity(origin, EntityType.ITEM_DISPLAY, false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag(Emotes.PROP_TAG);
            Emotes.q(() -> d.setPersistent(false));
            Emotes.q(() -> d.setVisibleByDefault(false));
            Emotes.q(() -> d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND));
            Emotes.q(() -> d.setViewRange(1.0f));
            Emotes.q(() -> d.setShadowRadius(0f));
            Emotes.q(() -> d.setInterpolationDuration(2));
            d.setItemStack(item(p, part, slim));
            parts[part.ordinal()] = d;
        }
    }

    static ItemStack item(Player p, Part part, boolean slim) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        try { m.setPlayerProfile(p.getPlayerProfile()); } catch (RuntimeException ignored) { }
        m.setItemModel(new NamespacedKey("faultline", "emote/" + part.model));
        it.setItemMeta(m);
        if (slim) Emotes.q(() -> it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString("slim").build()));
        return it;
    }

    static boolean slim(Player p) {
        try {
            return p.getPlayerProfile().getTextures().getSkinModel() == org.bukkit.profile.PlayerTextures.SkinModel.SLIM;
        } catch (RuntimeException | LinkageError e) { return false; }
    }

    boolean valid() { for (ItemDisplay d : parts) if (d == null || !d.isValid()) return false; return true; }

    void remove() { for (ItemDisplay d : parts) if (d != null) d.remove(); }

    // ---------------------------------------------------------------- forward kinematics

    static Quaternionf euler(float[] a, int i) {
        return new Quaternionf().rotateY((float) Math.toRadians(a[i + 1])).rotateX((float) Math.toRadians(a[i])).rotateZ((float) Math.toRadians(a[i + 2]));
    }

    /** Every part's place (rig frame, from the feet) and rotation for a pose. [part] = {origin, rotation}. */
    Object[][] solve(Pose q) {
        Object[][] out = new Object[Part.values().length][];
        float[] j = q.j;
        Vector3f pelvis = new Vector3f((float) q.right, 2 * L + (float) q.up, (float) -q.fwd);
        Quaternionf qp = new Quaternionf().rotateY((float) Math.toRadians(q.yaw)).mul(euler(q.pelvis, 0));
        // spine
        Quaternionf qw = new Quaternionf(qp).mul(euler(j, Part.WAIST.ordinal() * 3));
        Vector3f waistTop = new Vector3f(pelvis).add(qw.transform(new Vector3f(0, L, 0)));
        put(out, Part.WAIST, waistTop, qw);
        Quaternionf qc = new Quaternionf(qw).mul(euler(j, Part.CHEST.ordinal() * 3));
        Vector3f neck = new Vector3f(waistTop).add(qc.transform(new Vector3f(0, L, 0)));
        put(out, Part.CHEST, neck, qc);
        put(out, Part.HEAD, neck, new Quaternionf(qc).mul(euler(j, Part.HEAD.ordinal() * 3)));
        // arms
        float sx = slim ? 0.3222656f : 0.3515625f;
        for (int side = 0; side < 2; side++) {
            Part up = side == 0 ? Part.R_ARM : Part.L_ARM, lo = side == 0 ? Part.R_FORE : Part.L_FORE;
            Vector3f shoulder = new Vector3f(neck).add(qc.transform(new Vector3f(side == 0 ? sx : -sx, -SHOULDER_DROP, 0)));
            Quaternionf qa = new Quaternionf(qc).mul(euler(j, up.ordinal() * 3));
            put(out, up, new Vector3f(shoulder).add(qa.transform(new Vector3f(0, SHOULDER_DROP, 0))), qa);
            Vector3f elbow = new Vector3f(shoulder).add(qa.transform(new Vector3f(0, -(L - SHOULDER_DROP), 0)));
            put(out, lo, elbow, new Quaternionf(qa).mul(euler(j, lo.ordinal() * 3)));
        }
        // legs
        for (int side = 0; side < 2; side++) {
            Part up = side == 0 ? Part.R_LEG : Part.L_LEG, lo = side == 0 ? Part.R_SHIN : Part.L_SHIN;
            Vector3f hip = new Vector3f(pelvis).add(qp.transform(new Vector3f(side == 0 ? 0.1171875f : -0.1171875f, 0, 0)));
            Quaternionf ql = new Quaternionf(qp).mul(euler(j, up.ordinal() * 3));
            put(out, up, hip, ql);
            Vector3f knee = new Vector3f(hip).add(ql.transform(new Vector3f(0, -L, 0)));
            put(out, lo, knee, new Quaternionf(ql).mul(euler(j, lo.ordinal() * 3)));
        }
        return out;
    }

    private static void put(Object[][] out, Part p, Vector3f at, Quaternionf q) { out[p.ordinal()] = new Object[]{at, q}; }

    /** World-facing transformations for a pose: base = the feet, yaw = the way the body faces (Minecraft yaw). */
    Transformation[] transforms(Pose q, Location base, float yaw) {
        Object[][] s = solve(q);
        Quaternionf face = new Quaternionf().rotateY((float) (Math.PI - Math.toRadians(yaw)));
        Vector3f shift = new Vector3f((float) (base.getX() - origin.getX()), (float) (base.getY() - origin.getY()), (float) (base.getZ() - origin.getZ()));
        Transformation[] t = new Transformation[parts.length];
        for (Part p : Part.values()) {
            Vector3f at = face.transform(new Vector3f((Vector3f) s[p.ordinal()][0])).add(shift);
            at.y -= (float) (SPACING * p.id);
            Quaternionf rot = new Quaternionf(face).mul((Quaternionf) s[p.ordinal()][1]);
            Vector3f scale = p.squash ? new Vector3f(1, 0.9375f, 1) : new Vector3f(1, 1, 1);
            t[p.ordinal()] = new Transformation(at, rot, scale, new Quaternionf());
        }
        return t;
    }

    void apply(Pose q, Location base, float yaw) {
        Transformation[] t = transforms(q, base, yaw);
        for (int i = 0; i < parts.length; i++) {
            ItemDisplay d = parts[i];
            if (d == null) continue;
            Transformation x = t[i];
            if (!finite(x)) continue;
            d.setTransformation(x);
            Emotes.q(() -> d.setInterpolationDelay(0));
        }
    }

    static boolean finite(Transformation t) {
        Vector3f a = t.getTranslation();
        Quaternionf r = t.getLeftRotation();
        return Float.isFinite(a.x) && Float.isFinite(a.y) && Float.isFinite(a.z) && Float.isFinite(r.x) && Float.isFinite(r.y) && Float.isFinite(r.z) && Float.isFinite(r.w);
    }

    /** The client's protocol through ViaVersion (by reflection), or -1 when there's no ViaVersion (= the server's own). */
    static int protocol(Player p) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object v = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class).invoke(api, p.getUniqueId());
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) { return -1; }
    }
}
