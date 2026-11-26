package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;

/**
 * The Farlands' nights (2.0, config {@code farlands-night:}; the Warden of the Rim is gone):
 * <ul>
 *   <li>BLOOD RAIN: every {@code blood-rain.check-minutes} 5, while someone is out there, {@code chance} 8% the rain turns
 *       red for {@code minutes} 8 (or a RIM SIGIL starts it). Red drops fall round everyone in the Farlands, monsters that
 *       spawn (and the ones already near you) get {@code mob-health} +50% / {@code mob-damage} +30% more, and monster drops,
 *       XP and Farlands chest loot are doubled. Being out there when it ends = Index stat {@code blood_rains}.</li>
 *   <li>COLLAPSING WALLS: walk along a wall top / ledge (natural grass, dirt, stone with a drop beside it) and now and then
 *       ({@code collapse.chance} 4% a new block, one per player every 20 s) it cracks under you and a few seconds later a
 *       piece of it breaks off and falls. Never near a block entity (chests, a base).</li>
 *   <li>WATCHERS: tall thin black figures standing still on far wall tops, only the player they watch can see them. Look
 *       straight at one (or come within 24 blocks) and it's gone. Index stat {@code watchers}.</li>
 * </ul>
 * Admin: {@code /farlands bloodrain [start|stop]}, {@code /farlands sigil [n] [player]}, {@code /farlands watcher},
 * {@code /farlands collapse}.
 */
final class FarlandsNight implements Listener {

