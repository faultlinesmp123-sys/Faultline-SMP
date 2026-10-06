package net.faultlinesmp.raids;

import net.faultlinesmp.raids.FaultlineRaids.Kind;
import net.faultlinesmp.raids.FaultlineRaids.ZombieRaid;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * PIGLIN RAIDS (inspired by the Horde of the Hunt in Minecraft Legends).
 *
 * A Piglin Brute killed by a player has a 5% chance to drop a PIGLIN WAR HORN, always level III, IV or V. Blow it and you
 * carry a Piglin Omen (like drinking a Zombie Omen); go near a village in the Overworld and the Piglins march on it.
 * Levels: III = 6 waves, IV = 8, V = 10. Every wave is 15 piglins:
 *   Grunts (golden swords), Crossbowmen, Brutes, Hoglin Riders, Piglin Mages (fireballs, a flame burst up close),
 *   Piglin Summoners (open little portals that spit out Grunts) and Balloonists (a piglin in a hot air balloon, dropping
 *   fire bombs). Wave 5 brings THE BULWARK (a mini boss: a giant shielded Brute), and once the last wave is down
 *   THE GREAT HOG charges in (the boss: a giant crowned hoglin with a charge, a ground slam, a rallying roar and fire breath).
 * None of them turn into Zombified Piglins in the Overworld.
 */
final class Piglins implements Listener {

    static final String UNIT_TAG = "faultline_piglin_unit"; // every piglin-raid mob (and a rider's hoglin): drops nothing
    static final String SHIELDWALL_TAG = "faultline_piglin_shieldwall", MAGMA_TAG = "faultline_piglin_magma";
    static final String BOMB_TAG = "faultline_piglin_bomb", FIREBALL_TAG = "faultline_piglin_fireball",
            HOG_TAG = "faultline_great_hog", BULWARK_TAG = "faultline_bulwark", DISPLAY_TAG = "faultline_piglin_display";

    final FaultlineRaids pl;
    final Random random;
    final NamespacedKey hornKey;
    final Map<UUID, Long> next = new HashMap<>();          // per-mob: next action (ms)
    final Map<UUID, Integer> burst = new HashMap<>();      // mage: flame burst wind-up (calls left)
    final Map<UUID, Integer> portal = new HashMap<>();     // summoner: portal opening (calls left)
    final Map<UUID, Location> portalAt = new HashMap<>();
    final Map<UUID, List<UUID>> summoned = new HashMap<>();
    final Map<UUID, Balloon> balloons = new HashMap<>();
    final Map<UUID, Boss> bosses = new HashMap<>();

    Piglins(FaultlineRaids pl) {
        this.pl = pl;
        this.random = pl.random;
        hornKey = new NamespacedKey(pl, "piglin_war_horn");
    }

    double cfg(String key, double def) { return pl.getConfig().getDouble("piglin-raid." + key, def); }

    static boolean isPiglinKind(Kind k) {
        return switch (k) {
            case PIGLIN_GRUNT, PIGLIN_CROSSBOW, PIGLIN_BRUTE, HOGLIN_RIDER, PIGLIN_MAGE, PIGLIN_SUMMONER, PIGLIN_BALLOON, BULWARK, GREAT_HOG,
                 PIGLIN_SAPPER, PIGLIN_SHIELDBEARER, PIGLIN_LOBBER, PIGLIN_RUNT, PIGLIN_BANNER, PIGLIN_MEDIC -> true;
            default -> false;
        };
    }

    static int waves(int level) { return level <= 3 ? 6 : level == 4 ? 8 : 10; }

