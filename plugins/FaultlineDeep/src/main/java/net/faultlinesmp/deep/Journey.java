package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * THE WAY DOWN (1.16.0: Lucki is gone). Every hour (`whirlpool.every-minutes`) a WHIRLPOOL opens somewhere in the
 * Overworld's oceans, 150-450 blocks from a random player (`min/max-distance`), under a light-blue beam in the sky. Its
 * coordinates go out in chat; it stays open `open-minutes` (10). Swim into its eye (or sail a boat in: you're thrown out
 * at the edge of the funnel) and the sea pulls you down into the Dark Ocean at 0, 300, 0. Admins open one any time for free:
 * `/whirlpool here|random|at <x> <z>|stop` (`/whirlpool` alone = where it is / when the next one comes, for anyone).
 * `whirlpool.needs-jacob` (false) = only players who beat Diamond Jacob may go down.
 *
 * The Mirror (FaultlineItems lost_mirror): on the surface it points you at the whirlpool; in the Dark Ocean (5 seconds)
 * it brings you back up (journey.kingdom if set, else your bed, else the world spawn).
 */
final class Journey implements Listener {

    final FaultlineDeep pl;
    final NamespacedKey mirrorKey = NamespacedKey.fromString("faultlineitems:lost_mirror");
    final Map<UUID, Long> channeling = new HashMap<>();
    private final Random random = new Random();
    private final File file;
    private final YamlConfiguration data;
    private int ticks;
    long nextDepths, nextRegular;
    boolean searching;
    final List<Pool> pools = new ArrayList<>();

    final class Pool {
        final Vortex v;
        final long endsAt;
        final boolean admin, depths;
        final Map<UUID, Long> sent = new HashMap<>(); // who went down, when (they can come back up and go again)
        final List<Chunk> tickets = new ArrayList<>();
        boolean warned;
        int errors;
        boolean logged;
        Pool(Vortex v, long endsAt, boolean admin, boolean depths) { this.v = v; this.endsAt = endsAt; this.admin = admin; this.depths = depths; }
    }

