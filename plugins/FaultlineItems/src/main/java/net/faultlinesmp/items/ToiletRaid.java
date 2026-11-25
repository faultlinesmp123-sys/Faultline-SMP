package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * THE TOILET RAID (Items 1.3.38, config toilet-raid:). ADMINS ONLY start it (items.adminbosses: /toiletraid start, or the
 * Golden Plunger). Waves of toilets with heads sticking out of them come at everyone round the spot (like the zombie and
 * skeleton raids), `waves` 10, each bigger, with more kinds: Toilet Grunt, Speed Flusher, Plunger Toilet (sludge shots),
 * Tank Toilet, Bomb Toilet (blows up next to you, no block damage), Golden Toilet (buffs the others). After the last wave
 * comes THE PORCELAIN COLOSSUS: a giant toilet (12000 health +40% per extra fighter, takes 25% less) that WRECKS BUILDS:
 * it walks through walls, its slam leaves craters, its laser and charge break blocks (never containers / block entities /
 * bedrock / obsidian; `boss.grief`, and `/toiletraid restore` puts the blocks back until a restart, or
 * `boss.restore-after-fight: true` does it on its own). Rewards are huge (`rewards`).
 * Body of a toilet = an invisible zombie wearing a player head (Bedrock sees the head) + block displays (the toilet) moved
 * with it every 2 ticks.
 */
