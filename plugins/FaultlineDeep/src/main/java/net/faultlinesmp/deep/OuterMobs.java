package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The outer ring's creatures (Deep 1.9.0), kept out of DeepMobs' giant switches: what they do (tick), how they take a
 * hit (hurt), what their bite does (bite), what they drop (drops), what happens when they die (died: the Glass Reef's
 * creatures SHATTER), the Drowned City's people's clothes (dress), MUTATIONS (any normal creature, `mobs.mutated-chance`
 * 3%: twice the health, harder and faster, a coloured glow, double drops and experience, Index stat mutated_sea), and the
 * two mini bosses' fights:
 *  - THE RIMEBACK (Frozen Shelf): Frost Breath (a freezing cone), Ice Ram, Glacier Fall (blocks of ice dropped from above,
 *    shadowed first), the Pod (frost orcas) and, below half, Freeze Over (a freezing ring) and faster moves
 *  - THE DROWNED KING (the palace): Trident Volley, Tidal Slam (a shockwave), the Royal Guard (guards come running), the
 *    King's Decree (everyone is dragged to kneel before him) and the Whirl; below half his priests come too
 * Both have a boss bar for everyone within 48. Their deaths are announced, give loot through console commands and Index
 * stats (rimeback / drowned_king), and the King drops three Drowned Vault Keys.
 */
final class OuterMobs {
    final FaultlineDeep pl;
    final DeepMobs mobs;
    final Random random = new Random();
    boolean extra;                                           // spawning a school / pair / summon: no more extras off it
    /** The mini bosses' summons, so they're capped. */
    final Set<java.util.UUID> summoned = new HashSet<>();

    static final Set<String> NO_MUTATE = Set.of("goblin", "rimeback", "drownking", "mermaid", "merguard", "greatwhale", "prismfish", "iceseal", "hadalsnail");

    OuterMobs(FaultlineDeep pl, DeepMobs mobs) { this.pl = pl; this.mobs = mobs; }

    double cfg(String path, double def) { return pl.getConfig().getDouble("mobs." + path, def); }

    // =========================================================================================== spawning
    void spawned(DeepMobs.Beast m) {
        String id = m.k.id();
        if (DeepMobs.PLAIN.contains(id)) dress(m);
        if (!extra) {
            extra = true;
            try {
                if (id.equals("prismfish")) for (int i = 0; i < 3 + random.nextInt(3); i++) school(m, 1.6);   // schools
                if (id.equals("frostorca") && random.nextDouble() < 0.6) school(m, 3);                      // they hunt in pairs
                if (id.equals("iceseal") && random.nextDouble() < 0.5) school(m, 2.5);
            } finally { extra = false; }
        }
        if (id.equals("rimeback") || id.equals("drownking")) {
            m.bar = Bukkit.createBossBar((id.equals("rimeback") ? ChatColor.AQUA : ChatColor.GOLD) + "" + ChatColor.BOLD + m.k.name(),
                    id.equals("rimeback") ? BarColor.BLUE : BarColor.YELLOW, BarStyle.SEGMENTED_10);
        }
        if (!NO_MUTATE.contains(id) && !DeepMobs.RAIN.contains(id) && m.k.weight() > 0 && random.nextDouble() < cfg("mutated-chance", 0.03)) mutate(m);
    }

    void school(DeepMobs.Beast m, double spread) {
        Location o = m.body.getLocation().add(random.nextGaussian() * spread, random.nextGaussian() * spread * 0.4, random.nextGaussian() * spread);
        if (o.getBlock().getType() == Material.WATER) mobs.spawn(m.k, o);
    }

