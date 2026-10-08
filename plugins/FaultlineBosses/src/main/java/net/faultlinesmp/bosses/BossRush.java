package net.faultlinesmp.bosses;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE BOSS RUSH (/bossrush): every boss, back to back. The leader starts it; everyone in survival within 10 blocks joins.
 * Before each boss the team is teleported to that boss's home biome (the Demon Eye on the plains, Frostbeard in the snow,
 * the Dune Devourer in the desert, the Kraken over a deep ocean, Diamond Jacob on a mountain peak, Grimtusk in a Nether
 * bastion...), found once and remembered in bossrush.yml (an admin can set one with /bossrush setarena <boss>).
 *
 * Bosses drop no loot in a rush. Dying puts you out (you keep your things); the run fails when the whole team is out or
 * a boss leaves. Finish it for the rewards, the Boss Rush Champion achievement, and a time on the leaderboard.
 */
final class BossRush implements Listener, CommandExecutor, TabCompleter {

    /** One stage: the boss, where it lives, and a name for titles. */
    record Stage(String kind, String name, World.Environment env, List<Biome> biomes) {}

    static final List<Stage> STAGES = List.of(
            // the raid bosses (FaultlineRaids) open the rush
            new Stage("rotbeard", "Captain Rotbeard", World.Environment.NORMAL, List.of(Biome.BEACH, Biome.STONY_SHORE, Biome.SNOWY_BEACH)),
            new Stage("bulwark", "The Bulwark", World.Environment.NORMAL, List.of(Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS)),
            new Stage("great_hog", "The Great Hog", World.Environment.NETHER, List.of(Biome.CRIMSON_FOREST)),
            new Stage("queen_spider", "The Queen Spider", World.Environment.NORMAL, List.of(Biome.DARK_FOREST, Biome.PALE_GARDEN)),
            new Stage("demoneye", "The Demon Eye", World.Environment.NORMAL, List.of(Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.MEADOW)),
            new Stage("frostbeard", "Frostbeard", World.Environment.NORMAL, List.of(Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.GROVE)),
            new Stage("dune", "The Dune Devourer", World.Environment.NORMAL, List.of(Biome.DESERT)),
            new Stage("frostmaw", "The Frostmaw", World.Environment.NORMAL, List.of(Biome.ICE_SPIKES, Biome.SNOWY_PLAINS, Biome.FROZEN_PEAKS)),
            new Stage("don", "Don Lorenzo", World.Environment.NORMAL, List.of(Biome.PLAINS, Biome.MEADOW, Biome.SAVANNA)),
            new Stage("kraken", "The Kraken", World.Environment.NORMAL, List.of(Biome.DEEP_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_LUKEWARM_OCEAN)),
            new Stage("jacob", "Diamond Jacob", World.Environment.NORMAL, List.of(Biome.JAGGED_PEAKS, Biome.STONY_PEAKS, Biome.FROZEN_PEAKS)),
            new Stage("rocco", "Rocco Vendetta", World.Environment.NORMAL, List.of(Biome.PLAINS, Biome.SAVANNA, Biome.MEADOW)),
            new Stage("grimtusk", "Grimtusk, the Piglin Warlord", World.Environment.NETHER, List.of(Biome.NETHER_WASTES, Biome.CRIMSON_FOREST)),
            // the wild bosses (1.4)
            new Stage("golem", "The Stone Golem", World.Environment.NORMAL, List.of(Biome.LUSH_CAVES, Biome.JUNGLE, Biome.FOREST)),
            new Stage("sandworm", "The Sandworm King", World.Environment.NORMAL, List.of(Biome.DESERT, Biome.BADLANDS)),
            new Stage("leviathan", "The Leviathan", World.Environment.NORMAL, List.of(Biome.DEEP_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_LUKEWARM_OCEAN)),
            new Stage("lich", "The Lich", World.Environment.NORMAL, List.of(Biome.DEEP_DARK, Biome.DARK_FOREST)),
            new Stage("frostwyrm", "The Frost Wyrm", World.Environment.NORMAL, List.of(Biome.FROZEN_PEAKS, Biome.SNOWY_SLOPES, Biome.JAGGED_PEAKS)),
            // the finale: the Lost Explorer in his arena below the bedrock (solo: the leader fights him, the rest watch from the path)
            new Stage("explorer", "The Lost Explorer", World.Environment.THE_END, List.of()));

