package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Diving gear, after Calamity's chain: Diving Helmet + Flipper = Diving Gear; Diving Gear + Depths Charm + a Heart of
 * the Sea = Abyssal Diving Gear. Also "Crush Depth" (deep underwater and out of breath hurts), and every player's
 * diving tier is written to their data each second (faultlineitems:diving_tier, 0-3) so a future underwater boss can
 * read it with one line.
 */
final class DivingGear implements Listener {

    enum Piece {
        HELMET("diving_helmet", Material.NAUTILUS_SHELL, ChatColor.GOLD, "Diving Helmet",
                ChatColor.GREEN + "3x longer breath underwater"),
        FLIPPER("flipper", Material.PHANTOM_MEMBRANE, ChatColor.DARK_AQUA, "Flipper",
                ChatColor.GREEN + "Swim much faster"),
        GEAR("diving_gear", Material.PRISMARINE_CRYSTALS, ChatColor.AQUA, "Diving Gear",
                ChatColor.GREEN + "3x longer breath underwater", ChatColor.GREEN + "Swim much faster",
                ChatColor.DARK_GRAY + "(a Diving Helmet and a Flipper in one slot)"),
        DEPTHS("depths_charm", Material.ECHO_SHARD, ChatColor.DARK_BLUE, "Depths Charm",
                ChatColor.GREEN + "Halves Crush Depth damage", ChatColor.DARK_GRAY + "(deep underwater, out of breath)"),
        ABYSSAL("abyssal_diving_gear", Material.HEART_OF_THE_SEA, ChatColor.DARK_AQUA, "Abyssal Diving Gear",
                ChatColor.GREEN + "6x longer breath underwater", ChatColor.GREEN + "83% less drowning damage",
                ChatColor.GREEN + "Swim much faster", ChatColor.GREEN + "See clearly underwater",
                ChatColor.GREEN + "Halves Crush Depth damage"),
        SUIT("abyssal_diving_suit", Material.NETHERITE_SCRAP, ChatColor.DARK_PURPLE, "Abyssal Diving Suit",
                ChatColor.GREEN + "Breathe underwater", ChatColor.GREEN + "Immune to Crush Depth",
                ChatColor.GREEN + "Everything the Abyssal Diving Gear does",
                ChatColor.GREEN + "3 plates: each absorbs 15% of a hit underwater",
                ChatColor.DARK_GRAY + "(a broken plate regrows after 20 seconds)",
                ChatColor.DARK_GRAY + "Dropped by the Kraken.");

        final String id, name;
        final Material material;
        final ChatColor color;
        final String[] lore;

        Piece(String id, Material material, ChatColor color, String name, String... lore) {
            this.id = id; this.material = material; this.color = color; this.name = name; this.lore = lore;
        }
    }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final Map<Piece, NamespacedKey> keys = new java.util.EnumMap<>(Piece.class);
    private final NamespacedKey tierKey, gearRecipe, abyssalRecipe;
    private final Set<UUID> gaveNightVision = new HashSet<>();

