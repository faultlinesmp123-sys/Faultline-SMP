package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 1.9.7: catches ROLLBACKS (a player coming back with an older copy of their save: the wipe / "put it in a shulker, relog,
 * have it twice" dupe) and the things that cause them.
 *  - When a player leaves, what they carry (inventory, armour, off hand, ender chest, counted into shulker boxes and
 *    bundles) and where they stand is written to saveguard.yml. When they join, it's compared with what the server
 *    loaded: different = their save went back in time. That's an ALERT (or a LOG line for small things) naming what
 *    "came back" and what's missing, how long they were away and how far they moved.
 *  - Their data is saved by hand on quit too, and an error there is logged in full (the real reason a save fails).
 *  - Items that can't be written to disk (a stack over 99, an enchant level over 255, a broken component) make a save
 *    fail or lose the item: they're fixed (or, when nothing helps, taken out and logged) before the save, on quit and
 *    every few minutes.
 *  - A server that didn't stop cleanly (crash, kill, power loss) is reported at the next start with who was online:
 *    everyone online then can have lost their last minutes while chests they filled were saved.
 */
final class SaveGuard implements Listener {

    private final FaultlineIndex index;
    private final File file;
    private final Map<UUID, Print> prints = new HashMap<>();
    private final Set<String> online = new LinkedHashSet<>();
    private boolean canSerialize = true;
    private int cursor;

    record Print(long at, String name, Map<String, Integer> counts, String world, double x, double y, double z) { }

    /** 1.9.12: a full copy of what each player logged off with (inventory + armour + off hand + ender chest), one file each. */
    private final File invDir;
    /** When each player's logout copy was taken: a death right after (combat-log kill) throws the copy away. */
    private final Map<UUID, Long> quitAt = new HashMap<>();

    SaveGuard(FaultlineIndex index) {
        this.index = index;
        this.file = new File(index.getDataFolder(), "saveguard.yml");
        this.invDir = new File(index.getDataFolder(), "inventories");
        load();
        Bukkit.getScheduler().runTaskTimer(index, this::sweep, 200L, 100L);
    }

    private boolean on() { return index.getConfig().getBoolean("save-guard.enabled", true); }

    // ---------------------------------------------------------------- file

    private void load() {
        if (!file.exists()) { write(false); return; }
        YamlConfiguration y = SafeYaml.load(index, file).yaml; // 1.9.10: a cut-off file is salvaged, never thrown
        boolean clean = y.getBoolean("clean", true);
        List<String> was = y.getStringList("online");
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) for (String id : ps.getKeys(false)) {
            try {
                ConfigurationSection c = ps.getConfigurationSection(id);
                Map<String, Integer> counts = new TreeMap<>();
                ConfigurationSection cs = c.getConfigurationSection("items");
                if (cs != null) for (String k : cs.getKeys(false)) counts.put(k, cs.getInt(k));
                if (System.currentTimeMillis() - c.getLong("at") > 45L * 86400000) continue; // gone 45 days: forget
                prints.put(UUID.fromString(id), new Print(c.getLong("at"), c.getString("name", "?"), counts, c.getString("world", ""), c.getDouble("x"), c.getDouble("y"), c.getDouble("z")));
            } catch (RuntimeException ignored) { }
        }
        if (!clean) {
            // whoever was online never had a clean quit: their record can't be trusted, and their save may be minutes old
            for (String entry : was) {
                int bar = entry.indexOf('|');
                try { UUID id = UUID.fromString(bar < 0 ? entry : entry.substring(0, bar)); prints.remove(id); snapFile(id).delete(); } catch (IllegalArgumentException ignored) { }
            }
            List<String> names = new ArrayList<>();
            for (String entry : was) { int bar = entry.indexOf('|'); names.add(bar < 0 ? entry : entry.substring(bar + 1)); }
            String text = "The server did NOT stop cleanly last time (a crash, a kill or a power cut)."
                    + (names.isEmpty() ? " Nobody was online." : " Online then: " + String.join(", ", names)
                    + ". Their saves can be a few minutes old while chests and shulker boxes they filled were saved: that is a rollback, and a way to dupe.")
                    + " Always stop the server with /stop.";
            index.getLogger().warning("[SaveGuard] " + text);
            Bukkit.getScheduler().runTaskLater(index, () -> { if (index.watch != null) index.watch.record(new UUID(0, 0), null, "rollback", Watch.Sev.ALERT, text, null); }, 100L);
        }
        write(false);
    }

    private void write(boolean clean) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("clean", clean);
        y.set("online", new ArrayList<>(online));
        for (var e : prints.entrySet()) {
            String k = "players." + e.getKey();
            Print p = e.getValue();
            y.set(k + ".at", p.at); y.set(k + ".name", p.name); y.set(k + ".world", p.world);
            y.set(k + ".x", p.x); y.set(k + ".y", p.y); y.set(k + ".z", p.z);
            for (var c : p.counts.entrySet()) y.set(k + ".items." + c.getKey(), c.getValue());
        }
        SafeYaml.write(index, file, y.saveToString());
    }

    /** The server is stopping: everyone online is about to be saved as they are. */
    void shutdown() {
        if (on()) for (Player p : Bukkit.getOnlinePlayers()) {
            try { repair(p); Print pr = print(p); prints.put(p.getUniqueId(), pr); snapshot(p, pr.at()); } catch (RuntimeException ignored) { }
        }
        online.clear();
        write(true);
    }

    // ---------------------------------------------------------------- what a player carries

    private static void count(ItemStack it, Map<String, Integer> into, int depth) {
        if (it == null || it.getType().isAir()) return;
        into.merge(it.getType().name(), it.getAmount(), Integer::sum);
        if (depth > 2 || !it.hasItemMeta()) return;
        try {
            ItemMeta m = it.getItemMeta();
            if (m instanceof BlockStateMeta b && b.hasBlockState() && b.getBlockState() instanceof ShulkerBox box) {
                for (ItemStack in : box.getInventory().getContents()) count(in, into, depth + 1);
            } else if (m instanceof BundleMeta bundle) {
                for (ItemStack in : bundle.getItems()) count(in, into, depth + 1);
            }
        } catch (RuntimeException ignored) { }
    }

    Print print(Player p) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack it : p.getInventory().getContents()) count(it, counts, 0);
        for (ItemStack it : p.getEnderChest().getContents()) count(it, counts, 0);
        try { count(p.getItemOnCursor(), counts, 0); } catch (RuntimeException ignored) { }
        Location l = p.getLocation();
        return new Print(System.currentTimeMillis(), p.getName(), counts, l.getWorld() == null ? "" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    // ---------------------------------------------------------------- join / quit

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        online.add(p.getUniqueId() + "|" + p.getName());
        quitAt.remove(p.getUniqueId());
        Print old = prints.remove(p.getUniqueId());
        write(false);
        if (!on() || old == null) return;
        Print now = print(p);
        if (old.counts.equals(now.counts)) { snapFile(p.getUniqueId()).delete(); return; }
        List<String> back = new ArrayList<>(), gone = new ArrayList<>();
        int gained = 0;
        boolean valuable = false;
        Set<String> keys = new java.util.TreeSet<>(old.counts.keySet());
        keys.addAll(now.counts.keySet());
        for (String k : keys) {
            int d = now.counts.getOrDefault(k, 0) - old.counts.getOrDefault(k, 0);
            if (d > 0) { back.add("+" + d + " " + nice(k)); gained += d; if (precious(k)) valuable = true; }
            else if (d < 0) gone.add(d + " " + nice(k));
        }
        long mins = Math.max(0, (System.currentTimeMillis() - old.at) / 60000);
        Location l = p.getLocation();
        double moved = old.world.equals(l.getWorld().getName()) ? Math.sqrt(sq(l.getX() - old.x) + sq(l.getY() - old.y) + sq(l.getZ() - old.z)) : -1;
        String text = Watch.b(p.getName()) + " came back with a DIFFERENT inventory than they left with (away " + mins + " min"
                + (moved < 0 ? ", in another world" : moved > 3 ? ", " + Math.round(moved) + " blocks from where they logged off" : "") + "): their save went back in time."
                + (back.isEmpty() ? "" : " Has again: " + cut(back) + ".") + (gone.isEmpty() ? "" : " Missing: " + cut(gone) + ".")
                + (back.isEmpty() ? "" : " If those went into a chest or a shulker box before they left, that's a dupe.");
        boolean restored = restore(p, old);
        boolean dupe = restored && !back.isEmpty();
        if (restored) text += " Their inventory and ender chest were PUT BACK to exactly what they logged off with" + (dupe ? " and they were kicked for the dupe." : ".");
        index.watch.record(p, "rollback", restored || valuable || gained >= 32 || !gone.isEmpty() ? Watch.Sev.ALERT : Watch.Sev.LOG, text);
        if (!restored) return;
        if (dupe && index.getConfig().getBoolean("save-guard.kick-on-dupe", true)) {
            String msg = index.getConfig().getString("save-guard.kick-message",
                    "Your inventory didn't match what you logged out with, so it was put back. Duping gets you kicked.");
            Bukkit.getScheduler().runTaskLater(index, () -> { if (p.isOnline()) p.kickPlayer(msg); }, 2L);
        } else {
            p.sendMessage(org.bukkit.ChatColor.YELLOW + "Your save didn't load right, so your inventory was put back to what you logged out with.");
        }
    }

    // ---------------------------------------------------------------- the logout copy

    private File snapFile(UUID id) { return new File(invDir, id + ".yml"); }

    /** Written straight to disk at logout (temp file + atomic move). Can't write = the old copy is deleted, never kept stale. */
    private void snapshot(Player p, long at) {
        File f = snapFile(p.getUniqueId());
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("at", at);
            y.set("name", p.getName());
            y.set("version", Bukkit.getBukkitVersion());
            y.set("inventory", java.util.Arrays.asList(p.getInventory().getContents()));
            y.set("ender-chest", java.util.Arrays.asList(p.getEnderChest().getContents()));
            SafeYaml.saveNow(y, f);
            quitAt.put(p.getUniqueId(), System.currentTimeMillis());
        } catch (Throwable ex) {
            f.delete();
            index.getLogger().warning("[SaveGuard] couldn't save " + p.getName() + "'s logout inventory (" + ex + "): no restore for them next time");
        }
    }

    /** Puts back what they logged off with. False = no usable copy (none, stale, too old, unreadable). */
    private boolean restore(Player p, Print old) {
        if (!index.getConfig().getBoolean("save-guard.restore", true)) return false;
        File f = snapFile(p.getUniqueId());
        if (!f.exists()) return false;
        try {
            YamlConfiguration y = SafeYaml.load(index, f).yaml;
            long at = y.getLong("at");
            long maxAge = index.getConfig().getLong("save-guard.restore-max-days", 14) * 86400000L;
            if (at != old.at() || System.currentTimeMillis() - at > maxAge) { f.delete(); return false; }
            // after a Minecraft update items can be renamed or changed: a copy from another version is never restored
            if (!Bukkit.getBukkitVersion().equals(y.getString("version", Bukkit.getBukkitVersion()))) { f.delete(); return false; }
            ItemStack[] inv = items(y.getList("inventory"), p.getInventory().getSize());
            ItemStack[] ec = items(y.getList("ender-chest"), p.getEnderChest().getSize());
            if (inv == null || ec == null) return false;
            p.setItemOnCursor(null);
            p.getInventory().setContents(inv);
            p.getEnderChest().setContents(ec);
            p.updateInventory();
            try { p.saveData(); } catch (Throwable ignored) { }
            f.delete();
            index.getLogger().info("[SaveGuard] put back " + p.getName() + "'s logout inventory");
            return true;
        } catch (Throwable ex) {
            index.getLogger().warning("[SaveGuard] couldn't put back " + p.getName() + "'s inventory: " + ex);
            return false;
        }
    }

    private static ItemStack[] items(List<?> l, int size) {
        if (l == null || l.size() != size) return null;
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) out[i] = l.get(i) instanceof ItemStack it ? it : null;
        return out;
    }

    /** Killed while logging off (DonutCombat's combat-log kill): their items dropped, so the logout copy is wrong. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        Long q = quitAt.get(id);
        if (q != null && System.currentTimeMillis() - q < 2000) { snapFile(id).delete(); prints.remove(id); quitAt.remove(id); write(false); }
    }

    private static double sq(double v) { return v * v; }
    private static String nice(String k) { return k.toLowerCase().replace('_', ' '); }
    private static String cut(List<String> l) { return l.size() <= 10 ? String.join(", ", l) : String.join(", ", l.subList(0, 10)) + " and " + (l.size() - 10) + " more"; }

    private static boolean precious(String k) {
        return k.contains("DIAMOND") || k.contains("NETHERITE") || k.contains("EMERALD") || k.contains("SHULKER") || k.contains("ELYTRA") || k.contains("TOTEM")
                || k.contains("ENCHANTED") || k.contains("BEACON") || k.contains("GOLDEN_APPLE") || k.contains("DEBRIS") || k.contains("HEAD") || k.contains("MACE") || k.contains("NETHER_STAR");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        online.remove(p.getUniqueId() + "|" + p.getName());
        if (on()) {
            try { repair(p); } catch (RuntimeException ex) { index.getLogger().warning("[SaveGuard] checking " + p.getName() + "'s items: " + ex); }
            if (index.getConfig().getBoolean("save-guard.save-on-quit", true)) {
                try { p.saveData(); }
                catch (Throwable ex) {
                    String why = ex + (ex.getCause() != null ? " (" + ex.getCause() + ")" : "");
                    if (!why.contains("Unimplemented"))
                        index.watch.record(p, "rollback", Watch.Sev.ALERT, Watch.b(p.getName()) + "'s data could NOT be saved when they left: " + why + ". They will come back with an old save.");
                }
            }
            // 1.9.12: the copy is taken a tick later, once every plugin's quit code has run (race items taken back, boss form
            // undone, DonutCombat's combat-log kill): what's left is exactly what the game writes to their save.
            if (index.isEnabled()) Bukkit.getScheduler().runTask(index, () -> {
                if (p.isOnline()) return; // already back (can't really happen in one tick)
                Print pr = print(p);
                prints.put(p.getUniqueId(), pr);
                snapshot(p, pr.at());
                write(false);
            });
        }
        write(false);
    }

    // ---------------------------------------------------------------- items that can't be saved

    /** Every 5 s one online player's items are checked, so a bad item is caught long before they log off. */
    private void sweep() {
        if (!on()) return;
        List<? extends Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (all.isEmpty()) return;
        Player p = all.get(Math.floorMod(cursor++, all.size()));
        try { repair(p); } catch (RuntimeException ex) { index.getLogger().warning("[SaveGuard] checking " + p.getName() + "'s items: " + ex); }
    }

    /** Fixes or removes what the game can't write to disk. Returns how many items it touched. */
    int repair(Player p) {
        if (!index.getConfig().getBoolean("save-guard.fix-unsaveable", true)) return 0;
        int n = fix(p, p.getInventory(), "inventory");
        n += fix(p, p.getEnderChest(), "ender chest");
        return n;
    }

    private int fix(Player p, Inventory inv, String where) {
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType().isAir()) continue;
            String problem = null;
            if (it.getAmount() > 99) { // the game refuses to write a stack over 99
                problem = "a stack of " + it.getAmount();
                it.setAmount(Math.min(99, Math.max(1, it.getMaxStackSize())));
                inv.setItem(i, it);
            }
            if (levels(it)) { problem = (problem == null ? "" : problem + ", ") + "an enchant level over 255"; inv.setItem(i, it); }
            // only things that can hide a broken component are worth the real test
            if (canSerialize && (problem != null || it.hasItemMeta())) {
                String err = unsaveable(it);
                if (err != null) {
                    inv.setItem(i, null);
                    n++;
                    index.watch.record(p, "unsaveable", Watch.Sev.ALERT, Watch.b(p.getName()) + " carried an item the game can't save (" + it.getType().name().toLowerCase()
                            + " x" + it.getAmount() + " in their " + where + ": " + err + "). It was taken out so the rest of their save isn't lost. Item: " + describe(it));
                    continue;
                }
            }
            if (problem != null) {
                n++;
                index.watch.record(p, "unsaveable", Watch.Sev.LOG, Watch.b(p.getName()) + " had " + it.getType().name().toLowerCase() + " with " + problem
                        + " in their " + where + " (the game can't save that): fixed.");
            }
        }
        return n;
    }

    /** Clamps enchant levels the game can't store. True = something changed. */
    private static boolean levels(ItemStack it) {
        if (!it.hasItemMeta()) return false;
        boolean changed = false;
        try {
            ItemMeta m = it.getItemMeta();
            for (var e : new HashMap<>(m.getEnchants()).entrySet()) if (e.getValue() > 255) { m.removeEnchant(e.getKey()); m.addEnchant(e.getKey(), 255, true); changed = true; }
            if (m instanceof EnchantmentStorageMeta s)
                for (Map.Entry<Enchantment, Integer> e : new HashMap<>(s.getStoredEnchants()).entrySet())
                    if (e.getValue() > 255) { s.removeStoredEnchant(e.getKey()); s.addStoredEnchant(e.getKey(), 255, true); changed = true; }
            if (changed) it.setItemMeta(m);
        } catch (RuntimeException ignored) { }
        return changed;
    }

    /** Null = it saves fine; else why not. */
    private String unsaveable(ItemStack it) {
        try {
            byte[] raw = it.serializeAsBytes();
            // 1.9.8: it must also READ back. A file that saves but can't be loaded makes the game fall back to the
            // player's previous save (.dat_old) = a rollback, or start them fresh at spawn.
            ItemStack back = ItemStack.deserializeBytes(raw);
            if (back == null || back.getType() != it.getType()) return "it doesn't read back as the same item";
            return null;
        }
        catch (Throwable t) {
            String s = String.valueOf(t);
            if (t instanceof LinkageError || s.contains("Unimplemented") || t instanceof UnsupportedOperationException) { canSerialize = false; return null; }
            return s.length() > 200 ? s.substring(0, 200) : s;
        }
    }

    private static String describe(ItemStack it) {
        try { String s = String.valueOf(it); return s.length() > 300 ? s.substring(0, 300) + "..." : s; } catch (RuntimeException e) { return it.getType().name(); }
    }
}
