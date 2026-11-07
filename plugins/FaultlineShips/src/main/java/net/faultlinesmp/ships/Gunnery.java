package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.6.12: manning a gun. Sneak + right-click with a Cannonball next to a ship's cannon or a land cannon and you take the
 * gun: you're held in place, W/S raise and lower the barrel, A/D swing it, Sprint = fine steps, scrolling the hotbar
 * picks the powder charge, Jump (or right-click) fires, Sneak lets go. The gunner alone sees the shot's arc and where it
 * will land.
 */
final class Gunnery implements Listener {

    /** Powder charges: name, muzzle speed x, reload x. */
    static final String[] CHARGE_NAMES = {"Light", "Standard", "Heavy"};
    static final double[] CHARGE_SPEED = {0.7, 1.0, 1.3};
    static final double[] CHARGE_RELOAD = {0.8, 1.0, 1.35};

    static final class Gunner {
        final UUID player;
        final Ship ship; final int cell;            // a ship's gun
        final LandCannons.Cannon land;              // or a land cannon
        float trav, elev;                           // ship: traverse (+ = left) / elevation; land: yaw / elevation (+ = up)
        int charge = 1;
        boolean armed;                              // sneak was let go since taking the gun (sneak again = leave)
        boolean lastJump;
        int t;
        Gunner(UUID player, Ship ship, int cell, LandCannons.Cannon land) { this.player = player; this.ship = ship; this.cell = cell; this.land = land; }
    }

    private final FaultlineShips pl;
    final NamespacedKey modKey;
    final Map<UUID, Gunner> gunners = new HashMap<>();

    Gunnery(FaultlineShips pl) {
        this.pl = pl;
        modKey = new NamespacedKey(pl, "gunner");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    double cfg(String k, double d) { return pl.getConfig().getDouble("gunnery." + k, d); }

    // ---------------------------------------------------------------- who mans what

    boolean manning(Player p) { return gunners.containsKey(p.getUniqueId()); }

    boolean manning(Player p, Ship s, int i) {
        Gunner g = gunners.get(p.getUniqueId());
        return g != null && g.ship == s && g.cell == i;
    }

    boolean manning(Player p, LandCannons.Cannon c) {
        Gunner g = gunners.get(p.getUniqueId());
        return g != null && g.land == c;
    }

    boolean manned(Ship s, int i) {
        for (Gunner g : gunners.values()) if (g.ship == s && g.cell == i) return true;
        return false;
    }

    boolean manned(LandCannons.Cannon c) {
        for (Gunner g : gunners.values()) if (g.land == c) return true;
        return false;
    }

    /** Sneak + right-click with a Cannonball: takes the nearest gun. False when there's no gun in reach (the click goes on). */
    boolean man(Player p) {
        if (!pl.getConfig().getBoolean("gunnery.enabled", true)) return false;
        if (manning(p)) return true;
        double reach = cfg("reach", 2.8), best = reach * reach;
        Location eye = p.getLocation().add(0, 1.0, 0);
        Ship bs = null; int bi = -1; LandCannons.Cannon bc = null;
        for (Ship s : pl.ships.values()) {
            if (!s.spawned() || !eye.getWorld().getName().equals(s.world)) continue;
            for (int i : s.cannons()) {
                Location c = s.gunCenter(i);
                double d = c.distanceSquared(eye);
                if (d < best) { best = d; bs = s; bi = i; bc = null; }
            }
        }
        for (LandCannons.Cannon c : pl.landCannons.byBlock.values()) {
            if (!c.world.equals(eye.getWorld().getName())) continue;
            Location at = c.center();
            if (at == null) continue;
            double d = at.distanceSquared(eye);
            if (d < best) { best = d; bc = c; bs = null; }
        }
        if (bs == null && bc == null) return false;
        if (p.getVehicle() != null) { p.sendActionBar(Component.text("Get off your seat to man a gun.", NamedTextColor.YELLOW)); return true; }
        Gunner g;
        if (bs != null) {
            if (bs.ai != null) return false;
            if (!pl.mayCaptain(p, bs)) { p.sendActionBar(Component.text("Only the ship's owner and crew can man its guns.", NamedTextColor.RED)); return true; }
            if (bs.building || bs.wrecked) { p.sendActionBar(Component.text("That gun can't be manned right now.", NamedTextColor.RED)); return true; }
            if (manned(bs, bi)) { p.sendActionBar(Component.text("Someone's manning that gun.", NamedTextColor.YELLOW)); return true; }
            g = new Gunner(p.getUniqueId(), bs, bi, null);
            float[] a = bs.aim.get(bi);
            if (a != null) { g.trav = a[0]; g.elev = a[1]; }
        } else {
            if (!pl.landCannons.mayUse(p, bc)) { p.sendActionBar(Component.text("That isn't your cannon.", NamedTextColor.RED)); return true; }
            if (manned(bc)) { p.sendActionBar(Component.text("Someone's manning that gun.", NamedTextColor.YELLOW)); return true; }
            g = new Gunner(p.getUniqueId(), null, -1, bc);
            g.trav = bc.yaw; g.elev = -bc.pitch;
        }
        gunners.put(p.getUniqueId(), g);
        freeze(p, true);
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.7f);
        p.sendMessage(Component.text("You man the gun. ", NamedTextColor.GOLD)
                .append(Component.text("W/S elevation, A/D traverse, Sprint = fine aim, scroll = powder charge, Jump or right-click = fire, Sneak = leave.", NamedTextColor.GRAY)));
        return true;
    }

