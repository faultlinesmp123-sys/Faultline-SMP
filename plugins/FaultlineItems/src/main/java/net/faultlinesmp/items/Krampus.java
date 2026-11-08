package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
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
 * KRAMPUS (Items 1.2.78, Christmas, config krampus:). A seasonal mini boss for the Winter window (start/end, default
 * 12-01..01-06, like FaultlineCosmetics' Winter season). He comes:
 *  - when someone rings KRAMPUS' BELL (BELL + PDC faultlineitems:krampus_bell, glint, never placed; craft: coal blocks,
 *    iron bars, a bell: CIC / IBI / CIC; only works in the window and in the Overworld unless you're an admin),
 *  - or at night in the window: every 5 minutes each survival player in the Overworld rolls natural-chance (0.3%).
 * Body: a wither skeleton (scale 1.7, tag faultline_krampus) in black fur (leather), with goat horns, a chain belt and
 * his sack (item displays following him; Bedrock sees the skeleton). 900 health +25% per extra fighter, boss bar.
 * Moves (one every 3-4 s): BIRCH SWITCH (a cone in front, 9 + slowness), CHAIN LASH (pulls the farthest fighter to him),
 * INTO THE SACK (grabs the nearest: blind, carried on his back taking 2 a second for 3 s, then thrown; hitting him for
 * sack-break 5% of his health frees them), COAL BARRAGE (lumps of coal at everyone: 5 + slowness), NAUGHTY LIST (the one
 * who hurt him most glows; he hunts them with Speed II for 8 s and hits them +50%), KRAMPUS' IMPS (3 small black imps,
 * tag faultline_krampus_imp), and below half: WINTER'S HOWL (freezes everyone within 9 and throws them back).
 * Rewards per fighter: reward-presents Presents (`cosmetic tokens <p> winter n`), a Mythic Bag, 2 Goodie Bags, Index stat
 * krampus; drops coal blocks and diamonds, 1200 XP. /holiday krampus [player], /holiday krampus kill.
 */
final class Krampus implements Listener {

    static final String TAG = "faultline_krampus", IMP_TAG = "faultline_krampus_imp", PART_TAG = "faultline_krampus_part";
    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey bellKey;

    final class Fight {
        WitherSkeleton body;
        final List<ItemDisplay> parts = new ArrayList<>();
        BossBar bar;
        int tick, nextMove = 60, sackTicks, howled;
        double sackHits;
        Player sacked, naughty;
        int naughtyUntil;
        final Map<UUID, Double> damage = new HashMap<>();
        final Set<UUID> fighters = new HashSet<>(), imps = new HashSet<>();
    }

    Fight fight;
    private long nextNatural;

