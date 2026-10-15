package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * RANGER GEAR (Items 1.2.27, config ranger-gear:): eight accessories for bow players, worn in /accessories like any other.
 *  - Hawkeye Monocle (craft: gold + spyglass + eye of ender): sneak while drawing a bow = a zoomed scope and the
 *    distance to whatever you aim at in the action bar.
 *  - Quickdraw Glove (1% Skeletons/Strays/Bogged): bows hit at full power from 75% draw (the shot is sped up to match).
 *  - Fletcher's Pouch (craft: leather, string, rabbit hide, an arrow): 30% of shots don't use an arrow (those can't be
 *    picked up again, so no duping).
 *  - Windrunner Band (3% Breezes): arrows fly 20% faster (further, and a little harder).
 *  - Ricochet Charm (VERY rare: 1.5% from Elite skeletons, 1% from a Bone Commander): an arrow that hits a block
 *    bounces off it once, at 75% speed.
 *  - Phantom Fletching (2% Phantoms): arrows pierce one more target.
 *  - Wolfcaller Whistle (craft: bones, a goat horn, gold): your tamed wolves within 32 blocks attack whatever you shoot.
 *  - Ranger's Oath Pendant (3% Evokers, or the EXTREME "The Ranger's Oath" Quest Book quest): with two other ranger
 *    accessories worn (the Ranger Emblem counts), +1 heart, +10% arrow damage (on hit) and +10% speed.
 * Base items are inert ones (no bundle, no bone a wolf would eat, no emerald a villager would take).
 * Drops only from mobs a player killed (no spawner mobs). All in the Index; /giveaccessory names below; /itemsmenu.
 */
final class RangerGear implements Listener {

    enum Piece {
        MONOCLE("hawkeye_monocle", "monocle", Material.SPYGLASS, ChatColor.GOLD, "Hawkeye Monocle",
                "Sneak while drawing a bow to zoom in", "and see how far your target is."),
        QUICKDRAW("quickdraw_glove", "quickdraw", Material.LEATHER, ChatColor.YELLOW, "Quickdraw Glove",
                "Bows shoot at full power", "from 75% draw."),
        POUCH("fletchers_pouch", "pouch", Material.RABBIT_HIDE, ChatColor.GREEN, "Fletcher's Pouch",
                "30% of your shots don't", "use up an arrow."),
        WINDRUNNER("windrunner_band", "windrunner", Material.BREEZE_ROD, ChatColor.AQUA, "Windrunner Band",
                "Arrows fly 8% faster", "and further."),
        RICOCHET("ricochet_charm", "ricochet", Material.PRISMARINE_SHARD, ChatColor.LIGHT_PURPLE, "Ricochet Charm",
                "Arrows that hit a wall", "bounce off it once."),
        PHANTOM("phantom_fletching", "phantom", Material.FEATHER, ChatColor.DARK_AQUA, "Phantom Fletching",
                "Arrows pass through", "one more target."),
        WHISTLE("wolfcaller_whistle", "whistle", Material.FLINT, ChatColor.WHITE, "Wolfcaller Whistle",
                "Your tamed wolves attack", "whatever you shoot."),
        OATH("rangers_oath", "oath", Material.ECHO_SHARD, ChatColor.DARK_GREEN, "Ranger's Oath Pendant",
                "With 2 other ranger accessories:", "+1 heart, +10% arrow damage and +10% speed.");

        final String id, command, name;
        final Material base;
        final ChatColor color;
        final String[] lore;
        Piece(String id, String command, Material base, ChatColor color, String name, String... lore) {
            this.id = id; this.command = command; this.base = base; this.color = color; this.name = name; this.lore = lore;
        }
    }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final java.util.Map<Piece, NamespacedKey> keys = new java.util.EnumMap<>(Piece.class);
    final NamespacedKey ricochetKey, bowEnchKey, fullKey, oathHealth, oathSpeed;

    RangerGear(FaultlineItems plugin) {
        this.plugin = plugin;
        for (Piece p : Piece.values()) keys.put(p, new NamespacedKey(plugin, p.id));
        ricochetKey = new NamespacedKey(plugin, "ricochet");
        bowEnchKey = new NamespacedKey(plugin, "arrow_fenchants");
        fullKey = new NamespacedKey(plugin, "arrow_full");
        oathHealth = new NamespacedKey(plugin, "ranger_oath_health");
        oathSpeed = new NamespacedKey(plugin, "ranger_oath_speed");
        Bukkit.getScheduler().runTaskTimer(plugin, this::scope, 4L, 4L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::oathTick, 20L, 20L);
        recipes();
    }

