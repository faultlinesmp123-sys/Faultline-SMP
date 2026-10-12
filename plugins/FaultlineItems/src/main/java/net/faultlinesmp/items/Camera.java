package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CAMERA MODE (Items 1.2.23, config camera:, camera.yml): a free camera for recording, for Bedrock players (no Freecam /
 * Flashback mods there) and Java players without them. /camera = spectator mode where you stand: fly through
 * anything, invisible to everyone in survival; /camera again = back exactly where you were, in your old game mode.
 * /camera speed 1-10. Guarded so it can't be used to dodge a fight or scout bases: not within camera.combat-seconds of
 * being hit by / hitting a player (or a mob, camera.combat-includes-mobs), never during a race, and tethered
 * camera.max-distance (64) blocks from where you started, in that world (faultline.camera.unlimited skips the tether and
 * may spectate other players). Quitting, a crash or a restart puts you back on your feet where you started.
 */
final class Camera implements Listener, CommandExecutor {

    record Spot(Location at, GameMode mode, float flySpeed) { }

    private final FaultlineItems plugin;
    private final File file;
    final Map<UUID, Spot> on = new HashMap<>();
    private final Map<UUID, Long> lastFight = new HashMap<>();
    private final java.util.Set<UUID> restoring = new java.util.HashSet<>();

    Camera(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "camera.yml");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    private double maxDistance() { return plugin.getConfig().getDouble("camera.max-distance", 64); }

    boolean inCamera(Player p) { return on.containsKey(p.getUniqueId()); }

