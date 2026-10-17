package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * FAULTLINE DEEP: the Dark Ocean (a world of water only, under the Black Market's abandoned kingdom). The Kraken's Mirror
 * takes you to the kingdom, Lucki rows you out and opens a whirlpool, and you drop into the Dark Ocean at 0, 300, 0.
 * DeepGenerator = the terrain, DeepMobs = its 20 creatures, Journey = the Mirror / Lucki / the whirlpool, Ocean = music,
 * events and dying down there. The bosses live in FaultlineBosses (Wild), Dark Loot in FaultlineItems.
 */
public final class FaultlineDeep extends JavaPlugin implements Listener {

    static final String DEFAULT_WORLD = "faultline_deep";
    static FaultlineDeep instance;
    World world;
    final DeepGenerator generator = new DeepGenerator();
    DeepMobs mobs;
    Journey journey;
    Ocean ocean;
    AcidRain acidRain;
    Den den;
    DenRep rep;
    Submersible subs;
    Migration migration;
    Bloom bloom;
    KrakenGrave grave;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        instance = this;
        world = loadWorld();
        mobs = new DeepMobs(this);
        journey = new Journey(this);
        ocean = new Ocean(this);
        acidRain = new AcidRain(this);
        den = new Den(this);
        rep = new DenRep(this);
        subs = new Submersible(this);
        migration = new Migration(this);
        bloom = new Bloom(this);
        if (getCommand("den") != null) getCommand("den").setExecutor(rep::command);
        getServer().getPluginManager().registerEvents(mobs, this);
        getServer().getPluginManager().registerEvents(journey, this);
        getServer().getPluginManager().registerEvents(ocean, this);
        getServer().getPluginManager().registerEvents(new Elevator(this), this);
        getServer().getPluginManager().registerEvents(subs, this);
        getServer().getPluginManager().registerEvents(new MarketGuard(this), this); // no flooding the Bubble, no robbing the market // the Black Market's lift down to the Pit
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, journey::spawnLucki);
    }

    @Override
    public void onDisable() {
        if (mobs != null) mobs.shutdown();
        if (journey != null) journey.shutdown();
        if (ocean != null) ocean.shutdown();
        if (acidRain != null) acidRain.shutdown();
        if (den != null) den.shutdown();
        if (rep != null) rep.save();
        if (subs != null) subs.shutdown();
        if (migration != null) migration.shutdown();
        if (bloom != null) bloom.stop();
    }

    String worldName() { return getConfig().getString("world", DEFAULT_WORLD); }

    /** The Dark Ocean world: made with our generator the first time, then loaded like any other world. */
    World loadWorld() {
        World w = Bukkit.getWorld(worldName());
        if (w == null) {
            WorldCreator wc = new WorldCreator(worldName()).environment(World.Environment.NORMAL).generator(generator);
            w = wc.createWorld();
        }
        if (w == null) { getLogger().severe("Couldn't load or create the Dark Ocean world " + worldName()); return null; }
        setup(w);
        return w;
    }

    void setup(World w) {
        try {
            w.setGameRule(GameRule.DO_INSOMNIA, false);      // no phantoms, ever
            w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
            w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
            w.setGameRule(GameRule.KEEP_INVENTORY, false);
        } catch (RuntimeException ignored) { } // (a rule a newer server renamed)
        w.setStorm(false);
        double r = getConfig().getDouble("border-radius", 2000);
        if (Math.abs(r - 1050) < 1e-9) r = 2000; // 1.7.0: the border is 2000 (an old config's 1050 reads as that): room for the Mermaid Den
        w.getWorldBorder().setCenter(0, 0);
        w.getWorldBorder().setSize(r * 2);
        w.setSpawnLocation(0, DeepGenerator.SEA, 0);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return generator; }

    /** Which part of the Dark Ocean a spot is in (the Sunlit Sea, the Crust's caves, the Abyss). */
    DeepGenerator.Zone zone(Location l) {
        return generator.zone(l.getWorld().getSeed(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    // =========================================================================================== FaultlineBosses' Deep Boss Rush
    /**
     * Where each stage of the Deep Boss Rush is fought (FaultlineBosses asks by reflection, once, and remembers it): the
     * open Sunlit Sea (Old Inkjaw), near its floor (the Grandmaw Clam), a tall pocket of the Abyss (the Lament, the Umbral
     * Leviathan), the Sulphur Sea's water (the Brine Scourge) and its Works (Grubnash), Coralia's throne, the Trench's Maw.
     */
    public static Location rushSpot(String kind) {
        FaultlineDeep pl = instance;
        if (pl == null || pl.world == null) return null;
        World w = pl.world;
        long seed = w.getSeed();
        DeepGenerator g = pl.generator;
        return switch (kind) {
            case "inkjaw" -> new Location(w, 200.5, Math.min(DeepGenerator.SEA - 30, g.seaFloor(seed, 200, 200) + 24), 200.5);
            case "grandmaw" -> new Location(w, 300.5, g.seaFloor(seed, 300, -300) + 4, -299.5);
            case "lament", "umbral" -> {
                int[] p = g.abyssPocket(seed, kind.equals("lament") ? 300 : -200, kind.equals("lament") ? 300 : -500);
                yield p == null ? null : new Location(w, p[0] + 0.5, p[1], p[2] + 0.5);
            }
            case "scourge" -> {
                for (int r = 0; r < 200; r += 8) for (int a = 0; a < 360; a += r == 0 ? 360 : 30) {
                    int x = Sulphur.SX + (int) Math.round(Math.cos(Math.toRadians(a)) * r), z = Sulphur.SZ + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                    if (g.sulphur(seed, x, z) > 0.8 && g.seaFloor(seed, x, z) < DeepGenerator.SEA - 22) yield new Location(w, x + 0.5, DeepGenerator.SEA - 10, z + 0.5);
                }
                yield null;
            }
            case "goblin" -> new Location(w, Sulphur.WORKS_X + 0.5, DeepGenerator.SEA + 3, Sulphur.WORKS_Z + 0.5);
            case "coralia" -> { int[] t = MermaidDen.throne(); yield new Location(w, t[0] + 0.5, t[1] + 3, t[2] + 6.5); }
            case "titan" -> new Location(w, Trench.LX + 0.5, Trench.BOTTOM + 4, Trench.LZ + 0.5);
            default -> null;
        };
    }

    /**
     * For FaultlineItems' hadal fishing (by reflection): which waters a fishing hook is in, or null outside the Dark Ocean.
     * "TRENCH" and "DEN" go by the column (a line dropped over the Trench reaches its fish), "SULPHUR" by the acid's weight,
     * "ABYSS" for a hook below y 35 (or in the Crust/Abyss), "BUBBLE" for the dry town and the Pit, else "SUNLIT".
     */
    public static String fishZone(Location l) {
        FaultlineDeep pl = instance;
        if (pl == null || pl.world == null || l == null || l.getWorld() != pl.world) return null;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        long seed = pl.world.getSeed();
        if (Market.dry(l.getX(), l.getY(), l.getZ())) return "BUBBLE";
        if (pl.generator.trenchColumn(seed, x, z)) return "TRENCH";
        if (MermaidDen.weight(x, z) > 0.9) return "DEN";
        if (pl.generator.sulphur(seed, x, z) > 0.35) return "SULPHUR";
        DeepGenerator.Zone zn = pl.generator.zone(seed, x, y, z);
        if (zn == DeepGenerator.Zone.TRENCH) return "TRENCH";
        if (zn == DeepGenerator.Zone.ABYSS || zn == DeepGenerator.Zone.CAVES || y < 35) return "ABYSS";
        return "SUNLIT";
    }

    /** Spawns one of the Dark Ocean's creatures for FaultlineBosses (Grubnash in the Deep Boss Rush): its UUID, or null. */
    public static java.util.UUID rushSpawn(String id, Location at) {
        FaultlineDeep pl = instance;
        DeepMobs.Kind k = DeepMobs.KINDS.get(id);
        if (pl == null || pl.mobs == null || k == null) return null;
        DeepMobs.Beast b = pl.mobs.spawn(k, at);
        return b == null ? null : b.body.getUniqueId();
    }

    void console(String cmd) {
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }
        catch (RuntimeException e) { getLogger().warning("Command failed: " + cmd + " (" + e.getMessage() + ")"); }
    }

    // =========================================================================================== spawn eggs
    @EventHandler
    public void onEgg(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = mobs.eggOf(e.getItem());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        DeepMobs.Kind k = DeepMobs.KINDS.get(id);
        if (k == null) return;
        Location at = e.getClickedBlock() != null ? e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5)
                : p.getEyeLocation().add(p.getLocation().getDirection().multiply(3));
        if (mobs.spawn(k, at) != null && p.getGameMode() != org.bukkit.GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
    }

    // =========================================================================================== /deep
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        Player self = sender instanceof Player p ? p : null;
        if (!sender.hasPermission("faultlinedeep.admin")) { sender.sendMessage(ChatColor.RED + "Admins only."); return true; }
        switch (sub) {
            case "set" -> {
                if (self == null || args.length < 2 || !List.of("kingdom", "lucki", "whirlpool").contains(args[1].toLowerCase())) {
                    sender.sendMessage(ChatColor.YELLOW + "/deep set <kingdom|lucki|whirlpool>   (where you stand; Lucki's spot must be water)"); return true;
                }
                journey.setPlace(args[1].toLowerCase(), self.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Set the " + args[1].toLowerCase() + " spot here.");
            }
            case "tp" -> {
                Player who = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (who == null || world == null) { sender.sendMessage(ChatColor.RED + "Who? (or the world isn't loaded)"); return true; }
                who.teleport(journey.entry());
                sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + " into the Dark Ocean.");
            }
            case "town" -> { // the bubble: to the path just south of the build plot
                Player who = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (who == null || world == null) { sender.sendMessage(ChatColor.RED + "Who? (or the world isn't loaded)"); return true; }
                int[] at = Town.plotSpawn();
                who.teleport(new org.bukkit.Location(world, at[0] + 0.5, at[1], at[2] + 0.5, 180, 0));
                sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + " to the Bubble. The 50x50 plot is " + Town.PLOT_X0 + ", " + Town.PLOT_Z0
                        + " to " + Town.PLOT_X1 + ", " + Town.PLOT_Z1 + ": the Black Market (its lift goes down to the Pit).");
            }
            case "sulphur" -> { // the Sulphur Sea: onto the Sulphur Works' deck
                Player who = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (who == null || world == null) { sender.sendMessage(ChatColor.RED + "Who? (or the world isn't loaded)"); return true; }
                who.teleport(new org.bukkit.Location(world, Sulphur.WORKS_X + 0.5, DeepGenerator.SEA + 3, Sulphur.WORKS_Z + 0.5));
                sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + " to the Sulphur Sea (the Sulphur Works, " + Sulphur.WORKS_X + ", " + Sulphur.WORKS_Z
                        + "). The region is centred on " + Sulphur.SX + ", " + Sulphur.SZ + ". The Brine Scourge is summoned here.");
            }
            case "trench" -> { // the Trench (1.8.0): its mouth, or down in the Maw
                boolean maw = args.length > 1 && args[1].equalsIgnoreCase("maw");
                Player who = args.length > (maw ? 2 : 1) ? Bukkit.getPlayerExact(args[maw ? 2 : 1]) : self;
                if (who == null || world == null) { sender.sendMessage(ChatColor.RED + "Who? (or the world isn't loaded)"); return true; }
                int x = maw ? Trench.LX : (int) Trench.PX[20], z = maw ? Trench.LZ + 24 : (int) Trench.PZ[20];
                double y = maw ? Trench.BOTTOM + 4 : generator.seaFloor(world.getSeed(), x, z) + 6;
                who.teleport(new org.bukkit.Location(world, x + 0.5, y, z + 0.5, maw ? 180 : 0, maw ? 0 : 60));
                sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + (maw ? " into the Maw (" + Trench.LX + ", " + Trench.LZ + "), where the Abyssal Titan sleeps."
                        : " to the Trench's mouth (" + x + ", " + z + "). It winds " + (int) Trench.LENGTH + " blocks south to the Maw at " + Trench.LX + ", " + Trench.LZ + "."));
            }
            case "spawn" -> {
                DeepMobs.Kind k = args.length > 1 ? DeepMobs.KINDS.get(args[1].toLowerCase()) : null;
                if (k == null || self == null) { sender.sendMessage(ChatColor.YELLOW + "/deep spawn <" + String.join("|", DeepMobs.KINDS.keySet()) + ">"); return true; }
                mobs.spawn(k, self.getLocation().add(self.getLocation().getDirection().multiply(3)));
            }
            case "egg" -> {
                DeepMobs.Kind k = args.length > 1 ? DeepMobs.KINDS.get(args[1].toLowerCase()) : null;
                if (k == null) { sender.sendMessage(ChatColor.YELLOW + "/deep egg <mob> [amount] [player]"); return true; }
                int n = 1; Player to = self;
                for (int i = 2; i < args.length; i++) {
                    Player o = Bukkit.getPlayerExact(args[i]);
                    if (o != null) to = o; else try { n = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                if (to == null) { sender.sendMessage("Who?"); return true; }
                ItemStack egg = mobs.egg(k); egg.setAmount(n);
                Player dest = to;
                dest.getInventory().addItem(egg).values().forEach(it -> dest.getWorld().dropItemNaturally(dest.getLocation(), it));
            }
            case "event" -> {
                String which = args.length > 1 ? args[1].toLowerCase() : "";
                if (which.equals("stop")) { ocean.stop(false); sender.sendMessage(ChatColor.GREEN + "Stopped."); return true; }
                if (!which.equals("pressure") && !which.equals("undertow")) { sender.sendMessage(ChatColor.YELLOW + "/deep event <pressure|undertow|stop>"); return true; }
                sender.sendMessage(ocean.start(which) ? ChatColor.GREEN + "Started: " + which : ChatColor.RED + "The Dark Ocean world isn't loaded.");
            }
            case "acidrain" -> {
                String what = args.length > 1 ? args[1].toLowerCase() : "start";
                switch (what) {
                    case "stop" -> { acidRain.stop(false); sender.sendMessage(ChatColor.GREEN + "Stopped the Acid Rain."); }
                    case "goblin" -> {
                        if (!acidRain.active) acidRain.start();
                        Player near = self != null && acidRain.caught(self) ? self : acidRain.caughtPlayers().stream().findFirst().orElse(self);
                        sender.sendMessage(near != null && acidRain.spawnGoblin(near) ? ChatColor.GREEN + "Grubnash is coming." : ChatColor.RED + "No one in the Sulphur Sea to send him at (or he's already out).");
                    }
                    default -> sender.sendMessage(acidRain.start() ? ChatColor.GREEN + "The Acid Rain begins over the Sulphur Sea." : ChatColor.RED + "It's already raining (or the world isn't loaded).");
                }
            }
            case "den" -> { // the Mermaid Den
                String what = args.length > 1 ? args[1].toLowerCase() : "info";
                switch (what) {
                    case "tp" -> {
                        Player who = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : self;
                        if (who == null || world == null) { sender.sendMessage(ChatColor.RED + "Who? (or the world isn't loaded)"); return true; }
                        who.teleport(new org.bukkit.Location(world, MermaidDen.MX + 0.5, MermaidDen.PLAZA + 4, MermaidDen.MZ + 40.5, 180, 10));
                        sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + " to the Mermaid Den (" + MermaidDen.MX + ", " + MermaidDen.MZ + ").");
                    }
                    case "raid" -> {
                        if (self == null) { sender.sendMessage("Players only."); return true; }
                        sender.sendMessage(den.startWar(self) ? ChatColor.GREEN + "The Den goes to war." : ChatColor.RED + "It's already at war (or the world isn't loaded).");
                    }
                    case "stop" -> { den.endWar(false); sender.sendMessage(ChatColor.GREEN + "Stopped the Den's war."); }
                    case "reset" -> { den.reset(); sender.sendMessage(ChatColor.GREEN + "All ten mermaids are home again."); }
                    case "rise" -> { den.rise("Called by an admin."); sender.sendMessage(ChatColor.GREEN + "Coralia rises over her throne."); }
                    case "where" -> { for (String line : den.where()) sender.sendMessage(ChatColor.GRAY + line); }
                    default -> sender.sendMessage(ChatColor.LIGHT_PURPLE + den.info() + ChatColor.YELLOW + "\n/deep den [info|where|tp [player]|raid|stop|reset|rise]");
                }
            }
            case "bloom" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) { bloom.stop(); sender.sendMessage(ChatColor.GREEN + "The bloom fades."); return true; }
                sender.sendMessage(bloom.start() ? ChatColor.GREEN + "The Bioluminescent Bloom begins." : ChatColor.RED + "It's already blooming (or the world isn't loaded).");
            }
            case "migration" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) { migration.end(false, null); sender.sendMessage(ChatColor.GREEN + "The pod swims off."); return true; }
                Player near = self != null && world != null && self.getWorld().equals(world) ? self : world == null ? null : world.getPlayers().stream().findFirst().orElse(null);
                sender.sendMessage(near != null && migration.start(near) ? ChatColor.GREEN + "A pod of great whales sets off." : ChatColor.RED + "No one in the Dark Ocean to send them past (or a pod is already out).");
            }
            case "sub" -> { // a Submersible
                Player who = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : self;
                if (who == null) { sender.sendMessage(ChatColor.RED + "Who?"); return true; }
                subs.give(who);
                sender.sendMessage(ChatColor.GREEN + "Gave " + who.getName() + " a Submersible.");
            }
            case "lucki" -> { journey.removeLucki(); journey.spawnLucki(); sender.sendMessage(ChatColor.GREEN + "Lucki is back at his spot (if it's set and loaded)."); }
            case "info", "" -> {
                sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Dark Ocean " + ChatColor.GRAY + "(" + worldName() + (world == null ? ", NOT LOADED" : "") + ")");
                sender.sendMessage(ChatColor.GRAY + "Kingdom: " + spot(journey.place("kingdom")) + "  Lucki: " + spot(journey.place("lucki")) + "  Whirlpool: " + spot(journey.poolSpot()));
                sender.sendMessage(ChatColor.GRAY + "Creatures out: " + mobs.live.size() + "   Event: " + (ocean.event == null ? "none" : ocean.event) + "   Acid Rain: " + (acidRain.active ? "falling" : "no"));
                if (self != null && world != null && self.getWorld().equals(world)) sender.sendMessage(ChatColor.GRAY + "You're in: " + zone(self.getLocation()));
                sender.sendMessage(ChatColor.YELLOW + "/deep set <kingdom|lucki|whirlpool>, tp [player], town [player], sulphur [player], trench [maw] [player], spawn <mob>, egg <mob> [n] [player], event <pressure|undertow|stop>, acidrain [start|stop|goblin], den [info|tp|raid|stop|reset|rise], lucki");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/deep [info|set|tp|town|sulphur|trench|spawn|egg|event|acidrain|den|lucki]");
        }
        return true;
    }

    static String spot(Location l) { return l == null ? ChatColor.RED + "not set" + ChatColor.GRAY : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("info", "set", "tp", "town", "sulphur", "trench", "spawn", "egg", "event", "acidrain", "den", "bloom", "migration", "sub", "lucki"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("set")) out.addAll(List.of("kingdom", "lucki", "whirlpool"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("egg"))) out.addAll(DeepMobs.KINDS.keySet());
        else if (args.length == 2 && args[0].equalsIgnoreCase("event")) out.addAll(List.of("pressure", "undertow", "stop"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("den")) out.addAll(List.of("info", "where", "tp", "raid", "stop", "reset", "rise"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("acidrain")) out.addAll(List.of("start", "stop", "goblin"));
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
