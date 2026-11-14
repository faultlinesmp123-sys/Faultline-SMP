package net.faultlinesmp.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE BLOOD MOON (a whole night). Natural monsters spawn stronger (health / damage / speed modifiers), more of them come
 * for each player, killing them gives double XP and Blood Moon monsters a bonus drop; nobody sleeps; a red mist and a
 * heartbeat; at midnight the CRIMSON CHAMPION (a 150 hp red knight: leaps, drinks blood, calls the dead at half health)
 * hunts someone. Dawn: the top hunters are named and paid. FaultlineItems sends the red moon sky and Vampires hunt the
 * surface; FaultlineRaids makes raids bigger.
 */
final class BloodMoon extends Ev {

    static final String MOB_TAG = "bloodmoon_mob";     // no "faultline" prefix: Raids' Elites still roll on these
    static final String MINION_TAG = "bloodmoon_minion";
    static final String CHAMPION_TAG = "faultline_crimson_champion";
    private static final Color RED = Color.fromRGB(150, 8, 12);

    private final Set<UUID> mobs = new HashSet<>();
    final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private boolean championDone;
    private Champion champ;

    BloodMoon(FaultlineEvents pl) { super(pl); }

    @Override String id() { return "BLOOD_MOON"; }
    @Override String key() { return "blood-moon"; }
    @Override String title() { return "Blood Moon"; }
    @Override ChatColor color() { return ChatColor.DARK_RED; }
    @Override BarColor barColor() { return BarColor.RED; }
    @Override boolean night() { return true; }
    @Override String blurb() { return "monsters are stronger and more of them come, double XP from kills, bonus loot, no sleeping, and at midnight the Crimson Champion hunts."; }

    @Override String barText() {
        int total = kills.values().stream().mapToInt(Integer::intValue).sum();
        return ChatColor.RED + "Monsters slain: " + total + (champ != null && champ.body.isValid() ? ChatColor.DARK_RED + " · the Champion walks" : "");
    }

    @Override
    void start(boolean announce) {
        kills.clear(); deaths.clear(); mobs.clear(); championDone = false;
        if (announce) announce(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Blood Moon Rises",
                ChatColor.RED + "Monsters grow stronger. No one sleeps tonight.",
                ChatColor.DARK_RED + "☾ " + ChatColor.BOLD + "The Blood Moon rises!" + ChatColor.RED + " Monsters are stronger and hungrier, kills give double XP, and something walks at midnight.",
                Sound.ENTITY_WITHER_SPAWN, 0.5f);
    }

    @Override
    void stop(boolean natural) {
        if (champ != null) champ.crumble();
        champ = null;
        if (!natural) return;
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        pl.say(ChatColor.DARK_RED + "☾ " + ChatColor.RED + "The Blood Moon sets. " + ChatColor.GRAY + "The night is over.");
        String[] medal = {ChatColor.GOLD + "1st", ChatColor.GRAY + "2nd", ChatColor.RED + "3rd"};
        for (int k = 0; k < Math.min(3, top.size()); k++) {
            String name = Bukkit.getOfflinePlayer(top.get(k).getKey()).getName();
            pl.say("  " + medal[k] + " " + ChatColor.WHITE + name + ChatColor.GRAY + " - " + top.get(k).getValue() + " kills");
        }
        int min = i("dawn-rewards.min-kills", 10);
        for (var e : kills.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            pl.stat(p, "blood_moon_kills", e.getValue());
            if (deaths.getOrDefault(e.getKey(), 0) == 0) pl.stat(p, "blood_moons_survived", 1);
            if (e.getValue() >= min) {
                for (String c : list("dawn-rewards.commands")) pl.console(c, p);
                p.sendMessage(ChatColor.RED + "You slew " + e.getValue() + " monsters under the Blood Moon. " + ChatColor.GOLD + "Reward given.");
            }
        }
        if (!top.isEmpty() && top.get(0).getValue() >= min) {
            Player best = Bukkit.getPlayer(top.get(0).getKey());
            if (best != null) for (String c : list("dawn-rewards.top-commands")) pl.console(c, best);
        }
        if (pl.loud()) for (Player p : pl.players()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 1.2f);
    }

    // ---------------------------------------------------------------- every second

