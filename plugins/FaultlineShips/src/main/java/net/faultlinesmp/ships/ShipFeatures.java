package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * THE SHIPS OVERHAUL (Ships 1.7.0). What makes each ship its own thing, and what makes sailing worth it:
 *  - ABILITIES: the captain presses F (swap hands) at the helm, or /ship ability. One per type, own cooldown:
 *    Dinghy ROW HARD (x1.7 speed 6 s / 30 s), Sloop TAILWIND (x1.4 speed + x1.6 turning 10 s / 45 s), Brigantine SMOKE
 *    SCREEN (12 s: half damage, anyone not crew near it is blinded / 60 s), Galleon BROADSIDE (every gun on the side you
 *    look fires at once, reload ignored, a ball each / 40 s), Pirate Ship GRAPPLE (the nearest ship within 24 is held
 *    alongside for 15 s, enemies near you get Weakness + Slowness / 90 s).
 *  - PASSIVES: the Galleon takes 15% less damage, the Pirate Ship's guns reload 15% faster, the
 *    Dinghy is rowed (the wind doesn't touch it), the Sloop turns 15% faster.
 *  - WIND: each world has a wind that veers slowly; sailing with it is up to +15% speed, into it up to -15% (Windy
 *    events and thunderstorms blow harder). Shown on the captain's bar.
 *  - CRUISE CONTROL: double-tap W (Ship.sail).
 *  - FISHING NET upgrade: a ship with a hold, under way, nets a catch into it every `net.seconds` (30).
 *  - LOOKOUT'S SPYGLASS upgrade: the nearest other ship within 160 on the captain's bar.
 *  - /ship locate: where your ships are, and a pointer to the nearest for 30 s.
 */
final class ShipFeatures implements Listener {
    final FaultlineShips pl;
    private final Random random = new Random();
    private final Map<String, double[]> wind = new HashMap<>(); // world -> {direction (yaw it blows toward), strength}
    private final Map<UUID, Long> pointing = new HashMap<>();   // /ship locate: until when (server tick)

    ShipFeatures(FaultlineShips pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    double cfg(String k, double d) { return pl.getConfig().getDouble("features." + k, d); }
    boolean on(String k) { return pl.getConfig().getBoolean("features." + k + ".enabled", true); }

    // ============================================================================================ wind
    double[] wind(World w) {
        return wind.computeIfAbsent(w.getName(), k -> new double[]{random.nextDouble() * 360, 0.6 + random.nextDouble() * 0.4});
    }

    private boolean windy(World w) {
        try {
            String ev = w.getPersistentDataContainer().get(NamespacedKey.fromString("faultlineevents:active_event"), PersistentDataType.STRING);
            return "WINDY".equalsIgnoreCase(ev);
        } catch (RuntimeException e) { return false; }
    }

    /** -1 (straight into it) .. 1 (straight with it), times how hard it blows. */
    double windOn(Ship s) {
        World w = s.world();
        if (w == null || !on("wind")) return 0;
        double[] wd = wind(w);
        double rel = Math.toRadians(s.yaw - wd[0]);
        double str = wd[1] * (windy(w) ? 1.6 : 1) * (w.isThundering() ? 1.3 : 1);
        return Math.cos(rel) * str;
    }

    double speedMult(Ship s) {
        double m = 1;
        if (s.type != ShipType.DINGHY) m *= 1 + cfg("wind.effect", 0.15) * windOn(s);
        if (s.boostUntil > pl.now) m *= s.boostMult;
        return m;
    }

    double turnMult(Ship s) {
        double m = s.type == ShipType.SLOOP ? 1.15 : 1;
        if (s.boostUntil > pl.now) m *= s.turnMult;
        return m;
    }

    double damageMult(Ship s) {
        double m = s.type == ShipType.GALLEON && s.ai == null ? 1 - cfg("galleon-armor", 0.15) : 1;
        if (s.smokeUntil > pl.now) m *= 0.5;
        return m;
    }

    static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    static String arrow(Player p, Location c) {
        double dx = c.getX() - p.getLocation().getX(), dz = c.getZ() - p.getLocation().getZ();
        double want = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((want - p.getLocation().getYaw()) % 360 + 540) % 360 - 180;
        return ARROWS[(int) Math.floorMod(Math.round(rel / 45.0), 8)];
    }

    /** The captain's action bar after the speed: wind, cruise, ability, the lookout. */
    Component captainBar(Ship s, Player cap) {
        Component c = Component.empty();
        if (on("wind") && s.type != ShipType.DINGHY) {
            double w = windOn(s);
            NamedTextColor col = w > 0.3 ? NamedTextColor.GREEN : w < -0.3 ? NamedTextColor.RED : NamedTextColor.GRAY;
            double[] wd = wind(s.world());
            Location toward = cap.getLocation().clone().add(Ship.fx((float) wd[0]) * 10, 0, Ship.fz((float) wd[0]) * 10);
            c = c.append(Component.text("   Wind " + arrow(cap, toward) + (w > 0.3 ? " with you" : w < -0.3 ? " against you" : " across"), col));
        }
        if (s.cruise) c = c.append(Component.text("   CRUISE", NamedTextColor.AQUA));
        long left = (s.abilityReady - pl.now + 19) / 20;
        c = c.append(left > 0 ? Component.text("   " + abilityName(s.type) + " " + left + "s", NamedTextColor.DARK_GRAY)
                : Component.text("   [F] " + abilityName(s.type), NamedTextColor.GOLD));
        if (s.upgrade("nest") > 0) {
            Ship o = nearestShip(s, 160);
            if (o != null) c = c.append(Component.text("   ⚑ " + (o.ai != null ? "skeleton ship" : o.title()) + " " + (int) o.center().distance(s.center()) + "m " + arrow(cap, o.center()),
                    o.ai != null ? NamedTextColor.RED : NamedTextColor.YELLOW));
        }
        return c;
    }

    Ship nearestShip(Ship s, double range) {
        Ship best = null;
        double bd = range * range;
        for (Ship o : pl.ships.values()) {
            if (o == s || !o.world.equals(s.world) || o.wrecked || !o.spawned() && o.ai != null) continue;
            double d = o.center().distanceSquared(s.center());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    // ============================================================================================ abilities
    static String abilityName(ShipType t) {
        return switch (t) {
            case DINGHY -> "Row Hard";
            case SLOOP -> "Tailwind";
            case BRIGANTINE -> "Smoke Screen";
            case GALLEON -> "Broadside";
            case PIRATE -> "Grapple";
        };
    }

    long cooldownTicks(ShipType t) {
        int sec = switch (t) { case DINGHY -> 30; case SLOOP -> 45; case BRIGANTINE -> 60; case GALLEON -> 40; case PIRATE -> 90; };
        return (long) (cfg("abilities." + t.name().toLowerCase() + ".cooldown-seconds", sec) * 20);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Ship s = pl.ridingOn(e.getPlayer());
        if (s == null || s.seatOf(e.getPlayer()) != 0) return;
        e.setCancelled(true);
        ability(e.getPlayer(), s);
    }

    /** The captain uses the ship's ability. */
    boolean ability(Player p, Ship s) {
        if (!on("abilities")) { p.sendActionBar(Component.text("Ship abilities are switched off.", NamedTextColor.GRAY)); return false; }
        if (s.ai != null || s.building || s.wrecked || s.anchored) { p.sendActionBar(Component.text("Get under way first.", NamedTextColor.YELLOW)); return false; }
        if (!pl.mayCaptain(p, s)) { p.sendActionBar(Component.text("Only the captain and crew can do that.", NamedTextColor.RED)); return false; }
        if (s.abilityReady > pl.now) { p.sendActionBar(Component.text(abilityName(s.type) + " is ready in " + (s.abilityReady - pl.now + 19) / 20 + "s.", NamedTextColor.YELLOW)); return false; }
        World w = s.world();
        Location c = s.center();
        boolean used = switch (s.type) {
            case DINGHY -> { boost(s, 1.7, 1.2, 6); w.playSound(c, Sound.ENTITY_BOAT_PADDLE_WATER, SoundCategory.PLAYERS, 2f, 0.8f); yield true; }
            case SLOOP -> {
                boost(s, 1.4, 1.6, 10);
                w.playSound(c, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1.5f, 0.8f);
                w.spawnParticle(Particle.GUST, c.clone().add(0, 4, 0), 6, 2, 2, 2, 0);
                yield true;
            }
            case BRIGANTINE -> {
                s.smokeUntil = pl.now + 12 * 20;
                w.playSound(c, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, SoundCategory.PLAYERS, 2f, 0.5f);
                yield true;
            }
            case GALLEON -> broadside(p, s);
            case PIRATE -> grapple(p, s);
        };
        if (!used) return false;
        s.abilityReady = pl.now + cooldownTicks(s.type);
        for (Player r : s.riders()) r.sendActionBar(Component.text(abilityName(s.type) + "!", NamedTextColor.GOLD));
        return true;
    }

    private void boost(Ship s, double speed, double turn, int seconds) {
        s.boostMult = speed; s.turnMult = turn;
        s.boostUntil = pl.now + seconds * 20L;
    }

    /** Every ready gun on the side the captain looks at fires (reload ignored), aimed where they look. */
    private boolean broadside(Player p, Ship s) {
        Vector look = p.getEyeLocation().getDirection();
        double[] right = Ship.toWorld(0, 0, s.yaw, 0, 1);
        int side = look.getX() * right[0] + look.getZ() * right[1] >= 0 ? 1 : -1;
        float elev = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, look.getY()))));
        int fired = 0;
        for (int i : s.cannons()) {
            if (s.sideOf(i) != side || s.blocks[i] == null) continue;
            s.cannonReady.remove(i);
            pl.layGun(s, i, 0, elev, 2);
            if (pl.fireGun(p, s, i, 1.05, 1)) fired++;
            else break; // out of cannonballs
        }
        if (fired == 0) { p.sendActionBar(Component.text("No guns on that side (or no Cannonballs).", NamedTextColor.YELLOW)); return false; }
        s.world().playSound(s.center(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3f, 0.6f);
        return true;
    }

    /** The nearest ship within 24 is held alongside; enemies near you are cowed. */
    private boolean grapple(Player p, Ship s) {
        Ship o = nearestShip(s, cfg("abilities.pirate.range", 24) + s.radius());
        if (o == null || o.wrecked) { p.sendActionBar(Component.text("No ship close enough to grapple.", NamedTextColor.YELLOW)); return false; }
        long until = pl.now + 15 * 20;
        s.grappledUntil = until; s.grappledTo = o;
        o.grappledUntil = until; o.grappledTo = s;
        o.cruise = false;
        World w = s.world();
        w.playSound(o.center(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 2f, 0.6f);
        w.playSound(s.center(), Sound.ITEM_GOAT_HORN_SOUND_0, SoundCategory.PLAYERS, 1.5f, 1.2f);
        for (Player e : w.getPlayers()) {
            if (e.getLocation().distanceSquared(s.center()) > 20 * 20 || crew(e, s)) continue;
            e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 0));
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0));
        }
        for (Player r : o.riders()) r.sendMessage(ChatColor.RED + "Grappled by the " + s.title() + "!");
        return true;
    }

    boolean crew(Player p, Ship s) { return s.seatOf(p) >= 0 || s.walkers.containsKey(p.getUniqueId()) || s.ai == null && pl.mayCaptain(p, s); }

    // ============================================================================================ every tick
    private int t;

    private void tick() {
        t++;
        if (t % 20 == 0) for (World w : Bukkit.getWorlds()) { // the wind veers
            double[] wd = wind.get(w.getName());
            if (wd == null) continue;
            wd[0] = (wd[0] + random.nextGaussian() * 1.5 + 360) % 360;
            wd[1] = Math.max(0.4, Math.min(1.0, wd[1] + random.nextGaussian() * 0.02));
        }
        for (Ship s : pl.ships.values()) {
            if (!s.spawned()) continue;
            World w = s.world();
            if (s.smokeUntil > pl.now && t % 4 == 0) { // the smoke screen
                Location c = s.center();
                double r = s.radius();
                w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 14, r * 0.6, 2, r * 0.6, 0.01);
                w.spawnParticle(Particle.LARGE_SMOKE, c, 20, r * 0.8, 1.5, r * 0.8, 0.02);
                if (t % 20 == 0) for (Player e : w.getPlayers())
                    if (!crew(e, s) && e.getLocation().distanceSquared(c) < (r + 10) * (r + 10))
                        e.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false));
            }
            if (s.grappledUntil > pl.now && s.grappledTo != null && t % 5 == 0 && s.id.compareTo(s.grappledTo.id) < 0) chain(s, s.grappledTo);
            if (s.grappledUntil <= pl.now) s.grappledTo = null;
            if (t % 20 == 0 && s.upgrade("net") > 0 && s.sailing() && Math.abs(s.speed) > 0.05 && on("net")) net(s);
        }
        if (t % 10 == 0 && !pointing.isEmpty()) for (UUID u : new ArrayList<>(pointing.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || pointing.get(u) < pl.now) { pointing.remove(u); continue; }
            Ship best = null; double bd = Double.MAX_VALUE;
            for (Ship s : pl.ships.values()) {
                if (!s.owner.equals(u) || !s.world.equals(p.getWorld().getName())) continue;
                double d = s.x - p.getLocation().getX(), dz = s.z - p.getLocation().getZ(), dd = d * d + dz * dz;
                if (dd < bd) { bd = dd; best = s; }
            }
            if (best == null) { pointing.remove(u); continue; }
            Location at = new Location(p.getWorld(), best.x, best.y, best.z);
            p.sendActionBar(Component.text("⚓ " + best.title() + "  " + arrow(p, at) + "  " + (int) Math.sqrt(bd) + " blocks", NamedTextColor.AQUA));
        }
    }

    private void chain(Ship a, Ship b) {
        if (b == null || !b.spawned() || !a.world.equals(b.world)) return;
        Location p = a.center(), q = b.center();
        Vector d = q.toVector().subtract(p.toVector());
        double len = d.length();
        if (len < 0.5) return;
        d.multiply(1 / len);
        for (double k = 0; k < len; k += 0.8)
            a.world().spawnParticle(Particle.DUST, p.clone().add(d.clone().multiply(k)), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(70, 70, 76), 1f));
    }

    // ============================================================================================ the fishing net
    private static final Material[] CATCH = {Material.COD, Material.COD, Material.COD, Material.SALMON, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH, Material.INK_SAC, Material.KELP};
    private static final Material[] TREASURE = {Material.NAUTILUS_SHELL, Material.NAME_TAG, Material.SADDLE, Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS, Material.EXPERIENCE_BOTTLE};

    private void net(Ship s) {
        s.netTimer++;
        if (s.netTimer < cfg("net.seconds", 30)) return;
        s.netTimer = 0;
        Inventory hold = s.hold();
        if (hold == null) return;
        Material m = random.nextDouble() < cfg("net.treasure-chance", 0.05) ? TREASURE[random.nextInt(TREASURE.length)] : CATCH[random.nextInt(CATCH.length)];
        var left = hold.addItem(new ItemStack(m, m == Material.EXPERIENCE_BOTTLE ? 2 : 1));
        Player cap = s.rider(0);
        if (!left.isEmpty()) { if (cap != null) cap.sendMessage(ChatColor.YELLOW + "The hold is full: the net can't take any more."); return; }
        s.world().spawnParticle(Particle.SPLASH, s.center().add(0, -1, 0), 20, s.type.halfWidth, 0.2, 2, 0.05);
        if (cap != null) cap.playSound(cap.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.PLAYERS, 0.7f, 1.2f);
    }

    // ============================================================================================ /ship locate, quits
    void locate(Player p) {
        List<Ship> mine = new ArrayList<>();
        for (Ship s : pl.ships.values()) if (s.owner.equals(p.getUniqueId())) mine.add(s);
        if (mine.isEmpty()) { p.sendMessage(ChatColor.GRAY + "You don't have a ship."); return; }
        p.sendMessage(ChatColor.AQUA + "Your ships:");
        for (Ship s : mine) {
            boolean here = s.world.equals(p.getWorld().getName());
            Location at = new Location(p.getWorld(), s.x, s.y, s.z);
            p.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + s.title() + ChatColor.GRAY + " (" + s.type.title + ") at " + (int) s.x + ", " + (int) s.z
                    + (here ? " (" + (int) Math.hypot(s.x - p.getLocation().getX(), s.z - p.getLocation().getZ()) + " blocks " + arrow(p, at) + ")" : " in " + s.world)
                    + (s.wrecked ? ChatColor.RED + " wrecked" : s.anchored ? " anchored" : " afloat") + ChatColor.DARK_GRAY + ", " + (int) s.distance + " blocks sailed");
        }
        pointing.put(p.getUniqueId(), pl.now + 30 * 20);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        for (Ship s : pl.ships.values()) s.near.remove(u); // a new login starts with nothing shown
        pointing.remove(u);
    }
}
