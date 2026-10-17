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
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Three sea events (Deep 1.9.0), one at a time, never during the Bloom or the Acid Rain's own sky:
 *  - THE ABYSSAL ECLIPSE (`tides.eclipse.*`): for 5 minutes the dark climbs to the surface. Ocean.darkZone reads
 *    `eclipse`: the whole Dark Ocean is pitch black (a Lantern of Souls / glowing creatures still light it), and the
 *    Abyss's creatures rise near everyone in the Sunlit Sea every 15 s. Index stat `abyssal_eclipses`.
 *  - THE RED TIDE (`tides.red-tide.*`): 10 minutes of blooming red algae in the Sunlit Sea: its water poisons you (every
 *    2 s; the Abyssal Diving Suit or Water Breathing + Fire Resistance keep it off), red murk round you, and every
 *    creature in it goes mad (they turn on anyone within 16). Index stat `red_tides`.
 *  - THE FEEDING FRENZY (`tides.frenzy.*`): something big dying in the open sea (a creature of 150+ health, the Great
 *    Whale, a mini boss) has `chance` 50% to draw a frenzy: 4-6 sharks (by zone) circle in, mad, Speed II, for 60 s.
 * Eclipse and Red Tide roll every `check-minutes` (15) with someone in the Dark Ocean; `/deep eclipse|redtide|frenzy`.
 */
final class Tides {
    static final List<String> RISERS = List.of("gulper", "viperfang", "ghostshark", "vampsquid", "swallower", "chainlight", "watcher", "isopod");
    static final List<String> SUNLIT_SHARKS = List.of("fangstalker", "sharkquid", "hammerjaw", "burrowshark");
    static final List<String> DEEP_SHARKS = List.of("ghostshark", "gulper", "viperfang");
    final FaultlineDeep pl;
    final Random random = new Random();
    String active;       // "eclipse", "redtide" or null
    long endsAt, frenzyCd;
    BossBar bar;
    int secs;

    Tides(FaultlineDeep pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("tides." + k, def); }

    boolean eclipse() { return "eclipse".equals(active); }

    boolean redTide() { return "redtide".equals(active); }

    boolean sunlit(Player p) { return pl.zone(p.getLocation()) == DeepGenerator.Zone.SUNLIT && !Market.dry(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ()); }

    void tick() {
        secs++;
        World w = pl.world;
        if (w == null) return;
        if (active == null) {
            if (!pl.getConfig().getBoolean("tides.enabled", true) || secs % (int) Math.max(60, cfg("check-minutes", 15) * 60) != 0) return;
            if (pl.bloom != null && pl.bloom.active || pl.acidRain != null && pl.acidRain.active) return;
            boolean anyone = w.getPlayers().stream().anyMatch(DeepMobs::fair);
            if (!anyone) return;
            double r = random.nextDouble();
            if (r < cfg("eclipse.chance", 0.03)) start("eclipse");
            else if (r < cfg("eclipse.chance", 0.03) + cfg("red-tide.chance", 0.05)) start("redtide");
            return;
        }
        long left = endsAt - System.currentTimeMillis();
        if (left <= 0) { stop(); return; }
        bar.setProgress(Math.max(0, Math.min(1, left / (minutes(active) * 60_000))));
        for (Player p : w.getPlayers()) {
            bar.addPlayer(p);
            if (eclipse()) eclipseTick(p);
            else redTideTick(p);
        }
    }

    double minutes(String what) { return what.equals("eclipse") ? cfg("eclipse.minutes", 5) : cfg("red-tide.minutes", 10); }

    void eclipseTick(Player p) {
        Location l = p.getLocation();
        p.spawnParticle(Particle.SQUID_INK, l.clone().add(0, 1, 0), 6, 4, 3, 4, 0.01);
        if (secs % 15 == 0 && DeepMobs.fair(p) && sunlit(p) && pl.mobs != null) {
            Location at = pl.mobs.spawnSpot(l);
            DeepMobs.Kind k = DeepMobs.KINDS.get(RISERS.get(random.nextInt(RISERS.size())));
            if (at != null && k != null) pl.mobs.spawn(k, at);
        }
    }

