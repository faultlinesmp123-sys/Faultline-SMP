package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.*;

/**
 * HAUNTED VILLAGES (Items 1.2.78, Halloween, config haunted-villages:). In the Halloween window (start/end, default
 * 10-01..11-02 like FaultlineCosmetics' season, zone America/New_York), every check-minutes at night each survival player
 * near a village in the Overworld rolls `chance`: that village is HAUNTED. Spirits rise for `minutes` (6): a boss bar,
 * soul fog, the villagers cower inside (Slowness + glowing so you can find them), three waves of RESTLESS SPIRITS (vexes
 * without swords, tag faultline_haunt_spirit) and HAUNTED VILLAGERS (zombie villagers with carved-pumpkin heads,
 * faultline_haunt_villager), then the LANTERN WRAITH (a wither skeleton with a jack o'lantern head, 160 hp, scale 1.4,
 * faultline_lantern_wraith: Soul Flames, a Wail (blindness + it calls spirits), Blink behind you). Kill it to lift the
 * haunting: everyone who fought gets reward-candy Candy (FaultlineCosmetics: `cosmetic tokens <p> halloween n`), a
 * Goodie Bag and Index stat haunted_villages. Dawn or the timer = the spirits fade, no reward. Nothing in the world is
 * changed (no blocks), every spirit is non-persistent. Admins: /fweather haunt [player] | haunt stop.
 */
final class HauntedVillage implements Listener {

    static final String SPIRIT = "faultline_haunt_spirit", VILLAGER = "faultline_haunt_villager", WRAITH = "faultline_lantern_wraith", ANY = "faultline_haunt";

    private final FaultlineItems plugin;
    private final Random random = new Random();

    final class Haunt {
        Location center;
        World world;
        long endsAt;
        int wave, tick;
        final Set<UUID> mobs = new HashSet<>(), fighters = new HashSet<>(), cowering = new HashSet<>();
        LivingEntity wraith;
        BossBar bar;
        boolean forced;
    }

    Haunt haunt;
    private long nextCheck;

    HauntedVillage(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(ANY)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("haunted-villages." + k, d); }

    LocalDate today() {
        try { return LocalDate.now(ZoneId.of(plugin.getConfig().getString("haunted-villages.zone", "America/New_York"))); }
        catch (RuntimeException e) { return LocalDate.now(); }
    }

    static boolean inWindow(LocalDate d, String start, String end) {
        try {
            MonthDay s = MonthDay.parse("--" + start), e = MonthDay.parse("--" + end), t = MonthDay.from(d);
            return s.isBefore(e) || s.equals(e) ? !t.isBefore(s) && !t.isAfter(e) : !t.isBefore(s) || !t.isAfter(e);
        } catch (RuntimeException ex) { return false; }
    }

    boolean season() {
        return inWindow(today(), plugin.getConfig().getString("haunted-villages.start", "10-01"), plugin.getConfig().getString("haunted-villages.end", "11-02"));
    }

    static boolean night(World w) { long t = w.getTime(); return t > 13000 && t < 23000; }

