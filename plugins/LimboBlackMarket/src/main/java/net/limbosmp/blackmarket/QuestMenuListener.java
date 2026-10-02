package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import net.limbosmp.blackmarket.QuestRegistry.Quest;

public class QuestMenuListener implements Listener {

    private final LimboBlackMarket plugin;
    private final QuestService service;

    public QuestMenuListener(LimboBlackMarket plugin, QuestService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Opens the main menu. Used by the Quest Book and after confirm screens. */
    public void openMenu(Player player) {
        player.openInventory(new QuestMenuHolder().build(service, player));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof QuestMenuHolder) && !(holder instanceof QuestConfirmHolder)) return;

        // Nothing in these menus can be moved, including shift-clicks from the player's own inventory.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;

        if (holder instanceof QuestMenuHolder menu) {
            handleMenuClick(player, menu, event.getSlot());
        } else {
            handleConfirmClick(player, (QuestConfirmHolder) holder, event.getSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof QuestMenuHolder || holder instanceof QuestConfirmHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMenuClick(Player player, QuestMenuHolder menu, int slot) {
        if (slot == QuestMenuHolder.ABANDON_SLOT) {
            Quest active = service.getActive(player.getUniqueId());
            if (active != null) open(player, new QuestConfirmHolder(QuestConfirmHolder.Action.ABANDON, active.id()), active);
            return;
        }

        Quest quest = service.getRegistry().get(menu.questAt(slot));
        if (quest == null) return;

        switch (service.check(player.getUniqueId(), quest)) {
            case OK -> open(player, new QuestConfirmHolder(QuestConfirmHolder.Action.ACCEPT, quest.id()), quest);
            case HAS_ACTIVE -> {
                Quest active = service.getActive(player.getUniqueId());
                if (active != null && active.id().equals(quest.id())) {
                    deny(player, "This is already your active quest.");
                } else {
                    deny(player, "You can only do one quest at a time. Finish or abandon your current one first.");
                }
            }
            case ON_COOLDOWN -> deny(player, "You can do " + quest.name() + " again in "
                    + QuestService.formatDuration(service.cooldownRemaining(player.getUniqueId(), quest)) + ".");
            case ALREADY_COMPLETED -> deny(player, "You've already completed " + quest.name() + ". It's one-time only.");
        }
    }

    private void handleConfirmClick(Player player, QuestConfirmHolder confirm, int slot) {
        if (slot == QuestConfirmHolder.CANCEL_SLOT) {
            later(player, () -> openMenu(player));
            return;
        }
        if (slot != QuestConfirmHolder.CONFIRM_SLOT) return;

        Quest quest = service.getRegistry().get(confirm.getQuestId());
        if (quest == null) return;

        if (confirm.getAction() == QuestConfirmHolder.Action.ACCEPT) {
            // Re-checked inside accept() — the state could have changed while this screen was open.
            if (!service.accept(player, quest)) deny(player, "You can't accept that quest right now.");
        } else {
            service.abandon(player);
        }
        later(player, () -> openMenu(player));
    }

    private void open(Player player, QuestConfirmHolder confirm, Quest quest) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        later(player, () -> player.openInventory(confirm.build(quest)));
    }

    private void deny(Player player, String message) {
        player.sendMessage(ChatColor.RED + message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    /** Switching inventories from inside a click event is safest one tick later. */
    private void later(Player player, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) task.run();
        });
    }
}