    void redTideTick(Player p) {
        if (!sunlit(p)) return;
        Location l = p.getLocation();
        for (int i = 0; i < 18; i++) {
            Location c = l.clone().add(random.nextGaussian() * 6, random.nextGaussian() * 3, random.nextGaussian() * 6);
            if (c.getBlock().getType() != Material.WATER) continue;
            Color col = random.nextBoolean() ? Color.fromRGB(150, 20, 25) : Color.fromRGB(200, 60, 40);
            p.spawnParticle(Particle.DUST, c, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.6f));
        }
        if (secs % 2 == 0 && DeepMobs.fair(p) && p.isInWater() && !safe(p)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, true, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, true, false, false));
        }
        if (secs % 3 == 0 && DeepMobs.fair(p) && pl.mobs != null) { // the creatures go mad
            for (DeepMobs.Beast m : new ArrayList<>(pl.mobs.live.values())) {
                if (m.k.temper() == DeepMobs.Temper.PASSIVE || m.bar != null || m.angry() || !m.body.getWorld().equals(p.getWorld())) continue;
                if (m.body.getLocation().distanceSquared(l) < 16 * 16) pl.mobs.anger(m, p, 200);
            }
        }
    }

    boolean safe(Player p) {
        if (pl.ocean != null && pl.ocean.suit(p)) return true;
        return p.hasPotionEffect(PotionEffectType.WATER_BREATHING) && p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }

    boolean start(String what) {
        if (active != null || pl.world == null) return false;
        active = what;
        endsAt = System.currentTimeMillis() + (long) (minutes(what) * 60_000);
        boolean ecl = what.equals("eclipse");
        bar = Bukkit.createBossBar(ecl ? ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Abyssal Eclipse" : ChatColor.RED + "" + ChatColor.BOLD + "The Red Tide",
                ecl ? BarColor.PURPLE : BarColor.RED, BarStyle.SEGMENTED_10);
        for (Player p : pl.world.getPlayers()) {
            if (ecl) {
                p.sendTitle(ChatColor.DARK_PURPLE + "The Abyssal Eclipse", ChatColor.GRAY + "the dark rises to the surface", 10, 60, 20);
                p.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Abyssal Eclipse! " + ChatColor.GRAY + "For " + (int) minutes(what)
                        + " minutes the whole Dark Ocean is pitch black, and the things of the Abyss swim up into the Sunlit Sea. Bring a light.");
                p.playSound(p, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.AMBIENT, 0.8f, 0.6f);
                pl.console("index stat " + p.getName() + " abyssal_eclipses 1");
            } else {
                p.sendTitle(ChatColor.RED + "The Red Tide", ChatColor.GRAY + "the Sunlit Sea turns to blood", 10, 60, 20);
                p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "The Red Tide! " + ChatColor.GRAY + "For " + (int) minutes(what)
                        + " minutes the Sunlit Sea's water is poison (the Abyssal Diving Suit, or Water Breathing with Fire Resistance, keeps it off), and every creature in it goes mad.");
                p.playSound(p, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE, SoundCategory.AMBIENT, 1f, 0.5f);
                pl.console("index stat " + p.getName() + " red_tides 1");
            }
        }
        return true;
    }

    void stop() {
        if (active == null) return;
        String was = active;
        active = null;
        if (bar != null) bar.removeAll();
        bar = null;
        if (pl.world != null) for (Player p : pl.world.getPlayers())
            p.sendActionBar(ChatColor.GRAY + (was.equals("eclipse") ? "The eclipse passes. Light comes back to the sea." : "The red tide thins and drifts away."));
    }

    // =========================================================================================== the feeding frenzy
    /** Something big died here (DeepMobs.onDeath / FaultlineBosses): maybe a frenzy. */
    void bigDeath(Location at, double health) {
        if (pl.world == null || !at.getWorld().equals(pl.world) || health < cfg("frenzy.min-health", 150)) return;
        if (System.currentTimeMillis() < frenzyCd || random.nextDouble() >= cfg("frenzy.chance", 0.5)) return;
        if (Market.dry(at.getX(), at.getY(), at.getZ()) || at.getBlock().getType() != Material.WATER) return;
        frenzy(at);
    }

    boolean frenzy(Location at) {
        if (pl.mobs == null) return false;
        frenzyCd = System.currentTimeMillis() + (long) (cfg("frenzy.cooldown-seconds", 120) * 1000);
        DeepGenerator.Zone z = pl.zone(at);
        List<String> pool = z == DeepGenerator.Zone.SUNLIT ? SUNLIT_SHARKS : DEEP_SHARKS;
        int n = 4 + random.nextInt(3);
        List<DeepMobs.Beast> sharks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location c = at.clone().add(Math.cos(a) * 14, random.nextInt(5) - 2, Math.sin(a) * 14);
            if (c.getBlock().getType() != Material.WATER) c = at.clone().add(0, 2, 0);
            DeepMobs.Kind k = DeepMobs.KINDS.get(pool.get(random.nextInt(pool.size())));
            DeepMobs.Beast b = k == null ? null : pl.mobs.spawn(k, c);
            if (b == null) continue;
            b.body.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1200, 1, true, false));
            sharks.add(b);
        }
        at.getWorld().spawnParticle(Particle.DUST, at, 60, 3, 2, 3, 0, new Particle.DustOptions(Color.fromRGB(140, 10, 10), 2.2f));
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 48 * 48) {
            p.sendTitle("", ChatColor.DARK_RED + "Blood in the water... " + ChatColor.GRAY + "a feeding frenzy!", 5, 40, 10);
            p.playSound(at, Sound.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 1f, 0.6f);
        }
        // they go for whoever's nearest, for a minute
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            t[0] += 20;
            if (t[0] > cfg("frenzy.seconds", 60) * 20) { task.cancel(); return; }
            for (DeepMobs.Beast b : sharks) {
                if (!b.body.isValid()) continue;
                Player near = null; double best = 24 * 24;
                for (Player p : b.body.getWorld().getPlayers()) {
                    if (!DeepMobs.fair(p)) continue;
                    double d = p.getLocation().distanceSquared(b.body.getLocation());
                    if (d < best) { best = d; near = p; }
                }
                if (near != null) pl.mobs.anger(b, near, 60);
                b.body.getWorld().spawnParticle(Particle.DUST, b.body.getLocation(), 3, 0.4, 0.3, 0.4, 0, new Particle.DustOptions(Color.fromRGB(140, 10, 10), 1.2f));
            }
        }, 20L, 20L);
        return !sharks.isEmpty();
    }
}
