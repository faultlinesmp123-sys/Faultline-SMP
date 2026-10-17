package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Living in the Dark Ocean:
 *  - MUSIC BY DEPTH: y 35 and up = "Theme of the Sunken Sea" (faultline:deep.sunken), below 35 = "Hadopelagic Pressure"
 *    (faultline:deep.abyss), looped, on the MUSIC channel so a boss's track (which stops that channel) takes over, and it
 *    comes back when the boss music is over (FaultlineBosses.hearingBossMusic, by reflection). The tracks are in
 *    FaultlineDeepMusic.zip (a second server resource pack) and FaultlineDeep.mcpack for Bedrock.
 *  - THE FISH GET YOUR THINGS: what you drop when you die here is eaten (gone) one minute later unless someone picks it up.
 *  - TWO EVENTS, like the real deep sea:
 *      PRESSURE SURGE: the deep water presses in. Below y 20 you're crushed (damage every 2 s, more the deeper you go,
 *      mining fatigue, the glass-creak sounds) unless you wear the Abyssal Diving Suit or have Conduit Power.
 *      UNDERTOW: a rip current sweeps across the Sunlit Sea in one direction, dragging swimmers and boats with it.
 *    One at a time, every events.every-minutes (45-90) while someone's down here; /deep event <pressure|undertow|stop>.
 *  - Respawning: back at the Dark Ocean's entry (0, 300, 0) unless you have a bed or anchor set (death.respawn).
 */
final class Ocean implements Listener {

    final FaultlineDeep pl;
    final Random random = new Random();
    final NamespacedKey drownedKey;
    private final Map<UUID, String> track = new HashMap<>();
    private final Map<UUID, Long> trackAt = new HashMap<>();
    /** Death drops waiting to be eaten: the item and when. */
    private final List<Object[]> eaten = new ArrayList<>();
    String event;
    long eventEnds, nextEvent;
    Vector current;
    BossBar bar;
    int ticks;

    Ocean(FaultlineDeep pl) {
        this.pl = pl;
        drownedKey = new NamespacedKey(pl, "deep_death_drop");
        nextEvent = System.currentTimeMillis() + (long) (gap() * 60000);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 2L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble(path, def); }

    double gap() { double lo = cfg("events.every-minutes-min", 45), hi = cfg("events.every-minutes-max", 90); return lo + random.nextDouble() * Math.max(0, hi - lo); }

    boolean inDeep(Player p) { return pl.world != null && p.getWorld().equals(pl.world); }

    void tick() {
        ticks += 2;
        if (pl.world == null) return;
        if (ticks % 40 == 0) music();
        if (ticks % 20 == 0) eat();
        if (ticks % 40 == 0) sulphur();
        if (ticks % 40 == 0) trench();
        if (ticks % 4 == 0) greenRain();
        if (ticks % 20 == 0) dark();
        holdDrops();
        events();
    }

    // =========================================================================================== the Sulphur Sea (1.4.0)
    private final java.util.Set<UUID> inSulphur = new java.util.HashSet<>();

