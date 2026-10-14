package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * TRINKETS (Items 1.2.28, config trinkets:): nine more accessories, worn in /accessories.
 *  - Bloodthirst Fang (8% Ravagers, 1% Vindicators): every kill heals you 3 hearts.
 *  - Ironhide Pauldron (craft: iron blocks, netherite scrap, leather): 15% knockback resistance.
 *  - Gladiator's Crest (40% from Diamond Jacob, each fighter): +1 armor per other player within 10 blocks (max +4).
 *  - Guardian's Oath (craft: iron, gold and a shield): kingdom-mates within 8 blocks of you take 1% less damage.
 *  - Miner's Lantern Charm (craft: lanterns, glowstone dust, a diamond): mining stone makes the ores within 4 blocks glow for
 *    you for 3 s (Java: glowing block outlines only you see; Bedrock: particles). The Watch knows (metadata
 *    faultline_ore_sense): its x-ray alerts become log lines for wearers.
 *  - Green Thumb Ring (craft: wheat, bone blocks, gold): crops within 8 blocks of you grow 15% faster.
 *  - Treasure Sense (3% from Drowned): buried treasure chests and your Pirate Treasure Maps' X within 30 blocks show
 *    in the action bar with a ping.
 *  - Smelter's Glove (craft: leather, iron, a blast furnace): 20% of raw iron / gold / copper you mine drops smelted.
 *  - Featherfall Charm (craft: feathers, slime ball, gold): no fall damage from 5 blocks or less.
 */
final class TrinketGear implements Listener {

    enum Piece {
        FANG("bloodthirst_fang", "fang", Material.FLINT, ChatColor.DARK_RED, "Bloodthirst Fang", "Every kill heals you", "3 hearts."),
        PAULDRON("ironhide_pauldron", "pauldron", Material.IRON_NUGGET, ChatColor.GRAY, "Ironhide Pauldron", "15% less knockback."),
        CREST("gladiators_crest", "crest", Material.GOLD_NUGGET, ChatColor.GOLD, "Gladiator's Crest", "+1 armor for every player", "within 10 blocks (max +4)."),
        GUARDIAN("guardians_oath", "guardian", Material.NAUTILUS_SHELL, ChatColor.BLUE, "Guardian's Oath", "Kingdom-mates near you", "take 1% less damage."),
        LANTERN("miners_lantern", "lantern", Material.AMETHYST_SHARD, ChatColor.YELLOW, "Miner's Lantern Charm", "Mining stone makes ores", "within 4 blocks glow."),
        THUMB("green_thumb", "greenthumb", Material.PRISMARINE_CRYSTALS, ChatColor.GREEN, "Green Thumb Ring", "Crops near you grow", "15% faster."),
        TREASURE("treasure_sense", "treasure", Material.COMPASS, ChatColor.GOLD, "Treasure Sense", "Buried treasure and your map's X", "ping within 30 blocks."),
        SMELTER("smelters_glove", "smelter", Material.LEATHER, ChatColor.RED, "Smelter's Glove", "20% of raw iron, gold and", "copper drops smelted."),
        FEATHERFALL("featherfall_charm", "featherfall", Material.FEATHER, ChatColor.WHITE, "Featherfall Charm", "No fall damage from", "5 blocks or less.");

        final String id, command, name;
        final Material base;
        final ChatColor color;
        final String[] lore;
        Piece(String id, String command, Material base, ChatColor color, String name, String... lore) {
            this.id = id; this.command = command; this.base = base; this.color = color; this.name = name; this.lore = lore;
        }
    }

    static final Map<Material, Material> SMELT = Map.of(Material.RAW_IRON, Material.IRON_INGOT, Material.RAW_GOLD, Material.GOLD_INGOT,
            Material.RAW_COPPER, Material.COPPER_INGOT);
    static final Set<String> STONE = Set.of("STONE", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE", "NETHERRACK", "BASALT", "BLACKSTONE", "CALCITE");

    private final FaultlineItems plugin;
    private final Random random = new Random();
    private final Map<Piece, NamespacedKey> keys = new EnumMap<>(Piece.class);
    final NamespacedKey pauldronKey, crestKey;
    /** Online wearers' locations, refreshed every second (crop growth and the guardian aura look here). */
    final Map<UUID, Location> thumbs = new HashMap<>(), guardians = new HashMap<>();
    private final Map<UUID, Long> lanternReady = new HashMap<>();
    private final Map<UUID, Long> treasurePing = new HashMap<>();

    TrinketGear(FaultlineItems plugin) {
        this.plugin = plugin;
        for (Piece p : Piece.values()) keys.put(p, new NamespacedKey(plugin, p.id));
        pauldronKey = new NamespacedKey(plugin, "ironhide_pauldron_kb");
        crestKey = new NamespacedKey(plugin, "gladiators_crest_armor");
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::treasureTick, 40L, 40L);
        recipes();
    }

