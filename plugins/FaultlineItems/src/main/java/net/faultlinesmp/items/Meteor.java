package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * METEORS WERE REMOVED in Items 1.2.68 (no more strikes, guards, loot or /meteor). What's left:
 *  - on startup, any leftover meteor guard (tag faultline_meteor) is removed, and a crater a crash left behind
 *    (meteor.yml `scar:`, written by the old strikes) is put back the way the ground was;
 *  - `legacy()`, the &sect;-text to Component helper the rest of the plugin uses.
 */
final class Meteor {

    static final String TAG = "faultline_meteor";

    /** What a meteor left: only these are put back (anything else = someone built there since). */
    private static final Set<Material> OURS = Set.of(Material.AIR, Material.CAVE_AIR, Material.MAGMA_BLOCK, Material.BLACKSTONE,
            Material.BASALT, Material.COBBLED_DEEPSLATE, Material.COARSE_DIRT, Material.CRYING_OBSIDIAN, Material.ANCIENT_DEBRIS,
            Material.GILDED_BLACKSTONE, Material.OBSIDIAN, Material.FIRE, Material.WATER, Material.LAVA);

    private Meteor() { }

    static void cleanUp(FaultlineItems plugin) {
        try {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        } catch (RuntimeException ignored) { }
        Bukkit.getScheduler().runTask(plugin, () -> {
            java.io.File f = new java.io.File(plugin.getDataFolder(), "meteor.yml");
            if (!f.exists()) return;
            try {
                org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                World w = Bukkit.getWorld(y.getString("scar.world", ""));
                List<String> l = y.getStringList("scar.blocks");
                if (w != null && !l.isEmpty()) {
                    Map<String, String> before = new LinkedHashMap<>();
                    for (String line : l) { int i = line.indexOf('|'); if (i > 0) before.put(line.substring(0, i), line.substring(i + 1)); }
                    List<Map.Entry<String, String>> list = new ArrayList<>(before.entrySet());
                    java.util.Collections.reverse(list);
                    int n = 0;
                    for (Map.Entry<String, String> en : list) {
                        try {
                            String[] c = en.getKey().split(",");
                            Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                            if (!OURS.contains(b.getType())) continue;
                            b.setBlockData(Bukkit.createBlockData(en.getValue()), false);
                            n++;
                        } catch (RuntimeException ignored) { }
                    }
                    plugin.getLogger().info("[Meteor] meteors are removed: healed " + n + " blocks of the last crater");
                }
                if (!f.delete()) f.deleteOnExit();
            } catch (RuntimeException e) { plugin.getLogger().warning("[Meteor] " + e); }
        });
    }

    static net.kyori.adventure.text.Component legacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
