package net.faultlinesmp.events;

import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;

/** One sky event. Its listeners are always registered; each checks {@link #on()}. */
abstract class Ev implements Listener {

    final FaultlineEvents pl;

    Ev(FaultlineEvents pl) { this.pl = pl; }

    abstract String id();          // BLOOD_MOON (written in the world's PDC)
    abstract String key();         // blood-moon (config section, commands)
    abstract String title();
    abstract ChatColor color();
    abstract BarColor barColor();
    abstract String blurb();       // one line: what it does
    abstract boolean night();      // true = ends at dawn
    int minutes() { return Math.max(1, pl.getConfig().getInt(key() + ".minutes", 12)); }
    String barText() { return ""; }

    abstract void start(boolean announce);
    abstract void stop(boolean natural);
    void tick(long t) { }
    void second() { }
    void joined(Player p) { }
    void left(Player p) { }

    boolean on() { return pl.active == this; }
    boolean enabled() { return pl.getConfig().getBoolean(key() + ".enabled", true); }

    double d(String path, double def) { return pl.getConfig().getDouble(key() + "." + path, def); }
    int i(String path, int def) { return pl.getConfig().getInt(key() + "." + path, def); }
    boolean b(String path, boolean def) { return pl.getConfig().getBoolean(key() + "." + path, def); }
    List<String> list(String path) { return pl.getConfig().getStringList(key() + "." + path); }

    /** Title + chat line + sound for everyone in the event's worlds; the chat line goes to the whole server. */
    void announce(String title, String sub, String chat, Sound sound, float pitch) {
        if (!pl.loud()) return;
        org.bukkit.Bukkit.broadcastMessage(chat);
        for (Player p : pl.players()) {
            p.sendTitle(title, sub, 10, 70, 20);
            if (sound != null) p.playSound(p.getLocation(), sound, 0.8f, pitch);
        }
    }

    /** "item:MAT:min-max" / "MAT:min-max" -> a stack, or null. */
    ItemStack stack(String spec) {
        String s = spec.startsWith("item:") ? spec.substring(5) : spec;
        String[] p = s.split(":");
        Material m = FaultlineEvents.material(p[0]);
        if (m == null || !m.isItem()) return null;
        int lo = 1, hi = 1;
        if (p.length > 1) {
            String[] r = p[1].split("-");
            try { lo = Integer.parseInt(r[0].trim()); hi = r.length > 1 ? Integer.parseInt(r[1].trim()) : lo; } catch (NumberFormatException ignored) { }
        }
        int n = lo + (hi > lo ? pl.random.nextInt(hi - lo + 1) : 0);
        return n <= 0 ? null : new ItemStack(m, Math.min(n, m.getMaxStackSize()));
    }

    static void give(Player p, ItemStack it) {
        for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    static boolean finite(Vector v) { return Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ()); }

    static void vel(Entity e, Vector v) {
        if (!finite(v)) return;
        double len = v.length();
        if (len > 4) v.multiply(4 / len);
        e.setVelocity(v);
    }

    static boolean solid(Block b) { return b.getType().isSolid() && !b.isLiquid(); }
    static boolean open(Block b) { return !b.getType().isSolid() && !b.isLiquid() && b.getType() != Material.LAVA && b.getType() != Material.POWDER_SNOW; }

    /**
     * A spot a mob can stand on min..max blocks from the player, in a loaded chunk, on the player's own level
     * (underground = the same cave, outdoors = the surface), not in water or lava.
     */
    Location groundSpot(Player p, double min, double max, int tries) {
        World w = p.getWorld();
        Location base = p.getLocation();
        int hy = w.getHighestBlockYAt(base.getBlockX(), base.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        boolean outside = base.getBlockY() >= hy - 2;
        for (int k = 0; k < tries; k++) {
            double a = pl.random.nextDouble() * Math.PI * 2, r = min + pl.random.nextDouble() * (max - min);
            int x = (int) Math.floor(base.getX() + Math.cos(a) * r), z = (int) Math.floor(base.getZ() + Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (outside) {
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block floor = w.getBlockAt(x, y, z);
                if (!solid(floor) || floor.getType() == Material.MAGMA_BLOCK) continue;
                if (open(floor.getRelative(0, 1, 0)) && open(floor.getRelative(0, 2, 0)) && Math.abs(y - base.getY()) < 14)
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
            } else {
                for (int dy = 6; dy >= -6; dy--) {
                    Block floor = w.getBlockAt(x, base.getBlockY() + dy - 1, z);
                    if (solid(floor) && open(floor.getRelative(0, 1, 0)) && open(floor.getRelative(0, 2, 0)))
                        return new Location(w, x + 0.5, floor.getY() + 1, z + 0.5);
                }
            }
        }
        return null;
    }
}
