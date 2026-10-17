package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * THE LEVIATHAN MIGRATION (Deep 1.8.0): every 60-120 minutes (`migration.every-minutes-min/max`), with someone in the Sunlit
 * Sea, a pod of 3-5 GREAT WHALES (DeepMobs "greatwhale", passive, 400 health) appears 50 blocks from one of them and crosses
 * the Sunlit Sea in a straight line, `migration.distance` (600) blocks. At the end the eldest one sinks to the floor:
 * a fresh WHALE FALL (bone ribs and a chest of loot) for whoever followed them. Killing a whale has a
 * `migration.umbral-chance` (35%) to bring the Umbral Leviathan up from below. `/deep migration [start|stop]`.
 */
final class Migration {
    final FaultlineDeep pl;
    final Random random = new Random();
    final List<DeepMobs.Beast> pod = new ArrayList<>();
    Vector heading;
    Location start;
    long nextAt;
    boolean active;

    Migration(FaultlineDeep pl) {
        this.pl = pl;
        nextAt = System.currentTimeMillis() + (long) (gap() * 60_000);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 2L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("migration." + k, def); }

    double gap() { double lo = cfg("every-minutes-min", 60), hi = cfg("every-minutes-max", 120); return lo + random.nextDouble() * Math.max(0, hi - lo); }

    int ticks;

    void tick() {
        ticks += 2;
        World w = pl.world;
        if (w == null || !pl.getConfig().getBoolean("migration.enabled", true)) return;
        if (!active) {
            if (ticks % 200 == 0 && System.currentTimeMillis() >= nextAt) {
                List<Player> sunlit = new ArrayList<>();
                for (Player p : w.getPlayers()) if (DeepMobs.fair(p) && pl.zone(p.getLocation()) == DeepGenerator.Zone.SUNLIT) sunlit.add(p);
                if (!sunlit.isEmpty()) start(sunlit.get(random.nextInt(sunlit.size())));
                else nextAt = System.currentTimeMillis() + 5 * 60_000;
            }
            return;
        }
        pod.removeIf(b -> !b.body.isValid() || b.body.isDead());
        if (pod.isEmpty()) { end(false, null); return; }
        // the pod swims on together along its heading, bobbing gently
        DeepMobs.Beast lead = pod.get(0);
        for (int i = 0; i < pod.size(); i++) {
            DeepMobs.Beast b = pod.get(i);
            Vector v = heading.clone().multiply(cfg("speed", 0.22));
            v.setY(Math.sin((ticks + i * 30) * 0.03) * 0.03);
            Location l = b.body.getLocation();
            if (l.getY() > DeepGenerator.SEA - 6) v.setY(-0.05);
            if (l.clone().add(heading.clone().multiply(3)).getBlock().getType().isSolid()) v.setY(0.15); // over a rise
            Edition.vel(b.body, v);
        }
        if (lead.body.getLocation().distance(start) > cfg("distance", 600)) end(true, lead);
    }

