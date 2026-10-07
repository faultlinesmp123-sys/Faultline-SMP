package net.faultlinesmp.items;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Supplier;

/**
 * THE CARAVAN: a traveling merchant who rolls in every few hours near a random player, stays for one hour, and sells
 * rare things for diamonds, emeralds and netherite: totems, elytra, mythic bags, top enchanted books, templates, a
 * Kraken bait worm, even the Ankh and the Cross. Everyone is told where he's set up. Right-click him to trade (Java and
 * Bedrock: he's a real Wandering Trader, with two trader llamas and a camel).
 */
final class Caravan implements Listener, CommandExecutor {

    static final String TAG = "faultline_caravan";

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private long nextAt;
    private Camp camp;
    private final Set<UUID> introduced = new HashSet<>();

    final class Camp {
        WanderingTrader merchant;
        final List<LivingEntity> animals = new ArrayList<>();
        Location at;
        long leavesAt;
        int ticks;
        boolean valid() { return merchant != null && merchant.isValid(); }
        void remove() {
            if (merchant != null && merchant.isValid()) merchant.remove();
            for (LivingEntity a : animals) if (a.isValid()) a.remove();
        }
    }

    Caravan(FaultlineItems plugin) {
        this.plugin = plugin;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        nextAt = System.currentTimeMillis() + (long) (cfg("first-minutes", 30) * 60000);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("caravan." + path, def); }