    void leave(Player p, String why) {
        Gunner g = gunners.remove(p.getUniqueId());
        freeze(p, false);
        if (g != null && why != null) p.sendActionBar(Component.text(why, NamedTextColor.GRAY));
    }

    void leaveAll() {
        for (UUID id : new ArrayList<>(gunners.keySet())) {
            Player p = Bukkit.getPlayer(id);
            gunners.remove(id);
            if (p != null) freeze(p, false);
        }
    }

    /** Held in place: no walking, no jumping (the keys lay the gun). */
    void freeze(Player p, boolean on) {
        for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) if (modKey.equals(m.getKey())) inst.removeModifier(m);
            if (on) inst.addModifier(new AttributeModifier(modKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
    }

    // ---------------------------------------------------------------- firing

    /** Fires the gun this player mans, where it's laid. */
    boolean fire(Player p) {
        Gunner g = gunners.get(p.getUniqueId());
        if (g == null) return false;
        double sp = CHARGE_SPEED[g.charge], rl = CHARGE_RELOAD[g.charge];
        boolean ok;
        if (g.ship != null) {
            pl.layGun(g.ship, g.cell, g.trav, g.elev, 1);
            ok = pl.fireGun(p, g.ship, g.cell, sp, rl);
        } else {
            pl.landCannons.turn(g.land, g.trav, -g.elev, 0.01f);
            ok = pl.landCannons.fireAt(p, g.land, sp, rl);
        }
        if (ok) Safe.vel(p, p.getVelocity().add(new Vector(0, 0.05, 0))); // the deck shakes
        return ok;
    }

    // ---------------------------------------------------------------- every tick

    void tick() {
        if (gunners.isEmpty()) return;
        for (Gunner g : new ArrayList<>(gunners.values())) {
            Player p = Bukkit.getPlayer(g.player);
            if (p == null || !p.isOnline()) { gunners.remove(g.player); continue; }
            String gone = check(p, g);
            if (gone != null) { leave(p, gone); continue; }
            Input in = pl.inputSource.apply(p);
            if (in == null) continue;
            if (!in.isSneak()) g.armed = true;
            else if (g.armed) { leave(p, "You leave the gun."); continue; }
            double step = in.isSprint() ? cfg("fine-step", 0.35) : cfg("step", 1.4);
            float dt = 0, de = 0;
            if (in.isForward()) de += step;
            if (in.isBackward()) de -= step;
            if (in.isLeft()) dt += step;
            if (in.isRight()) dt -= step;
            if (g.ship != null) {
                double mt = pl.maxTraverse();
                g.trav = (float) Math.max(-mt, Math.min(mt, g.trav + dt));
                g.elev = (float) Math.max(pl.minElevation(), Math.min(pl.maxElevation(), g.elev + de));
                pl.layGun(g.ship, g.cell, g.trav, g.elev, 2);
            } else {
                g.trav = wrap(g.trav - dt); // A = left = yaw down
                g.elev = (float) Math.max(cfg("land-min-elevation", -20), Math.min(cfg("land-max-elevation", 45), g.elev + de));
                pl.landCannons.turn(g.land, g.trav, -g.elev, 0.3f);
            }
            if ((dt != 0 || de != 0) && g.t % 4 == 0) p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, SoundCategory.PLAYERS, 0.25f, 1.8f);
            boolean jump = in.isJump();
            if (jump && !g.lastJump) fire(p);
            g.lastJump = jump;
            if (g.t % 3 == 0) drawArc(p, g);
            if (g.t % 5 == 0) hud(p, g);
            g.t++;
        }
    }

