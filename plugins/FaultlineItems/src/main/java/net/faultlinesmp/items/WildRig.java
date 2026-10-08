package net.faultlinesmp.items;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Drawing a "wild" model part (tools/wild_assets.py) for both editions, like FaultlineBosses' Wild.Part: Java players
 * see an ItemDisplay; Bedrock players see an armor stand (only they can see) wearing the same model as a helmet,
 * which FaultlineWild.mcpack draws full size.
 */
final class WildRig {
    private WildRig() {}

    static final Quaternionf Q0 = new Quaternionf().rotationY((float) Math.toRadians(-90));
    static final Quaternionf FLIP = new Quaternionf().rotationY((float) Math.PI);

    static ItemStack model(String part) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "wild/" + part));
        it.setItemMeta(m);
        return it;
    }

    static ItemDisplay display(World w, Location at, String part, String tag) {
        Location l = at.clone(); l.setYaw(0); l.setPitch(0);
        return w.spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(model(part));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setPersistent(false);
            d.addScoreboardTag(tag);
            d.addScoreboardTag("faultline_no_bedrock_fx"); // Bedrock players see the stand instead
            d.setBrightness(new Display.Brightness(12, 12));
        });
    }

    static ArmorStand stand(Plugin plugin, World w, Location at, String part, double k, String tag) {
        ArmorStand a = w.spawn(at, ArmorStand.class, s -> {
            s.setVisibleByDefault(false);
            s.setPersistent(false);
            s.addScoreboardTag(tag);
            s.addScoreboardTag(StandGuard.TAG);
            s.setInvisible(true);
            s.setGravity(false);
            s.setSilent(true);
            s.setBasePlate(false);
            s.setMarker(false); // Geyser needs a real armor stand to draw the helmet
            s.getEquipment().setHelmet(model(part));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try { s.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (RuntimeException ignored) { }
            }
        });
        AttributeInstance sc = a.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(Math.max(0.0625, Math.min(16, k)));
        for (Player p : w.getPlayers()) if (Weather.bedrock(p)) p.showEntity(plugin, a);
        StandGuard.track(a);
        return a;
    }

    /** Put the part's origin at pos facing yaw, with an extra turn in its own frame (+x front, +z right). */
    static void pose(ItemDisplay d, ArmorStand st, String part, double k, Location pos, float yaw, Quaternionf local) {
        if (d != null && d.isValid()) {
            Location l = pos.clone(); l.setYaw(0); l.setPitch(0);
            d.teleport(l);
            double[] meta = WildParts.of(part);
            Quaternionf D = new Quaternionf().rotationY((float) Math.toRadians(-yaw)).mul(Q0);
            if (local != null) D.mul(local);
            Vector3f off = new Vector3f((float) (meta[1] * k), (float) (meta[2] * k), (float) (meta[3] * k));
            D.transform(off);
            float s = (float) (meta[0] * k);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(off, new Quaternionf(D).mul(FLIP), new Vector3f(s, s, s), new Quaternionf()));
        }
        if (st != null && st.isValid()) {
            Location sl = pos.clone(); sl.setYaw(yaw); sl.setPitch(0);
            st.teleport(sl);
        }
    }
}
