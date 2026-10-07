package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * METEOR STRIKES: every hour (real time) a meteor falls somewhere in the Overworld, a few hundred blocks from a random
 * player. Everyone is told roughly where, a minute ahead. It lands in a smoking crater, guarded by Molten Husks, Meteor
 * Crawlers and a Star Sentinel; the first player to break its glowing core (Gilded Blackstone) claims its loot. The
 * meteor rock has some Ancient Debris in it, for anyone. Unclaimed, it cools after 30 minutes.
 *
 * Craters only form on natural ground: a spot near anything player-built is skipped.
 */
final class Meteor implements Listener, CommandExecutor {

    static final String TAG = "faultline_meteor", HUSK_TAG = "faultline_meteor_husk", CRAWLER_TAG = "faultline_meteor_crawler",
            SENTINEL_TAG = "faultline_star_sentinel";

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private long nextAt;          // System.currentTimeMillis() of the next strike
    private Strike strike;        // the falling / landed meteor, if any

    final class Strike {
        final World world; final int x, z; int y;
        long landsAt; boolean landed, claimed;
        Location core;
        BossBar bar;
        BlockDisplay rock;
        int ticks, lifeTicks;
        final List<LivingEntity> guards = new ArrayList<>();
        Strike(World world, int x, int y, int z) { this.world = world; this.x = x; this.y = y; this.z = z; }
    }

