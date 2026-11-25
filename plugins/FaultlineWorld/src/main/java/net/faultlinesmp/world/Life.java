package net.faultlinesmp.world;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * The Faultline overworld at runtime: protection (pyramids, admin cores), loot + spawner mobs in freshly generated
 * structures, the pitch-black abyss and its music, the holes down to the Dark Ocean, the Inferno Forest (mobs day and
 * night that never burn), the burning Wasteland and its acid rain (the Umbrella keeps you dry), the Fibers (rare
 * elites, the wild Queen Spider 5%, her servants, the Zombie Giant 1%), the Mire's crowds and the illager cities.
 */
final class Life implements Listener {
    final FaultlineWorld plugin;
    final Random random = new Random();
    final List<BukkitTask> tasks = new ArrayList<>();
    final Set<UUID> abyssMusic = new HashSet<>();
    final Set<UUID> wasteMusic = new HashSet<>();
    final Map<UUID, Long> musicAt = new HashMap<>();
    final NamespacedKey extraKey;
    final NamespacedKey filledKey;
    final Set<UUID> falling = new HashSet<>();
    final Set<UUID> wasteRain = new HashSet<>();
    final Map<Long, Long> cityWaveAt = new HashMap<>(), cityEvokerAt = new HashMap<>();
    final NamespacedKey umbrellaKey = new NamespacedKey("faultlineitems", "umbrella");
    final Giants giants;

