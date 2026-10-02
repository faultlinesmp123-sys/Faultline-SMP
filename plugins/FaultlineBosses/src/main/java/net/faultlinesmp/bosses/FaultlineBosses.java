package net.faultlinesmp.bosses;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.LinkedHashMap;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Arrays;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * THE DEMON EYE — inspired by Terraria's Eye of Cthulhu as reworked by Calamity's
 * Infernum mode. 1,000 health, 4 phases (at 80% / 35% / 15% health).
 *
 * Phase 1: Hover Charge, Charging Servants, Horizontal Blood Charge (drops Tooth Balls
 *          that burst into teeth after 5.5s).
 * Phase 2: turns into its mouth form. Adds Spin Dash and Tooth Vomit.
 * Phase 3: adds Blood Shots (slow homing globs). Tooth Vomit gets wider.
 * Phase 4: everything faster and denser.
 * Enrages if the fight runs into daytime.
 *
 * HOW IT'S BUILT: an invisible, AI-less Slime is the hitbox (so swords and arrows
 * work normally); an ItemDisplay shows the 3D model from the FaultlineBosses resource
 * pack and is moved/rotated/scaled every tick for the animations. Projectiles and
 * Servants are simulated here (cheap, fully controlled).
 *
 * SUMMON: the Suspicious Eye (0.05% from vanilla mobs only), right-click at night in
 * the Overworld. One Demon Eye at a time. Nobody can sleep while it's alive.
 * Anyone who fights and then dies is "down"; if everyone fighting is down at once,
 * the Demon Eye leaves. Winners (everyone who fought) get the rewards.
 */
public final class FaultlineBosses extends JavaPlugin implements Listener {

    /**
     * Every config load (startup, /freload) checks config.yml first. One mis-indented line makes Bukkit
     * ignore the WHOLE file and silently use the defaults (e.g. a pasted, indented section). A section name that belongs at the start of a line but got indented is moved back, the
     * original is kept as config.yml.broken-<time>, and the console says exactly what was fixed.
     */
    @Override
    public void reloadConfig() {
        try {
            repairConfigFile();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Couldn't check config.yml for formatting problems", e);
        }
        super.reloadConfig();
    }

    private void repairConfigFile() throws java.io.IOException {
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        String text = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        String problem = yamlProblem(text);
        if (problem == null) return;

        String defaults;
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null) return;
            defaults = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.List<String> changes = new java.util.ArrayList<>();
        String result = repairYaml(text, defaults, changes);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        java.io.File backup = new java.io.File(getDataFolder(), "config.yml.broken-" + stamp);
        if (!changes.isEmpty() && yamlProblem(result) == null) {
            java.nio.file.Files.copy(file.toPath(), backup.toPath());
            java.nio.file.Files.writeString(file.toPath(), result, java.nio.charset.StandardCharsets.UTF_8);
            getLogger().warning("config.yml had a formatting error, so it was FIXED automatically (original saved as " + backup.getName() + "):");
            for (String c : changes) getLogger().warning("  - " + c);
        } else {
            getLogger().severe("config.yml has a formatting error that couldn't be fixed automatically, so the plugin is using its"
                    + " DEFAULT settings until it's fixed: " + problem);
        }
    }

    /** Moves top-level section names (per the default config) back to column 0 and replaces tabs; lists what changed. */
    static String repairYaml(String text, String defaults, java.util.List<String> changes) {
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
            new org.bukkit.configuration.file.YamlConfiguration().loadFromString(text);
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


    static final String BOSS_TAG = "faultline_demon_eye";
    static final String SERVANT_TAG = "faultline_demon_servant";
    static final String DISPLAY_TAG = "faultline_boss_display";
    private static final UUID PACK_ID = UUID.fromString("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");

    enum Attack { HOVER, SERVANTS, BLOOD_CHARGE, SPIN_DASH, TOOTH_VOMIT, BLOOD_SHOTS, BLOOD_RAIN, PHANTOM_DASH }

    private static final Attack[] PATTERN_1 = {Attack.HOVER, Attack.HOVER, Attack.SERVANTS, Attack.HOVER, Attack.BLOOD_CHARGE, Attack.HOVER, Attack.SERVANTS};
    private static final Attack[] PATTERN_2 = {Attack.HOVER, Attack.HOVER, Attack.SERVANTS, Attack.SPIN_DASH, Attack.HOVER, Attack.HOVER, Attack.TOOTH_VOMIT,
            Attack.HOVER, Attack.HOVER, Attack.SPIN_DASH, Attack.BLOOD_CHARGE, Attack.HOVER, Attack.BLOOD_RAIN, Attack.HOVER, Attack.TOOTH_VOMIT, Attack.SPIN_DASH};
    private static final Attack[] PATTERN_3 = {Attack.HOVER, Attack.HOVER, Attack.BLOOD_SHOTS, Attack.SERVANTS, Attack.SPIN_DASH, Attack.HOVER, Attack.HOVER,
            Attack.BLOOD_SHOTS, Attack.HOVER, Attack.PHANTOM_DASH, Attack.BLOOD_CHARGE, Attack.BLOOD_SHOTS, Attack.HOVER, Attack.BLOOD_RAIN,
            Attack.HOVER, Attack.TOOTH_VOMIT, Attack.PHANTOM_DASH, Attack.SPIN_DASH};

    private final Random random = new Random();
    private boolean dealing; // true only while the boss deals its own damage
    private NamespacedKey summonKey;
    private DemonEye eye;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig(); // runs the config repair before anything reads the config
        summonKey = new NamespacedKey(this, "suspicious_eye");
        staffKey = new NamespacedKey(this, "twin_eye_staff");
        shieldKey = new NamespacedKey(this, "demon_eye_shield");
        arrowKey = new NamespacedKey(this, "unholy_arrow");
        mortEggKey = new NamespacedKey(this, "mortimer_egg");
        duneEggKey = new NamespacedKey(this, "dune_egg");
        frostEggKey = new NamespacedKey(this, "frostmaw_egg");
        armbandKey = new NamespacedKey(this, "captains_armband");
        lorenzoBallKey = new NamespacedKey(this, "lorenzos_ball");
        restoreBoxFromFile(); // a Don arena left over from a crash
        registerJacobRecipe();
        getCommand("jacob").setExecutor((sender, command, label, args) -> {
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            Player self = sender instanceof Player pl ? pl : null;
            switch (sub) {
                case "summon" -> {
                    if (self == null) { sender.sendMessage("Players only."); return true; }
                    if (jacob != null) { sender.sendMessage(ChatColor.GRAY + "Diamond Jacob is already here."); return true; }
                    summonJacob(self.getLocation(), self);
                }
                case "kill" -> { if (jacob != null) { jacob.removeEverything(); jacob = null; sender.sendMessage(ChatColor.GREEN + "Removed Diamond Jacob."); } else sender.sendMessage(ChatColor.GRAY + "He isn't here."); }
                case "phase" -> {
                    if (jacob == null || args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/jacob phase <2|3|4>"); return true; }
                    int ph;
                    try { ph = Math.max(2, Math.min(4, Integer.parseInt(args[1]))); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.YELLOW + "/jacob phase <2|3|4>"); return true; }
                    double per = jacob.maxHp / 4;
                    jacob.hp = jacob.maxHp - (ph - 1) * per + 1;
                    jacob.state = ph == 2 ? Jacob.P1 : ph == 3 ? Jacob.P2 : Jacob.P3;
                    jacob.damage(2, self, true); // crosses into that phase's cutscene
                }
                case "item" -> {
                    String which = args.length > 1 ? args[1].toLowerCase() : "horn";
                    int amount = 1; Player target = self;
                    for (int i = 2; i < args.length; i++) {
                        Player o = Bukkit.getPlayerExact(args[i]);
                        if (o != null) target = o; else { try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { } }
                    }
                    if (target == null) { sender.sendMessage("Who should get it?"); return true; }
                    for (int i = 0; i < amount; i++) give(target, jacobItem(which));
                    sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + which + " to " + target.getName() + ".");
                }
                default -> sender.sendMessage(ChatColor.YELLOW + "/jacob <summon|kill|phase <2|3|4>|item <horn|hammer|sword> [amount] [player]>");
            }
            return true;
        });
        getCommand("kraken").setExecutor((sender, command, label, args) -> {
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            Player self = sender instanceof Player pl ? pl : null;
            switch (sub) {
                case "summon" -> {
                    if (self == null) { sender.sendMessage("Players only."); return true; }
                    if (kraken != null) { sender.sendMessage(ChatColor.GRAY + "The Kraken is already here."); return true; }
                    Location at = self.getLocation().add(self.getLocation().getDirection().setY(0).multiply(8));
                    if (!isWater(at.getBlock())) { sender.sendMessage(ChatColor.RED + "Stand in deep water (he needs water to swim in)."); return true; }
                    summonKraken(at, self);
                }
                case "kill" -> { if (kraken != null) { kraken.removeEverything(); kraken = null; sender.sendMessage(ChatColor.GREEN + "Removed the Kraken."); } else sender.sendMessage(ChatColor.GRAY + "He isn't here."); }
                case "phase" -> {
                    if (kraken == null || args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/kraken phase <2|3>"); return true; }
                    int ph;
                    try { ph = Math.max(2, Math.min(3, Integer.parseInt(args[1]))); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.YELLOW + "/kraken phase <2|3>"); return true; }
                    kraken.hp = Math.min(kraken.hp, kraken.maxHp * (ph == 2 ? 0.65 : 0.32));
                    kraken.checkPhase();
                }
                case "bubble" -> { if (kraken != null) { kraken.pinkTimer = 1; sender.sendMessage(ChatColor.LIGHT_PURPLE + "A pink bubble is coming."); } }
                case "worm" -> { if (self != null) console("givekraken worm 1 " + self.getName(), self); }
                default -> sender.sendMessage(ChatColor.YELLOW + "/kraken <summon|kill|phase <2|3>|bubble|worm>");
            }
            return true;
        });
        getCommand("don").setExecutor((sender, command, label, args) -> {
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (sub.equals("answer")) { // the [Yes] / [No] buttons
                if (sender instanceof Player p && don != null && args.length >= 3) don.answer(p, args[1].equalsIgnoreCase("yes"), args[2]);
                return true;
            }
            if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
            Player self = sender instanceof Player p ? p : null;
            switch (sub) {
                case "summon" -> {
                    if (self == null) return true;
                    if (don != null) { sender.sendMessage(ChatColor.RED + "Don Lorenzo is already here."); return true; }
                    String problem = flatProblem(self.getLocation());
                    if (problem != null) sender.sendMessage(ChatColor.YELLOW + "(Normally blocked: " + problem + " Summoning anyway.)");
                    summonDon(self.getLocation(), self);
                }
                case "kill" -> { if (don != null) { don.cleanup(); sender.sendMessage(ChatColor.GREEN + "Removed Don Lorenzo (and restored the arena)."); } else sender.sendMessage(ChatColor.GRAY + "He isn't here."); }
                case "phase2" -> { if (don != null && don.state <= PHASE1) { if (don.state == ARRIVE) { don.lift = 0; don.ballAt = null; } if ((don.state == TALK || don.state == ARRIVE) && self != null) { don.join(self); don.state = PHASE1; } don.startCutscene(); } }
                case "speech" -> { if (don != null && don.state == PHASE2) { don.mt = T_SPEECH; sender.sendMessage(ChatColor.GRAY + "(Skipped to 2:43. The music won't line up.)"); } }
                case "armband", "ball" -> {
                    int amount = 1; Player target = self;
                    for (int i = 1; i < args.length; i++) {
                        Player online = Bukkit.getPlayerExact(args[i]);
                        if (online != null) target = online;
                        else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                    }
                    if (target == null) return true;
                    for (int i = 0; i < amount; i++) give(target, sub.equals("armband") ? captainsArmband() : lorenzosBall());
                    sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x " + (sub.equals("armband") ? "Captain's Armband" : "Lorenzo's Ball") + ".");
                }
                default -> sender.sendMessage(ChatColor.YELLOW + "/don summon | kill | phase2 | speech | armband [amount] [player] | ball [amount] [player]");
            }
            return true;
        });
        talonKey = new NamespacedKey(this, "talon");
        idolKey = new NamespacedKey(this, "dune_idol");
        idolRecipeKey = new NamespacedKey(this, "dune_idol_recipe");
        registerIdolRecipe();
        removeLeftovers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            // Each boss updates on its own: an error in one can't freeze the others, and the error is
            // written to the console (at most every 30s per boss) so it can be tracked down.
            safely("Demon Eye", () -> { if (eye != null) eye.tick(); });
            safely("Frostbeard", () -> { if (mortimer != null) mortimer.tick(); });
            safely("Dune worm", () -> { if (dune != null) dune.tick(); });
            safely("Don Lorenzo", () -> { if (don != null) don.tick(); });
            safely("Kraken", () -> { if (kraken != null) kraken.tick(); });
            safely("Diamond Jacob", () -> { if (jacob != null) jacob.tick(); });
            safely("Kraken bait", this::baitTick);
            safely("Lorenzo's Ball", this::ballTick);
            safely("Bedrock stand-ins", this::proxyTick);
            safely("Twin Eyes", this::minionTick);
            safely("Shield dash", this::dashTick);
        }, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::arrowTick, 2L, 2L);
        getServer().getScheduler().runTaskTimer(this, this::mortimerSpawnTick, 600L, 600L);
        getServer().getScheduler().runTaskTimer(this, this::duneSpawnTick, 300L, 600L);
        getServer().getScheduler().runTaskTimer(this, this::vultureSpawnTick, 200L, 400L);
        getServer().getScheduler().runTaskTimer(this, () -> safely("Vultures", this::birdTick), 1L, 1L);
        getCommand("dune").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (sub.equals("summon") && sender instanceof Player p) {
                if (dune != null) { sender.sendMessage(ChatColor.RED + "A great worm is already here."); return true; }
                summonDune(p, args.length > 1 && args[1].equalsIgnoreCase("frost"));
                sender.sendMessage(ChatColor.GRAY + "(Fight it in survival; creative players don't count as fighters.)");
            } else if (sub.equals("kill")) {
                if (dune != null) { dune.removeEverything(); dune = null; sender.sendMessage(ChatColor.GREEN + "Removed the Dune Devourer."); }
                else sender.sendMessage(ChatColor.GRAY + "The Dune Devourer isn't here.");
            } else if (sub.equals("egg")) {
                int amount = 1;
                Player target = sender instanceof Player p ? p : null;
                for (int i = 1; i < args.length; i++) {
                    Player online = Bukkit.getPlayerExact(args[i]);
                    if (online != null) target = online;
                    else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                if (target == null) { sender.sendMessage(ChatColor.YELLOW + "/dune egg [amount] [player]"); return true; }
                boolean frostEggWanted = args.length > 1 && args[1].equalsIgnoreCase("frost");
                ItemStack egg = frostEggWanted ? frostmawEgg() : duneEgg();
                egg.setAmount(amount);
                give(target, egg);
                sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x " + egg.getItemMeta().getDisplayName() + ChatColor.GREEN + ".");
            } else if (sub.equals("item") && args.length > 1) {
                int amount = 1;
                Player target = sender instanceof Player p ? p : null;
                for (int i = 2; i < args.length; i++) {
                    Player online = Bukkit.getPlayerExact(args[i]);
                    if (online != null) target = online;
                    else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                ItemStack item = args[1].equalsIgnoreCase("talon") ? talon() : args[1].equalsIgnoreCase("idol") ? duneIdol() : null;
                if (item == null || target == null) { sender.sendMessage(ChatColor.YELLOW + "/dune item <talon|idol> [amount] [player]"); return true; }
                item.setAmount(amount);
                give(target, item);
                sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x " + item.getItemMeta().getDisplayName() + ChatColor.GREEN + ".");
            } else if (sub.equals("vulture") && sender instanceof Player p) {
                // test: spawn one over you, then show exactly which wild-spawn checks pass where you're standing
                boolean owl = args.length > 1 && args[1].equalsIgnoreCase("owl");
                org.bukkit.entity.Phantom v = spawnVulture(p.getLocation().add(0, 10, 0), true, owl ? "Snow Owl" : "Vulture");
                if (v == null || !v.isValid()) sender.sendMessage(ChatColor.RED + "The server refused to spawn it: another plugin or a protected region is blocking phantoms here.");
                else sender.sendMessage(ChatColor.GREEN + "Spawned a " + (owl ? "Snow Owl" : "Vulture") + " 10 blocks above you. Look up!");
                Location l = p.getLocation();
                String biome = l.getBlock().getBiome().getKey().getKey();
                int sky = l.getBlock().getLightFromSky();
                long nearby = p.getNearbyEntities(48, 32, 48).stream().filter(e -> e.getScoreboardTags().contains(WILD_VULTURE_TAG)).count();
                int max = getConfig().getInt("vultures.max-nearby", 3);
                boolean sandstorm = "SANDSTORM".equals(activeEvent());
                sender.sendMessage(ChatColor.GOLD + "Wild Vultures, where you're standing:");
                sender.sendMessage(check(getConfig().getBoolean("vultures.enabled", true), "vultures.enabled is on"));
                sender.sendMessage(check(survival(p), "you're in Survival/Adventure (not Creative/Spectator)"));
                sender.sendMessage(check(l.getWorld().getEnvironment() == World.Environment.NORMAL, "you're in the Overworld"));
                sender.sendMessage(check(l.getWorld().getDifficulty() != Difficulty.PEACEFUL, "difficulty isn't Peaceful"));
                sender.sendMessage(check(desertAt(l), "you're in a desert or badlands (biome here: " + biome + ")"));
                sender.sendMessage(check(sky >= 12, "you're under open sky (sky light " + sky + ", needs 12+)"));
                sender.sendMessage(check(nearby < max, "fewer than " + max + " wild Vultures nearby (" + nearby + " now)"));
                sender.sendMessage(ChatColor.GRAY + "If all pass: a " + (int) Math.round(100 * getConfig().getDouble(sandstorm ? "vultures.sandstorm-chance" : "vultures.chance", sandstorm ? 0.5 : 0.25))
                        + "% chance every 20 seconds" + (sandstorm ? " (Sandstorm boost)" : "") + ", 1-2 at a time.");
            } else sender.sendMessage(ChatColor.YELLOW + "/dune summon [frost] | kill | egg [frost] [amount] [player] | item <talon|idol> [amount] [player] | vulture [owl]");
            return true;
        });
        getCommand("frostbeard").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (sub.equals("summon") && sender instanceof Player p) {
                if (mortimer != null) { sender.sendMessage(ChatColor.RED + "Frostbeard is already here."); return true; }
                Vector ahead = p.getLocation().getDirection().setY(0); // looking straight up or down: still somewhere sensible
                        if (ahead.lengthSquared() < 0.0001) ahead = new Vector(1, 0, 0);
                        summonMortimer(p.getLocation().add(ahead.normalize().multiply(6)).getBlock(), p);
                sender.sendMessage(ChatColor.GRAY + "(Fight him in survival; creative players don't count as fighters.)");
            } else if (sub.equals("kill")) {
                if (mortimer != null) { mortimer.removeEverything(); mortimer = null; sender.sendMessage(ChatColor.GREEN + "Removed Frostbeard."); }
                else sender.sendMessage(ChatColor.GRAY + "Frostbeard isn't here.");
            } else if (sub.equals("egg")) {
                int amount = 1;
                Player target = sender instanceof Player p ? p : null;
                for (int i = 1; i < args.length; i++) {
                    Player online = Bukkit.getPlayerExact(args[i]);
                    if (online != null) target = online;
                    else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                if (target == null) { sender.sendMessage(ChatColor.YELLOW + "/frostbeard egg [amount] [player]"); return true; }
                ItemStack egg = mortimerEgg();
                egg.setAmount(amount);
                give(target, egg);
                sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x Frostbeard Spawn Egg.");
            } else sender.sendMessage(ChatColor.YELLOW + "/frostbeard summon | kill | egg [amount] [player]");
            return true;
        });
        getCommand("demoneye").setExecutor(new BossCommand());
        getLogger().info("Faultline Bosses enabled.");
    }

    @Override
    public void onDisable() {
        // BUG FIX: these ran in a row, so ONE error left every boss after it frozen in the world
        // (and still glowing). Each runs on its own now, and a final sweep removes anything left.
        safely("shutdown: Don Lorenzo", () -> { if (don != null) don.cleanup(); }); // restores the arena and anyone mid-cutscene
        safely("shutdown: Lorenzo's Ball", () -> { for (KickedBall b : kickedBalls) if (b.display.isValid()) b.display.remove(); });
        safely("shutdown: Dune worm", () -> { if (dune != null) dune.removeEverything(); });
        safely("shutdown: stand-ins", () -> { for (Proxy px : proxies.values()) if (px.stand.isValid()) px.stand.remove(); });
        safely("shutdown: Frostbeard", () -> { if (mortimer != null) mortimer.removeEverything(); });
        safely("shutdown: Twin Eyes", () -> { for (UUID owner : new ArrayList<>(minions.keySet())) dismissMinions(owner); });
        safely("shutdown: Demon Eye", () -> { if (eye != null) eye.removeEverything(); });
        safely("shutdown: leftovers", this::removeLeftovers);
        safely("shutdown: Kraken", () -> { if (kraken != null) kraken.removeEverything(); });
        safely("shutdown: Diamond Jacob", () -> { if (jacob != null) jacob.removeEverything(); });
        don = null; dune = null; mortimer = null; eye = null; kraken = null; jacob = null;
        proxies.clear();
    }

    private double cfg(String key, double def) {
        return getConfig().getDouble("demon-eye." + key, def);
    }

    private static boolean survival(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private static boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    private void removeLeftovers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                Set<String> tags = e.getScoreboardTags();
                if (tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG) || tags.contains(DISPLAY_TAG)
                        || tags.contains(MORT_TAG) || tags.contains(RUNNER_TAG) || tags.contains(TWIN_EYE_TAG) || tags.contains(MORT_ORB_TAG)
                        || tags.contains(PROXY_TAG) || tags.contains(DUNE_TAG) || tags.contains(DUNE_SEG_TAG) || tags.contains(VULTURE_TAG)
                        || tags.contains(DON_TAG) || tags.contains(DON_ORB_TAG) || tags.contains(KRAKEN_TAG) || tags.contains(KRAKEN_TENT_TAG)
                        || tags.contains(KRAKEN_PINK_TAG) || tags.contains(KRAKEN_EEL_TAG) || tags.contains(KRAKEN_MINION_TAG)
                        || tags.contains(JACOB_TAG) || tags.contains(JACOB_BIRD_TAG) || tags.contains(JACOB_ORB_TAG) || tags.contains(JACOB_MINION_TAG)) e.remove();
            }
        }
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private void sound(Location at, String name, float volume, float pitch) {
        at.getWorld().playSound(at, "faultline:demon_eye." + name, SoundCategory.HOSTILE, volume, pitch);
    }

    /**
     * Item displays draw item models turned 180 degrees from how the model is built,
     * so the eye's face ended up at the back (it dashed tendrils-first and spat teeth
     * out of its tendrils). This flips it so the face leads. model-flip: false undoes it.
     */
    private Quaternionf facing(float roll) {
        Quaternionf q = new Quaternionf();
        if (getConfig().getBoolean("demon-eye.model-flip", true)) q.rotateY((float) Math.PI);
        return q.rotateZ(roll);
    }

    /** An item whose look comes from the FaultlineBosses resource pack. */
    private static ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", model));
        item.setItemMeta(meta);
        return item;
    }

    private ItemDisplay spawnDisplay(Location at, String model, float scale, int teleportTicks, Display.Billboard billboard) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class);
        d.setItemStack(modelItem(model));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setBillboard(billboard);
        d.setTeleportDuration(teleportTicks);
        d.setInterpolationDuration(2);
        d.setViewRange(4f);
        d.setBrightness(new Display.Brightness(13, 13)); // stays visible at night
        d.setPersistent(false);
        d.addScoreboardTag(DISPLAY_TAG);
        boolean eyeModel = billboard == Display.Billboard.FIXED;
        d.setTransformation(new Transformation(new Vector3f(), eyeModel ? facing(0) : new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
        return d;
    }

    // ===================== RESOURCE PACK =====================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay so this lands after other plugins' join-time packs (BackpackPlus race).
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            String url = getConfig().getString("resource-pack.url", "");
            String hash = getConfig().getString("resource-pack.hash", "");
            if (url == null || url.isBlank()) return;
            try {
                byte[] bytes = hash == null || hash.isBlank() ? new byte[0] : HexFormat.of().parseHex(hash);
                player.addResourcePack(PACK_ID, url, bytes, null, false);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Couldn't send the boss resource pack to " + player.getName(), e);
            }
        }, 70L);
    }

    // ===================== SUSPICIOUS EYE (summon item) =====================

    ItemStack summonItem() {
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "suspicious_eye"));
        meta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Suspicious Eye");
        meta.setLore(List.of(ChatColor.GRAY + "Use at night to summon", ChatColor.GRAY + "the Demon Eye.",
                ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "It's watching you..."));
        meta.getPersistentDataContainer().set(summonKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSummonItem(ItemStack item) {
        if (item == null || item.getType() != Material.ENDER_EYE || !item.hasItemMeta()) return false;
        Byte tag = item.getItemMeta().getPersistentDataContainer().get(summonKey, PersistentDataType.BYTE);
        return tag != null && tag == (byte) 1;
    }

    /** 0.05% from vanilla mobs only: no custom/raid/summoned mobs, no spawner or spawn-egg mobs. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player || dead.getKiller() == null) return;
        if (dead.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_"))) return;
        if (dead.fromMobSpawner()) return;
        CreatureSpawnEvent.SpawnReason reason = dead.getEntitySpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM || reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) return;
        if (random.nextDouble() < cfg("summon-item-drop-chance", 0.0005)) {
            event.getDrops().add(summonItem());
            dead.getKiller().sendMessage(ChatColor.DARK_RED + "Something dropped a Suspicious Eye... it's watching you.");
        }
    }

    @EventHandler
    public void onSummon(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isSummonItem(event.getItem())) return;
        // Always deny: it's an Ender Eye underneath, which would otherwise be thrown.
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);

        Player player = event.getPlayer();
        World world = player.getWorld();
        if (eye != null) {
            player.sendMessage(ChatColor.RED + "The Demon Eye is already here.");
            return;
        }
        if (world.getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage(ChatColor.RED + "The eye only opens in the Overworld.");
            return;
        }
        if (!isNight(world)) {
            player.sendMessage(ChatColor.RED + "The eye only opens at night.");
            return;
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            player.sendMessage(ChatColor.RED + "Nothing answers on Peaceful.");
            return;
        }
        if (!summon(player)) return; // BUG FIX: only use up the eye if something actually appeared
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        }
    }

    /** Returns false if it couldn't appear (e.g. WorldGuard blocks mob spawning there). */
    boolean summon(Player summoner) {
        double angle = random.nextDouble() * Math.PI * 2;
        Location at = summoner.getLocation().add(Math.cos(angle) * 18, 10, Math.sin(angle) * 18);
        eye = new DemonEye(at, summoner);
        if (!eye.hitbox.isValid()) {
            eye.removeEverything();
            eye = null;
            summoner.sendMessage(ChatColor.RED + "The Demon Eye can't appear here (mob spawning is blocked in this area).");
            return false;
        }
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Demon Eye has awoken!");
        for (Player p : summoner.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 128 * 128) continue;
            p.showTitle(Title.title(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE DEMON EYE"),
                    legacy(ChatColor.GRAY + "has awoken..."),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        }
        sound(at, "summon", 4f, 1f);
        return true;
    }

    // ===================== EVENTS: sleep, hits, deaths =====================

    @EventHandler(ignoreCancelled = true)
    public void onSleep(PlayerBedEnterEvent event) {
        if (eye == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.DARK_RED + "You can't sleep while the Demon Eye is watching.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        // BUG FIX: Frostbeard's size-3 hitbox split into normal (visible) slimes when he died.
        // Every Faultline hitbox is tagged "faultline_...", so none of them can ever split.
        if (event.getEntity().getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_"))) event.setCancelled(true);
    }

    /** The hitbox passes through terrain, so terrain damage is ignored; transformations make it untouchable. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBossDamage(EntityDamageEvent event) {
        if (eye == null || !event.getEntity().getScoreboardTags().contains(BOSS_TAG)) return;
        EntityDamageEvent.DamageCause c = event.getCause();
        boolean allowed = c == EntityDamageEvent.DamageCause.ENTITY_ATTACK || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || c == EntityDamageEvent.DamageCause.PROJECTILE || c == EntityDamageEvent.DamageCause.MAGIC
                || c == EntityDamageEvent.DamageCause.THORNS || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || c == EntityDamageEvent.DamageCause.CUSTOM;
        if (!allowed || eye.transitionTicks > 0 || eye.dying || !byPlayer(event)) {
            event.setCancelled(true);
            return;
        }
        if (!event.isCancelled()) eye.onHurt(); // BUG FIX: a hit blocked by another plugin shouldn't flash/sound
    }

    /** Slimes damage whoever touches them (even without AI); the hitbox only hurts through real attacks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTouchDamage(EntityDamageByEntityEvent event) {
        Set<String> tags = event.getDamager().getScoreboardTags();
        if (!(tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG) || tags.contains(MORT_TAG) || tags.contains(MORT_ORB_TAG)
                || tags.contains(DUNE_TAG) || tags.contains(DUNE_SEG_TAG) || tags.contains(KRAKEN_TAG) || tags.contains(KRAKEN_TENT_TAG)
                || tags.contains(KRAKEN_PINK_TAG) || tags.contains(KRAKEN_EEL_TAG) || tags.contains(JACOB_TAG) || tags.contains(JACOB_BIRD_TAG)
                || tags.contains(JACOB_ORB_TAG))) return;
        if (!dealing) {
            event.setCancelled(true);
        } else if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            // hurt() already decided about the shield; don't let vanilla block it a second time
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
        }
    }

    /** How a boss attack interacts with a raised shield. */
    enum Guard { BLOCKABLE, HEAVY, UNBLOCKABLE }

    /**
     * Every Demon Eye hit goes through here. Shields only count if you're facing
     * where the attack actually comes FROM (a tooth falling from above, a Servant from
     * the side), not just the boss. BLOCKABLE: fully blocked. HEAVY: half damage, and
     * your shield is knocked away for 5 seconds. UNBLOCKABLE: shields don't help.
     */
    private void hurt(Player p, double amount, Entity source, Vector from, Guard guard) {
        boolean frost = source != null && source.getScoreboardTags().contains(MORT_TAG);
        boolean worm = source != null && source.getScoreboardTags().contains(DUNE_TAG);
        if (source != null && source.getScoreboardTags().contains(DON_TAG)) amount *= ncfg("damage-multiplier", 1.3); // Don hits 30% harder
        if (source != null && source.getScoreboardTags().contains(KRAKEN_TAG)) amount *= kcfg("damage-multiplier", 1.2) / Math.max(0.01, cfg("damage-multiplier", 1.0)); // his own multiplier
        if (source != null && source.getScoreboardTags().contains(JACOB_TAG)) amount *= jcfg("damage-multiplier", 1.0) / Math.max(0.01, cfg("damage-multiplier", 1.0));
        amount *= frost ? mcfg("damage-multiplier", 1.5) : worm ? dcfg("damage-multiplier", 1.2) : cfg("damage-multiplier", 1.0);
        if (guard != Guard.UNBLOCKABLE && facingBlock(p, from)) {
            shieldHit(p, amount, guard == Guard.HEAVY);
            if (guard == Guard.BLOCKABLE) return;
            amount *= 0.5;
        }
        dealing = true;
        try {
            p.damage(amount, source);
        } finally {
            dealing = false;
        }
    }

    /** Raising a shield and facing the attack's origin (within 90 degrees). */
    static boolean facingBlock(Player p, Vector from) {
        if (!p.isBlocking()) return false;
        Vector to = from.clone().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 0.01) return true;
        return p.getEyeLocation().getDirection().dot(to.normalize()) > 0;
    }

    static void shieldHit(Player p, double damage, boolean heavy) {
        EquipmentSlot hand = p.getInventory().getItemInMainHand().getType() == Material.SHIELD ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        p.damageItemStack(hand, 1 + (int) Math.floor(damage)); // normal shield wear (breaks when used up)
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        if (heavy) { // like an axe hit: the shield is knocked away for 5 seconds
            p.setCooldown(Material.SHIELD, 100);
            p.clearActiveItem();
            p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 0.8f, 1.2f);
        }
    }

    /** Hitting the Demon Eye (or a Servant), or getting hit by it, puts you in the fight. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        if (eye == null) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player s ? s : null;
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (attacker != null && (tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG))) eye.join(attacker);
        if (event.getEntity() instanceof Player victim && event.getDamager().getScoreboardTags().contains(BOSS_TAG)) eye.join(victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (tags.contains(SERVANT_TAG)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            if (eye != null) eye.servantKilled(event.getEntity());
            return;
        }
        if (!tags.contains(BOSS_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (eye != null) eye.die();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (eye != null) eye.fighterDied(event.getEntity());
    }

    // ===================== THE BOSS =====================

    private class Shot {
        static final int TOOTH = 0, TOOTH_BALL = 1, BLOOD = 2;
        final int type;
        final ItemDisplay display;
        Vector pos, vel;
        boolean stuck;
        int life, stuckAt;
        UUID homing;

        Shot(int type, Vector pos, Vector vel) {
            this.type = type;
            this.pos = pos;
            this.vel = vel;
            String model = type == TOOTH ? "demon_tooth" : type == TOOTH_BALL ? "tooth_ball" : "blood_glob";
            float scale = type == TOOTH_BALL ? 1.4f : 1.1f;
            this.display = spawnDisplay(pos.toLocation(eye.world), model, scale, 1, Display.Billboard.CENTER);
        }
    }

    private class Servant {
        final Slime hitbox;
        final ItemDisplay display;
        Vector pos, vel;
        UUID target;
        int life;

        Servant(Vector pos, UUID target) {
            this.pos = pos;
            this.vel = new Vector(0, 0.2, 0);
            this.target = target;
            Location at = pos.toLocation(eye.world);
            this.hitbox = (Slime) eye.world.spawnEntity(at, EntityType.SLIME, false);
            hitbox.setSize(1);
            setupHitbox(hitbox, cfg("servant-health", 8), SERVANT_TAG, "Servant of the Eye");
            this.display = spawnDisplay(at, "demon_eye", 0.9f, 1, Display.Billboard.FIXED);
            proxy(hitbox, display, EntityType.GUARDIAN, 0.7);
        }
    }

    /**
     * BUG FIX: the plain "invisible" setting gets undone by Minecraft whenever a potion effect
     * is added to or wears off the mob (it re-checks for a real Invisibility effect). A Frost
     * Flare hit, a splash potion, or a Spectral Arrow made the green hitbox slime appear (glowing
     * slimes even draw their shell while invisible). A real, permanent, hidden Invisibility effect
     * survives that re-check; onHiddenEffect below keeps every other effect off.
     */
    private static void hideForGood(LivingEntity e) {
        e.setInvisible(true);
        e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    /**
     * BUG FIX: bosses only take damage a PLAYER caused (their hits, arrows, thrown potions, TNT they lit, clouds they
     * made, thorns, bed/anchor blasts, a Necromancer Staff minion). Before, Iron Golems (they attack slimes on sight),
     * creepers and skeletons could hurt the bosses too, so luring a worm into a village killed it for free loot.
     */
    private static boolean byPlayer(EntityDamageEvent e) {
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || c == EntityDamageEvent.DamageCause.CUSTOM) return true; // beds/anchors; the plugin's own hits
        if (!(e instanceof EntityDamageByEntityEvent by)) return false;
        Entity d = by.getDamager();
        if (d instanceof Player) return true;
        if (d.getScoreboardTags().contains("faultline_summon")) return true; // a player's Necromancer Staff minion
        if (d instanceof org.bukkit.entity.Projectile pr) return pr.getShooter() instanceof Player;
        if (d instanceof org.bukkit.entity.TNTPrimed tnt) return tnt.getSource() instanceof Player;
        if (d instanceof org.bukkit.entity.AreaEffectCloud cloud) return cloud.getSource() instanceof Player;
        return false;
    }

    private static boolean hiddenBody(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(BOSS_TAG) || t.contains(SERVANT_TAG) || t.contains(MORT_TAG) || t.contains(MORT_ORB_TAG)
                || t.contains(TWIN_EYE_TAG) || t.contains(RUNNER_TAG) || t.contains(DUNE_TAG) || t.contains(DUNE_SEG_TAG)
                || t.contains(DON_TAG) || t.contains(DON_ORB_TAG) || t.contains(VULTURE_TAG) // Vultures: invisible under their bird model
                || t.contains(KRAKEN_TAG) || t.contains(KRAKEN_TENT_TAG) || t.contains(KRAKEN_PINK_TAG) || t.contains(KRAKEN_EEL_TAG)
                || t.contains(JACOB_TAG) || t.contains(JACOB_BIRD_TAG) || t.contains(JACOB_ORB_TAG);
    }

    /** Hitboxes and Icicle Runners take no potion effects (no Glowing, Slowness, Poison...), and never lose Invisibility. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHiddenEffect(org.bukkit.event.entity.EntityPotionEffectEvent event) {
        if (!hiddenBody(event.getEntity())) return;
        org.bukkit.potion.PotionEffect incoming = event.getNewEffect();
        org.bukkit.potion.PotionEffect outgoing = event.getOldEffect();
        boolean invisIn = incoming != null && incoming.getType().equals(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        boolean invisOut = incoming == null && outgoing != null && outgoing.getType().equals(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        if (invisOut || (incoming != null && !invisIn)) event.setCancelled(true);
    }

    /** Boss hitboxes are fireproof: Fire Aspect / Flame made the invisible hitbox show flames. */
    @EventHandler(ignoreCancelled = true)
    public void onHitboxCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (hiddenBody(event.getEntity())) { event.setCancelled(true); event.getEntity().setFireTicks(0); }
    }

    private void setupHitbox(Slime slime, double health, String tag, String name) {
        slime.setAI(false);
        slime.setGravity(false);
        hideForGood(slime);
        slime.setSilent(true);
        // NOT setCollidable(false): on Paper that also makes arrows and other
        // projectiles pass straight through (melee still worked, arrows didn't).
        slime.setPersistent(false);
        slime.setRemoveWhenFarAway(false);
        slime.addScoreboardTag(tag);
        slime.setCustomName(ChatColor.DARK_RED + name);
        slime.setCustomNameVisible(false);
        AttributeInstance max = slime.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) max.setBaseValue(health); // after setSize, which resets health
        // BUG FIX: the server caps max health (1024 by default, spigot.yml). Asking for more (the worm's 1,100)
        // threw an error halfway through building the boss, leaving a stuck, half-built worm behind.
        slime.setHealth(Math.min(health, max != null ? max.getValue() : 20));
        AttributeInstance kb = slime.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0);
    }

    private class DemonEye {
        final World world;
        final Slime hitbox;
        final ItemDisplay model;
        final BossBar bar;
        Vector pos, vel = new Vector();
        int phase = 1;
        Attack[] pattern = PATTERN_1;
        int patternIndex = -1;
        Attack attack;
        int t;                     // ticks into the current attack
        UUID target;
        boolean contact, lookUp, stretch;
        Vector lastLook = new Vector(0, 0, 1);
        final Set<UUID> fighters = new HashSet<>();
        final Set<UUID> down = new HashSet<>();
        final Map<UUID, Integer> contactCooldown = new HashMap<>();
        final List<Shot> shots = new ArrayList<>();
        final List<Servant> servants = new ArrayList<>();
        final Map<UUID, Long> musicUntil = new HashMap<>(); // who's hearing the theme, and when their loop ends
        float musicPitch = 1f;
        int transitionTicks, hurtFlash, hurtSoundCd, lonelyTicks, ticksAlive, leaveTicks, deathTicks;
        boolean mouth, dying, leaving, enraged, vanished;
        float roll;
        // per-attack scratch
        double angle, side;
        int axisX;
        int shotsFired, burstsFired;

        DemonEye(Location at, Player summoner) {
            this.world = at.getWorld();
            this.pos = at.toVector();
            this.hitbox = (Slime) world.spawnEntity(at.clone().subtract(0, 1.3, 0), EntityType.SLIME, false);
            hitbox.setSize(5); // ~2.6 block hitbox (set first: it resets health)
            setupHitbox(hitbox, cfg("health", 1000), BOSS_TAG, "The Demon Eye");
            this.model = spawnDisplay(at, "demon_eye", (float) cfg("model-scale", 3.0), 2, Display.Billboard.FIXED);
            proxy(hitbox, model, EntityType.GUARDIAN, cfg("bedrock.eye-scale", 3.2));
            this.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Demon Eye", BarColor.RED, BarStyle.SEGMENTED_20);
            for (Player p : world.getPlayers()) {
                if (survival(p) && p.getLocation().distanceSquared(summoner.getLocation()) <= 64 * 64) fighters.add(p.getUniqueId());
            }
            if (survival(summoner)) fighters.add(summoner.getUniqueId()); // an admin in creative isn't a fighter
            target = summoner.getUniqueId();
            nextAttack();
        }

        // ---------- fighters ----------

        void join(Player p) {
            if (!survival(p)) return;
            fighters.add(p.getUniqueId());
            down.remove(p.getUniqueId()); // back in the fight
        }

        void fighterDied(Player p) {
            if (!fighters.contains(p.getUniqueId()) || dying || leaving) return;
            down.add(p.getUniqueId());
            // BUG FIX: offline, creative, or other-world fighters used to count as
            // "still fighting", so a wipe could never be detected.
            boolean anyoneUp = fighters.stream().anyMatch(id -> {
                if (down.contains(id)) return false;
                Player f = Bukkit.getPlayer(id);
                return f != null && !f.isDead() && survival(f) && f.getWorld().equals(world);
            });
            if (!anyoneUp) leave(ChatColor.DARK_RED + "Everyone fighting the Demon Eye has fallen... it drifts away into the night.");
        }

        List<Player> activeFighters() {
            List<Player> list = new ArrayList<>();
            for (UUID id : fighters) {
                if (down.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead() || !survival(p) || !p.getWorld().equals(world)) continue;
                if (p.getLocation().toVector().distanceSquared(pos) > 120 * 120) continue;
                list.add(p);
            }
            return list;
        }

        Player targetPlayer() {
            Player p = target == null ? null : Bukkit.getPlayer(target);
            if (p != null && activeFighters().contains(p)) return p;
            List<Player> active = activeFighters();
            if (active.isEmpty()) return null;
            Player pick = active.get(random.nextInt(active.size()));
            target = pick.getUniqueId();
            return pick;
        }

        Vector eyeOf(Player p) {
            return p.getLocation().toVector().add(new Vector(0, 1.2, 0));
        }

        double speed() {
            return (1 + 0.15 * (phase - 1)) * (enraged ? 1.4 : 1.0);
        }

        /** How high it hovers above you (0.6 = 60% of its original heights, so melee can reach it more). */
        double low() {
            return cfg("hover-height-scale", 0.6);
        }

        // ---------- main loop (every tick) ----------

        void tick() {
            ticksAlive++;
            if (dying) {
                deathTick();
                return;
            }
            if (leaving) {
                leaveTick();
                return;
            }
            if (!hitbox.isValid() || !model.isValid()) { // e.g. its area unloaded
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "The Demon Eye has vanished into the night...");
                removeEverything();
                eye = null;
                return;
            }
            enraged = !isNight(world);

            double engage = cfg("engage-radius", 48);
            // Anyone in survival nearby is in the fight (including players back after dying).
            for (Player p : world.getPlayers()) {
                if (survival(p) && !p.isDead() && p.getLocation().toVector().distanceSquared(pos) < engage * engage) join(p);
            }
            List<Player> active = activeFighters();
            if (active.isEmpty()) {
                if (++lonelyTicks > 200) { // nobody left in the fight for 10 seconds
                    leave(ChatColor.DARK_RED + "The Demon Eye has lost interest...");
                    return;
                }
            } else {
                lonelyTicks = 0;
            }

            // BUG FIX: everything here ran as one chain, so ONE error froze the Eye for good. Now each part recovers,
            // and a broken move is skipped instead of repeating forever.
            try {
                checkPhase();
                if (transitionTicks > 0) {
                    transitionTick();
                } else {
                    Player target = targetPlayer();
                    if (target != null) runAttack(target);
                    else vel.multiply(0.9);
                }
                eyeFails = 0;
            } catch (RuntimeException e) {
                logBossPart("Demon Eye", "move " + attack + " (tick " + t + ")", e);
                contact = false; stretch = false;
                nextAttack();
                if (++eyeFails > 100) { leave(ChatColor.DARK_RED + "The Demon Eye has vanished into the night..."); return; }
            }

            pos.add(vel);
            bossPart("Demon Eye", "contact damage", () -> contactDamage(active));
            bossPart("Demon Eye", "servants", this::tickServants);
            bossPart("Demon Eye", "shots", this::tickShots);
            bossPart("Demon Eye", "progress watchdog", () -> eyeWatchdog(active));
            updateBar();
            if (ticksAlive % 10 == 0) musicTick();
            render();
            contactCooldown.replaceAll((k, v) -> v - 1);
            contactCooldown.values().removeIf(v -> v <= 0);
            if (hurtFlash > 0 && --hurtFlash == 0) model.setGlowing(false);
            if (hurtSoundCd > 0) hurtSoundCd--;
        }

        int eyeFails, eyeLastSeen;

        /** Nowhere a fighter can see it for 15 seconds (inside terrain, walled off): it blinks back above one. */
        void eyeWatchdog(List<Player> active) {
            if (active.isEmpty() || transitionTicks > 0) { eyeLastSeen = ticksAlive; return; }
            for (Player p : active) {
                Vector d = eyeOf(p).subtract(pos);
                double len = d.length();
                if (len > 24) continue;
                if (len < 0.5 || (!world.getBlockAt(pos.toLocation(world)).getType().isSolid()
                        && world.rayTraceBlocks(pos.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null)) {
                    eyeLastSeen = ticksAlive; return;
                }
            }
            if (ticksAlive - eyeLastSeen < 300) return;
            eyeLastSeen = ticksAlive;
            Player f = active.get(random.nextInt(active.size()));
            Vector spot = eyeOf(f);
            for (int up = 6; up >= 2; up--) { // as high as there's open air over them (under a roof: lower)
                Vector c = eyeOf(f).add(new Vector(0, up, 0));
                if (!world.getBlockAt(c.toLocation(world)).getType().isSolid()) { spot = c; break; }
            }
            world.spawnParticle(Particle.DUST, pos.toLocation(world), 30, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.6f));
            pos = spot; vel = new Vector(); contact = false; stretch = false;
            world.spawnParticle(Particle.DUST, pos.toLocation(world), 30, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.6f));
            getLogger().info("[Demon Eye] was out of sight of everyone for 15s: brought it back above " + f.getName() + ".");
            nextAttack();
        }

        void fly(Vector goal, double maxSpeed, double accel) {
            Vector desired = goal.clone().subtract(pos);
            if (desired.length() > maxSpeed) desired.normalize().multiply(maxSpeed);
            vel.add(desired.subtract(vel).multiply(accel));
        }

        Vector toward(Player p, double sp) {
            Vector v = eyeOf(p).subtract(pos);
            return v.lengthSquared() < 0.01 ? new Vector() : v.normalize().multiply(sp);
        }

        void look(Vector at) {
            Vector d = at.clone().subtract(pos);
            if (d.lengthSquared() > 0.01) lastLook = d.normalize();
        }

        double chargeSpeed() {
            return cfg("charge-speed", 0.75);
        }

        /**
         * The launch velocity that makes a projectile with this gravity land on
         * `target` after exactly `ticks` ticks (matches the shot physics: each tick
         * gravity is applied, then it moves).
         */
        Vector lob(Vector from, Vector target, int ticks, double gravity) {
            Vector d = target.clone().subtract(from);
            double vy = (d.getY() + gravity * ticks * (ticks + 1) / 2.0) / ticks;
            return new Vector(d.getX() / ticks, vy, d.getZ() / ticks);
        }

        void nextAttack() {
            patternIndex = (patternIndex + 1) % pattern.length;
            attack = pattern[patternIndex];
            t = 0;
            contact = false;
            vanished = false;
            lookUp = false;
            stretch = false;
            shotsFired = 0;
            burstsFired = 0;
            target = null; // every attack picks a fighter (spreads the pressure across players)
        }

        // ---------- attacks ----------

        void runAttack(Player target) {
            Vector tp = eyeOf(target);
            double sp = speed();
            switch (attack) {
                case HOVER -> { // drift above, then charge
                    if (t < 30) {
                        fly(tp.clone().add(new Vector(Math.sin(ticksAlive * 0.05) * 5, 7 * low(), Math.cos(ticksAlive * 0.05) * 5)), 0.6 * sp, 0.15);
                        look(tp);
                    } else if (t == 30) {
                        vel = toward(target, 1.1 * sp * chargeSpeed());
                        contact = true;
                        stretch = true;
                        sound(pos.toLocation(world), "dash", 2f, 1f);
                    } else if (t > 50) {
                        // recovery: brakes hard and hangs in the air, no ramming damage.
                        // This is the window to hit it (like Terraria's pause between dashes).
                        vel.multiply(0.7);
                        stretch = false;
                        contact = false;
                    }
                    if (t >= 50 + (int) cfg("recovery-ticks", 30)) nextAttack();
                }
                case SERVANTS -> { // rush overhead, stop, birth servants in bursts of blood
                    int count = phase == 1 ? 6 : 9;
                    int every = phase >= 4 ? 2 : 4;
                    if (t < 20) {
                        fly(tp.clone().add(new Vector(0, 9 * low(), 0)), 0.9 * sp, 0.2);
                    } else {
                        vel.multiply(0.7);
                        if ((t - 20) % every == 0 && shotsFired < count && servants.size() < (int) cfg("max-servants", 14)) {
                            List<Player> active = activeFighters();
                            Player aim = active.isEmpty() ? target : active.get(shotsFired % active.size());
                            Vector at = pos.clone().add(new Vector(random.nextDouble() * 3 - 1.5, -1, random.nextDouble() * 3 - 1.5));
                            servants.add(new Servant(at, aim.getUniqueId()));
                            bloodBurst(at, 12);
                            sound(at.toLocation(world), "spit", 1.2f, 1.3f);
                            shotsFired++;
                        }
                    }
                    look(tp);
                    if (t >= 20 + count * every + 10) nextAttack();
                }
                case BLOOD_CHARGE -> { // fly far to the side, then sweep across dropping Tooth Balls
                    int prep = 35;
                    int length = phase == 1 ? 45 : phase >= 4 ? 30 : 38;
                    int dropEvery = phase == 1 ? 6 : phase >= 4 ? 3 : 4;
                    if (t == 0) {
                        side = random.nextBoolean() ? 1 : -1;
                        axisX = random.nextBoolean() ? 1 : 0;
                    }
                    Vector sideways = axisX == 1 ? new Vector(side, 0, 0) : new Vector(0, 0, side);
                    if (t < prep) {
                        fly(tp.clone().add(sideways.clone().multiply(18)).add(new Vector(0, 10 * low(), 0)), 1.3 * sp, 0.25);
                        look(tp);
                    } else if (t == prep) {
                        vel = sideways.clone().multiply(-1.0 * sp * chargeSpeed());
                        contact = true;
                        stretch = true;
                        sound(pos.toLocation(world), "roar", 2.5f, 1.1f);
                    } else if (t < prep + length) {
                        look(pos.clone().add(vel));
                        if ((t - prep) % dropEvery == 0) addShot(Shot.TOOTH_BALL, pos.clone().add(new Vector(0, -1.2, 0)), new Vector(0, -0.1, 0), null);
                    } else {
                        vel.multiply(0.8);
                        stretch = false;
                        if (t >= prep + length + 12) nextAttack();
                    }
                }
                case SPIN_DASH -> { // circle the target, a long charge, then 4 short charges
                    int spin = phase >= 4 ? 28 : 40;
                    if (t == 0) angle = random.nextDouble() * Math.PI * 2;
                    if (t < 25) {
                        fly(tp.clone().add(new Vector(Math.cos(angle) * 12, 3, Math.sin(angle) * 12)), 1.4 * sp, 0.3);
                        look(tp);
                    } else if (t < 25 + spin) {
                        contact = true;
                        angle += Math.PI * 2 / spin;
                        Vector goal = tp.clone().add(new Vector(Math.cos(angle) * 12, 3, Math.sin(angle) * 12));
                        vel = goal.subtract(pos).multiply(0.6);
                        look(tp);
                    } else if (t == 25 + spin) {
                        vel = toward(target, 1.45 * sp * chargeSpeed());
                        stretch = true;
                        sound(pos.toLocation(world), "roar", 2.5f, 0.9f);
                    } else if (t < 25 + spin + 20) {
                        // long charge
                    } else {
                        int s = t - (25 + spin + 20);
                        int cycle = s % 18;
                        if (cycle < 8) {
                            vel.multiply(0.6);
                            stretch = false;
                            look(tp);
                        } else if (cycle == 8) {
                            vel = toward(target, 1.2 * sp * chargeSpeed());
                            stretch = true;
                            sound(pos.toLocation(world), "dash", 1.8f, 1.2f);
                        }
                        if (s >= 18 * 4) nextAttack();
                    }
                }
                case TOOTH_VOMIT -> { // hover just above, turn up, roar, spray arcs of teeth
                    int spreads = phase == 2 ? 4 : 6;
                    int perSpread = phase == 2 ? 4 : phase == 3 ? 7 : 9;
                    if (t < 25) {
                        fly(tp.clone().add(new Vector(0, 8 * low(), 0)), 1.3 * sp, 0.3);
                        look(tp);
                    } else if (t < 35) {
                        vel.multiply(0.6);
                        lookUp = true;
                    } else {
                        if (t == 35) sound(pos.toLocation(world), "roar", 3f, 0.8f);
                        if ((t - 35) % 6 == 0 && burstsFired < spreads) {
                            List<Player> active = activeFighters();
                            Vector mouth = pos.clone().add(new Vector(0, 1.2, 0));
                            for (int i = 0; i < perSpread; i++) {
                                if (active.isEmpty()) break;
                                // spread the teeth across everyone fighting, landing around (and on) each of them
                                Player aim = active.get((burstsFired + i) % active.size());
                                double spread = cfg("tooth-spread", 2.5) * Math.sqrt(random.nextDouble());
                                double a = random.nextDouble() * Math.PI * 2;
                                Vector landing = aim.getLocation().toVector().add(new Vector(Math.cos(a) * spread, 0.9, Math.sin(a) * spread));
                                int flight = (int) cfg("tooth-flight-ticks", 30) + random.nextInt(12);
                                addShot(Shot.TOOTH, mouth.clone(), lob(mouth, landing, flight, 0.045), null);
                            }
                            sound(pos.toLocation(world), "spit", 1.5f, 0.9f);
                            burstsFired++;
                        }
                        if (burstsFired >= spreads && t > 35 + spreads * 6 + 15) nextAttack();
                    }
                }
                case BLOOD_SHOTS -> { // keep ~16 blocks away and fire homing blood, recoiling each time
                    int every = phase >= 4 ? 8 : 12;
                    if (t < 30) {
                        Vector away = pos.clone().subtract(tp);
                        away.setY(0);
                        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                        fly(tp.clone().add(away.normalize().multiply(16)).add(new Vector(0, 4, 0)), 1.1 * sp, 0.25);
                        look(tp);
                    } else {
                        vel.multiply(0.85);
                        look(tp);
                        if ((t - 30) % every == 0 && burstsFired < 4) {
                            List<Player> active = activeFighters();
                            for (int i = 0; i < 3; i++) {
                                Player aim = active.isEmpty() ? target : active.get(random.nextInt(active.size()));
                                Vector dir = eyeOf(aim).subtract(pos).normalize()
                                        .add(new Vector(random.nextGaussian() * 0.25, random.nextGaussian() * 0.25, random.nextGaussian() * 0.25));
                                addShot(Shot.BLOOD, pos.clone().add(lastLook.clone().multiply(1.6)), dir.normalize().multiply(0.4), aim.getUniqueId());
                            }
                            vel.add(toward(target, 0.4).multiply(-1)); // recoil
                            sound(pos.toLocation(world), "spit", 1.6f, 0.7f);
                            burstsFired++;
                        }
                        if (burstsFired >= 4 && t > 30 + every * 4 + 10) nextAttack();
                    }
                }
                case BLOOD_RAIN -> { // flies high overhead and rains blood down on everyone (look up and block)
                    if (t < 25) {
                        fly(tp.clone().add(new Vector(0, 12 * low(), 0)), 1.2 * sp, 0.25);
                        look(tp);
                    } else if (t < 75) {
                        vel.multiply(0.8);
                        lookUp = true;
                        if (t == 25) sound(pos.toLocation(world), "roar", 2.5f, 1.3f);
                        if (t % 2 == 0) {
                            for (Player p : activeFighters()) {
                                for (int i = 0; i < (phase >= 4 ? 2 : 1); i++) {
                                    Vector drop = eyeOf(p).add(new Vector(random.nextGaussian() * 4, 12 + random.nextDouble() * 3, random.nextGaussian() * 4));
                                    addShot(Shot.BLOOD, drop, new Vector(0, -0.4, 0), null); // no homing: it just falls
                                }
                            }
                        }
                        if (t % 10 == 0) sound(pos.toLocation(world), "spit", 1.2f, 0.6f);
                    } else if (t >= 85) nextAttack();
                }
                case PHANTOM_DASH -> { // vanishes, reappears off to the side, and charges. Three times.
                    int cycle = t % 40;
                    if (cycle == 0) {
                        vanished = true;
                        vel = new Vector();
                        world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world), 30, 1, 1, 1, 0.05);
                        bloodBurst(pos, 20);
                        sound(pos.toLocation(world), "dash", 1.5f, 0.6f);
                    }
                    if (cycle == 10) {
                        double a = random.nextDouble() * Math.PI * 2;
                        pos = tp.clone().add(new Vector(Math.cos(a) * 14, 3, Math.sin(a) * 14));
                        vanished = false;
                        world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world), 30, 1, 1, 1, 0.05);
                        bloodBurst(pos, 25);
                        sound(pos.toLocation(world), "roar", 2f, 1.2f);
                    }
                    if (cycle > 10 && cycle < 18) { vel.multiply(0.5); look(tp); } // a short tell before it charges
                    if (cycle == 18) {
                        vel = toward(target, 1.5 * sp * chargeSpeed());
                        contact = true;
                        stretch = true;
                        sound(pos.toLocation(world), "dash", 2f, 1.1f);
                    }
                    if (cycle > 34) { vel.multiply(0.8); contact = false; stretch = false; }
                    if (t >= 40 * 3) nextAttack();
                }
            }
            t++;
        }

        // ---------- phases ----------

        void checkPhase() {
            double frac = hitbox.getHealth() / cfg("health", 1000);
            int want = frac <= cfg("phase-4-at", 0.15) ? 4 : frac <= cfg("phase-3-at", 0.35) ? 3 : frac <= cfg("phase-2-at", 0.80) ? 2 : 1;
            if (want <= phase) return;
            phase = want;
            pattern = phase == 1 ? PATTERN_1 : phase == 2 ? PATTERN_2 : PATTERN_3;
            patternIndex = -1;
            transitionTicks = !mouth ? 50 : 24; // the full transformation if it hasn't opened its mouth yet
            contact = false;
            stretch = false;
            sound(pos.toLocation(world), !mouth ? "phase" : "roar", 4f, !mouth ? 1f : 0.7f);
            bloodBurst(pos, 50);
        }

        /** Phase 2: spins, flashes red, splits open into the mouth form. Later phases: a shorter roar-spin. */
        void transitionTick() {
            transitionTicks--;
            vel.multiply(0.8);
            roll += !mouth ? 0.55f : 0.4f;
            // BUG FIX: a big burst of damage can jump from phase 1 straight to 3 or 4;
            // it still has to open into its mouth form.
            if (!mouth && transitionTicks == 25) {
                mouth = true;
                model.setItemStack(modelItem("demon_eye_mouth"));
                proxy(hitbox, model, EntityType.ELDER_GUARDIAN, cfg("bedrock.mouth-scale", 1.4));
                world.spawnParticle(Particle.DUST, pos.toLocation(world), 80, 1.6, 1.6, 1.6, 0,
                        new Particle.DustOptions(Color.fromRGB(200, 0, 0), 2.2f));
                world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 2, 0.5, 0.5, 0.5, 0);
            }
            if (transitionTicks == 0) {
                roll = 0;
                nextAttack();
            }
        }

        // ---------- contact, servants, shots ----------

        void contactDamage(List<Player> active) {
            if (!contact || transitionTicks > 0) return;
            double radius = 2.4;
            double dmg = (phase == 1 ? cfg("contact-damage", 6) : cfg("contact-damage-mouth", 8)) + (enraged ? 2 : 0);
            for (Player p : active) {
                if (contactCooldown.containsKey(p.getUniqueId())) continue;
                if (eyeOf(p).subtract(new Vector(0, 0.4, 0)).distanceSquared(pos) > radius * radius) continue;
                hurt(p, dmg, hitbox, pos, Guard.HEAVY); // rams: heavy
                Vector push = p.getLocation().toVector().subtract(pos).setY(0);
                if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                p.setVelocity(push.normalize().multiply(0.9).setY(0.45));
                contactCooldown.put(p.getUniqueId(), 12);
            }
        }

        void tickServants() {
            Iterator<Servant> it = servants.iterator();
            while (it.hasNext()) {
                Servant s = it.next();
                s.life++;
                Player t = Bukkit.getPlayer(s.target);
                if (!s.hitbox.isValid() || s.life > 240 || t == null || t.isDead() || !t.getWorld().equals(world)) {
                    killServant(s, false);
                    it.remove();
                    continue;
                }
                // constant acceleration toward its target, with a low turning speed
                Vector dir = eyeOf(t).subtract(s.pos).normalize();
                s.vel.multiply(0.97).add(dir.multiply(0.06));
                if (s.vel.length() > 1.0) s.vel.normalize().multiply(1.0);
                Vector next = s.pos.clone().add(s.vel);
                if (next.toLocation(world).getBlock().getType().isSolid()) { // dies against terrain
                    killServant(s, true);
                    it.remove();
                    continue;
                }
                s.pos = next;
                if (eyeOf(t).distanceSquared(s.pos) < 1.4 * 1.4) {
                    hurt(t, cfg("servant-damage", 4), hitbox, s.pos, Guard.BLOCKABLE);
                    join(t);
                    killServant(s, true);
                    it.remove();
                    continue;
                }
                Location at = s.pos.toLocation(world);
                s.hitbox.teleport(at.clone().subtract(0, 0.26, 0));
                Vector look = s.vel.clone();
                at.setDirection(look.lengthSquared() > 0.001 ? look : new Vector(0, 0, 1));
                at.setYaw(at.getYaw() + (float) cfg("model-yaw-offset", 0));
                s.display.teleport(at);
            }
        }

        void servantKilled(LivingEntity hitbox) {
            servants.removeIf(s -> {
                if (!s.hitbox.equals(hitbox)) return false;
                bloodBurst(s.pos, 10);
                s.display.remove();
                return true;
            });
        }

        void killServant(Servant s, boolean burst) {
            if (burst) bloodBurst(s.pos, 10);
            s.display.remove();
            if (s.hitbox.isValid()) s.hitbox.remove();
        }

        void addShot(int type, Vector at, Vector v, UUID homing) {
            if (shots.size() >= (int) cfg("max-projectiles", 160)) return;
            Shot s = new Shot(type, at, v);
            s.homing = homing;
            shots.add(s);
        }

        void tickShots() {
            List<Player> active = activeFighters();
            // BUG FIX: loop over a COPY. A bursting Tooth Ball adds new teeth to this
            // list mid-loop, which threw a ConcurrentModificationException.
            for (Shot s : new ArrayList<>(shots)) {
                s.life++;
                boolean remove = false;
                if (!s.stuck) {
                    if (s.type == Shot.BLOOD && s.homing != null) {
                        Player h = Bukkit.getPlayer(s.homing);
                        if (h != null && h.getWorld().equals(world)) {
                            s.vel.multiply(0.95).add(eyeOf(h).subtract(s.pos).normalize().multiply(0.035));
                            double max = enraged ? 0.6 : 0.45;
                            if (s.vel.length() > max) s.vel.normalize().multiply(max);
                        }
                    } else {
                        s.vel.setY(s.vel.getY() - (s.type == Shot.TOOTH ? 0.045 : 0.05)); // gravity
                    }
                    Vector next = s.pos.clone().add(s.vel);
                    if (next.toLocation(world).getBlock().getType().isSolid()) {
                        if (s.type == Shot.BLOOD) remove = true;
                        else { // teeth stick where they land; tooth balls come to rest
                            s.stuck = true;
                            s.stuckAt = s.life;
                            s.vel = new Vector();
                        }
                    } else {
                        s.pos = next;
                    }
                }
                // hits
                double radius = s.type == Shot.TOOTH_BALL ? 1.1 : 0.9;
                double dmg = s.type == Shot.TOOTH ? cfg("tooth-damage", 4) : s.type == Shot.TOOTH_BALL ? cfg("tooth-ball-damage", 5) : cfg("blood-damage", 5);
                for (Player p : active) {
                    if (contactCooldown.containsKey(p.getUniqueId())) continue;
                    if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(s.pos) > radius * radius) continue;
                    hurt(p, dmg, hitbox, s.pos, Guard.BLOCKABLE); // teeth, tooth balls, blood
                    contactCooldown.put(p.getUniqueId(), 10);
                    if (s.type != Shot.TOOTH_BALL) remove = true;
                }
                // lifetimes
                if (s.type == Shot.TOOTH_BALL && s.life >= 110) { // bursts into 2 teeth after 5.5s
                    Vector from = s.pos.clone().add(new Vector(0, 0.5, 0));
                    Player near = null;
                    double best = 18 * 18;
                    for (Player p : active) {
                        double d = p.getLocation().toVector().distanceSquared(s.pos);
                        if (d < best) { best = d; near = p; }
                    }
                    for (int i = -1; i <= 1; i += 2) {
                        if (near != null) { // arc up and down onto the nearest player
                            double a = random.nextDouble() * Math.PI * 2;
                            Vector landing = near.getLocation().toVector().add(new Vector(Math.cos(a) * 1.5, 0.9, Math.sin(a) * 1.5));
                            addShot(Shot.TOOTH, from.clone(), lob(from, landing, 22 + random.nextInt(8), 0.045), null);
                        } else {
                            double a = random.nextDouble() * Math.PI * 2;
                            addShot(Shot.TOOTH, from.clone(), new Vector(Math.cos(a) * 0.25 * i, 1.0, Math.sin(a) * 0.25 * i), null);
                        }
                    }
                    bloodBurst(s.pos, 12);
                    sound(s.pos.toLocation(world), "spit", 1f, 1.4f);
                    remove = true;
                }
                if (s.type == Shot.TOOTH && (s.stuck ? s.life - s.stuckAt > 180 : s.life > 200)) remove = true;
                if (s.type == Shot.BLOOD && s.life > 100) remove = true;
                if (s.type == Shot.TOOTH_BALL && !s.stuck && s.life > 200) remove = true;

                if (remove) {
                    s.display.remove();
                    shots.remove(s);
                } else {
                    s.display.teleport(s.pos.toLocation(world));
                    if (s.life % 2 == 0) bedrockTrail(s.pos.toLocation(world), s.type == Shot.BLOOD ? Color.fromRGB(170, 0, 0)
                            : s.type == Shot.TOOTH_BALL ? Color.fromRGB(120, 20, 30) : Color.fromRGB(240, 235, 220), 1.2f);
                    if (s.type == Shot.BLOOD && s.life % 2 == 0) {
                        world.spawnParticle(Particle.DUST, s.pos.toLocation(world), 2, 0.1, 0.1, 0.1, 0,
                                new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.1f));
                    }
                }
            }
        }

        void bloodBurst(Vector at, int count) {
            world.spawnParticle(Particle.DUST, at.toLocation(world), count, 0.6, 0.6, 0.6, 0,
                    new Particle.DustOptions(Color.fromRGB(160, 0, 0), 1.5f));
        }

        // ---------- look & feel ----------

        void render() {
            Location at = pos.toLocation(world);
            hitbox.teleport(at.clone().subtract(0, 1.3, 0));

            Vector dir = lookUp ? new Vector(0, 1, 0.001) : lastLook;
            Location view = at.clone();
            view.setDirection(dir);
            view.setYaw(view.getYaw() + (float) cfg("model-yaw-offset", 0));
            view.setPitch(view.getPitch() * (float) cfg("model-pitch-sign", 1));
            model.teleport(view);

            // animations: idle bob, stretch on dashes, spin during transformations
            if (ticksAlive % 2 == 0) {
                float base = (float) cfg("model-scale", 3.0);
                float bob = (float) Math.sin(ticksAlive * 0.12) * 0.15f;
                Vector3f scale = vanished ? new Vector3f(0.01f, 0.01f, 0.01f)
                        : stretch ? new Vector3f(base * 0.85f, base * 0.85f, base * 1.25f) : new Vector3f(base, base, base);
                model.setInterpolationDelay(0);
                model.setTransformation(new Transformation(new Vector3f(0, bob, 0), facing(roll),
                        scale, new Quaternionf()));
            }
            // a faint blood trail while charging
            if (contact && ticksAlive % 2 == 0) {
                world.spawnParticle(Particle.DUST, at, 3, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.fromRGB(120, 0, 0), 1.3f));
            }
        }

        void onHurt() {
            model.setGlowColorOverride(Color.RED);
            model.setGlowing(true);
            hurtFlash = 3;
            if (hurtSoundCd == 0) {
                sound(pos.toLocation(world), "hurt", 1.2f, 0.9f + random.nextFloat() * 0.3f);
                hurtSoundCd = 5;
            }
        }

        void updateBar() {
            double max = cfg("health", 1000);
            bar.setProgress(Math.max(0, Math.min(1, hitbox.getHealth() / max)));
            bar.setTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Demon Eye" + (enraged ? ChatColor.GOLD + " (ENRAGED)" : ""));
            if (ticksAlive % 10 != 0) return;
            for (Player p : new ArrayList<>(bar.getPlayers())) {
                if (!p.isOnline() || !p.getWorld().equals(world) || p.getLocation().toVector().distanceSquared(pos) > 96 * 96) bar.removePlayer(p);
            }
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) <= 96 * 96 && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            }
        }

        // ---------- boss music ----------
        //
        // Plays for everyone within the radius, attached to the player so it
        // doesn't fade as they move. Loops for the whole fight, stops when they
        // leave the radius or the fight ends. Uses the Jukebox/Note Blocks
        // volume slider, and pauses vanilla's background music so they don't clash.
        // Phases 3-4 play it slightly faster (and higher) for extra intensity.

        static final String MUSIC = "faultline:demon_eye.music";

        void musicTick() {
            if (!getConfig().getBoolean("demon-eye.music.enabled", true)) return;
            float wantPitch = phase >= 3 ? (float) cfg("music.intense-speed", 1.0) : 1f;
            if (wantPitch != musicPitch) { // phase 3: restart everyone's loop at the faster speed
                musicPitch = wantPitch;
                stopMusicAll();
            }
            double radius = cfg("music.radius", 64);
            long now = System.currentTimeMillis();
            long loopMs = (long) (cfg("music.length-seconds", 316.42) * 1000 / musicPitch);
            Set<UUID> inRange = new HashSet<>();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > radius * radius) continue;
                inRange.add(p.getUniqueId());
                Long until = musicUntil.get(p.getUniqueId());
                if (until == null || now >= until - 150) {
                    p.stopSound(SoundCategory.MUSIC); // pause vanilla's background track
                    p.playSound(p, MUSIC, SoundCategory.RECORDS, (float) cfg("music.volume", 0.8), musicPitch);
                    musicUntil.put(p.getUniqueId(), now + loopMs);
                }
            }
            // left the radius (or the server): stop their music
            for (UUID id : new ArrayList<>(musicUntil.keySet())) {
                if (inRange.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.stopSound(MUSIC, SoundCategory.RECORDS);
                musicUntil.remove(id);
            }
        }

        void stopMusicAll() {
            for (UUID id : musicUntil.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.stopSound(MUSIC, SoundCategory.RECORDS);
            }
            musicUntil.clear();
        }

        // ---------- endings ----------

        void die() {
            if (dying) return;
            dying = true;
            stopMusicAll();
            contact = false;
            bar.removeAll();
            sound(pos.toLocation(world), "death", 4f, 1f);
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Demon Eye has been defeated!");
            clearMinions();
            rewardFighters();
        }

        /** Spins faster and faster, shrinks, and bursts. */
        void deathTick() {
            deathTicks++;
            roll += 0.15f + deathTicks * 0.012f;
            pos.add(new Vector(0, -0.03, 0));
            float base = (float) cfg("model-scale", 3.0);
            float s = Math.max(0.05f, base * (1 - deathTicks / 60f));
            model.setInterpolationDelay(0);
            model.setTransformation(new Transformation(new Vector3f(), facing(roll), new Vector3f(s, s, s), new Quaternionf()));
            model.teleport(pos.toLocation(world));
            if (deathTicks % 3 == 0) bloodBurst(pos, 15);
            if (deathTicks >= 60) {
                world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 3, 0.5, 0.5, 0.5, 0);
                bloodBurst(pos, 120);
                removeEverything();
                eye = null;
            }
        }

        /**
         * BUG FIX: this can be triggered mid-loop (a projectile kills the last fighter),
         * so it only flags; the actual cleanup runs at the start of the next tick.
         * Clearing the lists here threw a ConcurrentModificationException.
         */
        void leave(String message) {
            if (leaving || dying) return;
            leaving = true;
            stopMusicAll();
            contact = false;
            Bukkit.broadcastMessage(message);
        }

        /** Rises up and away, then vanishes. */
        void leaveTick() {
            leaveTicks++;
            if (leaveTicks == 1) {
                bar.removeAll();
                clearMinions();
                if (hitbox.isValid()) hitbox.remove();
            }
            pos.add(new Vector(0, 0.6, 0));
            if (model.isValid()) model.teleport(pos.toLocation(world));
            if (leaveTicks >= 40) {
                removeEverything();
                eye = null;
            }
        }

        void clearMinions() {
            for (Servant s : servants) killServant(s, false);
            servants.clear();
            for (Shot s : shots) s.display.remove();
            shots.clear();
        }

        void removeEverything() {
            stopMusicAll();
            clearMinions();
            bar.removeAll();
            if (hitbox.isValid()) hitbox.remove();
            if (model.isValid()) model.remove();
        }

        /**
         * The loot bursts out of the eye where it died and falls to the ground (like a
         * Terraria boss), instead of appearing in inventories. Each fighter's share is
         * locked to them, so only they can pick it up. XP drops as orbs.
         */
        void rewardFighters() {
            Location at = pos.toLocation(world);
            String spot = "at:" + world.getName() + "," + at.getX() + "," + at.getY() + "," + at.getZ();
            int orbs = 0;
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                console("givemythicbag " + (int) cfg("rewards.mythic-bags", 10) + " " + p.getName() + " " + spot, p, at);
                if (random.nextDouble() < cfg("rewards.ankh-piece-chance", 0.5)) // a piece of the Ankh Shield
                    console("giveaccessory ankhpiece 1 " + p.getName() + " " + spot, p, at);
                for (int i = 0; i < 2; i++) console("zraid omen " + (random.nextBoolean() ? 4 : 5) + " 1 " + p.getName() + " " + spot, p, at);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, 12 + random.nextInt(5)));
                if (random.nextDouble() < cfg("rewards.netherite-chance", 0.5)) dropLocked(at, p, new ItemStack(Material.NETHERITE_INGOT, 1 + random.nextInt(3)));
                // the Demon Eye's own gear
                dropLocked(at, p, unholyArrows(32 + random.nextInt(33)));
                if (random.nextDouble() < cfg("rewards.shield-chance", 0.25)) dropLocked(at, p, eyeShield());
                if (random.nextDouble() < cfg("rewards.twin-staff-chance", 0.20)) dropLocked(at, p, twinEyeStaff());
                orbs += (int) cfg("rewards.xp", 1000);
                p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "DEMON EYE DEFEATED"),
                        legacy(ChatColor.GRAY + "Your loot dropped where it fell. Only you can pick it up."),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            }
            for (int left = orbs; left > 0; ) { // XP rains down as orbs
                int amount = Math.min(left, 100);
                left -= amount;
                org.bukkit.entity.ExperienceOrb orb = world.spawn(at, org.bukkit.entity.ExperienceOrb.class);
                orb.setExperience(amount);
            }
        }
    }

    // ===================== DEMON EYE GEAR: TWIN EYE STAFF, SHIELD, UNHOLY ARROWS =====================

    private NamespacedKey staffKey, shieldKey, arrowKey;

    ItemStack twinEyeStaff() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "twin_eye_staff"));
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Twin Eye Staff");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click to summon two small eyes", ChatColor.GRAY + "that fight for you for 30 seconds.",
                ChatColor.DARK_GRAY + "60 second cooldown. From the Demon Eye."));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(staffKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Shields can't take custom textures without changing every shield, so it's a banner design: red with a white eye. */
    ItemStack eyeShield() {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.ShieldMeta shield) {
            shield.setBaseColor(org.bukkit.DyeColor.RED);
            shield.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.WHITE, org.bukkit.block.banner.PatternType.CIRCLE));
            shield.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.BLACK, org.bukkit.block.banner.PatternType.BORDER));
        }
        meta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Shield of the Demon Eye");
        meta.setLore(List.of(ChatColor.GRAY + "Press F while holding it to dash.",
                ChatColor.GREEN + "Players you slam into get knocked back.", ChatColor.DARK_GRAY + "20 second cooldown. From the Demon Eye."));
        meta.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack unholyArrows(int amount) {
        ItemStack item = new ItemStack(Material.ARROW, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "unholy_arrow"));
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Unholy Arrow");
        meta.setLore(List.of(ChatColor.GREEN + "Deals 2x the damage of a normal arrow", ChatColor.DARK_GRAY + "From the Demon Eye."));
        meta.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean tagged(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte tag = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return tag != null && tag == (byte) 1;
    }

    // ---------- Twin Eye Staff ----------

    static final String TWIN_EYE_TAG = "faultline_twin_eye";

    private class Minion {
        final ItemDisplay display;
        final Slime hitbox; // invisible, so other players can hit the eye off
        final UUID owner;
        Vector pos, vel = new Vector();
        final int index;
        long nextHit;
        Minion(Vector pos, int index, World world, UUID owner) {
            this.pos = pos;
            this.index = index;
            this.owner = owner;
            this.display = spawnDisplay(pos.toLocation(world), "demon_eye", (float) cfg("twin-staff.eye-scale", 0.55), 1, Display.Billboard.FIXED);
            this.hitbox = (Slime) world.spawnEntity(pos.toLocation(world), EntityType.SLIME, false);
            hitbox.setSize(1); // small: about the size of the eye (size 1 slimes never split or deal touch damage)
            setupHitbox(hitbox, cfg("twin-staff.eye-health", 10), TWIN_EYE_TAG, "Twin Eye");
            proxy(hitbox, display, EntityType.GUARDIAN, 0.5);
        }
    }

    private Minion minionFor(Entity hitbox) {
        for (List<Minion> list : minions.values()) for (Minion m : list) if (m.hitbox.equals(hitbox)) return m;
        return null;
    }

    /** The owner's own hits pass through their eyes; anyone else can knock them out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTwinEyeHit(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(TWIN_EYE_TAG)) return;
        Minion m = minionFor(event.getEntity());
        if (m == null) { event.setCancelled(true); return; }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) { event.setCancelled(true); return; } // no fall/suffocation/etc.
        Entity d = byEntity.getDamager();
        Player attacker = d instanceof Player pl ? pl : d instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        // Only OTHER players (and their arrows) can knock eyes out. Iron Golems attack slimes on sight,
        // and Necromancer zombies target hostile mobs, so any other source is ignored.
        if (attacker == null || attacker.getUniqueId().equals(m.owner)) { event.setCancelled(true); return; }
        m.display.setGlowColorOverride(Color.RED);
        m.display.setGlowing(true);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (m.display.isValid()) m.display.setGlowing(false); }, 3L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTwinEyeDeath(EntityDeathEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(TWIN_EYE_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        Minion m = minionFor(event.getEntity());
        if (m == null) return;
        List<Minion> list = minions.get(m.owner);
        list.remove(m);
        m.display.getWorld().spawnParticle(Particle.DUST, m.display.getLocation(), 20, 0.25, 0.25, 0.25, 0,
                new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.4f));
        m.display.getWorld().playSound(m.display.getLocation(), org.bukkit.Sound.ENTITY_SLIME_SQUISH, 1f, 0.7f);
        m.display.remove();
        Player owner = Bukkit.getPlayer(m.owner);
        if (owner != null) owner.sendActionBar(legacy(ChatColor.RED + (list.isEmpty() ? "Both of your eyes were knocked out!" : "One of your eyes was knocked out!")));
        if (list.isEmpty()) dismissMinions(m.owner); // the cooldown still applies
    }
    private final Map<UUID, List<Minion>> minions = new HashMap<>();
    private final Map<UUID, Long> minionsUntil = new HashMap<>();
    private final Map<UUID, Long> staffCooldown = new HashMap<>();
    private final Map<UUID, UUID> lastOpponent = new HashMap<>();
    private final Map<UUID, Long> lastOpponentAt = new HashMap<>();

    @EventHandler
    public void onStaffUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!tagged(event.getItem(), staffKey)) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        long cd = staffCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (cd > now) {
            p.sendMessage(ChatColor.RED + "The staff is recharging: " + ((cd - now + 999) / 1000) + "s left.");
            return;
        }
        dismissMinions(p.getUniqueId());
        List<Minion> pair = new ArrayList<>();
        for (int i = 0; i < 2; i++) pair.add(new Minion(p.getEyeLocation().toVector().add(new Vector(i == 0 ? 1 : -1, 0.5, 0)), i, p.getWorld(), p.getUniqueId()));
        minions.put(p.getUniqueId(), pair);
        minionsUntil.put(p.getUniqueId(), now + (long) (cfg("twin-staff.seconds", 30) * 1000));
        staffCooldown.put(p.getUniqueId(), now + (long) (cfg("twin-staff.cooldown-seconds", 60) * 1000));
        sound(p.getLocation(), "summon", 0.8f, 1.8f);
        p.sendMessage(ChatColor.RED + "Two small eyes open beside you...");
    }

    /** Remember who each player is fighting, so the eyes know who to go after. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpTag(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player a ? a
                : event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker.equals(victim)) return;
        long now = System.currentTimeMillis();
        lastOpponent.put(attacker.getUniqueId(), victim.getUniqueId());
        lastOpponentAt.put(attacker.getUniqueId(), now);
        lastOpponent.put(victim.getUniqueId(), attacker.getUniqueId());
        lastOpponentAt.put(victim.getUniqueId(), now);
    }

    private void dismissMinions(UUID owner) {
        List<Minion> list = minions.remove(owner);
        minionsUntil.remove(owner);
        if (list == null) return;
        for (Minion m : list) {
            if (m.hitbox.isValid()) m.hitbox.remove();
            if (m.display.isValid()) {
                m.display.getWorld().spawnParticle(Particle.DUST, m.display.getLocation(), 12, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.2f));
                m.display.remove();
            }
        }
    }

    /** Every tick: eyes orbit you, and dive at the player you're fighting or the nearest hostile mob. */
    private void minionTick() {
        long now = System.currentTimeMillis();
        for (UUID owner : new ArrayList<>(minions.keySet())) {
            Player p = Bukkit.getPlayer(owner);
            if (p == null || p.isDead() || now >= minionsUntil.getOrDefault(owner, 0L)) {
                dismissMinions(owner);
                continue;
            }
            LivingEntity target = minionTarget(p, now);
            for (Minion m : minions.get(owner)) {
                if (!m.display.isValid() || !m.display.getWorld().equals(p.getWorld())) {
                    m.pos = p.getEyeLocation().toVector();
                    continue;
                }
                Vector goal;
                double speed;
                if (target != null) {
                    goal = target.getLocation().toVector().add(new Vector(0, target.getHeight() * 0.6, 0));
                    speed = cfg("twin-staff.dive-speed", 0.8);
                } else {
                    double a = now / 400.0 + m.index * Math.PI;
                    goal = p.getEyeLocation().toVector().add(new Vector(Math.cos(a) * 1.6, 0.6, Math.sin(a) * 1.6));
                    speed = 0.6;
                }
                Vector want = goal.clone().subtract(m.pos);
                if (want.length() > speed) want.normalize().multiply(speed);
                m.vel.add(want.subtract(m.vel).multiply(0.3));
                m.pos.add(m.vel);
                if (target != null && now >= m.nextHit && target.getLocation().toVector().add(new Vector(0, target.getHeight() * 0.6, 0)).distanceSquared(m.pos) < 1.4 * 1.4) {
                    target.damage(cfg("twin-staff.damage", 4), p); // dealt as the owner, so no-PvP zones still apply
                    m.nextHit = now + 800;
                    m.vel = m.vel.clone().multiply(-0.7).add(new Vector(0, 0.3, 0)); // bounce back off
                }
                Location at = m.pos.toLocation(p.getWorld());
                Vector face = target != null ? target.getLocation().toVector().subtract(m.pos) : m.vel.clone();
                if (face.lengthSquared() > 0.001) at.setDirection(face);
                m.display.teleport(at);
                if (m.hitbox.isValid()) m.hitbox.teleport(at.clone().subtract(0, 0.26, 0));
            }
        }
    }

    private LivingEntity minionTarget(Player owner, long now) {
        UUID opp = lastOpponent.get(owner.getUniqueId());
        if (opp != null && now - lastOpponentAt.getOrDefault(owner.getUniqueId(), 0L) < 15000) {
            Player enemy = Bukkit.getPlayer(opp);
            if (enemy != null && !enemy.isDead() && enemy.getWorld().equals(owner.getWorld())
                    && enemy.getLocation().distanceSquared(owner.getLocation()) < 24 * 24) return enemy;
        }
        LivingEntity best = null;
        double bestDist = 16 * 16;
        for (Entity e : owner.getNearbyEntities(16, 8, 16)) {
            if (!(e instanceof org.bukkit.entity.Enemy) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            if (e.getScoreboardTags().contains("faultline_summon") || e.getScoreboardTags().contains(TWIN_EYE_TAG)) continue; // other players' minions
            double d = e.getLocation().distanceSquared(owner.getLocation());
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    // ---------- Shield of the Demon Eye: F to dash ----------

    private final Map<UUID, Long> dashCooldown = new HashMap<>();
    private final Map<UUID, Long> dashingUntil = new HashMap<>();
    private final Map<UUID, Set<UUID>> dashHits = new HashMap<>();
    private boolean dashDealing, dashLanded;

    @EventHandler(ignoreCancelled = true)
    public void onShieldDash(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (p.isSneaking()) return; // Shift + F stays free (Moon Stone bat form)
        if (!tagged(p.getInventory().getItemInMainHand(), shieldKey) && !tagged(p.getInventory().getItemInOffHand(), shieldKey)) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        long cd = dashCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (cd > now) {
            p.sendActionBar(legacy(ChatColor.RED + "Shield dash: " + ((cd - now + 999) / 1000) + "s"));
            return;
        }
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) return;
        p.setVelocity(dir.normalize().multiply(cfg("shield.dash-speed", 1.8)).setY(0.25));
        dashCooldown.put(p.getUniqueId(), now + (long) (cfg("shield.cooldown-seconds", 20) * 1000));
        dashingUntil.put(p.getUniqueId(), now + 600);
        dashHits.put(p.getUniqueId(), new HashSet<>());
        sound(p.getLocation(), "dash", 1.2f, 1.3f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0,
                new Particle.DustOptions(Color.fromRGB(160, 0, 0), 1.3f));
    }

    /** Every tick: slamming into a player during the dash knocks them back. */
    private void dashTick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(dashingUntil.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || now > dashingUntil.get(id)) {
                dashingUntil.remove(id);
                dashHits.remove(id);
                continue;
            }
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 3, 0.3, 0.4, 0.3, 0,
                    new Particle.DustOptions(Color.fromRGB(130, 0, 0), 1.1f));
            for (Entity e : p.getNearbyEntities(1.2, 1.5, 1.2)) {
                if (!(e instanceof Player victim) || victim.equals(p) || !dashHits.get(id).add(victim.getUniqueId())) continue;
                // hit through the normal damage event, so no-PvP zones and teams can stop it
                dashDealing = true;
                dashLanded = false;
                try {
                    victim.damage(cfg("shield.dash-damage", 2), p);
                } finally {
                    dashDealing = false;
                }
                if (!dashLanded) continue;
                Vector push = victim.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = p.getLocation().getDirection().setY(0);
                victim.setVelocity(push.normalize().multiply(cfg("shield.knockback", 1.5)).setY(0.45));
                victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDashLanded(EntityDamageByEntityEvent event) {
        if (dashDealing) dashLanded = true; // it got through protection plugins
    }

    // ---------- Unholy Arrows: 2x damage + a purple trail ----------

    private final Set<UUID> unholyInFlight = new HashSet<>();

    @EventHandler(ignoreCancelled = true)
    public void onShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (!tagged(event.getConsumable(), arrowKey) || !(event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        arrow.setDamage(arrow.getDamage() * cfg("unholy-arrow.damage-multiplier", 2.0));
        arrow.setItemStack(unholyArrows(1)); // picking it back up gives an Unholy Arrow
        unholyInFlight.add(arrow.getUniqueId());
    }

    private void arrowTick() {
        unholyInFlight.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof org.bukkit.entity.AbstractArrow arrow) || !arrow.isValid() || arrow.isInBlock()) return true;
            arrow.getWorld().spawnParticle(Particle.DUST, arrow.getLocation(), 2, 0.05, 0.05, 0.05, 0,
                    new Particle.DustOptions(Color.fromRGB(150, 50, 200), 1.0f));
            return false;
        });
    }

    // ---------- keep these out of crafting / furnaces ----------

    @EventHandler
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            boolean idolRecipe = event.getRecipe() instanceof org.bukkit.Keyed k && k.getKey().equals(idolRecipeKey);
            if ((tagged(ingredient, talonKey) && !idolRecipe) || tagged(ingredient, idolKey) || tagged(ingredient, armbandKey) || tagged(ingredient, lorenzoBallKey)
                    || tagged(ingredient, staffKey) || tagged(ingredient, shieldKey) || tagged(ingredient, summonKey)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFuel(org.bukkit.event.inventory.FurnaceBurnEvent event) {
        if (tagged(event.getFuel(), staffKey)) event.setCancelled(true);
    }

    // ===================== FROSTBEARD THE SNOW WIZARD (internally still "mortimer": config, tags, saved data) =====================
    //
    // Spawns in snow biomes (and anywhere snowy during a Snowy Day): every 30s each player
    // in the snow has a 0.5% chance; he snuffs out a torch near them and appears from it.
    // 600 health but very tanky (40% damage reduction, thick armor, no knockback).
    // Phase 1 WIZARD: Icicle Runners, Winter Whale slam, crystal-ball cards (the pink one
    //   can be parried: block it with a shield and it flies back into him).
    // Phase 2 SNOW MONSTER (<66%): rolls, ground-pounds (ice spikes erupt), fridge form lobs
    //   ice cubes that shatter into smaller cubes.
    // Phase 3 SNOWFLAKE (<33%): rings of ice shards, icicle rain, a sweeping freezing beam.

    static final String MORT_TAG = "faultline_mortimer";
    static final String RUNNER_TAG = "faultline_icicle_runner";
    static final String MORT_ORB_TAG = "faultline_mortimer_orb";
    private Mortimer mortimer;
    private long mortimerCooldownUntil;
    private NamespacedKey mortEggKey;

    /** Admin/test spawn egg (in /itemsmenu): right-click a block to summon Mortimer there. */
    ItemStack mortimerEgg() {
        ItemStack item = new ItemStack(Material.SNOW_GOLEM_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard Spawn Egg");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to summon", ChatColor.GRAY + "Frostbeard there."));
        meta.getPersistentDataContainer().set(mortEggKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMortimerEgg(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tagged(event.getItem(), mortEggKey)) return;
        event.setUseInteractedBlock(Result.DENY); // never a real Snow Golem, never changes a spawner
        event.setUseItemInHand(Result.DENY);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player p = event.getPlayer();
        if (mortimer != null) { p.sendMessage(ChatColor.RED + "Frostbeard is already here."); return; }
        summonMortimer(event.getClickedBlock().getRelative(event.getBlockFace()), p);
        if (mortimer == null) return; // blocked (e.g. no mob spawning here)
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        } else {
            p.sendMessage(ChatColor.GRAY + "(Fight him in survival; creative players don't count as fighters.)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMortimerEggDispense(org.bukkit.event.block.BlockDispenseEvent event) {
        if (tagged(event.getItem(), mortEggKey)) event.setCancelled(true); // no dispenser tricks
    }

    private double mcfg(String key, double def) {
        return getConfig().getDouble("mortimer." + key, def);
    }

    private boolean snowyDay() {
        if (Bukkit.getWorlds().isEmpty()) return false;
        String active = Bukkit.getWorlds().get(0).getPersistentDataContainer()
                .get(new NamespacedKey("faultlineevents", "active_event"), PersistentDataType.STRING);
        return "SNOWY_DAY".equals(active);
    }

    private boolean inTheSnow(Location loc) {
        String biome = loc.getBlock().getBiome().getKey().getKey();
        if (biome.contains("desert") || biome.contains("badlands")) return false;
        return loc.getBlock().getTemperature() < 0.15 || snowyDay();
    }

    /** Every 30 seconds: each player in the snow rolls; on a hit he snuffs a torch near them. */
    private void mortimerSpawnTick() {
        if (mortimer != null || System.currentTimeMillis() < mortimerCooldownUntil) return;
        if (!getConfig().getBoolean("mortimer.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL || !inTheSnow(p.getLocation())) continue;
            if (random.nextDouble() >= mcfg("spawn-chance", 0.005)) continue;
            Block torch = findTorch(p.getLocation(), (int) mcfg("torch-radius", 16));
            if (torch == null) continue; // he only comes where someone has lit a torch
            summonMortimer(torch, p);
            return;
        }
    }

    private Block findTorch(Location center, int r) {
        List<Block> found = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int y = -6; y <= 6; y++) for (int z = -r; z <= r; z++) {
            Block b = center.getBlock().getRelative(x, y, z);
            Material t = b.getType();
            if (t == Material.TORCH || t == Material.WALL_TORCH || t == Material.SOUL_TORCH || t == Material.SOUL_WALL_TORCH) found.add(b);
        }
        return found.isEmpty() ? null : found.get(random.nextInt(found.size()));
    }

    void summonMortimer(Block torch, Player near) {
        Location at = torch.getLocation().add(0.5, 0.2, 0.5);
        if (torch.getType().name().contains("TORCH")) {
            Material drop = torch.getType().name().contains("SOUL") ? Material.SOUL_TORCH : Material.TORCH;
            torch.setType(Material.AIR);
            at.getWorld().dropItemNaturally(at, new ItemStack(drop)); // nobody loses their torch
        }
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 80, 0.8, 1.2, 0.8, 0.05);
        at.getWorld().playSound(at, org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.6f);
        at.getWorld().playSound(at, org.bukkit.Sound.BLOCK_BELL_USE, 2f, 0.7f);
        mortimer = new Mortimer(at.clone().add(0, 3, 0), near);
        if (!mortimer.hitbox.isValid()) { // e.g. WorldGuard blocks mob spawning here
            mortimer.removeEverything();
            mortimer = null;
            return;
        }
        Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard" + ChatColor.WHITE + " has snuffed out a torch near "
                + near.getName() + "... and the air turns bitter cold!");
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 96 * 96) continue;
            p.showTitle(Title.title(legacy(ChatColor.AQUA + "" + ChatColor.BOLD + "FROSTBEARD"),
                    legacy(ChatColor.WHITE + "Your little fire won't save you now."),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        }
    }

    /** A simulated projectile: cards, ice cubes, shards, icicles. */
    private class MShot {
        final ItemDisplay display;
        final String kind; // card, pink, cube, small, shard, icicle, parried
        Vector pos, vel;
        double gravity, damage;
        int life, maxLife;
        Guard guard = Guard.BLOCKABLE;
        UUID owner; // who parried it
        Slime hitbox; // pink orbs: hit it (sword, arrow, anything) to send it back into Mortimer
        MShot(String kind, String model, float scale, Vector pos, Vector vel, double gravity, double damage, int maxLife, boolean sprite) {
            this.kind = kind; this.pos = pos; this.vel = vel; this.gravity = gravity; this.damage = damage; this.maxLife = maxLife;
            this.display = spawnDisplay(pos.toLocation(mortimer.world), model, scale, 1, sprite ? Display.Billboard.CENTER : Display.Billboard.FIXED);
        }
    }

    private class Mortimer {
        final World world;
        final Slime hitbox;
        final ItemDisplay model;
        final BossBar bar;
        Vector pos, vel = new Vector(), lastLook = new Vector(0, 0, 1);
        int phase = 1, attack = -1, t, ticksAlive, transition, lonely, deathTicks, hurtFlash;
        boolean dying, leaving, contact, spinning;
        float roll, tumble;
        final Set<UUID> fighters = new HashSet<>(), down = new HashSet<>();
        final Map<UUID, Integer> hitCooldown = new HashMap<>();
        final List<MShot> shots = new ArrayList<>();
        final List<LivingEntity> runners = new ArrayList<>();
        // each Runner's ice model, kept separately: if a Runner vanishes any way at all, its model goes too
        final Map<LivingEntity, ItemDisplay> runnerLooks = new HashMap<>();
        final List<ItemDisplay> props = new ArrayList<>();   // whale, spikes: cleaned up with the fight
        // per-attack scratch
        Vector spot, dir;
        int count;
        ItemDisplay whale;
        UUID target;

        Mortimer(Location at, Player near) {
            this.world = at.getWorld();
            this.pos = at.toVector();
            this.hitbox = (Slime) world.spawnEntity(at, EntityType.SLIME, false);
            hitbox.setSize(3); // resized per form with the SCALE attribute (setSize would reset health)
            setupHitbox(hitbox, mcfg("health", 700), MORT_TAG, "Frostbeard");
            AttributeInstance armor = hitbox.getAttribute(Attribute.ARMOR);
            if (armor != null) armor.setBaseValue(mcfg("armor", 15));
            AttributeInstance tough = hitbox.getAttribute(Attribute.ARMOR_TOUGHNESS);
            if (tough != null) tough.setBaseValue(mcfg("armor-toughness", 8));
            setHitboxScale(1.6);
            this.model = spawnDisplay(at, "mf_wizard", (float) mcfg("wizard-scale", 2.2), 2, Display.Billboard.FIXED);
            proxy(hitbox, model, EntityType.EVOKER, mcfg("bedrock.wizard-scale", 1.6));
            this.bar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard", BarColor.BLUE, BarStyle.SEGMENTED_12);
            for (Player p : world.getPlayers()) {
                if (survival(p) && p.getLocation().distanceSquared(at) <= 48 * 48) fighters.add(p.getUniqueId());
            }
            if (survival(near)) fighters.add(near.getUniqueId());
        }

        void setHitboxScale(double s) {
            AttributeInstance scale = hitbox.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(s);
        }

        double hitboxHalf() { return 0.78 * (hitbox.getAttribute(Attribute.SCALE) == null ? 1 : hitbox.getAttribute(Attribute.SCALE).getValue()); }

        // ---------- fighters (same rules as the Demon Eye) ----------
        void join(Player p) {
            if (!survival(p)) return;
            fighters.add(p.getUniqueId());
            down.remove(p.getUniqueId());
        }

        void fighterDied(Player p) {
            if (!fighters.contains(p.getUniqueId()) || dying || leaving) return;
            down.add(p.getUniqueId());
            if (active().isEmpty()) leave(ChatColor.AQUA + "Frostbeard tips his hat. \"Frozen solid. How disappointing.\"");
        }

        List<Player> active() {
            List<Player> list = new ArrayList<>();
            for (UUID id : fighters) {
                if (down.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead() || !survival(p) || !p.getWorld().equals(world)) continue;
                if (p.getLocation().toVector().distanceSquared(pos) > 100 * 100) continue;
                list.add(p);
            }
            return list;
        }

        Player pick() {
            List<Player> a = active();
            if (a.isEmpty()) return null;
            Player p = a.get(random.nextInt(a.size()));
            target = p.getUniqueId();
            return p;
        }

        Player current() {
            Player p = target == null ? null : Bukkit.getPlayer(target);
            return p != null && active().contains(p) ? p : pick();
        }

        Vector eye(Player p) { return p.getLocation().toVector().add(new Vector(0, 1.2, 0)); }

        /**
         * The floor at a spot, searched from just above the given height DOWNWARD (never from
         * above your head, so it can't pick a roof or tree canopy). NaN if the spot is inside
         * solid blocks or there's no floor within 6 blocks below.
         */
        double floorAt(double x, double z, double feetY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), start = (int) Math.floor(feetY + 0.5);
            if (world.getBlockAt(bx, start, bz).getType().isSolid() && world.getBlockAt(bx, start + 1, bz).getType().isSolid()) return Double.NaN;
            for (int y = start; y > start - 6; y--) {
                if (world.getBlockAt(bx, y - 1, bz).getType().isSolid() && !world.getBlockAt(bx, y, bz).getType().isSolid()) return y;
            }
            return Double.NaN;
        }

        /** The ground under a point (for the snow monster and slams). */
        double groundY(double x, double z, double fromY) {
            // BUG FIX: this only looked down from 4 blocks above, so inside anything taller (a castle wall, a hill)
            // it never saw the top and said "stay here": the Snow Brute ended up embedded in walls. And over a drop
            // deeper than 24 blocks it said "stay here" too, so he floated in mid-air. Now: from inside blocks it
            // climbs to the top; from the air it finds the floor below; with no floor in reach, he sinks (falls).
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            int min = world.getMinHeight(), max = world.getMaxHeight() - 1;
            y = Math.max(min, Math.min(max, y));
            if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                for (int up = 0; up < 80 && y < max; up++, y++) if (!world.getBlockAt(bx, y + 1, bz).getType().isSolid()) return y + 1;
                return fromY;
            }
            for (int down = 0; down < 40 && y > min; down++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
            return fromY - 1.5; // nothing below within reach: keep falling
        }

        // ---------- main loop ----------
        void tick() {
            ticksAlive++;
            if (dying) { deathTick(); return; }
            if (leaving) { leaveTick(); return; }
            if (!hitbox.isValid() || !model.isValid()) {
                removeEverything();
                mortimer = null;
                return;
            }
            double engage = mcfg("engage-radius", 48);
            // BUG FIX: nearby players only used to be added while he already had a fighter, so with
            // none (summoned in creative, arrived late, or back after dying) he froze in the air
            // with no target, and nobody could reach him to get "back in".
            for (Player p : world.getPlayers()) {
                if (survival(p) && !p.isDead() && p.getLocation().toVector().distanceSquared(pos) < engage * engage) join(p);
            }
            List<Player> a = active();
            if (a.isEmpty()) {
                vel.multiply(0.8); // settle in place instead of drifting on old momentum
                if (++lonely > 400) { leave(ChatColor.AQUA + "Frostbeard grows bored and vanishes in a flurry of snow."); return; }
            } else {
                lonely = 0;
            }
            part("phase", this::checkPhase);
            // BUG FIX: an error partway through a move froze him FOREVER (the move's timer only advances at its end,
            // so the same step failed every tick, and nothing after it ever ran: still glowing, hanging in the air).
            try {
                if (transition > 0) transitionTick();
                else {
                    Player target = current();
                    if (target != null) attackTick(target);
                }
                failStreak = 0;
            } catch (RuntimeException e) {
                logPart("move " + attack + " (tick " + t + ")", e);
                contact = false; tumble = 0; hopOffset = 0;
                next();
                if (++failStreak > 100) { leave(ChatColor.AQUA + "Frostbeard vanishes in a flurry of snow."); return; }
            }
            pos.add(vel);
            if (contact) part("ram", () -> ramDamage(a));
            part("projectiles", () -> tickShots(a));
            part("progress watchdog", () -> progressWatchdog(a));
            // BUG FIX: Runners that vanished (despawned, /kill, unloaded) were dropped from this list, but
            // their ice models were left standing in the world, frozen, forever.
            for (LivingEntity r : new ArrayList<>(runners)) {
                if (r.isValid() && !r.isDead()) continue;
                ItemDisplay look = runnerLooks.remove(r);
                if (look != null && look.isValid()) look.remove();
                runners.remove(r);
            }
            part("drawing", this::render);
            hitCooldown.replaceAll((k, v) -> v - 1);
            hitCooldown.values().removeIf(v -> v <= 0);
            if (hurtFlash > 0 && --hurtFlash == 0) model.setGlowing(false);
            if (poseTicks > 0 && --poseTicks == 0 && phase == 1) setModel("mf_wizard", mcfg("wizard-scale", 2.2));
            part("boss bar", this::updateBar);
            if (ticksAlive % 10 == 0) part("music", this::musicTick);
        }

        int failStreak, lastClose, bonks;
        final Map<String, Long> partErrorLog = new HashMap<>();

        void part(String what, Runnable r) {
            try { r.run(); } catch (RuntimeException e) { logPart(what, e); }
        }

        void logPart(String what, RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - partErrorLog.getOrDefault(what, 0L) < 30000) return;
            partErrorLog.put(what, now);
            getLogger().log(java.util.logging.Level.SEVERE, "[Frostbeard] error in " + what + " (skipped, he keeps going; please report this):", e);
        }

        /** Nowhere near any fighter for 12 seconds (stuck behind a wall, fell off somewhere): he blinks back beside one. */
        void progressWatchdog(List<Player> a) {
            if (a.isEmpty() || transition > 0) { lastClose = ticksAlive; return; }
            boolean lost = pos.getY() < world.getMinHeight() - 5;
            // BUG FIX: "close" used to be just within 16 blocks, even THROUGH a wall, so hovering inside the castle wall
            // or bonking into it from the other side never counted as stuck. Now he also has to be able to see you.
            if (!lost) for (Player p : a) if (eye(p).distanceSquared(pos) < 16 * 16 && canSee(p)) { lastClose = ticksAlive; return; }
            if (!lost && ticksAlive - lastClose < 240) return;
            lastClose = ticksAlive;
            blinkNear(a.get(random.nextInt(a.size())), "was stuck (walled off or out of reach) for 12s");
        }

        /** A clear line from him to your eyes (he isn't inside a wall or behind one). */
        boolean canSee(Player p) {
            Vector from = pos.clone(), to = eye(p);
            Vector d = to.clone().subtract(from);
            double len = d.length();
            if (len < 0.5) return true;
            if (world.getBlockAt(from.toLocation(world)).getType().isSolid()) return false; // he's inside a block
            return world.rayTraceBlocks(from.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null;
        }

        /** Reappears beside a fighter in a puff of snow. */
        void blinkNear(Player f, String why) {
            Location feet = f.getLocation();
            Vector side = feet.getDirection().setY(0);
            if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
            side.normalize();
            // somewhere he can actually see you from (5 blocks out, then 2.5, then right beside you): never behind a wall again
            Vector spot = null; double floor = feet.getY();
            for (double reach : new double[]{5, 2.5, 1.2}) {
                for (Vector dirv : new Vector[]{side, side.clone().multiply(-1), new Vector(-side.getZ(), 0, side.getX()), new Vector(side.getZ(), 0, -side.getX())}) {
                    Vector c = feet.toVector().add(dirv.clone().multiply(reach));
                    double fl = floorAt(c.getX(), c.getZ(), feet.getY());
                    if (Double.isNaN(fl) || Math.abs(fl - feet.getY()) > 2.5) continue;
                    Vector body = c.clone().setY(phase == 2 ? fl + 0.1 + hitboxHalf() : fl + 4);
                    if (openSpot(body) && clearLine(body, eye(f))) { spot = c; floor = fl; break; }
                }
                if (spot != null) break;
            }
            if (spot == null) spot = feet.toVector();
            world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 60, 1, 1.5, 1, 0.1);
            pos = spot.setY(phase == 2 ? floor + 0.1 : floor + 4);
            vel = new Vector();
            contact = false; tumble = 0; hopOffset = 0;
            if (phase == 2 && !"mf_snow_monster".equals(currentModel)) setModel("mf_snow_monster", mcfg("monster-scale", 3.0));
            world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 60, 1, 1.5, 1, 0.1);
            world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.8f);
            getLogger().info("[Frostbeard] " + why + ": brought him back next to " + f.getName() + ".");
            bonks = 0;
            next();
        }

        /** Is there a wall (2+ blocks tall) right in front of him at this spot? */
        boolean wallAhead(Vector from, Vector direction) {
            Vector ahead = from.clone().add(direction.clone().multiply(1.6));
            return world.getBlockAt(ahead.clone().add(new Vector(0, 1.0, 0)).toLocation(world)).getType().isSolid()
                    && world.getBlockAt(ahead.clone().add(new Vector(0, 2.0, 0)).toLocation(world)).getType().isSolid();
        }

        void fly(Vector goal, double max, double accel) {
            Vector want = goal.clone().subtract(pos);
            if (want.length() > max) want.normalize().multiply(max);
            vel.add(want.subtract(vel).multiply(accel));
        }

        void look(Vector at) {
            Vector d = at.clone().subtract(pos);
            if (d.lengthSquared() > 0.01) lastLook = d.normalize();
        }

        void next() {
            int[] pool = phase == 1 ? new int[]{0, 1, 2, 2, 9, 10} : phase == 2 ? new int[]{3, 4, 5, 11} : new int[]{6, 7, 8, 0, 9, 10};
            int pick;
            do pick = pool[random.nextInt(pool.length)]; while (pick == attack && pool.length > 1);
            if (phase == 2) { // BUG FIX: 3 of his 4 moves stand still and only reach ~13 blocks, so from farther away he
                double near = Double.MAX_VALUE;  // just slammed empty air in place. Far away: roll at you (or lob cubes).
                for (Player p : active()) near = Math.min(near, p.getLocation().toVector().distanceSquared(pos));
                if (near > 13 * 13 && pick != 3 && pick != 5) pick = random.nextInt(3) == 0 ? 5 : 3;
            }
            attack = pick;
            t = 0;
            count = 0;
            contact = false;
            spinning = phase == 3;
            target = null;
            if (phase == 2 && !"mf_snow_monster".equals(currentModel)) setModel("mf_snow_monster", mcfg("monster-scale", 3.0));
        }

        String currentModel = "mf_wizard";
        int poseTicks;

        /** Wizard animation frames: cast (orb raised), spread (summoning), block (mace deflect). */
        void pose(String model, int ticks) {
            if (phase != 1 || transition > 0) return;
            setModel(model, mcfg("wizard-scale", 2.2));
            poseTicks = ticks;
        }
        void setModel(String m, double scale) {
            currentModel = m;
            model.setItemStack(modelItem(m));
            baseScale = (float) scale;
        }
        float baseScale = 2.2f;

        // ---------- attacks ----------
        void attackTick(Player target) {
            if (attack < 0) { next(); return; }
            Vector tp = eye(target);
            switch (attack) {
                // ===== WIZARD =====
                case 0 -> { // Icicle Runners: throws his arms wide, four little ice minions run at you
                    hover(tp, 3);
                    if (t == 2) pose("mf_wizard_spread", 26);
                    if (t == 10) {
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_PHANTOM_FLAP, 2f, 0.5f);
                        int want = 4 - runners.size();
                        for (int i = 0; i < want; i++) spawnRunner(target);
                    }
                    if (t >= 40) next();
                }
                case 1 -> { // Winter Whale: a shadow marks the spot, then the whale slams down
                    hover(tp, 4);
                    if (t == 0) pose("mf_wizard_spread", 22);
                    if (t == 0) {
                        spot = target.getLocation().toVector();
                        spot.setY(groundY(spot.getX(), spot.getZ(), spot.getY()));
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.7f);
                    }
                    if (t < 30 && t % 2 == 0) ring(spot.clone().add(new Vector(0, 0.2, 0)), 3.5, Particle.SNOWFLAKE, 24);
                    if (t == 20) {
                        whale = spawnDisplay(spot.clone().add(new Vector(0, 18, 0)).toLocation(world), "mf_whale", (float) mcfg("whale-scale", 3.0), 2, Display.Billboard.FIXED);
                        props.add(whale);
                    }
                    if (whale != null && t >= 20 && t < 32) {
                        double h = 18 * (1 - (t - 20) / 12.0);
                        Location l = spot.clone().add(new Vector(0, Math.max(0, h), 0)).toLocation(world);
                        l.setPitch(90);
                        whale.teleport(l);
                    }
                    if (t == 32) {
                        world.spawnParticle(Particle.EXPLOSION, spot.toLocation(world), 3, 1, 0.3, 1, 0);
                        world.spawnParticle(Particle.SNOWFLAKE, spot.toLocation(world), 120, 2.5, 0.5, 2.5, 0.2);
                        world.playSound(spot.toLocation(world), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
                        for (Player p : active()) {
                            if (p.getLocation().toVector().distanceSquared(spot) > 4.5 * 4.5) continue;
                            hurt(p, mcfg("whale-damage", 12), hitbox, spot, Guard.UNBLOCKABLE); // a slam: dodge it
                            Vector push = p.getLocation().toVector().subtract(spot).setY(0); // BUG FIX: standing still ON the spot = a zero
                            push = push.lengthSquared() > 0.0001 ? push.normalize().multiply(0.8) : new Vector(); // vector, NaN, an error,
                            p.setVelocity(push.setY(0.7));                                  // and the whale left stuck in the ground
                        }
                    }
                    if (whale != null && t > 32 && t < 52) whale.teleport(spot.clone().add(new Vector(0, -(t - 32) * 0.15, 0)).toLocation(world));
                    if (t >= 52) {
                        if (whale != null) { whale.remove(); props.remove(whale); whale = null; }
                        next();
                    }
                }
                case 2 -> { // Ice orbs: five in a row, one is pink (hit it or block it to send it back)
                    hover(tp, 3);
                    if (t < 12 && t % 2 == 0) world.spawnParticle(Particle.ENCHANT, pos.toLocation(world).add(0, 1, 0), 12, 0.6, 0.6, 0.6, 0.5);
                    if (t >= 12 && (t - 12) % 7 == 0 && count < 5) {
                        boolean pink = count == 2;
                        pose("mf_wizard_cast", 5);  // arm up: he throws from his raised hand
                        Vector from = pos.clone().add(new Vector(0, 1.6, 0)).add(lastLook.clone().multiply(0.8));
                        Vector v = tp.clone().subtract(from).normalize().multiply(mcfg("orb-speed", 0.5));
                        MShot orb = new MShot(pink ? "pink" : "card", pink ? "mf_orb_pink" : "mf_orb", 1.3f, from, v, 0, mcfg("orb-damage", 5), 90, true);
                        if (pink) {
                            orb.hitbox = (Slime) world.spawnEntity(from.toLocation(world), EntityType.SLIME, false);
                            orb.hitbox.setSize(1);
                            setupHitbox(orb.hitbox, 100, MORT_ORB_TAG, "Pink Orb");
                            world.spawnParticle(Particle.DUST, from.toLocation(world), 12, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 120, 200), 1.3f));
                        }
                        shots.add(orb);
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, pink ? 1.8f : 1.2f);
                        count++;
                    }
                    if (count >= 5 && t > 12 + 7 * 5 + 10) next();
                }
                // ===== SNOW MONSTER =====
                case 3 -> { // Roll: curls up and rolls across, one to three times
                    onGround();
                    int rolls = 1 + (int) (random.nextDouble() * 3);
                    if (t == 0) count = rolls;
                    int cycle = t % 45;
                    if (cycle == 0) {
                        dir = target.getLocation().toVector().subtract(pos).setY(0);
                        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                        dir.normalize();
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_SNOW_BREAK, 2f, 0.5f);
                    }
                    if (cycle < 12) {
                        vel.multiply(0.6); tumble = 0; look(tp);
                        if (cycle == 8) setModel("mf_snowball", mcfg("monster-scale", 3.0) * 0.8); // curls up
                    }
                    else if (cycle < 38 && wallAhead(pos, dir)) { // BUG FIX: it used to roll straight THROUGH walls
                        contact = false; vel = dir.clone().multiply(-0.3); tumble = 0;
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_SNOW_BREAK, 2f, 0.4f);
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.2f, 0.6f);
                        world.spawnParticle(Particle.BLOCK, pos.toLocation(world).add(dir.clone().multiply(1.5)).add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0, Material.SNOW_BLOCK.createBlockData());
                        t += 38 - cycle - 1; // bonk: skip to the uncurl
                        // BUG FIX: every roll aims straight at you, so with a wall between you he bonked forever
                        // (and was too close for the old watchdog to notice). Two bonks in a row: he goes around it.
                        if (++bonks >= 2) { blinkNear(target, "kept bonking into a wall between him and " + target.getName()); return; }
                    }
                    else if (cycle < 38) {
                        contact = true;
                        vel = dir.clone().multiply(mcfg("roll-speed", 0.75));
                        tumble += 0.35f;
                        lastLook = dir.clone();
                        if (t % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 8, 1, 0.2, 1, 0.05);
                    } else {
                        contact = false; vel.multiply(0.5); tumble = 0;
                        if (cycle == 38) setModel("mf_snow_monster", mcfg("monster-scale", 3.0)); // uncurls
                    }
                    if (cycle == 37 && !wallAhead(pos, dir)) bonks = 0; // a clean roll resets it
                    if (t >= 45 * count) { tumble = 0; setModel("mf_snow_monster", mcfg("monster-scale", 3.0)); next(); }
                }
                case 4 -> { // Ground pound: hops, slams, and ice spikes erupt outward toward everyone
                    onGround();
                    vel.multiply(0.5);
                    look(tp);
                    if (t == 0) setModel("mf_snow_monster_slam", mcfg("monster-scale", 3.0)); // arms up
                    if (t < 14) hopOffset = (float) Math.sin(t / 14.0 * Math.PI) * 3f;
                    if (t == 14) {
                        setModel("mf_snow_monster", mcfg("monster-scale", 3.0));          // SLAM
                        hopOffset = 0;
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.5f);
                        world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 60, 2, 0.2, 2, 0, Material.SNOW_BLOCK.createBlockData());
                    }
                    if (t >= 14 && (t - 14) % 5 == 0 && count < 4) {
                        count++;
                        for (Player p : active()) {
                            Vector d = p.getLocation().toVector().subtract(pos).setY(0);
                            if (d.lengthSquared() < 0.01) d = new Vector(1, 0, 0);
                            Vector at = pos.clone().add(d.normalize().multiply(3.2 * count));
                            at.setY(groundY(at.getX(), at.getZ(), pos.getY()));
                            spike(at);
                        }
                    }
                    if (t >= 14 + 5 * 4 + 12) next();
                }
                case 5 -> { // Fridge: turns into a fridge and lobs ice cubes that shatter into smaller ones
                    onGround();
                    vel.multiply(0.5);
                    look(tp);
                    if (t == 0) { setModel("mf_fridge", mcfg("fridge-scale", 2.6)); world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_IRON_DOOR_OPEN, 2f, 0.6f); }
                    if (t >= 10 && (t - 10) % 9 == 0 && count < 3 + phaseBonus()) {
                        count++;
                        Player aim = pick();
                        if (aim != null) {
                            Vector from = pos.clone().add(new Vector(0, 2.5, 0));
                            Vector land = aim.getLocation().toVector().add(new Vector(random.nextGaussian(), 0.3, random.nextGaussian()));
                            shots.add(new MShot("cube", "mf_ice_cube", 1.3f, from, lob(from, land, 26, 0.05), 0.05, mcfg("cube-damage", 6), 60, false));
                            world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_SNOWBALL_THROW, 1.5f, 0.5f);
                        }
                    }
                    if (t >= 10 + 9 * 4 + 10) { setModel("mf_snow_monster", mcfg("monster-scale", 3.0)); next(); }
                }
                // ===== SNOWFLAKE =====
                case 6 -> { // Rings of ice shards, rotating each wave
                    hover(tp, 3);
                    if (t >= 10 && (t - 10) % 12 == 0 && count < 3) {
                        count++;
                        int n = (int) mcfg("shards-per-ring", 12);
                        for (int i = 0; i < n; i++) {
                            double a = Math.PI * 2 * i / n + count * 0.26;
                            Vector aim = tp.clone().subtract(pos).setY(0);
                            double dist = Math.max(4, aim.length());
                            Vector v = new Vector(Math.cos(a) * dist, tp.getY() - pos.getY(), Math.sin(a) * dist).normalize().multiply(0.5);
                            shots.add(new MShot("shard", "ice_shard", 1.1f, pos.clone(), v, 0, mcfg("shard-damage", 5), 70, true));
                        }
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 1.4f);
                    }
                    if (count >= 3 && t > 10 + 36 + 15) next();
                }
                case 7 -> { // Icicle rain: warnings under everyone, then icicles fall (block by looking up)
                    hover(tp, 4);
                    if (t == 0) {
                        spots.clear();
                        for (Player p : active()) for (int i = 0; i < 3; i++) {
                            Vector s = p.getLocation().toVector().add(new Vector(random.nextGaussian() * 1.8, 0, random.nextGaussian() * 1.8));
                            s.setY(groundY(s.getX(), s.getZ(), s.getY()));
                            spots.add(s);
                        }
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_POWDER_SNOW_STEP, 2f, 0.5f);
                    }
                    if (t < 24 && t % 3 == 0) for (Vector s : spots) ring(s.clone().add(new Vector(0, 0.15, 0)), 1.1, Particle.SNOWFLAKE, 10);
                    if (t == 24) for (Vector s : spots) {
                        MShot ic = new MShot("icicle", "mf_icicle", 1.6f, s.clone().add(new Vector(0, 12, 0)), new Vector(0, -0.3, 0), 0.06, mcfg("icicle-damage", 7), 60, false);
                        shots.add(ic);
                    }
                    if (t >= 60) next();
                }
                case 8 -> { // Freezing beam: charges, then sweeps toward you
                    hover(tp, 3.5);
                    if (t < 25) {
                        if (t % 2 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 20, 1.5, 1.5, 1.5, -0.1);
                        dir = tp.clone().subtract(pos).normalize();
                        if (t == 0) world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 2f, 1.6f);
                    } else if (t < 60) {
                        Vector want = tp.clone().subtract(pos).normalize();
                        dir = dir.clone().multiply(0.93).add(want.multiply(0.07)).normalize(); // sweeps slowly after you
                        beam(dir);
                    }
                    if (t >= 70) next();
                }
                case 9 -> { // Taunt: lands near you and laughs. Your chance to hit him up close!
                    if (t == 0) {
                        // BUG FIX: the landing spot was found by scanning down from 4 blocks ABOVE you,
                        // so under a tree or a roof he "landed" on the leaves/roof, still up in the air.
                        // Now: 3 blocks from you toward him, on the floor at your feet level; if that
                        // spot is inside a wall or a hill, right beside you instead.
                        Location feet = target.getLocation();
                        Vector toward = pos.clone().subtract(feet.toVector()).setY(0);
                        if (toward.lengthSquared() < 0.01) toward = new Vector(1, 0, 0);
                        spot = feet.toVector().add(toward.normalize().multiply(3));
                        double floor = floorAt(spot.getX(), spot.getZ(), feet.getY());
                        if (Double.isNaN(floor) || Math.abs(floor - feet.getY()) > 2.5) { // wall, hill, or drop: land beside you
                            spot = feet.toVector().add(toward.multiply(1.2));
                            floor = feet.getY();
                        }
                        spot.setY(floor + hitboxHalf() + 0.05);
                    }
                    if (t < 25) fly(spot, 0.5, 0.2);
                    else {
                        vel.multiply(0.5);
                        pos.setY(pos.getY() + (spot.getY() - pos.getY()) * 0.3);
                        if (t == 25) {
                            world.spawnParticle(Particle.BLOCK, pos.toLocation(world).subtract(0, hitboxHalf(), 0), 40, 1, 0.1, 1, 0, Material.SNOW_BLOCK.createBlockData());
                            world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_WITCH_CELEBRATE, 2f, 0.6f);
                            Bukkit.getOnlinePlayers().stream().filter(p -> p.getWorld().equals(world) && p.getLocation().toVector().distanceSquared(pos) < 40 * 40)
                                    .forEach(p -> p.sendActionBar(legacy(ChatColor.AQUA + (phase == 1 ? "Frostbeard lands to taunt you. Hit him!" : "The Blizzard slows down... hit it!"))));
                        }
                        if (t == 50) world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_WITCH_CELEBRATE, 1.6f, 0.75f);
                    }
                    look(tp);
                    if (t >= 25 + (int) mcfg("taunt-ticks", 60)) next();
                }
                case 10 -> { // Hailstorm: hail pours down around everyone for a few seconds (look up and block)
                    hover(tp, phase == 3 ? 5 : 6);
                    if (t == 0) {
                        pose("mf_wizard_spread", 20);
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ITEM_TRIDENT_THUNDER, 1.5f, 1.6f);
                    }
                    if (t >= 10 && t < 70 && t % 2 == 0) {
                        for (Player p : active()) {
                            Vector from = p.getLocation().toVector().add(new Vector(random.nextGaussian() * 3.5, 11 + random.nextDouble() * 3, random.nextGaussian() * 3.5));
                            shots.add(new MShot("hail", "ice_shard", 0.7f, from, new Vector(random.nextGaussian() * 0.02, -0.5, random.nextGaussian() * 0.02),
                                    0.03, mcfg("hail-damage", 3), 60, true));
                        }
                        if (t % 6 == 0) world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_HIT, 1f, 0.7f);
                    }
                    if (t >= 80) next();
                }
                case 11 -> { // Frost Nova: a ring of frost races outward along the ground. Jump over it!
                    onGround();
                    vel.multiply(0.5);
                    look(tp);
                    if (t == 0) {
                        setModel("mf_snow_monster_slam", mcfg("monster-scale", 3.0));
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f);
                    }
                    if (t == 12) {
                        setModel("mf_snow_monster", mcfg("monster-scale", 3.0));
                        novaR = 1;
                        hitNova.clear();
                        world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 2.5f, 0.5f);
                    }
                    if (t >= 12 && novaR < mcfg("nova-radius", 14)) {
                        novaR += 0.45;
                        double gy = groundY(pos.getX(), pos.getZ(), pos.getY()) + 0.2;
                        Vector c = new Vector(pos.getX(), gy, pos.getZ());
                        ring(c, novaR, Particle.SNOWFLAKE, (int) (novaR * 6));
                        ring(c, novaR, Particle.WHITE_ASH, (int) (novaR * 3));
                        for (Player p : active()) {
                            if (hitNova.contains(p.getUniqueId())) continue;
                            double d = Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
                            if (Math.abs(d - novaR) < 0.8 && p.isOnGround()) { // jumping over it dodges it
                                hurt(p, mcfg("nova-damage", 9), hitbox, pos, Guard.UNBLOCKABLE);
                                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 1));
                                hitNova.add(p.getUniqueId());
                            }
                        }
                    }
                    if (t >= 12 + 32 + 8) next();
                }
                default -> next();
            }
            t++;
        }

        final List<Vector> spots = new ArrayList<>();
        double novaR;
        final Set<UUID> hitNova = new HashSet<>();
        float hopOffset;
        int phaseBonus() { return phase >= 3 ? 1 : 0; }

        void hover(Vector tp, double height) {
            double side = Math.sin(ticksAlive / 40.0) * 7; // glides back and forth like in Cuphead
            Vector across = new Vector(-lastLook.getZ(), 0, lastLook.getX());
            if (across.lengthSquared() < 0.01) across = new Vector(1, 0, 0);
            // under a low ceiling (a cave, an underground arena), stay below it instead of hovering inside the rock
            for (int dy = 1; dy <= (int) height + 3; dy++) {
                if (world.getBlockAt(tp.getBlockX(), tp.getBlockY() + dy, tp.getBlockZ()).getType().isSolid()) {
                    height = Math.max(1.5, Math.min(height, dy - hitboxHalf() - 1.2));
                    break;
                }
            }
            across.normalize();
            // BUG FIX: the glide swung him 7 blocks sideways with no collision, so in a courtyard he slid INTO the walls
            // and hovered inside the stone (unhittable). Now the glide shortens until the spot (and the way there) is open.
            Vector goal = null;
            for (double f : new double[]{1.0, 0.6, 0.3, 0.0}) {
                Vector g = tp.clone().add(new Vector(0, height, 0)).add(across.clone().multiply(side * f));
                if (openSpot(g) && clearLine(g, tp)) { goal = g; break; }
            }
            if (goal == null) goal = tp.clone().add(new Vector(0, Math.min(height, 2.5), 0)); // tight spot: stay right over you
            fly(goal, 0.45, 0.12);
            look(tp);
        }

        /** Room for his body here (no solid block at his center or his top). */
        boolean openSpot(Vector v) {
            double h = hitboxHalf();
            return !world.getBlockAt(v.toLocation(world)).getType().isSolid()
                    && !world.getBlockAt(v.clone().add(new Vector(0, h, 0)).toLocation(world)).getType().isSolid()
                    && !world.getBlockAt(v.clone().add(new Vector(0, -h * 0.6, 0)).toLocation(world)).getType().isSolid();
        }

        /** No blocks between two points. */
        boolean clearLine(Vector from, Vector to) {
            Vector d = to.clone().subtract(from);
            double len = d.length();
            if (len < 0.5) return true;
            return world.rayTraceBlocks(from.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null;
        }

        float sq = 1, leanFwd, tilt, shakeAmt;           // what's on screen now
        float wSq, wLean, wTilt, wShake;                  // what this tick's move wants

        /** Cartoon animation for a single model: squash & stretch, leaning into throws, tilting, shaking. */
        void animate() {
            wSq = 1; wLean = 0; wTilt = 0; wShake = 0;
            float f;
            if (transition > 0) { // breaking into a new form: shudders and pulses
                wShake = 0.12f; wSq = 1 + (float) Math.sin(transition * 0.9) * 0.12f;
            } else switch (attack) {
                case 0 -> { // Icicle Runners: crouches, then flings his arms (stretch, lean in)
                    if (t < 10) { wSq = 0.86f; wLean = -0.15f; }
                    else if (t < 16) { wSq = 1.14f; wLean = 0.2f; }
                }
                case 1 -> { // Winter Whale: rises on his toes, swaying, then a jolt at the slam
                    if (t < 30) { wSq = 1.1f; wTilt = (float) Math.sin(t * 0.4) * 0.1f; }
                    if (t == 32) { sq = 0.8f; shakeAmt = 0.15f; }
                }
                case 2 -> { // Ice orbs: a lean-back and a snap forward on every throw
                    int c = t - 12;
                    if (c >= 0 && count <= 5) { int k = ((c % 7) + 7) % 7; wLean = k < 2 ? -0.18f : k < 4 ? 0.25f : 0; wSq = k < 2 ? 0.92f : k < 4 ? 1.1f : 1; }
                    else if (t < 12) wShake = 0.03f; // gathering power
                }
                case 3 -> { // Roll: squashes as he curls, stretches as he uncurls
                    int c = t % 45;
                    if (c >= 6 && c < 12) wSq = 0.8f;
                    else if (c >= 38 && c < 42) wSq = 1.12f;
                    if (c == 38 && contact) { sq = 0.75f; shakeAmt = 0.12f; } // bonk
                }
                case 4 -> { // Ground pound: deep crouch, stretched leap, flattened landing
                    if (t < 4) wSq = 0.72f;
                    else if (t < 14) { wSq = 1.22f; wLean = (float) Math.sin(t / 14.0 * Math.PI) * -0.12f; }
                    if (t == 14) { sq = 0.6f; shakeAmt = 0.2f; }
                }
                case 5 -> { // Fridge: rattles and squashes on each throw
                    int c = t - 10;
                    if (c >= 0 && c % 9 < 3) { wSq = 0.88f; wShake = 0.07f; }
                    else if (t < 10) wTilt = (float) Math.sin(t * 0.6) * 0.06f;
                }
                case 6 -> { // Rings: pulses out with every ring
                    int c = t - 10;
                    if (c >= 0 && c % 12 < 3) wSq = 1.15f;
                }
                case 7 -> { wTilt = (float) Math.sin(t * 0.25) * 0.15f; if (t > 18 && t < 24) wShake = 0.08f; } // Icicle rain: wobbles, then shudders as they drop
                case 8 -> { // Beam: shakes harder and harder while charging, leans into the sweep
                    if (t < 25) { wShake = 0.02f + t * 0.004f; wSq = 0.9f; }
                    else { wLean = 0.25f; wSq = 1.06f; }
                }
                case 9 -> { // Taunt: bounces with laughter, rocking side to side
                    if (t > 25) { wSq = 1 + (float) Math.sin(t * 0.9) * 0.1f; wTilt = (float) Math.sin(t * 0.45) * 0.14f; }
                }
                case 10 -> { wSq = 1 + (float) Math.sin(t * 0.5) * 0.05f; wTilt = (float) Math.sin(t * 0.2) * 0.08f; } // Hailstorm: a slow churn
                case 11 -> { // Frost Nova: winds down tighter and shakes, then bursts up
                    if (t < 12) { f = t / 12f; wSq = 1 - 0.3f * f; wShake = 0.1f * f; }
                    if (t == 12) { sq = 1.25f; shakeAmt = 0.18f; }
                }
                default -> { }
            }
            if (phase == 2 && attack != 4 && vel.clone().setY(0).lengthSquared() > 0.01) wTilt += (float) Math.sin(ticksAlive * 0.5) * 0.09f; // the Brute's waddle
            sq += (wSq - sq) * 0.35f;
            leanFwd += (wLean - leanFwd) * 0.3f;
            tilt += (wTilt - tilt) * 0.3f;
            shakeAmt = Math.max(wShake, shakeAmt * 0.75f);
        }

        void onGround() {
            double g = groundY(pos.getX(), pos.getZ(), pos.getY());
            pos.setY(pos.getY() + (g + 0.1 - pos.getY()) * 0.35);
            vel.setY(0);
        }

        Vector lob(Vector from, Vector target, int ticks, double gravity) {
            Vector d = target.clone().subtract(from);
            return new Vector(d.getX() / ticks, (d.getY() + gravity * ticks * (ticks + 1) / 2.0) / ticks, d.getZ() / ticks);
        }

        void ring(Vector c, double r, Particle p, int n) {
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                world.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }

        void spawnRunner(Player target) {
            Location at = pos.toLocation(world).add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2);
            at.setY(groundY(at.getX(), at.getZ(), at.getY()));
            if (!(world.spawnEntity(at, EntityType.ZOMBIE, false) instanceof org.bukkit.entity.Zombie z)) return;
            z.setBaby();
            hideForGood(z);
            z.setSilent(true);
            z.setShouldBurnInDay(false);
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false); // BUG FIX: zombies despawn when nobody's near, leaving the model behind
            AttributeInstance reinf = z.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
            if (reinf != null) reinf.setBaseValue(0); // BUG FIX: no real zombies called in as "reinforcements"
            z.setCanPickupItems(false);
            z.getEquipment().clear();
            z.addScoreboardTag(RUNNER_TAG);
            z.setCustomName(ChatColor.AQUA + "Icicle Runner");
            z.setCustomNameVisible(false);
            AttributeInstance hp = z.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) { hp.setBaseValue(mcfg("runner-health", 12)); z.setHealth(hp.getBaseValue()); }
            ItemDisplay look = spawnDisplay(at, "mf_icicle_runner", 0.9f, 1, Display.Billboard.FIXED);
            look.setTransformation(new Transformation(new Vector3f(0, 0.2f, 0), facing(0), new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf()));
            z.addPassenger(look);
            proxy(z, look, EntityType.SNOW_GOLEM, 0.5);
            z.setTarget(target);
            runners.add(z);
            runnerLooks.put(z, look);
            world.spawnParticle(Particle.SNOWFLAKE, at, 20, 0.3, 0.5, 0.3, 0.05);
        }

        void spike(Vector at) {
            ItemDisplay s = spawnDisplay(at.toLocation(world), "mf_spike", 0.2f, 1, Display.Billboard.FIXED);
            s.setInterpolationDuration(4);
            s.setInterpolationDelay(0);
            s.setTransformation(new Transformation(new Vector3f(0, 0.9f, 0), new Quaternionf(), new Vector3f(1.3f, 1.8f, 1.3f), new Quaternionf()));
            props.add(s);
            world.playSound(at.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
            world.spawnParticle(Particle.BLOCK, at.toLocation(world), 20, 0.4, 0.3, 0.4, 0, Material.PACKED_ICE.createBlockData());
            for (Player p : active()) {
                if (p.getLocation().toVector().distanceSquared(at) < 1.6 * 1.6) {
                    hurt(p, mcfg("spike-damage", 8), hitbox, at, Guard.UNBLOCKABLE); // from the ground: jump or dodge
                    p.setVelocity(p.getVelocity().setY(0.6));
                }
            }
            Bukkit.getScheduler().runTaskLater(FaultlineBosses.this, () -> { props.remove(s); if (s.isValid()) s.remove(); }, 24L);
        }

        void beam(Vector d) {
            Vector p = pos.clone();
            for (double s = 0; s < 26; s += 0.6) {
                p = pos.clone().add(d.clone().multiply(s));
                if (world.getBlockAt(p.toLocation(world)).getType().isSolid()) break;
                world.spawnParticle(Particle.DUST, p.toLocation(world), 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(170, 230, 255), 1.4f));
                for (Player pl : active()) {
                    if (hitCooldown.containsKey(pl.getUniqueId())) continue;
                    if (eye(pl).subtract(new Vector(0, 0.3, 0)).distanceSquared(p) > 1.1 * 1.1) continue;
                    hurt(pl, mcfg("beam-damage", 4), hitbox, pos, Guard.HEAVY);
                    pl.setFreezeTicks(Math.min(pl.getMaxFreezeTicks() + 60, pl.getFreezeTicks() + 60));
                    pl.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 1));
                    hitCooldown.put(pl.getUniqueId(), 10);
                }
            }
        }

        void ramDamage(List<Player> a) {
            double r = hitboxHalf() + 0.8;
            for (Player p : a) {
                if (hitCooldown.containsKey(p.getUniqueId())) continue;
                if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(pos.clone().add(new Vector(0, hitboxHalf(), 0))) > r * r) continue;
                hurt(p, mcfg("roll-damage", 10), hitbox, pos, Guard.HEAVY);
                Vector push = p.getLocation().toVector().subtract(pos).setY(0);
                if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                p.setVelocity(push.normalize().multiply(1.1).setY(0.5));
                hitCooldown.put(p.getUniqueId(), 14);
            }
        }

        void tickShots(List<Player> a) {
            for (MShot s : new ArrayList<>(shots)) {
                s.life++;
                boolean remove = s.life > s.maxLife;
                if ("parried".equals(s.kind)) { // flies back into Mortimer
                    Vector home = pos.clone().add(new Vector(0, hitboxHalf(), 0));
                    s.vel = home.clone().subtract(s.pos).normalize().multiply(0.9);
                    s.pos.add(s.vel);
                    if (s.pos.distanceSquared(home) < 2.2 * 2.2) {
                        Player by = s.owner == null ? null : Bukkit.getPlayer(s.owner);
                        hitbox.damage(mcfg("parry-damage", 25), by);
                        world.spawnParticle(Particle.END_ROD, s.pos.toLocation(world), 30, 0.4, 0.4, 0.4, 0.15);
                        world.playSound(s.pos.toLocation(world), org.bukkit.Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 2f, 1.2f);
                        remove = true;
                    }
                } else {
                    s.vel.setY(s.vel.getY() - s.gravity);
                    Vector next = s.pos.clone().add(s.vel);
                    if (world.getBlockAt(next.toLocation(world)).getType().isSolid()) {
                        if ("cube".equals(s.kind)) shatter(s.pos);
                        if ("icicle".equals(s.kind)) {
                            world.spawnParticle(Particle.BLOCK, s.pos.toLocation(world), 20, 0.3, 0.2, 0.3, 0, Material.ICE.createBlockData());
                            world.playSound(s.pos.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
                        }
                        remove = true;
                    } else s.pos = next;
                    double radius = "cube".equals(s.kind) ? 1.2 : "icicle".equals(s.kind) ? 1.3 : 0.9;
                    for (Player p : a) {
                        if (remove || hitCooldown.containsKey(p.getUniqueId())) continue;
                        if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(s.pos) > radius * radius) continue;
                        if ("pink".equals(s.kind) && facingBlock(p, s.pos)) { // PARRY: block the pink orb and it flies back
                            shieldHit(p, 1, false);
                            reflect(s, p);
                            remove = true;
                            continue;
                        }
                        hurt(p, s.damage, hitbox, s.pos, s.guard);
                        hitCooldown.put(p.getUniqueId(), 8);
                        if ("cube".equals(s.kind)) shatter(s.pos);
                        remove = true;
                    }
                }
                if (remove) {
                    s.display.remove();
                    if (s.hitbox != null && s.hitbox.isValid()) s.hitbox.remove();
                    shots.remove(s);
                } else {
                    if (s.hitbox != null && s.hitbox.isValid()) s.hitbox.teleport(s.pos.toLocation(world).subtract(0, 0.26, 0));
                    Location l = s.pos.toLocation(world);
                    if ("icicle".equals(s.kind)) l.setPitch(0);
                    else if (s.vel.lengthSquared() > 0.001) l.setDirection(s.vel);
                    s.display.teleport(l);
                    if (s.life % 2 == 0) bedrockTrail(l, "pink".equals(s.kind) ? Color.fromRGB(255, 120, 200)
                            : "cube".equals(s.kind) || "small".equals(s.kind) ? Color.fromRGB(150, 205, 245) : Color.fromRGB(225, 245, 255), 1.3f);
                }
            }
        }

        /** A pink orb turns around and flies back into Mortimer (hit with anything, or blocked with a shield). */
        void reflect(MShot s, Player by) {
            MShot back = new MShot("parried", "mf_orb_pink", 1.6f, s.pos.clone(), new Vector(), 0, 0, 80, true);
            back.owner = by.getUniqueId();
            shots.add(back);
            by.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "PARRY!"));
            world.playSound(by.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.8f, 2f);
            world.spawnParticle(Particle.DUST, s.pos.toLocation(world), 16, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 120, 200), 1.5f));
        }

        /** Mace hits: he raises his hand and swats it away. */
        void deflect(Player p) {
            pose("mf_wizard_block", 12);
            world.playSound(pos.toLocation(world), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 2f, 0.6f);
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_ANVIL_PLACE, 1f, 1.6f);
            world.spawnParticle(Particle.SNOWFLAKE, p.getEyeLocation(), 25, 0.3, 0.3, 0.3, 0.1);
            Vector push = p.getLocation().toVector().subtract(pos).setY(0);
            if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
            p.setVelocity(push.normalize().multiply(1.2).setY(0.5));
            p.sendActionBar(legacy(ChatColor.AQUA + "Frostbeard swats your mace away!"));
        }

        // ---------- the Demon Eye's boss music ----------
        final Map<UUID, Long> musicUntil = new HashMap<>();
        static final String MUSIC = "faultline:demon_eye.music";

        void musicTick() {
            if (!getConfig().getBoolean("mortimer.music", true)) return;
            double radius = mcfg("music.radius", cfg("music.radius", 64));
            long now = System.currentTimeMillis();
            long loopMs = (long) (mcfg("music.length-seconds", cfg("music.length-seconds", 316.42)) * 1000);
            Set<UUID> inRange = new HashSet<>();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > radius * radius) continue;
                inRange.add(p.getUniqueId());
                Long until = musicUntil.get(p.getUniqueId());
                if (until == null || now >= until - 150) {
                    p.stopSound(SoundCategory.MUSIC);
                    p.playSound(p, MUSIC, SoundCategory.RECORDS, (float) mcfg("music.volume", cfg("music.volume", 0.8)), 1f);
                    musicUntil.put(p.getUniqueId(), now + loopMs);
                }
            }
            for (UUID id : new ArrayList<>(musicUntil.keySet())) {
                if (inRange.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.stopSound(MUSIC, SoundCategory.RECORDS);
                musicUntil.remove(id);
            }
        }

        void stopMusic() {
            for (UUID id : musicUntil.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.stopSound(MUSIC, SoundCategory.RECORDS);
            }
            musicUntil.clear();
        }

        /** A big ice cube breaks into three small ones that hop outward. */
        void shatter(Vector at) {
            world.playSound(at.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 1.3f, 0.9f);
            world.spawnParticle(Particle.BLOCK, at.toLocation(world), 25, 0.4, 0.3, 0.4, 0, Material.ICE.createBlockData());
            for (int i = 0; i < 3; i++) {
                double a = Math.PI * 2 * i / 3 + random.nextDouble();
                Vector from = at.clone().add(new Vector(0, 0.6, 0));
                shots.add(new MShot("small", "mf_ice_cube", 0.6f, from, new Vector(Math.cos(a) * 0.22, 0.45, Math.sin(a) * 0.22), 0.05,
                        mcfg("small-cube-damage", 3), 40, false));
            }
        }

        // ---------- phases ----------
        void checkPhase() {
            double frac = hitbox.getHealth() / mcfg("health", 700);
            int want = frac <= mcfg("phase-3-at", 0.33) ? 3 : frac <= mcfg("phase-2-at", 0.66) ? 2 : 1;
            if (want <= phase) return;
            phase = want;
            transition = 50;
            contact = false;
            tumble = 0;
            hopOffset = 0;
            for (MShot s : shots) { s.display.remove(); if (s.hitbox != null && s.hitbox.isValid()) s.hitbox.remove(); }
            shots.clear();
            if (whale != null) { whale.remove(); props.remove(whale); whale = null; } // don't leave it hanging mid-air
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_BELL_USE, 3f, phase == 2 ? 0.6f : 0.9f);
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_BELL_RESONATE, 2f, 1f);
        }

        void transitionTick() {
            transition--;
            vel.multiply(0.8);
            roll += 0.5f;
            if (transition % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 40, 1.5, 2, 1.5, 0.1);
            if (transition == 25) {
                if (phase == 2) { setModel("mf_snow_monster", mcfg("monster-scale", 3.0)); setHitboxScale(2.6); proxy(hitbox, model, EntityType.SNOW_GOLEM, mcfg("bedrock.monster-scale", 2.0)); }
                else { setModel("mf_snowflake", mcfg("flake-scale", 4.0)); setHitboxScale(2.3); proxy(hitbox, model, EntityType.BREEZE, mcfg("bedrock.flake-scale", 2.0)); }
                world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 2, 0.5, 0.5, 0.5, 0);
            }
            if (transition == 0) {
                roll = 0;
                attack = -1;
                Bukkit.getOnlinePlayers().stream().filter(p -> p.getWorld().equals(world) && p.getLocation().toVector().distanceSquared(pos) < 64 * 64)
                        .forEach(p -> p.sendActionBar(legacy(ChatColor.AQUA + (phase == 2 ? "The Snow Brute breaks free!" : "Frostbeard becomes a blizzard!"))));
            }
        }

        // ---------- look & feel ----------
        void render() {
            Location at = pos.toLocation(world);
            double half = hitboxHalf();
            boolean grounded = phase == 2;
            hitbox.teleport(at.clone().subtract(0, grounded ? 0 : half, 0));
            Location view = at.clone();
            double bottom = grounded ? 0 : -half;
            view.add(0, bottom + 0.5 * baseScale, 0); // model y=0 sits on the hitbox's bottom
            view.setDirection(lastLook.clone().setY(0).lengthSquared() < 0.01 ? new Vector(0, 0, 1) : lastLook.clone().setY(0));
            model.teleport(view);
            animate();
            {
                float s = baseScale;
                float lean = 0;
                if (phase == 1 && transition == 0) { // leans into his glides
                    Vector across = new Vector(-lastLook.getZ(), 0, lastLook.getX());
                    lean = (float) Math.max(-0.35, Math.min(0.35, -vel.dot(across) * 0.9));
                }
                Quaternionf rot = facing(phase == 3 ? ticksAlive * (attack == 9 && t > 25 ? 0.03f : 0.12f) : roll + lean);
                if (tumble != 0) rot = new Quaternionf().rotateY((float) Math.PI).rotateX(tumble);
                float bob = grounded ? hopOffset : (float) Math.sin(ticksAlive * 0.1) * 0.2f;
                // squash & stretch (keeps his volume, and his base stays planted), plus lean, tilt and shake
                float sy = sq, sxz = (float) (1 / Math.sqrt(Math.max(0.3, sq)));
                rot = new Quaternionf(rot).rotateX(leanFwd).rotateZ(tilt);
                bob -= 0.5f * s * (1 - sy);
                float jx = shakeAmt > 0.01f ? (float) (random.nextGaussian() * shakeAmt) : 0, jz = shakeAmt > 0.01f ? (float) (random.nextGaussian() * shakeAmt) : 0;
                model.setInterpolationDelay(0);
                model.setTransformation(new Transformation(new Vector3f(jx, bob, jz), rot, new Vector3f(s * sxz, s * sy, s * sxz), new Quaternionf()));
            }
            if (ticksAlive % 4 == 0) world.spawnParticle(Particle.SNOWFLAKE, at, 4, 1, 1.2, 1, 0.01);
            if (ticksAlive % 6 == 0) world.spawnParticle(Particle.END_ROD, at, 1, 0.8, 1, 0.8, 0.005);
            if (!grounded && ticksAlive % 3 == 0) { // shadow on the ground below him
                double gy = groundY(pos.getX(), pos.getZ(), pos.getY()) + 0.1;
                double r = Math.max(0.8, half * 0.9);
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * 2 * i / 10;
                    world.spawnParticle(Particle.DUST, pos.getX() + Math.cos(a) * r, gy, pos.getZ() + Math.sin(a) * r, 1, 0.05, 0, 0.05, 0,
                            new Particle.DustOptions(Color.fromRGB(40, 60, 90), 1.2f));
                }
            }
        }

        void onHurt() {
            model.setGlowColorOverride(Color.AQUA);
            model.setGlowing(true);
            hurtFlash = 3;
            sq = Math.min(sq, 0.84f); shakeAmt = Math.max(shakeAmt, 0.1f); // flinches
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_HIT, 1f, 0.8f + random.nextFloat() * 0.4f);
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, hitbox.getHealth() / mcfg("health", 700))));
            bar.setTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard" + ChatColor.WHITE
                    + (phase == 1 ? "" : phase == 2 ? " - The Snow Brute" : " - The Blizzard"));
            if (ticksAlive % 10 != 0) return;
            for (Player p : new ArrayList<>(bar.getPlayers())) {
                if (!p.isOnline() || !p.getWorld().equals(world) || p.getLocation().toVector().distanceSquared(pos) > 80 * 80) bar.removePlayer(p);
            }
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) <= 80 * 80 && !bar.getPlayers().contains(p)) bar.addPlayer(p);
        }

        // ---------- endings ----------
        void die() {
            if (dying) return;
            dying = true;
            bar.removeAll();
            stopMusic();
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 3f, 0.5f);
            world.playSound(pos.toLocation(world), org.bukkit.Sound.BLOCK_BELL_USE, 3f, 1.4f);
            Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostbeard has melted!");
            clear();
            rewards();
            mortimerCooldownUntil = System.currentTimeMillis() + (long) (mcfg("cooldown-minutes", 30) * 60000);
        }

        void deathTick() { // spins, shrinks, and bursts into snow
            deathTicks++;
            roll += 0.2f + deathTicks * 0.01f;
            float s = Math.max(0.05f, baseScale * (1 - deathTicks / 50f));
            model.setInterpolationDelay(0);
            model.setTransformation(new Transformation(new Vector3f(), facing(roll), new Vector3f(s, s, s), new Quaternionf()));
            if (deathTicks % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 30, 1, 1, 1, 0.1);
            if (deathTicks >= 50) {
                world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 150, 1.5, 1.5, 1.5, 0, Material.SNOW_BLOCK.createBlockData());
                removeEverything();
                mortimer = null;
            }
        }

        void leave(String message) {
            if (leaving || dying) return;
            leaving = true;
            stopMusic();
            Bukkit.broadcastMessage(message);
            mortimerCooldownUntil = System.currentTimeMillis() + (long) (mcfg("cooldown-minutes", 30) * 60000);
        }

        void leaveTick() {
            deathTicks++;
            if (deathTicks == 1) { bar.removeAll(); clear(); if (hitbox.isValid()) hitbox.remove(); }
            pos.add(new Vector(0, 0.4, 0));
            if (model.isValid()) model.teleport(pos.toLocation(world));
            world.spawnParticle(Particle.SNOWFLAKE, pos.toLocation(world), 10, 0.8, 0.8, 0.8, 0.05);
            if (deathTicks >= 40) { removeEverything(); mortimer = null; }
        }

        void clear() {
            for (MShot s : shots) { s.display.remove(); if (s.hitbox != null && s.hitbox.isValid()) s.hitbox.remove(); }
            shots.clear();
            for (LivingEntity r : runners) { r.getPassengers().forEach(Entity::remove); r.remove(); }
            runners.clear();
            for (ItemDisplay look : runnerLooks.values()) if (look.isValid()) look.remove(); // even ones that fell off their Runner
            runnerLooks.clear();
            for (ItemDisplay p : props) if (p.isValid()) p.remove();
            props.clear();
        }

        void removeEverything() {
            stopMusic();
            clear();
            bar.removeAll();
            if (hitbox.isValid()) hitbox.remove();
            if (model.isValid()) model.remove();
        }

        void rewards() {
            Location at = pos.toLocation(world);
            String spot = "at:" + world.getName() + "," + at.getX() + "," + at.getY() + "," + at.getZ();
            int orbs = 0;
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                console("givemythicbag " + (int) mcfg("rewards.mythic-bags", 3) + " " + p.getName() + " " + spot, p, at);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, 8 + random.nextInt(7)));
                dropLocked(at, p, new ItemStack(Material.BLUE_ICE, 16));
                if (random.nextDouble() < mcfg("rewards.frost-flare-chance", 0.25)) console("giveaccessory frostflare 1 " + p.getName() + " " + spot, p, at);
                orbs += (int) mcfg("rewards.xp", 500);
                p.showTitle(Title.title(legacy(ChatColor.AQUA + "" + ChatColor.BOLD + "FROSTBEARD DEFEATED"),
                        legacy(ChatColor.GRAY + "Your loot dropped where he melted. Only you can pick it up."),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            }
            for (int left = orbs; left > 0; ) {
                int amount = Math.min(left, 100);
                left -= amount;
                world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(amount);
            }
        }
    }

    // ---------- Mortimer events ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onMortimerDamage(EntityDamageEvent event) {
        if (mortimer == null || !event.getEntity().getScoreboardTags().contains(MORT_TAG)) return;
        EntityDamageEvent.DamageCause c = event.getCause();
        boolean allowed = c == EntityDamageEvent.DamageCause.ENTITY_ATTACK || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || c == EntityDamageEvent.DamageCause.PROJECTILE || c == EntityDamageEvent.DamageCause.MAGIC
                || c == EntityDamageEvent.DamageCause.THORNS || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || c == EntityDamageEvent.DamageCause.CUSTOM;
        if (!allowed || mortimer.transition > 0 || mortimer.dying || !byPlayer(event)) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player p
                && p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            event.setCancelled(true); // he raises his hand and swats maces away
            mortimer.deflect(p);
            return;
        }
        if (c == EntityDamageEvent.DamageCause.PROJECTILE) {
            // bows actually work: arrows ignore his armor and his damage reduction
            if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0);
        } else {
            event.setDamage(event.getDamage() * (1 - mcfg("damage-reduction", 0.40))); // really tanky up close
        }
        mortimer.onHurt();
    }

    private final Map<UUID, Long> fireNotice = new HashMap<>();

    /** He's an ice wizard: Fire Aspect, Flame arrows, lava, nothing sets him on fire. */
    @EventHandler(ignoreCancelled = true)
    public void onMortimerCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (!tags.contains(MORT_TAG) && !tags.contains(RUNNER_TAG) && !tags.contains(MORT_ORB_TAG)) return;
        event.setCancelled(true);
        if (event instanceof org.bukkit.event.entity.EntityCombustByEntityEvent by) {
            Player p = by.getCombuster() instanceof Player pl ? pl
                    : by.getCombuster() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
            if (p != null && System.currentTimeMillis() > fireNotice.getOrDefault(p.getUniqueId(), 0L)) {
                p.sendActionBar(legacy(ChatColor.AQUA + "Fire does nothing to an ice wizard!"));
                fireNotice.put(p.getUniqueId(), System.currentTimeMillis() + 4000);
            }
        }
    }

    /** Hitting a pink orb (melee or an arrow) sends it back into Mortimer. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPinkOrbHit(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(MORT_ORB_TAG)) return;
        event.setCancelled(true); // it never "dies"; it gets knocked back instead
        if (mortimer == null || !(event instanceof EntityDamageByEntityEvent by)) return;
        Player p = by.getDamager() instanceof Player pl ? pl
                : by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (p == null) return;
        for (MShot s : new ArrayList<>(mortimer.shots)) {
            if (s.hitbox == null || !s.hitbox.equals(event.getEntity()) || !"pink".equals(s.kind)) continue;
            mortimer.reflect(s, p);
            mortimer.join(p);
            s.display.remove();
            s.hitbox.remove();
            mortimer.shots.remove(s);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMortimerFight(EntityDamageByEntityEvent event) {
        if (mortimer == null) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player s ? s : null;
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (attacker != null && (tags.contains(MORT_TAG) || tags.contains(RUNNER_TAG))) mortimer.join(attacker);
        if (event.getEntity() instanceof Player victim && event.getDamager().getScoreboardTags().contains(RUNNER_TAG)) {
            victim.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 0)); // chilly bite
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMortimerDeath(EntityDeathEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (tags.contains(RUNNER_TAG)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.getEntity().getPassengers().forEach(Entity::remove);
            event.getEntity().getWorld().spawnParticle(Particle.BLOCK, event.getEntity().getLocation(), 20, 0.3, 0.3, 0.3, 0, Material.ICE.createBlockData());
            return;
        }
        if (!tags.contains(MORT_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (mortimer != null) mortimer.die();
    }

    @EventHandler
    public void onMortimerPlayerDeath(PlayerDeathEvent event) {
        if (mortimer != null) mortimer.fighterDied(event.getEntity());
    }


    // ===================== BEDROCK STAND-INS =====================
    //
    // Bedrock players (through Geyser) can't see display entities or Java resource-pack
    // models, so the bosses looked invisible to them. Each boss/minion hitbox gets a vanilla
    // "stand-in" mob that ONLY Bedrock players can see (hidden from every Java player). It
    // follows its hitbox every tick, and hitting it hurts the real boss. When the hitbox goes
    // away (death, cleanup, despawn), its stand-in is removed with it.

    static final String PROXY_TAG = "faultline_bedrock_proxy";

    private static final class Proxy {
        final LivingEntity stand;
        final LivingEntity anchor;   // the real hitbox (damage goes here)
        final Entity look;           // whose facing to copy (the Java model), may be null
        Proxy(LivingEntity stand, LivingEntity anchor, Entity look) { this.stand = stand; this.anchor = anchor; this.look = look; }
    }
    private final Map<UUID, Proxy> proxies = new HashMap<>(); // keyed by the anchor's UUID
    private int proxyTicks;

    /** Floodgate gives Bedrock players UUIDs whose first half is all zeros. */
    static boolean bedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0;
    }

    private boolean anyBedrockOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) if (bedrock(p)) return true;
        return false;
    }

    /** Gives (or replaces) the Bedrock stand-in for this hitbox. */
    void proxy(LivingEntity anchor, Entity look, EntityType type, double scale) {
        if (anchor == null || !anchor.isValid()) return;
        Proxy old = proxies.remove(anchor.getUniqueId());
        if (old != null && old.stand.isValid()) old.stand.remove();
        Entity raw = anchor.getWorld().spawnEntity(anchor.getLocation(), type, false);
        if (!(raw instanceof LivingEntity stand)) { raw.remove(); return; }
        for (Player p : Bukkit.getOnlinePlayers()) if (!bedrock(p)) p.hideEntity(this, stand); // Java players never see it
        if (stand instanceof org.bukkit.entity.Mob mob) mob.setAI(false);
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setPersistent(false);
        stand.setRemoveWhenFarAway(false);
        stand.addScoreboardTag(PROXY_TAG);
        if (stand instanceof org.bukkit.entity.Snowman golem) golem.setDerp(false);
        AttributeInstance sc = stand.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(scale);
        proxies.put(anchor.getUniqueId(), new Proxy(stand, anchor, look));
    }

    /** Every tick: stand-ins follow their hitboxes (feet to feet) and face where the model faces. */
    private void proxyTick() {
        if (++proxyTicks % 40 == 0) { // safety net: Java players never see a Bedrock stand-in
            for (Player p : Bukkit.getOnlinePlayers()) if (!bedrock(p)) for (Proxy px : proxies.values()) if (px.stand.isValid()) p.hideEntity(this, px.stand);
        }
        for (Iterator<Map.Entry<UUID, Proxy>> it = proxies.entrySet().iterator(); it.hasNext(); ) {
            Proxy px = it.next().getValue();
            if (!px.anchor.isValid() || px.anchor.isDead() || !px.stand.isValid()) {
                if (px.stand.isValid()) px.stand.remove();
                it.remove();
                continue;
            }
            Location at = px.anchor.getLocation();
            if (px.look != null && px.look.isValid()) { at.setYaw(px.look.getLocation().getYaw()); at.setPitch(0); }
            px.stand.teleport(at);
        }
    }

    /** Hitting a stand-in hurts the real thing (only players and their arrows; golems etc. do nothing). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProxyHit(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(PROXY_TAG)) return;
        event.setCancelled(true);
        if (!(event instanceof EntityDamageByEntityEvent by)) return;
        Entity d = by.getDamager();
        Entity attacker = d instanceof Player ? d : d instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null) return;
        for (Proxy px : proxies.values()) {
            if (px.stand.equals(event.getEntity())) {
                if (!px.anchor.isValid()) return;
                // BUG FIX: an ARROW that hit a stand-in was passed on as a punch from the player, so a boss that only takes
                // arrows (Diamond Jacob on his hawk) threw it out. The stand-ins are hidden from Java players but still
                // physically there, so Java players' arrows hit them too. Arrows now arrive as arrows.
                if (d instanceof Projectile) {
                    px.anchor.damage(event.getDamage(), org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.ARROW)
                            .withDirectEntity(d).withCausingEntity(attacker).build());
                } else {
                    px.anchor.damage(event.getDamage(), attacker);
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProxySnowTrail(org.bukkit.event.block.EntityBlockFormEvent event) {
        if (event.getEntity().getScoreboardTags().contains(PROXY_TAG)) event.setCancelled(true); // Snow Golem stand-ins leave no snow
    }

    @EventHandler(ignoreCancelled = true)
    public void onProxyTarget(org.bukkit.event.entity.EntityTargetEvent event) {
        if (event.getTarget() != null && event.getTarget().getScoreboardTags().contains(PROXY_TAG)) event.setCancelled(true);
    }

    /** New Java players can't see existing stand-ins either. */
    @EventHandler
    public void onProxyJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (bedrock(p)) return;
        for (Proxy px : proxies.values()) if (px.stand.isValid()) p.hideEntity(this, px.stand);
    }

    /** Projectiles are item displays too: give Bedrock players a trail to see (and dodge). */
    void bedrockTrail(Location at, Color color, float size) {
        for (Player p : at.getWorld().getPlayers()) {
            if (bedrock(p) && p.getLocation().distanceSquared(at) < 48 * 48) {
                p.spawnParticle(Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(color, size));
            }
        }
    }

    // ===================== THE DUNE DEVOURER (inspired by Calamity/Infernum's Desert Scourge) =====================
    //
    // Surfaces during a Sandstorm (deserts/badlands) or a Snowy Day (anywhere snowy): every 30s each
    // player standing in it has a 0.5% chance. Announced in chat only. A segmented worm (head, body,
    // tail) that burrows: it steers freely underground and falls in arcs through the air, so it
    // naturally lunges out and dives back in. Hitting any segment damages it.
    // Phase 1: Sand Spit, Sand Rush Charge, Sandstorm. Phase 2 (55%): + Ground Slam (twice, with
    // Sandnadoes). Phase 3 (25%): + Vultures.

    static final String DUNE_TAG = "faultline_dune";
    static final String DUNE_SEG_TAG = "faultline_dune_segment";
    static final String VULTURE_TAG = "faultline_vulture";
    private Dune dune;
    private long duneCooldownUntil;
    private NamespacedKey duneEggKey;

    private double dcfg(String key, double def) {
        return getConfig().getDouble("dune." + key, def);
    }

    private String activeEvent() {
        if (Bukkit.getWorlds().isEmpty()) return null;
        return Bukkit.getWorlds().get(0).getPersistentDataContainer()
                .get(new NamespacedKey("faultlineevents", "active_event"), PersistentDataType.STRING);
    }

    private static boolean desertAt(Location loc) {
        String b = loc.getBlock().getBiome().getKey().getKey();
        return b.contains("desert") || b.contains("badlands");
    }

    /** Every 30 seconds: players standing in a Sandstorm or on a Snowy Day roll for it. */
    private void duneSpawnTick() {
        if (dune != null || System.currentTimeMillis() < duneCooldownUntil || !getConfig().getBoolean("dune.enabled", true)) return;
        String event = activeEvent();
        boolean sandstorm = "SANDSTORM".equals(event), snowy = "SNOWY_DAY".equals(event);
        if (!sandstorm && !snowy) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL || p.getWorld().getDifficulty() == Difficulty.PEACEFUL) continue;
            boolean inSand = sandstorm && desertAt(p.getLocation()), inSnow = snowy && inTheSnow(p.getLocation());
            if (!(inSand || inSnow) || random.nextDouble() >= dcfg("spawn-chance", 0.005)) continue;
            summonDune(p, inSnow); // the Snowy Day belongs to the Frostmaw
            return;
        }
    }

    void summonDune(Player near, boolean frost) {
        try {
            dune = new Dune(near, frost);
        } catch (RuntimeException e) {
            dune = null;
            getLogger().log(java.util.logging.Level.SEVERE, "[Dune worm] couldn't be summoned (nothing was left behind):", e);
            near.sendMessage(ChatColor.RED + "The worm couldn't be summoned. (The error is in the server console.)");
            return;
        }
        if (!dune.head().hitbox.isValid()) { dune.removeEverything(); dune = null; return; }
        // chat only: the rumble under your feet is the real warning
        Bukkit.broadcastMessage(dune.tint() + "" + ChatColor.BOLD + "The ground trembles beneath " + near.getName() + "... "
                + (frost ? ChatColor.WHITE + "the Frostmaw is rising!" : ChatColor.YELLOW + "the Dune Devourer is rising!"));
    }

    private NamespacedKey frostEggKey;

    ItemStack frostmawEgg() {
        ItemStack item = new ItemStack(Material.POLAR_BEAR_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Frostmaw Spawn Egg");
        meta.setLore(List.of(ChatColor.GRAY + "Use it to summon the Frostmaw", ChatColor.GRAY + "right under you."));
        meta.getPersistentDataContainer().set(frostEggKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack duneEgg() {
        ItemStack item = new ItemStack(Material.SILVERFISH_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Dune Devourer Spawn Egg");
        meta.setLore(List.of(ChatColor.GRAY + "Use it to summon the Dune Devourer", ChatColor.GRAY + "right under you."));
        meta.getPersistentDataContainer().set(duneEggKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onDuneEgg(PlayerInteractEvent event) {
        boolean frostEgg = tagged(event.getItem(), frostEggKey);
        if (event.getHand() != EquipmentSlot.HAND || !(tagged(event.getItem(), duneEggKey) || frostEgg)) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) return;
        Player p = event.getPlayer();
        if (dune != null) { p.sendMessage(ChatColor.RED + "A great worm is already here."); return; }
        summonDune(p, frostEgg);
        if (dune == null) return;
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        } else {
            p.sendMessage(ChatColor.GRAY + "(Fight it in survival; creative players don't count as fighters.)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDuneEggDispense(org.bukkit.event.block.BlockDispenseEvent event) {
        if (tagged(event.getItem(), duneEggKey) || tagged(event.getItem(), frostEggKey)) event.setCancelled(true);
    }

    private class DShot {
        final ItemDisplay display;
        Vector pos, vel;
        final double gravity, damage;
        int life;
        final int maxLife;
        DShot(Vector pos, Vector vel, double gravity, double damage, int maxLife) {
            this.pos = pos; this.vel = vel; this.gravity = gravity; this.damage = damage; this.maxLife = maxLife;
            this.display = spawnDisplay(pos.toLocation(dune.world), dune.frost ? "frost_blast" : "sand_blast", 1.3f, 1, Display.Billboard.CENTER);
        }
    }

    private class Seg {
        Vector pos;
        final ItemDisplay display;
        final Slime hitbox;
        final String model;
        boolean wasInGround;
        Seg(Vector pos, String model, World world, boolean head, String bossName) {
            this.pos = pos;
            this.model = model;
            Location at = pos.toLocation(world);
            this.display = spawnDisplay(at, model, (float) dcfg("model-scale", 2.2), 2, Display.Billboard.FIXED);
            this.hitbox = (Slime) world.spawnEntity(at, EntityType.SLIME, false);
            try {
                hitbox.setSize(3); // ~1.6 blocks, then scaled to match the model
                setupHitbox(hitbox, head ? dcfg("health", 1100) : 1024, head ? DUNE_TAG : DUNE_SEG_TAG, bossName);
            } catch (RuntimeException e) { // this piece failed: remove it, then let the worm clean up the rest
                display.remove(); hitbox.remove();
                throw e;
            }
            AttributeInstance sc = hitbox.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(head ? dcfg("hitbox-scale", 1.25) : dcfg("body-hitbox-scale", 1.5));
            // BUG FIX: body hits are passed to the head; with the normal ~half-second hit cooldown,
            // hits from several players on different segments were mostly ignored.
            if (head) hitbox.setMaximumNoDamageTicks((int) dcfg("head-hit-cooldown-ticks", 5));
            proxy(hitbox, display, EntityType.SILVERFISH, head ? 4.5 : 3.8);
        }
        double half() {
            AttributeInstance sc = hitbox.getAttribute(Attribute.SCALE);
            return 0.78 * (sc == null ? 1 : sc.getValue());
        }
    }

    private class Sandnado {
        Vector pos;
        final Vector drift;
        int life;
        Sandnado(Vector pos, Vector drift) { this.pos = pos; this.drift = drift; }
    }

    // =====================================================================================================
    //  THE KRAKEN: drop a Bait Worm into a Deep Ocean. 2,200 health, 30% defense, 3 phases, 11 moves.
    // =====================================================================================================
    static final String KRAKEN_TAG = "faultline_kraken", KRAKEN_TENT_TAG = "faultline_kraken_tentacle",
            KRAKEN_PINK_TAG = "faultline_kraken_pink", KRAKEN_EEL_TAG = "faultline_kraken_eel", KRAKEN_MINION_TAG = "faultline_kraken_minion";
    private Kraken kraken;
    private long krakenCooldownUntil;
    private final Map<UUID, Sink> sinking = new HashMap<>();
    private static final org.bukkit.NamespacedKey WORM_KEY = new org.bukkit.NamespacedKey("faultlineitems", "kraken_worm");
    private static final org.bukkit.NamespacedKey COUNTERS_KEY = new org.bukkit.NamespacedKey("faultlineitems", "kraken_counters");
    private static final org.bukkit.NamespacedKey DIVING_KEY = new org.bukkit.NamespacedKey("faultlineitems", "diving_tier");

    private double kcfg(String path, double def) { return getConfig().getDouble("kraken." + path, def); }

    static boolean isWater(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS;
    }

    static boolean deepOcean(Location l) {
        String b = l.getBlock().getBiome().getKey().getKey();
        return b.equals("deep_ocean") || b.equals("deep_cold_ocean") || b.equals("deep_lukewarm_ocean") || b.equals("deep_frozen_ocean");
    }

    /** How many blocks of water are below (and including) this spot. */
    static int waterDepth(Location l) {
        int n = 0;
        for (int y = l.getBlockY(); y > l.getWorld().getMinHeight() && n < 64; y--) {
            if (!isWater(l.getWorld().getBlockAt(l.getBlockX(), y, l.getBlockZ()))) break;
            n++;
        }
        return n;
    }

    private boolean counter(Player p, String id) {
        String s = p.getPersistentDataContainer().get(COUNTERS_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        return s != null && Arrays.asList(s.split(",")).contains(id);
    }

    private int divingTier(Player p) {
        Integer t = p.getPersistentDataContainer().get(DIVING_KEY, org.bukkit.persistence.PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    // ---------- the bait: it sinks for 8 seconds, vanishes, and then he comes ----------
    private static final class Sink {
        final org.bukkit.entity.Item item; final UUID by; int age, sinking = -1; boolean warned;
        Sink(org.bukkit.entity.Item item, UUID by) { this.item = item; this.by = by; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWormDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        ItemStack s = event.getItemDrop().getItemStack();
        if (!s.hasItemMeta() || !s.getItemMeta().getPersistentDataContainer().has(WORM_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) return;
        if (s.getAmount() > 1) { // drop one worm at a time: the rest go back in your hand
            ItemStack rest = s.clone(); rest.setAmount(s.getAmount() - 1);
            s.setAmount(1); event.getItemDrop().setItemStack(s);
            give(event.getPlayer(), rest);
        }
        sinking.put(event.getItemDrop().getUniqueId(), new Sink(event.getItemDrop(), event.getPlayer().getUniqueId()));
    }

    private void baitTick() {
        for (Iterator<Map.Entry<UUID, Sink>> it = sinking.entrySet().iterator(); it.hasNext(); ) {
            Sink s = it.next().getValue();
            org.bukkit.entity.Item item = s.item;
            if (!item.isValid()) { it.remove(); continue; }
            s.age++;
            Player by = Bukkit.getPlayer(s.by);
            if (s.sinking < 0) {
                if (!isWater(item.getLocation().getBlock())) { if (s.age > 200) it.remove(); continue; } // never hit the water
                String why = null;
                if (item.getWorld().getEnvironment() != World.Environment.NORMAL || !deepOcean(item.getLocation())) why = "It needs a Deep Ocean.";
                else if (waterDepth(item.getLocation()) < kcfg("min-depth", 15)) why = "The water isn't deep enough here (it needs " + (int) kcfg("min-depth", 15) + "+ blocks).";
                else if (kraken != null) why = "Something down there is already awake...";
                else if (sinking.values().stream().anyMatch(o -> o != s && o.sinking >= 0)) why = "Another worm is already sinking. Something down there is stirring..."; // BUG FIX: the second worm was lost
                else if (System.currentTimeMillis() < krakenCooldownUntil) why = "The deep is still recovering. Try again in a few minutes.";
                if (why != null) {
                    if (by != null && !s.warned) by.sendActionBar(legacy(ChatColor.DARK_AQUA + "The worm just floats. " + ChatColor.GRAY + why));
                    s.warned = true; it.remove(); continue; // a normal item from now on
                }
                s.sinking = 0;
                // BUG FIX: pickup delay / no-gravity / invulnerable are SAVED with the item, so a restart mid-sink left a worm
                // nobody could ever pick up, hanging in the water. Now pickups are just blocked while it sinks (see below).
                if (by != null) by.sendActionBar(legacy(ChatColor.DARK_AQUA + "The worm sinks into the dark..."));
            }
            int k = ++s.sinking;
            Location l = item.getLocation();
            double wiggle = Math.sin(k * 0.4) * 0.05;
            item.setVelocity(new Vector(wiggle, -0.06, Math.cos(k * 0.4) * 0.05)); // sinks, wriggling
            if (k % 3 == 0) l.getWorld().spawnParticle(Particle.BUBBLE, l.clone().add(0, 0.3, 0), 3, 0.1, 0.1, 0.1, 0.02);
            if (k % 40 == 0) l.getWorld().playSound(l, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.8f, 0.6f);
            if (k >= (int) kcfg("sink-ticks", 160)) { // 8 seconds: it's gone... and then he comes
                it.remove();
                Location at = l.clone();
                item.remove();
                Bukkit.getScheduler().runTaskLater(this, () -> summonKraken(at, by), 30L);
            }
        }
    }

    /** A sinking worm can't be picked up (by players or hoppers), without changing anything saved on the item. */
    @EventHandler(ignoreCancelled = true)
    public void onSinkingPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        Sink s = sinking.get(event.getItem().getUniqueId());
        if (s != null && s.sinking >= 0) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSinkingHopper(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        Sink s = sinking.get(event.getItem().getUniqueId());
        if (s != null && s.sinking >= 0) event.setCancelled(true);
    }

    void summonKraken(Location at, Player by) {
        if (kraken != null) return;
        World w = at.getWorld();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 96 * 96) continue;
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.45f);
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.6f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.5f);
            p.showTitle(Title.title(legacy(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "THE KRAKEN APPEARS"),
                    legacy(ChatColor.GRAY + "from the depths..."),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(900))));
            Vector shake = new Vector(random.nextGaussian() * 0.25, 0.15, random.nextGaussian() * 0.25); // the water shakes
            if (survival(p)) p.setVelocity(p.getVelocity().add(shake));
        }
        kraken = new Kraken(at, by);
        Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Kraken " + ChatColor.GRAY + "has risen from the deep" + (by != null ? " (summoned by " + by.getName() + ")" : "") + "!");
    }

    // ---------- the boss ----------
    private final class Kraken {
        static final int ARMS = 8, SEGS = 9;
        final World world;
        final Location home;
        Vector pos, vel = new Vector();
        float yaw, lean;
        double hp, maxHp, defense;
        int lastAttack = -1;
        int phase = 1, ticks, attack = -1, t, sub, count, recover = 60, transition, intro = 100, lonely, deathT, leaveT, failStreak, lastSeen, pinkTimer, bubbleTimer, eelTimer;
        boolean dying, leaving;
        float scale, pulse, beakOpen, flinch;
        float swell, jig, jigV;                 // wind-up swell; a springy wobble on his head
        Vector lastVel = new Vector();
        float roll, deathFade = 1;                                    // banking into turns; shrinking away at the very end
        int blinkT, nextBlink = 100, hurtT, recoilT, squashT;           // blinks, hit flash, arm recoil, impact squash
        boolean thrusting, rewarded, flashing;
        Vector moveDir = new Vector(0, 0, 1);
        final Slime hitbox;
        ItemDisplay mantle, beakTop, beakBottom, pupilL, pupilR;
        final Arm[] arms = new Arm[ARMS];
        final List<Bubble> airs = new ArrayList<>();
        Pink pink;
        final List<Barnacle> barnacles = new ArrayList<>();
        final List<Eel> eels = new ArrayList<>();
        final List<LivingEntity> minions = new ArrayList<>();
        final Set<UUID> fighters = new HashSet<>(), down = new HashSet<>();
        final Map<UUID, Integer> outTicks = new HashMap<>(), hitCooldown = new HashMap<>();
        final BossBar bar;
        UUID gripped; int gripArm = -1, gripHits, gripT;
        Vector lockPoint, inkCenter; int inkT;
        final List<Vector> barrageTargets = new ArrayList<>();
        long musicStart = -1;
        final Set<UUID> listeners = new HashSet<>();

        final class Arm {
            final int index;
            final ItemDisplay[] segs = new ItemDisplay[SEGS];
            final Vector[] p = new Vector[SEGS + 1];
            final double[] len = new double[SEGS], width = new double[SEGS];
            Vector goal, smooth;
            double follow = 0.15, wave = 0.35, curl = 0.5;
            boolean busy;
            Slime tip;

            Arm(int index) {
                this.index = index;
                // about 22 blocks long and 2.3 wide at the base (an octopus's arms are ~3x its head), tapering to 0.7
                for (int j = 0; j < SEGS; j++) { len[j] = (3.2 - j * 0.18) * scale / 4; width[j] = (2.3 - j * 0.2) * scale / 4; }
                Vector root = root();
                for (int j = 0; j <= SEGS; j++) p[j] = root.clone().add(new Vector(0, -j * 1.5 * scale / 4, 0));
                goal = p[SEGS].clone(); smooth = goal.clone();
                for (int j = 0; j < SEGS; j++)
                    segs[j] = spawnDisplay(p[j].toLocation(world), j == SEGS - 1 ? "kraken_tentacle_tip" : "kraken_tentacle", 1f, 2, Display.Billboard.FIXED);
                tip = (Slime) world.spawnEntity(p[SEGS].toLocation(world), EntityType.SLIME, false);
                tip.setSize(3);
                setupHitbox(tip, 200, KRAKEN_TENT_TAG, "Kraken Tentacle");
            }

            /** Where it joins his head: a ring around the base of his mantle. */
            Vector root() {
                double a = Math.toRadians(index * 45 + 22.5) + Math.toRadians(-yaw);
                double r = 2.2 * scale / 4;
                return pos.clone().add(new Vector(Math.cos(a) * r, -2.3 * scale / 4, Math.sin(a) * r));
            }

            Vector outward() {
                Vector o = root().subtract(pos).setY(0);
                return o.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : o.normalize();
            }

            Vector tipPos() { return p[SEGS].clone(); }

            /** Inverse kinematics (FABRIK): the whole arm bends to reach its goal, then a wave runs down it. */
            void solve() {
                Vector root = root();
                if (!finite(goal)) goal = root.clone().add(new Vector(0, -6, 0));
                smooth.add(goal.clone().subtract(smooth).multiply(follow));
                double total = 0; for (double l : len) total += l;
                Vector to = smooth.clone().subtract(root);
                if (to.length() > total * 0.995) { // out of reach: stretches straight toward it
                    Vector d = safeDir(to, new Vector(0, -1, 0));
                    p[0] = root.clone();
                    for (int j = 0; j < SEGS; j++) p[j + 1] = p[j].clone().add(d.clone().multiply(len[j]));
                } else {
                    for (int iter = 0; iter < 3; iter++) {
                        p[SEGS] = smooth.clone();
                        for (int j = SEGS - 1; j >= 0; j--) p[j] = p[j + 1].clone().add(safeDir(p[j].clone().subtract(p[j + 1]), new Vector(0, 1, 0)).multiply(len[j]));
                        p[0] = root.clone();
                        for (int j = 0; j < SEGS; j++) p[j + 1] = p[j].clone().add(safeDir(p[j + 1].clone().subtract(p[j]), new Vector(0, -1, 0)).multiply(len[j]));
                    }
                }
                // a traveling wave down the arm, so it never looks stiff (stronger toward the tip)
                double sp = speed();
                for (int j = 1; j <= SEGS; j++) {
                    Vector dir = safeDir(p[j].clone().subtract(p[j - 1]), new Vector(0, -1, 0));
                    Vector side = safeDir(dir.getCrossProduct(new Vector(0, 1, 0)), new Vector(1, 0, 0));
                    double w = Math.sin(ticks * 0.12 * sp + j * 0.8 + index * 1.7) * wave * (j / (double) SEGS) * scale / 4;
                    p[j].add(side.multiply(w));
                }
                // the tips curl inward, like a real octopus's (not while the arm is attacking: it has to aim)
                double c = (busy && !dying) ? 0 : curl; // BUG FIX: dying arms are all "busy", so the death clutch never coiled
                if (c > 0.01) {
                    Vector center = pos.clone().add(new Vector(0, -2 * scale / 4, 0));
                    for (int j = SEGS - 3; j <= SEGS; j++) {
                        double kk = (j - (SEGS - 3)) / 3.0;
                        p[j].add(safeDir(center.clone().subtract(p[j]), new Vector(0, 1, 0)).multiply(c * kk * 1.2 * scale / 4));
                    }
                }
                for (int j = 0; j < SEGS; j++) p[j + 1] = p[j].clone().add(safeDir(p[j + 1].clone().subtract(p[j]), new Vector(0, -1, 0)).multiply(len[j]));
            }

            void render() {
                for (int j = 0; j < SEGS; j++) {
                    ItemDisplay d = segs[j];
                    if (d == null || !d.isValid()) continue;
                    Vector a = p[j], b = p[j + 1];
                    Vector mid = a.clone().add(b).multiply(0.5);
                    Vector dir = safeDir(b.clone().subtract(a), new Vector(0, -1, 0));
                    // BUG FIX: item displays draw models turned 180 degrees, so without facing(0) the curled tip pointed BACK
                    // toward his head. The flip makes each segment's +Z (the tip end) point along the arm.
                    Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ())).mul(facing(0));
                    boolean tipSeg = j == SEGS - 1;
                    float wxy = (float) (width[j] / (tipSeg ? 0.25 : 0.375)) * deathFade;
                    Location at = mid.toLocation(world); at.setYaw(0); at.setPitch(0);
                    d.teleport(at);
                    d.setInterpolationDelay(0);
                    d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(wxy, wxy, (float) len[j] * deathFade), new Quaternionf()));
                }
                if (tip != null && tip.isValid()) tip.teleport(p[SEGS].toLocation(world).subtract(0, 0.8, 0));
            }

            void remove() {
                for (ItemDisplay d : segs) if (d != null && d.isValid()) d.remove();
                if (tip != null && tip.isValid()) tip.remove();
            }
        }

        final class Bubble {
            final ItemDisplay d; final Vector pos; int age; boolean gone;
            Bubble(Vector at) {
                pos = at.clone();
                d = spawnDisplay(at.toLocation(world), "kraken_air_bubble", (float) kcfg("air-bubble-scale", 3.0), 2, Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
            }
            void pop() {
                if (gone) return; gone = true;
                world.spawnParticle(Particle.BUBBLE_POP, pos.toLocation(world), 20, 0.8, 0.8, 0.8, 0.05);
                world.playSound(pos.toLocation(world), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1.2f, 0.9f);
                if (d.isValid()) d.remove();
            }
        }

        final class Pink {
            final ItemDisplay d; final Slime box; final Vector pos; int age;
            Pink(Vector at) {
                pos = at.clone();
                d = spawnDisplay(at.toLocation(world), "kraken_pink_bubble", 2.6f, 2, Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(true); d.setGlowColorOverride(Color.fromRGB(255, 110, 200));
                box = (Slime) world.spawnEntity(at.toLocation(world), EntityType.SLIME, false);
                box.setSize(3);
                setupHitbox(box, 50, KRAKEN_PINK_TAG, "Pink Bubble");
            }
            void remove() { if (d.isValid()) d.remove(); if (box.isValid()) box.remove(); }
        }

        final class Barnacle {
            final ItemDisplay d; final Vector pos, v; int life;
            Barnacle(Vector from, Vector v) {
                pos = from.clone(); this.v = v;
                d = spawnDisplay(from.toLocation(world), "kraken_barnacle", 1.3f, 1, Display.Billboard.FIXED);
            }
        }

        final class Eel {
            final ItemDisplay d; final Slime box; final Vector pos; Vector v = new Vector(); int cd, age;
            Eel(Vector at) {
                pos = at.clone();
                d = spawnDisplay(at.toLocation(world), "kraken_eel", 1.8f, 1, Display.Billboard.FIXED);
                box = (Slime) world.spawnEntity(at.toLocation(world), EntityType.SLIME, false);
                box.setSize(1);
                hideForGood(box);
                box.setAI(false); box.setGravity(false); box.setSilent(true); box.setPersistent(false);
                box.addScoreboardTag(KRAKEN_EEL_TAG);
                AttributeInstance h = box.getAttribute(Attribute.MAX_HEALTH);
                if (h != null) h.setBaseValue(kcfg("eel-health", 8));
                box.setHealth(Math.min(kcfg("eel-health", 8), h != null ? h.getValue() : 8));
            }
            void remove() { if (d.isValid()) d.remove(); if (box.isValid()) box.remove(); }
        }

        Kraken(Location at, Player by) {
            world = at.getWorld();
            scale = (float) kcfg("scale", 4.0);
            maxHp = kcfg("health", 2200); hp = maxHp; defense = kcfg("defense", 0.3);
            double floor = seabedY(at.getX(), at.getZ(), at.getY());
            home = new Location(world, at.getX(), Math.max(floor + 9 * scale / 4, at.getY() - 4), at.getZ());
            pos = home.toVector().add(new Vector(0, -14, 0)); // starts deep below, and rises
            if (by != null) {
                Vector to = by.getLocation().toVector().subtract(pos);
                yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                fighters.add(by.getUniqueId());
            }
            hitbox = (Slime) world.spawnEntity(pos.toLocation(world), EntityType.SLIME, false);
            hitbox.setSize((int) Math.max(4, Math.round(11 * scale / 4))); // about 5.7 blocks: matches his 7-block-wide head
            setupHitbox(hitbox, 1000, KRAKEN_TAG, "The Kraken");
            mantle = spawnDisplay(pos.toLocation(world), "kraken_mantle_1", scale, 2, Display.Billboard.FIXED);
            beakTop = spawnDisplay(pos.toLocation(world), "kraken_beak", scale * 0.55f, 2, Display.Billboard.FIXED);
            beakBottom = spawnDisplay(pos.toLocation(world), "kraken_beak", scale * 0.5f, 2, Display.Billboard.FIXED);
            pupilL = spawnDisplay(pos.toLocation(world), "kraken_pupil", scale * 0.55f, 2, Display.Billboard.FIXED);
            pupilR = spawnDisplay(pos.toLocation(world), "kraken_pupil", scale * 0.55f, 2, Display.Billboard.FIXED);
            for (ItemDisplay d : new ItemDisplay[]{mantle, beakTop, beakBottom, pupilL, pupilR}) d.setViewRange(8f);
            for (int i = 0; i < ARMS; i++) arms[i] = new Arm(i);
            for (Arm a : arms) for (ItemDisplay d : a.segs) if (d != null) d.setViewRange(8f);
            proxy(hitbox, mantle, EntityType.GLOW_SQUID, kcfg("bedrock.squid-scale", 10)); // Bedrock: a giant glow squid
            bar = Bukkit.createBossBar(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Kraken", BarColor.BLUE, BarStyle.SEGMENTED_6);
            pinkTimer = (int) kcfg("pink-bubble-seconds", 15) * 20;
            playMusic();
        }

        // ---------- helpers ----------
        double speed() { return phase == 1 ? kcfg("phase1-speed", 0.6) : phase == 2 ? kcfg("phase2-speed", 0.85) : kcfg("phase3-speed", 1.05); }
        int windup(int base) { return (int) Math.round(base * (phase == 1 ? 1.0 : phase == 2 ? 0.8 : 0.65)); }
        Location loc() { return pos.toLocation(world, yaw, 0); }
        boolean finite(Vector v) { return v != null && Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ()); }
        Vector safeDir(Vector v, Vector fallback) { return (v.lengthSquared() > 1e-8 && finite(v)) ? v.clone().normalize() : fallback.clone(); }
        Vector beak() { return pos.clone().add(vec(rotBase().transform(new Vector3f(0, -13, 7).mul(scale / 16f), new Vector3f()))); }
        Vector vec(Vector3f v) { return new Vector(v.x, v.y, v.z); }
        /**
         * BUG FIX: rotModel() includes the 180-degree flip that only DRAWING needs (item displays are drawn turned around).
         * Positions were computed with it too, which mirrored them front-to-back: his pupils sat on the BACK of his head,
         * his beak behind it, and his siphon bubbled out of the wrong side. Offsets use this (no flip) instead.
         */
        Quaternionf rotBase() { return new Quaternionf().rotateY((float) -Math.toRadians(yaw)).rotateX(lean - swell * 0.22f).rotateZ(roll); }

        Quaternionf rotModel() { return new Quaternionf().rotateY((float) -Math.toRadians(yaw)).rotateX(lean - swell * 0.22f).rotateZ(roll).mul(facing(0)); } // swell: leans back

        /** The tip of his siphon (the funnel on his side): his jets and his ink come out of here. */
        Vector siphon() { return pos.clone().add(vec(rotBase().transform(new Vector3f(19, -3, 2).mul(scale / 16f), new Vector3f()))); }
        Vector eye(Player p) { return p.getLocation().toVector().add(new Vector(0, 1.2, 0)); }

        double surface() { return surfaceY(pos.getX(), pos.getZ(), pos.getY()); }

        List<Player> active() {
            List<Player> a = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (!survival(p) || p.isDead() || down.contains(p.getUniqueId())) continue;
                if (p.getLocation().toVector().distanceSquared(pos) > 64 * 64) continue;
                a.add(p);
            }
            return a;
        }

        Player nearest(List<Player> a) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : a) { double d = p.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        Arm armNear(Vector at) {
            Arm best = arms[0]; double bd = Double.MAX_VALUE;
            for (Arm a : arms) { if (a.busy) continue; double d = a.root().distanceSquared(at); if (d < bd) { bd = d; best = a; } }
            return best;
        }

        void strike(Player p, double dmg, Guard guard, Vector from) {
            if (hitCooldown.containsKey(p.getUniqueId())) return;
            hitCooldown.put(p.getUniqueId(), 8);
            hurt(p, dmg, hitbox, from, guard);
        }

        void say(String text) {
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 80 * 80) p.sendActionBar(legacy(text));
        }

        // ---------- the update ----------
        void tick() {
            ticks++;
            if (!hitbox.isValid() && !dying && !leaving) { removeEverything(); kraken = null; return; } // his area unloaded
            if (hitbox.getHealth() < 900 && !dying) hitbox.setHealth(1000);
            if (dying) { bossPart("Kraken", "death", this::deathTick); if (kraken != this) return; renderAll(); return; }
            if (leaving) { bossPart("Kraken", "leaving", this::leaveTick); if (kraken != this) return; renderAll(); return; }
            for (Player p : world.getPlayers()) if (survival(p) && !p.isDead() && p.getLocation().toVector().distanceSquared(pos) < 48 * 48) join(p);
            List<Player> a = active();
            if (a.isEmpty()) {
                if (++lonely > 400) { leave(ChatColor.DARK_AQUA + "The Kraken sinks back into the abyss..."); return; }
            } else lonely = 0;
            try {
                if (intro > 0) introTick();
                else if (transition > 0) transitionTick();
                else if (!a.isEmpty()) attackTick(a);
                failStreak = 0;
            } catch (RuntimeException e) {
                logBossPart("Kraken", "move " + attack + " (step " + sub + ", tick " + t + ")", e);
                endAttack(20);
                releaseGrip();
                if (++failStreak > 100) { leave(ChatColor.DARK_AQUA + "The Kraken sinks back into the abyss..."); return; }
            }
            bossPart("Kraken", "swimming", () -> swim(a));
            bossPart("Kraken", "out of the water", () -> outOfWater(a));
            bossPart("Kraken", "air bubbles", () -> airTick(a));
            bossPart("Kraken", "pink bubble", this::pinkTick);
            bossPart("Kraken", "barnacles", () -> barnacleTick(a));
            bossPart("Kraken", "eels", () -> eelTick(a));
            bossPart("Kraken", "ink", () -> inkTick(a));
            bossPart("Kraken", "grip", this::gripTick);
            bossPart("Kraken", "watchdog", () -> watchdog(a));
            renderAll();
            minions.removeIf(m -> !m.isValid() || m.isDead());
            hitCooldown.replaceAll((k, v) -> v - 1);
            hitCooldown.values().removeIf(v -> v <= 0);
            bossPart("Kraken", "boss bar", this::updateBar);
            if (ticks % 10 == 0) bossPart("Kraken", "music", this::musicTick);
        }

        void renderAll() {
            bossPart("Kraken", "arms", () -> { for (Arm arm : arms) { arm.solve(); arm.render(); } });
            bossPart("Kraken", "drawing", this::render);
        }

        void join(Player p) {
            fighters.add(p.getUniqueId());
            down.remove(p.getUniqueId());
            // BUG FIX: only players nearby at the roar got the music, so anyone who swam in later fought in silence
            if (musicStart >= 0 && listeners.add(p.getUniqueId())) {
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:kraken.music", SoundCategory.RECORDS, (float) kcfg("music.volume", 1.0), 1f);
            }
        }

        // ---------- rising out of the dark ----------
        void introTick() {
            intro--;
            Vector to = home.toVector().subtract(pos);
            pos.add(to.multiply(0.035));
            pulse = (float) Math.max(0, Math.sin(ticks * 0.25)) * 0.6f;
            for (Arm a : arms) { a.goal = a.root().add(new Vector(0, -14 * scale / 4, 0)).add(a.outward().multiply(9 * scale / 4)); a.follow = 0.08; a.wave = 0.6; }
            if (intro % 20 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos.toLocation(world), 60, 3, 3, 3, 0.1);
            if (intro == 10) {
                beakOpen = 1;
                world.playSound(pos.toLocation(world), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.4f);
            }
        }

        // ---------- phases ----------
        void checkPhase() {
            int want = hp < maxHp * kcfg("phase3-at", 0.33) ? 3 : hp < maxHp * kcfg("phase2-at", 0.66) ? 2 : 1;
            if (want <= phase) return;
            phase = want;
            transition = 60;
            endAttack(0);
            releaseGrip();
            mantle.setItemStack(modelItem("kraken_mantle_" + phase)); // glowing veins
            world.playSound(pos.toLocation(world), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, phase == 2 ? 0.55f : 0.35f);
            world.playSound(pos.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.6f);
            say(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + (phase == 2 ? "The Kraken is enraged!" : "The Kraken calls the eels of the deep!"));
        }

        void transitionTick() {
            transition--;
            beakOpen = 1;
            pulse = (float) Math.abs(Math.sin(transition * 0.4)) * 0.8f;
            flinch = (float) Math.sin(transition * 0.9) * 0.3f;
            for (Arm a : arms) { // every arm thrashes
                a.goal = a.root().add(a.outward().multiply(9 * scale / 4)).add(new Vector(random.nextGaussian() * 0.8, 14 * scale / 4, random.nextGaussian() * 0.8)); // raised high, trembling
                a.follow = 0.25; a.wave = 0.9; a.curl = 0.2;
            }
            if (transition % 6 == 0) world.spawnParticle(Particle.BUBBLE, pos.toLocation(world), 80, 4, 4, 4, 0.2);
            if (transition % 4 == 0) mantle.setItemStack(modelItem("kraken_mantle_" + ((transition / 4) % 2 == 0 ? phase : phase - 1))); // the veins flicker on
            if (transition == 0) { beakOpen = 0; mantle.setItemStack(modelItem("kraken_mantle_" + phase)); }
        }

        // ---------- swimming: slow jet pulses, and he never leaves the water ----------
        void swim(List<Player> a) {
            Player target = nearest(a);
            double sp = speed();
            if (target != null && intro <= 0 && !dying) {
                Vector tp = target.getLocation().toVector();
                Vector to = tp.clone().subtract(pos);
                float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                float diff = ((want - yaw) % 360 + 540) % 360 - 180;
                float turn = (float) Math.max(-3 * sp, Math.min(3 * sp, diff));
                yaw += turn;
                roll += ((float) Math.max(-0.35, Math.min(0.35, -turn * 0.09)) - roll) * 0.1f; // banks into the turn
                boolean holdsStill = attack == 3 || attack == 4 || attack == 9 || attack == 11 || attack == 7;
                if (!holdsStill && attack != 5) {
                    int cycle = (int) Math.max(20, 40 / sp), c = ticks % cycle;
                    double flat = Math.hypot(to.getX(), to.getZ());
                    Vector station = tp.clone().add(new Vector(0, 2, 0));
                    if (flat > 0.1) station.subtract(new Vector(to.getX(), 0, to.getZ()).multiply(Math.min(1, 10 / flat))); // ~10 blocks away
                    thrusting = c < 10;
                    if (c < 10) { // the jet: contracts, pushes, and a stream of bubbles shoots out of his siphon
                        pulse = (float) Math.sin(c / 10.0 * Math.PI);
                        Vector push = safeDir(station.subtract(pos), new Vector()).multiply(0.06 * sp);
                        vel.add(push);
                        if (c % 2 == 0) {
                            Vector back = safeDir(push.clone().multiply(-1), new Vector(0, 0, -1));
                            Location sl = siphon().toLocation(world);
                            for (int i = 0; i < 4; i++) world.spawnParticle(Particle.BUBBLE, sl.clone().add(back.clone().multiply(i * 0.8)), 3, 0.2, 0.2, 0.2, 0.02);
                        }
                    } else pulse *= 0.85f;
                }
            }
            if (!finite(vel)) vel = new Vector();
            double cap = 0.9 * sp + (attack == 5 ? 1.0 : 0);
            if (vel.length() > cap) vel = vel.normalize().multiply(cap);
            pos.add(vel);
            if (vel.lengthSquared() > 0.0004) moveDir = vel.clone().normalize();
            vel.multiply(0.94);
            if (attack < 0) roll *= 0.97f;
            // his head tilts toward whoever he's after (front up when you're above him), on top of leaning into his speed
            float toward = 0;
            Player tilt = target;
            if (tilt != null) {
                Vector to = eye(tilt).subtract(pos);
                toward = (float) Math.max(-0.35, Math.min(0.35, -Math.atan2(to.getY(), Math.max(1, Math.hypot(to.getX(), to.getZ()))) * 0.6));
            }
            lean += ((float) Math.max(-0.5, Math.min(0.5, vel.length() * 0.5 + toward)) - lean) * 0.1f;
            // a springy wobble whenever his speed changes (a thrust, a lunge, a stop)
            double kick = vel.clone().subtract(lastVel).length();
            lastVel = vel.clone();
            jigV += (float) Math.min(0.12, kick * 0.35); // tuned by simulation: ~6% on a jet, ~11% on a lunge
            // (the spring itself steps in render(), which runs every tick, even while he dies)
            keepInWater();
        }

        /** BUG-PROOFING: never above the water, never in the seabed, never wandering off from his arena. */
        void keepInWater() {
            if (!finite(pos)) pos = home.toVector();
            Vector flat = pos.clone().subtract(home.toVector()).setY(0);
            double limit = kcfg("arena-radius", 24);
            if (flat.length() > limit) { pos = home.toVector().add(flat.normalize().multiply(limit)).setY(pos.getY()); vel.multiply(0.3); }
            double top = surfaceY(pos.getX(), pos.getZ(), Math.min(pos.getY(), home.getY() + 30)) - 5.5 * scale / 4;
            double floor = seabedY(pos.getX(), pos.getZ(), pos.getY()) + 4.5 * scale / 4;
            if (top < floor) { // a shallow spot: the middle of the water column
                double mid = (top + floor) / 2; pos.setY(mid);
            } else if (pos.getY() > top) { pos.setY(top); if (vel.getY() > 0) vel.setY(0); }
            else if (pos.getY() < floor && intro <= 0) { pos.setY(floor); if (vel.getY() < 0) vel.setY(0); }
        }

        double surfaceY(double x, double z, double fromY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
            if (!isWater(world.getBlockAt(bx, y, bz))) { // above the water: come down to it
                for (int k = 0; k < 64 && y > world.getMinHeight(); k++, y--) if (isWater(world.getBlockAt(bx, y, bz))) return y + 1;
                return fromY;
            }
            for (int k = 0; k < 128 && y < world.getMaxHeight() - 1; k++, y++) if (!isWater(world.getBlockAt(bx, y, bz))) return y;
            return fromY + 32;
        }

        double seabedY(double x, double z, double fromY) { return FaultlineBosses.this.seabedY(world, x, z, fromY); }

        // ---------- out of the water: two hits and you're dead ----------
        void outOfWater(List<Player> a) {
            if (intro > 0) return;
            for (Player p : a) {
                boolean in = p.isInWater() || isWater(p.getEyeLocation().getBlock());
                int n = in ? 0 : outTicks.merge(p.getUniqueId(), 1, Integer::sum);
                if (in) { outTicks.remove(p.getUniqueId()); continue; }
                if (n == (int) kcfg("out-of-water.warn-ticks", 30))
                    p.sendActionBar(legacy(ChatColor.RED + "" + ChatColor.BOLD + "GET BACK IN THE WATER!"));
                if (n >= (int) kcfg("out-of-water.smash-ticks", 50)) {
                    Arm arm = armNear(p.getLocation().toVector());
                    arm.goal = eye(p); arm.follow = 0.9;
                    double cut = p.getAttribute(Attribute.MAX_HEALTH).getValue() * kcfg("out-of-water.smash-fraction", 0.55);
                    world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2f, 0.4f);
                    world.spawnParticle(Particle.SPLASH, p.getLocation(), 60, 1, 1, 1, 0.2);
                    // BUG FIX: a direct p.damage() from his hitbox was cancelled by the slime touch-damage gate, so the
                    // second smash never killed anyone. hurt() is the path that gate lets through.
                    if (p.getHealth() - cut <= 0.5) hurt(p, 10000, hitbox, pos, Guard.UNBLOCKABLE); // the second smash
                    else { p.setHealth(p.getHealth() - cut); p.playHurtAnimation(0); }
                    p.setVelocity(safeDir(p.getLocation().toVector().subtract(pos).setY(0), new Vector(1, 0, 0)).multiply(-0.6).setY(-0.4)); // dragged back toward the water
                    outTicks.put(p.getUniqueId(), (int) kcfg("out-of-water.smash-ticks", 50) - 20); // the next one comes fast
                }
            }
        }

        // ---------- air bubbles: stay in them to breathe ----------
        void airTick(List<Player> a) {
            if (--bubbleTimer <= 0) {
                bubbleTimer = phase == 1 ? 100 : phase == 2 ? 90 : 80;
                int n = 2 + random.nextInt(2);
                for (int i = 0; i < n && airs.size() < 10; i++) spawnAir();
            }
            for (Bubble b : airs) {
                if (b.gone) continue;
                b.age++;
                b.pos.add(new Vector(Math.sin(b.age * 0.05) * 0.01, 0.03, 0));
                if (b.age > 300 || b.pos.getY() > surfaceY(b.pos.getX(), b.pos.getZ(), b.pos.getY()) - 2) { b.pop(); continue; }
                Location l = b.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                b.d.teleport(l);
                if (b.age % 10 == 0) world.spawnParticle(Particle.BUBBLE, l, 4, 0.6, 0.6, 0.6, 0.02);
                for (Player p : a) {
                    if (eye(p).distanceSquared(b.pos) > 2.0 * 2.0) continue;
                    if (counter(p, "coral_lung")) { // Coral Lung: all your air, and 5 seconds of water breathing
                        if (p.getRemainingAir() < p.getMaximumAir()) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BREATH, 0.8f, 1.2f);
                        p.setRemainingAir(p.getMaximumAir());
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 0, true, false, true));
                    } else p.setRemainingAir(Math.min(p.getMaximumAir(), p.getRemainingAir() + 15));
                }
            }
            airs.removeIf(b -> b.gone);
        }

        void spawnAir() {
            for (int tries = 0; tries < 8; tries++) {
                double ang = random.nextDouble() * Math.PI * 2, dist = 6 + random.nextDouble() * 12;
                Vector at = pos.clone().add(new Vector(Math.cos(ang) * dist, random.nextDouble() * 6 - 4, Math.sin(ang) * dist));
                if (!isWater(at.toLocation(world).getBlock())) continue;
                airs.add(new Bubble(at));
                return;
            }
        }

        void popAirNear(Vector c, double r) { for (Bubble b : airs) if (b.pos.distanceSquared(c) < r * r) b.pop(); }

        // ---------- the pink bubble: pop it for 150-200 damage ----------
        void pinkTick() {
            if (intro > 0 || transition > 0) return;
            if (pink != null) {
                pink.age++;
                // BUG FIX: it could rise out of the water, so popping it meant surfacing (and getting smashed)
                if (pink.pos.getY() < surfaceY(pink.pos.getX(), pink.pos.getZ(), pink.pos.getY()) - 2.5) pink.pos.add(new Vector(0, 0.015, 0));
                Location l = pink.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                pink.d.teleport(l);
                if (pink.box.isValid()) pink.box.teleport(l.clone().subtract(0, 0.8, 0));
                if (pink.age % 6 == 0) world.spawnParticle(Particle.DUST, l, 3, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.2f));
                if (pink.age > 240) { pink.remove(); pink = null; }
                return;
            }
            if (--pinkTimer > 0) return;
            pinkTimer = (int) kcfg("pink-bubble-seconds", 15) * 20;
            for (int tries = 0; tries < 8; tries++) {
                double ang = random.nextDouble() * Math.PI * 2, dist = 7 + random.nextDouble() * 6;
                Vector at = pos.clone().add(new Vector(Math.cos(ang) * dist, random.nextDouble() * 4 - 2, Math.sin(ang) * dist));
                if (!isWater(at.toLocation(world).getBlock())) continue;
                pink = new Pink(at);
                world.playSound(at.toLocation(world), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f, 0.6f);
                say(ChatColor.LIGHT_PURPLE + "A pink bubble rises! Pop it!");
                return;
            }
        }

        void popPink(Player by) {
            if (pink == null || intro > 0 || transition > 0) return; // he's invulnerable right now: it stays for later
            Location l = pink.pos.toLocation(world);
            world.spawnParticle(Particle.DUST, l, 60, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.8f));
            world.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 0.7f);
            pink.remove(); pink = null;
            double dmg = kcfg("pink-damage-min", 150) + random.nextDouble() * (kcfg("pink-damage-max", 200) - kcfg("pink-damage-min", 150));
            damage(dmg, by, true);
            flinch = 1;
        }

        void damage(double amount, Player by, boolean exact) {
            if (dying || intro > 0 || transition > 0) return;
            hp -= exact ? amount : amount * (1 - defense);
            flinch = Math.max(flinch, 0.5f);
            hurtT = 10; recoilT = 6;
            if (by != null) join(by);
            if (hp <= 0) { die(); return; }
            checkPhase();
        }

        // ---------- moves ----------
        void endAttack(int rec) {
            attack = -1; t = 0; sub = 0; count = 0;
            recover = rec;
            for (Arm a : arms) { a.busy = false; }
            beakOpen = 0;
        }

        void attackTick(List<Player> a) {
            idleArms();
            if (recover > 0) { recover--; return; }
            Player target = nearest(a);
            if (target == null) return;
            if (attack < 0) {
                List<Integer> pool = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
                if (phase == 3) pool.addAll(List.of(9, 10, 11, 9, 10, 11));
                if (minions.size() >= 4) pool.removeIf(m -> m == 8);
                int pick;
                do pick = pool.get(random.nextInt(pool.size())); while (pick == lastAttack && pool.size() > 1);
                attack = pick; lastAttack = pick; t = 0; sub = 0; count = 0;
            }
            switch (attack) {
                case 1 -> tentacleSlam(target, a);
                case 2 -> crushingGrip(target);
                case 3 -> inkCloud(target);
                case 4 -> whirlpool(a, false);
                case 5 -> beakBite(target, a);
                case 6 -> barnacleBarrage(a);
                case 7 -> tidalSurge(a);
                case 8 -> spawnOfTheDeep();
                case 9 -> whirlpool(a, true);
                case 10 -> eightArmBarrage(a);
                case 11 -> crushingDepths(a);
                default -> endAttack(10);
            }
            t++;
        }

        int rest() { return phase == 1 ? 50 : phase == 2 ? 36 : 26; }

        /** Arms that aren't doing anything drift and curl around him. */
        void idleArms() {
            double s = scale / 4;
            Player near = null; double bd = 25 * 25;
            for (Player p : world.getPlayers()) { if (!survival(p)) continue; double d = eye(p).distanceSquared(pos); if (d < bd) { bd = d; near = p; } }
            // his two front-most arms are the curious ones
            Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            int first = 0, second = 1; double d1 = -9, d2 = -9;
            for (Arm arm : arms) { double d = arm.outward().dot(fwd); if (d > d1) { d2 = d1; second = first; d1 = d; first = arm.index; } else if (d > d2) { d2 = d; second = arm.index; } }
            for (Arm arm : arms) {
                if (arm.busy) continue;
                Vector g;
                if (thrusting) { // the jet: every arm sweeps back together behind him, streamlined
                    g = arm.root().subtract(moveDir.clone().multiply(17 * s)).add(arm.outward().multiply(2.5 * s));
                    arm.follow = 0.22; arm.wave = 0.15; arm.curl = 0.1;
                } else { // gliding: they flare back out and drift, tips curled in
                    g = arm.root().add(arm.outward().multiply(13 * s)).add(new Vector(0, -11 * s, 0)); // ~17 of its 22 blocks: extended, not crumpled
                    g.add(new Vector(Math.sin(ticks * 0.03 + arm.index) * 2.5 * s, Math.sin(ticks * 0.05 + arm.index * 2) * 1.5 * s, Math.cos(ticks * 0.03 + arm.index) * 2.5 * s));
                    arm.follow = 0.08; arm.wave = 0.4;
                    arm.curl = 0.45 + 0.35 * Math.sin(ticks * 0.035 + arm.index * 1.3); // each arm curls and uncurls on its own rhythm
                    if (near != null && (arm.index == first || arm.index == second)) { g = g.multiply(0.65).add(eye(near).multiply(0.35)); arm.curl = 0.3; } // lazily reaching for you
                }
                if (recoilT > 0) g.add(arm.outward().multiply(recoilT * 0.8)); // flinches away when he's hit
                arm.goal = g;
            }
        }

        void ring(Vector c, double r, Color col) {
            for (int i = 0; i < 20; i++) {
                double a = Math.PI * 2 * i / 20;
                world.spawnParticle(Particle.DUST, c.toLocation(world).add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f));
            }
        }

        /** 1) Tentacle Slam: an arm coils up high, a red ring marks where it lands, then it slams. */
        void tentacleSlam(Player target, List<Player> a) {
            int w = windup(34);
            if (t == 0) { Arm arm = armNear(target.getLocation().toVector()); arm.busy = true; sub = arm.index; }
            Arm arm = arms[sub];
            if (t < w - 12) lockPoint = target.getLocation().toVector();
            if (t < w) {
                arm.goal = lockPoint.clone().add(new Vector(Math.cos(t * 0.35) * 2.2, 9, Math.sin(t * 0.35) * 2.2)); arm.follow = 0.12; arm.wave = 0.7; // coils up, writhing
                if (t % 3 == 0) ring(lockPoint, 3.2, Color.fromRGB(220, 40, 40));
            } else if (t < w + 8) {
                arm.goal = lockPoint.clone().add(new Vector(0, -1.5, 0)); arm.follow = 0.65; arm.wave = 0.1; // SLAM
                if (t == w + 3) {
                    // BUG FIX: the hit was centered on the warning ring even when the arm couldn't reach it (you swam out
                    // of its ~22-block reach), so you got hit by a slam that never touched you. It's centered on the tip now.
                    Vector impact = arm.tipPos();
                    squashT = 6; // the whole head jolts with it
                    world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
                    world.spawnParticle(Particle.BUBBLE_POP, impact.toLocation(world), 80, 2, 1, 2, 0.15);
                    for (Player p : a) if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(impact) < 3.6 * 3.6) {
                        strike(p, kcfg("damage.slam", 22), Guard.HEAVY, arm.tipPos());
                        p.setVelocity(p.getLocation().toVector().subtract(impact).setY(0).multiply(0.3).setY(-0.5));
                    }
                }
            } else if (t <= w + 22) {
                arm.goal = lockPoint.clone().add(new Vector(0, 5, 0)); arm.follow = 0.12; arm.wave = 0.3; // recoils back up after the hit
            } else endAttack(rest());
        }

        /** 2) Crushing Grip: an arm reaches for you; if it catches you, it drags you down and away from the air. */
        void crushingGrip(Player target) {
            if (t == 0) { Arm arm = armNear(target.getLocation().toVector()); arm.busy = true; sub = arm.index; }
            Arm arm = arms[sub];
            // BUG FIX: once you broke free, "gripped" was empty again, so the move went straight back to reaching and
            // grabbed you again on the spot. count = 1 now means "already grabbed once": breaking free ends the move.
            if (count == 1) { if (gripped == null) endAttack(rest()); return; }
            if (gripped == null) {
                arm.goal = eye(target); arm.follow = 0.18 * speed() + 0.06; arm.wave = 0.25;
                if (arm.tipPos().distanceSquared(eye(target)) < 2.6 * 2.6 && t > 6) {
                    count = 1;
                    gripped = target.getUniqueId(); gripArm = sub; gripHits = 0; gripT = 0;
                    world.playSound(target.getLocation(), Sound.ENTITY_SLIME_SQUISH, 2f, 0.5f);
                    target.sendActionBar(legacy(ChatColor.RED + "" + ChatColor.BOLD + "GRABBED! " + ChatColor.GRAY + "Hit the tentacle to break free!"));
                } else if (t > windup(60)) endAttack(rest());
            } else if (gripArm < 0) endAttack(rest()); // released (broke free)
        }

        void gripTick() {
            if (gripped == null) return;
            Player p = Bukkit.getPlayer(gripped);
            if (p == null || p.isDead() || gripArm < 0) { releaseGrip(); return; }
            Arm arm = arms[gripArm];
            gripT++;
            boolean eel = counter(p, "eelskin_wrap");
            int needHits = eel ? 1 : 3, maxT = eel ? 33 : 100;
            // drags you down and away from the nearest air bubble
            Vector away = new Vector(0, -1, 0);
            Bubble near = null; double bd = Double.MAX_VALUE;
            for (Bubble b : airs) { double d = b.pos.distanceSquared(eye(p)); if (d < bd) { bd = d; near = b; } }
            if (near != null) away.add(safeDir(eye(p).subtract(near.pos), new Vector()).multiply(0.8));
            // the tip wraps around you (circling your body) while the arm drags you down and away from the air
            arm.goal = eye(p).add(new Vector(Math.cos(gripT * 0.45) * 0.9, -0.4, Math.sin(gripT * 0.45) * 0.9)); arm.follow = 0.35;
            p.setVelocity(safeDir(away, new Vector(0, -1, 0)).multiply(0.28));
            if (gripT % 20 == 0) strike(p, kcfg("damage.grip-per-second", 4), Guard.UNBLOCKABLE, arm.tipPos());
            if (gripHits >= needHits || gripT >= maxT) {
                p.sendActionBar(legacy(ChatColor.GREEN + "You broke free!"));
                p.setVelocity(p.getVelocity().add(safeDir(eye(p).subtract(pos), new Vector(0, 1, 0)).multiply(0.8)));
                releaseGrip();
            }
        }

        void releaseGrip() {
            if (gripArm >= 0) arms[gripArm].busy = false;
            gripped = null; gripArm = -1; gripHits = 0;
        }

        /** 3) Ink Cloud: a jet of ink smothers the water around you (Darkness + Blindness). */
        void inkCloud(Player target) {
            beakOpen = t < 16 ? t / 16f : Math.max(0, 1 - (t - 16) / 10f);
            pulse = t >= 8 && t <= 16 ? (float) Math.sin((t - 8) / 8.0 * Math.PI) : pulse * 0.85f; // he clenches to squirt it
            if (t == 14) {
                inkCenter = pos.clone().add(target.getLocation().toVector().subtract(pos).multiply(0.6));
                inkT = 100;
                world.playSound(pos.toLocation(world), Sound.ENTITY_SQUID_SQUIRT, 3f, 0.4f);
                Vector from = siphon(); // octopuses ink through their siphon
                Vector dir = safeDir(inkCenter.clone().subtract(from), new Vector(0, 0, 1));
                for (int i = 0; i < 30; i++) world.spawnParticle(Particle.SQUID_INK, from.clone().add(dir.clone().multiply(i * 0.5)).toLocation(world), 6, 0.3, 0.3, 0.3, 0.02);
            }
            if (t > 30) endAttack(rest());
        }

        void inkTick(List<Player> a) {
            if (inkT <= 0 || inkCenter == null) return;
            inkT--;
            double r = 7;
            if (inkT % 2 == 0) world.spawnParticle(Particle.SQUID_INK, inkCenter.toLocation(world), 30, r * 0.5, r * 0.4, r * 0.5, 0.01);
            for (Player p : a) {
                if (eye(p).distanceSquared(inkCenter) > r * r) continue;
                if (counter(p, "glow_gland")) { // Glow Gland: immune, and you can see through it
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 60, 0, true, false, true));
                    continue;
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, true));
            }
        }

        /** 4) Whirlpool (and 9: the Abyssal Vortex): pulls everyone toward his beak. */
        void whirlpool(List<Player> a, boolean abyssal) {
            int dur = abyssal ? 140 : 90;
            double range = abyssal ? 26 : 18, pullBase = abyssal ? 0.16 : 0.1;
            beakOpen = 1;
            Vector c = beak();
            if (t == 0) {
                world.playSound(c.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 2.5f, 0.5f);
                if (abyssal) { popAirNear(c, 12); inkCenter = c.clone(); inkT = 80; say(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ABYSSAL VORTEX!"); }
            }
            for (Arm arm : arms) { // every arm spins around him
                double ang = Math.toRadians(arm.index * 45) + t * 0.15;
                arm.goal = pos.clone().add(new Vector(Math.cos(ang) * 15, -4, Math.sin(ang) * 15)); arm.follow = 0.2; arm.wave = 0.5;
            }
            if (t % 3 == 0) for (int i = 0; i < 3; i++) {
                double ang = t * 0.3 + i * 2.1, rr = range * (1 - (t % 30) / 30.0);
                world.spawnParticle(Particle.BUBBLE, c.toLocation(world).add(Math.cos(ang) * rr, Math.sin(t * 0.1) * 2, Math.sin(ang) * rr), 4, 0.2, 0.2, 0.2, 0.01);
            }
            for (Player p : a) {
                Vector to = c.clone().subtract(eye(p));
                double d = to.length();
                if (d > range || d < 0.1) continue;
                double pull = pullBase * (counter(p, "eelskin_wrap") ? 0.5 : 1);
                Vector swirl = safeDir(new Vector(-to.getZ(), 0, to.getX()), new Vector()).multiply(pull * 0.6); // straight above/below: no swirl (not NaN)
                p.setVelocity(p.getVelocity().multiply(0.8).add(to.normalize().multiply(pull)).add(swirl));
                if (d < 3.5 && t % 20 == 0) strike(p, kcfg("damage.whirlpool-bite", 8), Guard.UNBLOCKABLE, c);
            }
            if (t >= dur) endAttack(rest());
        }

        /** 5) Beak Bite: rears back with his beak wide open, then lunges. */
        void beakBite(Player target, List<Player> a) {
            int w = windup(24);
            if (t < w) {
                beakOpen = t / (float) w;
                Vector back = safeDir(pos.clone().subtract(target.getLocation().toVector()), new Vector(0, 0, 1));
                vel.add(back.multiply(0.01));
                for (Arm arm : arms) { arm.goal = arm.root().add(new Vector(0, 6, 0)).add(arm.outward().multiply(15)); arm.follow = 0.1; } // spread wide
            } else if (t == w) {
                vel = safeDir(eye(target).subtract(pos), new Vector(0, 0, 1)).multiply(1.4 * speed() + 0.5);
                world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, 2.5f, 0.5f);
            } else if (t < w + 14) {
                beakOpen = 1;
                for (Arm arm : arms) { arm.goal = arm.root().subtract(safeDir(vel, new Vector(0, 0, 1)).multiply(17)).add(arm.outward().multiply(3)); arm.follow = 0.3; } // trail behind
                for (Player p : a) if (eye(p).distanceSquared(beak()) < 4.5 * 4.5) strike(p, kcfg("damage.bite", 20), Guard.HEAVY, beak());
            } else {
                beakOpen = Math.max(0, beakOpen - 0.1f);
                if (t > w + 26) endAttack(rest());
            }
        }

        /** 6) Barnacle Barrage: three volleys of barnacles spat at everyone. */
        void barnacleBarrage(List<Player> a) {
            beakOpen = (t % 20) < 8 ? 1 : 0.3f;
            if (t % 20 == 8 && t <= 48) {
                world.playSound(pos.toLocation(world), Sound.ENTITY_LLAMA_SPIT, 2.5f, 0.5f);
                for (Player p : a) {
                    Vector aim = safeDir(eye(p).subtract(beak()), new Vector(0, 0, 1));
                    for (int i = -2; i <= 2; i++) barnacles.add(new Barnacle(beak(), aim.clone().rotateAroundY(Math.toRadians(i * 9)).multiply(0.7)));
                }
            }
            if (t > 60) endAttack(rest());
        }

        void barnacleTick(List<Player> a) {
            for (Iterator<Barnacle> it = barnacles.iterator(); it.hasNext(); ) {
                Barnacle b = it.next();
                b.pos.add(b.v); b.life++;
                Location l = b.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                if (b.d.isValid()) b.d.teleport(l);
                boolean done = b.life > 60 || (world.getBlockAt(l).getType().isSolid());
                for (Player p : a) if (!done && eye(p).distanceSquared(b.pos) < 1.4 * 1.4) { strike(p, kcfg("damage.barnacle", 8), Guard.BLOCKABLE, b.pos); done = true; }
                if (done) { if (b.d.isValid()) b.d.remove(); it.remove(); }
            }
        }

        /** 7) Tidal Surge: he clenches, then a ring of force rushes outward and pops the air bubbles it passes. */
        void tidalSurge(List<Player> a) {
            int w = windup(26);
            if (t < w) {
                pulse = t / (float) w;
                for (Arm arm : arms) { arm.goal = arm.root().add(new Vector(0, -2, 0)).add(arm.outward().multiply(2)); arm.follow = 0.15; } // curls in tight
            } else {
                double r = (t - w) * 0.9;
                pulse = Math.max(0, pulse - 0.15f);
                for (Arm arm : arms) { arm.goal = arm.root().add(arm.outward().multiply(r + 3)); arm.follow = 0.4; } // flings out
                if (t == w) world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.5f);
                if (t % 2 == 0) for (int i = 0; i < 28; i++) {
                    double ang = Math.PI * 2 * i / 28;
                    world.spawnParticle(Particle.BUBBLE_POP, pos.toLocation(world).add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 1, 0, 0.6, 0, 0);
                }
                popAirNear(pos, r);
                for (Player p : a) {
                    Vector d = p.getLocation().toVector().subtract(pos);
                    double flat = Math.hypot(d.getX(), d.getZ());
                    if (Math.abs(flat - r) < 1.2 && Math.abs(eye(p).getY() - pos.getY()) < 2.0) {
                        strike(p, kcfg("damage.surge", 15), Guard.HEAVY, pos);
                        p.setVelocity(safeDir(d.setY(0), new Vector(1, 0, 0)).multiply(1.1).setY(0.3));
                    }
                }
                if (r > 22) endAttack(rest());
            }
        }

        /** 8) Spawn of the Deep: Drowned with tridents rise to help him. */
        void spawnOfTheDeep() {
            beakOpen = 0.6f;
            if (t == 10) {
                world.playSound(pos.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.8f);
                for (int i = 0; i < 3 && minions.size() < 6; i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    Location l = pos.toLocation(world).add(Math.cos(ang) * 6, -2, Math.sin(ang) * 6);
                    if (!isWater(l.getBlock())) continue;
                    org.bukkit.entity.Drowned d = world.spawn(l, org.bukkit.entity.Drowned.class, z -> {
                        z.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                        z.getEquipment().setItemInMainHandDropChance(0f);
                        z.setPersistent(false);
                        z.setRemoveWhenFarAway(false);
                        z.addScoreboardTag(KRAKEN_MINION_TAG);
                        z.setCustomName(ChatColor.DARK_AQUA + "Spawn of the Deep");
                        AttributeInstance r = z.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                        if (r != null) r.setBaseValue(0);
                    });
                    minions.add(d);
                    world.spawnParticle(Particle.BUBBLE_COLUMN_UP, l, 30, 0.4, 1, 0.4, 0.05);
                }
            }
            if (t > 30) endAttack(rest());
        }

        /** 10) Eight-Arm Barrage: all eight arms slam, one after another, sweeping around him. */
        void eightArmBarrage(List<Player> a) {
            int gap = windup(9);
            if (t == 0) {
                barrageTargets.clear();
                for (int i = 0; i < ARMS; i++) {
                    Player p = a.get(i % a.size());
                    double ang = Math.toRadians(i * 45);
                    barrageTargets.add(p.getLocation().toVector().add(new Vector(Math.cos(ang) * 3, 0, Math.sin(ang) * 3)));
                    arms[i].busy = true;
                }
                say(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "EIGHT-ARM BARRAGE!");
            }
            for (int i = 0; i < ARMS; i++) {
                int slamAt = 20 + i * gap;
                Vector at = barrageTargets.get(i);
                Arm arm = arms[i];
                if (t < slamAt) {
                    arm.goal = at.clone().add(new Vector(0, 8, 0)); arm.follow = 0.12;
                    if (t % 3 == 0 && t > slamAt - 18) ring(at, 3, Color.fromRGB(220, 40, 40));
                } else if (t < slamAt + 6) {
                    arm.goal = at.clone().add(new Vector(0, -1.5, 0)); arm.follow = 0.7;
                    if (t == slamAt + 3) {
                        Vector impact = arm.tipPos(); // where the arm actually landed (same fix as Tentacle Slam)
                        squashT = 4;
                        world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
                        world.spawnParticle(Particle.BUBBLE_POP, impact.toLocation(world), 40, 1.5, 0.8, 1.5, 0.1);
                        for (Player p : a) if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(impact) < 3.4 * 3.4) strike(p, kcfg("damage.barrage", 17), Guard.HEAVY, impact);
                    }
                } else arm.busy = false;
            }
            if (t > 20 + ARMS * gap + 20) endAttack(rest());
        }

        /** 11) Crushing Depths: he dives and drags the pressure down; only the air bubbles are safe. */
        void crushingDepths(List<Player> a) {
            if (t == 0) {
                say(ChatColor.DARK_BLUE + "" + ChatColor.BOLD + "CRUSHING DEPTHS! " + ChatColor.GRAY + "Get inside an air bubble!");
                world.playSound(pos.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2.5f, 0.4f);
                for (int i = 0; i < 4; i++) spawnAir();
            }
            vel.add(new Vector(0, -0.02, 0));
            pulse = 0.4f + (float) Math.sin(t * 0.3) * 0.2f;
            if (t > 20 && t % 20 == 0) for (Player p : a) {
                boolean safe = false;
                for (Bubble b : airs) if (eye(p).distanceSquared(b.pos) < 2.2 * 2.2) { safe = true; break; }
                if (safe) continue;
                int tier = divingTier(p);
                if (tier >= 4) continue; // the Abyssal Diving Suit is immune
                double dmg = kcfg("damage.crush-per-second", 4) * (tier >= 3 || counter(p, "depths_charm") ? 0.5 : 1); // BUG FIX: the charm says it halves this
                strike(p, dmg, Guard.UNBLOCKABLE, pos);
                p.sendActionBar(legacy(ChatColor.DARK_BLUE + "The pressure is crushing you!"));
            }
            if (t > 160) endAttack(rest());
        }

        // ---------- eels (phase 3) ----------
        void eelTick(List<Player> a) {
            if (phase == 3 && intro <= 0 && transition <= 0 && ++eelTimer >= kcfg("eel-every-ticks", 200)) {
                eelTimer = 0;
                for (int i = 0; i < 2 && eels.size() < kcfg("max-eels", 6); i++) {
                    Vector at = pos.clone().add(new Vector(random.nextGaussian() * 3, -2, random.nextGaussian() * 3));
                    if (isWater(at.toLocation(world).getBlock())) eels.add(new Eel(at));
                }
            }
            for (Iterator<Eel> it = eels.iterator(); it.hasNext(); ) {
                Eel e = it.next();
                if (!e.box.isValid() || e.box.isDead() || ++e.age > 900) { e.remove(); it.remove(); continue; }
                Player tgt = null; double bd = 30 * 30;
                for (Player p : a) { double d = eye(p).distanceSquared(e.pos); if (d < bd) { bd = d; tgt = p; } }
                if (tgt != null) e.v.add(safeDir(eye(tgt).subtract(e.pos), new Vector()).multiply(0.05));
                if (e.v.length() > 0.38) e.v = e.v.normalize().multiply(0.38);
                Vector next = e.pos.clone().add(e.v);
                if (isWater(next.toLocation(world).getBlock())) e.pos.copy(next); else e.v.multiply(-0.5);
                Vector dir = safeDir(e.v, new Vector(0, 0, 1));
                float wig = (float) Math.sin(e.age * 0.6) * 0.5f;
                Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ())).rotateY(wig);
                Location l = e.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                e.d.teleport(l); e.d.setInterpolationDelay(0);
                e.d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf()));
                e.box.teleport(l.clone().subtract(0, 0.25, 0));
                if (e.cd > 0) e.cd--;
                if (tgt != null && e.cd == 0 && eye(tgt).distanceSquared(e.pos) < 1.5 * 1.5) {
                    double zap = kcfg("damage.eel-zap", 5) * (counter(tgt, "eelskin_wrap") ? 0.5 : 1);
                    hurt(tgt, zap, hitbox, e.pos, Guard.UNBLOCKABLE);
                    tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
                    world.spawnParticle(Particle.ELECTRIC_SPARK, e.pos.toLocation(world), 12, 0.3, 0.3, 0.3, 0.1);
                    world.playSound(e.pos.toLocation(world), Sound.ENTITY_GUARDIAN_ATTACK, 1f, 1.8f);
                    e.cd = 30;
                }
            }
        }

        // ---------- the watchdog: never stuck where nobody can see him ----------
        void watchdog(List<Player> a) {
            if (a.isEmpty() || intro > 0 || transition > 0) { lastSeen = ticks; return; }
            for (Player p : a) {
                Vector d = eye(p).subtract(pos);
                double len = d.length();
                if (len > 34) continue;
                if (len < 0.5 || world.rayTraceBlocks(pos.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null) { lastSeen = ticks; return; }
            }
            if (ticks - lastSeen < 300) return;
            lastSeen = ticks;
            Player f = a.get(random.nextInt(a.size()));
            Vector spot = f.getLocation().toVector().add(new Vector(random.nextGaussian() * 6, 0, random.nextGaussian() * 6));
            pos = spot; vel = new Vector();
            keepInWater();
            endAttack(20);
            releaseGrip();
            getLogger().info("[Kraken] was out of sight of everyone for 15s: brought him back near " + f.getName() + ".");
        }

        // ---------- drawing ----------
        void render() {
            hitbox.teleport(pos.toLocation(world).subtract(0, hitbox.getHeight() / 2, 0));
            flinch *= 0.85f;
            float breath = (float) Math.sin(ticks * 0.06) * 0.03f;
            float sq = squashT > 0 ? squashT * 0.04f : 0; if (squashT > 0) squashT--; // the jolt of a slam landing
            // he swells up and leans back before his big attacks, so you can read them coming
            // BUG FIX: when he died mid-wind-up, attack and t froze, so his head stayed swollen through the whole death
            boolean winding = !dying && !leaving && ((attack == 1 && t < windup(34)) || (attack == 5 && t < windup(24)) || (attack == 7 && t < windup(26))
                    || (attack == 10 && t < 20) || (attack == 3 && t < 14));
            swell += ((winding ? 1f : 0f) - swell) * 0.12f;
            // BUG FIX: this stepped inside swim(), which doesn't run while he dies or leaves, so the death shudder never
            // showed and any wobble in progress froze his head mid-stretch
            jigV += -jig * 0.22f; jigV *= 0.82f; jig += jigV;
            float sy = (1 + pulse * 0.18f + breath - flinch * 0.1f - sq + swell * 0.1f + jig * 0.5f) * deathFade,
                  sxz = (1 - pulse * 0.1f - breath * 0.5f + flinch * 0.05f + sq * 0.5f + swell * 0.08f - jig * 0.25f) * deathFade; // jet contraction
            if (attack < 0 && !dying && intro <= 0 && ticks % 45 == 0)        // idle: a little puff of bubbles from his siphon
                world.spawnParticle(Particle.BUBBLE, siphon().toLocation(world), 12, 0.3, 0.3, 0.3, 0.08);
            if (attack < 0 && !dying && intro <= 0 && transition <= 0)        // idle: the beak slowly chews
                beakOpen += (0.08f + (float) Math.abs(Math.sin(ticks * 0.12)) * 0.14f - beakOpen) * 0.2f;
            if (recoilT > 0) recoilT--;
            boolean wantFlash = hurtT > 7 && !dying;                           // a red flash when he's hit
            if (wantFlash != flashing) { flashing = wantFlash; mantle.setGlowColorOverride(Color.fromRGB(255, 40, 40)); mantle.setGlowing(wantFlash); }
            if (--nextBlink <= 0) { blinkT = 5; nextBlink = 80 + random.nextInt(120); }
            float lid = blinkT > 0 ? 0.06f : 1f; if (blinkT > 0) blinkT--;
            float wide = hurtT > 0 ? 1.35f : (attack > 0 && recover <= 0 ? 0.7f : 1f); // wide when hurt, narrowed slits while he attacks
            if (hurtT > 0) hurtT--;
            if (dying && deathT > 30) { lid = 0; wide = 0; }                // the light has gone out of his eyes
            Quaternionf rot = rotModel();
            Quaternionf base = rotBase(); // for positions (rot is only for drawing)
            Location l = pos.toLocation(world); l.setYaw(0); l.setPitch(0);
            mantle.teleport(l);
            mantle.setInterpolationDelay(0);
            mantle.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(scale * sxz, scale * sy, scale * sxz), new Quaternionf()));
            // eyes follow the nearest player
            Player look = null; double bd = 40 * 40;
            for (Player p : world.getPlayers()) { double d = p.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; look = p; } }
            float s = scale / 16f;
            for (int i = 0; i < 2; i++) {
                ItemDisplay pupil = i == 0 ? pupilL : pupilR;
                // BUG FIX: the pupils sat just behind the eye's surface and moved TOWARD you (into or out of the eye),
                // so they were usually hidden. Now they sit on the surface and slide ACROSS the eye toward you.
                Vector3f eyeOff = base.transform(new Vector3f(i == 0 ? -7.5f : 7.5f, 0, 16.6f).mul(s * sxz, s * sy, s * sxz), new Vector3f());
                Vector at = pos.clone().add(vec(eyeOff));
                if (look != null) {
                    Vector fwd = vec(base.transform(new Vector3f(0, 0, 1), new Vector3f()));
                    Vector to = safeDir(eye(look).subtract(at), new Vector());
                    Vector across = to.clone().subtract(fwd.clone().multiply(to.dot(fwd))); // the part of "toward you" that lies in the eye's plane
                    if (across.lengthSquared() > 1e-6) at.add(across.normalize().multiply(Math.min(1, across.length() * 2) * 0.32 * scale / 4));
                }
                Location pl = at.toLocation(world); pl.setYaw(0); pl.setPitch(0);
                pupil.teleport(pl); pupil.setInterpolationDelay(0);
                pupil.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(scale * 0.55f * wide * deathFade, scale * 0.55f * lid * deathFade, scale * 0.55f * deathFade), new Quaternionf()));
            }
            // the beak opens: the two halves part and tilt apart
            Vector3f bOff = base.transform(new Vector3f(0, -12.5f, 6).mul(s), new Vector3f());
            Vector b = pos.clone().add(vec(bOff));
            float open = beakOpen * 0.6f;
            Quaternionf topRot = new Quaternionf(rot).rotateX(-open);
            Quaternionf botRot = new Quaternionf(rot).rotateX((float) Math.PI + open);
            Location bt = b.toLocation(world).add(0, open * 0.6 * scale / 4, 0); bt.setYaw(0); bt.setPitch(0);
            Location bb = b.toLocation(world).subtract(0, (0.4 + open * 0.6) * scale / 4, 0); bb.setYaw(0); bb.setPitch(0);
            beakTop.teleport(bt); beakTop.setInterpolationDelay(0);
            beakTop.setTransformation(new Transformation(new Vector3f(), topRot, new Vector3f(scale * 0.55f * deathFade, scale * 0.55f * deathFade, scale * 0.55f * deathFade), new Quaternionf()));
            beakBottom.teleport(bb); beakBottom.setInterpolationDelay(0);
            beakBottom.setTransformation(new Transformation(new Vector3f(), botRot, new Vector3f(scale * 0.5f * deathFade, scale * 0.5f * deathFade, scale * 0.5f * deathFade), new Quaternionf()));
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            bar.setTitle(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Kraken" + ChatColor.GRAY + "  Phase " + phase);
            for (Player p : world.getPlayers()) {
                boolean near = p.getLocation().toVector().distanceSquared(pos) < 80 * 80;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        // ---------- music (switched on once the track is in the pack) ----------
        void playMusic() {
            if (!getConfig().getBoolean("kraken.music.enabled", true)) return;
            musicStart = System.currentTimeMillis();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > 96 * 96) continue;
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:kraken.music", SoundCategory.RECORDS, (float) kcfg("music.volume", 1.0), 1f);
                listeners.add(p.getUniqueId());
            }
        }

        void musicTick() {
            if (musicStart < 0) return;
            if (System.currentTimeMillis() - musicStart > kcfg("music.length-seconds", 397.22) * 1000) { stopMusic(); playMusic(); }
        }

        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound("faultline:kraken.music", SoundCategory.RECORDS); }
            listeners.clear();
            musicStart = -1;
        }

        // ---------- the end ----------
        void die() {
            if (dying) return;
            dying = true; deathT = 0;
            releaseGrip();
            stopMusic();
            bar.removeAll();
            endAttack(0);
            for (Eel e : eels) e.remove(); eels.clear();                         // his helpers go with him
            for (LivingEntity m : minions) if (m.isValid()) m.remove(); minions.clear();
            if (pink != null) { pink.remove(); pink = null; }
            Location c = pos.toLocation(world);
            world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.35f);   // a final roar
            world.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_DEATH, 2.5f, 0.5f);
            Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (hitbox.isValid()) hitbox.setHealth(0); }); // credits the kill (the Index)
        }

        /** About 8 seconds: death throes, the last ink, sinking onto his side with limp arms trailing up, and gone. */
        void deathTick() {
            deathT++;
            Location c = pos.toLocation(world);
            if (deathT == 1) { // an agonized roar
                world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.35f);
                world.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_DEATH, 3f, 0.5f);
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(c) < 48 * 48 && survival(p))
                    p.setVelocity(p.getVelocity().add(new Vector(random.nextGaussian() * 0.2, 0.1, random.nextGaussian() * 0.2))); // the water shakes
            }
            if (deathT <= 40) {                                                  // 1) death throes
                beakOpen = 1;
                pulse = (float) Math.abs(Math.sin(deathT * 0.8)) * 0.7f;
                yaw += (float) (random.nextGaussian() * 4);
                roll = (float) (Math.sin(deathT * 0.9) * 0.25);
                for (Arm a : arms) {
                    a.busy = true;
                    a.goal = a.root().add(a.outward().multiply(16)).add(new Vector(random.nextGaussian() * 4, Math.sin(deathT * 0.5 + a.index) * 8, random.nextGaussian() * 4));
                    a.follow = 0.35; a.wave = 1.0;
                }
                if (deathT % 3 == 0) mantle.setItemStack(modelItem(random.nextBoolean() ? "kraken_mantle_3" : "kraken_mantle_1")); // the veins flicker
                if (deathT % 4 == 0) world.spawnParticle(Particle.BUBBLE, c, 40, 3, 3, 3, 0.2);
                if (deathT % 10 == 0) world.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_HURT, 2f, 0.4f);
            } else if (deathT <= 65) {                                           // 2) NEW: he clutches himself, arms coiling in, shuddering
                beakOpen += (0.15f - beakOpen) * 0.2f;
                pulse = 0.25f + (float) Math.abs(Math.sin(deathT * 1.3)) * 0.25f;
                jigV += (float) (random.nextGaussian() * 0.04);
                for (Arm a : arms) {
                    a.busy = true;
                    a.goal = a.root().add(new Vector(0, 3, 0)).add(a.outward().multiply(-1.5)); // wrapped back around his body
                    a.follow = 0.12; a.wave = 0.5; a.curl = 1.0;
                }
                if (deathT % 8 == 0) world.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_HURT, 1.6f, 0.3f);
            } else if (deathT == 66) {                                           // 3) the last ink: his eyes go dark
                mantle.setItemStack(modelItem("kraken_mantle_dead"));
                world.playSound(c, Sound.ENTITY_SQUID_DEATH, 3f, 0.4f);
                world.playSound(c, Sound.ENTITY_SQUID_SQUIRT, 3f, 0.3f);
                world.spawnParticle(Particle.SQUID_INK, siphon().toLocation(world), 250, 4, 4, 4, 0.15);
                world.spawnParticle(Particle.SQUID_INK, c, 200, 6, 5, 6, 0.05);
            } else if (deathT <= 170) {                                          // 4) sinks, rolling onto his side, limp arms trailing up
                double kk = (deathT - 66) / 104.0;
                pos.add(new Vector(0, -0.06, 0));
                roll += (1.25f - roll) * 0.03f; lean += (0.4f - lean) * 0.03f;
                beakOpen += (0.35f - beakOpen) * 0.05f; pulse *= 0.9f;
                for (Arm a : arms) { a.goal = a.root().add(new Vector(0, 15, 0)).add(a.outward().multiply(6 + 3 * kk)); a.follow = 0.035; a.wave = 0.12; a.curl = 0.2; }
                if (deathT % 14 == 0 && kk < 0.7) { // NEW: final twitches, one arm at a time
                    Arm a = arms[random.nextInt(ARMS)];
                    a.goal = a.root().add(a.outward().multiply(14)).add(new Vector(0, -4, 0)); a.follow = 0.5;
                }
                if (deathT % 6 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, c, 25, 3, 2, 3, 0.05);
            } else if (deathT <= 200) {                                          // 5) shrinking away into the dark
                deathFade = 1 - (deathT - 170) / 30f;
                pos.add(new Vector(0, -0.05, 0));
                if (deathT % 4 == 0) world.spawnParticle(Particle.BUBBLE, c, 30, 3, 3, 3, 0.1);
            } else {                                                             // gone: the title, and his hoard
                world.spawnParticle(Particle.BUBBLE_COLUMN_UP, c, 200, 4, 4, 4, 0.3);
                world.playSound(c, Sound.ENTITY_ENDER_DRAGON_DEATH, 2.5f, 0.6f);
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(c) > 96 * 96) continue;
                    p.showTitle(Title.title(legacy(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "THE KRAKEN HAS FALLEN"), legacy(ChatColor.GRAY + "The deep is quiet again."),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(900))));
                }
                Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "The Kraken " + ChatColor.GRAY + "has been slain!");
                if (!rewarded) { rewarded = true; rewards(); }
                removeEverything();
                if (kraken == this) kraken = null;
                krakenCooldownUntil = System.currentTimeMillis() + (long) (kcfg("cooldown-minutes", 10) * 60000);
            }
        }

        void leave(String message) {
            if (leaving || dying) return;
            leaving = true; leaveT = 0;
            releaseGrip();
            stopMusic();
            bar.removeAll();
            Bukkit.broadcastMessage(message);
        }

        void leaveTick() {
            leaveT++;
            pos.add(new Vector(0, -0.25, 0));
            for (Arm a : arms) { a.goal = a.root().add(new Vector(0, 17, 0)).add(a.outward().multiply(5)); a.follow = 0.1; }
            if (leaveT > 60) { removeEverything(); if (kraken == this) kraken = null; }
        }

        void rewards() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                console("givemythicbag " + (int) kcfg("rewards.mythic-bags", 18) + " " + p.getName(), p);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, (int) kcfg("rewards.diamonds-min", 16) + random.nextInt((int) kcfg("rewards.diamonds-extra", 9))));
                if (random.nextDouble() < kcfg("rewards.suit-chance", 0.4)) console("givediving suit 1 " + p.getName(), p);
                int xp = (int) kcfg("rewards.xp", 2000);
                for (int left = xp; left > 0; ) { int amount = Math.min(left, 100); left -= amount; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(amount); }
                p.sendMessage(ChatColor.DARK_AQUA + "The Kraken's hoard is yours. " + ChatColor.GRAY + "(Only you can pick up your loot.)");
                console("index discover " + p.getName() + " the_kraken", p);
            }
        }

        void removeEverything() {
            // BUG FIX: rewards come at the END of his 10-second death; a restart/reload during it removed him with no loot
            if (dying && !rewarded) { rewarded = true; try { rewards(); } catch (RuntimeException e) { logBossPart("Kraken", "rewards", e); } }
            if (dying && !rewarded) { rewarded = true; rewards(); } // stopped mid-death (a restart, /kraken kill): you still get the loot
            releaseGrip();
            stopMusic();
            bar.removeAll();
            for (Arm a : arms) if (a != null) a.remove();
            for (ItemDisplay d : new ItemDisplay[]{mantle, beakTop, beakBottom, pupilL, pupilR}) if (d != null && d.isValid()) d.remove();
            for (Bubble b : airs) if (b.d.isValid()) b.d.remove();
            airs.clear();
            if (pink != null) { pink.remove(); pink = null; }
            for (Barnacle b : barnacles) if (b.d.isValid()) b.d.remove();
            barnacles.clear();
            for (Eel e : eels) e.remove();
            eels.clear();
            for (LivingEntity m : minions) if (m.isValid()) m.remove();
            minions.clear();
            if (hitbox.isValid()) hitbox.remove();
        }
    }

    /** The seabed below (or around) a spot: the first block that isn't water, going down. */
    double seabedY(World world, double x, double z, double fromY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
        y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, y));
        if (!isWater(world.getBlockAt(bx, y, bz)) && world.getBlockAt(bx, y, bz).getType().isSolid()) { // inside the seabed: climb out
            for (int k = 0; k < 64 && y < world.getMaxHeight() - 1; k++, y++) if (!world.getBlockAt(bx, y + 1, bz).getType().isSolid()) return y + 1;
            return fromY;
        }
        for (int k = 0; k < 128 && y > world.getMinHeight(); k++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
        return fromY - 32;
    }

    /** BUG FIX: his eels are tiny slimes underneath, so they dropped slimeballs and XP. Nothing drops from any Kraken part. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKrakenPartDeath(EntityDeathEvent event) {
        Set<String> t = event.getEntity().getScoreboardTags();
        if (!(t.contains(KRAKEN_TAG) || t.contains(KRAKEN_TENT_TAG) || t.contains(KRAKEN_PINK_TAG) || t.contains(KRAKEN_EEL_TAG))) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    // ---------- hits: only players (and their minions) can hurt him; pink bubbles and tentacles count too ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onKrakenDamage(EntityDamageEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        boolean body = tags.contains(KRAKEN_TAG), arm = tags.contains(KRAKEN_TENT_TAG), pinkBox = tags.contains(KRAKEN_PINK_TAG), eel = tags.contains(KRAKEN_EEL_TAG);
        if (!body && !arm && !pinkBox && !eel) return;
        if (!byPlayer(event)) { event.setCancelled(true); return; } // no golems, no drowning, no suffocation
        if (eel) return; // eels just die normally
        if (kraken == null) { event.setCancelled(true); return; }
        Player by = event instanceof EntityDamageByEntityEvent e ? playerFrom(e.getDamager()) : null;
        if (pinkBox) { event.setCancelled(true); kraken.popPink(by); return; }
        // BUG FIX: cancelling every hit meant the Index never saw who fought him (it ignores cancelled hits), so nobody
        // got credit for the kill. Like Don: the hit lands for almost nothing, and his real health is tracked separately.
        // BUG FIX: the damage was read AFTER shrinking the hit, so every sword hit and arrow did 0.001 to him; only the
        // pink bubbles could hurt him. Read it first (like Don and Diamond Jacob).
        double dmg = event.getDamage();
        event.setDamage(0.001);
        if (arm) {
            for (Kraken.Arm a : kraken.arms) if (a.tip == event.getEntity() && a.index == kraken.gripArm) kraken.gripHits++;
            dmg *= kcfg("tentacle-hit-multiplier", 0.5);
        }
        kraken.damage(dmg, by, false);
        event.getEntity().getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, event.getEntity().getLocation().add(0, 1, 0), 4, 0.4, 0.4, 0.4, 0.1);
    }

    // =====================================================================================================
    //  DIAMOND JACOB: blow a Diamond War Horn on a mountain peak. 4,000 health, 4 phases, 3 cutscenes.
    // =====================================================================================================
    static final String JACOB_TAG = "faultline_jacob", JACOB_BIRD_TAG = "faultline_jacob_bird", JACOB_ORB_TAG = "faultline_jacob_orb",
            JACOB_MINION_TAG = "faultline_jacob_minion";
    private Jacob jacob;
    private long jacobCooldownUntil;
    private final org.bukkit.NamespacedKey JACOB_ITEM_KEY = new org.bukkit.NamespacedKey(this, "jacob_item");
    private static final org.bukkit.NamespacedKey JACOB_COUNTERS_KEY = new org.bukkit.NamespacedKey("faultlineitems", "jacob_counters");
    private final Map<UUID, Long> jacobItemCooldown = new HashMap<>(), jacobHammerCooldown = new HashMap<>(); // blade, hammer

    private double jcfg(String path, double def) { return getConfig().getDouble("jacob." + path, def); }

    private boolean jacobCounter(Player p, String id) {
        String s = p.getPersistentDataContainer().get(JACOB_COUNTERS_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        return s != null && Arrays.asList(s.split(",")).contains(id);
    }

    static boolean mountainPeak(Location l) {
        String b = l.getBlock().getBiome().getKey().getKey();
        return b.equals("jagged_peaks") || b.equals("frozen_peaks") || b.equals("stony_peaks");
    }

    // ---------- his items: the horn (summons him), his hammer and his sword (he drops both) ----------
    ItemStack jacobItem(String which) {
        ItemStack s;
        org.bukkit.inventory.meta.ItemMeta m;
        switch (which) {
            case "hammer" -> {
                s = new ItemStack(Material.NETHERITE_AXE); m = s.getItemMeta();
                m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Jacob's War Hammer");
                m.setLore(List.of(ChatColor.GRAY + "Pulled from the ground, still humming with power.", ChatColor.GREEN + "Right-click: Ground Slam " + ChatColor.DARK_GRAY + "(10s cooldown)",
                        ChatColor.DARK_GRAY + "Dropped by Diamond Jacob."));
                m.setItemModel(new org.bukkit.NamespacedKey("faultline", "jacob_hammer"));
                m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_hammer_damage"), 14, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
                m.addAttributeModifier(Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_hammer_speed"), -3.2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
                m.setEnchantmentGlintOverride(true);
            }
            case "sword" -> {
                s = new ItemStack(Material.NETHERITE_SWORD); m = s.getItemMeta();
                m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Ember Blade");
                m.setLore(List.of(ChatColor.GRAY + "It never stops burning.", ChatColor.GREEN + "Right-click: Ember Dash " + ChatColor.DARK_GRAY + "(8s cooldown)",
                        ChatColor.DARK_GRAY + "Dropped by Diamond Jacob."));
                m.setItemModel(new org.bukkit.NamespacedKey("faultline", "jacob_sword"));
                m.addEnchant(org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 2, true);
                m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_sword_damage"), 10, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
                m.addAttributeModifier(Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_sword_speed"), -2.2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            }
            default -> {
                s = new ItemStack(Material.GOAT_HORN); m = s.getItemMeta();
                m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond War Horn");
                m.setLore(List.of(ChatColor.GRAY + "Blow it on a mountain peak...", ChatColor.DARK_AQUA + "...and Diamond Jacob answers.",
                        ChatColor.DARK_GRAY + "(Jagged, Frozen, or Stony Peaks)"));
                m.setItemModel(new org.bukkit.NamespacedKey("faultline", "jacob_horn"));
                m.setMaxStackSize(1);
            }
        }
        m.getPersistentDataContainer().set(JACOB_ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING, which.equals("hammer") || which.equals("sword") ? which : "horn");
        s.setItemMeta(m);
        return s;
    }

    String jacobItemType(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(JACOB_ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
    }

    void registerJacobRecipe() {
        org.bukkit.inventory.ShapedRecipe r = new org.bukkit.inventory.ShapedRecipe(new org.bukkit.NamespacedKey(this, "diamond_war_horn"), jacobItem("horn"));
        r.shape("DGD", "GHG", "DGD");
        r.setIngredient('D', Material.DIAMOND_BLOCK);
        r.setIngredient('G', Material.GOLD_INGOT);
        r.setIngredient('H', Material.GOAT_HORN);
        addRecipeSafely(r);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJacobItem(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        String type = jacobItemType(p.getInventory().getItemInMainHand());
        if (type == null) return;
        // BUG FIX: every right-click was swallowed, so holding his hammer, blade, or horn you couldn't open a door,
        // chest, or crafting table (and clicking one fired the ability). Usable blocks work normally now; sneak to override.
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        switch (type) {
            case "horn" -> {
                if (!mountainPeak(p.getLocation())) { p.sendActionBar(legacy(ChatColor.AQUA + "The horn only carries from a mountain peak.")); return; }
                if (jacob != null) { p.sendActionBar(legacy(ChatColor.GRAY + "Diamond Jacob is already here.")); return; }
                if (now < jacobCooldownUntil) { p.sendActionBar(legacy(ChatColor.GRAY + "The mountains are still echoing... try again in a few minutes.")); return; }
                p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
                summonJacob(p.getLocation(), p);
            }
            case "hammer" -> { // Ground Slam
                if (now < jacobHammerCooldown.getOrDefault(p.getUniqueId(), 0L)) return;
                jacobHammerCooldown.put(p.getUniqueId(), now + 10000);
                Location at = p.getLocation();
                at.getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.6f);
                at.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 1.5, 0.2, 1.5, 0);
                at.getWorld().spawnParticle(Particle.BLOCK, at, 60, 2.5, 0.1, 2.5, 0, at.clone().subtract(0, 1, 0).getBlock().getBlockData());
                for (Entity e : at.getWorld().getNearbyEntities(at, 5, 3, 5)) {
                    if (!(e instanceof LivingEntity le) || e == p || e instanceof Player || e.getScoreboardTags().contains(DISPLAY_TAG) || spareFromAbility(e)) continue;
                    le.damage(10, p);
                    le.setVelocity(le.getLocation().toVector().subtract(at.toVector()).setY(0).multiply(0.3).setY(0.6));
                }
            }
            case "sword" -> { // Ember Dash
                if (now < jacobItemCooldown.getOrDefault(p.getUniqueId(), 0L)) return;
                jacobItemCooldown.put(p.getUniqueId(), now + 8000);
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
                dir.normalize();
                p.setVelocity(dir.clone().multiply(1.8).setY(0.15));
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 1.2f);
                Location start = p.getLocation();
                for (int i = 0; i < 7; i++) {
                    Location l = start.clone().add(dir.clone().multiply(i));
                    p.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 0.5, 0), 6, 0.3, 0.2, 0.3, 0.02);
                    for (Entity e : p.getWorld().getNearbyEntities(l, 1.5, 1.5, 1.5)) {
                        if (!(e instanceof LivingEntity le) || e == p || e instanceof Player || e.getScoreboardTags().contains(DISPLAY_TAG) || spareFromAbility(e)) continue;
                        le.damage(8, p); le.setFireTicks(80);
                    }
                }
            }
            default -> { }
        }
    }

    /** BUG FIX: Ground Slam and Ember Dash smashed armor stands and set people's tamed wolves, cats, and horses on fire. */
    static boolean spareFromAbility(Entity e) {
        return e instanceof org.bukkit.entity.ArmorStand || (e instanceof org.bukkit.entity.Tameable t && t.isTamed());
    }

    void summonJacob(Location at, Player by) {
        if (jacob != null) return;
        World w = at.getWorld();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 96 * 96) continue;
            p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_7, 3f, 0.6f);
        }
        jacob = new Jacob(at, by);
        Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond Jacob " + ChatColor.GRAY + "answers the horn" + (by != null ? " (blown by " + by.getName() + ")" : "") + "!");
    }

    // ---------- his cutscenes: a spectator camera; everyone is put back exactly where they were ----------
    private final class JCut {
        final ItemDisplay cam;
        final Set<UUID> viewers = new HashSet<>(), shielded = new HashSet<>();
        final org.bukkit.configuration.file.YamlConfiguration saved = new org.bukkit.configuration.file.YamlConfiguration();
        JCut(Jacob j) {
            cam = j.world.spawn(j.loc(), ItemDisplay.class, d -> { d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.setTeleportDuration(3); });
            for (Player p : j.world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(j.pos) > 64 * 64 || p.isDead()) continue;
                if (bedrock(p)) { p.setInvulnerable(true); shielded.add(p.getUniqueId()); continue; } // Bedrock can't watch a camera: just made safe
                if (!survival(p)) continue;
                String k = p.getUniqueId().toString();
                Location l = p.getLocation();
                saved.set(k + ".world", l.getWorld().getName()); saved.set(k + ".x", l.getX()); saved.set(k + ".y", l.getY()); saved.set(k + ".z", l.getZ());
                saved.set(k + ".yaw", l.getYaw()); saved.set(k + ".pitch", l.getPitch()); saved.set(k + ".mode", p.getGameMode().name());
                viewers.add(p.getUniqueId());
            }
            try { saved.save(jacobCutFile()); } catch (Exception ignored) { }
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.setGameMode(GameMode.SPECTATOR);
                p.setSpectatorTarget(cam);
            }
        }
        void shot(Location at) {
            cam.teleport(at);
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.getGameMode() == GameMode.SPECTATOR && !cam.equals(p.getSpectatorTarget())) p.setSpectatorTarget(cam);
            }
        }
        void restore(Player p) {
            String k = p.getUniqueId().toString();
            if (!saved.contains(k + ".x")) return;
            World w = Bukkit.getWorld(saved.getString(k + ".world", p.getWorld().getName()));
            Location l = new Location(w != null ? w : p.getWorld(), saved.getDouble(k + ".x"), saved.getDouble(k + ".y"), saved.getDouble(k + ".z"),
                    (float) saved.getDouble(k + ".yaw"), (float) saved.getDouble(k + ".pitch"));
            p.setSpectatorTarget(null);
            allowTeleport = true; p.teleport(l); allowTeleport = false;
            try { p.setGameMode(GameMode.valueOf(saved.getString(k + ".mode", "SURVIVAL"))); } catch (IllegalArgumentException e) { p.setGameMode(GameMode.SURVIVAL); }
            p.setFallDistance(0); p.setFireTicks(0);
            saved.set(k, null);
            viewers.remove(p.getUniqueId());
            try { if (saved.getKeys(false).isEmpty()) jacobCutFile().delete(); else saved.save(jacobCutFile()); } catch (Exception ignored) { }
        }
        void finish() {
            for (UUID id : new ArrayList<>(viewers)) { Player p = Bukkit.getPlayer(id); if (p != null) restore(p); }
            for (UUID id : shielded) { Player p = Bukkit.getPlayer(id); if (p != null) p.setInvulnerable(false); }
            shielded.clear();
            if (cam.isValid()) cam.remove();
            jacobCutFile().delete();
        }
    }

    java.io.File jacobCutFile() { return new java.io.File(getDataFolder(), "jacob_cutscene.yml"); }

    /** After a crash mid-cutscene: put them back the moment they rejoin. */
    @EventHandler
    public void onJacobCutRejoin(org.bukkit.event.player.PlayerJoinEvent event) {
        java.io.File f = jacobCutFile();
        if (!f.exists()) return;
        Player p = event.getPlayer();
        if (jacob != null && jacob.cut != null && jacob.cut.viewers.contains(p.getUniqueId())) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        String k = p.getUniqueId().toString();
        if (!y.contains(k + ".x")) return;
        World w = Bukkit.getWorld(y.getString(k + ".world", p.getWorld().getName()));
        Location l = new Location(w != null ? w : p.getWorld(), y.getDouble(k + ".x"), y.getDouble(k + ".y"), y.getDouble(k + ".z"));
        p.setSpectatorTarget(null);
        p.teleport(l);
        try { p.setGameMode(GameMode.valueOf(y.getString(k + ".mode", "SURVIVAL"))); } catch (IllegalArgumentException e) { p.setGameMode(GameMode.SURVIVAL); }
        p.setFallDistance(0);
        y.set(k, null);
        try { if (y.getKeys(false).isEmpty()) f.delete(); else y.save(f); } catch (Exception ignored) { }
    }

    @EventHandler
    public void onJacobCutQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (jacob != null && jacob.cut != null && jacob.cut.viewers.contains(event.getPlayer().getUniqueId())) jacob.cut.restore(event.getPlayer());
    }

    // ---------- the boss ----------
    private final class Jacob {
        static final int ARRIVE = 0, P1 = 1, CUT1 = 2, P2 = 3, CUT2 = 4, P3 = 5, CUT3 = 6, P4 = 7, DEFEAT = 8, GONE = 9;
        final World world;
        final Location home;
        Vector pos;
        float yaw, scale;
        double hp, maxHp;
        int state = ARRIVE, ticks, st, attack = -1, lastAttack = -1, t, recover = 40, failStreak, lastSeen, orbTimer, spiderTimer, pearlCd, flinch;
        final Slime hitbox;
        Slime birdBox;
        ItemDisplay[] parts;
        ItemDisplay held, bird;
        boolean birdUp, ablaze;
        Vector birdPos, birdVel = new Vector();
        // the great hawk is a jointed rig now: body (= bird), head, tail, two wings
        ItemDisplay birdHead, birdTail, birdWingR, birdWingL;
        float birdYaw, birdPitch, birdRoll, birdHeadYaw, flapPh, flapA, sweepA, tailFanB;
        /** The hawk's orientation, for POSITIONS (drawing adds facing(0): item displays are drawn turned around). */
        Quaternionf birdBase() { return new Quaternionf().rotateY((float) -Math.toRadians(birdYaw)).rotateX(birdPitch).rotateZ(birdRoll); }
        /** A point on the hawk, in model pixels. */
        Vector birdAt(float x, float y, float z) {
            float s = (float) jcfg("bird-scale", 5.0) / 16f;
            Vector3f v = birdBase().transform(new Vector3f(x * s, y * s, z * s), new Vector3f());
            return birdPos.clone().add(new Vector(v.x, v.y, v.z));
        }
        /** Where the hawk circles: around the player it's hunting, always well above the ground under it. */
        Vector circleSpot() {
            Player tgt = nearest(active());
            Vector center = tgt != null ? tgt.getLocation().toVector() : home.toVector();
            double r = jcfg("bird-radius", 12);
            Vector want = center.add(new Vector(Math.cos(circle) * r, jcfg("bird-height", 14) + Math.sin(ticks * 0.05) * 1.2, Math.sin(circle) * r));
            double floor = world.getHighestBlockYAt(want.getBlockX(), want.getBlockZ()) + jcfg("bird-clearance", 8);
            if (want.getY() < floor) want.setY(floor);
            return want;
        }
        void drawPart(ItemDisplay d, Location at, Quaternionf rot, float sx, float s) {
            if (d == null || !d.isValid()) return;
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(sx, s, s), new Quaternionf()));
        }
        void removeBird() {
            for (ItemDisplay d : new ItemDisplay[]{bird, birdHead, birdTail, birdWingR, birdWingL}) if (d != null && d.isValid()) d.remove();
        }
        double circle;
        Pose pose = standPose(), shown = standPose();
        JCut cut;
        final Set<UUID> fighters = new HashSet<>();
        final Map<UUID, Integer> hitCooldown = new HashMap<>();
        final List<JShot> shots = new ArrayList<>();
        final List<JOrb> orbs = new ArrayList<>();
        final List<LivingEntity> minions = new ArrayList<>(), army = new ArrayList<>(), mounts = new ArrayList<>();
        final List<Vector> marks = new ArrayList<>();
        Vector lock, pearlFrom, pearlTo;
        ItemDisplay pearlDisplay, thrownHammer;
        Vector hammerPos, hammerVel; boolean hammerBack;
        UUID branded; int brandT;
        final BossBar bar;
        long musicStart = -1; int track;
        final Set<UUID> listeners = new HashSet<>();

        final class JShot {
            final ItemDisplay d; final Vector pos, vel; final double dmg; final boolean pierce, fire; final Guard guard; int life; final Set<UUID> hit = new HashSet<>(); final double gravity;
            JShot(Vector from, Vector vel, Material look, double dmg, boolean pierce, boolean fire, Guard guard, double gravity) {
                this.pos = from.clone(); this.vel = vel; this.dmg = dmg; this.pierce = pierce; this.fire = fire; this.guard = guard; this.gravity = gravity;
                d = world.spawn(from.toLocation(world), ItemDisplay.class, x -> { x.setItemStack(new ItemStack(look)); x.setPersistent(false); x.addScoreboardTag(DISPLAY_TAG); x.setTeleportDuration(1); });
            }
        }

        final class JOrb {
            final ItemDisplay d; final Slime box; final Vector pos; int age; boolean launched; Player by;
            JOrb(Vector at) {
                pos = at.clone();
                d = spawnDisplay(at.toLocation(world), "kraken_pink_bubble", 1.4f, 2, Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15)); d.setGlowing(true); d.setGlowColorOverride(Color.fromRGB(255, 110, 200));
                box = (Slime) world.spawnEntity(at.toLocation(world), EntityType.SLIME, false);
                box.setSize(2);
                setupHitbox(box, 50, JACOB_ORB_TAG, "Pink Orb");
            }
            void remove() { if (d.isValid()) d.remove(); if (box.isValid()) box.remove(); }
        }

        Jacob(Location at, Player by) {
            world = at.getWorld();
            home = at.clone();
            scale = (float) jcfg("scale", 1.15);
            maxHp = jcfg("health", 4000); hp = maxHp;
            if (by != null) fighters.add(by.getUniqueId());
            birdPos = home.toVector().add(new Vector(0, 40, 0)); // swoops in from high above
            pos = birdPos.clone();
            hitbox = (Slime) world.spawnEntity(pos.toLocation(world), EntityType.SLIME, false);
            hitbox.setSize(2);
            setupHitbox(hitbox, 1000, JACOB_TAG, "Diamond Jacob");
            // BUG FIX: a size-2 slime is ~1 block, so his hitbox only covered his feet and knees: swords and arrows aimed
            // at his body or head (he's ~2.4 blocks tall) went straight through him. Scaled up to cover his whole body.
            AttributeInstance hs = hitbox.getAttribute(Attribute.SCALE);
            if (hs != null) hs.setBaseValue(jcfg("hitbox-scale", 2.3) * scale / 1.15);
            birdBox = (Slime) world.spawnEntity(birdPos.toLocation(world), EntityType.SLIME, false);
            birdBox.setSize(5);
            setupHitbox(birdBox, 1000, JACOB_BIRD_TAG, "Jacob's Great Hawk");
            AttributeInstance bs0 = birdBox.getAttribute(Attribute.SCALE);
            if (bs0 != null) bs0.setBaseValue(jcfg("bird-hitbox-scale", 1.4)); // ~3.6 blocks: its whole body, not just the middle
            parts = rig("jacob_armor", true);
            float bs = (float) jcfg("bird-scale", 5.0);
            bird = spawnDisplay(birdPos.toLocation(world), "jacob_bird_body", bs, 2, Display.Billboard.FIXED);
            birdHead = spawnDisplay(birdPos.toLocation(world), "jacob_bird_head", bs, 2, Display.Billboard.FIXED);
            birdTail = spawnDisplay(birdPos.toLocation(world), "jacob_bird_tail", bs, 2, Display.Billboard.FIXED);
            birdWingR = spawnDisplay(birdPos.toLocation(world), "jacob_bird_wing_r", bs, 2, Display.Billboard.FIXED);
            birdWingL = spawnDisplay(birdPos.toLocation(world), "jacob_bird_wing_l", bs, 2, Display.Billboard.FIXED);
            for (ItemDisplay d : new ItemDisplay[]{bird, birdHead, birdTail, birdWingR, birdWingL}) d.setViewRange(8f);
            hold(new ItemStack(Material.BOW));
            proxy(hitbox, parts[2], EntityType.ZOMBIE, 1.0);
            proxy(birdBox, bird, EntityType.PHANTOM, 4.0);
            bar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond Jacob", BarColor.BLUE, BarStyle.SEGMENTED_20);
            orbTimer = (int) (jcfg("orb-every-seconds", 20) * 20);
            spiderTimer = 160;
            if (by != null) {
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(home) < 96 * 96)
                    p.showTitle(Title.title(legacy(ChatColor.AQUA + "" + ChatColor.BOLD + "DIAMOND JACOB"), legacy(ChatColor.GRAY + "answers the horn"),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(700))));
            }
        }

        ItemDisplay[] rig(String prefix, boolean glint) {
            String[] names = {prefix + "_leg_r", prefix + "_leg_l", prefix + "_body", prefix + "_arm_r", prefix + "_arm_l", prefix + "_head"};
            ItemDisplay[] r = new ItemDisplay[6];
            for (int i = 0; i < 6; i++) {
                r[i] = spawnDisplay(pos.toLocation(world), names[i], scale, 2, Display.Billboard.FIXED);
                r[i].setViewRange(6f);
                if (glint) { ItemStack s = r[i].getItemStack(); org.bukkit.inventory.meta.ItemMeta m = s.getItemMeta(); m.setEnchantmentGlintOverride(true); s.setItemMeta(m); r[i].setItemStack(s); }
            }
            return r;
        }

        void hold(ItemStack item) {
            if (held == null || !held.isValid()) {
                held = world.spawn(pos.toLocation(world), ItemDisplay.class, d -> {
                    d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.setTeleportDuration(2); d.setInterpolationDuration(2);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
                });
            }
            held.setItemStack(item);
        }

        ItemStack model(Material base, String name, boolean glint) {
            ItemStack s = new ItemStack(base); org.bukkit.inventory.meta.ItemMeta m = s.getItemMeta();
            m.setItemModel(new org.bukkit.NamespacedKey("faultline", name)); if (glint) m.setEnchantmentGlintOverride(true); s.setItemMeta(m); return s;
        }

        // ---------- helpers ----------
        Location loc() { return pos.toLocation(world, yaw, 0); }
        Vector eye(Player p) { return p.getLocation().toVector().add(new Vector(0, 1.2, 0)); }
        boolean finite(Vector v) { return v != null && Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ()); }
        Vector safeDir(Vector v, Vector fb) { return (v.lengthSquared() > 1e-8 && finite(v)) ? v.clone().normalize() : fb.clone(); }
        Vector fwd() { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }
        int phase() { return state <= CUT1 ? 1 : state <= CUT2 ? 2 : state <= CUT3 ? 3 : 4; }
        double speed() { return phase() == 4 ? jcfg("phase4-speed", 0.42) : jcfg("walk-speed", 0.3); }

        double fallV;
        int blocked;

        /**
         * BUG FIX: walking snapped his height to the ground every step, so on a mountain he leapt to the top of any cliff
         * he walked into (up to 24 blocks in one step) and dropped off ledges instantly. Now he steps up at most a block,
         * falls at a real speed, and reports a wall (too tall to step up) so he can pearl over it instead.
         */
        boolean stepTo(Vector delta) {
            Vector next = pos.clone().add(delta);
            double floor = floorY(next.getX(), next.getZ(), pos.getY() + 0.5);
            if (floor - pos.getY() > 1.2) { blocked++; return false; } // a wall or a cliff face
            blocked = 0;
            pos.setX(next.getX()); pos.setZ(next.getZ());
            settle();
            return true;
        }

        /** Climbs out if he's inside a block; otherwise falls toward the ground like gravity. */
        void settle() {
            double floor = floorY(pos.getX(), pos.getZ(), pos.getY() + 0.5);
            if (floor >= pos.getY() - 0.01) { pos.setY(floor); fallV = 0; return; }
            fallV = Math.min(fallV + 0.08, 1.2);
            pos.setY(Math.max(floor, pos.getY() - fallV));
        }

        double floorY(double x, double z, double fromY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, y));
            if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                for (int k = 0; k < 24 && y < world.getMaxHeight() - 1; k++, y++) if (!world.getBlockAt(bx, y + 1, bz).getType().isSolid()) return y + 1;
                return fromY;
            }
            for (int k = 0; k < 64 && y > world.getMinHeight(); k++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
            return fromY;
        }

        List<Player> active() {
            List<Player> a = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (!survival(p) || p.isDead()) continue;
                if (p.getLocation().toVector().distanceSquared(state <= CUT1 ? birdPos : pos) > 80 * 80) continue;
                a.add(p);
            }
            return a;
        }

        Player nearest(List<Player> a) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : a) { double d = p.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        void strike(Player p, double dmg, Guard guard, Vector from, boolean fire, boolean mounted) {
            if (hitCooldown.containsKey(p.getUniqueId())) return;
            hitCooldown.put(p.getUniqueId(), 8);
            boolean ward = jacobCounter(p, "ember_ward"), banner = jacobCounter(p, "banner_of_defiance");
            if (fire && ward) dmg *= 0.75;     // Ember Ward: 25% less from his flames
            if (mounted && banner) dmg *= 0.7; // Banner of Defiance: his army deals 30% less
            hurt(p, dmg, hitbox, from, guard);
            if (fire && !ward) p.setFireTicks(Math.max(p.getFireTicks(), 60));
            if (mounted && banner) steady(p);
        }

        /** Banner of Defiance: cancels the knockback of a hit from his army (applied after the hit, so next tick). */
        void steady(Player p) {
            Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (p.isValid()) p.setVelocity(new Vector(0, Math.min(0, p.getVelocity().getY()), 0)); });
        }

        /** Ender Anchor: wearers see a purple ring where his pearl is about to land. */
        void anchorMark(Vector at) {
            Location c = at.toLocation(world); c.setY(floorY(at.getX(), at.getZ(), at.getY() + 1) + 0.15);
            for (Player p : active()) {
                if (!jacobCounter(p, "ender_anchor")) continue;
                for (int i = 0; i < 16; i++) { double ang = Math.PI * 2 * i / 16; p.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * 1.2, 0, Math.sin(ang) * 1.2), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(170, 60, 255), 1.5f)); }
                p.spawnParticle(Particle.PORTAL, c, 6, 0.3, 0.2, 0.3, 0.3);
            }
        }

        void say(String text, int stayTicks) {
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 80 * 80)
                p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), legacy(text),
                        Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(stayTicks * 50L), java.time.Duration.ofMillis(400))));
        }

        void black(int inT, int stayT, int outT) {
            net.kyori.adventure.text.Component b = net.kyori.adventure.text.Component.text("\ue901").font(net.kyori.adventure.key.Key.key("faultline", "cinematic"));
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 96 * 96)
                p.showTitle(Title.title(b, net.kyori.adventure.text.Component.empty(),
                        Title.Times.times(java.time.Duration.ofMillis(inT * 50L), java.time.Duration.ofMillis(stayT * 50L), java.time.Duration.ofMillis(outT * 50L))));
        }

        void flash(boolean white) {
            net.kyori.adventure.text.Component b = net.kyori.adventure.text.Component.text(white ? "\ue900" : "\ue901").font(net.kyori.adventure.key.Key.key("faultline", "cinematic"));
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 96 * 96)
                p.showTitle(Title.title(b, net.kyori.adventure.text.Component.empty(), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(50))));
        }

        // ---------- music: track 1 for phases 1-3 (silent in cutscenes), track 2 from the third cutscene ----------
        void playMusic(int which) {
            stopMusic();
            if (!getConfig().getBoolean("jacob.music.enabled", true)) { musicStart = System.currentTimeMillis(); track = which; return; } // still keeps time for the cutscene
            track = which; musicStart = System.currentTimeMillis();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > 96 * 96) continue;
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:jacob.music" + which, SoundCategory.RECORDS, (float) jcfg("music.volume", 1.0), 1f);
                listeners.add(p.getUniqueId());
            }
        }
        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) { p.stopSound("faultline:jacob.music1", SoundCategory.RECORDS); p.stopSound("faultline:jacob.music2", SoundCategory.RECORDS); } }
            listeners.clear(); musicStart = -1;
        }
        void musicTick() {
            if (musicStart < 0 || track == 0 || cut != null) return;
            if (System.currentTimeMillis() - musicStart > jcfg("music.length" + track + "-seconds", track == 2 ? 287 : 315) * 1000) playMusic(track);
        }
        int songTicks() { return musicStart < 0 ? 0 : (int) ((System.currentTimeMillis() - musicStart) / 50); }

        // ---------- the update ----------
        void tick() {
            ticks++; st++;
            if (!hitbox.isValid() && state < DEFEAT) { removeEverything(); jacob = null; return; } // his area unloaded
            if (hitbox.getHealth() < 900 && state < DEFEAT) hitbox.setHealth(1000);
            if (birdBox != null && birdBox.isValid() && birdBox.getHealth() < 900) birdBox.setHealth(1000);
            List<Player> a = active();
            for (Player p : a) fighters.add(p.getUniqueId());
            if (a.isEmpty() && cut == null && state != DEFEAT && st > 100) {
                if (++lonelyT > 600) { leave(); return; }
            } else lonelyT = 0;
            try {
                switch (state) {
                    case ARRIVE -> arriveTick();
                    case P1, P2, P3, P4 -> fightTick(a);
                    case CUT1 -> cut1Tick();
                    case CUT2 -> cut2Tick();
                    case CUT3 -> cut3Tick();
                    case DEFEAT -> defeatTick();
                    default -> { }
                }
                failStreak = 0;
            } catch (RuntimeException e) {
                logBossPart("Diamond Jacob", "state " + state + ", move " + attack + " (tick " + t + ")", e);
                if (cut != null && state != CUT3) { cut.finish(); cut = null; }
                attack = -1; recover = 20;
                if (++failStreak > 100) { leave(); return; }
            }
            if (jacob != this) return;
            bossPart("Diamond Jacob", "drawing", this::render);
            bossPart("Diamond Jacob", "shots", () -> shotTick(a));
            bossPart("Diamond Jacob", "orbs", () -> orbTick(a));
            bossPart("Diamond Jacob", "hammer", () -> hammerTick(a));
            bossPart("Diamond Jacob", "brand", () -> brandTick(a));
            minions.removeIf(m -> !m.isValid() || m.isDead());
            // BUG FIX: spiders are neutral in bright light (they drop their target ~1% every tick), and he fights on open
            // peaks in the daytime, so his spiders mostly wandered around. Every second they're pointed back at a fighter.
            if (ticks % 20 == 0 && !a.isEmpty()) for (LivingEntity m : minions) {
                if (!(m instanceof org.bukkit.entity.Mob mob) || !(m instanceof org.bukkit.entity.Spider)) continue;
                if (mob.getTarget() instanceof Player tp && a.contains(tp)) continue;
                Player near = null; double bd = 32 * 32;
                for (Player p : a) { double dd = p.getLocation().distanceSquared(m.getLocation()); if (dd < bd) { bd = dd; near = p; } }
                if (near != null) mob.setTarget(near);
            }
            army.removeIf(m -> !m.isValid() || m.isDead());
            mounts.removeIf(h -> {
                if (!h.isValid() || h.isDead()) return true;
                if (!h.getPassengers().isEmpty()) return false;
                world.spawnParticle(Particle.CLOUD, h.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
                h.remove();
                return true;
            });
            hitCooldown.replaceAll((k, v) -> v - 1); hitCooldown.values().removeIf(v -> v <= 0);
            bossPart("Diamond Jacob", "boss bar", this::updateBar);
            if (ticks % 20 == 0) bossPart("Diamond Jacob", "music", this::musicTick);
        }
        int lonelyT;

        // ---------- arriving: the hawk swoops in out of the sky ----------
        void arriveTick() {
            Vector target = home.toVector().add(new Vector(0, jcfg("bird-height", 14), jcfg("bird-radius", 12)));
            birdPos.add(target.clone().subtract(birdPos).multiply(0.06));
            if (st == 1) { world.playSound(home, Sound.ENTITY_PHANTOM_FLAP, 3f, 0.4f); }
            if (st >= 70) { state = P1; st = 0; circle = 0; playMusic(1); }
        }

        // ---------- the fight ----------
        void fightTick(List<Player> a) {
            if (state == P1) birdFly();
            if (flinch > 0) flinch--;
            if (a.isEmpty()) return;
            Player target = nearest(a);
            int ph = phase();
            // pink orbs: phases 1-3, every 20 seconds
            if (ph <= 3 && --orbTimer <= 0) { orbTimer = (int) (jcfg("orb-every-seconds", 20) * 20); spawnOrb(a); }
            // spiders from his staff: phases 2-3
            if ((state == P2 || state == P3) && --spiderTimer <= 0 && attack < 0) { spiderTimer = (int) jcfg("spider-every-ticks", 240); attack = 99; t = 0; }
            if (state != P1) walk(target);
            if (attack < 0) {
                if (recover > 0) { recover--; return; }
                List<Integer> pool = switch (state) {
                    case P1 -> List.of(1, 2, 3, 4);
                    case P2 -> List.of(10, 11, 12, 13);
                    case P3 -> List.of(10, 11, 12, 13, 20, 21, 20, 21);
                    default -> List.of(30, 31, 32, 33, 34);
                };
                int pick;
                do pick = pool.get(random.nextInt(pool.size())); while (pick == lastAttack && pool.size() > 1);
                attack = pick; lastAttack = pick; t = 0; lock = null; marks.clear();
            }
            switch (attack) {
                case 1 -> piercingArrow(target);
                case 2 -> arrowVolley(target);
                case 3 -> talonDive(target, a);
                case 4 -> arrowRain(a);
                case 10 -> hammerSlam(a);
                case 11 -> pearlStrike(target, a);
                case 12 -> whirlwind(target, a);
                case 13 -> hammerThrow(target);
                case 20 -> cavalryCharge(target, a);
                case 21 -> spearWall(target, a);
                case 30 -> emberFlurry(target, a);
                case 31 -> phoenixLeap(target, a);
                case 32 -> infernoPillar(a);
                case 33 -> burningBrand(target);
                case 34 -> diamondShatter();
                case 99 -> summonSpiders();
                default -> end(20);
            }
            t++;
            bossPart("Diamond Jacob", "watchdog", () -> watchdog(a));
        }

        void end(int rest) {
            attack = -1; t = 0;
            recover = (int) Math.round(rest * (phase() == 4 ? 0.6 : phase() == 3 ? 0.8 : 1.0));
            pose = state == P1 ? riderPose(false) : standPose();
        }

        // ---------- phase 1: the hawk ----------
        void birdFly() {
            if (attack == 3) return; // the dive moves the bird itself
            circle += jcfg("bird-circle-speed", 0.018);
            // BUG FIX: it circled the SUMMON point at a fixed height, so on a mountain it flew straight into the slopes (and
            // kept circling an empty spot if you moved). It circles the player it's hunting now, always above the terrain.
            Vector want = circleSpot();
            birdVel = want.subtract(birdPos).multiply(0.15);
            if (birdVel.length() > 1.2) birdVel = birdVel.normalize().multiply(1.2); // no lurching when it switches targets
            birdPos.add(birdVel);
            double ground = world.getHighestBlockYAt(birdPos.getBlockX(), birdPos.getBlockZ()) + 3;
            if (birdPos.getY() < ground) birdPos.setY(ground);
        }

        Pose riderPose(boolean drawing) {
            Pose p = new Pose().set(LEG_R, -75, 0, -12).set(LEG_L, -75, 0, 12).drop(-0.5f);
            if (drawing) p.set(ARM_L, -90, 0, 6).set(ARM_R, -90, -30, 0).set(HEAD, 6, 0, 0); else p.set(ARM_L, -40, 0, 8).set(ARM_R, -20, 0, -8);
            return p;
        }

        Vector bowAt() { return pos.clone().add(new Vector(0, 1.4 * scale, 0)).add(fwd().multiply(0.6)); }

        void piercingArrow(Player target) {
            int w = 22;
            pose = riderPose(t > 4);
            if (t == w) {
                Vector aim = safeDir(eye(target).subtract(bowAt()), fwd());
                shots.add(new JShot(bowAt(), aim.multiply(2.4), Material.SPECTRAL_ARROW, jcfg("damage.piercing-arrow", 15), true, false, Guard.UNBLOCKABLE, 0.0));
                world.playSound(loc(), Sound.ENTITY_ARROW_SHOOT, 2f, 0.6f);
            }
            if (t > w + 18) end(30);
        }

        void arrowVolley(Player target) {
            pose = riderPose(true);
            if (t == 16 || t == 32) {
                Vector aim = safeDir(eye(target).subtract(bowAt()), fwd());
                for (int i = -2; i <= 2; i++) shots.add(new JShot(bowAt(), aim.clone().rotateAroundY(Math.toRadians(i * 11)).multiply(1.6), Material.ARROW, jcfg("damage.volley-arrow", 7), false, false, Guard.BLOCKABLE, 0.01));
                world.playSound(loc(), Sound.ENTITY_ARROW_SHOOT, 2f, 0.9f);
            }
            if (t > 50) end(34);
        }

        void talonDive(Player target, List<Player> a) {
            pose = riderPose(false);
            if (t == 0) lock = target.getLocation().toVector();
            if (t < 20) { // climbs and marks where it'll strike
                birdPos.add(new Vector(0, 0.25, 0));
                if (t > 6) lock = target.getLocation().toVector();
                if (t % 3 == 0) ring(lock, 3, Color.fromRGB(220, 40, 40));
            } else if (t < 36) { // the dive
                Vector to = lock.clone().add(new Vector(0, 1.6, 0)).subtract(birdPos);
                birdVel = safeDir(to, new Vector(0, -1, 0)).multiply(Math.min(1.6, to.length()));
                birdPos.add(birdVel);
                if (t == 20) world.playSound(birdPos.toLocation(world), Sound.ENTITY_PHANTOM_FLAP, 3f, 0.3f);
                for (Player p : a) if (p.getLocation().toVector().add(new Vector(0, 1, 0)).distanceSquared(birdPos) < 3.4 * 3.4) {
                    strike(p, jcfg("damage.talon-dive", 16), Guard.HEAVY, birdPos, false, false);
                    p.setVelocity(safeDir(p.getLocation().toVector().subtract(birdPos).setY(0), new Vector(1, 0, 0)).multiply(1.0).setY(0.5));
                }
            } else if (t < 70) { // climbs back up to its circle
                Vector want = circleSpot(); // (it used to climb back to the summon point, through the mountain)
                birdPos.add(want.subtract(birdPos).multiply(0.08));
            } else end(30);
        }

        void arrowRain(List<Player> a) {
            pose = riderPose(true);
            if (t == 0) for (Player p : a) { marks.add(p.getLocation().toVector()); if (marks.size() >= 4) break; }
            if (t < 30 && t % 3 == 0) for (Vector m : marks) ring(m, 3, Color.fromRGB(220, 40, 40));
            if (t == 30) {
                world.playSound(loc(), Sound.ENTITY_ARROW_SHOOT, 2.5f, 0.5f);
                for (Vector m : marks) for (int i = 0; i < 6; i++)
                    shots.add(new JShot(m.clone().add(new Vector(random.nextGaussian() * 1.4, 18 + i, random.nextGaussian() * 1.4)), new Vector(0, -1.6, 0), Material.ARROW, jcfg("damage.arrow-rain", 9), false, false, Guard.BLOCKABLE, 0.0));
            }
            if (t > 60) end(34);
        }

        // ---------- phase 2: the hammer ----------
        void walk(Player target) {
            if (state == P1 || attack == 12 || attack == 31 || attack == 30) return; // these moves move him themselves
            Vector to = target.getLocation().toVector().subtract(pos).setY(0);
            float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-12, Math.min(12, diff));
            double d = to.length();
            if (pearlCd > 0) pearlCd--; // (counts down every tick: it used to only count while you were far away)
            if (attack < 0 && d > 18 && pearlCd <= 0) { pearlCd = 80; attack = 11; t = 0; return; } // far away: he pearls toward you
            if (attack < 0 && d > 3.2) {
                stepTo(safeDir(to, fwd()).multiply(Math.min(speed(), d - 3)));
                pose = Pose.lerp(pose, runPose(ticks, phase() == 4 ? 1f : 0.7f), 0.4f);
                // blocked by a wall or cliff for a moment: he pearls over it (and lands behind you)
                if (blocked > 15 && pearlCd <= 0) { blocked = 0; pearlCd = 80; attack = 11; t = 0; return; }
            } else if (attack < 0) pose = Pose.lerp(pose, standPose(), 0.2f);
            settle();
        }

        void hammerSlam(List<Player> a) {
            int w = 18;
            if (t < w) pose = anim(t, new int[]{0, w}, new Pose[]{standPose(), slashWindupPose()});
            else if (t == w) {
                pose = slashPose(); lock = pos.clone();
                world.playSound(loc(), Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
                world.spawnParticle(Particle.EXPLOSION, loc().add(fwd().multiply(1.5)), 2, 0.4, 0.1, 0.4, 0);
            } else if (t < w + 20) {
                double r = (t - w) * 0.6;
                for (int i = 0; i < 24; i++) { double ang = Math.PI * 2 * i / 24; world.spawnParticle(Particle.BLOCK, lock.toLocation(world).add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r), 2, 0.1, 0.05, 0.1, 0, Material.STONE.createBlockData()); }
                for (Player p : a) {
                    double flat = p.getLocation().toVector().subtract(lock).setY(0).length();
                    if (Math.abs(flat - r) < 0.9 && p.isOnGround()) strike(p, jcfg("damage.hammer-slam", 16), Guard.HEAVY, lock, false, false); // jump it
                }
            } else end(36);
        }

        void pearlTo(Vector dest) {
            pearlFrom = pos.clone().add(new Vector(0, 1.5, 0)); pearlTo = dest.clone();
            if (pearlDisplay == null || !pearlDisplay.isValid())
                pearlDisplay = world.spawn(pearlFrom.toLocation(world), ItemDisplay.class, d -> { d.setItemStack(new ItemStack(Material.ENDER_PEARL)); d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.setTeleportDuration(1); });
            world.playSound(loc(), Sound.ENTITY_ENDER_PEARL_THROW, 1.5f, 0.8f);
        }

        /** Moves the thrown pearl along its arc; true on the tick it lands (and he appears there). */
        boolean pearlFly(int k, int over) {
            if (pearlFrom == null) return false;
            double f = Math.min(1, k / (double) over);
            if (k % 2 == 0) anchorMark(pearlTo);
            Vector at = pearlFrom.clone().multiply(1 - f).add(pearlTo.clone().multiply(f)).add(new Vector(0, Math.sin(f * Math.PI) * 4, 0));
            if (pearlDisplay != null && pearlDisplay.isValid()) pearlDisplay.teleport(at.toLocation(world));
            world.spawnParticle(Particle.PORTAL, at.toLocation(world), 4, 0.1, 0.1, 0.1, 0.2);
            if (k < over) return false;
            world.spawnParticle(Particle.PORTAL, loc().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
            pos = pearlTo.clone(); pos.setY(floorY(pos.getX(), pos.getZ(), pos.getY() + 1));
            world.spawnParticle(Particle.PORTAL, loc().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
            world.playSound(loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.6f, 0.8f);
            if (pearlDisplay != null && pearlDisplay.isValid()) pearlDisplay.remove();
            pearlDisplay = null; pearlFrom = null;
            return true;
        }

        void pearlStrike(Player target, List<Player> a) {
            if (t == 0) {
                Vector behind = target.getLocation().getDirection().setY(0);
                behind = safeDir(behind, new Vector(1, 0, 0));
                pearlTo(target.getLocation().toVector().subtract(behind.multiply(2.2)));
                pose = Pose.lerp(standPose(), handOutPose(), 1f);
            }
            if (t <= 12) { if (pearlFly(t, 12)) { Vector to = target.getLocation().toVector().subtract(pos); yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())); } }
            else if (t < 20) pose = anim(t, new int[]{12, 20}, new Pose[]{crouchPose(), slashWindupPose()});
            else if (t == 20) {
                pose = slashPose();
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.6f);
                for (Player p : a) if (inFront(p, 3.6)) strike(p, jcfg("damage.pearl-strike", 14) * (jacobCounter(p, "ender_anchor") ? 0.5 : 1), Guard.HEAVY, pos, false, false); // Ender Anchor: half
            } else if (t > 34) end(30);
        }

        boolean inFront(Player p, double reach) {
            Vector to = p.getLocation().toVector().subtract(pos);
            if (to.lengthSquared() > reach * reach || Math.abs(to.getY()) > 2.5) return false;
            return to.setY(0).lengthSquared() < 1 || safeDir(to.setY(0), fwd()).dot(fwd()) > 0.2;
        }

        void whirlwind(Player target, List<Player> a) {
            if (t < 10) pose = anim(t, new int[]{0, 10}, new Pose[]{standPose(), crouchPose()});
            else if (t < 70) {
                yaw += 38;
                pose = spreadPose();
                Vector to = target.getLocation().toVector().subtract(pos).setY(0);
                if (to.length() > 1) stepTo(safeDir(to, fwd()).multiply(0.22)); // (same step-up/fall rules as walking)
                else settle();
                if (t % 4 == 0) world.spawnParticle(Particle.SWEEP_ATTACK, loc().add(0, 1, 0), 2, 1.5, 0.3, 1.5, 0);
                if (t % 10 == 0) {
                    world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 1.2f);
                    for (Player p : a) if (p.getLocation().toVector().distanceSquared(pos) < 3.4 * 3.4) strike(p, jcfg("damage.whirlwind", 8), Guard.BLOCKABLE, pos, false, false);
                }
            } else end(36);
        }

        void hammerThrow(Player target) {
            if (t < 14) pose = anim(t, new int[]{0, 14}, new Pose[]{standPose(), slashWindupPose()});
            if (t == 14) {
                pose = slashPose();
                hammerPos = pos.clone().add(new Vector(0, 1.6, 0));
                hammerVel = safeDir(eye(target).subtract(hammerPos), fwd()).multiply(1.1);
                hammerBack = false;
                thrownHammer = world.spawn(hammerPos.toLocation(world), ItemDisplay.class, d -> { d.setItemStack(model(Material.NETHERITE_AXE, "jacob_hammer", true)); d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.setTeleportDuration(1); });
                if (held != null && held.isValid()) held.setItemStack(new ItemStack(Material.AIR));
                world.playSound(loc(), Sound.ITEM_TRIDENT_THROW, 1.6f, 0.6f);
            }
            if (t > 14 && thrownHammer == null) { hold(model(Material.NETHERITE_AXE, "jacob_hammer", true)); end(30); }
            if (t > 90) { if (thrownHammer != null && thrownHammer.isValid()) thrownHammer.remove(); thrownHammer = null; hold(model(Material.NETHERITE_AXE, "jacob_hammer", true)); end(30); }
        }

        void hammerTick(List<Player> a) {
            if (thrownHammer == null) return;
            if (!thrownHammer.isValid()) { thrownHammer = null; return; }
            if (hammerBack || hammerPos.distanceSquared(pos) > 17 * 17 || t > 34) {
                hammerBack = true;
                hammerVel = safeDir(pos.clone().add(new Vector(0, 1.6, 0)).subtract(hammerPos), new Vector(0, 1, 0)).multiply(1.2);
            }
            hammerPos.add(hammerVel);
            Location l = hammerPos.toLocation(world); l.setYaw(0); l.setPitch(0);
            thrownHammer.teleport(l);
            thrownHammer.setInterpolationDelay(0);
            thrownHammer.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(ticks * 0.6f).rotateZ(ticks * 0.9f), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            for (Player p : a) if (eye(p).distanceSquared(hammerPos) < 1.8 * 1.8) strike(p, jcfg("damage.hammer-throw", 12), Guard.HEAVY, hammerPos, false, false);
            if (hammerBack && hammerPos.distanceSquared(pos.clone().add(new Vector(0, 1.6, 0))) < 1.5 * 1.5) { thrownHammer.remove(); thrownHammer = null; }
        }

        void summonSpiders() {
            if (t == 0) hold(model(Material.STICK, "spider_staff", false)); // his staff
            pose = anim(t, new int[]{0, 8, 30}, new Pose[]{standPose(), pointPose(), pointPose()});
            if (t == 10) {
                world.playSound(loc(), Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.6f);
                for (int i = 0; i < 3 && minions.size() < (int) jcfg("max-spiders", 6); i++) {
                    Location l = loc().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2);
                    l.setY(floorY(l.getX(), l.getZ(), l.getY() + 1));
                    LivingEntity s = (LivingEntity) world.spawnEntity(l, i == 0 ? EntityType.SPIDER : EntityType.CAVE_SPIDER);
                    s.addScoreboardTag(JACOB_MINION_TAG); s.setPersistent(false); s.setRemoveWhenFarAway(false);
                    minions.add(s);
                    world.spawnParticle(Particle.DUST, l.clone().add(0, 0.5, 0), 20, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(60, 180, 60), 1.4f));
                }
            }
            if (t > 30) { hold(model(Material.NETHERITE_AXE, "jacob_hammer", true)); end(16); }
        }

        // ---------- phase 3: the army ----------
        void cavalryCharge(Player target, List<Player> a) {
            pose = anim(t, new int[]{0, 10, 40}, new Pose[]{standPose(), slashWindupPose(), slashWindupPose()});
            if (t == 12) {
                world.playSound(loc(), Sound.ITEM_GOAT_HORN_SOUND_2, 3f, 0.9f); say(ChatColor.AQUA + "CHARGE!", 30);
                // his army thinned out: the horn calls reinforcements (so the charge always has riders)
                if (army.size() < (int) jcfg("reinforce-below", 4))
                    for (int i = 0; i < (int) jcfg("reinforcements", 5) && army.size() < (int) jcfg("army-size", 15); i++) {
                        org.bukkit.entity.Vindicator v = spawnSoldier(random.nextInt(Math.max(1, (int) jcfg("army-size", 15))));
                        v.setAI(true); if (v.getVehicle() instanceof org.bukkit.entity.Mob h) h.setAI(true);
                    }
            }
            if (t >= 15 && t < 45) for (LivingEntity s : army) {
                Entity mount = s.getVehicle();
                if (!(mount instanceof LivingEntity horse) || !horse.isValid()) continue;
                Vector to = target.getLocation().toVector().subtract(horse.getLocation().toVector()).setY(0);
                horse.setVelocity(safeDir(to, new Vector(1, 0, 0)).multiply(0.7).setY(horse.getVelocity().getY()));
                if (t % 4 == 0) world.spawnParticle(Particle.BLOCK, horse.getLocation(), 6, 0.4, 0.1, 0.4, 0, horse.getLocation().subtract(0, 1, 0).getBlock().getBlockData());
                for (Player p : a) if (p.getLocation().distanceSquared(horse.getLocation()) < 2.4 * 2.4) {
                    strike(p, jcfg("damage.cavalry", 9), Guard.HEAVY, horse.getLocation().toVector(), false, true);
                    if (!jacobCounter(p, "banner_of_defiance")) p.setVelocity(safeDir(to, new Vector(1, 0, 0)).multiply(0.9).setY(0.4));
                }
            }
            if (t > 55) end(40);
        }

        void spearWall(Player target, List<Player> a) {
            pose = anim(t, new int[]{0, 10, 24, 34}, new Pose[]{standPose(), slashWindupPose(), slashPose(), standPose()});
            if (t == 0) {
                Vector dir = safeDir(target.getLocation().toVector().subtract(pos).setY(0), fwd());
                for (int s = -1; s <= 1; s++) marks.add(dir.clone().rotateAroundY(Math.toRadians(s * 22)));
            }
            if (t < 22 && t % 3 == 0) for (Vector dir : marks) for (int i = 2; i <= 14; i += 2)
                world.spawnParticle(Particle.DUST, loc().add(dir.clone().multiply(i)).add(0, 0.15, 0), 1, 0.1, 0, 0.1, 0, new Particle.DustOptions(Color.fromRGB(220, 40, 40), 1.3f));
            if (t == 22) {
                world.playSound(loc(), Sound.ITEM_TRIDENT_THROW, 2f, 0.5f);
                for (Vector dir : marks) for (int i = 2; i <= 14; i += 2) {
                    Location l = loc().add(dir.clone().multiply(i));
                    l.setY(floorY(l.getX(), l.getZ(), l.getY() + 1));
                    ItemDisplay spear = world.spawn(l, ItemDisplay.class, d -> { d.setItemStack(new ItemStack(Material.IRON_SPEAR)); d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG);
                        d.setTransformation(new Transformation(new Vector3f(0, 0.9f, 0), new Quaternionf().rotateZ((float) Math.toRadians(-45)), new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf())); });
                    Bukkit.getScheduler().runTaskLater(FaultlineBosses.this, () -> { if (spear.isValid()) spear.remove(); }, 24L);
                    for (Player p : a) if (p.getLocation().toVector().setY(0).distanceSquared(l.toVector().setY(0)) < 1.4 * 1.4 && Math.abs(p.getLocation().getY() - l.getY()) < 2.5)
                        strike(p, jcfg("damage.spear-wall", 12), Guard.HEAVY, l.toVector(), false, true);
                }
            }
            if (t > 40) end(36);
        }

        // ---------- phase 4: ablaze ----------
        void emberFlurry(Player target, List<Player> a) {
            int c = t % 14, n = t / 14;
            if (n >= 3) { end(26); return; }
            Vector to = target.getLocation().toVector().subtract(pos).setY(0);
            if (c < 6) {
                yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                if (to.length() > 2) stepTo(safeDir(to, fwd()).multiply(0.9)); // (steps up a block at most, stops at walls)
                else settle();
                pose = runPose(ticks * 2, 1.2f);
                world.spawnParticle(Particle.FLAME, loc().add(0, 0.6, 0), 4, 0.3, 0.3, 0.3, 0.01);
            } else if (c == 6) {
                pose = n % 2 == 0 ? slashPose() : mirror(slashPose());
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.8f);
                world.spawnParticle(Particle.FLAME, loc().add(fwd().multiply(1.5)).add(0, 1, 0), 20, 0.8, 0.4, 0.8, 0.02);
                for (Player p : a) if (inFront(p, 3.4)) strike(p, jcfg("damage.ember-flurry", 9), Guard.BLOCKABLE, pos, true, false);
            } else pose = Pose.lerp(pose, slashWindupPose(), 0.3f);
        }

        void phoenixLeap(Player target, List<Player> a) {
            if (t < 10) { pose = anim(t, new int[]{0, 10}, new Pose[]{standPose(), crouchPose()}); lock = target.getLocation().toVector(); }
            else if (t < 32) {
                double f = (t - 10) / 22.0;
                Vector start = lock.clone(); // leaps toward where you were
                Vector to = start.subtract(pos).setY(0);
                if (to.length() > 0.5) pos.add(safeDir(to, fwd()).multiply(Math.min(to.length(), 0.6)));
                double ground = floorY(pos.getX(), pos.getZ(), pos.getY() + 6);
                pos.setY(ground + Math.sin(f * Math.PI) * 7);
                pose = jumpPose();
                world.spawnParticle(Particle.FLAME, loc().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.02);
            } else if (t == 32) {
                pos.setY(floorY(pos.getX(), pos.getZ(), pos.getY() + 1));
                pose = landPose();
                world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
                for (int i = 0; i < 30; i++) { double ang = Math.PI * 2 * i / 30; world.spawnParticle(Particle.FLAME, loc().add(Math.cos(ang) * 4, 0.3, Math.sin(ang) * 4), 3, 0.2, 0.2, 0.2, 0.02); }
                for (Player p : a) if (p.getLocation().toVector().distanceSquared(pos) < 5 * 5) strike(p, jcfg("damage.phoenix-leap", 18), Guard.HEAVY, pos, true, false);
            } else if (t > 46) end(30);
        }

        void infernoPillar(List<Player> a) {
            pose = anim(t, new int[]{0, 10, 26}, new Pose[]{standPose(), spreadPose(), spreadPose()});
            if (t == 0) for (Player p : a) { marks.add(p.getLocation().toVector()); marks.add(p.getLocation().toVector().add(new Vector(random.nextGaussian() * 3, 0, random.nextGaussian() * 3))); if (marks.size() >= 6) break; }
            if (t < 26 && t % 3 == 0) for (Vector m : marks) ring(m, 1.8, Color.fromRGB(255, 120, 30));
            if (t >= 26 && t < 40) {
                for (Vector m : marks) world.spawnParticle(Particle.FLAME, m.toLocation(world).add(0, (t - 26) * 0.5, 0), 12, 0.5, 0.6, 0.5, 0.02);
                if (t == 26) {
                    world.playSound(loc(), Sound.ENTITY_BLAZE_SHOOT, 2f, 0.5f);
                    for (Vector m : marks) for (Player p : a) if (p.getLocation().toVector().setY(0).distanceSquared(m.clone().setY(0)) < 2 * 2) strike(p, jcfg("damage.inferno-pillar", 12), Guard.UNBLOCKABLE, m, true, false);
                }
            }
            if (t > 44) end(30);
        }

        void burningBrand(Player target) {
            pose = anim(t, new int[]{0, 8, 20}, new Pose[]{standPose(), pointPose(), pointPose()});
            if (t == 8) {
                branded = target.getUniqueId(); brandT = 70;
                target.sendActionBar(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "BRANDED! " + ChatColor.GRAY + "Get away from everyone: it's about to explode!"));
                world.playSound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.6f);
            }
            if (t > 20) end(24);
        }

        void brandTick(List<Player> a) {
            if (branded == null) return;
            Player b = Bukkit.getPlayer(branded);
            if (b == null || b.isDead()) { branded = null; return; }
            brandT--;
            if (brandT % 4 == 0) for (int i = 0; i < 10; i++) { double ang = Math.PI * 2 * i / 10 + brandT * 0.2; world.spawnParticle(Particle.FLAME, b.getLocation().add(Math.cos(ang) * 0.7, 2.4, Math.sin(ang) * 0.7), 1, 0, 0, 0, 0); }
            if (brandT <= 0) {
                Location at = b.getLocation();
                world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.9f);
                world.spawnParticle(Particle.EXPLOSION, at, 3, 1, 0.5, 1, 0);
                world.spawnParticle(Particle.FLAME, at, 60, 2, 1, 2, 0.05);
                for (Player p : a) if (p.getLocation().distanceSquared(at) < 3 * 3) { hitCooldown.remove(p.getUniqueId()); strike(p, jcfg("damage.burning-brand", 14), Guard.UNBLOCKABLE, at.toVector(), true, false); }
                branded = null;
            }
        }

        void diamondShatter() {
            pose = anim(t, new int[]{0, 14, 18, 30}, new Pose[]{standPose(), crouchPose(), spreadPose(), standPose()});
            if (t < 14 && t % 2 == 0) world.spawnParticle(Particle.END_ROD, loc().add(0, 1, 0), 4, 0.5, 0.8, 0.5, 0.02);
            if (t == 16) {
                world.playSound(loc(), Sound.BLOCK_GLASS_BREAK, 2f, 0.6f);
                for (int i = 0; i < 16; i++) {
                    Vector dir = new Vector(Math.cos(Math.PI * 2 * i / 16), 0.05, Math.sin(Math.PI * 2 * i / 16));
                    shots.add(new JShot(pos.clone().add(new Vector(0, 1.2, 0)), dir.multiply(0.9), Material.DIAMOND, jcfg("damage.diamond-shard", 7), false, false, Guard.BLOCKABLE, 0.0));
                }
            }
            if (t > 34) end(28);
        }

        void ring(Vector c, double r, Color col) {
            for (int i = 0; i < 18; i++) { double ang = Math.PI * 2 * i / 18; world.spawnParticle(Particle.DUST, c.toLocation(world).add(Math.cos(ang) * r, 0.15, Math.sin(ang) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f)); }
        }

        // ---------- projectiles ----------
        void shotTick(List<Player> a) {
            for (Iterator<JShot> it = shots.iterator(); it.hasNext(); ) {
                JShot s = it.next();
                s.vel.add(new Vector(0, -s.gravity, 0));
                s.pos.add(s.vel); s.life++;
                Location l = s.pos.toLocation(world);
                Vector dir = safeDir(s.vel, new Vector(0, 0, 1));
                l.setYaw(0); l.setPitch(0);
                boolean done = s.life > 80 || world.getBlockAt(l).getType().isSolid();
                if (s.d.isValid()) {
                    s.d.teleport(l);
                    s.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationTo(new Vector3f(1, 1, 0).normalize(), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ())), new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
                }
                if (s.life % 2 == 0) world.spawnParticle(Particle.CRIT, l, 1, 0, 0, 0, 0);
                for (Player p : a) {
                    if (done || s.hit.contains(p.getUniqueId())) continue;
                    if (eye(p).subtract(new Vector(0, 0.4, 0)).distanceSquared(s.pos) < 1.1 * 1.1) {
                        s.hit.add(p.getUniqueId());
                        hitCooldown.remove(p.getUniqueId());
                        strike(p, s.dmg, s.guard, s.pos, s.fire, false);
                        if (!s.pierce) done = true; // a piercing arrow keeps going through you
                    }
                }
                if (done) { if (s.d.isValid()) s.d.remove(); it.remove(); }
            }
        }

        // ---------- pink orbs: hit one and it flies into him for 100 damage ----------
        void spawnOrb(List<Player> a) {
            Player p = a.get(random.nextInt(a.size()));
            Location l = p.getLocation().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
            l.setY(floorY(l.getX(), l.getZ(), l.getY() + 2) + 0.2);
            orbs.add(new JOrb(l.toVector()));
            world.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f, 0.6f);
            for (Player q : a) q.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "A pink orb rises from the ground! Hit it into him!"));
        }

        void orbTick(List<Player> a) {
            for (Iterator<JOrb> it = orbs.iterator(); it.hasNext(); ) {
                JOrb o = it.next();
                o.age++;
                if (!o.launched) {
                    if (o.age < 30) o.pos.add(new Vector(0, 0.06, 0)); // rises out of the ground
                    if (o.age > 240 || !o.box.isValid()) { o.remove(); it.remove(); continue; }
                } else {
                    Vector to = pos.clone().add(new Vector(0, 1.2, 0)).subtract(o.pos);
                    if (to.length() < 1.6) {
                        world.spawnParticle(Particle.DUST, o.pos.toLocation(world), 40, 0.8, 0.8, 0.8, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.8f));
                        world.playSound(o.pos.toLocation(world), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 0.7f);
                        damage(jcfg("orb-damage", 100), o.by, true);
                        o.remove(); it.remove(); continue;
                    }
                    o.pos.add(safeDir(to, new Vector(0, 1, 0)).multiply(1.4));
                    if (o.age > 400) { o.remove(); it.remove(); continue; }
                }
                Location l = o.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                o.d.teleport(l);
                if (o.box.isValid()) o.box.teleport(l.clone().subtract(0, 0.5, 0));
            }
        }

        void launchOrb(Slime box, Player by) {
            for (JOrb o : orbs) if (o.box == box && !o.launched) {
                o.launched = true; o.by = by;
                world.playSound(o.pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.4f, 1.4f);
                if (o.box.isValid()) o.box.remove();
            }
        }

        // ---------- taking damage, and the phases ----------
        void damage(double amount, Player by, boolean exact) {
            if (cut != null || state == ARRIVE || state >= DEFEAT || state == CUT1 || state == CUT2 || state == CUT3) return;
            hp -= exact ? amount : amount * (1 - jcfg("defense", 0.0));
            flinch = 6;
            if (by != null) fighters.add(by.getUniqueId());
            double per = maxHp / 4;
            if (state == P1 && hp <= maxHp - per) {
                hp = maxHp - per;
                // built one phase at a time: until phase 2 is unlocked (max-phase), he withdraws here (no loot)
                if (jcfg("max-phase", 4) < 2) {
                    Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (jacob == this) {
                        Bukkit.broadcastMessage(ChatColor.AQUA + "Diamond Jacob " + ChatColor.GRAY + "pulls his hawk out of the fight... " + ChatColor.DARK_GRAY + "(Phase 2 arrives in the next update.)");
                        leave();
                    } });
                    return;
                }
                startCut(CUT1);
            }
            else if (state == P2 && hp <= maxHp - 2 * per) {
                hp = maxHp - 2 * per;
                if (jcfg("max-phase", 4) < 3) { // built one phase at a time: until phase 3 is unlocked, he withdraws here (no loot)
                    Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (jacob == this) {
                        Bukkit.broadcastMessage(ChatColor.AQUA + "Diamond Jacob " + ChatColor.GRAY + "staggers back and retreats... " + ChatColor.DARK_GRAY + "(Phase 3 arrives in the next update.)");
                        leave();
                    } });
                    return;
                }
                startCut(CUT2);
            }
            else if (state == P3 && hp <= maxHp - 3 * per) {
                hp = maxHp - 3 * per;
                if (jcfg("max-phase", 4) < 4) { // built one phase at a time: until phase 4 is unlocked, he withdraws here (no loot)
                    Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (jacob == this) {
                        Bukkit.broadcastMessage(ChatColor.AQUA + "Diamond Jacob " + ChatColor.GRAY + "sounds the retreat, and his army rides off with him... " + ChatColor.DARK_GRAY + "(Phase 4 arrives in the next update.)");
                        leave();
                    } });
                    return;
                }
                startCut(CUT3);
            }
            else if (state == P4 && hp <= 0) { hp = 0; defeat(); }
        }

        void startCut(int which) {
            state = which; st = 0; attack = -1; t = 0;
            stopMusic(); // no music in the cutscenes (the third one starts his second track itself)
            clearShots();
            for (JOrb o : orbs) o.remove();
            orbs.clear();
            branded = null;
            if (which == CUT3) { // he falls: his army and spiders scatter (phase 4 is him alone, ablaze)
                for (LivingEntity m : minions) if (m.isValid()) { world.spawnParticle(Particle.CLOUD, m.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02); m.remove(); }
                for (LivingEntity s : army) { if (s.getVehicle() != null) s.getVehicle().remove(); if (s.isValid()) { world.spawnParticle(Particle.CLOUD, s.getLocation().add(0, 1, 0), 16, 0.5, 0.8, 0.5, 0.02); s.remove(); } }
                for (LivingEntity h : mounts) if (h.isValid()) h.remove();
                minions.clear(); army.clear(); mounts.clear();
            }
            Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (jacob == this && state == which && cut == null) cut = new JCut(this); });
        }

        void clearShots() {
            for (JShot s : shots) if (s.d.isValid()) s.d.remove();
            shots.clear();
            if (thrownHammer != null && thrownHammer.isValid()) thrownHammer.remove();
            thrownHammer = null;
            if (pearlDisplay != null && pearlDisplay.isValid()) pearlDisplay.remove();
            pearlDisplay = null; pearlFrom = null;
        }

        Location camAt(Vector target, double dist, double height, double angleDeg) {
            double a = Math.toRadians(yaw + angleDeg);
            Vector at = target.clone().add(new Vector(-Math.sin(a) * dist, height, Math.cos(a) * dist));
            Location l = at.toLocation(world);
            l.setDirection(target.clone().add(new Vector(0, 1.2, 0)).subtract(at));
            return l;
        }

        /** Cutscene 1: an arrow takes down the hawk; he pulls an enchanted hammer from the ground. */
        void cut1Tick() {
            if (cut == null) return;
            int k = st;
            if (k == 2) black(8, 22, 10);
            if (k < 40) cut.shot(camAt(birdPos, 11, 2, 70));
            if (k == 30) shots.add(new JShot(birdPos.clone().add(new Vector(20, 6, 0)), new Vector(-2.0, -0.6, 0), Material.SPECTRAL_ARROW, 0, false, false, Guard.BLOCKABLE, 0));
            if (k == 40) { // the hit
                world.playSound(birdPos.toLocation(world), Sound.ENTITY_PARROT_HURT, 3f, 0.4f);
                world.spawnParticle(Particle.CLOUD, birdPos.toLocation(world), 30, 1.2, 0.6, 1.2, 0.05);
                clearShots();
            }
            if (k >= 40 && k < 76) { // the hawk falls; he falls with it
                birdPos.add(new Vector(0.05, -0.55, 0.02));
                birdPos.setY(Math.max(birdPos.getY(), floorY(birdPos.getX(), birdPos.getZ(), birdPos.getY()) + 0.5));
                cut.shot(camAt(birdPos, 12, 5, 40));
            }
            if (k == 76) {
                world.playSound(birdPos.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
                world.spawnParticle(Particle.BLOCK, birdPos.toLocation(world), 80, 2, 0.4, 2, 0.1, birdPos.toLocation(world).subtract(0, 1, 0).getBlock().getBlockData());
                pos = birdPos.clone().add(new Vector(2.5, 0, 0)); pos.setY(floorY(pos.getX(), pos.getZ(), pos.getY() + 1));
                if (held != null && held.isValid()) held.setItemStack(new ItemStack(Material.AIR));
            }
            if (k > 76 && k < 150) cut.shot(camAt(pos, 4.5, 1.2, 15));
            if (k > 76 && k < 96) pose = kneelPose(ticks);
            if (k >= 96 && k < 112) { // reaches down and pulls the hammer out of the ground
                pose = Pose.lerp(kneelPose(ticks), standPose().set(ARM_R, 10, 0, 0), (k - 96) / 16f);
                world.spawnParticle(Particle.ENCHANT, loc().add(fwd().multiply(0.8)).add(0, 0.6, 0), 12, 0.3, 0.6, 0.3, 0.5);
                if (k == 104) { hold(model(Material.NETHERITE_AXE, "jacob_hammer", true)); world.playSound(loc(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 2f, 0.6f); }
            }
            if (k >= 112 && k < 150) pose = Pose.lerp(pose, powerPose(), 0.2f);
            if (k == 116) say(ChatColor.AQUA + "\"You cannot defeat me this easily.\"", 50);
            if (k == 156) black(10, 20, 10);
            if (k >= 172) { // phase 2
                removeBird();
                if (birdBox != null && birdBox.isValid()) birdBox.remove();
                bird = null; birdBox = null;
                endCut(P2);
                playMusic(1);
            }
        }

        /** Cutscene 2: exhausted on one knee, he blows his war horn, and his army rides in. */
        void cut2Tick() {
            if (cut == null) return;
            int k = st;
            if (k == 2) black(8, 22, 10);
            if (k < 50) { pose = kneelPose(ticks); cut.shot(camAt(pos, 4, 1.0, 25)); if (k % 10 == 0) world.spawnParticle(Particle.CLOUD, loc().add(0, 1.6, 0), 3, 0.2, 0.1, 0.2, 0.01); }
            if (k == 50) hold(new ItemStack(Material.GOAT_HORN));
            if (k >= 50 && k < 80) pose = Pose.lerp(kneelPose(ticks), new Pose().set(ARM_R, -150, 0, -10).set(HEAD, -25, 0, 0).set(BODY, -8, 0, 0), (k - 50) / 30f);
            if (k == 70) {
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(loc()) < 96 * 96) p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 4f, 0.7f);
                world.spawnParticle(Particle.SONIC_BOOM, loc().add(0, 2, 0), 1);
            }
            if (k >= 80 && k <= 150 && (k - 80) % 5 == 0 && army.size() < (int) jcfg("army-size", 15)) spawnSoldier(army.size());
            if (k >= 80) cut.shot(camAt(pos, 6 + (k - 80) * 0.12, 2 + (k - 80) * 0.08, 25 + (k - 80) * 1.5));
            if (k == 160) black(10, 20, 10);
            if (k >= 176) {
                hold(model(Material.NETHERITE_AXE, "jacob_hammer", true));
                for (LivingEntity s : army) { if (s instanceof org.bukkit.entity.Mob m) m.setAI(true); if (s.getVehicle() instanceof org.bukkit.entity.Mob h) h.setAI(true); }
                endCut(P3);
                playMusic(1);
            }
        }

        org.bukkit.entity.Vindicator spawnSoldier(int i) {
            double ang = Math.PI * 2 * i / jcfg("army-size", 15);
            Location l = loc().add(Math.cos(ang) * 11, 0, Math.sin(ang) * 11);
            l.setY(floorY(l.getX(), l.getZ(), l.getY() + 3));
            l.setYaw((float) Math.toDegrees(Math.atan2(Math.cos(ang), -Math.sin(ang))));
            org.bukkit.entity.Horse horse = world.spawn(l, org.bukkit.entity.Horse.class, h -> {
                h.setAdult(); h.setTamed(true); h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                h.getInventory().setArmor(new ItemStack(Material.IRON_HORSE_ARMOR));
                h.setPersistent(false); h.setRemoveWhenFarAway(false); h.addScoreboardTag(JACOB_MINION_TAG); h.setAI(false);
                AttributeInstance hh = h.getAttribute(Attribute.MAX_HEALTH); if (hh != null) { hh.setBaseValue(30); h.setHealth(30); }
            });
            org.bukkit.entity.Vindicator v = world.spawn(l, org.bukkit.entity.Vindicator.class, s -> {
                s.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET)); s.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                s.getEquipment().setLeggings(new ItemStack(Material.IRON_LEGGINGS)); s.getEquipment().setBoots(new ItemStack(Material.IRON_BOOTS));
                s.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SPEAR));
                s.getEquipment().setHelmetDropChance(0); s.getEquipment().setChestplateDropChance(0); s.getEquipment().setLeggingsDropChance(0);
                s.getEquipment().setBootsDropChance(0); s.getEquipment().setItemInMainHandDropChance(0);
                s.setCustomName(ChatColor.GRAY + "Jacob's Soldier"); s.setPersistent(false); s.setRemoveWhenFarAway(false); s.setAI(false);
                s.setCanJoinRaid(false); s.setPatrolLeader(false); s.addScoreboardTag(JACOB_MINION_TAG);
            });
            horse.addPassenger(v);
            army.add(v); mounts.add(horse);
            world.spawnParticle(Particle.CLOUD, l.clone().add(0, 1, 0), 20, 0.6, 0.8, 0.6, 0.02);
            world.playSound(l, Sound.ENTITY_HORSE_GALLOP, 1f, 0.9f);
            return v;
        }

        /** Cutscene 3: he lies still... then his second track starts, the screen glitches, and he rises on fire. */
        void cut3Tick() {
            if (cut == null) return;
            int k = st;
            pose = new Pose().set(BODY, -90, 0, 0).set(LEG_R, -90, 0, -6).set(LEG_L, -90, 0, 6).set(ARM_R, 0, 0, -30).set(ARM_L, 0, 0, 30).set(HEAD, 0, 20, 0).drop(-0.75f);
            if (k == 2) black(8, 20, 12);
            if (k < 140 || musicStart < 0) cut.shot(camAt(pos, 5, 2.4, 40 + k * 0.1));
            if (k == 30) say(ChatColor.GRAY + "Is he... dead?", 40);
            if (k == 70) say(ChatColor.GRAY + "Did he give up?", 40);
            if (k == 130) playMusic(2); // 0:00: out of nowhere, the music
            if (k < 130 || musicStart < 0) return;
            int m = songTicks(); // the rest follows the song's clock
            if (m >= 100 && m < 140) { // 0:05: the screen glitches, and he's on fire
                if (!ablaze) {
                    ablaze = true;
                    for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
                    parts = rig("jacob_blaze", false);
                    hold(model(Material.NETHERITE_SWORD, "jacob_sword", false));
                }
                if (m % 3 == 0) flash(random.nextBoolean());
                world.spawnParticle(Particle.FLAME, loc().add(0, 0.5, 0), 12, 0.8, 0.3, 0.8, 0.02);
                world.spawnParticle(Particle.LAVA, loc().add(0, 0.5, 0), 2, 0.6, 0.2, 0.6, 0);
                Location shake = camAt(pos, 4, 1.6, 30 + random.nextGaussian() * 6);
                shake.add(random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2);
                cut.shot(shake);
            }
            if (m >= 140 && m < 300 && (m - 140) % 30 == 0) black(0, 32, 0); // 0:07: full black (refreshed until 0:15)
            if (m >= 220 && m < 224) for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(loc()) < 96 * 96) // 0:11, on the black screen
                p.showTitle(Title.title(net.kyori.adventure.text.Component.text("\ue901").font(net.kyori.adventure.key.Key.key("faultline", "cinematic")),
                        legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "It's not over yet..."),
                        Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(3600), java.time.Duration.ofMillis(200))));
            if (m >= 300) { // 0:15: he's back, standing, ablaze
                pose = powerPose();
                world.playSound(loc(), Sound.ENTITY_WITHER_SPAWN, 2f, 1.2f);
                for (int i = 0; i < 40; i++) { double ang = Math.PI * 2 * i / 40; world.spawnParticle(Particle.FLAME, loc().add(Math.cos(ang) * 3, 0.3, Math.sin(ang) * 3), 3, 0.1, 0.3, 0.1, 0.02); }
                endCut(P4);
                track = 2;
            }
        }

        void endCut(int next) {
            if (cut != null) { cut.finish(); cut = null; }
            if (next != P1) { // the hawk is gone after phase 1 (even when an admin skips ahead)
                removeBird();
                if (birdBox != null && birdBox.isValid()) birdBox.remove();
                bird = null; birdBox = null;
            }
            state = next; st = 0; attack = -1; t = 0; recover = 30;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(loc()) < 96 * 96) p.resetTitle();
            if (next != P1 && (thrownHammer == null)) pose = standPose();
        }

        // ---------- defeat: "I give up..." and five seconds later, he falls ----------
        void defeat() {
            state = DEFEAT; st = 0; attack = -1;
            clearShots(); branded = null;
            for (LivingEntity m : minions) if (m.isValid()) m.remove();
            for (LivingEntity s : army) { if (s.getVehicle() != null) s.getVehicle().remove(); if (s.isValid()) s.remove(); }
            for (LivingEntity h : mounts) if (h.isValid()) h.remove();
            minions.clear(); army.clear(); mounts.clear();
            bar.removeAll();
            say(ChatColor.AQUA + "\"I give up...\"", 80);
        }

        boolean rewarded;
        void defeatTick() {
            int k = st;
            if (k < 100) pose = kneelPose(ticks);
            else pose = Pose.lerp(pose, new Pose().set(BODY, -90, 0, 0).set(LEG_R, -90, 0, 0).set(LEG_L, -90, 0, 0).drop(-0.75f), 0.15f);
            if (k == 100) {
                stopMusic();
                world.playSound(loc(), Sound.ENTITY_PLAYER_DEATH, 2f, 0.6f);
                world.spawnParticle(Particle.CLOUD, loc().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.05);
                Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond Jacob " + ChatColor.GRAY + "has fallen!");
                if (!rewarded) { rewarded = true; rewards(); }
                Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (hitbox.isValid()) hitbox.setHealth(0); }); // the kill (for the Index)
            }
            if (k > 140) { removeEverything(); if (jacob == this) jacob = null; jacobCooldownUntil = System.currentTimeMillis() + (long) (jcfg("cooldown-minutes", 15) * 60000); }
        }

        void rewards() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                console("givemythicbag " + (int) jcfg("rewards.mythic-bags", 20) + " " + p.getName(), p);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, (int) jcfg("rewards.diamonds-min", 20) + random.nextInt((int) jcfg("rewards.diamonds-extra", 11))));
                dropLocked(at, p, jacobItem("hammer"));
                dropLocked(at, p, jacobItem("sword"));
                for (String acc : List.of("falconers_quiver", "ender_anchor", "banner_of_defiance", "ember_ward", "diamond_heart")) console("givejacob " + acc + " 1 " + p.getName(), p);
                int xp = (int) jcfg("rewards.xp", 2500);
                for (int left = xp; left > 0; ) { int n = Math.min(left, 100); left -= n; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(n); }
                console("index discover " + p.getName() + " diamond_jacob", p);
                p.sendMessage(ChatColor.AQUA + "Diamond Jacob's hammer, his blade, and his five charms are yours.");
            }
        }

        void leave() {
            Bukkit.broadcastMessage(ChatColor.AQUA + "Diamond Jacob " + ChatColor.GRAY + "has left the mountain.");
            removeEverything();
            if (jacob == this) jacob = null;
        }

        void watchdog(List<Player> a) {
            if (state == P1 && !a.isEmpty()) { // BUG FIX: phase 1 had no rescue at all: the hawk flies back over a fighter
                for (Player p : a) {
                    Vector d = eye(p).subtract(birdPos);
                    double len = d.length();
                    if (len > 70) continue;
                    if (len < 0.5 || world.rayTraceBlocks(birdPos.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null) { lastSeen = ticks; return; }
                }
                if (ticks - lastSeen < 300) return;
                lastSeen = ticks;
                birdPos = circleSpot(); birdVel = new Vector();
                getLogger().info("[Diamond Jacob] his hawk was out of sight of everyone for 15s: brought it back overhead.");
                return;
            }
            if (state == P1 || a.isEmpty() || attack == 11) { lastSeen = ticks; return; }
            for (Player p : a) {
                Vector d = eye(p).subtract(pos.clone().add(new Vector(0, 1.6, 0)));
                double len = d.length();
                if (len > 30) continue;
                if (len < 0.5 || world.rayTraceBlocks(pos.toLocation(world).add(0, 1.6, 0), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null) { lastSeen = ticks; return; }
            }
            if (ticks - lastSeen < 300) return;
            lastSeen = ticks;
            Player f = a.get(random.nextInt(a.size()));
            attack = -1; pearlTo(f.getLocation().toVector().add(new Vector(random.nextGaussian() * 3, 0, random.nextGaussian() * 3))); attack = 11; t = 1;
            getLogger().info("[Diamond Jacob] was out of sight of everyone for 15s: he pearled back to " + f.getName() + ".");
        }

        // ---------- drawing ----------
        void render() {
            if (state <= CUT1 && bird != null && bird.isValid()) {
                // ---- the great hawk: faces where it flies, noses into climbs and dives, banks into turns ----
                double flat = Math.hypot(birdVel.getX(), birdVel.getZ());
                boolean falling = state == CUT1 && st >= 40;
                if (flat > 0.03) {
                    float want = (float) Math.toDegrees(Math.atan2(-birdVel.getX(), birdVel.getZ()));
                    float diff = ((want - birdYaw) % 360 + 540) % 360 - 180;
                    birdYaw += Math.max(-7, Math.min(7, diff));
                    if (!falling) birdRoll += ((float) Math.max(-0.6, Math.min(0.6, -diff * 0.035)) - birdRoll) * 0.12f;
                }
                if (falling) birdRoll = st * 0.25f; // tumbling out of the sky
                birdPitch += ((float) Math.max(-0.7, Math.min(0.9, -Math.atan2(birdVel.getY(), Math.max(0.2, flat)))) - birdPitch) * 0.15f;
                // the wings: glide, beat hard to climb, fold back to dive, flail as it falls
                boolean diving = state == P1 && attack == 3 && t >= 20 && t < 36;
                boolean climbing = birdVel.getY() > 0.05 || (attack == 3 && t < 20);
                flapPh += diving ? 0.05f : falling ? 0.9f : climbing ? 0.38f : 0.12f;
                float amp = diving ? 0.05f : falling ? 0.45f : climbing ? 0.6f : 0.15f;
                flapA += ((float) (Math.sin(flapPh) * amp + (diving ? -0.25 : 0.08)) - flapA) * 0.5f;
                sweepA += ((diving ? 0.9f : falling ? 0.3f : 0f) - sweepA) * 0.15f;
                tailFanB += ((float) Math.min(0.5, Math.abs(birdRoll) * 0.8) - tailFanB) * 0.1f;
                Player look = nearest(active()); // its head tracks who it's hunting
                if (look != null && !falling) {
                    Vector to = eye(look).subtract(birdAt(0, 2, 7));
                    Vector3f local = new Quaternionf(birdBase()).conjugate().transform(new Vector3f((float) to.getX(), (float) to.getY(), (float) to.getZ()));
                    birdHeadYaw += ((float) Math.max(-0.9, Math.min(0.9, Math.atan2(local.x, local.z))) - birdHeadYaw) * 0.15f;
                }
                float bs = (float) jcfg("bird-scale", 5.0);
                Quaternionf bb = birdBase(); Quaternionf flip = facing(0);
                Location bl = birdPos.toLocation(world);
                drawPart(bird, bl.clone(), new Quaternionf(bb).mul(flip), bs, bs);
                drawPart(birdHead, birdAt(0, 2, 7).toLocation(world), new Quaternionf(bb).rotateY(birdHeadYaw).mul(flip), bs, bs);
                drawPart(birdTail, birdAt(0, 0.5f, -7).toLocation(world), new Quaternionf(bb).rotateX(-0.15f).mul(flip), bs * (1 + tailFanB), bs);
                drawPart(birdWingR, birdAt(3, 2.5f, 1).toLocation(world), new Quaternionf(bb).rotateY(-sweepA).rotateZ(flapA).mul(flip), bs, bs);
                drawPart(birdWingL, birdAt(-3, 2.5f, 1).toLocation(world), new Quaternionf(bb).rotateY(sweepA).rotateZ(-flapA).mul(flip), bs, bs);
                if (birdBox != null && birdBox.isValid()) birdBox.teleport(bl.clone().subtract(0, birdBox.getHeight() / 2, 0));
                if (state == P1 || state == ARRIVE || (state == CUT1 && st < 76)) { // he rides on its back (and tilts with it)
                    pos = birdAt(0, 5.5f, -1.5f);
                    yaw = birdYaw;
                    if (state == P1 && attack >= 1 && attack <= 4) { Player tgt = nearest(active()); if (tgt != null) { Vector to = tgt.getLocation().toVector().subtract(pos); yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())); } }
                }
            }
            hitbox.teleport(pos.toLocation(world).add(0, 0.2, 0));
            Pose p = pose.copy();
            p.add(BODY, (float) Math.sin(ticks * 0.08) * 1.5f, 0, 0);
            if (flinch > 0) p.add(BODY, -flinch * 2f, 0, 0).add(HEAD, -flinch * 2f, 0, 0);
            shown = Pose.lerp(shown, p, 0.45f);
            renderRig(parts, held, pos.toLocation(world), yaw, scale, shown);
            if (ablaze && state != DEFEAT && ticks % 2 == 0) world.spawnParticle(Particle.FLAME, loc().add(0, 1.1, 0), 3, 0.35, 0.8, 0.35, 0.01);
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            bar.setTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond Jacob" + ChatColor.GRAY + "  Phase " + phase() + (state == P1 ? ChatColor.YELLOW + "  (use a bow!)" : ""));
            boolean show = state != DEFEAT && cut == null;
            for (Player p : world.getPlayers()) {
                boolean near = show && p.getLocation().toVector().distanceSquared(pos) < 90 * 90;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        void removeEverything() {
            if (state >= DEFEAT && !rewarded) { rewarded = true; try { rewards(); } catch (RuntimeException e) { logBossPart("Diamond Jacob", "rewards", e); } }
            if (cut != null) { cut.finish(); cut = null; }
            stopMusic();
            bar.removeAll();
            clearShots();
            for (JOrb o : orbs) o.remove();
            orbs.clear();
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (held != null && held.isValid()) held.remove();
            removeBird();
            if (birdBox != null && birdBox.isValid()) birdBox.remove();
            for (LivingEntity m : minions) if (m.isValid()) m.remove();
            for (LivingEntity s : army) { if (s.getVehicle() != null) s.getVehicle().remove(); if (s.isValid()) s.remove(); }
            for (LivingEntity h : mounts) if (h.isValid()) h.remove();
            minions.clear(); army.clear(); mounts.clear();
            if (hitbox.isValid()) hitbox.remove();
            state = GONE;
        }
    }

    // ---------- hits: phase 1 needs a bow; pink orbs launch into him ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onJacobDamage(EntityDamageEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        boolean body = tags.contains(JACOB_TAG), birdHit = tags.contains(JACOB_BIRD_TAG), orb = tags.contains(JACOB_ORB_TAG);
        if (!body && !birdHit && !orb) return;
        if (!byPlayer(event)) { event.setCancelled(true); return; }
        if (jacob == null) { event.setCancelled(true); return; }
        Player by = event instanceof EntityDamageByEntityEvent e ? playerFrom(e.getDamager()) : null;
        if (orb) { event.setCancelled(true); jacob.launchOrb((Slime) event.getEntity(), by); return; }
        boolean projectile = event instanceof EntityDamageByEntityEvent e && e.getDamager() instanceof org.bukkit.entity.Projectile;
        if (jacob.state == Jacob.P1 && !projectile) { // he's up on his hawk: only arrows (and thrown things) reach him
            event.setCancelled(true);
            if (by != null) by.sendActionBar(legacy(ChatColor.YELLOW + "He's out of reach up there. Use a bow!"));
            return;
        }
        double dmg = event.getDamage(); // read it BEFORE shrinking the hit (or he'd only ever take 0.001)
        event.setDamage(0.001); // like Don: the hit lands (so the Index credits you), his real health is tracked separately
        jacob.damage(dmg, by, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJacobPartDeath(EntityDeathEvent event) {
        Set<String> t = event.getEntity().getScoreboardTags();
        if (!(t.contains(JACOB_TAG) || t.contains(JACOB_BIRD_TAG) || t.contains(JACOB_ORB_TAG) || t.contains(JACOB_MINION_TAG))) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /** Banner of Defiance: his army (soldiers and their horses, not his spiders) deals 30% less and can't knock you back. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJacobArmyHit(EntityDamageByEntityEvent event) {
        if (jacob == null || !(event.getEntity() instanceof Player p)) return;
        Entity d = event.getDamager();
        if (!d.getScoreboardTags().contains(JACOB_MINION_TAG) || !(d instanceof org.bukkit.entity.Vindicator || d instanceof org.bukkit.entity.AbstractHorse)) return;
        if (!jacobCounter(p, "banner_of_defiance")) return;
        event.setDamage(event.getDamage() * 0.7);
        jacob.steady(p);
    }

    boolean inJacobCut(Player p) { return jacob != null && jacob.cut != null && jacob.cut.viewers.contains(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJacobCutTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!allowTeleport && inJacobCut(event.getPlayer())) event.setCancelled(true); // no teleporting out of his cutscenes
    }

    private class Dune {
        final World world;
        final List<Seg> segs = new ArrayList<>();
        final BossBar bar;
        Vector vel = new Vector(0, 0, 0);
        int phase = 1, attack = -1, t, ticksAlive, intro = 60, transition, lonely, deathTicks, sub, count;
        boolean dying, leaving, flying; // flying = ignore gravity (used by some attacks)
        final Set<UUID> fighters = new HashSet<>(), down = new HashSet<>();
        final Map<UUID, Integer> hitCooldown = new HashMap<>();
        final List<DShot> shots = new ArrayList<>();
        final List<Sandnado> nados = new ArrayList<>();
        final List<LivingEntity> vultures = new ArrayList<>();
        final Map<UUID, Long> musicUntil = new HashMap<>();
        UUID target;
        Vector aim, side;
        Material surface; // sand or snow, for the burrow spray
        int jaw;                   // ticks the mouth stays open (roar, spit, charge, slam)
        String headModel;
        final boolean frost;       // true = the Frostmaw (Snowy Day), false = the Dune Devourer (Sandstorm)
        String pre() { return frost ? "frostmaw_" : "dune_"; }
        String name() { return frost ? "The Frostmaw" : "The Dune Devourer"; }
        ChatColor tint() { return frost ? ChatColor.AQUA : ChatColor.GOLD; }
        Color dust() { return frost ? Color.fromRGB(215, 238, 255) : Color.fromRGB(214, 180, 110); }
        /** The Frostmaw's hits freeze and slow you. */
        void chill(Player p) {
            if (!frost) return;
            p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + 50));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 50, 1));
        }

        Dune(Player near, boolean frost) {
            this.world = near.getWorld();
            this.frost = frost;
            this.headModel = pre() + "head";
            this.surface = frost ? Material.SNOW_BLOCK : Material.SAND;
            int n = (int) dcfg("segments", 22);
            double spacing = dcfg("spacing", 1.8);
            Vector start = near.getLocation().toVector().add(new Vector(0, -10, 0)); // coiled up underground, right below you
            try {
                for (int i = 0; i < n; i++) {
                    String model = pre() + (i == 0 ? "head" : i == n - 1 ? "tail" : "body");
                    segs.add(new Seg(start.clone().add(new Vector(0, -i * spacing, 0)), model, world, i == 0, name()));
                }
            } catch (RuntimeException e) { // BUG FIX: never leave a half-built worm stuck in the world
                for (Seg sg : segs) { if (sg.display.isValid()) sg.display.remove(); if (sg.hitbox.isValid()) sg.hitbox.remove(); }
                throw e;
            }
            bar = Bukkit.createBossBar(tint() + "" + ChatColor.BOLD + name(), frost ? BarColor.BLUE : BarColor.YELLOW, BarStyle.SEGMENTED_20);
            for (Player p : world.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(near.getLocation()) < 48 * 48) fighters.add(p.getUniqueId());
            if (survival(near)) fighters.add(near.getUniqueId());
            target = near.getUniqueId();
        }

        Seg head() { return segs.get(0); }

        /** The head hitbox's real max health (the server may cap it below the configured health). */
        double hitboxMax() {
            AttributeInstance m = head().hitbox.getAttribute(Attribute.MAX_HEALTH);
            return m != null ? Math.max(1, m.getValue()) : dcfg("health", 1100);
        }

        /** Hits are scaled by this, so killing it always takes its full configured health (1,100) even under the cap. */
        double healthScale() { return Math.min(1, hitboxMax() / dcfg("health", 1100)); }
        Vector hp() { return head().pos; }

        // ---------- fighters (same rules as the other bosses) ----------
        void join(Player p) { if (!survival(p)) return; fighters.add(p.getUniqueId()); down.remove(p.getUniqueId()); }
        void fighterDied(Player p) {
            if (!fighters.contains(p.getUniqueId()) || dying || leaving) return;
            down.add(p.getUniqueId());
            if (active().isEmpty()) leave(tint() + name() + (frost ? " sinks back beneath the snow, well fed." : " sinks back beneath the sand, well fed."));
        }
        List<Player> active() {
            List<Player> list = new ArrayList<>();
            for (UUID id : fighters) {
                if (down.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead() || !survival(p) || !p.getWorld().equals(world)) continue;
                if (p.getLocation().toVector().distanceSquared(hp()) > 110 * 110) continue;
                list.add(p);
            }
            return list;
        }
        Player current() {
            Player p = target == null ? null : Bukkit.getPlayer(target);
            if (p != null && active().contains(p)) return p;
            List<Player> a = active();
            if (a.isEmpty()) return null;
            p = a.get(random.nextInt(a.size()));
            target = p.getUniqueId();
            return p;
        }
        Vector eye(Player p) { return p.getLocation().toVector().add(new Vector(0, 1.2, 0)); }
        boolean solidAt(Vector v) { return world.getBlockAt(v.toLocation(world)).getType().isSolid(); }
        /**
         * The ground surface at (x, z), relative to fromY. BUG FIX: this only looked DOWN from 3 blocks above, so once
         * the worm was more than 3 blocks deep (charging into a hill, dune, mesa or wall) it never saw the real surface,
         * answered "3 blocks below you", and a Sand Rush dove 1.2 blocks every tick (up to ~54 blocks down).
         * Now: from inside the ground it climbs to the top; from the air it finds the floor below. Also rounds
         * correctly below y=0, and over a hole or the void it stays level instead of sinking.
         */
        double groundY(double x, double z, double fromY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            int min = world.getMinHeight(), max = world.getMaxHeight() - 1;
            y = Math.max(min, Math.min(max, y));
            if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                for (int up = 0; up < 128 && y < max; up++, y++) if (!world.getBlockAt(bx, y + 1, bz).getType().isSolid()) return y + 1;
                return fromY;
            }
            // far enough for players flying with Rocket Boots
            for (int down = 0; down < 128 && y > min; down++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
            return fromY;
        }

        /**
         * BUG FIX: "the surface" used to be the HIGHEST block in the column, which in a cave or an
         * underground arena is the top of the ceiling. The worm thought it was buried far too deep and
         * got pushed up forever while steering back down at you: it bobbed in place. Now it's the first
         * open air ABOVE the worm (the floor you're standing on). None within 24 blocks: where it is.
         */
        double surfaceAbove(Vector at) {
            int x = (int) Math.floor(at.getX()), z = (int) Math.floor(at.getZ()), y0 = (int) Math.floor(at.getY());
            int max = world.getMaxHeight();
            for (int y = y0; y < y0 + 96 && y < max; y++) { // BUG FIX: used to give up past 24 blocks deep
                if (!world.getBlockAt(x, y, z).getType().isSolid()) return y;
            }
            return at.getY();
        }

        /** Steer toward a point: `turn` is how sharply it can change direction, `speed` its cruising speed. */
        void steer(Vector goal, double speed, double turn) {
            Vector want = goal.clone().subtract(hp());
            if (want.lengthSquared() < 0.01) return;
            want.normalize().multiply(speed);
            vel.add(want.subtract(vel).multiply(turn));
        }

        // ---------- main loop ----------
        void tick() {
            ticksAlive++;
            if (dying) { deathTick(); return; }
            if (leaving) { leaveTick(); return; }
            if (!head().hitbox.isValid()) { removeEverything(); dune = null; return; }
            double engage = dcfg("engage-radius", 48);
            for (Player p : world.getPlayers()) if (survival(p) && !p.isDead() && p.getLocation().toVector().distanceSquared(hp()) < engage * engage) join(p);
            List<Player> a = active();
            if (a.isEmpty()) {
                vel.multiply(0.9);
                if (++lonely > 400) { leave(tint() + name() + " loses interest and burrows away."); return; }
            } else lonely = 0;

            if (ticksAlive % 10 == 0) part("music", this::musicTick); // first: nothing below can ever stop the music
            Player target = current();
            // BUG FIX: an error partway through a move used to freeze the whole worm FOREVER (the move's timer only
            // advances at its end, so the same step failed every tick, and nothing after it (physics, projectiles,
            // drawing) ever ran again). Now a failing move is logged and skipped, and every part below runs on its own.
            try {
                if (intro > 0) introTick(target);
                else {
                    checkPhase();
                    if (transition > 0) { transition--; steer(target == null ? hp() : eye(target).add(new Vector(0, -2, 0)), 0.4, 0.08); }
                    else if (target != null) attackTick(target);
                    else flying = false; // no target: never left hovering mid-move (the frozen "pillar")
                }
                failStreak = 0;
            } catch (RuntimeException e) {
                logPart("move " + attack + " (step " + sub + ", tick " + t + ")", e);
                intro = 0; flying = false;
                next(); // skip the broken move instead of repeating it forever
                if (++failStreak > 100) { leave(tint() + name() + " burrows away."); return; } // failsafe: never stands frozen
            }
            part("physics", this::physics);
            part("body", this::follow);
            if (intro <= 0) part("contact damage", () -> contactDamage(a));
            part("projectiles", () -> tickShots(a));
            part("sandnadoes", () -> tickNados(a));
            vultures.removeIf(v -> !v.isValid() || v.isDead());
            if (ticksAlive % 40 == 0) for (LivingEntity bird : vultures) { // stay on the fight, never drift off
                if (!(bird instanceof org.bukkit.entity.Phantom v)) continue;
                Player f = null; double best = Double.MAX_VALUE;
                for (Player p : active()) { double dd = p.getLocation().distanceSquared(v.getLocation()); if (dd < best) { best = dd; f = p; } }
                if (f == null) continue;
                if (v.getTarget() == null || !v.getTarget().equals(f)) v.setTarget(f);
                v.setAnchorLocation(f.getLocation().add(0, 8, 0));
            }
            part("drawing", this::render);
            part("watchdog", () -> watchdog(a));
            part("progress watchdog", () -> progressWatchdog(a));
            hitCooldown.replaceAll((k, v) -> v - 1);
            hitCooldown.values().removeIf(v -> v <= 0);
            part("boss bar", this::updateBar);
        }

        int failStreak;
        final Map<String, Long> partErrorLog = new HashMap<>();

        /** Runs one part of the worm's update; if it errors, it's logged and the rest of the worm keeps going. */
        void part(String what, Runnable r) {
            try { r.run(); } catch (RuntimeException e) { logPart(what, e); }
        }

        void logPart(String what, RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - partErrorLog.getOrDefault(what, 0L) < 30000) return; // at most every 30 seconds per part
            partErrorLog.put(what, now);
            getLogger().log(java.util.logging.Level.SEVERE, "[Dune worm] " + name() + ": error in " + what + " (skipped, it keeps going; please report this):", e);
        }

        /** Underground it steers freely; in the air, gravity pulls it into an arc (unless an attack is flying it). */
        void physics() {
            boolean inGround = solidAt(hp());
            if (inGround && !flying) { // never burrows deep out of reach: pushed back up past the max depth
                double top = surfaceAbove(hp());
                // BUG FIX: this SET its vertical speed to +0.08 (up) every tick past 3 blocks deep, cancelling any dive.
                // Ambush wants to go 4+ blocks deep, so it could never get there and bobbed at the surface for 2.5s.
                // Now: a deliberate dive is allowed deeper, and the push adds lift instead of overriding its speed.
                double maxDepth = (attack == 5 && sub == 0) ? 9 : dcfg("max-burrow-depth", 3);
                double depth = top - hp().getY();
                if (depth > maxDepth) vel.setY(vel.getY() + Math.min(0.25, 0.06 + (depth - maxDepth) * 0.02));
            }
            if (!inGround && !flying) {
                vel.setY(vel.getY() - dcfg("gravity", 0.045));
                if (vel.getY() < -1.4) vel.setY(-1.4);
            }
            double max = dcfg("max-speed", 1.3);
            if (vel.length() > max) vel.normalize().multiply(max);
            hp().add(vel);
        }

        /** Each segment follows the one in front of it at a fixed spacing, like a chain. */
        void follow() {
            double spacing = dcfg("spacing", 1.8);
            for (int i = 1; i < segs.size(); i++) {
                Vector lead = segs.get(i - 1).pos, me = segs.get(i).pos;
                Vector d = me.clone().subtract(lead);
                if (d.lengthSquared() < 0.0001) d = new Vector(0, -1, 0);
                segs.get(i).pos = lead.clone().add(d.normalize().multiply(spacing));
            }
            // sand (or snow) sprays wherever a segment breaks the surface
            for (Seg s : segs) {
                boolean in = solidAt(s.pos);
                if (in != s.wasInGround && ticksAlive > 2) {
                    Location l = s.pos.toLocation(world);
                    world.spawnParticle(Particle.BLOCK, l, 18, 0.6, 0.3, 0.6, 0, surface.createBlockData());
                    if (s == head()) world.playSound(l, surface == Material.SAND ? org.bukkit.Sound.BLOCK_SAND_BREAK : org.bukkit.Sound.BLOCK_SNOW_BREAK, 1.6f, 0.6f);
                }
                s.wasInGround = in;
            }
        }

        // ---------- intro: rumbling, then it bursts out from under you ----------
        void introTick(Player target) {
            intro--;
            if (target == null) return;
            Vector feet = target.getLocation().toVector();
            if (intro > 0) {
                flying = true;
                vel = new Vector();
                hp().setX(feet.getX()); hp().setZ(feet.getZ()); hp().setY(feet.getY() - 9);
                if (intro % 3 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double ang = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * 4;
                        Location l = target.getLocation().add(Math.cos(ang) * r, 0.1, Math.sin(ang) * r);
                        world.spawnParticle(Particle.BLOCK, l, 6, 0.2, 0.1, 0.2, 0.05, surface.createBlockData());
                    }
                }
                if (intro % 10 == 0) world.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_WARDEN_DIG, 1.5f, 0.6f);
            } else { // burst out from behind, arcing over you
                flying = false;
                Vector behind = target.getLocation().getDirection().setY(0);
                if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
                hp().add(behind.normalize().multiply(-5));
                vel = new Vector(0, 1.25, 0).add(behind.multiply(0.25));
                world.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 3f, 0.6f);
                world.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1.4f);
            }
        }

        void next() {
            int[] pool = phase == 1 ? new int[]{0, 0, 1, 2, 5} : phase == 2 ? new int[]{0, 1, 2, 3, 3, 5, 6} : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 7};
            int pick;
            do pick = pool[random.nextInt(pool.length)]; while (pick == attack && pool.length > 1);
            if (pick == 4 && vultures.size() >= 8) pick = 0;
            attack = pick; t = 0; sub = 0; count = 0; flying = false; target = null;
        }

        void attackTick(Player target) {
            if (attack < 0) { next(); return; }
            Vector tp = eye(target);
            double dist = tp.distance(hp());
            switch (attack) {
                case 0 -> { // SAND SPIT: sharp-turning chase; from range, rings of 7 Sand Blasts
                    // BUG FIX: up close it steered straight at your eyes (just above the ground) with a very weak turn.
                    // Gravity pulled it down in the air and it steered back up in the ground, so it PARKED at your feet,
                    // bobbing at the surface (simulated: median 1.7 blocks from you at a quarter of its speed, and 77% of
                    // all watchdog rescues). Now it travels under the ground, then BREACHES: bursts up through you and
                    // past, arcs back down, and lines up again.
                    Vector under = tp.clone().setY(groundY(tp.getX(), tp.getZ(), tp.getY()) - dcfg("travel-depth", 2.5));
                    // BUG FIX: on a bridge or thin platform over open air, "under your floor" is in the AIR, so the worm could
                    // never get buried and never breached (it just bobbed under the bridge). Use the real ground below instead.
                    if (!solidAt(under)) under.setY(groundY(tp.getX(), tp.getZ(), under.getY()) - dcfg("travel-depth", 2.5));
                    count++;
                    if (sub == 1) { // the breach: up through you and past
                        flying = true;
                        jaw = Math.max(jaw, 2);
                        vel = aim.clone().multiply(dcfg("breach-speed", 1.15));
                        // BUG FIX: the fan only fired from the air at long range, but the worm now closes in underground, so
                        // "Sand Spit" almost never spat. It spits a fan at the top of every breach.
                        if (count == 9) fan(hp(), tp, 7, dcfg("spit-spread-degrees", 40), 0.55, dcfg("blast-damage", 6));
                        if (count >= 14) { flying = false; sub = 2; count = 0; } // gravity arcs it back down
                    } else if (sub == 2) { // dives back under and lines up again
                        steer(under, dcfg("spit-speed", 0.75), 0.14);
                        if (count >= 25) { sub = 0; count = 0; }
                    } else {
                        steer(under, dcfg("spit-speed", 0.75), 0.14);
                        if (dist < 10 && solidAt(hp())) { // close and buried: breach!
                            aim = tp.clone().subtract(hp());
                            if (aim.lengthSquared() < 0.01) aim = new Vector(0, 1, 0);
                            aim.normalize();
                            if (aim.getY() < 0.35) aim.setY(0.35).normalize(); // always bursts UP out of the ground
                            sub = 1; count = 0;
                            roar(1.2f);
                        }
                    }
                    // BUG FIX: this ring was fired flat at the head's height, so it missed whenever the head was
                    // above or below you, and underground the shots died in the dirt. Now: a fan of 7 aimed at you,
                    // only when the head is out in the open.
                    if (dist > 12 && t % 27 == 0 && !solidAt(hp())) fan(hp(), tp, 7, dcfg("spit-spread-degrees", 40), 0.55, dcfg("blast-damage", 6));
                    if (t >= 120) next();
                }
                case 1 -> { // SAND RUSH CHARGE: sinks to your side, then charges underground, spraying blasts up
                    if (sub == 0) {
                        if (t == 0) { side = new Vector(-(tp.getZ() - hp().getZ()), 0, tp.getX() - hp().getX()); if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0); side.normalize(); }
                        double gy = groundY(tp.getX(), tp.getZ(), tp.getY());
                        steer(tp.clone().add(side.clone().multiply(14)).setY(gy - 2), 0.7, 0.12);
                        if (solidAt(hp()) && hp().getY() < gy - 1) {
                            sub = 1; t = 0;
                            aim = tp.clone().subtract(hp()).setY(0);
                            if (aim.lengthSquared() < 0.01) aim = side.clone().multiply(-1);
                            aim.normalize();
                            roar(1.0f);
                        } else if (t > 100) next(); // took too long to get into the ground
                    } else {
                        flying = true;
                        jaw = Math.max(jaw, 2); // mouth open the whole charge
                        double gy = groundY(hp().getX(), hp().getZ(), hp().getY());
                        vel = aim.clone().multiply(dcfg("rush-speed", 1.0));
                        hp().setY(hp().getY() + ((gy - dcfg("rush-depth", 1.0)) - hp().getY()) * 0.3); // just under the surface
                        if (t % 5 == 0) {
                            Vector up = hp().clone().setY(gy + 0.2);
                            shots.add(new DShot(up, new Vector(random.nextGaussian() * 0.12, 0.85 + random.nextDouble() * 0.25, random.nextGaussian() * 0.12),
                                    0.04, dcfg("blast-damage", 6), 80));
                            world.spawnParticle(Particle.BLOCK, up.toLocation(world), 25, 0.3, 1.2, 0.3, 0.05, surface.createBlockData()); // sand geyser
                        }
                        if (t >= 45) { flying = false; vel = aim.clone().multiply(0.6).setY(0.8); next(); }
                    }
                }
                case 2 -> { // SANDSTORM: wind and dust; groups of 3 blasts sweep in from your sides while it prowls
                    // circles around you just under the surface (it used to crawl so slowly it looked frozen)
                    double ang = t * 0.05;
                    Vector ring = tp.clone().add(new Vector(Math.cos(ang) * 10, -2.5, Math.sin(ang) * 10));
                    steer(ring, dcfg("sandstorm-prowl-speed", 0.6), 0.1);
                    if (t % 4 == 0) for (Player p : active())
                        { p.spawnParticle(Particle.DUST, p.getLocation().add(0, 1.5, 0), 20, 6, 2, 6, 0, new Particle.DustOptions(dust(), 2.2f));
                          if (frost) p.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 2, 0), 30, 7, 3, 7, 0.05); } // whiteout
                    if (t % 10 == 0) {
                        List<Player> a = active();
                        Player p = a.isEmpty() ? target : a.get(random.nextInt(a.size()));
                        Vector across = new Vector(random.nextBoolean() ? 1 : -1, 0, 0);
                        if (random.nextBoolean()) across = new Vector(0, 0, across.getX());
                        // BUG FIX: these spawned 3 blocks above your eyes and flew straight across, so they
                        // always passed over your head. Now each group comes in at chest height, aimed at you.
                        Vector chest = p.getLocation().toVector().add(new Vector(0, 1.0, 0));
                        Vector from = chest.clone().add(across.clone().multiply(-16));
                        for (int i = -1; i <= 1; i++) {
                            Vector start = from.clone().add(new Vector(0, i * 0.9, 0)).add(new Vector(-across.getZ(), 0, across.getX()).multiply(i * 0.6));
                            Vector v = chest.clone().subtract(start).normalize().multiply(dcfg("sandstorm-blast-speed", 0.6));
                            shots.add(new DShot(start, v, 0, dcfg("blast-damage", 6), 70));
                        }
                        world.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.7f);
                    }
                    if (t >= 160) next();
                }
                case 3 -> { // GROUND SLAM (twice): climbs high, roars, plummets; the impact bursts into blasts + two Sandnadoes
                    if (sub == 0) {
                        flying = true;
                        steer(tp.clone().add(new Vector(random.nextGaussian() * 2, 24, random.nextGaussian() * 2)), 1.0, 0.15);
                        if (t >= 40) { sub = 1; t = 0; roar(0.8f); }
                    } else if (sub == 1) {
                        flying = false;
                        jaw = Math.max(jaw, 2); // gaping as it plummets
                        steer(tp.clone().add(new Vector(0, -6, 0)), 1.3, 0.25);
                        if (solidAt(hp()) || t > 70) {
                            Vector at = hp().clone();
                            at.setY(groundY(at.getX(), at.getZ(), at.getY() + 2) + 0.5);
                            for (int i = 0; i < 16; i++) {
                                double ang = Math.PI * 2 * i / 16 + random.nextDouble() * 0.2;
                                double sp = 0.35 + random.nextDouble() * 0.25;
                                shots.add(new DShot(at.clone(), new Vector(Math.cos(ang) * sp, 0.35 + random.nextDouble() * 0.35, Math.sin(ang) * sp),
                                        0.04, dcfg("blast-damage", 6), 70));
                            }
                            Vector perp = new Vector(-vel.getZ(), 0, vel.getX());
                            if (perp.lengthSquared() < 0.01) perp = new Vector(1, 0, 0);
                            perp.normalize().multiply(dcfg("sandnado-speed", 0.08));
                            nados.add(new Sandnado(at.clone(), perp.clone()));
                            nados.add(new Sandnado(at.clone(), perp.clone().multiply(-1)));
                            world.spawnParticle(Particle.BLOCK, at.toLocation(world), 120, 2.5, 0.5, 2.5, 0.1, surface.createBlockData());
                            world.playSound(at.toLocation(world), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
                            count++;
                            sub = 2; t = 0;
                        }
                    } else if (t >= 15) {
                        if (count >= 2) next(); else { sub = 0; t = 0; }
                    }
                }
                case 4 -> { // VULTURES: a short roar and four vultures circle down from above
                    double orbit = t * 0.12; // prowls in a ring under you while they arrive (it used to sit at your feet)
                    steer(tp.clone().add(new Vector(Math.cos(orbit) * 7, -3.5, Math.sin(orbit) * 7)), 0.7, 0.12);
                    if (t == 5) {
                        roar(1.4f);
                        for (int i = 0; i < 4 && vultures.size() < 8; i++) {
                            double ang = Math.PI * 2 * i / 4;
                            Location l = target.getLocation().add(Math.cos(ang) * 6, 9, Math.sin(ang) * 6);
                            org.bukkit.entity.Phantom v = spawnVulture(l, false, frost ? "Snow Owl" : "Vulture");
                            if (v != null) { v.setTarget(target); vultures.add(v); }
                        }
                    }
                    if (t >= 40) next();
                }
                case 5 -> { // AMBUSH: dives out of sight, a bulge tracks under your feet... then it erupts
                    double gy = groundY(tp.getX(), tp.getZ(), tp.getY());
                    if (sub == 0) {
                        steer(tp.clone().setY(gy - 7), 0.9, 0.2);
                        if ((solidAt(hp()) && hp().getY() < gy - 4) || t > 50) { sub = 1; t = 0; }
                    } else if (sub == 1) {
                        flying = true;
                        vel = new Vector();
                        hp().setX(hp().getX() + (tp.getX() - hp().getX()) * 0.25); // follows you underground
                        hp().setZ(hp().getZ() + (tp.getZ() - hp().getZ()) * 0.25);
                        hp().setY(gy - 6);
                        if (t % 3 == 0) {
                            Location bulge = new Location(world, hp().getX(), gy + 0.05, hp().getZ());
                            world.spawnParticle(Particle.BLOCK, bulge, 18, 0.9, 0.2, 0.9, 0.08, surface.createBlockData());
                        }
                        if (t % 8 == 0) world.playSound(new Location(world, hp().getX(), gy, hp().getZ()), org.bukkit.Sound.ENTITY_WARDEN_DIG, 1.4f, 0.6f);
                        if (t >= dcfg("ambush-warning-ticks", 22)) { // ERUPT
                            flying = false;
                            vel = new Vector(0, 1.45, 0);
                            aim = new Vector(hp().getX(), gy, hp().getZ());
                            roar(1.1f);
                            world.spawnParticle(Particle.BLOCK, aim.toLocation(world), 90, 1.5, 0.4, 1.5, 0.15, surface.createBlockData());
                            for (Player p : active()) {
                                Vector d = p.getLocation().toVector().subtract(aim);
                                if (d.getX() * d.getX() + d.getZ() * d.getZ() > 2.8 * 2.8 || Math.abs(d.getY()) > 3) continue;
                                hurt(p, dcfg("ambush-damage", 12), head().hitbox, aim, Guard.HEAVY);
                                chill(p);
                                p.setVelocity(p.getVelocity().setY(1.0));
                                hitCooldown.put(p.getUniqueId(), 12);
                            }
                            sub = 2; t = 0;
                        }
                    } else if (t >= 30) next();
                }
                case 6 -> { // TREMOR: climbs, slams straight down, and a shockwave rolls out along the ground (jump it!)
                    if (sub == 0) {
                        flying = true;
                        steer(tp.clone().add(new Vector(0, 9, 0)), 1.0, 0.2);
                        if (t >= 25) { sub = 1; t = 0; roar(0.7f); }
                    } else if (sub == 1) {
                        flying = false;
                        vel = new Vector(vel.getX() * 0.5, -1.3, vel.getZ() * 0.5);
                        if (solidAt(hp()) || t > 60) {
                            aim = hp().clone();
                            aim.setY(groundY(aim.getX(), aim.getZ(), aim.getY() + 3));
                            world.playSound(aim.toLocation(world), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
                            sub = 2; t = 0;
                        }
                    } else {
                        double r = 1 + t * 0.7; // the ring rolls outward
                        for (int i = 0; i < 28; i++) {
                            double a = Math.PI * 2 * i / 28;
                            Location l = new Location(world, aim.getX() + Math.cos(a) * r, aim.getY() + 0.1, aim.getZ() + Math.sin(a) * r);
                            world.spawnParticle(Particle.BLOCK, l, 2, 0.1, 0.1, 0.1, 0, surface.createBlockData());
                        }
                        if (t % 2 == 0) world.playSound(aim.toLocation(world), org.bukkit.Sound.BLOCK_GRAVEL_BREAK, 1.2f, 0.5f);
                        for (Player p : active()) {
                            if (hitCooldown.containsKey(p.getUniqueId()) || !p.isOnGround()) continue; // jumping over it dodges it
                            Vector d = p.getLocation().toVector().subtract(aim);
                            double flat = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
                            if (Math.abs(flat - r) > 1.0 || Math.abs(d.getY()) > 1.5) continue;
                            hurt(p, dcfg("tremor-damage", 7), head().hitbox, aim, Guard.UNBLOCKABLE);
                            chill(p);
                            p.setVelocity(p.getVelocity().setY(0.8));
                            hitCooldown.put(p.getUniqueId(), 10);
                        }
                        if (t >= 20) next();
                    }
                }
                case 7 -> { // BREATH: surfaces a few blocks away, jaw open, and sprays a cone at you
                    if (sub == 0) {
                        Vector from = hp().clone().subtract(tp).setY(0);
                        if (from.lengthSquared() < 0.01) from = new Vector(1, 0, 0);
                        if (t == 0) aim = breathSpot(tp, from.normalize()); // BUG FIX: it used to hover inside walls
                        Vector spot = aim;
                        flying = true;
                        steer(spot, 1.0, 0.25);
                        if (hp().distanceSquared(spot) < 2.5 * 2.5 || t > 45) { sub = 1; t = 0; roar(1.3f); }
                    } else {
                        flying = true;
                        vel.multiply(0.5);
                        jaw = Math.max(jaw, 2);
                        vel = vel.add(tp.clone().subtract(hp()).multiply(0.001)); // keeps its head turned toward you
                        if (t % 2 == 0) {
                            Vector aimAt = tp.clone().subtract(hp());
                            if (aimAt.lengthSquared() > 0.01) {
                                aimAt.normalize();
                                Vector d = aimAt.clone().rotateAroundY(Math.toRadians(random.nextGaussian() * 12))
                                        .add(new Vector(0, random.nextGaussian() * 0.08, 0)).normalize().multiply(0.75);
                                shots.add(new DShot(hp().clone().add(aimAt.clone().multiply(1.6)), d, 0, dcfg("breath-damage", 3), 30));
                                Location mouth = hp().toLocation(world).add(aimAt.multiply(1.8));
                                if (frost) world.spawnParticle(Particle.SNOWFLAKE, mouth, 6, 0.3, 0.3, 0.3, 0.05);
                                else world.spawnParticle(Particle.BLOCK, mouth, 6, 0.3, 0.3, 0.3, 0.05, surface.createBlockData());
                            }
                        }
                        if (t % 10 == 0) world.playSound(hp().toLocation(world), frost ? org.bukkit.Sound.BLOCK_POWDER_SNOW_BREAK : org.bukkit.Sound.BLOCK_SAND_BREAK, 1.6f, 0.5f);
                        if (t >= 40) { flying = false; next(); }
                    }
                }
                default -> next();
            }
            t++;
        }

        Vector lastSpot;
        int stillTicks;
        /** Safety net: if it has barely moved for 3 seconds while someone's fighting it, start a new attack. */
        /**
         * Safety net: every 3 seconds, if the head hasn't gotten at least 3 blocks from where it was
         * 3 seconds ago (frozen, bobbing, or jittering in place), it drops what it's doing and lunges at you.
         */
        int lastClose; // when it was last near a fighter

        /**
         * BUG FIX: the old watchdog only noticed a head that moved under 3 blocks in 3 seconds. A worm bobbing up and
         * down, or circling inside a wall, never counted as stuck. This notices "nowhere near anyone for 12 seconds"
         * and resurfaces it: it burrows under a fighter and bursts out, like its entrance.
         */
        void progressWatchdog(List<Player> a) {
            if (a.isEmpty() || intro > 0 || transition > 0) { lastClose = ticksAlive; return; }
            for (Player p : a) if (eye(p).distanceSquared(hp()) < 14 * 14) { lastClose = ticksAlive; return; }
            if (ticksAlive - lastClose < 240) return;
            lastClose = ticksAlive;
            Player f = a.get(random.nextInt(a.size()));
            Vector feet = f.getLocation().toVector();
            double gy = groundY(feet.getX(), feet.getZ(), feet.getY() + 0.5);
            Vector behind = f.getLocation().getDirection().setY(0);
            if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
            Vector spot = feet.clone().subtract(behind.normalize().multiply(5)).setY(gy - 3);
            hp().copy(spot);
            flying = false;
            vel = new Vector(0, 1.2, 0).add(behind.multiply(0.25));
            roar(1.0f);
            world.spawnParticle(Particle.BLOCK, spot.toLocation(world).add(0, 3, 0), 60, 1.2, 0.4, 1.2, 0.1, surface.createBlockData());
            getLogger().info("[Dune worm] " + name() + " was stuck away from everyone for 12s: resurfaced it next to " + f.getName() + ".");
            next();
        }

        /** A spot 8 blocks from you with open air and a clear line to you (tries a few angles, then higher up). */
        Vector breathSpot(Vector tp, Vector away) {
            for (int lift = 0; lift <= 6; lift += 3) {
                for (int deg : new int[]{0, 50, -50, 100, -100, 150, -150, 180}) {
                    Vector spot = tp.clone().add(away.clone().rotateAroundY(Math.toRadians(deg)).multiply(8)).setY(tp.getY() + 1.5 + lift);
                    if (solidAt(spot) || solidAt(spot.clone().add(new Vector(0, 1, 0)))) continue;
                    org.bukkit.util.RayTraceResult hit = world.rayTraceBlocks(spot.toLocation(world), tp.clone().subtract(spot).normalize(),
                            spot.distance(tp), org.bukkit.FluidCollisionMode.NEVER, true);
                    if (hit == null) return spot;
                }
            }
            return tp.clone().add(away.clone().multiply(8)).setY(tp.getY() + 4.5);
        }

        void watchdog(List<Player> a) {
            if (intro > 0 || transition > 0 || a.isEmpty() || attack == 5 || attack == 7) { stillTicks = 0; lastSpot = hp().clone(); return; } // these hold still on purpose
            if (lastSpot == null) lastSpot = hp().clone();
            if (++stillTicks < 60) return;
            boolean stuck = lastSpot.distanceSquared(hp()) < 3 * 3;
            stillTicks = 0;
            lastSpot = hp().clone();
            if (!stuck) return;
            flying = false;
            next();
            Player t = current();
            if (t != null) {
                Vector lunge = eye(t).subtract(hp());
                if (lunge.lengthSquared() > 0.01) vel = lunge.normalize().multiply(1.0);
            }
        }

        void roar(float pitch) {
            jaw = Math.max(jaw, 20);
            if (frost) { // a colder, higher shriek, with ice cracking
                world.playSound(hp().toLocation(world), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 3f, pitch * 0.95f);
                world.playSound(hp().toLocation(world), org.bukkit.Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);
                return;
            }
            world.playSound(hp().toLocation(world), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 3f, pitch * 0.7f);
            world.playSound(hp().toLocation(world), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, pitch * 1.3f);
        }

        /** A fan of blasts aimed at a point, spread sideways by `spreadDeg` in total. */
        void fan(Vector from, Vector at, int n, double spreadDeg, double speed, double dmg) {
            jaw = Math.max(jaw, 8);
            Vector aim = at.clone().subtract(from);
            if (aim.lengthSquared() < 0.01) return;
            aim.normalize();
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(-spreadDeg / 2 + spreadDeg * i / Math.max(1, n - 1));
                Vector d = aim.clone().rotateAroundY(a).normalize().multiply(speed);
                shots.add(new DShot(from.clone(), d, 0, dmg, 70));
            }
            world.playSound(from.toLocation(world), org.bukkit.Sound.ENTITY_LLAMA_SPIT, 2f, frost ? 0.9f : 0.5f);
        }

        void ring(Vector at, int n, double speed, double up, double gravity, double dmg) {
            jaw = Math.max(jaw, 8);
            for (int i = 0; i < n; i++) {
                double ang = Math.PI * 2 * i / n;
                shots.add(new DShot(at.clone(), new Vector(Math.cos(ang) * speed, up, Math.sin(ang) * speed), gravity, dmg, 70));
            }
            world.playSound(at.toLocation(world), org.bukkit.Sound.ENTITY_LLAMA_SPIT, 2f, 0.5f);
        }

        void checkPhase() {
            double frac = head().hitbox.getHealth() / hitboxMax();
            int want = frac <= dcfg("phase-3-at", 0.25) ? 3 : frac <= dcfg("phase-2-at", 0.55) ? 2 : 1;
            if (want <= phase) return;
            phase = want;
            transition = 30;
            attack = -1;
            flying = false;
            roar(0.6f);
            world.spawnParticle(Particle.BLOCK, hp().toLocation(world), 80, 2, 1, 2, 0.1, surface.createBlockData());
            for (Player p : active()) p.sendActionBar(legacy(tint() + (phase == 2 ? name() + " thrashes wildly!" : frost ? "Snow Owls circle overhead..." : "Vultures circle overhead...")));
        }

        // ---------- damage ----------
        void contactDamage(List<Player> a) {
            for (Player p : a) {
                if (hitCooldown.containsKey(p.getUniqueId())) continue;
                Vector pc = p.getLocation().toVector().add(new Vector(0, 0.9, 0));
                for (int i = 0; i < segs.size(); i++) {
                    Seg s = segs.get(i);
                    double r = s.half() + 0.7;
                    if (pc.distanceSquared(s.pos) > r * r) continue;
                    boolean isHead = i == 0;
                    hurt(p, isHead ? dcfg("head-damage", 10) : dcfg("body-damage", 5), head().hitbox, s.pos, isHead ? Guard.HEAVY : Guard.BLOCKABLE);
                    if (isHead) chill(p);
                    Vector push = p.getLocation().toVector().subtract(s.pos).setY(0);
                    if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                    p.setVelocity(push.normalize().multiply(isHead ? 1.0 : 0.6).setY(0.45));
                    hitCooldown.put(p.getUniqueId(), 12);
                    break;
                }
            }
        }

        void tickShots(List<Player> a) {
            for (DShot s : new ArrayList<>(shots)) {
                s.life++;
                s.vel.setY(s.vel.getY() - s.gravity);
                Vector nextPos = s.pos.clone().add(s.vel);
                boolean remove = s.life > s.maxLife;
                if (!remove && s.life > 2 && solidAt(nextPos)) {
                    world.spawnParticle(Particle.BLOCK, s.pos.toLocation(world), 8, 0.2, 0.2, 0.2, 0, surface.createBlockData());
                    remove = true;
                } else s.pos = nextPos;
                for (Player p : a) {
                    if (remove || hitCooldown.containsKey(p.getUniqueId())) continue;
                    if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(s.pos) > 1.0) continue;
                    boolean blocked = facingBlock(p, s.pos);
                    hurt(p, s.damage, head().hitbox, s.pos, Guard.BLOCKABLE);
                    if (!blocked) chill(p);
                    hitCooldown.put(p.getUniqueId(), 8);
                    remove = true;
                }
                if (remove) { s.display.remove(); shots.remove(s); }
                else {
                    s.display.teleport(s.pos.toLocation(world));
                    if (s.life % 2 == 0) bedrockTrail(s.pos.toLocation(world), dust(), 1.3f);
                }
            }
        }

        /** Sandnadoes: very tall, very slow columns of sand that lift and hurt anyone caught inside. */
        void tickNados(List<Player> a) {
            for (Sandnado n : new ArrayList<>(nados)) {
                n.life++;
                n.pos.add(n.drift);
                n.pos.setY(groundY(n.pos.getX(), n.pos.getZ(), n.pos.getY() + 2));
                for (int h = 0; h < 12; h += 1) {
                    double ang = n.life * 0.5 + h * 0.8, r = 0.6 + h * 0.12;
                    world.spawnParticle(Particle.BLOCK, n.pos.getX() + Math.cos(ang) * r, n.pos.getY() + h, n.pos.getZ() + Math.sin(ang) * r,
                            2, 0.1, 0.1, 0.1, 0, surface.createBlockData());
                }
                if (n.life % 3 == 0) bedrockTrail(n.pos.toLocation(world).add(0, 2, 0), dust(), 2f);
                if (frost && n.life % 2 == 0) world.spawnParticle(Particle.SNOWFLAKE, n.pos.toLocation(world).add(0, 5, 0), 12, 0.8, 4, 0.8, 0.05);
                for (Player p : a) {
                    Vector d = p.getLocation().toVector().subtract(n.pos);
                    if (d.getY() < -1 || d.getY() > 12 || d.getX() * d.getX() + d.getZ() * d.getZ() > 2.2) continue;
                    p.setVelocity(p.getVelocity().setY(0.9));
                    if (!hitCooldown.containsKey(p.getUniqueId())) {
                        hurt(p, dcfg("sandnado-damage", 4), head().hitbox, n.pos, Guard.UNBLOCKABLE);
                        chill(p);
                        hitCooldown.put(p.getUniqueId(), 15);
                    }
                }
                if (n.life > 100) nados.remove(n);
            }
        }

        // ---------- look ----------
        void render() {
            if (jaw > 0) jaw--;
            String wantHead = pre() + (jaw > 0 ? "head_open" : "head");
            if (!wantHead.equals(headModel)) { headModel = wantHead; head().display.setItemStack(modelItem(wantHead)); }
            // it announces itself: sand bulges and sprays on the surface above it when it burrows close under you
            if (ticksAlive % 2 == 0 && solidAt(hp())) {
                double top = surfaceAbove(hp());
                if (top - hp().getY() < 5) {
                    Location bulge = new Location(world, hp().getX(), top + 0.05, hp().getZ());
                    world.spawnParticle(Particle.BLOCK, bulge, 10, 0.9, 0.2, 0.9, 0.05, surface.createBlockData());
                    if (ticksAlive % 20 == 0) world.playSound(bulge, org.bukkit.Sound.ENTITY_WARDEN_DIG, 0.9f, 0.7f);
                }
            }
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                Location at = s.pos.toLocation(world);
                s.hitbox.teleport(at.clone().subtract(0, s.half(), 0));
                Vector dir;
                if (i == 0) dir = vel.lengthSquared() > 0.001 ? vel.clone() : new Vector(0, 0, 1);
                else if (i == segs.size() - 1) dir = s.pos.clone().subtract(segs.get(i - 1).pos); // the stinger trails behind
                else dir = segs.get(i - 1).pos.clone().subtract(s.pos);
                if (dir.lengthSquared() > 0.0001) at.setDirection(dir);
                s.display.teleport(at);
                if (ticksAlive % 2 == 0) { // slither: each segment rolls a little, out of step with the one ahead
                    float scale = (float) dcfg("model-scale", 2.2);
                    float roll = (float) Math.sin(ticksAlive * 0.18 + i * 0.7) * 0.14f;
                    s.display.setInterpolationDelay(0);
                    s.display.setTransformation(new Transformation(new Vector3f(), facing(roll), new Vector3f(scale, scale, scale), new Quaternionf()));
                }
            }
        }

        void musicTick() {
            if (!getConfig().getBoolean("dune.music.enabled", true)) return;
            double radius = dcfg("music.radius", 64);
            long now = System.currentTimeMillis();
            long loopMs = (long) (dcfg("music.length-seconds", 304.2) * 1000);
            Set<UUID> in = new HashSet<>();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(hp()) > radius * radius) continue;
                in.add(p.getUniqueId());
                Long until = musicUntil.get(p.getUniqueId());
                if (until == null || now >= until - 150) {
                    p.stopSound(SoundCategory.MUSIC);
                    p.playSound(p, "faultline:dune.music", SoundCategory.RECORDS, (float) dcfg("music.volume", 0.8), 1f);
                    musicUntil.put(p.getUniqueId(), now + loopMs);
                }
            }
            for (UUID id : new ArrayList<>(musicUntil.keySet())) {
                if (in.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.stopSound("faultline:dune.music", SoundCategory.RECORDS);
                musicUntil.remove(id);
            }
        }

        void stopMusic() {
            for (UUID id : musicUntil.keySet()) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound("faultline:dune.music", SoundCategory.RECORDS); }
            musicUntil.clear();
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, head().hitbox.getHealth() / hitboxMax())));
            bar.setTitle(tint() + "" + ChatColor.BOLD + name() + (frost ? ChatColor.WHITE : ChatColor.YELLOW)
                    + (phase == 1 ? "" : phase == 2 ? " - Thrashing" : " - Ravenous"));
            if (ticksAlive % 10 != 0) return;
            for (Player p : new ArrayList<>(bar.getPlayers()))
                if (!p.isOnline() || !p.getWorld().equals(world) || p.getLocation().toVector().distanceSquared(hp()) > 90 * 90) bar.removePlayer(p);
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(hp()) <= 90 * 90 && !bar.getPlayers().contains(p)) bar.addPlayer(p);
        }

        // ---------- endings ----------
        void die() {
            if (dying) return;
            dying = true;
            stopMusic();
            bar.removeAll();
            roar(0.5f);
            Bukkit.broadcastMessage(tint() + "" + ChatColor.BOLD + name() + " has been slain!");
            clearMinions();
            rewards();
            duneCooldownUntil = System.currentTimeMillis() + (long) (dcfg("cooldown-minutes", 30) * 60000);
        }

        /** Bursts apart segment by segment, from the tail up to the head. */
        void deathTick() {
            deathTicks++;
            if (deathTicks % 2 == 0 && !segs.isEmpty()) {
                Seg s = segs.remove(segs.size() - 1);
                Location l = s.pos.toLocation(world);
                world.spawnParticle(Particle.BLOCK, l, 40, 0.8, 0.8, 0.8, 0.1, surface.createBlockData());
                world.playSound(l, frost ? org.bukkit.Sound.BLOCK_GLASS_BREAK : org.bukkit.Sound.BLOCK_SAND_BREAK, 1.5f, 0.6f);
                s.display.remove();
                if (s.hitbox.isValid()) s.hitbox.remove();
            }
            if (segs.isEmpty()) { removeEverything(); dune = null; }
        }

        void leave(String message) {
            if (leaving || dying) return;
            leaving = true;
            stopMusic();
            Bukkit.broadcastMessage(message);
            duneCooldownUntil = System.currentTimeMillis() + (long) (dcfg("cooldown-minutes", 30) * 60000);
        }

        void leaveTick() { // dives down and away
            deathTicks++;
            if (deathTicks == 1) { bar.removeAll(); clearMinions(); }
            vel = new Vector(0, -0.8, 0);
            hp().add(vel);
            follow();
            render();
            if (deathTicks >= 50) { removeEverything(); dune = null; }
        }

        void clearMinions() {
            for (DShot s : shots) s.display.remove();
            shots.clear();
            nados.clear();
            for (LivingEntity v : vultures) if (v.isValid()) v.remove();
            vultures.clear();
        }

        void removeEverything() {
            stopMusic();
            clearMinions();
            bar.removeAll();
            for (Seg s : segs) { if (s.display.isValid()) s.display.remove(); if (s.hitbox.isValid()) s.hitbox.remove(); }
        }

        void rewards() {
            Location at = hp().toLocation(world);
            // BUG FIX: it often dies burrowed, and the loot dropped inside the sand where nobody
            // could reach it. If the head is underground, drop it on the surface above instead.
            if (solidAt(hp())) at.setY(surfaceAbove(hp()) + 0.2);
            else at.setY(groundY(at.getX(), at.getZ(), at.getY() + 3) + 1);
            String spot = "at:" + world.getName() + "," + at.getX() + "," + at.getY() + "," + at.getZ();
            int orbs = 0;
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                console("givemythicbag " + (int) dcfg("rewards.mythic-bags", 3) + " " + p.getName() + " " + spot, p, at);
                if (random.nextDouble() < dcfg("rewards.cross-chance", 0.5)) // a piece of the Ankh Shield
                    console("giveaccessory cross 1 " + p.getName() + " " + spot, p, at);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, 8 + random.nextInt(7)));
                if (random.nextDouble() < dcfg("rewards.ankh-chance", 0.0001)) {
                    console("giveaccessory ankh 1 " + p.getName() + " " + spot, p, at);
                    Bukkit.broadcastMessage(tint() + "" + ChatColor.BOLD + p.getName() + " found an Ankh Shield in " + name() + "'s remains!");
                }
                orbs += (int) dcfg("rewards.xp", 600);
                p.sendMessage(tint() + "Your loot dropped where " + name() + " fell. Only you can pick it up.");
            }
            for (int left = orbs; left > 0; ) {
                int amount = Math.min(left, 100);
                left -= amount;
                world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(amount);
            }
        }
    }

    // ---------- Dune Devourer events ----------

    /** Hitting any segment damages the worm (body hits count a bit less than the head). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDuneDamage(EntityDamageEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        boolean seg = tags.contains(DUNE_SEG_TAG), head = tags.contains(DUNE_TAG);
        if (!seg && !head) return;
        if (dune == null || dune.dying || dune.intro > 0 || dune.transition > 0) { event.setCancelled(true); return; }
        EntityDamageEvent.DamageCause c = event.getCause();
        boolean allowed = c == EntityDamageEvent.DamageCause.ENTITY_ATTACK || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || c == EntityDamageEvent.DamageCause.PROJECTILE || c == EntityDamageEvent.DamageCause.MAGIC
                || c == EntityDamageEvent.DamageCause.THORNS || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (!allowed) { event.setCancelled(true); return; }
        if (seg) {
            event.setCancelled(true);
            if (!(event instanceof EntityDamageByEntityEvent by)) return;
            Entity d = by.getDamager();
            Entity attacker = d instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : d;
            // BUG FIX: Iron Golems attack slimes on sight; only players (and their arrows) count
            if (!(attacker instanceof Player)) return;
            dune.head().hitbox.damage(event.getDamage() * dcfg("body-hit-multiplier", 1.2), attacker);
            for (Seg sg : dune.segs) { // flash the segment you hit, with a thud, so you know it landed
                if (!sg.hitbox.equals(event.getEntity())) continue;
                sg.display.setGlowColorOverride(dune.frost ? Color.AQUA : Color.ORANGE);
                sg.display.setGlowing(true);
                Bukkit.getScheduler().runTaskLater(this, () -> { if (sg.display.isValid()) sg.display.setGlowing(false); }, 4L);
                sg.display.getWorld().playSound(sg.display.getLocation(), org.bukkit.Sound.ENTITY_TURTLE_HURT, 1.2f, 0.6f);
                break;
            }
        } else if (!event.isCancelled()) {
            if (!byPlayer(event)) { event.setCancelled(true); return; } // the head used to take hits from golems, creepers, skeletons
            event.setDamage(event.getDamage() * (1 - dcfg("defense", 0.35)) * dune.healthScale()); // 35% defense; scaled so it takes its full health to kill
            ItemDisplay headModel = dune.head().display; // the model hit NOW (the worm may be gone or different in 3 ticks)
            headModel.setGlowColorOverride(dune.frost ? Color.AQUA : Color.ORANGE);
            headModel.setGlowing(true);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (headModel.isValid()) headModel.setGlowing(false); }, 3L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuneFight(EntityDamageByEntityEvent event) {
        if (dune == null) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player s ? s : null;
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (attacker != null && (tags.contains(DUNE_TAG) || tags.contains(VULTURE_TAG))) dune.join(attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDuneDeath(EntityDeathEvent event) {
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (tags.contains(VULTURE_TAG)) {
            event.getDrops().clear();
            boolean wild = tags.contains(WILD_VULTURE_TAG);
            event.setDroppedExp(wild ? 5 : 0);
            if (wild && event.getEntity().getKiller() != null && random.nextDouble() < getConfig().getDouble("vultures.talon-chance", 0.5)) {
                event.getDrops().add(talon());
            }
            return;
        }
        if (!tags.contains(DUNE_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (dune != null) dune.die();
    }

    @EventHandler
    public void onDunePlayerDeath(PlayerDeathEvent event) {
        if (dune != null) dune.fighterDied(event.getEntity());
    }


    // ===================== VULTURES, TALONS, AND THE DUNE IDOL =====================
    //
    // Wild Vultures circle over deserts and badlands (open sky, day or night; more during a
    // Sandstorm) and drop Talons. Talon + Netherite Ingot + Diamond = the Dune Idol, which
    // summons the Dune Devourer when used in a desert, badlands, or snowy area.
    // The Vultures the boss summons are the same mob but drop nothing (no farming the fight).

    static final String WILD_VULTURE_TAG = "faultline_vulture_wild";
    private NamespacedKey talonKey, idolKey, idolRecipeKey;

    ItemStack talon() {
        ItemStack item = new ItemStack(Material.FLINT);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "talon"));
        meta.setDisplayName(ChatColor.GOLD + "Talon");
        meta.setLore(List.of(ChatColor.GRAY + "A vulture's hooked claw.", ChatColor.DARK_GRAY + "Crafting: Talon + Netherite Ingot + Diamond",
                ChatColor.DARK_GRAY + "= Dune Idol"));
        meta.getPersistentDataContainer().set(talonKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack duneIdol() {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "dune_idol"));
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Dune Idol");
        meta.setLore(List.of(ChatColor.GRAY + "In a desert or badlands: summons the " + ChatColor.GOLD + "Dune Devourer",
                ChatColor.GRAY + "In a snowy area: summons the " + ChatColor.AQUA + "Frostmaw"));
        meta.getPersistentDataContainer().set(idolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void registerIdolRecipe() {
        ShapelessRecipe r = new ShapelessRecipe(idolRecipeKey, duneIdol());
        r.addIngredient(new RecipeChoice.ExactChoice(talon())); // a real Talon, not just any Flint
        r.addIngredient(Material.NETHERITE_INGOT);
        r.addIngredient(Material.DIAMOND);
        FaultlineBosses.addRecipeSafely(r);
    }

    @EventHandler
    public void onIdolRecipeJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(idolRecipeKey);
    }

    @EventHandler
    public void onIdolUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tagged(event.getItem(), idolKey)) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (dune != null) { p.sendMessage(ChatColor.RED + "A great worm is already here."); return; }
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL || p.getWorld().getDifficulty() == Difficulty.PEACEFUL
                || !(desertAt(p.getLocation()) || inTheSnow(p.getLocation()))) {
            p.sendMessage(ChatColor.RED + "The idol only stirs in a desert, badlands, or snowy area.");
            return;
        }
        summonDune(p, !desertAt(p.getLocation())); // desert: the Dune Devourer; snow: the Frostmaw
        if (dune == null) return;
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    /** Spawns a Vulture. Wild ones despawn normally and drop Talons; the boss's drop nothing. */
    org.bukkit.entity.Phantom spawnVulture(Location at, boolean wild) {
        return spawnVulture(at, wild, "Vulture");
    }

    org.bukkit.entity.Phantom spawnVulture(Location at, boolean wild, String name) {
        if (!(at.getWorld().spawnEntity(at, EntityType.PHANTOM, false) instanceof org.bukkit.entity.Phantom v)) return null;
        v.setShouldBurnInDay(false);
        v.addScoreboardTag(VULTURE_TAG);
        if (wild) v.addScoreboardTag(WILD_VULTURE_TAG);
        v.setPersistent(false);
        v.setRemoveWhenFarAway(wild);
        v.setCustomName(("Snow Owl".equals(name) ? ChatColor.AQUA : ChatColor.GOLD) + name);
        v.setCustomNameVisible(false);
        AttributeInstance hp = v.getAttribute(Attribute.MAX_HEALTH);
        double health = wild ? getConfig().getDouble("vultures.health", 14) : dcfg("vulture-health", 14); // the worm's own use dune.vulture-health
        if (hp != null) { hp.setBaseValue(health); v.setHealth(Math.min(health, hp.getValue())); }
        v.setAnchorLocation(at.clone()); // circle over where they appear (not wherever the game decides)
        // they used to be plain phantoms with a hidden name: now a real bird, with flapping wings
        boolean owl = "Snow Owl".equals(name);
        hideForGood(v);
        float s = (float) getConfig().getDouble("vultures.model-scale", 1.3);
        ItemDisplay look = spawnDisplay(v.getLocation(), owl ? "snow_owl_up" : "vulture_up", s, 2, Display.Billboard.FIXED);
        look.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        look.setViewRange(6f);
        birds.put(v.getUniqueId(), new Bird(v, look, owl ? "snow_owl" : "vulture", s));
        proxy(v, look, EntityType.PHANTOM, 1.0); // Bedrock players (who can't see models) see a plain phantom
        return v;
    }

    private static String check(boolean ok, String what) {
        return (ok ? ChatColor.GREEN + " \u2714 " : ChatColor.RED + " \u2716 ") + ChatColor.GRAY + what;
    }

    /** A Vulture or Snow Owl's model, riding along with its (invisible) phantom. */
    static final class Bird {
        final org.bukkit.entity.Phantom body; final ItemDisplay look; final String model; final float scale; boolean up;
        Bird(org.bukkit.entity.Phantom body, ItemDisplay look, String model, float scale) { this.body = body; this.look = look; this.model = model; this.scale = scale; }
    }
    private final Map<UUID, Bird> birds = new HashMap<>();
    private int birdTicks;

    /** Every tick: each bird's model follows its phantom, faces where it flies, and flaps. */
    private void birdTick() {
        birdTicks++;
        for (Iterator<Bird> it = birds.values().iterator(); it.hasNext(); ) {
            Bird b = it.next();
            if (!b.body.isValid() || b.body.isDead()) { if (b.look.isValid()) b.look.remove(); it.remove(); continue; }
            if (!b.look.isValid()) { b.body.remove(); it.remove(); continue; }
            Location at = b.body.getLocation();
            float yaw = at.getYaw(), pitch = at.getPitch();
            at.setYaw(0); at.setPitch(0);
            b.look.teleport(at.add(0, 0.25, 0));
            b.look.setInterpolationDelay(0);
            b.look.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).mul(facing(0)), // BUG FIX: they flew tail-first
                    new Vector3f(b.scale, b.scale, b.scale), new Quaternionf()));
            if (birdTicks % 5 == 0) { b.up = !b.up; b.look.setItemStack(modelItem(b.model + (b.up ? "_up" : "_down"))); }
        }
    }

    /** Every 20 seconds: a chance for Vultures to show up over each player out in a desert. */
    private void vultureSpawnTick() {
        if (!getConfig().getBoolean("vultures.enabled", true)) return;
        boolean sandstorm = "SANDSTORM".equals(activeEvent());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!survival(p) || p.getWorld().getEnvironment() != World.Environment.NORMAL || p.getWorld().getDifficulty() == Difficulty.PEACEFUL) continue;
            if (!desertAt(p.getLocation()) || p.getLocation().getBlock().getLightFromSky() < 12) continue; // out in the open desert
            long nearby = p.getNearbyEntities(48, 32, 48).stream().filter(e -> e.getScoreboardTags().contains(WILD_VULTURE_TAG)).count();
            if (nearby >= getConfig().getInt("vultures.max-nearby", 3)) continue;
            double chance = getConfig().getDouble(sandstorm ? "vultures.sandstorm-chance" : "vultures.chance", sandstorm ? 0.5 : 0.25);
            if (random.nextDouble() >= chance) continue;
            int n = 1 + random.nextInt(2);
            for (int i = 0; i < n; i++) {
                Location at = p.getLocation().add(random.nextGaussian() * 12, 16 + random.nextInt(6), random.nextGaussian() * 12);
                if (!at.getBlock().getType().isAir()) continue;
                org.bukkit.entity.Phantom v = spawnVulture(at, true);
                if (v != null) v.setTarget(p); // BUG FIX: Paper's phantoms only attack players who haven't slept; a set target always swoops
            }
        }
    }


    private final Map<String, Long> lastErrorLog = new HashMap<>();
    private final Map<String, Long> bossPartLog = new HashMap<>();

    /** Logs one boss part's error (at most every 30 seconds per part), so the console says exactly what failed. */
    private void logBossPart(String boss, String what, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - bossPartLog.getOrDefault(boss + what, 0L) < 30000) return;
        bossPartLog.put(boss + what, now);
        getLogger().log(java.util.logging.Level.SEVERE, "[" + boss + "] error in " + what + " (skipped, the fight keeps going; please report this):", e);
    }

    /** Runs one part of a boss's update; an error is logged and the rest of the boss keeps going. */
    private void bossPart(String boss, String what, Runnable r) {
        try { r.run(); } catch (RuntimeException e) { logBossPart(boss, what, e); }
    }

    private void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog.getOrDefault(what, 0L) > 30000) {
                lastErrorLog.put(what, now);
                getLogger().log(java.util.logging.Level.SEVERE, "[" + what + "] error while updating (it keeps running; please report this):", t);
            }
        }
    }

    // =====================================================================================
    // ===================== DON LORENZO =====================
    // =====================================================================================
    //
    // Summoned with a Captain's Armband (1% from wild animals), only in the daytime, only on a flat,
    // open spot with no buildings or villagers. Right-click him: "Are you sure you want to fight?"
    //   PHASE 1  no music. 500 health, no defense, 2 moves (all ball: Rapid Fire + a big kick). Can't drop below 50...
    //   CUTSCENE at 50 health: 22 seconds, the Overtime music starts at 0:00.
    //   PHASE 2  0:22 the beat drops: a 32x32 box slams up. 1,500 health (scales with fighters), 5% defense,
    //            6 moves, very fast. A pink orb rises every 10s: hit it into him for big damage.
    //   2:43     "I guess you couldn't kill me in time." He talks about you.
    //   3:05     spears drop from the ceiling: everyone in the box dies (totems don't save you).
    // Everyone dies at any point: he vanishes. Box blocks are saved to a file and restored, even after a crash.

    static final String DON_TAG = "faultline_don";
    static final String DON_ORB_TAG = "faultline_don_orb";
    private Don don;
    private NamespacedKey armbandKey, lorenzoBallKey;

    private double ncfg(String key, double def) {
        return getConfig().getDouble("don." + key, def);
    }

    // ---------- the rig: 6 animated pieces using his skin ----------
    // Joints in skin pixels relative to his feet. He faces +Z, so +X is HIS left.
    private static final String[] PART_MODEL = {"don_leg_r", "don_leg_l", "don_body", "don_arm_r", "don_arm_l", "don_head"};
    private static final float[][] JOINT = {{-2, 12, 0}, {2, 12, 0}, {0, 12, 0}, {-5.5f, 24, 0}, {5.5f, 24, 0}, {0, 24, 0}}; // slim (3-pixel) arms
    static final int LEG_R = 0, LEG_L = 1, BODY = 2, ARM_R = 3, ARM_L = 4, HEAD = 5;

    /** A pose: rotation (degrees x/y/z) per part, plus how far the whole rig sinks (for kneeling). */
    static final class Pose {
        final float[][] r = new float[6][3];
        float drop;
        Pose set(int part, float x, float y, float z) { r[part][0] = x; r[part][1] = y; r[part][2] = z; return this; }
        Pose drop(float d) { drop = d; return this; }
        Pose copy() { Pose p = new Pose(); for (int i = 0; i < 6; i++) p.r[i] = r[i].clone(); p.drop = drop; return p; }
        Pose add(int part, float x, float y, float z) { r[part][0] += x; r[part][1] += y; r[part][2] += z; return this; }
        static Pose lerp(Pose a, Pose b, float t) {
            t = Math.max(0, Math.min(1, t));
            Pose p = new Pose();
            for (int i = 0; i < 6; i++) for (int k = 0; k < 3; k++) p.r[i][k] = a.r[i][k] + (b.r[i][k] - a.r[i][k]) * t;
            p.drop = a.drop + (b.drop - a.drop) * t;
            return p;
        }
    }

    // The pose library (arms/legs: negative X = forward/up; right arm out to the side = negative Z).
    static Pose standPose() { return new Pose(); }
    /** Waiting for you: ball on his left hip, weight on one leg, head cocked. tossT < 20 = tossing the ball up. */
    static Pose hipHoldPose(int t, int tossT) {
        float sway = (float) Math.sin(t * 0.05) * 2.5f;
        Pose p = new Pose().set(ARM_L, -12, 0, 24).set(ARM_R, 8, 0, -20)          // left arm holds the ball, right hangs loose
                .set(BODY, 1, 0, 3 + sway).set(HEAD, 6, 0, -7)                   // hip cocked, head tilted
                .set(LEG_R, 0, 0, -3).set(LEG_L, -4, 0, 6);                       // weight on one leg
        if (tossT < 20) { // flicks the ball up off his hip and watches it
            float f = (float) Math.sin(tossT / 20.0 * Math.PI);
            p.add(ARM_L, -70 * (tossT < 8 ? 1 - tossT / 8f : 0), 0, -8).add(HEAD, -28 * f, 0, 7 * f);
        }
        return p;
    }
    static Pose idlePose(int t) {
        float b = (float) Math.sin(t * 0.12) * 3;
        return new Pose().set(ARM_R, -12 + b, 0, -6).set(ARM_L, b, 0, 5).set(BODY, 2, 0, 0).set(HEAD, -b, 0, 0);
    }
    static Pose walkPose(int t, float amount) {
        float s = (float) Math.sin(t * 0.55) * 32 * amount;
        return new Pose().set(LEG_R, s, 0, 0).set(LEG_L, -s, 0, 0).set(ARM_R, -s * 0.8f - 10, 0, -4).set(ARM_L, s * 0.8f, 0, 4).set(BODY, 4 * amount, 0, 0);
    }
    static Pose slashWindupPose() { return new Pose().set(ARM_R, -160, 0, -20).set(ARM_L, -20, 0, 20).set(BODY, 6, 25, 0).set(LEG_R, 20, 0, 0).set(LEG_L, -15, 0, 0); }
    static Pose slashPose() { return new Pose().set(ARM_R, -35, 0, 15).set(ARM_L, 20, 0, 10).set(BODY, 14, -30, 0).set(LEG_R, -25, 0, 0).set(LEG_L, 20, 0, 0); }
    static Pose kickWindupPose() { return new Pose().set(LEG_R, 55, 0, 0).set(ARM_L, -40, 0, 20).set(ARM_R, 25, 0, -15).set(BODY, -6, 0, 0); }
    static Pose kickPose() { return new Pose().set(LEG_R, -80, 0, 0).set(ARM_L, 30, 0, 25).set(ARM_R, -30, 0, -25).set(BODY, -12, 0, 0); }
    static Pose kneelPose(int t) {
        float breathe = (float) Math.sin(t * 0.25) * 3;
        return new Pose().drop(-0.5f).set(LEG_R, -85, 0, 0).set(LEG_L, 75, 0, 0).set(BODY, 22 + breathe, 0, 0)
                .set(HEAD, 25, 0, 0).set(ARM_R, -20, 0, -8).set(ARM_L, -40, 0, 10);
    }
    static Pose handOutPose() { return new Pose().set(ARM_R, -80, 10, 0).set(ARM_L, 5, 0, 6).set(HEAD, 0, 0, 14).set(BODY, 0, 8, 0); }
    static Pose spreadPose() { return new Pose().set(ARM_R, -10, 0, -75).set(ARM_L, -10, 0, 75).set(HEAD, -15, 0, 0).set(BODY, -8, 0, 0); }
    static Pose powerPose() {
        return new Pose().set(LEG_R, 0, 0, -12).set(LEG_L, 0, 0, 12).set(ARM_R, 20, 0, -28).set(ARM_L, 20, 0, 28).set(BODY, 12, 0, 0).set(HEAD, 12, 0, 0);
    }
    static Pose guardPose() { return new Pose().set(ARM_R, -95, 35, 0).set(ARM_L, -70, -25, 0).set(BODY, 10, 0, 0).set(LEG_R, 15, 0, -8).set(LEG_L, -10, 0, 8); }
    static Pose staggerPose() { return new Pose().set(BODY, -22, 0, 0).set(HEAD, -25, 0, 0).set(ARM_R, 35, 0, -30).set(ARM_L, 35, 0, 30).set(LEG_R, -15, 0, 0); }
    static Pose foldArmsPose() { return new Pose().set(ARM_R, -75, -45, 0).set(ARM_L, -75, 45, 0).set(HEAD, -8, 0, 0); }
    static Pose pointPose() { return new Pose().set(ARM_R, -92, -8, 0).set(ARM_L, 15, 0, 12).set(BODY, 4, -18, 0).set(HEAD, 0, 10, 0).set(LEG_R, -12, 0, 0).set(LEG_L, 10, 0, 0); }
    // ---- fight animation: easing, keyframes, mirroring, and the new poses ----
    static float ease(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }       // smooth in and out
    static float snap(float x) { x = Math.max(0, Math.min(1, x)); float i = 1 - x; return 1 - i * i * i; } // fast start: strikes
    /** Keyframes (frame numbers ascending). Gaps of 4 ticks or less snap like a strike; longer ones ease. */
    static Pose anim(float t, int[] f, Pose[] p) {
        if (t <= f[0]) return p[0].copy();
        for (int i = 0; i < f.length - 1; i++) {
            if (t < f[i + 1]) {
                float x = (t - f[i]) / (float) (f[i + 1] - f[i]);
                return Pose.lerp(p[i], p[i + 1], f[i + 1] - f[i] <= 4 ? snap(x) : ease(x));
            }
        }
        return p[p.length - 1].copy();
    }
    /** The same pose with the other leg/arm (a left-footed kick from a right-footed one). */
    static Pose mirror(Pose a) {
        Pose m = new Pose();
        int[][] swap = {{LEG_R, LEG_L}, {LEG_L, LEG_R}, {ARM_R, ARM_L}, {ARM_L, ARM_R}, {BODY, BODY}, {HEAD, HEAD}};
        for (int[] sw : swap) m.set(sw[0], a.r[sw[1]][0], -a.r[sw[1]][1], -a.r[sw[1]][2]);
        m.drop = a.drop;
        return m;
    }
    /** A sprint: arms pumping, leaning in, knees driving, a running bob. */
    static Pose runPose(int t, float a) {
        float s = (float) Math.sin(t * 0.7) * 48 * a;
        return new Pose().set(LEG_R, s - 8 * a, 0, 0).set(LEG_L, -s - 8 * a, 0, 0)
                .set(ARM_R, -s * 1.1f - 18, 0, -6).set(ARM_L, s * 1.1f - 18, 0, 6)
                .set(BODY, 20 * a, s * 0.12f, 0).set(HEAD, -14 * a, 0, 0)
                .drop(-(float) Math.abs(Math.sin(t * 0.7)) * 0.07f * a);
    }
    /** Anticipation: weight down, leaning in, arms back. */
    static Pose crouchPose() { return new Pose().drop(-0.22f).set(BODY, 26, 0, 0).set(HEAD, -18, 0, 0).set(LEG_R, -24, 0, -4).set(LEG_L, -10, 0, 6).set(ARM_R, 38, 0, -14).set(ARM_L, 30, 0, 14); }
    /** A deep wind-up: kicking leg far back, body twisted away, front arm up for balance, eyes on you. */
    static Pose powerWindupPose() { return new Pose().set(LEG_R, 78, 0, 0).set(LEG_L, -12, 0, 3).set(BODY, -4, 34, 0).set(ARM_L, -100, 0, 22).set(ARM_R, 42, 0, -30).set(HEAD, 6, -28, 0); }
    /** The follow-through: kicking leg high, leaning back, arms flung out. */
    static Pose followThroughPose() { return new Pose().drop(0.05f).set(LEG_R, -118, 0, 0).set(LEG_L, 14, 0, 0).set(BODY, -20, -28, 0).set(ARM_L, 48, 0, 42).set(ARM_R, -62, 0, -46).set(HEAD, 10, 20, 0); }
    /** Landing from a leap: a deep squat, arms out for balance. */
    static Pose landPose() { return new Pose().drop(-0.38f).set(LEG_R, -34, 0, -6).set(LEG_L, 24, 0, 8).set(BODY, 32, 0, 0).set(ARM_R, 30, 0, -48).set(ARM_L, 30, 0, 48).set(HEAD, 8, 0, 0); }
    /** Mid-air overhead spike: leg over his head, arched back. */
    static Pose spikePose() { return new Pose().set(LEG_R, -150, 0, 0).set(LEG_L, 32, 0, 0).set(BODY, -32, 0, 0).set(ARM_R, 62, 0, -62).set(ARM_L, 62, 0, 62).set(HEAD, -20, 0, 0); }
    /** "Feeding time, OK?": chest out, head back, arms flung behind. */
    static Pose roarPose(int t) { float q = (float) Math.sin(t * 0.9) * 4; return new Pose().set(BODY, -18, 0, 0).set(HEAD, -32 + q, 0, 0).set(ARM_R, 42, 0, -72).set(ARM_L, 42, 0, 72).set(LEG_R, 0, 0, -14).set(LEG_L, 0, 0, 14); }
    /** Rocked by a big hit (an orb): arched back, arms flung, lifted onto his heels. */
    static Pose recoilPose() { return new Pose().drop(0.1f).set(BODY, -36, 0, 0).set(HEAD, -36, 0, 0).set(ARM_R, 72, 0, -55).set(ARM_L, 72, 0, 55).set(LEG_R, -32, 0, 0).set(LEG_L, 16, 0, 0); }

    static Pose jumpPose() { return new Pose().set(LEG_R, -40, 0, 0).set(LEG_L, 30, 0, 0).set(ARM_R, -170, 0, -10).set(ARM_L, -150, 0, 10); }

    private Quaternionf euler(float[] r) {
        return new Quaternionf().rotateY((float) Math.toRadians(r[1])).rotateX((float) Math.toRadians(r[0])).rotateZ((float) Math.toRadians(r[2]));
    }

    /** Places all 6 pieces (+ his sword) for a pose. Arms and head follow the body's lean and twist. */
    void renderRig(ItemDisplay[] parts, ItemDisplay sword, Location root, float yaw, float scale, Pose pose) {
        Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
        Quaternionf qBody = euler(pose.r[BODY]);
        Quaternionf flip = facing(0);
        Vector3f hips = new Vector3f(0, 12, 0);
        float px = scale / 16f;
        for (int i = 0; i < 6; i++) {
            ItemDisplay d = parts[i];
            if (d == null || !d.isValid()) continue;
            Vector3f joint = new Vector3f(JOINT[i][0], JOINT[i][1], JOINT[i][2]);
            Quaternionf rot;
            if (i == ARM_R || i == ARM_L || i == HEAD) {
                joint.sub(hips); qBody.transform(joint); joint.add(hips);
                rot = new Quaternionf(qYaw).mul(qBody).mul(euler(pose.r[i]));
            } else if (i == BODY) {
                rot = new Quaternionf(qYaw).mul(qBody);
            } else {
                rot = new Quaternionf(qYaw).mul(euler(pose.r[i]));
            }
            joint.mul(px); qYaw.transform(joint);
            Location at = root.clone().add(joint.x, joint.y + pose.drop, joint.z);
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), rot.mul(flip), new Vector3f(scale, scale, scale), new Quaternionf()));
        }
        if (sword != null && sword.isValid()) { // held in the right hand, the way a player holds an item
            Vector3f shoulder = new Vector3f(JOINT[ARM_R][0], JOINT[ARM_R][1], JOINT[ARM_R][2]);
            shoulder.sub(hips); qBody.transform(shoulder); shoulder.add(hips);
            Quaternionf qArm = new Quaternionf(qBody).mul(euler(pose.r[ARM_R]));
            Vector3f hand = qArm.transform(new Vector3f(0, -10, 0)).add(shoulder).mul(px);
            qYaw.transform(hand);
            Location at = root.clone().add(hand.x, hand.y + pose.drop, hand.z);
            at.setYaw(0); at.setPitch(0);
            sword.teleport(at);
            sword.setInterpolationDelay(0);
            Quaternionf rot = new Quaternionf(qYaw).mul(qArm).rotateX((float) Math.toRadians(ncfg("sword-pitch", -90))).rotateY((float) Math.PI);
            float ss = scale * (float) ncfg("sword-scale", 0.85);
            sword.setTransformation(new Transformation(new Vector3f(0, 0, 0), rot, new Vector3f(ss, ss, ss), new Quaternionf()));
        }
    }

    ItemDisplay[] spawnRig(Location at, float scale) {
        ItemDisplay[] parts = new ItemDisplay[6];
        for (int i = 0; i < 6; i++) {
            parts[i] = spawnDisplay(at, PART_MODEL[i], scale, 2, Display.Billboard.FIXED);
            parts[i].setViewRange(6f);
        }
        return parts;
    }

    ItemDisplay spawnSword(Location at) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class);
        d.setItemStack(new ItemStack(Material.NETHERITE_SWORD));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
        d.setTeleportDuration(2);
        d.setInterpolationDuration(2);
        d.setViewRange(6f);
        d.setBrightness(new Display.Brightness(13, 13));
        d.setPersistent(false);
        d.addScoreboardTag(DISPLAY_TAG);
        return d;
    }

    // ---------- the Captain's Armband (summons him) ----------
    ItemStack captainsArmband() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "captains_armband"));
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Captain's Armband");
        meta.setLore(List.of(ChatColor.GRAY + "Use it in the daytime, on a flat,", ChatColor.GRAY + "open area with no buildings",
                ChatColor.GRAY + "or villagers nearby, to summon", ChatColor.LIGHT_PURPLE + "Don Lorenzo" + ChatColor.GRAY + "."));
        meta.getPersistentDataContainer().set(armbandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** 1% from WILD animals only (not bred, spawner, or egg animals: no farms). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnimalDrop(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e instanceof org.bukkit.entity.Animals) || e.getKiller() == null || e.fromMobSpawner()) return;
        CreatureSpawnEvent.SpawnReason r = e.getEntitySpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.BREEDING || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || r == CreatureSpawnEvent.SpawnReason.EGG
                || r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG || r == CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        if (random.nextDouble() < ncfg("armband-chance", 0.01)) event.getDrops().add(captainsArmband());
    }

    /** Why this spot can't host the fight, or null if it can. */
    String flatProblem(Location center) {
        World w = center.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return "He only fights in the Overworld.";
        int r = 18, cx = center.getBlockX(), cz = center.getBlockZ();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = cx - r; x <= cx + r; x += 2) {
            for (int z = cz - r; z <= cz + r; z += 2) {
                int top = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (Math.abs(top - center.getBlockY()) > 12) continue; // under a roof/cave: judged by the scan below
                min = Math.min(min, top); max = Math.max(max, top);
                Material m = w.getBlockAt(x, top, z).getType();
                if (m == Material.WATER || m == Material.LAVA) return "There's too much water or lava here.";
            }
        }
        if (min != Integer.MAX_VALUE && max - min > (int) ncfg("flatness", 2)) return "The ground isn't flat enough (he needs about 36x36 blocks of flat ground).";
        int floor = center.getBlockY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int y = floor - 1; y <= floor + 9; y++) {
                    String n = w.getBlockAt(x, y, z).getType().name();
                    if (n.endsWith("_BED") || n.endsWith("_DOOR") || n.contains("CHEST") || n.equals("BARREL") || n.equals("CRAFTING_TABLE")
                            || n.contains("FURNACE") || n.endsWith("_PLANKS") || n.endsWith("_STAIRS") || n.endsWith("_SLAB") || n.endsWith("_FENCE")
                            || n.contains("GLASS") || n.contains("TORCH") || n.contains("LANTERN") || n.contains("BRICK") || n.equals("COBBLESTONE")
                            || n.endsWith("_WOOL") || n.endsWith("_CARPET") || n.equals("BOOKSHELF") || n.contains("ANVIL") || n.endsWith("_SIGN"))
                        return "There are buildings too close (he needs open ground with nothing built nearby).";
                }
                if (Math.abs(x - cx) <= 3 && Math.abs(z - cz) <= 3) {
                    for (int y = floor; y <= floor + 11; y++) {
                        if (w.getBlockAt(x, y, z).getType().isSolid()) return "There isn't enough open space above you.";
                    }
                }
            }
        }
        if (!w.getNearbyEntities(center, 48, 32, 48, e -> e instanceof org.bukkit.entity.Villager).isEmpty()) return "A villager is too close.";
        return null;
    }

    @EventHandler
    public void onArmband(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tagged(event.getItem(), armbandKey)) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (don != null) { p.sendMessage(ChatColor.RED + "Don Lorenzo is already here."); return; }
        if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL) { p.sendMessage(ChatColor.RED + "He won't fight on Peaceful."); return; }
        if (isNight(p.getWorld())) { p.sendMessage(ChatColor.RED + "Don Lorenzo only fights in the daytime."); return; }
        String problem = flatProblem(p.getLocation());
        if (problem != null) { p.sendMessage(ChatColor.RED + problem + ChatColor.GRAY + " (The armband wasn't used.)"); return; }
        summonDon(p.getLocation(), p);
        if (don != null && p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    void summonDon(Location at, Player by) {
        Location spot = at.clone();
        spot.setX(spot.getBlockX() + 0.5); spot.setZ(spot.getBlockZ() + 0.5); spot.setY(spot.getBlockY());
        don = new Don(spot, by);
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Don Lorenzo " + ChatColor.GRAY + "has arrived near " + by.getName() + "...");
    }

    // ---------- Lorenzo's Ball (a kicked weapon that comes back) ----------
    ItemStack lorenzosBall() {
        ItemStack item = new ItemStack(Material.FIREWORK_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "lorenzos_ball"));
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Lorenzo's Ball");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click to kick it. It comes back to you.",
                ChatColor.GREEN + "3x an arrow's damage + Frostbite",
                ChatColor.DARK_GRAY + "Frostbite: frozen, Slowness II, 1 damage/s for 4s"));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(lorenzoBallKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private final class KickedBall {
        final Player owner;
        final ItemDisplay display;
        Vector pos, vel;
        boolean returning;
        int life;
        double traveled;
        KickedBall(Player owner, Vector pos, Vector vel) {
            this.owner = owner; this.pos = pos; this.vel = vel;
            this.display = spawnDisplay(pos.toLocation(owner.getWorld()), "don_ball", 0.55f, 1, Display.Billboard.FIXED);
        }
    }
    private final List<KickedBall> kickedBalls = new ArrayList<>();

    @EventHandler
    public void onBallKick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tagged(event.getItem(), lorenzoBallKey)) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (p.hasCooldown(Material.FIREWORK_STAR)) return;
        for (KickedBall b : kickedBalls) if (b.owner.equals(p)) return; // still out there
        p.setCooldown(Material.FIREWORK_STAR, (int) ncfg("ball.cooldown-ticks", 40));
        Vector dir = p.getEyeLocation().getDirection().normalize();
        kickedBalls.add(new KickedBall(p, p.getEyeLocation().toVector().add(dir.clone().multiply(0.8)).subtract(new Vector(0, 0.3, 0)),
                dir.multiply(ncfg("ball.speed", 1.3))));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.8f);
    }

    private void ballTick() {
        for (KickedBall b : new ArrayList<>(kickedBalls)) {
            b.life++;
            World w = b.owner.getWorld();
            boolean gone = !b.owner.isOnline() || !b.display.isValid() || b.life > 140 || !b.display.getWorld().equals(w);
            if (!gone && !b.returning) {
                double len = b.vel.length();
                org.bukkit.util.RayTraceResult hit = w.rayTrace(b.pos.toLocation(w), b.vel, len, org.bukkit.FluidCollisionMode.NEVER, true, 0.35,
                        e -> e instanceof LivingEntity le && !le.equals(b.owner) && !le.isDead() && !(e instanceof org.bukkit.entity.ArmorStand));
                if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
                    double dmg = ncfg("ball.damage", 18) * (target instanceof Player ? ncfg("ball.player-damage-multiplier", 1.0) : 1);
                    target.damage(dmg, b.owner);
                    frostbite(target, b.owner);
                    w.playSound(target.getLocation(), org.bukkit.Sound.BLOCK_GLASS_BREAK, 1f, 1.4f);
                    w.spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
                    b.returning = true;
                } else if (hit != null && hit.getHitBlock() != null) {
                    w.playSound(b.pos.toLocation(w), org.bukkit.Sound.BLOCK_WOOL_HIT, 1f, 1.2f);
                    b.returning = true;
                } else {
                    b.pos.add(b.vel);
                    b.traveled += len;
                    if (b.traveled > ncfg("ball.range", 24)) b.returning = true;
                }
            }
            if (!gone && b.returning) { // curves back to you (it passes through blocks on the way home)
                Vector home = b.owner.getEyeLocation().toVector().subtract(new Vector(0, 0.4, 0));
                Vector to = home.clone().subtract(b.pos);
                if (to.length() < 1.3) gone = true;
                else b.pos.add(to.normalize().multiply(1.4));
            }
            if (gone) { if (b.display.isValid()) b.display.remove(); kickedBalls.remove(b); continue; }
            Location l = b.pos.toLocation(w);
            b.display.teleport(l);
            b.display.setInterpolationDelay(0);
            b.display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(b.life * 0.5f, b.life * 0.3f, 0),
                    new Vector3f(0.55f, 0.55f, 0.55f), new Quaternionf()));
            if (b.life % 2 == 0) w.spawnParticle(Particle.SNOWFLAKE, l, 1, 0, 0, 0, 0);
        }
    }

    /** Frostbite: frozen, Slowness II, and 1 damage a second for 4 seconds. */
    void frostbite(LivingEntity target, Player source) {
        target.setFreezeTicks(Math.min(target.getMaxFreezeTicks() + 60, target.getFreezeTicks() + 140));
        target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 80, 1));
        for (int i = 1; i <= 4; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (target.isValid() && !target.isDead()) target.damage(ncfg("ball.frostbite-damage", 1), source);
            }, 20L * i);
        }
    }

    // ---------- the fight ----------
    static final int ARRIVE = -1; // his entrance: untouchable, can't be talked to yet
    static final int TALK = 0, PHASE1 = 1, CUTSCENE = 2, PHASE2 = 3, SPEECH = 4, SPEARS = 5, DYING = 6, LEAVING = 7;
    static final int T_FIGHT = 445, T_SPEECH = 3260, T_SPEARS = 3700; // music ticks: 0:22.25, 2:43, 3:05

    private final class DonShot {
        final ItemDisplay display;
        Vector pos, vel;
        final double damage;
        final Guard guard;
        final boolean bounces;
        int life;
        final int maxLife;
        DonShot(Vector pos, Vector vel, double damage, Guard guard, boolean bounces, int maxLife) {
            this.pos = pos; this.vel = vel; this.damage = damage; this.guard = guard; this.bounces = bounces; this.maxLife = maxLife;
            this.display = spawnDisplay(pos.toLocation(don.world), "don_ball", 0.6f, 1, Display.Billboard.FIXED);
        }
    }

    private final class DonOrb {
        final ItemDisplay display;
        final Slime hitbox;
        final Vector base;
        Vector pos;
        int life;
        Player launchedBy; // hit toward him
        DonOrb(Vector base) {
            this.base = base; this.pos = base.clone().add(new Vector(0, -1, 0));
            Location at = pos.toLocation(don.world);
            this.display = spawnDisplay(at, "mf_orb_pink", 1.4f, 1, Display.Billboard.CENTER);
            this.display.setGlowing(true);
            this.display.setGlowColorOverride(Color.fromRGB(255, 110, 200));
            this.hitbox = (Slime) don.world.spawnEntity(at, EntityType.SLIME, false);
            hitbox.setSize(1);
            setupHitbox(hitbox, 1000, DON_ORB_TAG, "Pink Orb");
            AttributeInstance sc = hitbox.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(1.8);
        }
    }

    private final class Don {
        final World world;
        final Location home;           // the middle of the arena
        final int floorY;
        Vector pos;                    // his feet
        float yaw;
        final Zombie hitbox;
        ItemDisplay[] parts;
        ItemDisplay sword;
        final ItemDisplay ball;        // juggled at his feet, and the one he kicks
        int state = TALK, t, ticks, attack = -1, sub, count, recover, stagger, lonely, talkTicks, mt = -1, mtPrev = -1;
        long musicStart = -1; // real time the song started: the fight follows the SONG, not the server's tick count

        /**
         * Where the song is right now, in ticks (20 = 1 second), from the real clock. BUG FIX: counting server
         * ticks drifts behind the song whenever the server lags (fewer than 20 ticks a second), so the box,
         * the speech, and the spears would land late.
         */
        int songTicks() {
            if (musicStart < 0) return mt + 1;
            return (int) ((System.currentTimeMillis() - musicStart) / 50) - (int) ncfg("music-offset-ticks", 0);
        }

        /** True the tick the song passes `mark` (a laggy tick can skip right over an exact tick). */
        boolean crossed(int mark) { return mtPrev < mark && mt >= mark; }
        double hp = 500, maxHp = 500;
        float scale = 1.0f;
        UUID talker;
        String token = Long.toHexString(random.nextLong());
        final Set<UUID> fighters = new HashSet<>(), down = new HashSet<>();
        final Map<UUID, Double> dealt = new HashMap<>();
        final Map<UUID, Integer> orbsHit = new HashMap<>(), hitCooldown = new HashMap<>();
        final List<DonShot> shots = new ArrayList<>();
        final List<DonOrb> orbs = new ArrayList<>();
        final List<ItemDisplay> spears = new ArrayList<>();
        final Set<UUID> listeners = new HashSet<>();
        BossBar bar, timer;
        Vector dashDir, aimAt;
        Pose pose = standPose();
        boolean guarding;
        Cutscene cutscene;
        Box box;

        Don(Location spot, Player by) {
            this.world = spot.getWorld();
            this.home = spot.clone();
            this.floorY = spot.getBlockY();
            this.pos = spot.toVector();
            this.yaw = by.getLocation().getYaw() + 180;
            this.hitbox = world.spawn(spot, Zombie.class, z -> {
                z.setAI(false); z.setSilent(true); z.setGravity(false); z.setAdult(); z.setShouldBurnInDay(false);
                z.setPersistent(false); z.setRemoveWhenFarAway(false); z.setCanPickupItems(false);
                z.getEquipment().clear();
                AttributeInstance max = z.getAttribute(Attribute.MAX_HEALTH);
                if (max != null) max.setBaseValue(1000);
                z.setHealth(1000);
                AttributeInstance kb = z.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                if (kb != null) kb.setBaseValue(1);
                // BUG FIX: on Hard, a hit zombie can call a real, visible zombie in as "reinforcements"
                AttributeInstance reinf = z.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                if (reinf != null) reinf.setBaseValue(0);
                z.addScoreboardTag(DON_TAG);
                z.setCustomName(ChatColor.LIGHT_PURPLE + "Don Lorenzo");
                z.setCustomNameVisible(false);
            });
            hideForGood(hitbox);
            parts = spawnRig(spot, 0.001f); // hidden until he lands (the entrance reveals him)
            sword = null; // ball only: no sword
            state = ARRIVE;
            arriveBy = by.getUniqueId();
            ball = spawnDisplay(spot, "don_ball", 0.45f, 1, Display.Billboard.FIXED);
            proxy(hitbox, null, EntityType.VINDICATOR, 1.0);
            bar = Bukkit.createBossBar(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Don Lorenzo", BarColor.PURPLE, BarStyle.SEGMENTED_10);
        }

        Location loc() { return pos.toLocation(world, yaw, 0); }

        // ---------- his entrance ----------
        int arriveT;
        UUID arriveBy;
        double lift;        // how high above the ground the rig is drawn (he drops in from the sky)
        Location ballAt;    // the ball's position during the entrance (null = normal)
        Location arriveBallSpot;

        Vector fwd() { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }

        /** Where the ball sits on his left hip (plus a toss, when he flicks it up). */
        Location hipBall(Location root, int tossT) {
            Location l = root.clone().add(new Vector(0.36 * scale, 0.82 * scale, 0.12).rotateAroundY(Math.toRadians(-yaw)));
            if (tossT < 20) l.add(0, Math.sin(tossT / 20.0 * Math.PI) * 1.3, 0).add(fwd().multiply(0.15));
            return l;
        }

        /** ~4.5 seconds: a pink spiral, the ball drops in first, then he lands, rises, flicks the ball up and catches it on his hip. */
        void arriveTick() {
            int t = ++arriveT;
            Location c = home.clone();
            Player by = arriveBy != null ? Bukkit.getPlayer(arriveBy) : null;
            if (by != null && by.getWorld().equals(world)) face(by.getLocation().toVector());
            if (arriveBallSpot == null) arriveBallSpot = c.clone().add(fwd().multiply(1.3)); // locked once (he keeps turning to face you)
            Location ballSpot = arriveBallSpot;
            Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.1f);
            // 1) a pink spiral rises out of the ground, humming
            if (t == 1) { world.playSound(c, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.5f); world.playSound(c, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.4f, 0.6f); }
            if (t < 44 && t % 2 == 0) for (int k = 0; k < 3; k++) {
                double r = Math.max(0.3, 1.7 - t * 0.03), ang = t * 0.35 + k * 2.094;
                world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * r, 0.1 + t * 0.07, Math.sin(ang) * r), 1, 0, 0, 0, 0, pink);
            }
            if (t < 40 && t % 6 == 0) world.spawnParticle(Particle.BLOCK, c.clone().add(0, 0.1, 0), 10, 1.4 + t * 0.03, 0.05, 1.4 + t * 0.03, 0, c.clone().subtract(0, 1, 0).getBlock().getBlockData());
            // 2) the ball arrives first: it drops out of the sky and bounces
            if (t < 8) ballAt = ballSpot.clone().add(0, 30, 0); // out of sight
            else if (t < 24) { double f = (t - 8) / 16.0; ballAt = ballSpot.clone().add(0, 0.2 + 16 * (1 - f * f), 0); }
            else if (t < 34) ballAt = ballSpot.clone().add(0, 0.2 + Math.sin((t - 24) / 10.0 * Math.PI) * 1.2, 0);
            else if (t < 40) ballAt = ballSpot.clone().add(0, 0.2 + Math.sin((t - 34) / 6.0 * Math.PI) * 0.3, 0);
            else if (t < 72) ballAt = ballSpot.clone().add(0, 0.2, 0);
            if (t == 24) {
                world.playSound(ballSpot, org.bukkit.Sound.BLOCK_WOOL_BREAK, 1.6f, 0.6f);
                world.spawnParticle(Particle.BLOCK, ballSpot.clone().add(0, 0.1, 0), 20, 0.4, 0.05, 0.4, 0, ballSpot.clone().subtract(0, 1, 0).getBlock().getBlockData());
            }
            // 3) he drops in from the sky: a superhero landing
            if (t < 40) lift = 0;
            else if (t < 48) { double f = (t - 40) / 8.0; lift = 12 * (1 - f * f); pose = jumpPose(); }
            if (t == 48) {
                lift = 0;
                world.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0);
                world.spawnParticle(Particle.BLOCK, c.clone().add(0, 0.1, 0), 60, 1.8, 0.1, 1.8, 0, c.clone().subtract(0, 1, 0).getBlock().getBlockData());
                world.spawnParticle(Particle.DUST, c.clone().add(0, 0.4, 0), 24, 1.2, 0.3, 1.2, 0, pink);
                world.playSound(c, org.bukkit.Sound.ENTITY_PLAYER_BIG_FALL, 2f, 0.6f);
                world.playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.9f);
                for (Player p : world.getPlayers()) { // a gentle shockwave (no damage)
                    Vector away = p.getLocation().toVector().subtract(pos).setY(0);
                    if (!survival(p) || away.lengthSquared() > 36 || away.lengthSquared() < 0.01) continue;
                    p.setVelocity(away.normalize().multiply(0.55).setY(0.3));
                }
            }
            if (t >= 48 && t < 56) pose = kneelPose(ticks);                                             // holds the landing
            else if (t >= 56 && t < 68) pose = Pose.lerp(kneelPose(ticks), standPose(), (t - 56) / 12f); // and rises
            // 4) flicks the ball up with his foot and catches it on his hip
            else if (t >= 68 && t < 72) pose = Pose.lerp(standPose(), kickWindupPose(), (t - 68) / 4f);
            else if (t == 72) { pose = kickPose(); world.playSound(ballSpot, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 1.3f); }
            if (t >= 72 && t < 84) {
                double f = (t - 72) / 12.0;
                Location hip = hipBall(loc(), 99);
                ballAt = ballSpot.clone().add(0, 0.2, 0).multiply(1 - f).add(hip.toVector().multiply(f)).add(0, Math.sin(f * Math.PI) * 1.6, 0);
                pose = Pose.lerp(kickPose(), hipHoldPose(ticks, 99), (float) f);
            }
            if (t >= 84) { // 5) caught: ready
                ballAt = null;
                world.playSound(loc(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.2f, 0.8f);
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().toVector().distanceSquared(pos) > 40 * 40) continue;
                    p.showTitle(Title.title(legacy(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Don Lorenzo"), legacy(ChatColor.GRAY + "Right-click him to talk."),
                            Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(2200), java.time.Duration.ofMillis(600))));
                }
                state = TALK; talkTicks = 0;
            }
        }

        // ---------- who's fighting ----------
        void join(Player p) {
            if (!survival(p) && !inCutscene(p)) return;
            fighters.add(p.getUniqueId()); down.remove(p.getUniqueId());
        }
        List<Player> active() {
            List<Player> list = new ArrayList<>();
            for (UUID id : fighters) {
                if (down.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead() || !p.getWorld().equals(world)) continue;
                if (!survival(p) && !inCutscene(p)) continue;
                if (p.getLocation().toVector().distanceSquared(pos) > 70 * 70) continue;
                list.add(p);
            }
            return list;
        }
        Player nearest() {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : active()) { double d = p.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; best = p; } }
            return best;
        }
        void fighterDied(Player p) {
            if (!fighters.contains(p.getUniqueId()) || state >= DYING) return;
            down.add(p.getUniqueId());
            if (state == PHASE1 || state == PHASE2 || state == SPEECH) {
                Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (don == this && active().isEmpty()) vanish(ChatColor.LIGHT_PURPLE + "Don Lorenzo: " + ChatColor.WHITE + "...Too easy."); });
            }
        }

        // ---------- main loop ----------
        void tick() {
            ticks++;
            if (!hitbox.isValid() && state < DYING) { cleanup(); return; }
            if (state == PHASE2 || state == SPEECH || state == SPEARS) { mtPrev = mt; mt = Math.max(mt + 1, songTicks()); }
            // his real health is `hp`; the hitbox never dies on its own (but once he's beaten, leave it dead:
            // BUG FIX: this used to revive it, and the fight never finished cleaning up)
            if (state < DYING && hitbox.isValid() && hitbox.getHealth() < 900) hitbox.setHealth(1000);
            // BUG FIX: everything below ran as one chain, so ONE error (a move, the cutscene) froze him for good, and
            // an error in the cutscene would have left everyone stuck in spectator mode. Now each part recovers.
            try {
                switch (state) {
                    case ARRIVE -> arriveTick();
                    case TALK -> talkTick();
                    case PHASE1 -> phase1Tick();
                    case CUTSCENE -> { if (cutscene != null) cutscene.tick(); }
                    case PHASE2 -> phase2Tick();
                    case SPEECH -> speechTick();
                    case SPEARS -> spearsTick();
                    case DYING -> dyingTick();
                    case LEAVING -> leavingTick();
                    default -> { }
                }
                failStreak = 0;
            } catch (RuntimeException e) {
                logBossPart("Don Lorenzo", "state " + state + ", move " + attack + " (tick " + t + ")", e);
                recover();
                if (don != this) return;
            }
            if (state != CUTSCENE) bossPart("Don Lorenzo", "drawing", this::render);
            bossPart("Don Lorenzo", "shots", this::tickShots);
            bossPart("Don Lorenzo", "orbs", this::tickOrbs);
            hitCooldown.replaceAll((k, v) -> v - 1);
            hitCooldown.values().removeIf(v -> v <= 0);
            bossPart("Don Lorenzo", "boss bars", this::updateBars);
        }

        int failStreak;

        /** After an error: skip what broke, and never leave anyone stuck (spectating, slowed, or in the box). */
        void recover() {
            failStreak++;
            try {
                switch (state) {
                    case ARRIVE -> { lift = 0; ballAt = null; state = TALK; talkTicks = 0; }   // straight to talking
                    case CUTSCENE -> {                                                     // release everyone, straight to the drop
                        if (cutscene != null) cutscene.finish(true);
                        if (box == null) beginPhase2(); else state = PHASE2; // never build a second box (it would save the glass as "original")
                    }
                    case PHASE1, PHASE2 -> { attack = -1; t = 0; stagger = 0; guarding = false; dribbling = false; recover = 10; } // skip the move
                    default -> { }
                }
            } catch (RuntimeException again) {
                logBossPart("Don Lorenzo", "recovering", again);
                failStreak = 999;
            }
            // still failing (or the recovery itself failed): end the fight cleanly (restores the arena and everyone in it)
            if (failStreak > (state >= SPEECH ? 40 : 100)) {
                Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "Don Lorenzo " + ChatColor.GRAY + "vanished.");
                cleanup();
            }
        }

        Pose shown = standPose();   // what's actually on screen: eases toward `pose` every tick
        int flinch, hitFlash;
        boolean flashing;
        Vector lastPos;
        double moveSpeed;
        Player lookAt;             // his head follows this player

        void render() {
            Location root = loc();
            hitbox.teleport(root);
            // how fast he's actually moving (for the walk bob and hip twist)
            if (lastPos != null) moveSpeed = moveSpeed * 0.7 + lastPos.clone().setY(0).distance(pos.clone().setY(0)) * 0.3;
            lastPos = pos.clone();
            Pose layered = pose.copy();
            // breathing
            float breath = (float) Math.sin(ticks * 0.08);
            layered.add(BODY, breath * 1.5f, 0, 0).add(ARM_R, 0, 0, -breath * 2).add(ARM_L, 0, 0, breath * 2);
            // walking: a bob and a hip twist in step with his speed
            if (moveSpeed > 0.04) {
                float stride = (float) Math.min(1, moveSpeed * 3);
                layered.drop += -(float) Math.abs(Math.sin(ticks * 0.55)) * 0.07f * scale * stride;
                layered.add(BODY, 0, (float) Math.sin(ticks * 0.55) * 7 * stride, 0);
            }
            // head tracking: he looks at his target (within a natural neck range)
            Player look = lookAt != null && lookAt.isOnline() && lookAt.getWorld().equals(world) ? lookAt : nearestAny(20);
            if (look != null) {
                Vector to = look.getEyeLocation().toVector().subtract(pos.clone().add(new Vector(0, 1.6 * scale, 0)));
                float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                float rel = ((want - yaw) % 360 + 540) % 360 - 180;
                float pitch = (float) -Math.toDegrees(Math.atan2(to.getY(), Math.max(0.5, Math.hypot(to.getX(), to.getZ()))));
                layered.add(HEAD, Math.max(-30, Math.min(30, pitch)), -Math.max(-45, Math.min(45, rel)), 0);
            }
            // a flinch when he's hit
            if (flinch > 0) { layered.add(BODY, -flinch * 2.2f, 0, 0).add(HEAD, -flinch * 2f, 0, 0); flinch--; }
            shown = Pose.lerp(shown, layered, 0.45f); // eases into every new pose instead of snapping
            boolean hidden = state == ARRIVE && arriveT < 40; // the entrance: not here yet
            renderRig(parts, sword, lift > 0 ? root.clone().add(0, lift, 0) : root, yaw, hidden ? 0.001f : scale, shown);
            // a pink hit-flash on his whole body
            boolean wantFlash = hitFlash > 0;
            if (hitFlash > 0) hitFlash--;
            if (wantFlash != flashing) {
                flashing = wantFlash;
                for (ItemDisplay pd : parts) if (pd != null && pd.isValid()) { pd.setGlowColorOverride(Color.fromRGB(255, 120, 200)); pd.setGlowing(wantFlash); }
            }
            // (between attacks he juggles the ball: see the ball's juggle below)
            // the ball: juggled at his right foot, unless he's kicking it
            if (ball.isValid()) {
                boolean juggle = attack < 0 && stagger <= 0;
                Vector foot = new Vector(-0.25, 0, 0.55).rotateAroundY(Math.toRadians(-yaw));
                double hop = juggle ? Math.abs(Math.sin(ticks * 0.18)) * 0.9 : 0.12;
                Location bl = state == ARRIVE ? (ballAt != null ? ballAt : hipBall(root, 99))   // the entrance
                        : state == TALK ? hipBall(root, ticks % 140)                              // on his hip while he waits
                        : root.clone().add(foot).add(0, 0.15 + hop, 0);                          // at his foot in the fight
                ball.teleport(bl);
                ball.setInterpolationDelay(0);
                ball.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX(ticks * 0.3f),
                        new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
            }
            if (state == PHASE2 && ticks % 3 == 0) { // the Overtime aura (light: it outlines him, it doesn't bury him)
                world.spawnParticle(Particle.DUST, root.clone().add(0, 1.0 * scale, 0), 2, 0.4, 0.75, 0.4, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 110, 200), 0.9f));
            }
        }

        void face(Vector target) {
            Vector d = target.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += diff * (state == PHASE2 ? 0.5f : 0.25f);
        }

        /** Moves along the ground toward a point, inside the box if it's up. */
        void moveToward(Vector target, double speed) {
            Vector d = target.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            if (d.length() > speed) d.normalize().multiply(speed);
            pos.add(d);
            clampToArena();
            pos.setY(groundAt(pos));
        }

        double groundAt(Vector v) {
            int x = v.getBlockX(), z = v.getBlockZ();
            for (int y = floorY + 3; y >= floorY - 4; y--) {
                if (world.getBlockAt(x, y - 1, z).getType().isSolid() && !world.getBlockAt(x, y, z).getType().isSolid()) return y;
            }
            return floorY;
        }

        void clampToArena() {
            if (box != null) { // the box's real interior: never inside a wall
                pos.setX(Math.max(box.x0 + 1.4, Math.min(box.x1 - 0.4, pos.getX())));
                pos.setZ(Math.max(box.z0 + 1.4, Math.min(box.z1 - 0.4, pos.getZ())));
                return;
            }
            pos.setX(Math.max(home.getX() - 30, Math.min(home.getX() + 30, pos.getX())));
            pos.setZ(Math.max(home.getZ() - 30, Math.min(home.getZ() + 30, pos.getZ())));
        }

        boolean inFront(Player p, double range, double cone) {
            Vector to = p.getLocation().toVector().subtract(pos).setY(0);
            if (to.lengthSquared() > range * range) return false;
            if (to.lengthSquared() < 0.5) return true;
            Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            return fwd.dot(to.normalize()) > cone;
        }

        void strike(Player p, double dmg, Guard guard) {
            if (hitCooldown.containsKey(p.getUniqueId())) return;
            if (p.getUniqueId().equals(marked)) dmg *= ncfg("price-check-bonus", 1.2); // Price Check: +20% on the marked player
            hurt(p, dmg, hitbox, pos.clone().add(new Vector(0, 1, 0)), guard);
            hitCooldown.put(p.getUniqueId(), 8);
        }

        // ---------- TALK: he waits for someone to talk to him ----------
        void talkTick() {
            Player n = nearestAny(24);
            if (n != null) face(n.getLocation().toVector());
            pose = hipHoldPose(ticks, ticks % 140); // ball on his hip; every 7 seconds he tosses it up and catches it
            if (++talkTicks > ncfg("talk-timeout-seconds", 180) * 20) vanish(ChatColor.LIGHT_PURPLE + "Don Lorenzo " + ChatColor.GRAY + "got bored and left.");
        }

        Player nearestAny(double range) {
            Player best = null; double bd = range * range;
            for (Player p : world.getPlayers()) {
                double d = p.getLocation().toVector().distanceSquared(pos);
                if (d < bd) { bd = d; best = p; }
            }
            return best;
        }

        void ask(Player p) {
            if (state != TALK) return;
            if (talker != null && !talker.equals(p.getUniqueId()) && Bukkit.getPlayer(talker) != null) {
                p.sendMessage(ChatColor.GRAY + "He's talking to someone else right now."); return;
            }
            talker = p.getUniqueId();
            face(p.getLocation().toVector());
            p.sendMessage(net.kyori.adventure.text.Component.text("Don Lorenzo: ", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE)
                    .append(net.kyori.adventure.text.Component.text("Are you sure you want to fight? ", net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(net.kyori.adventure.text.Component.text("[Yes]", net.kyori.adventure.text.format.NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/don answer yes " + token)))
                    .append(net.kyori.adventure.text.Component.text(" "))
                    .append(net.kyori.adventure.text.Component.text("[No]", net.kyori.adventure.text.format.NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.BOLD)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/don answer no " + token))));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_AMBIENT, 1f, 0.6f);
        }

        void answer(Player p, boolean yes, String tok) {
            if (state != TALK || !token.equals(tok)) { p.sendMessage(ChatColor.GRAY + "That question isn't open anymore."); return; }
            if (talker == null || !talker.equals(p.getUniqueId())) { p.sendMessage(ChatColor.GRAY + "He didn't ask you."); return; }
            if (!yes) {
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Don Lorenzo: " + ChatColor.WHITE + "Smart. Come back when you're ready.");
                vanish(null);
                return;
            }
            if (!survival(p)) { p.sendMessage(ChatColor.GRAY + "(Switch to survival to fight him.)"); return; }
            token = "used";
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Don Lorenzo: " + ChatColor.WHITE + "Then show me.");
            for (Player q : world.getPlayers()) if (q.getLocation().toVector().distanceSquared(pos) < 32 * 32) join(q);
            join(p);
            for (Player q : active()) bar.addPlayer(q);
            state = PHASE1;
            hp = maxHp = ncfg("phase1-health", 500);
            attack = -1; recover = 20;
        }

        // ---------- PHASE 1: 500 health, 2 moves, all ball ----------
        void phase1Tick() {
            lookAt = nearest();
            List<Player> a = active();
            for (Player p : world.getPlayers()) if (survival(p) && p.getLocation().toVector().distanceSquared(pos) < 24 * 24) join(p);
            Player target = nearest();
            if (target == null) { if (++lonely > 1200) vanish(ChatColor.LIGHT_PURPLE + "Don Lorenzo " + ChatColor.GRAY + "left."); pose = idlePose(ticks); return; }
            lonely = 0;
            Vector tp = target.getLocation().toVector();
            face(tp);
            double dist = tp.clone().subtract(pos).setY(0).length();
            if (attack < 0) {
                if (recover > 0) { recover--; pose = Pose.lerp(pose, idlePose(ticks), 0.3f); return; }
                if (dist > 2.6) { moveToward(tp, ncfg("phase1-speed", 0.22)); pose = walkPose(ticks, 1); return; }
                attack = random.nextInt(4) == 0 ? 1 : 0; t = 0; // mostly Rapid Fire
            }
            if (attack == 0) { // RAPID FIRE: three quick kicked shots as he closes in
                int c = t % 12;
                boolean left = (t / 12) % 2 == 1; // alternates right and left foot
                Pose wind = left ? mirror(kickWindupPose()) : kickWindupPose(), hit = left ? mirror(kickPose()) : kickPose();
                Pose thru = left ? mirror(followThroughPose()) : followThroughPose();
                if (t < 10) pose = anim(t, new int[]{0, 10}, new Pose[]{standPose(), crouchPose()});
                else if (t < 46) {
                    pose = anim(c, new int[]{0, 2, 4, 8, 12}, new Pose[]{wind, wind, hit, thru, wind});
                    if (c == 3) {
                        ballTrail();
                        moveToward(tp, 0.5);
                        kickBall(target.getEyeLocation().toVector(), 1.1, ncfg("phase1.slash-damage", 5), Guard.BLOCKABLE, false);
                    }
                } else { attack = -1; recover = 22; }
            } else { // BALL KICK
                pose = anim(t, new int[]{0, 6, 13, 15, 20, 26},
                        new Pose[]{standPose(), crouchPose(), powerWindupPose(), kickPose(), followThroughPose(), standPose()});
                if (t == 14) kickBall(target.getEyeLocation().toVector(), 0.9, ncfg("phase1.kick-damage", 6), Guard.BLOCKABLE, false);
                else if (t >= 26) { attack = -1; recover = 26; }
            }
            t++;
        }

        void kickBall(Vector at, double speed, double dmg, Guard guard, boolean bounces) {
            Vector from = pos.clone().add(new Vector(0, 0.5, 0)).add(new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))).multiply(0.8));
            Vector v = at.clone().subtract(from);
            if (v.lengthSquared() < 0.01) return;
            shots.add(new DonShot(from, v.normalize().multiply(speed), dmg, guard, bounces, bounces ? 90 : 50));
            world.playSound(loc(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.4f, 0.7f);
        }

        /** Phase 1 stops at 50 health: that's what starts the cutscene. */
        void damage(double amount, Player by) {
            flinch = Math.max(flinch, 6);
            hitFlash = 3;
            if (state == PHASE1) {
                hp -= amount;
                if (by != null) dealt.merge(by.getUniqueId(), amount, Double::sum);
                if (hp <= ncfg("phase1-threshold", 50)) {
                    hp = ncfg("phase1-threshold", 50);
                    state = CUTSCENE; // locks him right away; the cutscene itself starts next tick, after this hit is done
                    Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (don == this && cutscene == null && state == CUTSCENE) startCutscene(); });
                }
            } else if (state == PHASE2) {
                double d = amount * (1 - ncfg("phase2-defense", 0.05));
                hp -= d;
                if (by != null) dealt.merge(by.getUniqueId(), d, Double::sum);
                if (hp <= 0) die();
            }
        }

        void startCutscene() {
            state = CUTSCENE;
            attack = -1;
            for (DonShot s : shots) s.display.remove();
            shots.clear();
            cutscene = new Cutscene(this);
        }

        /** Called by the cutscene at 0:22: the box slams up and phase 2 begins. */
        void beginPhase2() {
            state = PHASE2;
            mt = mtPrev = T_FIGHT;
            scale = 1.1f;
            for (ItemDisplay d : parts) d.remove();
            parts = spawnRig(loc(), scale);
            int n = Math.max(1, active().size());
            maxHp = hp = ncfg("phase2-health", 1500) * (1 + ncfg("phase2-health-per-extra-fighter", 0.4) * (n - 1));
            bar.setTitle(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Don Lorenzo " + ChatColor.WHITE + "- OVERTIME");
            timer = Bukkit.createBossBar("", BarColor.PINK, BarStyle.SOLID);
            box = new Box(this);
            box.build();
            for (Player p : active()) {
                // BUG FIX: "inside" counts the walls themselves, so someone standing on the edge got a glass
                // block placed around them and could just walk out the other side. Only the open interior counts now.
                if (!box.interior(p.getLocation())) { allowTeleport = true; p.teleport(randomInside(4)); allowTeleport = false; }
                else { // BUG FIX: put back mid-jump or mid-flight? Set down on the floor, not dropped from the air
                    Location here = p.getLocation();
                    double floor = groundAt(here.toVector());
                    if (here.getY() - floor > 1.2) { here.setY(floor); allowTeleport = true; p.teleport(here); allowTeleport = false; }
                }
                p.setFallDistance(0);
                bar.addPlayer(p); timer.addPlayer(p);
            }
            attack = -1; recover = 10; count = 0;
        }

        Location randomInside(double margin) {
            double r = 16 - margin;
            Vector v = home.toVector().add(new Vector((random.nextDouble() * 2 - 1) * r, 0, (random.nextDouble() * 2 - 1) * r));
            v.setY(groundAt(v));
            return v.toLocation(world, random.nextFloat() * 360, 0);
        }

        // ---------- PHASE 2: 6 moves, fast, pink orbs every 10s ----------
        void phase2Tick() {
            markTick();
            if (mt >= T_SPEECH) { unmark(); startSpeech(); return; }
            if (active().isEmpty()) { vanish(ChatColor.LIGHT_PURPLE + "Don Lorenzo: " + ChatColor.WHITE + "...Too easy."); return; }
            int every = (int) ncfg("orb-interval-ticks", 200);
            if (mt > T_FIGHT && (mt - T_FIGHT) / every > Math.max(0, mtPrev - T_FIGHT) / every) spawnOrb();
            List<Player> a = active();
            Player target = nearest();
            if (target == null) return;
            Player m = marked != null ? Bukkit.getPlayer(marked) : null; // Price Check: he goes after the marked player
            if (m != null && a.contains(m)) target = m;
            lookAt = target;
            Vector tp = target.getLocation().toVector();
            if (stagger > 0) { stagger--; pose = anim(20 - stagger, new int[]{0, 3, 12, 20}, new Pose[]{recoilPose(), recoilPose(), staggerPose(), powerPose()}); return; }
            face(tp);
            double dist = tp.clone().subtract(pos).setY(0).length();
            if (attack < 0) {
                if (recover > 0) { recover--; pose = Pose.lerp(pose, powerPose(), 0.25f); return; }
                if (dist > 5) { // dashes in, leaving a pink trail
                    moveToward(tp, ncfg("phase2-speed", 0.95));
                    pose = runPose(ticks, 1f); // a real sprint
                    if (ticks % 2 == 0) world.spawnParticle(Particle.DUST, loc().add(0, 0.6, 0), 1, 0.2, 0.3, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.0f));
                    return;
                }
                int pick;
                int moves = hp < maxHp * ncfg("ace-eater-below", 0.6) ? 9 : 8; // Ace Eater (Flow) only once he's hurt
                do pick = 1 + random.nextInt(moves); while ((pick == count && random.nextInt(3) > 0) || (pick == 7 && marked != null));
                attack = pick; count = pick; t = 0; sub = 0; guarding = false;
            }
            switch (attack) {
                case 1 -> blinkSlash(target, a);
                case 2 -> overtimeRush(target, a);
                case 3 -> directShot(target);
                case 4 -> ricochet(target);
                case 5 -> groundSlash(target, a);
                case 6 -> zombieGuard(target);
                case 7 -> priceCheck(target);
                case 8 -> zombieDribble(target, a);
                case 9 -> aceEater(target, a);
                default -> attack = -1;
            }
            t++;
        }

        /** A glowing pink "ghost" of him that lingers a moment where he just was (blinks, dashes, counters). */
        void afterimage() {
            for (ItemDisplay pd : parts) {
                if (pd == null || !pd.isValid()) continue;
                ItemDisplay g = world.spawn(pd.getLocation(), ItemDisplay.class, x -> {
                    x.setItemStack(pd.getItemStack());
                    x.setTransformation(pd.getTransformation());
                    x.setGlowing(true);
                    x.setGlowColorOverride(Color.fromRGB(255, 110, 200));
                    x.setBrightness(new Display.Brightness(15, 15));
                    x.setViewRange(6f);
                    x.setPersistent(false);
                    x.addScoreboardTag(DISPLAY_TAG);
                });
                Bukkit.getScheduler().runTaskLater(FaultlineBosses.this, () -> { if (g.isValid()) g.remove(); }, 8L);
            }
        }

        /** A kick arc: a low sweep of particles in front of him along the ball's path. */
        void ballTrail() {
            Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            boolean p2 = state == PHASE2;
            for (int i = 0; i <= 8; i++) {
                double f = i / 8.0;
                Location l = loc().add(fwd.clone().multiply((0.4 + f * 2.2) * scale)).add(0, (0.25 + Math.sin(f * Math.PI) * 0.7) * scale, 0);
                world.spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0,
                        new Particle.DustOptions(p2 ? Color.fromRGB(255, 110, 200) : Color.fromRGB(235, 235, 245), 1.2f));
                if (i % 3 == 0) world.spawnParticle(Particle.CRIT, l, 1, 0, 0, 0, 0.1);
            }
        }

        /** (Old sword trail: no longer used.) */
        void slashTrail() {
            Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            boolean p2 = state == PHASE2;
            for (int a = -70; a <= 70; a += 14) {
                Vector v = fwd.clone().rotateAroundY(Math.toRadians(a)).multiply(1.7 * scale);
                Location l = loc().add(v).add(0, (1.1 + a / 140.0 * 0.6) * scale, 0);
                world.spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0,
                        new Particle.DustOptions(p2 ? Color.fromRGB(255, 110, 200) : Color.fromRGB(235, 235, 245), 1.2f));
                if (a % 28 == 0) world.spawnParticle(Particle.CRIT, l, 1, 0, 0, 0, 0.1);
            }
        }

        void endAttack(int recoverTicks) { attack = -1; recover = (int) Math.round(recoverTicks * ncfg("phase2.recovery-scale", 0.75)); guarding = false; dribbling = false; } // shorter pauses

        /** 1) Blink Shot: vanishes into pink light, reappears behind you, and slashes. */
        void blinkSlash(Player target, List<Player> a) {
            if (t == 0) {
                afterimage(); // a ghost where he was
                world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.3f));
                Vector behind = target.getLocation().getDirection().setY(0);
                if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
                pos = target.getLocation().toVector().subtract(behind.normalize().multiply(2.2));
                clampToArena(); pos.setY(groundAt(pos));
                face(target.getLocation().toVector()); face(target.getLocation().toVector()); face(target.getLocation().toVector());
                world.playSound(loc(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.4f);
            }
            if (t < 7) pose = anim(t, new int[]{0, 2, 6}, new Pose[]{crouchPose(), crouchPose(), powerWindupPose()}); // vanishes low, reappears wound up
            else if (t == 7) { // point-blank: he blasts the ball right into you
                pose = kickPose();
                ballTrail();
                world.playSound(loc(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.6f, 0.7f);
                world.spawnParticle(Particle.EXPLOSION, loc().add(0, 1.0, 0).add(loc().getDirection().multiply(1.3)), 1);
                for (Player p : a) if (inFront(p, 3.4, 0.2)) {
                    strike(p, ncfg("phase2.blink-damage", 9), Guard.BLOCKABLE);
                    Vector kb = p.getLocation().toVector().subtract(pos).setY(0);
                    if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(0.9).setY(0.35));
                }
            } else if (t > 22) endAttack(14); // the follow-through is your window
            if (t > 7 && t <= 22) pose = anim(t, new int[]{7, 11, 18, 22}, new Pose[]{kickPose(), followThroughPose(), followThroughPose(), standPose()});
        }

        /** 2) Overtime Rush: three lightning dashes along a pink line. */
        void overtimeRush(Player target, List<Player> a) {
            int c = t % 24;
            if (c == 0 && t / 24 >= 3) { endAttack(18); return; }
            if (c < 10) { // aim: a pink line shows the path
                pose = Pose.lerp(pose, crouchPose(), 0.3f); // a sprinter's crouch while he lines it up
                dashDir = target.getLocation().toVector().subtract(pos).setY(0);
                if (dashDir.lengthSquared() < 0.01) dashDir = new Vector(1, 0, 0);
                dashDir.normalize();
                for (int i = 1; i < 16; i += 2) {
                    world.spawnParticle(Particle.DUST, pos.toLocation(world).add(dashDir.clone().multiply(i)).add(0, 0.2, 0), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 80, 180), 1.6f));
                }
            } else if (c < 20) {
                pose = runPose(ticks * 2, 1.25f);
                pos.add(dashDir.clone().multiply(ncfg("phase2.rush-speed", 1.6)));
                clampToArena(); pos.setY(groundAt(pos));
                if (c == 10) { afterimage(); world.playSound(loc(), org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, 1.2f); }
                world.spawnParticle(Particle.DUST, loc().add(0, 0.8, 0), 2, 0.25, 0.5, 0.25, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.1f));
                for (Player p : a) if (p.getLocation().toVector().distanceSquared(pos) < 1.7 * 1.7) strike(p, ncfg("phase2.rush-damage", 8), Guard.HEAVY);
            } else pose = Pose.lerp(pose, standPose(), 0.4f);
        }

        /** 3) Direct Shot: plants his foot, the ball glows pink, and he blasts it at you. */
        void directShot(Player target) {
            if (t < 14) {
                pose = anim(t, new int[]{0, 5, 13}, new Pose[]{standPose(), crouchPose(), powerWindupPose()});
                if (t % 2 == 0) world.spawnParticle(Particle.DUST, ball.getLocation(), 4, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.5f));
            } else if (t == 14) {
                pose = kickPose();
                kickBall(target.getEyeLocation().toVector(), ncfg("phase2.shot-speed", 1.4), ncfg("phase2.shot-damage", 10), Guard.HEAVY, false);
            } else if (t < 30) pose = anim(t, new int[]{14, 18, 24, 30}, new Pose[]{kickPose(), followThroughPose(), followThroughPose(), standPose()}); // holds the follow-through a beat
            else endAttack(16);
        }

        /** 4) Ricochet: three balls that bounce off the walls of the box. */
        void ricochet(Player target) {
            if (t < 10) pose = anim(t, new int[]{0, 9}, new Pose[]{standPose(), mirror(powerWindupPose())}); // a left-footed volley
            else if (t == 10) {
                pose = mirror(kickPose());
                Vector aim = target.getLocation().toVector().add(new Vector(0, 1, 0));
                for (int i = -1; i <= 1; i++) {
                    Vector flat = aim.clone().subtract(pos).setY(0);
                    if (flat.lengthSquared() < 0.0001) flat = fwd(); // you're right on top of him
                    Vector v = flat.normalize().rotateAroundY(Math.toRadians(i * 25));
                    kickBall(pos.clone().add(v.multiply(10)).setY(pos.getY() + 1), ncfg("phase2.ricochet-speed", 0.8), ncfg("phase2.ricochet-damage", 6), Guard.BLOCKABLE, true);
                }
            } else if (t < 26) pose = anim(t, new int[]{10, 14, 20, 26}, new Pose[]{mirror(kickPose()), mirror(followThroughPose()), mirror(followThroughPose()), standPose()});
            else endAttack(14);
        }

        /** 5) Ground Spike: leaps, spikes the ball into the ground, and a shockwave rolls forward. Jump it! */
        void groundSlash(Player target, List<Player> a) {
            if (t < 12) { // crouch, leap, and the overhead spike at the top
                pose = anim(t, new int[]{0, 3, 8, 12}, new Pose[]{standPose(), crouchPose(), jumpPose(), spikePose()});
                if (t >= 3) pose.drop = (float) Math.sin((t - 3) / 9.0 * Math.PI) * 1.6f;
            }
            else if (t == 12) {
                pose = landPose(); // lands in a squat, driving the ball down
                aimAt = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
                Location spike = loc().add(aimAt.clone().multiply(0.9));
                world.spawnParticle(Particle.EXPLOSION, spike, 1);
                world.spawnParticle(Particle.DUST, spike.clone().add(0, 0.3, 0), 18, 0.4, 0.2, 0.4, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.5f));
                world.playSound(loc(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.6f, 0.5f);
                world.playSound(loc(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.7f);
            } else if (t < 28) {
                double d = (t - 12) * 1.0;
                Vector c = pos.clone().add(aimAt.clone().multiply(d));
                Vector side = new Vector(-aimAt.getZ(), 0, aimAt.getX());
                for (int i = -2; i <= 2; i++) {
                    Location l = c.clone().add(side.clone().multiply(i * 0.8)).toLocation(world).add(0, 0.1, 0);
                    world.spawnParticle(Particle.BLOCK, l, 4, 0.2, 0.1, 0.2, 0, l.clone().subtract(0, 1, 0).getBlock().getBlockData());
                    world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.4f));
                }
                for (Player p : a) {
                    if (!p.isOnGround()) continue; // jumped over it
                    Vector rel = p.getLocation().toVector().subtract(c).setY(0);
                    if (Math.abs(rel.dot(aimAt)) < 1.0 && Math.abs(rel.dot(side)) < 2.0) {
                        strike(p, ncfg("phase2.shockwave-damage", 8), Guard.UNBLOCKABLE);
                        p.setVelocity(p.getVelocity().setY(0.7));
                    }
                }
                pose = Pose.lerp(pose, standPose(), 0.15f);
            } else endAttack(14);
        }

        /** 6) Zombie Guard: a counter stance. Hit him during it and he counters; wait it out instead. */
        void zombieGuard(Player target) {
            guarding = t < 34;
            Pose g = guardPose(); g.add(BODY, 0, (float) Math.sin(t * 0.3) * 7, 0).add(HEAD, 0, (float) Math.sin(t * 0.3) * -5, 0);
            pose = Pose.lerp(pose, g, 0.35f); // the guard sways, daring you to swing
            if (t % 3 == 0 && guarding) world.spawnParticle(Particle.END_ROD, loc().add(0, 1.2, 0), 3, 0.4, 0.6, 0.4, 0.01);
            if (t == 0) world.playSound(loc(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1.4f, 0.6f);
            if (t >= 34) endAttack(10);
        }

        // ---------------- Mastery moves ----------------
        UUID marked;              // Price Check: the player he's marked
        int markTicks;
        boolean dribbling;        // Zombie Dribble: untouchable (i-frames) while dribbling
        final NamespacedKey markKey = new NamespacedKey(FaultlineBosses.this, "don_price_check");

        /** 7) Price Check: marks you with a target symbol. Marked: 22% slower, he focuses you, and his hits on you do +20%. */
        void priceCheck(Player target) {
            if (t == 0) {
                pose = pointPose();
                marked = target.getUniqueId();
                markTicks = (int) ncfg("price-check-ticks", 120);
                AttributeInstance speed = target.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null && speed.getModifier(markKey) == null)
                    speed.addTransientModifier(new org.bukkit.attribute.AttributeModifier(markKey, -0.22, org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
                target.sendActionBar(legacy(ChatColor.RED + "\u2716 PRICE CHECK " + ChatColor.GRAY + "- you're marked: slowed, and he's coming for you."));
                world.playSound(target.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1.4f, 0.6f);
                world.playSound(loc(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
            }
            pose = anim(t, new int[]{0, 4, 11, 14}, new Pose[]{standPose(), pointPose(), pointPose().add(HEAD, 0, 0, 12), standPose()});
            if (t >= 14) endAttack(8);
        }

        /** The mark: a red target over their head; it wears off (and the slow is removed) after 6 seconds. */
        void markTick() {
            if (marked == null) return;
            Player m = Bukkit.getPlayer(marked);
            if (m == null || --markTicks <= 0 || state != PHASE2 || m.isDead()) { unmark(); return; }
            if (ticks % 3 == 0) {
                Location top = m.getLocation().add(0, 2.4, 0);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8 + ticks * 0.1;
                    world.spawnParticle(Particle.DUST, top.clone().add(Math.cos(a) * 0.45, 0, Math.sin(a) * 0.45), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(230, 30, 40), 0.8f));
                }
                world.spawnParticle(Particle.DUST, top, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(230, 30, 40), 1.2f));
            }
        }

        void unmark() {
            if (marked == null) return;
            Player m = Bukkit.getPlayer(marked);
            if (m != null) {
                AttributeInstance speed = m.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null && speed.getModifier(markKey) != null) speed.removeModifier(markKey);
            }
            marked = null;
        }

        /** 8) Zombie Dribble: weaves straight at you with the ball, untouchable, bowling over anyone in the way. */
        void zombieDribble(Player target, List<Player> a) {
            if (t == 0) { dribbling = true; world.playSound(loc(), org.bukkit.Sound.ENTITY_ZOMBIE_AMBIENT, 1.4f, 0.6f); }
            Vector to = target.getLocation().toVector().subtract(pos).setY(0);
            if (to.lengthSquared() < 0.01) to = new Vector(1, 0, 0);
            to.normalize();
            Vector side = new Vector(-to.getZ(), 0, to.getX()).multiply(Math.sin(t * 0.55) * 0.55); // the weave
            pos.add(to.clone().multiply(ncfg("phase2.dribble-speed", 0.75)).add(side));
            clampToArena(); pos.setY(groundAt(pos));
            pose = runPose(ticks * 2, 0.8f);
            pose.add(BODY, 18, (float) Math.sin(t * 0.55) * 20, 0).add(HEAD, -10, 0, 0); // hunched, swaying: the zombie walk
            if (ticks % 2 == 0) {
                world.spawnParticle(Particle.DUST, loc().add(0, 0.4, 0), 1, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(Color.fromRGB(40, 20, 50), 1.1f));
                world.spawnParticle(Particle.DUST, loc().add(0, 0.2, 0), 1, 0.15, 0.1, 0.15, 0, new Particle.DustOptions(Color.fromRGB(140, 60, 200), 1.0f));
            }
            for (Player p : a) {
                if (p.getLocation().toVector().distanceSquared(pos) > 1.5 * 1.5) continue;
                strike(p, ncfg("phase2.dribble-damage", 9), Guard.HEAVY);
                Vector push = p.getLocation().toVector().subtract(pos).setY(0);
                p.setVelocity((push.lengthSquared() > 0.0001 ? push.normalize().multiply(0.9) : new Vector()).setY(0.45));
            }
            if (t >= (int) ncfg("phase2.dribble-ticks", 45)) { dribbling = false; endAttack(16); }
        }

        /** 9) Ace Eater (Flow): "Feeding time, OK?" + a lion, then Undead Crash (a locked-on dash + shot), then "Nyo-ho!" Soulless Strike. */
        void aceEater(Player target, List<Player> a) {
            if (t == 0) {
                pose = spreadPose();
                for (Player p : a) p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), legacy(ChatColor.GOLD + "Feeding time, OK?"),
                        Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(300))));
                world.playSound(loc(), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 2f, 0.6f);
            }
            if (t < 20) pose = anim(t, new int[]{0, 4, 16, 20}, new Pose[]{crouchPose(), roarPose(t), roarPose(t), crouchPose()}); // the roar
            if (t < 20 && t % 2 == 0) lion();
            if (t == 20) { // Undead Crash: a target symbol locks on you
                aimAt = target.getLocation().toVector();
                dashDir = aimAt.clone().subtract(pos).setY(0);
                if (dashDir.lengthSquared() < 0.01) dashDir = new Vector(1, 0, 0);
                dashDir.normalize();
                afterimage();
                world.playSound(loc(), org.bukkit.Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 0.8f);
            }
            if (t >= 20 && t < 34) {
                Location lock = target.getLocation().add(0, 2.4, 0);
                if (t % 2 == 0) for (int i = 0; i < 10; i++) {
                    double ang = Math.PI * 2 * i / 10 - t * 0.3;
                    world.spawnParticle(Particle.DUST, lock.clone().add(Math.cos(ang) * 0.6, 0, Math.sin(ang) * 0.6), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 220), 0.9f));
                }
                pos.add(dashDir.clone().multiply(ncfg("phase2.crash-speed", 1.5)));
                clampToArena(); pos.setY(groundAt(pos));
                pose = runPose(ticks * 2, 1.25f);
                world.spawnParticle(Particle.DUST, loc().add(0, 0.8, 0), 2, 0.2, 0.4, 0.2, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 220), 1.2f));
                if (pos.distanceSquared(target.getLocation().toVector()) < 3.5 * 3.5 || t == 33) {
                    pose = kickPose();
                    kickBall(target.getEyeLocation().toVector(), ncfg("phase2.crash-shot-speed", 1.7), ncfg("phase2.crash-damage", 14), Guard.HEAVY, false);
                    t = 34;
                }
            }
            if (t > 34 && t < 44) pose = Pose.lerp(pose, followThroughPose(), 0.3f);
            if (t == 44) { // Soulless Strike: "Nyo-ho!", behind you, a right-hand shot
                for (Player p : a) p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), legacy(ChatColor.LIGHT_PURPLE + "Nyo-ho!"),
                        Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(200))));
                world.playSound(loc(), org.bukkit.Sound.ENTITY_WITCH_CELEBRATE, 1.4f, 0.7f);
                afterimage();
                Vector behind = target.getLocation().getDirection().setY(0);
                if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
                pos = target.getLocation().toVector().subtract(behind.normalize().multiply(1.8));
                clampToArena(); pos.setY(groundAt(pos));
                face(target.getLocation().toVector()); face(target.getLocation().toVector()); face(target.getLocation().toVector());
                pose = crouchPose();
            }
            if (t > 44 && t < 50) pose = anim(t, new int[]{44, 49}, new Pose[]{crouchPose(), powerWindupPose()});
            if (t == 50) {
                pose = kickPose();
                ballTrail();
                world.playSound(loc(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2f, 0.5f);
                for (Player p : a) if (inFront(p, 3.2, 0.1)) {
                    strike(p, ncfg("phase2.soulless-damage", 16), Guard.HEAVY);
                    Vector kb = p.getLocation().toVector().subtract(pos).setY(0);
                    if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.6).setY(0.6));
                }
            }
            if (t > 50 && t < 64) pose = anim(t, new int[]{50, 54, 60, 64}, new Pose[]{kickPose(), followThroughPose(), followThroughPose(), standPose()});
            if (t >= 64) endAttack(20);
        }

        /** A golden lion rising behind him (a mane of particles and two eyes). */
        void lion() {
            Vector back = new Vector(Math.sin(Math.toRadians(yaw)), 0, -Math.cos(Math.toRadians(yaw))).multiply(1.4);
            Location c = loc().add(back).add(0, 2.2 * scale, 0);
            Vector right = new Vector(-back.getZ(), 0, back.getX()).normalize();
            for (int i = 0; i < 18; i++) {
                double a = Math.PI * 2 * i / 18;
                Location m = c.clone().add(right.clone().multiply(Math.cos(a) * 1.5)).add(0, Math.sin(a) * 1.5, 0);
                world.spawnParticle(Particle.DUST, m, 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(230, 170, 40), 1.3f));
            }
            for (int s2 = -1; s2 <= 1; s2 += 2)
                world.spawnParticle(Particle.DUST, c.clone().add(right.clone().multiply(0.45 * s2)).add(0, 0.25, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 240, 200), 1.4f));
        }

        /** Hit during his guard: he blinks to you and punishes. */
        void counter(Player p) {
            guarding = false;
            afterimage();
            attack = 1; t = 8; sub = 0; // into the follow-through of a Blink Shot (the counter itself is the hit)
            Vector dir = p.getLocation().getDirection().setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
            pos = p.getLocation().toVector().subtract(dir.normalize().multiply(1.8));
            clampToArena(); pos.setY(groundAt(pos));
            face(p.getLocation().toVector()); face(p.getLocation().toVector()); face(p.getLocation().toVector());
            world.playSound(loc(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, 0.6f);
            pose = followThroughPose(); ballTrail(); // a counter-kick
            hurt(p, ncfg("phase2.counter-damage", 12), hitbox, pos, Guard.HEAVY);
            p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "Countered! Don't hit him while he's guarding."));
        }

        // ---------- the pink orbs ----------
        void spawnOrb() {
            if (box == null) return;
            Location at = randomInside(3);
            orbs.add(new DonOrb(at.toVector().add(new Vector(0, 1.3, 0))));
            world.playSound(at, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 1.2f);
            world.spawnParticle(Particle.BLOCK, at, 30, 0.5, 0.1, 0.5, 0.1, at.clone().subtract(0, 1, 0).getBlock().getBlockData());
        }

        void orbHit(DonOrb o, Player by) {
            if (o.launchedBy != null || state != PHASE2) return;
            o.launchedBy = by;
            world.playSound(o.pos.toLocation(world), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.5f, 1.4f);
        }

        void tickOrbs() {
            for (DonOrb o : new ArrayList<>(orbs)) {
                o.life++;
                boolean remove = state != PHASE2 || !o.hitbox.isValid();
                if (!remove && o.launchedBy == null) {
                    if (o.life <= 20) o.pos = o.base.clone().add(new Vector(0, -1.3 + o.life * 0.065, 0)); // rises out of the ground
                    else o.pos = o.base.clone().add(new Vector(0, Math.sin(o.life * 0.15) * 0.15, 0));
                    if (o.life > ncfg("orb-life-ticks", 180)) remove = true;
                } else if (!remove) { // flying into him
                    Vector chest = pos.clone().add(new Vector(0, 1.1 * scale, 0));
                    Vector to = chest.clone().subtract(o.pos);
                    if (to.length() < 1.2) {
                        double hit = ncfg("orb-damage-min", 100) + random.nextDouble() * (ncfg("orb-damage-max", 150) - ncfg("orb-damage-min", 100));
                        damage(hit / (1 - ncfg("phase2-defense", 0.05)), o.launchedBy); // exactly 100-150 after his defense
                        orbsHit.merge(o.launchedBy.getUniqueId(), 1, Integer::sum);
                        stagger = 20; attack = -1; guarding = false; dribbling = false; // BUG FIX: a stagger mid-dribble left him untouchable for good
                        world.playSound(loc(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.6f);
                        world.spawnParticle(Particle.DUST, chest.toLocation(world), 16, 0.5, 0.6, 0.5, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.4f));
                        remove = true;
                    } else o.pos.add(to.normalize().multiply(1.1));
                }
                if (remove) { o.display.remove(); if (o.hitbox.isValid()) o.hitbox.remove(); orbs.remove(o); continue; }
                Location l = o.pos.toLocation(world);
                o.display.teleport(l);
                o.hitbox.teleport(l.clone().subtract(0, 0.45, 0));
                if (o.life % 3 == 0) bedrockTrail(l, Color.fromRGB(255, 110, 200), 1.6f);
            }
        }

        void tickShots() {
            for (DonShot s : new ArrayList<>(shots)) {
                s.life++;
                Vector next = s.pos.clone().add(s.vel);
                boolean remove = s.life > s.maxLife;
                if (s.bounces && box != null) { // off the walls of the box
                    if (next.getX() < box.x0 + 1.3 || next.getX() > box.x1 - 0.3) { s.vel.setX(-s.vel.getX()); next = s.pos.clone().add(s.vel); }
                    if (next.getZ() < box.z0 + 1.3 || next.getZ() > box.z1 - 0.3) { s.vel.setZ(-s.vel.getZ()); next = s.pos.clone().add(s.vel); }
                }
                if (!remove && world.getBlockAt(next.toLocation(world)).getType().isSolid() && !s.bounces) remove = true;
                else s.pos = next;
                for (Player p : active()) {
                    if (remove) break;
                    if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(s.pos) > 1.1) continue;
                    strike(p, s.damage, s.guard);
                    p.setVelocity(p.getVelocity().add(s.vel.clone().normalize().multiply(0.8).setY(0.35)));
                    if (!s.bounces) remove = true;
                }
                if (remove) { s.display.remove(); shots.remove(s); continue; }
                Location l = s.pos.toLocation(world);
                s.display.teleport(l);
                s.display.setInterpolationDelay(0);
                s.display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(s.life * 0.6f, s.life * 0.4f, 0),
                        new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                if (s.life % 2 == 0) {
                    world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.1f));
                    bedrockTrail(l, Color.fromRGB(255, 110, 200), 1.2f);
                }
            }
        }

        void updateBars() {
            if (ticks % 5 != 0) return;
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            if (timer != null) {
                int left = Math.max(0, T_SPEECH - mt);
                timer.setProgress(Math.max(0, Math.min(1, left / (double) (T_SPEECH - T_FIGHT))));
                int sec = left / 20;
                timer.setTitle(ChatColor.LIGHT_PURPLE + "\u23F1 " + ChatColor.WHITE + "" + ChatColor.BOLD + (sec / 60) + ":" + String.format("%02d", sec % 60)
                        + ChatColor.GRAY + " left to beat him");
            }
            if (state < DYING) { // BUG FIX: after he's beaten, don't put the bars back on everyone's screen
                for (Player p : active()) { if (!bar.getPlayers().contains(p)) bar.addPlayer(p); if (timer != null && !timer.getPlayers().contains(p)) timer.addPlayer(p); }
            }
        }

        // ---------- 2:43: the speech ----------
        void startSpeech() {
            state = SPEECH;
            attack = -1; guarding = false;
            if (timer != null) timer.removeAll();
            for (DonOrb o : orbs) { o.display.remove(); if (o.hitbox.isValid()) o.hitbox.remove(); }
            orbs.clear();
            say(ChatColor.WHITE + "I guess you couldn't kill me in time.", 70);
        }

        void speechTick() {
            Vector mid = home.toVector(); mid.setY(groundAt(mid));
            if (pos.clone().setY(0).distanceSquared(mid.clone().setY(0)) > 1) { moveToward(mid, 0.3); pose = walkPose(ticks, 0.6f); }
            else pose = foldArmsPose();
            Player n = nearest();
            if (n != null) face(n.getLocation().toVector());
            for (int i = 0; i < 4; i++) {
                if (!crossed(T_SPEECH + 80 * (i + 1))) continue;
                List<String> lines = speechLines();
                if (i < lines.size()) say(lines.get(i), 70);
            }
            if (crossed(T_SPEARS - 10)) say(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Goodbye.", 20);
            if (mt >= T_SPEARS) startSpears();
        }

        /** He talks about YOU: who hit him hardest, who ignored the orbs, who didn't last. */
        List<String> speechLines() {
            List<String> out = new ArrayList<>();
            List<UUID> byDamage = new ArrayList<>(fighters);
            byDamage.sort((x, y) -> Double.compare(dealt.getOrDefault(y, 0.0), dealt.getOrDefault(x, 0.0)));
            for (UUID id : byDamage) {
                if (out.size() >= 3) break;
                Player p = Bukkit.getPlayer(id);
                String name = p != null ? p.getName() : "You";
                int dmg = (int) Math.round(dealt.getOrDefault(id, 0.0));
                int orbCount = orbsHit.getOrDefault(id, 0);
                if (down.contains(id)) out.add(ChatColor.WHITE + name + " didn't even make it this far.");
                else if (out.isEmpty()) out.add(ChatColor.WHITE + name + "... " + dmg + " damage. You were the only one who worried me.");
                else if (orbCount < 5) out.add(ChatColor.WHITE + name + ", " + orbCount + " orbs? You needed a lot more than that.");
                else out.add(ChatColor.WHITE + name + ", " + dmg + " damage. Close. Not close enough.");
            }
            if (out.isEmpty()) out.add(ChatColor.WHITE + "Nobody? How disappointing.");
            return out;
        }

        void say(String line, int stay) {
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > 70 * 70) continue;
                p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), legacy(line),
                        Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(stay * 50L), java.time.Duration.ofMillis(300))));
            }
        }

        // ---------- 3:05: the ceiling comes down ----------
        final Set<UUID> impaled = new HashSet<>();
        void startSpears() {
            state = SPEARS;
            t = 0;
            if (box == null) { vanish(null); return; }
            for (double x = home.getX() - 14; x <= home.getX() + 14; x += 2.5) {
                for (double z = home.getZ() - 14; z <= home.getZ() + 14; z += 2.5) {
                    Location l = new Location(world, x, floorY + 10.2, z);
                    spears.add(spawnDisplay(l, "don_spear", 1.6f, 1, Display.Billboard.FIXED));
                }
            }
            world.playSound(home, org.bukkit.Sound.BLOCK_ANVIL_LAND, 3f, 0.5f);
        }

        void spearsTick() {
            t++;
            for (ItemDisplay s : spears) if (s.isValid()) s.teleport(s.getLocation().subtract(0, 1.6, 0));
            if (t == 6) { // everyone in the box: dead, totem or not
                for (Player p : world.getPlayers()) {
                    if (!survival(p) || !box.inside(p.getLocation())) continue;
                    impaled.add(p.getUniqueId());
                    p.setHealth(0);
                }
                world.playSound(home, org.bukkit.Sound.ENTITY_PLAYER_HURT, 3f, 0.6f);
            }
            if (t == 30) vanish(null);
        }

        // ---------- endings ----------
        void die() {
            if (state >= DYING) return;
            unmark();
            state = DYING; t = 0;
            stopMusic();
            if (timer != null) timer.removeAll();
            bar.removeAll();
            for (DonShot s : shots) s.display.remove();
            shots.clear();
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Don Lorenzo has been defeated!");
            say(ChatColor.WHITE + "...Heh. Not bad.", 60);
            rewards();
            Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (hitbox.isValid()) hitbox.setHealth(0); }); // counts as a kill for the Index
        }

        void dyingTick() {
            t++;
            if (t > 200) { cleanup(); return; } // safety net: the death animation always finishes
            pose = Pose.lerp(pose, kneelPose(ticks), 0.08f);
            if (t % 3 == 0) world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 12, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.6f));
            if (t >= 70) {
                world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 120, 0.6, 1, 0.6, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 2f));
                world.playSound(loc(), org.bukkit.Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.5f, 0.7f);
                cleanup();
            }
        }

        void vanish(String message) {
            if (state == LEAVING) return;
            if (message != null) Bukkit.broadcastMessage(message);
            state = LEAVING; t = 0;
            stopMusic();
        }

        void leavingTick() {
            if (t++ == 0) {
                world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 80, 0.5, 1, 0.5, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 2f));
                world.playSound(loc(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.8f);
            }
            if (t >= 10) cleanup();
        }

        void rewards() {
            Location at = home.clone().add(0, 1, 0);
            at.setY(groundAt(at.toVector()) + 0.5);
            String spot = "at:" + world.getName() + "," + at.getX() + "," + at.getY() + "," + at.getZ();
            int orbsXp = 0;
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                console("givemythicbag " + (int) ncfg("rewards.mythic-bags", 15) + " " + p.getName() + " " + spot, p, at);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, (int) ncfg("rewards.diamonds-min", 12) + random.nextInt((int) ncfg("rewards.diamonds-extra", 9))));
                if (random.nextDouble() < ncfg("rewards.ball-chance", 0.5)) dropLocked(at, p, lorenzosBall());
                orbsXp += (int) ncfg("rewards.xp", 1500);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Your loot dropped in the middle of the arena. Only you can pick it up.");
            }
            for (int left = orbsXp; left > 0; ) {
                int amount = Math.min(left, 100); left -= amount;
                world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(amount);
            }
        }

        void playMusic() {
            musicStart = System.currentTimeMillis();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > 64 * 64) continue;
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:don.music", SoundCategory.RECORDS, (float) ncfg("music.volume", 1.0), 1f);
                listeners.add(p.getUniqueId());
            }
        }

        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound("faultline:don.music", SoundCategory.RECORDS); }
            listeners.clear();
        }

        void cleanup() {
            unmark(); // never leave anyone slowed
            stopMusic();
            if (cutscene != null) cutscene.finish(false);
            if (box != null) box.restore();
            if (bar != null) bar.removeAll();
            if (timer != null) timer.removeAll();
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (sword != null && sword.isValid()) sword.remove();
            if (ball.isValid()) ball.remove();
            for (DonShot s : shots) s.display.remove();
            for (DonOrb o : orbs) { o.display.remove(); if (o.hitbox.isValid()) o.hitbox.remove(); }
            for (ItemDisplay s : spears) if (s.isValid()) s.remove();
            hitbox.remove(); // even if it already died
            state = LEAVING;
            if (don == this) don = null;
        }
    }

    // ---------- the cutscene (0:00 - 0:22) ----------
    private boolean allowTeleport; // our own teleports get through the anti-escape checks

    boolean inCutscene(Player p) {
        return don != null && don.cutscene != null && don.cutscene.viewers.contains(p.getUniqueId());
    }

    private java.io.File cutsceneFile() { return new java.io.File(getDataFolder(), "don_cutscene.yml"); }
    private java.io.File boxFile() { return new java.io.File(getDataFolder(), "don_box.yml"); }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    private final class Cutscene {
        final Don d;
        final ItemDisplay cam;
        final Set<UUID> viewers = new HashSet<>();
        final Set<UUID> shielded = new HashSet<>(); // Bedrock players: they can't follow the camera, so nothing can hurt them instead
        final org.bukkit.configuration.file.YamlConfiguration saved = new org.bukkit.configuration.file.YamlConfiguration();
        int k;

        Cutscene(Don d) {
            this.d = d;
            this.k = -(int) ncfg("music-offset-ticks", 0);
            this.cam = d.world.spawn(camAt(5, 1.6, 35, 0), ItemDisplay.class, c -> {
                c.setTeleportDuration(2); c.setPersistent(false); c.addScoreboardTag(DISPLAY_TAG);
            });
            for (Player p : d.world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(d.pos) > 40 * 40) continue;
                if (!survival(p) && !d.fighters.contains(p.getUniqueId())) continue; // creative players just watch
                d.join(p);
                if (bedrock(p)) { // Bedrock can't follow the camera: they stay put, and can't be hurt until 0:22
                    if (!p.isInvulnerable()) { p.setInvulnerable(true); shielded.add(p.getUniqueId()); }
                    continue;
                }
                Location l = p.getLocation();
                String key = p.getUniqueId().toString();
                saved.set(key + ".world", l.getWorld().getName());
                saved.set(key + ".x", l.getX()); saved.set(key + ".y", l.getY()); saved.set(key + ".z", l.getZ());
                saved.set(key + ".yaw", l.getYaw()); saved.set(key + ".pitch", l.getPitch());
                saved.set(key + ".gamemode", p.getGameMode().name());
                viewers.add(p.getUniqueId());
            }
            try { getDataFolder().mkdirs(); saved.save(cutsceneFile()); } catch (Exception e) { getLogger().warning("Couldn't save the cutscene backup: " + e); }
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.setFallDistance(0);
                allowTeleport = true;
                p.setGameMode(GameMode.SPECTATOR);
                p.teleport(cam.getLocation());
                p.setSpectatorTarget(cam);
                allowTeleport = false;
            }
            for (Player p : d.active()) d.bar.addPlayer(p);
            d.playMusic();
        }

        /** A camera spot around him: `ang` 0 = right in front of him, positive = to his left. */
        Location camAt(double dist, double height, double ang, double lookUp) {
            Vector fwd = new Vector(-Math.sin(Math.toRadians(d.yaw)), 0, Math.cos(Math.toRadians(d.yaw))).rotateAroundY(Math.toRadians(ang));
            Location at = d.pos.toLocation(d.world).add(fwd.multiply(dist)).add(0, height, 0);
            Location head = d.pos.toLocation(d.world).add(0, 1.6 * d.scale + lookUp, 0);
            Vector dir = head.toVector().subtract(at.toVector());
            if (dir.lengthSquared() > 0.001) at.setDirection(dir);
            return at;
        }

        Location blend(double t, double d0, double h0, double a0, double d1, double h1, double a1, double lookUp) {
            double s = smooth(t);
            return camAt(d0 + (d1 - d0) * s, h0 + (h1 - h0) * s, a0 + (a1 - a0) * s, lookUp);
        }

        double shake;                          // camera shake: kicked up on the big hits, then settles
        final java.util.Random rng = new java.util.Random();

        /** Types a line out letter by letter as a subtitle. */
        void type(String line, int since, int stayTicks) {
            int shown = Math.min(line.length(), since / 2 + 1);
            String text = line.substring(0, shown);
            for (Player p : d.world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(d.pos) > 70 * 70) continue;
                p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), legacy(ChatColor.WHITE + text),
                        Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(stayTicks * 50L), java.time.Duration.ofMillis(400))));
            }
            if (shown < line.length() && since % 2 == 0)
                for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.8f); }
        }

        /** A pulse of aura and cracked ground on a beat. */
        void pulse(Location root, int particles, double shakeAmount) {
            d.world.spawnParticle(Particle.DUST, root.clone().add(0, 1.1, 0), particles / 2, 0.7, 1.1, 0.7, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.4f));
            d.world.spawnParticle(Particle.BLOCK, root, particles / 3, 1.4, 0.05, 1.4, 0.05, root.clone().subtract(0, 1, 0).getBlock().getBlockData());
            shake = Math.max(shake, shakeAmount);
        }

        /** Over his shoulder, looking at the nearest fighter. */
        Location overShoulder() {
            Vector fwd = new Vector(-Math.sin(Math.toRadians(d.yaw)), 0, Math.cos(Math.toRadians(d.yaw)));
            Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
            Location at = d.pos.toLocation(d.world).subtract(fwd.clone().multiply(2.3)).add(right.multiply(-0.8)).add(0, 2.15 * d.scale, 0);
            // aim at where the nearest fighter was STANDING (during the cutscene, everyone's actually at the camera)
            Vector target = d.pos.clone().add(fwd.clone().multiply(6)).add(new Vector(0, 1.5, 0));
            double best = Double.MAX_VALUE;
            for (String key : saved.getKeys(false)) {
                Vector spot = new Vector(saved.getDouble(key + ".x"), saved.getDouble(key + ".y") + 1.5, saved.getDouble(key + ".z"));
                double dd = spot.distanceSquared(d.pos);
                if (dd < best && dd > 1) { best = dd; target = spot; }
            }
            at.setDirection(target.subtract(at.toVector()));
            return at;
        }

        void tick() {
            int prev = k;
            k = Math.max(k + 1, d.songTicks()); // the song's clock (catches up after a laggy tick)
            int t = Math.max(0, k);
            java.util.function.IntPredicate at = m -> prev < m && k >= m; // fires even if the exact tick was skipped
            Location root = d.loc();
            World w = d.world;
            // ================= the camera (cuts land on the song's hits) =================
            Location shot;
            if (t < 100) shot = blend(t / 100.0, 11, 0.4, 20, 5.5, 0.9, 10, -0.6);                              // 0-5s: creeping along the ground toward him
            else if (t < 216) shot = blend((t - 100) / 116.0, 6, 1.0, 10, 5, 2.4, 190, -0.5);                  // 5-10.8s: a slow orbit, rising
            else if (t < 280) shot = blend((t - 216) / 64.0, 3.4, 0.25, -20, 3.1, 0.3, -8, 0.1);               // 10.8s HIT: low angle as he rises
            else if (t < 300) shot = overShoulder();                                                          // 14.0s (loudest hit): over his shoulder, at you
            else if (t < 369) shot = blend((t - 300) / 69.0, 3, 6, 0, 1.5, 26, 200, -1.6);                     // 15-18.5s: spiraling up overhead
            else if (t < 391) shot = blend((t - 369) / 22.0, 4.5, 1.5, 25, 4.2, 1.5, 35, 0);                   // 18.5s: front shot, the juggling trick
            else if (t < 397) shot = blend((t - 391) / 6.0, 5, 1.7, 5, 1.45, 1.72, 0, 0);                      // 19.6s: snap zoom to his face
            else shot = blend((t - 397) / 48.0, 1.45, 1.72, 0, 1.3, 1.72, 0, 0);                             // slow creep in to the drop
            if (shake > 0.01) { // camera shake on the big hits
                shot.add((rng.nextDouble() - 0.5) * shake, (rng.nextDouble() - 0.5) * shake, (rng.nextDouble() - 0.5) * shake);
                shot.setYaw(shot.getYaw() + (float) ((rng.nextDouble() - 0.5) * shake * 8));
                shot.setPitch(shot.getPitch() + (float) ((rng.nextDouble() - 0.5) * shake * 6));
                shake *= 0.82;
            }
            cam.teleport(shot);
            for (UUID id : viewers) { // stay locked on the camera
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.getGameMode() == GameMode.SPECTATOR && !cam.equals(p.getSpectatorTarget())) p.setSpectatorTarget(cam);
            }
            // ================= Don =================
            d.lookAt = null; // his head follows whoever's closest (the camera, from where you're watching)
            if (t < 170) d.pose = kneelPose(d.ticks);                                                         // on one knee, breathing hard
            else if (t < 216) { d.pose = kneelPose(d.ticks); d.pose.r[HEAD][0] = 25 - 35 * (float) smooth((t - 170) / 46.0); } // his head slowly comes up
            else if (t < 262) d.pose = Pose.lerp(kneelPose(d.ticks), standPose(), (float) smooth((t - 216) / 46.0)); // he RISES on the hit
            else if (t < 280) d.pose = Pose.lerp(d.pose, powerPose(), 0.2f);
            else if (t < 300) d.pose = Pose.lerp(d.pose, pointPose(), 0.35f);                                // points right at you
            else if (t < 369) d.pose = Pose.lerp(d.pose, spreadPose(), 0.15f);                               // arms wide as the box draws itself
            else if (t < 391) d.pose = Pose.lerp(powerPose(), kickWindupPose(), (float) Math.abs(Math.sin((t - 369) * 0.45))); // a juggling trick
            else d.pose = Pose.lerp(d.pose, powerPose(), 0.3f);
            d.render();
            // ================= effects, on the beats =================
            Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.6f);
            if (t < 216 && t % 5 == 0) w.spawnParticle(Particle.WHITE_ASH, root.clone().add(0, 1, 0), 5, 0.5, 0.6, 0.5, 0.01);      // dust drifting off him
            if (t >= 170 && t < 216 && t % 4 == 0) w.spawnParticle(Particle.DUST, root.clone().add(0, 0.8, 0), 2, 0.3, 0.5, 0.3, 0, pink); // the first flickers
            if (t >= 216 && t % 2 == 0) w.spawnParticle(Particle.DUST, root.clone().add(0, 1.0, 0), Math.min(5, 2 + (t - 216) / 40), 0.45, 0.85, 0.45, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.0f)); // the aura (light, so you can see HIM)
            if (at.test(216)) { // the first big hit
                pulse(root, 150, 0.6);
                w.playSound(root, org.bukkit.Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.6f, 0.5f);
                w.playSound(root, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f);
            }
            for (int beat : new int[]{234, 247, 259}) if (at.test(beat)) pulse(root, 50, 0.22);                // each hit as he rises
            if (at.test(280)) { pulse(root, 90, 0.4); w.playSound(root, org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_1, 1.2f, 0.7f); }
            if (t >= 40 && t <= 90 && (t - 40) % 2 == 0) type("...Heh.", t - 40, 40);
            if (t >= 282 && t <= 340 && (t - 282) % 2 == 0) type("You really thought that was all?", t - 282, 50);
            // the box outline draws one side per beat
            int[] sides = {300, 321, 333, 346};
            int done = 0, last = 300;
            for (int m : sides) if (t >= m) { done++; last = m; }
            if (done > 0 && t % 2 == 0) drawOutline(Math.min(1.0, (done - 1 + Math.min(1.0, (t - last) / 8.0)) / 4.0));
            for (int m : sides) if (at.test(m)) { w.playSound(root, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.7f + 0.1f * done); shake = Math.max(shake, 0.12); }
            if (at.test(369)) { d.afterimage(); w.playSound(root, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.4f, 0.9f); d.ballTrail(); } // flicks the ball up
            if (at.test(391)) { // OVERTIME.
                for (Player p : w.getPlayers()) if (p.getLocation().toVector().distanceSquared(d.pos) < 70 * 70)
                    p.showTitle(Title.title(legacy(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "OVERTIME."), net.kyori.adventure.text.Component.empty(),
                            Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1600), java.time.Duration.ofMillis(200))));
                w.playSound(root, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.4f);
                w.spawnParticle(Particle.DUST, root.clone().add(0, 1.1, 0), 90, 1.4, 1.6, 1.4, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.8f));
                shake = 0.7;
            }
            if (at.test(415)) pulse(root, 60, 0.25);
            if (at.test(430)) { // the white flash, into the drop
                net.kyori.adventure.text.Component flash = net.kyori.adventure.text.Component.text("\ue900")
                        .font(net.kyori.adventure.key.Key.key("faultline", "cinematic")).color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
                for (Player p : w.getPlayers()) if (p.getLocation().toVector().distanceSquared(d.pos) < 70 * 70)
                    p.showTitle(Title.title(flash, net.kyori.adventure.text.Component.empty(),
                            Title.Times.times(java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(700))));
            }
            if (t >= T_FIGHT) { // 0:22: the beat drops
                finish(true);
                d.beginPhase2();
            }
        }

        void drawOutline(double progress) {
            double x0 = d.home.getBlockX() - 16, z0 = d.home.getBlockZ() - 16, len = 32;
            double y = d.floorY + 0.15;
            int total = 128, shown = (int) (total * progress);
            Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 80, 180), 1.8f);
            for (int i = 0; i < shown; i++) {
                double s = i / (double) total * 4 * len, x, z;
                if (s < len) { x = x0 + s; z = z0; }
                else if (s < 2 * len) { x = x0 + len; z = z0 + (s - len); }
                else if (s < 3 * len) { x = x0 + len - (s - 2 * len); z = z0 + len; }
                else { x = x0; z = z0 + len - (s - 3 * len); }
                d.world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, pink);
            }
        }

        /** Everyone back to exactly how they were: place, gamemode, no fall damage, no leftover speed. */
        void restore(Player p) {
            String key = p.getUniqueId().toString();
            if (!saved.contains(key + ".world")) return;
            World w = Bukkit.getWorld(saved.getString(key + ".world", ""));
            p.setSpectatorTarget(null);
            if (w != null) {
                Location back = new Location(w, saved.getDouble(key + ".x"), saved.getDouble(key + ".y"), saved.getDouble(key + ".z"),
                        (float) saved.getDouble(key + ".yaw"), (float) saved.getDouble(key + ".pitch"));
                allowTeleport = true; p.teleport(back); allowTeleport = false;
            }
            try { p.setGameMode(GameMode.valueOf(saved.getString(key + ".gamemode", "SURVIVAL"))); } catch (Exception e) { p.setGameMode(GameMode.SURVIVAL); }
            p.setFallDistance(0);
            p.setVelocity(new Vector());
            p.setFireTicks(0);
            saved.set(key, null);
            try { if (saved.getKeys(false).isEmpty()) cutsceneFile().delete(); else saved.save(cutsceneFile()); } catch (Exception ignored) { }
        }

        void finish(boolean normal) {
            for (UUID id : new ArrayList<>(viewers)) { Player p = Bukkit.getPlayer(id); if (p != null) restore(p); }
            for (UUID id : shielded) { Player p = Bukkit.getPlayer(id); if (p != null) p.setInvulnerable(false); }
            shielded.clear();
            viewers.clear();
            if (cam.isValid()) cam.remove();
            try { if (saved.getKeys(false).isEmpty()) cutsceneFile().delete(); else saved.save(cutsceneFile()); } catch (Exception ignored) { }
            d.cutscene = null;
        }
    }

    /** After a crash mid-cutscene: anyone stuck in spectator gets put back when they rejoin. */
    @EventHandler
    public void onCutsceneRejoin(PlayerJoinEvent event) {
        java.io.File f = cutsceneFile();
        if (!f.exists()) return;
        Player p = event.getPlayer();
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        String key = p.getUniqueId().toString();
        if (!y.contains(key + ".world") || inCutscene(p)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World w = Bukkit.getWorld(y.getString(key + ".world", ""));
            if (w != null) {
                allowTeleport = true;
                p.teleport(new Location(w, y.getDouble(key + ".x"), y.getDouble(key + ".y"), y.getDouble(key + ".z")));
                allowTeleport = false;
            }
            try { p.setGameMode(GameMode.valueOf(y.getString(key + ".gamemode", "SURVIVAL"))); } catch (Exception e) { p.setGameMode(GameMode.SURVIVAL); }
            p.setFallDistance(0);
            y.set(key, null);
            try { if (y.getKeys(false).isEmpty()) f.delete(); else y.save(f); } catch (Exception ignored) { }
        }, 5L);
    }

    @EventHandler
    public void onCutsceneQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (inCutscene(p)) { don.cutscene.restore(p); don.cutscene.viewers.remove(p.getUniqueId()); }
    }

    /** No dropping off the camera (Shift) and no spectator teleporting (number keys) during the cutscene. */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent event) {
        if (inCutscene(event.getPlayer()) || inJacobCut(event.getPlayer())) event.setCancelled(true);
    }

    // ---------- the box (32x32, walls + ceiling) ----------
    private final class Box {
        final Don d;
        final World world;
        final int x0, x1, z0, z1, y0, y1;
        final Map<String, org.bukkit.block.data.BlockData> saved = new LinkedHashMap<>();

        Box(Don d) {
            this.d = d; this.world = d.world;
            x0 = d.home.getBlockX() - 16; x1 = x0 + 31; z0 = d.home.getBlockZ() - 16; z1 = z0 + 31;
            y0 = d.floorY; y1 = d.floorY + 11;
        }

        boolean inside(Location l) {
            return l.getWorld().equals(world) && l.getX() >= x0 && l.getX() < x1 + 1 && l.getZ() >= z0 && l.getZ() < z1 + 1
                    && l.getY() >= y0 - 4 && l.getY() <= y1 + 1;
        }

        /** The open space between the walls (not the wall blocks themselves). */
        boolean interior(Location l) {
            return l.getWorld().equals(world) && l.getBlockX() > x0 && l.getBlockX() < x1 && l.getBlockZ() > z0 && l.getBlockZ() < z1
                    && l.getY() >= y0 - 1 && l.getY() < y1;
        }

        boolean shell(int x, int y, int z) {
            if (y == y1) return x >= x0 && x <= x1 && z >= z0 && z <= z1;
            return y >= y0 - 3 && y < y1 && (x == x0 || x == x1 || z == z0 || z == z1) && x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        void build() {
            Material mat = Material.matchMaterial(getConfig().getString("don.box-material", "PINK_STAINED_GLASS"));
            if (mat == null || !mat.isBlock()) mat = Material.PINK_STAINED_GLASS;
            for (int x = x0; x <= x1; x++) for (int y = y0 - 3; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                if (shell(x, y, z)) saved.put(x + "," + y + "," + z, world.getBlockAt(x, y, z).getBlockData());
            }
            // written to disk BEFORE anything changes, so a crash can always be undone
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            y.set("world", world.getName());
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, org.bukkit.block.data.BlockData> e : saved.entrySet()) entries.add(e.getKey() + "|" + e.getValue().getAsString());
            y.set("blocks", entries);
            try { getDataFolder().mkdirs(); y.save(boxFile()); } catch (Exception e) { getLogger().warning("Couldn't save the box backup: " + e); }
            for (String k : saved.keySet()) {
                String[] c = k.split(",");
                world.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])).setType(mat, false);
            }
            world.playSound(d.home, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
            world.playSound(d.home, org.bukkit.Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
            for (int i = 0; i < 64; i++) {
                double s = i / 64.0 * 128, x = x0 + (s % 32), z = s < 32 ? z0 : s < 64 ? z1 : z0 + (s % 32);
                world.spawnParticle(Particle.DUST, x, y0 + 1, z, 3, 0.2, 1, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 2f));
            }
        }

        void restore() {
            for (Map.Entry<String, org.bukkit.block.data.BlockData> e : saved.entrySet()) {
                String[] c = e.getKey().split(",");
                world.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])).setBlockData(e.getValue(), false);
            }
            saved.clear();
            boxFile().delete();
            if (d.box == this) d.box = null;
        }
    }

    /** On startup: if the server went down mid-fight, put back everything the box replaced. */
    private void restoreBoxFromFile() {
        java.io.File f = boxFile();
        if (!f.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) return;
        int n = 0;
        for (String entry : y.getStringList("blocks")) {
            try {
                String[] parts = entry.split("\\|", 2), c = parts[0].split(",");
                w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])).setBlockData(Bukkit.createBlockData(parts[1]), false);
                n++;
            } catch (Exception ignored) { }
        }
        f.delete();
        getLogger().info("Restored " + n + " blocks from a Don Lorenzo arena left over from a crash.");
    }

    // ---------- damage, talking, orbs ----------
    private Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player s) return s;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDonDamage(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(DON_TAG)) return;
        Player by = event instanceof EntityDamageByEntityEvent e ? playerFrom(e.getDamager()) : null;
        if (don == null || by == null || (don.state != PHASE1 && don.state != PHASE2)) {
            event.setCancelled(true);
            if (don != null && by != null && don.state == TALK) by.sendActionBar(legacy(ChatColor.GRAY + "Right-click to talk to him first."));
            return;
        }
        if (don.state == PHASE2 && don.guarding) { event.setCancelled(true); don.counter(by); return; }
        if (don.state == PHASE2 && don.dribbling && don.attack == 8) { // Zombie Dribble: i-frames (only while he's actually dribbling)
            event.setCancelled(true);
            by.playSound(by.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.8f);
            by.sendActionBar(legacy(ChatColor.DARK_PURPLE + "He's untouchable while he dribbles. Get out of his way!"));
            return;
        }
        double amount = event.getDamage();
        event.setDamage(0.001); // his real health is tracked separately (it can go past Minecraft's health cap)
        don.join(by);
        don.damage(amount, by);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDonHitboxDeath(EntityDeathEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(DON_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onDonTalk(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || don == null) return;
        Entity clicked = event.getRightClicked();
        boolean him = clicked.getScoreboardTags().contains(DON_TAG);
        if (!him && clicked.getScoreboardTags().contains(PROXY_TAG)) { // Bedrock players click his stand-in
            Proxy px = proxies.get(don.hitbox.getUniqueId());
            him = px != null && px.stand.equals(clicked);
        }
        if (!him) return;
        event.setCancelled(true);
        if (don.state == TALK) don.ask(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDonOrb(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(DON_ORB_TAG)) return;
        event.setCancelled(true);
        if (don == null || !(event instanceof EntityDamageByEntityEvent e)) return;
        Player by = playerFrom(e.getDamager());
        if (by == null) return;
        for (DonOrb o : don.orbs) if (o.hitbox.equals(event.getEntity())) { don.join(by); don.orbHit(o, by); return; }
    }

    // ---------- 3:05: no totem saves you ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onImpaledResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (don != null && event.getEntity() instanceof Player p && don.impaled.contains(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDonPlayerDeath(PlayerDeathEvent event) {
        if (don == null) return;
        Player p = event.getEntity();
        if (don.impaled.contains(p.getUniqueId())) event.deathMessage(legacy(p.getName() + " was impaled by Don Lorenzo's ceiling"));
        don.fighterDied(p);
    }

    // ---------- the box can't be broken, blown up, pushed, or escaped ----------
    private boolean inBox(Location l) { return don != null && don.box != null && don.box.inside(l); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxBreak(org.bukkit.event.block.BlockBreakEvent event) { if (inBox(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxPlace(org.bukkit.event.block.BlockPlaceEvent event) { if (inBox(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxBucket(org.bukkit.event.player.PlayerBucketEmptyEvent event) { if (inBox(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxBucketFill(org.bukkit.event.player.PlayerBucketFillEvent event) { if (inBox(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxExplode(org.bukkit.event.entity.EntityExplodeEvent event) { event.blockList().removeIf(b -> inBox(b.getLocation())); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) { event.blockList().removeIf(b -> inBox(b.getLocation())); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxPistonOut(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if (inBox(event.getBlock().getLocation()) || event.getBlocks().stream().anyMatch(b -> inBox(b.getLocation()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxPistonIn(org.bukkit.event.block.BlockPistonRetractEvent event) {
        if (inBox(event.getBlock().getLocation()) || event.getBlocks().stream().anyMatch(b -> inBox(b.getLocation()))) event.setCancelled(true);
    }

    /** No pearls, chorus fruit, /home, /tpa, /spawn out of the box (or into it), and no teleporting in the cutscene. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (allowTeleport || don == null) return;
        Player p = event.getPlayer();
        if (inCutscene(p)) { event.setCancelled(true); return; }
        if (don.box == null || event.getTo() == null) return;
        boolean fromIn = don.box.inside(event.getFrom()), toIn = don.box.inside(event.getTo());
        if (fromIn && !toIn && survival(p)) { event.setCancelled(true); p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "There's no escaping the box.")); }
        else if (!fromIn && toIn && survival(p)) event.setCancelled(true);
    }

    // ===================== REWARDS & COMMAND =====================

    /** Drops an item at a spot, bursting outward, pick-up-able only by `owner`. */
    private void dropLocked(Location at, Player owner, ItemStack item) {
        org.bukkit.entity.Item drop = at.getWorld().dropItem(at, item);
        drop.setOwner(owner.getUniqueId());
        drop.setVelocity(new Vector(random.nextGaussian() * 0.15, 0.3 + random.nextDouble() * 0.2, random.nextGaussian() * 0.15));
    }

    private void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    /** Mythic Bags come from FaultlineItems, Zombie Omens from FaultlineRaids (the real items). */
    private void console(String command, Player p) {
        console(command, p, null);
    }

    private void console(String command, Player p, Location dropAt) {
        boolean index = command.startsWith("index ");
        String pluginName = command.startsWith("zraid") ? "FaultlineRaids" : index ? "FaultlineIndex" : "FaultlineItems";
        Plugin other = Bukkit.getPluginManager().getPlugin(pluginName);
        boolean ok = other != null && other.isEnabled() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!ok && index) return; // just an Index unlock: nothing to make up for
        if (!ok) {
            getLogger().warning("Couldn't run '" + command + "' (is " + pluginName + " installed?) — gave diamonds instead.");
            if (dropAt != null) dropLocked(dropAt, p, new ItemStack(Material.DIAMOND, 4));
            else give(p, new ItemStack(Material.DIAMOND, 4));
        }
    }

    private class BossCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("bosses.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            switch (sub) {
                case "summon" -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Only players can summon it.");
                        return true;
                    }
                    if (eye != null) {
                        sender.sendMessage(ChatColor.RED + "The Demon Eye is already here.");
                        return true;
                    }
                    if (summon(p)) sender.sendMessage(ChatColor.GRAY + "(Tip: fight it in survival — creative players don't count as fighters.)");
                }
                case "kill" -> {
                    if (eye == null) {
                        sender.sendMessage(ChatColor.GRAY + "No Demon Eye is active.");
                        return true;
                    }
                    eye.removeEverything();
                    eye = null;
                    sender.sendMessage(ChatColor.GREEN + "Removed the Demon Eye.");
                }
                case "give" -> {
                    int amount = 1;
                    Player target = sender instanceof Player p ? p : null;
                    for (int i = 1; i < args.length; i++) {
                        Player online = Bukkit.getPlayerExact(args[i]);
                        if (online != null) target = online;
                        else try {
                            amount = Math.max(1, Math.min(64, Integer.parseInt(args[i])));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Usage: /demoneye give [amount] [player]");
                        return true;
                    }
                    ItemStack item = summonItem();
                    item.setAmount(amount);
                    give(target, item);
                    sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + amount + "x Suspicious Eye.");
                }
                case "item" -> {
                    String which = args.length > 1 ? args[1].toLowerCase() : "";
                    int amount = 1;
                    Player target = sender instanceof Player p ? p : null;
                    for (int i = 2; i < args.length; i++) {
                        Player online = Bukkit.getPlayerExact(args[i]);
                        if (online != null) target = online;
                        else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                    }
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Usage: /demoneye item <staff|shield|arrows> [amount] [player]");
                        return true;
                    }
                    ItemStack item = switch (which) {
                        case "staff" -> twinEyeStaff();
                        case "shield" -> eyeShield();
                        case "arrows" -> unholyArrows(amount);
                        default -> null;
                    };
                    if (item == null) {
                        sender.sendMessage(ChatColor.RED + "Usage: /demoneye item <staff|shield|arrows> [amount] [player]");
                        return true;
                    }
                    give(target, item);
                    sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + item.getItemMeta().getDisplayName() + ChatColor.GREEN + ".");
                }
                default -> sender.sendMessage(ChatColor.YELLOW + "/demoneye summon | kill | give [amount] [player] | item <staff|shield|arrows> [amount] [player]");
            }
            return true;
        }
    }
}
