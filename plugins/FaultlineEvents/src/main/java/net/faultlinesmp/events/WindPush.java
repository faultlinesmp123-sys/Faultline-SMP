package net.faultlinesmp.events;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Pushes a player a little WITHOUT taking their own speed away. Setting a player's velocity REPLACES the speed their
 * game has (every push would stop a sprint or a jump), so the push goes the way wind charges do it: an explosion packet
 * carrying only a knockback, which the game ADDS to the player's speed. No sound ("intentionally_empty"), no blocks, one
 * faint particle. Built by reflection on Paper's Mojang-named classes (the jar says so in its manifest); anything that
 * doesn't match turns the push off with one warning ({@link #why()}), and Bedrock players are never sent it (Geyser
 * would play a real explosion).
 */
final class WindPush {

    private static boolean tried, ok;
    private static String why = "not tried yet";
    private static Constructor<?> packet;
    private static Class<?>[] params;
    private static Constructor<?> vec3;
    private static Object faint, gust, silence, emptyList;
    private static Class<?> vec3Class, particleClass, holderClass;

    static String why() { return ok ? "working" : why; }

    static synchronized boolean ready(Logger log) {
        if (tried) return ok;
        tried = true;
        try {
            Class<?> pkt = Class.forName("net.minecraft.network.protocol.game.ClientboundExplodePacket");
            if (pkt.isRecord()) {
                RecordComponent[] rc = pkt.getRecordComponents();
                params = new Class<?>[rc.length];
                for (int k = 0; k < rc.length; k++) params[k] = rc[k].getType();
                packet = pkt.getDeclaredConstructor(params);
            } else {
                Constructor<?> best = null;
                for (Constructor<?> c : pkt.getDeclaredConstructors()) if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
                packet = best;
                params = best.getParameterTypes();
            }
            packet.setAccessible(true);
            vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            vec3 = vec3Class.getConstructor(double.class, double.class, double.class);
            particleClass = Class.forName("net.minecraft.core.particles.ParticleOptions");
            Class<?> types = Class.forName("net.minecraft.core.particles.ParticleTypes");
            faint = field(types, "WHITE_ASH", "CLOUD");
            gust = field(types, "GUST", "CLOUD");
            holderClass = Class.forName("net.minecraft.core.Holder");
            Class<?> sounds = Class.forName("net.minecraft.sounds.SoundEvents");
            Object s = null;
            for (Field f : sounds.getFields()) if (f.getName().equals("EMPTY")) s = f.get(null);
            if (s == null) throw new IllegalStateException("no silent sound (SoundEvents.EMPTY)");
            if (!holderClass.isInstance(s)) s = holderClass.getMethod("direct", Object.class).invoke(null, s);
            silence = s;
            for (Class<?> t : params) if (t.getSimpleName().equals("WeightedList")) emptyList = t.getMethod("of").invoke(null);
            // a dry run, so a mismatch shows up now and not on the first gust
            build(0, 0, 0, 0, 0, 0, false);
            ok = true;
            why = "working";
        } catch (Throwable e) {
            ok = false;
            why = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (log != null) log.warning("[Windy] the wind can't push players on this server (" + why + "); everything else still works");
        }
        return ok;
    }

    private static Object field(Class<?> c, String... names) throws ReflectiveOperationException {
        for (String n : names) try { return c.getField(n).get(null); } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(names[0]);
    }

    private static Object build(double x, double y, double z, double kx, double ky, double kz, boolean big) throws ReflectiveOperationException {
        Object center = vec3.newInstance(x, y, z), knock = vec3.newInstance(kx, ky, kz);
        Object[] args = new Object[params.length];
        boolean centerSet = false;
        for (int k = 0; k < params.length; k++) {
            Class<?> t = params[k];
            if (t == vec3Class && !centerSet) { args[k] = center; centerSet = true; }
            else if (t == Optional.class) args[k] = Optional.of(knock);
            else if (t == float.class) args[k] = 0f;
            else if (t == int.class) args[k] = 0;
            else if (t == double.class) args[k] = 0d;
            else if (particleClass.isAssignableFrom(t)) args[k] = big ? gust : faint;
            else if (holderClass.isAssignableFrom(t)) args[k] = silence;
            else if (t.getSimpleName().equals("WeightedList") && emptyList != null) args[k] = emptyList;
            else throw new IllegalStateException("unknown packet part " + t.getName());
        }
        return packet.newInstance(args);
    }

    private static Method send;
    private static Field connection;

    /** Adds (vx, vy, vz) to the player's speed. False = it couldn't. */
    static boolean push(Player p, double vx, double vy, double vz, boolean gustFx) {
        if (!ok) return false;
        try {
            var l = p.getLocation();
            Object pkt = build(l.getX(), l.getY() + 1.6, l.getZ(), vx, vy, vz, gustFx);
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            if (connection == null) {
                for (Class<?> c = handle.getClass(); c != null && connection == null; c = c.getSuperclass())
                    for (Field f : c.getDeclaredFields()) if (f.getName().equals("connection")) { f.setAccessible(true); connection = f; break; }
                if (connection == null) throw new IllegalStateException("no connection field");
            }
            Object conn = connection.get(handle);
            if (send == null) {
                for (Method m : conn.getClass().getMethods())
                    if (m.getName().equals("send") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(pkt.getClass())) { send = m; break; }
                if (send == null) throw new IllegalStateException("no send(Packet)");
            }
            send.invoke(conn, pkt);
            return true;
        } catch (Throwable e) {
            ok = false;
            why = "send: " + e;
            return false;
        }
    }
}
