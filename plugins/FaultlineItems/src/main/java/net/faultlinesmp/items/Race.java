package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ELYTRA RACES (Items 1.2.17, config race:, courses + best times in races.yml). Admins build a course and host it:
 *   /race create <name> [mode]     start line = where you stand (and the way you face); you're now editing it.
 *                                  mode: elytra (default), boat (everyone gets a boat), horse (everyone gets the same
 *                                  horse), any (bring anything: your own ship, horse, boat, feet). /race mode <mode> changes it
 *   /race cp add [radius]          a checkpoint ring where you are (default race.default-radius 6); the LAST one is the finish
 *   /race cp undo | cp list | setstart | edit <name> | delete <name>
 *   /race host <name> [lend]       opens sign-ups (a clickable [JOIN] in chat); "lend" lends everyone an elytra + rockets
 *   /race start                    everyone who joined goes to the start, 5 s countdown (held in place), GO
 *   /race stop
 * Players: /race join, /race leave, /race list, /race top [name].
 * Rings are drawn only for each racer (the next one bright, the one after faint); a checkpoint counts when the path
 * between two ticks passes within its radius, so fast flyers can't skip it. Finishing = time, place, personal best
 * (races.yml), race.rewards commands, Index stat elytra_races. Dying, leaving or race.max-minutes = DNF. Lent elytras
 * and rockets are tagged and taken back at the end (and on join, after a crash); the chestplate they replaced comes back.
 */
final class Race implements Listener, CommandExecutor, TabCompleter {

    static final class Course {
        String name;
        String mode = "elytra"; // elytra | boat | horse | any
        Location start;
        final List<double[]> cps = new ArrayList<>(); // x, y, z, radius
        final Map<UUID, Long> best = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
    }

    static final class Racer {
        final UUID id;
        int next;
        Location last;
        long finished = -1;
        boolean out;
        ItemStack savedChest;
        boolean lent;
        UUID mount; // the boat or horse lent for this race
        boolean free; // "any" race on their own vehicle: not held at the line
        Racer(UUID id) { this.id = id; }
    }

    private final FaultlineItems plugin;
    private final File file;
    final NamespacedKey lentKey;
    static final List<String> MODES = List.of("elytra", "boat", "horse", "any");
    static final String MOUNT_TAG = "faultline_race_mount";
    final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<UUID, String> editing = new HashMap<>();
    private final Map<UUID, ItemStack> pendingRestore = new HashMap<>();

    // the race being hosted
    Course live;
    boolean lend, running;
    final Map<UUID, Racer> racers = new LinkedHashMap<>();
    long startAt, goAt;
    int place;

