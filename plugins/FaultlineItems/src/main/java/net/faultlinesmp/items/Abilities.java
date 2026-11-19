package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * PERSONAL ABILITIES (Items 1.3.33, config abilities:, data abilities.yml). Admins get the five ability items from /adminmenu
 * (op only). Right-clicking one binds that ability to you FOREVER (the item is used up; it's saved by UUID, survives
 * relogs / restarts / deaths). One ability per player; only an admin can take it away (/adminmenu: click their head, or
 * /ability remove <player>).
 *  - FROST (Star):     your hits (melee and your arrows) can frostbite: Slowness + the freezing overlay.
 *  - AIM (Lucian):     your arrows lock onto the player / mob closest to where you aim (in sight) and curve to them; a shield
 *                      still blocks them. An arrow that hits a wall bounces off it at someone in sight (up to 2 bounces).
 *  - SPEED (Voise):    move 1.2x as fast (was 2x until 1.3.34).
 *  - HOPS (Zombie):    jump higher (mace slams hit harder from higher), a little more safe fall.
 *  - SHADE (Sin):      Strength II, Swiftness II, Fire Resistance forever, and Invisibility until a totem pops; it comes back
 *                      `shade.invis-return-seconds` (300) later, or when you respawn.
 */
final class Abilities implements Listener, CommandExecutor, TabCompleter {

    enum Kind {
        FROST("frost", "Frostbite", "Star", Material.PAPER, "blue_ice", ChatColor.AQUA,
                "Your hits can frostbite: Slowness and the freezing chill.", "Wins team fights by slowing everyone down."),
        AIM("aim", "Hawkeye", "Lucian", Material.PAPER, "spectral_arrow", ChatColor.GOLD,
                "Your arrows lock onto whoever you aim at and curve to them.", "Arrows bounce off walls at someone in sight. Shields still block."),
        SPEED("speed", "Swiftwind", "Voise", Material.PAPER, "feather", ChatColor.WHITE,
                "You move 20% faster.", "Perfect for getting away."),
        HOPS("hops", "Skyhop", "Zombie", Material.PAPER, "rabbit_foot", ChatColor.GREEN,
                "You jump higher (and slam harder with a mace).", "A little more safe fall too."),
        SHADE("shade", "Shade", "Sin", Material.PAPER, "echo_shard", ChatColor.DARK_PURPLE,
                "Strength II, Swiftness II and Fire Resistance, forever.", "Invisible until a totem pops (it comes back later).");

        final String id, title, owner, model, line1, line2;
        final Material base;
        final ChatColor color;
        Kind(String id, String title, String owner, Material base, String model, ChatColor color, String line1, String line2) {
            this.id = id; this.title = title; this.owner = owner; this.base = base; this.model = model; this.color = color; this.line1 = line1; this.line2 = line2;
        }
        static Kind of(String s) { for (Kind k : values()) if (k.id.equalsIgnoreCase(s) || k.title.equalsIgnoreCase(s) || k.owner.equalsIgnoreCase(s)) return k; return null; }
    }

    static final String ADMIN = "items.abilities.admin";

    private final FaultlineItems plugin;
    private final File file;
    private final Map<UUID, Kind> owned = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final NamespacedKey itemKey, arrowKey, speedKey, jumpKey, fallKey;
    private final Map<UUID, Long> frostReady = new HashMap<>();
    /** Sin: invisibility gone (popped) until this time (ms); absent = invisible. */
    private final Map<UUID, Long> invisBackAt = new HashMap<>();
    /** Lucian's arrows in flight -> target. */
    private final Map<UUID, Homing> homing = new HashMap<>();
    private long tick;

    record Homing(AbstractArrow arrow, UUID target, long until) {}

