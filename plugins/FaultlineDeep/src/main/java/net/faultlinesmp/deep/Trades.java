package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * What Halcyon's citizens trade (vanilla style, by profession, all levels open, never sold out). The Dock Master sells
 * the Submersible for 128 emeralds (two stacks of 64: the only way to get one since 1.17.0).
 */
final class Trades {
    private Trades() { }

    static List<MerchantRecipe> of(Citizens.Person p, FaultlineDeep pl) {
        List<MerchantRecipe> l = new ArrayList<>();
        switch (p.job) {
            case GUARD, CHILD, IDLER -> { return l; }
            case DOCKMASTER -> {
                if (pl.subs != null) l.add(r(pl.subs.item(100, false), new ItemStack(Material.EMERALD, 64), new ItemStack(Material.EMERALD, 64)));
                l.add(r(new ItemStack(Material.IRON_INGOT, 6), em(2)));
                l.add(r(new ItemStack(Material.MAP), em(3)));
                return l;
            }
            case CURATOR -> {
                l.add(r(new ItemStack(Material.BOOK, 3), em(1)));
                l.add(r(new ItemStack(Material.EMERALD), new ItemStack(Material.PAPER, 24)));
                l.add(r(new ItemStack(Material.NAUTILUS_SHELL), em(8)));
                l.add(r(new ItemStack(Material.PRISMARINE_CRYSTALS, 4), em(3)));
                return l;
            }
            default -> { }
        }
        var prof = p.prof;
        if (prof == org.bukkit.entity.Villager.Profession.FARMER) {
            l.add(r(em(1), new ItemStack(Material.WHEAT, 20)));
            l.add(r(em(1), new ItemStack(Material.POTATO, 26)));
            l.add(r(em(1), new ItemStack(Material.CARROT, 22)));
            l.add(r(new ItemStack(Material.BREAD, 6), em(1)));
            l.add(r(new ItemStack(Material.PUMPKIN_PIE, 4), em(1)));
            l.add(r(new ItemStack(Material.GOLDEN_CARROT, 3), em(3)));
            l.add(r(new ItemStack(Material.GLISTERING_MELON_SLICE, 3), em(4)));
        } else if (prof == org.bukkit.entity.Villager.Profession.FLETCHER) {
            l.add(r(em(1), new ItemStack(Material.STICK, 32)));
            l.add(r(new ItemStack(Material.OAK_LOG, 8), em(1)));
            l.add(r(new ItemStack(Material.BIRCH_LOG, 8), em(1)));
            l.add(r(new ItemStack(Material.SPRUCE_LOG, 8), em(1)));
            l.add(r(new ItemStack(Material.ARROW, 16), em(1)));
            l.add(r(new ItemStack(Material.BOW), em(2)));
        } else if (prof == org.bukkit.entity.Villager.Profession.TOOLSMITH) {
            l.add(r(em(1), new ItemStack(Material.COAL, 15)));
            l.add(r(em(1), new ItemStack(Material.IRON_INGOT, 4)));
            l.add(r(new ItemStack(Material.COAL, 8), em(1)));
            l.add(r(new ItemStack(Material.TORCH, 16), em(1)));
            l.add(r(ench(Material.IRON_PICKAXE, Enchantment.EFFICIENCY, 2), em(6)));
            l.add(r(ench(Material.DIAMOND_PICKAXE, Enchantment.EFFICIENCY, 3), em(20)));
        } else if (prof == org.bukkit.entity.Villager.Profession.MASON) {
            l.add(r(em(1), new ItemStack(Material.CLAY_BALL, 10)));
            l.add(r(new ItemStack(Material.BRICKS, 10), em(1)));
            l.add(r(new ItemStack(Material.POLISHED_ANDESITE, 4), em(1)));
            l.add(r(new ItemStack(Material.PRISMARINE_BRICKS, 4), em(2)));
            l.add(r(new ItemStack(Material.SEA_LANTERN, 2), em(3)));
            l.add(r(new ItemStack(Material.QUARTZ_BLOCK), em(1)));
        } else if (prof == org.bukkit.entity.Villager.Profession.CLERIC) {
            l.add(r(em(1), new ItemStack(Material.ROTTEN_FLESH, 32)));
            l.add(r(new ItemStack(Material.REDSTONE, 2), em(1)));
            l.add(r(new ItemStack(Material.LAPIS_LAZULI), em(1)));
            l.add(r(new ItemStack(Material.GLOWSTONE), em(4)));
            l.add(r(new ItemStack(Material.ENDER_PEARL), em(5)));
            l.add(r(new ItemStack(Material.EXPERIENCE_BOTTLE), em(3)));
        } else if (prof == org.bukkit.entity.Villager.Profession.FISHERMAN) {
            l.add(r(em(1), new ItemStack(Material.STRING, 20)));
            l.add(r(new ItemStack(Material.COOKED_COD, 6), em(1), new ItemStack(Material.COD, 6)));
            l.add(r(new ItemStack(Material.COD_BUCKET), em(3)));
            l.add(r(new ItemStack(Material.TROPICAL_FISH_BUCKET), em(4)));
            l.add(r(em(1), new ItemStack(Material.SALMON, 13)));
            l.add(r(new ItemStack(Material.CAMPFIRE), em(2)));
        } else if (prof == org.bukkit.entity.Villager.Profession.LIBRARIAN) {
            l.add(r(em(1), new ItemStack(Material.PAPER, 24)));
            l.add(r(book(Enchantment.UNBREAKING, 3), em(14), new ItemStack(Material.BOOK)));
            l.add(r(book(Enchantment.PROTECTION, 3), em(16), new ItemStack(Material.BOOK)));
            l.add(r(book(Enchantment.EFFICIENCY, 4), em(18), new ItemStack(Material.BOOK)));
            l.add(r(new ItemStack(Material.BOOKSHELF), em(9)));
            l.add(r(new ItemStack(Material.LANTERN), em(1)));
        } else if (prof == org.bukkit.entity.Villager.Profession.CARTOGRAPHER) {
            l.add(r(em(1), new ItemStack(Material.PAPER, 24)));
            l.add(r(new ItemStack(Material.MAP), em(7)));
            l.add(r(new ItemStack(Material.ITEM_FRAME), em(7)));
            l.add(r(new ItemStack(Material.COMPASS), em(4)));
            l.add(r(new ItemStack(Material.GLOBE_BANNER_PATTERN), em(8)));
        } else if (prof == org.bukkit.entity.Villager.Profession.BUTCHER) {
            l.add(r(em(1), new ItemStack(Material.CHICKEN, 14)));
            l.add(r(new ItemStack(Material.COOKED_PORKCHOP, 5), em(1)));
            l.add(r(new ItemStack(Material.COOKED_CHICKEN, 6), em(1)));
            l.add(r(new ItemStack(Material.RABBIT_STEW), em(1)));
            l.add(r(new ItemStack(Material.COOKED_BEEF, 5), em(1)));
        } else if (prof == org.bukkit.entity.Villager.Profession.LEATHERWORKER) {
            l.add(r(em(1), new ItemStack(Material.LEATHER, 6)));
            l.add(r(new ItemStack(Material.LEATHER_BOOTS), em(3)));
            l.add(r(new ItemStack(Material.LEATHER_HORSE_ARMOR), em(6)));
            l.add(r(new ItemStack(Material.SADDLE), em(6)));
            l.add(r(new ItemStack(Material.BUNDLE), em(4)));
        } else if (prof == org.bukkit.entity.Villager.Profession.SHEPHERD) {
            l.add(r(em(1), new ItemStack(Material.WHITE_WOOL, 18)));
            l.add(r(new ItemStack(Material.LIGHT_BLUE_WOOL, 2), em(1)));
            l.add(r(new ItemStack(Material.CYAN_WOOL, 2), em(1)));
            l.add(r(new ItemStack(Material.SHEARS), em(2)));
            l.add(r(new ItemStack(Material.BLUE_BED), em(3)));
            l.add(r(new ItemStack(Material.PAINTING, 3), em(2)));
        } else if (prof == org.bukkit.entity.Villager.Profession.ARMORER) {
            l.add(r(em(1), new ItemStack(Material.IRON_INGOT, 4)));
            l.add(r(new ItemStack(Material.IRON_HELMET), em(5)));
            l.add(r(new ItemStack(Material.IRON_CHESTPLATE), em(9)));
            l.add(r(new ItemStack(Material.SHIELD), em(5)));
            l.add(r(ench(Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 2), em(30)));
        }
        return l;
    }

    static ItemStack em(int n) { return new ItemStack(Material.EMERALD, n); }

    static MerchantRecipe r(ItemStack result, ItemStack... cost) {
        MerchantRecipe m = new MerchantRecipe(result, 0, 99999, false, 0, 0f);
        for (ItemStack c : cost) m.addIngredient(c);
        return m;
    }

    static ItemStack ench(Material m, Enchantment e, int lvl) {
        ItemStack it = new ItemStack(m);
        it.addUnsafeEnchantment(e, lvl);
        return it;
    }

    static ItemStack book(Enchantment e, int lvl) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        if (m instanceof EnchantmentStorageMeta es) { es.addStoredEnchant(e, lvl, true); it.setItemMeta(es); }
        return it;
    }
}
