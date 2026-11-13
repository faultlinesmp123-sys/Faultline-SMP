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
 * FAULTLINE BOSSES. Since 2.0.0 only Diamond Jacob is left on land (he drops the Mirror, the way to the Dark Ocean),
 * plus the Kraken (only in the Dark Ocean: the Abyssal Kraken) and the Dark Ocean's bosses (Wild.java + DeepBosses.java).
 * The other land bosses, the Boss Rush and the Night of Terrors were removed; their leftovers are swept on startup
 * (OLD_TAGS) and their items are taken from players by FaultlineItems' OldGear.
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
    /** 2.0.0: tags of the removed land bosses (Frostbeard, the Twin Eye staff, the Dune worm, vultures, Don, Rocco, Grimtusk, the
     *  land wild bosses): swept on startup so nothing they left behind hangs in a world. */
    static final List<String> OLD_TAGS = List.of("faultline_mortimer", "faultline_icicle_runner", "faultline_mortimer_orb", "faultline_twin_eye",
            "faultline_dune", "faultline_dune_segment", "faultline_vulture", "faultline_vulture_wild", "faultline_don", "faultline_don_orb",
            "faultline_rocco", "faultline_werner", "faultline_vendetta", "faultline_grimtusk", "faultline_ironhide", "faultline_leviathan_bile",
            "faultline_lich_bolt", "faultline_soul_bolt", "faultline_wild_boulder", "faultline_lich_crystal");
    private static final UUID PACK_ID = UUID.fromString("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");

    final Random random = new Random();
    final BedrockFx bedrockFx = new BedrockFx(this); // boss moves drawn in particles for Bedrock players
    private boolean dealing; // true only while the boss deals its own damage
    DeathMessages deathMessages; // 1.5.8: "was frozen solid by Frostbeard" instead of "was slain by Slime"

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig(); // runs the config repair before anything reads the config
        registerJacobRecipe();
        getCommand("bossmorph").setExecutor(this::morphCommand);
        getCommand("bossmorph").setTabCompleter((sender, command, label, args) ->
                args.length == 1 ? MORPH_KINDS.stream().filter(k -> k.startsWith(args[0].toLowerCase())).toList() : List.of());
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
                    if (!colossalWorld(self.getWorld()) && !krakenHere(self.getWorld())) { sender.sendMessage(ChatColor.RED + "Not in this world (the Overworld's seas or the Dark Ocean)."); return true; }
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
                default -> sender.sendMessage(ChatColor.YELLOW + "/kraken <summon|kill|phase <2|3>|bubble|worm>" + ChatColor.GRAY + " (Overworld water = the Kraken, the Dark Ocean = the Abyssal Kraken)");
            }
            return true;
        });
        for (String old : List.of("dune_idol_recipe", "suspicious_eye", "twin_eye_staff", "demon_eye_shield", "unholy_arrow")) // 2.0.0: the land bosses' recipes
            Bukkit.removeRecipe(new NamespacedKey(this, old));
        removeLeftovers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(bedrockFx, this);
        deathMessages = new DeathMessages(this);
        getServer().getPluginManager().registerEvents(deathMessages, this);
        wild = new Wild(this);
        getServer().getPluginManager().registerEvents(wild, this);
        for (String kind : Wild.KINDS) {
            String cmd = kind;
            getCommand(cmd).setExecutor((sender, command, label, args) -> wild.command(kind, sender, args));
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            // Each boss updates on its own: an error in one can't freeze the others, and the error is
            // written to the console (at most every 30s per boss) so it can be tracked down.
            safely("Kraken", () -> { if (kraken != null) kraken.tick(); });
            safely("Diamond Jacob", () -> { if (jacob != null) jacob.tick(); });
            safely("Wild bosses", () -> { if (wild != null) wild.tick(); });
            safely("Bedrock moves", bedrockFx::tick);
            safely("Boss form", this::morphTick);
            safely("Kraken bait", this::baitTick);
            safely("Bedrock stand-ins", this::proxyTick);
        }, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> safely("Boss music", this::quietVanilla), 40L, 40L);
        getLogger().info("Faultline Bosses enabled.");
    }

    @Override
    public void onDisable() {
        MUSIC_HEARD.clear();
        safely("shutdown: boss form", () -> { if (morph != null) endMorph(false, ChatColor.GRAY + "The server is stopping: you're yourself again."); });
        // BUG FIX: these ran in a row, so ONE error left every boss after it frozen in the world
        // (and still glowing). Each runs on its own now, and a final sweep removes anything left.
        safely("shutdown: Bedrock mirrors", bedrockFx::clearMirrors);
        safely("shutdown: stand-ins", () -> { for (Proxy px : proxies.values()) if (px.stand.isValid()) px.stand.remove(); });
        safely("shutdown: leftovers", this::removeLeftovers);
        safely("shutdown: Kraken", () -> { if (kraken != null) kraken.removeEverything(); });
        safely("shutdown: Diamond Jacob", () -> { if (jacob != null) jacob.removeEverything(); });
        safely("shutdown: Wild bosses", () -> { if (wild != null) wild.shutdown(); });
        kraken = null; jacob = null;
        proxies.clear();
    }

    private double cfg(String key, double def) {
        return getConfig().getDouble("bosses." + key, getConfig().getDouble("demon-eye." + key, def)); // 2.0.0: was under demon-eye
    }

    static boolean survival(Player p) {
        // an admin playing a boss (/bossmorph) is never a fighter: no boss targets, hurts, or films them
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !MORPHED.contains(p.getUniqueId());
    }

    private static boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    private void removeLeftovers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                Set<String> tags = e.getScoreboardTags();
                // (2.0.0: the removed land bosses' tags are still swept, so nothing of theirs is left in a world)
                if (tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG) || tags.contains(DISPLAY_TAG) || OLD_TAGS.stream().anyMatch(tags::contains)
                        || tags.contains(PROXY_TAG) || tags.contains(KRAKEN_TAG) || tags.contains(KRAKEN_TENT_TAG)
                        || tags.contains(KRAKEN_PINK_TAG) || tags.contains(KRAKEN_EEL_TAG) || tags.contains(KRAKEN_MINION_TAG)
                        || tags.contains(JACOB_TAG) || tags.contains(JACOB_BIRD_TAG) || tags.contains(JACOB_ORB_TAG) || tags.contains(JACOB_MINION_TAG)
                        || Wild.ours(e)) e.remove();
            }
        }
    }

    static net.kyori.adventure.text.Component legacy(String text) {
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
    Quaternionf facing(float roll) {
        Quaternionf q = new Quaternionf();
        if (getConfig().getBoolean("bosses.model-flip", getConfig().getBoolean("demon-eye.model-flip", true))) q.rotateY((float) Math.PI);
        return q.rotateZ(roll);
    }

    /** An item whose look comes from the FaultlineBosses resource pack. */
    static ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", model));
        item.setItemMeta(meta);
        return item;
    }

    ItemDisplay spawnDisplay(Location at, String model, float scale, int teleportTicks, Display.Billboard billboard) {
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

    // ===================== EVENTS: sleep, hits, deaths =====================

    @EventHandler(ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        // BUG FIX: Frostbeard's size-3 hitbox split into normal (visible) slimes when he died.
        // Every Faultline hitbox is tagged "faultline_...", so none of them can ever split.
        // (through EntityEvent: SlimeSplitEvent.getEntity()'s return type differs between API versions -> NoSuchMethodError)
        if (((org.bukkit.event.entity.EntityEvent) event).getEntity().getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_"))) event.setCancelled(true);
    }

    /** Slimes damage whoever touches them (even without AI); the hitbox only hurts through real attacks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTouchDamage(EntityDamageByEntityEvent event) {
        Set<String> tags = event.getDamager().getScoreboardTags();
        if (!(tags.contains(KRAKEN_TAG) || tags.contains(KRAKEN_TENT_TAG)
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
    void hurt(Player p, double amount, Entity source, Vector from, Guard guard) {
        if (source != null && source.getScoreboardTags().contains(KRAKEN_TAG)) amount *= kcfg("damage-multiplier", 1.2) / Math.max(0.01, cfg("damage-multiplier", 1.0)); // his own multiplier
        if (source != null && source.getScoreboardTags().contains(KRAKEN_ABYSSAL_TAG)) amount *= kcfg("abyssal.damage-multiplier", 1.5); // the Abyssal Kraken hits harder still
        if (source != null && source.getScoreboardTags().contains(JACOB_TAG)) amount *= jcfg("damage-multiplier", 1.0) / Math.max(0.01, cfg("damage-multiplier", 1.0));
        amount *= cfg("damage-multiplier", 1.0);
        if (guard != Guard.UNBLOCKABLE && facingBlock(p, from)) {
            shieldHit(p, amount, guard == Guard.HEAVY);
            if (guard == Guard.BLOCKABLE) return;
            amount *= 0.5;
        }
        if (deathMessages != null) deathMessages.note(p, source);
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

    // ===================== HITBOXES =====================

    /**
     * BUG FIX: the plain "invisible" setting gets undone by Minecraft whenever a potion effect
     * is added to or wears off the mob (it re-checks for a real Invisibility effect). A Frost
     * Flare hit, a splash potion, or a Spectral Arrow made the green hitbox slime appear (glowing
     * slimes even draw their shell while invisible). A real, permanent, hidden Invisibility effect
     * survives that re-check; onHiddenEffect below keeps every other effect off.
     */
    static void hideForGood(LivingEntity e) {
        e.setInvisible(true);
        e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    /**
     * BUG FIX: bosses only take damage a PLAYER caused (their hits, arrows, thrown potions, TNT they lit, clouds they
     * made, thorns, bed/anchor blasts, a Necromancer Staff minion). Before, Iron Golems (they attack slimes on sight),
     * creepers and skeletons could hurt the bosses too, so luring a worm into a village killed it for free loot.
     */
    static boolean byPlayer(EntityDamageEvent e) {
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
        return t.contains(KRAKEN_TAG) || t.contains(KRAKEN_TENT_TAG) || t.contains(KRAKEN_PINK_TAG) || t.contains(KRAKEN_EEL_TAG)
                || t.contains(JACOB_TAG) || t.contains(JACOB_BIRD_TAG) || t.contains(JACOB_ORB_TAG);
    }

    /** Hitboxes and Icicle Runners take no potion effects (no Glowing, Slowness, Poison...), and never lose Invisibility. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHiddenEffect(org.bukkit.event.entity.EntityPotionEffectEvent event) {
        // BUG FIX: called through EntityEvent. EntityPotionEffectEvent.getEntity() returns LivingEntity in newer APIs and
        // Entity in older ones; a jar built against the wrong one threw NoSuchMethodError here, so milk and potions could
        // strip a boss hitbox's invisibility and Java players saw plain slimes inside the bosses.
        if (!hiddenBody(((org.bukkit.event.entity.EntityEvent) event).getEntity())) return;
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

    void setupHitbox(Slime slime, double health, String tag, String name) {
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
    /**
     * A cinematic full-screen black (or white) frame. Java players get the cinematic font's full-screen glyph; Bedrock
     * players can't draw Java fonts (they'd see a broken box), so a black frame is Blindness for as long, and the
     * subtitle still shows.
     */
    static void cinematic(Player p, boolean white, net.kyori.adventure.text.Component subtitle, int inT, int stayT, int outT) {
        Title.Times times = Title.Times.times(java.time.Duration.ofMillis(inT * 50L), java.time.Duration.ofMillis(stayT * 50L), java.time.Duration.ofMillis(outT * 50L));
        if (bedrock(p)) {
            if (!white) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, inT + stayT + outT / 2 + 1, 0, false, false, false));
            if (subtitle != null && !subtitle.equals(net.kyori.adventure.text.Component.empty()))
                p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), subtitle, times));
            return;
        }
        net.kyori.adventure.text.Component b = net.kyori.adventure.text.Component.text(white ? "\ue900" : "\ue901").font(net.kyori.adventure.key.Key.key("faultline", "cinematic"));
        if (white) b = b.color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        p.showTitle(Title.title(b, subtitle == null ? net.kyori.adventure.text.Component.empty() : subtitle, times));
    }

    static boolean bedrock(Player p) {
        return Edition.bedrock(p);
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
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null) return;
        // BUG FIX: the stand-in used to spawn visible to everyone and was hidden from Java players a moment later, and a
        // Java player who wasn't hidden from it in time (or came into range from elsewhere) could see a plain zombie or
        // vindicator standing inside Don, Jacob or Rocco. It's now invisible to everyone from the moment it exists, and
        // only Bedrock players are shown it.
        java.util.function.Consumer<Entity> init = e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setSilent(true);
            if (e instanceof org.bukkit.entity.Mob mob) mob.setAI(false);
            if (e instanceof Zombie z) { z.setShouldBurnInDay(false); z.setCanPickupItems(false); }
            if (e instanceof LivingEntity le) { le.getEquipment().clear(); le.setCanPickupItems(false); }
        };
        Entity raw = anchor.getWorld().spawn(anchor.getLocation(), cls, init);
        if (!(raw instanceof LivingEntity stand)) { raw.remove(); return; }
        for (Player p : Bukkit.getOnlinePlayers()) if (bedrock(p)) p.showEntity(this, stand); // only Bedrock players see it
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

    /** The real thing behind a Bedrock stand-in (null if it isn't one): a click on the stand-in counts as a click on it. */
    LivingEntity proxyAnchorOf(Entity stand) {
        if (stand == null || !stand.getScoreboardTags().contains(PROXY_TAG)) return null;
        for (Proxy px : proxies.values()) if (px.stand.equals(stand)) return px.anchor;
        return null;
    }

    /** The Bedrock stand-in for this anchor, if it has one. */
    LivingEntity proxyStandOf(LivingEntity anchor) {
        Proxy px = anchor == null ? null : proxies.get(anchor.getUniqueId());
        return px == null ? null : px.stand;
    }

    /** Every tick: stand-ins follow their hitboxes (feet to feet) and face where the model faces. */
    private void proxyTick() {
        if (++proxyTicks % 40 == 0) { // safety net: Java players never see a Bedrock stand-in
            for (Player p : Bukkit.getOnlinePlayers()) for (Proxy px : proxies.values()) {
                if (!px.stand.isValid()) continue;
                if (bedrock(p)) { if (!p.canSee(px.stand)) p.showEntity(this, px.stand); }
                else if (p.canSee(px.stand)) p.hideEntity(this, px.stand);
            }
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
        for (Proxy px : proxies.values()) if (px.stand.isValid()) { if (bedrock(p)) p.showEntity(this, px.stand); else p.hideEntity(this, px.stand); }
    }

    /** Projectiles are item displays too: give Bedrock players a trail to see (and dodge). */
    void bedrockTrail(Location at, Color color, float size) {
        for (Player p : at.getWorld().getPlayers()) {
            if (bedrock(p) && p.getLocation().distanceSquared(at) < 48 * 48) {
                p.spawnParticle(Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(color, size));
            }
        }
    }

    // =====================================================================================================
    //  THE KRAKEN: drop a Bait Worm into a Deep Ocean. 2,200 health, 30% defense, 3 phases, 11 moves.
    // =====================================================================================================
    static final String KRAKEN_TAG = "faultline_kraken", KRAKEN_TENT_TAG = "faultline_kraken_tentacle",
            KRAKEN_PINK_TAG = "faultline_kraken_pink", KRAKEN_EEL_TAG = "faultline_kraken_eel", KRAKEN_MINION_TAG = "faultline_kraken_minion",
            KRAKEN_SEA_TAG = "faultline_kraken_sea", // the Kraken at Sea (fought from a ship)
            KRAKEN_ABYSSAL_TAG = "faultline_kraken_abyssal"; // the Abyssal Kraken (the Dark Ocean: 10x his size)
    private Kraken kraken;
    private boolean krakenNormalOnly; // boss form: no Abyssal Kraken
    private long krakenCooldownUntil;
    private final Map<UUID, Sink> sinking = new HashMap<>();
    private static final org.bukkit.NamespacedKey WORM_KEY = new org.bukkit.NamespacedKey("faultlineitems", "kraken_worm");
    private static final org.bukkit.NamespacedKey COUNTERS_KEY = new org.bukkit.NamespacedKey("faultlineitems", "kraken_counters");
    private static final org.bukkit.NamespacedKey DIVING_KEY = new org.bukkit.NamespacedKey("faultlineitems", "diving_tier");

    private double kcfg(String path, double def) { return getConfig().getDouble("kraken." + path, def); }

    /** Bosses 2.0.1: the Abyssal Kraken is smaller (players lagged). A server config still holding 1.9.0's default reads as the new one. */
    private double acfg(String path, double old, double now) {
        double v = getConfig().getDouble("kraken.abyssal." + path, now);
        return Math.abs(v - old) < 1e-9 ? now : v;
    }

    /** The Dark Ocean (FaultlineDeep's world): a Kraken woken there is the Abyssal Kraken, ten times his size. */
    boolean colossalWorld(World w) {
        return w != null && getConfig().getBoolean("kraken.abyssal.enabled", true)
                && w.getName().equals(getConfig().getString("kraken.abyssal.world", "faultline_deep"));
    }

    /** 2.0.2: the normal Kraken lives in the Overworld's deep oceans again (kraken.overworld). */
    boolean krakenHere(World w) {
        return w != null && w.getEnvironment() == World.Environment.NORMAL && !colossalWorld(w) && getConfig().getBoolean("kraken.overworld", true);
    }

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
        ShipLink.Vessel vessel; // dropped over the side of a ship or boat: the Kraken at Sea
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
        Sink sink = new Sink(event.getItemDrop(), event.getPlayer().getUniqueId());
        // (2.0.0: no Kraken at Sea any more: only the Abyssal Kraken, in the Dark Ocean)
        sinking.put(event.getItemDrop().getUniqueId(), sink);
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
                boolean abyss = colossalWorld(item.getWorld()); // the Dark Ocean: the Abyssal Kraken, in any open water deep enough
                int need = (int) (abyss ? Math.min(63, kcfg("abyssal.min-depth", 60)) : kcfg("min-depth", 15));
                if (!abyss && (!krakenHere(item.getWorld()) || !deepOcean(item.getLocation()))) why = "It needs a Deep Ocean (or the Dark Ocean)."; // 2.0.2: the normal Kraken is back
                else if (waterDepth(item.getLocation()) < need) why = "The water isn't deep enough here (it needs " + need + "+ blocks).";
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
            Safe.vel(item, new Vector(wiggle, -0.06, Math.cos(k * 0.4) * 0.05)); // sinks, wriggling
            if (k % 3 == 0) l.getWorld().spawnParticle(Particle.BUBBLE, l.clone().add(0, 0.3, 0), 3, 0.1, 0.1, 0.1, 0.02);
            if (k % 40 == 0) l.getWorld().playSound(l, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.8f, 0.6f);
            if (k >= (int) kcfg("sink-ticks", 160)) { // 8 seconds: it's gone... and then he comes
                it.remove();
                Location at = l.clone();
                ItemStack worm = item.getItemStack().clone();
                item.remove();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    // BUG FIX: the worm was already gone, so if he couldn't come (an error, or a Kraken already out by
                    // then) the bait was simply lost. Now it comes back.
                    Kraken before = kraken;
                    try { summonKraken(at, by); } catch (RuntimeException ex) { getLogger().log(Level.WARNING, "Couldn't summon the Kraken", ex); }
                    if (kraken == null || kraken == before) {
                        if (by != null && by.isOnline()) { give(by, worm); by.sendActionBar(legacy(ChatColor.DARK_AQUA + "Nothing came up. " + ChatColor.GRAY + "(You got the worm back.)")); }
                        else at.getWorld().dropItemNaturally(at, worm);
                    }
                }, 30L);
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
        boolean abyss = colossalWorld(w);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > (abyss ? 240 * 240 : 96 * 96)) continue;
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.45f);
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.6f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.5f);
            p.showTitle(Title.title(legacy(abyss ? ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "THE ABYSSAL KRAKEN" : ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "THE KRAKEN APPEARS"),
                    legacy(ChatColor.GRAY + (abyss ? "Something the size of a mountain rises..." : "from the depths...")),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(900))));
            Vector shake = new Vector(random.nextGaussian() * 0.25, 0.15, random.nextGaussian() * 0.25); // the water shakes
            if (survival(p)) Safe.vel(p, p.getVelocity().add(shake));
        }
        kraken = new Kraken(at, by);
        Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + kraken.title() + " " + ChatColor.GRAY + "has risen from the " + (abyss ? "Dark Ocean" : "deep") + (by != null ? " (summoned by " + by.getName() + ")" : "") + "!");
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
        // ---- the Kraken at Sea: risen beside a ship or boat, head out of the water. Keep yourself AND your ship alive.
        final ShipLink.Vessel vessel;
        final boolean atSea;
        boolean surgeHitShip, biteHitShip;
        // ---- the Abyssal Kraken (Bosses 1.9.0): woken in the Dark Ocean, ten times his size and harder. k = how many times
        // bigger (1 for the normal Kraken, so nothing changes for him), hs = how much bigger his hit areas get (smaller than
        // k, so you can still swim out of them), ms = how much faster he moves (to cross his own size).
        final boolean colossal;
        final double k, hs, ms;
        final Map<UUID, Integer> touched = new HashMap<>();
        final List<Vector> storm = new ArrayList<>(); int stormT;
        double far(double d) { return colossal ? d * acfg("reach-scale", 2.5, 1.6) : d; }
        double bodyR(double d) { return colossal ? d * k * 0.6 : d; }

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
                tip.setSize(colossal ? (int) Math.min(40, Math.round(3 * hs * 2)) : 3);
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
                if (tip != null && tip.isValid()) tip.teleport(p[SEGS].toLocation(world).subtract(0, colossal ? tip.getSize() * 0.26 : 0.8, 0));
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
                d = spawnDisplay(at.toLocation(world), "kraken_air_bubble", (float) (kcfg("air-bubble-scale", 3.0) * hs), 2, Display.Billboard.FIXED);
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
                d = spawnDisplay(at.toLocation(world), "kraken_pink_bubble", colossal ? 4f : 2.6f, 2, Display.Billboard.FIXED);
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
                d = spawnDisplay(from.toLocation(world), "kraken_barnacle", (float) (1.3 * hs), 1, Display.Billboard.FIXED);
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

        Kraken(Location at, Player by) { this(at, by, null); }

        Kraken(Location at, Player by, ShipLink.Vessel vessel) {
            world = at.getWorld();
            this.vessel = vessel;
            atSea = vessel != null;
            colossal = !atSea && !krakenNormalOnly && colossalWorld(world);
            scale = (float) (colossal ? acfg("scale", 40.0, 14.0) : kcfg("scale", 4.0));
            k = colossal ? scale / Math.max(0.5, kcfg("scale", 4.0)) : 1;
            hs = colossal ? acfg("hit-scale", 2.2, 1.5) : 1;
            ms = colossal ? acfg("move-scale", 2.2, 1.4) : 1;
            maxHp = atSea ? kcfg("sea.health", 1600) : colossal ? kcfg("abyssal.health", 9000) : kcfg("health", 2200); hp = maxHp; defense = kcfg("defense", 0.3);
            double floor = seabedY(at.getX(), at.getZ(), at.getY());
            home = atSea ? new Location(world, at.getX(), surfaceY(at.getX(), at.getZ(), at.getY()) + kcfg("sea.rise", 1.2) * scale / 4, at.getZ())
                         : new Location(world, at.getX(), Math.max(floor + 9 * scale / 4, at.getY() - 4), at.getZ());
            if (!atSea) { // 2.0.1: his home must fit his whole head (a summon beside a cliff put him half in it)
                pos = home.toVector();
                if (!fits(pos, headR())) { Vector f = openWater(headR()); if (f != null) { home.setX(f.getX()); home.setY(f.getY()); home.setZ(f.getZ()); } }
            }
            pos = home.toVector().add(new Vector(0, -14 * k, 0)); // starts deep below, and rises
            if (by != null) {
                Vector to = by.getLocation().toVector().subtract(pos);
                yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                fighters.add(by.getUniqueId());
            }
            hitbox = (Slime) world.spawnEntity(pos.toLocation(world), EntityType.SLIME, false);
            hitbox.setSize((int) Math.min(127, Math.max(4, Math.round(11 * scale / 4)))); // about 5.7 blocks: matches his 7-block-wide head
            setupHitbox(hitbox, 1000, KRAKEN_TAG, "The Kraken");
            if (atSea) hitbox.addScoreboardTag(KRAKEN_SEA_TAG);
            if (colossal) hitbox.addScoreboardTag(KRAKEN_ABYSSAL_TAG);
            mantle = spawnDisplay(pos.toLocation(world), "kraken_mantle_1", scale, 2, Display.Billboard.FIXED);
            beakTop = spawnDisplay(pos.toLocation(world), "kraken_beak", scale * 0.55f, 2, Display.Billboard.FIXED);
            beakBottom = spawnDisplay(pos.toLocation(world), "kraken_beak", scale * 0.5f, 2, Display.Billboard.FIXED);
            pupilL = spawnDisplay(pos.toLocation(world), "kraken_pupil", scale * 0.55f, 2, Display.Billboard.FIXED);
            pupilR = spawnDisplay(pos.toLocation(world), "kraken_pupil", scale * 0.55f, 2, Display.Billboard.FIXED);
            for (ItemDisplay d : new ItemDisplay[]{mantle, beakTop, beakBottom, pupilL, pupilR}) d.setViewRange(8f);
            for (int i = 0; i < ARMS; i++) arms[i] = new Arm(i);
            for (Arm a : arms) for (ItemDisplay d : a.segs) if (d != null) d.setViewRange(8f);
            if (colossal) { // big displays are culled by a box at their origin: give them one their size (or they blink out)
                for (ItemDisplay d : new ItemDisplay[]{mantle, beakTop, beakBottom, pupilL, pupilR}) { d.setViewRange(6f); d.setDisplayWidth(scale * 2.5f); d.setDisplayHeight(scale * 2.5f); }
                for (Arm a : arms) for (ItemDisplay d : a.segs) if (d != null) { d.setViewRange(5f); d.setDisplayWidth(scale); d.setDisplayHeight(scale); }
            }
            proxy(hitbox, mantle, EntityType.GLOW_SQUID, colossal ? 8 : kcfg("bedrock.squid-scale", 10)); // Bedrock: a giant glow squid
            bar = Bukkit.createBossBar(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + title(), colossal ? BarColor.PURPLE : BarColor.BLUE, BarStyle.SEGMENTED_6);
            pinkTimer = (int) kcfg("pink-bubble-seconds", 15) * 20;
            playMusic();
        }

        // ---------- helpers ----------
        String title() { return colossal ? "The Abyssal Kraken" : "The Kraken"; }

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
                if (p.getLocation().toVector().distanceSquared(pos) > far(64) * far(64)) continue;
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
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < far(80) * far(80)) p.sendActionBar(legacy(text));
        }

        // ---------- the update ----------
        void tick() {
            ticks++;
            if (!hitbox.isValid() && !dying && !leaving) { removeEverything(); kraken = null; return; } // his area unloaded
            if (hitbox.getHealth() < 900 && !dying) hitbox.setHealth(1000);
            if (dying) { bossPart("Kraken", "death", this::deathTick); if (kraken != this) return; renderAll(); return; }
            if (leaving) { bossPart("Kraken", "leaving", this::leaveTick); if (kraken != this) return; renderAll(); return; }
            for (Player p : world.getPlayers()) if (survival(p) && !p.isDead() && p.getLocation().toVector().distanceSquared(pos) < far(48) * far(48)) join(p);
            List<Player> a = active();
            if (atSea && intro <= 0 && !vessel.alive()) { lostAtSea(); return; } // the ship's gone under: he's won
            if (a.isEmpty() && pilot(this) == null) {
                if (++lonely > 400) { leave(ChatColor.DARK_AQUA + "The Kraken sinks back into the abyss..."); return; }
            } else lonely = 0;
            try {
                if (intro > 0) introTick();
                else if (transition > 0) transitionTick();
                else if (!a.isEmpty() || pilot(this) != null) attackTick(a);
                failStreak = 0;
            } catch (RuntimeException e) {
                logBossPart("Kraken", "move " + attack + " (step " + sub + ", tick " + t + ")", e);
                endAttack(20);
                releaseGrip();
                if (++failStreak > 100) { leave(ChatColor.DARK_AQUA + "The Kraken sinks back into the abyss..."); return; }
            }
            bossPart("Kraken", "swimming", () -> swim(a));
            if (!atSea) bossPart("Kraken", "out of the water", () -> outOfWater(a)); // at sea you're meant to be on deck
            if (!atSea) bossPart("Kraken", "air bubbles", () -> airTick(a));
            bossPart("Kraken", "pink bubble", this::pinkTick);
            bossPart("Kraken", "barnacles", () -> barnacleTick(a));
            if (!atSea) bossPart("Kraken", "eels", () -> eelTick(a));
            bossPart("Kraken", "ink", () -> inkTick(a));
            bossPart("Kraken", "grip", this::gripTick);
            if (colossal) bossPart("Kraken", "arm contact", () -> armTouch(a));
            if (pilot(this) == null) bossPart("Kraken", "watchdog", () -> watchdog(a));
            renderAll();
            minions.removeIf(m -> !m.isValid() || m.isDead());
            hitCooldown.replaceAll((k, v) -> v - 1);
            hitCooldown.values().removeIf(v -> v <= 0);
            bossPart("Kraken", "boss bar", this::updateBar);
            if (ticks % 10 == 0) bossPart("Kraken", "music", this::musicTick);
        }

        /** Bosses 2.0.1: every model piece is a teleport + a transformation packet to everyone watching (~150 a tick):
         *  now every 2nd tick (the displays interpolate over 2 ticks, so it looks the same), and none with nobody near. */
        boolean send() {
            if (dying || leaving) return true;
            if ((ticks & 1) != 0) return false;
            double r = colossal ? 200 : 128;
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < r * r) return true;
            return false;
        }

        void renderAll() {
            boolean send = send();
            bossPart("Kraken", "arms", () -> { for (Arm arm : arms) { arm.solve(); if (send) arm.render(); } });
            bossPart("Kraken", "drawing", this::render);
        }

        void join(Player p) {
            fighters.add(p.getUniqueId());
            down.remove(p.getUniqueId());
            // BUG FIX: only players nearby at the roar got the music, so anyone who swam in later fought in silence
            if (musicStart >= 0 && listeners.add(p.getUniqueId())) {
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:kraken.music", SoundCategory.RECORDS, (float) kcfg("music.volume", 1.0), 1f);
                heardMusic(p, "kraken");
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
            Player pl = pilot(this);
            if (pl != null) target = pilotTarget(pl, a);
            if (pl != null && attack < 0 && intro <= 0 && !dying) { // boss form: he swims to a spot in front of the admin
                Location e = pl.getEyeLocation();
                Vector station = e.toVector().add(e.getDirection().multiply(14));
                Vector to = station.subtract(pos);
                vel.add(safeDir(to, new Vector()).multiply(Math.min(0.12, to.length() * 0.012)));
                float want = (float) Math.toDegrees(Math.atan2(-e.getDirection().getX(), e.getDirection().getZ()));
                float diff = ((want - yaw) % 360 + 540) % 360 - 180;
                yaw += (float) Math.max(-4, Math.min(4, diff));
                target = null; // skip the hunting below
            }
            if (atSea && pl == null && intro <= 0 && !dying && attack != 5) { // at sea: he keeps station beside the ship, facing it
                Location c = vessel.center();
                if (c != null) {
                    Vector cv = c.toVector();
                    home.setX(cv.getX()); home.setZ(cv.getZ());
                    Vector flatOff = pos.clone().subtract(cv).setY(0);
                    if (flatOff.lengthSquared() < 1) flatOff = new Vector(Math.cos(ticks * 0.01), 0, Math.sin(ticks * 0.01));
                    double dist = kcfg("sea.distance", 11) + Math.min(vessel.radius() * 0.3, 5);
                    Vector station = cv.clone().add(flatOff.normalize().multiply(dist));
                    Vector to = station.subtract(pos).setY(0);
                    vel.add(safeDir(to, new Vector()).multiply(Math.min(0.14 * sp + 0.04, to.length() * 0.03)));
                    Vector face = cv.clone().subtract(pos);
                    float want = (float) Math.toDegrees(Math.atan2(-face.getX(), face.getZ()));
                    float diff = ((want - yaw) % 360 + 540) % 360 - 180;
                    float turn = (float) Math.max(-3 * sp, Math.min(3 * sp, diff));
                    yaw += turn;
                    roll += ((float) Math.max(-0.35, Math.min(0.35, -turn * 0.09)) - roll) * 0.1f;
                    thrusting = to.length() > 6; // trails his arms behind him while he chases the ship
                    pulse = thrusting ? (float) Math.max(0, Math.sin(ticks * 0.3)) * 0.5f : pulse * 0.9f;
                }
                target = null; // skip the hunting below
            }
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
                    if (colossal) station.add(new Vector(0, acfg("station-height", 20, 8), 0)); // his beak and arms hang low: his head stays above you
                    double sd = colossal ? acfg("station", 50, 20) : 10;
                    if (colossal) { // always that far off (the normal one stays put once he's within 10): never with you inside his head
                        Vector away = flat > 0.1 ? new Vector(-to.getX(), 0, -to.getZ()).multiply(1 / flat) : new Vector(Math.sin(Math.toRadians(yaw)), 0, -Math.cos(Math.toRadians(yaw)));
                        station.add(away.multiply(sd));
                    } else if (flat > 0.1) station.subtract(new Vector(to.getX(), 0, to.getZ()).multiply(Math.min(1, sd / flat))); // ~10 blocks away
                    thrusting = c < 10;
                    if (c < 10) { // the jet: contracts, pushes, and a stream of bubbles shoots out of his siphon
                        pulse = (float) Math.sin(c / 10.0 * Math.PI);
                        Vector push = safeDir(station.subtract(pos), new Vector()).multiply(0.06 * sp * ms);
                        vel.add(push);
                        if (c % 2 == 0) {
                            Vector back = safeDir(push.clone().multiply(-1), new Vector(0, 0, -1));
                            Location sl = siphon().toLocation(world);
                            for (int i = 0; i < 4; i++) world.spawnParticle(Particle.BUBBLE, sl.clone().add(back.clone().multiply(i * 0.8 * k)), colossal ? 12 : 3, 0.2 * k, 0.2 * k, 0.2 * k, 0.02);
                        }
                    } else pulse *= 0.85f;
                }
            }
            if (!finite(vel)) vel = new Vector();
            double cap = (0.9 * sp + (attack == 5 ? (atSea ? 1.8 : 1.0) : 0)) * ms;
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
            if (atSea) { keepAtSurface(); return; }
            if (!finite(pos)) pos = home.toVector();
            Vector flat = pos.clone().subtract(home.toVector()).setY(0);
            double limit = colossal ? acfg("arena-radius", 140, 70) : kcfg("arena-radius", 24);
            if (flat.length() > limit) { pos = home.toVector().add(flat.normalize().multiply(limit)).setY(pos.getY()); vel.multiply(0.3); }
            if (intro <= 0) unwall(); // (during the intro he rises out of the dark on purpose)
            double top = surfaceY(pos.getX(), pos.getZ(), Math.min(pos.getY(), home.getY() + 30 * k)) - 5.5 * scale / 4;
            double floor = seabedY(pos.getX(), pos.getZ(), pos.getY()) + 4.5 * scale / 4;
            // Bosses 2.0.1: his head is wide, so the rock beside the middle counts too (he sank into slopes and ledges)
            if (wallTick != ticks / 4) { wallTick = ticks / 4; ringFloor = ringFloor(); }
            if (ringFloor > -1e8) floor = Math.max(floor, ringFloor + 4.5 * scale / 4 * 0.75);
            if (top < floor) { // a shallow spot: the middle of the water column
                double mid = (top + floor) / 2; pos.setY(mid);
            } else if (pos.getY() > top) { pos.setY(top); if (vel.getY() > 0) vel.setY(0); }
            else if (pos.getY() < floor && intro <= 0) { pos.setY(floor); if (vel.getY() < 0) vel.setY(0); }
        }

        // ---- Bosses 2.0.1: never stuck in the ground. Only the column under his middle was checked, so his head swam into
        // cliffs, ledges and cave walls beside it and sat there half buried.
        int wallTick = -1, buried; double ringFloor = -1e9;
        double headR() { return 3.5 * scale / 4; }

        boolean solidAt(double x, double y, double z) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
            if (by < world.getMinHeight() || by >= world.getMaxHeight()) return by < world.getMinHeight();
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return true; // never into unloaded ground
            return world.getBlockAt(bx, by, bz).getType().isSolid();
        }

        /** The highest floor under the edge of his head (only ground BELOW him: walls are unwall()'s job). */
        double ringFloor() {
            double r = headR() * 0.75, best = -1e9;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4, x = pos.getX() + Math.cos(a) * r, z = pos.getZ() + Math.sin(a) * r;
                if (solidAt(x, pos.getY(), z)) continue;
                double f = seabedY(x, z, pos.getY());
                if (f <= pos.getY() && f > best) best = f;
            }
            return best;
        }

        /** Pushes him out of any rock his head overlaps, kills the speed going into it, and if he's still buried after
         *  2 s (a cave, a slide), lifts him to the nearest open water that fits his head. */
        void unwall() {
            double r = headR();
            Vector out = new Vector(); int hit = 0, tried = 0;
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6, cx = Math.cos(a), cz = Math.sin(a);
                for (double f : new double[]{0.5, 1.0}) for (double dy : new double[]{-0.6, 0, 0.6}) {
                    tried++;
                    if (solidAt(pos.getX() + cx * r * f, pos.getY() + dy * r, pos.getZ() + cz * r * f)) {
                        out.add(new Vector(-cx, -dy * 0.8, -cz).multiply(f)); hit++;
                    }
                }
            }
            boolean core = solidAt(pos.getX(), pos.getY(), pos.getZ());
            if (hit == 0 && !core) { buried = 0; return; }
            if (out.lengthSquared() > 1e-6) {
                Vector dir = out.normalize();
                pos.add(dir.clone().multiply(Math.min(0.25 + 0.04 * hit, 1.2) * ms));
                double into = -vel.dot(dir);
                if (into > 0) vel.add(dir.clone().multiply(into));
            }
            if (core || hit > tried / 3) buried++; else buried = Math.max(0, buried - 1);
            if (buried < 40) return;
            buried = 0;
            Vector free = openWater(r);
            if (free != null) {
                pos = free; vel = new Vector();
                world.spawnParticle(Particle.BUBBLE_COLUMN_UP, pos.toLocation(world), colossal ? 120 : 30, r * 0.4, r * 0.4, r * 0.4, 0.1);
                getLogger().info("[Kraken] was stuck in the ground: moved him to open water.");
            }
        }

        boolean fits(Vector c, double r) {
            if (!isWater(world.getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ()))) return false;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                for (double dy : new double[]{-0.6, 0, 0.6}) if (solidAt(c.getX() + Math.cos(a) * r, c.getY() + dy * r, c.getZ() + Math.sin(a) * r)) return false;
            }
            return true;
        }

        /** The nearest spot (up first, then outward) where his whole head is in water. */
        Vector openWater(double r) {
            for (int step = 1; step <= 12; step++) {
                double d = step * r * 0.5;
                Vector[] tries = {new Vector(0, d, 0), new Vector(d, d * 0.5, 0), new Vector(-d, d * 0.5, 0), new Vector(0, d * 0.5, d),
                        new Vector(0, d * 0.5, -d), new Vector(d, 0, d), new Vector(-d, 0, -d), new Vector(d, 0, -d), new Vector(-d, 0, d)};
                for (Vector t : tries) {
                    Vector c = pos.clone().add(t);
                    if (fits(c, r)) return c;
                }
            }
            return null;
        }

        /** At sea: his head stays out of the water (rising to it during the intro), wherever the ship goes. */
        void keepAtSurface() {
            if (!finite(pos)) pos = home.toVector();
            double want = surfaceY(pos.getX(), pos.getZ(), pos.getY()) + kcfg("sea.rise", 1.2) * scale / 4;
            if (intro > 0) { if (pos.getY() > want) pos.setY(want); return; }
            pos.setY(pos.getY() + (want - pos.getY()) * 0.25);
            vel.setY(0);
        }

        double surfaceY(double x, double z, double fromY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
            if (!isWater(world.getBlockAt(bx, y, bz))) { // above the water: come down to it
                for (int k = 0; k < 64 && y > world.getMinHeight(); k++, y--) if (isWater(world.getBlockAt(bx, y, bz))) return y + 1;
                return fromY;
            }
            for (int i = 0, n = colossal ? 420 : 128; i < n && y < world.getMaxHeight() - 1; i++, y++) if (!isWater(world.getBlockAt(bx, y, bz))) return y;
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
                    Safe.vel(p, safeDir(p.getLocation().toVector().subtract(pos).setY(0), new Vector(1, 0, 0)).multiply(-0.6).setY(-0.4)); // dragged back toward the water
                    outTicks.put(p.getUniqueId(), (int) kcfg("out-of-water.smash-ticks", 50) - 20); // the next one comes fast
                }
            }
        }

        // ---------- air bubbles: stay in them to breathe ----------
        void airTick(List<Player> a) {
            if (--bubbleTimer <= 0) {
                bubbleTimer = phase == 1 ? 100 : phase == 2 ? 90 : 80;
                int n = 2 + random.nextInt(2);
                for (int i = 0; i < n && airs.size() < (colossal ? 14 : 10); i++) spawnAir();
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
                    if (eye(p).distanceSquared(b.pos) > 2.0 * hs * 2.0 * hs) continue;
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
            Vector c = colossal ? nearFighter() : pos; // the giant's head is far off: his bubbles rise among the fighters
            for (int tries = 0; tries < 8; tries++) {
                double ang = random.nextDouble() * Math.PI * 2, dist = colossal ? 5 + random.nextDouble() * 10 : 6 + random.nextDouble() * 12;
                Vector at = c.clone().add(new Vector(Math.cos(ang) * dist, random.nextDouble() * 6 - 4, Math.sin(ang) * dist));
                if (!isWater(at.toLocation(world).getBlock())) continue;
                airs.add(new Bubble(at));
                return;
            }
        }

        /** A random fighter's position (the Abyssal Kraken puts bubbles, eels and minions round them), or his head. */
        Vector nearFighter() {
            List<Player> a = active();
            return a.isEmpty() ? pos.clone() : a.get(random.nextInt(a.size())).getLocation().toVector();
        }

        void popAirNear(Vector c, double r) { for (Bubble b : airs) if (b.pos.distanceSquared(c) < r * r) b.pop(); }

        // ---------- the pink bubble: pop it for 150-200 damage ----------
        void pinkTick() {
            if (intro > 0 || transition > 0) return;
            if (pink != null) {
                pink.age++;
                // BUG FIX: it could rise out of the water, so popping it meant surfacing (and getting smashed)
                if (atSea) pink.pos.setY(surfaceY(pink.pos.getX(), pink.pos.getZ(), pink.pos.getY() + 3) + 1.8 + Math.sin(pink.age * 0.08) * 0.3); // bobbing on the waves: shoot it
                else if (pink.pos.getY() < surfaceY(pink.pos.getX(), pink.pos.getZ(), pink.pos.getY()) - 2.5) pink.pos.add(new Vector(0, 0.015, 0));
                Location l = pink.pos.toLocation(world); l.setYaw(0); l.setPitch(0);
                pink.d.teleport(l);
                if (pink.box.isValid()) pink.box.teleport(l.clone().subtract(0, 0.8, 0));
                if (pink.age % 6 == 0) world.spawnParticle(Particle.DUST, l, 3, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 200), 1.2f));
                if (pink.age > 240) { pink.remove(); pink = null; }
                return;
            }
            if (--pinkTimer > 0) return;
            pinkTimer = (int) kcfg("pink-bubble-seconds", 15) * 20;
            Vector pc = colossal ? nearFighter() : pos;
            for (int tries = 0; tries < 8; tries++) {
                double ang = random.nextDouble() * Math.PI * 2, dist = 7 + random.nextDouble() * 6;
                Vector at = pc.clone().add(new Vector(Math.cos(ang) * dist, random.nextDouble() * 4 - 2, Math.sin(ang) * dist));
                if (atSea) at.setY(surfaceY(at.getX(), at.getZ(), at.getY() + 3) + 1.8);
                else if (!isWater(at.toLocation(world).getBlock())) continue;
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
            damage(dmg * (colossal ? kcfg("abyssal.pink-multiplier", 3.0) : 1), by, true);
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
            Player pl = pilot(this);
            if (pl != null) { // boss form: the admin's pick (no waiting between moves), at whoever they're looking at
                Player target = pilotTarget(pl, a);
                if (attack < 0) { int q = takeMove(this); if (q < 0) return; attack = q; lastAttack = q; t = 0; sub = 0; count = 0; }
                runKrakenMove(target, a);
                return;
            }
            if (recover > 0) { recover--; return; }
            Player target = nearest(a);
            if (target == null) return;
            if (attack < 0) {
                // at sea he has 7 moves: Tentacle Slam, Crushing Grip, Ink Cloud, Hull Bite, Barnacle Barrage, Tidal Surge,
                // and (phase 3) Eight-Arm Barrage. Whirlpool, Spawn of the Deep and the depths moves need you underwater.
                List<Integer> pool = atSea ? new ArrayList<>(List.of(1, 2, 3, 5, 6, 7)) : new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
                if (phase == 3) pool.addAll(atSea ? List.of(10, 10, 10) : List.of(9, 10, 11, 9, 10, 11));
                if (colossal) { pool.add(12); if (phase >= 2) pool.add(13); } // the Abyssal Kraken: Abyssal Clap, Ink Storm
                if (minions.size() >= (colossal ? 8 : 4)) pool.removeIf(m -> m == 8);
                int pick;
                do pick = pool.get(random.nextInt(pool.size())); while (pick == lastAttack && pool.size() > 1);
                attack = pick; lastAttack = pick; t = 0; sub = 0; count = 0;
            }
            runKrakenMove(target, a);
        }

        void runKrakenMove(Player target, List<Player> a) {
            switch (attack) {
                case 1 -> tentacleSlam(target, a);
                case 2 -> crushingGrip(target);
                case 3 -> inkCloud(target);
                case 4 -> whirlpool(a, false);
                case 5 -> beakBite(target, a);
                case 6 -> barnacleBarrage(a);
                case 7 -> tidalSurge(a);
                case 8 -> spawnOfTheDeep(a);
                case 9 -> whirlpool(a, true);
                case 10 -> eightArmBarrage(a);
                case 11 -> crushingDepths(a);
                case 12 -> abyssalClap(target, a);
                case 13 -> inkStorm(a);
                default -> endAttack(10);
            }
            t++;
        }

        int rest() { int r = phase == 1 ? 50 : phase == 2 ? 36 : 26; return colossal ? (int) Math.round(r * kcfg("abyssal.rest-multiplier", 0.75)) : r; }

        /** Arms that aren't doing anything drift and curl around him. */
        void idleArms() {
            double s = scale / 4;
            Player near = null; double bd = far(25) * far(25);
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
                if (recoilT > 0) g.add(arm.outward().multiply(recoilT * 0.8 * k)); // flinches away when he's hit
                arm.goal = g;
            }
        }

        void ring(Vector c, double r, Color col) {
            int n = colossal ? 40 : 20;
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                world.spawnParticle(Particle.DUST, c.toLocation(world).add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, colossal ? 2.2f : 1.4f));
            }
        }

        /** 1) Tentacle Slam: an arm coils up high, a red ring marks where it lands, then it slams. */
        void tentacleSlam(Player target, List<Player> a) {
            int w = windup(34);
            if (t == 0) { Arm arm = armNear(target.getLocation().toVector()); arm.busy = true; sub = arm.index; }
            Arm arm = arms[sub];
            if (t < w - (colossal ? 22 : 12)) lockPoint = target.getLocation().toVector();
            if (t < w) {
                arm.goal = lockPoint.clone().add(new Vector(Math.cos(t * 0.35) * 2.2 * k, 9 * k, Math.sin(t * 0.35) * 2.2 * k)); arm.follow = 0.12; arm.wave = 0.7; // coils up, writhing
                if (t % 3 == 0) ring(lockPoint, 3.2 * hs, Color.fromRGB(220, 40, 40));
            } else if (t < w + 8) {
                arm.goal = lockPoint.clone().add(new Vector(0, -1.5, 0)); arm.follow = 0.65; arm.wave = 0.1; // SLAM
                if (t == w + 3) {
                    // BUG FIX: the hit was centered on the warning ring even when the arm couldn't reach it (you swam out
                    // of its ~22-block reach), so you got hit by a slam that never touched you. It's centered on the tip now.
                    Vector impact = arm.tipPos();
                    squashT = 6; // the whole head jolts with it
                    world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
                    world.spawnParticle(Particle.BUBBLE_POP, impact.toLocation(world), 80, 2, 1, 2, 0.15);
                    seaHit(impact, kcfg("sea.ship-damage.slam", 0.04), 4.0);
                    for (Player p : a) if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(impact) < 3.6 * hs * 3.6 * hs) {
                        strike(p, kcfg("damage.slam", 22), Guard.HEAVY, arm.tipPos());
                        Safe.vel(p, p.getLocation().toVector().subtract(impact).setY(0).multiply(0.3).setY(-0.5));
                    }
                }
            } else if (t <= w + 22) {
                arm.goal = lockPoint.clone().add(new Vector(0, 5 * k, 0)); arm.follow = 0.12; arm.wave = 0.3; // recoils back up after the hit
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
                if (arm.tipPos().distanceSquared(eye(target)) < 2.6 * hs * 2.6 * hs && t > 6) {
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
            if (atSea) away = safeDir(pos.clone().subtract(eye(p)).setY(0), new Vector(0, 0, 1)).add(new Vector(0, eye(p).getY() > surface() + 0.5 ? 0.35 : -0.2, 0)); // off the deck and into the sea
            // the tip wraps around you (circling your body) while the arm drags you down and away from the air
            arm.goal = eye(p).add(new Vector(Math.cos(gripT * 0.45) * 0.9 * hs, -0.4, Math.sin(gripT * 0.45) * 0.9 * hs)); arm.follow = 0.35;
            Safe.vel(p, safeDir(away, new Vector(0, -1, 0)).multiply(colossal ? 0.4 : 0.28));
            if (gripT % 20 == 0) strike(p, kcfg("damage.grip-per-second", 4), Guard.UNBLOCKABLE, arm.tipPos());
            if (gripHits >= needHits || gripT >= maxT) {
                p.sendActionBar(legacy(ChatColor.GREEN + "You broke free!"));
                Safe.vel(p, p.getVelocity().add(safeDir(eye(p).subtract(pos), new Vector(0, 1, 0)).multiply(0.8)));
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
                inkCenter = colossal ? eye(target) : pos.clone().add(target.getLocation().toVector().subtract(pos).multiply(0.6));
                inkT = 100;
                world.playSound(pos.toLocation(world), Sound.ENTITY_SQUID_SQUIRT, 3f, 0.4f);
                Vector from = siphon(); // octopuses ink through their siphon
                Vector dir = safeDir(inkCenter.clone().subtract(from), new Vector(0, 0, 1));
                double len = Math.min(from.distance(inkCenter), 15 * k);
                for (int i = 0; i < 30; i++) world.spawnParticle(Particle.SQUID_INK, from.clone().add(dir.clone().multiply(i * len / 30)).toLocation(world), 6, 0.3 * hs, 0.3 * hs, 0.3 * hs, 0.02);
            }
            if (t > 30) endAttack(rest());
        }

        void inkTick(List<Player> a) {
            if (stormT > 0) { // the Ink Storm: a cloud on every fighter, and the ink burns
                stormT--;
                double r = 6 * hs;
                for (Vector c : storm) {
                    if (stormT % 2 == 0) world.spawnParticle(Particle.SQUID_INK, c.toLocation(world), 30, r * 0.5, r * 0.4, r * 0.5, 0.01);
                    for (Player p : a) {
                        if (eye(p).distanceSquared(c) > r * r) continue;
                        if (counter(p, "glow_gland")) { p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 60, 0, true, false, true)); continue; }
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, true, false, true));
                        if (stormT % 20 == 0) strike(p, kcfg("abyssal.damage.ink-per-second", 3), Guard.UNBLOCKABLE, c);
                    }
                }
                if (stormT <= 0) storm.clear();
            }
            if (inkT <= 0 || inkCenter == null) return;
            inkT--;
            double r = 7 * hs;
            if (inkT % 2 == 0) world.spawnParticle(Particle.SQUID_INK, inkCenter.toLocation(world), colossal ? 60 : 30, r * 0.5, r * 0.4, r * 0.5, 0.01);
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
            double range = (abyssal ? 26 : 18) * hs, pullBase = (abyssal ? 0.16 : 0.1) * (colossal ? 1.3 : 1);
            beakOpen = 1;
            Vector c = beak();
            if (t == 0) {
                world.playSound(c.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 2.5f, 0.5f);
                if (abyssal) { popAirNear(c, 12 * hs); inkCenter = c.clone(); inkT = 80; say(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ABYSSAL VORTEX!"); }
            }
            for (Arm arm : arms) { // every arm spins around him
                double ang = Math.toRadians(arm.index * 45) + t * 0.15;
                arm.goal = pos.clone().add(new Vector(Math.cos(ang) * 15 * k, -4 * k, Math.sin(ang) * 15 * k)); arm.follow = 0.2; arm.wave = 0.5;
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
                Safe.vel(p, p.getVelocity().multiply(0.8).add(to.normalize().multiply(pull)).add(swirl));
                if (d < bodyR(3.5) && t % 20 == 0) strike(p, kcfg("damage.whirlpool-bite", 8), Guard.UNBLOCKABLE, c);
            }
            if (t >= dur) endAttack(rest());
        }

        /** 5) Beak Bite: rears back with his beak wide open, then lunges. */
        void beakBite(Player target, List<Player> a) {
            int w = windup(24);
            if (t < w) {
                beakOpen = t / (float) w;
                Vector back = safeDir(pos.clone().subtract(target.getLocation().toVector()), new Vector(0, 0, 1));
                vel.add(back.multiply(0.01 * ms));
                for (Arm arm : arms) { arm.goal = arm.root().add(new Vector(0, 6 * k, 0)).add(arm.outward().multiply(15 * k)); arm.follow = 0.1; } // spread wide
            } else if (t == w) {
                vel = safeDir(eye(target).subtract(colossal ? beak() : pos), new Vector(0, 0, 1)).multiply((1.4 * speed() + 0.5) * ms); // the giant aims his beak
                if (atSea) { // at sea: he lunges at the hull, staying at the surface, far enough to reach it
                    Vector to = eye(target).subtract(pos).setY(0);
                    vel = safeDir(to, new Vector(0, 0, 1)).multiply(Math.max(1.0, Math.min(2.4, to.length() * 0.14)));
                }
                biteHitShip = false;
                world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, 2.5f, 0.5f);
            } else if (t < w + 14) {
                beakOpen = 1;
                if (atSea && !biteHitShip && vessel.distanceToHull(beak().toLocation(world)) < 4.5) { // Hull Bite: his beak crunches into the ship
                    biteHitShip = true;
                    seaHit(beak(), kcfg("sea.ship-damage.bite", 0.07), 6);
                    vel.multiply(-0.3);
                }
                for (Arm arm : arms) { arm.goal = arm.root().subtract(safeDir(vel, new Vector(0, 0, 1)).multiply(17 * k)).add(arm.outward().multiply(3 * k)); arm.follow = 0.3; } // trail behind
                for (Player p : a) if (eye(p).distanceSquared(beak()) < bodyR(4.5) * bodyR(4.5)) strike(p, kcfg("damage.bite", 20), Guard.HEAVY, beak());
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
                    for (int i = -2; i <= 2; i++) barnacles.add(new Barnacle(beak(), aim.clone().rotateAroundY(Math.toRadians(i * 9)).multiply(colossal ? 1.4 : 0.7)));
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
                boolean solid = world.getBlockAt(l).getType().isSolid();
                if (solid && atSea && b.life % 2 == 0) seaHit(b.pos, kcfg("sea.ship-damage.barnacle", 0.005), 2.0); // they chip the hull
                boolean done = b.life > (colossal ? 90 : 60) || solid;
                for (Player p : a) if (!done && eye(p).distanceSquared(b.pos) < 1.4 * hs * 1.4 * hs) { strike(p, kcfg("damage.barnacle", 8), Guard.BLOCKABLE, b.pos); done = true; }
                if (done) { if (b.d.isValid()) b.d.remove(); it.remove(); }
            }
        }

        /** 7) Tidal Surge: he clenches, then a ring of force rushes outward and pops the air bubbles it passes. */
        void tidalSurge(List<Player> a) {
            int w = windup(26);
            if (t == 0) surgeHitShip = false;
            if (t < w) {
                pulse = t / (float) w;
                for (Arm arm : arms) { arm.goal = arm.root().add(new Vector(0, -2 * k, 0)).add(arm.outward().multiply(2 * k)); arm.follow = 0.15; } // curls in tight
            } else {
                double r = (t - w) * 0.9 * ms + (colossal ? 3 * k : 0);
                pulse = Math.max(0, pulse - 0.15f);
                for (Arm arm : arms) { arm.goal = arm.root().add(arm.outward().multiply(r + 3 * k)); arm.follow = 0.4; } // flings out
                if (t == w) world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.5f);
                int pts = colossal ? 120 : 28;
                if (t % 2 == 0) for (int i = 0; i < pts; i++) {
                    double ang = Math.PI * 2 * i / pts;
                    world.spawnParticle(Particle.BUBBLE_POP, pos.toLocation(world).add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 1, 0, 0.6, 0, 0);
                }
                popAirNear(pos, r);
                if (atSea && !surgeHitShip) { // the wave slams into the ship's side as it passes
                    Location c = vessel.center();
                    if (c != null && r >= Math.hypot(c.getX() - pos.getX(), c.getZ() - pos.getZ()) - vessel.radius() * 0.5) {
                        surgeHitShip = true;
                        Vector side = pos.clone().add(safeDir(c.toVector().subtract(pos).setY(0), new Vector(1, 0, 0)).multiply(r));
                        side.setY(c.getY() - 1);
                        seaHit(side, kcfg("sea.ship-damage.surge", 0.03), 99);
                        world.spawnParticle(Particle.SPLASH, side.toLocation(world), 120, 2, 1, 2, 0.4);
                    }
                }
                for (Player p : a) {
                    Vector d = p.getLocation().toVector().subtract(pos);
                    double flat = Math.hypot(d.getX(), d.getZ());
                    if (Math.abs(flat - r) < 1.2 * hs && Math.abs(eye(p).getY() - pos.getY()) < (atSea ? 6.0 : 2.0 * hs)) { // at sea: it reaches the deck
                        strike(p, kcfg("damage.surge", 15), Guard.HEAVY, pos);
                        Safe.vel(p, safeDir(d.setY(0), new Vector(1, 0, 0)).multiply(1.1).setY(0.3));
                    }
                }
                if (r > 22 * k) endAttack(rest());
            }
        }

        /** 8) Spawn of the Deep: Drowned with tridents rise to help him. */
        void spawnOfTheDeep(List<Player> a) {
            beakOpen = 0.6f;
            if (t == 10) {
                world.playSound(pos.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.8f);
                for (int i = 0; i < (colossal ? 5 : 3) && minions.size() < (colossal ? 10 : 6); i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    Vector c = colossal ? nearFighter() : pos; // the giant's spawn rise among the fighters
                    Location l = c.toLocation(world).add(Math.cos(ang) * (colossal ? 9 : 6), -2, Math.sin(ang) * (colossal ? 9 : 6));
                    if (!isWater(l.getBlock())) continue;
                    org.bukkit.entity.Drowned d = Wild.spawnAs(world, l, EntityType.DROWNED, org.bukkit.entity.Drowned.class, z -> {
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
                    barrageTargets.add(p.getLocation().toVector().add(new Vector(Math.cos(ang) * 3 * hs, 0, Math.sin(ang) * 3 * hs)));
                    arms[i].busy = true;
                }
                say(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "EIGHT-ARM BARRAGE!");
            }
            for (int i = 0; i < ARMS; i++) {
                int slamAt = 20 + i * gap;
                Vector at = barrageTargets.get(i);
                Arm arm = arms[i];
                if (t < slamAt) {
                    arm.goal = at.clone().add(new Vector(0, 8 * k, 0)); arm.follow = 0.12;
                    if (t % 3 == 0 && t > slamAt - 18) ring(at, 3 * hs, Color.fromRGB(220, 40, 40));
                } else if (t < slamAt + 6) {
                    arm.goal = at.clone().add(new Vector(0, -1.5, 0)); arm.follow = 0.7;
                    if (t == slamAt + 3) {
                        Vector impact = arm.tipPos(); // where the arm actually landed (same fix as Tentacle Slam)
                        squashT = 4;
                        world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
                        world.spawnParticle(Particle.BUBBLE_POP, impact.toLocation(world), 40, 1.5, 0.8, 1.5, 0.1);
                        seaHit(impact, kcfg("sea.ship-damage.barrage", 0.03), 3.5);
                        for (Player p : a) if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(impact) < 3.4 * hs * 3.4 * hs) strike(p, kcfg("damage.barrage", 17), Guard.HEAVY, impact);
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
            vel.add(new Vector(0, -0.02 * ms, 0));
            pulse = 0.4f + (float) Math.sin(t * 0.3) * 0.2f;
            if (t > 20 && t % 20 == 0) for (Player p : a) {
                boolean safe = false;
                for (Bubble b : airs) if (eye(p).distanceSquared(b.pos) < 2.2 * hs * 2.2 * hs) { safe = true; break; }
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
                for (int i = 0; i < (colossal ? 3 : 2) && eels.size() < kcfg("max-eels", 6) * (colossal ? 2 : 1); i++) {
                    Vector at = (colossal ? nearFighter().add(new Vector(0, 3, 0)) : pos.clone()).add(new Vector(random.nextGaussian() * (colossal ? 6 : 3), -2, random.nextGaussian() * (colossal ? 6 : 3)));
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

        // ---------- the Abyssal Kraken's own moves ----------
        /** 12) Abyssal Clap: two arms rise either side of you, a red ring marks the spot, and they clap shut on it. */
        void abyssalClap(Player target, List<Player> a) {
            int w = windup(44);
            if (t == 0) {
                Vector to = target.getLocation().toVector();
                Arm one = armNear(to); one.busy = true;
                Arm two = armNear(to); two.busy = true; // armNear skips busy arms: the next nearest
                sub = one.index; count = two.index;
                say(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "ABYSSAL CLAP!");
            }
            Arm one = arms[sub], two = arms[count];
            if (t < w - 16) lockPoint = target.getLocation().toVector().add(new Vector(0, 1, 0));
            Vector side = safeDir(lockPoint.clone().subtract(pos).setY(0).getCrossProduct(new Vector(0, 1, 0)), new Vector(1, 0, 0));
            double r = 4.5 * hs;
            if (t < w) {
                double open = 14 * hs;
                one.goal = lockPoint.clone().add(side.clone().multiply(open)).add(new Vector(0, 3 * hs, 0)); one.follow = 0.14; one.wave = 0.5;
                two.goal = lockPoint.clone().add(side.clone().multiply(-open)).add(new Vector(0, 3 * hs, 0)); two.follow = 0.14; two.wave = 0.5;
                if (t % 3 == 0) ring(lockPoint, r, Color.fromRGB(150, 40, 200));
            } else if (t < w + 8) {
                one.goal = lockPoint.clone(); two.goal = lockPoint.clone(); one.follow = 0.7; two.follow = 0.7; one.wave = 0.05; two.wave = 0.05;
                if (t == w + 4) {
                    squashT = 6;
                    world.playSound(lockPoint.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 2.2f, 0.4f);
                    world.playSound(lockPoint.toLocation(world), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.4f, 0.6f);
                    world.spawnParticle(Particle.BUBBLE_POP, lockPoint.toLocation(world), 120, r * 0.5, r * 0.5, r * 0.5, 0.2);
                    for (Player p : a) if (eye(p).distanceSquared(lockPoint) < r * r) {
                        hitCooldown.remove(p.getUniqueId()); // an arm brushing you a moment before never saves you from the clap
                        strike(p, kcfg("abyssal.damage.clap", 26), Guard.HEAVY, lockPoint);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
                    }
                }
            } else if (t < w + 24) {
                one.goal = lockPoint.clone().add(side.clone().multiply(6 * hs)).add(new Vector(0, 6 * hs, 0)); one.follow = 0.1;
                two.goal = lockPoint.clone().add(side.clone().multiply(-6 * hs)).add(new Vector(0, 6 * hs, 0)); two.follow = 0.1;
            } else endAttack(rest());
        }

        /** 13) Ink Storm (phase 2+): he clenches and ink bursts on every fighter at once. It burns, blinds and slows. */
        void inkStorm(List<Player> a) {
            beakOpen = t < 14 ? t / 14f : Math.max(0, 1 - (t - 14) / 10f);
            pulse = t <= 14 ? (float) Math.sin(t / 14.0 * Math.PI) : pulse * 0.85f;
            if (t == 0) say(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "INK STORM! " + ChatColor.GRAY + "Swim out of the black!");
            if (t == 14) {
                storm.clear();
                for (Player p : a) storm.add(eye(p));
                stormT = 100;
                world.playSound(pos.toLocation(world), Sound.ENTITY_SQUID_SQUIRT, 3f, 0.3f);
                world.playSound(pos.toLocation(world), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.4f);
            }
            if (t > 34) endAttack(rest());
        }

        /** The Abyssal Kraken's arms are a hazard on their own: swim into one and it hurts (once a second). */
        void armTouch(List<Player> a) {
            if (intro > 0 || ticks % 4 != 0) return;
            touched.replaceAll((id, v) -> v - 4);
            touched.values().removeIf(v -> v <= 0);
            for (Player p : a) {
                if (touched.containsKey(p.getUniqueId())) continue;
                Vector e = eye(p);
                outer:
                for (Arm arm : arms) for (int j = 0; j < SEGS; j++) {
                    Vector s = arm.p[j], d = arm.p[j + 1].clone().subtract(s);
                    double l2 = d.lengthSquared();
                    double f = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, e.clone().subtract(s).dot(d) / l2));
                    double rr = arm.width[j] * 0.55;
                    if (s.clone().add(d.multiply(f)).distanceSquared(e) > rr * rr) continue;
                    touched.put(p.getUniqueId(), 20);
                    strike(p, kcfg("abyssal.damage.arm-touch", 6), Guard.BLOCKABLE, arm.p[j]);
                    Safe.vel(p, safeDir(e.clone().subtract(arm.p[j]), new Vector(0, 1, 0)).multiply(0.6));
                    break outer;
                }
            }
        }

        // ---------- the watchdog: never stuck where nobody can see him ----------
        void watchdog(List<Player> a) {
            if (a.isEmpty() || intro > 0 || transition > 0) { lastSeen = ticks; return; }
            for (Player p : a) {
                Vector d = eye(p).subtract(pos);
                double len = d.length();
                if (len > far(34)) continue;
                if (len < 0.5 || world.rayTraceBlocks(pos.toLocation(world), d.multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true) == null) { lastSeen = ticks; return; }
            }
            if (ticks - lastSeen < 300) return;
            lastSeen = ticks;
            Player f = a.get(random.nextInt(a.size()));
            Vector spot = colossal
                    ? f.getLocation().toVector().add(safeDir(new Vector(random.nextGaussian(), 0, random.nextGaussian()), new Vector(1, 0, 0)).multiply(acfg("station", 50, 20))).add(new Vector(0, acfg("station-height", 20, 8), 0))
                    : f.getLocation().toVector().add(new Vector(random.nextGaussian() * 6, 0, random.nextGaussian() * 6));
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
            boolean winding = !dying && !leaving && ((attack == 1 && t < windup(34)) || (attack == 12 && t < windup(44)) || (attack == 5 && t < windup(24)) || (attack == 7 && t < windup(26))
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
            boolean send = send();
            if (send) {
                mantle.teleport(l);
                mantle.setInterpolationDelay(0);
                mantle.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(scale * sxz, scale * sy, scale * sxz), new Quaternionf()));
            }
            if (!send) return;
            // eyes follow the nearest player
            Player look = null; double bd = far(40) * far(40);
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
            bar.setTitle(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + title() + ChatColor.GRAY + "  Phase " + phase);
            for (Player p : world.getPlayers()) {
                boolean near = p.getLocation().toVector().distanceSquared(pos) < far(80) * far(80);
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        // ---------- music (switched on once the track is in the pack) ----------
        void playMusic() {
            if (!getConfig().getBoolean("kraken.music.enabled", true)) return;
            musicStart = System.currentTimeMillis();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > far(96) * far(96)) continue;
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p, "faultline:kraken.music", SoundCategory.RECORDS, (float) kcfg("music.volume", 1.0), 1f);
                heardMusic(p, "kraken");
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

        // ---------- at sea: his hits land on the ship too ----------
        void seaHit(Vector at, double fraction, double reach) {
            if (!atSea || vessel == null) return;
            Location l = at.toLocation(world);
            if (vessel.distanceToHull(l) > reach) return;
            if (!vessel.hit(l, fraction)) return;
            world.playSound(l, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.6f, 0.6f);
            world.spawnParticle(Particle.BLOCK, l, 30, 1, 0.5, 1, 0, Material.SPRUCE_PLANKS.createBlockData());
            if (vessel.boat != null) say(ChatColor.RED + "" + ChatColor.BOLD + "He smashed the boat!");
        }

        /** The ship is wrecked (or the boat smashed): he drags it under and goes. No hoard. */
        void lostAtSea() {
            String what = vessel.title();
            krakenCooldownUntil = System.currentTimeMillis() + (long) (kcfg("cooldown-minutes", 10) * 60000);
            for (Player p : world.getPlayers()) {
                if (p.getLocation().toVector().distanceSquared(pos) > 96 * 96) continue;
                p.showTitle(Title.title(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "YOUR " + what.toUpperCase() + " IS LOST"),
                        legacy(ChatColor.GRAY + "The Kraken drags it down into the deep..."),
                        Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(900))));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.4f);
            }
            leave(ChatColor.DARK_AQUA + "The Kraken drags the " + what + " down into the deep...");
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
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(c) < far(48) * far(48) && survival(p))
                    Safe.vel(p, p.getVelocity().add(new Vector(random.nextGaussian() * 0.2, 0.1, random.nextGaussian() * 0.2))); // the water shakes
            }
            if (deathT <= 40) {                                                  // 1) death throes
                beakOpen = 1;
                pulse = (float) Math.abs(Math.sin(deathT * 0.8)) * 0.7f;
                yaw += (float) (random.nextGaussian() * 4);
                roll = (float) (Math.sin(deathT * 0.9) * 0.25);
                for (Arm a : arms) {
                    a.busy = true;
                    a.goal = a.root().add(a.outward().multiply(16 * k)).add(new Vector(random.nextGaussian() * 4 * k, Math.sin(deathT * 0.5 + a.index) * 8 * k, random.nextGaussian() * 4 * k));
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
                    a.goal = a.root().add(new Vector(0, 3 * k, 0)).add(a.outward().multiply(-1.5 * k)); // wrapped back around his body
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
                pos.add(new Vector(0, -0.06 * ms, 0));
                roll += (1.25f - roll) * 0.03f; lean += (0.4f - lean) * 0.03f;
                beakOpen += (0.35f - beakOpen) * 0.05f; pulse *= 0.9f;
                for (Arm a : arms) { a.goal = a.root().add(new Vector(0, 15 * k, 0)).add(a.outward().multiply((6 + 3 * kk) * k)); a.follow = 0.035; a.wave = 0.12; a.curl = 0.2; }
                if (deathT % 14 == 0 && kk < 0.7) { // NEW: final twitches, one arm at a time
                    Arm a = arms[random.nextInt(ARMS)];
                    a.goal = a.root().add(a.outward().multiply(14 * k)).add(new Vector(0, -4 * k, 0)); a.follow = 0.5;
                }
                if (deathT % 6 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, c, 25, 3, 2, 3, 0.05);
            } else if (deathT <= 200) {                                          // 5) shrinking away into the dark
                deathFade = 1 - (deathT - 170) / 30f;
                pos.add(new Vector(0, -0.05 * ms, 0));
                if (deathT % 4 == 0) world.spawnParticle(Particle.BUBBLE, c, 30, 3, 3, 3, 0.1);
            } else {                                                             // gone: the title, and his hoard
                world.spawnParticle(Particle.BUBBLE_COLUMN_UP, c, 200, 4, 4, 4, 0.3);
                world.playSound(c, Sound.ENTITY_ENDER_DRAGON_DEATH, 2.5f, 0.6f);
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(c) > far(96) * far(96)) continue;
                    p.showTitle(Title.title(legacy(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + (colossal ? "THE ABYSSAL KRAKEN HAS FALLEN" : "THE KRAKEN HAS FALLEN")), legacy(ChatColor.GRAY + "The deep is quiet again."),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(900))));
                }
                Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + title() + " " + ChatColor.GRAY + "has been slain!");
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
            pos.add(new Vector(0, -0.25 * ms, 0));
            for (Arm a : arms) { a.goal = a.root().add(new Vector(0, 17 * k, 0)).add(a.outward().multiply(5 * k)); a.follow = 0.1; }
            if (leaveT > 60) { removeEverything(); if (kraken == this) kraken = null; }
        }

        void rewards() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                double more = colossal ? kcfg("abyssal.reward-multiplier", 2.0) : 1;
                console("givemythicbag " + (int) Math.round(kcfg("rewards.mythic-bags", 18) * more) + " " + p.getName(), p);
                dropLocked(at, p, new ItemStack(Material.DIAMOND, Math.min(64, (int) Math.round(((int) kcfg("rewards.diamonds-min", 16) + random.nextInt((int) kcfg("rewards.diamonds-extra", 9))) * more))));
                if (colossal) { // the deep's own loot on top
                    console("darkloot bag " + (int) kcfg("abyssal.dark-loot-bags", 4) + " " + p.getName(), p);
                    console("darkloot rare " + p.getName() + " " + kcfg("abyssal.dark-loot-rare-chance", 0.5), p);
                    dropLocked(at, p, new ItemStack(Material.NETHERITE_INGOT, (int) kcfg("abyssal.netherite", 2)));
                    if (random.nextDouble() < kcfg("abyssal.cross-chance", 0.5)) console("giveaccessory cross 1 " + p.getName(), p); // 2.0.0: was the Dune worm's
                }
                if (random.nextDouble() < kcfg("rewards.suit-chance", 0.4)) console("givediving suit 1 " + p.getName(), p);
                // 2.0.0: the Mirror (the way to the Dark Ocean) comes from Diamond Jacob now, not the Kraken.
                int xp = (int) (kcfg("rewards.xp", 2000) * more);
                for (int left = xp; left > 0; ) { int amount = Math.min(left, 100); left -= amount; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(amount); }
                p.sendMessage(ChatColor.DARK_AQUA + "The Kraken's hoard is yours. " + ChatColor.GRAY + "(Only you can pick up your loot.)");
                console("index discover " + p.getName() + (atSea ? " the_kraken_at_sea" : colossal ? " the_abyssal_kraken" : " the_kraken"), p);
            }
        }

        void removeEverything() {
            storm.clear(); stormT = 0;
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
        // 400: the Dark Ocean is ~250 blocks deep (128 found no floor there, and the Abyssal Kraken was pushed up to the surface)
        for (int k = 0; k < 400 && y > world.getMinHeight(); k++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
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

    /**
     * 2.0.0: Diamond Jacob is harder (he's the only land boss left and drops the Mirror). A value still equal to its OLD
     * default (or missing) reads as the new one; a value the owner picked is kept.
     */
    static final Map<String, double[]> JACOB_HARDER = Map.ofEntries(
            Map.entry("health", new double[] {4000, 6000}),
            Map.entry("defense", new double[] {0.0, 0.15}),
            Map.entry("damage-multiplier", new double[] {1.0, 1.3}),
            Map.entry("walk-speed", new double[] {0.3, 0.34}),
            Map.entry("phase4-speed", new double[] {0.42, 0.5}),
            Map.entry("orb-damage", new double[] {100, 80}),
            Map.entry("orb-every-seconds", new double[] {20, 26}),
            Map.entry("spider-every-ticks", new double[] {240, 180}),
            Map.entry("max-spiders", new double[] {6, 9}),
            Map.entry("army-size", new double[] {15, 20}),
            Map.entry("reinforce-below", new double[] {4, 6}),
            Map.entry("reinforcements", new double[] {5, 7}),
            Map.entry("rest-multiplier", new double[] {1.0, 0.75}));

    private double jcfg(String path, double def) {
        double v = getConfig().getDouble("jacob." + path, def);
        double[] o = JACOB_HARDER.get(path);
        return o != null && Math.abs(v - o[0]) < 1e-9 ? o[1] : v;
    }

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
                m.setLore(List.of(ChatColor.GRAY + "A flanged war mace, pulled from the ground,", ChatColor.GRAY + "still humming with power. Heavy and slow.", ChatColor.GREEN + "Right-click: Ground Slam " + ChatColor.DARK_GRAY + "(10s cooldown)",
                        ChatColor.DARK_GRAY + "Dropped by Diamond Jacob."));
                m.setItemModel(new org.bukkit.NamespacedKey("faultline", "jacob_hammer"));
                m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_hammer_damage"), 10, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
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
                m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(new org.bukkit.NamespacedKey(this, "jacob_sword_damage"), 9, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
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
                // BUG FIX: the horn was used up BEFORE he spawned (and even in creative): a failed spawn ate it
                try { summonJacob(p.getLocation(), p); } catch (RuntimeException ex) { getLogger().log(Level.WARNING, "Couldn't summon Diamond Jacob", ex); }
                if (jacob == null) { p.sendMessage(ChatColor.RED + "Diamond Jacob couldn't appear here. " + ChatColor.GRAY + "(The horn wasn't used.)"); return; }
                if (p.getGameMode() != GameMode.CREATIVE) p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
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
                    Safe.vel(le, le.getLocation().toVector().subtract(at.toVector()).setY(0).multiply(0.3).setY(0.6));
                }
            }
            case "sword" -> { // Ember Dash
                if (now < jacobItemCooldown.getOrDefault(p.getUniqueId(), 0L)) return;
                jacobItemCooldown.put(p.getUniqueId(), now + 8000);
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
                dir.normalize();
                Safe.vel(p, dir.clone().multiply(1.8).setY(0.15));
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
                if (p == null || p.getGameMode() != GameMode.SPECTATOR) continue;
                // Bedrock can't look through another entity (Geyser has no spectator camera): it rides the shot itself
                if (bedrock(p)) { allowTeleport = true; p.teleport(at); allowTeleport = false; continue; }
                if (!cam.equals(p.getSpectatorTarget())) p.setSpectatorTarget(cam);
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
                    d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.addScoreboardTag(BedrockFx.HELD_TAG); d.setTeleportDuration(2); d.setInterpolationDuration(2);
                    // BUG FIX: THIRDPERSON_RIGHTHAND plus the rig's spear math (tuned for Don) left his sword, hammer, and bow
                    // sticking out at odd angles. He places his weapon himself now (renderWeapon), from a grip per item.
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                });
            }
            held.setItemStack(item);
            grip = Grip.of(item);
        }

        Grip grip;

        /** Where his hand closes on an item (model pixels) and which way it points: axis = toward the tip, face = the flat side. */
        record Grip(Vector3f at, Vector3f axis, Vector3f face, float size) {
            static final Vector3f DIAG = new Vector3f(1, 1, 0).normalize(), FLAT = new Vector3f(0, 0, 1);
            static Grip of(ItemStack item) {
                if (item == null || item.getType().isAir()) return null;
                org.bukkit.NamespacedKey m = item.hasItemMeta() ? item.getItemMeta().getItemModel() : null;
                String key = m != null ? m.getKey() : item.getType().getKey().getKey();
                return switch (key) {
                    case "jacob_hammer" -> new Grip(new Vector3f(8, 1.5f, 8), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), 1.0f); // handle up, head across
                    case "bow" -> new Grip(new Vector3f(4.5f, 9.5f, 8), DIAG, FLAT, 1.0f);   // middle of the limb; tips along the arm's forward
                    case "goat_horn" -> new Grip(new Vector3f(4, 3.5f, 8), DIAG, FLAT, 0.8f);
                    default -> new Grip(new Vector3f(3, 3, 8), DIAG, FLAT, 1.0f);            // swords, staffs: handle at the bottom-left
                };
            }
            /** Item model directions -> his arm's frame (axis -> forward out of the fist, face -> sideways). */
            Quaternionf toArm() {
                Vector3f third = new Vector3f(axis).cross(face);
                org.joml.Matrix3f src = new org.joml.Matrix3f(axis, face, third);
                org.joml.Matrix3f dst = new org.joml.Matrix3f(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                return new Quaternionf().setFromNormalized(dst.mul(src.transpose()));
            }
        }

        /** His weapon in his right fist, using the same joints and pose as renderRig. */
        void renderWeapon(Location root, float yaw, float scale, Pose pose) {
            if (held == null || !held.isValid()) return;
            if (grip == null) { held.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf())); return; }
            Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
            Quaternionf qBody = euler(pose.r[BODY]);
            Vector3f hips = new Vector3f(0, 12, 0);
            Vector3f shoulder = new Vector3f(JOINT[ARM_R][0], JOINT[ARM_R][1], JOINT[ARM_R][2]).sub(hips);
            qBody.transform(shoulder).add(hips);
            Quaternionf qArm = new Quaternionf(qBody).mul(euler(pose.r[ARM_R]));
            Vector3f hand = qArm.transform(new Vector3f(0, -10.6f, 0.4f)).add(shoulder).mul(scale / 16f);
            qYaw.transform(hand);
            Quaternionf rot = new Quaternionf(qYaw).mul(qArm).mul(grip.toArm());
            float size = scale * grip.size();
            // put the grip point (not the model's middle) in his fist
            Vector3f g = new Vector3f(grip.at()).sub(8, 8, 8).mul(size / 16f);
            rot.transform(g);
            Location at = root.clone().add(hand.x - g.x, hand.y - g.y + pose.drop, hand.z - g.z);
            at.setYaw(0); at.setPitch(0);
            held.teleport(at);
            held.setInterpolationDelay(0);
            held.setTransformation(new Transformation(new Vector3f(), new Quaternionf(rot).mul(facing(0)), new Vector3f(size, size, size), new Quaternionf()));
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
            Bukkit.getScheduler().runTask(FaultlineBosses.this, () -> { if (p.isValid()) Safe.vel(p, new Vector(0, Math.min(0, p.getVelocity().getY()), 0)); });
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
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 96 * 96) cinematic(p, false, null, inT, stayT, outT);
        }

        void flash(boolean white) {
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 96 * 96) cinematic(p, white, null, 0, 2, 1);
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
                heardMusic(p, "jacob");
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
            if (a.isEmpty() && cut == null && state != DEFEAT && st > 100 && pilot(this) == null) {
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
            if (ticks % 20 == 0) bossPart("Diamond Jacob", "army follows", () -> armyFollow(a));
            if (ticks % 40 == 0) bossPart("Diamond Jacob", "hitboxes hidden", this::rehide);
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
            Player pl = pilot(this);
            if (state == P1) birdFly();
            if (flinch > 0) flinch--;
            if (a.isEmpty() && pl == null) return;
            Player target = pl != null ? pilotTarget(pl, a) : nearest(a);
            int ph = phase();
            // pink orbs: phases 1-3, every 20 seconds (only when there's someone to hit them)
            if (ph <= 3 && !a.isEmpty() && --orbTimer <= 0) { orbTimer = (int) (jcfg("orb-every-seconds", 20) * 20); spawnOrb(a); }
            // spiders from his staff: phases 2-3 (in boss form, only when the admin calls them)
            if (pl == null && (state == P2 || state == P3) && --spiderTimer <= 0 && attack < 0) { spiderTimer = (int) jcfg("spider-every-ticks", 240); attack = 99; t = 0; }
            if (pl != null) { // boss form: he walks where the admin walks, faces where they face, and uses the move they pick
                if (state != P1) { if (attack < 0) pilotWalk(pl); else faceToward(target); }
                if (attack < 0) {
                    int q = takeMove(this);
                    if (q < 0) return;
                    attack = q; lastAttack = q; t = 0; lock = null; marks.clear();
                }
            } else if (state != P1) walk(target);
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
            if (pl == null) bossPart("Diamond Jacob", "watchdog", () -> watchdog(a));
        }

        /** Boss form, between moves: he follows the admin on foot and faces where they face. */
        void pilotWalk(Player pl) {
            Vector to = pl.getLocation().toVector().subtract(pos).setY(0);
            float want = pl.getLocation().getYaw();
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-15, Math.min(15, diff));
            double d = to.length();
            if (d > 0.2) {
                stepTo(safeDir(to, fwd()).multiply(Math.min(d, speed() * 1.8)));
                pose = Pose.lerp(pose, stridePose(phase() == 4 ? 1.3f : 1f), 0.35f);
            } else {
                pose = Pose.lerp(pose, readyPose(), 0.2f);
                settle();
            }
        }

        void faceToward(Player target) {
            Vector to = target.getLocation().toVector().subtract(pos).setY(0);
            if (to.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-12, Math.min(12, diff));
        }

        void end(int rest) {
            attack = -1; t = 0;
            recover = (int) Math.round(rest * (phase() == 4 ? 0.6 : phase() == 3 ? 0.8 : 1.0) * jcfg("rest-multiplier", 1.0));
            pose = state == P1 ? riderPose(false) : readyPose();
        }

        // ---------- phase 1: the hawk ----------
        void birdFly() {
            if (attack == 3) return; // the dive moves the bird itself
            Player pl = pilot(this);
            if (pl != null) { // boss form: the hawk carries him wherever the admin flies (he sits where they are)
                Vector want = pl.getLocation().toVector().add(new Vector(0, -1.7, 0)).subtract(birdPos).multiply(0.3);
                if (want.length() > 1.4) want = want.normalize().multiply(1.4);
                birdVel = want;
                birdPos.add(birdVel);
                return;
            }
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

        /** Riding the hawk: knees gripping, swaying with its wingbeats. Drawing: bow arm out, string pulled to his cheek. */
        Pose riderPose(boolean drawing) {
            float b = (float) Math.sin(ticks * 0.11);
            Pose p = new Pose().set(LEG_R, -72, 0, -16).set(LEG_L, -72, 0, 16).drop(-0.5f).set(BODY, 6 + b * 1.5f, 0, b * 1.5f);
            if (drawing) p.set(ARM_R, -90, -16, 0).set(ARM_L, -86, 40, 0).set(BODY, 4, 16, 0).set(HEAD, 2, -14, 0);
            else p.set(ARM_R, -34 + b * 3, 0, -12).set(ARM_L, -42 - b * 3, 0, 12).set(HEAD, 6, 0, 0);
            return p;
        }

        // ---------- animation ----------
        float stride, headYaw;      // walk cycle (advanced by the distance he actually covers), where his head looks
        int recoilT;                // a pink orb rocks him back
        Vector lastDrawPos;

        /** Combat-ready idle: feet apart, weight shifting, breathing, weapon up. Ablaze: hunched forward, blade low. */
        Pose readyPose() {
            float b = (float) Math.sin(ticks * 0.09), w = (float) Math.sin(ticks * 0.045);
            Pose p = new Pose().set(LEG_R, -8, 0, -7).set(LEG_L, 10, 0, 7).drop(-0.06f + b * 0.015f).set(HEAD, -6 - b, -6, 0);
            if (ablaze) return p.set(BODY, 16 + b * 2, 10 + w * 3, 0).set(ARM_R, -40 + b * 3, -10, -16).set(ARM_L, -18 - b * 3, 0, 22);
            return p.set(BODY, 7 + b * 1.5f, 6 + w * 3, 0).set(ARM_R, -32 + b * 2, 0, -10).set(ARM_L, -16 - b * 2, 18, 14);
        }

        /** A heavy armored stride, synced to the ground he covers (no sliding feet). a > 1 = a sprint. */
        Pose stridePose(float a) {
            float sn = (float) Math.sin(stride), cs = (float) Math.cos(stride);
            return new Pose().set(LEG_R, sn * 34 * a, 0, -3).set(LEG_L, -sn * 34 * a, 0, 3)
                    .set(ARM_R, (ablaze ? -46 : -30) - sn * 10 * a, 0, -12).set(ARM_L, sn * 32 * a - 8, 0, 12)
                    .set(BODY, 9 * a + 2, -sn * 7 * a, 0).set(HEAD, -7 * a, sn * 5 * a, 0)
                    .drop(-(float) Math.abs(cs) * 0.08f * a);
        }

        Pose p(float[]... parts) { // compact builder: {part, x, y, z}..., last {-1, drop}
            Pose q = new Pose();
            for (float[] f : parts) { if (f[0] < 0) q.drop(f[1]); else q.set((int) f[0], f[1], f[2], f[3]); }
            return q;
        }
        // hammer: two-handed overhead slam
        Pose liftPose()     { return p(new float[]{ARM_R, -100, 0, -10}, new float[]{ARM_L, -92, 22, 10}, new float[]{BODY, -2, 0, 0}, new float[]{-1, -0.05f}); }
        Pose overheadPose() { return p(new float[]{ARM_R, -176, 0, -8}, new float[]{ARM_L, -166, 16, 8}, new float[]{BODY, -15, 0, 0}, new float[]{HEAD, -14, 0, 0},
                                       new float[]{LEG_R, 10, 0, -6}, new float[]{LEG_L, -14, 0, 6}, new float[]{-1, 0.05f}); }
        Pose slamPose()     { return p(new float[]{ARM_R, -36, 0, -4}, new float[]{ARM_L, -32, 18, 4}, new float[]{BODY, 36, 0, 0}, new float[]{HEAD, 14, 0, 0},
                                       new float[]{LEG_R, -32, 0, -8}, new float[]{LEG_L, 26, 0, 8}, new float[]{-1, -0.34f}); }
        // one-handed: big overhead chop, a throw, a catch
        Pose chopWindupPose() { return p(new float[]{ARM_R, -168, 0, -18}, new float[]{ARM_L, -40, 0, 26}, new float[]{BODY, -10, 24, 0}, new float[]{HEAD, -6, -18, 0},
                                         new float[]{LEG_R, 18, 0, -4}, new float[]{LEG_L, -16, 0, 4}); }
        Pose chopPose()       { return p(new float[]{ARM_R, -34, 0, 12}, new float[]{ARM_L, 22, 0, 18}, new float[]{BODY, 30, -26, 0}, new float[]{HEAD, 10, 20, 0},
                                         new float[]{LEG_R, -34, 0, -6}, new float[]{LEG_L, 24, 0, 6}, new float[]{-1, -0.22f}); }
        Pose throwPose()      { return p(new float[]{ARM_R, -60, 0, -8}, new float[]{ARM_L, 18, 0, 22}, new float[]{BODY, 24, -30, 0}, new float[]{HEAD, 4, 24, 0},
                                         new float[]{LEG_R, -30, 0, -6}, new float[]{LEG_L, 22, 0, 6}, new float[]{-1, -0.12f}); }
        Pose catchPose()      { return p(new float[]{ARM_R, -128, 0, -14}, new float[]{ARM_L, -20, 0, 18}, new float[]{BODY, -6, 0, 0}, new float[]{HEAD, -18, 0, 0}); }
        // the blade (phase 4): forehand and backhand cuts, both with the sword arm
        Pose foreWindup()  { return p(new float[]{ARM_R, -150, 0, -42}, new float[]{ARM_L, -30, 0, 26}, new float[]{BODY, 8, 30, 0}, new float[]{LEG_R, 16, 0, -4}, new float[]{LEG_L, -14, 0, 4}); }
        Pose foreSlash()   { return p(new float[]{ARM_R, -48, 0, 26}, new float[]{ARM_L, 20, 0, 22}, new float[]{BODY, 18, -32, 0}, new float[]{HEAD, 6, 22, 0},
                                      new float[]{LEG_R, -30, 0, -6}, new float[]{LEG_L, 22, 0, 6}, new float[]{-1, -0.16f}); }
        Pose backWindup()  { return p(new float[]{ARM_R, -92, 52, 0}, new float[]{ARM_L, -40, 0, 30}, new float[]{BODY, 10, -26, 0}, new float[]{LEG_R, -12, 0, -4}, new float[]{LEG_L, 14, 0, 4}); }
        Pose backSlash()   { return p(new float[]{ARM_R, -92, -72, 0}, new float[]{ARM_L, 10, 0, 30}, new float[]{BODY, 16, 32, 0}, new float[]{HEAD, 4, -20, 0},
                                      new float[]{LEG_R, 22, 0, -6}, new float[]{LEG_L, -30, 0, 6}, new float[]{-1, -0.16f}); }
        Pose skyPose()     { return p(new float[]{ARM_R, -172, 0, -14}, new float[]{ARM_L, -168, 0, 14}, new float[]{BODY, -14, 0, 0}, new float[]{HEAD, -32, 0, 0},
                                      new float[]{LEG_R, 0, 0, -10}, new float[]{LEG_L, 0, 0, 10}); }

        Vector bowAt() { return pos.clone().add(new Vector(0, 1.4 * scale, 0)).add(fwd().multiply(0.6)); }

        void piercingArrow(Player target) {
            int w = 22;
            pose = t <= w ? Pose.lerp(riderPose(false), riderPose(true), Math.min(1, t / 8f))
                    : Pose.lerp(riderPose(true).set(ARM_L, -62, 70, -18), riderPose(false), Math.min(1, (t - w) / 10f)); // the string snaps forward
            if (t == w) {
                Vector aim = safeDir(eye(target).subtract(bowAt()), fwd());
                shots.add(new JShot(bowAt(), aim.multiply(2.4), Material.SPECTRAL_ARROW, jcfg("damage.piercing-arrow", 15), true, false, Guard.UNBLOCKABLE, 0.0));
                world.playSound(loc(), Sound.ENTITY_ARROW_SHOOT, 2f, 0.6f);
            }
            if (t > w + 18) end(30);
        }

        void arrowVolley(Player target) {
            int c = t % 16; // draw, loose, draw, loose
            pose = c < 12 ? riderPose(true) : riderPose(true).set(ARM_L, -64, 66, -16);
            if (t == 16 || t == 32) {
                Vector aim = safeDir(eye(target).subtract(bowAt()), fwd());
                for (int i = -2; i <= 2; i++) shots.add(new JShot(bowAt(), aim.clone().rotateAroundY(Math.toRadians(i * 11)).multiply(1.6), Material.ARROW, jcfg("damage.volley-arrow", 7), false, false, Guard.BLOCKABLE, 0.01));
                world.playSound(loc(), Sound.ENTITY_ARROW_SHOOT, 2f, 0.9f);
            }
            if (t > 50) end(34);
        }

        void talonDive(Player target, List<Player> a) {
            pose = t < 20 ? riderPose(false) : riderPose(false).add(BODY, 26, 0, 0).set(ARM_R, -64, 0, -8).set(ARM_L, -64, 0, 8).set(HEAD, -20, 0, 0); // flattened on its back for the dive
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
                    Safe.vel(p, safeDir(p.getLocation().toVector().subtract(birdPos).setY(0), new Vector(1, 0, 0)).multiply(1.0).setY(0.5));
                }
            } else if (t < 70) { // climbs back up to its circle
                Vector want = circleSpot(); // (it used to climb back to the summon point, through the mountain)
                birdPos.add(want.subtract(birdPos).multiply(0.08));
            } else end(30);
        }

        void arrowRain(List<Player> a) {
            pose = Pose.lerp(riderPose(false), riderPose(true).set(ARM_R, -158, -10, -8).set(ARM_L, -150, 34, 0).set(HEAD, -34, -10, 0).set(BODY, -10, 10, 0), Math.min(1, t / 10f)); // bow to the sky
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
                pose = Pose.lerp(pose, stridePose(phase() == 4 ? 1.3f : 1f), 0.35f);
                // blocked by a wall or cliff for a moment: he pearls over it (and lands behind you)
                if (blocked > 15 && pearlCd <= 0) { blocked = 0; pearlCd = 80; attack = 11; t = 0; return; }
            } else if (attack < 0) pose = Pose.lerp(pose, readyPose(), 0.2f);
            settle();
        }

        void hammerSlam(List<Player> a) {
            int w = 18;
            pose = anim(t, new int[]{0, 7, 14, 16, 18, 30, 44}, new Pose[]{readyPose(), liftPose(), overheadPose(), overheadPose().add(BODY, -3, 0, 0), slamPose(), slamPose(), readyPose()});
            if (t < w) { }
            else if (t == w) {
                lock = pos.clone();
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
            }
            // flicks the pearl off his off hand, appears crouched behind you, rises into a chop
            Pose flick = readyPose().set(ARM_L, -118, 0, 8).set(BODY, 4, -12, 0);
            pose = anim(t, new int[]{0, 3, 10, 12, 17, 20, 34}, new Pose[]{readyPose(), flick, readyPose(), crouchPose(), chopWindupPose(), chopPose(), readyPose()});
            if (t <= 12) { if (pearlFly(t, 12)) { Vector to = target.getLocation().toVector().subtract(pos); yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())); } }
            else if (t < 20) { }
            else if (t == 20) {
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
            if (t < 10) pose = anim(t, new int[]{0, 10}, new Pose[]{readyPose(), crouchPose()});
            else if (t < 70) {
                yaw += 38;
                float bob = (float) Math.sin(t * 0.8) * 3; // hammer out at arm's length, leaning into the spin
                pose = p(new float[]{ARM_R, -14 + bob, 0, -86}, new float[]{ARM_L, -14 - bob, 0, 80}, new float[]{BODY, 14, 0, -6},
                         new float[]{HEAD, -10, 0, 0}, new float[]{LEG_R, -10, 0, -12}, new float[]{LEG_L, 10, 0, 12}, new float[]{-1, -0.1f});
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
            if (t < 14) pose = anim(t, new int[]{0, 5, 14}, new Pose[]{readyPose(), crouchPose().set(ARM_R, 30, 0, -20), chopWindupPose()});
            else if (thrownHammer != null) { // follow-through, then reaching up to catch it as it spins back
                boolean incoming = hammerBack && hammerPos != null && hammerPos.distanceSquared(pos) < 6 * 6;
                pose = Pose.lerp(pose, incoming ? catchPose() : throwPose(), incoming ? 0.35f : 0.25f);
            }
            if (t == 14) {
                pose = throwPose();
                hammerPos = pos.clone().add(new Vector(0, 1.6, 0));
                hammerVel = safeDir(eye(target).subtract(hammerPos), fwd()).multiply(1.1);
                hammerBack = false;
                thrownHammer = world.spawn(hammerPos.toLocation(world), ItemDisplay.class, d -> { d.setItemStack(model(Material.NETHERITE_AXE, "jacob_hammer", true)); d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG); d.setTeleportDuration(1); });
                hold(new ItemStack(Material.AIR));
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
            Pose raise = readyPose().set(ARM_R, -165, 0, -10).set(HEAD, -20, 0, 0).set(BODY, -6, 0, 0); // staff to the sky...
            Pose plant = crouchPose().set(ARM_R, -52, 0, -6).set(ARM_L, -30, 0, 20);                    // ...and driven into the ground
            pose = anim(t, new int[]{0, 7, 10, 22, 30}, new Pose[]{readyPose(), raise, plant, plant, readyPose()});
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

        /**
         * His riders stay with him: a soldier with no fighter near it (they ran off, or it lost them) rides back to his side
         * instead of standing where it was; one left far behind (over 64 blocks) is brought back to him.
         */
        void armyFollow(List<Player> a) {
            if (cut != null) return;
            Location me = loc();
            int i = 0;
            for (LivingEntity s : army) {
                i++;
                if (!(s instanceof org.bukkit.entity.Mob sm) || !sm.hasAI() || !s.isValid()) continue;
                boolean fighterNear = false;
                for (Player p : a) if (p.getWorld().equals(world) && p.getLocation().distanceSquared(s.getLocation()) < 18 * 18) { fighterNear = true; break; }
                if (fighterNear) continue; // there's someone to fight right here
                double dj = s.getLocation().distanceSquared(me);
                if (dj < 7 * 7) continue; // already at his side
                // a ring around him, each rider its own spot
                double ang = Math.PI * 2 * i / Math.max(1, army.size());
                Location spot = me.clone().add(Math.cos(ang) * 5, 0, Math.sin(ang) * 5);
                spot.setY(floorY(spot.getX(), spot.getZ(), me.getY() + 3));
                Entity mount = s.getVehicle();
                if (dj > 64 * 64) { // left far behind: back to him
                    Entity mover = mount != null ? mount : s;
                    mover.teleport(spot);
                    world.spawnParticle(Particle.CLOUD, spot.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
                    continue;
                }
                if (sm.getTarget() instanceof Player tp && (!a.contains(tp) || tp.getLocation().distanceSquared(s.getLocation()) > 18 * 18)) sm.setTarget(null);
                sm.getPathfinder().moveTo(spot, 1.4);
                if (mount instanceof org.bukkit.entity.Mob horse) horse.getPathfinder().moveTo(spot, 1.6);
            }
        }

        /** Safety net: his hitboxes stay invisible (Java), and Bedrock players, who hit his stand-ins, never see them at all. */
        void rehide() {
            List<LivingEntity> boxes = new ArrayList<>();
            if (hitbox.isValid()) boxes.add(hitbox);
            if (birdBox != null && birdBox.isValid()) boxes.add(birdBox);
            for (LivingEntity b : boxes) {
                if (!b.isInvisible() || !b.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) hideForGood(b);
                for (Player p : world.getPlayers()) if (bedrock(p) && p.canSee(b)) p.hideEntity(FaultlineBosses.this, b);
            }
            for (JOrb o : orbs) if (o.box.isValid() && (!o.box.isInvisible() || !o.box.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY))) hideForGood(o.box);
        }

        // ---------- phase 3: the army ----------
        void cavalryCharge(Player target, List<Player> a) {
            Pose rally = p(new float[]{ARM_R, -168, 0, -16}, new float[]{ARM_L, -36, 0, 30}, new float[]{BODY, -10, 0, 0}, new float[]{HEAD, -26, 0, 0},
                           new float[]{LEG_R, 0, 0, -10}, new float[]{LEG_L, 0, 0, 10});                                   // weapon raised: "CHARGE!"
            Pose lead = p(new float[]{ARM_R, -108, 0, -6}, new float[]{ARM_L, 12, 0, 20}, new float[]{BODY, 14, 0, 0}, new float[]{HEAD, -6, 0, 0},
                          new float[]{LEG_R, -24, 0, -4}, new float[]{LEG_L, 20, 0, 4});                                     // pointing them at you
            pose = anim(t, new int[]{0, 9, 15, 44, 55}, new Pose[]{readyPose(), rally, lead, lead, readyPose()});
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
                Safe.vel(horse, safeDir(to, new Vector(1, 0, 0)).multiply(0.7).setY(horse.getVelocity().getY()));
                if (t % 4 == 0) world.spawnParticle(Particle.BLOCK, horse.getLocation(), 6, 0.4, 0.1, 0.4, 0, horse.getLocation().subtract(0, 1, 0).getBlock().getBlockData());
                for (Player p : a) if (p.getLocation().distanceSquared(horse.getLocation()) < 2.4 * 2.4) {
                    strike(p, jcfg("damage.cavalry", 9), Guard.HEAVY, horse.getLocation().toVector(), false, true);
                    if (!jacobCounter(p, "banner_of_defiance")) Safe.vel(p, safeDir(to, new Vector(1, 0, 0)).multiply(0.9).setY(0.4));
                }
            }
            if (t > 55) end(40);
        }

        void spearWall(Player target, List<Player> a) {
            Pose thrust = p(new float[]{ARM_R, -84, 0, -4}, new float[]{ARM_L, 24, 0, 18}, new float[]{BODY, 22, -12, 0}, new float[]{HEAD, 2, 10, 0},
                            new float[]{LEG_R, -36, 0, -6}, new float[]{LEG_L, 26, 0, 6}, new float[]{-1, -0.2f});
            pose = anim(t, new int[]{0, 12, 20, 22, 30, 40}, new Pose[]{readyPose(), chopWindupPose(), chopWindupPose().add(BODY, -4, 4, 0), thrust, thrust, readyPose()});
            if (t == 0) {
                Vector dir = safeDir(target.getLocation().toVector().subtract(pos).setY(0), fwd());
                for (int s = -1; s <= 1; s++) marks.add(dir.clone().rotateAroundY(Math.toRadians(s * 22)));
            }
            // BUG FIX: the warning lines were drawn at his own height, so on a slope they floated in the air or sank into
            // the ground; and every spear faced the same way, so lines running the other way showed up as paper-thin slivers.
            if (t < 22 && t % 3 == 0) for (Vector dir : marks) for (int i = 2; i <= 14; i += 2) {
                Location ml = loc().add(dir.clone().multiply(i));
                ml.setY(floorY(ml.getX(), ml.getZ(), ml.getY() + 1) + 0.15);
                world.spawnParticle(Particle.DUST, ml, 1, 0.1, 0, 0.1, 0, new Particle.DustOptions(Color.fromRGB(220, 40, 40), 1.3f));
            }
            if (t == 22) {
                world.playSound(loc(), Sound.ITEM_TRIDENT_THROW, 2f, 0.5f);
                for (Vector dir : marks) for (int i = 2; i <= 14; i += 2) {
                    Location l = loc().add(dir.clone().multiply(i));
                    l.setY(floorY(l.getX(), l.getZ(), l.getY() + 1));
                    // the flat spear faces across its line (seen side-on by whoever's dodging), a little tilt each, and it
                    // bursts up out of the ground instead of just appearing
                    l.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())) + 90 + (float) (random.nextGaussian() * 8));
                    l.setPitch(0);
                    float lean = (float) Math.toRadians(-45 + random.nextGaussian() * 5);
                    ItemDisplay spear = world.spawn(l, ItemDisplay.class, d -> { d.setItemStack(new ItemStack(Material.IRON_SPEAR)); d.setPersistent(false); d.addScoreboardTag(DISPLAY_TAG);
                        d.setTransformation(new Transformation(new Vector3f(0, -1.6f, 0), new Quaternionf().rotateZ(lean), new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf())); });
                    Bukkit.getScheduler().runTaskLater(FaultlineBosses.this, () -> {
                        if (!spear.isValid()) return;
                        spear.setInterpolationDelay(0); spear.setInterpolationDuration(3);
                        spear.setTransformation(new Transformation(new Vector3f(0, 0.9f, 0), new Quaternionf().rotateZ(lean), new Vector3f(1.8f, 1.8f, 1.8f), new Quaternionf()));
                    }, 1L);
                    org.bukkit.block.Block under = l.clone().subtract(0, 0.5, 0).getBlock();
                    if (i % 4 == 2 && !under.getType().isAir()) world.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0, under.getBlockData());
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
                pose = Pose.lerp(stridePose(1.5f), n % 2 == 0 ? foreWindup() : backWindup(), c / 6f); // dash in, blade drawn back
                world.spawnParticle(Particle.FLAME, loc().add(0, 0.6, 0), 4, 0.3, 0.3, 0.3, 0.01);
            } else if (c == 6) {
                pose = n % 2 == 0 ? foreSlash() : backSlash(); // forehand, backhand, forehand
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.8f);
                world.spawnParticle(Particle.FLAME, loc().add(fwd().multiply(1.5)).add(0, 1, 0), 20, 0.8, 0.4, 0.8, 0.02);
                for (Player p : a) if (inFront(p, 3.4)) strike(p, jcfg("damage.ember-flurry", 9), Guard.BLOCKABLE, pos, true, false);
            } else pose = Pose.lerp(pose, n % 2 == 0 ? foreSlash() : backSlash(), 0.3f); // holds the follow-through
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
                pose = landPose().set(ARM_R, -40, 0, -6).set(HEAD, 14, 0, 0); // lands blade-first
                world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
                for (int i = 0; i < 30; i++) { double ang = Math.PI * 2 * i / 30; world.spawnParticle(Particle.FLAME, loc().add(Math.cos(ang) * 4, 0.3, Math.sin(ang) * 4), 3, 0.2, 0.2, 0.2, 0.02); }
                for (Player p : a) if (p.getLocation().toVector().distanceSquared(pos) < 5 * 5) strike(p, jcfg("damage.phoenix-leap", 18), Guard.HEAVY, pos, true, false);
            } else if (t > 46) end(30);
        }

        void infernoPillar(List<Player> a) {
            pose = anim(t, new int[]{0, 10, 24, 26, 36, 44}, new Pose[]{readyPose(), skyPose(), skyPose().add(BODY, -4, 0, 0), slamPose(), slamPose(), readyPose()}); // calls the fire down
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
            pose = anim(t, new int[]{0, 8, 20}, new Pose[]{readyPose(), pointPose(), pointPose()});
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
            pose = anim(t, new int[]{0, 14, 17, 30}, new Pose[]{readyPose(), crouchPose(), spreadPose().add(HEAD, -10, 0, 0).drop(0.08f), readyPose()});
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
                        recoilT = 14;
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

        Vector fallVel = new Vector();
        boolean birdDown;
        int crashT;

        Vector birdFwd() { return new Vector(-Math.sin(Math.toRadians(birdYaw)), 0, Math.cos(Math.toRadians(birdYaw))); }

        /** The hawk hits the ground: dust and feathers, and he's thrown off beside it. */
        void crash() {
            birdDown = true; crashT = st; fallVel = new Vector(); birdVel = new Vector();
            Location at = birdPos.toLocation(world);
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
            world.playSound(at, Sound.ENTITY_PARROT_DEATH, 2.5f, 0.4f);
            world.spawnParticle(Particle.BLOCK, at, 90, 2.2, 0.4, 2.2, 0.1, at.clone().subtract(0, 1.2, 0).getBlock().getBlockData());
            world.spawnParticle(Particle.ITEM, at, 40, 1.5, 0.5, 1.5, 0.12, new ItemStack(Material.FEATHER));
            world.spawnParticle(Particle.CLOUD, at, 20, 1.5, 0.3, 1.5, 0.03);
            Vector side = new Vector(birdFwd().getZ(), 0, -birdFwd().getX());
            pos = birdPos.clone().add(side.multiply(3.0)); pos.setY(floorY(pos.getX(), pos.getZ(), pos.getY() + 2));
            hold(new ItemStack(Material.AIR));
        }

        /** Cutscene 1: an arrow takes down the hawk; he pulls an enchanted hammer from the ground. */
        void cut1Tick() {
            if (cut == null) return;
            int k = st;
            if (k == 2) black(8, 22, 10);
            if (k < 40) cut.shot(camAt(birdPos, 11, 2, 70));
            if (k == 30) shots.add(new JShot(birdPos.clone().add(new Vector(20, 6, 0)), new Vector(-2.0, -0.6, 0), Material.SPECTRAL_ARROW, 0, false, false, Guard.BLOCKABLE, 0));
            if (k == 40) { // the hit: it jolts up and to the side, still carried forward by its speed
                world.playSound(birdPos.toLocation(world), Sound.ENTITY_PARROT_HURT, 3f, 0.4f);
                world.playSound(birdPos.toLocation(world), Sound.ENTITY_PHANTOM_HURT, 2.5f, 0.5f);
                world.spawnParticle(Particle.CLOUD, birdPos.toLocation(world), 30, 1.2, 0.6, 1.2, 0.05);
                world.spawnParticle(Particle.ITEM, birdPos.toLocation(world), 25, 1.0, 0.6, 1.0, 0.08, new ItemStack(Material.FEATHER));
                clearShots();
                fallVel = birdFwd().multiply(0.6).setY(0.28);
            }
            // BUG FIX: the hawk used to spin like a drill all the way down (and kept spinning on the ground). Now it noses over
            // and falls in an arc with its wings flailing, crashes, and lies still on its side.
            if (k > 40 && !birdDown) {
                fallVel.multiply(0.96).setY(Math.max(-1.15, fallVel.getY() - 0.07));
                birdPos.add(fallVel);
                double ground = floorY(birdPos.getX(), birdPos.getZ(), birdPos.getY() + 2) + 0.9;
                if (birdPos.getY() <= ground || k >= 76) { birdPos.setY(ground); crash(); }
                else if (k % 3 == 0) world.spawnParticle(Particle.ITEM, birdPos.toLocation(world), 3, 0.8, 0.4, 0.8, 0.03, new ItemStack(Material.FEATHER));
                cut.shot(camAt(birdPos, 13, 5, 40));
            }
            if (birdDown && k <= 76) {
                cut.shot(camAt(birdPos, 11, 3.5, 40));
                if (k > crashT + 2) pose = landPose().drop(-0.6f).set(BODY, 50, 0, 0); // thrown clear, on his hands and knees
            }
            if (k > 76 && k < 150) cut.shot(camAt(pos, 4.5, 1.2, 15));
            if (k > 76 && k < 96) pose = Pose.lerp(pose, kneelPose(ticks), 0.2f);
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
                cinematic(p, false, legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "It's not over yet..."), 0, 72, 4);
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
                if (getConfig().getBoolean("jacob.rewards.mirror", true)) console("givelostmirror 1 " + p.getName(), p); // 2.0.0: the way to the Dark Ocean
                if (random.nextDouble() < jcfg("rewards.ankh-piece-chance", 0.5)) console("giveaccessory ankhpiece 1 " + p.getName(), p); // 2.0.0: was the Demon Eye's
                p.sendMessage(ChatColor.AQUA + "Diamond Jacob's hammer, his blade, his five charms and a strange Mirror are yours.");
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
                if (falling && !birdDown) birdVel = fallVel.clone(); // pitch follows the real fall (noses over as it drops)
                if (flat > 0.03 && !falling) {
                    float want = (float) Math.toDegrees(Math.atan2(-birdVel.getX(), birdVel.getZ()));
                    float diff = ((want - birdYaw) % 360 + 540) % 360 - 180;
                    birdYaw += Math.max(-7, Math.min(7, diff));
                    if (!falling) birdRoll += ((float) Math.max(-0.6, Math.min(0.6, -diff * 0.035)) - birdRoll) * 0.12f;
                }
                if (falling) birdRoll += ((birdDown ? 1.35f : 0.75f) - birdRoll) * (birdDown ? 0.3f : 0.07f); // keels over (once), then lies on its side
                float wantPitch = birdDown ? 0.1f : (float) Math.max(-0.7, Math.min(0.9, -Math.atan2(birdVel.getY(), Math.max(0.2, Math.hypot(birdVel.getX(), birdVel.getZ())))));
                birdPitch += (wantPitch - birdPitch) * (birdDown ? 0.3f : 0.15f);
                // the wings: glide, beat hard to climb, fold back to dive, flail as it falls
                boolean diving = state == P1 && attack == 3 && t >= 20 && t < 36;
                boolean climbing = birdVel.getY() > 0.05 || (attack == 3 && t < 20);
                flapPh += birdDown ? 0 : diving ? 0.05f : falling ? 0.75f : climbing ? 0.38f : 0.12f;
                float amp = birdDown ? 0 : diving ? 0.05f : falling ? 0.5f * Math.max(0.3f, 1 - (st - 40) / 40f) : climbing ? 0.6f : 0.15f; // flailing weakens as it drops
                flapA += ((float) (Math.sin(flapPh) * amp + (birdDown ? -0.42 : diving ? -0.25 : 0.08)) - flapA) * (birdDown ? 0.25f : 0.5f);
                sweepA += ((birdDown ? 0.35f : diving ? 0.9f : falling ? 0.3f : 0f) - sweepA) * 0.15f;
                tailFanB += ((float) Math.min(0.5, Math.abs(birdRoll) * 0.8) - tailFanB) * 0.1f;
                Player look = nearest(active()); // its head tracks who it's hunting
                if (birdDown) birdHeadYaw += (0.55f - birdHeadYaw) * 0.2f; // head lolls to one side
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
                if (state == P1 || state == ARRIVE || (state == CUT1 && !birdDown)) { // he rides on its back (and tilts with it)
                    pos = birdAt(0, 5.5f, -1.5f);
                    yaw = birdYaw;
                    if (state == P1 && attack >= 1 && attack <= 4) { Player tgt = nearest(active()); if (tgt != null) { Vector to = tgt.getLocation().toVector().subtract(pos); yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())); } }
                }
            }
            hitbox.teleport(pos.toLocation(world).add(0, 0.2, 0));
            // the walk cycle follows the ground he actually covers (a pearl or a cutscene jump doesn't count)
            if (lastDrawPos != null) {
                double moved = Math.hypot(pos.getX() - lastDrawPos.getX(), pos.getZ() - lastDrawPos.getZ());
                if (moved < 1.5) stride += (float) (moved * 2.9);
            }
            lastDrawPos = pos.clone();
            Pose p = pose.copy();
            p.add(BODY, (float) Math.sin(ticks * 0.08) * 1.5f, 0, 0);
            if (flinch > 0) p.add(BODY, -flinch * 2f, 0, 0).add(HEAD, -flinch * 2f, 0, 0);
            if (recoilT > 0) { p = Pose.lerp(p, recoilPose(), recoilT / 14f * 0.85f); recoilT--; } // a pink orb rocks him back
            // he watches whoever he's fighting: head (and a little of his chest) turn toward them
            float wantHead = 0;
            if (cut == null && state != DEFEAT && state != ARRIVE && attack != 12) {
                Player look = nearest(active());
                if (look != null) {
                    Vector to = look.getLocation().toVector().subtract(pos);
                    float a = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                    wantHead = Math.max(-55, Math.min(55, ((a - yaw) % 360 + 540) % 360 - 180));
                }
            }
            headYaw += (wantHead - headYaw) * 0.18f;
            p.add(HEAD, 0, -headYaw * 0.8f, 0).add(BODY, 0, -headYaw * 0.2f, 0);
            shown = Pose.lerp(shown, p, 0.45f);
            renderRig(parts, null, pos.toLocation(world), yaw, scale, shown);
            renderWeapon(pos.toLocation(world), yaw, scale, shown);
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


    private final Map<String, Long> lastErrorLog = new HashMap<>();
    private final Map<String, Long> bossPartLog = new HashMap<>();

    /** Logs one boss part's error (at most every 30 seconds per part), so the console says exactly what failed. */
    void logBossPart(String boss, String what, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - bossPartLog.getOrDefault(boss + what, 0L) < 30000) return;
        bossPartLog.put(boss + what, now);
        getLogger().log(java.util.logging.Level.SEVERE, "[" + boss + "] error in " + what + " (skipped, the fight keeps going; please report this):", e);
    }

    /** Runs one part of a boss's update; an error is logged and the rest of the boss keeps going. */
    void bossPart(String boss, String what, Runnable r) {
        try {
            r.run();
            if (what.equals("fight") && !failStreak.isEmpty()) failStreak.remove(boss);
        } catch (RuntimeException e) {
            logBossPart(boss, what, e);
            if (what.equals("fight")) stuck(boss); // a fight that errors every tick is stuck (the Lost Explorer)
        }
    }

    private void safely(String what, Runnable r) {
        try {
            r.run();
            if (!failStreak.isEmpty()) failStreak.remove(what);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog.getOrDefault(what, 0L) > 30000) {
                lastErrorLog.put(what, now);
                getLogger().log(java.util.logging.Level.SEVERE, "[" + what + "] error while updating (it keeps running; please report this):", t);
            }
            stuck(what);
        }
    }

    /** How many updates in a row have failed, per boss. */
    private final Map<String, Integer> failStreak = new HashMap<>();

    /**
     * A boss whose update has failed every tick for 5 seconds is frozen for good: before 1.4.8 it stayed "already here"
     * until a restart, so nobody could summon it again. Now it's removed (its model, hitbox, music, arena) and can be
     * summoned again; the error is still in the console.
     */
    void stuck(String what) {
        int n = failStreak.merge(what, 1, Integer::sum);
        if (n < 100) return;
        failStreak.remove(what);
        Runnable reset = switch (what) {
            case "Kraken" -> () -> { try { if (kraken != null) kraken.removeEverything(); } finally { kraken = null; } };
            case "Diamond Jacob" -> () -> { try { if (jacob != null) jacob.removeEverything(); } finally { jacob = null; } };
            default -> null;
        };
        if (reset == null) return;
        getLogger().severe("[" + what + "] kept failing for 5 seconds: removed so it can be summoned again (the error is above).");
        try { reset.run(); } catch (Throwable t) { getLogger().log(java.util.logging.Level.WARNING, "[" + what + "] cleanup also failed", t); }
    }

    // =====================================================================================
    // ===================== the shared rig (Diamond Jacob's knight uses it) =====================
    // =====================================================================================

    // ---------- the rig: 6 animated pieces using his skin ----------
    // Joints in skin pixels relative to his feet. He faces +Z, so +X is HIS left.
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

    Quaternionf euler(float[] r) {
        return new Quaternionf().rotateY((float) Math.toRadians(r[1])).rotateX((float) Math.toRadians(r[0])).rotateZ((float) Math.toRadians(r[2]));
    }

    /** Places all 6 pieces (+ his sword) for a pose. Arms and head follow the body's lean and twist. */
    void renderRig(ItemDisplay[] parts, ItemDisplay sword, Location root, float yaw, float scale, Pose pose) {
        renderRig(parts, sword, root, yaw, scale, pose, -90f, 0.85f);
    }

    /**
     * swordPitch tilts the held item from the hand: -90 suits vanilla swords (their own third-person transform already
     * tilts them), +90 points a custom weapon whose model is built blade-up (the Lost Explorer's axe/greatsword) forward.
     */
    void renderRig(ItemDisplay[] parts, ItemDisplay sword, Location root, float yaw, float scale, Pose pose, float swordPitch, float swordScale) {
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
            Quaternionf rot = new Quaternionf(qYaw).mul(qArm).rotateX((float) Math.toRadians(swordPitch)).rotateY((float) Math.PI);
            float ss = scale * swordScale;
            sword.setTransformation(new Transformation(new Vector3f(0, 0, 0), rot, new Vector3f(ss, ss, ss), new Quaternionf()));
        }
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

    // ---------- the fight ----------
    static final int ARRIVE = -1; // his entrance: untouchable, can't be talked to yet
    static final int T_FIGHT = 445, T_SPEECH = 3260, T_SPEARS = 3700; // music ticks: 0:22.25, 2:43, 3:05

    // ---------- the cutscene (0:00 - 0:22) ----------
    private boolean allowTeleport; // our own teleports get through the anti-escape checks

    private java.io.File cutsceneFile() { return new java.io.File(getDataFolder(), "don_cutscene.yml"); }
    private java.io.File boxFile() { return new java.io.File(getDataFolder(), "don_box.yml"); }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    // ---------- damage, talking, orbs ----------
    Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player s) return s;
        return null;
    }

    // ===================== REWARDS & COMMAND =====================

    /**
     * Damage from a player's ability (Twin Eye Staff eyes, the Vendetta Fist, a helper): credited to the player, but typed
     * as magic. BUG FIX: it used to be dealt as a plain hit FROM the player, and a Guardian (or Elder Guardian) hit that
     * way spikes back at whoever hit it directly, from any distance: the staff's eyes and the Fist "attacked" their own user.
     */
    static org.bukkit.damage.DamageSource abilityDamage(Player p) {
        return org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC).withCausingEntity(p).withDirectEntity(p).build();
    }

    /** Drops an item at a spot, bursting outward, pick-up-able only by `owner`. */
    void dropLocked(Location at, Player owner, ItemStack item) {
        org.bukkit.entity.Item drop = at.getWorld().dropItem(at, item);
        drop.setOwner(owner.getUniqueId());
        Safe.vel(drop, new Vector(random.nextGaussian() * 0.15, 0.3 + random.nextDouble() * 0.2, random.nextGaussian() * 0.15));
    }

    void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    /** Mythic Bags come from FaultlineItems, Zombie Omens from FaultlineRaids (the real items). */
    void console(String command, Player p) {
        console(command, p, null);
    }

    void console(String command, Player p, Location dropAt) {
        boolean index = command.startsWith("index ");
        String pluginName = command.startsWith("zraid") ? "FaultlineRaids" : index ? "FaultlineIndex" : command.startsWith("cosmetic ") ? "FaultlineCosmetics" : "FaultlineItems";
        Plugin other = Bukkit.getPluginManager().getPlugin(pluginName);
        boolean ok = other != null && other.isEnabled() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!ok && index) return; // just an Index unlock: nothing to make up for
        if (!ok) {
            getLogger().warning("Couldn't run '" + command + "' (is " + pluginName + " installed?) — gave diamonds instead.");
            if (dropAt != null) dropLocked(dropAt, p, new ItemStack(Material.DIAMOND, 4));
            else give(p, new ItemStack(Material.DIAMOND, 4));
        }
    }


    // =====================================================================================================
    //  BOSS FORM (/bossmorph): an admin plays a boss. They turn invisible and untouchable, the boss follows
    //  them, and their hotbar becomes the boss's moves (right-click one to use it on whoever they're looking at).
    //  Everyone else fights it like the real thing: its health, phases, cutscenes, and defeat all work normally.
    // =====================================================================================================
    static final Set<UUID> MORPHED = new HashSet<>();
    /**
     * True while this player hears a boss's music (FaultlineDeep asks by reflection, so its ocean music steps aside).
     */
    static FaultlineBosses instance;

    /** Who was last given which boss's music, and in which world (every boss's music start calls heardMusic). */
    static final Map<UUID, String[]> MUSIC_HEARD = new java.util.concurrent.ConcurrentHashMap<>();

    static void heardMusic(Player p, String kind) { MUSIC_HEARD.put(p.getUniqueId(), new String[]{kind, p.getWorld().getName()}); }

    /**
     * True while this player hears a boss's music: a wild/deep boss that has them as a listener, or any other boss
     * still out that started its music for them in the world they're in. FaultlineDeep asks this (by reflection) so the
     * Dark Ocean's music never plays over a boss's; the vanilla background music is also kept off while it's true.
     */
    /**
     * FaultlineDeep (by reflection) when someone who has beaten all six sea bosses swims into the Trench's Maw: wakes the
     * Abyssal Titan there. True if it's out (just now, or already); false if it couldn't (cooldown, no room, no world).
     */
    public static boolean wakeTitan(Location at, Player by) {
        FaultlineBosses pl = instance;
        if (pl == null || pl.wild == null) return false;
        if (pl.wild.get("titan") != null) return true;
        if (System.currentTimeMillis() < pl.wild.cooldownUntil.getOrDefault("titan", 0L)) return false;
        pl.wild.lastFail = null;
        return pl.wild.summon("titan", at, by) != null;
    }

    /**
     * FaultlineDeep (by reflection): a sea boss's real model, tiny, posed in a lab jar (scale = the rig's k; radius = the
     * circle a serpent swims round). Returns its id, or null. Harmless: no hitboxes, no fight, no music.
     */
    public static String specimen(String kind, Location at, double scale, double radius) {
        FaultlineBosses pl = instance;
        return pl == null || pl.wild == null ? null : pl.wild.specimen(kind, at, scale, radius);
    }

    public static void removeSpecimen(String id) {
        FaultlineBosses pl = instance;
        if (pl != null && pl.wild != null) pl.wild.removeSpecimen(id);
    }

    public static boolean hearingBossMusic(UUID id) {
        FaultlineBosses pl = instance;
        if (pl == null) return false;
        if (pl.wild != null) for (Wild.Boss b : pl.wild.bosses.values()) if (b.listeners.contains(id)) return true;
        String[] h = MUSIC_HEARD.get(id);
        if (h == null) return false;
        if (Wild.KINDS.contains(h[0])) { MUSIC_HEARD.remove(id, h); return false; } // wild/deep bosses: their listener list (above) is the truth
        Player p = Bukkit.getPlayer(id);
        boolean live;
        try { live = p != null && p.getWorld().getName().equals(h[1]) && pl.bossAlive(h[0]); } catch (RuntimeException e) { live = false; }
        if (!live) MUSIC_HEARD.remove(id, h);
        return live;
    }

    /** Every 2 s: vanilla's background music (MUSIC category) is kept off for anyone hearing a boss's music. */
    void quietVanilla() {
        if (MUSIC_HEARD.isEmpty() && (wild == null || wild.bosses.isEmpty())) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (hearingBossMusic(p.getUniqueId())) p.stopSound(SoundCategory.MUSIC);
    }

    static final List<String> MORPH_KINDS = List.of("jacob", "scourge", "umbral", "coralia", "inkjaw", "grandmaw", "lament", "titan", "off", "release");
    private Morph morph;
    Wild wild;

    /** A boss that's out right now, or null. */
    String bossOutName() {
        if (kraken != null) return kraken.title();
        if (jacob != null) return "Diamond Jacob";
        if (wild != null && wild.any() != null) return wild.any().name();
        return null;
    }

    /** Is this boss out right now (the music check asks). */
    boolean bossAlive(String kind) {
        return switch (kind) {
            case "kraken" -> kraken != null; case "jacob" -> jacob != null;
            case "scourge", "umbral", "coralia", "inkjaw", "grandmaw", "lament", "titan" -> wild != null && wild.get(kind) != null;
            default -> false;
        };
    }

    private final NamespacedKey MORPH_MOVE_KEY = new NamespacedKey(this, "morph_move");
    java.io.File morphFile() { return new java.io.File(getDataFolder(), "boss_form.yml"); }

    record MoveSlot(int id, String name, Material icon) {}

    final class Morph {
        final UUID player; final String kind; Object boss; int queued = -1; String shown = ""; int ticks;
        Morph(UUID player, String kind) { this.player = player; this.kind = kind; }
    }

    /** The admin playing this boss, or null. */
    Player pilot(Object boss) {
        if (morph == null || boss == null || morph.boss != boss) return null;
        Player p = Bukkit.getPlayer(morph.player);
        return p != null && p.isOnline() ? p : null;
    }

    /** The move the admin picked (and clears it), or -1. */
    int takeMove(Object boss) {
        if (morph == null || morph.boss != boss) return -1;
        int q = morph.queued; morph.queued = -1; return q;
    }

    boolean morphQueued(Object boss) { return morph != null && morph.boss == boss && morph.queued >= 0; }

    /** Who the admin's move goes at: the fighter they're looking at, else the nearest one, else the admin's own spot. */
    Player pilotTarget(Player pilot, List<Player> fighters) {
        Vector eye = pilot.getEyeLocation().toVector(), dir = pilot.getEyeLocation().getDirection();
        Player best = null, near = null; double bestDot = Math.cos(Math.toRadians(22)), nearD = 64 * 64;
        for (Player f : fighters) {
            Vector to = f.getEyeLocation().toVector().subtract(eye);
            double d2 = to.lengthSquared();
            if (d2 < nearD) { nearD = d2; near = f; }
            if (d2 > 64 * 64 || d2 < 0.01) continue;
            double dot = to.normalize().dot(dir);
            if (dot > bestDot) { bestDot = dot; best = f; }
        }
        return best != null ? best : near != null ? near : pilot;
    }

    /** The current boss's moves for its current phase (what goes on the hotbar). */
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        if (morph == null) return m;
        Object b = morph.boss;
        if (b == jacob && jacob != null) {
            switch (jacob.phase()) {
                case 1 -> {
                    m.add(new MoveSlot(1, "Piercing Arrow", Material.SPECTRAL_ARROW));
                    m.add(new MoveSlot(2, "Arrow Volley", Material.ARROW));
                    m.add(new MoveSlot(3, "Talon Dive", Material.FEATHER));
                    m.add(new MoveSlot(4, "Arrow Rain", Material.TIPPED_ARROW));
                }
                case 2, 3 -> {
                    m.add(new MoveSlot(10, "Hammer Slam", Material.HEAVY_CORE)); // not MACE: FaultlineItems removes maces
                    m.add(new MoveSlot(11, "Pearl Strike", Material.ENDER_PEARL));
                    m.add(new MoveSlot(12, "Whirlwind", Material.WIND_CHARGE));
                    m.add(new MoveSlot(13, "Hammer Throw", Material.NETHERITE_AXE));
                    m.add(new MoveSlot(99, "Summon Spiders", Material.SPIDER_EYE));
                    if (jacob.phase() == 3) {
                        m.add(new MoveSlot(20, "Cavalry Charge", Material.GOAT_HORN));
                        m.add(new MoveSlot(21, "Spear Wall", Material.IRON_SPEAR));
                    }
                }
                default -> {
                    m.add(new MoveSlot(30, "Ember Flurry", Material.BLAZE_POWDER));
                    m.add(new MoveSlot(31, "Phoenix Leap", Material.FIRE_CHARGE));
                    m.add(new MoveSlot(32, "Inferno Pillars", Material.BLAZE_ROD));
                    m.add(new MoveSlot(33, "Burning Brand", Material.MAGMA_CREAM));
                    m.add(new MoveSlot(34, "Diamond Shatter", Material.DIAMOND));
                }
            }
        }
        else if (wild != null && wild.isBoss(b)) m.addAll(((Wild.Boss) b).morphMoves());
        return m.size() > 8 ? m.subList(0, 8) : m;
    }

    ItemStack moveItem(MoveSlot mv) {
        ItemStack it = new ItemStack(mv.icon());
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + mv.name());
        meta.setLore(List.of(ChatColor.GRAY + "Right-click: use it on the player", ChatColor.GRAY + "you're looking at (or the nearest)."));
        meta.getPersistentDataContainer().set(MORPH_MOVE_KEY, PersistentDataType.INTEGER, mv.id());
        meta.setMaxStackSize(1);
        it.setItemMeta(meta);
        return it;
    }

    ItemStack leaveItem() {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Leave boss form");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click: the boss leaves and you're", ChatColor.GRAY + "yourself again. (/bossmorph release lets it fight on alone.)"));
        meta.getPersistentDataContainer().set(MORPH_MOVE_KEY, PersistentDataType.INTEGER, -2);
        it.setItemMeta(meta);
        return it;
    }

    void refreshMorphHotbar(Player p) {
        List<MoveSlot> moves = morphMoves();
        String sig = moves.toString();
        if (sig.equals(morph.shown)) return;
        boolean first = morph.shown.isEmpty();
        morph.shown = sig;
        p.getInventory().clear();
        for (int i = 0; i < moves.size(); i++) p.getInventory().setItem(i, moveItem(moves.get(i)));
        p.getInventory().setItem(8, leaveItem());
        if (!first) p.sendActionBar(legacy(ChatColor.GOLD + "New phase: your moves changed!"));
    }

    boolean morphCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String kind = args.length > 0 ? args[0].toLowerCase() : "";
        if (kind.equals("off") || kind.equals("release")) {
            if (morph == null) { sender.sendMessage(ChatColor.GRAY + "Nobody is in boss form."); return true; }
            endMorph(kind.equals("off"), ChatColor.GREEN + (kind.equals("off") ? "You're yourself again (the boss left)." : "You're yourself again. The boss fights on alone!"));
            sender.sendMessage(ChatColor.GREEN + "Done.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (morph != null) { sender.sendMessage(ChatColor.RED + "Someone is already in boss form. (/bossmorph off)"); return true; }
        if (!MORPH_KINDS.contains(kind) || kind.equals("off") || kind.equals("release")) {
            sender.sendMessage(ChatColor.YELLOW + "/bossmorph <jacob|scourge|umbral|coralia|inkjaw|grandmaw|lament|titan>  |  /bossmorph off  |  /bossmorph release");
            return true;
        }
        boolean busy = switch (kind) {
            case "jacob" -> jacob != null;
            default -> wild.get(kind) != null;
        };
        if (busy) { sender.sendMessage(ChatColor.RED + "That boss is already out. Remove it first (/" + kind + " kill)."); return true; }
        startMorph(p, kind);
        return true;
    }

    void startMorph(Player p, String kind) {
        // save everything about them first (to a file too, so a crash can't eat their inventory)
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("uuid", p.getUniqueId().toString());
        y.set("inventory", Arrays.asList(p.getInventory().getContents()));
        y.set("mode", p.getGameMode().name());
        y.set("allow-flight", p.getAllowFlight());
        y.set("flying", p.isFlying());
        y.set("invulnerable", p.isInvulnerable());
        y.set("collidable", p.isCollidable());
        try { getDataFolder().mkdirs(); y.save(morphFile()); } catch (Exception e) { p.sendMessage(ChatColor.RED + "Couldn't save your inventory, so boss form was cancelled."); return; }

        MORPHED.add(p.getUniqueId());
        morph = new Morph(p.getUniqueId(), kind);
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        if (!kind.equals("don") && !kind.equals("rocco")) p.setFlying(true);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        p.setFireTicks(0);

        Location at = p.getLocation();
        Vector look = at.getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(0, 0, 1);
        look.normalize();
        try {
            switch (kind) {
                case "jacob" -> { summonJacob(at, null); morph.boss = jacob; }
                default -> morph.boss = wild.summon(kind, at.clone().add(look.clone().multiply(4)), null);
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Boss form: couldn't summon " + kind, e);
        }
        if (morph.boss == null) { endMorph(false, ChatColor.RED + "The boss couldn't appear here (mob spawning blocked?)."); return; }
        refreshMorphHotbar(p);
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "You are the boss! " + ChatColor.GRAY + "It follows you; right-click a move on your hotbar to use it on "
                + "whoever you're looking at. Press F5 for a better view. Slot 9 (or /bossmorph off) to stop.");
    }

    /** Puts them back exactly as they were. leave = the boss goes too; otherwise it fights on with its own AI. */
    void endMorph(boolean leave, String message) {
        Morph m = morph;
        morph = null;
        if (m == null) return;
        MORPHED.remove(m.player);
        if (leave && m.boss != null) {
            String cmd = m.boss == jacob ? "jacob kill" : null;
            if (cmd != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            else if (wild != null && wild.isBoss(m.boss)) ((Wild.Boss) m.boss).leave(null);
        }
        Player p = Bukkit.getPlayer(m.player);
        if (p != null) { restoreMorphed(p); if (message != null) p.sendMessage(message); }
    }

    void restoreMorphed(Player p) {
        java.io.File f = morphFile();
        if (!f.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        if (!p.getUniqueId().toString().equals(y.getString("uuid"))) return;
        MORPHED.remove(p.getUniqueId());
        p.getInventory().clear();
        List<?> inv = y.getList("inventory", List.of());
        ItemStack[] items = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < Math.min(items.length, inv.size()); i++) items[i] = inv.get(i) instanceof ItemStack is ? is : null;
        p.getInventory().setContents(items);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        try { p.setGameMode(GameMode.valueOf(y.getString("mode", "SURVIVAL"))); } catch (IllegalArgumentException e) { p.setGameMode(GameMode.SURVIVAL); }
        p.setAllowFlight(y.getBoolean("allow-flight") || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
        p.setFlying(y.getBoolean("flying") && p.getAllowFlight());
        p.setInvulnerable(y.getBoolean("invulnerable"));
        p.setCollidable(y.getBoolean("collidable", true));
        p.setFallDistance(0);
        f.delete();
    }

    /** Every tick: is the boss still around? Hotbar matches its phase? Still invisible and fed? */
    void morphTick() {
        if (morph == null) return;
        Player p = Bukkit.getPlayer(morph.player);
        if (p == null) { endMorph(true, null); return; }
        boolean alive = morph.boss != null && (morph.boss == jacob || wild != null && wild.isBoss(morph.boss));
        if (!alive) { endMorph(false, ChatColor.GOLD + "Your boss was defeated! You're yourself again."); return; }
        if (++morph.ticks % 10 == 0) {
            refreshMorphHotbar(p);
            if (!p.hasPotionEffect(PotionEffectType.INVISIBILITY))
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            p.setFoodLevel(20); p.setFireTicks(0);
            if (morph.queued < 0) {
                List<MoveSlot> mv = morphMoves();
                p.sendActionBar(legacy(ChatColor.GOLD + "Boss form" + ChatColor.GRAY + "  |  " + mv.size() + " moves  |  slot 9 to leave"));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphUse(PlayerInteractEvent event) {
        if (morph == null || !event.getPlayer().getUniqueId().equals(morph.player)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = event.getItem();
        if (it == null || !it.hasItemMeta()) return;
        Integer id = it.getItemMeta().getPersistentDataContainer().get(MORPH_MOVE_KEY, PersistentDataType.INTEGER);
        if (id == null) return;
        if (id == -2) { endMorph(true, ChatColor.GREEN + "You're yourself again."); return; }
        morph.queued = id;
        event.getPlayer().sendActionBar(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + ChatColor.stripColor(it.getItemMeta().getDisplayName()) + "!"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphEntityUse(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (morph != null && event.getPlayer().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphAttack(EntityDamageByEntityEvent event) { // the boss does the fighting, not the invisible admin
        if (morph != null && event.getDamager().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (morph != null && event.getPlayer().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (morph != null && event.getEntity().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphInventory(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (morph != null && event.getWhoClicked().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMorphSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (morph != null && event.getPlayer().getUniqueId().equals(morph.player)) event.setCancelled(true);
    }

    @EventHandler
    public void onMorphQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (morph != null && event.getPlayer().getUniqueId().equals(morph.player)) endMorph(true, null); // restored before their data saves
    }

    /** After a crash mid-boss-form: give them their stuff back when they rejoin. */
    @EventHandler
    public void onMorphRejoin(PlayerJoinEvent event) {
        if (morph != null && event.getPlayer().getUniqueId().equals(morph.player)) return;
        restoreMorphed(event.getPlayer());
    }

}