    // =====================================================================================================
    //  the horn
    // =====================================================================================================
    ItemStack horn(int level) {
        level = Math.max(3, Math.min(5, level));
        ItemStack item = new ItemStack(Material.GOAT_HORN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Piglin War Horn " + FaultlineRaids.roman(level));
        meta.setLore(List.of(
                ChatColor.GRAY + "Blow it, then go near a village.",
                ChatColor.RED + "The Piglins will march on it.",
                ChatColor.DARK_GRAY + "Piglin Raid level " + level + " (" + waves(level) + " waves)"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.setItemModel(new NamespacedKey("faultline", "piglin_war_horn"));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(hornKey, PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return item;
    }

    int hornLevel(ItemStack item) {
        if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) return 0;
        Integer l = item.getItemMeta().getPersistentDataContainer().get(hornKey, PersistentDataType.INTEGER);
        return l == null ? 0 : l;
    }

    /** III common, V rare: 50/32/18%. */
    ItemStack randomHorn() {
        double r = random.nextDouble();
        return horn(r < 0.50 ? 3 : r < 0.82 ? 4 : 5);
    }

    /** Blowing the horn: a Piglin Omen (go near a village). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHorn(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        int level = hornLevel(event.getItem());
        if (level == 0) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p.getCooldown(Material.GOAT_HORN) > 0) return;
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            org.bukkit.inventory.EquipmentSlot hand = event.getHand() == null ? org.bukkit.inventory.EquipmentSlot.HAND : event.getHand();
            ItemStack it = p.getInventory().getItem(hand);
            if (hornLevel(it) > 0) { it.setAmount(it.getAmount() - 1); p.getInventory().setItem(hand, it.getAmount() > 0 ? it : null); }
        }
        p.setCooldown(Material.GOAT_HORN, 40);
        p.getWorld().playSound(p.getLocation(), Sound.EVENT_RAID_HORN, SoundCategory.PLAYERS, 3f, 0.55f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, SoundCategory.PLAYERS, 1.5f, 0.7f);
        p.getWorld().spawnParticle(Particle.FLAME, p.getEyeLocation().add(p.getLocation().getDirection()), 20, 0.3, 0.3, 0.3, 0.03);
        pl.giveOmen(p, level, FaultlineRaids.OMEN_PIGLIN);
    }

    /** A Piglin Brute killed by a player: 5% for a War Horn (III-V). */
    void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        UUID id = dead.getUniqueId();
        if (dead instanceof PiglinBrute && dead.getKiller() != null && !FaultlineRaids.isRaidMobStatic(dead)
                && random.nextDouble() < cfg("horn-chance", 0.05)) {
            event.getDrops().add(randomHorn());
        }
        // Raid piglins drop no gear, just a few gold nuggets (80%: 3-5); the riders' hoglins drop nothing.
        // The bosses add their own loot below.
        if (dead.getScoreboardTags().contains(UNIT_TAG)
                || ((dead instanceof PiglinAbstract || dead instanceof Hoglin) && FaultlineRaids.isRaidMobStatic(dead))) {
            event.getDrops().clear();
            if (dead instanceof PiglinAbstract && dead.getKiller() != null && random.nextDouble() < cfg("nugget-chance", 0.8)) {
                int lo = (int) cfg("nugget-min", 3), hi = Math.max(lo, (int) cfg("nugget-max", 5));
                event.getDrops().add(new ItemStack(Material.GOLD_NUGGET, lo + random.nextInt(hi - lo + 1)));
            }
        }
        Boss b = bosses.get(id);
        if (b != null) {
            event.getDrops().clear();
            Player killer = dead.getKiller();
            if (b.hog) {
                event.getDrops().add(new ItemStack(Material.GOLD_INGOT, 8 + random.nextInt(9)));
                event.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, 1 + random.nextInt(3)));
                event.setDroppedExp((int) cfg("great-hog.xp", 400));
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "The Great Hog has fallen" + (killer != null ? " to " + killer.getName() : "") + "!");
            } else {
                event.getDrops().add(new ItemStack(Material.GOLD_BLOCK, 1 + random.nextInt(3)));
                event.setDroppedExp((int) cfg("bulwark.xp", 150));
                pl.announceNear(dead.getLocation(), ChatColor.GOLD + "The Bulwark is broken!");
            }
            b.bar.removeAll();
        }
        if (pl.special.get(id) == Kind.PIGLIN_SAPPER && !blown.remove(id)) { // killing a Sapper still sets off its TNT (1 s to back off)
            Location at = dead.getLocation();
            at.getWorld().playSound(at, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
            Bukkit.getScheduler().runTaskLater(pl, () -> at.getWorld().createExplosion(at, (float) cfg("sapper.power", 2.5), false, false), 20L);
        }
        Balloon bl = balloons.get(id);
        if (bl != null) { // the envelope tears and the basket falls
            Location at = dead.getLocation().add(0, 4, 0);
            dead.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 1, 1, 1, 0);
            dead.getWorld().spawnParticle(Particle.BLOCK, at, 60, 1.4, 1.6, 1.4, 0, Material.RED_WOOL.createBlockData());
            dead.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.6f);
        }
    }

    void clear(UUID id) {
        fuses.remove(id);
        next.remove(id); burst.remove(id); portal.remove(id); portalAt.remove(id); summoned.remove(id);
        Balloon bl = balloons.remove(id);
        if (bl != null) for (BlockDisplay d : bl.parts) if (d != null && d.isValid()) d.remove();
        Boss b = bosses.remove(id);
        if (b != null) { b.bar.removeAll(); if (b.crown != null && b.crown.isValid()) b.crown.remove(); }
    }

    // =====================================================================================================
    //  waves: always 15
    // =====================================================================================================
    List<Kind> wave(int w, int level, int total) {
        int size = (int) cfg("wave-size", 15);
        List<Kind> sp = new ArrayList<>(); // the specials: shuffled, then cut to fit (a different mix every raid)
        for (int i = 0; i < 1 + w / 4; i++) sp.add(Kind.PIGLIN_CROSSBOW);
        if (w >= 2) for (int i = 0; i < 1 + w / 5 + (level >= 5 ? 1 : 0); i++) sp.add(Kind.PIGLIN_BRUTE);
        if (w >= 2) for (int i = 0; i < 1 + (w >= 6 ? 1 : 0); i++) sp.add(Kind.PIGLIN_SHIELDBEARER);
        if (w >= 2) for (int i = 0; i < 1 + (w >= 8 ? 1 : 0); i++) sp.add(Kind.PIGLIN_MAGE);
        if (w >= 3) for (int i = 0; i < 1 + (w >= 7 ? 1 : 0); i++) sp.add(Kind.PIGLIN_SAPPER);
        if (w >= 3) sp.add(Kind.PIGLIN_SUMMONER);
        if (w >= 3) for (int i = 0; i < 1 + (w >= 7 ? 1 : 0); i++) sp.add(Kind.PIGLIN_BALLOON);
        if (w >= 4) sp.add(Kind.PIGLIN_LOBBER);
        if (w >= 4) for (int i = 0; i < 1 + (w >= 8 ? 1 : 0); i++) sp.add(Kind.HOGLIN_RIDER);
        if (w >= 5) sp.add(Kind.PIGLIN_BANNER);
        if (w >= 6) sp.add(Kind.PIGLIN_MEDIC);
        java.util.Collections.shuffle(sp, random);
        List<Kind> k = new ArrayList<>();
        if (w == 5) k.add(Kind.BULWARK);
        int room = size - 2 - k.size();
        for (Kind x : sp) if (k.size() - (w == 5 ? 1 : 0) < room) k.add(x);
        if (w == 1 || w == 3 || w == 6 || w == 9) for (int i = 0; i < 3 && k.size() < size; i++) k.add(Kind.PIGLIN_RUNT); // a pack of Runts
        while (k.size() < size) k.add(Kind.PIGLIN_GRUNT);
        return k;
    }

    // =====================================================================================================
    //  spawning
    // =====================================================================================================
    LivingEntity spawn(Kind kind, Location at) {
        World world = at.getWorld();
        EntityType type = switch (kind) {
            case PIGLIN_BRUTE, BULWARK -> EntityType.PIGLIN_BRUTE;
            case GREAT_HOG -> EntityType.HOGLIN;
            default -> EntityType.PIGLIN;
        };
        if (!(world.spawnEntity(at, type, false) instanceof LivingEntity mob) || !mob.isValid()) return null;
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
        mob.addScoreboardTag(UNIT_TAG);
        noZombie(mob); // in the Overworld they'd turn into Zombified Piglins after 15 seconds
        if (mob instanceof Piglin pig) { pig.setAdult(); pig.setIsAbleToHunt(false); }
        FaultlineRaids.setAttr(mob, Attribute.FOLLOW_RANGE, 48);
        EntityEquipment gear = mob.getEquipment();
        switch (kind) {
            case PIGLIN_GRUNT -> {
                hp(mob, cfg("grunt-health", 20));
                if (gear != null) {
                    gear.setItemInMainHand(new ItemStack(random.nextBoolean() ? Material.GOLDEN_SWORD : Material.GOLDEN_AXE));
                    if (random.nextDouble() < 0.4) gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                }
                mob.setCustomName(ChatColor.GOLD + "Piglin Grunt");
            }
            case PIGLIN_CROSSBOW -> {
                hp(mob, cfg("grunt-health", 20));
                if (gear != null) { gear.setItemInMainHand(new ItemStack(Material.CROSSBOW)); gear.setHelmet(FaultlineRaids.dyedStatic(Material.LEATHER_HELMET, Color.fromRGB(120, 70, 30))); }
                mob.setCustomName(ChatColor.GOLD + "Piglin Crossbowman");
            }
            case PIGLIN_BRUTE -> {
                if (gear != null) gear.setItemInMainHand(new ItemStack(Material.GOLDEN_AXE));
                mob.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Piglin Brute");
            }
            case PIGLIN_MAGE -> {
                hp(mob, cfg("mage-health", 24));
                if (gear != null) {
                    gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    gear.setChestplate(FaultlineRaids.dyedStatic(Material.LEATHER_CHESTPLATE, Color.fromRGB(150, 30, 20)));
                    gear.setLeggings(FaultlineRaids.dyedStatic(Material.LEATHER_LEGGINGS, Color.fromRGB(90, 20, 15)));
                    gear.setItemInMainHand(new ItemStack(Material.BLAZE_ROD));
                }
                mob.setCustomName(ChatColor.RED + "Piglin Mage");
            }
            case PIGLIN_SUMMONER -> {
                hp(mob, cfg("summoner-health", 26));
                if (gear != null) {
                    gear.setHelmet(FaultlineRaids.dyedStatic(Material.LEATHER_HELMET, Color.fromRGB(70, 20, 95)));
                    gear.setChestplate(FaultlineRaids.dyedStatic(Material.LEATHER_CHESTPLATE, Color.fromRGB(70, 20, 95)));
                    gear.setItemInMainHand(new ItemStack(Material.CRYING_OBSIDIAN));
                }
                mob.setCustomName(ChatColor.DARK_PURPLE + "Piglin Summoner");
            }
            case PIGLIN_BALLOON -> {
                hp(mob, cfg("balloon.health", 30));
                if (gear != null) { gear.setItemInMainHand(new ItemStack(Material.FIRE_CHARGE)); gear.setHelmet(FaultlineRaids.dyedStatic(Material.LEATHER_HELMET, Color.fromRGB(110, 60, 25))); }
                mob.setGravity(false);
                if (mob instanceof Mob m) m.setAI(false); // it doesn't walk: the balloon carries it
                mob.setCustomName(ChatColor.RED + "Piglin Balloonist");
                balloons.put(mob.getUniqueId(), new Balloon(mob));
            }
            case HOGLIN_RIDER -> {
                hp(mob, cfg("grunt-health", 20));
                if (gear != null) { gear.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD)); gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET)); }
                mob.setCustomName(ChatColor.GOLD + "Hoglin Rider");
                if (world.spawnEntity(at, EntityType.HOGLIN, false) instanceof Hoglin hog && hog.isValid()) {
                    hog.setPersistent(false);
                    hog.setAdult();
                    hog.addScoreboardTag(UNIT_TAG);
                    noZombie(hog);
                    hog.setIsAbleToBeHunted(false);
                    hog.setRemoveWhenFarAway(false);
                    hp(hog, cfg("rider-hoglin-health", 40));
                    FaultlineRaids.setAttr(hog, Attribute.MOVEMENT_SPEED, 0.34);
                    hog.addPassenger(mob);
                    pl.horses.put(mob.getUniqueId(), hog.getUniqueId());
                }
            }
            case PIGLIN_SAPPER -> {
                hp(mob, cfg("sapper-health", 18));
                FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.38);
                if (gear != null) { gear.setHelmet(new ItemStack(Material.TNT)); gear.setItemInMainHand(new ItemStack(Material.FLINT_AND_STEEL)); }
                mob.setCustomName(ChatColor.RED + "Piglin Sapper");
            }
            case PIGLIN_SHIELDBEARER -> {
                hp(mob, cfg("shieldbearer-health", 30));
                FaultlineRaids.setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 0.7);
                FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.27);
                if (gear != null) {
                    gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    gear.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                    gear.setItemInMainHand(new ItemStack(Material.GOLDEN_AXE));
                    gear.setItemInOffHand(new ItemStack(Material.SHIELD));
                }
                mob.addScoreboardTag(SHIELDWALL_TAG);
                mob.setCustomName(ChatColor.GOLD + "Piglin Shieldbearer");
            }
            case PIGLIN_LOBBER -> {
                hp(mob, cfg("lobber-health", 22));
                if (gear != null) { gear.setItemInMainHand(new ItemStack(Material.MAGMA_CREAM)); gear.setHelmet(FaultlineRaids.dyedStatic(Material.LEATHER_HELMET, Color.fromRGB(60, 40, 30))); }
                mob.setCustomName(ChatColor.GOLD + "Piglin Lobber");
            }
            case PIGLIN_RUNT -> {
                if (mob instanceof Piglin pig) pig.setBaby();
                hp(mob, cfg("runt-health", 8));
                FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.42);
                if (gear != null) gear.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
                mob.setCustomName(ChatColor.GOLD + "Piglin Runt");
            }
            case PIGLIN_BANNER -> {
                hp(mob, cfg("banner-health", 28));
                if (gear != null) { gear.setHelmet(warBanner()); gear.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD)); }
                mob.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Piglin Banner Bearer");
            }
            case PIGLIN_MEDIC -> {
                hp(mob, cfg("medic-health", 22));
                if (gear != null) {
                    gear.setHelmet(FaultlineRaids.dyedStatic(Material.LEATHER_HELMET, Color.fromRGB(240, 240, 230)));
                    gear.setChestplate(FaultlineRaids.dyedStatic(Material.LEATHER_CHESTPLATE, Color.fromRGB(240, 240, 230)));
                    gear.setItemInMainHand(new ItemStack(Material.GLISTERING_MELON_SLICE));
                }
                mob.setCustomName(ChatColor.GREEN + "Piglin Medic");
            }
            case BULWARK -> makeBulwark(mob);
            case GREAT_HOG -> makeHog(mob);
            default -> { }
        }
        if (gear != null) {
            gear.setHelmetDropChance(0f); gear.setChestplateDropChance(0f); gear.setLeggingsDropChance(0f);
            gear.setBootsDropChance(0f); gear.setItemInMainHandDropChance(0f); gear.setItemInOffHandDropChance(0f);
        }
        mob.setCustomNameVisible(kind == Kind.BULWARK || kind == Kind.GREAT_HOG);
        switch (kind) {
            case PIGLIN_MAGE, PIGLIN_SUMMONER, PIGLIN_BALLOON, HOGLIN_RIDER, BULWARK, GREAT_HOG,
                 PIGLIN_SAPPER, PIGLIN_LOBBER, PIGLIN_BANNER, PIGLIN_MEDIC -> pl.special.put(mob.getUniqueId(), kind);
            default -> { }
        }
        return mob;
    }

    static void noZombie(LivingEntity e) {
        if (e instanceof PiglinAbstract p) p.setImmuneToZombification(true);
        if (e instanceof Hoglin h) h.setImmuneToZombification(true);
    }

    static void hp(LivingEntity mob, double hp) {
        FaultlineRaids.setAttr(mob, Attribute.MAX_HEALTH, hp);
        mob.setHealth(hp);
    }

    /** Raid piglins join the raid they came with (summoned grunts, the Great Hog's rally). */
    LivingEntity spawnFor(LivingEntity owner, Kind kind, Location at) {
        LivingEntity mob = spawn(kind, at);
        if (mob == null) return null;
        ZombieRaid raid = pl.mobRaid.get(owner.getUniqueId());
        if (raid != null) {
            pl.tagMob(mob, FaultlineRaids.RAID_TAG);
            raid.mobs.add(mob.getUniqueId());
            pl.mobRaid.put(mob.getUniqueId(), raid);
            raid.waveSize++;
        } else pl.tagMob(mob, FaultlineRaids.ARMY_TAG);
        if (mob instanceof Mob m) {
            m.setRemoveWhenFarAway(false);
            LivingEntity t = pl.targetFor(owner);
            if (t != null) m.setTarget(t);
        }
        return mob;
    }

    // =====================================================================================================
    //  every 2 ticks
    // =====================================================================================================
    void tick(LivingEntity mob, Kind kind, long now) {
        switch (kind) {
            case PIGLIN_MAGE -> mageTick(mob, now);
            case PIGLIN_SUMMONER -> summonerTick(mob, now);
            case PIGLIN_BALLOON -> balloonTick(mob, now);
            case HOGLIN_RIDER -> riderTick(mob, now);
            case BULWARK, GREAT_HOG -> bossTick(mob, now);
            case PIGLIN_SAPPER -> sapperTick(mob, now);
            case PIGLIN_LOBBER -> lobberTick(mob, now);
            case PIGLIN_BANNER -> bannerTick(mob, now);
            case PIGLIN_MEDIC -> medicTick(mob, now);
            default -> { }
        }
    }

    /** Is its next action due? (The first check just schedules it 1.5 s out.) */
    boolean ready(LivingEntity mob, long now) {
        Long n = next.get(mob.getUniqueId());
        if (n == null) { next.put(mob.getUniqueId(), now + 1500); return false; }
        return now >= n;
    }

    final Map<String, Long> hitCd = new HashMap<>();
    final java.util.Set<UUID> bombs = new java.util.HashSet<>();
    int spawnLevel = 4; // the raid level for a boss being spawned right now

    /** One hit per attacker per 0.7 s (a charge or a shockwave touches you for several ticks). */
    boolean recentlyHit(LivingEntity v, LivingEntity by) {
        String k = v.getUniqueId() + ":" + by.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < hitCd.getOrDefault(k, 0L)) return true;
        hitCd.put(k, now + 700);
        if (hitCd.size() > 2000) hitCd.values().removeIf(t -> t < now);
        return false;
    }

    // --- PIGLIN MAGE: fireballs from range; a flame burst if you get close
    void mageTick(LivingEntity mob, long now) {
        Integer b = burst.get(mob.getUniqueId());
        if (b != null) { // winding up the burst: fire gathering at its feet
            mob.getWorld().spawnParticle(Particle.FLAME, mob.getLocation().add(0, 0.2, 0), 6, 0.5, 0.1, 0.5, 0.02);
            ring(mob.getLocation(), 4, Color.fromRGB(255, 120, 0));
            if (b <= 0) {
                burst.remove(mob.getUniqueId());
                Location c = mob.getLocation();
                for (int i = 0; i < 48; i++) {
                    double a = i * Math.PI * 2 / 48;
                    for (double r = 1; r <= 4; r += 1.5) mob.getWorld().spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 1, 0, 0.05, 0, 0.02);
                }
                mob.getWorld().playSound(c, Sound.ITEM_FIRECHARGE_USE, 1.4f, 0.6f);
                for (Entity e : mob.getNearbyEntities(4, 2.5, 4)) {
                    if (!(e instanceof LivingEntity v) || FaultlineRaids.isRaidMobStatic(v) || (v instanceof Player p && !FaultlineRaids.survivalStatic(p))) continue;
                    pl.guarded(v, cfg("mage.burst-damage", 5), mob, c.toVector(), false);
                    v.setFireTicks(Math.max(v.getFireTicks(), 50));
                    v.setVelocity(v.getLocation().toVector().subtract(c.toVector()).setY(0).normalize().multiply(0.8).setY(0.35));
                }
                next.put(mob.getUniqueId(), now + 2500);
            } else burst.put(mob.getUniqueId(), b - 1);
            return;
        }
        if (!ready(mob, now)) return;
        LivingEntity target = pl.targetFor(mob);
        if (target == null || !target.getWorld().equals(mob.getWorld())) return;
        double dist = target.getLocation().distance(mob.getLocation());
        if (dist < 3.5) { // too close: a burst (0.8 s warning)
            burst.put(mob.getUniqueId(), 8);
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 1.2f, 0.7f);
            return;
        }
        if (dist > 24 || !mob.hasLineOfSight(target)) return;
        SmallFireball fb = mob.launchProjectile(SmallFireball.class);
        fb.setIsIncendiary(false); // never sets blocks on fire
        fb.setYield(0);
        fb.addScoreboardTag(FIREBALL_TAG);
        Vector aim = target.getEyeLocation().subtract(0, 0.5, 0).toVector().subtract(mob.getEyeLocation().toVector());
        fb.setDirection(aim.normalize());
        fb.setVelocity(aim.normalize().multiply(0.9));
        mob.swingMainHand();
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        next.put(mob.getUniqueId(), now + 2600 + random.nextInt(900));
    }

    // --- PIGLIN SUMMONER: opens a little nether portal and Grunts step out of it (it keeps its distance)
    void summonerTick(LivingEntity mob, long now) {
        LivingEntity target = pl.targetFor(mob);
        if (target != null && target.getWorld().equals(mob.getWorld()) && target.getLocation().distanceSquared(mob.getLocation()) < 36 && mob instanceof Mob m && now % 1000 < 100) {
            Vector away = mob.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.01) m.getPathfinder().moveTo(mob.getLocation().add(away.normalize().multiply(6)), 1.3);
        }
        Integer pt = portal.get(mob.getUniqueId());
        if (pt != null) {
            Location at = portalAt.get(mob.getUniqueId());
            mob.getWorld().spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 30, 0.4, 0.9, 0.4, 0.6);
            mob.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 10, 0.5, 1, 0.5, 0, new Particle.DustOptions(Color.fromRGB(140, 40, 220), 1.5f));
            ring(at, 1.3, Color.fromRGB(140, 40, 220));
            if (pt <= 0) {
                portal.remove(mob.getUniqueId());
                portalAt.remove(mob.getUniqueId());
                List<UUID> mine = summoned.computeIfAbsent(mob.getUniqueId(), k -> new ArrayList<>());
                for (int i = 0; i < 2; i++) {
                    LivingEntity g = spawnFor(mob, Kind.PIGLIN_GRUNT, at.clone().add(random.nextGaussian() * 0.4, 0, random.nextGaussian() * 0.4));
                    if (g != null) { hp(g, cfg("summoned-health", 14)); g.setCustomName(ChatColor.LIGHT_PURPLE + "Summoned Grunt"); mine.add(g.getUniqueId()); }
                }
                mob.getWorld().playSound(at, Sound.BLOCK_PORTAL_TRAVEL, 0.4f, 1.6f);
                next.put(mob.getUniqueId(), now + (long) (cfg("summoner.seconds", 10) * 1000));
            } else portal.put(mob.getUniqueId(), pt - 1);
            return;
        }
        if (!ready(mob, now) || target == null) return;
        List<UUID> mine = summoned.computeIfAbsent(mob.getUniqueId(), k -> new ArrayList<>());
        mine.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
        if (mine.size() >= (int) cfg("summoner.max-alive", 4)) { next.put(mob.getUniqueId(), now + 3000); return; }
        Vector toT = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
        Location at = mob.getLocation().add(toT.lengthSquared() > 0.01 ? toT.normalize().multiply(2.5) : new Vector(2, 0, 0));
        Block feet = at.getBlock();
        if (!feet.isPassable()) at = mob.getLocation();
        portal.put(mob.getUniqueId(), 15); // 1.5 s
        portalAt.put(mob.getUniqueId(), at);
        mob.swingMainHand();
        mob.getWorld().playSound(at, Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.4f);
    }

    // --- HOGLIN RIDER: hoglins don't follow their rider, so point it at the target
    void riderTick(LivingEntity rider, long now) {
        if (now < next.getOrDefault(rider.getUniqueId(), 0L)) return;
        next.put(rider.getUniqueId(), now + 500);
        UUID hogId = pl.horses.get(rider.getUniqueId());
        Entity hog = hogId == null ? null : Bukkit.getEntity(hogId);
        LivingEntity target = pl.targetFor(rider);
        if (hog instanceof Mob h && h.isValid() && target != null && target.getWorld().equals(h.getWorld())) {
            h.setTarget(target);
            h.getPathfinder().moveTo(target, 1.6);
        }
    }

    // --- PIGLIN SAPPER: runs at you with TNT on its head; close enough and it lights it (1 s fuse). Never breaks blocks.
    final Map<UUID, Integer> fuses = new HashMap<>();
    final java.util.Set<UUID> blown = new java.util.HashSet<>();

    void sapperTick(LivingEntity mob, long now) {
        Integer f = fuses.get(mob.getUniqueId());
        if (f != null) {
            mob.getWorld().spawnParticle(Particle.SMOKE, mob.getEyeLocation().add(0, 0.5, 0), 4, 0.1, 0.1, 0.1, 0.02);
            if (f % 2 == 0) mob.getWorld().spawnParticle(Particle.DUST, mob.getEyeLocation().add(0, 0.5, 0), 6, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(Color.WHITE, 1.6f)); // flashing
            if (f <= 0) {
                Location at = mob.getLocation();
                blown.add(mob.getUniqueId());
                fuses.remove(mob.getUniqueId());
                mob.setHealth(0);
                at.getWorld().createExplosion(at, (float) cfg("sapper.power", 2.5), false, false, mob);
            } else fuses.put(mob.getUniqueId(), f - 1);
            return;
        }
        LivingEntity t = pl.targetFor(mob);
        if (t != null && t.getWorld().equals(mob.getWorld()) && t.getLocation().distanceSquared(mob.getLocation()) < 2.6 * 2.6) {
            fuses.put(mob.getUniqueId(), 10); // 20 ticks
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_TNT_PRIMED, 1.2f, 1.2f);
            if (mob instanceof Mob m) m.getPathfinder().stopPathfinding();
        }
    }

    // --- PIGLIN LOBBER: lobs magma over walls; where it lands, the ground burns for a few seconds
    void lobberTick(LivingEntity mob, long now) {
        if (!ready(mob, now)) return;
        LivingEntity t = pl.targetFor(mob);
        if (t == null || !t.getWorld().equals(mob.getWorld())) return;
        Vector d = t.getLocation().toVector().subtract(mob.getLocation().toVector());
        double flat = Math.hypot(d.getX(), d.getZ());
        if (flat < 4 || flat > 22) return;
        Location from = mob.getEyeLocation();
        double ticks = 18 + flat * 0.8, g = 0.03;
        Vector v = new Vector(d.getX() / ticks, (t.getLocation().getY() - from.getY()) / ticks + 0.5 * g * ticks, d.getZ() / ticks);
        Snowball ball = mob.getWorld().spawn(from, Snowball.class, s -> {
            s.setItem(new ItemStack(Material.MAGMA_CREAM));
            s.setShooter(mob);
            s.addScoreboardTag(MAGMA_TAG);
        });
        ball.setVelocity(v);
        bombs.add(ball.getUniqueId()); // (gets a smoke trail too)
        mob.swingMainHand();
        mob.getWorld().playSound(from, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.5f);
        circleWarn.put(t.getLocation().clone(), System.currentTimeMillis() + (long) (ticks * 50));
        next.put(mob.getUniqueId(), now + (long) (cfg("lobber.seconds", 4) * 1000));
    }

    final Map<Location, Long> circleWarn = new HashMap<>(); // where magma is about to land
    final Map<Location, Long> zones = new HashMap<>();      // burning ground: until when
    final Map<Location, UUID> zoneOwner = new HashMap<>();

    @EventHandler
    public void onMagma(ProjectileHitEvent event) {
        Projectile p = event.getEntity();
        if (!p.getScoreboardTags().contains(MAGMA_TAG)) return;
        bombs.remove(p.getUniqueId());
        Location at = p.getLocation();
        p.remove();
        at.getWorld().playSound(at, Sound.BLOCK_LAVA_POP, 1.4f, 0.7f);
        at.getWorld().spawnParticle(Particle.LAVA, at, 12, 0.6, 0.2, 0.6, 0);
        zones.put(at, System.currentTimeMillis() + (long) (cfg("lobber.zone-seconds", 4) * 1000));
        if (p.getShooter() instanceof Entity e) zoneOwner.put(at, e.getUniqueId());
    }

    int zoneTicks;
    void zoneTick() {
        long now = System.currentTimeMillis();
        zoneTicks++;
        circleWarn.entrySet().removeIf(e -> { if (now > e.getValue()) return true; if (zoneTicks % 2 == 0) ring(e.getKey(), 2.2, Color.fromRGB(255, 90, 0)); return false; });
        zones.entrySet().removeIf(e -> {
            Location at = e.getKey();
            if (now > e.getValue() || at.getWorld() == null) { zoneOwner.remove(at); return true; }
            at.getWorld().spawnParticle(Particle.FLAME, at, 6, 1.4, 0.1, 1.4, 0.01);
            if (zoneTicks % 3 == 0) at.getWorld().spawnParticle(Particle.LAVA, at, 2, 1.2, 0.1, 1.2, 0);
            if (zoneTicks % 5 == 0) {
                Entity owner = zoneOwner.containsKey(at) ? Bukkit.getEntity(zoneOwner.get(at)) : null;
                for (Entity en : at.getWorld().getNearbyEntities(at, 2.2, 1.5, 2.2)) {
                    if (!(en instanceof LivingEntity v) || FaultlineRaids.isRaidMobStatic(v) || (v instanceof Player pp && !FaultlineRaids.survivalStatic(pp))) continue;
                    pl.guarded(v, cfg("lobber.zone-damage", 2), owner, at.toVector(), false);
                    v.setFireTicks(Math.max(v.getFireTicks(), 40));
                }
            }
            return false;
        });
    }

    // --- PIGLIN BANNER BEARER: every few seconds, every piglin around it gets Strength and Speed. Kill it first.
    void bannerTick(LivingEntity mob, long now) {
        if (now % 1000 < 100) ring(mob.getLocation(), 1.2, Color.fromRGB(255, 200, 40));
        if (!ready(mob, now)) return;
        int n = 0;
        for (Entity e : mob.getNearbyEntities(10, 5, 10)) {
            if (!(e instanceof LivingEntity ally) || !FaultlineRaids.isRaidMobStatic(ally) || e instanceof Player) continue;
            ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, false, true));
            ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 0, false, true));
            ally.getWorld().spawnParticle(Particle.DUST, ally.getLocation().add(0, ally.getHeight() + 0.3, 0), 4, 0.2, 0.1, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.2f));
            n++;
        }
        if (n > 0) {
            mob.swingMainHand();
            mob.getWorld().playSound(mob.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1.6f);
            mob.getWorld().spawnParticle(Particle.FLAME, mob.getEyeLocation().add(0, 0.8, 0), 12, 0.3, 0.3, 0.3, 0.03);
        }
        next.put(mob.getUniqueId(), now + (long) (cfg("banner.seconds", 8) * 1000));
    }

    // --- PIGLIN MEDIC: stays back and heals whichever piglin is hurt the most. Kill it first.
    void medicTick(LivingEntity mob, long now) {
        LivingEntity t = pl.targetFor(mob);
        if (t != null && t.getWorld().equals(mob.getWorld()) && t.getLocation().distanceSquared(mob.getLocation()) < 36 && mob instanceof Mob m && now % 1000 < 100) {
            Vector away = mob.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.01) m.getPathfinder().moveTo(mob.getLocation().add(away.normalize().multiply(6)), 1.3);
        }
        if (!ready(mob, now)) return;
        LivingEntity worst = null; double ratio = 0.9;
        for (Entity e : mob.getNearbyEntities(10, 5, 10)) {
            if (!(e instanceof LivingEntity ally) || !FaultlineRaids.isRaidMobStatic(ally) || e instanceof Player) continue;
            AttributeInstance max = ally.getAttribute(Attribute.MAX_HEALTH);
            if (max == null) continue;
            double r = ally.getHealth() / max.getValue();
            if (r < ratio) { ratio = r; worst = ally; }
        }
        if (worst == null) { next.put(mob.getUniqueId(), now + 1500); return; }
        AttributeInstance max = worst.getAttribute(Attribute.MAX_HEALTH);
        worst.setHealth(Math.min(max.getValue(), worst.getHealth() + cfg("medic.heal", 8)));
        Vector from = mob.getEyeLocation().toVector(), to = worst.getLocation().add(0, worst.getHeight() / 2, 0).toVector();
        Vector step = to.clone().subtract(from);
        double len = step.length();
        for (double d = 0; d < len; d += 0.5) mob.getWorld().spawnParticle(Particle.DUST, from.clone().add(step.clone().multiply(d / len)).toLocation(mob.getWorld()), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 220, 90), 1.0f));
        worst.getWorld().spawnParticle(Particle.HEART, worst.getLocation().add(0, worst.getHeight() + 0.3, 0), 3, 0.3, 0.2, 0.3, 0);
        mob.swingMainHand();
        mob.getWorld().playSound(mob.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        next.put(mob.getUniqueId(), now + (long) (cfg("medic.seconds", 5) * 1000));
    }

    ItemStack warBanner() {
        ItemStack b = new ItemStack(Material.ORANGE_BANNER);
        if (b.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta bm) {
            bm.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.BLACK, org.bukkit.block.banner.PatternType.PIGLIN));
            bm.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.YELLOW, org.bukkit.block.banner.PatternType.BORDER));
            b.setItemMeta(bm);
        }
        return b;
    }

    // =====================================================================================================
    //  the hot air balloon: a red and gold envelope, ropes, a basket with a Piglin in it, dropping fire bombs
    // =====================================================================================================
    /** Chains are IRON_CHAIN in newer versions and CHAIN in older ones. */
    static final Material ROPE = Material.matchMaterial("IRON_CHAIN") != null ? Material.matchMaterial("IRON_CHAIN")
            : Material.matchMaterial("CHAIN") != null ? Material.matchMaterial("CHAIN") : Material.OAK_FENCE;

    final class Balloon {
        final BlockDisplay[] parts = new BlockDisplay[9];
        double angle = random.nextDouble() * Math.PI * 2;
        // block, scale x/y/z, offset x/y/z from the piglin's feet (offset = the block's min corner)
        final Object[][] shape = {
                {Material.BARREL, 1.3f, 0.75f, 1.3f, -0.65f, -0.25f, -0.65f},         // the basket
                {Material.RED_WOOL, 3.4f, 3.6f, 3.4f, -1.7f, 3.2f, -1.7f},           // the envelope
                {Material.YELLOW_WOOL, 3.55f, 0.55f, 3.55f, -1.775f, 4.6f, -1.775f}, // gold band
                {Material.ORANGE_WOOL, 2.4f, 0.7f, 2.4f, -1.2f, 6.8f, -1.2f},        // crown of the envelope
                {Material.ORANGE_WOOL, 1.8f, 0.6f, 1.8f, -0.9f, 2.65f, -0.9f},       // its mouth
                {ROPE, 1f, 2.6f, 1f, -1.1f, 0.4f, -1.1f},                  // ropes
                {ROPE, 1f, 2.6f, 1f, 0.1f, 0.4f, -1.1f},
                {ROPE, 1f, 2.6f, 1f, -1.1f, 0.4f, 0.1f},
                {ROPE, 1f, 2.6f, 1f, 0.1f, 0.4f, 0.1f},
        };

        Balloon(LivingEntity mob) {
            Location at = mob.getLocation();
            for (int i = 0; i < parts.length; i++) {
                Object[] s = shape[i];
                BlockData data = ((Material) s[0]).createBlockData();
                Transformation tr = new Transformation(new Vector3f((float) s[4], (float) s[5], (float) s[6]), new Quaternionf(),
                        new Vector3f((float) s[1], (float) s[2], (float) s[3]), new Quaternionf());
                parts[i] = at.getWorld().spawn(at, BlockDisplay.class, d -> {
                    d.setBlock(data);
                    d.setTransformation(tr);
                    d.setPersistent(false);
                    d.setTeleportDuration(2);
                    d.setViewRange(4f);
                    d.addScoreboardTag(DISPLAY_TAG);
                });
            }
        }
    }

    void balloonTick(LivingEntity mob, long now) {
        Balloon b = balloons.get(mob.getUniqueId());
        if (b == null) return;
        World w = mob.getWorld();
        Location here = mob.getLocation();
        LivingEntity target = pl.targetFor(mob);
        ZombieRaid raid = pl.mobRaid.get(mob.getUniqueId());
        Location around = target != null && target.getWorld().equals(w) ? target.getLocation() : raid != null ? raid.center : here;
        b.angle += 0.025;
        double r = 7;
        double gx = around.getX() + Math.cos(b.angle) * r, gz = around.getZ() + Math.sin(b.angle) * r;
        double gy = w.getHighestBlockYAt((int) Math.floor(here.getX()), (int) Math.floor(here.getZ())) + cfg("balloon.height", 12);
        Vector step = new Vector(gx - here.getX(), (gy - here.getY()) * 0.15, gz - here.getZ());
        double len = Math.hypot(step.getX(), step.getZ());
        double speed = cfg("balloon.speed", 0.22);
        if (len > speed) { step.setX(step.getX() / len * speed); step.setZ(step.getZ() / len * speed); }
        Location to = here.clone().add(step);
        if (target != null && target.getWorld().equals(w)) {
            Vector f = target.getLocation().toVector().subtract(to.toVector());
            to.setYaw((float) Math.toDegrees(Math.atan2(-f.getX(), f.getZ())));
        }
        mob.teleport(to);
        for (BlockDisplay d : b.parts) if (d != null && d.isValid()) d.teleport(new Location(w, to.getX(), to.getY(), to.getZ()));
        w.spawnParticle(Particle.FLAME, to.clone().add(0, 2.4, 0), 2, 0.1, 0.1, 0.1, 0.01); // the burner
        if (now % 3000 < 100) w.playSound(to, Sound.ENTITY_BLAZE_BURN, 0.6f, 0.6f);
        // bombs
        if (target == null || !ready(mob, now)) return;
        Vector flat = target.getLocation().toVector().subtract(to.toVector()).setY(0);
        if (flat.length() > 12) return;
        Snowball bomb = w.spawn(to.clone().add(0, 0.2, 0), Snowball.class, s -> {
            s.setItem(new ItemStack(Material.FIRE_CHARGE));
            s.setShooter(mob);
            s.addScoreboardTag(BOMB_TAG);
        });
        bombs.add(bomb.getUniqueId());
        double fall = Math.max(4, to.getY() - target.getLocation().getY());
        double flightTicks = Math.sqrt(2 * fall / 0.03);
        bomb.setVelocity(flat.multiply(1 / flightTicks).setY(-0.1));
        w.playSound(to, Sound.ENTITY_TNT_PRIMED, 0.8f, 1.4f);
        next.put(mob.getUniqueId(), now + (long) (cfg("balloon.bomb-seconds", 3.5) * 1000));
    }

    @EventHandler
    public void onBomb(ProjectileHitEvent event) {
        Projectile p = event.getEntity();
        if (!p.getScoreboardTags().contains(BOMB_TAG)) return;
        bombs.remove(p.getUniqueId());
        Location at = p.getLocation();
        Entity src = p.getShooter() instanceof Entity e ? e : null;
        p.remove();
        at.getWorld().spawnParticle(Particle.FLAME, at, 30, 0.8, 0.4, 0.8, 0.05);
        at.getWorld().createExplosion(at, (float) cfg("balloon.bomb-power", 2.0), false, false, src); // never breaks blocks or starts fires
    }

    /** Bomb trails, so you can see them coming. */
    void projectileTick() {
        bombs.removeIf(id -> {
            Entity s = Bukkit.getEntity(id);
            if (s == null || !s.isValid()) return true;
            s.getWorld().spawnParticle(Particle.SMOKE, s.getLocation(), 3, 0.05, 0.05, 0.05, 0.01);
            s.getWorld().spawnParticle(Particle.FLAME, s.getLocation(), 1, 0.05, 0.05, 0.05, 0.01);
            return false;
        });
    }

    void ring(Location c, double r, Color col) {
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.6f));
        }
    }

    // =====================================================================================================
    //  bosses: THE BULWARK (wave 5) and THE GREAT HOG (after the last wave)
    // =====================================================================================================
    final class Boss {
        final boolean hog;
        final BossBar bar;
        String move;   // null = between moves
        int moveT;     // calls into the move (one call = 2 ticks)
        Vector dir;
        long nextMove;
        long guardUntil, stunUntil;
        boolean enraged;
        int chain;
        ItemDisplay crown;
        Boss(boolean hog, String name, BarColor color) {
            this.hog = hog;
            bar = Bukkit.createBossBar(name, color, BarStyle.SEGMENTED_20);
            nextMove = System.currentTimeMillis() + 3000;
        }
    }

    void makeBulwark(LivingEntity mob) {
        int lv = levelOf(mob);
        mob.addScoreboardTag(BULWARK_TAG);
        hp(mob, cfg("bulwark.health", 220) + cfg("bulwark.health-per-level", 60) * Math.max(0, lv - 3));
        FaultlineRaids.setAttr(mob, Attribute.SCALE, 1.7);
        FaultlineRaids.setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        FaultlineRaids.setAttr(mob, Attribute.ATTACK_DAMAGE, cfg("bulwark.melee-damage", 12));
        FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.3);
        EntityEquipment g = mob.getEquipment();
        if (g != null) {
            g.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            g.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            g.setItemInMainHand(new ItemStack(Material.GOLDEN_AXE));
            g.setItemInOffHand(new ItemStack(Material.SHIELD));
        }
        mob.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "The Bulwark");
        bosses.put(mob.getUniqueId(), new Boss(false, ChatColor.GOLD + "" + ChatColor.BOLD + "The Bulwark" + ChatColor.RESET + ChatColor.GRAY + " — the Piglins' shield", BarColor.YELLOW));
    }

    void makeHog(LivingEntity mob) {
        int lv = levelOf(mob);
        mob.addScoreboardTag(HOG_TAG);
        if (mob instanceof Hoglin h) { h.setAdult(); h.setIsAbleToBeHunted(false); }
        hp(mob, cfg("great-hog.health", 600) + cfg("great-hog.health-per-level", 200) * Math.max(0, lv - 3));
        FaultlineRaids.setAttr(mob, Attribute.SCALE, cfg("great-hog.scale", 2.4) + 0.2 * Math.max(0, lv - 3));
        FaultlineRaids.setAttr(mob, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        FaultlineRaids.setAttr(mob, Attribute.ATTACK_DAMAGE, cfg("great-hog.melee-damage", 12));
        FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.3);
        FaultlineRaids.setAttr(mob, Attribute.STEP_HEIGHT, 1.5);
        mob.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "The Great Hog");
        Boss b = new Boss(true, ChatColor.GOLD + "" + ChatColor.BOLD + "The Great Hog" + ChatColor.RESET + ChatColor.GRAY + " — Leader of the Horde", BarColor.RED);
        b.crown = mob.getWorld().spawn(mob.getLocation(), ItemDisplay.class, d -> { // a big gold crown riding on its head
            d.setItemStack(new ItemStack(Material.GOLDEN_HELMET));
            d.setTransformation(new Transformation(new Vector3f(0, -0.2f, 0.9f), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            d.setPersistent(false);
            d.addScoreboardTag(DISPLAY_TAG);
        });
        mob.addPassenger(b.crown);
        bosses.put(mob.getUniqueId(), b);
    }

    int levelOf(LivingEntity mob) {
        ZombieRaid raid = pl.mobRaid.get(mob.getUniqueId());
        return raid != null ? raid.level : spawnLevel;
    }

    void bossTick(LivingEntity mob, long now) {
        Boss b = bosses.get(mob.getUniqueId());
        if (b == null) return;
        AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max == null ? 100 : max.getValue();
        b.bar.setProgress(Math.max(0, Math.min(1, mob.getHealth() / maxHp)));
        for (Player p : new ArrayList<>(b.bar.getPlayers()))
            if (!p.isOnline() || !p.getWorld().equals(mob.getWorld()) || p.getLocation().distanceSquared(mob.getLocation()) > 64 * 64) b.bar.removePlayer(p);
        for (Player p : mob.getWorld().getPlayers())
            if (!b.bar.getPlayers().contains(p) && p.getLocation().distanceSquared(mob.getLocation()) < 48 * 48) b.bar.addPlayer(p);
        if (b.hog) hogTick(mob, b, now, maxHp); else bulwarkTick(mob, b, now);
    }

    // --- THE BULWARK: Shield Bash, Ground Slam, and Unbreakable (a shield wall: hit it from behind)
    void bulwarkTick(LivingEntity mob, Boss b, long now) {
        LivingEntity target = pl.targetFor(mob);
        if (now < b.guardUntil) {
            mob.getWorld().spawnParticle(Particle.ENCHANTED_HIT, mob.getLocation().add(fwd(mob).multiply(1.2)).add(0, 1.6, 0), 4, 0.4, 0.6, 0.4, 0);
            if (target instanceof Mob) { } // (no-op)
        }
        if (b.move == null) {
            if (target == null || now < b.nextMove) return;
            double d = target.getLocation().distance(mob.getLocation());
            double r = random.nextDouble();
            if (d >= 4 && d <= 14 && r < 0.55) startMove(b, "bash", mob, target);
            else if (d < 6 && r < 0.85) startMove(b, "slam", mob, target);
            else if (mob.getHealth() < (mob.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.75)) startMove(b, "guard", mob, target);
            else b.nextMove = now + 1500;
            return;
        }
        b.moveT++;
        switch (b.move) {
            case "bash" -> {
                if (b.moveT <= 7) { // wind-up: shield forward, a red line where it'll go
                    if (target != null) b.dir = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0).normalize();
                    if (mob instanceof Mob m) m.getPathfinder().stopPathfinding();
                    line(mob.getLocation(), b.dir, 12, Color.fromRGB(230, 0, 0));
                    return;
                }
                if (b.moveT == 8) mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 1.1f);
                mob.setVelocity(b.dir.clone().multiply(0.95).setY(mob.getVelocity().getY()));
                for (Entity e : mob.getNearbyEntities(1.8, 1.5, 1.8)) if (e instanceof Player p && FaultlineRaids.survivalStatic(p) && !recentlyHit(p, mob)) {
                    pl.guarded(p, cfg("bulwark.bash-damage", 9), mob, mob.getLocation().toVector(), true);
                    p.setVelocity(b.dir.clone().multiply(1.6).setY(0.5));
                }
                if (b.moveT >= 16) endMove(b, now, 2500);
            }
            case "slam" -> {
                if (b.moveT <= 8) { ring(mob.getLocation(), 5, Color.fromRGB(230, 0, 0)); ring(mob.getLocation(), 3, Color.fromRGB(230, 0, 0)); return; }
                Location c = mob.getLocation();
                mob.getWorld().spawnParticle(Particle.EXPLOSION, c, 4, 2, 0.1, 2, 0);
                mob.getWorld().spawnParticle(Particle.BLOCK, c, 60, 2.5, 0.2, 2.5, 0, c.clone().subtract(0, 1, 0).getBlock().getBlockData());
                mob.getWorld().playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.6f);
                for (Entity e : mob.getNearbyEntities(5, 2.5, 5)) if (e instanceof LivingEntity v && !FaultlineRaids.isRaidMobStatic(v) && v.isOnGround()
                        && !(v instanceof Player p && !FaultlineRaids.survivalStatic(p))) {
                    pl.guarded(v, cfg("bulwark.slam-damage", 10), mob, c.toVector(), true);
                    v.setVelocity(new Vector(0, 0.9, 0));
                }
                endMove(b, now, 3000);
            }
            case "guard" -> {
                if (b.moveT == 1) {
                    b.guardUntil = now + 4000;
                    mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.5f, 0.5f);
                    for (Player p : pl.playersAround(mob.getLocation(), 32)) p.sendActionBar(FaultlineRaids.legacyStatic(ChatColor.GOLD + "The Bulwark raises its shield! " + ChatColor.GRAY + "Hit it from behind."));
                }
                endMove(b, now, 4500);
            }
            default -> endMove(b, now, 1500);
        }
    }

    // --- THE GREAT HOG: Charge (into a wall = stunned), Earthshaker (jump the ring), Rally, Fire Breath
    void hogTick(LivingEntity mob, Boss b, long now, double maxHp) {
        if (!b.enraged && mob.getHealth() < maxHp * 0.5) {
            b.enraged = true;
            FaultlineRaids.setAttr(mob, Attribute.MOVEMENT_SPEED, 0.36);
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, 3f, 0.4f);
            for (Player p : pl.playersAround(mob.getLocation(), 64))
                p.showTitle(net.kyori.adventure.title.Title.title(FaultlineRaids.legacyStatic(ChatColor.RED + "" + ChatColor.BOLD + "THE GREAT HOG IS ENRAGED"),
                        FaultlineRaids.legacyStatic(ChatColor.GRAY + "It charges again and again..."),
                        net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(600))));
        }
        if (now < b.stunUntil) { // stunned after hitting a wall: stars, and it takes extra damage
            mob.getWorld().spawnParticle(Particle.CRIT, mob.getLocation().add(0, mob.getHeight() + 0.3, 0), 6, 0.6, 0.1, 0.6, 0.1);
            return;
        }
        if (b.move == null && mob instanceof Mob m0 && !m0.hasAI()) m0.setAI(true);
        LivingEntity target = pl.targetFor(mob);
        if (b.move == null) {
            if (target == null || now < b.nextMove) return;
            double d = target.getLocation().distance(mob.getLocation());
            double r = random.nextDouble();
            ZombieRaid raid = pl.mobRaid.get(mob.getUniqueId());
            if (raid != null && raid.mobs.size() < 6 && r < 0.18) startMove(b, "rally", mob, target);
            else if (d >= 6 && d <= 30 && r < 0.6) { b.chain = b.enraged ? 2 : 1; startMove(b, "charge", mob, target); }
            else if (d < 10 && r < 0.8) startMove(b, "stomp", mob, target);
            else if (d < 9) startMove(b, "breath", mob, target);
            else { b.chain = b.enraged ? 2 : 1; startMove(b, "charge", mob, target); }
            return;
        }
        b.moveT++;
        double spd = b.enraged ? 1.3 : 1.0;
        switch (b.move) {
            case "charge" -> {
                int wind = (int) (11 / spd);
                if (b.moveT <= wind) { // pawing the ground, a red lane where it'll run
                    if (mob instanceof Mob m) m.setAI(false);
                    if (target != null) b.dir = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0).normalize();
                    face(mob, b.dir);
                    line(mob.getLocation(), b.dir, 24, Color.fromRGB(230, 0, 0));
                    if (b.moveT % 3 == 0) { mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_HOGLIN_STEP, 1.5f, 0.5f); mob.getWorld().spawnParticle(Particle.BLOCK, mob.getLocation(), 12, 0.6, 0.1, 0.6, 0, mob.getLocation().subtract(0, 1, 0).getBlock().getBlockData()); }
                    if (b.moveT == wind) mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, 2f, 0.5f);
                    return;
                }
                // the rush: 1.4 blocks a call, flattening anyone in the way
                Location here = mob.getLocation();
                Location nxt = here.clone().add(b.dir.clone().multiply(1.4));
                int gy = groundY(nxt, here.getBlockY());
                double width = mob.getWidth() / 2;
                Block ahead = here.clone().add(b.dir.clone().multiply(width + 1.0)).add(0, 1, 0).getBlock();
                if (gy == Integer.MIN_VALUE || (!ahead.isPassable() && !ahead.getRelative(0, 1, 0).isPassable()) || b.moveT > wind + 20) {
                    boolean wall = gy == Integer.MIN_VALUE || !ahead.isPassable();
                    if (wall) { // slammed into something: stunned
                        b.stunUntil = now + (long) (cfg("great-hog.stun-seconds", 3) * 1000);
                        mob.getWorld().playSound(here, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.6f, 0.5f);
                        mob.getWorld().spawnParticle(Particle.EXPLOSION, here.clone().add(b.dir.clone().multiply(width + 0.5)).add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
                        for (Player p : pl.playersAround(here, 32)) p.sendActionBar(FaultlineRaids.legacyStatic(ChatColor.GOLD + "The Great Hog is stunned! " + ChatColor.GRAY + "Hit it now!"));
                        b.move = null; b.moveT = 0; b.nextMove = b.stunUntil + 800;
                        return;
                    }
                    if (--b.chain > 0) { b.moveT = 0; return; }
                    endMove(b, now, 2000);
                    return;
                }
                nxt.setY(gy);
                nxt.setYaw(here.getYaw());
                mob.teleport(nxt);
                mob.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, nxt, 2, 0.6, 0.1, 0.6, 0.01);
                if (b.moveT % 2 == 0) mob.getWorld().playSound(nxt, Sound.ENTITY_HOGLIN_STEP, 2f, 0.6f);
                for (Entity e : mob.getNearbyEntities(width + 1, 2, width + 1)) if (e instanceof LivingEntity v && !FaultlineRaids.isRaidMobStatic(v)
                        && !(v instanceof Player p && !FaultlineRaids.survivalStatic(p)) && !recentlyHit(v, mob)) {
                    pl.guarded(v, cfg("great-hog.charge-damage", 13), mob, here.toVector(), true);
                    v.setVelocity(b.dir.clone().multiply(1.4).setY(0.8));
                }
            }
            case "stomp" -> {
                int wind = (int) (10 / spd);
                if (b.moveT == 1) { mob.setVelocity(new Vector(0, 0.75, 0)); mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, 2f, 0.4f); }
                if (b.moveT <= wind) { ring(mob.getLocation(), 3, Color.fromRGB(230, 0, 0)); return; }
                if (b.moveT == wind + 1) {
                    mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2f, 0.4f);
                    mob.getWorld().spawnParticle(Particle.EXPLOSION, mob.getLocation(), 6, 2, 0.1, 2, 0);
                }
                double r = 2 + (b.moveT - wind) * 0.9; // the shockwave rolls outward: jump it
                Location c = mob.getLocation();
                BlockData under = c.clone().subtract(0, 1, 0).getBlock().getBlockData();
                for (int i = 0; i < 48; i++) {
                    double a = i * Math.PI * 2 / 48;
                    c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0, under);
                }
                for (Entity e : mob.getNearbyEntities(r + 1, 2, r + 1)) if (e instanceof LivingEntity v && !FaultlineRaids.isRaidMobStatic(v) && v.isOnGround()
                        && !(v instanceof Player p && !FaultlineRaids.survivalStatic(p)) && Math.abs(v.getLocation().distance(c) - r) < 1.0 && !recentlyHit(v, mob)) {
                    pl.guarded(v, cfg("great-hog.stomp-damage", 9), mob, c.toVector(), true);
                    v.setVelocity(v.getLocation().toVector().subtract(c.toVector()).setY(0).normalize().multiply(0.6).setY(0.9));
                }
                if (r >= 12) endMove(b, now, 2200);
            }
            case "rally" -> {
                if (b.moveT == 1) {
                    mob.getWorld().playSound(mob.getLocation(), Sound.EVENT_RAID_HORN, 3f, 0.5f);
                    mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, 3f, 0.3f);
                    if (mob instanceof Mob m) m.getPathfinder().stopPathfinding();
                }
                if (b.moveT == 10) {
                    int n = 2 + levelOf(mob) / 2;
                    for (int i = 0; i < n; i++) {
                        Location at = pl.ring(mob.getLocation(), 4, 9);
                        if (at == null) at = mob.getLocation();
                        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 15, 0.4, 0.8, 0.4, 0.02);
                        spawnFor(mob, random.nextDouble() < 0.3 ? Kind.PIGLIN_BRUTE : Kind.PIGLIN_GRUNT, at);
                    }
                    for (Player p : pl.playersAround(mob.getLocation(), 48)) p.sendActionBar(FaultlineRaids.legacyStatic(ChatColor.GOLD + "The Great Hog calls the Horde!"));
                }
                if (b.moveT >= 14) endMove(b, now, 3000);
            }
            case "breath" -> {
                if (b.moveT == 1 && mob instanceof Mob m) m.setAI(false);
                if (target != null && b.moveT <= 6) { b.dir = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0).normalize(); face(mob, b.dir); }
                Location mouth = mob.getLocation().add(b.dir.clone().multiply(mob.getWidth() / 2 + 0.3)).add(0, mob.getHeight() * 0.45, 0);
                if (b.moveT <= 6) { mob.getWorld().spawnParticle(Particle.SMOKE, mouth, 8, 0.2, 0.2, 0.2, 0.02); cone(mob.getLocation(), b.dir, 8, 30, Color.fromRGB(255, 120, 0)); return; }
                for (int i = 0; i < 10; i++) {
                    Vector v = b.dir.clone().rotateAroundY((random.nextDouble() - 0.5) * Math.toRadians(60)).multiply(0.5 + random.nextDouble() * 0.4);
                    mob.getWorld().spawnParticle(Particle.FLAME, mouth, 0, v.getX(), v.getY() + 0.02, v.getZ(), 1);
                }
                if (b.moveT % 3 == 0) mob.getWorld().playSound(mouth, Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
                if (b.moveT % 4 == 0) for (Entity e : mob.getNearbyEntities(9, 3, 9)) {
                    if (!(e instanceof LivingEntity v) || FaultlineRaids.isRaidMobStatic(v) || (v instanceof Player p && !FaultlineRaids.survivalStatic(p))) continue;
                    Vector to = v.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                    if (to.length() > 8 + mob.getWidth() / 2 || Math.toDegrees(to.angle(b.dir)) > 32) continue;
                    pl.guarded(v, cfg("great-hog.breath-damage", 3), mob, mouth.toVector(), false);
                    v.setFireTicks(Math.max(v.getFireTicks(), 60));
                }
                if (b.moveT >= 18) endMove(b, now, 2500);
            }
            default -> endMove(b, now, 1500);
        }
    }

    void startMove(Boss b, String move, LivingEntity mob, LivingEntity target) {
        b.move = move; b.moveT = 0;
        b.dir = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (b.dir.lengthSquared() < 0.01) b.dir = fwd(mob);
        b.dir.normalize();
    }

    void endMove(Boss b, long now, long pause) {
        b.move = null; b.moveT = 0;
        b.nextMove = now + (long) (pause / (b.enraged ? 1.4 : 1.0));
    }

    /** The Bulwark's shield wall: 80% less damage from the front. Stunned Great Hog: 50% more. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHurt(EntityDamageByEntityEvent event) {
        if (event.getEntity().getScoreboardTags().contains(SHIELDWALL_TAG) && event.getEntity() instanceof LivingEntity sb) { // Shieldbearer: 75% less from the front
            Entity src = event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : event.getDamager();
            Vector to = src.getLocation().toVector().subtract(sb.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 0.01 && Math.toDegrees(to.angle(fwd(sb))) < 65) {
                event.setDamage(event.getDamage() * (1 - cfg("shieldbearer.front-block", 0.75)));
                sb.getWorld().playSound(sb.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 1f);
            }
            return;
        }
        Boss b = bosses.get(event.getEntity().getUniqueId());
        if (b == null) return;
        long now = System.currentTimeMillis();
        Entity src = event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : event.getDamager();
        if (!b.hog && now < b.guardUntil && event.getEntity() instanceof LivingEntity mob) {
            Vector to = src.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 0.01 && Math.toDegrees(to.angle(fwd(mob))) < 70) {
                event.setDamage(event.getDamage() * 0.2);
                mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
            }
        }
        if (b.hog && now < b.stunUntil) event.setDamage(event.getDamage() * 1.5);
    }

    static Vector fwd(LivingEntity e) {
        double yaw = Math.toRadians(e.getLocation().getYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    static void face(LivingEntity mob, Vector dir) {
        Location l = mob.getLocation();
        l.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
        mob.setRotation(l.getYaw(), 0);
    }

    /** Ground height to stand on at a spot (within 2 blocks up/down of where it is), or MIN_VALUE if there's none. */
    static int groundY(Location at, int y) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int dy = 2; dy >= -3; dy--) {
            Block feet = w.getBlockAt(x, y + dy, z), below = feet.getRelative(0, -1, 0);
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && below.getType().isSolid()) return y + dy;
        }
        return Integer.MIN_VALUE;
    }

    void line(Location from, Vector dir, double len, Color col) {
        if (dir == null) return;
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(1.1);
        for (double d = 1; d <= len; d += 1) {
            Location c = from.clone().add(dir.clone().multiply(d)).add(0, 0.15, 0);
            from.getWorld().spawnParticle(Particle.DUST, c.clone().add(side), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.6f));
            from.getWorld().spawnParticle(Particle.DUST, c.clone().subtract(side), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.6f));
        }
    }

    void cone(Location from, Vector dir, double len, double halfDeg, Color col) {
        for (double r = 1.5; r <= len; r += 1.2) for (double a = -halfDeg; a <= halfDeg; a += 12) {
            Vector v = dir.clone().rotateAroundY(Math.toRadians(a)).multiply(r);
            from.getWorld().spawnParticle(Particle.DUST, from.clone().add(v).add(0, 0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f));
        }
    }
}
