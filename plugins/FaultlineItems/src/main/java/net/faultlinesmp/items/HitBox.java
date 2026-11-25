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
    }
}
