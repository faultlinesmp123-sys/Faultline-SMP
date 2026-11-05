package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Bee;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * WEATHER EVENTS (config weather:): rare things the sky (or the fields) do on their own.
 *   SANDSTORM  in deserts / badlands: thick dust, slowness, husk hordes, and buried treasure in the sand you dig.
 *   BLIZZARD   in snowy biomes: snow, cold (you freeze unless you're near a fire or wearing leather), stray packs.
 *   ECLIPSE    at midday the sun goes dark for 5 minutes: it turns to night and every neutral mob turns on you.
 * (The Aurora and the Locust Swarm were removed in Items 1.2.67; leftover locusts carry TAG and are swept on startup.)
 * Admin: /fweather <sandstorm|blizzard|eclipse> [player] | stop [kind] | status
 */
final class Weather implements Listener, CommandExecutor, org.bukkit.command.TabCompleter {

    static final String TAG = "faultline_weather", HUSK_TAG = "faultline_sandstorm_husk", STRAY_TAG = "faultline_blizzard_stray",
            PART_TAG = "faultline_weather_part";
    static final List<String> KINDS = List.of("sandstorm", "blizzard", "eclipse");
    static final Map<String, String> ABOUT = Map.of(
            "sandstorm", "deserts/badlands: dust, husks, treasure in the sand",
            "blizzard", "snowy biomes: freezing cold, strays",
            "eclipse", "a black sun: beasts turn on you for 5 min");

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final Map<String, Event> active = new LinkedHashMap<>();
    private int ticks;

    Weather(FaultlineItems plugin) {
        // 1.2.0's eclipse froze the daylight cycle at midnight and only undid it when it ended normally: a world it left
        // stuck (daylight cycle off, time exactly 18000) gets its days and nights back
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) {
                try {
                    if (!FaultlineItems.surfaceWorld(w)) continue;
                    if (Boolean.FALSE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE)) && Math.abs(w.getTime() % 24000 - 18000) <= 100) {
                        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
                        plugin.getLogger().warning("[Weather] " + w.getName() + " was stuck at midnight with the daylight cycle off (left by an old eclipse): turned the daylight cycle back on.");
                    }
                } catch (RuntimeException ignored) { }
            }
        });
        this.plugin = plugin;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
    }

    double cfg(String path, double def) { return plugin.getConfig().getDouble("weather." + path, def); }

    boolean on(String kind) { return plugin.getConfig().getBoolean("weather." + kind + ".enabled", true); }

    static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    static boolean bedrock(Player p) { return Edition.bedrock(p); }

    // =====================================================================================================
    //  the clock: every minute each kind rolls its chance where it can happen
    // =====================================================================================================
    private void tick() {
        ticks++;
        for (Event e : new ArrayList<>(active.values())) {
            try {
                if (e.tick()) end(e, true);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[Weather] " + e.kind + " error (it ends):", ex);
                end(e, false);
            }
        }
        if (ticks % 1200 != 0 || !plugin.getConfig().getBoolean("weather.enabled", true)) return;
        // never on top of a FaultlineEvents event (Blood Moon, fog...): one sky event at a time
        if (plugin.faultlineEvent() != null) return;
        for (World w : Bukkit.getWorlds()) {
            if (!FaultlineItems.surfaceWorld(w)) continue;
            long t = w.getTime();
            // ECLIPSE: around midday
            if (on("eclipse") && !active.containsKey("eclipse") && t >= 4500 && t < 7500 && !w.getPlayers().isEmpty()
                    && random.nextDouble() < cfg("eclipse.chance-per-minute", 0.003)) start("eclipse", w, null);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || !FaultlineItems.surfaceWorld(p.getWorld())) continue;
            Biome b = p.getLocation().getBlock().getBiome();
            if (on("sandstorm") && !active.containsKey("sandstorm") && SANDS.contains(b) && random.nextDouble() < cfg("sandstorm.chance-per-minute", 0.006)) start("sandstorm", p.getWorld(), p.getLocation());
            else if (on("blizzard") && !active.containsKey("blizzard") && SNOWY.contains(b) && random.nextDouble() < cfg("blizzard.chance-per-minute", 0.006)) start("blizzard", p.getWorld(), p.getLocation());
        }
    }

    static boolean isDay(World w) { long t = w.getTime(); return t < 12000 || t > 23500; }

    static final Set<Biome> SANDS = new HashSet<>(List.of(Biome.DESERT, Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS));
    static final Set<Biome> SNOWY = new HashSet<>(List.of(Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.SNOWY_SLOPES, Biome.SNOWY_BEACH, Biome.GROVE,
            Biome.ICE_SPIKES, Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.FROZEN_RIVER, Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN));

    // =====================================================================================================
    //  starting and ending
    // =====================================================================================================
    Event start(String kind, World w, Location at) {
        if (active.containsKey(kind)) return null;
        Event e = switch (kind) {
            case "sandstorm" -> at == null ? null : new Sandstorm(at);
            case "blizzard" -> at == null ? null : new Blizzard(at);
            case "eclipse" -> new Eclipse(w);
            default -> null;
        };
        if (e == null) return null;
        active.put(kind, e);
        e.begin();
        return e;
    }

    void end(Event e, boolean natural) {
        active.values().remove(e);
        e.finish(natural);
    }

    void shutdown() {
        for (Event e : new ArrayList<>(active.values())) end(e, false);
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    abstract class Event {
        final String kind;
        final World world;
        final long endsAt;
        int t;
        Event(String kind, World world, double minutes) { this.kind = kind; this.world = world; endsAt = System.currentTimeMillis() + (long) (minutes * 60000); }
        abstract void begin();
        /** True when it's over. */
        abstract boolean tick();
        abstract void finish(boolean natural);
        String where() { return ""; }
    }

    // =====================================================================================================
    //  SANDSTORM
    // =====================================================================================================
    final class Sandstorm extends Event {
        final Location center;
        final Set<Long> placed = new HashSet<>();          // sand put down during the storm: never treasure (no place-and-dig farm)
        final Map<UUID, Integer> found = new HashMap<>();   // treasures per player this storm
        Sandstorm(Location at) { super("sandstorm", at.getWorld(), Weather.this.cfg("sandstorm.minutes", 6)); center = at.clone(); }

        @Override String where() { return " near X " + center.getBlockX() + ", Z " + center.getBlockZ(); }

        boolean inside(Player p) {
            return p.getWorld().equals(world) && p.getLocation().distanceSquared(center) < sq(cfg("sandstorm.radius", 110));
        }

        @Override void begin() {
            for (Player p : world.getPlayers()) if (inside(p)) {
                p.showTitle(net.kyori.adventure.title.Title.title(Meteor.legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "SANDSTORM"),
                        Meteor.legacy(ChatColor.YELLOW + "Husks ride the wind. Dig the sand for treasure.")));
                p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 1f, 0.5f);
            }
            Bukkit.broadcastMessage(ChatColor.GOLD + "A sandstorm sweeps the desert" + where() + ChatColor.GRAY + " (husks, and buried treasure)");
        }

        @Override boolean tick() {
            t++;
            if (System.currentTimeMillis() > endsAt) return true;
            if (t % 5 == 0) for (Player p : world.getPlayers()) {
                if (!inside(p)) continue;
                Location l = p.getLocation();
                p.spawnParticle(Particle.FALLING_DUST, l.clone().add(0, 1.5, 0), 70, 5, 2.5, 5, 0, Material.SAND.createBlockData());
                p.spawnParticle(Particle.FALLING_DUST, l.clone().add(0, 1.2, 0), 25, 1.5, 1, 1.5, 0, Material.RED_SAND.createBlockData());
                if (survival(p) && l.getBlock().getLightFromSky() > 10) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, true, false, true));
                    if (p.getInventory().getHelmet() == null) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, true, false, true)); // dust in your eyes: wear a helmet
                }
                if (t % 60 == 0) p.playSound(l, Sound.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 0.35f, 0.4f);
            }
            if (t % 300 == 0) for (Player p : world.getPlayers()) {
                if (!inside(p) || !survival(p)) continue;
                long near = p.getNearbyEntities(32, 16, 32).stream().filter(e -> e.getScoreboardTags().contains(HUSK_TAG)).count();
                for (int i = 0; i < Math.min(2, (int) cfg("sandstorm.husks-per-player", 4) - near); i++) {
                    Location s = ring(p.getLocation(), 10, 16);
                    if (s == null) continue;
                    world.spawn(s, Husk.class, h -> {
                        h.addScoreboardTag(TAG); h.addScoreboardTag(HUSK_TAG);
                        h.setCustomName(ChatColor.GOLD + "Sandstorm Husk");
                        h.setCustomNameVisible(false);
                        h.setPersistent(false);
                        h.setTarget(p);
                    });
                }
            }
            return false;
        }

        @Override void finish(boolean natural) {
            if (natural) for (Player p : world.getPlayers()) if (inside(p)) p.sendMessage(ChatColor.GOLD + "The sandstorm settles.");
            for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(HUSK_TAG) && e.getLocation().distanceSquared(center) < sq(200)) e.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceSand(BlockPlaceEvent e) {
        if (!(active.get("sandstorm") instanceof Sandstorm s)) return;
        Material m = e.getBlockPlaced().getType();
        if ((m == Material.SAND || m == Material.RED_SAND || m == Material.SUSPICIOUS_SAND) && e.getBlockPlaced().getWorld().equals(s.world))
            s.placed.add(e.getBlockPlaced().getBlockKey());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDig(BlockBreakEvent e) {
        if (!(active.get("sandstorm") instanceof Sandstorm s) || !s.inside(e.getPlayer())) return;
        Material m = e.getBlock().getType();
        if (m != Material.SAND && m != Material.RED_SAND && m != Material.SUSPICIOUS_SAND) return;
        if (s.placed.remove(e.getBlock().getBlockKey())) return;
        int had = s.found.getOrDefault(e.getPlayer().getUniqueId(), 0);
        if (had >= (int) cfg("sandstorm.treasure-per-player", 3)) return;
        if (random.nextDouble() >= cfg("sandstorm.treasure-chance", 0.04)) return;
        s.found.put(e.getPlayer().getUniqueId(), had + 1);
        Location l = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        List<String> loot = plugin.getConfig().getStringList("weather.sandstorm.treasure");
        if (loot.isEmpty()) loot = List.of("GOLD_INGOT:2-6", "EMERALD:1-4", "DIAMOND:1-2:25", "GOLDEN_APPLE:1:15", "RABBIT_FOOT:1:30");
        for (String line : loot) { ItemStack it = parse(line); if (it != null) l.getWorld().dropItemNaturally(l, it); }
        e.getPlayer().sendActionBar(Meteor.legacy(ChatColor.GOLD + "You dug up buried treasure!"));
        l.getWorld().playSound(l, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1f, 0.8f);
        l.getWorld().spawnParticle(Particle.WAX_ON, l, 20, 0.4, 0.4, 0.4, 0.5);
    }

    // =====================================================================================================
    //  BLIZZARD
    // =====================================================================================================
    final class Blizzard extends Event {
        final Location center;
        final Set<UUID> weathered = new HashSet<>();
        Blizzard(Location at) { super("blizzard", at.getWorld(), Weather.this.cfg("blizzard.minutes", 6)); center = at.clone(); }

        @Override String where() { return " near X " + center.getBlockX() + ", Z " + center.getBlockZ(); }

        boolean inside(Player p) {
            return p.getWorld().equals(world) && p.getLocation().distanceSquared(center) < sq(cfg("blizzard.radius", 110));
        }

        @Override void begin() {
            for (Player p : world.getPlayers()) if (inside(p)) p.showTitle(net.kyori.adventure.title.Title.title(Meteor.legacy(ChatColor.AQUA + "" + ChatColor.BOLD + "BLIZZARD"),
                    Meteor.legacy(ChatColor.WHITE + "Stay by a fire, or wear leather.")));
            Bukkit.broadcastMessage(ChatColor.AQUA + "A blizzard howls over the snow" + where() + ChatColor.GRAY + " (keep warm: fire or leather)");
        }

        boolean warm(Player p) {
            int leather = 0;
            for (ItemStack it : p.getInventory().getArmorContents()) if (it != null && it.getType().name().startsWith("LEATHER_")) leather++;
            if (leather >= 2) return true;
            Location l = p.getLocation();
            for (int dx = -4; dx <= 4; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -4; dz <= 4; dz++) {
                Block b = l.getBlock().getRelative(dx, dy, dz);
                Material m = b.getType();
                if (m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LAVA || m == Material.MAGMA_BLOCK) return true;
                if ((m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER)
                        && b.getBlockData() instanceof Lightable lit && lit.isLit()) return true;
            }
            return false;
        }

        @Override boolean tick() {
            t++;
            if (System.currentTimeMillis() > endsAt) return true;
            if (t % 10 == 0) for (Player p : world.getPlayers()) {
                boolean in = inside(p);
                if (in && weathered.add(p.getUniqueId())) p.setPlayerWeather(WeatherType.DOWNFALL);
                if (!in && weathered.remove(p.getUniqueId())) p.resetPlayerWeather();
                if (!in) continue;
                Location l = p.getLocation();
                p.spawnParticle(Particle.SNOWFLAKE, l.clone().add(0, 2, 0), 60, 6, 3, 6, 0.08);
                p.spawnParticle(Particle.WHITE_ASH, l.clone().add(0, 1.5, 0), 30, 4, 2, 4, 0.02);
                if (!survival(p) || l.getBlock().getLightFromSky() < 10) continue;
                if (warm(p)) { if (t % 100 == 0) p.sendActionBar(Meteor.legacy(ChatColor.GOLD + "You're warm.")); continue; }
                p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + (int) cfg("blizzard.cold-per-half-second", 26)));
                if (t % 60 == 0) p.sendActionBar(Meteor.legacy(ChatColor.AQUA + "You're freezing! " + ChatColor.GRAY + "Stand by a fire, or wear leather."));
            }
            if (t % 400 == 0) for (Player p : world.getPlayers()) {
                if (!inside(p) || !survival(p)) continue;
                long near = p.getNearbyEntities(32, 16, 32).stream().filter(e -> e.getScoreboardTags().contains(STRAY_TAG)).count();
                if (near >= cfg("blizzard.strays-per-player", 6)) continue;
                for (int i = 0; i < 3; i++) {
                    Location s = ring(p.getLocation(), 10, 18);
                    if (s == null) continue;
                    world.spawn(s, Stray.class, st -> {
                        st.addScoreboardTag(TAG); st.addScoreboardTag(STRAY_TAG);
                        st.setCustomName(ChatColor.AQUA + "Blizzard Stray");
                        st.setCustomNameVisible(false);
                        st.setPersistent(false);
                        st.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                        st.setTarget(p);
                    });
                }
            }
            return false;
        }

        @Override void finish(boolean natural) {
            for (UUID u : weathered) { Player p = Bukkit.getPlayer(u); if (p != null) p.resetPlayerWeather(); }
            weathered.clear();
            if (natural) Bukkit.broadcastMessage(ChatColor.AQUA + "The blizzard blows itself out.");
            for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(STRAY_TAG) && e.getLocation().distanceSquared(center) < sq(200)) e.remove();
        }
    }

    // =====================================================================================================
    //  ECLIPSE
    // =====================================================================================================
    /**
     * The sun goes black. It never touches the world's time or its gamerules any more: the old version froze the daylight
     * cycle at midnight and only gave it back when the eclipse ended normally, so a restart or crash mid-eclipse left the
     * world stuck (no more days or nights: blood moons, fog and every other night event stopped). Now each player's own
     * sky is set to midnight (setPlayerTime, never saved, undone on quit/relog), a BLACK SUN with a burning corona hangs
     * straight overhead where the moon would be (Java: a model only they see; Bedrock: a ring of particles), and the
     * real world keeps turning.
     */
    final class Eclipse extends Event {
        BossBar bar;
        final Map<UUID, ItemDisplay> suns = new HashMap<>();
        Eclipse(World w) { super("eclipse", w, Weather.this.cfg("eclipse.minutes", 5)); }

        @Override void begin() {
            bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☀ ECLIPSE", BarColor.RED, BarStyle.SOLID);
            for (Player p : world.getPlayers()) {
                darken(p);
                p.showTitle(net.kyori.adventure.title.Title.title(Meteor.legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "ECLIPSE"),
                        Meteor.legacy(ChatColor.GRAY + "The sun goes black. Every beast turns on you.")));
                p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.5f);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.4f, 0.5f);
            }
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The sun turns black! " + ChatColor.GRAY + "Wolves, bees, golems, endermen and bears turn hostile for " + (int) cfg("eclipse.minutes", 5) + " minutes.");
        }

        void darken(Player p) {
            p.setPlayerTime(18000, false); // only their sky; the world's own clock runs on
            bar.addPlayer(p);
        }

        void lighten(Player p) {
            p.resetPlayerTime();
            if (bar != null) bar.removePlayer(p);
            ItemDisplay d = suns.remove(p.getUniqueId());
            if (d != null && d.isValid()) d.remove();
        }

        ItemDisplay sun(Player p) {
            ItemDisplay d = suns.get(p.getUniqueId());
            if (d != null && d.isValid() && d.getWorld().equals(p.getWorld())) return d;
            if (d != null && d.isValid()) d.remove();
            d = p.getWorld().spawn(p.getLocation().add(0, 50, 0), ItemDisplay.class, e -> {
                e.setVisibleByDefault(false);
                e.setItemStack(WildRig.model("black_sun"));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4f);
                e.setTeleportDuration(3);
                e.setPersistent(false);
                e.addScoreboardTag(TAG);
                e.addScoreboardTag("faultline_no_bedrock_fx");
            });
            double[] meta = WildParts.of("black_sun");
            double k = Math.max(0.3, Math.min(3, cfg("eclipse.sun-size", 1.0)));
            float sc = (float) (meta[0] * k);
            d.setTransformation(new Transformation(new Vector3f((float) (meta[1] * k), (float) (meta[2] * k), (float) (meta[3] * k)),
                    new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
            p.showEntity(plugin, d);
            suns.put(p.getUniqueId(), d);
            return d;
        }

        @Override boolean tick() {
            t++;
            long left = endsAt - System.currentTimeMillis();
            if (left <= 0) return true;
            double up = Math.max(30, Math.min(90, cfg("eclipse.sun-height", 55)));
            for (Player p : world.getPlayers()) {
                if (p.getPlayerTime() % 24000 != 18000 || p.isPlayerTimeRelative()) darken(p); // a new arrival, or a relog
                if (bedrock(p)) {
                    if (t % 5 == 0) { // Bedrock: a black disc ringed with fire, straight overhead
                        Location c = p.getLocation().add(0, 30, 0);
                        for (int i = 0; i < 28; i++) {
                            double a = i * Math.PI * 2 / 28;
                            p.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 6, 0, Math.sin(a) * 6), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 170, 60), 3f));
                            if (i % 2 == 0) p.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 3.5, 0.1, Math.sin(a) * 3.5), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(10, 10, 14), 4f));
                        }
                        p.spawnParticle(Particle.DUST, c, 6, 1.5, 0, 1.5, 0, new Particle.DustOptions(Color.fromRGB(10, 10, 14), 4f));
                    }
                } else if (plugin.eventPacks != null && plugin.eventPacks.sends("eclipse")) {
                    ItemDisplay old = suns.remove(p.getUniqueId()); // 1.2.59: the Eclipse pack draws the black sun in the sky
                    if (old != null && old.isValid()) old.remove();
                } else {
                    ItemDisplay d = sun(p);
                    Location at = p.getLocation().add(0, up, 0);
                    at.setYaw(0); at.setPitch(0);
                    d.teleport(at);
                }
            }
            for (Iterator<Map.Entry<UUID, ItemDisplay>> it = suns.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || !p.getWorld().equals(world)) { if (e.getValue().isValid()) e.getValue().remove(); if (p != null) lighten(p); it.remove(); }
            }
            if (t % 20 == 0) bar.setProgress(Math.max(0, Math.min(1, left / (cfg("eclipse.minutes", 5) * 60000))));
            if (t % 60 == 0) for (Player p : world.getPlayers()) {
                if (!survival(p)) continue;
                for (Entity e : p.getNearbyEntities(24, 12, 24)) {
                    if (!(e instanceof Mob m) || m.getTarget() != null) continue;
                    if (e instanceof Wolf w && w.isTamed()) continue;
                    if (e instanceof IronGolem g && g.isPlayerCreated()) continue;
                    if (e instanceof Wolf || e instanceof Bee || e instanceof IronGolem || e instanceof Enderman || e instanceof PolarBear
                            || e instanceof Spider || e instanceof CaveSpider) {
                        if (e instanceof Wolf w) w.setAngry(true);
                        if (e instanceof Bee b) b.setAnger(400);
                        m.setTarget(p);
                    }
                }
            }
            return false;
        }

        @Override void finish(boolean natural) {
            for (Player p : Bukkit.getOnlinePlayers()) if (suns.containsKey(p.getUniqueId()) || p.getWorld().equals(world)) lighten(p);
            for (ItemDisplay d : suns.values()) if (d.isValid()) d.remove();
            suns.clear();
            if (bar != null) bar.removeAll();
            if (natural) Bukkit.broadcastMessage(ChatColor.YELLOW + "The eclipse passes. The sun returns.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) { // storm mobs don't burn in the (dark) day
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(HUSK_TAG) || t.contains(STRAY_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (active.get("eclipse") instanceof Eclipse ec && ec.bar != null && p.getWorld().equals(ec.world)) ec.darken(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (active.get("blizzard") instanceof Blizzard b && b.weathered.remove(e.getPlayer().getUniqueId())) e.getPlayer().resetPlayerWeather();
        if (active.get("eclipse") instanceof Eclipse ec) ec.lighten(e.getPlayer());
    }

    // =====================================================================================================
    //  helpers + /fweather
    // =====================================================================================================
    static double sq(double v) { return v * v; }

    /** A spot on the surface between min and max blocks from l (open air above), or null. */
    Location ring(Location l, double min, double max) {
        World w = l.getWorld();
        for (int i = 0; i < 10; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (top.isLiquid() || Math.abs(top.getY() - l.getY()) > 12) continue;
            return top.getLocation().add(0.5, 1, 0.5);
        }
        return null;
    }

    ItemStack parse(String line) {
        try {
            String[] p = line.split(":");
            Material m = Material.valueOf(p[0].trim().toUpperCase());
            int lo = 1, hi = 1;
            if (p.length > 1) { String[] r = p[1].split("-"); lo = Integer.parseInt(r[0].trim()); hi = r.length > 1 ? Integer.parseInt(r[1].trim()) : lo; }
            if (p.length > 2 && random.nextDouble() * 100 >= Double.parseDouble(p[2].trim())) return null;
            return new ItemStack(m, lo + random.nextInt(Math.max(1, hi - lo + 1)));
        } catch (RuntimeException e) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("items.weather")) return out;
        if (args.length == 1) {
            for (String k : KINDS) if (k.startsWith(args[0].toLowerCase())) out.add(k);
            for (String k : List.of("list", "stop", "status")) if (k.startsWith(args[0].toLowerCase())) out.add(k);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            for (String k : KINDS) if (k.startsWith(args[1].toLowerCase())) out.add(k);
        } else if (args.length == 2 && KINDS.contains(args[0].toLowerCase())) {
            for (Player o : Bukkit.getOnlinePlayers()) if (o.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(o.getName());
        }
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.weather")) {
            if (active.isEmpty()) sender.sendMessage(ChatColor.GRAY + "The skies are calm.");
            else for (Event e : active.values()) sender.sendMessage(ChatColor.YELLOW + "Happening now: " + ChatColor.WHITE + e.kind + e.where());
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        Player p = sender instanceof Player pl ? pl : null;
        // /fweather <event> <player>: at that player (the console and /itemsmenu use this)
        if (args.length > 1 && KINDS.contains(sub)) {
            Player who = Bukkit.getPlayerExact(args[1]);
            if (who == null) { sender.sendMessage(ChatColor.RED + "No player called " + args[1] + " online."); return true; }
            p = who;
        }
        if ((sub.equals("list") || sub.equals("help")) && sender instanceof Player bp && Edition.bedrock(bp)) {
            List<String> names = new ArrayList<>();
            for (String k : KINDS) names.add(k + (active.containsKey(k) ? " §e(now)" : "") + "\n§8" + ABOUT.get(k));
            if (BedrockForms.menu(plugin, bp, "Weather events", "Start one where you stand.", names,
                    i -> bp.performCommand("fweather " + KINDS.get(i)), null)) return true;
        }
        if (sub.equals("list") || sub.equals("help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Weather events " + ChatColor.GRAY + "(click one to start it where you stand)");
            for (String k : KINDS) {
                Component line = Component.text(" [start] ", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/fweather " + k))
                        .hoverEvent(HoverEvent.showText(Component.text("/fweather " + k)))
                        .append(Component.text(k, active.containsKey(k) ? NamedTextColor.YELLOW : NamedTextColor.WHITE))
                        .append(Component.text(" - " + ABOUT.get(k) + (active.containsKey(k) ? " (happening now)" : ""), NamedTextColor.GRAY));
                sender.sendMessage(line);
            }
            sender.sendMessage(ChatColor.GRAY + "/fweather stop [event] | status | <event> [player]");
            return true;
        }
        if (sub.equals("status")) {
            if (active.isEmpty()) sender.sendMessage(ChatColor.GRAY + "No weather events running.");
            for (Event e : active.values()) sender.sendMessage(ChatColor.YELLOW + e.kind + ChatColor.GRAY + e.where() + " (" + Math.max(0, (e.endsAt - System.currentTimeMillis()) / 1000) + "s left)");
            return true;
        }
        if (sub.equals("stop")) {
            String which = args.length > 1 ? args[1].toLowerCase() : null;
            for (Event e : new ArrayList<>(active.values())) if (which == null || e.kind.equals(which)) end(e, true);
            sender.sendMessage(ChatColor.GREEN + "Stopped.");
            return true;
        }
        if (!KINDS.contains(sub)) { sender.sendMessage(ChatColor.YELLOW + "/fweather <sandstorm|blizzard|eclipse|stop [kind]|status>"); return true; }
        if (active.containsKey(sub)) { sender.sendMessage(ChatColor.RED + "That's already happening (/fweather stop " + sub + ")."); return true; }
        World w = p != null ? p.getWorld() : Bukkit.getWorlds().get(0);
        Location at = p != null ? p.getLocation() : null;
        Event e = start(sub, w, at);
        sender.sendMessage(e != null ? ChatColor.GREEN + "Started: " + sub : ChatColor.RED + "Couldn't start that here (stand where it should happen).");
        return true;
    }

}
