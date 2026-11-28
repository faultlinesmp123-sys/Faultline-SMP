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
    /** id -> "uuid;name" of whoever last had it in their inventory (for /macelimit take on someone offline). */
    final Map<Long, String> holders = new HashMap<>();
    /** Ids staff removed: any mace carrying one is deleted wherever it turns up (offline players, pickups). */
    final Set<Long> revoked = new HashSet<>();

    MaceLimit(FaultlineItems plugin) {
        this.plugin = plugin;
        idKey = new NamespacedKey(plugin, "mace_id");
        file = new File(plugin.getDataFolder(), "maces.yml");
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        if (y.isConfigurationSection("maces")) for (String k : y.getConfigurationSection("maces").getKeys(false)) {
            try { maces.put(Long.parseLong(k), y.getString("maces." + k, "")); } catch (NumberFormatException ignored) { }
        }
        if (y.isConfigurationSection("holders")) for (String k : y.getConfigurationSection("holders").getKeys(false)) {
            try { holders.put(Long.parseLong(k), y.getString("holders." + k, "")); } catch (NumberFormatException ignored) { }
        }
        for (String k : y.getStringList("revoked")) { try { revoked.add(Long.parseLong(k)); } catch (NumberFormatException ignored) { } }
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 60L, 20L); // every second: an extra mace never lasts
    }

    boolean on() { return plugin.getConfig().getBoolean("mace-limit.enabled", true); }
    int max() { return Math.max(0, plugin.getConfig().getInt("mace-limit.max", 5)); }
    boolean full() { return on() && maces.size() >= max(); }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : maces.entrySet()) y.set("maces." + e.getKey(), e.getValue());
        for (var e : holders.entrySet()) if (maces.containsKey(e.getKey())) y.set("holders." + e.getKey(), e.getValue());
        List<String> rv = new ArrayList<>(); for (Long id : revoked) rv.add(String.valueOf(id));
        y.set("revoked", rv);
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
        // AUDIT: a creative player's middle-click copy keeps the mace id, so the copy looked like the same registered mace
        // forever (a free extra one once it reached a survival inventory). The same id seen twice in one pass = the second
        // is a copy: it gets a new id and counts like any other mace.
        Set<Long> seen = new HashSet<>();
        boolean changed = trimToMax();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!revoked.isEmpty()) strip(p, false, false); // staff removed it (works in creative too)
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            ItemStack[] c = p.getInventory().getContents();
            int killed = 0;
            for (int i = 0; i < c.length; i++) {
                ItemStack it = c[i];
                if (extra(it, seen)) { p.getInventory().setItem(i, null); killed++; continue; }
                if (fix(it, p, seen)) p.getInventory().setItem(i, it);
                changed |= hold(it, p);
            }
            ItemStack cur = p.getItemOnCursor();
            if (extra(cur, seen)) { p.setItemOnCursor(null); killed++; }
            else {
                if (fix(cur, p, seen)) p.setItemOnCursor(cur);
                changed |= hold(cur, p);
            }
            if (killed > 0) {
                p.sendMessage(ChatColor.RED + "All " + max() + " maces already exist, so that mace was deleted.");
                plugin.getLogger().warning("[MaceLimit] deleted " + killed + " extra mace(s) from " + p.getName() + " (limit " + maces.size() + "/" + max() + " full)");
            }
        }
        if (changed) save();
    }

    /**
     * A mace that isn't one of the registered ones (no id, an id that isn't in the list, or a second copy of an id) while
     * the limit is full: it can't exist, so the sweep deletes it.
     */
    boolean extra(ItemStack it, Set<Long> seen) {
        if (!mace(it) || !full()) return false;
        Long id = idOf(it);
        if (id == null || !maces.containsKey(id)) return true;
        return seen.contains(id);
    }

    /**
     * More maces registered than the limit (old maces from before it, a lowered max): the FIRST ones registered keep their
     * place, the newest are revoked = deleted wherever they turn up. True = the list changed.
     */
    boolean trimToMax() {
        if (!on() || maces.size() <= max()) return false;
        List<Long> ids = new ArrayList<>(maces.keySet());
        int n = 0;
        for (int i = ids.size() - 1; i >= 0 && maces.size() > max(); i--) {
            Long id = ids.get(i);
            String who = holders.getOrDefault(id, maces.get(id));
            revoked.add(id);
            free(id);
            n++;
            plugin.getLogger().warning("[MaceLimit] over the limit: mace #" + id + " (" + who + ") revoked, " + maces.size() + "/" + max());
        }
        if (n > 0) Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + n + " extra mace" + (n == 1 ? "" : "s") + " over the limit of " + max() + " were deleted.");
        return n > 0;
    }

    /** Remembers who has this mace now; true = that changed. */
    boolean hold(ItemStack it, Player p) {
        Long id = idOf(it);
        if (id == null || !maces.containsKey(id)) return false;
        String v = p.getUniqueId() + ";" + p.getName();
        return !v.equals(holders.put(id, v));
    }

    boolean gone(ItemStack it) { Long id = idOf(it); return id != null && revoked.contains(id); }

    /**
     * Deletes the player's maces: every one when {@code all} (/macelimit take), else only revoked ones. Looks in the
     * inventory (armour + off hand), cursor, ender chest, the open container and inside shulker boxes / bundles they carry.
     * Every mace deleted frees its place. Returns how many went.
     */
    int strip(Player p, boolean all) { return strip(p, all, true); }

    /** {@code deep} = also open shulker boxes / bundles (the 5 s sweep skips them: MaceRules moves maces out of those). */
    int strip(Player p, boolean all, boolean deep) {
        int n = 0;
        n += stripInv(p.getInventory(), all, deep);
        n += stripInv(p.getEnderChest(), all, deep);
        org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
        if (top != null && top != p.getInventory() && !(top instanceof org.bukkit.inventory.CraftingInventory)) n += stripInv(top, all, deep);
        ItemStack cur = p.getItemOnCursor();
        if (mace(cur) && (all || gone(cur))) { free(idOf(cur)); p.setItemOnCursor(null); n++; }
        if (n > 0) { save(); plugin.getLogger().info("[MaceLimit] removed " + n + " mace(s) from " + p.getName() + ", " + maces.size() + "/" + max()); }
        return n;
    }

    int stripInv(org.bukkit.inventory.Inventory inv, boolean all, boolean deep) {
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null) continue;
            if (mace(it)) { if (all || gone(it)) { free(idOf(it)); inv.setItem(i, null); n++; } continue; }
            if (!deep || !(it.getType().name().endsWith("SHULKER_BOX") || it.getType().name().endsWith("BUNDLE"))) continue;
            if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta) && !(it.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta)) continue;
            ItemMeta m = it.getItemMeta();
            int k = 0;
            if (m instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
                k = stripInv(box.getInventory(), all, true);
                if (k > 0) { bsm.setBlockState(box); it.setItemMeta(bsm); inv.setItem(i, it); }
            } else if (m instanceof org.bukkit.inventory.meta.BundleMeta bm) {
                List<ItemStack> keep = new ArrayList<>();
                for (ItemStack x : bm.getItems()) { if (mace(x) && (all || gone(x))) { free(idOf(x)); k++; } else keep.add(x); }
                if (k > 0) { bm.setItems(keep); it.setItemMeta(bm); inv.setItem(i, it); }
            }
            n += k;
        }
        return n;
    }

    /** A mace place freed (the id stays revoked so a copy elsewhere is deleted too). */
    void free(Long id) {
        if (id == null) return;
        maces.remove(id);
        holders.remove(id);
    }

    /** Registers / re-ids one mace in a sweep; true = the item was changed. */
    boolean fix(ItemStack it, Player p, Set<Long> seen) {
        if (it == null || it.getType() != Material.MACE) return false;
        Long id = idOf(it);
        if (id != null && !seen.add(id)) { // a second copy of an id
            ItemMeta m = it.getItemMeta();
            m.getPersistentDataContainer().remove(idKey);
            it.setItemMeta(m);
            plugin.getLogger().warning("[MaceLimit] " + p.getName() + " had a second mace with id #" + id + ": it gets its own id and counts");
            register(it, p.getName());
            seen.add(idOf(it));
            return true;
        }
        if (id != null && maces.containsKey(id)) return false;
        register(it, p.getName());
        seen.add(idOf(it));
        return true;
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
            // two maces in (the grid's repair recipe): every other mace that went in frees its place once the craft happened
            List<Long> others = new ArrayList<>();
            for (ItemStack it : e.getInventory().getMatrix()) { Long id = idOf(it); if (id != null && !id.equals(from)) others.add(id); }
            if (!others.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> {
                if (from > 0 && !holds(p, from)) return; // the craft didn't go through
                boolean changed = false;
                for (Long id : others) if (!holds(p, id) && maces.remove(id) != null) changed = true;
                if (changed) { save(); plugin.getLogger().info("[MaceLimit] maces " + others + " merged into #" + from + ", " + maces.size() + "/" + max()); }
            });
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
        Long made = idOf(out);
        // AUDIT: a click that doesn't take the result (cursor busy...) still fires this event: check a tick later that the
        // mace really reached them, else its place is given back
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (made == null) return;
            if (!holds(p, made)) {
                if (maces.remove(made) != null) { save(); plugin.getLogger().info("[MaceLimit] mace #" + made + " wasn't really crafted, place freed"); }
                return;
            }
            announceForge(p, max() - maces.size());
        });
    }

    /** Everyone hears about a new mace: chat line, a title, a sound (each can be turned off under mace-limit.announce). */
    void announceForge(Player p, int left) {
        String tail = left <= 0 ? "That was the last one." : left + (left == 1 ? " mace left." : " maces left.");
        String path = "mace-limit.announce.";
        if (plugin.getConfig().getBoolean(path + "chat", true))
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "A mace has been forged" + ChatColor.GRAY + " by "
                    + ChatColor.WHITE + p.getName() + ChatColor.GRAY + ". " + tail);
        boolean title = plugin.getConfig().getBoolean(path + "title", true);
        boolean sound = plugin.getConfig().getBoolean(path + "sound", true);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (title) o.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "A MACE HAS BEEN FORGED",
                    ChatColor.GRAY + "by " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " · " + tail, 10, 60, 20);
            if (sound) {
                o.playSound(o.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, org.bukkit.SoundCategory.MASTER, 1f, 0.6f);
                o.playSound(o.getLocation(), org.bukkit.Sound.ITEM_MACE_SMASH_GROUND_HEAVY, org.bukkit.SoundCategory.MASTER, 1f, 0.8f);
            }
        }
        plugin.getLogger().info("[MaceLimit] " + p.getName() + " forged a mace, " + maces.size() + "/" + max());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (on() && mace(e.getResult())) e.setCancelled(true); // only by hand, so the count stays right
    }

    /** This mace id is on the player's cursor, in their inventory, or still in the open crafting view. */
    boolean holds(Player p, long id) {
        if (!p.isOnline()) return true; // can't tell: keep it
        Long c = idOf(p.getItemOnCursor());
        if (c != null && c == id) return true;
        for (ItemStack it : p.getInventory().getContents()) { Long x = idOf(it); if (x != null && x == id) return true; }
        org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
        // a crafting grid's RESULT slot doesn't count (an untaken result is exactly the craft that didn't happen)
        ItemStack[] in = top instanceof org.bukkit.inventory.CraftingInventory ci ? ci.getMatrix() : top == null ? new ItemStack[0] : top.getContents();
        for (ItemStack it : in) { Long x = idOf(it); if (x != null && x == id) return true; }
        return false;
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
        if (gone == null || !maces.containsKey(gone) || !(e.getWhoClicked() instanceof Player p)) return;
        // AUDIT: a click that doesn't take the result (not enough levels, cursor busy) still fires: check a tick later that
        // the second mace really went (not still in the anvil, the inventory or on the cursor)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (holds(p, gone) || maces.remove(gone) == null) return;
            save();
            plugin.getLogger().info("[MaceLimit] mace #" + gone + " was merged into #" + keep + ", " + maces.size() + "/" + max());
        });
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

    /** A removed mace picked up (dropped before staff took it, or a dupe) is deleted on the spot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (!mace(it)) return;
        boolean extra = on() && full() && (idOf(it) == null || !maces.containsKey(idOf(it)))
                && !(e.getEntity() instanceof Player pp && (pp.getGameMode() == GameMode.CREATIVE || pp.getGameMode() == GameMode.SPECTATOR));
        if (!extra && (revoked.isEmpty() || !gone(it))) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        if (revoked.isEmpty()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && strip(p, false) > 0) p.sendMessage(ChatColor.RED + "Staff removed your mace.");
        }, 20L);
    }

    // ------------------------------------------------------------------ /macelimit

    void removeId(CommandSender s, long id) {
        String h = holders.get(id);
        revoked.add(id);
        free(id);
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) { int k = strip(p, false); if (k > 0) { n += k; p.sendMessage(ChatColor.RED + "Staff removed your mace."); } }
        save();
        String who = h == null ? "?" : h.split(";", 2)[h.contains(";") ? 1 : 0];
        s.sendMessage(ChatColor.GREEN + "Mace #" + id + " (" + who + ") removed" + (n > 0 ? "" : ": it's deleted wherever it turns up") + ". Maces: " + maces.size() + "/" + max());
        announceRemoved(who);
    }

    void announceRemoved(String who) {
        if (!plugin.getConfig().getBoolean("mace-limit.announce.chat", true)) return;
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "A mace was taken from " + ChatColor.WHITE + who + ChatColor.DARK_RED + " by staff. "
                + ChatColor.GRAY + "One can be forged again.");
    }

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
            case "take", "remove" -> { // /macelimit take <player>: delete their mace(s), online or off
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "/macelimit take <player>   (or /macelimit remove <id>)"); return true; }
                Long byId = null;
                try { byId = Long.parseLong(a[1].replace("#", "")); } catch (NumberFormatException ignored) { }
                if (byId != null && (maces.containsKey(byId) || holders.containsKey(byId))) { removeId(s, byId); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) {
                    int n = strip(t, true);
                    // anything registered to them that wasn't on them (dropped / hidden) is deleted when it turns up
                    for (var e : new ArrayList<>(holders.entrySet())) if (e.getValue().startsWith(t.getUniqueId() + ";")) { revoked.add(e.getKey()); free(e.getKey()); n++; }
                    save();
                    if (n == 0) { s.sendMessage(ChatColor.GRAY + t.getName() + " has no mace."); return true; }
                    t.sendMessage(ChatColor.RED + "Staff removed your mace.");
                    s.sendMessage(ChatColor.GREEN + "Removed " + t.getName() + "'s mace" + (n == 1 ? "" : "s (" + n + ")") + ". Maces: " + maces.size() + "/" + max());
                    announceRemoved(t.getName());
                    return true;
                }
                int n = 0;
                for (var e : new ArrayList<>(holders.entrySet())) {
                    String[] v = e.getValue().split(";", 2);
                    if (v.length == 2 && v[1].equalsIgnoreCase(a[1])) { revoked.add(e.getKey()); free(e.getKey()); n++; }
                }
                if (n == 0) { s.sendMessage(ChatColor.RED + "No mace known for " + a[1] + " (they have to be online, or have held it since this update)."); return true; }
                save();
                s.sendMessage(ChatColor.GREEN + "Removed " + a[1] + "'s mace" + (n == 1 ? "" : "s (" + n + ")") + ": it's deleted the moment they log in. Maces: " + maces.size() + "/" + max());
                announceRemoved(a[1]);
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
                    String h = holders.get(e.getKey());
                    s.sendMessage(ChatColor.GRAY + " #" + e.getKey() + ChatColor.WHITE + " made/first held by " + v[0]
                            + (h == null ? "" : ChatColor.GRAY + ", now " + ChatColor.WHITE + h.split(";", 2)[1]));
                }
                s.sendMessage(ChatColor.DARK_GRAY + "/macelimit take <player>, remove <id>, free <id|all>, max <n>");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("items.macelimit")) return List.of();
        if (a.length == 1) return List.of("list", "take", "remove", "free", "max");
        if (a.length == 2 && a[0].equalsIgnoreCase("take")) {
            Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
            for (String v : holders.values()) { String[] x = v.split(";", 2); if (x.length == 2) out.add(x[1]); }
            return new ArrayList<>(out);
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("remove")) { List<String> out = new ArrayList<>(); for (Long id : maces.keySet()) out.add(String.valueOf(id)); return out; }
        if (a.length == 2 && a[0].equalsIgnoreCase("free")) {
            List<String> out = new ArrayList<>(List.of("all"));
            for (Long id : maces.keySet()) out.add(String.valueOf(id));
            return out;
        }
        return List.of();
    }
}
