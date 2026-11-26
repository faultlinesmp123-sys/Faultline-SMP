package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

/**
 * 2.0 MACE LIMIT (the owner: only 5 maces on the server). Config mace-limit: (enabled, max 5), data maces.yml.
 * Every mace gets an id (item PDC faultlineitems:mace_id, a random long) and is registered with who made / last held it.
 * Crafting a mace is refused once {@code max} are registered (the crafting grid shows nothing, the Crafter block won't make
 * one, no shift-click crafting); a mace that's DESTROYED (EntityRemoveEvent DEATH / DESPAWN / OUT_OF_WORLD on its item:
 * lava, fire, cactus, explosions, the void, despawning) frees its place. Maces from before this get an id the first time
 * they're seen in a survival inventory and count too (nobody loses theirs, but no new one is made while there are 5+).
 * MaceRules' restrict mode keeps every mace in its owner's inventory. /macelimit (items.macelimit, op):
 * list | free <id|all> | max <n>.
 */
final class MaceLimit implements Listener, TabExecutor {
    final FaultlineItems plugin;
    final NamespacedKey idKey;
    final File file;
    /** id -> "holder name;time first seen". */
    final Map<Long, String> maces = new LinkedHashMap<>();
    final Random random = new Random();

    MaceLimit(FaultlineItems plugin) {
        this.plugin = plugin;
        idKey = new NamespacedKey(plugin, "mace_id");
        file = new File(plugin.getDataFolder(), "maces.yml");
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        if (y.isConfigurationSection("maces")) for (String k : y.getConfigurationSection("maces").getKeys(false)) {
            try { maces.put(Long.parseLong(k), y.getString("maces." + k, "")); } catch (NumberFormatException ignored) { }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 60L, 100L);
    }

