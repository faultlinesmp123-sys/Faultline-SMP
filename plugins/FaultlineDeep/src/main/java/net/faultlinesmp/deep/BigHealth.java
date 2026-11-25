package net.faultlinesmp.deep;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Deep 1.19.2: health over the server's cap. Spigot caps every mob's max health (spigot.yml `settings.attribute.maxHealth.max`,
 * 1024 by default): setBaseValue(3600) is clamped to 1024 and then setHealth(3600) THROWS. That broke the nest Queen (3600): her
 * body spawned, the setup threw before her model / hitbox / tracking, and the nest tried again every tick = dozens of model-less
 * queens. `set(e, hp)` gives the mob as much as the cap allows and, when that's less than asked, scales every hit it takes (and
 * every heal) down by the same factor (entity PDC `faultlinedeep:hp_scale`), so it's exactly as hard to kill as `hp` says.
 * Health bars read health / max health: still right.
 */
final class BigHealth implements Listener {
    private static NamespacedKey KEY;

    static void init(Plugin p) {
        KEY = new NamespacedKey(p, "hp_scale");
        p.getServer().getPluginManager().registerEvents(new BigHealth(), p);
    }

    /** Max health `hp` (or as near as the server allows, damage scaled to match), full health. Returns the real max health. */
    static double set(LivingEntity e, double hp) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return e.getHealth();
        ai.setBaseValue(hp);
        double real = ai.getValue();
        if (real <= 0) real = 1;
        e.setHealth(Math.min(hp, real));
        if (KEY != null) {
            if (hp > real + 0.01) e.getPersistentDataContainer().set(KEY, PersistentDataType.DOUBLE, hp / real);
            else e.getPersistentDataContainer().remove(KEY);
        }
        return real;
    }

    static double scale(LivingEntity e) {
        if (KEY == null) return 1;
        Double s = e.getPersistentDataContainer().get(KEY, PersistentDataType.DOUBLE);
        return s == null || s < 1 ? 1 : s;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        double s = scale(le);
        if (s > 1) e.setDamage(e.getDamage() / s);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        double s = scale(le);
        if (s > 1) e.setAmount(e.getAmount() / s);
    }
}