    static float wrap(float yaw) {
        yaw %= 360f;
        if (yaw >= 180f) yaw -= 360f;
        if (yaw < -180f) yaw += 360f;
        return yaw;
    }

    /** Why this player can't stay on the gun (null = they can). */
    String check(Player p, Gunner g) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return "You leave the gun.";
        if (p.getVehicle() != null) return "You leave the gun.";
        Location at;
        if (g.ship != null) {
            if (!pl.ships.containsValue(g.ship) || !g.ship.spawned() || g.cell >= g.ship.blocks.length || g.ship.blocks[g.cell] == null) return "The gun is gone.";
            if (g.ship.wrecked) return "The ship is wrecked.";
            at = g.ship.gunCenter(g.cell);
        } else {
            if (!pl.landCannons.byBlock.containsValue(g.land)) return "The gun is gone.";
            at = g.land.center();
        }
        if (at == null || at.getWorld() != p.getWorld()) return "The gun is gone.";
        double max = cfg("leave-distance", 4.5);
        if (at.distanceSquared(p.getLocation().add(0, 1, 0)) > max * max) return "You're too far from the gun.";
        return null;
    }

    Location muzzle(Gunner g) { return g.ship != null ? g.ship.gunMuzzle(g.cell) : pl.landCannons.muzzle(g.land); }

    Vector velocity(Gunner g) {
        Vector dir = g.ship != null ? g.ship.gunDir(g.cell) : LandCannons.dirOf(g.trav, -g.elev);
        double base = g.ship != null ? pl.cfg("cannon.speed", 2.4) : pl.landCannons.cfg("speed", 2.6);
        return dir.multiply(base * CHARGE_SPEED[g.charge]);
    }

    /** The shot's flight (a snowball: drag 0.99, gravity 0.03 a tick) until it hits something solid. Last point = landing. */
    List<Location> flight(Gunner g, int maxTicks) {
        List<Location> pts = new ArrayList<>();
        Location pos = muzzle(g);
        World w = pos.getWorld();
        Vector v = velocity(g);
        pts.add(pos.clone());
        for (int k = 0; k < maxTicks; k++) {
            Location next = pos.clone().add(v);
            // check the block on the way at half steps so a fast ball can't skip a wall
            Location mid = pos.clone().add(v.clone().multiply(0.5));
            if (solid(w, mid)) { pts.add(mid); return pts; }
            if (solid(w, next)) { pts.add(next); return pts; }
            if (next.getY() < w.getMinHeight()) { pts.add(next); return pts; }
            pos = next;
            pts.add(pos.clone());
            v.multiply(0.99);
            v.setY(v.getY() - 0.03);
        }
        return pts;
    }

    static boolean solid(World w, Location l) {
        if (l.getY() >= w.getMaxHeight()) return false; // flying over the build limit
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return true;
        Block b = w.getBlockAt(l);
        return b.getType().isSolid();
    }

    void drawArc(Player p, Gunner g) {
        List<Location> pts = flight(g, (int) cfg("arc-ticks", 160));
        Particle.DustOptions trail = new Particle.DustOptions(Color.fromRGB(255, 200, 60), 0.7f);
        Particle.DustOptions mark = new Particle.DustOptions(Color.fromRGB(230, 40, 40), 1.2f);
        // a dot every ~1.5 blocks along the arc
        double carry = 0;
        for (int k = 1; k < pts.size(); k++) {
            Location a = pts.get(k - 1), b = pts.get(k);
            double len = a.distance(b);
            Vector d = b.toVector().subtract(a.toVector());
            double s = 1.5 - carry;
            while (s <= len) {
                p.spawnParticle(Particle.DUST, a.clone().add(d.clone().multiply(s / Math.max(1e-6, len))), 1, 0, 0, 0, 0, trail);
                s += 1.5;
            }
            carry = len - (s - 1.5);
        }
        Location land = pts.get(pts.size() - 1);
        for (int k = 0; k < 16; k++) {
            double a = Math.PI * 2 * k / 16;
            p.spawnParticle(Particle.DUST, land.clone().add(Math.cos(a) * 1.6, 0.3, Math.sin(a) * 1.6), 1, 0, 0, 0, 0, mark);
        }
        p.spawnParticle(Particle.DUST, land.clone().add(0, 0.4, 0), 3, 0.1, 0.3, 0.1, 0, mark);
    }

    void hud(Player p, Gunner g) {
        List<Location> pts = flight(g, (int) cfg("arc-ticks", 160));
        Location from = pts.get(0), to = pts.get(pts.size() - 1);
        double range = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        boolean ready = g.ship != null ? g.ship.cannonReady.getOrDefault(g.cell, 0L) <= pl.now : g.land.readyAt <= pl.now;
        String trav = g.ship != null ? String.format("Traverse %+.1f°", -g.trav) : String.format("Bearing %s", bearing(g.trav));
        p.sendActionBar(Component.text(String.format("Elevation %+.1f°  ", g.elev), NamedTextColor.GOLD)
                .append(Component.text(trav + "  ", NamedTextColor.YELLOW))
                .append(Component.text("Charge: " + CHARGE_NAMES[g.charge] + "  ", NamedTextColor.AQUA))
                .append(Component.text(String.format("Range %.0fm  ", range), NamedTextColor.WHITE))
                .append(ready ? Component.text("READY", NamedTextColor.GREEN) : Component.text("reloading", NamedTextColor.RED)));
    }

    static String bearing(float yaw) {
        double deg = ((yaw + 180) % 360 + 360) % 360; // 0 = north
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return String.format("%.0f° %s", deg, n[(int) Math.round(deg / 45) % 8]);
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent e) {
        Gunner g = gunners.get(e.getPlayer().getUniqueId());
        if (g == null) return;
        e.setCancelled(true);
        int diff = e.getNewSlot() - e.getPreviousSlot();
        if (diff > 4) diff -= 9;
        if (diff < -4) diff += 9;
        int c = Math.max(0, Math.min(CHARGE_NAMES.length - 1, g.charge + (diff > 0 ? 1 : -1)));
        if (c == g.charge) return;
        g.charge = c;
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_SAND_PLACE, SoundCategory.PLAYERS, 0.6f, 0.8f + 0.2f * c);
        hud(e.getPlayer(), g);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { if (manning(e.getPlayer())) leave(e.getPlayer(), null); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { if (manning(e.getEntity())) leave(e.getEntity(), null); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { if (manning(e.getPlayer())) leave(e.getPlayer(), null); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR && manning(e.getPlayer())) leave(e.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        // the ship's own deck fixes are tiny; anything farther is a real teleport
        if (!manning(e.getPlayer()) || e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 25) leave(e.getPlayer(), "You leave the gun.");
    }

    /** A crash left the modifiers on: take them off. */
    @EventHandler public void onJoin(PlayerJoinEvent e) { freeze(e.getPlayer(), false); }
}
