package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE BLOOD MOON WITHOUT A LOADING SCREEN (Items 1.2.58). FaultlineEvents turned the moon red by pushing a second
 * resource pack (FaultlineBloodMoon.zip) to everyone when a Blood Moon started; any pack pushed mid-game makes the client
 * reload EVERY pack (FaultlineSMP.zip is ~24 MB): the Mojang loading screen, for long enough to die in. Now, while
 * FaultlineEvents' BLOOD_MOON is on (`faultlineevents:active_event` on the main world), each Java player gets a private red
 * moon (an ItemDisplay only they see, model faultline:blood_moon in FaultlineSMP.zip) drawn exactly over the real moon:
 * same direction (worked out from their sky's time like the game does), same size, following them. Nothing is reloaded.
 * Turn FaultlineEvents' own Blood Moon pack off in its config. If another plugin still pushes a pack, the console and ops
 * are told once (`foreignPack`). Bedrock players never got the Java pack and Geyser can't draw displays: nothing changes.
 */
final class BloodMoonSky implements Listener {
    static final String TAG = "faultline_blood_moon_sky";
    /** Our own packs (FaultlineItems' main + music, FaultlineBosses' optional one): anything else is a foreign push. */
    static final Set<UUID> OURS = Set.of(UUID.fromString("9d3e7a1c-5b2f-4e8a-b6c4-7f1a2d3e4b5c"),
            UUID.fromString("4c8e1f2a-7d3b-4a96-b5e0-2f6c9a1d8e37"), UUID.fromString("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"));

    final FaultlineItems plugin;
    final Map<UUID, ItemDisplay> moons = new HashMap<>();
    boolean warned;
    int t;

    BloodMoonSky(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[Blood Moon sky] " + e); }
        }, 20L, 2L);
    }

    boolean enabled() { return plugin.getConfig().getBoolean("blood-moon-sky.enabled", true); }

    double cfg(String path, double def) { return plugin.getConfig().getDouble("blood-moon-sky." + path, def); }

    /** The game's own sky angle for a time of day (0 = noon sun overhead, 0.5 = midnight moon overhead). */
    static double celestial(long dayTime) {
        double d = (dayTime / 24000.0 - 0.25) % 1.0;
        if (d < 0) d += 1;
        double e = 0.5 - Math.cos(d * Math.PI) / 2;
        return (d * 2 + e) / 3.0;
    }

    /** Where the moon is in that player's sky (a unit vector): opposite the sun, which rises in the east (+x). */
    static Vector moonDir(long dayTime) {
        double a = celestial(dayTime) * Math.PI * 2;
        return new Vector(Math.sin(a), -Math.cos(a), 0);
    }

    static ItemStack model() {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "blood_moon"));
        s.setItemMeta(m);
        return s;
    }

    boolean on(World w) {
        return enabled() && !Bukkit.getWorlds().isEmpty() && w.equals(Bukkit.getWorlds().get(0)) && w.getEnvironment() == World.Environment.NORMAL
                && plugin.isEventActive("BLOOD_MOON");
    }

    void tick() {
        t++;
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        boolean active = main != null && on(main);
        if (active) for (Player p : main.getPlayers()) {
            if (Edition.bedrock(p)) continue;
            Vector dir = moonDir(p.getPlayerTime() % 24000);
            ItemDisplay d = moon(p);
            if (dir.getY() < -0.12) { d.setViewRange(0f); continue; } // set: hide it under the horizon
            d.setViewRange(4f);
            double dist = Math.max(24, Math.min(120, cfg("distance", 60)));
            Location at = p.getEyeLocation().add(dir.clone().multiply(dist));
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            // the game draws the moon as a 40-wide square 100 away: the same angle at our distance (plus a hair to cover it)
            float sc = (float) (40 * dist / 100.0 * cfg("size", 1.08));
            if (t % 20 == 1) d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
        for (Iterator<Map.Entry<UUID, ItemDisplay>> it = moons.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (!active || p == null || !p.getWorld().equals(main) || !e.getValue().isValid()) {
                if (e.getValue().isValid()) e.getValue().remove();
                it.remove();
            }
        }
    }

    ItemDisplay moon(Player p) {
        ItemDisplay d = moons.get(p.getUniqueId());
        if (d != null && d.isValid() && d.getWorld().equals(p.getWorld())) return d;
        if (d != null && d.isValid()) d.remove();
        d = p.getWorld().spawn(p.getEyeLocation().add(0, 60, 0), ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setItemStack(model());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            e.setTeleportDuration(2);
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.addScoreboardTag("faultline_no_bedrock_fx");
        });
        float sc = (float) (40 * cfg("distance", 60) / 100.0 * cfg("size", 1.08));
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
        p.showEntity(plugin, d);
        moons.put(p.getUniqueId(), d);
        return d;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ItemDisplay d = moons.remove(e.getPlayer().getUniqueId());
        if (d != null && d.isValid()) d.remove();
    }

    /** Another plugin pushed a resource pack mid-game: that's the loading screen. Say so once. */
    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (warned || e.getStatus() != PlayerResourcePackStatusEvent.Status.ACCEPTED) return;
        UUID id;
        try { id = e.getID(); } catch (RuntimeException | NoSuchMethodError ex) { return; }
        if (id == null || OURS.contains(id) || e.getPlayer().getTicksLived() < 20 * 30) return; // join-time packs are expected
        warned = true;
        String why = plugin.isEventActive("BLOOD_MOON") ? " during the Blood Moon (FaultlineEvents' moon pack)" : "";
        String msg = "Another plugin just pushed a resource pack to " + e.getPlayer().getName() + why
                + ": every player's game reloads all packs (the loading screen). Turn that pack off in its plugin's config;"
                + " FaultlineItems draws the red moon itself now (blood-moon-sky).";
        plugin.getLogger().warning(msg);
        for (Player op : Bukkit.getOnlinePlayers()) if (op.isOp()) op.sendMessage(ChatColor.RED + "[Faultline] " + ChatColor.GRAY + msg);
    }

    void shutdown() {
        for (ItemDisplay d : moons.values()) if (d.isValid()) d.remove();
        moons.clear();
    }
}