    NamespacedKey key(Piece p) { return keys.get(p); }

    ItemStack item(Piece p) {
        String[] lore = new String[p.lore.length + 1];
        for (int i = 0; i < p.lore.length; i++) lore[i] = ChatColor.GREEN + p.lore[i];
        lore[p.lore.length] = ChatColor.DARK_GREEN + "Ranger gear";
        return FaultlineItems.NewAccessoryItems.make(plugin, p.base, p.color + "" + ChatColor.BOLD + p.name, key(p), lore);
    }

    boolean wearing(Player p, Piece piece) { return plugin.getAccessoryManager().hasEquipped(p, key(piece)); }

    /** Ranger accessories worn, the Ranger Emblem included. */
    int rangerCount(Player p) {
        int n = plugin.getAccessoryManager().hasEquipped(p, plugin.getRangerEmblemKey()) ? 1 : 0;
        for (Piece piece : Piece.values()) if (wearing(p, piece)) n++;
        return n;
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("ranger-gear." + path, def); }

    // ---------------------------------------------------------------- recipes

    private void recipes() {
        try {
            ShapedRecipe mono = new ShapedRecipe(new NamespacedKey(plugin, "hawkeye_monocle_recipe"), item(Piece.MONOCLE));
            mono.shape(" G ", "GYG", " E ");
            mono.setIngredient('G', Material.GOLD_INGOT); mono.setIngredient('Y', Material.SPYGLASS); mono.setIngredient('E', Material.ENDER_EYE);
            Bukkit.addRecipe(mono);
            ShapedRecipe pouch = new ShapedRecipe(new NamespacedKey(plugin, "fletchers_pouch_recipe"), item(Piece.POUCH));
            pouch.shape("LSL", "RAR", "LLL");
            pouch.setIngredient('L', Material.LEATHER); pouch.setIngredient('S', Material.STRING);
            pouch.setIngredient('R', Material.RABBIT_HIDE); pouch.setIngredient('A', Material.ARROW);
            Bukkit.addRecipe(pouch);
            ShapedRecipe whistle = new ShapedRecipe(new NamespacedKey(plugin, "wolfcaller_whistle_recipe"), item(Piece.WHISTLE));
            whistle.shape(" B ", "BHB", " G ");
            whistle.setIngredient('B', Material.BONE); whistle.setIngredient('H', Material.GOAT_HORN); whistle.setIngredient('G', Material.GOLD_INGOT);
            Bukkit.addRecipe(whistle);
        } catch (RuntimeException e) { plugin.getLogger().warning("Ranger gear recipes: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- drops

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v instanceof Player || v.getKiller() == null) return;
        try {
            if (v.fromMobSpawner()) return;
            var reason = v.getEntitySpawnReason();
            if (reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                    || reason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        } catch (RuntimeException ignored) { } // (the test server doesn't know)
        boolean elite = v.getScoreboardTags().contains("faultline_elite");
        switch (v.getType()) {
            case SKELETON, STRAY, BOGGED -> {
                if (!v.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline") && !t.equals("faultline_elite") && !t.equals("faultline_blazing")))
                    roll(e, "quickdraw-drop-chance", 0.01, Piece.QUICKDRAW);
                if (elite) roll(e, "ricochet-elite-skeleton-chance", 0.015, Piece.RICOCHET);
            }
            case BREEZE -> roll(e, "windrunner-drop-chance", 0.03, Piece.WINDRUNNER);
            case PHANTOM -> roll(e, "phantom-drop-chance", 0.02, Piece.PHANTOM);
            case EVOKER -> roll(e, "oath-drop-chance", 0.03, Piece.OATH);
            default -> { }
        }
        if (v.getScoreboardTags().contains("faultline_bone_commander")) roll(e, "ricochet-commander-chance", 0.01, Piece.RICOCHET);
    }

    private void roll(EntityDeathEvent e, String path, double def, Piece p) {
        if (random.nextDouble() < cfg(path, def) * plugin.eventDropMultiplier()) e.getDrops().add(item(p));
    }

    // ---------------------------------------------------------------- shooting

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        ItemStack bow = e.getBow();
        boolean isBow = bow != null && bow.getType() == Material.BOW;
        double force = e.getForce();
        Vector vel = arrow.getVelocity();
        double mult = 1;
        // Quickdraw: a 75% draw counts as full (bows only: a crossbow is always full)
        if (isBow && force < 1 && wearing(p, Piece.QUICKDRAW)) {
            double full = cfg("quickdraw-full-at", 0.75);
            double effective = Math.min(1, force / full);
            if (force > 0.05) mult *= effective / force;
            force = effective;
            if (effective >= 1) arrow.setCritical(true);
        }
        if (wearing(p, Piece.WINDRUNNER)) mult *= Balance.num(plugin, "ranger-gear.windrunner-speed", 1.2, 1.08);
        if (mult != 1) arrow.setVelocity(vel.multiply(mult));
        if (wearing(p, Piece.PHANTOM)) arrow.setPierceLevel(Math.min(127, arrow.getPierceLevel() + 1));
        if (wearing(p, Piece.RICOCHET)) arrow.getPersistentDataContainer().set(ricochetKey, PersistentDataType.BYTE, (byte) 1);
        if (wearing(p, Piece.POUCH) && e.shouldConsumeItem() && p.getGameMode() != GameMode.CREATIVE && random.nextDouble() < cfg("pouch-save-chance", 0.3)) {
            e.setConsumeItem(false);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY); // the saved arrow stays in your quiver; this one can't be picked up
        }
        // boss enchant books on bows (Frostbite, Venom, Lifesteal, Thunderstrike): carried by the arrow
        if (isBow && plugin.enchants != null) {
            String s = bow.hasItemMeta() ? bow.getItemMeta().getPersistentDataContainer().get(plugin.enchants.itemKey, PersistentDataType.STRING) : null;
            if (s != null && !s.isEmpty()) {
                arrow.getPersistentDataContainer().set(bowEnchKey, PersistentDataType.STRING, s);
                if (force >= 0.9) arrow.getPersistentDataContainer().set(fullKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }

    // ---------------------------------------------------------------- ricochet

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLand(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow a) || e.getHitBlock() == null || e.getHitBlockFace() == null) return;
        PersistentDataContainer pdc = a.getPersistentDataContainer();
        if (!pdc.has(ricochetKey, PersistentDataType.BYTE)) return;
        pdc.remove(ricochetKey);
        Vector v = a.getVelocity();
        if (v.lengthSquared() < 0.25) return; // too slow to bounce
        BlockFace f = e.getHitBlockFace();
        Vector n = new Vector(f.getModX(), f.getModY(), f.getModZ());
        Vector out = v.clone().subtract(n.clone().multiply(2 * v.dot(n))).multiply(cfg("ricochet-speed", 0.75));
        Location at = a.getLocation().add(n.clone().multiply(0.3));
        if (!(at.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.ARROW) instanceof Arrow b)) return;
        b.setShooter(a.getShooter());
        b.setDamage(a.getDamage());
        b.setCritical(a.isCritical());
        b.setPierceLevel(a.getPierceLevel());
        b.setFireTicks(a.getFireTicks());
        b.setPickupStatus(a.getPickupStatus());
        // 1.2.29: each key read with its own type (reading the BYTE flag as a STRING threw, after the bounce arrow was
        // already spawned: an enchanted bow + a Ricochet Charm left two arrows and an error)
        String ench = pdc.get(bowEnchKey, PersistentDataType.STRING);
        if (ench != null) b.getPersistentDataContainer().set(bowEnchKey, PersistentDataType.STRING, ench);
        if (pdc.has(fullKey, PersistentDataType.BYTE)) b.getPersistentDataContainer().set(fullKey, PersistentDataType.BYTE, (byte) 1);
        Safe.vel(b, out);
        a.remove();
        at.getWorld().playSound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 0.7f, 1.8f);
        at.getWorld().spawnParticle(Particle.CRIT, at, 6, 0.1, 0.1, 0.1, 0.05);
    }

