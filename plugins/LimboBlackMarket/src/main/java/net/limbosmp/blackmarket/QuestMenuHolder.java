package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.limbosmp.blackmarket.QuestDataManager.PlayerQuestData;
import net.limbosmp.blackmarket.QuestRegistry.Quest;
import net.limbosmp.blackmarket.QuestRegistry.Step;

/**
 * Layout (54 slots):
 *   Row 0: border, slot 4 = "Your Quest" summary
 *   Row 1: the 7 regular quests
 *   Row 2: the last 3 quests, centered
 *   Row 5: slot 49 = Abandon button (only when you have an active quest)
 */
public class QuestMenuHolder implements InventoryHolder {

    public static final int INFO_SLOT = 4;
    public static final int ABANDON_SLOT = 49;
    /** Quest icons fill these in registry order. Add a slot here when adding a quest. */
    public static final int[] QUEST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};

    private static final int BAR_LENGTH = 20;

    private final Map<Integer, String> slotToQuest = new HashMap<>();
    private Inventory inventory;

    public Inventory build(QuestService service, Player player) {
        inventory = Bukkit.createInventory(this, 54, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Quests");
        UUID id = player.getUniqueId();
        PlayerQuestData data = service.getData(id);
        Quest active = service.getActive(id);

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);

        List<Quest> quests = service.getRegistry().all();
        for (int i = 0; i < quests.size() && i < QUEST_SLOTS.length; i++) {
            Quest quest = quests.get(i);
            inventory.setItem(QUEST_SLOTS[i], questIcon(service, id, data, quest, active));
            slotToQuest.put(QUEST_SLOTS[i], quest.id());
        }

        inventory.setItem(INFO_SLOT, infoIcon(data, active));

        if (active != null) {
            inventory.setItem(ABANDON_SLOT, item(Material.BARRIER, ChatColor.RED + "" + ChatColor.BOLD + "Abandon Quest",
                    List.of(ChatColor.GRAY + "Drop " + active.name() + ".",
                            ChatColor.RED + "All progress will be lost.")));
        }
        return inventory;
    }

    public String questAt(int slot) {
        return slotToQuest.get(slot);
    }

    private ItemStack questIcon(QuestService service, UUID id, PlayerQuestData data, Quest quest, Quest active) {
        boolean isActive = active != null && active.id().equals(quest.id());
        List<String> lore = new ArrayList<>();

        lore.add(ChatColor.GRAY + "Difficulty: " + quest.difficulty().color + quest.difficulty().label);
        lore.add("");
        lore.add(ChatColor.WHITE + "Steps:");

        List<Step> steps = quest.steps();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (isActive && i < data.step) {
                lore.add(ChatColor.GREEN + " \u2714 " + ChatColor.STRIKETHROUGH + step.description());
            } else if (isActive && i == data.step) {
                lore.add(ChatColor.YELLOW + " \u00bb " + step.description()
                        + ChatColor.GRAY + " (" + data.progress + "/" + step.count() + ")");
                lore.add("   " + progressBar(data.progress, step.count()));
            } else {
                lore.add(ChatColor.DARK_GRAY + " \u2022 " + ChatColor.GRAY + step.description());
            }
        }

        lore.add("");
        lore.add(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + quest.rewardText());
        lore.add(ChatColor.DARK_GRAY + service.repeatText(quest));
        lore.add("");
        lore.add(statusLine(service, id, quest, isActive));

        ItemStack icon = item(quest.icon(), quest.difficulty().color + "" + ChatColor.BOLD + quest.name(), lore);
        if (isActive) glint(icon);
        return icon;
    }

    private String statusLine(QuestService service, UUID id, Quest quest, boolean isActive) {
        if (isActive) return ChatColor.AQUA + "" + ChatColor.BOLD + "ACTIVE";
        return switch (service.check(id, quest)) {
            case OK -> ChatColor.GREEN + "" + ChatColor.BOLD + "Click to accept";
            case HAS_ACTIVE -> ChatColor.GRAY + "Finish or abandon your current quest first";
            case ON_COOLDOWN -> ChatColor.RED + "On cooldown: "
                    + QuestService.formatDuration(service.cooldownRemaining(id, quest));
            case ALREADY_COMPLETED -> ChatColor.GOLD + "Completed";
        };
    }

    private ItemStack infoIcon(PlayerQuestData data, Quest active) {
        List<String> lore = new ArrayList<>();
        if (active == null) {
            lore.add(ChatColor.GRAY + "No active quest.");
            lore.add(ChatColor.GRAY + "Pick one below to start it.");
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "You can only do one quest at a time.");
        } else {
            Step step = active.steps().get(data.step);
            lore.add(active.difficulty().color + active.name());
            lore.add(ChatColor.GRAY + "Step " + (data.step + 1) + "/" + active.steps().size() + ": "
                    + ChatColor.YELLOW + step.description());
            lore.add("   " + progressBar(data.progress, step.count())
                    + ChatColor.GRAY + " " + data.progress + "/" + step.count());
        }
        ItemStack icon = item(Material.WRITABLE_BOOK, ChatColor.GOLD + "" + ChatColor.BOLD + "Your Quest", lore);
        if (active != null) glint(icon);
        return icon;
    }

    private String progressBar(int progress, int total) {
        int filled = total <= 0 ? BAR_LENGTH : Math.min(BAR_LENGTH, (int) Math.round((double) progress / total * BAR_LENGTH));
        return ChatColor.GREEN + "|".repeat(filled) + ChatColor.DARK_GRAY + "|".repeat(BAR_LENGTH - filled);
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private static void glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
