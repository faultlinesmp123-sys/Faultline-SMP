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

    /** Allowed enchantment -> its max level. */
    Map<Enchantment, Integer> allowed() {
        Map<Enchantment, Integer> out = new HashMap<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mace-enchants.allowed");
        Map<String, Object> m = new HashMap<>();
        if (s != null) m.putAll(s.getValues(false)); else { m.put("unbreaking", 3); m.put("wind_burst", 2); m.put("mending", 1); }
        for (var e : m.entrySet()) {
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e.getKey().toLowerCase(java.util.Locale.ROOT)));
            if (en != null && e.getValue() instanceof Number n) out.put(en, n.intValue());
        }
        return out;
    }

    int windCap() {
        Enchantment wb = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("wind_burst"));
        return wb == null ? Integer.MAX_VALUE : allowed().getOrDefault(wb, 0);
    }

    /** Fixes a mace or a Wind Burst book in place. True = it changed. */
    boolean fix(ItemStack it) {
        if (it == null || !on()) return false;
        if (it.getType() == Material.MACE) {
            if (it.getEnchantments().isEmpty()) return false;
            Map<Enchantment, Integer> ok = allowed();
            boolean changed = false;
            ItemMeta meta = it.getItemMeta();
            for (var e : new HashMap<>(meta.getEnchants()).entrySet()) {
                Integer max = ok.get(e.getKey());
                if (max == null || max <= 0) { meta.removeEnchant(e.getKey()); changed = true; }
                else if (e.getValue() > max) { meta.removeEnchant(e.getKey()); meta.addEnchant(e.getKey(), max, true); changed = true; }
            }
            if (changed) it.setItemMeta(meta);
            return changed;
        }
        if (it.getType() == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof EnchantmentStorageMeta bm) {
            Enchantment wb = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("wind_burst"));
            if (wb == null || !bm.hasStoredEnchant(wb)) return false;
            int cap = windCap();
            if (bm.getStoredEnchantLevel(wb) <= cap) return false;
            bm.removeStoredEnchant(wb);
            if (cap > 0) bm.addStoredEnchant(wb, cap, true);
            it.setItemMeta(bm);
            return true;
        }
        return false;
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
        Map<Enchantment, Integer> ok = allowed(), add = e.getEnchantsToAdd();
        int best = 1;
        for (var en : new HashMap<>(add).entrySet()) {
            best = Math.max(best, en.getValue());
            Integer max = ok.get(en.getKey());
            if (max == null || max <= 0) add.remove(en.getKey());
            else if (en.getValue() > max) add.put(en.getKey(), max);
        }
        if (add.isEmpty()) { // the table offered only forbidden ones: Unbreaking instead (the lapis isn't wasted)
            Enchantment unb = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (unb != null && ok.containsKey(unb)) add.put(unb, Math.min(best, ok.get(unb)));
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
