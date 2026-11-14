package net.faultlinesmp.index;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 1.9.13 DUPE GUARD: every valuable one-of-a-kind item (stack size 1: shulker boxes, netherite / diamond gear, elytra, maces,
 * tridents, totems, anything enchanted, every item one of our plugins made) carries a serial number (item PDC
 * {@code faultlineindex:uid}). The same serial in two places at once = a copy:
 * <ul>
 * <li>seen twice in one player's inventory + ender chest + cursor, or twice inside one container;</li>
 * <li>seen somewhere new while the place it was last seen (an online player, a loaded container, a chest minecart / donkey)
 *     STILL holds it (checked live at that moment, so moving an item never counts).</li>
 * </ul>
 * The newer copy is deleted (also from inside shulker boxes / bundles), staff get an ALERT, and a player holding the copy is
 * kicked ({@code dupe-guard.kick}). Where each serial was last seen is kept in dupeguard.yml, so a copy turning up days later
 * is still caught. Stackables (player heads, string...) can't carry a serial: string dupers keep working; heads are covered
 * by SaveGuard's logout copies and the Watch's value spikes.
 * <p>
 * Never a false kick: creative / spectator players aren't scanned, and their items get NEW serials when they leave creative
 * (middle-click clones); clones made by plugins on purpose (mimic drops, any mob's death drops) get new serials; staff with
 * {@code faultlineindex.dupeguard.exempt} (op) and anyone during an InventoryRollbackPlus restore (15 s after a staff click in a
 * "Backup" menu) just get a new serial on the second copy instead of losing it.
 */
final class DupeGuard implements Listener, CommandExecutor {

    private final FaultlineIndex index;
    private final NamespacedKey UID;
    private final File file;
    /** serial -> "holder|time". Holder: p:<uuid> (inventory + ender chest + cursor), c:<world>;x;y;z, m:<entity uuid>. */
    private final Map<String, String[]> seen = new HashMap<>();
    private boolean dirty;
    private long graceUntil;
    private int caught;
    private int cursor;
    private final List<String> configWarnings = new ArrayList<>();

    DupeGuard(FaultlineIndex index) {
        this.index = index;
        this.UID = new NamespacedKey(index, "uid");
        this.file = new File(index.getDataFolder(), "dupeguard.yml");
        load();
        auditPaperConfig();
        Bukkit.getScheduler().runTaskTimer(index, this::tick, 40L, 1L);
        Bukkit.getScheduler().runTaskTimer(index, () -> { if (dirty) save(); }, 6000L, 6000L);
    }

    boolean on() { return index.getConfig().getBoolean("dupe-guard.enabled", true); }

    // ---------------------------------------------------------------- what's tracked

    private static final Set<String> VALUABLE = Set.of("ELYTRA", "MACE", "TRIDENT", "TOTEM_OF_UNDYING", "HEAVY_CORE", "BUNDLE");

    boolean tracked(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getAmount() != 1) return false;
        if (it.getMaxStackSize() != 1) return false;
        String n = it.getType().name();
        if (n.endsWith("SHULKER_BOX") || n.startsWith("NETHERITE_") || n.startsWith("DIAMOND_") || VALUABLE.contains(n) || n.endsWith("_BUNDLE")) return true;
        if (!it.hasItemMeta()) return false;
        if (!it.getEnchantments().isEmpty() || it.getType() == Material.ENCHANTED_BOOK) return true;
        try {
            for (NamespacedKey k : it.getPersistentDataContainer().getKeys())
                if (k.getNamespace().startsWith("faultline") || k.getNamespace().startsWith("limbo")) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    String uid(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        try { return it.getPersistentDataContainer().get(UID, PersistentDataType.STRING); } catch (Throwable e) { return null; }
    }

    private ItemStack withUid(ItemStack it, String id) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        if (id == null) m.getPersistentDataContainer().remove(UID); else m.getPersistentDataContainer().set(UID, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    private static String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 20); }

    // ---------------------------------------------------------------- scanning

    /** One serial's copy found during a scan. */
    private record Hit(String uid, Inventory inv, int slot, int nested) { } // nested = index inside a shulker/bundle in that slot, -1 = the slot itself

    private static List<ItemStack> inside(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return List.of();
        String n = it.getType().name();
        if (!n.endsWith("SHULKER_BOX") && !n.endsWith("BUNDLE")) return List.of();
        try {
            ItemMeta m = it.getItemMeta();
            if (m instanceof BlockStateMeta b && b.hasBlockState() && b.getBlockState() instanceof ShulkerBox box) return Arrays.asList(box.getInventory().getContents());
            if (m instanceof BundleMeta bm) return bm.getItems();
        } catch (Throwable ignored) { }
        return List.of();
    }

    /** Every serial in these inventories (slots + one level of shulker / bundle contents). Gives untagged tracked items a serial. */
    private List<Hit> collect(List<Inventory> invs, boolean assign) {
        List<Hit> out = new ArrayList<>();
        for (Inventory inv : invs) {
            if (inv == null) continue;
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                ItemStack it = c[i];
                if (it == null || it.getType().isAir()) continue;
                String id = uid(it);
                if (id == null && assign && tracked(it)) { id = newId(); inv.setItem(i, withUid(it.clone(), id)); }
                if (id != null) out.add(new Hit(id, inv, i, -1));
                List<ItemStack> in = inside(it);
                for (int j = 0; j < in.size(); j++) { String n = uid(in.get(j)); if (n != null) out.add(new Hit(n, inv, i, j)); }
            }
        }
        return out;
    }

    private static List<Inventory> personal(Player p) { return List.of(p.getInventory(), p.getEnderChest()); }

    /** Does this place still hold that serial right now? Unknown (offline, unloaded) = false: never punish on a guess. */
    private boolean holds(String holder, String id) {
        try {
            if (holder.startsWith("p:")) {
                Player p = Bukkit.getPlayer(UUID.fromString(holder.substring(2)));
                if (p == null || !p.isOnline()) return false;
                if (id.equals(uid(p.getItemOnCursor()))) return true;
                for (Hit h : collect(personal(p), false)) if (h.uid.equals(id)) return true;
                return false;
            }
            if (holder.startsWith("c:")) {
                String[] a = holder.substring(2).split(";");
                World w = Bukkit.getWorld(a[0]);
                if (w == null) return false;
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
                BlockState st;
                try { st = w.getBlockAt(x, y, z).getState(false); } catch (Throwable t) { st = w.getBlockAt(x, y, z).getState(); }
                if (!(st instanceof Container ct)) return false;
                for (Hit h : collect(List.of(ct.getInventory()), false)) if (h.uid.equals(id)) return true;
                return false;
            }
            if (holder.startsWith("m:")) {
                Entity e = Bukkit.getEntity(UUID.fromString(holder.substring(2)));
                if (!(e instanceof InventoryHolder ih) || e instanceof org.bukkit.entity.HumanEntity || !e.isValid()) return false;
                for (Hit h : collect(List.of(ih.getInventory()), false)) if (h.uid.equals(id)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /**
     * Checks a group of inventories that belong to one place. {@code owner} = the player who holds them (their own inventory),
     * {@code viewer} = the player who opened a container (null for their own inventory).
     */
    private void check(String holder, List<Inventory> invs, Player owner, Player viewer) {
        if (!on()) return;
        long now = System.currentTimeMillis();
        List<Hit> hits = collect(invs, owner != null);
        Set<String> places = new HashSet<>(List.of(holder));
        for (Inventory inv : invs) places.addAll(halves(inv));
        Set<String> here = new HashSet<>();
        for (Hit h : hits) {
            if (!here.add(h.uid)) { copy(h, holder, owner, viewer, "twice in the same " + (owner != null ? "inventory" : "container")); continue; }
            String[] prev = seen.get(h.uid);
            if (prev != null && !places.contains(prev[0]) && holds(prev[0], h.uid)) {
                copy(h, holder, owner, viewer, "while the original is still in " + describe(prev[0]));
                continue;
            }
            seen.put(h.uid, new String[]{holder, String.valueOf(now)});
            dirty = true;
        }
    }

    /** The holder without copying a block entity (Paper), else the plain call. */
    private static InventoryHolder holder(Inventory inv) {
        try { return inv.getHolder(false); } catch (Throwable t) { return inv.getHolder(); }
    }

    /** Every block a container inventory is made of (both halves of a double chest). */
    private static Set<String> halves(Inventory inv) {
        Set<String> out = new HashSet<>();
        try {
            InventoryHolder h = holder(inv);
            List<InventoryHolder> parts;
            if (h instanceof DoubleChest dc) {
                try { parts = Arrays.asList(dc.getLeftSide(false), dc.getRightSide(false)); }
                catch (Throwable t) { parts = Arrays.asList(dc.getLeftSide(), dc.getRightSide()); }
            } else parts = h == null ? List.of() : List.of(h);
            for (InventoryHolder part : parts)
                if (part instanceof BlockInventoryHolder bh) { Block b = bh.getBlock(); out.add("c:" + b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ()); }
        } catch (Throwable ignored) { }
        return out;
    }

    private String describe(String holder) {
        if (holder.startsWith("p:")) {
            try { Player p = Bukkit.getPlayer(UUID.fromString(holder.substring(2))); return p != null ? p.getName() + "'s inventory" : "a player's inventory"; }
            catch (RuntimeException e) { return "a player's inventory"; }
        }
        if (holder.startsWith("c:")) return "a container at " + holder.substring(2).replace(';', ' ');
        return "a storage entity";
    }

    /** A second copy: deleted (or re-serialed for staff / during a restore), alert, kick. */
    private void copy(Hit h, String holder, Player owner, Player viewer, String how) {
        ItemStack slot = h.inv.getItem(h.slot);
        if (slot == null) return;
        Player culprit = owner != null ? owner : viewer;
        boolean pardon = System.currentTimeMillis() < graceUntil || (culprit != null && culprit.hasPermission("faultlineindex.dupeguard.exempt"));
        String what;
        if (h.nested < 0) {
            what = slot.getType().name().toLowerCase(Locale.ROOT);
            if (pardon) h.inv.setItem(h.slot, withUid(slot.clone(), newId()));
            else h.inv.setItem(h.slot, null);
        } else {
            ItemStack box = slot.clone();
            what = "an item inside a " + box.getType().name().toLowerCase(Locale.ROOT);
            try {
                ItemMeta m = box.getItemMeta();
                if (m instanceof BlockStateMeta b && b.getBlockState() instanceof ShulkerBox sb) {
                    ItemStack in = sb.getInventory().getItem(h.nested);
                    if (in != null) what = in.getType().name().toLowerCase(Locale.ROOT) + " inside a shulker box";
                    sb.getInventory().setItem(h.nested, pardon && in != null ? withUid(in.clone(), newId()) : null);
                    b.setBlockState(sb);
                    box.setItemMeta(b);
                } else if (m instanceof BundleMeta bm) {
                    List<ItemStack> items = new ArrayList<>(bm.getItems());
                    if (h.nested < items.size()) {
                        ItemStack in = items.get(h.nested);
                        what = in.getType().name().toLowerCase(Locale.ROOT) + " inside a bundle";
                        if (pardon) items.set(h.nested, withUid(in.clone(), newId())); else items.remove(h.nested);
                        bm.setItems(items);
                        box.setItemMeta(bm);
                    }
                }
                h.inv.setItem(h.slot, box);
            } catch (RuntimeException ignored) { }
        }
        if (pardon) return;
        caught++;
        String who = culprit != null ? culprit.getName() : "?";
        String text = Watch.b(who) + " had a DUPED " + what + " (the same item " + how + "). The copy was deleted"
                + (culprit != null && owner != null && kick() ? " and they were kicked." : ".");
        if (culprit != null) index.watch.record(culprit, "dupe", Watch.Sev.ALERT, text);
        index.getLogger().warning("[DupeGuard] " + ChatColor.stripColor(text));
        if (owner != null && kick()) {
            String msg = index.getConfig().getString("dupe-guard.kick-message", "Duped item detected and removed. Duping gets you kicked.");
            Bukkit.getScheduler().runTask(index, () -> { if (owner.isOnline()) owner.kickPlayer(msg); });
        }
    }

    private boolean kick() { return index.getConfig().getBoolean("dupe-guard.kick", true); }

    private boolean scanned(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    /** Each online player about once a second (spread over the ticks). */
    private void tick() {
        if (!on()) return;
        List<? extends Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (all.isEmpty()) return;
        int per = Math.max(1, (all.size() + 19) / 20);
        for (int k = 0; k < per; k++) {
            Player p = all.get(Math.floorMod(cursor++, all.size()));
            if (!scanned(p)) continue;
            try { checkPlayer(p); } catch (RuntimeException ex) { index.getLogger().warning("[DupeGuard] scanning " + p.getName() + ": " + ex); }
        }
    }

    void checkPlayer(Player p) { check("p:" + p.getUniqueId(), personal(p), p, null); }

    private static final Set<org.bukkit.event.inventory.InventoryType> STORAGE = EnumSet.of(
            org.bukkit.event.inventory.InventoryType.CHEST, org.bukkit.event.inventory.InventoryType.BARREL,
            org.bukkit.event.inventory.InventoryType.SHULKER_BOX, org.bukkit.event.inventory.InventoryType.HOPPER,
            org.bukkit.event.inventory.InventoryType.DISPENSER, org.bukkit.event.inventory.InventoryType.DROPPER);

    /** Only real storage: never a crafting grid, anvil, plugin menu or anything a player holds. */
    private static String holderOf(Inventory inv) {
        if (!STORAGE.contains(inv.getType())) return null;
        InventoryHolder h = holder(inv);
        if (h == null || h instanceof org.bukkit.entity.HumanEntity) return null;
        Location l = null;
        if (h instanceof DoubleChest dc) l = dc.getLocation();
        else if (h instanceof BlockInventoryHolder bh) l = bh.getBlock().getLocation();
        else if (h instanceof Entity e) return "m:" + e.getUniqueId();
        if (l == null || l.getWorld() == null) return null;
        Block b = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        return "c:" + b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !scanned(p)) return;
        String holder = holderOf(e.getInventory());
        if (holder == null) return;
        Inventory inv = e.getInventory();
        Bukkit.getScheduler().runTask(index, () -> { try { check(holder, List.of(inv), null, p); checkPlayer(p); } catch (RuntimeException ignored) { } });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !scanned(p)) return;
        String holder = holderOf(e.getInventory());
        Inventory inv = e.getInventory();
        Bukkit.getScheduler().runTask(index, () -> {
            try { if (holder != null) check(holder, List.of(inv), null, p); if (p.isOnline()) checkPlayer(p); } catch (RuntimeException ignored) { }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !scanned(p) || uid(e.getItem().getItemStack()) == null) return;
        Bukkit.getScheduler().runTask(index, () -> { if (p.isOnline()) checkPlayer(p); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(index, () -> { if (p.isOnline() && scanned(p)) checkPlayer(p); }, 40L); // after SaveGuard's restore
    }

    /** Creative middle-click clones: the clone gets its own serial. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreative(InventoryCreativeEvent e) {
        ItemStack c = e.getCursor();
        if (uid(c) != null) e.setCursor(withUid(c.clone(), newId()));
    }

    /** Leaving creative / spectator: everything they carry gets new serials (anything could have been cloned). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        GameMode from = e.getPlayer().getGameMode();
        if (from != GameMode.CREATIVE && from != GameMode.SPECTATOR) return;
        Player p = e.getPlayer();
        for (Inventory inv : personal(p)) {
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) if (uid(c[i]) != null) inv.setItem(i, withUid(c[i].clone(), newId()));
        }
    }

    /** Mobs' drops (a mimic drops copies of a real player's gear on purpose): new serials. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        for (ItemStack it : e.getDrops()) if (uid(it) != null) withUid(it, newId());
    }

    /** InventoryRollbackPlus restores hand out old copies on purpose: 15 s where a second copy gets a new serial instead. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRestoreClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("inventoryrollbackplus.restore")) return;
        String title;
        try { title = ChatColor.stripColor(e.getView().getTitle()); } catch (Throwable t) { return; }
        if (title != null && title.contains("Backup")) graceUntil = System.currentTimeMillis() + 15000;
    }

    // ---------------------------------------------------------------- server config

    /** The vanilla dupes Paper can switch back on. String dupers are left alone on purpose. */
    private void auditPaperConfig() {
        configWarnings.clear();
        File f = new File("config/paper-global.yml");
        try { File w = new File(Bukkit.getWorldContainer(), "config/paper-global.yml"); if (w.exists()) f = w; } catch (Throwable ignored) { }
        if (!f.exists()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.getBoolean("unsupported-settings.allow-piston-duplication", false))
                configWarnings.add("config/paper-global.yml: unsupported-settings.allow-piston-duplication is TRUE (TNT, carpet and rail dupers work). Set it to false.");
            if (y.getBoolean("unsupported-settings.allow-unsafe-end-portal-teleportation", false))
                configWarnings.add("config/paper-global.yml: unsupported-settings.allow-unsafe-end-portal-teleportation is TRUE (sand / gravity block dupers work). Set it to false.");
            if (y.getBoolean("unsupported-settings.allow-headless-pistons", false))
                configWarnings.add("config/paper-global.yml: unsupported-settings.allow-headless-pistons is TRUE (block-breaking exploits). Set it to false.");
        } catch (RuntimeException ignored) { }
        for (String w : configWarnings) index.getLogger().warning("[DupeGuard] " + w);
    }

    // ---------------------------------------------------------------- saving

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = SafeYaml.load(index, file).yaml;
        long cut = System.currentTimeMillis() - 60L * 86400000;
        for (String k : y.getKeys(false)) {
            String v = y.getString(k, "");
            int bar = v.lastIndexOf('|');
            if (bar < 0) continue;
            try { if (Long.parseLong(v.substring(bar + 1)) < cut) continue; } catch (NumberFormatException e) { continue; }
            seen.put(k, new String[]{v.substring(0, bar), v.substring(bar + 1)});
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : seen.entrySet()) y.set(e.getKey(), e.getValue()[0] + "|" + e.getValue()[1]);
        SafeYaml.write(index, file, y.saveToString());
        dirty = false;
    }

    // ---------------------------------------------------------------- /dupeguard

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("faultlineindex.watch")) { s.sendMessage(ChatColor.RED + "Staff only."); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("check")) {
            Player p = Bukkit.getPlayerExact(a[1]);
            if (p == null) { s.sendMessage(ChatColor.RED + "Not online."); return true; }
            int before = caught;
            checkPlayer(p);
            s.sendMessage(caught > before ? ChatColor.RED + "Found " + (caught - before) + " duped item(s) on " + p.getName() + "." : ChatColor.GREEN + p.getName() + " is clean.");
            return true;
        }
        s.sendMessage(ChatColor.GOLD + "DupeGuard " + (on() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF") + ChatColor.GRAY + ": "
                + seen.size() + " items tracked, " + caught + " copies caught since the start, kick " + (kick() ? "on" : "off") + ".");
        auditPaperConfig();
        if (configWarnings.isEmpty()) s.sendMessage(ChatColor.GREEN + "Paper's dupe settings are all off (string dupers aren't touched).");
        for (String w : configWarnings) s.sendMessage(ChatColor.RED + w);
        return true;
    }
}
