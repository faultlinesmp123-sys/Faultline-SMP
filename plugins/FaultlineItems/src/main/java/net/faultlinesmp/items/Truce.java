package net.faultlinesmp.items;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * 1.3.20 "MB" TRUCE (config `truce:`): two players fighting each other both say "mb" (or another word in `truce.words`) in
 * chat within `confirm-seconds` (30) and their fight stops: each of them is out of combat (DonutCombat's tag ended through
 * its own private endCombatForPlayer, by reflection; the built-in mimic tag cleared too), unless they're still fighting someone
 * else (another player hit in the combat window, or a mimic): then only that pair is dropped and they're told who's left.
 * Opponents = player-vs-player hits (melee, their projectiles, their pets) within the combat window (DonutCombat's
 * combat-duration when it's installed, else `mimic-player.combat-tag.seconds`). Hitting each other again starts a new fight.
 */
final class Truce implements Listener {

    private final FaultlineItems plugin;
    /** a -> (b -> last hit between them, either way). */
    private final Map<UUID, Map<UUID, Long>> opp = new HashMap<>();
    /** a -> (b -> when a said mb to b). */
    private final Map<UUID, Map<UUID, Long>> said = new HashMap<>();
    /** last time a mimic and this player fought. */
    private final Map<UUID, Long> mimicAt = new HashMap<>();

    Truce(FaultlineItems plugin) { this.plugin = plugin; }

    boolean on() { return plugin.getConfig().getBoolean("truce.enabled", true); }

    private List<String> words() {
        List<String> w = plugin.getConfig().getStringList("truce.words");
        return w.isEmpty() ? List.of("mb", "mb all", "my bad", "mbmb", "mb mb") : w;
    }

    /** The combat window in ms: DonutCombat's own duration when it's there. */
    long windowMs() {
        org.bukkit.plugin.Plugin dc = Bukkit.getPluginManager().getPlugin("DonutCombat");
        if (dc != null && dc.isEnabled()) {
            try {
                java.lang.reflect.Field f = dc.getClass().getDeclaredField("combatDuration");
                f.setAccessible(true);
                int s = f.getInt(dc);
                if (s > 0) return s * 1000L;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            return dc.getConfig().getInt("combat-duration", 120) * 1000L;
        }
        return plugin.getConfig().getLong("mimic-player.combat-tag.seconds", 120) * 1000L;
    }

    void noteMimic(Player p) { mimicAt.put(p.getUniqueId(), System.currentTimeMillis()); }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Player playerOf(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (e instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = playerOf(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        long now = System.currentTimeMillis();
        opp.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>()).put(victim.getUniqueId(), now);
        opp.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).put(attacker.getUniqueId(), now);
        // a new hit after someone said mb cancels what they said
        Map<UUID, Long> a = said.get(attacker.getUniqueId());
        if (a != null) a.remove(victim.getUniqueId());
    }

    /** Players this one has fought within the window. */
    Set<UUID> opponents(UUID id) {
        Map<UUID, Long> m = opp.get(id);
        if (m == null) return Set.of();
        long cut = System.currentTimeMillis() - windowMs();
        m.values().removeIf(t -> t < cut);
        if (m.isEmpty()) { opp.remove(id); return Set.of(); }
        return new HashSet<>(m.keySet());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!on()) return;
        String msg = normalize(PlainTextComponentSerializer.plainText().serialize(e.message()));
        if (!words().contains(msg)) return;
        UUID id = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> mb(id));
    }

    /** `id` said mb to everyone they're fighting. */
    void mb(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        Set<UUID> foes = opponents(id);
        if (foes.isEmpty()) return;
        long now = System.currentTimeMillis();
        long confirm = plugin.getConfig().getLong("truce.confirm-seconds", 30) * 1000L;
        Map<UUID, Long> mine = said.computeIfAbsent(id, k -> new HashMap<>());
        for (UUID f : foes) {
            Player o = Bukkit.getPlayer(f);
            if (o == null) continue;
            Long theirs = said.getOrDefault(f, Map.of()).get(id);
            if (theirs != null && now - theirs <= confirm) { truce(p, o); continue; }
            mine.put(f, now);
            o.sendMessage(Component.text(p.getName() + " said mb. Say mb back to stop the fight.", NamedTextColor.YELLOW));
            p.sendMessage(Component.text("Waiting for " + o.getName() + " to say mb.", NamedTextColor.GRAY));
        }
    }

    private void truce(Player a, Player b) {
        drop(a.getUniqueId(), b.getUniqueId());
        drop(b.getUniqueId(), a.getUniqueId());
        release(a, b);
        release(b, a);
        plugin.getLogger().info("[Truce] " + a.getName() + " and " + b.getName() + " said mb");
    }

    private void drop(UUID a, UUID b) {
        Map<UUID, Long> m = opp.get(a);
        if (m != null) { m.remove(b); if (m.isEmpty()) opp.remove(a); }
        Map<UUID, Long> s = said.get(a);
        if (s != null) { s.remove(b); if (s.isEmpty()) said.remove(a); }
    }

    private void release(Player p, Player other) {
        Set<UUID> left = opponents(p.getUniqueId());
        Long m = mimicAt.get(p.getUniqueId());
        boolean mimic = m != null && System.currentTimeMillis() - m < windowMs();
        if (!left.isEmpty() || mimic) {
            List<String> names = new ArrayList<>();
            for (UUID u : left) { Player o = Bukkit.getPlayer(u); if (o != null) names.add(o.getName()); }
            if (mimic) names.add("a mimic");
            p.sendMessage(Component.text("Truce with " + other.getName() + ", but you're still in combat with " + String.join(", ", names) + ".", NamedTextColor.YELLOW));
            return;
        }
        endCombat(p);
        p.sendMessage(Component.text("Truce with " + other.getName() + ": you're out of combat.", NamedTextColor.GREEN));
    }

    private java.lang.reflect.Method donutEnd;
    private org.bukkit.plugin.Plugin donut;

    /** Ends this player's combat tag: DonutCombat's and the built-in one. */
    void endCombat(Player p) {
        if (plugin.combatTag != null) plugin.combatTag.clear(p.getUniqueId());
        org.bukkit.plugin.Plugin dc = Bukkit.getPluginManager().getPlugin("DonutCombat");
        if (dc == null || !dc.isEnabled()) return;
        try {
            if (donut != dc || donutEnd == null) {
                donut = dc;
                donutEnd = dc.getClass().getDeclaredMethod("endCombatForPlayer", UUID.class);
                donutEnd.setAccessible(true);
            }
            donutEnd.invoke(dc, p.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("[Truce] couldn't end " + p.getName() + "'s DonutCombat tag (" + e + ")");
        }
    }

    private void forget(UUID id) {
        opp.remove(id); said.remove(id); mimicAt.remove(id);
        for (Map<UUID, Long> m : opp.values()) m.remove(id);
        for (Map<UUID, Long> m : said.values()) m.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { forget(e.getEntity().getUniqueId()); }
}
