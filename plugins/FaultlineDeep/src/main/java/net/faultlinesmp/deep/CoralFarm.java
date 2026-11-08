package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * CORAL FARMING (Deep 1.15.0, config habitat.coral.farm:). Air Coral you plant GROWS: every minute each loaded one rolls
 * 1 / grow-minutes (8) for a BLOOM, up to max-blooms (3); a dome with HYDROPONICS within 16 blocks makes it 50% faster.
 * Blooming coral sparkles. Right-click it with an empty hand to harvest: one AIR CORAL FRAGMENT per bloom (PAPER, model
 * faultline:air_coral_fragment, PDC faultlinedeep:coral_fragment). Fragments:
 *  - sell at the Glimmer Shop all year: /glimmer sell (or the Sell button in the shop) = 1 Glimmer Pearl per
 *    `sell-per-pearl` (4) fragments;
 *  - grow more coral: 4 fragments round a Tube Coral Block = an Air Coral.
 * Blooms are kept in the chunk's PDC (faultlinedeep:coral_blooms "x,y,z=n;..."). Index stat coral_harvested.
 */
final class CoralFarm implements Listener {

    private final FaultlineDeep pl;
    private final Random random = new Random();
    final NamespacedKey fragKey, bloomKey;
    final Map<Long, Integer> blooms = new HashMap<>();
    private int secs;

