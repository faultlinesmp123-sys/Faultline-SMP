package net.faultlinesmp.events;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FOG (12 minutes, day or night). The world fades out past a few chunks (each player's send view distance is lowered,
 * so the game draws its own distance fog) and a grey mist drifts round everyone. The undead don't burn, more of them
 * wander in out of the fog, kills give +50% XP, distant sounds, and now and then SOMEONE stands in the fog watching you:
 * a still figure with an online player's face that vanishes when you come close or stare at it.
 */
final class Fog extends Ev {

    static final String MOB_TAG = "fog_mob";
    static final String SHADE_TAG = "faultline_fog_shade";
    private static final Color MIST = Color.fromRGB(196, 200, 206), MIST2 = Color.fromRGB(170, 176, 184);

    private final Set<UUID> mobs = new HashSet<>();
    private final Map<UUID, Shade> shades = new HashMap<>();
    private final Set<UUID> fogged = new HashSet<>();

    Fog(FaultlineEvents pl) { super(pl); }

    @Override String id() { return "FOG"; }
    @Override String key() { return "fog"; }
    @Override String title() { return "Fog"; }
    @Override ChatColor color() { return ChatColor.GRAY; }
    @Override BarColor barColor() { return BarColor.WHITE; }
    @Override boolean night() { return false; }
    @Override String blurb() { return "a thick fog: you can't see far, the undead don't burn and more of them wander in, kills give +50% XP. Something watches from the mist."; }
    @Override String barText() { return ChatColor.GRAY + "Stay close to the light"; }

    @Override
    void start(boolean announce) {
        for (Player p : pl.players()) fogIn(p);
        if (announce) announce(ChatColor.GRAY + "" + ChatColor.BOLD + "The Fog Rolls In",
                ChatColor.DARK_GRAY + "You can't see far. Neither can they.",
                ChatColor.GRAY + "☁ " + ChatColor.BOLD + "The fog rolls in." + ChatColor.GRAY + " The undead walk in daylight and more of them come. Stay together.",
                Sound.AMBIENT_CAVE, 0.6f);
    }

    @Override
    void stop(boolean natural) {
        for (UUID id : new ArrayList<>(fogged)) { Player p = Bukkit.getPlayer(id); if (p != null) fogOut(p); }
        fogged.clear();
        for (Shade s : new ArrayList<>(shades.values())) s.vanish(false);
        shades.clear();
        if (natural) Bukkit.broadcastMessage(ChatColor.GRAY + "☁ The fog lifts.");
    }

    @Override void joined(Player p) { fogIn(p); }
    @Override void left(Player p) { fogOut(p); Shade s = shades.remove(p.getUniqueId()); if (s != null) s.vanish(false); }

    private void fogIn(Player p) {
        int vd = i("view-distance", 4);
        if (vd <= 0) return;
        try { p.setSendViewDistance(Math.max(2, vd)); fogged.add(p.getUniqueId()); } catch (RuntimeException | LinkageError e) { pl.problem("fog view", e); }
    }

    private void fogOut(Player p) {
        if (!fogged.remove(p.getUniqueId())) return;
        try { p.setSendViewDistance(-1); } catch (RuntimeException | LinkageError ignored) { }
    }

    @Override
    void tick(long t) {
        if (t % 5 != 0) return;
        int n = i("density", 60);
        for (Player p : pl.players()) {
            Location l = p.getLocation();
            for (int k = 0; k < n; k++) {
                double a = pl.random.nextDouble() * Math.PI * 2, r = 3 + pl.random.nextDouble() * 18;
                Location at = l.clone().add(Math.cos(a) * r, pl.random.nextDouble() * 6 - 1.5, Math.sin(a) * r);
                p.spawnParticle(Particle.DUST, at, 1, 0.4, 0.3, 0.4, 0, new Particle.DustOptions(k % 3 == 0 ? MIST2 : MIST, 3.2f + pl.random.nextFloat() * 1.2f));
            }
            if (pl.random.nextInt(4) == 0) p.spawnParticle(Particle.CLOUD, l.clone().add(pl.random.nextGaussian() * 6, 0.2, pl.random.nextGaussian() * 6), 0, 0.03, 0, 0.03, 1);
        }
        for (Shade s : new ArrayList<>(shades.values())) s.tick();
    }

    @Override
    void second() {
        mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
        long sec = pl.ticks / 20;
        int every = Math.max(3, i("spawns.every-seconds", 20));
        for (Player p : pl.players()) {
            if (!fogged.contains(p.getUniqueId())) fogIn(p);
            if (!FaultlineEvents.survival(p)) continue;
            if (sec % every == Math.floorMod(p.getUniqueId().hashCode(), every)) wanderers(p);
            if (b("ambience", true) && pl.random.nextInt(18) == 0) {
                Location at = p.getLocation().add(pl.random.nextGaussian() * 10, 0, pl.random.nextGaussian() * 10);
                Sound s = switch (pl.random.nextInt(4)) { case 0 -> Sound.AMBIENT_CAVE; case 1 -> Sound.ENTITY_ZOMBIE_AMBIENT; case 2 -> Sound.BLOCK_GRAVEL_STEP; default -> Sound.ENTITY_PHANTOM_AMBIENT; };
                p.playSound(at, s, 0.5f, 0.6f + pl.random.nextFloat() * 0.3f);
            }
            if (b("shades.enabled", true) && !shades.containsKey(p.getUniqueId())
                    && sec % Math.max(10, i("shades.every-seconds", 35)) == 0 && pl.random.nextDouble() < d("shades.chance", 0.4)) shade(p);
        }
    }

