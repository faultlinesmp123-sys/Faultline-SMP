package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HALCYON at runtime (Deep 1.17.0): the city is ACTIVE while a player is within `halcyon.active-radius` of it (its people
 * and subs only exist then), its music plays inside the dome (Ocean.music), and it protects itself:
 *  - nobody (without faultlinedeep.city) breaks, places, empties or fills buckets, opens containers or uses beds there:
 *    doors, trapdoors, gates, buttons, crafting tables and the like still work; explosions, pistons, fire, flowing water from
 *    outside, trampled crops, falling leaves: none of it touches the city
 *  - MINING THE DOME: the block isn't broken, and if a guard (or any citizen within 12) can see you, you're WANTED
 *  - THE WANTED LEVEL (1-3 stars, `wanted()`): hitting a citizen 1, a guard / killing a citizen / mining in sight 2, sinking a
 *    patrol sub 3. Guards hunt you in the city, the patrol subs' radar finds you out at sea (SubFleet), stars 2+ = glowing.
 *    It lasts `halcyon.wanted-minutes` (3, x level) after your last crime; dying clears it
 *  - no monsters spawn under the dome
 */
final class HalcyonCity implements Listener {
    final FaultlineDeep pl;
    Citizens citizens;
    SubFleet fleet;
    Exhibits exhibits;
    boolean active;
    long lastNear;
    int ticks;
    final Map<UUID, Wanted> wanted = new HashMap<>();
    final Set<UUID> inside = new HashSet<>();
    final Set<UUID> visited = new HashSet<>();
    final File file;

    static final class Wanted {
        int level;
        long until;
        String why;
        BossBar bar;
    }

