package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * THE FARLANDS (2.0 round 10, config {@code farlands:}). The outer ring of the main world (FaultlineWorld's biome
 * {@code faultline:farlands}, past 3280 blocks out) is meant to be HARD with really good loot:
 * <ul>
 *   <li>Every monster that spawns there gets +25% health and damage ({@code mob-health} / {@code mob-damage}).</li>
 *   <li>GHASTS drift over the walls (the datapack spawns a few; this keeps up to {@code ghasts.max} near each player).</li>
 *   <li>WALL CRAWLERS: spiders that cling high on the walls and DROP onto you from above (a heavy hit + Slowness).
 *       30% of Farlands spiders, plus packs that turn up on the walls over players.</li>
 *   <li>ECHOES: die out here and a dark copy of you rises where you fell, carrying {@code echoes.share} of what you dropped.
 *       Kill it (anyone can) to get it all back. It fades after {@code echoes.expire-days}, loot and all.</li>
 *   <li>THE RIM TRADER: a wandering trader that only ever comes to the Farlands, selling things you can't buy anywhere
 *       else (the Rim Sigil = a Blood Rain, see FarlandsNight; bags, boss books, totems). Every offer can be bought a few times, then he's sold out.</li>
 *   <li>LOOT: the datapack's Farlands chests (vaults, floating vaults, lost explorers' camps) get Goodie / Mythic / Dark Loot
 *       bags, boss enchant books, accessories and Rim Sigils on top; camps hold the explorer's journal. 12% of the vault
 *       chests are MIMICS ({@code mimic.farlands-chance}, in Creatures).</li>
 * </ul>
 * The music, the dark crimson sky and the ambient sounds come from the datapack's biome (FaultlineWorld).
 * Admin: {@code /farlands [status|trader [here]|crawler|echoes|ghast]}.
 */
final class Farlands implements Listener, CommandExecutor, TabCompleter {

    static final String CRAWLER = "faultline_wall_crawler", ECHO = "faultline_echo", TRADER = "faultline_rim_trader", PERM = "items.farlands";
    static final String BIOME = "faultline:farlands";
    private static final Particle.DustOptions DARK = new Particle.DustOptions(Color.fromRGB(0x2A0E1A), 1.6f);

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey hpKey, dmgKey;
    private final Set<UUID> crawlers = new HashSet<>();
    private final Map<UUID, Integer> pounceAt = new HashMap<>();
    private final Map<UUID, Long> dropping = new HashMap<>();
    private final Map<UUID, Boolean> inCache = new HashMap<>();
    private final List<Echo> echoes = new ArrayList<>();
    private final File echoFile;
    static final String MUSIC = "faultline:farlands.music";
    static final String[] VANILLA_MUSIC = {"minecraft:music.game", "minecraft:music.creative", "minecraft:music.overworld.windswept_hills",
            "minecraft:music.overworld.jagged_peaks", "minecraft:music.overworld.stony_peaks", "minecraft:music.under_water"};
    private final Map<UUID, Long> musicAt = new HashMap<>();
    /** Players we've put in the Farlands' night + rain (reset when they leave). */
    private final Set<UUID> spooky = new HashSet<>();
    private final Map<UUID, Integer> spookAt = new HashMap<>();
    private UUID trader;
    private long traderUntil;
    private int tick, musicErrAt = -100000;

    static final class Echo {
        String id, owner, ownerName, world, items; double x, y, z; long created; UUID entity;
    }

