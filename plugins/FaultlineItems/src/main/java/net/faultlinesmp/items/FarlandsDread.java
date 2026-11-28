package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

/**
 * THE FARLANDS' DREAD (`farlands-dread:`), ticked from Farlands.tickAll:
 *  - THE STILLNESS: every monster round you freezes, the music and the rain stop, no particles; 30 s later one footstep right
 *    behind you, a breath, and it all starts again.
 *  - CHUNK ROT: a chunk-sized patch near you turns to the wrong blocks for a few seconds (only you see it), then snaps back.
 *  - THE LONG NIGHT: rare, server-wide, 10 min: no moon, near pitch black, monsters drop double, more of them come,
 *    insanity twice as fast; a lit Rim Lighthouse (or a Lantern of Sanity) is the only safe place.
 *  - ECHO STORM: every Echo out there wakes and hunts the nearest player (Farlands.echoStorm).
 *  - THE FACELESS: a tall blank-faced thing that only moves while nobody is looking at it.
 *  - THE WORLD GOES WRONG past `wrong-depth` 70% Rim Depth: compasses spin, the /coords bar lies, a red tint on the screen.
 *  - SLEEP: sleeping in the Farlands works (it's always night out there), but sometimes you wake somewhere else and your
 *    bed is gone.
 *  - THE LANTERN OF SANITY: a placeable soul lantern; fuelled, insanity can't rise within 8 blocks and falls fast.
 */
final class FarlandsDread implements Listener {

    static final String FACELESS = "faultline_faceless", STILLED = "dread_stilled";
    private static final Material[] ROT = {Material.MAGENTA_CONCRETE, Material.BLACK_CONCRETE, Material.MAGENTA_GLAZED_TERRACOTTA,
            Material.LIME_WOOL, Material.GLASS, Material.PURPLE_STAINED_GLASS, Material.CYAN_WOOL, Material.NETHERRACK, Material.SPONGE,
            Material.BEDROCK, Material.TARGET, Material.WHITE_WOOL};

    final FaultlineItems plugin;
    private final Random rnd = new Random();
    final NamespacedKey lanternKey, lanternBlocks;
    private int tick;

    // the Stillness: player -> tick it ends, and the mobs it froze
    private final Map<UUID, Integer> still = new HashMap<>();
    private final Map<UUID, List<UUID>> frozen = new HashMap<>();
    private final Map<UUID, Integer> stillReady = new HashMap<>(), rotReady = new HashMap<>(), facelessReady = new HashMap<>();
    // chunk rot: player -> faked spots (put back later)
    private final Map<UUID, List<Location>> rotted = new HashMap<>();
    // the Long Night (server ticks)
    int longNightUntil;
    private int longNightReady;
    private final Set<UUID> inLongNight = new HashSet<>();
    // the world goes wrong
    private final Set<UUID> wrong = new HashSet<>();
    static final Set<UUID> SCRAMBLED = new HashSet<>();
    // the Faceless
    private final Map<UUID, Integer> faceless = new HashMap<>();   // entity -> last tick a survival player was near
    // lanterns: world -> packed block key -> fuel runs out at (epoch ms)
    private final Map<String, Map<Long, Long>> lanterns = new HashMap<>();
    // sleep
    private final Map<UUID, Integer> sleepingSince = new HashMap<>();
    private final Map<UUID, Block> bedOf = new HashMap<>();

