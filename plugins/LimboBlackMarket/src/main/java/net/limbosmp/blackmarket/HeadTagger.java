package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * When a player dies, tags any PLAYER_HEAD in their drops that represents
 * their own head with their current Black Market tier — both as a
 * PersistentDataContainer value and as visible lore (so players and staff
 * can see what it's worth at a glance before it's manually traded in).
 *
 * Runs at MONITOR priority so it sees the final drop list after other
 * plugins (e.g. your Drophead plugin) have added/modified items.
 */
public class HeadTagger implements Listener {

    private final LimboBlackMarket plugin;

    public HeadTagger(LimboBlackMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String tier = plugin.getTierManager().getTier(victim.getUniqueId());

        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getType() != Material.PLAYER_HEAD) continue;
            if (!(drop.getItemMeta() instanceof SkullMeta skullMeta)) continue;

            // Only tag heads that represent the victim themself, in case other
            // plugins/loot tables also drop unrelated player heads on death.
            if (skullMeta.getOwningPlayer() == null
                    || !skullMeta.getOwningPlayer().getUniqueId().equals(victim.getUniqueId())) {
                continue;
            }

            skullMeta.getPersistentDataContainer().set(
                    plugin.getHeadTierKey(), PersistentDataType.STRING, tier);

            List<String> lore = skullMeta.hasLore() ? new ArrayList<>(skullMeta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + "Black Market Value: " + plugin.getMarketConfig().getDisplayName(tier));
            skullMeta.setLore(lore);

            drop.setItemMeta(skullMeta);
        }
    }
}
