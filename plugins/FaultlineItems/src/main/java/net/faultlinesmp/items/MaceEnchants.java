package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

/**
 * MACE ENCHANTMENTS (Items 1.2.55, config mace-enchants:). Maces are allowed again, but a mace can only carry
 * Unbreaking III, Wind Burst II and Mending (`allowed`): anything else is stripped and levels are capped, wherever the
 * mace gets them: the enchanting table, the anvil (books or another mace), and maces players already have (their
 * inventories are checked every 2 s, on pickup and when held). Enchanted books of Wind Burst are capped to II too, so
 * nobody can get Wind Burst III at all. faultline.mace.bypass skips it.
 */
final class MaceEnchants implements Listener {
    final FaultlineItems plugin;

    MaceEnchants(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { try { for (Player p : Bukkit.getOnlinePlayers()) sweep(p); } catch (RuntimeException ignored) { } }, 47L, 40L);
    }

    boolean on() { return plugin.getConfig().getBoolean("mace-enchants.enabled", true); }

    /**
     * Allowed enchantment id ("wind_burst") -> its max level. Matched BY NAME, never by Enchantment object: on the live
     * server the objects from the registry and from an item didn't compare equal, so Wind Burst II was stripped (1.2.56).
     */
    Map<String, Integer> allowed() {
        Map<String, Integer> out = new HashMap<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mace-enchants.allowed");
        Map<String, Object> m = new HashMap<>();
        if (s != null) m.putAll(s.getValues(false));
        if (m.isEmpty()) { m.put("unbreaking", 3); m.put("wind_burst", 2); m.put("mending", 1); }
        for (var e : m.entrySet()) {
            int lvl = e.getValue() instanceof Number n ? n.intValue() : parse(String.valueOf(e.getValue()));
            if (lvl > 0) out.put(id(e.getKey()), lvl);
        }
        return out;
    }

    static int parse(String v) { try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { return 0; } }

    /** "minecraft:wind_burst", "Wind_Burst", "wind burst" -> "wind_burst". */
    static String id(String raw) {
        String k = raw.toLowerCase(java.util.Locale.ROOT).trim().replace(' ', '_');
        int c = k.indexOf(':');
        return c >= 0 ? k.substring(c + 1) : k;
    }

    static String id(Enchantment e) { return e.getKey().getKey().toLowerCase(java.util.Locale.ROOT); }

    /** The max level allowed for this enchantment on a mace (0 = not allowed). */
    int max(Enchantment e) { return allowed().getOrDefault(id(e), 0); }

    int windCap() { return allowed().getOrDefault("wind_burst", 0); }

    /** Fixes a mace or a Wind Burst book in place. True = it changed. */
    boolean fix(ItemStack it) {
        if (it == null || !on()) return false;
        if (it.getType() == Material.MACE) {
            if (it.getEnchantments().isEmpty()) return false;
            Map<String, Integer> ok = allowed();
            boolean changed = false;
            ItemMeta meta = it.getItemMeta();
            for (var e : new HashMap<>(meta.getEnchants()).entrySet()) {
                int max = ok.getOrDefault(id(e.getKey()), 0);
                if (max <= 0) { meta.removeEnchant(e.getKey()); changed = true; note(id(e.getKey()) + " " + e.getValue() + " removed"); }
                else if (e.getValue() > max) { meta.removeEnchant(e.getKey()); meta.addEnchant(e.getKey(), max, true); changed = true; note(id(e.getKey()) + " " + e.getValue() + " -> " + max); }
            }
            if (changed) it.setItemMeta(meta);
            return changed;
        }
        if (it.getType() == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof EnchantmentStorageMeta bm) {
            Enchantment wb = null;
            for (Enchantment e : bm.getStoredEnchants().keySet()) if (id(e).equals("wind_burst")) wb = e;
            if (wb == null) return false;
            int cap = windCap();
            if (bm.getStoredEnchantLevel(wb) <= cap) return false;
            bm.removeStoredEnchant(wb);
            if (cap > 0) bm.addStoredEnchant(wb, cap, true);
            it.setItemMeta(bm);
            return true;
        }
        return false;
    }

    private long notedAt;
    /** The console says what was taken off a mace (at most once every 10 s), so a wrong cap shows up in the log. */
    void note(String what) {
        if (System.currentTimeMillis() - notedAt < 10000) return;
        notedAt = System.currentTimeMillis();
        plugin.getLogger().info("[Maces] mace enchant " + what + " (allowed: " + allowed() + ")");
    }

    boolean bypass(Player p) { return p.hasPermission("faultline.mace.bypass"); }

    void sweep(Player p) {
        if (!on() || bypass(p)) return;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || (it.getType() != Material.MACE && it.getType() != Material.ENCHANTED_BOOK)) continue;
            if (fix(it)) p.getInventory().setItem(i, it);
        }
        ItemStack cur = p.getItemOnCursor();
        if (fix(cur)) p.setItemOnCursor(cur);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTable(EnchantItemEvent e) {
        if (!on() || e.getItem().getType() != Material.MACE || bypass(e.getEnchanter())) return;
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        int best = 1;
        for (var en : new HashMap<>(add).entrySet()) {
            best = Math.max(best, en.getValue());
            int max = max(en.getKey());
            if (max <= 0) add.remove(en.getKey());
            else if (en.getValue() > max) add.put(en.getKey(), max);
        }
        if (add.isEmpty()) { // the table offered only forbidden ones: Unbreaking instead (the lapis isn't wasted)
            Enchantment unb = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            int um = allowed().getOrDefault("unbreaking", 0);
            if (unb != null && um > 0) add.put(unb, Math.min(best, um));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack r = e.getResult();
        if (!on() || r == null || r.getType() != Material.MACE) return;
        if (e.getView().getPlayer() instanceof Player p && bypass(p)) return;
        if (!fix(r)) return;
        ItemStack left = e.getInventory().getFirstItem();
        // nothing gained once capped (a Wind Burst III book on a Wind Burst II mace, Density...) = no result
        if (left != null && left.getType() == Material.MACE && left.getEnchantments().equals(r.getEnchantments())
                && java.util.Objects.equals(left.getItemMeta().hasDisplayName() ? left.getItemMeta().displayName() : null,
                r.getItemMeta().hasDisplayName() ? r.getItemMeta().displayName() : null)) { e.setResult(null); return; }
        e.setResult(r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !on()) return;
        Material m = e.getItem().getItemStack().getType();
        if (m == Material.MACE || m == Material.ENCHANTED_BOOK) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) sweep(p); });
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (it != null && it.getType() == Material.MACE && !bypass(e.getPlayer()) && fix(it)) e.getPlayer().getInventory().setItem(e.getNewSlot(), it);
    }
}
