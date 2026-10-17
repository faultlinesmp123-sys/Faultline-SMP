package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DIVE HELMETS (1.2.57): helmets with an OXYGEN TANK. Underwater the tank drains instead of your breath (your air stays
 * full until it's empty), shown in the action bar; it refills at the surface or anywhere you can breathe (a Bubble's air,
 * the Pit), and fast in a bubble column or with Conduit Power.
 *  1 Copper Dive Helmet      120 s, 2 armor       (copper blocks, glass, a nautilus shell)
 *  2 Reinforced Dive Helmet  300 s, 3 armor       (the copper one, iron blocks, prismarine crystals)
 *  3 Hadal Dive Helmet       600 s, 3 armor +2 toughness; with the Abyssal Diving Suit it keeps the Trench's hadal
 *                            pressure out (FaultlineDeep reads faultlineitems:oxygen_helmet = the tier off the worn helmet)
 * Worn models: tools/wild_assets.py worn_items (item model faultline:dive_helmet_<n>, 3D on the head, flat in the inventory).
 */
final class DiveHelmets {
    static final String[] IDS = {"dive_helmet_1", "dive_helmet_2", "dive_helmet_3"};
    static final String[] NAMES = {"Copper Dive Helmet", "Reinforced Dive Helmet", "Hadal Dive Helmet"};
    static final ChatColor[] COLORS = {ChatColor.GOLD, ChatColor.WHITE, ChatColor.DARK_PURPLE};

    final FaultlineItems plugin;
    final NamespacedKey tierKey, oxyKey, armorKey, toughKey;
    final Set<UUID> told = new HashSet<>();
    int ticks;

    DiveHelmets(FaultlineItems plugin) {
        this.plugin = plugin;
        tierKey = new NamespacedKey(plugin, "oxygen_helmet");
        oxyKey = new NamespacedKey(plugin, "oxygen");
        armorKey = new NamespacedKey(plugin, "dive_helmet_armor");
        toughKey = new NamespacedKey(plugin, "dive_helmet_toughness");
        recipes();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[Dive helmets] " + e); } }, 20L, 10L);
    }

    double cfg(String path, double def) { return plugin.getConfig().getDouble("dive-helmets." + path, def); }

    /** The tank, in seconds, of a tier. */
    double tank(int tier) { return switch (tier) { case 1 -> cfg("tank-seconds.copper", 120); case 2 -> cfg("tank-seconds.reinforced", 300); default -> cfg("tank-seconds.hadal", 600); }; }

    static int tierOf(String id) { for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i + 1; return 0; }

