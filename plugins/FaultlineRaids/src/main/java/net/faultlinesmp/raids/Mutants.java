package net.faultlinesmp.raids;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * THE 25 NEW MUTANTS (Raids 1.3.0, config elites.mutants:). They come out of the same rolls as the Looter Zombie,
 * Blazing Skeleton and Splitting Creeper (elites.mutation-chance 2% of natural spawns, raid-mutation-chance 5% in raid
 * waves): a mob picks one of the mutations its kind can have. Natural hostile spawns of any kind count for mutations
 * (phantoms, ghasts and hoglins too; never slimes or magma cubes) and pillager patrols too. Mutants give no bonus loot
 * (only Elites do), so a farm gets nothing out of them; a player kill counts the Index stat "mutants".
 * Minions they make (Gravecaller babies, Broodmother spiders, a Twin's copy) are tagged faultline_mutant_minion: no
 * drops, 1 XP, never mutate or turn Elite. No entity scans: mutants that need a timer are kept in a set (re-found when
 * their chunk loads).
 */
final class Mutants implements Listener {

    static final String MINION = "faultline_mutant_minion";

    /** One mutation: id (for /zraid mutant), scoreboard tag, name prefix + colour, who can have it, the mob /zraid spawns. */
    record Kind(String id, String tag, String prefix, ChatColor color, Predicate<LivingEntity> fits, EntityType sample) { }

    static boolean zombie(LivingEntity m) { return m instanceof Zombie && !(m instanceof PigZombie); }
    static boolean skeleton(LivingEntity m) { return m.getType() == EntityType.SKELETON || m.getType() == EntityType.STRAY || m.getType() == EntityType.BOGGED; }
    static boolean spider(LivingEntity m) { return m.getType() == EntityType.SPIDER || m.getType() == EntityType.CAVE_SPIDER; }
    static boolean is(LivingEntity m, EntityType... t) { for (EntityType x : t) if (m.getType() == x) return true; return false; }
    static boolean melee(LivingEntity m) {
        return zombie(m) || spider(m) || is(m, EntityType.WITHER_SKELETON, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.PIGLIN_BRUTE,
                EntityType.VINDICATOR, EntityType.PIGLIN);
    }

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static void add(String id, String tag, String prefix, ChatColor c, Predicate<LivingEntity> fits, EntityType sample) {
        KINDS.put(id, new Kind(id, tag, prefix, c, fits, sample));
    }
    static {
        // the three from 1.2.0 (their powers live in Elites)
        add("looter", Elites.LOOTER, "Looter", ChatColor.DARK_GREEN, Mutants::zombie, EntityType.ZOMBIE);
        add("blazing", Elites.BLAZING, "Blazing", ChatColor.GOLD, Mutants::skeleton, EntityType.SKELETON);
        add("splitter", Elites.SPLITTER, "Splitting", ChatColor.GREEN, m -> m instanceof Creeper, EntityType.CREEPER);
        // zombies (zombie, husk, drowned, zombie villager)
        add("plague", "faultline_plague", "Plague", ChatColor.DARK_GREEN, Mutants::zombie, EntityType.ZOMBIE);
        add("brute", "faultline_brute", "Brute", ChatColor.DARK_RED, Mutants::zombie, EntityType.ZOMBIE);
        add("bloater", "faultline_bloater", "Bloated", ChatColor.GREEN, Mutants::zombie, EntityType.ZOMBIE);
        add("gravecaller", "faultline_gravecaller", "Gravecaller", ChatColor.DARK_PURPLE, Mutants::zombie, EntityType.ZOMBIE);
        add("twin", "faultline_twin_zombie", "Twin", ChatColor.AQUA, Mutants::zombie, EntityType.ZOMBIE);
        add("troll", "faultline_troll", "Troll", ChatColor.DARK_GREEN, Mutants::zombie, EntityType.ZOMBIE);
        add("dustdevil", "faultline_dustdevil", "Dust Devil", ChatColor.YELLOW, m -> is(m, EntityType.HUSK), EntityType.HUSK);
        add("abyssal", "faultline_abyssal", "Abyssal", ChatColor.DARK_AQUA, m -> is(m, EntityType.DROWNED), EntityType.DROWNED);
        // skeletons (skeleton, stray, bogged), pillagers
        add("sniper", "faultline_sniper", "Sniper", ChatColor.GRAY, Mutants::skeleton, EntityType.SKELETON);
        add("volley", "faultline_volley", "Volley", ChatColor.WHITE, Mutants::skeleton, EntityType.SKELETON);
        add("frost", "faultline_frost_archer", "Frost", ChatColor.AQUA, Mutants::skeleton, EntityType.SKELETON);
        add("hexbolt", "faultline_hexbolt", "Hexbolt", ChatColor.LIGHT_PURPLE, m -> skeleton(m) || is(m, EntityType.PILLAGER), EntityType.PILLAGER);
        add("phasing", "faultline_phasing", "Phasing", ChatColor.DARK_PURPLE, Mutants::skeleton, EntityType.SKELETON);
        add("cursed", "faultline_cursed", "Cursed", ChatColor.DARK_GRAY, m -> is(m, EntityType.WITHER_SKELETON), EntityType.WITHER_SKELETON);
        // creepers
        add("storm", "faultline_storm", "Storm", ChatColor.BLUE, m -> m instanceof Creeper, EntityType.CREEPER);
        add("magnetic", "faultline_magnetic", "Magnetic", ChatColor.RED, m -> m instanceof Creeper, EntityType.CREEPER);
        add("stealth", "faultline_stealth", "Stealth", ChatColor.DARK_GRAY, m -> m instanceof Creeper, EntityType.CREEPER);
        add("ender", "faultline_ender_creeper", "Ender", ChatColor.DARK_PURPLE, m -> m instanceof Creeper, EntityType.CREEPER);
        // spiders
        add("weaver", "faultline_weaver", "Weaver", ChatColor.WHITE, Mutants::spider, EntityType.SPIDER);
        add("leaper", "faultline_leaper", "Leaping", ChatColor.RED, Mutants::spider, EntityType.SPIDER);
        add("broodmother", "faultline_broodmother", "Broodmother", ChatColor.DARK_RED, m -> is(m, EntityType.SPIDER), EntityType.SPIDER);
        // the Nether, the sky
        add("inferno", "faultline_inferno", "Inferno", ChatColor.GOLD, m -> is(m, EntityType.BLAZE, EntityType.GHAST), EntityType.BLAZE);
        add("shrieker", "faultline_shrieker", "Shrieking", ChatColor.DARK_BLUE, m -> is(m, EntityType.PHANTOM), EntityType.PHANTOM);
        add("rampager", "faultline_rampager", "Rampaging", ChatColor.RED, m -> is(m, EntityType.HOGLIN, EntityType.ZOGLIN), EntityType.HOGLIN);
        add("vampiric", "faultline_vampiric", "Vampiric", ChatColor.DARK_RED, Mutants::melee, EntityType.ZOMBIE);
    }
    /** Mutants that need the timer. */
    static final Set<String> TICKED = Set.of("faultline_gravecaller", "faultline_magnetic", "faultline_stealth", "faultline_ender_creeper",
            "faultline_leaper", "faultline_rampager");
    /** Projectiles carry these from their shooter. */
    static final Set<String> SHOT_TAGS = Set.of("faultline_frost_archer", "faultline_hexbolt");

    private final FaultlineRaids plugin;
    private final Set<UUID> ticking = new HashSet<>();
    private final Map<UUID, Long> ready = new HashMap<>();
    private final Map<UUID, List<UUID>> babies = new HashMap<>();
    private final Map<Block, Long> webs = new HashMap<>();
    private boolean launching;
    long ticks;

    Mutants(FaultlineRaids plugin) { this.plugin = plugin; }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("elites.mutants." + path, def); }

    boolean enabled(Kind k) {
        return !plugin.getConfig().getStringList("elites.disabled-mutants").contains(k.id());
    }

    /** Every mutation this mob can have (enabled ones). */
    List<Kind> candidates(LivingEntity m) {
        List<Kind> out = new ArrayList<>();
        for (Kind k : KINDS.values()) if (enabled(k) && k.fits().test(m)) out.add(k);
        return out;
    }

    static Kind kindOf(LivingEntity m) {
        for (Kind k : KINDS.values()) if (m.getScoreboardTags().contains(k.tag())) return k;
        return null;
    }

    /** Sets up one of the 25 (the 1.2.0 three are set up by Elites). */
    void apply(LivingEntity m, Kind k) {
        m.setCustomName(k.color() + k.prefix() + " " + Elites.nice(m));
        m.setCustomNameVisible(true);
        switch (k.id()) {
            case "brute" -> {
                scale(m, 1.4);
                base(m, Attribute.KNOCKBACK_RESISTANCE, 1.0);
                heal(m, Attribute.MAX_HEALTH, 1.5);
            }
            case "bloater" -> {
                scale(m, 1.25);
                mult(m, Attribute.MOVEMENT_SPEED, 0.8);
            }
            case "troll" -> {
                heal(m, Attribute.MAX_HEALTH, 1.5);
                m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 1, false, false));
            }
            case "sniper" -> {
                AttributeInstance fr = m.getAttribute(Attribute.FOLLOW_RANGE);
                if (fr != null) fr.setBaseValue(Math.max(fr.getBaseValue(), 40));
            }
            case "cursed" -> m.getWorld().spawnParticle(Particle.SMOKE, m.getLocation().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
            case "storm" -> ((Creeper) m).setPowered(true);
            case "stealth" -> {
                m.setCustomNameVisible(false);
                m.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
            case "shrieker" -> m.setGlowing(true);
            case "rampager" -> {
                m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
                base(m, Attribute.KNOCKBACK_RESISTANCE, 0.6);
            }
            case "broodmother" -> {
                scale(m, 1.3);
                heal(m, Attribute.MAX_HEALTH, 1.5);
            }
            default -> { }
        }
        if (TICKED.contains(k.tag())) ticking.add(m.getUniqueId());
    }

    private static void scale(LivingEntity m, double s) {
        try { AttributeInstance a = m.getAttribute(Attribute.SCALE); if (a != null) a.setBaseValue(a.getBaseValue() * s); } catch (RuntimeException | LinkageError ignored) { }
    }
    private static void base(LivingEntity m, Attribute at, double v) {
        try { AttributeInstance a = m.getAttribute(at); if (a != null) a.setBaseValue(v); } catch (RuntimeException | LinkageError ignored) { }
    }
    private static void mult(LivingEntity m, Attribute at, double f) {
        try { AttributeInstance a = m.getAttribute(at); if (a != null) a.setBaseValue(a.getBaseValue() * f); } catch (RuntimeException | LinkageError ignored) { }
    }
    private static void heal(LivingEntity m, Attribute at, double f) {
        AttributeInstance a = m.getAttribute(at);
        if (a == null) return;
        a.setBaseValue(a.getBaseValue() * f);
        m.setHealth(a.getValue());
    }

    static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    private boolean cooled(UUID id, long cooldownTicks) {
        Long t = ready.get(id);
        if (t != null && t > ticks) return false;
        ready.put(id, ticks + cooldownTicks);
        return true;
    }

    // ---------------------------------------------------------------- hits

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        // a phasing skeleton blinks away from players
        if (e.getEntity() instanceof LivingEntity v && v.getScoreboardTags().contains("faultline_phasing") && player(d) != null
                && plugin.random.nextDouble() < cfg("phasing.chance", 0.4) && cooled(v.getUniqueId(), 60)) {
            Location to = safeNear(v.getLocation(), 4, 8);
            if (to != null) {
                v.getWorld().spawnParticle(Particle.PORTAL, v.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.5);
                v.teleport(to);
                v.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.3f);
            }
        }
        if (!(e.getEntity() instanceof Player p) || !survival(p)) return;
        if (d instanceof Projectile pr) {
            Set<String> t = pr.getScoreboardTags();
            if (t.contains("faultline_frost_archer")) {
                p.setFreezeTicks(Math.max(p.getFreezeTicks(), 140));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
            }
            if (t.contains("faultline_hexbolt")) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
                p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
            }
            if (pr.getShooter() instanceof LivingEntity s && s.getScoreboardTags().contains("faultline_inferno")) p.setFireTicks(Math.max(p.getFireTicks(), 100));
            return;
        }
        if (!(d instanceof LivingEntity m)) return;
        Set<String> t = m.getScoreboardTags();
        double dmg = e.getFinalDamage();
        if (t.contains("faultline_plague")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 160, 1));
        }
        if (t.contains("faultline_brute")) {
            Vector away = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) away = m.getLocation().getDirection().setY(0);
            Safe.vel(p, away.normalize().multiply(cfg("brute.launch", 1.2)).setY(0.55));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f);
        }
        if (t.contains("faultline_dustdevil")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
            p.getWorld().spawnParticle(Particle.BLOCK, p.getEyeLocation(), 25, 0.4, 0.4, 0.4, 0, Material.SAND.createBlockData());
        }
        if (t.contains("faultline_abyssal")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 160, 1));
            if (p.isInWater()) Safe.vel(p, p.getVelocity().setY(-0.8));
        }
        if (t.contains("faultline_cursed")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 1));
            heal(m, dmg);
        }
        if (t.contains("faultline_vampiric")) {
            heal(m, dmg * cfg("vampiric.heal", 0.5));
            m.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, m.getLocation().add(0, 1.2, 0), 4, 0.2, 0.3, 0.2, 0);
        }
        if (t.contains("faultline_shrieker")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_HURT, 1f, 0.5f);
        }
        if (t.contains("faultline_weaver")) web(p.getLocation().getBlock());
    }

    private static Player player(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private static void heal(LivingEntity m, double amount) {
        AttributeInstance mh = m.getAttribute(Attribute.MAX_HEALTH);
        if (mh == null || amount <= 0 || m.isDead()) return;
        m.setHealth(Math.min(mh.getValue(), m.getHealth() + amount));
    }

    /** A cobweb at the player's feet for a few seconds (only into air; removed again). */
    void web(Block b) {
        if (b.getType() != Material.AIR) return;
        b.setType(Material.COBWEB, false);
        webs.put(b, ticks + (long) cfg("weaver.web-ticks", 100));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity m)) return;
        Set<String> t = m.getScoreboardTags();
        // a troll stops healing for 5 s when it burns
        if (t.contains("faultline_troll")) {
            EntityDamageEvent.DamageCause c = e.getCause();
            if (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK || c == EntityDamageEvent.DamageCause.LAVA) {
                m.removePotionEffect(PotionEffectType.REGENERATION);
                ready.put(m.getUniqueId(), ticks + 100);
                ticking.add(m.getUniqueId());
            }
        }
        // a twin splits in two the first time it drops below half health
        if (t.contains("faultline_twin_zombie") && !t.contains(MINION) && !t.contains("faultline_twin_split")) {
            AttributeInstance mh = m.getAttribute(Attribute.MAX_HEALTH);
            double after = m.getHealth() - e.getFinalDamage();
            if (mh != null && after > 0 && after < mh.getValue() / 2) {
                m.addScoreboardTag("faultline_twin_split"); // splits once
                Location at = m.getLocation();
                if (at.getWorld().spawnEntity(at.clone().add(0.5, 0, 0.5), m.getType(), false) instanceof LivingEntity copy) {
                    copy.addScoreboardTag(MINION);
                    copy.addScoreboardTag("faultline_twin_zombie");
                    copy.setCustomName(m.getCustomName());
                    copy.setCustomNameVisible(true);
                    copy.setHealth(Math.min(copy.getHealth(), Math.max(1, after)));
                    if (m instanceof Mob a && copy instanceof Mob b) b.setTarget(a.getTarget());
                    at.getWorld().spawnParticle(Particle.CLOUD, at.add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
                    at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_INFECT, 1f, 1.4f);
                }
            }
        }
    }

    // ---------------------------------------------------------------- shots

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (launching || !(e.getProjectile() instanceof AbstractArrow a)) return;
        LivingEntity s = e.getEntity();
        Set<String> t = s.getScoreboardTags();
        for (String st : SHOT_TAGS) if (t.contains(st)) a.addScoreboardTag(st);
        if (t.contains("faultline_sniper")) {
            Safe.vel(a, a.getVelocity().multiply(cfg("sniper.speed", 1.6)));
            a.setDamage(a.getDamage() * cfg("sniper.damage", 1.5));
        }
        if (t.contains("faultline_volley")) {
            launching = true;
            try {
                for (int side = -1; side <= 1; side += 2) {
                    Vector v = a.getVelocity().clone().rotateAroundY(Math.toRadians(8 * side));
                    Arrow extra = s.launchProjectile(Arrow.class, v);
                    extra.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    extra.setDamage(a.getDamage());
                    for (String st : SHOT_TAGS) if (t.contains(st)) extra.addScoreboardTag(st);
                }
            } catch (RuntimeException ignored) {
            } finally { launching = false; }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (launching || !(e.getEntity() instanceof Fireball f) || !(f.getShooter() instanceof LivingEntity s)
                || !s.getScoreboardTags().contains("faultline_inferno")) return;
        Class<? extends Fireball> cls = f instanceof SmallFireball ? SmallFireball.class : f instanceof LargeFireball ? LargeFireball.class : null;
        if (cls == null) return;
        launching = true;
        try {
            Vector dir = f.getDirection();
            if (dir.lengthSquared() < 1e-6) dir = f.getVelocity();
            for (int side = -1; side <= 1; side += 2) {
                Fireball extra = s.launchProjectile(cls);
                extra.teleport(f.getLocation());
                extra.setDirection(dir.clone().rotateAroundY(Math.toRadians(12 * side)));
                if (extra instanceof LargeFireball lf && f instanceof LargeFireball of) lf.setYield(of.getYield());
            }
        } catch (RuntimeException ignored) {
        } finally { launching = false; }
    }

    // ---------------------------------------------------------------- explosions and deaths

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() instanceof Creeper c && c.getScoreboardTags().contains("faultline_storm")) {
            try { c.getWorld().strikeLightningEffect(c.getLocation()); } catch (RuntimeException ignored) { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        Set<String> t = m.getScoreboardTags();
        ticking.remove(m.getUniqueId());
        ready.remove(m.getUniqueId());
        Location at = m.getLocation();
        if (t.contains(MINION)) { e.getDrops().clear(); e.setDroppedExp(1); }
        if (t.contains("faultline_plague")) cloud(at);
        if (t.contains("faultline_bloater")) burst(m, at);
        if (t.contains("faultline_broodmother")) {
            int n = 3 + plugin.random.nextInt(2);
            for (int i = 0; i < n; i++) minion(at.clone().add(plugin.random.nextDouble() - 0.5, 0.2, plugin.random.nextDouble() - 0.5), EntityType.CAVE_SPIDER, ChatColor.DARK_RED + "Brood Spider");
            at.getWorld().playSound(at, Sound.ENTITY_SPIDER_DEATH, 1f, 0.6f);
        }
        Player killer = m.getKiller();
        if (killer != null && kindOf(m) != null && !t.contains(MINION) && Bukkit.getPluginCommand("index") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + killer.getName() + " mutants 1");
    }

    private void cloud(Location at) {
        try {
            if (!(at.getWorld().spawnEntity(at, EntityType.AREA_EFFECT_CLOUD) instanceof AreaEffectCloud c)) return;
            c.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 100, 0), true);
            c.setRadius(3f);
            c.setDuration(100);
            c.setRadiusPerTick(-0.01f);
            c.setColor(org.bukkit.Color.fromRGB(90, 140, 40));
        } catch (RuntimeException ignored) { }
    }

    /** A bloater's burst: hurts and throws back players near it (no blocks broken, no fire). */
    void burst(LivingEntity m, Location at) {
        double r = cfg("bloater.radius", 4), max = cfg("bloater.damage", 8);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 1, 0), 40, 0.8, 0.6, 0.8, 0, Material.SLIME_BLOCK.createBlockData());
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
        for (Entity n : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (!(n instanceof Player p) || !survival(p)) continue;
            double dist = p.getLocation().distance(at);
            if (dist > r) continue;
            p.damage(max * (1 - dist / r) + 1, m);
            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) away = new Vector(0, 0, 0); else away.normalize();
            Safe.vel(p, away.multiply(0.9).setY(0.45));
        }
    }

    LivingEntity minion(Location at, EntityType type, String name) {
        if (!(at.getWorld().spawnEntity(at, type, false) instanceof LivingEntity m)) return null;
        m.addScoreboardTag(MINION);
        m.setCustomName(name);
        return m;
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity n : e.getEntities()) {
            if (!(n instanceof LivingEntity)) continue;
            for (String t : n.getScoreboardTags()) if (TICKED.contains(t) || t.equals("faultline_troll")) { ticking.add(n.getUniqueId()); break; }
        }
    }

    // ---------------------------------------------------------------- the timer (every 10 ticks)

    void tick() {
        ticks += 10;
        webs.entrySet().removeIf(w -> {
            if (w.getValue() > ticks) return false;
            if (w.getKey().getType() == Material.COBWEB) w.getKey().setType(Material.AIR, false);
            return true;
        });
        for (UUID id : new ArrayList<>(ticking)) {
            Entity n = Bukkit.getEntity(id);
            if (!(n instanceof LivingEntity m) || !m.isValid()) { if (n == null || n.isDead()) { ticking.remove(id); ready.remove(id); babies.remove(id); } continue; }
            Set<String> t = m.getScoreboardTags();
            Player target = m instanceof Mob mob && mob.getTarget() instanceof Player p && survival(p) && p.getWorld() == m.getWorld() ? p : null;
            double dist = target == null ? 1e9 : target.getLocation().distance(m.getLocation());
            if (t.contains("faultline_troll")) {
                Long r = ready.get(id);
                if (r == null || r <= ticks) {
                    m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 1, false, false));
                    ticking.remove(id);
                }
            }
            if (t.contains("faultline_gravecaller") && target != null && dist < 16) {
                List<UUID> mine = babies.computeIfAbsent(id, k -> new ArrayList<>());
                mine.removeIf(b -> { Entity x = Bukkit.getEntity(b); return x == null || x.isDead(); });
                if (mine.size() < (int) cfg("gravecaller.max", 4) && cooled(id, (long) cfg("gravecaller.every-ticks", 200))) {
                    for (int i = 0; i < 2 && mine.size() < (int) cfg("gravecaller.max", 4); i++) {
                        Location l = safeNear(m.getLocation(), 1, 3);
                        if (l == null) l = m.getLocation();
                        LivingEntity b = minion(l, EntityType.ZOMBIE, ChatColor.DARK_PURPLE + "Risen");
                        if (b instanceof Zombie z) { z.setBaby(); if (z instanceof Mob bm) bm.setTarget(target); }
                        if (b != null) mine.add(b.getUniqueId());
                        l.getWorld().spawnParticle(Particle.SOUL, l, 10, 0.3, 0.2, 0.3, 0.02);
                    }
                    m.getWorld().playSound(m.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.7f);
                }
            }
            if (t.contains("faultline_magnetic")) {
                double r = cfg("magnetic.radius", 7);
                for (Entity x : m.getNearbyEntities(r, r, r)) {
                    if (!(x instanceof Player p) || !survival(p) || p.isFlying()) continue;
                    Vector pull = m.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (pull.lengthSquared() < 1) continue;
                    Safe.vel(p, p.getVelocity().add(pull.normalize().multiply(cfg("magnetic.pull", 0.18))));
                }
                if (ticks % 40 == 0) m.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, m.getLocation().add(0, 1, 0), 8, 0.4, 0.5, 0.4, 0.05);
            }
            if (t.contains("faultline_stealth")) {
                boolean near = false;
                for (Entity x : m.getNearbyEntities(5, 5, 5)) if (x instanceof Player p && survival(p)) { near = true; break; }
                if (near) m.removePotionEffect(PotionEffectType.INVISIBILITY);
                else if (!m.hasPotionEffect(PotionEffectType.INVISIBILITY))
                    m.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
            if (t.contains("faultline_ender_creeper") && target != null && dist > 7 && dist < 24 && cooled(id, 80)) {
                Location to = safeNear(target.getLocation(), 1, 3);
                if (to != null) {
                    m.getWorld().spawnParticle(Particle.PORTAL, m.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.5);
                    m.teleport(to);
                    m.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
                }
            }
            if (t.contains("faultline_leaper") && target != null && dist > 4 && dist < 14 && (m.isOnGround() || m.getLocation().subtract(0, 0.1, 0).getBlock().getType().isSolid()) && cooled(id, 60)) {
                Vector v = target.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize().multiply(Math.min(1.3, dist * 0.11));
                Safe.vel(m, v.setY(0.45));
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 1.5f);
            }
            if (t.contains("faultline_rampager") && target != null && dist > 5 && dist < 16 && cooled(id, 100)) {
                Vector v = target.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize().multiply(cfg("rampager.charge", 1.4));
                Safe.vel(m, v.setY(0.2));
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, 1.2f, 0.8f);
            }
        }
    }

    /** A spot min-max blocks from c with a floor and room to stand, or null. */
    Location safeNear(Location c, double min, double max) {
        for (int i = 0; i < 14; i++) {
            double a = plugin.random.nextDouble() * Math.PI * 2, d = min + plugin.random.nextDouble() * (max - min);
            Location l = c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy = 2; dy >= -3; dy--) {
                Block feet = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() + dy, l.getBlockZ());
                if (open(feet) && open(feet.getRelative(0, 1, 0)) && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    Location to = feet.getLocation().add(0.5, 0, 0.5);
                    to.setYaw(c.getYaw());
                    return to;
                }
            }
        }
        return null;
    }

    /** Room to stand: not solid, not liquid. */
    static boolean open(Block b) {
        Material t = b.getType();
        return !t.isSolid() && t != Material.WATER && t != Material.LAVA && t != Material.COBWEB && t != Material.POWDER_SNOW;
    }

    void shutdown() {
        for (Block b : webs.keySet()) if (b.getType() == Material.COBWEB) b.setType(Material.AIR, false);
        webs.clear();
    }

    int webCount() { return webs.size(); }
    boolean isTicking(UUID id) { return ticking.contains(id); }
}
