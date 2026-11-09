package net.faultlinesmp.items;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 1.3.0 (Items): client mods.
 *
 * MAP MODS (`mod-guard:`): on join every Java player is sent Xaero's switch-off codes in chat (invisible lines; Xaero's
 * Minimap reads them: the minimap off + fair play = no cave map / entity radar). Any OTHER map mod the client announces
 * (a plugin channel or its client brand matching `map-patterns`, e.g. Xaero's World Map, JourneyMap, VoxelMap, FTB Chunks,
 * a homemade "...map" mod) can't be switched off from the server, so `map-action` kick (default) tells them to remove it.
 * PIE CHART MODS: anything matching `pie-patterns` = kicked. A mod that announces nothing can't be seen by any server.
 *
 * PIE CHART LIMIT (`pie-limit:`): F3's pie chart counts the block entities the client is drawing, so it shows chests behind
 * walls. Every chunk sent to a Java player has its rendered block entities (chests, shulkers, beds, signs, banners, heads,
 * spawners, TRIAL SPAWNERS, VAULTS, pots...; never ENDER CHESTS) swapped for a fake block (the wall around it, or air /
 * water if it's open) until the player is within `reveal-distance` and can SEE it (a block walk from the eyes), or within
 * `touch-distance`. Revealed ones go back to fake past `rehide-distance`. Fake blocks are client-side only (the world never
 * changes). Bedrock players (no pie chart), `faultline.piechart.bypass`, creative/spectator skip it.
 */
final class ModGuard implements Listener {

    private final FaultlineItems plugin;
    /** Per player: chunk key -> positions hidden from them (packed), and the ones revealed. */
    private final Map<UUID, Map<Long, Set<Long>>> hidden = new HashMap<>();
    private final Map<UUID, Map<Long, Set<Long>>> shown = new HashMap<>();
    private final Map<UUID, UUID> worldOf = new HashMap<>();
    private Set<Material> hide = EnumSet.noneOf(Material.class);
    private List<Pattern> mapPatterns = List.of(), mapAllow = List.of(), piePatterns = List.of();
    private int tick;

    /** Rendered block entities; the ender chest stays (the owner wants it to show). */
    static final String[] DEFAULT_HIDE = {
            "CHEST", "TRAPPED_CHEST", "*SHULKER_BOX", "*_BED", "*_BANNER", "*_SIGN", "*_HANGING_SIGN",
            "*_HEAD", "*_SKULL", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "ENCHANTING_TABLE", "BEACON", "BELL", "CONDUIT",
            "CAMPFIRE", "SOUL_CAMPFIRE", "LECTERN", "DECORATED_POT", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "END_GATEWAY",
            "COPPER_GOLEM_STATUE", "*_COPPER_GOLEM_STATUE", "SHELF", "*_SHELF"};

    static final String[] DEFAULT_MAPS = {
            "(?i)world_?map", "(?i)journey_?map", "(?i)voxel_?map", "(?i)ftb_?chunks", "(?i)map_?frontiers", "(?i)antique_?atlas",
            "(?i)mini_?map", "(?i)maps?(?=[:_./-]|$)", "(?i)atlas(?=[:_./-]|$)", "(?i)radar"}; // any "...map" mod, homemade too
    static final String[] DEFAULT_ALLOW = {"(?i)^xaerominimap", "(?i)^xaero_minimap", "(?i)(key|bit|tone|mip|cube|shadow|normal|light|height|texture|block|item|color|colour|font)_?maps?(?=[:_./-]|$)"}; // switched off by the codes instead
    static final String[] DEFAULT_PIE = {"(?i)pie.?chart", "(?i)pie.?ray", "(?i)pie.?menu"};
    /** Xaero's Minimap: no minimap, fair play (no cave mode / entity radar). Sent as invisible chat lines. */
    static final String[] DEFAULT_CODES = {"§n§o§m§i§n§i§m§a§p", "§f§a§i§r§x§a§e§r§o"};

    ModGuard(FaultlineItems plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    void reload() {
        Set<Material> h = EnumSet.noneOf(Material.class);
        List<String> names = plugin.getConfig().isList("pie-limit.hide") ? plugin.getConfig().getStringList("pie-limit.hide") : List.of(DEFAULT_HIDE);
        for (String n : names) {
            String u = n.trim().toUpperCase(Locale.ROOT);
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isBlock()) continue;
                if (u.startsWith("*") ? m.name().endsWith(u.substring(1)) : m.name().equals(u)) h.add(m);
            }
        }
        h.remove(Material.ENDER_CHEST);
        hide = h;
        mapPatterns = patterns("mod-guard.map-patterns", DEFAULT_MAPS);
        mapAllow = patterns("mod-guard.map-allow", DEFAULT_ALLOW);
        piePatterns = patterns("mod-guard.pie-patterns", DEFAULT_PIE);
    }

    private List<Pattern> patterns(String path, String[] def) {
        List<String> raw = plugin.getConfig().isList(path) ? plugin.getConfig().getStringList(path) : List.of(def);
        List<Pattern> out = new ArrayList<>();
        for (String r : raw) try { out.add(Pattern.compile(r)); } catch (RuntimeException ex) { plugin.getLogger().warning("[ModGuard] bad pattern " + r + ": " + ex.getMessage()); }
        return out;
    }

    boolean guardOn() { return plugin.getConfig().getBoolean("mod-guard.enabled", true); }
    boolean pieOn() { return plugin.getConfig().getBoolean("pie-limit.enabled", true); }

    // ================================================================ mods

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!guardOn() || Edition.bedrock(p)) return;
        if (plugin.getConfig().getBoolean("mod-guard.xaero-codes", true)) {
            List<String> codes = plugin.getConfig().isList("mod-guard.codes") ? plugin.getConfig().getStringList("mod-guard.codes") : List.of(DEFAULT_CODES);
            // literal text, not Bukkit colour translation: "&i" isn't a colour code, so translation would leave the code broken
            for (String c : codes) p.sendMessage(net.kyori.adventure.text.Component.text(c.replace('&', '\u00a7')));
        }
        // channels can arrive after the join (configuration phase / late registers): check now and a bit later
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) checkAll(p); }, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) checkAll(p); }, 200L);
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        if (!guardOn() || Edition.bedrock(e.getPlayer())) return;
        Player p = e.getPlayer();
        String ch = e.getChannel();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) check(p, ch, "channel"); });
    }

    void checkAll(Player p) {
        if (p.hasPermission("faultline.modguard.bypass")) return;
        String brand = null;
        try { brand = p.getClientBrandName(); } catch (Throwable ignored) { }
        if (brand != null && check(p, brand, "client")) return;
        for (String ch : new ArrayList<>(p.getListeningPluginChannels())) if (check(p, ch, "channel")) return;
    }

    /** True when it kicked them. */
    boolean check(Player p, String what, String kind) {
        if (what == null || what.isEmpty() || p.hasPermission("faultline.modguard.bypass")) return false;
        for (Pattern pt : piePatterns) if (pt.matcher(what).find()) return act(p, what, kind, "pie-action", "pie chart mods");
        for (Pattern a : mapAllow) if (a.matcher(what).find()) return false;
        for (Pattern pt : mapPatterns) if (pt.matcher(what).find()) return act(p, what, kind, "map-action", "map mods");
        return false;
    }

    private boolean act(Player p, String what, String kind, String key, String label) {
        String action = plugin.getConfig().getString("mod-guard." + key, "kick").toLowerCase(Locale.ROOT);
        plugin.getLogger().info("[ModGuard] " + p.getName() + " has " + label + " (" + kind + " " + what + "): " + action);
        if (action.equals("kick")) {
            p.kickPlayer(ChatColor.RED + "Faultline doesn't allow " + label + ".\n" + ChatColor.GRAY + "Found: " + what
                    + "\n" + ChatColor.WHITE + "Remove it and join again. (Xaero's Minimap is fine: it switches itself off here.)");
            return true;
        }
        if (action.equals("warn")) p.sendMessage(ChatColor.RED + "[Faultline] " + label + " aren't allowed here (" + what + ").");
        return false;
    }

    /** The codes in the server list's MOTD too (Xaero reads them there as well). */
    @EventHandler
    public void onPing(com.destroystokyo.paper.event.server.PaperServerListPingEvent e) {
        if (!guardOn() || !plugin.getConfig().getBoolean("mod-guard.motd-codes", true) || !plugin.getConfig().getBoolean("mod-guard.xaero-codes", true)) return;
        List<String> codes = plugin.getConfig().isList("mod-guard.codes") ? plugin.getConfig().getStringList("mod-guard.codes") : List.of(DEFAULT_CODES);
        StringBuilder sb = new StringBuilder();
        for (String c : codes) sb.append(c.replace('&', '\u00a7'));
        try { e.motd(e.motd().append(net.kyori.adventure.text.Component.text(sb.toString()))); } catch (Throwable ignored) { }
    }

    // ================================================================ the pie chart

    boolean pieFor(Player p) {
        return pieOn() && !Edition.bedrock(p) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR
                && !p.hasPermission("faultline.piechart.bypass");
    }

    static long pack(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    static int ux(long k) { return (int) (k >> 38); }
    static int uz(long k) { return (int) (k << 26 >> 38); }
    static int uy(long k) { return (int) (k << 52 >> 52); }
    static long chunkKey(int cx, int cz) { return ((long) cz << 32) | (cx & 0xFFFFFFFFL); }

    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent e) {
        Player p = e.getPlayer();
        if (!pieFor(p)) return;
        Chunk c = e.getChunk();
        List<Block> blocks = new ArrayList<>();
        try {
            for (BlockState s : c.getTileEntities(b -> hide.contains(b.getType()), false)) blocks.add(s.getBlock());
        } catch (Throwable t) { // a server without the filtered lookup (tests): every block entity, filtered here
            try { for (BlockState s : c.getTileEntities(false)) if (hide.contains(s.getType())) blocks.add(s.getBlock()); }
            catch (Throwable t2) { scan(c, blocks); }
        }
        if (blocks.isEmpty()) return;
        // after the chunk packet: next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.getWorld() != c.getWorld() || !sent(p, chunkKey(c.getX(), c.getZ()))) return;
            hideNow(p, blocks, false);
        });
    }

    static boolean sent(Player p, long ck) { try { return p.isChunkSent(ck); } catch (Throwable t) { return true; } }

    /** Last resort (only where block entities can't be listed): look at every block of the chunk. */
    private void scan(Chunk c, List<Block> out) {
        World w = c.getWorld();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = w.getMinHeight(); y < w.getMaxHeight(); y++) {
            Block b = c.getBlock(x, y, z);
            if (hide.contains(b.getType())) out.add(b);
        }
    }

    @EventHandler
    public void onChunkGone(PlayerChunkUnloadEvent e) {
        long ck = chunkKey(e.getChunk().getX(), e.getChunk().getZ());
        UUID id = e.getPlayer().getUniqueId();
        Map<Long, Set<Long>> h = hidden.get(id), s = shown.get(id);
        if (h != null) h.remove(ck);
        if (s != null) s.remove(ck);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!pieOn() || !hide.contains(e.getBlockPlaced().getType())) return;
        Block b = e.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!hide.contains(b.getType())) return;
            long ck = chunkKey(b.getX() >> 4, b.getZ() >> 4);
            for (Player p : b.getWorld().getPlayers()) if (pieFor(p) && sent(p, ck)) hideNow(p, List.of(b), true);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { forget(e.getPlayer()); }

    void forget(Player p) { hidden.remove(p.getUniqueId()); shown.remove(p.getUniqueId()); worldOf.remove(p.getUniqueId()); }

    /** Fakes these for p unless it may see them now. `place` = a block just placed (nearby + seen ones stay real). */
    void hideNow(Player p, List<Block> blocks, boolean place) {
        UUID id = p.getUniqueId();
        UUID w = p.getWorld().getUID();
        if (!w.equals(worldOf.get(id))) { hidden.remove(id); shown.remove(id); worldOf.put(id, w); }
        Map<Long, Set<Long>> h = hidden.computeIfAbsent(id, k -> new HashMap<>());
        Map<Long, Set<Long>> s = shown.computeIfAbsent(id, k -> new HashMap<>());
        Map<Position, BlockData> fakes = new HashMap<>();
        Location eye = p.getEyeLocation();
        for (Block b : blocks) {
            if (!hide.contains(b.getType())) continue;
            long k = pack(b.getX(), b.getY(), b.getZ()), ck = chunkKey(b.getX() >> 4, b.getZ() >> 4);
            if (may(p, eye, b)) { s.computeIfAbsent(ck, x -> new HashSet<>()).add(k); continue; }
            h.computeIfAbsent(ck, x -> new HashSet<>()).add(k);
            Set<Long> ss = s.get(ck); if (ss != null) ss.remove(k);
            fakes.put(Position.block(b.getX(), b.getY(), b.getZ()), fake(b));
        }
        if (!fakes.isEmpty()) try { p.sendMultiBlockChange(fakes); } catch (Throwable t) { for (var en : fakes.entrySet()) p.sendBlockChange(en.getKey().toLocation(b0(p)), en.getValue()); }
    }

    private static World b0(Player p) { return p.getWorld(); }

    /** What the client gets instead: the wall it's set in (all 6 sides solid), else air / water. */
    static BlockData fake(Block b) {
        BlockData real = b.getBlockData();
        boolean wet = real instanceof Waterlogged wl && wl.isWaterlogged();
        Material wall = null;
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Material t = b.getRelative(f).getType();
            if (!t.isOccluding()) return (wet ? Material.WATER : Material.AIR).createBlockData();
            if (wall == null) wall = t;
        }
        return wall != null ? wall.createBlockData() : Material.STONE.createBlockData();
    }

    /** Close enough to touch, or within reveal distance and in sight. */
    boolean may(Player p, Location eye, Block b) {
        if (!p.getWorld().equals(b.getWorld())) return false;
        double d2 = eye.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5));
        double touch = plugin.getConfig().getDouble("pie-limit.touch-distance", 6), reveal = plugin.getConfig().getDouble("pie-limit.reveal-distance", 24);
        if (d2 <= touch * touch) return true;
        return d2 <= reveal * reveal && seesBlock(eye, b);
    }

    /** A block walk from the eyes to the block's middle: any solid in between (other than it) blocks it. */
    static boolean seesBlock(Location eye, Block b) {
        Vector to = new Vector(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5).subtract(eye.toVector());
        double len = to.length();
        if (len < 1e-3) return true;
        to.multiply(1 / len);
        World w = eye.getWorld();
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (double t = 0.3; t < len - 0.6; t += 0.3) {
            int x = (int) Math.floor(eye.getX() + to.getX() * t), y = (int) Math.floor(eye.getY() + to.getY() * t), z = (int) Math.floor(eye.getZ() + to.getZ() * t);
            if (x == lx && y == ly && z == lz) continue;
            lx = x; ly = y; lz = z;
            if (x == b.getX() && y == b.getY() && z == b.getZ()) break;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isOccluding()) return false;
        }
        return true;
    }

    /** Every 5 ticks: reveal what each player may see now, fake again what they walked far from. */
    private void tick() {
        tick++;
        if (!pieOn()) return;
        double rehide = plugin.getConfig().getDouble("pie-limit.rehide-distance", 32);
        int budget = plugin.getConfig().getInt("pie-limit.checks-per-player", 96);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Map<Long, Set<Long>> h = hidden.get(id), s = shown.get(id);
            if (h == null) continue;
            if (!p.getWorld().getUID().equals(worldOf.get(id))) { forget(p); continue; }
            if (!pieFor(p)) { revealAll(p, h); continue; } // went creative / got the bypass: everything real again
            World w = p.getWorld();
            Location eye = p.getEyeLocation();
            int pcx = eye.getBlockX() >> 4, pcz = eye.getBlockZ() >> 4;
            int reach = (int) Math.ceil(plugin.getConfig().getDouble("pie-limit.reveal-distance", 24) / 16) + 1;
            List<Block> reveal = new ArrayList<>();
            int checks = 0;
            for (int dx = -reach; dx <= reach && checks < budget; dx++) for (int dz = -reach; dz <= reach && checks < budget; dz++) {
                Set<Long> set = h.get(chunkKey(pcx + dx, pcz + dz));
                if (set == null || set.isEmpty()) continue;
                for (Iterator<Long> it = set.iterator(); it.hasNext() && checks < budget; ) {
                    long k = it.next();
                    Block b = w.getBlockAt(ux(k), uy(k), uz(k));
                    if (!hide.contains(b.getType())) { it.remove(); continue; } // broken: the server already sent the truth
                    checks++;
                    if (may(p, eye, b)) { it.remove(); reveal.add(b); }
                }
            }
            for (Block b : reveal) {
                show(p, b);
                s.computeIfAbsent(chunkKey(b.getX() >> 4, b.getZ() >> 4), x -> new HashSet<>()).add(pack(b.getX(), b.getY(), b.getZ()));
            }
            if (tick % 4 == 0) { // walked away: fake it again
                List<Block> again = new ArrayList<>();
                for (Iterator<Map.Entry<Long, Set<Long>>> ci = s.entrySet().iterator(); ci.hasNext(); ) {
                    Set<Long> set = ci.next().getValue();
                    for (Iterator<Long> it = set.iterator(); it.hasNext(); ) {
                        long k = it.next();
                        double ddx = ux(k) + 0.5 - eye.getX(), ddy = uy(k) + 0.5 - eye.getY(), ddz = uz(k) + 0.5 - eye.getZ();
                        if (ddx * ddx + ddy * ddy + ddz * ddz > rehide * rehide) { it.remove(); again.add(w.getBlockAt(ux(k), uy(k), uz(k))); }
                    }
                    if (set.isEmpty()) ci.remove();
                }
                if (!again.isEmpty()) hideNow(p, again, false);
            }
        }
    }

    private void revealAll(Player p, Map<Long, Set<Long>> h) {
        World w = p.getWorld();
        for (Set<Long> set : h.values()) for (long k : set) show(p, w.getBlockAt(ux(k), uy(k), uz(k)));
        forget(p);
    }

    /** The real block back, with its block entity data (sign text, banner pattern, head skin, the spawner's mob...). */
    static void show(Player p, Block b) {
        p.sendBlockChange(b.getLocation(), b.getBlockData());
        try { if (b.getState(false) instanceof TileState ts) p.sendBlockUpdate(b.getLocation(), ts); } catch (Throwable ignored) { }
    }

    /** For tests / status. */
    int hiddenFor(Player p) {
        Map<Long, Set<Long>> h = hidden.get(p.getUniqueId());
        if (h == null) return 0;
        int n = 0; for (Set<Long> s : h.values()) n += s.size(); return n;
    }
}