    // ---------------------------------------------------------------- the command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use the camera."); return true; }
        if (!plugin.getConfig().getBoolean("camera.enabled", true) && !p.hasPermission("faultline.camera.unlimited")) {
            p.sendMessage(ChatColor.RED + "Camera mode is turned off."); return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("speed")) {
            if (!inCamera(p)) { p.sendMessage(ChatColor.RED + "Use /camera first."); return true; }
            int n;
            try { n = Integer.parseInt(args.length > 1 ? args[1] : "x"); } catch (NumberFormatException e) { n = -1; }
            if (n < 1 || n > 10) { p.sendMessage(ChatColor.RED + "/camera speed <1-10> (2 is normal)"); return true; }
            p.setFlySpeed(Math.min(1f, n * 0.05f));
            p.sendMessage(ChatColor.AQUA + "Camera speed " + n + ".");
            return true;
        }
        if (inCamera(p)) leave(p, true);
        else enter(p);
        return true;
    }

    void enter(Player p) {
        String why = refuse(p);
        if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
        on.put(p.getUniqueId(), new Spot(p.getLocation().clone(), p.getGameMode(), p.getFlySpeed()));
        save();
        p.setGameMode(GameMode.SPECTATOR);
        p.setFlySpeed(0.1f);
        double max = maxDistance();
        boolean free = p.hasPermission("faultline.camera.unlimited") || max <= 0;
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Camera on. " + ChatColor.GRAY + "Fly anywhere"
                + (free ? "" : " within " + (int) max + " blocks") + "; nobody can see you. "
                + ChatColor.WHITE + "/camera" + ChatColor.GRAY + " to come back, " + ChatColor.WHITE + "/camera speed 1-10"
                + ChatColor.GRAY + ". Hide your HUD for clean shots (F1 on Java).");
    }

    /** Why this player can't start the camera right now (null = they can). */
    String refuse(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return "You're already in spectator mode.";
        if (p.isDead()) return "Not while you're dead.";
        if (p.isInsideVehicle()) return "Get off what you're riding first.";
        if (plugin.race != null && plugin.race.racers.containsKey(p.getUniqueId())) return "Not during a race.";
        if (p.hasPermission("faultline.camera.unlimited")) return null;
        Long f = lastFight.get(p.getUniqueId());
        long secs = (long) plugin.getConfig().getDouble("camera.combat-seconds", 10);
        if (f != null && System.currentTimeMillis() - f < secs * 1000) {
            return "You're in combat: wait " + Math.max(1, (secs * 1000 - (System.currentTimeMillis() - f) + 999) / 1000) + " s.";
        }
        if (p.getFallDistance() > 3) return "Not while you're falling.";
        return null;
    }

    /** Back on your feet where you started, in your old game mode. */
    void leave(Player p, boolean say) {
        Spot s = on.remove(p.getUniqueId());
        if (s == null) return;
        save();
        restoring.add(p.getUniqueId());
        try {
            Location back = s.at();
            if (back.getWorld() == null) back = p.getWorld().getSpawnLocation();
            p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
            p.setGameMode(s.mode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : s.mode());
            p.setFlySpeed(s.flySpeed() <= 0 ? 0.1f : s.flySpeed());
            p.setFallDistance(0);
        } finally { restoring.remove(p.getUniqueId()); }
        if (say) p.sendMessage(ChatColor.AQUA + "Camera off.");
    }

    // ---------------------------------------------------------------- the tether

    void tick() {
        if (on.isEmpty()) return;
        double max = maxDistance();
        for (Map.Entry<UUID, Spot> e : new HashMap<>(on).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            if (p.getGameMode() != GameMode.SPECTATOR) { on.remove(e.getKey()); save(); continue; } // someone changed it
            if (p.hasPermission("faultline.camera.unlimited") || max <= 0) continue;
            Location start = e.getValue().at();
            World w = start.getWorld();
            if (w == null) continue;
            Location l = p.getLocation();
            if (!l.getWorld().equals(w)) { leave(p, false); p.sendMessage(ChatColor.RED + "The camera can't leave this world."); continue; }
            if (l.distanceSquared(start) <= max * max) continue;
            // pulled back to the edge of the tether, facing the same way
            org.bukkit.util.Vector d = l.toVector().subtract(start.toVector()).normalize().multiply(max - 1);
            Location edge = start.clone().add(d);
            edge.setYaw(l.getYaw()); edge.setPitch(l.getPitch());
            p.teleport(edge, PlayerTeleportEvent.TeleportCause.PLUGIN);
            p.sendActionBar(ChatColor.RED + "The camera can't go further than " + (int) max + " blocks.");
        }
    }

    /** Spectator teleports (the player list, clicking someone) stay inside the tether; spectating others is staff only. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Spot s = on.get(p.getUniqueId());
        if (s == null || restoring.contains(p.getUniqueId()) || p.hasPermission("faultline.camera.unlimited")) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        double max = maxDistance();
        Location to = e.getTo();
        if (to == null || !to.getWorld().equals(s.at().getWorld()) || (max > 0 && to.distanceSquared(s.at()) > max * max)) {
            e.setCancelled(true);
            p.sendActionBar(ChatColor.RED + "That's out of the camera's range.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectate(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        if (inCamera(e.getPlayer()) && !e.getPlayer().hasPermission("faultline.camera.unlimited")) e.setCancelled(true);
    }

    /** Leaving spectator any other way (another plugin, /gamemode) ends camera mode WITHOUT moving them (staff chose it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        if (restoring.contains(e.getPlayer().getUniqueId())) return;
        if (e.getNewGameMode() != GameMode.SPECTATOR && on.remove(e.getPlayer().getUniqueId()) != null) save();
    }

    // ---------------------------------------------------------------- combat (can't hop into the camera to escape a fight)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        boolean mobs = plugin.getConfig().getBoolean("camera.combat-includes-mobs", true);
        org.bukkit.entity.Entity by = e.getDamager();
        if (by instanceof Projectile pr && pr.getShooter() instanceof org.bukkit.entity.Entity sh) by = sh;
        if (by instanceof Tameable t && t.getOwner() instanceof Player o) by = o;
        long now = System.currentTimeMillis();
        if (e.getEntity() instanceof Player victim && (by instanceof Player || mobs && by instanceof org.bukkit.entity.Enemy)) {
            lastFight.put(victim.getUniqueId(), now);
        }
        if (by instanceof Player attacker && (e.getEntity() instanceof Player || mobs && e.getEntity() instanceof org.bukkit.entity.Enemy)) {
            lastFight.put(attacker.getUniqueId(), now);
        }
    }

    // ---------------------------------------------------------------- quit / crash / restart

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastFight.remove(e.getPlayer().getUniqueId());
        if (inCamera(e.getPlayer())) leave(e.getPlayer(), false); // saved on their feet, never in spectator
    }

    /** After a crash the player file says spectator: camera.yml knows where they really were. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : null;
        if (y == null) return;
        ConfigurationSection c = y.getConfigurationSection("camera." + p.getUniqueId());
        if (c == null || inCamera(p)) return;
        Location at = c.getLocation("at");
        GameMode mode;
        try { mode = GameMode.valueOf(c.getString("mode", "SURVIVAL")); } catch (IllegalArgumentException ex) { mode = GameMode.SURVIVAL; }
        on.put(p.getUniqueId(), new Spot(at == null ? p.getLocation() : at, mode, (float) c.getDouble("fly-speed", 0.1)));
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) leave(p, false); });
    }

    /** Server stopping: everyone in the camera goes back first. */
    void shutdown() {
        for (UUID id : new java.util.ArrayList<>(on.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leave(p, false);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Spot> e : on.entrySet()) {
            String k = "camera." + e.getKey();
            y.set(k + ".at", e.getValue().at());
            y.set(k + ".mode", e.getValue().mode().name());
            y.set(k + ".fly-speed", e.getValue().flySpeed());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save camera.yml: " + ex.getMessage()); }
    }
}
