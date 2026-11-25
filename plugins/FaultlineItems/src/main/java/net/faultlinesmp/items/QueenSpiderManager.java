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
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Spider;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
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
 * THE QUEEN SPIDER (overhauled in Items 1.3.44; was FaultlineItems.QueenSpiderManager). Her body is a hitbox spider made
 * invisible, drawn by QueenRig (31 jointed parts); Bedrock players see a stand-in spider (only them) whose hits go to her.
 * Two kinds: a WILD queen (rare in caves: `spawn-chance` 0.0025 of natural cave spiders, an old 0.01 reads as that; the
 * egg; /queenspider spawn) and a NEST queen (SpiderNest: she sleeps in her nest's throne until someone hits her; much
 * harder, `nest.*`, and much better loot to every fighter). Music: the Demon Eye's track (`faultline:demon_eye.music`,
 * 316 s) for everyone within 48 while she's awake.
 * Three phases (60% / 30%): each starts with a shriek (2 s she can't be hurt) and a brood burst.
 *   1: BITE (lunge + fangs, poison), POUNCE (6-16 away), LEG SWEEP (front legs, a cone of knockback), VENOM SPIT,
 *      WEB SHOT (a web bolt: a web cage round where it hits + Slowness), BROOD CALL.
 *   2: + three-glob spit, SILK PULL (reels you in), STOMP (rear up, slam, ring of webs), EGG SACS.
 *   3: faster (x1.35), + CEILING DROP (leaps out of sight, a red circle marks you, she lands on it), VENOM RAIN.
 */
final class QueenSpiderManager implements Listener {

    static final String TAG = "faultline_queen_spider";
    static final String BROOD_TAG = "faultline_brood";
    static final String VENOM_TAG = "faultline_queen_venom";
    static final String WEB_TAG = "faultline_queen_web";
    static final String STAND_TAG = "faultline_queen_standin";
    static final String MUSIC = "faultline:demon_eye.music";

    static final class Queen {
        Spider body;
        Spider standin;          // Bedrock only
        QueenRig rig;
        BossBar bar;
        final Set<UUID> brood = new HashSet<>();
        final Set<UUID> fighters = new HashSet<>();
        final Map<UUID, Long> musicAt = new HashMap<>();
        String nestId;           // null = a wild queen
        boolean asleep;
        int phase = 1;
        long invulnUntil, nextMove, t;
        String move;             // the move she's in, or null
        int moveT;
        Player mark;             // who the move is aimed at
        Location markAt;
        final Map<String, Long> ready = new HashMap<>();
        double scale;
    }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final Map<UUID, Queen> queens = new HashMap<>();
    private final Set<Block> webs = new HashSet<>();
    private final Map<Block, UUID> eggSacs = new HashMap<>();
    private final Map<Block, org.bukkit.entity.ItemDisplay> sacLooks = new HashMap<>();
    SpiderNest nests; // set by SpiderNest

    QueenSpiderManager(FaultlineItems plugin) { this.plugin = plugin; }

    static boolean isQueen(Entity e) { return e != null && e.getScoreboardTags().contains(TAG); }
    static boolean isBrood(Entity e) { return e != null && e.getScoreboardTags().contains(BROOD_TAG); }

    double cfg(String key, double def) { return plugin.getConfig().getDouble("queen-spider-boss." + key, def); }
    private double ncfg(Queen q, String key, double wild, double nest) {
        return q.nestId != null ? cfg("nest." + key, nest) : cfg(key, wild);
    }

    // ============================================================================================ spawning
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (event.getEntityType() != EntityType.SPIDER || !(event.getEntity() instanceof Spider spider)) return;
        Location loc = event.getLocation();
        if (loc.getBlock().getLightFromSky() > 0) return; // caves only
        if (nests != null && nests.near(loc, 48)) return; // 1.3.46: a nest has its own queen (its spiders were making wild ones)
        queens.values().removeIf(q -> !q.body.isValid() && (q.rig == null || q.rig.gone));
        int wild = 0;
        for (Queen q : queens.values()) if (q.nestId == null) wild++;
        if (wild >= (int) cfg("max-alive", 2)) return;
        if (random.nextDouble() >= Balance.num(plugin, "queen-spider-boss.spawn-chance", 0.01, 0.0025)) return; // 1.3.44: 4x rarer
        if (!roomFor(loc)) return;
        makeQueen(spider);
    }

    private boolean roomFor(Location loc) {
        Block base = loc.getBlock();
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) for (int z = -2; z <= 2; z++)
            if (!base.getRelative(x, y, z).isPassable()) return false;
        return true;
    }

    /** A wild queen (egg, natural, /queenspider spawn): awake at once. */
    void makeQueen(Spider spider) { setup(spider, null, false); }

    /** A nest's queen, asleep on her throne. */
    Queen sleepingQueen(Location at, String nestId) {
        if (!(at.getWorld().spawnEntity(at, EntityType.SPIDER, false) instanceof Spider spider) || !spider.isValid()) return null;
        return setup(spider, nestId, true);
    }

    private Queen setup(Spider spider, String nestId, boolean asleep) {
        Queen q = new Queen();
        q.body = spider;
        q.nestId = nestId;
        q.asleep = asleep;
        q.scale = nestId != null ? cfg("nest.scale", 4.2) : cfg("scale", 3.0);
        spider.addScoreboardTag(TAG);
        if (nestId != null) spider.addScoreboardTag("faultline_nest_queen");
        spider.setPersistent(false);
        spider.setRemoveWhenFarAway(false);
        spider.setInvisible(true); // QueenRig draws her
        spider.setSilent(false);
        String name = nestId != null ? "Queen of the Nest" : "Queen Spider";
        spider.setCustomName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + name);
        spider.setCustomNameVisible(false);
        setAttr(spider, Attribute.SCALE, q.scale);
        double hp = ncfg(q, "health", Balance.num(plugin, "queen-spider-boss.health", 500, 900), 3600); // really hard
        setAttr(spider, Attribute.MAX_HEALTH, hp);
        spider.setHealth(hp);
        setAttr(spider, Attribute.ATTACK_DAMAGE, ncfg(q, "bite-damage", 13, 20));
        setAttr(spider, Attribute.ARMOR, q.nestId != null ? cfg("nest.armor", 12) : cfg("armor", 6));
        setAttr(spider, Attribute.KNOCKBACK_RESISTANCE, 0.9);
        setAttr(spider, Attribute.FOLLOW_RANGE, 40);
        if (asleep) { spider.setAI(false); spider.setInvulnerable(false); }
        q.rig = new QueenRig(plugin, spider, q.scale);
        if (asleep) q.rig.play("sleep", 0);
        else q.rig.play("wake", 40);
        q.bar = Bukkit.createBossBar(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + name, BarColor.PURPLE, BarStyle.SEGMENTED_20);
        q.nextMove = 60;
        // Bedrock stand-in: a real spider only Bedrock players can see; their hits go to her
        try {
            if (spider.getWorld().spawnEntity(spider.getLocation(), EntityType.SPIDER, false) instanceof Spider s2) {
                s2.addScoreboardTag(STAND_TAG);
                s2.setPersistent(false);
                s2.setAI(false);
                s2.setSilent(true);
                s2.setInvulnerable(false);
                s2.setCollidable(false);
                s2.setVisibleByDefault(false);
                setAttr(s2, Attribute.SCALE, q.scale);
                q.standin = s2;
                for (Player p : spider.getWorld().getPlayers()) if (Edition.bedrock(p)) p.showEntity(plugin, s2);
            }
        } catch (RuntimeException ignored) { }
        queens.put(spider.getUniqueId(), q);
        if (!asleep) wakeEffects(q);
        return q;
    }

    private static void setAttr(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    /** Waking up: a roar, the bar, the music. */
    void wake(Queen q, Player by) {
        if (!q.asleep) return;
        q.asleep = false;
        q.body.setAI(true);
        q.invulnUntil = q.t + 50;
        q.nextMove = q.t + 60;
        // tougher for a group
        int fighters = 0;
        for (Player p : q.body.getWorld().getPlayers()) if (survival(p) && p.getLocation().distanceSquared(q.body.getLocation()) < 48 * 48) fighters++;
        if (q.nestId != null && fighters > 1) {
            double hp = cfg("nest.health", 3600) * (1 + (fighters - 1) * cfg("nest.per-fighter", 0.3));
            setAttr(q.body, Attribute.MAX_HEALTH, hp);
            q.body.setHealth(hp);
        }
        q.rig.play("wake", 45);
        wakeEffects(q);
        if (by != null) q.body.setTarget(by);
    }

    private void wakeEffects(Queen q) {
        Location l = q.body.getLocation();
        l.getWorld().playSound(l, "faultline:demon_eye.roar", SoundCategory.HOSTILE, 3f, 0.6f);
        l.getWorld().playSound(l, Sound.ENTITY_SPIDER_AMBIENT, 3f, 0.3f);
        l.getWorld().playSound(l, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.6f);
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 40 * 40) {
            p.sendTitle(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + (q.nestId != null ? "The Queen Awakens" : "Queen Spider"),
                    ChatColor.GRAY + (q.nestId != null ? "You woke her." : "Something huge crawls out of the dark."), 8, 50, 15);
        }
    }

    // ============================================================================================ every tick
    void tick() {
        for (Queen q : new ArrayList<>(queens.values())) {
            try { tickQueen(q); }
            catch (RuntimeException e) { plugin.getLogger().warning("[QueenSpider] tick: " + e); }
        }
    }

    private void tickQueen(Queen q) {
        q.t++;
        Spider queen = q.body;
        if (q.rig != null) q.rig.tick();
        if (!queen.isValid() || queen.isDead()) {
            if (q.rig == null || q.rig.gone) { end(q); queens.remove(queen.getUniqueId()); }
            return;
        }
        if (q.standin != null && q.standin.isValid()) {
            if (q.t % 2 == 0) q.standin.teleport(queen.getLocation());
            if (q.t % 40 == 0) for (Player p : queen.getWorld().getPlayers()) if (Edition.bedrock(p) && !p.canSee(q.standin)) p.showEntity(plugin, q.standin);
        }
        if (q.asleep) {
            if (q.t % 30 == 0) queen.getWorld().spawnParticle(Particle.WHITE_ASH, queen.getLocation().add(0, q.scale * 0.6, 0), 6, 0.5, 0.3, 0.5, 0);
            if (q.t % 80 == 0) queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.6f, 0.35f);
            return;
        }
        double maxHp = maxHp(queen);
        if (q.t % 10 == 0) bar(q, maxHp);
        if (q.t % 20 == 0) music(q);
        // phases
        double frac = queen.getHealth() / maxHp;
        int want = frac <= cfg("phase3-at", 0.3) ? 3 : frac <= cfg("phase2-at", 0.6) ? 2 : 1;
        if (want > q.phase) newPhase(q, want);
        if (q.move != null) { runMove(q); return; }
        if (q.t < q.nextMove) return;
        Player target = queen.getTarget() instanceof Player p && survival(p) && p.getLocation().distanceSquared(queen.getLocation()) < 40 * 40 ? p : nearestSurvival(queen.getLocation(), 32);
        if (target == null) return;
        if (queen.getTarget() != target) queen.setTarget(target);
        pickMove(q, target);
    }

    private double maxHp(LivingEntity e) {
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 500 : max.getValue();
    }

    private void bar(Queen q, double maxHp) {
        q.bar.setProgress(Math.max(0, Math.min(1, q.body.getHealth() / maxHp)));
        List<Player> near = new ArrayList<>();
        for (Player p : q.body.getWorld().getPlayers()) if (p.getLocation().distanceSquared(q.body.getLocation()) <= 48 * 48) near.add(p);
        for (Player p : new ArrayList<>(q.bar.getPlayers())) if (!near.contains(p)) q.bar.removePlayer(p);
        for (Player p : near) if (!q.bar.getPlayers().contains(p)) q.bar.addPlayer(p);
    }

    /** The Demon Eye's music for everyone within 48 (316 s, looped); stopped when they leave. */
    private void music(Queen q) {
        if (!plugin.getConfig().getBoolean("queen-spider-boss.music", true)) return;
        long len = (long) (cfg("music-seconds", 316.4) * 20);
        Location l = q.body.getLocation();
        for (Player p : l.getWorld().getPlayers()) {
            boolean in = p.getLocation().distanceSquared(l) < 48 * 48;
            Long at = q.musicAt.get(p.getUniqueId());
            if (in && (at == null || q.t - at >= len)) {
                p.stopSound(SoundCategory.MUSIC);
                p.playSound(p.getLocation(), MUSIC, SoundCategory.RECORDS, 1f, 1f);
                q.musicAt.put(p.getUniqueId(), q.t);
            } else if (!in && at != null && p.getLocation().distanceSquared(l) > 80 * 80) {
                p.stopSound(MUSIC, SoundCategory.RECORDS);
                q.musicAt.remove(p.getUniqueId());
            }
        }
    }

    private void stopMusic(Queen q) {
        for (UUID u : q.musicAt.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) p.stopSound(MUSIC, SoundCategory.RECORDS); }
        q.musicAt.clear();
    }

    private void newPhase(Queen q, int phase) {
        q.phase = phase;
        q.invulnUntil = q.t + 40;
        q.move = null;
        Spider queen = q.body;
        q.rig.play("rear", 40);
        queen.getWorld().playSound(queen.getLocation(), "faultline:demon_eye.phase", SoundCategory.HOSTILE, 3f, 0.7f);
        queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2f, 1.4f);
        if (phase == 2) q.bar.setColor(BarColor.RED);
        if (phase == 3) {
            q.bar.setColor(BarColor.RED);
            AttributeInstance speed = queen.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.35);
            queen.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + (q.nestId != null ? "Queen of the Nest" : "Queen Spider") + " (Frenzied)");
            q.bar.setTitle(queen.getCustomName());
        }
        for (Player p : queen.getWorld().getPlayers()) if (p.getLocation().distanceSquared(queen.getLocation()) < 48 * 48)
            p.sendMessage(ChatColor.DARK_RED + (phase == 2 ? "The Queen shrieks: her brood pours out of the walls!" : "The Queen goes into a frenzy!"));
        Player t = nearestSurvival(queen.getLocation(), 32);
        for (int i = 0; i < 2 + phase * 2; i++) spawnBrood(q, queen.getLocation(), i % 2 == 0 ? EntityType.CAVE_SPIDER : EntityType.SPIDER, t);
        q.nextMove = q.t + 50;
    }

    // ============================================================================================ moves
    private long gap(Queen q) {
        double g = q.phase == 1 ? 26 : q.phase == 2 ? 18 : 11;
        if (q.nestId != null) g *= 0.75;
        return (long) g;
    }

    private boolean readyFor(Queen q, String move) { return q.ready.getOrDefault(move, 0L) <= q.t; }
    private void cool(Queen q, String move, double seconds) { q.ready.put(move, q.t + (long) (seconds * 20 * (q.phase == 3 ? 0.65 : 1))); }

    private void pickMove(Queen q, Player target) {
        Spider queen = q.body;
        double reach = 2.2 + q.scale * 0.8;
        double d = target.getLocation().distance(queen.getLocation());
        boolean sees = sees(queen, target);
        List<String> pool = new ArrayList<>();
        if (d <= reach + 1 && readyFor(q, "bite")) { pool.add("bite"); pool.add("bite"); }
        if (d <= reach + 3 && readyFor(q, "sweep")) pool.add("sweep");
        if (d >= 6 && d <= 16 && readyFor(q, "pounce") && queen.isOnGround()) pool.add("pounce");
        if (d >= 4 && d <= 26 && sees && readyFor(q, "spit")) pool.add("spit");
        if (d >= 5 && d <= 22 && sees && readyFor(q, "web")) pool.add("web");
        if (readyFor(q, "brood") && q.brood.size() < (int) cfg("max-brood", 12)) pool.add("brood");
        if (q.phase >= 2) {
            if (d >= 8 && d <= 22 && sees && readyFor(q, "pull")) pool.add("pull");
            if (d <= 8 && readyFor(q, "stomp") && queen.isOnGround()) pool.add("stomp");
            if (readyFor(q, "sacs")) pool.add("sacs");
        }
        if (q.phase >= 3 || q.phase >= 2 && q.nestId != null) {
            if (readyFor(q, "drop") && queen.isOnGround()) pool.add("drop");
        }
        if (q.phase >= 3 && readyFor(q, "rain")) pool.add("rain");
        if (pool.isEmpty()) return;
        String m = pool.get(random.nextInt(pool.size()));
        q.move = m;
        q.moveT = 0;
        q.mark = target;
        q.markAt = target.getLocation();
        q.nextMove = q.t + gap(q);
    }

    private void runMove(Queen q) {
        Spider queen = q.body;
        Player tg = q.mark;
        int t = ++q.moveT;
        World w = queen.getWorld();
        if (tg == null || !tg.isValid() || tg.isDead() || !tg.getWorld().equals(w)) { q.move = null; return; }
        Location ql = queen.getLocation();
        switch (q.move) {
            case "bite" -> {
                if (t == 1) { q.rig.play("bite", 16); face(queen, tg); }
                if (t == 6) Safe.vel(queen, dir(ql, tg.getLocation()).multiply(0.6).setY(0.1));
                if (t == 9) {
                    double reach = 2.6 + q.scale * 0.8;
                    for (Player p : inFront(queen, reach, 70)) {
                        hurt(q, p, ncfg(q, "bite-damage", 12, 18), true);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) (cfg("poison-seconds", 6) * 20), 1));
                    }
                    w.playSound(ql, Sound.ENTITY_SPIDER_HURT, 1.5f, 0.4f);
                }
                if (t >= 16) { cool(q, "bite", 2.5); q.move = null; }
            }
            case "pounce" -> {
                if (t == 1) { q.rig.play("crouch", 14); face(queen, tg); w.playSound(ql, Sound.ENTITY_SPIDER_STEP, 2f, 0.4f); }
                if (t == 12) {
                    q.rig.play("bite", 20);
                    Vector jump = tg.getLocation().toVector().subtract(ql.toVector()).setY(0);
                    double dist = jump.length();
                    Safe.vel(queen, jump.normalize().multiply(Math.min(2.4, dist * 0.14)).setY(0.75));
                }
                if (t > 16 && queen.isOnGround() || t > 40) {
                    slam(q, 3.5, ncfg(q, "pounce-damage", 10, 15));
                    cool(q, "pounce", 8); q.move = null;
                }
            }
            case "sweep" -> {
                if (t == 1) { q.rig.play("rear", 22); face(queen, tg); w.playSound(ql, Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.5f); }
                if (t == 15) {
                    for (Player p : inFront(queen, 3 + q.scale * 1.2, 140)) {
                        hurt(q, p, ncfg(q, "sweep-damage", 8, 13), false);
                        Safe.vel(p, dir(ql, p.getLocation()).multiply(1.4).setY(0.55));
                    }
                    w.playSound(ql, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.5f);
                    w.spawnParticle(Particle.SWEEP_ATTACK, ql.clone().add(dir(ql, tg.getLocation()).multiply(2.5)).add(0, 1, 0), 5, 1.5, 0.4, 1.5, 0);
                }
                if (t >= 22) { cool(q, "sweep", 6); q.move = null; }
            }
            case "spit" -> {
                if (t == 1) { q.rig.play("spit", 16); face(queen, tg); }
                if (t == 8) {
                    int n = q.phase >= 2 ? 3 : 1;
                    for (int i = 0; i < n; i++) spit(queen, tg, (i - (n - 1) / 2.0) * 0.18);
                }
                if (t >= 16) { cool(q, "spit", 5); q.move = null; }
            }
            case "web" -> {
                if (t == 1) { q.rig.play("spit", 16); face(queen, tg); }
                if (t == 8) webShot(queen, tg);
                if (t >= 16) { cool(q, "web", 10); q.move = null; }
            }
            case "brood" -> {
                if (t == 1) { q.rig.play("brood", 30); w.playSound(ql, Sound.ENTITY_SPIDER_AMBIENT, 2f, 1.4f); }
                if (t == 20) {
                    int n = 2 + q.phase;
                    for (int i = 0; i < n && q.brood.size() < (int) cfg("max-brood", 12); i++) spawnBrood(q, ql, i % 3 == 0 ? EntityType.SPIDER : EntityType.CAVE_SPIDER, tg);
                }
                if (t >= 30) { cool(q, "brood", 14); q.move = null; }
            }
            case "pull" -> {
                if (t == 1) { q.rig.play("spit", 30); face(queen, tg); w.playSound(ql, Sound.ENTITY_FISHING_BOBBER_THROW, 2f, 0.5f); }
                if (t >= 6 && t <= 26 && t % 2 == 0) {
                    silkLine(ql.clone().add(0, q.scale * 0.5, 0), tg.getLocation().add(0, 1, 0));
                    if (t >= 10 && !FaultlineItems.facingBlock(tg, ql.toVector())) {
                        Vector in = dir(tg.getLocation(), ql).multiply(0.55).setY(0.12);
                        Safe.vel(tg, in);
                        tg.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0));
                    }
                }
                if (t >= 30) { cool(q, "pull", 12); q.move = null; }
            }
            case "stomp" -> {
                if (t == 1) { q.rig.play("slam", 30); w.playSound(ql, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 1.3f); }
                if (t == 4) Safe.vel(queen, new Vector(0, 0.9, 0));
                if (t > 10 && queen.isOnGround() || t > 34) {
                    slam(q, cfg("stomp-radius", 7), ncfg(q, "stomp-damage", 11, 16));
                    Block c = ql.getBlock();
                    for (int i = 0; i < 12; i++) {
                        double a = i * Math.PI / 6, r = 4 + random.nextDouble() * 2;
                        placeWeb(c.getRelative((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r)), (long) cfg("web-seconds", 5));
                    }
                    cool(q, "stomp", 9); q.move = null;
                }
            }
            case "sacs" -> {
                if (t == 1) { q.rig.play("brood", 24); }
                if (t == 16) {
                    int laid = laySacs(queen, tg, (int) cfg("egg-sacs", 3) + (q.nestId != null ? 1 : 0));
                    if (laid > 0) {
                        w.playSound(ql, Sound.ENTITY_TURTLE_LAY_EGG, 2f, 0.5f);
                        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(ql) < 40 * 40)
                            p.sendActionBar(net.kyori.adventure.text.Component.text("EGG SACS! Break them before they hatch!", net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
                    }
                }
                if (t >= 24) { cool(q, "sacs", 22); q.move = null; }
            }
            case "drop" -> { // Ceiling Drop: up out of sight, a red circle marks you, she lands on it
                if (t == 1) { q.rig.play("crouch", 10); w.playSound(ql, Sound.ENTITY_SPIDER_STEP, 2f, 0.3f); }
                if (t == 8) Safe.vel(queen, new Vector(0, 1.6, 0));
                if (t >= 8 && t <= 40) {
                    if (t % 2 == 0) q.markAt = tg.getLocation();
                    circle(q.markAt, 3.5, Color.fromRGB(200, 20, 30));
                    if (t % 10 == 0) tg.playSound(tg.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.5f, 0.5f);
                }
                if (t == 40) {
                    Location above = q.markAt.clone().add(0, 8, 0);
                    if (clearAbove(q.markAt, 8)) queen.teleport(above);
                    Safe.vel(queen, new Vector(0, -1.6, 0));
                    q.rig.play("slam", 20);
                }
                if (t > 42 && queen.isOnGround() || t > 70) {
                    slam(q, 4.5, ncfg(q, "drop-damage", 14, 20));
                    cool(q, "drop", 18); q.move = null;
                }
            }
            case "rain" -> { // Venom Rain: circles under everyone, then globs splash there
                if (t == 1) {
                    q.rig.play("spit", 30);
                    w.playSound(ql, "faultline:demon_eye.spit", SoundCategory.HOSTILE, 2f, 0.6f);
                    rainSpots.put(queen.getUniqueId(), new ArrayList<>());
                    for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(ql) < 30 * 30)
                        for (int i = 0; i < 3; i++) rainSpots.get(queen.getUniqueId()).add(p.getLocation().add(random.nextGaussian() * 2.5, 0, random.nextGaussian() * 2.5));
                }
                List<Location> spots = rainSpots.getOrDefault(queen.getUniqueId(), List.of());
                if (t < 30) for (Location s : spots) { circle(s, 1.6, Color.fromRGB(110, 230, 40)); if (t % 4 == 0) w.spawnParticle(Particle.FALLING_DRIPSTONE_LAVA, s.clone().add(0, 6, 0), 1, 0.3, 0, 0.3, 0); }
                if (t == 30) for (Location s : spots) {
                    w.spawnParticle(Particle.ITEM_SLIME, s.clone().add(0, 0.3, 0), 30, 0.8, 0.2, 0.8, 0.05);
                    w.playSound(s, Sound.ENTITY_SLIME_SQUISH, 1.5f, 0.5f);
                    for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(s) < 1.8 * 1.8) {
                        hurt(q, p, ncfg(q, "rain-damage", 6, 9), false);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
                    }
                }
                if (t >= 32) { rainSpots.remove(queen.getUniqueId()); cool(q, "rain", 14); q.move = null; }
            }
            default -> q.move = null;
        }
    }

    private final Map<UUID, List<Location>> rainSpots = new HashMap<>();

    private boolean clearAbove(Location l, int h) {
        for (int i = 1; i <= h; i++) if (l.clone().add(0, i, 0).getBlock().getType().isSolid()) return false;
        return true;
    }

    private void circle(Location c, double r, Color col) {
        Particle.DustOptions d = new Particle.DustOptions(col, 1.3f);
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI / 10;
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0, d);
        }
    }

    private void silkLine(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.multiply(1 / len);
        Particle.DustOptions silk = new Particle.DustOptions(Color.fromRGB(235, 235, 240), 0.8f);
        for (double k = 0; k < len; k += 0.5) a.getWorld().spawnParticle(Particle.DUST, a.clone().add(d.clone().multiply(k)), 1, 0, 0, 0, 0, silk);
    }

    private static Vector dir(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1e-6) return new Vector(1, 0, 0);
        return v.normalize();
    }

    private void face(Spider s, Player p) {
        Location l = s.getLocation();
        l.setDirection(p.getLocation().toVector().subtract(l.toVector()));
        try { s.setRotation(l.getYaw(), 0); } catch (RuntimeException ignored) { }
    }

    /** Players within `reach` and within `cone` degrees of where she faces. */
    private List<Player> inFront(Spider s, double reach, double cone) {
        List<Player> out = new ArrayList<>();
        Location l = s.getLocation();
        float yaw;
        try { yaw = s.getBodyYaw(); } catch (Throwable e) { yaw = l.getYaw(); }
        Vector f = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        for (Player p : l.getWorld().getPlayers()) {
            if (!survival(p)) continue;
            Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            double d = to.length();
            if (d > reach || Math.abs(p.getLocation().getY() - l.getY()) > 4) continue;
            if (d < 1.5 || Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(f))))) <= cone / 2) out.add(p);
        }
        return out;
    }

    private void hurt(Queen q, Player p, double dmg, boolean blockable) {
        if (blockable && FaultlineItems.facingBlock(p, q.body.getLocation().toVector())) { FaultlineItems.shieldHit(p, dmg); return; }
        slamming = !blockable;
        try { p.damage(dmg, q.body); } finally { slamming = false; }
    }

    // ============================================================================================ older pieces (kept)
    private void spawnBrood(Queen q, Location around, EntityType type, Player target) {
        Location at = around.clone().add(random.nextInt(7) - 3, 0.2, random.nextInt(7) - 3);
        if (!at.getBlock().isPassable()) at = around;
        if (at.getWorld().spawnEntity(at, type, false) instanceof Mob baby && baby.isValid()) {
            baby.addScoreboardTag(BROOD_TAG);
            baby.setPersistent(false);
            if (target != null) baby.setTarget(target);
            q.brood.add(baby.getUniqueId());
        }
        q.brood.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
    }

    private int laySacs(Spider queen, Player target, int count) {
        Location c = queen.getLocation();
        Vector toTarget = target.getLocation().toVector().subtract(c.toVector()).setY(0);
        double base = toTarget.lengthSquared() > 0.01 ? Math.atan2(toTarget.getZ(), toTarget.getX()) : random.nextDouble() * Math.PI * 2;
        int laid = 0;
        for (int tries = 0; tries < 24 && laid < count; tries++) {
            double ang = base + (random.nextDouble() - 0.5) * Math.PI * 1.4;
            double r = 3 + random.nextDouble() * 5;
            Block spot = null;
            Block col = c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r).getBlock();
            for (int dy = 2; dy >= -3; dy--) {
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
            long hatchTicks = (long) (cfg("egg-sac-hatch-seconds", 6) * 20);
            for (long k = 10; k < hatchTicks; k += 10) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSacBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!eggSacs.containsKey(b)) return;
        eggSacs.remove(b);
        event.setDropItems(false);
        b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TURTLE_EGG_BREAK, 1.5f, 0.6f);
        removeSacLook(b);
    }

    private void hatch(Block sac) {
        removeSacLook(sac);
        UUID queenId = eggSacs.remove(sac);
        if (queenId == null || sac.getType() != Material.COBWEB) return;
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

    private void clearWeb(Block block) {
        if (webs.remove(block) && block.getType() == Material.COBWEB) block.setType(Material.AIR);
    }

    private void spit(Spider queen, Player target, double spread) {
        Location from = queen.getLocation().add(0, queen.getHeight() * 0.6, 0);
        Snowball glob = queen.getWorld().spawn(from, Snowball.class, s -> {
            s.setItem(new ItemStack(Material.LIME_DYE));
            s.addScoreboardTag(VENOM_TAG);
            s.setShooter(queen);
        });
        Vector aim = target.getEyeLocation().subtract(0, 0.5, 0).toVector().subtract(from.toVector());
        double dist = aim.length();
        aim.setY(aim.getY() + dist * 0.08);
        aim = aim.normalize().rotateAroundY(spread);
        Safe.vel(glob, aim.multiply(1.35));
        queen.getWorld().playSound(queen.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.5f, 0.6f);
    }

    /** Web Shot: a bolt of web; where it lands, a web cage. */
    private void webShot(Spider queen, Player target) {
        Location from = queen.getLocation().add(0, queen.getHeight() * 0.5, 0);
        Snowball bolt = queen.getWorld().spawn(from, Snowball.class, s -> {
            s.setItem(new ItemStack(Material.COBWEB));
            s.addScoreboardTag(WEB_TAG);
            s.setShooter(queen);
        });
        Vector aim = target.getEyeLocation().subtract(0, 0.8, 0).toVector().subtract(from.toVector());
        aim.setY(aim.getY() + aim.length() * 0.06);
        Safe.vel(bolt, aim.normalize().multiply(1.5));
        queen.getWorld().playSound(from, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 1.8f);
    }

    @EventHandler
    public void onShotHit(ProjectileHitEvent event) {
        Projectile pr = event.getEntity();
        boolean venom = pr.getScoreboardTags().contains(VENOM_TAG), web = pr.getScoreboardTags().contains(WEB_TAG);
        if (!venom && !web) return;
        Location at = pr.getLocation();
        Entity shooter = pr.getShooter() instanceof Entity s ? s : null;
        Queen q = shooter == null ? null : queens.get(shooter.getUniqueId());
        if (venom) {
            at.getWorld().spawnParticle(Particle.ITEM_SLIME, at, 20, 0.4, 0.4, 0.4, 0);
            if (event.getHitEntity() instanceof LivingEntity victim && !isQueen(victim) && !isBrood(victim)) {
                double dmg = q == null ? cfg("spit-damage", 4) : ncfg(q, "spit-damage", 4, 6);
                if (victim instanceof Player p && FaultlineItems.facingBlock(p, at.toVector())) FaultlineItems.shieldHit(p, dmg);
                else {
                    victim.damage(dmg, shooter);
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 2));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
                }
            }
        } else {
            Block c = event.getHitEntity() != null ? event.getHitEntity().getLocation().getBlock() : at.getBlock();
            at.getWorld().playSound(at, Sound.BLOCK_WOOL_PLACE, 1.5f, 0.6f);
            at.getWorld().spawnParticle(Particle.BLOCK, at, 30, 0.6, 0.6, 0.6, 0, Material.COBWEB.createBlockData());
            for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = -1; z <= 1; z++)
                placeWeb(c.getRelative(x, y, z), (long) cfg("web-seconds", 5));
            if (event.getHitEntity() instanceof Player p) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
        }
        pr.remove();
    }

    private Player nearestSurvival(Location loc, double range) {
        Player best = null;
        double bestDist = range * range;
        for (Player p : loc.getWorld().getPlayers()) {
            if (!survival(p)) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    private static boolean survival(Player p) { return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE); }

    private static boolean sees(LivingEntity a, Player b) {
        try { return a.hasLineOfSight(b); } catch (RuntimeException e) { return true; }
    }

    private void slam(Queen q, double radius, double damage) {
        Spider queen = q.body;
        Location at = queen.getLocation();
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 8, radius / 3, 0.2, radius / 3, 0);
        at.getWorld().spawnParticle(Particle.BLOCK, at, 60, radius / 2, 0.2, radius / 2, 0, at.clone().add(0, -1, 0).getBlock().getBlockData());
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        for (Player p : at.getWorld().getPlayers()) {
            if (!survival(p) || p.getLocation().distanceSquared(at) > radius * radius || Math.abs(p.getLocation().getY() - at.getY()) > 3) continue;
            hurt(q, p, damage, false);
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1));
            Safe.vel(p, dir(at, p.getLocation()).multiply(1.2).setY(0.6));
        }
    }

    private boolean slamming;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSlamDamage(EntityDamageByEntityEvent event) {
        if (slamming && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent event) {
        if (!isQueen(event.getDamager()) || !(event.getEntity() instanceof LivingEntity victim)) return;
        if (FaultlineItems.shieldBlocked(event)) return;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) (cfg("poison-seconds", 6) * 20), 1));
    }

    // ============================================================================================ being hit
    Queen queenOf(Entity e) {
        if (e == null) return null;
        Queen q = queens.get(e.getUniqueId());
        if (q != null) return q;
        if (e.getScoreboardTags().contains(STAND_TAG)) for (Queen x : queens.values()) if (x.standin == e) return x;
        return null;
    }

    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        Queen q = queenOf(event.getEntity());
        if (q == null) return;
        if (event.getEntity() == q.standin) { // Bedrock: the stand-in passes it on
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent ev && attacker(ev.getDamager()) != null && q.body.isValid())
                q.body.damage(event.getFinalDamage(), attacker(ev.getDamager()));
            return;
        }
        Player by = event instanceof EntityDamageByEntityEvent ev ? attacker(ev.getDamager()) : null;
        if (q.asleep) {
            if (by == null || by.getGameMode() == GameMode.CREATIVE && !plugin.getConfig().getBoolean("queen-spider-boss.creative-wakes", true)) { event.setCancelled(true); return; }
            wake(q, by);
            q.fighters.add(by.getUniqueId());
            event.setDamage(Math.min(event.getDamage(), 10));
            return;
        }
        if (q.t < q.invulnUntil) { event.setCancelled(true); return; }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL || event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE || event.getCause() == EntityDamageEvent.DamageCause.LAVA) { event.setCancelled(true); return; }
        if (by != null) q.fighters.add(by.getUniqueId());
        if (q.t % 6 == 0) q.body.getWorld().playSound(q.body.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.5f, 0.5f);
        if (q.rig != null) q.rig.hurt(event.getDamage());
    }

    /** Asleep: she doesn't hunt anyone. Her brood never turn on her. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        Queen q = queens.get(event.getEntity().getUniqueId());
        if (q != null && q.asleep) event.setCancelled(true);
        if (isBrood(event.getEntity()) && isQueen(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (isBrood(dead)) { event.getDrops().clear(); event.setDroppedExp(0); return; }
        if (dead.getScoreboardTags().contains(STAND_TAG)) { event.getDrops().clear(); event.setDroppedExp(0); return; }
        if (!isQueen(dead)) return;
        Queen q = queens.get(dead.getUniqueId());
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (q != null) {
            if (q.rig != null) q.rig.die();
            dead.getWorld().playSound(dead.getLocation(), "faultline:demon_eye.death", SoundCategory.HOSTILE, 3f, 0.6f);
            end(q);
        }
        for (Map.Entry<Block, UUID> e : new ArrayList<>(eggSacs.entrySet())) {
            if (!e.getValue().equals(dead.getUniqueId())) continue;
            eggSacs.remove(e.getKey());
            removeSacLook(e.getKey());
            if (e.getKey().getType() == Material.COBWEB) e.getKey().setType(Material.AIR);
        }
        for (UUID id : q == null ? Set.<UUID>of() : q.brood) { Entity b = Bukkit.getEntity(id); if (b != null) b.remove(); }
        Player killer = dead.getKiller();
        if (q != null && q.nestId != null) {
            if (nests != null) nests.queenDied(q.nestId);
            nestRewards(q, dead, killer);
            return;
        }
        if (killer == null) return;
        event.setDroppedExp((int) cfg("xp", 400));
        event.getDrops().add(new ItemStack(Material.SPIDER_EYE, 10));
        if (random.nextDouble() < cfg("diamond-chance", 0.5)) event.getDrops().add(new ItemStack(Material.DIAMOND, 5 + random.nextInt(6)));
        if (random.nextDouble() < Balance.num(plugin, "queen-spider-boss.spider-staff-chance", 0.10, 1.0)) event.getDrops().add(FaultlineItems.SpiderStaffItem.create(plugin)); // 1.3.44: always
        if (random.nextDouble() < cfg("ankh-chance", 0.02)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giveaccessory ankhpiece 1 " + killer.getName());
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + killer.getName() + " found an Ankh in the Queen's nest of bones!");
        }
        if (random.nextDouble() < plugin.getConfig().getDouble("boss-loot.queen-silk-chance", 0.05) * plugin.eventDropMultiplier()) {
            event.getDrops().add(FaultlineItems.QueenSilkItem.create(plugin));
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + killer.getName() + " claimed the Queen's Silk!");
        }
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + killer.getName() + " slew the Queen Spider!");
    }

    static final List<String> NEST_REWARDS = List.of("item DIAMOND 14", "item NETHERITE_SCRAP 3", "item SPIDER_EYE 16", "item EXPERIENCE_BOTTLE 24",
            "givegoodiebag 2 {player}", "givemythicbag 1 {player}", "fenchant give {enchant} 1 1 {player}");

    /** The nest queen: every fighter gets the rewards, plus a good chance of her staff and silk. */
    private void nestRewards(Queen q, LivingEntity dead, Player killer) {
        Set<UUID> who = new HashSet<>(q.fighters);
        if (killer != null) who.add(killer.getUniqueId());
        for (UUID u : who) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || p.getLocation().distanceSquared(dead.getLocation()) > 96 * 96) continue;
            ToiletRaid.rewards(plugin, p, "queen-spider-boss.nest.rewards", NEST_REWARDS, random);
            p.giveExp((int) cfg("nest.xp", 1500));
            if (random.nextDouble() < cfg("nest.spider-staff-chance", 1.0)) give(p, FaultlineItems.SpiderStaffItem.create(plugin));
            if (random.nextDouble() < cfg("nest.ankh-chance", 0.02)) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giveaccessory ankhpiece 1 " + p.getName());
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + p.getName() + " found an Ankh in the Queen's nest!");
            }
            if (random.nextDouble() < cfg("nest.queen-silk-chance", 0.20)) {
                give(p, FaultlineItems.QueenSilkItem.create(plugin));
                Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + p.getName() + " claimed the Queen's Silk!");
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " nest_queens 1");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + p.getName() + " queen_spider");
        }
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Queen of the Nest has fallen" + (killer != null ? " to " + killer.getName() : "") + "!");
    }

    private void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    private void end(Queen q) {
        if (q.bar != null) q.bar.removeAll();
        stopMusic(q);
        if (q.standin != null && q.standin.isValid()) q.standin.remove();
    }

    /** Takes a queen away now (her nest's chunk unloaded, the plugin stopping). */
    void remove(Queen q) {
        end(q);
        if (q.rig != null) q.rig.remove();
        if (q.body.isValid()) q.body.remove();
        for (UUID id : q.brood) { Entity b = Bukkit.getEntity(id); if (b != null) b.remove(); }
        queens.remove(q.body.getUniqueId());
    }

    // ============================================================================================ the egg
    @EventHandler
    public void onEggUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!FaultlineItems.QueenSpiderEggItem.isEgg(plugin, event.getItem())) return;
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
        if (FaultlineItems.QueenSpiderEggItem.isEgg(plugin, event.getItem())) event.setCancelled(true);
    }

    void removeAll() {
        for (Queen q : new ArrayList<>(queens.values())) remove(q);
        queens.clear();
        new ArrayList<>(webs).forEach(this::clearWeb);
        for (Block sac : new ArrayList<>(eggSacs.keySet())) {
            if (sac.getType() == Material.COBWEB) sac.setType(Material.AIR);
            removeSacLook(sac);
        }
        eggSacs.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class))
            if (e.getScoreboardTags().contains(QueenRig.TAG)) e.remove();
    }
}
