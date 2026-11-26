package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 2.0 WILD MOBS (config {@code wild-mobs:}): biome variants and wolf packs.
 * <ul>
 * <li>JUNGLE STALKER: natural skeletons in jungles (`jungle.chance` 30%): green leather, faster, arrows poison you.</li>
 * <li>FROST REVENANT: natural strays / skeletons in snowy biomes (`frost.chance` 30%): pale blue leather, arrows freeze you
 *     (Slowness II + the frozen overlay).</li>
 * <li>STORM PHANTOM: natural phantoms in the sky isles or above y 350 (`storm.chance` 40%): bigger, glowing, every bite is a
 *     harmless lightning flash + 4 extra damage + Slowness; extra phantom membranes.</li>
 * <li>DIRE WOLF PACKS: at night, a survival player on the surface of a wild biome rolls `packs.chance` 2% every 30 s
 *     (`packs.cooldown-minutes` 15 per player): 3-5 angry black Dire Wolves and an Alpha hunt them; gone at dawn, can't be tamed.</li>
 * </ul>
 */
final class WildMobs implements Listener, CommandExecutor, TabCompleter {

    static final String JUNGLE = "faultline_jungle_stalker", FROST = "faultline_frost_revenant", STORM = "faultline_storm_phantom",
            WOLF = "faultline_dire_wolf", ALPHA = "faultline_dire_alpha";
    private static final Set<String> JUNGLE_BIOMES = Set.of("jungle", "bamboo_jungle", "sparse_jungle");
    private static final Set<String> PACK_BIOMES = Set.of("forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "taiga",
            "old_growth_pine_taiga", "old_growth_spruce_taiga", "snowy_taiga", "plains", "snowy_plains", "meadow", "grove", "savanna",
            "savanna_plateau", "windswept_hills", "windswept_forest", "windswept_gravelly_hills", "snowy_slopes", "deep_forest");

    private final FaultlineItems plugin;
    private final Random rnd = new Random();
    private final Set<UUID> wolves = new HashSet<>();
    private final Map<UUID, Long> packReady = new HashMap<>();
    private long ticks;