    ItemStack item(int tier) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(COLORS[tier - 1] + "" + ChatColor.BOLD + NAMES[tier - 1]);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.AQUA + "Oxygen tank: " + (int) tank(tier) + " s underwater");
        lore.add(ChatColor.GRAY + "Your breath lasts until the tank is empty.");
        lore.add(ChatColor.GRAY + "Refills at the surface, fast in bubble columns.");
        if (tier == 3) { lore.add(ChatColor.LIGHT_PURPLE + "Rated for the Trench: with the Abyssal"); lore.add(ChatColor.LIGHT_PURPLE + "Diving Suit, hadal pressure can't touch you."); }
        lore.add(ChatColor.BLUE + "+" + (tier == 1 ? 2 : 3) + " Armor" + (tier == 3 ? ", +2 Toughness" : ""));
        m.setLore(lore);
        m.setItemModel(new NamespacedKey("faultline", IDS[tier - 1]));
        try {
            var eq = m.getEquippable();
            eq.setSlot(org.bukkit.inventory.EquipmentSlot.HEAD);
            eq.setEquipSound(Sound.ITEM_ARMOR_EQUIP_IRON);
            m.setEquippable(eq);
        } catch (RuntimeException | NoSuchMethodError ignored) { }
        m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(armorKey, tier == 1 ? 2 : 3, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        if (tier == 3) m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(toughKey, 2, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        m.getPersistentDataContainer().set(plugin.gear.gearKey, PersistentDataType.STRING, IDS[tier - 1]);
        s.setItemMeta(m);
        return s;
    }

    int worn(Player p) {
        ItemStack h = p.getInventory().getHelmet();
        if (h == null || !h.hasItemMeta()) return 0;
        Integer t = h.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    void recipes() {
        ShapedRecipe a = new ShapedRecipe(new NamespacedKey(plugin, "dive_helmet_1"), item(1));
        a.shape("CCC", "CGC", " N ");
        a.setIngredient('C', Material.COPPER_BLOCK); a.setIngredient('G', Material.GLASS); a.setIngredient('N', Material.NAUTILUS_SHELL);
        FaultlineItems.addRecipeSafely(a);
        ShapedRecipe b = new ShapedRecipe(new NamespacedKey(plugin, "dive_helmet_2"), item(2));
        b.shape("IPI", "PHP", "IPI");
        b.setIngredient('I', Material.IRON_BLOCK); b.setIngredient('P', Material.PRISMARINE_CRYSTALS); b.setIngredient('H', new RecipeChoice.ExactChoice(item(1)));
        FaultlineItems.addRecipeSafely(b);
        ShapedRecipe c = new ShapedRecipe(new NamespacedKey(plugin, "dive_helmet_3"), item(3));
        c.shape("EKE", "NHN", "EKE");
        c.setIngredient('E', Material.ECHO_SHARD); c.setIngredient('K', Material.HEART_OF_THE_SEA); c.setIngredient('N', Material.NETHERITE_INGOT);
        c.setIngredient('H', new RecipeChoice.ExactChoice(item(2)));
        FaultlineItems.addRecipeSafely(c);
    }

    double oxygen(Player p, double cap) { return Math.min(cap, p.getPersistentDataContainer().getOrDefault(oxyKey, PersistentDataType.DOUBLE, cap)); }

    void setOxygen(Player p, double v) { p.getPersistentDataContainer().set(oxyKey, PersistentDataType.DOUBLE, v); }

    /** Every half second. */
    void tick() {
        ticks++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            int tier = worn(p);
            if (tier == 0 || p.getGameMode() == GameMode.SPECTATOR) continue;
            double cap = tank(tier), o = oxygen(p, cap);
            boolean under = p.getEyeLocation().getBlock().getType() == Material.WATER || p.getEyeLocation().getBlock().getType() == Material.BUBBLE_COLUMN
                    || p.getEyeLocation().getBlock().getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
            if (!under) { // breathing: it fills in 10 s
                if (o < cap) setOxygen(p, Math.min(cap, o + cap / 20));
                told.remove(p.getUniqueId());
                continue;
            }
            boolean fast = p.getEyeLocation().getBlock().getType() == Material.BUBBLE_COLUMN || p.hasPotionEffect(PotionEffectType.CONDUIT_POWER);
            if (fast) o = Math.min(cap, o + cfg("bubble-refill-per-second", 10) * 0.5);
            else if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) o = Math.max(0, o - 0.5);
            setOxygen(p, o);
            if (o > 0) p.setRemainingAir(p.getMaximumAir()); // the tank breathes for you
            else if (told.add(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "Your oxygen tank is empty! " + ChatColor.GRAY + "Get to the surface, or a bubble column.");
                p.playSound(p, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1f, 0.5f);
            }
            if (ticks % 2 == 0 || o < 15) bar(p, o, cap, fast);
        }
    }

    void bar(Player p, double o, double cap, boolean fast) {
        int n = 20, full = (int) Math.round(o / cap * n);
        ChatColor c = o / cap > 0.5 ? ChatColor.AQUA : o / cap > 0.2 ? ChatColor.YELLOW : ChatColor.RED;
        StringBuilder sb = new StringBuilder(ChatColor.WHITE + "O" + ChatColor.GRAY + "2 " + c);
        for (int i = 0; i < n; i++) { if (i == full) sb.append(ChatColor.DARK_GRAY); sb.append('|'); }
        sb.append(" ").append(c).append((int) Math.ceil(o)).append("s").append(fast ? ChatColor.AQUA + " (refilling)" : "");
        p.sendActionBar(sb.toString());
    }
}
