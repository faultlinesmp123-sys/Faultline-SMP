package net.faultlinesmp.items;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirror..?? (dropped by the Lost Explorer): a big hit bounces off you and back at whoever dealt it.
 * Runs at HIGH so it gets there before FaultlineBosses puts a player on their knees in the Explorer's arena.
 */
final class MirrorGear implements Listener {

    private final FaultlineItems plugin;
    final NamespacedKey key;
    private final Map<UUID, Long> ready = new HashMap<>();
    private boolean reflecting; // a reflected hit can't be reflected again (two Mirrors facing each other)

    MirrorGear(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "lost_mirror");
    }

    int cooldown() { return Math.max(1, plugin.getConfig().getInt("mirror.cooldown-seconds", 45)); }
    double threshold() { return plugin.getConfig().getDouble("mirror.min-hit-share", 0.33); }

    ItemStack item() {
        return FaultlineItems.NewAccessoryItems.make(plugin, Material.GLASS_PANE, ChatColor.WHITE + "" + ChatColor.BOLD + "Mirror..??", key,
                ChatColor.GREEN + "A hit that would take a third of your",
                ChatColor.GREEN + "health or more bounces off you, back",
                ChatColor.GREEN + "at whoever dealt it. You take none of it.",
                ChatColor.DARK_GRAY + "Then it needs " + cooldown() + " seconds to clear.",
                ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "You look into it. Something looks back.",
                ChatColor.DARK_GRAY + "Dropped by the Lost Explorer.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent event) {
        if (reflecting || !(event.getEntity() instanceof Player p)) return;
        if (!plugin.getAccessoryManager().hasEquipped(p, key)) return;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double hp = max == null ? 20 : max.getValue();
        if (event.getFinalDamage() < hp * threshold()) return;
        long now = System.currentTimeMillis();
        if (now < ready.getOrDefault(p.getUniqueId(), 0L)) return;
        ready.put(p.getUniqueId(), now + cooldown() * 1000L);

        double dmg = event.getDamage();
        event.setCancelled(true);
        Entity from = event.getDamager();
        if (from instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) { pr.remove(); from = shooter; }
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.4f, 0.6f);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.1, 0), 30, 0.4, 0.6, 0.4, 0.08);
        p.sendActionBar(net.kyori.adventure.text.Component.text("The Mirror..?? throws the blow back. (" + cooldown() + "s)", net.kyori.adventure.text.format.NamedTextColor.WHITE));
        if (from instanceof LivingEntity back && !back.equals(p) && back.isValid() && !back.isDead()) {
            reflecting = true;
            try { back.damage(Math.min(dmg, plugin.getConfig().getDouble("mirror.max-reflect", 30)), p); }
            finally { reflecting = false; }
            back.getWorld().spawnParticle(Particle.END_ROD, back.getLocation().add(0, back.getHeight() / 2, 0), 20, 0.3, 0.4, 0.3, 0.05);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (System.currentTimeMillis() >= ready.getOrDefault(event.getPlayer().getUniqueId(), 0L)) ready.remove(event.getPlayer().getUniqueId());
    }
}
