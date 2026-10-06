package net.faultlinesmp.cosmetics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Faultline Cosmetics: permanent unlocks worn in 4 slots (HAT, NECK, BACK, BODY), shown over armor,
 * for Java and Bedrock (Geyser) players. How each slot is drawn lives in {@link Renderer}.
 */
public final class FaultlineCosmetics extends JavaPlugin implements Listener {

    public enum Slot {
        HAT("Hat"), NECK("Neck"), BACK("Back"), BODY("Body");
        final String label;
        Slot(String label) { this.label = label; }
    }

    /** One cosmetic from config.yml. Its models follow the naming in config.yml's header. */
    public record Cosmetic(String id, String name, Slot slot, String source) {
        NamespacedKey icon() { return new NamespacedKey("faultline", "cosmetic/" + id + "_icon"); }
        NamespacedKey worn() { return new NamespacedKey("faultline", "cosmetic/" + id); }
        NamespacedKey equipment() { return new NamespacedKey("faultline", id); }
    }

    static final class Data {
        final LinkedHashSet<String> unlocked = new LinkedHashSet<>();
        final EnumMap<Slot, String> equipped = new EnumMap<>(Slot.class);
    }

    private static FaultlineCosmetics instance;
    private final LinkedHashMap<String, Cosmetic> cosmetics = new LinkedHashMap<>();
    private final Map<UUID, Data> data = new HashMap<>();
    private File dataFile;
    private boolean dirty;
    private Renderer renderer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadCosmetics();
        dataFile = new File(getDataFolder(), "players.yml");
        loadData();
        renderer = new Renderer(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(renderer, this);
        renderer.start();
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) saveData(); }, 600L, 600L);
        getLogger().info(cosmetics.size() + " cosmetics loaded. Bedrock detection: " + renderer.bedrockDetection());
    }

    @Override
    public void onDisable() {
        if (renderer != null) renderer.stop();
        if (dirty) saveData();
    }

    // ------------------------------------------------------------------ API (other plugins / console)

    /** Unlocks a cosmetic forever. Returns false if the id is unknown or it was already unlocked. */
    public static boolean unlock(UUID player, String id) {
        if (instance == null || !instance.cosmetics.containsKey(id)) return false;
        boolean added = instance.data(player).unlocked.add(id);
        if (added) instance.dirty = true;
        return added;
    }

    public static boolean has(UUID player, String id) {
        return instance != null && instance.data(player).unlocked.contains(id);
    }

    // ------------------------------------------------------------------ config + storage

    private void loadCosmetics() {
        cosmetics.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("cosmetics");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            Slot slot;
            try { slot = Slot.valueOf(c.getString("slot", "").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { getLogger().warning("Cosmetic " + id + " has no valid slot (HAT, NECK, BACK or BODY), skipped."); continue; }
            cosmetics.put(id, new Cosmetic(id, c.getString("name", id), slot, c.getString("source", "")));
        }
    }

    private void loadData() {
        data.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : y.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            Data d = new Data();
            d.unlocked.addAll(y.getStringList(key + ".unlocked"));
            ConfigurationSection eq = y.getConfigurationSection(key + ".equipped");
            if (eq != null) for (String s : eq.getKeys(false)) {
                try { d.equipped.put(Slot.valueOf(s), eq.getString(s)); } catch (IllegalArgumentException ignored) { }
            }
            data.put(id, d);
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> e : data.entrySet()) {
            Data d = e.getValue();
            if (d.unlocked.isEmpty() && d.equipped.isEmpty()) continue;
            String k = e.getKey().toString();
            y.set(k + ".unlocked", new ArrayList<>(d.unlocked));
            for (Map.Entry<Slot, String> q : d.equipped.entrySet()) y.set(k + ".equipped." + q.getKey().name(), q.getValue());
        }
        try { y.save(dataFile); dirty = false; }
        catch (IOException ex) { getLogger().warning("Couldn't save players.yml: " + ex.getMessage()); }
    }

    Data data(UUID id) { return data.computeIfAbsent(id, k -> new Data()); }

    /** What a player is wearing right now (only cosmetics that still exist and are unlocked). */
    EnumMap<Slot, Cosmetic> worn(UUID id) {
        EnumMap<Slot, Cosmetic> out = new EnumMap<>(Slot.class);
        Data d = data.get(id);
        if (d == null) return out;
        for (Map.Entry<Slot, String> e : d.equipped.entrySet()) {
            Cosmetic c = cosmetics.get(e.getValue());
            if (c != null && c.slot() == e.getKey() && d.unlocked.contains(c.id())) out.put(e.getKey(), c);
        }
        return out;
    }

    boolean showOwn() { return getConfig().getBoolean("show-own", true); }
    int resendTicks() { return Math.max(10, getConfig().getInt("resend-ticks", 40)); }

    // ------------------------------------------------------------------ text

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    static void msg(CommandSender to, String legacy) { to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)); }

    // ------------------------------------------------------------------ the menu

    private static final int[] LIST = {12, 13, 14, 15, 16, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};
    private static final int[] SLOT_AT = {10, 19, 28, 37}; // HAT, NECK, BACK, BODY
    private static final int PREV = 48, INFO = 49, NEXT = 50, TAKE_OFF = 46;

    static final class Menu implements InventoryHolder {
        final UUID owner; final int page; final Map<Integer, String> ids = new HashMap<>();
        Inventory inv;
        Menu(UUID owner, int page) { this.owner = owner; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    void openMenu(Player p, int page) {
        List<Cosmetic> all = new ArrayList<>(cosmetics.values());
        Data d = data(p.getUniqueId());
        all.sort(Comparator.comparing(c -> !d.unlocked.contains(c.id()))); // unlocked first, config order kept
        int pages = Math.max(1, (all.size() + LIST.length - 1) / LIST.length);
        page = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(p.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(m, 54, text("&8Cosmetics"));
        m.inv = inv;
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        EnumMap<Slot, Cosmetic> worn = worn(p.getUniqueId());
        for (Slot s : Slot.values()) {
            Cosmetic c = worn.get(s);
            inv.setItem(SLOT_AT[s.ordinal()], c == null
                ? button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7" + s.label + ": &8nothing", "&8Pick a " + s.label.toLowerCase(Locale.ROOT) + " cosmetic on the right.")
                : icon(c, "&7" + s.label + " slot", "", "&eClick to take it off."));
        }
        for (int i = 0; i < LIST.length; i++) {
            int n = page * LIST.length + i;
            if (n >= all.size()) { inv.setItem(LIST[i], null); continue; }
            Cosmetic c = all.get(n);
            boolean has = d.unlocked.contains(c.id()), on = c == worn.get(c.slot());
            List<String> lore = new ArrayList<>(List.of("&7Slot: &f" + c.slot().label, ""));
            if (on) lore.add("&aWearing it. &eClick to take it off.");
            else if (has) lore.add("&eClick to wear it.");
            else { lore.add("&cLocked"); if (!c.source().isEmpty()) lore.add("&7" + c.source()); }
            ItemStack it = icon(c, lore.toArray(new String[0]));
            if (on) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
            inv.setItem(LIST[i], it);
            m.ids.put(LIST[i], c.id());
        }
        long have = cosmetics.keySet().stream().filter(d.unlocked::contains).count();
        inv.setItem(INFO, button(Material.BOOK, "&6Your cosmetics", "&7Unlocked: &f" + have + "/" + cosmetics.size(),
            "&7They show over your armor and", "&7don't change your stats.", "&7Page " + (page + 1) + "/" + pages));
        if (page > 0) inv.setItem(PREV, button(Material.ARROW, "&ePrevious page"));
        if (page < pages - 1) inv.setItem(NEXT, button(Material.ARROW, "&eNext page"));
        if (!worn.isEmpty()) inv.setItem(TAKE_OFF, button(Material.BARRIER, "&cTake everything off"));
        p.openInventory(inv);
    }

    ItemStack icon(Cosmetic c, String... lore) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        im.setItemModel(c.icon());
        im.displayName(text(c.name()));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(text(s));
        im.lore(l);
        im.setMaxStackSize(1);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    private static ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.displayName(text(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(text(s));
        im.lore(l);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int raw = e.getRawSlot();
        Data d = data(p.getUniqueId());
        if (raw == PREV) { openMenu(p, m.page - 1); return; }
        if (raw == NEXT) { openMenu(p, m.page + 1); return; }
        if (raw == TAKE_OFF && !d.equipped.isEmpty()) {
            d.equipped.clear(); dirty = true; renderer.refresh(p);
            p.playSound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.8f);
            openMenu(p, m.page); return;
        }
        for (Slot s : Slot.values()) if (raw == SLOT_AT[s.ordinal()] && d.equipped.remove(s) != null) {
            dirty = true; renderer.refresh(p);
            p.playSound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.8f);
            openMenu(p, m.page); return;
        }
        String id = m.ids.get(raw);
        Cosmetic c = id == null ? null : cosmetics.get(id);
        if (c == null) return;
        if (!d.unlocked.contains(id)) {
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            msg(p, "&cYou haven't unlocked " + c.name() + "&c yet." + (c.source().isEmpty() ? "" : " &7(" + c.source() + ")"));
            return;
        }
        if (id.equals(d.equipped.get(c.slot()))) d.equipped.remove(c.slot());
        else d.equipped.put(c.slot(), id);
        dirty = true;
        renderer.refresh(p);
        p.playSound(p, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 1.1f);
        openMenu(p, m.page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // /cosmetics opens the menu; so does /cosmetic for anyone who isn't an admin (an easy typo that used to just say
        // "no permission", so it looked like the command didn't work)
        boolean admin = sender.hasPermission("faultlinecosmetics.admin");
        if (cmd.getName().equalsIgnoreCase("cosmetics") || !admin) {
            if (sender instanceof Player p) {
                try { openMenu(p, 0); }
                catch (RuntimeException | LinkageError ex) { // never fail silently: tell them, and put the cause in the console
                    getLogger().log(java.util.logging.Level.SEVERE, "Couldn't open the cosmetics menu for " + p.getName(), ex);
                    msg(p, "&cThe cosmetics menu couldn't open (the error is in the server console). Tell an admin.");
                }
            }
            else msg(sender, "&cOnly players can open the cosmetics menu.");
            return true;
        }
        if (args.length == 0) { if (sender instanceof Player p) { openMenu(p, 0); return true; } usage(sender); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig(); loadCosmetics();
                for (Player p : Bukkit.getOnlinePlayers()) renderer.refresh(p);
                msg(sender, "&aFaultlineCosmetics reloaded: " + cosmetics.size() + " cosmetics.");
            }
            case "list" -> {
                if (args.length < 2) { msg(sender, "&6Cosmetics: &f" + describe(cosmetics.keySet())); return true; }
                OfflinePlayer t = target(sender, args[1]);
                if (t == null) return true;
                Data d = data(t.getUniqueId());
                msg(sender, "&6" + t.getName() + " has unlocked: &f" + (d.unlocked.isEmpty() ? "nothing" : String.join(", ", d.unlocked)));
                msg(sender, "&6Wearing: &f" + (d.equipped.isEmpty() ? "nothing" : d.equipped.toString()));
            }
            case "unlock", "lock" -> {
                if (args.length < 3) { usage(sender); return true; }
                OfflinePlayer t = target(sender, args[1]);
                if (t == null) return true;
                boolean unlock = args[0].equalsIgnoreCase("unlock");
                List<String> ids = args[2].equalsIgnoreCase("all") ? new ArrayList<>(cosmetics.keySet()) : List.of(args[2]);
                for (String id : ids) if (!cosmetics.containsKey(id)) { msg(sender, "&cUnknown cosmetic: " + id + ". Known: " + describe(cosmetics.keySet())); return true; }
                Data d = data(t.getUniqueId());
                List<String> changed = new ArrayList<>();
                for (String id : ids) {
                    if (unlock ? d.unlocked.add(id) : d.unlocked.remove(id)) changed.add(id);
                    if (!unlock) d.equipped.values().removeIf(id::equals);
                }
                dirty = true;
                Player online = t.getPlayer();
                if (online != null) {
                    renderer.refresh(online);
                    if (unlock && !changed.isEmpty() && !(args.length > 3 && args[3].equalsIgnoreCase("silent"))) {
                        for (String id : changed) msg(online, "&6&lCOSMETIC UNLOCKED! &r" + cosmetics.get(id).name() + " &7is yours forever. Wear it from &e/cosmetics&7.");
                        online.playSound(online, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                    }
                }
                msg(sender, changed.isEmpty() ? "&7Nothing changed for " + t.getName() + "."
                    : "&a" + (unlock ? "Unlocked" : "Locked") + " for " + t.getName() + ": " + String.join(", ", changed));
            }
            default -> usage(sender);
        }
        return true;
    }

    private static String describe(Collection<String> ids) { return ids.isEmpty() ? "none" : String.join(", ", ids); }

    private void usage(CommandSender s) {
        msg(s, "&6/cosmetic unlock <player> <id|all> [silent] &7- unlock forever");
        msg(s, "&6/cosmetic lock <player> <id|all> &7- take an unlock away");
        msg(s, "&6/cosmetic list [player] &7- every cosmetic, or what a player has");
        msg(s, "&6/cosmetic reload &7- reload config.yml");
    }

    private OfflinePlayer target(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        try { return Bukkit.getOfflinePlayer(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(name);
        if (o == null) msg(sender, "&cNo player called " + name + " has played here.");
        return o;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("cosmetics") || !sender.hasPermission("faultlinecosmetics.admin")) return List.of();
        List<String> opts = new ArrayList<>();
        if (args.length == 1) opts.addAll(List.of("unlock", "lock", "list", "reload"));
        else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
        else if (args.length == 3 && (args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("lock"))) { opts.addAll(cosmetics.keySet()); opts.add("all"); }
        else if (args.length == 4 && args[0].equalsIgnoreCase("unlock")) opts.add("silent");
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        opts.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return opts;
    }
}
