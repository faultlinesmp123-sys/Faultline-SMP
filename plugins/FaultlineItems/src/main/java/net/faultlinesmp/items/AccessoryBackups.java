package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 1.3.13: ACCESSORY BACKUPS, so an InventoryRollbackPlus restore brings the accessories back too.
 * IRP only knows the vanilla inventory; worn accessories live in accessories.yml. At the same moments IRP takes a backup
 * (join, quit, death (before they drop), world change, /irp forcebackup) the 3 accessory slots are snapshotted here with
 * the time (accessory_backups.yml, the newest {@code accessory-backups.keep} per player). When staff click IRP's
 * "restore whole inventory" button (the Nether Star in its "Main Inventory Backup" menu: it carries the player's uuid and
 * the backup's timestamp in its PDC), the snapshot taken at that moment is put back in the player's accessory slots.
 * /accrestore &lt;player&gt; [#n] lists / restores one by hand.
 */
final class AccessoryBackups implements Listener, TabExecutor {

    private static final String IRP_MENU = "Main Inventory Backup";
    private static final String IRP = "inventoryrollbackplus";

    record Snap(long at, String why, ItemStack[] slots) { }

    private final FaultlineItems plugin;
    private final File file;
    private final Map<UUID, List<Snap>> snaps = new HashMap<>();
    private boolean dirty;

    AccessoryBackups(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "accessory_backups.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 600L, 600L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("accessory-backups.enabled", true); }
    private int keep() { return Math.max(5, plugin.getConfig().getInt("accessory-backups.keep", 80)); }

    // ---------------------------------------------------------------- file

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            ConfigurationSection ps = y.getConfigurationSection(id);
            if (ps == null) continue;
            List<Snap> list = new ArrayList<>();
            for (String k : ps.getKeys(false)) {
                try {
                    ConfigurationSection c = ps.getConfigurationSection(k);
                    ItemStack[] slots = new ItemStack[FaultlineItems.AccessoryManager.SLOT_COUNT];
                    for (int i = 0; i < slots.length; i++) slots[i] = c.getItemStack("slot" + i);
                    list.add(new Snap(c.getLong("at"), c.getString("why", "?"), slots));
                } catch (RuntimeException ignored) { }
            }
            list.sort((a, b) -> Long.compare(a.at, b.at));
            try { snaps.put(UUID.fromString(id), list); } catch (IllegalArgumentException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : snaps.entrySet()) {
            int n = 0;
            for (Snap s : e.getValue()) {
                String k = e.getKey() + "." + (n++);
                y.set(k + ".at", s.at);
                y.set(k + ".why", s.why);
                for (int i = 0; i < s.slots.length; i++) if (s.slots[i] != null) y.set(k + ".slot" + i, s.slots[i]);
            }
        }
        try { y.save(file); dirty = false; } catch (IOException ex) { plugin.getLogger().warning("[AccessoryBackups] couldn't save: " + ex.getMessage()); }
    }

    // ---------------------------------------------------------------- snapshots

    /** What they wear right now (the open /accessories menu is the live copy). */
    private ItemStack[] current(Player p) {
        ItemStack[] out = new ItemStack[FaultlineItems.AccessoryManager.SLOT_COUNT];
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof FaultlineItems.AccessoryGuiHolder h && h.live()) {
            for (int i = 0; i < FaultlineItems.AccessoryGuiHolder.SLOTS.length && i < out.length; i++) {
                ItemStack it = top.getItem(FaultlineItems.AccessoryGuiHolder.SLOTS[i]);
                out[i] = FaultlineItems.AccessoryManager.isAccessory(plugin, it) ? it.clone() : null;
            }
            return out;
        }
        ItemStack[] worn = plugin.getAccessoryManager().getSlots(p.getUniqueId());
        for (int i = 0; i < out.length && i < worn.length; i++) out[i] = worn[i] == null ? null : worn[i].clone();
        return out;
    }

    private static boolean same(ItemStack[] a, ItemStack[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null ? b[i] != null : b[i] == null || !a[i].equals(b[i])) return false;
        }
        return true;
    }

    void snapshot(Player p, String why) {
        if (!on()) return;
        try {
            List<Snap> list = snaps.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            list.add(new Snap(System.currentTimeMillis(), why, current(p)));
            while (list.size() > keep()) list.remove(0);
            dirty = true;
        } catch (RuntimeException e) { plugin.getLogger().warning("[AccessoryBackups] snapshot of " + p.getName() + ": " + e); }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onJoin(PlayerJoinEvent e) { snapshot(e.getPlayer(), "join"); }
    @EventHandler(priority = EventPriority.LOWEST) public void onQuit(PlayerQuitEvent e) { snapshot(e.getPlayer(), "quit"); if (dirty) save(); }
    @EventHandler(priority = EventPriority.LOWEST) public void onWorld(PlayerChangedWorldEvent e) { snapshot(e.getPlayer(), "world change"); }
    /** Before the accessories drop (that handler is HIGH). */
    @EventHandler(priority = EventPriority.LOWEST) public void onDeath(PlayerDeathEvent e) { snapshot(e.getEntity(), "death"); }

    /** /irp forcebackup ... : everyone online gets a snapshot, whoever it named. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (!m.matches("^/(inventoryrollbackplus:)?(irp|ir|inventoryrollback|inventoryrollbackplus)\\s+forcebackup.*")) return;
        for (Player p : Bukkit.getOnlinePlayers()) snapshot(p, "force");
    }

    // ---------------------------------------------------------------- IRP's restore button

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIrpClick(InventoryClickEvent e) {
        if (!on() || !(e.getWhoClicked() instanceof Player staff)) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() != Material.NETHER_STAR || !it.hasItemMeta()) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        String title;
        try { title = e.getView().getTitle(); } catch (RuntimeException | LinkageError ex) { return; }
        if (!IRP_MENU.equals(ChatColor.stripColor(title))) return;
        if (!staff.hasPermission("inventoryrollbackplus.restore")) return;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(new NamespacedKey(IRP, "uuid"), PersistentDataType.STRING);
        Long at = pdc.get(new NamespacedKey(IRP, "timestamp"), PersistentDataType.LONG);
        if (id == null || at == null) return;
        UUID who;
        try { who = UUID.fromString(id); } catch (IllegalArgumentException ex) { return; }
        Player target = Bukkit.getPlayer(who);
        if (target == null) return; // IRP only restores a whole inventory to someone online
        Snap s = nearest(who, at, plugin.getConfig().getLong("accessory-backups.match-seconds", 15) * 1000L);
        if (s == null) {
            staff.sendMessage(ChatColor.GRAY + "[Accessories] No accessory backup from that moment for " + target.getName()
                    + " (backups started with this update). " + ChatColor.WHITE + "/accrestore " + target.getName() + ChatColor.GRAY + " lists what there is.");
            return;
        }
        // after IRP has put the inventory back
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.isOnline()) return;
            restore(target, s);
            staff.sendMessage(ChatColor.GREEN + "[Accessories] Restored " + target.getName() + "'s accessories from that backup too: " + ChatColor.WHITE + names(s));
        }, 5L);
    }

    /** The snapshot closest to that time, within the window. */
    Snap nearest(UUID who, long at, long window) {
        List<Snap> list = snaps.get(who);
        if (list == null) return null;
        Snap best = null;
        long gap = Long.MAX_VALUE;
        for (Snap s : list) { long d = Math.abs(s.at - at); if (d < gap) { gap = d; best = s; } }
        return gap <= window ? best : null;
    }

    /** Replaces the worn accessories with the snapshot's (like IRP replaces the inventory). */
    void restore(Player p, Snap s) {
        // an open /accessories menu would save its own slots back over this when it closes
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof FaultlineItems.AccessoryGuiHolder) p.closeInventory();
        snapshot(p, "before restore"); // so a wrong restore can be undone
        ItemStack[] copy = new ItemStack[s.slots.length];
        for (int i = 0; i < copy.length; i++) copy[i] = s.slots[i] == null ? null : s.slots[i].clone();
        plugin.getAccessoryManager().setSlots(p.getUniqueId(), copy);
        save();
        p.sendMessage(ChatColor.AQUA + "Your accessories were restored by staff.");
        plugin.getLogger().info("[AccessoryBackups] restored " + p.getName() + "'s accessories from " + time(s.at) + " (" + s.why + "): " + names(s));
    }

    private static String names(Snap s) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : s.slots) if (it != null) out.add(ChatColor.stripColor(it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : it.getType().name()));
        return out.isEmpty() ? "none worn" : String.join(", ", out);
    }

    private static String time(long at) { return new SimpleDateFormat("MMM d HH:mm:ss").format(new Date(at)); }

    // ---------------------------------------------------------------- /accrestore

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.accrestore")) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
        if (a.length == 0) { s.sendMessage(ChatColor.YELLOW + "/accrestore <player> [#n]" + ChatColor.GRAY + " (no number = the list)"); return true; }
        Player online = Bukkit.getPlayerExact(a[0]);
        UUID who = online != null ? online.getUniqueId() : null;
        if (who == null) { OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[0]); if (o != null) who = o.getUniqueId(); }
        List<Snap> list = who == null ? null : snaps.get(who);
        if (list == null || list.isEmpty()) { s.sendMessage(ChatColor.GRAY + "No accessory backups for " + a[0] + "."); return true; }
        if (a.length == 1) {
            s.sendMessage(ChatColor.GOLD + "Accessory backups of " + a[0] + ChatColor.GRAY + " (newest first, /accrestore " + a[0] + " <#>):");
            int shown = 0;
            ItemStack[] last = null;
            for (int i = list.size() - 1; i >= 0 && shown < 15; i--) {
                Snap sn = list.get(i);
                if (last != null && same(last, sn.slots)) continue; // same as the newer one: skip
                last = sn.slots;
                s.sendMessage(ChatColor.YELLOW + "#" + (i + 1) + ChatColor.GRAY + " " + time(sn.at) + " (" + sn.why + "): " + ChatColor.WHITE + names(sn));
                shown++;
            }
            return true;
        }
        if (online == null) { s.sendMessage(ChatColor.RED + a[0] + " has to be online to be restored."); return true; }
        int n;
        try { n = Integer.parseInt(a[1].replace("#", "")); } catch (NumberFormatException e) { s.sendMessage(ChatColor.YELLOW + "/accrestore <player> [#n]"); return true; }
        if (n < 1 || n > list.size()) { s.sendMessage(ChatColor.RED + "There's no backup #" + n + "."); return true; }
        Snap sn = list.get(n - 1);
        restore(online, sn);
        s.sendMessage(ChatColor.GREEN + "Restored " + online.getName() + "'s accessories from " + time(sn.at) + ": " + ChatColor.WHITE + names(sn));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("items.accrestore"))
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(p.getName());
        return out;
    }
}
