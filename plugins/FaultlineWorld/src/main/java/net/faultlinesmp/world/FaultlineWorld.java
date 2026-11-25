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
        if (setup()) return;
        Bukkit.getScheduler().runTask(this, this::afterWorlds);
        life = new Life(this);
        Bukkit.getPluginManager().registerEvents(life, this);
    }

    @Override
    public void onDisable() { if (life != null) life.stop(); }

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

    /** The main world: spawn at y 250 on island A and the 7500 x 7500 border. */
    private void afterWorlds() {
        World w = Bukkit.getWorlds().get(0);
        if (!(w.getGenerator() instanceof WorldGen)) {
            getLogger().warning("[World] the main world isn't using the Faultline generator (it was made before FaultlineWorld). Delete the world folder (server stopped) to make the new one.");
            return;
        }
        if (getConfig().getBoolean("set-spawn", true)) {
            Location s = new Location(w, Layout.SPAWN_X + 0.5, Layout.SPAWN_Y + 1, Layout.SPAWN_Z + 0.5);
            if (w.getSpawnLocation().distanceSquared(s) > 4) w.setSpawnLocation(s);
        }
        WorldBorder b = w.getWorldBorder();
        int size = getConfig().getInt("border-size", Layout.BORDER * 2);
        if (b.getSize() != size || b.getCenter().getX() != 0 || b.getCenter().getZ() != 0) { b.setCenter(0, 0); b.setSize(size); }
        getLogger().info("[World] Faultline overworld ready: spawn " + Layout.SPAWN_X + " " + (Layout.SPAWN_Y + 1) + " " + Layout.SPAWN_Z + ", border " + size + " x " + size);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
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
        sender.sendMessage("§3[World] §7/fworld where §8| §7/fworld tp <site | pyramid1-3 | <faction>core>");
        return true;
    }
}
