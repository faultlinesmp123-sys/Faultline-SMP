package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;

import java.util.*;

/**
 * CREATURES (the "wild" update):
 *   MIMICS           1% of the loot chests you find (dungeons, temples, mineshafts...) bite back: the chest grows teeth
 *                    and legs. Kill it for everything that was inside, and more. (Java: the chest model snapping its
 *                    lid; Bedrock: the same model on a stand.) Config mimic:.
 *   SKELETON KNIGHTS at night, patrols of armoured skeletons ride out on skeleton horses (a captain and his knights) and
 *                    hunt whoever they find. They wear great helms (the Knight's Helm drops sometimes) and ride off at dawn.
 * Admin: /fcreature <mimic|knights> [here]
 */
final class Creatures implements Listener, CommandExecutor {

    static final String MIMIC_TAG = "faultline_mimic", MIMIC_FX = "faultline_mimic_part", KNIGHT_TAG = "faultline_skeleton_knight",
            KNIGHT_HORSE_TAG = "faultline_knight_horse";

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final List<Mimic> mimics = new ArrayList<>();
    private int ticks;

    Creatures(FaultlineItems plugin) {
        this.plugin = plugin;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
            Set<String> t = e.getScoreboardTags();
            if (t.contains(MIMIC_TAG) || t.contains(MIMIC_FX)) e.remove();
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    double cfg(String path, double def) { return plugin.getConfig().getDouble(path, def); }

    private void tick() {
        ticks++;
        mimics.removeIf(m -> {
            // still listed but gone = unloaded or removed, not killed (onDeath takes killed ones off the list): the chest comes back.
            // (isDead() can't tell: Bukkit reports a removed mob as dead too)
            if (!m.alive()) { m.restore(); m.remove(); return true; }
            m.tick(); return false;
        });
        if (ticks % (int) Math.max(200, cfg("skeleton-knights.check-seconds", 300) * 20) == 0 && plugin.getConfig().getBoolean("skeleton-knights.enabled", true)) patrols();
        if (ticks % 200 == 0) dawn();
    }

    // =====================================================================================================
    //  MIMICS
    // =====================================================================================================
    // HIGH: after protection plugins, so a chest you aren't allowed to open never wakes up
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.CHEST || !(b.getState() instanceof Chest chest)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isSneaking() && !p.getInventory().getItemInMainHand().getType().isAir()) return;
        if (!plugin.getConfig().getBoolean("mimic.enabled", true)) return;
        LootTable table = chest.getLootTable();
        if (table == null) return; // only the chests the world made (never one a player placed or already opened)
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest cd && cd.getType() != Type.SINGLE) return;
        if (random.nextDouble() >= cfg("mimic.chance", 0.01)) return;
        e.setCancelled(true);
        awaken(b, table, p);
    }

    Mimic awaken(Block b, LootTable table, Player by) {
        float yaw = b.getBlockData() instanceof org.bukkit.block.data.Directional d ? faceYaw(d.getFacing()) : 0;
        org.bukkit.block.data.BlockData was = b.getBlockData();
        if (b.getState() instanceof Chest c) c.getBlockInventory().clear();
        b.setType(Material.AIR);
        Location at = b.getLocation().add(0.5, 0, 0.5);
        at.setYaw(yaw);
        Mimic m = new Mimic(at, table);
        m.home = b; m.homeData = was;
        mimics.add(m);
        World w = b.getWorld();
        w.playSound(at, Sound.BLOCK_CHEST_OPEN, SoundCategory.HOSTILE, 1.5f, 0.5f);
        w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1f, 1.8f);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 30, 0.4, 0.4, 0.4, 0, Material.OAK_PLANKS.createBlockData());
        if (by != null) {
            m.body.setTarget(by);
            by.sendActionBar(Meteor.legacy(ChatColor.RED + "" + ChatColor.BOLD + "IT'S A MIMIC!"));
        }
        return m;
    }

    static float faceYaw(org.bukkit.block.BlockFace f) {
        return switch (f) { case SOUTH -> 0; case WEST -> 90; case NORTH -> 180; case EAST -> -90; default -> 0; };
    }

    final class Mimic {
        final Spider body;
        final ItemDisplay base, lid;
        final ArmorStand baseStand, lidStand;
        final LootTable table;
        Block home;
        org.bukkit.block.data.BlockData homeData;
        int t;

        Mimic(Location at, LootTable table) {
            this.table = table;
            World w = at.getWorld();
            body = w.spawn(at, Spider.class, s -> {
                s.addScoreboardTag(MIMIC_TAG);
                s.setCustomName(ChatColor.GOLD + "Mimic");
                s.setCustomNameVisible(false);
                s.setPersistent(false);
                s.setRemoveWhenFarAway(false);
                s.setSilent(true);
                s.setInvisible(true);
                attr(s, Attribute.MAX_HEALTH, cfg("mimic.health", 60));
                s.setHealth(cfg("mimic.health", 60));
                attr(s, Attribute.ATTACK_DAMAGE, cfg("mimic.damage", 8));
                attr(s, Attribute.MOVEMENT_SPEED, cfg("mimic.speed", 0.32));
                attr(s, Attribute.SCALE, 0.8);
                attr(s, Attribute.FOLLOW_RANGE, 32);
            });
            base = WildRig.display(w, at, "mimic_base", MIMIC_FX);
            lid = WildRig.display(w, at, "mimic_lid", MIMIC_FX);
            baseStand = WildRig.stand(plugin, w, at, "mimic_base", 1.0, MIMIC_FX);
            lidStand = WildRig.stand(plugin, w, at, "mimic_lid", 1.0, MIMIC_FX);
            StandGuard.forward(baseStand, body);
            StandGuard.forward(lidStand, body);
            tick();
        }

        boolean alive() { return body.isValid() && !body.isDead(); }

        void tick() {
            t++;
            Location l = body.getLocation();
            float yaw = l.getYaw();
            boolean hunting = body.getTarget() != null && body.getTarget().getLocation().distanceSquared(l) < 25;
            double open = hunting ? Math.abs(Math.sin(t * 0.35)) * 60 : 8 + Math.abs(Math.sin(t * 0.08)) * 10;
            double hop = body.isOnGround() ? Math.abs(Math.sin(t * 0.5)) * 0.08 : 0;
            Location bl = l.clone().add(0, hop, 0);
            WildRig.pose(base, baseStand, "mimic_base", 1.0, bl, yaw, null);
            Location hinge = bl.clone().add(Wildish.dir(yaw).multiply(-0.45)).add(0, 0.62, 0);
            WildRig.pose(lid, null, "mimic_lid", 1.0, hinge, yaw, new Quaternionf(new AxisAngle4f((float) Math.toRadians(open), 0, 0, 1)));
            // Bedrock: an armor stand can't tilt its helmet, so the lid lifts and slides back as it opens instead
            double lift = Math.sin(Math.toRadians(open));
            Location lidAt = hinge.clone().add(Wildish.dir(yaw).multiply(-0.15 * lift)).add(0, 0.35 * lift, 0);
            WildRig.pose(null, lidStand, "mimic_lid", 1.0, lidAt, yaw, null);
            if (hunting && t % 8 == 0) body.getWorld().playSound(l, Sound.BLOCK_CHEST_CLOSE, SoundCategory.HOSTILE, 0.8f, 1.4f);
        }

        /** It went away without being killed (chunk unloaded, restart): put its chest back, loot table and all. */
        void restore() {
            if (home == null || homeData == null) return;
            try {
                if (!home.getType().isAir() && !home.isLiquid()) return;
                home.setBlockData(homeData, false);
                if (home.getState() instanceof Chest c) { c.setLootTable(table); c.update(true, false); }
            } catch (RuntimeException ignored) { }
            home = null;
        }

        void remove() {
            StandGuard.forget(baseStand); StandGuard.forget(lidStand);
            for (Entity e : new Entity[]{base, lid, baseStand, lidStand}) if (e != null && e.isValid()) e.remove();
            if (body.isValid() && !body.isDead()) body.remove();
        }
    }

    // =====================================================================================================
    //  SKELETON KNIGHTS
    // =====================================================================================================
    void patrols() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL || Weather.isDay(w)) continue;
            if (p.getLocation().getBlock().getLightFromSky() < 12) continue; // out on the surface
            if (random.nextDouble() >= cfg("skeleton-knights.chance", 0.12)) continue;
            long near = p.getNearbyEntities(64, 32, 64).stream().filter(e -> e.getScoreboardTags().contains(KNIGHT_TAG)).count();
            if (near > 0) continue;
            Location at = surface(p.getLocation(), 22, 32);
            if (at != null) patrol(at, p);
        }
    }

    Location surface(Location l, double min, double max) {
        World w = l.getWorld();
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (top.isLiquid() || Math.abs(top.getY() - l.getY()) > 14) continue;
            return top.getLocation().add(0.5, 1, 0.5);
        }
        return null;
    }

    List<Skeleton> patrol(Location at, Player target) {
        List<Skeleton> out = new ArrayList<>();
        int n = 2 + random.nextInt(2);
        for (int i = 0; i <= n; i++) {
            boolean captain = i == 0;
            Location l = at.clone().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2);
            l.setY(at.getWorld().getHighestBlockYAt(l, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
            SkeletonHorse horse = at.getWorld().spawn(l, SkeletonHorse.class, h -> {
                h.addScoreboardTag(KNIGHT_HORSE_TAG);
                h.setTamed(true);
                h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                h.setPersistent(false);
                attr(h, Attribute.MOVEMENT_SPEED, 0.28);
            });
            Skeleton k = at.getWorld().spawn(l, Skeleton.class, s -> {
                s.addScoreboardTag(KNIGHT_TAG);
                s.setCustomName(captain ? ChatColor.WHITE + "" + ChatColor.BOLD + "Skeleton Knight Captain" : ChatColor.WHITE + "Skeleton Knight");
                s.setCustomNameVisible(false);
                s.setPersistent(false);
                s.setShouldBurnInDay(false);
                attr(s, Attribute.MAX_HEALTH, captain ? cfg("skeleton-knights.captain-health", 50) : cfg("skeleton-knights.health", 32));
                s.setHealth(captain ? cfg("skeleton-knights.captain-health", 50) : cfg("skeleton-knights.health", 32));
                attr(s, Attribute.ATTACK_DAMAGE, captain ? 7 : 5);
                attr(s, Attribute.FOLLOW_RANGE, 40);
                var eq = s.getEquipment();
                eq.setHelmet(plugin.getGear().item("knight_helm"));
                eq.setChestplate(new ItemStack(captain ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                eq.setBoots(new ItemStack(Material.IRON_BOOTS));
                eq.setItemInMainHand(new ItemStack(captain ? Material.DIAMOND_SWORD : Material.IRON_SWORD));
                eq.setItemInOffHand(new ItemStack(Material.SHIELD));
                eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
                eq.setItemInMainHandDropChance(0.04f); eq.setItemInOffHandDropChance(0);
            });
            horse.addPassenger(k);
            if (target != null) k.setTarget(target);
            out.add(k);
        }
        at.getWorld().playSound(at, Sound.ENTITY_SKELETON_HORSE_AMBIENT, SoundCategory.HOSTILE, 3f, 0.6f);
        if (target != null) target.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "Hoofbeats in the dark... a patrol of Skeleton Knights rides your way.");
        return out;
    }

    /** At dawn the patrols ride off. */
    void dawn() {
        for (World w : Bukkit.getWorlds()) {
            if (!Weather.isDay(w)) continue;
            for (Entity e : w.getEntities()) {
                Set<String> t = e.getScoreboardTags();
                if (!t.contains(KNIGHT_TAG) && !t.contains(KNIGHT_HORSE_TAG)) continue;
                w.spawnParticle(Particle.CLOUD, e.getLocation().add(0, 1, 0), 10, 0.4, 0.6, 0.4, 0.02);
                e.remove();
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity d = e.getEntity();
        Set<String> t = d.getScoreboardTags();
        if (t.contains(MIMIC_TAG)) {
            Mimic m = null;
            for (Mimic x : mimics) if (x.body.equals(d)) m = x;
            e.getDrops().clear();
            e.setDroppedExp((int) cfg("mimic.xp", 40));
            if (m != null && m.table != null) {
                try {
                    LootContext ctx = new LootContext.Builder(d.getLocation()).lootedEntity(d).killer(d.getKiller()).build();
                    e.getDrops().addAll(m.table.populateLoot(random, ctx));
                } catch (RuntimeException ignored) { }
            }
            e.getDrops().add(new ItemStack(Material.CHEST));
            e.getDrops().add(new ItemStack(Material.GOLD_INGOT, 2 + random.nextInt(5)));
            if (random.nextDouble() < 0.35) e.getDrops().add(new ItemStack(Material.DIAMOND, 1 + random.nextInt(2)));
            if (d.getKiller() != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + d.getKiller().getName() + " mimics 1");
            if (m != null) { m.remove(); mimics.remove(m); }
        } else if (t.contains(KNIGHT_TAG)) {
            e.getDrops().removeIf(it -> it != null && plugin.getGear().id(it) != null);
            e.getDrops().add(new ItemStack(Material.BONE, 1 + random.nextInt(3)));
            e.getDrops().add(new ItemStack(Material.IRON_NUGGET, 2 + random.nextInt(5)));
            if (d.getKiller() != null && random.nextDouble() < cfg("skeleton-knights.helm-chance", 0.06)) e.getDrops().add(plugin.getGear().item("knight_helm"));
            if (d.getCustomName() != null && d.getCustomName().contains("Captain")) e.getDrops().add(new ItemStack(Material.EMERALD, 2 + random.nextInt(4)));
            e.setDroppedExp(20);
            if (d.getVehicle() != null) d.getVehicle().remove();
        } else if (t.contains(KNIGHT_HORSE_TAG)) {
            e.getDrops().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) {
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(KNIGHT_TAG) || t.contains(KNIGHT_HORSE_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Mimic m : mimics) for (ArmorStand s : new ArmorStand[]{m.baseStand, m.lidStand})
            if (s.isValid()) { if (Weather.bedrock(p)) p.showEntity(plugin, s); else p.hideEntity(plugin, s); }
    }

    void shutdown() { for (Mimic m : mimics) { m.restore(); m.remove(); } mimics.clear(); }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.fcreature")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        Player p = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player sp ? sp : null;
        if (p == null) { sender.sendMessage("/fcreature <mimic|knights> [player]"); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "mimic" -> {
                Block b = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)).getBlock();
                awaken(b, org.bukkit.Bukkit.getLootTable(org.bukkit.NamespacedKey.minecraft("chests/simple_dungeon")), p);
                sender.sendMessage(ChatColor.GREEN + "A mimic!");
            }
            case "knights" -> {
                Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(8));
                at.setY(at.getWorld().getHighestBlockYAt(at, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
                patrol(at, p);
                sender.sendMessage(ChatColor.GREEN + "A Skeleton Knight patrol. (They ride off at dawn.)");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/fcreature <mimic|knights>");
        }
        return true;
    }

    /** Small helpers. */
    static final class Wildish {
        static org.bukkit.util.Vector dir(float yaw) { return new org.bukkit.util.Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }
    }
}
