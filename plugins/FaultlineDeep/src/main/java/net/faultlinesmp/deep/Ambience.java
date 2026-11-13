package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * THE DARK OCEAN'S AMBIENCE (Deep 1.17.0): every biome gets its own living water, drawn round each player only they see
 * (so it costs nothing for anyone else, and it works in chunks generated long ago):
 *  - the Sunlit Sea: drifting plankton and sun glints; the Shallows' rising bubbles, the Kelpwood's spores, the Ember
 *    Meadows' embers, the Lantern Groves' fireflies, the Pale Dunes' drifting sand, the Needle Fields' red dust, the Drowned
 *    Peaks' cold snow, the Lumen Reef's violet motes, the Hanging Isles' falling blossom
 *  - the Abyss and the caves: marine snow sinking through the dark, and now and then something bioluminescent blinking
 *  - the Sulphur Sea: acid bubbles and yellow-green haze; the Trench: ash, rising heat and soul sparks; the Den: pink and
 *    cyan sparkles; the Frozen Shelf: snow; the Drowned City: grey silt; the Glass Reef: rainbow glints; the Bone Fields:
 *    bone dust
 *  - and a far-off sound now and then: whale song, the deep's creaks, the Trench's heartbeat
 * `ambience.enabled`, `ambience.density` (1.0) and `ambience.sounds` in the config.
 */
final class Ambience {
    final FaultlineDeep pl;
    final Random random = new Random();
    final Map<UUID, Long> nextSound = new HashMap<>();
    int ticks;