    /**
     * Every 2 s: the Sulphur Sea's water is acid. Swimming in it poisons you unless you wear Abyssal Diving Gear or the
     * Suit (FaultlineItems' diving tier 3+, read from the player's data). A title the first time you come in.
     */
    void sulphur() {
        NamespacedKey tierKey = NamespacedKey.fromString("faultlineitems:diving_tier");
        for (Player p : pl.world.getPlayers()) {
            Location l = p.getLocation();
            boolean in = pl.generator.sulphur(pl.world.getSeed(), l.getBlockX(), l.getBlockZ()) > 0.35 && l.getY() > 30;
            if (in && inSulphur.add(p.getUniqueId())) {
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text("The Sulphur Sea", NamedTextColor.GREEN),
                        Component.text("The water burns. Something huge moves under it.", NamedTextColor.GRAY)));
                p.playSound(l, Sound.AMBIENT_UNDERWATER_ENTER, SoundCategory.AMBIENT, 1f, 0.6f);
            } else if (!in) inSulphur.remove(p.getUniqueId());
            // it always rains over the Sulphur Sea (only for the players in it: their own weather), and stops when they leave
            if (in && pl.getConfig().getBoolean("sulphur.always-raining", true)) {
                if (p.getPlayerWeather() != org.bukkit.WeatherType.DOWNFALL) p.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
                rained.add(p.getUniqueId());
            } else if (rained.remove(p.getUniqueId())) p.resetPlayerWeather();
            boolean wet = p.isInWater() || l.getBlock().getType() == org.bukkit.Material.WATER;
            if (!in || !wet || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!pl.getConfig().getBoolean("sulphur.acid-water", true)) continue;
            Integer tier = tierKey == null ? null : p.getPersistentDataContainer().get(tierKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (tier != null && tier >= (int) cfg("sulphur.safe-diving-tier", 3)) continue;
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.POISON, 60, 0, false, true, true));
            if (ticks % 200 == 0) p.sendActionBar(Component.text("The Sulphur Sea's water burns! ", NamedTextColor.GREEN)
                    .append(Component.text("(Abyssal Diving Gear keeps it out)", NamedTextColor.GRAY)));
        }
    }

    private final java.util.Set<UUID> rained = new java.util.HashSet<>();

    // =========================================================================================== the Trench (1.8.0)
    final java.util.Set<UUID> inTrench = new java.util.HashSet<>();

    /**
     * Every 2 s: HADAL PRESSURE. Anywhere in the Trench below `trench.pressure.below-y` (-10) the deep crushes you, all the
     * time (not an event): Conduit Power doesn't help down here. The Abyssal Diving Suit halves it; the suit AND a Hadal
     * Diving Helmet (FaultlineItems: a helmet tagged faultlineitems:oxygen_helmet = tier, 3+) keep it out. A title the
     * first time you go in.
     */
    void trench() {
        for (Player p : pl.world.getPlayers()) {
            Location l = p.getLocation();
            boolean in = pl.zone(l) == DeepGenerator.Zone.TRENCH;
            if (in && inTrench.add(p.getUniqueId())) {
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text("The Trench", NamedTextColor.DARK_PURPLE),
                        Component.text("No light reaches here. Something vast sleeps at the bottom.", NamedTextColor.GRAY)));
                p.playSound(l, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 1f, 0.5f);
            } else if (!in) inTrench.remove(p.getUniqueId());
            if (in) maw(p, l);
            if (!in || l.getY() >= cfg("trench.pressure.below-y", -10)) continue;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (!pl.getConfig().getBoolean("trench.pressure.enabled", true)) continue;
            if (p.getScoreboardTags().contains("faultline_boss_rush")) continue; // the Deep Boss Rush brings its own team down here
            if (pl.subs != null && pl.subs.riding(p)) continue;                 // inside a Submersible (its hull takes the depth)
            boolean suit = suit(p);
            int helmet = helmetTier(p);
            if (suit && helmet >= (int) cfg("trench.pressure.helmet-tier", 3)) {
                if (ticks % 400 == 0) p.sendActionBar(Component.text("Your suit and helmet hold against the Trench.", NamedTextColor.DARK_AQUA));
                continue;
            }
            double dmg = cfg("trench.pressure.damage", 3) * (suit ? 0.5 : 1);
            p.damage(dmg);
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 1, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0, true, false, true));
            p.playSound(l, Sound.BLOCK_GLASS_HIT, SoundCategory.AMBIENT, 1f, 0.4f);
            p.sendActionBar(Component.text("Hadal pressure! ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text(suit ? "Your suit helps, but you need a Hadal Dive Helmet too." : "You need the Abyssal Diving Suit and a Hadal Dive Helmet.", NamedTextColor.GRAY)));
        }
    }

    private final Map<UUID, Long> mawTold = new HashMap<>();
    private long titanTried;

    /**
     * THE MAW: someone in survival who has beaten all six of the Dark Ocean's bosses (the Index) swims down into the
     * Titan's lair = FaultlineBosses wakes the Abyssal Titan (FaultlineBosses.wakeTitan, by reflection). Anyone else is
     * told what it wants, at most every 5 minutes.
     */
    void maw(Player p, Location l) {
        if (Math.hypot(l.getX() - Trench.LX, l.getZ() - Trench.LZ) > cfg("trench.titan.wake-radius", 30) || l.getY() > Trench.BOTTOM + 30) return;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        if (!pl.getConfig().getBoolean("trench.titan.enabled", true)) return;
        if (!sixBeaten(p)) {
            long now = System.currentTimeMillis();
            if (now - mawTold.getOrDefault(p.getUniqueId(), 0L) < 300_000) return;
            mawTold.put(p.getUniqueId(), now);
            p.sendMessage(net.kyori.adventure.text.Component.text("Something vast sleeps under the floor of the Maw. It doesn't stir for you... yet. ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text("(Beat all six of the Dark Ocean's bosses to wake the Abyssal Titan.)", NamedTextColor.GRAY)));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - titanTried < 10_000) return; // (once out, Bosses answers true without summoning again)
        titanTried = now;
        try {
            var bp = Bukkit.getPluginManager().getPlugin("FaultlineBosses");
            if (bp == null) return;
            Class<?> c = Class.forName("net.faultlinesmp.bosses.FaultlineBosses", true, bp.getClass().getClassLoader());
            c.getMethod("wakeTitan", Location.class, Player.class).invoke(null, new Location(l.getWorld(), Trench.LX + 0.5, Trench.BOTTOM + 3, Trench.LZ + 0.5), p);
        } catch (Throwable e) { pl.getLogger().warning("Couldn't wake the Abyssal Titan: " + e); }
    }

    /** Has this player beaten all six sea bosses? (the Index; without the Index: no one has) */
    boolean sixBeaten(Player p) {
        if (p.hasPermission("faultlinedeep.bypass")) return true;
        try {
            Class<?> c = Class.forName("net.faultlinesmp.index.FaultlineIndex", true, Bukkit.getPluginManager().getPlugin("FaultlineIndex").getClass().getClassLoader());
            var m = c.getMethod("defeated", UUID.class, String.class);
            for (String id : pl.getConfig().getStringList("trench.titan.needs").isEmpty()
                    ? java.util.List.of("brine_scourge", "umbral_leviathan", "coralia", "old_inkjaw", "grandmaw_clam", "the_lament")
                    : pl.getConfig().getStringList("trench.titan.needs"))
                if (!(boolean) m.invoke(null, p.getUniqueId(), id)) return false;
            return true;
        } catch (Throwable e) { return false; }
    }

    /** The worn diving helmet's tier (FaultlineItems: faultlineitems:oxygen_helmet on the helmet), 0 = none. */
    static int helmetTier(Player p) {
        var h = p.getInventory().getHelmet();
        if (h == null || !h.hasItemMeta()) return 0;
        NamespacedKey k = NamespacedKey.fromString("faultlineitems:oxygen_helmet");
        Integer t = k == null ? null : h.getItemMeta().getPersistentDataContainer().get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    /**
     * The Sulphur Sea's rain is GREEN: falling drops of lime acid round every player in it who's under open sky (sent to
     * that player only, every 4 ticks), on top of the real rain. Bedrock sees the same particles.
     */
    void greenRain() {
        if (inSulphur.isEmpty() || !pl.getConfig().getBoolean("sulphur.always-raining", true)) return;
        int n = (int) cfg("sulphur.green-rain-particles", 40);
        if (pl.acidRain != null && pl.acidRain.active) n *= 3; // the Acid Rain: a downpour
        org.bukkit.block.data.BlockData lime = org.bukkit.Material.LIME_CONCRETE_POWDER.createBlockData();
        org.bukkit.block.data.BlockData yellow = org.bukkit.Material.YELLOW_CONCRETE_POWDER.createBlockData();
        for (UUID id : inSulphur) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.getWorld().equals(pl.world)) continue;
            Location l = p.getLocation();
            if (pl.world.getHighestBlockYAt(l, org.bukkit.HeightMap.MOTION_BLOCKING) > l.getY() + 2) continue; // under a roof
            for (int i = 0; i < n; i++) {
                double x = l.getX() + (random.nextDouble() - 0.5) * 22, z = l.getZ() + (random.nextDouble() - 0.5) * 22;
                double y = l.getY() + 6 + random.nextDouble() * 8;
                p.spawnParticle(Particle.FALLING_DUST, x, y, z, 1, 0, 0, 0, 0, random.nextInt(4) == 0 ? yellow : lime);
            }
            if (random.nextInt(3) == 0)
                p.spawnParticle(Particle.DUST, l.getX(), l.getY() + 4, l.getZ(), 12, 6, 3, 6, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(150, 220, 60), 1.2f));
        }
    }

    // =========================================================================================== the dark (1.6.0)
    /** Players the Abyss is darkening right now (FaultlineItems reads the "faultline_abyss_dark" metadata: no night vision). */
    final java.util.Set<UUID> darkened = new java.util.HashSet<>();
    /** Players a Glowgrub / Dumbo Octopus is lighting up, until which Ocean tick (DeepMobs writes it). */
    final Map<UUID, Long> glowLit = new HashMap<>();
    static final String DARK_META = "faultline_abyss_dark";

    /** The zones that are pitch black (`darkness.zones`, default the Abyss and the crust's caves). */
    boolean darkZone(Location l) {
        if (Market.dry(l.getX(), l.getY(), l.getZ())) return false; // the Pit and the lift: lit rooms
        DeepGenerator.Zone z = pl.zone(l);
        if (z == DeepGenerator.Zone.TRENCH) return true; // (1.8.0: always, whatever an older config's list says)
        return pl.getConfig().getStringList("darkness.zones").isEmpty() ? z == DeepGenerator.Zone.ABYSS || z == DeepGenerator.Zone.CAVES
                : pl.getConfig().getStringList("darkness.zones").contains(z.name());
    }

    /**
     * Every second: down in the Abyss there is NO light. Darkness (hidden: no icon, no particles), which also cancels the
     * client's brightness slider, and every Night Vision is taken away (potions, the Abyssal Diving Gear, the Mining Helmet:
     * FaultlineItems holds off while the metadata is set). The only light: a Lantern of Souls held (it burns the dark
     * away), a Glowgrub close by, glowing creatures and the blocks that give light.
     */
    void dark() {
        boolean on = pl.getConfig().getBoolean("darkness.enabled", true);
        long now = ticks;
        for (Player p : pl.world.getPlayers()) {
            UUID id = p.getUniqueId();
            boolean want = on && p.getGameMode() != GameMode.SPECTATOR
                    && (p.getGameMode() != GameMode.CREATIVE || pl.getConfig().getBoolean("darkness.creative", true))
                    && darkZone(p.getLocation());
            if (!want) { if (darkened.remove(id)) undark(p); continue; }
            if (darkened.add(id)) {
                p.setMetadata(DARK_META, new org.bukkit.metadata.FixedMetadataValue(pl, true));
                if (!pl.getConfig().getString("darkness.message", "x").isEmpty())
                    p.sendActionBar(Component.text(pl.getConfig().getString("darkness.message", "The Abyss swallows every light."), NamedTextColor.DARK_GRAY));
            }
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION);
            boolean lit = lantern(p) || glowLit.getOrDefault(id, 0L) > now || pl.bloom != null && pl.bloom.active; // (the Bioluminescent Bloom lights it all)
            org.bukkit.potion.PotionEffect d = p.getPotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS);
            if (lit) { if (ours(d)) p.removePotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS); continue; }
            // topped up long before it runs out: re-adding it makes the client fade it in again
            if (d == null || ours(d) && d.getDuration() < 200)
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 600, 0, true, false, false));
        }
        glowLit.values().removeIf(t -> t < now);
    }

    /** Our Darkness: ambient, no particles (a Lampsquid's pulse or a Warden's is a normal one, and is left alone). */
    static boolean ours(org.bukkit.potion.PotionEffect d) { return d != null && d.isAmbient() && !d.hasParticles(); }

    void undark(Player p) {
        p.removeMetadata(DARK_META, pl);
        if (ours(p.getPotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS))) p.removePotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS);
    }

    /** Holding FaultlineItems' Lantern of Souls (PDC faultlineitems:gear = lantern_of_souls). */
    static boolean lantern(Player p) {
        NamespacedKey k = NamespacedKey.fromString("faultlineitems:gear");
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            if (it == null || it.getType().isAir() || !it.hasItemMeta()) continue;
            if ("lantern_of_souls".equals(it.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING))) return true;
        }
        return false;
    }

    // =========================================================================================== music
    void music() {
        if (!pl.getConfig().getBoolean("music.enabled", true)) return;
        long now = System.currentTimeMillis();
        for (Player p : pl.world.getPlayers()) {
            // 4 blocks of give round the line, so swimming along y 35 doesn't flip the two songs every 2 seconds
            double y = p.getLocation().getY(), line = cfg("music.abyss-below-y", 35);
            String cur = track.get(p.getUniqueId());
            String want = "sunken".equals(cur) ? (y < line - 4 ? "abyss" : "sunken") : "abyss".equals(cur) ? (y > line + 4 ? "sunken" : "abyss") : (y >= line ? "sunken" : "abyss");
            if (inSulphur.contains(p.getUniqueId())) want = "acid"; // the Sulphur Sea: "Corrosion" (Theme of the Acid Rain)
            quietVanilla(p); // vanilla's own underwater / background tracks would play over ours (or over a boss's)
            if (bossMusic(p)) { stopMusic(p); continue; } // a boss's music has the floor: ours stops, and waits
            String have = track.get(p.getUniqueId());
            double len = cfg("music." + want + "-seconds", want.equals("sunken") ? 362 : want.equals("acid") ? 105 : 286);
            if (want.equals(have) && now - trackAt.getOrDefault(p.getUniqueId(), 0L) < len * 1000) continue;
            if (have != null) p.stopSound("faultline:deep." + have, SoundCategory.MUSIC);
            p.stopSound(SoundCategory.MUSIC); // vanilla's background tune too
            p.playSound(p, "faultline:deep." + want, SoundCategory.MUSIC, (float) cfg("music.volume", 1.0), 1f);
            track.put(p.getUniqueId(), want);
            trackAt.put(p.getUniqueId(), now);
        }
    }

    /** The vanilla tracks the client could start down here (ocean biomes: the underwater and the general game music). */
    static final List<String> VANILLA = List.of("minecraft:music.under_water", "minecraft:music.game", "minecraft:music.creative",
            "minecraft:music.overworld.deep_dark", "minecraft:music.overworld.lush_caves", "minecraft:music.overworld.dripstone_caves");

    static void quietVanilla(Player p) { for (String k : VANILLA) p.stopSound(k, SoundCategory.MUSIC); }

    void stopMusic(Player p) {
        String have = track.remove(p.getUniqueId());
        if (have != null) p.stopSound("faultline:deep." + have, SoundCategory.MUSIC);
    }

    /** Is a FaultlineBosses fight playing its music to this player? */
    static boolean bossMusic(Player p) {
        try {
            var b = Bukkit.getPluginManager().getPlugin("FaultlineBosses");
            if (b == null || !b.isEnabled()) return false;
            Class<?> c = Class.forName("net.faultlinesmp.bosses.FaultlineBosses", true, b.getClass().getClassLoader());
            return (boolean) c.getMethod("hearingBossMusic", UUID.class).invoke(null, p.getUniqueId());
        } catch (Throwable e) { return false; }
    }

    @EventHandler
    public void onLeaveWorld(PlayerChangedWorldEvent e) {
        if (pl.world == null || !e.getFrom().equals(pl.world)) return;
        stopMusic(e.getPlayer());
        inSulphur.remove(e.getPlayer().getUniqueId());
        if (rained.remove(e.getPlayer().getUniqueId())) e.getPlayer().resetPlayerWeather(); // the acid rain stays down there
        if (darkened.remove(e.getPlayer().getUniqueId())) undark(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        track.remove(id); inSulphur.remove(id);
        if (rained.remove(id)) e.getPlayer().resetPlayerWeather();
        if (darkened.remove(id)) undark(e.getPlayer());
    }

    // =========================================================================================== dying down here
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!inDeep(p) || e.getKeepInventory()) return;
        Location died = p.getLocation();
        if (Market.dry(died.getX(), died.getY(), died.getZ())) return; // the Bubble / the Pit: dry air, your things just drop
        List<ItemStack> drops = new ArrayList<>(e.getDrops());
        e.getDrops().clear();
        Location at = p.getLocation();
        long when = System.currentTimeMillis() + (long) (cfg("death.eaten-after-seconds", 60) * 1000);
        for (ItemStack it : drops) {
            if (it == null || it.getType().isAir()) continue;
            Item item = at.getWorld().dropItemNaturally(at, it);
            item.getPersistentDataContainer().set(drownedKey, PersistentDataType.STRING, p.getUniqueId().toString());
            eaten.add(new Object[]{item, when, p.getUniqueId(), item.getLocation().clone()});
        }
        p.sendMessage(ChatColor.DARK_AQUA + "Your things are sinking where you died. " + ChatColor.RED + "In one minute the fish will have them.");
    }

    /** Items float up in water: the drops are held where you died (they'd drift off toward the surface in the minute). */
    void holdDrops() {
        for (Object[] o : eaten) {
            Item item = (Item) o[0];
            if (!item.isValid()) continue;
            Location spot = (Location) o[3];
            Edition.vel(item, new Vector());
            if (item.getLocation().distanceSquared(spot) > 0.09) item.teleport(spot);
        }
    }

    void eat() {
        long now = System.currentTimeMillis();
        for (Iterator<Object[]> it = eaten.iterator(); it.hasNext(); ) {
            Object[] o = it.next();
            Item item = (Item) o[0];
            if (!item.isValid()) { it.remove(); continue; }
            long when = (long) o[1];
            if (now >= when - 10000 && ticks % 40 == 0) item.getWorld().spawnParticle(Particle.BUBBLE, item.getLocation(), 6, 0.3, 0.3, 0.3, 0.02);
            if (now < when) continue;
            Location l = item.getLocation();
            l.getWorld().spawnParticle(Particle.BUBBLE_POP, l, 12, 0.3, 0.3, 0.3, 0.05);
            l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 0.6f, 1.4f);
            item.remove();
            it.remove();
            Player owner = Bukkit.getPlayer((UUID) o[2]);
            if (owner != null && ticks % 2 == 0) owner.sendActionBar(Component.text("Something down there ate the last of your things.", NamedTextColor.DARK_AQUA));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) { // only players pick them up (no drowned grabbing your sword)
        if (e.getItem().getPersistentDataContainer().has(drownedKey, PersistentDataType.STRING) && !(e.getEntity() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (pl.world == null || !p.getWorld().equals(pl.world)) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        if (!pl.getConfig().getString("death.respawn", "entry").equalsIgnoreCase("entry")) return;
        Location died = p.getLocation(); // (still where they died)
        if (Market.dry(died.getX(), died.getY(), died.getZ())) { // a fight in the Pit, or a fall in the town: back in front of the Black Market
            int[] at = Town.plotSpawn();
            e.setRespawnLocation(new Location(pl.world, at[0] + 0.5, at[1], at[2] + 0.5, 180, 0));
            return;
        }
        Location house = pl.rep == null ? null : pl.rep.houseSpot(p); // Honored with the Mermaid Den: home to your house there (1.8.0)
        if (house != null) { e.setRespawnLocation(house); return; }
        e.setRespawnLocation(new Location(pl.world, 0.5, DeepGenerator.SEA, 0.5));
    }

    // =========================================================================================== events
    void events() {
        long now = System.currentTimeMillis();
        boolean anyone = !pl.world.getPlayers().isEmpty();
        if (event == null) {
            if (anyone && now >= nextEvent && pl.getConfig().getBoolean("events.enabled", true))
                start(random.nextBoolean() ? "pressure" : "undertow");
            return;
        }
        if (now >= eventEnds) { stop(true); return; }
        if (event.equals("pressure")) pressureTick(); else undertowTick();
        if (bar != null && ticks % 20 == 0) {
            double total = cfg("events." + event + ".minutes", event.equals("pressure") ? 4 : 3) * 60000;
            bar.setProgress(Math.max(0, Math.min(1, (eventEnds - now) / total)));
            for (Player p : pl.world.getPlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            for (Player p : new ArrayList<>(bar.getPlayers())) if (!inDeep(p)) bar.removePlayer(p);
        }
    }

    boolean start(String which) {
        if (pl.world == null) return false;
        stop(false);
        event = which;
        eventEnds = System.currentTimeMillis() + (long) (cfg("events." + which + ".minutes", which.equals("pressure") ? 4 : 3) * 60000);
        boolean pressure = which.equals("pressure");
        if (!pressure) {
            double a = random.nextDouble() * Math.PI * 2;
            current = new Vector(Math.cos(a), 0, Math.sin(a));
        }
        bar = Bukkit.createBossBar(pressure ? ChatColor.DARK_RED + "" + ChatColor.BOLD + "PRESSURE SURGE " + ChatColor.GRAY + "(below y " + (int) cfg("events.pressure.below-y", 20) + ")"
                : ChatColor.AQUA + "" + ChatColor.BOLD + "UNDERTOW " + ChatColor.GRAY + "(the current pulls " + compass(current) + ")",
                pressure ? BarColor.RED : BarColor.BLUE, BarStyle.SOLID);
        for (Player p : pl.world.getPlayers()) {
            bar.addPlayer(p);
            p.showTitle(Title.title(Component.text(pressure ? "PRESSURE SURGE" : "UNDERTOW", pressure ? NamedTextColor.DARK_RED : NamedTextColor.AQUA),
                    Component.text(pressure ? "The deep presses in. Get above y " + (int) cfg("events.pressure.below-y", 20) + ", or wear the Abyssal Diving Suit."
                            : "A rip current sweeps the Sunlit Sea " + compass(current) + ".", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), pressure ? Sound.ENTITY_WARDEN_HEARTBEAT : Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, SoundCategory.AMBIENT, 2f, 0.5f);
        }
        return true;
    }

    void stop(boolean natural) {
        if (event == null) return;
        String was = event;
        event = null;
        if (bar != null) bar.removeAll();
        bar = null;
        nextEvent = System.currentTimeMillis() + (long) (gap() * 60000);
        if (natural && pl.world != null) for (Player p : pl.world.getPlayers())
            p.sendActionBar(Component.text(was.equals("pressure") ? "The pressure eases." : "The undertow dies down.", NamedTextColor.GRAY));
    }

    static String compass(Vector v) {
        if (v == null) return "";
        double a = Math.toDegrees(Math.atan2(v.getZ(), v.getX()));
        String[] dirs = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        return dirs[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    void pressureTick() {
        if (ticks % 40 != 0) return;
        double below = cfg("events.pressure.below-y", 20);
        for (Player p : pl.world.getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            double y = p.getLocation().getY();
            if (y >= below) continue;
            Location at = p.getLocation();
            if (Market.dry(at.getX(), at.getY(), at.getZ())) continue; // the Bubble and the Pit hold the deep back
            if (pl.subs != null && pl.subs.riding(p)) continue;         // so does a Submersible's hull (it takes the depth itself)
            if (p.hasPotionEffect(PotionEffectType.CONDUIT_POWER) || suit(p)) {
                if (ticks % 200 == 0) p.sendActionBar(Component.text("Your suit holds against the pressure.", NamedTextColor.DARK_AQUA));
                continue;
            }
            double depth = below - y;
            double dmg = cfg("events.pressure.damage", 1.0) + depth / cfg("events.pressure.blocks-per-extra-damage", 30);
            p.damage(Math.min(cfg("events.pressure.max-damage", 4), dmg));
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 0, true, false, true));
            if (depth > 40) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, true, false, false));
            p.playSound(p.getLocation(), Sound.BLOCK_GLASS_HIT, SoundCategory.AMBIENT, 1f, 0.5f);
            p.sendActionBar(Component.text("The pressure is crushing you! Swim up past y " + (int) below + ".", NamedTextColor.RED));
        }
    }

    /** The Abyssal Diving Suit (FaultlineItems: an accessory or the chestplate, by its tag) shuts the pressure out. */
    boolean suit(Player p) {
        try {
            var items = Bukkit.getPluginManager().getPlugin("FaultlineItems");
            if (items == null) return false;
            Object am = items.getClass().getMethod("getAccessoryManager").invoke(items);
            NamespacedKey k = NamespacedKey.fromString("faultlineitems:abyssal_diving_suit");
            var chest = p.getInventory().getChestplate(); // worn as a chestplate counts too
            if (chest != null && chest.hasItemMeta() && chest.getItemMeta().getPersistentDataContainer().has(k)) return true;
            for (var m : am.getClass().getMethods())
                if (m.getName().equals("hasEquipped") && m.getParameterCount() == 2) {
                    m.setAccessible(true); // AccessoryManager is package-private: without this every call threw (the suit never helped)
                    return (boolean) m.invoke(am, p, k);
                }
        } catch (Throwable ignored) { }
        return false;
    }

    void undertowTick() {
        double pushNow = cfg("events.undertow.push", 0.075);
        for (Player p : pl.world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) continue;
            Location l = p.getLocation();
            if (pl.zone(l) != DeepGenerator.Zone.SUNLIT || !p.isInWater() && !(p.getVehicle() instanceof Boat)) continue;
            Entity mover = p.getVehicle() instanceof Boat b ? b : p;
            Vector v = mover.getVelocity().add(current.clone().multiply(pushNow));
            if (v.length() > 0.9) v.normalize().multiply(0.9);
            Edition.vel(mover, v);
            if (ticks % 10 == 0) p.spawnParticle(Particle.BUBBLE_COLUMN_UP, l.clone().add(current.clone().multiply(-2)), 6, 1.2, 0.8, 1.2, 0.05);
            if (ticks % 6 == 0) l.getWorld().spawnParticle(Particle.CURRENT_DOWN, l.clone().add(current.clone().multiply(3)), 4, 1.5, 0.8, 1.5, 0);
        }
    }

    void shutdown() {
        if (bar != null) bar.removeAll();
        eaten.clear(); // (what's still lying there stays: ordinary items now)
        if (pl.world != null) for (Player p : pl.world.getPlayers()) stopMusic(p);
        for (UUID id : darkened) { Player p = Bukkit.getPlayer(id); if (p != null) undark(p); }
        darkened.clear();
    }
}
