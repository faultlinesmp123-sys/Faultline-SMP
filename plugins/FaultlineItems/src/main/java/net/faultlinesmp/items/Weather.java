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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Lightable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
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
import org.bukkit.entity.Vex;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
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
 *   AURORA     some nights: ribbons of light across the sky (Java: glowing models; Bedrock: coloured particles).
 *              Experience is doubled and enchanting is cheaper until dawn.
 *   SANDSTORM  in deserts / badlands: thick dust, slowness, husk hordes, and buried treasure in the sand you dig.
 *   BLIZZARD   in snowy biomes: snow, cold (you freeze unless you're near a fire or wearing leather), stray packs.
 *   ECLIPSE    at midday the sun goes dark for 5 minutes: it turns to night and every neutral mob turns on you.
 *   LOCUSTS    a swarm finds a farm and eats the crops (back to seedlings) until it's driven off.
 * Admin: /fevent <aurora|sandstorm|blizzard|eclipse|locusts> [here] | stop [kind] | status
 */
final class Weather implements Listener, CommandExecutor {

    static final String TAG = "faultline_weather", HUSK_TAG = "faultline_sandstorm_husk", STRAY_TAG = "faultline_blizzard_stray",
            LOCUST_TAG = "faultline_locust", PART_TAG = "faultline_weather_part";
    static final List<String> KINDS = List.of("aurora", "sandstorm", "blizzard", "eclipse", "locusts");

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final Map<String, Event> active = new LinkedHashMap<>();
    private final Map<String, Long> lastNight = new HashMap<>();
    private int ticks;

    Weather(FaultlineItems plugin) {
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
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            long t = w.getTime();
            // AURORA: rolled once each night, as it falls
            if (on("aurora") && !active.containsKey("aurora") && t >= 13000 && t < 14500) {
                long day = w.getFullTime() / 24000;
                if (!Objects.equals(lastNight.get(w.getName()), day)) {
                    lastNight.put(w.getName(), day);
                    if (random.nextDouble() < cfg("aurora.chance-per-night", 0.2)) start("aurora", w, null);
                }
            }
            // ECLIPSE: around midday
            if (on("eclipse") && !active.containsKey("eclipse") && t >= 4500 && t < 7500 && !w.getPlayers().isEmpty()
                    && random.nextDouble() < cfg("eclipse.chance-per-minute", 0.003)) start("eclipse", w, null);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            Biome b = p.getLocation().getBlock().getBiome();
            if (on("sandstorm") && !active.containsKey("sandstorm") && SANDS.contains(b) && random.nextDouble() < cfg("sandstorm.chance-per-minute", 0.006)) start("sandstorm", p.getWorld(), p.getLocation());
            else if (on("blizzard") && !active.containsKey("blizzard") && SNOWY.contains(b) && random.nextDouble() < cfg("blizzard.chance-per-minute", 0.006)) start("blizzard", p.getWorld(), p.getLocation());
            else if (on("locusts") && !active.containsKey("locusts") && isDay(p.getWorld()) && random.nextDouble() < cfg("locusts.chance-per-minute", 0.004)) {
                Location farm = farmNear(p.getLocation(), 16, (int) cfg("locusts.min-crops", 16));
                if (farm != null) start("locusts", p.getWorld(), farm);
            }
        }
    }

    static boolean isDay(World w) { long t = w.getTime(); return t < 12000 || t > 23500; }

    static final Set<Biome> SANDS = new HashSet<>(List.of(Biome.DESERT, Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS));
    static final Set<Biome> SNOWY = new HashSet<>(List.of(Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.SNOWY_SLOPES, Biome.SNOWY_BEACH, Biome.GROVE,
            Biome.ICE_SPIKES, Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.FROZEN_RIVER, Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN));
    static final Set<Material> CROPS = new HashSet<>(List.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.MELON_STEM, Material.PUMPKIN_STEM, Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP));

    /** The middle of a field near l with at least n crops within r, or null. */
    static Location farmNear(Location l, int r, int n) {
        World w = l.getWorld();
        int count = 0; double x = 0, y = 0, z = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -4; dy <= 4; dy++) {
            Block b = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            if (!CROPS.contains(b.getType())) continue;
            count++; x += b.getX(); y += b.getY(); z += b.getZ();
        }
        return count < n ? null : new Location(w, x / count + 0.5, y / count + 1.5, z / count + 0.5);
    }

    // =====================================================================================================
    //  starting and ending
    // =====================================================================================================
    Event start(String kind, World w, Location at) {
        if (active.containsKey(kind)) return null;
        Event e = switch (kind) {
            case "aurora" -> new Aurora(w);
            case "sandstorm" -> at == null ? null : new Sandstorm(at);
            case "blizzard" -> at == null ? null : new Blizzard(at);
            case "eclipse" -> new Eclipse(w);
            case "locusts" -> at == null ? null : new Locusts(at);
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
    //  AURORA
    // =====================================================================================================
    final class Aurora extends Event {
        final Map<UUID, List<ItemDisplay>> ribbons = new HashMap<>();
        Aurora(World w) { super("aurora", w, 14); }

        @Override void begin() {
            for (Player p : world.getPlayers()) {
                p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ An aurora lights up the night sky! " + ChatColor.GRAY + "Experience is doubled and enchanting is cheaper until dawn.");
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.AMBIENT, 1f, 0.6f);
            }
        }

        @Override boolean tick() {
            t++;
            long time = world.getTime();
            if (t > 200 && (time >= 23000 || time < 12500)) return true;
            if (t % 2 != 0) return false;
            Set<UUID> here = new HashSet<>();
            for (Player p : world.getPlayers()) {
                here.add(p.getUniqueId());
                List<ItemDisplay> rs = ribbons.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
                rs.removeIf(d -> !d.isValid());
                Location base = p.getLocation().clone();
                base.setY(Math.max(base.getY(), world.getHighestBlockYAt(base, HeightMap.MOTION_BLOCKING_NO_LEAVES)) + cfg("aurora.height", 70));
                if (rs.isEmpty()) for (int i = 0; i < 3; i++) {
                    int k = i;
                    rs.add(world.spawn(base, ItemDisplay.class, d -> {
                        ItemStack it = new ItemStack(Material.PAPER);
                        ItemMeta m = it.getItemMeta();
                        m.setItemModel(new NamespacedKey("faultline", "wild/aurora"));
                        it.setItemMeta(m);
                        d.setItemStack(it);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setViewRange(8f);
                        d.setTeleportDuration(10);
                        d.setInterpolationDuration(10);
                        d.setPersistent(false);
                        d.addScoreboardTag(TAG);
                        d.addScoreboardTag(PART_TAG + "_" + k);
                    }));
                }
                if (t % 10 == 0) for (int i = 0; i < rs.size(); i++) {
                    ItemDisplay d = rs.get(i);
                    double phase = (t / 20.0) * 0.08 + i * 1.7;
                    Location l = base.clone().add(Math.sin(phase) * 12, i * 6, -40 + i * 26);
                    d.teleport(l);
                    float s = (float) (WildParts.of("aurora")[0] * 5.0);
                    d.setInterpolationDelay(0);
                    d.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                            new Quaternionf().rotationY((float) (Math.sin(phase * 0.7) * 0.6 + i * 0.4)).rotateX((float) Math.toRadians(-20)),
                            new Vector3f(s, s * 1.6f, s), new Quaternionf()));
                }
                if (bedrock(p) && t % 10 == 0) { // Bedrock can't draw the ribbons: a curtain of coloured dust instead
                    Color[] cols = {Color.fromRGB(60, 255, 150), Color.fromRGB(120, 160, 255), Color.fromRGB(190, 110, 255)};
                    for (int i = 0; i < 40; i++) {
                        double x = (i - 20) * 1.2;
                        double y = 22 + Math.sin(i * 0.35 + t * 0.05) * 2 + (i % 3);
                        p.spawnParticle(Particle.DUST, p.getLocation().add(x, y, -18), 1, 0.2, 0.4, 0.2, 0, new Particle.DustOptions(cols[i % 3], 2.4f));
                    }
                }
            }
            for (Iterator<Map.Entry<UUID, List<ItemDisplay>>> it = ribbons.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (here.contains(e.getKey())) continue;
                for (ItemDisplay d : e.getValue()) if (d.isValid()) d.remove();
                it.remove();
            }
            return false;
        }

        @Override void finish(boolean natural) {
            for (List<ItemDisplay> l : ribbons.values()) for (ItemDisplay d : l) if (d.isValid()) d.remove();
            ribbons.clear();
            if (natural) for (Player p : world.getPlayers()) p.sendMessage(ChatColor.GRAY + "The aurora fades with the dawn.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent e) {
        Event a = active.get("aurora");
        if (a != null && e.getPlayer().getWorld().equals(a.world) && e.getAmount() > 0) e.setAmount((int) Math.round(e.getAmount() * cfg("aurora.xp-multiplier", 2.0)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        Event a = active.get("aurora");
        if (a == null || !e.getEnchanter().getWorld().equals(a.world)) return;
        double k = cfg("aurora.enchant-cost-multiplier", 0.7);
        for (var offer : e.getOffers()) if (offer != null) offer.setCost(Math.max(1, (int) Math.round(offer.getCost() * k)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        Event a = active.get("aurora");
        if (a == null || !e.getEnchanter().getWorld().equals(a.world)) return;
        e.setExpLevelCost(Math.max(1, (int) Math.round(e.getExpLevelCost() * cfg("aurora.enchant-cost-multiplier", 0.7))));
    }

    // =====================================================================================================
    //  SANDSTORM
    // =====================================================================================================
    final class Sandstorm extends Event {
        final Location center;
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
    public void onDig(BlockBreakEvent e) {
        if (!(active.get("sandstorm") instanceof Sandstorm s) || !s.inside(e.getPlayer())) return;
        Material m = e.getBlock().getType();
        if (m != Material.SAND && m != Material.RED_SAND && m != Material.SUSPICIOUS_SAND) return;
        if (random.nextDouble() >= cfg("sandstorm.treasure-chance", 0.04)) return;
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
    final class Eclipse extends Event {
        long savedTime;
        Boolean cycle;
        BossBar bar;
        Eclipse(World w) { super("eclipse", w, Weather.this.cfg("eclipse.minutes", 5)); }

        @Override void begin() {
            savedTime = world.getTime();
            cycle = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(18000);
            bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☀ ECLIPSE", BarColor.RED, BarStyle.SOLID);
            for (Player p : world.getPlayers()) {
                bar.addPlayer(p);
                p.showTitle(net.kyori.adventure.title.Title.title(Meteor.legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "ECLIPSE"),
                        Meteor.legacy(ChatColor.GRAY + "The sun goes dark. Every beast turns on you.")));
                p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.5f);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.4f, 0.5f);
            }
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "An eclipse darkens the sky! " + ChatColor.GRAY + "Wolves, bees, golems, endermen and bears turn hostile for 5 minutes.");
        }

        @Override boolean tick() {
            t++;
            long left = endsAt - System.currentTimeMillis();
            if (left <= 0) return true;
            if (t % 20 == 0) {
                bar.setProgress(Math.max(0, Math.min(1, left / (cfg("eclipse.minutes", 5) * 60000))));
                for (Player p : world.getPlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            }
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
            world.setTime(savedTime + 6000);
            if (cycle != null) world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, cycle);
            if (bar != null) bar.removeAll();
            if (natural) Bukkit.broadcastMessage(ChatColor.YELLOW + "The eclipse passes. The sun returns.");
        }
    }

    // =====================================================================================================
    //  LOCUSTS
    // =====================================================================================================
    final class Locusts extends Event {
        final Location farm;
        final List<Swarmer> swarm = new ArrayList<>();
        final Set<UUID> defenders = new HashSet<>();
        int eaten;
        Locusts(Location at) { super("locusts", at.getWorld(), Weather.this.cfg("locusts.minutes", 4)); farm = at.clone(); }

        @Override String where() { return " at X " + farm.getBlockX() + ", Z " + farm.getBlockZ(); }

        final class Swarmer {
            final Vex body;
            final ItemDisplay model;
            final ArmorStand stand;
            Swarmer(Location at) {
                body = world.spawn(at, Vex.class, v -> {
                    v.addScoreboardTag(TAG); v.addScoreboardTag(LOCUST_TAG);
                    v.setCustomName(ChatColor.YELLOW + "Locust");
                    v.setCustomNameVisible(false);
                    v.setPersistent(false);
                    v.setSilent(true);
                    v.setInvisible(true);
                    v.getEquipment().setItemInMainHand(null);
                    v.setBound(farm);
                    AttributeInstance mh = v.getAttribute(Attribute.MAX_HEALTH);
                    if (mh != null) { mh.setBaseValue(cfg("locusts.health", 6)); v.setHealth(cfg("locusts.health", 6)); }
                });
                model = WildRig.display(world, at, "locust", TAG);
                stand = WildRig.stand(plugin, world, at, "locust", 1.0, TAG);
                StandGuard.forward(stand, body);
            }
            boolean alive() { return body.isValid() && !body.isDead(); }
            void follow() {
                Location l = body.getLocation().add(0, 0.3, 0);
                WildRig.pose(model, stand, "locust", 1.0, l, l.getYaw(), null);
            }
            void remove() { if (body.isValid()) body.remove(); if (model.isValid()) model.remove(); StandGuard.forget(stand); if (stand.isValid()) stand.remove(); }
        }

        @Override void begin() {
            int n = (int) cfg("locusts.count", 12);
            for (int i = 0; i < n; i++) swarm.add(new Swarmer(farm.clone().add(random.nextGaussian() * 4, 2 + random.nextDouble() * 3, random.nextGaussian() * 4)));
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(farm) < sq(48)) {
                p.showTitle(net.kyori.adventure.title.Title.title(Meteor.legacy(ChatColor.YELLOW + "" + ChatColor.BOLD + "LOCUST SWARM"),
                        Meteor.legacy(ChatColor.GRAY + "They're eating your crops! Swat them!")));
            }
            Bukkit.broadcastMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "A locust swarm descends on a farm" + where() + ChatColor.GRAY + " (drive it off before it eats everything)");
        }

        @Override boolean tick() {
            t++;
            swarm.removeIf(s -> { if (!s.alive()) { s.remove(); return true; } return false; });
            if (swarm.isEmpty()) {
                for (UUID u : defenders) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null) continue;
                    for (String line : plugin.getConfig().getStringList("weather.locusts.rewards")) { ItemStack it = parse(line); if (it != null) p.getWorld().dropItemNaturally(p.getLocation(), it); }
                    if (plugin.getConfig().getStringList("weather.locusts.rewards").isEmpty()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(Material.EMERALD, 3 + random.nextInt(4)));
                        p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(Material.BONE_MEAL, 16));
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " locust_swarms 1");
                }
                Bukkit.broadcastMessage(ChatColor.YELLOW + "The locust swarm is driven off!" + ChatColor.GRAY + " (" + eaten + " crops lost)");
                return true;
            }
            if (System.currentTimeMillis() > endsAt) {
                Bukkit.broadcastMessage(ChatColor.YELLOW + "The locust swarm moves on, full..." + ChatColor.GRAY + " (" + eaten + " crops lost)");
                return true;
            }
            for (Swarmer s : swarm) s.follow();
            if (t % 15 == 0) world.playSound(farm, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, SoundCategory.HOSTILE, 2f, 1.6f);
            if (t % 40 == 0) for (Swarmer s : swarm) {
                Block crop = cropNear(s.body.getLocation(), 6);
                if (crop == null) crop = cropNear(farm, 14);
                if (crop == null || !(crop.getBlockData() instanceof Ageable a)) continue;
                if (a.getAge() == 0) continue;
                a.setAge(0);
                crop.setBlockData(a, false);
                eaten++;
                world.spawnParticle(Particle.BLOCK, crop.getLocation().add(0.5, 0.4, 0.5), 8, 0.3, 0.2, 0.3, 0, Material.HAY_BLOCK.createBlockData());
                world.playSound(crop.getLocation(), Sound.BLOCK_CROP_BREAK, SoundCategory.HOSTILE, 0.7f, 1.3f);
            }
            return false;
        }

        Block cropNear(Location l, int r) {
            for (int i = 0; i < 12; i++) {
                Block b = l.getWorld().getBlockAt(l.getBlockX() + random.nextInt(r * 2 + 1) - r, l.getBlockY() - 1 + random.nextInt(4) - 2, l.getBlockZ() + random.nextInt(r * 2 + 1) - r);
                if (CROPS.contains(b.getType()) && b.getBlockData() instanceof Ageable a && a.getAge() > 0) return b;
            }
            return null;
        }

        @Override void finish(boolean natural) { for (Swarmer s : swarm) s.remove(); swarm.clear(); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLocustTarget(EntityTargetEvent e) {
        if (e.getEntity().getScoreboardTags().contains(LOCUST_TAG)) e.setCancelled(true); // they want the crops, not you
    }

    @EventHandler(ignoreCancelled = true)
    public void onLocustHit(EntityDamageByEntityEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(LOCUST_TAG)) return;
        Player p = e.getDamager() instanceof Player pl ? pl : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (p != null && active.get("locusts") instanceof Locusts l) l.defenders.add(p.getUniqueId());
    }

    @EventHandler
    public void onWeatherMobDeath(EntityDeathEvent e) {
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(LOCUST_TAG)) {
            e.getDrops().clear();
            e.setDroppedExp(3);
            if (random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.BONE_MEAL, 1 + random.nextInt(3)));
            e.getEntity().getWorld().spawnParticle(Particle.ITEM, e.getEntity().getLocation(), 8, 0.2, 0.2, 0.2, 0.05, new ItemStack(Material.WHEAT_SEEDS));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) { // storm mobs don't burn in the (dark) day
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(HUSK_TAG) || t.contains(STRAY_TAG) || t.contains(LOCUST_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (active.get("locusts") instanceof Locusts l) for (Locusts.Swarmer s : l.swarm) if (s.stand.isValid()) { if (bedrock(p)) p.showEntity(plugin, s.stand); else p.hideEntity(plugin, s.stand); }
        if (active.get("eclipse") instanceof Eclipse ec && ec.bar != null && p.getWorld().equals(ec.world)) ec.bar.addPlayer(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (active.get("blizzard") instanceof Blizzard b && b.weathered.remove(e.getPlayer().getUniqueId())) e.getPlayer().resetPlayerWeather();
    }

    // =====================================================================================================
    //  helpers + /fevent
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
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.weather")) {
            if (active.isEmpty()) sender.sendMessage(ChatColor.GRAY + "The skies are calm.");
            else for (Event e : active.values()) sender.sendMessage(ChatColor.YELLOW + "Happening now: " + ChatColor.WHITE + e.kind + e.where());
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        Player p = sender instanceof Player pl ? pl : null;
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
        if (!KINDS.contains(sub)) { sender.sendMessage(ChatColor.YELLOW + "/fevent <aurora|sandstorm|blizzard|eclipse|locusts|stop [kind]|status>"); return true; }
        if (active.containsKey(sub)) { sender.sendMessage(ChatColor.RED + "That's already happening (/fevent stop " + sub + ")."); return true; }
        World w = p != null ? p.getWorld() : Bukkit.getWorlds().get(0);
        Location at = p != null ? p.getLocation() : null;
        if (sub.equals("locusts") && p != null) {
            Location farm = farmNear(p.getLocation(), 16, 1);
            at = farm != null ? farm : p.getLocation();
        }
        Event e = start(sub, w, at);
        sender.sendMessage(e != null ? ChatColor.GREEN + "Started: " + sub : ChatColor.RED + "Couldn't start that here (stand where it should happen).");
        return true;
    }

}
