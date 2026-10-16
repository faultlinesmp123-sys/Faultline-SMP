package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE DARK OCEAN'S CREATURES: 20 kinds, by zone (the Sunlit Sea, the crust's caves, the abyss) and temper (passive,
 * neutral: only fights back, aggressive). Inspired by Subnautica / Below Zero, the Calamity mod's seas and Sea of Solitude,
 * with names and looks of our own.
 *
 * Each one is a vanilla mob that does the moving and the hitting (a fish, a squid, a drowned, a spider, a frog...), made
 * invisible, wearing its model: an ItemDisplay riding it (paper with item model faultline:deep/<id>, tools/deep_assets.py)
 * for Java, and an armor stand only Bedrock players see (the same item on its head, drawn by FaultlineDeep.mcpack) that
 * follows it; hitting the stand hits the creature. Everything is non-persistent: unloaded = gone, the spawner refills.
 * Kept in `live` (never a world scan); ticked every 2 ticks.
 */
final class DeepMobs implements Listener {

    static final String TAG = "faultline_deep_mob", PART = "faultline_deep_part", STAND = "faultline_deep_stand";

    enum Temper { PASSIVE, NEUTRAL, AGGRESSIVE }

    /** One kind of creature. damage 0 = no melee. */
    record Kind(String id, String name, ChatColor color, Temper temper, DeepGenerator.Zone zone, EntityType base,
                double scale, double health, double damage, double speed, int weight, float modelScale, String about) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    private static void k(String id, String name, ChatColor c, Temper t, DeepGenerator.Zone z, EntityType base, double scale,
                          double hp, double dmg, double speed, int weight, float ms, String about) {
        KINDS.put(id, new Kind(id, name, c, t, z, base, scale, hp, dmg, speed, weight, ms, about));
    }

    static {
        var S = DeepGenerator.Zone.SUNLIT; var C = DeepGenerator.Zone.CAVES; var A = DeepGenerator.Zone.ABYSS;
        var P = Temper.PASSIVE; var N = Temper.NEUTRAL; var G = Temper.AGGRESSIVE;
        // ---- the Sunlit Sea
        k("gawker", "Gawker", ChatColor.YELLOW, P, S, EntityType.COD, 1.0, 4, 0, 0, 30, 0.9f, "A round little fish that's mostly eye. It stares, then darts off in a school.");
        k("bloatfin", "Bloatfin", ChatColor.AQUA, P, S, EntityType.SALMON, 1.0, 6, 0, 0, 20, 1.0f, "A fish like a bubble with fins. Pop one and its air sac bursts: everyone close gets their air back and breathes easy for 15 seconds.");
        k("jellywing", "Jellywing", ChatColor.LIGHT_PURPLE, P, S, EntityType.SQUID, 1.3, 14, 0, 0, 10, 1.6f, "A glowing ray as clear as jelly, gliding over the reefs. Sirens comb their hair with its spines.");
        k("driftback", "Driftback", ChatColor.GREEN, P, S, EntityType.DOLPHIN, 3.0, 160, 0, 0, 1, 5.0f, "A gentle giant with a whole reef growing on its back. It hums as it drifts by.");
        k("clam", "Clam", ChatColor.WHITE, N, S, EntityType.SHULKER, 1.0, 20, 5, 0, 12, 1.0f, "Sits shut on the sea floor. Hit it and it snaps back. Some hold a pearl, and a very few a Shield of the Ocean.");
        k("lumenbell", "Lumen Bell", ChatColor.BLUE, N, S, EntityType.SQUID, 1.1, 10, 2, 0, 10, 1.3f, "A ghostly bell of a jellyfish, pulsing with light. Touching its trailing threads stings; hurting it sets off a shock.");
        k("gasbloom", "Gasbloom", ChatColor.DARK_GREEN, N, S, EntityType.SQUID, 1.5, 24, 0, 0, 6, 1.8f, "A slow, swollen floater. Crowd it or hit it and it lets out pods of poison gas.");
        k("fangstalker", "Fangstalker", ChatColor.GRAY, N, S, EntityType.DROWNED, 1.15, 30, 6, 0.26, 7, 1.6f, "A lean shark with a jaw full of hooked teeth. It leaves you alone... unless you hit it, or swim by holding metal it wants.");
        k("blastfin", "Blastfin", ChatColor.GOLD, G, S, EntityType.DROWNED, 0.6, 6, 0, 0.3, 8, 0.9f, "A spiky little fish full of volatile gas. It swims straight at you and bursts.");
        k("burrowshark", "Burrow Shark", ChatColor.DARK_RED, G, S, EntityType.DROWNED, 1.2, 30, 7, 0.27, 5, 1.7f, "Lies buried in the sand with only its fin showing. Swim too close and it erupts.");
        // ---- the crust's caves
        k("cavecrawler", "Cavecrawler", ChatColor.RED, G, C, EntityType.SPIDER, 0.55, 10, 3, 0.3, 30, 1.0f, "A spindly crab that scuttles over cave walls and leaps at anything that moves.");
        k("glowgrub", "Glowgrub", ChatColor.YELLOW, P, C, EntityType.FROG, 0.6, 4, 0, 0, 25, 0.8f, "A soft grub that glows. Stay near one and its light shows you the way.");
        k("shellsnake", "Shellsnake", ChatColor.DARK_AQUA, G, C, EntityType.DROWNED, 1.4, 40, 8, 0.25, 12, 2.0f, "Coils inside cracks in the rock, waiting. Then it strikes, all teeth and armour plates.");
        // ---- the abyss
        k("lampsquid", "Lampsquid", ChatColor.AQUA, N, A, EntityType.SQUID, 1.7, 40, 0, 0, 10, 2.0f, "A squid with lanterns for eyes. Anger it and it sends out a pulse that kills every light: your night vision, your conduit, everything.");
        k("volteel", "Volt Eel", ChatColor.YELLOW, G, A, EntityType.DROWNED, 1.3, 36, 5, 0.25, 12, 2.0f, "A long eel crackling with power. Get close and it discharges through the water.");
        k("needlejaw", "Needlejaw", ChatColor.DARK_PURPLE, G, A, EntityType.DROWNED, 0.9, 20, 6, 0.32, 18, 1.4f, "A black fish with a lure of light and teeth like needles. Fast.");
        k("devilfish", "Masked Devilfish", ChatColor.DARK_RED, G, A, EntityType.DROWNED, 1.15, 50, 8, 0.26, 8, 1.7f, "Wears a mask of bone that turns most hits aside. Crack the mask first.");
        k("riftwarper", "Rift Warper", ChatColor.LIGHT_PURPLE, G, A, EntityType.DROWNED, 1.2, 40, 6, 0.27, 5, 1.8f, "It blinks through the water, and its bolts throw you somewhere else.");
        k("hollowkin", "Hollow Kin", ChatColor.DARK_GRAY, N, A, EntityType.DROWNED, 0.95, 18, 4, 0.24, 14, 1.4f, "Shadows of children, drifting in the dark. They shy away from light. Hurt one and they all come for you.");
        k("sharkquid", "Sharkquid", ChatColor.BLUE, G, A, EntityType.DROWNED, 1.3, 34, 7, 0.3, 12, 1.9f, "Half shark, half squid, all hunger. It charges, and inks you when it's hurt.");
    }

