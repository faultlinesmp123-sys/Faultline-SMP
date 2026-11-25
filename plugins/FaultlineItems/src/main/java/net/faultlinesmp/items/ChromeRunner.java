package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
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
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * THE CHROME RUNNER (Items 1.3.38, config chrome-runner:). An ADMIN-ONLY boss (items.adminbosses: /chromerunner summon, or
 * the Overclocked Chip): Kade Voss, a chromed-up street merc in a yellow jacket (an invisible zombie wearing the armour +
 * a player head, `head-skin` picks whose). 7000 health (+35% per extra fighter), takes 20% less. Very hard.
 * Moves: OVERCLOCK (time slows for everyone round him while he blinks behind each of you and cuts), GORILLA PUNCH (huge
 * knockback, breaks shields), MONOWIRE (a whip line), SMART BARRAGE (homing shots), MISSILES (phase 2), and below 30%
 * CYBERPSYCHOSIS (faster, harder, overclocks all the time). Rewards are huge, and the CHRONO SPINE implant: the top
 * damager always gets one, everyone else `spine-chance` 25%. Chrono Spine: right-click = 6 s where you move very fast and
 * everything round you crawls (Slowness III), 90 s cooldown.
 */
final class ChromeRunner implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_chrome_runner", SHOT = "faultline_chrome_shot", PERM = ToiletRaid.PERM;
    private static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(0x3CF2E6), 1.4f),
            PINK = new Particle.DustOptions(Color.fromRGB(0xFF3FA4), 1.4f), RED = new Particle.DustOptions(Color.fromRGB(0xFF2020), 1.8f);

    final class Fight {
        Zombie body; BossBar bar; int tick, nextMove = 60, move, moveT, phase = 1, dashes; Player target; Vector wire; Location wireFrom;
        final Map<UUID, Double> damage = new HashMap<>(); final Set<UUID> fighters = new HashSet<>();
        final Map<UUID, UUID> shots = new HashMap<>(); // homing shot -> target
    }

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey chipKey, spineKey;
    Fight fight;
    private final Map<UUID, Long> spineReady = new HashMap<>();
    private final Map<UUID, Long> spineOn = new HashMap<>();
    private long tick;

    ChromeRunner(FaultlineItems plugin) {
        this.plugin = plugin;
        chipKey = new NamespacedKey(plugin, "overclocked_chip");
        spineKey = new NamespacedKey(plugin, "chrono_spine");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(SHOT)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("chrome-runner." + k, d); }

    // ================================================================= items

    ItemStack chip(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, n);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Overclocked Chip", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("ADMINS ONLY. Right-click: the Chrome Runner", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("comes for whoever's near you.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Very, very hard. Huge rewards.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(chipKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack spine() {
        ItemStack it = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Chrono Spine", TextColor.color(0x3CF2E6)).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        int secs = (int) cfg("spine.seconds", 6), cd = (int) cfg("spine.cooldown-seconds", 90);
        m.lore(List.of(Component.text("A spinal implant torn from the Chrome Runner.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: for " + secs + "s you move like lightning", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("and everything round you crawls.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("Cooldown " + cd + "s", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setMaxStackSize(1);
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(spineKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean has(ItemStack it, NamespacedKey k) { return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(k, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (has(e.getItem(), chipKey)) {
            e.setCancelled(true);
            if (!p.hasPermission(PERM)) { p.sendMessage(ChatColor.RED + "Only admins can call the Chrome Runner."); return; }
            if (fight != null) { p.sendMessage(ChatColor.RED + "The Chrome Runner is already out."); return; }
            if (summon(p) != null && p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
        } else if (has(e.getItem(), spineKey)) {
            e.setCancelled(true);
            overclockSelf(p);
        }
    }

    private void overclockSelf(Player p) {
        Long ready = spineReady.get(p.getUniqueId());
        if (ready != null && ready > tick) { p.sendActionBar(ChatColor.RED + "Chrono Spine: " + ((ready - tick) / 20 + 1) + "s"); return; }
        int ticks = (int) (cfg("spine.seconds", 6) * 20);
        spineReady.put(p.getUniqueId(), tick + (long) (cfg("spine.cooldown-seconds", 90) * 20));
        spineOn.put(p.getUniqueId(), tick + ticks);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 3, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, ticks, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 0, false, false, true));
        p.setFoodLevel(Math.max(0, p.getFoodLevel() - 3));
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 2f);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 1, 1.6f);
        double r = cfg("spine.radius", 16);
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            if (e instanceof Player o && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 2, false, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 0, false, true));
        }
    }

    // ================================================================= the fight

    Fight summon(Player near) {
        if (fight != null) return null;
        Location at = null;
        Vector look = near.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(1, 0, 0);
        look.normalize();
        for (int d = 8; d >= 3 && at == null; d--) {
            Location c = near.getLocation().add(look.clone().multiply(d));
            for (int dy = 3; dy >= -4; dy--) {
                Location l = c.clone().add(0, dy, 0);
                if (Emotes.open(l.getBlock()) && Emotes.open(l.clone().add(0, 1, 0).getBlock()) && l.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    at = l.getBlock().getLocation().add(0.5, 0, 0.5); break;
                }
            }
        }
        if (at == null) at = near.getLocation();
        Fight f = new Fight();
        Zombie z = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, false);
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        Emotes.q(() -> z.setShouldBurnInDay(false));
        Emotes.q(() -> z.setCanPickupItems(false));
        z.setPersistent(false);
        Emotes.q(() -> z.setRemoveWhenFarAway(false));
        Emotes.q(() -> z.setSilent(true));
        z.addScoreboardTag(TAG);
        z.customName(Component.text("Kade Voss, the Chrome Runner", NamedTextColor.YELLOW));
        z.setCustomNameVisible(true);
        int n = 0;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 48 * 48 && p.getGameMode() == GameMode.SURVIVAL) n++;
        double hp = cfg("health", 7000) * (1 + cfg("per-fighter", 0.35) * Math.max(0, n - 1));
        BigHealth.set(z, hp);
        MimicPlayer.attr(z, Attribute.ATTACK_DAMAGE, cfg("melee", 15));
        MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, 0.36);
        MimicPlayer.attr(z, Attribute.ARMOR, 14);
        MimicPlayer.attr(z, Attribute.ARMOR_TOUGHNESS, 8);
        MimicPlayer.attr(z, Attribute.KNOCKBACK_RESISTANCE, 0.85);
        MimicPlayer.attr(z, Attribute.FOLLOW_RANGE, 64);
        MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        MimicPlayer.attr(z, Attribute.STEP_HEIGHT, 1.2);
        z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        var eq = z.getEquipment();
        eq.clear();
        eq.setHelmet(head());
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, 0xF2C12E));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, 0x1A1A22));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, 0x2B2B33));
        eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0); } catch (RuntimeException ignored) { }
        f.body = z;
        f.bar = Bukkit.createBossBar(ChatColor.YELLOW + "Kade Voss, the Chrome Runner", BarColor.YELLOW, BarStyle.SEGMENTED_20);
        fight = f;
        World w = at.getWorld();
        w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 3, 1.4f);
        w.playSound(at, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2, 1.8f);
        w.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 120, 1, 1.2, 1, 0, CYAN);
        w.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 80, 1, 1.2, 1, 0, PINK);
        Bukkit.broadcastMessage(ChatColor.YELLOW + "⚡ " + ChatColor.GOLD + "Kade Voss, the Chrome Runner, " + ChatColor.YELLOW + "is hunting near " + near.getName() + ".");
        return f;
    }

    private ItemStack head() {
        ItemStack h = new ItemStack(Material.PLAYER_HEAD);
        try {
            String name = plugin.getConfig().getString("chrome-runner.head-skin", "");
            OfflinePlayer who = null;
            if (name != null && !name.isBlank()) who = Bukkit.getOfflinePlayer(name);
            else { List<? extends Player> on = new ArrayList<>(Bukkit.getOnlinePlayers()); if (!on.isEmpty()) who = on.get(random.nextInt(on.size())); }
            if (who != null && h.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(who); h.setItemMeta(sm); }
        } catch (RuntimeException ignored) { }
        return h;
    }

    private static ItemStack dyed(Material m, int rgb) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(Color.fromRGB(rgb)); it.setItemMeta(lm); }
        return it;
    }

    private List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(c) < r * r && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()) out.add(p);
        return out;
    }

    private void fightTick(Fight f) {
        Zombie z = f.body;
        World w = z.getWorld();
        f.tick++;
        double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
        f.bar.setProgress(Math.max(0, Math.min(1, z.getHealth() / max)));
        for (Player p : w.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(z.getLocation()) < 80 * 80;
            if (in && !f.bar.getPlayers().contains(p)) f.bar.addPlayer(p); else if (!in && f.bar.getPlayers().contains(p)) f.bar.removePlayer(p);
        }
        List<Player> ps = near(z.getLocation(), 64);
        for (Player p : ps) f.fighters.add(p.getUniqueId());
        if (ps.isEmpty()) { if (f.tick > 20 * 120) leave(f, false); return; }
        int phase = z.getHealth() < max * 0.3 ? 3 : z.getHealth() < max * 0.65 ? 2 : 1;
        if (phase != f.phase) {
            f.phase = phase;
            if (phase == 3) {
                z.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
                z.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 1, false, false));
                for (Player p : ps) p.sendTitle(ChatColor.RED + "CYBERPSYCHOSIS", ChatColor.GRAY + "He's lost it.", 5, 50, 15);
                w.playSound(z.getLocation(), Sound.ENTITY_WARDEN_ROAR, 3, 1.4f);
            } else for (Player p : ps) p.sendTitle(ChatColor.YELLOW + "OVERDRIVE", "", 5, 30, 10);
        }
        if (f.tick % 3 == 0) w.spawnParticle(Particle.DUST, z.getLocation().add(0, 1, 0), 2, 0.2, 0.5, 0.2, 0, phase == 3 ? RED : CYAN);
        if (f.target == null || !f.target.isValid() || f.target.isDead() || !ps.contains(f.target) || f.tick % 80 == 0) f.target = ps.get(random.nextInt(ps.size()));
        if (z.getTarget() != f.target) z.setTarget(f.target);
        // never left behind: blinks to a fighter who got far
        if (f.move == 0 && f.tick % 40 == 0 && f.target.getLocation().distanceSquared(z.getLocation()) > 22 * 22) blinkBehind(z, f.target);
        steerShots(f);
        if (f.move != 0) { moveTick(f, ps); return; }
        if (f.tick < f.nextMove) return;
        int[] pool = phase == 1 ? new int[]{1, 2, 3, 4} : phase == 2 ? new int[]{1, 2, 3, 4, 5} : new int[]{1, 1, 2, 3, 4, 5};
        f.move = pool[random.nextInt(pool.length)];
        f.moveT = 0;
        moveTick(f, ps);
    }

    private void endMove(Fight f) {
        f.move = 0;
        f.nextMove = f.tick + (f.phase == 1 ? 55 : f.phase == 2 ? 40 : 26) + random.nextInt(15);
    }

    private void blinkBehind(Zombie z, Player p) {
        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(1, 0, 0);
        Location to = p.getLocation().subtract(back.normalize().multiply(1.6));
        if (!Emotes.open(to.getBlock()) || !Emotes.open(to.clone().add(0, 1, 0).getBlock())) to = p.getLocation();
        to.setDirection(p.getLocation().toVector().subtract(to.toVector()).setY(0).lengthSquared() < 1e-4 ? back : p.getLocation().toVector().subtract(to.toVector()));
        Location from = z.getLocation();
        trail(from, to);
        z.teleport(to);
        z.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.8f);
    }

    private void trail(Location a, Location b) {
        if (a.getWorld() != b.getWorld()) return;
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.normalize();
        for (double t = 0; t < len; t += 0.5) {
            Location l = a.clone().add(d.clone().multiply(t)).add(0, 1, 0);
            a.getWorld().spawnParticle(Particle.DUST, l, 2, 0.15, 0.5, 0.15, 0, (int) (t * 2) % 2 == 0 ? CYAN : PINK);
        }
    }

    private void moveTick(Fight f, List<Player> ps) {
        Zombie z = f.body;
        World w = z.getWorld();
        Location at = z.getLocation();
        int t = f.moveT++;
        switch (f.move) {
            case 1 -> { // OVERCLOCK: time slows for everyone round him; he blinks behind each of you and cuts
                if (t == 0) {
                    f.dashes = 0;
                    w.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 3, 2f);
                    w.playSound(at, Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 3, 1.4f);
                    for (Player p : ps) {
                        if (p.getLocation().distanceSquared(at) > 30 * 30) continue;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 3, false, false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 70, 1, false, false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, false));
                        p.sendActionBar(ChatColor.AQUA + "Time slows...");
                    }
                }
                if (t > 6 && t % 8 == 0 && f.dashes < (f.phase == 3 ? 7 : 5)) {
                    Player p = ps.get(f.dashes % ps.size());
                    blinkBehind(z, p);
                    z.swingMainHand();
                    p.damage(cfg("moves.overclock", 7), z);
                    w.spawnParticle(Particle.SWEEP_ATTACK, p.getLocation().add(0, 1, 0), 2, 0.3, 0.3, 0.3);
                    f.dashes++;
                }
                if (t > 64) endMove(f);
            }
            case 2 -> { // GORILLA PUNCH
                Player p = f.target;
                if (t == 0) { w.playSound(at, Sound.BLOCK_PISTON_EXTEND, 2, 0.6f); }
                if (t < 10) { w.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1.2, 0), 6, 0.4, 0.4, 0.4, 0.1); break; }
                if (t == 10) {
                    if (p.getLocation().distanceSquared(at) > 4 * 4 && p.getLocation().distanceSquared(at) < 14 * 14) blinkBehind(z, p);
                    if (p.getLocation().distanceSquared(z.getLocation()) < 4.5 * 4.5) {
                        z.swingMainHand();
                        if (p.isBlocking()) { p.setCooldown(Material.SHIELD, 120); w.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.5f, 0.8f); }
                        p.damage(cfg("moves.punch", 16), z);
                        Vector away = p.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
                        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                        Safe.vel(p, away.normalize().multiply(2.6).setY(0.8));
                        w.playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 2, 0.6f);
                        w.spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 2);
                    }
                    endMove(f);
                }
            }
            case 3 -> { // MONOWIRE: a glowing line, then it cuts
                if (t == 0) {
                    f.wireFrom = at.clone().add(0, 1.2, 0);
                    f.wire = f.target.getLocation().add(0, 1, 0).toVector().subtract(f.wireFrom.toVector());
                    if (f.wire.lengthSquared() < 1e-4) f.wire = new Vector(1, 0, 0);
                    f.wire.normalize();
                    w.playSound(at, Sound.BLOCK_TRIPWIRE_ATTACH, 2, 0.6f);
                }
                double len = cfg("moves.wire-length", 14);
                boolean cut = t == 14;
                if (t % 2 == 0 || cut) for (double d = 0.5; d < len; d += 0.5)
                    w.spawnParticle(Particle.DUST, f.wireFrom.clone().add(f.wire.clone().multiply(d)), 1, 0, 0, 0, 0, cut ? RED : PINK);
                if (cut) {
                    z.swingMainHand();
                    w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2, 1.6f);
                    for (Player p : ps) {
                        Vector rel = p.getLocation().add(0, 1, 0).toVector().subtract(f.wireFrom.toVector());
                        double along = rel.dot(f.wire);
                        if (along < 0 || along > len) continue;
                        if (rel.clone().subtract(f.wire.clone().multiply(along)).length() < 1.3) {
                            p.damage(cfg("moves.wire", 12), z);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                        }
                    }
                    endMove(f);
                }
            }
            case 4 -> { // SMART BARRAGE: homing shots
                if (t % 4 == 0 && t < 32) {
                    Player p = ps.get(random.nextInt(ps.size()));
                    Location eye = z.getEyeLocation();
                    Snowball s = w.spawn(eye, Snowball.class, sb -> { sb.setItem(new ItemStack(Material.IRON_NUGGET)); sb.addScoreboardTag(SHOT); sb.setShooter(z); sb.setGravity(false); });
                    Vector v = p.getEyeLocation().toVector().subtract(eye.toVector());
                    if (v.lengthSquared() < 1e-4) v = new Vector(0, 1, 0);
                    Safe.vel(s, v.normalize().multiply(0.9).add(new Vector(random.nextGaussian() * 0.3, 0.3, random.nextGaussian() * 0.3)));
                    f.shots.put(s.getUniqueId(), p.getUniqueId());
                    w.playSound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 1.8f);
                }
                if (t > 36) endMove(f);
            }
            case 5 -> { // MISSILES
                if (t % 6 == 0 && t < 30) for (Player p : ps) {
                    if (random.nextInt(3) != 0) continue;
                    Location eye = z.getEyeLocation().add(0, 0.6, 0);
                    Snowball s = w.spawn(eye, Snowball.class, sb -> { sb.setItem(new ItemStack(Material.FIRE_CHARGE)); sb.addScoreboardTag(SHOT); sb.addScoreboardTag(SHOT + "_missile"); sb.setShooter(z); sb.setGravity(false); });
                    Safe.vel(s, new Vector(random.nextGaussian() * 0.2, 0.8, random.nextGaussian() * 0.2));
                    f.shots.put(s.getUniqueId(), p.getUniqueId());
                    w.playSound(eye, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.7f);
                }
                if (t > 34) endMove(f);
            }
            default -> endMove(f);
        }
    }

    private void steerShots(Fight f) {
        for (var it = f.shots.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Entity s = Bukkit.getEntity(en.getKey());
            Player p = Bukkit.getPlayer(en.getValue());
            if (s == null || !s.isValid() || s.getTicksLived() > 100) { if (s != null) s.remove(); it.remove(); continue; }
            boolean missile = s.getScoreboardTags().contains(SHOT + "_missile");
            if (s.getTicksLived() % 2 == 0) s.getWorld().spawnParticle(missile ? Particle.SMOKE : Particle.ELECTRIC_SPARK, s.getLocation(), 1, 0, 0, 0, 0);
            if (p == null || p.getWorld() != s.getWorld() || p.isDead()) continue;
            if (missile && s.getTicksLived() < 12) continue;
            Vector want = p.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(s.getLocation().toVector());
            if (want.lengthSquared() < 1e-4) continue;
            double speed = missile ? 0.9 : 1.1;
            Vector v = s.getVelocity().multiply(0.75).add(want.normalize().multiply(speed * 0.25));
            if (v.lengthSquared() > 1e-6) Safe.vel(s, v.normalize().multiply(speed));
        }
    }

    @EventHandler
    public void onShot(ProjectileHitEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(SHOT)) return;
        Fight f = fight;
        Entity by = f != null ? f.body : null;
        Location at = e.getEntity().getLocation();
        if (e.getEntity().getScoreboardTags().contains(SHOT + "_missile")) {
            at.getWorld().spawnParticle(Particle.EXPLOSION, at, 2);
            at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.3f);
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().distance(at);
                if (d < 3.5) { p.damage(cfg("moves.missile", 9) * (1 - d / 4.5), by); p.setFireTicks(60); }
            }
        } else if (e.getHitEntity() instanceof Player p && p.getGameMode() != GameMode.CREATIVE) {
            p.damage(cfg("moves.shot", 5), by);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || e.getCause() == EntityDamageEvent.DamageCause.DROWNING) { e.setCancelled(true); return; }
        e.setDamage(e.getDamage() * (1 - cfg("damage-reduction", 0.2)));
        if (e instanceof EntityDamageByEntityEvent be) {
            Player by = TotemLimit.playerBehind(be.getDamager());
            if (by != null) f.damage.merge(by.getUniqueId(), e.getFinalDamage(), Double::sum);
            // he dodges: 15% of hits are side-stepped in a blur
            if (by != null && random.nextDouble() < cfg("dodge-chance", 0.15)) {
                e.setCancelled(true);
                Vector side = by.getLocation().getDirection().setY(0).crossProduct(new Vector(0, 1, 0));
                if (side.lengthSquared() > 1e-4) {
                    Location to = f.body.getLocation().add(side.normalize().multiply(random.nextBoolean() ? 2.5 : -2.5));
                    if (Emotes.open(to.getBlock()) && Emotes.open(to.clone().add(0, 1, 0).getBlock())) { trail(f.body.getLocation(), to); f.body.teleport(to); }
                }
                by.sendActionBar(ChatColor.AQUA + "Dodged.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true); }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.NETHERITE_INGOT, 2));
        e.getDrops().add(new ItemStack(Material.DIAMOND_BLOCK, 4));
        e.setDroppedExp((int) cfg("xp", 7000));
        UUID top = f.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        int given = 0;
        for (UUID id : f.fighters) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            ToiletRaid.rewards(plugin, p, "chrome-runner.rewards", DEFAULT_REWARDS, random);
            if (id.equals(top) || random.nextDouble() < cfg("spine-chance", 0.25)) {
                for (ItemStack left : p.getInventory().addItem(spine()).values()) p.getWorld().dropItem(p.getLocation(), left);
                Bukkit.broadcastMessage(ChatColor.AQUA + "⚡ " + p.getName() + " tore out the Chrono Spine!");
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " chrome_runner 1");
            given++;
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "⚡ The Chrome Runner is down! " + ChatColor.YELLOW + given + " fighter" + (given == 1 ? "" : "s") + " share the spoils.");
        leave(f, true);
    }

    static final List<String> DEFAULT_REWARDS = List.of(
            "givemythicbag 3 {player}", "givegoodiebag 6 {player}", "darkloot bag 2 {player}", "fenchant give {enchant} 2 1 {player}",
            "item NETHERITE_INGOT 3", "item DIAMOND_BLOCK 4", "item ENCHANTED_GOLDEN_APPLE 2", "item TOTEM_OF_UNDYING 1", "item EXPERIENCE_BOTTLE 48");

    void leave(Fight f, boolean died) {
        if (fight != f) return;
        fight = null;
        f.bar.removeAll();
        for (UUID id : f.shots.keySet()) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        if (!died && f.body.isValid()) {
            f.body.getWorld().spawnParticle(Particle.DUST, f.body.getLocation().add(0, 1, 0), 80, 0.6, 1, 0.6, 0, CYAN);
            f.body.remove();
            Bukkit.broadcastMessage(ChatColor.YELLOW + "⚡ The Chrome Runner vanishes into the night.");
        }
    }

    private void tick() {
        tick++;
        try {
            Fight f = fight;
            if (f != null) {
                if (!f.body.isValid() || f.body.isDead()) { if (!f.body.isDead()) leave(f, false); }
                else fightTick(f);
            }
            if (!spineOn.isEmpty()) for (var it = spineOn.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                Player p = Bukkit.getPlayer(en.getKey());
                if (p == null || en.getValue() < tick) { it.remove(); continue; }
                if (tick % 2 == 0) p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 3, 0.2, 0.5, 0.2, 0, tick % 4 == 0 ? CYAN : PINK);
            }
            if (tick % 1200 == 0) spineReady.values().removeIf(t -> t < tick);
        } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[ChromeRunner] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    void shutdown() { Fight f = fight; if (f != null) leave(f, false); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "summon" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (t == null) { s.sendMessage(ChatColor.RED + "/chromerunner summon [player]"); return true; }
                s.sendMessage(summon(t) != null ? ChatColor.GREEN + "The Chrome Runner is out." : ChatColor.RED + "He's already out.");
            }
            case "kill" -> { Fight f = fight; if (f != null) leave(f, false); s.sendMessage(ChatColor.YELLOW + "Gone."); }
            case "chip" -> { if (p != null) p.getInventory().addItem(chip(1)); }
            case "spine" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (t != null) { t.getInventory().addItem(spine()); s.sendMessage(ChatColor.GREEN + "Gave a Chrono Spine to " + t.getName() + "."); }
            }
            default -> {
                Fight f = fight;
                s.sendMessage(ChatColor.YELLOW + (f == null ? "The Chrome Runner isn't out." : "Chrome Runner at " + Where.spot(f.body.getLocation()) + ", " + Math.round(f.body.getHealth()) + " hp")
                        + ChatColor.GRAY + "  /chromerunner summon [player] | kill | chip | spine [player]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("summon", "kill", "chip", "spine", "status"), a[0]);
        if (a.length == 2) { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return Legendary.filter(l, a[1]); }
        return List.of();
    }
}
