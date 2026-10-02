package net.limbosmp.blackmarket;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public class QuestProgressListener implements Listener {

    private final LimboBlackMarket plugin;
    private final QuestService service;
    private final Set<Material> trackedBlocks;

    public QuestProgressListener(LimboBlackMarket plugin, QuestService service) {
        this.plugin = plugin;
        this.service = service;
        this.trackedBlocks = service.getRegistry().trackedMineBlocks();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return; // player kills handled below
        // Necromancer Staff minions (FaultlineItems) never count — otherwise you
        // could summon zombies and kill them yourself to finish zombie quests.
        if (event.getEntity().getScoreboardTags().contains("faultline_summon")) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        service.onMobKill(killer, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        service.onPlayerKill(killer, victim);

        // This listener owns recording kills for the anti-farm cooldown, and
        // does it for EVERY player kill (any tier, quest active or not) —
        // otherwise farming a low-tier player would never get flagged.
        // Marked AFTER the quest check above, so it only blocks repeat kills.
        plugin.getAntiFarmManager().markCredited(killer.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidFinish(RaidFinishEvent event) {
        for (Player winner : event.getWinners()) {
            service.onRaidWin(winner);
        }
    }

    // ---------- mining, with placed-block protection ----------
    //
    // BUG FIX: Ancient Debris always drops itself, so before this a player
    // could place one block and mine it over and over to finish any "mine
    // Ancient Debris" step. Player-placed blocks of any quest-tracked type
    // are now marked (stored on the chunk itself, so it survives restarts)
    // and don't count when broken.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!trackedBlocks.contains(block.getType())) return;
        block.getChunk().getPersistentDataContainer().set(placedKey(block), PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (!trackedBlocks.contains(type)) return;

        Chunk chunk = block.getChunk();
        NamespacedKey key = placedKey(block);
        if (chunk.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            chunk.getPersistentDataContainer().remove(key);
            return; // player-placed — doesn't count
        }

        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        service.onMine(player, type);
    }

    private NamespacedKey placedKey(Block block) {
        // Position within the chunk (0-15 on x/z). Y can be negative, which is
        // fine — '-' is allowed in NamespacedKey keys.
        return new NamespacedKey(plugin, "placed_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }
}
