package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Diamond Jacob's five accessories. Which ones a player wears is written to them every second
 * (faultlineitems:jacob_counters); FaultlineBosses reads it for the effects that live in his fight
 * (Ender Anchor's pearl marker and Pearl Strike, the Banner against his army, the Ember Ward against his flames).
 */
final class JacobGear implements Listener {

    enum Piece {
        QUIVER("falconers_quiver", Material.ARROW, ChatColor.GOLD, "Falconer's Quiver",
                ChatColor.GREEN + "+20% arrow damage against anything airborne"),
        ANCHOR("ender_anchor", Material.ENDER_EYE, ChatColor.DARK_PURPLE, "Ender Anchor",
                ChatColor.GREEN + "See where Diamond Jacob is about to pearl to", ChatColor.GREEN + "His Pearl Strike deals half damage to you"),
        BANNER("banner_of_defiance", Material.PAPER, ChatColor.RED, "Banner of Defiance",
                ChatColor.GREEN + "His army deals 30% less damage to you", ChatColor.GREEN + "Their hits and horses can't knock you back"),
        EMBER("ember_ward", Material.BLAZE_POWDER, ChatColor.GOLD, "Ember Ward",
                ChatColor.GREEN + "You can't be set on fire", ChatColor.GREEN + "25% less fire damage (his flames too)"),
        HEART("diamond_heart", Material.DIAMOND, ChatColor.AQUA, "Diamond Heart",
                ChatColor.GREEN + "+2 hearts of max health");

        final String id, name; final Material material; final ChatColor color; final String[] lore;
        Piece(String id, Material material, ChatColor color, String name, String... lore) {
            this.id = id; this.material = material; this.color = color; this.name = name; this.lore = lore;
        }
    }

    private final FaultlineItems plugin;
    private final Map<Piece, NamespacedKey> keys = new java.util.EnumMap<>(Piece.class);
    private final NamespacedKey countersKey, heartMod;

    JacobGear(FaultlineItems plugin) {
        this.plugin = plugin;
        for (Piece p : Piece.values()) keys.put(p, new NamespacedKey(plugin, p.id));
        countersKey = new NamespacedKey(plugin, "jacob_counters"); // FaultlineBosses reads this on players
        heartMod = new NamespacedKey(plugin, "diamond_heart_health");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { sync(); } catch (RuntimeException e) { plugin.getLogger().warning("[Jacob gear] " + e); }
        }, 20L, 20L);
    }

    ItemStack item(Piece p) {
        return FaultlineItems.NewAccessoryItems.make(plugin, p.material, p.color + "" + ChatColor.BOLD + p.name, keys.get(p),
                java.util.stream.Stream.concat(java.util.Arrays.stream(p.lore), java.util.stream.Stream.of(ChatColor.DARK_GRAY + "Dropped by Diamond Jacob.")).toArray(String[]::new));
    }

    Map<String, Function<FaultlineItems, ItemStack>> catalog() {
        Map<String, Function<FaultlineItems, ItemStack>> m = new LinkedHashMap<>();
        for (Piece p : Piece.values()) m.put(p.id, pl -> item(p));
        return m;
    }

    private boolean has(Player p, Piece piece) { return plugin.getAccessoryManager().hasEquipped(p, keys.get(piece)); }

    private void sync() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> on = new ArrayList<>();
            for (Piece piece : Piece.values()) if (has(p, piece)) on.add(piece.id);
            p.getPersistentDataContainer().set(countersKey, PersistentDataType.STRING, String.join(",", on));
            if (has(p, Piece.EMBER) && p.getFireTicks() > 0) p.setFireTicks(0); // can't burn
            // Diamond Heart: +2 hearts while it's on
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max == null) continue;
            boolean wants = has(p, Piece.HEART), got = max.getModifier(heartMod) != null;
            if (wants && !got) max.addModifier(new AttributeModifier(heartMod, 4, AttributeModifier.Operation.ADD_NUMBER));
            else if (!wants && got) {
                max.removeModifier(heartMod);
                if (p.getHealth() > max.getValue()) p.setHealth(max.getValue());
            }
        }
    }

    /** Falconer's Quiver: +20% arrow damage against anything in the air. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player by && has(by, Piece.QUIVER) && !event.getEntity().isOnGround())
            event.setDamage(event.getDamage() * 1.2);
    }

    /** Ember Ward: no burning, 25% less fire damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFire(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || !has(p, Piece.EMBER)) return;
        EntityDamageEvent.DamageCause c = event.getCause();
        if (c == EntityDamageEvent.DamageCause.FIRE_TICK) { event.setCancelled(true); p.setFireTicks(0); return; }
        if (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.LAVA || c == EntityDamageEvent.DamageCause.HOT_FLOOR
                || c == EntityDamageEvent.DamageCause.CAMPFIRE) event.setDamage(event.getDamage() * 0.75);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player p && has(p, Piece.EMBER)) event.setCancelled(true);
    }
}