    @Override
    void second() {
        mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
        World w = pl.main();
        int every = Math.max(3, i("extra-spawns.every-seconds", 15));
        boolean spawnNow = (pl.ticks / 20) % every == 0;
        int particles = i("particles", 14);
        for (Player p : pl.players()) {
            Location l = p.getLocation();
            for (int k = 0; k < particles; k++) {
                Location at = l.clone().add(pl.random.nextGaussian() * 7, pl.random.nextDouble() * 5 - 1, pl.random.nextGaussian() * 7);
                p.spawnParticle(Particle.DUST, at, 1, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(RED, 1.6f));
            }
            if (pl.random.nextInt(25) == 0) p.playSound(l, Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.8f);
            if (spawnNow && FaultlineEvents.survival(p)) extraSpawns(p);
        }
        if (!championDone && b("champion.enabled", true) && w.getTime() >= i("champion.at-time", 18000)) {
            championDone = true;
            List<Player> hunters = new ArrayList<>();
            for (Player p : pl.players()) if (FaultlineEvents.survival(p) && !p.isDead()) hunters.add(p);
            if (!hunters.isEmpty()) {
                Player t = hunters.get(pl.random.nextInt(hunters.size()));
                Location at = championSpot(t);
                if (at != null) spawnChampion(at, t);
            }
        }
        if (champ != null) {
            if (!champ.body.isValid()) champ.gone();
            else champ.second();
        }
    }

