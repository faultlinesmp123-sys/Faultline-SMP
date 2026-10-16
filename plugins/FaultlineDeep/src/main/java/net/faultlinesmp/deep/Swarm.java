package net.faultlinesmp.deep;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * SWARM, standing in Lucki's boat (Deep 1.5.0): the 6-piece rig from tools/below_models.py (models faultline:swarm_*,
 * still in FaultlineSMP.zip), posed the way FaultlineBosses' renderRig poses a rig: a joint per piece in pixels, arms and
 * head turning with the body. He looks down into the funnel, points at the rider, and laughs. Bedrock (no display
 * entities) sees an armor stand in black with a wither skull instead. Nothing here is saved: it's gone at the trip's end.
 */
final class Swarm {
    static final String TAG = "faultline_swarm_reveal";
    private static final String[] MODEL = {"swarm_leg_r", "swarm_leg_l", "swarm_body", "swarm_arm_r", "swarm_arm_l", "swarm_head"};
    private static final float[][] JOINT = {{-2, 12, 0}, {2, 12, 0}, {0, 12, 0}, {-5.5f, 24, 0}, {5.5f, 24, 0}, {0, 24, 0}};
    static final int LEG_R = 0, LEG_L = 1, BODY = 2, ARM_R = 3, ARM_L = 4, HEAD = 5;

    private final FaultlineDeep pl;
    private final Location root;
    private final float yaw;
    private final ItemDisplay[] parts = new ItemDisplay[6];
    private TextDisplay name;
    private ArmorStand stand;

    Swarm(FaultlineDeep pl, Location root, float yaw) {
        this.pl = pl;
        this.root = root.clone();
        this.yaw = yaw;
        World w = root.getWorld();
        for (int i = 0; i < 6; i++) {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setItemModel(new NamespacedKey("faultline", MODEL[i]));
            it.setItemMeta(m);
            ItemDisplay d = w.spawn(root, ItemDisplay.class, e -> {
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setBrightness(new Display.Brightness(12, 12));
                e.setPersistent(false);
                e.addScoreboardTag(TAG);
                e.addScoreboardTag("faultline_no_bedrock_fx"); // Bedrock gets the stand below
            });
            parts[i] = d;
        }
        name = w.spawn(root.clone().add(0, 2.45, 0), TextDisplay.class, e -> {
            e.setText("§4§lSwarm");
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
        });
        stand = w.spawn(root.clone().add(0, -0.1, 0), ArmorStand.class, s -> {
            s.setVisibleByDefault(false);
            s.setPersistent(false);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setArms(true);
            s.addScoreboardTag(TAG);
            s.setRotation(yaw, 0);
            s.setCustomName("\u00a74\u00a7lSwarm"); // Bedrock can't see the TextDisplay
            s.setCustomNameVisible(true);
            s.getEquipment().setItem(EquipmentSlot.HEAD, new ItemStack(Material.WITHER_SKELETON_SKULL));
            for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                Material mat = es == EquipmentSlot.CHEST ? Material.LEATHER_CHESTPLATE : es == EquipmentSlot.LEGS ? Material.LEATHER_LEGGINGS : Material.LEATHER_BOOTS;
                ItemStack a = new ItemStack(mat);
                LeatherArmorMeta lm = (LeatherArmorMeta) a.getItemMeta();
                lm.setColor(Color.fromRGB(20, 18, 24));
                a.setItemMeta(lm);
                s.getEquipment().setItem(es, a);
            }
            for (EquipmentSlot es : EquipmentSlot.values()) try { s.addEquipmentLock(es, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (Throwable ignored) { }
        });
        for (Player p : w.getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, stand);
        pose(0);
    }

    /** t = ticks since the reveal started (he appears at 30). */
    void tick(int t) {
        pose(t - 30);
        World w = root.getWorld();
        if (t % 6 == 0) w.spawnParticle(Particle.DUST, root.clone().add(0, 1.2, 0), 3, 0.35, 0.7, 0.35, 0,
                new Particle.DustOptions(Color.fromRGB(30, 10, 40), 1.4f));
        if (t % 20 == 0) w.spawnParticle(Particle.SQUID_INK, root.clone().add(0, 0.4, 0), 4, 0.4, 0.1, 0.4, 0.01);
    }

    /** Standing, looking down into the funnel; points at the rider, and laughs (shoulders shaking) near the end. */
    private void pose(int t) {
        float[][] r = new float[6][3];
        double laugh = t > 195 ? Math.sin(t * 1.3) * 6 : 0;
        r[BODY][0] = (float) (10 + laugh * 0.5);
        r[HEAD][0] = (float) (32 - laugh);
        float point = (float) Math.min(1, Math.max(0, (t - 40) / 15.0));
        r[ARM_R][0] = -75 * point - 5; r[ARM_R][2] = -6;
        r[ARM_L][0] = (float) (8 + laugh); r[ARM_L][2] = 8;
        r[LEG_R][2] = -3; r[LEG_L][2] = 3;
        Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
        Quaternionf qBody = euler(r[BODY]);
        Quaternionf flip = new Quaternionf().rotateY((float) Math.PI);
        Vector3f hips = new Vector3f(0, 12, 0);
        float scale = 1f, px = scale / 16f;
        for (int i = 0; i < 6; i++) {
            ItemDisplay d = parts[i];
            if (d == null || !d.isValid()) continue;
            Vector3f joint = new Vector3f(JOINT[i][0], JOINT[i][1], JOINT[i][2]);
            Quaternionf rot;
            if (i == ARM_R || i == ARM_L || i == HEAD) {
                joint.sub(hips); qBody.transform(joint); joint.add(hips);
                rot = new Quaternionf(qYaw).mul(qBody).mul(euler(r[i]));
            } else if (i == BODY) rot = new Quaternionf(qYaw).mul(qBody);
            else rot = new Quaternionf(qYaw).mul(euler(r[i]));
            joint.mul(px); qYaw.transform(joint);
            Location at = root.clone().add(joint.x, joint.y, joint.z);
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), rot.mul(flip), new Vector3f(scale, scale, scale), new Quaternionf()));
        }
    }

    private static Quaternionf euler(float[] r) {
        return new Quaternionf().rotateY((float) Math.toRadians(r[1])).rotateX((float) Math.toRadians(r[0])).rotateZ((float) Math.toRadians(r[2]));
    }

    void remove() {
        World w = root.getWorld();
        w.spawnParticle(Particle.LARGE_SMOKE, root.clone().add(0, 1, 0), 50, 0.4, 0.8, 0.4, 0.02);
        for (Entity e : parts) if (e != null && e.isValid()) e.remove();
        if (name != null && name.isValid()) name.remove();
        if (stand != null && stand.isValid()) stand.remove();
    }
}
