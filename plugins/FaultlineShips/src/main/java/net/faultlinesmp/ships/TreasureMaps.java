package net.faultlinesmp.ships;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * PIRATE TREASURE MAPS (Ships 1.6.1, config pirates.treasure:). Plundering a skeleton ship's hold finds one now and then
 * (pirates.treasure.plunder-chance 35%); admins: /ship pirates map [player]. The map (PAPER drawn as a vanilla map, PDC
 * faultlineships:treasure_map = "world;x;z;id") points at a spot treasure.min/max-distance (250-700) from where it was
 * found. Held, the action bar says how far and which way; close in, a red X only the holder sees marks the spot; dig
 * any block at the X (from the surface down 5, the sea floor when it's under water) and a chest of loot comes up
 * (treasure.loot = "MATERIAL:min-max:chance", treasure.commands = Pirates.run commands). The map is used up;
 * index stat treasure_maps. The surface height is written into the map the first time you get close (treasure_y).
 */
final class TreasureMaps implements Listener {

    static final List<String> DEFAULT_LOOT = List.of("GOLD_INGOT:8-24:1.0", "DIAMOND:3-8:0.8", "EMERALD:6-16:0.6",
            "NETHERITE_SCRAP:1-2:0.25", "ENCHANTED_GOLDEN_APPLE:1-1:0.05", "GOLDEN_APPLE:1-3:0.4", "EXPERIENCE_BOTTLE:8-16:0.5");
    static final List<String> DEFAULT_COMMANDS = List.of("givemythicbag 1 {player}|0.5", "ship give cannonball {4-12} {player}|0.6");

    private final FaultlineShips pl;
    final NamespacedKey mapKey, yKey;
    private final Random rnd = new Random();

    TreasureMaps(FaultlineShips pl) {
        this.pl = pl;
        mapKey = new NamespacedKey(pl, "treasure_map");
        yKey = new NamespacedKey(pl, "treasure_y");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 10L, 10L);
    }

    private double cfg(String k, double d) { return pl.getConfig().getDouble("pirates.treasure." + k, d); }

    ItemStack map(Location from) {
        double a = rnd.nextDouble() * Math.PI * 2;
        double d = cfg("min-distance", 250) + rnd.nextDouble() * Math.max(0, cfg("max-distance", 700) - cfg("min-distance", 250));
        int x = (int) Math.floor(from.getX() + Math.cos(a) * d), z = (int) Math.floor(from.getZ() + Math.sin(a) * d);
        World w = from.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
        return map(w.getName(), x, z);
    }

    ItemStack map(String world, int x, int z) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Pirate Treasure Map");
        m.setLore(List.of(ChatColor.GRAY + "X marks the spot, somewhere near",
                ChatColor.WHITE + "X " + roughly(x) + ", Z " + roughly(z) + ChatColor.GRAY + " (" + world + ").",
                ChatColor.GREEN + "Hold it: it tells you which way to go.",
                ChatColor.GREEN + "Dig at the red X for the treasure."));
        m.setItemModel(NamespacedKey.minecraft("map"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(mapKey, PersistentDataType.STRING, world + ";" + x + ";" + z + ";" + UUID.randomUUID());
        it.setItemMeta(m);
        return it;
    }

    static int roughly(int v) { return Math.round(v / 50f) * 50; }

    boolean isMap(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(mapKey, PersistentDataType.STRING); }

    /** {world, x, z, id} or null. */
    String[] spot(ItemStack it) {
        if (!isMap(it)) return null;
        String[] p = it.getItemMeta().getPersistentDataContainer().get(mapKey, PersistentDataType.STRING).split(";");
        return p.length == 4 ? p : null;
    }

    static int surface(World w, int x, int z) {
        try { return w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR); }
        catch (RuntimeException e) { return w.getHighestBlockYAt(x, z); } // (the test server)
    }

    static String compass(double dx, double dz) {
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return dirs[Math.floorMod((int) Math.round(yaw / 45.0), 8)];
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack held = isMap(p.getInventory().getItemInMainHand()) ? p.getInventory().getItemInMainHand()
                    : isMap(p.getInventory().getItemInOffHand()) ? p.getInventory().getItemInOffHand() : null;
            String[] s = spot(held);
            if (s == null) continue;
            if (!p.getWorld().getName().equals(s[0])) { p.sendActionBar(ChatColor.GOLD + "🗺 This map is for " + s[0] + "."); continue; }
            int x = Integer.parseInt(s[1]), z = Integer.parseInt(s[2]);
            double dx = x + 0.5 - p.getLocation().getX(), dz = z + 0.5 - p.getLocation().getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 48 && p.getWorld().isChunkLoaded(x >> 4, z >> 4) && !held.getItemMeta().getPersistentDataContainer().has(yKey, PersistentDataType.INTEGER)) {
                ItemMeta m = held.getItemMeta();
                m.getPersistentDataContainer().set(yKey, PersistentDataType.INTEGER, surface(p.getWorld(), x, z));
                held.setItemMeta(m);
                if (isMap(p.getInventory().getItemInMainHand())) p.getInventory().setItemInMainHand(held); else p.getInventory().setItemInOffHand(held);
            }
            if (dist > 12) {
                p.sendActionBar(ChatColor.GOLD + "🗺 Treasure: " + ChatColor.WHITE + (int) dist + " blocks " + compass(dx, dz)
                        + (dist < 40 ? ChatColor.YELLOW + "  (you're close...)" : ""));
            } else {
                Integer y = held.getItemMeta().getPersistentDataContainer().get(yKey, PersistentDataType.INTEGER);
                int top = y != null ? y : surface(p.getWorld(), x, z);
                p.sendActionBar(ChatColor.RED + "" + ChatColor.BOLD + "✖ " + ChatColor.GOLD + (dist < 2.5 ? "Dig right here!" : "X marks the spot: " + (int) dist + " blocks " + compass(dx, dz)));
                Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.4f);
                for (double t = -1; t <= 1.001; t += 0.25) {
                    p.spawnParticle(Particle.DUST, x + 0.5 + t, top + 1.1, z + 0.5 + t, 1, 0, 0, 0, 0, red);
                    p.spawnParticle(Particle.DUST, x + 0.5 + t, top + 1.1, z + 0.5 - t, 1, 0, 0, 0, 0, red);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        var inv = p.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            String[] s = spot(it);
            if (s == null || !b.getWorld().getName().equals(s[0])) continue;
            int x = Integer.parseInt(s[1]), z = Integer.parseInt(s[2]);
            if (Math.abs(b.getX() - x) > 1 || Math.abs(b.getZ() - z) > 1) continue;
            Integer y = it.getItemMeta().getPersistentDataContainer().get(yKey, PersistentDataType.INTEGER);
            int top = y != null ? y : surface(b.getWorld(), x, z) + 1;
            if (b.getY() > top + 1 || b.getY() < top - 5) continue;
            inv.setItem(slot, null);
            int fs = slot;
            Bukkit.getScheduler().runTask(pl, () -> unearth(p, b, fs));
            return;
        }
    }

    void unearth(Player p, Block at, int slot) {
        boolean wet = at.getType() == Material.WATER;
        at.setType(Material.CHEST, false);
        if (wet && at.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl) { wl.setWaterlogged(true); at.setBlockData(wl, false); }
        if (at.getState() instanceof Chest chest) {
            List<ItemStack> loot = new ArrayList<>();
            List<String> table = pl.getConfig().isList("pirates.treasure.loot") ? pl.getConfig().getStringList("pirates.treasure.loot") : DEFAULT_LOOT;
            for (String row : table) {
                String[] r = row.split(":");
                if (r.length != 3) continue;
                Material m = Material.matchMaterial(r[0]);
                if (m == null) continue;
                try {
                    if (rnd.nextDouble() >= Double.parseDouble(r[2])) continue;
                    String[] mm = r[1].split("-");
                    int lo = Integer.parseInt(mm[0]), hi = Integer.parseInt(mm[mm.length - 1]);
                    loot.add(new ItemStack(m, lo + rnd.nextInt(Math.max(1, hi - lo + 1))));
                } catch (NumberFormatException ignored) { }
            }
            var ci = chest.getBlockInventory();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < ci.getSize(); i++) slots.add(i);
            Collections.shuffle(slots, rnd);
            for (int i = 0; i < loot.size() && i < slots.size(); i++) ci.setItem(slots.get(i), loot.get(i));
        }
        List<String> cmds = pl.getConfig().isList("pirates.treasure.commands") ? pl.getConfig().getStringList("pirates.treasure.commands") : DEFAULT_COMMANDS;
        for (String c : cmds) {
            String[] parts = c.split("\\|");
            double chance = 1;
            if (parts.length > 1) try { chance = Double.parseDouble(parts[1]); } catch (NumberFormatException ignored) { }
            if (rnd.nextDouble() < chance) pl.pirates.run(parts[0], p);
        }
        Location l = at.getLocation().add(0.5, 1, 0.5);
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 40, 0.5, 0.5, 0.5, 0.3);
        at.getWorld().playSound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Bukkit.broadcastMessage(ChatColor.GOLD + "☠ " + p.getName() + " dug up a pirate's buried treasure!");
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " treasure_maps 1");
    }

    /** From a plundered hold (Pirates.plunder): maybe a map. */
    boolean maybeFind(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR || rnd.nextDouble() >= cfg("plunder-chance", 0.35)) return false;
        p.getInventory().addItem(map(p.getLocation())).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        return true;
    }
}