    FarlandsDread(FaultlineItems plugin) {
        this.plugin = plugin;
        lanternKey = new NamespacedKey(plugin, "sanity_lantern");
        lanternBlocks = new NamespacedKey(plugin, "sanity_lanterns");
        try {
            NamespacedKey rk = new NamespacedKey(plugin, "sanity_lantern");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, lantern(1));
                r.shape("GAG", "ASA", "GAG");
                r.setIngredient('G', Material.GLOWSTONE_DUST);
                r.setIngredient('A', Material.AMETHYST_SHARD);
                r.setIngredient('S', Material.SOUL_LANTERN);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { plugin.getLogger().warning("Lantern of Sanity recipe: " + e.getMessage()); }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (e.getScoreboardTags().contains(FACELESS)) e.remove();   // never saved
                    else if (e.getScoreboardTags().contains(STILLED) && e instanceof Mob m) { m.setAI(true); m.removeScoreboardTag(STILLED); }
                }
                for (Chunk c : w.getLoadedChunks()) readLanterns(c);
            }
        });
    }

    double cfg(String k, double d) { return FarNerf.get(plugin, "farlands-dread." + k, d); }
    boolean flag(String k) { return plugin.getConfig().getBoolean("farlands-dread." + k, true); }
    Farlands far() { return plugin.farlands; }

    // ================================================================= hooks other classes read

    boolean still(Player p) { return still.containsKey(p.getUniqueId()); }
    boolean longNight() { return tick < longNightUntil; }

    /** The player time the Farlands should show (spookyTick): the Long Night is a moonless midnight (moon phase 4). */
    long playerTime() { return longNight() ? 4 * 24000L + 18000 : 18000; }

    /** The Stillness takes the rain away too. */
    WeatherType weather(Player p) { return still(p) ? WeatherType.CLEAR : WeatherType.DOWNFALL; }

    /** Within a fuelled Lantern of Sanity's reach. */
    boolean nearLantern(Player p) {
        Map<Long, Long> m = lanterns.get(p.getWorld().getName());
        if (m == null || m.isEmpty()) return false;
        long now = System.currentTimeMillis();
        double r = cfg("lantern.radius", 8), r2 = r * r;
        Location l = p.getLocation();
        for (Map.Entry<Long, Long> e : m.entrySet()) {
            if (e.getValue() < now) continue;
            long k = e.getKey();
            int x = (int) (k << 37 >> 37), y = (int) (k >>> 54) - 64, z = (int) (k << 10 >> 37);
            Block b = p.getWorld().getBlockAt(x, y, z);
            if (b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(l) <= r2) return true;
        }
        return false;
    }

    // ================================================================= the tick (every tick, from Farlands.tickAll)

    void tick() {
        tick++;
        if (!flag("enabled")) return;
        try {
            if (tick % 20 == 0) second();
            if (!still.isEmpty()) stillTick();
            if (!faceless.isEmpty()) facelessTick();
            if (tick % 40 == 0) wrongTick();
            if (tick % 20 == 0) sleepTick();
        } catch (RuntimeException ex) {
            if (tick % 200 == 0) plugin.getLogger().warning("[Dread] " + ex);
        }
    }

    private void second() {
        List<Player> out = far().playersOut();
        // the Long Night: one roll every 10 minutes while someone's out there
        if (!longNight() && tick % 12000 == 0 && !out.isEmpty() && tick >= longNightReady
                && rnd.nextDouble() < cfg("long-night.chance", 0.02)) startLongNight();
        if (longNight()) longNightTick(out);
        else if (!inLongNight.isEmpty()) endLongNight();
        // the Echo Storm
        if (tick % 12000 == 6000 && !out.isEmpty() && !far().echoStorm() && rnd.nextDouble() < cfg("echo-storm.chance", 0.03)) startEchoStorm();
        for (Player p : out) {
            UUID id = p.getUniqueId();
            if (OuterRim.safe(p)) continue;
            if (tick % 600 == 0 && !still(p) && !far().hushed(p) && tick >= stillReady.getOrDefault(id, 0)
                    && rnd.nextDouble() < cfg("stillness.chance", 0.05)) startStill(p);
            if (tick % (int) (cfg("displace.every-minutes", 5) * 1200) == 900 && rnd.nextDouble() < cfg("displace.chance", 0.05)) displace(p);
            if (tick % 600 == 300 && tick >= rotReady.getOrDefault(id, 0) && rnd.nextDouble() < cfg("chunk-rot.chance", 0.06)) {
                rotReady.put(id, tick + (int) (cfg("chunk-rot.cooldown-seconds", 180) * 20));
                rot(p);
            }
            if (tick % 600 == 150 && OuterRim.depth(p.getLocation()) >= cfg("faceless.min-depth", 0.35)
                    && tick >= facelessReady.getOrDefault(id, 0) && faceless.size() < cfg("faceless.max", 2)
                    && rnd.nextDouble() < cfg("faceless.chance", 0.03)) {
                if (spawnFaceless(p) != null) facelessReady.put(id, tick + (int) (cfg("faceless.cooldown-minutes", 20) * 1200));
            }
        }
        if (tick % 1200 == 0) {
            stillReady.values().removeIf(t -> t < tick);
            rotReady.values().removeIf(t -> t < tick);
            facelessReady.values().removeIf(t -> t < tick);
            long now = System.currentTimeMillis();
            for (Map<Long, Long> m : lanterns.values()) m.values().removeIf(t -> t < now - 3_600_000L);
        }
        // lantern glow
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Map<Long, Long>> e : lanterns.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) continue;
            for (Map.Entry<Long, Long> l : e.getValue().entrySet()) {
                if (l.getValue() < now) continue;
                long k = l.getKey();
                int x = (int) (k << 37 >> 37), y = (int) (k >>> 54) - 64, z = (int) (k << 10 >> 37);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                w.spawnParticle(Particle.SOUL, x + 0.5, y + 0.7, z + 0.5, 2, 0.3, 0.2, 0.3, 0.01);
                w.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 2.5, 1, 2.5, 0);
            }
        }
    }

    // ================================================================= 1. the Stillness

    void startStill(Player p) {
        UUID id = p.getUniqueId();
        stillReady.put(id, tick + (int) (cfg("stillness.cooldown-minutes", 30) * 1200));
        still.put(id, tick + (int) (cfg("stillness.seconds", 30) * 20));
        List<UUID> froze = new ArrayList<>();
        double r = cfg("stillness.radius", 48);
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Mob m) || !(e instanceof Enemy)) continue;
            if (!m.hasAI()) continue;
            boolean ours = false;
            for (String t : m.getScoreboardTags()) if (t.startsWith("faultline")) { ours = true; break; }   // mimics, bosses, hitboxes, echoes
            if (ours) continue;
            m.setAI(false);
            m.addScoreboardTag(STILLED);
            m.setVelocity(new Vector());
            froze.add(m.getUniqueId());
        }
        frozen.put(id, froze);
        p.stopAllSounds();
        far().musicReset(id);   // the track starts again right after
        p.setPlayerWeather(WeatherType.CLEAR);
        plugin.getLogger().info("[Dread] the Stillness round " + p.getName() + " (" + froze.size() + " mobs frozen)");
    }

    private void stillTick() {
        for (Iterator<Map.Entry<UUID, Integer>> it = still.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !far().inCached(p)) { unfreeze(e.getKey()); it.remove(); continue; }
            if (tick % 10 == 0) { p.stopAllSounds(); far().musicReset(p.getUniqueId()); }   // nothing plays: not even the wind
            if (tick < e.getValue()) continue;
            it.remove();
            // the footstep
            Vector back = p.getLocation().getDirection().setY(0);
            if (back.lengthSquared() < 1e-4) back = new Vector(1, 0, 0);
            Location behind = p.getLocation().add(back.normalize().multiply(-1.2));
            Block under = behind.getBlock().getRelative(0, -1, 0);
            p.playSound(behind, under.getBlockData().getSoundGroup().getStepSound(), SoundCategory.PLAYERS, 1f, 0.8f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.playSound(behind, "minecraft:entity.player.breath", SoundCategory.PLAYERS, 0.9f, 0.6f);
                unfreeze(p.getUniqueId());
                p.setPlayerWeather(WeatherType.DOWNFALL);
                if (plugin.insanity != null) plugin.insanity.force(p, plugin.insanity.get(p) + cfg("stillness.insanity", 6));
            }, 14);
        }
    }

    private void unfreeze(UUID player) {
        List<UUID> l = frozen.remove(player);
        if (l == null) return;
        for (UUID u : l) { Entity e = Bukkit.getEntity(u); if (e instanceof Mob m && m.isValid()) { m.setAI(true); m.removeScoreboardTag(STILLED); } }
    }

    // ================================================================= 2. chunk rot

    void rot(Player p) {
        Location c = p.getLocation();
        double a = rnd.nextDouble() * Math.PI * 2, d = 18 + rnd.nextDouble() * 18;
        int bx = (int) Math.floor(c.getX() + Math.cos(a) * d) >> 4 << 4, bz = (int) Math.floor(c.getZ() + Math.sin(a) * d) >> 4 << 4;
        World w = p.getWorld();
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return;
        Map<Location, BlockData> fake = new HashMap<>();
        Material main = ROT[rnd.nextInt(ROT.length)], second = ROT[rnd.nextInt(ROT.length)];
        boolean checker = rnd.nextBoolean();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = w.getHighestBlockYAt(bx + x, bz + z);
            if (Math.abs(top - c.getBlockY()) > 40) continue;
            Material m = checker ? (((x >> 1) + (z >> 1)) % 2 == 0 ? Material.MAGENTA_CONCRETE : Material.BLACK_CONCRETE)
                    : (rnd.nextInt(5) == 0 ? second : main);
            for (int y = top; y > top - 3; y--) {
                Block b = w.getBlockAt(bx + x, y, bz + z);
                if (b.getType().isAir() || b.getState(false) instanceof org.bukkit.block.TileState) continue;
                fake.put(b.getLocation(), m.createBlockData());
            }
        }
        if (fake.isEmpty()) return;
        try { p.sendMultiBlockChange(fake); } catch (RuntimeException ex) { for (var e : fake.entrySet()) p.sendBlockChange(e.getKey(), e.getValue()); }
        p.playSound(new Location(w, bx + 8, c.getY(), bz + 8), "minecraft:block.beacon.deactivate", SoundCategory.AMBIENT, 1f, 0.5f);
        p.playSound(new Location(w, bx + 8, c.getY(), bz + 8), "minecraft:block.sculk_shrieker.shriek", SoundCategory.AMBIENT, 0.3f, 1.8f);
        List<Location> spots = new ArrayList<>(fake.keySet());
        rotted.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(spots);
        Bukkit.getScheduler().runTaskLater(plugin, () -> unrot(p, spots), 60 + rnd.nextInt(80));
    }

    private void unrot(Player p, List<Location> spots) {
        List<Location> mine = rotted.get(p.getUniqueId());
        if (mine != null) { mine.removeAll(spots); if (mine.isEmpty()) rotted.remove(p.getUniqueId()); }
        if (!p.isOnline()) return;
        Map<Location, BlockData> real = new HashMap<>();
        for (Location l : spots) if (l.getWorld() == p.getWorld() && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) real.put(l, l.getBlock().getBlockData());
        if (real.isEmpty()) return;
        try { p.sendMultiBlockChange(real); } catch (RuntimeException ex) { for (var e : real.entrySet()) p.sendBlockChange(e.getKey(), e.getValue()); }
    }

    // ================================================================= 3. the Long Night

    void startLongNight() {
        longNightUntil = tick + (int) (cfg("long-night.minutes", 10) * 1200);
        longNightReady = longNightUntil + (int) (cfg("long-night.cooldown-hours", 3) * 72000);
        plugin.getLogger().info("[Dread] the Long Night begins");
    }

    void stopLongNight() { longNightUntil = tick; }

    private void longNightTick(List<Player> out) {
        for (Player p : out) {
            UUID id = p.getUniqueId();
            if (inLongNight.add(id)) {
                p.showTitle(Title.title(Component.text("THE LONG NIGHT", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, true),
                        Component.text("The moon is gone.", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(2))));
                p.playSound(p, "minecraft:ambient.cave", SoundCategory.AMBIENT, 1f, 0.5f);
                p.setPlayerTime(playerTime(), false);
            }
            boolean safe = OuterRim.safe(p) || nearLantern(p);
            if (!safe) {
                // near-black: a hidden Darkness kept on (its pulse never fully ends)
                PotionEffect cur = p.getPotionEffect(PotionEffectType.DARKNESS);
                if (cur == null || (cur.isAmbient() && !cur.hasParticles() && cur.getDuration() < 60))
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, true, false, false));
                if (plugin.insanity != null && !plugin.insanity.wearing(p)) plugin.insanity.force(p, plugin.insanity.get(p) + cfg("long-night.insanity-per-second", 0.4));
                if (tick % 200 == 0) longNightSpawn(p);
            }
        }
        inLongNight.removeIf(id -> { Player p = Bukkit.getPlayer(id); return p == null || !far().inCached(p); });
    }

    private void endLongNight() {
        for (UUID id : inLongNight) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.setPlayerTime(playerTime(), false);
            PotionEffect cur = p.getPotionEffect(PotionEffectType.DARKNESS);
            if (cur != null && cur.isAmbient() && !cur.hasParticles()) p.removePotionEffect(PotionEffectType.DARKNESS);
            p.sendActionBar(Component.text("The moon comes back.", NamedTextColor.GRAY));
        }
        inLongNight.clear();
        plugin.getLogger().info("[Dread] the Long Night ends");
    }

    /** More monsters come for anyone out in the open (NATURAL: the Farlands make them Elites / Mutants, torches still stop them). */
    private void longNightSpawn(Player p) {
        if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL) return;
        int near = 0;
        for (Entity e : p.getNearbyEntities(32, 16, 32)) if (e instanceof Enemy) near++;
        if (near >= cfg("long-night.cap", 10)) return;
        EntityType[] kinds = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.ZOMBIE};
        for (int i = 0; i < 2; i++) {
            Location at = ground(p, 14, 26);
            if (at == null) continue;
            p.getWorld().spawnEntity(at, kinds[rnd.nextInt(kinds.length)], CreatureSpawnEvent.SpawnReason.NATURAL);
        }
    }

    /** Double plain drops from monsters killed in the Long Night. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLongNightKill(EntityDeathEvent e) {
        if (!longNight() || !(e.getEntity() instanceof Enemy) || e.getEntity().getKiller() == null) return;
        LivingEntity v = e.getEntity();
        for (String t : v.getScoreboardTags()) if (t.startsWith("faultline_player_mimic") || t.equals(Farlands.ECHO) || t.startsWith("faultline_looter")) return;
        if (!Farlands.in(v.getLocation())) return;
        List<ItemStack> extra = new ArrayList<>();
        var eq = v.getEquipment();
        for (ItemStack it : e.getDrops()) {
            if (it == null || it.getType().isAir() || it.getMaxStackSize() == 1 || it.hasItemMeta()) continue;
            boolean held = false;
            if (eq != null) for (EquipmentSlot s : EquipmentSlot.values()) {
                try { ItemStack w = eq.getItem(s); if (w != null && w.isSimilar(it)) { held = true; break; } } catch (RuntimeException ignored) { }
            }
            if (!held) extra.add(it.clone());
        }
        e.getDrops().addAll(extra);
        e.setDroppedExp(e.getDroppedExp() * 2);
    }

    // ================================================================= 4. the Echo Storm

    void startEchoStorm() {
        int t = (int) (cfg("echo-storm.minutes", 8) * 1200);
        far().startEchoStorm(t);
        for (Player p : far().playersOut()) {
            p.sendActionBar(Component.text("The dead are walking.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true));
            p.playSound(p, "minecraft:entity.warden.emerge", SoundCategory.HOSTILE, 0.6f, 0.5f);
        }
        plugin.getLogger().info("[Dread] an Echo Storm");
    }

    // ================================================================= 6. the Faceless

    Zombie spawnFaceless(Player p) {
        Location at = behind(p, 30, 45);
        if (at == null) at = ground(p, 30, 45);
        if (at == null) return null;
        Zombie z = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(FACELESS));
        z.setPersistent(false);
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        Emotes.q(() -> z.setShouldBurnInDay(false));
        Emotes.q(() -> z.setCanPickupItems(false));
        Emotes.q(() -> z.setRemoveWhenFarAway(false));
        z.setSilent(true);
        z.setAI(false);
        MimicPlayer.attr(z, Attribute.SCALE, 1.35);
        MimicPlayer.attr(z, Attribute.ARMOR, 10);
        MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        BigHealth.set(z, cfg("faceless.health", 80));
        var eq = z.getEquipment();
        eq.clear();
        eq.setHelmet(new ItemStack(Material.BLACK_CONCRETE));   // no face
        eq.setChestplate(black(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(black(Material.LEATHER_LEGGINGS));
        eq.setBoots(black(Material.LEATHER_BOOTS));
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0); } catch (RuntimeException ignored) { }
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        faceless.put(z.getUniqueId(), tick);
        plugin.getLogger().info("[Dread] the Faceless came for " + p.getName());
        return z;
    }

    private static ItemStack black(Material m) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(Color.fromRGB(0x07070A)); it.setItemMeta(lm); }
        return it;
    }

    /** Someone in survival can see it (looking roughly at it, no wall between). */
    private boolean watched(Zombie z) {
        Location mid = z.getLocation().add(0, z.getHeight() * 0.6, 0);
        for (Player p : z.getWorld().getPlayers()) {
            if (!Farlands.survival(p)) continue;
            Location eye = p.getEyeLocation();
            Vector to = mid.toVector().subtract(eye.toVector());
            double d = to.length();
            if (d > 96 || d < 0.1) continue;
            if (eye.getDirection().dot(to.multiply(1 / d)) < cfg("faceless.view-cos", 0.5)) continue;   // ~60 degrees off centre
            if (clear(eye, mid)) return true;
        }
        return false;
    }

    private static boolean clear(Location a, Location b) {
        Vector step = b.toVector().subtract(a.toVector());
        double len = step.length();
        if (len < 0.5) return true;
        step.multiply(0.5 / len);
        Location c = a.clone();
        for (double t = 0.5; t < len - 0.5; t += 0.5) {
            c.add(step);
            if (c.getBlock().getType().isOccluding()) return false;
        }
        return true;
    }

    private void facelessTick() {
        for (Iterator<Map.Entry<UUID, Integer>> it = faceless.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Zombie z) || !z.isValid() || z.isDead()) { it.remove(); continue; }
            Player tgt = null; double best = 96 * 96;
            for (Player p : z.getWorld().getPlayers()) {
                if (!Farlands.survival(p)) continue;
                double d = p.getLocation().distanceSquared(z.getLocation());
                if (d < best) { best = d; tgt = p; }
            }
            if (tgt == null) {
                if (tick - en.getValue() > 2400) { z.remove(); it.remove(); }
                continue;
            }
            en.setValue(tick);
            if (watched(z)) {
                // frozen while seen: it only ever turns its blank face toward you
                if (tick % 2 == 0) {
                    Location l = z.getLocation();
                    l.setDirection(tgt.getLocation().toVector().subtract(l.toVector()));
                    z.setRotation(l.getYaw(), 0);
                }
                continue;
            }
            Location from = z.getLocation();
            Vector to = tgt.getLocation().toVector().subtract(from.toVector()).setY(0);
            double dist = to.length();
            if (dist < cfg("faceless.reach", 1.6)) {
                if (tick % 10 == 0) {
                    tgt.damage(cfg("faceless.damage", 7), z);
                    tgt.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
                    tgt.playSound(tgt, "minecraft:entity.warden.attack_impact", SoundCategory.HOSTILE, 1f, 0.6f);
                    if (plugin.insanity != null) plugin.insanity.force(tgt, plugin.insanity.get(tgt) + 4);
                }
                continue;
            }
            double sp = Math.min(dist - 1.2, cfg("faceless.speed", 0.55));
            if (sp <= 0) continue;
            Location next = from.clone().add(to.multiply(sp / dist));
            Location floor = floorAt(next, from.getBlockY());
            if (floor == null) continue;
            floor.setDirection(tgt.getLocation().toVector().subtract(floor.toVector()));
            floor.setPitch(0);
            z.teleport(floor);
            if (tick % 6 == 0) z.getWorld().playSound(floor, "minecraft:block.gravel.step", SoundCategory.HOSTILE, 0.4f, 0.5f);
        }
    }

    /** Where it can stand near this x/z: 3 up to 4 down, 2 blocks of room (it's tall but slips through like a shadow). */
    private static Location floorAt(Location at, int fromY) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int dy = 2; dy >= -4; dy--) {
            int y = fromY + dy;
            Block f = w.getBlockAt(x, y - 1, z), b = w.getBlockAt(x, y, z), h = w.getBlockAt(x, y + 1, z);
            if (f.getType().isSolid() && !b.getType().isSolid() && !h.getType().isSolid() && !b.isLiquid())
                return new Location(w, at.getX(), y, at.getZ());
        }
        return null;
    }

    @EventHandler
    public void onFacelessDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(FACELESS)) return;
        faceless.remove(e.getEntity().getUniqueId());
        e.getDrops().clear();
        e.setDroppedExp(80);
        if (e.getEntity().getKiller() == null) return;
        Player k = e.getEntity().getKiller();
        if (plugin.outerRim != null) e.getDrops().add(plugin.outerRim.shard(3 + rnd.nextInt(4)));
        if (plugin.farlandsNight != null && rnd.nextDouble() < cfg("faceless.sigil-chance", 0.1)) e.getDrops().add(plugin.farlandsNight.sigil(1));
        if (rnd.nextDouble() < cfg("faceless.lantern-chance", 0.25)) e.getDrops().add(lantern(1));
        e.getEntity().getWorld().spawnParticle(Particle.SQUID_INK, e.getEntity().getLocation().add(0, 1.2, 0), 60, 0.4, 1, 0.4, 0.02);
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + k.getName() + " faceless 1"); } catch (RuntimeException ignored) { }
    }

    // ================================================================= 16. the world goes wrong

    private void wrongTick() {
        double at = cfg("wrong-depth", 0.7);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean in = far().inCached(p) && Farlands.survival(p) && OuterRim.depth(p.getLocation()) >= at
                    && !OuterRim.safe(p) && (plugin.insanity == null || !plugin.insanity.wearing(p));
            if (!in) { if (wrong.remove(id)) unwrong(p); continue; }
            if (wrong.add(id)) {
                p.sendActionBar(Component.text("Something about this place is wrong.", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, true));
                SCRAMBLED.add(id);
                try {
                    WorldBorder b = Bukkit.createWorldBorder();
                    b.setCenter(p.getLocation());
                    b.setSize(59_999_000);
                    b.setWarningDistance(59_999_000);   // the whole screen takes the border's red tint
                    p.setWorldBorder(b);
                } catch (RuntimeException | LinkageError ignored) { }
            }
            // compasses spin
            Location l = p.getLocation();
            double a = rnd.nextDouble() * Math.PI * 2;
            p.setCompassTarget(l.clone().add(Math.cos(a) * 100, 0, Math.sin(a) * 100));
        }
    }

    private void unwrong(Player p) {
        SCRAMBLED.remove(p.getUniqueId());
        try { p.setWorldBorder(null); } catch (RuntimeException | LinkageError ignored) { }
        p.setCompassTarget(p.getWorld().getSpawnLocation());
    }

    /** The /coords bar out there: numbers that drift and lie. */
    static String scramble(Location l, Random r) {
        int x = l.getBlockX() + r.nextInt(4001) - 2000, y = l.getBlockY() + r.nextInt(201) - 100, z = l.getBlockZ() + r.nextInt(4001) - 2000;
        if (r.nextInt(4) == 0) return "§k" + x + "§r " + y + " §k" + z + "§r";
        return x + " " + y + " " + z;
    }

    // ================================================================= 17. you sleep, and wake somewhere else

    /** It's always night out there for you, so beds work even when the world's clock says day. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBed(PlayerBedEnterEvent e) {
        if (!flag("sleep.enabled")) return;
        Player p = e.getPlayer();
        if (!Farlands.in(e.getBed().getLocation())) return;
        if (e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW) e.setUseBed(Event.Result.ALLOW);
        if (e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK || e.useBed() == Event.Result.ALLOW)
            { sleepingSince.put(p.getUniqueId(), tick); bedOf.put(p.getUniqueId(), e.getBed()); }
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent e) { sleepingSince.remove(e.getPlayer().getUniqueId()); }

    private void sleepTick() {
        for (Iterator<Map.Entry<UUID, Integer>> it = sleepingSince.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isSleeping()) { it.remove(); continue; }
            if (tick - en.getValue() < cfg("sleep.after-seconds", 4) * 20) continue;
            it.remove();
            if (rnd.nextDouble() >= cfg("sleep.chance", 0.35)) continue;
            moveSleeper(p);
        }
    }

    void moveSleeper(Player p) {
        Block head = bedOf.remove(p.getUniqueId());
        if (head == null) head = p.getLocation().getBlock();
        Location to = null;
        for (int i = 0; i < 12 && to == null; i++) to = ground(p, cfg("sleep.min-distance", 25), cfg("sleep.max-distance", 70));
        try { p.wakeup(false); } catch (RuntimeException ignored) { }
        if (to == null) return;
        // the bed is gone: both halves, no drop
        if (head.getBlockData() instanceof Bed bd) {
            Block other = bd.getPart() == Bed.Part.HEAD ? head.getRelative(bd.getFacing().getOppositeFace()) : head.getRelative(bd.getFacing());
            if (other.getBlockData() instanceof Bed) other.setType(Material.AIR, false);
            head.setType(Material.AIR, false);
        }
        try { p.setRespawnLocation(null); } catch (RuntimeException | LinkageError ignored) { }
        Location dest = to;
        dest.setYaw(rnd.nextFloat() * 360);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.teleport(dest);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false, false));
            p.showTitle(Title.title(Component.empty(), Component.text("You don't remember walking here.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(3), Duration.ofSeconds(2))));
            p.playSound(p, "minecraft:ambient.cave", SoundCategory.AMBIENT, 1f, 0.6f);
            if (plugin.insanity != null) plugin.insanity.force(p, plugin.insanity.get(p) + cfg("sleep.insanity", 15));
            plugin.getLogger().info("[Dread] " + p.getName() + " woke up somewhere else");
        });
    }

    // ================================================================= lost: you blink and you're somewhere else

    boolean displace(Player p) {
        if (!flag("displace.enabled") || p.isInsideVehicle() || p.isGliding() || p.isSleeping() || (plugin.riptideLock != null && plugin.riptideLock.inCombat(p))) return false;
        Location to = null;
        for (int i = 0; i < 12 && to == null; i++) to = ground(p, cfg("displace.min-distance", 40), cfg("displace.max-distance", 150));
        if (to == null) return false;
        Location dest = to;
        dest.setYaw(rnd.nextFloat() * 360);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
        p.playSound(p, "minecraft:block.portal.trigger", SoundCategory.AMBIENT, 0.25f, 0.4f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !far().inCached(p)) return;
            p.teleport(dest);
            p.playSound(p, "minecraft:ambient.cave", SoundCategory.AMBIENT, 1f, 0.6f);
            p.sendActionBar(Component.text("...where am I?", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true));
            if (plugin.insanity != null) plugin.insanity.force(p, plugin.insanity.get(p) + cfg("displace.insanity", 8));
            plugin.getLogger().info("[Dread] " + p.getName() + " was moved " + (int) p.getLocation().distance(dest) + " blocks");
        }, 20);
        return true;
    }

    // ================================================================= 10. the Lantern of Sanity

    ItemStack lantern(int n) {
        ItemStack it = FaultlineItems.NewAccessoryItems.make(plugin, Material.SOUL_LANTERN, ChatColor.AQUA + "" + ChatColor.BOLD + "Lantern of Sanity", lanternKey,
                ChatColor.AQUA + "Place it: within 8 blocks your mind is your own.",
                ChatColor.GRAY + "Insanity can't rise near it and falls fast.",
                ChatColor.GRAY + "Burns 10 min. Right-click it with an Amethyst Shard",
                ChatColor.GRAY + "(+10 min) or Glowstone Dust (+5 min) to feed it.");
        var m = it.getItemMeta();
        m.getPersistentDataContainer().remove(plugin.getAccessoryKey());   // a block, not an accessory
        m.setItemModel(NamespacedKey.minecraft("soul_lantern"));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(16);
        it.setItemMeta(m);
        it.setAmount(Math.max(1, n));
        return it;
    }

    boolean isLantern(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(lanternKey, PersistentDataType.BYTE); }

    static long key(Block b) { return ((long) (b.getX() & 0x7FFFFFF)) | (((long) b.getZ() & 0x7FFFFFF) << 27) | ((long) (b.getY() + 64) << 54); }

    private int local(Block b) { return ((b.getX() & 15) << 16) | ((b.getZ() & 15) << 12) | ((b.getY() + 2048) & 4095); }

    private void save(Chunk c) {
        Map<Long, Long> m = lanterns.get(c.getWorld().getName());
        List<Long> out = new ArrayList<>();
        if (m != null) for (Map.Entry<Long, Long> e : m.entrySet()) {
            long k = e.getKey();
            int x = (int) (k << 37 >> 37), z = (int) (k << 10 >> 37);
            if (x >> 4 == c.getX() && z >> 4 == c.getZ()) {
                int y = (int) (k >>> 54) - 64;
                out.add((long) local(c.getWorld().getBlockAt(x, y, z)));
                out.add(e.getValue());
            }
        }
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (out.isEmpty()) pdc.remove(lanternBlocks);
        else pdc.set(lanternBlocks, PersistentDataType.LONG_ARRAY, out.stream().mapToLong(Long::longValue).toArray());
    }

    private void readLanterns(Chunk c) {
        long[] a = c.getPersistentDataContainer().get(lanternBlocks, PersistentDataType.LONG_ARRAY);
        if (a == null) return;
        Map<Long, Long> m = lanterns.computeIfAbsent(c.getWorld().getName(), k -> new HashMap<>());
        for (int i = 0; i + 1 < a.length; i += 2) {
            int v = (int) a[i];
            int x = (c.getX() << 4) + ((v >> 16) & 15), z = (c.getZ() << 4) + ((v >> 12) & 15), y = (v & 4095) - 2048;
            Block b = c.getWorld().getBlockAt(x, y, z);
            if (b.getType() == Material.SOUL_LANTERN) m.put(key(b), a[i + 1]);
        }
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) { readLanterns(e.getChunk()); }

    /** A crash mid-Stillness: those mobs were saved frozen. */
    @EventHandler
    public void onEntities(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity en : e.getEntities())
            if (en.getScoreboardTags().contains(STILLED) && en instanceof Mob m) { m.setAI(true); m.removeScoreboardTag(STILLED); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceLantern(BlockPlaceEvent e) {
        if (!isLantern(e.getItemInHand())) return;
        Block b = e.getBlockPlaced();
        lanterns.computeIfAbsent(b.getWorld().getName(), k -> new HashMap<>())
                .put(key(b), System.currentTimeMillis() + (long) (cfg("lantern.start-minutes", 10) * 60000));
        save(b.getChunk());
        e.getPlayer().sendActionBar(Component.text("The Lantern of Sanity burns. 10 minutes of fuel.", NamedTextColor.AQUA));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakLantern(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SOUL_LANTERN) return;
        Map<Long, Long> m = lanterns.get(b.getWorld().getName());
        if (m == null || m.remove(key(b)) == null) return;
        save(b.getChunk());
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), lantern(1));
    }

    /** Feed it: amethyst shard +10 min, glowstone dust +5 min (max 4 hours). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFeed(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.SOUL_LANTERN) return;
        Map<Long, Long> m = lanterns.get(b.getWorld().getName());
        if (m == null || !m.containsKey(key(b))) return;
        ItemStack hand = e.getItem();
        long add = hand == null ? 0 : hand.getType() == Material.AMETHYST_SHARD ? 600_000L : hand.getType() == Material.GLOWSTONE_DUST ? 300_000L : 0;
        long now = System.currentTimeMillis(), left = Math.max(0, m.get(key(b)) - now);
        e.setCancelled(true);
        if (add == 0) { e.getPlayer().sendActionBar(Component.text("Fuel: " + (left / 60000) + " min left", NamedTextColor.AQUA)); return; }
        long max = (long) (cfg("lantern.max-hours", 4) * 3_600_000L);
        if (left >= max) { e.getPlayer().sendActionBar(Component.text("It's burning as bright as it can.", NamedTextColor.AQUA)); return; }
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        long until = now + Math.min(max, left + add);
        m.put(key(b), until);
        save(b.getChunk());
        b.getWorld().playSound(b.getLocation(), "minecraft:block.amethyst_block.chime", SoundCategory.BLOCKS, 1f, 1.2f);
        e.getPlayer().sendActionBar(Component.text("Fuel: " + ((until - now) / 60000) + " min left", NamedTextColor.AQUA));
    }

    // ================================================================= helpers

    private Location behind(Player p, double min, double max) {
        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) return null;
        back.normalize().multiply(-1);
        for (int i = 0; i < 10; i++) {
            Vector d = back.clone().rotateAroundY((rnd.nextDouble() - 0.5) * 1.4).multiply(min + rnd.nextDouble() * (max - min));
            Location f = floorAt(p.getLocation().add(d), p.getLocation().getBlockY() + 4);
            if (f == null) f = floorAt(p.getLocation().add(d), p.getLocation().getBlockY() - 4);
            if (f != null && Farlands.in(f)) return f;
        }
        return null;
    }

    private Location ground(Player p, double min, double max) {
        World w = p.getWorld();
        for (int i = 0; i < 14; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(a) * r), z = (int) Math.floor(p.getZ() + Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = p.getLocation().getBlockY() + 10; y >= p.getLocation().getBlockY() - 10; y--) {
                Block f = w.getBlockAt(x, y - 1, z), b = w.getBlockAt(x, y, z), h = w.getBlockAt(x, y + 1, z);
                if (f.getType().isSolid() && b.getType().isAir() && h.getType().isAir()) {
                    Location l = new Location(w, x + 0.5, y, z + 0.5);
                    if (Farlands.in(l)) return l;
                    break;
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (still.remove(id) != null) unfreeze(id);
        if (wrong.remove(id)) unwrong(e.getPlayer());
        rotted.remove(id);
        inLongNight.remove(id);
        sleepingSince.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        try { e.getPlayer().setWorldBorder(null); } catch (RuntimeException | LinkageError ignored) { }
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(frozen.keySet())) unfreeze(id);
        still.clear();
        for (UUID id : wrong) { Player p = Bukkit.getPlayer(id); if (p != null) unwrong(p); }
        wrong.clear();
        for (UUID u : faceless.keySet()) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        faceless.clear();
        for (Map.Entry<UUID, List<Location>> e : new ArrayList<>(rotted.entrySet())) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null) unrot(p, new ArrayList<>(e.getValue())); }
    }

    String status() {
        return "Long Night " + (longNight() ? "ON (" + (longNightUntil - tick) / 20 + " s)" : "off") + ", Echo Storm " + (far().echoStorm() ? "ON" : "off")
                + ", " + still.size() + " in the Stillness, " + faceless.size() + " Faceless, " + wrong.size() + " where it's wrong";
    }
}