    Meteor(FaultlineItems plugin) {
        this.plugin = plugin;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        nextAt = System.currentTimeMillis() + (long) (cfg("every-minutes", 60) * 60000);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> guard(this::tick), 20L, 1L);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("meteor." + path, def); }

    private long errAt;
    private void guard(Runnable r) {
        try { r.run(); } catch (RuntimeException e) {
            if (System.currentTimeMillis() - errAt > 30000) {
                errAt = System.currentTimeMillis();
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Meteor] error (skipped; please report this):", e);
            }
        }
    }

    // ===================================================================== timing

    private void tick() {
        if (strike == null) {
            if (!plugin.getConfig().getBoolean("meteor.enabled", true)) return;
            if (System.currentTimeMillis() >= nextAt) {
                nextAt = System.currentTimeMillis() + (long) (cfg("every-minutes", 60) * 60000);
                start(null);
            }
            return;
        }
        Strike s = strike;
        s.ticks++;
        if (!s.landed) fallTick(s);
        else landedTick(s);
    }

    /** Picks a spot near a random player (or near `near`) and announces it. Returns false if no spot was found. */
    boolean start(Player near) {
        if (strike != null) return false;
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (near != null && p != near) continue;
            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (near == null && p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            pool.add(p);
        }
        if (pool.isEmpty()) return false;
        Player p = pool.get(random.nextInt(pool.size()));
        World w = p.getWorld();
        double min = near != null ? cfg("test-distance", 30) : cfg("min-distance", 250), max = near != null ? min + 10 : cfg("max-distance", 600);
        for (int tries = 0; tries < 40; tries++) {
            double ang = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getLocation().getX() + Math.cos(ang) * d), z = (int) Math.floor(p.getLocation().getZ() + Math.sin(ang) * d);
            if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) continue;
            if (!w.isChunkGenerated(x >> 4, z >> 4)) continue; // never generate new terrain for it (slow)
            w.getChunkAt(x >> 4, z >> 4); // loads the landing chunk (and keeps it ticking through the fall)
            Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!natural(top.getType()) || top.isLiquid() || top.getY() < w.getSeaLevel() - 6) continue;
            if (base(w, x, top.getY(), z) != null) continue;
            begin(w, x, top.getY(), z);
            return true;
        }
        plugin.getLogger().info("[Meteor] no clear landing spot this time (everything near players is built up or water). Trying again in an hour.");
        return false;
    }

    /** An admin's meteor, exactly where they say (no base check: they chose the spot). Returns an error, or null if it's falling. */
    String startAt(World w, int x, int z) {
        if (strike != null) return "A meteor is already out (/meteor stop).";
        if (w.getEnvironment() != World.Environment.NORMAL) return "Meteors only fall in the Overworld.";
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) return "That's outside the world border.";
        if (!w.isChunkGenerated(x >> 4, z >> 4)) return "That land hasn't been generated yet.";
        w.getChunkAt(x >> 4, z >> 4);
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top.isLiquid()) return "That's water. A meteor needs ground.";
        begin(w, x, top.getY(), z);
        return null;
    }

    private void begin(World w, int x, int y, int z) {
        Strike s = new Strike(w, x, y, z);
        s.landsAt = System.currentTimeMillis() + (long) (cfg("warning-seconds", 60) * 1000);
        s.bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
        for (Player p : w.getPlayers()) s.bar.addPlayer(p);
        strike = s;
        // BUG FIX: the landing chunk could unload during the fall or the wait (guards gone, the core checked by loading the
        // chunk again every second). It stays loaded until the meteor is done.
        w.addPluginChunkTicket(x >> 4, z >> 4, plugin);
        String where = ChatColor.WHITE + "X " + x + ", Z " + z;
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "☄ A METEOR IS FALLING! " + ChatColor.YELLOW + "It lands near " + where
                + ChatColor.YELLOW + " in " + (int) cfg("warning-seconds", 60) + " seconds. First to break its core claims the loot!");
        for (Player p : w.getPlayers()) {
            p.showTitle(net.kyori.adventure.title.Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "☄ METEOR"),
                    legacy(ChatColor.YELLOW + "Landing near X " + x + ", Z " + z)));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.5f, 1.6f);
        }
    }

    // ===================================================================== the fall

    private static final double FALL_TICKS = 120; // the last 6 seconds: a fireball across the sky

    private void fallTick(Strike s) {
        long left = s.landsAt - System.currentTimeMillis();
        if (s.ticks % 10 == 0) {
            s.bar.setTitle(ChatColor.GOLD + "☄ Meteor lands in " + Math.max(0, left / 1000) + "s " + ChatColor.GRAY + "near " + ChatColor.WHITE + "X " + s.x + ", Z " + s.z);
            s.bar.setProgress(Math.max(0, Math.min(1, left / (cfg("warning-seconds", 60) * 1000))));
            for (Player p : s.world.getPlayers()) if (!s.bar.getPlayers().contains(p)) s.bar.addPlayer(p);
        }
        double fall = left / 50.0; // ticks to impact
        if (fall > FALL_TICKS) return;
        Location impact = new Location(s.world, s.x + 0.5, s.y + 1, s.z + 0.5);
        double f = Math.max(0, fall / FALL_TICKS); // 1 -> 0
        Vector from = new Vector(70, 160, 30);
        Location at = impact.clone().add(from.clone().multiply(f));
        if (s.rock == null) {
            s.rock = s.world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(Material.MAGMA_BLOCK.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(-1.5f, -1.5f, -1.5f), new Quaternionf(), new Vector3f(3, 3, 3), new Quaternionf()));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(true);
                d.setGlowColorOverride(Color.ORANGE);
                d.setTeleportDuration(2);
                d.setViewRange(8f);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            });
            for (Player p : s.world.getPlayers()) p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, SoundCategory.AMBIENT, 1f, 0.5f);
        }
        s.rock.teleport(at);
        s.rock.setTransformation(new Transformation(new Vector3f(-1.5f, -1.5f, -1.5f), new Quaternionf().rotateXYZ(s.ticks * 0.15f, s.ticks * 0.1f, 0),
                new Vector3f(3, 3, 3), new Quaternionf()));
        s.world.spawnParticle(Particle.FLAME, at, 25, 1.2, 1.2, 1.2, 0.05, null, true);
        s.world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 1.4, 1.4, 1.4, 0.02, null, true);
        s.world.spawnParticle(Particle.LAVA, at, 3, 1, 1, 1, 0, null, true);
        if (s.ticks % 20 == 0) s.world.playSound(at, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.AMBIENT, 6f, 0.4f);
        if (fall <= 0) impact(s);
    }

    private void impact(Strike s) {
        if (s.rock != null) s.rock.remove();
        s.rock = null;
        s.landed = true;
        s.lifeTicks = 0;
        Location c = new Location(s.world, s.x + 0.5, s.y + 1, s.z + 0.5);
        s.world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 2, 1, 2, 0, null, true);
        s.world.spawnParticle(Particle.LAVA, c, 80, 4, 2, 4, 0, null, true);
        s.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c, 60, 3, 1, 3, 0.05, null, true);
        s.world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 8f, 0.5f);
        s.world.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 8f, 0.6f);
        for (Entity e : s.world.getNearbyEntities(c, 8, 6, 8)) {
            if (!(e instanceof LivingEntity le)) continue;
            Vector push = e.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
            le.setVelocity(push.normalize().multiply(1.2).setY(0.6));
            if (le instanceof Player pl && (pl.getGameMode() == GameMode.SURVIVAL || pl.getGameMode() == GameMode.ADVENTURE)) {
                double d = e.getLocation().distance(c);
                pl.damage(Math.max(2, cfg("impact-damage", 14) * (1 - d / 9)));
                pl.setFireTicks(60);
            }
        }
        crater(s);
        spawnGuards(s);
        s.bar.setColor(BarColor.YELLOW);
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "☄ The meteor has landed " + ChatColor.YELLOW + "at "
                + ChatColor.WHITE + "X " + s.x + ", Y " + s.core.getBlockY() + ", Z " + s.z + ChatColor.YELLOW + ". Its guardians are waking up...");
    }

    // ===================================================================== the crater

    private static final Set<Material> NATURAL = EnumSet.noneOf(Material.class);
    static {
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || !m.isBlock()) continue; // never touch legacy materials (loads legacy support)
            String n = m.name();
            if (n.endsWith("_LEAVES") || n.endsWith("_SAPLING") || n.contains("GRASS") || n.endsWith("_FLOWER") || n.endsWith("_TULIP")
                    || n.endsWith("TERRACOTTA") && !n.contains("GLAZED") || n.endsWith("_ORE") || n.equals("SNOW") || n.equals("SNOW_BLOCK")
                    || n.equals("POWDER_SNOW") || n.equals("ICE") || n.equals("PACKED_ICE"))
                NATURAL.add(m);
        }
        NATURAL.addAll(List.of(Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.MUD,
                Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
                Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE, Material.GRAVEL, Material.CLAY, Material.MOSS_BLOCK,
                Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE, Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.DANDELION,
                Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.CORNFLOWER,
                Material.LILY_OF_THE_VALLEY, Material.SWEET_BERRY_BUSH, Material.CACTUS, Material.SUGAR_CANE, Material.PUMPKIN, Material.MELON,
                Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.VINE, Material.MOSS_CARPET, Material.SNOW, Material.LILAC, Material.PEONY,
                Material.ROSE_BUSH, Material.SUNFLOWER, Material.SMOOTH_BASALT));
        for (Material m : Tag.LOGS.getValues()) NATURAL.add(m);
    }

    static boolean natural(Material m) { return m.isAir() || NATURAL.contains(m); }

    /**
     * Why a meteor can't land here (a build, a base, someone's bed), or null if it can. Checked within
     * meteor.build-check-radius (48) blocks: player-made blocks on the surface, block entities (chests, furnaces,
     * beds, ...) near the surface, chunks players have spent hours in, and online players' respawn points.
     * Inside a village (plus 8 blocks) the village's own houses and chests don't count; the time and bed checks still do.
     */
    String base(World w, int x, int y, int z) {
        int r = Math.max(12, (int) cfg("build-check-radius", 48));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location rs = null;
            try { rs = p.getRespawnLocation(); } catch (RuntimeException ignored) { }
            if (rs != null && rs.getWorld() == w && Math.hypot(rs.getX() - x, rs.getZ() - z) < r + 16) return "a respawn point";
        }
        List<org.bukkit.util.BoundingBox> villages = villages(w, x, z, r);
        long hours = (long) (cfg("base-hours", 3) * 72000); // inhabited time is in ticks
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
            if (!w.isChunkGenerated(cx, cz)) continue;
            org.bukkit.Chunk c = w.getChunkAt(cx, cz);
            try { if (hours > 0 && c.getInhabitedTime() >= hours) return "a base (players spend a lot of time here)"; } catch (RuntimeException ignored) { }
            org.bukkit.block.BlockState[] tiles;
            try { tiles = c.getTileEntities(false); } catch (RuntimeException e) { continue; }
            for (org.bukkit.block.BlockState t : tiles) {
                if (WILD_TILES.contains(t.getType()) || Math.hypot(t.getX() - x, t.getZ() - z) > r) continue;
                if (inside(villages, t.getX(), t.getZ())) continue;
                // chests in dungeons, temples, trial chambers... are deep underground; a base's are on the surface
                if (t.getY() < w.getHighestBlockYAt(t.getX(), t.getZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) - 8) continue;
                return "a base (" + t.getType().name().toLowerCase() + ")";
            }
        }
        if (inside(villages, x, z)) return null;
        // every surface column (a spot that's rejected stops at the first build, so only the chosen one is scanned in full)
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) continue;
            int bx = x + dx, bz = z + dz;
            if (!w.isChunkGenerated(bx >> 4, bz >> 4) || inside(villages, bx, bz)) continue;
            int top = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            for (int by = Math.max(w.getMinHeight(), top - 4); by <= top + 2; by++)
                if (built(w.getBlockAt(bx, by, bz))) return "a build at " + bx + ", " + by + ", " + bz;
        }
        return null;
    }

    /** Is this block player-made? Logs count as wild (trees), but stripped logs and bark blocks don't grow anywhere. */
    static boolean built(Block b) {
        Material m = b.getType();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.BEDROCK) return false;
        if (Tag.LOGS.isTagged(m)) return m.name().startsWith("STRIPPED_") || m.name().endsWith("_WOOD") || m.name().endsWith("_HYPHAE");
        return !natural(m) && !OLD_CRATER.contains(m) && !WILD.contains(m) && !WILD_TILES.contains(m) && !Tag.LEAVES.isTagged(m) && !Tag.FLOWERS.isTagged(m);
    }

    /** What an old meteor leaves behind (its crater isn't someone's build). */
    private static final Set<Material> OLD_CRATER = EnumSet.of(Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.BASALT, Material.COBBLED_DEEPSLATE,
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.ANCIENT_DEBRIS, Material.GILDED_BLACKSTONE, Material.FIRE);

    /** Other things that grow or generate on the surface by themselves. */
    private static final Set<Material> WILD = EnumSet.noneOf(Material.class);
    static {
        for (String n : List.of("MOSSY_COBBLESTONE", "BROWN_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK", "MUSHROOM_STEM", "BAMBOO", "COCOA",
                "AZALEA", "FLOWERING_AZALEA", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "HANGING_ROOTS", "GLOW_LICHEN", "LILY_PAD", "KELP",
                "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS", "SEA_PICKLE", "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "SMALL_DRIPLEAF", "PINK_PETALS",
                "WILDFLOWERS", "LEAF_LITTER", "BUSH", "FIREFLY_BUSH", "CACTUS_FLOWER", "PALE_MOSS_BLOCK", "PALE_MOSS_CARPET",
                "PALE_HANGING_MOSS", "SPORE_BLOSSOM", "CAVE_VINES", "CAVE_VINES_PLANT", "MAGMA_BLOCK", "SUSPICIOUS_SAND")) {
            try { WILD.add(Material.valueOf(n)); } catch (IllegalArgumentException ignored) { }
        }
    }

    /** Block entities that turn up in the wild on their own (bee nests, spawners, ...): not a sign of a base. */
    private static final Set<Material> WILD_TILES = EnumSet.noneOf(Material.class);
    static {
        for (String n : List.of("BEE_NEST", "BEEHIVE", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL",
                "DECORATED_POT", "CREAKING_HEART", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER", "SCULK_CATALYST", "END_GATEWAY",
                "MOVING_PISTON")) {
            try { WILD_TILES.add(Material.valueOf(n)); } catch (IllegalArgumentException ignored) { }
        }
    }

    /** The boxes of villages near the spot (grown 8 blocks so their fields and paths count too). */
    private static List<org.bukkit.util.BoundingBox> villages(World w, int x, int z, int r) {
        List<org.bukkit.util.BoundingBox> out = new ArrayList<>();
        try {
            for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) {
                if (!w.isChunkGenerated(cx, cz)) continue;
                for (org.bukkit.generator.structure.GeneratedStructure gs : w.getStructures(cx, cz)) {
                    org.bukkit.NamespacedKey k = org.bukkit.Registry.STRUCTURE.getKey(gs.getStructure());
                    if (k != null && k.getKey().startsWith("village")) out.add(gs.getBoundingBox().clone().expand(8));
                }
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    private static boolean inside(List<org.bukkit.util.BoundingBox> boxes, int x, int z) {
        for (org.bukkit.util.BoundingBox b : boxes) if (x >= b.getMinX() && x <= b.getMaxX() && z >= b.getMinZ() && z <= b.getMaxZ()) return true;
        return false;
    }

    private void set(Block b, Material m) {
        if (b.getType() == Material.BEDROCK || !natural(b.getType()) && !b.isLiquid()) return;
        b.setType(m, false);
    }

    private void crater(Strike s) {
        World w = s.world;
        double R = cfg("crater-radius", 5);
        int cx = s.x, cz = s.z, cy = s.y;
        // the bowl
        for (int dx = (int) -R - 1; dx <= R + 1; dx++) for (int dz = (int) -R - 1; dz <= R + 1; dz++) for (int dy = -4; dy <= 6; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + (dy - 1.5) * (dy - 1.5) * 2.2);
            Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
            if (d < R) set(b, Material.AIR);
            else if (d < R + 1.1 && dy <= 1 && !b.getType().isAir()) {
                double r = random.nextDouble();
                set(b, r < 0.25 ? Material.MAGMA_BLOCK : r < 0.5 ? Material.BLACKSTONE : r < 0.7 ? Material.BASALT : r < 0.85 ? Material.COBBLED_DEEPSLATE : Material.COARSE_DIRT);
            }
        }
        // fire and smoke on the rim
        for (int i = 0; i < 14; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = R * (0.6 + random.nextDouble() * 0.6);
            int x = (int) Math.round(cx + Math.cos(a) * r), z = (int) Math.round(cz + Math.sin(a) * r);
            Block top = w.getHighestBlockAt(x, z);
            Block above = top.getRelative(0, 1, 0);
            if (above.getType().isAir() && top.getType().isSolid() && top.getType() != Material.MAGMA_BLOCK) above.setType(Material.FIRE, false);
        }
        // the meteor: a rough ball of rock in the bottom of the crater, debris inside, the glowing core on top
        int by = cy - 2;
        List<Block> rock = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 3; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + (dy - 1.2) * (dy - 1.2) * 1.4);
            if (d > 2.3 + random.nextDouble() * 0.4) continue;
            Block b = w.getBlockAt(cx + dx, by + dy, cz + dz);
            if (b.getType() == Material.BEDROCK) continue;
            double r = random.nextDouble();
            b.setType(r < 0.45 ? Material.BLACKSTONE : r < 0.7 ? Material.BASALT : r < 0.85 ? Material.MAGMA_BLOCK : Material.CRYING_OBSIDIAN, false);
            rock.add(b);
        }
        int debris = (int) cfg("ancient-debris", 3);
        java.util.Collections.shuffle(rock, random);
        for (int i = 0; i < Math.min(debris, rock.size()); i++) rock.get(i).setType(Material.ANCIENT_DEBRIS, false);
        int top = by;
        while (top < by + 6 && !w.getBlockAt(cx, top + 1, cz).getType().isAir()) top++;
        Block core = w.getBlockAt(cx, top + 1, cz);
        core.setType(Material.GILDED_BLACKSTONE, false);
        s.core = core.getLocation();
    }

    // ===================================================================== the guards

    private void spawnGuards(Strike s) {
        Location c = s.core.clone().add(0.5, 1, 0.5);
        int husks = (int) cfg("guards.molten-husks", 4), crawlers = (int) cfg("guards.meteor-crawlers", 4), sentinels = (int) cfg("guards.star-sentinels", 1);
        for (int i = 0; i < husks; i++) s.guards.add(spawn(ring(c, 5, 8), "husk"));
        for (int i = 0; i < crawlers; i++) s.guards.add(spawn(ring(c, 2, 5), "crawler"));
        for (int i = 0; i < sentinels; i++) s.guards.add(spawn(c.clone().add(0, 4, 0), "sentinel"));
        s.guards.removeIf(g -> g == null);
    }

    private Location ring(Location c, double min, double max) {
        double a = random.nextDouble() * Math.PI * 2, r = min + random.nextDouble() * (max - min);
        int x = (int) Math.floor(c.getX() + Math.cos(a) * r), z = (int) Math.floor(c.getZ() + Math.sin(a) * r);
        Block top = c.getWorld().getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return top.getLocation().add(0.5, 1, 0.5);
    }

    /** One meteor guard ("husk", "crawler", "sentinel"), also used by /meteor egg. */
    LivingEntity spawn(Location at, String kind) {
        World w = at.getWorld();
        LivingEntity e;
        switch (kind) {
            case "crawler" -> e = w.spawn(at, Endermite.class, m -> {
                setup(m, CRAWLER_TAG, ChatColor.GOLD + "Meteor Crawler", cfg("guards.crawler-health", 12));
                attr(m, Attribute.ATTACK_DAMAGE, 4); attr(m, Attribute.MOVEMENT_SPEED, 0.32);
            });
            case "sentinel" -> e = w.spawn(at, Blaze.class, m -> {
                setup(m, SENTINEL_TAG, ChatColor.YELLOW + "" + ChatColor.BOLD + "Star Sentinel", cfg("guards.sentinel-health", 80));
                attr(m, Attribute.SCALE, 1.4);
                m.setGlowing(true);
            });
            default -> e = w.spawn(at, Husk.class, m -> {
                setup(m, HUSK_TAG, ChatColor.GOLD + "Molten Husk", cfg("guards.husk-health", 40));
                m.setAdult();
                EntityEquipment eq = m.getEquipment();
                eq.setHelmet(new ItemStack(Material.MAGMA_BLOCK)); eq.setHelmetDropChance(0);
                ItemStack sword = new ItemStack(Material.GOLDEN_SWORD);
                sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
                eq.setItemInMainHand(sword); eq.setItemInMainHandDropChance(0);
                attr(m, Attribute.ATTACK_DAMAGE, 6); attr(m, Attribute.MOVEMENT_SPEED, 0.27);
                m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
            });
        }
        return e;
    }

    private void setup(Mob m, String tag, String name, double hp) {
        m.addScoreboardTag(TAG);
        m.addScoreboardTag(tag);
        m.setCustomName(name);
        m.setCustomNameVisible(false);
        m.setRemoveWhenFarAway(false);
        m.setPersistent(false);
        attr(m, Attribute.MAX_HEALTH, hp);
        m.setHealth(hp);
        m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    static boolean guard(Entity e) { return e.getScoreboardTags().contains(TAG); }

    // ===================================================================== after landing

    private void landedTick(Strike s) {
        s.lifeTicks++;
        Location c = s.core.clone().add(0.5, 0.5, 0.5);
        if (!s.claimed) {
            if (s.ticks % 5 == 0) {
                s.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c.clone().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0.03, null, true);
                s.world.spawnParticle(Particle.DUST, c, 4, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.4f));
            }
            if (s.ticks % 20 == 0) {
                int left = (int) Math.max(0, cfg("lifetime-minutes", 30) * 60 - s.lifeTicks / 20);
                s.bar.setTitle(ChatColor.GOLD + "☄ Meteor at " + ChatColor.WHITE + "X " + s.x + ", Y " + s.core.getBlockY() + ", Z " + s.z
                        + ChatColor.GRAY + "  (cools in " + left / 60 + ":" + String.format("%02d", left % 60) + ")");
                s.bar.setProgress(Math.max(0, Math.min(1, left / (cfg("lifetime-minutes", 30) * 60))));
                for (Player p : s.world.getPlayers()) if (!s.bar.getPlayers().contains(p)) s.bar.addPlayer(p);
                if (s.core.getBlock().getType() != Material.GILDED_BLACKSTONE) { // pushed or blown away somehow
                    s.core.getBlock().setType(Material.GILDED_BLACKSTONE, false);
                }
                // guards stay near their meteor
                for (LivingEntity g : s.guards) if (g.isValid() && g.getLocation().distanceSquared(c) > 30 * 30) g.teleport(c.clone().add(random.nextInt(5) - 2, 2, random.nextInt(5) - 2));
            }
            if (s.lifeTicks > cfg("lifetime-minutes", 30) * 1200) {
                s.core.getBlock().setType(Material.OBSIDIAN, false);
                Bukkit.broadcastMessage(ChatColor.GRAY + "☄ Nobody claimed the meteor. It has cooled into plain obsidian.");
                end(s);
            }
        } else if (s.lifeTicks > 20 * 120) end(s); // two minutes after it's claimed, the guards crumble away
    }

    private void end(Strike s) {
        if (s.bar != null) s.bar.removeAll();
        for (LivingEntity g : s.guards) if (g.isValid()) {
            g.getWorld().spawnParticle(Particle.LAVA, g.getLocation(), 6, 0.3, 0.5, 0.3, 0);
            g.remove();
        }
        if (s.rock != null) s.rock.remove();
        s.world.removePluginChunkTicket(s.x >> 4, s.z >> 4, plugin);
        if (strike == s) strike = null;
    }

    void shutdown() {
        if (strike == null) return;
        if (strike.landed && !strike.claimed && strike.core != null) strike.core.getBlock().setType(Material.OBSIDIAN, false);
        end(strike);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Strike s = strike;
        if (s == null || !s.landed || s.claimed || s.core == null || !e.getBlock().getLocation().equals(s.core)) return;
        Player p = e.getPlayer();
        e.setDropItems(false);
        e.setExpToDrop(0);
        s.claimed = true;
        s.lifeTicks = 0;
        Location at = s.core.clone().add(0.5, 0.5, 0.5);
        for (ItemStack it : loot()) {
            Item drop = at.getWorld().dropItem(at, it);
            drop.setOwner(p.getUniqueId());
            drop.setVelocity(new Vector(random.nextGaussian() * 0.12, 0.35, random.nextGaussian() * 0.12));
        }
        int xp = (int) cfg("loot.xp", 300);
        at.getWorld().spawn(at, ExperienceOrb.class, o -> o.setExperience(xp));
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 80, 0.5, 0.8, 0.5, 0.3);
        at.getWorld().playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 2f, 1f);
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "☄ " + p.getName() + ChatColor.YELLOW + " claimed the meteor!");
        if (s.bar != null) s.bar.removeAll();
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " meteors 1");
    }

    /** The core's loot: config meteor.loot ("MATERIAL:min-max", or mythic_bag / goodie_bag). */
    List<ItemStack> loot() {
        List<String> lines = plugin.getConfig().isList("meteor.loot.items") ? plugin.getConfig().getStringList("meteor.loot.items")
                : List.of("DIAMOND:4-8", "EMERALD:6-12", "NETHERITE_SCRAP:1-2", "GOLD_INGOT:8-16", "AMETHYST_SHARD:8-16", "mythic_bag:1", "goodie_bag:2");
        List<ItemStack> out = new ArrayList<>();
        for (String line : lines) {
            String[] kv = line.split(":");
            int lo = 1, hi = 1;
            try {
                String[] r = (kv.length > 1 ? kv[1] : "1").split("-");
                lo = Integer.parseInt(r[0].trim()); hi = r.length > 1 ? Integer.parseInt(r[1].trim()) : lo;
            } catch (NumberFormatException ignored) { }
            int n = lo + random.nextInt(Math.max(1, hi - lo + 1));
            Supplier<ItemStack> special = switch (kv[0].trim().toLowerCase()) {
                case "mythic_bag" -> () -> FaultlineItems.MythicGoodieBagItem.create(plugin);
                case "goodie_bag" -> () -> FaultlineItems.GoodieBagItem.create(plugin);
                default -> null;
            };
            if (special != null) { for (int i = 0; i < n; i++) out.add(special.get()); continue; }
            Material m = Material.matchMaterial(kv[0].trim());
            if (m == null || !m.isItem()) { plugin.getLogger().warning("[Meteor] unknown loot item: " + line); continue; }
            while (n > 0) { int k = Math.min(n, m.getMaxStackSize()); out.add(new ItemStack(m, k)); n -= k; }
        }
        return out;
    }

    @EventHandler
    public void onGuardDeath(EntityDeathEvent e) {
        if (!guard(e.getEntity())) return;
        e.getDrops().clear();
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(HUSK_TAG)) {
            e.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 3 + random.nextInt(6)));
            if (random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.MAGMA_CREAM, 1 + random.nextInt(2)));
            e.setDroppedExp(12);
        } else if (t.contains(SENTINEL_TAG)) {
            e.getDrops().add(new ItemStack(Material.BLAZE_ROD, 2 + random.nextInt(3)));
            if (random.nextDouble() < 0.4) e.getDrops().add(new ItemStack(Material.DIAMOND, 1 + random.nextInt(2)));
            e.setDroppedExp(40);
        } else if (t.contains(CRAWLER_TAG)) { // pops in a little burst of embers when it dies
            Location l = e.getEntity().getLocation();
            l.getWorld().spawnParticle(Particle.LAVA, l, 8, 0.3, 0.3, 0.3, 0);
            l.getWorld().playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.4f);
            if (random.nextDouble() < 0.5) e.getDrops().add(new ItemStack(Material.IRON_NUGGET, 2 + random.nextInt(4)));
            e.setDroppedExp(5);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardBurn(EntityCombustEvent e) { if (guard(e.getEntity())) e.setCancelled(true); }

    /** Explosions never take the core (it can only be claimed by breaking it). */
    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (strike != null && strike.core != null) e.blockList().removeIf(b -> b.getLocation().equals(strike.core)); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (strike != null && strike.core != null) e.blockList().removeIf(b -> b.getLocation().equals(strike.core)); }

    // ===================================================================== /meteor

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        boolean admin = sender.hasPermission("items.meteor");
        if (sub.isEmpty() || !admin) {
            if (strike != null) sender.sendMessage(ChatColor.GOLD + "☄ " + (strike.landed ? "A meteor is down at " : "A meteor is falling toward ")
                    + ChatColor.WHITE + "X " + strike.x + ", Z " + strike.z + (strike.claimed ? ChatColor.GRAY + " (already claimed)" : ""));
            else {
                long m = Math.max(0, (nextAt - System.currentTimeMillis()) / 60000);
                sender.sendMessage(ChatColor.GOLD + "☄ The next meteor falls in about " + ChatColor.WHITE + m + " minute" + (m == 1 ? "" : "s") + ChatColor.GOLD + ".");
            }
            return true;
        }
        switch (sub) {
            case "now" -> {
                if (strike != null) { sender.sendMessage(ChatColor.RED + "A meteor is already out (/meteor stop)."); return true; }
                nextAt = System.currentTimeMillis() + (long) (cfg("every-minutes", 60) * 60000);
                sender.sendMessage(start(null) ? ChatColor.GREEN + "A meteor is on its way." : ChatColor.RED + "No survival player in the Overworld with clear ground around them.");
            }
            case "here" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (strike != null) { sender.sendMessage(ChatColor.RED + "A meteor is already out (/meteor stop)."); return true; }
                sender.sendMessage(start(p) ? ChatColor.GREEN + "A meteor is falling about " + (int) cfg("test-distance", 30) + " blocks from you." : ChatColor.RED + "No clear ground near you.");
            }
            case "target" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only (console: /meteor at <x> <z> [world])."); return true; }
                Block b = p.getTargetBlockExact(200);
                if (b == null) { sender.sendMessage(ChatColor.RED + "Look at the ground you want it to hit (up to 200 blocks away)."); return true; }
                String err = startAt(b.getWorld(), b.getX(), b.getZ());
                sender.sendMessage(err == null ? ChatColor.GREEN + "A meteor is falling at X " + b.getX() + ", Z " + b.getZ() + "." : ChatColor.RED + err);
            }
            case "at" -> {
                if (args.length < 3) { sender.sendMessage(ChatColor.YELLOW + "/meteor at <x> <z> [world]"); return true; }
                World w = args.length > 3 ? Bukkit.getWorld(args[3]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                if (w == null) { sender.sendMessage(ChatColor.RED + "No world called " + args[3] + "."); return true; }
                int x, z;
                try { x = (int) Math.floor(Double.parseDouble(args[1])); z = (int) Math.floor(Double.parseDouble(args[2])); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "/meteor at <x> <z> [world]"); return true; }
                String err = startAt(w, x, z);
                sender.sendMessage(err == null ? ChatColor.GREEN + "A meteor is falling at X " + x + ", Z " + z + "." : ChatColor.RED + err);
            }
            case "check" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Location l = p.getLocation();
                String why = base(p.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                sender.sendMessage(why == null ? ChatColor.GREEN + "A random meteor could land here." : ChatColor.YELLOW + "Random meteors avoid this spot: " + why + ".");
            }
            case "stop" -> {
                if (strike == null) { sender.sendMessage(ChatColor.GRAY + "No meteor is out."); return true; }
                shutdown();
                sender.sendMessage(ChatColor.GREEN + "The meteor is gone (an unclaimed core turned to obsidian).");
            }
            case "egg" -> {
                if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/meteor egg <husk|crawler|sentinel>"); return true; }
                String kind = args[1].toLowerCase();
                LivingEntity m = spawn(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)), kind);
                if (m != null) sender.sendMessage(ChatColor.GREEN + "Spawned a " + ChatColor.stripColor(m.getCustomName()) + ".");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/meteor [now|here|target|at <x> <z> [world]|check|stop|egg <husk|crawler|sentinel>]");
        }
        return true;
    }

    static net.kyori.adventure.text.Component legacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text);
    }

    Strike active() { return strike; }
    void setNext(long at) { nextAt = at; }
}
