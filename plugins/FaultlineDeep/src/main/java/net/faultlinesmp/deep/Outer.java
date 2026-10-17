package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The outer ring's runtime (1.9.0): the four regions past the old border (the Frozen Shelf, the Drowned City, the Glass
 * Reef, the Bone Fields; their terrain is in those classes, their creatures in OuterMobs).
 *  - a title the first time each visit you swim into a region
 *  - THE DROWNED KING sits his throne (DrownedCity.THRONE): someone within `outer.king.wake-radius` 30 = he rises
 *  - THE RIMEBACK sleeps in the Frozen Shelf's lair: someone under the ice within `outer.rimeback.wake-radius` 50 of its
 *    middle = it comes up out of the dark. Both: `outer.<boss>.cooldown-minutes` 30 after a kill (or after it leaves)
 *  - DROWNED VAULT KEYS (PAPER, model faultline:drowned_vault_key, PDC faultlinedeep:vault_key) open the city's 10 vault
 *    chests (DrownedCity.VAULTS), each once: the key is used up, the chest fills with the vault's loot and is marked done
 *    for FaultlineItems' Dark Loot (faultlineitems:dark_loot_done), so it never rolls ordinary loot. A shut vault can't be
 *    broken or blown up.
 *  - ANCIENT FOSSILS (PAPER, model faultline:ancient_fossil, PDC faultlinedeep:fossil): breaking bone the Bone Fields grew
 *    (`outer.fossil.mine-chance` 0.04; bone players place is recorded in the chunk PDC and never counts) and from its
 *    creatures. A mermaid takes one as a gift (15 reputation) and gives a sea treasure back.
 */
final class Outer implements Listener {

    final FaultlineDeep pl;
    final Random random = new Random();
    final NamespacedKey keyKey, fossilKey, openKey, placedBone, doneKey;
    final Map<UUID, String> region = new HashMap<>();
    final Map<String, Long> cooldown = new HashMap<>(); // boss id -> no rising before (ms)
    UUID king, rimeback;
    BukkitTask task;