    Life(FaultlineWorld plugin) {
        this.plugin = plugin;
        this.extraKey = new NamespacedKey(plugin, "extra_spawn");
        this.filledKey = new NamespacedKey(plugin, "filled");
        this.giants = new Giants(plugin);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::second, 40, 20));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::spawns, 100, 100));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::rare, 1200, 1200));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, giants::tick, 1, 1));
        Bukkit.getPluginManager().registerEvents(giants, plugin);
    }

    void stop() { for (BukkitTask t : tasks) t.cancel(); giants.removeAll(); }

    World main() { return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0); }

    boolean ours(World w) { return w != null && w.getGenerator() instanceof WorldGen; }

    Terrain terrain(World w) { return plugin.gen.terrain(w); }

    /** Where a player is: the surface biome or the underground one. */
    B where(Location l) {
        Terrain t = terrain(l.getWorld());
        Terrain.Col c = t.column(l.getBlockX(), l.getBlockZ());
        B u = t.underground(l.getBlockX(), l.getBlockY(), l.getBlockZ(), c);
        if (u == null && l.getBlockY() < c.surface - 14 && !c.sea) return null;   // ordinary caves
        return u != null ? u : c.biome;
    }

    static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    // ------------------------------------------------------------------ protection

    boolean protectedAt(Block b) {
        if (!ours(b.getWorld())) return false;
        Terrain t = terrain(b.getWorld());
        return plugin.getConfig().getBoolean("protect.pyramids", true) && Structures.inPyramid(t, b.getX(), b.getY(), b.getZ())
                || plugin.getConfig().getBoolean("protect.cores", true) && Structures.inCore(t, b.getX(), b.getY(), b.getZ());
    }

    boolean exempt(Player p) { return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("faultlineworld.build"); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (exempt(e.getPlayer()) || !protectedAt(e.getBlock())) return;
        Material m = e.getBlock().getType();
        // the loot inside can be taken; the building can't be broken
        if (m == Material.CHEST || m == Material.DECORATED_POT || m == Material.COBWEB || m == Material.SPAWNER || m == Material.TRIAL_SPAWNER) {
            if (m == Material.CHEST || m == Material.DECORATED_POT || m == Material.COBWEB) return;
        }
        e.setCancelled(true);
        e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("The ancient stone won't break.", net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (exempt(e.getPlayer()) || !protectedAt(e.getBlock())) return;
        if (e.getBlock().getType() == Material.TORCH || e.getBlock().getType() == Material.WALL_TORCH) return;  // light your way through the maze
        e.setCancelled(true);
    }

    /** AUDIT: buckets (flooding the maze, lava) and mobs that change blocks (Withers, endermen, ravagers) were missed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (!exempt(e.getPlayer()) && protectedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        if (!exempt(e.getPlayer()) && protectedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChange(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof FallingBlock) return;
        if (protectedAt(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::protectedAt); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::protectedAt); }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (protectedAt(b)) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onPistonBack(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (protectedAt(b)) { e.setCancelled(true); return; } }

    /**
     * AUDIT: vanilla structures (villages, mineshafts, strongholds, trial chambers, ancient cities...) don't know our builds are
     * there. One whose box touches a faction build, a pyramid (incl. its underworld) or an admin core isn't generated, so no
     * mineshaft cuts a way through the maze and no village lands in a castle. Async event: only pure maths here.
     */
    @EventHandler(ignoreCancelled = true)
    public void onStructure(org.bukkit.event.world.AsyncStructureSpawnEvent e) {
        try {
            if (!ours(e.getWorld())) return;
            org.bukkit.util.BoundingBox bb = e.getBoundingBox();
            if (bb == null) return;
            Terrain t = terrain(e.getWorld());
            // by FOOTPRINT, at any height (a jungle temple had landed on a shrine's roof: vanilla sets a structure down on
            // whatever is there), with a wide margin
            int pad = 12;
            for (Layout.Site s : Layout.SITES) {
                int[] b = Sites.box(s);
                if (b != null && bb.getMaxX() >= b[0] - pad && bb.getMinX() <= b[3] + pad && bb.getMaxZ() >= b[2] - pad && bb.getMinZ() <= b[5] + pad) { e.setCancelled(true); return; }
            }
            for (int[] p : Layout.PYRAMIDS)
                if (bb.getMaxX() >= p[0] - Structures.PH - pad && bb.getMinX() <= p[0] + Structures.PH + pad && bb.getMaxZ() >= p[1] - Structures.PH - pad && bb.getMinZ() <= p[1] + Structures.PH + pad) { e.setCancelled(true); return; }
            for (Layout.Core c : Layout.CORES)
                if (bb.getMaxX() >= c.x() - 30 && bb.getMinX() <= c.x() + 30 && bb.getMaxZ() >= c.z() - 30 && bb.getMinZ() <= c.z() + 30) { e.setCancelled(true); return; }
            for (int[] c : Structures.cities())
                if (bb.getMaxX() >= c[0] - Structures.CITY_R - pad && bb.getMinX() <= c[0] + Structures.CITY_R + pad && bb.getMaxZ() >= c[1] - Structures.CITY_R - pad && bb.getMinZ() <= c[1] + Structures.CITY_R + pad) { e.setCancelled(true); return; }
            for (Layout.Cavern c : Layout.CAVERNS)
                if (bb.getMaxX() >= c.x() - c.r() && bb.getMinX() <= c.x() + c.r() && bb.getMaxZ() >= c.z() - c.r() && bb.getMinZ() <= c.z() + c.r() && bb.getMaxY() >= c.floor() - 10 && bb.getMinY() <= c.ceil() + 10) { e.setCancelled(true); return; }
            for (Layout.Road r : Layout.ROADS) {
                double cx = (bb.getMinX() + bb.getMaxX()) / 2, cz = (bb.getMinZ() + bb.getMaxZ()) / 2, half = Math.max(bb.getWidthX(), bb.getWidthZ()) / 2;
                if (Layout.segDist(cx, cz, r.x0(), r.z0(), r.x1(), r.z1(), null) < half + 6) { e.setCancelled(true); return; }
            }
            // round 7: vanilla structures landing IN each other (ancient cities through trial chambers through mineshafts;
            // the deep underground here is 260 blocks tall and everything vanilla aims at the same few levels). The first
            // one to claim a spot keeps it; anything whose box then touches it isn't built.
            double[] box = {bb.getMinX() - 6, bb.getMinY() - 4, bb.getMinZ() - 6, bb.getMaxX() + 6, bb.getMaxY() + 4, bb.getMaxZ() + 6};
            synchronized (claimed) {
                for (double[] o : claimed)
                    if (box[3] >= o[0] && box[0] <= o[3] && box[4] >= o[1] && box[1] <= o[4] && box[5] >= o[2] && box[2] <= o[5]) { e.setCancelled(true); return; }
                claimed.add(box);
            }
        } catch (Throwable ignored) { }
    }

    /** Boxes of the vanilla structures let through since the server started (see onStructure). */
    private final java.util.List<double[]> claimed = new java.util.ArrayList<>();

    static boolean hits(org.bukkit.util.BoundingBox bb, int x0, int y0, int z0, int x1, int y1, int z1) {
        return bb.getMaxX() >= x0 && bb.getMinX() <= x1 && bb.getMaxY() >= y0 && bb.getMinY() <= y1 && bb.getMaxZ() >= z0 && bb.getMinZ() <= z1;
    }

    // ------------------------------------------------------------------ fresh chunks: loot and spawner mobs

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        // AUDIT: this used to wait a tick and skip the chunk if it had unloaded again, which is exactly what a pregenerator
        // (Chunky) does: every chest stayed empty and every spawner blank. Now: right away, once per chunk (a PDC mark),
        // on ANY load of a chunk that hasn't been done yet.
        if (!ours(e.getWorld())) return;
        Chunk ch = e.getChunk();
        if (ch.getPersistentDataContainer().has(filledKey, PersistentDataType.BYTE)) return;
        try { Sites.heal(terrain(ch.getWorld()), ch); } catch (Throwable err) { plugin.getLogger().warning("[World] healing builds in chunk " + ch.getX() + "," + ch.getZ() + ": " + err); }
        try { fillChunk(ch); } catch (Throwable err) { plugin.getLogger().warning("[World] filling chunk " + ch.getX() + "," + ch.getZ() + ": " + err); }
        ch.getPersistentDataContainer().set(filledKey, PersistentDataType.BYTE, (byte) 1);
    }

    void fillChunk(Chunk ch) {
        Terrain t = terrain(ch.getWorld());
        for (BlockState st : ch.getTileEntities(b -> {
            Material m = b.getType();
            return m == Material.CHEST || m == Material.BARREL || m == Material.SPAWNER || m == Material.TRIAL_SPAWNER || m == Material.VAULT;
        }, false)) {
            Block b = st.getBlock();
            Context ctx = context(t, b);
            if (ctx == null) continue;
            try {
                if (st instanceof Chest chest && chest.getLootTable() == null && chest.getBlockInventory().isEmpty()) {
                    chest.setLootTable(ctx.loot, random.nextLong()); chest.update(true, false);
                } else if (st instanceof Barrel barrel && barrel.getLootTable() == null && barrel.getInventory().isEmpty()) {
                    barrel.setLootTable(ctx.loot, random.nextLong()); barrel.update(true, false);
                } else if (st instanceof CreatureSpawner sp && sp.getSpawnedType() == null) {
                    sp.setSpawnedType(ctx.mobs[random.nextInt(ctx.mobs.length)]); sp.update(true, false);
                } else if (st instanceof TrialSpawner ts && ts.getNormalConfiguration().getSpawnedType() == null) {
                    trial(ts, ctx);   // AUDIT: only blank ones (ours); a vanilla Trial Chamber's spawners keep their own setup
                }
            } catch (Throwable err) {
                plugin.getLogger().fine("[World] couldn't set up " + b.getType() + " at " + b.getLocation() + ": " + err);
            }
        }
    }

    void trial(TrialSpawner ts, Context ctx) {
        EntityType type = ctx.mobs[random.nextInt(ctx.mobs.length)];
        for (var conf : List.of(ts.getNormalConfiguration(), ts.getOminousConfiguration())) {
            conf.setSpawnedType(type);
            conf.setBaseSpawnsBeforeCooldown(6);
            conf.setBaseSimultaneousEntities(3);
            Map<LootTable, Integer> rewards = new HashMap<>();
            rewards.put(LootTables.TRIAL_CHAMBER_KEY.getLootTable(), 2);
            rewards.put(LootTables.TRIAL_CHAMBER_CONSUMABLES.getLootTable(), 1);
            conf.setPossibleRewards(rewards);
        }
        ts.update(true, false);
    }

    record Context(LootTable loot, EntityType... mobs) {}

    /** What kind of place a container / spawner is in (only our structures; vanilla ones keep theirs). */
    Context context(Terrain t, Block b) {
        int x = b.getX(), y = b.getY(), z = b.getZ();
        if (Structures.inPyramid(t, x, y, z)) {
                return new Context(LootTables.DESERT_PYRAMID.getLootTable(), EntityType.HUSK, EntityType.SKELETON, EntityType.BOGGED, EntityType.CAVE_SPIDER);
        }
        for (Layout.Site s : Layout.SITES) {
            int[] bx = Sites.box(s);
            if (bx != null && x >= bx[0] && x <= bx[3] && z >= bx[2] && z <= bx[5] && y >= bx[1] && y <= bx[4])
                return new Context(LootTables.STRONGHOLD_CORRIDOR.getLootTable(), EntityType.ZOMBIE, EntityType.SKELETON);
        }
        Terrain.Col c = t.column(x, z);
        B here = t.underground(x, y, z, c);
        if (here == null) here = c.biome;
        if (b.getType() == Material.TRIAL_SPAWNER || b.getType() == Material.VAULT || y < c.surface - 20 && here.faultlineTen()) {
            EntityType[] mobs = switch (here) {
                case DEEP_DESERT -> new EntityType[]{EntityType.HUSK, EntityType.SKELETON};
                case INFERNO_FOREST -> new EntityType[]{EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.WITHER_SKELETON};
                case WASTELAND -> new EntityType[]{EntityType.BOGGED, EntityType.SLIME, EntityType.DROWNED};
                case GREAT_SEA, ABYSS -> new EntityType[]{EntityType.DROWNED, EntityType.GUARDIAN};
                case SPIDER, SPIDER_CAVES -> new EntityType[]{EntityType.SPIDER, EntityType.CAVE_SPIDER};
                case MIRE -> new EntityType[]{EntityType.ZOMBIE, EntityType.BOGGED, EntityType.SLIME};
                case FIBERS -> new EntityType[]{EntityType.STRAY, EntityType.ZOMBIE, EntityType.CAVE_SPIDER, EntityType.BREEZE};
                default -> new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.BREEZE};
            };
            LootTable loot = b.getType() == Material.CHEST ? LootTables.TRIAL_CHAMBERS_CORRIDOR.getLootTable() : LootTables.SIMPLE_DUNGEON.getLootTable();
            return new Context(loot, mobs);
        }
        if (here == B.SKY_ISLES) return new Context(b.getType() == Material.CHEST ? LootTables.VILLAGE_TOOLSMITH.getLootTable() : LootTables.VILLAGE_WEAPONSMITH.getLootTable(), EntityType.ZOMBIE, EntityType.SKELETON);   // the abandoned steam city
        if (here == B.JAGGED_PEAKS || here == B.STONY_PEAKS || here == B.WINDSWEPT_HILLS || here == B.FROZEN_PEAKS || here == B.SNOWY_SLOPES)
            return new Context(LootTables.ABANDONED_MINESHAFT.getLootTable(), EntityType.SKELETON, EntityType.STRAY);   // the ruined watchtowers
        if (here == B.JUNGLE || here == B.BAMBOO_JUNGLE) return new Context(LootTables.JUNGLE_TEMPLE.getLootTable(), EntityType.ZOMBIE, EntityType.SKELETON);   // the huts in the kapoks
        if (here == B.SPIDER) return new Context(LootTables.SIMPLE_DUNGEON.getLootTable(), EntityType.SPIDER, EntityType.CAVE_SPIDER);   // the brood mounds
        if (here == B.DEEP_FOREST) return new Context(LootTables.PILLAGER_OUTPOST.getLootTable(), EntityType.VINDICATOR, EntityType.PILLAGER);
        if (here == B.MIRE || here == B.INFERNO_FOREST) return new Context(LootTables.ABANDONED_MINESHAFT.getLootTable(), EntityType.ZOMBIE, EntityType.SKELETON);
        if (Structures.inCore(t, x, y, z)) return null;
        return null;
    }

    // ------------------------------------------------------------------ every second

    void second() {
        World w = main();
        if (!ours(w)) return;
        Terrain t = terrain(w);
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            Terrain.Col c = t.column(l.getBlockX(), l.getBlockZ());
            boolean inAbyss = c.abyssTop != Integer.MIN_VALUE && l.getY() <= c.abyssTop + 1 && l.getY() >= c.abyssBottom - 2;
            abyss(p, inAbyss);
            if (c.hole && l.getY() < c.abyssBottom + 8) toTheDeep(p);
            boolean waste = c.biome == B.WASTELAND && l.getY() > c.surface - 6;
            wasteland(p, waste, c);
        }
    }

    /** The abyss: pitch black (hidden Darkness), its own music while you're in it (and only then). */
    void abyss(Player p, boolean in) {
        if (in) {
            // FaultlineItems' diving gear / Mining Helmet hold their Night Vision off while this is set (else it flickers)
            if (!p.hasMetadata("faultline_abyss_dark")) p.setMetadata("faultline_abyss_dark", new org.bukkit.metadata.FixedMetadataValue(plugin, true));
            if (plugin.getConfig().getBoolean("abyss.darkness", true)) {
                PotionEffect cur = p.getPotionEffect(PotionEffectType.DARKNESS);
                if (cur == null || cur.getDuration() < 60) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 220, 0, true, false, false));
                if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION) && plugin.getConfig().getBoolean("abyss.no-night-vision", true)) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
            music(p, abyssMusic, plugin.getConfig().getString("abyss.music", "faultline:deep.abyss"), plugin.getConfig().getInt("abyss.music-seconds", 286));
        } else if (abyssMusic.remove(p.getUniqueId())) {
            p.removeMetadata("faultline_abyss_dark", plugin);
            p.stopSound(plugin.getConfig().getString("abyss.music", "faultline:deep.abyss"), SoundCategory.MUSIC);
            PotionEffect cur = p.getPotionEffect(PotionEffectType.DARKNESS);
            if (cur != null && cur.isAmbient()) p.removePotionEffect(PotionEffectType.DARKNESS);
        }
    }

    void music(Player p, Set<UUID> set, String sound, int seconds) {
        long now = System.currentTimeMillis();
        if (set.add(p.getUniqueId()) || now - musicAt.getOrDefault(p.getUniqueId(), 0L) > seconds * 1000L) {
            p.stopSound(SoundCategory.MUSIC);
            p.playSound(p, sound, SoundCategory.MUSIC, 1f, 1f);
            musicAt.put(p.getUniqueId(), now);
        }
    }

    /** The bottom of each hole: the way down into the Dark Ocean. */
    void toTheDeep(Player p) {
        World deep = Bukkit.getWorld(plugin.getConfig().getString("holes.world", "faultline_deep"));
        if (deep == null || !falling.add(p.getUniqueId())) return;   // one trip at a time
        Bukkit.getScheduler().runTaskLater(plugin, () -> falling.remove(p.getUniqueId()), 100);
        if (p.isInsideVehicle()) p.leaveVehicle();                    // a boat / horse would make the teleport fail
        p.eject();
        Location to = new Location(deep, 0.5, plugin.getConfig().getInt("holes.arrive-y", 300), 0.5);
        p.teleportAsync(to).thenAccept(ok -> {
            if (!ok) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 400, 0, true, false, true));
            p.sendTitle("§3The Dark Ocean", "§7the hole pulled you down", 10, 50, 20);
            p.playSound(p, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.6f);
        });
    }

    boolean umbrella(Player p) {
        if (plugin.getConfig().getBoolean("wasteland.umbrella-protects", true)) {
            if (p.getPersistentDataContainer().has(umbrellaKey, PersistentDataType.BYTE)) return true;     // set by FaultlineItems while worn
            for (ItemStack i : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand(), p.getInventory().getHelmet()})
                if (i != null && !i.isEmpty() && i.getPersistentDataContainer().has(umbrellaKey)) return true;
        }
        return false;
    }

    /** The Wasteland burns: exposed to its sky you smoulder, in its acid rain you burn more (the Umbrella keeps you dry). */
    void wasteland(Player p, boolean in, Terrain.Col c) {
        String track = plugin.getConfig().getString("wasteland.music", "faultline:deep.acid");
        if (!in) {
            if (wasteMusic.remove(p.getUniqueId())) { p.stopSound(track, SoundCategory.MUSIC); if (wasteRain.remove(p.getUniqueId())) p.resetPlayerWeather(); }
            return;
        }
        music(p, wasteMusic, track, plugin.getConfig().getInt("wasteland.music-seconds", 105));
        boolean rain = acidRainNow(p.getWorld());
        // only on a change: resetting every second would wipe another plugin's weather for this player
        if (rain) { if (wasteRain.add(p.getUniqueId())) p.setPlayerWeather(WeatherType.DOWNFALL); }
        else if (wasteRain.remove(p.getUniqueId())) p.resetPlayerWeather();
        if (!survival(p) || p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) return;
        Block above = p.getWorld().getHighestBlockAt(p.getLocation());
        boolean exposed = above.getY() <= p.getLocation().getBlockY() + 1;
        if (!exposed) return;
        boolean dry = umbrella(p);
        if (rain && !dry) {
            p.damage(plugin.getConfig().getDouble("wasteland.acid-rain-damage", 2.0));
            p.getWorld().spawnParticle(Particle.FALLING_DUST, p.getLocation().add(0, 2, 0), 6, 0.4, 0.5, 0.4, Material.LIME_CONCRETE_POWDER.createBlockData());
        }
        if (random.nextDouble() < plugin.getConfig().getDouble("wasteland.burn-chance", 0.18) && !(dry && plugin.getConfig().getBoolean("wasteland.umbrella-stops-burning", false))) {
            p.setFireTicks(Math.max(p.getFireTicks(), 40));
        }
    }

    long rainUntil, nextRain;

    boolean acidRainNow(World w) {
        long now = System.currentTimeMillis();
        if (now < rainUntil) return true;
        if (nextRain == 0) nextRain = now + 1000L * 60 * plugin.getConfig().getInt("wasteland.acid-rain-every-minutes", 25);
        if (now > nextRain) {
            rainUntil = now + 1000L * 60 * plugin.getConfig().getInt("wasteland.acid-rain-minutes", 5);
            nextRain = rainUntil + 1000L * 60 * (plugin.getConfig().getInt("wasteland.acid-rain-every-minutes", 25) + random.nextInt(15));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ mobs

    /** Inferno Forest mobs never burn in daylight. */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (!(e.getEntity() instanceof Monster) || !ours(e.getEntity().getWorld())) return;
        B b = where(e.getEntity().getLocation());
        if (b == B.INFERNO_FOREST || b == B.FARLANDS) e.setCancelled(true);
    }

    /** The elites' biome rules are in FaultlineRaids; here: extra spawns for the busy places (every 5 s). */
    /** A safety net for water mobs: no more than this many glow squids / squids near one player (the datapack once listed
     *  glow squids in the wrong category, the game's cap never counted them, and hundreds piled up). */
    void cullSquids(World w) {
        int max = plugin.getConfig().getInt("spawns.squid-cap", 8);
        for (Player p : w.getPlayers()) {
            int n = 0;
            for (Entity e : p.getNearbyEntities(72, 96, 72)) {
                if (!(e instanceof Squid) || e.customName() != null) continue;
                if (++n > max) e.remove();
            }
        }
    }

    void spawns() {
        World w = main();
        if (!ours(w)) return;
        cullSquids(w);
        if (w.getDifficulty() == Difficulty.PEACEFUL) return;
        for (Player p : w.getPlayers()) {
            if (!survival(p)) continue;
            B b = where(p.getLocation());
            if (b == null) continue;
            String key = switch (b) { case INFERNO_FOREST -> "inferno"; case MIRE -> "mire"; case FARLANDS -> "farlands"; case FIBERS -> "fibers"; case SPIDER -> "spider"; default -> null; };
            if (key == null) continue;
            int cap = plugin.getConfig().getInt("spawns." + key + ".cap", key.equals("mire") || key.equals("farlands") ? 14 : 8);
            int near = 0;
            for (Entity e : p.getNearbyEntities(40, 24, 40)) if (e instanceof Monster) near++;
            if (near >= cap) continue;
            int n = plugin.getConfig().getInt("spawns." + key + ".per-wave", key.equals("mire") || key.equals("farlands") ? 4 : 2);
            for (int i = 0; i < n; i++) {
                Location at = spot(p.getLocation(), b);
                if (at == null) continue;
                EntityType type = pick(b);
                spawnNatural(w, at, type);
            }
        }
        illagers(w);
    }

    /** By entity type (the rule for 26.x: never spawn by class), as a NATURAL spawn so FaultlineRaids' elite rules apply. */
    void spawnNatural(World w, Location at, EntityType type) {
        w.spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL, ent -> ent.getPersistentDataContainer().set(extraKey, PersistentDataType.BYTE, (byte) 1));
    }

    EntityType pick(B b) {
        EntityType[] pool = switch (b) {
            case INFERNO_FOREST -> new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK, EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.CREEPER};
            case MIRE -> new EntityType[]{EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.BOGGED, EntityType.SLIME, EntityType.SPIDER, EntityType.CREEPER, EntityType.WITCH};
            case FARLANDS -> new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.HUSK, EntityType.STRAY, EntityType.VINDICATOR};
            case FIBERS -> new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CAVE_SPIDER, EntityType.SPIDER, EntityType.CREEPER};
            case SPIDER -> new EntityType[]{EntityType.SPIDER, EntityType.SPIDER, EntityType.CAVE_SPIDER};
            default -> new EntityType[]{EntityType.ZOMBIE};
        };
        return pool[random.nextInt(pool.length)];
    }

    /** A free spot 12-30 blocks away, out of sight-ish, on solid ground with 2 blocks of room. */
    Location spot(Location c, B want) {
        World w = c.getWorld();
        for (int k = 0; k < 12; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = 12 + random.nextDouble() * 18;
            int x = c.getBlockX() + (int) (Math.cos(a) * d), z = c.getBlockZ() + (int) (Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = -8; dy <= 8; dy++) {
                int y = c.getBlockY() + dy;
                Block f = w.getBlockAt(x, y - 1, z), b1 = w.getBlockAt(x, y, z), b2 = w.getBlockAt(x, y + 1, z);
                if (f.getType().isSolid() && b1.isPassable() && !b1.isLiquid() && b2.isPassable() && !b2.isLiquid()) {
                    Location l = new Location(w, x + 0.5, y, z + 0.5);
                    if (want == null || where(l) == want) return l;
                }
            }
        }
        return null;
    }

    /** Illager cities keep a garrison while someone's near. */
    void illagers(World w) {
        Terrain t = terrain(w);
        for (int[] c : Structures.cities()) {
            Location center = new Location(w, c[0] + 0.5, t.column(c[0], c[1]).surface + 1, c[1] + 0.5);
            if (!w.isChunkLoaded(c[0] >> 4, c[1] >> 4)) continue;
            boolean someone = false;
            for (Player p : w.getPlayers()) if (survival(p) && p.getLocation().distanceSquared(center) < 90 * 90) someone = true;
            if (!someone) continue;
            int have = 0;
            for (Entity e : w.getNearbyEntities(center, Structures.CITY_R, 30, Structures.CITY_R)) if (e instanceof Illager) have++;
            int cap = plugin.getConfig().getInt("illager-city.garrison", 12);
            if (have >= cap) continue;
            // AUDIT: it refilled every 5 s, Evokers included = an endless Totem farm. Now one wave a minute, and one
            // Evoker per city every 20 minutes.
            long key = ((long) c[0] << 32) ^ (c[1] & 0xffffffffL), now = System.currentTimeMillis();
            if (now < cityWaveAt.getOrDefault(key, 0L)) continue;
            cityWaveAt.put(key, now + 1000L * plugin.getConfig().getInt("illager-city.wave-seconds", 60));
            boolean evokerOk = now >= cityEvokerAt.getOrDefault(key, 0L);
            EntityType[] pool = {EntityType.PILLAGER, EntityType.PILLAGER, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.VINDICATOR, EntityType.EVOKER};
            for (int i = 0; i < Math.min(3, cap - have); i++) {
                int sr = Structures.CITY_R - 24;                                  // round 9: inside the crag ring, never on the crags
                int x = c[0] + random.nextInt(sr * 2) - sr, z = c[1] + random.nextInt(sr * 2) - sr;
                Location at = w.getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
                EntityType type = pool[random.nextInt(pool.length)];
                if (type == EntityType.EVOKER) {
                    if (!evokerOk) type = EntityType.VINDICATOR;
                    else { evokerOk = false; cityEvokerAt.put(key, now + 60_000L * plugin.getConfig().getInt("illager-city.evoker-minutes", 20)); }
                }
                Entity e = w.spawnEntity(at, type);
                if (e instanceof Raider r) r.setPatrolLeader(false);
                if (e instanceof Mob m) m.setPersistent(false);
            }
        }
    }

    /** Every minute: the Fibers' rare guests (the wild Queen Spider 5% per 10 minutes, servants, the Zombie Giant 1%). */
    int minute;

    void rare() {
        World w = main();
        if (!ours(w) || w.getDifficulty() == Difficulty.PEACEFUL) return;
        minute++;
        ConsoleCommandSender con = Bukkit.getConsoleSender();
        for (Player p : w.getPlayers()) {
            if (!survival(p) || where(p.getLocation()) != B.FIBERS) continue;
            if (minute % 10 == 0 && random.nextDouble() < plugin.getConfig().getDouble("fibers.queen-chance", 0.05) && Bukkit.getPluginCommand("queenspider") != null) {
                Location at = spot(p.getLocation(), B.FIBERS);
                if (at != null) Bukkit.dispatchCommand(con, "queenspider spawn " + w.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
            }
            if (random.nextDouble() < plugin.getConfig().getDouble("fibers.servant-chance", 0.12) && Bukkit.getPluginCommand("queenspider") != null) {
                Location at = spot(p.getLocation(), B.FIBERS);
                if (at != null) Bukkit.dispatchCommand(con, "queenspider brood servant 1 " + w.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
            }
            if (minute % 10 == 0 && random.nextDouble() < plugin.getConfig().getDouble("fibers.giant-chance", 0.01)) giants.spawnNear(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        abyssMusic.remove(e.getPlayer().getUniqueId());
        wasteMusic.remove(e.getPlayer().getUniqueId());
        musicAt.remove(e.getPlayer().getUniqueId());
        wasteRain.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeMetadata("faultline_abyss_dark", plugin);
    }
}
