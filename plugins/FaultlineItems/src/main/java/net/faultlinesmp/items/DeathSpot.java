package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * DEATH COORDINATES (Items 1.2.26, config death-coords:). When you die, only YOU are told where: on respawn a chat line
 * with the X Y Z, the dimension, how far it is from where you respawned, and [Copy] (copies the coordinates) / [Again]
 * buttons; Bedrock players get a menu. Saved in your player data (PDC faultlineitems:last_death), so /lastdeath shows it
 * again any time (after a relog or a restart too), with the distance and the way to go from where you stand.
 */
final class DeathSpot implements Listener, CommandExecutor {

    private final FaultlineItems plugin;
    private final NamespacedKey key;

    DeathSpot(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "last_death");
    }

    record Spot(String world, int x, int y, int z, long at) {
        String coords() { return x + " " + y + " " + z; }
    }

    Spot get(Player p) {
        String s = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s == null) return null;
        String[] a = s.split(";");
        try { return new Spot(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Long.parseLong(a[4])); }
        catch (RuntimeException e) { return null; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.getConfig().getBoolean("death-coords.enabled", true)) return;
        Location l = e.getEntity().getLocation();
        e.getEntity().getPersistentDataContainer().set(key, PersistentDataType.STRING,
                l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ() + ";" + System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("death-coords.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) tell(p, true); }, 10L);
    }

    static String dimension(String world) {
        World w = Bukkit.getWorld(world);
        return w == null ? world : Where.dimension(w);
    }

    /** "312 blocks north-east", or "in the Nether" when it's another world. */
    static String away(Player p, Spot s) {
        Location here = p.getLocation();
        if (!here.getWorld().getName().equals(s.world())) return "in another dimension";
        double dx = s.x() + 0.5 - here.getX(), dz = s.z() + 0.5 - here.getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        if (dist < 3) return "right here";
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return dist + " blocks " + names[Math.floorMod(Math.round(yaw / 45f), 8)];
    }

    void tell(Player p, boolean justDied) {
        Spot s = get(p);
        if (s == null) { p.sendMessage(ChatColor.GRAY + "You haven't died yet. Keep it that way."); return; }
        String head = justDied ? "☠ You died at " : "☠ Your last death: ";
        String where = s.coords() + " (" + dimension(s.world()) + ")";
        String far = away(p, s);
        if (Edition.bedrock(p) && BedrockForms.menu(plugin, p, "Where you died", "§c" + head + "§f" + where + "\n§7That's " + far + " from you.\n\n§8/lastdeath shows it again.",
                List.of("OK"), i -> { }, null)) return;
        p.sendMessage(Component.text(head, NamedTextColor.RED).append(Component.text(where, NamedTextColor.WHITE))
                .append(Component.text(" · " + far + " ", NamedTextColor.GRAY))
                .append(Component.text("[Copy]", NamedTextColor.AQUA).clickEvent(ClickEvent.copyToClipboard(s.coords()))
                        .hoverEvent(HoverEvent.showText(Component.text("Copy " + s.coords()))))
                .append(Component.text(" "))
                .append(Component.text("[Again]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/lastdeath"))
                        .hoverEvent(HoverEvent.showText(Component.text("/lastdeath: distance from where you are now")))));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        tell(p, false);
        return true;
    }
}