    private void tick() {
        try {
            if (camp == null) {
                if (plugin.getConfig().getBoolean("caravan.enabled", true) && System.currentTimeMillis() >= nextAt) {
                    nextAt = System.currentTimeMillis() + (long) (cfg("every-minutes", 180) * 60000);
                    arrive(null);
                }
                return;
            }
            Camp c = camp;
            c.ticks++;
            if (!c.valid()) { // his chunk unloaded: he packs up (comes back next time)
                if (c.at.isChunkLoaded()) { leave(false); return; }
                if (System.currentTimeMillis() >= c.leavesAt) leave(false);
                return;
            }
            long left = c.leavesAt - System.currentTimeMillis();
            if (left <= 0) { leave(true); return; }
            long min = left / 60000;
            c.merchant.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Caravan Merchant " + ChatColor.GRAY + "(leaves in " + (min + 1) + "m)");
            if (left < 5 * 60000 && left > 5 * 60000 - 1000)
                Bukkit.broadcastMessage(ChatColor.GOLD + "The Caravan Merchant " + ChatColor.YELLOW + "leaves in 5 minutes " + ChatColor.GRAY + "(X " + c.at.getBlockX() + ", Z " + c.at.getBlockZ() + ")");
            if (c.ticks % 3 == 0) c.at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.merchant.getLocation().add(0, 2.3, 0), 2, 0.3, 0.2, 0.3, 0);
            if (c.ticks % 7 == 0) { // keeps his animals by his side
                for (LivingEntity a : c.animals) if (a.isValid() && a.getLocation().distanceSquared(c.at) > 12 * 12) a.teleport(c.at.clone().add(2, 0, 2));
                if (c.merchant.getLocation().distanceSquared(c.at) > 4) c.merchant.teleport(c.at);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Caravan] error (skipped; please report this):", e);
        }
    }

    /** Sets up camp near a random survival player in the Overworld (or right by `near`). */
    boolean arrive(Player near) {
        if (camp != null) return false;
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (near != null && p != near) continue;
            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (near == null && p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            pool.add(p);
        }
        if (pool.isEmpty()) return false;
        Player p = pool.get(random.nextInt(pool.size()));
        double min = near != null ? 4 : cfg("min-distance", 40), max = near != null ? 6 : cfg("max-distance", 100);
        Location spot = null;
        for (int tries = 0; tries < 30 && spot == null; tries++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getLocation().getX() + Math.cos(a) * d), z = (int) Math.floor(p.getLocation().getZ() + Math.sin(a) * d);
            if (!p.getWorld().isChunkGenerated(x >> 4, z >> 4)) continue;
            Block top = p.getWorld().getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!top.getType().isSolid() || top.isLiquid() || Tag_isLeaves(top.getType())) continue;
            if (!top.getRelative(0, 1, 0).getType().isAir() || !top.getRelative(0, 2, 0).getType().isAir()) continue;
            spot = top.getLocation().add(0.5, 1, 0.5);
        }
        if (spot == null) return false;
        Vector look = p.getLocation().toVector().subtract(spot.toVector()).setY(0);
        if (look.lengthSquared() > 0.01) spot.setDirection(look);
        setUp(spot);
        String where = ChatColor.WHITE + "X " + spot.getBlockX() + ", Z " + spot.getBlockZ();
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "The Caravan has arrived! " + ChatColor.YELLOW + "A traveling merchant set up camp at "
                + where + ChatColor.YELLOW + " for one hour. He takes diamonds, emeralds and netherite.");
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 0.8f, 0.9f);
        return true;
    }

    private static boolean Tag_isLeaves(Material m) { return org.bukkit.Tag.LEAVES.isTagged(m); }

    private void setUp(Location at) {
        World w = at.getWorld();
        Camp c = new Camp();
        c.at = at.clone();
        c.leavesAt = System.currentTimeMillis() + (long) (cfg("stay-minutes", 60) * 60000);
        c.merchant = w.spawn(at, WanderingTrader.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setDespawnDelay(0);
            v.setCanPickupItems(false);
            v.addScoreboardTag(TAG);
            v.setCustomNameVisible(true);
            v.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Caravan Merchant");
            v.setRecipes(stock());
            v.setRotation(at.getYaw(), 0);
        });
        Vector side = at.getDirection().setY(0);
        if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
        side = new Vector(-side.getZ(), 0, side.getX()).normalize();
        for (int i = 0; i < 2; i++) {
            Location l = safe(at.clone().add(side.clone().multiply(i == 0 ? 2.5 : -2.5)).add(at.getDirection().setY(0).multiply(-1.5)));
            TraderLlama llama = w.spawn(l, TraderLlama.class, m -> {
                m.setInvulnerable(true); m.setPersistent(false); m.setRemoveWhenFarAway(false); m.addScoreboardTag(TAG);
                m.setColor(random.nextBoolean() ? Llama.Color.BROWN : Llama.Color.CREAMY);
                m.setAdult();
            });
            llama.setLeashHolder(c.merchant);
            c.animals.add(llama);
        }
        Location cl = safe(at.clone().add(at.getDirection().setY(0).multiply(-3.5)));
        c.animals.add(w.spawn(cl, Camel.class, m -> {
            m.setInvulnerable(true); m.setPersistent(false); m.setRemoveWhenFarAway(false); m.addScoreboardTag(TAG);
            m.setAI(false); m.setAdult(); m.setTamed(true);
            m.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        }));
        w.spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30, 1.5, 0.5, 1.5, 0.02);
        camp = c;
        // BUG FIX: when nobody was near, his chunk unloaded and took him with it (he's not saved), so whoever came looking
        // found nothing. His chunk now stays loaded until he leaves.
        w.addPluginChunkTicket(at.getBlockX() >> 4, at.getBlockZ() >> 4, plugin);
    }

    private Location safe(Location l) {
        Block top = l.getWorld().getHighestBlockAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return top.getLocation().add(0.5, 1, 0.5);
    }

    void leave(boolean say) {
        Camp c = camp;
        camp = null;
        if (c == null) return;
        if (say && c.valid()) {
            c.at.getWorld().spawnParticle(Particle.CLOUD, c.at.clone().add(0, 1, 0), 30, 1.5, 0.5, 1.5, 0.02);
            Bukkit.broadcastMessage(ChatColor.GOLD + "The Caravan " + ChatColor.GRAY + "has packed up and moved on. It'll be back.");
        }
        c.remove();
        c.at.getWorld().removePluginChunkTicket(c.at.getBlockX() >> 4, c.at.getBlockZ() >> 4, plugin);
    }

    // ===================================================================== his wares

    record Ware(String id, int emeralds, int diamonds, int netherite, int weight, int uses) {}

    private static final List<String> DEFAULT_STOCK = List.of(
            // id:emeralds:diamonds:netherite:weight:uses
            "mythic_bag:24:4:0:5:2", "goodie_bags:16:0:0:6:3", "totem:12:2:0:6:2", "enchanted_golden_apple:0:8:1:3:1",
            "elytra:0:16:2:1:1", "netherite_template:0:12:0:4:2", "heart_of_the_sea:0:8:0:4:1", "nether_star:0:12:1:2:1",
            "shulker_shells:10:0:0:6:3", "trident:0:10:0:3:1", "mending:24:0:0:6:2", "unbreaking:14:0:0:6:3",
            "protection:16:0:0:6:3", "sharpness:20:0:0:5:2", "efficiency:16:0:0:5:2", "fortune:24:2:0:4:1", "looting:24:2:0:4:1",
            "sniffer_egg:16:0:0:3:1", "silence_trim:16:4:0:1:1", "eye_trim:12:2:0:2:1", "spire_trim:12:2:0:2:1",
            "kraken_worm:0:12:0:3:1", "wither_skull:0:10:0:3:1", "pigstep:8:0:0:3:1", "ankh:0:16:2:1:1", "cross:0:16:2:1:1",
            "golden_carrots:8:0:0:5:4", "experience_bottles:12:0:0:5:3", "netherite_scrap:0:6:0:4:2");

    private ItemStack book(Enchantment e, int level) {
        ItemStack b = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta m = (EnchantmentStorageMeta) b.getItemMeta();
        m.addStoredEnchant(e, level, true);
        b.setItemMeta(m);
        return b;
    }

    private Map<String, Supplier<ItemStack>> items() {
        Map<String, Supplier<ItemStack>> m = new LinkedHashMap<>();
        m.put("mythic_bag", () -> FaultlineItems.MythicGoodieBagItem.create(plugin));
        m.put("goodie_bags", () -> { ItemStack s = FaultlineItems.GoodieBagItem.create(plugin); s.setAmount(2); return s; });
        m.put("totem", () -> new ItemStack(Material.TOTEM_OF_UNDYING));
        m.put("enchanted_golden_apple", () -> new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
        m.put("elytra", () -> new ItemStack(Material.ELYTRA));
        m.put("netherite_template", () -> new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        m.put("heart_of_the_sea", () -> new ItemStack(Material.HEART_OF_THE_SEA));
        m.put("nether_star", () -> new ItemStack(Material.NETHER_STAR));
        m.put("shulker_shells", () -> new ItemStack(Material.SHULKER_SHELL, 2));
        m.put("trident", () -> new ItemStack(Material.TRIDENT));
        m.put("mending", () -> book(Enchantment.MENDING, 1));
        m.put("unbreaking", () -> book(Enchantment.UNBREAKING, 3));
        m.put("protection", () -> book(Enchantment.PROTECTION, 4));
        m.put("sharpness", () -> book(Enchantment.SHARPNESS, 5));
        m.put("efficiency", () -> book(Enchantment.EFFICIENCY, 5));
        m.put("fortune", () -> book(Enchantment.FORTUNE, 3));
        m.put("looting", () -> book(Enchantment.LOOTING, 3));
        m.put("sniffer_egg", () -> new ItemStack(Material.SNIFFER_EGG));
        m.put("silence_trim", () -> new ItemStack(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE));
        m.put("eye_trim", () -> new ItemStack(Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE));
        m.put("spire_trim", () -> new ItemStack(Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE));
        if (plugin.getKrakenGear() != null) m.put("kraken_worm", () -> plugin.getKrakenGear().worm());
        m.put("wither_skull", () -> new ItemStack(Material.WITHER_SKELETON_SKULL));
        m.put("pigstep", () -> new ItemStack(Material.MUSIC_DISC_PIGSTEP));
        m.put("ankh", () -> FaultlineItems.ImmunityCharms.ankhPiece(plugin));
        m.put("cross", () -> FaultlineItems.ImmunityCharms.cross(plugin));
        m.put("golden_carrots", () -> new ItemStack(Material.GOLDEN_CARROT, 32));
        m.put("experience_bottles", () -> new ItemStack(Material.EXPERIENCE_BOTTLE, 32));
        m.put("netherite_scrap", () -> new ItemStack(Material.NETHERITE_SCRAP, 2));
        return m;
    }

    List<Ware> wares() {
        List<String> lines = plugin.getConfig().isList("caravan.stock") ? plugin.getConfig().getStringList("caravan.stock") : DEFAULT_STOCK;
        List<Ware> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split(":");
            try {
                out.add(new Ware(f[0].trim(), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                        f.length > 4 ? Integer.parseInt(f[4].trim()) : 1, f.length > 5 ? Integer.parseInt(f[5].trim()) : 1));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[Caravan] skipping a bad stock line: " + line + " (use id:emeralds:diamonds:netherite:weight:uses)");
            }
        }
        return out;
    }

    /** A random selection of his wares (weighted, no repeats). A trade takes at most two kinds of payment. */
    List<MerchantRecipe> stock() {
        Map<String, Supplier<ItemStack>> items = items();
        List<Ware> pool = new ArrayList<>();
        for (Ware w : wares()) {
            if (items.containsKey(w.id())) pool.add(w);
            else plugin.getLogger().warning("[Caravan] unknown item in stock: " + w.id());
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        int offers = (int) cfg("offers", 8);
        while (recipes.size() < offers && !pool.isEmpty()) {
            int total = 0;
            for (Ware w : pool) total += Math.max(1, w.weight());
            int roll = random.nextInt(total);
            Ware pick = pool.get(pool.size() - 1);
            for (Ware w : pool) { roll -= Math.max(1, w.weight()); if (roll < 0) { pick = w; break; } }
            pool.remove(pick);
            MerchantRecipe r = new MerchantRecipe(items.get(pick.id()).get(), Math.max(1, pick.uses()));
            List<ItemStack> pay = new ArrayList<>();
            if (pick.netherite() > 0) pay.add(new ItemStack(Material.NETHERITE_INGOT, Math.min(64, pick.netherite())));
            if (pick.diamonds() > 0) pay.add(new ItemStack(Material.DIAMOND, Math.min(64, pick.diamonds())));
            if (pick.emeralds() > 0) pay.add(new ItemStack(Material.EMERALD, Math.min(64, pick.emeralds())));
            if (pay.isEmpty()) pay.add(new ItemStack(Material.EMERALD, 1));
            for (ItemStack p : pay.subList(0, Math.min(2, pay.size()))) r.addIngredient(p);
            r.setExperienceReward(false);
            r.setIgnoreDiscounts(true);
            recipes.add(r);
        }
        return recipes;
    }

    // ===================================================================== events

    private boolean ours(Entity e) { return e != null && e.getScoreboardTags().contains(TAG); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(PlayerInteractEntityEvent e) {
        if (!ours(e.getRightClicked())) return;
        if (!(e.getRightClicked() instanceof WanderingTrader)) { e.setCancelled(true); return; } // no riding the camel / llamas
        Player p = e.getPlayer();
        if (introduced.add(p.getUniqueId()) && Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + p.getName() + " caravan_merchant");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnimalClick(PlayerInteractEntityEvent e) {
        if (ours(e.getRightClicked()) && !(e.getRightClicked() instanceof WanderingTrader)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (!ours(e.getVillager())) return;
        Player p = e.getPlayer();
        p.playSound(p.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 0.8f, 1.1f);
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " caravan_trades 1");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent e) {
        if (ours(e.getEntity()) && camp != null && camp.valid() && e.getEntity() instanceof LivingEntity le) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (le.isValid() && camp != null && camp.valid()) le.setLeashHolder(camp.merchant); });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    void shutdown() { leave(false); }

    // ===================================================================== /caravan

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        if (sub.isEmpty() || !sender.hasPermission("items.caravan")) {
            if (camp != null) {
                long m = Math.max(0, (camp.leavesAt - System.currentTimeMillis()) / 60000);
                sender.sendMessage(ChatColor.GOLD + "The Caravan is at " + ChatColor.WHITE + "X " + camp.at.getBlockX() + ", Z " + camp.at.getBlockZ()
                        + ChatColor.GOLD + " for about " + ChatColor.WHITE + (m + 1) + " more minutes" + ChatColor.GOLD + ".");
            } else {
                long m = Math.max(0, (nextAt - System.currentTimeMillis()) / 60000);
                sender.sendMessage(ChatColor.GOLD + "The Caravan comes back in about " + ChatColor.WHITE + (m + 1) + " minutes" + ChatColor.GOLD + ".");
            }
            return true;
        }
        switch (sub) {
            case "now" -> {
                if (camp != null) { sender.sendMessage(ChatColor.RED + "The Caravan is already here (/caravan stop)."); return true; }
                nextAt = System.currentTimeMillis() + (long) (cfg("every-minutes", 180) * 60000);
                sender.sendMessage(arrive(null) ? ChatColor.GREEN + "The Caravan is on its way." : ChatColor.RED + "No survival player in the Overworld with room around them.");
            }
            case "here" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (camp != null) { sender.sendMessage(ChatColor.RED + "The Caravan is already here (/caravan stop)."); return true; }
                sender.sendMessage(arrive(p) ? ChatColor.GREEN + "The Caravan set up next to you." : ChatColor.RED + "No room around you.");
            }
            case "stop" -> { leave(true); sender.sendMessage(ChatColor.GREEN + "The Caravan left."); }
            case "reroll" -> {
                if (camp != null && camp.valid()) { camp.merchant.setRecipes(stock()); sender.sendMessage(ChatColor.GREEN + "New wares."); }
                else sender.sendMessage(ChatColor.GRAY + "The Caravan isn't here.");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/caravan [now|here|stop|reroll]");
        }
        return true;
    }

    Camp camp() { return camp; }
}
