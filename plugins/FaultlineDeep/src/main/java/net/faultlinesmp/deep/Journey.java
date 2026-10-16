package net.faultlinesmp.deep;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
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
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE WAY DOWN: the Kraken's Mirror (FaultlineItems lost_mirror), the abandoned kingdom, Lucki and the whirlpool.
 *
 *  1. Right-click the Mirror (needs Rocco Vendetta AND Diamond Jacob beaten: the Index's kill records): after 3 seconds of
 *     the glass clouding over you're in the abandoned kingdom where the Black Market stands (journey.kingdom, set by an
 *     admin with /deep set kingdom).
 *  2. Lucki waits at the dock in his little boat (journey.lucki, /deep set lucki): a fisherman (a villager that doesn't
 *     move or take damage). Talk to him (right-click): a few lines, and you climb in.
 *  3. He rows you out toward the whirlpool spot (journey.whirlpool; by default 0, sea level, 0 in his world): a short sail,
 *     the screen fades, and you're there. He holds up one finger, and a great WHIRLPOOL opens around the boat.
 *  4. Drop into it (he lets you out; or step off yourself) and the sea pulls you down: you come out in the Dark Ocean at
 *     0, 100, 0. It stays open 40 seconds for anyone else who's earned the way; then Lucki rows home.
 *  Coming back: right-click the Mirror in the Dark Ocean (5 seconds) = back at the kingdom.
 */
final class Journey implements Listener {

    static final String LUCKI_TAG = "faultline_lucki", BOAT_TAG = "faultline_lucki_boat";

    final FaultlineDeep pl;
    final NamespacedKey mirrorKey = NamespacedKey.fromString("faultlineitems:lost_mirror");
    Villager lucki;
    Boat boat;
    /** The trip in progress (one at a time: he has one boat). */
    Trip trip;
    final Map<UUID, Long> channeling = new HashMap<>();
    private final Map<UUID, Long> talkCd = new HashMap<>();
    private int ticks;

    final class Trip {
        final Player rider;
        int t, phase; // 0 talking, 1 rowing, 2 fading, 3 the whirlpool, 4 rowing home
        Location pool;
        final Set<UUID> sent = new HashSet<>();
        Trip(Player rider) { this.rider = rider; }
    }

