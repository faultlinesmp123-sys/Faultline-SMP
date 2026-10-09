package net.faultlinesmp.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * /bedrockcheck: why can't Bedrock players see the packs? Everything we ship is checked where Geyser reads it:
 *   Geyser + Floodgate installed, enable-custom-content / force-resource-packs in Geyser's config,
 *   plugins/Geyser-Spigot/packs/: each of our .mcpacks there, up to date (bedrock_packs.yml = what this build expects),
 *     no second copy of the same pack, no unzipped folder (Geyser only reads .mcpack/.zip files), none left elsewhere,
 *   plugins/Geyser-Spigot/custom_mappings/: our four mapping files, valid JSON, format 2,
 *   and who online is detected as Bedrock (a linked Floodgate account uses a Java UUID).
 * /bedrockcheck install [branch]: downloads the packs and mappings from the GitHub repo (default branch: main, or
 * bedrock-packs.branch in the config), moves old copies of the same packs to packs/old/, then asks for a restart
 * (Geyser loads packs and mappings only when it starts).
 */
final class BedrockCheck implements CommandExecutor {
    static final String REPO = "https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/";
    static final List<String> MAPPINGS = List.of("faultline_items_mappings.json", "faultline_wild_mappings.json",
            "faultline_ships_mappings.json", "faultline_cosmetics_mappings.json");

    private final FaultlineItems plugin;
    BedrockCheck(FaultlineItems plugin) { this.plugin = plugin; }

    record Expected(String file, String uuid, String version) { }
    record Found(File file, String uuid, String version, String name) { }

