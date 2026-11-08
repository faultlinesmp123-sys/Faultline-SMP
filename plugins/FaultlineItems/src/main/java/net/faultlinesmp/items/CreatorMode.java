package net.faultlinesmp.items;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.regex.Pattern;

/**
 * CREATOR MODE (Items 1.2.78, /creator, perm faultline.creator, default true; config creator-mode:). For recording or
 * streaming: what this server shows you on screen stops giving away where things are.
 *  - the /coords boss bar shows "hidden" instead of your X Y Z (facing, biome, dimension stay);
 *  - your death message shows only the distance and direction (no coordinates, no [Copy]);
 *  - locations other players share with /coords share reach you as "hidden";
 *  - in chat you SEE, coordinates ("120 64 -300", "x: 120 z: -300") and IP addresses ("1.2.3.4", "1.2.3.4:25565") are
 *    blanked out (each viewer gets their own copy of a message, so only creator-mode players see the blanks).
 * The F3 screen can't be hidden by a server for one player only (vanilla only has the world-wide reducedDebugInfo rule):
 * the toggle message says so. Saved per player (PDC faultlineitems:creator_mode).
 */
final class CreatorMode implements Listener, CommandExecutor {

    static NamespacedKey KEY;
    static final String HIDDEN = "▒▒▒";
    static final Pattern COORDS = Pattern.compile("(?i)(?<!(?<!§)[\\w.])-?\\d{1,7}(?:\\.\\d+)?[,\\s/]+-?\\d{1,3}(?:\\.\\d+)?[,\\s/]+-?\\d{1,7}(?:\\.\\d+)?(?![\\w.])"
            + "|\\b[xz]\\s*[:=]\\s*-?\\d{1,7}");
    static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");
    private static final TextReplacementConfig R_COORDS = TextReplacementConfig.builder().match(COORDS).replacement(HIDDEN).build();
    private static final TextReplacementConfig R_IP = TextReplacementConfig.builder().match(IP).replacement(HIDDEN).build();
    private static FaultlineItems plugin;

    CreatorMode(FaultlineItems pl) { plugin = pl; KEY = new NamespacedKey(pl, "creator_mode"); }

    static boolean on(Player p) {
        if (p == null || KEY == null || plugin == null || !plugin.getConfig().getBoolean("creator-mode.enabled", true)) return false;
        Byte b = p.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    static String mask(String s) { return IP.matcher(COORDS.matcher(s).replaceAll(HIDDEN)).replaceAll(HIDDEN); }

    static Component mask(Component c) { return c.replaceText(R_COORDS).replaceText(R_IP); }

    /** A line for this viewer: blanked if they're in creator mode. */
    static String forViewer(Player viewer, String line) { return on(viewer) ? mask(line) : line; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (!plugin.getConfig().getBoolean("creator-mode.enabled", true)) { p.sendMessage(ChatColor.RED + "Creator mode is turned off on this server."); return true; }
        if (p.isPermissionSet("faultline.creator") && !p.hasPermission("faultline.creator")) { p.sendMessage(ChatColor.RED + "You can't use creator mode."); return true; }
        boolean now = a.length > 0 ? a[0].equalsIgnoreCase("on") : !on(p);
        if (now) p.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(KEY);
        if (now) {
            p.sendMessage(ChatColor.RED + "● " + ChatColor.GOLD + "Creator mode ON." + ChatColor.GRAY
                    + " Your /coords bar, death coordinates and shared locations are hidden, and coordinates and IP addresses in chat are blanked for you.");
            p.sendMessage(ChatColor.DARK_GRAY + "The server can't hide your F3 screen: keep it closed while recording. /creator again turns this off.");
        } else p.sendMessage(ChatColor.GRAY + "Creator mode off.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("creator-mode.enabled", true) || !plugin.getConfig().getBoolean("creator-mode.mask-chat", true)) return;
        ChatRenderer old = e.renderer();
        e.renderer((source, name, message, viewer) -> {
            Component out = old.render(source, name, message, viewer);
            return viewer instanceof Player v && on(v) ? mask(out) : out;
        });
    }
}