    Journey(FaultlineDeep pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    // =========================================================================================== places
    Location place(String key) {
        String b = "journey." + key + ".";
        if (!pl.getConfig().isConfigurationSection("journey." + key)) return null;
        World w = Bukkit.getWorld(pl.getConfig().getString(b + "world", ""));
        if (w == null) return null;
        return new Location(w, pl.getConfig().getDouble(b + "x"), pl.getConfig().getDouble(b + "y"), pl.getConfig().getDouble(b + "z"),
                (float) pl.getConfig().getDouble(b + "yaw"), 0);
    }

    void setPlace(String key, Location l) {
        String b = "journey." + key + ".";
        pl.getConfig().set(b + "world", l.getWorld().getName());
        pl.getConfig().set(b + "x", Math.round(l.getX() * 10) / 10.0);
        pl.getConfig().set(b + "y", Math.round(l.getY() * 10) / 10.0);
        pl.getConfig().set(b + "z", Math.round(l.getZ() * 10) / 10.0);
        pl.getConfig().set(b + "yaw", Math.round(l.getYaw()));
        pl.saveConfig();
        if (key.equals("lucki")) { removeLucki(); spawnLucki(); }
    }

    /** Where the whirlpool opens: journey.whirlpool, else 0, sea level, 0 in Lucki's world. */
    Location poolSpot() {
        Location p = place("whirlpool");
        if (p != null) return p;
        Location l = place("lucki");
        if (l == null) return null;
        return new Location(l.getWorld(), 0.5, l.getWorld().getSeaLevel() - 1, 0.5);
    }

    Location entry() {
        World w = pl.world;
        return w == null ? null : new Location(w, 0.5, DeepGenerator.SEA, 0.5, 0, 20);
    }

    // =========================================================================================== the Mirror
    boolean isMirror(ItemStack it) {
        return it != null && it.hasItemMeta() && mirrorKey != null && it.getItemMeta().getPersistentDataContainer().has(mirrorKey, PersistentDataType.BYTE);
    }

    /** Has this player beaten both Rocco Vendetta and Diamond Jacob? (asks the Index; without the Index: yes) */
    boolean earned(Player p) {
        if (p.hasPermission("faultlinedeep.bypass")) return true;
        try {
            Class<?> c = Class.forName("net.faultlinesmp.index.FaultlineIndex", true, Bukkit.getPluginManager().getPlugin("FaultlineIndex").getClass().getClassLoader());
            var m = c.getMethod("defeated", UUID.class, String.class);
            boolean rocco = (boolean) m.invoke(null, p.getUniqueId(), pl.getConfig().getString("journey.needs.rocco", "rocco_vendetta"));
            boolean jacob = (boolean) m.invoke(null, p.getUniqueId(), pl.getConfig().getString("journey.needs.jacob", "diamond_jacob"));
            return rocco && jacob;
        } catch (Throwable e) {
            return true;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMirror(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isMirror(p.getInventory().getItemInMainHand())) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);
        if (channeling.containsKey(p.getUniqueId())) return;
        boolean inDeep = pl.world != null && p.getWorld().equals(pl.world);
        Location kingdom = place("kingdom");
        if (kingdom == null) { p.sendMessage(ChatColor.GRAY + "You look into the Mirror. It shows nothing... yet. " + ChatColor.DARK_GRAY + "(An admin sets the kingdom with /deep set kingdom.)"); return; }
        if (!inDeep && !earned(p)) {
            p.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.ITALIC + "You look into the Mirror. Two faces look back, and they don't let you through.");
            p.sendMessage(ChatColor.GRAY + "Beat " + ChatColor.WHITE + "Rocco Vendetta" + ChatColor.GRAY + " and " + ChatColor.WHITE + "Diamond Jacob" + ChatColor.GRAY + " first.");
            p.playSound(p.getLocation(), Sound.BLOCK_GLASS_HIT, 1f, 0.6f);
            return;
        }
        int secs = inDeep ? (int) pl.getConfig().getDouble("journey.return-seconds", 5) : (int) pl.getConfig().getDouble("journey.mirror-seconds", 3);
        channeling.put(p.getUniqueId(), System.currentTimeMillis());
        Location start = p.getLocation();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (!p.isOnline() || p.isDead() || p.getLocation().getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 9) {
                task.cancel(); channeling.remove(p.getUniqueId());
                if (p.isOnline()) p.sendActionBar(Component.text("The Mirror clears. (You moved.)", NamedTextColor.GRAY));
                return;
            }
            t[0]++;
            p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 12, 0.4, 0.8, 0.4, 0.05);
            if (t[0] % 20 == 0) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 0.6f + t[0] / 100f);
            p.sendActionBar(Component.text("The Mirror clouds over... " + Math.max(0, secs - t[0] / 20) + "s", NamedTextColor.AQUA));
            if (t[0] >= secs * 20) {
                task.cancel(); channeling.remove(p.getUniqueId());
                p.teleport(kingdom);
                p.setFallDistance(0);
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
                p.showTitle(Title.title(Component.text(inDeep ? "The Surface" : "The Abandoned Kingdom", NamedTextColor.GRAY),
                        Component.text(inDeep ? "The Mirror pulls you back up." : "Find Lucki at the docks.", NamedTextColor.DARK_GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(800))));
                p.playSound(kingdom, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
            }
        }, 1L, 1L);
    }

    // =========================================================================================== Lucki
    void spawnLucki() {
        Location l = place("lucki");
        if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        if (lucki != null && lucki.isValid() && boat != null && boat.isValid()) return;
        removeLucki();
        World w = l.getWorld();
        boat = (Boat) w.spawnEntity(l, EntityType.OAK_BOAT, false);
        boat.addScoreboardTag(BOAT_TAG);
        boat.setPersistent(false);
        boat.setInvulnerable(true);
        lucki = (Villager) w.spawnEntity(l, EntityType.VILLAGER, false);
        lucki.addScoreboardTag(LUCKI_TAG);
        lucki.setPersistent(false);
        lucki.setInvulnerable(true);
        lucki.setAI(false);
        lucki.setSilent(false);
        lucki.setProfession(Villager.Profession.FISHERMAN);
        lucki.setCustomName(ChatColor.AQUA + "" + ChatColor.BOLD + "Lucki");
        lucki.setCustomNameVisible(true);
        lucki.setRemoveWhenFarAway(false);
        boat.addPassenger(lucki);
    }

    void removeLucki() {
        if (lucki != null && lucki.isValid()) lucki.remove();
        if (boat != null && boat.isValid()) boat.remove();
        lucki = null; boat = null;
    }

    boolean isLucki(Entity e) { return e.getScoreboardTags().contains(LUCKI_TAG) || e.getScoreboardTags().contains(BOAT_TAG); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTalk(PlayerInteractEntityEvent e) {
        if (!isLucki(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now < talkCd.getOrDefault(p.getUniqueId(), 0L)) return;
        talkCd.put(p.getUniqueId(), now + 3000);
        if (trip != null) { say(p, trip.rider == p ? "Hold tight." : "One at a time, friend. Wait for me to come back. Or swim after us."); return; }
        if (!earned(p)) {
            say(p, "Who sent you? No... you haven't earned the way. Rocco and Jacob still stand. Come back when they don't.");
            return;
        }
        if (pl.world == null) { say(p, "The sea's not ready for you yet. (The Dark Ocean world isn't loaded.)"); return; }
        Location spot = poolSpot();
        if (spot == null) { say(p, "I don't know where to take you. (An admin sets the spot with /deep set whirlpool.)"); return; }
        startTrip(p);
    }

    void say(Player p, String line) {
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Lucki" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + line);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 1f, 1.2f);
    }

    static final List<String> HELLO = List.of(
            "Oh! Another one who looked into that Mirror.",
            "You want to go down there? Into the Dark Ocean? Ha. Nobody comes back the same.",
            "Down there, if you drown, the fish get your things in a minute. Remember that.",
            "Get in. Hold on to something.");

    void startTrip(Player p) {
        trip = new Trip(p);
        trip.pool = poolSpot();
    }

    void tick() {
        ticks++;
        if (ticks % 100 == 0) spawnLucki();
        Trip tr = trip;
        if (tr == null) return;
        tr.t++;
        Player p = tr.rider;
        if (!p.isOnline() || p.isDead()) { endTrip(); return; }
        switch (tr.phase) {
            case 0 -> { // talking
                int i = tr.t / 40;
                if (tr.t % 40 == 1 && i < HELLO.size()) say(p, HELLO.get(i));
                if (tr.t >= HELLO.size() * 40) {
                    if (boat == null || !boat.isValid()) { endTrip(); return; }
                    boat.addPassenger(p);
                    tr.phase = 1; tr.t = 0;
                }
            }
            case 1 -> { // rowing out
                if (boat == null || !boat.isValid()) { endTrip(); return; }
                Vector to = tr.pool.toVector().subtract(boat.getLocation().toVector()).setY(0);
                double d = to.length();
                if (d > 1) Edition.vel(boat, to.normalize().multiply(0.35));
                if (tr.t % 30 == 0) boat.getWorld().playSound(boat.getLocation(), Sound.ENTITY_BOAT_PADDLE_WATER, 1f, 1f);
                if (d < 6 || tr.t > pl.getConfig().getDouble("journey.row-seconds", 6) * 20) { // a fade: the rest of the way happens off screen
                    tr.phase = 2; tr.t = 0;
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false, false));
                    p.showTitle(Title.title(Component.empty(), Component.text("Lucki rows on into the fog...", NamedTextColor.GRAY),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1500), Duration.ofMillis(500))));
                }
            }
            case 2 -> {
                if (tr.t == 20) {
                    Location at = tr.pool.clone().add(-4, 0, 0);
                    at.setY(tr.pool.getY() + 1);
                    at.setYaw(-90);
                    boat.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
                }
                if (tr.t == 50) {
                    say(p, "Here. Right here. Watch.");
                    lucki.swingMainHand();
                    p.getWorld().spawnParticle(Particle.END_ROD, lucki.getEyeLocation().add(0, 0.6, 0), 20, 0.05, 0.3, 0.05, 0.02);
                    p.getWorld().playSound(tr.pool, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.AMBIENT, 1.5f, 0.5f);
                }
                if (tr.t >= 70) { tr.phase = 3; tr.t = 0; p.getWorld().playSound(tr.pool, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.AMBIENT, 1f, 0.5f); }
            }
            case 3 -> { // the whirlpool
                whirlpool(tr);
                if (tr.t == 40) say(p, "Go on. Jump. And don't look up.");
                if (tr.t == 80 && p.getVehicle() == boat) { p.leaveVehicle(); Edition.vel(p, tr.pool.toVector().subtract(p.getLocation().toVector()).setY(0).normalize().multiply(0.6).setY(0.2)); }
                pull(tr);
                if (tr.t > pl.getConfig().getDouble("journey.whirlpool-seconds", 40) * 20) { tr.phase = 4; tr.t = 0; }
            }
            case 4 -> { // home again
                Location home = place("lucki");
                if (home != null && boat != null && boat.isValid()) boat.teleport(home, TeleportFlag.EntityState.RETAIN_PASSENGERS);
                endTrip();
            }
            default -> endTrip();
        }
    }

    /** The whirlpool itself: rings of spray and bubbles spiralling in, and the roar of the water. */
    void whirlpool(Trip tr) {
        World w = tr.pool.getWorld();
        double r0 = pl.getConfig().getDouble("journey.whirlpool-radius", 7);
        double spin = tr.t * 0.18;
        for (int ring = 0; ring < 5; ring++) {
            double r = r0 * (1 - ring / 5.0) + 0.6;
            int n = 10 + ring * 2;
            for (int i = 0; i < n; i++) {
                double a = spin * (1 + ring * 0.35) + i * Math.PI * 2 / n;
                Location l = tr.pool.clone().add(Math.cos(a) * r, 0.9 - ring * 0.25, Math.sin(a) * r);
                w.spawnParticle(ring % 2 == 0 ? Particle.SPLASH : Particle.BUBBLE_POP, l, 2, 0.1, 0.05, 0.1, 0.02);
            }
        }
        w.spawnParticle(Particle.DUST, tr.pool.clone().add(0, 0.5, 0), 12, 1.2, 0.2, 1.2, 0, new Particle.DustOptions(Color.fromRGB(10, 20, 50), 2.2f));
        if (tr.t % 20 == 0) w.playSound(tr.pool, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.AMBIENT, 2f, 0.5f);
        if (tr.t % 50 == 0) w.playSound(tr.pool, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE, SoundCategory.AMBIENT, 2f, 0.6f);
    }

    /** Anyone in the water near the middle is pulled in and down: earned = into the Dark Ocean, not = thrown back out. */
    void pull(Trip tr) {
        double r0 = pl.getConfig().getDouble("journey.whirlpool-radius", 7);
        for (Player p : tr.pool.getWorld().getPlayers()) {
            if (p.isInsideVehicle()) continue;
            Location l = p.getLocation();
            double dx = tr.pool.getX() - l.getX(), dz = tr.pool.getZ() - l.getZ(), d = Math.hypot(dx, dz);
            if (d > r0 || Math.abs(l.getY() - tr.pool.getY()) > 6) continue;
            if (!earned(p)) {
                if (tr.t % 20 == 0) { Edition.vel(p, new Vector(-dx, 0, -dz).normalize().multiply(0.9).setY(0.4)); p.sendActionBar(Component.text("The water won't take you. (Beat Rocco and Jacob.)", NamedTextColor.GRAY)); }
                continue;
            }
            // spin round and in
            Vector in = new Vector(dx, 0, dz).normalize().multiply(0.18), round = new Vector(-dz, 0, dx).normalize().multiply(0.22);
            Edition.vel(p, in.add(round).setY(-0.12));
            if (d < 2.2 && tr.sent.add(p.getUniqueId())) send(p);
        }
    }

    /** Down into the Dark Ocean. */
    void send(Player p) {
        Location to = entry();
        if (to == null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.5f);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            p.teleport(to);
            p.setFallDistance(0);
            p.setRemainingAir(p.getMaximumAir());
            p.showTitle(Title.title(Component.text("THE DARK OCEAN", NamedTextColor.DARK_AQUA),
                    Component.text("Swim. Explore. Don't drown.", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(600), Duration.ofMillis(3500), Duration.ofMillis(1200))));
            p.playSound(to, Sound.AMBIENT_UNDERWATER_ENTER, 1.2f, 0.6f);
        }, 15L);
    }

    void endTrip() {
        trip = null;
        if (boat != null && boat.isValid()) for (Entity e : boat.getPassengers()) if (e instanceof Player p) p.leaveVehicle();
    }

    // ---- Lucki's boat can't be broken, and Lucki can't be hurt or left behind
    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) { if (isLucki(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBoatHit(VehicleDamageEvent e) { if (isLucki(e.getVehicle())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBoatBreak(VehicleDestroyEvent e) { if (isLucki(e.getVehicle())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onExit(VehicleExitEvent e) {
        if (!isLucki(e.getVehicle())) return;
        if (e.getExited() == lucki) { e.setCancelled(true); return; }
        // the rider can't hop off while rowing out (they'd be left in the middle of the sea)
        if (trip != null && e.getExited() == trip.rider && trip.phase < 3) e.setCancelled(true);
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        Location l = place("lucki");
        if (l != null && l.getWorld().equals(e.getWorld()) && (l.getBlockX() >> 4) == e.getChunk().getX() && (l.getBlockZ() >> 4) == e.getChunk().getZ())
            Bukkit.getScheduler().runTask(pl, this::spawnLucki);
        for (Entity en : e.getChunk().getEntities()) if (isLucki(en) && en != lucki && en != boat) en.remove(); // leftovers
    }

    void shutdown() { removeLucki(); }
}
