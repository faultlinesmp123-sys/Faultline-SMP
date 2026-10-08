package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * THE GHOST SHIP, "The Wailing Mary": on dark nights a spectral galleon drifts out of the fog near someone sailing the
 * ocean, crewed by Ghost Pirates. Cannonballs pass straight through her and nobody can board her... unless someone
 * nearby holds a LANTERN OF SOULS (FaultlineItems): then she turns solid and drops anchor. Board her, beat her
 * captain, CAPTAIN HOLLOW, and her hold is yours. Without the lantern for 20 s she fades out again (anyone on her deck
 * falls through). She's gone at dawn. Config pirates.ghost-ship:. Admin: /ship pirates ghost.
 * Java: pale glass-and-bleached-wood blocks; Bedrock: the galleon_ghost model. Captain Hollow is a "wild" model part
 * (ItemDisplay for Java, armor stand helmet for Bedrock) on an invisible wither skeleton.
 */
final class GhostShip implements Listener {

    static final String CAPTAIN_TAG = "faultline_ghost_captain", FX_TAG = "faultline_ghost_fx";
    static final Quaternionf Q0 = new Quaternionf().rotationY((float) Math.toRadians(-90)), FLIP = new Quaternionf().rotationY((float) Math.PI);

    final FaultlineShips pl;
    final Pirates pirates;
    final NamespacedKey gearKey = new NamespacedKey("faultlineitems", "gear");
    Ship ship;
    Pirates.Brain brain;
    long lanternAt = -1, born, solidAt = -1;
    LivingEntity captain;
    ItemDisplay capModel;
    ArmorStand capStand;
    BossBar bar;
    final Set<UUID> fighters = new HashSet<>();
    boolean beaten;
    long nextAbility;

    GhostShip(FaultlineShips pl, Pirates pirates) { this.pl = pl; this.pirates = pirates; }

    double cfg(String k, double def) { return pl.getConfig().getDouble("pirates.ghost-ship." + k, def); }

    static boolean night(World w) { long t = w.getTime(); return t >= 13000 && t < 23000; }

