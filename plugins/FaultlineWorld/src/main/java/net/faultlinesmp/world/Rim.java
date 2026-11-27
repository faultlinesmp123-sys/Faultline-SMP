package net.faultlinesmp.world;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * The Outer Rim at runtime (2.0):
 * <ul>
 *   <li>RIM LIGHTHOUSES: light the campfire in a lighthouse's lamp room (flint and steel, a fire charge) and it burns for
 *       {@code rim.lighthouse-minutes} (60). While lit, nothing hostile spawns naturally within {@link RimGen#LIGHT_RADIUS} and
 *       players there carry the metadata {@code faultline_rim_safe} (FaultlineItems skips the Rim pressure, static storms, fake
 *       players and gravity anomalies for them). Then it goes out. Lit lighthouses are kept in the world PDC
 *       ({@code faultlineworld:rim_lights} "x,z=until;...").</li>
 *   <li>LOST VILLAGERS: the first time a lost village's middle chunk loads, two villagers (tag {@code faultline_lost_villager},
 *       persistent, invulnerable, no AI) stand by the well. FaultlineItems gives them their trades.</li>
 *   <li>THE RIM CITADEL: a title the first time each session someone walks in.</li>
 * </ul>
 */
final class Rim implements Listener {
    static final String VILLAGER_TAG = "faultline_lost_villager";
    static final String SAFE_META = "faultline_rim_safe";

    private final FaultlineWorld plugin;
    private final NamespacedKey lightsKey, villagersKey, homeKey;
    private final Map<String, Long> lit = new HashMap<>();   // "x,z" -> until (ms)
    private final Set<UUID> safe = new HashSet<>(), inCitadel = new HashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean loaded;

