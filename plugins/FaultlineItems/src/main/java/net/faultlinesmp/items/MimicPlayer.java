package net.faultlinesmp.items;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * MIMIC PLAYER (Items 1.2.79, ADMIN ONLY: /mimicplayer, perm items.mimicplayer, default op; config mimic-player:).
 * An NPC that looks and fights like a real player.
 *
 * GEAR (1.2.79): the REAL player's: if they're online, what they have on right now; else what they had when they last
 * logged off (snapshots every minute + on quit, mimic_gear.yml). Their armour with its enchants (Protection IV counts:
 * armour points/toughness/knockback resistance from the pieces as attributes, Protection / Fire / Blast / Projectile
 * Protection + Feather Falling as the same EPF reduction vanilla uses), their best sword and axe (damage, Sharpness, Fire
 * Aspect, Knockback), their bow (Power), their off hand, and their REAL counts of golden apples, enchanted golden apples,
 * ender pearls, splash Healing potions and totems. Their max health (20 for a normal player). No snapshot = mimic-player.kit.
 * Low and close with gaps left, it PEARLS AWAY to get room, then eats.
 * BODY: a MANNEQUIN (tag faultline_player_mimic_body) with an offline player's skin and name, their kingdom line above it
 * (like FaultlineKingdoms' head tags: kingdom colour or "Outlaw"), and the kit: armour, sword in hand, shield in the off
 * hand; the hotbar visibly switches (axe, bow, golden apple, potion, pearl).
 * BRAIN: an invisible VINDICATOR (tag faultline_player_mimic) that walks, paths and takes knockback. It carries NOTHING:
 * an invisible mob still shows its armour and items, which drew a second set of gear on top of the body (1.2.78 bug).
 * Not undead, so Regeneration and Instant Health work on it like on a player. Its own melee is switched off: every hit
 * is ours (`strike`), timed like a player's.
 *
 * Fighting (per `mimic-player.skill`: easy / normal / hard / godlike, see SKILLS):
 *  - real attack cooldown (sword 12.5 ticks, axe 20) and reach 3; waits for a charged hit (hit-select), crits while
 *    falling (x1.5, crit particles), sprint hits knock harder, then W-TAPS (backs off a few ticks to reset sprint);
 *  - JUMP CRITS: jumps in so it lands the hit on the way down;
 *  - circle-STRAFES at ~2.7 blocks, flips direction now and then, keeps facing you (head and body);
 *  - SHIELD: raises it (crouched) when you're close, facing it and its own hit isn't ready, lowers it to swing; it blocks
 *    melee and arrows from the front; an axe hit knocks it out for 5 s. If YOU block, it swaps to its axe and breaks
 *    your shield for 5 s;
 *  - heals like a player: SPLASH POTIONS of Healing II at its feet (looks down, throws), GOLDEN APPLES when it has
 *    room (stands, eats 1.6 s), natural regen out of combat; one TOTEM pop;
 *  - ENDER PEARLS to chase anyone 14+ blocks off, and to escape when low with no heals left (then runs and regens);
 *  - BOW at 8-20 blocks with sight: draws, leads its shot by your speed;
 *  - plays player sounds (hurt, death, crit, eat, burp, splash).
 * TECHS (1.2.79, all from what the player really carries): MACE (wind-charge slams with air steering, Wind Burst pogo
 * chains, stun slams = axe breaks your shield then the mace lands, elytra + rocket dives, sword/axe-to-mace swap when
 * falling into reach; real mace damage by fall height, Density, Breach), SWORD (hit-select, crit chains, W-tap/S-tap,
 * sprint hits; every hit is a crit or a sprint hit, never a sweep), AXE (shield breaks, axe crits), UHC (lava at your feet, cobwebs, rod combos, flint and steel, water
 * clutches, block pillars to escape). World techs put every block back and stop flows / fire spread / bucket pickups
 * (`mimic-player.world-techs`).
 * 1.2.80 STYLES from the inventory (`style`): mace / nethop / diamond (3+ potions) / uhc / axe / sword. POT PvP: keeps its
 * buffs up from the potions it really carries (Strength, Speed, Fire Res, Turtle Master... splash at its feet or drunk),
 * throws its Slowness / Weakness / Poison / Harming splashes AT you with lead, re-totems its off hand. SWORD: spacing (sits
 * just outside your reach while its hit charges, steps in when it's ready), JUMP RESETS (jumps as your hit lands = less
 * knockback), snowballs / eggs at you when you sprint in. AXE: shield timing on YOUR charged swing. UHC: web + lava combo,
 * water on itself when it's burning, arrows counted (Infinity = no limit). Strength / Weakness change its damage.
 * Removing: /mimicplayer remove (the one you look at / nearest), remove <name>, remove all / clear.
 * Drops nothing (mimic-player.drops), no XP unless set. Non-persistent: /mimicplayer clear or a restart removes them.
 * `/mimicplayer [random|<player>] [count] [skill]`, `clear`, `list`. Bedrock may not draw the mannequin.
 */
final class MimicPlayer implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_player_mimic", BODY_TAG = "faultline_player_mimic_body", TAG_TAG = "faultline_player_mimic_tag";
    private final FaultlineItems plugin;
    private final Random random = new Random();

    /** How good it is: reaction (ticks), shield chance per tick, hit-select (cooldown before swinging), jump-crit chance,
     *  bow chance, aim spread, W-tap chance, strafe strength. */
    record Skill(String id, int reaction, double block, double hitSelect, double jumpCrit, double bow, double spread, double wtap, double strafe, double speed) { }

    static final Map<String, Skill> SKILLS = new LinkedHashMap<>();
    static {
        SKILLS.put("easy", new Skill("easy", 10, 0.01, 0.6, 0.15, 0.3, 3.0, 0.2, 0.06, 0.28));
        SKILLS.put("normal", new Skill("normal", 6, 0.03, 0.85, 0.45, 0.55, 1.5, 0.5, 0.10, 0.31));
        SKILLS.put("hard", new Skill("hard", 3, 0.05, 0.92, 0.75, 0.75, 0.8, 0.8, 0.12, 0.33));
        SKILLS.put("godlike", new Skill("godlike", 1, 0.08, 0.97, 0.95, 0.9, 0.3, 1.0, 0.14, 0.35));
    }

    static final class Mimic {
        Vindicator brain;
        Mannequin body;
        TextDisplay tag;
        UUID who;
        String name;
        Skill skill;
        Player target;
        int t, strafeDir = 1, strafeFlip, lastHit = -100, lastHurt = -100, reactAt;
        int webTicks, chaseStuck, flyTicks, nextRocket, flyCooldown;
        boolean flying;
        int blockUntil, shieldOffUntil, eatUntil, drinkUntil, drawUntil, pearlReady, bowReady, wtapUntil, fleeUntil, axeUntil;
        int apples, gapples, pots, pearlsLeft, totems = 1, wind, lava, water, webs, blocks, rockets, arrows, throwables;
        final List<ItemStack> buffs = new ArrayList<>(), debuffs = new ArrayList<>();
        int buffReady, debuffReady, throwReady, jumpReset = -1, potUntil, lookDownUntil;
        ItemStack potting;
        String style = "sword";
        Gear gear;
        boolean sprintHit, eatAfterPearl, eatingGod, slam, dive, stunSlam;
        int maceReady, lavaReady, webReady, rodReady, fireReady, pillarReady, pillarCount, jumpAt = -1, clutchUntil, noFallUntil;
        float yaw;
        String held = "sword";
    }

    final Map<UUID, Mimic> byBrain = new HashMap<>(), byBody = new HashMap<>();
    private final Set<UUID> pearls = new HashSet<>(), potions = new HashSet<>();
    private boolean striking;

    // ================================================================ the real player's gear

    /** What a player carries that the mimic uses. */
    static final class Gear {
        ItemStack helmet, chest, legs, boots, weapon, axe, bow, offhand, mace;
        int gaps, egaps, pearls, pots, totems, wind, lava, water, webs, blocks, rockets;
        boolean rod, flint, elytra;
        int arrows, throwables;
        final List<ItemStack> buffs = new ArrayList<>(), debuffs = new ArrayList<>(); // one entry per potion they carry
        Material block = Material.COBBLESTONE;
        double maxHealth = 20;
    }

    final Map<UUID, Gear> snapshots = new HashMap<>();
    private java.io.File gearFile;

    static boolean weapon(Material m) { String n = m.name(); return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR") || n.equals("TRIDENT"); }

    static double score(ItemStack it) {
        if (it == null) return -1;
        return baseDamage(it.getType()) + level(it, "sharpness") * 0.6 + level(it, "fire_aspect") * 0.3;
    }

    static int level(ItemStack it, String id) {
        if (it == null || !it.hasItemMeta()) return 0;
        for (var e : it.getEnchantments().entrySet()) if (e.getKey().getKey().getKey().equals(id)) return e.getValue();
        return 0;
    }

    /** Vanilla attack damage of a weapon (sword/axe tiers). */
    static double baseDamage(Material m) {
        String n = m.name();
        String t = n.startsWith("NETHERITE") ? "N" : n.startsWith("DIAMOND") ? "D" : n.startsWith("IRON") ? "I" : n.startsWith("STONE") ? "S" : n.startsWith("GOLDEN") ? "G" : n.startsWith("WOODEN") ? "W" : "?";
        if (n.endsWith("_SWORD")) return switch (t) { case "N" -> 8; case "D" -> 7; case "I" -> 6; case "S" -> 5; default -> 4; };
        if (n.endsWith("_AXE")) return switch (t) { case "N" -> 10; case "D", "I", "S" -> 9; default -> 7; };
        if (n.equals("TRIDENT")) return 9;
        if (n.equals("MACE")) return 6;
        if (n.endsWith("_SPEAR")) return 5;
        return 1;
    }

    static Gear capture(Player p) {
        Gear g = new Gear();
        var inv = p.getInventory();
        g.helmet = copy(inv.getHelmet()); g.chest = copy(inv.getChestplate()); g.legs = copy(inv.getLeggings()); g.boots = copy(inv.getBoots());
        g.offhand = copy(inv.getItemInOffHand());
        ItemStack held = inv.getItemInMainHand();
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            Material m = it.getType();
            switch (m) {
                case GOLDEN_APPLE -> g.gaps += it.getAmount();
                case ENCHANTED_GOLDEN_APPLE -> g.egaps += it.getAmount();
                case ENDER_PEARL -> g.pearls += it.getAmount();
                case TOTEM_OF_UNDYING -> g.totems += it.getAmount();
                case MACE -> { if (g.mace == null || maceScore(it) > maceScore(g.mace)) g.mace = it.clone(); }
                case WIND_CHARGE -> g.wind += it.getAmount();
                case LAVA_BUCKET -> g.lava++;
                case WATER_BUCKET -> g.water++;
                case COBWEB -> g.webs += it.getAmount();
                case FISHING_ROD -> g.rod = true;
                case FLINT_AND_STEEL -> g.flint = true;
                case FIREWORK_ROCKET -> g.rockets += it.getAmount();
                case ELYTRA -> g.elytra = true;
                case BOW -> { if (g.bow == null || level(it, "power") > level(g.bow, "power")) g.bow = it.clone(); }
                case SPLASH_POTION, POTION, LINGERING_POTION -> {
                    if (m == Material.SPLASH_POTION && healing(it)) { g.pots += it.getAmount(); break; }
                    List<PotionEffect> fx = effects(it);
                    boolean bad = fx.stream().anyMatch(e -> BAD.contains(e.getType())), good = fx.stream().anyMatch(e -> GOOD.contains(e.getType()));
                    for (int i = 0; i < it.getAmount(); i++) {
                        ItemStack one = it.clone(); one.setAmount(1);
                        if (bad && m != Material.POTION) g.debuffs.add(one); else if (good && !bad) g.buffs.add(one);
                    }
                }
                case ARROW, SPECTRAL_ARROW, TIPPED_ARROW -> g.arrows += it.getAmount();
                case SNOWBALL, EGG -> g.throwables += it.getAmount();
                default -> {
                    if (m.name().endsWith("_AXE")) { if (score(it) > score(g.axe)) g.axe = it.clone(); }
                    else if (weapon(m) && score(it) > score(g.weapon)) g.weapon = it.clone();
                    else if (m.isBlock() && m.isSolid() && !m.hasGravity() && !m.isInteractable() && m.isOccluding() && it.getAmount() > g.blocks) { g.blocks = it.getAmount(); g.block = m; }
                }
            }
        }
        // armour: what they wear, else the best spare piece of that slot in their inventory
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            String n = it.getType().name();
            if (n.endsWith("_HELMET") && armourScore(it) > armourScore(g.helmet) && inv.getHelmet() == null) g.helmet = it.clone();
            else if (n.endsWith("_CHESTPLATE") && armourScore(it) > armourScore(g.chest) && inv.getChestplate() == null) g.chest = it.clone();
            else if (n.endsWith("_LEGGINGS") && armourScore(it) > armourScore(g.legs) && inv.getLeggings() == null) g.legs = it.clone();
            else if (n.endsWith("_BOOTS") && armourScore(it) > armourScore(g.boots) && inv.getBoots() == null) g.boots = it.clone();
        }
        if (held != null && weapon(held.getType()) && !held.getType().name().endsWith("_AXE")) g.weapon = held.clone(); // what they're holding right now wins
        if (g.weapon == null && g.axe != null) { g.weapon = g.axe; }
        if (g.chest != null && g.chest.getType() == Material.ELYTRA) g.elytra = true;
        try { g.maxHealth = p.getAttribute(Attribute.MAX_HEALTH).getValue(); } catch (RuntimeException e) { g.maxHealth = 20; }
        return g;
    }

    static final Set<PotionEffectType> GOOD = Set.of(PotionEffectType.SPEED, PotionEffectType.STRENGTH, PotionEffectType.FIRE_RESISTANCE,
            PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE, PotionEffectType.ABSORPTION, PotionEffectType.INSTANT_HEALTH,
            PotionEffectType.JUMP_BOOST, PotionEffectType.INVISIBILITY, PotionEffectType.WATER_BREATHING, PotionEffectType.SLOW_FALLING, PotionEffectType.HEALTH_BOOST);
    static final Set<PotionEffectType> BAD = Set.of(PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON,
            PotionEffectType.INSTANT_DAMAGE, PotionEffectType.WITHER, PotionEffectType.BLINDNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA);

    /** Every effect a potion item gives (its base type + custom effects). */
    static List<PotionEffect> effects(ItemStack it) {
        List<PotionEffect> out = new ArrayList<>();
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return out;
        try { if (pm.getBasePotionType() != null) out.addAll(pm.getBasePotionType().getPotionEffects()); } catch (RuntimeException ignored) { }
        out.addAll(pm.getCustomEffects());
        return out;
    }

    static double armourScore(ItemStack it) {
        if (it == null) return -1;
        double[] a = armorOf(it);
        return a[0] * 10 + a[1] * 3 + level(it, "protection") * 2 + level(it, "blast_protection") + level(it, "projectile_protection");
    }

    static int maceScore(ItemStack it) { return level(it, "density") + level(it, "breach") + 2 * level(it, "wind_burst"); }

    static ItemStack copy(ItemStack it) { return it == null || it.getType().isAir() ? null : it.clone(); }

    static boolean healing(ItemStack it) {
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return false;
        try {
            PotionType t = pm.getBasePotionType();
            if (t == PotionType.HEALING || t == PotionType.STRONG_HEALING) return true;
        } catch (RuntimeException ignored) { }
        for (PotionEffect e : pm.getCustomEffects()) if (e.getType().equals(PotionEffectType.INSTANT_HEALTH)) return true;
        return false;
    }

    /** Online = their gear right now; else the last snapshot (or null = the configured kit). */
    Gear gearFor(OfflinePlayer who) {
        if (who == null) return null;
        Player on = who.getPlayer();
        if (on != null && on.isOnline()) { Gear g = capture(on); snapshots.put(on.getUniqueId(), g); return g; }
        return snapshots.get(who.getUniqueId());
    }

    void loadGear() {
        gearFile = new java.io.File(plugin.getDataFolder(), "mimic_gear.yml");
        if (!gearFile.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(gearFile);
        for (String k : y.getKeys(false)) {
            try {
                var c = y.getConfigurationSection(k);
                if (c == null) continue;
                Gear g = new Gear();
                g.helmet = c.getItemStack("helmet"); g.chest = c.getItemStack("chest"); g.legs = c.getItemStack("legs"); g.boots = c.getItemStack("boots");
                g.weapon = c.getItemStack("weapon"); g.axe = c.getItemStack("axe"); g.bow = c.getItemStack("bow"); g.offhand = c.getItemStack("offhand");
                g.gaps = c.getInt("gaps"); g.egaps = c.getInt("egaps"); g.pearls = c.getInt("pearls"); g.pots = c.getInt("pots"); g.totems = c.getInt("totems");
                g.maxHealth = c.getDouble("max-health", 20);
                g.mace = c.getItemStack("mace");
                g.wind = c.getInt("wind"); g.lava = c.getInt("lava"); g.water = c.getInt("water"); g.webs = c.getInt("webs");
                g.blocks = c.getInt("blocks"); g.rockets = c.getInt("rockets");
                g.rod = c.getBoolean("rod"); g.flint = c.getBoolean("flint"); g.elytra = c.getBoolean("elytra");
                g.arrows = c.getInt("arrows"); g.throwables = c.getInt("throwables");
                for (Object o : c.getList("buffs", List.of())) if (o instanceof ItemStack is) g.buffs.add(is);
                for (Object o : c.getList("debuffs", List.of())) if (o instanceof ItemStack is) g.debuffs.add(is);
                Material bm = Material.matchMaterial(c.getString("block", "COBBLESTONE"));
                if (bm != null) g.block = bm;
                snapshots.put(UUID.fromString(k), g);
            } catch (RuntimeException ignored) { }
        }
    }

    void saveGear() {
        if (gearFile == null) return;
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e : snapshots.entrySet()) {
            String k = e.getKey().toString() + ".";
            Gear g = e.getValue();
            y.set(k + "helmet", g.helmet); y.set(k + "chest", g.chest); y.set(k + "legs", g.legs); y.set(k + "boots", g.boots);
            y.set(k + "weapon", g.weapon); y.set(k + "axe", g.axe); y.set(k + "bow", g.bow); y.set(k + "offhand", g.offhand);
            y.set(k + "gaps", g.gaps); y.set(k + "egaps", g.egaps); y.set(k + "pearls", g.pearls); y.set(k + "pots", g.pots); y.set(k + "totems", g.totems);
            y.set(k + "max-health", g.maxHealth);
            y.set(k + "mace", g.mace);
            y.set(k + "wind", g.wind); y.set(k + "lava", g.lava); y.set(k + "water", g.water); y.set(k + "webs", g.webs);
            y.set(k + "blocks", g.blocks); y.set(k + "rockets", g.rockets);
            y.set(k + "arrows", g.arrows); y.set(k + "throwables", g.throwables);
            y.set(k + "buffs", new ArrayList<>(g.buffs)); y.set(k + "debuffs", new ArrayList<>(g.debuffs));
            y.set(k + "rod", g.rod); y.set(k + "flint", g.flint); y.set(k + "elytra", g.elytra); y.set(k + "block", g.block.name());
        }
        try { y.save(gearFile); } catch (java.io.IOException ex) { plugin.getLogger().warning("[MimicPlayer] couldn't save mimic_gear.yml: " + ex.getMessage()); }
    }

    @EventHandler
    public void onQuitSnap(org.bukkit.event.player.PlayerQuitEvent e) {
        try { snapshots.put(e.getPlayer().getUniqueId(), capture(e.getPlayer())); saveGear(); } catch (RuntimeException ignored) { }
    }

    /** Armour points / toughness of a piece (vanilla values). */
    static double[] armorOf(ItemStack it) {
        if (it == null) return new double[]{0, 0, 0};
        String n = it.getType().name();
        int slot = n.endsWith("_HELMET") ? 0 : n.endsWith("_CHESTPLATE") ? 1 : n.endsWith("_LEGGINGS") ? 2 : n.endsWith("_BOOTS") ? 3 : -1;
        if (slot < 0) return new double[]{0, 0, 0};
        double[][] t = n.startsWith("NETHERITE") || n.startsWith("DIAMOND") ? new double[][]{{3}, {8}, {6}, {3}}
                : n.startsWith("IRON") || n.startsWith("CHAINMAIL") ? new double[][]{{2}, {n.startsWith("IRON") ? 6 : 5}, {n.startsWith("IRON") ? 5 : 4}, {n.startsWith("IRON") ? 2 : 1}}
                : n.startsWith("GOLDEN") ? new double[][]{{2}, {5}, {3}, {1}}
                : n.startsWith("TURTLE") ? new double[][]{{2}, {0}, {0}, {0}}
                : new double[][]{{1}, {3}, {2}, {1}}; // leather
        double tough = n.startsWith("NETHERITE") ? 3 : n.startsWith("DIAMOND") ? 2 : 0;
        double kb = n.startsWith("NETHERITE") ? 0.1 : 0;
        return new double[]{t[slot][0], tough, kb};
    }

    ItemStack[] armour(Mimic m) { Gear g = m.gear; return new ItemStack[]{g.helmet, g.chest, g.legs, g.boots}; }

    /** Vanilla's enchantment protection factor for this damage (capped 20, 4% each). */
    double epf(Mimic m, EntityDamageEvent.DamageCause c, boolean projectile) {
        int f = 0;
        for (ItemStack it : armour(m)) {
            if (it == null) continue;
            f += level(it, "protection");
            if (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK || c == EntityDamageEvent.DamageCause.LAVA) f += 2 * level(it, "fire_protection");
            if (c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) f += 2 * level(it, "blast_protection");
            if (projectile) f += 2 * level(it, "projectile_protection");
            if (c == EntityDamageEvent.DamageCause.FALL) f += 3 * level(it, "feather_falling");
        }
        return Math.min(20, f) * 0.04;
    }

    MimicPlayer(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        loadGear();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { // keep everyone's snapshot fresh (used once they log off)
            for (Player p : Bukkit.getOnlinePlayers()) try { snapshots.put(p.getUniqueId(), capture(p)); } catch (RuntimeException ignored) { }
        }, 200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveGear, 6000L, 6000L);
        Bukkit.getScheduler().runTask(plugin, () -> { // leftovers (non-persistent, but a /reload keeps entities)
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(BODY_TAG) || e.getScoreboardTags().contains(TAG_TAG)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("mimic-player." + k, d); }

    String tier() {
        String t = plugin.getConfig().getString("mimic-player.kit", "diamond").toLowerCase(Locale.ROOT);
        return t.equals("netherite") || t.equals("iron") ? t : "diamond";
    }

    // ================================================================ spawning

    Mimic spawn(Location at, OfflinePlayer who, Skill skill) {
        World w = at.getWorld();
        Mimic m = new Mimic();
        m.name = who != null && who.getName() != null ? who.getName() : "Steve";
        m.who = who == null ? null : who.getUniqueId();
        m.skill = skill;
        m.gear = gearFor(who);
        if (m.gear == null) m.gear = kitGear();
        else if (plugin.getConfig().getBoolean("mimic-player.kit-when-bare", true)) { // no armour at all: the kit's pieces
            Gear k = null;
            if (m.gear.helmet == null && m.gear.chest == null && m.gear.legs == null && m.gear.boots == null) k = kitGear();
            if (k != null) { m.gear.helmet = k.helmet; m.gear.chest = k.chest; m.gear.legs = k.legs; m.gear.boots = k.boots; }
        }
        m.apples = m.gear.gaps; m.gapples = m.gear.egaps; m.pots = m.gear.pots; m.pearlsLeft = m.gear.pearls; m.totems = m.gear.totems;
        m.arrows = m.gear.arrows; m.throwables = m.gear.throwables;
        m.buffs.addAll(m.gear.buffs); m.debuffs.addAll(m.gear.debuffs);
        m.style = style(m.gear);
        m.wind = m.gear.wind; m.lava = m.gear.lava; m.water = m.gear.water; m.webs = m.gear.webs; m.blocks = m.gear.blocks; m.rockets = m.gear.rockets;
        Vindicator v = (Vindicator) w.spawnEntity(at, EntityType.VINDICATOR, false);
        v.addScoreboardTag(TAG);
        v.setPersistent(false);
        v.setRemoveWhenFarAway(false);
        v.setSilent(true);
        v.setCanPickupItems(false);
        Emotes.q(() -> v.setCanJoinRaid(false));
        Emotes.q(() -> v.setPatrolLeader(false));
        v.customName(Component.text(m.name));
        v.setCustomNameVisible(false);
        // no vanilla mob AI (wandering, its own walk-to-you-and-swing): only our player-like movement moves it
        Emotes.q(() -> Bukkit.getMobGoals().removeAllGoals(v));
        v.getEquipment().clear(); // NOTHING on the brain: an invisible mob still draws its gear
        for (EquipmentSlot s : EquipmentSlot.values()) try { v.getEquipment().setDropChance(s, 0f); } catch (RuntimeException ignored) { }
        v.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        double hp = cfg("health", 0) > 0 ? cfg("health", 0) : m.gear.maxHealth; // a real player's health
        attr(v, Attribute.MAX_HEALTH, hp);
        v.setHealth(hp);
        attr(v, Attribute.MOVEMENT_SPEED, skill.speed());
        attr(v, Attribute.FOLLOW_RANGE, Math.min(2048, range()));
        attr(v, Attribute.ATTACK_DAMAGE, 0);
        // their armour's protection, since the brain wears nothing: armour points, toughness, knockback resistance
        double ar = 0, tough = 0, kb = 0;
        for (ItemStack it : armour(m)) { double[] a = armorOf(it); ar += a[0]; tough += a[1]; kb += a[2]; }
        attr(v, Attribute.ARMOR, ar);
        attr(v, Attribute.ARMOR_TOUGHNESS, tough);
        attr(v, Attribute.KNOCKBACK_RESISTANCE, kb);
        Mannequin b = (Mannequin) w.spawnEntity(at, EntityType.MANNEQUIN, false);
        b.addScoreboardTag(BODY_TAG);
        Emotes.q(() -> b.setPersistent(false));
        Emotes.q(() -> b.setGravity(false));
        Emotes.q(() -> b.setCollidable(false));
        Emotes.q(() -> b.setDescription(null));
        Emotes.q(() -> b.customName(Component.text(m.name)));
        Emotes.q(() -> b.setCustomNameVisible(true));
        dress(m, b.getEquipment());
        if (who != null) {
            try {
                PlayerProfile pp = who.getPlayerProfile();
                b.setProfile(ResolvableProfile.resolvableProfile(pp));
                if (!pp.hasTextures()) pp.update().thenAccept(full -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (b.isValid()) Emotes.q(() -> b.setProfile(ResolvableProfile.resolvableProfile(full)));
                }));
            } catch (RuntimeException | LinkageError ignored) { }
        }
        m.brain = v; m.body = b;
        m.yaw = at.getYaw();
        if (plugin.getConfig().getBoolean("mimic-player.kingdom-tag", true)) m.tag = kingdomTag(m, at);
        byBrain.put(v.getUniqueId(), m);
        byBody.put(b.getUniqueId(), m);
        return m;
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private void dress(Mimic m, EntityEquipment eq) {
        Gear g = m.gear;
        eq.setHelmet(g.helmet == null ? null : g.helmet.clone());
        eq.setChestplate(g.chest == null ? null : g.chest.clone());
        eq.setLeggings(g.legs == null ? null : g.legs.clone());
        eq.setBoots(g.boots == null ? null : g.boots.clone());
        eq.setItemInMainHand(hotbar(m, "sword"));
        eq.setItemInOffHand(g.offhand == null ? null : g.offhand.clone());
        for (EquipmentSlot s : EquipmentSlot.values()) try { eq.setDropChance(s, 0f); } catch (RuntimeException ignored) { }
    }

    private static ItemStack enchanted(Material m) {
        ItemStack it = new ItemStack(m);
        try { it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION, 2); } catch (RuntimeException ignored) { }
        return it;
    }

    /** The configured kit, for a player with no snapshot. */
    Gear kitGear() {
        Gear g = new Gear();
        String pre = tier().toUpperCase(Locale.ROOT);
        g.helmet = enchanted(Material.valueOf(pre + "_HELMET")); g.chest = enchanted(Material.valueOf(pre + "_CHESTPLATE"));
        g.legs = enchanted(Material.valueOf(pre + "_LEGGINGS")); g.boots = enchanted(Material.valueOf(pre + "_BOOTS"));
        g.weapon = new ItemStack(Material.valueOf(pre + "_SWORD"));
        try { g.weapon.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, (int) cfg("sharpness", 2)); } catch (RuntimeException ignored) { }
        g.axe = new ItemStack(Material.valueOf(pre + "_AXE"));
        g.bow = new ItemStack(Material.BOW);
        g.offhand = new ItemStack(Material.SHIELD);
        g.gaps = (int) cfg("golden-apples", 4); g.pots = (int) cfg("healing-potions", 3); g.pearls = (int) cfg("ender-pearls", 8); g.totems = 1;
        g.water = 1; g.webs = 4; g.blocks = 32; g.rod = true; g.arrows = 32;
        for (PotionType pt : new PotionType[]{PotionType.STRONG_STRENGTH, PotionType.STRONG_SWIFTNESS, PotionType.LONG_FIRE_RESISTANCE}) {
            ItemStack b = new ItemStack(Material.SPLASH_POTION);
            if (b.getItemMeta() instanceof PotionMeta pm) { try { pm.setBasePotionType(pt); } catch (RuntimeException ignored) { } b.setItemMeta(pm); }
            g.buffs.add(b);
        }
        return g;
    }

    ItemStack hotbar(Mimic m, String what) {
        Gear g = m.gear;
        ItemStack it = switch (what) {
            case "axe" -> g.axe;
            case "bow" -> g.bow;
            case "apple" -> new ItemStack(Material.GOLDEN_APPLE);
            case "gapple" -> new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
            case "pearl" -> new ItemStack(Material.ENDER_PEARL);
            case "potion" -> healingPotion();
            case "mace" -> g.mace;
            case "wind" -> new ItemStack(Material.WIND_CHARGE);
            case "lava" -> new ItemStack(Material.LAVA_BUCKET);
            case "water" -> new ItemStack(Material.WATER_BUCKET);
            case "web" -> new ItemStack(Material.COBWEB);
            case "rod" -> new ItemStack(Material.FISHING_ROD);
            case "flint" -> new ItemStack(Material.FLINT_AND_STEEL);
            case "block" -> new ItemStack(g.block);
            case "rocket" -> new ItemStack(Material.FIREWORK_ROCKET);
            default -> g.weapon;
        };
        return it == null ? null : it.clone();
    }

    ItemStack weaponHeld(Mimic m) { return m.held.equals("axe") ? m.gear.axe : m.held.equals("mace") ? m.gear.mace : m.gear.weapon; }

    /** What kind of fighter their inventory says they are (mace / nethop / diamond / uhc / axe / sword). */
    static String style(Gear g) {
        if (g.mace != null) return "mace";
        boolean neth = g.chest != null && g.chest.getType().name().startsWith("NETHERITE");
        boolean potions = g.buffs.size() + g.pots + g.debuffs.size() >= 3;
        if (potions) return neth ? "nethop" : "diamond";
        if (g.lava > 0 || g.webs > 0 || g.rod || g.blocks >= 16) return "uhc";
        if (g.axe != null && g.offhand != null && g.offhand.getType() == Material.SHIELD) return "axe";
        return "sword";
    }

    boolean hasShield(Mimic m) { return m.gear.offhand != null && m.gear.offhand.getType() == Material.SHIELD; }

    static ItemStack healingPotion() {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        if (it.getItemMeta() instanceof PotionMeta pm) { try { pm.setBasePotionType(PotionType.STRONG_HEALING); } catch (RuntimeException ignored) { } it.setItemMeta(pm); }
        return it;
    }

    void hold(Mimic m, String what) {
        if (what.equals(m.held)) return;
        m.held = what;
        m.body.getEquipment().setItemInMainHand(hotbar(m, what));
    }

    /** Their kingdom line over the name, like FaultlineKingdoms' head tags (read by reflection). */
    private TextDisplay kingdomTag(Mimic m, Location at) {
        String line = kingdomLine(m.who);
        if (line == null) return null;
        TextDisplay d = (TextDisplay) at.getWorld().spawnEntity(tagSpot(at), EntityType.TEXT_DISPLAY, false);
        d.addScoreboardTag(TAG_TAG);
        d.setPersistent(false);
        d.text(LegacyComponentSerializer.legacySection().deserialize(line));
        Emotes.q(() -> d.setBillboard(Display.Billboard.CENTER));
        Emotes.q(() -> d.setDefaultBackground(true));
        Emotes.q(() -> d.setShadowed(false));
        Emotes.q(() -> d.setTeleportDuration(2));
        Emotes.q(() -> d.setViewRange(0.7f));
        Emotes.q(() -> d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.85f, 0.85f, 0.85f), new Quaternionf())));
        return d;
    }

    static Location tagSpot(Location l) { return new Location(l.getWorld(), l.getX(), l.getY() + 1.8 + 0.45, l.getZ()); }

    static String kingdomLine(UUID who) {
        try {
            org.bukkit.plugin.Plugin k = Bukkit.getPluginManager().getPlugin("FaultlineKingdoms");
            if (k == null || !k.isEnabled()) return null;
            int n = who == null ? 0 : (int) k.getClass().getMethod("kingdomOf", UUID.class).invoke(null, who);
            var c = k.getConfig();
            if (n > 0) {
                ChatColor col;
                try { col = ChatColor.valueOf(c.getString("kingdoms." + n + ".color", "WHITE").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { col = ChatColor.WHITE; }
                return col + c.getString("kingdoms." + n + ".name", "Kingdom " + n);
            }
            ChatColor col;
            try { col = ChatColor.valueOf(c.getString("display.outlaw-color", "DARK_RED").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { col = ChatColor.DARK_RED; }
            return col + c.getString("display.outlaw-name", "Outlaw");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

    void remove(Mimic m) {
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        try { m.brain.remove(); } catch (RuntimeException ignored) { }
        try { m.body.remove(); } catch (RuntimeException ignored) { }
        if (m.tag != null && m.tag.isValid()) m.tag.remove();
    }

    void removeAll() {
        for (Mimic m : new ArrayList<>(byBrain.values())) remove(m);
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(org.bukkit.entity.Projectile.class)) // pearls still in the air (a one-off, on the command)
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        restoreAll(); try { saveGear(); } catch (RuntimeException ignored) { } }

    // ================================================================ the brain

    private void tick() {
        try { tickTemp(); } catch (RuntimeException ignored) { }
        for (Mimic m : new ArrayList<>(byBrain.values())) {
            try { think(m); } catch (RuntimeException ex) {
                StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(s -> s.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
                plugin.getLogger().warning("[MimicPlayer] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
                remove(m);
            }
        }
    }

    double range() { return Math.max(16, cfg("range", 160)); }

    private Player nearest(Mob z) { return nearest(z, null); }

    /** The nearest fighter within `range` (160); it keeps chasing the one it has out to 1.5x that. */
    private Player nearest(Mob z, Player keep) {
        if (keep != null && keep.isValid() && !keep.isDead() && keep.getWorld() == z.getWorld()
                && (keep.getGameMode() == GameMode.SURVIVAL || keep.getGameMode() == GameMode.ADVENTURE) && !keep.hasMetadata("vanished")
                && keep.getLocation().distanceSquared(z.getLocation()) < Math.pow(range() * 1.5, 2)) {
            // stays on its target unless someone else is right on top of it
            Player other = nearest(z, null);
            if (other == null || other == keep || other.getLocation().distanceSquared(z.getLocation()) > 9) return keep;
        }
        Player best = null;
        double bd = range() * range();
        for (Player p : z.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || p.hasMetadata("vanished")) continue;
            double d = p.getLocation().distanceSquared(z.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    double maxHp(LivingEntity e) { return e.getAttribute(Attribute.MAX_HEALTH).getValue(); }

    /** Attack cooldown in ticks of what it holds (sword 1.6 attacks/s, axe 1.0). */
    static int cooldown(ItemStack w) {
        if (w == null) return 5;
        String n = w.getType().name();
        if (n.endsWith("_SWORD")) return 13;
        if (n.endsWith("_AXE")) return n.startsWith("WOODEN") || n.startsWith("STONE") ? 25 : n.startsWith("IRON") ? 22 : 20;
        if (n.equals("TRIDENT")) return 18;
        if (n.equals("MACE")) return 33;
        return 13;
    }

    double charge(Mimic m) { return Math.min(1, (m.t - m.lastHit) / (double) cooldown(weaponHeld(m))); }

    /** Eye to the nearest point of the target's hitbox. */
    static double reachTo(LivingEntity from, LivingEntity to) {
        Vector eye = from.getEyeLocation().toVector();
        BoundingBox b = to.getBoundingBox();
        double x = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double y = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double z = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        return eye.distance(new Vector(x, y, z));
    }

    private void think(Mimic m) {
        Vindicator v = m.brain;
        if (!v.isValid() || !m.body.isValid()) { remove(m); return; }
        m.t++;
        if (m.t % 10 == 0 || m.target == null || !m.target.isValid() || m.target.isDead() || m.target.getWorld() != v.getWorld()) {
            Player n = nearest(v, m.target);
            if (n != m.target) m.reactAt = m.t + m.skill.reaction();
            m.target = n;
            if (n != null && v.getTarget() != n) v.setTarget(n);
        }
        Player tg = m.target;
        double hp = v.getHealth() / maxHp(v);
        boolean busy = m.eatUntil > m.t || m.drinkUntil > m.t || m.drawUntil > m.t || m.potUntil > m.t;
        // natural regen like a fed player, out of combat
        if (m.t - m.lastHurt > 120 && m.t % 10 == 0 && v.getHealth() < maxHp(v)) v.setHealth(Math.min(maxHp(v), v.getHealth() + 1));

        clutch(m); // water MLG on a long fall, any time
        if (m.jumpAt == m.t && v.isOnGround() && tg != null) { // crit chain: back up into the air right after a crit
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 1e-4) Safe.vel(v, to.normalize().multiply(0.25).setY(0.42));
        }
        if (m.jumpReset == m.t && v.isOnGround()) { // JUMP RESET: jumping as the hit lands takes the knockback down
            Vector cur = v.getVelocity();
            Safe.vel(v, new Vector(cur.getX() * 0.45, 0.42, cur.getZ() * 0.45));
        }
        if (busy) { busyTick(m, tg); follow(m); return; }
        if (webEscape(m, tg)) { follow(m); return; }
        if (tg == null) { hold(m, "sword"); follow(m); return; }
        double dist = tg.getLocation().distance(v.getLocation());
        boolean sees = sees(v, tg);

        // ---- POT PvP: keep its buffs up (Strength, Speed, Fire Res... whatever it carries), debuff you, snowball you
        if (selfExtinguish(m)) { follow(m); return; }
        if (dist < 24 && m.t >= m.buffReady && (dist > 3.6 || m.wtapUntil > m.t || charge(m) < 0.5) && buff(m)) { follow(m); return; }
        if (dist > 2.5 && dist < 8 && sees && m.t >= m.debuffReady && debuff(m, tg)) { follow(m); return; }
        if (m.throwables > 0 && dist > 3.5 && dist < 10 && sees && m.t >= m.throwReady && tg.isSprinting() && random.nextDouble() < 0.15) { snowball(m, tg); follow(m); return; }
        // ---- heal up
        if (hp < cfg("potion-below", 0.45) && m.pots > 0 && m.t - m.lastHit > 4) { drink(m); follow(m); return; }
        boolean food = m.apples > 0 || m.gapples > 0;
        if (hp < cfg("eat-below", 0.4) && food && (dist > 5 || m.fleeUntil > m.t || m.eatAfterPearl)) { m.eatAfterPearl = false; eat(m); follow(m); return; }
        // low, close, with gaps: pearl away to get room to eat (like a real player)
        if (hp < cfg("eat-below", 0.4) && food && dist <= 5 && m.pearlsLeft > 0 && m.t >= m.pearlReady && m.pots == 0) {
            m.eatAfterPearl = true; pearlAway(m, tg); follow(m); return;
        }
        // ---- escape: low with nothing left
        if (hp < 0.3 && !food && m.pots == 0 && m.fleeUntil <= m.t) {
            m.fleeUntil = m.t + 160;
            if (m.pearlsLeft > 0 && m.t >= m.pearlReady) pearlAway(m, tg);
        }
        if (m.fleeUntil > m.t) { if (!elytraEscape(m, tg) && !pillar(m)) flee(m, tg); follow(m); return; }
        // ---- far: pearl in, or chase
        if (elytraPvp(m, tg, dist, sees)) { follow(m); return; }
        if (dist > 14 && dist < 60 && m.pearlsLeft > 0 && m.t >= m.pearlReady && sees && m.t >= m.reactAt) { pearl(m, tg); follow(m); return; }
        // ---- mid: bow
        if (m.gear.bow != null && (m.arrows > 0 || level(m.gear.bow, "infinity") > 0) && dist > 8 && dist < 22 && sees && m.t >= m.bowReady && random.nextDouble() < m.skill.bow() * 0.05) {
            m.bowReady = m.t + 80 + random.nextInt(60);
            m.drawUntil = m.t + 20 + random.nextInt(8);
            hold(m, "bow");
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            follow(m);
            return;
        }
        // ---- close: the fight
        if (m.t >= m.reactAt) melee(m, tg, dist, sees);
        follow(m);
    }

    private void busyTick(Mimic m, Player tg) {
        Vindicator v = m.brain;
        Safe.vel(v, new Vector(v.getVelocity().getX() * 0.5, v.getVelocity().getY(), v.getVelocity().getZ() * 0.5));
        if (m.eatUntil > m.t) {
            if (m.eatUntil - m.t == 1) {
                if (m.eatingGod) { // an enchanted golden apple
                    v.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3));
                    v.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 1));
                    v.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0));
                    v.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
                } else {
                    v.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
                    v.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                }
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_PLAYER_BURP, 1, 1);
                Emotes.q(() -> m.body.clearActiveItem());
                hold(m, "sword");
                if (v.getHealth() / maxHp(v) > 0.3 || v.getAbsorptionAmount() > 0) { m.fleeUntil = 0; m.eatAfterPearl = false; m.reactAt = m.t; } // healed: run back in
            } else if (m.t % 4 == 0) v.getWorld().playSound(v.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 0.9f + random.nextFloat() * 0.2f);
        } else if (m.drinkUntil > m.t) {
            if (m.drinkUntil - m.t == 3) splashSelf(m);
            if (m.drinkUntil - m.t == 1) hold(m, "sword");
        } else if (m.potUntil > m.t) {
            if (m.potUntil - m.t == 1 && m.potting != null) {
                for (PotionEffect pe : effects(m.potting)) v.addPotionEffect(pe);
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1, 1);
                Emotes.q(() -> m.body.clearActiveItem());
                m.potting = null;
                hold(m, "sword");
            }
        } else if (m.drawUntil > m.t) {
            if (m.drawUntil - m.t == 1) { if (tg != null) shoot(m, tg); Emotes.q(() -> m.body.clearActiveItem()); hold(m, "sword"); }
        }
    }

    private void eat(Mimic m) {
        boolean god = m.gapples > 0 && (m.apples == 0 || m.brain.getHealth() / maxHp(m.brain) < 0.25);
        if (god) m.gapples--; else m.apples--;
        m.eatingGod = god;
        m.eatUntil = m.t + 32;
        hold(m, god ? "gapple" : "apple");
        Emotes.q(() -> m.body.clearActiveItem());
        Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
    }

    private void drink(Mimic m) {
        m.pots--;
        m.drinkUntil = m.t + 8;
        hold(m, "potion");
    }

    /** Throws Healing II straight down (looks down for it: `follow` points the head at the feet). */
    private void splashSelf(Mimic m) {
        Vindicator v = m.brain;
        Location at = v.getLocation().add(0, 1.2, 0);
        ThrownPotion p = v.getWorld().spawn(at, ThrownPotion.class, e -> { e.setItem(healingPotion()); e.setShooter(v); });
        Safe.vel(p, new Vector(0, -0.5, 0));
        potions.add(p.getUniqueId());
        Emotes.q(() -> m.body.swingMainHand());
        v.getWorld().playSound(at, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!potions.remove(e.getPotion().getUniqueId())) return;
        // a mimic's heal pot heals it (and anyone beside it, like a real one)
        for (LivingEntity le : e.getAffectedEntities()) {
            Mimic m = byBrain.get(le.getUniqueId());
            if (m != null) { le.setHealth(Math.min(maxHp(le), le.getHealth() + 8 * e.getIntensity(le))); e.setIntensity(le, 0); }
        }
    }

    // ---- elytra PvP

    boolean elytraOn() { return plugin.getConfig().getBoolean("mimic-player.elytra-pvp", true); }

    /**
     * ELYTRA PvP (it needs an elytra + rockets in their gear): takes off with a rocket to chase anyone far off or anyone
     * flying, swaps its chestplate for the elytra in the air (and back on landing: the elytra swap), steers its glide at
     * you with lead, boosts with rockets when it slows, stays above you and dives in, sprint/crit hits you as it passes
     * (a mace lands as a smash), follows you up into the air, and lands to fight on the ground once it's close.
     */
    private boolean elytraPvp(Mimic m, Player tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        if (!elytraOn() || !m.gear.elytra) { if (m.flying) land(m); return false; }
        if (m.flying) { glide(m, tg, dist); return true; }
        if (m.rockets <= 0 || m.t < m.flyCooldown || !v.isOnGround()) return false;
        boolean theyFly = tg.isGliding() && dist > 6;
        boolean far = dist > 22 && (m.pearlsLeft == 0 || dist > 60 || random.nextDouble() < 0.5);
        boolean above = tg.getLocation().getY() - v.getLocation().getY() > 8 && dist > 8;
        if (!(theyFly || far || above)) return false;
        takeOff(m, tg);
        return true;
    }

    private void takeOff(Mimic m, Player tg) {
        Vindicator v = m.brain;
        m.flying = true; m.flyTicks = 0;
        m.body.getEquipment().setChestplate(new ItemStack(Material.ELYTRA)); // the elytra swap
        armourFor(m, true);
        Emotes.q(() -> v.getPathfinder().stopPathfinding());
        Safe.vel(v, new Vector(0, 0.75, 0));
        rocket(m, tg, 0.6);
    }

    private void rocket(Mimic m, Player tg, double up) {
        Vindicator v = m.brain;
        if (m.rockets <= 0) return;
        m.rockets--;
        hold(m, "rocket");
        Emotes.q(() -> m.body.swingMainHand());
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1, 1);
        v.getWorld().spawnParticle(Particle.FIREWORK, v.getLocation(), 16, 0.2, 0.2, 0.2, 0.05);
        Vector dir = tg == null ? v.getLocation().getDirection() : tg.getEyeLocation().toVector().subtract(v.getLocation().toVector());
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 1, 0);
        dir.normalize();
        Vector cur = v.getVelocity();
        Safe.vel(v, cur.multiply(0.5).add(dir.multiply(1.5)).add(new Vector(0, up, 0)));
        m.nextRocket = m.t + 30 + random.nextInt(20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid() && m.flying) hold(m, m.gear.mace != null ? "mace" : "sword"); }, 3L);
    }

    /** One tick of gliding: steer, boost, hit, land. */
    private void glide(Mimic m, Player tg, double dist) {
        Vindicator v = m.brain;
        m.flyTicks++;
        Emotes.q(() -> v.getPathfinder().stopPathfinding());
        Vector cur = v.getVelocity();
        double speed = cur.length();
        // where to: their eyes + where they're going; from far off it stays ~6 above them, then dives in
        Vector aim = tg.getEyeLocation().toVector().add(tg.getVelocity().clone().multiply(Math.min(12, dist / Math.max(0.6, speed))));
        if (dist > 14) aim.add(new Vector(0, 6, 0));
        Vector dir = aim.subtract(v.getLocation().toVector());
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, -1, 0);
        dir.normalize();
        // glide physics: turn the velocity toward the aim, lift from speed, gravity, air drag
        Vector nv = cur.clone().multiply(0.92).add(dir.clone().multiply(0.16));
        nv.setY(nv.getY() + Math.min(0.07, speed * 0.05) - 0.04);
        if (nv.length() > 2.2) nv.normalize().multiply(2.2);
        Safe.vel(v, nv);
        if (m.flyTicks > 4 && speed < 0.9 && m.t >= m.nextRocket && m.rockets > 0 && dist > 6) rocket(m, tg, 0.1);
        // hit as it passes (falling = a crit; flying in fast = a sprint hit; a mace smashes)
        if (reachTo(v, tg) <= cfg("reach", 3.0) && charge(m) >= m.skill.hitSelect() && sees(v, tg)) {
            if (m.gear.mace != null && cur.getY() < -0.5) { hold(m, "mace"); smash(m, tg); land(m); return; }
            hold(m, "sword");
            m.sprintHit = speed > 0.5;
            strike(m, tg, charge(m));
        }
        // land: close to someone on the ground, ran into the ground, or out of rockets and slow
        boolean ground = v.isOnGround() && m.flyTicks > 6;
        if (ground || (dist < 5 && !tg.isGliding() && tg.isOnGround() && cur.getY() <= 0) || (m.rockets == 0 && speed < 0.25 && m.flyTicks > 40) || m.flyTicks > 20 * 90) land(m);
    }

    private void land(Mimic m) {
        m.flying = false;
        m.noFallUntil = m.t + 30;
        m.flyCooldown = m.t + 60;
        m.body.getEquipment().setChestplate(m.gear.chest == null || m.gear.chest.getType() == Material.ELYTRA ? null : m.gear.chest.clone()); // back to the chestplate
        armourFor(m, false);
        hold(m, "sword");
    }

    /** Armour points while flying: the chestplate is off (the elytra's on). */
    private void armourFor(Mimic m, boolean elytra) {
        double ar = 0, tough = 0, kb = 0;
        for (ItemStack it : armour(m)) {
            if (elytra && it == m.gear.chest) continue;
            double[] a = armorOf(it); ar += a[0]; tough += a[1]; kb += a[2];
        }
        attr(m.brain, Attribute.ARMOR, ar);
        attr(m.brain, Attribute.ARMOR_TOUGHNESS, tough);
        attr(m.brain, Attribute.KNOCKBACK_RESISTANCE, kb);
    }

    /** Losing with nothing left: rockets away into the sky instead of running. */
    private boolean elytraEscape(Mimic m, Player tg) {
        Vindicator v = m.brain;
        if (!elytraOn() || !m.gear.elytra) return false;
        if (m.flying) {
            m.flyTicks++;
            Vector away = v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            Vector nv = v.getVelocity().multiply(0.92).add(away.normalize().multiply(0.14));
            nv.setY(nv.getY() + 0.03);
            Safe.vel(v, nv);
            if (m.t >= m.nextRocket && m.rockets > 0 && v.getVelocity().length() < 1) rocket(m, null, 0.4);
            if ((v.isOnGround() && m.flyTicks > 6) || m.fleeUntil - m.t < 2) land(m);
            return true;
        }
        if (m.rockets <= 0 || !v.isOnGround() || m.t < m.flyCooldown) return false;
        m.flying = true; m.flyTicks = 0;
        m.body.getEquipment().setChestplate(new ItemStack(Material.ELYTRA));
        armourFor(m, true);
        Safe.vel(v, new Vector(0, 0.8, 0));
        rocket(m, null, 0.9);
        return true;
    }

    // ---- cobwebs

    /**
     * Stuck in a COBWEB: a real player gets out by pouring a WATER BUCKET on it (the web washes away, the water is picked back
     * up), else pearls out, else cuts it with the sword.
     */
    private boolean webEscape(Mimic m, Player tg) {
        Vindicator v = m.brain;
        Block feet = v.getLocation().getBlock(), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block web = feet.getType() == Material.COBWEB ? feet : head.getType() == Material.COBWEB ? head : null;
        if (web == null) { m.webTicks = 0; return false; }
        m.webTicks++;
        if (m.webTicks < 2 + m.skill.reaction() / 3) return false; // a moment to notice
        if (m.water > 0 && worldTechs()) {
            m.water--;
            hold(m, "water");
            m.lookDownUntil = m.t + 8;
            Emotes.q(() -> m.body.swingMainHand());
            for (Block b : new Block[]{feet, head}) {
                if (b.getType() != Material.COBWEB) continue;
                temp.remove(b); // one it placed itself: the water takes it
                b.setType(Material.AIR, false);
            }
            if (place(feet, Material.WATER, 10)) feet.getWorld().playSound(feet.getLocation().clone().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_EMPTY, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { // bucket back
                if (!m.body.isValid()) return;
                m.water++;
                hold(m, "sword");
            }, 11L);
            m.webTicks = 0;
            return true;
        }
        if (m.pearlsLeft > 0 && m.t >= m.pearlReady) {
            if (tg != null && tg.getLocation().distance(v.getLocation()) > 6) pearl(m, tg); else if (tg != null) pearlAway(m, tg);
            else return false;
            m.webTicks = 0;
            return true;
        }
        // cut it with the sword (a sword breaks a web fast)
        hold(m, "sword");
        if (m.webTicks % 4 == 0) Emotes.q(() -> m.body.swingMainHand());
        if (m.webTicks >= 2 + m.skill.reaction() / 3 + 8) {
            temp.remove(web);
            web.setType(Material.AIR, false);
            web.getWorld().playSound(web.getLocation().clone().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOL_BREAK, 1, 1);
            m.webTicks = 0;
        }
        return true;
    }

    // ---- melee

    private void melee(Mimic m, Player tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        Skill s = m.skill;
        if (m.gear.mace != null && maceTech(m, tg, dist, sees)) return;
        if (uhcTech(m, tg, dist, sees)) return;
        double reach = reachTo(v, tg);
        double ch = charge(m);
        boolean theyBlock = tg.isBlocking();
        // their shield up: out with the axe
        if (theyBlock && dist < 4.5 && m.axeUntil < m.t && m.gear.axe != null) { m.axeUntil = m.t + 30; m.lastHit = Math.min(m.lastHit, m.t - 20); }
        String want = m.axeUntil > m.t ? "axe" : "sword";
        if (!want.equals(m.held)) hold(m, want);
        boolean shielding = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
        // swing when charged enough and in reach (lower the shield first). EVERY hit is a CRIT (falling onto them after a
        // jump) or a SPRINT hit (a lunge in): never a plain standing hit, never a sweep.
        if (reach <= cfg("reach", 3.0) + 0.6 && sees && ch >= s.hitSelect() - 0.1 && !(shielding && random.nextDouble() < 0.5)) {
            boolean falling = !v.isOnGround() && v.getVelocity().getY() < -0.05;
            boolean rising = !v.isOnGround() && !falling;
            boolean inReach = reach <= cfg("reach", 3.0);
            if (falling && inReach && ch >= s.hitSelect()) { // the crit lands
                if (shielding) { m.blockUntil = m.t; Emotes.q(() -> m.body.clearActiveItem()); }
                m.sprintHit = false;
                strike(m, tg, ch);
                return;
            }
            if (rising) return; // on the way up: wait for the fall
            if (v.isOnGround() && inReach && ch >= s.hitSelect()) {
                if (shielding) { m.blockUntil = m.t; Emotes.q(() -> m.body.clearActiveItem()); }
                Vector to0 = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (to0.lengthSquared() > 1e-4) to0.normalize();
                boolean room = !v.getLocation().getBlock().getRelative(0, 2, 0).getType().isSolid();
                boolean critStyle = m.style.equals("axe") || m.style.equals("uhc") || m.style.equals("mace");
                if (room && m.wtapUntil <= m.t && random.nextDouble() < (critStyle ? Math.max(0.6, s.jumpCrit()) : s.jumpCrit() * 0.8)) {
                    // CRIT: jump now, hit on the way down (stays close so they're still in reach)
                    Safe.vel(v, to0.clone().multiply(0.12).setY(0.42));
                    return;
                }
                // SPRINT HIT: lunge in (sprinting) and hit
                Safe.vel(v, new Vector(to0.getX() * 0.42, v.getVelocity().getY(), to0.getZ() * 0.42));
                m.sprintHit = true;
                strike(m, tg, ch);
                return;
            }
        }
        // raise the shield: close, facing us, our hit not ready
        float theirs;
        try { theirs = tg.getAttackCooldown(); } catch (RuntimeException ex) { theirs = 1f; }
        boolean theyReady = theirs > 0.9f && reachTo(tg, v) < 3.3 && facing(tg, v);
        // SHIELD TIMING: up right when their swing is charged and we can't hit yet (axe players), else the odd block
        if (!shielding && hasShield(m) && m.shieldOffUntil <= m.t && dist < 4 && ch < 0.7 && facing(tg, v)
                && (theyReady && m.style.equals("axe") ? random.nextDouble() < 0.5 : random.nextDouble() < s.block())) {
            m.blockUntil = m.t + 10 + random.nextInt(12);
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.OFF_HAND));
        } else if (shielding && m.blockUntil - m.t == 1) Emotes.q(() -> m.body.clearActiveItem());
        // move: W-tap back, jump-crit in, strafe close, chase far
        Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-4) return;
        to.normalize();
        Vector cur = v.getVelocity();
        if (m.wtapUntil > m.t) {
            Safe.vel(v, new Vector(cur.getX() * 0.5 - to.getX() * 0.12, cur.getY(), cur.getZ() * 0.5 - to.getZ() * 0.12));
            return;
        }
        if (v.isOnGround() && dist > 2.4 && dist < 4.2 && ch > 0.55 && ch < 0.9 && random.nextDouble() < s.jumpCrit() * 0.3) {
            Safe.vel(v, to.clone().multiply(0.3).setY(0.42)); // lands the hit on the way down
            return;
        }
        if (dist < 4.5) {
            if (--m.strafeFlip <= 0) { m.strafeDir = -m.strafeDir; m.strafeFlip = 12 + random.nextInt(30); }
            Vector side = new Vector(-to.getZ(), 0, to.getX()).multiply(s.strafe() * m.strafeDir);
            // SPACING: sit just outside their reach while our hit charges, step in the moment it's ready
            double keep = ch >= s.hitSelect() - 0.1 ? 2.5 : (m.style.equals("sword") || m.style.equals("nethop") || m.style.equals("diamond") ? 3.35 : 2.8);
            Vector fw = to.clone().multiply(dist > keep + 0.3 ? 0.1 : dist < keep - 0.3 ? -0.08 : 0);
            if (v.isOnGround()) Safe.vel(v, new Vector(cur.getX() * 0.55 + side.getX() + fw.getX(), cur.getY(), cur.getZ() * 0.55 + side.getZ() + fw.getZ()));
            m.sprintHit = false;
        } else {
            chase(m, tg, to);
            m.sprintHit = true; // running in = a sprint hit
        }
    }

    /**
     * Runs at them like a sprinting player: steers its own velocity straight in (hops a block in the way), with the
     * pathfinder only as a backup when it can't get closer that way (round walls).
     */
    private void chase(Mimic m, Player tg, Vector to) {
        Vindicator v = m.brain;
        double sp = m.skill.speed() * 0.9;
        PotionEffect speed = v.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) sp *= 1 + 0.2 * (speed.getAmplifier() + 1);
        Vector cur = v.getVelocity();
        Block ahead = v.getLocation().clone().add(to.getX() * 0.8, 0, to.getZ() * 0.8).getBlock();
        boolean wall = ahead.getType().isSolid(), tall = ahead.getRelative(0, 1, 0).getType().isSolid();
        if (wall && tall) m.chaseStuck++; else m.chaseStuck = Math.max(0, m.chaseStuck - 1);
        if (m.chaseStuck > 10) { Emotes.q(() -> v.getPathfinder().moveTo(tg, 1.4)); return; }
        move(m, to, sp, wall && !tall, true);
    }

    /**
     * Moves like a player: sprint speed on the ground (0.28 b/t + Speed), SPRINT-JUMPS across open ground (a jump with
     * the sprint boost, keeping its speed through the air), jumps up single blocks, only small corrections in the air.
     */
    private void move(Mimic m, Vector dir, double sp, boolean stepUp, boolean sprintJump) {
        Vindicator v = m.brain;
        Vector cur = v.getVelocity();
        if (v.isOnGround()) {
            Block ahead = v.getLocation().clone().add(dir.getX() * 1.6, 0, dir.getZ() * 1.6).getBlock();
            boolean open = !ahead.getType().isSolid() && !ahead.getRelative(0, 1, 0).getType().isSolid() && ahead.getRelative(0, -1, 0).getType().isSolid()
                    && !v.getLocation().getBlock().getRelative(0, 2, 0).getType().isSolid();
            if (stepUp) { Safe.vel(v, new Vector(dir.getX() * sp, 0.42, dir.getZ() * sp)); return; }
            if (sprintJump && open && random.nextDouble() < 0.6) { // sprint-jump: +0.2 forward on take-off
                Safe.vel(v, new Vector(dir.getX() * (sp + 0.2), 0.42, dir.getZ() * (sp + 0.2)));
                return;
            }
            Safe.vel(v, new Vector(cur.getX() * 0.4 + dir.getX() * sp * 0.9, cur.getY(), cur.getZ() * 0.4 + dir.getZ() * sp * 0.9));
        } else {
            // air: keeps its momentum (a player can't turn much mid-jump), a little steering
            Safe.vel(v, new Vector(cur.getX() * 0.98 + dir.getX() * 0.02, cur.getY(), cur.getZ() * 0.98 + dir.getZ() * 0.02));
        }
    }

    /** One hit, the way a player's would land. */
    private void strike(Mimic m, Player tg, double charge) {
        Vindicator v = m.brain;
        boolean crit = !v.isOnGround() && v.getVelocity().getY() < -0.05;
        ItemStack w = weaponHeld(m);
        double base = w == null ? 1 : baseDamage(w.getType());
        double sharp = level(w, "sharpness");
        PotionEffect str = v.getPotionEffect(PotionEffectType.STRENGTH), weak = v.getPotionEffect(PotionEffectType.WEAKNESS);
        base += (str == null ? 0 : 3 * (str.getAmplifier() + 1)) - (weak == null ? 0 : 4 * (weak.getAmplifier() + 1));
        base = Math.max(0.5, base);
        double dmg = (base * (0.2 + 0.8 * charge * charge) + (sharp > 0 ? 0.5 * sharp + 0.5 : 0) * charge) * (crit ? 1.5 : 1);
        dmg *= cfg("damage-multiplier", 1.0);
        Emotes.q(() -> m.body.swingMainHand());
        boolean axeOnShield = m.held.equals("axe") && tg.isBlocking() && facing(tg, v);
        striking = true;
        try { tg.damage(dmg, v); } finally { striking = false; }
        if (axeOnShield) {
            Emotes.q(() -> tg.setCooldown(Material.SHIELD, 100));
            Emotes.q(() -> tg.clearActiveItem());
            tg.getWorld().playSound(tg.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
            m.axeUntil = m.t + 4;
        }
        Vector push = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        int kbLvl = level(w, "knockback");
        if ((m.sprintHit || kbLvl > 0) && push.lengthSquared() > 1e-4)
            Safe.vel(tg, tg.getVelocity().add(push.normalize().multiply((m.sprintHit ? 0.45 : 0) + 0.45 * kbLvl).setY(0.1)));
        int fire = level(w, "fire_aspect");
        if (fire > 0 && !axeOnShield) tg.setFireTicks(Math.max(tg.getFireTicks(), 80 * fire));
        Location l = tg.getLocation().add(0, 1, 0);
        if (crit) { tg.getWorld().spawnParticle(Particle.CRIT, l, 14, 0.3, 0.4, 0.3, 0.25); tg.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1); }
        else if (charge > 0.9) tg.getWorld().playSound(l, m.sprintHit ? Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK : Sound.ENTITY_PLAYER_ATTACK_STRONG, 1, 1);
        else tg.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_WEAK, 1, 1);
        m.lastHit = m.t;
        if (crit && random.nextDouble() < m.skill.jumpCrit()) m.jumpAt = m.t + 2 + random.nextInt(2); // crit chain
        if (m.sprintHit && random.nextDouble() < m.skill.wtap()) m.wtapUntil = m.t + 3 + random.nextInt(3);
        m.sprintHit = false;
    }

    // ================================================================ techs

    /** Vanilla mace damage for a fall: +4 a block for 3 blocks, +2 a block for the next 5, then +1. */
    static double maceBonus(double fall) {
        if (fall <= 1.5) return 0;
        if (fall <= 3) return 4 * fall;
        if (fall <= 8) return 12 + 2 * (fall - 3);
        return 22 + (fall - 8);
    }

    /**
     * MACE TECHS: WIND-CHARGE SLAM (throws one at its feet, flies up, steers in the air, smashes on the way down),
     * WIND BURST POGO (Wind Burst throws it back up after a smash: it chains), STUN SLAM (your shield's up: the axe breaks
     * it, then the mace lands), ELYTRA DIVE (rockets up from range and dives in), AXE/SWORD TO MACE SWAP (any time it's
     * falling far enough into reach, it swaps to the mace for the hit). Density adds 0.5 a block per level, Breach cuts
     * through armour.
     */
    private boolean maceTech(Mimic m, Player tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        boolean ground = v.isOnGround();
        if (m.slam) {
            if (ground && m.t > m.maceReady - 50) { m.slam = false; m.dive = false; hold(m, "sword"); return false; } // missed: landed
            // air control toward the target (a player steering a slam)
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            Vector cur = v.getVelocity();
            if (to.lengthSquared() > 0.04) {
                to.normalize().multiply(m.dive && cur.getY() < 0 ? 0.9 : 0.32);
                Safe.vel(v, new Vector(cur.getX() * 0.7 + to.getX() * 0.3, m.dive && cur.getY() < 0 ? Math.min(cur.getY(), -0.9) : cur.getY(), cur.getZ() * 0.7 + to.getZ() * 0.3));
            }
            if (cur.getY() < -0.1 && v.getFallDistance() > 1.5) hold(m, "mace");
            if (cur.getY() < -0.1 && v.getFallDistance() > 1.5 && reachTo(v, tg) <= cfg("reach", 3.0) + 0.5) smash(m, tg);
            return true;
        }
        // sword/axe -> mace swap: already falling into reach
        if (!ground && v.getVelocity().getY() < -0.1 && v.getFallDistance() > 1.5 && reachTo(v, tg) <= cfg("reach", 3.0) && charge(m) > 0.5) {
            hold(m, "mace"); smash(m, tg); return true;
        }
        if (m.t < m.maceReady || !sees) return false;
        // WIND-CHARGE SLAM
        if (ground && dist <= 5.5 && m.wind > 0 && random.nextDouble() < 0.08 + m.skill.jumpCrit() * 0.1) {
            m.wind--;
            hold(m, "wind");
            Emotes.q(() -> m.body.swingMainHand());
            Location feet = v.getLocation();
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, feet, 1);
            v.getWorld().playSound(feet, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Vector to = tg.getLocation().toVector().subtract(feet.toVector()).setY(0);
            Vector out = to.lengthSquared() > 1e-4 ? to.normalize().multiply(0.2) : new Vector();
            Safe.vel(v, out.setY(1.25)); // ~7 blocks up, like a wind charge at your feet
            m.slam = true; m.dive = false;
            m.stunSlam = tg.isBlocking() && m.gear.axe != null;
            m.maceReady = m.t + 60;
            return true;
        }
        // ELYTRA DIVE from range
        if (ground && dist > 10 && dist < 26 && m.gear.elytra && m.rockets > 0 && random.nextDouble() < 0.04) {
            m.rockets--;
            hold(m, "rocket");
            Emotes.q(() -> m.body.swingMainHand());
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1, 1);
            v.getWorld().spawnParticle(Particle.FIREWORK, v.getLocation(), 20, 0.2, 0.2, 0.2, 0.05);
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0).normalize().multiply(0.6);
            Safe.vel(v, to.setY(1.7));
            m.slam = true; m.dive = true;
            m.stunSlam = false;
            m.maceReady = m.t + 120;
            return true;
        }
        return false;
    }

    private void smash(Mimic m, Player tg) {
        Vindicator v = m.brain;
        ItemStack mace = m.gear.mace;
        double fall = v.getFallDistance();
        // STUN SLAM: shield up? the axe breaks it first
        if ((m.stunSlam || tg.isBlocking()) && m.gear.axe != null && tg.isBlocking()) {
            hold(m, "axe");
            Emotes.q(() -> tg.setCooldown(Material.SHIELD, 100));
            Emotes.q(() -> tg.clearActiveItem());
            tg.getWorld().playSound(tg.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
            hold(m, "mace");
        }
        m.stunSlam = false;
        double dmg = 6 * 1.5 + maceBonus(fall) + 0.5 * level(mace, "density") * fall;
        int breach = level(mace, "breach");
        if (breach > 0) dmg *= 1 + 0.12 * breach; // armour counts for less
        dmg *= cfg("damage-multiplier", 1.0);
        Emotes.q(() -> m.body.swingMainHand());
        striking = true;
        try { tg.damage(dmg, v); } finally { striking = false; }
        Location at = tg.getLocation();
        tg.getWorld().playSound(at, fall > 5 ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND, 1, 1);
        tg.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.3);
        tg.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
        for (Entity e : tg.getNearbyEntities(3.5, 2, 3.5)) { // the smash knocks back what's around
            if (!(e instanceof LivingEntity) || e == v || byBody.containsKey(e.getUniqueId())) continue;
            Vector away = e.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() > 1e-4) Safe.vel(e, away.normalize().multiply(0.7).setY(0.35));
        }
        v.setFallDistance(0);
        m.noFallUntil = m.t + 10;
        m.lastHit = m.t;
        int wb = level(mace, "wind_burst");
        if (wb > 0) { // WIND BURST POGO: back up for another
            Safe.vel(v, new Vector(v.getVelocity().getX() * 0.4, 0.75 + 0.3 * wb, v.getVelocity().getZ() * 0.4));
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, v.getLocation(), 1);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1.2f);
            m.slam = true;
            m.maceReady = m.t + 60;
        } else { m.slam = false; m.dive = false; Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid() && !m.slam) hold(m, "sword"); }, 4L); }
    }

    // ---- world techs: everything they place is put back (TempBlock), never into anything but air

    static final class TempBlock { Block block; org.bukkit.block.data.BlockData before; Material placed; int until; }
    final Map<Block, TempBlock> temp = new HashMap<>();
    private int clock;

    boolean worldTechs() { return plugin.getConfig().getBoolean("mimic-player.world-techs", true); }

    boolean place(Block b, Material mat, int ticks) {
        if (!worldTechs() || temp.containsKey(b) || !(b.getType().isAir())) return false;
        TempBlock t = new TempBlock();
        t.block = b; t.before = b.getBlockData().clone(); t.placed = mat; t.until = clock + ticks;
        b.setType(mat, false);
        temp.put(b, t);
        return true;
    }

    void restore(TempBlock t) {
        if (t.block.getType() == t.placed) t.block.setBlockData(t.before, false);
        temp.remove(t.block);
    }

    void tickTemp() {
        clock++;
        if (temp.isEmpty()) return;
        for (TempBlock t : new ArrayList<>(temp.values())) if (clock >= t.until) {
            restore(t);
            Sound s = t.placed == Material.LAVA ? Sound.ITEM_BUCKET_FILL_LAVA : t.placed == Material.WATER ? Sound.ITEM_BUCKET_FILL : null;
            if (s != null) t.block.getWorld().playSound(t.block.getLocation().clone().add(0.5, 0.5, 0.5), s, 0.8f, 1);
        }
    }

    void restoreAll() { for (TempBlock t : new ArrayList<>(temp.values())) restore(t); }

    boolean nearTemp(Block b, Material mat, int r) {
        for (TempBlock t : temp.values()) if (t.placed == mat && t.block.getWorld() == b.getWorld()
                && Math.abs(t.block.getX() - b.getX()) <= r && Math.abs(t.block.getY() - b.getY()) <= r && Math.abs(t.block.getZ() - b.getZ()) <= r) return true;
        return false;
    }

    static boolean solidBelow(Block b) { return b.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid(); }

    /**
     * UHC TECHS: LAVA under you (picked back up after 1.5 s), COBWEB on you (5 s), ROD hits from range then a sprint in,
     * FLINT & STEEL at your feet, WATER CLUTCH on its own long falls, BLOCK PILLAR to escape when it's losing.
     * Only with world-techs on, only into air, and every block is put back (flows, fire spread and bucket pick-ups are blocked).
     */
    private boolean uhcTech(Mimic m, Player tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        if (!sees || !v.isOnGround()) return false;
        Block feet = tg.getLocation().getBlock();
        boolean fireproof = tg.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE);
        // ROD: from 4-9 blocks, a hook hit (a little knockback), then sprint in for the combo
        if (m.gear.rod && dist > 4 && dist < 9 && m.t >= m.rodReady && random.nextDouble() < 0.06) {
            m.rodReady = m.t + 50;
            hold(m, "rod");
            Emotes.q(() -> m.body.swingMainHand());
            Location a = eye(m), b = tg.getLocation().add(0, 1.2, 0);
            Vector step = b.toVector().subtract(a.toVector());
            double len = step.length();
            step.normalize().multiply(0.6);
            Location c = a.clone();
            for (double d = 0; d < len; d += 0.6) { c.add(step); c.getWorld().spawnParticle(Particle.CRIT, c, 1, 0, 0, 0, 0); }
            v.getWorld().playSound(a, Sound.ENTITY_FISHING_BOBBER_THROW, 1, 1);
            striking = true;
            try { tg.damage(0.01, v); } finally { striking = false; }
            Vector away = b.toVector().subtract(a.toVector()).setY(0);
            if (away.lengthSquared() > 1e-4) Safe.vel(tg, tg.getVelocity().add(away.normalize().multiply(0.25).setY(0.25)));
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 5L);
            m.sprintHit = true;
            return true;
        }
        if (!worldTechs()) return false;
        // LAVA at their feet
        if (m.lava > 0 && !fireproof && dist <= 4 && m.t >= m.lavaReady && tg.isOnGround() && solidBelow(feet) && random.nextDouble() < 0.05) {
            if (place(feet, Material.LAVA, 30)) {
                m.lavaReady = m.t + 200;
                hold(m, "lava");
                Emotes.q(() -> m.body.swingMainHand());
                feet.getWorld().playSound(feet.getLocation().clone().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_EMPTY_LAVA, 1, 1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 30L);
                return true;
            }
        }
        // COBWEB on them
        if (m.webs > 0 && dist <= 3.5 && m.t >= m.webReady && random.nextDouble() < 0.05) {
            if (place(feet, Material.COBWEB, 100)) {
                m.webs--;
                m.webReady = m.t + 160;
                if (m.lava > 0 && !fireproof) m.lavaReady = Math.min(m.lavaReady, m.t + 6); // WEB + LAVA combo
                hold(m, "web");
                Emotes.q(() -> m.body.swingMainHand());
                feet.getWorld().playSound(feet.getLocation(), Sound.BLOCK_COBWEB_PLACE, 1, 1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 6L);
                return true;
            }
        }
        // FLINT & STEEL
        if (m.gear.flint && !fireproof && dist <= 3 && m.t >= m.fireReady && solidBelow(feet) && random.nextDouble() < 0.04) {
            if (place(feet, Material.FIRE, 60)) {
                m.fireReady = m.t + 240;
                hold(m, "flint");
                Emotes.q(() -> m.body.swingMainHand());
                feet.getWorld().playSound(feet.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1, 1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 6L);
                return true;
            }
        }
        return false;
    }

    /** WATER CLUTCH: falling far onto solid ground with a water bucket = water just before it lands, picked back up. */
    private void clutch(Mimic m) {
        Vindicator v = m.brain;
        if (m.water <= 0 || v.isOnGround() || v.getVelocity().getY() > -0.5 || v.getFallDistance() < 5 || m.slam && m.gear.mace != null || !worldTechs()) return;
        Block below = v.getLocation().getBlock();
        for (int i = 0; i < 3; i++) {
            Block under = below.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (under.getType().isSolid()) {
                if (place(below, Material.WATER, 12)) {
                    m.clutchUntil = m.t + 20;
                    hold(m, "water");
                    Emotes.q(() -> m.body.swingMainHand());
                    below.getWorld().playSound(below.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1, 1);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 12L);
                }
                return;
            }
            below = under;
        }
    }

    /** BLOCK PILLAR: losing and running, it jumps and places blocks under itself (3 high, put back after 10 s). */
    private boolean pillar(Mimic m) {
        Vindicator v = m.brain;
        if (m.blocks < 3 || m.t < m.pillarReady || !worldTechs()) return false;
        if (v.isOnGround()) { Safe.vel(v, new Vector(0, 0.42, 0)); hold(m, "block"); return true; }
        if (v.getVelocity().getY() < 0.1) {
            Block b = v.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
            if (place(b, m.gear.block, 200)) {
                m.blocks--;
                Emotes.q(() -> m.body.swingMainHand());
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1);
                if (++m.pillarCount >= 3) { m.pillarReady = m.t + 300; m.pillarCount = 0; }
            }
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(org.bukkit.event.block.BlockFromToEvent e) { if (temp.containsKey(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(org.bukkit.event.block.BlockIgniteEvent e) {
        if (temp.isEmpty()) return;
        if (nearTemp(e.getBlock(), Material.LAVA, 4) || nearTemp(e.getBlock(), Material.FIRE, 4)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent e) { if (!temp.isEmpty() && nearTemp(e.getBlock(), Material.FIRE, 4)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(org.bukkit.event.block.BlockSpreadEvent e) { if (!temp.isEmpty() && (nearTemp(e.getBlock(), Material.FIRE, 4) || nearTemp(e.getBlock(), Material.LAVA, 4))) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(org.bukkit.event.player.PlayerBucketFillEvent e) { if (temp.containsKey(e.getBlockClicked())) e.setCancelled(true); } // no free lava

    @EventHandler(ignoreCancelled = true)
    public void onTempBreak(org.bukkit.event.block.BlockBreakEvent e) {
        TempBlock t = temp.remove(e.getBlock());
        if (t != null) e.setDropItems(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTempExplode(EntityExplodeEvent e) { if (!temp.isEmpty()) e.blockList().removeIf(temp::containsKey); }

    // ---- pot PvP (Diamond SMP / Neth OP): their own potions

    /** A buff it's missing (or about to lose) from a potion it really carries: splash = at its feet, drinkable = 1.6 s. */
    private boolean buff(Mimic m) {
        Vindicator v = m.brain;
        for (Iterator<ItemStack> it = m.buffs.iterator(); it.hasNext(); ) {
            ItemStack pot = it.next();
            boolean need = false;
            for (PotionEffect pe : effects(pot)) {
                if (pe.getType().equals(PotionEffectType.INSTANT_HEALTH)) { need = v.getHealth() / maxHp(v) < 0.5; continue; }
                PotionEffect has = v.getPotionEffect(pe.getType());
                if (has == null || has.getDuration() < 100 || has.getAmplifier() < pe.getAmplifier()) need = true;
            }
            if (!need) continue;
            it.remove();
            m.buffReady = m.t + 20;
            if (pot.getType() == Material.POTION) { // drink it
                m.potting = pot;
                m.potUntil = m.t + 32;
                m.held = "?";
                m.body.getEquipment().setItemInMainHand(pot.clone());
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            } else { // splash it down at its feet
                m.held = "?";
                m.body.getEquipment().setItemInMainHand(pot.clone());
                m.lookDownUntil = m.t + 6; // looks down
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 6L);
                Location at = v.getLocation().add(0, 1.2, 0);
                ThrownPotion tp = v.getWorld().spawn(at, ThrownPotion.class, e -> { e.setItem(pot.clone()); e.setShooter(v); });
                Safe.vel(tp, new Vector(0, -0.5, 0));
                Emotes.q(() -> m.body.swingMainHand());
                v.getWorld().playSound(at, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
            }
            return true;
        }
        return false;
    }

    /** Slowness / Weakness / Poison / Harming... thrown AT you (leading your movement), if it carries any and you don't have it yet. */
    private boolean debuff(Mimic m, Player tg) {
        for (Iterator<ItemStack> it = m.debuffs.iterator(); it.hasNext(); ) {
            ItemStack pot = it.next();
            boolean useful = false;
            for (PotionEffect pe : effects(pot)) if (pe.getType().equals(PotionEffectType.INSTANT_DAMAGE) || !tg.hasPotionEffect(pe.getType())) useful = true;
            if (!useful) continue;
            it.remove();
            m.debuffReady = m.t + 80 + random.nextInt(40);
            Vindicator v = m.brain;
            Location eye = eye(m);
            Vector lead = tg.getVelocity().clone().setY(0).multiply(6);
            Vector aim = tg.getLocation().toVector().add(lead).subtract(eye.toVector());
            double d = Math.hypot(aim.getX(), aim.getZ());
            Vector vel = aim.clone().setY(0).normalize().multiply(Math.min(1.0, 0.11 * d + 0.15)).setY(0.18 + d * 0.02);
            m.held = "?";
            m.body.getEquipment().setItemInMainHand(pot.clone());
            ThrownPotion tp = v.getWorld().spawn(eye, ThrownPotion.class, e -> { e.setItem(pot.clone()); e.setShooter(v); });
            Safe.vel(tp, vel);
            Emotes.q(() -> m.body.swingMainHand());
            v.getWorld().playSound(eye, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 5L);
            return true;
        }
        return false;
    }

    /** Snowballs / eggs at a sprinting enemy: breaks their sprint (and their combo). */
    private void snowball(Mimic m, Player tg) {
        Vindicator v = m.brain;
        m.throwables--;
        m.throwReady = m.t + 15;
        Location eye = eye(m);
        Vector aim = tg.getEyeLocation().toVector().add(tg.getVelocity().clone().multiply(3)).subtract(eye.toVector());
        aim.setY(aim.getY() + aim.length() * 0.04);
        Snowball sb = v.getWorld().spawn(eye, Snowball.class, e -> e.setShooter(v));
        Safe.vel(sb, aim.normalize().multiply(1.5));
        Emotes.q(() -> m.body.swingMainHand());
        v.getWorld().playSound(eye, Sound.ENTITY_SNOWBALL_THROW, 1, 1);
    }

    /** UHC: on fire without Fire Resistance = water at its own feet. */
    private boolean selfExtinguish(Mimic m) {
        Vindicator v = m.brain;
        if (v.getFireTicks() <= 20 || m.water <= 0 || v.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) || !v.isOnGround()) return false;
        Block feet = v.getLocation().getBlock();
        if (!place(feet, Material.WATER, 10)) return false;
        hold(m, "water");
        Emotes.q(() -> m.body.swingMainHand());
        v.setFireTicks(0);
        v.getWorld().playSound(feet.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 8L);
        return true;
    }

    private void flee(Mimic m, Player tg) {
        Vindicator v = m.brain;
        Vector away = v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        away.normalize();
        Block ahead = v.getLocation().clone().add(away.getX() * 0.8, 0, away.getZ() * 0.8).getBlock();
        boolean wall = ahead.getType().isSolid(), tall = ahead.getRelative(0, 1, 0).getType().isSolid();
        if (wall && tall) { Location to = v.getLocation().add(away.multiply(8)); Emotes.q(() -> v.getPathfinder().moveTo(to, 1.4)); }
        else move(m, away, m.skill.speed() * 0.9, wall, true);
        hold(m, "sword");
        if (m.fleeUntil - m.t < 2 || v.getHealth() / maxHp(v) > 0.7) m.fleeUntil = 0;
    }

    static boolean facing(LivingEntity who, Entity at) {
        Vector look = who.getLocation().getDirection().setY(0);
        Vector to = at.getLocation().toVector().subtract(who.getLocation().toVector()).setY(0);
        return look.lengthSquared() > 1e-4 && to.lengthSquared() > 1e-4 && look.normalize().dot(to.normalize()) > 0.6;
    }

    private boolean sees(LivingEntity a, LivingEntity b) {
        try { return a.hasLineOfSight(b); } catch (RuntimeException e) { return true; }
    }

    /** The body stands where the brain is, faces its target (head + body), crouches behind its shield, looks down to splash. */
    /** The body's eye (a mannequin's), or the brain's when that isn't available. */
    Location eye(Mimic m) {
        try { return m.body.getEyeLocation(); } catch (RuntimeException e) { return m.brain.getEyeLocation(); }
    }

    private void follow(Mimic m) {
        Location l = m.brain.getLocation();
        Location to = l.clone();
        float pitch = l.getPitch();
        float wantYaw = m.yaw;
        if (m.target != null) {
            Vector d = m.target.getEyeLocation().toVector().subtract(m.brain.getEyeLocation().toVector());
            if (d.lengthSquared() > 1e-4) {
                wantYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
            }
            if (m.fleeUntil > m.t) wantYaw += 180;
        }
        if (m.drinkUntil > m.t || m.lookDownUntil > m.t) pitch = 88;
        float diff = ((wantYaw - m.yaw) % 360 + 540) % 360 - 180;
        m.yaw += Math.max(-40, Math.min(40, diff)); // a quick but not instant turn
        to.setYaw(m.yaw);
        to.setPitch(pitch);
        // MOVE the body (velocity = the gap to the brain) instead of teleporting it every tick: the game only animates a
        // body's legs / arms from real movement. A teleport only when it's fallen behind (corners, pearls, other world).
        Location bl = m.body.getLocation();
        if (bl.getWorld() != l.getWorld() || bl.distanceSquared(l) > 1.2 * 1.2) m.body.teleport(to);
        else Safe.vel(m.body, l.toVector().subtract(bl.toVector()));
        float yaw = m.yaw, p = pitch;
        Emotes.q(() -> m.body.setRotation(yaw, p));
        Emotes.q(() -> m.body.setBodyYaw(yaw));
        Pose want = m.flying || m.dive && m.slam ? Pose.FALL_FLYING : m.blockUntil > m.t && m.shieldOffUntil <= m.t ? Pose.SNEAKING : Pose.STANDING;
        Emotes.q(() -> { if (m.body.getPose() != want) m.body.setPose(want, true); });
        if (m.tag != null && m.tag.isValid()) m.tag.teleport(tagSpot(l).add(0, want == Pose.SNEAKING ? -0.3 : 0, 0));
    }

    // ---- bow and pearls

    private void shoot(Mimic m, Player tg) {
        Vindicator v = m.brain;
        Location eye = eye(m);
        double d = eye.distance(tg.getLocation());
        Vector lead = tg.getVelocity().clone().setY(0).multiply(d / 2.6);
        Vector aim = tg.getLocation().add(0, 1.1, 0).toVector().add(lead).subtract(eye.toVector());
        aim.setY(aim.getY() + d * 0.055);
        if (level(m.gear.bow, "infinity") == 0) m.arrows--;
        Arrow a = v.getWorld().spawnArrow(eye.add(aim.clone().normalize().multiply(0.8)), aim.normalize(), 2.8f, (float) m.skill.spread());
        a.setShooter(v);
        int power = level(m.gear.bow, "power");
        a.setDamage(2 + (power > 0 ? 0.5 * power + 0.5 : 0));
        if (level(m.gear.bow, "flame") > 0) a.setFireTicks(100);
        a.setCritical(true);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1, 1);
    }

    private void throwPearl(Mimic m, Location target) {
        Vindicator v = m.brain;
        m.pearlsLeft--;
        m.pearlReady = m.t + 300;
        Location eye = eye(m);
        Vector aim = target.toVector().subtract(eye.toVector());
        double d = Math.hypot(aim.getX(), aim.getZ());
        Vector vel = aim.clone().setY(0).normalize().multiply(Math.min(1.6, 0.05 * d + 0.6)).setY(Math.min(0.9, 0.2 + d * 0.022));
        EnderPearl p = v.getWorld().spawn(eye, EnderPearl.class, e -> { e.setShooter(v); e.addScoreboardTag(TAG); });
        Safe.vel(p, vel);
        pearls.add(p.getUniqueId());
        hold(m, "pearl");
        Emotes.q(() -> m.body.swingMainHand());
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 6L);
    }

    private void pearl(Mimic m, Player tg) { throwPearl(m, tg.getLocation()); }

    private void pearlAway(Mimic m, Player tg) {
        Vector away = m.brain.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        throwPearl(m, m.brain.getLocation().add(away.normalize().multiply(20)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof EnderPearl p) || !pearls.remove(p.getUniqueId())) return;
        if (!(p.getShooter() instanceof Vindicator v) || !byBrain.containsKey(v.getUniqueId())) return;
        Location at = p.getLocation();
        at.setYaw(v.getLocation().getYaw());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!v.isValid()) return;
            v.teleport(at);
            v.getWorld().playSound(at, Sound.ENTITY_PLAYER_TELEPORT, 1, 1);
            striking = true; // our own pearl damage, not its melee
            try { v.damage(1); } finally { striking = false; }
        });
        p.remove();
        e.setCancelled(true);
    }

    // ================================================================ damage

    /** Hits on the body go to the brain (knockback, armour and all). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBodyHit(EntityDamageEvent e) {
        Mimic m = byBody.get(e.getEntity().getUniqueId());
        if (m == null) return;
        e.setCancelled(true);
        if (!m.brain.isValid()) return;
        if (e instanceof EntityDamageByEntityEvent be) {
            if (be.getDamager().equals(m.brain)) return;
            m.brain.damage(e.getDamage(), be.getDamager());
        } else if (e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION && e.getCause() != EntityDamageEvent.DamageCause.FALL
                && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) m.brain.damage(e.getDamage());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrainHurt(EntityDamageByEntityEvent e) {
        // its own vanilla melee is off: only our timed strikes land
        Mimic att = byBrain.get(e.getDamager().getUniqueId());
        if (att != null && !striking) { e.setCancelled(true); return; }
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null) return;
        boolean shielding = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
        if (shielding) { // blocks melee and arrows from the front; an axe knocks the shield out for 5 s
            Vector face = m.body.getLocation().getDirection().setY(0);
            Vector from = e.getDamager().getLocation().toVector().subtract(m.brain.getLocation().toVector()).setY(0);
            if (face.lengthSquared() > 1e-4 && from.lengthSquared() > 1e-4 && face.normalize().dot(from.normalize()) > 0.2) {
                Player by = TotemLimit.playerBehind(e.getDamager());
                if (by != null && !(e.getDamager() instanceof Projectile) && by.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) {
                    m.shieldOffUntil = m.t + 100;
                    m.blockUntil = 0;
                    Emotes.q(() -> m.body.clearActiveItem());
                    m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
                } else m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1, 1);
                e.setCancelled(true);
                return;
            }
        }
        m.lastHurt = m.t;
        if (!(e.getDamager() instanceof Projectile) && m.brain.isOnGround() && random.nextDouble() < m.skill.jumpCrit()) m.jumpReset = m.t + 1;
        Emotes.q(() -> m.body.playHurtAnimation(0));
        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ENTITY_PLAYER_HURT, 1, 1);
        if (TotemLimit.playerBehind(e.getDamager()) instanceof Player p && m.target != p && p.getLocation().distanceSquared(m.brain.getLocation()) < 400) m.target = p; // turns on whoever hits it
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBrainFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m != null && (m.noFallUntil > m.t || m.clutchUntil > m.t)) e.setCancelled(true); // a mace smash / a water clutch
    }

    /** Their armour's enchantments (Protection IV...), the way vanilla applies them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProtection(EntityDamageEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null) return;
        boolean proj = e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof Projectile;
        double r = epf(m, e.getCause(), proj);
        if (r > 0) e.setDamage(e.getDamage() * (1 - r));
    }

    /** Their totems (each one pops), then death. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null || e.getFinalDamage() < m.brain.getHealth() + m.brain.getAbsorptionAmount() || m.totems <= 0) return;
        m.totems--;
        if (m.totems > 0 && !hasShield(m)) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) m.body.getEquipment().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING)); }, 3L); // re-totem
        else if (m.totems == 0 && m.gear.offhand != null && m.gear.offhand.getType() == Material.TOTEM_OF_UNDYING) m.body.getEquipment().setItemInOffHand(null);
        e.setCancelled(true);
        m.brain.setHealth(1);
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        m.brain.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        Emotes.q(() -> m.body.playEffect(EntityEffect.PROTECTED_FROM_DEATH));
        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ITEM_TOTEM_USE, 1, 1);
        m.brain.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, m.brain.getLocation().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null) return;
        e.getDrops().clear();
        e.setDroppedExp(plugin.getConfig().getInt("mimic-player.xp", 0));
        for (String s : plugin.getConfig().getStringList("mimic-player.drops")) {
            try { String[] p = s.split(":"); e.getDrops().add(new ItemStack(Material.valueOf(p[0].toUpperCase(Locale.ROOT)), p.length > 1 ? Integer.parseInt(p[1]) : 1)); }
            catch (RuntimeException ignored) { }
        }
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1, 1);
        m.body.getWorld().spawnParticle(Particle.POOF, m.body.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        try { m.body.remove(); } catch (RuntimeException ignored) { }
        if (m.tag != null && m.tag.isValid()) m.tag.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (byBrain.containsKey(e.getEntity().getUniqueId()) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true); // players only
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) { if (byBrain.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    // ================================================================ command

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("items.mimicplayer")) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "random" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("clear") || (sub.equals("remove") || sub.equals("kill") || sub.equals("delete")) && a.length > 1 && a[1].equalsIgnoreCase("all")) {
            int n = byBrain.size();
            removeAll();
            s.sendMessage(ChatColor.GREEN + "Removed " + n + " mimic" + (n == 1 ? "" : "s") + ".");
            return true;
        }
        if (sub.equals("remove") || sub.equals("kill") || sub.equals("delete")) {
            List<Mimic> gone = new ArrayList<>();
            if (a.length > 1) { // by name
                for (Mimic m : byBrain.values()) if (m.name.equalsIgnoreCase(a[1])) gone.add(m);
            } else if (s instanceof Player p) { // the one you're looking at, else the nearest within 32
                Mimic best = null; double bd = 32 * 32;
                for (Mimic m : byBrain.values()) {
                    if (m.brain.getWorld() != p.getWorld()) continue;
                    Vector to = m.brain.getLocation().add(0, 1, 0).toVector().subtract(p.getEyeLocation().toVector());
                    double d = to.lengthSquared();
                    double look = to.lengthSquared() > 1e-4 ? p.getEyeLocation().getDirection().dot(to.clone().normalize()) : 1;
                    double score = look > 0.97 ? d * 0.25 : d; // looked at counts most
                    if (score < bd) { bd = score; best = m; }
                }
                if (best != null) gone.add(best);
            }
            if (gone.isEmpty()) { s.sendMessage(ChatColor.RED + (a.length > 1 ? "No mimic of " + a[1] + "." : "No mimic near you. /mimicplayer remove <name|all>")); return true; }
            for (Mimic m : gone) {
                m.body.getWorld().spawnParticle(Particle.POOF, m.body.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
                remove(m);
            }
            s.sendMessage(ChatColor.GREEN + "Removed " + gone.size() + " mimic" + (gone.size() == 1 ? "" : "s") + (a.length > 1 ? " of " + a[1] : "") + ".");
            return true;
        }
        if (sub.equals("list")) {
            s.sendMessage(ChatColor.GOLD + "Mimics (" + byBrain.size() + "):");
            for (Mimic m : byBrain.values()) s.sendMessage(ChatColor.GRAY + " " + m.name + " (" + m.skill.id() + ") at " + Where.spot(m.brain.getLocation()) + ", "
                    + Math.round(m.brain.getHealth()) + " hp, " + m.style + ", " + m.buffs.size() + " buff pots, " + m.debuffs.size() + " debuff pots, " + m.apples + " gaps, " + m.gapples + " god apples, " + m.pots + " pots, " + m.pearlsLeft + " pearls, " + m.totems + " totems");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Run it in game (it spawns where you look)."); return true; }
        int count = 1;
        Skill skill = SKILLS.getOrDefault(plugin.getConfig().getString("mimic-player.skill", "normal").toLowerCase(Locale.ROOT), SKILLS.get("normal"));
        for (int i = 1; i < a.length; i++) {
            Skill k = SKILLS.get(a[i].toLowerCase(Locale.ROOT));
            if (k != null) { skill = k; continue; }
            try { count = Math.max(1, Math.min(10, Integer.parseInt(a[i]))); } catch (NumberFormatException ignored) { }
        }
        org.bukkit.block.Block hit;
        try { hit = p.getTargetBlockExact(30); } catch (RuntimeException ex) { hit = null; }
        Location at = hit != null ? hit.getLocation().add(0.5, 1, 0.5) : p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
        at.setYaw(p.getLocation().getYaw() + 180);
        for (int i = 0; i < count; i++) {
            OfflinePlayer who = sub.equals("random") ? randomPlayer(p) : Bukkit.getOfflinePlayer(a[0]);
            Mimic m = spawn(at.clone().add(random.nextGaussian() * 1.2, 0, random.nextGaussian() * 1.2), who, skill);
            s.sendMessage(ChatColor.GREEN + "Spawned a mimic of " + ChatColor.WHITE + m.name + ChatColor.GREEN + " (" + skill.id() + "). /mimicplayer clear removes them.");
        }
        return true;
    }

    private OfflinePlayer randomPlayer(Player not) {
        OfflinePlayer[] all = Bukkit.getOfflinePlayers();
        List<OfflinePlayer> pick = new ArrayList<>();
        for (OfflinePlayer o : all) if (o.getName() != null && !o.getUniqueId().equals(not.getUniqueId()) && !o.isOnline()) pick.add(o);
        if (pick.isEmpty()) for (OfflinePlayer o : all) if (o.getName() != null) pick.add(o);
        return pick.isEmpty() ? not : pick.get(random.nextInt(pick.size()));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>(List.of("random", "remove", "clear", "list"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("kill"))) {
            List<String> out = new ArrayList<>(List.of("all"));
            for (Mimic m : byBrain.values()) if (!out.contains(m.name)) out.add(m.name);
            return out;
        }
        if (a.length == 2) return List.of("1", "3", "5", "easy", "normal", "hard", "godlike");
        if (a.length == 3) return new ArrayList<>(SKILLS.keySet());
        return List.of();
    }
}