    boolean lantern(Player p) {
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            if (it == null || !it.hasItemMeta()) continue;
            if ("lantern_of_souls".equals(it.getItemMeta().getPersistentDataContainer().get(gearKey, PersistentDataType.STRING))) return true;
        }
        return false;
    }

    static Material ghost(ShipType.Need n) {
        return switch (n) {
            case PLANKS -> Material.LIGHT_BLUE_STAINED_GLASS;
            case LOG -> Material.PALE_OAK_LOG;
            case FENCE -> Material.PALE_OAK_FENCE;
            case STAIRS -> Material.PALE_OAK_STAIRS;
            case WOOL, SAIL_BLACK -> Material.CYAN_STAINED_GLASS;
            case SAIL_WHITE -> Material.WHITE_STAINED_GLASS;
            case CHEST -> Material.BARREL;
            case LANTERN -> Material.SOUL_LANTERN;
            case LADDER -> Material.LADDER;
            case TRAPDOOR, HATCH -> Material.PALE_OAK_TRAPDOOR;
            case PANE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case CANNON -> Material.IRON_BLOCK;
        };
    }

    // =====================================================================================================
    //  appearing
    // =====================================================================================================
    /** Rolled with the skeleton ships: true if she appeared. */
    boolean roll(Player p, Ship on) {
        if (ship != null || !pl.getConfig().getBoolean("pirates.ghost-ship.enabled", true) || !night(p.getWorld())) return false;
        double chance = cfg("chance", 0.03) * (p.getWorld().hasStorm() ? 2 : 1); // likelier in the rain (fog)
        if (pirates.rnd.nextDouble() >= chance) return false;
        return spawn(p, on) != null;
    }

    Ship spawn(Player p, Ship on) {
        if (ship != null) return null;
        double base = pirates.rnd.nextDouble() * 360;
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(base + i * 22.5), r = cfg("spawn-distance", 55) + (i % 2) * 15;
            double x = on.x - Math.sin(a) * r, z = on.z + Math.cos(a) * r;
            float yaw = Pirates.bearing(x, z, on.x, on.z);
            Ship s = new Ship(pl, UUID.randomUUID(), ShipType.GALLEON, Pirates.OWNER);
            s.world = on.world;
            s.x = Math.floor(x) + 0.5; s.y = on.y; s.z = Math.floor(z) + 0.5;
            s.yaw = ((Math.round(yaw / 90f) * 90f) % 360 + 360) % 360;
            if (!s.clear(s.x, s.z, s.yaw, true, null)) continue;
            for (int k = 0; k < s.blocks.length; k++) s.blocks[k] = s.blockFor(k, ghost(s.type.cells.get(k).need()));
            s.recount();
            Pirates.Brain b = pirates.new Brain(s, null, false);
            b.ghost = true;
            s.ai = b;
            s.hp = s.maxHp();
            s.name = "The Wailing Mary";
            s.cargo = loot();
            pirates.brains.put(s.id, b);
            pl.ships.put(s.id, s);
            s.spawn();
            ship = s; brain = b; born = pl.now; beaten = false; fighters.clear();
            crew();
            for (Player q : s.world().getPlayers()) if (q.getLocation().distanceSquared(p.getLocation()) < 150 * 150) {
                q.sendTitle("", ChatColor.AQUA + "" + ChatColor.ITALIC + "A ghostly ship drifts out of the fog...", 20, 60, 20);
                q.playSound(q.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.HOSTILE, 1f, 0.6f);
                q.sendMessage(ChatColor.AQUA + "The Wailing Mary " + ChatColor.GRAY + "sails out of the dark. Nothing touches her... unless you carry a " + ChatColor.AQUA + "Lantern of Souls" + ChatColor.GRAY + ".");
            }
            return s;
        }
        return null;
    }

    void crew() {
        Ship s = ship;
        if (s.seats == null) return;
        for (int i = 0; i < s.seats.length; i++) {
            if (s.seats[i] == null || !s.seats[i].isValid() || !s.seats[i].getPassengers().isEmpty()) continue;
            LivingEntity m = pirates.spawnMob(Pirates.Kind.WRAITH, s.seatLoc(i), brain);
            if (m != null) s.seats[i].addPassenger(m);
        }
    }

    ItemStack[] loot() {
        List<ItemStack> l = new ArrayList<>();
        Random r = pirates.rnd;
        l.add(new ItemStack(Material.GOLD_BLOCK, 2 + r.nextInt(4)));
        l.add(new ItemStack(Material.DIAMOND, 4 + r.nextInt(6)));
        l.add(new ItemStack(Material.EMERALD, 6 + r.nextInt(10)));
        l.add(new ItemStack(Material.ECHO_SHARD, 1 + r.nextInt(3)));
        if (r.nextDouble() < 0.5) l.add(new ItemStack(Material.HEART_OF_THE_SEA));
        if (r.nextDouble() < 0.3) l.add(new ItemStack(Material.NETHERITE_SCRAP, 1 + r.nextInt(2)));
        if (r.nextDouble() < 0.2) l.add(new ItemStack(Material.TOTEM_OF_UNDYING));
        ItemStack[] out = new ItemStack[ShipType.GALLEON.cargo];
        for (int i = 0; i < l.size() && i < out.length; i++) out[r.nextInt(out.length)] = l.get(i);
        return out;
    }

    // =====================================================================================================
    //  her mind (instead of a skeleton ship's)
    // =====================================================================================================
    void think(Pirates.Brain b, long now) {
        Ship s = b.ship;
        Pirates.AiInput in = b.input;
        in.clear();
        if (!night(s.world()) && !s.anchored) { fade("The Wailing Mary fades with the dawn..."); return; }
        if (now - born > cfg("lifetime-minutes", 15) * 1200 && captain == null) { fade("The Wailing Mary fades back into the fog."); return; }
        Player t = null; double td = cfg("sight", 160);
        boolean lanternNear = false;
        for (Player p : s.world().getPlayers()) {
            if (!pirates.fighting(p)) continue;
            double d = p.getLocation().distance(new Location(s.world(), s.x, p.getLocation().getY(), s.z));
            if (d < td) { td = d; t = p; }
            if (lantern(p) && d < cfg("lantern-range", 30)) lanternNear = true;
        }
        if (lanternNear) lanternAt = now;
        boolean wantSolid = lanternAt >= 0 && now - lanternAt < cfg("solid-seconds", 20) * 20;
        if (s.anchored) {
            if (!wantSolid && !beaten) { dematerialize(); return; }
            pirates.boarded(b, now);
            if (captain == null && !beaten) summonCaptain();
            return;
        }
        if (s.anchorStep >= 0) return;
        if (wantSolid) { // the lantern's light makes her solid: she heaves to and anchors
            double max = s.type.speed * pl.getConfig().getDouble("ships." + s.type.size + ".speed-multiplier", 1.0);
            s.speed = Ship.brake(s.speed, max);
            s.turnRate = 0;
            if (Math.abs(s.speed) <= max * 0.3 && now % 10 == 0) {
                s.dropAnchor(null);
                for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(s.center()) < 60 * 60)
                    p.sendActionBar(Component.text("The Lantern of Souls makes the Wailing Mary solid: board her!", NamedTextColor.AQUA));
            }
            return;
        }
        if (t == null) { in.f = true; return; }
        // she circles whoever she's haunting, just out of reach
        float bearing = Pirates.bearing(s.x, s.z, t.getLocation().getX(), t.getLocation().getZ());
        float want = td > 40 ? bearing : td < 22 ? bearing + 180 : bearing + 90;
        float diff = Pirates.wrap(want - s.yaw);
        in.r = diff > 4; in.l = diff < -4; in.f = true;
    }

    void dematerialize() {
        Ship s = ship;
        if (captain != null) { removeCaptain(); }
        s.raiseAnchor(null);
        brain.boarded = false;
        for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(s.center()) < 50 * 50)
            p.sendActionBar(Component.text("Without the lantern's light, the Wailing Mary turns to mist...", NamedTextColor.GRAY));
        for (Map.Entry<UUID, Pirates.Crew> e : pirates.crew.entrySet()) {
            if (e.getValue().home != brain) continue;
            Entity m = Bukkit.getEntity(e.getKey());
            if (!(m instanceof LivingEntity le) || !le.isValid() || le.isInsideVehicle()) continue;
            for (ArmorStand seat : s.seats) if (seat != null && seat.getPassengers().isEmpty()) { seat.addPassenger(le); break; }
        }
    }

    void fade(String msg) {
        Ship s = ship;
        if (s == null) return;
        for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(s.center()) < 100 * 100) p.sendMessage(ChatColor.AQUA + "" + ChatColor.ITALIC + msg);
        s.world().spawnParticle(Particle.SOUL, s.center(), 120, s.radius() / 2, 3, s.radius() / 2, 0.02);
        removeCaptain();
        pirates.discard(s);
        ship = null; brain = null; lanternAt = -1;
    }

    // =====================================================================================================
    //  Captain Hollow
    // =====================================================================================================
    void summonCaptain() {
        Ship s = ship;
        Location at = s.center().add(0, 0.2, 0);
        captain = s.world().spawn(at, WitherSkeleton.class, c -> {
            c.addScoreboardTag(Pirates.TAG);
            c.addScoreboardTag(CAPTAIN_TAG);
            c.setCustomName(ChatColor.AQUA + "" + ChatColor.BOLD + "Captain Hollow");
            c.setCustomNameVisible(false);
            c.setPersistent(false);
            c.setRemoveWhenFarAway(false);
            c.setSilent(true);
            c.setInvisible(true);
            c.getEquipment().clear();
            attr(c, Attribute.MAX_HEALTH, cfg("captain-health", 400));
            c.setHealth(cfg("captain-health", 400));
            attr(c, Attribute.ATTACK_DAMAGE, cfg("captain-damage", 9));
            attr(c, Attribute.MOVEMENT_SPEED, 0.3);
            attr(c, Attribute.KNOCKBACK_RESISTANCE, 0.8);
            attr(c, Attribute.FOLLOW_RANGE, 32);
            c.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        });
        ItemStack model = partItem();
        capModel = s.world().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(model);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setPersistent(false);
            d.addScoreboardTag(FX_TAG);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(org.bukkit.Color.fromRGB(90, 250, 255));
        });
        capStand = s.world().spawn(at, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
            a.addScoreboardTag(FX_TAG);
            a.setInvisible(true);
            a.setGravity(false);
            a.setMarker(false);
            a.getEquipment().setHelmet(model.clone());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try { a.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (RuntimeException ignored) { }
            }
        });
        AttributeInstance sc = capStand.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(1.3);
        for (Player p : s.world().getPlayers()) if (FaultlineShips.bedrock(p)) p.showEntity(pl, capStand);
        bar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Captain Hollow", BarColor.BLUE, BarStyle.SEGMENTED_10);
        s.world().playSound(at, Sound.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 3f, 0.6f);
        for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(at) < 48 * 48)
            p.sendMessage(ChatColor.AQUA + "[Captain Hollow] " + ChatColor.WHITE + "\"Another lantern... another soul for my crew.\"");
        nextAbility = pl.now + 60;
    }

    static ItemStack partItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "wild/ghost_captain"));
        it.setItemMeta(m);
        return it;
    }

    void captainTick(long now) {
        if (captain == null) return;
        if (!captain.isValid() || captain.isDead()) return;
        Location l = captain.getLocation();
        float yaw = l.getYaw();
        double k = 1.3;
        double[] meta = WildParts.of("ghost_captain");
        Location pos = l.clone().add(0, Math.sin(now * 0.1) * 0.06, 0);
        Location dl = pos.clone(); dl.setYaw(0); dl.setPitch(0);
        capModel.teleport(dl);
        Quaternionf D = new Quaternionf().rotationY((float) Math.toRadians(-yaw)).mul(Q0);
        Vector3f off = new Vector3f((float) (meta[1] * k), (float) (meta[2] * k), (float) (meta[3] * k));
        D.transform(off);
        float s = (float) (meta[0] * k);
        capModel.setInterpolationDelay(0);
        capModel.setTransformation(new Transformation(off, new Quaternionf(D).mul(FLIP), new Vector3f(s, s, s), new Quaternionf()));
        Location sl = pos.clone(); sl.setPitch(0);
        capStand.teleport(sl);
        if (now % 4 == 0) l.getWorld().spawnParticle(Particle.SOUL, l.clone().add(0, 0.6, 0), 2, 0.3, 0.4, 0.3, 0.01);
        List<Player> near = new ArrayList<>();
        for (Player p : l.getWorld().getPlayers()) if (pirates.fighting(p) && p.getLocation().distanceSquared(l) < 30 * 30) { near.add(p); fighters.add(p.getUniqueId()); }
        AttributeInstance mh = captain.getAttribute(Attribute.MAX_HEALTH);
        double max = mh == null ? 400 : mh.getValue();
        bar.setProgress(Math.max(0, Math.min(1, captain.getHealth() / max)));
        for (Player p : l.getWorld().getPlayers()) {
            boolean in = p.getLocation().distanceSquared(l) < 48 * 48;
            if (in && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            else if (!in && bar.getPlayers().contains(p)) bar.removePlayer(p);
        }
        if (near.isEmpty() || now < nextAbility || !(captain instanceof org.bukkit.entity.Mob mob)) return;
        nextAbility = now + 80 + pirates.rnd.nextInt(40);
        Player t = near.get(pirates.rnd.nextInt(near.size()));
        mob.setTarget(t);
        switch (pirates.rnd.nextInt(4)) {
            case 0 -> { // blink behind you
                Location behind = t.getLocation().clone().add(t.getLocation().getDirection().setY(0).normalize().multiply(-2));
                behind.setYaw(t.getLocation().getYaw());
                l.getWorld().spawnParticle(Particle.SOUL, l, 30, 0.4, 0.8, 0.4, 0.05);
                captain.teleport(behind);
                l.getWorld().playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.5f, 0.6f);
            }
            case 1 -> { // a spectral sweep
                Vector f = l.getDirection().setY(0);
                if (f.lengthSquared() < 1e-4) f = new Vector(1, 0, 0);
                f.normalize();
                l.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2f, 0.5f);
                for (int a = -60; a <= 60; a += 15) {
                    Vector d = f.clone().rotateAroundY(Math.toRadians(a));
                    for (double r = 1; r <= 4.5; r += 0.9) l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(d.clone().multiply(r)).add(0, 1, 0), 1, 0, 0, 0, 0);
                }
                for (Player p : near) {
                    Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                    if (to.lengthSquared() > 5 * 5 || to.lengthSquared() > 0.01 && to.normalize().dot(f) < 0.5) continue;
                    p.damage(cfg("sweep-damage", 9), captain);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
                }
            }
            case 2 -> { // the drowned dead rise to his call
                for (int i = 0; i < 2; i++) pirates.spawnMob(Pirates.Kind.WRAITH, l.clone().add(pirates.rnd.nextGaussian() * 2, 0, pirates.rnd.nextGaussian() * 2), brain);
                l.getWorld().playSound(l, Sound.ENTITY_VEX_CHARGE, SoundCategory.HOSTILE, 2f, 0.5f);
            }
            default -> { // soul chains: drags you in
                Vector pull = l.toVector().subtract(t.getLocation().toVector());
                t.setVelocity(pull.setY(0).normalize().multiply(1.3).setY(0.4));
                for (double d = 0; d < 1; d += 0.08) l.getWorld().spawnParticle(Particle.SOUL, t.getLocation().clone().add(pull.clone().multiply(d)).add(0, 1, 0), 1, 0, 0, 0, 0);
                t.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0));
            }
        }
    }

    void removeCaptain() {
        if (captain != null && captain.isValid()) captain.remove();
        if (capModel != null && capModel.isValid()) capModel.remove();
        if (capStand != null && capStand.isValid()) capStand.remove();
        if (bar != null) bar.removeAll();
        captain = null; capModel = null; capStand = null;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(CAPTAIN_TAG)) return;
        e.getDrops().clear();
        e.setDroppedExp(400);
        Location l = e.getEntity().getLocation();
        l.getWorld().spawnParticle(Particle.SOUL, l.clone().add(0, 1, 0), 80, 0.6, 1, 0.6, 0.06);
        l.getWorld().playSound(l, Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 2f, 1.3f);
        Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Captain Hollow " + ChatColor.YELLOW + "has been laid to rest! The Wailing Mary's hold is free for the taking.");
        for (UUID u : fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.getWorld().equals(l.getWorld())) continue;
            run("givemythicbag " + (int) cfg("rewards.mythic-bags", 2) + " " + p.getName());
            run("givegoodiebag " + (int) cfg("rewards.goodie-bags", 2) + " " + p.getName());
            p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(Material.DIAMOND, 4 + pirates.rnd.nextInt(5)));
            if (pirates.rnd.nextDouble() < cfg("rewards.disc-chance", 0.25)) run("givedisc pirates " + p.getName());
            run("index stat " + p.getName() + " ghost_ships 1");
            run("index discover " + p.getName() + " ghost_captain");
        }
        if (capModel != null && capModel.isValid()) capModel.remove();
        if (capStand != null && capStand.isValid()) capStand.remove();
        if (bar != null) bar.removeAll();
        captain = null; capModel = null; capStand = null;
        beaten = true;
        if (brain != null) brain.beaten = true;
    }

    void run(String cmd) {
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (RuntimeException ignored) { }
    }

    /** Bedrock players see (and so hit) the captain's stand, not the invisible skeleton: pass their hits on to him. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onStandHit(EntityDamageEvent e) {
        if (capStand == null || !e.getEntity().equals(capStand)) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent ee) || captain == null || !captain.isValid() || captain.isDead()) return;
        org.bukkit.entity.Entity by = ee.getDamager();
        if (by instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof org.bukkit.entity.Entity sh) { pr.remove(); by = sh; }
        if (by != null && !by.equals(captain)) captain.damage(Math.max(0.5, e.getDamage()), by);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStandClick(PlayerInteractEntityEvent e) { if (capStand != null && e.getRightClicked().equals(capStand)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStandRob(PlayerArmorStandManipulateEvent e) { if (capStand != null && e.getRightClicked().equals(capStand)) e.setCancelled(true); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (capStand != null && capStand.isValid()) { if (FaultlineShips.bedrock(e.getPlayer())) e.getPlayer().showEntity(pl, capStand); else e.getPlayer().hideEntity(pl, capStand); }
    }

    void tick(long now) {
        if (ship == null) return;
        if (!pl.ships.containsKey(ship.id)) { removeCaptain(); ship = null; brain = null; return; }
        if (now % 6 == 0 && ship.spawned()) {
            Location c = ship.center();
            ship.world().spawnParticle(Particle.SOUL, c.clone().add(0, 3, 0), 6, ship.radius() / 2, 3, ship.radius() / 3, 0.01);
            ship.world().spawnParticle(Particle.WHITE_ASH, c, 20, ship.radius() / 2, 4, ship.radius() / 2, 0.01);
        }
        if (now % 100 == 0 && ship.spawned()) ship.world().playSound(ship.center(), Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, SoundCategory.HOSTILE, 2f, 0.6f);
        captainTick(now);
    }

    void shutdown() { removeCaptain(); }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }
}