    // ---------------------------------------------------------------- arrow hits

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow arrow) || arrow instanceof Trident || !(arrow.getShooter() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity victim) || victim instanceof org.bukkit.entity.ArmorStand || victim.equals(p)) return;
        // Ranger's Oath set bonus (1.2.38): +arrow damage with the full set on
        if (wearing(p, Piece.OATH) && rangerCount(p) >= 3) e.setDamage(e.getDamage() * (1 + cfg("oath-arrow-bonus", 0.10)));
        // Wolfcaller: your wolves join in
        if (wearing(p, Piece.WHISTLE) && !(victim instanceof Tameable t && t.isTamed() && p.equals(t.getOwner()))) {
            for (Entity n : p.getNearbyEntities(32, 16, 32)) {
                if (n instanceof Wolf w && w.isTamed() && p.equals(w.getOwner()) && !w.isSitting() && !w.equals(victim)) w.setTarget(victim);
            }
        }
        // bow enchant books
        String s = arrow.getPersistentDataContainer().get(bowEnchKey, PersistentDataType.STRING);
        if (s != null && plugin.enchants != null) {
            boolean full = arrow.getPersistentDataContainer().has(fullKey, PersistentDataType.BYTE);
            plugin.enchants.applyRanged(p, victim, e, s, full);
        }
    }

    // ---------------------------------------------------------------- the scope

    void scope() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isSneaking() || !p.isHandRaised()) continue;
            Material hand = p.getInventory().getItemInMainHand().getType();
            if (hand != Material.BOW && hand != Material.CROSSBOW) continue;
            if (!wearing(p, Piece.MONOCLE)) continue;
            // a heavy slowness while sneak-drawing = the camera zooms in (the field of view follows walk speed)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 8, 3, false, false, false));
            String what;
            RayTraceResult r = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), cfg("monocle-range", 160),
                    org.bukkit.FluidCollisionMode.NEVER, true, 0.3, x -> x != p && x instanceof LivingEntity && !(x instanceof org.bukkit.entity.ArmorStand));
            if (r != null && r.getHitEntity() instanceof LivingEntity le) {
                String n = le instanceof Player op ? op.getName() : le.getCustomName() != null ? ChatColor.stripColor(le.getCustomName())
                        : le.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                what = ChatColor.RED + "🎯 " + n + ChatColor.GRAY + " · " + ChatColor.WHITE + Math.round(le.getLocation().distance(p.getLocation())) + " m";
            } else if (r != null && r.getHitBlock() != null) {
                what = ChatColor.GRAY + "🎯 " + ChatColor.WHITE + Math.round(r.getHitPosition().distance(p.getEyeLocation().toVector())) + " m";
            } else what = ChatColor.GRAY + "🎯 out of range";
            p.sendActionBar(what);
        }
    }

    // ---------------------------------------------------------------- the oath set bonus

    void oathTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean on = wearing(p, Piece.OATH) && rangerCount(p) >= 3;
            // 1.2.39: the set bonus is +1 heart (+2 MAX_HEALTH), +arrow damage (applied on hit) and +10% speed. An older
            // +2 hearts (amount 4) is replaced by set() because the amount changed; a server config still saying 4 reads as 2.
            set(p, Attribute.MAX_HEALTH, oathHealth, on ? Balance.num(plugin, "ranger-gear.oath-health", 4, 2) : 0, AttributeModifier.Operation.ADD_NUMBER);
            set(p, Attribute.MOVEMENT_SPEED, oathSpeed, on ? Balance.num(plugin, "ranger-gear.oath-speed", 0.2, 0.1) : 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        }
    }

    private void set(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance in = p.getAttribute(a);
        if (in == null) return;
        AttributeModifier have = null;
        for (AttributeModifier m : in.getModifiers()) if (m.getKey().equals(key)) have = m;
        if (have != null && have.getAmount() == amount) return;
        if (have != null) in.removeModifier(have);
        if (amount != 0) in.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
        if (a == Attribute.MAX_HEALTH && p.getHealth() > in.getValue()) p.setHealth(in.getValue());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        set(e.getPlayer(), Attribute.MAX_HEALTH, oathHealth, 0, AttributeModifier.Operation.ADD_NUMBER);
        set(e.getPlayer(), Attribute.MOVEMENT_SPEED, oathSpeed, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    void shutdown() { for (Player p : Bukkit.getOnlinePlayers()) { set(p, Attribute.MAX_HEALTH, oathHealth, 0, AttributeModifier.Operation.ADD_NUMBER); set(p, Attribute.MOVEMENT_SPEED, oathSpeed, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1); } }

    List<ItemStack> all() { List<ItemStack> l = new ArrayList<>(); for (Piece p : Piece.values()) l.add(item(p)); return l; }
}