    Outer(FaultlineDeep pl) {
        this.pl = pl;
        keyKey = new NamespacedKey(pl, "vault_key");
        fossilKey = new NamespacedKey(pl, "fossil");
        openKey = new NamespacedKey(pl, "vault_open");
        placedBone = new NamespacedKey(pl, "placed_bone");
        doneKey = new NamespacedKey("faultlineitems", "dark_loot_done");
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40, 40);
    }

    void shutdown() { if (task != null) task.cancel(); }

    double cfg(String path, double def) { return pl.getConfig().getDouble("outer." + path, def); }

    // =========================================================================================== every 2 s
    void tick() {
        World w = pl.world;
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            String now = Regions.at(l.getBlockX(), l.getBlockZ());
            String was = region.get(p.getUniqueId());
            if (now != null && !now.equals(was)) {
                p.sendTitle(ChatColor.AQUA + Regions.title(now), ChatColor.GRAY + Regions.subtitle(now), 10, 60, 20);
                p.playSound(l, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE, SoundCategory.AMBIENT, 1f, 0.8f);
            }
            if (now == null) region.remove(p.getUniqueId()); else region.put(p.getUniqueId(), now);
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL && p.getGameMode() != org.bukkit.GameMode.ADVENTURE) continue;
            if ("city".equals(now)) wakeKing(p);
            if ("frozen".equals(now)) wakeRimeback(p);
        }
        if (king != null && !pl.mobs.live.containsKey(king)) { king = null; rest("drownking"); }
        if (rimeback != null && !pl.mobs.live.containsKey(rimeback)) { rimeback = null; rest("rimeback"); }
    }

    void rest(String id) { cooldown.putIfAbsent(id, System.currentTimeMillis() + (long) (cfg(key(id) + ".cooldown-minutes", 30) * 60_000)); }

    static String key(String id) { return id.equals("drownking") ? "king" : "rimeback"; }

    boolean ready(String id) {
        Long until = cooldown.get(id);
        if (until == null) return true;
        if (System.currentTimeMillis() < until) return false;
        cooldown.remove(id);
        return true;
    }

    void wakeKing(Player p) {
        if (king != null || !ready("drownking")) return;
        int[] t = DrownedCity.THRONE;
        Location throne = new Location(pl.world, t[0] + 0.5, t[1] + 1, t[2] + 0.5);
        if (!throne.isWorldLoaded() || p.getLocation().distanceSquared(throne) > sq(cfg("king.wake-radius", 30))) return;
        DeepMobs.Beast b = pl.mobs.spawn(DeepMobs.KINDS.get("drownking"), throne);
        if (b == null) return;
        king = b.body.getUniqueId();
        for (Player o : pl.world.getPlayers()) if (o.getLocation().distanceSquared(throne) < 80 * 80) {
            o.sendTitle(ChatColor.DARK_AQUA + "The Drowned King", ChatColor.GRAY + "rises from his throne", 10, 50, 20);
            o.playSound(throne, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 1f, 0.6f);
        }
        throne.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, throne, 80, 1.2, 1.5, 1.2, 0.1);
    }

    void wakeRimeback(Player p) {
        if (rimeback != null || !ready("rimeback")) return;
        Location l = p.getLocation();
        if (Math.hypot(l.getX() - FrozenShelf.CX, l.getZ() - FrozenShelf.CZ) > cfg("rimeback.wake-radius", 50)) return;
        if (l.getY() > DeepGenerator.SEA - 4) return; // under the ice, not walking on it
        // up out of the dark below and to the side
        Location at = null;
        for (int i = 0; i < 16 && at == null; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            Location c = l.clone().add(Math.cos(a) * 14, -6 - random.nextInt(6), Math.sin(a) * 14);
            if (c.getBlock().getType() == Material.WATER && c.clone().add(0, 2, 0).getBlock().getType() == Material.WATER) at = c;
        }
        if (at == null) return;
        DeepMobs.Beast b = pl.mobs.spawn(DeepMobs.KINDS.get("rimeback"), at);
        if (b == null) return;
        rimeback = b.body.getUniqueId();
        for (Player o : pl.world.getPlayers()) if (o.getLocation().distanceSquared(at) < 80 * 80) {
            o.sendTitle(ChatColor.AQUA + "The Rimeback", ChatColor.GRAY + "the ice groans", 10, 50, 20);
            o.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1f, 0.5f);
        }
    }

    /** A mini boss died (OuterMobs): it rests before it can rise again. */
    void bossDown(String id) {
        cooldown.put(id, System.currentTimeMillis() + (long) (cfg(key(id) + ".cooldown-minutes", 30) * 60_000));
        if (id.equals("drownking")) king = null; else rimeback = null;
    }

    static double sq(double d) { return d * d; }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { region.remove(e.getPlayer().getUniqueId()); }

    // =========================================================================================== items
    ItemStack vaultKey(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "Drowned Vault Key");
        m.setLore(List.of(ChatColor.GRAY + "Green with age, heavy with gold.", ChatColor.GRAY + "Opens one vault in the Drowned City.",
                ChatColor.DARK_GRAY + "Right-click a vault chest."));
        m.setItemModel(new NamespacedKey("faultline", "drowned_vault_key"));
        m.getPersistentDataContainer().set(keyKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack fossil(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + "Ancient Fossil");
        m.setLore(List.of(ChatColor.GRAY + "Something from before the sea was dark.", ChatColor.GRAY + "The Den's mermaids treasure these.",
                ChatColor.DARK_GRAY + "Give one to a mermaid."));
        m.setItemModel(new NamespacedKey("faultline", "ancient_fossil"));
        m.getPersistentDataContainer().set(fossilKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isKey(ItemStack it) { return it != null && it.getType() == Material.PAPER && it.getPersistentDataContainer().has(keyKey, PersistentDataType.BYTE); }

    boolean isFossil(ItemStack it) { return it != null && it.getType() == Material.PAPER && it.getPersistentDataContainer().has(fossilKey, PersistentDataType.BYTE); }

    void giveFossil(Player p, int n) {
        p.getInventory().addItem(fossil(n)).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        p.sendActionBar(ChatColor.YELLOW + "An Ancient Fossil!");
        p.playSound(p.getLocation(), Sound.BLOCK_SUSPICIOUS_GRAVEL_BREAK, 1f, 0.8f);
        pl.console("index discover " + p.getName() + " ancient_fossil");
        pl.console("index stat " + p.getName() + " fossils " + n);
    }

    /** A mermaid takes a fossil (DenRep.gift): what she gives back. */
    ItemStack fossilReward() {
        double r = random.nextDouble();
        if (r < 0.04) return new ItemStack(Material.HEART_OF_THE_SEA);
        if (r < 0.25) return new ItemStack(Material.NAUTILUS_SHELL, 1 + random.nextInt(2));
        if (r < 0.5) return new ItemStack(Material.DIAMOND, 1 + random.nextInt(2));
        if (r < 0.75) return new ItemStack(Material.PRISMARINE_CRYSTALS, 8 + random.nextInt(9));
        return new ItemStack(Material.EMERALD, 4 + random.nextInt(6));
    }

    // =========================================================================================== the vaults
    boolean vault(Block b) {
        return pl.world != null && b.getWorld().equals(pl.world) && b.getType() == Material.CHEST && DrownedCity.vault(b.getX(), b.getY(), b.getZ());
    }

    boolean open(Block b) { return b.getState() instanceof TileState ts && ts.getPersistentDataContainer().has(openKey, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVault(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !vault(e.getClickedBlock())) return;
        Block b = e.getClickedBlock();
        if (open(b)) return; // an opened vault is a plain chest
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isKey(hand)) {
            p.sendActionBar(ChatColor.GOLD + "Locked. " + ChatColor.GRAY + "It needs a Drowned Vault Key.");
            p.playSound(b.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.8f);
            return;
        }
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        if (!(b.getState() instanceof Chest c)) return;
        PersistentDataContainer d = c.getPersistentDataContainer();
        d.set(openKey, PersistentDataType.BYTE, (byte) 1);
        d.set(doneKey, PersistentDataType.BYTE, (byte) 1); // Dark Loot leaves it alone
        c.update();
        if (b.getState() instanceof Chest fresh) fill(fresh.getBlockInventory(), p);
        Location l = b.getLocation().add(0.5, 1, 0.5);
        b.getWorld().playSound(l, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.6f);
        b.getWorld().playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.7f);
        b.getWorld().spawnParticle(Particle.WAX_OFF, l, 30, 0.4, 0.4, 0.4, 0.2);
        p.sendTitle("", ChatColor.GOLD + "The vault swings open", 5, 40, 10);
        pl.console("index stat " + p.getName() + " drowned_vaults 1");
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline() && b.getState() instanceof Chest again) p.openInventory(again.getInventory()); });
    }

    void fill(Inventory inv, Player p) {
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Material.GOLD_BLOCK, 2 + random.nextInt(4)));
        loot.add(new ItemStack(Material.DIAMOND, 4 + random.nextInt(7)));
        loot.add(new ItemStack(Material.EMERALD_BLOCK, 1 + random.nextInt(3)));
        loot.add(new ItemStack(Material.GOLDEN_APPLE, 2 + random.nextInt(3)));
        if (random.nextDouble() < 0.5) loot.add(new ItemStack(Material.NETHERITE_SCRAP, 1 + random.nextInt(3)));
        if (random.nextDouble() < 0.12) loot.add(new ItemStack(Material.NETHERITE_INGOT));
        if (random.nextDouble() < 0.08) loot.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
        if (random.nextDouble() < 0.35) loot.add(new ItemStack(Material.TRIDENT));
        if (random.nextDouble() < 0.4) loot.add(new ItemStack(Material.HEART_OF_THE_SEA));
        loot.add(new ItemStack(Material.GOLD_INGOT, 8 + random.nextInt(17)));
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) free.add(i);
        Collections.shuffle(free, random);
        int i = 0;
        for (ItemStack it : loot) { if (i >= free.size()) break; inv.setItem(free.get(i++), it); }
        pl.console("darkloot bag 2 " + p.getName());
        if (random.nextDouble() < cfg("vault.rare-chance", 0.05)) pl.console("darkloot rare " + p.getName());
        if (random.nextDouble() < cfg("vault.book-chance", 0.25)) pl.console("fenchant give lifesteal 1 1 " + p.getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVaultBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (vault(b) && !open(b) && !e.getPlayer().hasPermission("faultlinedeep.market")) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ChatColor.GOLD + "The vault won't budge." + ChatColor.GRAY + " (It needs a Drowned Vault Key.)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlast(EntityExplodeEvent e) { e.blockList().removeIf(b -> vault(b) && !open(b)); }

    @EventHandler(ignoreCancelled = true)
    public void onBlast(BlockExplodeEvent e) { e.blockList().removeIf(b -> vault(b) && !open(b)); }

    // =========================================================================================== fossils
    boolean bones(Block b) {
        return pl.world != null && b.getWorld().equals(pl.world) && "bones".equals(Regions.at(b.getX(), b.getZ()));
    }

    static String local(Block b) { return (b.getX() & 15) + "," + b.getY() + "," + (b.getZ() & 15); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceBone(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BONE_BLOCK || !bones(b)) return;
        Chunk c = b.getChunk();
        String old = c.getPersistentDataContainer().getOrDefault(placedBone, PersistentDataType.STRING, "");
        String me = local(b);
        if (!(";" + old + ";").contains(";" + me + ";")) c.getPersistentDataContainer().set(placedBone, PersistentDataType.STRING, old.isEmpty() ? me : old + ";" + me);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMineBone(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BONE_BLOCK || !bones(b)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        Chunk c = b.getChunk();
        String old = c.getPersistentDataContainer().getOrDefault(placedBone, PersistentDataType.STRING, "");
        String me = local(b);
        if ((";" + old + ";").contains(";" + me + ";")) { // placed: forget it, no fossil
            List<String> keep = new ArrayList<>(List.of(old.split(";")));
            keep.remove(me);
            if (keep.isEmpty()) c.getPersistentDataContainer().remove(placedBone);
            else c.getPersistentDataContainer().set(placedBone, PersistentDataType.STRING, String.join(";", keep));
            return;
        }
        if (random.nextDouble() < cfg("fossil.mine-chance", 0.04)) giveFossil(p, 1);
    }
}
