package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE WHIRLPOOL RADAR (Deep 1.19.0, config whirlpool.radar.*): a COMPASS with glint + PDC {@code faultlinedeep:whirlpool_radar};
 * recipe CAC/RKR/CAC (copper ingots, amethyst shards, redstone, a compass). Held in either hand (every 10 ticks): the
 * nearest open whirlpool within `range` 2000 in your world as an action bar arrow + distance + how many signals, and a ping
 * that speeds up as you get closer. It can't tell a regular whirlpool from the Depths until you're within
 * `identify-distance` 120 (then: "THE DEPTHS" or "a whirlpool").
 */
final class Radar {
    final FaultlineDeep pl;
    final NamespacedKey key;
    private final Map<UUID, Integer> pingIn = new HashMap<>();

    Radar(FaultlineDeep pl) {
        this.pl = pl;
        key = new NamespacedKey(pl, "whirlpool_radar");
        try {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "whirlpool_radar"), item());
            r.shape("CAC", "RKR", "CAC");
            r.setIngredient('C', Material.COPPER_INGOT); r.setIngredient('A', Material.AMETHYST_SHARD);
            r.setIngredient('R', Material.REDSTONE); r.setIngredient('K', Material.COMPASS);
            Bukkit.addRecipe(r);
        } catch (RuntimeException ignored) { } // (already there after a reload)
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 10L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("whirlpool.radar." + k, def); }

    ItemStack item() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Whirlpool Radar");
        m.setLore(List.of(ChatColor.GRAY + "Hold it to find open whirlpools.",
                ChatColor.GRAY + "Most only tear you up. One, " + ChatColor.LIGHT_PURPLE + "the Depths" + ChatColor.GRAY + ",",
                ChatColor.GRAY + "leads down to the Dark Ocean.",
                ChatColor.DARK_GRAY + "It tells them apart within " + (int) cfg("identify-distance", 120) + " blocks."));
        try { m.setEnchantmentGlintOverride(true); } catch (RuntimeException | LinkageError ignored) { }
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.getType() == Material.COMPASS && it.hasItemMeta() && it.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void tick() {
        if (pl.journey == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!is(p.getInventory().getItemInMainHand()) && !is(p.getInventory().getItemInOffHand())) { pingIn.remove(p.getUniqueId()); continue; }
            Journey.Pool best = null;
            double bestD = cfg("range", 2000);
            int signals = 0;
            for (Journey.Pool o : pl.journey.open()) {
                Location c = o.v.center;
                if (!c.getWorld().equals(p.getWorld())) continue;
                double d = Math.hypot(c.getX() - p.getLocation().getX(), c.getZ() - p.getLocation().getZ());
                if (d > cfg("range", 2000)) continue;
                signals++;
                if (d < bestD) { bestD = d; best = o; }
            }
            if (best == null) {
                p.sendActionBar(Component.text("Whirlpool Radar  ·  no signal", NamedTextColor.GRAY));
                continue;
            }
            Location c = best.v.center;
            boolean known = bestD <= cfg("identify-distance", 120);
            Component what = !known ? Component.text("  unknown", NamedTextColor.DARK_GRAY)
                    : best.depths ? Component.text("  THE DEPTHS", NamedTextColor.LIGHT_PURPLE) : Component.text("  a whirlpool", NamedTextColor.WHITE);
            p.sendActionBar(Component.text("Whirlpool Radar  " + Journey.arrow(p, c) + "  " + (int) bestD + " blocks", NamedTextColor.AQUA)
                    .append(what)
                    .append(Component.text(signals > 1 ? "   (" + signals + " signals)" : "", NamedTextColor.GRAY)));
            // ping: every 4 s far off, every half second close up
            int every = (int) Math.max(1, Math.min(8, bestD / 60));
            int left = pingIn.getOrDefault(p.getUniqueId(), 0) - 1;
            if (left <= 0) {
                float pitch = (float) Math.max(0.6, Math.min(2.0, 2.0 - bestD / 500));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.6f, pitch);
                if (known && best.depths) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 0.7f);
                left = every;
            }
            pingIn.put(p.getUniqueId(), left);
        }
    }
}