    Krampus(FaultlineItems plugin) {
        this.plugin = plugin;
        bellKey = new NamespacedKey(plugin, "krampus_bell");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(IMP_TAG) || e.getScoreboardTags().contains(PART_TAG)) e.remove();
        });
        try {
            NamespacedKey rk = new NamespacedKey(plugin, "krampus_bell_recipe");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, bell(1));
                r.shape("CIC", "IBI", "CIC");
                r.setIngredient('C', Material.COAL_BLOCK);
                r.setIngredient('I', Material.IRON_BARS);
                r.setIngredient('B', Material.BELL);
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { plugin.getLogger().warning("Krampus' Bell recipe: " + e.getMessage()); }
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("krampus." + k, d); }

    boolean season() {
        return HauntedVillage.inWindow(today(), plugin.getConfig().getString("krampus.start", "12-01"), plugin.getConfig().getString("krampus.end", "01-06"));
    }

    java.time.LocalDate today() {
        try { return java.time.LocalDate.now(java.time.ZoneId.of(plugin.getConfig().getString("krampus.zone", "America/New_York"))); }
        catch (RuntimeException e) { return java.time.LocalDate.now(); }
    }

    ItemStack bell(int n) {
        ItemStack it = new ItemStack(Material.BELL, n);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Krampus' Bell", NamedTextColor.DARK_RED).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Ring it (right-click) between December 1", NamedTextColor.GRAY),
                Component.text("and January 6 and Krampus comes for you.", NamedTextColor.GRAY),
                Component.text("Bring friends. Be good.", NamedTextColor.DARK_GRAY)));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(bellKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isBell(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(bellKey, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRing(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isBell(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        boolean admin = p.hasPermission("items.holiday");
        if (!admin && !season()) { p.sendMessage(ChatColor.RED + "The bell is silent. Krampus only answers between December 1 and January 6."); return; }
        if (!admin && p.getWorld().getEnvironment() != World.Environment.NORMAL) { p.sendMessage(ChatColor.RED + "Krampus only walks the Overworld."); return; }
        if (fight != null) { p.sendMessage(ChatColor.RED + "Krampus is already out there."); return; }
        if (summon(p) == null) { p.sendMessage(ChatColor.RED + "No room for him here."); return; }
        if (p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isBell(e.getItemInHand())) e.setCancelled(true); }

    // ---------------------------------------------------------------- the fight

    Fight summon(Player near) {
        if (fight != null) return null;
        Location at = null;
        Vector look = near.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(1, 0, 0);
        look.normalize();
        for (int d = 7; d >= 3 && at == null; d--) {
            Location c = near.getLocation().add(look.clone().multiply(d));
            for (int dy = 3; dy >= -4; dy--) {
                Location l = c.clone().add(0, dy, 0);
                if (Emotes.open(l.getBlock()) && Emotes.open(l.clone().add(0, 1, 0).getBlock()) && Emotes.open(l.clone().add(0, 2, 0).getBlock())
                        && Emotes.open(l.clone().add(0, 3, 0).getBlock()) && l.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    at = l.getBlock().getLocation().add(0.5, 0, 0.5); break;
                }
            }
        }
        if (at == null) return null;
        Fight f = new Fight();
        WitherSkeleton w = (WitherSkeleton) at.getWorld().spawnEntity(at, EntityType.WITHER_SKELETON, false);
        w.addScoreboardTag(TAG);
        w.setPersistent(false);
        w.setRemoveWhenFarAway(false);
        w.customName(Component.text("Krampus", NamedTextColor.DARK_RED));
        w.setCustomNameVisible(true);
        int n = 0;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 40 * 40 && p.getGameMode() == GameMode.SURVIVAL) n++;
        double hp = cfg("health", 900) * (1 + cfg("per-fighter", 0.25) * Math.max(0, n - 1));
        MimicPlayer.attr(w, Attribute.MAX_HEALTH, hp);
        w.setHealth(hp);
        MimicPlayer.attr(w, Attribute.SCALE, cfg("scale", 1.7));
        MimicPlayer.attr(w, Attribute.MOVEMENT_SPEED, 0.3);
        MimicPlayer.attr(w, Attribute.ATTACK_DAMAGE, cfg("melee", 10));
        MimicPlayer.attr(w, Attribute.KNOCKBACK_RESISTANCE, 0.8);
        MimicPlayer.attr(w, Attribute.FOLLOW_RANGE, 48);
        w.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        var eq = w.getEquipment();
        eq.setChestplate(fur(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(fur(Material.LEATHER_LEGGINGS));
        eq.setBoots(fur(Material.LEATHER_BOOTS));
        eq.setItemInMainHand(new ItemStack(Material.STICK));
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0); } catch (RuntimeException ignored) { }
        f.body = w;
        part(f, Material.GOAT_HORN, 0.7f);
        part(f, Material.GOAT_HORN, 0.7f);
        part(f, chain(), 0.9f);
        part(f, Material.BUNDLE, 1.3f);
        f.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "Krampus", BarColor.RED, BarStyle.SEGMENTED_10);
        fight = f;
        Location boom = at;
        Emotes.q(() -> boom.getWorld().strikeLightningEffect(boom));
        at.getWorld().playSound(at, Sound.BLOCK_BELL_RESONATE, 3, 0.5f);
        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 3, 0.6f);
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 2, 0), 120, 1.5, 2, 1.5, 0.05);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "🔔 " + ChatColor.RED + "Krampus has come for " + ChatColor.WHITE + near.getName() + ChatColor.RED + "!");
        return f;
    }

    private static Material chain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        if (m == null) m = Material.matchMaterial("CHAIN");
        return m == null ? Material.IRON_BARS : m;
    }

    private static ItemStack fur(Material m) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) { lm.setColor(Color.fromRGB(0x1c1410)); it.setItemMeta(lm); }
        return it;
    }

    private void part(Fight f, Material m, float scale) {
        ItemDisplay d = (ItemDisplay) f.body.getWorld().spawnEntity(f.body.getLocation(), EntityType.ITEM_DISPLAY, false);
        d.addScoreboardTag(PART_TAG);
        d.setPersistent(false);
        d.setItemStack(new ItemStack(m));
        try { d.setTeleportDuration(2); } catch (RuntimeException ignored) { }
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        f.parts.add(d);
    }

    /** Horns on his head, the chain round his waist, the sack on his back. */
    private void dress(Fight f) {
        Location l = f.body.getLocation();
        double k = cfg("scale", 1.7);
        float yaw;
        try { yaw = f.body.getBodyYaw(); } catch (RuntimeException e) { yaw = l.getYaw(); }
        double r = Math.toRadians(yaw);
        Vector fw = new Vector(-Math.sin(r), 0, Math.cos(r)), side = new Vector(Math.cos(r), 0, Math.sin(r));
        Location head = l.clone().add(0, 2.35 * k, 0);
        place(f.parts.get(0), head.clone().add(side.clone().multiply(0.28 * k)).add(fw.clone().multiply(-0.05 * k)), yaw, -30, 25);
        place(f.parts.get(1), head.clone().add(side.clone().multiply(-0.28 * k)).add(fw.clone().multiply(-0.05 * k)), yaw, -30, -25);
        place(f.parts.get(2), l.clone().add(0, 1.25 * k, 0).add(fw.clone().multiply(0.12 * k)), yaw, 0, 90);
        place(f.parts.get(3), l.clone().add(0, 1.65 * k, 0).add(fw.clone().multiply(-0.42 * k)), yaw + 180, 0, 0);
    }

    private static void place(ItemDisplay d, Location at, float yaw, float pitch, float roll) {
        at.setYaw(yaw);
        at.setPitch(pitch);
        d.teleport(at);
        Transformation t = d.getTransformation();
        d.setTransformation(new Transformation(t.getTranslation(), new Quaternionf().rotateZ((float) Math.toRadians(roll)), t.getScale(), t.getRightRotation()));
    }

    private void tick() {
        try { tick0(); } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[Krampus] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    private void tick0() {
        Fight f = fight;
        if (f == null) { natural(); return; }
        if (!f.body.isValid() || f.body.isDead()) { if (!f.body.isDead()) leave(f, false); return; }
        f.tick++;
        dress(f);
        World w = f.body.getWorld();
        // who's fighting
        List<Player> near = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(f.body.getLocation()) < 48 * 48;
            if (in && !f.bar.getPlayers().contains(p)) f.bar.addPlayer(p);
            else if (!in && f.bar.getPlayers().contains(p)) f.bar.removePlayer(p);
            if (in && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()) { near.add(p); f.fighters.add(p.getUniqueId()); }
        }
        double max = f.body.getAttribute(Attribute.MAX_HEALTH).getValue();
        f.bar.setProgress(Math.max(0, Math.min(1, f.body.getHealth() / max)));
        if (near.isEmpty()) { if (f.tick > 20 * 60) leave(f, false); return; }
        if (f.tick % 5 == 0) w.spawnParticle(Particle.SNOWFLAKE, f.body.getLocation().add(0, 2, 0), 3, 0.6, 1, 0.6, 0.01);
        // the sack
        if (f.sacked != null) {
            Player s = f.sacked;
            if (!s.isOnline() || s.isDead() || s.getWorld() != w || f.sackTicks-- <= 0 || f.sackHits >= max * cfg("sack-break", 0.05)) releaseSack(f, f.sackTicks > 0);
            else {
                Location back = f.body.getLocation().add(f.body.getLocation().getDirection().setY(0).normalize().multiply(-1.2)).add(0, 1.6, 0);
                back.setYaw(s.getLocation().getYaw()); back.setPitch(s.getLocation().getPitch());
                s.teleport(back);
                if (f.sackTicks % 20 == 0) s.damage(2, f.body);
            }
        }
        // the naughty one
        Player tgt = f.naughty != null && f.naughtyUntil > f.tick && f.naughty.isOnline() && near.contains(f.naughty) ? f.naughty : nearest(near, f.body.getLocation());
        if (f.naughty != null && f.naughtyUntil <= f.tick) { f.naughty.setGlowing(false); f.naughty = null; }
        if (tgt != null && f.body.getTarget() != tgt) f.body.setTarget(tgt);
        if (f.tick < f.nextMove) return;
        f.nextMove = f.tick + 60 + random.nextInt(20);
        boolean low = f.body.getHealth() < max * 0.5;
        if (low && f.howled + 300 < f.tick) { f.howled = f.tick; howl(f, near); return; }
        switch (random.nextInt(6)) {
            case 0 -> switchCone(f, tgt);
            case 1 -> lash(f, near);
            case 2 -> { if (f.sacked == null) sack(f, nearest(near, f.body.getLocation())); else coal(f, near); }
            case 3 -> coal(f, near);
            case 4 -> naughty(f, near);
            default -> imps(f, near);
        }
    }

    private static Player nearest(List<Player> ps, Location at) {
        Player best = null; double bd = Double.MAX_VALUE;
        for (Player p : ps) { double d = p.getLocation().distanceSquared(at); if (d < bd) { bd = d; best = p; } }
        return best;
    }

    private void say(Fight f, String line) {
        for (Player p : f.bar.getPlayers()) p.sendMessage(ChatColor.DARK_RED + "Krampus: " + ChatColor.RED + line);
    }

    private void switchCone(Fight f, Player t) {
        if (t == null) return;
        Location at = f.body.getLocation();
        Vector dir = t.getLocation().toVector().subtract(at.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) return;
        dir.normalize();
        f.body.swingMainHand();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2, 0.6f);
        for (int i = 1; i <= 5; i++) at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(dir.clone().multiply(i)).add(0, 1.2, 0), 1);
        for (Entity e : at.getWorld().getNearbyEntities(at, 5.5, 3, 5.5)) {
            if (!(e instanceof Player p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            Vector to = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (to.lengthSquared() > 0.01 && to.normalize().dot(dir) < 0.45) continue;
            p.damage(cfg("moves.switch", 9), f.body);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        }
    }

    private void lash(Fight f, List<Player> near) {
        Player far = null; double fd = 0;
        for (Player p : near) { double d = p.getLocation().distanceSquared(f.body.getLocation()); if (d > fd) { fd = d; far = p; } }
        if (far == null || fd < 16) { coal(f, near); return; }
        Location a = f.body.getLocation().add(0, 1.5, 0), b = far.getLocation().add(0, 1, 0);
        Vector step = b.toVector().subtract(a.toVector());
        double len = step.length();
        step.normalize().multiply(0.5);
        Location c = a.clone();
        for (double d = 0; d < len; d += 0.5) { c.add(step); c.getWorld().spawnParticle(Particle.CRIT, c, 1, 0, 0, 0, 0); }
        a.getWorld().playSound(a, Sound.BLOCK_CHAIN_PLACE, 2, 0.5f);
        Vector pull = a.toVector().subtract(b.toVector()).multiply(0.16).setY(0.5);
        Safe.vel(far, pull);
        far.damage(cfg("moves.lash", 4), f.body);
        say(f, random.nextBoolean() ? "Come here, little one." : "No running.");
    }

    private void sack(Fight f, Player p) {
        if (p == null || p.getLocation().distanceSquared(f.body.getLocation()) > 6 * 6) return;
        f.sacked = p;
        f.sackTicks = 60;
        f.sackHits = 0;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, false));
        p.sendTitle(ChatColor.DARK_RED + "Into the sack!", ChatColor.GRAY + "Your friends can hit him to free you", 0, 40, 10);
        f.body.getWorld().playSound(f.body.getLocation(), Sound.ITEM_BUNDLE_INSERT, 2, 0.5f);
        say(f, "Into the sack with you!");
    }

    private void releaseSack(Fight f, boolean freed) {
        Player s = f.sacked;
        f.sacked = null;
        if (s == null || !s.isOnline()) return;
        s.removePotionEffect(PotionEffectType.BLINDNESS);
        Vector away = f.body.getLocation().getDirection().setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        Safe.vel(s, away.normalize().multiply(freed ? 0.6 : 1.4).setY(0.6));
        s.sendActionBar(freed ? ChatColor.GREEN + "Freed!" : ChatColor.RED + "Thrown!");
    }

    private void coal(Fight f, List<Player> near) {
        Location eye = f.body.getEyeLocation();
        for (Player p : near) {
            for (int i = 0; i < 3; i++) {
                int delay = i * 5;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (fight != f || !p.isOnline() || !f.body.isValid()) return;
                    Vector v = p.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
                    double d = v.length();
                    v.normalize().multiply(1.3).setY(v.getY() * 1.3 + d * 0.012);
                    Snowball s = f.body.getWorld().spawn(eye, Snowball.class, sb -> { sb.setItem(new ItemStack(Material.COAL)); sb.addScoreboardTag(TAG + "_coal"); sb.setShooter(f.body); });
                    Safe.vel(s, v);
                }, delay);
            }
        }
        f.body.getWorld().playSound(eye, Sound.ENTITY_SNOW_GOLEM_SHOOT, 2, 0.5f);
        say(f, random.nextBoolean() ? "Coal for the naughty!" : "You've all been very bad.");
    }

    @EventHandler
    public void onCoal(ProjectileHitEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG + "_coal")) return;
        if (e.getHitEntity() instanceof Player p && fight != null) {
            p.damage(cfg("moves.coal", 5), fight.body);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
        }
        e.getEntity().getWorld().spawnParticle(Particle.SMOKE, e.getEntity().getLocation(), 6, 0.1, 0.1, 0.1, 0.02);
    }

    private void naughty(Fight f, List<Player> near) {
        Player worst = null; double wd = -1;
        for (Player p : near) { double d = f.damage.getOrDefault(p.getUniqueId(), 0.0); if (d > wd) { wd = d; worst = p; } }
        if (worst == null) return;
        if (f.naughty != null) f.naughty.setGlowing(false);
        f.naughty = worst;
        f.naughtyUntil = f.tick + 160;
        worst.setGlowing(true);
        f.body.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 160, 1, false, false));
        for (Player p : f.bar.getPlayers()) p.sendMessage(ChatColor.DARK_RED + "📜 " + ChatColor.RED + worst.getName() + " is on the Naughty List!");
        worst.playSound(worst.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1, 0.7f);
    }

    private void imps(Fight f, List<Player> near) {
        f.imps.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
        if (f.imps.size() >= 6) { coal(f, near); return; }
        for (int i = 0; i < 3; i++) {
            Location at = f.body.getLocation().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2);
            if (!Emotes.open(at.getBlock())) at = f.body.getLocation();
            Zombie z = (Zombie) at.getWorld().spawnEntity(at, EntityType.ZOMBIE, false);
            try { z.setAdult(); } catch (RuntimeException ignored) { }
            Emotes.q(() -> z.setShouldBurnInDay(false));
            z.customName(Component.text("Krampus' Imp", NamedTextColor.DARK_GRAY));
            z.getEquipment().setHelmet(fur(Material.LEATHER_HELMET));
            z.getEquipment().setChestplate(fur(Material.LEATHER_CHESTPLATE));
            z.getEquipment().setLeggings(fur(Material.LEATHER_LEGGINGS));
            z.getEquipment().setBoots(fur(Material.LEATHER_BOOTS));
            for (EquipmentSlot s : EquipmentSlot.values()) try { z.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
            MimicPlayer.attr(z, Attribute.SCALE, 0.6);
            MimicPlayer.attr(z, Attribute.MAX_HEALTH, 16);
            z.setHealth(16);
            MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, 0.34);
            MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
            z.addScoreboardTag(IMP_TAG);
            z.setPersistent(false);
            Player t = nearest(near, at);
            if (t != null) z.setTarget(t);
            f.imps.add(z.getUniqueId());
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.5, 0), 10, 0.2, 0.3, 0.2, 0.02);
        }
        say(f, "My little helpers!");
    }

    private void howl(Fight f, List<Player> near) {
        Location at = f.body.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_POLAR_BEAR_WARNING, 3, 0.5f);
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 1.5, 0), 300, 4, 1.5, 4, 0.2);
        for (Player p : near) {
            if (p.getLocation().distanceSquared(at) > 81) continue;
            p.setFreezeTicks(Math.max(p.getFreezeTicks(), 260));
            p.damage(cfg("moves.howl", 6), f.body);
            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            Safe.vel(p, away.normalize().multiply(1.3).setY(0.5));
        }
        say(f, "WINTER TAKES YOU!");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        Player by = TotemLimit.playerBehind(e.getDamager());
        if (by == null) return;
        f.damage.merge(by.getUniqueId(), e.getFinalDamage(), Double::sum);
        if (f.sacked != null && !by.equals(f.sacked)) f.sackHits += e.getFinalDamage();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHits(EntityDamageByEntityEvent e) {
        Fight f = fight;
        if (f == null || e.getDamager() != f.body || f.naughty == null || e.getEntity() != f.naughty) return;
        e.setDamage(e.getDamage() * 1.5);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(IMP_TAG)) { e.getDrops().clear(); e.setDroppedExp(3); return; }
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.COAL_BLOCK, 4 + random.nextInt(5)));
        e.getDrops().add(new ItemStack(Material.DIAMOND, 3 + random.nextInt(4)));
        e.setDroppedExp(1200);
        int presents = (int) cfg("reward-presents", 12);
        for (UUID id : f.fighters) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (Bukkit.getPluginManager().getPlugin("FaultlineCosmetics") != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cosmetic tokens " + p.getName() + " winter " + presents);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givemythicbag 1 " + p.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givegoodiebag 2 " + p.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " krampus 1");
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "🎄 Krampus is beaten! " + ChatColor.GRAY + f.fighters.size() + " fighter" + (f.fighters.size() == 1 ? "" : "s") + " got " + presents + " Presents.");
        leave(f, true);
    }

    void leave(Fight f, boolean died) {
        if (fight != f) return;
        fight = null;
        releaseSack(f, true);
        if (f.naughty != null) f.naughty.setGlowing(false);
        f.bar.removeAll();
        for (ItemDisplay d : f.parts) if (d.isValid()) d.remove();
        for (UUID id : f.imps) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        if (!died && f.body.isValid()) {
            f.body.getWorld().spawnParticle(Particle.SNOWFLAKE, f.body.getLocation().add(0, 2, 0), 150, 1, 2, 1, 0.1);
            f.body.remove();
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "🔔 Krampus walks off into the snow.");
        }
    }

    private void natural() {
        long now = System.currentTimeMillis();
        if (now < nextNatural) return; // 1.2.95: the clock first (the date / config were read every tick)
        nextNatural = now + 5 * 60_000;
        if (!plugin.getConfig().getBoolean("krampus.enabled", true) || !season()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL || p.getWorld().getEnvironment() != World.Environment.NORMAL || !FaultlineItems.surfaceWorld(p.getWorld())) continue;
            if (!HauntedVillage.night(p.getWorld()) || random.nextDouble() >= cfg("natural-chance", 0.003)) continue;
            if (summon(p) != null) return;
        }
    }

    void shutdown() { Fight f = fight; if (f != null) leave(f, false); }

    String status() {
        Fight f = fight;
        return f == null ? "Krampus isn't out" + (season() ? " (Winter: on)" : " (not Christmas time)")
                : "Krampus at " + Where.spot(f.body.getLocation()) + ", " + Math.round(f.body.getHealth()) + " hp, " + f.fighters.size() + " fighters";
    }
}