    @Override
    void tick(long t) {
        if (champ != null && champ.body.isValid() && t % 2 == 0) {
            Location l = champ.body.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.DUST, l, 3, 0.35, 0.6, 0.35, 0, new Particle.DustOptions(RED, 1.3f));
        }
    }

    private void extraSpawns(Player p) {
        int near = 0;
        for (UUID id : mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) < 48 * 48) near++;
        }
        int want = i("extra-spawns.per-player", 5) - near;
        for (int k = 0; k < Math.min(2, want); k++) {
            Location at = groundSpot(p, d("extra-spawns.min-distance", 16), d("extra-spawns.max-distance", 30), 10);
            if (at == null) return;
            if (at.getBlock().getLightFromBlocks() >= 8) continue; // lit bases stay safe
            EntityType type = pick(at);
            try {
                Entity e = at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL);
                if (e instanceof LivingEntity le && !le.getScoreboardTags().contains(MOB_TAG)) buff(le);
                if (e instanceof Monster m) m.setTarget(p);
            } catch (RuntimeException ex) { pl.problem("blood spawn", ex); }
        }
    }

    private EntityType pick(Location at) {
        String biome = String.valueOf(at.getBlock().getBiome().getKey()).toLowerCase();
        double r = pl.random.nextDouble();
        if (r < 0.40) return biome.contains("desert") || biome.contains("badlands") ? EntityType.HUSK : EntityType.ZOMBIE;
        if (r < 0.65) return biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") ? EntityType.STRAY : EntityType.SKELETON;
        if (r < 0.80) return EntityType.SPIDER;
        if (r < 0.95) return EntityType.CREEPER;
        return EntityType.WITCH;
    }

    // ---------------------------------------------------------------- stronger monsters

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on() || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster m)) return;
        if (!pl.eventWorld(m.getWorld())) return;
        buff(m);
    }

    void buff(LivingEntity m) {
        if (!m.addScoreboardTag(MOB_TAG)) return;
        mod(m, Attribute.MAX_HEALTH, "blood_health", d("mob-health", 1.3) - 1);
        mod(m, Attribute.ATTACK_DAMAGE, "blood_damage", d("mob-damage", 1.25) - 1);
        mod(m, Attribute.MOVEMENT_SPEED, "blood_speed", d("mob-speed", 1.1) - 1);
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) m.setHealth(hp.getValue());
        mobs.add(m.getUniqueId());
    }

    private void mod(LivingEntity m, Attribute a, String name, double amount) {
        AttributeInstance in = m.getAttribute(a);
        if (in == null || amount == 0) return;
        NamespacedKey k = new NamespacedKey(pl, name);
        for (AttributeModifier old : new ArrayList<>(in.getModifiers())) if (old.getKey().equals(k)) in.removeModifier(old);
        in.addModifier(new AttributeModifier(k, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v instanceof Player dead) {
            if (on() && pl.eventWorld(dead.getWorld())) deaths.merge(dead.getUniqueId(), 1, Integer::sum);
            return;
        }
        if (v.getScoreboardTags().contains(MINION_TAG)) { e.getDrops().clear(); e.setDroppedExp(1); return; }
        if (champ != null && v.equals(champ.body)) { champ.slain(e); champ = null; return; }
        if (!on() || !(v instanceof Enemy) || !pl.eventWorld(v.getWorld())) return;
        Player k = v.getKiller();
        if (k == null) return;
        kills.merge(k.getUniqueId(), 1, Integer::sum);
        e.setDroppedExp((int) Math.round(e.getDroppedExp() * d("xp-multiplier", 2.0)));
        boolean ours = v.getScoreboardTags().contains(MOB_TAG);
        boolean boss = v.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_"));
        if (ours && !boss && pl.random.nextDouble() < d("bonus-loot-chance", 0.25)) {
            List<String> loot = list("bonus-loot");
            if (!loot.isEmpty()) {
                ItemStack it = stack(loot.get(pl.random.nextInt(loot.size())));
                if (it != null) e.getDrops().add(it);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) {
        if (!on() || !b("no-sleep", true) || !pl.eventWorld(e.getPlayer().getWorld())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("You can't sleep under a Blood Moon.", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- the Crimson Champion

    Location championSpot(Player t) { return groundSpot(t, 18, 26, 20); }

    void spawnChampion(Location at, Player target) {
        if (champ != null && champ.body.isValid()) champ.crumble();
        Zombie z = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM);
        z.setAdult();
        z.addScoreboardTag(CHAMPION_TAG);
        z.setPersistent(false);
        z.setRemoveWhenFarAway(false);
        z.setCanPickupItems(false);
        z.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Crimson Champion");
        z.setCustomNameVisible(true);
        set(z, Attribute.MAX_HEALTH, d("champion.health", 150));
        set(z, Attribute.ATTACK_DAMAGE, d("champion.damage", 9));
        set(z, Attribute.MOVEMENT_SPEED, 0.29);
        set(z, Attribute.KNOCKBACK_RESISTANCE, 0.6);
        set(z, Attribute.FOLLOW_RANGE, 48);
        set(z, Attribute.SCALE, 1.25);
        z.setHealth(z.getAttribute(Attribute.MAX_HEALTH).getValue());
        var eq = z.getEquipment();
        eq.setHelmet(leather(Material.LEATHER_HELMET)); eq.setChestplate(leather(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(leather(Material.LEATHER_LEGGINGS)); eq.setBoots(leather(Material.LEATHER_BOOTS));
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1);
        eq.setItemInMainHand(sword);
        eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setBootsDropChance(0); eq.setItemInMainHandDropChance(0);
        z.setTarget(target);
        champ = new Champion(z);
        Location l = z.getLocation();
        try { l.getWorld().strikeLightningEffect(l); } catch (RuntimeException ignored) { }
        l.getWorld().spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 80, 0.8, 1.2, 0.8, 0, new Particle.DustOptions(RED, 2f));
        if (pl.loud()) for (Player p : pl.players()) {
            p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.7f, 0.6f);
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Crimson Champion", ChatColor.RED + "hunts " + target.getName(), 10, 60, 20);
        }
        pl.say(ChatColor.DARK_RED + "☾ " + ChatColor.BOLD + "The Crimson Champion" + ChatColor.RED + " walks the Blood Moon, hunting " + target.getName() + "!");
    }

    private static void set(LivingEntity e, Attribute a, double v) { AttributeInstance in = e.getAttribute(a); if (in != null) in.setBaseValue(v); }

    private static ItemStack leather(Material m) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(Color.fromRGB(120, 0, 10)); it.setItemMeta(lm); }
        return it;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) {
        if (e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (e.getEntity().getScoreboardTags().contains(CHAMPION_TAG) || e.getEntity().getScoreboardTags().contains(MINION_TAG)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (champ == null) return;
        if (e.getDamager().equals(champ.body) && e.getEntity() instanceof LivingEntity) {
            double heal = e.getFinalDamage() * 0.35; // it drinks your blood
            AttributeInstance hp = champ.body.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) champ.body.setHealth(Math.min(hp.getValue(), champ.body.getHealth() + heal));
            champ.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, champ.body.getLocation().add(0, 2, 0), 4, 0.3, 0.3, 0.3, 0);
        }
        if (e.getEntity().equals(champ.body)) {
            Entity d = e.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
            if (d instanceof Player p) champ.hurtBy.add(p.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getEntity().getScoreboardTags().contains(MINION_TAG) && e.getTarget() != null && e.getTarget().getScoreboardTags().contains(CHAMPION_TAG)) e.setCancelled(true);
    }

    final class Champion {
        final Zombie body;
        final Set<UUID> hurtBy = new HashSet<>();
        final BossBar bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Crimson Champion", BarColor.RED, BarStyle.SOLID);
        int nextLeap = 6;
        boolean howled;
        int sec;

        Champion(Zombie z) { body = z; }

        void second() {
            sec++;
            AttributeInstance hp = body.getAttribute(Attribute.MAX_HEALTH);
            double frac = hp == null ? 1 : body.getHealth() / hp.getValue();
            bar.setProgress(Math.max(0, Math.min(1, frac)));
            for (Player p : pl.players()) {
                boolean near = p.getWorld().equals(body.getWorld()) && p.getLocation().distanceSquared(body.getLocation()) < 64 * 64;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
            LivingEntity t = body.getTarget();
            if (!(t instanceof Player tp) || !tp.isValid() || !FaultlineEvents.survival(tp)) {
                Player best = null;
                for (Player p : pl.players())
                    if (FaultlineEvents.survival(p) && p.getWorld().equals(body.getWorld()) && (best == null || p.getLocation().distanceSquared(body.getLocation()) < best.getLocation().distanceSquared(body.getLocation()))) best = p;
                if (best != null && best.getLocation().distanceSquared(body.getLocation()) < 48 * 48) body.setTarget(best);
                t = body.getTarget();
            }
            if (t != null && --nextLeap <= 0) {
                double dist = t.getLocation().distance(body.getLocation());
                if (dist > 3 && dist < 15 && body.isOnGround()) { // Blood Leap
                    Vector v = t.getLocation().toVector().subtract(body.getLocation().toVector());
                    v.setY(0);
                    if (v.lengthSquared() > 0.01) {
                        v.normalize().multiply(Math.min(1.4, dist * 0.11)).setY(0.55);
                        vel(body, v);
                        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.3f);
                        body.getWorld().spawnParticle(Particle.DUST, body.getLocation(), 30, 0.5, 0.2, 0.5, 0, new Particle.DustOptions(RED, 1.8f));
                    }
                }
                nextLeap = 6 + pl.random.nextInt(3);
            }
            if (!howled && frac < 0.5) { // the dead answer
                howled = true;
                body.getWorld().playSound(body.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.5f, 0.5f);
                for (int k = 0; k < 3; k++) {
                    Location at = body.getLocation().add(pl.random.nextDouble() * 6 - 3, 0, pl.random.nextDouble() * 6 - 3);
                    if (!open(at.getBlock()) || !open(at.getBlock().getRelative(0, 1, 0))) at = body.getLocation();
                    Zombie m = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM);
                    m.addScoreboardTag(MINION_TAG);
                    m.setPersistent(false);
                    m.setCustomName(ChatColor.RED + "Bloodbound");
                    if (body.getTarget() != null) m.setTarget(body.getTarget());
                    at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(RED, 1.5f));
                }
            }
            if (sec % 4 == 0) body.getWorld().playSound(body.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.7f);
        }

        void slain(EntityDeathEvent e) {
            bar.removeAll();
            e.getDrops().clear();
            for (String spec : list("champion.drops")) { ItemStack it = stack(spec); if (it != null) e.getDrops().add(it); }
            e.setDroppedExp(i("champion.xp", 250));
            Player killer = body.getKiller();
            if (killer != null) hurtBy.add(killer.getUniqueId());
            for (UUID id : hurtBy) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                for (String c : list("champion.rewards")) pl.console(c, p);
                pl.stat(p, "crimson_champions", 1);
            }
            Location l = body.getLocation();
            l.getWorld().spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 120, 1, 1.4, 1, 0, new Particle.DustOptions(RED, 2.2f));
            l.getWorld().playSound(l, Sound.ENTITY_WITHER_DEATH, 0.8f, 1.4f);
            pl.say(ChatColor.DARK_RED + "☾ " + ChatColor.RED + "The Crimson Champion has fallen" + (killer != null ? " to " + ChatColor.WHITE + killer.getName() : "") + ChatColor.RED + "!");
        }

        void gone() { bar.removeAll(); champ = null; }

        void crumble() {
            bar.removeAll();
            if (body.isValid()) {
                Location l = body.getLocation();
                l.getWorld().spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 60, 0.6, 1, 0.6, 0, new Particle.DustOptions(RED, 1.8f));
                l.getWorld().playSound(l, Sound.BLOCK_SAND_BREAK, 1f, 0.6f);
                body.remove();
            }
        }
    }
}
