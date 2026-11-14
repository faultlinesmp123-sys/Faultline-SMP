package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * HALCYON'S PATROL SUBS (Deep 1.17.0): four research subs (the Submersible's model, bigger) with villager pilots, berthed on
 * the dock outside the south gate. While the city is awake they go out:
 *  - EXPLORING: off to a spot of open sea 60-300 blocks round the city (only loaded water), and back
 *  - SURVEYING: a creature near them gets a scan beam ("sample logged"); a passive one may be CAPTURED for the Aquarium
 *    (a tractor beam, and it's gone); an aggressive one is ATTACKED with torpedoes
 *  - RADAR: anyone wanted by Halcyon within `halcyon.radar-radius` (400) is HUNTED: every sub in the fleet closes in and
 *    fires homing torpedoes (`torpedo-damage`), pinging its radar at them. Attacking a sub makes you wanted at once
 *  - HEALTH: each has a bar over it (`sub-health` 120). At 0 it SINKS: alarms, smoke, nose down to the sea floor, its pilot
 *    bails out, it drops salvage; it comes back to its berth after `sub-respawn-minutes`. Sinking one = wanted 3
 *  - low on health or full of samples: back to its berth to repair
 * Its hitbox is an invisible slime (spawned by type); Bedrock players see a stand wearing the sub, like the Submersible.
 */
final class SubFleet implements Listener {
    static final String TAG = "faultline_halcyon_sub", HIT_TAG = "faultline_halcyon_sub_hit", BSTAND = "faultline_halcyon_sub_bedrock";

    enum State { DOCKED, EXPLORE, SURVEY, CAPTURE, ATTACK, HUNT, RETURN, SINKING }

    static final String[] NAMES = {"HS-01 Pelican", "HS-02 Seeker", "HS-03 Nautilus", "HS-04 Warden"};
    static final String[] PILOTS = {"Pilot Maren", "Pilot Osric", "Pilot Lyle", "Pilot Wren"};

    final class Patrol {
        final int id;
        final String name, pilot;
        ItemDisplay body, prop;
        TextDisplay bar;
        Slime hit;
        ArmorStand bed;
        Location pos;
        Vector vel = new Vector();
        float yaw, pitch;
        double hp, spin;
        State state = State.DOCKED;
        Location goal;
        LivingEntity prey;
        UUID hunting;
        int t, shotCd, cargo, stuck, sinkT, flash;
        long deadUntil;
        Patrol(int id) { this.id = id; name = NAMES[id % NAMES.length]; pilot = PILOTS[id % PILOTS.length]; }
        boolean out() { return body != null && body.isValid(); }
    }

    final class Torpedo {
        Location at;
        Vector dir;
        int life = 70;
        UUID target;
        final Patrol from;
        Torpedo(Patrol from) { this.from = from; }
    }

    final FaultlineDeep pl;
    final HalcyonCity city;
    final List<Patrol> fleet = new ArrayList<>();
    final List<Torpedo> torps = new ArrayList<>();
    final Map<UUID, Patrol> byHit = new HashMap<>();
    final Random random = new Random();
    int ticks;

    SubFleet(FaultlineDeep pl, HalcyonCity city) {
        this.pl = pl;
        this.city = city;
        for (int i = 0; i < Halcyon.berths().length; i++) fleet.add(new Patrol(i));
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("halcyon." + path, def); }

    Location berth(Patrol s) {
        int[] b = Halcyon.berths()[s.id];
        return new Location(pl.world, Halcyon.HX + b[0] + 0.5, b[1], Halcyon.HZ + b[2] + 0.5, 180, 0);
    }

    // =========================================================================================== spawning
    void spawnAll() {
        if (pl.world == null || !pl.getConfig().getBoolean("halcyon.subs", true)) return;
        for (Patrol s : fleet) if (!s.out() && System.currentTimeMillis() >= s.deadUntil) spawn(s);
    }

    void spawn(Patrol s) {
        Location l = berth(s);
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        s.pos = l.clone();
        s.yaw = 180; s.pitch = 0;
        s.vel = new Vector();
        s.hp = cfg("sub-health", 120);
        s.state = State.DOCKED;
        s.cargo = 0; s.sinkT = 0; s.prey = null; s.hunting = null;
        s.t = random.nextInt(200);
        s.body = part(l, "deep/submersible");
        s.prop = part(l, "deep/submersible__prop");
        s.bar = l.getWorld().spawn(l.clone().add(0, 2.6, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(2);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(120, 0, 0, 0));
        });
        Entity e = l.getWorld().spawnEntity(l, EntityType.SLIME, false); // by type (26.2 can't spawn Slime by class)
        if (e instanceof Slime sl) {
            sl.setSize(4);
            sl.setAI(false);
            sl.setInvisible(true);
            sl.setSilent(true);
            sl.setGravity(false);
            sl.setPersistent(false);
            sl.setRemoveWhenFarAway(false);
            sl.addScoreboardTag(TAG);
            sl.addScoreboardTag(HIT_TAG);
            sl.setCustomName(ChatColor.AQUA + s.name);
            sl.setCustomNameVisible(false);
            DeepMobs.attr(sl, Attribute.MAX_HEALTH, 1000);
            sl.setHealth(1000);
            s.hit = sl;
            byHit.put(sl.getUniqueId(), s);
        } else e.remove();
        s.bed = l.getWorld().spawn(l, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
            a.setInvisible(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setMarker(false);
            a.addScoreboardTag(TAG);
            a.addScoreboardTag(BSTAND);
            a.getEquipment().setHelmet(DeepMobs.modelItem("submersible"));
        });
        DeepMobs.attr(s.bed, Attribute.SCALE, cfg("sub-scale", 2.6));
        for (Player p : l.getWorld().getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, s.bed);
        draw(s);
    }

    ItemDisplay part(Location l, String model) {
        Location f = l.clone(); f.setYaw(0); f.setPitch(0);
        return l.getWorld().spawn(f, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setItemModel(NamespacedKey.fromString("faultline:" + model));
            it.setItemMeta(m);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setViewRange(3f);
            d.setBrightness(new Display.Brightness(13, 13));
        });
    }

    void remove(Patrol s) {
        for (Entity e : new Entity[]{s.body, s.prop, s.bar, s.hit, s.bed}) if (e != null && e.isValid()) e.remove();
        if (s.hit != null) byHit.remove(s.hit.getUniqueId());
        s.body = null; s.prop = null; s.bar = null; s.hit = null; s.bed = null;
    }

    void removeAll() {
        for (Patrol s : fleet) remove(s);
        torps.clear();
    }

    String summary() {
        StringBuilder sb = new StringBuilder("Patrol subs:");
        for (Patrol s : fleet) sb.append(" ").append(s.name.substring(0, 5)).append("=").append(s.out() ? s.state.name().toLowerCase() + " " + (int) s.hp + "hp" : "away");
        return sb.toString();
    }

    // =========================================================================================== every tick
    void tick() {
        ticks++;
        if (pl.world == null) return;
        if (ticks % 200 == 0) spawnAll();
        for (Patrol s : fleet) {
            if (!s.out()) continue;
            try { step(s); } catch (RuntimeException e) { if (ticks % 200 == 0) pl.getLogger().warning("[Halcyon] sub " + s.name + ": " + e); }
        }
        torpedoes();
    }

    void step(Patrol s) {
        s.t++;
        if (s.shotCd > 0) s.shotCd--;
        if (s.flash > 0 && --s.flash == 0) for (ItemDisplay d : new ItemDisplay[]{s.body, s.prop}) if (d != null) d.setGlowing(false);
        if (s.state == State.SINKING) { sink(s); return; }
        // the radar: anyone wanted in range is hunted
        if (s.t % 20 == 0 && s.state != State.RETURN) {
            Player p = radar(s);
            if (p != null) { s.state = State.HUNT; s.hunting = p.getUniqueId(); }
            else if (s.state == State.HUNT) { s.state = State.EXPLORE; s.hunting = null; s.goal = null; }
        }
        double maxHp = cfg("sub-health", 120);
        if (s.state != State.HUNT && s.state != State.DOCKED && (s.hp < maxHp * 0.4 || s.cargo >= 3)) s.state = State.RETURN;
        switch (s.state) {
            case DOCKED -> {
                s.hp = Math.min(maxHp, s.hp + 0.1);
                if (s.t % 600 == 0 || s.hp >= maxHp && s.t % 200 == 0 && random.nextInt(3) == 0) { s.state = State.EXPLORE; s.goal = null; s.cargo = 0; }
                drive(s, berth(s), 0.08, 1.5);
            }
            case EXPLORE -> {
                if (s.goal == null || s.pos.distanceSquared(s.goal) < 16 || s.stuck > 60) { s.goal = exploreGoal(s); s.stuck = 0; }
                if (s.goal == null) { s.state = State.RETURN; break; }
                drive(s, s.goal, cfg("sub-speed", 0.2), 4);
                if (s.t % 40 == 0) survey(s);
            }
            case SURVEY, CAPTURE, ATTACK -> work(s);
            case HUNT -> hunt(s);
            case RETURN -> {
                Location b = berth(s);
                drive(s, b, cfg("sub-speed", 0.2), 1.5);
                if (s.pos.distanceSquared(b) < 4) { s.state = State.DOCKED; s.t = 1; if (s.cargo > 0) say(s, s.cargo + " specimens delivered to the Aquarium."); s.cargo = 0; }
            }
            default -> { }
        }
        draw(s);
    }

    /** Steer toward a spot: eased velocity, nose follows, over rock and never into the dome. */
    void drive(Patrol s, Location goal, double speed, double slowAt) {
        Vector want = goal.toVector().subtract(s.pos.toVector());
        double d = want.length();
        if (d < 0.05) { s.vel.multiply(0.8); move(s); return; }
        want.multiply(1 / d).multiply(Math.min(speed, speed * d / Math.max(0.1, slowAt)));
        s.vel.add(want.subtract(s.vel).multiply(0.08));
        move(s);
    }

    void move(Patrol s) {
        World w = s.pos.getWorld();
        Location next = s.pos.clone().add(s.vel);
        Location ahead = s.pos.clone().add(s.vel.clone().normalize().multiply(3));
        if (s.vel.lengthSquared() > 1e-6 && (!loaded(ahead) || solid(ahead) || solid(ahead.clone().add(0, 1, 0)))) {
            s.vel.setY(Math.max(s.vel.getY(), 0.12)); // climb over it
            s.vel.setX(s.vel.getX() * 0.6); s.vel.setZ(s.vel.getZ() * 0.6);
            s.stuck++;
            next = s.pos.clone().add(s.vel);
        }
        if (!loaded(next) || solid(next)) { s.vel.multiply(-0.3); s.stuck++; return; }
        // keep out of the dome (a sub inside would sit in the air) and under the surface
        if (Halcyon.envelope(next.getX(), next.getY(), next.getZ(), 0.08) && !Halcyon.envelope(s.pos.getX(), s.pos.getY(), s.pos.getZ(), 0.08)) {
            Vector out = s.vel.clone().multiply(-1).setY(0);
            if (out.lengthSquared() > 1e-4) s.vel.add(out.normalize().multiply(0.05));
            s.vel.setY(Math.max(s.vel.getY(), 0.05));
            return;
        }
        if (next.getY() > DeepGenerator.SEA - 2) next.setY(DeepGenerator.SEA - 2);
        s.pos = next;
        double hs = Math.hypot(s.vel.getX(), s.vel.getZ());
        if (hs > 0.02) {
            float wy = (float) Math.toDegrees(Math.atan2(-s.vel.getX(), s.vel.getZ()));
            s.yaw += (float) Math.IEEEremainder(wy - s.yaw, 360) * 0.12f;
        }
        float wp = (float) Math.max(-25, Math.min(25, Math.toDegrees(Math.atan2(-s.vel.getY(), Math.max(0.05, hs))) * 0.7));
        s.pitch += (wp - s.pitch) * 0.1f;
        s.spin += 6 + s.vel.length() * 60;
    }

    boolean loaded(Location l) { return l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4); }

    static boolean solid(Location l) { return l.getBlock().getType().isSolid(); }

    Location exploreGoal(Patrol s) {
        World w = pl.world;
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = 70 + random.nextDouble() * (cfg("explore-radius", 300) - 70);
            int x = (int) (Halcyon.HX + Math.cos(a) * r), z = (int) (Halcyon.HZ + Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int floor = w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
            int y = Math.min(DeepGenerator.SEA - 6, floor + 6 + random.nextInt(30));
            Location l = new Location(w, x + 0.5, y, z + 0.5);
            if (l.getBlock().getType() == Material.WATER) return l;
        }
        return null;
    }

    // ---------------------------------------------------------------------------- surveying, capturing, attacking creatures
    void survey(Patrol s) {
        if (pl.mobs == null) return;
        DeepMobs.Beast best = null;
        double bd = 24 * 24;
        for (DeepMobs.Beast m : pl.mobs.live.values()) {
            if (!m.body.isValid() || !m.body.getWorld().equals(s.pos.getWorld())) continue;
            double d = m.body.getLocation().distanceSquared(s.pos);
            if (d < bd) { bd = d; best = m; }
        }
        if (best == null) return;
        s.prey = best.body;
        s.state = switch (best.k.temper()) {
            case AGGRESSIVE -> State.ATTACK;
            case PASSIVE -> random.nextInt(2) == 0 ? State.CAPTURE : State.SURVEY;
            default -> State.SURVEY;
        };
        s.t = 1;
        if (s.state == State.ATTACK) say(s, "Hostile contact. Engaging.");
    }

    void work(Patrol s) {
        LivingEntity m = s.prey;
        if (m == null || !m.isValid() || m.isDead() || !m.getWorld().equals(s.pos.getWorld()) || s.t > 400) { s.state = State.EXPLORE; s.prey = null; return; }
        Location c = m.getLocation().add(0, m.getHeight() / 2, 0);
        double d = c.distance(s.pos);
        Vector off = s.pos.toVector().subtract(c.toVector());
        if (off.lengthSquared() < 1e-4) off = new Vector(1, 0, 0);
        Location stand = c.clone().add(off.normalize().multiply(s.state == State.ATTACK ? 9 : 5));
        drive(s, stand, cfg("sub-speed", 0.2), 3);
        World w = s.pos.getWorld();
        switch (s.state) {
            case SURVEY -> {
                if (d < 9 && s.t % 4 == 0) beam(s, c, Particle.END_ROD);
                if (s.t == 120) { say(s, "Sample logged: " + ChatColor.stripColor(m.getCustomName() == null ? "unknown" : m.getCustomName()) + "."); s.state = State.EXPLORE; }
            }
            case CAPTURE -> {
                if (d < 8) {
                    if (s.t % 3 == 0) beam(s, c, Particle.GLOW);
                    Vector pull = s.pos.toVector().subtract(c.toVector());
                    if (pull.lengthSquared() > 4) Edition.vel(m, pull.normalize().multiply(0.15));
                    if (s.t % 20 == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.NEUTRAL, 1f, 1.6f);
                    if (d < 4 || s.t > 200) {
                        w.spawnParticle(Particle.BUBBLE_POP, c, 30, 0.6, 0.6, 0.6, 0.1);
                        w.playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.NEUTRAL, 1f, 1.4f);
                        say(s, "Specimen captured: " + ChatColor.stripColor(m.getCustomName() == null ? "creature" : m.getCustomName()) + ".");
                        m.remove();
                        s.cargo++;
                        s.state = State.EXPLORE;
                    }
                }
            }
            case ATTACK -> { if (d < 22 && s.shotCd <= 0) fire(s, c, m.getUniqueId()); }
            default -> { }
        }
    }

    void beam(Patrol s, Location to, Particle p) {
        Vector d = to.toVector().subtract(s.pos.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.multiply(1 / len);
        for (double k = 0; k < len; k += 0.6) s.pos.getWorld().spawnParticle(p, s.pos.clone().add(d.clone().multiply(k)), 1, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------------------- radar and hunting
    Player radar(Patrol s) {
        double r = cfg("radar-radius", 400);
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : s.pos.getWorld().getPlayers()) {
            if (city.wanted(p) <= 0 || p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (Halcyon.inside(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())) continue; // the guards' business
            double dc = p.getLocation().distanceSquared(city.center());
            if (dc > r * r) continue;
            double d = p.getLocation().distanceSquared(s.pos);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    void hunt(Patrol s) {
        Player p = s.hunting == null ? null : Bukkit.getPlayer(s.hunting);
        if (p == null || city.wanted(p) <= 0 || !p.getWorld().equals(s.pos.getWorld())) { s.state = State.EXPLORE; s.hunting = null; return; }
        Location c = p.getLocation().add(0, 1, 0);
        Vector off = s.pos.toVector().subtract(c.toVector());
        if (off.lengthSquared() < 1e-4) off = new Vector(1, 0.3, 0);
        double ang = Math.toRadians(s.id * 90 + s.t * 0.6);                                // they spread out round you
        Vector ring = new Vector(Math.cos(ang), 0.25, Math.sin(ang)).multiply(cfg("hunt-distance", 13));
        Location stand = c.clone().add(ring);
        drive(s, stand, cfg("sub-hunt-speed", 0.3), 3);
        double d = c.distance(s.pos);
        if (s.t % 60 == 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.HOSTILE, 1.2f, 1.8f);
            p.sendActionBar(Component.text("RADAR LOCK: " + s.name + " (" + (int) d + " m)", NamedTextColor.RED));
        }
        if (d < 28 && s.shotCd <= 0 && c.getBlock().isLiquid()) fire(s, c, p.getUniqueId());
        else if (d < 28 && s.shotCd <= 0 && s.t % 40 == 0) fire(s, c, p.getUniqueId()); // out of the water: still, less often
    }

    /** Someone was made wanted: every sub on patrol turns toward them (HalcyonCity.crime). */
    void alarm(Player p) {
        if (city.wanted(p) <= 0 || Halcyon.inside(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())) return;
        boolean any = false;
        for (Patrol s : fleet) if (s.out() && s.state != State.SINKING) { s.state = State.HUNT; s.hunting = p.getUniqueId(); any = true; }
        if (any) p.sendMessage(ChatColor.RED + "[Halcyon Radar] " + ChatColor.GRAY + "Contact identified. All patrol units, intercept.");
    }

    // ---------------------------------------------------------------------------- torpedoes
    void fire(Patrol s, Location at, UUID target) {
        s.shotCd = (int) cfg("torpedo-cooldown-ticks", 50);
        Torpedo t = new Torpedo(s);
        t.at = s.pos.clone();
        Vector d = at.toVector().subtract(t.at.toVector());
        if (d.lengthSquared() < 1e-4) return;
        t.dir = d.normalize();
        t.target = target;
        torps.add(t);
        s.pos.getWorld().playSound(s.pos, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.HOSTILE, 1.5f, 1.4f);
    }

    void torpedoes() {
        for (var it = torps.iterator(); it.hasNext(); ) {
            Torpedo t = it.next();
            World w = t.at.getWorld();
            Entity target = t.target == null ? null : Bukkit.getEntity(t.target);
            if (target != null && target.isValid() && target.getWorld().equals(w)) {
                Vector want = target.getLocation().add(0, target.getHeight() / 2, 0).toVector().subtract(t.at.toVector());
                if (want.lengthSquared() > 1e-4) t.dir = t.dir.multiply(0.85).add(want.normalize().multiply(0.15)).normalize();
            }
            double speed = cfg("torpedo-speed", 0.9);
            boolean boom = false;
            for (int k = 0; k < 2 && !boom; k++) {
                t.at.add(t.dir.clone().multiply(speed / 2));
                if (!loaded(t.at) || solid(t.at)) boom = true;
                for (Entity e : w.getNearbyEntities(t.at, 1.3, 1.3, 1.3)) {
                    if (!(e instanceof LivingEntity le) || e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(Citizens.TAG)) continue;
                    if (e instanceof Player p && (t.target == null || !p.getUniqueId().equals(t.target))) continue; // only who it was fired at
                    boom = true;
                    break;
                }
            }
            if (--t.life <= 0) boom = true;
            if (!boom) { w.spawnParticle(Particle.BUBBLE, t.at, 3, 0.1, 0.1, 0.1, 0.02); w.spawnParticle(Particle.GLOW, t.at, 1, 0, 0, 0, 0); continue; }
            it.remove();
            w.spawnParticle(Particle.EXPLOSION, t.at, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.BUBBLE_POP, t.at, 30, 1, 1, 1, 0.2);
            w.playSound(t.at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.2f, 1.3f);
            double dmg = cfg("torpedo-damage", 7);
            for (Entity e : w.getNearbyEntities(t.at, 2.5, 2.5, 2.5)) {
                if (!(e instanceof LivingEntity le) || e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(Citizens.TAG)) continue;
                if (e instanceof Player p && (t.target == null || !p.getUniqueId().equals(t.target) || p.getGameMode() == GameMode.CREATIVE)) continue;
                le.damage(dmg, t.from.hit != null && t.from.hit.isValid() ? t.from.hit : null);
            }
        }
    }

    // ---------------------------------------------------------------------------- getting hit, sinking
    @EventHandler(priority = EventPriority.HIGH)
    public void onHurt(EntityDamageEvent e) {
        Patrol s = byHit.get(e.getEntity().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        Player by = e instanceof EntityDamageByEntityEvent ed ? Citizens.playerBehind(ed.getDamager()) : null;
        if (by == null || s.state == State.SINKING) return;
        if (by.getGameMode() == GameMode.CREATIVE && HalcyonCity.exempt(by)) return;
        s.hp -= e.getFinalDamage();
        s.flash = 4;
        for (ItemDisplay d : new ItemDisplay[]{s.body, s.prop}) if (d != null) { d.setGlowColorOverride(org.bukkit.Color.fromRGB(255, 70, 70)); d.setGlowing(true); }
        s.pos.getWorld().playSound(s.pos, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 0.6f, 1.6f);
        if (s.hp <= 0) { sinkStart(s, by); return; }
        city.crime(by, 2, "attacking a patrol sub");
        s.state = State.HUNT;
        s.hunting = by.getUniqueId();
    }

    void sinkStart(Patrol s, Player by) {
        s.hp = 0;
        s.state = State.SINKING;
        s.sinkT = 0;
        World w = s.pos.getWorld();
        w.playSound(s.pos, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.7f);
        w.playSound(s.pos, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 2f, 0.6f);
        if (by != null) {
            city.crime(by, 3, "sinking " + s.name);
            pl.console("index stat " + by.getName() + " subs_sunk 1");
            pl.console("index discover " + by.getName() + " halcyon_patrol_sub");
            // salvage
            Location l = s.pos.clone();
            w.dropItemNaturally(l, new ItemStack(Material.IRON_INGOT, 4 + random.nextInt(5)));
            w.dropItemNaturally(l, new ItemStack(Material.COPPER_INGOT, 6 + random.nextInt(8)));
            if (random.nextDouble() < cfg("sub-salvage-bag-chance", 0.3)) pl.console("darkloot bag 1 " + by.getName());
        }
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(s.pos) < 120 * 120)
            p.sendMessage(ChatColor.DARK_AQUA + "[Halcyon Radio] " + ChatColor.RED + s.name + " is going down! " + s.pilot + " has bailed out.");
    }

    void sink(Patrol s) {
        s.sinkT++;
        World w = s.pos.getWorld();
        s.pitch += (35 - s.pitch) * 0.05f;
        s.yaw += 0.6f;
        Location next = s.pos.clone().add(0, -0.12, 0);
        if (loaded(next) && !solid(next.clone().add(0, -0.8, 0))) s.pos = next;
        if (s.sinkT % 3 == 0) w.spawnParticle(Particle.LARGE_SMOKE, s.pos.clone().add(0, 1, 0), 3, 0.4, 0.3, 0.4, 0.02);
        if (s.sinkT % 2 == 0) w.spawnParticle(Particle.BUBBLE_COLUMN_UP, s.pos, 6, 0.6, 0.4, 0.6, 0.05);
        if (s.sinkT % 40 == 0 && s.sinkT < 200) w.playSound(s.pos, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.HOSTILE, 1.5f, 0.5f);
        if (s.sinkT == 10) w.spawnParticle(Particle.BUBBLE_POP, s.pos.clone().add(0, 1.5, 0), 40, 0.5, 2, 0.5, 0.1); // the pilot's escape
        draw(s);
        if (s.sinkT > cfg("sub-wreck-seconds", 40) * 20) {
            remove(s);
            s.deadUntil = System.currentTimeMillis() + (long) (cfg("sub-respawn-minutes", 10) * 60000);
        }
    }

    // ---------------------------------------------------------------------------- drawing
    void draw(Patrol s) {
        Location l = s.pos.clone(); l.setYaw(0); l.setPitch(0);
        float sc = (float) cfg("sub-scale", 2.6);
        Quaternionf frame = new Quaternionf().rotationY((float) Math.toRadians(-s.yaw)).rotateX((float) Math.toRadians(s.pitch)).rotateY((float) Math.toRadians(-90));
        Quaternionf flip = new Quaternionf().rotationY((float) Math.PI);
        if (s.body != null && s.body.isValid()) {
            s.body.teleport(l);
            s.body.setInterpolationDelay(0);
            s.body.setTransformation(new Transformation(new Vector3f(), new Quaternionf(frame).mul(flip), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
        if (s.prop != null && s.prop.isValid()) {
            float[] pv = {-1.08f, 0f, 0f};
            List<DeepParts.Bone> bones = DeepParts.BONES.get("submersible");
            if (bones != null) for (DeepParts.Bone b : bones) if (b.name().equals("prop")) pv = new float[]{b.x(), b.y(), b.z()};
            Vector3f t = new Vector3f(pv[0], pv[1], pv[2]).mul(sc);
            frame.transform(t);
            s.prop.teleport(l);
            s.prop.setInterpolationDelay(0);
            s.prop.setTransformation(new Transformation(t, new Quaternionf(frame).mul(new Quaternionf().rotateX((float) Math.toRadians(s.spin))).mul(flip), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
        if (s.hit != null && s.hit.isValid()) s.hit.teleport(l.clone().add(0, -1, 0));
        if (s.bed != null && s.bed.isValid()) { Location b = l.clone().add(0, -0.6, 0); b.setYaw(s.yaw); s.bed.teleport(b); }
        if (s.bar != null && s.bar.isValid()) {
            s.bar.teleport(l.clone().add(0, 2.4, 0));
            if (s.t % 5 == 0 || s.state == State.SINKING) s.bar.text(barText(s));
        }
    }

    Component barText(Patrol s) {
        double max = cfg("sub-health", 120), f = Math.max(0, s.hp / max);
        int n = 12, full = (int) Math.round(f * n);
        NamedTextColor c = f > 0.6 ? NamedTextColor.GREEN : f > 0.3 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        String what = switch (s.state) {
            case DOCKED -> "berthed"; case EXPLORE -> "exploring"; case SURVEY -> "surveying"; case CAPTURE -> "capturing";
            case ATTACK -> "engaging"; case HUNT -> "PURSUIT"; case RETURN -> "returning"; case SINKING -> "SINKING";
        };
        return Component.text(s.name, NamedTextColor.AQUA).append(Component.text("  " + what, s.state == State.HUNT || s.state == State.SINKING ? NamedTextColor.RED : NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("|".repeat(full), c)).append(Component.text("|".repeat(n - full), NamedTextColor.DARK_GRAY))
                .append(Component.text("  " + (int) Math.ceil(Math.max(0, s.hp)) + "/" + (int) max, NamedTextColor.WHITE));
    }

    void say(Patrol s, String text) {
        for (Player p : s.pos.getWorld().getPlayers()) if (p.getLocation().distanceSquared(s.pos) < 40 * 40)
            p.sendMessage(ChatColor.DARK_AQUA + "[" + s.name + "] " + ChatColor.GRAY + text);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        Patrol s = byHit.get(e.getRightClicked().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text(s.name + ", piloted by " + s.pilot + ". Subs are sold at the dock office.", NamedTextColor.AQUA));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (e.getEntity().getScoreboardTags().contains(HIT_TAG)) e.setCancelled(true); }
}