    boolean start(Player near) {
        World w = pl.world;
        if (active || w == null) return false;
        DeepMobs.Kind k = DeepMobs.KINDS.get("greatwhale");
        double a = random.nextDouble() * Math.PI * 2;
        heading = new Vector(Math.cos(a), 0, Math.sin(a));
        Location base = near.getLocation().clone().add(heading.clone().multiply(-50)).add(new Vector(-heading.getZ(), 0, heading.getX()).multiply(random.nextGaussian() * 10));
        base.setY(Math.max(pl.generator.seaFloor(w.getSeed(), base.getBlockX(), base.getBlockZ()) + 12, DeepGenerator.SEA - 30));
        int n = 3 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            Location at = base.clone().add(heading.clone().multiply(-i * 9)).add(random.nextGaussian() * 4, random.nextGaussian() * 2, random.nextGaussian() * 4);
            if (at.getBlock().getType() != Material.WATER) continue;
            DeepMobs.Beast b = pl.mobs.spawn(k, at);
            if (b == null) continue;
            if (b.body instanceof Mob m) m.setAI(false);
            try { b.body.setRemoveWhenFarAway(false); } catch (RuntimeException ignored) { }
            pod.add(b);
        }
        if (pod.isEmpty()) return false;
        active = true;
        start = pod.get(0).body.getLocation();
        String dir = Ocean.compass(heading);
        for (Player p : w.getPlayers()) {
            p.sendMessage(ChatColor.BLUE + "" + ChatColor.BOLD + "The Leviathan Migration! " + ChatColor.GRAY + "A pod of " + pod.size() + " great whales is crossing the Sunlit Sea near "
                    + start.getBlockX() + ", " + start.getBlockZ() + ", heading " + dir + ". Follow them...");
            p.playSound(p, Sound.ENTITY_WARDEN_LISTENING, SoundCategory.AMBIENT, 0.8f, 0.3f);
        }
        return true;
    }

    /** The journey's end (or the pod is gone): the eldest sinks into a whale fall. */
    void end(boolean arrived, DeepMobs.Beast eldest) {
        World w = pl.world;
        active = false;
        nextAt = System.currentTimeMillis() + (long) (gap() * 60_000);
        if (arrived && eldest != null && w != null) {
            Location at = eldest.body.getLocation();
            pl.mobs.remove(eldest);
            Location fall = whaleFall(at);
            if (fall != null) for (Player p : w.getPlayers())
                p.sendMessage(ChatColor.BLUE + "The eldest whale of the pod sinks at " + fall.getBlockX() + ", " + fall.getBlockY() + ", " + fall.getBlockZ() + ChatColor.GRAY + ": a fresh whale fall.");
        }
        for (DeepMobs.Beast b : pod) pl.mobs.remove(b); // the rest swim off into the deep
        pod.clear();
    }

    /** Bone ribs on the sea floor under `at`, and a chest of loot in the middle. */
    Location whaleFall(Location at) {
        World w = at.getWorld();
        int x0 = at.getBlockX(), z0 = at.getBlockZ();
        int floor = pl.generator.seaFloor(w.getSeed(), x0, z0);
        if (!w.isChunkLoaded(x0 >> 4, z0 >> 4)) return null;
        Vector f = heading.clone();
        Vector r = new Vector(-f.getZ(), 0, f.getX());
        int base = floor + 1;
        for (int t = -10; t <= 10; t++) {
            Block spine = w.getBlockAt(at.clone().add(f.clone().multiply(t)).getBlockX(), base, at.clone().add(f.clone().multiply(t)).getBlockZ());
            if (spine.getType() == Material.WATER) spine.setType(Material.BONE_BLOCK, false);
            if (Math.floorMod(t, 3) != 0) continue;
            double R = 5 - Math.max(0, t) * 0.2;
            for (double ang = 0; ang <= Math.PI; ang += 0.15) {
                Location p = at.clone().add(f.clone().multiply(t)).add(r.clone().multiply(Math.cos(ang) * R));
                Block b = w.getBlockAt(p.getBlockX(), base + (int) Math.round(Math.sin(ang) * R), p.getBlockZ());
                if (b.getType() == Material.WATER) b.setType(Material.BONE_BLOCK, false);
            }
        }
        Block chest = w.getBlockAt(x0, base, z0);
        chest.setType(Material.CHEST, false);
        if (chest.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl) { wl.setWaterlogged(true); chest.setBlockData(wl, false); }
        if (chest.getState() instanceof Chest c) {
            List<ItemStack> loot = new ArrayList<>(List.of(new ItemStack(Material.PRISMARINE_CRYSTALS, 8 + random.nextInt(9)), new ItemStack(Material.NAUTILUS_SHELL, 2 + random.nextInt(3)),
                    new ItemStack(Material.BONE, 10 + random.nextInt(10)), new ItemStack(Material.GOLD_INGOT, 3 + random.nextInt(5)), new ItemStack(Material.DIAMOND, 1 + random.nextInt(3))));
            if (random.nextDouble() < cfg("heart-chance", 0.35)) loot.add(new ItemStack(Material.HEART_OF_THE_SEA));
            for (ItemStack it : loot) c.getBlockInventory().setItem(random.nextInt(27), it);
        }
        Location spot = new Location(w, x0, base, z0);
        w.spawnParticle(Particle.BUBBLE_COLUMN_UP, spot.clone().add(0.5, 1, 0.5), 60, 3, 2, 3, 0.1);
        return spot;
    }

    /** A player killed a great whale: sometimes something comes up from below for them. */
    void whaleKilled(Player killer, Location at) {
        if (killer == null || random.nextDouble() >= cfg("umbral-chance", 0.35)) return;
        if (Bukkit.getPluginCommand("umbral") == null) return;
        killer.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "The whale's blood sinks into the dark... and something down there tastes it.");
        Location l = killer.getLocation();
        Bukkit.getScheduler().runTaskLater(pl, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "umbral summon " + l.getWorld().getName() + " " + l.getX() + " " + (l.getY() - 6) + " " + l.getZ()), 60L);
    }

    void shutdown() { for (DeepMobs.Beast b : pod) pl.mobs.remove(b); pod.clear(); active = false; }
}
