package net.faultlinesmp.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LANTERN NIGHT (a whole night): a calm, festive night. Most monster spawns never happen, sky lanterns drift up all round
 * you, LANTERN WISPS (little glowing lanterns bobbing near the ground) give a reward when you walk into one, XP is x1.5,
 * and at midnight there's a fireworks show (harmless). FaultlineItems doubles its custom drop chances and gives villager
 * discounts while it's on.
 */
final class LanternNight extends Ev {

    static final String LANTERN_TAG = "faultline_sky_lantern", WISP_TAG = "faultline_lantern_wisp", SHOW_TAG = "faultline_lantern_show";

    private final List<Sky> skies = new ArrayList<>();
    private final List<Wisp> wisps = new ArrayList<>();
    private final Map<UUID, Integer> collected = new HashMap<>();
    private boolean showDone;
    private long showUntil;

    LanternNight(FaultlineEvents pl) { super(pl); }

    @Override String id() { return "LANTERN_NIGHT"; }
    @Override String key() { return "lantern-night"; }
    @Override String title() { return "Lantern Night"; }
    @Override ChatColor color() { return ChatColor.GOLD; }
    @Override BarColor barColor() { return BarColor.YELLOW; }
    @Override boolean night() { return true; }
    @Override String blurb() { return "a calm night: few monsters, sky lanterns, Lantern Wisps to catch for rewards, XP x1.5, double custom drops, cheaper villagers and fireworks at midnight."; }
    @Override String barText() { return ChatColor.YELLOW + "Catch the Lantern Wisps"; }

    @Override
    void start(boolean announce) {
        collected.clear(); showDone = false; showUntil = 0;
        if (announce) announce(ChatColor.GOLD + "" + ChatColor.BOLD + "Lantern Night",
                ChatColor.YELLOW + "A calm night. Catch the wisps.",
                ChatColor.GOLD + "✦ " + ChatColor.BOLD + "Lantern Night!" + ChatColor.YELLOW + " Few monsters tonight, Lantern Wisps to catch, XP x1.5, double drops and cheaper villagers. Fireworks at midnight.",
                Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f);
    }