    final FaultlineDeep pl;
    final Random random = new Random();
    final NamespacedKey eggKey;
    final Map<UUID, Beast> live = new HashMap<>();
    private int ticks;

    /** A live creature. */
    final class Beast {
        final Kind k;
        final LivingEntity body;
        ItemDisplay model;
        ArmorStand stand;
        UUID angryAt;
        long angryUntil;
        int t, cd, maskHits = 3;
        float sentYaw = 9999, sentPitch = 9999;
        boolean buried, inked, fused;
        Beast(Kind k, LivingEntity body) { this.k = k; this.body = body; }
        boolean angry() { return angryAt != null && ticks < angryUntil; }
        Player angryPlayer() { return angryAt == null ? null : Bukkit.getPlayer(angryAt); }
    }

    DeepMobs(FaultlineDeep pl) {
        this.pl = pl;
        eggKey = new NamespacedKey(pl, "deep_egg");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 2L, 2L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("mobs." + path, def); }

    static boolean ours(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(TAG) || t.contains(PART) || t.contains(STAND);
    }

    // =========================================================================================== spawning one
    static ItemStack modelItem(String id) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "deep/" + id));
        it.setItemMeta(m);
        return it;
    }

    Beast spawn(Kind k, Location at) {
        World w = at.getWorld();
        Entity e = w.spawnEntity(at, k.base(), false); // by entity type (Paper 26.2 can't spawn some old classes by class)
        if (!(e instanceof LivingEntity body)) { e.remove(); return null; }
        body.addScoreboardTag(TAG);
        body.addScoreboardTag("faultline_deep_" + k.id());
        body.setPersistent(false);
        body.setRemoveWhenFarAway(true);
        body.setInvisible(true);
        body.setSilent(true);
        body.setCanPickupItems(false);
        body.setCustomName(k.color() + k.name());
        body.setCustomNameVisible(false);
        attr(body, Attribute.MAX_HEALTH, k.health());
        body.setHealth(k.health());
        attr(body, Attribute.SCALE, k.scale());
        if (k.damage() > 0) attr(body, Attribute.ATTACK_DAMAGE, k.damage());
        if (k.speed() > 0) attr(body, Attribute.MOVEMENT_SPEED, k.speed());
        if (body.getEquipment() != null) {
            body.getEquipment().clear();
            for (EquipmentSlot s : EquipmentSlot.values()) try { body.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
        }
        if (body instanceof Zombie z) { z.setAdult(); try { z.setShouldBurnInDay(false); } catch (Throwable ignored) { } }
        if (k.id().equals("clam") && body instanceof Mob m2) m2.setAI(false);
        Beast mob = new Beast(k, body);
        mob.model = w.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(modelItem(k.id()));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.addScoreboardTag(PART);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setTeleportDuration(2);
            d.setInterpolationDuration(3);
            d.setViewRange(1.2f);
            d.setShadowRadius(0);
        });
        body.addPassenger(mob.model);
        mob.stand = w.spawn(at, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
            a.addScoreboardTag(STAND);
            a.setInvisible(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setMarker(false); // Geyser draws the helmet only on a real armor stand
            a.getEquipment().setHelmet(modelItem(k.id()));
        });
        attr(mob.stand, Attribute.SCALE, Math.max(0.0625, Math.min(16, k.modelScale())));
        for (Player p : w.getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, mob.stand);
        if (k.id().equals("burrowshark") && body instanceof Mob m3) { m3.setAI(false); mob.buried = true; }
        if (k.id().equals("shellsnake") && body instanceof Mob m4) { m4.setAI(false); mob.buried = true; }
        live.put(body.getUniqueId(), mob);
        pose(mob, true);
        return mob;
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    /** The model's pose: centred on the body, turned to where it faces (yaw, and pitch as it swims up or down). */
    void pose(Beast m, boolean force) {
        if (m.model == null || !m.model.isValid()) return;
        Location l = m.body.getLocation();
        float yaw = l.getYaw(), pitch = Math.max(-60, Math.min(60, l.getPitch()));
        if (m.buried) pitch = 0;
        if (!force && Math.abs(angle(yaw - m.sentYaw)) < 4 && Math.abs(pitch - m.sentPitch) < 8) return;
        m.sentYaw = yaw; m.sentPitch = pitch;
        double h = m.body.getHeight();
        float s = m.k.modelScale() * (m.fused ? 1.5f : 1f);
        // the models are authored like the wild bosses' parts (+x = front): Wild's Q0 (local -> display) and the half
        // turn item displays draw items with (tools/deep_assets.py)
        Quaternionf q = new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(-90)).rotateY((float) Math.PI);
        float down = (float) (-h * 0.5 - (m.buried ? h * 0.7 : 0));
        m.model.setInterpolationDelay(0);
        m.model.setTransformation(new Transformation(new Vector3f(0, down, 0), q, new Vector3f(s, s, s), new Quaternionf()));
    }

    static float angle(float d) { return ((d % 360) + 540) % 360 - 180; }

    // =========================================================================================== every 2 ticks
    void tick() {
        ticks += 2;
        if (live.isEmpty()) { if (ticks % 30 == 0) spawner(); return; }
        for (var it = live.values().iterator(); it.hasNext(); ) {
            Beast m = it.next();
            if (!m.body.isValid() || m.body.isDead()) { cleanup(m); it.remove(); continue; }
            try { tickOne(m); } catch (RuntimeException ex) { pl.getLogger().warning("[Deep] " + m.k.id() + ": " + ex); }
        }
        if (ticks % 30 == 0) spawner();
        if (ticks % 100 == 0) farAway();
        if (ticks % 40 == 0) bedrockUpkeep();
    }

    void cleanup(Beast m) {
        if (m.model != null && m.model.isValid()) m.model.remove();
        if (m.stand != null && m.stand.isValid()) m.stand.remove();
    }

    void remove(Beast m) { cleanup(m); live.remove(m.body.getUniqueId()); if (m.body.isValid()) m.body.remove(); }

    private void tickOne(Beast m) {
        m.t += 2;
        if (m.cd > 0) m.cd -= 2;
        pose(m, false);
        if (m.stand != null && m.stand.isValid()) {
            Location l = m.body.getLocation();
            // the Bedrock model is centred on the stand's feet, so the stand stands in the body's middle
            Location sl = l.clone().add(0, m.body.getHeight() * 0.5 - (m.buried ? m.body.getHeight() * 0.7 : 0), 0);
            sl.setPitch(0);
            m.stand.teleport(sl);
        }
        if (m.t % 10 != 0) return;
        Player target = target(m);
        World w = m.body.getWorld();
        Location c = m.body.getLocation().add(0, m.body.getHeight() / 2, 0);
        switch (m.k.id()) {
            case "jellywing" -> { if (m.t % 20 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.6, 0.2, 0.6, 0); }
            case "driftback" -> { if (m.t % 40 == 0) { w.spawnParticle(Particle.BUBBLE, c, 12, 2, 1, 2, 0.05); if (random.nextInt(4) == 0) w.playSound(c, Sound.ENTITY_DOLPHIN_AMBIENT_WATER, SoundCategory.NEUTRAL, 2f, 0.4f); } }
            case "clam" -> {
                Player a = m.angryPlayer();
                if (m.angry() && a != null && a.getLocation().distanceSquared(c) < 3.5 * 3.5 && m.cd <= 0 && fair(a)) {
                    m.cd = 30;
                    a.damage(cfg("clam.bite", 5), m.body);
                    w.playSound(c, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 1f, 1.4f);
                    snap(m);
                }
            }
            case "lumenbell" -> {
                if (m.t % 40 == 0) w.spawnParticle(Particle.GLOW, c, 4, 0.3, 0.6, 0.3, 0);
                for (Player p : near(c, 1.6)) if (m.cd <= 0 && fair(p)) {
                    m.cd = 20; p.damage(cfg("lumenbell.sting", 2), m.body);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                    w.spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
                }
                if (m.angry() && m.t % 60 == 0) shock(m, c, 3, cfg("lumenbell.shock", 4));
            }
            case "gasbloom" -> {
                Player p = target != null ? target : nearest(c, 3);
                if (p != null && m.cd <= 0 && (m.angry() || random.nextInt(4) == 0)) { m.cd = 80; gas(m, c); }
            }
            case "fangstalker" -> {
                if (!m.angry() && m.t % 40 == 0) for (Player p : near(c, 10)) if (fair(p) && metal(p.getInventory().getItemInMainHand()) && random.nextDouble() < 0.12) {
                    anger(m, p, 600);
                    p.sendActionBar(ChatColor.GRAY + "A Fangstalker has seen your " + pretty(p.getInventory().getItemInMainHand().getType()) + "...");
                }
            }
            case "blastfin" -> {
                if (target != null && !m.fused && target.getLocation().distanceSquared(c) < 2.4 * 2.4) {
                    m.fused = true; pose(m, true);
                    w.playSound(c, Sound.ENTITY_PUFFER_FISH_BLOW_UP, SoundCategory.HOSTILE, 1.2f, 0.8f);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (!m.body.isValid()) return;
                        Location at = m.body.getLocation();
                        remove(m);
                        at.getWorld().createExplosion(at, (float) cfg("blastfin.power", 2.4), false, false);
                    }, 16L);
                }
            }
            case "burrowshark", "shellsnake" -> {
                if (m.buried) {
                    if (m.t % 40 == 0) w.spawnParticle(Particle.BLOCK, c.clone().add(0, -0.6, 0), 4, 0.4, 0.1, 0.4, 0, m.body.getLocation().add(0, -1, 0).getBlock().getBlockData());
                    Player p = nearest(c, m.k.id().equals("burrowshark") ? 9 : 6);
                    if (p != null && fair(p)) erupt(m, p);
                }
            }
            case "cavecrawler" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 25 && m.body.isOnGround()) {
                    m.cd = 40;
                    Vector v = target.getLocation().toVector().subtract(m.body.getLocation().toVector());
                    Edition.vel(m.body, v.normalize().multiply(0.8).setY(0.45));
                    w.playSound(c, Sound.ENTITY_SPIDER_STEP, SoundCategory.HOSTILE, 1f, 1.6f);
                }
            }
            case "glowgrub" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.3, 0.2, 0.3, 0);
                for (Player p : near(c, cfg("glowgrub.light-radius", 7)))
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, true));
            }
            case "lampsquid" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.GLOW, c, 2, 0.4, 0.4, 0.4, 0);
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a) && a.getLocation().distanceSquared(c) < 64) {
                    if (m.cd <= 0) { m.cd = 120; emp(m, c); }
                    else if (m.t % 30 == 0) dash(m, a, 0.7);
                }
            }
            case "volteel" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, c, 4, 0.5, 0.3, 0.5, 0.02);
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 4.5 * 4.5) { m.cd = 60; shock(m, c, 4.5, cfg("volteel.shock", 4)); }
            }
            case "needlejaw" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.END_ROD, c.clone().add(m.body.getLocation().getDirection().multiply(0.8)), 1, 0, 0, 0, 0);
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 49) { m.cd = 50; dash(m, target, 0.9); }
            }
            case "riftwarper" -> {
                if (target != null && m.cd <= 0) {
                    m.cd = 80;
                    blink(m, target);
                    if (target.getLocation().distanceSquared(c) < 144 && m.body.hasLineOfSight(target)) warpBolt(m, target);
                }
            }
            case "hollowkin" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.SMOKE, c, 3, 0.3, 0.5, 0.3, 0.01);
                Player lit = null;
                for (Player p : near(c, 8)) if (holdsLight(p)) { lit = p; break; }
                if (lit != null && !m.angry()) { // shy away from the light
                    Vector away = c.toVector().subtract(lit.getLocation().toVector());
                    Edition.vel(m.body, away.lengthSquared() < 0.01 ? new Vector(0, 0.2, 0) : away.normalize().multiply(0.35));
                }
            }
            case "sharkquid" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 100) { m.cd = 60; dash(m, target, 1.0); w.playSound(c, Sound.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 1f, 1.4f); }
                if (!m.inked && m.body.getHealth() < m.k.health() * 0.5) {
                    m.inked = true;
                    w.spawnParticle(Particle.SQUID_INK, c, 60, 1.5, 1.5, 1.5, 0.05);
                    for (Player p : near(c, 4.5)) if (fair(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                }
            }
            default -> { }
        }
        // a neutral one whose grudge ran out calms down
        if (m.k.temper() == Temper.NEUTRAL && m.angryAt != null && !m.angry()) {
            m.angryAt = null;
            if (m.body instanceof Mob mb) mb.setTarget(null);
        }
    }

    // ---------- abilities
    void snap(Beast m) {
        if (m.model == null) return;
        m.fused = true; pose(m, true);
        Bukkit.getScheduler().runTaskLater(pl, () -> { m.fused = false; pose(m, true); }, 6L);
    }

    void shock(Beast m, Location c, double r, double dmg) {
        World w = c.getWorld();
        w.spawnParticle(Particle.ELECTRIC_SPARK, c, 70, r * 0.6, r * 0.4, r * 0.6, 0.2);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 0.6f, 1.8f);
        for (Player p : near(c, r)) if (fair(p) && p.isInWater()) {
            p.damage(dmg, m.body);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        }
    }

    void gas(Beast m, Location c) {
        World w = c.getWorld();
        w.playSound(c, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, SoundCategory.HOSTILE, 1.4f, 0.6f);
        for (int i = 0; i < 3; i++) {
            Location at = c.clone().add(random.nextGaussian() * 1.5, random.nextGaussian() * 0.6, random.nextGaussian() * 1.5);
            w.spawn(at, AreaEffectCloud.class, cl -> {
                cl.setRadius(2.2f);
                cl.setDuration(100);
                cl.setRadiusPerTick(-0.01f);
                cl.setColor(Color.fromRGB(110, 170, 40));
                cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);
                cl.addScoreboardTag(PART);
            });
        }
    }

    void erupt(Beast m, Player p) {
        m.buried = false;
        if (m.body instanceof Mob mb) { mb.setAI(true); mb.setTarget(p); }
        Location c = m.body.getLocation();
        World w = c.getWorld();
        w.spawnParticle(Particle.BLOCK, c, 50, 1, 0.5, 1, 0.1, c.clone().add(0, -1, 0).getBlock().getBlockData());
        w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 0.8f, 1.7f);
        Edition.vel(m.body, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.0).setY(0.5));
        pose(m, true);
    }

    void dash(Beast m, Player p, double speed) {
        Vector v = p.getLocation().add(0, 0.8, 0).toVector().subtract(m.body.getLocation().toVector());
        if (v.lengthSquared() < 0.01) return;
        Edition.vel(m.body, v.normalize().multiply(speed));
    }

    void emp(Beast m, Location c) {
        World w = c.getWorld();
        w.spawnParticle(Particle.FLASH, c, 1);
        w.spawnParticle(Particle.SONIC_BOOM, c, 1);
        w.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        for (Player p : near(c, 9)) if (fair(p)) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.removePotionEffect(PotionEffectType.CONDUIT_POWER);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
            p.sendActionBar(ChatColor.AQUA + "The Lampsquid's pulse snuffs out every light!");
        }
    }

    void blink(Beast m, Player t) {
        Location to = waterNear(t.getLocation(), 3, 6);
        if (to == null) return;
        World w = m.body.getWorld();
        w.spawnParticle(Particle.REVERSE_PORTAL, m.body.getLocation(), 30, 0.4, 0.6, 0.4, 0.05);
        m.body.teleport(to);
        w.spawnParticle(Particle.REVERSE_PORTAL, to, 30, 0.4, 0.6, 0.4, 0.05);
        w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 0.6f);
    }

    void warpBolt(Beast m, Player p) {
        Location to = waterNear(p.getLocation(), 5, 8);
        if (to == null) return;
        to.setYaw(p.getLocation().getYaw()); to.setPitch(p.getLocation().getPitch());
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
        p.teleport(to);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
        p.sendActionBar(ChatColor.LIGHT_PURPLE + "The Rift Warper's bolt throws you through the water!");
    }

    /** A water block (with water above it) between min and max blocks from l. */
    Location waterNear(Location l, double min, double max) {
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            Location c = l.clone().add(Math.cos(a) * d, random.nextGaussian() * 1.5, Math.sin(a) * d);
            if (c.getBlock().getType() == Material.WATER && c.clone().add(0, 1, 0).getBlock().getType() == Material.WATER) return c;
        }
        return null;
    }

    static boolean metal(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return (n.startsWith("IRON_") || n.startsWith("GOLDEN_") || n.startsWith("NETHERITE_") || n.startsWith("DIAMOND_"))
                && (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"))
                || it.getType() == Material.TRIDENT;
    }

    static final Set<Material> LIGHTS = EnumSet.of(Material.TORCH, Material.SOUL_TORCH, Material.LANTERN, Material.SOUL_LANTERN,
            Material.GLOWSTONE, Material.SEA_LANTERN, Material.SEA_PICKLE, Material.GLOW_BERRIES, Material.SHROOMLIGHT,
            Material.JACK_O_LANTERN, Material.END_ROD, Material.GLOW_INK_SAC, Material.LIGHT);

    static boolean holdsLight(Player p) {
        return LIGHTS.contains(p.getInventory().getItemInMainHand().getType()) || LIGHTS.contains(p.getInventory().getItemInOffHand().getType());
    }

    static String pretty(Material m) { return m.name().toLowerCase().replace('_', ' '); }

    // ---------- who's around
    static boolean fair(Player p) { return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead(); }

    List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        return out;
    }

    Player nearest(Location c, double r) {
        Player best = null; double bd = r * r;
        for (Player p : c.getWorld().getPlayers()) { double d = p.getLocation().distanceSquared(c); if (d <= bd && fair(p)) { bd = d; best = p; } }
        return best;
    }

    /** Who it's after: an aggressive one hunts the nearest fair player in 16; a neutral one only whoever angered it. */
    Player target(Beast m) {
        if (m.k.temper() == Temper.PASSIVE) return null;
        if (m.k.temper() == Temper.NEUTRAL) { Player a = m.angryPlayer(); return m.angry() && a != null && fair(a) ? a : null; }
        if (m.body instanceof Mob mb && mb.getTarget() instanceof Player p && fair(p)) return p;
        Player p = nearest(m.body.getLocation(), 16);
        if (p != null && m.body instanceof Mob mb && !m.buried) mb.setTarget(p);
        return p;
    }

    void anger(Beast m, Player p, int ticksLong) {
        m.angryAt = p.getUniqueId();
        m.angryUntil = ticks + ticksLong;
        if (m.body instanceof Mob mb && !m.k.id().equals("clam")) mb.setTarget(p);
    }

    // =========================================================================================== the spawner
    /** Every 1.5 s: each player in the Dark Ocean with fewer than mobs.cap creatures around gets one more, for where they are. */
    void spawner() {
        World w = pl.world;
        if (w == null || !pl.getConfig().getBoolean("mobs.enabled", true)) return;
        int cap = (int) cfg("cap", 14);
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            int around = 0;
            for (Beast m : live.values()) if (m.body.getWorld().equals(w) && m.body.getLocation().distanceSquared(l) < 56 * 56) around++;
            if (around >= cap) continue;
            Location at = spawnSpot(l);
            if (at == null) continue;
            DeepGenerator.Zone z = pl.zone(at);
            if (z == DeepGenerator.Zone.ABYSS && random.nextDouble() < cfg("vanilla.guardian-chance", 0.06)) { w.spawnEntity(at, random.nextDouble() < cfg("vanilla.elder-share", 0.08) ? EntityType.ELDER_GUARDIAN : EntityType.GUARDIAN); continue; }
            Kind k = pick(z, at);
            if (k != null) spawn(k, at);
        }
    }

    /** A spot 14-40 blocks from l in open water (2 blocks of it), loaded. */
    Location spawnSpot(Location l) {
        World w = l.getWorld();
        for (int i = 0; i < 6; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = 14 + random.nextDouble() * 26;
            Location c = l.clone().add(Math.cos(a) * d, random.nextGaussian() * 8, Math.sin(a) * d);
            c.setY(Math.max(w.getMinHeight() + 4, Math.min(DeepGenerator.SEA - 2, c.getY())));
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            Block b = c.getBlock();
            if (b.getType() != Material.WATER || b.getRelative(0, 1, 0).getType() != Material.WATER) continue;
            return c;
        }
        return null;
    }

    Kind pick(DeepGenerator.Zone z, Location at) {
        boolean floor = at.clone().add(0, -1, 0).getBlock().getType().isSolid();
        List<Kind> opts = new ArrayList<>();
        int total = 0;
        for (Kind k : KINDS.values()) {
            if (k.zone() != z) continue;
            if (!pl.getConfig().getBoolean("mobs." + k.id() + ".enabled", true)) continue;
            boolean needsFloor = k.id().equals("clam") || k.id().equals("burrowshark") || k.id().equals("cavecrawler") || k.id().equals("glowgrub") || k.id().equals("shellsnake");
            if (needsFloor != floor && needsFloor) continue;
            if (k.id().equals("driftback")) { long n = live.values().stream().filter(m -> m.k == k).count(); if (n >= cfg("driftback.max", 2)) continue; }
            int wgt = (int) cfg(k.id() + ".weight", k.weight());
            if (wgt <= 0) continue;
            opts.add(k); total += wgt;
        }
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Kind k : opts) { r -= (int) cfg(k.id() + ".weight", k.weight()); if (r < 0) return k; }
        return null;
    }

    /** Ones nobody is near any more go (they're not saved anyway). */
    void farAway() {
        for (Beast m : new ArrayList<>(live.values())) {
            Location l = m.body.getLocation();
            boolean anyone = false;
            for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 100 * 100) { anyone = true; break; }
            if (!anyone) remove(m);
        }
    }

    void bedrockUpkeep() {
        for (Beast m : live.values()) {
            if (m.stand == null || !m.stand.isValid()) continue;
            for (Player p : m.stand.getWorld().getPlayers()) {
                if (Edition.bedrock(p)) { if (!p.canSee(m.stand)) p.showEntity(pl, m.stand); }
                else if (p.canSee(m.stand)) p.hideEntity(pl, m.stand);
            }
        }
    }

    // =========================================================================================== events
    Beast mobOf(Entity e) {
        Beast m = live.get(e.getUniqueId());
        if (m != null) return m;
        if (e.getScoreboardTags().contains(STAND)) for (Beast x : live.values()) if (e.equals(x.stand)) return x;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Beast m = live.get(e.getEntity().getUniqueId());
        if (m == null) return;
        if (e.getTarget() == null) return;
        if (!(e.getTarget() instanceof Player p) || !fair(p)) { e.setCancelled(true); return; }
        if (m.buried) { e.setCancelled(true); return; }
        switch (m.k.temper()) {
            case PASSIVE -> e.setCancelled(true);
            case NEUTRAL -> { if (!m.angry() || !p.getUniqueId().equals(m.angryAt)) e.setCancelled(true); }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHurt(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        if (ent.getScoreboardTags().contains(STAND)) { // a Bedrock player hit the model: hit the creature
            e.setCancelled(true);
            Beast m = mobOf(ent);
            if (m != null && e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && fair(p)) m.body.damage(e.getDamage(), p);
            return;
        }
        if (ent.getScoreboardTags().contains(PART)) { e.setCancelled(true); return; }
        Beast m = live.get(ent.getUniqueId());
        if (m == null) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.DROWNING || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || e.getCause() == EntityDamageEvent.DamageCause.DRYOUT) { e.setCancelled(true); return; }
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p) by = p;
            else if (ee.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player s) by = s;
            if (ee.getDamager() != null && ours(ee.getDamager())) { e.setCancelled(true); return; } // they never hurt each other
        }
        if (m.k.id().equals("devilfish") && m.maskHits > 0) { // the bone mask turns most of it aside
            e.setDamage(e.getDamage() * (1 - cfg("devilfish.mask-reduction", 0.7)));
            if (by != null && e.getDamage() >= 1.2) {
                m.maskHits--;
                World w = ent.getWorld();
                w.playSound(ent.getLocation(), m.maskHits == 0 ? Sound.ENTITY_ITEM_BREAK : Sound.BLOCK_BONE_BLOCK_HIT, SoundCategory.HOSTILE, 1.2f, 0.8f);
                if (m.maskHits == 0) { w.spawnParticle(Particle.BLOCK, ent.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0, Material.BONE_BLOCK.createBlockData()); by.sendActionBar(ChatColor.GOLD + "You cracked the Devilfish's mask!"); }
            }
        }
        if (by != null) {
            if (m.buried) erupt(m, by);
            if (m.k.temper() == Temper.NEUTRAL) {
                anger(m, by, 400);
                if (m.k.id().equals("hollowkin")) for (Beast o : live.values()) // hurt one and they all come
                    if (o.k == m.k && o.body.getLocation().distanceSquared(ent.getLocation()) < 18 * 18) anger(o, by, 400);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent e) {
        Beast m = live.get(e.getDamager().getUniqueId());
        if (m == null || !(e.getEntity() instanceof Player p)) return;
        if (m.k.temper() == Temper.PASSIVE) { e.setCancelled(true); return; }
        if (m.k.id().equals("hollowkin")) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0));
        if (m.k.id().equals("needlejaw")) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 0));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Beast m = live.remove(e.getEntity().getUniqueId());
        if (m == null) return;
        cleanup(m);
        e.getDrops().clear();
        e.setDroppedExp((int) Math.max(1, m.k.health() / 4));
        Location at = e.getEntity().getLocation();
        if (m.k.id().equals("bloatfin")) { // its air sac bursts
            at.getWorld().spawnParticle(Particle.BUBBLE_POP, at, 40, 1.2, 1, 1.2, 0.1);
            for (Player p : near(at, 4.5)) { p.setRemainingAir(p.getMaximumAir()); p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 300, 0)); }
        }
        Player killer = e.getEntity().getKiller();
        drops(m.k, e.getDrops(), killer);
    }

    /** Each creature's loot: a little vanilla stuff, its rare bits (through the other plugins' commands), a Dark Loot Bag now and then. */
    void drops(Kind k, List<ItemStack> drops, Player killer) {
        switch (k.id()) {
            case "gawker" -> drops.add(new ItemStack(Material.COD, 1 + random.nextInt(2)));
            case "bloatfin" -> drops.add(new ItemStack(Material.SALMON, 1));
            case "jellywing" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 1 + random.nextInt(2))); rare(killer, "jellywing.sirens-comb", 0.015, "coralia item sirens_comb 1 %p"); }
            case "driftback" -> { drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 6 + random.nextInt(8))); drops.add(new ItemStack(Material.SEA_LANTERN, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.NAUTILUS_SHELL, 1 + random.nextInt(2))); rare(killer, "driftback.bag", 0.15, "darkloot bag 1 %p"); }
            case "clam" -> {
                drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(3)));
                rare(killer, "clam.shield-of-the-ocean", 0.01, "giveaccessory shield 1 %p");
                rare(killer, "clam.glistening-pearl", 0.02, "grandmaw item glistening_pearl 1 %p");
            }
            case "lumenbell" -> drops.add(new ItemStack(Material.GLOW_INK_SAC, 1));
            case "gasbloom" -> { drops.add(new ItemStack(Material.SLIME_BALL, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.FERMENTED_SPIDER_EYE, 1)); }
            case "fangstalker" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(3))); rare(killer, "fangstalker.abyssal-lure", 0.01, "leviathan item abyssal_lure 1 %p"); }
            case "blastfin" -> drops.add(new ItemStack(Material.GUNPOWDER, 1 + random.nextInt(2)));
            case "burrowshark" -> { drops.add(new ItemStack(Material.SAND, 2 + random.nextInt(4))); drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); }
            case "cavecrawler" -> drops.add(new ItemStack(Material.STRING, 1 + random.nextInt(2)));
            case "glowgrub" -> drops.add(new ItemStack(Material.GLOW_BERRIES, 1 + random.nextInt(3)));
            case "shellsnake" -> { drops.add(new ItemStack(Material.TURTLE_SCUTE, 1)); drops.add(new ItemStack(Material.NAUTILUS_SHELL, random.nextInt(2))); }
            case "lampsquid" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 2 + random.nextInt(3))); rare(killer, "lampsquid.inkjaw-bait", 0.02, "inkjaw item inkjaw_bait 1 %p"); }
            case "volteel" -> { drops.add(new ItemStack(Material.COPPER_INGOT, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.REDSTONE, 2 + random.nextInt(3))); }
            case "needlejaw" -> drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(2)));
            case "devilfish" -> { drops.add(new ItemStack(Material.BONE_BLOCK, 1)); rare(killer, "devilfish.brine-bait", 0.01, "scourge item brine_bait 1 %p"); }
            case "riftwarper" -> { drops.add(new ItemStack(Material.ENDER_PEARL, 1 + random.nextInt(2))); rare(killer, "riftwarper.umbral-sonar", 0.01, "umbral item umbral_sonar 1 %p"); }
            case "hollowkin" -> { if (random.nextDouble() < 0.02) drops.add(new ItemStack(Material.ECHO_SHARD, 1)); rare(killer, "hollowkin.mournful-shell", 0.015, "lament item mournful_shell 1 %p"); }
            case "sharkquid" -> { drops.add(new ItemStack(Material.INK_SAC, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.BONE, 1)); }
            default -> { }
        }
        if (killer != null && k.zone() != DeepGenerator.Zone.SUNLIT) rare(killer, "dark-loot-bag", 0.01, "darkloot bag 1 %p");
        else if (killer != null) rare(killer, "dark-loot-bag-sunlit", 0.004, "darkloot bag 1 %p");
    }

    /** A rare drop: a console command for the killer (the item comes from the plugin that owns it). */
    void rare(Player killer, String path, double def, String cmd) {
        if (killer == null || random.nextDouble() >= cfg("drops." + path, def)) return;
        pl.console(cmd.replace("%p", killer.getName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (live.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onStandUse(PlayerInteractAtEntityEvent e) { if (ours(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onRob(PlayerArmorStandManipulateEvent e) { if (ours(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        for (Entity en : e.getChunk().getEntities()) {
            Beast m = live.get(en.getUniqueId());
            if (m != null) remove(m);
            else if (en.getScoreboardTags().contains(PART) || en.getScoreboardTags().contains(STAND)) en.remove();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean b = Edition.bedrock(p);
        for (Beast m : live.values()) if (m.stand != null && m.stand.isValid()) { if (b) p.showEntity(pl, m.stand); else p.hideEntity(pl, m.stand); }
    }

    /** In the Dark Ocean only sea life spawns on its own (no phantoms, no bats, nothing from the land). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaSpawn(CreatureSpawnEvent e) {
        if (pl.world == null || !e.getEntity().getWorld().equals(pl.world)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || r == CreatureSpawnEvent.SpawnReason.DEFAULT || r == CreatureSpawnEvent.SpawnReason.BUCKET || r == CreatureSpawnEvent.SpawnReason.BREEDING
                || r == CreatureSpawnEvent.SpawnReason.EGG) {
            if (e.getEntityType() != EntityType.PHANTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;
        }
        if (!SEA_LIFE.contains(e.getEntityType())) e.setCancelled(true);
    }

    static final Set<EntityType> SEA_LIFE = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH, EntityType.PUFFERFISH,
            EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL, EntityType.DROWNED,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.TADPOLE);

    // =========================================================================================== eggs (admins, /itemsmenu)
    ItemStack egg(Kind k) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(k.color() + "" + ChatColor.BOLD + k.name() + ChatColor.GRAY + " Spawn Egg");
        m.setLore(List.of(ChatColor.GRAY + k.temper().name().substring(0, 1) + k.temper().name().substring(1).toLowerCase() + ", " + zoneName(k.zone()),
                ChatColor.DARK_GRAY + "Right-click in water to release it."));
        m.setItemModel(new NamespacedKey("faultline", "deep/" + k.id() + "_icon"));
        m.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, k.id());
        it.setItemMeta(m);
        return it;
    }

    static String zoneName(DeepGenerator.Zone z) {
        return switch (z) { case SUNLIT -> "the Sunlit Sea"; case CAVES -> "the caves of the Crust"; case ABYSS -> "the Abyss"; };
    }

    String eggOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
    }

    void shutdown() {
        for (Beast m : new ArrayList<>(live.values())) remove(m);
        live.clear();
    }
}
