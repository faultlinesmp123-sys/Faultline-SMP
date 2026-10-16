package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE ACID RAIN (Deep 1.5.0), after the Calamity mod's event. Every `acid-rain.check-minutes` (5), if anyone is in (or
 * near) the Sulphur Sea, there's an `acid-rain.chance` (0.5%) it starts: `acid-rain.minutes` (6) of heavy green rain over
 * the Sulphur Sea (Ocean.greenRain draws it three times as thick). Out in the open without a helmet it burns you (a
 * helmet takes the wear instead). Its five creatures (DeepMobs.RAIN: the Sludge Shambler, Rustbones, Acid Mites, the Storm
 * Wraith, the Bile Mauler) come for everyone there, and `goblin-after-seconds` (90) in GRUBNASH THE ACID GOBLIN rides in:
 * a 500-health mini boss with his own boss bar. When it ends the rain's creatures melt away; a goblin still standing
 * cackles and vanishes (no loot). `/deep acidrain [start|stop|goblin]`.
 */
final class AcidRain {
    final FaultlineDeep pl;
    final Random random = new Random();
    boolean active;
    long startedAt, endsAt;
    boolean goblinCame;
    UUID goblin;
    BossBar bar, goblinBar;
    private int secs;

    AcidRain(FaultlineDeep pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("acid-rain." + k, def); }

    /** In the Sulphur Sea, or close enough to its edge to be caught by the rain. */
    boolean caught(Player p) {
        World w = pl.world;
        if (w == null || !p.getWorld().equals(w) || p.getGameMode() == GameMode.SPECTATOR) return false;
        Location l = p.getLocation();
        return pl.generator.sulphur(w.getSeed(), l.getBlockX(), l.getBlockZ()) > cfg("edge-weight", 0.15);
    }

    List<Player> caughtPlayers() {
        List<Player> out = new ArrayList<>();
        if (pl.world != null) for (Player p : pl.world.getPlayers()) if (caught(p)) out.add(p);
        return out;
    }

    void tick() {
        secs++;
        if (!pl.getConfig().getBoolean("acid-rain.enabled", true) || pl.world == null) { if (active) stop(false); return; }
        if (!active) {
            int every = (int) Math.max(1, cfg("check-minutes", 5) * 60);
            if (secs % every == 0) {
                boolean anyone = false;
                for (Player p : caughtPlayers()) if (DeepMobs.fair(p)) { anyone = true; break; }
                if (anyone && random.nextDouble() < cfg("chance", 0.005)) start();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= endsAt) { stop(true); return; }
        List<Player> in = caughtPlayers();
        // the bar: who's in it now, and how long is left
        if (bar != null) {
            for (Player p : new ArrayList<>(bar.getPlayers())) if (!in.contains(p)) bar.removePlayer(p);
            for (Player p : in) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            bar.setProgress(Math.max(0, Math.min(1, (endsAt - now) / (double) (endsAt - startedAt))));
        }
        // the rain burns anyone out in the open (a helmet takes it instead)
        if (secs % 3 == 0) for (Player p : in) if (DeepMobs.fair(p) && exposed(p)) burn(p);
        // thunder now and then
        if (secs % 7 == 0) for (Player p : in) {
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 0.6f, 0.6f + random.nextFloat() * 0.3f);
            if (random.nextInt(3) == 0) try { pl.world.strikeLightningEffect(p.getLocation().add(random.nextGaussian() * 40, 0, random.nextGaussian() * 40)); } catch (RuntimeException ignored) { }
        }
        // its creatures
        if (secs % (int) Math.max(1, cfg("spawn-seconds", 5)) == 0) for (Player p : in) if (DeepMobs.fair(p)) spawnNear(p);
        // the goblin
        if (!goblinCame && now - startedAt >= cfg("goblin-after-seconds", 90) * 1000 && !in.isEmpty()) {
            List<Player> fair = new ArrayList<>();
            for (Player p : in) if (DeepMobs.fair(p)) fair.add(p);
            if (!fair.isEmpty()) spawnGoblin(fair.get(random.nextInt(fair.size())));
        }
        goblinTick();
    }

