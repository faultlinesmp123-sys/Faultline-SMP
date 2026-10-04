package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The Kraken's items: the Bait Worm that summons him (0.5% from digging dirt, or the Skeleton Wanderer) and three
 * counter accessories from ocean mobs. Which counters a player has on is written to them every second
 * (faultlineitems:kraken_counters) for the boss to read.
 */
final class KrakenGear implements Listener {

    enum Counter {
        CORAL_LUNG("coral_lung", Material.PRISMARINE_SHARD, ChatColor.LIGHT_PURPLE, "Coral Lung", EntityType.PUFFERFISH, "drops.coral-lung-from-pufferfish", 0.03,
                ChatColor.GREEN + "The Kraken's air bubbles refill ALL your air", ChatColor.GREEN + "and give 5 seconds of Water Breathing"),
        GLOW_GLAND("glow_gland", Material.GLOWSTONE_DUST, ChatColor.AQUA, "Glow Gland", EntityType.GLOW_SQUID, "drops.glow-gland-from-glow-squid", 0.04,
                ChatColor.GREEN + "Immune to the Kraken's ink", ChatColor.GREEN + "See clearly inside ink clouds"),
        EELSKIN_WRAP("eelskin_wrap", Material.LEATHER, ChatColor.DARK_GREEN, "Eelskin Wrap", EntityType.DROWNED, "drops.eelskin-wrap-from-drowned", 0.02,
                ChatColor.GREEN + "Break free of the Kraken's grip 3x faster", ChatColor.GREEN + "His whirlpools pull you 50% less",
                ChatColor.GREEN + "His eels deal half damage to you");

        final String id, name, config;
        final Material material;
        final ChatColor color;
        final EntityType from;
        final double chance;
        final String[] lore;

        Counter(String id, Material material, ChatColor color, String name, EntityType from, String config, double chance, String... lore) {
            this.id = id; this.material = material; this.color = color; this.name = name; this.from = from;
            this.config = config; this.chance = chance; this.lore = lore;
        }
    }

    private static final Set<Material> DIRT = EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.MUD, Material.PODZOL);

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final Map<Counter, NamespacedKey> keys = new java.util.EnumMap<>(Counter.class);
    private final NamespacedKey wormKey, countersKey;

    KrakenGear(FaultlineItems plugin) {
        this.plugin = plugin;
        for (Counter c : Counter.values()) keys.put(c, new NamespacedKey(plugin, c.id));
        wormKey = new NamespacedKey(plugin, "kraken_worm");       // FaultlineBosses looks for this on a dropped item
        countersKey = new NamespacedKey(plugin, "kraken_counters"); // FaultlineBosses reads this on players
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { sync(); } catch (RuntimeException e) { plugin.getLogger().warning("[Kraken gear] " + e); }
        }, 20L, 20L);
    }

    ItemStack counter(Counter c) {
        return FaultlineItems.NewAccessoryItems.make(plugin, c.material, c.color + "" + ChatColor.BOLD + c.name, keys.get(c), c.lore);
    }

    ItemStack worm() {
        ItemStack s = new ItemStack(Material.RABBIT_HIDE);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Bait Worm");
        m.setLore(List.of(ChatColor.GRAY + "It won't stop wriggling.", ChatColor.DARK_AQUA + "Drop it into a Deep Ocean...",
                ChatColor.DARK_GRAY + "Something down there is hungry."));
        m.addEnchant(Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.setMaxStackSize(16);
        m.setItemModel(new NamespacedKey("faultline", "kraken_worm"));
        m.getPersistentDataContainer().set(wormKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    boolean isWorm(ItemStack s) {
        return s != null && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(wormKey, PersistentDataType.BYTE);
    }

    Map<String, Function<FaultlineItems, ItemStack>> catalog() {
        Map<String, Function<FaultlineItems, ItemStack>> m = new LinkedHashMap<>();
        m.put("worm", pl -> worm());
        for (Counter c : Counter.values()) m.put(c.id, pl -> counter(c));
        return m;
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("kraken-gear." + path, def); }

    /** Every second: which counters each player wears, for the Kraken fight. */
    private void sync() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> on = new ArrayList<>();
            for (Counter c : Counter.values()) if (plugin.getAccessoryManager().hasEquipped(p, keys.get(c))) on.add(c.id);
            if (plugin.getAccessoryManager().hasEquipped(p, new NamespacedKey(plugin, "depths_charm"))) on.add("depths_charm"); // halves Crushing Depths
            p.getPersistentDataContainer().set(countersKey, PersistentDataType.STRING, String.join(",", on));
        }
    }

    /**
     * Dirt a player placed (remembered in memory, the most recent 200,000 blocks). BUG FIX: placing and breaking the
     * same dirt block over and over rolled the 0.5% every time, so a stack of dirt was an endless Kraken summon farm.
     */
    private final Map<UUID, Set<Long>> placedDirt = new java.util.HashMap<>();
    private static final int PLACED_MEMORY = 200_000;

    private Set<Long> placedIn(org.bukkit.World w) {
        return placedDirt.computeIfAbsent(w.getUID(), k -> java.util.Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>() {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) { return size() > PLACED_MEMORY; }
        }));
    }

    private static long key(org.bukkit.block.Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (DIRT.contains(event.getBlockPlaced().getType())) placedIn(event.getBlockPlaced().getWorld()).add(key(event.getBlockPlaced()));
    }

    /** 0.5% of the (natural) dirt you dig has a Bait Worm in it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!DIRT.contains(event.getBlock().getType())) return;
        if (placedIn(event.getBlock().getWorld()).remove(key(event.getBlock()))) return; // a player put it there
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        if (random.nextDouble() >= cfg("worm-from-dirt", 0.005)) return;
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        at.getWorld().dropItemNaturally(at, worm());
        p.playSound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 1f, 1.6f);
        p.sendActionBar(ChatColor.DARK_AQUA + "Something wriggles in the dirt... a Bait Worm!");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        for (Counter c : Counter.values()) {
            if (event.getEntityType() != c.from || random.nextDouble() >= cfg(c.config, c.chance)) continue;
            event.getDrops().add(counter(c));
            Player k = event.getEntity().getKiller();
            k.playSound(k.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.4f);
        }
    }
}
