package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The armor stands that draw things for Bedrock players only (WildRig stands: locusts, Mimics; museum pedestals).
 * Java players never see them, but Bedrock players do, and their hits and clicks land on the stand instead of the
 * thing it draws. Without this a Bedrock player punching a Mimic broke the stand (dropping its model) and never hurt
 * the Mimic, and punching a pedestal's stand dropped a COPY of the exhibit.
 *   hits    -> cancelled, and passed on to the creature it draws (same damage, same attacker)
 *   clicks  -> a pedestal's stand acts as the pedestal (put something on show / take it back); others do nothing
 *   robbing -> never (the equipment is locked as well)
 */
final class StandGuard implements Listener {
    static final String TAG = "faultline_bedrock_stand";
    /** stand -> the entity its hits go to */
    static final Map<UUID, UUID> FORWARD = new HashMap<>();
    /** stand -> the pedestal block it shows */
    static final Map<UUID, Block> PEDESTAL = new HashMap<>();

    /** every live stand, so Bedrock players arriving from another world (or late) still get to see it */
    static final java.util.Set<ArmorStand> STANDS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private final FaultlineItems plugin;
    StandGuard(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 40L, 40L);
    }

    static void track(ArmorStand stand) { if (stand != null) STANDS.add(stand); }

    /** Java players never see a stand; Bedrock players in its world always do. */
    void upkeep() {
        STANDS.removeIf(a -> !a.isValid());
        if (STANDS.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean bedrock = Weather.bedrock(p);
            for (ArmorStand a : STANDS) {
                if (!a.getWorld().equals(p.getWorld())) continue;
                if (bedrock) { if (!p.canSee(a)) p.showEntity(plugin, a); }
                else if (p.canSee(a)) p.hideEntity(plugin, a);
            }
        }
    }

    static void forward(ArmorStand stand, Entity to) { if (stand != null && to != null) FORWARD.put(stand.getUniqueId(), to.getUniqueId()); }

    static void forget(Entity stand) {
        if (stand == null) return;
        FORWARD.remove(stand.getUniqueId()); PEDESTAL.remove(stand.getUniqueId());
        if (stand instanceof ArmorStand a) STANDS.remove(a);
    }

    static boolean ours(Entity e) { return e instanceof ArmorStand && e.getScoreboardTags().contains(TAG); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageEvent e) {
        if (!ours(e.getEntity())) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent ee)) return;
        UUID to = FORWARD.get(e.getEntity().getUniqueId());
        Entity target = to == null ? null : Bukkit.getEntity(to);
        if (!(target instanceof LivingEntity le) || le.isDead()) return;
        Entity by = ee.getDamager();
        if (by instanceof Projectile pr && pr.getShooter() instanceof Entity s) { pr.remove(); by = s; }
        if (by == null || by.equals(le)) return;
        le.damage(Math.max(0.5, e.getDamage()), by);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!ours(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = PEDESTAL.get(e.getRightClicked().getUniqueId());
        if (b != null && plugin.getGear() != null) plugin.getGear().clickPedestal(e.getPlayer(), b);
    }

    /** Arrows fly through a stand that only draws something (a pedestal's); a creature's stand takes the hit for it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onShot(ProjectileHitEvent e) {
        if (e.getHitEntity() != null && ours(e.getHitEntity()) && !FORWARD.containsKey(e.getHitEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRob(PlayerArmorStandManipulateEvent e) { if (ours(e.getRightClicked())) e.setCancelled(true); }
}