    boolean on() { return plugin.getConfig().getBoolean("mace-limit.enabled", true); }
    int max() { return Math.max(0, plugin.getConfig().getInt("mace-limit.max", 5)); }
    boolean full() { return on() && maces.size() >= max(); }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : maces.entrySet()) y.set("maces." + e.getKey(), e.getValue());
        SafeYaml.write(plugin, file, y.saveToString());
    }

    Long idOf(ItemStack it) {
        if (it == null || it.getType() != Material.MACE) return null;
        return it.getPersistentDataContainer().get(idKey, PersistentDataType.LONG);
    }

    long newId() { long id; do { id = random.nextLong() & Long.MAX_VALUE; } while (id == 0 || maces.containsKey(id)); return id; }

    /** Puts an id on an untagged mace (in place) and registers it. */
    void register(ItemStack it, String who) {
        Long id = idOf(it);
        if (id == null) {
            id = newId();
            ItemMeta m = it.getItemMeta();
            m.getPersistentDataContainer().set(idKey, PersistentDataType.LONG, id);
            it.setItemMeta(m);
        }
        if (!maces.containsKey(id)) {
            maces.put(id, who + ";" + System.currentTimeMillis());
            save();
            plugin.getLogger().info("[MaceLimit] mace #" + id + " registered (" + who + "), " + maces.size() + "/" + max());
        }
    }

    /** Every survival player's maces (inventory + cursor) get an id; old ones from before the limit are counted. */
    void sweep() {
        if (!on()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            ItemStack[] c = p.getInventory().getContents();
            for (int i = 0; i < c.length; i++) {
                ItemStack it = c[i];
                if (it == null || it.getType() != Material.MACE) continue;
                Long id = idOf(it);
                if (id != null && maces.containsKey(id)) continue;
                register(it, p.getName());
                p.getInventory().setItem(i, it);
            }
        }
    }

    // ------------------------------------------------------------------ crafting

    static boolean mace(ItemStack it) { return it != null && it.getType() == Material.MACE; }

    /** The registered mace in a crafting grid (Stormbreaker is made FROM a mace: it takes that mace's place, no new one). */
    Long inGrid(ItemStack[] matrix) {
        if (matrix == null) return null;
        for (ItemStack it : matrix) { Long id = idOf(it); if (id != null) return id; }
        for (ItemStack it : matrix) if (mace(it)) return -1L; // an untagged old mace: still a mace going in
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!on() || !mace(e.getInventory().getResult())) return;
        if (inGrid(e.getInventory().getMatrix()) != null) return; // a mace made from a mace: the count doesn't change
        if (full()) {
            e.getInventory().setResult(null);
            if (e.getView().getPlayer() instanceof Player p)
                p.sendActionBar(ChatColor.RED + "All " + max() + " maces on the server have been made. " + ChatColor.GRAY + "No more can be crafted.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!on() || !mace(e.getRecipe().getResult()) && !mace(e.getCurrentItem())) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        Long from = inGrid(e.getInventory().getMatrix());
        if (from != null) { // Stormbreaker & co.: the new mace takes the old one's id and place
            if (e.isShiftClick()) { e.setCancelled(true); return; }
            ItemStack out = e.getCurrentItem() == null ? null : e.getCurrentItem().clone();
            if (out == null) return;
            if (from > 0) {
                ItemMeta m = out.getItemMeta();
                m.getPersistentDataContainer().set(idKey, PersistentDataType.LONG, from);
                out.setItemMeta(m);
                e.setCurrentItem(out);
            } // an untagged one: the sweep gives the result an id and counts it, like the old mace would have been
            return;
        }
        if (full()) { e.setCancelled(true); p.sendActionBar(ChatColor.RED + "All " + max() + " maces on the server have been made."); return; }
        if (e.isShiftClick() || e.getClick().isKeyboardClick()) { // could make several at once
            e.setCancelled(true);
            p.sendActionBar(ChatColor.RED + "Click the mace out normally " + ChatColor.GRAY + "(one at a time).");
            return;
        }
        ItemStack out = e.getCurrentItem() == null ? null : e.getCurrentItem().clone();
        if (out == null) { e.setCancelled(true); return; }
        register(out, p.getName());
        e.setCurrentItem(out);
        int left = max() - maces.size();
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "A mace has been forged" + ChatColor.GRAY + " by " + p.getName()
                + ". " + (left <= 0 ? "That was the last one." : left + " left."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (on() && mace(e.getResult())) e.setCancelled(true); // only by hand, so the count stays right
    }

    // ------------------------------------------------------------------ two maces into one (anvil / grindstone)

    /** Taking the result of an anvil or grindstone that ate a second mace: that second mace's place is free again. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!on()) return;
        org.bukkit.inventory.Inventory top = e.getView().getTopInventory();
        if (!(top instanceof org.bukkit.inventory.AnvilInventory) && !(top instanceof org.bukkit.inventory.GrindstoneInventory)) return;
        if (e.getRawSlot() != 2 || !mace(e.getCurrentItem())) return;
        Long keep = idOf(e.getCurrentItem());
        Long a = idOf(top.getItem(0)), b = idOf(top.getItem(1));
        Long gone = a != null && !a.equals(keep) ? a : b != null && !b.equals(keep) ? b : null;
        if (gone == null || !maces.containsKey(gone)) return;
        maces.remove(gone);
        save();
        plugin.getLogger().info("[MaceLimit] mace #" + gone + " was merged into #" + keep + ", " + maces.size() + "/" + max());
    }

    // ------------------------------------------------------------------ destroyed

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        Long id = idOf(item.getItemStack());
        if (id == null || !maces.containsKey(id)) return;
        EntityRemoveEvent.Cause c = e.getCause();
        if (c != EntityRemoveEvent.Cause.DEATH && c != EntityRemoveEvent.Cause.DESPAWN && c != EntityRemoveEvent.Cause.OUT_OF_WORLD) return;
        maces.remove(id);
        save();
        plugin.getLogger().info("[MaceLimit] mace #" + id + " was destroyed (" + c + "), " + maces.size() + "/" + max());
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "A mace was destroyed. " + ChatColor.GRAY + "One can be forged again.");
    }

    // ------------------------------------------------------------------ /macelimit

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.macelimit")) { s.sendMessage(ChatColor.GRAY + "Maces on the server: " + maces.size() + "/" + max()); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "free" -> {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "/macelimit free <id|all>"); return true; }
                if (a[1].equalsIgnoreCase("all")) { maces.clear(); save(); s.sendMessage(ChatColor.GREEN + "Every mace place freed (maces still out there are counted again when seen)."); return true; }
                try {
                    long id = Long.parseLong(a[1].replace("#", ""));
                    s.sendMessage(maces.remove(id) != null ? ChatColor.GREEN + "Freed mace #" + id + "." : ChatColor.RED + "No mace #" + id + ".");
                    save();
                } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Not an id: " + a[1]); }
            }
            case "max" -> {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "/macelimit max <n>"); return true; }
                try {
                    plugin.getConfig().set("mace-limit.max", Integer.parseInt(a[1]));
                    plugin.saveConfig();
                    s.sendMessage(ChatColor.GREEN + "Mace limit is now " + max() + ".");
                } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Not a number: " + a[1]); }
            }
            default -> {
                s.sendMessage(ChatColor.GOLD + "Maces: " + maces.size() + "/" + max() + (on() ? "" : ChatColor.GRAY + " (limit off)"));
                for (var e : maces.entrySet()) {
                    String[] v = e.getValue().split(";", 2);
                    s.sendMessage(ChatColor.GRAY + " #" + e.getKey() + ChatColor.WHITE + " made/first held by " + v[0]);
                }
                s.sendMessage(ChatColor.DARK_GRAY + "/macelimit free <id|all>, /macelimit max <n>");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.macelimit")) return List.of();
        if (a.length == 1) return List.of("list", "free", "max");
        if (a.length == 2 && a[0].equalsIgnoreCase("free")) {
            List<String> out = new ArrayList<>(List.of("all"));
            for (Long id : maces.keySet()) out.add(String.valueOf(id));
            return out;
        }
        return List.of();
    }
}