    @Override
    void stop(boolean natural) {
        for (Sky s : skies) if (s.d.isValid()) s.d.remove();
        skies.clear();
        for (Wisp w : wisps) if (w.d.isValid()) w.d.remove();
        wisps.clear();
        for (var e : collected.entrySet()) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null) pl.stat(p, "lantern_wisps", e.getValue()); }
        if (natural) pl.say(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "The lanterns burn out. Lantern Night is over.");
    }

    // ---------------------------------------------------------------- calm

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on() || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster)) return;
        if (pl.eventWorld(e.getLocation().getWorld()) && pl.random.nextDouble() < d("calm", 0.6)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onXp(PlayerExpChangeEvent e) {
        if (!on() || e.getAmount() <= 0 || !pl.eventWorld(e.getPlayer().getWorld())) return;
        e.setAmount((int) Math.round(e.getAmount() * d("xp-multiplier", 1.5)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onShowHurt(EntityDamageByEntityEvent e) { if (e.getDamager().getScoreboardTags().contains(SHOW_TAG)) e.setCancelled(true); }

    // ---------------------------------------------------------------- ticks

    @Override
    void tick(long t) {
        int every = Math.max(5, i("sky-lanterns.every-ticks", 30));
        if (t % every == 0) for (Player p : pl.players()) {
            int mine = 0;
            for (Sky s : skies) if (s.owner.equals(p.getUniqueId())) mine++;
            if (mine < i("sky-lanterns.per-player", 14)) sky(p);
        }
        if (t % 10 == 0) {
            double rise = d("sky-lanterns.rise-per-second", 1.6) / 2;
            int life = i("sky-lanterns.lifetime-seconds", 45) * 2;
            for (Iterator<Sky> it = skies.iterator(); it.hasNext(); ) {
                Sky s = it.next();
                if (!s.d.isValid() || ++s.age > life) { if (s.d.isValid()) s.d.remove(); it.remove(); continue; }
                Location l = s.d.getLocation();
                l.add(Math.sin(s.age * 0.3 + s.phase) * 0.12, rise, Math.cos(s.age * 0.25 + s.phase) * 0.12);
                s.d.teleport(l);
                l.getWorld().spawnParticle(Particle.SMALL_FLAME, l.clone().add(0, 0.2, 0), 1, 0.05, 0.02, 0.05, 0.002);
            }
        }
        if (t % 5 == 0) for (Iterator<Wisp> it = wisps.iterator(); it.hasNext(); ) {
            Wisp w = it.next();
            if (!w.d.isValid() || (w.age += 5) > i("wisps.lifetime-seconds", 180) * 20) { if (w.d.isValid()) w.d.remove(); it.remove(); continue; }
            Location l = w.base.clone().add(0, Math.sin(w.age * 0.08) * 0.25, 0);
            w.d.teleport(l);
            l.getWorld().spawnParticle(Particle.END_ROD, l.clone().add(0, 0.3, 0), 1, 0.15, 0.15, 0.15, 0.005);
            double r = d("wisps.collect-radius", 1.8);
            for (Player p : pl.players()) {
                if (!FaultlineEvents.survival(p) || !p.getWorld().equals(l.getWorld())) continue;
                if (p.getLocation().add(0, 0.9, 0).distanceSquared(l) <= r * r) { collect(p, w); it.remove(); break; }
            }
        }
        if (showUntil > 0 && t % 20 == 0) {
            if (System.currentTimeMillis() > showUntil) showUntil = 0;
            else for (Player p : pl.players()) if (pl.random.nextDouble() < 0.5) firework(p);
        }
    }

    @Override
    void second() {
        World w = pl.main();
        long sec = pl.ticks / 20;
        int every = Math.max(5, i("wisps.every-seconds", 20));
        for (Player p : pl.players()) {
            if (!FaultlineEvents.survival(p)) continue;
            if (sec % every == Math.floorMod(p.getUniqueId().hashCode(), every)) {
                int mine = 0;
                for (Wisp x : wisps) if (x.owner.equals(p.getUniqueId())) mine++;
                if (mine < i("wisps.per-player", 2)) wisp(p);
            }
            if (pl.random.nextInt(20) == 0) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.8f + pl.random.nextFloat() * 0.6f);
        }
        if (!showDone && b("fireworks.enabled", true) && w.getTime() >= i("fireworks.at-time", 18000)) {
            showDone = true;
            showUntil = System.currentTimeMillis() + i("fireworks.seconds", 40) * 1000L;
            pl.say(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Midnight! Look up.");
        }
    }

    private BlockDisplay lantern(Location at, float scale, boolean soul) {
        BlockDisplay d = (BlockDisplay) at.getWorld().spawnEntity(at, EntityType.BLOCK_DISPLAY, false);
        org.bukkit.block.data.BlockData data = (soul ? Material.SOUL_LANTERN : Material.LANTERN).createBlockData();
        if (data instanceof Lantern l) l.setHanging(false);
        d.setBlock(data);
        d.setPersistent(false);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setTeleportDuration(10);
        d.setTransformation(new Transformation(new Vector3f(-scale / 2, 0, -scale / 2), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        return d;
    }

    private void sky(Player p) {
        double min = d("sky-lanterns.min-distance", 6), max = d("sky-lanterns.max-distance", 30);
        double a = pl.random.nextDouble() * Math.PI * 2, r = min + pl.random.nextDouble() * (max - min);
        Location base = p.getLocation();
        int x = (int) Math.floor(base.getX() + Math.cos(a) * r), z = (int) Math.floor(base.getZ() + Math.sin(a) * r);
        World w = p.getWorld();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        if (y > base.getY() + 30 || y < base.getY() - 40) return; // underground players don't get lanterns in the rock
        BlockDisplay d = lantern(new Location(w, x + 0.5, y + 1.5, z + 0.5), 0.6f, false);
        d.addScoreboardTag(LANTERN_TAG);
        d.addScoreboardTag("faultline_no_bedrock_fx");
        skies.add(new Sky(p.getUniqueId(), d, pl.random.nextDouble() * 6));
    }

    private void wisp(Player p) {
        Location at = groundSpot(p, 10, 24, 8);
        if (at == null) return;
        Location base = at.clone().add(0, 0.6, 0);
        BlockDisplay d = lantern(base, 0.45f, pl.random.nextInt(6) == 0);
        d.addScoreboardTag(WISP_TAG);
        d.setGlowing(true);
        d.setGlowColorOverride(Color.fromRGB(255, 200, 80));
        wisps.add(new Wisp(p.getUniqueId(), d, base));
    }

    private void collect(Player p, Wisp w) {
        Location l = w.d.getLocation();
        w.d.remove();
        l.getWorld().spawnParticle(Particle.END_ROD, l, 20, 0.3, 0.3, 0.3, 0.06);
        l.getWorld().spawnParticle(Particle.FLAME, l, 8, 0.2, 0.2, 0.2, 0.02);
        p.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.7f);
        int n = collected.merge(p.getUniqueId(), 1, Integer::sum);
        List<String> got = new ArrayList<>();
        for (String r : list("wisps.rewards")) {
            int semi = r.indexOf(';');
            if (semi < 0) continue;
            double chance;
            try { chance = Double.parseDouble(r.substring(0, semi).trim()); } catch (NumberFormatException e) { continue; }
            if (pl.random.nextDouble() >= chance) continue;
            String what = r.substring(semi + 1).trim();
            if (what.startsWith("xp:")) {
                try {
                    int xp = Integer.parseInt(what.substring(3).trim());
                    ExperienceOrb orb = (ExperienceOrb) p.getWorld().spawnEntity(p.getLocation(), EntityType.EXPERIENCE_ORB);
                    orb.setExperience(xp);
                } catch (RuntimeException e) { p.giveExp(25); }
            } else if (what.startsWith("cmd:")) {
                pl.console(what.substring(4).trim(), p);
                got.add("a surprise");
            } else {
                ItemStack it = stack(what);
                if (it != null) { give(p, it); got.add(it.getAmount() + " " + it.getType().name().toLowerCase().replace('_', ' ')); }
            }
        }
        p.sendActionBar(net.kyori.adventure.text.Component.text("Lantern Wisp caught (" + n + ")" + (got.isEmpty() ? "" : ": " + String.join(", ", got)), net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    private void firework(Player p) {
        double a = pl.random.nextDouble() * Math.PI * 2, r = 20 + pl.random.nextDouble() * 15;
        Location base = p.getLocation();
        int x = (int) Math.floor(base.getX() + Math.cos(a) * r), z = (int) Math.floor(base.getZ() + Math.sin(a) * r);
        World w = p.getWorld();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        if (y > base.getY() + 40 || y < base.getY() - 40) return;
        try {
            Firework fw = (Firework) w.spawnEntity(new Location(w, x + 0.5, y + 1, z + 0.5), EntityType.FIREWORK_ROCKET);
            fw.addScoreboardTag(SHOW_TAG);
            FireworkMeta m = fw.getFireworkMeta();
            Color[] pal = {Color.fromRGB(255, 190, 60), Color.fromRGB(255, 120, 40), Color.fromRGB(255, 230, 150), Color.fromRGB(240, 80, 80), Color.fromRGB(120, 200, 255)};
            FireworkEffect.Type[] types = {FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST, FireworkEffect.Type.BALL};
            m.addEffect(FireworkEffect.builder().with(types[pl.random.nextInt(types.length)])
                    .withColor(pal[pl.random.nextInt(pal.length)], pal[pl.random.nextInt(pal.length)])
                    .withFade(Color.fromRGB(255, 240, 200)).trail(true).flicker(pl.random.nextBoolean()).build());
            m.setPower(1 + pl.random.nextInt(2));
            fw.setFireworkMeta(m);
        } catch (RuntimeException e) { pl.problem("firework", e); }
    }

    private static final class Sky {
        final UUID owner; final BlockDisplay d; final double phase; int age;
        Sky(UUID owner, BlockDisplay d, double phase) { this.owner = owner; this.d = d; this.phase = phase; }
    }

    private static final class Wisp {
        final UUID owner; final BlockDisplay d; final Location base; int age;
        Wisp(UUID owner, BlockDisplay d, Location base) { this.owner = owner; this.d = d; this.base = base; }
    }
}
