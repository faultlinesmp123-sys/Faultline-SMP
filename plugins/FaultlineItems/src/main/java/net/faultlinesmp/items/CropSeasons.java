package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * CROP SEASONS (Items 1.2.26, config crop-seasons:). Every crop has real-world months it's in season (the server's
 * time zone, crop-seasons.zone): IN season it grows faster (in-season-boost 35%: an extra growth stage) and a ripe
 * harvest sometimes gives one more (harvest-bonus 25%); OUT of season it grows slower (off-season-skip 25% of growth
 * steps are skipped). Nether Wart has no seasons. Bone meal is never touched. /crops lists what's in season now and next.
 */
final class CropSeasons implements Listener, CommandExecutor {

    /** A crop: its display name, the blocks that grow, and the default months (1-12). */
    record Crop(String id, String name, Set<Material> blocks, Set<Integer> months) { }

    static final List<Crop> DEFAULTS = List.of(
            crop("wheat", "Wheat", Set.of(Material.WHEAT), 6, 7, 8, 9),
            crop("carrots", "Carrots", Set.of(Material.CARROTS), 3, 4, 5, 9, 10),
            crop("potatoes", "Potatoes", Set.of(Material.POTATOES), 3, 4, 5, 10, 11),
            crop("beetroots", "Beetroot", Set.of(Material.BEETROOTS), 9, 10, 11, 12),
            crop("pumpkins", "Pumpkins", Set.of(Material.PUMPKIN_STEM, Material.PUMPKIN), 8, 9, 10),
            crop("melons", "Melons", Set.of(Material.MELON_STEM, Material.MELON), 6, 7, 8),
            crop("berries", "Sweet Berries", Set.of(Material.SWEET_BERRY_BUSH), 7, 8, 9, 12),
            crop("sugar_cane", "Sugar Cane", Set.of(Material.SUGAR_CANE), 4, 5, 6, 7),
            crop("cocoa", "Cocoa", Set.of(Material.COCOA), 1, 2, 6, 7),
            crop("cactus", "Cactus", Set.of(Material.CACTUS), 6, 7, 8),
            crop("bamboo", "Bamboo", Set.of(Material.BAMBOO), 3, 4, 5, 6),
            crop("flowers", "Torchflowers & Pitcher Plants", Set.of(Material.TORCHFLOWER_CROP, Material.PITCHER_CROP), 4, 5, 6));

    static Crop crop(String id, String name, Set<Material> blocks, Integer... months) { return new Crop(id, name, blocks, Set.of(months)); }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final Map<Material, Crop> byBlock = new EnumMap<>(Material.class);
    private final Map<String, Crop> crops = new LinkedHashMap<>();
    /** Swappable in tests. */
    java.util.function.Supplier<LocalDate> today;

    CropSeasons(FaultlineItems plugin) {
        this.plugin = plugin;
        today = () -> {
            String z = plugin.getConfig().getString("crop-seasons.zone", "America/New_York");
            try { return LocalDate.now(ZoneId.of(z)); } catch (RuntimeException e) { return LocalDate.now(); }
        };
        reload();
    }

    void reload() {
        byBlock.clear(); crops.clear();
        ConfigurationSection months = plugin.getConfig().getConfigurationSection("crop-seasons.months");
        for (Crop c : DEFAULTS) {
            Set<Integer> m = c.months();
            if (months != null && months.isList(c.id())) m = new TreeSet<>(months.getIntegerList(c.id()));
            Crop k = new Crop(c.id(), c.name(), c.blocks(), m);
            crops.put(k.id(), k);
            for (Material b : k.blocks()) byBlock.put(b, k);
        }
    }

    private boolean on() { return plugin.getConfig().getBoolean("crop-seasons.enabled", true); }

    int month() { return today.get().getMonthValue(); }

    boolean inSeason(Crop c) { return c.months().contains(month()); }

    Crop cropOf(Material m) { return byBlock.get(m); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (!on()) return;
        Block b = e.getBlock();
        Material grows = e.getNewState().getType();
        Crop c = byBlock.get(grows);
        if (c == null) c = byBlock.get(b.getType());
        if (c == null) return;
        if (inSeason(c)) {
            if (random.nextDouble() >= plugin.getConfig().getDouble("crop-seasons.in-season-boost", 0.35)) return;
            // one more stage, right after this one
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) return;
                a.setAge(a.getAge() + 1);
                b.setBlockData(a);
            });
        } else if (random.nextDouble() < plugin.getConfig().getDouble("crop-seasons.off-season-skip", 0.25)) {
            e.setCancelled(true);
        }
    }

    /** A ripe in-season harvest sometimes gives one more of what it dropped. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(BlockDropItemEvent e) {
        if (!on()) return;
        Crop c = byBlock.get(e.getBlockState().getType());
        if (c == null || !inSeason(c) || e.getItems().isEmpty()) return;
        if (e.getBlockState().getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) return;
        if (random.nextDouble() >= plugin.getConfig().getDouble("crop-seasons.harvest-bonus", 0.25)) return;
        Item first = e.getItems().get(0);
        ItemStack it = first.getItemStack();
        it.setAmount(Math.min(it.getMaxStackSize(), it.getAmount() + 1));
        first.setItemStack(it);
    }

    // ---------------------------------------------------------------- /crops

    static String monthName(int m) { return Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH); }

    List<String> lines() {
        int now = month();
        List<String> in = new ArrayList<>(), next = new ArrayList<>(), off = new ArrayList<>();
        int nextMonth = now % 12 + 1;
        for (Crop c : crops.values()) {
            if (c.months().contains(now)) in.add(c.name());
            else if (c.months().contains(nextMonth)) next.add(c.name());
            else off.add(c.name() + " (" + String.join(", ", c.months().stream().sorted().map(CropSeasons::monthName).toList()) + ")");
        }
        List<String> out = new ArrayList<>();
        out.add(ChatColor.GOLD + "Crop seasons, " + Month.of(now).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ":");
        out.add(ChatColor.GREEN + "In season (grow faster, bigger harvests): " + ChatColor.WHITE + (in.isEmpty() ? "nothing" : String.join(", ", in)));
        if (!next.isEmpty()) out.add(ChatColor.YELLOW + "Coming next month: " + ChatColor.WHITE + String.join(", ", next));
        out.add(ChatColor.GRAY + "Out of season (grow slower): " + (off.isEmpty() ? "nothing" : String.join(", ", off)));
        out.add(ChatColor.DARK_GRAY + "Nether Wart has no seasons.");
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> l = lines();
        if (sender instanceof Player p && Edition.bedrock(p)) {
            String body = String.join("\n", l.subList(1, l.size())).replace(ChatColor.COLOR_CHAR, '§');
            if (BedrockForms.menu(plugin, p, ChatColor.stripColor(l.get(0)), body, List.of("OK"), i -> { }, null)) return true;
        }
        for (String s : l) sender.sendMessage(s);
        return true;
    }
}