    /** The list 1.3.0 wrote into server configs: still that list = use the full default (raid bosses + the Explorer). */
    static final List<String> OLD_DEFAULT = List.of("demoneye", "frostbeard", "dune", "frostmaw", "don", "kraken", "jacob", "rocco", "grimtusk");
    /** 1.3.1's list (without the Queen Spider): also upgraded to the full default. */
    static final List<String> OLD_DEFAULT_2 = List.of("rotbeard", "bulwark", "great_hog", "demoneye", "frostbeard", "dune", "frostmaw", "don", "kraken", "jacob", "rocco", "grimtusk", "explorer");
    /** 1.3.2-1.3.4's list (before the wild bosses): upgraded too. */
    static final List<String> OLD_DEFAULT_3 = List.of("rotbeard", "bulwark", "great_hog", "queen_spider", "demoneye", "frostbeard", "dune", "frostmaw", "don", "kraken", "jacob", "rocco", "grimtusk", "explorer");

    final FaultlineBosses pl;
    private final File file;
    private final YamlConfiguration data;
    Run run;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    final class Run {
        final List<UUID> team = new ArrayList<>();
        final Set<UUID> out = new HashSet<>();
        final Map<UUID, Location> home = new HashMap<>();
        final List<Stage> stages;
        int stage = -1, t, state; // 0 = travelling / countdown, 1 = fighting, 2 = break, 3 = finished
        boolean defeated;
        long startedAt;
        final BossBar bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        Location arena;
        Run(List<Stage> stages) { this.stages = stages; }

        List<Player> alive() {
            List<Player> a = new ArrayList<>();
            for (UUID id : team) { Player p = Bukkit.getPlayer(id); if (p != null && !out.contains(id) && p.isOnline()) a.add(p); }
            return a;
        }

        Stage current() { return stage >= 0 && stage < stages.size() ? stages.get(stage) : null; }
    }

    final org.bukkit.NamespacedKey sigilKey;

