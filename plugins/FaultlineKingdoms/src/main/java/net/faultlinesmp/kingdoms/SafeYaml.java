package net.faultlinesmp.kingdoms;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 1.3.19: data files that can't be cut in half. accessories.yml was written straight over itself on the main thread on every
 * change: a crash / kill / full disk mid-write left half a file ("found unexpected end of stream"), the plugin then loaded
 * NOBODY's accessories and the next save erased the rest.
 * <ul>
 * <li>{@link #write}: the text goes to {@code <name>.tmp} first, then an atomic move over the real file (the old file stays
 * whole until the new one is complete). Off the main thread while the plugin runs (one writer thread, in order), on it when
 * shutting down.</li>
 * <li>{@link #load}: a file that won't parse is copied to {@code <name>.broken-<time>} (never overwritten) and SALVAGED: every
 * top-level section (one player) is parsed on its own, so one cut-off entry loses one player, not everyone.</li>
 * </ul>
 */
final class SafeYaml {
    private SafeYaml() { }

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FaultlineKingdoms-save");
        t.setDaemon(true);
        return t;
    });

    /** What {@link #load} found. */
    static final class Loaded {
        final YamlConfiguration yaml = new YamlConfiguration();
        boolean broken;          // the file didn't parse as a whole
        final List<String> lost = new ArrayList<>(); // top-level keys whose section couldn't be read (the cut-off one)
        String brokenCopy;       // where the original was copied
    }

    private static final Pattern TOP = Pattern.compile("^'?[^\\s#'][^:]*'?:.*$");
    private static final Pattern CHILD = Pattern.compile("^  '?[^\\s#'-][^:]*'?:.*$");

    /** The disk is (nearly) full: what cut accessories.yml / saveguard.yml in half. Warned on start and every 10 minutes. */
    static void diskCheck(Plugin plugin) {
        try {
            java.nio.file.Path dir = plugin.getDataFolder().toPath();
            Files.createDirectories(dir);
            long free = Files.getFileStore(dir).getUsableSpace();
            long min = 500L * 1024 * 1024;
            if (free >= min) return;
            String msg = "THE SERVER DISK IS ALMOST FULL: " + (free / 1024 / 1024) + " MB free. Files get cut in half and player data is lost. Free space now (old world backups, logs, crash reports).";
            plugin.getLogger().severe(msg);
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) if (p.isOp()) p.sendMessage(org.bukkit.ChatColor.RED + "[" + plugin.getName() + "] " + msg);
        } catch (Exception ignored) { }
    }

    static Loaded load(Plugin plugin, File file) {
        Loaded out = new Loaded();
        String queued = PENDING.get(file.getAbsoluteFile());
        if (queued != null) { try { out.yaml.loadFromString(queued); return out; } catch (Exception ignored) { } }
        if (!file.exists()) return out;
        String text;
        try { text = Files.readString(file.toPath(), StandardCharsets.UTF_8); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Couldn't read " + file.getName(), e); out.broken = true; return out; }
        try { out.yaml.loadFromString(text); return out; }
        catch (Exception whole) {
            out.broken = true;
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File copy = new File(file.getParentFile(), file.getName() + ".broken-" + stamp);
            try { Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING); out.brokenCopy = copy.getName(); }
            catch (IOException e) { plugin.getLogger().warning("Couldn't copy the broken " + file.getName() + ": " + e.getMessage()); }
            plugin.getLogger().severe(file.getName() + " is damaged (" + firstLine(whole) + "). The original is kept as "
                    + (out.brokenCopy == null ? "(copy failed)" : out.brokenCopy) + "; salvaging it entry by entry.");
        }
        // salvage: split at top-level keys and parse each section on its own
        String[] lines = text.split("\r?\n", -1);
        List<int[]> blocks = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (TOP.matcher(lines[i]).matches()) { if (start >= 0) blocks.add(new int[]{start, i}); start = i; }
        }
        if (start >= 0) blocks.add(new int[]{start, lines.length});
        int ok = 0;
        for (int[] b : blocks) {
            StringBuilder sb = new StringBuilder();
            for (int i = b[0]; i < b[1]; i++) sb.append(lines[i]).append('\n');
            String key = lines[b[0]].substring(0, lines[b[0]].indexOf(':')).replace("'", "").trim();
            try {
                YamlConfiguration part = new YamlConfiguration();
                part.loadFromString(sb.toString());
                for (String k : part.getKeys(false)) out.yaml.set(k, part.get(k));
                ok++;
            } catch (Exception e) {
                // one level down: a section holding everyone (players:) loses only the cut-off entry
                int got = 0, total = 0;
                List<Integer> starts = new ArrayList<>();
                for (int i = b[0] + 1; i < b[1]; i++) if (CHILD.matcher(lines[i]).matches()) starts.add(i);
                for (int j = 0; j < starts.size(); j++) {
                    int from = starts.get(j), to = j + 1 < starts.size() ? starts.get(j + 1) : b[1];
                    StringBuilder cb = new StringBuilder(lines[b[0]]).append('\n');
                    for (int i = from; i < to; i++) cb.append(lines[i]).append('\n');
                    total++;
                    try {
                        YamlConfiguration part = new YamlConfiguration();
                        part.loadFromString(cb.toString());
                        for (String k : part.getKeys(true)) if (!part.isConfigurationSection(k)) out.yaml.set(k, part.get(k));
                        got++;
                    } catch (Exception e2) { out.lost.add(key + "." + lines[from].trim().replaceAll(":.*$", "").replace("'", "")); }
                }
                if (got > 0) { ok++; plugin.getLogger().severe(file.getName() + ": '" + key + "' salvaged " + got + " of " + total + " entries"); }
                else if (total == 0) out.lost.add(key);
            }
        }
        plugin.getLogger().severe(file.getName() + ": salvaged " + ok + " of " + blocks.size() + " entries"
                + (out.lost.isEmpty() ? "." : "; couldn't read " + out.lost));
        return out;
    }

    /** Writes the text safely: off the main thread unless the plugin is shutting down. */
    static void write(Plugin plugin, File file, String text) {
        File key = file.getAbsoluteFile();
        PENDING.put(key, text); // reads see it at once, even before it reaches the disk
        Runnable job = () -> {
            String latest = PENDING.get(key);
            if (latest == null) return; // a later job already wrote it
            try { atomic(file, latest); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Failed to save " + file.getName() + " (is the disk full?)", e); }
            finally { PENDING.remove(key, latest); }
        };
        if (!plugin.isEnabled()) { job.run(); return; }
        try { WRITER.execute(job); } catch (RuntimeException e) { job.run(); }
    }

    /** Text queued for a file and not on the disk yet (several saves in a row = only the newest is written). */
    private static final java.util.concurrent.ConcurrentHashMap<File, String> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    /** The file as it is now: what's queued for it, else the disk (a re-read right after a save used to miss it). */
    static YamlConfiguration fresh(File file) {
        YamlConfiguration y = new YamlConfiguration();
        String queued = PENDING.get(file.getAbsoluteFile());
        try {
            if (queued != null) y.loadFromString(queued);
            else if (file.exists()) y.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return y;
    }

    /** {@link #write} for code that used yaml.save(file) inside a try/catch(IOException). */
    static void save(Plugin plugin, YamlConfiguration y, File file) throws IOException { write(plugin, file, y.saveToString()); }

    /** Written now, on this thread (data that must be on the disk before the next line runs, e.g. an inventory about to be cleared). */
    static void saveNow(YamlConfiguration y, File file) throws IOException { PENDING.remove(file.getAbsoluteFile()); atomic(file, y.saveToString()); }

    static void atomic(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(dir, file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    /** On disable: let queued writes finish before the plugin goes. */
    static void flush() {
        try {
            java.util.concurrent.Future<?> f = WRITER.submit(() -> { });
            f.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private static String firstLine(Throwable t) {
        String m = String.valueOf(t.getMessage());
        int nl = m.indexOf('\n');
        return (nl < 0 ? m : m.substring(0, nl)).trim();
    }
}
