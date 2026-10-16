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
 * takes you to the kingdom, Lucki rows you out and opens a whirlpool, and you drop into the Dark Ocean at 0, 100, 0.
 * DeepGenerator = the terrain, DeepMobs = its 20 creatures, Journey = the Mirror / Lucki / the whirlpool, Ocean = music,
 * events and dying down there. The bosses live in FaultlineBosses (Wild), Dark Loot in FaultlineItems.
 */
public final class FaultlineDeep extends JavaPlugin implements Listener {

    static final String DEFAULT_WORLD = "faultline_deep";
    World world;
    final DeepGenerator generator = new DeepGenerator();
    DeepMobs mobs;
    Journey journey;
    Ocean ocean;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        world = loadWorld();
        mobs = new DeepMobs(this);
        journey = new Journey(this);
        ocean = new Ocean(this);
        getServer().getPluginManager().registerEvents(mobs, this);
        getServer().getPluginManager().registerEvents(journey, this);
        getServer().getPluginManager().registerEvents(ocean, this);
        getServer().getPluginManager().registerEvents(new Elevator(this), this);
        getServer().getPluginManager().registerEvents(new MarketGuard(this), this); // no flooding the Bubble, no robbing the market // the Black Market's lift down to the Pit
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, journey::spawnLucki);
    }

    @Override
    public void onDisable() {
        if (mobs != null) mobs.shutdown();
        if (journey != null) journey.shutdown();
        if (ocean != null) ocean.shutdown();
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
        double r = getConfig().getDouble("border-radius", 1050);
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
            case "lucki" -> { journey.removeLucki(); journey.spawnLucki(); sender.sendMessage(ChatColor.GREEN + "Lucki is back at his spot (if it's set and loaded)."); }
            case "info", "" -> {
                sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Dark Ocean " + ChatColor.GRAY + "(" + worldName() + (world == null ? ", NOT LOADED" : "") + ")");
                sender.sendMessage(ChatColor.GRAY + "Kingdom: " + spot(journey.place("kingdom")) + "  Lucki: " + spot(journey.place("lucki")) + "  Whirlpool: " + spot(journey.poolSpot()));
                sender.sendMessage(ChatColor.GRAY + "Creatures out: " + mobs.live.size() + "   Event: " + (ocean.event == null ? "none" : ocean.event));
                if (self != null && world != null && self.getWorld().equals(world)) sender.sendMessage(ChatColor.GRAY + "You're in: " + zone(self.getLocation()));
                sender.sendMessage(ChatColor.YELLOW + "/deep set <kingdom|lucki|whirlpool>, tp [player], town [player], sulphur [player], spawn <mob>, egg <mob> [n] [player], event <pressure|undertow|stop>, lucki");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/deep [info|set|tp|town|sulphur|spawn|egg|event|lucki]");
        }
        return true;
    }

    static String spot(Location l) { return l == null ? ChatColor.RED + "not set" + ChatColor.GRAY : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("info", "set", "tp", "town", "sulphur", "spawn", "egg", "event", "lucki"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("set")) out.addAll(List.of("kingdom", "lucki", "whirlpool"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("egg"))) out.addAll(DeepMobs.KINDS.keySet());
        else if (args.length == 2 && args[0].equalsIgnoreCase("event")) out.addAll(List.of("pressure", "undertow", "stop"));
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