final class ToiletRaid implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_toilet", PART = "faultline_toilet_part", BOSS_TAG = "faultline_toilet_colossus",
            SLUDGE = "faultline_toilet_sludge", PERM = "items.adminbosses";

    enum Kind {
        GRUNT("grunt", "Toilet Grunt", 0.9, 34, 7, 0.27, Material.QUARTZ_BLOCK),
        SPEEDER("speeder", "Speed Flusher", 0.72, 20, 5, 0.40, Material.QUARTZ_BLOCK),
        PLUNGER("plunger", "Plunger Toilet", 0.9, 30, 5, 0.25, Material.QUARTZ_BLOCK),
        TANK("tank", "Tank Toilet", 1.35, 140, 13, 0.21, Material.QUARTZ_BLOCK),
        BOMB("bomb", "Bomb Toilet", 0.85, 24, 4, 0.33, Material.QUARTZ_BLOCK),
        GOLDEN("golden", "Golden Toilet", 1.15, 220, 11, 0.27, Material.GOLD_BLOCK);
        final String id, title; final double scale, hp, dmg, speed; final Material shell;
        Kind(String id, String title, double scale, double hp, double dmg, double speed, Material shell) {
            this.id = id; this.title = title; this.scale = scale; this.hp = hp; this.dmg = dmg; this.speed = speed; this.shell = shell;
        }
        static Kind of(String s) { for (Kind k : values()) if (k.id.equalsIgnoreCase(s)) return k; return null; }
    }

    final class Unit {
        Zombie body; Kind kind; final List<BlockDisplay> parts = new ArrayList<>(); double k; int fuse = -1, shootAt; float lastYaw = 999;
        boolean ofRaid;
    }

    final class Raid {
        Location center; int wave, waves, waitUntil, alone; BossBar bar; final Set<UUID> fighters = new HashSet<>(); boolean bossPhase;
    }

    final class Colossus {
        Unit u; BossBar bar; int tick, nextMove = 80, move, moveT, phase = 1; Player target; Vector charge; Location beam;
        final Map<UUID, Double> damage = new HashMap<>(); final Set<UUID> fighters = new HashSet<>(); boolean leaping; int alone;
    }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey plungerKey;
    final Map<UUID, Unit> units = new HashMap<>();
    Raid raid;
    Colossus boss;
    /** Blocks the Colossus broke (first original per spot), for /toiletraid restore. */
    private final LinkedHashMap<Location, BlockData> griefed = new LinkedHashMap<>();
    private long tick;

    private final java.io.File griefFile;
    private boolean griefDirty;

    ToiletRaid(FaultlineItems plugin) {
        this.plugin = plugin;
        plungerKey = new NamespacedKey(plugin, "golden_plunger");
        griefFile = new java.io.File(plugin.getDataFolder(), "toilet_grief.yml");
        loadGrief();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(PART) || e.getScoreboardTags().contains(BOSS_TAG)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("toilet-raid." + k, d); }

    /** 1.3.39: what the Colossus broke survives restarts and crashes (saved every minute), so /toiletraid restore still works. */
    private void loadGrief() {
        if (!griefFile.exists()) return;
        for (String line : SafeYaml.load(plugin, griefFile).yaml.getStringList("blocks")) {
            try {
                String[] a = line.split(";", 5);
                World w = Bukkit.getWorld(a[0]);
                if (w == null) continue;
                griefed.putIfAbsent(new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])), Bukkit.createBlockData(a[4]));
            } catch (RuntimeException ignored) { }
        }
    }

    void saveGrief() {
        if (!griefDirty) return;
        griefDirty = false;
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        List<String> out = new ArrayList<>(griefed.size());
        for (var e : griefed.entrySet()) {
            Location l = e.getKey();
            out.add(l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ() + ";" + e.getValue().getAsString());
        }
        y.set("blocks", out);
        SafeYaml.write(plugin, griefFile, y.saveToString());
    }

    // ================================================================= the Golden Plunger

    ItemStack plunger(int n) {
        ItemStack it = new ItemStack(Material.LIGHTNING_ROD, n);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Golden Plunger", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("ADMINS ONLY. Right-click: the Toilet Raid", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("starts right where you stand.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Very, very hard. Huge rewards.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(plungerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlunger(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null || !e.getItem().hasItemMeta()
                || !e.getItem().getPersistentDataContainer().has(plungerKey, PersistentDataType.BYTE)) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission(PERM)) { p.sendMessage(ChatColor.RED + "Only admins can start the Toilet Raid."); return; }
        if (start(p.getLocation(), (int) cfg("waves", 10)) && p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
        else if (raid != null || boss != null) p.sendMessage(ChatColor.RED + "A Toilet Raid is already going.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (e.getItemInHand().hasItemMeta() && e.getItemInHand().getPersistentDataContainer().has(plungerKey, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    // ================================================================= raid

    boolean start(Location at, int waves) {
        if (raid != null || boss != null) return false;
        Raid r = new Raid();
        r.center = at.clone();
        r.waves = Math.max(1, Math.min(30, waves));
        r.bar = Bukkit.createBossBar(ChatColor.WHITE + "Toilet Raid", BarColor.WHITE, BarStyle.SEGMENTED_10);
        r.waitUntil = (int) tick + 100;
        raid = r;
        Bukkit.broadcastMessage(ChatColor.WHITE + "🚽 " + ChatColor.AQUA + "The TOILET RAID has begun at " + Where.spot(at) + "! "
                + ChatColor.GRAY + r.waves + " waves, then something huge.");
        at.getWorld().playSound(at, Sound.EVENT_RAID_HORN, 6, 0.8f);
        return true;
    }

    void stop() {
        Raid r = raid;
        raid = null;
        if (r != null) r.bar.removeAll();
        Colossus c = boss;
        boss = null;
        if (c != null) { c.bar.removeAll(); removeUnit(c.u); }
        for (Unit u : new ArrayList<>(units.values())) removeUnit(u);
        units.clear();
    }

    private List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(c) < r * r && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()) out.add(p);
        return out;
    }

    private void raidTick(Raid r) {
        List<Player> ps = near(r.center, 90);
        for (Player p : r.center.getWorld().getPlayers()) {
            boolean in = p.getLocation().distanceSquared(r.center) < 110 * 110;
            if (in && !r.bar.getPlayers().contains(p)) r.bar.addPlayer(p); else if (!in) r.bar.removePlayer(p);
        }
        for (Player p : ps) r.fighters.add(p.getUniqueId());
        if (ps.isEmpty()) { if (++r.alone > 20 * 60) { Bukkit.broadcastMessage(ChatColor.GRAY + "🚽 The Toilet Raid flushed away (everyone left or died)."); stop(); } return; }
        r.alone = 0;
        if (r.bossPhase) return;
        long alive = units.values().stream().filter(u -> u.ofRaid && u.body.isValid() && !u.body.isDead()).count();
        if (r.wave > 0) {
            r.bar.setTitle(ChatColor.WHITE + "Toilet Raid: wave " + r.wave + "/" + r.waves + ChatColor.GRAY + " (" + alive + " left)");
        }
        if (alive > 0) { r.waitUntil = (int) tick + 100; return; }
        if (tick < r.waitUntil) return;
        if (r.wave >= r.waves) {
            r.bossPhase = true;
            r.bar.setTitle(ChatColor.DARK_RED + "Toilet Raid: THE COLOSSUS");
            r.bar.setProgress(1);
            for (Player p : ps) p.sendTitle(ChatColor.DARK_RED + "THE PORCELAIN COLOSSUS", ChatColor.GRAY + "It's coming. Hide your builds.", 10, 60, 20);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (raid == r) spawnBoss(r.center, true); }, 80);
            return;
        }
        r.wave++;
        r.bar.setProgress(Math.max(0, 1 - (r.wave - 1) / (double) r.waves));
        for (Player p : ps) p.sendTitle(ChatColor.AQUA + "Wave " + r.wave, ChatColor.GRAY + "of " + r.waves, 5, 40, 10);
        r.center.getWorld().playSound(r.center, Sound.EVENT_RAID_HORN, 6, 1f);
        spawnWave(r, ps.size());
    }

    private void spawnWave(Raid r, int players) {
        int w = r.wave;
        int count = (int) Math.min(cfg("max-per-wave", 45), (cfg("base-count", 6) + cfg("per-wave", 3) * w) * (1 + cfg("per-fighter", 0.4) * Math.max(0, players - 1)));
        double hpMul = 1 + 0.06 * (w - 1);
        List<Kind> pool = new ArrayList<>(List.of(Kind.GRUNT, Kind.GRUNT, Kind.GRUNT));
        if (w >= 2) pool.addAll(List.of(Kind.SPEEDER, Kind.SPEEDER));
        if (w >= 3) pool.addAll(List.of(Kind.PLUNGER, Kind.PLUNGER));
        if (w >= 4) pool.add(Kind.TANK);
        if (w >= 5) pool.addAll(List.of(Kind.BOMB, Kind.BOMB));
        int golden = w >= 7 ? 1 + (w >= 9 ? 1 : 0) : 0;
        for (int i = 0; i < count; i++) {
            Kind k = i < golden ? Kind.GOLDEN : pool.get(random.nextInt(pool.size()));
            Location at = spot(r.center, 14, 26);
            int delay = i * 3;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (raid != r) return;
                Unit u = spawn(k, at, hpMul);
                if (u != null) u.ofRaid = true;
            }, delay);
        }
    }

    private Location spot(Location c, double min, double max) {
        for (int tries = 0; tries < 12; tries++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            Location l = c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy = 10; dy >= -12; dy--) {
                Block b = l.clone().add(0, dy, 0).getBlock();
                if (b.getType().isSolid() && Emotes.open(b.getRelative(0, 1, 0)) && Emotes.open(b.getRelative(0, 2, 0)) && Emotes.open(b.getRelative(0, 3, 0)))
                    return b.getLocation().add(0.5, 1, 0.5);
            }
        }
        return c.clone();
    }

    // ================================================================= one toilet

    private ItemStack head() {
        ItemStack h = new ItemStack(Material.PLAYER_HEAD);
        try {
            String name = plugin.getConfig().getString("toilet-raid.head-skin", "");
            List<? extends Player> on = new ArrayList<>(Bukkit.getOnlinePlayers());
            OfflinePlayer who = name != null && !name.isBlank() ? Bukkit.getOfflinePlayer(name) : on.isEmpty() ? null : on.get(random.nextInt(on.size()));
            if (who != null && h.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(who); h.setItemMeta(sm); }
        } catch (RuntimeException ignored) { }
        return h;
    }

    Unit spawn(Kind kind, Location at, double hpMul) {
        Zombie z = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, false);
        Unit u = new Unit();
        u.body = z; u.kind = kind;
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        Emotes.q(() -> z.setShouldBurnInDay(false));
        Emotes.q(() -> z.setCanPickupItems(false));
        z.setPersistent(false);
        Emotes.q(() -> z.setRemoveWhenFarAway(false));
        Emotes.q(() -> z.setSilent(true));
        z.addScoreboardTag(TAG);
        z.addScoreboardTag(TAG + "_" + kind.id);
        z.customName(Component.text(kind.title, kind == Kind.GOLDEN ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        z.setCustomNameVisible(false);
        double hp = kind.hp * hpMul * cfg("health-multiplier", 1.0);
        MimicPlayer.attr(z, Attribute.SCALE, kind.scale);
        BigHealth.set(z, hp);
        MimicPlayer.attr(z, Attribute.ATTACK_DAMAGE, kind.dmg * cfg("damage-multiplier", 1.0));
        MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, kind.speed);
        MimicPlayer.attr(z, Attribute.FOLLOW_RANGE, 64);
        MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        MimicPlayer.attr(z, Attribute.KNOCKBACK_RESISTANCE, kind == Kind.TANK || kind == Kind.GOLDEN ? 0.9 : 0.3);
        MimicPlayer.attr(z, Attribute.ARMOR, kind == Kind.TANK ? 12 : kind == Kind.GOLDEN ? 10 : 4);
        z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        z.getEquipment().clear();
        z.getEquipment().setHelmet(head());
        for (EquipmentSlot s : EquipmentSlot.values()) try { z.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
        u.k = kind.scale * 2.05;
        model(u, kind.shell);
        u.shootAt = (int) tick + 40 + random.nextInt(40);
        units.put(z.getUniqueId(), u);
        at.getWorld().spawnParticle(Particle.SPLASH, at.clone().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.1);
        at.getWorld().playSound(at, Sound.ITEM_BUCKET_EMPTY, 1.2f, 0.7f);
        return u;
    }

    /** The toilet: x across, y up, z forward (the tank is behind, -z), in units of k. */
    private void model(Unit u, Material shell) {
        Material seat = shell == Material.GOLD_BLOCK ? Material.RAW_GOLD_BLOCK : Material.SMOOTH_QUARTZ;
        part(u, shell, 0, 0, -0.02, 0.42, 0.36, 0.5);          // pedestal
        part(u, shell, 0, 0.34, 0.02, 0.74, 0.3, 0.86);        // bowl
        part(u, seat, 0, 0.64, 0.04, 0.8, 0.09, 0.9);          // seat
        part(u, shell, 0, 0.55, -0.48, 0.76, 0.62, 0.28);      // tank
        part(u, seat, 0, 1.17, -0.48, 0.82, 0.07, 0.32);       // tank lid
        part(u, seat, 0, 0.72, -0.31, 0.78, 0.8, 0.06);        // lid, up
        part(u, Material.IRON_BLOCK, 0.3, 0.98, -0.32, 0.14, 0.05, 0.06); // handle
        if (u.kind == Kind.BOMB) part(u, Material.TNT, 0, 1.24, -0.48, 0.3, 0.3, 0.3);
        if (u.kind == Kind.PLUNGER) part(u, Material.RED_TERRACOTTA, -0.42, 0.5, 0.1, 0.06, 0.9, 0.06);
    }

    private void part(Unit u, Material m, double cx, double y, double cz, double w, double h, double d) {
        BlockDisplay bd = (BlockDisplay) u.body.getWorld().spawnEntity(u.body.getLocation(), EntityType.BLOCK_DISPLAY, false);
        bd.addScoreboardTag(PART);
        bd.setPersistent(false);
        bd.setBlock(m.createBlockData());
        try { bd.setTeleportDuration(2); } catch (RuntimeException ignored) { }
        float k = (float) u.k;
        bd.setTransformation(new Transformation(new Vector3f((float) (cx - w / 2) * k, (float) y * k, (float) (cz - d / 2) * k),
                new Quaternionf(), new Vector3f((float) w * k, (float) h * k, (float) d * k), new Quaternionf()));
        float big = (float) Math.max(2, u.k * 1.5);
        bd.setDisplayWidth(big); bd.setDisplayHeight(big);
        u.parts.add(bd);
    }

    private void follow(Unit u) {
        Location l = u.body.getLocation();
        float yaw;
        try { yaw = u.body.getBodyYaw(); } catch (RuntimeException e) { yaw = l.getYaw(); }
        l.setYaw(yaw); l.setPitch(0);
        for (BlockDisplay d : u.parts) if (d.isValid()) d.teleport(l);
    }

    void removeUnit(Unit u) {
        for (BlockDisplay d : u.parts) if (d.isValid()) d.remove();
        if (u.body.isValid()) u.body.remove();
        units.remove(u.body.getUniqueId());
    }

    private Player nearestTarget(Location at, double r) {
        Player best = null; double bd = r * r;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private void unitTick(Unit u) {
        Zombie z = u.body;
        if (tick % 2 == 0) follow(u);
        if (tick % 10 == 0) {
            Player t = nearestTarget(z.getLocation(), 48);
            if (t != null && z.getTarget() != t) z.setTarget(t);
            if (random.nextInt(20) == 0) z.getWorld().playSound(z.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 0.7f, 1.4f);
        }
        LivingEntity t = z.getTarget();
        switch (u.kind) {
            case PLUNGER -> {
                if (t != null && tick >= u.shootAt && t.getLocation().distanceSquared(z.getLocation()) < 22 * 22 && sees(z, t)) {
                    u.shootAt = (int) tick + 45 + random.nextInt(20);
                    sludge(z, t, cfg("moves.sludge", 6));
                }
            }
            case BOMB -> {
                if (u.fuse < 0 && t != null && t.getLocation().distanceSquared(z.getLocation()) < 2.6 * 2.6) {
                    u.fuse = 30;
                    z.getWorld().playSound(z.getLocation(), Sound.ENTITY_TNT_PRIMED, 1.5f, 1f);
                }
                if (u.fuse >= 0) {
                    z.getWorld().spawnParticle(Particle.SMOKE, z.getLocation().add(0, 1.6, 0), 4, 0.2, 0.2, 0.2, 0.02);
                    if (u.fuse-- == 0) { boom(z.getLocation(), 4.5, cfg("moves.bomb", 15), z); z.setHealth(0); }
                }
            }
            case GOLDEN -> {
                if (tick % 40 == 0) {
                    for (Entity e : z.getNearbyEntities(8, 4, 8))
                        if (e instanceof Zombie o && o.getScoreboardTags().contains(TAG)) {
                            o.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, false));
                            o.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0, false, false));
                        }
                    z.getWorld().spawnParticle(Particle.WAX_ON, z.getLocation().add(0, 1.5, 0), 12, 2, 1, 2, 0);
                }
            }
            default -> { }
        }
    }

    private static boolean sees(LivingEntity a, Entity b) { try { return a.hasLineOfSight(b); } catch (RuntimeException e) { return true; } }

    void sludge(LivingEntity from, LivingEntity t, double damage) {
        Location eye = from.getEyeLocation();
        Vector v = t.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
        double d = v.length();
        v.normalize().multiply(1.25).setY(v.getY() * 1.25 + d * 0.013);
        Snowball s = from.getWorld().spawn(eye, Snowball.class, sb -> {
            sb.setItem(new ItemStack(Material.SLIME_BALL));
            sb.addScoreboardTag(SLUDGE);
            sb.addScoreboardTag(SLUDGE + "_" + (int) Math.round(damage * 10));
            sb.setShooter(from);
        });
        Safe.vel(s, v);
        from.getWorld().playSound(eye, Sound.ENTITY_SLIME_SQUISH, 1.2f, 0.6f);
    }

    @EventHandler
    public void onSludge(ProjectileHitEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(SLUDGE)) return;
        double dmg = 6;
        for (String t : e.getEntity().getScoreboardTags()) if (t.startsWith(SLUDGE + "_")) try { dmg = Integer.parseInt(t.substring(SLUDGE.length() + 1)) / 10.0; } catch (NumberFormatException ignored) { }
        if (e.getHitEntity() instanceof Player p && p.getGameMode() != GameMode.CREATIVE) {
            p.damage(dmg, e.getEntity().getShooter() instanceof Entity s ? s : null);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1));
        }
        e.getEntity().getWorld().spawnParticle(Particle.ITEM_SLIME, e.getEntity().getLocation(), 12, 0.3, 0.3, 0.3, 0.05);
    }

    /** A blast that hurts players (falling off with distance) but never breaks blocks. */
    void boom(Location at, double r, double damage, Entity by) {
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 2, 0.9f);
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(at);
            if (d > r) continue;
            p.damage(damage * (1 - d / (r + 1)), by);
            Vector away = p.getLocation().toVector().subtract(at.toVector());
            if (away.lengthSquared() < 1e-4) away = new Vector(0, 1, 0);
            Safe.vel(p, away.normalize().multiply(1.0).setY(0.6));
        }
    }

    // ================================================================= the Colossus

    Colossus spawnBoss(Location at, boolean ofRaid) {
        if (boss != null) return null;
        Location spot = spot(at, 0, 6);
        int n = Math.max(1, near(at, 90).size());
        Unit u = spawn(Kind.GRUNT, spot, 1);
        units.remove(u.body.getUniqueId());
        for (BlockDisplay d : u.parts) d.remove();
        u.parts.clear();
        Zombie z = u.body;
        z.getScoreboardTags().remove(TAG);
        z.getScoreboardTags().remove(TAG + "_grunt");
        z.addScoreboardTag(BOSS_TAG);
        z.customName(Component.text("The Porcelain Colossus", NamedTextColor.DARK_RED));
        z.setCustomNameVisible(true);
        double scale = cfg("boss.scale", 4);
        double hp = cfg("boss.health", 12000) * (1 + cfg("boss.per-fighter", 0.4) * (n - 1));
        MimicPlayer.attr(z, Attribute.SCALE, scale);
        BigHealth.set(z, hp);
        MimicPlayer.attr(z, Attribute.ATTACK_DAMAGE, cfg("boss.melee", 22));
        MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, 0.27);
        MimicPlayer.attr(z, Attribute.KNOCKBACK_RESISTANCE, 1);
        MimicPlayer.attr(z, Attribute.ARMOR, 18);
        MimicPlayer.attr(z, Attribute.ARMOR_TOUGHNESS, 10);
        MimicPlayer.attr(z, Attribute.STEP_HEIGHT, 2.5);
        MimicPlayer.attr(z, Attribute.FOLLOW_RANGE, 96);
        u.k = scale * 2.05;
        model(u, Material.QUARTZ_BLOCK);
        Colossus c = new Colossus();
        c.u = u;
        c.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "The Porcelain Colossus", BarColor.RED, BarStyle.SEGMENTED_20);
        boss = c;
        World w = spot.getWorld();
        Emotes.q(() -> w.strikeLightningEffect(spot));
        w.playSound(spot, Sound.ENTITY_WARDEN_EMERGE, 4, 0.6f);
        w.playSound(spot, Sound.ENTITY_RAVAGER_ROAR, 4, 0.5f);
        w.spawnParticle(Particle.SPLASH, spot.clone().add(0, 3, 0), 400, 3, 3, 3, 0.4);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "🚽 THE PORCELAIN COLOSSUS " + ChatColor.RED + "has risen at " + Where.spot(spot) + ". It will wreck everything.");
        return c;
    }

    private boolean grief() {
        return plugin.getConfig().getBoolean("toilet-raid.boss.grief", true);
    }

    private static final Set<Material> TOUGH = EnumSet.of(Material.BEDROCK, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.BARRIER,
            Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.NETHER_PORTAL, Material.END_GATEWAY, Material.REINFORCED_DEEPSLATE,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.LIGHT, Material.RESPAWN_ANCHOR, Material.ANCIENT_DEBRIS, Material.NETHERITE_BLOCK);

    /** Breaks one block for the Colossus (no drops; never a container / block entity / tough block / fluid). */
    boolean wreck(Block b) {
        if (!grief() || b.getWorld().getName().equals("faultline_deep")) return false;
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || TOUGH.contains(m) || m.getHardness() < 0 || m.getHardness() > 50) return false;
        if (b.getState(false) instanceof org.bukkit.block.TileState) return false;
        Location key = b.getLocation();
        griefed.putIfAbsent(key, b.getBlockData());
        griefDirty = true;
        if (griefed.size() > 200_000) griefed.remove(griefed.keySet().iterator().next());
        if (random.nextInt(4) == 0) b.getWorld().spawnParticle(Particle.BLOCK, key.clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, b.getBlockData());
        b.setType(Material.AIR, false);
        return true;
    }

    /** Breaks blocks in a box (centre, half sizes); at most `limit`. */
    private int wreckBox(Location c, double hx, double y0, double y1, double hz, int limit) {
        int n = 0;
        World w = c.getWorld();
        for (int x = (int) Math.floor(c.getX() - hx); x <= (int) Math.floor(c.getX() + hx) && n < limit; x++)
            for (int z = (int) Math.floor(c.getZ() - hz); z <= (int) Math.floor(c.getZ() + hz) && n < limit; z++)
                for (int y = (int) Math.floor(c.getY() + y0); y <= (int) Math.floor(c.getY() + y1) && n < limit; y++)
                    if (wreck(w.getBlockAt(x, y, z))) n++;
        if (n > 0 && random.nextInt(3) == 0) w.playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.5f, 0.6f);
        return n;
    }

    private void bossTick(Colossus c) {
        Zombie z = c.u.body;
        World w = z.getWorld();
        c.tick++;
        if (tick % 2 == 0) follow(c.u);
        double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
        c.bar.setProgress(Math.max(0, Math.min(1, z.getHealth() / max)));
        for (Player p : w.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(z.getLocation()) < 100 * 100;
            if (in && !c.bar.getPlayers().contains(p)) c.bar.addPlayer(p); else if (!in && c.bar.getPlayers().contains(p)) c.bar.removePlayer(p);
        }
        List<Player> ps = near(z.getLocation(), 80);
        for (Player p : ps) c.fighters.add(p.getUniqueId());
        if (ps.isEmpty()) {
            if (++c.alone > 20 * 120 && raid == null) { // 1.3.39: nobody came back for 2 minutes = it flushes itself away
                boss = null; c.bar.removeAll(); removeUnit(c.u);
                Bukkit.broadcastMessage(ChatColor.GRAY + "🚽 The Porcelain Colossus flushed itself away.");
            }
            return;
        }
        c.alone = 0;
        int phase = z.getHealth() < max * 0.3 ? 3 : z.getHealth() < max * 0.6 ? 2 : 1;
        if (phase != c.phase) {
            c.phase = phase;
            w.playSound(z.getLocation(), Sound.ENTITY_WITHER_SPAWN, 3, 0.6f);
            for (Player p : ps) p.sendTitle(ChatColor.DARK_RED + (phase == 3 ? "IT'S OVERFLOWING" : "IT'S ANGRY"), "", 5, 40, 10);
            if (phase == 3) {
                z.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
                z.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 1, false, false));
            }
        }
        if (c.target == null || !c.target.isValid() || c.target.isDead() || !ps.contains(c.target) || c.tick % 100 == 0) c.target = ps.get(random.nextInt(ps.size()));
        if (z.getTarget() != c.target) z.setTarget(c.target);
        // walks through builds: whatever is in front of it and round its body goes
        if (c.tick % 4 == 0) {
            Vector fw = z.getLocation().getDirection().setY(0);
            if (fw.lengthSquared() > 1e-4) fw.normalize();
            double s = cfg("boss.scale", 4);
            wreckBox(z.getLocation().add(fw.multiply(0.9 * s)), 0.9 * s, 0.6, 2.0 * s, 0.9 * s, 40);
        }
        if (phase == 3 && c.tick % 3 == 0) w.spawnParticle(Particle.DUST, z.getLocation().add(0, 3, 0), 6, 2, 2, 2, 0, new Particle.DustOptions(Color.fromRGB(0x6FBF3A), 2.5f));
        if (c.move != 0) { moveTick(c, ps); return; }
        if (c.tick < c.nextMove) return;
        int[] pool = phase == 1 ? new int[]{1, 2, 3, 4, 5} : phase == 2 ? new int[]{1, 2, 3, 4, 5, 6} : new int[]{1, 2, 3, 4, 6, 7, 3, 4};
        c.move = pool[random.nextInt(pool.length)];
        if (c.move == 5 && units.size() > 20) c.move = 3;
        c.moveT = 0;
        moveTick(c, ps);
    }

    private void endMove(Colossus c) {
        c.move = 0;
        c.nextMove = c.tick + (c.phase == 1 ? 70 : c.phase == 2 ? 50 : 34) + random.nextInt(20);
    }

    private void moveTick(Colossus c, List<Player> ps) {
        Zombie z = c.u.body;
        World w = z.getWorld();
        Location at = z.getLocation();
        double s = cfg("boss.scale", 4);
        int t = c.moveT++;
        switch (c.move) {
            case 1 -> { // LID SLAM: leaps, comes down in a shockwave, leaves a crater
                if (t == 0) { Safe.vel(z, c.target.getLocation().toVector().subtract(at.toVector()).setY(0).multiply(0.08).setY(1.25)); w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 3, 0.7f); }
                if (t > 6 && (((Entity) z).isOnGround() || t > 60)) {
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4, 0.5f);
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 2, 0.5, 2);
                    for (int i = 0; i < 64; i++) { double a = Math.PI * 2 * i / 64; w.spawnParticle(Particle.CLOUD, at.clone().add(Math.cos(a) * 9, 0.3, Math.sin(a) * 9), 1, 0, 0, 0, 0.05); }
                    for (Player p : ps) {
                        double d = p.getLocation().distance(at);
                        if (d > 10) continue;
                        p.damage(cfg("boss.moves.slam", 20) * (1 - d / 12), z);
                        Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                        Safe.vel(p, away.normalize().multiply(1.2).setY(0.9));
                    }
                    // the crater
                    int r = 3;
                    for (int x = -r; x <= r; x++) for (int y = -2; y <= 0; y++) for (int zz = -r; zz <= r; zz++)
                        if (x * x + y * y * 2 + zz * zz <= r * r) wreck(at.clone().add(x, y - 0.5, zz).getBlock());
                    endMove(c);
                }
            }
            case 2 -> { // THE FLUSH: everyone within 24 is sucked in, then the swirl hits the ones close
                if (t == 0) w.playSound(at, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 4, 0.5f);
                for (int i = 0; i < 6; i++) {
                    double a = (t * 0.35) + i * Math.PI / 3, r = 8 - (t % 40) / 6.0;
                    w.spawnParticle(Particle.SPLASH, at.clone().add(Math.cos(a) * r, 0.5 + i * 0.3, Math.sin(a) * r), 4, 0.2, 0.2, 0.2, 0);
                    w.spawnParticle(Particle.BUBBLE_POP, at.clone().add(Math.cos(a) * r * 0.6, 1, Math.sin(a) * r * 0.6), 2, 0.2, 0.2, 0.2, 0);
                }
                for (Player p : ps) {
                    Vector to = at.toVector().subtract(p.getLocation().toVector());
                    double d = to.length();
                    if (d > 24 || d < 1) continue;
                    Safe.vel(p, p.getVelocity().add(to.normalize().multiply(0.07)));
                }
                if (t == 55) {
                    w.playSound(at, Sound.ITEM_BUCKET_EMPTY, 4, 0.5f);
                    for (Player p : ps) if (p.getLocation().distance(at) < 6) {
                        p.damage(cfg("boss.moves.flush", 18), z);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
                    }
                    endMove(c);
                }
            }
            case 3 -> { // EYE LASER: a beam that follows its target, hurts and cuts through blocks
                Location eye = z.getEyeLocation();
                if (t == 0) { c.beam = c.target.getLocation().add(0, 1, 0); w.playSound(eye, Sound.BLOCK_BEACON_ACTIVATE, 3, 2f); }
                Location goal = c.target.getLocation().add(0, 1, 0);
                if (goal.getWorld() == eye.getWorld()) c.beam.add(goal.toVector().subtract(c.beam.toVector()).multiply(t < 15 ? 0.25 : 0.09));
                Vector dir = c.beam.toVector().subtract(eye.toVector());
                double len = Math.min(48, dir.length());
                if (len < 0.5) { endMove(c); break; }
                dir.normalize();
                boolean firing = t >= 15;
                int cut = 0;
                for (double d = 1; d < len; d += 0.6) {
                    Location pnt = eye.clone().add(dir.clone().multiply(d));
                    w.spawnParticle(Particle.DUST, pnt, 1, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(firing ? Color.RED : Color.fromRGB(0xFF9090), firing ? 1.6f : 0.7f));
                    if (firing && cut < 6 && wreck(pnt.getBlock())) cut++;
                }
                if (firing && t % 4 == 0) {
                    for (Player p : ps) {
                        Vector rel = p.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
                        double along = rel.dot(dir);
                        if (along < 0 || along > len) continue;
                        if (rel.clone().subtract(dir.clone().multiply(along)).length() < 1.4) { p.damage(cfg("boss.moves.laser", 5), z); p.setFireTicks(40); }
                    }
                    w.playSound(eye, Sound.BLOCK_FIRE_EXTINGUISH, 1, 1.6f);
                }
                if (t > 70) endMove(c);
            }
            case 4 -> { // RAMPAGE: charges at its target, straight through walls
                if (t == 0) {
                    c.charge = c.target.getLocation().toVector().subtract(at.toVector()).setY(0);
                    if (c.charge.lengthSquared() < 1e-4) c.charge = new Vector(1, 0, 0);
                    c.charge.normalize();
                    w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 4, 0.5f);
                }
                if (t < 15) { w.spawnParticle(Particle.ANGRY_VILLAGER, at.clone().add(0, 4, 0), 1, 1, 1, 1); break; }
                Location face = at.clone(); face.setDirection(c.charge);
                Safe.vel(z, c.charge.clone().multiply(c.phase == 3 ? 1.0 : 0.8).setY(z.getVelocity().getY()));
                wreckBox(at.clone().add(c.charge.clone().multiply(1.3 * s)), 1.1 * s, 0.6, 2.2 * s, 1.1 * s, 60);
                for (Player p : ps) if (p.getLocation().distanceSquared(at) < (1.4 * s) * (1.4 * s) && p.getNoDamageTicks() <= 10) {
                    p.damage(cfg("boss.moves.charge", 24), z);
                    Safe.vel(p, c.charge.clone().multiply(2.0).setY(0.9));
                }
                if (t > 45) endMove(c);
            }
            case 5 -> { // SUMMON
                for (int i = 0; i < 5; i++) {
                    Kind k = new Kind[]{Kind.GRUNT, Kind.SPEEDER, Kind.PLUNGER, Kind.BOMB, Kind.TANK}[random.nextInt(5)];
                    spawn(k, spot(at, 3, 9), 1.3);
                }
                w.playSound(at, Sound.EVENT_RAID_HORN, 4, 1.4f);
                endMove(c);
            }
            case 6 -> { // SLUDGE BARRAGE
                if (t % 10 == 0) for (Player p : ps) sludge(z, p, cfg("boss.moves.sludge", 9));
                if (t >= 30) endMove(c);
            }
            case 7 -> { // TOXIC OVERFLOW: poison pools under everyone
                for (Player p : ps) {
                    AreaEffectCloud cl = (AreaEffectCloud) w.spawnEntity(p.getLocation(), EntityType.AREA_EFFECT_CLOUD, false);
                    cl.setRadius(3.5f);
                    cl.setDuration(120);
                    cl.setColor(Color.fromRGB(0x6FBF3A));
                    cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 2), true);
                    cl.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1), true);
                    cl.setSource(z);
                }
                w.playSound(at, Sound.ENTITY_WITCH_THROW, 3, 0.5f);
                endMove(c);
            }
            default -> endMove(c);
        }
    }

    int restore() {
        int n = 0;
        for (var e : griefed.entrySet()) {
            Block b = e.getKey().getBlock();
            if (b.getType().isAir() || b.isLiquid()) { b.setBlockData(e.getValue(), false); n++; }
        }
        griefed.clear();
        griefDirty = true;
        saveGrief();
        return n;
    }

    // ================================================================= events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHurt(EntityDamageEvent e) {
        Colossus c = boss;
        if (c != null && e.getEntity() == c.u.body) {
            if (e.getCause() == EntityDamageEvent.DamageCause.FALL || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                    || e.getCause() == EntityDamageEvent.DamageCause.DROWNING) { e.setCancelled(true); return; }
            e.setDamage(e.getDamage() * (1 - cfg("boss.damage-reduction", 0.25)));
            if (e instanceof EntityDamageByEntityEvent be) { Player by = TotemLimit.playerBehind(be.getDamager()); if (by != null) c.damage.merge(by.getUniqueId(), e.getFinalDamage(), Double::sum); }
            return;
        }
        if (e.getEntity() instanceof Zombie z && units.containsKey(z.getUniqueId())
                && (e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION || e.getCause() == EntityDamageEvent.DamageCause.DROWNING)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargetOther(EntityTargetEvent e) {
        // toilets never fight each other or other mobs; only players
        if (e.getEntity().getScoreboardTags().contains(TAG) || e.getEntity().getScoreboardTags().contains(BOSS_TAG))
            if (e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG) || e.getEntity().getScoreboardTags().contains(BOSS_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG) && !(e instanceof EntityCombustByEntityEvent)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Unit u = units.get(e.getEntity().getUniqueId());
        if (u != null) {
            e.getDrops().clear();
            e.setDroppedExp(u.kind == Kind.TANK || u.kind == Kind.GOLDEN ? 40 : 12);
            if (u.kind == Kind.GOLDEN) e.getDrops().add(new ItemStack(Material.GOLD_INGOT, 4 + random.nextInt(5)));
            e.getEntity().getWorld().playSound(e.getEntity().getLocation(), Sound.ITEM_BUCKET_FILL, 1.2f, 0.6f);
            for (BlockDisplay d : u.parts) if (d.isValid()) d.remove();
            units.remove(u.body.getUniqueId());
            if (e.getEntity().getKiller() != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + e.getEntity().getKiller().getName() + " toilets_flushed 1");
            return;
        }
        Colossus c = boss;
        if (c == null || e.getEntity() != c.u.body) return;
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.NETHERITE_INGOT, 3));
        e.getDrops().add(new ItemStack(Material.DIAMOND_BLOCK, 6));
        e.setDroppedExp((int) cfg("boss.xp", 9000));
        boss = null;
        c.bar.removeAll();
        for (BlockDisplay d : c.u.parts) if (d.isValid()) d.remove();
        Set<UUID> all = new HashSet<>(c.fighters);
        if (raid != null) all.addAll(raid.fighters);
        int given = 0;
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            rewards(plugin, p, "toilet-raid.rewards", DEFAULT_REWARDS, random);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " toilet_colossus 1");
            given++;
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "🚽 THE PORCELAIN COLOSSUS HAS BEEN FLUSHED! " + ChatColor.YELLOW + given + " fighter" + (given == 1 ? "" : "s") + " share the spoils.");
        Location at = c.u.body.getLocation();
        at.getWorld().spawnParticle(Particle.SPLASH, at.clone().add(0, 3, 0), 600, 4, 4, 4, 0.5);
        at.getWorld().playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 3, 1);
        Raid r = raid;
        raid = null;
        if (r != null) r.bar.removeAll();
        for (Unit o : new ArrayList<>(units.values())) removeUnit(o);
        if (plugin.getConfig().getBoolean("toilet-raid.boss.restore-after-fight", false))
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getLogger().info("[ToiletRaid] restored " + restore() + " blocks"), 200);
    }

    static final List<String> DEFAULT_REWARDS = List.of(
            "givemythicbag 4 {player}", "givegoodiebag 8 {player}", "darkloot bag 3 {player}",
            "fenchant give {enchant} 2 1 {player}", "fenchant give {enchant} 2 1 {player}",
            "item NETHERITE_INGOT 4", "item DIAMOND_BLOCK 6", "item ENCHANTED_GOLDEN_APPLE 3", "item TOTEM_OF_UNDYING 2",
            "item EXPERIENCE_BOTTLE 64", "item NETHER_STAR 1");

    static final String[] ENCHANTS = {"lifesteal", "venom", "thunderstrike", "executioner", "cleave", "bleed", "vitality", "arrowguard", "thornhide", "swiftstride", "featherstep", "veinminer"};

    /** Runs a reward list for a player: "item MATERIAL n" gives items; anything else is a console command ({player}, {enchant}). */
    static void rewards(FaultlineItems plugin, Player p, String path, List<String> defaults, Random random) {
        List<String> list = plugin.getConfig().isList(path) ? plugin.getConfig().getStringList(path) : defaults;
        for (String line : list) {
            try {
                String l = line.replace("{player}", p.getName()).replace("{enchant}", ENCHANTS[random.nextInt(ENCHANTS.length)]);
                if (l.startsWith("item ")) {
                    String[] a = l.split(" ");
                    Material m = Material.matchMaterial(a[1]);
                    if (m == null) continue;
                    int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                    while (n > 0) {
                        int take = Math.min(n, m.getMaxStackSize());
                        for (ItemStack left : p.getInventory().addItem(new ItemStack(m, take)).values()) p.getWorld().dropItem(p.getLocation(), left);
                        n -= take;
                    }
                } else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), l);
            } catch (RuntimeException ex) { plugin.getLogger().warning("[Rewards] " + line + ": " + ex.getMessage()); }
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
    }

    // ================================================================= tick

    private void tick() {
        tick++;
        try {
            if (tick % 1200 == 0) saveGrief();
            Raid r = raid;
            if (r != null) raidTick(r);
            Colossus c = boss;
            if (c != null) {
                if (!c.u.body.isValid() || c.u.body.isDead()) { if (!c.u.body.isDead()) { boss = null; c.bar.removeAll(); removeUnit(c.u); } }
                else bossTick(c);
            }
            for (Unit u : new ArrayList<>(units.values())) {
                if (!u.body.isValid() || u.body.isDead()) { if (!u.body.isDead()) removeUnit(u); else units.remove(u.body.getUniqueId()); continue; }
                try { unitTick(u); } catch (RuntimeException ex) { if (tick % 200 == 0) plugin.getLogger().warning("[ToiletRaid] " + u.kind.id + ": " + ex); }
            }
        } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[ToiletRaid] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    void shutdown() {
        stop();
        if (plugin.getConfig().getBoolean("toilet-raid.boss.restore-after-fight", false)) restore();
        saveGrief();
    }

    // ================================================================= /toiletraid

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "start" -> {
                if (p == null) { s.sendMessage("Players only."); return true; }
                int waves = a.length > 1 ? Legendary.parseInt(a[1], (int) cfg("waves", 10)) : (int) cfg("waves", 10);
                s.sendMessage(start(p.getLocation(), waves) ? ChatColor.GREEN + "Toilet Raid started (" + waves + " waves)." : ChatColor.RED + "One is already going (/toiletraid stop).");
            }
            case "boss" -> {
                if (p == null) { s.sendMessage("Players only."); return true; }
                if (boss != null) { s.sendMessage(ChatColor.RED + "The Colossus is already out."); return true; }
                spawnBoss(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(8)), false);
            }
            case "spawn" -> {
                if (p == null || a.length < 2 || Kind.of(a[1]) == null) { s.sendMessage(ChatColor.RED + "/toiletraid spawn <grunt|speeder|plunger|tank|bomb|golden> [n]"); return true; }
                int n = a.length > 2 ? Legendary.parseInt(a[2], 1) : 1;
                for (int i = 0; i < Math.min(30, n); i++) spawn(Kind.of(a[1]), spot(p.getLocation(), 3, 7), 1);
            }
            case "stop" -> { stop(); s.sendMessage(ChatColor.YELLOW + "Toilet Raid stopped."); }
            case "restore" -> s.sendMessage(ChatColor.GREEN + "Put back " + restore() + " blocks the Colossus broke.");
            case "plunger" -> {
                if (p == null) { s.sendMessage("Players only."); return true; }
                p.getInventory().addItem(plunger(1));
            }
            default -> s.sendMessage(ChatColor.AQUA + (raid == null ? "No Toilet Raid." : "Raid at " + Where.spot(raid.center) + ", wave " + raid.wave + "/" + raid.waves)
                    + (boss == null ? "" : " Colossus: " + Math.round(boss.u.body.getHealth()) + " hp.") + ChatColor.GRAY + " (" + units.size() + " toilets, "
                    + griefed.size() + " blocks to restore)  /toiletraid start [waves] | boss | spawn <kind> [n] | stop | restore | plunger");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("start", "boss", "spawn", "stop", "restore", "plunger", "status"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) { List<String> l = new ArrayList<>(); for (Kind k : Kind.values()) l.add(k.id); return Legendary.filter(l, a[1]); }
        return List.of();
    }
}