    Rim(FaultlineWorld plugin) {
        this.plugin = plugin;
        lightsKey = new NamespacedKey(plugin, "rim_lights");
        villagersKey = new NamespacedKey(plugin, "rim_villagers");
        homeKey = new NamespacedKey(plugin, "rim_home");
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::second, 60, 20));
    }

    void stop() {
        for (BukkitTask t : tasks) t.cancel();
        for (UUID id : safe) { Player p = Bukkit.getPlayer(id); if (p != null) p.removeMetadata(SAFE_META, plugin); }
        save();
    }

    private World main() {
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() instanceof WorldGen) return w;
        return null;
    }

    private Terrain terrain(World w) { return plugin.gen.terrain(w); }
    private int minutes() { return plugin.getConfig().getInt("rim.lighthouse-minutes", 60); }

    // ------------------------------------------------------------------ lighthouses

    private void load(World w) {
        if (loaded) return;
        loaded = true;
        String s = w.getPersistentDataContainer().get(lightsKey, PersistentDataType.STRING);
        if (s == null) return;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            try { lit.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
    }

    private void save() {
        World w = main();
        if (w == null) return;
        StringBuilder sb = new StringBuilder();
        for (var e : lit.entrySet()) { if (sb.length() > 0) sb.append(';'); sb.append(e.getKey()).append('=').append(e.getValue()); }
        if (sb.length() == 0) w.getPersistentDataContainer().remove(lightsKey);
        else w.getPersistentDataContainer().set(lightsKey, PersistentDataType.STRING, sb.toString());
    }

    /** True when a lit lighthouse covers (x, z). */
    boolean safeAt(World w, double x, double z) {
        if (lit.isEmpty() || w != main()) return false;
        long now = System.currentTimeMillis();
        for (int[] l : RimGen.lightsNear(terrain(w).seed, x, z, RimGen.LIGHT_RADIUS)) {
            Long until = lit.get(l[0] + "," + l[1]);
            if (until != null && until > now) return true;
        }
        return false;
    }

    private void second() {
        tether();
        World w = main();
        if (w == null) return;
        load(w);
        Terrain t = terrain(w);
        long now = System.currentTimeMillis();
        boolean changed = false;
        // each lighthouse near a player: is its campfire lit? (players light it with flint and steel)
        Set<String> seen = new HashSet<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (!Layout.outerRim(l.getX(), l.getZ())) {
                if (safe.remove(p.getUniqueId())) p.removeMetadata(SAFE_META, plugin);
                inCitadel.remove(p.getUniqueId());
                continue;
            }
            for (int[] lh : RimGen.lightsNear(t.seed, l.getX(), l.getZ(), 64)) {
                String key = lh[0] + "," + lh[1];
                if (!seen.add(key)) continue;
                int[] lamp = RimGen.lamp(t, lh);
                if (!w.isChunkLoaded(lamp[0] >> 4, lamp[2] >> 4)) continue;
                Block b = w.getBlockAt(lamp[0], lamp[1], lamp[2]);
                if (!(b.getBlockData() instanceof Lightable fire) || b.getType() != Material.CAMPFIRE) continue;
                Long until = lit.get(key);
                if (fire.isLit() && (until == null || until <= now)) {
                    lit.put(key, now + minutes() * 60_000L);
                    changed = true;
                    for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(b.getLocation()) < 200 * 200) {
                        q.sendTitle(ChatColor.GOLD + "The lighthouse is lit", ChatColor.GRAY + "The Rim keeps away from it for an hour.", 10, 50, 15);
                        q.playSound(q.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.8f);
                    }
                } else if (until != null && until <= now) {
                    lit.remove(key);
                    changed = true;
                    if (fire.isLit()) { fire.setLit(false); b.setBlockData(fire); }
                    w.playSound(b.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 2f, 0.6f);
                } else if (until != null && fire.isLit() && now % 4 == 0) {
                    w.spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 2.5, 0.5), 6, 0.6, 1.5, 0.6, 0.02);
                }
            }
            boolean s = safeAt(w, l.getX(), l.getZ());
            if (s && safe.add(p.getUniqueId())) { p.setMetadata(SAFE_META, new FixedMetadataValue(plugin, true)); p.sendActionBar(ChatColor.GOLD + "A lit lighthouse keeps the Rim away."); }
            else if (!s && safe.remove(p.getUniqueId())) p.removeMetadata(SAFE_META, plugin);
            // the Citadel's title
            boolean in = RimGen.inCitadel(l.getX(), l.getZ());
            if (in && inCitadel.add(p.getUniqueId()))
                p.sendTitle(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "The Rim Citadel", ChatColor.GRAY + "Whoever held the edge of the world.", 10, 60, 20);
            else if (!in && RimGen.citadelDist(l.getX(), l.getZ()) > 120) inCitadel.remove(p.getUniqueId());
        }
        // lighthouses that ran out where nobody was
        for (Iterator<Map.Entry<String, Long>> it = lit.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() <= now - 3600_000L) { it.remove(); changed = true; }
        if (changed) save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (lit.isEmpty() || !(e.getEntity() instanceof Enemy)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.PATROL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        Location l = e.getLocation();
        if (Layout.outerRim(l.getX(), l.getZ()) && safeAt(l.getWorld(), l.getX(), l.getZ())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        safe.remove(e.getPlayer().getUniqueId());
        inCitadel.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeMetadata(SAFE_META, plugin);
    }

    // ------------------------------------------------------------------ lost villagers

    /** True when the village's well is really there (a chunk made before the Outer Rim existed has no village). */
    private static boolean wellThere(World w, int[] v) {
        return w.getBlockAt(v[0] + 2, Terrain.SEA + 18, v[1]).getType() == Material.MOSSY_COBBLESTONE;
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (w != main()) return;
        Chunk ch = e.getChunk();
        int bx = ch.getX() << 4, bz = ch.getZ() << 4;
        if (!Layout.outerRim(bx + 8, bz + 8) || ch.getPersistentDataContainer().has(villagersKey, PersistentDataType.BYTE)) return;
        Terrain t = terrain(w);
        for (int[] v : RimGen.villagesNear(t.seed, bx + 8, bz + 8)) {
            if (v[0] >> 4 != ch.getX() || v[1] >> 4 != ch.getZ()) continue;
            // AUDIT: a chunk generated before the update has no village: no villagers floating in the air there
            if (!wellThere(w, v)) return;
            ch.getPersistentDataContainer().set(villagersKey, PersistentDataType.BYTE, (byte) 1);
            Bukkit.getScheduler().runTask(plugin, () -> spawnVillagers(w, v));
        }
    }

    private void spawnVillagers(World w, int[] v) {
        int g = Terrain.SEA + 18;
        for (int k = 0; k < 2; k++) {
            Location at = new Location(w, v[0] + (k == 0 ? 3.5 : -2.5), g + 1, v[1] + 3.5);
            Villager vi = (Villager) w.spawnEntity(at, EntityType.VILLAGER, CreatureSpawnEvent.SpawnReason.CUSTOM);
            vi.addScoreboardTag(VILLAGER_TAG);
            vi.setCustomName("Lost Villager");
            vi.setCustomNameVisible(true);
            vi.setPersistent(true);
            vi.setRemoveWhenFarAway(false);
            vi.setInvulnerable(true);
            vi.setAI(true);      // they walk about like normal villagers (kept near the well: tether())
            try { vi.setProfession(Villager.Profession.CLERIC); vi.setVillagerLevel(5); vi.setVillagerExperience(250); } catch (Throwable ignored) { }
            vi.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, v[0] + ";" + v[1]);
            lost.add(vi.getUniqueId());
        }
    }

    /** Lost Villagers wander like normal ones but are walked back when they stray past 26 blocks from their well. */
    private final Set<UUID> lost = new HashSet<>();
    private void tether() {
        for (Iterator<UUID> it = lost.iterator(); it.hasNext(); ) {
            Entity en = Bukkit.getEntity(it.next());
            if (!(en instanceof Villager vi) || !vi.isValid()) { it.remove(); continue; }
            String h = vi.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
            if (h == null) continue;
            String[] p = h.split(";");
            double hx = Integer.parseInt(p[0]) + 0.5, hz = Integer.parseInt(p[1]) + 0.5;
            Location l = vi.getLocation();
            double d = Math.hypot(l.getX() - hx, l.getZ() - hz);
            if (d > 60) { vi.teleport(new Location(vi.getWorld(), hx + 3, Terrain.SEA + 19, hz + 3)); continue; }
            if (d > 26) vi.getPathfinder().moveTo(new Location(vi.getWorld(), hx + 3, Terrain.SEA + 19, hz + 3), 0.6);
        }
    }

    /** Lost Villagers left floating where no village was built (spawned by the first 2.0 jar in old chunks) are removed. */
    @EventHandler
    public void onEntities(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (!en.getScoreboardTags().contains(VILLAGER_TAG)) continue;
            Block under = en.getLocation().getBlock().getRelative(0, -1, 0);
            if (!under.getType().isAir() || !((LivingEntity) en).hasAI()) {
                if (en instanceof Villager vi && !under.getType().isAir()) {          // older ones stood frozen: let them walk
                    vi.setAI(true);
                    if (!vi.getPersistentDataContainer().has(homeKey, PersistentDataType.STRING)) {
                        Location l = vi.getLocation();
                        int[] best = null; double bd = 1e9;
                        for (int[] v : RimGen.villagesNear(terrain(vi.getWorld()).seed, l.getX(), l.getZ())) { double d = Math.hypot(v[0] - l.getX(), v[1] - l.getZ()); if (d < bd) { bd = d; best = v; } }
                        if (best != null) vi.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, best[0] + ";" + best[1]);
                    }
                    lost.add(vi.getUniqueId());
                }
                if (!under.getType().isAir()) continue;
            }
            Chunk ch = en.getLocation().getChunk();
            en.remove();
            ch.getPersistentDataContainer().remove(villagersKey);
        }
    }

    // ------------------------------------------------------------------ /fworld rim list | build

    /** Nearest places of one kind: {x, y, z} sorted by distance (n of them). */
    List<int[]> nearest(World w, String kind, double px, double pz, int n) {
        Terrain t = terrain(w);
        List<int[]> out = new ArrayList<>();
        if (kind.equals("citadel")) { out.add(new int[]{RimGen.CITADEL[0], RimGen.CITADEL_Y + 1, RimGen.CITADEL[1]}); return out; }
        int cell = kind.equals("village") ? RimGen.VILLAGE_CELL : kind.equals("lighthouse") ? RimGen.LIGHT_CELL : RimGen.GLITCH_CELL;
        int span = Layout.BORDER * 2 / cell + 2;
        int ci0 = Math.floorDiv((int) px, cell), cj0 = Math.floorDiv((int) pz, cell);
        int reach = Math.min(span, 40);
        for (int a = -reach; a <= reach; a++) for (int b = -reach; b <= reach; b++) {
            int[] f = kind.equals("village") ? RimGen.villageAt(t.seed, ci0 + a, cj0 + b) : kind.equals("lighthouse") ? RimGen.lightAt(t.seed, ci0 + a, cj0 + b) : RimGen.glitchAt(t.seed, ci0 + a, cj0 + b);
            if (f == null) continue;
            int y = kind.equals("village") ? Terrain.SEA + 19 : kind.equals("lighthouse") ? RimGen.lamp(t, new int[]{f[0], f[1]})[1] : t.column(f[0], f[1]).surface + 1;
            out.add(new int[]{f[0], y, f[1], kind.equals("glitch") ? f[2] : 0});
        }
        out.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - px, c[2] - pz)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** How far round its middle a place reaches (what a rebuild redraws). */
    static int reach(String kind, int[] at) {
        return switch (kind) {
            case "citadel" -> RimGen.CITADEL_PAD + 45;
            case "village" -> RimGen.VILLAGE_PAD + RimGen.VILLAGE_BLEND;
            case "lighthouse" -> 8;
            default -> at.length > 3 ? at[3] + 12 : 50;
        };
    }

    private BukkitTask building;

    /**
     * Redraws one Outer Rim place into chunks that were generated before it existed: every chunk it touches is generated
     * again by the Faultline generator into memory, and inside {@code reach} of its middle every block that differs is put in
     * the world (block entities a player placed are left alone). A few chunks a tick. Then chests / spawners get their loot and
     * a village gets its villagers.
     */
    boolean build(Player p, String kind, int[] at) {
        if (building != null) { p.sendMessage(ChatColor.RED + "A rebuild is already running."); return false; }
        World w = p.getWorld();
        int r = reach(kind, at);
        int cx0 = (at[0] - r) >> 4, cx1 = (at[0] + r) >> 4, cz0 = (at[2] - r) >> 4, cz1 = (at[2] + r) >> 4;
        Deque<int[]> chunks = new ArrayDeque<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) chunks.add(new int[]{cx, cz});
        int total = chunks.size();
        long[] changed = {0};
        p.sendMessage(ChatColor.GOLD + "Rebuilding the " + kind + " at " + at[0] + " " + at[2] + " (" + total + " chunks)...");
        building = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long t0 = System.nanoTime();
            while (!chunks.isEmpty() && System.nanoTime() - t0 < 25_000_000L) {
                int[] c = chunks.poll();
                try { changed[0] += redraw(w, c[0], c[1], at[0], at[2], r); }
                catch (Throwable err) { plugin.getLogger().warning("[Rim] rebuilding chunk " + c[0] + "," + c[1] + ": " + err); }
            }
            if (!chunks.isEmpty()) return;
            building.cancel();
            building = null;
            if (kind.equals("village")) {
                Chunk ch = w.getChunkAt(at[0] >> 4, at[2] >> 4);
                if (!ch.getPersistentDataContainer().has(villagersKey, PersistentDataType.BYTE) && wellThere(w, new int[]{at[0], at[2]})) {
                    ch.getPersistentDataContainer().set(villagersKey, PersistentDataType.BYTE, (byte) 1);
                    spawnVillagers(w, new int[]{at[0], at[2]});
                }
            }
            plugin.getLogger().info("[Rim] rebuilt the " + kind + " at " + at[0] + " " + at[2] + ": " + changed[0] + " blocks");
            if (p.isOnline()) p.sendMessage(ChatColor.GOLD + "Done: the " + kind + " at " + at[0] + " " + at[1] + " " + at[2] + " (" + changed[0] + " blocks changed).");
        }, 1, 1);
        return true;
    }

    private long redraw(World w, int cx, int cz, int mx, int mz, int r) {
        Chunk ch = w.getChunkAt(cx, cz);
        int minY = w.getMinHeight(), maxY = w.getMaxHeight(), h = maxY - minY;
        BlockData[] a = new BlockData[16 * 16 * h];
        BlockData air = Bukkit.createBlockData(Material.AIR);
        java.util.Arrays.fill(a, air);
        ChunkData cd = (ChunkData) java.lang.reflect.Proxy.newProxyInstance(Rim.class.getClassLoader(), new Class<?>[]{ChunkData.class}, (o, m, args) -> {
            switch (m.getName()) {
                case "setBlock" -> {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < minY || y >= maxY || x < 0 || x > 15 || z < 0 || z > 15) return null;
                    a[((y - minY) * 16 + z) * 16 + x] = args[3] instanceof Material mm ? mm.createBlockData() : (BlockData) args[3];
                    return null;
                }
                case "setRegion" -> {
                    int x0 = (int) args[0], y0 = (int) args[1], z0 = (int) args[2], x1 = (int) args[3], y1 = (int) args[4], z1 = (int) args[5];
                    BlockData bd = args[6] instanceof Material mm ? mm.createBlockData() : (BlockData) args[6];
                    for (int x = Math.max(0, x0); x < Math.min(16, x1); x++) for (int z = Math.max(0, z0); z < Math.min(16, z1); z++)
                        for (int y = Math.max(minY, y0); y < Math.min(maxY, y1); y++) a[((y - minY) * 16 + z) * 16 + x] = bd;
                    return null;
                }
                case "getType" -> {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < minY || y >= maxY || x < 0 || x > 15 || z < 0 || z > 15) return Material.AIR;
                    return a[((y - minY) * 16 + z) * 16 + x].getMaterial();
                }
                case "getBlockData" -> {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < minY || y >= maxY || x < 0 || x > 15 || z < 0 || z > 15) return air;
                    return a[((y - minY) * 16 + z) * 16 + x];
                }
                case "getMinHeight" -> { return minY; }
                case "getMaxHeight" -> { return maxY; }
                case "hashCode" -> { return System.identityHashCode(o); }
                case "equals" -> { return o == args[0]; }
                default -> { return null; }
            }
        });
        WorldGen gen = (WorldGen) w.getGenerator();
        gen.generateNoise(w, new Random(w.getSeed() ^ ((long) cx << 32) ^ cz), cx, cz, cd);
        gen.generateSurface(w, new Random(w.getSeed() ^ ((long) cx << 32) ^ cz), cx, cz, cd);
        ChunkSnapshot snap = ch.getChunkSnapshot(false, false, false);
        long n = 0;
        int bx = cx << 4, bz = cz << 4;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (Math.hypot(bx + x - mx, bz + z - mz) > r) continue;
            for (int y = minY; y < maxY; y++) {
                BlockData want = a[((y - minY) * 16 + z) * 16 + x];
                BlockData have = snap.getBlockData(x, y, z);
                if (have.equals(want)) continue;
                Block b = ch.getBlock(x, y, z);
                if (b.getState(false) instanceof org.bukkit.block.TileState) continue;   // chests / furnaces / signs already there stay
                b.setBlockData(want, false);
                n++;
            }
        }
        // loot for the new chests / spawners (the chunk's "filled" mark was set when it first loaded)
        if (plugin.life != null) try { plugin.life.fillChunk(ch); } catch (Throwable ignored) { }
        return n;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(VILLAGER_TAG)) e.setCancelled(true);
    }
}
