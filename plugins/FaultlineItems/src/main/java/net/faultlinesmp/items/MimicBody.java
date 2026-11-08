package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.*;
import java.util.*;

/**
 * 1.2.95: the mimic's body as a REAL PLAYER ENTITY on every viewer's screen. A mannequin can't play a player's arm swing, and
 * draws none of a player's movement (sprint lean, elytra glide, swimming, crouch walk), so each viewer is sent a player entity
 * that USES THE MANNEQUIN'S OWN ENTITY ID (the mannequin itself is hidden from them): their hits on it reach the server as
 * hits on the mannequin, which passes them to the mimic like before; the server keeps the mannequin for the hitbox, the gear
 * and the use-item flags (eating, shield, bow), and this mirrors them every tick: position and look, head, equipment, the
 * entity flags (sprinting, crouching, swimming, gliding, riptide, using an item), the real swing (the animate packet) and the
 * hurt flash. Built from the server's own packet classes by reflection (Paper runs Mojang-mapped); if any of it is missing
 * on this version it logs once and every mimic shows its plain mannequin again (`mimic-player.player-body: false` = off).
 */
final class MimicBody {
    private static boolean tried, ok;
    private static Constructor<?> addCtor, headCtor, animCtor, hurtCtor, removeCtor, dataCtor, equipCtor;
    private static Method posOf, getHandle, entityData, nonDefault, dataGet, dvCreate, dvId, itemBySlot, pairOf;
    private static Object playerType, zero, flagsAcc, poseAcc;
    private static Object[] slots;
    private static final Map<String, Object> POSES = new HashMap<>();
    private static final Set<Integer> allowed = new HashSet<>();
    private static Class<?> playerClass;

    private MimicBody() { }

    static boolean active(FaultlineItems plugin) {
        return plugin.getConfig().getBoolean("mimic-player.player-body", true) && init();
    }

