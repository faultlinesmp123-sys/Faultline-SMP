package net.faultlinesmp.items;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Items 1.3.49: a rigged spider's body (the Queen, the brood) HIDDEN from Java players. An invisible spider still draws its
 * glowing red EYES (vanilla's eye layer ignores invisibility): those were the floating red cubes all over the nest (the Queen's
 * x4). So the body is hidden from every Java player (`hideEntity`; Bedrock players keep seeing their stand-in), and an
 * Interaction box of the same size follows it: a Java player hitting the box makes a real vanilla attack on the body
 * (`Player#attack`: weapon, enchants, crits, cooldown all apply). Arrows and other projectiles hit the body on the server as
 * before (the client doesn't decide those).
 */
final class HitBox implements Listener {

    static final String TAG = "faultline_hitbox";
    private static final Map<UUID, HitBox> BY_BOX = new HashMap<>();
    private static final Map<UUID, HitBox> BY_BODY = new HashMap<>();
    private static FaultlineItems plugin;

    final LivingEntity body;
    Interaction box;

    static void init(FaultlineItems p) {
        plugin = p;
        p.getServer().getPluginManager().registerEvents(new HitBox(), p);
        Bukkit.getScheduler().runTaskTimer(p, HitBox::standinUpkeep, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(p, HitBox::arrows, 1L, 1L);
    }

    // ---------------------------------------------------------------- 1.3.55: Bedrock stand-ins
    // A stand-in used to be hidden from everyone and SHOWN to each player recognised as Bedrock: when that recognition missed
    // (or the show didn't reach them), Bedrock players saw no Queen at all. Now it's visible by default and HIDDEN from Java
    // players only, re-checked every second and on join / world change: a miss means a Java player sees a plain spider,
    // never that a Bedrock player sees nothing.
    private static final java.util.Set<org.bukkit.entity.Entity> STANDINS = new java.util.HashSet<>();

    static void bedrockOnly(org.bukkit.entity.Entity e) {
        if (plugin == null || e == null) return;
        e.setVisibleByDefault(true);
        STANDINS.add(e);
        for (Player p : e.getWorld().getPlayers()) fixStandin(p, e);
    }

    private static void fixStandin(Player p, org.bukkit.entity.Entity e) {
        boolean bedrock = Edition.bedrock(p);
        if (bedrock && !p.canSee(e)) p.showEntity(plugin, e);
        else if (!bedrock && p.canSee(e)) p.hideEntity(plugin, e);
    }

    private static void standinUpkeep() {
        STANDINS.removeIf(e -> !e.isValid());
        for (org.bukkit.entity.Entity e : STANDINS) for (Player p : e.getWorld().getPlayers()) fixStandin(p, e);
    }

    private HitBox() { body = null; }

    HitBox(LivingEntity body, double width, double height) {
        this.body = body;
        if (plugin == null) return;
        box = body.getWorld().spawn(body.getLocation(), Interaction.class, i -> {
            i.setPersistent(false);
            i.addScoreboardTag(TAG);
            i.setInteractionWidth((float) width);
            i.setInteractionHeight((float) height);
            i.setResponsive(true);
        });
        BY_BOX.put(box.getUniqueId(), this);
        BY_BODY.put(body.getUniqueId(), this);
        for (Player p : body.getWorld().getPlayers()) hideFrom(p);
    }

    private void hideFrom(Player p) {
        p.hideEntity(plugin, body); // everyone (Bedrock players see and hit their stand-in spider instead)
        if (Edition.bedrock(p) && box != null) p.hideEntity(plugin, box);
    }

    void tick(long t) {
        if (box == null) return;
        if (!body.isValid()) { remove(); return; }
        if (!box.isValid()) return;
        box.teleport(body.getLocation());
        if (t % 20 == 0) for (Player p : body.getWorld().getPlayers())
            if (p.canSee(body) && p.getLocation().distanceSquared(body.getLocation()) < 160 * 160) hideFrom(p);
    }

    void remove() {
        if (box != null) { BY_BOX.remove(box.getUniqueId()); if (box.isValid()) box.remove(); }
        if (body != null) BY_BODY.remove(body.getUniqueId());
    }

    // ---------------------------------------------------------------- 1.3.56: arrows
    // The game never lets a player's projectile hit an entity hidden from that player (CraftBukkit's canHitEntity), so with the
    // body hidden every arrow flew straight through her. Players' arrows / tridents are followed here: if the path it's about to
    // fly this tick passes through a hidden body's box (the size she's drawn at), it lands: the vanilla arrow damage (speed x
    // damage, crits, Power, Flame, Punch none) as ARROW / TRIDENT damage from the shooter, then the arrow drops (or keeps
    // flying with Piercing).
    private static final java.util.Set<org.bukkit.entity.AbstractArrow> FLYING = new java.util.HashSet<>();

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.AbstractArrow a && a.getShooter() instanceof Player && !BY_BODY.isEmpty()) FLYING.add(a);
    }

    private static boolean inBlock(org.bukkit.entity.AbstractArrow a) { try { return a.isInBlock(); } catch (RuntimeException e) { return false; } }

    private static void arrows() {
        if (FLYING.isEmpty()) return;
        java.util.Iterator<org.bukkit.entity.AbstractArrow> it = FLYING.iterator();
        while (it.hasNext()) {
            org.bukkit.entity.AbstractArrow a = it.next();
            if (!a.isValid() || inBlock(a) || a.getTicksLived() > 200) { it.remove(); continue; }
            org.bukkit.util.Vector v = a.getVelocity();
            double len = v.length();
            if (len < 0.05 || !Double.isFinite(len)) continue;
            org.bukkit.Location at = a.getLocation();
            for (HitBox h : BY_BODY.values()) {
                if (h.body == null || !h.body.isValid() || h.body.isDead() || h.body.getWorld() != at.getWorld() || h.box == null) continue;
                org.bukkit.Location b = h.body.getLocation();
                if (b.distanceSquared(at) > 40 * 40) continue;
                double hw = h.box.getInteractionWidth() / 2.0, hh = h.box.getInteractionHeight();
                org.bukkit.util.BoundingBox bb = new org.bukkit.util.BoundingBox(b.getX() - hw, b.getY(), b.getZ() - hw, b.getX() + hw, b.getY() + hh, b.getZ() + hw);
                org.bukkit.util.RayTraceResult hit = bb.rayTrace(at.toVector(), v.clone().multiply(1 / len), len + 0.3);
                if (hit == null) continue;
                if (a.getPierceLevel() > 0 && a.getScoreboardTags().contains("faultline_hit_" + h.body.getEntityId())) continue;
                Player shooter = (Player) a.getShooter();
                double dmg;
                if (a instanceof org.bukkit.entity.Trident) dmg = 8;
                else {
                    dmg = Math.ceil(Math.max(0, Math.min(len * a.getDamage(), Integer.MAX_VALUE)));
                    if (a.isCritical()) dmg += java.util.concurrent.ThreadLocalRandom.current().nextInt((int) (dmg / 2 + 2));
                }
                try {
                    org.bukkit.damage.DamageSource src = org.bukkit.damage.DamageSource.builder(a instanceof org.bukkit.entity.Trident
                            ? org.bukkit.damage.DamageType.TRIDENT : org.bukkit.damage.DamageType.ARROW).withCausingEntity(shooter).withDirectEntity(a).build();
                    h.body.damage(dmg, src);
                } catch (RuntimeException | LinkageError ex) { h.body.damage(dmg, shooter); }
                if (a.getFireTicks() > 0) h.body.setFireTicks(Math.max(h.body.getFireTicks(), 100));
                at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_ARROW_HIT, 1f, 1.1f);
                shooter.playSound(shooter, org.bukkit.Sound.ENTITY_ARROW_HIT_PLAYER, 0.4f, 1f);
                if (a.getPierceLevel() > 0) { a.addScoreboardTag("faultline_hit_" + h.body.getEntityId()); a.setPierceLevel(a.getPierceLevel() - 1); continue; }
                if (a instanceof org.bukkit.entity.Trident t) Safe.vel(t, v.clone().multiply(-0.1).setY(0.1)); // bounces off, Loyalty brings it back
                else a.remove();
                it.remove();
                break;
            }
        }
    }

    private static boolean attacking;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        HitBox h = BY_BOX.get(e.getAttacked().getUniqueId());
        if (h == null || attacking) return;
        e.setCancelled(true);
        if (!h.body.isValid() || h.body.isDead()) return;
        attacking = true;
        try { e.getPlayer().attack(h.body); } finally { attacking = false; }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> hideAll(e.getPlayer()), 5L); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> hideAll(e.getPlayer()), 5L); }

    private static void hideAll(Player p) {
        if (!p.isOnline()) return;
        for (HitBox h : BY_BODY.values()) if (h.body != null && h.body.getWorld() == p.getWorld()) h.hideFrom(p);
        for (org.bukkit.entity.Entity e : STANDINS) if (e.isValid() && e.getWorld() == p.getWorld()) fixStandin(p, e);
    }
}
