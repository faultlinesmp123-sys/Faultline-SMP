package net.faultlinesmp.index;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WATCH (Index 1.6.15, config watch:, data watch.yml, logs/watch-YYYY-MM-DD.log): catches griefers, cheaters and
 * x-rayers, server side, no client mod needed. Everything goes to a daily log file and /watch log; LOG lines also go to
 * the Discord staff logs channel; ALERTS go to online staff (faultlineindex.alerts, [TP] button) and the logs channel
 * pinging discord.logs.alert-role (at most once a minute).
 *
 * X-RAY: every diamond / ancient debris / emerald VEIN a player opens is checked: was it BURIED (every open face of the
 * ore is a block that player dug themselves, so they couldn't have seen it) or EXPOSED (a cave, lava, water, someone
 * else's tunnel)? And how much stone did they dig for it? Alert = within watch.xray.window-minutes, at least
 * min-buried buried veins, mostly buried, and fewer than max-blocks-per-vein stone/netherrack mined per vein (branch
 * mining legit takes hundreds). Ores players placed are skipped (chunk PDC). Lifetime numbers per player in watch.yml.
 *
 * CHEATS: reach (eye to the target's hitbox against their interaction range, allowing for ping and the target's speed),
 * killaura (hits far outside where they look; several different targets in one tick), fly (3 s unsupported in the air
 * without falling), speed (blocks/second far over sprint-jumping, potions counted), nuker (dozens of hard blocks a
 * second, Vein Miner books skipped), scaffold (blocks placed under your feet while moving and not looking down).
 * Each counts violations; enough in a minute = alert. Elytra, riptide, vehicles, knockback, teleports and lag are
 * allowed for. watch.skip-bedrock (true): Bedrock players' touch controls aren't judged by these.
 *
 * DUPES / ILLEGAL: every 30 s each survival player's inventory (and shulker boxes in it, and any container they open) is
 * checked for items survival can't get (bedrock, barriers, command blocks, spawners...), enchantments over the max,
 * unbreakable or over-stacked items (our own custom items are skipped); watch.illegal.remove takes them. Sudden jumps in
 * a player's valuables (diamonds, netherite, totems...) and valuables handed from one player to another are logged.
 *
 * GRIEF: who built each chunk (chunk PDC faultlineindex:builders); breaking built blocks in someone else's build is
 * summed up per player (alert at watch.grief.alert-blocks), TNT / end crystals blowing up builds (with who lit them),
 * other players' pets, villagers and name-tagged mobs killed, item frames / armor stands / paintings broken.
 *
 * ACCOUNTS: alts (a salted hash of each IP, never the IP itself), and an alert when someone shares one with a BANNED
 * player; creative inventory takes and switches to creative/spectator are logged.
 *
 * /watch <player> | log [player] [n] | xray [player] | alts <player> | check <player> | alerts.
 */
final class Watch implements Listener, CommandExecutor, TabCompleter {

    enum Sev { INFO, LOG, ALERT }

    record Line(long at, UUID who, String name, String kind, Sev sev, String text) { }

    // ---------------------------------------------------------------- tables

    enum Family {
        DIAMOND("diamond", false), DEBRIS("ancient debris", true), EMERALD("emerald", false);
        final String label; final boolean nether;
        Family(String label, boolean nether) { this.label = label; this.nether = nether; }
    }

    static Family family(Material m) {
        return switch (m.name()) {
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> Family.DIAMOND;
            case "ANCIENT_DEBRIS" -> Family.DEBRIS;
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> Family.EMERALD;
            default -> null;
        };
    }

    /** Blocks that count as "digging" for the x-ray ratio. */
    static final Set<String> STONE = Set.of("STONE", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE", "CALCITE", "DRIPSTONE_BLOCK", "SMOOTH_BASALT");
    static final Set<String> NETHER_STONE = Set.of("NETHERRACK", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL");

    /** Blocks that grow / generate (breaking these in someone's chunk isn't "breaking their build"). */
    static final Set<String> NATURAL = Set.of("STONE", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE", "CALCITE", "DIRT", "GRASS_BLOCK",
            "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM", "SAND", "RED_SAND", "GRAVEL", "CLAY", "MUD", "SNOW", "SNOW_BLOCK", "ICE",
            "PACKED_ICE", "NETHERRACK", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL", "END_STONE", "SANDSTONE", "RED_SANDSTONE",
            "TERRACOTTA", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "VINE", "KELP", "KELP_PLANT", "SEAGRASS",
            "TALL_SEAGRASS", "SUGAR_CANE", "BAMBOO", "CACTUS", "MOSS_BLOCK", "MOSS_CARPET", "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE",
            "MAGMA_BLOCK", "OBSIDIAN", "COBWEB", "GLOW_LICHEN", "SCULK", "SCULK_VEIN", "AMETHYST_BLOCK", "SMOOTH_BASALT", "BEDROCK");

    static final List<String> DEFAULT_ILLEGAL = List.of("BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK", "LIGHT", "KNOWLEDGE_BOOK",
            "END_PORTAL_FRAME", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "PETRIFIED_OAK_SLAB",
            "FARMLAND", "DIRT_PATH", "FROGSPAWN", "TEST_BLOCK", "TEST_INSTANCE_BLOCK");

    /** "Diamond value" of the things dupers and thieves move around. */
    static final Map<String, Double> DEFAULT_VALUE = Map.ofEntries(Map.entry("DIAMOND", 1.0), Map.entry("DIAMOND_BLOCK", 9.0),
            Map.entry("NETHERITE_SCRAP", 2.0), Map.entry("NETHERITE_INGOT", 8.0), Map.entry("NETHERITE_BLOCK", 72.0), Map.entry("ANCIENT_DEBRIS", 2.0),
            Map.entry("TOTEM_OF_UNDYING", 4.0), Map.entry("ENCHANTED_GOLDEN_APPLE", 16.0), Map.entry("ELYTRA", 32.0), Map.entry("BEACON", 16.0),
            Map.entry("NETHER_STAR", 12.0), Map.entry("EMERALD_BLOCK", 2.0), Map.entry("GOLD_BLOCK", 1.0), Map.entry("NETHERITE_UPGRADE_SMITHING_TEMPLATE", 6.0),
            Map.entry("HEAVY_CORE", 8.0), Map.entry("PLAYER_HEAD", 4.0), Map.entry("DIAMOND_SWORD", 2.0), Map.entry("DIAMOND_PICKAXE", 3.0),
            Map.entry("DIAMOND_HELMET", 5.0), Map.entry("DIAMOND_CHESTPLATE", 8.0), Map.entry("DIAMOND_LEGGINGS", 7.0), Map.entry("DIAMOND_BOOTS", 4.0),
            Map.entry("NETHERITE_SWORD", 10.0), Map.entry("NETHERITE_PICKAXE", 11.0), Map.entry("NETHERITE_HELMET", 13.0),
            Map.entry("NETHERITE_CHESTPLATE", 16.0), Map.entry("NETHERITE_LEGGINGS", 15.0), Map.entry("NETHERITE_BOOTS", 12.0));

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC),
            TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC), SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);

    // ---------------------------------------------------------------- state

    /** One x-ray vein. */
    record Vein(long at, Family fam, String world, int x, int y, int z, boolean buried, long dugBefore) { }

    /** Per-player mining record (x-ray). */
    static final class Miner {
        final long[] minute = new long[60];
        final int[] stone = new int[60], nether = new int[60];
        long stoneTotal, netherTotal, lastActive;
        final Map<Family, Long> lastVeinDug = new EnumMap<>(Family.class);
        final Deque<Vein> veins = new ArrayDeque<>();
        /** Positions this player dug recently (packed), oldest first. */
        final LinkedHashSet<Long> dug = new LinkedHashSet<>();
        final Deque<long[]> recentOres = new ArrayDeque<>(); // {time, family ordinal, x, y, z}

        void add(boolean isNether, long now) {
            long m = now / 60000;
            int i = (int) (m % 60);
            if (minute[i] != m) { minute[i] = m; stone[i] = 0; nether[i] = 0; }
            if (isNether) { nether[i]++; netherTotal++; } else { stone[i]++; stoneTotal++; }
        }

        int dugIn(boolean isNether, int minutes, long now) {
            long m = now / 60000;
            int sum = 0;
            for (int i = 0; i < 60; i++) if (minute[i] > m - minutes && minute[i] <= m) sum += isNether ? nether[i] : stone[i];
            return sum;
        }
    }

    /** Per-player movement / combat record. */
    static final class Mover {
        Location last; long lastVelocity, lastTeleport, lastHitTick = -1;
        int hover, fast, nukeSecond, nukeCount, nukeRun, placeSecond, placeCount, placeRun;
        double speed; // horizontal blocks/second over the last second
        final Set<UUID> hitThisTick = new HashSet<>();
    }

    /** Violations within the last minute per kind (for alerts), plus totals this session. */
    static final class Vl {
        final Map<String, Deque<Long>> recent = new HashMap<>();
        final Map<String, Integer> session = new HashMap<>();
        int add(String kind, long now, long windowMs) {
            Deque<Long> d = recent.computeIfAbsent(kind, k -> new ArrayDeque<>());
            d.add(now);
            while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.pollFirst();
            session.merge(kind, 1, Integer::sum);
            return d.size();
        }
    }

    /** Someone breaking blocks in someone else's build: summed up into one line. */
    static final class Run {
        final UUID breaker, owner; final String breakerName, ownerName; final Location first;
        final Map<Material, Integer> kinds = new HashMap<>();
        int count; long started, lastAt; boolean alerted;
        Run(Player p, UUID owner, String ownerName, Location at, long now) {
            this.breaker = p.getUniqueId(); this.breakerName = p.getName(); this.owner = owner; this.ownerName = ownerName; this.first = at; started = lastAt = now;
        }
    }

    private final FaultlineIndex index;
    private final File dataFile, logDir;
    private YamlConfiguration data;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Faultline-Watch-log"); t.setDaemon(true); return t; });
    private final Deque<Line> ring = new ArrayDeque<>();
    final Map<UUID, Miner> miners = new HashMap<>();
    final Map<UUID, Mover> movers = new HashMap<>();
    final Map<UUID, Vl> vls = new HashMap<>();
    private final Map<String, Long> alerted = new HashMap<>(), throttled = new HashMap<>();
    private final Set<UUID> muted = new HashSet<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<UUID, Double> lastValue = new HashMap<>();
    private final Map<UUID, Map<Material, Integer>> lastCounts = new HashMap<>();
    private final Map<String, long[]> handoffs = new HashMap<>(); // "from|to|material" -> {amount, first ms}
    private final Map<UUID, Map<Material, Integer>> creative = new HashMap<>();
    private final Map<UUID, UUID> crystalHitBy = new HashMap<>();
    private final Map<Long, Map<UUID, Integer>> builders = new HashMap<>();
    private final Set<Long> dirtyChunks = new HashSet<>();
    private final NamespacedKey placedOres, buildersKey;
    private Set<Material> illegal = Set.of();
    private Map<Material, Double> value = Map.of();
    private boolean dirty;

    Watch(FaultlineIndex index) {
        this.index = index;
        placedOres = new NamespacedKey(index, "placed_ores");
        buildersKey = new NamespacedKey(index, "builders");
        dataFile = new File(index.getDataFolder(), "watch.yml");
        logDir = new File(index.getDataFolder(), "logs");
        data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();
        if (data.getString("salt", "").isEmpty()) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            data.set("salt", HexFormat.of().formatHex(b));
            dirty = true;
        }
        for (String s : data.getStringList("muted")) try { muted.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        reload();
        Bukkit.getScheduler().runTaskTimer(index, this::second, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(index, this::scanAll, 20L * 30, 20L * 30);
        Bukkit.getScheduler().runTaskTimer(index, () -> { flushChunks(); save(); }, 20L * 300, 20L * 300);
    }

    void reload() {
        Set<Material> ill = new HashSet<>();
        for (String s : cfg().isList("illegal.items") ? cfg().getStringList("illegal.items") : DEFAULT_ILLEGAL) {
            Material m = Material.matchMaterial(s);
            if (m != null) ill.add(m);
        }
        illegal = ill;
        Map<Material, Double> v = new HashMap<>();
        DEFAULT_VALUE.forEach((k, d) -> { Material m = Material.matchMaterial(k); if (m != null) v.put(m, d); });
        ConfigurationSection vs = cfg().getConfigurationSection("value.worth");
        if (vs != null) for (String k : vs.getKeys(false)) { Material m = Material.matchMaterial(k); if (m != null) v.put(m, vs.getDouble(k)); }
        for (Material m : Material.values()) if (!m.isLegacy() && Tag.SHULKER_BOXES.isTagged(m)) v.putIfAbsent(m, 1.0);
        value = v;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = index.getConfig().getConfigurationSection("watch");
        return c != null ? c : index.getConfig().createSection("watch");
    }

    boolean enabled(String what) { return index.getConfig().getBoolean("watch.enabled", true) && index.getConfig().getBoolean("watch." + what + ".enabled", true); }

    // ---------------------------------------------------------------- output

    /**
     * Records one thing. INFO = file + /watch log; LOG = + Discord logs channel; ALERT = + staff in game + Discord with
     * the alert role (once per player per kind per watch.alert-cooldown-minutes; later ones are LOG lines).
     */
    void record(UUID who, String name, String kind, Sev sev, String text, Location at) {
        long now = System.currentTimeMillis();
        if (sev == Sev.ALERT) {
            String key = who + "|" + kind;
            Long last = alerted.get(key);
            if (last != null && now - last < (long) (cfg().getDouble("alert-cooldown-minutes", 5) * 60000)) sev = Sev.LOG;
            else alerted.put(key, now);
        }
        Line line = new Line(now, who, name, kind, sev, text);
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > cfg().getInt("memory-lines", 2000)) ring.pollFirst();
        }
        if (cfg().getBoolean("log-file", true)) {
            String plain = "[" + TIME.format(Instant.ofEpochMilli(now)) + "] [" + sev + "] [" + kind + "] " + text.replace("**", "").replace("\\", "") + "\n";
            String day = DAY.format(Instant.ofEpochMilli(now));
            writer.execute(() -> {
                try {
                    if (!logDir.exists()) logDir.mkdirs();
                    try (Writer w = new FileWriter(new File(logDir, "watch-" + day + ".log"), StandardCharsets.UTF_8, true)) { w.write(plain); }
                } catch (IOException e) { index.getLogger().warning("Couldn't write the watch log: " + e.getMessage()); }
            });
        }
        Discord d = index.discord;
        if (sev == Sev.ALERT) {
            if (d != null) d.alert("🚨 " + text);
            Component msg = Component.text("[Watch] ", NamedTextColor.RED).append(Component.text(ChatColor.stripColor(text.replace("**", "").replace("\\", "")), NamedTextColor.YELLOW));
            if (at != null && at.getWorld() != null && name != null) {
                msg = msg.append(Component.text(" [TP]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/minecraft:tp @s " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()))
                        .hoverEvent(HoverEvent.showText(Component.text("Teleport there (" + Discord.where(at) + ")"))));
                msg = msg.append(Component.text(" [INFO]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/watch " + name))
                        .hoverEvent(HoverEvent.showText(Component.text("/watch " + name))));
            }
            for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission("faultlineindex.alerts") && !muted.contains(s.getUniqueId())) s.sendMessage(msg);
            index.getLogger().warning("[Watch] " + text.replace("**", "").replace("\\", ""));
        } else if (sev == Sev.LOG) {
            if (d != null) d.log(text);
            if (cfg().getBoolean("ingame-logs", false)) {
                for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission("faultlineindex.alerts") && !muted.contains(s.getUniqueId()))
                    s.sendMessage(ChatColor.DARK_GRAY + "[Watch] " + ChatColor.GRAY + text.replace("**", "").replace("\\", ""));
            }
        }
    }

    void record(Player p, String kind, Sev sev, String text) { record(p.getUniqueId(), p.getName(), kind, sev, text, p.getLocation()); }

    static String b(String name) { return "**" + Discord.esc(name) + "**"; }

    List<Line> lines(UUID who, int n) {
        List<Line> out = new ArrayList<>();
        synchronized (ring) {
            var it = ring.descendingIterator();
            while (it.hasNext() && out.size() < n) { Line l = it.next(); if (who == null || who.equals(l.who())) out.add(l); }
        }
        return out;
    }

    /** A violation: counted; once watch.<kind>.count are inside watch.<kind>.window-seconds, an alert. */
    private void violation(Player p, String kind, String detail, int defCount) {
        long now = System.currentTimeMillis();
        int n = vls.computeIfAbsent(p.getUniqueId(), k -> new Vl()).add(kind, now, (long) (cfg().getDouble(kind + ".window-seconds", 60) * 1000));
        String path = "players." + p.getUniqueId() + ".vl." + kind;
        data.set(path, data.getInt(path) + 1);
        dirty = true;
        int need = cfg().getInt(kind + ".count", defCount);
        record(p, kind, n >= need ? Sev.ALERT : Sev.INFO, (n >= need ? "**" + kind.toUpperCase(Locale.ROOT) + "?** " : kind + ": ") + b(p.getName()) + " " + detail
                + (n >= need ? " (" + n + " times in " + cfg().getInt(kind + ".window-seconds", 60) + " s)" : "") + " at " + Discord.where(p.getLocation()));
    }

    private boolean judged(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        if (p.hasPermission("faultlineindex.watch.exempt")) return false;
        return !(cfg().getBoolean("skip-bedrock", true) && Edition.bedrock(p));
    }

    // ---------------------------------------------------------------- x-ray

    static long pack(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    static int local(Block b) { return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8); }

    boolean placedOre(Block b) {
        long[] a = b.getChunk().getPersistentDataContainer().get(placedOres, PersistentDataType.LONG_ARRAY);
        if (a == null) return false;
        int l = local(b);
        for (long v : a) if (v == l) return true;
        return false;
    }

    private void markOre(Block b, boolean add) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] a = pdc.get(placedOres, PersistentDataType.LONG_ARRAY);
        int l = local(b);
        List<Long> list = new ArrayList<>();
        if (a != null) for (long v : a) if (v != l) list.add(v);
        if (add) list.add((long) l);
        if (list.size() > 512) list = list.subList(list.size() - 512, list.size());
        if (list.isEmpty()) pdc.remove(placedOres);
        else pdc.set(placedOres, PersistentDataType.LONG_ARRAY, list.stream().mapToLong(Long::longValue).toArray());
    }

    private void mine(Player p, Block b) {
        if (!enabled("xray")) return;
        Material m = b.getType();
        String n = m.name();
        long now = System.currentTimeMillis();
        Miner mi = miners.computeIfAbsent(p.getUniqueId(), k -> new Miner());
        mi.lastActive = now;
        long pos = pack(b.getX(), b.getY(), b.getZ());
        mi.dug.add(pos);
        if (mi.dug.size() > 6000) { var it = mi.dug.iterator(); for (int i = 0; i < 1000 && it.hasNext(); i++) { it.next(); it.remove(); } }
        if (STONE.contains(n)) { mi.add(false, now); return; }
        if (NETHER_STONE.contains(n)) { mi.add(true, now); return; }
        Family f = family(m);
        if (f == null) return;
        if (placedOre(b)) { markOre(b, false); return; }
        // same vein = an ore of this family dug within 4 blocks in the last 2 minutes
        while (!mi.recentOres.isEmpty() && now - mi.recentOres.peekFirst()[0] > 120000) mi.recentOres.pollFirst();
        boolean sameVein = false;
        for (long[] r : mi.recentOres) {
            if (r[1] == f.ordinal() && Math.abs(r[2] - b.getX()) <= 4 && Math.abs(r[3] - b.getY()) <= 4 && Math.abs(r[4] - b.getZ()) <= 4) { sameVein = true; break; }
        }
        mi.recentOres.add(new long[]{now, f.ordinal(), b.getX(), b.getY(), b.getZ()});
        if (sameVein) return;
        boolean buried = buried(mi, b);
        long dugNow = f.nether ? mi.netherTotal : mi.stoneTotal;
        Long prev = mi.lastVeinDug.put(f, dugNow);
        long since = prev == null ? -1 : dugNow - prev;
        Vein v = new Vein(now, f, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), buried, since);
        mi.veins.add(v);
        int window = cfg().getInt("xray.window-minutes", 20);
        while (!mi.veins.isEmpty() && now - mi.veins.peekFirst().at() > window * 60000L * 3) mi.veins.pollFirst();
        String base = "players." + p.getUniqueId() + ".xray." + f.name().toLowerCase(Locale.ROOT);
        data.set(base + ".veins", data.getInt(base + ".veins") + 1);
        if (buried) data.set(base + ".buried", data.getInt(base + ".buried") + 1);
        data.set("players." + p.getUniqueId() + ".xray.stone", mi.stoneTotal + data.getLong("players." + p.getUniqueId() + ".xray.stone-saved"));
        data.set("players." + p.getUniqueId() + ".name", p.getName());
        dirty = true;
        boolean discordVeins = cfg().getBoolean("xray.discord-veins", true) && f != Family.EMERALD;
        record(p, "vein", discordVeins ? Sev.LOG : Sev.INFO, (f == Family.DEBRIS ? "🟫 " : f == Family.DIAMOND ? "💎 " : "🟩 ") + b(p.getName()) + " found "
                + f.label + " at " + Discord.where(b.getLocation()) + " (" + (buried ? "BURIED: no cave or tunnel of anyone else's next to it" : "exposed")
                + (since >= 0 ? ", " + since + " blocks dug since their last " + f.label : "") + ")");
        judgeXray(p, mi, f, now);
    }

    /** Buried = every face of the ore that isn't solid rock is a block this player dug out themselves. */
    boolean buried(Miner mi, Block b) {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            Block n = b.getRelative(f[0], f[1], f[2]);
            if (n.getType().isOccluding()) continue;
            if (mi.dug.contains(pack(n.getX(), n.getY(), n.getZ()))) continue;
            return false;
        }
        return true;
    }

    void judgeXray(Player p, Miner mi, Family f, long now) {
        int window = cfg().getInt("xray.window-minutes", 20);
        String k = "xray." + f.name().toLowerCase(Locale.ROOT) + ".";
        int minBuried = cfg().getInt(k + "min-buried", f == Family.DEBRIS ? 3 : 4);
        double maxPer = cfg().getDouble(k + "max-blocks-per-vein", f == Family.DEBRIS ? 45 : 60);
        double share = cfg().getDouble("xray.buried-share", 0.6);
        int veins = 0, buried = 0;
        List<Vein> last = new ArrayList<>();
        for (Vein v : mi.veins) if (v.fam() == f && now - v.at() <= window * 60000L) { veins++; if (v.buried()) { buried++; last.add(v); } }
        if (buried < minBuried || buried < veins * share) return;
        int dug = mi.dugIn(f.nether, window, now);
        double per = dug / (double) buried;
        if (per >= maxPer) return;
        StringBuilder spots = new StringBuilder();
        for (int i = Math.max(0, last.size() - 4); i < last.size(); i++) {
            Vein v = last.get(i);
            spots.append(spots.length() > 0 ? ", " : "").append(v.x()).append(' ').append(v.y()).append(' ').append(v.z());
        }
        String path = "players." + p.getUniqueId() + ".vl.xray";
        data.set(path, data.getInt(path) + 1);
        dirty = true;
        // 1.6.22: a Miner's Lantern Charm (FaultlineItems) really shows ores within 4 blocks: a log line, not an alert
        boolean lantern = false;
        for (var m : p.getMetadata("faultline_ore_sense")) if (m.asBoolean()) lantern = true;
        record(p, "xray-" + f.name().toLowerCase(Locale.ROOT), lantern ? Sev.LOG : Sev.ALERT, (lantern ? "(wears a Miner's Lantern Charm) " : "") + "⛏️ **X-RAY?** " + b(p.getName()) + ": " + buried + " of " + veins + " " + f.label
                + " veins in " + window + " min were fully BURIED, and they dug only " + dug + " " + (f.nether ? "netherrack/basalt" : "stone") + " for them ("
                + Math.round(per) + " per vein; legit branch mining takes hundreds). Last veins: " + spots + " in " + p.getWorld().getName());
    }

    // ---------------------------------------------------------------- blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Material m = b.getType();
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            mine(p, b);
            if (m == Material.BEDROCK) record(p, "bedrock", Sev.ALERT, "🧱 **BEDROCK BROKEN** by " + b(p.getName()) + " in survival at " + Discord.where(b.getLocation()));
            nuker(p, b);
        }
        buildBreak(p, b, m);
    }

    private void nuker(Player p, Block b) {
        if (!enabled("nuker") || !judged(p) || b.getType().getHardness() < 0.5f) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool.hasItemMeta()) {
            String fe = tool.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("faultlineitems", "fenchants"), PersistentDataType.STRING);
            if (fe != null && fe.contains("veinminer")) return;
        }
        Mover mv = movers.computeIfAbsent(p.getUniqueId(), k -> new Mover());
        int sec = (int) (System.currentTimeMillis() / 1000);
        if (mv.nukeSecond != sec) { mv.nukeRun = (mv.nukeSecond == sec - 1 && mv.nukeCount > cfg().getInt("nuker.blocks-per-second", 22)) ? mv.nukeRun + 1 : 0; mv.nukeSecond = sec; mv.nukeCount = 0; }
        mv.nukeCount++;
        if (mv.nukeCount == cfg().getInt("nuker.blocks-per-second", 22) + 1 && mv.nukeRun >= 1)
            violation(p, "nuker", "broke " + mv.nukeCount + "+ hard blocks in one second, two seconds running", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        if (family(b.getType()) != null) markOre(b, true);
        if (p.getGameMode() != GameMode.SPECTATOR) noteBuilder(b, p.getUniqueId(), 1);
        if (!judged(p) || !enabled("scaffold")) return;
        Mover mv = movers.computeIfAbsent(p.getUniqueId(), k -> new Mover());
        int sec = (int) (System.currentTimeMillis() / 1000);
        int fast = cfg().getInt("scaffold.places-per-second", 12);
        if (mv.placeSecond != sec) { mv.placeRun = (mv.placeSecond == sec - 1 && mv.placeCount > fast) ? mv.placeRun + 1 : 0; mv.placeSecond = sec; mv.placeCount = 0; }
        mv.placeCount++;
        if (mv.placeCount == fast + 1 && mv.placeRun >= 1) violation(p, "fastplace", "placed " + mv.placeCount + "+ blocks a second, two seconds running", 1);
        Location l = p.getLocation();
        boolean under = b.getY() == l.getBlockY() - 1 && Math.abs(b.getX() + 0.5 - l.getX()) < 1.3 && Math.abs(b.getZ() + 0.5 - l.getZ()) < 1.3;
        if (under && l.getPitch() < cfg().getDouble("scaffold.max-pitch", 45) && mv.speed > 3.5 && !p.isFlying())
            violation(p, "scaffold", "placed a block under their feet while running and looking " + Math.round(l.getPitch()) + "° (not down)", cfg().getInt("scaffold.count", 6));
    }

    // ---------------------------------------------------------------- builds (chunk builders)

    private long chunkKey(Chunk c) { return ((long) c.getWorld().getUID().hashCode() << 42) ^ (((long) c.getX() & 0x1FFFFF) << 21) ^ ((long) c.getZ() & 0x1FFFFF); }

    Map<UUID, Integer> builders(Chunk c) {
        long k = chunkKey(c);
        Map<UUID, Integer> m = builders.get(k);
        if (m != null) return m;
        m = new HashMap<>();
        String s = c.getPersistentDataContainer().get(buildersKey, PersistentDataType.STRING);
        if (s != null) for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) try { m.put(UUID.fromString(part.substring(0, eq)), Integer.parseInt(part.substring(eq + 1))); } catch (IllegalArgumentException ignored) { }
        }
        builders.put(k, m);
        return m;
    }

    private void noteBuilder(Block b, UUID who, int n) {
        Chunk c = b.getChunk();
        Map<UUID, Integer> m = builders(c);
        m.merge(who, n, Integer::sum);
        if (m.size() > 8) { // keep the biggest builders
            UUID least = m.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (least != null && !least.equals(who)) m.remove(least);
        }
        dirtyChunks.add(chunkKey(c));
    }

    private void writeChunk(Chunk c) {
        long k = chunkKey(c);
        Map<UUID, Integer> m = builders.get(k);
        if (m == null) return;
        if (dirtyChunks.remove(k)) {
            StringBuilder s = new StringBuilder();
            m.forEach((u, n) -> s.append(s.length() > 0 ? "," : "").append(u).append('=').append(n));
            c.getPersistentDataContainer().set(buildersKey, PersistentDataType.STRING, s.toString());
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        writeChunk(e.getChunk());
        builders.remove(chunkKey(e.getChunk()));
    }

    void flushChunks() {
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) if (dirtyChunks.contains(chunkKey(c))) writeChunk(c);
        dirtyChunks.clear();
    }

    /** The chunk's main builder if it isn't this player (and this player built far less there): {uuid, count}. */
    Map.Entry<UUID, Integer> otherBuilder(Block b, UUID me) {
        Map<UUID, Integer> m = builders(b.getChunk());
        Map.Entry<UUID, Integer> top = m.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null || top.getKey().equals(me) || top.getValue() < cfg().getInt("grief.min-built", 20)) return null;
        if (m.getOrDefault(me, 0) * 4 >= top.getValue()) return null; // they built a good part of it too
        if (cfg().getBoolean("grief.ignore-same-kingdom", false)) {
            int a = Grief.kingdom(me), c = Grief.kingdom(top.getKey());
            if (a > 0 && a == c) return null;
        }
        return top;
    }

    static String nameOf(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() != null ? o.getName() : u.toString().substring(0, 8);
    }

    private void buildBreak(Player p, Block b, Material m) {
        if (!enabled("grief") || p.getGameMode() == GameMode.CREATIVE && !cfg().getBoolean("grief.count-creative", true)) return;
        if (NATURAL.contains(m.name()) || m.name().endsWith("_ORE") || m.name().endsWith("_LEAVES") || m.name().endsWith("_LOG") && !m.name().startsWith("STRIPPED")
                || Tag.FLOWERS.isTagged(m) || Tag.CROPS.isTagged(m)) return;
        Map.Entry<UUID, Integer> o = otherBuilder(b, p.getUniqueId());
        if (o == null) return;
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + "|" + o.getKey();
        Run r = runs.computeIfAbsent(key, k -> new Run(p, o.getKey(), nameOf(o.getKey()), b.getLocation(), now));
        r.count++; r.lastAt = now;
        r.kinds.merge(m, 1, Integer::sum);
        int alertAt = cfg().getInt("grief.alert-blocks", 64);
        if (!r.alerted && r.count >= alertAt) {
            r.alerted = true;
            record(p.getUniqueId(), p.getName(), "grief", Sev.ALERT, "🧱 **GRIEFING?** " + b(p.getName()) + " has broken " + r.count + " blocks in " + b(r.ownerName)
                    + "'s build (" + kinds(r.kinds) + ") at " + Discord.where(r.first), r.first);
        }
    }

    static String kinds(Map<Material, Integer> k) {
        StringBuilder s = new StringBuilder();
        int shown = 0;
        for (var e : k.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).toList()) {
            if (shown++ == 5) { s.append(", ..."); break; }
            s.append(s.length() > 0 ? ", " : "").append(e.getValue()).append(' ').append(Discord.pretty(e.getKey()));
        }
        return s.toString();
    }

    void flushRuns(long now) {
        long quiet = (long) (cfg().getDouble("grief.summary-seconds", 20) * 1000);
        runs.entrySet().removeIf(en -> {
            Run r = en.getValue();
            if (now - r.lastAt < quiet && now - r.started < quiet * 4) return false;
            if (!r.alerted || r.count > cfg().getInt("grief.alert-blocks", 64))
                record(r.breaker, r.breakerName, "build-break", r.count >= cfg().getInt("grief.log-blocks", 8) ? Sev.LOG : Sev.INFO, "🧱 " + b(r.breakerName) + " broke "
                        + r.count + " blocks in " + b(r.ownerName) + "'s build (" + kinds(r.kinds) + ") at " + Discord.where(r.first), r.first);
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Entity by = e.getRemover();
        if (by instanceof Projectile pr && pr.getShooter() instanceof Entity s) by = s;
        if (!(by instanceof Player p) || !enabled("grief")) return;
        Map.Entry<UUID, Integer> o = otherBuilder(e.getEntity().getLocation().getBlock(), p.getUniqueId());
        if (o == null) return;
        record(p.getUniqueId(), p.getName(), "frame", Sev.LOG, "🖼️ " + b(p.getName()) + " broke a " + e.getEntity().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " in " + b(nameOf(o.getKey())) + "'s build at " + Discord.where(e.getEntity().getLocation()), e.getEntity().getLocation());
    }

    // ---------------------------------------------------------------- explosions

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) return;
        Entity by = e.getDamager();
        if (by instanceof Projectile pr && pr.getShooter() instanceof Entity s) by = s;
        if (by instanceof Player p) {
            crystalHitBy.put(e.getEntity().getUniqueId(), p.getUniqueId());
            if (crystalHitBy.size() > 200) crystalHitBy.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!enabled("grief")) return;
        UUID who = null;
        String what;
        if (e.getEntity() instanceof TNTPrimed t) {
            Entity s = t.getSource();
            if (s instanceof Projectile pr && pr.getShooter() instanceof Entity sh) s = sh;
            if (s instanceof Player p) who = p.getUniqueId();
            what = "TNT";
        } else if (e.getEntity() instanceof EnderCrystal) {
            who = crystalHitBy.remove(e.getEntity().getUniqueId());
            what = "An end crystal";
        } else return;
        int built = 0;
        Map<UUID, Integer> owners = new HashMap<>();
        for (Block b : e.blockList()) {
            if (NATURAL.contains(b.getType().name())) continue;
            built++;
            Map.Entry<UUID, Integer> o = otherBuilder(b, who == null ? new UUID(0, 0) : who);
            if (o != null) owners.merge(o.getKey(), 1, Integer::sum);
        }
        if (built < cfg().getInt("grief.explosion-min-blocks", 6)) return;
        String whoName = who == null ? null : nameOf(who);
        String ownersText = owners.isEmpty() ? "" : " in " + String.join(", ", owners.keySet().stream().map(u -> b(nameOf(u)) + "'s").toList()) + " build";
        boolean big = !owners.isEmpty() && owners.values().stream().mapToInt(Integer::intValue).sum() >= cfg().getInt("grief.explosion-alert-blocks", 30);
        record(who, whoName, "explosion", big ? Sev.ALERT : Sev.LOG, "💥 " + what + (whoName != null ? " from " + b(whoName) : " (nobody known)") + " destroyed " + built
                + " placed blocks" + ownersText + " at " + Discord.where(e.getLocation()), e.getLocation());
    }

    // ---------------------------------------------------------------- kills

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || v instanceof Player || !enabled("kills")) return;
        for (String t : v.getScoreboardTags()) if (t.startsWith("faultline")) return; // our bosses / raid mobs
        if (v instanceof Tameable t && t.isTamed() && t.getOwner() != null && !t.getOwner().getUniqueId().equals(k.getUniqueId())) {
            record(k.getUniqueId(), k.getName(), "pet", Sev.LOG, "🐾 " + b(k.getName()) + " killed " + b(t.getOwner().getName() == null ? "someone" : t.getOwner().getName())
                    + "'s " + pretty(v) + " at " + Discord.where(v.getLocation()), v.getLocation());
        } else if (v instanceof Villager) {
            if (throttle(k.getUniqueId() + "|villager", 30000)) record(k.getUniqueId(), k.getName(), "villager", Sev.LOG, "🧑‍🌾 " + b(k.getName()) + " killed a villager"
                    + (v.getCustomName() != null ? " named \"" + Discord.esc(ChatColor.stripColor(v.getCustomName())) + "\"" : "") + " at " + Discord.where(v.getLocation()), v.getLocation());
        } else if (v.getCustomName() != null && !v.getCustomName().contains("§")) {
            record(k.getUniqueId(), k.getName(), "named", Sev.LOG, "🏷️ " + b(k.getName()) + " killed a name-tagged " + pretty(v) + " \"" + Discord.esc(v.getCustomName()) + "\" at "
                    + Discord.where(v.getLocation()), v.getLocation());
        }
    }

    static String pretty(Entity e) { return e.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + (e.getCustomName() != null && e instanceof Tameable ? " \"" + Discord.esc(ChatColor.stripColor(e.getCustomName())) + "\"" : ""); }

    private boolean throttle(String key, long ms) {
        long now = System.currentTimeMillis();
        Long last = throttled.get(key);
        if (last != null && now - last < ms) return false;
        throttled.put(key, now);
        return true;
    }

    // ---------------------------------------------------------------- combat

    /**
     * Paper's PrePlayerAttackEntityEvent fires only for a real attack packet (never for a plugin's damage(x, player):
     * staffs, cleaves, forwarded boss hitboxes), so area attacks don't look like killaura or reach.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        if (!e.willAttack() || !enabled("combat") || !judged(p) || p.isInsideVehicle()) return;
        Entity v = e.getAttacked();
        if (v instanceof Player vp && vp.isInsideVehicle()) return;
        for (String t : v.getScoreboardTags()) if (t.startsWith("faultline")) return; // boss parts, hitboxes, stands
        Mover mv = movers.computeIfAbsent(p.getUniqueId(), k -> new Mover());
        Location eye = p.getEyeLocation();
        BoundingBox box = v.getBoundingBox();
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())), cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())),
                cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dist = Math.sqrt(sq(cx - eye.getX()) + sq(cy - eye.getY()) + sq(cz - eye.getZ()));
        // reach
        double range = 3.0;
        try {
            AttributeInstance a = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            if (a != null) range = a.getValue();
        } catch (RuntimeException | LinkageError ignored) { }
        double victimSpeed = 0;
        int victimPing = 0;
        if (v instanceof Player vp) {
            Mover vm = movers.get(vp.getUniqueId());
            if (vm != null) victimSpeed = vm.speed;
            victimPing = vp.getPing();
        } else victimSpeed = v.getVelocity().length() * 20;
        // both players' positions are old by their ping: allow for how fast each of them was moving
        double lag = Math.min(2.0, (Math.min(p.getPing(), 400) + Math.min(victimPing, 400)) / 1000.0 * (Math.min(victimSpeed, 12) + Math.min(mv.speed, 12)));
        double limit = range + cfg().getDouble("reach.tolerance", 0.75) + lag;
        if (enabled("reach") && dist > limit)
            violation(p, "reach", String.format(Locale.ROOT, "hit %s from %.2f blocks (allowed %.2f, ping %d)", v instanceof Player vp ? vp.getName() : pretty(v), dist, limit, p.getPing()),
                    cfg().getInt("reach.count", 4));
        // aim: hits far outside where they look
        if (enabled("killaura") && dist > 1.5) {
            Vector to = new Vector(box.getCenterX() - eye.getX(), box.getCenterY() - eye.getY(), box.getCenterZ() - eye.getZ());
            double ang = Math.toDegrees(eye.getDirection().angle(to));
            if (ang > cfg().getDouble("killaura.max-angle", 75))
                violation(p, "killaura", "hit " + (v instanceof Player vp ? vp.getName() : pretty(v)) + " " + Math.round(ang) + "° away from where they looked", cfg().getInt("killaura.count", 6));
        }
        // several different targets in one tick
        if (enabled("killaura")) {
            int tick = Bukkit.getCurrentTick();
            if (mv.lastHitTick != tick) { mv.lastHitTick = tick; mv.hitThisTick.clear(); }
            mv.hitThisTick.add(v.getUniqueId());
            if (mv.hitThisTick.size() == 2) violation(p, "multiaura", "hit " + mv.hitThisTick.size() + " different targets in the same tick", cfg().getInt("multiaura.count", 3));
        }
    }

    static double sq(double d) { return d * d; }

    // ---------------------------------------------------------------- movement (every second)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) { movers.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new Mover()).lastVelocity = System.currentTimeMillis(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Mover m = movers.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new Mover());
        m.lastTeleport = System.currentTimeMillis();
        m.last = null;
        m.hover = 0; m.fast = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) movers.computeIfAbsent(p.getUniqueId(), k -> new Mover()).lastVelocity = System.currentTimeMillis();
    }

    void second() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Mover m = movers.computeIfAbsent(p.getUniqueId(), k -> new Mover());
            Location l = p.getLocation();
            Location prev = m.last;
            m.last = l;
            if (prev == null || prev.getWorld() != l.getWorld()) { m.speed = 0; continue; }
            m.speed = Math.hypot(l.getX() - prev.getX(), l.getZ() - prev.getZ());
            if (!judged(p) || !enabled("movement")) { m.hover = 0; m.fast = 0; continue; }
            boolean excused = !p.hasGravity() || p.isFlying() || p.getAllowFlight() || p.isGliding() || p.isRiptiding() || p.isInsideVehicle() || p.isDead()
                    || now - m.lastVelocity < 2500 || now - m.lastTeleport < 2500 || p.hasPotionEffect(PotionEffectType.LEVITATION);
            if (excused) { m.hover = 0; m.fast = 0; continue; }
            // fly: unsupported in the air for 3 seconds without falling
            double dy = l.getY() - prev.getY();
            if (dy > -0.6 && !p.hasPotionEffect(PotionEffectType.SLOW_FALLING) && unsupported(p, l)) {
                if (++m.hover == cfg().getInt("fly.seconds", 3)) violation(p, "fly", "hung in the air with nothing under them for " + m.hover + " s", 1);
            } else m.hover = 0;
            // speed
            double limit = cfg().getDouble("speed.blocks-per-second", 16);
            try { // Hermes/Spectre boots, the Harpy Ring...: movement speed modifiers (base 0.1)
                AttributeInstance ms = p.getAttribute(Attribute.MOVEMENT_SPEED);
                if (ms != null && ms.getValue() > 0.1) limit *= ms.getValue() / 0.1;
            } catch (RuntimeException | LinkageError ignored) { }
            PotionEffect sp = p.getPotionEffect(PotionEffectType.SPEED);
            if (sp != null) limit += 3 * (sp.getAmplifier() + 1);
            if (p.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE) || p.isSwimming()) limit += 8;
            if (m.speed > limit) {
                if (++m.fast >= 2) violation(p, "speed", String.format(Locale.ROOT, "moved %.1f blocks/second on foot (limit %.1f)", m.speed, limit), cfg().getInt("speed.count", 2));
            } else m.fast = 0;
        }
        flushRuns(now);
        flushHandoffs(now);
        if (alerted.size() > 5000) alerted.clear();
        if (throttled.size() > 5000) throttled.clear();
        flushCreative();
        // forget miners idle for longer than the window (their lifetime numbers stay in watch.yml)
        long idle = cfg().getInt("xray.window-minutes", 20) * 60000L * 3;
        miners.entrySet().removeIf(en -> {
            if (now - en.getValue().lastActive <= idle || Bukkit.getPlayer(en.getKey()) != null) return false;
            String base = "players." + en.getKey() + ".xray.stone-saved";
            data.set(base, data.getLong(base) + en.getValue().stoneTotal);
            return true;
        });
    }

    /** Nothing to stand on, hold on to or swim in, 2 blocks down under any part of them, and no entity to stand on. */
    boolean unsupported(Player p, Location l) {
        World w = l.getWorld();
        double[] off = {-0.31, 0.31};
        int fy = (int) Math.floor(l.getY());
        for (double ox : off) for (double oz : off) {
            int x = (int) Math.floor(l.getX() + ox), z = (int) Math.floor(l.getZ() + oz);
            for (int y = fy; y >= fy - 2; y--) {
                Block b = w.getBlockAt(x, y, z);
                Material t = b.getType();
                if (t.isAir()) continue;
                String n = t.name();
                if (t.isSolid() || b.isLiquid() || Tag.CLIMBABLE.isTagged(t) || t == Material.COBWEB || t == Material.POWDER_SNOW || t == Material.SCAFFOLDING
                        || t == Material.BUBBLE_COLUMN || t == Material.SNOW || t == Material.LILY_PAD || n.contains("SLAB") || n.contains("STAIRS")
                        || n.contains("CARPET") || n.contains("WALL") || n.contains("FENCE")) return false;
            }
        }
        for (Entity e : p.getNearbyEntities(1.5, 3, 1.5)) if (!(e instanceof Item) && !(e instanceof Projectile)) return false;
        return true;
    }

    // ---------------------------------------------------------------- illegal items + valuables (every 30 s)

    void scanAll() {
        if (!index.getConfig().getBoolean("watch.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) scan(p, false);
    }

    /** Checks a player's inventory; returns what was illegal. */
    List<String> scan(Player p, boolean forced) {
        List<String> found = new ArrayList<>();
        if (!forced && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("faultlineindex.watch.exempt"))) {
            lastValue.remove(p.getUniqueId());
            return found;
        }
        Inventory inv = p.getInventory();
        Map<Material, Integer> counts = new HashMap<>();
        ItemStack[] c = inv.getContents();
        boolean remove = cfg().getBoolean("illegal.remove", false);
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType().isAir()) continue;
            String why = illegal(it);
            count(it, counts);
            if (why == null && it.getItemMeta() instanceof BlockStateMeta bsm && Tag.SHULKER_BOXES.isTagged(it.getType())) {
                try {
                    if (bsm.getBlockState() instanceof ShulkerBox sb) for (ItemStack in : sb.getInventory().getContents()) {
                        if (in == null || in.getType().isAir()) continue;
                        count(in, counts);
                        String w2 = illegal(in);
                        if (w2 != null) { why = w2 + " (inside a shulker box)"; break; }
                    }
                } catch (RuntimeException ignored) { }
            }
            if (why == null) continue;
            found.add(it.getAmount() + " " + Discord.pretty(it.getType()) + ": " + why);
            if (remove && !forced) inv.setItem(i, null);
        }
        if (!found.isEmpty() && (forced || throttle(p.getUniqueId() + "|illegal|" + String.join(",", found), 600000))) {
            record(p, "illegal", Sev.ALERT, "🚫 **ILLEGAL ITEMS** on " + b(p.getName()) + ": " + String.join("; ", found) + (remove && !forced ? " (removed)" : ""));
        }
        if (!forced && enabled("value")) valueCheck(p, counts);
        return found;
    }

    private void count(ItemStack it, Map<Material, Integer> counts) { if (value.containsKey(it.getType())) counts.merge(it.getType(), it.getAmount(), Integer::sum); }

    /** Why this item can't come from survival, or null. Our own plugins' items (a faultline/limbo PDC key) are fine. */
    String illegal(ItemStack it) {
        ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
        if (meta != null) for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
            if (ours(k.getNamespace())) return null;
        }
        if (meta != null) {
            try { if (meta.hasItemModel() && ours(meta.getItemModel().getNamespace())) return null; } catch (RuntimeException | LinkageError ignored) { }
        }
        if (illegal.contains(it.getType())) return "can't be had in survival";
        if (it.getAmount() > it.getMaxStackSize()) return "stacked to " + it.getAmount() + " (max " + it.getMaxStackSize() + ")";
        if (meta == null) return null;
        if (meta.isUnbreakable()) return "unbreakable";
        Map<Enchantment, Integer> en = new HashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta esm) en.putAll(esm.getStoredEnchants());
        for (var e : en.entrySet()) if (e.getValue() > e.getKey().getMaxLevel())
            return e.getKey().getKey().getKey().replace('_', ' ') + " " + e.getValue() + " (max " + e.getKey().getMaxLevel() + ")";
        if (meta.hasAttributeModifiers() && cfg().getBoolean("illegal.attribute-modifiers", true)) {
            var mods = meta.getAttributeModifiers();
            if (mods != null) for (var m : mods.values()) if (!ours(m.getKey().getNamespace())) return "custom attribute modifiers";
        }
        return null;
    }

    static boolean ours(String ns) { return ns.startsWith("faultline") || ns.startsWith("limbo") || ns.startsWith("dropheads"); }

    private final Map<UUID, Long> usedStorage = new HashMap<>();

    /** Deaths / respawns: picking your own things back up isn't a "gain". */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) { deathAt.put(e.getEntity().getUniqueId(), System.currentTimeMillis()); }

    private final Map<UUID, Long> deathAt = new HashMap<>();

    private void valueCheck(Player p, Map<Material, Integer> counts) {
        double total = 0;
        for (var e : counts.entrySet()) total += value.getOrDefault(e.getKey(), 0.0) * e.getValue();
        Double before = lastValue.put(p.getUniqueId(), total);
        Map<Material, Integer> prevCounts = lastCounts.put(p.getUniqueId(), counts);
        if (before == null || prevCounts == null) return;
        Long died = deathAt.get(p.getUniqueId());
        if (died != null && System.currentTimeMillis() - died < 10 * 60000L) return; // collecting their own drops
        double gain = total - before;
        if (gain < cfg().getDouble("value.spike", 300)) return;
        Map<Material, Integer> up = new HashMap<>();
        counts.forEach((m, n) -> { int d = n - prevCounts.getOrDefault(m, 0); if (d > 0) up.put(m, d); });
        record(p, "value", Sev.LOG, "📈 " + b(p.getName()) + " gained about " + Math.round(gain) + " diamonds' worth in 30 s (" + kinds(up) + "), now "
                + Math.round(total) + " in their inventory" + (System.currentTimeMillis() - usedStorage.getOrDefault(p.getUniqueId(), 0L) < 35000 ? ", after opening a chest / ender chest" : "")
                + ", at " + Discord.where(p.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        if (top.getLocation() != null || top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) usedStorage.put(p.getUniqueId(), System.currentTimeMillis());
        if (p.getGameMode() == GameMode.CREATIVE || !enabled("illegal")) return;
        if (top.getLocation() == null) return;
        List<String> found = new ArrayList<>();
        for (ItemStack it : top.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            String why = illegal(it);
            if (why != null) found.add(it.getAmount() + " " + Discord.pretty(it.getType()) + ": " + why);
        }
        if (!found.isEmpty() && throttle("illegal-at|" + Discord.where(top.getLocation()), 600000))
            record(p.getUniqueId(), p.getName(), "illegal", Sev.ALERT, "🚫 **ILLEGAL ITEMS** in a " + top.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " " + b(p.getName()) + " opened at "
                    + Discord.where(top.getLocation()) + ": " + String.join("; ", found), top.getLocation());
    }

    // ---------------------------------------------------------------- handoffs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !enabled("handoffs")) return;
        Item item = e.getItem();
        UUID from = item.getThrower();
        if (from == null || from.equals(p.getUniqueId())) return;
        ItemStack it = item.getItemStack();
        double worth = value.getOrDefault(it.getType(), 0.0) * it.getAmount();
        if (worth <= 0) return;
        long[] h = handoffs.computeIfAbsent(from + "|" + p.getUniqueId() + "|" + it.getType().name(), k -> new long[]{0, System.currentTimeMillis()});
        h[0] += it.getAmount();
    }

    void flushHandoffs(long now) {
        Map<String, Map<Material, Integer>> byPair = new HashMap<>();
        handoffs.entrySet().removeIf(en -> {
            if (now - en.getValue()[1] < 15000) return false;
            String[] k = en.getKey().split("\\|");
            Material m = Material.matchMaterial(k[2]);
            if (m != null) byPair.computeIfAbsent(k[0] + "|" + k[1], x -> new HashMap<>()).merge(m, (int) en.getValue()[0], Integer::sum);
            return true;
        });
        byPair.forEach((pair, items) -> {
            String[] k = pair.split("\\|");
            UUID from = UUID.fromString(k[0]), to = UUID.fromString(k[1]);
            double worth = 0;
            for (var e : items.entrySet()) worth += value.getOrDefault(e.getKey(), 0.0) * e.getValue();
            if (worth < cfg().getDouble("handoffs.min-worth", 9)) return;
            boolean sameIp = sharedIp(from, to);
            Player tp = Bukkit.getPlayer(to);
            record(to, nameOf(to), "handoff", Sev.LOG, "🤝 " + b(nameOf(to)) + " picked up " + kinds(items) + " dropped by " + b(nameOf(from))
                    + (sameIp ? " (**same IP**: an alt?)" : "") + (tp != null ? " at " + Discord.where(tp.getLocation()) : ""), tp != null ? tp.getLocation() : null);
        });
    }

    // ---------------------------------------------------------------- creative / game modes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !enabled("creative")) return;
        ItemStack it = e.getCursor();
        if (it == null || it.getType().isAir()) return;
        creative.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(it.getType(), it.getAmount(), Integer::sum);
    }

    private long creativeFlushed;

    private void flushCreative() {
        long now = System.currentTimeMillis();
        if (now - creativeFlushed < 30000 || creative.isEmpty()) return;
        creativeFlushed = now;
        creative.forEach((u, items) -> {
            int total = items.values().stream().mapToInt(Integer::intValue).sum();
            if (total >= cfg().getInt("creative.min-items", 64)) record(u, nameOf(u), "creative", Sev.LOG, "🎨 " + b(nameOf(u)) + " took from the creative inventory: " + kinds(items), null);
        });
        creative.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        GameMode g = e.getNewGameMode();
        if (!enabled("creative") || g == GameMode.SURVIVAL || g == GameMode.ADVENTURE) return;
        String cause = "";
        try { cause = e.getCause().name().toLowerCase(Locale.ROOT); } catch (RuntimeException | LinkageError ignored) { }
        if (g == GameMode.SPECTATOR && cause.equals("plugin")) return; // /camera and other plugins
        Player p = e.getPlayer();
        record(p, "gamemode", Sev.LOG, "🎮 " + b(p.getName()) + " switched to **" + g.name().toLowerCase(Locale.ROOT) + "**" + (cause.isEmpty() ? "" : " (" + cause.replace('_', ' ') + ")")
                + " at " + Discord.where(p.getLocation()));
    }

    // ---------------------------------------------------------------- alts

    String hash(InetAddress a) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data.getString("salt", "").getBytes(StandardCharsets.UTF_8));
            md.update(a.getAddress());
            return HexFormat.of().formatHex(md.digest()).substring(0, 24);
        } catch (Exception e) { return null; }
    }

    boolean sharedIp(UUID a, UUID b) {
        List<String> ha = data.getStringList("players." + a + ".ips");
        for (String h : data.getStringList("players." + b + ".ips")) if (ha.contains(h)) return true;
        return false;
    }

    Set<UUID> alts(UUID u) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String h : data.getStringList("players." + u + ".ips"))
            for (String s : data.getStringList("ips." + h)) try { UUID o = UUID.fromString(s); if (!o.equals(u)) out.add(o); } catch (IllegalArgumentException ignored) { }
        return out;
    }

    static boolean banned(UUID u) {
        try { return Bukkit.getOfflinePlayer(u).isBanned(); } catch (RuntimeException | LinkageError e) { return false; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        data.set("players." + p.getUniqueId() + ".name", p.getName());
        data.set("players." + p.getUniqueId() + ".last-join", System.currentTimeMillis());
        dirty = true;
        if (!enabled("alts")) return;
        InetSocketAddress sa = p.getAddress();
        if (sa == null || sa.getAddress() == null) return;
        InetAddress a = sa.getAddress();
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) return; // Geyser on the same box, LAN
        String h = hash(a);
        if (h == null) return;
        List<String> mine = new ArrayList<>(data.getStringList("players." + p.getUniqueId() + ".ips"));
        if (!mine.contains(h)) { mine.add(h); if (mine.size() > 12) mine.remove(0); data.set("players." + p.getUniqueId() + ".ips", mine); }
        List<String> who = new ArrayList<>(data.getStringList("ips." + h));
        if (!who.contains(p.getUniqueId().toString())) { who.add(p.getUniqueId().toString()); if (who.size() > 30) who.remove(0); data.set("ips." + h, who); }
        Set<UUID> others = alts(p.getUniqueId());
        if (others.isEmpty()) return;
        List<String> bannedNames = new ArrayList<>(), names = new ArrayList<>();
        for (UUID o : others) { names.add(nameOf(o)); if (banned(o)) bannedNames.add(nameOf(o)); }
        if (!bannedNames.isEmpty()) {
            record(p, "ban-evasion", Sev.ALERT, "🚷 **BAN EVASION?** " + b(p.getName()) + " shares an IP with BANNED " + String.join(", ", bannedNames.stream().map(Watch::b).toList())
                    + " (all accounts on it: " + String.join(", ", names) + ")");
        } else {
            String key = "players." + p.getUniqueId() + ".alts-told";
            List<String> told = data.getStringList(key);
            List<String> fresh = new ArrayList<>();
            for (UUID o : others) if (!told.contains(o.toString())) fresh.add(o.toString());
            if (!fresh.isEmpty()) {
                List<String> all = new ArrayList<>(told); all.addAll(fresh);
                data.set(key, all);
                record(p, "alts", Sev.LOG, "👥 " + b(p.getName()) + " shares an IP with " + String.join(", ", names.stream().map(Watch::b).toList()));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        movers.remove(u);
        lastValue.remove(u);
        lastCounts.remove(u);
        usedStorage.remove(u);
        Vl v = vls.get(u);
        if (v != null) v.recent.clear();
    }

    // ---------------------------------------------------------------- saving

    void save() {
        for (var en : miners.entrySet()) data.set("players." + en.getKey() + ".xray.stone", en.getValue().stoneTotal + data.getLong("players." + en.getKey() + ".xray.stone-saved"));
        data.set("muted", muted.stream().map(UUID::toString).toList());
        if (!dirty && !dataFile.exists()) return;
        try { data.save(dataFile); dirty = false; } catch (IOException e) { index.getLogger().warning("Couldn't save watch.yml: " + e.getMessage()); }
    }

    void shutdown() {
        try { flushRuns(Long.MAX_VALUE / 2); flushChunks(); } catch (RuntimeException ignored) { }
        save();
        writer.shutdown();
        try { writer.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- /watch

    UUID find(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        ConfigurationSection ps = data.getConfigurationSection("players");
        if (ps != null) for (String k : ps.getKeys(false)) if (name.equalsIgnoreCase(ps.getString(k + ".name", ""))) try { return UUID.fromString(k); } catch (IllegalArgumentException ignored) { }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        if (!s.hasPermission("faultlineindex.watch")) { s.sendMessage(ChatColor.RED + "Staff only."); return true; }
        if (args.length == 0) {
            s.sendMessage(ChatColor.GOLD + "Watch: " + ChatColor.GRAY + "x-ray, cheats, dupes, grief and alts.");
            s.sendMessage(ChatColor.YELLOW + "/watch <player>" + ChatColor.GRAY + ": everything on them");
            s.sendMessage(ChatColor.YELLOW + "/watch log [player] [n]" + ChatColor.GRAY + ": the latest lines");
            s.sendMessage(ChatColor.YELLOW + "/watch xray [player]" + ChatColor.GRAY + ": who mines suspiciously / their veins");
            s.sendMessage(ChatColor.YELLOW + "/watch alts <player>" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/watch check <player>" + ChatColor.GRAY + " (illegal items now), "
                    + ChatColor.YELLOW + "/watch alerts" + ChatColor.GRAY + " (mute/unmute in game)");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alerts" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (!muted.remove(p.getUniqueId())) { muted.add(p.getUniqueId()); p.sendMessage(ChatColor.GRAY + "Watch alerts muted."); }
                else p.sendMessage(ChatColor.GREEN + "Watch alerts on.");
                dirty = true;
                return true;
            }
            case "log" -> {
                UUID who = null;
                int n = 15;
                for (int i = 1; i < args.length; i++) {
                    try { n = Math.max(1, Math.min(100, Integer.parseInt(args[i]))); }
                    catch (NumberFormatException ex) { who = find(args[i]); if (who == null) { s.sendMessage(ChatColor.RED + "Nobody called " + args[i] + "."); return true; } }
                }
                List<Line> l = lines(who, n);
                s.sendMessage(ChatColor.GOLD + "Watch log" + (who != null ? " for " + nameOf(who) : "") + " (" + l.size() + ", newest first; full logs in plugins/FaultlineIndex/logs):");
                for (Line x : l) s.sendMessage(show(x));
                return true;
            }
            case "xray" -> { xray(s, args.length > 1 ? args[1] : null); return true; }
            case "alts" -> {
                if (args.length < 2) { s.sendMessage(ChatColor.RED + "/watch alts <player>"); return true; }
                UUID u = find(args[1]);
                if (u == null) { s.sendMessage(ChatColor.RED + "Nobody called " + args[1] + "."); return true; }
                Set<UUID> a = alts(u);
                s.sendMessage(ChatColor.GOLD + nameOf(u) + ChatColor.GRAY + " shares an IP with: " + (a.isEmpty() ? "nobody" : String.join(", ",
                        a.stream().map(o -> (banned(o) ? ChatColor.RED + "[BANNED] " : ChatColor.WHITE.toString()) + nameOf(o) + ChatColor.GRAY).toList())));
                return true;
            }
            case "check" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) { s.sendMessage(ChatColor.RED + "/watch check <online player>"); return true; }
                List<String> f = scan(t, true);
                List<String> ender = new ArrayList<>();
                for (ItemStack it : t.getEnderChest().getContents()) if (it != null && !it.getType().isAir()) { String w = illegal(it); if (w != null) ender.add(it.getAmount() + " " + Discord.pretty(it.getType()) + ": " + w); }
                s.sendMessage(ChatColor.GOLD + t.getName() + ChatColor.GRAY + ": " + (f.isEmpty() && ender.isEmpty() ? ChatColor.GREEN + "nothing illegal." : ""));
                for (String x : f) s.sendMessage(ChatColor.RED + " inventory: " + x);
                for (String x : ender) s.sendMessage(ChatColor.RED + " ender chest: " + x);
                return true;
            }
            default -> { profile(s, args[0]); return true; }
        }
    }

    static String show(Line x) {
        ChatColor c = x.sev() == Sev.ALERT ? ChatColor.RED : x.sev() == Sev.LOG ? ChatColor.YELLOW : ChatColor.GRAY;
        return ChatColor.DARK_GRAY + SHORT.format(Instant.ofEpochMilli(x.at())) + " " + c + x.text().replace("**", "").replace("\\", "");
    }

    void profile(CommandSender s, String name) {
        UUID u = find(name);
        if (u == null) { s.sendMessage(ChatColor.RED + "Nobody called " + name + "."); return; }
        String base = "players." + u;
        s.sendMessage(ChatColor.GOLD + "━━ Watch: " + nameOf(u) + (Bukkit.getPlayer(u) != null ? ChatColor.GREEN + " (online)" : ChatColor.GRAY + " (offline)") + (banned(u) ? ChatColor.RED + " [BANNED]" : ""));
        ConfigurationSection vl = data.getConfigurationSection(base + ".vl");
        StringBuilder v = new StringBuilder();
        if (vl != null) for (String k : vl.getKeys(false)) v.append(v.length() > 0 ? ", " : "").append(k).append(' ').append(vl.getInt(k));
        s.sendMessage(ChatColor.YELLOW + "Violations (all time): " + ChatColor.WHITE + (v.length() == 0 ? "none" : v));
        Vl now = vls.get(u);
        if (now != null && !now.session.isEmpty()) s.sendMessage(ChatColor.YELLOW + "This session: " + ChatColor.WHITE + now.session);
        long stone = data.getLong(base + ".xray.stone");
        Miner mi = miners.get(u);
        if (mi != null) stone = mi.stoneTotal + data.getLong(base + ".xray.stone-saved");
        StringBuilder x = new StringBuilder(ChatColor.YELLOW + "Mining (all time): " + ChatColor.WHITE + stone + " stone");
        for (Family f : Family.values()) {
            String k = base + ".xray." + f.name().toLowerCase(Locale.ROOT);
            int veins = data.getInt(k + ".veins"), buried = data.getInt(k + ".buried");
            if (veins > 0) x.append(", ").append(veins).append(' ').append(f.label).append(" veins (").append(buried).append(" buried)");
        }
        s.sendMessage(x.toString());
        Set<UUID> a = alts(u);
        if (!a.isEmpty()) s.sendMessage(ChatColor.YELLOW + "Same IP as: " + ChatColor.WHITE + String.join(", ", a.stream().map(o -> (banned(o) ? "[BANNED] " : "") + nameOf(o)).toList()));
        List<Line> l = lines(u, 8);
        if (!l.isEmpty()) { s.sendMessage(ChatColor.YELLOW + "Latest:"); for (Line y : l) s.sendMessage(show(y)); }
    }

    void xray(CommandSender s, String name) {
        long now = System.currentTimeMillis();
        int window = cfg().getInt("xray.window-minutes", 20);
        if (name != null) {
            UUID u = find(name);
            Miner mi = u == null ? null : miners.get(u);
            if (mi == null) { s.sendMessage(ChatColor.GRAY + "No recent mining for " + name + "."); return; }
            s.sendMessage(ChatColor.GOLD + nameOf(u) + ChatColor.GRAY + ": " + mi.dugIn(false, window, now) + " stone, " + mi.dugIn(true, window, now) + " netherrack in the last " + window + " min. Veins:");
            for (Vein v : mi.veins) s.sendMessage(ChatColor.DARK_GRAY + SHORT.format(Instant.ofEpochMilli(v.at())) + " " + (v.buried() ? ChatColor.RED + "BURIED " : ChatColor.GREEN + "exposed ")
                    + ChatColor.WHITE + v.fam().label + ChatColor.GRAY + " at " + v.world() + " " + v.x() + " " + v.y() + " " + v.z() + (v.dugBefore() >= 0 ? ", " + v.dugBefore() + " dug since the last" : ""));
            return;
        }
        record Row(String name, int veins, int buried, int dug, double per) { }
        List<Row> rows = new ArrayList<>();
        for (var en : miners.entrySet()) {
            Miner mi = en.getValue();
            int veins = 0, buried = 0;
            for (Vein v : mi.veins) if (now - v.at() <= window * 60000L) { veins++; if (v.buried()) buried++; }
            if (veins == 0) continue;
            int dug = mi.dugIn(false, window, now) + mi.dugIn(true, window, now);
            rows.add(new Row(nameOf(en.getKey()), veins, buried, dug, buried == 0 ? 9999 : dug / (double) buried));
        }
        rows.sort(Comparator.comparingDouble(Row::per));
        s.sendMessage(ChatColor.GOLD + "X-ray, last " + window + " min (fewest blocks dug per buried vein first):");
        if (rows.isEmpty()) s.sendMessage(ChatColor.GRAY + " nobody has found diamonds, debris or emeralds lately.");
        for (Row r : rows) s.sendMessage((r.per() < 60 && r.buried() >= 3 ? ChatColor.RED : ChatColor.WHITE) + " " + r.name() + ChatColor.GRAY + ": " + r.veins() + " veins, " + r.buried()
                + " buried, " + r.dug() + " dug" + (r.buried() > 0 ? " (" + Math.round(r.per()) + " per buried vein)" : ""));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        if (args.length == 1) { names.addAll(List.of("log", "xray", "alts", "check", "alerts")); return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
        if (args.length == 2 && !args[0].equalsIgnoreCase("alerts")) return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
