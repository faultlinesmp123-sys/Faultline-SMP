package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 1.3.8: UNHOLY ARROWS are back (they left with the Demon Eye in Bosses 2.0.0; same PDC key `faultlinebosses:unholy_arrow`
 * and model `faultline:unholy_arrow`, so old ones still work, and OldGear no longer takes them).
 *  - Skeletons, Strays and Bogged killed by a player drop 1-2, `unholy-arrows.drop-chance` 5% (never spawner / trial
 *    spawner mobs: no farms).
 *  - Crafted from witch ingredients + an arrow (shapeless): Arrow + Fermented Spider Eye + Glowstone Dust + Redstone = 2.
 *  - No bonus damage any more (was +2.5%). Instead a near-full-draw hit on a living thing gives the SHOOTER Strength II and
 *    Swiftness II for `buff-seconds` 3, at most once every `cooldown-seconds` 20 (action bar countdown).
 *  - Infinity doesn't make them endless (one is used up per shot); picking one up gives the Unholy Arrow back. Purple trail.
 *  - Admin: /giveunholy [amount] [player].
 */
final class UnholyArrows implements Listener, CommandExecutor {

    static final NamespacedKey KEY = NamespacedKey.fromString("faultlinebosses:unholy_arrow");
    private static final String FLIGHT = "faultline_unholy", FULL = "faultline_unholy_full";

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final Map<UUID, Long> ready = new HashMap<>();
    private final Set<UUID> flying = new HashSet<>();

