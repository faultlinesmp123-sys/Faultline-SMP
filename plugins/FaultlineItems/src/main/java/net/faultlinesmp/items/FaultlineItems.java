package net.faultlinesmp.items;

import io.papermc.paper.potion.PotionMix;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInputEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Snowball;
import org.bukkit.plugin.Plugin;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Enemy;
import org.bukkit.Tag;
import org.bukkit.Difficulty;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.entity.Stray;
import org.bukkit.block.Biome;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Bat;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Monster;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Item;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Trident;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.Chunk;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.entity.Zombie;
import java.util.Iterator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.EquipmentSlotGroup;

public final class FaultlineItems extends JavaPlugin {

    /**
     * Every config load (startup, /itemsreload, /freload) checks config.yml first. One mis-indented line makes Bukkit
     * ignore the WHOLE file and silently use the defaults (that's how a pasted, indented "item-textures:" turned the
     * resource pack off). A section name that belongs at the start of a line but got indented is moved back, the
     * original is kept as config.yml.broken-<time>, and the console says exactly what was fixed.
     */
    @Override
    public void reloadConfig() {
        try {
            repairConfigFile();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Couldn't check config.yml for formatting problems", e);
        }
        super.reloadConfig();
    }

    private void repairConfigFile() throws IOException {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String problem = yamlProblem(text);
        if (problem == null) return;

        String defaults;
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null) return;
            defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> changes = new ArrayList<>();
        String result = repairYaml(text, defaults, changes);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        File backup = new File(getDataFolder(), "config.yml.broken-" + stamp);
        if (!changes.isEmpty() && yamlProblem(result) == null) {
            java.nio.file.Files.copy(file.toPath(), backup.toPath());
            java.nio.file.Files.writeString(file.toPath(), result, StandardCharsets.UTF_8);
            getLogger().warning("config.yml had a formatting error, so it was FIXED automatically (original saved as " + backup.getName() + "):");
            for (String c : changes) getLogger().warning("  - " + c);
        } else {
            getLogger().severe("config.yml has a formatting error that couldn't be fixed automatically, so the plugin is using its"
                    + " DEFAULT settings until it's fixed: " + problem);
        }
    }

    /** Moves top-level section names (per the default config) back to column 0 and replaces tabs; lists what changed. */
    static String repairYaml(String text, String defaults, List<String> changes) {
        // section names that sit at the start of a line in the default config, and are never used as nested keys
        java.util.Set<String> topLevel = new java.util.HashSet<>(), nested = new java.util.HashSet<>();
        java.util.regex.Pattern key = java.util.regex.Pattern.compile("^(\\s*)([A-Za-z0-9_-]+):");
        for (String line : defaults.split("\\R")) {
            java.util.regex.Matcher m = key.matcher(line);
            if (m.find()) (m.group(1).isEmpty() ? topLevel : nested).add(m.group(2));
        }
        topLevel.removeAll(nested);
        StringBuilder fixed = new StringBuilder();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\t", "  ");
            if (!line.equals(lines[i])) changes.add("line " + (i + 1) + ": replaced tabs with spaces");
            java.util.regex.Matcher m = key.matcher(line);
            if (m.find() && !m.group(1).isEmpty() && topLevel.contains(m.group(2))) {
                line = line.substring(m.group(1).length());
                changes.add("line " + (i + 1) + ": moved '" + m.group(2) + ":' back to the start of the line");
            }
            fixed.append(line);
            if (i < lines.length - 1) fixed.append('\n');
        }
        return fixed.toString();
    }

    /** null if the text is valid YAML, otherwise the parser's message (it names the line). */
    static String yamlProblem(String text) {
        try {
            new YamlConfiguration().loadFromString(text);
            return null;
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            return e.getMessage() == null ? "unknown error" : e.getMessage().replaceAll("\\s+", " ");
        }
    }
    /** Adds a recipe, replacing any copy left over from before a reload ("Duplicate recipe" would stop the plugin from starting). */
    static void addRecipeSafely(org.bukkit.inventory.Recipe recipe) {
        if (recipe instanceof org.bukkit.Keyed keyed) org.bukkit.Bukkit.removeRecipe(keyed.getKey());
        try {
            org.bukkit.Bukkit.addRecipe(recipe);
        } catch (IllegalStateException e) {
            org.bukkit.Bukkit.getLogger().warning("Couldn't add a recipe: " + e.getMessage());
        }
    }


    private NamespacedKey magicMirrorKey;
    private NamespacedKey usesKey;
    private NamespacedKey cloudPotionKey;
    private NamespacedKey darknessStageKey;
    private NamespacedKey goldenRingKey;
    private NamespacedKey goodieBagKey;
    private NamespacedKey mythicGoodieBagKey;
    private NamespacedKey lavaCharmKey;
    private NamespacedKey blackBeltKey;
    private NamespacedKey moonStoneKey;
    private NamespacedKey rangerEmblemKey;
    private NamespacedKey ironStickKey;
    private NamespacedKey necroStaffKey;
    private NamespacedKey bloodyWolfScarfKey;
    private NamespacedKey frostFlareKey;
    private NamespacedKey spelunkerAmuletKey;
    private NamespacedKey harpyRingKey;
    private NamespacedKey ankhShieldKey;
    private NamespacedKey cleftHornKey;
    private NamespacedKey weirdClockKey;
    private NamespacedKey hermesBootsKey;
    private NamespacedKey rocketBootsKey;
    private NamespacedKey spectreBootsKey;

    /** Set by the FaultlineEvents plugin on the main world while an event is running. */
    private static final NamespacedKey ACTIVE_EVENT_KEY = NamespacedKey.fromString("faultlineevents:active_event");
    private NecromancerManager necromancerManager;
    private NamespacedKey vampireEggKey;
    private VampireManager vampireManager;
    private NamespacedKey frostWraithEggKey;
    private NamespacedKey spiderStaffKey;
    private NamespacedKey queenEggKey;
    private QueenSpiderManager queenSpiderManager;
    private BatFormManager batFormManager;
    private NamespacedKey grapplingHookKey;
    private NamespacedKey queenSilkKey;
    private BossLootListener bossLootListener;
    private NamespacedKey accessoryKey;
    private NamespacedKey climbingClawsKey;
    private NamespacedKey bezoarKey;
    private NamespacedKey discountCardKey;
    private NamespacedKey lifeJellyKey;
    private NamespacedKey shieldOfTheOceanKey;

    private TeleportManager teleportManager;
    private DoubleJumpManager doubleJumpManager;
    private CombatTracker combatTracker;
    private AccessoryManager accessoryManager;
    private ItemTextureManager itemTextureManager;
    private LifeJellyListener lifeJellyListener;
    private ShieldOfTheOceanListener shieldOfTheOceanListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig(); // runs the config repair below before anything reads the config

        this.magicMirrorKey = new NamespacedKey(this, "magic_mirror");
        this.usesKey = new NamespacedKey(this, "magic_mirror_uses");
        this.cloudPotionKey = new NamespacedKey(this, "cloud_potion");
        this.darknessStageKey = new NamespacedKey(this, "darkness_stage");
        this.goldenRingKey = new NamespacedKey(this, "golden_ring");
        this.goodieBagKey = new NamespacedKey(this, "goodie_bag");
        this.mythicGoodieBagKey = new NamespacedKey(this, "mythic_goodie_bag");
        this.lavaCharmKey = new NamespacedKey(this, "lava_charm");
        this.blackBeltKey = new NamespacedKey(this, "black_belt");
        this.moonStoneKey = new NamespacedKey(this, "moon_stone");
        this.rangerEmblemKey = new NamespacedKey(this, "ranger_emblem");
        this.ironStickKey = new NamespacedKey(this, "iron_stick");
        this.necroStaffKey = new NamespacedKey(this, "necromancer_staff");
        this.vampireEggKey = new NamespacedKey(this, "vampire_egg");
        this.frostWraithEggKey = new NamespacedKey(this, "frost_wraith_egg");
        this.spiderStaffKey = new NamespacedKey(this, "spider_staff");
        this.queenEggKey = new NamespacedKey(this, "queen_spider_egg");
        this.grapplingHookKey = new NamespacedKey(this, "grappling_hook");
        this.queenSilkKey = new NamespacedKey(this, "queen_silk");
        this.bloodyWolfScarfKey = new NamespacedKey(this, "bloody_wolf_scarf");
        this.frostFlareKey = new NamespacedKey(this, "frost_flare");
        this.spelunkerAmuletKey = new NamespacedKey(this, "spelunker_amulet");
        this.harpyRingKey = new NamespacedKey(this, "harpy_ring");
        this.ankhShieldKey = new NamespacedKey(this, "ankh_shield");
        this.cleftHornKey = new NamespacedKey(this, "cleft_horn");
        this.weirdClockKey = new NamespacedKey(this, "weird_clock");
        this.hermesBootsKey = new NamespacedKey(this, "hermes_boots");
        this.rocketBootsKey = new NamespacedKey(this, "rocket_boots");
        this.spectreBootsKey = new NamespacedKey(this, "spectre_boots");
        this.accessoryKey = new NamespacedKey(this, "is_accessory");
        this.climbingClawsKey = new NamespacedKey(this, "climbing_claws");
        this.bezoarKey = new NamespacedKey(this, "bezoar");
        this.discountCardKey = new NamespacedKey(this, "discount_card");
        this.lifeJellyKey = new NamespacedKey(this, "life_jelly");
        this.shieldOfTheOceanKey = new NamespacedKey(this, "shield_of_the_ocean");

        this.teleportManager = new TeleportManager(this);
        this.doubleJumpManager = new DoubleJumpManager(this);
        this.combatTracker = new CombatTracker(this);
        this.accessoryManager = new AccessoryManager(this);
        this.itemTextureManager = new ItemTextureManager(this);
        this.lifeJellyListener = new LifeJellyListener(this);
        this.shieldOfTheOceanListener = new ShieldOfTheOceanListener(this);

        // Magic Mirror
        getServer().getPluginManager().registerEvents(new MagicMirrorListener(this), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new AnvilRepairListener(this), this);
        getServer().getPluginManager().registerEvents(combatTracker, this);
        getCommand("givemirror").setExecutor(new GiveItemCommand(this, "items.givemirror", "/givemirror [amount] [player]", ItemCatalog.single(MagicMirrorItem::create)));
        getServer().removeRecipe(magicMirrorKey); // safe no-op normally; prevents a crash on /reload
        FaultlineItems.addRecipeSafely(MagicMirrorItem.buildRecipe(this));

        // Cloud Potion
        getServer().getPluginManager().registerEvents(new CloudPotionListener(this), this);
        getCommand("givecloudpotion").setExecutor(new GiveItemCommand(this, "items.givecloudpotion", "/givecloudpotion [amount] [player]", ItemCatalog.single(CloudPotionItem::create)));
        CloudPotionItem.registerBrewingRecipe(this);

        // Darkness Potion
        getCommand("givedarkness").setExecutor(new GiveItemCommand(this, "items.givedarkness", "/givedarkness [final|stage1|stage2] [amount] [player]", ItemCatalog.DARKNESS_TYPES, "final"));
        DarknessPotionItem.registerBrewingRecipes(this);

        // Golden Ring
        getServer().getPluginManager().registerEvents(new GoldenRingListener(this), this);
        getCommand("givegoldenring").setExecutor(new GiveItemCommand(this, "items.givegoldenring", "/givegoldenring [amount] [player]", ItemCatalog.single(GoldenRingItem::create)));
        getServer().removeRecipe(goldenRingKey);
        FaultlineItems.addRecipeSafely(GoldenRingItem.buildRecipe(this));

        // Goodie Bag (no recipe yet — drops from mob kills, or admin/testing give)
        getServer().getPluginManager().registerEvents(new GoodieBagListener(this), this);
        getCommand("givegoodiebag").setExecutor(new GiveItemCommand(this, "items.givegoodiebag", "/givegoodiebag [amount] [player]", ItemCatalog.single(GoodieBagItem::create)));

        // Mythic Goodie Bag (drops from Wardens/Withers; LimboBlackMarket also
        // hands these out as quest rewards via /givemythicbag from the console)
        getServer().getPluginManager().registerEvents(new MythicGoodieBagListener(this), this);
        // Lava Charm / Black Belt / Moon Stone / Ranger Emblem (accessories) + their drops
        getServer().getPluginManager().registerEvents(new NewAccessoryListener(this), this);
        getServer().getPluginManager().registerEvents(new NewItemDropListener(this), this);
        getServer().getPluginManager().registerEvents(new ImmunityCharms(this), this);
        getServer().getPluginManager().registerEvents(new AnkhImbue(this), this);
        // diving gear (Calamity-style) and the Skeleton Wanderer (a cave trader who sells accessories)
        divingGear = new DivingGear(this);
        getServer().getPluginManager().registerEvents(divingGear, this);
        getCommand("givediving").setExecutor(new GiveItemCommand(this, "items.givediving",
                "/givediving <helmet|flipper|gear|depths|abyssal> [amount] [player]", divingGear.catalog(), "gear"));
        jacobGear = new JacobGear(this);
        getServer().getPluginManager().registerEvents(jacobGear, this);
        getCommand("givejacob").setExecutor(new GiveItemCommand(this, "items.givejacob",
                "/givejacob <falconers_quiver|ender_anchor|banner_of_defiance|ember_ward|diamond_heart> [amount] [player]", jacobGear.catalog(), "falconers_quiver"));
        mirrorGear = new MirrorGear(this);
        getServer().getPluginManager().registerEvents(mirrorGear, this);
        getCommand("givelostmirror").setExecutor(new GiveItemCommand(this, "items.givelostmirror",
                "/givelostmirror [amount] [player]", ItemCatalog.single(pl -> pl.getMirrorGear().item())));
        krakenGear = new KrakenGear(this);
        getServer().getPluginManager().registerEvents(krakenGear, this);
        getCommand("givekraken").setExecutor(new GiveItemCommand(this, "items.givekraken",
                "/givekraken <worm|coral_lung|glow_gland|eelskin_wrap> [amount] [player]", krakenGear.catalog(), "worm"));
        caveTrader = new CaveTrader(this, divingGear);
        getServer().getPluginManager().registerEvents(caveTrader, this);
        getCommand("cavetrader").setExecutor(caveTrader);
        gear = new Gear(this);
        getServer().getPluginManager().registerEvents(gear, this);
        getCommand("fgear").setExecutor(gear);
        getCommand("giveancientgear").setExecutor(gear);
        getCommand("givedisc").setExecutor(gear);
        weather = new Weather(this);
        getServer().getPluginManager().registerEvents(weather, this);
        getCommand("fweather").setExecutor(weather);
        getCommand("fweather").setTabCompleter(weather);
        getServer().getPluginManager().registerEvents(new StandGuard(this), this);
        if (getCommand("bedrockcheck") != null) getCommand("bedrockcheck").setExecutor(new BedrockCheck(this));
        // tell the console straight away when Bedrock players won't get the packs
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            try {
                List<String> rep = new BedrockCheck(this).report();
                if (rep.stream().anyMatch(l -> l.contains(" !!  "))) {
                    getLogger().warning("Bedrock packs: problems found (run /bedrockcheck in game for the full list):");
                    for (String l : rep) if (l.contains(" !!  ")) getLogger().warning(ChatColor.stripColor(l).trim());
                }
            } catch (Throwable t) { getLogger().warning("Bedrock pack check failed: " + t); }
        }, 200L);
        creatures = new Creatures(this);
        getServer().getPluginManager().registerEvents(creatures, this);
        getCommand("fcreature").setExecutor(creatures);
        meteor = new Meteor(this);
        getServer().getPluginManager().registerEvents(meteor, this);
        getCommand("meteor").setExecutor(meteor);
        caravan = new Caravan(this);
        getServer().getPluginManager().registerEvents(caravan, this);
        getCommand("caravan").setExecutor(caravan);
        getServer().getPluginManager().registerEvents(new CraftGuardListener(this), this);
        NewAccessoryTicker newAccessoryTicker = new NewAccessoryTicker(this);
        getServer().getScheduler().runTaskTimer(this, newAccessoryTicker::tick, 20L, 20L);

        // Bloody Wolf Scarf / Frost Flare / Spelunker's Amulet / Harpy Ring / Ankh Shield + fishing
        getServer().getPluginManager().registerEvents(new FiveAccessoryListener(this), this);
        getServer().getPluginManager().registerEvents(new FishingListener(this), this);
        getServer().removeRecipe(harpyRingKey);
        FaultlineItems.addRecipeSafely(NewAccessoryItems.harpyRingRecipe(this));

        // Vampire (custom mob)
        this.vampireManager = new VampireManager(this);
        vampireManager.removeLeftovers();
        getServer().getPluginManager().registerEvents(vampireManager, this);
        getServer().getScheduler().runTaskTimer(this, vampireManager::tick, 2L, 2L);
        getServer().getScheduler().runTaskTimer(this, vampireManager::bleedTick, 20L, 20L);
        int[] vampSecond = {0};
        getServer().getScheduler().runTaskTimer(this, () -> vampireManager.bloodMoonTick(++vampSecond[0]), 20L, 20L);
        getCommand("givevampireegg").setExecutor(new GiveItemCommand(this, "items.givevampireegg", "/givevampireegg [amount] [player]", ItemCatalog.single(VampireEggItem::create)));

        // Frost Wraith (custom mob, Snowy Days only)
        FrostWraithManager frostWraithManager = new FrostWraithManager(this);
        getServer().getPluginManager().registerEvents(frostWraithManager, this);
        int[] snowSecond = {0};
        getServer().getScheduler().runTaskTimer(this, () -> frostWraithManager.surfaceSpawnTick(++snowSecond[0]), 20L, 20L);
        getCommand("givefrostwraithegg").setExecutor(new GiveItemCommand(this, "items.givefrostwraithegg", "/givefrostwraithegg [amount] [player]", ItemCatalog.single(FrostWraithEggItem::create)));

        // Queen Spider (cave boss) + Spider Staff (uses the Necromancer minion system below)
        this.queenSpiderManager = new QueenSpiderManager(this);
        getServer().getPluginManager().registerEvents(queenSpiderManager, this);
        getServer().getScheduler().runTaskTimer(this, queenSpiderManager::tick, 10L, 10L);
        getCommand("queenspider").setExecutor((sender, cmd, label, args) -> { // /queenspider spawn [<world> <x> <y> <z>] (the Boss Rush uses this)
            if (args.length == 0 || !args[0].equalsIgnoreCase("spawn")) { sender.sendMessage(ChatColor.YELLOW + "/queenspider spawn [<world> <x> <y> <z>]"); return true; }
            Location at;
            if (args.length >= 5) {
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "No world " + args[1]); return true; }
                try { at = new Location(w, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4])); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Bad coordinates."); return true; }
            } else if (sender instanceof Player p) at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
            else { sender.sendMessage("Give a world and coordinates."); return true; }
            if (!(at.getWorld().spawnEntity(at, EntityType.SPIDER, false) instanceof Spider spider) || !spider.isValid()) return false;
            queenSpiderManager.makeQueen(spider);
            sender.sendMessage(ChatColor.GREEN + "A Queen Spider appeared.");
            return true;
        });
        getCommand("givespiderstaff").setExecutor(new GiveItemCommand(this, "items.givespiderstaff", "/givespiderstaff [amount] [player]", ItemCatalog.single(SpiderStaffItem::create)));
        getCommand("givequeenspideregg").setExecutor(new GiveItemCommand(this, "items.givequeenspideregg", "/givequeenspideregg [amount] [player]", ItemCatalog.single(QueenSpiderEggItem::create)));

        // Boots: Hermes / Rocket / Spectre (+ rocket flight, anvil combo, Armorer trades)
        RocketBootsManager rocketBoots = new RocketBootsManager(this);
        getServer().getPluginManager().registerEvents(rocketBoots, this);
        getServer().getScheduler().runTaskTimer(this, rocketBoots::tick, 1L, 1L);
        getServer().getPluginManager().registerEvents(new BootsListener(this), this);

        // Boss loot: Rotbeard's Grappling Hook and the Queen's Silk
        this.bossLootListener = new BossLootListener(this);
        getServer().getPluginManager().registerEvents(bossLootListener, this);
        getCommand("givegrapplinghook").setExecutor(new GiveItemCommand(this, "items.givegrapplinghook", "/givegrapplinghook [amount] [player]", ItemCatalog.single(GrapplingHookItem::create)));
        getCommand("givequeensilk").setExecutor(new GiveItemCommand(this, "items.givequeensilk", "/givequeensilk [amount] [player]", ItemCatalog.single(QueenSilkItem::create)));

        // Moon Stone bat form (Shift + F or /batform)
        this.batFormManager = new BatFormManager(this);
        getServer().getPluginManager().registerEvents(batFormManager, this);
        getServer().getScheduler().runTaskTimer(this, batFormManager::tick, 2L, 2L);
        getCommand("batform").setExecutor(new BatFormCommand(this));
        // Flight guard: no survival player flies without a reason (bat form, Cloud Potion, a /fly permission)
        getServer().getScheduler().runTaskTimer(this, () -> FlightGuard.tick(this), 40L, 10L);

        // Necromancer Staff
        this.necromancerManager = new NecromancerManager(this);
        necromancerManager.removeLeftovers();
        getServer().getPluginManager().registerEvents(necromancerManager, this);
        getServer().getScheduler().runTaskTimer(this, necromancerManager::tick, 20L, 20L);
        getServer().removeRecipe(ironStickKey);
        FaultlineItems.addRecipeSafely(IronStickItem.recipe(this));
        getServer().removeRecipe(necroStaffKey);
        FaultlineItems.addRecipeSafely(NecromancerStaffItem.recipe(this));
        getCommand("givenecrostaff").setExecutor(new GiveItemCommand(this, "items.givenecrostaff", "/givenecrostaff [amount] [player]", ItemCatalog.single(NecromancerStaffItem::create)));
        getCommand("unlocknecromancer").setExecutor(new UnlockNecromancerCommand(this));

        getCommand("givemythicbag").setExecutor(new GiveItemCommand(this, "items.givemythicbag", "/givemythicbag [amount] [player]", ItemCatalog.single(MythicGoodieBagItem::create)));

        // Accessories GUI (2 slots) — see AccessoryManager for why this exists
        // instead of real inventory slots next to the shield.
        getServer().getPluginManager().registerEvents(new AccessoryGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new AccessoryDeathListener(this), this);
        getCommand("accessories").setExecutor(new AccessoryCommand(this));

        // Climbing Claws (no recipe/drop source yet — admin/testing give only)
        getServer().getPluginManager().registerEvents(new ClimbingClawsListener(this), this);

        // Bezoar (drops from Bees/Cave Spiders)
        getServer().getPluginManager().registerEvents(new BezoarListener(this), this);

        // Discount Card (drops from Villagers)
        getServer().getPluginManager().registerEvents(new DiscountCardListener(this), this);

        // Life Jelly (drops from Glow Squids) — periodic heal tick
        getServer().getPluginManager().registerEvents(lifeJellyListener, this);
        getServer().getScheduler().runTaskTimer(this, lifeJellyListener::tick, 40L, 40L);

        // Shield of the Ocean (guaranteed drop from Elder Guardians) — periodic speed/swim tick
        getServer().getPluginManager().registerEvents(shieldOfTheOceanListener, this);
        getServer().getScheduler().runTaskTimer(this, shieldOfTheOceanListener::tick, 20L, 20L);

        getCommand("giveaccessory").setExecutor(new GiveItemCommand(this, "items.giveaccessory", "/giveaccessory <claws|bezoar|discount|jelly|shield|lavacharm|blackbelt|moonstone|ranger|scarf|frostflare|spelunker|harpy|ankh|clefthorn|weirdclock|divinghelmet|flipper|divinggear|depthscharm|abyssalgear|abyssalsuit|corallung|glowgland|eelskin|(any immunity charm)> [amount] [player]", ItemCatalog.ACCESSORY_TYPES, null));

        // Admin item menu — every Faultline item in one place (closest thing to a creative tab a plugin can make).
        getServer().getPluginManager().registerEvents(new ItemsMenuListener(this), this);
        getCommand("itemsmenu").setExecutor(new ItemsMenuCommand(this));

        getCommand("itemsreload").setExecutor(new ItemsReloadCommand(this));

        getServer().getPluginManager().registerEvents(new ItemTextureJoinListener(this), this);
        getCommand("testtexture").setExecutor(new TestTextureCommand());

        getServer().getPluginManager().registerEvents(new RecipeDiscoveryListener(this), this);
        for (Player player : getServer().getOnlinePlayers()) {
            player.discoverRecipes(List.of(magicMirrorKey, goldenRingKey, ironStickKey, necroStaffKey, harpyRingKey));
        }

        // Accessories also save immediately on every GUI close and death (see
        // AccessoryManager#setSlots). This timer is just a safety net. BUG FIX:
        // this used to run ASYNC, touching the same HashMap the main thread
        // edits plus serializing ItemStacks off-thread — neither is thread-safe.
        getServer().getScheduler().runTaskTimer(this, accessoryManager::save, 20L * 60L * 5L, 20L * 60L * 5L);

        getLogger().info("Faultline SMP Items enabled — Magic Mirror, Cloud Potion, Darkness Potion, "
                + "Golden Ring, Goodie Bag, and 5 Accessory items loaded.");
    }

    @Override
    public void onDisable() {
        if (caveTrader != null) caveTrader.removeAll();
        if (meteor != null) meteor.shutdown();
        if (weather != null) weather.shutdown();
        if (creatures != null) creatures.shutdown();
        if (gear != null) gear.shutdown();
        if (caravan != null) caravan.shutdown();
        if (accessoryManager != null) {
            accessoryManager.save();
        }
        if (necromancerManager != null) {
            necromancerManager.removeAll();
        }
        if (vampireManager != null) {
            vampireManager.removeAll();
        }
        if (queenSpiderManager != null) {
            queenSpiderManager.removeAll();
        }
        if (batFormManager != null) {
            batFormManager.endAll(); // restore everyone's health, size, and flight
        }
        if (bossLootListener != null) {
            bossLootListener.clearAllWebs();
        }
        // Unregister brewing mixes so a /reload doesn't try to register them twice.
        for (String key : List.of("cloud_potion_mix", "darkness_stage1", "darkness_stage2", "darkness_final")) {
            Bukkit.getPotionBrewer().removePotionMix(new NamespacedKey(this, key));
        }
    }

    public NamespacedKey getMagicMirrorKey() {
        return magicMirrorKey;
    }

    public NamespacedKey getUsesKey() {
        return usesKey;
    }

    public NamespacedKey getCloudPotionKey() {
        return cloudPotionKey;
    }

    public NamespacedKey getDarknessStageKey() {
        return darknessStageKey;
    }

    public NamespacedKey getGoldenRingKey() {
        return goldenRingKey;
    }

    public NamespacedKey getGoodieBagKey() {
        return goodieBagKey;
    }

    public NamespacedKey getMythicGoodieBagKey() {
        return mythicGoodieBagKey;
    }

    public NamespacedKey getLavaCharmKey() {
        return lavaCharmKey;
    }

    public NamespacedKey getBlackBeltKey() {
        return blackBeltKey;
    }

    public NamespacedKey getMoonStoneKey() {
        return moonStoneKey;
    }

    public NamespacedKey getRangerEmblemKey() {
        return rangerEmblemKey;
    }

    public NamespacedKey getIronStickKey() {
        return ironStickKey;
    }

    public NamespacedKey getNecroStaffKey() {
        return necroStaffKey;
    }

    public NecromancerManager getNecromancerManager() {
        return necromancerManager;
    }

    public NamespacedKey getVampireEggKey() {
        return vampireEggKey;
    }

    public NamespacedKey getFrostWraithEggKey() {
        return frostWraithEggKey;
    }

    public NamespacedKey getSpiderStaffKey() {
        return spiderStaffKey;
    }

    public NamespacedKey getQueenEggKey() {
        return queenEggKey;
    }

    public BatFormManager getBatFormManager() {
        return batFormManager;
    }

    public NamespacedKey getGrapplingHookKey() {
        return grapplingHookKey;
    }

    public NamespacedKey getQueenSilkKey() {
        return queenSilkKey;
    }

    public NamespacedKey getBloodyWolfScarfKey() {
        return bloodyWolfScarfKey;
    }

    public NamespacedKey getFrostFlareKey() {
        return frostFlareKey;
    }

    public NamespacedKey getSpelunkerAmuletKey() {
        return spelunkerAmuletKey;
    }

    public NamespacedKey getHarpyRingKey() {
        return harpyRingKey;
    }

    public NamespacedKey getWeirdClockKey() {
        return weirdClockKey;
    }

    public NamespacedKey getCleftHornKey() {
        return cleftHornKey;
    }

    public NamespacedKey getHermesBootsKey() {
        return hermesBootsKey;
    }

    public NamespacedKey getRocketBootsKey() {
        return rocketBootsKey;
    }

    public NamespacedKey getSpectreBootsKey() {
        return spectreBootsKey;
    }

    public NamespacedKey getAnkhShieldKey() {
        return ankhShieldKey;
    }

    // ---------- shields vs custom boss attacks ----------

    /** A vanilla shield block already happened on this hit (the blocking reduction applied). */
    static boolean shieldBlocked(EntityDamageEvent event) {
        return event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0;
    }

    /** Raising a shield and facing where the attack comes from (within 90 degrees). */
    static boolean facingBlock(Player p, Vector from) {
        if (!p.isBlocking()) return false;
        Vector to = from.clone().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 0.01) return true;
        return p.getEyeLocation().getDirection().dot(to.normalize()) > 0;
    }

    static void shieldHit(Player p, double damage) {
        EquipmentSlot hand = p.getInventory().getItemInMainHand().getType() == Material.SHIELD ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        p.damageItemStack(hand, 1 + (int) Math.floor(damage));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
    }

    /** True while FaultlineEvents is running this event (e.g. "LANTERN_NIGHT"). Safe if FaultlineEvents isn't installed. */
    /** The FaultlineEvents event running now (BLOOD_MOON, ...), or null. */
    public String faultlineEvent() {
        if (Bukkit.getWorlds().isEmpty() || ACTIVE_EVENT_KEY == null) return null;
        String active = Bukkit.getWorlds().get(0).getPersistentDataContainer().get(ACTIVE_EVENT_KEY, PersistentDataType.STRING);
        return active == null || active.isBlank() ? null : active;
    }

    public boolean isEventActive(String eventName) {
        if (Bukkit.getWorlds().isEmpty() || ACTIVE_EVENT_KEY == null) return false;
        String active = Bukkit.getWorlds().get(0).getPersistentDataContainer().get(ACTIVE_EVENT_KEY, PersistentDataType.STRING);
        return eventName.equals(active);
    }

    /**
     * Mobs a player can make on demand: bred or hatched animals, built golems, spawn eggs, and anything a plugin
     * spawned (boss minions like Rocco's family, raid waves, Necromancer minions, the Golden Ring's extra spawns).
     * Item drops that hand out diamonds or rare gear skip these, or a breeding pen / snow golem line / boss add wave
     * turns into an item printer.
     */
    static boolean farmedMob(LivingEntity e) {
        // (spawners have their own config toggles at each drop table, so they're not decided here)
        return switch (e.getEntitySpawnReason()) {
            case BREEDING, EGG, DISPENSE_EGG, SPAWNER_EGG, BUILD_SNOWMAN, BUILD_IRONGOLEM, VILLAGE_DEFENSE,
                 CUSTOM, COMMAND, DUPLICATION, BUCKET, SLIME_SPLIT -> true;
            default -> false;
        };
    }

    /** Lantern Night doubles custom item drop chances (mob drops, ore drops, fishing). */
    public double eventDropMultiplier() {
        return isEventActive("LANTERN_NIGHT") ? getConfig().getDouble("lantern-night.drop-multiplier", 2.0) : 1.0;
    }

    public NamespacedKey getAccessoryKey() {
        return accessoryKey;
    }

    public NamespacedKey getClimbingClawsKey() {
        return climbingClawsKey;
    }

    public NamespacedKey getBezoarKey() {
        return bezoarKey;
    }

    public NamespacedKey getDiscountCardKey() {
        return discountCardKey;
    }

    public NamespacedKey getLifeJellyKey() {
        return lifeJellyKey;
    }

    public NamespacedKey getShieldOfTheOceanKey() {
        return shieldOfTheOceanKey;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    public DoubleJumpManager getDoubleJumpManager() {
        return doubleJumpManager;
    }

    public CombatTracker getCombatTracker() {
        return combatTracker;
    }

    private DivingGear divingGear;
    private CaveTrader caveTrader;
    Meteor meteor;
    Caravan caravan;
    Weather weather;
    Creatures creatures;
    Gear gear;
    Gear getGear() { return gear; }

    DivingGear getDivingGear() { return divingGear; }

    private KrakenGear krakenGear;
    private JacobGear jacobGear;
    JacobGear getJacobGear() { return jacobGear; }
    private MirrorGear mirrorGear;
    MirrorGear getMirrorGear() { return mirrorGear; }
    KrakenGear getKrakenGear() { return krakenGear; }

    public AccessoryManager getAccessoryManager() {
        return accessoryManager;
    }

    public ItemTextureManager getItemTextureManager() {
        return itemTextureManager;
    }

    // ===================== ACCESSORY SYSTEM =====================

    static class AccessoryManager {

        public static final int SLOT_COUNT = 3;

        private final FaultlineItems plugin;
        private final File file;
        private final Map<UUID, ItemStack[]> slots = new HashMap<>();
        private boolean dirty = false;

        public AccessoryManager(FaultlineItems plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "accessories.yml");
            load();
        }

        private void load() {
            if (!file.exists()) return;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ItemStack[] items = new ItemStack[SLOT_COUNT];
                    for (int i = 0; i < SLOT_COUNT; i++) {
                        items[i] = yaml.getItemStack(key + ".slot" + i, null);
                    }
                    slots.put(uuid, items);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping invalid UUID in accessories.yml: " + key);
                }
            }
        }

        public void save() {
            if (!dirty) return;
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, ItemStack[]> entry : slots.entrySet()) {
                for (int i = 0; i < SLOT_COUNT; i++) {
                    ItemStack item = entry.getValue()[i];
                    if (item != null) {
                        yaml.set(entry.getKey() + ".slot" + i, item);
                    }
                }
            }
            try {
                yaml.save(file);
                dirty = false;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save accessories.yml", e);
            }
        }

        public ItemStack[] getSlots(UUID playerId) {
            return slots.computeIfAbsent(playerId, k -> new ItemStack[SLOT_COUNT]);
        }

        public void setSlots(UUID playerId, ItemStack[] items) {
            ItemStack[] copy = new ItemStack[SLOT_COUNT];
            for (int i = 0; i < SLOT_COUNT; i++) {
                copy[i] = (i < items.length) ? items[i] : null;
            }
            slots.put(playerId, copy);
            dirty = true;
            save(); // immediate — a crash before the periodic save would otherwise lose equipped accessories
        }

        /** True if the player has this specific tagged accessory item equipped in either slot. */
        public boolean hasEquipped(Player player, org.bukkit.NamespacedKey itemKey) {
            ItemStack[] items = getSlots(player.getUniqueId());
            // BUG FIX: with /accessories open, what's really worn is what's in the menu right now (the saved slots only
            // update when it closes). Taking an accessory out onto the cursor (or into your inventory, or dropping it
            // for a friend) kept its powers until the menu was closed.
            var top = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof AccessoryGuiHolder) {
                items = new ItemStack[AccessoryGuiHolder.SLOTS.length];
                for (int i = 0; i < AccessoryGuiHolder.SLOTS.length; i++) items[i] = top.getItem(AccessoryGuiHolder.SLOTS[i]);
            }
            for (ItemStack item : items) {
                if (item == null || !item.hasItemMeta()) continue;
                Byte tag = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
                if (tag != null && tag == (byte) 1) return true;
            }
            return false;
        }

        public static boolean isAccessory(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getAccessoryKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class AccessoryGuiHolder implements InventoryHolder {

        /** Menu slots that hold accessories, in order (index 0 = first accessory slot). */
        public static final int[] SLOTS = {2, 4, 6};

        static int indexOf(int menuSlot) {
            for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == menuSlot) return i;
            return -1;
        }

        private Inventory inventory;

        public Inventory build(FaultlineItems plugin, Player player) {
            inventory = Bukkit.createInventory(this, 9, ChatColor.GOLD + "Accessories");

            ItemStack[] equipped = plugin.getAccessoryManager().getSlots(player.getUniqueId());

            // Decorative filler so the real slots stand out visually.
            ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
            for (int i = 0; i < 9; i++) {
                if (indexOf(i) < 0) inventory.setItem(i, filler);
            }
            for (int i = 0; i < SLOTS.length; i++) {
                ItemStack item = i < equipped.length ? equipped[i] : null;
                inventory.setItem(SLOTS[i], item != null ? item : emptySlotPlaceholder());
            }

            return inventory;
        }

        private ItemStack emptySlotPlaceholder() {
            return namedItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "Empty Accessory Slot",
                    List.of(ChatColor.GRAY + "Drag an accessory item here."));
        }

        private ItemStack namedItem(Material material, String name, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }


    static class AccessoryGuiListener implements Listener {

        private final FaultlineItems plugin;

        public AccessoryGuiListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof AccessoryGuiHolder)) return;

            if (event.getClickedInventory() == null
                    || event.getClickedInventory().getHolder() != event.getInventory().getHolder()) {
                // Clicked their OWN inventory (bottom half) while this GUI is
                // open. BUG FIX: this used to be unconditionally cancelled
                // along with everything else, which blocked even picking the
                // item up in the first place — nothing could ever get onto
                // the cursor to drag in. Now this only blocks shift-click
                // (which would auto-transfer into the top inventory, bypassing
                // our validation entirely) and otherwise lets normal
                // pickup/placement within their own inventory proceed as usual.
                if (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                    // COLLECT_TO_CURSOR (double-click) would otherwise vacuum
                    // matching items out of the menu, including the filler panes.
                    event.setCancelled(true);
                }
                return;
            }

            event.setCancelled(true); // now only cancels clicks within our own top inventory — safe

            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getSlot();
            if (AccessoryGuiHolder.indexOf(slot) < 0) return; // decorative filler

            ItemStack cursor = event.getCursor();
            ItemStack currentSlotItem = event.getCurrentItem();
            boolean cursorEmpty = cursor == null || cursor.getType() == Material.AIR;
            boolean slotHasRealItem = isRealAccessory(currentSlotItem);

            if (cursorEmpty) {
                if (slotHasRealItem) {
                    player.setItemOnCursor(currentSlotItem);
                    event.getClickedInventory().setItem(slot, placeholder());
                }
                return;
            }

            if (!AccessoryManager.isAccessory(plugin, cursor)) {
                player.sendMessage(ChatColor.RED + "Only accessory items can go in these slots.");
                return;
            }

            // Only ONE accessory goes into the slot; the rest of a stack stays on
            // the cursor (otherwise you'd equip, and lose on death, all 16 at once).
            ItemStack single = cursor.clone();
            single.setAmount(1);
            if (slotHasRealItem && currentSlotItem.isSimilar(single)) return; // already wearing that one

            ItemStack remainder = null;
            if (cursor.getAmount() > 1) {
                remainder = cursor.clone();
                remainder.setAmount(cursor.getAmount() - 1);
            }

            if (slotHasRealItem && remainder == null) {
                player.setItemOnCursor(currentSlotItem); // clean swap
            } else {
                player.setItemOnCursor(remainder);
                if (slotHasRealItem) {
                    // The cursor is still holding the rest of the stack, so the
                    // accessory being replaced goes back into the inventory instead.
                    player.getInventory().addItem(currentSlotItem).values()
                            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                }
            }
            event.getClickedInventory().setItem(slot, single);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (!(event.getInventory().getHolder() instanceof AccessoryGuiHolder)) return;
            // Dragging across slots (paint-mode placement) isn't worth replicating
            // the validation for — block it entirely, single-click swap still works fine.
            event.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder() instanceof AccessoryGuiHolder)) return;
            if (!(event.getPlayer() instanceof Player player)) return;

            ItemStack[] toSave = new ItemStack[AccessoryManager.SLOT_COUNT];
            for (int i = 0; i < AccessoryGuiHolder.SLOTS.length; i++) {
                ItemStack item = event.getInventory().getItem(AccessoryGuiHolder.SLOTS[i]);
                toSave[i] = isRealAccessory(item) ? item : null;
            }

            plugin.getAccessoryManager().setSlots(player.getUniqueId(), toSave);
        }

        private boolean isRealAccessory(ItemStack item) {
            return AccessoryManager.isAccessory(plugin, item);
        }

        private ItemStack placeholder() {
            ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            var meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Empty Accessory Slot");
            item.setItemMeta(meta);
            return item;
        }
    }


    static class AccessoryCommand implements CommandExecutor {

        private final FaultlineItems plugin;

        public AccessoryCommand(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }

            AccessoryGuiHolder holder = new AccessoryGuiHolder();
            player.openInventory(holder.build(plugin, player));
            return true;
        }
    }


    static class AccessoryDeathListener implements Listener {

        private final FaultlineItems plugin;

        public AccessoryDeathListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onDeath(PlayerDeathEvent event) {
            Player player = event.getEntity();

            // BUG FIX: keepInventory means drops never hit the ground, so clearing
            // the slots below would just delete the accessories. Keep them equipped.
            if (event.getKeepInventory()) return;

            // BUG FIX (dupe): if they die with /accessories open, the menu's close
            // event fires AFTER this and would re-save the items as still equipped,
            // even though they were just dropped. Wipe the open menu's slots first
            // so the close event saves them as empty.
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof AccessoryGuiHolder) {
                var top = player.getOpenInventory().getTopInventory();
                ItemStack[] live = new ItemStack[AccessoryManager.SLOT_COUNT];
                for (int i = 0; i < AccessoryGuiHolder.SLOTS.length; i++) {
                    ItemStack item = top.getItem(AccessoryGuiHolder.SLOTS[i]);
                    live[i] = AccessoryManager.isAccessory(plugin, item) ? item : null;
                    top.setItem(AccessoryGuiHolder.SLOTS[i], null);
                }
                // The open menu is the live source of truth (they may have swapped
                // items since it was last saved), so use it for the drops.
                plugin.getAccessoryManager().setSlots(player.getUniqueId(), live);
            }

            ItemStack[] equipped = plugin.getAccessoryManager().getSlots(player.getUniqueId());

            boolean hadAny = false;
            for (ItemStack item : equipped) {
                if (item != null) {
                    event.getDrops().add(item);
                    hadAny = true;
                }
            }

            if (hadAny) {
                plugin.getAccessoryManager().setSlots(player.getUniqueId(), new ItemStack[AccessoryManager.SLOT_COUNT]);
            }
        }
    }


    // ===================== MAGIC MIRROR =====================

    static class MagicMirrorItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.CLOCK);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Magic Mirror");

            // Fake enchant glow without an actual enchant effect
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Max stack 1: stacked copies would share one item's data (e.g. a whole
            // stack of Mirrors sharing one durability bar and breaking together).
            meta.setMaxStackSize(1);

            meta.getPersistentDataContainer().set(plugin.getMagicMirrorKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getUsesKey(), PersistentDataType.INTEGER, 0);

            // Custom texture (see the FaultlineItemTextures resource pack) — only
            // shows for players who've received that pack; harmless no-op
            // (falls back to the base Material's look) for anyone who hasn't.
            meta.setItemModel(new NamespacedKey("faultline", "magic_mirror"));

            item.setItemMeta(meta);
            applyLore(plugin, item, 0);
            return item;
        }

        public static boolean isMagicMirror(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
                return false;
            }
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getMagicMirrorKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        public static int getUses(FaultlineItems plugin, ItemStack item) {
            if (!isMagicMirror(plugin, item)) return 0;
            Integer uses = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getUsesKey(), PersistentDataType.INTEGER);
            return uses != null ? uses : 0;
        }

        /** Sets the uses-since-repair count on the given item and refreshes its lore to match. */
        public static void setUses(FaultlineItems plugin, ItemStack item, int uses) {
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(plugin.getUsesKey(), PersistentDataType.INTEGER, uses);
            item.setItemMeta(meta);
            applyLore(plugin, item, uses);
        }

        private static void applyLore(FaultlineItems plugin, ItemStack item, int uses) {
            int maxUses = plugin.getConfig().getInt("max-uses", 10);
            ItemMeta meta = item.getItemMeta();

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Right-click to open the mirror.");
            lore.add(ChatColor.GRAY + "Teleport to your bed, the");
            lore.add(ChatColor.GRAY + "Black Market, or the Nether.");
            lore.add("");
            lore.add(ChatColor.YELLOW + "Uses: " + ChatColor.WHITE + uses + "/" + maxUses);
            lore.add(ChatColor.DARK_GRAY + "Repair in an anvil with a gold ingot.");
            meta.setLore(lore);

            item.setItemMeta(meta);
        }

        public static ShapedRecipe buildRecipe(FaultlineItems plugin) {
            NamespacedKey key = new NamespacedKey(plugin, "magic_mirror");
            ShapedRecipe recipe = new ShapedRecipe(key, create(plugin));
            recipe.shape("GPG", "PEP", "GPG");
            recipe.setIngredient('G', Material.GLASS);
            recipe.setIngredient('P', Material.GOLD_INGOT);
            recipe.setIngredient('E', Material.ENDER_EYE);
            return recipe;
        }
    }


    static class MagicMirrorGuiHolder implements InventoryHolder {

        public static final int SLOT_BED = 2;
        public static final int SLOT_BLACK_MARKET = 4;
        public static final int SLOT_NETHER = 6;

        private Inventory inventory;

        public Inventory build() {
            inventory = Bukkit.createInventory(this, 9, ChatColor.LIGHT_PURPLE + "Magic Mirror");

            inventory.setItem(SLOT_BED, namedItem(Material.RED_BED, ChatColor.GREEN + "Teleport to Bed",
                    List.of(ChatColor.GRAY + "Teleports to your bed spawn point.")));
            inventory.setItem(SLOT_BLACK_MARKET, namedItem(Material.ENDER_CHEST, ChatColor.GOLD + "Black Market",
                    List.of(ChatColor.GRAY + "Teleports to the Black Market.")));
            inventory.setItem(SLOT_NETHER, namedItem(Material.NETHERRACK, ChatColor.RED + "The Nether",
                    List.of(ChatColor.GRAY + "Teleports to a random safe spot", ChatColor.GRAY + "in the Nether.")));

            return inventory;
        }

        private ItemStack namedItem(Material material, String name, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }


    static class MagicMirrorListener implements org.bukkit.event.Listener {

        private final FaultlineItems plugin;

        public MagicMirrorListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onInteract(PlayerInteractEvent event) {
            // Bukkit fires this event once per hand; only react to the main hand
            // to avoid opening the GUI twice from a single right-click.
            if (event.getHand() != EquipmentSlot.HAND) return;

            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

            ItemStack item = event.getItem();
            if (!MagicMirrorItem.isMagicMirror(plugin, item)) return;

            // Prevent accidental block interaction (e.g. sleeping in a bed,
            // opening a chest) while using the mirror.
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);

            Player player = event.getPlayer();
            if (plugin.getTeleportManager().isChanneling(player.getUniqueId())) {
                player.sendMessage(org.bukkit.ChatColor.RED + "You're already teleporting!");
                return;
            }

            long remaining = plugin.getTeleportManager().getRemainingCooldownSeconds(player.getUniqueId());
            if (remaining > 0) {
                player.sendMessage(org.bukkit.ChatColor.RED + "Your mirror is cooling down — " + remaining + "s left.");
                return;
            }

            if (plugin.getCombatTracker().isInCombat(player.getUniqueId())) {
                long combatRemaining = plugin.getCombatTracker().secondsRemaining(player.getUniqueId());
                player.sendMessage(org.bukkit.ChatColor.RED + "You can't use the Magic Mirror in combat! ("
                        + combatRemaining + "s left)");
                return;
            }

            MagicMirrorGuiHolder holder = new MagicMirrorGuiHolder();
            player.openInventory(holder.build());
        }

        @EventHandler
        public void onGuiClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof MagicMirrorGuiHolder)) return;

            event.setCancelled(true); // never let items be taken from this GUI

            // Ignore clicks in the player's own inventory while this GUI is open
            if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() != event.getInventory().getHolder()) {
                return;
            }

            if (!(event.getWhoClicked() instanceof Player player)) return;

            // Record which hotbar slot held the mirror at the moment of use, so
            // TeleportManager can find the same item again 3 seconds later to
            // apply the uses/durability update, even if it's not re-checked
            // via getItemInHand() at that point.
            int heldSlot = player.getInventory().getHeldItemSlot();

            int slot = event.getSlot();
            player.closeInventory();
            // BUG FIX: combat and cooldown were only checked when the menu OPENED. Opening it before a fight and
            // picking a destination mid-fight teleported you out of combat.
            if (slot != MagicMirrorGuiHolder.SLOT_BED && slot != MagicMirrorGuiHolder.SLOT_BLACK_MARKET && slot != MagicMirrorGuiHolder.SLOT_NETHER) return;
            if (plugin.getTeleportManager().isChanneling(player.getUniqueId())) return;
            if (plugin.getCombatTracker().isInCombat(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You can't use the Magic Mirror in combat! ("
                        + plugin.getCombatTracker().secondsRemaining(player.getUniqueId()) + "s left)");
                return;
            }
            if (plugin.getTeleportManager().getRemainingCooldownSeconds(player.getUniqueId()) > 0) return;
            if (!MagicMirrorItem.isMagicMirror(plugin, player.getInventory().getItem(heldSlot))) {
                player.sendMessage(ChatColor.RED + "Hold your Magic Mirror to use it.");
                return;
            }

            switch (slot) {
                case MagicMirrorGuiHolder.SLOT_BED -> plugin.getTeleportManager().startBedTeleport(player, heldSlot);
                case MagicMirrorGuiHolder.SLOT_BLACK_MARKET -> plugin.getTeleportManager().startBlackMarketTeleport(player, heldSlot);
                case MagicMirrorGuiHolder.SLOT_NETHER -> plugin.getTeleportManager().startNetherTeleport(player, heldSlot);
                default -> { /* clicked an empty/decorative slot, do nothing */ }
            }
        }
    }


    static class TeleportManager implements Listener {

        private enum Target { BED, BLACK_MARKET, NETHER }

        private record ChannelSession(Location startLocation, int heldSlot, BukkitTask teleportTask, BukkitTask particleTask) {}

        private final FaultlineItems plugin;
        private final Map<UUID, ChannelSession> channeling = new HashMap<>();
        private final Map<UUID, Long> lastUseMillis = new HashMap<>();
        private final Random random = new Random();

        public TeleportManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        public boolean isChanneling(UUID uuid) {
            return channeling.containsKey(uuid);
        }

        public long getRemainingCooldownSeconds(UUID uuid) {
            Long last = lastUseMillis.get(uuid);
            if (last == null) return 0;
            long cooldownMillis = plugin.getConfig().getInt("cooldown-seconds", 10) * 1000L;
            long elapsed = System.currentTimeMillis() - last;
            long remainingMillis = cooldownMillis - elapsed;
            return remainingMillis <= 0 ? 0 : (remainingMillis / 1000L) + 1; // round up
        }

        // ---------- Public entry points, called from the GUI click handler ----------

        public void startBedTeleport(Player player, int heldSlot) {
            if (player.getRespawnLocation() == null) {
                player.sendMessage(ChatColor.RED + "You don't have a bed spawn set! Sleep in a bed first.");
                return;
            }
            beginChannel(player, Target.BED, heldSlot);
        }

        public void startBlackMarketTeleport(Player player, int heldSlot) {
            if (resolveBlackMarketLocation() == null) {
                player.sendMessage(ChatColor.RED + "Black Market location isn't configured right now — tell staff.");
                return;
            }
            beginChannel(player, Target.BLACK_MARKET, heldSlot);
        }

        public void startNetherTeleport(Player player, int heldSlot) {
            World netherWorld = Bukkit.getWorld(plugin.getConfig().getString("nether.world", "world_nether"));
            if (netherWorld == null) {
                player.sendMessage(ChatColor.RED + "The Nether isn't available right now — tell staff.");
                return;
            }
            beginChannel(player, Target.NETHER, heldSlot);
        }

        // ---------- Channel lifecycle ----------

        private void beginChannel(Player player, Target target, int heldSlot) {
            int delaySeconds = plugin.getConfig().getInt("teleport-delay-seconds", 3);
            Location startLocation = player.getLocation().clone();

            player.sendMessage(ChatColor.LIGHT_PURPLE + "Teleporting in " + delaySeconds
                    + "s... don't move or take damage!");
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.3f);

            BukkitTask teleportTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Confirm the session is still valid (wasn't cancelled) before executing.
                ChannelSession session = channeling.get(player.getUniqueId());
                if (session == null) return;
                channeling.remove(player.getUniqueId());
                session.particleTask().cancel();

                if (!player.isOnline()) return;

                boolean success = switch (target) {
                    case BED -> executeBedTeleport(player);
                    case BLACK_MARKET -> executeBlackMarketTeleport(player);
                    case NETHER -> executeNetherTeleport(player);
                };

                if (success) {
                    onTeleportSuccess(player, session.heldSlot());
                }
            }, delaySeconds * 20L);

            // Particles/sound loop while channeling, every 5 ticks (0.25s)
            BukkitTask particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                Location loc = player.getLocation().clone().add(0, 1, 0);
                player.getWorld().spawnParticle(Particle.PORTAL, loc, 15, 0.4, 0.6, 0.4, 0.02);
                player.getWorld().playSound(loc, Sound.BLOCK_PORTAL_AMBIENT, 0.4f, 1.5f);
            }, 0L, 5L);

            channeling.put(player.getUniqueId(), new ChannelSession(startLocation, heldSlot, teleportTask, particleTask));
        }

        private void cancelChannel(Player player, String reason) {
            ChannelSession session = channeling.remove(player.getUniqueId());
            if (session == null) return;
            session.teleportTask().cancel();
            session.particleTask().cancel();
            player.sendMessage(ChatColor.RED + "Teleport cancelled — " + reason);
        }

        // ---------- Cancellation triggers ----------

        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            if (!channeling.containsKey(event.getPlayer().getUniqueId())) return;

            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) return;

            // Ignore pure look changes (head turning) — only real position movement cancels.
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                cancelChannel(event.getPlayer(), "you moved.");
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            if (!channeling.containsKey(player.getUniqueId())) return;
            // Check post-resolution (MONITOR) so damage blocked/zeroed by another
            // plugin, game mode, or effect doesn't wrongly cancel the teleport.
            if (event.isCancelled() || event.getFinalDamage() <= 0) return;
            cancelChannel(player, "you took damage.");
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            // Clean up so a task doesn't try to teleport an offline player later.
            ChannelSession session = channeling.remove(event.getPlayer().getUniqueId());
            if (session != null) {
                session.teleportTask().cancel();
                session.particleTask().cancel();
            }
        }

        @EventHandler
        public void onItemHeldChange(PlayerItemHeldEvent event) {
            // Scrolling/number-key hotbar switches don't fire PlayerMoveEvent,
            // so without this a player could swap the mirror out mid-channel and
            // dodge the durability hit while the teleport still completed.
            if (!channeling.containsKey(event.getPlayer().getUniqueId())) return;
            cancelChannel(event.getPlayer(), "you switched items.");
        }

        // ---------- Destination execution — each returns true only on an actual successful teleport ----------

        private boolean executeBedTeleport(Player player) {
            Location bedSpawn = player.getRespawnLocation();
            if (bedSpawn == null) {
                player.sendMessage(ChatColor.RED + "Your bed spawn is no longer set!");
                return false;
            }
            player.teleport(bedSpawn);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to your bed.");
            return true;
        }

        private boolean executeBlackMarketTeleport(Player player) {
            Location location = resolveBlackMarketLocation();
            if (location == null) {
                player.sendMessage(ChatColor.RED + "Black Market location isn't configured right now — tell staff.");
                return false;
            }
            player.teleport(location);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to the Black Market.");
            return true;
        }

        private Location resolveBlackMarketLocation() {
            String worldName = plugin.getConfig().getString("black-market.world", "world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) return null;

            double x = plugin.getConfig().getDouble("black-market.x");
            double y = plugin.getConfig().getDouble("black-market.y");
            double z = plugin.getConfig().getDouble("black-market.z");
            return new Location(world, x, y, z);
        }

        private boolean executeNetherTeleport(Player player) {
            World netherWorld = Bukkit.getWorld(plugin.getConfig().getString("nether.world", "world_nether"));
            if (netherWorld == null) {
                player.sendMessage(ChatColor.RED + "The Nether isn't available right now — tell staff.");
                return false;
            }
            int radius = plugin.getConfig().getInt("nether.radius", 5000);

            Location safe = findSafeNetherLocation(netherWorld, radius);
            if (safe == null) {
                player.sendMessage(ChatColor.RED + "Couldn't find a safe spot in the Nether — try again.");
                return false;
            }
            player.teleport(safe);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to a random spot in the Nether.");
            return true;
        }

        /**
         * Scans random X/Z/Y points within the Nether's usual build range for a
         * 2-block-tall air pocket with solid, non-lava ground beneath it.
         *
         * Runs in two passes to avoid lag: the first pass only looks at chunks
         * that are already loaded (zero chunk-generation cost), so on an
         * explored Nether this never touches the main thread's chunk loader.
         * Only if that comes up empty does it fall back to a small, capped
         * number of attempts that may force a chunk to load/generate — this
         * keeps the worst-case hitch small instead of unbounded.
         */
        private Location findSafeNetherLocation(World world, int radius) {
            Location fastResult = scanForSafeSpot(world, radius, 30, true);
            if (fastResult != null) return fastResult;

            return scanForSafeSpot(world, radius, 5, false);
        }

        private Location scanForSafeSpot(World world, int radius, int attempts, boolean loadedChunksOnly) {
            for (int i = 0; i < attempts; i++) {
                int x = random.nextInt(radius * 2) - radius;
                int z = random.nextInt(radius * 2) - radius;

                if (loadedChunksOnly && !world.isChunkLoaded(x >> 4, z >> 4)) continue;

                int y = 32 + random.nextInt(69); // 32-100, avoids bedrock floor/ceiling bands

                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                Block ground = world.getBlockAt(x, y - 1, z);

                if (!feet.isPassable() || !head.isPassable()) continue;
                if (!ground.getType().isSolid()) continue;
                if (isHazard(ground.getType()) || isHazard(feet.getType()) || isHazard(head.getType())) continue;

                return new Location(world, x + 0.5, y, z + 0.5);
            }
            return null;
        }

        private boolean isHazard(org.bukkit.Material material) {
            return switch (material) {
                case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK -> true;
                default -> false;
            };
        }

        // ---------- Post-teleport: cooldown + durability ----------

        private void onTeleportSuccess(Player player, int heldSlot) {
            lastUseMillis.put(player.getUniqueId(), System.currentTimeMillis());
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, player.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.05);

            applyMirrorUsage(player, heldSlot);
        }

        private void applyMirrorUsage(Player player, int heldSlot) {
            ItemStack item = player.getInventory().getItem(heldSlot);
            // Player may have swapped hotbar slots during the 3s channel — if
            // whatever's there now isn't a mirror anymore, just skip the
            // durability update rather than guessing at a different slot.
            if (!MagicMirrorItem.isMagicMirror(plugin, item)) return;

            int maxUses = plugin.getConfig().getInt("max-uses", 10);
            int uses = MagicMirrorItem.getUses(plugin, item) + 1;

            if (uses >= maxUses) {
                if (item.getAmount() <= 1) {
                    player.getInventory().setItem(heldSlot, null);
                } else {
                    item.setAmount(item.getAmount() - 1);
                }
                player.sendMessage(ChatColor.RED + "Your Magic Mirror shattered from overuse! "
                        + ChatColor.GRAY + "Repair it in an anvil with a gold ingot.");
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 0.8f);
            } else {
                MagicMirrorItem.setUses(plugin, item, uses);
            }
        }
    }


    static class CombatTracker implements Listener {

        private final FaultlineItems plugin;
        private final Map<UUID, Long> lastCombatTime = new HashMap<>();

        public CombatTracker(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        // BUG FIX: ignoreCancelled. In a no-PvP zone, a blocked punch used to still
        // combat-lock the victim's Magic Mirror.
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof Player victim)) return;

            Player attacker = resolveAttackingPlayer(event.getDamager());
            if (attacker == null) return; // not caused by a player — mob, environmental, etc.
            if (attacker.getUniqueId().equals(victim.getUniqueId())) return; // no self-tagging

            lastCombatTime.put(victim.getUniqueId(), System.currentTimeMillis());
        }

        private Player resolveAttackingPlayer(Entity damager) {
            if (damager instanceof Player player) return player;
            if (damager instanceof Projectile projectile) {
                ProjectileSource source = projectile.getShooter();
                if (source instanceof Player player) return player;
            }
            return null;
        }

        public boolean isInCombat(UUID uuid) {
            Long last = lastCombatTime.get(uuid);
            if (last == null) return false;
            long lockSeconds = plugin.getConfig().getLong("combat-lock-seconds", 10);
            return System.currentTimeMillis() - last < lockSeconds * 1000L;
        }

        /** Seconds left on the lock, rounded up, for player-facing messages. */
        public long secondsRemaining(UUID uuid) {
            Long last = lastCombatTime.get(uuid);
            if (last == null) return 0;
            long lockSeconds = plugin.getConfig().getLong("combat-lock-seconds", 10);
            long remainingMs = (lockSeconds * 1000L) - (System.currentTimeMillis() - last);
            return Math.max(0, (remainingMs + 999) / 1000);
        }
    }


    static class AnvilRepairListener implements Listener {

        private final FaultlineItems plugin;

        public AnvilRepairListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onPrepare(PrepareAnvilEvent event) {
            AnvilInventory anvil = event.getInventory();
            ItemStack left = anvil.getItem(0);
            ItemStack right = anvil.getItem(1);

            if (isRepairCombo(left, right)) {
                ItemStack preview = left.clone();
                MagicMirrorItem.setUses(plugin, preview, 0);
                event.setResult(preview);
            }
        }

        @EventHandler
        public void onResultClick(InventoryClickEvent event) {
            if (!(event.getInventory() instanceof AnvilInventory anvil)) return;
            if (event.getRawSlot() != 2) return; // the anvil's output slot

            ItemStack left = anvil.getItem(0);
            ItemStack right = anvil.getItem(1);
            // Not our custom repair combo (e.g. a plain rename, or an unrelated
            // combine) — leave it alone and let vanilla handle it as normal.
            if (!isRepairCombo(left, right)) return;

            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            ItemStack repaired = left.clone();
            MagicMirrorItem.setUses(plugin, repaired, 0);

            // Consume exactly 1 gold ingot from the right stack.
            if (right.getAmount() > 1) {
                right.setAmount(right.getAmount() - 1);
                anvil.setItem(1, right);
            } else {
                anvil.setItem(1, null);
            }

            // Consume exactly 1 mirror from the left stack. In practice mirrors
            // are always stack size 1 (differing uses/lore prevents stacking),
            // but handle a larger stack safely regardless.
            if (left.getAmount() > 1) {
                left.setAmount(left.getAmount() - 1);
                anvil.setItem(0, left);
            } else {
                anvil.setItem(0, null);
            }

            anvil.setItem(2, null);

            ItemStack cursor = player.getItemOnCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                player.setItemOnCursor(repaired);
            } else {
                var leftover = player.getInventory().addItem(repaired);
                leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            }

            player.updateInventory();
            player.sendMessage(ChatColor.GREEN + "Magic Mirror repaired!");
        }

        private boolean isRepairCombo(ItemStack left, ItemStack right) {
            return MagicMirrorItem.isMagicMirror(plugin, left)
                    && right != null && right.getType() == Material.GOLD_INGOT
                    // No point "repairing" a mirror that isn't worn — also keeps
                    // this from hijacking a plain rename if gold happens to be
                    // sitting in the anvil's second slot on a fresh mirror.
                    && MagicMirrorItem.getUses(plugin, left) > 0;
        }
    }


    // ===================== CLOUD POTION =====================

    static class CloudPotionItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();

            long durationSeconds = plugin.getConfig().getLong("cloud-potion.duration-seconds", 35);

            meta.setColor(Color.fromRGB(173, 216, 230)); // light blue, "cloud" feel
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Cloud Potion");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Drink to gain the ability to",
                    ChatColor.GRAY + "double jump for " + durationSeconds + " seconds."
            ));

            meta.getPersistentDataContainer().set(plugin.getCloudPotionKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "cloud_potion")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isCloudPotion(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) {
                return false;
            }
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getCloudPotionKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        private static ItemStack awkwardPotion() {
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setBasePotionType(PotionType.AWKWARD);
            item.setItemMeta(meta);
            return item;
        }

        public static void registerBrewingRecipe(FaultlineItems plugin) {
            NamespacedKey key = new NamespacedKey(plugin, "cloud_potion_mix");
            PotionMix mix = new PotionMix(
                    key,
                    create(plugin),
                    new RecipeChoice.ExactChoice(awkwardPotion()),
                    new RecipeChoice.MaterialChoice(Material.RABBIT_FOOT)
            );
            Bukkit.getPotionBrewer().addPotionMix(mix);
        }
    }


    static class CloudPotionListener implements Listener {

        private final FaultlineItems plugin;

        public CloudPotionListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onConsume(PlayerItemConsumeEvent event) {
            ItemStack item = event.getItem();
            if (!CloudPotionItem.isCloudPotion(plugin, item)) return;

            plugin.getDoubleJumpManager().activate(event.getPlayer());
            // Let the event proceed normally — vanilla already removes exactly
            // one potion from the stack and gives back a Glass Bottle, which is
            // the correct/expected behavior for a plain drinkable potion.
        }

        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            // Cheap early-out for the vast majority of players who don't have
            // the effect active, so this doesn't cost anything server-wide.
            if (!plugin.getDoubleJumpManager().isActive(player.getUniqueId())) return;

            if (player.isOnGround()) {
                plugin.getDoubleJumpManager().resetAirJump(player.getUniqueId());
            }
        }

        @EventHandler
        public void onToggleFlight(PlayerToggleFlightEvent event) {
            Player player = event.getPlayer();

            // Not our effect — leave it alone entirely (e.g. a creative player's
            // normal flight toggle should work completely unaffected).
            if (!plugin.getDoubleJumpManager().isActive(player.getUniqueId())) return;
            // BUG FIX: in Bat Form the player is really flying; double-tapping jump used to cancel that flight here.
            if (plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(player)) return;

            // While the effect is active, real flight must NEVER actually turn
            // on — always cancel the toggle here, regardless of whether this
            // particular attempt also earns a jump boost. Previously this only
            // cancelled when tryDoubleJump() returned true, which meant every
            // other case (on ground, already used this hop, on cooldown) let
            // the toggle through — and since allowFlight was true the whole
            // duration, that silently turned on real creative-style flight.
            // That was the "walking on air" bug.
            event.setCancelled(true);
            player.setFlying(false);

            boolean granted = plugin.getDoubleJumpManager().tryDoubleJump(player);

            // Briefly revoke allowFlight regardless of outcome — see the
            // javadoc on lockFlightBriefly() for why this is what actually
            // stops the spam-click "slow falling" glide, not just the event
            // cancellation above.
            plugin.getDoubleJumpManager().lockFlightBriefly(player);

            if (!granted) return; // valid attempt, just no boost earned this time

            double jumpVelocity = plugin.getConfig().getDouble("cloud-potion.jump-velocity", 0.65);
            Vector velocity = player.getVelocity();
            velocity.setY(jumpVelocity);
            Safe.vel(player, velocity);

            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 0.3, 0.1, 0.3, 0.02);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1.5f);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            plugin.getDoubleJumpManager().cleanup(event.getPlayer());
        }

        @EventHandler
        public void onRespawn(PlayerRespawnEvent event) {
            plugin.getDoubleJumpManager().cleanup(event.getPlayer());
        }
    }


    static class DoubleJumpManager {

        private final FaultlineItems plugin;
        private final Map<UUID, BukkitTask> activeEffects = new HashMap<>();
        private final Map<UUID, Long> lastJumpUse = new HashMap<>();
        private final Set<UUID> usedJumpThisAirtime = new HashSet<>();

        public DoubleJumpManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        public boolean isActive(UUID uuid) {
            return activeEffects.containsKey(uuid);
        }

        public void activate(Player player) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                player.sendMessage(ChatColor.YELLOW + "You already have flight — the Cloud Potion has no effect.");
                return;
            }

            UUID uuid = player.getUniqueId();
            long durationSeconds = plugin.getConfig().getLong("cloud-potion.duration-seconds", 35);

            // If already active, just restart the timer instead of stacking.
            BukkitTask existing = activeEffects.remove(uuid);
            if (existing != null) {
                existing.cancel();
            } else {
                player.setAllowFlight(true);
            }

            // Fresh activation always grants one usable jump right away, even
            // if they happen to be mid-air from a previous, about-to-expire buff.
            usedJumpThisAirtime.remove(uuid);

            player.sendMessage(ChatColor.AQUA + "You feel light as a cloud! " + ChatColor.GRAY
                    + "Double jump enabled for " + durationSeconds + "s.");

            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> deactivate(player), durationSeconds * 20L);
            activeEffects.put(uuid, task);
        }

        public void deactivate(Player player) {
            UUID uuid = player.getUniqueId();
            BukkitTask task = activeEffects.remove(uuid);
            boolean wasActive = task != null;
            if (task != null) {
                task.cancel();
            }
            lastJumpUse.remove(uuid);
            usedJumpThisAirtime.remove(uuid);

            if (!player.isOnline()) return;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

            // BUG FIX: the potion wearing off mid Bat Form switched flight off and dropped the bat out of the sky.
            // Bat Form puts flight back the way it should be when it ends.
            boolean bat = plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(player);
            if (!bat) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
            if (wasActive) {
                player.sendMessage(ChatColor.GRAY + "Your Cloud Potion effect has worn off.");
            }
        }

        /**
         * Called from the listener when a toggle-flight (double-space) event
         * happens. Returns true if this should be treated as a double-jump
         * (active, airborne, not already used this hop, off cooldown) — the
         * listener is responsible for cancelling the event and applying the
         * velocity boost when this returns true.
         */
        public boolean tryDoubleJump(Player player) {
            UUID uuid = player.getUniqueId();
            if (!isActive(uuid)) return false;
            if (player.isOnGround()) return false;
            if (usedJumpThisAirtime.contains(uuid)) return false; // already used the one jump for this hop

            long cooldownMs = plugin.getConfig().getLong("cloud-potion.per-jump-cooldown-ms", 500);
            long now = System.currentTimeMillis();
            Long last = lastJumpUse.get(uuid);
            if (last != null && now - last < cooldownMs) return false;

            lastJumpUse.put(uuid, now);
            usedJumpThisAirtime.add(uuid);
            return true;
        }

        /**
         * Called when the player is confirmed to be back on the ground, so
         * their next takeoff earns a fresh double-jump. See CloudPotionListener.
         */
        public void resetAirJump(UUID uuid) {
            usedJumpThisAirtime.remove(uuid);
        }

        /**
         * Called after ANY toggle-flight attempt (whether it earned a boost or
         * not) while the effect is active. Briefly revokes allowFlight itself,
         * re-enabling it only after the per-jump cooldown passes (and only if
         * the effect is still active then).
         *
         * This matters because just cancelling the event isn't enough on its
         * own: with allowFlight left true for the whole buff, rapid
         * spam-tapping space still causes the CLIENT to repeatedly attempt to
         * enter flying mode before each attempt gets rejected — and each of
         * those brief rejected attempts produces a flicker of near-zero-gravity
         * physics that, spammed fast enough, adds up to a visible slow-fall
         * glide. With allowFlight off during the lockout window, there's no
         * flight-capable state for the client to even attempt entering, so
         * spam-clicking during the cooldown does nothing at all.
         */
        public void lockFlightBriefly(Player player) {
            UUID uuid = player.getUniqueId();
            if (!isActive(uuid)) return;
            if (plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(player)) return;

            player.setAllowFlight(false);

            long cooldownMs = plugin.getConfig().getLong("cloud-potion.per-jump-cooldown-ms", 500);
            long cooldownTicks = Math.max(1L, cooldownMs / 50L);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isActive(uuid) && player.isOnline()) {
                    player.setAllowFlight(true);
                }
            }, cooldownTicks);
            // (the bat check above matters too: this delayed re-enable never fights Bat Form's own flight)
        }

        /** Called on quit/respawn to make sure no lingering flight flag or task survives. */
        public void cleanup(Player player) {
            deactivate(player);
        }
    }


    // ===================== DARKNESS POTION =====================

    static class DarknessPotionItem {

        public static ItemStack createStage1(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_GRAY + "Potion of Whispers");
            meta.setLore(List.of(ChatColor.GRAY + "Something stirs within...", ChatColor.GRAY + "Needs a Fermented Spider Eye."));
            meta.setColor(Color.fromRGB(45, 35, 55));
            meta.getPersistentDataContainer().set(plugin.getDarknessStageKey(), PersistentDataType.INTEGER, 1);
            meta.setItemModel(new NamespacedKey("faultline", "potion_of_whispers")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static ItemStack createStage2(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Potion of the Abyss");
            meta.setLore(List.of(ChatColor.GRAY + "The void grows nearer...", ChatColor.GRAY + "Needs a Wither Rose."));
            meta.setColor(Color.fromRGB(25, 15, 30));
            meta.getPersistentDataContainer().set(plugin.getDarknessStageKey(), PersistentDataType.INTEGER, 2);
            meta.setItemModel(new NamespacedKey("faultline", "potion_of_the_abyss")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static ItemStack createFinal(FaultlineItems plugin) {
            int durationSeconds = plugin.getConfig().getInt("darkness-potion.duration-seconds", 25);

            ItemStack item = new ItemStack(Material.SPLASH_POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setDisplayName(ChatColor.BLACK + "" + ChatColor.BOLD + "Darkness Potion");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Throw for " + durationSeconds + "s of",
                    ChatColor.GRAY + "near-total darkness.",
                    ChatColor.DARK_GRAY + "Darkness + Blindness"
            ));
            meta.setColor(Color.fromRGB(0, 0, 0));
            meta.addCustomEffect(new PotionEffect(PotionEffectType.DARKNESS, durationSeconds * 20, 0), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.BLINDNESS, durationSeconds * 20, 0), true);
            meta.getPersistentDataContainer().set(plugin.getDarknessStageKey(), PersistentDataType.INTEGER, 3);
            meta.setItemModel(new NamespacedKey("faultline", "darkness_potion")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        private static ItemStack awkwardPotion() {
            ItemStack item = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setBasePotionType(PotionType.AWKWARD);
            item.setItemMeta(meta);
            return item;
        }

        public static void registerBrewingRecipes(FaultlineItems plugin) {
            var brewer = Bukkit.getPotionBrewer();

            brewer.addPotionMix(new PotionMix(
                    new NamespacedKey(plugin, "darkness_stage1"),
                    createStage1(plugin),
                    new RecipeChoice.ExactChoice(awkwardPotion()),
                    new RecipeChoice.MaterialChoice(Material.GHAST_TEAR)
            ));

            brewer.addPotionMix(new PotionMix(
                    new NamespacedKey(plugin, "darkness_stage2"),
                    createStage2(plugin),
                    new RecipeChoice.ExactChoice(createStage1(plugin)),
                    new RecipeChoice.MaterialChoice(Material.FERMENTED_SPIDER_EYE)
            ));

            brewer.addPotionMix(new PotionMix(
                    new NamespacedKey(plugin, "darkness_final"),
                    createFinal(plugin),
                    new RecipeChoice.ExactChoice(createStage2(plugin)),
                    new RecipeChoice.MaterialChoice(Material.WITHER_ROSE)
            ));
        }
    }


    // ===================== GOODIE BAG =====================

    static class GoodieBagItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.CHEST);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Goodie Bag");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to open.",
                    ChatColor.GRAY + "A little something for your trouble."
            ));

            meta.getPersistentDataContainer().set(plugin.getGoodieBagKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "goodie_bag")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isGoodieBag(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getGoodieBagKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class GoodieBagListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        private static final List<BonusEntry> BONUS_LOOT = List.of(
                new BonusEntry(Material.IRON_INGOT, 4, 8),
                new BonusEntry(Material.GOLD_INGOT, 2, 5),
                new BonusEntry(Material.EMERALD, 2, 4),
                new BonusEntry(Material.ARROW, 8, 16),
                new BonusEntry(Material.EXPERIENCE_BOTTLE, 3, 6),
                new BonusEntry(Material.GOLDEN_APPLE, 1, 1),
                new BonusEntry(Material.REDSTONE, 6, 12),
                new BonusEntry(Material.LAPIS_LAZULI, 6, 12)
        );

        private record BonusEntry(Material material, int min, int max) {}

        public GoodieBagListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onMobDeath(EntityDeathEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim)) return;
            // BUG FIX: EntityDeathEvent also fires for PLAYER deaths (PlayerDeathEvent shares its listeners), so every
            // PvP kill had a 5% Goodie Bag chance: two friends could farm each other (or an alt) for diamonds.
            if (victim instanceof Player) return;
            Player killer = victim.getKiller();
            if (killer == null) return;
            // BUG FIX: bred animals, built snow golems, spawn eggs and boss/raid adds counted too (Rocco keeps
            // summoning family members, and FaultlineBosses clears their drops BEFORE this adds a bag), so a chicken
            // farm or a long boss fight printed Goodie Bags. Same rule as the other drop tables now.
            if (plugin.getConfig().getBoolean("goodie-bag.exclude-farmed-mobs", true) && farmedMob(victim)) return;

            // Spawner mobs don't count by default — otherwise a spawner grinder
            // (this server runs SMPSilkSpawner, so spawners are movable) turns
            // into a diamond printer. Toggle in config if you want them to count.
            if (victim.fromMobSpawner() && plugin.getConfig().getBoolean("goodie-bag.exclude-spawner-mobs", true)) return;
            if (NecromancerManager.isSummon(victim)) return; // Necromancer minions never drop anything
            if (QueenSpiderManager.isBrood(victim)) return; // nor does the Queen Spider's brood (no farming her)
            // nor boss minions or summoned eyes (FaultlineBosses): Twin Eyes can be knocked out on
            // purpose, so without this a friend could farm your staff for Goodie Bags
            Set<String> tags = victim.getScoreboardTags();
            if (tags.contains("faultline_twin_eye") || tags.contains("faultline_demon_servant") || tags.contains("faultline_icicle_runner")
                    || tags.contains("faultline_vulture") || tags.contains("faultline_dune_segment")) return;

            double dropChance = plugin.getConfig().getDouble("goodie-bag.mob-kill-drop-chance", 0.05) * plugin.eventDropMultiplier();
            if (random.nextDouble() >= dropChance) return;

            event.getDrops().add(GoodieBagItem.create(plugin));
        }

        @EventHandler
        public void onInteract(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;

            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

            ItemStack item = event.getItem();
            if (!GoodieBagItem.isGoodieBag(plugin, item)) return;

            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);

            Player player = event.getPlayer();

            // Consume one bag
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItemInMainHand(item);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            openBag(player);
        }

        private void openBag(Player player) {
            int diamondMin = plugin.getConfig().getInt("goodie-bag.diamond-min", 3);
            int diamondMax = plugin.getConfig().getInt("goodie-bag.diamond-max", 5);
            int diamonds = diamondMin + (diamondMax > diamondMin ? random.nextInt(diamondMax - diamondMin + 1) : 0);
            giveItem(player, new ItemStack(Material.DIAMOND, diamonds));

            BonusEntry bonus = BONUS_LOOT.get(random.nextInt(BONUS_LOOT.size()));
            int bonusAmount = bonus.min() + (bonus.max() > bonus.min() ? random.nextInt(bonus.max() - bonus.min() + 1) : 0);
            giveItem(player, new ItemStack(bonus.material(), bonusAmount));

            StringBuilder message = new StringBuilder();
            message.append(ChatColor.YELLOW).append("You opened a Goodie Bag! ")
                    .append(ChatColor.GRAY).append("+").append(diamonds).append(" Diamonds, +")
                    .append(bonusAmount).append(" ").append(formatMaterialName(bonus.material()));

            double elytraChance = plugin.getConfig().getDouble("goodie-bag.elytra-chance", 0.0001);
            if (random.nextDouble() < elytraChance) {
                giveItem(player, new ItemStack(Material.ELYTRA, 1));
                message.append(ChatColor.GOLD).append(ChatColor.BOLD).append(" ...AND AN ELYTRA?! JACKPOT!");
            }

            player.sendMessage(message.toString());
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.1);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        private void giveItem(Player player, ItemStack item) {
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        }

        private String formatMaterialName(Material material) {
            String[] words = material.name().replace('_', ' ').toLowerCase().split(" ");
            StringBuilder sb = new StringBuilder();
            for (String word : words) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
            return sb.toString().trim();
        }
    }


    // ===================== MYTHIC GOODIE BAG =====================

    static class MythicGoodieBagItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.ENDER_CHEST);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Mythic Goodie Bag");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to open.",
                    ChatColor.LIGHT_PURPLE + "Taken from a fallen boss."
            ));
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(plugin.getMythicGoodieBagKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "mythic_goodie_bag")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isMythicBag(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getMythicGoodieBagKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    static class MythicGoodieBagListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        private record Loot(double chance, String label, List<ItemStack> items) {}

        // ONE roll per bag, top to bottom. Chances add up to 100%.
        private static final List<Loot> LOOT = List.of(
                new Loot(0.001, "an ELYTRA", List.of(new ItemStack(Material.ELYTRA, 1))),
                new Loot(0.10, "a Netherite Block", List.of(new ItemStack(Material.NETHERITE_BLOCK, 1))),
                new Loot(0.20, "5 Diamond Blocks", List.of(new ItemStack(Material.DIAMOND_BLOCK, 5))),
                new Loot(0.20, "a Totem of Undying", List.of(new ItemStack(Material.TOTEM_OF_UNDYING, 1))),
                new Loot(0.20, "2 Ancient Debris", List.of(new ItemStack(Material.ANCIENT_DEBRIS, 2))),
                new Loot(0.15, "16 Bottles o' Enchanting + 2 Golden Apples",
                        List.of(new ItemStack(Material.EXPERIENCE_BOTTLE, 16), new ItemStack(Material.GOLDEN_APPLE, 2))),
                new Loot(0.149, "16 Ender Pearls", List.of(new ItemStack(Material.ENDER_PEARL, 16)))
        );

        public MythicGoodieBagListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onBossDeath(EntityDeathEvent event) {
            if (event.getEntity().getKiller() == null) return; // must be a player kill

            double chance;
            if (event.getEntityType() == EntityType.WARDEN) {
                chance = plugin.getConfig().getDouble("mythic-goodie-bag.warden-drop-chance", 0.10);
            } else if (event.getEntityType() == EntityType.WITHER) {
                chance = plugin.getConfig().getDouble("mythic-goodie-bag.wither-drop-chance", 0.125);
            } else {
                return;
            }

            if (random.nextDouble() < chance * plugin.eventDropMultiplier()) {
                event.getDrops().add(MythicGoodieBagItem.create(plugin));
            }
        }

        @EventHandler
        public void onInteract(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

            ItemStack item = event.getItem();
            if (!MythicGoodieBagItem.isMythicBag(plugin, item)) return;

            // Deny both, so it never places as an Ender Chest block.
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);

            Player player = event.getPlayer();
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItemInMainHand(item);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            Loot loot = roll();
            for (ItemStack reward : loot.items()) {
                var leftover = player.getInventory().addItem(reward.clone());
                leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            }

            if (loot.items().get(0).getType() == Material.ELYTRA) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + player.getName()
                        + " pulled an ELYTRA from a Mythic Goodie Bag!");
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else {
                player.sendMessage(ChatColor.DARK_PURPLE + "You opened a Mythic Goodie Bag: "
                        + ChatColor.LIGHT_PURPLE + loot.label() + "!");
                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
            }
        }

        private Loot roll() {
            double r = random.nextDouble();
            double cumulative = 0;
            for (Loot loot : LOOT) {
                cumulative += loot.chance();
                if (r < cumulative) return loot;
            }
            return LOOT.get(LOOT.size() - 1); // floating-point safety net
        }
    }

    // ===================== GOLDEN RING (accessory) =====================

    static class GoldenRingItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Golden Ring");

            List<String> lore = List.of(
                    ChatColor.GRAY + "Equip via /accessories to activate.",
                    ChatColor.GREEN + "+100% EXP from mobs and players",
                    ChatColor.RED + "Mobs spawn 1.5x more often near you"
            );
            meta.setLore(lore);

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getGoldenRingKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            // Custom texture (see the FaultlineItemTextures resource pack) — only
            // shows for players who've received that pack; harmless no-op
            // (falls back to the base Material's look) for anyone who hasn't.
            meta.setItemModel(new NamespacedKey("faultline", "golden_ring"));

            item.setItemMeta(meta);
            return item;
        }

        public static boolean isGoldenRing(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getGoldenRingKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        public static ShapedRecipe buildRecipe(FaultlineItems plugin) {
            NamespacedKey key = new NamespacedKey(plugin, "golden_ring");
            ShapedRecipe recipe = new ShapedRecipe(key, create(plugin));
            recipe.shape(" D ", "G G", " G ");
            recipe.setIngredient('D', Material.DIAMOND);
            recipe.setIngredient('G', Material.GOLD_INGOT);
            return recipe;
        }
    }


    static class GoldenRingListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        public GoldenRingListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onMobDeath(EntityDeathEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim)) return;
            // BUG FIX: player deaths reach this listener too (and onPlayerDeath below), so a PvP kill's XP was doubled twice (4x)
            if (victim instanceof Player) return;
            Player killer = victim.getKiller();
            if (killer == null) return;
            if (!isWearingRing(killer)) return;

            double multiplier = plugin.getConfig().getDouble("golden-ring.exp-multiplier", 2.0);
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * multiplier));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player killer = event.getEntity().getKiller();
            if (killer == null) return;
            if (!isWearingRing(killer)) return;

            double multiplier = plugin.getConfig().getDouble("golden-ring.exp-multiplier", 2.0);
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * multiplier));
        }

        // BUG FIXES: ignoreCancelled (it used to add mobs even where WorldGuard blocked
        // the spawn), and hostile mobs only (it used to double fish/squid/bats too,
        // which floods oceans with extra entities).
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCreatureSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
            if (!(event.getEntity() instanceof Monster)) return;

            Location location = event.getLocation();
            double radius = plugin.getConfig().getDouble("golden-ring.mob-spawn-radius", 64);
            boolean ringNearby = location.getWorld().getPlayers().stream()
                    .anyMatch(p -> p.getLocation().distanceSquared(location) <= radius * radius && isWearingRing(p));

            if (!ringNearby) return;

            double extraChance = plugin.getConfig().getDouble("golden-ring.mob-spawn-extra-chance", 0.5);
            if (random.nextDouble() >= extraChance) return;

            EntityType type = event.getEntityType();
            location.getWorld().spawnEntity(location, type); // defaults to SpawnReason.CUSTOM, not NATURAL
        }

        private boolean isWearingRing(Player player) {
            return plugin.getAccessoryManager().hasEquipped(player, plugin.getGoldenRingKey());
        }
    }


    // ===================== CLIMBING CLAWS (accessory) =====================

    static class ClimbingClawsItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.PHANTOM_MEMBRANE);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Climbing Claws");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Equip via /accessories.",
                    ChatColor.GREEN + "Hold into a wall + jump to climb it."
            ));

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getClimbingClawsKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "climbing_claws")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isClimbingClaws(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getClimbingClawsKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class ClimbingClawsListener implements Listener {

        private static final double CLIMB_SPEED = 0.2;
        private static final long MAX_CLIMB_TICKS = 60L; // 3 seconds hard cap per climb

        private final FaultlineItems plugin;
        private final Map<UUID, BukkitTask> activeClimbs = new HashMap<>();
        private final Set<UUID> wasOnGroundLastTick = new HashSet<>();

        public ClimbingClawsListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            UUID id = player.getUniqueId();

            boolean onGroundNow = player.isOnGround();
            boolean wasOnGround = wasOnGroundLastTick.contains(id);
            boolean movingUpward = event.getTo() != null && event.getTo().getY() > event.getFrom().getY();

            if (onGroundNow) {
                wasOnGroundLastTick.add(id);
            } else {
                wasOnGroundLastTick.remove(id);
            }

            boolean justJumped = wasOnGround && !onGroundNow && movingUpward;
            if (!justJumped) return;
            if (activeClimbs.containsKey(id)) return; // already mid-climb, ignore extra jump presses
            if (!plugin.getAccessoryManager().hasEquipped(player, plugin.getClimbingClawsKey())) return;
            if (!isFacingWall(player)) return;

            startClimb(player);
        }

        private void startClimb(Player player) {
            UUID id = player.getUniqueId();

            BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                long ticksElapsed = 0;

                @Override
                public void run() {
                    ticksElapsed++;
                    if (!player.isOnline() || ticksElapsed > MAX_CLIMB_TICKS
                            || !plugin.getAccessoryManager().hasEquipped(player, plugin.getClimbingClawsKey())
                            || !isFacingWall(player)) {
                        stopClimb(id);
                        return;
                    }

                    Vector velocity = player.getVelocity();
                    velocity.setY(CLIMB_SPEED);
                    Safe.vel(player, velocity);
                    player.setFallDistance(0f);
                }
            }, 0L, 1L);

            activeClimbs.put(id, task);
        }

        private void stopClimb(UUID id) {
            BukkitTask task = activeClimbs.remove(id);
            if (task != null) task.cancel();
        }

        private boolean isFacingWall(Player player) {
            RayTraceResult result = player.rayTraceBlocks(0.6);
            return result != null && result.getHitBlock() != null && result.getHitBlock().getType().isSolid();
        }
    }


    // ===================== BEZOAR (accessory) =====================

    // ===================== IMMUNITY CHARMS =====================
    //
    // One accessory for every debuff in the game that didn't have one yet (Slowness = A Weird
    // Clock, Poison = Bezoar, all of them = Ankh Shield). Each drops from the mob that causes its
    // debuff. Wearing one blocks that debuff and clears it if you already had it.

    static class ImmunityCharms implements Listener {
        enum Charm {
            GUARDIAN_SCALE("guardian_scale", "guardianscale", Material.PRISMARINE_SHARD, ChatColor.DARK_AQUA, "Guardian Scale", PotionEffectType.MINING_FATIGUE, "Mining Fatigue", EntityType.GUARDIAN, 0.03, EntityType.ELDER_GUARDIAN, 1.0),
            GINGER_ROOT("ginger_root", "gingerroot", Material.CLAY_BALL, ChatColor.GOLD, "Ginger Root", PotionEffectType.NAUSEA, "Nausea", EntityType.PUFFERFISH, 0.08, EntityType.DROWNED, 0.01),
            BLINDFOLD("blindfold", "blindfold", Material.PAPER, ChatColor.DARK_GRAY, "Blindfold", PotionEffectType.BLINDNESS, "Blindness", EntityType.ENDERMAN, 0.015),
            SALT_RATION("salt_ration", "saltration", Material.QUARTZ, ChatColor.WHITE, "Salt Ration", PotionEffectType.HUNGER, "Hunger", EntityType.HUSK, 0.02),
            VITAMINS("vitamins", "vitamins", Material.BRICK, ChatColor.RED, "Vitamins", PotionEffectType.WEAKNESS, "Weakness", EntityType.WITCH, 0.03),
            SOUL_WARD("soul_ward", "soulward", Material.NETHER_BRICK, ChatColor.DARK_AQUA, "Soul Ward", PotionEffectType.WITHER, "Wither", EntityType.WITHER_SKELETON, 0.025, EntityType.WITHER, 1.0),
            GRAVITY_STONE("gravity_stone", "gravitystone", Material.SHULKER_SHELL, ChatColor.LIGHT_PURPLE, "Gravity Stone", PotionEffectType.LEVITATION, "Levitation", EntityType.SHULKER, 0.04),
            FOUR_LEAF_CLOVER("four_leaf_clover", "clover", Material.FEATHER, ChatColor.GREEN, "Four-Leaf Clover", PotionEffectType.UNLUCK, "Bad Luck", EntityType.RABBIT, 0.03),
            NIGHT_LENS("night_lens", "nightlens", Material.ECHO_SHARD, ChatColor.DARK_AQUA, "Night Lens", PotionEffectType.DARKNESS, "Darkness", EntityType.WARDEN, 0.5),
            WIND_WARD("wind_ward", "windward", Material.TURTLE_SCUTE, ChatColor.AQUA, "Wind Ward", PotionEffectType.WIND_CHARGED, "Wind Charged", EntityType.BREEZE, 0.05),
            SILK_WARD("silk_ward", "silkward", Material.RABBIT_HIDE, ChatColor.WHITE, "Silk Ward", PotionEffectType.WEAVING, "Weaving", EntityType.SPIDER, 0.015, EntityType.CAVE_SPIDER, 0.01),
            SLIME_WARD("slime_ward", "slimeward", Material.LEATHER, ChatColor.GREEN, "Slime Ward", PotionEffectType.OOZING, "Oozing", EntityType.SLIME, 0.02, EntityType.MAGMA_CUBE, 0.02),
            PEST_CHARM("pest_charm", "pestcharm", Material.IRON_NUGGET, ChatColor.GRAY, "Pest Charm", PotionEffectType.INFESTED, "Infested", EntityType.SILVERFISH, 0.04, EntityType.ENDERMITE, 0.04);

            final String id, command, name, label;
            final Material material;
            final ChatColor color;
            final PotionEffectType effect;
            final Map<EntityType, Double> drops = new java.util.EnumMap<>(EntityType.class);

            Charm(String id, String command, Material material, ChatColor color, String name, PotionEffectType effect, String label, Object... drops) {
                this.id = id; this.command = command; this.material = material; this.color = color;
                this.name = name; this.effect = effect; this.label = label;
                for (int i = 0; i + 1 < drops.length; i += 2) this.drops.put((EntityType) drops[i], (Double) drops[i + 1]);
            }
        }

        private static final Map<Charm, NamespacedKey> KEYS = new java.util.EnumMap<>(Charm.class);

        static NamespacedKey key(FaultlineItems plugin, Charm c) {
            return KEYS.computeIfAbsent(c, k -> new NamespacedKey(plugin, k.id));
        }

        static ItemStack item(FaultlineItems plugin, Charm c) {
            return NewAccessoryItems.make(plugin, c.material, c.color + "" + ChatColor.BOLD + c.name, key(plugin, c),
                    ChatColor.GREEN + "Immune to " + c.label);
        }


        // ---------- combination charms (5 accessories in an X), the Ankh + Cross, and the Ankh Shield recipe ----------
        enum Combo {
            NIGHTWATCH("nightwatch_charm", "nightwatch", Material.NETHER_STAR, ChatColor.DARK_PURPLE, "Nightwatch Charm",
                    new PotionEffectType[]{PotionEffectType.BLINDNESS, PotionEffectType.HUNGER, PotionEffectType.WEAKNESS, PotionEffectType.WITHER, PotionEffectType.DARKNESS},
                    "Blindness, Hunger, Weakness, Wither, and Darkness"),
            WAYFARER("wayfarer_charm", "wayfarer", Material.PRISMARINE_CRYSTALS, ChatColor.AQUA, "Wayfarer's Charm",
                    new PotionEffectType[]{PotionEffectType.SLOWNESS, PotionEffectType.POISON, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA, PotionEffectType.LEVITATION},
                    "Slowness, Poison, Mining Fatigue, Nausea, and Levitation"),
            FORTUNE("fortune_charm", "fortune", Material.AMETHYST_SHARD, ChatColor.GREEN, "Fortune's Charm",
                    new PotionEffectType[]{PotionEffectType.UNLUCK, PotionEffectType.WIND_CHARGED, PotionEffectType.WEAVING, PotionEffectType.OOZING, PotionEffectType.INFESTED},
                    "Bad Luck, Wind Charged, Weaving, Oozing, and Infested");

            final String id, command, name, label;
            final Material material;
            final ChatColor color;
            final PotionEffectType[] effects;

            Combo(String id, String command, Material material, ChatColor color, String name, PotionEffectType[] effects, String label) {
                this.id = id; this.command = command; this.material = material; this.color = color; this.name = name; this.effects = effects; this.label = label;
            }
        }

        private static final Map<Combo, NamespacedKey> COMBO_KEYS = new java.util.EnumMap<>(Combo.class);
        private static final Map<NamespacedKey, Combo> COMBO_RECIPES = new HashMap<>();
        static NamespacedKey ankhRecipeKey, ankhPieceKey, crossKey;

        static NamespacedKey key(FaultlineItems plugin, Combo c) {
            return COMBO_KEYS.computeIfAbsent(c, k -> new NamespacedKey(plugin, k.id));
        }

        static ItemStack combo(FaultlineItems plugin, Combo c) {
            return NewAccessoryItems.make(plugin, c.material, c.color + "" + ChatColor.BOLD + c.name, key(plugin, c),
                    ChatColor.GREEN + "Immune to " + c.label,
                    ChatColor.DARK_GRAY + "One of the three pieces around the Cross",
                    ChatColor.DARK_GRAY + "in the Ankh Shield recipe.");
        }

        /** The five accessories that go in the X for each combination. */
        static List<ItemStack> members(FaultlineItems plugin, Combo c) {
            return switch (c) {
                case NIGHTWATCH -> List.of(item(plugin, Charm.BLINDFOLD), item(plugin, Charm.SALT_RATION), item(plugin, Charm.VITAMINS),
                        item(plugin, Charm.SOUL_WARD), item(plugin, Charm.NIGHT_LENS));
                case WAYFARER -> List.of(NewAccessoryItems.weirdClock(plugin), BezoarItem.create(plugin), item(plugin, Charm.GUARDIAN_SCALE),
                        item(plugin, Charm.GINGER_ROOT), item(plugin, Charm.GRAVITY_STONE));
                case FORTUNE -> List.of(item(plugin, Charm.FOUR_LEAF_CLOVER), item(plugin, Charm.WIND_WARD), item(plugin, Charm.SILK_WARD),
                        item(plugin, Charm.SLIME_WARD), item(plugin, Charm.PEST_CHARM));
            };
        }

        private static ItemStack piece(FaultlineItems plugin, Material material, String name, NamespacedKey key, String... lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(List.of(lore));
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.setItemModel(new NamespacedKey("faultline", key.getKey()));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static ItemStack ankhPiece(FaultlineItems plugin) {
            return piece(plugin, Material.GOLD_NUGGET, ChatColor.YELLOW + "" + ChatColor.BOLD + "Ankh", ankhPieceKey,
                    ChatColor.GRAY + "A piece of the Ankh Shield.", ChatColor.DARK_GRAY + "Dropped by the Demon Eye.",
                    ChatColor.DARK_GRAY + "Goes below the Cross in the recipe.");
        }

        static ItemStack cross(FaultlineItems plugin) {
            return piece(plugin, Material.NETHERITE_SCRAP, ChatColor.GOLD + "" + ChatColor.BOLD + "Cross", crossKey,
                    ChatColor.GRAY + "A piece of the Ankh Shield.", ChatColor.DARK_GRAY + "Dropped by the Dune Devourer (and the Frostmaw).",
                    ChatColor.DARK_GRAY + "Goes in the middle of the recipe.");
        }

        static boolean isPiece(ItemStack item) {
            if (item == null || !item.hasItemMeta() || ankhPieceKey == null) return false;
            var pdc = item.getItemMeta().getPersistentDataContainer();
            return pdc.has(ankhPieceKey, PersistentDataType.BYTE) || pdc.has(crossKey, PersistentDataType.BYTE);
        }

        static boolean isOurRecipe(org.bukkit.inventory.Recipe r) {
            return r instanceof org.bukkit.Keyed k && (COMBO_RECIPES.containsKey(k.getKey()) || k.getKey().equals(ankhRecipeKey));
        }

        private void registerRecipes() {
            ankhPieceKey = new NamespacedKey(plugin, "ankh_piece");
            crossKey = new NamespacedKey(plugin, "ankh_cross");
            for (Combo c : Combo.values()) {
                key(plugin, c);
                NamespacedKey rk = new NamespacedKey(plugin, c.id + "_recipe");
                ShapedRecipe r = new ShapedRecipe(rk, combo(plugin, c));
                r.shape("A A", " A ", "A A"); // an X: the four corners and the middle
                r.setIngredient('A', new RecipeChoice.ExactChoice(members(plugin, c)));
                FaultlineItems.addRecipeSafely(r);
                COMBO_RECIPES.put(rk, c);
            }
            ankhRecipeKey = new NamespacedKey(plugin, "ankh_shield_recipe");
            ShapedRecipe ankh = new ShapedRecipe(ankhRecipeKey, NewAccessoryItems.ankhShield(plugin));
            ankh.shape("NCN", "CXC", "NAN");
            ankh.setIngredient('N', Material.NETHERITE_BLOCK);
            ankh.setIngredient('C', new RecipeChoice.ExactChoice(List.of(combo(plugin, Combo.NIGHTWATCH), combo(plugin, Combo.WAYFARER), combo(plugin, Combo.FORTUNE))));
            ankh.setIngredient('X', new RecipeChoice.ExactChoice(cross(plugin)));
            ankh.setIngredient('A', new RecipeChoice.ExactChoice(ankhPiece(plugin)));
            FaultlineItems.addRecipeSafely(ankh);
            for (Player p : Bukkit.getOnlinePlayers()) discover(p);
        }

        private void discover(Player p) {
            for (NamespacedKey k : COMBO_RECIPES.keySet()) p.discoverRecipe(k);
            p.discoverRecipe(ankhRecipeKey);
        }

        @EventHandler
        public void onJoinRecipes(org.bukkit.event.player.PlayerJoinEvent event) {
            discover(event.getPlayer());
        }

        /** The recipes accept any of the pieces in each spot, so make sure they're all DIFFERENT (no 5 Blindfolds). */
        @EventHandler(priority = EventPriority.HIGH)
        public void onPrepareOurs(PrepareItemCraftEvent event) {
            if (!(event.getRecipe() instanceof org.bukkit.Keyed k)) return;
            ItemStack[] m = event.getInventory().getMatrix();
            if (m.length < 9) return;
            Combo combo = COMBO_RECIPES.get(k.getKey());
            if (combo != null) {
                List<ItemStack> need = members(plugin, combo);
                Set<Integer> found = new HashSet<>();
                for (int slot : new int[]{0, 2, 4, 6, 8}) {
                    for (int i = 0; i < need.size(); i++) if (need.get(i).isSimilar(m[slot])) found.add(i);
                }
                if (found.size() != 5) event.getInventory().setResult(null);
            } else if (k.getKey().equals(ankhRecipeKey)) {
                Set<Combo> found = new HashSet<>();
                for (int slot : new int[]{1, 3, 5}) {
                    for (Combo c : Combo.values()) if (combo(plugin, c).isSimilar(m[slot])) found.add(c);
                }
                if (found.size() != 3) event.getInventory().setResult(null);
            }
        }

        private final FaultlineItems plugin;
        private final Random random = new Random();

        ImmunityCharms(FaultlineItems plugin) {
            this.plugin = plugin;
            for (Charm c : Charm.values()) key(plugin, c);
            registerRecipes();
            // clear the debuff if you already had it when you put the charm on
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (Charm c : Charm.values()) {
                        if (p.hasPotionEffect(c.effect) && plugin.getAccessoryManager().hasEquipped(p, key(plugin, c))) p.removePotionEffect(c.effect);
                    }
                    for (Combo c : Combo.values()) {
                        if (!plugin.getAccessoryManager().hasEquipped(p, key(plugin, c))) continue;
                        for (PotionEffectType t : c.effects) if (p.hasPotionEffect(t)) p.removePotionEffect(t);
                    }
                }
            }, 20L, 20L);
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onEffect(EntityPotionEffectEvent event) {
            // (through EntityEvent: getEntity()'s return type differs between API versions -> NoSuchMethodError)
            if (!(((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player) || event.getNewEffect() == null) return;
            PotionEffectType type = event.getNewEffect().getType();
            for (Charm c : Charm.values()) {
                if (c.effect.equals(type) && plugin.getAccessoryManager().hasEquipped(player, key(plugin, c))) {
                    event.setCancelled(true);
                    return;
                }
            }
            for (Combo c : Combo.values()) {
                if (!java.util.Arrays.asList(c.effects).contains(type)) continue;
                if (plugin.getAccessoryManager().hasEquipped(player, key(plugin, c))) { event.setCancelled(true); return; }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(EntityDeathEvent event) {
            LivingEntity victim = event.getEntity();
            if (victim instanceof Player || victim.getKiller() == null) return;
            if (victim.fromMobSpawner() && plugin.getConfig().getBoolean("drops.exclude-spawner-mobs", true)) return; // no mob farms
            CreatureSpawnEvent.SpawnReason reason = victim.getEntitySpawnReason();
            if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return; // no raid/boss minions or eggs
            for (Charm c : Charm.values()) {
                Double base = c.drops.get(victim.getType());
                if (base == null) continue;
                double chance = plugin.getConfig().getDouble("immunity-charms." + c.command + "-chance-" + victim.getType().name().toLowerCase(), base);
                if (random.nextDouble() < chance) event.getDrops().add(item(plugin, c));
            }
        }
    }

    // ===================== ANKH SHIELD: IMBUE IT INTO A SHIELD =====================
    //
    // The Ankh Shield accessory does nothing on its own. In an anvil: any Shield (a regular one,
    // or the Shield of the Demon Eye) on the left + the Ankh Shield on the right = the same shield,
    // imbued. While you hold an imbued shield (either hand): immune to every debuff (you still take
    // knockback). ankh-shield.old-effects: true adds the old extras: freezing and fire immunity,
    // no knockback, +5% damage resistance, +15% speed, +2.5% melee damage.

    static class AnkhImbue implements Listener {
        private static NamespacedKey imbuedKey;
        private final FaultlineItems plugin;

        AnkhImbue(FaultlineItems plugin) {
            this.plugin = plugin;
            imbuedKey = new NamespacedKey(plugin, "ankh_imbued");
        }

        static boolean isImbued(ItemStack item) {
            return item != null && item.getType() == Material.SHIELD && item.hasItemMeta() && imbuedKey != null
                    && item.getItemMeta().getPersistentDataContainer().has(imbuedKey, PersistentDataType.BYTE);
        }

        /** ankh-shield.old-effects: true brings back the old fire/freeze immunity, resistance, speed and damage. */
        static boolean fullPowers(FaultlineItems plugin) { return plugin.getConfig().getBoolean("ankh-shield.old-effects", false); }

        /** Immune to every debuff (on by default). */
        static boolean debuffs(FaultlineItems plugin) {
            return plugin.getConfig().getBoolean("ankh-shield.debuff-immunity", true) || fullPowers(plugin);
        }

        /** No knockback: off by default now (you take knockback like anyone else). */
        static boolean noKnockback(FaultlineItems plugin) {
            return plugin.getConfig().getBoolean("ankh-shield.no-knockback", false) || fullPowers(plugin);
        }

        static final String LORE = ChatColor.GREEN + "While held: immune to all debuffs.";
        private static final String OLD_LORE = ChatColor.GREEN + "While held: no knockback.";

        /** Shields imbued before the change still say "no knockback": fix the line. */
        static void fixLore(ItemStack item) {
            if (!isImbued(item)) return;
            ItemMeta meta = item.getItemMeta();
            if (!meta.hasLore() || !meta.getLore().contains(OLD_LORE)) return;
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.replaceAll(l -> l.equals(OLD_LORE) ? LORE : l);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        /** Is this player holding an Ankh-imbued shield in either hand? */
        static boolean active(Player player) {
            return isImbued(player.getInventory().getItemInOffHand()) || isImbued(player.getInventory().getItemInMainHand());
        }

        private boolean isAnkh(ItemStack item) {
            return item != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(plugin.getAnkhShieldKey(), PersistentDataType.BYTE);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnvil(PrepareAnvilEvent event) {
            ItemStack shield = event.getInventory().getItem(0), ankh = event.getInventory().getItem(1);
            if (shield == null || shield.getType() != Material.SHIELD || !isAnkh(ankh)) return;
            if (isImbued(shield)) { event.setResult(null); return; } // already has one
            ItemStack result = shield.clone();
            result.setAmount(1);
            ItemMeta meta = result.getItemMeta();
            meta.getPersistentDataContainer().set(imbuedKey, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.YELLOW + "" + ChatColor.BOLD + "✦ Ankh Shield");
            lore.add(LORE);
            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            result.setItemMeta(meta);
            event.setResult(result);
            event.getView().setRepairCost(plugin.getConfig().getInt("ankh-shield.anvil-levels", 15));
            event.getView().setRepairItemCountCost(1); // uses one Ankh Shield, not the whole stack
        }

        /** +5% damage resistance while holding it. */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player && active(player) && fullPowers(plugin)) {
                event.setDamage(event.getDamage() * (1 - plugin.getConfig().getDouble("ankh-shield.damage-resistance", 0.05)));
            }
        }
    }

    static class BezoarItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.RABBIT_FOOT);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Bezoar");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Equip via /accessories.",
                    ChatColor.GREEN + "Immune to Poison"
            ));

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getBezoarKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "bezoar")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isBezoar(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getBezoarKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class BezoarListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        public BezoarListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onPotionEffect(EntityPotionEffectEvent event) {
            if (event.getNewEffect() == null) return;
            if (event.getNewEffect().getType() != PotionEffectType.POISON) return;
            if (!(((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player)) return; // (see onEffect)

            if (plugin.getAccessoryManager().hasEquipped(player, plugin.getBezoarKey())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEntityDeath(EntityDeathEvent event) {
            EntityType type = event.getEntityType();
            if (type != EntityType.BEE && type != EntityType.CAVE_SPIDER) return;

            Player killer = event.getEntity().getKiller();
            if (killer == null) return;

            double chance = plugin.getConfig().getDouble("bezoar.drop-chance", 0.02);
            if (random.nextDouble() < chance * plugin.eventDropMultiplier()) {
                event.getDrops().add(BezoarItem.create(plugin));
            }
        }
    }


    // ===================== DISCOUNT CARD (accessory) =====================

    static class DiscountCardItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();

            double percent = plugin.getConfig().getDouble("discount-card.discount-percent", 17.5);

            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Discount Card");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Equip via /accessories.",
                    ChatColor.GREEN + "-" + trimTrailingZero(percent) + "% villager trade prices"
            ));

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getDiscountCardKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "discount_card")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isDiscountCard(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getDiscountCardKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        private static String trimTrailingZero(double value) {
            if (value == Math.floor(value)) return String.valueOf((int) value);
            return String.valueOf(value);
        }
    }


    static class DiscountCardListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Map<Integer, Integer>> originalPrices = new HashMap<>();

        public DiscountCardListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        // BUG FIX: MONITOR + ignoreCancelled. If another plugin (VillagerInABukkit,
        // WorldGuard) blocked the trade screen AFTER prices were lowered, the close
        // event never fired and that villager stayed discounted for everyone forever.
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onOpen(InventoryOpenEvent event) {
            if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) return;
            if (!(event.getPlayer() instanceof Player player)) return;

            // Discount Card and Lantern Night stack. Both live HERE on purpose: if
            // two plugins each lowered prices and restored them on close, they could
            // undo each other in the wrong order and leave prices permanently low.
            double percent = 0;
            if (plugin.getAccessoryManager().hasEquipped(player, plugin.getDiscountCardKey())) {
                percent += plugin.getConfig().getDouble("discount-card.discount-percent", 17.5);
            }
            if (plugin.isEventActive("LANTERN_NIGHT")) {
                percent += plugin.getConfig().getDouble("lantern-night.villager-discount-percent", 10);
            }
            if (percent <= 0) return;

            Merchant merchant = merchantInventory.getMerchant();
            if (!(merchant instanceof Villager villager)) return;

            List<MerchantRecipe> recipes = villager.getRecipes();
            Map<Integer, Integer> saved = new HashMap<>();

            for (int i = 0; i < recipes.size(); i++) {
                MerchantRecipe recipe = recipes.get(i);
                List<ItemStack> ingredients = recipe.getIngredients();
                if (ingredients.isEmpty()) continue;

                ItemStack firstIngredient = ingredients.get(0);
                int originalAmount = firstIngredient.getAmount();
                saved.put(i, originalAmount);

                int discounted = (int) Math.max(1, Math.round(originalAmount * (1 - percent / 100.0)));
                firstIngredient.setAmount(discounted);

                List<ItemStack> newIngredients = new ArrayList<>(ingredients);
                newIngredients.set(0, firstIngredient);
                recipe.setIngredients(newIngredients);
            }

            villager.setRecipes(recipes);
            originalPrices.put(player.getUniqueId(), saved);
            player.sendMessage(ChatColor.AQUA + "Discount applied — prices reduced "
                    + trimTrailingZero(percent) + "%!");
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) return;
            if (!(event.getPlayer() instanceof Player player)) return;

            Map<Integer, Integer> saved = originalPrices.remove(player.getUniqueId());
            if (saved == null) return; // wasn't discounted this session

            Merchant merchant = merchantInventory.getMerchant();
            if (!(merchant instanceof Villager villager)) return;

            List<MerchantRecipe> recipes = villager.getRecipes();
            for (Map.Entry<Integer, Integer> entry : saved.entrySet()) {
                int index = entry.getKey();
                if (index >= recipes.size()) continue;
                MerchantRecipe recipe = recipes.get(index);
                List<ItemStack> ingredients = new ArrayList<>(recipe.getIngredients());
                if (ingredients.isEmpty()) continue;
                ItemStack firstIngredient = ingredients.get(0);
                firstIngredient.setAmount(entry.getValue());
                ingredients.set(0, firstIngredient);
                recipe.setIngredients(ingredients);
            }
            villager.setRecipes(recipes);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onVillagerDeath(EntityDeathEvent event) {
            if (event.getEntityType() != EntityType.VILLAGER) return;
            Player killer = event.getEntity().getKiller();
            if (killer == null) return;

            double chance = plugin.getConfig().getDouble("discount-card.drop-chance", 0.10);
            if (random.nextDouble() < chance * plugin.eventDropMultiplier()) {
                event.getDrops().add(DiscountCardItem.create(plugin));
            }
        }

        private String trimTrailingZero(double value) {
            if (value == Math.floor(value)) return String.valueOf((int) value);
            return String.valueOf(value);
        }
    }


    // ===================== LIFE JELLY (accessory) =====================

    static class LifeJellyItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.GLOW_INK_SAC);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Life Jelly");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Equip via /accessories.",
                    ChatColor.GREEN + "Heals 2% max health every 2s"
            ));

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getLifeJellyKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "life_jelly")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isLifeJelly(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getLifeJellyKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class LifeJellyListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        public LifeJellyListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        /** Called on a repeating timer from the main class — see onEnable. */
        public void tick() {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.getAccessoryManager().hasEquipped(player, plugin.getLifeJellyKey())) continue;
                if (player.isDead()) continue;

                var maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttribute == null) continue;

                double maxHealth = maxHealthAttribute.getValue();
                double healAmount = maxHealth * 0.02;
                player.setHealth(Math.min(maxHealth, player.getHealth() + healAmount));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onGlowSquidDeath(EntityDeathEvent event) {
            if (event.getEntityType() != EntityType.GLOW_SQUID) return;
            Player killer = event.getEntity().getKiller();
            if (killer == null) return;

            double chance = plugin.getConfig().getDouble("life-jelly.drop-chance", 0.05);
            if (random.nextDouble() < chance * plugin.eventDropMultiplier()) {
                event.getDrops().add(LifeJellyItem.create(plugin));
            }
        }
    }


    // ===================== SHIELD OF THE OCEAN (accessory) =====================

    static class ShieldOfTheOceanItem {

        public static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Shield of the Ocean");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Equip via /accessories.",
                    ChatColor.GREEN + "Swim faster",
                    ChatColor.GREEN + "+10% movement speed"
            ));

            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            // Accessories stack to 16: they carry no per-item data, so stacking is safe.
            meta.setMaxStackSize(16);

            meta.getPersistentDataContainer().set(plugin.getShieldOfTheOceanKey(), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);

            meta.setItemModel(new NamespacedKey("faultline", "shield_of_the_ocean")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        public static boolean isShieldOfTheOcean(FaultlineItems plugin, ItemStack item) {
            if (item == null || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getShieldOfTheOceanKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }


    static class ShieldOfTheOceanListener implements Listener {

        // BUG FIX: the old build used the deprecated UUID-based AttributeModifier
        // constructor, which the server stores under the key "minecraft:<uuid>".
        // Anyone who had the Shield equipped under that build can be left with
        // that modifier permanently (the new code wouldn't recognize it), meaning
        // a stuck +10% speed. This UUID is stripped off every player on each tick.
        private static final String LEGACY_SPEED_UUID = "a4e1c9d2-6f3b-4a8e-9c1d-2b7f5e0a3c6d";

        private final FaultlineItems plugin;
        private final NamespacedKey speedKey;
        private final NamespacedKey swimKey;

        public ShieldOfTheOceanListener(FaultlineItems plugin) {
            this.plugin = plugin;
            this.speedKey = new NamespacedKey(plugin, "shield_ocean_speed");
            this.swimKey = new NamespacedKey(plugin, "shield_ocean_swim");
        }

        /** Called on a repeating timer from the main class — see onEnable. */
        public void tick() {
            for (Player player : Bukkit.getOnlinePlayers()) {
                removeLegacyModifier(player);

                boolean equipped = plugin.getAccessoryManager().hasEquipped(player, plugin.getShieldOfTheOceanKey());

                applyModifier(player, Attribute.MOVEMENT_SPEED, speedKey,
                        plugin.getConfig().getDouble("shield-of-the-ocean.move-speed-boost", 0.10),
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, equipped);

                // NERFED from full Dolphin's Grace. WATER_MOVEMENT_EFFICIENCY defaults
                // to 0 (range 0-1), so this ADDS a flat amount — a multiplier on 0
                // would do nothing. 0.3 is roughly one level of Depth Strider.
                applyModifier(player, Attribute.WATER_MOVEMENT_EFFICIENCY, swimKey,
                        plugin.getConfig().getDouble("shield-of-the-ocean.swim-efficiency-boost", 0.3),
                        AttributeModifier.Operation.ADD_NUMBER, equipped);
            }
        }

        private void applyModifier(Player player, Attribute attributeType, NamespacedKey key,
                                   double amount, AttributeModifier.Operation operation, boolean shouldHave) {
            AttributeInstance attribute = player.getAttribute(attributeType);
            if (attribute == null) return;

            AttributeModifier existing = attribute.getModifiers().stream()
                    .filter(m -> m.getKey().equals(key))
                    .findFirst().orElse(null);

            if (shouldHave && existing == null) {
                attribute.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
            } else if (!shouldHave && existing != null) {
                attribute.removeModifier(existing);
            }
        }

        private void removeLegacyModifier(Player player) {
            AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (attribute == null) return;
            attribute.getModifiers().stream()
                    .filter(m -> m.getKey().getKey().equals(LEGACY_SPEED_UUID))
                    .toList()
                    .forEach(attribute::removeModifier);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onElderGuardianDeath(EntityDeathEvent event) {
            if (event.getEntityType() != EntityType.ELDER_GUARDIAN) return;
            Player killer = event.getEntity().getKiller();
            if (killer == null) return;

            // 100% — guaranteed drop, no chance roll needed.
            event.getDrops().add(ShieldOfTheOceanItem.create(plugin));
        }
    }


    // ===================== ITEM TEXTURES =====================

    static class ItemTextureManager {

        // Fixed, unique to this pack slot — must never collide with
        // FaultlinePermadeath's heart-texture UUID or any other plugin's.
        private static final UUID PACK_ID = UUID.fromString("9d3e7a1c-5b2f-4e8a-b6c4-7f1a2d3e4b5c");

        private final FaultlineItems plugin;

        public ItemTextureManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        /** Sends the pack; returns a short status line (also logged) so a missing pack is easy to track down. */
        public String applyTextures(Player player) {
            String cfgFile = new File(plugin.getDataFolder(), "config.yml").getPath();
            if (!plugin.getConfig().getBoolean("item-textures.enabled", true)) {
                String why = "not sent: item-textures.enabled is false in " + cfgFile;
                plugin.getLogger().info("[Resource pack] " + player.getName() + ": " + why);
                return why;
            }

            String url = plugin.getConfig().getString("item-textures.url", "");
            String hashHex = plugin.getConfig().getString("item-textures.hash", "");
            if (url == null || url.isBlank()) {
                String why = "not sent: item-textures.url is empty in " + cfgFile;
                plugin.getLogger().warning("[Resource pack] " + player.getName() + ": " + why);
                return why;
            }

            try {
                String hex = hashHex == null ? "" : hashHex.trim();
                if (!hex.isEmpty() && !hex.matches("[0-9a-fA-F]{40}")) {
                    String why = "not sent: item-textures.hash isn't a 40-character SHA-1 (got \"" + hex + "\") in " + cfgFile;
                    plugin.getLogger().warning("[Resource pack] " + player.getName() + ": " + why);
                    return why;
                }
                byte[] hashBytes = hex.isEmpty() ? new byte[0] : HexFormat.of().parseHex(hex);
                player.addResourcePack(PACK_ID, url, hashBytes, null, false);
                plugin.getLogger().info("[Resource pack] sent to " + player.getName() + " (hash " + (hex.isEmpty() ? "none" : hex) + ")");
                return "sent (hash " + (hex.isEmpty() ? "none" : hex) + ")";
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send item texture pack to " + player.getName(), e);
                return "failed: " + e.getMessage();
            }
        }

        static UUID packId() { return PACK_ID; }
    }


    static class ItemTextureJoinListener implements Listener {

        private final FaultlineItems plugin;

        public ItemTextureJoinListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) plugin.getItemTextureManager().applyTextures(player);
            }, 60L); // 3 seconds
        }

        /** What each player's game did with the pack: DECLINED means their server entry has resource packs turned off. */
        @EventHandler
        public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
            if (!ItemTextureManager.packId().equals(event.getID())) return;
            var st = event.getStatus();
            String hint = switch (st) {
                case DECLINED -> " (their game has Server Resource Packs set to Disabled: Multiplayer > Edit server > Enabled)";
                case FAILED_DOWNLOAD -> " (couldn't download it, or the hash doesn't match the file at the url)";
                case INVALID_URL -> " (the url in item-textures.url is wrong)";
                case FAILED_RELOAD -> " (downloaded, but the game couldn't load it)";
                default -> "";
            };
            java.util.logging.Level lvl = (st == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
                    || st == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED
                    || st == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED) ? java.util.logging.Level.INFO : java.util.logging.Level.WARNING;
            plugin.getLogger().log(lvl, "[Resource pack] " + event.getPlayer().getName() + ": " + st + hint);
        }
    }


    static class TestTextureCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }

            ItemStack stick = new ItemStack(Material.STICK);
            ItemMeta meta = stick.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "Texture Test Stick");
            meta.setItemModel(new NamespacedKey("faultline", "golden_ring"));
            stick.setItemMeta(meta);

            player.getInventory().addItem(stick);
            player.sendMessage(ChatColor.GREEN + "Gave you a test stick with the Golden Ring model. "
                    + "If it looks like a normal stick or shows checkered missing-texture, the pack has an issue. "
                    + "If it shows the ring texture, the pack/code are both fine.");
            return true;
        }
    }


    // ===================== RECIPE DISCOVERY =====================

    static class RecipeDiscoveryListener implements Listener {

        private final FaultlineItems plugin;

        public RecipeDiscoveryListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            event.getPlayer().discoverRecipes(List.of(plugin.getMagicMirrorKey(), plugin.getGoldenRingKey(),
                    plugin.getIronStickKey(), plugin.getNecroStaffKey(), plugin.getHarpyRingKey()));
        }
    }


    // ===================== NEW ACCESSORIES: LAVA CHARM / BLACK BELT / MOON STONE / RANGER EMBLEM =====================

    static class NewAccessoryItems {

        static ItemStack make(FaultlineItems plugin, Material material, String name, NamespacedKey key, String... effects) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Equip via /accessories.");
            lore.addAll(List.of(effects));
            meta.setLore(lore);
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.setMaxStackSize(16); // accessories carry no per-item data, so stacking is safe
            meta.setItemModel(new NamespacedKey("faultline", key.getKey())); // texture from FaultlineItemTextures
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(plugin.getAccessoryKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static ItemStack lavaCharm(FaultlineItems plugin) {
            return make(plugin, Material.MAGMA_CREAM, ChatColor.GOLD + "" + ChatColor.BOLD + "Lava Charm", plugin.getLavaCharmKey(),
                    ChatColor.GREEN + "Touching lava or fire makes you",
                    ChatColor.GREEN + "immune to it for 7 seconds.",
                    ChatColor.DARK_GRAY + "60 second cooldown after.");
        }

        static ItemStack blackBelt(FaultlineItems plugin) {
            return make(plugin, Material.LEATHER, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Black Belt", plugin.getBlackBeltKey(),
                    ChatColor.GREEN + "Take 5% less damage",
                    ChatColor.RED + "2.5% slower movement");
        }

        static ItemStack moonStone(FaultlineItems plugin) {
            return make(plugin, Material.QUARTZ, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Moon Stone", plugin.getMoonStoneKey(),
                    ChatColor.DARK_RED + "Become a Vampire.",
                    ChatColor.GRAY + "Always: " + ChatColor.GREEN + "Lifesteal on hits",
                    ChatColor.GRAY + "Night: " + ChatColor.GREEN + "Strength I + Regeneration",
                    ChatColor.GRAY + "Day: " + ChatColor.RED + "Weakness, and you burn in sunlight",
                    ChatColor.DARK_GRAY + "(rain stops the burning)",
                    ChatColor.DARK_RED + "Shift + F: turn into a bat for 20s",
                    ChatColor.DARK_GRAY + "(2 hearts, 2 minute cooldown)");
        }

        static ItemStack bloodyWolfScarf(FaultlineItems plugin) {
            return make(plugin, Material.RABBIT_HIDE, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Bloody Wolf Scarf", plugin.getBloodyWolfScarfKey(),
                    ChatColor.GREEN + "Take 5% less damage",
                    ChatColor.GREEN + "+2.5% melee damage");
        }

        static ItemStack frostFlare(FaultlineItems plugin) {
            return make(plugin, Material.PRISMARINE_CRYSTALS, ChatColor.AQUA + "" + ChatColor.BOLD + "Frost Flare", plugin.getFrostFlareKey(),
                    ChatColor.GREEN + "Your hits give Slowness",
                    ChatColor.GREEN + "Immune to Slowness, +5% speed",
                    ChatColor.RED + "Mobs spawn 2x near you",
                    ChatColor.RED + "You get hungry faster");
        }

        static ItemStack spelunkerAmulet(FaultlineItems plugin) {
            return make(plugin, Material.AMETHYST_SHARD, ChatColor.GOLD + "" + ChatColor.BOLD + "Spelunker's Amulet", plugin.getSpelunkerAmuletKey(),
                    ChatColor.GREEN + "+2% mining speed",
                    ChatColor.GREEN + "Immune to falling blocks");
        }

        static ItemStack harpyRing(FaultlineItems plugin) {
            return make(plugin, Material.GOLD_NUGGET, ChatColor.WHITE + "" + ChatColor.BOLD + "Harpy Ring", plugin.getHarpyRingKey(),
                    ChatColor.GREEN + "+7.5% movement speed",
                    ChatColor.GREEN + "Elytra flights last longer",
                    ChatColor.DARK_GRAY + "(Elytra durability drains 50% slower)");
        }

        static ItemStack weirdClock(FaultlineItems plugin) {
            return make(plugin, Material.CLOCK, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "A Weird Clock...", plugin.getWeirdClockKey(),
                    ChatColor.GREEN + "You're immune to Slowness");
        }

        static ItemStack cleftHorn(FaultlineItems plugin) {
            return make(plugin, Material.FLINT, ChatColor.GOLD + "" + ChatColor.BOLD + "Cleft Horn", plugin.getCleftHornKey(),
                    ChatColor.GREEN + "Your hits ignore 5% of armor");
        }

        static ItemStack ankhShield(FaultlineItems plugin) {
            return make(plugin, Material.NAUTILUS_SHELL, ChatColor.YELLOW + "" + ChatColor.BOLD + "Ankh Shield", plugin.getAnkhShieldKey(),
                    ChatColor.GOLD + "Does nothing on its own: put a Shield",
                    ChatColor.GOLD + "and this in an anvil to imbue it.",
                    ChatColor.GREEN + "While holding the shield: immune to all debuffs.");
        }

        /** Gold Ingots in the corners, Diamonds on the edges, Netherite Block in the middle. */
        static ShapedRecipe harpyRingRecipe(FaultlineItems plugin) {
            ShapedRecipe recipe = new ShapedRecipe(plugin.getHarpyRingKey(), harpyRing(plugin));
            recipe.shape("GDG", "DND", "GDG");
            recipe.setIngredient('G', Material.GOLD_INGOT);
            recipe.setIngredient('D', Material.DIAMOND);
            recipe.setIngredient('N', Material.NETHERITE_BLOCK);
            return recipe;
        }

        static ItemStack rangerEmblem(FaultlineItems plugin) {
            return make(plugin, Material.FEATHER, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Ranger Emblem", plugin.getRangerEmblemKey(),
                    ChatColor.GREEN + "+10% bow and crossbow damage");
        }
    }

    /** Lava Charm, Black Belt, Ranger Emblem, and Moon Stone lifesteal (all event-driven). */
    static class NewAccessoryListener implements Listener {

        // Magma blocks (HOT_FLOOR) deliberately don't trigger it: stepping on magma
        // would waste the charm and leave you on cooldown right before real lava.
        private static final Set<EntityDamageEvent.DamageCause> FIRE_CAUSES = EnumSet.of(
                EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.FIRE,
                EntityDamageEvent.DamageCause.FIRE_TICK);

        private final FaultlineItems plugin;
        private final Map<UUID, Long> lavaImmuneUntil = new HashMap<>();
        private final Map<UUID, Long> lavaCooldownUntil = new HashMap<>();

        NewAccessoryListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        private boolean wearing(Player player, NamespacedKey key) {
            return plugin.getAccessoryManager().hasEquipped(player, key);
        }

        // ---- Lava Charm: auto-triggers on the first lava/fire damage, then cooldown ----
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onFireDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            if (!FIRE_CAUSES.contains(event.getCause())) return;

            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();

            if (lavaImmuneUntil.getOrDefault(id, 0L) > now) {
                event.setCancelled(true);
                player.setFireTicks(0);
                return;
            }
            if (!wearing(player, plugin.getLavaCharmKey())) return;
            if (lavaCooldownUntil.getOrDefault(id, 0L) > now) return;

            int seconds = plugin.getConfig().getInt("lava-charm.immunity-seconds", 7);
            int cooldown = plugin.getConfig().getInt("lava-charm.cooldown-seconds", 60);
            lavaImmuneUntil.put(id, now + seconds * 1000L);
            lavaCooldownUntil.put(id, now + (seconds + cooldown) * 1000L); // cooldown starts once immunity ends

            event.setCancelled(true);
            player.setFireTicks(0);
            // Fire Resistance shows the timer on screen and covers lava vanilla-side too.
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, seconds * 20, 0, false, true, true));
            player.sendMessage(ChatColor.GOLD + "Your Lava Charm flares up! " + ChatColor.GRAY + seconds + "s of fire immunity.");
            player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 1.4f);
        }

        // ---- Black Belt: 5% less damage taken ----
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDamageTaken(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            if (!wearing(player, plugin.getBlackBeltKey())) return;
            double reduction = plugin.getConfig().getDouble("black-belt.damage-reduction", 0.05);
            event.setDamage(event.getDamage() * (1 - reduction));
        }

        // ---- Ranger Emblem: +10% damage, arrows from bows and crossbows only ----
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDamageDealt(EntityDamageByEntityEvent event) {
            // AbstractArrow covers arrows and spectral arrows; Tridents are also
            // AbstractArrows, so they're excluded explicitly.
            if (!(event.getDamager() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
            if (!(arrow.getShooter() instanceof Player attacker)) return;
            if (!wearing(attacker, plugin.getRangerEmblemKey())) return;
            double bonus = plugin.getConfig().getDouble("ranger-emblem.damage-bonus", 0.10);
            event.setDamage(event.getDamage() * (1 + bonus));
        }

        // ---- Moon Stone: lifesteal on melee hits, day or night ----
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onLifesteal(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player attacker)) return; // melee only
            if (!(event.getEntity() instanceof LivingEntity) || event.getEntity() instanceof org.bukkit.entity.ArmorStand) return; // no healing off armor stands
            if (!wearing(attacker, plugin.getMoonStoneKey())) return;

            AttributeInstance maxHealth = attacker.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth == null || attacker.isDead()) return;
            double steal = event.getFinalDamage() * plugin.getConfig().getDouble("moon-stone.lifesteal", 0.05);
            attacker.setHealth(Math.min(maxHealth.getValue(), attacker.getHealth() + steal));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            lavaImmuneUntil.remove(id);
            // cooldown is kept on purpose, so relogging can't reset it
        }

        static Player attackerOf(Entity damager) {
            if (damager instanceof Player player) return player;
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
            return null;
        }
    }

    /** Every-second effects: Black Belt slowdown and the Moon Stone's day/night vampire effects. */
    static class NewAccessoryTicker {

        private final FaultlineItems plugin;
        private final NamespacedKey beltSpeedKey;
        private int ticks = 0;

        private final NamespacedKey frostSpeedKey;
        private final NamespacedKey harpySpeedKey;
        private final NamespacedKey spelunkerMiningKey;
        private final NamespacedKey ankhKnockbackKey, ankhSpeedKey, ankhDamageKey;

        NewAccessoryTicker(FaultlineItems plugin) {
            this.plugin = plugin;
            this.beltSpeedKey = new NamespacedKey(plugin, "black_belt_speed");
            this.frostSpeedKey = new NamespacedKey(plugin, "frost_flare_speed");
            this.harpySpeedKey = new NamespacedKey(plugin, "harpy_ring_speed");
            this.spelunkerMiningKey = new NamespacedKey(plugin, "spelunker_mining");
            this.ankhKnockbackKey = new NamespacedKey(plugin, "ankh_knockback");
            this.ankhSpeedKey = new NamespacedKey(plugin, "ankh_speed");
            this.ankhDamageKey = new NamespacedKey(plugin, "ankh_damage");
        }

        private void tickNewAccessories(Player player) {
            AccessoryManager acc = plugin.getAccessoryManager();
            boolean frost = acc.hasEquipped(player, plugin.getFrostFlareKey());
            boolean harpy = acc.hasEquipped(player, plugin.getHarpyRingKey());
            boolean spelunker = acc.hasEquipped(player, plugin.getSpelunkerAmuletKey());
            boolean ankh = AnkhImbue.active(player); // only works on a shield (combined in an anvil)
            boolean ankhFull = ankh && AnkhImbue.fullPowers(plugin); // the old extras, only with old-effects on
            boolean ankhDebuffs = ankh && AnkhImbue.debuffs(plugin);
            if (ankh) { AnkhImbue.fixLore(player.getInventory().getItemInMainHand()); AnkhImbue.fixLore(player.getInventory().getItemInOffHand()); }

            applyModifier(player, Attribute.MOVEMENT_SPEED, frostSpeedKey,
                    plugin.getConfig().getDouble("frost-flare.speed-bonus", 0.05), AttributeModifier.Operation.MULTIPLY_SCALAR_1, frost);
            applyModifier(player, Attribute.MOVEMENT_SPEED, harpySpeedKey,
                    plugin.getConfig().getDouble("harpy-ring.speed-bonus", 0.075), AttributeModifier.Operation.MULTIPLY_SCALAR_1, harpy);
            applyModifier(player, Attribute.BLOCK_BREAK_SPEED, spelunkerMiningKey,
                    plugin.getConfig().getDouble("spelunker-amulet.mining-speed-bonus", 0.02), AttributeModifier.Operation.MULTIPLY_SCALAR_1, spelunker);
            applyModifier(player, Attribute.KNOCKBACK_RESISTANCE, ankhKnockbackKey, 1.0, AttributeModifier.Operation.ADD_NUMBER, ankh && AnkhImbue.noKnockback(plugin));
            applyModifier(player, Attribute.MOVEMENT_SPEED, ankhSpeedKey,
                    plugin.getConfig().getDouble("ankh-shield.speed-bonus", 0.15), AttributeModifier.Operation.MULTIPLY_SCALAR_1, ankhFull);
            applyModifier(player, Attribute.ATTACK_DAMAGE, ankhDamageKey,
                    plugin.getConfig().getDouble("ankh-shield.melee-damage-bonus", 0.025), AttributeModifier.Operation.MULTIPLY_SCALAR_1, ankhFull);

            boolean survival = player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
            if (plugin.getAccessoryManager().hasEquipped(player, plugin.getWeirdClockKey())) player.removePotionEffect(PotionEffectType.SLOWNESS);
            if (frost) {
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                if (survival) {
                    float extra = (float) plugin.getConfig().getDouble("frost-flare.extra-exhaustion-per-second", 0.05);
                    player.setExhaustion(Math.min(40f, player.getExhaustion() + extra));
                }
            }
            if (ankhDebuffs) {
                // Clears anything that was already on them when they picked it up.
                for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                    if (FiveAccessoryListener.ankhBlocks(effect)) player.removePotionEffect(effect.getType());
                }
            }
            if (ankhFull) {
                player.setFreezeTicks(0); // no freezing either (powder snow, Frostbeard's beam)
                if (player.getFireTicks() > 0) player.setFireTicks(0);
            }
        }

        private void applyModifier(Player player, Attribute type, NamespacedKey key, double amount,
                                   AttributeModifier.Operation operation, boolean shouldHave) {
            AttributeInstance attribute = player.getAttribute(type);
            if (attribute == null) return;
            AttributeModifier existing = attribute.getModifiers().stream()
                    .filter(m -> m.getKey().equals(key)).findFirst().orElse(null);
            if (shouldHave && existing == null) {
                attribute.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
            } else if (!shouldHave && existing != null) {
                attribute.removeModifier(existing);
            }
        }

        void tick() {
            ticks++;
            int regenInterval = Math.max(1, plugin.getConfig().getInt("moon-stone.night-regen-interval-seconds", 5));
            boolean regenThisTick = ticks % regenInterval == 0;

            for (Player player : Bukkit.getOnlinePlayers()) {
                applyBeltSlowdown(player, plugin.getAccessoryManager().hasEquipped(player, plugin.getBlackBeltKey()));
                tickNewAccessories(player);

                if (!plugin.getAccessoryManager().hasEquipped(player, plugin.getMoonStoneKey())) continue;
                if (plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(player)) continue; // bats don't burn
                if (player.isDead()) continue;

                if (isSunUp(player.getWorld())) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, false, true));
                    boolean canBurn = player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
                    boolean underOpenSky = player.getEyeLocation().getBlock().getLightFromSky() == 15;
                    boolean ankh = AnkhImbue.active(player) && AnkhImbue.fullPowers(plugin);
                    if (canBurn && underOpenSky && !ankh && !player.getWorld().hasStorm() && !player.isInWater()) {
                        player.setFireTicks(Math.max(player.getFireTicks(), 60));
                    }
                } else {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0, true, false, true));
                    if (regenThisTick) {
                        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                        if (maxHealth != null) {
                            double heal = maxHealth.getValue() * plugin.getConfig().getDouble("moon-stone.night-regen-percent", 0.05);
                            player.setHealth(Math.min(maxHealth.getValue(), player.getHealth() + heal));
                        }
                    }
                }
            }
        }

        /** Overworld daytime (same window zombies burn in). The Nether and End count as night — no sun there. */
        private boolean isSunUp(World world) {
            if (world.getEnvironment() != World.Environment.NORMAL) return false;
            long time = world.getTime();
            return time < 12300 || time > 23850;
        }

        private void applyBeltSlowdown(Player player, boolean shouldHave) {
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed == null) return;
            AttributeModifier existing = speed.getModifiers().stream()
                    .filter(m -> m.getKey().equals(beltSpeedKey)).findFirst().orElse(null);
            if (shouldHave && existing == null) {
                double penalty = plugin.getConfig().getDouble("black-belt.speed-penalty", 0.025);
                speed.addModifier(new AttributeModifier(beltSpeedKey, -penalty,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            } else if (!shouldHave && existing != null) {
                speed.removeModifier(existing);
            }
        }
    }

    /** Mob drops for the 4 new accessories and the Necromancer Staff. */
    static class NewItemDropListener implements Listener {

        private final FaultlineItems plugin;
        private final Random random = new Random();

        NewItemDropListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(EntityDeathEvent event) {
            LivingEntity victim = event.getEntity();
            if (victim instanceof Player) return;
            if (victim.getKiller() == null) return;
            if (NecromancerManager.isSummon(victim)) return;
            if (VampireManager.isVampire(victim)) return; // Vampires have their own loot table
            if (FrostWraithManager.isWraith(victim)) return; // so do Frost Wraiths
            if (QueenSpiderManager.isQueen(victim) || QueenSpiderManager.isBrood(victim)) return; // and the Queen; her brood drop nothing
            if (victim.fromMobSpawner() && plugin.getConfig().getBoolean("drops.exclude-spawner-mobs", true)) return;

            CreatureSpawnEvent.SpawnReason reason = victim.getEntitySpawnReason();
            if (reason != CreatureSpawnEvent.SpawnReason.CUSTOM && reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                    && victim instanceof Monster && victim.getWorld().getEnvironment() == World.Environment.NORMAL
                    && victim.getLocation().getBlock().getLightFromSky() == 0
                    && victim.getLocation().getY() < plugin.getConfig().getDouble("weird-clock.max-y", 50)) {
                // cave mobs only: underground, no open sky, and deep enough that dark-room mob farms don't count
                roll(event, "weird-clock.cave-drop-chance", 0.01, NewAccessoryItems.weirdClock(plugin));
            }
            // BUG FIX: bred animals, chicks from eggs and built golems counted (a chicken farm + a sword = boots)
            if (!farmedMob(victim)) {
                roll(event, "boots.hermes-drop-chance", 0.005, BootsItems.hermes(plugin));
                roll(event, "boots.rocket-drop-chance", 0.005, BootsItems.rocket(plugin));
            }

            switch (victim.getType()) {
                case BAT -> {
                    roll(event, "lava-charm.bat-drop-chance", 0.05, NewAccessoryItems.lavaCharm(plugin));
                    roll(event, "moon-stone.bat-drop-chance", 0.01, NewAccessoryItems.moonStone(plugin));
                }
                case BLAZE -> roll(event, "lava-charm.blaze-drop-chance", 0.10, NewAccessoryItems.lavaCharm(plugin));
                case PHANTOM -> roll(event, "moon-stone.phantom-drop-chance", 0.05, NewAccessoryItems.moonStone(plugin));
                case PILLAGER -> roll(event, "ranger-emblem.pillager-drop-chance", 0.02, NewAccessoryItems.rangerEmblem(plugin));
                case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, STRAY ->
                        roll(event, "black-belt.drop-chance", 0.01, NewAccessoryItems.blackBelt(plugin));
                case SKELETON -> {
                    roll(event, "black-belt.drop-chance", 0.01, NewAccessoryItems.blackBelt(plugin));
                    roll(event, "necromancer-staff.skeleton-drop-chance", 0.001, NecromancerStaffItem.create(plugin));
                }
                case WITHER_SKELETON ->
                        roll(event, "necromancer-staff.wither-skeleton-drop-chance", 0.005, NecromancerStaffItem.create(plugin));
                case PIGLIN_BRUTE -> roll(event, "bloody-wolf-scarf.drop-chance", 0.05, NewAccessoryItems.bloodyWolfScarf(plugin));
                case SNOW_GOLEM -> roll(event, "frost-flare.snow-golem-drop-chance", 0.0075, NewAccessoryItems.frostFlare(plugin));
                case GOAT -> roll(event, "cleft-horn.goat-drop-chance", 0.02, NewAccessoryItems.cleftHorn(plugin));
                case SPIDER -> roll(event, "spider-staff.spider-drop-chance", 0.0001, SpiderStaffItem.create(plugin));
                default -> { }
            }
        }

        private void roll(EntityDeathEvent event, String configKey, double defaultChance, ItemStack item) {
            if (random.nextDouble() < plugin.getConfig().getDouble(configKey, defaultChance) * plugin.eventDropMultiplier()) {
                event.getDrops().add(item);
            }
        }
    }

    /**
     * Stops custom items being eaten by vanilla recipes (they're built on
     * normal materials like Leather, Quartz, Chest, Blaze Rod), and stops
     * Iron Sticks / the staff being burned as furnace fuel.
     */
    static class CraftGuardListener implements Listener {

        private final FaultlineItems plugin;

        CraftGuardListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onPrepareCraft(PrepareItemCraftEvent event) {
            if (ImmunityCharms.isOurRecipe(event.getRecipe())) return; // combination charms + Ankh Shield: checked in ImmunityCharms
            if (plugin.getDivingGear() != null && plugin.getDivingGear().isOurRecipe(event.getRecipe())) return; // diving gear combines accessories
            ItemStack result = event.getInventory().getResult();
            boolean craftingStaff = NecromancerStaffItem.isStaff(plugin, result);
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (ingredient == null) continue;
                boolean protectedItem = AccessoryManager.isAccessory(plugin, ingredient)
                        || (plugin.getKrakenGear() != null && plugin.getKrakenGear().isWorm(ingredient)) // never crafted into leather
                        || ImmunityCharms.isPiece(ingredient)
                        || NecromancerStaffItem.isStaff(plugin, ingredient)
                        || SpiderStaffItem.isStaff(plugin, ingredient)
                        || QueenSilkItem.isSilk(plugin, ingredient)
                        || GrapplingHookItem.isHook(plugin, ingredient)
                        || GoodieBagItem.isGoodieBag(plugin, ingredient)
                        || MythicGoodieBagItem.isMythicBag(plugin, ingredient);
                boolean ironStickMisused = IronStickItem.isIronStick(plugin, ingredient) && !craftingStaff;
                if (protectedItem || ironStickMisused) {
                    event.getInventory().setResult(null);
                    return;
                }
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onFuel(FurnaceBurnEvent event) {
            ItemStack fuel = event.getFuel();
            if (IronStickItem.isIronStick(plugin, fuel) || NecromancerStaffItem.isStaff(plugin, fuel)) {
                event.setCancelled(true);
            }
        }
    }

    // ===================== SCARF / FROST FLARE / SPELUNKER / HARPY / ANKH =====================

    static class FiveAccessoryListener implements Listener {

        /**
         * The Ankh Shield blocks every effect Minecraft marks as HARMFUL (Poison, Wither, Slowness,
         * Weakness, Mining Fatigue, Nausea, Blindness, Darkness, Hunger, Levitation, Bad Luck,
         * Infested, Oozing, Weaving, Wind Charged, and anything added later). Bad Omen and Trial
         * Omen are NEUTRAL in Minecraft, so raids and ominous trials still work.
         */
        static boolean ankhBlocks(PotionEffectType type) {
            return type.getEffectCategory() == PotionEffectType.Category.HARMFUL;
        }

        /** A harmful effect, but not a cutscene one (the bosses' black screens and the Below's darkness are
         *  hidden: no icon, no particles). */
        static boolean ankhBlocks(PotionEffect effect) {
            return ankhBlocks(effect.getType()) && (effect.hasIcon() || effect.hasParticles());
        }

        private final FaultlineItems plugin;
        private final Random random = new Random();

        FiveAccessoryListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        private boolean wearing(Player player, NamespacedKey key) {
            return plugin.getAccessoryManager().hasEquipped(player, key);
        }

        /** Frost Flare blocks Slowness; Ankh Shield blocks its whole list. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPotion(EntityPotionEffectEvent event) {
            // (through EntityEvent: getEntity()'s return type differs between API versions -> NoSuchMethodError)
            if (!(((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player) || event.getNewEffect() == null) return;
            PotionEffectType type = event.getNewEffect().getType();
            if (type.equals(PotionEffectType.SLOWNESS) && (wearing(player, plugin.getFrostFlareKey()) || wearing(player, plugin.getWeirdClockKey()))) {
                event.setCancelled(true);
            } else if (ankhBlocks(event.getNewEffect()) && AnkhImbue.active(player) && AnkhImbue.debuffs(plugin)) {
                event.setCancelled(true);
            }
        }

        /** Ankh Shield: can't be set on fire (lava itself still hurts). */
        @EventHandler(ignoreCancelled = true)
        public void onCombust(EntityCombustEvent event) {
            if (event.getEntity() instanceof Player player && AnkhImbue.active(player) && AnkhImbue.fullPowers(plugin)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDamageTaken(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            EntityDamageEvent.DamageCause cause = event.getCause();

            if ((cause == EntityDamageEvent.DamageCause.FIRE_TICK || cause == EntityDamageEvent.DamageCause.FIRE)
                    && AnkhImbue.active(player) && AnkhImbue.fullPowers(plugin)) {
                event.setCancelled(true);
                player.setFireTicks(0);
                return;
            }
            if (cause == EntityDamageEvent.DamageCause.FALLING_BLOCK && wearing(player, plugin.getSpelunkerAmuletKey())) {
                event.setCancelled(true);
                return;
            }
            if (wearing(player, plugin.getBloodyWolfScarfKey())) {
                event.setDamage(event.getDamage() * (1 - plugin.getConfig().getDouble("bloody-wolf-scarf.damage-reduction", 0.05)));
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent event) {
            // Scarf: +2.5% melee (direct hits only)
            if (event.getDamager() instanceof Player melee && wearing(melee, plugin.getBloodyWolfScarfKey())) {
                event.setDamage(event.getDamage() * (1 + plugin.getConfig().getDouble("bloody-wolf-scarf.melee-bonus", 0.025)));
            }
            // Frost Flare: every hit (melee or projectile) slows the target
            Player attacker = NewAccessoryListener.attackerOf(event.getDamager());
            if (attacker != null && event.getEntity() instanceof LivingEntity target && !target.equals(attacker)
                    && wearing(attacker, plugin.getFrostFlareKey())) {
                int seconds = plugin.getConfig().getInt("frost-flare.slowness-seconds", 3);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 0));
            }
        }

        /** Cleft Horn: armor penetration. The target's armor reduces your hit 5% less. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPierce(EntityDamageByEntityEvent event) {
            Player attacker = NewAccessoryListener.attackerOf(event.getDamager());
            if (attacker == null || !wearing(attacker, plugin.getCleftHornKey())) return;
            if (!event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) return;
            double pen = plugin.getConfig().getDouble("cleft-horn.armor-penetration", 0.05);
            event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, event.getDamage(EntityDamageEvent.DamageModifier.ARMOR) * (1 - pen));
        }

        /** Harpy Ring: Elytra durability drains 50% slower, so flights last longer. */
        @EventHandler(ignoreCancelled = true)
        public void onElytraWear(PlayerItemDamageEvent event) {
            if (event.getItem().getType() != Material.ELYTRA) return;
            if (!wearing(event.getPlayer(), plugin.getHarpyRingKey())) return;
            if (random.nextDouble() < plugin.getConfig().getDouble("harpy-ring.elytra-durability-save-chance", 0.5)) {
                event.setCancelled(true);
            }
        }

        /** Frost Flare downside: 2x natural spawns near the wearer. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onNaturalSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
            if (!(event.getEntity() instanceof Monster)) return; // hostile mobs only, not fish/bats
            Location loc = event.getLocation();
            double radius = plugin.getConfig().getDouble("frost-flare.mob-spawn-radius", 64);
            boolean nearFlare = loc.getWorld().getPlayers().stream().anyMatch(p ->
                    p.getLocation().distanceSquared(loc) <= radius * radius && wearing(p, plugin.getFrostFlareKey()));
            if (nearFlare) {
                loc.getWorld().spawnEntity(loc, event.getEntityType()); // CUSTOM reason, so this can't chain
            }
        }

        // ---------- Spelunker's Amulet: drops from natural ores only ----------

        private static boolean isOre(Material type) {
            return type.name().endsWith("_ORE");
        }

        private NamespacedKey placedKey(Block block) {
            return new NamespacedKey(plugin, "placed_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onOrePlace(BlockPlaceEvent event) {
            Block block = event.getBlockPlaced();
            if (!isOre(block.getType())) return;
            block.getChunk().getPersistentDataContainer().set(placedKey(block), PersistentDataType.BYTE, (byte) 1);
        }

        /**
         * The "placed" mark is stored at a position, so a piston pushing an ore
         * would carry it to an unmarked spot and make it count as natural again
         * (silk touch, place, push, mine, repeat). Ores moved by pistons count as placed.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPistonExtend(BlockPistonExtendEvent event) {
            markPushedOres(event.getBlocks(), event.getDirection());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPistonRetract(BlockPistonRetractEvent event) {
            markPushedOres(event.getBlocks(), event.getDirection());
        }

        private void markPushedOres(List<Block> moved, BlockFace direction) {
            for (Block block : moved) {
                if (!isOre(block.getType())) continue;
                Block destination = block.getRelative(direction);
                destination.getChunk().getPersistentDataContainer().set(placedKey(destination), PersistentDataType.BYTE, (byte) 1);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onOreBreak(BlockBreakEvent event) {
            Block block = event.getBlock();
            if (!isOre(block.getType())) return;

            Chunk chunk = block.getChunk();
            NamespacedKey key = placedKey(block);
            if (chunk.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                chunk.getPersistentDataContainer().remove(key);
                return; // player-placed ore (silk touch loop) never counts
            }
            GameMode mode = event.getPlayer().getGameMode();
            if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

            double chance = plugin.getConfig().getDouble("spelunker-amulet.ore-drop-chance", 0.005) * plugin.eventDropMultiplier();
            if (random.nextDouble() < chance) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), NewAccessoryItems.spelunkerAmulet(plugin));
                event.getPlayer().sendMessage(ChatColor.GOLD + "Something glints in the rock... a Spelunker's Amulet!");
            }
        }
    }

    // ===================== FISHING =====================

    /**
     * Custom fishing loot. Anti-AFK: only players who've moved or looked around
     * recently get custom catches, otherwise AFK fish farms would print Mythic
     * Goodie Bags. Mobs fished up can't break blocks (no Wither/creeper craters).
     */
    static class FishingListener implements Listener {

        static final String FISHED_TAG = "faultline_fished";

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Long> lastActive = new HashMap<>();

        FishingListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        static boolean isFished(Entity entity) {
            return entity != null && entity.getScoreboardTags().contains(FISHED_TAG);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) return;
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
                lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            lastActive.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onFish(PlayerFishEvent event) {
            if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
            if (!(event.getCaught() instanceof Item caught)) return;
            Player player = event.getPlayer();

            long afkLimit = plugin.getConfig().getLong("fishing.afk-minutes", 5) * 60_000L;
            if (System.currentTimeMillis() - lastActive.getOrDefault(player.getUniqueId(), 0L) > afkLimit) return;

            Location hook = event.getHook().getLocation();
            double luck = (hook.getWorld().hasStorm() ? plugin.getConfig().getDouble("fishing.storm-luck-multiplier", 2.0) : 1.0)
                    * plugin.eventDropMultiplier();

            double r = random.nextDouble();
            double c = 0;
            if (r < (c += plugin.getConfig().getDouble("fishing.ankh-shield-chance", 0.001) * luck)) {
                caught.setItemStack(NewAccessoryItems.ankhShield(plugin));
                Bukkit.broadcastMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + player.getName() + " fished up an ANKH SHIELD!");
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else if (r < (c += plugin.getConfig().getDouble("fishing.mythic-goodie-bag-chance", 0.025) * luck)) {
                caught.setItemStack(MythicGoodieBagItem.create(plugin));
                player.sendMessage(ChatColor.DARK_PURPLE + "You fished up a Mythic Goodie Bag!");
            } else if (r < (c += plugin.getConfig().getDouble("fishing.goodie-bag-chance", 0.075) * luck)) {
                caught.setItemStack(GoodieBagItem.create(plugin));
                player.sendMessage(ChatColor.GOLD + "You fished up a Goodie Bag!");
            } else if (r < (c += plugin.getConfig().getDouble("fishing.potion-chance", 0.05) * luck)) {
                caught.setItemStack(random.nextBoolean() ? DarknessPotionItem.createFinal(plugin) : CloudPotionItem.create(plugin));
                player.sendMessage(ChatColor.AQUA + "You fished up a potion!");
            } else if (r < c + plugin.getConfig().getDouble("fishing.mob-chance", 0.05)) {
                // Luck deliberately doesn't boost mob catches.
                caught.remove();
                reelInMob(player, hook);
            }
        }

        private void reelInMob(Player player, Location hook) {
            double dangerShare = plugin.getConfig().getDouble("fishing.dangerous-mob-share", 0.1); // 10% of 5% = 0.5%
            Location at = hook.clone().add(0, 0.5, 0);

            if (random.nextDouble() < dangerShare) {
                int pick = random.nextInt(5);
                switch (pick) {
                    case 0 -> spawn(player, at, EntityType.WITHER, 1);
                    case 1 -> spawn(player, at, EntityType.WARDEN, 1);
                    case 2 -> {
                        for (Entity vex : spawn(player, at, EntityType.VEX, 10)) {
                            // Fished Vexes don't hang around forever.
                            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (vex.isValid()) vex.remove(); }, 20L * 60);
                        }
                    }
                    case 3 -> spawn(player, at, EntityType.CREEPER, 1).forEach(e -> ((Creeper) e).setPowered(true));
                    default -> spawn(player, at, EntityType.VINDICATOR, 3);
                }
                player.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "You reeled in something terrible...");
                player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.8f);
            } else {
                EntityType[] common = {EntityType.DROWNED, EntityType.SPIDER, EntityType.STRAY, EntityType.CREEPER};
                spawn(player, at, common[random.nextInt(common.length)], 1);
                player.sendMessage(ChatColor.RED + "Something bit back!");
            }
        }

        private List<Entity> spawn(Player player, Location at, EntityType type, int count) {
            List<Entity> spawned = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Entity entity = at.getWorld().spawnEntity(at, type);
                entity.addScoreboardTag(FISHED_TAG);
                // Pull it toward the player, like a caught item.
                Vector pull = player.getLocation().toVector().subtract(at.toVector());
                double dist = pull.length();
                Safe.vel(entity, new Vector(pull.getX() * 0.1, pull.getY() * 0.1 + Math.sqrt(dist) * 0.08, pull.getZ() * 0.1));
                spawned.add(entity);
            }
            return spawned;
        }

        /** Fished-up mobs can't destroy terrain: creeper/Wither explosions and the Wither's block-breaking. */
        @EventHandler(ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            Entity source = event.getEntity();
            boolean fished = isFished(source)
                    || (source instanceof WitherSkull skull && skull.getShooter() instanceof Entity shooter && isFished(shooter));
            if (fished) event.blockList().clear();
        }

        @EventHandler(ignoreCancelled = true)
        public void onChangeBlock(EntityChangeBlockEvent event) {
            if (isFished(event.getEntity())) event.setCancelled(true);
        }
    }

    // ===================== NECROMANCER STAFF =====================

    static class IronStickItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.STICK);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.WHITE + "Iron Stick");
            meta.setLore(List.of(ChatColor.GRAY + "Used to craft the Necromancer Staff."));
            meta.getPersistentDataContainer().set(plugin.getIronStickKey(), PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("faultline", "iron_stick")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        static boolean isIronStick(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getIronStickKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        /** Iron Ingot on top of a Stick (fits the 2x2 inventory grid). */
        static ShapedRecipe recipe(FaultlineItems plugin) {
            ShapedRecipe recipe = new ShapedRecipe(plugin.getIronStickKey(), create(plugin));
            recipe.shape("I", "S");
            recipe.setIngredient('I', Material.IRON_INGOT);
            recipe.setIngredient('S', Material.STICK);
            return recipe;
        }
    }

    static class NecromancerStaffItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.BLAZE_ROD);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Necromancer Staff");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to raise 6-9 zombie minions.",
                    ChatColor.GRAY + "Minions last 30 seconds. Cooldown: 1m 30s.",
                    ChatColor.GREEN + "Fighting a player? Your minions attack them.",
                    ChatColor.DARK_GRAY + "Requires the Necromancer's Trial quest."
            ));
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(plugin.getNecroStaffKey(), PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("faultline", "necromancer_staff")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        static boolean isStaff(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getNecroStaffKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }

        /**
         *   .  N  W      N = Nether Star, W = Wither Skeleton Skull
         *   .  I  .      I = Iron Stick (plain sticks don't work)
         *   I  .  .
         */
        static ShapedRecipe recipe(FaultlineItems plugin) {
            ShapedRecipe recipe = new ShapedRecipe(plugin.getNecroStaffKey(), create(plugin));
            recipe.shape(" NW", " I ", "I  ");
            recipe.setIngredient('N', Material.NETHER_STAR);
            recipe.setIngredient('W', Material.WITHER_SKELETON_SKULL);
            recipe.setIngredient('I', new RecipeChoice.ExactChoice(IronStickItem.create(plugin)));
            return recipe;
        }
    }

    /**
     * Summoning, minion AI control, cleanup, and who has unlocked the staff.
     *
     * Lag safety: minions are tracked in one map (only they get processed),
     * capped server-wide, never saved to disk (setPersistent(false)), removed
     * after 30s, when their owner logs off, and on shutdown, and can't call
     * zombie reinforcements.
     */
    static class NecromancerManager implements Listener {

        static final String SUMMON_TAG = "faultline_summon";

        private static class Summon {
            final UUID owner;
            UUID enemy; // the player the owner is fighting, or null (then it fights nearby monsters)
            final long expiresAt;
            final Mob body; // a Zombie (Necromancer Staff) or a Spider / Cave Spider (Spider Staff)

            Summon(UUID owner, UUID enemy, long expiresAt, Mob body) {
                this.owner = owner;
                this.enemy = enemy;
                this.expiresAt = expiresAt;
                this.body = body;
            }
        }

        private record CombatTag(UUID opponent, long time) {}

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Summon> summons = new HashMap<>();
        private final Map<UUID, Long> cooldownUntil = new HashMap<>();
        private final Map<UUID, Long> spiderCooldownUntil = new HashMap<>();
        private final Map<UUID, CombatTag> lastOpponent = new HashMap<>();
        private final Set<UUID> unlocked = new HashSet<>();
        private final File unlockFile;

        NecromancerManager(FaultlineItems plugin) {
            this.plugin = plugin;
            this.unlockFile = new File(plugin.getDataFolder(), "necromancer_unlocks.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(unlockFile);
            for (String id : yaml.getStringList("unlocked")) {
                try {
                    unlocked.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        static boolean isSummon(Entity entity) {
            return entity.getScoreboardTags().contains(SUMMON_TAG);
        }

        boolean isUnlocked(UUID playerId) {
            return unlocked.contains(playerId);
        }

        void unlock(UUID playerId) {
            if (!unlocked.add(playerId)) return;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("unlocked", unlocked.stream().map(UUID::toString).toList());
            try {
                yaml.save(unlockFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save necromancer_unlocks.yml", e);
            }
        }

        // ---------- summoning ----------

        @EventHandler
        public void onUse(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
            boolean necro = NecromancerStaffItem.isStaff(plugin, event.getItem());
            boolean spider = SpiderStaffItem.isStaff(plugin, event.getItem());
            if (!necro && !spider) return;

            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            Player player = event.getPlayer();
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            String section = necro ? "necromancer-staff." : "spider-staff.";
            Map<UUID, Long> cooldowns = necro ? cooldownUntil : spiderCooldownUntil;

            if (necro && !isUnlocked(id)) {
                player.sendMessage(ChatColor.RED + "The staff doesn't answer you yet. Complete the "
                        + ChatColor.DARK_GREEN + "Necromancer's Trial" + ChatColor.RED + " quest (Quest Book) first.");
                return;
            }
            long cd = cooldowns.getOrDefault(id, 0L);
            if (cd > now) {
                player.sendMessage(ChatColor.RED + "Your staff is recharging: " + ((cd - now + 999) / 1000) + "s left.");
                return;
            }

            int min = plugin.getConfig().getInt(section + "min-minions", necro ? 6 : 3);
            int max = plugin.getConfig().getInt(section + "max-minions", necro ? 9 : 5);
            int count = min + random.nextInt(Math.max(1, max - min + 1));
            int serverCap = plugin.getConfig().getInt("necromancer-staff.max-minions-server", 60);
            if (summons.size() + count > serverCap) {
                player.sendMessage(ChatColor.RED + "Too many minions are out on the server right now. Try again in a few seconds.");
                return;
            }

            Player enemy = currentOpponent(player);
            long lifetime = plugin.getConfig().getLong(section + "minion-lifetime-seconds", 30) * 1000L;

            int spawned = 0;
            for (int i = 0; i < count; i++) {
                EntityType type = necro ? EntityType.ZOMBIE
                        : (random.nextDouble() < plugin.getConfig().getDouble("spider-staff.cave-spider-share", 0.35)
                                ? EntityType.CAVE_SPIDER : EntityType.SPIDER);
                if (spawnMinion(player, enemy, now + lifetime, type)) spawned++;
            }
            if (spawned == 0) {
                player.sendMessage(ChatColor.RED + "Your minions couldn't rise here.");
                return;
            }

            cooldowns.put(id, now + plugin.getConfig().getLong(section + "cooldown-seconds", necro ? 90 : 60) * 1000L);
            player.getWorld().playSound(player.getLocation(),
                    necro ? Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED : Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.7f);
            String what = necro ? "minions" : "spiders";
            ChatColor color = necro ? ChatColor.DARK_GREEN : ChatColor.DARK_PURPLE;
            if (enemy != null) {
                player.sendMessage(color + "You summoned " + spawned + " " + what + "! " + ChatColor.RED + "They're hunting " + enemy.getName() + ".");
            } else {
                player.sendMessage(color + "You summoned " + spawned + " " + what + ". " + ChatColor.GRAY + "They'll fight any monsters nearby.");
            }
        }

        private boolean spawnMinion(Player owner, Player enemy, long expiresAt, EntityType type) {
            Location loc = findSpot(owner.getLocation());
            // randomizeData = false skips vanilla's random setup: no babies, no random
            // gear, no chicken jockeys or spider jockeys left behind when minions vanish.
            if (!(owner.getWorld().spawnEntity(loc, type, false) instanceof Mob mob)) return false;
            if (!mob.isValid()) return false; // a protection plugin blocked the spawn

            mob.addScoreboardTag(SUMMON_TAG);
            mob.setPersistent(false); // never saved to disk, so they can't pile up across restarts
            mob.setCanPickupItems(false);
            boolean zombie = mob instanceof Zombie;
            mob.setCustomName((zombie ? ChatColor.DARK_GREEN : ChatColor.DARK_PURPLE) + owner.getName()
                    + (zombie ? "'s Minion" : "'s Spider"));
            mob.setCustomNameVisible(false);

            if (mob instanceof Zombie z) {
                z.setAdult();
                z.setShouldBurnInDay(false);
                EntityEquipment gear = z.getEquipment();
                if (gear != null) {
                    gear.clear();
                    gear.setHelmet(new ItemStack(Material.IRON_HELMET));
                    gear.setHelmetDropChance(0f);
                    gear.setItemInMainHandDropChance(0f);
                }
                AttributeInstance reinforcements = z.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                if (reinforcements != null) reinforcements.setBaseValue(0); // no surprise extra zombies
            }

            AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(health.getBaseValue() * (zombie ? 2 : 1.5)); // tougher than normal
                mob.setHealth(health.getBaseValue());
            }
            AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.15); // a little faster

            if (enemy != null) mob.setTarget(enemy);
            summons.put(mob.getUniqueId(), new Summon(owner.getUniqueId(),
                    enemy == null ? null : enemy.getUniqueId(), expiresAt, mob));
            return true;
        }

        private Location findSpot(Location center) {
            for (int attempt = 0; attempt < 6; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double radius = 1.5 + random.nextDouble() * 2;
                Block base = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius).getBlock();
                for (int dy : new int[]{0, 1, -1}) {
                    Block feet = base.getRelative(0, dy, 0);
                    if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                            && feet.getRelative(0, -1, 0).getType().isSolid()) {
                        return feet.getLocation().add(0.5, 0, 0.5);
                    }
                }
            }
            return center;
        }

        private Player currentOpponent(Player player) {
            CombatTag tag = lastOpponent.get(player.getUniqueId());
            long window = plugin.getConfig().getLong("necromancer-staff.combat-window-seconds", 15) * 1000L;
            if (tag == null || System.currentTimeMillis() - tag.time() > window) return null;
            Player enemy = Bukkit.getPlayer(tag.opponent());
            if (enemy == null || enemy.isDead() || !enemy.getWorld().equals(player.getWorld())) return null;
            if (enemy.getLocation().distanceSquared(player.getLocation()) > 48 * 48) return null;
            return enemy;
        }

        // ---------- keeping minions in line ----------

        /**
         * What a minion may fight: the player its owner is fighting, or any hostile
         * mob. Never its owner, other players, other minions, or passive animals.
         */
        private boolean validPrey(Summon summon, Entity target) {
            if (target == null || target.getUniqueId().equals(summon.owner) || isSummon(target)) return false;
            if (target instanceof Player) return target.getUniqueId().equals(summon.enemy);
            return target instanceof Enemy;
        }

        @EventHandler(ignoreCancelled = true)
        public void onTarget(EntityTargetEvent event) {
            Summon summon = summons.get(event.getEntity().getUniqueId());
            if (summon == null || event.getTarget() == null) return;
            if (!validPrey(summon, event.getTarget())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onMinionAttack(EntityDamageByEntityEvent event) {
            Summon summon = summons.get(event.getDamager().getUniqueId());
            if (summon == null) return;
            if (!validPrey(summon, event.getEntity())) event.setCancelled(true);
        }

        /** Remembers who each player is fighting, so a summon knows who to send the minions at. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPvp(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof Player victim)) return;
            Player attacker = NewAccessoryListener.attackerOf(event.getDamager());
            if (attacker == null || attacker.equals(victim)) return;
            long now = System.currentTimeMillis();
            lastOpponent.put(attacker.getUniqueId(), new CombatTag(victim.getUniqueId(), now));
            lastOpponent.put(victim.getUniqueId(), new CombatTag(attacker.getUniqueId(), now));
        }

        /** Minions drop nothing and give no XP (other drop listeners also skip them). */
        @EventHandler(priority = EventPriority.HIGH)
        public void onMinionDeath(EntityDeathEvent event) {
            if (!isSummon(event.getEntity())) return;
            event.getDrops().clear();
            event.setDroppedExp(0);
            summons.remove(event.getEntity().getUniqueId());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            lastOpponent.remove(id);
            summons.values().removeIf(summon -> {
                if (!summon.owner.equals(id)) return false;
                summon.body.remove();
                return true;
            });
        }

        /** Runs every second: expire old minions, re-aim or calm down the rest. */
        void tick() {
            long now = System.currentTimeMillis();
            Iterator<Summon> it = summons.values().iterator();
            while (it.hasNext()) {
                Summon summon = it.next();
                Mob zombie = summon.body;
                if (!zombie.isValid() || zombie.isDead()) {
                    it.remove();
                    continue;
                }
                if (now >= summon.expiresAt) {
                    zombie.getWorld().spawnParticle(Particle.LARGE_SMOKE, zombie.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
                    zombie.remove();
                    it.remove();
                    continue;
                }
                if (summon.enemy != null) {
                    Player enemy = Bukkit.getPlayer(summon.enemy);
                    boolean lost = enemy == null || enemy.isDead() || !enemy.getWorld().equals(zombie.getWorld())
                            || enemy.getLocation().distanceSquared(zombie.getLocation()) > 48 * 48;
                    if (lost) {
                        summon.enemy = null; // fight's over; they go back to hunting monsters
                        zombie.setTarget(null);
                    } else {
                        if (zombie.getTarget() != enemy) zombie.setTarget(enemy);
                        continue;
                    }
                }
                // No player fight: hunt the nearest monster, otherwise stay close to the owner.
                LivingEntity current = zombie.getTarget();
                if (current == null || current.isDead() || !validPrey(summon, current)) {
                    LivingEntity prey = nearestMonster(summon, zombie, plugin.getConfig().getDouble("necromancer-staff.hunt-radius", 16));
                    zombie.setTarget(prey);
                    Player owner = Bukkit.getPlayer(summon.owner);
                    if (prey == null && owner != null && owner.getWorld().equals(zombie.getWorld())
                            && owner.getLocation().distanceSquared(zombie.getLocation()) > 8 * 8) {
                        zombie.getPathfinder().moveTo(owner, 1.2);
                    }
                }
            }
        }

        private LivingEntity nearestMonster(Summon summon, Mob minion, double radius) {
            LivingEntity best = null;
            double bestDist = radius * radius;
            for (Entity e : minion.getNearbyEntities(radius, radius / 2, radius)) {
                if (!(e instanceof LivingEntity living) || living.isDead() || !validPrey(summon, e) || e instanceof Player) continue;
                double d = e.getLocation().distanceSquared(minion.getLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = living;
                }
            }
            return best;
        }

        void removeAll() {
            summons.values().forEach(summon -> summon.body.remove());
            summons.clear();
        }

        /** Safety net on startup: clear any minion that somehow survived a crash. */
        void removeLeftovers() {
            for (World world : Bukkit.getWorlds()) {
                for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                    if (isSummon(mob)) mob.remove();
                }
            }
        }
    }

    static class UnlockNecromancerCommand implements CommandExecutor {

        private final FaultlineItems plugin;

        UnlockNecromancerCommand(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("items.unlocknecromancer")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /unlocknecromancer <player>");
                return false;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "That player isn't online.");
                return false; // false tells LimboBlackMarket the unlock failed
            }
            plugin.getNecromancerManager().unlock(target.getUniqueId());
            target.sendMessage(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "The Necromancer Staff now answers to you.");
            sender.sendMessage(ChatColor.GREEN + "Unlocked the Necromancer Staff for " + target.getName() + ".");
            return true;
        }
    }

    // ===================== VAMPIRE (custom mob) =====================

    static class VampireEggItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.BAT_SPAWN_EGG);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Vampire Spawn Egg");
            meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to summon a Vampire."));
            meta.getPersistentDataContainer().set(plugin.getVampireEggKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static boolean isVampireEgg(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.BAT_SPAWN_EGG || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getVampireEggKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    /**
     * The Vampire: hides among cave bats. When a player comes close, the bat flies
     * straight at them and turns into a Wither Skeleton wearing a vampire head and
     * a dark red / black outfit. Run away and it turns back into a bat to chase you.
     * Health carries over between forms. Its hits make you bleed.
     *
     * Lag safety: only vampires are processed (one map), capped server-wide, never
     * saved to disk (setPersistent(false)), and cleared on restart.
     */
    static class VampireManager implements Listener {

        static final String TAG = "faultline_vampire";

        private static class Vampire {
            LivingEntity body;
            boolean bat;
            UUID target;
            long lastShift;
            double bestDist = Double.MAX_VALUE; // for spotting a bat stuck against a wall
            long lastProgress;
        }

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Vampire> byBody = new HashMap<>();
        private final Map<UUID, Integer> bleeding = new HashMap<>();
        private final Set<UUID> justHit = new HashSet<>();
        private int ticks = 0;

        VampireManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        static boolean isVampire(Entity entity) {
            return entity != null && entity.getScoreboardTags().contains(TAG);
        }

        private double cfg(String key, double def) {
            return plugin.getConfig().getDouble("vampire." + key, def);
        }

        // ---------- spawning ----------

        /** 2.5% of naturally spawning bats (bats only spawn in caves) are secretly vampires. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBatSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
            if (!(event.getEntity() instanceof Bat bat)) return;
            if (byBody.size() >= (int) cfg("max-alive", 15)) return;
            if (random.nextDouble() >= cfg("bat-chance", 0.025)) return;
            register(bat, null);
        }

        private Vampire register(Bat bat, UUID target) {
            Vampire v = new Vampire();
            v.body = bat;
            v.bat = true;
            v.target = target;
            setupBat(bat, cfg("health", 40));
            byBody.put(bat.getUniqueId(), v);
            return v;
        }

        private void setupBat(Bat bat, double health) {
            bat.addScoreboardTag(TAG);
            bat.setPersistent(false);
            bat.setAwake(true);
            AttributeInstance max = bat.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(cfg("health", 40)); // same health pool as the vampire form
                bat.setHealth(Math.max(1, Math.min(health, max.getValue())));
            }
        }

        /**
         * Blood Moon: Vampires hunt on the surface too (Overworld and Nether). Every
         * few seconds, each survival player with fewer than 2 Vampires nearby gets a
         * Vampire bat spawned 20-32 blocks away that comes straight for them.
         */
        void bloodMoonTick(int second) {
            if (!plugin.isEventActive("BLOOD_MOON")) return;
            if (!plugin.getConfig().getBoolean("vampire.blood-moon.enabled", true)) return;
            int interval = Math.max(1, (int) cfg("blood-moon.spawn-interval-seconds", 20));
            if (second % interval != 0) return;

            int cap = (int) cfg("blood-moon.max-alive", 25);
            int perPlayer = (int) cfg("blood-moon.per-player", 2);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (byBody.size() >= cap) return;
                if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
                World world = player.getWorld();
                World.Environment env = world.getEnvironment();
                if ((env != World.Environment.NORMAL && env != World.Environment.NETHER) || world.getDifficulty() == Difficulty.PEACEFUL) continue;

                long nearby = byBody.values().stream().filter(v -> v.body.getWorld().equals(world)
                        && v.body.getLocation().distanceSquared(player.getLocation()) < 48 * 48).count();
                if (nearby >= perPlayer) continue;

                Location spot = findAirSpot(player.getLocation());
                if (spot == null) continue;
                if (!(world.spawnEntity(spot, EntityType.BAT, false) instanceof Bat bat) || !bat.isValid()) continue;
                register(bat, player.getUniqueId()); // hunts this player right away
            }
        }

        /** Open air 20-32 blocks away, a few blocks above the player's height (works in the Nether too). */
        private Location findAirSpot(Location center) {
            World world = center.getWorld();
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double dist = 20 + random.nextDouble() * 12;
                Location spot = center.clone().add(Math.cos(angle) * dist, 3 + random.nextInt(4), Math.sin(angle) * dist);
                if (!world.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) continue; // never force-load
                Block block = spot.getBlock();
                if (block.getType().isAir() && block.getRelative(0, 1, 0).getType().isAir()) return spot;
            }
            return null;
        }

        @EventHandler
        public void onEggUse(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!VampireEggItem.isVampireEgg(plugin, event.getItem())) return;
            // Always deny, so it never spawns a normal bat or turns a spawner into a bat spawner.
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

            Player player = event.getPlayer();
            Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0.5, 0.5);
            if (!(at.getWorld().spawnEntity(at, EntityType.BAT, false) instanceof Bat bat) || !bat.isValid()) return;
            register(bat, null);

            if (player.getGameMode() != GameMode.CREATIVE) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
            }
        }

        /** Dispensers would spawn a plain bat from the egg, so they just refuse it. */
        @EventHandler(ignoreCancelled = true)
        public void onDispense(BlockDispenseEvent event) {
            if (VampireEggItem.isVampireEgg(plugin, event.getItem())) event.setCancelled(true);
        }

        // ---------- behavior ----------

        /** Runs every 2 ticks: bats steer at their target, vampires re-aim or change form. */
        void tick() {
            ticks++;
            long now = System.currentTimeMillis();
            for (Vampire v : new ArrayList<>(byBody.values())) {
                if (!v.body.isValid()) { // despawned or chunk unloaded
                    byBody.remove(v.body.getUniqueId());
                    continue;
                }
                Player target = validTarget(v);
                if (target == null) {
                    v.target = null;
                    if (ticks % 10 == 0) {
                        Player found = nearestPlayer(v.body.getLocation(), cfg("notice-range", 16));
                        if (found != null) {
                            v.target = found.getUniqueId();
                            v.bestDist = Double.MAX_VALUE; // fresh chase, fresh stuck-timer
                            v.lastProgress = now;
                        }
                        else if (!v.bat && now - v.lastShift > 3000) shiftToBat(v); // nobody around: hide again
                    }
                    continue;
                }

                double dist = v.body.getLocation().distance(target.getLocation());
                if (v.bat) {
                    // BUG FIX: bats fly in a straight line, so a wall between them and
                    // the player used to pin them there forever. If it hasn't gotten
                    // closer in 4 seconds and is fairly near, it transforms anyway —
                    // the vampire form can walk around walls.
                    if (dist < v.bestDist - 0.5) {
                        v.bestDist = dist;
                        v.lastProgress = now;
                    }
                    boolean stuck = now - v.lastProgress > 4000 && dist <= 12;
                    if ((dist <= cfg("transform-range", 2.5) || stuck) && now - v.lastShift >= 1500) {
                        if (!shiftToVampire(v, target)) {
                            v.bestDist = dist; // no room to transform here; keep chasing
                            v.lastProgress = now;
                        }
                    } else {
                        Vector toward = target.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(v.body.getLocation().toVector());
                        if (toward.lengthSquared() > 0.01) Safe.vel(v.body, toward.normalize().multiply(cfg("bat-speed", 0.5)));
                    }
                } else if (ticks % 5 == 0) {
                    if (ticks % 10 == 0) {
                        v.body.getWorld().spawnParticle(Particle.DUST, v.body.getLocation().add(0, 1.4, 0), 4, 0.35, 0.6, 0.35, 0,
                                new Particle.DustOptions(Color.fromRGB(110, 0, 0), 1.1f));
                    }
                    if (dist > cfg("flee-range", 12) && now - v.lastShift >= 3000) {
                        shiftToBat(v);
                    } else if (v.body instanceof WitherSkeleton skeleton && skeleton.getTarget() != target) {
                        skeleton.setTarget(target);
                    }
                }
            }
        }

        private Player validTarget(Vampire v) {
            if (v.target == null) return null;
            Player p = Bukkit.getPlayer(v.target);
            if (p == null || p.isDead() || !p.getWorld().equals(v.body.getWorld())) return null;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return null;
            if (p.getLocation().distanceSquared(v.body.getLocation()) > 48 * 48) return null;
            return p;
        }

        private Player nearestPlayer(Location loc, double range) {
            Player best = null;
            double bestDist = range * range;
            for (Player p : loc.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
                double d = p.getLocation().distanceSquared(loc);
                if (d <= bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            return best;
        }

        /** Returns false if there's no room to transform here (the bat keeps chasing). */
        private boolean shiftToVampire(Vampire v, Player target) {
            // BUG FIX: bats fit through 1-block gaps, but the vampire is 2.4 blocks tall.
            // Transforming in a low tunnel used to spawn it inside the ceiling
            // (suffocating). Now it needs real standing room on solid ground.
            Location loc = standingSpot(v.body.getLocation());
            if (loc == null) return false;
            double health = v.body.getHealth();
            if (!(loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON, false) instanceof WitherSkeleton vamp) || !vamp.isValid()) return false;

            vamp.addScoreboardTag(TAG);
            vamp.setPersistent(false);
            vamp.setCanPickupItems(false);
            vamp.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Vampire");
            vamp.setCustomNameVisible(false);

            // LOOK: the skeleton body itself is invisible, so only the outfit and the
            // vampire face show. That hides the thin gray bones poking out of the
            // clothes and makes it read as a person in a coat. (Invisible mobs still
            // render their armor and head.) Black coat and trousers with red trims.
            vamp.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));

            EntityEquipment gear = vamp.getEquipment();
            if (gear != null) {
                gear.clear();
                gear.setHelmet(vampireHead());
                gear.setChestplate(outfit(Material.LEATHER_CHESTPLATE, Color.fromRGB(22, 18, 26), TrimPattern.SILENCE));
                gear.setLeggings(outfit(Material.LEATHER_LEGGINGS, Color.fromRGB(30, 26, 34), TrimPattern.RIB));
                gear.setBoots(outfit(Material.LEATHER_BOOTS, Color.fromRGB(18, 14, 20), TrimPattern.RIB));
                gear.setItemInMainHand(null); // claws, not a sword
                gear.setHelmetDropChance(0f);
                gear.setChestplateDropChance(0f);
                gear.setLeggingsDropChance(0f);
                gear.setBootsDropChance(0f);
                gear.setItemInMainHandDropChance(0f);
            }
            AttributeInstance max = vamp.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(cfg("health", 40));
                vamp.setHealth(Math.max(1, Math.min(health, max.getValue())));
            }
            AttributeInstance damage = vamp.getAttribute(Attribute.ATTACK_DAMAGE);
            if (damage != null) damage.setBaseValue(cfg("hit-damage", 9)); // claws: brute-level hits, no weapon needed
            AttributeInstance speed = vamp.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(cfg("speed", 0.28));
            AttributeInstance follow = vamp.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) follow.setBaseValue(32);
            vamp.setTarget(target);

            puff(loc);
            loc.getWorld().playSound(loc, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.6f);
            replaceBody(v, vamp, false);
            return true;
        }

        /** Solid ground below (within 6 blocks) with 3 blocks of open space above it. */
        private Location standingSpot(Location from) {
            Block start = from.getBlock();
            int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] off : offsets) {
                Block column = start.getRelative(off[0], 0, off[1]);
                for (int dy = 0; dy >= -6; dy--) {
                    Block feet = column.getRelative(0, dy, 0);
                    if (!feet.isPassable()) break; // hit the ground/wall without finding a floor
                    Block below = feet.getRelative(0, -1, 0);
                    if (below.getType().isSolid() && feet.getRelative(0, 1, 0).isPassable()
                            && feet.getRelative(0, 2, 0).isPassable() && !feet.isLiquid()) {
                        return feet.getLocation().add(0.5, 0, 0.5);
                    }
                }
            }
            return null;
        }

        private void shiftToBat(Vampire v) {
            Location loc = v.body.getEyeLocation();
            double health = v.body.getHealth();
            if (!(loc.getWorld().spawnEntity(loc, EntityType.BAT, false) instanceof Bat bat) || !bat.isValid()) return;
            setupBat(bat, health);
            puff(loc);
            loc.getWorld().playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.7f);
            replaceBody(v, bat, true);
        }

        private void replaceBody(Vampire v, LivingEntity newBody, boolean bat) {
            byBody.remove(v.body.getUniqueId());
            v.body.remove();
            v.body = newBody;
            v.bat = bat;
            v.lastShift = System.currentTimeMillis();
            v.bestDist = Double.MAX_VALUE; // fresh stuck-timer after every form change
            v.lastProgress = v.lastShift;
            byBody.put(newBody.getUniqueId(), v);
        }

        private void puff(Location loc) {
            loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
            loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 25, 0.5, 0.7, 0.5, 0,
                    new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.4f));
        }

        /** Dyed leather with a red armor trim (the patterned lines smithing templates add). */
        private ItemStack outfit(Material material, Color color, TrimPattern pattern) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof LeatherArmorMeta leather) leather.setColor(color);
            if (meta instanceof ArmorMeta armor) armor.setTrim(new ArmorTrim(TrimMaterial.REDSTONE, pattern));
            if (meta != null) {
                meta.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM);
                item.setItemMeta(meta);
            }
            return item;
        }

        /** A player head with a vampire face (a Minecraft-Heads texture; change it in config). */
        private ItemStack vampireHead() {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            String hash = plugin.getConfig().getString("vampire.head-texture",
                    "14d412d8a6b96cd7aaa8322ef75ff5f44c7888cf32240ad8c923a58cea7407a6");
            if (hash == null || hash.isBlank() || !(head.getItemMeta() instanceof SkullMeta meta)) return head;

            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("faultline-vampire-" + hash).getBytes(StandardCharsets.UTF_8)), "Vampire");
            profile.setProperty(new ProfileProperty("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
            return head;
        }

        // ---------- bleeding ----------

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onVampireHit(EntityDamageByEntityEvent event) {
            Vampire v = byBody.get(event.getDamager().getUniqueId());
            if (v == null || v.bat || !(event.getEntity() instanceof LivingEntity victim)) return;
            if (shieldBlocked(event)) return; // blocked with a shield: no bleed
            bleeding.put(victim.getUniqueId(), (int) cfg("bleed-seconds", 4)); // getting hit again restarts it
            justHit.add(victim.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> justHit.remove(victim.getUniqueId()));
        }

        /** Vampires use Wither Skeleton bodies, whose hits normally give Wither. They bleed you instead. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onWitherEffect(EntityPotionEffectEvent event) {
            if (event.getCause() != EntityPotionEffectEvent.Cause.ATTACK || event.getNewEffect() == null) return;
            if (event.getNewEffect().getType().equals(PotionEffectType.WITHER) && justHit.contains(((org.bukkit.event.entity.EntityEvent) event).getEntity().getUniqueId())) { // (see onEffect)
                event.setCancelled(true);
            }
        }

        /** BUG FIX: leftover bleed used to carry through death if you respawned quickly. */
        @EventHandler
        public void onAnyDeath(EntityDeathEvent event) {
            bleeding.remove(event.getEntity().getUniqueId());
        }

        /** Runs every second. */
        void bleedTick() {
            double damage = cfg("bleed-damage", 1.0);
            bleeding.entrySet().removeIf(entry -> {
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (!(entity instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) return true;
                victim.damage(damage);
                victim.getWorld().spawnParticle(Particle.DUST, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0,
                        new Particle.DustOptions(Color.fromRGB(160, 0, 0), 1.2f));
                entry.setValue(entry.getValue() - 1);
                return entry.getValue() <= 0;
            });
        }

        // ---------- death & loot ----------

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent event) {
            if (!isVampire(event.getEntity())) return;
            byBody.remove(event.getEntity().getUniqueId());
            event.getDrops().clear(); // no Wither Skeleton loot (bones, coal, skulls)
            event.setDroppedExp(0);
            if (event.getEntity().getKiller() == null) return; // loot only for player kills

            event.setDroppedExp((int) cfg("xp", 30));
            List<ItemStack> drops = event.getDrops();
            drops.add(new ItemStack(Material.SPIDER_EYE, 1 + random.nextInt(3)));
            if (random.nextDouble() < cfg("gunpowder-chance", 0.5)) {
                drops.add(new ItemStack(Material.GUNPOWDER, 3 + random.nextInt(4)));
            }
            if (random.nextDouble() < cfg("diamond-chance", 0.05)) {
                drops.add(new ItemStack(Material.DIAMOND, 2 + random.nextInt(5)));
            }
            if (random.nextDouble() < cfg("accessory-chance", 0.025) * plugin.eventDropMultiplier()) {
                drops.add(random.nextBoolean() ? NewAccessoryItems.moonStone(plugin) : NewAccessoryItems.spelunkerAmulet(plugin));
            }
        }

        void removeAll() {
            byBody.values().forEach(v -> v.body.remove());
            byBody.clear();
        }

        void removeLeftovers() {
            for (World world : Bukkit.getWorlds()) {
                for (LivingEntity entity : world.getLivingEntities()) {
                    if (isVampire(entity)) entity.remove();
                }
            }
        }
    }

    // ===================== FROST WRAITH (custom mob, Snowy Days only) =====================

    static class FrostWraithEggItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.STRAY_SPAWN_EGG);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Frost Wraith Spawn Egg");
            meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to summon a Frost Wraith."));
            meta.getPersistentDataContainer().set(plugin.getFrostWraithEggKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static boolean isEgg(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.STRAY_SPAWN_EGG || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getFrostWraithEggKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    /**
     * During a Snowy Day (run by FaultlineEvents), outside Deserts/Badlands:
     * half of natural Skeleton spawns become Strays, and a few Strays become
     * Frost Wraiths. Frost Wraiths wear a Blue Ice block on their head, and
     * their arrows add Slowness II + the powder-snow freeze.
     */
    static class FrostWraithManager implements Listener {

        static final String TAG = "faultline_frost_wraith";
        static final Set<Biome> NO_SNOW = Set.of(Biome.DESERT, Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS);

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Set<UUID> alive = new HashSet<>();

        FrostWraithManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        static boolean isWraith(Entity entity) {
            return entity != null && entity.getScoreboardTags().contains(TAG);
        }

        private double cfg(String key, double def) {
            return plugin.getConfig().getDouble("frost-wraith." + key, def);
        }

        static boolean snowFallsAt(Location loc) {
            return loc.getWorld().getEnvironment() == World.Environment.NORMAL && !NO_SNOW.contains(loc.getBlock().getBiome());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
            if (!plugin.isEventActive("SNOWY_DAY")) return;
            Location loc = event.getLocation();
            if (!snowFallsAt(loc)) return;

            if (event.getEntityType() == EntityType.SKELETON && random.nextDouble() < cfg("skeleton-to-stray-chance", 0.5)) {
                event.setCancelled(true);
                if (loc.getWorld().spawnEntity(loc, EntityType.STRAY) instanceof Stray stray && stray.isValid()) {
                    maybeWraith(stray);
                }
            } else if (event.getEntity() instanceof Stray stray) {
                maybeWraith(stray);
            }
        }

        // ---------- surface Strays & Zombies during a Snowy Day ----------
        //
        // Hostile mobs only spawn in total darkness, and a Snowy Day is daytime, so
        // without this nothing would spawn on the surface. Every few seconds, near
        // each survival player standing where snow falls, a Stray or Zombie spawns
        // 16-32 blocks away. They don't burn in daylight. Capped per player and
        // server-wide, and never saved to disk.

        static final String SNOW_MOB_TAG = "faultline_snow_mob";
        private final Set<UUID> snowMobs = new HashSet<>();

        /** Runs every second; each player gets a spawn attempt every few seconds. */
        void surfaceSpawnTick(int second) {
            if (!plugin.isEventActive("SNOWY_DAY")) {
                // BUG FIX: once the Snowy Day is over, any of its mobs still alive burn
                // in daylight again (they used to stay fireproof forever).
                if (!snowMobs.isEmpty()) {
                    for (UUID id : snowMobs) {
                        Entity e = Bukkit.getEntity(id);
                        if (e instanceof Zombie z) z.setShouldBurnInDay(true);
                        else if (e instanceof Stray st) st.setShouldBurnInDay(true);
                    }
                    snowMobs.clear();
                }
                return;
            }
            int interval = Math.max(1, (int) plugin.getConfig().getDouble("snowy-day-mobs.spawn-interval-seconds", 5));
            if (second % interval != 0) return;

            snowMobs.removeIf(id -> {
                Entity e = Bukkit.getEntity(id);
                return e == null || !e.isValid();
            });
            int serverCap = (int) plugin.getConfig().getDouble("snowy-day-mobs.server-cap", 60);
            int playerCap = (int) plugin.getConfig().getDouble("snowy-day-mobs.per-player-cap", 8);

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (snowMobs.size() >= serverCap) return;
                if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
                World world = player.getWorld();
                if (world.getDifficulty() == Difficulty.PEACEFUL || !snowFallsAt(player.getLocation())) continue;

                long nearby = snowMobs.stream().map(Bukkit::getEntity)
                        .filter(e -> e != null && e.getWorld().equals(world)
                                && e.getLocation().distanceSquared(player.getLocation()) < 48 * 48)
                        .count();
                if (nearby >= playerCap) continue;

                Location spot = findSurfaceSpot(player);
                if (spot == null) continue;
                boolean stray = random.nextDouble() < plugin.getConfig().getDouble("snowy-day-mobs.stray-share", 0.5);
                // randomizeData = false: no babies/chicken jockeys; the Stray gets its bow below.
                Entity entity = world.spawnEntity(spot, stray ? EntityType.STRAY : EntityType.ZOMBIE, false);
                if (!(entity instanceof Monster mob) || !mob.isValid()) continue;

                mob.addScoreboardTag(SNOW_MOB_TAG);
                mob.setPersistent(false);
                if (mob instanceof Stray s) {
                    s.setShouldBurnInDay(false);
                    if (s.getEquipment() != null) {
                        s.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                        s.getEquipment().setItemInMainHandDropChance(0f);
                    }
                    maybeWraith(s);
                } else if (mob instanceof Zombie z) {
                    z.setShouldBurnInDay(false);
                }
                snowMobs.add(mob.getUniqueId());
            }
        }

        private Location findSurfaceSpot(Player player) {
            Location center = player.getLocation();
            World world = center.getWorld();
            double min = plugin.getConfig().getDouble("snowy-day-mobs.min-distance", 16);
            double max = plugin.getConfig().getDouble("snowy-day-mobs.max-distance", 32);
            for (int attempt = 0; attempt < 6; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double dist = min + random.nextDouble() * (max - min);
                int x = (int) Math.floor(center.getX() + Math.cos(angle) * dist);
                int z = (int) Math.floor(center.getZ() + Math.sin(angle) * dist);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue; // never force-load chunks
                Block ground = world.getHighestBlockAt(x, z);
                if (!ground.getType().isSolid() || ground.isLiquid() || Tag.LEAVES.isTagged(ground.getType())) continue; // not on treetops
                Block feet = ground.getRelative(0, 1, 0);
                // snow layers are passable, so mobs can spawn on freshly snowed ground
                if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable() || feet.isLiquid()) continue;
                Location spot = feet.getLocation().add(0.5, 0, 0.5);
                if (!snowFallsAt(spot)) continue;
                boolean tooClose = world.getPlayers().stream().anyMatch(p -> p.getLocation().distanceSquared(spot) < min * min);
                if (!tooClose) return spot;
            }
            return null;
        }

        private void maybeWraith(Stray stray) {
            alive.removeIf(id -> {
                Entity e = Bukkit.getEntity(id);
                return e == null || !e.isValid();
            });
            if (alive.size() >= (int) cfg("max-alive", 10)) return;
            if (random.nextDouble() < cfg("stray-to-wraith-chance", 0.08)) makeWraith(stray);
        }

        void makeWraith(Stray stray) {
            stray.addScoreboardTag(TAG);
            stray.setPersistent(false);
            stray.setCanPickupItems(false);
            stray.setCustomName(ChatColor.AQUA + "" + ChatColor.BOLD + "Frost Wraith");
            stray.setCustomNameVisible(false);

            EntityEquipment gear = stray.getEquipment();
            if (gear != null) {
                gear.setHelmet(new ItemStack(Material.BLUE_ICE)); // a block of ice where its head is
                gear.setItemInMainHand(new ItemStack(Material.BOW));
                gear.setHelmetDropChance(0f);
                gear.setItemInMainHandDropChance(0f);
            }
            AttributeInstance max = stray.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(cfg("health", 30));
                stray.setHealth(max.getValue());
            }
            alive.add(stray.getUniqueId());
            Location loc = stray.getLocation().add(0, 1, 0);
            loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc, 30, 0.4, 0.8, 0.4, 0.02);
        }

        /** Frost Wraith arrows: Slowness II + the powder-snow freeze (blue hearts, frosty screen). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onArrowHit(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof AbstractArrow arrow)) return;
            if (!(arrow.getShooter() instanceof Entity shooter) || !isWraith(shooter)) return;
            if (!(event.getEntity() instanceof LivingEntity victim)) return;
            if (shieldBlocked(event)) return; // blocked with a shield: no freeze
            int seconds = (int) cfg("slow-seconds", 3);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 1));
            victim.setFreezeTicks(Math.max(victim.getFreezeTicks(), 140 + seconds * 20));
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent event) {
            if (!isWraith(event.getEntity())) return;
            alive.remove(event.getEntity().getUniqueId());
            event.getDrops().clear(); // no normal Stray loot (tipped arrows etc.)
            event.setDroppedExp(0);
            if (event.getEntity().getKiller() == null) return; // loot only for player kills

            event.setDroppedExp((int) cfg("xp", 15));
            event.getDrops().add(new ItemStack(Material.BONE, 2 + random.nextInt(2)));
            if (random.nextDouble() < cfg("bow-or-book-chance", 0.30)) {
                event.getDrops().add(random.nextBoolean() ? powerBook() : new ItemStack(Material.BOW));
            }
            if (random.nextDouble() < cfg("frost-flare-chance", 0.025) * plugin.eventDropMultiplier()) {
                event.getDrops().add(NewAccessoryItems.frostFlare(plugin));
            }
        }

        private ItemStack powerBook() {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(Enchantment.POWER, 1, true);
                book.setItemMeta(meta);
            }
            return book;
        }

        @EventHandler
        public void onEggUse(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!FrostWraithEggItem.isEgg(plugin, event.getItem())) return;
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

            Player player = event.getPlayer();
            Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
            if (!(at.getWorld().spawnEntity(at, EntityType.STRAY) instanceof Stray stray) || !stray.isValid()) return;
            makeWraith(stray);
            if (player.getGameMode() != GameMode.CREATIVE) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDispense(BlockDispenseEvent event) {
            if (FrostWraithEggItem.isEgg(plugin, event.getItem())) event.setCancelled(true);
        }
    }

    // ===================== SPIDER STAFF & QUEEN SPIDER =====================

    static class SpiderStaffItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.BREEZE_ROD);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Spider Staff");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to summon 3-5 spiders.",
                    ChatColor.GRAY + "They last 30 seconds. Cooldown: 1 minute.",
                    ChatColor.GREEN + "They fight monsters, and whoever you're fighting."));
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(plugin.getSpiderStaffKey(), PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("faultline", "spider_staff")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        static boolean isStaff(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.BREEZE_ROD || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getSpiderStaffKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    static class QueenSpiderEggItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.SPIDER_SPAWN_EGG);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Queen Spider Spawn Egg");
            meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to summon the Queen Spider."));
            meta.getPersistentDataContainer().set(plugin.getQueenEggKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static boolean isEgg(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.SPIDER_SPAWN_EGG || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getQueenEggKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    /**
     * The Queen Spider: a cave boss. 1% of spiders that spawn underground (no sky
     * above) become a Queen, if there's room for her. 3x a spider's size, 500 health.
     *
     * Moves: poison Bite, Brood (Spiders + Cave Spiders), Stomp (leap and slam),
     * Pounce (leaps onto you from a distance), Venom Spit (green poison shots), Web
     * Trap (webs all around you), Egg Sacs (hatch into Cave Spiders unless broken).
     * Enrages at half health: faster, everything on shorter cooldowns.
     *
     * Webs and egg sacs are always removed again. Her brood drop nothing, so she
     * can't be farmed. Settings live under "queen-spider-boss" in config.yml.
     */
    static class QueenSpiderManager implements Listener {

        static final String TAG = "faultline_queen_spider";
        static final String BROOD_TAG = "faultline_brood";
        static final String VENOM_TAG = "faultline_queen_venom";

        private static class Queen {
            Spider body;
            BossBar bar;
            final Set<UUID> brood = new HashSet<>();
            long nextBrood, nextStomp, nextWeb, nextPounce, nextSpit, nextSacs, landAt, pounceLandAt;
            boolean stomping, pouncing, enraged;
        }

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Queen> queens = new HashMap<>();
        private final Set<Block> webs = new HashSet<>();
        private final Map<Block, UUID> eggSacs = new HashMap<>(); // sac block -> its queen
        private final Map<Block, org.bukkit.entity.ItemDisplay> sacLooks = new HashMap<>(); // the egg you can see on each sac

        QueenSpiderManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        static boolean isQueen(Entity e) {
            return e != null && e.getScoreboardTags().contains(TAG);
        }

        static boolean isBrood(Entity e) {
            return e != null && e.getScoreboardTags().contains(BROOD_TAG);
        }

        private double cfg(String key, double def) {
            return plugin.getConfig().getDouble("queen-spider-boss." + key, def);
        }

        /** Enraged: every cooldown is shorter. */
        private long cd(Queen q, String key, double seconds) {
            return (long) (cfg(key, seconds) * 1000 * (q.enraged ? 0.5 : 1.0));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
            if (event.getEntityType() != EntityType.SPIDER || !(event.getEntity() instanceof Spider spider)) return;
            Location loc = event.getLocation();
            if (loc.getBlock().getLightFromSky() > 0) return; // caves only
            queens.values().removeIf(q -> !q.body.isValid());
            if (queens.size() >= (int) cfg("max-alive", 2)) return;
            if (random.nextDouble() >= cfg("spawn-chance", 0.01)) return;
            if (!roomFor(loc)) return; // she's huge; a cramped tunnel would trap her
            makeQueen(spider);
        }

        /** A 5x3x5 pocket of open space around the spot. */
        private boolean roomFor(Location loc) {
            Block base = loc.getBlock();
            for (int x = -2; x <= 2; x++)
                for (int y = 0; y <= 2; y++)
                    for (int z = -2; z <= 2; z++)
                        if (!base.getRelative(x, y, z).isPassable()) return false;
            return true;
        }

        void makeQueen(Spider spider) {
            spider.addScoreboardTag(TAG);
            spider.setPersistent(false);
            spider.setRemoveWhenFarAway(false);
            spider.setCustomName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Queen Spider");
            spider.setCustomNameVisible(true);
            setAttr(spider, Attribute.SCALE, 3.0);
            setAttr(spider, Attribute.MAX_HEALTH, cfg("health", 500));
            spider.setHealth(cfg("health", 500));
            setAttr(spider, Attribute.ATTACK_DAMAGE, cfg("bite-damage", 12));
            setAttr(spider, Attribute.KNOCKBACK_RESISTANCE, 0.85);
            setAttr(spider, Attribute.FOLLOW_RANGE, 40);

            Queen q = new Queen();
            q.body = spider;
            q.bar = Bukkit.createBossBar(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Queen Spider", BarColor.PURPLE, BarStyle.SEGMENTED_20);
            long now = System.currentTimeMillis();
            q.nextBrood = now + 5000;
            q.nextStomp = now + 3000;
            q.nextWeb = now + 6000;
            q.nextPounce = now + 4000;
            q.nextSpit = now + 2500;
            q.nextSacs = now + 10000;
            queens.put(spider.getUniqueId(), q);
            spider.getWorld().playSound(spider.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.4f);
        }

        private static void setAttr(LivingEntity e, Attribute attribute, double value) {
            AttributeInstance inst = e.getAttribute(attribute);
            if (inst != null) inst.setBaseValue(value);
        }

        /** Every half second. */
        void tick() {
            long now = System.currentTimeMillis();
            for (Queen q : new ArrayList<>(queens.values())) {
                Spider queen = q.body;
                if (!queen.isValid() || queen.isDead()) {
                    q.bar.removeAll();
                    queens.remove(queen.getUniqueId());
                    continue;
                }
                AttributeInstance max = queen.getAttribute(Attribute.MAX_HEALTH);
                double maxHp = max == null ? 500 : max.getValue();
                q.bar.setProgress(Math.max(0, Math.min(1, queen.getHealth() / maxHp)));
                List<Player> near = new ArrayList<>();
                for (Player p : queen.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(queen.getLocation()) <= 40 * 40) near.add(p);
                }
                for (Player p : new ArrayList<>(q.bar.getPlayers())) if (!near.contains(p)) q.bar.removePlayer(p);
                for (Player p : near) if (!q.bar.getPlayers().contains(p)) q.bar.addPlayer(p);

                // Enrage at half health
                if (!q.enraged && queen.getHealth() <= maxHp / 2) {
                    q.enraged = true;
                    AttributeInstance speed = queen.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.35);
                    q.bar.setColor(BarColor.RED);
                    queen.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Queen Spider (Enraged)");
                    queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2f, 1.6f);
                    queen.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, queen.getLocation().add(0, 2, 0), 15, 1.5, 1, 1.5, 0);
                    for (Player p : near) p.sendMessage(ChatColor.DARK_RED + "The Queen Spider shrieks in fury!");
                }

                Player target = queen.getTarget() instanceof Player p && !p.isDead() ? p : nearestSurvival(queen.getLocation(), 28);
                if (target == null) continue;
                if (queen.getTarget() != target) queen.setTarget(target);
                double dist = target.getLocation().distance(queen.getLocation());

                // Landings from Stomp and Pounce
                if (q.stomping && now >= q.landAt) {
                    q.stomping = false;
                    slam(queen, cfg("stomp-radius", 6), cfg("stomp-damage", 10));
                }
                if (q.pouncing && now >= q.pounceLandAt) {
                    q.pouncing = false;
                    slam(queen, 3.5, cfg("pounce-damage", 10));
                }
                if (q.stomping || q.pouncing) continue; // mid-air: no other moves

                if (dist <= 6 && queen.isOnGround() && now >= q.nextStomp) {
                    // Stomp: leap up, then slam down
                    q.stomping = true;
                    q.landAt = now + 900;
                    q.nextStomp = now + cd(q, "stomp-cooldown-seconds", 7);
                    Safe.vel(queen, new Vector(0, 1.0, 0));
                    queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.4f);
                } else if (dist >= 6 && dist <= 16 && queen.isOnGround() && now >= q.nextPounce) {
                    // Pounce: leap onto the target from a distance
                    q.pouncing = true;
                    q.pounceLandAt = now + 1100;
                    q.nextPounce = now + cd(q, "pounce-cooldown-seconds", 9);
                    Vector jump = target.getLocation().toVector().subtract(queen.getLocation().toVector()).setY(0);
                    Safe.vel(queen, jump.normalize().multiply(Math.min(2.2, dist * 0.13)).setY(0.75));
                    queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_SPIDER_STEP, 2f, 0.5f);
                } else if (dist >= 4 && dist <= 24 && now >= q.nextSpit && queen.hasLineOfSight(target)) {
                    spit(queen, target);
                    q.nextSpit = now + cd(q, "spit-cooldown-seconds", 5);
                }

                // Brood
                q.brood.removeIf(id -> {
                    Entity e = Bukkit.getEntity(id);
                    return e == null || !e.isValid() || e.isDead();
                });
                if (now >= q.nextBrood && q.brood.size() < (int) cfg("max-brood", 14)) {
                    for (int i = 0; i < 5; i++) spawnBrood(q, queen.getLocation(), i < 3 ? EntityType.CAVE_SPIDER : EntityType.SPIDER, target);
                    q.nextBrood = now + cd(q, "brood-seconds", 8);
                    queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 1.4f);
                }

                // Web Trap: webs all around the target
                if (now >= q.nextWeb && dist <= 18 && queen.hasLineOfSight(target)) {
                    Block feet = target.getLocation().getBlock();
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) placeWeb(feet.getRelative(x, 0, z), (long) cfg("web-seconds", 5));
                    queen.getWorld().playSound(target.getLocation(), Sound.ENTITY_SPIDER_HURT, 1f, 0.5f);
                    q.nextWeb = now + cd(q, "web-cooldown-seconds", 14);
                }

                // Egg Sacs: hatch into Cave Spiders unless broken first
                if (now >= q.nextSacs) {
                    int laid = laySacs(queen, target, (int) cfg("egg-sacs", 3));
                    if (laid > 0) {
                        queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_TURTLE_LAY_EGG, 2f, 0.5f);
                        queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.6f);
                        for (Player p : near) {
                            p.sendMessage(ChatColor.DARK_PURPLE + "The Queen Spider lays " + laid + " egg sacs! " + ChatColor.GRAY + "Break them before they hatch.");
                            p.sendActionBar(legacy(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "EGG SACS! " + ChatColor.GRAY + "Break them before they hatch!"));
                        }
                        q.nextSacs = now + cd(q, "egg-sac-cooldown-seconds", 22);
                    } else q.nextSacs = now + 2000; // nowhere to put them right now: try again soon
                }
            }
        }

        private void spawnBrood(Queen q, Location around, EntityType type, Player target) {
            Location at = around.clone().add(random.nextInt(5) - 2, 0.2, random.nextInt(5) - 2);
            if (!at.getBlock().isPassable()) at = around;
            if (at.getWorld().spawnEntity(at, type, false) instanceof Mob baby && baby.isValid()) {
                baby.addScoreboardTag(BROOD_TAG);
                baby.setPersistent(false);
                if (target != null) baby.setTarget(target);
                q.brood.add(baby.getUniqueId());
            }
        }

        /**
         * Lays egg sacs on open floor around her: a big egg you can see, wrapped in web (break the web to destroy it).
         * BUG FIX: they used to be plain cobwebs dropped within 4 blocks of her, at her feet height. She's 3x a spider's
         * size, so they mostly ended up under her own body, and with only an action bar line it looked like she never
         * laid any. Now they go on real floor 3-7 blocks out (toward whoever she's fighting), with a sound and a chat line.
         */
        private int laySacs(Spider queen, Player target, int count) {
            Location c = queen.getLocation();
            Vector toTarget = target.getLocation().toVector().subtract(c.toVector()).setY(0);
            double base = toTarget.lengthSquared() > 0.01 ? Math.atan2(toTarget.getZ(), toTarget.getX()) : random.nextDouble() * Math.PI * 2;
            int laid = 0;
            for (int tries = 0; tries < 24 && laid < count; tries++) {
                double ang = base + (random.nextDouble() - 0.5) * Math.PI * 1.4;
                double r = 3 + random.nextDouble() * 4;
                Block spot = null;
                Block col = c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r).getBlock();
                for (int dy = 2; dy >= -3; dy--) { // find the floor near her height
                    Block b = col.getRelative(0, dy, 0);
                    if (b.getType().isAir() && b.getRelative(0, -1, 0).getType().isSolid()) { spot = b; break; }
                }
                if (spot == null || eggSacs.containsKey(spot)) continue;
                spot.setType(Material.COBWEB);
                eggSacs.put(spot, queen.getUniqueId());
                Location eggAt = spot.getLocation().add(0.5, 0.45, 0.5);
                org.bukkit.entity.ItemDisplay egg = spot.getWorld().spawn(eggAt, org.bukkit.entity.ItemDisplay.class, d -> {
                    d.setItemStack(new ItemStack(Material.SNIFFER_EGG));
                    d.setPersistent(false);
                    d.setBrightness(new org.bukkit.entity.Display.Brightness(12, 12));
                    d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(0.75f, 0.75f, 0.75f), new org.joml.Quaternionf()));
                    d.setInterpolationDuration(10);
                    d.addScoreboardTag(TAG + "_sac");
                });
                sacLooks.put(spot, egg);
                spot.getWorld().spawnParticle(Particle.ITEM_SLIME, eggAt, 16, 0.3, 0.3, 0.3, 0);
                long hatchTicks = (long) (cfg("egg-sac-hatch-seconds", 6) * 20);
                for (long k = 10; k < hatchTicks; k += 10) { // it swells and throbs faster as it gets ready to hatch
                    final float f = (float) k / hatchTicks;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!egg.isValid()) return;
                        float sz = 0.75f + 0.35f * f + (float) Math.sin(f * 30) * 0.05f;
                        egg.setInterpolationDelay(0);
                        egg.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                                new org.joml.Vector3f(sz, sz, sz), new org.joml.Quaternionf()));
                        if (f > 0.6) egg.getWorld().playSound(egg.getLocation(), Sound.ENTITY_TURTLE_EGG_CRACK, 0.6f, 0.7f + f * 0.4f);
                    }, k);
                }
                final Block sac = spot;
                Bukkit.getScheduler().runTaskLater(plugin, () -> hatch(sac), hatchTicks);
                laid++;
            }
            return laid;
        }

        private void removeSacLook(Block sac) {
            org.bukkit.entity.ItemDisplay egg = sacLooks.remove(sac);
            if (egg != null && egg.isValid()) egg.remove();
        }

        /** Breaking the web destroys the egg inside (and nothing hatches). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSacBreak(BlockBreakEvent event) {
            Block b = event.getBlock();
            if (!eggSacs.containsKey(b)) return;
            eggSacs.remove(b);
            event.setDropItems(false);
            b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TURTLE_EGG_BREAK, 1.5f, 0.6f);
            b.getWorld().spawnParticle(Particle.ITEM_SLIME, b.getLocation().add(0.5, 0.4, 0.5), 20, 0.3, 0.3, 0.3, 0);
            removeSacLook(b);
        }

        private void hatch(Block sac) {
            removeSacLook(sac);
            UUID queenId = eggSacs.remove(sac);
            if (queenId == null || sac.getType() != Material.COBWEB) return; // broken in time
            sac.setType(Material.AIR);
            sac.getWorld().playSound(sac.getLocation(), Sound.ENTITY_TURTLE_EGG_HATCH, 1.5f, 0.6f);
            Queen q = queens.get(queenId);
            if (q == null) return;
            Player target = nearestSurvival(sac.getLocation(), 24);
            for (int i = 0; i < 3; i++) spawnBrood(q, sac.getLocation().add(0.5, 0, 0.5), EntityType.CAVE_SPIDER, target);
        }

        private void placeWeb(Block block, long seconds) {
            if (!block.getType().isAir()) return;
            block.setType(Material.COBWEB);
            webs.add(block);
            Bukkit.getScheduler().runTaskLater(plugin, () -> clearWeb(block), seconds * 20L);
        }

        /** Venom Spit: a green glob that poisons hard and makes you dizzy. */
        private void spit(Spider queen, Player target) {
            Snowball glob = queen.launchProjectile(Snowball.class);
            glob.setItem(new ItemStack(Material.LIME_DYE));
            glob.addScoreboardTag(VENOM_TAG);
            Vector aim = target.getEyeLocation().subtract(0, 0.5, 0).toVector().subtract(queen.getEyeLocation().toVector());
            double dist = aim.length();
            aim.setY(aim.getY() + dist * 0.08); // arc
            Safe.vel(glob, aim.normalize().multiply(1.3));
            queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.5f, 0.6f);
        }

        @EventHandler
        public void onVenomHit(ProjectileHitEvent event) {
            if (!event.getEntity().getScoreboardTags().contains(VENOM_TAG)) return;
            Location at = event.getEntity().getLocation();
            at.getWorld().spawnParticle(Particle.ITEM_SLIME, at, 20, 0.4, 0.4, 0.4, 0);
            if (event.getHitEntity() instanceof LivingEntity victim && !isQueen(victim) && !isBrood(victim)) {
                Entity shooter = event.getEntity().getShooter() instanceof Entity s ? s : null;
                if (victim instanceof Player p && facingBlock(p, at.toVector())) {
                    shieldHit(p, cfg("spit-damage", 4)); // blockable: no damage, poison, or nausea
                } else {
                    victim.damage(cfg("spit-damage", 4), shooter);
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 2));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
                }
            }
            event.getEntity().remove();
        }

        private Player nearestSurvival(Location loc, double range) {
            Player best = null;
            double bestDist = range * range;
            for (Player p : loc.getWorld().getPlayers()) {
                if (p.isDead() || (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)) continue;
                double d = p.getLocation().distanceSquared(loc);
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            return best;
        }

        private void slam(Spider queen, double radius, double damage) {
            Location at = queen.getLocation();
            at.getWorld().spawnParticle(Particle.EXPLOSION, at, 8, radius / 3, 0.2, radius / 3, 0);
            at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
            for (Entity e : queen.getNearbyEntities(radius, 3, radius)) {
                if (!(e instanceof Player p) || p.isDead()) continue;
                slamming = true; // unblockable: a ground slam has to be dodged
                try {
                    p.damage(damage, queen);
                } finally {
                    slamming = false;
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1));
                Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                Safe.vel(p, away.normalize().multiply(1.2).setY(0.6));
            }
        }

        private void clearWeb(Block block) {
            if (webs.remove(block) && block.getType() == Material.COBWEB) block.setType(Material.AIR);
        }

        private boolean slamming;

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onSlamDamage(EntityDamageByEntityEvent event) {
            if (slamming && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
                event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
            }
        }

        /** Her bite poisons hard. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBite(EntityDamageByEntityEvent event) {
            if (!isQueen(event.getDamager()) || !(event.getEntity() instanceof LivingEntity victim)) return;
            if (shieldBlocked(event)) return; // blocked with a shield: no poison
            victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) (cfg("poison-seconds", 7) * 20), 1));
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent event) {
            LivingEntity dead = event.getEntity();
            if (isBrood(dead)) { // brood never drop anything, so she can't be farmed
                event.getDrops().clear();
                event.setDroppedExp(0);
                return;
            }
            if (!isQueen(dead)) return;
            Queen q = queens.remove(dead.getUniqueId());
            if (q != null) q.bar.removeAll();
            for (Map.Entry<Block, UUID> e : new ArrayList<>(eggSacs.entrySet())) { // her unhatched sacs die with her
                if (!e.getValue().equals(dead.getUniqueId())) continue;
                eggSacs.remove(e.getKey());
                removeSacLook(e.getKey());
                if (e.getKey().getType() == Material.COBWEB) e.getKey().setType(Material.AIR);
            }
            event.getDrops().clear();
            event.setDroppedExp(0);
            Player killer = dead.getKiller();
            if (killer == null) return;

            event.setDroppedExp((int) cfg("xp", 300));
            event.getDrops().add(new ItemStack(Material.SPIDER_EYE, 10));
            if (random.nextDouble() < cfg("diamond-chance", 0.5)) {
                event.getDrops().add(new ItemStack(Material.DIAMOND, 5 + random.nextInt(6)));
            }
            if (random.nextDouble() < cfg("spider-staff-chance", 0.10) * plugin.eventDropMultiplier()) {
                event.getDrops().add(SpiderStaffItem.create(plugin));
            }
            if (random.nextDouble() < plugin.getConfig().getDouble("boss-loot.queen-silk-chance", 0.05) * plugin.eventDropMultiplier()) {
                event.getDrops().add(QueenSilkItem.create(plugin));
                Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + killer.getName() + " claimed the Queen's Silk!");
            }
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + killer.getName() + " slew the Queen Spider!");
        }

        @EventHandler
        public void onEggUse(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!QueenSpiderEggItem.isEgg(plugin, event.getItem())) return;
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

            Player player = event.getPlayer();
            Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
            if (!(at.getWorld().spawnEntity(at, EntityType.SPIDER, false) instanceof Spider spider) || !spider.isValid()) return;
            makeQueen(spider);
            if (player.getGameMode() != GameMode.CREATIVE) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDispense(BlockDispenseEvent event) {
            if (QueenSpiderEggItem.isEgg(plugin, event.getItem())) event.setCancelled(true);
        }

        void removeAll() {
            for (Queen q : queens.values()) {
                q.bar.removeAll();
                q.body.remove();
            }
            queens.clear();
            new ArrayList<>(webs).forEach(this::clearWeb);
            for (Block sac : new ArrayList<>(eggSacs.keySet())) {
                if (sac.getType() == Material.COBWEB) sac.setType(Material.AIR);
                removeSacLook(sac);
            }
            eggSacs.clear();
        }

        private static net.kyori.adventure.text.Component legacy(String text) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text);
        }
    }

    // ===================== MOON STONE: BAT FORM =====================

    /**
     * Shift + F (sneak + swap hands) or /batform, with the Moon Stone equipped:
     * become a bat for 20 seconds. Others see a bat (your body and gear are hidden),
     * you shrink and can fly, but you're down to 2 hearts and can't fight or use
     * anything. Health scales back when you change back (no free healing).
     * 120 second cooldown once it ends.
     */
    static class BatFormManager implements Listener {

        static final String BAT_TAG = "faultline_player_bat";

        private static class Form {
            Bat bat;
            long endsAt;
            double savedHealth;
            boolean hadAllowFlight;
            boolean hadCloudPotion; // allowFlight was only on because of a Cloud Potion
            float flySpeed;
        }

        private final FaultlineItems plugin;
        private final Map<UUID, Form> forms = new HashMap<>();
        private final Map<UUID, Long> cooldownUntil = new HashMap<>();
        private final NamespacedKey healthKey;
        private final NamespacedKey scaleKey;
        private int ticks = 0;

        BatFormManager(FaultlineItems plugin) {
            this.plugin = plugin;
            this.healthKey = new NamespacedKey(plugin, "bat_form_health");
            this.scaleKey = new NamespacedKey(plugin, "bat_form_scale");
        }

        boolean isBat(Player player) {
            return forms.containsKey(player.getUniqueId());
        }

        private double cfg(String key, double def) {
            return plugin.getConfig().getDouble("moon-stone.bat-form." + key, def);
        }

        // ---------- triggers ----------

        /** Shift + F. Plain F still swaps hands normally. */
        @EventHandler(ignoreCancelled = true)
        public void onSwapHands(PlayerSwapHandItemsEvent event) {
            Player player = event.getPlayer();
            if (!player.isSneaking()) return;
            if (!isBat(player) && !plugin.getAccessoryManager().hasEquipped(player, plugin.getMoonStoneKey())) return;
            event.setCancelled(true);
            toggle(player);
        }

        void toggle(Player player) {
            if (isBat(player)) {
                end(player, true);
                return;
            }
            if (!plugin.getAccessoryManager().hasEquipped(player, plugin.getMoonStoneKey())) {
                player.sendMessage(ChatColor.RED + "You need the Moon Stone equipped to turn into a bat.");
                return;
            }
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
                player.sendMessage(ChatColor.RED + "Bat form only works in survival.");
                return;
            }
            long left = cooldownUntil.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                player.sendMessage(ChatColor.RED + "You can't turn into a bat again for " + ((left + 999) / 1000) + "s.");
                return;
            }
            start(player);
        }

        // ---------- start / end ----------

        private void start(Player player) {
            Form form = new Form();
            form.savedHealth = player.getHealth();
            form.hadAllowFlight = player.getAllowFlight();
            form.hadCloudPotion = plugin.getDoubleJumpManager().isActive(player.getUniqueId());
            form.flySpeed = player.getFlySpeed();
            form.endsAt = System.currentTimeMillis() + (long) (cfg("seconds", 20) * 1000);

            // 2 hearts
            double batHealth = cfg("hearts", 2) * 2;
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                removeModifier(max, healthKey);
                max.addModifier(new AttributeModifier(healthKey, batHealth - max.getValue(),
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                player.setHealth(Math.max(1, Math.min(batHealth, form.savedHealth)));
            }
            // bat-sized
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            if (scale != null) {
                removeModifier(scale, scaleKey);
                scale.addModifier(new AttributeModifier(scaleKey, cfg("scale", 0.35) - 1,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setFlySpeed((float) cfg("fly-speed", 0.12));
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));

            // what everyone else sees: a bat where you are
            if (player.getWorld().spawnEntity(player.getLocation(), EntityType.BAT, false) instanceof Bat bat) {
                bat.addScoreboardTag(BAT_TAG);
                bat.setPersistent(false);
                bat.setAI(false);
                bat.setGravity(false);
                bat.setAwake(true);
                player.hideEntity(plugin, bat); // you don't see your own bat in your face
                form.bat = bat;
            }
            forms.put(player.getUniqueId(), form);
            hideGear(player);

            Location loc = player.getLocation().add(0, 0.5, 0);
            loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 20, 0.3, 0.5, 0.3, 0.02);
            loc.getWorld().spawnParticle(Particle.DUST, loc, 20, 0.4, 0.6, 0.4, 0, new Particle.DustOptions(Color.fromRGB(120, 0, 0), 1.3f));
            loc.getWorld().playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.8f);
            player.sendMessage(ChatColor.DARK_RED + "You turn into a bat! " + ChatColor.GRAY
                    + "(" + (int) cfg("seconds", 20) + "s, Shift + F to change back early)");
        }

        /** died = true skips the health restore (they're respawning anyway). */
        private void end(Player player, boolean announce) {
            end(player, announce, false);
        }

        private void end(Player player, boolean announce, boolean died) {
            Form form = forms.remove(player.getUniqueId());
            if (form == null) return;

            double batHealth = cfg("hearts", 2) * 2;
            double fraction = Math.max(0, Math.min(1, player.getHealth() / batHealth));
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) removeModifier(max, healthKey);
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            if (scale != null) removeModifier(scale, scaleKey);
            if (!died && max != null && !player.isDead()) {
                // health scales with what the bat had left: no free healing from bat form
                player.setHealth(Math.max(1, Math.min(max.getValue(), form.savedHealth * fraction)));
            }

            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            player.setFlySpeed(form.flySpeed);
            boolean creativeFlight = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
            if (!creativeFlight) {
                player.setFlying(false);
                // BUG FIX (free flight): flight is never "put back" from what it was when the form started (a leftover
                // allowFlight from anywhere was passed on by every bat form, = creative-style flight in survival).
                // A survival player keeps it only for a Cloud Potion still running, or a /fly permission.
                boolean allow = plugin.getDoubleJumpManager().isActive(player.getUniqueId())
                        || form.hadAllowFlight && FlightGuard.mayFly(player);
                player.setAllowFlight(allow);
            }
            if (!died && !player.isOnGround()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 6, 0, false, false, true));
            }
            if (form.bat != null && form.bat.isValid()) form.bat.remove();
            showGear(player);
            cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + (long) (cfg("cooldown-seconds", 120) * 1000));

            if (announce && player.isOnline()) {
                Location loc = player.getLocation().add(0, 0.5, 0);
                loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 20, 0.3, 0.5, 0.3, 0.02);
                loc.getWorld().playSound(loc, Sound.ENTITY_BAT_DEATH, 0.8f, 0.7f);
                player.sendMessage(ChatColor.GRAY + "You change back. Bat form recharges in " + (int) cfg("cooldown-seconds", 120) + "s.");
            }
        }

        private static void removeModifier(AttributeInstance attribute, NamespacedKey key) {
            attribute.getModifiers().stream().filter(m -> m.getKey().equals(key)).toList().forEach(attribute::removeModifier);
        }

        // ---------- gear hiding (invisibility alone still shows armor and held items) ----------

        private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        private void hideGear(Player player) {
            Map<EquipmentSlot, ItemStack> empty = new HashMap<>();
            for (EquipmentSlot slot : SLOTS) empty.put(slot, new ItemStack(Material.AIR));
            for (Player other : player.getWorld().getPlayers()) {
                if (!other.equals(player)) other.sendEquipmentChange(player, empty);
            }
        }

        private void showGear(Player player) {
            if (!player.isOnline()) return;
            Map<EquipmentSlot, ItemStack> real = new HashMap<>();
            for (EquipmentSlot slot : SLOTS) {
                ItemStack item = player.getInventory().getItem(slot);
                real.put(slot, item == null ? new ItemStack(Material.AIR) : item);
            }
            for (Player other : player.getWorld().getPlayers()) {
                if (!other.equals(player)) other.sendEquipmentChange(player, real);
            }
        }

        // ---------- every 2 ticks ----------

        void tick() {
            ticks++;
            long now = System.currentTimeMillis();
            for (UUID id : new ArrayList<>(forms.keySet())) {
                Player player = Bukkit.getPlayer(id);
                Form form = forms.get(id);
                if (player == null || !player.isOnline()) {
                    if (form.bat != null) form.bat.remove();
                    forms.remove(id);
                    continue;
                }
                if (now >= form.endsAt || !plugin.getAccessoryManager().hasEquipped(player, plugin.getMoonStoneKey())) {
                    end(player, true);
                    continue;
                }
                if (form.bat == null || !form.bat.isValid()) {
                    end(player, true);
                    continue;
                }
                form.bat.teleport(player.getLocation().add(0, 0.1, 0));
                if (ticks % 5 == 0) {
                    hideGear(player); // re-send, in case new players came near or gear changed
                    player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                            ChatColor.DARK_RED + "Bat Form: " + ChatColor.WHITE + ((form.endsAt - now + 999) / 1000) + "s"));
                }
            }
        }

        // ---------- bats can't fight or use things; hits on the bat land on you ----------

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onBatAttack(EntityDamageByEntityEvent event) {
            Player attacker = NewAccessoryListener.attackerOf(event.getDamager());
            if (attacker != null && isBat(attacker)) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onBatHit(EntityDamageEvent event) {
            if (!event.getEntity().getScoreboardTags().contains(BAT_TAG)) return;
            event.setCancelled(true);
            if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
            for (Map.Entry<UUID, Form> entry : forms.entrySet()) {
                if (entry.getValue().bat != null && entry.getValue().bat.equals(event.getEntity())) {
                    Player owner = Bukkit.getPlayer(entry.getKey());
                    if (owner != null) owner.damage(event.getDamage(), byEntity.getDamager());
                    return;
                }
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onUse(PlayerInteractEvent event) {
            if (isBat(event.getPlayer())) {
                event.setUseInteractedBlock(Result.DENY);
                event.setUseItemInHand(Result.DENY);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onUseEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
            if (isBat(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            if (isBat(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            if (isBat(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler
        public void onDeath(PlayerDeathEvent event) {
            end(event.getEntity(), false, true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            end(event.getPlayer(), false);
        }

        /**
         * BUG FIX: Bat Form's changes (tiny size, 2 max hearts, invisibility, flight)
         * are saved in the player's file. A normal shutdown undoes them, but a crash
         * doesn't, which would leave someone stuck as an invisible tiny 2-heart flyer
         * forever. Anyone who joins with leftovers (and isn't actually a bat) is restored.
         */
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            if (isBat(player)) return;
            boolean leftover = false;
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && max.getModifiers().stream().anyMatch(m -> m.getKey().equals(healthKey))) {
                removeModifier(max, healthKey);
                leftover = true;
            }
            AttributeInstance scale = player.getAttribute(Attribute.SCALE);
            if (scale != null && scale.getModifiers().stream().anyMatch(m -> m.getKey().equals(scaleKey))) {
                removeModifier(scale, scaleKey);
                leftover = true;
            }
            if (!leftover) return;
            PotionEffect invis = player.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (invis != null && invis.isInfinite()) player.removePotionEffect(PotionEffectType.INVISIBILITY);
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setFlying(false);
                player.setAllowFlight(false);
                player.setFlySpeed(0.1f);
            }
            player.sendMessage(ChatColor.GRAY + "You were a bat when the server went down. You've been changed back.");
        }

        void endAll() {
            for (UUID id : new ArrayList<>(forms.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) end(player, false);
            }
        }
    }

    static class BatFormCommand implements CommandExecutor {
        private final FaultlineItems plugin;

        BatFormCommand(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can do that.");
                return true;
            }
            plugin.getBatFormManager().toggle(player);
            return true;
        }
    }

    // ===================== BOSS LOOT: GRAPPLING HOOK & QUEEN'S SILK =====================

    static class GrapplingHookItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.FISHING_ROD);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Rotbeard's Grappling Hook");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Cast it at a wall, ceiling, or ledge,",
                    ChatColor.GRAY + "then reel in to launch yourself there.",
                    ChatColor.DARK_GRAY + "Taken from Captain Rotbeard."));
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(plugin.getGrapplingHookKey(), PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("faultline", "grappling_hook")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        static boolean isHook(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getGrapplingHookKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    static class QueenSilkItem {

        static ItemStack create(FaultlineItems plugin) {
            ItemStack item = new ItemStack(Material.STRING);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Queen's Silk");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to shoot a glob of silk.",
                    ChatColor.GREEN + "Whatever it hits gets webbed and slowed.",
                    ChatColor.DARK_GRAY + "8 second cooldown. Taken from the Queen Spider."));
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(plugin.getQueenSilkKey(), PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("faultline", "queen_silk")); // texture from FaultlineItemTextures
            item.setItemMeta(meta);
            return item;
        }

        static boolean isSilk(FaultlineItems plugin, ItemStack item) {
            if (item == null || item.getType() != Material.STRING || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getQueenSilkKey(), PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    static class BossLootListener implements Listener {

        static final String SILK_TAG = "faultline_queen_silk_shot";
        static final String ROTBEARD_TAG = "faultline_rotbeard"; // set by FaultlineRaids

        private final FaultlineItems plugin;
        private final Random random = new Random();
        private final Map<UUID, Long> grappleCooldown = new HashMap<>();
        private final Map<UUID, Long> silkCooldown = new HashMap<>();
        private final Map<UUID, Long> noFallUntil = new HashMap<>();
        private final Set<Block> webs = new HashSet<>();

        BossLootListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        private double cfg(String key, double def) {
            return plugin.getConfig().getDouble("boss-loot." + key, def);
        }

        // ---------- Rotbeard drops the Grappling Hook ----------

        /** MONITOR: FaultlineRaids clears Rotbeard's normal drops at HIGHEST first. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRotbeardDeath(EntityDeathEvent event) {
            if (!event.getEntity().getScoreboardTags().contains(ROTBEARD_TAG)) return;
            Player killer = event.getEntity().getKiller();
            if (killer == null) return;
            if (random.nextDouble() < cfg("grappling-hook-chance", 0.10) * plugin.eventDropMultiplier()) {
                event.getDrops().add(GrapplingHookItem.create(plugin));
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + killer.getName()
                        + " claimed Rotbeard's Grappling Hook!");
            }
        }

        // ---------- Grappling Hook ----------

        private ItemStack rodInUse(Player player) {
            ItemStack main = player.getInventory().getItemInMainHand();
            if (main.getType() == Material.FISHING_ROD) return main;
            return player.getInventory().getItemInOffHand();
        }

        /** Reeling in while the hook is stuck in a block pulls you to it. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onReel(PlayerFishEvent event) {
            if (event.getState() != PlayerFishEvent.State.IN_GROUND) return;
            Player player = event.getPlayer();
            if (!GrapplingHookItem.isHook(plugin, rodInUse(player))) return;

            long now = System.currentTimeMillis();
            if (now < grappleCooldown.getOrDefault(player.getUniqueId(), 0L)) return;
            grappleCooldown.put(player.getUniqueId(), now + (long) (cfg("grapple-cooldown-seconds", 1.5) * 1000));

            Location hook = event.getHook().getLocation();
            Vector toward = hook.toVector().subtract(player.getLocation().toVector());
            double dist = toward.length();
            if (dist < 1.5) return;
            double speed = Math.min(cfg("grapple-max-speed", 2.6), 0.9 + dist * 0.12);
            Vector launch = toward.normalize().multiply(speed);
            launch.setY(launch.getY() + 0.35); // a little lift so you clear ledges
            Safe.vel(player, launch);
            noFallUntil.put(player.getUniqueId(), now + 5000);

            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.7f);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.6f, 1.4f);
            Vector step = toward.clone().normalize().multiply(0.8);
            Location point = player.getEyeLocation();
            for (int i = 0; i < Math.min(40, dist / 0.8); i++) {
                point.add(step);
                player.getWorld().spawnParticle(Particle.CRIT, point, 1, 0, 0, 0, 0);
            }
        }

        /** No fall damage from a grapple landing (for a few seconds after launching). */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onFall(EntityDamageEvent event) {
            if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
            Long until = noFallUntil.remove(player.getUniqueId());
            if (until != null && System.currentTimeMillis() <= until) event.setCancelled(true);
        }

        // ---------- Queen's Silk ----------

        @EventHandler
        public void onSilkUse(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
            if (!QueenSilkItem.isSilk(plugin, event.getItem())) return;
            event.setUseInteractedBlock(Result.DENY); // never places it as string
            event.setUseItemInHand(Result.DENY);

            Player player = event.getPlayer();
            long now = System.currentTimeMillis();
            long ready = silkCooldown.getOrDefault(player.getUniqueId(), 0L);
            if (now < ready) {
                player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                        ChatColor.RED + "Queen's Silk: " + ((ready - now + 999) / 1000) + "s"));
                return;
            }
            silkCooldown.put(player.getUniqueId(), now + (long) (cfg("silk-cooldown-seconds", 8) * 1000));

            Snowball glob = player.launchProjectile(Snowball.class);
            glob.setItem(new ItemStack(Material.COBWEB));
            glob.addScoreboardTag(SILK_TAG);
            Safe.vel(glob, player.getEyeLocation().getDirection().multiply(1.8));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 1.6f);
        }

        /**
         * Hitting a mob or player: webbed + heavy Slowness. Done through the damage
         * event so it respects no-PvP zones (WorldGuard cancels it there).
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSilkHitEntity(EntityDamageByEntityEvent event) {
            if (!event.getDamager().getScoreboardTags().contains(SILK_TAG)) return;
            if (!(event.getEntity() instanceof LivingEntity victim)) return;
            int seconds = (int) cfg("silk-web-seconds", 3);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 2));
            placeWeb(victim.getLocation().getBlock(), seconds);
            victim.getWorld().spawnParticle(Particle.ITEM, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.05,
                    new ItemStack(Material.COBWEB));
        }

        /** Hitting a block: a web on that side of it. */
        @EventHandler(ignoreCancelled = true)
        public void onSilkHitBlock(ProjectileHitEvent event) {
            if (!event.getEntity().getScoreboardTags().contains(SILK_TAG)) return;
            if (event.getHitBlock() == null || event.getHitBlockFace() == null) return;
            placeWeb(event.getHitBlock().getRelative(event.getHitBlockFace()), (int) cfg("silk-web-seconds", 3));
        }

        /** Temporary web: only into empty air, removed again after a few seconds. */
        private void placeWeb(Block block, int seconds) {
            if (!block.getType().isAir()) return;
            block.setType(Material.COBWEB);
            webs.add(block);
            Bukkit.getScheduler().runTaskLater(plugin, () -> clearWeb(block), seconds * 20L);
        }

        private void clearWeb(Block block) {
            if (webs.remove(block) && block.getType() == Material.COBWEB) block.setType(Material.AIR);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            noFallUntil.remove(event.getPlayer().getUniqueId());
        }

        void clearAllWebs() {
            new ArrayList<>(webs).forEach(this::clearWeb);
        }
    }

    // ===================== BOOTS: HERMES / ROCKET / SPECTRE =====================

    /**
     * Real armor (boots slot). Worn they use the dyed-leather boots model; the inventory
     * icon is custom art. Unbreakable. Hermes: +3 armor, +15% speed. Rocket: +2 armor,
     * rocket flight. Spectre (Hermes + Rocket in an anvil): +3 armor, +15% speed, flight.
     */
    static class BootsItems {

        static ItemStack hermes(FaultlineItems plugin) {
            return make(plugin, plugin.getHermesBootsKey(), "hermes_boots", Color.fromRGB(236, 228, 205),
                    ChatColor.WHITE + "" + ChatColor.BOLD + "Hermes Boots", 3, 0.15,
                    List.of(ChatColor.GREEN + "+15% movement speed"));
        }

        static ItemStack rocket(FaultlineItems plugin) {
            return make(plugin, plugin.getRocketBootsKey(), "rocket_boots", Color.fromRGB(185, 40, 40),
                    ChatColor.RED + "" + ChatColor.BOLD + "Rocket Boots", 2, 0,
                    List.of(ChatColor.GREEN + "Double-tap jump to fly (hold to keep going)",
                            ChatColor.GRAY + "7 short bursts, recharged when you land"));
        }

        static ItemStack spectre(FaultlineItems plugin) {
            return make(plugin, plugin.getSpectreBootsKey(), "spectre_boots", Color.fromRGB(185, 225, 240),
                    ChatColor.AQUA + "" + ChatColor.BOLD + "Spectre Boots", 3, 0.15,
                    List.of(ChatColor.GREEN + "+15% movement speed",
                            ChatColor.GREEN + "Double-tap jump to fly (hold to keep going)",
                            ChatColor.GRAY + "7 short bursts, recharged when you land"));
        }

        private static ItemStack make(FaultlineItems plugin, NamespacedKey key, String model, Color color, String name,
                                      double armor, double speed, List<String> lore) {
            ItemStack item = new ItemStack(Material.LEATHER_BOOTS);
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof LeatherArmorMeta leather) leather.setColor(color);
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.setItemModel(new NamespacedKey("faultline", model)); // texture from FaultlineItemTextures
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
            meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, model + "_armor"),
                    armor, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.FEET));
            if (speed > 0) {
                meta.addAttributeModifier(Attribute.MOVEMENT_SPEED, new AttributeModifier(new NamespacedKey(plugin, model + "_speed"),
                        speed, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.FEET));
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return item;
        }

        static boolean is(ItemStack item, NamespacedKey key) {
            if (item == null || item.getType() != Material.LEATHER_BOOTS || !item.hasItemMeta()) return false;
            Byte tag = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
            return tag != null && tag == (byte) 1;
        }
    }

    /**
     * Rocket flight (Rocket and Spectre Boots), like Terraria: in the air, holding jump
     * burns flight in 7 short charges. Letting go of jump mid-charge throws away the
     * rest of that charge. Everything recharges when you touch the ground or climb.
     * (Holding/releasing jump is read from the player's input, available since 1.21.3.)
     */
    static class RocketBootsManager implements Listener {

        private final FaultlineItems plugin;
        private final Map<UUID, Boolean> jumpHeld = new HashMap<>();
        private final Map<UUID, int[]> state = new HashMap<>(); // [charges left, ticks left in this charge]
        // Players who've used rocket flight and haven't landed yet (the mace / armor-swap lock)
        private final Set<UUID> rocketAirborne = new HashSet<>();
        private int ticks;

        RocketBootsManager(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        /** Players whose rockets are armed: they pressed jump again while already in the air. */
        private final Set<UUID> armed = new HashSet<>();

        /**
         * DOUBLE-TAP to fly: the first jump press (on the ground) is a normal jump; pressing
         * jump again while airborne arms the rockets. Holding a normal jump no longer launches you.
         */
        @EventHandler
        public void onInput(PlayerInputEvent event) {
            Player p = event.getPlayer();
            boolean now = event.getInput().isJump();
            boolean before = jumpHeld.getOrDefault(p.getUniqueId(), false);
            if (now && !before && !p.isOnGround() && !p.isClimbing() && !p.isInWater()) armed.add(p.getUniqueId());
            jumpHeld.put(p.getUniqueId(), now);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            jumpHeld.remove(event.getPlayer().getUniqueId());
            armed.remove(event.getPlayer().getUniqueId());
            state.remove(event.getPlayer().getUniqueId());
            rocketAirborne.remove(event.getPlayer().getUniqueId());
        }

        // ---------- anti-exploit: rocket flight + mace ----------
        //
        // A mace smash scales with fall height, and rocket flight gets you very high. After
        // using rocket flight, until you land: melee only works with swords and axes (no mace
        // smashes), and you can't take boots off or put them on (no mid-air swap tricks).

        private boolean locked(Player p) {
            if (rocketAirborne.contains(p.getUniqueId())) return true;
            ItemStack boots = p.getInventory().getBoots();
            boolean rockets = BootsItems.is(boots, plugin.getRocketBootsKey()) || BootsItems.is(boots, plugin.getSpectreBootsKey());
            return rockets && !p.isOnGround() && !p.isInWater() && !p.isClimbing();
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onRocketMelee(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player p) || !locked(p)) return; // bows and other projectiles still work
            String weapon = p.getInventory().getItemInMainHand().getType().name();
            if (weapon.endsWith("_SWORD") || weapon.endsWith("_AXE")) return;
            event.setCancelled(true);
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                    ChatColor.RED + "While rocket-flying you can only hit with swords or axes!"));
        }

        @EventHandler(ignoreCancelled = true)
        public void onRocketArmorClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player p) || !rocketAirborne.contains(p.getUniqueId())) return;
            boolean armorSlot = event.getSlotType() == InventoryType.SlotType.ARMOR;
            ItemStack moving = event.getCurrentItem();
            boolean shiftingArmor = event.isShiftClick() && moving != null && isWearable(moving.getType());
            if (armorSlot || shiftingArmor) {
                event.setCancelled(true);
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(ChatColor.RED + "You can't change armor mid-flight. Land first!"));
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onRocketArmorDrag(InventoryDragEvent event) {
            if (!(event.getWhoClicked() instanceof Player p) || !rocketAirborne.contains(p.getUniqueId())) return;
            // armor slots in the player's own inventory view are raw slots 5-8
            if (event.getView().getTopInventory().getType() == InventoryType.CRAFTING
                    && event.getRawSlots().stream().anyMatch(s -> s >= 5 && s <= 8)) event.setCancelled(true);
        }

        @EventHandler
        public void onRocketArmorEquip(PlayerInteractEvent event) {
            Player p = event.getPlayer();
            if (!rocketAirborne.contains(p.getUniqueId()) || event.getItem() == null || !isWearable(event.getItem().getType())) return;
            event.setUseItemInHand(Result.DENY); // no right-click-to-wear mid-flight
        }

        private static boolean isWearable(Material m) {
            String n = m.name();
            return n.endsWith("_BOOTS") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || m == Material.ELYTRA;
        }

        /** Every tick. */
        void tick() {
            ticks++;
            int charges = plugin.getConfig().getInt("boots.rocket-charges", 7);
            int chargeTicks = plugin.getConfig().getInt("boots.rocket-charge-ticks", 3); // 0.17s is about 3.4 ticks
            double lift = plugin.getConfig().getDouble("boots.rocket-lift", 0.42);
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                ItemStack boots = player.getInventory().getBoots();
                // A REAL landing (ground, climbing, water) is the only thing that recharges flight
                // and ends the anti-exploit lock. Gliding on an Elytra used to count as landing, so
                // rocket up -> glide a moment -> thrust again = infinite height, and the armor lock
                // switched off mid-air. Creative flight still resets (it's not survival).
                boolean landed = player.isOnGround() || player.isClimbing() || player.isInWater()
                        || (player.isFlying() && player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE);
                int[] st = state.computeIfAbsent(id, k -> new int[]{charges, 0});
                if (landed) {
                    st[0] = charges;
                    st[1] = 0;
                    rocketAirborne.remove(id);
                    armed.remove(id); // landing disarms: double-tap again next time
                }
                // Charges are remembered even with the boots off, so taking them off and back
                // on mid-air no longer hands out a fresh set.
                if (!BootsItems.is(boots, plugin.getRocketBootsKey()) && !BootsItems.is(boots, plugin.getSpectreBootsKey())) continue;
                if (landed || player.isGliding() || player.isFlying()) continue;
                if (!armed.contains(id)) continue; // not double-tapped yet
                if (!jumpHeld.getOrDefault(id, false)) {
                    st[1] = 0; // let go of jump: the rest of this charge is gone
                    continue;
                }
                if (st[1] <= 0) {
                    if (st[0] <= 0) continue; // out of charges until landing
                    st[0]--;
                    st[1] = chargeTicks;
                }
                st[1]--;
                rocketAirborne.add(id);
                Vector v = player.getVelocity();
                v.setY(Math.max(v.getY(), lift));
                Safe.vel(player, v);
                player.setFallDistance(0);
                Location feet = player.getLocation();
                player.getWorld().spawnParticle(Particle.FLAME, feet, 4, 0.15, 0.02, 0.15, 0.01);
                player.getWorld().spawnParticle(Particle.SMOKE, feet, 3, 0.15, 0.02, 0.15, 0.01);
                if (ticks % 2 == 0) player.getWorld().playSound(feet, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.35f, 1.6f);
            }
        }
    }

    /** Hermes Boots + Rocket Boots in an anvil (either order) = Spectre Boots. Also the Armorer trades. */
    static class BootsListener implements Listener {

        private final FaultlineItems plugin;

        BootsListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onAnvil(PrepareAnvilEvent event) {
            ItemStack a = event.getInventory().getItem(0);
            ItemStack b = event.getInventory().getItem(1);
            boolean combo = (BootsItems.is(a, plugin.getHermesBootsKey()) && BootsItems.is(b, plugin.getRocketBootsKey()))
                    || (BootsItems.is(a, plugin.getRocketBootsKey()) && BootsItems.is(b, plugin.getHermesBootsKey()));
            if (!combo) return;
            event.setResult(BootsItems.spectre(plugin));
            event.getView().setRepairCost(plugin.getConfig().getInt("boots.spectre-anvil-levels", 10));
        }

        /** Armorers sell Hermes Boots (45 Emeralds + Green Dye) and Rocket Boots (60 Emeralds + a Harpy Ring). */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onTrade(InventoryOpenEvent event) {
            if (!(event.getInventory() instanceof MerchantInventory inv) || !(inv.getMerchant() instanceof Villager villager)) return;
            if (!Villager.Profession.ARMORER.equals(villager.getProfession())) return;
            List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
            boolean changed = false;
            if (recipes.stream().noneMatch(r -> BootsItems.is(r.getResult(), plugin.getHermesBootsKey()))) {
                MerchantRecipe r = new MerchantRecipe(BootsItems.hermes(plugin), 0, 3, true, 15, 0f);
                r.addIngredient(new ItemStack(Material.EMERALD, plugin.getConfig().getInt("boots.hermes-trade-emeralds", 45)));
                r.addIngredient(new ItemStack(Material.GREEN_DYE, 1));
                recipes.add(r);
                changed = true;
            }
            if (recipes.stream().noneMatch(r -> BootsItems.is(r.getResult(), plugin.getRocketBootsKey()))) {
                MerchantRecipe r = new MerchantRecipe(BootsItems.rocket(plugin), 0, 3, true, 20, 0f);
                r.addIngredient(new ItemStack(Material.EMERALD, plugin.getConfig().getInt("boots.rocket-trade-emeralds", 60)));
                r.addIngredient(NewAccessoryItems.harpyRing(plugin));
                recipes.add(r);
                changed = true;
            }
            if (changed) villager.setRecipes(recipes);
        }
    }

    // ===================== ITEM CATALOG / GIVE COMMANDS / ADMIN MENU =====================

    /** Every custom item, by the name used in commands and in /itemsmenu. */
    static class ItemCatalog {

        static final Map<String, Function<FaultlineItems, ItemStack>> ACCESSORY_TYPES = new LinkedHashMap<>();
        // Stage names are words, not "1"/"2", so they can't be confused with an amount.
        static final Map<String, Function<FaultlineItems, ItemStack>> DARKNESS_TYPES = new LinkedHashMap<>();

        static {
            ACCESSORY_TYPES.put("claws", ClimbingClawsItem::create);
            ACCESSORY_TYPES.put("bezoar", BezoarItem::create);
            ACCESSORY_TYPES.put("discount", DiscountCardItem::create);
            ACCESSORY_TYPES.put("jelly", LifeJellyItem::create);
            ACCESSORY_TYPES.put("shield", ShieldOfTheOceanItem::create);
            ACCESSORY_TYPES.put("lavacharm", NewAccessoryItems::lavaCharm);
            ACCESSORY_TYPES.put("blackbelt", NewAccessoryItems::blackBelt);
            ACCESSORY_TYPES.put("moonstone", NewAccessoryItems::moonStone);
            ACCESSORY_TYPES.put("ranger", NewAccessoryItems::rangerEmblem);
            ACCESSORY_TYPES.put("scarf", NewAccessoryItems::bloodyWolfScarf);
            ACCESSORY_TYPES.put("frostflare", NewAccessoryItems::frostFlare);
            ACCESSORY_TYPES.put("spelunker", NewAccessoryItems::spelunkerAmulet);
            ACCESSORY_TYPES.put("harpy", NewAccessoryItems::harpyRing);
            ACCESSORY_TYPES.put("ankh", NewAccessoryItems::ankhShield);
            ACCESSORY_TYPES.put("clefthorn", NewAccessoryItems::cleftHorn);
            ACCESSORY_TYPES.put("weirdclock", NewAccessoryItems::weirdClock);
            for (ImmunityCharms.Charm c : ImmunityCharms.Charm.values()) ACCESSORY_TYPES.put(c.command, p -> ImmunityCharms.item(p, c));
            for (ImmunityCharms.Combo c : ImmunityCharms.Combo.values()) ACCESSORY_TYPES.put(c.command, p -> ImmunityCharms.combo(p, c));
            ACCESSORY_TYPES.put("ankhpiece", ImmunityCharms::ankhPiece); // the Ankh Shield's two boss pieces (given by FaultlineBosses)
            ACCESSORY_TYPES.put("cross", ImmunityCharms::cross);
            // diving gear and the Kraken's counters (their objects are made in onEnable, so they're looked up when given)
            ACCESSORY_TYPES.put("divinghelmet", pl -> pl.getDivingGear().item(DivingGear.Piece.HELMET));
            ACCESSORY_TYPES.put("flipper", pl -> pl.getDivingGear().item(DivingGear.Piece.FLIPPER));
            ACCESSORY_TYPES.put("divinggear", pl -> pl.getDivingGear().item(DivingGear.Piece.GEAR));
            ACCESSORY_TYPES.put("depthscharm", pl -> pl.getDivingGear().item(DivingGear.Piece.DEPTHS));
            ACCESSORY_TYPES.put("abyssalgear", pl -> pl.getDivingGear().item(DivingGear.Piece.ABYSSAL));
            ACCESSORY_TYPES.put("abyssalsuit", pl -> pl.getDivingGear().item(DivingGear.Piece.SUIT));
            ACCESSORY_TYPES.put("corallung", pl -> pl.getKrakenGear().counter(KrakenGear.Counter.CORAL_LUNG));
            ACCESSORY_TYPES.put("glowgland", pl -> pl.getKrakenGear().counter(KrakenGear.Counter.GLOW_GLAND));
            ACCESSORY_TYPES.put("eelskin", pl -> pl.getKrakenGear().counter(KrakenGear.Counter.EELSKIN_WRAP));
            for (JacobGear.Piece jp : JacobGear.Piece.values()) ACCESSORY_TYPES.put(jp.id.replace("_", ""), pl -> pl.getJacobGear().item(jp));
            ACCESSORY_TYPES.put("lostmirror", pl -> pl.getMirrorGear().item());

            DARKNESS_TYPES.put("final", DarknessPotionItem::createFinal);
            DARKNESS_TYPES.put("stage1", DarknessPotionItem::createStage1);
            DARKNESS_TYPES.put("stage2", DarknessPotionItem::createStage2);
        }

        /** Order of items in /itemsmenu. */
        static final List<Function<FaultlineItems, ItemStack>> MENU = List.of(
                MagicMirrorItem::create,
                CloudPotionItem::create,
                DarknessPotionItem::createFinal,
                DarknessPotionItem::createStage1,
                DarknessPotionItem::createStage2,
                GoodieBagItem::create,
                MythicGoodieBagItem::create,
                GoldenRingItem::create,
                ClimbingClawsItem::create,
                BezoarItem::create,
                DiscountCardItem::create,
                LifeJellyItem::create,
                ShieldOfTheOceanItem::create,
                NewAccessoryItems::lavaCharm,
                NewAccessoryItems::blackBelt,
                NewAccessoryItems::moonStone,
                NewAccessoryItems::rangerEmblem,
                NewAccessoryItems::bloodyWolfScarf,
                NewAccessoryItems::frostFlare,
                NewAccessoryItems::spelunkerAmulet,
                NewAccessoryItems::harpyRing,
                NewAccessoryItems::ankhShield,
                NewAccessoryItems::cleftHorn,
                NewAccessoryItems::weirdClock,
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.GUARDIAN_SCALE),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.GINGER_ROOT),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.BLINDFOLD),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.SALT_RATION),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.VITAMINS),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.SOUL_WARD),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.GRAVITY_STONE),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.FOUR_LEAF_CLOVER),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.NIGHT_LENS),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.WIND_WARD),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.SILK_WARD),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.SLIME_WARD),
                p -> ImmunityCharms.item(p, ImmunityCharms.Charm.PEST_CHARM),
                p -> ImmunityCharms.combo(p, ImmunityCharms.Combo.NIGHTWATCH),
                p -> ImmunityCharms.combo(p, ImmunityCharms.Combo.WAYFARER),
                p -> ImmunityCharms.combo(p, ImmunityCharms.Combo.FORTUNE),
                ImmunityCharms::ankhPiece,
                ImmunityCharms::cross,
                BootsItems::hermes,
                BootsItems::rocket,
                BootsItems::spectre,
                NecromancerStaffItem::create,
                IronStickItem::create,
                VampireEggItem::create,
                FrostWraithEggItem::create,
                SpiderStaffItem::create,
                QueenSpiderEggItem::create,
                GrapplingHookItem::create,
                QueenSilkItem::create,
                // diving gear (Calamity-style)
                pl -> pl.getDivingGear().item(DivingGear.Piece.HELMET),
                pl -> pl.getDivingGear().item(DivingGear.Piece.FLIPPER),
                pl -> pl.getDivingGear().item(DivingGear.Piece.GEAR),
                pl -> pl.getDivingGear().item(DivingGear.Piece.DEPTHS),
                pl -> pl.getDivingGear().item(DivingGear.Piece.ABYSSAL),
                pl -> pl.getDivingGear().item(DivingGear.Piece.SUIT),
                // the Kraken: his summon item and the three counters
                pl -> pl.getKrakenGear().worm(),
                pl -> pl.getKrakenGear().counter(KrakenGear.Counter.CORAL_LUNG),
                pl -> pl.getKrakenGear().counter(KrakenGear.Counter.GLOW_GLAND),
                pl -> pl.getKrakenGear().counter(KrakenGear.Counter.EELSKIN_WRAP),
                // Diamond Jacob's accessories
                pl -> pl.getJacobGear().item(JacobGear.Piece.QUIVER),
                pl -> pl.getJacobGear().item(JacobGear.Piece.ANCHOR),
                pl -> pl.getJacobGear().item(JacobGear.Piece.BANNER),
                pl -> pl.getJacobGear().item(JacobGear.Piece.EMBER),
                pl -> pl.getJacobGear().item(JacobGear.Piece.HEART),
                // the Lost Explorer's drop
                pl -> pl.getMirrorGear().item());

        static Map<String, Function<FaultlineItems, ItemStack>> single(Function<FaultlineItems, ItemStack> factory) {
            return Map.of("", factory);
        }

        /**
         * Gives `amount` of an item, split into proper stacks (1 per slot for
         * max-stack-1 items). Anything that doesn't fit drops at their feet.
         */
        static void give(FaultlineItems plugin, Player target, Function<FaultlineItems, ItemStack> factory, int amount) {
            int remaining = amount;
            while (remaining > 0) {
                ItemStack item = factory.apply(plugin);
                int count = Math.min(remaining, item.getMaxStackSize());
                item.setAmount(count);
                remaining -= count;
                target.getInventory().addItem(item).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            }
        }

        /** Drops the items at a spot, bursting outward, pick-up-able only by `owner`. */
        static void dropLocked(FaultlineItems plugin, Player owner, Function<FaultlineItems, ItemStack> factory, int amount, Location at) {
            java.util.Random r = new java.util.Random();
            int remaining = amount;
            while (remaining > 0) {
                ItemStack item = factory.apply(plugin);
                int count = Math.min(remaining, item.getMaxStackSize());
                item.setAmount(count);
                remaining -= count;
                Item drop = at.getWorld().dropItem(at, item);
                drop.setOwner(owner.getUniqueId());
                Safe.vel(drop, new Vector(r.nextGaussian() * 0.15, 0.3 + r.nextDouble() * 0.2, r.nextGaussian() * 0.15));
            }
        }

        static String nameOf(FaultlineItems plugin, Function<FaultlineItems, ItemStack> factory) {
            ItemStack item = factory.apply(plugin);
            return item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name();
        }
    }

    /**
     * One give command used for every item. Usage: /<cmd> [type] [amount] [player]
     * Amount and player can go in either order. A single-item command has no type.
     */
    static class GiveItemCommand implements CommandExecutor {

        private static final int MAX_AMOUNT = 64 * 36; // a full inventory of stacks

        private final FaultlineItems plugin;
        private final String permission;
        private final String usage;
        private final Map<String, Function<FaultlineItems, ItemStack>> types;
        /** Type used when none is given; null means a type is required. Ignored for single-item commands. */
        private final String defaultType;

        GiveItemCommand(FaultlineItems plugin, String permission, String usage, Map<String, Function<FaultlineItems, ItemStack>> types) {
            this(plugin, permission, usage, types, "");
        }

        GiveItemCommand(FaultlineItems plugin, String permission, String usage,
                        Map<String, Function<FaultlineItems, ItemStack>> types, String defaultType) {
            this.plugin = plugin;
            this.permission = permission;
            this.usage = usage;
            this.types = types;
            this.defaultType = defaultType;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission(permission)) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            int index = 0;
            String type = defaultType;
            if (!types.containsKey("")) { // multi-type command (giveaccessory / givedarkness)
                if (args.length > 0 && types.containsKey(args[0].toLowerCase())) {
                    type = args[0].toLowerCase();
                    index = 1;
                } else if (defaultType == null) {
                    sender.sendMessage(ChatColor.RED + "Pick a type: " + String.join(", ", types.keySet()));
                    sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage);
                    return false;
                }
            }
            Function<FaultlineItems, ItemStack> factory = types.get(type);

            Player target = null;
            int amount = 1;
            Location dropAt = null;
            for (int i = index; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("at:")) { // at:world,x,y,z -> drop there, locked to the player (boss loot)
                    String[] p = arg.substring(3).split(",");
                    World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
                    if (w == null) return false;
                    try {
                        dropAt = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    continue;
                }
                // Online player names are checked FIRST, so a player whose name is
                // all numbers (e.g. "12345") isn't mistaken for an amount.
                Player online = Bukkit.getPlayerExact(arg);
                if (online != null && target == null) {
                    target = online;
                } else if (arg.matches("\\d{1,6}")) {
                    amount = Integer.parseInt(arg);
                } else {
                    sender.sendMessage(ChatColor.RED + "'" + arg + "' isn't an online player or a number.");
                    return false; // false tells LimboBlackMarket a reward give failed
                }
            }

            if (target == null) {
                if (sender instanceof Player self) {
                    target = self;
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage + " (the console must name a player)");
                    return false;
                }
            }

            if (amount < 1) amount = 1;
            if (amount > MAX_AMOUNT) {
                sender.sendMessage(ChatColor.GRAY + "Capped at " + MAX_AMOUNT + " (one full inventory).");
                amount = MAX_AMOUNT;
            }

            if (dropAt != null) ItemCatalog.dropLocked(plugin, target, factory, amount, dropAt);
            else ItemCatalog.give(plugin, target, factory, amount);
            sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x "
                    + ChatColor.RESET + ItemCatalog.nameOf(plugin, factory) + ChatColor.GREEN + ".");
            return true;
        }
    }

    /**
     * One thing in /itemsmenu. Faultline Items' own items are made directly; items
     * from the other Faultline plugins show a look-alike icon, and clicking asks
     * that plugin (by console command) to hand over the real item.
     */
    static class MenuEntry {
        final ItemStack icon;
        final Function<FaultlineItems, ItemStack> factory; // own items
        final String command;                              // other plugins: {amount} and {player} get filled in
        final String pluginName;

        MenuEntry(ItemStack icon, Function<FaultlineItems, ItemStack> factory, String command, String pluginName) {
            this.icon = icon;
            this.factory = factory;
            this.command = command;
            this.pluginName = pluginName;
        }

        static List<MenuEntry> all(FaultlineItems plugin) {
            List<MenuEntry> list = new ArrayList<>();
            for (Function<FaultlineItems, ItemStack> f : ItemCatalog.MENU) list.add(new MenuEntry(f.apply(plugin), f, null, null));

            if (enabled("LimboBlackMarket")) {
                list.add(external(icon(Material.BOOK, "quest_book", ChatColor.GOLD + "" + ChatColor.BOLD + "Quest Book", "LimboBlackMarket"),
                        "givequestbook {amount} {player}", "LimboBlackMarket"));
            }
            if (enabled("FaultlineIndex")) {
                list.add(external(icon(Material.BOOK, "faultline_index", ChatColor.GOLD + "" + ChatColor.BOLD + "Faultline Index", "FaultlineIndex"),
                        "index give {player}", "FaultlineIndex"));
            }
            if (enabled("FaultlineShips")) {
                String[][] ships = {{"dinghy", "" + ChatColor.GREEN, "Dinghy"}, {"sloop", "" + ChatColor.WHITE, "Sloop"}, {"brigantine", "" + ChatColor.GOLD, "Brigantine"},
                        {"galleon", "" + ChatColor.AQUA, "Galleon"}, {"pirate", "" + ChatColor.DARK_GRAY, "Pirate Ship"}};
                for (String[] sh : ships) {
                    String title = sh[2];
                    list.add(external(icon(Material.GLOBE_BANNER_PATTERN, null, sh[1] + ChatColor.BOLD + title + " Blueprint", "FaultlineShips"),
                            "ship give " + sh[0] + " {amount} {player}", "FaultlineShips"));
                }
                for (String[] sh : ships) { // admin: ships that are already built
                    list.add(external(icon(Material.GLOBE_BANNER_PATTERN, null, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + sh[2] + ChatColor.GRAY + " (built)", "FaultlineShips"),
                            "ship give built_" + sh[0] + " {amount} {player}", "FaultlineShips"));
                }
                list.add(external(icon(Material.MACE, null, ChatColor.GOLD + "" + ChatColor.BOLD + "Shipwright's Hammer", "FaultlineShips"),
                        "ship give hammer {amount} {player}", "FaultlineShips"));
                list.add(external(icon(Material.PAPER, "ship_cannon", ChatColor.GRAY + "" + ChatColor.BOLD + "Ship Cannon", "FaultlineShips"),
                        "ship give cannon {amount} {player}", "FaultlineShips"));
                list.add(external(icon(Material.PAPER, "cannonball", ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Cannonball", "FaultlineShips"),
                        "ship give cannonball {amount} {player}", "FaultlineShips"));
                list.add(external(icon(Material.GOAT_HORN, null, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Cursed Pirate Horn", "FaultlineShips"),
                        "ship pirates horn {amount} {player}", "FaultlineShips"));
                list.add(external(icon(Material.BELL, null, ChatColor.AQUA + "" + ChatColor.BOLD + "Phantom Bell " + ChatColor.GRAY + "(calls the Ghost Ship)", "FaultlineShips"),
                        "ship pirates bell {amount} {player}", "FaultlineShips"));
                list.add(external(icon(Material.SOUL_LANTERN, null, ChatColor.AQUA + "" + ChatColor.BOLD + "The Wailing Mary " + ChatColor.GRAY + "(summons the Ghost Ship near you, at sea)", "FaultlineShips"),
                        "ship pirates ghost {player}", "FaultlineShips"));
                Object[][] pirates = { // the Pirate Invasion's bosses first, then its mobs
                        {"son", Material.SKELETON_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "The Captain's Son (Boss)"},
                        {"commander", Material.SKELETON_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "The Skeleton Commander (Boss)"},
                        {"captain", Material.WITHER_SKELETON_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "The Skeleton Captain (Boss)"},
                        {"deckhand", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Deckhand"},
                        {"musketeer", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Musketeer"},
                        {"gunner", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Gunner"},
                        {"boarder", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Boarder"},
                        {"powder_monkey", Material.SKELETON_SPAWN_EGG, ChatColor.RED + "Powder Monkey"},
                        {"corsair", Material.DROWNED_SPAWN_EGG, ChatColor.DARK_AQUA + "Drowned Corsair"},
                        {"bosun", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Bosun"},
                        {"gull", Material.PHANTOM_SPAWN_EGG, ChatColor.GRAY + "Ghost Gull"},
                        {"shark", Material.GUARDIAN_SPAWN_EGG, ChatColor.GRAY + "Bone Shark"},
                        {"navigator", Material.STRAY_SPAWN_EGG, ChatColor.DARK_GREEN + "Skeleton Navigator"},
                        {"wraith", Material.WITHER_SKELETON_SPAWN_EGG, ChatColor.AQUA + "Ghost Pirate"}};
                for (Object[] egg : pirates) {
                    list.add(external(icon((Material) egg[1], null, egg[2] + " Spawn Egg", "FaultlineShips"),
                            "ship pirates egg " + egg[0] + " {amount} {player}", "FaultlineShips"));
                }
            }
            // the wild update: gear, discs, eggs
            list.add(external(icon(Material.PAPER, "mining_helmet", ChatColor.YELLOW + "" + ChatColor.BOLD + "Mining Helmet", "FaultlineItems"), "fgear mining_helmet {amount} {player}", "FaultlineItems"));
            list.add(external(icon(Material.PAPER, "lantern_of_souls", ChatColor.AQUA + "" + ChatColor.BOLD + "Lantern of Souls", "FaultlineItems"), "fgear lantern_of_souls {amount} {player}", "FaultlineItems"));
            list.add(external(icon(Material.PAPER, "knight_helm", ChatColor.WHITE + "" + ChatColor.BOLD + "Knight's Helm", "FaultlineItems"), "fgear knight_helm {amount} {player}", "FaultlineItems"));
            list.add(external(icon(Material.PAPER, "ancient_gear_part", ChatColor.GREEN + "Ancient Gear Part", "FaultlineItems"), "fgear ancient_gear_part {amount} {player}", "FaultlineItems"));
            list.add(external(icon(Material.PAPER, "ancient_core", ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Ancient Core", "FaultlineItems"), "fgear ancient_core {amount} {player}", "FaultlineItems"));
            list.add(external(icon(Material.POLISHED_BLACKSTONE_BRICK_WALL, "museum_pedestal", ChatColor.GOLD + "" + ChatColor.BOLD + "Museum Pedestal", "FaultlineItems"), "fgear museum_pedestal {amount} {player}", "FaultlineItems"));
            for (var d : Gear.DISCS.entrySet())
                list.add(external(icon(Material.PAPER, "disc/disc_" + d.getKey(), ChatColor.AQUA + "Music Disc " + ChatColor.GRAY + "(" + d.getValue()[0] + ")", "FaultlineItems"), "fgear disc_" + d.getKey() + " {amount} {player}", "FaultlineItems"));
            // weather events: start one where the admin stands
            list.add(external(icon(Material.AMETHYST_SHARD, null, ChatColor.GREEN + "" + ChatColor.BOLD + "Event: Aurora " + ChatColor.GRAY + "(starts it, at night)", "FaultlineItems"), "fweather aurora {player}", "FaultlineItems"));
            list.add(external(icon(Material.SAND, null, ChatColor.GOLD + "" + ChatColor.BOLD + "Event: Sandstorm " + ChatColor.GRAY + "(starts it here)", "FaultlineItems"), "fweather sandstorm {player}", "FaultlineItems"));
            list.add(external(icon(Material.POWDER_SNOW_BUCKET, null, ChatColor.AQUA + "" + ChatColor.BOLD + "Event: Blizzard " + ChatColor.GRAY + "(starts it here)", "FaultlineItems"), "fweather blizzard {player}", "FaultlineItems"));
            list.add(external(icon(Material.BLACK_CONCRETE, null, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Event: Eclipse " + ChatColor.GRAY + "(the black sun)", "FaultlineItems"), "fweather eclipse {player}", "FaultlineItems"));
            list.add(external(icon(Material.WHEAT, null, ChatColor.YELLOW + "" + ChatColor.BOLD + "Event: Locust Swarm " + ChatColor.GRAY + "(at the farm near you)", "FaultlineItems"), "fweather locusts {player}", "FaultlineItems"));
            list.add(external(icon(Material.CHEST, null, ChatColor.GOLD + "" + ChatColor.BOLD + "Mimic " + ChatColor.GRAY + "(spawns one)", "FaultlineItems"), "fcreature mimic {player}", "FaultlineItems"));
            list.add(external(icon(Material.SKELETON_HORSE_SPAWN_EGG, null, ChatColor.WHITE + "" + ChatColor.BOLD + "Skeleton Knight Patrol " + ChatColor.GRAY + "(spawns one)", "FaultlineItems"), "fcreature knights {player}", "FaultlineItems"));
            if (enabled("FaultlineBosses")) {
                list.add(external(icon(Material.PAPER, "abyssal_lure", ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Abyssal Lure " + ChatColor.GRAY + "(the Leviathan)", "FaultlineBosses"), "leviathan item abyssal_lure {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "sandworm_drum", ChatColor.GOLD + "" + ChatColor.BOLD + "Sandworm Drum " + ChatColor.GRAY + "(the Sandworm King)", "FaultlineBosses"), "sandworm item sandworm_drum {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "lich_phylactery", ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Cursed Phylactery " + ChatColor.GRAY + "(the Lich)", "FaultlineBosses"), "lich item lich_phylactery {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "frozen_horn", ChatColor.AQUA + "" + ChatColor.BOLD + "Frozen Horn " + ChatColor.GRAY + "(the Frost Wyrm)", "FaultlineBosses"), "frostwyrm item frozen_horn {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.IRON_GOLEM_SPAWN_EGG, null, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "The Stone Golem " + ChatColor.GRAY + "(summons it)", "FaultlineBosses"), "stonegolem summon {world} {x} {y} {z}", "FaultlineBosses"));
                list.add(external(icon(Material.TRIDENT, "tidebreaker", ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Tidebreaker", "FaultlineBosses"), "leviathan item tidebreaker {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.DIAMOND_SWORD, "sandworm_fang", ChatColor.GOLD + "" + ChatColor.BOLD + "Sandworm Fang", "FaultlineBosses"), "sandworm item sandworm_fang {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.STICK, "lich_staff", ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Staff of the Lich", "FaultlineBosses"), "lich item lich_staff {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.DIAMOND_SWORD, "glacial_fang", ChatColor.AQUA + "" + ChatColor.BOLD + "Glacial Fang", "FaultlineBosses"), "frostwyrm item glacial_fang {amount} {player}", "FaultlineBosses"));
            }
            if (enabled("FaultlineCosmetics")) { // the seasonal currency (Candy at Halloween, Presents in Winter)
                list.add(external(icon(Material.PAPER, "season_candy", ChatColor.GOLD + "" + ChatColor.BOLD + "Candy " + ChatColor.GRAY + "(Halloween)", "FaultlineCosmetics"),
                        "cosmetic tokens {player} halloween {amount}", "FaultlineCosmetics"));
                list.add(external(icon(Material.PAPER, "season_present", ChatColor.AQUA + "" + ChatColor.BOLD + "Present " + ChatColor.GRAY + "(Winter)", "FaultlineCosmetics"),
                        "cosmetic tokens {player} winter {amount}", "FaultlineCosmetics"));
            }
            if (enabled("FaultlineBosses")) {
                list.add(external(icon(Material.ENDER_EYE, "suspicious_eye", ChatColor.DARK_RED + "" + ChatColor.BOLD + "Suspicious Eye", "FaultlineBosses"),
                        "demoneye give {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "captains_armband", ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Captain's Armband", "FaultlineBosses"),
                        "don armband {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.FIREWORK_STAR, "lorenzos_ball", ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Lorenzo's Ball", "FaultlineBosses"),
                        "don ball {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.FLINT, "talon", ChatColor.GOLD + "Talon", "FaultlineBosses"),
                        "dune item talon {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.HEART_OF_THE_SEA, "dune_idol", ChatColor.GOLD + "" + ChatColor.BOLD + "Dune Idol", "FaultlineBosses"),
                        "dune item idol {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.SILVERFISH_SPAWN_EGG, null, ChatColor.GOLD + "" + ChatColor.BOLD + "Dune Devourer Spawn Egg", "FaultlineBosses"),
                        "dune egg {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.POLAR_BEAR_SPAWN_EGG, null, ChatColor.AQUA + "" + ChatColor.BOLD + "Frostmaw Spawn Egg", "FaultlineBosses"),
                        "dune egg frost {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.SNOW_GOLEM_SPAWN_EGG, null, ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard Spawn Egg", "FaultlineBosses"),
                        "frostbeard egg {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.BLAZE_ROD, "twin_eye_staff", ChatColor.RED + "" + ChatColor.BOLD + "Twin Eye Staff", "FaultlineBosses"),
                        "demoneye item staff {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.SHIELD, null, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Shield of the Demon Eye", "FaultlineBosses"),
                        "demoneye item shield {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.ARROW, "unholy_arrow", ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Unholy Arrow", "FaultlineBosses"),
                        "demoneye item arrows {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.GOAT_HORN, "jacob_horn", ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond War Horn", "FaultlineBosses"),
                        "jacob item horn {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.NETHERITE_AXE, "jacob_hammer", ChatColor.AQUA + "" + ChatColor.BOLD + "Jacob's War Hammer", "FaultlineBosses"),
                        "jacob item hammer {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.NETHERITE_SWORD, "jacob_sword", ChatColor.GOLD + "" + ChatColor.BOLD + "Ember Blade", "FaultlineBosses"),
                        "jacob item sword {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "vendetta_contract", ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Vendetta Contract", "FaultlineBosses"),
                        "rocco item contract {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "vendetta_fist", ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Vendetta Fist", "FaultlineBosses"),
                        "rocco item fist {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.WITHER_SKELETON_SKULL, "explorer_head", ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer " + ChatColor.GRAY + "(go to his arena)", "FaultlineBosses"),
                        "explorer arena {player}", "FaultlineBosses"));
                list.add(external(icon(Material.NETHER_STAR, "boss_rush_sigil", ChatColor.RED + "" + ChatColor.BOLD + "Boss Rush Sigil", "FaultlineBosses"),
                        "bossrush sigil {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.PAPER, "warlord_challenge", ChatColor.GOLD + "" + ChatColor.BOLD + "Warlord's Challenge " + ChatColor.GRAY + "(Grimtusk)", "FaultlineBosses"),
                        "grimtusk item challenge {amount} {player}", "FaultlineBosses"));
                list.add(external(icon(Material.NETHERITE_AXE, "warlord_cleaver", ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk's Cleaver", "FaultlineBosses"),
                        "grimtusk item cleaver {amount} {player}", "FaultlineBosses"));
            }
            if (enabled("FaultlineRaids")) {
                String[] roman = {"I", "II", "III", "IV", "V"};
                for (int level = 1; level <= 5; level++) {
                    ItemStack omen = new ItemStack(Material.POTION);
                    if (omen.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                        pm.setColor(Color.fromRGB(40, 95, 30));
                        pm.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Omen " + roman[level - 1]);
                        pm.setLore(List.of(ChatColor.DARK_GRAY + "From FaultlineRaids"));
                        pm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                        pm.setItemModel(new NamespacedKey("faultline", "zombie_omen"));
                        omen.setItemMeta(pm);
                    }
                    list.add(external(omen, "zraid omen " + level + " {amount} {player}", "FaultlineRaids"));
                }
                for (int level = 1; level <= 5; level++) {
                    ItemStack skOmen = new ItemStack(Material.POTION);
                    if (skOmen.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                        pm.setColor(Color.fromRGB(226, 220, 204));
                        pm.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Skeleton Omen " + roman[level - 1]);
                        pm.setLore(List.of(ChatColor.DARK_GRAY + "From FaultlineRaids"));
                        pm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                        pm.setItemModel(new NamespacedKey("faultline", "skeleton_omen"));
                        skOmen.setItemMeta(pm);
                    }
                    list.add(external(skOmen, "zraid skeletonomen " + level + " {amount} {player}", "FaultlineRaids"));
                }
                Object[][] eggs = {
                        {"archer", Material.ZOMBIE_SPAWN_EGG, ChatColor.GREEN + "Zombie Archer"},
                        {"brute", Material.HUSK_SPAWN_EGG, ChatColor.DARK_GREEN + "Zombie Brute"},
                        {"bomber", Material.CREEPER_SPAWN_EGG, ChatColor.RED + "Bomber Zombie"},
                        {"baby_bomber", Material.CREEPER_SPAWN_EGG, ChatColor.RED + "Baby Bomber"},
                        {"bat", Material.BAT_SPAWN_EGG, ChatColor.GRAY + "Zombie Bat"},
                        {"flyer", Material.PHANTOM_SPAWN_EGG, ChatColor.AQUA + "Flying Zombie"},
                        {"witch", Material.WITCH_SPAWN_EGG, ChatColor.DARK_PURPLE + "Raid Witch"},
                        {"horseman", Material.ZOMBIE_HORSE_SPAWN_EGG, ChatColor.DARK_GREEN + "Zombie Horseman"},
                        {"mage", Material.EVOKER_SPAWN_EGG, ChatColor.GREEN + "Zombie Mage"},
                        {"captain", Material.ZOMBIE_VILLAGER_SPAWN_EGG, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Zombie Captain & Army"},
                        {"rotbeard", Material.DROWNED_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "Captain Rotbeard (Boss)"},
                        {"skeleton_army", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "" + ChatColor.BOLD + "Bone Commander & Army"},
                        {"skel_soldier", Material.SKELETON_SPAWN_EGG, ChatColor.WHITE + "Skeleton Soldier"},
                        {"frost_archer", Material.STRAY_SPAWN_EGG, ChatColor.AQUA + "Frost Archer"},
                        {"bog_archer", Material.BOGGED_SPAWN_EGG, ChatColor.DARK_GREEN + "Bog Archer"},
                        {"shieldbearer", Material.SKELETON_SPAWN_EGG, ChatColor.GRAY + "Shieldbearer"},
                        {"bone_rider", Material.SKELETON_HORSE_SPAWN_EGG, ChatColor.WHITE + "Bone Rider"},
                        {"wither_brute", Material.WITHER_SKELETON_SPAWN_EGG, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Wither Brute"},
                        {"piglin_grunt", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Grunt"},
                        {"piglin_crossbow", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Crossbowman"},
                        {"piglin_mage", Material.PIGLIN_SPAWN_EGG, ChatColor.RED + "Piglin Mage"},
                        {"piglin_summoner", Material.PIGLIN_SPAWN_EGG, ChatColor.DARK_PURPLE + "Piglin Summoner"},
                        {"piglin_balloon", Material.PIGLIN_SPAWN_EGG, ChatColor.RED + "Piglin Balloonist"},
                        {"hoglin_rider", Material.HOGLIN_SPAWN_EGG, ChatColor.GOLD + "Hoglin Rider"},
                        {"bulwark", Material.PIGLIN_BRUTE_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "The Bulwark (Mini Boss)"},
                        {"great_hog", Material.HOGLIN_SPAWN_EGG, ChatColor.GOLD + "" + ChatColor.BOLD + "The Great Hog (Boss)"},
                        {"piglin_sapper", Material.PIGLIN_SPAWN_EGG, ChatColor.RED + "Piglin Sapper"},
                        {"piglin_shieldbearer", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Shieldbearer"},
                        {"piglin_lobber", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Lobber"},
                        {"piglin_runt", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Runt"},
                        {"piglin_banner", Material.PIGLIN_SPAWN_EGG, ChatColor.GOLD + "Piglin Banner Bearer"},
                        {"piglin_medic", Material.PIGLIN_SPAWN_EGG, ChatColor.GREEN + "Piglin Medic"}};
                for (int level = 3; level <= 5; level++) { // the Piglin War Horn (starts a Piglin Raid)
                    list.add(external(icon(Material.GOAT_HORN, "piglin_war_horn", ChatColor.GOLD + "" + ChatColor.BOLD + "Piglin War Horn " + roman[level - 1], "FaultlineRaids"),
                            "zraid horn " + level + " {amount} {player}", "FaultlineRaids"));
                }
                for (Object[] egg : eggs) {
                    list.add(external(icon((Material) egg[1], null, egg[2] + " Spawn Egg", "FaultlineRaids"),
                            "zraid egg " + egg[0] + " {amount} {player}", "FaultlineRaids"));
                }
            }
            return list;
        }

        private static boolean enabled(String name) {
            Plugin other = Bukkit.getPluginManager().getPlugin(name);
            return other != null && other.isEnabled();
        }

        private static MenuEntry external(ItemStack icon, String command, String pluginName) {
            return new MenuEntry(icon, null, command, pluginName);
        }

        private static ItemStack icon(Material material, String model, String name, String from) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (model != null) meta.setItemModel(new NamespacedKey("faultline", model));
            meta.setDisplayName(name);
            meta.setLore(List.of(ChatColor.DARK_GRAY + "From " + from));
            item.setItemMeta(meta);
            return item;
        }

        void give(FaultlineItems plugin, Player player, boolean stack) {
            int amount = stack ? icon.getMaxStackSize() : 1;
            if (factory != null) {
                ItemCatalog.give(plugin, player, factory, amount);
                return;
            }
            Location ahead = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(4));
            String cmd = command.replace("{amount}", String.valueOf(amount)).replace("{player}", player.getName())
                    .replace("{world}", player.getWorld().getName()).replace("{x}", String.valueOf(ahead.getBlockX() + 0.5))
                    .replace("{y}", String.valueOf(player.getLocation().getBlockY())).replace("{z}", String.valueOf(ahead.getBlockZ() + 0.5));
            if (!enabled(pluginName) || !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                player.sendMessage(ChatColor.RED + "Couldn't get that item: " + pluginName + " isn't responding.");
            }
        }
    }

    static class ItemsMenuHolder implements InventoryHolder {

        static final int PER_PAGE = 45;
        static final int PREV = 45, INFO = 49, NEXT = 53;

        private Inventory inventory;
        private List<MenuEntry> entries;
        private int page;

        Inventory build(FaultlineItems plugin, int page) {
            this.entries = MenuEntry.all(plugin);
            int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
            this.page = Math.max(0, Math.min(page, pages - 1));
            inventory = Bukkit.createInventory(this, 54, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Faultline Items"
                    + ChatColor.DARK_GRAY + " (" + (this.page + 1) + "/" + pages + ")");

            for (int i = 0; i < PER_PAGE; i++) {
                int index = this.page * PER_PAGE + i;
                if (index < entries.size()) inventory.setItem(i, entries.get(index).icon);
            }
            ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
            for (int i = 45; i < 54; i++) inventory.setItem(i, filler);
            inventory.setItem(INFO, button(Material.OAK_SIGN, ChatColor.GOLD + "How to use",
                    List.of(ChatColor.GRAY + "Left-click: take 1", ChatColor.GRAY + "Shift-click: take a full stack",
                            ChatColor.DARK_GRAY + "" + entries.size() + " items from every Faultline plugin")));
            if (this.page > 0) inventory.setItem(PREV, button(Material.ARROW, ChatColor.YELLOW + "Previous page", List.of()));
            if (this.page < pages - 1) inventory.setItem(NEXT, button(Material.ARROW, ChatColor.YELLOW + "Next page", List.of()));
            return inventory;
        }

        private static ItemStack button(Material material, String name, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        MenuEntry entryAt(int slot) {
            int index = page * PER_PAGE + slot;
            return slot >= 0 && slot < PER_PAGE && index < entries.size() ? entries.get(index) : null;
        }

        int page() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static class ItemsMenuListener implements Listener {

        private final FaultlineItems plugin;

        ItemsMenuListener(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof ItemsMenuHolder holder)) return;
            event.setCancelled(true); // the menu is a catalog, nothing in it can be moved
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() != event.getInventory()) return;
            if (!player.hasPermission("items.menu")) return;

            int slot = event.getSlot();
            ItemStack clicked = event.getCurrentItem();
            if ((slot == ItemsMenuHolder.PREV || slot == ItemsMenuHolder.NEXT) && clicked != null && clicked.getType() == Material.ARROW) {
                int page = holder.page() + (slot == ItemsMenuHolder.NEXT ? 1 : -1);
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(new ItemsMenuHolder().build(plugin, page)));
                return;
            }
            MenuEntry entry = holder.entryAt(slot);
            if (entry == null) return;
            entry.give(plugin, player, event.isShiftClick());
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof ItemsMenuHolder) event.setCancelled(true);
        }
    }

    static class ItemsMenuCommand implements CommandExecutor {

        private final FaultlineItems plugin;

        ItemsMenuCommand(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can open the item menu.");
                return true;
            }
            if (!player.hasPermission("items.menu")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            player.openInventory(new ItemsMenuHolder().build(plugin, 0));
            return true;
        }
    }

    // ===================== MISC =====================

    static class ItemsReloadCommand implements CommandExecutor {

        private final FaultlineItems plugin;

        public ItemsReloadCommand(FaultlineItems plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("items.reload")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            plugin.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Faultline SMP Items config reloaded.");
            // the resource pack is only sent on join, so a changed url/hash/enabled used to need everyone to rejoin
            for (Player p : Bukkit.getOnlinePlayers()) {
                String status = plugin.getItemTextureManager().applyTextures(p);
                sender.sendMessage(ChatColor.GRAY + "Resource pack -> " + p.getName() + ": " + status);
            }
            return true;
        }
    }


    // ===================== OTHER =====================

}
