package net.faultlinesmp.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/**
 * FaultlineWorld: the Faultline SMP 2.0 overworld. Loads at STARTUP so Paper can ask it for the main world's
 * generator (bukkit.yml: worlds.<level-name>.generator: FaultlineWorld). On a brand new server it installs its
 * datapack (the custom biomes, sea level 200, diamonds at y 0-35) and the bukkit.yml line, then stops once:
 * start the server again and the new world is made with both.
 */
public final class FaultlineWorld extends JavaPlugin {

    static FaultlineWorld INSTANCE;
    final WorldGen gen = new WorldGen();
    Life life;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();
        Sites.load(getLogger());
        resetFarlandsIfAsked();     // (before setup: the world isn't open yet at STARTUP)
        if (setup()) return;
        Bukkit.getScheduler().runTask(this, this::afterWorlds);
        life = new Life(this);
        Bukkit.getPluginManager().registerEvents(life, this);
        rim = new Rim(this);   // the Outer Rim: lighthouses, Lost Villagers, the Citadel
        Bukkit.getPluginManager().registerEvents(rim, this);
    }

    Rim rim;

    @Override
    public void onDisable() { if (life != null) life.stop(); if (rim != null) rim.stop(); }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return gen; }

    /** The level name from server.properties (the main world's folder). */
    static String levelName() {
        Properties p = new Properties();
        try (var in = Files.newInputStream(new File("server.properties").toPath())) { p.load(in); } catch (Exception ignored) { }
        return p.getProperty("level-name", "world");
    }

    /**
     * First start: put the datapack in place and point bukkit.yml at us. Returns true when the server has to restart
     * before the world is made (fresh server, nothing was there yet).
     */
    private boolean setup() {
        String level = levelName();
        File worldDir = new File(Bukkit.getWorldContainer(), level);
        boolean fresh = !new File(worldDir, "level.dat").exists();
        boolean datapackLoaded = WorldGen.BiomeMap.lookup("faultline:great_sea") != null;
        boolean changed = false;
        File bukkitFile = new File("bukkit.yml");
        boolean lineSet = false;
        try {
            if (bukkitFile.exists()) { YamlConfiguration b = new YamlConfiguration(); b.load(bukkitFile); lineSet = getName().equals(b.getString("worlds." + level + ".generator")); }
        } catch (Exception e) {
            getLogger().severe("[World] bukkit.yml can't be read (" + e.getMessage() + "): not touching it. Fix it, or add by hand:  worlds: { " + level + ": { generator: " + getName() + " } }");
            return false;
        }
        // AUDIT: an existing world made WITHOUT us is never touched. Switching its generator / sea level would make every new
        // chunk a cliff against the old ones. The owner wipes the world folder first; then this runs on the fresh start.
        if (!fresh && !lineSet) {
            getLogger().severe("[World] '" + level + "' already exists and wasn't made by FaultlineWorld: leaving it alone. Stop the server, delete the '"
                    + level + "' folder (and its nether/end if you want them fresh), start again.");
            return false;
        }
        try {
            File dp = new File(worldDir, "datapacks/faultline_world.zip");
            byte[] ours = null;
            try (InputStream in = getResource("faultline_world.zip")) { if (in != null) ours = in.readAllBytes(); }
            // A newer jar carries a newer datapack (mob lists, biome looks: the sea level and the terrain never change in it).
            // It replaces the installed one; the server reads it on its next start.
            boolean stale = ours != null && dp.exists() && !java.util.Arrays.equals(ours, Files.readAllBytes(dp.toPath()));
            if (ours != null && (!datapackLoaded || !dp.exists() || stale)) {
                dp.getParentFile().mkdirs();
                File tmp = new File(dp.getPath() + ".tmp");
                Files.write(tmp.toPath(), ours);
                Files.move(tmp.toPath(), dp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                changed = !datapackLoaded;
                if (stale && !fresh) getLogger().warning("[World] the Faultline datapack was updated by this jar. RESTART the server once more for it to take effect (mob spawn lists, biome looks).");
            }
            if (!lineSet) {
                YamlConfiguration y = new YamlConfiguration();
                if (bukkitFile.exists()) y.load(bukkitFile);   // throws on a broken file: never write an empty one over it
                y.set("worlds." + level + ".generator", getName());
                File tmp = new File("bukkit.yml.faultline.tmp");
                Files.writeString(tmp.toPath(), y.saveToString());
                Files.move(tmp.toPath(), bukkitFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                changed = true;
            }
        } catch (Exception e) {
            getLogger().severe("[World] couldn't install the datapack / bukkit.yml generator: " + e);
        }
        if (changed && fresh && getConfig().getBoolean("stop-after-install", true)) {
            getLogger().severe("==========================================================================");
            getLogger().severe(" Faultline World installed its datapack and the bukkit.yml generator line.");
            getLogger().severe(" The server stops now, BEFORE the world is made. Start it again: the Faultline");
            getLogger().severe(" overworld (3 islands, the Great Sea, the new biomes) is generated on that start.");
            getLogger().severe("==========================================================================");
            System.out.flush();
            Runtime.getRuntime().halt(0);  // nothing has been loaded or saved yet: no world exists
            return true;
        }
        if (!datapackLoaded) getLogger().warning("[World] the faultline datapack isn't loaded yet (restart once): custom biomes show as vanilla ones until then.");
        return false;
    }

    /** The main world: spawn at y 250 on island A and the 20000 x 20000 border. */
    private void afterWorlds() {
        World w = Bukkit.getWorlds().get(0);
        if (!(w.getGenerator() instanceof WorldGen)) {
            getLogger().warning("[World] the main world isn't using the Faultline generator (it was made before FaultlineWorld). Delete the world folder (server stopped) to make the new one.");
            return;
        }
        // The world spawn is put on island A ONCE (world PDC faultlineworld:spawn_set). It used to be reset on every start,
        // which undid /setworldspawn after each restart.
        org.bukkit.NamespacedKey setKey = new org.bukkit.NamespacedKey(this, "spawn_set");
        if (getConfig().getBoolean("set-spawn", true) && !w.getPersistentDataContainer().has(setKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
            Location s = new Location(w, Layout.SPAWN_X + 0.5, Layout.SPAWN_Y + 1, Layout.SPAWN_Z + 0.5);
            // only a brand-new world is moved: on a running world the spawn may have been set by staff on purpose
            if (w.getFullTime() < 1200 && w.getSpawnLocation().distanceSquared(s) > 4) w.setSpawnLocation(s);
            w.getPersistentDataContainer().set(setKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
        WorldBorder b = w.getWorldBorder();
        int size = getConfig().getInt("border-size", Layout.BORDER * 2);
        if (size == Layout.OLD_BORDER * 2 || size == 10000) size = Layout.BORDER * 2;   // the Outer Rim: an old config's 7500 / 10000 reads as 20000
        if (b.getSize() != size || b.getCenter().getX() != 0 || b.getCenter().getZ() != 0) { b.setCenter(0, 0); b.setSize(size); }
        getLogger().info("[World] Faultline overworld ready: spawn " + Layout.SPAWN_X + " " + (Layout.SPAWN_Y + 1) + " " + Layout.SPAWN_Z + ", border " + size + " x " + size);
    }

    // ================================================================== resetting the Farlands' chunks

    /** The Farlands start at 3280 from 0,0 with a +-70 wobble and a 100-block blend: a column 3420+ out is ALWAYS Farlands, so a
     *  region file wholly past this holds nothing else (spawn, the factions, the pyramids, the sky kingdom are all far inside). */
    static final int RESET_FROM = 3420;

    File resetMarker() { return new File(getDataFolder(), "reset-farlands.txt"); }

    /** Region files (r.X.Z.mca) wholly at least `from` blocks from 0,0. */
    static boolean farRegion(String name, int from) {
        String[] p = name.split("\\.");
        if (p.length != 4 || !p[0].equals("r") || !p[3].equals("mca")) return false;
        try {
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            long x0 = rx * 512L, x1 = x0 + 511, z0 = rz * 512L, z1 = z0 + 511;
            long dx = x0 > 0 ? x0 : x1 < 0 ? -x1 : 0, dz = z0 > 0 ? z0 : z1 < 0 ? -z1 : 0;
            return Math.hypot(dx, dz) >= from;
        } catch (NumberFormatException e) { return false; }
    }

    List<File> farFiles(File worldDir, int from) {
        List<File> out = new java.util.ArrayList<>();
        for (String sub : new String[]{"region", "entities", "poi"}) {
            File[] fs = new File(worldDir, sub).listFiles();
            if (fs != null) for (File f : fs) if (farRegion(f.getName(), from)) out.add(f);
        }
        return out;
    }

    /**
     * 2.0: /fworld resetfarlands confirm leaves a marker; on the next start (here, before the main world opens) every region file
     * wholly in the Farlands is MOVED to <world>/farlands_backup_<time>/ (region, entities, poi), so those chunks generate fresh
     * with the current generator and datapack. It waits while the datapack is being updated (a new chunk made then would save
     * the vanilla fallback biome again).
     */
    void resetFarlandsIfAsked() {
        File mk = resetMarker();
        if (!mk.isFile()) return;
        int from = RESET_FROM;
        try { from = Math.max(RESET_FROM, Integer.parseInt(Files.readString(mk.toPath()).trim())); } catch (Exception ignored) { }
        if (WorldGen.BiomeMap.lookup("faultline:missing_chunks") == null) {
            getLogger().warning("[World] the Farlands reset waits: this start loads the new datapack first. RESTART once more and the Farlands reset then.");
            return;
        }
        // SAFETY: only while the main world is closed (at startup). Never move a file a loaded world is using.
        if (Bukkit.getWorld(levelName()) != null) {
            getLogger().warning("[World] the Farlands reset waits: the world '" + levelName() + "' is already open. It runs on the next full restart.");
            return;
        }
        File worldDir = new File(Bukkit.getWorldContainer(), levelName());
        if (!new File(worldDir, "level.dat").isFile() || !new File(worldDir, "region").isDirectory()) {
            getLogger().warning("[World] the Farlands reset: no world at " + worldDir.getPath() + " (nothing moved).");
            if (!mk.delete()) mk.deleteOnExit();
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
        File backup = new File(worldDir, "farlands_backup_" + stamp);
        int moved = 0, failed = 0;
        for (File f : farFiles(worldDir, from)) {
            File to = new File(new File(backup, f.getParentFile().getName()), f.getName());
            try { to.getParentFile().mkdirs(); Files.move(f.toPath(), to.toPath()); moved++; }
            catch (Exception e) { failed++; getLogger().warning("[World] couldn't move " + f + ": " + e); }
        }
        if (!mk.delete()) mk.deleteOnExit();
        getLogger().warning("[World] FARLANDS RESET: moved " + moved + " region file(s) (" + from + "+ blocks out) to " + backup.getName()
                + (failed > 0 ? " (" + failed + " failed)" : "") + ". Those chunks generate fresh. Delete that folder once you're happy.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("resetfarlands")) {
            if (!sender.hasPermission("faultlineworld.admin") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) { sender.sendMessage("§cAdmins only."); return true; }
            int from = RESET_FROM;
            for (int i = 1; i + 1 < args.length; i++) if (args[i].equalsIgnoreCase("from")) try { from = Math.max(RESET_FROM, Integer.parseInt(args[i + 1])); } catch (NumberFormatException ignored) { }
            File worldDir = new File(Bukkit.getWorldContainer(), levelName());
            List<File> files = farFiles(worldDir, from);
            long bytes = 0; for (File f : files) bytes += f.length();
            boolean confirm = java.util.Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("confirm"));
            boolean cancel = java.util.Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("cancel"));
            if (cancel) { sender.sendMessage(resetMarker().delete() ? "§3[World] §7Farlands reset cancelled." : "§3[World] §7No reset was waiting."); return true; }
            if (!confirm) {
                int all = 0; File[] rf = new File(worldDir, "region").listFiles(); if (rf != null) for (File f : rf) if (f.getName().endsWith(".mca")) all++;
                sender.sendMessage("§3[World] §7" + files.size() + " of " + all + " region file(s) (" + (bytes / 1024 / 1024) + " MB) are wholly in the Farlands (" + from + "+ blocks from 0,0).");
                sender.sendMessage("§aKEPT: §7everything within ~" + from + " blocks of 0,0 (spawn, the factions, pyramids, the sky kingdom, your bases there), the Nether, the End and the Dark Ocean.");
                sender.sendMessage("§7On the next restart they're moved to a backup folder in the world, and those chunks generate fresh");
                sender.sendMessage("§7(the Broken Lands, rain, no snow). §cEverything built out there goes with them §7(it stays in the backup).");
                sender.sendMessage("§e/fworld resetfarlands confirm §7to do it  ·  §7from <blocks> §8(further out only)§7  ·  cancel");
                return true;
            }
            try { getDataFolder().mkdirs(); Files.writeString(resetMarker().toPath(), String.valueOf(from)); }
            catch (Exception e) { sender.sendMessage("§cCouldn't write the marker: " + e.getMessage()); return true; }
            sender.sendMessage("§3[World] §aThe Farlands (" + files.size() + " region files, " + from + "+ blocks out) reset on the next restart. §7(/fworld resetfarlands cancel to undo)");
            getLogger().warning("[World] " + sender.getName() + " asked for a Farlands reset (" + from + "+): it happens on the next restart");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("where")) {
            if (!(sender instanceof Player p)) return true;
            Terrain t = gen.terrain(p.getWorld());
            Terrain.Col c = t.column(p.getLocation().getBlockX(), p.getLocation().getBlockZ());
            B u = t.underground(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), c);
            sender.sendMessage("§3[World] §7biome §f" + (u != null ? u : c.biome) + "§7, surface y §f" + c.surface + (c.sea ? " §7(sea)" : "")
                    + (c.abyssTop != Integer.MIN_VALUE ? " §7abyss §f" + c.abyssBottom + ".." + c.abyssTop : ""));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("giant") && sender instanceof Player p) {
            if (life == null) { sender.sendMessage("§cNot running in this world."); return true; }
            life.giants.spawn(p.getLocation());
            sender.sendMessage("§3[World] §7A Zombie Giant. It needs 12 blocks of head room to move.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("rim") && sender instanceof Player p) {
            // /fworld rim list [kind] = the nearest ones' coordinates; /fworld rim build [kind] = redraw the nearest one into old chunks
            String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
            if ((sub.equals("list") || sub.equals("build")) && rim != null) {
                String kind = args.length > 2 ? args[2].toLowerCase(java.util.Locale.ROOT) : "village";
                if (!java.util.List.of("village", "lighthouse", "glitch", "citadel").contains(kind)) { p.sendMessage("§3[World] §7kinds: village, lighthouse, glitch, citadel"); return true; }
                java.util.List<int[]> near = rim.nearest(p.getWorld(), kind, p.getLocation().getX(), p.getLocation().getZ(), sub.equals("list") ? 10 : 1);
                if (near.isEmpty()) { p.sendMessage("§3[World] §7None found."); return true; }
                if (sub.equals("list")) {
                    p.sendMessage("§3[World] §7The nearest " + kind + (kind.equals("citadel") ? "" : "s") + " (x y z, blocks away):");
                    for (int[] c : near) p.sendMessage("§f  " + c[0] + " " + c[1] + " " + c[2] + " §8(" + (int) Math.hypot(c[0] - p.getLocation().getX(), c[2] - p.getLocation().getZ()) + ")");
                    p.sendMessage("§8A chunk generated before the update has no " + kind + ": stand near it and /fworld rim build " + kind + ".");
                } else rim.build(p, kind, near.get(0));
                return true;
            }
            String k = sub.isEmpty() ? "citadel" : sub;
            Terrain t = gen.terrain(p.getWorld());
            int[] best = null; double bd = Double.MAX_VALUE;
            if (k.equals("citadel")) best = new int[]{RimGen.CITADEL[0], RimGen.CITADEL_Y + 1, RimGen.CITADEL[1] - 60};
            else {
                int cell = k.equals("village") ? RimGen.VILLAGE_CELL : k.equals("lighthouse") ? RimGen.LIGHT_CELL : RimGen.GLITCH_CELL;
                int ci0 = Math.floorDiv(p.getLocation().getBlockX(), cell), cj0 = Math.floorDiv(p.getLocation().getBlockZ(), cell);
                for (int a = -30; a <= 30; a++) for (int b = -30; b <= 30; b++) {
                    int[] f = k.equals("village") ? RimGen.villageAt(t.seed, ci0 + a, cj0 + b) : k.equals("lighthouse") ? RimGen.lightAt(t.seed, ci0 + a, cj0 + b) : RimGen.glitchAt(t.seed, ci0 + a, cj0 + b);
                    if (f == null) continue;
                    double d = p.getLocation().distanceSquared(new org.bukkit.Location(p.getWorld(), f[0], p.getLocation().getY(), f[1]));
                    if (d < bd) { bd = d; best = new int[]{f[0], 0, f[1]}; }
                }
                if (best != null) best[1] = k.equals("lighthouse") ? RimGen.lamp(t, new int[]{best[0], best[2]})[1] + 1 : t.column(best[0], best[2]).surface + (k.equals("village") ? 1 : 3);
                if (best != null && k.equals("village")) best[2] -= 8;
            }
            if (best == null) { p.sendMessage("None found."); return true; }
            p.teleport(new org.bukkit.Location(p.getWorld(), best[0] + 0.5, best[1], best[2] + 0.5));
            p.sendMessage("Rim " + k + " at " + best[0] + " " + best[1] + " " + best[2]);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("tp") && sender instanceof Player p) {
            String what = args.length > 1 ? args[1].toLowerCase() : "";
            for (Layout.Site s : Layout.SITES) if (s.file().toLowerCase().contains(what) && !what.isEmpty()) {
                int[] b = Sites.box(s);
                int y = b != null ? b[4] + 3 : s.y() + 60;
                p.teleport(new Location(p.getWorld(), s.x() + 0.5, Math.min(Layout.MAX_Y - 1, y), s.z() + 0.5));
                sender.sendMessage("§3[World] §7" + s.file());
                return true;
            }
            for (Layout.Core k : Layout.CORES) if (what.equals(k.faction() + "core") || what.equals("core" + k.faction())) {
                int y = Structures.coreBase(gen.terrain(p.getWorld()), k);
                p.teleport(new Location(p.getWorld(), k.x() + 0.5, y + 1, k.z() + 18.5, 180, 0));
                sender.sendMessage("§3[World] §7the " + k.faction() + " shrine");
                return true;
            }
            for (int i = 0; i < Layout.PYRAMIDS.length; i++) if (what.equals("pyramid" + (i + 1))) {
                p.teleport(new Location(p.getWorld(), Layout.PYRAMIDS[i][0] + 0.5, 300, Layout.PYRAMIDS[i][1] - Structures.PH - 10));
                return true;
            }
            for (int i = 0; i < Layout.HOLES.length; i++) if (what.equals("hole" + (i + 1))) {
                p.teleport(new Location(p.getWorld(), Layout.HOLES[i][0] + 0.5, 201, Layout.HOLES[i][1] + 0.5));
                return true;
            }
            sender.sendMessage("§3[World] §7/fworld tp <site name | pyramid1-3 | mountaincore / junglecore / icecore / fiberscore / skycore>");
            return true;
        }
        sender.sendMessage("§3[World] §7/fworld where §8| §7/fworld tp <site | pyramid1-3 | <faction>core> §8| §7/fworld resetfarlands [confirm|cancel] [from <blocks>]");
        return true;
    }
}
