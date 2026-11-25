package net.faultlinesmp.bundle;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * FAULTLINESMPJAR: every Faultline SMP plugin in ONE jar. The 11 plugin jars ride inside this one ({@code bundled/<Name>.jar}).
 * On start (load: STARTUP, before the worlds) each is copied out to {@code plugins/FAULTLINESMPJAR/jars/} (only when it changed;
 * temp file + atomic move, so a full disk can't leave half a jar) and loaded through Paper's own plugin manager, in dependency
 * order; Paper then enables them with every other plugin after the worlds load, exactly as if they were separate jars.
 * <p>
 * Nothing about the plugins changes: same names, same commands, same PDC namespaces (faultlineitems:..., so every existing item
 * keeps working) and the SAME DATA FOLDERS: Paper always puts a plugin's data folder at {@code plugins/<Name>/}
 * ({@code server.getPluginsFolder()}), wherever its jar is, so configs, players.yml, ships.yml... stay where they are.
 * A plugin already installed as its own jar wins: the bundled copy is skipped with a warning.
 */
public final class Bundle extends JavaPlugin {

    /** Load order = dependency order (each one's softdepends come before it). */
    static final List<String> ORDER = List.of(
            "FaultlineWorld", "FaultlineItems", "FaultlineRaids", "FaultlineCosmetics", "FaultlineBosses", "FaultlineShips",
            "FaultlineKingdoms", "LimboBlackMarket", "FaultlineDeep", "FaultlineEvents", "FaultlineSkills", "FaultlineIndex");

    /** The main world's generator lives in this one: the world holds its code, so it can only change with a restart. */
    static final Set<String> NO_RELOAD = Set.of("FaultlineWorld");

    private final Map<String, String> status = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        File jars = new File(getDataFolder(), "jars");
        jars.mkdirs();
        List<Plugin> loaded = new ArrayList<>();
        for (String name : ORDER) {
            try {
                Plugin already = Bukkit.getPluginManager().getPlugin(name);
                if (already != null) {
                    status.put(name, "separate jar (" + already.getDescription().getVersion() + ")");
                    getLogger().warning(name + " is also installed as its own jar: using that one. Delete plugins/" + name + ".jar to use the bundled "
                            + name + ".");
                    continue;
                }
                byte[] data = read("bundled/" + name + ".jar");
                if (data == null) { status.put(name, "not in this jar"); continue; }
                File jar = new File(jars, name + ".jar");
                if (!jar.exists() || !Arrays.equals(sha1(Files.readAllBytes(jar.toPath())), sha1(data))) {
                    Path tmp = new File(jars, name + ".jar.tmp").toPath();
                    Files.write(tmp, data);
                    try { Files.move(tmp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (IOException e) { Files.move(tmp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                }
                Plugin p = Bukkit.getPluginManager().loadPlugin(jar);
                if (p == null) { status.put(name, "NOT LOADED (Paper refused the jar)"); getLogger().severe("Paper refused " + name + ".jar"); continue; }
                loaded.add(p);
                status.put(name, "loaded " + p.getDescription().getVersion());
                // 2.0: a load: STARTUP plugin (FaultlineWorld = the main world's generator) has to be ON before the worlds
                // load, and Paper's startup pass already took its list of plugins: switch it on ourselves, now.
                if (p.getDescription().getLoad() == org.bukkit.plugin.PluginLoadOrder.STARTUP && !p.isEnabled())
                    Bukkit.getPluginManager().enablePlugin(p);
            } catch (Throwable t) {
                status.put(name, "NOT LOADED (" + t + ")");
                getLogger().log(java.util.logging.Level.SEVERE, "Couldn't load the bundled " + name + " (is the disk full?)", t);
            }
        }
        // loaded after the worlds (a /reload or a plugin manager): Paper won't enable them for us any more
        if (!Bukkit.getWorlds().isEmpty()) for (Plugin p : loaded) if (!p.isEnabled()) Bukkit.getPluginManager().enablePlugin(p);
        getLogger().info("Faultline plugins: " + status);
    }

    // ------------------------------------------------------------------ /faultline reload

    /** The bundled jar out of the FAULTLINESMPJAR.jar that is on disk NOW (the one the owner just uploaded), not the one we started from. */
    private byte[] readFresh(String name) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(getFile())) {
            java.util.zip.ZipEntry e = z.getEntry("bundled/" + name + ".jar");
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
        }
    }

    private boolean ours(Plugin p) {
        if (p == null) return false;
        String st = status.getOrDefault(p.getName(), "");
        if (st.startsWith("separate jar")) return false;          // installed as its own jar: never ours
        if (st.startsWith("loaded") || st.contains("(reloaded)")) return true;
        try {
            File src = new File(p.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return src.getCanonicalFile().getParentFile().equals(new File(getDataFolder(), "jars").getCanonicalFile());
        } catch (Throwable t) { return false; }
    }

    /**
     * Swaps every bundled plugin for the copy inside the FAULTLINESMPJAR.jar now on disk, without a restart: all of them are
     * disabled (newest first; each saves its data like at a shutdown), forgotten by Paper (its plugin list, name table, dependency
     * tree, their commands), the new jars are written out and loaded, then enabled in order. All 11 go together even if only one
     * changed: they find each other by name and some keep what they found, so mixing old and new copies isn't safe.
     * The Dark Ocean's generator lives in FaultlineDeep's jar, so its world is saved and unloaded first: refused while anyone is in it.
     */
    private void reload(CommandSender s, boolean force) {
        List<String> names = new ArrayList<>();
        Map<String, byte[]> fresh = new LinkedHashMap<>();
        boolean changed = false;
        for (String name : ORDER) {
            if (NO_RELOAD.contains(name)) continue; // needs a restart
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null && !ours(p)) continue; // installed as its own jar: not ours to swap
            byte[] data;
            try { data = readFresh(name); } catch (IOException e) { s.sendMessage(ChatColor.RED + "Couldn't read " + getFile().getName() + ": " + e.getMessage()); return; }
            if (data == null) continue;
            File jar = new File(new File(getDataFolder(), "jars"), name + ".jar");
            try { if (!jar.exists() || !Arrays.equals(sha1(Files.readAllBytes(jar.toPath())), sha1(data))) changed = true; } catch (IOException e) { changed = true; }
            names.add(name); fresh.put(name, data);
        }
        if (!changed && !force) { s.sendMessage(ChatColor.YELLOW + "Nothing changed in " + getFile().getName() + ". Put the new FAULTLINESMPJAR.jar in plugins/ first, or use /faultline reload force."); return; }
        org.bukkit.World deep = Bukkit.getWorld("faultline_deep");
        if (names.contains("FaultlineDeep") && deep != null && !deep.getPlayers().isEmpty()) {
            StringBuilder who = new StringBuilder();
            for (org.bukkit.entity.Player p : deep.getPlayers()) who.append(who.length() == 0 ? "" : ", ").append(p.getName());
            s.sendMessage(ChatColor.RED + "Can't reload while anyone is in the Dark Ocean (its world runs on FaultlineDeep's code): " + who + ". Get them out first.");
            return;
        }
        long t0 = System.currentTimeMillis();
        s.sendMessage(ChatColor.GOLD + "Reloading " + names.size() + " Faultline plugins...");
        getLogger().info("[Reload] by " + s.getName() + ": " + names);
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.closeInventory(); // no plugin menu stays open on code that's going away
        // 1) off, newest first (Index first, Items last), like a shutdown
        List<String> rev = new ArrayList<>(names); Collections.reverse(rev);
        for (String name : rev) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p == null) continue;
            try { Bukkit.getPluginManager().disablePlugin(p); } catch (Throwable t) { getLogger().log(java.util.logging.Level.SEVERE, "[Reload] disabling " + name, t); }
            if (name.equals("FaultlineDeep") && deep != null) {
                try { if (!Bukkit.unloadWorld(deep, true)) getLogger().warning("[Reload] the Dark Ocean world wouldn't unload"); } catch (Throwable t) { getLogger().log(java.util.logging.Level.WARNING, "[Reload] unloading faultline_deep", t); }
            }
            forget(p);
        }
        // 2) the new jars out, loaded and switched on in order
        File jars = new File(getDataFolder(), "jars");
        List<Plugin> loaded = new ArrayList<>();
        for (String name : names) {
            try {
                File jar = new File(jars, name + ".jar");
                Path tmp = new File(jars, name + ".jar.tmp").toPath();
                Files.write(tmp, fresh.get(name));
                try { Files.move(tmp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException e) { Files.move(tmp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                Plugin p = Bukkit.getPluginManager().loadPlugin(jar);
                if (p == null) { status.put(name, "NOT LOADED (Paper refused the jar)"); continue; }
                loaded.add(p);
            } catch (Throwable t) {
                status.put(name, "NOT LOADED (" + t + ")");
                getLogger().log(java.util.logging.Level.SEVERE, "[Reload] loading " + name, t);
            }
        }
        for (Plugin p : loaded) {
            try { Bukkit.getPluginManager().enablePlugin(p); } catch (Throwable t) { getLogger().log(java.util.logging.Level.SEVERE, "[Reload] enabling " + p.getName(), t); }
            status.put(p.getName(), (p.isEnabled() ? "loaded " : "NOT RUNNING ") + p.getDescription().getVersion() + " (reloaded)");
        }
        try { Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer()); } catch (Throwable ignored) { }
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        int ok = 0; for (Plugin p : loaded) if (p.isEnabled()) ok++;
        String msg = (ok == names.size() ? ChatColor.GREEN : ChatColor.RED) + "Reloaded " + ok + "/" + names.size() + " in " + (System.currentTimeMillis() - t0) + " ms."
                + (ok == names.size() ? "" : " Check the console, and restart if something is off.");
        s.sendMessage(msg);
        getLogger().info("[Reload] done: " + status);
    }

    /** Takes a disabled plugin out of Paper's plugin list, name table, dependency tree and command map, so its new copy can load. */
    private void forget(Plugin p) {
        try { // the commands it registered
            org.bukkit.command.CommandMap map = Bukkit.getCommandMap();
            Map<String, Command> known = map.getKnownCommands();
            for (Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator(); it.hasNext(); ) {
                Command c = it.next().getValue();
                if (c instanceof org.bukkit.command.PluginIdentifiableCommand pc && pc.getPlugin() == p) { c.unregister(map); it.remove(); }
            }
        } catch (Throwable t) { getLogger().log(java.util.logging.Level.WARNING, "[Reload] commands of " + p.getName(), t); }
        boolean done = false;
        try { // Paper: PaperPluginManagerImpl.getInstance().instanceManager.{plugins, lookupNames, dependencyTree}
            Object impl = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl").getMethod("getInstance").invoke(null);
            Object inst = field(impl, "instanceManager");
            done = dropFrom(inst, p);
            Object tree = field(inst, "dependencyTree");
            for (java.lang.reflect.Method m : tree.getClass().getMethods())
                if (m.getName().equals("remove") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(p.getPluginMeta())) { m.invoke(tree, p.getPluginMeta()); break; }
        } catch (Throwable ignored) { }
        if (!done) try { done = dropFrom(Bukkit.getPluginManager(), p); } catch (Throwable ignored) { } // plain Bukkit / test servers
        if (!done) getLogger().warning("[Reload] couldn't remove " + p.getName() + " from the plugin list: its new copy may be refused (restart instead)");
    }

    @SuppressWarnings("unchecked")
    private static boolean dropFrom(Object holder, Plugin p) throws Exception {
        boolean any = false;
        Object list = field(holder, "plugins");
        if (list instanceof Collection<?> c) any |= c.remove(p);
        else if (list instanceof Plugin[] arr) { /* nothing to do */ }
        try {
            Object names = field(holder, "lookupNames");
            if (names instanceof Map<?, ?> m) { any |= ((Map<Object, Object>) m).values().removeIf(v -> v == p); }
        } catch (NoSuchFieldException ignored) { }
        return any;
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private byte[] read(String path) throws IOException {
        try (InputStream in = getResource(path)) { return in == null ? null : in.readAllBytes(); }
    }

    private static byte[] sha1(byte[] b) {
        try { return MessageDigest.getInstance("SHA-1").digest(b); } catch (Exception e) { return new byte[0]; }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equals("faultline")) {
            if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) { reload(s, a.length >= 2 && a[1].equalsIgnoreCase("force")); return true; }
            s.sendMessage(ChatColor.GOLD + "/faultline reload" + ChatColor.GRAY + ": swap in the plugins from the FAULTLINESMPJAR.jar now in plugins/ (no restart). "
                    + ChatColor.GOLD + "/faultline reload force" + ChatColor.GRAY + ": even if nothing changed.");
            return true;
        }
        s.sendMessage(ChatColor.GOLD + "FAULTLINESMPJAR " + getDescription().getVersion());
        for (var e : status.entrySet()) {
            Plugin p = Bukkit.getPluginManager().getPlugin(e.getKey());
            boolean on = p != null && p.isEnabled();
            s.sendMessage((on ? ChatColor.GREEN : ChatColor.RED) + " " + e.getKey() + ChatColor.GRAY + ": " + e.getValue() + (on ? ", running" : p != null ? ", not running" : ""));
        }
        return true;
    }
}