    UnholyArrows(FaultlineItems plugin) {
        this.plugin = plugin;
        try {
            ShapelessRecipe r = new ShapelessRecipe(new NamespacedKey(plugin, "unholy_arrow"), item(2));
            r.addIngredient(Material.ARROW);
            r.addIngredient(Material.FERMENTED_SPIDER_EYE);
            r.addIngredient(Material.GLOWSTONE_DUST);
            r.addIngredient(Material.REDSTONE);
            FaultlineItems.addRecipeSafely(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("[Unholy] recipe: " + e); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::trail, 20L, 2L);
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("unholy-arrows." + k, d); }

    static ItemStack item(int amount) {
        ItemStack item = new ItemStack(Material.ARROW, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("faultline", "unholy_arrow"));
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Unholy Arrow");
        meta.setLore(List.of(ChatColor.GRAY + "Hit something with a full draw:",
                ChatColor.GREEN + "Strength II + Swiftness II for 3s" + ChatColor.DARK_GRAY + " (20s cooldown)",
                ChatColor.DARK_GRAY + "Dropped by skeletons, or crafted."));
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    static boolean is(ItemStack it) {
        return it != null && it.getType() == Material.ARROW && it.hasItemMeta() && it.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    /** Old arrows from the Demon Eye days still say "+2.5% damage": fixed when held / shot. */
    private void refresh(ItemStack it) {
        if (!is(it)) return;
        List<String> want = item(1).getItemMeta().getLore();
        ItemMeta m = it.getItemMeta();
        if (want.equals(m.getLore())) return;
        m.setLore(want);
        it.setItemMeta(m);
    }

    // ---------------------------------------------------------------- drops
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (!(v instanceof Skeleton || v instanceof Stray || v instanceof Bogged) || v.getKiller() == null) return;
        try {
            CreatureSpawnEvent.SpawnReason why = v.getEntitySpawnReason();
            if (why == CreatureSpawnEvent.SpawnReason.SPAWNER || why == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) return;
        } catch (Throwable ignored) { }
        if (v.getScoreboardTags().contains("faultline_mutant_minion")) return;
        if (random.nextDouble() >= cfg("drop-chance", 0.05)) return;
        e.getDrops().add(item(1 + random.nextInt(2)));
    }

    // ---------------------------------------------------------------- shooting
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!is(e.getConsumable()) || !(e.getProjectile() instanceof AbstractArrow arrow)) return;
        refresh(e.getConsumable());
        ItemStack bow = e.getBow();
        boolean infinity = bow != null && bow.getType() == Material.BOW && bow.containsEnchantment(Enchantment.INFINITY);
        if (infinity) e.setConsumeItem(true); // never endless
        try {
            arrow.setItemStack(item(1)); // picking it back up gives an Unholy Arrow
            // 1.3.12: only the Infinity case. A Multishot crossbow's two side arrows are CREATIVE_ONLY too, and making
            // them pickable turned 1 Unholy Arrow into 3.
            if (infinity && arrow.getPickupStatus() == AbstractArrow.PickupStatus.CREATIVE_ONLY && e.shouldConsumeItem())
                arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
        } catch (RuntimeException ignored) { }
        arrow.addScoreboardTag(FLIGHT);
        if (e.getForce() >= cfg("min-draw", 0.9) || bow != null && bow.getType() == Material.CROSSBOW) arrow.addScoreboardTag(FULL);
        flying.add(arrow.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow arrow) || !arrow.getScoreboardTags().contains(FLIGHT)) return;
        if (!(arrow.getShooter() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand || victim == p) return;
        if (victim.isInvisible() && !(victim instanceof Player)) return; // boss hitboxes
        if (!arrow.getScoreboardTags().contains(FULL)) { p.sendActionBar(Meteor.legacy(ChatColor.DARK_PURPLE + "Unholy Arrow: " + ChatColor.GRAY + "needs a full draw")); return; }
        long now = System.currentTimeMillis();
        long at = ready.getOrDefault(p.getUniqueId(), 0L);
        if (now < at) {
            p.sendActionBar(Meteor.legacy(ChatColor.DARK_PURPLE + "Unholy Arrow: " + ChatColor.GRAY + "ready in " + ((at - now + 999) / 1000) + "s"));
            return;
        }
        ready.put(p.getUniqueId(), now + (long) (cfg("cooldown-seconds", 20) * 1000));
        int ticks = (int) Math.round(cfg("buff-seconds", 3) * 20);
        give(p, PotionEffectType.STRENGTH, (int) cfg("strength-level", 2) - 1, ticks);
        give(p, PotionEffectType.SPEED, (int) cfg("swiftness-level", 2) - 1, ticks);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 200), 1.4f));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.5f, 1.6f);
        p.sendActionBar(Meteor.legacy(ChatColor.DARK_PURPLE + "Unholy! " + ChatColor.GRAY + "Strength II + Swiftness II"));
    }

    /** Never cuts a stronger or longer effect the player already has. */
    private static void give(Player p, PotionEffectType t, int amp, int ticks) {
        if (amp < 0) return;
        PotionEffect have = p.getPotionEffect(t);
        if (have != null && (have.getAmplifier() > amp || have.getAmplifier() == amp && have.getDuration() > ticks)) return;
        p.addPotionEffect(new PotionEffect(t, ticks, amp, false, true, true));
    }

    private void trail() {
        flying.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof AbstractArrow a) || !a.isValid() || a.isInBlock()) return true;
            a.getWorld().spawnParticle(Particle.DUST, a.getLocation(), 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 200), 1.0f));
            return false;
        });
        if (ready.size() > 200) { long now = System.currentTimeMillis(); ready.values().removeIf(t -> t < now); }
    }

    // ---------------------------------------------------------------- /giveunholy
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.giveunholy")) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
        int n = 16;
        try { if (a.length > 0) n = Math.max(1, Math.min(2304, Integer.parseInt(a[0]))); } catch (NumberFormatException ignored) { }
        Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
        if (t == null) { s.sendMessage(ChatColor.YELLOW + "/giveunholy [amount] [player]"); return true; }
        for (int left = n; left > 0; left -= 64)
            for (ItemStack rest : t.getInventory().addItem(item(Math.min(64, left))).values()) t.getWorld().dropItemNaturally(t.getLocation(), rest);
        s.sendMessage(ChatColor.GREEN + "Gave " + n + " Unholy Arrows to " + t.getName() + ".");
        return true;
    }

    static boolean creative(Player p) { return p.getGameMode() == GameMode.CREATIVE; }
}