    /** The nearest village to a spot within ~10 chunks, or null. */
    static Location village(Location at) {
        try {
            var r = at.getWorld().locateNearestStructure(at, org.bukkit.generator.structure.StructureType.JIGSAW, 10, false);
            if (r != null && r.getStructure().key().value().startsWith("village")) return r.getLocation();
        } catch (RuntimeException | LinkageError ignored) { }
        try {
            for (var s : new org.bukkit.generator.structure.Structure[]{org.bukkit.generator.structure.Structure.VILLAGE_PLAINS,
                    org.bukkit.generator.structure.Structure.VILLAGE_DESERT, org.bukkit.generator.structure.Structure.VILLAGE_SAVANNA,
                    org.bukkit.generator.structure.Structure.VILLAGE_SNOWY, org.bukkit.generator.structure.Structure.VILLAGE_TAIGA}) {
                var r = at.getWorld().locateNearestStructure(at, s, 6, false);
                if (r != null) return r.getLocation();
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return null;
    }

    private void tick() {
        try { tick0(); } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[Haunted] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    private void tick0() {
        if (haunt != null) { run(haunt); return; }
        long now = System.currentTimeMillis();
        if (now < nextCheck) return; // 1.2.95: the clock first
        nextCheck = now + (long) (cfg("check-minutes", 10) * 60_000);
        if (!plugin.getConfig().getBoolean("haunted-villages.enabled", true) || !season()) return;
        List<Player> ps = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getGameMode() == GameMode.SURVIVAL && p.getWorld().getEnvironment() == World.Environment.NORMAL && FaultlineItems.surfaceWorld(p.getWorld()) && night(p.getWorld())) ps.add(p);
        Collections.shuffle(ps);
        for (Player p : ps) {
            if (random.nextDouble() >= cfg("chance", 0.15)) continue;
            Location v = village(p.getLocation());
            if (v == null || v.distanceSquared(p.getLocation()) > 160 * 160) continue;
            start(v, false);
            return;
        }
    }

    /** Haunts the village at (or nearest to) this spot. */
    boolean start(Location at, boolean forced) {
        if (haunt != null) return false;
        Location v = at.getWorld().getEnvironment() == World.Environment.NORMAL ? village(at) : null;
        if (v == null || v.distanceSquared(at) > 200 * 200) { if (!forced) return false; v = at; }
        Location c = v.clone();
        c.setY(c.getWorld().getHighestBlockYAt(c) + 1);
        Haunt h = new Haunt();
        h.center = c; h.world = c.getWorld(); h.forced = forced;
        h.endsAt = System.currentTimeMillis() + (long) (cfg("minutes", 6) * 60_000);
        h.bar = Bukkit.createBossBar(ChatColor.DARK_PURPLE + "☠ The village is haunted", BarColor.PURPLE, BarStyle.SEGMENTED_6);
        haunt = h;
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "☠ " + ChatColor.LIGHT_PURPLE + "Spirits are rising over a village near "
                + nearestName(c) + ChatColor.LIGHT_PURPLE + "! Drive them off before dawn.");
        h.world.playSound(c, Sound.AMBIENT_CAVE, 4, 0.6f);
        return true;
    }

    private String nearestName(Location c) {
        Player best = null; double bd = Double.MAX_VALUE;
        for (Player p : c.getWorld().getPlayers()) { double d = p.getLocation().distanceSquared(c); if (d < bd) { bd = d; best = p; } }
        return ChatColor.WHITE + (best == null ? "somewhere" : best.getName()) + ChatColor.LIGHT_PURPLE;
    }

    void stop(boolean won) {
        Haunt h = haunt;
        if (h == null) return;
        haunt = null;
        h.bar.removeAll();
        for (UUID id : h.mobs) { Entity e = Bukkit.getEntity(id); if (e != null) { e.getWorld().spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02); e.remove(); } }
        for (UUID id : h.cowering) { Entity e = Bukkit.getEntity(id); if (e instanceof LivingEntity le) { le.removePotionEffect(PotionEffectType.SLOWNESS); le.setGlowing(false); } }
        if (h.wraith != null && h.wraith.isValid()) h.wraith.remove();
        if (won) {
            int candy = (int) cfg("reward-candy", 6);
            for (UUID id : h.fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                if (Bukkit.getPluginManager().getPlugin("FaultlineCosmetics") != null)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cosmetic tokens " + p.getName() + " halloween " + candy);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givegoodiebag 1 " + p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " haunted_villages 1");
            }
            Bukkit.broadcastMessage(ChatColor.GOLD + "🎃 The Lantern Wraith is gone and the village is at peace. "
                    + ChatColor.GRAY + "(" + h.fighters.size() + " defender" + (h.fighters.size() == 1 ? "" : "s") + " got " + candy + " Candy)");
        } else Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "☠ The spirits fade back into the dark.");
    }

    private void run(Haunt h) {
        h.tick++;
        long now = System.currentTimeMillis();
        if (now > h.endsAt || (!h.forced && !night(h.world) && h.tick > 40) || !h.world.isChunkLoaded(h.center.getBlockX() >> 4, h.center.getBlockZ() >> 4)) { stop(false); return; }
        h.mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
        // who's here: the bar, fog, cowering villagers
        for (Player p : h.world.getPlayers()) {
            boolean near = p.getLocation().distanceSquared(h.center) < 80 * 80;
            if (near && !h.bar.getPlayers().contains(p)) h.bar.addPlayer(p);
            else if (!near && h.bar.getPlayers().contains(p)) h.bar.removePlayer(p);
            if (near && p.getGameMode() == GameMode.SURVIVAL) h.fighters.add(p.getUniqueId());
            if (near && h.tick % 2 == 0) {
                p.spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 3, 6, 2, 6, 0.01);
                p.spawnParticle(Particle.ASH, p.getLocation().add(0, 2, 0), 12, 8, 3, 8, 0);
            }
        }
        if (h.tick % 10 == 1) for (Entity e : h.world.getNearbyEntities(h.center, 48, 24, 48, en -> en instanceof Villager)) {
            Villager v = (Villager) e;
            v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 2, false, false));
            v.setGlowing(true);
            h.cowering.add(v.getUniqueId());
        }
        long total = (long) (cfg("minutes", 6) * 60_000);
        double left = Math.max(0, Math.min(1, (h.endsAt - now) / (double) total));
        h.bar.setProgress(left);
        int waves = (int) cfg("waves", 3);
        if (h.wave < waves && h.mobs.isEmpty() && h.tick > 6) {
            h.wave++;
            int n = (int) cfg("wave-size", 6) + h.wave * 2 + Math.min(6, h.fighters.size());
            for (int i = 0; i < n; i++) spawnMinion(h, i % 3 == 0);
            h.bar.setTitle(ChatColor.DARK_PURPLE + "☠ The village is haunted " + ChatColor.GRAY + "(wave " + h.wave + "/" + waves + ")");
            for (Player p : h.bar.getPlayers()) { p.sendTitle("", ChatColor.LIGHT_PURPLE + "Wave " + h.wave, 5, 30, 10); p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_NEARBY_CLOSER, 0.8f, 1.4f); }
        } else if (h.wave >= waves && h.wraith == null && h.mobs.isEmpty()) spawnWraith(h);
        if (h.wraith != null) wraithTick(h);
    }

    private Location around(Haunt h, double r) {
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = 6 + random.nextDouble() * r;
            int x = (int) (h.center.getX() + Math.cos(a) * d), z = (int) (h.center.getZ() + Math.sin(a) * d);
            if (!h.world.isChunkLoaded(x >> 4, z >> 4)) continue;
            return new Location(h.world, x + 0.5, h.world.getHighestBlockYAt(x, z) + 1, z + 0.5);
        }
        return h.center.clone();
    }

    private void spawnMinion(Haunt h, boolean villager) {
        Location at = around(h, 26);
        LivingEntity e;
        if (villager) {
            ZombieVillager z = (ZombieVillager) h.world.spawnEntity(at, EntityType.ZOMBIE_VILLAGER, false);
            z.getEquipment().setHelmet(new ItemStack(random.nextBoolean() ? Material.CARVED_PUMPKIN : Material.JACK_O_LANTERN));
            z.getEquipment().setHelmetDropChance(0);
            Emotes.q(() -> z.setShouldBurnInDay(false));
            try { z.setConversionTime(-1); } catch (RuntimeException ignored) { }
            z.customName(Component.text("Haunted Villager"));
            z.addScoreboardTag(VILLAGER);
            e = z;
        } else {
            Vex v = (Vex) h.world.spawnEntity(at.add(0, 2, 0), EntityType.VEX, false);
            v.getEquipment().setItemInMainHand(null);
            v.customName(Component.text("Restless Spirit"));
            try { v.setLimitedLifetime(false); } catch (RuntimeException ignored) { }
            MimicPlayer.attr(v, Attribute.MAX_HEALTH, 14);
            v.setHealth(14);
            v.addScoreboardTag(SPIRIT);
            e = v;
        }
        e.addScoreboardTag(ANY);
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        h.mobs.add(e.getUniqueId());
        h.world.spawnParticle(Particle.SOUL, at, 15, 0.3, 0.6, 0.3, 0.03);
        Player t = nearestFighter(h, at);
        if (t != null && e instanceof Mob m) m.setTarget(t);
    }

    private Player nearestFighter(Haunt h, Location at) {
        Player best = null; double bd = 64 * 64;
        for (Player p : h.world.getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private void spawnWraith(Haunt h) {
        Location at = around(h, 12);
        WitherSkeleton w = (WitherSkeleton) h.world.spawnEntity(at, EntityType.WITHER_SKELETON, false);
        w.customName(Component.text("The Lantern Wraith", net.kyori.adventure.text.format.NamedTextColor.GOLD));
        w.setCustomNameVisible(true);
        w.getEquipment().setHelmet(new ItemStack(Material.JACK_O_LANTERN));
        w.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_HOE));
        w.getEquipment().setHelmetDropChance(0);
        w.getEquipment().setItemInMainHandDropChance(0);
        double hp = cfg("wraith-health", 160) * (1 + 0.2 * Math.max(0, h.fighters.size() - 1));
        MimicPlayer.attr(w, Attribute.MAX_HEALTH, hp);
        w.setHealth(hp);
        MimicPlayer.attr(w, Attribute.SCALE, 1.4);
        MimicPlayer.attr(w, Attribute.MOVEMENT_SPEED, 0.3);
        w.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        w.addScoreboardTag(WRAITH);
        w.addScoreboardTag(ANY);
        w.setPersistent(false);
        w.setRemoveWhenFarAway(false);
        h.wraith = w;
        Emotes.q(() -> h.world.strikeLightningEffect(at));
        h.world.playSound(at, Sound.ENTITY_WITHER_SPAWN, 2, 1.4f);
        h.bar.setTitle(ChatColor.GOLD + "🎃 The Lantern Wraith");
        h.bar.setColor(BarColor.YELLOW);
    }

    private void wraithTick(Haunt h) {
        LivingEntity w = h.wraith;
        if (!w.isValid() || w.isDead()) return;
        h.bar.setProgress(Math.max(0, Math.min(1, w.getHealth() / w.getAttribute(Attribute.MAX_HEALTH).getValue())));
        w.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, w.getLocation().add(0, 2.4, 0), 2, 0.2, 0.2, 0.2, 0.01);
        Player t = nearestFighter(h, w.getLocation());
        if (t == null) return;
        if (w instanceof Mob m && m.getTarget() != t) m.setTarget(t);
        if (h.tick % 16 != 0) return; // a move every 8 s
        switch (random.nextInt(3)) {
            case 0 -> { // Soul Flames: a line of soul fire toward the target
                Vector dir = t.getLocation().toVector().subtract(w.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() < 1e-4) return;
                dir.normalize();
                for (int i = 1; i <= 10; i++) {
                    Location l = w.getLocation().add(dir.clone().multiply(i));
                    int step = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 12, 0.3, 0.3, 0.3, 0.02);
                        for (Entity e : l.getWorld().getNearbyEntities(l, 1, 1.5, 1, en -> en instanceof Player))
                            { Player p = (Player) e; p.damage(cfg("soul-flame-damage", 5), w); p.setFireTicks(60); }
                    }, step * 2L);
                }
                w.getWorld().playSound(w.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.6f);
            }
            case 1 -> { // the Wail: blindness nearby, and two spirits
                w.getWorld().playSound(w.getLocation(), Sound.ENTITY_GHAST_SCREAM, 2, 0.5f);
                for (Entity e : w.getNearbyEntities(10, 6, 10)) if (e instanceof Player p) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                for (int i = 0; i < 2; i++) spawnMinion(h, false);
            }
            default -> { // Blink: behind the target
                Vector back = t.getLocation().getDirection().setY(0);
                if (back.lengthSquared() < 1e-4) return;
                Location to = t.getLocation().subtract(back.normalize().multiply(2));
                if (!Emotes.open(to.getBlock()) || !Emotes.open(to.clone().add(0, 1, 0).getBlock())) return;
                w.getWorld().spawnParticle(Particle.SOUL, w.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
                w.teleport(to);
                w.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1, 0.6f);
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ANY)) return;
        e.getDrops().clear();
        Haunt h = haunt;
        if (e.getEntity().getScoreboardTags().contains(WRAITH)) {
            e.setDroppedExp(300);
            e.getDrops().add(new ItemStack(Material.JACK_O_LANTERN, 2));
            e.getDrops().add(new ItemStack(Material.DIAMOND, 2 + random.nextInt(3)));
            if (h != null && h.wraith == e.getEntity()) Bukkit.getScheduler().runTask(plugin, () -> stop(true));
        } else {
            e.setDroppedExp(5);
            if (random.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.PUMPKIN_PIE));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) { if (e.getEntity().getScoreboardTags().contains(ANY)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (e.getEntity().getScoreboardTags().contains(ANY)) e.setCancelled(true); }

    void shutdown() { stop(false); }

    String status() {
        Haunt h = haunt;
        if (h == null) return "no haunting" + (season() ? " (Halloween: on)" : " (not Halloween)");
        return "haunting at " + Where.spot(h.center) + ", wave " + h.wave + ", " + h.mobs.size() + " spirits" + (h.wraith != null ? ", the Lantern Wraith is out" : "");
    }
}
