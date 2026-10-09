package net.faultlinesmp.cosmetics;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.faultlinesmp.cosmetics.FaultlineCosmetics.Cosmetic;
import net.faultlinesmp.cosmetics.FaultlineCosmetics.Slot;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Draws worn cosmetics.
 *
 *   HAT, BODY    every viewer gets a fake helmet / chestplate (sendEquipmentChange). Java draws the item model on the
 *                head / the equipment asset as an armor layer; Geyser maps the same item to a Bedrock attachable.
 *   NECK, BACK   Java viewers: an ItemDisplay that follows the wearer's torso every tick.
 *                Bedrock viewers can't see those, so they get a fake LEGS (neck) / FEET (back) item instead, which
 *                the Bedrock pack draws on the body bone (so Bedrock viewers don't see that player's real leggings/boots).
 *
 * Fake equipment is never sent to the wearer: it would show up in their own armor slots.
 */
final class Renderer implements Listener {

    private static final float PX = 0.9375f / 16f;          // one model pixel on a player, in blocks
    private static final float STAND_Y = 0.9375f * (1.501f - 6 / 16f);                  // torso center, standing
    private static final float NECK_SNEAK_Y = 0.9375f * (1.501f - 3.2f / 16f) - 0.125f;  // the neck while sneaking
    private static final float SNEAK_TILT = 0.5f;            // vanilla leans the body 0.5 rad forward when sneaking

    private final FaultlineCosmetics plugin;
    private final NamespacedKey tag;
    private final Map<UUID, EnumMap<Slot, ItemDisplay>> displays = new HashMap<>();
    private final Map<UUID, float[]> lastPose = new HashMap<>();  // yaw, sneaking, scale: skip unchanged transforms
    private BukkitTask tick, resend;
    private Object floodgate, geyser;
    private Method floodgateCheck, geyserCheck;

