package net.faultlinesmp.skills;

import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.List;

/** The four skill trees and their perks (each perk needs the one before it and a tree level; it costs skill points). */
enum Tree {
    MINING("Mining", ChatColor.GRAY, Material.IRON_PICKAXE, "Dig natural stone and ores.", List.of(
            new Perk("quick_hands", "Quick Hands", 5, 1, Material.GOLDEN_PICKAXE, "Haste I for a few seconds after you break stone."),
            new Perk("lucky_strike", "Lucky Strike", 10, 2, Material.RAW_GOLD, "5% chance an ore drops twice."),
            new Perk("night_eyes", "Night Eyes", 15, 2, Material.ENDER_EYE, "Night Vision below y 0 in the Overworld."),
            new Perk("smelter", "Smelter", 20, 3, Material.FURNACE, "10% chance raw iron, gold and copper drop as ingots."),
            new Perk("tough_picks", "Tough Picks", 25, 3, Material.ANVIL, "20% chance a pickaxe takes no damage."),
            new Perk("master_miner", "Master Miner", 40, 5, Material.NETHERITE_PICKAXE, "Haste II with a pickaxe below y 40, Lucky Strike 15%."))),
    BUILDING("Building", ChatColor.GOLD, Material.BRICKS, "Place blocks that stay where you put them.", List.of(
            new Perk("reach", "Builder's Reach", 5, 1, Material.STICK, "+1 block reach."),
            new Perk("thrifty", "Thrifty", 10, 2, Material.CHEST, "5% chance a placed block isn't used up."),
            new Perk("quick_builder", "Quick Builder", 15, 2, Material.SUGAR, "+10% speed while holding a block."),
            new Perk("long_reach", "Long Reach", 20, 3, Material.END_ROD, "+2 block reach (instead of +1)."),
            new Perk("featherweight", "Featherweight", 25, 3, Material.FEATHER, "No fall damage from 8 blocks or less while holding a block."),
            new Perk("master_builder", "Master Builder", 40, 5, Material.BEACON, "Thrifty 12%, +3 block reach."))),
    FARMING("Farming", ChatColor.GREEN, Material.WHEAT, "Harvest grown crops and breed animals.", List.of(
            new Perk("green_fingers", "Green Fingers", 5, 1, Material.WHEAT_SEEDS, "10% chance of an extra crop drop."),
            new Perk("replanter", "Replanter", 10, 2, Material.IRON_HOE, "Harvesting with a hoe replants the crop."),
            new Perk("whisperer", "Animal Whisperer", 15, 2, Material.LEAD, "Breeding gives double experience, 10% twins."),
            new Perk("bountiful", "Bountiful", 20, 3, Material.HAY_BLOCK, "20% chance of an extra crop drop (instead of 10%)."),
            new Perk("sweep", "Hoe Sweep", 25, 3, Material.DIAMOND_HOE, "Harvesting a crop with a hoe harvests the grown ones around it (3x3)."),
            new Perk("master_farmer", "Master Farmer", 40, 5, Material.GOLDEN_CARROT, "Farm food fills 2 more hunger."))),
    REDSTONE("Redstone", ChatColor.RED, Material.REDSTONE, "Build circuits: place components that stay, craft them.", List.of(
            new Perk("tinkerer", "Tinkerer", 5, 1, Material.CRAFTING_TABLE, "10% chance crafting a component gives one more."),
            new Perk("wired", "Wired", 10, 2, Material.REDSTONE, "10% chance placed redstone dust isn't used up."),
            new Perk("engineer_eye", "Engineer's Eye", 15, 2, Material.SPYGLASS, "Sneak + right-click a component with an empty hand: its power and state."),
            new Perk("rail_rider", "Rail Rider", 20, 3, Material.MINECART, "Minecarts you ride go 50% faster."),
            new Perk("spare_parts", "Spare Parts", 25, 3, Material.REPEATER, "25% chance a placed component isn't used up."),
            new Perk("master_engineer", "Master Engineer", 40, 5, Material.COMPARATOR, "Haste II holding redstone items, minecarts twice as fast.")));

    record Perk(String id, String name, int level, int cost, Material icon, String about) { }

    final String title;
    final ChatColor color;
    final Material icon;
    final String how;
    final List<Perk> perks;

    Tree(String title, ChatColor color, Material icon, String how, List<Perk> perks) {
        this.title = title; this.color = color; this.icon = icon; this.how = how; this.perks = perks;
    }

    String key() { return name().toLowerCase(); }

    static Tree of(String s) {
        for (Tree t : values()) if (t.key().equalsIgnoreCase(s) || t.title.equalsIgnoreCase(s)) return t;
        return null;
    }
}
