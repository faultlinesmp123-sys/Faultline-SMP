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
            "FaultlineItems", "FaultlineRaids", "FaultlineCosmetics", "FaultlineBosses", "FaultlineShips",
            "FaultlineKingdoms", "LimboBlackMarket", "FaultlineDeep", "FaultlineEvents", "FaultlineSkills", "FaultlineIndex");

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
            } catch (Throwable t) {
                status.put(name, "NOT LOADED (" + t + ")");
                getLogger().log(java.util.logging.Level.SEVERE, "Couldn't load the bundled " + name + " (is the disk full?)", t);
            }
        }
        // loaded after the worlds (a /reload or a plugin manager): Paper won't enable them for us any more
        if (!Bukkit.getWorlds().isEmpty()) for (Plugin p : loaded) if (!p.isEnabled()) Bukkit.getPluginManager().enablePlugin(p);
        getLogger().info("Faultline plugins: " + status);
    }

    private byte[] read(String path) throws IOException {
        try (InputStream in = getResource(path)) { return in == null ? null : in.readAllBytes(); }
    }

    private static byte[] sha1(byte[] b) {
        try { return MessageDigest.getInstance("SHA-1").digest(b); } catch (Exception e) { return new byte[0]; }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        s.sendMessage(ChatColor.GOLD + "FAULTLINESMPJAR " + getDescription().getVersion());
        for (var e : status.entrySet()) {
            Plugin p = Bukkit.getPluginManager().getPlugin(e.getKey());
            boolean on = p != null && p.isEnabled();
            s.sendMessage((on ? ChatColor.GREEN : ChatColor.RED) + " " + e.getKey() + ChatColor.GRAY + ": " + e.getValue() + (on ? ", running" : p != null ? ", not running" : ""));
        }
        return true;
    }
}
