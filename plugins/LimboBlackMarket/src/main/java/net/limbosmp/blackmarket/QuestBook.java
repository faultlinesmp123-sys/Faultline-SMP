package net.limbosmp.blackmarket;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The Quest Book replaces /quest — craft one (Book + Iron Ingot, shapeless,
 * works in the 2x2 inventory grid) and right-click it to open the quest menu.
 */
public class QuestBook implements Listener {

    private final LimboBlackMarket plugin;
    private final QuestMenuListener menu;

    public QuestBook(LimboBlackMarket plugin, QuestMenuListener menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    public static ItemStack create(LimboBlackMarket plugin) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Quest Book");
        meta.setLore(List.of(
                ChatColor.GRAY + "Right-click to view quests.",
                ChatColor.DARK_GRAY + "One quest at a time."
        ));
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setItemModel(new org.bukkit.NamespacedKey("faultline", "quest_book")); // texture from FaultlineItemTextures
        meta.getPersistentDataContainer().set(plugin.getQuestBookKey(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isQuestBook(LimboBlackMarket plugin, ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) return false;
        Byte tag = item.getItemMeta().getPersistentDataContainer().get(plugin.getQuestBookKey(), PersistentDataType.BYTE);
        return tag != null && tag == (byte) 1;
    }

    public static ShapelessRecipe recipe(LimboBlackMarket plugin) {
        ShapelessRecipe recipe = new ShapelessRecipe(plugin.getQuestBookKey(), create(plugin));
        recipe.addIngredient(Material.BOOK);
        recipe.addIngredient(Material.IRON_INGOT);
        return recipe;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isQuestBook(plugin, event.getItem())) return;

        // Stops it being placed on a lectern / used on a block.
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        menu.openMenu(event.getPlayer());
    }

    /**
     * The Quest Book is technically a regular Book, so without this it could
     * be eaten by vanilla recipes (Bookshelf, Lectern, Book and Quill), or
     * fed back into its own recipe. Block any craft that uses one.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isQuestBook(plugin, ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Custom recipes don't show up in the recipe book unless unlocked.
        event.getPlayer().discoverRecipe(plugin.getQuestBookKey());
    }
}