    private void wanderers(Player p) {
        int near = 0;
        for (UUID id : mobs) { Entity e = Bukkit.getEntity(id); if (e != null && e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) < 40 * 40) near++; }
        if (near >= i("spawns.per-player", 3)) return;
        Location at = groundSpot(p, d("spawns.min-distance", 14), d("spawns.max-distance", 26), 10);
        if (at == null || at.getBlock().getLightFromBlocks() >= 8) return;
        EntityType type = switch (pl.random.nextInt(5)) { case 0, 1 -> EntityType.ZOMBIE; case 2 -> EntityType.SKELETON; case 3 -> EntityType.SPIDER; default -> EntityType.CREEPER; };
        try {
            Entity e = at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL);
            e.addScoreboardTag(MOB_TAG);
            mobs.add(e.getUniqueId());
        } catch (RuntimeException ex) { pl.problem("fog spawn", ex); }
    }

    /** The undead don't burn in the fog (it's the sun that burns them; fire and lava still do). */
    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) {
        if (!on() || !b("undead-dont-burn", true) || e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (e.getEntity() instanceof Monster && pl.eventWorld(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!on() || !(e.getEntity() instanceof Enemy) || e.getEntity().getKiller() == null || !pl.eventWorld(e.getEntity().getWorld())) return;
        e.setDroppedExp((int) Math.round(e.getDroppedExp() * d("xp-multiplier", 1.5)));
        pl.stat(e.getEntity().getKiller(), "fog_kills", 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShadeHurt(EntityDamageEvent e) { if (e.getEntity().getScoreboardTags().contains(SHADE_TAG)) e.setCancelled(true); }

    // ---------------------------------------------------------------- shades

    private void shade(Player p) {
        Location eye = p.getLocation();
        Vector back = eye.getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
        Location at = null;
        for (int k = 0; k < 12 && at == null; k++) {
            Location c = groundSpot(p, 18, 26, 4);
            if (c == null) continue;
            Vector to = c.toVector().subtract(eye.toVector()).setY(0);
            if (to.lengthSquared() > 0 && to.normalize().dot(back.clone().normalize()) > 0.2) at = c; // ahead of them, at the edge of sight
        }
        if (at == null) return;
        List<Player> faces = new ArrayList<>(Bukkit.getOnlinePlayers());
        Player face = faces.get(pl.random.nextInt(faces.size()));
        try {
            Mannequin m = (Mannequin) at.getWorld().spawnEntity(at, EntityType.MANNEQUIN, false);
            m.addScoreboardTag(SHADE_TAG);
            m.setPersistent(false);
            m.setGravity(false);
            m.setSilent(true);
            m.setInvulnerable(true);
            m.setCollidable(false);
            m.setVisibleByDefault(false);
            try { m.setImmovable(true); } catch (RuntimeException ignored) { }
            try { m.setProfile(ResolvableProfile.resolvableProfile(face.getPlayerProfile())); } catch (RuntimeException ignored) { }
            try { m.setDescription(null); } catch (RuntimeException ignored) { }
            p.showEntity(pl, m);
            shades.put(p.getUniqueId(), new Shade(p, m));
        } catch (RuntimeException | LinkageError e) { pl.problem("shade", e); }
    }

    final class Shade {
        final Player p;
        final Mannequin m;
        int life, stare;

        Shade(Player p, Mannequin m) { this.p = p; this.m = m; }

        void tick() {
            life += 5;
            if (!p.isOnline() || !m.isValid() || !p.getWorld().equals(m.getWorld())) { vanish(false); return; }
            Location ml = m.getLocation(), pe = p.getEyeLocation();
            Vector to = pe.toVector().subtract(ml.toVector().add(new Vector(0, 1.6, 0)));
            double dist = to.length();
            if (dist > 0.1) { // it watches you
                Location face = ml.clone();
                face.setDirection(to);
                m.setRotation(face.getYaw(), face.getPitch());
            }
            Vector look = pe.getDirection();
            Vector at = ml.toVector().add(new Vector(0, 1, 0)).subtract(pe.toVector());
            boolean staring = at.lengthSquared() > 0 && look.dot(at.clone().normalize()) > 0.985;
            stare = staring ? stare + 5 : 0;
            if (dist < 11 || stare >= 20 || life > 20 * 25) vanish(true);
        }

        void vanish(boolean show) {
            shades.remove(p.getUniqueId());
            if (m.isValid()) {
                if (show && p.isOnline()) {
                    Location l = m.getLocation().add(0, 1, 0);
                    p.spawnParticle(Particle.LARGE_SMOKE, l, 25, 0.3, 0.7, 0.3, 0.01);
                    p.playSound(l, Sound.AMBIENT_CAVE, 0.9f, 1.4f);
                    pl.stat(p, "fog_shades", 1);
                }
                m.remove();
            }
        }
    }
}