    WildMobs(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 20);
    }

    private boolean on(String what) { return plugin.getConfig().getBoolean("wild-mobs." + what + ".enabled", true); }
    private double cfg(String path, double def) { return plugin.getConfig().getDouble("wild-mobs." + path, def); }

    static String biome(Location l) {
        try { return l.getBlock().getBiome().getKey().getKey(); } catch (Throwable t) { return ""; }
    }

    static boolean snowy(Location l) {
        String b = biome(l);
        return b.contains("snow") || b.contains("frozen") || b.contains("ice") || b.equals("grove") || b.contains("jagged");
    }

    static boolean skyIsles(Location l) {
        try { return l.getBlock().getBiome().getKey().toString().equals("faultline:sky_isles"); } catch (Throwable t) { return false; }
    }

    private static boolean ours(Entity e) {
        for (String t : e.getScoreboardTags()) if (t.startsWith("faultline")) return true;
        return false;
    }

    // ================================================================ biome variants

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || ours(e.getEntity())) return;
        LivingEntity le = e.getEntity();
        Location l = e.getLocation();
        EntityType t = e.getEntityType();
        if (t == EntityType.SKELETON && on("jungle") && JUNGLE_BIOMES.contains(biome(l)) && rnd.nextDouble() < cfg("jungle.chance", 0.3)) jungle((Skeleton) le);
        else if ((t == EntityType.STRAY || t == EntityType.SKELETON) && on("frost") && snowy(l) && rnd.nextDouble() < cfg("frost.chance", 0.3)) frost((AbstractSkeleton) le);
        else if (t == EntityType.PHANTOM && on("storm") && (skyIsles(l) || l.getY() >= cfg("storm.min-y", 350)) && rnd.nextDouble() < cfg("storm.chance", 0.4)) storm((Phantom) le);
    }

    private static ItemStack dyed(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(c); it.setItemMeta(lm); }
        return it;
    }

    private static void dress(LivingEntity le, Color c) {
        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;
        eq.setHelmet(dyed(Material.LEATHER_HELMET, c)); eq.setHelmetDropChance(0);
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, c)); eq.setChestplateDropChance(0);
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, c)); eq.setLeggingsDropChance(0);
        eq.setBoots(dyed(Material.LEATHER_BOOTS, c)); eq.setBootsDropChance(0);
    }

    private static void mult(LivingEntity le, Attribute a, double by) {
        AttributeInstance i = le.getAttribute(a);
        if (i != null) i.setBaseValue(i.getBaseValue() * by);
    }

    void jungle(Skeleton s) {
        s.addScoreboardTag(JUNGLE);
        s.setCustomName("Jungle Stalker"); s.setCustomNameVisible(false);
        dress(s, Color.fromRGB(0x3B6B22));
        mult(s, Attribute.MOVEMENT_SPEED, 1.2);
    }

    void frost(AbstractSkeleton s) {
        s.addScoreboardTag(FROST);
        s.setCustomName("Frost Revenant"); s.setCustomNameVisible(false);
        dress(s, Color.fromRGB(0xBFE6FF));
        mult(s, Attribute.MAX_HEALTH, 1.25); s.setHealth(s.getAttribute(Attribute.MAX_HEALTH).getValue());
    }

    void storm(Phantom p) {
        p.addScoreboardTag(STORM);
        p.setCustomName("Storm Phantom"); p.setCustomNameVisible(false);
        p.setSize(Math.max(p.getSize(), 3));
        p.setGlowing(true);
        p.setShouldBurnInDay(false);
        mult(p, Attribute.MAX_HEALTH, 1.5); p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getProjectile() instanceof Arrow a)) return;
        Set<String> tags = e.getEntity().getScoreboardTags();
        if (tags.contains(JUNGLE)) a.addCustomEffect(new PotionEffect(PotionEffectType.POISON, (int) (cfg("jungle.poison-seconds", 5) * 20), 0), true);
        else if (tags.contains(FROST)) a.addScoreboardTag(FROST);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        Entity d = e.getDamager();
        if (d.getScoreboardTags().contains(FROST)) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            victim.setFreezeTicks(Math.min(victim.getMaxFreezeTicks() + 40, victim.getFreezeTicks() + 120));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        } else if (d.getScoreboardTags().contains(STORM) && victim instanceof Player) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            e.setDamage(e.getDamage() + cfg("storm.extra-damage", 4));
        }
    }

    // ================================================================ dire wolf packs

    private void tick() {
        ticks++;
        // dawn: the packs go
        if (!wolves.isEmpty()) {
            for (Iterator<UUID> it = wolves.iterator(); it.hasNext(); ) {
                Entity en = Bukkit.getEntity(it.next());
                if (!(en instanceof Wolf w) || !w.isValid()) { it.remove(); continue; }
                if (!night(w.getWorld())) { w.getWorld().spawnParticle(Particle.CLOUD, w.getLocation(), 8, 0.3, 0.3, 0.3, 0.02); w.remove(); it.remove(); continue; }
                if (w.getTarget() == null || !w.getTarget().isValid()) {
                    Player p = nearest(w.getLocation(), 32);
                    if (p != null) { w.setAngry(true); w.setTarget(p); }
                }
            }
        }
        if (!on("packs") || ticks % 30 != 0) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL || !night(w) || w.getDifficulty() == Difficulty.PEACEFUL) continue;
            Location l = p.getLocation();
            if (l.getBlockY() < w.getHighestBlockYAt(l) - 2 || !PACK_BIOMES.contains(biome(l))) continue;
            if (now < packReady.getOrDefault(p.getUniqueId(), 0L) || rnd.nextDouble() >= cfg("packs.chance", 0.02)) continue;
            if (spawnPack(p)) packReady.put(p.getUniqueId(), now + (long) (cfg("packs.cooldown-minutes", 15) * 60000));
        }
    }

    static boolean night(World w) { long t = w.getTime(); return t >= 13000 && t <= 23000; }

    private Player nearest(Location l, double r) {
        Player best = null; double bd = r * r;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** A pack comes out of the dark 20-30 blocks away. */
    boolean spawnPack(Player p) {
        Location spot = null;
        for (int k = 0; k < 10 && spot == null; k++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 20 + rnd.nextDouble() * 10;
            int x = p.getLocation().getBlockX() + (int) (Math.cos(a) * d), z = p.getLocation().getBlockZ() + (int) (Math.sin(a) * d);
            if (!p.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            Block top = p.getWorld().getHighestBlockAt(x, z);
            if (!top.getType().isSolid() || top.isLiquid()) continue;
            spot = top.getLocation().add(0.5, 1, 0.5);
        }
        if (spot == null) return false;
        int n = 3 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) wolf(spot.clone().add(rnd.nextDouble() * 3 - 1.5, 0, rnd.nextDouble() * 3 - 1.5), p, false);
        wolf(spot, p, true);
        p.getWorld().playSound(spot, "minecraft:entity.wolf.howl", SoundCategory.HOSTILE, 2f, 0.7f);
        p.sendActionBar(ChatColor.DARK_GRAY + "Something is howling nearby...");
        return true;
    }

    Wolf wolf(Location at, Player target, boolean alpha) {
        Wolf w = (Wolf) at.getWorld().spawnEntity(at, EntityType.WOLF, CreatureSpawnEvent.SpawnReason.CUSTOM);
        w.addScoreboardTag(WOLF);
        if (alpha) w.addScoreboardTag(ALPHA);
        try { w.setVariant(Wolf.Variant.BLACK); } catch (Throwable ignored) { }
        w.setCustomName(alpha ? "Alpha Dire Wolf" : "Dire Wolf");
        w.setCustomNameVisible(alpha);
        w.setPersistent(false);
        w.setRemoveWhenFarAway(true);
        AttributeInstance hp = w.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(alpha ? cfg("packs.alpha-health", 40) : cfg("packs.health", 20));
        w.setHealth(alpha ? cfg("packs.alpha-health", 40) : cfg("packs.health", 20));
        AttributeInstance dmg = w.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(alpha ? cfg("packs.alpha-damage", 8) : cfg("packs.damage", 5));
        mult(w, Attribute.MOVEMENT_SPEED, 1.15);
        if (alpha) { AttributeInstance sc = w.getAttribute(Attribute.SCALE); if (sc != null) sc.setBaseValue(1.4); }
        w.setAngry(true);
        if (target != null) w.setTarget(target);
        wolves.add(w.getUniqueId());
        return w;
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getScoreboardTags().contains(WOLF)) wolves.add(en.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent e) { if (e.getEntity().getScoreboardTags().contains(WOLF)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(WOLF)) e.setCancelled(true);   // no feeding / taming / sitting them
    }

    // ================================================================ drops

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        Set<String> tags = le.getScoreboardTags();
        if (tags.contains(WOLF)) wolves.remove(le.getUniqueId());
        if (le.getKiller() == null) return;
        if (tags.contains(JUNGLE)) {
            e.setDroppedExp(e.getDroppedExp() * 2);
            if (rnd.nextDouble() < cfg("jungle.drop-chance", 0.08)) e.getDrops().add(tipped(PotionType.POISON, 4 + rnd.nextInt(5)));
            if (rnd.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.BAMBOO, 2 + rnd.nextInt(5)));
        } else if (tags.contains(FROST)) {
            e.setDroppedExp(e.getDroppedExp() * 2);
            if (rnd.nextDouble() < cfg("frost.drop-chance", 0.08)) e.getDrops().add(tipped(PotionType.SLOWNESS, 4 + rnd.nextInt(5)));
            if (rnd.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.PACKED_ICE, 1 + rnd.nextInt(3)));
        } else if (tags.contains(STORM)) {
            e.setDroppedExp(e.getDroppedExp() * 2);
            e.getDrops().add(new ItemStack(Material.PHANTOM_MEMBRANE, 1 + rnd.nextInt(2)));
        } else if (tags.contains(ALPHA)) {
            e.setDroppedExp(40);
            e.getDrops().add(new ItemStack(Material.LEATHER, 2 + rnd.nextInt(3)));
            e.getDrops().add(new ItemStack(Material.BONE, 2 + rnd.nextInt(3)));
            if (rnd.nextDouble() < cfg("packs.alpha-goodie-chance", 0.25)) e.getDrops().add(FaultlineItems.GoodieBagItem.create(plugin));
            if (plugin.getConfig().getBoolean("wild-mobs.packs.index-stat", true))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + le.getKiller().getName() + " dire_alphas 1");
        } else if (tags.contains(WOLF)) {
            e.setDroppedExp(8);
            if (rnd.nextDouble() < 0.5) e.getDrops().add(new ItemStack(Material.BONE, 1 + rnd.nextInt(2)));
            if (rnd.nextDouble() < 0.3) e.getDrops().add(new ItemStack(Material.LEATHER, 1));
        }
    }

    private static ItemStack tipped(PotionType type, int n) {
        ItemStack it = new ItemStack(Material.TIPPED_ARROW, n);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) { pm.setBasePotionType(type); it.setItemMeta(pm); }
        return it;
    }

    void shutdown() {
        for (UUID id : wolves) { Entity en = Bukkit.getEntity(id); if (en != null) en.remove(); }
        wolves.clear();
    }

    // ================================================================ /wildmob

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.wildmobs")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        String what = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
        switch (what) {
            case "jungle" -> jungle((Skeleton) p.getWorld().spawnEntity(at, EntityType.SKELETON, CreatureSpawnEvent.SpawnReason.COMMAND));
            case "frost" -> frost((Stray) p.getWorld().spawnEntity(at, EntityType.STRAY, CreatureSpawnEvent.SpawnReason.COMMAND));
            case "storm" -> storm((Phantom) p.getWorld().spawnEntity(at.add(0, 6, 0), EntityType.PHANTOM, CreatureSpawnEvent.SpawnReason.COMMAND));
            case "pack" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (t == null) { s.sendMessage(ChatColor.RED + "Who?"); return true; }
                if (!spawnPack(t)) { s.sendMessage(ChatColor.RED + "No room for a pack there."); return true; }
            }
            case "wolf" -> wolf(at, null, false);
            case "alpha" -> wolf(at, null, true);
            default -> { s.sendMessage(ChatColor.YELLOW + "/" + label + " <jungle|frost|storm|pack [player]|wolf|alpha>"); return true; }
        }
        s.sendMessage(ChatColor.GREEN + "Spawned: " + what);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("items.wildmobs")) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("jungle", "frost", "storm", "pack", "wolf", "alpha"), a[0]);
        return null;
    }
}
