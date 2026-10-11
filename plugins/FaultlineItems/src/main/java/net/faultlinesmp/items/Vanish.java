package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * STAFF VANISH (Items 1.2.18, config vanish:, saved in vanished.yml so it survives a relog/restart).
 * /vanish [player] (faultline.vanish): hidden from everyone without faultline.vanish.see (gone from the tab list too,
 * Bedrock included), a fake "left the game" for them on vanish and a fake join on reappear (vanish.fake-messages),
 * the real join/quit messages of a vanished player are hidden, mobs don't target them, they don't pick items up
 * (vanish.pickup), an action bar reminds them. Sets the "vanished" metadata other plugins read (EssentialsX/SuperVanish
 * convention); the Index's Discord bridge skips their joins/leaves.
 */
final class Vanish implements Listener, CommandExecutor {

    private final FaultlineItems plugin;
    private final File file;
    final Set<UUID> hidden = new HashSet<>();

    Vanish(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "vanished.yml");
        if (file.exists()) for (String s : YamlConfiguration.loadConfiguration(file).getStringList("vanished")) {
            try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    boolean vanished(Player p) { return hidden.contains(p.getUniqueId()); }

    static boolean sees(Player viewer) { return viewer.hasPermission("faultline.vanish.see"); }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : hidden) l.add(u.toString());
        y.set("vanished", l);
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save vanished.yml: " + e.getMessage()); }
    }

    void apply(Player p) {
        boolean v = vanished(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            if (v && !sees(o)) o.hidePlayer(plugin, p); else o.showPlayer(plugin, p);
        }
        if (v) p.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        else p.removeMetadata("vanished", plugin);
    }

    void set(Player p, boolean v) {
        if (v == vanished(p)) return;
        if (v) hidden.add(p.getUniqueId()); else hidden.remove(p.getUniqueId());
        apply(p);
        save();
        if (plugin.getConfig().getBoolean("vanish.fake-messages", true)) {
            String msg = ChatColor.YELLOW + p.getName() + (v ? " left the game" : " joined the game");
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && !sees(o)) o.sendMessage(msg);
        }
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && sees(o))
            o.sendMessage(ChatColor.GRAY + "[Staff] " + p.getName() + (v ? " vanished." : " is visible again."));
        p.sendMessage(v ? ChatColor.AQUA + "You're vanished. " + ChatColor.GRAY + "Only staff can see you. /vanish again to come back."
                : ChatColor.GREEN + "You're visible again.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("faultline.vanish")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            List<String> n = new ArrayList<>();
            for (UUID u : hidden) n.add(Bukkit.getOfflinePlayer(u).getName());
            sender.sendMessage(ChatColor.GRAY + "Vanished: " + (n.isEmpty() ? "nobody" : String.join(", ", n)));
            return true;
        }
        Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player p ? p : null;
        if (target == null) { sender.sendMessage(ChatColor.RED + (args.length > 0 ? "Player not found." : "/vanish <player>")); return true; }
        set(target, !vanished(target));
        if (!target.equals(sender)) sender.sendMessage(ChatColor.GRAY + target.getName() + (vanished(target) ? " is vanished." : " is visible."));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (vanished(p)) {
            e.joinMessage(null);
            apply(p);
            p.sendMessage(ChatColor.AQUA + "You joined vanished.");
        }
        if (!sees(p)) for (UUID u : hidden) { Player v = Bukkit.getPlayer(u); if (v != null && !v.equals(p)) p.hidePlayer(plugin, v); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { if (vanished(e.getPlayer())) e.quitMessage(null); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && vanished(p) && !plugin.getConfig().getBoolean("vanish.pickup", false)) e.setCancelled(true);
    }

    private void tick() {
        for (UUID u : hidden) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendActionBar(ChatColor.AQUA + "" + ChatColor.BOLD + "VANISHED");
        }
    }
}