    BossRush(FaultlineBosses pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "bossrush.yml");
        data = YamlConfiguration.loadConfiguration(file);
        sigilKey = new org.bukkit.NamespacedKey(pl, "rush_sigil");
        org.bukkit.inventory.ShapedRecipe rec = new org.bukkit.inventory.ShapedRecipe(new org.bukkit.NamespacedKey(pl, "boss_rush_sigil"), sigil());
        rec.shape("NNN", "NSN", "NNN");
        rec.setIngredient('N', Material.NETHERITE_BLOCK);
        rec.setIngredient('S', Material.NETHER_STAR);
        addRecipeSafely(rec);
    }

    // ===================================================================== the Boss Rush Sigil (starts a rush; never used up)

    ItemStack sigil() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush Sigil");
        m.setLore(List.of(ChatColor.GRAY + "Right-click: start a Boss Rush.",
                ChatColor.GRAY + "Everyone in survival within 10 blocks joins.",
                ChatColor.GRAY + "Every boss, back to back.",
                ChatColor.GOLD + "Never used up.",
                ChatColor.DARK_GRAY + "8 Netherite Blocks around a Nether Star."));
        m.setItemModel(new org.bukkit.NamespacedKey("faultline", "boss_rush_sigil"));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(sigilKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isSigil(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(sigilKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    boolean hasSigil(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isSigil(it)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSigil(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !isSigil(e.getItem())) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (run != null) { e.getPlayer().sendMessage(ChatColor.RED + "A Boss Rush is already running."); return; }
        start(e.getPlayer());
    }

    /** It's a Nether Star underneath: never a crafting ingredient (no beacons out of a Sigil). */
    @EventHandler
    public void onSigilCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) if (isSigil(i)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler
    public void onJoinRecipe(org.bukkit.event.player.PlayerJoinEvent e) { e.getPlayer().discoverRecipe(new org.bukkit.NamespacedKey(pl, "boss_rush_sigil")); }

    double c(String path, double def) { return pl.getConfig().getDouble("boss-rush." + path, def); }

    void save() {
        try { pl.getDataFolder().mkdirs(); data.save(file); } catch (Exception e) { pl.getLogger().warning("Couldn't save bossrush.yml: " + e.getMessage()); }
    }

    List<Stage> stages() {
        List<String> order = pl.getConfig().getStringList("boss-rush.bosses");
        if (order.isEmpty() || order.equals(OLD_DEFAULT) || order.equals(OLD_DEFAULT_2) || order.equals(OLD_DEFAULT_3)) return STAGES;
        List<Stage> out = new ArrayList<>();
        for (String k : order) STAGES.stream().filter(s -> s.kind().equalsIgnoreCase(k.trim())).findFirst().ifPresent(out::add);
        return out.isEmpty() ? STAGES : out;
    }

    // ===================================================================== hooks from the bosses

    /** True while a rush is fighting this kind of boss (its loot is skipped). */
    boolean suppress(String kind) {
        return run != null && run.state == 1 && run.current() != null && run.current().kind().equals(kind);
    }

    void defeated(String kind) {
        if (run != null && run.state == 1 && run.current() != null && run.current().kind().equals(kind)) run.defeated = true;
    }

    // ===================================================================== arenas

    World world(World.Environment env) {
        String name = pl.getConfig().getString("boss-rush." + (env == World.Environment.NETHER ? "nether-world" : "world"), "");
        if (name != null && !name.isBlank() && Bukkit.getWorld(name) != null) return Bukkit.getWorld(name);
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == env) return w;
        return null;
    }

    /** Where this boss is fought: remembered, or found once (the nearest fitting biome / a bastion) and remembered. */
    /** If none of a stage's own biomes turns up, these are tried next (rarer biomes like badlands can be far away). */
    static final Map<String, List<Biome>> FALLBACK = Map.of(
            "rotbeard", List.of(Biome.MANGROVE_SWAMP, Biome.SWAMP, Biome.RIVER, Biome.PLAINS),
            "bulwark", List.of(Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.DESERT, Biome.PLAINS),
            "queen_spider", List.of(Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.TAIGA, Biome.FOREST, Biome.PLAINS),
            "frostbeard", List.of(Biome.ICE_SPIKES, Biome.FROZEN_PEAKS, Biome.SNOWY_SLOPES),
            "frostmaw", List.of(Biome.SNOWY_TAIGA, Biome.GROVE, Biome.SNOWY_SLOPES),
            "dune", List.of(Biome.BADLANDS, Biome.SAVANNA),
            "jacob", List.of(Biome.SNOWY_SLOPES, Biome.WINDSWEPT_HILLS, Biome.MEADOW),
            "kraken", List.of(Biome.OCEAN, Biome.COLD_OCEAN, Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN),
            "leviathan", List.of(Biome.OCEAN, Biome.COLD_OCEAN, Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN),
            "great_hog", List.of(Biome.NETHER_WASTES, Biome.WARPED_FOREST));

    /** The team's spot, for a boss whose biome couldn't be found anywhere: they fight it right where they are. */
    Location here;

    Location arena(Stage s) {
        if (s.kind().equals("explorer")) { // on the white path, just short of his arena
            World v = pl.below.voidWorld();
            return v == null ? null : new Location(v, 0.5, Below.PATH_Y + 1, Below.ARENA_Z - 24.5, 0, 0);
        }
        String key = "arenas." + s.kind();
        if (data.isConfigurationSection(key)) {
            World w = Bukkit.getWorld(data.getString(key + ".world", ""));
            if (w != null) return new Location(w, data.getDouble(key + ".x"), data.getDouble(key + ".y"), data.getDouble(key + ".z"));
        }
        World w = world(s.env());
        if (w == null) return null;
        // BUG FIX: only the world spawn was searched, only 4000 blocks out, and a spot in water was thrown away, so bosses
        // with rare or watery biomes (Rotbeard's beach, the Bulwark's badlands) were skipped. Now: from the spawn AND from
        // where the team stands, 6400 blocks out, then fallback biomes, and a watery spot walks out to dry land.
        List<Location> origins = new ArrayList<>(List.of(w.getSpawnLocation()));
        if (here != null && here.getWorld().equals(w)) origins.add(0, here);
        Location spot = null;
        for (List<Biome> biomes : List.of(s.biomes(), FALLBACK.getOrDefault(s.kind(), List.of()))) {
            if (spot != null) break;
            for (Location origin : origins) {
                Location found = null;
                try {
                    if (s.kind().equals("grimtusk")) {
                        if (biomes != s.biomes()) break;
                        var r = w.locateNearestStructure(origin, org.bukkit.generator.structure.Structure.BASTION_REMNANT, (int) Math.max(150, c("search-chunks", 150)), false);
                        if (r != null) found = r.getLocation();
                    } else if (!biomes.isEmpty()) {
                        var r = w.locateNearestBiome(origin, (int) Math.max(6400, c("search-radius", 6400)), 32, 64, biomes.toArray(new Biome[0]));
                        if (r != null) found = r.getLocation();
                    }
                } catch (RuntimeException e) {
                    pl.getLogger().warning("[Boss Rush] couldn't search for " + s.name() + "'s biome: " + e);
                }
                if (found == null) continue;
                spot = surface(found, s);
                if (spot == null) spot = dryNear(found, s);
                if (spot != null) break;
            }
        }
        if (spot == null) {
            pl.getLogger().warning("[Boss Rush] no " + s.name() + " arena found (biomes " + s.biomes() + "); the team fights it where they are."
                    + " Set one with /bossrush setarena " + s.kind());
            return null;
        }
        data.set(key + ".world", spot.getWorld().getName());
        data.set(key + ".x", spot.getX()); data.set(key + ".y", spot.getY()); data.set(key + ".z", spot.getZ());
        save();
        return spot;
    }

    /** Around a watery or unusable spot, the nearest place to stand (out to 64 blocks). */
    Location dryNear(Location l, Stage s) {
        for (int rad = 4; rad <= 64; rad += 4)
            for (int a = 0; a < 360; a += 15) {
                Location at = l.clone().add(Math.cos(Math.toRadians(a)) * rad, 0, Math.sin(Math.toRadians(a)) * rad);
                Location sp = surface(at, s);
                if (sp != null) return sp;
            }
        return null;
    }

    /** A place to stand: the top of the ground (on the water for the Kraken, a floor inside the bastion for Grimtusk). */
    Location surface(Location l, Stage s) {
        World w = l.getWorld();
        w.getChunkAt(l.getBlockX() >> 4, l.getBlockZ() >> 4);
        if (w.getEnvironment() == World.Environment.NETHER) { // a floor with headroom, under the roof
            int x = l.getBlockX(), z = l.getBlockZ();
            for (int r = 0; r < 24; r += 2) for (int dx = -r; dx <= r; dx += Math.max(1, r)) for (int dz = -r; dz <= r; dz += Math.max(1, r))
                for (int y = Math.min(110, l.getBlockY() + 20); y > 32; y--) {
                    Block b = w.getBlockAt(x + dx, y, z + dz);
                    if (b.getType().isSolid() && b.getType() != Material.LAVA && b.getRelative(0, 1, 0).getType().isAir()
                            && b.getRelative(0, 2, 0).getType().isAir() && b.getRelative(0, 3, 0).getType().isAir())
                        return b.getLocation().add(0.5, 1, 0.5);
                }
            return null;
        }
        Block top = w.getHighestBlockAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (s.kind().equals("kraken") || s.kind().equals("leviathan")) return top.isLiquid() ? top.getLocation().add(0.5, 1, 0.5) : null;
        if (top.isLiquid()) return null;
        return top.getLocation().add(0.5, 1, 0.5);
    }

    // ===================================================================== the run

    void start(Player leader) {
        if (run != null) { leader.sendMessage(ChatColor.RED + "A Boss Rush is already running."); return; }
        String busy = pl.bossOutName();
        if (busy != null) { leader.sendMessage(ChatColor.RED + busy + " is out somewhere: wait until that fight is over."); return; }
        long now = System.currentTimeMillis();
        List<Player> team = new ArrayList<>();
        for (Player p : leader.getWorld().getPlayers())
            if (survival(p) && !p.isDead() && p.getLocation().distanceSquared(leader.getLocation()) < 10 * 10) team.add(p);
        if (!team.contains(leader)) { leader.sendMessage(ChatColor.RED + "Be in survival to start a Boss Rush."); return; }
        for (Player p : team) {
            Long until = cooldown.get(p.getUniqueId());
            if (until != null && until > now && !leader.hasPermission("bosses.admin")) {
                leader.sendMessage(ChatColor.RED + p.getName() + " ran a Boss Rush recently (" + ((until - now) / 60000 + 1) + " more minutes).");
                return;
            }
        }
        Run r = new Run(stages());
        for (Player p : team) { r.team.add(p.getUniqueId()); r.home.put(p.getUniqueId(), p.getLocation()); }
        r.startedAt = now;
        run = r;
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "BOSS RUSH! " + ChatColor.YELLOW + names(team) + ChatColor.YELLOW
                + " take on " + r.stages.size() + " bosses, back to back.");
        next(r);
    }

    private static String names(List<Player> ps) {
        List<String> n = new ArrayList<>();
        for (Player p : ps) n.add(p.getName());
        return String.join(", ", n);
    }

    /** Teleports the team to the next boss's biome and starts the countdown. */
    void next(Run r) {
        r.stage++;
        r.defeated = false;
        if (r.stage >= r.stages.size()) { win(r); return; }
        Stage s = r.current();
        List<Player> team = r.alive();
        here = team.isEmpty() ? null : team.get(0).getLocation();
        Location a = arena(s);
        if (a == null && !s.kind().equals("explorer") && here != null && world(s.env()) != null) {
            // no arena anywhere: fight it right here (in the right dimension: the Overworld spawn area / the Nether spawn)
            World w = world(s.env());
            Location base = here.getWorld().equals(w) ? here : w.getSpawnLocation();
            a = surface(base, s);
            if (a == null) a = dryNear(base, s);
            if (a != null) msgTeam(r, ChatColor.GRAY + "(No " + s.name() + " biome nearby, so you fight it right here.)");
        }
        if (a == null) {
            msgTeam(r, ChatColor.GRAY + "(No " + s.name() + " arena could be found in this world, so that boss is skipped.)");
            next(r);
            return;
        }
        r.arena = a;
        r.state = 0; r.t = 0;
        int i = 0;
        boolean below = s.kind().equals("explorer");
        for (Player p : r.alive()) {
            double ang = i++ * Math.PI * 2 / Math.max(1, r.alive().size());
            Location to = a.clone().add(Math.cos(ang) * 2, 0, Math.sin(ang) * 2);
            Location safe = below ? a.clone().add((i - 1) % 3 - 1, 0, -((i - 1) / 3)) : surface(to, s);
            if (below && !pl.below.returns.containsKey(p.getUniqueId())) { pl.below.returns.put(p.getUniqueId(), r.home.get(p.getUniqueId())); pl.below.dirty = true; }
            p.teleport(safe != null ? safe : a);
            p.setFallDistance(0);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
            r.bar.addPlayer(p);
        }
        msgTeam(r, ChatColor.RED + "" + ChatColor.BOLD + "Boss " + (r.stage + 1) + "/" + r.stages.size() + ": " + ChatColor.GOLD + s.name());
    }

    void tick() {
        Run r = run;
        if (r == null) return;
        r.t++;
        List<Player> alive = r.alive();
        if (alive.isEmpty() && r.state != 3) { fail(r, "the whole team is out"); return; }
        Stage s = r.current();
        long secs = (System.currentTimeMillis() - r.startedAt) / 1000;
        r.bar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush " + ChatColor.GRAY + (r.stage + 1) + "/" + r.stages.size()
                + (s != null ? "  " + ChatColor.GOLD + s.name() : "") + ChatColor.GRAY + "  " + time(secs * 1000));
        r.bar.setProgress(Math.max(0, Math.min(1, (r.stage + (r.state == 2 ? 1 : 0)) / (double) r.stages.size())));
        switch (r.state) {
            case 0 -> { // countdown, then the boss appears
                int left = 5 - r.t / 20;
                if (r.t % 20 == 0 && left > 0) for (Player p : alive) {
                    p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + s.name()), legacy(ChatColor.GRAY + "in " + left + "..."),
                            Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1100), java.time.Duration.ofMillis(100))));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                }
                if (r.t >= 100) {
                    Player lead = alive.get(0);
                    if (!pl.rushSummon(s.kind(), r.arena, lead)) {
                        msgTeam(r, ChatColor.GRAY + "(" + s.name() + " couldn't appear here, so that boss is skipped.)");
                        next(r);
                        return;
                    }
                    r.state = 1; r.t = 0;
                }
            }
            case 1 -> { // the fight
                if (r.defeated) {
                    r.state = 2; r.t = 0;
                    msgTeam(r, ChatColor.GREEN + "" + ChatColor.BOLD + s.name() + " down! " + ChatColor.GRAY + "Next boss in " + (int) c("break-seconds", 15) + " seconds.");
                    for (Player p : alive) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                } else if (r.t > 100 && !pl.rushAlive(s.kind())) fail(r, "beaten by " + s.name());
            }
            case 2 -> { if (r.t >= c("break-seconds", 15) * 20) next(r); }
            default -> { }
        }
    }

    void win(Run r) {
        r.state = 3;
        long ms = System.currentTimeMillis() - r.startedAt;
        List<Player> alive = r.alive();
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "BOSS RUSH COMPLETE! " + ChatColor.YELLOW + names(alive) + ChatColor.YELLOW
                + " beat every boss in " + ChatColor.WHITE + time(ms) + ChatColor.YELLOW + "!");
        for (Player p : alive) {
            p.showTitle(Title.title(legacy(ChatColor.RED + "" + ChatColor.BOLD + "BOSS RUSH CHAMPION"), legacy(ChatColor.GRAY + time(ms)),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(4000), java.time.Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            reward(p);
            pl.console("index stat " + p.getName() + " boss_rush 1", p);
            record(p, ms, alive.size());
        }
        finish(r, true);
    }

    void reward(Player p) {
        pl.console("givemythicbag " + (int) c("rewards.mythic-bags", 32) + " " + p.getName(), p);
        List<String> items = pl.getConfig().getStringList("boss-rush.rewards.items");
        if (items.isEmpty()) items = List.of("NETHERITE_INGOT:4", "DIAMOND_BLOCK:4", "TOTEM_OF_UNDYING:1");
        for (String spec : items) {
            String[] kv = spec.split(":");
            Material m = Material.matchMaterial(kv[0].trim());
            if (m == null) continue;
            int n = 1;
            try { n = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1; } catch (NumberFormatException ignored) { }
            pl.give(p, new ItemStack(m, Math.max(1, Math.min(m.getMaxStackSize(), n))));
        }
        int xp = (int) c("rewards.xp", 5000);
        p.giveExp(xp);
    }

    void fail(Run r, String why) {
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush failed " + ChatColor.GRAY + "(" + why + ") at boss "
                + (r.stage + 1) + "/" + r.stages.size() + ".");
        Stage s = r.current();
        if (s != null && r.state == 1) pl.rushKill(s.kind());
        finish(r, false);
    }

    /** Ends the run: everyone still in it goes back to where they started. */
    void finish(Run r, boolean won) {
        r.bar.removeAll();
        boolean voidUsed = false;
        for (UUID id : r.team) if (pl.below.returns.remove(id) != null) voidUsed = true; // they go home from here, not through the rift
        if (voidUsed) pl.below.dirty = true;
        long until = System.currentTimeMillis() + (long) (c("cooldown-minutes", 60) * 60000);
        for (UUID id : r.team) cooldown.put(id, until);
        for (UUID id : r.team) {
            Player p = Bukkit.getPlayer(id);
            Location h = r.home.get(id);
            if (p != null && !r.out.contains(id) && h != null) {
                Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && !p.isDead()) { p.teleport(h); p.setFallDistance(0); } }, won ? 100L : 60L);
                p.sendMessage(ChatColor.GRAY + "Sending you back to where you started in a few seconds...");
            }
        }
        if (run == r) run = null;
    }

    private void msgTeam(Run r, String text) {
        for (UUID id : r.team) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(text); }
    }

    static String time(long ms) {
        long s = ms / 1000;
        return (s / 3600 > 0 ? s / 3600 + ":" : "") + String.format(s / 3600 > 0 ? "%02d:%02d" : "%d:%02d", (s / 60) % 60, s % 60);
    }

    // ---------- the leaderboard (best time per player) ----------
    void record(Player p, long ms, int teamSize) {
        String k = "times." + p.getUniqueId();
        long best = data.getLong(k + ".ms", Long.MAX_VALUE);
        if (ms < best) {
            data.set(k + ".ms", ms); data.set(k + ".name", p.getName()); data.set(k + ".team", teamSize);
            p.sendMessage(ChatColor.GOLD + "New personal best: " + ChatColor.WHITE + time(ms));
        }
        data.set(k + ".runs", data.getInt(k + ".runs") + 1);
        save();
    }

    void leaderboard(CommandSender to) {
        var sec = data.getConfigurationSection("times");
        to.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush " + ChatColor.GRAY + "- fastest times");
        if (sec == null || sec.getKeys(false).isEmpty()) { to.sendMessage(ChatColor.GRAY + "  Nobody has finished one yet."); return; }
        List<String> ids = new ArrayList<>(sec.getKeys(false));
        ids.sort(Comparator.comparingLong(id -> sec.getLong(id + ".ms")));
        for (int i = 0; i < Math.min(10, ids.size()); i++) {
            String id = ids.get(i);
            to.sendMessage(ChatColor.GOLD + "  " + (i + 1) + ". " + ChatColor.WHITE + sec.getString(id + ".name") + ChatColor.GRAY + "  "
                    + time(sec.getLong(id + ".ms")) + " (team of " + sec.getInt(id + ".team") + ")");
        }
    }

    // ===================================================================== events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Run r = run;
        if (r == null || !r.team.contains(e.getEntity().getUniqueId()) || r.out.contains(e.getEntity().getUniqueId())) return;
        if (pl.getConfig().getBoolean("boss-rush.keep-inventory", true)) {
            e.setKeepInventory(true); e.getDrops().clear();
            e.setKeepLevel(true); e.setDroppedExp(0);
        }
        r.out.add(e.getEntity().getUniqueId());
        r.bar.removePlayer(e.getEntity());
        e.getEntity().sendMessage(ChatColor.RED + "You're out of the Boss Rush." + ChatColor.GRAY + " (You kept your things.)");
        msgTeam(r, ChatColor.GRAY + e.getEntity().getName() + " is out of the Boss Rush.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Run r = run;
        if (r != null && r.team.contains(e.getPlayer().getUniqueId())) { r.out.add(e.getPlayer().getUniqueId()); r.bar.removePlayer(e.getPlayer()); }
    }

    // ---------- bosses from other plugins: the raid bosses (FaultlineRaids, "zraid spawnboss") and the Queen Spider
    // (FaultlineItems, "queenspider spawn"), spawned by console command and followed by their tag ----------
    static final Map<String, String> RAID_TAGS = Map.of("rotbeard", "faultline_rotbeard", "bulwark", "faultline_bulwark", "great_hog", "faultline_great_hog",
            "queen_spider", "faultline_queen_spider");
    UUID raidBoss;

    static boolean external(String kind) { return RAID_TAGS.containsKey(kind); }

    boolean spawnRaidBoss(String kind, Location at) {
        boolean queen = kind.equals("queen_spider");
        org.bukkit.plugin.Plugin other = Bukkit.getPluginManager().getPlugin(queen ? "FaultlineItems" : "FaultlineRaids");
        if (other == null || !other.isEnabled()) return false;
        raidBoss = null;
        String where = at.getWorld().getName() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), queen ? "queenspider spawn " + where : "zraid spawnboss " + kind + " " + where);
        String tag = RAID_TAGS.get(kind);
        double best = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e : at.getWorld().getNearbyEntities(at, 6, 6, 6)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity) || !e.getScoreboardTags().contains(tag)) continue;
            double d = e.getLocation().distanceSquared(at);
            if (d < best) { best = d; raidBoss = e.getUniqueId(); }
        }
        return raidBoss != null;
    }

    boolean raidBossAlive() {
        if (raidBoss == null) return false;
        org.bukkit.entity.Entity e = Bukkit.getEntity(raidBoss);
        return e instanceof org.bukkit.entity.LivingEntity le && le.isValid() && !le.isDead();
    }

    void removeRaidBoss() {
        org.bukkit.entity.Entity e = raidBoss == null ? null : Bukkit.getEntity(raidBoss);
        if (e != null) e.remove();
        raidBoss = null;
    }

    /** A raid boss killed in the rush: the stage is won, and (after FaultlineRaids added its loot) there's none. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidBossDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (raidBoss == null || !e.getEntity().getUniqueId().equals(raidBoss)) return;
        raidBoss = null;
        Run r = run;
        if (r == null || r.state != 1 || r.current() == null || !RAID_TAGS.containsKey(r.current().kind())) return;
        r.defeated = true;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    void shutdown() {
        if (run != null) { run.bar.removeAll(); run = null; }
    }

    // ===================================================================== /bossrush

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        boolean admin = sender.hasPermission("bosses.admin");
        switch (sub) {
            case "start" -> {
                if (!(sender instanceof Player p)) return true;
                if (!admin && !hasSigil(p)) { p.sendMessage(ChatColor.RED + "You need a Boss Rush Sigil " + ChatColor.GRAY + "(8 Netherite Blocks around a Nether Star). Right-click it to start."); return true; }
                start(p);
            }
            case "sigil" -> { // admin: /bossrush sigil [amount] [player]
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                int amount = 1; Player target = sender instanceof Player sp ? sp : null;
                for (int i = 1; i < args.length; i++) {
                    Player o = Bukkit.getPlayerExact(args[i]);
                    if (o != null) target = o; else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                if (target == null) { sender.sendMessage("Who should get it?"); return true; }
                for (int i = 0; i < amount; i++) pl.give(target, sigil());
                sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + " Boss Rush Sigil" + (amount == 1 ? "" : "s") + ".");
            }
            case "top", "leaderboard" -> leaderboard(sender);
            case "stop" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                if (run == null) sender.sendMessage(ChatColor.GRAY + "No Boss Rush is running.");
                else fail(run, "stopped by an admin");
            }
            case "skip" -> {
                if (!admin || run == null) { sender.sendMessage(ChatColor.GRAY + "Nothing to skip."); return true; }
                Stage s = run.current();
                if (s != null && run.state == 1) pl.rushKill(s.kind());
                run.state = 2; run.t = (int) (c("break-seconds", 15) * 20);
                sender.sendMessage(ChatColor.GREEN + "Skipped to the next boss.");
            }
            case "setarena" -> {
                if (!admin || !(sender instanceof Player p) || args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/bossrush setarena <boss>  (where you stand)"); return true; }
                Stage s = STAGES.stream().filter(x -> x.kind().equalsIgnoreCase(args[1])).findFirst().orElse(null);
                if (s == null) { sender.sendMessage(ChatColor.RED + "Bosses: " + kinds()); return true; }
                String key = "arenas." + s.kind();
                data.set(key + ".world", p.getWorld().getName());
                data.set(key + ".x", p.getLocation().getX()); data.set(key + ".y", p.getLocation().getY()); data.set(key + ".z", p.getLocation().getZ());
                save();
                sender.sendMessage(ChatColor.GREEN + s.name() + " is fought here now.");
            }
            case "arenas" -> {
                if (!admin) return true;
                for (Stage s : stages()) {
                    String key = "arenas." + s.kind();
                    sender.sendMessage(ChatColor.GOLD + s.name() + ": " + ChatColor.WHITE + (data.isConfigurationSection(key)
                            ? data.getString(key + ".world") + " " + (int) data.getDouble(key + ".x") + ", " + (int) data.getDouble(key + ".y") + ", " + (int) data.getDouble(key + ".z")
                            : ChatColor.GRAY + "not found yet (searched the first time it's needed)"));
                }
            }
            case "clearcooldown" -> { if (admin) { cooldown.clear(); sender.sendMessage(ChatColor.GREEN + "Boss Rush cooldowns cleared."); } }
            default -> {
                sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush" + ChatColor.GRAY + ": every boss, back to back ("
                        + stages().size() + "). You're teleported to each boss's home biome.");
                sender.sendMessage(ChatColor.YELLOW + "Right-click a Boss Rush Sigil " + ChatColor.GRAY + "(8 Netherite Blocks around a Nether Star, never used up)"
                        + " - you and everyone within 10 blocks (in survival)");
                sender.sendMessage(ChatColor.YELLOW + "/bossrush top " + ChatColor.GRAY + "- the fastest times");
                sender.sendMessage(ChatColor.GRAY + "Bosses drop no loot in a rush. Die and you're out (you keep your things). Finish it for big rewards.");
                if (run != null) sender.sendMessage(ChatColor.GOLD + "A rush is running: boss " + (run.stage + 1) + "/" + run.stages.size() + ".");
                if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Admin: /bossrush start | stop | skip | setarena <boss> | arenas | clearcooldown | sigil [amount] [player]");
            }
        }
        return true;
    }

    String kinds() {
        List<String> k = new ArrayList<>();
        for (Stage s : STAGES) k.add(s.kind());
        return String.join(", ", k);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> o = new ArrayList<>();
        if (args.length == 1) { o.addAll(List.of("start", "top")); if (sender.hasPermission("bosses.admin")) o.addAll(List.of("stop", "skip", "setarena", "arenas", "clearcooldown", "sigil")); }
        else if (args.length == 2 && args[0].equalsIgnoreCase("setarena")) for (Stage s : STAGES) o.add(s.kind());
        String last = args[args.length - 1].toLowerCase();
        o.removeIf(x -> !x.startsWith(last));
        return o;
    }
}
