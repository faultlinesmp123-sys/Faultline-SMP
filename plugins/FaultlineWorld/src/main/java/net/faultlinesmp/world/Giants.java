package net.faultlinesmp.world;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The Zombie Giant (the Fibers' rarest guest): vanilla's Giant (the huge zombie, on Java and Bedrock alike) with a
 * brain of our own, since the vanilla one does nothing. It walks after the nearest player, punches (knockback),
 * stomps (a shockwave), roars (slowness) and throws zombies. Only spawns in a cavern with room for its 12-block body.
 */
final class Giants implements Listener {
    static final String TAG = "faultline_zombie_giant";
    final FaultlineWorld plugin;
    final Map<UUID, Brain> giants = new HashMap<>();
    final Random random = new Random();

    Giants(FaultlineWorld plugin) { this.plugin = plugin; }

    static final class Brain {
        final Giant g; final BossBar bar; int cd = 40, punch, step; Player target;
        Brain(Giant g, BossBar bar) { this.g = g; this.bar = bar; }
    }

    /** Spawns one near a player in the Fibers when there's a spot tall enough; true if it came. */
    boolean spawnNear(Player p) {
        Location at = roomFor(p.getLocation());
        if (at == null) return false;
        spawn(at);
        for (Player o : p.getWorld().getPlayers()) if (o.getLocation().distanceSquared(at) < 120 * 120) {
            o.sendTitle("§2The Zombie Giant", "§7something huge woke up in the Fibers", 10, 60, 20);
            o.playSound(o, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.5f);
        }
        return true;
    }

    Giant spawn(Location at) {
        Giant g = (Giant) at.getWorld().spawnEntity(at, EntityType.GIANT, CreatureSpawnEvent.SpawnReason.CUSTOM, ent -> { Giant e = (Giant) ent;
            e.addScoreboardTag(TAG);
            e.setCustomName("§2§lZombie Giant");
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            double hp = plugin.getConfig().getDouble("giant.health", 600);
            if (e.getAttribute(Attribute.MAX_HEALTH) != null) e.getAttribute(Attribute.MAX_HEALTH).setBaseValue(Math.min(1024, hp));
            e.setHealth(Math.min(1024, hp));
            if (e.getAttribute(Attribute.KNOCKBACK_RESISTANCE) != null) e.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1);
            if (e.getAttribute(Attribute.MOVEMENT_SPEED) != null) e.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.26);
        });
        BossBar bar = Bukkit.createBossBar("§2Zombie Giant", BarColor.GREEN, BarStyle.SEGMENTED_10);
        giants.put(g.getUniqueId(), new Brain(g, bar));
        return g;
    }

    /** A spot 14-30 blocks away with a 3x3 column of 13 blocks of air on solid ground. */
    Location roomFor(Location c) {
        World w = c.getWorld();
        for (int k = 0; k < 30; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = 14 + random.nextDouble() * 16;
            int x = c.getBlockX() + (int) (Math.cos(a) * d), z = c.getBlockZ() + (int) (Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = c.getBlockY() - 10; y <= c.getBlockY() + 10; y++) {
                if (!w.getBlockAt(x, y - 1, z).getType().isSolid()) continue;
                boolean room = true;
                for (int dx = -1; dx <= 1 && room; dx++) for (int dz = -1; dz <= 1 && room; dz++) for (int dy = 0; dy < 13; dy++)
                    if (!w.getBlockAt(x + dx, y + dy, z + dz).isPassable()) { room = false; break; }
                if (room) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    void tick() {
        for (Iterator<Brain> it = giants.values().iterator(); it.hasNext(); ) {
            Brain b = it.next();
            if (!b.g.isValid() || b.g.isDead()) { b.bar.removeAll(); it.remove(); continue; }
            try { think(b); } catch (Throwable e) { plugin.getLogger().fine("[Giant] " + e); }
        }
    }

    void think(Brain b) {
        Giant g = b.g;
        Location gl = g.getLocation();
        // target: nearest survival player within 48
        if (b.target == null || !b.target.isValid() || b.target.getWorld() != g.getWorld() || b.target.getLocation().distanceSquared(gl) > 64 * 64 || !Life.survival(b.target)) {
            b.target = null; double best = 48 * 48;
            for (Player p : g.getWorld().getPlayers()) { double d = p.getLocation().distanceSquared(gl); if (Life.survival(p) && d < best) { best = d; b.target = p; } }
        }
        // boss bar for players near
        double max = g.getAttribute(Attribute.MAX_HEALTH) != null ? g.getAttribute(Attribute.MAX_HEALTH).getValue() : 600;
        b.bar.setProgress(Math.max(0, Math.min(1, g.getHealth() / max)));
        for (Player p : g.getWorld().getPlayers()) {
            boolean near = p.getLocation().distanceSquared(gl) < 60 * 60;
            if (near && !b.bar.getPlayers().contains(p)) b.bar.addPlayer(p);
            else if (!near && b.bar.getPlayers().contains(p)) b.bar.removePlayer(p);
        }
        Player t = b.target;
        if (t == null) return;
        Vector to = t.getLocation().toVector().subtract(gl.toVector());
        double flat = Math.hypot(to.getX(), to.getZ());
        // face and walk
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        g.setRotation(yaw, 0);
        if (flat > 4.0 && b.step++ % 10 == 0) {
            if (!g.getPathfinder().moveTo(t.getLocation(), 1.0)) {
                Vector v = new Vector(to.getX(), 0, to.getZ()).normalize().multiply(0.22);
                v.setY(g.isOnGround() && blocked(g, v) ? 0.6 : g.getVelocity().getY());
                if (Double.isFinite(v.getX()) && Double.isFinite(v.getZ())) g.setVelocity(v);
            }
        }
        if (b.cd > 0) { b.cd--; }
        // punch
        if (flat < 5.2 && Math.abs(to.getY()) < 9 && b.punch-- <= 0) {
            b.punch = 30;
            g.swingMainHand();
            t.damage(plugin.getConfig().getDouble("giant.punch-damage", 14), g);
            Vector kb = new Vector(to.getX(), 0, to.getZ()).normalize().multiply(1.6).setY(0.55);
            if (Double.isFinite(kb.getX())) t.setVelocity(kb);
            g.getWorld().playSound(gl, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.5f);
            return;
        }
        if (b.cd > 0) return;
        int move = random.nextInt(3);
        if (move == 0 && flat < 10) stomp(b);
        else if (move == 1) roar(b);
        else throwZombie(b, t);
    }

    boolean blocked(Giant g, Vector v) {
        Location ahead = g.getLocation().add(v.clone().normalize().multiply(2.5));
        return !ahead.getBlock().isPassable() || !ahead.clone().add(0, 1, 0).getBlock().isPassable();
    }

    void stomp(Brain b) {
        b.cd = 80;
        Location l = b.g.getLocation();
        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.4f);
        l.getWorld().spawnParticle(Particle.EXPLOSION, l, 6, 2, 0.2, 2, 0);
        l.getWorld().spawnParticle(Particle.BLOCK, l, 120, 4, 0.2, 4, 0, l.clone().subtract(0, 1, 0).getBlock().getBlockData());
        for (Player p : l.getWorld().getPlayers()) {
            double d = p.getLocation().distance(l);
            if (d > 10 || !Life.survival(p) || !p.isOnGround()) continue;
            p.damage(plugin.getConfig().getDouble("giant.stomp-damage", 10) * (1 - d / 14), b.g);
            Vector v = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
            v.normalize().multiply(1.2).setY(0.9);
            p.setVelocity(v);
        }
    }

    void roar(Brain b) {
        b.cd = 120;
        Location l = b.g.getLocation();
        l.getWorld().playSound(l, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.45f);
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 20 * 20 && Life.survival(p)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
        }
    }

    void throwZombie(Brain b, Player t) {
        b.cd = 70;
        Location hand = b.g.getLocation().add(0, 9, 0);
        Zombie z = (Zombie) hand.getWorld().spawnEntity(hand, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> ((Zombie) e).setAdult());
        Vector v = t.getLocation().add(0, 1, 0).toVector().subtract(hand.toVector());
        double d = v.length();
        if (d < 1e-3) return;
        v.normalize().multiply(Math.min(2.2, 0.6 + d / 14)).setY(0.4 + d / 40);
        z.setVelocity(v);
        hand.getWorld().playSound(hand, Sound.ENTITY_ZOMBIE_AMBIENT, 2f, 0.4f);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Giant g) || !g.getScoreboardTags().contains(TAG)) return;
        Brain b = giants.remove(g.getUniqueId());
        if (b != null) b.bar.removeAll();
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.DIAMOND, 4 + random.nextInt(5)));
        e.getDrops().add(new ItemStack(Material.EMERALD, 8 + random.nextInt(9)));
        e.getDrops().add(new ItemStack(Material.ROTTEN_FLESH, 32));
        if (random.nextDouble() < 0.35) e.getDrops().add(new ItemStack(Material.TOTEM_OF_UNDYING));
        e.setDroppedExp(500);
        Player k = g.getKiller();
        if (k != null) {
            if (Bukkit.getPluginCommand("index") != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + k.getName() + " zombie_giants 1");
            if (Bukkit.getPluginCommand("index") != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + k.getName() + " zombie_giant");
            Bukkit.broadcastMessage("§2" + k.getName() + " brought down a Zombie Giant in the Fibers!");
        }
    }

    void removeAll() {
        for (Brain b : giants.values()) { b.bar.removeAll(); if (b.g.isValid()) b.g.remove(); }
        giants.clear();
    }
}
