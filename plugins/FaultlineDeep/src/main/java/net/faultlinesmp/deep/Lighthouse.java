package net.faultlinesmp.deep;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * LIGHTHOUSES (Deep 1.15.0, config lighthouse:). The LIGHTHOUSE LAMP (PAPER, model faultline:lighthouse_lamp, PDC
 * faultlinedeep:lighthouse_lamp; craft: glass across the top, sea lanterns either side of a Heart of the Sea, prismarine
 * bricks along the bottom) is placed on top of a TOWER you built: right-click the top of a block = a SEA LANTERN that's a
 * lighthouse. The taller the solid column under it (counted straight down, up to 60), the farther it reaches: radius =
 * base-radius 10 + per-block 1.5 x height, at most max-radius 48. Inside that radius the Dark Ocean's pitch-black zones
 * are LIT for good (Ocean.darkZone: no Abyss darkness, Night Vision works), and a beam of light sweeps round the lamp.
 * Re-measured every 10 s, so building the tower higher (or breaking it) changes the reach. Breaking the lamp gives the
 * item back; explosions and pistons leave it alone. max-per-player (3). Saved in lighthouses.yml. Works in any world
 * (the dark only exists in the Dark Ocean; elsewhere it's a beacon to look at).
 */
final class Lighthouse implements Listener {

    private final FaultlineDeep pl;
    final NamespacedKey key;
    private final File file;

    static final class Lamp {
        String world; int x, y, z; UUID owner; double radius;
        boolean is(Block b) { return b.getWorld().getName().equals(world) && b.getX() == x && b.getY() == y && b.getZ() == z; }
    }

    final List<Lamp> lamps = new ArrayList<>();
    private int tick;

    Lighthouse(FaultlineDeep pl) {
        this.pl = pl;
        key = new NamespacedKey(pl, "lighthouse_lamp");
        file = new File(pl.getDataFolder(), "lighthouses.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 2L);
        try {
            NamespacedKey rk = new NamespacedKey(pl, "lighthouse_lamp");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, lamp(1));
                r.shape("GGG", "SHS", "PPP");
                r.setIngredient('G', Material.GLASS);
                r.setIngredient('S', Material.SEA_LANTERN);
                r.setIngredient('H', Material.HEART_OF_THE_SEA);
                r.setIngredient('P', Material.PRISMARINE_BRICKS);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { pl.getLogger().warning("Lighthouse recipe: " + e.getMessage()); }
    }

    double cfg(String k, double d) { return pl.getConfig().getDouble("lighthouse." + k, d); }

    ItemStack lamp(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Lighthouse Lamp");
        m.setLore(List.of(ChatColor.GRAY + "Place it on top of a tower you built.", ChatColor.GRAY + "The taller the tower, the farther it shines:",
                ChatColor.GRAY + "the Abyss's dark lifts round it for good.", ChatColor.DARK_GRAY + "Up to " + (int) cfg("max-radius", 48) + " blocks."));
        m.setItemModel(new NamespacedKey("faultline", "lighthouse_lamp"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isLamp(ItemStack s) { return s != null && s.getType() == Material.PAPER && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    void load() {
        lamps.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("lamps")) {
            String[] p = s.split(",");
            if (p.length < 5) continue;
            try {
                Lamp l = new Lamp();
                l.world = p[0]; l.x = Integer.parseInt(p[1]); l.y = Integer.parseInt(p[2]); l.z = Integer.parseInt(p[3]); l.owner = UUID.fromString(p[4]);
                l.radius = p.length > 5 ? Double.parseDouble(p[5]) : cfg("base-radius", 10);
                lamps.add(l);
            } catch (RuntimeException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (Lamp l : lamps) out.add(l.world + "," + l.x + "," + l.y + "," + l.z + "," + l.owner + "," + l.radius);
        y.set("lamps", out);
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save lighthouses.yml: " + e.getMessage()); }
    }

    /** Is this spot within a lighthouse's light? (Ocean.darkZone) */
    boolean lit(Location at) {
        if (lamps.isEmpty() || at.getWorld() == null) return false;
        String w = at.getWorld().getName();
        for (Lamp l : lamps) {
            if (!l.world.equals(w)) continue;
            double dx = at.getX() - l.x - 0.5, dy = at.getY() - l.y - 0.5, dz = at.getZ() - l.z - 0.5;
            if (dx * dx + dy * dy + dz * dz < l.radius * l.radius) return true;
        }
        return false;
    }

    /** The tower under the lamp: solid blocks straight down. */
    int height(World w, Lamp l) {
        int h = 0;
        for (int y = l.y - 1; y > w.getMinHeight() && h < 60; y--) {
            Block b = w.getBlockAt(l.x, y, l.z);
            if (!b.getType().isSolid()) break;
            h++;
        }
        return h;
    }

    double radiusFor(int h) { return Math.min(cfg("max-radius", 48), cfg("base-radius", 10) + cfg("per-block", 1.5) * h); }

    private void tick() {
        tick++;
        boolean changed = false;
        for (Iterator<Lamp> it = lamps.iterator(); it.hasNext(); ) {
            Lamp l = it.next();
            World w = Bukkit.getWorld(l.world);
            if (w == null || !w.isChunkLoaded(l.x >> 4, l.z >> 4)) continue;
            Block b = w.getBlockAt(l.x, l.y, l.z);
            if (b.getType() != Material.SEA_LANTERN) { it.remove(); changed = true; continue; } // gone (WorldEdit, a creative player...)
            if (tick % 100 == 0) { double r = radiusFor(height(w, l)); if (r != l.radius) { l.radius = r; changed = true; } }
            beam(w, l);
        }
        if (changed) save();
    }

    /** A beam sweeping round the lamp (only drawn with someone within 128). */
    private void beam(World w, Lamp l) {
        Location c = new Location(w, l.x + 0.5, l.y + 0.5, l.z + 0.5);
        boolean seen = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 128 * 128) { seen = true; break; }
        if (!seen) return;
        double a = tick * 0.09;
        for (int side = 0; side < 2; side++) {
            double ang = a + side * Math.PI;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            for (double d = 1; d < l.radius; d += 1.6) {
                Location p = c.clone().add(dx * d, 0.2 - d * 0.02, dz * d);
                w.spawnParticle(Particle.END_ROD, p, 1, 0.05, 0.05, 0.05, 0);
            }
        }
        if (tick % 10 == 0) w.spawnParticle(Particle.GLOW, c.clone().add(0, 0.8, 0), 6, 0.3, 0.3, 0.3, 0);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !isLamp(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!pl.getConfig().getBoolean("lighthouse.enabled", true)) { p.sendMessage(ChatColor.RED + "Lighthouses are turned off."); return; }
        if (e.getBlockFace() != BlockFace.UP) { p.sendActionBar(ChatColor.RED + "Put it on TOP of your tower."); return; }
        Block at = e.getClickedBlock().getRelative(BlockFace.UP);
        if (!(at.getType().isAir() || at.getType() == Material.WATER) || at.getType() == Material.WATER && at.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() != 0) { p.sendActionBar(ChatColor.RED + "No room for the lamp there."); return; }
        int mine = 0;
        for (Lamp l : lamps) if (l.owner.equals(p.getUniqueId())) mine++;
        if (mine >= (int) cfg("max-per-player", 3) && !p.hasPermission("faultlinedeep.admin")) { p.sendMessage(ChatColor.RED + "You already have " + mine + " lighthouses (the most you can have)."); return; }
        BlockPlaceEvent check = new BlockPlaceEvent(at, at.getState(), e.getClickedBlock(), e.getItem(), p, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(check); // claim plugins, the Bubble's guard
        if (check.isCancelled()) return;
        at.setType(Material.SEA_LANTERN, false);
        Lamp l = new Lamp();
        l.world = at.getWorld().getName(); l.x = at.getX(); l.y = at.getY(); l.z = at.getZ(); l.owner = p.getUniqueId();
        int h = height(at.getWorld(), l);
        l.radius = radiusFor(h);
        lamps.add(l);
        save();
        if (p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
        at.getWorld().playSound(at.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.2f);
        at.getWorld().spawnParticle(Particle.FLASH, at.getLocation().clone().add(0.5, 0.5, 0.5), 1, org.bukkit.Color.WHITE); // (FLASH needs a colour since 1.21.9: without one it threw)
        p.sendMessage(ChatColor.YELLOW + "The lighthouse is lit: a tower of " + h + " blocks, light reaches " + (int) Math.round(l.radius) + " blocks."
                + (l.radius < cfg("max-radius", 48) ? ChatColor.GRAY + " (Build it higher to reach farther.)" : ""));
        pl.console("index discover " + p.getName() + " lighthouse_lamp");
    }

    Lamp at(Block b) { for (Lamp l : lamps) if (l.is(b)) return l; return null; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Lamp l = at(e.getBlock());
        if (l == null) return;
        e.setDropItems(false);
        lamps.remove(l);
        save();
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().clone().add(0.5, 0.5, 0.5), lamp(1));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }
}
