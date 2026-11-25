package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * 2.0.0 THE UMBRELLA: an accessory (and a cosmetic: worn, it opens over your head whenever it rains or you're in the
 * Wasteland). It keeps the acid rain off: FaultlineWorld's Wasteland rain and FaultlineDeep's Acid Rain both check the
 * player PDC flag {@code faultlineitems:umbrella} this sets while it's worn. Recipe: 3 wool over 2 sticks + an iron nugget.
 */
final class Umbrella implements Listener {
    final FaultlineItems plugin;
    final NamespacedKey key;
    final Map<UUID, ItemDisplay> open = new HashMap<>();

    Umbrella(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "umbrella");
        try {
            NamespacedKey rk = new NamespacedKey(plugin, "umbrella_recipe");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, item());
                r.shape("WWW", " S ", " SN");
                List<Material> wools = new ArrayList<>();
                for (Material m : Material.values()) if (m.name().endsWith("_WOOL") && !m.isLegacy()) wools.add(m);
                r.setIngredient('W', new RecipeChoice.MaterialChoice(wools));
                r.setIngredient('S', Material.STICK);
                r.setIngredient('N', Material.IRON_NUGGET);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { plugin.getLogger().warning("Umbrella recipe: " + e.getMessage()); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 5);
    }

    ItemStack item() {
        ItemStack it = FaultlineItems.NewAccessoryItems.make(plugin, Material.STICK, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Umbrella", key,
                ChatColor.GREEN + "Keeps the acid rain off you",
                ChatColor.GREEN + "(the Wasteland and the Dark Ocean).",
                ChatColor.GRAY + "Opens over your head when it rains.");
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "umbrella"));
        it.setItemMeta(m);
        return it;
    }

    boolean wearing(Player p) { return plugin.getAccessoryManager().hasEquipped(p, key); }

    /** Should it be open: raining where they stand (world storm, their own weather), or in the Wasteland. */
    boolean wet(Player p) {
        Location l = p.getLocation();
        if (p.getPlayerWeather() == WeatherType.DOWNFALL) return true;
        try { if (l.getBlock().getBiome().getKey().toString().equals("faultline:wasteland")) return true; } catch (Throwable ignored) { }
        return p.getWorld().hasStorm() && l.getBlock().getTemperature() < 1.5 && l.getBlock().getLightFromSky() > 10;
    }

    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean w = wearing(p);
            if (w) p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            else p.getPersistentDataContainer().remove(key);
            boolean show = w && wet(p) && p.getGameMode() != GameMode.SPECTATOR && !p.isInvisible() && !p.isGliding() && !p.isSwimming();
            ItemDisplay d = open.get(p.getUniqueId());
            if (!show) { if (d != null) { d.remove(); open.remove(p.getUniqueId()); } continue; }
            Location at = p.getLocation().add(0, p.getHeight() + 0.15, 0);
            at.setPitch(0);
            if (d == null || !d.isValid() || d.getWorld() != p.getWorld()) {
                if (d != null) d.remove();
                d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
                    ItemStack s = new ItemStack(Material.PAPER);
                    ItemMeta m = s.getItemMeta(); m.setItemModel(new NamespacedKey("faultline", "umbrella_open")); s.setItemMeta(m);
                    e.setItemStack(s);
                    e.setPersistent(false);
                    e.addScoreboardTag("faultline_umbrella");
                    e.addScoreboardTag("faultline_no_bedrock_fx");
                    e.setTeleportDuration(3);
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setTransformation(new Transformation(new Vector3f(0, 0.3f, 0), new AxisAngle4f(), new Vector3f(1.3f, 1.3f, 1.3f), new AxisAngle4f()));
                });
                open.put(p.getUniqueId(), d);
            } else d.teleport(at);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ItemDisplay d = open.remove(e.getPlayer().getUniqueId());
        if (d != null) d.remove();
    }

    void removeAll() { for (ItemDisplay d : open.values()) d.remove(); open.clear(); }
}
