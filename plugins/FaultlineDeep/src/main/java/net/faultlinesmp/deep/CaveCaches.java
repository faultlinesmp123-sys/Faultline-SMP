package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Random;

/**
 * THE CAVE CACHES (Deep 1.14.2, config caves:). DeepGenerator puts a chest on a plinth in the crust's caves in 2.5% of chunks
 * (`DeepGenerator.cacheSpot`, pure, so this finds them again by position): a POLISHED DEEPSLATE plinth + a lantern = open,
 * a CHISELED DEEPSLATE plinth + a soul lantern = LOCKED (40%). A locked cache needs a DEEP CACHE KEY (PAPER, model
 * faultline:drowned_vault_key, PDC faultlinedeep:cache_key) in your hand: the key is used up and the cache stays unlocked
 * (block PDC faultlinedeep:cache_unlocked). A locked cache can't be broken or blown open. The first open fills it through
 * FaultlineItems (`darkloot fill ... <caves.rare-chance>`): Dark Loot with a 1% shot at a RARE (normal chests: 0.1%).
 * Keys drop from Dark Ocean creatures killed by a player in the caves or the Abyss (caves.key-drop-chance 3%).
 */
final class CaveCaches implements Listener {

    final FaultlineDeep pl;
    final NamespacedKey keyKey, unlockedKey;
    final Random random = new Random();

    CaveCaches(FaultlineDeep pl) {
        this.pl = pl;
        keyKey = new NamespacedKey(pl, "cache_key");
        unlockedKey = new NamespacedKey(pl, "cache_unlocked");
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("caves." + path, def); }

    ItemStack key(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Deep Cache Key");
        m.setLore(List.of(ChatColor.GRAY + "Cold iron, crusted with salt.", ChatColor.GRAY + "Opens one locked cache in the Dark Ocean's caves.",
                ChatColor.DARK_GRAY + "Hold it and right-click a locked cache", ChatColor.DARK_GRAY + "(the one with the soul lantern)."));
        m.setItemModel(new NamespacedKey("faultline", "drowned_vault_key"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(keyKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isKey(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.hasItemMeta() && it.getPersistentDataContainer().has(keyKey, PersistentDataType.BYTE);
    }

    /** {x, y, z, locked} if this block is its chunk's cave cache, else null. */
    int[] cache(Block b) {
        if (b.getType() != Material.CHEST || !b.getWorld().getName().equals(pl.worldName())) return null;
        int[] c = pl.generator.cacheSpot(b.getWorld().getSeed(), b.getX() >> 4, b.getZ() >> 4);
        return c != null && c[0] == b.getX() && c[1] == b.getY() && c[2] == b.getZ() ? c : null;
    }

    boolean has(Block b, NamespacedKey k) { return b.getState() instanceof TileState ts && ts.getPersistentDataContainer().has(k, PersistentDataType.BYTE); }

    /** Still locked: a locked cache nobody has opened with a key, and FaultlineItems hasn't rolled (a placed one never counts). */
    boolean locked(Block b, int[] c) {
        if (c == null || c[3] != 1 || has(b, unlockedKey)) return false;
        return !has(b, new NamespacedKey("faultlineitems", "dark_loot_placed"));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        int[] c = cache(b);
        if (c == null) return;
        Player p = e.getPlayer();
        if (locked(b, c)) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!isKey(hand)) {
                e.setCancelled(true);
                p.sendActionBar(ChatColor.DARK_AQUA + "Locked. " + ChatColor.GRAY + "It needs a Deep Cache Key.");
                p.playSound(b.getLocation(), Sound.BLOCK_CHEST_LOCKED, SoundCategory.BLOCKS, 1f, 0.8f);
                return;
            }
            hand.setAmount(hand.getAmount() - 1);
            if (b.getState() instanceof TileState ts) { ts.getPersistentDataContainer().set(unlockedKey, PersistentDataType.BYTE, (byte) 1); ts.update(true, false); }
            p.playSound(b.getLocation(), Sound.BLOCK_VAULT_OPEN_SHUTTER, SoundCategory.BLOCKS, 1f, 1f);
            p.sendActionBar(ChatColor.AQUA + "The lock gives.");
            b.getWorld().spawnParticle(Particle.BUBBLE_POP, b.getLocation().add(0.5, 1, 0.5), 30, 0.4, 0.3, 0.4, 0.05);
        }
        // fill it now, before the chest opens (FaultlineItems then sees it as rolled and leaves it alone)
        if (Bukkit.getPluginManager().getPlugin("FaultlineItems") != null) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "darkloot fill " + b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + " " + cfg("rare-chance", 0.01) + " " + p.getName());
            } catch (RuntimeException ex) { pl.getLogger().warning("[Caves] couldn't fill a cache: " + ex); }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        int[] c = cache(b);
        if (c == null || !locked(b, c) || e.getPlayer().hasPermission("faultlinedeep.bypass") && e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(ChatColor.DARK_AQUA + "The cache is sealed. " + ChatColor.GRAY + "It needs a Deep Cache Key.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> locked(b, cache(b))); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> locked(b, cache(b))); }

    /** Keys from the creatures of the caves and the Abyss. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v.getKiller() == null || !v.getScoreboardTags().contains(DeepMobs.TAG) || !v.getWorld().getName().equals(pl.worldName())) return;
        DeepGenerator.Zone z = pl.zone(v.getLocation());
        if (z != DeepGenerator.Zone.CAVES && z != DeepGenerator.Zone.ABYSS) return;
        if (random.nextDouble() < cfg("key-drop-chance", 0.03)) e.getDrops().add(key(1));
    }
}
