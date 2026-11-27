package net.faultlinesmp.items;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 2.0 THE FARLANDS, deeper (config {@code outer-rim:}; everything skips players a lit Rim Lighthouse covers: FaultlineWorld's
 * {@code faultline_rim_safe} metadata):
 * <ul>
 *   <li>RIM DEPTH: a boss bar in the Farlands, 0% at the Farlands' edge (3280 from 0,0) to 100% at the border (10000). Monsters
 *       spawning naturally get +60% health / +40% damage at 100% (`depth.health`, `depth.damage`, scaled by depth).</li>
 *   <li>STATIC STORMS: every `storm.check-minutes` 8, `storm.chance` 0.15 with someone out there: `storm.minutes` 2 of static
 *       (nausea pulses, flicks of darkness, compasses spinning, ash, a crackle; the Rim Chart won't read).</li>
 *   <li>FAKE PLAYERS: past 30% depth, a minute's roll `fakes.chance` 0.08: footsteps behind you, a whisper from someone who isn't
 *       there, a "joined the game" only you see (and its "left" a few seconds later), a chest opening nearby.</li>
 *   <li>GRAVITY ANOMALIES: fixed zones (cells of 160, 25%, radius 14-30) where you float (Slow Falling + Jump Boost II) or are
 *       heavy (jump x0.3, Slowness); motes show the edge.</li>
 *   <li>RIM SHARDS (PRISMARINE_SHARD + glint, PDC {@code faultlineitems:rim_shard}): the Farlands' own money. Monsters killed
 *       there 15% (Elites 35%, Captains always 5-8, Echoes 3), every Farlands chest 2-6, Rim Sieges. Spent at the Rim Trader and
 *       the Lost Villagers (cursed gear).</li>
 *   <li>THE RIM CHART (PAPER, vanilla filled-map look, PDC {@code faultlineitems:rim_chart}; a map + 4 Rim Shards): right-click =
 *       vaults, camps, villages, lighthouses and the Citadel within 400 (FaultlineWorld's {@code FarApi} by reflection), and a
 *       trail of light to the nearest vault for 10 s.</li>
 *   <li>THE RIM SIEGE: every 10 min `siege.chance` 0.06 per player out there (60 min each, or `/farlands siege [player]`): hold
 *       the ground round where you stood through 4 waves (+2 a wave per extra player). Win = Rim Shards, a Goodie Bag, 20% a Rim
 *       Sigil, stat `rim_sieges`. Everyone 30+ away for 30 s = lost.</li>
 * </ul>
 */
final class OuterRim implements Listener {
    static final String SIEGE = "faultline_rim_siege";
    private static final double FAR0 = 3280, FAR1 = 10000;

    private final FaultlineItems plugin;
    final NamespacedKey shardKey, chartKey;
    private final NamespacedKey hpKey, dmgKey, heavyKey;
    private final Random rnd = new Random();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> fakeReady = new HashMap<>(), chartReady = new HashMap<>(), siegeReady = new HashMap<>();
    private final Set<UUID> heavy = new HashSet<>(), stormed = new HashSet<>();
    private long ticks, stormUntil, nextStormCheck;
    private Siege siege;

    OuterRim(FaultlineItems plugin) {
        this.plugin = plugin;
        shardKey = new NamespacedKey(plugin, "rim_shard");
        chartKey = new NamespacedKey(plugin, "rim_chart");
        hpKey = new NamespacedKey(plugin, "rim_depth_hp");
        dmgKey = new NamespacedKey(plugin, "rim_depth_dmg");
        heavyKey = new NamespacedKey(plugin, "rim_heavy");
        try {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(plugin, "rim_chart"), chart());
            r.shape(" S ", "SMS", " S ");
            r.setIngredient('S', new RecipeChoice.ExactChoice(shard(1)));
            r.setIngredient('M', Material.MAP);
            if (Bukkit.getRecipe(r.getKey()) == null) Bukkit.addRecipe(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("Rim Chart recipe: " + e.getMessage()); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 20);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("outer-rim." + path, def); }
    private boolean on() { return plugin.getConfig().getBoolean("outer-rim.enabled", true); }

    static double depth(Location l) {
        double d = Math.hypot(l.getX(), l.getZ());
        return Math.max(0, Math.min(1, (d - FAR0) / (FAR1 - FAR0)));
    }

    static boolean safe(Player p) { return p.hasMetadata("faultline_rim_safe"); }

    // ================================================================ items

    ItemStack shard(int n) {
        ItemStack it = new ItemStack(Material.PRISMARINE_SHARD, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Rim Shard");
        m.setLore(List.of(ChatColor.GRAY + "A sliver of the world's edge.", ChatColor.DARK_GRAY + "Spend it at the Rim Trader and the Lost Villagers."));
        m.addEnchant(Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(shardKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack chart() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Rim Chart");
        m.setLore(List.of(ChatColor.GREEN + "Right-click in the Farlands: the vaults, camps,",
                ChatColor.GREEN + "villages, lighthouses and the Citadel near you,",
                ChatColor.GREEN + "and a trail of light to the nearest vault.",
                ChatColor.DARK_GRAY + "Useless in a static storm."));
        m.setItemModel(NamespacedKey.minecraft("filled_map"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(chartKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isShard(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(shardKey, PersistentDataType.BYTE); }
    boolean isChart(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(chartKey, PersistentDataType.BYTE); }

    /** Extra Rim Trader offers, paid in shards (Farlands.recipes adds these). */
    List<MerchantRecipe> traderOffers() {
        List<MerchantRecipe> out = new ArrayList<>();
        out.add(Farlands.offer(chart(), 3, shard(6)));
        out.add(Farlands.offer(plugin.farlandsNight.sigil(1), 2, shard(16)));
        out.add(Farlands.offer(FaultlineItems.MythicGoodieBagItem.create(plugin), 2, shard(24)));
        out.add(Farlands.offer(new ItemStack(Material.NETHERITE_SCRAP, 2), 4, shard(10)));
        return out;
    }

    /** The Lost Villagers' cursed gear: strong, but cursed (vanishing / binding), paid in shards. */
    List<MerchantRecipe> villagerOffers() {
        List<MerchantRecipe> out = new ArrayList<>();
        out.add(Farlands.offer(cursed(Material.DIAMOND_SWORD, Enchantment.VANISHING_CURSE, Enchantment.SHARPNESS, 6, Enchantment.LOOTING, 3), 2, shard(14)));
        out.add(Farlands.offer(cursed(Material.DIAMOND_CHESTPLATE, Enchantment.BINDING_CURSE, Enchantment.PROTECTION, 5, Enchantment.UNBREAKING, 3), 2, shard(16)));
        out.add(Farlands.offer(cursed(Material.BOW, Enchantment.VANISHING_CURSE, Enchantment.POWER, 6, Enchantment.PUNCH, 2), 2, shard(10)));
        out.add(Farlands.offer(cursed(Material.DIAMOND_BOOTS, Enchantment.VANISHING_CURSE, Enchantment.FEATHER_FALLING, 5, Enchantment.DEPTH_STRIDER, 3), 2, shard(8)));
        out.add(Farlands.offer(cursed(Material.DIAMOND_PICKAXE, Enchantment.VANISHING_CURSE, Enchantment.EFFICIENCY, 6, Enchantment.FORTUNE, 3), 2, shard(14)));
        out.add(Farlands.offer(new ItemStack(Material.GOLDEN_APPLE, 3), 4, shard(4)));
        return out;
    }

    private static ItemStack cursed(Material m, Enchantment curse, Enchantment a, int al, Enchantment b, int bl) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.addEnchant(a, al, true);
        meta.addEnchant(b, bl, true);
        meta.addEnchant(curse, 1, true);
        meta.setLore(List.of(ChatColor.DARK_PURPLE + "" + ChatColor.ITALIC + "From the edge of the world. Cursed."));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillager(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v) || !v.getScoreboardTags().contains("faultline_lost_villager")) return;
        // their own cursed-gear offers, once (they're clerics now so vanilla lets you trade; vanilla's own offers are replaced)
        org.bukkit.NamespacedKey k = new org.bukkit.NamespacedKey(plugin, "rim_offers");
        if (!v.getPersistentDataContainer().has(k, org.bukkit.persistence.PersistentDataType.BYTE)) {
            v.setRecipes(villagerOffers());
            v.getPersistentDataContainer().set(k, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
    }

    // ================================================================ the tick

    private void tick() {
        ticks++;
        if (!on()) return;
        long now = System.currentTimeMillis();
        boolean storm = now < stormUntil;
        if (now >= nextStormCheck) {
            nextStormCheck = now + (long) (cfg("storm.check-minutes", 8) * 60000);
            if (!storm && !out().isEmpty() && rnd.nextDouble() < cfg("storm.chance", 0.15)) {
                stormUntil = now + (long) (cfg("storm.minutes", 2) * 60000);
                for (Player p : out()) if (!safe(p)) { p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f); p.sendActionBar(ChatColor.GRAY + "" + ChatColor.MAGIC + "xx" + ChatColor.GRAY + " static " + ChatColor.MAGIC + "xx"); }
            }
        }
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Farlands.survival(p) || !plugin.farlands.inCached(p)) continue;
            seen.add(p.getUniqueId());
            boolean safe = safe(p);
            double d = depth(p.getLocation());
            bar(p, d, safe, storm);
            if (safe) { unheavy(p); continue; }
            if (storm) { staticTick(p); stormed.add(p.getUniqueId()); }
            gravity(p);
            if (d > cfg("fakes.min-depth", 0.3) && ticks % 60 == 0 && now >= fakeReady.getOrDefault(p.getUniqueId(), 0L) && rnd.nextDouble() < cfg("fakes.chance", 0.08)) {
                fakeReady.put(p.getUniqueId(), now + 4 * 60000L);
                fake(p);
            }
            if (siege == null && d > 0.15 && ticks % 600 == 0 && now >= siegeReady.getOrDefault(p.getUniqueId(), 0L) && rnd.nextDouble() < cfg("siege.chance", 0.06)) {
                siegeReady.put(p.getUniqueId(), now + (long) (cfg("siege.cooldown-minutes", 60) * 60000));
                startSiege(p);
            }
        }
        for (Iterator<Map.Entry<UUID, BossBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!seen.contains(e.getKey())) { e.getValue().removeAll(); it.remove(); Player p = Bukkit.getPlayer(e.getKey()); if (p != null) unheavy(p); }
        }
        if (!storm && !stormed.isEmpty()) {   // the storm's over (or they left it): compasses point home again
            for (UUID id : stormed) { Player p = Bukkit.getPlayer(id); if (p != null) p.setCompassTarget(p.getWorld().getSpawnLocation()); }
            stormed.clear();
        }
        if (siege != null) {
            try { siege.tick(); }
            catch (RuntimeException e) { plugin.getLogger().warning("[OuterRim] siege: " + e); siege.end(false); }
        }
    }

    /** Rim Shards only go into the Rim Chart (they're prismarine shards underneath: no prismarine / sea lanterns from them). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        boolean shard = false;
        for (ItemStack it : e.getInventory().getMatrix()) if (isShard(it)) shard = true;
        if (!shard) return;
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(new NamespacedKey(plugin, "rim_chart"))) return;
        e.getInventory().setResult(null);
    }

    List<Player> out() { return plugin.farlands.playersOut(); }

    private void bar(Player p, double d, boolean safe, boolean storm) {
        if (!plugin.getConfig().getBoolean("outer-rim.depth.bar", true)) return;
        BossBar b = bars.computeIfAbsent(p.getUniqueId(), k -> {
            BossBar nb = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10);
            nb.addPlayer(p);
            return nb;
        });
        int pct = (int) Math.round(d * 100);
        b.setTitle(safe ? ChatColor.GOLD + "Rim Depth " + pct + "% " + ChatColor.GRAY + "(a lighthouse keeps it away)"
                : storm ? ChatColor.GRAY + "Rim Depth " + ChatColor.MAGIC + "00" + ChatColor.GRAY + "% (static)" : ChatColor.LIGHT_PURPLE + "Rim Depth " + pct + "%");
        b.setColor(safe ? BarColor.YELLOW : d > 0.66 ? BarColor.RED : BarColor.PURPLE);
        b.setProgress(Math.max(0, Math.min(1, d)));
    }

    // ================================================================ depth: stronger monsters

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on() || !(e.getEntity() instanceof Enemy) || e.getEntity() instanceof Player) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.PATROL) return;
        LivingEntity m = e.getEntity();
        if (!Farlands.in(m.getLocation())) return;
        double d = depth(m.getLocation());
        if (d <= 0.01) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!m.isValid()) return;
            AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null && hp.getBaseValue() <= 200) {
                hp.addModifier(new AttributeModifier(hpKey, cfg("depth.health", 0.6) * d, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
                m.setHealth(Math.min(hp.getValue(), 1024));
            }
            AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
            if (dmg != null) dmg.addModifier(new AttributeModifier(dmgKey, cfg("depth.damage", 0.4) * d, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        });
    }

    // ================================================================ static storms

    private void staticTick(Player p) {
        double r = rnd.nextDouble();
        if (r < 0.15) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, true, false, false));
        else if (r < 0.25) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, true, false, false));
        Location l = p.getLocation();
        p.setCompassTarget(l.clone().add(rnd.nextGaussian() * 500, 0, rnd.nextGaussian() * 500));   // compasses spin
        p.spawnParticle(Particle.WHITE_ASH, l.clone().add(0, 1, 0), 40, 3, 2, 3, 0.02);
        if (rnd.nextDouble() < 0.4) p.playSound(l, "minecraft:block.respawn_anchor.ambient", SoundCategory.AMBIENT, 0.6f, 0.5f + rnd.nextFloat());
    }

    boolean storming() { return System.currentTimeMillis() < stormUntil; }

    // ================================================================ fake players

    private void fake(Player p) {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) if (o.getName() != null && !o.getUniqueId().equals(p.getUniqueId()) && !o.isOnline()) names.add(o.getName());
        String name = names.isEmpty() ? "Herobrine" : names.get(rnd.nextInt(names.size()));
        Location l = p.getLocation();
        switch (rnd.nextInt(4)) {
            case 0 -> {   // footsteps behind you
                Vector back = l.getDirection().setY(0).normalize().multiply(-5);
                Location at = l.clone().add(back);
                for (int i = 0; i < 5; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.playSound(at, Sound.BLOCK_STONE_STEP, SoundCategory.PLAYERS, 0.9f, 0.9f); }, i * 6L);
            }
            case 1 -> {
                String[] lines = {"behind you", "you shouldn't be this far out", "wait for me", "did you hear that?", "it's following us", "don't go further", "who's there?"};
                p.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + name + " whispers to you: " + lines[rnd.nextInt(lines.length)]);
            }
            case 2 -> {
                p.sendMessage(ChatColor.YELLOW + name + " joined the game");
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.sendMessage(ChatColor.YELLOW + name + " left the game"); }, 120 + rnd.nextInt(200));
            }
            default -> p.playSound(l.clone().add(rnd.nextGaussian() * 6, 0, rnd.nextGaussian() * 6), Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.8f, 0.9f);
        }
    }

    // ================================================================ gravity anomalies

    /** The anomaly at (x, z): {cx, cz, radius, type 0 low / 1 heavy} or null. Fixed per world seed. */
    static int[] anomaly(long seed, double x, double z) {
        int cell = 160;
        int ci = Math.floorDiv((int) x, cell), cj = Math.floorDiv((int) z, cell);
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            long h = mix(seed, ci + a, cj + b);
            if ((h & 255) > 64) continue;
            int cx = (ci + a) * cell + 30 + (int) Math.floorMod(h >>> 8, (long) (cell - 60)), cz = (cj + b) * cell + 30 + (int) Math.floorMod(h >>> 20, (long) (cell - 60));
            int r = 14 + (int) Math.floorMod(h >>> 32, 17L);
            if (Math.hypot(cx - x, cz - z) <= r) return new int[]{cx, cz, r, (int) (h >>> 50 & 1)};
        }
        return null;
    }

    private static long mix(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x6A11;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }

    private void gravity(Player p) {
        Location l = p.getLocation();
        int[] a = anomaly(p.getWorld().getSeed(), l.getX(), l.getZ());
        if (a == null) { unheavy(p); return; }
        if (a[3] == 0) {
            unheavy(p);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 40, 1, true, false, true));
            p.spawnParticle(Particle.END_ROD, l.clone().add(0, 0.5, 0), 6, 2, 1, 2, 0.01);
        } else {
            AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null && heavy.add(p.getUniqueId())) j.addModifier(new AttributeModifier(heavyKey, -0.7, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
            p.spawnParticle(Particle.FALLING_OBSIDIAN_TEAR, l.clone().add(0, 2.5, 0), 6, 2, 0.5, 2, 0);
        }
        if (ticks % 4 == 0) p.sendActionBar(a[3] == 0 ? ChatColor.AQUA + "You feel light." : ChatColor.DARK_GRAY + "You feel heavy.");
    }

    void unheavy(Player p) {
        if (!heavy.remove(p.getUniqueId())) return;
        AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (j != null) for (AttributeModifier m : new ArrayList<>(j.getModifiers())) if (m.getKey().equals(heavyKey)) j.removeModifier(m);
    }

    // ================================================================ shards from kills and chests

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        if (!on() || !(m instanceof Enemy) || m.getKiller() == null || !Farlands.in(m.getLocation())) return;
        Set<String> t = m.getScoreboardTags();
        int n = 0;
        if (t.contains("faultline_elite_captain")) n = 5 + rnd.nextInt(4);
        else if (t.contains(Farlands.ECHO)) n = 3;
        else if (t.contains("faultline_elite") || t.contains("faultline_far_elite")) { if (rnd.nextDouble() < cfg("shards.elite-chance", 0.35)) n = 1 + rnd.nextInt(3); }
        else if (rnd.nextDouble() < cfg("shards.chance", 0.15)) n = 1 + rnd.nextInt(2);
        if (n > 0) e.getDrops().add(shard(n));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        NamespacedKey k = e.getLootTable().getKey();
        if (!"faultline".equals(k.getNamespace()) || !k.getKey().startsWith("chests/farlands_")) return;
        e.getLoot().add(shard(2 + rnd.nextInt(5)));
    }

    // ================================================================ the Rim Chart

    @EventHandler(priority = EventPriority.HIGH)
    public void onChart(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (!isChart(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now < chartReady.getOrDefault(p.getUniqueId(), 0L)) return;
        chartReady.put(p.getUniqueId(), now + 5000);
        if (!Farlands.in(p.getLocation())) { p.sendMessage(ChatColor.GRAY + "The chart only shows the Farlands."); return; }
        if (storming()) { p.sendMessage(ChatColor.GRAY + "The chart's lines swim. It won't read in the static."); return; }
        List<String> found = farApi(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), (int) cfg("chart.range", 400));
        if (found == null) { p.sendMessage(ChatColor.RED + "The chart is blank (FaultlineWorld isn't running)."); return; }
        Location l = p.getLocation();
        found.sort(Comparator.comparingDouble(s -> { String[] a = s.split(";"); return Math.hypot(Integer.parseInt(a[1]) - l.getX(), Integer.parseInt(a[3]) - l.getZ()); }));
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Rim Chart" + ChatColor.GRAY + " (" + found.size() + " within " + (int) cfg("chart.range", 400) + ")");
        int shown = 0;
        String[] vault = null;
        for (String s : found) {
            String[] a = s.split(";");
            if (vault == null && a[0].equals("vault")) vault = a;
            if (shown++ >= 8) continue;
            int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
            p.sendMessage(ChatColor.YELLOW + " " + name(a[0]) + ChatColor.GRAY + ": " + away(l, x, z) + ChatColor.DARK_GRAY + " (" + x + ", " + y + ", " + z + ")");
        }
        if (vault != null) trail(p, new Location(l.getWorld(), Integer.parseInt(vault[1]) + 0.5, Integer.parseInt(vault[2]), Integer.parseInt(vault[3]) + 0.5));
        p.playSound(l, Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
    }

    /** "120 blocks north-east". */
    static String away(Location from, int x, int z) {
        double dx = x + 0.5 - from.getX(), dz = z + 0.5 - from.getZ();
        int d = (int) Math.round(Math.hypot(dx, dz));
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int i = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8L);
        return d + " blocks " + dirs[i];
    }

    private static String name(String kind) {
        return switch (kind) {
            case "vault" -> "Wall vault"; case "camp" -> "Explorers' camp"; case "village" -> "Lost village";
            case "lighthouse" -> "Rim lighthouse"; case "citadel" -> "THE RIM CITADEL"; default -> kind;
        };
    }

    private void trail(Player p, Location to) {
        for (int k = 0; k < 10; k++) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.getWorld() != to.getWorld()) return;
            Location from = p.getEyeLocation().subtract(0, 0.4, 0);
            Vector dir = to.toVector().subtract(from.toVector());
            double len = Math.min(24, dir.length());
            if (len < 1) return;
            dir.normalize();
            for (double t = 2; t < len; t += 1.2) p.spawnParticle(Particle.END_ROD, from.clone().add(dir.clone().multiply(t)), 1, 0, 0, 0, 0);
        }, k * 20L);
    }

    private java.lang.reflect.Method farApi;

    @SuppressWarnings("unchecked")
    List<String> farApi(World w, int x, int z, int r) {
        try {
            if (farApi == null) {
                org.bukkit.plugin.Plugin fw = Bukkit.getPluginManager().getPlugin("FaultlineWorld");
                if (fw == null) return null;
                farApi = Class.forName("net.faultlinesmp.world.FarApi", true, fw.getClass().getClassLoader()).getMethod("near", World.class, int.class, int.class, int.class);
            }
            return new ArrayList<>((List<String>) farApi.invoke(null, w, x, z, r));
        } catch (Throwable t) { farApi = null; return null; }
    }

    // ================================================================ the Rim Siege

    final class Siege {
        final Location centre;
        final BossBar bar = Bukkit.createBossBar(ChatColor.DARK_RED + "Rim Siege", BarColor.RED, BarStyle.SEGMENTED_6);
        final Set<UUID> mobs = new HashSet<>();
        int wave, waves = 4, away, secs;

        Siege(Location centre) { this.centre = centre; }

        List<Player> defenders() {
            List<Player> out = new ArrayList<>();
            for (Player p : centre.getWorld().getPlayers()) if (Farlands.survival(p) && !p.isDead() && p.getLocation().distanceSquared(centre) < 30 * 30) out.add(p);
            return out;
        }

        void tick() {
            List<Player> def = defenders();
            for (Player p : centre.getWorld().getPlayers()) {
                boolean near = p.getLocation().distanceSquared(centre) < 60 * 60;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
            for (int i = 0; i < 24; i++) {   // the ring to hold
                double a = Math.PI * 2 * i / 24;
                centre.getWorld().spawnParticle(Particle.DUST, centre.clone().add(Math.cos(a) * 20, 0.3, Math.sin(a) * 20), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(170, 30, 60), 1.6f));
            }
            if (def.isEmpty()) { if (++away >= 30) { end(false); return; } } else away = 0;
            if (++secs < 5) return;   // 5 s to get ready
            mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
            if (mobs.isEmpty()) {
                if (wave >= waves) { end(true); return; }
                wave++;
                int n = 4 + 2 * wave + 2 * Math.max(0, def.size() - 1);
                for (int i = 0; i < n; i++) spawnOne();
                for (Player p : def) p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 2f, 0.7f);
            }
            bar.setTitle(ChatColor.DARK_RED + "Rim Siege " + ChatColor.GRAY + "- wave " + wave + "/" + waves + " (" + mobs.size() + " left)");
            bar.setProgress(Math.max(0, Math.min(1, 1 - (wave - 1 + (mobs.isEmpty() ? 1 : 0)) / (double) waves)));
        }

        void spawnOne() {
            World w = centre.getWorld();
            for (int k = 0; k < 12; k++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 18 + rnd.nextDouble() * 8;
                int x = centre.getBlockX() + (int) (Math.cos(a) * d), z = centre.getBlockZ() + (int) (Math.sin(a) * d);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int dy = 6; dy >= -6; dy--) {
                    Block f = w.getBlockAt(x, centre.getBlockY() + dy - 1, z);
                    if (!f.getType().isSolid() || f.getRelative(0, 1, 0).getType().isSolid() || f.getRelative(0, 2, 0).getType().isSolid()
                            || f.getRelative(0, 1, 0).isLiquid()) continue;
                    EntityType[] types = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK, EntityType.STRAY, EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.SPIDER};
                    Location at = f.getLocation().add(0.5, 1, 0.5);
                    LivingEntity m = (LivingEntity) w.spawnEntity(at, types[rnd.nextInt(types.length)], CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(SIEGE));
                    AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
                    if (hp != null) { hp.setBaseValue(hp.getBaseValue() * (1.5 + 0.15 * wave)); m.setHealth(hp.getValue()); }
                    AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
                    if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * 1.4);
                    if (m instanceof Mob mob) {
                        Player target = null; double best = Double.MAX_VALUE;
                        for (Player p : defenders()) { double dd = p.getLocation().distanceSquared(at); if (dd < best) { best = dd; target = p; } }
                        if (target != null) mob.setTarget(target);
                        mob.setRemoveWhenFarAway(false);
                    }
                    m.setPersistent(false);
                    mobs.add(m.getUniqueId());
                    w.spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
                    return;
                }
            }
        }

        void end(boolean won) {
            bar.removeAll();
            for (UUID id : mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
            mobs.clear();
            for (Player p : centre.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(centre) > 50 * 50) continue;
                if (!won) { p.sendTitle(ChatColor.DARK_RED + "The Rim took the ground", "", 10, 40, 10); continue; }
                p.sendTitle(ChatColor.GOLD + "Siege held", ChatColor.GRAY + "The Rim pulls back.", 10, 50, 15);
                give(p, shard(8 + rnd.nextInt(8)));
                give(p, FaultlineItems.GoodieBagItem.create(plugin));
                if (rnd.nextDouble() < cfg("siege.sigil-chance", 0.2)) give(p, plugin.farlandsNight.sigil(1));
                p.giveExp(150);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " rim_sieges 1");
            }
            siege = null;
        }
    }

    private static void give(Player p, ItemStack it) { for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left); }

    boolean startSiege(Player p) {
        if (siege != null) return false;
        siege = new Siege(p.getLocation().clone());
        for (Player q : p.getWorld().getPlayers()) if (q.getLocation().distanceSquared(p.getLocation()) < 60 * 60) {
            q.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "RIM SIEGE", ChatColor.GRAY + "Hold the ring. They're coming.", 10, 60, 15);
            q.playSound(q.getLocation(), Sound.EVENT_RAID_HORN, 2f, 0.5f);
        }
        return true;
    }

    boolean command(CommandSender s, String[] a) {
        if (a.length == 0 || !a[0].equalsIgnoreCase("siege") && !a[0].equalsIgnoreCase("storm") && !a[0].equalsIgnoreCase("shards") && !a[0].equalsIgnoreCase("chart") && !a[0].equalsIgnoreCase("fake")) return false;
        String sub = a[0].toLowerCase(Locale.ROOT);
        Player t = a.length > 1 && !sub.equals("shards") ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
        switch (sub) {
            case "siege" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("stop")) { if (siege != null) siege.end(false); s.sendMessage("Siege stopped."); return true; }
                if (t == null) { s.sendMessage("Who?"); return true; }
                s.sendMessage(startSiege(t) ? "Rim Siege started at " + t.getName() + "." : "A siege is already on.");
            }
            case "storm" -> { stormUntil = System.currentTimeMillis() + (long) (cfg("storm.minutes", 2) * 60000); s.sendMessage("Static storm started."); }
            case "fake" -> { if (t != null) fake(t); }
            case "chart" -> { if (t != null) give(t, chart()); }
            case "shards" -> {
                int n = a.length > 1 ? Integer.parseInt(a[1]) : 16;
                Player to = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (to != null) give(to, shard(n));
            }
            default -> { }
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BossBar b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) b.removeAll();
        unheavy(e.getPlayer());
    }

    void shutdown() {
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
        for (Player p : Bukkit.getOnlinePlayers()) unheavy(p);
        if (siege != null) siege.end(false);
    }
}
