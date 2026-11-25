package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 2.0 KINGDOM COMPASSES (admin only: /adminmenu row 3). One per kingdom; staff hand it to that kingdom's king. It points
 * at the kingdom's abandoned home in the main world (a lodestone compass with no lodestone), and while held the action bar
 * says how far it is. Targets: {@code kingdom-compass.targets.<1-5>} = "x,y,z" (defaults = FaultlineWorld's layout).
 * Item PDC {@code faultlineitems:kingdom_compass} = the kingdom number.
 */
final class KingdomCompass {
    final FaultlineItems plugin;
    final NamespacedKey key;

    static final String[] NAMES = {"The Mountain", "The Jungle", "The Ice", "The Fibers", "The Sky"};
    static final ChatColor[] COLORS = {ChatColor.RED, ChatColor.GREEN, ChatColor.AQUA, ChatColor.DARK_PURPLE, ChatColor.YELLOW};
    static final String[] PLACE = {"the mountain castle", "Himeji castle", "the frozen cathedral", "the way down to the Fibers", "the ladder up to the sky city"};
    /** FaultlineWorld's Layout: the mountain castle, Himeji, the cathedral on its mountain, the Fibers' way down, the sky ladder's dock. */
    static final int[][] DEFAULTS = {{-800, 222, -1650}, {-1500, 190, 1360}, {1385, 202, 1450}, {449, 210, -690}, {2404, 201, -1110}};

    KingdomCompass(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "kingdom_compass");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 10);
    }

    int[] target(int k) {
        String s = plugin.getConfig().getString("kingdom-compass.targets." + k, "");
        try {
            String[] p = s.split(",");
            if (p.length == 3) return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException ignored) { }
        return DEFAULTS[k - 1];
    }

    World world() {
        String w = plugin.getConfig().getString("kingdom-compass.world", "");
        World out = w.isEmpty() ? null : Bukkit.getWorld(w);
        return out != null ? out : Bukkit.getWorlds().get(0);
    }

    ItemStack item(int k) {
        ItemStack it = new ItemStack(Material.COMPASS);
        CompassMeta m = (CompassMeta) it.getItemMeta();
        int[] t = target(k);
        m.setLodestone(new Location(world(), t[0] + 0.5, t[1], t[2] + 0.5));
        m.setLodestoneTracked(false);
        m.setDisplayName(COLORS[k - 1] + "" + ChatColor.BOLD + NAMES[k - 1] + " Compass");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Points the way to " + PLACE[k - 1] + ",");
        lore.add(ChatColor.GRAY + "the abandoned home of " + COLORS[k - 1] + NAMES[k - 1] + ChatColor.GRAY + ".");
        lore.add(ChatColor.DARK_GRAY + "Hold it to see how far it is.");
        m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, k);
        it.setItemMeta(m);
        return it;
    }

    int kingdomOf(ItemStack it) {
        if (it == null || it.getType() != Material.COMPASS) return 0;
        Integer k = it.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return k == null || k < 1 || k > 5 ? 0 : k;
    }

    void tick() {
        World w = world();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int k = kingdomOf(p.getInventory().getItemInMainHand());
            if (k == 0) k = kingdomOf(p.getInventory().getItemInOffHand());
            if (k == 0) continue;
            String name = COLORS[k - 1] + NAMES[k - 1] + ChatColor.GRAY;
            if (p.getWorld() != w) { p.sendActionBar(name + ": the needle spins. Go back to the Overworld."); continue; }
            int[] t = target(k);
            Location l = p.getLocation();
            double dx = t[0] + 0.5 - l.getX(), dz = t[2] + 0.5 - l.getZ(), d = Math.hypot(dx, dz);
            if (d < 24) { p.sendActionBar(name + ": you're here."); continue; }
            double ang = Math.toDegrees(Math.atan2(dz, dx)) - 90 - l.getYaw();   // the way to go, relative to where you look
            ang = ((ang % 360) + 540) % 360 - 180;
            String arrow = Math.abs(ang) < 22.5 ? "↑" : Math.abs(ang) > 157.5 ? "↓" : ang > 0 ? (ang < 67.5 ? "↗" : ang < 112.5 ? "→" : "↘") : (ang > -67.5 ? "↖" : ang > -112.5 ? "←" : "↙");
            p.sendActionBar(name + ": " + ChatColor.WHITE + arrow + " " + (int) d + " blocks" + ChatColor.DARK_GRAY + " (" + t[0] + ", " + t[2] + ")");
        }
    }
}
