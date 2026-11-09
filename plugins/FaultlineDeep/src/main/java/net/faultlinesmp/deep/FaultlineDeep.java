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
 * FAULTLINE DEEP: the Dark Ocean (a world of water only). The way down:
 * (1.16.0) every hour a whirlpool opens in an Overworld ocean under a beam of light: its eye drops you into the Dark Ocean at 0, 300, 0.
 * DeepGenerator = the terrain, DeepMobs = its 20 creatures, Journey = the Mirror / the hourly whirlpool, Ocean = music,
 * events and dying down there. The bosses live in FaultlineBosses (Wild), Dark Loot in FaultlineItems.
 */
public final class FaultlineDeep extends JavaPlugin implements Listener {

    static final String DEFAULT_WORLD = "faultline_deep";
    static FaultlineDeep instance;
    World world;
    final DeepGenerator generator = new DeepGenerator();
    CaveCaches caves;
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
    Outer outer;
    Tides tides;
    Volcano volcano;
    Habitat habitat;
    AbyssMap chart;
    Glimmertide glimmer;
    DenFestival festival;   // 1.15.0
    CoralFarm coralFarm;    // 1.15.0
    Lighthouse lighthouse;  // 1.15.0

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
        outer = new Outer(this);
        tides = new Tides(this);
        volcano = new Volcano(this);
        getServer().getPluginManager().registerEvents(volcano, this);
        habitat = new Habitat(this);
        getServer().getPluginManager().registerEvents(habitat, this);
        getServer().getPluginManager().registerEvents(habitat.upgrades, this);
        chart = new AbyssMap(this);
        glimmer = new Glimmertide(this);
        getServer().getPluginManager().registerEvents(glimmer, this);
        if (getCommand("glimmer") != null) {
            getCommand("glimmer").setExecutor((sender, cmd, label, args) -> glimmer.command(sender, args));
            getCommand("glimmer").setTabCompleter((sender, cmd, label, args) -> args.length == 1 ? List.of("top", "sell") : List.of());
        }
        festival = new DenFestival(this); // 1.15.0: the Den's monthly festival
        coralFarm = new CoralFarm(this);  // 1.15.0: Air Coral blooms, fragments, selling
        getServer().getPluginManager().registerEvents(coralFarm, this);
        lighthouse = new Lighthouse(this); // 1.15.0: lighthouses light the dark for good
        getServer().getPluginManager().registerEvents(lighthouse, this);
        getServer().getPluginManager().registerEvents(chart, this);
        if (getCommand("chart") != null) getCommand("chart").setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player p) sender.sendMessage(chart.status(p)); else sender.sendMessage("Players only.");
            return true;
        });
        getServer().getPluginManager().registerEvents(outer, this);
        caves = new CaveCaches(this);
        getServer().getPluginManager().registerEvents(caves, this);
        if (getCommand("den") != null) getCommand("den").setExecutor(rep::command);
        if (getCommand("whirlpool") != null) {
            getCommand("whirlpool").setExecutor((sender, cmd, label, args) -> journey.command(sender, args));
            getCommand("whirlpool").setTabCompleter((sender, cmd, label, args) -> journey.tab(args));
        }
        if (getCommand("dome") != null) {
            getCommand("dome").setExecutor((sender, cmd, label, args) -> habitat.command(sender, args));
            getCommand("dome").setTabCompleter((sender, cmd, label, args) -> {
                if (args.length == 1) return List.of("list", "combine", "yes", "no", "upgrades", "trust", "untrust", "home", "color").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
                if (args.length == 2 && args[0].equalsIgnoreCase("color")) return DomeUpgrades.COLOURS.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
                return null; // (player names)
            });
        }
        getServer().getPluginManager().registerEvents(mobs, this);
        getServer().getPluginManager().registerEvents(journey, this);
        getServer().getPluginManager().registerEvents(ocean, this);
        getServer().getPluginManager().registerEvents(new Elevator(this), this);
        getServer().getPluginManager().registerEvents(subs, this);
        getServer().getPluginManager().registerEvents(new MarketGuard(this), this); // no flooding the Bubble, no robbing the market // the Black Market's lift down to the Pit
        getServer().getPluginManager().registerEvents(this, this);
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
        if (outer != null) outer.shutdown();
        if (tides != null) tides.stop();
        if (volcano != null) volcano.shutdown();
        if (chart != null) chart.shutdown();
        if (glimmer != null) glimmer.shutdown();
        if (festival != null) festival.shutdown();
        if (lighthouse != null) lighthouse.save();
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
        double r = getConfig().getDouble("border-radius", 3000);
        if (Math.abs(r - 1050) < 1e-9 || Math.abs(r - 2000) < 1e-9) r = 3000; // 1.9.0: the border is 3000 (an old config's 1050 / 2000 reads as that): room for the outer ring
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
                if (self == null || args.length < 2 || !args[1].equalsIgnoreCase("kingdom")) {
                    sender.sendMessage(ChatColor.YELLOW + "/deep set kingdom   (where you stand: where the Mirror brings players back up from the Dark Ocean)"); return true;
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
                    case "festival" -> sender.sendMessage(festival.admin(args.length > 2 ? args[2].toLowerCase() : "")); // 1.15.0
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
            case "eclipse", "redtide" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) { tides.stop(); sender.sendMessage(ChatColor.GREEN + "Stopped."); return true; }
                sender.sendMessage(tides.start(sub) ? ChatColor.GREEN + "Started: " + sub : ChatColor.RED + "Another sea event is on (or the world isn't loaded).");
            }
            case "frenzy" -> {
                if (self == null || !self.getWorld().equals(world)) { sender.sendMessage(ChatColor.RED + "Stand in the Dark Ocean."); return true; }
                tides.frenzy(self.getLocation().add(self.getLocation().getDirection().multiply(4)));
            }
            case "volcano" -> {
                String what = args.length > 1 ? args[1].toLowerCase() : "where";
                switch (what) {
                    case "start" -> {
                        boolean ok;
                        if (args.length > 3) { try { ok = volcano.startAt(Integer.parseInt(args[2]), Integer.parseInt(args[3])); } catch (NumberFormatException ex) { ok = false; } }
                        else ok = self != null && self.getWorld().equals(world) && volcano.start(self);
                        sender.sendMessage(ok ? ChatColor.GREEN + "A volcano rises. " + volcano.where() : ChatColor.RED + "No room for one near you (or one is already out). /deep volcano start [x z]");
                    }
                    case "here" -> sender.sendMessage(self != null && self.getWorld().equals(world) && volcano.startAt(self.getLocation().getBlockX(), self.getLocation().getBlockZ() + 30)
                            ? ChatColor.GREEN + "A volcano rises 30 blocks south of you." : ChatColor.RED + "One is already out (or you're not in the Dark Ocean).");
                    case "stop", "cool" -> { volcano.stop(); sender.sendMessage(ChatColor.GREEN + volcano.where()); }
                    case "heal" -> { if (volcano.state.equals("rising") || volcano.state.equals("erupting")) volcano.cool(); volcano.heal(); sender.sendMessage(ChatColor.GREEN + "The sea took it back."); }
                    default -> sender.sendMessage(ChatColor.GOLD + volcano.where() + ChatColor.YELLOW + "\n/deep volcano [where|start [x z]|here|cool|heal]");
                }
            }
            case "domes" -> { for (String line : habitat.list()) sender.sendMessage(ChatColor.GRAY + line); }
            case "season", "glimmertide" -> glimmer.admin(sender, args.length > 1 ? args[1].toLowerCase() : "");
            case "give" -> { // /deep give <coral|dome_small|dome_large|vault_key|fossil> [amount] [player]
                String what = args.length > 1 ? args[1].toLowerCase() : "";
                int n = 1; Player to = self;
                for (int i = 2; i < args.length; i++) { Player o = Bukkit.getPlayerExact(args[i]); if (o != null) to = o; else try { n = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { } }
                ItemStack it = switch (what) {
                    case "coral", "air_coral" -> habitat.coral(n);
                    case "dome_small" -> habitat.kit(6);
                    case "dome_large" -> habitat.kit(10);
                    case "vault_key" -> outer.vaultKey(n);
                    case "cache_key", "key" -> caves.key(n);
                    case "fossil" -> outer.fossil(n);
                    case "chart", "map" -> chart.item();
                    case "glimmer", "pearl", "glimmer_pearl" -> glimmer.pearl(n);
                    case "fragment", "coral_fragment" -> coralFarm.fragment(n);
                    case "lighthouse", "lamp" -> lighthouse.lamp(n);
                    default -> null;
                };
                if (it == null || to == null) { sender.sendMessage(ChatColor.YELLOW + "/deep give <coral|dome_small|dome_large|vault_key|cache_key|fossil|chart|glimmer|fragment|lighthouse> [amount] [player]"); return true; }
                Player dest = to;
                dest.getInventory().addItem(it).values().forEach(x -> dest.getWorld().dropItemNaturally(dest.getLocation(), x));
                sender.sendMessage(ChatColor.GREEN + "Gave " + dest.getName() + " " + what + ".");
            }
            case "region" -> { // /deep region <frozen|city|glass|bones> [player]
                String r = args.length > 1 ? args[1].toLowerCase() : "";
                Player who = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : self;
                int[] c = switch (r) {
                    case "frozen" -> new int[]{FrozenShelf.CX, FrozenShelf.CZ + 60};
                    case "city" -> new int[]{DrownedCity.CX, DrownedCity.CZ + 40};
                    case "glass" -> new int[]{GlassReef.CX, GlassReef.CZ + GlassReef.PLAZA_R + 6};
                    case "bones" -> new int[]{BoneFields.CX, BoneFields.CZ};
                    default -> null;
                };
                if (c == null || who == null || world == null) { sender.sendMessage(ChatColor.YELLOW + "/deep region <frozen|city|glass|bones> [player]"); return true; }
                int y = Math.min(DeepGenerator.SEA - 8, generator.seaFloor(world.getSeed(), c[0], c[1]) + 12);
                who.teleport(new Location(world, c[0] + 0.5, y, c[1] + 0.5));
                sender.sendMessage(ChatColor.GREEN + "Sent " + who.getName() + " to " + Regions.title(r) + " (" + c[0] + ", " + c[1] + ").");
            }
            case "whirlpool" -> journey.command(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "info", "" -> {
                sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Dark Ocean " + ChatColor.GRAY + "(" + worldName() + (world == null ? ", NOT LOADED" : "") + ")");
                sender.sendMessage(ChatColor.GRAY + "Mirror's way up: " + spot(journey.place("kingdom")) + "  Whirlpool: " + (journey.pool != null ? spot(journey.pool.v.center) : "next in " + journey.minutesToNext() + " min"));
                sender.sendMessage(ChatColor.GRAY + "Creatures out: " + mobs.live.size() + "   Event: " + (ocean.event == null ? "none" : ocean.event) + "   Acid Rain: " + (acidRain.active ? "falling" : "no"));
                if (self != null && world != null && self.getWorld().equals(world)) sender.sendMessage(ChatColor.GRAY + "You're in: " + zone(self.getLocation()));
                sender.sendMessage(ChatColor.YELLOW + "/deep set kingdom, whirlpool [here|random|at|stop|next], tp [player], town [player], sulphur [player], trench [maw] [player], spawn <mob>, egg <mob> [n] [player], event <pressure|undertow|stop>, acidrain [start|stop|goblin], den [info|tp|raid|stop|reset|rise], eclipse, redtide, frenzy, volcano, region <name>, give <item>, domes");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/deep [info|set|whirlpool|tp|town|sulphur|trench|spawn|egg|event|acidrain|den]");
        }
        return true;
    }

    static String spot(Location l) { return l == null ? ChatColor.RED + "not set" + ChatColor.GRAY : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("info", "set", "tp", "town", "sulphur", "trench", "spawn", "egg", "event", "acidrain", "den", "bloom", "migration", "sub", "whirlpool", "eclipse", "redtide", "frenzy", "volcano", "domes", "give", "region", "season"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("season")) out.addAll(List.of("start", "stop", "auto", "shoal"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) out.addAll(List.of("coral", "dome_small", "dome_large", "vault_key", "cache_key", "fossil", "chart", "glimmer", "fragment", "lighthouse"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("region")) out.addAll(List.of("frozen", "city", "glass", "bones"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("volcano")) out.addAll(List.of("where", "start", "here", "cool", "heal"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("set")) out.addAll(List.of("kingdom"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("egg"))) out.addAll(DeepMobs.KINDS.keySet());
        else if (args.length == 2 && args[0].equalsIgnoreCase("event")) out.addAll(List.of("pressure", "undertow", "stop"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("den")) out.addAll(List.of("info", "where", "tp", "raid", "stop", "reset", "rise"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("acidrain")) out.addAll(List.of("start", "stop", "goblin"));
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
