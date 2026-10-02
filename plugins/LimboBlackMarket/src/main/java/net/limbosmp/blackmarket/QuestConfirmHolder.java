package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

import net.limbosmp.blackmarket.QuestRegistry.Quest;

public class QuestConfirmHolder implements InventoryHolder {

    public enum Action { ACCEPT, ABANDON }

    public static final int CONFIRM_SLOT = 11;
    public static final int INFO_SLOT = 13;
    public static final int CANCEL_SLOT = 15;

    private final Action action;
    private final String questId;
    private Inventory inventory;

    public QuestConfirmHolder(Action action, String questId) {
        this.action = action;
        this.questId = questId;
    }

    public Action getAction() {
        return action;
    }

    public String getQuestId() {
        return questId;
    }

    public Inventory build(Quest quest) {
        boolean accepting = action == Action.ACCEPT;
        inventory = Bukkit.createInventory(this, 27, accepting
                ? ChatColor.DARK_GREEN + "Accept this quest?"
                : ChatColor.DARK_RED + "Abandon your quest?");

        ItemStack filler = QuestMenuHolder.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);

        inventory.setItem(INFO_SLOT, QuestMenuHolder.item(quest.icon(),
                quest.difficulty().color + "" + ChatColor.BOLD + quest.name(),
                accepting
                        ? List.of(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + quest.rewardText(),
                                  ChatColor.DARK_GRAY + "You can only have one quest at a time.")
                        : List.of(ChatColor.RED + "All progress on this quest will be lost.")));

        inventory.setItem(CONFIRM_SLOT, QuestMenuHolder.item(Material.LIME_CONCRETE,
                ChatColor.GREEN + "" + ChatColor.BOLD + (accepting ? "ACCEPT" : "YES, ABANDON"), List.of()));
        inventory.setItem(CANCEL_SLOT, QuestMenuHolder.item(Material.RED_CONCRETE,
                ChatColor.RED + "" + ChatColor.BOLD + "Go Back", List.of()));
        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