    Journey(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "whirlpool.yml");
        data = SafeYaml.load(pl, file).yaml;
        nextDepths = data.getLong("next-depths", 0);
        nextRegular = data.getLong("next-regular", 0);
        long now = System.currentTimeMillis();
        if (nextDepths <= 0) nextDepths = now + between(true);
        if (nextRegular <= 0) nextRegular = now + between(false);
        save();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 1L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("whirlpool." + k, def); }

    void save() {
        data.set("next-at", null);
        data.set("next-depths", nextDepths);
        data.set("next-regular", nextRegular);
        try { SafeYaml.save(pl, data, file); } catch (Exception e) { pl.getLogger().warning("[Whirlpool] couldn't save whirlpool.yml: " + e); }
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
    }

    Location entry() {
        World w = pl.world;
        return w == null ? null : new Location(w, 0.5, DeepGenerator.SEA, 0.5, 0, 20);
    }

    /** The world the whirlpools open in: whirlpool.world, else the first Overworld. */
    World surface() {
        World w = Bukkit.getWorld(pl.getConfig().getString("whirlpool.world", ""));
        if (w != null) return w;
        for (World x : Bukkit.getWorlds()) if (x.getEnvironment() == World.Environment.NORMAL && x != pl.world) return x;
        return null;
    }

    /** Where the Mirror brings you up: journey.kingdom if set, else your bed, else the surface world's spawn. */
    Location upTop(Player p) {
        Location k = place("kingdom");
        if (k != null) return k;
        Location bed = p.getRespawnLocation();
        if (bed != null && bed.getWorld() != null && !bed.getWorld().equals(pl.world)) return bed;
        World s = surface();
        return s == null ? null : s.getSpawnLocation().add(0.5, 0, 0.5);
    }

    // =========================================================================================== the Mirror
    boolean isMirror(ItemStack it) {
        return it != null && it.hasItemMeta() && mirrorKey != null && it.getPersistentDataContainer().has(mirrorKey, PersistentDataType.BYTE);
    }

    /** May this player go down? (whirlpool.needs-jacob: only after beating Diamond Jacob; asks the Index) */
    boolean earned(Player p) {
        if (!pl.getConfig().getBoolean("whirlpool.needs-jacob", false) || p.hasPermission("faultlinedeep.bypass")) return true;
        try {
            Class<?> c = Class.forName("net.faultlinesmp.index.FaultlineIndex", true, Bukkit.getPluginManager().getPlugin("FaultlineIndex").getClass().getClassLoader());
            var m = c.getMethod("defeated", UUID.class, String.class);
            return (boolean) m.invoke(null, p.getUniqueId(), pl.getConfig().getString("journey.needs.jacob", "diamond_jacob"));
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
        if (!inDeep) { // on the surface: a vague hint toward the Depths (the Whirlpool Radar finds it properly)
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 0.7f);
            Pool d = depths();
            if (d != null && d.v.center.getWorld().equals(p.getWorld()))
                p.sendMessage(ChatColor.LIGHT_PURPLE + "The Mirror swirls purple. " + ChatColor.GRAY + "The way down is open, somewhere " + away(p, d.v.center).replaceAll("^[0-9]+ blocks ", "to the ") + ".");
            else p.sendMessage(ChatColor.GRAY + "The Mirror shows a calm sea. The way down isn't open right now.");
            return;
        }
        Location up = upTop(p);
        if (up == null) { p.sendMessage(ChatColor.GRAY + "You look into the Mirror. It shows nothing."); return; }
        int secs = (int) pl.getConfig().getDouble("journey.return-seconds", 5);
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
                p.teleport(up);
                p.setFallDistance(0);
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
                p.showTitle(Title.title(Component.text("The Surface", NamedTextColor.GRAY), Component.text("The Mirror pulls you back up.", NamedTextColor.DARK_GRAY),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(800))));
                p.playSound(up, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
            }
        }, 1L, 1L);
    }

    // =========================================================================================== the whirlpools (1.19.0)
    /*
     * Two kinds, both silent (no announcement): REGULAR whirlpools (every whirlpool.regular.min..max-minutes, 15-45) just hurt,
     * spin you and spit you back out; THE DEPTHS (every whirlpool.depths.min..max-minutes, 10-20) is the only one whose eye
     * drops you into the Dark Ocean. Find them with the Whirlpool Radar (Radar.java) or by their beams (white = regular,
     * purple = the Depths).
     */
    void tick() {
        ticks++;
        for (Pool p : new ArrayList<>(pools)) {
            try { poolTick(p); p.errors = 0; }
            catch (Throwable ex) { // one bad tick doesn't close it; 100 in a row do
                if (!p.logged) { p.logged = true; pl.getLogger().log(java.util.logging.Level.WARNING, "[Whirlpool] tick failed", ex); }
                p.errors++;
                if (p.errors >= 100) close(p);
            }
        }
        if (ticks % 20 != 0 || searching || !pl.getConfig().getBoolean("whirlpool.enabled", true)) return;
        long now = System.currentTimeMillis();
        if (now >= nextDepths && depths() == null) {
            nextDepths = now + (long) (cfg("retry-minutes", 5) * 60000); save();
            startRandom(null, false, true);
        } else if (now >= nextRegular && regulars() < (int) cfg("regular.max-open", 3)) {
            nextRegular = now + (long) (cfg("retry-minutes", 5) * 60000); save();
            startRandom(null, false, false);
        }
    }

    Pool depths() { for (Pool p : pools) if (p.depths) return p; return null; }
    int regulars() { int n = 0; for (Pool p : pools) if (!p.depths) n++; return n; }

    long between(boolean depths) {
        String k = depths ? "depths." : "regular.";
        double a = cfg(k + "min-minutes", depths ? 10 : 15), b = cfg(k + "max-minutes", depths ? 20 : 45);
        return (long) ((a + random.nextDouble() * Math.max(0, b - a)) * 60000);
    }

    long minutesTo(long at) { return Math.max(0, (at - System.currentTimeMillis() + 59999) / 60000); }

    /** Near a random survival player in the surface world, in an ocean. */
    void startRandom(CommandSender by, boolean admin, boolean depths) {
        World w = surface();
        if (w == null) { if (by != null) by.sendMessage(ChatColor.RED + "No Overworld to open it in."); return; }
        List<Player> who = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) who.add(p);
        if (who.isEmpty() && by instanceof Player self && self.getWorld().equals(w)) who.add(self);
        if (who.isEmpty()) { if (by != null) by.sendMessage(ChatColor.RED + "Nobody in " + w.getName() + " to open it near."); return; }
        Player near = who.get(random.nextInt(who.size()));
        searching = true;
        if (by != null) by.sendMessage(ChatColor.GRAY + "Looking for open ocean near " + near.getName() + "...");
        tryAt(w, near.getLocation(), 0, by, admin, depths);
    }

    private void tryAt(World w, Location around, int attempt, CommandSender by, boolean admin, boolean depths) {
        int tries = (int) cfg("search-tries", 16);
        if (attempt >= tries) {
            searching = false;
            if (by != null) by.sendMessage(ChatColor.RED + "Couldn't find open ocean there (" + tries + " tries). Try /whirlpool here in the sea.");
            return;
        }
        double a = random.nextDouble() * Math.PI * 2, d = cfg("min-distance", 150) + random.nextDouble() * Math.max(0, cfg("max-distance", 450) - cfg("min-distance", 150));
        int x = (int) (around.getX() + Math.cos(a) * d), z = (int) (around.getZ() + Math.sin(a) * d);
        int cx = x >> 4, cz = z >> 4;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) loads.add(chunkAsync(w, cx + dx, cz + dz));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(pl, () -> {
            Location spot = err == null ? openSea(w, x, z) : null;
            if (spot != null) for (Pool o : pools) if (o.v.center.getWorld().equals(w) && o.v.center.distanceSquared(spot) < 48 * 48) spot = null; // not on top of another
            if (spot == null) { tryAt(w, around, attempt + 1, by, admin, depths); return; }
            searching = false;
            Pool p = open(spot, admin, depths);
            if (p != null && by != null) by.sendMessage(ChatColor.GREEN + (depths ? "The Depths whirlpool" : "A whirlpool") + " opened at " + spot.getBlockX() + ", " + spot.getBlockZ() + ".");
            if (!admin) {
                if (depths) nextDepths = System.currentTimeMillis() + between(true);
                else nextRegular = System.currentTimeMillis() + between(false);
                save();
            }
        }));
    }

    /** Loads (or generates) a chunk in the background; a server without async chunks loads it now. */
    static CompletableFuture<Chunk> chunkAsync(World w, int cx, int cz) {
        try { return w.getChunkAtAsync(cx, cz, true); }
        catch (RuntimeException e) { return CompletableFuture.completedFuture(w.getChunkAt(cx, cz)); }
    }

    /** Open sea here? An ocean biome, the sea level block water, deep enough, and water (no land, no ice) all round the rim. */
    Location openSea(World w, int x, int z) {
        int y = w.getSeaLevel() - 1;
        String biome = w.getBlockAt(x, y, z).getBiome().getKey().getKey();
        if (!biome.contains("ocean")) return null;
        if (!Vortex.water(w.getBlockAt(x, y, z)) || !w.getBlockAt(x, y + 1, z).isEmpty()) return null;
        int deep = 0;
        while (deep < 40 && Vortex.water(w.getBlockAt(x, y - deep - 1, z))) deep++;
        if (deep < cfg("min-depth", 10)) return null;
        double r = cfg("radius", 11) + 3;
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            int bx = (int) Math.floor(x + Math.cos(a) * r), bz = (int) Math.floor(z + Math.sin(a) * r);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
            if (!Vortex.water(w.getBlockAt(bx, y, bz)) || !w.getBlockAt(bx, y + 1, bz).isEmpty()) return null;
        }
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    /** Opens one here (water under it). Null = no water. Only one Depths whirlpool at a time. */
    Pool open(Location spot, boolean admin, boolean depths) {
        if (!Vortex.water(spot.getBlock()) && !Vortex.water(spot.getBlock().getRelative(0, -1, 0))) return null;
        if (depths) { Pool old = depths(); if (old != null) close(old); }
        double radius = depths ? cfg("radius", 11) : cfg("regular.radius", 8), depth = depths ? cfg("depth", 18) : cfg("regular.depth", 12);
        Vortex v = new Vortex(spot, radius, depth, (int) cfg("debris", 14), cfg("beam-height", 160), depths);
        double mins = depths ? cfg("open-minutes", 10) : cfg("regular.open-minutes", 10);
        Pool p = new Pool(v, System.currentTimeMillis() + (long) (mins * 60000), admin, depths);
        World w = spot.getWorld();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Chunk c = w.getChunkAt((spot.getBlockX() >> 4) + dx, (spot.getBlockZ() >> 4) + dz);
            try { if (c.addPluginChunkTicket(pl)) p.tickets.add(c); } catch (Throwable ignored) { } // keeps it loaded while it's open
        }
        pools.add(p);
        pl.getLogger().info("[Whirlpool] " + (depths ? "the Depths" : "a regular one") + " opened at " + w.getName() + " " + v.center.getBlockX() + " " + v.center.getBlockZ() + (admin ? " (admin)" : ""));
        return p;
    }

    static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** "312 blocks NE" for this player. */
    static String away(Player p, Location c) {
        double dx = c.getX() - p.getLocation().getX(), dz = c.getZ() - p.getLocation().getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"}; // atan2 of (dz, dx) measured like Minecraft yaw
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // 0 = south (+z), 90 = west
        int i = (int) Math.floorMod(Math.round(yaw / 45.0), 8);
        return dist + " blocks " + names[i];
    }

    /** An arrow from where p looks to c. */
    static String arrow(Player p, Location c) {
        double dx = c.getX() - p.getLocation().getX(), dz = c.getZ() - p.getLocation().getZ();
        double want = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((want - p.getLocation().getYaw()) % 360 + 540) % 360 - 180; // -180..180, 0 = straight ahead
        return ARROWS[(int) Math.floorMod(Math.round(rel / 45.0), 8)];
    }

    /** Players get a hint; admins (faultlinedeep.whirlpool) get every open one and the timers. */
    List<String> status(CommandSender s) {
        List<String> out = new ArrayList<>();
        if (!pl.getConfig().getBoolean("whirlpool.enabled", true)) { out.add(ChatColor.GRAY + "The whirlpools are switched off."); return out; }
        Player p = s instanceof Player x ? x : null;
        if (s == null || !s.hasPermission("faultlinedeep.whirlpool")) {
            out.add(ChatColor.AQUA + "Whirlpools open and close out in the ocean all the time. " + ChatColor.GRAY + "Most just tear you up;");
            out.add(ChatColor.GRAY + "one of them, " + ChatColor.LIGHT_PURPLE + "the Depths" + ChatColor.GRAY + " (purple beam), leads down to the Dark Ocean. Craft a "
                    + ChatColor.WHITE + "Whirlpool Radar" + ChatColor.GRAY + " to find them.");
            return out;
        }
        if (pools.isEmpty()) out.add(ChatColor.GRAY + "No whirlpool is open.");
        for (Pool c : pools) {
            Location at = c.v.center;
            long left = Math.max(0, (c.endsAt - System.currentTimeMillis()) / 60000);
            out.add((c.depths ? ChatColor.LIGHT_PURPLE + "The Depths" : ChatColor.AQUA + "Regular") + ChatColor.GRAY + " at " + ChatColor.WHITE + at.getBlockX() + ", " + at.getBlockZ()
                    + ChatColor.GRAY + " (" + at.getWorld().getName() + (p != null && p.getWorld().equals(at.getWorld()) ? ", " + away(p, at) : "") + "), closing in ~" + (left + 1) + " min.");
        }
        out.add(ChatColor.GRAY + "Next: the Depths in " + minutesTo(nextDepths) + " min" + (depths() != null ? " (after this one)" : "") + ", a regular one in " + minutesTo(nextRegular) + " min.");
        return out;
    }

    private void poolTick(Pool p) {
        Vortex v = p.v;
        long now = System.currentTimeMillis();
        if (!v.closing && now >= p.endsAt) {
            v.closing = true;
            for (Player o : v.center.getWorld().getPlayers()) if (o.getLocation().distanceSquared(v.center) < 48 * 48)
                o.sendActionBar(Component.text("The whirlpool is closing...", NamedTextColor.AQUA));
        }
        v.tick();
        pull(p);
        if (v.closed()) close(p);
    }

    void close(Pool p) {
        if (!pools.remove(p)) return;
        p.v.clear();
        for (Chunk c : p.tickets) try { c.removePluginChunkTicket(pl); } catch (Throwable ignored) { }
    }

    void closeAll() { for (Pool p : new ArrayList<>(pools)) close(p); }

    /** The open whirlpools (for the radar). */
    List<Pool> open() { return pools; }

    /**
     * Anyone near it is dragged round and in. In THE DEPTHS they slide down the walls into the eye, and the eye sends them down.
     * A REGULAR one hurts (whirlpool.regular.damage every second inside it) and its eye spits you out, up and away
     * (regular.spit-damage). A boat is dragged in with its rider and throws them out at the edge of the funnel.
     */
    void pull(Pool pl0) {
        Vortex v = pl0.v;
        if (v.r < 1) return;
        long now = System.currentTimeMillis();
        for (Player p : v.center.getWorld().getPlayers()) {
            long cool = pl0.depths ? 10000 : 2500;
            if (now - pl0.sent.getOrDefault(p.getUniqueId(), 0L) < cool || p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            double dx = v.center.getX() - l.getX(), dz = v.center.getZ() - l.getZ(), d = Math.hypot(dx, dz);
            if (d > v.r + 4 || l.getY() > v.surf + 5 || l.getY() < v.surf - v.d - 4) continue;
            Vector in = d < 0.05 ? new Vector() : new Vector(dx, 0, dz).normalize(), round = d < 0.05 ? new Vector() : new Vector(-dz, 0, dx).normalize();
            Entity ride = p.getVehicle();
            if (ride != null) {
                if (!(ride instanceof Boat)) continue;
                if (v.inside(ride.getLocation()) || d < v.r - 1) { p.leaveVehicle(); }
                else { Edition.vel(ride, in.clone().multiply(0.25).add(round.clone().multiply(0.3)).setY(0)); continue; }
            }
            if (pl0.depths && !earned(p)) {
                if (ticks % 20 == 0) { Edition.vel(p, in.clone().multiply(-0.9).setY(0.5)); p.sendActionBar(Component.text("The water won't take you. (Beat Diamond Jacob.)", NamedTextColor.GRAY)); }
                continue;
            }
            boolean inFunnel = v.inside(l);
            if (inFunnel) Edition.vel(p, in.clone().multiply(0.14).add(round.clone().multiply(pl0.depths ? 0.22 : 0.3)).setY(-0.45)); // down the wall
            else Edition.vel(p, in.clone().multiply(0.2).add(round.clone().multiply(0.25)).setY(-0.04));                               // in the water at the rim
            if (!pl0.depths && (inFunnel || d < v.r) && p.getGameMode() != GameMode.CREATIVE) {
                if (ticks % 20 == 0) {
                    p.damage(cfg("regular.damage", 2));
                    p.getWorld().spawnParticle(Particle.SPLASH, l.clone().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
                    p.sendActionBar(Component.text("The whirlpool is tearing you apart!", NamedTextColor.RED));
                }
            }
            if (v.atEye(l)) {
                pl0.sent.put(p.getUniqueId(), now);
                if (pl0.depths) send(p);
                else spit(p, in);
            }
        }
    }

    /** A regular whirlpool's eye throws you back up and out. */
    void spit(Player p, Vector in) {
        Vector out = in.lengthSquared() < 0.01 ? new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize() : in.clone().multiply(-1);
        if (p.getGameMode() != GameMode.CREATIVE) p.damage(cfg("regular.spit-damage", 4));
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) Edition.vel(p, out.multiply(1.3).setY(1.35)); });
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.6f);
        p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation(), 60, 0.6, 1.2, 0.6, 0.3);
        p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation(), 30, 0.5, 0.8, 0.5, 0.1);
        p.sendActionBar(Component.text("Spat back out. This isn't the way down.", NamedTextColor.GRAY));
    }

    /** Down into the Dark Ocean. */
    void send(Player p) {
        Location to = entry();
        if (to == null) { p.sendMessage(ChatColor.GRAY + "The sea below isn't ready. (The Dark Ocean world isn't loaded.)"); return; }
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.5f);
        p.playSound(p.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.5f, 0.5f);
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

    // =========================================================================================== /whirlpool
    boolean command(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        boolean admin = s.hasPermission("faultlinedeep.whirlpool");
        Player self = s instanceof Player p ? p : null;
        if (sub.isEmpty() || sub.equals("where") || !admin) {
            for (String line : status(s)) s.sendMessage(line);
            if (admin && sub.isEmpty()) s.sendMessage(ChatColor.YELLOW + USAGE);
            return true;
        }
        boolean depthsKind = !java.util.Arrays.asList(a).contains("regular"); // admin opens: the Depths unless "regular" is given
        switch (sub) {
            case "here" -> {
                if (self == null) { s.sendMessage("Players only."); return true; }
                if (open(self.getLocation().getBlock().getLocation().add(0.5, 0, 0.5), true, depthsKind) == null) s.sendMessage(ChatColor.RED + "Stand in the sea (water under you).");
                else s.sendMessage(ChatColor.GREEN + (depthsKind ? "The Depths whirlpool" : "A regular whirlpool") + " opened here.");
            }
            case "random" -> startRandom(s, true, depthsKind);
            case "at" -> {
                if (a.length < 3) { s.sendMessage(ChatColor.YELLOW + "/whirlpool at <x> <z> [world] [depths|regular]"); return true; }
                World w = a.length > 3 && Bukkit.getWorld(a[3]) != null ? Bukkit.getWorld(a[3]) : self != null ? self.getWorld() : surface();
                int x, z;
                try { x = Integer.parseInt(a[1]); z = Integer.parseInt(a[2]); } catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Numbers, please."); return true; }
                if (w == null) { s.sendMessage(ChatColor.RED + "No such world."); return true; }
                chunkAsync(w, x >> 4, z >> 4).thenAccept(c -> Bukkit.getScheduler().runTask(pl, () -> {
                    Location at = new Location(w, x + 0.5, w.getSeaLevel() - 1, z + 0.5);
                    if (open(at, true, depthsKind) == null) s.sendMessage(ChatColor.RED + "There's no sea at " + x + ", " + z + ".");
                    else s.sendMessage(ChatColor.GREEN + "Opened at " + x + ", " + z + ".");
                }));
            }
            case "stop" -> {
                if (pools.isEmpty()) { s.sendMessage(ChatColor.GRAY + "None is open."); return true; }
                for (Pool p : pools) p.v.closing = true;
                s.sendMessage(ChatColor.GREEN + "Closing " + pools.size() + ".");
            }
            case "next" -> {
                double m = 0;
                try { m = Double.parseDouble(a.length > 1 ? a[1] : "0"); } catch (NumberFormatException ignored) { }
                long at = System.currentTimeMillis() + (long) (m * 60000);
                if (depthsKind) nextDepths = at; else nextRegular = at;
                save();
                s.sendMessage(ChatColor.GREEN + "The next " + (depthsKind ? "Depths" : "regular") + " whirlpool opens in " + (int) m + " min.");
            }
            default -> s.sendMessage(ChatColor.YELLOW + USAGE);
        }
        return true;
    }

    static final String USAGE = "/whirlpool [where|here|random|at <x> <z> [world]|stop|next <minutes>] [depths|regular]";

    List<String> tab(String[] a) {
        if (a.length == 1) return List.of("where", "here", "random", "at", "stop", "next").stream().filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        if (a.length >= 2) return List.of("depths", "regular").stream().filter(x -> x.startsWith(a[a.length - 1].toLowerCase())).toList();
        return List.of();
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        for (Entity en : e.getChunk().getEntities()) if (en.getScoreboardTags().contains("faultline_lucki") || en.getScoreboardTags().contains("faultline_lucki_boat")
                || (en.getScoreboardTags().contains(Vortex.TAG) && pools.isEmpty())) en.remove(); // leftovers (Lucki is gone since 1.16.0)
    }

    void shutdown() { closeAll(); save(); }
}