    DivingGear(FaultlineItems plugin) {
        this.plugin = plugin;
        for (Piece p : Piece.values()) keys.put(p, new NamespacedKey(plugin, p.id));
        tierKey = new NamespacedKey(plugin, "diving_tier");
        gearRecipe = new NamespacedKey(plugin, "diving_gear_recipe");
        abyssalRecipe = new NamespacedKey(plugin, "abyssal_diving_gear_recipe");
        FaultlineItems.addRecipeSafely(recipeGear());
        FaultlineItems.addRecipeSafely(recipeAbyssal());
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[Diving gear] " + e); }
        }, 20L, 20L);
    }

    ItemStack item(Piece p) {
        return FaultlineItems.NewAccessoryItems.make(plugin, p.material, p.color + "" + ChatColor.BOLD + p.name, keys.get(p), p.lore);
    }

    /** For give commands and the Skeleton Wanderer. */
    Map<String, Function<FaultlineItems, ItemStack>> catalog() {
        Map<String, Function<FaultlineItems, ItemStack>> m = new LinkedHashMap<>();
        m.put("helmet", pl -> item(Piece.HELMET));
        m.put("flipper", pl -> item(Piece.FLIPPER));
        m.put("gear", pl -> item(Piece.GEAR));
        m.put("depths", pl -> item(Piece.DEPTHS));
        m.put("abyssal", pl -> item(Piece.ABYSSAL));
        m.put("suit", pl -> item(Piece.SUIT));
        return m;
    }

    boolean isOurRecipe(Recipe r) {
        return r instanceof org.bukkit.Keyed k && (k.getKey().equals(gearRecipe) || k.getKey().equals(abyssalRecipe));
    }

    private ShapelessRecipe recipeGear() {
        ShapelessRecipe r = new ShapelessRecipe(gearRecipe, item(Piece.GEAR));
        r.addIngredient(new RecipeChoice.ExactChoice(item(Piece.HELMET)));
        r.addIngredient(new RecipeChoice.ExactChoice(item(Piece.FLIPPER)));
        return r;
    }

    private ShapelessRecipe recipeAbyssal() {
        ShapelessRecipe r = new ShapelessRecipe(abyssalRecipe, item(Piece.ABYSSAL));
        r.addIngredient(new RecipeChoice.ExactChoice(item(Piece.GEAR)));
        r.addIngredient(new RecipeChoice.ExactChoice(item(Piece.DEPTHS)));
        r.addIngredient(Material.HEART_OF_THE_SEA);
        return r;
    }

    private boolean has(Player p, Piece... any) {
        for (Piece piece : any) if (plugin.getAccessoryManager().hasEquipped(p, keys.get(piece))) return true;
        return false;
    }

    /** 0 none, 1 a helmet or a flipper, 2 Diving Gear, 3 Abyssal Diving Gear, 4 the Abyssal Diving Suit. */
    int tier(Player p) {
        if (has(p, Piece.SUIT)) return 4;
        if (has(p, Piece.ABYSSAL)) return 3;
        if (has(p, Piece.GEAR)) return 2;
        if (has(p, Piece.HELMET, Piece.FLIPPER)) return 1;
        return 0;
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("diving." + path, def); }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int tier = tier(p);
            p.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier); // for a future underwater boss
            boolean underwater = p.isInWater() && p.getEyeLocation().getBlock().getType() == Material.WATER;
            if (p.isInWater() && has(p, Piece.FLIPPER, Piece.GEAR, Piece.ABYSSAL, Piece.SUIT))
                p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 0, true, false, true));
            // light underwater (Abyssal): night vision while submerged, taken back when you surface
            if (underwater && has(p, Piece.ABYSSAL, Piece.SUIT)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 320, 0, true, false, true));
                gaveNightVision.add(p.getUniqueId());
            } else if (gaveNightVision.remove(p.getUniqueId())) {
                PotionEffect nv = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (nv != null && nv.isAmbient() && nv.getDuration() <= 320) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
            // the suit: water breathing underwater, and its plates regrow (one every 20 seconds)
            boolean suit = has(p, Piece.SUIT);
            if (suit && underwater) p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 40, 0, true, false, true));
            if (suit) {
                int plates = plates(p);
                int since = regrow.merge(p.getUniqueId(), 1, Integer::sum);
                if (plates < 3 && since >= 20) { setPlates(p, plates + 1); regrow.put(p.getUniqueId(), 0); }
            }
            // Crush Depth: deep underwater and out of breath (the suit is immune)
            if (!suit && plugin.getConfig().getBoolean("diving.crush-depth.enabled", true) && underwater && p.getRemainingAir() <= 0
                    && p.getLocation().getY() < cfg("crush-depth.below-y", 0)
                    && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                double dmg = cfg("crush-depth.damage", 2.0);
                if (has(p, Piece.DEPTHS, Piece.ABYSSAL)) dmg *= 0.5;
                p.damage(dmg);
                p.sendActionBar(ChatColor.DARK_BLUE + "" + ChatColor.BOLD + "Crush Depth! " + ChatColor.GRAY + "The pressure is crushing you.");
            }
        }
    }

    /** Breath: each lost bubble has a chance to be saved (Helmet/Gear 2 in 3 = 3x breath, Abyssal 5 in 6 = 6x). */
    @EventHandler(ignoreCancelled = true)
    public void onAir(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (event.getAmount() >= p.getRemainingAir()) return; // only when losing air
        double save = has(p, Piece.ABYSSAL, Piece.SUIT) ? 5.0 / 6 : has(p, Piece.HELMET, Piece.GEAR) ? 2.0 / 3 : 0;
        if (save > 0 && random.nextDouble() < save) event.setCancelled(true);
    }

    private final Map<UUID, Integer> regrow = new java.util.HashMap<>();
    private NamespacedKey platesKey() { return new NamespacedKey(plugin, "suit_plates"); }
    int plates(Player p) { return p.getPersistentDataContainer().getOrDefault(platesKey(), PersistentDataType.INTEGER, 3); }
    void setPlates(Player p, int n) { p.getPersistentDataContainer().set(platesKey(), PersistentDataType.INTEGER, Math.max(0, Math.min(3, n))); }

    /** The suit's plates: underwater, each absorbs 15% of one hit, then breaks (and regrows after 20 seconds). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlates(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || !p.isInWater() || !has(p, Piece.SUIT)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING) return;
        int plates = plates(p);
        if (plates <= 0) return;
        event.setDamage(event.getDamage() * 0.85);
        setPlates(p, plates - 1);
        regrow.put(p.getUniqueId(), 0);
        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.6f, 1.6f);
        p.sendActionBar(ChatColor.DARK_PURPLE + "Suit plate cracked " + ChatColor.GRAY + "(" + (plates - 1) + " left)");
    }

    /** Abyssal: 83% less drowning damage (Calamity's 17.14/s down to 2.86/s). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrown(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.DROWNING || !(event.getEntity() instanceof Player p)) return;
        if (has(p, Piece.ABYSSAL, Piece.SUIT)) event.setDamage(event.getDamage() / 6.0);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        EntityType type = event.getEntityType();
        Piece piece = null; double chance = 0;
        if (type == EntityType.DROWNED) { piece = Piece.HELMET; chance = cfg("drops.helmet-from-drowned", 0.015); }
        else if (type == EntityType.GUARDIAN) { piece = Piece.FLIPPER; chance = cfg("drops.flipper-from-guardians", 0.02); }
        else if (type == EntityType.ELDER_GUARDIAN) { piece = Piece.DEPTHS; chance = cfg("drops.depths-charm-from-elder-guardians", 0.33); }
        if (piece != null && random.nextDouble() < chance) {
            event.getDrops().add(item(piece));
            Player k = event.getEntity().getKiller();
            k.playSound(k.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.2f);
        }
    }
}