    NamespacedKey key(Piece p) { return keys.get(p); }

    ItemStack item(Piece p) {
        String[] lore = new String[p.lore.length];
        for (int i = 0; i < p.lore.length; i++) lore[i] = ChatColor.GREEN + p.lore[i];
        return FaultlineItems.NewAccessoryItems.make(plugin, p.base, p.color + "" + ChatColor.BOLD + p.name, key(p), lore);
    }

    boolean wearing(Player p, Piece piece) { return plugin.getAccessoryManager().hasEquipped(p, key(piece)); }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("trinkets." + path, def); }

    // ---------------------------------------------------------------- recipes

    private void recipes() {
        try {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(plugin, "ironhide_pauldron_recipe"), item(Piece.PAULDRON));
            r.shape("INI", "ILI");
            r.setIngredient('I', Material.IRON_BLOCK); r.setIngredient('N', Material.NETHERITE_SCRAP); r.setIngredient('L', Material.LEATHER);
            Bukkit.addRecipe(r);
            r = new ShapedRecipe(new NamespacedKey(plugin, "guardians_oath_recipe"), item(Piece.GUARDIAN));
            r.shape("IGI", "GSG", "IGI");
            r.setIngredient('I', Material.IRON_INGOT); r.setIngredient('G', Material.GOLD_INGOT); r.setIngredient('S', Material.SHIELD);
            Bukkit.addRecipe(r);
            r = new ShapedRecipe(new NamespacedKey(plugin, "miners_lantern_recipe"), item(Piece.LANTERN));
            r.shape("GLG", "LDL", "GLG");
            r.setIngredient('G', Material.GLOWSTONE_DUST); r.setIngredient('L', Material.LANTERN); r.setIngredient('D', Material.DIAMOND);
            Bukkit.addRecipe(r);
            r = new ShapedRecipe(new NamespacedKey(plugin, "green_thumb_recipe"), item(Piece.THUMB));
            r.shape("WBW", "BGB", "WBW");
            r.setIngredient('W', Material.WHEAT); r.setIngredient('B', Material.BONE_BLOCK); r.setIngredient('G', Material.GOLD_INGOT);
            Bukkit.addRecipe(r);
            r = new ShapedRecipe(new NamespacedKey(plugin, "smelters_glove_recipe"), item(Piece.SMELTER));
            r.shape("LIL", "IBI", "LIL");
            r.setIngredient('L', Material.LEATHER); r.setIngredient('I', Material.IRON_INGOT); r.setIngredient('B', Material.BLAST_FURNACE);
            Bukkit.addRecipe(r);
            r = new ShapedRecipe(new NamespacedKey(plugin, "featherfall_charm_recipe"), item(Piece.FEATHERFALL));
            r.shape("FFF", "FSF", "FGF");
            r.setIngredient('F', Material.FEATHER); r.setIngredient('S', Material.SLIME_BALL); r.setIngredient('G', Material.GOLD_INGOT);
            Bukkit.addRecipe(r);
        } catch (RuntimeException e) { plugin.getLogger().warning("Trinket recipes: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- drops

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || v instanceof ArmorStand) return;
        // Bloodthirst Fang: any kill (players too)
        if (wearing(k, Piece.FANG) && !k.isDead()) {
            AttributeInstance max = k.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) k.setHealth(Math.min(max.getValue(), k.getHealth() + cfg("fang-heal", 6)));
            k.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, k.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3, 0.05);
        }
        if (v instanceof Player) return;
        try { if (v.fromMobSpawner()) return; } catch (RuntimeException ignored) { }
        switch (v.getType()) {
            case RAVAGER -> roll(e, "fang-ravager-chance", 0.08, Piece.FANG);
            case VINDICATOR -> roll(e, "fang-vindicator-chance", 0.01, Piece.FANG);
            case DROWNED -> roll(e, "treasure-drowned-chance", 0.03, Piece.TREASURE);
            default -> { }
        }
    }

    private void roll(EntityDeathEvent e, String path, double def, Piece p) {
        if (random.nextDouble() < cfg(path, def) * plugin.eventDropMultiplier()) e.getDrops().add(item(p));
    }

    /** Diamond Jacob's defeat (through the Index's boss-drop hook): the Gladiator's Crest. */
    void bossDrop(Player p, String entry) {
        if (!entry.equals("diamond_jacob") || random.nextDouble() >= cfg("crest-jacob-chance", 0.4)) return;
        ItemStack it = item(Piece.CREST);
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(ChatColor.GOLD + "Diamond Jacob dropped a " + ChatColor.BOLD + "Gladiator's Crest" + ChatColor.GOLD + "!");
    }

    // ---------------------------------------------------------------- every second: auras and modifiers

    void second() {
        thumbs.clear(); guardians.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (wearing(p, Piece.THUMB)) thumbs.put(p.getUniqueId(), p.getLocation());
            if (wearing(p, Piece.GUARDIAN)) guardians.put(p.getUniqueId(), p.getLocation());
            set(p, Attribute.KNOCKBACK_RESISTANCE, pauldronKey, wearing(p, Piece.PAULDRON) ? cfg("pauldron-knockback-resistance", 0.15) : 0);
            int near = 0;
            if (wearing(p, Piece.CREST)) {
                double r = cfg("crest-radius", 10);
                for (Player o : p.getWorld().getPlayers()) {
                    if (o != p && o.getGameMode() != GameMode.SPECTATOR && o.getLocation().distanceSquared(p.getLocation()) <= r * r) near++;
                }
                near = Math.min(near, (int) cfg("crest-max", 4));
            }
            set(p, Attribute.ARMOR, crestKey, near);
            boolean sense = wearing(p, Piece.LANTERN);
            if (sense) p.setMetadata("faultline_ore_sense", new FixedMetadataValue(plugin, true));
            else if (p.hasMetadata("faultline_ore_sense")) p.removeMetadata("faultline_ore_sense", plugin);
        }
    }

    private void set(Player p, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance in = p.getAttribute(a);
        if (in == null) return;
        AttributeModifier have = null;
        for (AttributeModifier m : in.getModifiers()) if (m.getKey().equals(key)) have = m;
        if (have != null && have.getAmount() == amount) return;
        if (have != null) in.removeModifier(have);
        if (amount != 0) in.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        set(e.getPlayer(), Attribute.KNOCKBACK_RESISTANCE, pauldronKey, 0);
        set(e.getPlayer(), Attribute.ARMOR, crestKey, 0);
    }

    void shutdown() { for (Player p : Bukkit.getOnlinePlayers()) { set(p, Attribute.KNOCKBACK_RESISTANCE, pauldronKey, 0); set(p, Attribute.ARMOR, crestKey, 0); } }

    // ---------------------------------------------------------------- Guardian's Oath

    static int kingdom(UUID id) {
        try {
            org.bukkit.plugin.Plugin k = Bukkit.getPluginManager().getPlugin("FaultlineKingdoms");
            if (k == null || !k.isEnabled()) return 0;
            return (int) k.getClass().getMethod("kingdomOf", UUID.class).invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return 0; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        // Featherfall: falls of 5 blocks or less don't hurt
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && v.getFallDistance() <= cfg("featherfall-blocks", 5) + 0.5 && wearing(v, Piece.FEATHERFALL)) {
            e.setCancelled(true);
            return;
        }
        if (guardians.isEmpty()) return;
        int mine = kingdom(v.getUniqueId());
        if (mine <= 0) return;
        double r = cfg("guardian-radius", 8);
        for (Map.Entry<UUID, Location> g : guardians.entrySet()) {
            if (g.getKey().equals(v.getUniqueId())) continue;
            Location l = g.getValue();
            if (l.getWorld() != v.getWorld() || l.distanceSquared(v.getLocation()) > r * r) continue;
            if (kingdom(g.getKey()) != mine) continue;
            e.setDamage(e.getDamage() * (1 - cfg("guardian-reduction", 0.01)));
            return; // one guardian is enough (it doesn't stack)
        }
    }

    // ---------------------------------------------------------------- Green Thumb

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (thumbs.isEmpty()) return;
        Block b = e.getBlock();
        double r = cfg("thumb-radius", 8);
        boolean near = false;
        for (Location l : thumbs.values()) if (l.getWorld() == b.getWorld() && l.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) <= r * r) { near = true; break; }
        if (!near || random.nextDouble() >= cfg("thumb-boost", 0.15)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) return;
            a.setAge(a.getAge() + 1);
            b.setBlockData(a);
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.6, 0.5), 3, 0.25, 0.2, 0.25, 0);
        });
    }

    // ---------------------------------------------------------------- Smelter's Glove

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOre(BlockDropItemEvent e) {
        if (!wearing(e.getPlayer(), Piece.SMELTER)) return;
        for (Item drop : e.getItems()) {
            ItemStack it = drop.getItemStack();
            Material to = SMELT.get(it.getType());
            if (to == null || random.nextDouble() >= cfg("smelter-chance", 0.2)) continue;
            drop.setItemStack(new ItemStack(to, it.getAmount()));
            Location l = drop.getLocation();
            l.getWorld().spawnParticle(Particle.FLAME, l, 5, 0.15, 0.15, 0.15, 0.01);
        }
    }

    // ---------------------------------------------------------------- Miner's Lantern

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!STONE.contains(e.getBlock().getType().name()) || !wearing(p, Piece.LANTERN)) return;
        long now = System.currentTimeMillis();
        if (lanternReady.getOrDefault(p.getUniqueId(), 0L) > now) return;
        lanternReady.put(p.getUniqueId(), now + (long) (cfg("lantern-cooldown-seconds", 1.5) * 1000));
        int r = (int) cfg("lantern-radius", 4);
        Block c = e.getBlock();
        List<Block> ores = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            Block b = c.getRelative(dx, dy, dz);
            String n = b.getType().name();
            if (n.endsWith("_ORE") || b.getType() == Material.ANCIENT_DEBRIS) ores.add(b);
            if (ores.size() >= 16) break;
        }
        if (ores.isEmpty()) return;
        boolean bedrock = Edition.bedrock(p);
        for (Block b : ores) {
            if (bedrock) {
                p.spawnParticle(Particle.WAX_OFF, b.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0);
                continue;
            }
            try {
                BlockDisplay d = (BlockDisplay) b.getWorld().spawnEntity(b.getLocation(), EntityType.BLOCK_DISPLAY);
                d.setBlock(b.getBlockData());
                d.setGlowing(true);
                d.setPersistent(false);
                d.addScoreboardTag("faultline_no_bedrock_fx");
                d.setVisibleByDefault(false);
                p.showEntity(plugin, d);
                Bukkit.getScheduler().runTaskLater(plugin, d::remove, (long) (cfg("lantern-seconds", 3) * 20));
            } catch (RuntimeException ignored) { }
        }
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f);
    }

    // ---------------------------------------------------------------- Treasure Sense

    void treasureTick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!wearing(p, Piece.TREASURE)) continue;
            Location best = null;
            String what = null;
            double range = cfg("treasure-range", 30), bestD = range * range;
            // the X of any Pirate Treasure Map you carry
            NamespacedKey mapKey = new NamespacedKey("faultlineships", "treasure_map");
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null || !it.hasItemMeta()) continue;
                String s = it.getItemMeta().getPersistentDataContainer().get(mapKey, PersistentDataType.STRING);
                if (s == null) continue;
                String[] a = s.split(";");
                try {
                    if (!a[0].equals(p.getWorld().getName())) continue;
                    Location x = new Location(p.getWorld(), Integer.parseInt(a[1]) + 0.5, p.getLocation().getY(), Integer.parseInt(a[2]) + 0.5);
                    double d = x.distanceSquared(p.getLocation());
                    if (d <= bestD) { bestD = d; best = x; what = "your map's X"; }
                } catch (RuntimeException ignored) { }
            }
            // a buried treasure chest still there
            Location chest = buriedChest(p.getLocation(), (int) range);
            if (chest != null && chest.distanceSquared(p.getLocation()) <= bestD) { best = chest; what = "buried treasure"; }
            if (best == null) continue;
            int dist = (int) Math.round(best.distance(p.getLocation()));
            p.sendActionBar(ChatColor.GOLD + "💰 " + what + ChatColor.GRAY + " · " + ChatColor.WHITE + dist + " blocks " + DeathSpot.away(p, new DeathSpot.Spot(best.getWorld().getName(), best.getBlockX(), best.getBlockY(), best.getBlockZ(), 0)).replaceAll("^\\d+ blocks ", ""));
            if (now - treasurePing.getOrDefault(p.getUniqueId(), 0L) > 6000) {
                treasurePing.put(p.getUniqueId(), now);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.6f + (float) Math.max(0, (30 - dist) / 30.0));
            }
        }
    }

    /** The nearest buried treasure chest within range that hasn't been dug up yet, or null. */
    Location buriedChest(Location at, int range) {
        World w = at.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return null;
        try {
            var res = w.locateNearestStructure(at, org.bukkit.generator.structure.Structure.BURIED_TREASURE, 1, false);
            if (res == null) return null;
            Location l = res.getLocation();
            int x = l.getBlockX(), z = l.getBlockZ();
            if (Math.hypot(x - at.getX(), z - at.getZ()) > range + 2) return null;
            int top = w.getHighestBlockYAt(x, z);
            for (int y = top + 1; y >= top - 16; y--) {
                if (w.getBlockAt(x, y, z).getType() == Material.CHEST) return new Location(w, x + 0.5, y, z + 0.5);
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return null;
    }
}