    Farlands(FaultlineItems plugin) {
        this.plugin = plugin;
        hpKey = new NamespacedKey(plugin, "farlands_hp");
        dmgKey = new NamespacedKey(plugin, "farlands_dmg");
        echoFile = new File(plugin.getDataFolder(), "echoes.yml");
        loadEchoes();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(ECHO) || e.getScoreboardTags().contains(TRADER)) e.remove();   // never saved; respawned from echoes.yml
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("farlands." + k, d); }

    boolean on() { return plugin.getConfig().getBoolean("farlands.enabled", true); }

    // ================================================================= where

    /** The main world's Farlands biome (FaultlineWorld). Without that biome (another generator) it's never true. */
    static volatile boolean forceIn;   // tests only (MockBukkit's worlds have no Faultline biomes)

    static boolean in(Location l) {
        if (l == null || l.getWorld() == null) return false;
        if (forceIn) return true;
        try { return BIOME.equals(l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ()).getKey().toString()); }
        catch (Throwable t) { return false; }
    }

    boolean inCached(Player p) {
        Boolean b = inCache.get(p.getUniqueId());
        if (b == null) { b = in(p.getLocation()); inCache.put(p.getUniqueId(), b); }
        return b;
    }

    static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    List<Player> playersOut() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (survival(p) && !p.isDead() && inCached(p)) out.add(p);
        return out;
    }

    // ================================================================= harder mobs

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on() || !(e.getEntity() instanceof Enemy) || e.getEntity() instanceof Player) return;
        LivingEntity m = e.getEntity();
        for (String t : m.getScoreboardTags()) if (t.startsWith("faultline")) return;   // our bosses and their minions set their own numbers
        if (!in(e.getLocation())) return;
        buff(m);
        if (m.getType() == EntityType.SPIDER && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && random.nextDouble() < cfg("crawlers.spider-share", 0.3))
            Bukkit.getScheduler().runTask(plugin, () -> { if (m.isValid()) crawler((Spider) m); });
    }

    void buff(LivingEntity m) {
        add(m, Attribute.MAX_HEALTH, hpKey, cfg("mob-health", 0.25));
        add(m, Attribute.ATTACK_DAMAGE, dmgKey, cfg("mob-damage", 0.25));
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) m.setHealth(Math.max(1, Math.min(hp.getValue(), m.getHealth() * (1 + cfg("mob-health", 0.25)))));
    }

    private static void add(LivingEntity m, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance i = m.getAttribute(a);
        if (i == null || amount == 0) return;
        for (AttributeModifier mod : i.getModifiers()) if (mod.getKey().equals(key)) return;
        i.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    // ================================================================= wall crawlers

    Spider crawler(Spider s) {
        s.addScoreboardTag(CRAWLER);
        s.customName(Component.text("Wall Crawler", NamedTextColor.DARK_RED));
        s.setCustomNameVisible(false);
        MimicPlayer.attr(s, Attribute.MAX_HEALTH, cfg("crawlers.health", 34));
        s.setHealth(s.getAttribute(Attribute.MAX_HEALTH).getValue());   // AUDIT: full (an Elite crawler's x2 modifier stays on it)
        MimicPlayer.attr(s, Attribute.ATTACK_DAMAGE, cfg("crawlers.damage", 6));
        MimicPlayer.attr(s, Attribute.MOVEMENT_SPEED, 0.36);
        MimicPlayer.attr(s, Attribute.FOLLOW_RANGE, 40);
        MimicPlayer.attr(s, Attribute.SCALE, 1.25);
        MimicPlayer.attr(s, Attribute.SAFE_FALL_DISTANCE, 64);
        crawlers.add(s.getUniqueId());
        return s;
    }

    /** A spot clinging high on a wall near the player: air with a solid block beside it, 6-18 above the player. */
    Location wallSpot(Player p) {
        World w = p.getWorld();
        Location c = p.getLocation();
        for (int k = 0; k < 20; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = 5 + random.nextDouble() * 9;
            int x = c.getBlockX() + (int) Math.round(Math.cos(a) * d), z = c.getBlockZ() + (int) Math.round(Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = 18; dy >= 6; dy--) {
                Block b = w.getBlockAt(x, c.getBlockY() + dy, z);
                if (!b.getType().isAir() || !b.getRelative(0, 1, 0).getType().isAir()) continue;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    if (b.getRelative(o[0], 0, o[1]).getType().isSolid()) return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    void crawlerPack(Player p) {
        int near = 0;
        for (Entity e : p.getNearbyEntities(32, 32, 32)) if (e.getScoreboardTags().contains(CRAWLER)) near++;
        int max = (int) cfg("crawlers.max-near", 4);
        int n = Math.min(max - near, 1 + random.nextInt(2));
        for (int i = 0; i < n; i++) {
            Location at = wallSpot(p);
            if (at == null) return;
            Spider s = (Spider) at.getWorld().spawnEntity(at, EntityType.SPIDER, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(CRAWLER));
            buff(s);
            crawler(s);
            s.setTarget(p);
        }
    }

    /** Every 4 ticks: a crawler above its target drops onto it. */
    void crawlerTick() {
        for (Iterator<UUID> it = crawlers.iterator(); it.hasNext(); ) {
            Entity e = Bukkit.getEntity(it.next());
            if (!(e instanceof Spider s) || !s.isValid()) { it.remove(); continue; }   // (dropping / pounceAt are cleaned every 30 s)
            Long dropT = dropping.get(s.getUniqueId());
            Player tg = s.getTarget() instanceof Player p && survival(p) ? p : null;
            if (tg == null) {
                for (Player p : s.getWorld().getPlayers()) if (survival(p) && p.getLocation().distanceSquared(s.getLocation()) < 16 * 16) { tg = p; s.setTarget(p); break; }
            }
            if (dropT != null) {
                if (tick - dropT > 40) { dropping.remove(s.getUniqueId()); continue; }
                if (tg != null && s.getLocation().distanceSquared(tg.getLocation()) < 2.4 * 2.4 && tick - dropT > 3) {
                    dropping.remove(s.getUniqueId());
                    tg.damage(cfg("crawlers.drop-damage", 9), s);
                    tg.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1));
                    tg.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
                    tg.getWorld().playSound(tg.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.4f, 0.5f);
                    tg.getWorld().spawnParticle(Particle.BLOCK, tg.getLocation().add(0, 0.3, 0), 18, 0.5, 0.2, 0.5, 0, Material.COBWEB.createBlockData());
                } else if (s.isOnGround() && tick - dropT > 12) dropping.remove(s.getUniqueId());
                continue;
            }
            if (tg == null) continue;
            Location sl = s.getLocation(), pl = tg.getLocation();
            double dy = sl.getY() - pl.getY(), hx = sl.getX() - pl.getX(), hz = sl.getZ() - pl.getZ();
            Integer ready = pounceAt.get(s.getUniqueId());
            if (dy >= 3 && dy < 24 && hx * hx + hz * hz < 7 * 7 && (ready == null || ready <= tick) && sees(s.getLocation().add(0, 0.5, 0), tg.getEyeLocation())) {
                Vector v = new Vector(-hx, 0, -hz).multiply(0.16).setY(dy > 8 ? -0.2 : 0.1);
                Safe.vel(s, v);
                dropping.put(s.getUniqueId(), (long) tick);
                pounceAt.put(s.getUniqueId(), tick + 100);
                s.getWorld().playSound(sl, Sound.ENTITY_SPIDER_AMBIENT, 1.6f, 0.6f);
                tg.sendActionBar(Component.text("Something drops from above!", NamedTextColor.DARK_RED));
            }
        }
    }

    /** A block walk from a to b: no solid, occluding block in between. */
    static boolean sees(Location a, Location b) {
        if (a.getWorld() != b.getWorld()) return false;
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.5) return true;
        d.multiply(1 / len);
        for (double t = 0.5; t < len - 0.3; t += 0.4) {
            Block blk = a.clone().add(d.clone().multiply(t)).getBlock();
            if (blk.getType().isOccluding()) return false;
        }
        return true;
    }

    /** AUDIT: a crawler whose chunk unloaded and loaded again is found again (it stopped pouncing). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Spider && en.getScoreboardTags().contains(CRAWLER)) crawlers.add(en.getUniqueId());
    }

    // ================================================================= ghasts

    void ghasts(Player p) {
        int near = 0;
        for (Entity e : p.getNearbyEntities(80, 80, 80)) if (e.getType() == EntityType.GHAST) near++;
        if (near >= cfg("ghasts.max", 2)) return;
        World w = p.getWorld();
        for (int k = 0; k < 8; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = 25 + random.nextDouble() * 20;
            int x = p.getLocation().getBlockX() + (int) (Math.cos(a) * d), z = p.getLocation().getBlockZ() + (int) (Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = Math.max(w.getHighestBlockYAt(x, z) + 8, p.getLocation().getBlockY() + 18 + random.nextInt(14));
            if (y > w.getMaxHeight() - 8) continue;
            boolean clear = true;
            for (int ox = -2; ox <= 2 && clear; ox += 2) for (int oz = -2; oz <= 2 && clear; oz += 2) for (int oy = 0; oy <= 4; oy += 2)
                if (!w.getBlockAt(x + ox, y + oy, z + oz).getType().isAir()) { clear = false; break; }
            if (!clear) continue;
            w.spawnEntity(new Location(w, x + 0.5, y, z + 0.5), EntityType.GHAST, CreatureSpawnEvent.SpawnReason.NATURAL, en -> { });
            return;
        }
    }

    // ================================================================= echoes

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!on() || !plugin.getConfig().getBoolean("farlands.echoes.enabled", true) || e.getKeepInventory() || !survival(p) || !in(p.getLocation())) return;
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack it : e.getDrops()) if (it != null && !it.getType().isAir() && it.getType() != Material.MACE) pool.add(it);
        if (pool.isEmpty()) return;
        Collections.shuffle(pool, random);
        int take = Math.max(1, (int) Math.round(pool.size() * cfg("echoes.share", 0.35)));
        List<ItemStack> held = new ArrayList<>();
        for (int i = 0; i < take && i < pool.size(); i++) held.add(pool.get(i));
        // remove those exact stacks from the drops (by identity)
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) { ItemStack s = it.next(); for (ItemStack h : held) if (s == h) { it.remove(); break; } }
        Echo ec = new Echo();
        ec.id = UUID.randomUUID().toString().substring(0, 8);
        ec.owner = p.getUniqueId().toString(); ec.ownerName = p.getName();
        Location l = p.getLocation();
        ec.world = l.getWorld().getName(); ec.x = l.getX(); ec.y = Math.max(l.getWorld().getMinHeight() + 2, l.getY()); ec.z = l.getZ();
        ec.created = System.currentTimeMillis();
        ec.items = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(held.toArray(new ItemStack[0])));
        echoes.add(ec);
        saveEchoesNow();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(ChatColor.DARK_RED + "Your ECHO rose in the Farlands, holding " + held.size() + " of your stacks. "
                    + ChatColor.GRAY + "Kill it at " + Math.round(ec.x) + " " + Math.round(ec.y) + " " + Math.round(ec.z) + " to get them back.");
        }, 40L);
    }

    void echoTick() {
        long now = System.currentTimeMillis();
        double days = cfg("echoes.expire-days", 3);
        boolean changed = false;
        for (Iterator<Echo> it = echoes.iterator(); it.hasNext(); ) {
            Echo ec = it.next();
            Entity body = ec.entity == null ? null : Bukkit.getEntity(ec.entity);
            if (days > 0 && now - ec.created > days * 86_400_000L && body == null) {
                plugin.getLogger().info("[Farlands] the echo of " + ec.ownerName + " faded (" + ec.id + ")");
                it.remove(); changed = true; continue;
            }
            World w = Bukkit.getWorld(ec.world);
            if (w == null) continue;
            Location at = new Location(w, ec.x, ec.y, ec.z);
            if (body != null && body.isValid()) {
                boolean anyone = false;
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(body.getLocation()) < 64 * 64) { anyone = true; break; }
                if (!anyone) { body.remove(); ec.entity = null; }
                else if (tick % 4 == 0) w.spawnParticle(Particle.DUST, body.getLocation().add(0, 1, 0), 3, 0.3, 0.6, 0.3, 0, DARK);
                continue;
            }
            ec.entity = null;
            if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            for (Player p : w.getPlayers())
                if (survival(p) && p.getLocation().distanceSquared(at) < 36 * 36) { spawnEcho(ec, at); break; }
        }
        if (changed) saveEchoes();
    }

    void spawnEcho(Echo ec, Location at) {
        // AUDIT: someone who died in lava / water / a wall: the echo rises on the nearest dry, open spot above (it used to
        // burn in the lava with the loot it held)
        Location spot = at.clone();
        for (int dy = 0; dy < 24 && !(Emotes.open(spot.getBlock()) && !spot.getBlock().isLiquid()
                && Emotes.open(spot.clone().add(0, 1, 0).getBlock()) && !spot.clone().add(0, 1, 0).getBlock().isLiquid()); dy++) spot.add(0, 1, 0);
        Zombie z = (Zombie) spot.getWorld().spawnEntity(spot, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(ECHO));
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        Emotes.q(() -> z.setShouldBurnInDay(false));
        Emotes.q(() -> z.setCanPickupItems(false));
        z.setPersistent(false);
        Emotes.q(() -> z.setRemoveWhenFarAway(false));
        z.customName(Component.text("Echo of " + ec.ownerName, NamedTextColor.DARK_GRAY));
        z.setCustomNameVisible(true);
        MimicPlayer.attr(z, Attribute.MAX_HEALTH, cfg("echoes.health", 80));
        z.setHealth(Math.min(cfg("echoes.health", 80), z.getAttribute(Attribute.MAX_HEALTH).getValue()));
        MimicPlayer.attr(z, Attribute.ATTACK_DAMAGE, cfg("echoes.damage", 9));
        MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, 0.3);
        MimicPlayer.attr(z, Attribute.ARMOR, 8);
        MimicPlayer.attr(z, Attribute.FOLLOW_RANGE, 40);
        MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        MimicPlayer.attr(z, Attribute.SAFE_FALL_DISTANCE, 64);   // AUDIT: rising over a wall it must not die from the drop
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        var eq = z.getEquipment();
        eq.clear();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        try {
            if (head.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(ec.owner))); head.setItemMeta(sm); }
        } catch (RuntimeException ignored) { }
        eq.setHelmet(head);
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS));
        eq.setBoots(dyed(Material.LEATHER_BOOTS));
        ItemStack[] items = items(ec);
        for (ItemStack it : items) if (it != null && (it.getType().name().endsWith("_SWORD") || it.getType().name().endsWith("_AXE"))) { eq.setItemInMainHand(it.clone()); break; }
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0); } catch (RuntimeException ignored) { }
        z.getPersistentDataContainer().set(new NamespacedKey(plugin, "echo_id"), org.bukkit.persistence.PersistentDataType.STRING, ec.id);
        ec.entity = z.getUniqueId();
        z.getWorld().spawnParticle(Particle.SQUID_INK, spot.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.02);
        z.getWorld().playSound(spot, Sound.ENTITY_WARDEN_EMERGE, 0.8f, 1.6f);
    }

    private static ItemStack dyed(Material m) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(Color.fromRGB(0x141018)); it.setItemMeta(lm); }
        return it;
    }

    ItemStack[] items(Echo ec) {
        try { return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(ec.items)); }
        catch (RuntimeException ex) { plugin.getLogger().warning("[Farlands] couldn't read the echo " + ec.id + ": " + ex); return new ItemStack[0]; }
    }

    @EventHandler
    public void onEchoDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ECHO)) return;
        String id = e.getEntity().getPersistentDataContainer().get(new NamespacedKey(plugin, "echo_id"), org.bukkit.persistence.PersistentDataType.STRING);
        e.getDrops().clear();
        e.setDroppedExp((int) cfg("echoes.xp", 120));
        Echo ec = null;
        for (Echo x : echoes) if (x.id.equals(id)) ec = x;
        if (ec == null) return;
        for (ItemStack it : items(ec)) if (it != null && !it.getType().isAir()) e.getDrops().add(it);
        echoes.remove(ec);
        saveEchoesNow();   // straight to disk: a crash right after must not bring the echo (and its loot) back = a dupe
        Player k = e.getEntity().getKiller();
        if (k != null) {
            k.sendMessage(ChatColor.DARK_GRAY + "The echo of " + ec.ownerName + " breaks apart and drops what it held.");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + k.getName() + " echoes 1");
        }
        e.getEntity().getWorld().spawnParticle(Particle.SQUID_INK, e.getEntity().getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.05);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEchoBurn(EntityCombustEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ECHO) && !(e instanceof EntityCombustByEntityEvent)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ECHO)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(ECHO) || t.contains(TRADER)) e.setCancelled(true);
    }

    void loadEchoes() {
        YamlConfiguration y = SafeYaml.load(plugin, echoFile).yaml;
        var sec = y.getConfigurationSection("echoes");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            var s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Echo ec = new Echo();
            ec.id = id; ec.owner = s.getString("owner", ""); ec.ownerName = s.getString("name", "?"); ec.world = s.getString("world", "world");
            ec.x = s.getDouble("x"); ec.y = s.getDouble("y"); ec.z = s.getDouble("z"); ec.created = s.getLong("created"); ec.items = s.getString("items", "");
            echoes.add(ec);
        }
    }

    void saveEchoesNow() {
        try { SafeYaml.saveNow(echoYaml(), echoFile); } catch (java.io.IOException ex) { plugin.getLogger().warning("[Farlands] couldn't save echoes.yml: " + ex); }
    }

    void saveEchoes() { SafeYaml.write(plugin, echoFile, echoYaml().saveToString()); }

    YamlConfiguration echoYaml() {
        YamlConfiguration y = new YamlConfiguration();
        for (Echo ec : echoes) {
            String p = "echoes." + ec.id + ".";
            y.set(p + "owner", ec.owner); y.set(p + "name", ec.ownerName); y.set(p + "world", ec.world);
            y.set(p + "x", ec.x); y.set(p + "y", ec.y); y.set(p + "z", ec.z); y.set(p + "created", ec.created); y.set(p + "items", ec.items);
        }
        return y;
    }

    // ================================================================= the Rim Trader

    void traderTick() {
        Entity t = trader == null ? null : Bukkit.getEntity(trader);
        if (t != null && t.isValid()) {
            if (System.currentTimeMillis() > traderUntil) { leaveTrader(t); }
            else if (tick % 10 == 0) t.getWorld().spawnParticle(Particle.CRIMSON_SPORE, t.getLocation().add(0, 1.2, 0), 6, 0.4, 0.6, 0.4, 0);
            return;
        }
        trader = null;
        if (tick % 1200 != 0) return;
        List<Player> out = playersOut();
        if (out.isEmpty() || random.nextDouble() >= cfg("trader.chance-per-minute", 0.04)) return;
        spawnTrader(out.get(random.nextInt(out.size())));
    }

    WanderingTrader spawnTrader(Player near) {
        Location at = groundNear(near.getLocation(), 8, 20);
        if (at == null) at = near.getLocation();
        WanderingTrader w = (WanderingTrader) at.getWorld().spawnEntity(at, EntityType.WANDERING_TRADER, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(TRADER));
        w.customName(Component.text("The Rim Trader", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        w.setCustomNameVisible(true);
        w.setPersistent(false);
        w.setInvulnerable(true);
        int minutes = (int) cfg("trader.minutes", 15);
        try { w.setDespawnDelay(minutes * 1200 + 200); } catch (RuntimeException ignored) { }
        w.setRecipes(recipes());
        trader = w.getUniqueId();
        traderUntil = System.currentTimeMillis() + minutes * 60_000L;
        at.getWorld().playSound(at, Sound.ENTITY_WANDERING_TRADER_REAPPEARED, 1.5f, 0.6f);
        at.getWorld().spawnParticle(Particle.CRIMSON_SPORE, at.clone().add(0, 1, 0), 80, 0.6, 1, 0.6, 0);
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < 120 * 120) p.sendMessage(ChatColor.RED + "The Rim Trader has set up nearby. " + ChatColor.GRAY + "He leaves in " + minutes + " minutes.");
        return w;
    }

    void leaveTrader(Entity t) {
        t.getWorld().spawnParticle(Particle.CRIMSON_SPORE, t.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0);
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_WANDERING_TRADER_DISAPPEARED, 1.2f, 0.6f);
        t.remove();
        trader = null;
    }

    Location groundNear(Location c, double min, double max) {
        World w = c.getWorld();
        for (int k = 0; k < 16; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = c.getBlockX() + (int) (Math.cos(a) * d), z = c.getBlockZ() + (int) (Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = 6; dy >= -6; dy--) {
                Block b = w.getBlockAt(x, c.getBlockY() + dy, z);
                if (b.getRelative(0, -1, 0).getType().isSolid() && Emotes.open(b) && Emotes.open(b.getRelative(0, 1, 0)) && !b.isLiquid())
                    return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    List<MerchantRecipe> recipes() {
        List<MerchantRecipe> out = new ArrayList<>();
        out.add(offer(plugin.farlandsNight.sigil(1), 2, new ItemStack(Material.DIAMOND, 24), new ItemStack(Material.EMERALD, 32)));
        out.add(offer(FaultlineItems.MythicGoodieBagItem.create(plugin), 2, new ItemStack(Material.DIAMOND, 28), new ItemStack(Material.NETHERITE_INGOT, 1)));
        ItemStack goodie = FaultlineItems.GoodieBagItem.create(plugin); goodie.setAmount(2);
        out.add(offer(goodie, 4, new ItemStack(Material.EMERALD, 20)));
        if (plugin.darkLoot != null) out.add(offer(plugin.darkLoot.bag(), 3, new ItemStack(Material.DIAMOND, 10), new ItemStack(Material.EMERALD, 10)));
        if (plugin.enchants != null) {
            String id = ToiletRaid.ENCHANTS[random.nextInt(ToiletRaid.ENCHANTS.length)];
            ItemStack book = plugin.enchants.book(id, 1);
            if (book != null) out.add(offer(book, 2, new ItemStack(Material.DIAMOND, 20), new ItemStack(Material.EMERALD, 16)));
        }
        out.add(offer(new ItemStack(Material.TOTEM_OF_UNDYING), 3, new ItemStack(Material.EMERALD, 24), new ItemStack(Material.DIAMOND, 8)));
        out.add(offer(new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 3, new ItemStack(Material.DIAMOND, 12)));
        out.add(offer(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), 1, new ItemStack(Material.DIAMOND, 32)));
        out.add(offer(new ItemStack(Material.EXPERIENCE_BOTTLE, 32), 4, new ItemStack(Material.EMERALD, 12)));
        out.add(offer(new ItemStack(Material.ENDER_PEARL, 8), 4, new ItemStack(Material.EMERALD, 8)));
        return out;
    }

    static MerchantRecipe offer(ItemStack result, int uses, ItemStack... cost) {
        MerchantRecipe r = new MerchantRecipe(result, 0, uses, false, 0, 0);
        for (ItemStack c : cost) {
            if (c.getAmount() > c.getMaxStackSize()) c.setAmount(c.getMaxStackSize());
            r.addIngredient(c);
        }
        return r;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTraderHurt(EntityDamageEvent e) { if (e.getEntity().getScoreboardTags().contains(TRADER)) e.setCancelled(true); }

    // ================================================================= loot on top of the datapack's tables

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        NamespacedKey k = e.getLootTable().getKey();
        if (!"faultline".equals(k.getNamespace()) || !k.getKey().startsWith("chests/farlands_")) return;
        String kind = k.getKey().substring("chests/farlands_".length());
        List<ItemStack> add = new ArrayList<>();
        boolean deep = kind.equals("vault_deep"), vault = kind.startsWith("vault");
        if (random.nextDouble() < (deep ? 0.6 : vault ? 0.4 : 0.15)) { ItemStack g = FaultlineItems.GoodieBagItem.create(plugin); g.setAmount(deep ? 2 : 1); add.add(g); }
        if (random.nextDouble() < (deep ? 0.18 : vault ? 0.07 : 0)) add.add(FaultlineItems.MythicGoodieBagItem.create(plugin));
        if (plugin.darkLoot != null && random.nextDouble() < (deep ? 0.35 : vault ? 0.2 : 0.05)) add.add(plugin.darkLoot.bag());
        if (plugin.enchants != null && random.nextDouble() < (deep ? 0.3 : vault ? 0.15 : 0.03)) {
            ItemStack b = plugin.enchants.book(ToiletRaid.ENCHANTS[random.nextInt(ToiletRaid.ENCHANTS.length)], 1);
            if (b != null) add.add(b);
        }
        if (plugin.darkLoot != null && random.nextDouble() < (deep ? 0.12 : vault ? 0.05 : 0.01)) { ItemStack a = plugin.darkLoot.accessory(); if (a != null) add.add(a); }
        if (plugin.farlandsNight != null && random.nextDouble() < (deep ? 0.08 : vault ? 0.03 : 0)) add.add(plugin.farlandsNight.sigil(1));
        if (kind.equals("camp") && random.nextDouble() < 0.75) add.add(journal());
        e.getLoot().addAll(add);
    }

    static final String[][] JOURNALS = {
            {"Day 1. We made it past the last map. The walls go up forever out here, and the sky is the wrong colour.",
                    "Day 3. Toma says he saw an island in the air. I told him to sleep. Then I saw it too.",
                    "Day 4. Something big walks between the walls at night. Stone that moves like it's alive. The ground shakes and the blocks around it flicker.",
                    "Day 5. We're turning back in the morning."},
            {"The vaults are real. Old builders sealed rooms inside the walls. Look for the chiseled stone on the wall tops.",
                    "Dig straight down from it and you'll hit the room. The deeper out you go, the better they get.",
                    "Watch the chests. One of them bit Oren's hand off."},
            {"The spiders here don't walk. They wait on the walls. You hear them before you see them.",
                    "Don't stand under a ledge. Don't stand still.",
                    "I've lost Maren. I keep seeing her out past the camp, but it isn't her. It just wears her face."},
            {"Met a trader out here. Red coat, no name. Sold me a sigil and told me not to use it.",
                    "He said it makes the rain bleed, and that everything out here gets hungrier when it does.",
                    "I'm going to use it."},
    };

    ItemStack journal() {
        ItemStack b = new ItemStack(Material.WRITTEN_BOOK);
        if (b.getItemMeta() instanceof BookMeta m) {
            String[] pages = JOURNALS[random.nextInt(JOURNALS.length)];
            m.setTitle("Lost Explorer's Journal");
            m.setAuthor("A lost explorer");
            m.setPages(List.of(pages));
            b.setItemMeta(m);
        }
        return b;
    }

    // ================================================================= music

    /**
     * The Farlands' music ("Underground Crimson", in FaultlineDeepMusic.zip), played to each player out there and looped
     * (`farlands.music-seconds` 161). The datapack's biome music alone was unreliable: it only switches once the song playing
     * ends, needs the datapack update (2 restarts), and starts after a random wait. Stops when you leave; the Spider Nest's own
     * music wins inside a nest.
     */
    void musicTick() {
        if (!plugin.getConfig().getBoolean("farlands.music", true)) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = inCached(p) && !inNest(p) && !bossMusic(p);
            Long at = musicAt.get(p.getUniqueId());
            if (!in) {
                if (at != null) { p.stopSound(MUSIC, SoundCategory.MUSIC); musicAt.remove(p.getUniqueId()); }
                continue;
            }
            for (String k : VANILLA_MUSIC) p.stopSound(k, SoundCategory.MUSIC);   // the game's own tracks never play over it
            if (at == null || now - at > cfg("music-seconds", 161.5) * 1000) {
                p.stopSound(SoundCategory.MUSIC);   // the game's own music would play over it
                p.playSound(p, MUSIC, SoundCategory.MUSIC, 1f, 1f);
                musicAt.put(p.getUniqueId(), now);
            }
        }
        musicAt.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private java.lang.reflect.Method bossMusicM;
    private boolean bossMusicLooked;

    /** AUDIT: a boss's music (FaultlineBosses: Jacob, the Kraken...) wins; ours would cut it off. */
    boolean bossMusic(Player p) {
        try {
            if (!bossMusicLooked) {
                bossMusicLooked = true;
                org.bukkit.plugin.Plugin b = Bukkit.getPluginManager().getPlugin("FaultlineBosses");
                if (b != null) bossMusicM = b.getClass().getMethod("hearingBossMusic", UUID.class);
            }
            return bossMusicM != null && Boolean.TRUE.equals(bossMusicM.invoke(null, p.getUniqueId()));
        } catch (Throwable t) { bossMusicM = null; return false; }
    }

    boolean inNest(Player p) {
        try { return plugin.spiderNest != null && plugin.spiderNest.nestAt(p.getLocation()) != null; } catch (RuntimeException e) { return false; }
    }

    @EventHandler
    public void onJoinMusic(org.bukkit.event.player.PlayerJoinEvent e) {
        musicAt.remove(e.getPlayer().getUniqueId());
        unspook(e.getPlayer());   // player time / weather aren't saved, but clear anything left from before a reload
    }

    @EventHandler
    public void onQuitSpooky(org.bukkit.event.player.PlayerQuitEvent e) { unspook(e.getPlayer()); }

    /** Sounds heard far off in the Farlands' night. */
    private static final String[] SPOOK = {
        "minecraft:ambient.cave", "minecraft:entity.warden.heartbeat", "minecraft:entity.ghast.ambient", "minecraft:entity.fox.screech",
        "minecraft:entity.phantom.ambient", "minecraft:block.sculk_shrieker.shriek", "minecraft:entity.enderman.stare",
        "minecraft:entity.zombie.ambient", "minecraft:entity.skeleton.ambient", "minecraft:entity.warden.ambient",
        "minecraft:ambient.soul_sand_valley.mood", "minecraft:ambient.basalt_deltas.mood", "minecraft:block.bell.resonate" };

    /**
     * Always night + rain in the Farlands, per player (`setPlayerTime` / `setPlayerWeather`: the server's own clock and weather
     * stay as they are, so spawning elsewhere is untouched), with far-off creepy sounds, ash in the air and silent lightning now
     * and then. No Darkness: you can still see. Leaving puts your own sky back.
     */
    void spookyTick() {
        boolean night = plugin.getConfig().getBoolean("farlands.always-night", true);
        boolean rain = plugin.getConfig().getBoolean("farlands.always-rain", true);
        boolean spook = plugin.getConfig().getBoolean("farlands.spooky", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!inCached(p)) { if (spooky.contains(id)) unspook(p); continue; }
            spooky.add(id);
            if (night && (p.isPlayerTimeRelative() || Math.abs(p.getPlayerTime() % 24000 - 18000) > 200)) p.setPlayerTime(18000, false);
            if (rain && p.getPlayerWeather() != WeatherType.DOWNFALL) p.setPlayerWeather(WeatherType.DOWNFALL);
            if (!spook) continue;
            Location l = p.getLocation();
            p.spawnParticle(Particle.ASH, l.clone().add(0, 2, 0), 18, 8, 4, 8, 0);
            p.spawnParticle(Particle.WHITE_ASH, l.clone().add(0, 2, 0), 8, 8, 4, 8, 0);
            int next = spookAt.getOrDefault(id, tick + 200 + random.nextInt(200));
            spookAt.putIfAbsent(id, next);
            if (tick < next) continue;
            spookAt.put(id, tick + 200 + random.nextInt(200));   // every 10-20 s
            double a = random.nextDouble() * Math.PI * 2, d = 14 + random.nextDouble() * 12;
            Location at = l.clone().add(Math.cos(a) * d, random.nextDouble() * 8 - 2, Math.sin(a) * d);
            String s = SPOOK[random.nextInt(SPOOK.length)];
            p.playSound(at, s, SoundCategory.AMBIENT, 0.55f + random.nextFloat() * 0.35f, 0.55f + random.nextFloat() * 0.35f);
            if (random.nextDouble() < cfg("spooky-lightning-chance", 0.2)) {
                double b = random.nextDouble() * Math.PI * 2, e = 30 + random.nextDouble() * 30;
                Location bolt = l.clone().add(Math.cos(b) * e, 0, Math.sin(b) * e);
                bolt.setY(l.getWorld().getHighestBlockYAt(bolt) + 1);
                if (l.getWorld().isChunkLoaded(bolt.getBlockX() >> 4, bolt.getBlockZ() >> 4)) l.getWorld().strikeLightningEffect(bolt);
            }
        }
        spooky.removeIf(u -> Bukkit.getPlayer(u) == null);
        spookAt.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    void unspook(Player p) {
        if (!spooky.remove(p.getUniqueId())) return;
        spookAt.remove(p.getUniqueId());
        try { p.resetPlayerTime(); p.resetPlayerWeather(); } catch (RuntimeException ignored) {}
    }

    // ================================================================= ticks

    void tickAll() {
        tick++;
        if (!on()) { if (!spooky.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) unspook(p); return; }
        try {
            if (tick % 20 == 0) inCache.clear();
            if (tick % 4 == 0 && !crawlers.isEmpty()) crawlerTick();
            if (tick % 20 == 0) echoTick();
            if (tick % 40 == 0) try { musicTick(); } catch (RuntimeException ex) {
                if (tick - musicErrAt > 1200) { musicErrAt = tick; plugin.getLogger().warning("[Farlands] music: " + ex); }
            }
            if (tick % 40 == 20) try { spookyTick(); } catch (RuntimeException ex) {
                if (tick - musicErrAt > 1200) { musicErrAt = tick; plugin.getLogger().warning("[Farlands] night: " + ex); }
            }
            traderTick();
            if (plugin.farlandsNight != null) plugin.farlandsNight.tick();
            if (tick % 600 == 0) {
                for (Player p : playersOut()) {
                    if (random.nextDouble() < cfg("crawlers.pack-chance", 0.3)) crawlerPack(p);
                    if (random.nextDouble() < cfg("ghasts.chance", 0.25)) ghasts(p);
                }
                pounceAt.values().removeIf(t -> t < tick);
                dropping.keySet().removeIf(id -> !crawlers.contains(id));
            }
        } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[Farlands] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) unspook(p);
        if (plugin.farlandsNight != null) plugin.farlandsNight.shutdown();
        for (Echo ec : echoes) { Entity b = ec.entity == null ? null : Bukkit.getEntity(ec.entity); if (b != null) b.remove(); ec.entity = null; }
        Entity t = trader == null ? null : Bukkit.getEntity(trader);
        if (t != null) t.remove();
        saveEchoes();
    }

    // ================================================================= command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        Player p = s instanceof Player pl ? pl : null;
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (!s.hasPermission(PERM)) {
            s.sendMessage(ChatColor.RED + "The Farlands: the outer ring of the world, past 3280 blocks. Hard, and the best loot there is.");
            return true;
        }
        switch (sub) {
            case "trader" -> {
                if (p == null) { s.sendMessage("Players only."); return true; }
                Entity t = trader == null ? null : Bukkit.getEntity(trader);
                if (t != null) leaveTrader(t);
                spawnTrader(p);
                s.sendMessage(ChatColor.GREEN + "The Rim Trader is here.");
            }
            case "crawler" -> {
                if (p == null) return true;
                Location at = wallSpot(p);
                Vector fwd = p.getLocation().getDirection().setY(0);
                if (at == null) at = p.getLocation().add(fwd.lengthSquared() < 1e-4 ? new Vector(3, 0, 0) : fwd.normalize().multiply(3));
                Spider sp = (Spider) p.getWorld().spawnEntity(at, EntityType.SPIDER, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(CRAWLER));
                crawler(sp);
                s.sendMessage(ChatColor.GREEN + "A Wall Crawler.");
            }
            case "music" -> {   // diagnostics: what the music code decides for you, then the track played both ways
                if (p == null) return true;
                Long at = musicAt.get(p.getUniqueId());
                s.sendMessage(ChatColor.GRAY + "music on: " + plugin.getConfig().getBoolean("farlands.music", true) + ", in the Farlands: " + in(p.getLocation())
                        + ", in a nest: " + inNest(p) + ", boss music: " + bossMusic(p) + ", started " + (at == null ? "never" : ((System.currentTimeMillis() - at) / 1000) + " s ago"));
                String how = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "entity";
                p.stopSound(SoundCategory.MUSIC);
                if (how.equals("spot")) p.playSound(p.getLocation(), MUSIC, SoundCategory.MUSIC, 1f, 1f);
                else p.playSound(p, MUSIC, SoundCategory.MUSIC, 1f, 1f);
                musicAt.put(p.getUniqueId(), System.currentTimeMillis());
                s.sendMessage(ChatColor.YELLOW + "Played the Farlands track (" + how + "). Hear it? Try /farlands music spot too.");
            }
            case "bloodrain" -> {
                FarlandsNight n = plugin.farlandsNight;
                if (a.length > 1 && a[1].equalsIgnoreCase("stop")) { n.stopBlood(false); s.sendMessage(ChatColor.GREEN + "Blood Rain stopped."); }
                else if (a.length > 1 && a[1].equalsIgnoreCase("start")) { n.startBlood(s.getName()); s.sendMessage(ChatColor.GREEN + "Blood Rain started."); }
                else s.sendMessage(ChatColor.GRAY + n.status() + ChatColor.DARK_GRAY + "  (/farlands bloodrain start|stop)");
            }
            case "sigil" -> {
                int n = 1; Player to = p;
                for (int i = 1; i < a.length; i++) {
                    Player online = Bukkit.getPlayerExact(a[i]);
                    if (online != null) to = online;
                    else try { n = Math.max(1, Math.min(64, Integer.parseInt(a[i]))); } catch (NumberFormatException ignored) { }
                }
                if (to == null) { s.sendMessage("Who should get it?"); return true; }
                Player t2 = to;
                t2.getInventory().addItem(plugin.farlandsNight.sigil(n)).values().forEach(left -> t2.getWorld().dropItemNaturally(t2.getLocation(), left));
                s.sendMessage(ChatColor.GREEN + "Gave " + n + " Rim Sigil(s) to " + to.getName() + ".");
            }
            case "watcher" -> {
                if (p == null) return true;
                s.sendMessage(plugin.farlandsNight.spawnWatcher(p, a.length > 1 && a[1].equalsIgnoreCase("close")) != null ? ChatColor.GREEN + "Something is watching you." : ChatColor.RED + "No wall top above you nearby.");
            }
            case "collapse" -> {
                if (p == null) return true;
                s.sendMessage(plugin.farlandsNight.collapseUnder(p) ? ChatColor.GREEN + "The ground cracks." : ChatColor.RED + "Nothing here can crumble (or a chest is near).");
            }
            case "ghast" -> { if (p != null) { ghasts(p); s.sendMessage(ChatColor.GREEN + "Tried to bring a ghast."); } }
            case "echoes" -> {
                s.sendMessage(ChatColor.GRAY + "" + echoes.size() + " echo(es):");
                for (Echo ec : echoes) s.sendMessage(ChatColor.DARK_GRAY + " " + ec.id + " " + ec.ownerName + " at " + Math.round(ec.x) + " " + Math.round(ec.y) + " " + Math.round(ec.z)
                        + (ec.entity != null ? ChatColor.RED + " (out)" : ""));
            }
            default -> {
                s.sendMessage(ChatColor.RED + "The Farlands " + ChatColor.GRAY + "(" + playersOut().size() + " player(s) out there)");
                s.sendMessage(ChatColor.GRAY + " Rim Trader: " + (trader != null ? "out" : "not out") + ", echoes: " + echoes.size()
                        + (p != null ? ", you are " + (in(p.getLocation()) ? "IN" : "not in") + " the Farlands" : ""));
                s.sendMessage(ChatColor.GRAY + " " + plugin.farlandsNight.status());
                s.sendMessage(ChatColor.DARK_GRAY + " /farlands trader | crawler | ghast | echoes | bloodrain | sigil | watcher | collapse");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM) || a.length != 1) return List.of();
        return Legendary.filter(List.of("status", "trader", "crawler", "ghast", "echoes", "music", "bloodrain", "sigil", "watcher", "collapse"), a[0]);
    }
}
