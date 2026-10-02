package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Skeleton Wanderer: a wandering trader who sets up shop in caves (5% every 2 minutes for anyone underground)
 * and sells accessories. Java players see an animated skeleton (6 model pieces + a lantern) and click an invisible
 * box; Bedrock players (who can't see models) see a plain Wandering Trader with the same shop.
 */
final class CaveTrader implements Listener, CommandExecutor {

    static final String TAG = "faultline_cave_trader";
    private static final int LEG_R = 0, LEG_L = 1, BODY = 2, ARM_R = 3, ARM_L = 4, HEAD = 5;
    private static final String[] MODEL = {"wanderer_leg_r", "wanderer_leg_l", "wanderer_body", "wanderer_arm_r", "wanderer_arm_l", "wanderer_head"};
    private static final float[][] JOINT = {{-2, 12, 0}, {2, 12, 0}, {0, 12, 0}, {-5, 24, 0}, {5, 24, 0}, {0, 24, 0}};

    private final FaultlineItems plugin;
    private final DivingGear diving;
    private final List<Trader> traders = new ArrayList<>();
    private final Random random = new Random();
    private final Set<UUID> introduced = new HashSet<>(); // who's been credited in the Index this session
    private final Map<String, Long> errorLog = new java.util.HashMap<>();

    final class Trader {
        WanderingTrader core;
        Interaction click;
        final ItemDisplay[] parts = new ItemDisplay[6];
        ItemDisplay lantern;
        Location home;
        float yaw, headYaw, headPitch;
        int age, wave, nod;
        final Set<UUID> greeted = new HashSet<>();

        boolean valid() { return core != null && core.isValid() && click != null && click.isValid(); }

        void remove() {
            if (core != null && core.isValid()) core.remove();
            if (click != null && click.isValid()) click.remove();
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (lantern != null && lantern.isValid()) lantern.remove();
        }
    }

    CaveTrader(FaultlineItems plugin, DivingGear diving) {
        this.plugin = plugin;
        this.diving = diving;
        removeLeftovers();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> guard("animation", this::tick), 1L, 1L);
        long every = Math.max(20, (long) (cfg("check-seconds", 120) * 20));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> guard("spawning", this::spawnTick), every, every);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("cave-trader." + path, def); }

    private void guard(String what, Runnable r) {
        try { r.run(); } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - errorLog.getOrDefault(what, 0L) < 30000) return;
            errorLog.put(what, now);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Skeleton Wanderer] error in " + what + " (skipped; please report this):", e);
        }
    }

    static boolean bedrock(Player p) { return p.getUniqueId().getMostSignificantBits() == 0; } // Floodgate players

    // ================= spawning =================

    private void spawnTick() {
        if (!plugin.getConfig().getBoolean("cave-trader.enabled", true)) return;
        traders.removeIf(t -> { if (!t.valid()) { t.remove(); return true; } return false; });
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (traders.size() >= cfg("max-traders", 3)) return;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            Location l = p.getLocation();
            if (l.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (l.getBlock().getLightFromSky() > 0 || l.getY() >= cfg("below-y", 50)) continue; // underground only
            if (nearTrader(l, 128)) continue;
            if (random.nextDouble() >= cfg("chance", 0.05)) continue;
            Location spot = findSpot(p);
            if (spot == null) continue;
            spawn(spot);
            p.sendMessage(ChatColor.GRAY + "You hear bones rattling nearby... " + ChatColor.GOLD + "a Skeleton Wanderer" + ChatColor.GRAY + " has set up shop.");
            p.playSound(spot, Sound.ENTITY_SKELETON_AMBIENT, 1f, 0.7f);
        }
    }

    private boolean nearTrader(Location l, double r) {
        for (Trader t : traders) if (t.home.getWorld().equals(l.getWorld()) && t.home.distanceSquared(l) < r * r) return true;
        return false;
    }

    /** Open cave floor 5-14 blocks away: solid ground, two blocks of air, no sky above. */
    private Location findSpot(Player p) {
        Location base = p.getLocation();
        for (int tries = 0; tries < 60; tries++) {
            double ang = random.nextDouble() * Math.PI * 2, dist = 5 + random.nextDouble() * 9;
            int x = (int) Math.floor(base.getX() + Math.cos(ang) * dist), z = (int) Math.floor(base.getZ() + Math.sin(ang) * dist);
            for (int dy = -4; dy <= 4; dy++) {
                Block feet = base.getWorld().getBlockAt(x, base.getBlockY() + dy, z);
                if (!feet.getType().isAir() || !feet.getRelative(0, 1, 0).getType().isAir() || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
                if (feet.getLightFromSky() > 0) continue;
                Location spot = feet.getLocation().add(0.5, 0, 0.5);
                Vector to = base.toVector().subtract(spot.toVector());
                spot.setYaw((float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())));
                return spot;
            }
        }
        return null;
    }

    Trader spawn(Location at) {
        World w = at.getWorld();
        Trader t = new Trader();
        t.home = at.clone();
        t.yaw = at.getYaw();
        t.core = w.spawn(at, WanderingTrader.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setDespawnDelay(0);
            v.setCanPickupItems(false);
            v.setCollidable(false);
            v.addScoreboardTag(TAG);
            v.setCustomName(ChatColor.GOLD + "Skeleton Wanderer");
            v.setCustomNameVisible(false);
            v.setRotation(at.getYaw(), 0);
            v.setRecipes(stock());
        });
        t.click = w.spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(2.1f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(TAG);
        });
        for (int i = 0; i < 6; i++) {
            final int k = i;
            t.parts[i] = w.spawn(at, ItemDisplay.class, d -> setupDisplay(d, model(MODEL[k]), 1f));
        }
        t.lantern = w.spawn(at, ItemDisplay.class, d -> { setupDisplay(d, new ItemStack(Material.LANTERN), 0.42f); d.setBrightness(new Display.Brightness(15, 15)); });
        for (Player p : Bukkit.getOnlinePlayers()) if (!bedrock(p)) p.hideEntity(plugin, t.core); // Java sees the skeleton
        traders.add(t);
        render(t);
        w.spawnParticle(Particle.SOUL, at.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
        return t;
    }

    private void setupDisplay(ItemDisplay d, ItemStack item, float scale) {
        d.setItemStack(item);
        d.setPersistent(false);
        d.addScoreboardTag(TAG);
        d.setTeleportDuration(2);
        d.setInterpolationDuration(2);
        d.setBrightness(new Display.Brightness(11, 0)); // lit by his own lantern, even in a dark cave
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private ItemStack model(String name) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", name));
        s.setItemMeta(m);
        return s;
    }

    // ================= his wares =================

    private record Ware(String id, int emeralds, int diamonds, int weight) {}

    private static final List<String> DEFAULT_STOCK = List.of(
            // immunity charms
            "guardian_scale:14:0:5", "ginger_root:12:0:5", "blindfold:14:0:5", "salt_ration:12:0:5", "vitamins:14:0:5",
            "soul_ward:18:0:4", "gravity_stone:16:0:4", "four_leaf_clover:14:0:5", "night_lens:20:0:3", "wind_ward:16:0:4",
            "silk_ward:12:0:5", "slime_ward:12:0:5", "pest_charm:12:0:5",
            // other accessories
            "golden_ring:24:0:3", "climbing_claws:28:0:3", "bezoar:24:0:3", "discount_card:32:0:2", "life_jelly:30:0:2",
            "shield_of_the_ocean:36:2:2", "lava_charm:30:0:3", "black_belt:28:0:3", "spelunker_amulet:24:0:4",
            "harpy_ring:30:0:3", "ranger_emblem:34:1:2",
            // diving gear
            "diving_helmet:22:0:5", "flipper:22:0:5", "depths_charm:40:4:1",
            // the Kraken
            "kraken_worm:16:0:3");

    private Map<String, Supplier<ItemStack>> items() {
        Map<String, Supplier<ItemStack>> m = new LinkedHashMap<>();
        for (FaultlineItems.ImmunityCharms.Charm c : FaultlineItems.ImmunityCharms.Charm.values())
            m.put(c.id, () -> FaultlineItems.ImmunityCharms.item(plugin, c));
        m.put("golden_ring", () -> FaultlineItems.GoldenRingItem.create(plugin));
        m.put("climbing_claws", () -> FaultlineItems.ClimbingClawsItem.create(plugin));
        m.put("bezoar", () -> FaultlineItems.BezoarItem.create(plugin));
        m.put("discount_card", () -> FaultlineItems.DiscountCardItem.create(plugin));
        m.put("life_jelly", () -> FaultlineItems.LifeJellyItem.create(plugin));
        m.put("shield_of_the_ocean", () -> FaultlineItems.ShieldOfTheOceanItem.create(plugin));
        m.put("lava_charm", () -> FaultlineItems.NewAccessoryItems.lavaCharm(plugin));
        m.put("black_belt", () -> FaultlineItems.NewAccessoryItems.blackBelt(plugin));
        m.put("spelunker_amulet", () -> FaultlineItems.NewAccessoryItems.spelunkerAmulet(plugin));
        m.put("harpy_ring", () -> FaultlineItems.NewAccessoryItems.harpyRing(plugin));
        m.put("ranger_emblem", () -> FaultlineItems.NewAccessoryItems.rangerEmblem(plugin));
        m.put("diving_helmet", () -> diving.item(DivingGear.Piece.HELMET));
        m.put("flipper", () -> diving.item(DivingGear.Piece.FLIPPER));
        m.put("depths_charm", () -> diving.item(DivingGear.Piece.DEPTHS));
        if (plugin.getKrakenGear() != null) m.put("kraken_worm", () -> plugin.getKrakenGear().worm());
        return m;
    }

    private List<Ware> wares() {
        List<String> lines = plugin.getConfig().isList("cave-trader.stock") ? plugin.getConfig().getStringList("cave-trader.stock") : DEFAULT_STOCK;
        List<Ware> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split(":");
            try {
                out.add(new Ware(f[0].trim(), Integer.parseInt(f[1].trim()), f.length > 2 ? Integer.parseInt(f[2].trim()) : 0, f.length > 3 ? Integer.parseInt(f[3].trim()) : 1));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[Skeleton Wanderer] skipping a bad stock line: " + line + " (use id:emeralds:diamonds:weight)");
            }
        }
        return out;
    }

    /** A random selection of his wares (weighted, no repeats). */
    List<MerchantRecipe> stock() {
        Map<String, Supplier<ItemStack>> items = items();
        List<Ware> pool = new ArrayList<>();
        for (Ware w : wares()) {
            if (items.containsKey(w.id())) pool.add(w);
            else plugin.getLogger().warning("[Skeleton Wanderer] unknown item in stock: " + w.id());
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        int offers = (int) cfg("offers", 5);
        while (recipes.size() < offers && !pool.isEmpty()) {
            int total = 0;
            for (Ware w : pool) total += Math.max(1, w.weight());
            int roll = random.nextInt(total);
            Ware pick = pool.get(pool.size() - 1);
            for (Ware w : pool) { roll -= Math.max(1, w.weight()); if (roll < 0) { pick = w; break; } }
            pool.remove(pick);
            MerchantRecipe r = new MerchantRecipe(items.get(pick.id()).get(), 1 + random.nextInt(2));
            r.addIngredient(new ItemStack(Material.EMERALD, Math.max(1, Math.min(64, pick.emeralds()))));
            if (pick.diamonds() > 0) r.addIngredient(new ItemStack(Material.DIAMOND, Math.min(64, pick.diamonds())));
            r.setExperienceReward(false);
            r.setIgnoreDiscounts(true);
            recipes.add(r);
        }
        return recipes;
    }

    // ================= animation =================

    private void tick() {
        for (int i = traders.size() - 1; i >= 0; i--) {
            Trader t = traders.get(i);
            if (!t.valid()) { t.remove(); traders.remove(i); continue; } // e.g. his area unloaded
            t.age++;
            if (t.age > cfg("lifetime-minutes", 8) * 1200) { leave(t); traders.remove(i); continue; }
            render(t);
        }
    }

    private void leave(Trader t) {
        Location at = t.home.clone().add(0, 1, 0);
        at.getWorld().spawnParticle(Particle.SOUL, at, 30, 0.4, 0.8, 0.4, 0.03);
        at.getWorld().spawnParticle(Particle.POOF, at, 20, 0.4, 0.8, 0.4, 0.02);
        at.getWorld().playSound(at, Sound.ENTITY_SKELETON_AMBIENT, 1f, 0.6f);
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < 32 * 32) p.sendMessage(ChatColor.GRAY + "The Skeleton Wanderer packs up his wares and rattles off into the dark.");
        t.remove();
    }

    private void render(Trader t) {
        Player look = null; double best = 10 * 10;
        for (Player p : t.home.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(t.home);
            if (d < best) { best = d; look = p; }
        }
        // turns (slowly) to face whoever's closest, and beckons the first time someone walks up
        if (look != null) {
            Vector to = look.getLocation().toVector().subtract(t.home.toVector());
            float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            float diff = ((want - t.yaw) % 360 + 540) % 360 - 180;
            if (Math.abs(diff) > 50) t.yaw += Math.signum(diff) * Math.min(Math.abs(diff) - 50, 4);
            if (best < 7 * 7 && t.greeted.add(look.getUniqueId())) {
                t.wave = 40;
                t.home.getWorld().playSound(t.home, Sound.ENTITY_SKELETON_AMBIENT, 0.8f, 1.4f);
            }
            Vector eye = look.getEyeLocation().toVector().subtract(t.home.toVector().add(new Vector(0, 1.6, 0)));
            float rel = ((want - t.yaw) % 360 + 540) % 360 - 180;
            float pitch = (float) -Math.toDegrees(Math.atan2(eye.getY(), Math.max(0.5, Math.hypot(eye.getX(), eye.getZ()))));
            t.headYaw += (-Math.max(-45, Math.min(45, rel)) - t.headYaw) * 0.2f;
            t.headPitch += (Math.max(-30, Math.min(30, pitch)) - t.headPitch) * 0.2f;
        } else { t.headYaw *= 0.9f; t.headPitch *= 0.9f; }
        float breath = (float) Math.sin(t.age * 0.07), sway = (float) Math.sin(t.age * 0.045);
        float[][] r = new float[6][3];
        r[BODY] = new float[]{3 + breath * 1.5f, 0, sway * 1.5f};                 // a slight stoop, breathing
        r[LEG_R] = new float[]{0, 0, -2}; r[LEG_L] = new float[]{0, 0, 2};
        r[ARM_L] = new float[]{-32 + sway * 4, 0, 8};                              // holds the lantern out
        r[ARM_R] = new float[]{6 + breath * 2, 0, -7};
        r[HEAD] = new float[]{t.headPitch + 4, t.headYaw, 6};                      // a curious head tilt
        if (t.wave > 0) { // beckons you over
            float w = (float) Math.sin(t.wave * 0.45);
            r[ARM_R] = new float[]{-150 + w * 25, 0, -18};
            t.wave--;
        }
        if (t.nod > 0) { r[HEAD][0] += (float) Math.sin(t.nod / 20.0 * Math.PI * 2) * 14; t.nod--; } // a nod when you open his shop
        renderRig(t, r);
    }

    private static Quaternionf euler(float[] r) {
        return new Quaternionf().rotateY((float) Math.toRadians(r[1])).rotateX((float) Math.toRadians(r[0])).rotateZ((float) Math.toRadians(r[2]));
    }

    /** Same joint math as Don Lorenzo's rig: arms and head follow the body's lean. */
    private void renderRig(Trader t, float[][] r) {
        float scale = (float) cfg("scale", 1.0);
        Location root = t.home.clone();
        Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(t.yaw));
        Quaternionf qBody = euler(r[BODY]);
        Quaternionf flip = new Quaternionf().rotateY((float) Math.PI);
        Vector3f hips = new Vector3f(0, 12, 0);
        float px = scale / 16f;
        for (int i = 0; i < 6; i++) {
            ItemDisplay d = t.parts[i];
            if (d == null || !d.isValid()) continue;
            Vector3f joint = new Vector3f(JOINT[i][0], JOINT[i][1], JOINT[i][2]);
            Quaternionf rot;
            if (i == ARM_R || i == ARM_L || i == HEAD) {
                joint.sub(hips); qBody.transform(joint); joint.add(hips);
                rot = new Quaternionf(qYaw).mul(qBody).mul(euler(r[i]));
            } else if (i == BODY) rot = new Quaternionf(qYaw).mul(qBody);
            else rot = new Quaternionf(qYaw).mul(euler(r[i]));
            joint.mul(px); qYaw.transform(joint);
            Location at = root.clone().add(joint.x, joint.y, joint.z);
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), rot.mul(flip), new Vector3f(scale, scale, scale), new Quaternionf()));
        }
        if (t.lantern != null && t.lantern.isValid()) { // hangs from his left hand, swinging a little
            Vector3f shoulder = new Vector3f(JOINT[ARM_L][0], JOINT[ARM_L][1], JOINT[ARM_L][2]);
            shoulder.sub(hips); qBody.transform(shoulder); shoulder.add(hips);
            Vector3f hand = new Quaternionf(qBody).mul(euler(r[ARM_L])).transform(new Vector3f(0, -11, 0)).add(shoulder).add(0, -3.5f, 0).mul(px);
            qYaw.transform(hand);
            Location at = root.clone().add(hand.x, hand.y, hand.z);
            at.setYaw(0); at.setPitch(0);
            t.lantern.teleport(at);
            t.lantern.setInterpolationDelay(0);
            float swing = (float) Math.sin(t.age * 0.08) * 0.12f;
            t.lantern.setTransformation(new Transformation(new Vector3f(), new Quaternionf(qYaw).rotateZ(swing),
                    new Vector3f(0.42f * scale, 0.42f * scale, 0.42f * scale), new Quaternionf()));
        }
    }

    // ================= trading, protection, cleanup =================

    private Trader byEntity(Entity e) {
        for (Trader t : traders) if (e.equals(t.click) || e.equals(t.core)) return t;
        return null;
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getScoreboardTags().contains(TAG)) return;
        if (event.getHand() != EquipmentSlot.HAND) { event.setCancelled(true); return; }
        Trader t = byEntity(event.getRightClicked());
        if (t == null) { event.setCancelled(true); return; }
        Player p = event.getPlayer();
        if (event.getRightClicked() instanceof Interaction) { // Java: open his shop (Bedrock clicks the trader itself)
            event.setCancelled(true);
            p.openMerchant(t.core, true);
        }
        t.nod = 20;
        p.playSound(t.home, Sound.ENTITY_SKELETON_AMBIENT, 0.7f, 1.6f);
        if (introduced.add(p.getUniqueId()))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + p.getName() + " skeleton_wanderer");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (bedrock(p)) return;
        for (Trader t : traders) if (t.core != null && t.core.isValid()) p.hideEntity(plugin, t.core);
    }

    void removeAll() {
        for (Trader t : traders) t.remove();
        traders.clear();
    }

    private void removeLeftovers() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Vector ahead = p.getLocation().getDirection().setY(0);
                if (ahead.lengthSquared() < 0.0001) ahead = new Vector(1, 0, 0);
                Location at = p.getLocation().add(ahead.normalize().multiply(3));
                at.setY(Math.floor(at.getY()));
                Vector back = p.getLocation().toVector().subtract(at.toVector());
                at.setYaw((float) Math.toDegrees(Math.atan2(-back.getX(), back.getZ())));
                spawn(at);
                sender.sendMessage(ChatColor.GREEN + "A Skeleton Wanderer set up shop in front of you.");
            }
            case "kill" -> {
                int n = traders.size();
                removeAll();
                sender.sendMessage(ChatColor.GREEN + "Removed " + n + " Skeleton Wanderer" + (n == 1 ? "" : "s") + ".");
            }
            case "reroll" -> {
                for (Trader t : traders) if (t.core != null && t.core.isValid()) t.core.setRecipes(stock());
                sender.sendMessage(ChatColor.GREEN + "Rerolled every Skeleton Wanderer's wares.");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/cavetrader <spawn|kill|reroll>");
        }
        return true;
    }
}
