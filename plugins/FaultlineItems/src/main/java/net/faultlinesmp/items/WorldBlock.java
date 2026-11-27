package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE WORLD BLOCK (Items 2.0, data world_blocks.yml): an admin tool for areas, like WorldEdit's wand.
 *  - Take it from /adminmenu (only there). Holding it: RIGHT-click a block = pos 1, LEFT-click a block = pos 2 (the block isn't
 *    broken). With both set the settings menu opens.
 *  - SETTINGS (chest menu): music (Faultline tracks or vanilla discs, or none), loop on/off, pitch (0.5-2.0), building and
 *    griefing (off = nobody can break, build, open containers, use buckets / fire, hurt or take villagers and animals, break
 *    frames and paintings; explosions, fire, pistons, water / lava from outside and mob griefing can't touch it), PvP on/off,
 *    who can come in (everyone / nobody but admins: anyone else is turned back at the edge), lighting (very dark .. very bright:
 *    each player's own sky time and Darkness / Night Vision) and weather (normal, clear, rain, snow: only inside, only for
 *    the people inside).
 *  - Press DONE: the World Block in your hand is bound to the area. The area only starts working once that block is PLACED
 *    somewhere inside it. Right-click the placed block = the menu again. Sneak + break it (admin) = the area is deleted.
 * Admins in creative ignore its rules; admins (items.worldblock) can always come in.
 */
final class WorldBlock implements Listener {
    static final String PERM = "items.worldblock";

    static final class Track {
        final String key, name; final double seconds; final Material icon;
        Track(String key, String name, double seconds, Material icon) { this.key = key; this.name = name; this.seconds = seconds; this.icon = icon; }
    }

    static final List<Track> FAULTLINE = List.of(
            new Track("faultline:world.festive", "Festive Lobby", 108.77, Material.SNOWBALL),
            new Track("faultline:deep.sunken", "Theme of the Sunken Sea", 362, Material.PRISMARINE_SHARD),
            new Track("faultline:deep.abyss", "Hadopelagic Pressure", 286, Material.SCULK),
            new Track("faultline:deep.acid", "Acid Rain", 105, Material.SLIME_BALL),
            new Track("faultline:deep.biocenter", "Engineer's Sanctum", 442, Material.COPPER_BULB),
            new Track("faultline:coralia.music", "Coralia", 285.86, Material.TUBE_CORAL),
            new Track("faultline:farlands.music", "The Farlands", 161.5, Material.CRIMSON_NYLIUM),
            new Track("faultline:nest.music", "The Spider Nest", 101, Material.COBWEB),
            new Track("faultline:pirates.music", "The Pirate Invasion", 291, Material.GOAT_HORN),
            new Track("faultline:jacob.music1", "Diamond Jacob I", 315, Material.DIAMOND_SWORD),
            new Track("faultline:jacob.music2", "Diamond Jacob II", 286, Material.DIAMOND),
            new Track("faultline:demon_eye.music", "Demon Eye", 316.4, Material.ENDER_EYE));

    static final List<Track> VANILLA = List.of(
            new Track("minecraft:music_disc.13", "13", 178, Material.MUSIC_DISC_13),
            new Track("minecraft:music_disc.cat", "cat", 185, Material.MUSIC_DISC_CAT),
            new Track("minecraft:music_disc.blocks", "blocks", 345, Material.MUSIC_DISC_BLOCKS),
            new Track("minecraft:music_disc.chirp", "chirp", 185, Material.MUSIC_DISC_CHIRP),
            new Track("minecraft:music_disc.far", "far", 174, Material.MUSIC_DISC_FAR),
            new Track("minecraft:music_disc.mall", "mall", 197, Material.MUSIC_DISC_MALL),
            new Track("minecraft:music_disc.mellohi", "mellohi", 96, Material.MUSIC_DISC_MELLOHI),
            new Track("minecraft:music_disc.stal", "stal", 150, Material.MUSIC_DISC_STAL),
            new Track("minecraft:music_disc.strad", "strad", 188, Material.MUSIC_DISC_STRAD),
            new Track("minecraft:music_disc.ward", "ward", 251, Material.MUSIC_DISC_WARD),
            new Track("minecraft:music_disc.11", "11", 71, Material.MUSIC_DISC_11),
            new Track("minecraft:music_disc.wait", "wait", 238, Material.MUSIC_DISC_WAIT),
            new Track("minecraft:music_disc.pigstep", "Pigstep", 148, Material.MUSIC_DISC_PIGSTEP),
            new Track("minecraft:music_disc.otherside", "otherside", 195, Material.MUSIC_DISC_OTHERSIDE),
            new Track("minecraft:music_disc.5", "5", 178, Material.MUSIC_DISC_5),
            new Track("minecraft:music_disc.relic", "Relic", 218, Material.MUSIC_DISC_RELIC),
            new Track("minecraft:music_disc.creator", "Creator", 176, Material.MUSIC_DISC_CREATOR),
            new Track("minecraft:music_disc.creator_music_box", "Creator (Music Box)", 73, Material.MUSIC_DISC_CREATOR_MUSIC_BOX),
            new Track("minecraft:music_disc.precipice", "Precipice", 299, Material.MUSIC_DISC_PRECIPICE));

    static Track track(String key) {
        if (key == null) return null;
        for (Track t : FAULTLINE) if (t.key.equals(key)) return t;
        for (Track t : VANILLA) if (t.key.equals(key)) return t;
        return null;
    }

    static final String[] WEATHER = {"normal", "clear", "rain", "snow"};
    static final String[] LIGHT = {"Very dark", "Dark", "Normal", "Bright", "Very bright"};

    final class Area {
        final String id;
        String world;
        int x1, y1, z1, x2, y2, z2;
        int bx, by, bz; boolean placed;
        String music; boolean loop = true; double pitch = 1.0;
        boolean grief, pvp = true, entry = true;
        int light; String weather = "normal";
        Area(String id) { this.id = id; }
        boolean in(Location l) {
            if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), z = l.getBlockZ(); // (2.0: every area reaches from the bottom of the world to the very top)
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }
        boolean isBlock(Block b) { return placed && b.getWorld().getName().equals(world) && b.getX() == bx && b.getY() == by && b.getZ() == bz; }
        boolean live() { return placed; }
        String size() { return (x2 - x1 + 1) + " x " + (z2 - z1 + 1) + " (bedrock to sky)"; }
        /** The whole height of its world (the music, weather and rules go all the way up to the build limit). */
        void fullHeight() { World w = Bukkit.getWorld(world); if (w != null) { y1 = w.getMinHeight(); y2 = w.getMaxHeight() - 1; } }
    }

    static final class Listen { String area, track; long until; double pitch; }
    private final Set<UUID> gaveDark = new java.util.HashSet<>(), gaveNv = new java.util.HashSet<>();

    private final FaultlineItems plugin;
    private final NamespacedKey toolKey, areaKey;
    private final File file;
    final Map<String, Area> areas = new LinkedHashMap<>();
    private final Map<UUID, Location[]> picks = new HashMap<>();
    private final Map<UUID, Listen> hearing = new HashMap<>();
    private final Map<UUID, String> inside = new HashMap<>();
    private final Map<UUID, Location> lastOut = new HashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();
    private long ticks;

    WorldBlock(FaultlineItems plugin) {
        this.plugin = plugin;
        toolKey = new NamespacedKey(plugin, "world_block");
        areaKey = new NamespacedKey(plugin, "world_block_area");
        file = new File(plugin.getDataFolder(), "world_blocks.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    // ================================================================= the item
    ItemStack tool() {
        ItemStack it = new ItemStack(Material.LODESTONE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "World Block");
        m.setLore(List.of(ChatColor.GRAY + "RIGHT-click a block = pos 1", ChatColor.GRAY + "LEFT-click a block = pos 2",
                ChatColor.GRAY + "Then pick the area's music, rules,", ChatColor.GRAY + "lighting and weather in the menu.", ChatColor.DARK_RED + "Admin only."));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack bound(Area a) {
        ItemStack it = new ItemStack(Material.LODESTONE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "World Block " + ChatColor.GRAY + "(" + a.id + ")");
        m.setLore(List.of(ChatColor.YELLOW + "PLACE IT INSIDE THE AREA to switch it on.", ChatColor.GRAY + "Area: " + a.size() + " in " + a.world,
                ChatColor.GRAY + "Right-click it once placed to change things.", ChatColor.DARK_RED + "Admin only."));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(areaKey, PersistentDataType.STRING, a.id);
        it.setItemMeta(m);
        return it;
    }

    boolean isTool(ItemStack it) { return it != null && it.getType() == Material.LODESTONE && it.hasItemMeta() && it.getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE); }
    String boundTo(ItemStack it) { return isTool(it) ? it.getPersistentDataContainer().get(areaKey, PersistentDataType.STRING) : null; }

    static boolean admin(Player p) { return p.hasPermission(PERM); }
    /** Admins in creative ignore an area's rules (to build it); everyone else, ops in survival too, follows them. */
    static boolean bypass(Player p) { return admin(p) && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR); }

    Area at(Location l) { for (Area a : areas.values()) if (a.live() && a.in(l)) return a; return null; }
    Area at(Block b) { return at(b.getLocation()); }

    // ================================================================= picking the area
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = e.getItem();
        if (!isTool(hand) || boundTo(hand) != null) return; // a bound block places normally
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!admin(p)) { p.sendMessage(ChatColor.RED + "Only admins can use the World Block."); return; }
        if (e.getClickedBlock() == null) return;
        Location[] pk = picks.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        Location l = e.getClickedBlock().getLocation();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) { pk[0] = l; p.sendMessage(ChatColor.LIGHT_PURPLE + "Pos 1 " + ChatColor.WHITE + xyz(l)); }
        else if (e.getAction() == Action.LEFT_CLICK_BLOCK) { pk[1] = l; p.sendMessage(ChatColor.LIGHT_PURPLE + "Pos 2 " + ChatColor.WHITE + xyz(l)); }
        else return;
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1f, e.getAction() == Action.RIGHT_CLICK_BLOCK ? 1.2f : 1.6f);
        if (pk[0] != null && pk[1] != null) {
            if (!pk[0].getWorld().equals(pk[1].getWorld())) { p.sendMessage(ChatColor.RED + "Both corners must be in the same world."); return; }
            Area a = new Area(newId());
            a.world = pk[0].getWorld().getName();
            a.x1 = Math.min(pk[0].getBlockX(), pk[1].getBlockX()); a.x2 = Math.max(pk[0].getBlockX(), pk[1].getBlockX());
            a.y1 = Math.min(pk[0].getBlockY(), pk[1].getBlockY()); a.y2 = Math.max(pk[0].getBlockY(), pk[1].getBlockY());
            a.z1 = Math.min(pk[0].getBlockZ(), pk[1].getBlockZ()); a.z2 = Math.max(pk[0].getBlockZ(), pk[1].getBlockZ());
            a.fullHeight();
            areas.put(a.id, a);
            picks.remove(p.getUniqueId());
            save();
            p.sendMessage(ChatColor.AQUA + "Area " + a.id + " (" + a.size() + ") made. " + ChatColor.GRAY + "Pick its settings, press DONE, then place the block inside it.");
            plugin.getLogger().info("[WorldBlock] " + p.getName() + " made area " + a.id + " " + a.world + " " + a.x1 + "," + a.y1 + "," + a.z1 + " .. " + a.x2 + "," + a.y2 + "," + a.z2);
            open(p, a);
        }
    }

    /** Left-clicking with the wand in creative breaks the block at once: never with the World Block in hand. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWandBreak(BlockBreakEvent e) {
        if (isTool(e.getPlayer().getInventory().getItemInMainHand()) && boundTo(e.getPlayer().getInventory().getItemInMainHand()) == null) e.setCancelled(true);
    }

    String newId() { int n = 1; while (areas.containsKey("area" + n)) n++; return "area" + n; }
    static String xyz(Location l) { return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    // ================================================================= placing / breaking the block
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlaceBlock(BlockPlaceEvent e) {
        String id = boundTo(e.getItemInHand());
        if (id == null) { if (isTool(e.getItemInHand())) e.setCancelled(true); return; }
        Player p = e.getPlayer();
        Area a = areas.get(id);
        if (!admin(p) || a == null) { e.setCancelled(true); if (a == null) p.sendMessage(ChatColor.RED + "That area doesn't exist any more."); return; }
        if (!a.in(e.getBlock().getLocation())) { e.setCancelled(true); p.sendMessage(ChatColor.RED + "Place it INSIDE its area (x " + a.x1 + ".." + a.x2 + ", z " + a.z1 + ".." + a.z2 + ")."); return; }
        e.setCancelled(false);
        if (a.placed) { Block old = Bukkit.getWorld(a.world) == null ? null : Bukkit.getWorld(a.world).getBlockAt(a.bx, a.by, a.bz); if (old != null && old.getType() == Material.LODESTONE) old.setType(Material.AIR); }
        a.placed = true; a.bx = e.getBlock().getX(); a.by = e.getBlock().getY(); a.bz = e.getBlock().getZ();
        save();
        p.sendMessage(ChatColor.GREEN + "World Block placed: area " + a.id + " is ON. " + ChatColor.GRAY + "Right-click it to change things, sneak + break it to delete the area.");
        p.playSound(p, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 1.2f);
    }

    Area blockArea(Block b) {
        if (b.getType() != Material.LODESTONE) return null;
        for (Area a : areas.values()) if (a.isBlock(b)) return a;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Area a = blockArea(e.getClickedBlock());
        if (a == null) return;
        e.setCancelled(true);
        if (!admin(e.getPlayer())) return;
        open(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent e) {
        Area a = blockArea(e.getBlock());
        if (a == null) return;
        Player p = e.getPlayer();
        if (!admin(p) || !p.isSneaking()) { e.setCancelled(true); if (admin(p)) p.sendMessage(ChatColor.GRAY + "Sneak + break to delete area " + a.id + "."); return; }
        e.setDropItems(false);
        delete(a);
        p.sendMessage(ChatColor.YELLOW + "Area " + a.id + " deleted.");
    }

    void delete(Area a) {
        areas.remove(a.id);
        for (Player p : Bukkit.getOnlinePlayers()) if (a.id.equals(inside.get(p.getUniqueId()))) leave(p, a);
        save();
        plugin.getLogger().info("[WorldBlock] area " + a.id + " deleted");
    }

    // ================================================================= the menu
    static final class Menu implements InventoryHolder {
        final String area; final int page; Inventory inv;
        Menu(String area, int page) { this.area = area; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name);
        List<String> l = new ArrayList<>(); for (String s : lore) l.add(ChatColor.GRAY + s);
        im.setLore(l);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    static String onOff(boolean b) { return b ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"; }

    void open(Player p, Area a) {
        Menu m = new Menu(a.id, 0);
        m.inv = Bukkit.createInventory(m, 54, ChatColor.DARK_AQUA + "World Block: " + a.id);
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) m.inv.setItem(i, glass);
        Track t = track(a.music);
        m.inv.setItem(4, button(Material.MAP, ChatColor.AQUA + "Area " + a.id, a.size() + " blocks in " + a.world,
                "x " + a.x1 + ".." + a.x2 + ", z " + a.z1 + ".." + a.z2 + ", y " + a.y1 + ".." + a.y2 + " (all of it)",
                a.placed ? "Block at " + a.bx + " " + a.by + " " + a.bz + " (ON)" : ChatColor.YELLOW + "Not placed yet: OFF until you place the block inside."));
        m.inv.setItem(19, button(t == null ? Material.JUKEBOX : t.icon, ChatColor.GOLD + "Music: " + ChatColor.WHITE + (t == null ? "none" : t.name), "Click to pick a song."));
        m.inv.setItem(20, button(a.loop ? Material.REPEATER : Material.COMPARATOR, ChatColor.GOLD + "Loop: " + onOff(a.loop), "Click to switch."));
        m.inv.setItem(21, button(Material.NOTE_BLOCK, ChatColor.GOLD + "Pitch: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f", a.pitch),
                "Left-click: higher", "Right-click: lower", "Shift-click: back to 1.0"));
        m.inv.setItem(23, button(a.grief ? Material.TNT : Material.BEDROCK, ChatColor.GOLD + "Building & griefing: " + onOff(a.grief),
                "OFF: nobody can break, build, open chests,", "use buckets / fire, hurt or take villagers.", "Click to switch."));
        m.inv.setItem(24, button(a.pvp ? Material.IRON_SWORD : Material.SHIELD, ChatColor.GOLD + "PvP: " + onOff(a.pvp), "Click to switch."));
        m.inv.setItem(25, button(a.entry ? Material.OAK_DOOR : Material.IRON_DOOR, ChatColor.GOLD + "Entering: " + (a.entry ? ChatColor.GREEN + "anyone" : ChatColor.RED + "admins only"),
                "Closed: everyone else is turned back at the", "edge (and put out if they're inside).", "Click to switch."));
        m.inv.setItem(30, button(a.light < 0 ? Material.BLACK_CANDLE : a.light > 0 ? Material.GLOWSTONE : Material.TORCH,
                ChatColor.GOLD + "Lighting: " + ChatColor.WHITE + LIGHT[a.light + 2], "Left-click: brighter", "Right-click: darker"));
        m.inv.setItem(32, button(switch (a.weather) { case "rain" -> Material.WATER_BUCKET; case "snow" -> Material.SNOW_BLOCK; case "clear" -> Material.SUNFLOWER; default -> Material.GRASS_BLOCK; },
                ChatColor.GOLD + "Weather: " + ChatColor.WHITE + a.weather, "Only inside the area.", "Click: normal > clear > rain > snow"));
        m.inv.setItem(49, button(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "DONE", a.placed ? "Close the menu." : "Gives you the World Block for this area:", a.placed ? "" : "place it inside the area to switch it on."));
        m.inv.setItem(53, button(Material.BARRIER, ChatColor.RED + "Delete this area", "Shift-click to delete."));
        p.openInventory(m.inv);
    }

    void openMusic(Player p, Area a, int page) {
        Menu m = new Menu(a.id, page);
        m.inv = Bukkit.createInventory(m, 54, ChatColor.DARK_AQUA + (page == 1 ? "Faultline music" : "Vanilla music"));
        List<Track> list = page == 1 ? FAULTLINE : VANILLA;
        for (int i = 0; i < list.size() && i < 36; i++) {
            Track t = list.get(i);
            m.inv.setItem(i, button(t.icon, (t.key.equals(a.music) ? ChatColor.GREEN + "> " : ChatColor.WHITE + "") + t.name,
                    (int) t.seconds / 60 + ":" + String.format("%02d", (int) t.seconds % 60), "Click to use it."));
        }
        m.inv.setItem(45, button(Material.BARRIER, ChatColor.RED + "No music"));
        m.inv.setItem(47, button(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "Faultline music"));
        m.inv.setItem(49, button(Material.ARROW, ChatColor.YELLOW + "Back"));
        m.inv.setItem(51, button(Material.MUSIC_DISC_CAT, ChatColor.AQUA + "Vanilla music"));
        p.openInventory(m.inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != m.inv || !admin(p)) return;
        Area a = areas.get(m.area);
        if (a == null) { p.closeInventory(); return; }
        int s = e.getSlot();
        if (m.page > 0) {
            List<Track> list = m.page == 1 ? FAULTLINE : VANILLA;
            if (s < list.size()) { a.music = list.get(s).key; restartMusic(a); save(); open(p, a); preview(p, a); }
            else if (s == 45) { a.music = null; restartMusic(a); save(); open(p, a); }
            else if (s == 47) openMusic(p, a, 1);
            else if (s == 51) openMusic(p, a, 2);
            else if (s == 49) open(p, a);
            return;
        }
        switch (s) {
            case 19 -> { openMusic(p, a, 1); return; }
            case 20 -> a.loop = !a.loop;
            case 21 -> {
                if (e.isShiftClick()) a.pitch = 1.0;
                else a.pitch = Math.max(0.5, Math.min(2.0, Math.round((a.pitch + (e.isRightClick() ? -0.1 : 0.1)) * 10) / 10.0));
                restartMusic(a);
            }
            case 23 -> a.grief = !a.grief;
            case 24 -> a.pvp = !a.pvp;
            case 25 -> { a.entry = !a.entry; if (!a.entry) putOut(a); }
            case 30 -> a.light = Math.max(-2, Math.min(2, a.light + (e.isRightClick() ? -1 : 1)));
            case 32 -> { int i = 0; for (int k = 0; k < WEATHER.length; k++) if (WEATHER[k].equals(a.weather)) i = k; a.weather = WEATHER[(i + 1) % WEATHER.length]; }
            case 49 -> {
                p.closeInventory();
                if (!a.placed) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    ItemStack give = bound(a);
                    if (isTool(hand) && boundTo(hand) == null) p.getInventory().setItemInMainHand(give);
                    else for (ItemStack left : p.getInventory().addItem(give).values()) p.getWorld().dropItem(p.getLocation(), left);
                    p.sendMessage(ChatColor.YELLOW + "Now place the World Block somewhere INSIDE the area to switch it on.");
                }
                save();
                return;
            }
            case 53 -> { if (e.isShiftClick()) { p.closeInventory(); if (a.placed) { World w = Bukkit.getWorld(a.world); if (w != null && w.getBlockAt(a.bx, a.by, a.bz).getType() == Material.LODESTONE) w.getBlockAt(a.bx, a.by, a.bz).setType(Material.AIR); } delete(a); p.sendMessage(ChatColor.YELLOW + "Area " + a.id + " deleted."); } return; }
            default -> { return; }
        }
        p.playSound(p, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.6f, 1.2f);
        save();
        for (Player in : Bukkit.getOnlinePlayers()) if (a.id.equals(inside.get(in.getUniqueId()))) apply(in, a);
        open(p, a);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true); }

    /** A few seconds of the song for the admin who picked it (when they aren't inside to hear it already). */
    void preview(Player p, Area a) {
        Track t = track(a.music);
        if (t == null || a.id.equals(inside.get(p.getUniqueId()))) return;
        p.playSound(p, t.key, SoundCategory.RECORDS, 1f, (float) a.pitch);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!a.id.equals(inside.get(p.getUniqueId()))) p.stopSound(t.key, SoundCategory.RECORDS); }, 160L);
    }

    void restartMusic(Area a) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Listen l = hearing.get(p.getUniqueId());
            if (l != null && a.id.equals(l.area)) { stopMusic(p); }
        }
    }

    // ================================================================= being inside: music, light, weather, the door
    void tick() {
        ticks++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { tickOne(p); } catch (RuntimeException ex) { if (ticks % 1200 == 0) plugin.getLogger().warning("[WorldBlock] " + ex); }
        }
    }

    void tickOne(Player p) {
        Area a = at(p.getLocation());
        String was = inside.get(p.getUniqueId());
        if (a != null && !a.entry && !admin(p)) { turnBack(p, a); return; }
        if (a == null) {
            lastOut.put(p.getUniqueId(), p.getLocation());
            if (was != null) { Area old = areas.get(was); leave(p, old); }
            return;
        }
        if (!a.id.equals(was)) {
            if (was != null) leave(p, areas.get(was));
            inside.put(p.getUniqueId(), a.id);
            apply(p, a);
        }
        music(p, a);
        weatherFx(p, a);
        if (ticks % 8 == 0) apply(p, a);
    }

    void apply(Player p, Area a) {
        // lighting = the player's own sky
        switch (a.light) {
            case -2, -1 -> p.setPlayerTime(18000, false);
            case 1, 2 -> p.setPlayerTime(6000, false);
            default -> p.resetPlayerTime();
        }
        switch (a.weather) {
            case "rain" -> p.setPlayerWeather(WeatherType.DOWNFALL);
            case "clear", "snow" -> p.setPlayerWeather(WeatherType.CLEAR);
            default -> p.resetPlayerWeather();
        }
        effects(p, a);
    }

    void effects(Player p, Area a) {
        UUID id = p.getUniqueId();
        if (a.light == -2) {
            if (!p.hasPotionEffect(PotionEffectType.DARKNESS)) { p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, PotionEffect.INFINITE_DURATION, 0, true, false, false)); gaveDark.add(id); }
        } else if (gaveDark.remove(id)) p.removePotionEffect(PotionEffectType.DARKNESS);
        if (a.light == 2) {
            if (!p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) { p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, false)); gaveNv.add(id); }
        } else if (gaveNv.remove(id)) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    void weatherFx(Player p, Area a) {
        Location c = p.getLocation();
        if ("snow".equals(a.weather)) {
            for (int i = 0; i < 40; i++) {
                Location l = c.clone().add((Math.random() - 0.5) * 24, 4 + Math.random() * 8, (Math.random() - 0.5) * 24);
                if (a.in(l)) p.spawnParticle(Particle.SNOWFLAKE, l, 1, 0, 0, 0, 0.02);
            }
        } else if ("rain".equals(a.weather)) {
            // the sky's rain doesn't fall in deserts, savannas or caves: water drops make sure it rains here anyway
            for (int i = 0; i < 30; i++) {
                Location l = c.clone().add((Math.random() - 0.5) * 20, 3 + Math.random() * 7, (Math.random() - 0.5) * 20);
                if (a.in(l)) p.spawnParticle(Particle.FALLING_WATER, l, 1, 0, 0, 0, 0);
            }
            if (ticks % 40 == 0) p.playSound(c, Sound.WEATHER_RAIN, SoundCategory.WEATHER, 0.25f, 1f);
        }
    }

    void music(Player p, Area a) {
        Track t = track(a.music);
        Listen l = hearing.get(p.getUniqueId());
        if (t == null) { if (l != null) stopMusic(p); return; }
        long now = System.currentTimeMillis();
        boolean same = l != null && l.area.equals(a.id) && l.track.equals(t.key) && l.pitch == a.pitch;
        if (same && (now < l.until || !a.loop)) return; // still playing, or it played once and doesn't loop
        if (l != null) p.stopSound(l.track, SoundCategory.RECORDS);
        p.stopSound(SoundCategory.MUSIC); // vanilla's own music steps aside
        p.playSound(p, t.key, SoundCategory.RECORDS, 1f, (float) a.pitch);
        Listen n = new Listen();
        n.area = a.id; n.track = t.key; n.pitch = a.pitch;
        n.until = now + (long) (t.seconds * 1000 / a.pitch) + 300;
        hearing.put(p.getUniqueId(), n);
    }

    void stopMusic(Player p) {
        Listen l = hearing.remove(p.getUniqueId());
        if (l != null && l.track != null) p.stopSound(l.track, SoundCategory.RECORDS);
    }

    void leave(Player p, Area a) {
        inside.remove(p.getUniqueId());
        stopMusic(p);
        p.resetPlayerTime();
        p.resetPlayerWeather();
        if (gaveDark.remove(p.getUniqueId())) p.removePotionEffect(PotionEffectType.DARKNESS);
        if (gaveNv.remove(p.getUniqueId())) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    /** Someone who may not come in: back to where they last stood outside (or just past the nearest edge). */
    void turnBack(Player p, Area a) {
        Location back = lastOut.get(p.getUniqueId());
        if (back == null || at(back) == a || !back.getWorld().equals(p.getWorld())) back = outside(a, p.getLocation());
        if (p.getVehicle() != null) p.leaveVehicle();
        Location t = back.clone(); t.setYaw(p.getLocation().getYaw()); t.setPitch(p.getLocation().getPitch());
        p.teleport(t);
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 2000) {
            warned.put(p.getUniqueId(), now);
            p.sendActionBar(net.kyori.adventure.text.Component.text("You can't go in there.", net.kyori.adventure.text.format.NamedTextColor.RED));
            p.playSound(p, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.PLAYERS, 0.8f, 0.8f);
        }
    }

    /** The nearest spot just outside the area's box, on the ground there. */
    Location outside(Area a, Location from) {
        double x = from.getX(), z = from.getZ();
        double dx1 = x - a.x1, dx2 = a.x2 + 1 - x, dz1 = z - a.z1, dz2 = a.z2 + 1 - z;
        double min = Math.min(Math.min(dx1, dx2), Math.min(dz1, dz2));
        if (min == dx1) x = a.x1 - 1.5; else if (min == dx2) x = a.x2 + 2.5; else if (min == dz1) z = a.z1 - 1.5; else z = a.z2 + 2.5;
        World w = from.getWorld();
        int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        double yy = Math.max(from.getY(), y + 1);
        return new Location(w, x, yy, z);
    }

    void putOut(Area a) {
        for (Player p : Bukkit.getOnlinePlayers()) if (!admin(p) && a.in(p.getLocation())) turnBack(p, a);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        if (admin(p)) return;
        Area a = at(e.getTo());
        if (a == null || a.entry || a.in(e.getFrom())) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 2000) {
            warned.put(p.getUniqueId(), now);
            p.sendActionBar(net.kyori.adventure.text.Component.text("You can't go in there.", net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (admin(p) || e.getTo() == null) return;
        Area a = at(e.getTo());
        if (a == null || a.entry || a.in(e.getFrom())) return;
        e.setCancelled(true);
        p.sendActionBar(net.kyori.adventure.text.Component.text("You can't go in there.", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String was = inside.remove(p.getUniqueId());
        if (was != null) leave(p, areas.get(was));
        hearing.remove(p.getUniqueId());
        lastOut.remove(p.getUniqueId());
        warned.remove(p.getUniqueId());
        picks.remove(p.getUniqueId());
        gaveDark.remove(p.getUniqueId()); gaveNv.remove(p.getUniqueId());
    }

    // ================================================================= the rules: griefing
    boolean noGrief(Location l, Player p) {
        Area a = at(l);
        return a != null && !a.grief && (p == null || !bypass(p));
    }

    void deny(Player p) {
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 1500) {
            warned.put(p.getUniqueId(), now);
            p.sendActionBar(net.kyori.adventure.text.Component.text("This area is protected.", net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (noGrief(e.getBlock().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (boundTo(e.getItemInHand()) != null) return;
        if (noGrief(e.getBlock().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (noGrief(e.getBlock().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (noGrief(e.getBlock().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); } }

    static final Set<Material> WORLD_CHANGERS = Set.of(Material.FLINT_AND_STEEL, Material.FIRE_CHARGE, Material.BONE_MEAL, Material.ARMOR_STAND,
            Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME, Material.PAINTING, Material.END_CRYSTAL, Material.LEAD, Material.SHEARS, Material.BRUSH,
            Material.MINECART, Material.CHEST_MINECART, Material.TNT_MINECART, Material.HOPPER_MINECART, Material.FURNACE_MINECART, Material.HONEYCOMB);

    static boolean changesWorld(Material m) {
        if (WORLD_CHANGERS.contains(m)) return true;
        String n = m.name();
        return n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("_HOE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") && !n.endsWith("PICKAXE");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || blockArea(b) != null) return;
        if (!noGrief(b.getLocation(), e.getPlayer())) return;
        if (e.getAction() == Action.PHYSICAL) { if (b.getType() == Material.FARMLAND || b.getType() == Material.TURTLE_EGG || b.getType() == Material.SNIFFER_EGG) e.setCancelled(true); return; }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        BlockState st = b.getState(false);
        if (st instanceof InventoryHolder || b.getType() == Material.ANVIL || b.getType().name().endsWith("_ANVIL") || b.getType() == Material.JUKEBOX
                || b.getType() == Material.FLOWER_POT || b.getType().name().startsWith("POTTED_") || b.getType() == Material.CAKE || b.getType().name().endsWith("_CANDLE_CAKE")
                || b.getType() == Material.RESPAWN_ANCHOR || b.getType() == Material.CHISELED_BOOKSHELF || b.getType() == Material.DECORATED_POT || b.getType() == Material.LECTERN
                || b.getType().name().endsWith("_SIGN") || b.getType() == Material.COMPOSTER || b.getType() == Material.REPEATER || b.getType() == Material.COMPARATOR
                || b.getType() == Material.DAYLIGHT_DETECTOR || b.getType() == Material.NOTE_BLOCK || b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE
                || b.getType() == Material.BEEHIVE || b.getType() == Material.BEE_NEST || b.getType() == Material.SWEET_BERRY_BUSH || b.getType() == Material.CAVE_VINES || b.getType() == Material.CAVE_VINES_PLANT || b.getType() == Material.CAULDRON || b.getType().name().endsWith("_CAULDRON")) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            deny(e.getPlayer());
            return;
        }
        if (e.getItem() != null && changesWorld(e.getItem().getType())) { e.setUseItemInHand(org.bukkit.event.Event.Result.DENY); deny(e.getPlayer()); }
    }

    /** No hurting or taking the area's villagers, animals, pets, frames and armor stands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurtThing(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        Player p = playerBehind(e.getDamager());
        if (v instanceof Player victim) { // PvP
            if (p == null || p.equals(victim)) return;
            if (noPvp(victim, p)) { e.setCancelled(true); p.sendActionBar(net.kyori.adventure.text.Component.text("PvP is off here.", net.kyori.adventure.text.format.NamedTextColor.RED)); }
            return;
        }
        if (v instanceof Enemy) return;
        if (!(v instanceof LivingEntity) && !(v instanceof Hanging) && !(v instanceof org.bukkit.entity.ArmorStand) && !(v instanceof org.bukkit.entity.Vehicle)) return;
        Area a = at(v.getLocation());
        if (a == null || a.grief) return;
        if (p != null && bypass(p)) return;
        if (p == null && !(e.getDamager() instanceof TNTPrimed) && !(e.getDamager() instanceof org.bukkit.entity.Creeper)) return; // mobs fighting mobs
        e.setCancelled(true);
        if (p != null) deny(p);
    }

    static Player playerBehind(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player p = playerBehind(e.getRemover());
        if (noGrief(e.getEntity().getLocation(), p)) { e.setCancelled(true); if (p != null) deny(p); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) { if (e.getPlayer() != null && noGrief(e.getEntity().getLocation(), e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent e) {
        Entity v = e.getRightClicked();
        if (!noGrief(v.getLocation(), e.getPlayer())) return;
        if (v instanceof Hanging || v instanceof org.bukkit.entity.ArmorStand || v instanceof org.bukkit.entity.minecart.StorageMinecart || v instanceof org.bukkit.entity.ChestBoat
                || v instanceof org.bukkit.entity.ChestedHorse && e.getPlayer().isSneaking()) {
            e.setCancelled(true); deny(e.getPlayer()); return;
        }
        // trading with villagers is fine; leading them off with a lead or a name tag / bucket isn't
        ItemStack hand = e.getHand() == EquipmentSlot.OFF_HAND ? e.getPlayer().getInventory().getItemInOffHand() : e.getPlayer().getInventory().getItemInMainHand();
        if (v instanceof LivingEntity && !(v instanceof Enemy) && hand != null && (hand.getType() == Material.LEAD || hand.getType() == Material.NAME_TAG
                || hand.getType() == Material.SHEARS || hand.getType().name().endsWith("_BUCKET"))) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) { if (noGrief(e.getRightClicked().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) { if (noGrief(e.getEntity().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); } }

    /** Villagers can't be carried off in boats or minecarts. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof AbstractVillager && noGrief(e.getEntered().getLocation(), null)) e.setCancelled(true);
    }

    /** Boats and minecarts: no breaking them (VehicleDamageEvent, not an entity damage event). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleHit(org.bukkit.event.vehicle.VehicleDamageEvent e) {
        Player p = e.getAttacker() == null ? null : playerBehind(e.getAttacker());
        if (p != null && noGrief(e.getVehicle().getLocation(), p)) { e.setCancelled(true); deny(p); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleBreak(org.bukkit.event.vehicle.VehicleDestroyEvent e) {
        Player p = e.getAttacker() == null ? null : playerBehind(e.getAttacker());
        if (p != null && noGrief(e.getVehicle().getLocation(), p)) e.setCancelled(true);
    }

    /** Boats, minecarts, armor stands and end crystals put down by a player. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(org.bukkit.event.entity.EntityPlaceEvent e) {
        if (e.getPlayer() != null && noGrief(e.getEntity().getLocation(), e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    /** Splash / lingering potions thrown by a player don't reach other players where PvP is off. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player thrower)) return;
        for (LivingEntity v : new ArrayList<>(e.getAffectedEntities()))
            if (v instanceof Player victim && !victim.equals(thrower) && noPvp(victim, thrower)) e.setIntensity(v, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(org.bukkit.event.entity.AreaEffectCloudApplyEvent e) {
        if (!(e.getEntity().getSource() instanceof Player thrower)) return;
        e.getAffectedEntities().removeIf(v -> v instanceof Player victim && !victim.equals(thrower) && noPvp(victim, thrower));
    }

    boolean noPvp(Player victim, Player attacker) {
        Area a = at(victim.getLocation()), b = at(attacker.getLocation());
        return a != null && !a.pvp || b != null && !b.pvp;
    }

    /** Hoppers (and hopper minecarts) outside can't pull things out of a protected area's chests. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
        if (areas.isEmpty()) return;
        Location from = e.getSource().getLocation(), to = e.getDestination().getLocation();
        if (from == null || to == null) return;
        Area a = at(from);
        if (a != null && !a.grief && !a.in(to)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> noGrief(b.getLocation(), null)); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> noGrief(b.getLocation(), null)); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (noGrief(e.getBlock().getLocation(), null)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) { if (noGrief(e.getBlock().getLocation(), e.getPlayer())) e.setCancelled(true); return; }
        if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && noGrief(e.getBlock().getLocation(), null)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Area a = at(e.getToBlock().getLocation());
        if (a != null && !a.grief && !a.in(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        Area home = at(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) if (crosses(home, b.getLocation()) || crosses(home, b.getRelative(e.getDirection()).getLocation())) { e.setCancelled(true); return; }
        if (crosses(home, e.getBlock().getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        Area home = at(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) if (crosses(home, b.getLocation())) { e.setCancelled(true); return; }
    }

    /** A protected area that isn't the piston's own. */
    boolean crosses(Area home, Location l) {
        Area a = at(l);
        return a != null && !a.grief && a != home;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof Enemy) && !(e.getEntity() instanceof org.bukkit.entity.EnderDragon)) return; // villagers farming, sheep, bees...
        if (noGrief(e.getBlock().getLocation(), null)) e.setCancelled(true);
    }

    /** The placed block itself is never blown up or pushed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplodeBlock(EntityExplodeEvent e) { e.blockList().removeIf(b -> blockArea(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplodeBlock(BlockExplodeEvent e) { e.blockList().removeIf(b -> blockArea(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonBlock(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (blockArea(b) != null) { e.setCancelled(true); return; } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonBlockIn(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (blockArea(b) != null) { e.setCancelled(true); return; } }

    // ================================================================= saving
    void load() {
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        ConfigurationSection s = y.getConfigurationSection("areas");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Area a = new Area(id);
            a.world = c.getString("world", "world");
            a.x1 = c.getInt("x1"); a.y1 = c.getInt("y1"); a.z1 = c.getInt("z1");
            a.x2 = c.getInt("x2"); a.y2 = c.getInt("y2"); a.z2 = c.getInt("z2");
            a.placed = c.getBoolean("placed", false);
            a.bx = c.getInt("bx"); a.by = c.getInt("by"); a.bz = c.getInt("bz");
            a.music = c.getString("music");
            a.loop = c.getBoolean("loop", true);
            a.pitch = Math.max(0.5, Math.min(2.0, c.getDouble("pitch", 1.0)));
            a.grief = c.getBoolean("grief", false);
            a.pvp = c.getBoolean("pvp", true);
            a.entry = c.getBoolean("entry", true);
            a.light = Math.max(-2, Math.min(2, c.getInt("light", 0)));
            a.weather = c.getString("weather", "normal");
            a.fullHeight();
            areas.put(id, a);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Area a : areas.values()) {
            String k = "areas." + a.id + ".";
            y.set(k + "world", a.world);
            y.set(k + "x1", a.x1); y.set(k + "y1", a.y1); y.set(k + "z1", a.z1);
            y.set(k + "x2", a.x2); y.set(k + "y2", a.y2); y.set(k + "z2", a.z2);
            y.set(k + "placed", a.placed);
            y.set(k + "bx", a.bx); y.set(k + "by", a.by); y.set(k + "bz", a.bz);
            y.set(k + "music", a.music);
            y.set(k + "loop", a.loop);
            y.set(k + "pitch", a.pitch);
            y.set(k + "grief", a.grief);
            y.set(k + "pvp", a.pvp);
            y.set(k + "entry", a.entry);
            y.set(k + "light", a.light);
            y.set(k + "weather", a.weather);
        }
        SafeYaml.write(plugin, file, y.saveToString());
    }

    void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) { String was = inside.get(p.getUniqueId()); if (was != null) leave(p, areas.get(was)); }
        save();
    }
}