    void mutate(DeepMobs.Beast m) {
        m.mutated = true;
        LivingEntity b = m.body;
        AttributeInstance hp = b.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) { hp.setBaseValue(hp.getBaseValue() * cfg("mutated.health", 2.0)); b.setHealth(hp.getBaseValue()); }
        AttributeInstance dmg = b.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * cfg("mutated.damage", 1.5));
        AttributeInstance sp = b.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.2);
        b.setCustomName(ChatColor.DARK_RED + "Mutated " + m.k.color() + m.k.name());
        Color c = switch (random.nextInt(3)) { case 0 -> Color.fromRGB(255, 40, 40); case 1 -> Color.fromRGB(190, 60, 255); default -> Color.fromRGB(80, 255, 120); };
        try {
            if (m.model != null) { m.model.setGlowing(true); m.model.setGlowColorOverride(c); }
            for (var part : m.parts) { part.setGlowing(true); part.setGlowColorOverride(c); }
        } catch (RuntimeException ignored) { }
        if (DeepMobs.PLAIN.contains(m.k.id())) b.setGlowing(true);
    }

    // ---- the Drowned City's people
    static ItemStack leather(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(c); it.setItemMeta(lm); }
        return it;
    }

    void dress(DeepMobs.Beast m) {
        EntityEquipment eq = m.body.getEquipment();
        if (eq == null) return;
        switch (m.k.id()) {
            case "drowncitizen" -> {
                Color[] cloth = {Color.fromRGB(110, 70, 40), Color.fromRGB(70, 90, 110), Color.fromRGB(120, 110, 90), Color.fromRGB(90, 40, 40), Color.fromRGB(60, 80, 60)};
                Color c = cloth[random.nextInt(cloth.length)];
                eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, c));
                if (random.nextBoolean()) eq.setLeggings(leather(Material.LEATHER_LEGGINGS, cloth[random.nextInt(cloth.length)]));
                if (random.nextInt(3) == 0) eq.setHelmet(leather(Material.LEATHER_HELMET, c));
                Material[] tools = {Material.AIR, Material.AIR, Material.FISHING_ROD, Material.WOODEN_SHOVEL, Material.LANTERN, Material.BOOK};
                eq.setItemInMainHand(new ItemStack(tools[random.nextInt(tools.length)]));
            }
            case "drownguard" -> {
                eq.setHelmet(new ItemStack(random.nextBoolean() ? Material.CHAINMAIL_HELMET : Material.IRON_HELMET));
                eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
                eq.setItemInMainHand(new ItemStack(Material.TRIDENT));
            }
            case "drownpriest" -> {
                Color robe = Color.fromRGB(70, 30, 100);
                eq.setHelmet(leather(Material.LEATHER_HELMET, robe));
                eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, robe));
                eq.setLeggings(leather(Material.LEATHER_LEGGINGS, robe));
                eq.setItemInMainHand(new ItemStack(Material.BOOK));
            }
            case "drownking" -> {
                eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));          // the crown
                eq.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                eq.setLeggings(leather(Material.LEATHER_LEGGINGS, Color.fromRGB(90, 20, 110)));
                eq.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
                eq.setItemInMainHand(new ItemStack(Material.TRIDENT));
                m.body.setCustomNameVisible(true);
            }
            default -> { }
        }
    }

    // =========================================================================================== every half second
    void tick(DeepMobs.Beast m, Player target, World w, Location c) {
        String id = m.k.id();
        if (m.mutated && m.t % 20 == 0) w.spawnParticle(Particle.DUST, c, 4, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(220, 40, 60), 1.4f));
        if (m.bar != null) bar(m, w);
        switch (id) {
            case "ossuaryray" -> { if (m.t % 40 == 0) w.spawnParticle(Particle.WHITE_ASH, c, 10, 1.6, 0.3, 1.6, 0); }
            case "glassjelly" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.END_ROD, c, 2, 0.4, 0.3, 0.4, 0.01);
                for (Player p : mobs.near(c, 1.8)) { p.damage(1.5, m.body); p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0)); }
            }
            case "prismfish" -> {
                if (m.t % 30 == 0) {
                    int[][] rb = {{255, 60, 60}, {255, 180, 40}, {250, 240, 60}, {80, 230, 100}, {70, 150, 255}, {170, 80, 255}};
                    int[] k = rb[random.nextInt(rb.length)];
                    w.spawnParticle(Particle.DUST, c, 3, 0.3, 0.2, 0.3, 0, new Particle.DustOptions(Color.fromRGB(k[0], k[1], k[2]), 1.0f));
                }
            }
            case "shardback" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 14 * 14) {
                    m.cd = 80;
                    w.playSound(c, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1f, 1.4f);
                    for (int i = 0; i < 3; i++) {
                        Vector dir = DeepMobs.unit(target.getEyeLocation().toVector().subtract(c.toVector())).add(new Vector(random.nextGaussian() * 0.08, random.nextGaussian() * 0.06, random.nextGaussian() * 0.08));
                        Arrow a = w.spawnArrow(c.clone().add(dir.clone().multiply(1.2)), dir, 1.5f, 0f);
                        a.setShooter(m.body);
                        a.setDamage(3);
                        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        a.addScoreboardTag(DeepMobs.TAG);
                    }
                }
            }
            case "narwhal" -> {
                if (m.angry() && target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 18 * 18) { // the tusk charge
                    m.cd = 50;
                    Edition.vel(m.body, DeepMobs.unit(target.getLocation().toVector().subtract(c.toVector())).multiply(1.4));
                    w.playSound(c, Sound.ENTITY_DOLPHIN_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.5f);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        for (Player p : mobs.near(m.body.getLocation(), 2.6)) { p.damage(m.k.damage(), m.body); Edition.vel(p, DeepMobs.unit(p.getLocation().toVector().subtract(m.body.getLocation().toVector())).multiply(0.9)); }
                    }, 8L);
                }
            }
            case "rimejelly" -> {
                for (Player p : mobs.near(c, 1.8)) { p.damage(1.5, m.body); freeze(p, 60); }
            }
            case "magmaeel", "cindercrab" -> { if (m.t % 20 == 0) w.spawnParticle(Particle.LAVA, c, 1, 0.2, 0.2, 0.2, 0); }
            case "drownpriest" -> {
                if (m.t % 100 == 0 && target != null && target.getLocation().distanceSquared(c) < 16 * 16) { // a curse
                    PotionEffectType[] curses = {PotionEffectType.SLOWNESS, PotionEffectType.LEVITATION, PotionEffectType.WEAKNESS, PotionEffectType.MINING_FATIGUE};
                    PotionEffectType t = curses[random.nextInt(curses.length)];
                    target.addPotionEffect(new PotionEffect(t, t == PotionEffectType.LEVITATION ? 40 : 100, t == PotionEffectType.SLOWNESS ? 1 : 0));
                    w.spawnParticle(Particle.WITCH, target.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0);
                    w.playSound(c, Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.HOSTILE, 1f, 0.6f);
                }
                if (m.t % 60 == 0) for (DeepMobs.Beast o : mobs.live.values()) { // mends the dead round it
                    if (o == m || !DeepMobs.PLAIN.contains(o.k.id()) || !o.body.getWorld().equals(w) || o.body.getLocation().distanceSquared(c) > 64) continue;
                    AttributeInstance mx = o.body.getAttribute(Attribute.MAX_HEALTH);
                    if (mx != null) o.body.setHealth(Math.min(mx.getValue(), o.body.getHealth() + 6));
                    w.spawnParticle(Particle.HAPPY_VILLAGER, o.body.getLocation().add(0, 1.5, 0), 4, 0.3, 0.3, 0.3, 0);
                }
            }
            case "rimeback" -> rimeback(m, target, w, c);
            case "drownking" -> king(m, target, w, c);
            default -> { }
        }
    }

    void bar(DeepMobs.Beast m, World w) {
        AttributeInstance mx = m.body.getAttribute(Attribute.MAX_HEALTH);
        double max = mx == null ? m.k.health() : mx.getValue();
        m.bar.setProgress(Math.max(0, Math.min(1, m.body.getHealth() / max)));
        Location l = m.body.getLocation();
        for (Player p : w.getPlayers()) {
            boolean near = p.getLocation().distanceSquared(l) < 48 * 48;
            if (near && !m.bar.getPlayers().contains(p)) m.bar.addPlayer(p);
            else if (!near && m.bar.getPlayers().contains(p)) m.bar.removePlayer(p);
        }
    }

    static void freeze(Player p, int ticks) {
        p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + ticks));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1));
    }

    double hpShare(DeepMobs.Beast m) {
        AttributeInstance mx = m.body.getAttribute(Attribute.MAX_HEALTH);
        return m.body.getHealth() / (mx == null ? m.k.health() : mx.getValue());
    }

    // ---- the Rimeback
    void rimeback(DeepMobs.Beast m, Player target, World w, Location c) {
        if (m.t % 20 == 0) w.spawnParticle(Particle.SNOWFLAKE, c, 12, 2, 1, 2, 0.02);
        if (target == null || m.cd > 0) return;
        boolean enraged = hpShare(m) < 0.5;
        int move = random.nextInt(enraged ? 5 : 4);
        m.cd = enraged ? 40 : 60;
        switch (move) {
            case 0 -> { // Frost Breath: a freezing cone
                Vector dir = DeepMobs.unit(target.getLocation().toVector().subtract(c.toVector()));
                w.playSound(c, Sound.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.HOSTILE, 2f, 0.5f);
                for (int i = 1; i <= 12; i++) w.spawnParticle(Particle.SNOWFLAKE, c.clone().add(dir.clone().multiply(i)), 6, i * 0.12, i * 0.12, i * 0.12, 0.02);
                for (Player p : mobs.near(c, 12)) {
                    Vector to = p.getLocation().toVector().subtract(c.toVector());
                    if (to.lengthSquared() < 1e-4 || DeepMobs.unit(to.clone()).dot(dir) > 0.8) { p.damage(6, m.body); freeze(p, 100); }
                }
            }
            case 1 -> { // Ice Ram
                w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
                Edition.vel(m.body, DeepMobs.unit(target.getLocation().toVector().subtract(c.toVector())).multiply(1.6));
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    for (Player p : mobs.near(m.body.getLocation(), 3.5)) { p.damage(m.k.damage(), m.body); Edition.vel(p, DeepMobs.unit(p.getLocation().toVector().subtract(m.body.getLocation().toVector())).multiply(1.2).setY(0.4)); }
                }, 10L);
            }
            case 2 -> { // Glacier Fall: blocks of ice from above, a shadow first
                for (Player p : mobs.near(c, 24)) {
                    Location at = p.getLocation().clone();
                    w.spawnParticle(Particle.DUST, at.clone().add(0, 0.2, 0), 30, 1.5, 0.05, 1.5, 0, new Particle.DustOptions(Color.fromRGB(150, 200, 255), 1.6f));
                    BlockDisplay ice = w.spawn(at.clone().add(-0.75, 9, -0.75), BlockDisplay.class, d -> {
                        d.setBlock(Material.PACKED_ICE.createBlockData());
                        d.setPersistent(false);
                        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf()));
                        d.setTeleportDuration(3);
                    });
                    for (int k = 1; k <= 6; k++) {
                        final int kk = k;
                        Bukkit.getScheduler().runTaskLater(pl, () -> { if (ice.isValid()) ice.teleport(at.clone().add(-0.75, 9 - kk * 1.5, -0.75)); }, 20L + k * 3);
                    }
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (ice.isValid()) ice.remove();
                        w.spawnParticle(Particle.BLOCK, at, 40, 1, 0.4, 1, 0, Material.PACKED_ICE.createBlockData());
                        w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                        for (Player q : mobs.near(at, 2.6)) { q.damage(8, m.body); freeze(q, 60); }
                    }, 40L);
                }
            }
            case 3 -> { // the Pod
                long mine = summoned.stream().map(Bukkit::getEntity).filter(e -> e != null && e.isValid()).count();
                if (mine >= 4) { m.cd = 0; return; }
                w.playSound(c, Sound.ENTITY_DOLPHIN_AMBIENT, SoundCategory.HOSTILE, 2f, 0.4f);
                summon("frostorca", c, 2);
            }
            default -> { // Freeze Over: a ring that freezes the water round it
                w.playSound(c, Sound.BLOCK_POWDER_SNOW_STEP, SoundCategory.HOSTILE, 3f, 0.4f);
                for (int i = 0; i < 48; i++) {
                    double a = i * Math.PI * 2 / 48;
                    w.spawnParticle(Particle.SNOWFLAKE, c.clone().add(Math.cos(a) * 9, 0, Math.sin(a) * 9), 3, 0.2, 0.6, 0.2, 0);
                }
                for (Player p : mobs.near(c, 10)) { p.damage(4, m.body); freeze(p, 140); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2)); }
            }
        }
    }

    void summon(String kind, Location c, int n) {
        DeepMobs.Kind k = DeepMobs.KINDS.get(kind);
        if (k == null) return;
        extra = true;
        try {
            for (int i = 0; i < n; i++) {
                Location o = c.clone().add(random.nextGaussian() * 3, random.nextGaussian(), random.nextGaussian() * 3);
                if (o.getBlock().getType() != Material.WATER) o = c.clone();
                DeepMobs.Beast b = mobs.spawn(k, o);
                if (b != null) summoned.add(b.body.getUniqueId());
            }
        } finally { extra = false; }
    }

    // ---- the Drowned King
    void king(DeepMobs.Beast m, Player target, World w, Location c) {
        if (m.t % 40 == 0) w.spawnParticle(Particle.DRIPPING_WATER, c.clone().add(0, 1.5, 0), 6, 0.6, 0.6, 0.6, 0);
        if (target == null || m.cd > 0) return;
        boolean enraged = hpShare(m) < 0.5;
        int move = random.nextInt(5);
        m.cd = enraged ? 40 : 60;
        switch (move) {
            case 0 -> { // Trident Volley
                w.playSound(c, Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 2f, 0.7f);
                for (Player p : mobs.near(c, 24)) for (int i = 0; i < (enraged ? 3 : 2); i++) {
                    Vector dir = DeepMobs.unit(p.getEyeLocation().toVector().subtract(c.toVector())).add(new Vector(random.nextGaussian() * 0.05, 0.02, random.nextGaussian() * 0.05));
                    Trident t = w.spawn(c.clone().add(dir.clone().multiply(1.5)), Trident.class, tr -> {
                        tr.setShooter(m.body);
                        tr.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        tr.setDamage(7);
                        tr.addScoreboardTag(DeepMobs.TAG);
                    });
                    Edition.vel(t, dir.multiply(1.8));
                    Bukkit.getScheduler().runTaskLater(pl, () -> { if (t.isValid()) t.remove(); }, 100L);
                }
            }
            case 1 -> { // Tidal Slam: a shockwave
                w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.5f);
                for (int r = 1; r <= 7; r++) {
                    final int rr = r;
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        for (int i = 0; i < 24; i++) { double a = i * Math.PI / 12; w.spawnParticle(Particle.BUBBLE_POP, c.clone().add(Math.cos(a) * rr, -0.8, Math.sin(a) * rr), 2, 0.1, 0.1, 0.1, 0.02); }
                    }, r * 2L);
                }
                for (Player p : mobs.near(c, 7)) { p.damage(7, m.body); Edition.vel(p, DeepMobs.unit(p.getLocation().toVector().subtract(c.toVector())).multiply(1.1).setY(0.5)); }
            }
            case 2 -> { // the Royal Guard
                long mine = summoned.stream().map(Bukkit::getEntity).filter(e -> e != null && e.isValid()).count();
                if (mine >= 6) { m.cd = 0; return; }
                w.playSound(c, Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 3f, 0.8f);
                for (Player p : mobs.near(c, 32)) p.sendMessage(ChatColor.GOLD + "The Drowned King: " + ChatColor.YELLOW + "Guards! To your king!");
                summon("drownguard", c, 3);
                if (enraged) summon("drownpriest", c, 1);
            }
            case 3 -> { // the King's Decree: everyone dragged before him
                for (Player p : mobs.near(c, 20)) {
                    p.sendTitle(ChatColor.GOLD + "KNEEL", ChatColor.GRAY + "before the Drowned King", 5, 25, 10);
                    Edition.vel(p, DeepMobs.unit(c.toVector().subtract(p.getLocation().toVector())).multiply(1.3));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                }
                w.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 2f, 0.6f);
            }
            default -> { // the Whirl
                w.playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.HOSTILE, 2f, 0.8f);
                w.spawnParticle(Particle.SWEEP_ATTACK, c, 12, 1.6, 0.4, 1.6, 0);
                for (Player p : mobs.near(c, 4.5)) { p.damage(5, m.body); Edition.vel(p, DeepMobs.unit(p.getLocation().toVector().subtract(c.toVector())).multiply(1.4).setY(0.3)); }
            }
        }
    }

    // =========================================================================================== hits taken, bites, deaths
    void hurt(DeepMobs.Beast m, EntityDamageEvent e, Player by) {
        String id = m.k.id();
        Entity ent = m.body;
        switch (id) {
            case "ossuaryray" -> {
                if (by != null && m.cd <= 0) { // the death rattle
                    m.cd = 60;
                    ent.getWorld().playSound(ent.getLocation(), Sound.ENTITY_SKELETON_HURT, SoundCategory.HOSTILE, 2f, 0.4f);
                    for (Player p : mobs.near(ent.getLocation(), 5)) { p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0)); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0)); }
                }
            }
            case "crystalcrab" -> { // the shell turns blows that don't come from behind
                if (e instanceof EntityDamageByEntityEvent ee) {
                    Vector face = m.body.getLocation().getDirection().setY(0), from = ee.getDamager().getLocation().toVector().subtract(ent.getLocation().toVector()).setY(0);
                    if (face.lengthSquared() > 1e-4 && from.lengthSquared() > 1e-4 && face.normalize().dot(from.normalize()) > -0.3) {
                        e.setDamage(e.getDamage() * 0.4);
                        ent.getWorld().playSound(ent.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.HOSTILE, 1f, 1.2f);
                    }
                }
            }
            case "narwhal", "ossuaryray2" -> { if (by != null) mobs.anger(m, by, 200); }
            case "cindercrab" -> {
                if (by != null && random.nextDouble() < 0.35) { // an ember burst
                    Location l = ent.getLocation();
                    l.getWorld().spawnParticle(Particle.LAVA, l, 12, 0.6, 0.4, 0.6, 0);
                    l.getWorld().spawnParticle(Particle.FLAME, l, 30, 1.2, 0.6, 1.2, 0.05);
                    l.getWorld().playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.HOSTILE, 1.2f, 0.7f);
                    for (Player p : mobs.near(l, 3)) p.damage(4, m.body);
                }
            }
            case "rimeback", "drownking" -> { if (by != null) mobs.anger(m, by, 600); }
            default -> { }
        }
    }

    void bite(DeepMobs.Beast m, Player p, EntityDamageByEntityEvent e) {
        switch (m.k.id()) {
            case "marroweel" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0)); p.sendActionBar(ChatColor.WHITE + "The Marrow Eel won't let go!"); }
            case "frostorca", "rimeback" -> freeze(p, 80);
            case "magmaeel" -> { e.setDamage(e.getDamage() + 2); p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0)); p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02); }
            case "drownguard" -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0));
            default -> { }
        }
        if (m.mutated) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
    }

    void died(DeepMobs.Beast m, Player killer, Location at, EntityDeathEvent e) {
        String id = m.k.id();
        World w = at.getWorld();
        if (id.equals("glassjelly") || id.equals("prismfish") || id.equals("crystalcrab") || id.equals("shardback")) { // it shatters
            w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.5f, 1.1f);
            w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 40, 0.6, 0.6, 0.6, 0, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            if (id.equals("glassjelly")) for (Player p : mobs.near(at, 3)) p.damage(3);
        }
        summoned.remove(m.body.getUniqueId());
        if (id.equals("rimeback") || id.equals("drownking")) {
            String who = killer != null ? killer.getName() : null;
            for (Player p : w.getPlayers()) p.sendMessage(m.k.color() + "" + ChatColor.BOLD + m.k.name() + ChatColor.GRAY + " has been slain" + (who != null ? " by " + ChatColor.WHITE + who : "") + ChatColor.GRAY + "!");
            List<Player> fighters = new ArrayList<>(mobs.near(at, 48));
            if (killer != null && !fighters.contains(killer)) fighters.add(killer);
            for (Player f : fighters) {
                String n = f.getName();
                pl.console("index discover " + n + " deep_" + id);
                pl.console("index stat " + n + " " + (id.equals("rimeback") ? "rimeback" : "drowned_king") + " 1");
                pl.console("darkloot bag " + (id.equals("rimeback") ? 3 : 2) + " " + n);
                if (random.nextDouble() < cfg(id + ".rare-chance", 0.25)) pl.console("darkloot rare " + n);
                if (id.equals("rimeback") && random.nextDouble() < 0.4) pl.console("fenchant give frostbite 2 1 " + n);
                f.giveExp(id.equals("rimeback") ? 1200 : 900);
            }
            if (pl.outer != null) pl.outer.bossDown(id);
        }
    }

    boolean drops(DeepMobs.Kind k, List<ItemStack> drops, Player killer) {
        switch (k.id()) {
            case "marroweel" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(3))); fossil(killer, 0.02); }
            case "scavcrab" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); fossil(killer, 0.1); }
            case "ossuaryray" -> { drops.add(new ItemStack(Material.BONE_BLOCK, 1 + random.nextInt(2))); fossil(killer, 0.05); }
            case "glassjelly" -> { drops.add(new ItemStack(Material.GLASS, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.GLOW_INK_SAC, 1)); }
            case "prismfish" -> drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 1 + random.nextInt(2)));
            case "crystalcrab" -> drops.add(new ItemStack(Material.AMETHYST_SHARD, 2 + random.nextInt(4)));
            case "shardback" -> { drops.add(new ItemStack(Material.TINTED_GLASS, 1)); drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(2))); }
            case "narwhal" -> { drops.add(new ItemStack(Material.BONE, 2)); if (random.nextDouble() < 0.04) drops.add(new ItemStack(Material.TRIDENT)); }
            case "iceseal" -> { drops.add(new ItemStack(Material.COD, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.LEATHER, 1)); }
            case "frostorca" -> { drops.add(new ItemStack(Material.LEATHER, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.SALMON, 1 + random.nextInt(2))); }
            case "rimejelly" -> { drops.add(new ItemStack(Material.SNOWBALL, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.ICE, 1)); }
            case "magmaeel" -> { drops.add(new ItemStack(Material.MAGMA_CREAM, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.BLAZE_POWDER, 1)); }
            case "cindercrab" -> { drops.add(new ItemStack(Material.GOLD_NUGGET, 2 + random.nextInt(4))); if (random.nextDouble() < 0.03) drops.add(new ItemStack(Material.ANCIENT_DEBRIS)); }
            case "drowncitizen" -> { drops.add(new ItemStack(Material.ROTTEN_FLESH, 1 + random.nextInt(2))); if (random.nextDouble() < 0.2) drops.add(new ItemStack(Material.COPPER_INGOT)); if (random.nextDouble() < 0.3) drops.add(new ItemStack(Material.GOLD_NUGGET, 1 + random.nextInt(3))); }
            case "drownguard" -> { drops.add(new ItemStack(Material.IRON_NUGGET, 2 + random.nextInt(4))); key(killer, drops, 0.08); }
            case "drownpriest" -> { drops.add(new ItemStack(Material.LAPIS_LAZULI, 2 + random.nextInt(3))); if (random.nextDouble() < 0.2) drops.add(new ItemStack(Material.EXPERIENCE_BOTTLE)); key(killer, drops, 0.05); }
            case "drownking" -> { drops.add(new ItemStack(Material.GOLD_BLOCK, 2)); for (int i = 0; i < 3; i++) key(killer, drops, 1.0); }
            case "rimeback" -> { drops.add(new ItemStack(Material.BLUE_ICE, 8)); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 8)); }
            default -> { return false; }
        }
        if (killer != null && DeepMobs.REGION.containsKey(k.id()) && random.nextDouble() < 0.01) pl.console("darkloot bag 1 " + killer.getName());
        return true;
    }

    void fossil(Player killer, double chance) {
        if (killer != null && pl.outer != null && random.nextDouble() < chance) pl.outer.giveFossil(killer, 1);
    }

    void key(Player killer, List<ItemStack> drops, double chance) {
        if (pl.outer != null && random.nextDouble() < chance) drops.add(pl.outer.vaultKey(1));
    }
}