    /** file name -> uuid/version, from bedrock_packs.yml in the jar (its keys have no ".mcpack": a dot is a YAML path) */
    List<Expected> expected() {
        List<Expected> out = new ArrayList<>();
        try (InputStream in = plugin.getResource("bedrock_packs.yml")) {
            if (in == null) return out;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String k : y.getKeys(false)) out.add(new Expected(k + ".mcpack", y.getString(k + ".uuid", ""), y.getString(k + ".version", "")));
        } catch (Exception ignored) { }
        return out;
    }

    File geyserFolder() {
        Plugin g = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        return g != null ? g.getDataFolder() : new File(plugin.getDataFolder().getParentFile(), "Geyser-Spigot");
    }

    /** The manifest of a pack file (Geyser takes any entry whose name contains manifest.json). */
    static Found read(File f) {
        try (ZipFile z = new ZipFile(f)) {
            ZipEntry best = z.getEntry("manifest.json");
            if (best == null) for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().contains("manifest.json")) { best = e; break; }
            }
            if (best == null) return new Found(f, null, null, null);
            JsonObject m = JsonParser.parseReader(new InputStreamReader(z.getInputStream(best), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject h = m.getAsJsonObject("header");
            String ver = "";
            if (h.has("version") && h.get("version").isJsonArray()) {
                var a = h.getAsJsonArray("version");
                ver = a.get(0).getAsInt() + "." + a.get(1).getAsInt() + "." + a.get(2).getAsInt();
            } else if (h.has("version")) ver = h.get("version").getAsString();
            return new Found(f, h.has("uuid") ? h.get("uuid").getAsString() : null, ver, h.has("name") ? h.get("name").getAsString() : f.getName());
        } catch (Exception e) {
            return new Found(f, null, null, null);
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("install")) {
            String branch = args.length > 1 ? args[1] : plugin.getConfig().getString("bedrock-packs.branch", "main");
            install(s, branch);
            return true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = report();
            Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(s::sendMessage));
        });
        return true;
    }

    static String ok(String t) { return ChatColor.GREEN + " OK  " + ChatColor.GRAY + t; }
    static String bad(String t) { return ChatColor.RED + " !!  " + ChatColor.WHITE + t; }
    static String warn(String t) { return ChatColor.YELLOW + " ??  " + ChatColor.GRAY + t; }

    List<String> report() {
        List<String> out = new ArrayList<>();
        int problems = 0;
        out.add(ChatColor.GOLD + "" + ChatColor.BOLD + "Bedrock check");
        // ---- Geyser / Floodgate
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        Plugin flood = Bukkit.getPluginManager().getPlugin("floodgate");
        if (geyser == null || !geyser.isEnabled()) { out.add(bad("Geyser-Spigot isn't running on this server.")); problems++; }
        else out.add(ok("Geyser " + geyser.getDescription().getVersion()));
        out.add(flood != null && flood.isEnabled() ? ok("Floodgate " + flood.getDescription().getVersion()) : warn("No Floodgate (Bedrock players are found through Geyser's API instead)"));
        out.add(ChatColor.GRAY + "     Bedrock detection: Floodgate UUIDs + " + Edition.sources());
        File gf = geyserFolder();
        // ---- Geyser config
        File cfg = new File(gf, "config.yml");
        if (cfg.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cfg);
            Boolean custom = flag(y, "enable-custom-content"), force = flag(y, "force-resource-packs");
            if (Boolean.FALSE.equals(custom)) { out.add(bad("Geyser config: enable-custom-content is false: custom items and models are off. Set it to true.")); problems++; }
            else out.add(ok("enable-custom-content: " + (custom == null ? "default (true)" : custom)));
            if (Boolean.FALSE.equals(force)) out.add(warn("force-resource-packs is false: a Bedrock player who taps 'no' to the download plays without the packs."));
            else out.add(ok("force-resource-packs: " + (force == null ? "default (true)" : force)));
        } else out.add(warn("No " + cfg.getPath() + " found"));
        // ---- packs
        File packs = new File(gf, "packs");
        List<Expected> exp = expected();
        Map<String, List<Found>> byUuid = new HashMap<>();
        if (!packs.isDirectory()) { out.add(bad("There is no " + packs.getPath() + " folder. Put the .mcpack files there.")); problems++; }
        else {
            File[] files = packs.listFiles();
            if (files == null) files = new File[0];
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isDirectory()) {
                    if (new File(f, "manifest.json").isFile()) { out.add(bad("packs/" + f.getName() + "/ is an UNZIPPED pack: Geyser ignores folders. Put the .mcpack file itself there.")); problems++; }
                    continue;
                }
                if (!(n.endsWith(".mcpack") || n.endsWith(".zip"))) {
                    if (n.contains("mcpack") || n.contains("faultline")) { out.add(bad("packs/" + f.getName() + ": Geyser only reads files ending in .mcpack or .zip (rename it).")); problems++; }
                    continue;
                }
                Found fd = read(f);
                if (fd.uuid() == null) { out.add(bad("packs/" + f.getName() + " has no readable manifest.json (broken or not a pack).")); problems++; continue; }
                byUuid.computeIfAbsent(fd.uuid().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(fd);
            }
            for (Expected e : exp) {
                List<Found> got = byUuid.getOrDefault(e.uuid().toLowerCase(Locale.ROOT), List.of());
                if (got.isEmpty()) { out.add(bad(e.file() + " is MISSING from packs/")); problems++; continue; }
                if (got.size() > 1) {
                    StringBuilder b = new StringBuilder();
                    for (Found f : got) b.append(f.file().getName()).append(" (").append(f.version()).append(") ");
                    out.add(bad(e.file() + " is in packs/ " + got.size() + " times: " + b + "- keep only the newest.")); problems++;
                }
                Found f = got.get(got.size() - 1);
                if (!e.version().equals(f.version())) { out.add(bad(e.file() + " is OUTDATED (" + f.version() + ", this plugin expects " + e.version() + "): replace it.")); problems++; }
                else out.add(ok(f.file().getName() + " " + f.version()));
            }
            Set<String> ours = new HashSet<>();
            for (Expected e : exp) ours.add(e.uuid().toLowerCase(Locale.ROOT));
            for (Map.Entry<String, List<Found>> en : byUuid.entrySet())
                if (!ours.contains(en.getKey())) for (Found f : en.getValue()) out.add(ChatColor.GRAY + "     also loaded: " + f.file().getName() + " (" + f.name() + ")");
        }
        // ---- packs in the wrong place
        for (File dir : new File[]{gf, new File(gf, "custom_mappings"), plugin.getDataFolder().getParentFile()}) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".mcpack")) {
                out.add(bad(f.getPath() + " is in the wrong folder: move it to " + packs.getPath())); problems++;
            }
        }
        // ---- mappings
        File maps = new File(gf, "custom_mappings");
        for (String m : MAPPINGS) {
            File f = new File(maps, m);
            if (!f.isFile()) { out.add(bad("custom_mappings/" + m + " is MISSING (items and models show as plain paper)")); problems++; continue; }
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
                int fv = o.has("format_version") ? o.get("format_version").getAsInt() : 0;
                int n = 0;
                if (o.has("items")) for (var e : o.getAsJsonObject("items").entrySet()) n += e.getValue().getAsJsonArray().size();
                if (fv != 2) { out.add(bad("custom_mappings/" + m + " is format " + fv + ", not 2")); problems++; }
                else out.add(ok("custom_mappings/" + m + " (" + n + " items)"));
            } catch (Exception ex) { out.add(bad("custom_mappings/" + m + " isn't valid JSON: " + ex.getMessage())); problems++; }
        }
        // ---- players
        List<String> who = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            who.add((Edition.bedrock(p) ? ChatColor.AQUA + "Bedrock " : ChatColor.GRAY + "Java ") + ChatColor.WHITE + p.getName());
        out.add(ChatColor.GRAY + "     Online: " + (who.isEmpty() ? "nobody" : String.join(ChatColor.GRAY + ", ", who)));
        if (problems == 0) out.add(ChatColor.GREEN + "Everything is in place. If Bedrock still sees nothing: restart the server (Geyser reads packs only at startup), then have the player remove the server and add it back (or clear its downloaded packs in Settings > Storage).");
        else out.add(ChatColor.RED + "" + problems + " problem(s). Fix them (or run /bedrockcheck install), then RESTART the server: Geyser reads packs and mappings only when it starts.");
        return out;
    }

    static Boolean flag(YamlConfiguration y, String key) {
        for (String path : new String[]{key, "gameplay." + key, "advanced." + key, "java." + key})
            if (y.contains(path)) return y.getBoolean(path);
        return null;
    }

    void install(CommandSender s, String branch) {
        File gf = geyserFolder();
        File packs = new File(gf, "packs"), maps = new File(gf, "custom_mappings"), old = new File(packs, "old");
        s.sendMessage(ChatColor.YELLOW + "Downloading the Bedrock packs from the '" + branch + "' branch...");
        List<Expected> exp = expected();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> msgs = new ArrayList<>();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
            try {
                packs.mkdirs(); maps.mkdirs();
                int done = 0;
                List<String> all = new ArrayList<>();
                for (Expected e : exp) all.add(e.file());
                all.addAll(MAPPINGS);
                for (String name : all) {
                    File dest = new File(name.endsWith(".mcpack") ? packs : maps, name);
                    File tmp = new File(dest.getParentFile(), name + ".download");
                    HttpRequest req = HttpRequest.newBuilder(URI.create(REPO + branch + "/bedrock/" + name)).timeout(Duration.ofMinutes(3)).GET().build();
                    HttpResponse<java.nio.file.Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()));
                    if (res.statusCode() != 200) { tmp.delete(); msgs.add(bad(name + ": GitHub answered " + res.statusCode() + " (is it on the '" + branch + "' branch?)")); continue; }
                    if (name.endsWith(".mcpack")) {
                        Found fd = read(tmp);
                        if (fd.uuid() == null) { tmp.delete(); msgs.add(bad(name + ": the download isn't a valid pack")); continue; }
                        // any other copy of the same pack goes to packs/old/ (two copies of one pack confuse Geyser)
                        File[] fs = packs.listFiles();
                        if (fs != null) for (File f : fs) {
                            if (!f.isFile() || f.equals(tmp) || f.equals(dest)) continue;
                            Found o = read(f);
                            if (fd.uuid().equalsIgnoreCase(String.valueOf(o.uuid()))) {
                                old.mkdirs();
                                Files.move(f.toPath(), new File(old, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                                msgs.add(ChatColor.GRAY + "     moved an older copy to packs/old/: " + f.getName());
                            }
                        }
                    }
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    msgs.add(ok(dest.getParentFile().getName() + "/" + name + " (" + (dest.length() / 1024) + " KB)"));
                    done++;
                }
                msgs.add(done == all.size()
                        ? ChatColor.GREEN + "Installed " + done + " files. RESTART the server so Geyser loads them, then run /bedrockcheck."
                        : ChatColor.YELLOW + "Installed " + done + " of " + all.size() + " files. Restart the server for those to load.");
            } catch (Exception ex) {
                msgs.add(bad("Download failed: " + ex.getMessage()));
            }
            Bukkit.getScheduler().runTask(plugin, () -> msgs.forEach(s::sendMessage));
        });
    }
}