    private static synchronized boolean init() {
        if (tried) return ok;
        tried = true;
        try {
            String g = "net.minecraft.network.protocol.game.";
            Class<?> add = Class.forName(g + "ClientboundAddEntityPacket");
            for (Constructor<?> c : add.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 11 && p[0] == int.class && p[1] == UUID.class) { addCtor = c; c.setAccessible(true); }
            }
            if (addCtor == null) throw new NoSuchMethodException("ClientboundAddEntityPacket(int, UUID, ...)");
            Class<?> entityC = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> livingC = Class.forName("net.minecraft.world.entity.LivingEntity");
            playerClass = Class.forName("net.minecraft.world.entity.player.Player");
            playerType = Class.forName("net.minecraft.world.entity.EntityType").getField("PLAYER").get(null);
            zero = Class.forName("net.minecraft.world.phys.Vec3").getField("ZERO").get(null);
            posOf = Class.forName(g + "ClientboundEntityPositionSyncPacket").getMethod("of", entityC); // interpolated movement, like a player's
            headCtor = Class.forName(g + "ClientboundRotateHeadPacket").getConstructor(entityC, byte.class);
            animCtor = Class.forName(g + "ClientboundAnimatePacket").getConstructor(entityC, int.class);
            try { hurtCtor = Class.forName(g + "ClientboundHurtAnimationPacket").getConstructor(livingC); } catch (ReflectiveOperationException ignored) { }
            removeCtor = Class.forName(g + "ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            Class<?> dataP = Class.forName(g + "ClientboundSetEntityDataPacket");
            dataCtor = dataP.getConstructor(int.class, List.class);
            Class<?> equipP = Class.forName(g + "ClientboundSetEquipmentPacket");
            equipCtor = equipP.getConstructor(int.class, List.class);
            Class<?> slotC = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            slots = new Object[6];
            String[] names = {"MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD"};
            for (int i = 0; i < 6; i++) slots[i] = slotC.getField(names[i]).get(null);
            itemBySlot = livingC.getMethod("getItemBySlot", slotC);
            pairOf = Class.forName("com.mojang.datafixers.util.Pair").getMethod("of", Object.class, Object.class);
            Class<?> sed = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            Class<?> acc = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
            Class<?> dv = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
            entityData = entityC.getMethod("getEntityData");
            nonDefault = sed.getMethod("getNonDefaultValues");
            dataGet = sed.getMethod("get", acc);
            dvCreate = dv.getMethod("create", acc, Object.class);
            dvId = dv.getMethod("id");
            Method accId = acc.getMethod("id");
            // only the data a PLAYER entity has too (Entity, LivingEntity, Avatar): the mannequin's own extra fields would
            // land on a player's slots of another type on the client and drop the viewer's connection
            for (Class<?> c = playerClass; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c == playerClass) continue;
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !acc.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    allowed.add((Integer) accId.invoke(f.get(null)));
                }
            }
            Field ff = entityC.getDeclaredField("DATA_SHARED_FLAGS_ID"); ff.setAccessible(true); flagsAcc = ff.get(null);
            Field pf = entityC.getDeclaredField("DATA_POSE"); pf.setAccessible(true); poseAcc = pf.get(null);
            Class<?> poseC = Class.forName("net.minecraft.world.entity.Pose");
            for (Object o : poseC.getEnumConstants()) POSES.put(((Enum<?>) o).name(), o);
            if (allowed.isEmpty() || !POSES.containsKey("STANDING")) throw new IllegalStateException("no entity data to copy");
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ok = false;
            Bukkit.getLogger().warning("[Mimic] can't draw mimics as players on this server version (they stay mannequins): " + e);
        }
        return ok;
    }

    /** What the mimic is doing that a player's body shows. */
    record Look(boolean sprinting, boolean crouching, boolean swimming, boolean gliding, boolean spin) { }

    static Object handle(Entity e) throws ReflectiveOperationException {
        Method gh = getHandle != null && getHandle.getDeclaringClass().isInstance(e) ? getHandle : (getHandle = e.getClass().getMethod("getHandle"));
        return gh.invoke(e);
    }

    /** Every tick: who sees it (shows it to new viewers, drops ones gone), then position, head, data and gear. */
    static void tick(FaultlineItems plugin, MimicPlayer.Mimic m, Look look) {
        if (!m.body.isValid()) return;
        try {
            Object h = handle(m.body);
            int id = m.body.getEntityId();
            World w = m.body.getWorld();
            double range = plugin.getConfig().getDouble("mimic-player.player-body-range", 96);
            // who drops out
            for (Iterator<UUID> it = m.bodyViewers.iterator(); it.hasNext(); ) {
                Player p = Bukkit.getPlayer(it.next());
                if (p == null) { it.remove(); continue; }
                if (p.getWorld() != w || p.getLocation().distanceSquared(m.body.getLocation()) > (range + 16) * (range + 16)) {
                    MimicTab.send(p, removeCtor.newInstance((Object) new int[]{id}));
                    it.remove();
                }
            }
            // who comes in
            List<Player> fresh = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                if (m.bodyViewers.contains(p.getUniqueId()) || p.getLocation().distanceSquared(m.body.getLocation()) > range * range) continue;
                fresh.add(p);
            }
            if (!fresh.isEmpty()) {
                if (m.bodyOwnInfo) MimicTab.add(fresh, m.bodyId, m.name, m.skin, null, false);
                Object add = addCtor.newInstance(id, m.bodyId, m.body.getLocation().getX(), m.body.getLocation().getY(), m.body.getLocation().getZ(),
                        m.body.getLocation().getPitch(), m.body.getLocation().getYaw(), playerType, 0, zero, (double) m.body.getLocation().getYaw());
                Object data = dataPacket(h, id, look, true), equip = equipPacket(h, id);
                for (Player p : fresh) {
                    p.hideEntity(plugin, m.body); // its mannequin is gone for them: the player entity takes its id
                    MimicTab.send(p, add);
                    if (data != null) MimicTab.send(p, data);
                    MimicTab.send(p, equip);
                    m.bodyViewers.add(p.getUniqueId());
                }
            }
            if (m.bodyViewers.isEmpty()) return;
            Object pos = posOf.invoke(null, h);
            Object head = headCtor.newInstance(h, (byte) Math.floor(m.body.getLocation().getYaw() * 256f / 360f));
            Object data = null, equip = null;
            int sig = look.hashCode() * 31 + flagsOf(h);
            if (sig != m.bodyLookSig || m.t % 20 == 0) { data = dataPacket(h, id, look, m.t % 20 == 0); m.bodyLookSig = sig; }
            if (m.t % 4 == 0) {
                String es = equipSig(h);
                if (!es.equals(m.bodyEquipSig)) { equip = equipPacket(h, id); m.bodyEquipSig = es; }
            }
            for (UUID u : m.bodyViewers) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                MimicTab.send(p, pos);
                MimicTab.send(p, head);
                if (data != null) MimicTab.send(p, data);
                if (equip != null) MimicTab.send(p, equip);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(plugin, e);
        }
    }

    /** The real arm swing (0 = main hand, 3 = off hand). */
    static void swing(FaultlineItems plugin, MimicPlayer.Mimic m, boolean offhand) { send(plugin, m, () -> animCtor.newInstance(handle(m.body), offhand ? 3 : 0)); }

    /** The red hurt flash and wobble. */
    static void hurt(FaultlineItems plugin, MimicPlayer.Mimic m) { if (hurtCtor != null) send(plugin, m, () -> hurtCtor.newInstance(handle(m.body))); }

    /** Gone (removed / killed): off every viewer's screen, its own player entry too. */
    static void remove(FaultlineItems plugin, MimicPlayer.Mimic m) {
        if (!ok || m.bodyViewers.isEmpty()) { m.bodyViewers.clear(); return; }
        List<Player> who = new ArrayList<>();
        for (UUID u : m.bodyViewers) { Player p = Bukkit.getPlayer(u); if (p != null) who.add(p); }
        try {
            Object rm = removeCtor.newInstance((Object) new int[]{m.body.getEntityId()});
            for (Player p : who) MimicTab.send(p, rm);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        if (m.bodyOwnInfo) MimicTab.remove(who, m.bodyId);
        m.bodyViewers.clear();
    }

    interface PacketMaker { Object make() throws ReflectiveOperationException; }

    private static void send(FaultlineItems plugin, MimicPlayer.Mimic m, PacketMaker mk) {
        if (!ok || m.bodyViewers.isEmpty()) return;
        try {
            Object pk = mk.make();
            for (UUID u : m.bodyViewers) { Player p = Bukkit.getPlayer(u); if (p != null) MimicTab.send(p, pk); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { fail(plugin, e); }
    }

    private static int flagsOf(Object h) throws ReflectiveOperationException {
        Object v = dataGet.invoke(entityData.invoke(h), flagsAcc);
        return v instanceof Byte b ? b : 0;
    }

    /** The mannequin's player-shaped data (use-item flags, skin layers, health...), with the movement flags and pose set. */
    @SuppressWarnings("unchecked")
    private static Object dataPacket(Object h, int id, Look look, boolean full) throws ReflectiveOperationException {
        List<Object> out = new ArrayList<>();
        if (full) {
            Object list = nonDefault.invoke(entityData.invoke(h));
            if (list instanceof List<?> l) for (Object dvv : l) {
                int did = (Integer) dvId.invoke(dvv);
                if (allowed.contains(did) && did != idOf(flagsAcc) && did != idOf(poseAcc)) out.add(dvv);
            }
        }
        int flags = flagsOf(h) & ~(0x02 | 0x08 | 0x10 | 0x80);
        if (look.crouching()) flags |= 0x02;
        if (look.sprinting()) flags |= 0x08;
        if (look.swimming()) flags |= 0x10;
        if (look.gliding()) flags |= 0x80;
        out.add(dvCreate.invoke(null, flagsAcc, (byte) flags));
        String pose = look.spin() ? "SPIN_ATTACK" : look.gliding() ? "FALL_FLYING" : look.swimming() ? "SWIMMING" : look.crouching() ? "CROUCHING" : "STANDING";
        Object pv = POSES.getOrDefault(pose, POSES.get("STANDING"));
        out.add(dvCreate.invoke(null, poseAcc, pv));
        return dataCtor.newInstance(id, out);
    }

    private static final Map<Object, Integer> IDS = new IdentityHashMap<>();

    private static int idOf(Object acc) throws ReflectiveOperationException {
        Integer v = IDS.get(acc);
        if (v == null) { v = (Integer) acc.getClass().getMethod("id").invoke(acc); IDS.put(acc, v); }
        return v;
    }

    private static Object equipPacket(Object h, int id) throws ReflectiveOperationException {
        List<Object> list = new ArrayList<>();
        for (Object slot : slots) list.add(pairOf.invoke(null, slot, itemBySlot.invoke(h, slot)));
        return equipCtor.newInstance(id, list);
    }

    private static String equipSig(Object h) throws ReflectiveOperationException {
        StringBuilder b = new StringBuilder();
        for (Object slot : slots) b.append(System.identityHashCode(itemBySlot.invoke(h, slot))).append(',');
        return b.toString();
    }

    /** Something broke on this version: logs once, every mimic is a plain mannequin again for everyone. */
    private static void fail(FaultlineItems plugin, Throwable e) {
        if (!ok) return;
        ok = false;
        Bukkit.getLogger().warning("[Mimic] drawing mimics as players failed, back to mannequins: " + e);
        if (plugin.mimics == null) return;
        for (MimicPlayer.Mimic m : new ArrayList<>(plugin.mimics.byBrain.values())) {
            for (UUID u : m.bodyViewers) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                try { MimicTab.send(p, removeCtor.newInstance((Object) new int[]{m.body.getEntityId()})); } catch (Throwable ignored) { }
                p.showEntity(plugin, m.body);
            }
            m.bodyViewers.clear();
        }
    }
}