    static final String WATCHER = "faultline_watcher", BLOOD = "faultline_blood_rain_mob";
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(0x9E0B0B), 1.3f);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(Color.fromRGB(0x5A0505), 1.8f);
    /** Only the Farlands' own natural blocks (never cobble / moss / mud / planks: those could be someone's build). */
    private static final Set<Material> CRUMBLE = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.STONE, Material.GRAVEL, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.DEEPSLATE, Material.PODZOL);

    private final FaultlineItems plugin;
    private final Farlands far;
    private final Random random = new Random();
    final NamespacedKey sigilKey, bloodHp, bloodDmg;

    private long bloodUntil;                       // server tick count it ends at (0 = off)
    private int tick, nextBloodCheck = 6000;
    private final Set<UUID> sawBlood = new HashSet<>();

    private final Map<UUID, Long> lastBlock = new HashMap<>();
    private final Map<UUID, Integer> collapseReady = new HashMap<>();
    private final List<Crumble> crumbling = new ArrayList<>();

    private final Map<UUID, Watcher> watchers = new HashMap<>();   // viewer -> their watcher
    private final Map<UUID, Integer> watcherNext = new HashMap<>();

    record Crumble(World w, List<Block> blocks, int at, UUID by) { }

    static final class Watcher {
        final List<Display> parts = new ArrayList<>();
        Location at; int born, stare;
    }

    FarlandsNight(FaultlineItems plugin, Farlands far) {
        this.plugin = plugin;
        this.far = far;
        sigilKey = new NamespacedKey(plugin, "rim_sigil");
        bloodHp = new NamespacedKey(plugin, "farlands_blood_hp");
        bloodDmg = new NamespacedKey(plugin, "farlands_blood_dmg");
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
                Set<String> t = e.getScoreboardTags();
                if (t.contains(WATCHER) || t.contains("faultline_rim_warden") || t.contains("faultline_rim_part")) e.remove();   // + the removed Warden's leftovers
            }
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("farlands-night." + k, d); }
    boolean flag(String k) { return plugin.getConfig().getBoolean("farlands-night." + k, true); }

    // ================================================================= the Rim Sigil (starts a Blood Rain)

    ItemStack sigil(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Rim Sigil", NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("A shard of the edge of the world.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click in the Farlands: the rain", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false),
                Component.text("turns to blood. Stronger monsters,", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false),
                Component.text("double the loot.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(NamespacedKey.minecraft("ender_eye"));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(sigilKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isSigil(ItemStack it) { return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(sigilKey, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSigil(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        if (!isSigil(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Farlands.in(p.getLocation())) { p.sendMessage(ChatColor.RED + "The sigil is cold here. It only wakes in the Farlands."); return; }
        if (bloodRain()) { p.sendMessage(ChatColor.RED + "The rain is already red."); return; }
        startBlood(p.getName());
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack h = p.getInventory().getItemInMainHand();
            if (isSigil(h)) { h.setAmount(h.getAmount() - 1); p.getInventory().setItemInMainHand(h.getAmount() > 0 ? h : null); }
        }
    }

    // ================================================================= blood rain

    boolean bloodRain() { return bloodUntil > tick; }

    void startBlood(String by) {
        bloodUntil = tick + (long) (cfg("blood-rain.minutes", 8) * 1200);
        sawBlood.clear();
        plugin.getLogger().info("[Farlands] Blood Rain" + (by != null ? " (" + by + "'s Rim Sigil)" : ""));
        for (Player p : far.playersOut()) {
            sawBlood.add(p.getUniqueId());
            p.showTitle(Title.title(Component.text("The rain turns red", NamedTextColor.DARK_RED),
                    Component.text("stronger monsters, double the loot", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p, "minecraft:entity.wither.ambient", SoundCategory.AMBIENT, 0.6f, 0.5f);
            p.playSound(p, "minecraft:ambient.basalt_deltas.mood", SoundCategory.AMBIENT, 1f, 0.6f);
            for (Entity en : p.getNearbyEntities(48, 32, 48)) if (en instanceof LivingEntity m && en instanceof Enemy && !(en instanceof Player)) bloodBuff(m);
        }
    }

    void stopBlood(boolean natural) {
        if (bloodUntil == 0) return;
        bloodUntil = 0;
        for (Player p : far.playersOut()) {
            p.sendActionBar(Component.text("The red rain stops.", NamedTextColor.GRAY));
            if (natural && sawBlood.contains(p.getUniqueId())) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " blood_rains 1");
        }
        sawBlood.clear();
    }

    void bloodBuff(LivingEntity m) {
        Set<String> tags = m.getScoreboardTags();   // our bosses, hitboxes, echoes keep their own numbers
        if (tags.contains(Farlands.ECHO) || tags.contains(Farlands.TRADER) || tags.contains("faultline_hitbox") || tags.contains("faultline_nest_brood")) return;
        AttributeInstance base = m.getAttribute(Attribute.MAX_HEALTH);
        if (base != null && base.getBaseValue() > 200) return;
        if (m.getScoreboardTags().contains(BLOOD)) return;
        m.addScoreboardTag(BLOOD);
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        double before = hp == null ? 0 : hp.getValue();
        add(m, Attribute.MAX_HEALTH, bloodHp, cfg("blood-rain.mob-health", 0.5));
        add(m, Attribute.ATTACK_DAMAGE, bloodDmg, cfg("blood-rain.mob-damage", 0.3));
        if (hp != null && before > 0) m.setHealth(Math.max(1, Math.min(hp.getValue(), m.getHealth() * hp.getValue() / before)));
    }

    private static void add(LivingEntity m, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance i = m.getAttribute(a);
        if (i == null || amount == 0) return;
        for (AttributeModifier mod : i.getModifiers()) if (mod.getKey().equals(key)) return;
        i.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!bloodRain() || !(e.getEntity() instanceof Enemy) || e.getEntity() instanceof Player) return;
        if (!Farlands.in(e.getLocation())) return;
        LivingEntity m = e.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> { if (m.isValid() && bloodRain()) bloodBuff(m); });   // after Raids made it an Elite / Mutant
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        if (!bloodRain() || !(e.getEntity() instanceof Enemy) || e.getEntity() instanceof Player || e.getEntity().getKiller() == null) return;
        if (!e.getEntity().getScoreboardTags().contains(BLOOD) && !Farlands.in(e.getEntity().getLocation())) return;
        List<ItemStack> extra = new ArrayList<>();
        for (ItemStack it : e.getDrops()) if (doubles(it)) extra.add(it.clone());
        e.getDrops().addAll(extra);
        e.setDroppedExp(e.getDroppedExp() * 2);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        if (!bloodRain() || e.getLootContext().getLocation() == null || !Farlands.in(e.getLootContext().getLocation())) return;
        if (!e.getLootTable().getKey().getKey().contains("farlands")) return;
        List<ItemStack> loot = new ArrayList<>(e.getLoot()), extra = new ArrayList<>();
        for (ItemStack it : loot) if (doubles(it)) extra.add(it.clone());
        loot.addAll(extra);
        e.setLoot(loot);
    }

    /** Only plain stackable things double (never our own items, never gear). */
    static boolean doubles(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getMaxStackSize() <= 1) return false;
        if (it.getType() == Material.ECHO_SHARD) return false;
        return it.getPersistentDataContainer().getKeys().isEmpty();
    }

    void bloodTick() {
        if (tick >= nextBloodCheck) {
            nextBloodCheck = tick + (int) Math.max(1200, cfg("blood-rain.check-minutes", 5) * 1200);
            if (!bloodRain() && flag("blood-rain.enabled") && !far.playersOut().isEmpty() && random.nextDouble() < cfg("blood-rain.chance", 0.08)) startBlood(null);
        }
        if (bloodUntil != 0 && tick >= bloodUntil) { stopBlood(true); return; }
        if (!bloodRain() || tick % 4 != 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!far.inCached(p)) continue;
            sawBlood.add(p.getUniqueId());
            Location l = p.getLocation();
            for (int i = 0; i < 26; i++) {
                double x = l.getX() + (random.nextDouble() - 0.5) * 22, z = l.getZ() + (random.nextDouble() - 0.5) * 22;
                double top = Math.max(l.getY() + 6, l.getWorld().getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1);
                if (top > l.getY() + 14) continue;   // under a roof / overhang: no rain
                p.spawnParticle(Particle.DUST, x, top + random.nextDouble() * 6, z, 0, 0, -1, 0, 1, i % 4 == 0 ? DEEP : RED);
            }
            p.spawnParticle(Particle.FALLING_DUST, l.clone().add(0, 8, 0), 6, 9, 2, 9, 0, Material.RED_CONCRETE.createBlockData());
        }
    }

    // ================================================================= collapsing walls

    void collapseTick() {
        if (!flag("collapse.enabled")) return;
        for (Player p : far.playersOut()) {
            if (!p.isOnGround() || p.isFlying() || p.isInsideVehicle()) continue;
            Block under = p.getLocation().clone().subtract(0, 0.2, 0).getBlock();
            long key = under.getBlockKey();
            Long was = lastBlock.put(p.getUniqueId(), key);
            if (was != null && was == key) continue;   // only a NEW block rolls
            if (tick < collapseReady.getOrDefault(p.getUniqueId(), 0)) continue;
            if (!CRUMBLE.contains(under.getType()) || !ledge(under)) continue;
            if (random.nextDouble() >= cfg("collapse.chance", 0.04)) continue;
            List<Block> piece = piece(under);
            if (piece.size() < 4) continue;
            collapseReady.put(p.getUniqueId(), tick + (int) (cfg("collapse.cooldown-seconds", 20) * 20));
            crumbling.add(new Crumble(under.getWorld(), piece, tick + (int) cfg("collapse.delay-ticks", 40), p.getUniqueId()));
            p.sendActionBar(Component.text("The ground cracks under you...", NamedTextColor.RED));
            under.getWorld().playSound(under.getLocation(), "minecraft:block.pointed_dripstone.break", SoundCategory.BLOCKS, 1.2f, 0.5f);
        }
        for (Iterator<Crumble> it = crumbling.iterator(); it.hasNext(); ) {
            Crumble c = it.next();
            int left = c.at() - tick;
            if (left > 0) {
                float progress = Math.min(0.95f, 1f - left / (float) cfg("collapse.delay-ticks", 40));
                for (Block b : c.blocks()) {
                    for (Player v : c.w().getPlayers()) if (v.getLocation().distanceSquared(b.getLocation()) < 64 * 64) crack(v, b.getLocation(), progress, b.hashCode());
                    if (random.nextInt(6) == 0) c.w().spawnParticle(Particle.BLOCK, b.getLocation().clone().add(0.5, 1, 0.5), 3, 0.3, 0.1, 0.3, 0, b.getBlockData());
                }
                if (left % 10 == 0) c.w().playSound(c.blocks().get(0).getLocation(), "minecraft:block.gravel.break", SoundCategory.BLOCKS, 1f, 0.6f);
                continue;
            }
            it.remove();
            fall(c);
        }
    }

    /** A wall top / ledge: open air within 3 blocks to a side, going down at least 6. */
    boolean ledge(Block b) {
        World w = b.getWorld();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) for (int r = 1; r <= 3; r++) {
            int x = b.getX() + d[0] * r, z = b.getZ() + d[1] * r;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            boolean drop = true;
            for (int dy = 0; dy >= -6 && drop; dy--) if (!w.getBlockAt(x, b.getY() + dy, z).isPassable()) drop = false;
            if (drop) return true;
        }
        return false;
    }

    /** The piece that breaks off: a rough disc round the block, 2-3 deep, only natural stuff, never near a block entity. */
    List<Block> piece(Block c) {
        List<Block> out = new ArrayList<>();
        World w = c.getWorld();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -4; y <= 2; y++) {
            Block b = c.getRelative(x, y, z);
            if (b.getState(false) instanceof org.bukkit.block.TileState) return List.of();   // someone's stuff nearby
        }
        double r = 2 + random.nextDouble() * 1.4;
        int depth = 2 + random.nextInt(2);
        int max = (int) cfg("collapse.max-blocks", 40);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (x * x + z * z > r * r) continue;
            for (int y = 0; y > -depth; y--) {
                Block b = c.getRelative(x, y, z);
                if (!CRUMBLE.contains(b.getType())) continue;
                out.add(b);
                if (out.size() >= max) return out;
            }
            Block above = c.getRelative(x, 1, z);   // grass, flowers on top go with it
            if (!above.getType().isAir() && above.isPassable() && !above.isLiquid()) out.add(0, above);
        }
        if (w.getHighestBlockYAt(c.getX(), c.getZ()) > c.getY() + 2) return List.of();   // a cave ceiling, not a wall top
        return out;
    }

    private static void crack(Player v, Location l, float progress, int id) {
        try { v.sendBlockDamage(l, progress, id); } catch (RuntimeException ignored) { }
    }

    void fall(Crumble c) {
        World w = c.w();
        Location mid = c.blocks().get(c.blocks().size() / 2).getLocation().clone().add(0.5, 0.5, 0.5);
        w.playSound(mid, "minecraft:entity.generic.explode", SoundCategory.BLOCKS, 0.7f, 0.5f);
        w.playSound(mid, "minecraft:block.stone.break", SoundCategory.BLOCKS, 1.5f, 0.5f);
        for (Block b : c.blocks()) {
            if (!CRUMBLE.contains(b.getType()) && !(b.isPassable() && !b.getType().isAir())) continue;   // changed meanwhile
            for (Player v : w.getPlayers()) if (v.getLocation().distanceSquared(b.getLocation()) < 64 * 64) crack(v, b.getLocation(), 0f, b.hashCode());
            BlockData data = b.getBlockData();
            boolean solid = CRUMBLE.contains(b.getType());
            b.setType(Material.AIR, false);
            if (!solid) continue;
            FallingBlock fb = w.spawn(b.getLocation().clone().add(0.5, 0, 0.5), FallingBlock.class, f -> {
                f.setBlockData(data);
                f.setDropItem(false);
                f.setHurtEntities(true);
                f.setDamagePerBlock(0.5f);
                f.setMaxDamage(6);
                f.addScoreboardTag("faultline_wall_crumble");
            });
            Safe.vel(fb, new Vector((random.nextDouble() - 0.5) * 0.15, -0.05, (random.nextDouble() - 0.5) * 0.15));
            w.spawnParticle(Particle.BLOCK, b.getLocation().clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, data);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLand(EntityChangeBlockEvent e) {   // fallen pieces land as rubble: cobble / dirt, a few vanish
        if (!(e.getEntity() instanceof FallingBlock fb) || !fb.getScoreboardTags().contains("faultline_wall_crumble")) return;
        if (random.nextInt(3) == 0) { e.setCancelled(true); fb.remove(); return; }
        Material m = fb.getBlockData().getMaterial();
        if (m == Material.GRASS_BLOCK || m == Material.PODZOL || m == Material.MOSS_BLOCK) fb.setBlockData(Material.DIRT.createBlockData());
        else if (m == Material.STONE || m == Material.DEEPSLATE) fb.setBlockData((m == Material.STONE ? Material.COBBLESTONE : Material.COBBLED_DEEPSLATE).createBlockData());
    }

    /** Admin: crumble the ground right under someone (tests too). */
    boolean collapseUnder(Player p) {
        Block under = p.getLocation().clone().subtract(0, 0.2, 0).getBlock();
        List<Block> piece = piece(under);
        if (piece.isEmpty()) return false;
        crumbling.add(new Crumble(under.getWorld(), piece, tick + (int) cfg("collapse.delay-ticks", 40), p.getUniqueId()));
        return true;
    }

    // ================================================================= watchers

    void watcherTick() {
        if (!flag("watchers.enabled")) { for (UUID id : new ArrayList<>(watchers.keySet())) vanish(id, false); return; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Watcher w = watchers.get(id);
            if (w != null) {
                if (!p.isOnline() || !far.inCached(p) || p.getWorld() != w.at.getWorld()) { vanish(id, false); continue; }
                Location eye = p.getEyeLocation();
                Vector to = w.at.clone().add(0, 2.4, 0).toVector().subtract(eye.toVector());
                double d = to.length();
                face(w, p);
                if (d < cfg("watchers.vanish-distance", 24)) { vanish(id, true); continue; }
                if (d > 1e-3 && to.normalize().dot(eye.getDirection()) > Math.cos(Math.toRadians(cfg("watchers.stare-degrees", 7)))) {
                    if (++w.stare >= 6) { vanish(id, true); continue; }   // ~0.5 s of looking straight at it
                } else w.stare = 0;
                if (tick - w.born > cfg("watchers.seconds", 60) * 20) vanish(id, false);
                else if (Edition.bedrock(p) && tick % 4 == 0) bedrockShape(p, w);
                continue;
            }
            if (!far.inCached(p) || !Farlands.survival(p) || p.isDead()) continue;
            int next = watcherNext.computeIfAbsent(id, k -> tick + 400 + random.nextInt(400));
            if (tick < next) continue;
            watcherNext.put(id, tick + (int) (cfg("watchers.every-seconds", 30) * 20 * (0.6 + random.nextDouble() * 0.8)));
            if (random.nextDouble() < cfg("watchers.chance", 0.35)) spawnWatcher(p, false);
        }
        watcherNext.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    /** A wall top 40-90 blocks off (closer = 26-34 for a "behind you" one) at least 10 above the player, in a loaded chunk. */
    Location watcherSpot(Player p, boolean close) {
        Location l = p.getLocation();
        World w = l.getWorld();
        Vector look = l.getDirection().setY(0);
        for (int tries = 0; tries < 24; tries++) {
            double a = close ? Math.atan2(-look.getZ(), -look.getX()) + (random.nextDouble() - 0.5) * 1.6 : random.nextDouble() * Math.PI * 2;
            double d = close ? 26 + random.nextDouble() * 8 : 40 + random.nextDouble() * 50;
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            if (y < l.getY() + (close ? 3 : 10)) continue;
            Block top = w.getBlockAt(x, y, z);
            if (top.isLiquid() || top.getType().name().contains("LEAVES")) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    Watcher spawnWatcher(Player p, boolean close) {
        Location at = watcherSpot(p, close);
        if (at == null) return null;
        Watcher w = new Watcher();
        w.at = at; w.born = tick;
        // body: a thin black column; head; two long arms; two pin-prick eyes. ~3.4 blocks tall, 0.35 wide.
        part(w, p, Material.BLACK_CONCRETE, 0, 0, 0, 0.32f, 2.5f, 0.22f, 0);
        part(w, p, Material.BLACK_CONCRETE, 0, 2.5, 0, 0.42f, 0.5f, 0.38f, 0);
        part(w, p, Material.BLACK_CONCRETE, -0.27, 0.55, 0, 0.1f, 2.0f, 0.1f, 0);
        part(w, p, Material.BLACK_CONCRETE, 0.27, 0.55, 0, 0.1f, 2.0f, 0.1f, 0);
        part(w, p, Material.WHITE_CONCRETE, -0.09, 2.75, -0.2, 0.06f, 0.05f, 0.02f, 15);
        part(w, p, Material.WHITE_CONCRETE, 0.09, 2.75, -0.2, 0.06f, 0.05f, 0.02f, 15);
        face(w, p);
        watchers.put(p.getUniqueId(), w);
        return w;
    }

    private void part(Watcher w, Player viewer, Material m, double x, double y, double z, float sx, float sy, float sz, int light) {
        Location base = w.at.clone();
        base.setYaw(0); base.setPitch(0);
        BlockDisplay d = base.getWorld().spawn(base, BlockDisplay.class, bd -> {
            bd.setVisibleByDefault(false);
            bd.setPersistent(false);
            bd.addScoreboardTag(WATCHER);
            bd.addScoreboardTag("faultline_no_bedrock_fx");
            bd.setBlock(m.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f((float) x - sx / 2, (float) y, (float) z - sz / 2), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            bd.setViewRange(4f);
            if (light > 0) bd.setBrightness(new Display.Brightness(light, light));
            else bd.setBrightness(new Display.Brightness(0, 0));
        });
        viewer.showEntity(plugin, d);
        w.parts.add(d);
    }

    void face(Watcher w, Player p) {
        Vector to = p.getLocation().toVector().subtract(w.at.toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        for (Display d : w.parts) if (d.isValid() && Math.abs(d.getLocation().getYaw() - yaw) > 2) d.setRotation(yaw, 0);
    }

    void bedrockShape(Player p, Watcher w) {   // Bedrock can't see displays: a thin black dust figure for them
        for (double y = 0; y < 3.0; y += 0.25) p.spawnParticle(Particle.DUST, w.at.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0, new Particle.DustOptions(Color.BLACK, 1.2f));
    }

    void vanish(UUID viewer, boolean seen) {
        Watcher w = watchers.remove(viewer);
        if (w == null) return;
        for (Display d : w.parts) if (d.isValid()) d.remove();
        Player p = Bukkit.getPlayer(viewer);
        if (p == null || !seen) return;
        p.spawnParticle(Particle.LARGE_SMOKE, w.at.clone().add(0, 1.6, 0), 20, 0.2, 1.0, 0.2, 0.01);
        p.playSound(w.at, "minecraft:entity.enderman.teleport", SoundCategory.AMBIENT, 0.4f, 0.4f);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " watchers 1");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index discover " + p.getName() + " farlands_watcher");
        if (random.nextDouble() < cfg("watchers.return-chance", 0.15))   // ...and sometimes it's behind you a moment later
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && far.inCached(p) && !watchers.containsKey(viewer)) spawnWatcher(p, true); }, 60L);
    }

    boolean hasWatcher(Player p) { return watchers.containsKey(p.getUniqueId()); }

    // ================================================================= ticks

    void tick() {
        tick++;
        try {
            bloodTick();
            if (tick % 10 == 0) collapseTick();
            if (tick % 2 == 0) watcherTick();
            if (tick % 1200 == 0) { lastBlock.keySet().removeIf(u -> Bukkit.getPlayer(u) == null); collapseReady.values().removeIf(t -> t < tick); }
        } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[Farlands night] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(watchers.keySet())) vanish(id, false);
        for (Crumble c : crumbling) for (Block b : c.blocks()) for (Player v : c.w().getPlayers()) crack(v, b.getLocation(), 0f, b.hashCode());
        crumbling.clear();
    }

    String status() {
        return "blood rain: " + (bloodRain() ? ((bloodUntil - tick) / 20) + " s left" : "no") + ", watchers out: " + watchers.size() + ", crumbling: " + crumbling.size();
    }
}