    CoralFarm(FaultlineDeep pl) {
        this.pl = pl;
        fragKey = new NamespacedKey(pl, "coral_fragment");
        bloomKey = new NamespacedKey(pl, "coral_blooms");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 20L);
        try {
            NamespacedKey rk = new NamespacedKey(pl, "air_coral_from_fragments");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, pl.habitat.coral(1));
                r.shape(" F ", "FTF", " F ");
                r.setIngredient('F', new org.bukkit.inventory.RecipeChoice.ExactChoice(fragment(1)));
                r.setIngredient('T', Material.TUBE_CORAL_BLOCK);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { pl.getLogger().warning("Coral fragment recipe: " + e.getMessage()); }
        if (pl.world != null) for (Chunk c : pl.world.getLoadedChunks()) load(c);
    }

    double cfg(String k, double d) { return pl.getConfig().getDouble("habitat.coral.farm." + k, d); }

    ItemStack fragment(int n) {
        ItemStack it = new ItemStack(Material.PAPER, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Air Coral Fragment");
        m.setLore(List.of(ChatColor.GRAY + "A bloom off a living Air Coral.", ChatColor.GRAY + "Sell them: " + ChatColor.WHITE + "/glimmer sell",
                ChatColor.GRAY + "Or 4 round a Tube Coral Block = an Air Coral."));
        m.setItemModel(new NamespacedKey("faultline", "air_coral_fragment"));
        m.getPersistentDataContainer().set(fragKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isFragment(ItemStack s) { return s != null && s.getType() == Material.PAPER && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(fragKey, PersistentDataType.BYTE); }

    int count(Player p) { int n = 0; for (ItemStack s : p.getInventory().getStorageContents()) if (isFragment(s)) n += s.getAmount(); return n; }

    // ---------------------------------------------------------------- growing

    private void tick() {
        if (!pl.getConfig().getBoolean("habitat.coral.farm.enabled", true)) return;
        secs++;
        World w = pl.world;
        if (w == null || pl.habitat == null) return;
        int max = (int) cfg("max-blooms", 3);
        if (secs % 60 == 0) blooms.keySet().removeIf(k -> !pl.habitat.corals.contains(k) && w.isChunkLoaded(Volcano.unpack(k)[0] >> 4, Volcano.unpack(k)[2] >> 4));
        for (long k : new ArrayList<>(pl.habitat.corals)) {
            int[] c = Volcano.unpack(k);
            if (!w.isChunkLoaded(c[0] >> 4, c[2] >> 4)) continue;
            int n = blooms.getOrDefault(k, 0);
            Location l = new Location(w, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
            if (secs % 60 == 0 && n < max) {
                double chance = 1.0 / Math.max(1, cfg("grow-minutes", 8));
                if (pl.habitat.upgrades.near(l, "hydroponics", 16) != null) chance *= 1.5;
                if (random.nextDouble() < chance) { set(k, w.getChunkAt(c[0] >> 4, c[2] >> 4), n + 1); n++; }
            }
            if (n > 0 && secs % 2 == 0) {
                boolean seen = false;
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(l) < 32 * 32) { seen = true; break; }
                if (seen) {
                    w.spawnParticle(Particle.GLOW, l.clone().add(0, 0.6, 0), 2 * n, 0.4, 0.3, 0.4, 0);
                    w.spawnParticle(Particle.END_ROD, l.clone().add(0, 0.7, 0), n, 0.3, 0.2, 0.3, 0.005);
                }
            }
        }
    }

    void set(Block b, int n) { set(Habitat.pack(b.getX(), b.getY(), b.getZ()), b.getChunk(), n); }

    void set(long k, Chunk c, int n) {
        if (n <= 0) blooms.remove(k); else blooms.put(k, n);
        StringBuilder s = new StringBuilder();
        int cx = c.getX(), cz = c.getZ();
        for (var e : blooms.entrySet()) {
            int[] p = Volcano.unpack(e.getKey());
            if (p[0] >> 4 != cx || p[2] >> 4 != cz) continue;
            if (s.length() > 0) s.append(';');
            s.append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append('=').append(e.getValue());
        }
        if (s.length() == 0) c.getPersistentDataContainer().remove(bloomKey);
        else c.getPersistentDataContainer().set(bloomKey, PersistentDataType.STRING, s.toString());
    }

    void load(Chunk c) {
        String s = c.getPersistentDataContainer().get(bloomKey, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            String[] xyz = kv[0].split(",");
            if (xyz.length != 3) continue;
            try { blooms.put(Habitat.pack(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), Integer.parseInt(kv[1])); }
            catch (NumberFormatException ignored) { }
        }
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent e) { if (pl.world != null && e.getWorld().equals(pl.world)) load(e.getChunk()); }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        if (pl.world == null || !e.getWorld().equals(pl.world)) return;
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        blooms.keySet().removeIf(k -> { int[] p = Volcano.unpack(k); return p[0] >> 4 == cx && p[2] >> 4 == cz; });
    }

    // ---------------------------------------------------------------- harvesting

    @EventHandler(priority = EventPriority.HIGH)
    public void onHarvest(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.BUBBLE_CORAL_BLOCK || pl.habitat == null) return;
        long k = Habitat.pack(b.getX(), b.getY(), b.getZ());
        if (!pl.habitat.corals.contains(k)) return;
        Player p = e.getPlayer();
        if (!p.getInventory().getItemInMainHand().getType().isAir() || p.isSneaking()) return;
        e.setCancelled(true);
        int n = blooms.getOrDefault(k, 0);
        if (n <= 0) { p.sendActionBar(ChatColor.GRAY + "Nothing to harvest yet: it blooms every few minutes."); return; }
        set(b, 0);
        Location l = b.getLocation().clone().add(0.5, 1, 0.5);
        p.getInventory().addItem(fragment(n)).values().forEach(x -> p.getWorld().dropItemNaturally(l, x));
        l.getWorld().playSound(l, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 1.4f);
        l.getWorld().spawnParticle(Particle.GLOW, l, 15, 0.4, 0.3, 0.4, 0.05);
        p.sendActionBar(ChatColor.AQUA + "+" + n + " Air Coral Fragment" + (n == 1 ? "" : "s"));
        pl.console("index stat " + p.getName() + " coral_harvested " + n);
    }

    // ---------------------------------------------------------------- selling

    /** /glimmer sell: every fragment you carry, in fours, for Glimmer Pearls. */
    void sell(Player p) {
        int per = Math.max(1, (int) cfg("sell-per-pearl", 4));
        int have = count(p);
        int pearls = have / per;
        if (pearls <= 0) { p.sendMessage(ChatColor.GRAY + "The coral buyer takes Air Coral Fragments " + per + " at a time (you have " + have + "). Harvest blooming Air Coral for them."); return; }
        int take = pearls * per;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && take > 0; i++) {
            if (!isFragment(inv[i])) continue;
            int use = Math.min(take, inv[i].getAmount());
            inv[i].setAmount(inv[i].getAmount() - use);
            take -= use;
            if (inv[i].getAmount() <= 0) inv[i] = null;
        }
        p.getInventory().setStorageContents(inv);
        if (pl.glimmer != null) pl.glimmer.give(p, pearls);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
        p.sendMessage(ChatColor.AQUA + "Sold " + pearls * per + " Air Coral Fragments for " + ChatColor.LIGHT_PURPLE + pearls + " Glimmer Pearl" + (pearls == 1 ? "" : "s") + ChatColor.AQUA + ".");
    }
}
