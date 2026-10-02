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
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

    static final String BOSS_TAG = "faultline_demon_eye";
    static final String SERVANT_TAG = "faultline_demon_servant";
    static final String DISPLAY_TAG = "faultline_boss_display";
    private static final UUID PACK_ID = UUID.fromString("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");

    enum Attack { HOVER, SERVANTS, BLOOD_CHARGE, SPIN_DASH, TOOTH_VOMIT, BLOOD_SHOTS }

    private static final Attack[] PATTERN_1 = {Attack.HOVER, Attack.HOVER, Attack.SERVANTS, Attack.HOVER, Attack.BLOOD_CHARGE, Attack.HOVER, Attack.SERVANTS};
    private static final Attack[] PATTERN_2 = {Attack.HOVER, Attack.HOVER, Attack.SERVANTS, Attack.SPIN_DASH, Attack.HOVER, Attack.HOVER, Attack.TOOTH_VOMIT,
            Attack.HOVER, Attack.HOVER, Attack.SPIN_DASH, Attack.BLOOD_CHARGE, Attack.HOVER, Attack.HOVER, Attack.TOOTH_VOMIT, Attack.SPIN_DASH};
    private static final Attack[] PATTERN_3 = {Attack.HOVER, Attack.HOVER, Attack.BLOOD_SHOTS, Attack.SERVANTS, Attack.SPIN_DASH, Attack.HOVER, Attack.HOVER,
            Attack.BLOOD_SHOTS, Attack.HOVER, Attack.HOVER, Attack.BLOOD_CHARGE, Attack.BLOOD_SHOTS, Attack.HOVER, Attack.HOVER, Attack.TOOTH_VOMIT, Attack.SPIN_DASH};

    private final Random random = new Random();
    private boolean dealing; // true only while the boss deals its own damage
    private NamespacedKey summonKey;
    private DemonEye eye;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        summonKey = new NamespacedKey(this, "suspicious_eye");
        removeLeftovers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> { if (eye != null) eye.tick(); }, 1L, 1L);
        getCommand("demoneye").setExecutor(new BossCommand());
        getLogger().info("Faultline Bosses enabled.");
    }

    @Override
    public void onDisable() {
        if (eye != null) eye.removeEverything();
        eye = null;
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
                if (tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG) || tags.contains(DISPLAY_TAG)) e.remove();
            }
        }
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private void sound(Location at, String name, float volume, float pitch) {
        at.getWorld().playSound(at, "faultline:demon_eye." + name, SoundCategory.HOSTILE, volume, pitch);
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
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
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
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        }
        summon(player);
    }

    void summon(Player summoner) {
        double angle = random.nextDouble() * Math.PI * 2;
        Location at = summoner.getLocation().add(Math.cos(angle) * 18, 10, Math.sin(angle) * 18);
        eye = new DemonEye(at, summoner);
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Demon Eye has awoken!");
        for (Player p : summoner.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 128 * 128) continue;
            p.showTitle(Title.title(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE DEMON EYE"),
                    legacy(ChatColor.GRAY + "has awoken..."),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        }
        sound(at, "summon", 4f, 1f);
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
        Set<String> tags = event.getEntity().getScoreboardTags();
        if (tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG)) event.setCancelled(true);
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
        if (!allowed || eye.transitionTicks > 0 || eye.dying) {
            event.setCancelled(true);
            return;
        }
        eye.onHurt();
    }

    /** Slimes damage whoever touches them (even without AI); the hitbox only hurts through real attacks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTouchDamage(EntityDamageByEntityEvent event) {
        Set<String> tags = event.getDamager().getScoreboardTags();
        if ((tags.contains(BOSS_TAG) || tags.contains(SERVANT_TAG)) && !dealing) event.setCancelled(true);
    }

    private void hurt(Player p, double amount, Entity source) {
        dealing = true;
        try {
            p.damage(amount, source);
        } finally {
            dealing = false;
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
        }
    }

    private void setupHitbox(Slime slime, double health, String tag, String name) {
        slime.setAI(false);
        slime.setGravity(false);
        slime.setInvisible(true);
        slime.setSilent(true);
        slime.setCollidable(false);
        slime.setPersistent(false);
        slime.setRemoveWhenFarAway(false);
        slime.addScoreboardTag(tag);
        slime.setCustomName(ChatColor.DARK_RED + name);
        slime.setCustomNameVisible(false);
        AttributeInstance max = slime.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) max.setBaseValue(health); // after setSize, which resets health
        slime.setHealth(health);
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
        int transitionTicks, hurtFlash, hurtSoundCd, lonelyTicks, ticksAlive, leaveTicks, deathTicks;
        boolean mouth, dying, leaving, enraged;
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
            this.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Demon Eye", BarColor.RED, BarStyle.SEGMENTED_20);
            for (Player p : world.getPlayers()) {
                if (survival(p) && p.getLocation().distanceSquared(summoner.getLocation()) <= 64 * 64) fighters.add(p.getUniqueId());
            }
            fighters.add(summoner.getUniqueId());
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
            if (down.containsAll(fighters)) leave(ChatColor.DARK_RED + "Everyone fighting the Demon Eye has fallen... it drifts away into the night.");
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
            if (!hitbox.isValid() || !model.isValid()) {
                removeEverything();
                eye = null;
                return;
            }
            enraged = !isNight(world);

            List<Player> active = activeFighters();
            if (active.isEmpty()) {
                if (++lonelyTicks > 200) { // nobody left in the fight for 10 seconds
                    leave(ChatColor.DARK_RED + "The Demon Eye has lost interest...");
                    return;
                }
            } else {
                lonelyTicks = 0;
            }

            checkPhase();
            if (transitionTicks > 0) {
                transitionTick();
            } else {
                Player target = targetPlayer();
                if (target != null) runAttack(target);
                else vel.multiply(0.9);
            }

            pos.add(vel);
            contactDamage(active);
            tickServants();
            tickShots();
            updateBar();
            render();
            contactCooldown.replaceAll((k, v) -> v - 1);
            contactCooldown.values().removeIf(v -> v <= 0);
            if (hurtFlash > 0 && --hurtFlash == 0) model.setGlowing(false);
            if (hurtSoundCd > 0) hurtSoundCd--;
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

        void nextAttack() {
            patternIndex = (patternIndex + 1) % pattern.length;
            attack = pattern[patternIndex];
            t = 0;
            contact = false;
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
                        fly(tp.clone().add(new Vector(Math.sin(ticksAlive * 0.05) * 5, 7, Math.cos(ticksAlive * 0.05) * 5)), 0.6 * sp, 0.15);
                        look(tp);
                    } else if (t == 30) {
                        vel = toward(target, 1.1 * sp);
                        contact = true;
                        stretch = true;
                        sound(pos.toLocation(world), "dash", 2f, 1f);
                    } else if (t > 50) {
                        vel.multiply(0.85);
                        stretch = false;
                    }
                    if (t >= 62) nextAttack();
                }
                case SERVANTS -> { // rush overhead, stop, birth servants in bursts of blood
                    int count = phase == 1 ? 6 : 9;
                    int every = phase >= 4 ? 2 : 4;
                    if (t < 20) {
                        fly(tp.clone().add(new Vector(0, 9, 0)), 0.9 * sp, 0.2);
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
                        fly(tp.clone().add(sideways.clone().multiply(18)).add(new Vector(0, 10, 0)), 1.3 * sp, 0.25);
                        look(tp);
                    } else if (t == prep) {
                        vel = sideways.clone().multiply(-1.0 * sp);
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
                        vel = toward(target, 1.45 * sp);
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
                            vel = toward(target, 1.2 * sp);
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
                        fly(tp.clone().add(new Vector(0, 8, 0)), 1.3 * sp, 0.3);
                        look(tp);
                    } else if (t < 35) {
                        vel.multiply(0.6);
                        lookUp = true;
                    } else {
                        if (t == 35) sound(pos.toLocation(world), "roar", 3f, 0.8f);
                        if ((t - 35) % 6 == 0 && burstsFired < spreads) {
                            for (int i = 0; i < perSpread; i++) {
                                double a = random.nextDouble() * Math.PI * 2;
                                double h = 0.18 + random.nextDouble() * (phase >= 3 ? 0.35 : 0.25);
                                addShot(Shot.TOOTH, pos.clone().add(new Vector(0, 1.2, 0)),
                                        new Vector(Math.cos(a) * h, 0.85 + random.nextDouble() * 0.3, Math.sin(a) * h), null);
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
                                addShot(Shot.BLOOD, pos.clone(), dir.normalize().multiply(0.4), aim.getUniqueId());
                            }
                            vel.add(toward(target, 0.4).multiply(-1)); // recoil
                            sound(pos.toLocation(world), "spit", 1.6f, 0.7f);
                            burstsFired++;
                        }
                        if (burstsFired >= 4 && t > 30 + every * 4 + 10) nextAttack();
                    }
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
            transitionTicks = phase == 2 ? 50 : 24;
            contact = false;
            stretch = false;
            sound(pos.toLocation(world), phase == 2 ? "phase" : "roar", 4f, phase == 2 ? 1f : 0.7f);
            bloodBurst(pos, 50);
        }

        /** Phase 2: spins, flashes red, splits open into the mouth form. Later phases: a shorter roar-spin. */
        void transitionTick() {
            transitionTicks--;
            vel.multiply(0.8);
            roll += phase == 2 ? 0.55f : 0.4f;
            if (phase == 2 && transitionTicks == 25 && !mouth) {
                mouth = true;
                model.setItemStack(modelItem("demon_eye_mouth"));
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
                hurt(p, dmg, hitbox);
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
                    hurt(t, cfg("servant-damage", 4), hitbox);
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
            Iterator<Shot> it = shots.iterator();
            while (it.hasNext()) {
                Shot s = it.next();
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
                    hurt(p, dmg, hitbox);
                    contactCooldown.put(p.getUniqueId(), 10);
                    if (s.type != Shot.TOOTH_BALL) remove = true;
                }
                // lifetimes
                if (s.type == Shot.TOOTH_BALL && s.life >= 110) { // bursts into 2 teeth after 5.5s
                    for (int i = -1; i <= 1; i += 2) {
                        double a = random.nextDouble() * Math.PI * 2;
                        addShot(Shot.TOOTH, s.pos.clone().add(new Vector(0, 0.5, 0)),
                                new Vector(Math.cos(a) * 0.25 * i, 1.0, Math.sin(a) * 0.25 * i), null);
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
                    it.remove();
                } else {
                    s.display.teleport(s.pos.toLocation(world));
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
                Vector3f scale = stretch ? new Vector3f(base * 0.85f, base * 0.85f, base * 1.25f) : new Vector3f(base, base, base);
                model.setInterpolationDelay(0);
                model.setTransformation(new Transformation(new Vector3f(0, bob, 0), new Quaternionf().rotateZ(roll),
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

        // ---------- endings ----------

        void die() {
            if (dying) return;
            dying = true;
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
            model.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ(roll), new Vector3f(s, s, s), new Quaternionf()));
            model.teleport(pos.toLocation(world));
            if (deathTicks % 3 == 0) bloodBurst(pos, 15);
            if (deathTicks >= 60) {
                world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 3, 0.5, 0.5, 0.5, 0);
                bloodBurst(pos, 120);
                removeEverything();
                eye = null;
            }
        }

        void leave(String message) {
            if (leaving || dying) return;
            leaving = true;
            contact = false;
            bar.removeAll();
            clearMinions();
            if (hitbox.isValid()) hitbox.remove();
            Bukkit.broadcastMessage(message);
        }

        /** Rises up and away, then vanishes. */
        void leaveTick() {
            leaveTicks++;
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
            clearMinions();
            bar.removeAll();
            if (hitbox.isValid()) hitbox.remove();
            if (model.isValid()) model.remove();
        }

        void rewardFighters() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                console("givemythicbag " + (int) cfg("rewards.mythic-bags", 10) + " " + p.getName(), p);
                for (int i = 0; i < 2; i++) console("zraid omen " + (random.nextBoolean() ? 4 : 5) + " 1 " + p.getName(), p);
                give(p, new ItemStack(Material.DIAMOND, 12 + random.nextInt(5)));
                if (random.nextDouble() < cfg("rewards.netherite-chance", 0.5)) give(p, new ItemStack(Material.NETHERITE_INGOT, 1 + random.nextInt(3)));
                p.giveExp((int) cfg("rewards.xp", 1000));
                p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "DEMON EYE DEFEATED"),
                        legacy(ChatColor.GRAY + "Your rewards are in your inventory."),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            }
        }
    }

    // ===================== REWARDS & COMMAND =====================

    private void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    /** Mythic Bags come from FaultlineItems, Zombie Omens from FaultlineRaids (the real items). */
    private void console(String command, Player p) {
        String pluginName = command.startsWith("zraid") ? "FaultlineRaids" : "FaultlineItems";
        Plugin other = Bukkit.getPluginManager().getPlugin(pluginName);
        boolean ok = other != null && other.isEnabled() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!ok) {
            getLogger().warning("Couldn't run '" + command + "' (is " + pluginName + " installed?) — gave diamonds instead.");
            give(p, new ItemStack(Material.DIAMOND, 4));
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
                    summon(p); // admin test: works any time of day (it'll be enraged in daylight)
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
                default -> sender.sendMessage(ChatColor.YELLOW + "/demoneye summon | kill | give [amount] [player]");
            }
            return true;
        }
    }
}