    Abilities(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "abilities.yml");
        itemKey = new NamespacedKey(plugin, "ability_item");
        arrowKey = new NamespacedKey(plugin, "ability_arrow");
        speedKey = new NamespacedKey(plugin, "ability_speed");
        jumpKey = new NamespacedKey(plugin, "ability_jump");
        fallKey = new NamespacedKey(plugin, "ability_fall");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    private boolean on() { return plugin.getConfig().getBoolean("abilities.enabled", true); }
    private double cfg(String path, double def) { return plugin.getConfig().getDouble("abilities." + path, def); }

    Kind of(Player p) { return on() ? owned.get(p.getUniqueId()) : null; }

    // ================================================================= data

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                Kind kind = Kind.of(s.getString(k + ".ability", ""));
                if (kind == null) continue;
                UUID id = UUID.fromString(k);
                owned.put(id, kind);
                names.put(id, s.getString(k + ".name", "?"));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Kind> e : owned.entrySet()) {
            y.set("players." + e.getKey() + ".ability", e.getValue().id);
            y.set("players." + e.getKey() + ".name", names.getOrDefault(e.getKey(), "?"));
        }
        SafeYaml.write(plugin, file, y.saveToString());
    }

    void grant(UUID id, String name, Kind k) {
        owned.put(id, k);
        names.put(id, name);
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null) { strip(p); apply(p); }
        plugin.getLogger().info("[Abilities] " + name + " now has " + k.title);
    }

    boolean remove(UUID id) {
        Kind k = owned.remove(id);
        if (k == null) return false;
        String n = names.remove(id);
        invisBackAt.remove(id);
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null) { strip(p); p.sendMessage(ChatColor.GRAY + "Your " + k.title + " ability was removed by an admin."); }
        plugin.getLogger().info("[Abilities] removed " + k.title + " from " + n);
        return true;
    }

    // ================================================================= the items

    ItemStack item(Kind k) {
        ItemStack it = new ItemStack(k.base);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(k.color + "" + ChatColor.BOLD + "Ability: " + k.title);
        m.setLore(List.of(ChatColor.GRAY + k.line1, ChatColor.GRAY + k.line2, "",
                ChatColor.DARK_GRAY + "Made for " + k.owner + ".",
                ChatColor.YELLOW + "Right-click: it's yours forever (one ability each)."));
        try { m.setItemModel(NamespacedKey.minecraft(k.model)); } catch (Throwable ignored) { }
        try { m.setEnchantmentGlintOverride(true); } catch (Throwable ignored) { }
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, k.id);
        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "ability_" + k.id), PersistentDataType.BYTE, (byte) 1); // Index detect
        it.setItemMeta(m);
        return it;
    }

    Kind itemKind(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return null;
        String id = it.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : Kind.of(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Kind k = itemKind(e.getItem());
        if (k == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!on()) { p.sendMessage(ChatColor.GRAY + "Abilities are turned off right now."); return; }
        Kind have = owned.get(p.getUniqueId());
        if (have == k) { p.sendMessage(ChatColor.GRAY + "You already have " + k.title + "."); return; }
        if (have != null) { p.sendMessage(ChatColor.RED + "You already have " + have.title + ". One ability each: ask an admin to remove it first."); return; }
        boolean off = e.getHand() == EquipmentSlot.OFF_HAND;
        ItemStack hand = off ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        ItemStack left = hand.getAmount() <= 1 ? null : hand.clone();
        if (left != null) left.setAmount(hand.getAmount() - 1);
        if (off) p.getInventory().setItemInOffHand(left); else p.getInventory().setItemInMainHand(left);
        grant(p.getUniqueId(), p.getName(), k);
        p.sendTitle(k.color + "" + ChatColor.BOLD + k.title, ChatColor.GRAY + "This ability is yours forever.", 10, 50, 15);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.3);
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + p.getName() + " ability_" + k.id); } catch (RuntimeException ignored) { }
    }

    // ================================================================= passive powers

    private void tick() {
        tick++;
        if (tick % 20 == 0) for (Player p : Bukkit.getOnlinePlayers()) {
            try { apply(p); } catch (RuntimeException ex) { if (tick % 1200 == 0) plugin.getLogger().warning("[Abilities] " + p.getName() + ": " + ex); }
        }
        if (!homing.isEmpty()) steer();
        if (tick % 1200 == 0) frostReady.values().removeIf(t -> t < tick); // mobs Star hit (they never "quit")
    }

    /** Puts this player's ability on (and nothing else). Every second. */
    void apply(Player p) {
        Kind k = of(p);
        modifier(p, Attribute.MOVEMENT_SPEED, speedKey, k == Kind.SPEED ? Balance.num(plugin, "abilities.speed.multiplier", 2.0, 1.2) - 1 : 0 /* 1.3.34: x1.2 (an old config's 2.0 reads as 1.2) */);
        modifier(p, Attribute.JUMP_STRENGTH, jumpKey, k == Kind.HOPS ? Balance.num(plugin, "abilities.hops.jump-bonus", 0.35, 0.42) : 0);
        modifier(p, Attribute.SAFE_FALL_DISTANCE, fallKey, k == Kind.HOPS ? cfg("hops.safe-fall-bonus", 0.5) : 0);
        if (k == Kind.SHADE && p.getGameMode() != GameMode.SPECTATOR && !p.isDead()) {
            effect(p, PotionEffectType.STRENGTH, (int) cfg("shade.strength-level", 2) - 1);
            effect(p, PotionEffectType.SPEED, (int) cfg("shade.swiftness-level", 2) - 1);
            effect(p, PotionEffectType.FIRE_RESISTANCE, 0);
            Long back = invisBackAt.get(p.getUniqueId());
            if (back != null && back > 0 && System.currentTimeMillis() >= back) { invisBackAt.remove(p.getUniqueId()); back = null; p.sendMessage(ChatColor.DARK_PURPLE + "You fade back into the shadows."); }
            if (back == null) effect(p, PotionEffectType.INVISIBILITY, 0);
        }
    }

    /** Our modifier at exactly this amount (MULTIPLY_SCALAR_1); 0 = none. */
    private static void modifier(Player p, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        AttributeModifier have = null;
        for (AttributeModifier m : inst.getModifiers()) if (key.equals(m.getKey())) have = m;
        if (have != null && (amount == 0 || Math.abs(have.getAmount() - amount) > 1e-9)) { inst.removeModifier(have); have = null; }
        if (have == null && amount != 0) inst.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    /** An endless effect at this level (hidden particles), unless a stronger one is running. */
    private static void effect(Player p, PotionEffectType t, int amp) {
        PotionEffect cur = p.getPotionEffect(t);
        if (cur != null && (cur.getAmplifier() > amp || cur.getAmplifier() == amp && cur.isInfinite())) return;
        p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, true, false, true));
    }

    /** Takes off everything an ability gave (removed, or a different one now). */
    void strip(Player p) {
        modifier(p, Attribute.MOVEMENT_SPEED, speedKey, 0);
        modifier(p, Attribute.JUMP_STRENGTH, jumpKey, 0);
        modifier(p, Attribute.SAFE_FALL_DISTANCE, fallKey, 0);
        if (owned.get(p.getUniqueId()) != Kind.SHADE)
            for (PotionEffectType t : List.of(PotionEffectType.STRENGTH, PotionEffectType.SPEED, PotionEffectType.FIRE_RESISTANCE, PotionEffectType.INVISIBILITY)) {
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null && cur.isInfinite() && cur.isAmbient()) p.removePotionEffect(t); // only ours (endless + ambient)
            }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (owned.containsKey(p.getUniqueId())) names.put(p.getUniqueId(), p.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { strip(p); apply(p); } }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { frostReady.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        invisBackAt.remove(p.getUniqueId()); // Sin's invisibility is back after a death
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) apply(p); }, 2L);
    }

    void disable() { for (Player p : Bukkit.getOnlinePlayers()) { modifier(p, Attribute.MOVEMENT_SPEED, speedKey, 0); modifier(p, Attribute.JUMP_STRENGTH, jumpKey, 0); modifier(p, Attribute.SAFE_FALL_DISTANCE, fallKey, 0); } }

    // ---------------- SHADE: a totem pop takes the invisibility

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || of(p) != Kind.SHADE) return;
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        double secs = cfg("shade.invis-return-seconds", 300);
        invisBackAt.put(p.getUniqueId(), secs <= 0 ? -1L : System.currentTimeMillis() + (long) (secs * 1000));
        p.sendMessage(ChatColor.DARK_PURPLE + "Your totem broke the shadows: you're visible" + (secs > 0 ? " for " + (int) Math.round(secs / 60) + " min." : " until you respawn."));
    }

    // ---------------- FROST: hits frostbite

    private static Player shooterOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand || victim.isInvisible() && !(victim instanceof Player)) return;
        Player p = shooterOf(e.getDamager());
        if (p == null || p.equals(victim) || of(p) != Kind.FROST) return;
        long now = tick; // server ticks (lag stretches it like the fight itself)
        Long ready = frostReady.get(victim.getUniqueId());
        if (ready != null && now < ready) return;
        if (Math.random() >= cfg("frost.chance", 0.5)) return;
        frostReady.put(victim.getUniqueId(), now + (long) (cfg("frost.cooldown-seconds", 1.5) * 20));
        int ticks = (int) (cfg("frost.seconds", 3) * 20);
        int amp = Math.max(0, (int) cfg("frost.slowness-level", 2) - 1);
        PotionEffect cur = victim.getPotionEffect(PotionEffectType.SLOWNESS);
        if (cur == null || cur.getAmplifier() <= amp) victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, amp, false, true, true));
        try { victim.setFreezeTicks(Math.min(victim.getMaxFreezeTicks() - 1, Math.max(victim.getFreezeTicks(), 0) + ticks)); } catch (Throwable ignored) { }
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        victim.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 18, 0.3, 0.5, 0.3, 0.02);
        victim.getWorld().playSound(at, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.6f);
        if (victim instanceof Player v) v.sendActionBar(net.kyori.adventure.text.Component.text("Frostbitten!", net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    // ---------------- AIM: homing arrows + wall bounces

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof Player p) || of(p) != Kind.AIM) return;
        arrow.getPersistentDataContainer().set(arrowKey, PersistentDataType.INTEGER, (int) cfg("aim.bounces", 2));
        Bukkit.getScheduler().runTask(plugin, () -> { // its velocity is set by now
            if (!arrow.isValid()) return;
            LivingEntity t = pick(p.getEyeLocation(), p.getEyeLocation().getDirection(), p, cfg("aim.cone-degrees", 14), cfg("aim.range", 64));
            if (t != null) homing.put(arrow.getUniqueId(), new Homing(arrow, t.getUniqueId(), tick + 100));
        });
    }

    /** The living thing nearest the aim line (within the cone) that the eye can see. */
    LivingEntity pick(Location eye, Vector dir, Player shooter, double coneDeg, double range) {
        dir = dir.clone().normalize();
        double cos = Math.cos(Math.toRadians(coneDeg));
        LivingEntity best = null; double bestScore = -1;
        for (Entity en : eye.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(en instanceof LivingEntity le) || !valid(le, shooter)) continue;
            Vector to = aimPoint(le).toVector().subtract(eye.toVector());
            double d = to.length();
            if (d < 0.5 || d > range) continue;
            double c = to.multiply(1 / d).dot(dir);
            if (c < cos) continue;
            if (!clear(eye, aimPoint(le))) continue;
            double score = c - d / (range * 20) + (le instanceof Player ? 0.02 : 0); // straightest line, then nearest; players first
            if (score > bestScore) { bestScore = score; best = le; }
        }
        return best;
    }

    private static boolean valid(LivingEntity le, Player shooter) {
        if (le.equals(shooter) || le.isDead() || le instanceof ArmorStand || le.isInvisible()) return false;
        if (le instanceof Player o && (o.getGameMode() == GameMode.SPECTATOR || o.getGameMode() == GameMode.CREATIVE)) return false;
        if (le instanceof Tameable t && shooter.equals(t.getOwner())) return false;
        for (String tag : le.getScoreboardTags()) if (tag.contains("hitbox") || tag.equals("faultline_bedrock_stand")) return false;
        return true;
    }

    private static Location aimPoint(LivingEntity le) { return le.getLocation().add(0, le.getHeight() * 0.6, 0); }

    /** No solid block between the two points (a block walk). */
    private static boolean clear(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 1e-3) return true;
        d.multiply(1 / len);
        World w = a.getWorld();
        for (double t = 0.4; t < len - 0.4; t += 0.4) {
            double x = a.getX() + d.getX() * t, y = a.getY() + d.getY() * t, z = a.getZ() + d.getZ() * t;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            if (w.getBlockAt(bx, by, bz).getType().isOccluding()) return false;
        }
        return true;
    }

    /** Every tick: each locked arrow turns toward its target, keeping its speed. */
    private void steer() {
        double turn = Math.max(0.05, Math.min(1, cfg("aim.turn", 0.35)));
        for (Iterator<Homing> it = homing.values().iterator(); it.hasNext(); ) {
            Homing h = it.next();
            AbstractArrow a = h.arrow();
            Entity t = Bukkit.getEntity(h.target());
            if (!a.isValid() || a.isInBlock() || a.isOnGround() || tick > h.until() || !(t instanceof LivingEntity le) || le.isDead()
                    || t.getWorld() != a.getWorld() || t.getLocation().distanceSquared(a.getLocation()) > 128 * 128) { it.remove(); continue; }
            Vector v = a.getVelocity();
            double speed = v.length();
            if (speed < 0.2) { it.remove(); continue; }
            Vector want = aimPoint(le).toVector().subtract(a.getLocation().toVector());
            if (want.lengthSquared() < 1e-6) continue;
            Vector dir = v.multiply(1 / speed).multiply(1 - turn).add(want.normalize().multiply(turn));
            if (dir.lengthSquared() < 1e-6) continue;
            Safe.vel(a, dir.normalize().multiply(speed));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a) || e.getHitBlock() == null || e.isCancelled()) return;
        Integer left = a.getPersistentDataContainer().get(arrowKey, PersistentDataType.INTEGER);
        if (left == null || left <= 0 || !(a.getShooter() instanceof Player p) || of(p) != Kind.AIM) return;
        BlockFace face = e.getHitBlockFace();
        Vector n = face == null ? new Vector() : face.getDirection();
        Location from = a.getLocation().add(n.clone().multiply(0.35));
        Homing was = homing.remove(a.getUniqueId());
        LivingEntity target = null;
        if (was != null && Bukkit.getEntity(was.target()) instanceof LivingEntity le && valid(le, p) && clear(from, aimPoint(le))
                && le.getLocation().distanceSquared(from) < cfg("aim.bounce-range", 32) * cfg("aim.bounce-range", 32)) target = le;
        if (target == null) { // anyone in sight of the bounce: a full circle around it
            double r = cfg("aim.bounce-range", 32), best = Double.MAX_VALUE;
            for (Entity en : from.getWorld().getNearbyEntities(from, r, r, r)) {
                if (!(en instanceof LivingEntity le) || !valid(le, p) || !clear(from, aimPoint(le))) continue;
                double d = le.getLocation().distanceSquared(from) - (le instanceof Player ? 64 : 0);
                if (d < best) { best = d; target = le; }
            }
        }
        if (target == null) return;
        LivingEntity tgt = target;
        double speed = Math.max(1.6, a.getVelocity().length() * 0.85);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!a.isValid()) return;
            try {
                AbstractArrow b = (AbstractArrow) from.getWorld().spawnEntity(from, a.getType(), false);
                b.setShooter(p);
                b.setDamage(a.getDamage());
                b.setCritical(a.isCritical());
                b.setPierceLevel(a.getPierceLevel());
                b.setFireTicks(a.getFireTicks());
                b.setPickupStatus(a.getPickupStatus());
                try { b.setItemStack(a.getItemStack()); } catch (Throwable ignored) { }
                if (a instanceof org.bukkit.entity.Arrow ta && b instanceof org.bukkit.entity.Arrow tb)
                    try { if (ta.getBasePotionType() != null) tb.setBasePotionType(ta.getBasePotionType()); } catch (Throwable ignored) { }
                b.getPersistentDataContainer().set(arrowKey, PersistentDataType.INTEGER, left - 1);
                Safe.vel(b, aimPoint(tgt).toVector().subtract(from.toVector()).normalize().multiply(speed));
                homing.put(b.getUniqueId(), new Homing(b, tgt.getUniqueId(), tick + 60));
                a.remove(); // the same arrow, carrying on: no extra arrow to pick up
                from.getWorld().spawnParticle(Particle.CRIT, from, 10, 0.1, 0.1, 0.1, 0.2);
                from.getWorld().playSound(from, Sound.ENTITY_ARROW_HIT, 0.8f, 1.6f);
            } catch (RuntimeException ex) { plugin.getLogger().warning("[Abilities] bounce: " + ex); }
        });
    }

    // ================================================================= /adminmenu + /ability

    static final class Menu implements InventoryHolder {
        Inventory inv;
        final List<UUID> heads = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    void openMenu(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, ChatColor.DARK_RED + "Admin Menu: Abilities");
        int slot = 2;
        for (Kind k : Kind.values()) m.inv.setItem(slot++, item(k));
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(ChatColor.YELLOW + "How it works");
        im.setLore(List.of(ChatColor.GRAY + "Top row: click to get an ability item.", ChatColor.GRAY + "Right-clicking it gives that ability forever.",
                ChatColor.GRAY + "Heads below: players with an ability.", ChatColor.RED + "Click a head to REMOVE their ability."));
        info.setItemMeta(im);
        m.inv.setItem(8, info);
        // 1.3.38: row 2 = the legendary weapons + the admin-only bosses
        if (plugin.legendary != null) { int ls = 10; for (Legendary.Kind k : Legendary.Kind.values()) m.inv.setItem(ls++, plugin.legendary.item(k)); }
        if (plugin.toiletRaid != null) m.inv.setItem(14, plugin.toiletRaid.plunger(1));
        if (plugin.chromeRunner != null) { m.inv.setItem(15, plugin.chromeRunner.chip(1)); m.inv.setItem(16, plugin.chromeRunner.spine()); }
        int s = 18;
        List<Map.Entry<UUID, Kind>> list = new ArrayList<>(owned.entrySet());
        list.sort(Comparator.comparing(e -> names.getOrDefault(e.getKey(), "?").toLowerCase(Locale.ROOT)));
        for (Map.Entry<UUID, Kind> e : list) {
            if (s >= 54) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            try { sm.setOwningPlayer(op); } catch (RuntimeException ignored) { }
            sm.setDisplayName(ChatColor.WHITE + names.getOrDefault(e.getKey(), "?"));
            sm.setLore(List.of(e.getValue().color + e.getValue().title, ChatColor.RED + "Click to remove it."));
            head.setItemMeta(sm);
            m.inv.setItem(s, head);
            m.heads.add(e.getKey());
            s++;
        }
        p.openInventory(m.inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != m.inv || !p.hasPermission(ADMIN)) return;
        int slot = e.getSlot();
        if (slot >= 2 && slot < 2 + Kind.values().length) {
            Kind k = Kind.values()[slot - 2];
            for (ItemStack left : p.getInventory().addItem(item(k)).values()) p.getWorld().dropItem(p.getLocation(), left);
            p.sendMessage(ChatColor.GREEN + "Got the " + k.title + " ability item.");
            plugin.getLogger().info("[Abilities] " + p.getName() + " took a " + k.title + " item from /adminmenu");
        } else if (slot >= 10 && slot <= 16) {
            ItemStack it = m.inv.getItem(slot);
            if (it == null || it.getType().isAir()) return;
            for (ItemStack left : p.getInventory().addItem(it.clone()).values()) p.getWorld().dropItem(p.getLocation(), left);
            p.sendMessage(ChatColor.GREEN + "Got it.");
            plugin.getLogger().info("[Abilities] " + p.getName() + " took slot " + slot + " from /adminmenu");
        } else if (slot >= 18 && slot - 18 < m.heads.size()) {
            UUID id = m.heads.get(slot - 18);
            String n = names.getOrDefault(id, "?");
            Kind k = owned.get(id);
            if (remove(id)) p.sendMessage(ChatColor.YELLOW + "Removed " + (k == null ? "the ability" : k.title) + " from " + n + ".");
            openMenu(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission(ADMIN);
        if (cmd.getName().equalsIgnoreCase("adminmenu")) {
            if (!admin) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            openMenu(p);
            return true;
        }
        // /ability
        if (a.length == 0 || !admin) {
            if (!(s instanceof Player p)) { s.sendMessage("Usage: /ability list | remove <player> | give <player> <" + ids() + ">"); return true; }
            Kind k = owned.get(p.getUniqueId());
            if (k == null) p.sendMessage(ChatColor.GRAY + "You don't have an ability.");
            else p.sendMessage(k.color + "" + ChatColor.BOLD + k.title + ChatColor.GRAY + ": " + k.line1 + " " + k.line2);
            if (admin) s.sendMessage(ChatColor.DARK_GRAY + "Admin: /adminmenu, /ability list | remove <player> | give <player> <" + ids() + ">");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (owned.isEmpty()) { s.sendMessage(ChatColor.GRAY + "Nobody has an ability."); return true; }
                for (Map.Entry<UUID, Kind> e : owned.entrySet())
                    s.sendMessage(ChatColor.WHITE + names.getOrDefault(e.getKey(), "?") + ChatColor.GRAY + " - " + e.getValue().color + e.getValue().title);
            }
            case "remove", "take" -> {
                if (a.length < 2) { s.sendMessage("Usage: /ability remove <player>"); return true; }
                UUID id = find(a[1]);
                if (id == null || !remove(id)) s.sendMessage(ChatColor.RED + a[1] + " has no ability.");
                else s.sendMessage(ChatColor.YELLOW + "Removed " + a[1] + "'s ability.");
            }
            case "give", "set" -> {
                if (a.length < 3) { s.sendMessage("Usage: /ability give <player> <" + ids() + ">"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                Kind k = Kind.of(a[2]);
                if (t == null || k == null) { s.sendMessage(ChatColor.RED + "Player must be online, ability one of " + ids() + "."); return true; }
                grant(t.getUniqueId(), t.getName(), k);
                t.sendMessage(k.color + "" + ChatColor.BOLD + "You now have " + k.title + "!");
                s.sendMessage(ChatColor.GREEN + t.getName() + " now has " + k.title + ".");
            }
            case "item" -> {
                if (!(s instanceof Player p) || a.length < 2 || Kind.of(a[1]) == null) { s.sendMessage("Usage: /ability item <" + ids() + ">"); return true; }
                p.getInventory().addItem(item(Kind.of(a[1])));
            }
            default -> s.sendMessage("Usage: /ability list | remove <player> | give <player> <" + ids() + "> | item <id>");
        }
        return true;
    }

    private UUID find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && owned.containsKey(online.getUniqueId())) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private static String ids() { StringBuilder b = new StringBuilder(); for (Kind k : Kind.values()) b.append(b.length() == 0 ? "" : "|").append(k.id); return b.toString(); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("ability") || !s.hasPermission(ADMIN)) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("list", "remove", "give", "item"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("remove")) out.addAll(names.values());
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 2 && a[0].equalsIgnoreCase("item") || a.length == 3 && a[0].equalsIgnoreCase("give")) for (Kind k : Kind.values()) out.add(k.id);
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
