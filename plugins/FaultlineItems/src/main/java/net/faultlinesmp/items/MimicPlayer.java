package net.faultlinesmp.items;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * MIMIC PLAYER (Items 1.2.78, ADMIN ONLY: /mimicplayer, perm items.mimicplayer, default op; config mimic-player:).
 * Spawns an NPC that looks like a real player: a MANNEQUIN wearing a random offline player's skin and name (or the one
 * you name), in a full kit, that fights like a PvPer. Its brain is an invisible zombie (tag faultline_player_mimic, the
 * mannequin is faultline_player_mimic_body and is put on it every tick, facing where it faces): hits on either are the
 * zombie's. What it does: chases the nearest survival player, circle-strafes and W-taps in close, sprint-jumps for crits
 * (+50% while falling), blocks with its shield when you're in front and charging (axes knock the shield out for 5 s),
 * eats a golden apple under 40% health, throws an ender pearl at anyone over 14 blocks away, shoots its bow at 8-20
 * blocks, and pops one totem. Drops nothing (mimic-player.drops), never despawns on its own until killed or
 * /mimicplayer clear. Not saved (non-persistent): a restart removes them. Bedrock sees nothing of the mannequin if Geyser
 * can't draw one.
 */
final class MimicPlayer implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_player_mimic", BODY_TAG = "faultline_player_mimic_body";
    private final FaultlineItems plugin;
    private final Random random = new Random();

    static final class Mimic {
        Zombie brain;
        Mannequin body;
        String name;
        int t, strafeDir = 1, strafeFlip, blockUntil, shieldOffUntil, eatUntil, pearlReady, bowReady, drawUntil, jumpReady;
        boolean totem = true, falling;
        int apples = 3;
        Player target;
    }

    final Map<UUID, Mimic> byBrain = new HashMap<>(), byBody = new HashMap<>();
    private final Set<UUID> pearls = new HashSet<>();

    MimicPlayer(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // leftovers (non-persistent, but a reload keeps entities)
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(BODY_TAG)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("mimic-player." + k, d); }

    // ---------------------------------------------------------------- spawning

    Mimic spawn(Location at, OfflinePlayer who) {
        World w = at.getWorld();
        Mimic m = new Mimic();
        m.name = who != null && who.getName() != null ? who.getName() : "Steve";
        Zombie z = (Zombie) w.spawnEntity(at, EntityType.ZOMBIE, false);
        z.addScoreboardTag(TAG);
        z.setPersistent(false);
        z.setRemoveWhenFarAway(false);
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        try { z.setShouldBurnInDay(false); } catch (RuntimeException ignored) { }
        z.setSilent(true);
        z.setCanPickupItems(false);
        z.customName(Component.text(m.name));
        z.setCustomNameVisible(false);
        z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        attr(z, Attribute.MAX_HEALTH, cfg("health", 40));
        z.setHealth(Math.min(z.getAttribute(Attribute.MAX_HEALTH).getValue(), cfg("health", 40)));
        attr(z, Attribute.MOVEMENT_SPEED, cfg("speed", 0.31));
        attr(z, Attribute.FOLLOW_RANGE, 40);
        attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        attr(z, Attribute.KNOCKBACK_RESISTANCE, 0);
        EntityEquipment eq = z.getEquipment();
        kit(eq);
        Mannequin b = (Mannequin) w.spawnEntity(at, EntityType.MANNEQUIN, false);
        b.addScoreboardTag(BODY_TAG);
        Emotes.q(() -> b.setPersistent(false));
        Emotes.q(() -> b.setGravity(false));
        Emotes.q(() -> b.setImmovable(true));
        Emotes.q(() -> b.setCollidable(false));
        Emotes.q(() -> b.setDescription(null));
        Emotes.q(() -> b.customName(Component.text(m.name)));
        Emotes.q(() -> b.setCustomNameVisible(true));
        kit(b.getEquipment());
        if (who != null) {
            try {
                PlayerProfile pp = who.getPlayerProfile();
                b.setProfile(ResolvableProfile.resolvableProfile(pp));
                if (!pp.hasTextures()) pp.update().thenAccept(full -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (b.isValid()) Emotes.q(() -> b.setProfile(ResolvableProfile.resolvableProfile(full)));
                }));
            } catch (RuntimeException | LinkageError ignored) { }
        }
        m.brain = z; m.body = b;
        byBrain.put(z.getUniqueId(), m);
        byBody.put(b.getUniqueId(), m);
        return m;
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private void kit(EntityEquipment eq) {
        String tier = plugin.getConfig().getString("mimic-player.kit", "diamond").toLowerCase(Locale.ROOT);
        String pre = tier.equals("netherite") ? "NETHERITE" : tier.equals("iron") ? "IRON" : "DIAMOND";
        eq.setHelmet(enchanted(Material.valueOf(pre + "_HELMET")));
        eq.setChestplate(enchanted(Material.valueOf(pre + "_CHESTPLATE")));
        eq.setLeggings(enchanted(Material.valueOf(pre + "_LEGGINGS")));
        eq.setBoots(enchanted(Material.valueOf(pre + "_BOOTS")));
        eq.setItemInMainHand(new ItemStack(Material.valueOf(pre + "_SWORD")));
        eq.setItemInOffHand(new ItemStack(Material.SHIELD));
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0f); } catch (RuntimeException ignored) { }
    }

    private static ItemStack enchanted(Material m) {
        ItemStack it = new ItemStack(m);
        try { it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION, 2); } catch (RuntimeException ignored) { }
        return it;
    }

    void remove(Mimic m) {
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        if (m.brain.isValid()) m.brain.remove();
        if (m.body.isValid()) m.body.remove();
    }

    void removeAll() { for (Mimic m : new ArrayList<>(byBrain.values())) remove(m); }

    // ---------------------------------------------------------------- the brain

    private void tick() {
        for (Mimic m : new ArrayList<>(byBrain.values())) {
            try { think(m); } catch (RuntimeException ex) {
                StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(s -> s.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
                plugin.getLogger().warning("[MimicPlayer] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
                remove(m);
            }
        }
    }

    private Player nearest(Zombie z) {
        Player best = null;
        double bd = 40 * 40;
        for (Player p : z.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || p.hasMetadata("vanished")) continue;
            double d = p.getLocation().distanceSquared(z.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private void think(Mimic m) {
        Zombie z = m.brain;
        if (!z.isValid() || !m.body.isValid()) { remove(m); return; }
        m.t++;
        if (m.t % 10 == 0 || m.target == null || !m.target.isValid() || m.target.getWorld() != z.getWorld()) {
            m.target = nearest(z);
            if (m.target != null && z.getTarget() != m.target) z.setTarget(m.target);
        }
        Player tg = m.target;
        EntityEquipment eq = m.body.getEquipment();
        double dist = tg == null ? 99 : tg.getLocation().distance(z.getLocation());
        // eating: stands still, golden apple in hand
        if (m.eatUntil > m.t) {
            z.setVelocity(new Vector(0, z.getVelocity().getY(), 0));
            if (m.eatUntil - m.t == 1) {
                z.setHealth(Math.min(z.getAttribute(Attribute.MAX_HEALTH).getValue(), z.getHealth() + 4));
                z.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
                z.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                z.getWorld().playSound(z.getLocation(), Sound.ENTITY_PLAYER_BURP, 1, 1);
                Emotes.q(() -> m.body.clearActiveItem());
                eq.setItemInMainHand(z.getEquipment().getItemInMainHand());
            } else if (m.t % 4 == 0) z.getWorld().playSound(z.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 0.9f + random.nextFloat() * 0.2f);
            follow(m);
            return;
        }
        if (tg != null) {
            double hp = z.getHealth() / z.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (hp < cfg("eat-below", 0.4) && m.apples > 0 && dist > 3) { // back off and eat
                m.apples--;
                m.eatUntil = m.t + 32;
                eq.setItemInMainHand(new ItemStack(Material.GOLDEN_APPLE));
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
                follow(m);
                return;
            }
            // pearl at far targets
            if (dist > 14 && dist < 40 && m.t >= m.pearlReady) { m.pearlReady = m.t + 300; pearl(m, tg); }
            // bow at mid range
            if (dist > 8 && dist < 20 && m.t >= m.bowReady && m.drawUntil <= m.t && hasSight(z, tg)) {
                m.bowReady = m.t + 90 + random.nextInt(60);
                m.drawUntil = m.t + 22;
                eq.setItemInMainHand(new ItemStack(Material.BOW));
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            }
            if (m.drawUntil > m.t) {
                z.setVelocity(new Vector(0, z.getVelocity().getY(), 0));
                if (m.drawUntil - m.t == 1) { shoot(m, tg); Emotes.q(() -> m.body.clearActiveItem()); eq.setItemInMainHand(z.getEquipment().getItemInMainHand()); }
                follow(m);
                return;
            }
            // blocking: target in front, close, swinging soon
            boolean shielding = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
            if (!shielding && dist < 4.5 && m.shieldOffUntil <= m.t && random.nextDouble() < cfg("block-chance", 0.03)) {
                m.blockUntil = m.t + 14 + random.nextInt(12);
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.OFF_HAND));
            } else if (m.blockUntil == m.t) Emotes.q(() -> m.body.clearActiveItem());
            // close: strafe + W-tap + crits
            if (dist < 4.5 && z.isOnGround()) {
                if (--m.strafeFlip <= 0) { m.strafeDir = -m.strafeDir; m.strafeFlip = 15 + random.nextInt(25); }
                Vector to = tg.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
                if (to.lengthSquared() > 1e-4) {
                    to.normalize();
                    Vector side = new Vector(-to.getZ(), 0, to.getX()).multiply(0.11 * m.strafeDir);
                    Vector fw = to.clone().multiply(dist > 2.6 ? 0.06 : -0.04);
                    Vector v = z.getVelocity();
                    Safe.vel(z, new Vector(v.getX() * 0.6 + side.getX() + fw.getX(), v.getY(), v.getZ() * 0.6 + side.getZ() + fw.getZ()));
                }
            }
            if (dist > 3 && dist < 7 && z.isOnGround() && m.t >= m.jumpReady) { // sprint-jump in for a crit
                m.jumpReady = m.t + 25 + random.nextInt(20);
                Vector to = tg.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
                if (to.lengthSquared() > 1e-4) Safe.vel(z, to.normalize().multiply(0.36).setY(0.42));
            }
        }
        m.falling = !z.isOnGround() && z.getVelocity().getY() < -0.08;
        follow(m);
    }

    /** The body stands where the brain is, facing its way; sneaks while shielding. */
    private void follow(Mimic m) {
        Location l = m.brain.getLocation();
        Location to = l.clone();
        try { to.setYaw(m.brain.getBodyYaw()); } catch (RuntimeException ignored) { }
        to.setPitch(l.getPitch());
        if (m.target != null) {
            Vector d = m.target.getEyeLocation().toVector().subtract(m.brain.getEyeLocation().toVector());
            if (d.lengthSquared() > 1e-4) {
                to.setYaw((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())));
                to.setPitch((float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))));
            }
        }
        m.body.teleport(to);
        Emotes.q(() -> m.body.setRotation(to.getYaw(), to.getPitch()));
        Emotes.q(() -> m.body.setBodyYaw(to.getYaw()));
        Pose want = m.blockUntil > m.t && m.shieldOffUntil <= m.t ? Pose.SNEAKING : Pose.STANDING;
        Emotes.q(() -> { if (m.body.getPose() != want) m.body.setPose(want, true); });
    }

    private boolean hasSight(LivingEntity a, LivingEntity b) {
        try { return a.hasLineOfSight(b); } catch (RuntimeException e) { return true; }
    }

    private void shoot(Mimic m, Player tg) {
        Zombie z = m.brain;
        Location eye = m.body.getEyeLocation();
        Vector lead = tg.getVelocity().clone().setY(0).multiply(eye.distance(tg.getLocation()) / 2.4);
        Vector aim = tg.getLocation().add(0, 1.1, 0).toVector().add(lead).subtract(eye.toVector());
        double d = aim.length();
        aim.setY(aim.getY() + d * 0.06);
        Arrow a = z.getWorld().spawnArrow(eye.add(aim.clone().normalize().multiply(0.8)), aim.normalize(), 2.6f, 1.5f);
        a.setShooter(z);
        a.setDamage(cfg("bow-damage", 3));
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        z.getWorld().playSound(z.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1, 1);
    }

    private void pearl(Mimic m, Player tg) {
        Zombie z = m.brain;
        Location eye = m.body.getEyeLocation();
        Vector aim = tg.getLocation().toVector().subtract(eye.toVector());
        double d = Math.sqrt(aim.getX() * aim.getX() + aim.getZ() * aim.getZ());
        Vector v = aim.clone().setY(0).normalize().multiply(Math.min(1.6, 0.05 * d + 0.6)).setY(Math.min(0.9, 0.2 + d * 0.022));
        EnderPearl p = z.getWorld().spawn(eye, EnderPearl.class, e -> { e.setShooter(z); e.addScoreboardTag(TAG); });
        Safe.vel(p, v);
        pearls.add(p.getUniqueId());
        m.body.getEquipment().setItemInMainHand(new ItemStack(Material.ENDER_PEARL));
        Emotes.q(() -> m.body.swingMainHand());
        z.getWorld().playSound(z.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) m.body.getEquipment().setItemInMainHand(z.getEquipment().getItemInMainHand()); }, 6L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof EnderPearl p) || !pearls.remove(p.getUniqueId())) return;
        if (!(p.getShooter() instanceof Zombie z) || !byBrain.containsKey(z.getUniqueId())) return;
        Location at = p.getLocation();
        at.setYaw(z.getLocation().getYaw());
        Bukkit.getScheduler().runTask(plugin, () -> { if (z.isValid()) { z.teleport(at); z.getWorld().playSound(at, Sound.ENTITY_PLAYER_TELEPORT, 1, 1); z.damage(1); } });
        p.remove();
        e.setCancelled(true);
    }

    // ---------------------------------------------------------------- damage

    /** Hits on the body go to the brain. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBodyHit(EntityDamageEvent e) {
        Mimic m = byBody.get(e.getEntity().getUniqueId());
        if (m == null) return;
        e.setCancelled(true);
        if (!m.brain.isValid()) return;
        if (e instanceof EntityDamageByEntityEvent be) {
            if (be.getDamager().equals(m.brain)) return;
            m.brain.damage(e.getDamage(), be.getDamager());
        } else if (e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION && e.getCause() != EntityDamageEvent.DamageCause.FALL) m.brain.damage(e.getDamage());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrainHurt(EntityDamageByEntityEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m != null) {
            // the shield: blocks melee and arrows from the front; an axe knocks it out for 5 s
            boolean shielding = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
            if (shielding) {
                Vector facing = m.body.getLocation().getDirection().setY(0);
                Vector from = e.getDamager().getLocation().toVector().subtract(m.brain.getLocation().toVector()).setY(0);
                if (facing.lengthSquared() > 0 && from.lengthSquared() > 0 && facing.normalize().dot(from.normalize()) > 0.2) {
                    Player by = TotemLimit.playerBehind(e.getDamager());
                    if (by != null && by.getInventory().getItemInMainHand().getType().name().endsWith("_AXE") && !(e.getDamager() instanceof Projectile)) {
                        m.shieldOffUntil = m.t + 100;
                        m.blockUntil = 0;
                        Emotes.q(() -> m.body.clearActiveItem());
                        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
                    } else m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1, 1);
                    e.setCancelled(true);
                    return;
                }
            }
            Emotes.q(() -> m.body.playHurtAnimation(0));
            return;
        }
        // its own hits: swing, crits while falling
        Mimic att = byBrain.get(e.getDamager().getUniqueId());
        if (att != null) {
            Emotes.q(() -> att.body.swingMainHand());
            if (att.falling) {
                e.setDamage(e.getDamage() * 1.5);
                e.getEntity().getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.2);
                e.getEntity().getWorld().playSound(e.getEntity().getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
            } else e.getEntity().getWorld().playSound(e.getEntity().getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1, 1);
        }
    }

    /** One totem, then death: the body goes with it, no drops. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null || e.getFinalDamage() < m.brain.getHealth() + m.brain.getAbsorptionAmount() || !m.totem) return;
        m.totem = false;
        e.setCancelled(true);
        m.brain.setHealth(1);
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        Emotes.q(() -> m.body.playEffect(EntityEffect.PROTECTED_FROM_DEATH));
        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_TOTEM_USE, 1, 1);
        m.brain.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, m.brain.getLocation().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null) return;
        e.getDrops().clear();
        e.setDroppedExp(plugin.getConfig().getInt("mimic-player.xp", 0));
        for (String s : plugin.getConfig().getStringList("mimic-player.drops")) {
            try { String[] p = s.split(":"); e.getDrops().add(new ItemStack(Material.valueOf(p[0].toUpperCase(Locale.ROOT)), p.length > 1 ? Integer.parseInt(p[1]) : 1)); }
            catch (RuntimeException ignored) { }
        }
        m.body.getWorld().spawnParticle(Particle.POOF, m.body.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        if (m.body.isValid()) m.body.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) { if (byBrain.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m != null && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true); // players only
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("items.mimicplayer")) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "random" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("clear") || sub.equals("remove")) {
            int n = byBrain.size();
            removeAll();
            s.sendMessage(ChatColor.GREEN + "Removed " + n + " mimic" + (n == 1 ? "" : "s") + ".");
            return true;
        }
        if (sub.equals("list")) {
            s.sendMessage(ChatColor.GOLD + "Mimics (" + byBrain.size() + "):");
            for (Mimic m : byBrain.values()) s.sendMessage(ChatColor.GRAY + " " + m.name + " at " + Where.spot(m.brain.getLocation()) + " (" + Math.round(m.brain.getHealth()) + " hp)");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Run it in game (it spawns where you look)."); return true; }
        int count = 1;
        if (a.length > 1) try { count = Math.max(1, Math.min(10, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
        Location at;
        org.bukkit.block.Block hit;
        try { hit = p.getTargetBlockExact(30); } catch (RuntimeException ex) { hit = null; }
        at = hit != null ? hit.getLocation().add(0.5, 1, 0.5) : p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
        for (int i = 0; i < count; i++) {
            OfflinePlayer who = sub.equals("random") ? randomPlayer(p) : Bukkit.getOfflinePlayer(a[0]);
            Mimic m = spawn(at.clone().add(random.nextGaussian() * 1.2, 0, random.nextGaussian() * 1.2), who);
            s.sendMessage(ChatColor.GREEN + "Spawned a mimic of " + ChatColor.WHITE + m.name + ChatColor.GREEN + ". /mimicplayer clear removes them.");
        }
        return true;
    }

    private OfflinePlayer randomPlayer(Player not) {
        OfflinePlayer[] all = Bukkit.getOfflinePlayers();
        List<OfflinePlayer> pick = new ArrayList<>();
        for (OfflinePlayer o : all) if (o.getName() != null && !o.getUniqueId().equals(not.getUniqueId()) && !o.isOnline()) pick.add(o);
        if (pick.isEmpty()) for (OfflinePlayer o : all) if (o.getName() != null) pick.add(o);
        return pick.isEmpty() ? not : pick.get(random.nextInt(pick.size()));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(List.of("random", "clear", "list"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        if (a.length == 2) return List.of("1", "3", "5");
        return List.of();
    }
}
