package net.faultlinesmp.items;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * A mimic's line in the tab list: the same player-info packets the server sends for a real player (ADD_PLAYER, listed,
 * latency, game mode), built by reflection on the server's own classes (Paper runs Mojang-mapped), so nothing extra is
 * needed. Anything missing = it logs once and the mimic just isn't in the tab list.
 */
final class MimicTab {
    private static boolean broken;
    private static final Random random = new Random();

    private MimicTab() { }

    /** Adds the entry for these viewers (a random UUID, the copied name and skin). */
    static void add(Collection<? extends Player> viewers, UUID id, String name, PlayerProfile skin) { add(viewers, id, name, skin, null); }

    /** `tabName` = what the tab list shows (e.g. "[Kingdom 1] Cloud" like the real players), or null for the plain name. */
    static void add(Collection<? extends Player> viewers, UUID id, String name, PlayerProfile skin, net.kyori.adventure.text.Component tabName) {
        add(viewers, id, name, skin, tabName, true);
    }

    /** `listed` false = an entry only so the game can draw a player entity with that skin (1.2.95: the mimic's body). */
    static void add(Collection<? extends Player> viewers, UUID id, String name, PlayerProfile skin, net.kyori.adventure.text.Component tabName, boolean listed) {
        if (broken || viewers.isEmpty()) return;
        try {
            PlayerProfile pp = Bukkit.createProfile(id, name);
            if (skin != null) for (ProfileProperty pr : skin.getProperties()) if (pr.getName().equals("textures")) pp.setProperty(pr);
            Object gameProfile = gameProfile(pp);
            Class<?> pkt = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> actC = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            Class<?> entryC = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            Object nmsName = null;
            if (tabName != null) try { nmsName = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asVanilla", net.kyori.adventure.text.Component.class).invoke(null, tabName); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            EnumSet<?> acts = nmsName != null ? actions(actC, "ADD_PLAYER", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_GAME_MODE", "UPDATE_DISPLAY_NAME")
                    : actions(actC, "ADD_PLAYER", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_GAME_MODE");
            Object entry = entry(entryC, id, gameProfile, nmsName, listed);
            Object packet = packet(pkt, acts, List.of(entry));
            for (Player p : viewers) send(p, packet);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Bukkit.getLogger().warning("[Mimic] couldn't put mimics in the tab list on this server version: " + e);
        }
    }

    static void remove(Collection<? extends Player> viewers, UUID id) {
        if (broken || viewers.isEmpty()) return;
        try {
            Class<?> pkt = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
            Object packet = pkt.getConstructor(List.class).newInstance(List.of(id));
            for (Player p : viewers) send(p, packet);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Bukkit.getLogger().warning("[Mimic] couldn't take a mimic out of the tab list: " + e);
        }
    }

    private static Object gameProfile(PlayerProfile pp) throws ReflectiveOperationException {
        for (String n : new String[]{"buildGameProfile", "getGameProfile", "asAuthlibCopy", "asAuthlib"}) {
            try { Method m = pp.getClass().getMethod(n); m.setAccessible(true); Object o = m.invoke(pp); if (o != null) return o; }
            catch (NoSuchMethodException ignored) { }
        }
        // last resort: a bare GameProfile (no skin)
        Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
        return gp.getConstructor(UUID.class, String.class).newInstance(pp.getId(), pp.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EnumSet<?> actions(Class<?> actC, String... names) {
        EnumSet set = EnumSet.noneOf((Class<Enum>) actC);
        for (String n : names) try { set.add(Enum.valueOf((Class<Enum>) actC, n)); } catch (IllegalArgumentException ignored) { }
        return set;
    }

    /** The Entry record, filled by type (its fields changed between versions): listed / hat on, a believable ping. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object entry(Class<?> entryC, UUID id, Object profile, Object displayName, boolean listed) throws ReflectiveOperationException {
        RecordComponent[] rc = entryC.getRecordComponents();
        Class<?>[] types = new Class<?>[rc.length];
        Object[] args = new Object[rc.length];
        int ints = 0, bools = 0;
        for (int i = 0; i < rc.length; i++) {
            Class<?> t = rc[i].getType();
            types[i] = t;
            if (t == UUID.class) args[i] = id;
            else if (t.isInstance(profile)) args[i] = profile;
            else if (displayName != null && t.isInstance(displayName)) args[i] = displayName;
            else if (t == boolean.class) args[i] = bools++ == 0 ? listed : true; // listed first, then the hat layer
            else if (t == int.class) args[i] = ints++ == 0 ? 25 + random.nextInt(70) : 0;
            else if (t.isEnum()) args[i] = Enum.valueOf((Class<Enum>) t, "SURVIVAL");
            else args[i] = null; // display name, chat session
        }
        Constructor<?> c = entryC.getDeclaredConstructor(types);
        c.setAccessible(true);
        return c.newInstance(args);
    }

    private static Object packet(Class<?> pkt, EnumSet<?> acts, List<Object> entries) throws ReflectiveOperationException {
        for (Constructor<?> c : pkt.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == EnumSet.class && p[1] == List.class) { c.setAccessible(true); return c.newInstance(acts, entries); }
        }
        // the public one takes server players: give it none, then set its entries
        Object packet = pkt.getConstructor(EnumSet.class, Collection.class).newInstance(acts, List.of());
        for (Field f : pkt.getDeclaredFields()) {
            if (f.getType() == List.class) { f.setAccessible(true); f.set(packet, entries); return packet; }
        }
        throw new NoSuchFieldException("entries");
    }

    private static Method getHandle, sendM;
    private static Field connF;

    /** Sends a server packet to this player (the lookups are done once and kept: the mimic's body sends some every tick). */
    static void send(Player p, Object packet) throws ReflectiveOperationException {
        Object handle = (getHandle != null && getHandle.getDeclaringClass().isInstance(p) ? getHandle : (getHandle = p.getClass().getMethod("getHandle"))).invoke(p);
        if (connF == null || !connF.getDeclaringClass().isInstance(handle)) {
            Field cf = null;
            for (Class<?> c = handle.getClass(); c != null && cf == null; c = c.getSuperclass())
                try { cf = c.getDeclaredField("connection"); } catch (NoSuchFieldException ignored) { }
            if (cf == null) throw new NoSuchFieldException("connection");
            cf.setAccessible(true);
            connF = cf;
        }
        Object conn = connF.get(handle);
        if (conn == null) return; // still logging in
        if (sendM == null || !sendM.getDeclaringClass().isInstance(conn)) {
            Method found = null;
            for (Class<?> c = conn.getClass(); c != null && found == null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals("send") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInterface() && m.getParameterTypes()[0].isInstance(packet)) { found = m; break; }
            if (found == null) throw new NoSuchMethodException("send");
            found.setAccessible(true);
            sendM = found;
        }
        sendM.invoke(conn, packet);
    }
}