    Renderer(FaultlineCosmetics plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "cosmetic");
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgate = c.getMethod("getInstance").invoke(null);
            floodgateCheck = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) { floodgate = null; }
        try {
            Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
            geyser = c.getMethod("api").invoke(null);
            geyserCheck = c.getMethod("isBedrockPlayer", UUID.class);
        } catch (Throwable ignored) { geyser = null; }
    }

    String bedrockDetection() {
        return floodgate != null ? "Floodgate API" : geyser != null ? "Geyser API" : "Floodgate UUIDs (no Floodgate/Geyser API found)";
    }

    boolean isBedrock(Player p) {
        try {
            if (floodgate != null) return (boolean) floodgateCheck.invoke(floodgate, p.getUniqueId());
            if (geyser != null) return (boolean) geyserCheck.invoke(geyser, p.getUniqueId());
        } catch (Throwable ignored) { }
        return Edition.bedrock(p);
    }

    void start() {
        for (World w : Bukkit.getWorlds())  // leftovers from a crash
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class))
                if (d.getPersistentDataContainer().has(tag)) d.remove();
        tick = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        int every = plugin.resendTicks();
        resend = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) { sendFakes(p); syncVisibility(p); }
        }, every, every);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    void stop() {
        if (tick != null) tick.cancel();
        if (resend != null) resend.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) { removeDisplays(p.getUniqueId()); restore(p, EnumSet.allOf(Slot.class)); }
    }

    // ------------------------------------------------------------------ equipment-based slots

    private static EquipmentSlot fakeSlot(Slot s) {
        return switch (s) { case HAT -> EquipmentSlot.HEAD; case NECK -> EquipmentSlot.LEGS; case BACK -> EquipmentSlot.FEET; case BODY -> EquipmentSlot.CHEST; };
    }

    /** The item a viewer is shown in the wearer's armor slot. Geyser matches its item model to the Bedrock mapping. */
    private static ItemStack fakeItem(Cosmetic c) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        im.setItemModel(c.slot() == Slot.BODY ? c.icon() : c.worn());
        if (c.slot() == Slot.HAT || c.slot() == Slot.BODY) {
            EquippableComponent eq = im.getEquippable();
            eq.setSlot(fakeSlot(c.slot()));
            if (c.slot() == Slot.BODY) eq.setModel(c.equipment());
            im.setEquippable(eq);
        }
        it.setItemMeta(im);
        return it;
    }

    /** Shows one viewer what the wearer has on. */
    private void sendFakes(Player wearer, Player viewer, EnumMap<Slot, Cosmetic> worn) {
        if (viewer.equals(wearer) || worn.isEmpty() || !viewer.canSee(wearer)) return;
        boolean bedrock = isBedrock(viewer);
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<Slot, Cosmetic> e : worn.entrySet()) {
            Slot s = e.getKey();
            if (s == Slot.HAT || s == Slot.BODY || bedrock) items.put(fakeSlot(s), fakeItem(e.getValue()));
        }
        if (!items.isEmpty()) viewer.sendEquipmentChange(wearer, items);
    }

    void sendFakes(Player wearer) {
        EnumMap<Slot, Cosmetic> worn = plugin.worn(wearer.getUniqueId());
        if (worn.isEmpty()) return;
        for (Player v : wearer.getTrackedBy()) sendFakes(wearer, v, worn);
    }

    /** Puts the wearer's real armor back on for everyone watching (for slots that are no longer faked). */
    private void restore(Player wearer, Set<Slot> slots) {
        if (slots.isEmpty()) return;
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        for (Slot s : slots) real.put(fakeSlot(s), wearer.getEquipment().getItem(fakeSlot(s)));
        for (Player v : wearer.getTrackedBy()) if (!v.equals(wearer)) v.sendEquipmentChange(wearer, real);
    }

    /** Re-applies everything for a wearer: call after they change what they wear. */
    void refresh(Player wearer) {
        EnumMap<Slot, Cosmetic> worn = plugin.worn(wearer.getUniqueId());
        EnumSet<Slot> off = EnumSet.allOf(Slot.class);
        off.removeAll(worn.keySet());
        restore(wearer, off);
        EnumMap<Slot, ItemDisplay> mine = displays.get(wearer.getUniqueId());
        if (mine != null) for (Slot s : EnumSet.copyOf(mine.keySet())) {   // drop displays that changed
            Cosmetic c = worn.get(s);
            ItemDisplay d = mine.get(s);
            if (c == null || !d.isValid() || !c.worn().equals(d.getItemStack().getItemMeta().getItemModel())) { d.remove(); mine.remove(s); }
        }
        lastPose.remove(wearer.getUniqueId());
        sendFakes(wearer);
    }

    private void later(Player p, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) r.run(); }, ticks);
    }

    // ------------------------------------------------------------------ display-based slots (Java viewers)

    private static boolean hidden(Player p) {
        return p.isDead() || !p.isValid() || p.getGameMode() == GameMode.SPECTATOR || p.isSwimming() || p.isGliding()
            || p.isSleeping() || p.isRiptiding() || p.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            EnumMap<Slot, Cosmetic> worn = plugin.worn(p.getUniqueId());
            worn.keySet().removeIf(s -> s != Slot.NECK && s != Slot.BACK);
            if (worn.isEmpty() || hidden(p)) { removeDisplays(p.getUniqueId()); continue; }
            EnumMap<Slot, ItemDisplay> mine = displays.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Slot.class));
            Location at = p.getLocation(); at.setYaw(0); at.setPitch(0);
            boolean spawned = false;
            for (Map.Entry<Slot, Cosmetic> e : worn.entrySet()) {
                ItemDisplay d = mine.get(e.getKey());
                if (d != null && (!d.isValid() || d.getWorld() != p.getWorld())) { d.remove(); d = null; }
                if (d == null) { mine.put(e.getKey(), spawn(p, at, e.getValue())); spawned = true; }
                else if (d.getLocation().distanceSquared(at) > 1e-6) d.teleport(at);
            }
            float yaw = p.getBodyYaw(), scale = scale(p);
            float sneak = p.isSneaking() ? 1 : 0;
            float[] last = lastPose.get(p.getUniqueId());
            if (spawned || last == null || Math.abs(wrap(yaw - last[0])) > 0.4f || last[1] != sneak || last[2] != scale) {
                Transformation t = pose(yaw, sneak > 0, scale);
                for (ItemDisplay d : mine.values()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(spawned ? 0 : 2);
                    d.setTransformation(t);
                }
                lastPose.put(p.getUniqueId(), new float[]{yaw, sneak, scale});
            }
        }
        displays.keySet().removeIf(id -> { if (Bukkit.getPlayer(id) == null) { removeDisplays0(id); return true; } return false; });
    }

    private static float wrap(float a) { a %= 360; return a > 180 ? a - 360 : a < -180 ? a + 360 : a; }

    private static float scale(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a == null ? 1f : (float) a.getValue();
    }

    /** Model center (8,8,8) on the torso center, facing the body's direction, leaning when sneaking. */
    private static Transformation pose(float bodyYaw, boolean sneaking, float scale) {
        float yawRad = (float) Math.toRadians(bodyYaw);
        Vector3f center = sneaking
            ? new Vector3f(0, NECK_SNEAK_Y - 6 * PX * (float) Math.cos(SNEAK_TILT), -6 * PX * (float) Math.sin(SNEAK_TILT))
            : new Vector3f(0, STAND_Y, 0);
        center.mul(scale).rotateY(-yawRad);
        // item displays draw models turned around: the last rotateY(PI) undoes that, so the model's +z is the front
        Quaternionf rot = new Quaternionf().rotateY(-yawRad).rotateX(sneaking ? SNEAK_TILT : 0).rotateY((float) Math.PI);
        float s = 0.9375f * scale;
        return new Transformation(center, rot, new Vector3f(s, s, s), new Quaternionf());
    }

    private ItemDisplay spawn(Player wearer, Location at, Cosmetic c) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta im = it.getItemMeta();
            im.setItemModel(c.worn());
            it.setItemMeta(im);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setPersistent(false);
            e.setTeleportDuration(2);
            e.setTransformation(pose(wearer.getBodyYaw(), wearer.isSneaking(), scale(wearer)));
            e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, wearer.getUniqueId() + ":" + c.id());
        });
        for (Player v : Bukkit.getOnlinePlayers()) if (!shouldSee(v, wearer)) v.hideEntity(plugin, d);
        return d;
    }

    private boolean shouldSee(Player viewer, Player wearer) {
        if (viewer.equals(wearer)) return plugin.showOwn();
        return !isBedrock(viewer) && viewer.canSee(wearer);
    }

    /** Hides/shows a wearer's displays per viewer (Bedrock viewers, vanished players, show-own). */
    private void syncVisibility(Player wearer) {
        EnumMap<Slot, ItemDisplay> mine = displays.get(wearer.getUniqueId());
        if (mine == null || mine.isEmpty()) return;
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean see = shouldSee(v, wearer);
            for (ItemDisplay d : mine.values()) {
                if (see && !v.canSee(d)) v.showEntity(plugin, d);
                else if (!see && v.canSee(d)) v.hideEntity(plugin, d);
            }
        }
    }

    private void removeDisplays(UUID id) {
        removeDisplays0(id);
        displays.remove(id);
    }

    private void removeDisplays0(UUID id) {
        EnumMap<Slot, ItemDisplay> mine = displays.get(id);
        if (mine != null) { for (Entity d : mine.values()) d.remove(); mine.clear(); }
        lastPose.remove(id);
    }

    // ------------------------------------------------------------------ when to re-send

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent e) {
        later(e.getPlayer(), 1, () -> sendFakes(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        later(e.getPlayer(), 1, () -> sendFakes(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent e) {
        if (!(e.getEntity() instanceof Player wearer)) return;
        Player viewer = e.getPlayer();
        later(viewer, 2, () -> { if (wearer.isOnline()) sendFakes(wearer, viewer, plugin.worn(wearer.getUniqueId())); });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        later(p, 5, () -> {
            refresh(p);
            for (Player w : Bukkit.getOnlinePlayers()) syncVisibility(w);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { later(e.getPlayer(), 5, () -> refresh(e.getPlayer())); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { later(e.getPlayer(), 5, () -> refresh(e.getPlayer())); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { removeDisplays(e.getPlayer().getUniqueId()); }
}