    Race(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "races.yml");
        lentKey = new NamespacedKey(plugin, "race_lent");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("race." + path, def); }

    // ---------------------------------------------------------------- saving

    void load() {
        courses.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("courses");
        if (cs != null) for (String key : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(key);
            World w = Bukkit.getWorld(c.getString("world", "world"));
            if (w == null) continue;
            Course co = new Course();
            co.name = key;
            co.mode = MODES.contains(c.getString("mode", "elytra")) ? c.getString("mode", "elytra") : "elytra";
            co.start = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
            for (String s : c.getStringList("checkpoints")) {
                String[] p = s.split(",");
                if (p.length == 4) co.cps.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])});
            }
            ConfigurationSection b = c.getConfigurationSection("best");
            if (b != null) for (String id : b.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(id);
                    co.best.put(u, b.getLong(id + ".ms"));
                    co.names.put(u, b.getString(id + ".name", "?"));
                } catch (IllegalArgumentException ignored) { }
            }
            courses.put(key.toLowerCase(Locale.ROOT), co);
        }
        ConfigurationSection pr = y.getConfigurationSection("restore");
        if (pr != null) for (String id : pr.getKeys(false)) {
            try { pendingRestore.put(UUID.fromString(id), pr.getItemStack(id)); } catch (IllegalArgumentException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Course co : courses.values()) {
            String b = "courses." + co.name + ".";
            y.set(b + "world", co.start.getWorld().getName());
            y.set(b + "mode", co.mode);
            y.set(b + "x", co.start.getX()); y.set(b + "y", co.start.getY()); y.set(b + "z", co.start.getZ());
            y.set(b + "yaw", co.start.getYaw()); y.set(b + "pitch", co.start.getPitch());
            List<String> cps = new ArrayList<>();
            for (double[] c : co.cps) cps.add(c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
            y.set(b + "checkpoints", cps);
            for (var e : co.best.entrySet()) {
                y.set(b + "best." + e.getKey() + ".ms", e.getValue());
                y.set(b + "best." + e.getKey() + ".name", co.names.getOrDefault(e.getKey(), "?"));
            }
        }
        // chestplates a lent elytra replaced, until they're given back (survives a crash)
        for (Racer r : racers.values()) if (r.savedChest != null) y.set("restore." + r.id, r.savedChest);
        for (var e : pendingRestore.entrySet()) y.set("restore." + e.getKey(), e.getValue());
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save races.yml: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- the race

    boolean host(Course co, boolean lendElytra) {
        if (live != null) return false;
        live = co; lend = lendElytra; running = false; racers.clear(); place = 0;
        Component join = Component.text("[JOIN]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/race join")).hoverEvent(HoverEvent.showText(Component.text("Join the race")));
        Bukkit.broadcast(Component.text(icon(co) + " " + title(co) + " is open: ", NamedTextColor.AQUA)
                .append(Component.text(co.name, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" (" + co.cps.size() + " rings, " + switch (co.mode) {
                    case "boat" -> "a boat race: boats provided";
                    case "horse" -> "a horse race: horses provided";
                    case "any" -> "anything goes: ships, horses, boats, feet";
                    default -> lendElytra ? "elytra lent" : "bring your elytra";
                } + ") ", NamedTextColor.GRAY))
                .append(join));
        return true;
    }

    boolean join(Player p) {
        if (live == null || running || racers.containsKey(p.getUniqueId())) return false;
        racers.put(p.getUniqueId(), new Racer(p.getUniqueId()));
        broadcastRacers(ChatColor.AQUA + p.getName() + ChatColor.GRAY + " joined the race (" + racers.size() + ").");
        return true;
    }

    void start() {
        if (live == null || running) return;
        racers.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        running = true;
        startAt = System.currentTimeMillis();
        goAt = startAt + (long) (cfg("countdown-seconds", 5) * 1000);
        int i = 0;
        for (Racer r : racers.values()) {
            Player p = Bukkit.getPlayer(r.id);
            Location at = spot(i++);
            boolean any = live.mode.equals("any");
            if (p.isInsideVehicle() && !any) p.leaveVehicle();
            if (any && p.getVehicle() != null) { // their own ship / horse / boat: they line up themselves, nothing is teleported
                r.last = p.getLocation().clone();
                r.free = true;
                p.sendMessage(ChatColor.YELLOW + "Get to the start line: the race starts in " + (int) Math.ceil(cfg("countdown-seconds", 5)) + "s.");
                continue;
            }
            p.teleport(at);
            r.last = at.clone();
            switch (live.mode) {
                case "boat", "horse" -> mountUp(p, r, at);
                case "any" -> { }
                default -> {
                    if (lend) lendTo(p, r);
                    else if (p.getInventory().getChestplate() == null || p.getInventory().getChestplate().getType() != Material.ELYTRA)
                        p.sendMessage(ChatColor.YELLOW + "Wear an elytra before GO!");
                }
            }
        }
        save();
    }

    /** Racers line up side by side across the start, facing the course. */
    private Location spot(int i) {
        Location s = live.start.clone();
        Vector side = s.getDirection().setY(0);
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        double gap = live.mode.equals("elytra") ? 1.5 : 2.6; // boats and horses need room
        side = side.normalize().crossProduct(new Vector(0, 1, 0)).multiply(((i % 2 == 0) ? 1 : -1) * ((i + 1) / 2) * gap);
        return s.add(side);
    }

    static String icon(Course c) { return switch (c.mode) { case "boat" -> "⛵"; case "horse" -> "🐎"; case "any" -> "🏁"; default -> "✈"; }; }
    static String title(Course c) { return switch (c.mode) { case "boat" -> "A Boat Race"; case "horse" -> "A Horse Race"; case "any" -> "A Race"; default -> "An Elytra Race"; }; }

    /** Boat / horse races: everyone gets the same mount, so it's the rider who wins. */
    void mountUp(Player p, Racer r, Location at) {
        org.bukkit.entity.Entity m;
        if (live.mode.equals("boat")) {
            EntityType t;
            try { t = EntityType.valueOf(plugin.getConfig().getString("race.boat-type", "OAK_BOAT").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { t = EntityType.OAK_BOAT; }
            m = at.getWorld().spawnEntity(at, t);
        } else {
            m = at.getWorld().spawnEntity(at, EntityType.HORSE);
            if (m instanceof org.bukkit.entity.Horse h) {
                h.setTamed(true); h.setOwner(p); h.setAdult(); h.setAgeLock(true);
                h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                h.setColor(org.bukkit.entity.Horse.Color.values()[Math.floorMod(r.id.hashCode(), org.bukkit.entity.Horse.Color.values().length)]);
                attr(h, org.bukkit.attribute.Attribute.MOVEMENT_SPEED, cfg("horse-speed", 0.3));
                attr(h, org.bukkit.attribute.Attribute.JUMP_STRENGTH, cfg("horse-jump", 0.75));
                attr(h, org.bukkit.attribute.Attribute.MAX_HEALTH, 30);
                h.setHealth(30);
                h.setInvulnerable(true);
            }
        }
        m.setPersistent(false);
        m.addScoreboardTag(MOUNT_TAG);
        m.setInvulnerable(true);
        m.addPassenger(p);
        r.mount = m.getUniqueId();
    }

    private static void attr(org.bukkit.entity.LivingEntity e, org.bukkit.attribute.Attribute a, double v) {
        var i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private boolean releasing; // our own eject: let it through

    /** Takes the lent boat or horse away. */
    void dismount(Racer r) {
        if (r.mount == null) return;
        org.bukkit.entity.Entity m = Bukkit.getEntity(r.mount);
        r.mount = null;
        releasing = true;
        try { if (m != null) { m.eject(); m.remove(); } } finally { releasing = false; }
    }

    void lendTo(Player p, Racer r) {
        ItemStack chest = p.getInventory().getChestplate();
        if (chest != null && !chest.getType().isAir() && !lent(chest)) r.savedChest = chest.clone();
        p.getInventory().setChestplate(tag(new ItemStack(Material.ELYTRA)));
        ItemStack rockets = tag(new ItemStack(Material.FIREWORK_ROCKET, (int) cfg("lend-rockets", 16)));
        p.getInventory().addItem(rockets);
        r.lent = true;
    }

    private ItemStack tag(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(lentKey, PersistentDataType.BYTE, (byte) 1);
        m.setDisplayName(ChatColor.AQUA + "Race " + (it.getType() == Material.ELYTRA ? "Elytra" : "Rocket") + ChatColor.GRAY + " (lent)");
        it.setItemMeta(m);
        return it;
    }

    boolean lent(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(lentKey, PersistentDataType.BYTE); }

    /** Takes back every lent item and gives back the chestplate. */
    void giveBack(Player p, Racer r) {
        strip(p);
        ItemStack back = r != null && r.savedChest != null ? r.savedChest : pendingRestore.remove(p.getUniqueId());
        if (r != null) r.savedChest = null;
        if (back != null) {
            if (p.getInventory().getChestplate() == null || p.getInventory().getChestplate().getType().isAir()) p.getInventory().setChestplate(back);
            else p.getInventory().addItem(back).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        }
    }

    void strip(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (lent(inv.getItem(i))) inv.setItem(i, null);
        if (lent(inv.getChestplate())) inv.setChestplate(null);
        if (lent(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (lent(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    void out(Racer r, String why) {
        if (r.out || r.finished >= 0) return;
        r.out = true;
        dismount(r);
        Player p = Bukkit.getPlayer(r.id);
        if (p != null) {
            p.sendMessage(ChatColor.RED + "Out of the race " + ChatColor.GRAY + "(" + why + ").");
            if (r.lent) giveBack(p, r);
        } else if (r.savedChest != null) { pendingRestore.put(r.id, r.savedChest); r.savedChest = null; }
        maybeEnd();
    }

    void finish(Player p, Racer r) {
        long ms = System.currentTimeMillis() - goAt;
        r.finished = ms;
        place++;
        dismount(r);
        Long pb = live.best.get(p.getUniqueId());
        boolean newBest = pb == null || ms < pb;
        if (newBest) { live.best.put(p.getUniqueId(), ms); live.names.put(p.getUniqueId(), p.getName()); }
        String medal = place == 1 ? ChatColor.GOLD + "1st" : place == 2 ? ChatColor.WHITE + "2nd" : place == 3 ? ChatColor.RED + "3rd" : ChatColor.GRAY + "#" + place;
        Bukkit.broadcastMessage(ChatColor.AQUA + icon(live) + " " + p.getName() + ChatColor.GRAY + " finished " + live.name + " " + medal
                + ChatColor.GRAY + " in " + ChatColor.WHITE + time(ms) + (newBest ? ChatColor.GREEN + " (personal best!)" : ""));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation(), 60, 1, 1, 1, 0.2);
        String key = place == 1 ? "first" : place == 2 ? "second" : place == 3 ? "third" : "finish";
        for (String c : plugin.getConfig().getStringList("race.rewards." + key)) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " " + (live.mode.equals("elytra") ? "elytra" : live.mode.equals("any") ? "open" : live.mode) + "_races 1");
        if (r.lent) giveBack(p, r);
        save();
        maybeEnd();
    }

    void maybeEnd() {
        if (running && racers.values().stream().allMatch(r -> r.out || r.finished >= 0)) end("everyone is done");
    }

    void end(String why) {
        if (live == null) return;
        for (Racer r : racers.values()) {
            dismount(r);
            Player p = Bukkit.getPlayer(r.id);
            if (p != null) { if (r.lent) giveBack(p, r); }
            else if (r.savedChest != null) { pendingRestore.put(r.id, r.savedChest); r.savedChest = null; }
        }
        List<Racer> done = new ArrayList<>(racers.values().stream().filter(r -> r.finished >= 0).toList());
        done.sort(Comparator.comparingLong(r -> r.finished));
        if (running) {
            StringBuilder s = new StringBuilder(ChatColor.AQUA + icon(live) + " The " + live.name + " race is over" + ChatColor.GRAY + " (" + why + ")");
            for (int i = 0; i < Math.min(3, done.size()); i++) {
                Player p = Bukkit.getPlayer(done.get(i).id);
                s.append("\n ").append(ChatColor.GRAY).append(i + 1).append(". ").append(ChatColor.WHITE)
                        .append(p != null ? p.getName() : live.names.getOrDefault(done.get(i).id, "?")).append(ChatColor.GRAY).append(" ").append(time(done.get(i).finished));
            }
            Bukkit.broadcastMessage(s.toString());
        } else Bukkit.broadcastMessage(ChatColor.GRAY + "The " + live.name + " race was called off.");
        live = null; running = false; racers.clear();
        save();
    }

    static String time(long ms) { return String.format("%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms / 100) % 10); }

    /** Closest distance from c to the segment a-b (so a flyer moving 3+ blocks a tick can't jump over a ring). */
    static double segDist(Vector a, Vector b, Vector c) {
        Vector ab = b.clone().subtract(a);
        double len = ab.lengthSquared();
        double t = len < 1e-9 ? 0 : Math.max(0, Math.min(1, c.clone().subtract(a).dot(ab) / len));
        return a.clone().add(ab.multiply(t)).distance(c);
    }

    void tick() {
        if (live == null) return;
        long now = System.currentTimeMillis();
        if (!running) return;
        boolean countdown = now < goAt;
        for (Racer r : new ArrayList<>(racers.values())) {
            if (live == null) return; // the last one finished: the race ended
            if (r.out || r.finished >= 0) continue;
            Player p = Bukkit.getPlayer(r.id);
            if (p == null) { out(r, "left"); continue; }
            if (countdown) { // held at the line
                org.bukkit.entity.Entity held = p.getVehicle() != null ? p.getVehicle() : p;
                boolean ours = held == p || held.getUniqueId().equals(r.mount); // never move someone's ship seat or their own horse
                if (r.free || !ours) r.last = p.getLocation().clone();
                else {
                    if (!p.getWorld().equals(r.last.getWorld()) || held.getLocation().distanceSquared(r.last) > 1.5) {
                        Location back = r.last.clone(); back.setYaw(held.getLocation().getYaw()); back.setPitch(held.getLocation().getPitch());
                        if (held == p) p.teleport(back);
                        else held.teleport(back, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                    }
                    if (held != p) held.setVelocity(new Vector());
                }
                long left = (goAt - now + 999) / 1000;
                p.sendActionBar(ChatColor.YELLOW + "" + ChatColor.BOLD + left + "...");
                if ((goAt - now) % 1000 < 100) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                continue;
            }
            if (now - goAt > cfg("max-minutes", 10) * 60000) { out(r, "out of time"); continue; }
            Location at = p.getLocation();
            if (!at.getWorld().equals(live.start.getWorld())) { out(r, "left the world"); continue; }
            while (r.next < live.cps.size()) {
                double[] c = live.cps.get(r.next);
                Vector center = new Vector(c[0], c[1], c[2]);
                Vector from = r.last.toVector(), to = at.toVector().add(new Vector(0, 0.9, 0));
                if (segDist(from.add(new Vector(0, 0.9, 0)), to, center) > c[3]) break;
                r.next++;
                if (r.next >= live.cps.size()) { finish(p, r); break; }
                p.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f + 0.4f * r.next / live.cps.size());
            }
            r.last = at.clone();
            if (r.finished >= 0 || r.out) continue;
            p.sendActionBar(ChatColor.AQUA + "Ring " + (r.next + 1) + "/" + live.cps.size() + ChatColor.GRAY + "  ·  " + ChatColor.WHITE + time(now - goAt));
            ring(p, live.cps.get(r.next), r.next == live.cps.size() - 1 ? Color.fromRGB(255, 215, 0) : Color.fromRGB(80, 220, 255), 1.4f);
            if (r.next + 1 < live.cps.size()) ring(p, live.cps.get(r.next + 1), Color.fromRGB(120, 120, 140), 0.8f);
        }
        if (live != null && countdown && now + 100 >= goAt) {
            for (Racer r : racers.values()) {
                Player p = Bukkit.getPlayer(r.id);
                if (p != null) { p.showTitle(net.kyori.adventure.title.Title.title(Component.text("GO!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.empty())); p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f); }
            }
        }
    }

    /** A ring of dust only this racer sees, facing the direction from the ring before. */
    private void ring(Player p, double[] c, Color color, float size) {
        Vector center = new Vector(c[0], c[1], c[2]);
        Vector axis = p.getLocation().toVector().subtract(center);
        if (axis.lengthSquared() < 1e-6) axis = new Vector(0, 0, 1);
        axis.setY(0);
        if (axis.lengthSquared() < 1e-6) axis = new Vector(0, 0, 1);
        axis.normalize();
        Vector u = new Vector(0, 1, 0), v = axis.clone().crossProduct(u).normalize();
        int n = (int) Math.max(16, Math.min(48, c[3] * 6));
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Vector pt = center.clone().add(u.clone().multiply(Math.sin(a) * c[3])).add(v.clone().multiply(Math.cos(a) * c[3]));
            p.spawnParticle(Particle.DUST, pt.getX(), pt.getY(), pt.getZ(), 1, 0, 0, 0, 0, dust);
        }
    }

    void broadcastRacers(String msg) { for (Racer r : racers.values()) { Player p = Bukkit.getPlayer(r.id); if (p != null) p.sendMessage(msg); } }

    void shutdown() { if (live != null) end("the server is stopping"); }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Racer r = racers.get(e.getEntity().getUniqueId());
        if (r != null && running) {
            e.getDrops().removeIf(this::lent); // a lent elytra never drops
            out(r, "died");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Racer r = racers.get(e.getPlayer().getUniqueId());
        if (r == null) return;
        if (!running) { racers.remove(r.id); return; }
        if (r.lent) giveBack(e.getPlayer(), r);
        out(r, "left");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        strip(p); // lent items left over from a crash
        ItemStack back = pendingRestore.remove(p.getUniqueId());
        if (back != null) {
            if (p.getInventory().getChestplate() == null) p.getInventory().setChestplate(back);
            else p.getInventory().addItem(back).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
            save();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p) || !e.getDismounted().getScoreboardTags().contains(MOUNT_TAG)) return;
        Racer r = racers.get(p.getUniqueId());
        if (!releasing && r != null && running && !r.out && r.finished < 0 && e.getDismounted().getUniqueId().equals(r.mount) && e.isCancellable()) {
            e.setCancelled(true);
            p.sendActionBar(ChatColor.YELLOW + "You're racing! /race leave to drop out.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMountHurt(org.bukkit.event.vehicle.VehicleDamageEvent e) { if (e.getVehicle().getScoreboardTags().contains(MOUNT_TAG)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onMountBreak(org.bukkit.event.vehicle.VehicleDestroyEvent e) { if (e.getVehicle().getScoreboardTags().contains(MOUNT_TAG)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onMountOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof org.bukkit.entity.Entity en && en.getScoreboardTags().contains(MOUNT_TAG)) e.setCancelled(true); // no stealing the saddle
    }

    /** A lent mount left over (unloaded mid-race, a crash with persistence off is already handled) is removed on sight. */
    @EventHandler
    public void onChunk(org.bukkit.event.world.ChunkLoadEvent e) {
        for (org.bukkit.entity.Entity en : e.getChunk().getEntities())
            if (en.getScoreboardTags().contains(MOUNT_TAG) && racers.values().stream().noneMatch(r -> en.getUniqueId().equals(r.mount))) en.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (lent(e.getItemDrop().getItemStack())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) return; // the player's own inventory
        if (lent(e.getCurrentItem()) || lent(e.getCursor())
                || (e.getHotbarButton() >= 0 && lent(e.getWhoClicked().getInventory().getItem(e.getHotbarButton())))) e.setCancelled(true);
    }

    /** No hiding lent rockets or the elytra in a bundle (strip() only looks at the inventory itself). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBundle(InventoryClickEvent e) {
        boolean bundleAction = e.getAction().name().contains("BUNDLE");
        boolean bundleInvolved = bundle(e.getCurrentItem()) || bundle(e.getCursor());
        if ((bundleAction || bundleInvolved) && (lent(e.getCurrentItem()) || lent(e.getCursor()))) e.setCancelled(true);
    }

    static boolean bundle(ItemStack it) { return it != null && it.getType().name().endsWith("BUNDLE");
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        boolean admin = sender.hasPermission("items.race.admin");
        switch (sub) {
            case "join" -> {
                if (p == null) return true;
                if (live == null) sender.sendMessage(ChatColor.RED + "No race is open right now.");
                else if (running) sender.sendMessage(ChatColor.RED + "The race already started.");
                else if (!join(p)) sender.sendMessage(ChatColor.YELLOW + "You're already in.");
                else sender.sendMessage(ChatColor.GREEN + "You're in! Wait for the host to start it.");
            }
            case "leave" -> {
                if (p == null) return true;
                Racer r = racers.get(p.getUniqueId());
                if (r == null) { sender.sendMessage(ChatColor.RED + "You're not in a race."); return true; }
                if (!running) { racers.remove(r.id); sender.sendMessage(ChatColor.GRAY + "You left the race."); }
                else out(r, "you left");
            }
            case "list" -> {
                if (courses.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No race courses yet."); return true; }
                for (Course c : courses.values()) sender.sendMessage(ChatColor.AQUA + c.name + ChatColor.GRAY + " (" + c.mode + ", " + c.cps.size() + " rings, " + c.start.getWorld().getName() + ")");
            }
            case "top" -> {
                Course c = args.length > 1 ? courses.get(args[1].toLowerCase(Locale.ROOT)) : live != null ? live : courses.values().stream().findFirst().orElse(null);
                if (c == null) { sender.sendMessage(ChatColor.RED + "/race top <course>"); return true; }
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "✈ " + c.name + ChatColor.GRAY + " best times");
                List<Map.Entry<UUID, Long>> list = new ArrayList<>(c.best.entrySet());
                list.sort(Map.Entry.comparingByValue());
                for (int i = 0; i < Math.min(10, list.size()); i++)
                    sender.sendMessage(ChatColor.GRAY + " " + (i + 1) + ". " + ChatColor.WHITE + c.names.getOrDefault(list.get(i).getKey(), "?") + ChatColor.GRAY + " " + time(list.get(i).getValue()));
                if (list.isEmpty()) sender.sendMessage(ChatColor.GRAY + " Nobody has finished it yet.");
            }
            default -> {
                if (!admin) { help(sender, false); return true; }
                return adminCommand(sender, p, sub, args);
            }
        }
        return true;
    }

    private boolean adminCommand(CommandSender sender, Player p, String sub, String[] args) {
        switch (sub) {
            case "create" -> {
                if (p == null || args.length < 2) { sender.sendMessage(ChatColor.RED + "/race create <name> (in game: the start is where you stand)"); return true; }
                String key = args[1].toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_-]{1,32}")) { sender.sendMessage(ChatColor.RED + "Use letters, numbers, _ and - only."); return true; }
                if (courses.containsKey(key)) { sender.sendMessage(ChatColor.RED + "That course exists (/race edit " + key + ")."); return true; }
                Course c = new Course(); c.name = key; c.start = p.getLocation().clone();
                if (args.length > 2 && MODES.contains(args[2].toLowerCase(Locale.ROOT))) c.mode = args[2].toLowerCase(Locale.ROOT);
                courses.put(key, c); editing.put(p.getUniqueId(), key); save();
                sender.sendMessage(ChatColor.GREEN + "Course " + key + " (" + c.mode + ") made, start here. Go round the course and /race cp add [radius] at each ring; the last ring is the finish.");
            }
            case "edit" -> {
                if (p == null || args.length < 2 || !courses.containsKey(args[1].toLowerCase(Locale.ROOT))) { sender.sendMessage(ChatColor.RED + "/race edit <course>"); return true; }
                editing.put(p.getUniqueId(), args[1].toLowerCase(Locale.ROOT));
                sender.sendMessage(ChatColor.GREEN + "Editing " + args[1] + ".");
            }
            case "mode" -> {
                Course c = edited(sender, p); if (c == null) return true;
                if (args.length < 2 || !MODES.contains(args[1].toLowerCase(Locale.ROOT))) { sender.sendMessage(ChatColor.RED + "/race mode <elytra|boat|horse|any>"); return true; }
                c.mode = args[1].toLowerCase(Locale.ROOT); save();
                sender.sendMessage(ChatColor.GREEN + c.name + " is now a " + c.mode + " course.");
            }
            case "setstart" -> {
                Course c = edited(sender, p); if (c == null) return true;
                c.start = p.getLocation().clone(); save();
                sender.sendMessage(ChatColor.GREEN + "Start moved here.");
            }
            case "cp", "checkpoint" -> {
                Course c = edited(sender, p); if (c == null) return true;
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
                if (what.equals("add")) {
                    double r = args.length > 2 ? Math.max(1.5, Math.min(30, parseD(args[2], cfg("default-radius", 6)))) : cfg("default-radius", 6);
                    if (!p.getWorld().equals(c.start.getWorld())) { sender.sendMessage(ChatColor.RED + "Rings must be in the start's world."); return true; }
                    Location l = p.getLocation();
                    c.cps.add(new double[]{l.getX(), l.getY() + 0.9, l.getZ(), r}); save();
                    sender.sendMessage(ChatColor.GREEN + "Ring " + c.cps.size() + " added (radius " + r + ").");
                    ring(p, c.cps.get(c.cps.size() - 1), Color.LIME, 1.5f);
                } else if (what.equals("undo")) {
                    if (!c.cps.isEmpty()) c.cps.remove(c.cps.size() - 1);
                    save(); sender.sendMessage(ChatColor.YELLOW + "Last ring removed (" + c.cps.size() + " left).");
                } else {
                    for (int i = 0; i < c.cps.size(); i++) { double[] d = c.cps.get(i); sender.sendMessage(ChatColor.GRAY + " " + (i + 1) + ". " + (int) d[0] + " " + (int) d[1] + " " + (int) d[2] + " r" + d[3]); }
                    if (p != null) for (double[] d : c.cps) ring(p, d, Color.LIME, 1.2f);
                }
            }
            case "delete" -> {
                if (args.length < 2 || courses.remove(args[1].toLowerCase(Locale.ROOT)) == null) { sender.sendMessage(ChatColor.RED + "/race delete <course>"); return true; }
                save(); sender.sendMessage(ChatColor.YELLOW + "Deleted.");
            }
            case "host", "open" -> {
                Course c = args.length > 1 ? courses.get(args[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null) { sender.sendMessage(ChatColor.RED + "/race host <course> [lend]"); return true; }
                if (c.cps.isEmpty()) { sender.sendMessage(ChatColor.RED + "That course has no rings yet."); return true; }
                boolean l = args.length > 2 ? args[2].equalsIgnoreCase("lend") : plugin.getConfig().getBoolean("race.lend-by-default", false);
                if (!host(c, l)) sender.sendMessage(ChatColor.RED + "A race is already open (/race stop).");
                else sender.sendMessage(ChatColor.GREEN + "Sign-ups open. /race start when everyone's in.");
            }
            case "start" -> {
                if (live == null) { sender.sendMessage(ChatColor.RED + "Host one first: /race host <course>."); return true; }
                if (running) { sender.sendMessage(ChatColor.RED + "It's already running."); return true; }
                if (racers.isEmpty()) { sender.sendMessage(ChatColor.RED + "Nobody joined yet."); return true; }
                start();
            }
            case "stop" -> {
                if (live == null) sender.sendMessage(ChatColor.RED + "No race is open.");
                else end("stopped by " + sender.getName());
            }
            default -> help(sender, true);
        }
        return true;
    }

    private Course edited(CommandSender sender, Player p) {
        if (p == null) { sender.sendMessage("In game only."); return null; }
        String key = editing.get(p.getUniqueId());
        Course c = key == null ? null : courses.get(key);
        if (c == null) sender.sendMessage(ChatColor.RED + "/race create <name> or /race edit <name> first.");
        return c;
    }

    private static double parseD(String s, double def) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; } }

    private void help(CommandSender s, boolean admin) {
        s.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "✈ Elytra Races" + ChatColor.GRAY + ": /race join | leave | list | top [course]");
        if (admin) s.sendMessage(ChatColor.GRAY + "Admin: /race create <name> [elytra|boat|horse|any] | mode <mode> | edit <name> | cp add [radius] | cp undo | cp list | setstart | delete <name> | host <name> [lend] | start | stop");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("items.race.admin");
        if (args.length == 1) {
            Set<String> s = new LinkedHashSet<>(List.of("join", "leave", "list", "top"));
            if (admin) s.addAll(List.of("create", "mode", "edit", "cp", "setstart", "delete", "host", "start", "stop"));
            return new ArrayList<>(s);
        }
        if (args.length == 2 && List.of("edit", "delete", "host", "open", "top").contains(args[0].toLowerCase(Locale.ROOT))) return new ArrayList<>(courses.keySet());
        if (args.length == 2 && args[0].equalsIgnoreCase("cp")) return List.of("add", "undo", "list");
        if (args.length == 3 && args[0].equalsIgnoreCase("host")) return List.of("lend");
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) return MODES;
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return MODES;
        return List.of();
    }
}
