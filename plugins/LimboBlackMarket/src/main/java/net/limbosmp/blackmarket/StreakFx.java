package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * KILL STREAK COSMETICS (1.2.0, config streak-cosmetics:). At trail-at (5) kills in a row you leave a FIRE TRAIL where you
 * walk (flames + embers at your feet, everyone sees it, Bedrock too: particles). At crown-at (10) a golden CROWN floats
 * above your name, over the kingdom head tag (an ItemDisplay wearing the Slayer Crown model, `faultline_streak_crown`,
 * non-persistent, follows you, turns slowly). Hidden from players who can't see you (vanish, emotes, /camera) and while
 * you're invisible / spectating / dead. Dying resets the streak, so both go away.
 */
final class StreakFx implements Listener {

    static final String TAG = "faultline_streak_crown";
    private final LimboBlackMarket pl;
    private final Map<UUID, ItemDisplay> crowns = new HashMap<>();
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private final Map<UUID, Location> lastFeet = new HashMap<>();
    private int ticks;

    StreakFx(LimboBlackMarket pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    boolean on() { return pl.getConfig().getBoolean("streak-cosmetics.enabled", true); }
    int trailAt() { return pl.getConfig().getInt("streak-cosmetics.trail-at", 5); }
    int crownAt() { return pl.getConfig().getInt("streak-cosmetics.crown-at", 10); }

    static boolean hidden(Player p) {
        return p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.hasPotionEffect(PotionEffectType.INVISIBILITY) || p.isInvisible() || p.hasMetadata("vanished");
    }

    /** Above the kingdom head tag (Kingdoms puts it at hitbox top + 0.78). */
    static Location spot(Player p, int t) {
        double h = p.getBoundingBox().getHeight();
        if (!(h > 0) || h > 4) h = p.isSneaking() ? 1.5 : 1.8;
        Location l = p.getLocation();
        Location at = new Location(l.getWorld(), l.getX(), l.getY() + h + 1.2 + Math.sin(t * 0.08) * 0.04, l.getZ());
        at.setYaw((t * 3) % 360);
        return at;
    }

    void tick() {
        ticks++;
        if (!on()) { if (!crowns.isEmpty()) for (UUID id : new ArrayList<>(crowns.keySet())) remove(id); return; }
        int trail = trailAt(), crown = crownAt();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int streak = pl.getStatsManager() == null ? 0 : pl.getStatsManager().getStreak(p.getUniqueId());
            // fire trail
            if (streak >= trail && !hidden(p)) {
                Location feet = p.getLocation(), was = lastFeet.put(p.getUniqueId(), feet);
                if (was != null && was.getWorld() == feet.getWorld() && was.distanceSquared(feet) > 0.01 && p.isOnGround()) {
                    p.getWorld().spawnParticle(Particle.FLAME, feet.clone().add(0, 0.05, 0), 2, 0.15, 0.02, 0.15, 0.005);
                    if (ticks % 3 == 0) p.getWorld().spawnParticle(Particle.SMALL_FLAME, feet.clone().add(0, 0.1, 0), 1, 0.2, 0.05, 0.2, 0.01);
                    if (streak >= crown && ticks % 4 == 0) p.getWorld().spawnParticle(Particle.LAVA, feet, 1, 0.1, 0, 0.1, 0);
                }
            } else lastFeet.remove(p.getUniqueId());
            // crown
            ItemDisplay d = crowns.get(p.getUniqueId());
            if (streak >= crown) {
                if (d == null || !d.isValid() || d.getWorld() != p.getWorld()) { remove(p.getUniqueId()); d = spawn(p); }
                d.teleport(spot(p, ticks));
                if (ticks % 10 == 0) sync(p, d);
            } else if (d != null) remove(p.getUniqueId());
        }
        if (ticks % 100 == 0) for (UUID id : new ArrayList<>(crowns.keySet())) if (Bukkit.getPlayer(id) == null) remove(id);
    }

    private ItemDisplay spawn(Player p) {
        ItemDisplay d = (ItemDisplay) p.getWorld().spawnEntity(spot(p, ticks), EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(TAG);
        d.addScoreboardTag("faultline_no_bedrock_fx");
        d.setPersistent(false);
        try { d.setVisibleByDefault(false); } catch (RuntimeException ignored) { }
        ItemStack it = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta m = it.getItemMeta();
        try { m.setItemModel(NamespacedKey.fromString(pl.getConfig().getString("streak-cosmetics.crown-model", "faultline:cosmetic/slayer_crown"))); } catch (RuntimeException ignored) { }
        it.setItemMeta(m);
        d.setItemStack(it);
        try { d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD); } catch (RuntimeException ignored) { }
        try { d.setBillboard(Display.Billboard.FIXED); } catch (RuntimeException ignored) { }
        try { d.setTeleportDuration(2); } catch (RuntimeException ignored) { }
        try { d.setBrightness(new Display.Brightness(15, 15)); } catch (RuntimeException ignored) { }
        float s = (float) pl.getConfig().getDouble("streak-cosmetics.crown-scale", 0.55);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        crowns.put(p.getUniqueId(), d);
        shown.put(p.getUniqueId(), new HashSet<>());
        Bukkit.getScheduler().runTask(pl, () -> sync(p, d));
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 2.2, 0), 30, 0.3, 0.3, 0.3, 0.2);
        return d;
    }

    void sync(Player owner, ItemDisplay d) {
        if (!d.isValid()) return;
        Set<UUID> s = shown.computeIfAbsent(owner.getUniqueId(), x -> new HashSet<>());
        boolean hide = hidden(owner);
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean want = !hide && v.getWorld() == owner.getWorld() && (v == owner || v.canSee(owner));
            if (want && s.add(v.getUniqueId())) v.showEntity(pl, d);
            else if (!want && s.remove(v.getUniqueId())) v.hideEntity(pl, d);
        }
    }

    void remove(UUID id) {
        ItemDisplay d = crowns.remove(id);
        if (d != null && d.isValid()) d.remove();
        shown.remove(id);
    }

    void removeAll() { for (UUID id : new ArrayList<>(crowns.keySet())) remove(id); }

    boolean hasCrown(UUID id) { ItemDisplay d = crowns.get(id); return d != null && d.isValid(); }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
        lastFeet.remove(e.getPlayer().getUniqueId());
        for (Set<UUID> s : shown.values()) s.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { remove(e.getPlayer().getUniqueId()); }
}
