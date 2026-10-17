package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE UNDERSEA VOLCANO (Deep 1.9.0, `volcano:`, state in volcano.yml). Every `check-minutes` (30) with someone in the
 * Sunlit Sea, `chance` (10%): a volcano RISES out of the open sea floor `min/max-distance` (180-350) from them (never near
 * a region, the Den, the Sulphur Sea, the Trench, the Bubble or the arrival point; 70+ blocks of water above). It grows
 * layer by layer over ~40 s (the sea floor shakes), then ERUPTS for `minutes` (60): a pillar of smoke and ash over the
 * crater, lava bombs (falling magma that bursts where it lands: `bomb-damage` 6, no blocks broken, nothing left behind),
 * BOILING water within `boil-radius` (14) of the cone (2 damage every 2 s without Fire Resistance), Magma Eels and Cinder
 * Crabs out of the vents (`cap` 8). Then it COOLS: the magma turns to obsidian and basalt, the crater plugs with obsidian
 * full of ANCIENT DEBRIS veins (`debris` 6-10), and 3 chests sit round the rim (Dark Loot rolls them). After `linger-hours`
 * (6) the sea takes it back: every block it changed is put back (only where it's still volcano rock: what players built
 * stays; the chests stay). On shutdown an erupting volcano cools at once; after a crash its scar is healed when due.
 * Index stat `volcanoes`. `/deep volcano [start|stop|cool|heal|where]`.
 */
final class Volcano implements Listener {
    static final String BOMB = "faultline_lava_bomb";
    static final List<Material> ROCK = List.of(Material.BASALT, Material.BLACKSTONE, Material.MAGMA_BLOCK, Material.OBSIDIAN, Material.SMOOTH_BASALT,
            Material.ANCIENT_DEBRIS, Material.CRYING_OBSIDIAN, Material.TUFF, Material.POLISHED_BLACKSTONE, Material.SHROOMLIGHT, Material.BLACK_CONCRETE_POWDER);
    final FaultlineDeep pl;
    final Random random = new Random();
    final File file;
    final Map<Long, String> scar = new LinkedHashMap<>(); // packed x/y/z -> the original block (data string)
    final List<long[]> build = new ArrayList<>();          // {x, y, z, material ordinal} to place, bottom up
    int built;                                              // how many of them are in
    final List<int[]> chests = new ArrayList<>();
    String state = "none"; // none / rising / erupting / cooled
    int cx, cz, floorY, peakY;
    long eruptEnds, healAt;
    int secs;
    BossBar bar;

