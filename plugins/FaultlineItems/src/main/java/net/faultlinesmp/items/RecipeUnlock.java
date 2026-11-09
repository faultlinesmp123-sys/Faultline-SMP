package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 1.3.5: BEDROCK PLAYERS COULDN'T CRAFT OUR ITEMS. Since 1.21.2 the server only tells a client (and so Geyser) about the
 * recipes in that player's recipe book, and plugin recipes are never unlocked on their own. A Java game still gets the
 * result from the server, but a Bedrock game only shows a crafting result for a recipe it knows: the accessories, the
 * backpack, diving gear, dive helmets, balloons, Blue Obsidian... showed nothing. Now every recipe of ours (any namespace
 * starting with "faultline" or "limbo", so the other plugins' too) is unlocked for every player on join (2 s and 10 s in,
 * after the other plugins have added theirs) and for everyone online at startup. `recipes.unlock-all: false` turns it off.
 */
final class RecipeUnlock implements Listener {
    private final FaultlineItems plugin;

    RecipeUnlock(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) unlock(p); }, 100L);
    }

    static List<NamespacedKey> ours() {
        List<NamespacedKey> out = new ArrayList<>();
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r;
            try { r = it.next(); } catch (RuntimeException e) { continue; }
            if (!(r instanceof Keyed k)) continue;
            String ns = k.getKey().getNamespace();
            if (ns.startsWith("faultline") || ns.startsWith("limbo")) out.add(k.getKey());
        }
        return out;
    }

    int unlock(Player p) {
        if (!plugin.getConfig().getBoolean("recipes.unlock-all", true) || !p.isOnline()) return 0;
        try { return p.discoverRecipes(ours()); } catch (RuntimeException e) { return 0; }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> unlock(p), 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> unlock(p), 200L);
    }
}
