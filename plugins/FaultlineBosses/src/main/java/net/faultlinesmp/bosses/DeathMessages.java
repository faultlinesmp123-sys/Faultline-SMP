package net.faultlinesmp.bosses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * BOSS DEATH MESSAGES (Bosses 1.5.8, config death-messages:). Vanilla said "was slain by Slime" (or a hitbox's bare
 * name): every boss hit goes through FaultlineBosses.hurt, which notes who hit you; dying within death-messages.seconds
 * (5) of it, of anything (the hit itself, the fall it knocked you into, the lava), says which boss killed you, a line
 * picked at random from that boss's set. The Index's Discord bridge posts the same line.
 */
final class DeathMessages implements Listener {

    record Hit(String boss, long at) { }

    private final FaultlineBosses pl;
    private final Map<UUID, Hit> last = new HashMap<>();
    private final Random random = new Random();

    /** Lowercase name fragment -> lines (%p = the player). First match wins, so the more specific come first. */
    static final Map<String, List<String>> LINES = new java.util.LinkedHashMap<>();
    static {
        LINES.put("demon eye", List.of("%p was devoured by the Demon Eye", "%p couldn't outstare the Demon Eye", "%p was torn apart by the Demon Eye's teeth"));
        LINES.put("eye", List.of("%p was devoured by the Demon Eye's brood", "%p was swarmed by the Servants of the Eye")); // servants, the twin eye
        LINES.put("hawk", List.of("%p was carried off by Jacob's Great Hawk", "%p was torn apart by Jacob's Great Hawk"));
        LINES.put("frostbeard", List.of("%p was frozen solid by Frostbeard", "%p was buried in Frostbeard's blizzard", "%p was shattered by Frostbeard"));
        LINES.put("mortimer", List.of("%p was frozen solid by Frostbeard", "%p was buried in Frostbeard's blizzard"));
        LINES.put("frostmaw", List.of("%p was dragged under the ice by the Frostmaw", "%p was swallowed by the Frostmaw"));
        LINES.put("dune", List.of("%p was swallowed by the Dune Devourer", "%p was dragged under the sand by the Dune Devourer"));
        LINES.put("don", List.of("%p refused Don Lorenzo's offer", "%p was whacked by Don Lorenzo", "%p sleeps with the fishes, thanks to Don Lorenzo"));
        LINES.put("kraken", List.of("%p was dragged to the depths by the Kraken", "%p was squeezed to death by the Kraken", "%p became the Kraken's lunch"));
        LINES.put("jacob", List.of("%p fell before Diamond Jacob", "%p was cut down by Diamond Jacob", "%p was crushed by Diamond Jacob's war mace"));
        LINES.put("werner", List.of("%p was flattened by Werner", "%p was folded in half by Werner"));
        LINES.put("rocco", List.of("%p was taken for a ride by Rocco Vendetta", "%p owed Rocco Vendetta money", "%p got the Vendetta treatment"));
        LINES.put("ironhide", List.of("%p was trampled by Ironhide", "%p was gored by Ironhide's tusks"));
        LINES.put("grimtusk", List.of("%p was cleaved in two by Grimtusk", "%p was gored by Grimtusk, the Piglin Warlord", "%p fell in Grimtusk's bastion"));
        LINES.put("leviathan", List.of("%p was dragged into the deep by the Leviathan", "%p was swallowed whole by the Leviathan", "%p was rammed by the Leviathan"));
        LINES.put("sandworm", List.of("%p was devoured by the Sandworm King", "%p was buried alive by the Sandworm King", "%p was spat out by the Sandworm King"));
        LINES.put("lich", List.of("%p had their soul drained by the Lich", "%p joined the Lich's army of the dead", "%p was cursed by the Lich"));
        LINES.put("wyrm", List.of("%p was frozen by the Frost Wyrm's breath", "%p was snatched from the ground by the Frost Wyrm", "%p was impaled by the Frost Wyrm's icicles"));
        LINES.put("golem", List.of("%p was pounded flat by the Stone Golem", "%p was crushed by the Stone Golem's boulder", "%p woke the Stone Golem"));
    }

    DeathMessages(FaultlineBosses pl) { this.pl = pl; }

    /** Called by FaultlineBosses.hurt for every boss hit on a player. */
    void note(Player p, Entity source) {
        if (source == null) return;
        String name = source.getCustomName();
        if (name == null || name.isBlank()) name = source.getName();
        name = ChatColor.stripColor(name);
        if (name == null || name.isBlank()) return;
        last.put(p.getUniqueId(), new Hit(name, System.currentTimeMillis()));
    }

    /** The death line for a boss name: one of its own lines, or "was slain by <name>". */
    String line(String boss, String player) {
        String low = boss.toLowerCase(Locale.ROOT);
        for (var e : LINES.entrySet()) {
            if (low.contains(e.getKey())) {
                List<String> l = e.getValue();
                return l.get(random.nextInt(l.size())).replace("%p", player);
            }
        }
        return player + " was slain by " + boss;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Hit h = last.remove(e.getEntity().getUniqueId());
        if (h == null || System.currentTimeMillis() - h.at() > pl.getConfig().getDouble("death-messages.seconds", 5) * 1000) return;
        // the Index learns this boss's weakness from the death (Index 1.7.0: Weakness.java)
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index died " + e.getEntity().getName() + " " + h.boss()); } catch (RuntimeException ignored) { }
        if (!pl.getConfig().getBoolean("death-messages.enabled", true)) return;
        if (e.deathMessage() == null) return; // someone hid it on purpose (a cutscene, another plugin)
        e.deathMessage(Component.text(line(h.boss(), e.getEntity().getName()), NamedTextColor.WHITE));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { last.remove(e.getPlayer().getUniqueId()); }
}