    Volcano(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "volcano.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::grow, 1L, 1L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("volcano." + k, def); }

    // =========================================================================================== where
    boolean goodSpot(long seed, int x, int z) {
        if (Math.hypot(x, z) < 200 || Math.hypot(x - 681, z + 951) < 260 || Math.abs(x) > 2850 || Math.abs(z) > 2850) return false;
        if (Regions.nearAny(x, z, 120) || MermaidDen.weight(x, z) > 0 || Math.hypot(x - MermaidDen.MX, z - MermaidDen.MZ) < 280) return false;
        DeepGenerator g = pl.generator;
        for (int dx = -40; dx <= 40; dx += 40) for (int dz = -40; dz <= 40; dz += 40) {
            if (g.sulphur(seed, x + dx, z + dz) > 0.01 || g.trenchColumn(seed, x + dx, z + dz)) return false;
            if (DeepGenerator.SEA - g.seaFloor(seed, x + dx, z + dz) < 70) return false;
        }
        return true;
    }

    Location find(Player near) {
        long seed = pl.world.getSeed();
        Location l = near.getLocation();
        for (int i = 0; i < 40; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = cfg("min-distance", 180) + random.nextDouble() * (cfg("max-distance", 350) - cfg("min-distance", 180));
            int x = (int) (l.getX() + Math.cos(a) * d), z = (int) (l.getZ() + Math.sin(a) * d);
            if (!goodSpot(seed, x, z)) continue;
            if (!pl.world.isChunkLoaded(x >> 4, z >> 4) && !pl.world.isChunkGenerated(x >> 4, z >> 4)) continue;
            return new Location(pl.world, x, pl.generator.seaFloor(seed, x, z), z);
        }
        return null;
    }

    // =========================================================================================== rising
    boolean start(Player near) {
        if (!state.equals("none") || pl.world == null || near == null) return false;
        Location at = find(near);
        if (at == null) return false;
        return startAt(at.getBlockX(), at.getBlockZ());
    }

    boolean startAt(int x, int z) {
        if (!state.equals("none") || pl.world == null) return false;
        cx = x; cz = z;
        floorY = pl.generator.seaFloor(pl.world.getSeed(), x, z);
        int height = (int) Math.min(cfg("height", 46), DeepGenerator.SEA - 24 - floorY);
        peakY = floorY + height;
        double base = cfg("base-radius", 22);
        build.clear();
        built = 0;
        chests.clear();
        // the cone, layer by layer: rough slopes of basalt / blackstone, glowing magma seams, a lava-red crater
        for (int y = floorY - 2; y <= peakY; y++) {
            double t = (y - floorY) / (double) height;
            double r = Math.max(4, base * Math.pow(1 - Math.max(0, t), 0.85));
            double crater = y > peakY - 7 ? 3.2 + (y - (peakY - 7)) * 0.5 : y > floorY + 3 ? 1.4 : 0; // the vent up the middle
            int ri = (int) Math.ceil(r + 1.5);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double d = Math.hypot(dx, dz);
                double wob = Math.sin(dx * 0.7 + y * 0.3) * 0.9 + Math.cos(dz * 0.6 - y * 0.25) * 0.9;
                if (d > r + wob) continue;
                Material m;
                if (d < crater) m = y > peakY - 2 ? Material.AIR : Material.MAGMA_BLOCK;        // the vent: magma all the way up, open at the top
                else if (d < crater + 1.2 && y > peakY - 7) m = Material.MAGMA_BLOCK;            // the crater's glowing lip
                else if (d > r + wob - 1.3) { // the skin
                    double n = random.nextDouble();
                    m = n < 0.08 ? Material.MAGMA_BLOCK : n < 0.45 ? Material.BASALT : n < 0.8 ? Material.BLACKSTONE : n < 0.92 ? Material.SMOOTH_BASALT : Material.TUFF;
                } else m = random.nextDouble() < 0.05 ? Material.MAGMA_BLOCK : Material.BLACKSTONE;
                if (m == Material.AIR) m = Material.WATER;
                build.add(new long[]{cx + dx, y, cz + dz, m.ordinal()});
            }
        }
        // lava rivers: magma channels down three sides
        for (int k = 0; k < 3; k++) {
            double a = random.nextDouble() * Math.PI * 2;
            for (int s = 0; s < height; s++) {
                double t = s / (double) height, r = base * Math.pow(t, 1 / 0.85) + 0.5;
                int y = peakY - s;
                int x2 = cx + (int) Math.round(Math.cos(a) * r), z2 = cz + (int) Math.round(Math.sin(a) * r);
                build.add(new long[]{x2, y, z2, Material.MAGMA_BLOCK.ordinal()});
                a += 0.03;
            }
        }
        state = "rising";
        announce(ChatColor.GOLD + "" + ChatColor.BOLD + "The sea floor splits open! " + ChatColor.GRAY + "An undersea volcano is rising at " + cx + ", " + cz + ".");
        for (Player p : pl.world.getPlayers()) p.playSound(p, Sound.ENTITY_WARDEN_DIG, SoundCategory.AMBIENT, 1f, 0.5f);
        save();
        return true;
    }

