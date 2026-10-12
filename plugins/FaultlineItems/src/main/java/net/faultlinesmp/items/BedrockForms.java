package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bedrock FORMS: the native Bedrock pop-up menus (a list of buttons, or a Yes/No question), sent through Floodgate's
 * API or, without Floodgate, Geyser's. Bedrock can't click chat text or links in a book, so anything that asks "click
 * [Yes]" or opens a book with links gives Bedrock players a form instead.
 * Everything is reflection (Cumulus, the form library, ships inside Floodgate and Geyser; neither is a dependency).
 * A method returns false when no form could be sent (no Floodgate/Geyser, an API change): the caller falls back to text.
 * Answers arrive on Geyser's network thread and are handed to the main thread here. Copies of this file: Bosses, Index, Cosmetics, Kingdoms.
 */
final class BedrockForms {
    private BedrockForms() {}

    private static volatile boolean looked;
    private static volatile Class<?> simpleC, simpleB, modalC, modalB, simpleR, modalR, formC, customC, customB, customR;
    private static volatile Object floodgate, geyser;
    private static volatile Method floodgateSend, geyserSend;

    private static Class<?> cls(String name) {
        try { return Class.forName(name); } catch (Throwable ignored) { }
        for (String pl : new String[]{"floodgate", "Geyser-Spigot"}) {
            Plugin p = Bukkit.getPluginManager().getPlugin(pl);
            if (p == null) continue;
            try { return Class.forName(name, true, p.getClass().getClassLoader()); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Method send(Object api, Class<?> form) {
        if (api == null || form == null) return null;
        for (Method m : api.getClass().getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (m.getName().equals("sendForm") && ps.length == 2 && ps[0] == UUID.class && ps[1] == form) {
                try { m.setAccessible(true); } catch (Throwable ignored) { }
                // look it up on a public interface, so a non-public implementation class can still be called
                for (Class<?> i : api.getClass().getInterfaces()) {
                    try { return i.getMethod("sendForm", UUID.class, form); } catch (Throwable ignored) { }
                }
                return m;
            }
        }
        return null;
    }

    private static synchronized void look() {
        if (looked) return;
        looked = true;
        formC = cls("org.geysermc.cumulus.form.Form");
        simpleC = cls("org.geysermc.cumulus.form.SimpleForm");
        simpleB = cls("org.geysermc.cumulus.form.SimpleForm$Builder");
        modalC = cls("org.geysermc.cumulus.form.ModalForm");
        modalB = cls("org.geysermc.cumulus.form.ModalForm$Builder");
        simpleR = cls("org.geysermc.cumulus.response.SimpleFormResponse");
        modalR = cls("org.geysermc.cumulus.response.ModalFormResponse");
        customC = cls("org.geysermc.cumulus.form.CustomForm");
        customB = cls("org.geysermc.cumulus.form.CustomForm$Builder");
        customR = cls("org.geysermc.cumulus.response.CustomFormResponse");
        try {
            Class<?> c = cls("org.geysermc.floodgate.api.FloodgateApi");
            if (c != null) { floodgate = c.getMethod("getInstance").invoke(null); floodgateSend = findSend(c, floodgate); }
        } catch (Throwable ignored) { }
        try {
            Class<?> c = cls("org.geysermc.geyser.api.GeyserApi");
            if (c != null) { geyser = c.getMethod("api").invoke(null); geyserSend = findSend(c, geyser); }
        } catch (Throwable ignored) { }
    }

    private static Method findSend(Class<?> apiInterface, Object api) {
        if (formC == null || api == null) return null;
        try { return apiInterface.getMethod("sendForm", UUID.class, formC); } catch (Throwable ignored) { }
        return send(api, formC);
    }

    /** Forms can be sent at all (Floodgate or Geyser with Cumulus). */
    static boolean available() {
        look();
        return simpleC != null && (floodgateSend != null || geyserSend != null);
    }

    private static Object call(Class<?> on, Object target, String name, Class<?> type, Object arg) throws Exception {
        return on.getMethod(name, type).invoke(target, arg);
    }

    private static boolean deliver(Player p, Object form) {
        try { if (floodgateSend != null && Boolean.TRUE.equals(floodgateSend.invoke(floodgate, p.getUniqueId(), form))) return true; } catch (Throwable ignored) { }
        try { if (geyserSend != null && Boolean.TRUE.equals(geyserSend.invoke(geyser, p.getUniqueId(), form))) return true; } catch (Throwable ignored) { }
        return false;
    }

    /**
     * A list of buttons. onPick gets the button's index (on the main thread); onClose (may be null) runs when they shut
     * it without picking. Text may use § colour codes and \n.
     */
    static boolean menu(Plugin pl, Player p, String title, String content, List<String> buttons, IntConsumer onPick, Runnable onClose) {
        if (!available() || simpleB == null || simpleR == null) return false;
        try {
            Object b = simpleC.getMethod("builder").invoke(null);
            call(simpleB, b, "title", String.class, title);
            call(simpleB, b, "content", String.class, content == null ? "" : content);
            for (String s : buttons) call(simpleB, b, "button", String.class, s);
            Method id = simpleR.getMethod("clickedButtonId");
            UUID who = p.getUniqueId();
            Consumer<Object> ok = r -> {
                int i;
                try { i = ((Number) id.invoke(r)).intValue(); } catch (Throwable t) { return; }
                Bukkit.getScheduler().runTask(pl, () -> { if (Bukkit.getPlayer(who) != null) onPick.accept(i); });
            };
            call(simpleB, b, "validResultHandler", Consumer.class, ok);
            if (onClose != null) {
                Runnable closed = () -> Bukkit.getScheduler().runTask(pl, () -> { if (Bukkit.getPlayer(who) != null) onClose.run(); });
                call(simpleB, b, "closedOrInvalidResultHandler", Runnable.class, closed);
            }
            return deliver(p, simpleB.getMethod("build").invoke(b));
        } catch (Throwable t) {
            pl.getLogger().warning("Couldn't send a Bedrock form to " + p.getName() + ": " + t);
            return false;
        }
    }

    /** A Yes/No question. onAnswer gets true for the first button (on the main thread); closing it answers nothing. */
    static boolean ask(Plugin pl, Player p, String title, String content, String yes, String no, Consumer<Boolean> onAnswer) {
        if (!available() || modalC == null || modalB == null || modalR == null) return false;
        try {
            Object b = modalC.getMethod("builder").invoke(null);
            call(modalB, b, "title", String.class, title);
            call(modalB, b, "content", String.class, content == null ? "" : content);
            call(modalB, b, "button1", String.class, yes);
            call(modalB, b, "button2", String.class, no);
            Method first = modalR.getMethod("clickedFirst");
            UUID who = p.getUniqueId();
            Consumer<Object> ok = r -> {
                boolean y;
                try { y = (Boolean) first.invoke(r); } catch (Throwable t) { return; }
                Bukkit.getScheduler().runTask(pl, () -> { if (Bukkit.getPlayer(who) != null) onAnswer.accept(y); });
            };
            call(modalB, b, "validResultHandler", Consumer.class, ok);
            return deliver(p, modalB.getMethod("build").invoke(b));
        } catch (Throwable t) {
            pl.getLogger().warning("Couldn't send a Bedrock form to " + p.getName() + ": " + t);
            return false;
        }
    }

    /** A text box (a Discord name, a code...). onText gets what they typed, trimmed (on the main thread); closing it does nothing. */
    static boolean input(Plugin pl, Player p, String title, String label, String placeholder, Consumer<String> onText) {
        if (!available() || customC == null || customB == null || customR == null) return false;
        try {
            Object b = customC.getMethod("builder").invoke(null);
            call(customB, b, "title", String.class, title);
            customB.getMethod("input", String.class, String.class).invoke(b, label, placeholder == null ? "" : placeholder);
            Method in = customR.getMethod("asInput", int.class);
            UUID who = p.getUniqueId();
            Consumer<Object> ok = r -> {
                String t;
                try { Object v = in.invoke(r, 0); t = v == null ? "" : v.toString().trim(); } catch (Throwable ex) { return; }
                Bukkit.getScheduler().runTask(pl, () -> { if (Bukkit.getPlayer(who) != null) onText.accept(t); });
            };
            call(customB, b, "validResultHandler", Consumer.class, ok);
            return deliver(p, customB.getMethod("build").invoke(b));
        } catch (Throwable t) {
            pl.getLogger().warning("Couldn't send a Bedrock form to " + p.getName() + ": " + t);
            return false;
        }
    }

    /** For tests: look again (after fake APIs were put in place). */
    static void reset() { looked = false; }
}