    HalcyonCity(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "halcyon.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("visited")) try { visited.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        citizens = new Citizens(pl, this);
        fleet = new SubFleet(pl, this);
        exhibits = new Exhibits(pl, this);
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getPluginManager().registerEvents(citizens, pl);
        Bukkit.getPluginManager().registerEvents(fleet, pl);
        Bukkit.getPluginManager().registerEvents(exhibits, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("halcyon." + path, def); }

    boolean enabled() { return pl.getConfig().getBoolean("halcyon.enabled", true) && pl.world != null; }

    Location center() { return new Location(pl.world, Halcyon.HX + 0.5, Halcyon.G, Halcyon.HZ + 0.5); }

    static boolean in(Location l) { return Halcyon.inside(l.getX(), l.getY(), l.getZ()); }

    boolean inCity(Player p) { return pl.world != null && p.getWorld().equals(pl.world) && in(p.getLocation()); }

    boolean ours(Block b) { return pl.world != null && b.getWorld().equals(pl.world) && Halcyon.grounds(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5); }

    static boolean exempt(Player p) { return p.hasPermission("faultlinedeep.city") || p.getGameMode() == GameMode.CREATIVE && p.hasPermission("faultlinedeep.admin"); }

    void tick() {
        ticks++;
        if (!enabled()) { if (active) setActive(false); return; }
        if (ticks % 20 == 0) {
            double r = cfg("active-radius", 260);
            boolean near = false;
            for (Player p : pl.world.getPlayers()) {
                Location l = p.getLocation();
                double dx = l.getX() - Halcyon.HX, dz = l.getZ() - Halcyon.HZ;
                if (dx * dx + dz * dz < r * r && p.getGameMode() != GameMode.SPECTATOR) near = true;
                boolean now = in(l);
                if (now && inside.add(p.getUniqueId())) entered(p);
                else if (!now) inside.remove(p.getUniqueId());
            }
            if (near) lastNear = System.currentTimeMillis();
            boolean want = near || System.currentTimeMillis() - lastNear < 60_000;
            if (want != active) setActive(want);
            wantedTick();
        }
        if (active) {
            citizens.tick();
            fleet.tick();
            exhibits.tick();
        }
    }

    void setActive(boolean on) {
        active = on;
        if (on) { citizens.spawnAll(); fleet.spawnAll(); }
        else { citizens.removeAll(); fleet.removeAll(); exhibits.removeAll(); }
    }

    void entered(Player p) {
        p.showTitle(Title.title(Component.text("Halcyon", NamedTextColor.AQUA), Component.text("The last city under the sea.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        if (visited.add(p.getUniqueId())) {
            p.sendMessage(ChatColor.AQUA + "Welcome to Halcyon. " + ChatColor.GRAY + "Trade with its people, visit the Bio-Center and the Aquarium, buy a Submersible at the dock. "
                    + ChatColor.RED + "Don't hurt anyone: the guards are watching, and the patrol subs have radar.");
            pl.console("index stat " + p.getName() + " halcyon 1");
            for (String id : new String[]{"halcyon_citizen", "halcyon_guard", "halcyon_dockmaster"}) pl.console("index discover " + p.getName() + " " + id);
            save();
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("visited", visited.stream().map(UUID::toString).toList());
        try { y.save(file); } catch (Exception e) { pl.getLogger().warning("[Halcyon] couldn't save halcyon.yml: " + e); }
    }

    // =========================================================================================== the wanted level
    int wanted(Player p) {
        Wanted w = wanted.get(p.getUniqueId());
        return w == null || System.currentTimeMillis() > w.until ? 0 : w.level;
    }

    /** A crime: the level only goes up; the clock restarts. */
    void crime(Player p, int level, String why) {
        if (p == null || exempt(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        Wanted w = wanted.computeIfAbsent(p.getUniqueId(), k -> new Wanted());
        int before = System.currentTimeMillis() > w.until ? 0 : w.level;
        w.level = Math.min(3, Math.max(before, level));
        w.until = System.currentTimeMillis() + (long) (cfg("wanted-minutes", 3) * 60000 * w.level);
        w.why = why;
        if (w.level > before) {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "WANTED " + stars(w.level) + ChatColor.RED + " Halcyon Security: " + ChatColor.GRAY + why + ".");
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.HOSTILE, 1.5f, 0.6f);
            if (w.level >= 2) for (Player o : pl.world.getPlayers())
                if (o != p && o.getLocation().distanceSquared(center()) < 300 * 300) o.sendMessage(ChatColor.RED + "[Halcyon Radio] " + ChatColor.GRAY + "All units: " + p.getName() + " is wanted (" + why + ").");
        }
        citizens.alarm(p);
        fleet.alarm(p);
    }

    static String stars(int n) { return ChatColor.GOLD + "★".repeat(n) + ChatColor.DARK_GRAY + "★".repeat(Math.max(0, 3 - n)); }

    void wantedTick() {
        long now = System.currentTimeMillis();
        for (var it = wanted.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Wanted w = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (now > w.until) {
                if (w.bar != null) w.bar.removeAll();
                if (p != null) { p.sendMessage(ChatColor.GREEN + "Halcyon Security lost interest in you."); if (glowing(p)) p.removePotionEffect(PotionEffectType.GLOWING); }
                it.remove();
                continue;
            }
            if (p == null) continue;
            boolean near = p.getWorld().equals(pl.world) && p.getLocation().distanceSquared(center()) < Math.pow(cfg("radar-radius", 400), 2);
            if (w.bar == null) w.bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
            w.bar.setTitle(ChatColor.RED + "WANTED " + stars(w.level) + ChatColor.GRAY + "  " + w.why + ChatColor.DARK_GRAY + "  (" + ((w.until - now) / 1000) + " s)");
            w.bar.setProgress(Math.max(0, Math.min(1, (w.until - now) / (cfg("wanted-minutes", 3) * 60000.0 * w.level))));
            if (near) { if (!w.bar.getPlayers().contains(p)) w.bar.addPlayer(p); } else w.bar.removePlayer(p);
            if (near && w.level >= 2) p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false, false));
        }
    }

    static boolean glowing(Player p) { PotionEffect e = p.getPotionEffect(PotionEffectType.GLOWING); return e != null && e.isAmbient() && !e.hasParticles(); }

    void clearWanted(UUID id) {
        Wanted w = wanted.remove(id);
        if (w != null && w.bar != null) w.bar.removeAll();
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) { clearWanted(e.getEntity().getUniqueId()); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { inside.remove(e.getPlayer().getUniqueId()); Wanted w = wanted.get(e.getPlayer().getUniqueId()); if (w != null && w.bar != null) w.bar.removePlayer(e.getPlayer()); }

    // =========================================================================================== protection
    boolean protect() { return enabled() && pl.getConfig().getBoolean("halcyon.protect", true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!protect() || !ours(e.getBlock()) || exempt(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("This is Halcyon's. Hands off.", NamedTextColor.RED));
    }

    /** Starting to mine the city: a guard who sees it (or a citizen close by) calls it in. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDig(BlockDamageEvent e) {
        if (!protect() || !ours(e.getBlock()) || exempt(e.getPlayer())) return;
        Player p = e.getPlayer();
        if (citizens.witnessed(p)) {
            boolean shell = Halcyon.e(e.getBlock().getX() + 0.5, e.getBlock().getY() + 0.5, e.getBlock().getZ() + 0.5) > 1 - Halcyon.SHELL / Halcyon.A - 0.02;
            crime(p, 2, shell ? "mining the dome" : "vandalising the city");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!protect() || !ours(e.getBlockPlaced()) || exempt(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("No building in Halcyon.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent e) { if (protect() && ours(e.getBlock()) && !exempt(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent e) { if (protect() && ours(e.getBlockClicked()) && !exempt(e.getPlayer())) e.setCancelled(true); }

    static final Set<Material> ALLOWED = Set.of(Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.STONECUTTER,
            Material.CARTOGRAPHY_TABLE, Material.LOOM, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL, Material.ENCHANTING_TABLE,
            Material.LECTERN, Material.BELL, Material.ENDER_CHEST);

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !protect() || !ours(b) || exempt(e.getPlayer())) return;
        if (e.getAction() == Action.PHYSICAL) { if (b.getType() == Material.FARMLAND) e.setCancelled(true); return; } // no trampling
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Material m = b.getType();
        if (b.getBlockData() instanceof Openable || ALLOWED.contains(m) || m.name().endsWith("_BUTTON") || m.name().endsWith("_PRESSURE_PLATE")) return;
        if (b.getState() instanceof org.bukkit.inventory.InventoryHolder || m.name().endsWith("_BED") || m == Material.COMPOSTER || m.name().contains("CAULDRON")
                || m == Material.FLOWER_POT || m.name().startsWith("POTTED_") || m == Material.SWEET_BERRY_BUSH || m == Material.CAMPFIRE || m == Material.JUKEBOX
                || m.name().endsWith("_SIGN") || m == Material.RESPAWN_ANCHOR || m.name().contains("CANDLE") || m == Material.CAKE) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("That belongs to the citizens of Halcyon.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (protect()) e.blockList().removeIf(this::ours); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (protect()) e.blockList().removeIf(this::ours); }

    @EventHandler(ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { if (protect() && (ours(e.getBlock()) || e.getBlocks().stream().anyMatch(this::ours))) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { if (protect() && (ours(e.getBlock()) || e.getBlocks().stream().anyMatch(this::ours))) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (protect() && ours(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (protect() && ours(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) { if (protect() && ours(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (protect() && ours(e.getBlock()) && e.getBlock().getType() == Material.FARMLAND) e.setCancelled(true); }

    /** Water from outside never runs in (a hole someone made); water already inside (the fountain, the tanks) behaves. */
    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!protect()) return;
        Block to = e.getToBlock(), from = e.getBlock();
        if (pl.world == null || !to.getWorld().equals(pl.world)) return;
        if (Halcyon.inside(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5) && !Halcyon.inside(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (!protect() || !ours(e.getBlock())) return;
        if (e.getEntity() instanceof Player p && exempt(p)) return;
        e.setCancelled(true); // trampling, endermen, ravagers, falling things
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!enabled() || !e.getLocation().getWorld().equals(pl.world) || !(e.getEntity() instanceof Enemy)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS && r != CreatureSpawnEvent.SpawnReason.PATROL) return;
        if (Halcyon.grounds(e.getLocation().getX(), e.getLocation().getY(), e.getLocation().getZ())) e.setCancelled(true);
    }

    // =========================================================================================== admin
    String info() {
        StringBuilder sb = new StringBuilder(ChatColor.AQUA + "Halcyon " + ChatColor.GRAY + "at " + Halcyon.HX + ", " + Halcyon.G + ", " + Halcyon.HZ
                + (active ? ChatColor.GREEN + " (awake)" : ChatColor.DARK_GRAY + " (asleep: nobody near)"));
        sb.append("\n").append(ChatColor.GRAY).append(citizens.summary());
        sb.append("\n").append(ChatColor.GRAY).append(fleet.summary());
        for (var en : wanted.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            sb.append("\n").append(ChatColor.RED).append("Wanted: ").append(p == null ? en.getKey() : p.getName()).append(" ").append(stars(en.getValue().level));
        }
        return sb.toString();
    }

    void shutdown() {
        citizens.removeAll();
        fleet.removeAll();
        exhibits.removeAll();
        for (Wanted w : wanted.values()) if (w.bar != null) w.bar.removeAll();
        save();
    }
}