    /** Every tick while rising: the next batch of blocks (bottom up, so it climbs out of the floor). */
    void grow() {
        if (!state.equals("rising") || pl.world == null) return;
        int n = build.size() / 800 + 120;
        for (int i = 0; i < n && built < build.size(); i++) {
            long[] b = build.get(built++);
            set((int) b[0], (int) b[1], (int) b[2], Material.values()[(int) b[3]]);
        }
        if (random.nextInt(4) == 0) {
            Location c = new Location(pl.world, cx + 0.5, floorY + 4, cz + 0.5);
            pl.world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 6, 6, 3, 6, 0.02);
        }
        if (built >= build.size()) { build.clear(); built = 0; erupt(); }
    }

    void set(int x, int y, int z, Material m) {
        if (y < pl.world.getMinHeight() || y > DeepGenerator.SEA - 2) return;
        Block b = pl.world.getBlockAt(x, y, z);
        if (b.getType() == Material.CHEST || b.getType() == Material.BARREL || b.getType() == Material.BEDROCK) return; // never eat a chest
        long k = pack(x, y, z);
        scar.putIfAbsent(k, b.getBlockData().getAsString());
        if (b.getType() != m) b.setType(m, false);
    }

    static long pack(int x, int y, int z) { return ((long) (x + 4_000_000) << 38) | ((long) (z + 4_000_000) << 12) | (y + 2048); }

    static int[] unpack(long k) { return new int[]{(int) (k >>> 38) - 4_000_000, (int) (k & 4095) - 2048, (int) ((k >>> 12) & ((1 << 26) - 1)) - 4_000_000}; }

    // =========================================================================================== erupting
    void erupt() {
        state = "erupting";
        eruptEnds = System.currentTimeMillis() + (long) (cfg("minutes", 60) * 60_000);
        bar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Undersea Volcano " + ChatColor.GRAY + "(" + cx + ", " + cz + ")", BarColor.RED, BarStyle.SEGMENTED_12);
        announce(ChatColor.RED + "" + ChatColor.BOLD + "The volcano erupts! " + ChatColor.GRAY + "Lava bombs, boiling water and fire creatures for "
                + (int) cfg("minutes", 60) + " minutes. When it cools, its heart is full of Ancient Debris.");
        for (Player p : pl.world.getPlayers()) if (near(p, 120)) pl.console("index stat " + p.getName() + " volcanoes 1");
        save();
    }

    boolean near(Player p, double r) { return p.getWorld().equals(pl.world) && Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz) < r; }

    void tick() {
        secs++;
        World w = pl.world;
        if (w == null) return;
        if (state.equals("cooled") && System.currentTimeMillis() >= healAt) { heal(); return; }
        if (state.equals("none")) {
            if (!pl.getConfig().getBoolean("volcano.enabled", true) || secs % (int) Math.max(60, cfg("check-minutes", 30) * 60) != 0) return;
            List<Player> sunlit = new ArrayList<>();
            for (Player p : w.getPlayers()) if (DeepMobs.fair(p) && pl.zone(p.getLocation()) == DeepGenerator.Zone.SUNLIT) sunlit.add(p);
            if (!sunlit.isEmpty() && random.nextDouble() < cfg("chance", 0.10)) start(sunlit.get(random.nextInt(sunlit.size())));
            return;
        }
        if (!state.equals("erupting")) return;
        long left = eruptEnds - System.currentTimeMillis();
        if (left <= 0) { cool(); return; }
        bar.setProgress(Math.max(0, Math.min(1, left / (cfg("minutes", 60) * 60_000))));
        Location top = new Location(w, cx + 0.5, peakY + 1, cz + 0.5);
        boolean watched = false;
        for (Player p : w.getPlayers()) {
            if (!near(p, 160)) { bar.removePlayer(p); continue; }
            bar.addPlayer(p);
            watched = true;
            if (secs % 2 == 0 && DeepMobs.fair(p) && near(p, cfg("boil-radius", 14) + cfg("base-radius", 22)) && p.isInWater()
                    && p.getLocation().getY() < peakY + 10 && !p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                    && Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz) < radiusAt(p.getLocation().getY()) + cfg("boil-radius", 14)) {
                p.damage(cfg("boil-damage", 2));
                p.sendActionBar(ChatColor.GOLD + "The water is boiling!" + ChatColor.GRAY + " (Fire Resistance keeps it off)");
                p.spawnParticle(Particle.BUBBLE_POP, p.getLocation().add(0, 1, 0), 12, 0.5, 0.6, 0.5, 0.05);
            }
            if (near(p, 60)) p.spawnParticle(Particle.ASH, p.getLocation().add(0, 2, 0), 30, 6, 4, 6, 0);
        }
        if (!watched || !top.isChunkLoaded()) return;
        // the plume
        w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, top, 25, 1.5, 1, 1.5, 0.06);
        w.spawnParticle(Particle.LAVA, top, 8, 1.5, 0.5, 1.5, 0);
        w.spawnParticle(Particle.DUST, top.clone().add(0, 4, 0), 30, 2.5, 4, 2.5, 0, new Particle.DustOptions(Color.fromRGB(60, 50, 50), 3f));
        w.spawnParticle(Particle.BUBBLE_COLUMN_UP, top, 30, 1.5, 2, 1.5, 0.2);
        if (secs % 4 == 0) w.playSound(top, Sound.BLOCK_LAVA_POP, SoundCategory.BLOCKS, 3f, 0.5f);
        if (secs % 10 == 0) w.playSound(top, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4f, 0.4f);
        // lava bombs
        int bombs = 1 + random.nextInt(3);
        for (int i = 0; i < bombs; i++) {
            FallingBlock fb = w.spawnFallingBlock(top.clone().add(0, 1, 0), Material.MAGMA_BLOCK.createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(false);
            fb.setMetadata(BOMB, new FixedMetadataValue(pl, true));
            double a = random.nextDouble() * Math.PI * 2, s = 0.35 + random.nextDouble() * 0.55;
            Edition.vel(fb, new Vector(Math.cos(a) * s, 0.9 + random.nextDouble() * 0.6, Math.sin(a) * s));
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (fb.isValid()) { burst(fb.getLocation()); fb.remove(); } }, 160L);
        }
        // fire creatures from the vents
        if (secs % 12 == 0 && pl.mobs != null) {
            long count = pl.mobs.live.values().stream().filter(b -> (b.k.id().equals("magmaeel") || b.k.id().equals("cindercrab")) && b.body.getLocation().distanceSquared(top) < 80 * 80).count();
            if (count < cfg("cap", 8)) {
                String id = random.nextBoolean() ? "magmaeel" : "cindercrab";
                double a = random.nextDouble() * Math.PI * 2;
                double y = floorY + 3 + random.nextInt(Math.max(1, peakY - floorY - 6));
                double r = radiusAt(y) + 2;
                Location at = new Location(w, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r);
                if (at.getBlock().getType() == Material.WATER) pl.mobs.spawn(DeepMobs.KINDS.get(id), at);
            }
        }
    }

    double radiusAt(double y) {
        double t = Math.max(0, Math.min(1, (y - floorY) / Math.max(1.0, peakY - floorY)));
        return cfg("base-radius", 22) * Math.pow(1 - t, 0.85);
    }

    void burst(Location l) {
        World w = l.getWorld();
        w.spawnParticle(Particle.LAVA, l, 14, 0.8, 0.5, 0.8, 0);
        w.spawnParticle(Particle.EXPLOSION, l, 1);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.5f, 1.3f);
        for (Player p : w.getPlayers()) if (DeepMobs.fair(p) && p.getLocation().distanceSquared(l) < 3.5 * 3.5) {
            p.damage(cfg("bomb-damage", 6));
            p.setFireTicks(Math.max(p.getFireTicks(), 60));
        }
    }

    @EventHandler
    public void onLand(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb) || !fb.hasMetadata(BOMB)) return;
        e.setCancelled(true); // nothing left behind
        burst(fb.getLocation());
        fb.remove();
    }

    // =========================================================================================== cooling
    void cool() {
        if (!state.equals("erupting") && !state.equals("rising")) return;
        if (bar != null) bar.removeAll();
        bar = null;
        build.clear();
        if (pl.world != null) {
            List<Long> keys = new ArrayList<>(scar.keySet());
            List<int[]> core = new ArrayList<>();
            for (long k : keys) {
                int[] p = unpack(k);
                if (!pl.world.isChunkLoaded(p[0] >> 4, p[2] >> 4)) continue;
                Block b = pl.world.getBlockAt(p[0], p[1], p[2]);
                if (b.getType() == Material.MAGMA_BLOCK) {
                    boolean vent = Math.hypot(p[0] - cx, p[2] - cz) < 4.5;
                    b.setType(vent || random.nextDouble() < 0.55 ? Material.OBSIDIAN : random.nextDouble() < 0.5 ? Material.CRYING_OBSIDIAN : Material.SMOOTH_BASALT, false);
                    if (vent) core.add(p);
                }
            }
            // debris veins in the plugged vent
            int veins = (int) cfg("debris-min", 6) + random.nextInt(Math.max(1, (int) cfg("debris-max", 10) - (int) cfg("debris-min", 6) + 1));
            java.util.Collections.shuffle(core, random);
            for (int i = 0; i < Math.min(veins, core.size()); i++) {
                int[] p = core.get(i);
                pl.world.getBlockAt(p[0], p[1], p[2]).setType(Material.ANCIENT_DEBRIS, false);
            }
            // the crater's plug + 3 chests round the rim
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (Math.hypot(dx, dz) < 3.3) set(cx + dx, peakY - 1, cz + dz, Material.OBSIDIAN);
            for (int i = 0; i < 3; i++) {
                double a = Math.PI * 2 * i / 3 + random.nextDouble();
                int x = cx + (int) Math.round(Math.cos(a) * 5), z = cz + (int) Math.round(Math.sin(a) * 5);
                for (int y = peakY + 2; y > floorY; y--) {
                    Block b = pl.world.getBlockAt(x, y, z);
                    if (b.getType() != Material.WATER && b.getType() != Material.AIR) {
                        Block up = b.getRelative(0, 1, 0);
                        BlockData chest = Bukkit.createBlockData("minecraft:chest[waterlogged=true]");
                        up.setBlockData(chest, false);
                        chests.add(new int[]{x, y + 1, z});
                        break;
                    }
                }
            }
            announce(ChatColor.GRAY + "The volcano at " + cx + ", " + cz + " goes quiet and cools to obsidian. Its heart is full of Ancient Debris, and three chests sit round the crater.");
        }
        state = "cooled";
        healAt = System.currentTimeMillis() + (long) (cfg("linger-hours", 6) * 3_600_000);
        save();
    }

    /** The sea takes it back: every block it changed, where it's still volcano rock (players' builds and the chests stay). */
    void heal() {
        if (pl.world == null) return;
        int put = 0;
        for (Map.Entry<Long, String> en : scar.entrySet()) {
            int[] p = unpack(en.getKey());
            Block b = pl.world.getBlockAt(p[0], p[1], p[2]);
            if (!ROCK.contains(b.getType()) && b.getType() != Material.WATER) continue;
            BlockData d;
            try { d = Bukkit.createBlockData(en.getValue()); } catch (IllegalArgumentException ex) { d = Material.WATER.createBlockData(); }
            if (!b.getBlockData().equals(d)) { b.setBlockData(d, false); put++; }
        }
        pl.getLogger().info("[Volcano] the sea took back the volcano at " + cx + ", " + cz + " (" + put + " blocks)");
        scar.clear();
        chests.clear();
        state = "none";
        save();
    }

    void stop() { if (state.equals("rising") || state.equals("erupting")) cool(); }

    void shutdown() {
        stop();
        save();
    }

    void announce(String msg) { if (pl.world != null) for (Player p : pl.world.getPlayers()) p.sendMessage(msg); }

    String where() {
        return switch (state) {
            case "none" -> "No volcano right now.";
            case "rising" -> "Rising at " + cx + ", " + cz + " (" + (build.size() - built) + " blocks to go).";
            case "erupting" -> "Erupting at " + cx + ", " + cz + ", peak y " + peakY + ", " + Math.max(0, (eruptEnds - System.currentTimeMillis()) / 60_000) + " min left.";
            default -> "Cooled at " + cx + ", " + cz + "; the sea takes it back in " + Math.max(0, (healAt - System.currentTimeMillis()) / 60_000) + " min.";
        };
    }

    // =========================================================================================== volcano.yml
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("state", state);
        y.set("x", cx); y.set("z", cz); y.set("floor", floorY); y.set("peak", peakY);
        y.set("heal-at", healAt);
        List<String> s = new ArrayList<>(scar.size());
        for (Map.Entry<Long, String> en : scar.entrySet()) s.add(en.getKey() + "|" + en.getValue());
        y.set("scar", s);
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save volcano.yml: " + e.getMessage()); }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        state = y.getString("state", "none");
        cx = y.getInt("x"); cz = y.getInt("z"); floorY = y.getInt("floor"); peakY = y.getInt("peak");
        healAt = y.getLong("heal-at");
        for (String s : y.getStringList("scar")) {
            int bar = s.indexOf('|');
            if (bar > 0) try { scar.put(Long.parseLong(s.substring(0, bar)), s.substring(bar + 1)); } catch (NumberFormatException ignored) { }
        }
        if (state.equals("rising") || state.equals("erupting")) { // a crash mid-eruption: it cools now, heals later
            state = "cooled";
            healAt = System.currentTimeMillis() + (long) (cfg("linger-hours", 6) * 3_600_000);
        }
    }
}
