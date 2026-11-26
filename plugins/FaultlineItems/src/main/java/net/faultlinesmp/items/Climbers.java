package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 2.0 for getting around rough country (config {@code climbers:}):
 * <ul>
 * <li>MOUNTAIN GOATS: feed a goat Wheat (`goats.tame-chance` 25% a wheat) = it's yours (PDC `faultlineitems:goat_owner`,
 *     persistent); right-click it with a Saddle = saddled; right-click a saddled goat of yours = ride it. It goes where you look
 *     (W / S), Jump = a goat leap (`goats.jump` 0.95, ~4 blocks), Sprint = faster, it hops up 1-block steps on its own, no fall
 *     damage for it or you. Sneak to get off. It drops its saddle when it dies.</li>
 * <li>HANG GLIDER: an ELYTRA (PDC `faultlineitems:hang_glider`, max damage `glider.durability` 120, vanilla elytra look) crafted
 *     early (2 phantom membranes, 3 sticks, leather, string): glides like an elytra, but fireworks don't boost it and it never goes
 *     faster than `glider.max-speed` 1.1 blocks a tick.</li>
 * <li>GRAPPLING HOOK UPGRADES (in an anvil with Rotbeard's Grappling Hook): LONG LINE (I-II: the cast flies x1.5 / x2 as far,
 *     a stronger pull) and the BARBED HOOK (hook a mob or player and reel = they're yanked to you; 6 s cooldown; refused where
 *     you couldn't hurt them).</li>
 * </ul>
 */
final class Climbers implements Listener {

    private final FaultlineItems plugin;
    final NamespacedKey goatOwner, goatSaddle, gliderKey, hookLine, hookBarb, lineItem, barbItem;
    private final Random rnd = new Random();
    private final Map<UUID, Long> barbReady = new HashMap<>();
    private final Map<UUID, Long> leapReady = new HashMap<>();

    Climbers(FaultlineItems plugin) {
        this.plugin = plugin;
        goatOwner = new NamespacedKey(plugin, "goat_owner");
        goatSaddle = new NamespacedKey(plugin, "goat_saddle");
        gliderKey = new NamespacedKey(plugin, "hang_glider");
        hookLine = new NamespacedKey(plugin, "hook_line");
        hookBarb = new NamespacedKey(plugin, "hook_barb");
        lineItem = new NamespacedKey(plugin, "long_line");
        barbItem = new NamespacedKey(plugin, "barbed_hook");
        recipes();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 1);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("climbers." + path, def); }

    // ================================================================ items

    ItemStack glider() {
        ItemStack it = new ItemStack(Material.ELYTRA);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Hang Glider");
        m.setLore(List.of(ChatColor.GRAY + "Glides like an elytra, but slower,",
                ChatColor.GRAY + "and fireworks don't boost it.",
                ChatColor.DARK_GRAY + "Jump off something high."));
        m.getPersistentDataContainer().set(gliderKey, PersistentDataType.BYTE, (byte) 1);
        if (m instanceof org.bukkit.inventory.meta.Damageable d) d.setMaxDamage((int) cfg("glider.durability", 120));
        it.setItemMeta(m);
        return it;
    }

    ItemStack longLine() {
        ItemStack it = new ItemStack(Material.STRING);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Long Line");
        m.setLore(List.of(ChatColor.GREEN + "Grappling Hook upgrade (anvil, up to II):",
                ChatColor.GREEN + "the hook flies much further and pulls harder."));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(lineItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack barbedHook() {
        ItemStack it = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Barbed Hook");
        m.setLore(List.of(ChatColor.GREEN + "Grappling Hook upgrade (anvil):",
                ChatColor.GREEN + "hook a mob or player and reel in",
                ChatColor.GREEN + "to yank them over to you."));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(barbItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isGlider(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(gliderKey, PersistentDataType.BYTE); }
    private boolean has(ItemStack it, NamespacedKey k) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(k, PersistentDataType.BYTE); }

    private void recipes() {
        try {
            ShapedRecipe g = new ShapedRecipe(new NamespacedKey(plugin, "hang_glider"), glider());
            g.shape("MSM", "STS", " L ");
            g.setIngredient('M', Material.PHANTOM_MEMBRANE); g.setIngredient('S', Material.STICK);
            g.setIngredient('T', Material.STRING); g.setIngredient('L', Material.LEATHER);
            add(g);
            ShapedRecipe l = new ShapedRecipe(new NamespacedKey(plugin, "long_line"), longLine());
            l.shape("SSS", "SIS", "SSS");
            l.setIngredient('S', Material.STRING); l.setIngredient('I', Material.IRON_INGOT);
            add(l);
            ShapedRecipe b = new ShapedRecipe(new NamespacedKey(plugin, "barbed_hook"), barbedHook());
            b.shape(" F ", "FHF", " I ");
            b.setIngredient('F', Material.FLINT); b.setIngredient('H', Material.TRIPWIRE_HOOK); b.setIngredient('I', Material.IRON_INGOT);
            add(b);
        } catch (RuntimeException e) { plugin.getLogger().warning("Climbers recipes: " + e.getMessage()); }
    }

    private static void add(ShapedRecipe r) { if (Bukkit.getRecipe(r.getKey()) == null) Bukkit.addRecipe(r); }

    // ================================================================ tick

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isGliding() && isGlider(p.getInventory().getChestplate())) {
                Vector v = p.getVelocity();
                double max = cfg("glider.max-speed", 1.1);
                if (v.length() > max) Safe.vel(p, v.normalize().multiply(max));
            }
            if (p.getVehicle() instanceof Goat goat && mine(goat, p)) ride(p, goat);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoost(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent e) {
        if (isGlider(e.getPlayer().getInventory().getChestplate())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ChatColor.GRAY + "Fireworks don't boost a Hang Glider.");
        }
    }

    // ================================================================ goats

    private boolean mine(Goat g, Player p) {
        String o = g.getPersistentDataContainer().get(goatOwner, PersistentDataType.STRING);
        return o != null && o.equals(p.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGoatClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Goat g) || e.getHand() != EquipmentSlot.HAND) return;
        if (!plugin.getConfig().getBoolean("climbers.goats.enabled", true)) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String owner = g.getPersistentDataContainer().get(goatOwner, PersistentDataType.STRING);
        boolean saddled = g.getPersistentDataContainer().has(goatSaddle, PersistentDataType.BYTE);
        if (owner == null) {
            if (hand.getType() != Material.WHEAT || !g.isAdult()) return;
            e.setCancelled(true);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            if (rnd.nextDouble() < cfg("goats.tame-chance", 0.25)) {
                g.getPersistentDataContainer().set(goatOwner, PersistentDataType.STRING, p.getUniqueId().toString());
                g.setPersistent(true);
                g.setRemoveWhenFarAway(false);
                g.getWorld().spawnParticle(Particle.HEART, g.getLocation().add(0, 1.2, 0), 6, 0.4, 0.3, 0.4, 0);
                p.sendActionBar(ChatColor.GREEN + "The goat trusts you. Put a Saddle on it to ride it.");
            } else g.getWorld().spawnParticle(Particle.SMOKE, g.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3, 0.01);
            return;
        }
        if (!owner.equals(p.getUniqueId().toString())) return;
        if (!saddled && hand.getType() == Material.SADDLE) {
            e.setCancelled(true);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            g.getPersistentDataContainer().set(goatSaddle, PersistentDataType.BYTE, (byte) 1);
            g.setCustomName((p.getName() + "'s Mountain Goat"));
            g.getWorld().playSound(g.getLocation(), Sound.ENTITY_HORSE_SADDLE, 1f, 1.2f);
            return;
        }
        if (saddled && (hand.getType().isAir() || hand.getType() == Material.WHEAT) && g.getPassengers().isEmpty() && !p.isSneaking()) {
            e.setCancelled(true);
            g.addPassenger(p);
        }
    }

    private void ride(Player p, Goat g) {
        boolean fwd, back, jump, sprint;
        try { var in = p.getCurrentInput(); fwd = in.isForward(); back = in.isBackward(); jump = in.isJump(); sprint = in.isSprint(); }
        catch (Throwable t) { fwd = back = jump = sprint = false; }
        if (g.hasAI()) g.setAI(false);   // its own goat brain would fight the rider (back on when they get off)
        float yaw = p.getLocation().getYaw();
        g.setRotation(yaw, 0);
        Vector dir = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        double speed = (sprint ? cfg("goats.sprint-speed", 0.42) : cfg("goats.speed", 0.3)) * (fwd ? 1 : back ? -0.5 : 0);
        Vector v = g.getVelocity();
        Vector want = dir.multiply(speed);
        double vy = v.getY();
        boolean ground = g.isOnGround();
        if (ground && jump && System.currentTimeMillis() >= leapReady.getOrDefault(g.getUniqueId(), 0L)) {
            vy = cfg("goats.jump", 0.95);
            leapReady.put(g.getUniqueId(), System.currentTimeMillis() + 600);
            g.getWorld().playSound(g.getLocation(), Sound.ENTITY_GOAT_LONG_JUMP, 1f, 1f);
        } else if (ground && speed > 0 && stepAhead(g, dir)) vy = 0.5;   // hop up a 1-block step on its own
        Safe.vel(g, new Vector(want.getX(), vy, want.getZ()));
        g.setFallDistance(0);
        p.setFallDistance(0);
    }

    private static boolean stepAhead(Entity e, Vector dir) {
        Location l = e.getLocation().add(dir.clone().multiply(e.getWidth() / 2 + 0.4));
        Block foot = l.getBlock(), head = foot.getRelative(0, 1, 0), above = foot.getRelative(0, 2, 0);
        return foot.getType().isSolid() && !head.getType().isSolid() && !above.getType().isSolid();
    }

    @EventHandler(ignoreCancelled = true)
    public void onGoatHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Goat g && e.getCause() == EntityDamageEvent.DamageCause.FALL && g.getPersistentDataContainer().has(goatOwner, PersistentDataType.STRING))
            e.setCancelled(true);
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL && p.getVehicle() instanceof Goat) e.setCancelled(true);
    }

    @EventHandler
    public void onGoatDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Goat g && g.getPersistentDataContainer().has(goatSaddle, PersistentDataType.BYTE)) e.getDrops().add(new ItemStack(Material.SADDLE));
    }

    /** Goats ram things: never their own rider. */
    @EventHandler(ignoreCancelled = true)
    public void onRam(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Goat g && e.getEntity() instanceof Player p && mine(g, p)) e.setCancelled(true);
    }

    // ================================================================ grappling hook upgrades

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack left = e.getInventory().getItem(0), right = e.getInventory().getItem(1);
        if (left == null || right == null || !FaultlineItems.GrapplingHookItem.isHook(plugin, left)) return;
        boolean line = has(right, lineItem), barb = has(right, barbItem);
        if (!line && !barb) return;
        ItemStack out = left.clone();
        ItemMeta m = out.getItemMeta();
        int lvl = m.getPersistentDataContainer().getOrDefault(hookLine, PersistentDataType.INTEGER, 0);
        boolean hasBarb = m.getPersistentDataContainer().has(hookBarb, PersistentDataType.BYTE);
        if (line) { if (lvl >= 2) { e.setResult(null); return; } lvl++; m.getPersistentDataContainer().set(hookLine, PersistentDataType.INTEGER, lvl); }
        if (barb) { if (hasBarb) { e.setResult(null); return; } hasBarb = true; m.getPersistentDataContainer().set(hookBarb, PersistentDataType.BYTE, (byte) 1); }
        List<String> lore = new ArrayList<>();
        if (m.getLore() != null) for (String s : m.getLore()) if (!s.contains("Long Line") && !s.contains("Barbed")) lore.add(s);
        if (lvl > 0) lore.add(ChatColor.GOLD + "Long Line " + (lvl == 1 ? "I" : "II") + ": casts " + (lvl == 1 ? "1.5x" : "2x") + " as far");
        if (hasBarb) lore.add(ChatColor.RED + "Barbed: reel in a hooked mob or player");
        m.setLore(lore);
        out.setItemMeta(m);
        e.setResult(out);
        try { e.getView().setRepairCost(5); } catch (Throwable ignored) { }
    }

    private ItemStack rod(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        return main.getType() == Material.FISHING_ROD ? main : p.getInventory().getItemInOffHand();
    }

    private int lineLevel(ItemStack rod) {
        return rod == null || rod.getType().isAir() ? 0 : rod.getPersistentDataContainer().getOrDefault(hookLine, PersistentDataType.INTEGER, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCast(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player p)) return;
        ItemStack rod = rod(p);
        if (!FaultlineItems.GrapplingHookItem.isHook(plugin, rod)) return;
        int lvl = lineLevel(rod);
        if (lvl <= 0) return;
        double mult = lvl == 1 ? cfg("hook.line-1", 1.5) : cfg("hook.line-2", 2.0);
        Bukkit.getScheduler().runTask(plugin, () -> { if (hook.isValid()) Safe.vel(hook, hook.getVelocity().multiply(mult)); });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReel(PlayerFishEvent e) {
        Player p = e.getPlayer();
        ItemStack rod = rod(p);
        if (!FaultlineItems.GrapplingHookItem.isHook(plugin, rod)) return;
        if (e.getState() == PlayerFishEvent.State.IN_GROUND && lineLevel(rod) > 0) {
            // the base hook already launched them: a Long Line pulls harder
            double extra = 1 + 0.15 * lineLevel(rod);
            Bukkit.getScheduler().runTask(plugin, () -> Safe.vel(p, p.getVelocity().multiply(extra)));
            return;
        }
        if (e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || !rod.getPersistentDataContainer().has(hookBarb, PersistentDataType.BYTE)) return;
        Entity caught = e.getCaught();
        if (!(caught instanceof LivingEntity target) || caught.equals(p)) return;
        long now = System.currentTimeMillis();
        if (now < barbReady.getOrDefault(p.getUniqueId(), 0L)) {
            p.sendActionBar(ChatColor.RED + "Barbed Hook: " + ((barbReady.get(p.getUniqueId()) - now + 999) / 1000) + "s");
            return;
        }
        // only where you could hurt them (spawn protection, grace periods, claims...)
        EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(p, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(p).withDirectEntity(p).build(), 0.01);
        Bukkit.getPluginManager().callEvent(probe);
        if (probe.isCancelled()) { p.sendActionBar(ChatColor.GRAY + "You can't hook them here."); return; }
        barbReady.put(p.getUniqueId(), now + (long) (cfg("hook.barb-cooldown-seconds", 6) * 1000));
        Vector to = p.getLocation().toVector().subtract(target.getLocation().toVector());
        double dist = to.length();
        if (dist < 1) return;
        Vector pull = to.normalize().multiply(Math.min(cfg("hook.barb-max-speed", 1.8), 0.6 + dist * 0.08)).setY(0.35 + Math.min(0.4, Math.max(0, to.getY()) * 0.05));
        Bukkit.getScheduler().runTask(plugin, () -> { if (target.isValid()) Safe.vel(target, pull); });
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.5f);
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 0.8f);
    }

    @EventHandler
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (e.getDismounted() instanceof Goat g && !g.hasAI() && g.getPersistentDataContainer().has(goatOwner, PersistentDataType.STRING)) g.setAI(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        barbReady.remove(e.getPlayer().getUniqueId());
        if (e.getPlayer().getVehicle() instanceof Goat g && !g.hasAI()) { g.setAI(true); g.removePassenger(e.getPlayer()); }
    }

    /** A goat left without AI (a crash mid-ride) gets it back when it loads with nobody on it. */
    @EventHandler
    public void onLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity en : e.getEntities())
            if (en instanceof Goat g && !g.hasAI() && g.getPassengers().isEmpty() && g.getPersistentDataContainer().has(goatOwner, PersistentDataType.STRING)) g.setAI(true);
    }
}
