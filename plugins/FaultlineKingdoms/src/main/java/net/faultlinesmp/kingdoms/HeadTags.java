package net.faultlinesmp.kingdoms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * KINGDOM HEAD TAGS (Kingdoms 1.3.0, config display.head-tag / display.outlaw-name / display.outlaw-color).
 * A line above every player's name: their kingdom in its colour, or "Outlaw" with no kingdom. It's a TextDisplay that
 * FOLLOWS the player (teleported to the top of their hitbox every tick it moved; teleport duration 2 smooths it), not a
 * passenger, so teleports and world changes never fail because of it. Non-persistent, tag `faultline_kingdom_tag`.
 * Who sees it: never the player themself; nobody who can't see the player (vanish, emotes, /camera: `canSee`); nobody while
 * the player is invisible (potion), a spectator or dead. Hidden by default and shown per viewer (rechecked every 10 ticks).
 */
final class HeadTags implements Listener {

    static final String TAG = "faultline_kingdom_tag";

    private final FaultlineKingdoms pl;
    final Map<UUID, TextDisplay> tags = new HashMap<>();
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private final Map<UUID, String> text = new HashMap<>();
    private final Map<UUID, Location> last = new HashMap<>();
    private int ticks;

    HeadTags(FaultlineKingdoms pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    boolean on() { return pl.getConfig().getBoolean("display.head-tag", true); }

    /** What the tag says: the kingdom in its colour, else the outlaw line. */
    String line(UUID id) {
        int k = FaultlineKingdoms.kingdomOf(id);
        if (k > 0) return pl.color(k) + pl.name(k);
        ChatColor c;
        try { c = ChatColor.valueOf(pl.getConfig().getString("display.outlaw-color", "DARK_RED").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { c = ChatColor.DARK_RED; }
        return c + pl.getConfig().getString("display.outlaw-name", "Outlaw");
    }

    /** Where it floats: just on top of the name tag (display.head-tag-offset over the hitbox; 1.3.0's 0.78 floated a line too high). */
    static double offset = 0.45;

    static Location spot(Player p) {
        double h = p.getBoundingBox().getHeight();
        if (!(h > 0) || h > 4) h = p.isSneaking() ? 1.5 : 1.8;
        Location l = p.getLocation();
        return new Location(l.getWorld(), l.getX(), l.getY() + h + offset, l.getZ());
    }

    void refresh(Player p) {
        if (!on()) { remove(p.getUniqueId()); return; }
        TextDisplay d = tags.get(p.getUniqueId());
        if (d == null || !d.isValid() || d.getWorld() != p.getWorld()) {
            remove(p.getUniqueId());
            d = spawn(p);
        }
        String want = line(p.getUniqueId());
        if (!want.equals(text.get(p.getUniqueId()))) {
            d.text(LegacyComponentSerializer.legacySection().deserialize(want));
            text.put(p.getUniqueId(), want);
        }
        sync(p, d);
    }

    private TextDisplay spawn(Player p) {
        TextDisplay d = (TextDisplay) p.getWorld().spawnEntity(spot(p), EntityType.TEXT_DISPLAY, false);
        d.addScoreboardTag(TAG);
        d.setPersistent(false);
        try { d.setVisibleByDefault(false); } catch (RuntimeException ignored) { }
        try { d.setBillboard(Display.Billboard.CENTER); } catch (RuntimeException ignored) { }
        try { d.setSeeThrough(false); } catch (RuntimeException ignored) { }
        try { d.setDefaultBackground(true); } catch (RuntimeException ignored) { }
        try { d.setShadowed(false); } catch (RuntimeException ignored) { }
        try { d.setTeleportDuration(2); } catch (RuntimeException ignored) { }
        try { d.setViewRange((float) pl.getConfig().getDouble("display.head-tag-view-range", 0.7)); } catch (RuntimeException ignored) { }
        float s = (float) pl.getConfig().getDouble("display.head-tag-scale", 0.85);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        tags.put(p.getUniqueId(), d);
        shown.put(p.getUniqueId(), new HashSet<>());
        text.remove(p.getUniqueId());
        last.put(p.getUniqueId(), d.getLocation());
        return d;
    }

    /** Hidden from the player themself, and from anyone who shouldn't see them right now. */
    boolean hidden(Player owner) {
        return owner.isDead() || owner.getGameMode() == GameMode.SPECTATOR || owner.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || owner.isInvisible() || owner.hasMetadata("vanished");
    }

    void sync(Player owner, TextDisplay d) {
        Set<UUID> s = shown.computeIfAbsent(owner.getUniqueId(), x -> new HashSet<>());
        boolean hide = hidden(owner);
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean want = !hide && v != owner && v.getWorld() == owner.getWorld() && v.canSee(owner);
            if (want && s.add(v.getUniqueId())) v.showEntity(pl, d);
            else if (!want && s.remove(v.getUniqueId())) v.hideEntity(pl, d);
        }
    }

    void remove(UUID id) {
        TextDisplay d = tags.remove(id);
        if (d != null && d.isValid()) d.remove();
        shown.remove(id);
        text.remove(id);
        last.remove(id);
    }

    void removeAll() { for (UUID id : new ArrayList<>(tags.keySet())) remove(id); }

    /** Every tick: follow everyone who moved; every 10 ticks: who may see it, and the text. */
    void tick() {
        ticks++;
        if (ticks % 100 == 1) { double o = pl.getConfig().getDouble("display.head-tag-offset", 0.45); offset = Math.abs(o - 0.78) < 1e-9 ? 0.45 : o; }
        if (!on()) { if (!tags.isEmpty()) removeAll(); return; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            TextDisplay d = tags.get(p.getUniqueId());
            if (d == null || !d.isValid() || d.getWorld() != p.getWorld()) { refresh(p); continue; }
            Location to = spot(p), was = last.get(p.getUniqueId());
            if (was == null || was.getWorld() != to.getWorld() || was.distanceSquared(to) > 0.0004) {
                d.teleport(to);
                last.put(p.getUniqueId(), to);
            }
            if (ticks % 10 == 0) refresh(p);
        }
        if (ticks % 100 == 0) tags.keySet().removeIf(id -> {
            if (Bukkit.getPlayer(id) != null) return false;
            TextDisplay d = tags.get(id);
            if (d != null && d.isValid()) d.remove();
            shown.remove(id); text.remove(id); last.remove(id);
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) refresh(p); }, 2L);
        // the newcomer gets to see everyone else's tag
        Bukkit.getScheduler().runTaskLater(pl, () -> { for (Player o : Bukkit.getOnlinePlayers()) { TextDisplay d = tags.get(o.getUniqueId()); if (d != null && d.isValid()) sync(o, d); } }, 3L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
        for (Set<UUID> s : shown.values()) s.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) {
        remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (e.getPlayer().isOnline()) refresh(e.getPlayer()); }, 2L);
    }

    static Component plain(String s) { return Component.text(s); }
}
