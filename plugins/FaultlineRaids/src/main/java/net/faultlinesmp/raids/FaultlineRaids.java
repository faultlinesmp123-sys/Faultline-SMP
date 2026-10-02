package net.faultlinesmp.raids;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Pose;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Zombie Raids.
 *
 * ZOMBIE OMEN (I-V): a dark green potion. Drink it, then go near a village
 * (3+ villagers within 48 blocks). After a 30s countdown a Zombie Raid starts
 * there: a boss bar, waves of zombies (level I = 3 waves ... level V = 7).
 * Win by clearing every wave: everyone who fought gets Hero of the Village
 * plus Goodie Bags / Mythic Goodie Bags / a small Ankh Shield chance (more
 * at higher levels, given through FaultlineItems' console commands).
 * Lose if every villager nearby dies, nobody is nearby for 2 minutes, or it
 * runs past 30 minutes.
 *
 * GET AN OMEN: Zombie Captains (5% chance each night, per player, a Captain and
 * his army spawn near you) always drop one; Trial Chamber chests and vaults
 * sometimes have one. Level I is common, V is rare.
 *
 * RAID MOBS: Zombies, Husks, babies, Zombie Archers (bows), Zombie Brutes
 * (triple health, bigger, armored), Bomber Zombies (explode like creepers,
 * also when killed; never break blocks), Zombie Bats (dive and bite, Hunger),
 * and Flying Zombies (elytra wings; circle above you, dive, climb).
 *
 * Lag safety: only special mobs get per-tick logic; raid mobs are never saved
 * to disk; waves are capped; raids are cleaned up on shutdown.
 */
public final class FaultlineRaids extends JavaPlugin implements Listener {

    enum Kind { ZOMBIE, HUSK, BABY, ARCHER, BRUTE, BOMBER, BABY_BOMBER, BAT, FLYER, WITCH, HORSEMAN, MAGE, CREW, BOSS,
        // the Skeleton Army / Skeleton Raids
        SKEL_SOLDIER, FROST_ARCHER, BOG_ARCHER, SHIELDBEARER, BONE_RIDER, WITHER_BRUTE, BONE_COMMANDER }

    static final String RAID_TAG = "faultline_raid_mob";
    static final String CAPTAIN_TAG = "faultline_zombie_captain";
    static final String ARMY_TAG = "faultline_zombie_army"; // a Captain and his soldiers (zombie OR skeleton: one side)
    static final String COMMANDER_TAG = "faultline_bone_commander";
    static final String SHIELD_TAG = "faultline_shieldbearer";

    private final Random random = new Random();
    private NamespacedKey omenKey;
    private NamespacedKey skeletonOmenKey;
    private NamespacedKey eggKey;

    /** Spawn eggs for testing (and admin fun). */
    enum Egg {
        ARCHER(Kind.ARCHER, Material.ZOMBIE_SPAWN_EGG, ChatColor.GREEN + "Zombie Archer"),
        BRUTE(Kind.BRUTE, Material.HUSK_SPAWN_EGG, ChatColor.DARK_GREEN + "Zombie Brute"), // different egg than the Archer
        BOMBER(Kind.BOMBER, Material.CREEPER_SPAWN_EGG, ChatColor.RED + "Bomber Zombie"),
        BAT(Kind.BAT, Material.BAT_SPAWN_EGG, ChatColor.GRAY + "Zombie Bat"),
        FLYER(Kind.FLYER, Material.PHANTOM_SPAWN_EGG, ChatColor.AQUA + "Flying Zombie"),
        BABY_BOMBER(Kind.BABY_BOMBER, Material.CREEPER_SPAWN_EGG, ChatColor.RED + "Baby Bomber"),
        WITCH(Kind.WITCH, Material.WITCH_SPAWN_EGG, ChatColor.DARK_PURPLE + "Raid Witch"),
        HORSEMAN(Kind.HORSEMAN, Material.ZOMBIE_HORSE_SPAWN_EGG, ChatColor.DARK_GREEN + "Zombie Horseman"),
        MAGE(Kind.MAGE, Material.EVOKER_SPAWN_EGG, ChatColor.GREEN + "Zombie Mage"),
        ROTBEARD(Kind.BOSS, Material.DROWNED_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "Captain Rotbeard (Boss)"),
        CAPTAIN(null, Material.ZOMBIE_VILLAGER_SPAWN_EGG, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Captain & Army"),
        SKELETON_ARMY(null, Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "" + ChatColor.BOLD + "Bone Commander & Army"),
        SKEL_SOLDIER(Kind.SKEL_SOLDIER, Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Soldier"),
        FROST_ARCHER(Kind.FROST_ARCHER, Material.STRAY_SPAWN_EGG, ChatColor.AQUA + "Frost Archer"),
        BOG_ARCHER(Kind.BOG_ARCHER, Material.BOGGED_SPAWN_EGG, ChatColor.DARK_GREEN + "Bog Archer"),
        SHIELDBEARER(Kind.SHIELDBEARER, Material.SKELETON_SPAWN_EGG, ChatColor.GRAY + "Shieldbearer"),
        BONE_RIDER(Kind.BONE_RIDER, Material.SKELETON_HORSE_SPAWN_EGG, ChatColor.WHITE + "Bone Rider"),
        WITHER_BRUTE(Kind.WITHER_BRUTE, Material.WITHER_SKELETON_SPAWN_EGG, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Wither Brute");

        final Kind kind;
        final Material material;
        final String name;

        Egg(Kind kind, Material material, String name) {
            this.kind = kind;
            this.material = material;
            this.name = name;
        }

        static Egg byName(String s) {
            for (Egg e : values()) if (e.name().equalsIgnoreCase(s)) return e;
            return null;
        }
    }

    // ---- omens ----
    private static class Omen {
        int level;
        boolean skeleton; // a Skeleton Omen (Skeleton Raid) instead of a Zombie Omen
        long expires;
        long countdownEnds; // 0 = not counting down
    }
    private final Map<UUID, Omen> omens = new HashMap<>();
    private File omenFile;

    // ---- raids ----
    private class ZombieRaid {
        World world;
        Location center;
        int level;
        int totalWaves;
        int wave = 0;
        int waveSize = 0;
        boolean forced; // started by an admin: no "all villagers died" loss
        BossBar bar;
        final Set<UUID> mobs = new HashSet<>();
        final Set<UUID> participants = new HashSet<>();
        final Set<UUID> down = new HashSet<>(); // fighters who died and haven't jumped back in
        long startedAt;
        long lastPlayerNear;
        long nextWaveAt;
        boolean over;
        boolean bossSpawned; // level IV-V: Captain Rotbeard (zombie) or a Bone Commander champion (skeleton) after the last wave
        boolean skeleton;    // a Skeleton Raid
        String kindName() { return skeleton ? "Skeleton Raid" : "Zombie Raid"; }
        ChatColor color() { return skeleton ? ChatColor.WHITE : ChatColor.DARK_GREEN; }
    }
    private final List<ZombieRaid> raids = new ArrayList<>();
    private final Map<UUID, ZombieRaid> mobRaid = new HashMap<>();

    // ---- special mob state ----
    private final Map<UUID, Kind> special = new HashMap<>();
    private final Map<UUID, Long> nextShot = new HashMap<>();
    private final Map<UUID, Integer> fuse = new HashMap<>();
    private final Set<UUID> exploded = new HashSet<>();
    private final Map<UUID, Long> batBite = new HashMap<>();
    private static class Flight { int phase; long phaseStart; double angle; boolean hit; int flap; }
    private final Set<UUID> ownGlideToggle = new HashSet<>(); // our own wing flaps, not the server's
    private final Map<UUID, Flight> flights = new HashMap<>();

    // ---- captains ----
    private boolean wasNight;
    private int seconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        omenKey = new NamespacedKey(this, "zombie_omen");
        skeletonOmenKey = new NamespacedKey(this, "skeleton_omen");
        eggKey = new NamespacedKey(this, "raid_egg");
        omenFile = new File(getDataFolder(), "omens.yml");
        loadOmens();
        wasNight = isNight(Bukkit.getWorlds().get(0));

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> { try { secondTick(); } catch (RuntimeException ex) { logPart("raid timer", ex); } }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> { try { specialTick(); } catch (RuntimeException ex) { logPart("special mobs", ex); } }, 2L, 2L);
        getCommand("zraid").setExecutor(new RaidCommand());
        getLogger().info("Faultline Raids enabled.");
    }

    @Override
    public void onDisable() {
        for (ZombieRaid raid : new ArrayList<>(raids)) endRaid(raid, false, null);
        bosses.values().forEach(b -> b.bar.removeAll());
        saveOmens();
    }

    private double cfg(String key, double def) {
        return getConfig().getDouble(key, def);
    }

    private static boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    private static boolean survival(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    // ===================== ZOMBIE OMEN ITEM =====================

    static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> "V"; };
    }

    ItemStack omenItem(int level) {
        level = Math.max(1, Math.min(5, level));
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(40, 95, 30));
        meta.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Omen " + roman(level));
        meta.setLore(List.of(
                ChatColor.GRAY + "Drink it, then go near a village.",
                ChatColor.RED + "The dead will come for it.",
                ChatColor.DARK_GRAY + "Raid level " + level + " (" + (2 + level) + " waves)"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.setItemModel(new NamespacedKey("faultline", "zombie_omen")); // texture from FaultlineItemTextures
        meta.getPersistentDataContainer().set(omenKey, PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack skeletonOmenItem(int level) {
        level = Math.max(1, Math.min(5, level));
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(226, 220, 204));
        meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Skeleton Omen " + roman(level));
        meta.setLore(List.of(
                ChatColor.GRAY + "Drink it, then go near a village.",
                ChatColor.RED + "The bones will march on it.",
                ChatColor.DARK_GRAY + "Raid level " + level + " (" + (2 + level) + " waves)"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.setItemModel(new NamespacedKey("faultline", "skeleton_omen")); // texture from the boss pack
        meta.getPersistentDataContainer().set(skeletonOmenKey, PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack randomSkeletonOmen() {
        double r = random.nextDouble();
        return skeletonOmenItem(r < 0.40 ? 1 : r < 0.65 ? 2 : r < 0.83 ? 3 : r < 0.94 ? 4 : 5);
    }

    private int skeletonOmenLevel(ItemStack item) {
        if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) return 0;
        Integer level = item.getItemMeta().getPersistentDataContainer().get(skeletonOmenKey, PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    private int omenLevel(ItemStack item) {
        if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) return 0;
        Integer level = item.getItemMeta().getPersistentDataContainer().get(omenKey, PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    /** Level I common, V rare: 40/25/18/11/6%. */
    ItemStack randomOmen() {
        double r = random.nextDouble();
        int level = r < 0.40 ? 1 : r < 0.65 ? 2 : r < 0.83 ? 3 : r < 0.94 ? 4 : 5;
        return omenItem(level);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        int zLevel = omenLevel(event.getItem()), sLevel = skeletonOmenLevel(event.getItem());
        int level = Math.max(zLevel, sLevel);
        if (level == 0) return;
        boolean skeleton = sLevel > 0;
        Player player = event.getPlayer();
        Omen omen = omens.computeIfAbsent(player.getUniqueId(), k -> new Omen());
        if (omen.skeleton != skeleton) omen.level = 0; // a different omen replaces the old one
        omen.skeleton = skeleton;
        omen.level = Math.max(omen.level, level);
        omen.expires = System.currentTimeMillis() + (long) cfg("omen.minutes", 60) * 60_000L;
        omen.countdownEnds = 0;
        saveOmens();
        player.showTitle(Title.title(legacy(skeleton ? ChatColor.WHITE + "" + ChatColor.BOLD + "Skeleton Omen " + roman(omen.level)
                        : ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Omen " + roman(omen.level)),
                legacy(ChatColor.GRAY + (skeleton ? "Find a village... the bones will march on it." : "Find a village... the dead will follow.")),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.6f);
    }

    // ===================== ONCE-A-SECOND LOOP =====================

    private void secondTick() {
        seconds++;
        long now = System.currentTimeMillis();

        // Omens: start a countdown near a village, then the raid.
        Iterator<Map.Entry<UUID, Omen>> it = omens.entrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Map.Entry<UUID, Omen> entry = it.next();
            Omen omen = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (now > omen.expires) {
                it.remove();
                changed = true;
                if (player != null) player.sendMessage(ChatColor.GRAY + "Your " + (omen.skeleton ? "Skeleton" : "Zombie") + " Omen fades away.");
                continue;
            }
            if (player == null || !survival(player) || player.getWorld().getEnvironment() != World.Environment.NORMAL
                    || player.getWorld().getDifficulty() == Difficulty.PEACEFUL) continue;

            List<Villager> villagers = villagersNear(player.getLocation(), cfg("village.radius", 48));
            boolean nearVillage = villagers.size() >= (int) cfg("village.min-villagers", 3);
            if (!nearVillage || raidNear(player.getLocation(), 128) != null) {
                omen.countdownEnds = 0;
                continue;
            }
            if (omen.countdownEnds == 0) {
                omen.countdownEnds = now + (long) cfg("omen.countdown-seconds", 30) * 1000L;
                player.sendMessage((omen.skeleton ? ChatColor.WHITE + "The bones have found this village... " : ChatColor.DARK_GREEN + "The dead have found this village... ")
                        + ChatColor.GRAY + "(" + (omen.skeleton ? "Skeleton" : "Zombie") + " Raid in " + (long) cfg("omen.countdown-seconds", 30) + "s)");
                player.playSound(player.getLocation(), omen.skeleton ? Sound.ENTITY_SKELETON_AMBIENT : Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 0.5f);
            } else if (now >= omen.countdownEnds) {
                it.remove();
                changed = true;
                startRaid(centroid(villagers), omen.level, player, false, omen.skeleton);
            }
        }
        if (changed) saveOmens();

        for (ZombieRaid raid : new ArrayList<>(raids)) raidTick(raid, now);
        captainTick(now);
    }

    private List<Villager> villagersNear(Location loc, double radius) {
        List<Villager> list = new ArrayList<>();
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius, e -> e instanceof Villager)) {
            list.add((Villager) e);
        }
        return list;
    }

    private Location centroid(List<Villager> villagers) {
        double x = 0, y = 0, z = 0;
        for (Villager v : villagers) {
            x += v.getLocation().getX();
            y += v.getLocation().getY();
            z += v.getLocation().getZ();
        }
        int n = villagers.size();
        return new Location(villagers.get(0).getWorld(), x / n, y / n, z / n);
    }

    private ZombieRaid raidNear(Location loc, double radius) {
        for (ZombieRaid raid : raids) {
            if (raid.world.equals(loc.getWorld()) && raid.center.distanceSquared(loc) <= radius * radius) return raid;
        }
        return null;
    }

    // ===================== RAIDS =====================

    void startRaid(Location center, int level, Player starter, boolean forced) {
        startRaid(center, level, starter, forced, false);
    }

    void startRaid(Location center, int level, Player starter, boolean forced, boolean skeleton) {
        ZombieRaid raid = new ZombieRaid();
        raid.skeleton = skeleton;
        raid.world = center.getWorld();
        raid.center = center;
        raid.level = Math.max(1, Math.min(5, level));
        raid.totalWaves = 2 + raid.level;
        raid.forced = forced;
        raid.startedAt = System.currentTimeMillis();
        raid.lastPlayerNear = raid.startedAt;
        raid.nextWaveAt = raid.startedAt + 5000;
        raid.bar = Bukkit.createBossBar(raid.color() + "" + ChatColor.BOLD + raid.kindName() + " " + roman(raid.level),
                skeleton ? BarColor.WHITE : BarColor.GREEN, BarStyle.SEGMENTED_10);
        if (starter != null && survival(starter)) raid.participants.add(starter.getUniqueId()); // a creative admin isn't a defender
        raids.add(raid);

        for (Player p : playersNear(raid, 96)) {
            p.showTitle(Title.title(legacy(raid.color() + "" + ChatColor.BOLD + raid.kindName().toUpperCase()),
                    legacy(ChatColor.GRAY + "Level " + roman(raid.level) + " — defend the village!"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 2f, 0.7f);
        }
    }

    private void announce(ZombieRaid raid, String message) {
        for (Player p : playersNear(raid, 96)) p.sendMessage(message);
    }

    private List<Player> playersNear(ZombieRaid raid, double radius) {
        List<Player> list = new ArrayList<>();
        for (Player p : raid.world.getPlayers()) {
            if (p.getLocation().distanceSquared(raid.center) <= radius * radius) list.add(p);
        }
        return list;
    }

    private void raidTick(ZombieRaid raid, long now) {
        if (raid.over) return;

        List<Player> near = playersNear(raid, 96);
        // Only add/remove players who actually crossed the range (no every-second flicker).
        for (Player p : new ArrayList<>(raid.bar.getPlayers())) if (!near.contains(p)) raid.bar.removePlayer(p);
        for (Player p : near) if (!raid.bar.getPlayers().contains(p)) raid.bar.addPlayer(p);

        if (near.stream().anyMatch(FaultlineRaids::survival)) raid.lastPlayerNear = now;
        else if (now - raid.lastPlayerNear > (long) cfg("raid.abandon-seconds", 120) * 1000L) {
            endRaid(raid, false, "Everyone left... the dead take the village.");
            return;
        }
        if (now - raid.startedAt > (long) cfg("raid.max-minutes", 30) * 60_000L) {
            endRaid(raid, false, "The raid dragged on too long. The village has fallen.");
            return;
        }
        if (!raid.forced && villagersNear(raid.center, 64).isEmpty()) {
            endRaid(raid, false, "Every villager is gone. The village has fallen.");
            return;
        }

        raid.mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            boolean gone = e == null || !e.isValid() || e.isDead();
            if (gone) mobRaid.remove(id);
            return gone;
        });

        if (raid.nextWaveAt == 0 && raid.mobs.isEmpty() && raid.wave > 0) {
            if (raid.wave >= raid.totalWaves) {
                if (raid.level >= (int) cfg("boss.min-level", 4) && !raid.bossSpawned) {
                    raid.bossSpawned = true;
                    spawnBossForRaid(raid);
                    return;
                }
                winRaid(raid);
                return;
            }
            raid.nextWaveAt = now + (long) cfg("raid.seconds-between-waves", 10) * 1000L;
        }
        if (raid.nextWaveAt > 0 && now >= raid.nextWaveAt) {
            raid.wave++;
            raid.nextWaveAt = 0;
            spawnWave(raid);
            for (Player p : near) p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.5f, 0.8f);
        }

        String title = raid.color() + "" + ChatColor.BOLD + raid.kindName() + " " + roman(raid.level) + ChatColor.RESET
                + ChatColor.GRAY + "  •  " + (raid.nextWaveAt > 0
                ? "Wave " + (raid.wave + 1) + "/" + raid.totalWaves + " in " + Math.max(0, (raid.nextWaveAt - now) / 1000) + "s"
                : "Wave " + raid.wave + "/" + raid.totalWaves + "  •  " + raid.mobs.size() + " left");
        raid.bar.setTitle(title);
        raid.bar.setProgress(raid.nextWaveAt > 0 || raid.waveSize == 0 ? 1.0
                : Math.max(0, Math.min(1, raid.mobs.size() / (double) raid.waveSize)));

        // Keep the wave coming: stragglers glow, wanderers come back, idle mobs get a target.
        boolean fewLeft = raid.nextWaveAt == 0 && raid.mobs.size() <= 3;
        for (UUID id : raid.mobs) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity mob)) continue;
            if (fewLeft) mob.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false));
            if (!mob.getWorld().equals(raid.world) || mob.getLocation().distanceSquared(raid.center) > 100 * 100) {
                Location back = ringSpot(raid.center, 26, 36);
                if (back != null) mob.teleport(back);
            }
            if (seconds % 2 == 0 && mob instanceof Mob m && m.getTarget() == null) {
                LivingEntity t = nearestVillagerOrPlayer(mob.getLocation(), 48);
                if (t != null) m.setTarget(t);
            }
        }
    }

    private LivingEntity nearestVillagerOrPlayer(Location loc, double range) {
        LivingEntity best = null;
        double bestDist = range * range;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, range, range, range,
                e -> e instanceof Villager || (e instanceof Player p && survival(p)))) {
            double d = e.getLocation().distanceSquared(loc);
            if (d < bestDist) {
                bestDist = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    private void spawnWave(ZombieRaid raid) {
        int w = raid.wave;
        int lv = raid.level;
        List<Kind> kinds = new ArrayList<>();

        // The middle wave is a swarm of babies: baby Zombies/Husks and baby Bombers.
        int babyWave = Math.max(2, (raid.totalWaves + 1) / 2);
        if (raid.skeleton) {
            if (w == babyWave) { // the Volley: a wall of archers behind a line of shields
                for (int i = 0; i < 5 + w + lv; i++) kinds.add(random.nextDouble() < 0.7 ? Kind.SKEL_SOLDIER : Kind.FROST_ARCHER);
                for (int i = 0; i < 2 + lv / 2; i++) kinds.add(Kind.SHIELDBEARER);
                announce(raid, ChatColor.WHITE + "You hear hundreds of bowstrings drawing at once...");
            } else {
                for (int i = 0; i < 3 + w + lv; i++) {
                    double r = random.nextDouble();
                    kinds.add(r < 0.6 ? Kind.SKEL_SOLDIER : r < 0.85 ? Kind.FROST_ARCHER : Kind.BOG_ARCHER);
                }
                for (int i = 0; i < 1 + w / 2; i++) kinds.add(Kind.SHIELDBEARER);
                if (w >= 2) for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0) + (w == raid.totalWaves ? 1 : 0); i++) kinds.add(Kind.WITHER_BRUTE);
                if (w >= 3) for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0); i++) kinds.add(Kind.BONE_RIDER);
                if (w == raid.totalWaves) kinds.add(Kind.BONE_COMMANDER); // the last wave marches behind a Commander
            }
        } else if (w == babyWave) {
            for (int i = 0; i < 5 + w + lv; i++) kinds.add(Kind.BABY);
            for (int i = 0; i < 2 + lv / 2; i++) kinds.add(Kind.BABY_BOMBER);
            announce(raid, ChatColor.YELLOW + "Something small and fast is coming...");
        } else {
            int regular = 3 + w + lv;
            for (int i = 0; i < regular; i++) {
                double r = random.nextDouble();
                kinds.add(r < 0.12 ? Kind.BABY : r < 0.45 ? Kind.HUSK : Kind.ZOMBIE);
            }
            for (int i = 0; i < 1 + w / 3 + (lv >= 3 ? 1 : 0); i++) kinds.add(Kind.ARCHER);
            if (w >= 2) {
                for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0) + (w == raid.totalWaves ? 1 : 0); i++) kinds.add(Kind.BRUTE);
                for (int i = 0; i < 1 + (w >= 4 ? 1 : 0); i++) kinds.add(Kind.BOMBER);
                for (int i = 0; i < 1 + (lv >= 3 ? 1 : 0); i++) kinds.add(Kind.MAGE);
                if (lv >= 2) for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0); i++) kinds.add(Kind.WITCH);
            }
            if (w >= 3) for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0); i++) kinds.add(Kind.HORSEMAN);
            for (int i = 0; i < 2 + lv / 2; i++) kinds.add(Kind.BAT);
            if (w >= 3 && lv >= 2) {
                for (int i = 0; i < 1 + (lv >= 4 ? 1 : 0); i++) kinds.add(Kind.FLYER);
            }
        }

        int cap = (int) cfg("raid.max-wave-size", 30);
        int spawned = 0;
        for (Kind kind : kinds) {
            if (spawned >= cap) break;
            // Fall back closer in, then to the village center: if nothing could spawn
            // (e.g. a village over water), the wave would "finish" instantly for free loot.
            Location spot = ringSpot(raid.center, 26, 40);
            if (spot == null) spot = ringSpot(raid.center, 6, 20);
            if (spot == null) spot = raid.center.clone();
            if (kind == Kind.BAT || kind == Kind.FLYER) spot.add(0, 4, 0);
            LivingEntity mob = spawnKind(kind, spot);
            if (mob == null) continue;
            tag(mob, RAID_TAG); // (and its horse, for Horsemen)
            if (mob instanceof Mob m) {
                m.setRemoveWhenFarAway(false); // don't despawn mid-raid
                LivingEntity t = nearestVillagerOrPlayer(raid.center, 64);
                if (t != null) m.setTarget(t);
            }
            raid.mobs.add(mob.getUniqueId());
            mobRaid.put(mob.getUniqueId(), raid);
            spawned++;
        }
        raid.waveSize = spawned;
    }

    /** Standing spot on the surface in a ring around a point (loaded chunks only, no treetops/water). */
    private Location ringSpot(Location center, double min, double max) {
        World world = center.getWorld();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block ground = world.getHighestBlockAt(x, z);
            if (!ground.getType().isSolid() || ground.isLiquid() || Tag.LEAVES.isTagged(ground.getType())) continue;
            Block feet = ground.getRelative(0, 1, 0);
            if (!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0, 1, 0).isPassable()) continue;
            return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    /** Level IV-V Skeleton Raids end with a Bone Commander champion: much tougher, and his war horn never stops. */
    private void spawnSkeletonChampion(ZombieRaid raid, Location spot) {
        LivingEntity boss = spawnKind(Kind.BONE_COMMANDER, spot);
        if (boss == null) { winRaid(raid); return; }
        setAttr(boss, Attribute.MAX_HEALTH, cfg("skeleton-raid.champion-health", 300));
        boss.setHealth(cfg("skeleton-raid.champion-health", 300));
        boss.addScoreboardTag(CHAMPION_TAG); // uses his moves faster
        setAttr(boss, Attribute.SCALE, 1.4);
        setAttr(boss, Attribute.KNOCKBACK_RESISTANCE, 0.8);
        boss.setCustomName(ChatColor.WHITE + "" + ChatColor.BOLD + "Bone Commander " + ChatColor.GOLD + "(Champion)");
        tag(boss, RAID_TAG);
        if (boss instanceof Mob m) m.setRemoveWhenFarAway(false);
        raid.mobs.add(boss.getUniqueId());
        mobRaid.put(boss.getUniqueId(), raid);
        raid.waveSize = 1;
        for (Player p : playersNear(raid, 96)) {
            p.showTitle(Title.title(legacy(ChatColor.WHITE + "" + ChatColor.BOLD + "THE BONE COMMANDER"),
                    legacy(ChatColor.GRAY + "The army's champion takes the field!"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 2f, 0.5f);
        }
    }

    private void winRaid(ZombieRaid raid) {
        int lv = raid.level;
        for (UUID id : raid.participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            int goodie = (4 + lv) + random.nextInt(2);                    // I: 5-6 ... V: 9-10
            int mythic = 1 + random.nextInt(1 + (lv - 1) / 2);             // I-II: 1, III-IV: 1-2, V: 1-3
            boolean ankh = random.nextDouble() < cfg("rewards.ankh-chance-per-level", 0.002) * lv; // V: 1%

            give(p, "givegoodiebag " + goodie + " " + p.getName());
            give(p, "givemythicbag " + mythic + " " + p.getName());
            if (ankh) {
                give(p, "giveaccessory ankh 1 " + p.getName());
                Bukkit.broadcastMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + p.getName() + " earned an ANKH SHIELD from a " + raid.kindName() + "!");
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 48000, lv - 1));
            p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "RAID DEFEATED"),
                    legacy(ChatColor.YELLOW + "Hero of the Village! " + ChatColor.GRAY + goodie + " Goodie Bags, " + mythic + " Mythic"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        endRaid(raid, true, null);
    }

    /** Rewards come from FaultlineItems' own give commands (so the items are the real ones). */
    private void give(Player player, String command) {
        Plugin items = Bukkit.getPluginManager().getPlugin("FaultlineItems");
        boolean ok = items != null && items.isEnabled() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!ok) {
            getLogger().warning("Couldn't run '" + command + "' (is FaultlineItems installed?) — gave emeralds instead.");
            player.getInventory().addItem(new ItemStack(Material.EMERALD, 16)).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    private void endRaid(ZombieRaid raid, boolean won, String lossMessage) {
        if (raid.over) return;
        raid.over = true;
        raids.remove(raid);
        if (lossMessage != null) {
            for (Player p : playersNear(raid, 96)) {
                p.sendMessage(ChatColor.DARK_RED + lossMessage);
                p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.6f);
            }
        }
        raid.bar.removeAll();
        for (UUID id : raid.mobs) {
            mobRaid.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) {
                e.getWorld().spawnParticle(Particle.LARGE_SMOKE, e.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
                e.remove();
            }
        }
        raid.mobs.clear();
    }

    // ===================== MOB TYPES =====================

    LivingEntity spawnKind(Kind kind, Location at) {
        World world = at.getWorld();
        EntityType type = switch (kind) {
            case HUSK -> EntityType.HUSK;
            case BAT -> EntityType.BAT;
            case WITCH -> EntityType.WITCH;
            case SKEL_SOLDIER, SHIELDBEARER, BONE_RIDER, BONE_COMMANDER -> EntityType.SKELETON;
            case FROST_ARCHER -> EntityType.STRAY;
            case BOG_ARCHER -> EntityType.BOGGED;
            case WITHER_BRUTE -> EntityType.WITHER_SKELETON;
            default -> EntityType.ZOMBIE;
        };
        // randomizeData = false: no random gear, babies, or chicken jockeys.
        if (!(world.spawnEntity(at, type, false) instanceof LivingEntity mob) || !mob.isValid()) return null;
        mob.setPersistent(false);
        mob.setCanPickupItems(false);

        if (mob instanceof Zombie z) {
            z.setShouldBurnInDay(false); // raids can happen in daylight
            if (kind == Kind.BABY || kind == Kind.BABY_BOMBER) z.setBaby(); else z.setAdult();
            if (kind == Kind.BABY && random.nextBoolean()) {
                // half the babies are baby husks
                z.remove();
                if (!(world.spawnEntity(at, EntityType.HUSK, false) instanceof Zombie husk)) return null;
                husk.setPersistent(false);
                husk.setCanPickupItems(false);
                husk.setShouldBurnInDay(false);
                husk.setBaby();
                z = husk;
                mob = husk;
            }
            setAttr(mob, Attribute.FOLLOW_RANGE, 48);
        }
        if (mob instanceof org.bukkit.entity.AbstractSkeleton sk) {
            sk.setShouldBurnInDay(false); // raids can happen in daylight (a night army is set to burn again below)
            setAttr(mob, Attribute.FOLLOW_RANGE, 48);
            double hp = switch (kind) { // the Skeleton Army is tougher than regular skeletons
                case SKEL_SOLDIER, FROST_ARCHER, BOG_ARCHER, BONE_RIDER -> cfg("skeleton-army.soldier-health", 30);
                case SHIELDBEARER -> cfg("skeleton-army.shieldbearer-health", 36);
                default -> 0;
            };
            if (hp > 0) { setAttr(mob, Attribute.MAX_HEALTH, hp); mob.setHealth(hp); }
        }
        EntityEquipment gear = mob.getEquipment();

        switch (kind) {
            case SKEL_SOLDIER, FROST_ARCHER, BOG_ARCHER -> {
                if (gear != null) {
                    gear.setItemInMainHand(new ItemStack(Material.BOW)); // unrandomized skeletons spawn empty-handed
                    if (kind == Kind.SKEL_SOLDIER) gear.setHelmet(dyed(Material.LEATHER_HELMET, Color.fromRGB(220, 214, 196)));
                }
                mob.setCustomName(kind == Kind.SKEL_SOLDIER ? ChatColor.WHITE + "Skeleton Soldier"
                        : kind == Kind.FROST_ARCHER ? ChatColor.AQUA + "Frost Archer" : ChatColor.DARK_GREEN + "Bog Archer");
            }
            case SHIELDBEARER -> {
                if (gear != null) {
                    gear.setHelmet(new ItemStack(Material.IRON_HELMET));
                    gear.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    gear.setItemInMainHand(new ItemStack(Material.STONE_SWORD)); // swords make a skeleton fight up close
                    gear.setItemInOffHand(new ItemStack(Material.SHIELD));
                }
                mob.addScoreboardTag(SHIELD_TAG);
                mob.setCustomName(ChatColor.GRAY + "Shieldbearer");
            }
            case WITHER_BRUTE -> {
                setAttr(mob, Attribute.MAX_HEALTH, cfg("skeleton-army.wither-brute-health", 60));
                mob.setHealth(cfg("skeleton-army.wither-brute-health", 60));
                setAttr(mob, Attribute.SCALE, 1.2);
                setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 0.5);
                if (gear != null) gear.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                mob.setCustomName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Wither Brute");
            }
            case BONE_RIDER -> {
                if (gear != null) {
                    gear.setItemInMainHand(new ItemStack(Material.BOW));
                    gear.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                }
                mob.setCustomName(ChatColor.WHITE + "Bone Rider");
                if (world.spawnEntity(at, EntityType.SKELETON_HORSE, false) instanceof LivingEntity horse && horse.isValid()) {
                    horse.setPersistent(false);
                    if (horse instanceof Mob hm) hm.setRemoveWhenFarAway(false);
                    if (horse instanceof org.bukkit.entity.AbstractHorse ah) ah.setTamed(true); // a tamed mount follows its rider
                    setAttr(horse, Attribute.MAX_HEALTH, 30);
                    horse.setHealth(30);
                    setAttr(horse, Attribute.MOVEMENT_SPEED, 0.3);
                    horse.addPassenger(mob);
                    horses.put(mob.getUniqueId(), horse.getUniqueId());
                }
            }
            case BONE_COMMANDER -> {
                setAttr(mob, Attribute.MAX_HEALTH, cfg("skeleton-army.commander-health", 80));
                mob.setHealth(cfg("skeleton-army.commander-health", 80));
                if (gear != null) {
                    gear.setHelmet(commanderBanner()); // the banner doubles as sun protection
                    gear.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    ItemStack bow = new ItemStack(Material.BOW);
                    bow.addUnsafeEnchantment(Enchantment.POWER, 3);
                    gear.setItemInMainHand(bow);
                    gear.setHelmetDropChance(0f);
                    gear.setChestplateDropChance(0f);
                    gear.setItemInMainHandDropChance(0f);
                }
                mob.addScoreboardTag(COMMANDER_TAG);
                mob.setCustomName(ChatColor.WHITE + "" + ChatColor.BOLD + "Bone Commander");
                mob.setCustomNameVisible(true);
            }
            case ARCHER -> {
                if (gear != null) {
                    gear.setItemInMainHand(new ItemStack(Material.BOW));
                    gear.setHelmet(dyed(Material.LEATHER_HELMET, Color.fromRGB(70, 90, 40)));
                }
                mob.setCustomName(ChatColor.GREEN + "Zombie Archer");
            }
            case BRUTE -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) {
                    hp.setBaseValue(hp.getBaseValue() * 3); // triple health
                    mob.setHealth(hp.getValue());
                }
                setAttr(mob, Attribute.SCALE, 1.25);
                setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 0.6);
                AttributeInstance dmg = mob.getAttribute(Attribute.ATTACK_DAMAGE);
                if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() + 2);
                if (gear != null) {
                    gear.setHelmet(new ItemStack(Material.IRON_HELMET));
                    gear.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    gear.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                    gear.setBoots(new ItemStack(Material.IRON_BOOTS));
                    gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                }
                mob.setCustomName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Brute");
            }
            case BOMBER, BABY_BOMBER -> {
                if (gear != null) gear.setItemInMainHand(new ItemStack(Material.TNT)); // carrying the bomb
                AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.15);
                mob.setCustomName(ChatColor.RED + (kind == Kind.BABY_BOMBER ? "Baby Bomber" : "Bomber Zombie"));
            }
            case WITCH -> setAttr(mob, Attribute.FOLLOW_RANGE, 32);
            case HORSEMAN -> {
                if (gear != null) {
                    gear.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                    gear.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                }
                mob.setCustomName(ChatColor.DARK_GREEN + "Zombie Horseman");
                if (world.spawnEntity(at, EntityType.ZOMBIE_HORSE, false) instanceof LivingEntity horse && horse.isValid()) {
                    horse.setPersistent(false);
                    if (horse instanceof Mob hm) hm.setRemoveWhenFarAway(false);
                    setAttr(horse, Attribute.MAX_HEALTH, 30);
                    horse.setHealth(30);
                    setAttr(horse, Attribute.MOVEMENT_SPEED, 0.32);
                    horse.addPassenger(mob);
                    horses.put(mob.getUniqueId(), horse.getUniqueId());
                }
            }
            case MAGE -> {
                if (gear != null) {
                    gear.setHelmet(dyed(Material.LEATHER_HELMET, Color.fromRGB(35, 105, 40)));    // hood
                    gear.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(35, 105, 40))); // robe
                    gear.setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.fromRGB(25, 60, 30)));
                    gear.setItemInMainHand(new ItemStack(Material.BLAZE_ROD)); // staff
                }
                mob.setCustomName(ChatColor.GREEN + "Zombie Mage");
            }
            case CREW -> {
                if (gear != null) {
                    gear.setHelmet(head(getConfig().getString("boss.crew-head-texture", "bbe94fd31b82222432f27b60d765a5d622fd76c4e3a8807f9a1d41fb707afae0")));
                    gear.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(120, 25, 25)));
                    gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                }
                mob.setCustomName(ChatColor.RED + "Pirate Crew");
            }
            case BOSS -> makeBoss(mob);
            case BAT -> {
                setAttr(mob, Attribute.MAX_HEALTH, 8);
                mob.setHealth(8);
                if (mob instanceof Bat bat) bat.setAwake(true);
            }
            case FLYER -> {
                if (gear != null) gear.setChestplate(new ItemStack(Material.ELYTRA)); // wings
                mob.setGravity(false);
                // Elytra gliding pose (flat body, wings spread) without real gliding physics.
                mob.setPose(Pose.FALL_FLYING, true);
                mob.setCustomName(ChatColor.AQUA + "Flying Zombie");
            }
            default -> { }
        }
        if (gear != null) {
            gear.setHelmetDropChance(0f);
            gear.setChestplateDropChance(0f);
            gear.setLeggingsDropChance(0f);
            gear.setBootsDropChance(0f);
            gear.setItemInMainHandDropChance(0f);
            gear.setItemInOffHandDropChance(0f);
        }
        mob.setCustomNameVisible(kind == Kind.BOSS);
        switch (kind) {
            case ARCHER, BOMBER, BAT, FLYER, MAGE, HORSEMAN, BOSS -> special.put(mob.getUniqueId(), kind);
            case BABY_BOMBER -> special.put(mob.getUniqueId(), Kind.BOMBER);
            default -> { }
        }
        return mob;
    }

    private static void setAttr(LivingEntity mob, Attribute attribute, double value) {
        AttributeInstance inst = mob.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    private static ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ===================== SPECIAL MOB BEHAVIOR (every 2 ticks) =====================

    private void specialTick() {
        long now = System.currentTimeMillis();
        // BUG FIX: loop over a COPY. Rotbeard's "Call the Crew" spawns a Baby Bomber
        // (a special mob) from inside this loop, which adds to the map mid-loop and
        // threw a ConcurrentModificationException every time he used it.
        for (Map.Entry<UUID, Kind> entry : new ArrayList<>(special.entrySet())) {
            Entity e = Bukkit.getEntity(entry.getKey());
            if (!(e instanceof LivingEntity mob) || !mob.isValid() || mob.isDead()) {
                special.remove(entry.getKey());
                clearState(entry.getKey());
                continue;
            }
            // BUG FIX: one mob's ability erroring stopped this loop for EVERY raid mob after it (every tick, so they all
            // froze). Now each mob runs on its own; an error is logged and only that mob's ability is skipped.
            try {
                switch (entry.getValue()) {
                    case ARCHER -> archerTick(mob, now);
                    case BOMBER -> bomberTick(mob);
                    case BAT -> batTick(mob, now);
                    case FLYER -> flyerTick(mob, now);
                    case MAGE -> mageTick(mob, now);
                    case HORSEMAN -> horsemanTick(mob, now);
                    case BOSS -> bossTick(mob, now);
                    default -> { }
                }
            } catch (RuntimeException ex) {
                logPart(entry.getValue() + " ability", ex);
            }
        }
        try { orbTick(); } catch (RuntimeException ex) { logPart("orbs", ex); }
    }

    private final Map<String, Long> partLog = new HashMap<>();

    /** Logs an error (at most every 30 seconds per part), so the console says exactly what failed. */
    private void logPart(String what, Throwable ex) {
        long t = System.currentTimeMillis();
        if (t - partLog.getOrDefault(what, 0L) < 30000) return;
        partLog.put(what, t);
        getLogger().log(java.util.logging.Level.SEVERE, "[Raids] error in " + what + " (skipped, everything else keeps going; please report this):", ex);
    }

    private void clearState(UUID id) {
        exploded.remove(id);
        UUID horse = horses.remove(id);
        if (horse != null) { // the rider is gone: so is the horse
            Entity h = Bukkit.getEntity(horse);
            if (h != null && h.isValid()) h.remove();
        }
        Boss boss = bosses.remove(id);
        if (boss != null) boss.bar.removeAll();
        nextShot.remove(id);
        fuse.remove(id);
        batBite.remove(id);
        flights.remove(id);
    }

    private LivingEntity targetOf(LivingEntity mob) {
        if (mob instanceof Mob m && m.getTarget() != null && m.getTarget().isValid() && !m.getTarget().isDead()) return m.getTarget();
        Player best = null;
        double bestDist = 32 * 32;
        for (Player p : mob.getWorld().getPlayers()) {
            if (!survival(p) || p.isDead()) continue;
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Zombies can't use bows on their own, so the Archer's shots are done here. */
    private void archerTick(LivingEntity mob, long now) {
        if (now < nextShot.getOrDefault(mob.getUniqueId(), 0L)) return;
        LivingEntity target = targetOf(mob);
        if (target == null || !target.getWorld().equals(mob.getWorld())) return;
        double dist = target.getLocation().distance(mob.getLocation());
        if (dist < 4 || dist > 24 || !mob.hasLineOfSight(target)) return;

        Arrow arrow = mob.launchProjectile(Arrow.class);
        Vector aim = target.getEyeLocation().subtract(0, 0.5, 0).toVector().subtract(mob.getEyeLocation().toVector());
        aim.setY(aim.getY() + dist * 0.12); // lead the arc a little
        arrow.setVelocity(aim.normalize().multiply(1.6));
        arrow.setDamage(cfg("mobs.archer-arrow-damage", 2.0));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1f, 0.8f);
        nextShot.put(mob.getUniqueId(), now + 2000 + random.nextInt(800));
    }

    /** Creeper-style: close in, hiss, explode. Blasts hurt but never break blocks. */
    private void bomberTick(LivingEntity mob) {
        UUID id = mob.getUniqueId();
        LivingEntity target = targetOf(mob);
        Integer left = fuse.get(id);
        if (left == null) {
            if (target != null && target.getWorld().equals(mob.getWorld()) && target.getLocation().distance(mob.getLocation()) <= 2.5) {
                fuse.put(id, 15); // 15 x 2 ticks = 1.5s, like a creeper
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1f, 0.9f);
            }
            return;
        }
        if (target == null || !target.getWorld().equals(mob.getWorld()) || target.getLocation().distance(mob.getLocation()) > 5) {
            fuse.remove(id); // backed off, like a creeper
            return;
        }
        mob.setVelocity(new Vector(0, mob.getVelocity().getY(), 0));
        mob.getWorld().spawnParticle(Particle.SMOKE, mob.getEyeLocation().add(0, 0.4, 0), 3, 0.1, 0.1, 0.1, 0.01);
        if (left <= 1) {
            fuse.remove(id);
            exploded.add(id);
            Location at = mob.getLocation();
            mob.remove();
            at.getWorld().createExplosion(at, (float) cfg("mobs.bomber-power", 3.0), false, false, mob);
        } else {
            fuse.put(id, left - 1);
        }
    }

    /** Dives at you and bites (a little damage + Hunger). */
    private void batTick(LivingEntity bat, long now) {
        LivingEntity target = targetOf(bat);
        if (!(target instanceof Player player) || !player.getWorld().equals(bat.getWorld())) return;
        Vector toward = player.getEyeLocation().subtract(0, 0.5, 0).toVector().subtract(bat.getLocation().toVector());
        double dist = toward.length();
        if (dist > 40) return;
        if (dist > 0.1) bat.setVelocity(toward.normalize().multiply(0.45));
        if (dist < 1.4 && now >= batBite.getOrDefault(bat.getUniqueId(), 0L)) {
            if (facingBlock(player, bat.getLocation().toVector())) {
                shieldHit(player, cfg("mobs.bat-bite-damage", 3.0), false); // blockable
            } else {
                player.damage(cfg("mobs.bat-bite-damage", 3.0), bat);
                player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 100, 0));
            }
            batBite.put(bat.getUniqueId(), now + 1500);
        }
    }

    /** Circles above you, dives, then climbs back up. */
    private void setWings(LivingEntity mob, boolean open) {
        ownGlideToggle.add(mob.getUniqueId());
        mob.setGliding(open);
        ownGlideToggle.remove(mob.getUniqueId());
    }

    /** The server tries to end a mob's glide on its own; Flying Zombies decide for themselves. */
    @EventHandler(ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!event.isGliding() && special.get(id) == Kind.FLYER && !ownGlideToggle.contains(id)) event.setCancelled(true);
    }

    /** Gliding into a wall normally hurts; not for Flying Zombies mid-dive. */
    @EventHandler(ignoreCancelled = true)
    public void onFlyerWallHit(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL && special.get(event.getEntity().getUniqueId()) == Kind.FLYER) {
            event.setCancelled(true);
        }
    }

    private void flyerTick(LivingEntity mob, long now) {
        Flight f = flights.computeIfAbsent(mob.getUniqueId(), k -> {
            Flight n = new Flight();
            n.phaseStart = now;
            n.angle = random.nextDouble() * Math.PI * 2;
            return n;
        });
        mob.setFallDistance(0);
        // Flying animation: body turned to face its flight direction, plus a wind trail.
        Vector vel = mob.getVelocity();
        if (vel.lengthSquared() > 0.003) {
            float yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            float pitch = (float) Math.toDegrees(-Math.atan2(vel.getY(), Math.hypot(vel.getX(), vel.getZ())));
            mob.setRotation(yaw, pitch);
        }
        mob.getWorld().spawnParticle(Particle.CLOUD, mob.getLocation().add(0, 0.8, 0), 1, 0.2, 0.2, 0.2, 0.005);

        // Wings: while circling and climbing, the Elytra opens and closes (a flap every
        // 8 ticks, with a flap sound). While diving, wings stay spread in the glide pose.
        f.flap++;
        if (f.phase == 1) {
            if (!mob.isGliding()) setWings(mob, true);
        } else if (f.flap % 4 == 0) {
            boolean open = !mob.isGliding();
            setWings(mob, open);
            if (open) mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.3f);
        }
        LivingEntity target = targetOf(mob);
        if (target == null || !target.getWorld().equals(mob.getWorld())) {
            mob.setVelocity(new Vector(0, 0, 0));
            return;
        }
        Location loc = mob.getLocation();
        switch (f.phase) {
            case 0 -> { // circle overhead
                f.angle += 0.12;
                Location point = target.getLocation().add(Math.cos(f.angle) * 5, 6, Math.sin(f.angle) * 5);
                Vector v = point.toVector().subtract(loc.toVector()).multiply(0.15);
                if (v.length() > 0.5) v.normalize().multiply(0.5);
                mob.setVelocity(v);
                if (now - f.phaseStart > 3000) { f.phase = 1; f.phaseStart = now; f.hit = false; }
            }
            case 1 -> { // dive
                Vector v = target.getEyeLocation().toVector().subtract(loc.toVector());
                double dist = v.length();
                if (dist > 0.1) mob.setVelocity(v.normalize().multiply(0.8));
                if (dist < 1.7 && !f.hit) {
                    guardedDamage(target, cfg("mobs.flyer-dive-damage", 5.0), mob, mob.getLocation().toVector(), true); // heavy
                    f.hit = true;
                }
                if (f.hit || now - f.phaseStart > 1500) { f.phase = 2; f.phaseStart = now; }
            }
            default -> { // climb back up
                mob.setVelocity(new Vector(0, 0.45, 0));
                if (now - f.phaseStart > 1200) { f.phase = 0; f.phaseStart = now; }
            }
        }
    }

    // ===================== MAGE, HORSEMAN, WITCH =====================

    static final String ORB_TAG = "faultline_mage_orb";
    private final Map<UUID, Long> orbs = new HashMap<>(); // orb -> fired at
    private final Map<UUID, UUID> horses = new HashMap<>(); // rider -> horse

    /** Tags a mob and, for a Horseman, his horse too. */
    private void tag(LivingEntity mob, String tag) {
        mob.addScoreboardTag(tag);
        UUID horse = horses.get(mob.getUniqueId());
        if (horse != null) {
            Entity h = Bukkit.getEntity(horse);
            if (h != null) h.addScoreboardTag(tag);
        }
    }

    /** Fires a slow green orb at its target every few seconds. */
    private void mageTick(LivingEntity mob, long now) {
        if (now < nextShot.getOrDefault(mob.getUniqueId(), 0L)) return;
        LivingEntity target = targetOf(mob);
        if (target == null || !target.getWorld().equals(mob.getWorld())) return;
        double dist = target.getLocation().distance(mob.getLocation());
        if (dist < 3 || dist > 22 || !mob.hasLineOfSight(target)) return;

        Snowball orb = mob.launchProjectile(Snowball.class);
        orb.setItem(new ItemStack(Material.SLIME_BALL)); // looks like a green orb
        orb.setGravity(false);
        orb.addScoreboardTag(ORB_TAG);
        Vector aim = target.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(mob.getEyeLocation().toVector());
        orb.setVelocity(aim.normalize().multiply(0.9));
        orbs.put(orb.getUniqueId(), now);
        mob.swingMainHand();
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.5f);
        nextShot.put(mob.getUniqueId(), now + 2800 + random.nextInt(900));
    }

    /** Green glow trail on flying orbs; orbs that miss fizzle out after 4 seconds. */
    private void orbTick() {
        long now = System.currentTimeMillis();
        orbs.entrySet().removeIf(entry -> {
            Entity orb = Bukkit.getEntity(entry.getKey());
            if (orb == null || !orb.isValid()) return true;
            if (now - entry.getValue() > 4000) {
                orb.remove();
                return true;
            }
            orb.getWorld().spawnParticle(Particle.DUST, orb.getLocation(), 3, 0.08, 0.08, 0.08, 0,
                    new Particle.DustOptions(Color.fromRGB(60, 230, 60), 1.4f));
            return false;
        });
    }

    @EventHandler
    public void onOrbHit(ProjectileHitEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(ORB_TAG)) return;
        Location at = event.getEntity().getLocation();
        at.getWorld().spawnParticle(Particle.DUST, at, 20, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(60, 230, 60), 1.6f));
        at.getWorld().playSound(at, Sound.BLOCK_SLIME_BLOCK_BREAK, 1f, 0.8f);
        if (event.getHitEntity() instanceof LivingEntity victim && !isRaidMob(victim)) {
            Entity shooter = event.getEntity().getShooter() instanceof Entity s ? s : null;
            if (victim instanceof Player p && facingBlock(p, at.toVector())) {
                shieldHit(p, cfg("mobs.mage-orb-damage", 5.0), false); // blockable
            } else {
                victim.damage(cfg("mobs.mage-orb-damage", 5.0), shooter);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
            }
        }
        orbs.remove(event.getEntity().getUniqueId());
        event.getEntity().remove();
    }

    /** Horses don't chase on their own, so point the horse at the rider's target. */
    private void horsemanTick(LivingEntity rider, long now) {
        if (now < nextShot.getOrDefault(rider.getUniqueId(), 0L)) return; // re-path twice a second, not every tick
        nextShot.put(rider.getUniqueId(), now + 500);
        UUID horseId = horses.get(rider.getUniqueId());
        Entity horse = horseId == null ? null : Bukkit.getEntity(horseId);
        LivingEntity target = targetOf(rider);
        if (horse instanceof Mob h && h.isValid() && target != null && target.getWorld().equals(h.getWorld())) {
            h.getPathfinder().moveTo(target, 2.0);
        }
    }

    /** A Witch's potions never hit her own side. */
    @EventHandler(ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Entity thrower) || !isRaidMob(thrower)) return;
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (isRaidMob(hit)) event.setIntensity(hit, 0);
        }
    }

    // ===================== BOSS: CAPTAIN ROTBEARD =====================

    static final String BOSS_TAG = "faultline_rotbeard";

    private static class Boss {
        BossBar bar;
        ZombieRaid raid; // null if spawned from an egg
        boolean rage;
        long nextMoveAt;
        final Map<String, Long> ready = new HashMap<>();
        final Set<UUID> crew = new HashSet<>();
    }
    private final Map<UUID, Boss> bosses = new HashMap<>();

    private void spawnBossForRaid(ZombieRaid raid) {
        Location spot = ringSpot(raid.center, 14, 24);
        if (spot == null) spot = raid.center.clone();
        if (raid.skeleton) { spawnSkeletonChampion(raid, spot); return; }
        LivingEntity boss = spawnKind(Kind.BOSS, spot);
        if (boss == null) { // couldn't spawn: don't hold the raid hostage
            winRaid(raid);
            return;
        }
        tag(boss, RAID_TAG);
        if (boss instanceof Mob m) m.setRemoveWhenFarAway(false);
        Boss state = bosses.get(boss.getUniqueId());
        if (state != null) state.raid = raid;
        raid.mobs.add(boss.getUniqueId());
        mobRaid.put(boss.getUniqueId(), raid);
        raid.waveSize = 1;
        for (Player p : playersNear(raid, 96)) {
            p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "CAPTAIN ROTBEARD"),
                    legacy(ChatColor.GRAY + "The Zombie Captain has come for the village!"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.8f);
        }
    }

    /** The Zombie Captain: a pirate. 500 health, bigger than a zombie, cutlass and flintlock. */
    private void makeBoss(LivingEntity mob) {
        mob.addScoreboardTag(BOSS_TAG);
        mob.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Captain Rotbeard");
        double hp = cfg("boss.health", 500);
        setAttr(mob, Attribute.MAX_HEALTH, hp);
        mob.setHealth(hp);
        setAttr(mob, Attribute.SCALE, 1.3);
        setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 0.9);
        setAttr(mob, Attribute.ATTACK_DAMAGE, cfg("boss.melee-damage", 10));
        setAttr(mob, Attribute.MOVEMENT_SPEED, 0.27);
        setAttr(mob, Attribute.FOLLOW_RANGE, 48);
        EntityEquipment gear = mob.getEquipment();
        if (gear != null) {
            gear.setHelmet(head(getConfig().getString("boss.head-texture", "7a8832a7b0ae0f028186b586a56ff7c0bae3d3f60d09e76cdc15f07f0d3b311f")));
            gear.setChestplate(trimmed(Material.LEATHER_CHESTPLATE, Color.fromRGB(35, 22, 18), TrimPattern.WAYFINDER));
            gear.setLeggings(trimmed(Material.LEATHER_LEGGINGS, Color.fromRGB(60, 40, 25), TrimPattern.WAYFINDER));
            gear.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(20, 15, 12)));
            gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD)); // cutlass
            gear.setItemInOffHand(new ItemStack(Material.CROSSBOW));     // flintlock
        }
        Boss state = new Boss();
        state.bar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Captain Rotbeard" + ChatColor.RESET
                + ChatColor.GRAY + " — The Zombie Captain", BarColor.YELLOW, BarStyle.SEGMENTED_20);
        state.nextMoveAt = System.currentTimeMillis() + 2500;
        bosses.put(mob.getUniqueId(), state);
    }

    private void bossTick(LivingEntity boss, long now) {
        Boss b = bosses.get(boss.getUniqueId());
        if (b == null) return;
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max == null ? 500 : max.getValue();
        b.bar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / maxHp)));
        for (Player p : new ArrayList<>(b.bar.getPlayers())) {
            if (!p.isOnline() || !p.getWorld().equals(boss.getWorld()) || p.getLocation().distanceSquared(boss.getLocation()) > 56 * 56) b.bar.removePlayer(p);
        }
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(boss.getLocation()) <= 48 * 48 && !b.bar.getPlayers().contains(p)) b.bar.addPlayer(p);
        }

        // Rum Rage: at half health he drinks, heals a bit, and gets faster and stronger.
        if (!b.rage && boss.getHealth() <= maxHp / 2) {
            b.rage = true;
            boss.setHealth(Math.min(maxHp, boss.getHealth() + maxHp * 0.10));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, true));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, true));
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_GENERIC_DRINK, 2f, 0.6f);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2f, 0.8f);
            boss.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, boss.getEyeLocation(), 10, 0.5, 0.5, 0.5, 0);
            b.bar.setColor(BarColor.RED);
            for (Player p : b.bar.getPlayers()) p.sendMessage(ChatColor.GOLD + "Captain Rotbeard downs his rum... " + ChatColor.RED + "and he's ANGRY!");
        }

        if (now < b.nextMoveAt) return;
        LivingEntity target = targetOf(boss);
        if (!(target instanceof Player player) || !player.getWorld().equals(boss.getWorld())) return;
        if (boss instanceof Mob m && m.getTarget() != player) m.setTarget(player);
        double dist = player.getLocation().distance(boss.getLocation());
        boolean sight = boss.hasLineOfSight(player);

        List<String> options = new ArrayList<>();
        if (dist <= 4.5 && ready(b, "sweep", now)) options.add("sweep");
        if (dist <= 7 && boss.isOnGround() && ready(b, "slam", now)) options.add("slam");
        if (dist >= 6 && dist <= 28 && sight && ready(b, "flintlock", now)) options.add("flintlock");
        if (dist >= 8 && dist <= 24 && sight && ready(b, "grapple", now)) options.add("grapple");
        if (dist <= 30 && ready(b, "cannon", now)) options.add("cannon");
        b.crew.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
        if (b.crew.size() < 6 && ready(b, "crew", now)) options.add("crew");
        if (options.isEmpty()) return;

        double speedUp = b.rage ? 0.6 : 1.0;
        String move = options.get(random.nextInt(options.size()));
        switch (move) {
            case "sweep" -> { cutlassSweep(boss); cooldown(b, "sweep", 5, speedUp, now); }
            case "slam" -> { anchorSlam(boss); cooldown(b, "slam", 14, speedUp, now); }
            case "flintlock" -> { flintlock(boss, player); cooldown(b, "flintlock", 7, speedUp, now); }
            case "grapple" -> { grapple(boss, player); cooldown(b, "grapple", 12, speedUp, now); }
            case "cannon" -> { cannonBarrage(boss); cooldown(b, "cannon", 18, speedUp, now); }
            default -> { callCrew(boss, b, player); cooldown(b, "crew", 30, speedUp, now); }
        }
        b.nextMoveAt = now + (long) (2500 * speedUp);
    }

    private boolean ready(Boss b, String move, long now) {
        return now >= b.ready.getOrDefault(move, 0L);
    }

    private void cooldown(Boss b, String move, double seconds, double speedUp, long now) {
        b.ready.put(move, now + (long) (seconds * 1000 * speedUp));
    }

    /** 1. Cutlass Sweep: a wide slash hitting everyone in front of him. */
    private void cutlassSweep(LivingEntity boss) {
        boss.getWorld().playSound(boss.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1f, 0.7f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!boss.isValid()) return;
            boss.swingMainHand();
            Location eye = boss.getEyeLocation();
            Vector facing = eye.getDirection().setY(0).normalize();
            boss.getWorld().spawnParticle(Particle.SWEEP_ATTACK, eye.clone().add(facing.clone().multiply(1.8)), 4, 1, 0.3, 1, 0);
            boss.getWorld().playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
            for (Entity e : boss.getNearbyEntities(4.5, 3, 4.5)) {
                if (!(e instanceof Player p) || p.isDead()) continue;
                Vector to = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(facing) < 0) continue; // only in front of him
                p.damage(cfg("boss.sweep-damage", 9), boss);
                p.setVelocity(to.multiply(0.9).setY(0.35));
            }
        }, 10L);
    }

    /** 2. Flintlock Shot: aims for a second, then a fast, hard-hitting shot. */
    private void flintlock(LivingEntity boss, Player target) {
        boss.getWorld().playSound(boss.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 1.5f, 0.7f);
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(boss.getLocation()) < 40 * 40) {
                p.sendActionBar(legacy(ChatColor.GOLD + "Rotbeard takes aim at " + target.getName() + "..."));
            }
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!boss.isValid() || !target.isOnline() || target.isDead() || !boss.hasLineOfSight(target)) return;
            Arrow shot = boss.launchProjectile(Arrow.class);
            double speed = 3.2;
            Vector aim = target.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(boss.getEyeLocation().toVector());
            shot.setVelocity(aim.normalize().multiply(speed));
            shot.setGravity(false);
            shot.setCritical(false);
            shot.setDamage(cfg("boss.flintlock-damage", 10) / speed); // arrow damage scales with speed
            shot.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            boss.getWorld().spawnParticle(Particle.LARGE_SMOKE, boss.getEyeLocation(), 8, 0.2, 0.2, 0.2, 0.02);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.8f);
        }, 20L);
    }

    /** 3. Cannon Barrage: red circles mark where cannonballs land 2 seconds later. */
    private void cannonBarrage(LivingEntity boss) {
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.4f);
        List<Location> spots = new ArrayList<>();
        for (Entity e : boss.getNearbyEntities(30, 15, 30)) {
            if (e instanceof Player p && survival(p)) {
                spots.add(p.getLocation());
                spots.add(p.getLocation().add(random.nextInt(7) - 3, 0, random.nextInt(7) - 3));
            }
        }
        if (spots.size() > 6) spots = spots.subList(0, 6);
        for (Location spot : spots) {
            for (int i = 0; i < 8; i++) { // warning circle for 2 seconds
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    for (int a = 0; a < 16; a++) {
                        double angle = a * Math.PI / 8;
                        spot.getWorld().spawnParticle(Particle.DUST, spot.clone().add(Math.cos(angle) * 1.8, 0.2, Math.sin(angle) * 1.8),
                                1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(220, 30, 30), 1.5f));
                    }
                }, i * 5L);
            }
            Bukkit.getScheduler().runTaskLater(this, () ->
                    spot.getWorld().createExplosion(spot, (float) cfg("boss.cannon-power", 2.5), false, false,
                            boss.isValid() ? boss : null), 40L);
        }
    }

    /** 4. Grappling Hook: yanks a far-away player right up to him and slows them. */
    private void grapple(LivingEntity boss, Player target) {
        if (facingBlock(target, boss.getEyeLocation().toVector())) { // blockable: the hook bounces off the shield
            shieldHit(target, 2, false);
            target.sendMessage(ChatColor.GOLD + "Your shield knocks Rotbeard's hook away!");
            return;
        }
        Location from = boss.getEyeLocation();
        Vector line = target.getEyeLocation().toVector().subtract(from.toVector());
        double length = line.length();
        line.normalize();
        for (double d = 0; d < length; d += 0.6) {
            from.getWorld().spawnParticle(Particle.CRIT, from.clone().add(line.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.5f, 0.6f);
        Vector pull = boss.getLocation().toVector().subtract(target.getLocation().toVector());
        pull = pull.lengthSquared() > 0.0001 ? pull.normalize().multiply(Math.min(2.2, 0.9 + length * 0.06)) : new Vector(); // never a NaN pull
        target.setVelocity(pull.setY(0.55));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        target.sendMessage(ChatColor.GOLD + "Rotbeard's hook catches you!");
    }

    /** 5. Call the Crew: pirate zombies and a baby Bomber "powder monkey". */
    private void callCrew(LivingEntity boss, Boss b, Player target) {
        boss.getWorld().playSound(boss.getLocation(), Sound.EVENT_RAID_HORN, 1.5f, 1.3f);
        for (int i = 0; i < 4; i++) {
            Location at = boss.getLocation().add(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
            if (!at.getBlock().isPassable()) at = boss.getLocation();
            LivingEntity mate = spawnKind(i == 3 ? Kind.BABY_BOMBER : Kind.CREW, at);
            if (mate == null) continue;
            if (b.raid != null) {
                tag(mate, RAID_TAG);
                b.raid.mobs.add(mate.getUniqueId());
                mobRaid.put(mate.getUniqueId(), b.raid);
            } else {
                tag(mate, ARMY_TAG);
            }
            if (mate instanceof Mob m) m.setTarget(target);
            b.crew.add(mate.getUniqueId());
            mate.getWorld().spawnParticle(Particle.LARGE_SMOKE, mate.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
        }
    }

    /** 6. Anchor Slam: leaps up and smashes down, launching everyone nearby. */
    private void anchorSlam(LivingEntity boss) {
        boss.setVelocity(new Vector(0, 1.1, 0));
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.5f, 1.2f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!boss.isValid()) return;
            Location at = boss.getLocation();
            at.getWorld().spawnParticle(Particle.EXPLOSION, at, 8, 2, 0.3, 2, 0);
            at.getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
            double radius = cfg("boss.slam-radius", 6);
            for (Entity e : boss.getNearbyEntities(radius, 4, radius)) {
                if (!(e instanceof Player p) || p.isDead()) continue;
                unblockable(p, cfg("boss.slam-damage", 10), boss); // a ground slam: dodge it
                Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                p.setVelocity(away.normalize().multiply(1.2).setY(0.75));
            }
        }, 18L);
    }

    private ItemStack head(String hash) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (hash == null || hash.isBlank() || !(head.getItemMeta() instanceof SkullMeta meta)) return head;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("faultline-raid-" + hash).getBytes(StandardCharsets.UTF_8)), "Pirate");
        profile.setProperty(new ProfileProperty("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        meta.setPlayerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack trimmed(Material material, Color color, TrimPattern pattern) {
        ItemStack item = dyed(material, color);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof ArmorMeta armor) armor.setTrim(new ArmorTrim(TrimMaterial.GOLD, pattern));
        item.setItemMeta(meta);
        return item;
    }

    // ===================== DEATHS, PARTICIPATION, LOOT =====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        UUID id = dead.getUniqueId();

        // Bomber: killing it still sets it off (1s warning, so you can back away).
        if (special.get(id) == Kind.BOMBER && !exploded.remove(id)) {
            Location at = dead.getLocation();
            at.getWorld().playSound(at, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
            at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
            Bukkit.getScheduler().runTaskLater(this, () ->
                    at.getWorld().createExplosion(at, (float) cfg("mobs.bomber-death-power", 2.5), false, false), 20L);
        }
        exploded.remove(id);

        if (dead.getScoreboardTags().contains(BOSS_TAG)) {
            event.getDrops().clear();
            event.setDroppedExp((int) cfg("boss.xp", 300));
            Player killer = dead.getKiller();
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Captain Rotbeard has fallen"
                    + (killer != null ? " to " + killer.getName() : "") + "!");
        }

        if (dead.getScoreboardTags().contains(CAPTAIN_TAG)) {
            event.getDrops().removeIf(i -> i.getType().name().endsWith("_BANNER"));
            if (dead.getKiller() != null) event.getDrops().add(randomOmen());
        }
        // A Bone Commander leading a night army: a Goodie Bag, good loot, and a Skeleton Omen.
        // (The raid's champion drops nothing extra: winning the raid is the reward.)
        if (dead.getScoreboardTags().contains(COMMANDER_TAG) && !dead.getScoreboardTags().contains(RAID_TAG) && dead.getKiller() != null) {
            Player killer = dead.getKiller();
            event.getDrops().removeIf(i -> i.getType().name().endsWith("_BANNER"));
            event.getDrops().add(randomSkeletonOmen());
            event.getDrops().add(new ItemStack(Material.BONE, 6 + random.nextInt(7)));
            event.getDrops().add(new ItemStack(Material.ARROW, 16 + random.nextInt(17)));
            if (random.nextDouble() < cfg("skeleton-army.bow-chance", 0.35)) {
                ItemStack bow = new ItemStack(Material.BOW);
                bow.addUnsafeEnchantment(Enchantment.POWER, 4);
                bow.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
                bow.addUnsafeEnchantment(Enchantment.PUNCH, 1);
                ItemMeta bm = bow.getItemMeta();
                bm.setDisplayName(ChatColor.WHITE + "Commander's Bow");
                bow.setItemMeta(bm);
                event.getDrops().add(bow);
            }
            Location l = dead.getLocation();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givegoodiebag 1 " + killer.getName()
                    + " at:" + l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
            event.setDroppedExp(40);
        }
    }

    /**
     * Raid mobs never hurt each other (an Archer's stray arrow would make the zombie
     * it hit turn on the Archer, and the raid would start killing itself).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!isRaidMob(event.getEntity())) return;
        Entity source = event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Entity s ? s : event.getDamager();
        if (isRaidMob(source)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRaidTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && isRaidMob(event.getEntity()) && isRaidMob(event.getTarget())) event.setCancelled(true);
    }

    /** Raid mobs, and a Captain's army, count as one side (no friendly fire). */
    private static boolean isRaidMob(Entity e) {
        return e != null && (e.getScoreboardTags().contains(RAID_TAG) || e.getScoreboardTags().contains(ARMY_TAG));
    }

    /** Anyone who hits a raid mob (or gets hit by one) counts as having fought in that raid. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player s ? s : null;
        ZombieRaid hitRaid = mobRaid.get(event.getEntity().getUniqueId());
        if (attacker != null && hitRaid != null) { hitRaid.participants.add(attacker.getUniqueId()); hitRaid.down.remove(attacker.getUniqueId()); }

        Entity source = event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Entity s ? s : event.getDamager();
        ZombieRaid byRaid = mobRaid.get(source.getUniqueId());
        if (byRaid != null && event.getEntity() instanceof Player victim) { byRaid.participants.add(victim.getUniqueId()); byRaid.down.remove(victim.getUniqueId()); }
    }

    /** Trial Chamber chests sometimes hold a Zombie Omen. */
    @EventHandler(ignoreCancelled = true)
    public void onChestLoot(LootGenerateEvent event) {
        if (!event.getLootTable().getKey().getKey().startsWith("chests/trial_chambers/")) return;
        if (random.nextDouble() < cfg("omen.trial-chest-chance", 0.10)) event.getLoot().add(randomOmen());
    }

    /**
     * ...and so do Trial Chamber vaults. Vault blocks only: Trial SPAWNERS fire this
     * same event every time they're beaten, which would make Omens farmable.
     */
    @EventHandler(ignoreCancelled = true)
    public void onVaultLoot(BlockDispenseLootEvent event) {
        if (event.getBlock().getType() != Material.VAULT) return;
        if (random.nextDouble() >= cfg("omen.trial-vault-chance", 0.10)) return;
        List<ItemStack> loot = new ArrayList<>(event.getDispensedLoot());
        loot.add(randomOmen());
        event.setDispensedLoot(loot);
    }

    // ===================== ZOMBIE CAPTAINS =====================

    /** At nightfall, each survival player in the main Overworld has a 5% chance of a Captain showing up later that night. */
    private void captainTick(long now) {
        World main = Bukkit.getWorlds().get(0);
        boolean night = isNight(main);
        if (night && !wasNight && main.getDifficulty() != Difficulty.PEACEFUL) {
            for (Player p : main.getPlayers()) {
                if (!survival(p) || random.nextDouble() >= cfg("captain.chance-per-night", 0.05)) continue;
                UUID id = p.getUniqueId();
                long delay = 20L * (30 + random.nextInt(91)); // 30s - 2min into the night
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && survival(player) && player.getWorld().equals(main) && isNight(main)) spawnCaptain(player);
                }, delay);
            }
        }
        if (night && !wasNight && main.getDifficulty() != Difficulty.PEACEFUL) {
            for (Player p : main.getPlayers()) { // the Skeleton Army rolls separately from the Zombie Army
                if (!survival(p) || random.nextDouble() >= cfg("skeleton-army.chance-per-night", 0.05)) continue;
                UUID id = p.getUniqueId();
                long delay = 20L * (30 + random.nextInt(91));
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && survival(player) && player.getWorld().equals(main) && isNight(main)) spawnSkeletonArmy(player);
                }, delay);
            }
        }
        wasNight = night;
        if (seconds % (int) cfg("skeleton-army.war-horn-seconds", 15) == 0) warHornTick();
        commanderTick();
    }

    void spawnCaptain(Player near) {
        Location spot = ringSpot(near.getLocation(), 24, 34);
        if (spot == null) return;
        if (spawnCaptainAt(spot, near)) {
            near.sendActionBar(legacy(ChatColor.DARK_GREEN + "You hear the groans of an army nearby..."));
        }
    }

    /** A Captain plus his 3-5 soldiers at this spot. The target can be null (they'll find someone). */
    boolean spawnCaptainAt(Location spot, Player near) {
        if (!(spot.getWorld().spawnEntity(spot, EntityType.ZOMBIE, false) instanceof Zombie captain) || !captain.isValid()) return false;
        captain.setPersistent(false);
        captain.setAdult();
        captain.setCanPickupItems(false);
        captain.addScoreboardTag(CAPTAIN_TAG);
        captain.addScoreboardTag(ARMY_TAG);
        captain.setCustomName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Captain");
        captain.setCustomNameVisible(true);
        setAttr(captain, Attribute.MAX_HEALTH, 40);
        captain.setHealth(40);
        setAttr(captain, Attribute.FOLLOW_RANGE, 48);
        EntityEquipment gear = captain.getEquipment();
        if (gear != null) {
            gear.setHelmet(captainBanner()); // the banner doubles as sun protection
            gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            gear.setHelmetDropChance(0f);
            gear.setItemInMainHandDropChance(0f);
        }
        if (near != null) captain.setTarget(near);

        int army = 3 + random.nextInt(3);
        for (int i = 0; i < army; i++) {
            Location at = spot.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            if (!at.getBlock().isPassable()) at = spot;
            LivingEntity soldier = spawnKind(i == 0 ? Kind.ARCHER : (random.nextBoolean() ? Kind.ZOMBIE : Kind.HUSK), at);
            if (soldier != null) tag(soldier, ARMY_TAG);
            if (soldier instanceof Zombie z) {
                z.setShouldBurnInDay(true); // the army burns at sunrise like normal zombies
                if (near != null) z.setTarget(near);
            }
        }
        return true;
    }

    // ===================== THE SKELETON ARMY =====================
    // Like the Zombie Army: some nights (5% per player, rolled separately) a Bone Commander
    // and 3-5 soldiers march on you. His war horn buffs his army; Shieldbearers block arrows
    // from the front. He drops a Goodie Bag, good loot, and a Skeleton Omen (Skeleton Raid).

    void spawnSkeletonArmy(Player near) {
        Location spot = ringSpot(near.getLocation(), 24, 34);
        if (spot == null) return;
        if (spawnSkeletonArmyAt(spot, near)) near.sendActionBar(legacy(ChatColor.WHITE + "You hear bones rattling in step nearby..."));
    }

    boolean spawnSkeletonArmyAt(Location spot, Player near) {
        LivingEntity commander = spawnKind(Kind.BONE_COMMANDER, spot);
        if (commander == null) return false;
        tag(commander, ARMY_TAG);
        if (commander instanceof Mob m && near != null) m.setTarget(near);
        int army = 3 + random.nextInt(3);
        for (int i = 0; i < army; i++) {
            Location at = spot.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            if (!at.getBlock().isPassable()) at = spot;
            Kind k = i == 0 ? Kind.SHIELDBEARER : i == 1 ? Kind.SKEL_SOLDIER
                    : random.nextDouble() < cfg("skeleton-army.rider-chance", 0.25) ? Kind.BONE_RIDER
                    : random.nextBoolean() ? Kind.SKEL_SOLDIER : Kind.FROST_ARCHER;
            LivingEntity soldier = spawnKind(k, at);
            if (soldier == null) continue;
            tag(soldier, ARMY_TAG);
            if (soldier instanceof org.bukkit.entity.AbstractSkeleton sk) sk.setShouldBurnInDay(true); // the army burns at sunrise
            if (soldier instanceof Mob m && near != null) m.setTarget(near);
        }
        return true;
    }

    // ===================== THE BONE COMMANDER'S MOVES =====================
    // 1 War Horn (above, every 15s)   2 Arrow Rain   3 Piercing Shot   4 Call to Arms   5 Disengage
    // Every few seconds he uses one: Disengage if you're right on top of him, otherwise one of the others.

    static final String CHAMPION_TAG = "faultline_bone_champion";
    static final String PIERCING_TAG = "faultline_piercing_shot";
    private final Map<UUID, Integer> commanderCooldown = new HashMap<>();

    private void commanderTick() {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity c : w.getLivingEntities()) {
                if (!c.getScoreboardTags().contains(COMMANDER_TAG) || c.isDead() || !(c instanceof Mob mob)) continue;
                if (!(mob.getTarget() instanceof Player target) || !target.getWorld().equals(w) || !survival(target)) continue;
                int cd = commanderCooldown.getOrDefault(c.getUniqueId(), 3) - 1;
                if (cd > 0) { commanderCooldown.put(c.getUniqueId(), cd); continue; }
                boolean champion = c.getScoreboardTags().contains(CHAMPION_TAG);
                double every = cfg("skeleton-army.move-seconds", 6) * (champion ? 0.6 : 1);
                commanderCooldown.put(c.getUniqueId(), Math.max(2, (int) Math.round(every)));
                double dist = c.getLocation().distance(target.getLocation());
                if (dist < 4.5) { disengage(c, target); continue; }
                int pick = random.nextInt(3);
                if (pick == 2 && armyNear(c) >= (int) cfg("skeleton-army.call-cap", 6)) pick = random.nextInt(2);
                switch (pick) {
                    case 0 -> arrowRain(c, target);
                    case 1 -> piercingShot(c, target);
                    default -> callToArms(c);
                }
            }
        }
        commanderCooldown.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
    }

    private long armyNear(LivingEntity c) {
        return c.getNearbyEntities(24, 12, 24).stream().filter(e -> e instanceof LivingEntity && isRaidMob(e) && !e.isDead()).count();
    }

    /** 2) Arrow Rain: a red ring marks the spot for 1.5s, then a volley falls on it. */
    private void arrowRain(LivingEntity c, Player target) {
        Location spot = target.getLocation();
        World w = spot.getWorld();
        c.getWorld().playSound(c.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.5f, 0.6f);
        target.sendActionBar(legacy(ChatColor.RED + "Arrows are coming down on you... move!"));
        for (int t = 0; t < 30; t += 3) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (int i = 0; i < 20; i++) {
                    double a = Math.PI * 2 * i / 20;
                    w.spawnParticle(Particle.DUST, spot.clone().add(Math.cos(a) * 3, 0.15, Math.sin(a) * 3), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(200, 30, 30), 1.4f));
                }
            }, t);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!c.isValid() || c.isDead()) return;
            w.playSound(spot, Sound.ENTITY_ARROW_SHOOT, 2f, 0.5f);
            int n = (int) cfg("skeleton-army.arrow-rain-arrows", 14);
            for (int i = 0; i < n; i++) {
                double a = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * 3;
                Location from = spot.clone().add(Math.cos(a) * r, 14 + random.nextDouble() * 3, Math.sin(a) * r);
                org.bukkit.entity.Arrow arrow = w.spawnArrow(from, new Vector(0, -1, 0), 1.8f, 2f);
                arrow.setShooter(c);
                arrow.setDamage(cfg("skeleton-army.arrow-rain-damage", 4));
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            }
        }, 30L);
    }

    /** 3) Piercing Shot: he plants his feet and draws for a second, then fires a heavy, piercing, slowing arrow. */
    private void piercingShot(LivingEntity c, Player target) {
        c.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 5, false, false));
        c.getWorld().playSound(c.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 1.5f, 0.5f);
        for (int t = 0; t < 20; t += 2) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (c.isValid()) c.getWorld().spawnParticle(Particle.CRIT, c.getEyeLocation(), 6, 0.3, 0.3, 0.3, 0.1);
            }, t);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!c.isValid() || c.isDead() || !target.isOnline()) return;
            Location eye = c.getEyeLocation();
            Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
            if (dir.lengthSquared() < 0.01) return;
            org.bukkit.entity.Arrow arrow = c.getWorld().spawnArrow(eye.add(dir.clone().normalize()), dir.normalize(), 3.2f, 0f);
            arrow.setShooter(c);
            arrow.setDamage(cfg("skeleton-army.piercing-shot-damage", 12));
            arrow.setPierceLevel(3);
            arrow.setCritical(true);
            arrow.setGlowing(true);
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            arrow.addScoreboardTag(PIERCING_TAG);
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 2f, 0.6f);
        }, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiercingHit(EntityDamageByEntityEvent event) {
        if (event.getDamager().getScoreboardTags().contains(PIERCING_TAG) && event.getEntity() instanceof Player p) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            // heavy knockback along the arrow's path (arrows no longer carry their own knockback in 1.21)
            Vector push = event.getDamager().getVelocity().setY(0);
            if (push.lengthSquared() > 0.01) {
                Vector kb = push.normalize().multiply(cfg("skeleton-army.piercing-shot-knockback", 1.2)).setY(0.4);
                Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) p.setVelocity(p.getVelocity().add(kb)); });
            }
        }
    }

    /** 4) Call to Arms: a Shieldbearer and a Skeleton Soldier claw their way up beside him. */
    private void callToArms(LivingEntity c) {
        boolean raid = c.getScoreboardTags().contains(RAID_TAG);
        c.getWorld().playSound(c.getLocation(), Sound.ENTITY_SKELETON_HORSE_AMBIENT, 1.5f, 0.5f);
        for (Kind k : new Kind[]{Kind.SHIELDBEARER, Kind.SKEL_SOLDIER}) {
            Location at = c.getLocation().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            if (!at.getBlock().isPassable()) at = c.getLocation();
            c.getWorld().spawnParticle(Particle.BLOCK, at, 30, 0.4, 0.2, 0.4, 0.1, at.clone().subtract(0, 1, 0).getBlock().getBlockData());
            LivingEntity s = spawnKind(k, at);
            if (s == null) continue;
            tag(s, ARMY_TAG); // same side as the Commander either way
            if (!raid && s instanceof org.bukkit.entity.AbstractSkeleton sk) sk.setShouldBurnInDay(true);
            if (s instanceof Mob m && c instanceof Mob cm && cm.getTarget() != null) m.setTarget(cm.getTarget());
        }
    }

    /** 5) Disengage: too close? He leaps back away from you and fires as he goes. */
    private void disengage(LivingEntity c, Player target) {
        Vector away = c.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        c.setVelocity(away.normalize().multiply(cfg("skeleton-army.disengage-strength", 1.1)).setY(0.55));
        c.getWorld().spawnParticle(Particle.POOF, c.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
        c.getWorld().playSound(c.getLocation(), Sound.ENTITY_SKELETON_STEP, 1.5f, 0.6f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!c.isValid() || c.isDead() || !target.isOnline()) return;
            Location eye = c.getEyeLocation();
            Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
            if (dir.lengthSquared() < 0.01) return;
            org.bukkit.entity.Arrow arrow = c.getWorld().spawnArrow(eye.add(dir.clone().normalize()), dir.normalize(), 2.4f, 1f);
            arrow.setShooter(c);
            arrow.setDamage(cfg("skeleton-army.disengage-arrow-damage", 6));
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1.5f, 1f);
        }, 6L);
    }

    /** The Skeleton Army's arrows hit harder than a normal skeleton's. */
    @EventHandler(ignoreCancelled = true)
    public void onArmyShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.AbstractSkeleton) || !isRaidMob(event.getEntity())) return;
        if (event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.setDamage(arrow.getDamage() * cfg("skeleton-army.arrow-damage-multiplier", 1.3));
        }
    }

    private ItemStack commanderBanner() {
        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        if (banner.getItemMeta() instanceof BannerMeta meta) {
            meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.SKULL));
            meta.addPattern(new Pattern(DyeColor.GRAY, PatternType.BORDER));
            banner.setItemMeta(meta);
        }
        return banner;
    }

    /** Every 15s each Bone Commander sounds his war horn: his side gets Speed + Strength for a few seconds. */
    private void warHornTick() {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity commander : w.getLivingEntities()) {
                if (!commander.getScoreboardTags().contains(COMMANDER_TAG) || commander.isDead()) continue;
                w.playSound(commander.getLocation(), Sound.EVENT_RAID_HORN, 1.5f, 1.4f);
                w.spawnParticle(Particle.NOTE, commander.getEyeLocation().add(0, 0.6, 0), 6, 0.4, 0.2, 0.4, 1);
                for (Entity e : commander.getNearbyEntities(16, 8, 16)) {
                    if (!(e instanceof LivingEntity ally) || !isRaidMob(ally)) continue;
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 160, 0));
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 160, 0));
                }
            }
        }
    }

    /** Shieldbearers block arrows and other projectiles that hit them from the front. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldbearer(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(SHIELD_TAG) || !(event.getDamager() instanceof Projectile proj)) return;
        LivingEntity bearer = (LivingEntity) event.getEntity();
        Location from = proj.getShooter() instanceof Entity shooter ? shooter.getLocation() : proj.getLocation().subtract(proj.getVelocity());
        Vector toShooter = from.toVector().subtract(bearer.getLocation().toVector()).setY(0);
        if (toShooter.lengthSquared() < 0.01) return;
        if (bearer.getLocation().getDirection().setY(0).normalize().dot(toShooter.normalize()) < 0.3) return; // hit from the side or behind
        event.setCancelled(true);
        proj.remove();
        bearer.getWorld().playSound(bearer.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
    }

    private ItemStack captainBanner() {
        ItemStack banner = new ItemStack(Material.BLACK_BANNER);
        if (banner.getItemMeta() instanceof BannerMeta meta) {
            meta.addPattern(new Pattern(DyeColor.LIME, PatternType.SKULL));
            meta.addPattern(new Pattern(DyeColor.GREEN, PatternType.BORDER));
            banner.setItemMeta(meta);
        }
        return banner;
    }

    // ===================== SPAWN EGGS + RAID ITEMS MENU =====================

    ItemStack eggItem(Egg egg) {
        ItemStack item = new ItemStack(egg.material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(egg.name + ChatColor.RESET + ChatColor.GRAY + " Spawn Egg");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to summon.", ChatColor.DARK_GRAY + "Zombie Raid mob"));
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, egg.name());
        item.setItemMeta(meta);
        return item;
    }

    private Egg eggOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String name = item.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
        return name == null ? null : Egg.byName(name);
    }

    @EventHandler
    public void onEggUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Egg egg = eggOf(event.getItem());
        if (egg == null) return;
        // Always deny: never spawn the vanilla mob, never change a spawner.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        boolean spawned;
        if (egg == Egg.SKELETON_ARMY) {
            spawned = spawnSkeletonArmyAt(at, null);
        } else if (egg == Egg.CAPTAIN) {
            spawned = spawnCaptainAt(at, null);
        } else {
            if (egg == Egg.BAT || egg == Egg.FLYER) at.add(0, 1, 0);
            LivingEntity mob = spawnKind(egg.kind, at);
            spawned = mob != null;
            if (mob != null) tag(mob, ARMY_TAG); // egg mobs are one team (no infighting)
        }
        if (spawned && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEggDispense(BlockDispenseEvent event) {
        if (eggOf(event.getItem()) != null) event.setCancelled(true);
    }

    /** Everything raid-related in one menu: the 6 eggs and Zombie Omens I-V. */
    private static class RaidMenuHolder implements InventoryHolder {
        Inventory inventory;
        final List<ItemStack> entries = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void openMenu(Player player) {
        RaidMenuHolder holder = new RaidMenuHolder();
        for (Egg egg : Egg.values()) holder.entries.add(eggItem(egg));
        for (int level = 1; level <= 5; level++) holder.entries.add(omenItem(level));
        for (int level = 1; level <= 5; level++) holder.entries.add(skeletonOmenItem(level));
        holder.inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Raid Items");
        for (int i = 0; i < holder.entries.size(); i++) holder.inventory.setItem(i, holder.entries.get(i));

        ItemStack help = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = help.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "How to use");
        meta.setLore(List.of(ChatColor.GRAY + "Left-click: take 1", ChatColor.GRAY + "Shift-click: take a full stack"));
        help.setItemMeta(meta);
        holder.inventory.setItem(49, help);
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RaidMenuHolder holder)) return;
        event.setCancelled(true); // it's a catalog; nothing in it can be moved
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
        if (!player.hasPermission("zraid.admin")) return;
        int slot = event.getSlot();
        if (slot < 0 || slot >= holder.entries.size()) return;

        ItemStack pick = holder.entries.get(slot).clone();
        pick.setAmount(event.isShiftClick() ? pick.getMaxStackSize() : 1);
        player.getInventory().addItem(pick).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RaidMenuHolder) event.setCancelled(true);
    }

    // ===================== RAID RULES: no sleeping, wipe = loss =====================

    /** Nobody can sleep while any Zombie Raid is going on. */
    @EventHandler(ignoreCancelled = true)
    public void onSleep(org.bukkit.event.player.PlayerBedEnterEvent event) {
        if (raids.isEmpty()) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.DARK_GREEN + "You can't sleep while the dead are raiding.");
    }

    /**
     * A fighter who dies is "down" until they jump back into the fight. If everyone
     * fighting a raid is down at the same time, the raid is lost.
     */
    @EventHandler
    public void onFighterDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        for (ZombieRaid raid : new ArrayList<>(raids)) {
            if (!raid.participants.contains(id)) continue;
            raid.down.add(id);
            // BUG FIX: offline, creative, or other-world participants used to count as
            // "still fighting", so one of them meant a wipe could never be detected.
            boolean anyoneUp = raid.participants.stream().anyMatch(pid -> {
                if (raid.down.contains(pid)) return false;
                Player p = Bukkit.getPlayer(pid);
                return p != null && !p.isDead() && survival(p) && p.getWorld().equals(raid.world);
            });
            if (!anyoneUp) {
                endRaid(raid, false, "Everyone defending the village has fallen. The dead take it.");
            }
        }
    }

    // ===================== SHIELDS vs RAID ATTACKS =====================
    //
    // Blockable: Cutlass Sweep & Flintlock (vanilla shields already handle those),
    // Grappling Hook, Zombie Bat bites, Mage orbs. Heavy (half damage, shield knocked
    // away 5s): Flying Zombie dives. Unblockable: Anchor Slam.

    private boolean dealing; // our own shield decision was made; vanilla shouldn't block again

    static boolean facingBlock(Player p, Vector from) {
        if (!p.isBlocking()) return false;
        Vector to = from.clone().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 0.01) return true;
        return p.getEyeLocation().getDirection().dot(to.normalize()) > 0;
    }

    static void shieldHit(Player p, double damage, boolean heavy) {
        org.bukkit.inventory.EquipmentSlot hand = p.getInventory().getItemInMainHand().getType() == Material.SHIELD
                ? org.bukkit.inventory.EquipmentSlot.HAND : org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        p.damageItemStack(hand, 1 + (int) Math.floor(damage));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        if (heavy) {
            p.setCooldown(Material.SHIELD, 100);
            p.clearActiveItem();
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.8f, 1.2f);
        }
    }

    /** Heavy: facing it with a shield halves the damage but knocks the shield away. */
    private void guardedDamage(LivingEntity victim, double amount, Entity source, Vector from, boolean heavy) {
        if (victim instanceof Player p && facingBlock(p, from)) {
            shieldHit(p, amount, heavy);
            if (!heavy) return;
            amount *= 0.5;
        }
        unblockable(victim, amount, source);
    }

    private void unblockable(LivingEntity victim, double amount, Entity source) {
        dealing = true;
        try {
            victim.damage(amount, source);
        } finally {
            dealing = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuardedDamage(EntityDamageByEntityEvent event) {
        if (dealing && event.isApplicable(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING)) {
            event.setDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING, 0);
        }
    }

    // ===================== COMMAND =====================

    private class RaidCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("zraid.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            switch (sub) {
                case "start" -> {
                    if (!(sender instanceof Player p)) return msg(sender, "Only players can start a raid.");
                    int level = args.length > 1 ? parse(args[1], 1) : 1;
                    boolean skel = args.length > 2 && args[2].toLowerCase().startsWith("skel");
                    startRaid(p.getLocation(), level, p, true, skel);
                    return msg(sender, ChatColor.GREEN + "Started a level " + roman(Math.max(1, Math.min(5, level)))
                            + (skel ? " Skeleton" : " Zombie") + " Raid here (test raid: it won't end if villagers die).");
                }
                case "skeletonarmy" -> {
                    if (!(sender instanceof Player p)) return msg(sender, "Only players can do that.");
                    spawnSkeletonArmy(p);
                    return msg(sender, ChatColor.GREEN + "A Bone Commander and his army approach...");
                }
                case "skeletonomen" -> {
                    int level = args.length > 1 ? parse(args[1], 1) : 1;
                    int amount = args.length > 2 ? Math.max(1, Math.min(64, parse(args[2], 1))) : 1;
                    Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                    if (target == null) return msg(sender, ChatColor.RED + "Usage: /zraid skeletonomen <1-5> [amount] [player]");
                    for (int i = 0; i < amount; i++) {
                        target.getInventory().addItem(skeletonOmenItem(level)).values()
                                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                    }
                    return msg(sender, ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x Skeleton Omen " + roman(Math.max(1, Math.min(5, level))) + ".");
                }
                case "stop" -> {
                    int count = raids.size();
                    for (ZombieRaid raid : new ArrayList<>(raids)) endRaid(raid, false, "The raid was called off.");
                    return msg(sender, ChatColor.GREEN + "Stopped " + count + " raid(s).");
                }
                case "captain" -> {
                    if (!(sender instanceof Player p)) return msg(sender, "Only players can do that.");
                    spawnCaptain(p);
                    return msg(sender, ChatColor.GREEN + "A Zombie Captain and his army approach...");
                }
                case "menu" -> {
                    if (!(sender instanceof Player p)) return msg(sender, "Only players can open the menu.");
                    openMenu(p);
                    return true;
                }
                case "egg" -> {
                    Egg egg = args.length > 1 ? Egg.byName(args[1]) : null;
                    if (egg == null) {
                        return msg(sender, ChatColor.RED + "Usage: /zraid egg <archer|brute|bomber|baby_bomber|bat|flyer|witch|horseman|mage|rotbeard|captain> [amount] [player]");
                    }
                    int amount = args.length > 2 ? Math.max(1, Math.min(64, parse(args[2], 1))) : 1;
                    Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                    if (target == null) return msg(sender, ChatColor.RED + "That player isn't online.");
                    ItemStack item = eggItem(egg);
                    item.setAmount(amount);
                    target.getInventory().addItem(item).values()
                            .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                    return msg(sender, ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x " + ChatColor.stripColor(egg.name) + " Spawn Egg.");
                }
                case "omen" -> {
                    int level = args.length > 1 ? parse(args[1], 1) : 1;
                    int amount = args.length > 2 ? Math.max(1, Math.min(64, parse(args[2], 1))) : 1;
                    Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                    if (target == null) return msg(sender, ChatColor.RED + "Usage: /zraid omen <1-5> [amount] [player]");
                    // optional 5th arg "at:world,x,y,z": drop there, locked to the player (Demon Eye loot)
                    Location dropAt = null;
                    if (args.length > 4 && args[4].startsWith("at:")) {
                        String[] c = args[4].substring(3).split(",");
                        World w = c.length == 4 ? Bukkit.getWorld(c[0]) : null;
                        if (w == null) return false;
                        dropAt = new Location(w, Double.parseDouble(c[1]), Double.parseDouble(c[2]), Double.parseDouble(c[3]));
                    }
                    for (int i = 0; i < amount; i++) {
                        if (dropAt != null) {
                            org.bukkit.entity.Item drop = dropAt.getWorld().dropItem(dropAt, omenItem(level));
                            drop.setOwner(target.getUniqueId());
                            drop.setVelocity(new Vector(random.nextGaussian() * 0.15, 0.3 + random.nextDouble() * 0.2, random.nextGaussian() * 0.15));
                        } else {
                            target.getInventory().addItem(omenItem(level)).values()
                                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                        }
                    }
                    return msg(sender, ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x Zombie Omen " + roman(Math.max(1, Math.min(5, level))) + ".");
                }
                default -> {
                    return msg(sender, ChatColor.YELLOW + "/zraid start <1-5> | stop | captain | menu | egg <type> [amount] [player] | omen <1-5> [amount] [player]");
                }
            }
        }

        private int parse(String s, int def) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private boolean msg(CommandSender sender, String text) {
            sender.sendMessage(text);
            return true;
        }
    }

    // ===================== SAVE / LOAD =====================

    private void loadOmens() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(omenFile);
        for (String key : yaml.getKeys(false)) {
            try {
                Omen omen = new Omen();
                omen.level = yaml.getInt(key + ".level");
                omen.expires = yaml.getLong(key + ".expires");
                omen.skeleton = yaml.getBoolean(key + ".skeleton", false);
                omens.put(UUID.fromString(key), omen);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveOmens() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Omen> entry : omens.entrySet()) {
            yaml.set(entry.getKey() + ".level", entry.getValue().level);
            yaml.set(entry.getKey() + ".expires", entry.getValue().expires);
            yaml.set(entry.getKey() + ".skeleton", entry.getValue().skeleton);
        }
        try {
            yaml.save(omenFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save omens.yml", e);
        }
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
