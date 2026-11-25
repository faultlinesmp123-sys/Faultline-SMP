package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE NEST'S LIFE (Items 1.3.47). The Queen's BROOD: spiders on the Queen's own rig (QueenRig, planted feet and all):
 * SERVANTS (her model, small: poison + slow bites), VENOM spiders (green: Poison II, a poison cloud when they die), EMBER
 * spiders (orange: set you alight, fireproof, a burst of flame when they die), SWIFT spiders (blue: fast, they pounce). Tag
 * `faultline_nest_brood` + `faultline_nest_<kind>`; non-persistent; Bedrock players see a plain stand-in spider (their hits pass on).
 * BROOD DENS (the nest's trial chambers): stepping near a den's egg sac wakes it: 3+ waves of spiders, cave spiders, zombies,
 * skeletons and brood round the dais; clear them = every fighter gets loot, then the den sleeps `den-cooldown-minutes` 30.
 * SLEEPING DENS: brood asleep on their silk beds (curled up, AI off) wake when you come within 4 blocks or hit one.
 * Also: what spawns round players inside, QUAKES (a rumble, dust, and dripstone dropping from the ceiling onto players),
 * the DARK (hidden Darkness while inside) and the nest's MUSIC (`faultline:nest.music`, in the music pack).
 */
final class NestBrood implements Listener {

    static final String TAG = "faultline_nest_brood";
    static final String STAND_TAG = "faultline_nest_brood_standin";
    static final String DEN_TAG = "faultline_nest_den_mob";
    static final String DRIP_TAG = "faultline_nest_drip";
    static final String MUSIC = "faultline:nest.music";

    enum Kind {
        SERVANT("Queen's Servant", "queen/", 1.55, 0.95, -1, 32, 5, 0.27, ChatColor.DARK_PURPLE),
        VENOM("Venom Spider", "nestspider/venom/", 1.25, 0.8, 11, 24, 4, 0.3, ChatColor.GREEN),
        EMBER("Ember Spider", "nestspider/ember/", 1.25, 0.8, 11, 24, 5, 0.3, ChatColor.GOLD),
        SWIFT("Swift Spider", "nestspider/swift/", 1.1, 0.7, 11, 18, 3, 0.46, ChatColor.AQUA);
        final String name, models;
        final double rig, hitbox, health, damage, speed;
        final int glow;
        final ChatColor color;
        Kind(String name, String models, double rig, double hitbox, int glow, double health, double damage, double speed, ChatColor color) {
            this.name = name; this.models = models; this.rig = rig; this.hitbox = hitbox; this.glow = glow;
            this.health = health; this.damage = damage; this.speed = speed; this.color = color;
        }
        String id() { return name().toLowerCase(); }
    }

    final class Brood {
        Spider body, standin;
        HitBox box; // 1.3.49
        QueenRig rig;
        Kind kind;
        boolean asleep;
        String nestId;
        long t, nextLeap;
    }

    /** A brood den that's awake: its waves. */
    private static final class Run {
        String nestId; int den; Location at;
        int wave, waves;
        final Set<UUID> mobs = new HashSet<>();
        final Set<UUID> fighters = new HashSet<>();
        long lonelySince = -1;
        int pause;
    }

    final FaultlineItems plugin;
    final SpiderNest nests;
    final Map<UUID, Brood> brood = new HashMap<>();
    private final Map<UUID, Brood> byStandin = new HashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<String, Long> bedSpawned = new HashMap<>();
    private final Map<String, UUID> bedSleeper = new HashMap<>(); // a bed's sleeper (no second one while it lives)
    private final Map<UUID, Long> musicAt = new HashMap<>();
    private final Map<String, Long> nextQuake = new HashMap<>();
    private final Random random = new Random();
    private long ticks;

    NestBrood(FaultlineItems plugin, SpiderNest nests) {
        this.plugin = plugin;
        this.nests = nests;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[NestBrood] tick: " + e); }
        }, 1L, 1L);
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("spider-nest." + k, d); }

    static boolean survival(Player p) { return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE); }

    // ============================================================================================ the brood
    Brood spawn(Kind kind, Location at, boolean asleep, String nestId) {
        World w = at.getWorld();
        if (!(w.spawnEntity(at, EntityType.SPIDER, false) instanceof Spider sp) || !sp.isValid()) return null;
        Brood b = new Brood();
        b.body = sp; b.kind = kind; b.asleep = asleep; b.nestId = nestId;
        sp.addScoreboardTag(TAG);
        sp.addScoreboardTag("faultline_nest_" + kind.id());
        sp.setPersistent(false);
        sp.setRemoveWhenFarAway(true);
        sp.setInvisible(true);
        sp.setCustomName(kind.color + kind.name);
        sp.setCustomNameVisible(false);
        attr(sp, Attribute.SCALE, kind.hitbox);
        attr(sp, Attribute.MAX_HEALTH, kind.health);
        sp.setHealth(kind.health);
        attr(sp, Attribute.ATTACK_DAMAGE, kind.damage);
        attr(sp, Attribute.MOVEMENT_SPEED, kind.speed);
        attr(sp, Attribute.FOLLOW_RANGE, 28);
        if (kind == Kind.EMBER) sp.setVisualFire(false);
        if (asleep) sp.setAI(false);
        b.rig = new QueenRig(plugin, sp, kind.rig, kind.models, kind.glow);
        b.box = new HitBox(sp, 1.4 * kind.hitbox, 0.9 * kind.hitbox);
        b.rig.play(asleep ? "sleep" : "wake", asleep ? 0 : 20);
        try { // Bedrock: a plain spider only they see
            if (w.spawnEntity(at, EntityType.SPIDER, false) instanceof Spider s2) {
                s2.addScoreboardTag(STAND_TAG);
                s2.setPersistent(false); s2.setAI(false); s2.setSilent(true); s2.setCollidable(false);
                s2.setVisibleByDefault(false);
                attr(s2, Attribute.SCALE, kind.hitbox);
                b.standin = s2;
                byStandin.put(s2.getUniqueId(), b);
                for (Player p : w.getPlayers()) if (Edition.bedrock(p)) p.showEntity(plugin, s2);
            }
        } catch (RuntimeException ignored) { }
        brood.put(sp.getUniqueId(), b);
        return b;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    void remove(Brood b) {
        brood.remove(b.body.getUniqueId());
        if (b.box != null) b.box.remove();
        if (b.rig != null) b.rig.remove();
        if (b.body.isValid()) b.body.remove();
        if (b.standin != null) { byStandin.remove(b.standin.getUniqueId()); if (b.standin.isValid()) b.standin.remove(); }
    }

    /** 1.3.50: every hostile mob inside a nest removed (`/queenspider killall`). */
    int clearNests() {
        int n = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) for (LivingEntity e : w.getLivingEntities())
            if (e instanceof org.bukkit.entity.Enemy && !(e instanceof Player) && nests.nestAt(e.getLocation()) != null
                    && !e.getScoreboardTags().contains("faultline_queen_spider")) { e.remove(); n++; }
        return n;
    }

    void liveIds(Set<UUID> live) { // 1.3.51: for QueenSpiderManager.sweepOrphans
        for (Brood b : brood.values()) {
            live.add(b.body.getUniqueId());
            if (b.standin != null) live.add(b.standin.getUniqueId());
            if (b.box != null && b.box.box != null) live.add(b.box.box.getUniqueId());
            if (b.rig != null) for (Entity e : b.rig.parts) live.add(e.getUniqueId());
        }
    }

    void removeAll() {
        for (Brood b : new ArrayList<>(brood.values())) remove(b);
        for (Run r : runs.values()) for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        runs.clear();
    }

    void removeNest(String id) {
        for (Brood b : new ArrayList<>(brood.values())) if (id.equals(b.nestId)) remove(b);
        runs.values().removeIf(r -> {
            if (!r.nestId.equals(id)) return false;
            for (UUID u : r.mobs) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
            return true;
        });
    }

    private void tick() {
        ticks++;
        for (Brood b : new ArrayList<>(brood.values())) {
            try { tickOne(b); } catch (RuntimeException e) { plugin.getLogger().warning("[NestBrood] " + b.kind + ": " + e); }
        }
        if (ticks % 10 == 0) dens();
    }

    private void tickOne(Brood b) {
        {
            b.t++;
            if (b.rig != null) b.rig.tick();
            if (b.box != null) b.box.tick(b.t);
            if (!b.body.isValid() || b.body.isDead()) {
                if (b.rig == null || b.rig.gone) {
                    brood.remove(b.body.getUniqueId());
                    if (b.standin != null) { byStandin.remove(b.standin.getUniqueId()); if (b.standin.isValid()) b.standin.remove(); }
                }
                return;
            }
            if (b.standin != null && b.standin.isValid() && b.t % 2 == 0) b.standin.teleport(b.body.getLocation());
            if (b.asleep) {
                if (b.t % 10 == 0) for (Player p : b.body.getWorld().getPlayers())
                    if (survival(p) && p.getLocation().distanceSquared(b.body.getLocation()) < 16 && !p.isSneaking()) { wake(b, p); break; }
                return;
            }
            Location l = b.body.getLocation();
            LivingEntity tg = b.body.getTarget();
            switch (b.kind) {
                case EMBER -> { if (b.t % 4 == 0) l.getWorld().spawnParticle(Particle.SMALL_FLAME, l.clone().add(0, 0.4, 0), 2, 0.3, 0.1, 0.3, 0.005); b.body.setFireTicks(0); }
                case VENOM -> { if (b.t % 8 == 0) l.getWorld().spawnParticle(Particle.DUST, l.clone().add(0, 0.5, 0), 2, 0.3, 0.1, 0.3, 0, new Particle.DustOptions(Color.fromRGB(90, 230, 60), 0.8f)); }
                case SWIFT -> { // pounces
                    if (tg != null && b.t >= b.nextLeap && b.body.isOnGround() && tg.getWorld() == l.getWorld()) {
                        double d = tg.getLocation().distance(l);
                        if (d > 3 && d < 9) {
                            Vector v = tg.getLocation().toVector().subtract(l.toVector()).setY(0).normalize().multiply(0.75).setY(0.42);
                            Safe.vel(b.body, v);
                            l.getWorld().playSound(l, Sound.ENTITY_SPIDER_AMBIENT, 0.8f, 1.6f);
                            b.rig.play("crouch", 6);
                            b.nextLeap = b.t + 50 + random.nextInt(40);
                        }
                    }
                }
                default -> { }
            }
            if (tg == null && b.t % 20 == 0) { // hunt whoever's near
                for (Player p : l.getWorld().getPlayers())
                    if (survival(p) && p.getLocation().distanceSquared(l) < 18 * 18 && b.body.hasLineOfSight(p)) { b.body.setTarget(p); break; }
            }
        }
    }

    void wake(Brood b, Player by) {
        if (!b.asleep) return;
        b.asleep = false;
        b.body.setAI(true);
        b.rig.play("wake", 24);
        b.body.getWorld().playSound(b.body.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.6f);
        if (by != null) b.body.setTarget(by);
        for (Brood o : brood.values()) // its bedmates wake too
            if (o.asleep && o.body.getWorld() == b.body.getWorld() && o.body.getLocation().distanceSquared(b.body.getLocation()) < 64) wake(o, by);
    }

    Brood of(Entity e) {
        if (e == null) return null;
        Brood b = brood.get(e.getUniqueId());
        return b != null ? b : byStandin.get(e.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Brood b = of(e.getEntity());
        if (b == null) return;
        if (e.getEntity() == b.standin) { // Bedrock hits the stand-in
            e.setCancelled(true);
            if (e instanceof EntityDamageByEntityEvent ev && b.body.isValid()) b.body.damage(e.getFinalDamage(), ev.getDamager());
            return;
        }
        if (b.kind == Kind.EMBER && (e.getCause() == EntityDamageEvent.DamageCause.FIRE || e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || e.getCause() == EntityDamageEvent.DamageCause.LAVA || e.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR)) { e.setCancelled(true); return; }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) { e.setCancelled(true); return; }
        if (b.asleep) wake(b, e instanceof EntityDamageByEntityEvent ev && ev.getDamager() instanceof Player p ? p : null);
        if (b.rig != null) b.rig.hurt(e.getDamage());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        Brood b = of(e.getEntity());
        if (b != null && b.kind == Kind.EMBER) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent e) {
        Brood b = brood.get(e.getDamager().getUniqueId());
        if (b == null || !(e.getEntity() instanceof LivingEntity v)) return;
        if (b.rig != null) b.rig.play("bite", 10);
        switch (b.kind) {
            case SERVANT -> { v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0)); v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0)); }
            case VENOM -> v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 120, 1));
            case EMBER -> v.setFireTicks(Math.max(v.getFireTicks(), 100));
            case SWIFT -> v.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Brood b = brood.get(e.getEntity().getUniqueId());
        if (b != null && b.asleep) e.setCancelled(true);
        if (b != null && e.getTarget() != null && (QueenSpiderManager.isQueen(e.getTarget()) || of(e.getTarget()) != null)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(STAND_TAG)) { e.getDrops().clear(); e.setDroppedExp(0); return; }
        Brood b = brood.get(e.getEntity().getUniqueId());
        if (b == null) return;
        if (b.rig != null) b.rig.die();
        QueenSpiderManager.killStandin(b.standin);
        e.getDrops().clear();
        Location l = e.getEntity().getLocation();
        e.getDrops().add(new ItemStack(Material.STRING, 1 + random.nextInt(3)));
        if (random.nextDouble() < 0.35) e.getDrops().add(new ItemStack(Material.SPIDER_EYE));
        if (b.kind == Kind.EMBER && random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.BLAZE_POWDER));
        if (b.kind == Kind.VENOM && random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.FERMENTED_SPIDER_EYE));
        if (b.kind == Kind.SWIFT && random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.SUGAR, 1 + random.nextInt(2)));
        if (b.kind == Kind.SERVANT && e.getEntity().getKiller() != null && random.nextDouble() < 0.12)
            for (ItemStack it : nests.roll(random, 1)) e.getDrops().add(it);
        e.setDroppedExp(b.kind == Kind.SERVANT ? 14 : 9);
        if (b.kind == Kind.VENOM) l.getWorld().spawn(l, AreaEffectCloud.class, c -> {
            c.setRadius(2.6f); c.setDuration(80); c.setRadiusPerTick(-0.01f); c.setWaitTime(0);
            c.setColor(Color.fromRGB(90, 230, 60));
            c.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 1), true);
        });
        if (b.kind == Kind.EMBER) {
            l.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 0.4, 0), 40, 0.8, 0.3, 0.8, 0.05);
            l.getWorld().playSound(l, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f);
            for (Entity n : l.getWorld().getNearbyEntities(l, 2.5, 2, 2.5)) if (n instanceof Player p && survival(p)) p.setFireTicks(Math.max(p.getFireTicks(), 60));
        }
        if (b.standin != null) { byStandin.remove(b.standin.getUniqueId()); if (b.standin.isValid()) b.standin.remove(); }
    }

    private boolean spawning;

    /** 1.3.49: the whole nest is dark, so vanilla filled it with mobs on top of ours: no NATURAL spawns inside a nest but ours. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (spawning || !(e.getEntity() instanceof org.bukkit.entity.Enemy) || nests.nests.isEmpty()) return;
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason why = e.getSpawnReason();
        boolean spawner = why == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
        if (!spawner && why != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (nests.nestAt(e.getLocation()) == null) return;
        if (!spawner) { e.setCancelled(true); return; }
        // 1.3.50: the nest's mob spawners piled spiders up (vanilla lets each one keep 6 alive): at most `spawner-cap` 2 mobs round one
        int near = 0, cap = plugin.getConfig().getInt("spider-nest.spawner-cap", 2);
        for (Entity n : e.getLocation().getWorld().getNearbyEntities(e.getLocation(), 16, 8, 16))
            if (n instanceof org.bukkit.entity.Enemy && n instanceof LivingEntity && ++near >= cap) { e.setCancelled(true); return; }
    }

    // ============================================================================================ spawning round players
    /** Something comes out of the dark near a player inside (every 2 s, `spawn-chance`), up to `mob-cap` near them. */
    void ambient(Player p, SpiderNest.Nest n) {
        if (!survival(p) || random.nextDouble() >= Balance.num(plugin, "spider-nest.spawn-chance", 0.18, 0.06)) return; // 1.3.49: far fewer
        int near = 0;
        for (Entity e : p.getNearbyEntities(22, 10, 22))
            if (e.getType() == EntityType.SPIDER && !e.getScoreboardTags().contains(STAND_TAG) || e.getType() == EntityType.CAVE_SPIDER
                    || e.getType() == EntityType.ZOMBIE || e.getType() == EntityType.SKELETON) near++;
        if (near >= Balance.num(plugin, "spider-nest.mob-cap", 9, 5)) return;
        Location at = spot(p.getLocation(), 7, 14);
        if (at == null) return;
        double r = random.nextDouble();
        spawning = true;
        try {
        if (r < 0.28) at.getWorld().spawnEntity(at, EntityType.SPIDER, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        else if (r < 0.50) at.getWorld().spawnEntity(at, EntityType.CAVE_SPIDER, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        else if (r < 0.62) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        else if (r < 0.74) at.getWorld().spawnEntity(at, EntityType.SKELETON, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        else if (broodNear(at, 40) < Balance.num(plugin, "spider-nest.brood-cap", 6, 3) && brood.size() < cfg("brood-max", 30)) {
            Kind k = r < 0.83 ? Kind.SERVANT : r < 0.89 ? Kind.VENOM : r < 0.95 ? Kind.EMBER : Kind.SWIFT;
            spawn(k, at, false, n.id);
        }
        } finally { spawning = false; }
    }

    int broodNear(Location l, double r) {
        int c = 0;
        for (Brood b : brood.values()) if (b.body.getWorld() == l.getWorld() && b.body.getLocation().distanceSquared(l) < r * r) c++;
        return c;
    }

    /** Dark floor min..max blocks from l, two blocks of air. */
    Location spot(Location l, double min, double max) {
        for (int tries = 0; tries < 16; tries++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            for (int dy = 4; dy >= -6; dy--) {
                Block b = l.getWorld().getBlockAt(x, l.getBlockY() + dy, z);
                if (!b.getType().isAir() || !b.getRelative(0, 1, 0).getType().isAir() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
                if (b.getLightFromBlocks() >= 8) break;
                return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    /** Sleeping brood on the silk beds of a sleeping den a player is near. */
    void beds(Player p, SpiderNest.Nest n) {
        long now = System.currentTimeMillis();
        for (int[] bed : n.sleeps) {
            String key = n.id + "@" + bed[0] + "," + bed[1] + "," + bed[2];
            if (now < bedSpawned.getOrDefault(key, 0L)) continue;
            int asleepNow = 0;
            for (Brood o : brood.values()) if (o.asleep && n.id.equals(o.nestId)) asleepNow++;
            if (asleepNow >= cfg("sleepers-max", 4)) return;
            UUID old = bedSleeper.get(key);
            if (old != null && brood.containsKey(old)) continue; // still there (asleep or out hunting)
            Location at = new Location(p.getWorld(), bed[0] + 0.5, bed[1], bed[2] + 0.5);
            double d = at.distanceSquared(p.getLocation());
            if (d > 26 * 26 || d < 10 * 10) continue;
            bedSpawned.put(key, now + (long) (cfg("bed-respawn-minutes", 10) * 60000));
            Kind k = random.nextDouble() < 0.5 ? Kind.SERVANT : Kind.values()[1 + random.nextInt(3)];
            if (brood.size() >= cfg("brood-max", 30)) return;
            Brood b = spawn(k, at, true, n.id);
            if (b != null) { b.body.setRotation(random.nextFloat() * 360, 0); bedSleeper.put(key, b.body.getUniqueId()); }
        }
    }

    // ============================================================================================ brood dens
    private void dens() {
        long now = System.currentTimeMillis();
        for (SpiderNest.Nest n : nests.nests.values()) {
            World w = Bukkit.getWorld(n.world);
            if (w == null) continue;
            for (int i = 0; i < n.dens.size(); i++) {
                int[] d = n.dens.get(i);
                String key = n.id + "#" + i;
                Run run = runs.get(key);
                if (!w.isChunkLoaded(d[0] >> 4, d[2] >> 4)) { if (run != null) abort(key, run); continue; }
                Location at = new Location(w, d[0] + 0.5, d[1], d[2] + 0.5);
                if (run == null) {
                    if (now < n.denReady.getOrDefault(i, 0L)) {
                        if (ticks % 40 == 0) w.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 1, 0), 4, 0.6, 0.6, 0.6, 0);
                        continue;
                    }
                    if (ticks % 20 == 0) w.spawnParticle(Particle.DUST, at.clone().add(0, 1.4, 0), 3, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.fromRGB(120, 255, 90), 1.0f));
                    Player trigger = null;
                    for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(at) < cfg("den-trigger-radius", 7) * cfg("den-trigger-radius", 7)) { trigger = p; break; }
                    if (trigger == null) continue;
                    run = new Run();
                    run.nestId = n.id; run.den = i; run.at = at;
                    int players = 0;
                    for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(at) < 24 * 24) { players++; run.fighters.add(p.getUniqueId()); }
                    run.waves = 3 + Math.max(0, players - 1) / 2;
                    runs.put(key, run);
                    for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) < 40 * 40) {
                        p.sendTitle(ChatColor.DARK_GREEN + "Brood Den", ChatColor.GRAY + "The egg sac splits open...", 5, 40, 10);
                        p.playSound(at, Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, 1.5f, 0.7f);
                        p.playSound(at, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 0.5f);
                    }
                    nextWave(run, w);
                    continue;
                }
                // a den that's awake
                for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(at) < 24 * 24) run.fighters.add(p.getUniqueId());
                boolean anyone = false;
                for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(at) < 30 * 30) anyone = true;
                if (!anyone) {
                    if (run.lonelySince < 0) run.lonelySince = now;
                    else if (now - run.lonelySince > 30000) { abort(key, run); continue; }
                } else run.lonelySince = -1;
                run.mobs.removeIf(u -> { Entity e = Bukkit.getEntity(u); return e == null || !e.isValid() || e.isDead(); });
                for (UUID u : new ArrayList<>(run.mobs)) { // a straggler that wandered off (or fell somewhere) can't hold the den up forever
                    Entity e = Bukkit.getEntity(u);
                    if (e != null && (e.getWorld() != w || e.getLocation().distanceSquared(at) > 40 * 40)) {
                        Brood b = brood.get(u);
                        if (b != null) remove(b); else e.remove();
                        run.mobs.remove(u);
                    }
                }
                for (UUID u : run.mobs) { // keep them on the fight
                    if (Bukkit.getEntity(u) instanceof Mob m && m.getTarget() == null) {
                        Player best = null; double bd = 30 * 30;
                        for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(m.getLocation()) < bd) { bd = p.getLocation().distanceSquared(m.getLocation()); best = p; }
                        if (best != null) m.setTarget(best);
                    }
                }
                if (ticks % 20 == 0) for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) < 30 * 30)
                    p.sendActionBar(net.kyori.adventure.text.Component.text("Brood Den: wave " + run.wave + "/" + run.waves + "  (" + run.mobs.size() + " left)", net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN));
                if (!run.mobs.isEmpty()) continue;
                if (run.pause > 0) { run.pause--; continue; }
                if (run.wave < run.waves) { nextWave(run, w); continue; }
                reward(n, key, run, w);
            }
        }
    }

    private void nextWave(Run run, World w) {
        run.wave++;
        run.pause = 4; // 2 s between waves
        int players = 0;
        for (UUID u : run.fighters) { Player p = Bukkit.getPlayer(u); if (p != null && survival(p) && p.getWorld() == w) players++; }
        int size = (int) Math.min(14, 4 + run.wave + 2 * Math.max(0, players - 1));
        w.playSound(run.at, Sound.BLOCK_TRIAL_SPAWNER_SPAWN_MOB, 1.5f, 0.8f);
        w.spawnParticle(Particle.WHITE_ASH, run.at.clone().add(0, 1, 0), 60, 2, 1, 2, 0.02);
        for (int i = 0; i < size; i++) {
            Location at = spot(run.at, 2.5, 6.5);
            if (at == null) at = run.at.clone().add(random.nextGaussian(), 0.5, random.nextGaussian());
            w.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION, at.clone().add(0, 0.5, 0), 8, 0.3, 0.4, 0.3, 0.02);
            double r = random.nextDouble() + run.wave * 0.05;
            Entity e;
            if (r < 0.24) e = w.spawnEntity(at, EntityType.SPIDER);
            else if (r < 0.42) e = w.spawnEntity(at, EntityType.CAVE_SPIDER);
            else if (r < 0.56) e = w.spawnEntity(at, EntityType.ZOMBIE);
            else if (r < 0.70) e = w.spawnEntity(at, EntityType.SKELETON);
            else {
                Kind k = r < 0.82 ? Kind.SERVANT : Kind.values()[1 + random.nextInt(3)];
                Brood b = spawn(k, at, false, run.nestId);
                e = b == null ? null : b.body;
            }
            if (e == null) continue;
            e.addScoreboardTag(DEN_TAG);
            e.setPersistent(false);
            if (e instanceof LivingEntity le && !(e instanceof Spider) && le.getEquipment() != null) { // keep their weapons (a skeleton needs its bow), drop none of it
                var eq = le.getEquipment();
                for (org.bukkit.inventory.EquipmentSlot s : org.bukkit.inventory.EquipmentSlot.values()) try { eq.setDropChance(s, 0f); } catch (RuntimeException ignored) { }
            }
            run.mobs.add(e.getUniqueId());
        }
    }

    private void abort(String key, Run run) {
        runs.remove(key);
        for (UUID u : run.mobs) {
            Entity e = Bukkit.getEntity(u);
            if (e == null) continue;
            Brood b = brood.get(u);
            if (b != null) remove(b); else e.remove();
        }
    }

    private void reward(SpiderNest.Nest n, String key, Run run, World w) {
        runs.remove(key);
        n.denReady.put(run.den, System.currentTimeMillis() + (long) (cfg("den-cooldown-minutes", 30) * 60000));
        nests.save();
        w.playSound(run.at, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.5f, 0.8f);
        w.playSound(run.at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, run.at.clone().add(0, 1.2, 0), 60, 0.6, 0.8, 0.6, 0.3);
        for (UUID u : run.fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || p.getWorld() != w || p.getLocation().distanceSquared(run.at) > 48 * 48) continue;
            List<ItemStack> loot = new ArrayList<>();
            for (int i = 0; i < 2 + run.waves / 2; i++) loot.addAll(nests.roll(random, 2));
            if (random.nextDouble() < 0.35) loot.add(new ItemStack(Material.DIAMOND, 1 + random.nextInt(2)));
            if (random.nextDouble() < 0.10) loot.add(new ItemStack(Material.GOLDEN_APPLE));
            for (ItemStack it : loot) for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.giveExp(40 + 10 * run.waves);
            p.sendTitle(ChatColor.GREEN + "Den Cleared", ChatColor.GRAY + "The brood is dead. Take what they hoarded.", 5, 50, 15);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " brood_dens 1");
        }
    }

    // ============================================================================================ quakes, the dark, the music
    /** Every few minutes the nest shakes: a rumble, falling dust, and dripstone breaking off the ceiling over players. */
    void quakeTick(SpiderNest.Nest n, List<Player> inside) {
        long now = System.currentTimeMillis();
        long next = nextQuake.computeIfAbsent(n.id, k -> now + (long) ((cfg("quake-min-minutes", 3) + random.nextDouble() * 3) * 60000));
        if (now < next || inside.isEmpty()) return;
        nextQuake.put(n.id, now + (long) ((cfg("quake-min-minutes", 3) + random.nextDouble() * (cfg("quake-max-minutes", 6) - cfg("quake-min-minutes", 3))) * 60000));
        quake(inside);
    }

    void quake(List<Player> inside) {
        for (Player p : inside) {
            Location l = p.getLocation();
            p.playSound(l, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.AMBIENT, 0.9f, 0.5f);
            p.playSound(l, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.AMBIENT, 1.5f, 0.5f);
            p.playSound(l, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1.5f, 0.5f);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, 0, true, false, false)); // the screen shakes
            p.spawnParticle(Particle.FALLING_DUST, l.clone().add(0, 4, 0), 80, 6, 1, 6, 0, Material.DEEPSLATE.createBlockData());
            p.sendActionBar(net.kyori.adventure.text.Component.text("The nest shakes!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            // dripstone over and around them breaks off
            int dropped = 0;
            for (int tries = 0; tries < 40 && dropped < cfg("quake-drips", 5); tries++) {
                int x = l.getBlockX() + random.nextInt(13) - 6, z = l.getBlockZ() + random.nextInt(13) - 6;
                if (tries < 3) { x = l.getBlockX() + random.nextInt(3) - 1; z = l.getBlockZ() + random.nextInt(3) - 1; } // one right over you
                Block b = l.getWorld().getBlockAt(x, l.getBlockY() + 2, z);
                int up = 0;
                while (up < 14 && b.getType().isAir()) { b = b.getRelative(0, 1, 0); up++; }
                if (up < 3 || up >= 14) continue;
                Block from = b.getType() == Material.POINTED_DRIPSTONE ? b : b.getRelative(0, -1, 0);
                if (from.getType() == Material.POINTED_DRIPSTONE) from.setType(Material.CAVE_AIR, false);
                drop(from.getLocation().add(0.5, b.getType() == Material.POINTED_DRIPSTONE ? -0.05 : 0, 0.5));
                dropped++;
            }
        }
    }

    private void drop(Location at) {
        BlockData tip;
        try { tip = Bukkit.createBlockData("minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]"); }
        catch (RuntimeException e) { tip = Material.POINTED_DRIPSTONE.createBlockData(); }
        FallingBlock fb = at.getWorld().spawnFallingBlock(at, tip);
        fb.addScoreboardTag(DRIP_TAG);
        fb.setDropItem(false);
        fb.setHurtEntities(true);
        fb.setDamagePerBlock(2f);
        fb.setMaxDamage(12);
        at.getWorld().spawnParticle(Particle.FALLING_DUST, at, 10, 0.2, 0.1, 0.2, 0, Material.DRIPSTONE_BLOCK.createBlockData());
    }

    /** A dropped dripstone shatters where it lands (never a new block). */
    @EventHandler(ignoreCancelled = true)
    public void onLand(EntityChangeBlockEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(DRIP_TAG)) return;
        e.setCancelled(true);
        Location l = e.getBlock().getLocation().add(0.5, 0.2, 0.5);
        l.getWorld().spawnParticle(Particle.BLOCK, l, 20, 0.3, 0.2, 0.3, 0, Material.POINTED_DRIPSTONE.createBlockData());
        l.getWorld().playSound(l, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1f, 0.8f);
        e.getEntity().remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDripItem(EntityDropItemEvent e) { if (e.getEntity().getScoreboardTags().contains(DRIP_TAG)) e.setCancelled(true); }

    /** Inside: it's DARK (hidden Darkness, unless a Night Vision potion... no, that doesn't help either), and the nest's music. */
    void insideTick(Player p, SpiderNest.Nest n, QueenSpiderManager queens) {
        if (plugin.getConfig().getBoolean("spider-nest.darkness", true) && p.getGameMode() != GameMode.SPECTATOR) {
            // 1.3.49: put on ONCE (endless) and taken off when you leave: re-applying it every few seconds restarted its fade-in
            // each time = a black flash
            if (!p.hasPotionEffect(PotionEffectType.DARKNESS))
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, PotionEffect.INFINITE_DURATION, 0, true, false, false));
            // 1.3.52: Darkness in a cave with no light = a black screen. A hidden Night Vision under it: you still get the
            // Darkness pulse and the dim edges, but you can see the cave (`spider-nest.see-in-the-dark`)
            if (plugin.getConfig().getBoolean("spider-nest.see-in-the-dark", true) && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION))
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, false));
        }
        boolean bossMusic = false;
        if (n.queen != null && !n.queen.asleep && n.queen.body.isValid() && n.queen.body.getWorld() == p.getWorld()
                && n.queen.body.getLocation().distanceSquared(p.getLocation()) < 64 * 64) bossMusic = true;
        long now = System.currentTimeMillis();
        Long at = musicAt.get(p.getUniqueId());
        if (bossMusic) {
            if (at != null) { p.stopSound(MUSIC, SoundCategory.MUSIC); musicAt.remove(p.getUniqueId()); }
            return;
        }
        if (at == null || now - at > cfg("music-seconds", 101) * 1000) {
            p.stopSound(SoundCategory.MUSIC);
            p.playSound(p, MUSIC, SoundCategory.MUSIC, 1f, 1f); // follows them (a sound at a spot fades as you walk off)
            musicAt.put(p.getUniqueId(), now);
        }
    }

    /** 1.3.52: the nest's endless Darkness / Night Vision never outlive a relog or a teleport out (insideTick puts them back inside). */
    @EventHandler
    public void onJoinClean(org.bukkit.event.player.PlayerJoinEvent e) { left(e.getPlayer()); }

    @EventHandler
    public void onQuitClean(org.bukkit.event.player.PlayerQuitEvent e) { left(e.getPlayer()); }

    void left(Player p) {
        if (musicAt.remove(p.getUniqueId()) != null) p.stopSound(MUSIC, SoundCategory.MUSIC);
        for (PotionEffectType t : new PotionEffectType[]{PotionEffectType.DARKNESS, PotionEffectType.NIGHT_VISION}) {
            PotionEffect cur = p.getPotionEffect(t);
            if (cur != null && cur.isAmbient() && !cur.hasParticles() && cur.getDuration() == PotionEffect.INFINITE_DURATION) p.removePotionEffect(t);
        }
    }
}