    Ambience(FaultlineDeep pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 5L);
    }

    void tick() {
        ticks++;
        if (pl.world == null || !pl.getConfig().getBoolean("ambience.enabled", true)) return;
        double density = pl.getConfig().getDouble("ambience.density", 1.0);
        long seed = pl.world.getSeed();
        long now = System.currentTimeMillis();
        for (Player p : pl.world.getPlayers()) {
            Location l = p.getLocation();
            if (Halcyon.inside(l.getX(), l.getY(), l.getZ()) || Market.dry(l.getX(), l.getY(), l.getZ())) continue;
            int x = l.getBlockX(), z = l.getBlockZ();
            DeepGenerator.Zone zone = pl.zone(l);
            String region = Regions.at(x, z);
            int n = (int) Math.max(1, Math.round(6 * density));
            if (zone == DeepGenerator.Zone.TRENCH) trench(p, l, n);
            else if (zone == DeepGenerator.Zone.SULPHUR || pl.generator.sulphur(seed, x, z) > 0.35) sulphur(p, l, n);
            else if (zone == DeepGenerator.Zone.ABYSS || zone == DeepGenerator.Zone.CAVES) abyss(p, l, n);
            else if (region != null) region(p, l, n, region);
            else if (MermaidDen.weight(x, z) > 0.4) den(p, l, n);
            else sunlit(p, l, n, Reaches.at(seed, x, z));
            if (pl.getConfig().getBoolean("ambience.sounds", true) && now > nextSound.getOrDefault(p.getUniqueId(), 0L)) {
                nextSound.put(p.getUniqueId(), now + 15000 + random.nextInt(25000));
                sound(p, l, zone, region);
            }
        }
    }

    /** A spot round them (within r, not right in their face). */
    Location around(Location l, double r) {
        double a = random.nextDouble() * Math.PI * 2, d = 2 + random.nextDouble() * (r - 2);
        return l.clone().add(Math.cos(a) * d, (random.nextDouble() - 0.3) * r * 0.6, Math.sin(a) * d);
    }

    static Particle.DustOptions dust(int r, int g, int b, float size) { return new Particle.DustOptions(Color.fromRGB(r, g, b), size); }

    void sunlit(Player p, Location l, int n, Reaches.Kind k) {
        for (int i = 0; i < n; i++) p.spawnParticle(Particle.UNDERWATER, around(l, 10), 2, 0.3, 0.3, 0.3, 0);
        if (l.getY() > DeepGenerator.SEA - 40 && random.nextInt(3) == 0) // sun glints near the surface
            p.spawnParticle(Particle.DUST, around(l, 8).add(0, 3, 0), 1, 0, 0, 0, 0, dust(255, 250, 210, 0.7f));
        switch (k) {
            case SHALLOWS -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.BUBBLE_COLUMN_UP, around(l, 10).add(0, -3, 0), 2, 0.1, 0.4, 0.1, 0.05); }
            case KELP -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(120, 190, 80, 0.8f)); }
            case MEADOW -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(255, random.nextInt(90) + 60, 30, 0.6f));
                if (random.nextInt(4) == 0) p.spawnParticle(Particle.CRIMSON_SPORE, around(l, 8), 3, 0.5, 0.5, 0.5, 0); }
            case BULB -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.GLOW, around(l, 10), 1, 0.2, 0.2, 0.2, 0.01); }
            case DUNES -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.FALLING_DUST, around(l, 10), 1, 0.3, 0.1, 0.3, 0, Material.SAND.createBlockData()); }
            case CRAGS -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(190, 90, 60, 0.7f)); }
            case PEAKS -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.WHITE_ASH, around(l, 10), 2, 0.5, 0.5, 0.5, 0); }
            case GLOW -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(200, 120, 255, 0.8f));
                if (random.nextInt(3) == 0) p.spawnParticle(Particle.GLOW, around(l, 8), 1, 0, 0, 0, 0); }
            case ISLES -> { for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, around(l, 10).add(0, 4, 0), 1, 0.5, 0, 0.5, 0); }
            default -> { }
        }
    }

    void abyss(Player p, Location l, int n) {
        for (int i = 0; i < n; i++) p.spawnParticle(Particle.WHITE_ASH, around(l, 10).add(0, 3, 0), 2, 0.6, 0.2, 0.6, 0); // marine snow
        if (random.nextInt(4) == 0) { // something blinks out there
            Location c = around(l, 12);
            p.spawnParticle(Particle.DUST, c, 3, 0.1, 0.1, 0.1, 0, random.nextBoolean() ? dust(60, 230, 255, 0.9f) : dust(120, 255, 140, 0.9f));
        }
    }

    void sulphur(Player p, Location l, int n) {
        for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.BUBBLE_COLUMN_UP, around(l, 10).add(0, -2, 0), 2, 0.1, 0.3, 0.1, 0.03);
        for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(170, 200, 40, 0.9f));
    }

    void trench(Player p, Location l, int n) {
        for (int i = 0; i < n; i++) p.spawnParticle(Particle.ASH, around(l, 10), 3, 0.5, 0.5, 0.5, 0);
        if (random.nextInt(3) == 0) p.spawnParticle(Particle.DUST, around(l, 10).add(0, -3, 0), 2, 0.2, 0.6, 0.2, 0, dust(255, 60, 30, 0.8f));
        if (random.nextInt(6) == 0) p.spawnParticle(Particle.SCULK_SOUL, around(l, 8), 1, 0, 0.2, 0, 0.01);
    }

    void den(Player p, Location l, int n) {
        for (int i = 0; i < n / 2 + 1; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, random.nextBoolean() ? dust(255, 150, 210, 0.8f) : dust(120, 240, 255, 0.8f));
        for (int i = 0; i < n / 2; i++) p.spawnParticle(Particle.BUBBLE, around(l, 8), 2, 0.2, 0.2, 0.2, 0.02);
    }

    void region(Player p, Location l, int n, String region) {
        switch (region) {
            case "frozen" -> { for (int i = 0; i < n; i++) p.spawnParticle(Particle.SNOWFLAKE, around(l, 10), 2, 0.5, 0.5, 0.5, 0); }
            case "city" -> { for (int i = 0; i < n; i++) p.spawnParticle(Particle.FALLING_DUST, around(l, 10), 1, 0.4, 0.1, 0.4, 0, Material.GRAVEL.createBlockData()); }
            case "glass" -> { for (int i = 0; i < n; i++) p.spawnParticle(Particle.DUST, around(l, 10), 1, 0, 0, 0, 0, dust(random.nextInt(156) + 100, random.nextInt(156) + 100, random.nextInt(156) + 100, 0.7f)); }
            case "bones" -> { for (int i = 0; i < n; i++) p.spawnParticle(Particle.FALLING_DUST, around(l, 10), 1, 0.4, 0.1, 0.4, 0, Material.BONE_BLOCK.createBlockData()); }
            default -> { }
        }
    }

    void sound(Player p, Location l, DeepGenerator.Zone zone, String region) {
        Location at = around(l, 16);
        float v = 0.35f;
        switch (zone) {
            case TRENCH -> p.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, v * 1.5f, 0.6f);
            case ABYSS, CAVES -> p.playSound(at, random.nextBoolean() ? Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE : Sound.BLOCK_CONDUIT_AMBIENT_SHORT, SoundCategory.AMBIENT, v, 0.7f);
            case SULPHUR -> p.playSound(at, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundCategory.AMBIENT, v, 0.6f);
            default -> {
                if ("frozen".equals(region)) p.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.AMBIENT, v * 0.6f, 0.5f); // the ice groaning
                else if (random.nextInt(3) == 0) p.playSound(at, Sound.ENTITY_DOLPHIN_AMBIENT_WATER, SoundCategory.AMBIENT, v, 0.45f); // far-off whale song
                else p.playSound(at, Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, SoundCategory.AMBIENT, v, 0.9f);
            }
        }
    }
}