    /** Nothing but sky over you, and not under the water. */
    boolean exposed(Player p) {
        Location e = p.getEyeLocation();
        if (e.getBlock().isLiquid()) return false;
        return p.getWorld().getHighestBlockYAt(e.getBlockX(), e.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING) <= e.getY();
    }

    void burn(Player p) {
        ItemStack helm = p.getInventory().getHelmet();
        if (helm != null && helm.getType().getMaxDurability() > 0 && helm.getItemMeta() instanceof Damageable d) { // the helmet takes it
            d.setDamage(Math.min(helm.getType().getMaxDurability() - 1, d.getDamage() + (int) cfg("helmet-wear", 2)));
            helm.setItemMeta(d);
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 70, 0));
        p.sendActionBar(Component.text("The acid rain burns! Get under cover, or wear a helmet.", NamedTextColor.GREEN));
    }

    int rainNear(Location l, double r) {
        int n = 0;
        for (DeepMobs.Beast m : pl.mobs.live.values()) if (DeepMobs.RAIN.contains(m.k.id()) && !m.k.id().equals("goblin")
                && m.body.getWorld().equals(l.getWorld()) && m.body.getLocation().distanceSquared(l) < r * r) n++;
        return n;
    }

    /** One of the rain's creatures for this player, where it lives: islands, the water, the air. */
    void spawnNear(Player p) {
        if (rainNear(p.getLocation(), 40) >= cfg("cap-per-player", 6)) return;
        String[] water = {"shambler", "shambler", "mauler"}, land = {"rustbones", "rustbones", "acidmite", "shambler"};
        int pick = random.nextInt(10);
        Location at;
        String id;
        if (pick < 2) { // the air
            id = "stormwraith";
            at = pl.mobs.spawnSpot(p.getLocation());
            Location ground = pl.mobs.landSpot(p.getLocation());
            if (ground != null && (at == null || random.nextBoolean())) at = ground;
            if (at == null) return;
            at = at.clone();
            at.setY(Math.max(at.getY(), DeepGenerator.SEA + 4) + random.nextInt(4));
        } else if (pick < 6) { // the islands
            at = pl.mobs.landSpot(p.getLocation());
            id = land[random.nextInt(land.length)];
            if (at == null) { at = surfaceSpot(p); id = water[random.nextInt(water.length)]; }
        } else { // the water
            at = surfaceSpot(p);
            id = water[random.nextInt(water.length)];
        }
        if (at == null) return;
        DeepMobs.Kind k = DeepMobs.KINDS.get(id);
        if (k == null || !pl.getConfig().getBoolean("mobs." + id + ".enabled", true)) return;
        int n = id.equals("acidmite") ? 3 + random.nextInt(2) : 1;
        for (int i = 0; i < n; i++) {
            DeepMobs.Beast b = pl.mobs.spawn(k, at.clone().add(i == 0 ? 0 : random.nextGaussian(), 0, i == 0 ? 0 : random.nextGaussian()));
            if (b != null && b.body instanceof Mob mb) mb.setTarget(p);
        }
    }

    /** Open water near the surface, 14-40 blocks from p. */
    Location surfaceSpot(Player p) {
        Location s = pl.mobs.spawnSpot(p.getLocation());
        if (s == null) return null;
        Location c = s.clone();
        c.setY(DeepGenerator.SEA - 2 - random.nextInt(6));
        return c.getBlock().getType() == org.bukkit.Material.WATER ? c : s;
    }

    // ---------------------------------------------------------------------------------------------------- start / stop
    boolean start() {
        if (active || pl.world == null) return false;
        active = true;
        goblinCame = false;
        goblin = null;
        startedAt = System.currentTimeMillis();
        endsAt = startedAt + (long) (cfg("minutes", 6) * 60_000);
        bar = Bukkit.createBossBar(ChatColor.GREEN + "" + ChatColor.BOLD + "ACID RAIN " + ChatColor.GRAY + "(get under cover or wear a helmet)", BarColor.GREEN, BarStyle.SEGMENTED_10);
        for (Player p : pl.world.getPlayers()) {
            if (caught(p)) {
                bar.addPlayer(p);
                p.showTitle(Title.title(Component.text("ACID RAIN", NamedTextColor.GREEN), Component.text("The sky over the Sulphur Sea turns green...", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000))));
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1.2f, 0.5f);
            }
            p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "The Acid Rain " + ChatColor.GRAY + "is falling over the Sulphur Sea. Things are crawling out of the sulphur...");
        }
        return true;
    }

    void stop(boolean natural) {
        if (!active) return;
        active = false;
        if (bar != null) { bar.removeAll(); bar = null; }
        if (goblinBar != null) { goblinBar.removeAll(); goblinBar = null; }
        // the rain's creatures melt away; a goblin still standing runs off with his loot
        for (DeepMobs.Beast m : new ArrayList<>(pl.mobs.live.values())) {
            if (!DeepMobs.RAIN.contains(m.k.id())) continue;
            Location at = m.body.getLocation();
            at.getWorld().spawnParticle(org.bukkit.Particle.DUST, at.clone().add(0, 0.6, 0), 20, 0.4, 0.5, 0.4, 0,
                    new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(150, 220, 60), 1.4f));
            if (m.k.id().equals("goblin")) for (Player p : pl.world.getPlayers())
                p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grubnash" + ChatColor.DARK_GRAY + ": " + ChatColor.YELLOW + "Hehehe! Too slow! " + ChatColor.GRAY + "(he vanishes into the mist)");
            pl.mobs.remove(m);
        }
        goblin = null;
        if (pl.world != null) for (Player p : pl.world.getPlayers())
            p.sendMessage(ChatColor.GREEN + (natural ? "The Acid Rain eases off. The sky over the Sulphur Sea clears." : "The Acid Rain was stopped."));
    }

    // ---------------------------------------------------------------------------------------------------- the goblin
    boolean spawnGoblin(Player near) {
        if (goblin != null) { DeepMobs.Beast b = pl.mobs.live.get(goblin); if (b != null && b.body.isValid()) return false; }
        Location at = pl.mobs.landSpot(near.getLocation());
        if (at == null) at = surfaceSpot(near);
        if (at == null) at = near.getLocation().add(near.getLocation().getDirection().setY(0).multiply(6));
        DeepMobs.Beast g = pl.mobs.spawn(DeepMobs.KINDS.get("goblin"), at);
        if (g == null) return false;
        goblinCame = true;
        goblin = g.body.getUniqueId();
        if (g.body instanceof Mob mb) mb.setTarget(near);
        goblinBar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Grubnash the Acid Goblin", BarColor.YELLOW, BarStyle.SEGMENTED_12);
        World w = at.getWorld();
        try { w.strikeLightningEffect(at); } catch (RuntimeException ignored) { }
        w.playSound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 2f, 1.4f);
        for (Player p : w.getPlayers()) if (caught(p) || p.getLocation().distanceSquared(at) < 80 * 80)
            p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grubnash the Acid Goblin " + ChatColor.YELLOW + "rides in on the storm! " + ChatColor.GRAY + "(mini boss, 500 health)");
        pl.mobs.goblinSay(g, "Hehehe! Fresh shinies for Grubnash!");
        return true;
    }

    void goblinTick() {
        if (goblin == null || goblinBar == null) return;
        DeepMobs.Beast g = pl.mobs.live.get(goblin);
        if (g == null || !g.body.isValid()) { goblinBar.removeAll(); goblinBar = null; goblin = null; return; }
        goblinBar.setProgress(Math.max(0, Math.min(1, g.body.getHealth() / g.k.health())));
        Set<Player> want = new HashSet<>();
        for (Player p : g.body.getWorld().getPlayers()) if (p.getLocation().distanceSquared(g.body.getLocation()) < 48 * 48) want.add(p);
        for (Player p : new ArrayList<>(goblinBar.getPlayers())) if (!want.contains(p)) goblinBar.removePlayer(p);
        for (Player p : want) if (!goblinBar.getPlayers().contains(p)) goblinBar.addPlayer(p);
    }

    void goblinDied(UUID who) {
        if (goblin == null || !goblin.equals(who)) return; // (an admin's egg goblin isn't the event's)
        if (goblinBar != null) { goblinBar.removeAll(); goblinBar = null; }
        goblin = null;
    }

    void shutdown() { stop(false); }
}
