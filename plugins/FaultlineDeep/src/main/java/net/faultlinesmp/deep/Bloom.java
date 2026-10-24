package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

/**
 * THE BIOLUMINESCENT BLOOM (Deep 1.8.0): a rare night of light in the deep. Every `bloom.check-minutes` (10), with someone
 * in the Abyss, the Crust's caves or the Trench, a `bloom.chance` (3%) it starts: for `bloom.minutes` (5) the darkness
 * lifts everywhere down there (Ocean.dark reads `active`), the water glows round everyone, and the deep's glowing
 * creatures come out (one more near each of them every 15 s: Lampsquids, Dumbo Octopi, Chainlights, Jellywings, Lumen
 * Bells, Crush Jellies, Anglerkings in the Trench). A good time to mine and explore. `/deep bloom [start|stop]`.
 */
final class Bloom {
    static final List<String> GLOWERS = List.of("lampsquid", "dumbo", "chainlight", "jellywing", "lumenbell", "watcher");
    static final List<String> TRENCH_GLOWERS = List.of("crushjelly", "anglerking", "hadalsnail");
    final FaultlineDeep pl;
    final Random random = new Random();
    boolean active;
    long endsAt;
    BossBar bar;
    int secs;

    Bloom(FaultlineDeep pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("bloom." + k, def); }

    boolean deep(Player p) {
        DeepGenerator.Zone z = pl.zone(p.getLocation());
        return z == DeepGenerator.Zone.ABYSS || z == DeepGenerator.Zone.CAVES || z == DeepGenerator.Zone.TRENCH;
    }

    void tick() {
        secs++;
        World w = pl.world;
        if (w == null || !pl.getConfig().getBoolean("bloom.enabled", true)) { if (active) stop(); return; }
        if (!active) {
            if (secs % (int) Math.max(1, cfg("check-minutes", 10) * 60) != 0) return;
            boolean anyone = false;
            for (Player p : w.getPlayers()) if (DeepMobs.fair(p) && deep(p)) { anyone = true; break; }
            double season = pl.glimmer != null && pl.glimmer.active() ? pl.getConfig().getDouble("glimmertide.bloom-multiplier", 3) : 1; // 1.12.0: the Glimmertide
            if (anyone && random.nextDouble() < cfg("chance", 0.03) * season) start();
            return;
        }
        if (System.currentTimeMillis() >= endsAt) { stop(); return; }
        bar.setProgress(Math.max(0, Math.min(1, (endsAt - System.currentTimeMillis()) / (cfg("minutes", 5) * 60_000))));
        for (Player p : w.getPlayers()) {
            if (!deep(p)) { bar.removePlayer(p); continue; }
            bar.addPlayer(p);
            Location l = p.getLocation();
            for (int i = 0; i < 14; i++) {
                Location c = l.clone().add(random.nextGaussian() * 7, random.nextGaussian() * 4, random.nextGaussian() * 7);
                Color col = switch (random.nextInt(4)) { case 0 -> Color.fromRGB(90, 255, 230); case 1 -> Color.fromRGB(190, 110, 255); case 2 -> Color.fromRGB(120, 180, 255); default -> Color.fromRGB(150, 255, 160); };
                p.spawnParticle(Particle.DUST, c, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f));
            }
            p.spawnParticle(Particle.GLOW, l.clone().add(0, 1, 0), 6, 5, 3, 5, 0);
            if (secs % 15 == 0 && DeepMobs.fair(p) && pl.mobs != null) {
                boolean trench = pl.zone(l) == DeepGenerator.Zone.TRENCH;
                List<String> pool = trench ? TRENCH_GLOWERS : GLOWERS;
                Location at = pl.mobs.spawnSpot(l);
                DeepMobs.Kind k = DeepMobs.KINDS.get(pool.get(random.nextInt(pool.size())));
                if (at != null && k != null) pl.mobs.spawn(k, at);
            }
        }
    }

    boolean start() {
        if (active || pl.world == null) return false;
        active = true;
        endsAt = System.currentTimeMillis() + (long) (cfg("minutes", 5) * 60_000);
        bar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "The Bioluminescent Bloom", BarColor.BLUE, BarStyle.SOLID);
        for (Player p : pl.world.getPlayers()) {
            p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "The Bioluminescent Bloom! " + ChatColor.GRAY + "The deep lights up for " + (int) cfg("minutes", 5)
                    + " minutes: no darkness in the Abyss, the caves or the Trench, and its glowing creatures are out.");
            p.playSound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 1f, 0.6f);
            if (deep(p)) pl.console("index stat " + p.getName() + " blooms 1");
        }
        return true;
    }

    void stop() {
        if (!active) return;
        active = false;
        if (bar != null) bar.removeAll();
        bar = null;
        if (pl.world != null) for (Player p : pl.world.getPlayers()) if (deep(p)) p.sendActionBar(Component.text("The bloom fades. The dark comes back.", NamedTextColor.DARK_GRAY));
    }
}
